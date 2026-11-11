package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcManualWindowStore;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.RunMapper;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.PauseCommand;
import com.labos.kernel.workflow.PauseCoordinator;
import com.labos.kernel.workflow.PauseManager;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.WorkflowService;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.labos.kernel.persistence.QJson.*;
import java.sql.Connection;

/**
 * 执行域 Service（人工窗口 / 隔离 / 暂停恢复 / 值守 / 忽略）。
 *
 * <p><b>为什么要有这一层</b>：Controller 只做 HTTP 解析与审计，业务必须归位。
 * 拆分时发现两个反模式：
 * <ol>
 *   <li>Controller 直接 new 工具类 16 处（PauseCoordinator / PauseManager /
 *       JdbcManualWindowStore / RuntimeHold），谁都能 new 出第二条执行路径；</li>
 *   <li>{@code RunActionController.updateAttendance} 里有裸 SQL —— 数据访问
 *       进了 Web 层。已下沉 {@code RunMapper.updateAttendance}。</li>
 * </ol>
 *
 * <p>返回值统一 {@link Result}(httpStatus, json)：暂停/恢复的内部异常**保持原样
 * 返回 200 + {"ok":false}**（前端按 ok 字段判断，不擅自改成 500）；
 * 状态码只表达「请求是否被受理」（409 已处置 / 404 不存在 / 400 参数错）。
 *
 * <p>暂停/恢复/忽略全部走 {@link PauseCoordinator}：PauseManager 只把 run 置回
 * queued，coordinator 内部才真的续跑。本类只做参数到领域对象的转换，
 * **不重写暂停语义**。
 */
@Service
public class RunService {

    /** 一段响应：HTTP 状态码 + JSON 体（调用方直接回给前端，不再加工）。 */
    /**
     * 一段响应：HTTP 状态码 + JSON 体 + 审计参数。
     *
     * <p>审计（action 之外的两个自由字段 entityKey/detail）是 Service 计算出来的
     * 业务事实（如 {@code "scope=" + scope}、{@code Jsons.obj("recordId", ...)}），
     * 必须由 Service 带出来给 Controller 落审计，Controller 不能自己编。
     */
    public record Result(int status, String json, Long auditEntityId,
                         String auditKey, String auditDetail) {
        public static Result ok(String json) {
            return Result.of(200, json, null, null, null);
        }
        public static Result of(int status, String json,
                                Long auditEntityId, String auditKey, String auditDetail) {
            return new Result(status, json, auditEntityId, auditKey, auditDetail);
        }
    }

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final WorkflowService workflows;
    private final ExperimentScheduler scheduler;
    private final RunMapper runMapper;
    private final com.labos.kernel.persistence.WorkflowWebMapper workflowWebMapper;
    private final PauseCoordinatorFactory pauseFactory;

    public RunService(DbConfig db, WorkflowService workflows,
                      ExperimentScheduler scheduler, RunMapper runMapper,
                      com.labos.kernel.persistence.WorkflowWebMapper workflowWebMapper,
                      PauseCoordinatorFactory pauseFactory) {
        this.db = db;
        this.workflows = workflows;
        this.scheduler = scheduler;
        this.runMapper = runMapper;
        this.workflowWebMapper = workflowWebMapper;
        this.pauseFactory = pauseFactory;
    }

    // ================================================================ 人工窗口

    public String manualWindows() throws java.sql.SQLException {
        JdbcManualWindowStore store = new JdbcManualWindowStore(db, TENANT);
        store.expireOverdue();
        List<String> items = new ArrayList<>();
        for (JdbcManualWindowStore.Window w : store.pending(20)) {
            items.add(Jsons.obj(
                    "windowId", w.id(),
                    "runId", w.runId(),
                    "windowKey", w.windowKey(),
                    "errorCode", w.errorCode(),
                    "nodeKey", w.nodeKey(),
                    "strategy", w.strategy(),
                    "defaultAction", w.defaultAction(),
                    "allowedActions", new Jsons.Raw(w.allowedActions()),
                    "decisionReason", w.decisionReason(),
                    "status", w.status(),
                    "openedAt", w.openedAt(),
                    "timeoutAt", w.timeoutAt()));
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 处置人工窗口。非法 action → 400；窗口不存在/已处置 → 409。 */
    public Result decideWindow(long windowId, String body, String requestId, String operator)
            throws java.sql.SQLException {
        String action;
        try {
            action = com.labos.kernel.recovery.ManualWindow.normalizeAction(
                    com.labos.kernel.api.ApiServer.jsonField(body, "action"));
        } catch (IllegalArgumentException e) {
            return Result.of(400, Jsons.obj("ok", false, "error", e.getMessage()), null, null, null);
        }
        JdbcManualWindowStore store = new JdbcManualWindowStore(db, TENANT);
        store.expireOverdue();
        JdbcManualWindowStore.Window w = store.byId(windowId);
        if (w == null || !"pending".equals(w.status())) {
            return Result.of(409, Jsons.obj("ok", false, "error", "窗口已被处置或已超时"), null, null, null);
        }
        if (!store.decide(windowId, action, operator,
                com.labos.kernel.api.ApiServer.jsonField(body, "note"))) {
            return Result.of(409, Jsons.obj("ok", false, "error", "窗口已被处置或已超时"), null, null, null);
        }
        // MANUAL = 仍交人工：保持 run 的 waiting_human，不触发续跑。
        // 其余动作复用既有 resume 路径（CONTINUE 会真的 resumeAsync）。
        String resume = "{\"ok\":true,\"paused\":true}";
        if (!"MANUAL".equals(action)) {
            String a = switch (action) {
                case "RETRY" -> "retry";
                case "SKIP" -> "skip";
                case "ABORT" -> "abort";
                default -> "continue";
            };
            resume = resumeRun(w.runId(), "action=" + a, body, requestId);
        }
        // L-01：人工介入落证（确认人/时间/板样本/交接点/动作/结果）
        long recordId = recordIntervention(windowId, w.runId(), null, operator, action,
                        field(body, "plateBarcode"), field(body, "sampleId"),
                        field(body, "fromLocation"), field(body, "toLocation"),
                        "MANUAL".equals(action) ? "waiting" : "resolved",
                        field(body, "note"));
        String json = Jsons.obj(
                "ok", true,
                "windowId", windowId,
                "runId", w.runId(),
                "action", action,
                "interventionRecordId", recordId,
                "resume", new Jsons.Raw(resume));
        return Result.of(200, json, windowId, action + " run=" + w.runId(), null);
    }

    // ================================================================ 隔离放行

    public Result releaseQuarantine(long runId, String body, String operator, long actorId)
            throws java.sql.SQLException {
        String note = field(body, "note");
        boolean ok = StartupRecovery.resolveQuarantine(db, TENANT, runId, actorId, note);
        if (!ok) {
            return Result.of(409, Jsons.obj("ok", false, "error", "该 run 不在隔离状态或已处置"), null, null, null);
        }
        long recordId = recordIntervention(0L, runId, null, operator,
                "QUARANTINE_RELEASE", field(body, "plateBarcode"),
                field(body, "sampleId"), field(body, "fromLocation"),
                field(body, "toLocation"), "resolved", note);
        return Result.of(200, Jsons.obj("ok", true, "runId", runId,
                "interventionRecordId", recordId), runId, note,
                Jsons.obj("recordId", recordId));
    }

    // ================================================================ 命令 outbox

    public Result replayOutbox(long id) throws java.sql.SQLException {
        boolean ok = replayCommandOutbox(id);
        if (!ok) {
            return Result.of(409, Jsons.obj("ok", false, "error", "事件不存在或不是 DELIVERED"), null, null, null);
        }
        return Result.ok(Jsons.obj("ok", true, "id", id, "state", "PENDING"));
    }

    // ================================================================ 搬运确认

    public Result confirmTransfer(long id, String body, String operator)
            throws java.sql.SQLException {
        boolean ok = confirmTransfer(id, field(body, "evidenceType"),
                operator, field(body, "note"));
        if (!ok) {
            return Result.of(409, Jsons.obj("ok", false, "error", "搬运事务不存在或已确认"), null, null, null);
        }
        return Result.of(200, Jsons.obj("ok", true, "id", id, "status", "CONFIRMED"),
                id, field(body, "evidenceType"), null);
    }

    // ================================================================ 板位证据 / 盘点

    public Result recordEvidence(String body, String operator) throws java.sql.SQLException {
        String plate = field(body, "plateBarcode");
        if (plate == null || plate.isBlank()) {
            return Result.of(400, Jsons.obj("error", "需要 plateBarcode"), null, null, null);
        }
        Long resourceId = com.labos.kernel.api.ApiServer.jsonLong(body, "resourceId");
        Long runId = com.labos.kernel.api.ApiServer.jsonLong(body, "runId");
        long id = recordPositionEvidence(plate,
                resourceId, field(body, "evidenceType"), runId,
                operator, field(body, "note"));
        return Result.of(200, Jsons.obj("id", id, "plateBarcode", plate),
                id, plate, Jsons.obj("type", field(body, "evidenceType")));
    }

    /** 人工盘点：报实际位置。actualLocation 为 "LOST" 表示确认丢失。 */
    public String locatePlate(String barcode, String body) {
        try {
            String loc = null;
            if (body != null) {
                int i = body.indexOf("\"actualLocation\"");
                if (i >= 0) {
                    int colon = body.indexOf(':', i);
                    if (colon >= 0) {
                        String rest = body.substring(colon + 1).trim();
                        int comma = rest.indexOf(',');
                        if (comma > 0) {
                            rest = rest.substring(0, comma);
                        }
                        int brace = rest.indexOf('}');
                        if (brace > 0) {
                            rest = rest.substring(0, brace);
                        }
                        loc = unquote(rest.trim());
                    }
                }
            }
            boolean ok = new PauseManager(db, TENANT).locatePlate(barcode, loc, "api", null);
            return Jsons.obj("ok", ok, "barcode", barcode,
                    "actualLocation", loc == null ? "LOST" : loc);
        } catch (RuntimeException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    // ================================================================ 暂停 / 恢复

    /** 暂停一个执行。kind 可选 paused_fault / waiting_human / paused_unknown。 */
    public String pauseRun(long runId, String queryString, String requestId) {
        try {
            Map<String, String> q = queryParams(queryString);
            PauseCoordinator.WorkflowPause r = pauseFactory.forTenant(TENANT)
                    .pauseWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.PAUSE,
                            q.get("kind"), q.get("reason"), requestId), q.get("nodeKey"));
            return Jsons.obj("ok", true, "runId", runId,
                    "pauseKind", r.pauseKindCode(), "releasedResources", r.releasedResources());
        } catch (RuntimeException | java.sql.SQLException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 恢复一个执行。
     *
     * <p>body 可以带 corrections：{@code {"corrections":{"PL-001":"ID1183-L2"}}}。
     */
    public String resumeRun(long runId, String queryString, String bodyText, String requestId) {
        try {
            Map<String, String> q = queryParams(queryString);
            Map<String, String> corrections = parseCorrections(bodyText);
            PauseManager.ResumeResult r = pauseFactory.forTenant(TENANT)
                    .resumeWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.RESUME,
                            q.get("action"), null, requestId), corrections);
            return r.toJsonString();
        } catch (RuntimeException | java.sql.SQLException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    // ================================================================ 值守模式

    /** 切换运行中的 run 的值守模式；已结束的 run 不受影响（0 行）。 */
    public Result attendance(long runId, String attendance) throws java.sql.SQLException {
        int updated = runMapper.updateAttendance(TENANT, runId, attendance);
        return Result.ok(Jsons.obj(
                "runId", runId,
                "attendance", attendance,
                "updated", updated,
                "note", updated == 0 ? "该 run 已结束，模式未改变" : "已切换"));
    }

    // ================================================================ 忽略（单个 / 批量）

    public Result dismissBatch(String body) throws java.sql.SQLException {
        List<Long> ids = new ArrayList<>();
        // **只取 "runIds":[...] 里的数字**，别把 body 里其它数字也当 id。
        // 没有这个 key = 全部忽略（"清空队列"），这是显式语义：
        // {} 和 {"all":true} 都走全量，带了 runIds 就只处理这些。
        java.util.regex.Matcher arr = java.util.regex.Pattern
                .compile("\"runIds\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(body == null ? "" : body);
        if (arr.find()) {
            java.util.regex.Matcher im = java.util.regex.Pattern
                    .compile("\\d+").matcher(arr.group(1));
            while (im.find()) {
                ids.add(Long.parseLong(im.group()));
            }
        }
        String kind = field(body, "kind");
        Integer before = com.labos.kernel.api.ApiServer.jsonInt(body, "beforeHours");
        int n = pauseFactory.forTenant(TENANT)
                .dismissBatchWorkflowRuns(ids, kind, before);
        return Result.of(200, Jsons.obj("dismissed", n), null,
                ids.isEmpty() ? "filter kind=" + kind + ",beforeHours=" + before
                        : "count=" + ids.size(), null);
    }

    public Result dismissOne(long runId, String requestId) throws java.sql.SQLException {
        boolean done = pauseFactory.forTenant(TENANT)
                .dismissWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.DISMISS,
                        null, null, requestId));
        return Result.of(done ? 200 : 404, Jsons.obj("runId", runId, "dismissed", done), runId, null, null);
    }

    // ================================================================ 跳过失败节点并继续

    /**
     * **跳过这个失败节点，继续往下跑**。
     *
     * <p>为什么需要：节点转人工（manual）之后，页面原有的动作只有两个 ——
     * 「恢复」（把 run 重新排队）与「忽略」（标记为已忽略、不计失败、从队列消失），
     * **两个都不会让流程从那个节点往下跑**。现场遇到"这一步就是过不去，
     * 后面还得继续"就只能干等。</p>
     *
     * <p>做法：把该节点标成「人工确认跳过」（status='skipped' +
     * input.skip_confirmed=true）⇒ 续跑时按"已完成"处理（不重做、不阻断下游），
     * 但状态留痕 ≠ completed（界面能看出它是被跳过的，不是做成了）。</p>
     *
     * <p>与系统自动的 skipped 的区别：自动 skipped 的语义是
     * 「板位不可信、必须停下来问人」（评审指引 D9）⇒ 仍会转人工；
     * 这里差一个"人确认过"。</p>
     */
    public Result skipNode(long runId, String nodeKey, String requestId)
            throws java.sql.SQLException {
        if (nodeKey == null || nodeKey.isBlank()) {
            return Result.of(400, Jsons.obj("ok", false, "error", "缺少 nodeKey"), null, null, null);
        }
        int n = runMapper.markNodeSkippedConfirmed(TENANT, runId, nodeKey.trim());
        if (n == 0) {
            return Result.of(404, Jsons.obj("ok", false,
                    "error", "节点不存在，或它不是失败待处理的状态（只能跳过 manual / skipped 的节点）"),
                    runId, null, null);
        }
        // 跳过之后要让流程**真的往下走**：删掉其余未完成的节点行并重跑图。
        // **必须一并释放挡住派发的现场扣留**：节点失败时抬的是**岛级/实验级** Hold，
        // 而扣留是**全局闸门** —— 不清掉它，续跑时设备仍被挡住 ⇒ 下游节点
        // 照样拿不到设备 ⇒ 又转人工（跳过的效果就白费了）。
        // 清扣留的动作与「恢复」一致（见 RunControl.resumeTx）。
        Long expId = runMapper.selectExperimentIdOfRun(TENANT, runId);
        if (expId != null) {
            try {
                com.labos.kernel.workflow.RuntimeHold holds =
                        new com.labos.kernel.workflow.RuntimeHold(db, TENANT);
                var covered = holds.coveringForExperimentChecked(expId);
                if (covered != null) {
                    holds.clear(com.labos.kernel.workflow.RuntimeHold.Scope.of(covered.scope()),
                            covered.scopeId(), null, "跳过失败节点后继续执行");
                }
            } catch (RuntimeException | java.sql.SQLException ex) {
                return Result.of(500, Jsons.obj("ok", false,
                        "error", "跳过已标记，但释放现场扣留失败：" + ex.getMessage()),
                        runId, null, null);
            }
        }
        try {
            workflows.resumeAsync(runId);
        } catch (RuntimeException ex) {
            return Result.of(500, Jsons.obj("ok", false,
                    "error", "跳过已标记，但续跑启动失败：" + ex.getMessage()),
                    runId, null, null);
        }
        return Result.ok(Jsons.obj("runId", runId, "nodeKey", nodeKey.trim(),
                "skipped", true, "message", "已跳过该节点并继续执行"));
    }

    // ================================================================ 只读

    public String pausedRuns() throws java.sql.SQLException {
        return keyValueArrayJson(new PauseManager(db, TENANT).pausedRuns());
    }

    public String unknownPlates() throws java.sql.SQLException {
        return keyValueArrayJson(new PauseManager(db, TENANT).unknownPlates());
    }

    /**
     * 命令级台账（2026-11-11）：这次 run 下发过哪些命令、什么参数、
     * 什么时候发出、设备什么时候回的。
     *
     * <p>为什么要有：节点表只说"这一步成功了"，说不出"给设备发了什么、
     * 什么时候发的、等了多久" ⇒ 现场排查设备问题时要翻库/翻日志才看得见。
     */
    public String runCommandsJson(long runId) throws java.sql.SQLException {
        java.util.List<java.util.Map<String, Object>> rows =
                com.labos.kernel.persistence.Mappers.get(
                        com.labos.kernel.persistence.RunMapper.class)
                        .selectRunCommands(TENANT, runId);
        return Jsons.obj("runId", runId, "total", rows.size(),
                // run 状态：前端据此决定台账要不要继续轮询（跑完就停）
                "status", runMapper.selectRunStatus(TENANT, runId),
                "commands", new Jsons.Raw(keyValueArrayJson(rows)));
    }

    public String holds() throws java.sql.SQLException {
        RuntimeHold.HoldView view = new RuntimeHold(db, TENANT).activeView();
        boolean unknown = view.state() == RuntimeHold.HoldState.UNKNOWN;
        return Jsons.obj(
                "anyActive", !view.holds().isEmpty(),
                // ADR-PAUSE-010：读不到扣留(=UNKNOWN)会让调度器 fail-closed 阻断新派发，
                // 必须让运维在界面上看得见，而不是只落日志。
                "unknown", unknown,
                "unknownReason", unknown
                        ? "读取现场扣留失败：调度器已按 fail-closed 阻断新实验派发，"
                        + "请检查数据库连通性，确认后再放行。"
                        : null,
                "holds", new Jsons.Raw(holdsJson(view.holds())));
    }

    /** 实验批次视图（D8/D11：attempt + 批次汇总 + 实验 D6 聚合）。 */
    public String batchesJson(long experimentId) throws java.sql.SQLException {
        return new com.labos.kernel.workflow.BatchOrchestrator(db, TENANT, workflows)
                .batchesJson(experimentId);
    }

    public String recoverable() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (var o : StartupRecovery.listRecoverable(db, TENANT)) {
            items.add(Jsons.obj(
                    "runId", o.runId(),
                    "runNo", o.runNo(),
                    "completedNodes", o.completedNodes(),
                    "totalNodes", o.totalNodes(),
                    "failedNodes", o.failedNodes()));
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }

    // ================================================================ 运行期变量

    /**
     * 这条 run 的**运行期变量表**（当前值 + 声明情况）。
     *
     * <p>为什么要把「声明」和「当前值」分开列：
     * <ul>
     *   <li>声明了但还没值的变量 —— 页面必须看得见（"这条流程要求这个参数"），
     *       否则现场根本不知道少了什么；</li>
     *   <li>运行期才出现的变量（{@code __setVar} 写出来的）—— 也要看得见
     *       （"这个值是哪来的"）。</li>
     * </ul>
     *
     * <p>当前值的来源就是 {@code run.input->'vars'}：启动时写进去的
     * （流程级初值 + 请求/实验传入的）、以及运行中 {@code __setVar} 改的。
     */
    public String runVariablesJson(long runId) throws java.sql.SQLException {
        java.util.List<java.util.Map<String, Object>> rows =
                runMapper.selectRunVars(TENANT, runId);
        Long wfId = runMapper.selectRunWorkflowId(TENANT, runId);
        String status = runMapper.selectRunStatus(TENANT, runId);
        if (status == null) {
            return null;   // run 不存在
        }
        // 当前值：name -> value
        java.util.Map<String, Object> vars = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> r : rows) {
            vars.put(String.valueOf(r.get("name")), r.get("value"));
        }

        // 声明清单（流程级变量）：name -> (type, defaultValue)
        java.util.Map<String, java.util.Map<String, Object>> declared =
                new java.util.LinkedHashMap<>();
        if (wfId != null) {
            for (java.util.Map<String, Object> row
                    : workflowWebMapper.selectWorkflowVariables(TENANT, wfId)) {
                declared.put(String.valueOf(row.get("name")), row);
            }
        }

        List<String> items = new ArrayList<>();
        // 先列声明过的（含"还没值"的）
        for (java.util.Map.Entry<String, java.util.Map<String, Object>> e : declared.entrySet()) {
            java.util.Map<String, Object> d = e.getValue();
            Object cur = vars.get(e.getKey());
            items.add(Jsons.obj(
                    "name", e.getKey(),
                    "type", d.get("type"),
                    "declared", true,
                    "defaultValue", d.get("defaultValue"),
                    "value", cur,
                    "hasValue", cur != null,
                    "description", d.get("description")));
        }
        // 再列运行期多出来的（__setVar 写出来的、请求传入的）
        for (java.util.Map.Entry<String, Object> e : vars.entrySet()) {
            if (declared.containsKey(e.getKey())) {
                continue;
            }
            items.add(Jsons.obj(
                    "name", e.getKey(),
                    "type", null,
                    "declared", false,
                    "defaultValue", null,
                    "value", e.getValue(),
                    "hasValue", e.getValue() != null,
                    "description", null));
        }
        return Jsons.obj(
                "runId", runId,
                "workflowId", wfId,
                "status", status,
                "editable", "running".equals(status) || "paused".equals(status)
                        || "queued".equals(status),
                "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 改一个运行期变量的值。
     *
     * <p>这个动作的语义：**正在跑的流程下一次读变量时看到新值**。
     * {@code __wait} 节点每轮都会重读 {@code run.input->'vars'}
     * （见 {@code GraphRunner.withVariableRefresher}）⇒ 改值能**唤醒**一个
     * 正在等条件的节点 —— 这是现场"人工放行"的正规做法。
     *
     * <p>只允许改**没结束**的 run：已完成的 run 改了也没有执行体会再读它，
     * 页面却会显示成"改了" ⇒ 误导现场。</p>
     */
    public Result setRunVariable(long runId, String name, String value) {
        if (name == null || name.isBlank()) {
            return Result.of(400, Jsons.obj("ok", false, "error", "变量名不能为空"), null, null, null);
        }
        String n = name.trim();
        if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return Result.of(400, Jsons.obj("ok", false,
                    "error", "变量名只能用字母、数字、下划线，且不能以数字开头：" + n), null, null, null);
        }
        String status = runMapper.selectRunStatus(TENANT, runId);
        if (status == null) {
            return Result.of(404, Jsons.obj("ok", false, "error", "run 不存在"), null, null, null);
        }
        if (!"running".equals(status) && !"paused".equals(status)
                && !"queued".equals(status)) {
            return Result.of(409, Jsons.obj("ok", false,
                    "error", "这条执行已经结束（" + status + "），改变量不会再被读到"),
                    null, null, null);
        }
        int updated = runMapper.setRunVar(TENANT, runId, n, value == null ? "" : value);
        return Result.of(200, Jsons.obj(
                "ok", updated > 0,
                "runId", runId,
                "name", n,
                "value", value == null ? "" : value), null, null, null);
    }

    // ================================================================ 内部

    /** 取 body 里的字符串字段（Controller 也要用 ⇒ public）。 */
    public static String field(String body, String key) {
        return com.labos.kernel.api.ApiServer.jsonField(body, key);
    }

    private static String holdsJson(List<Map<String, Object>> list) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> m : list) {
            items.add(Jsons.obj(
                    "id", m.get("id"),
                    "scope", m.get("scope"),
                    "scopeId", m.get("scopeId"),
                    "kind", m.get("kind"),
                    "reason", m.get("reason"),
                    "heldAt", m.get("heldAt"),
                    "heldBy", m.get("heldBy")));
        }
        return Jsons.arr(items);
    }

    private static String keyValueArrayJson(List<Map<String, Object>> rows) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            List<String> kvs = new ArrayList<>();
            m.forEach((k, v) -> kvs.add(Jsons.quote(k) + ":" + jsonVal(v)));
            items.add("{" + String.join(",", kvs) + "}");
        }
        return Jsons.arr(items);
    }

    private static String jsonVal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Iterable<?> it) {
            List<String> parts = new ArrayList<>();
            for (Object o : it) {
                parts.add(jsonVal(o));
            }
            return "[" + String.join(",", parts) + "]";
        }
        return Jsons.quote(String.valueOf(v));
    }

    /** 从 body 里解析 {"corrections":{"板号":"位置"}}。 */
    private static Map<String, String> parseCorrections(String bodyText) {
        Map<String, String> m = new LinkedHashMap<>();
        if (bodyText == null || bodyText.isBlank()) {
            return m;
        }
        int ci = bodyText.indexOf("\"corrections\"");
        if (ci < 0) {
            return m;
        }
        int open = bodyText.indexOf('{', ci);
        if (open < 0) {
            return m;
        }
        int depth = 0;
        int close = -1;
        for (int i = open; i < bodyText.length(); i++) {
            char ch = bodyText.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close <= open) {
            return m;
        }
        String inner = bodyText.substring(open + 1, close);
        for (String pair : inner.split(",")) {
            int colon = pair.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String k = unquote(pair.substring(0, colon).trim());
            String v = unquote(pair.substring(colon + 1).trim());
            if (!k.isEmpty()) {
                m.put(k, v);
            }
        }
        return m;
    }

    /** 简单 query 解析：a=1&b=2 → {a:1, b:2} */
    private static Map<String, String> queryParams(String queryString) {
        Map<String, String> m = new LinkedHashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return m;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (UnsupportedEncodingException ignore) {
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return m;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"");
    }

    public String recentRuns(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : runMapper.selectRecentRuns(TENANT, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "runNo", row.get("runNo"),
                    "runType", row.get("runType"),
                    "attendance", row.get("attendance"),
                    "status", row.get("status"),
                    "priority", mint(row, "priority"),
                    "nodeCount", mint(row, "nodeCount"),
                    "actualStart", mstr(row, "actualStart"),
                    "actualEnd", mstr(row, "actualEnd"),
                    "createdAt", mstr(row, "createdAt")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /** 单个 run 的详情：基础信息 + 节点 + 最近事件。 */
    public String runDetail(long runId) throws java.sql.SQLException {
        java.util.Map<String, Object> row = runMapper.selectRunDetail(TENANT, runId);
        if (row == null) {
            return null;
        }
        String base = Jsons.obj(
                "id", row.get("id"),
                "runNo", row.get("runNo"),
                "runType", row.get("runType"),
                "attendance", row.get("attendance"),
                "status", row.get("status"),
                "priority", mint(row, "priority"),
                "pauseKind", row.get("pauseKind"),
                "pauseReason", row.get("pauseReason"),
                "pauseNodeKey", row.get("pauseNodeKey"),
                "actualStart", mstr(row, "actualStart"),
                "actualEnd", mstr(row, "actualEnd"),
                "createdAt", mstr(row, "createdAt"),
                "input", mraw(row, "input"));

        List<String> nodes = new ArrayList<>();
        // 内置逻辑节点的一句人话（"等待 gate = open（已等 12 秒）"）
        // —— 没有它，光看节点名 "__wait" 根本不知道它在等什么。
        java.util.Map<String, String> builtinNotes = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> bn : runMapper.selectBuiltinNotes(TENANT, runId)) {
            builtinNotes.put(String.valueOf(bn.get("nodeKey")), (String) bn.get("note"));
        }
        for (java.util.Map<String, Object> n : runMapper.selectRunDetailNodes(runId)) {
            nodes.add(Jsons.obj(
                    "id", n.get("id"),
                    "nodeKey", n.get("nodeKey"),
                    "name", n.get("name"),
                    "status", n.get("status"),
                    "attempt", mint(n, "attempt"),
                    "resourceId", n.get("resourceId"),
                    "resourceCode", n.get("resourceCode"),
                    "capabilityId", n.get("capabilityId"),
                    "estimateSec", mint(n, "estimateSec"),
                    "level", mint(n, "level"),
                    "parentNodeId", n.get("parentNodeId"),
                    "loopIndex", mint(n, "loopIndex"),
                    "stepSeq", n.get("stepSeq"),
                    "reason", n.get("reason"),
                    "note", builtinNotes.get(String.valueOf(n.get("nodeKey"))),
                    "errorCode", n.get("errorCode")));
        }

        List<String> events = new ArrayList<>();
        for (java.util.Map<String, Object> e : runMapper.selectRunEvents(runId)) {
            events.add(Jsons.obj(
                    "at", mstr(e, "at"),
                    "type", e.get("type"),
                    "payload", mraw(e, "payload")));
        }

        return Jsons.obj(
                "run", raw(base),
                "nodes", raw(Jsons.arr(nodes)),
                "events", raw(Jsons.arr(events)));
    }

    /**
     * Process Status View：正在跑的 run 卡在哪。
     *
     * <p>回答"为什么不动"——只有日志、没有这个视图时很难定位。
     */
    public String processStatusJson() throws java.sql.SQLException {
        // 优先用内存态：执行期间 run_node 还没落库，查库看不到"卡在哪"
        List<com.labos.kernel.workflow.RunMonitor.Live> live =
                com.labos.kernel.workflow.RunMonitor.INSTANCE.running();
        if (!live.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (var l : live) {
                out.add(Jsons.obj(
                        "runId", l.runId,
                        "runNo", l.runNo,
                        "attendance", l.attendance,
                        "startedAt", l.startedAt.toString(),
                        "total", l.total,
                        "done", l.done,
                        "manual", l.manual,
                        "current", new Jsons.Raw(Jsons.obj(
                                "nodeKey", l.nodeKey,
                                "name", l.nodeName,
                                "status", l.nodeStatus,
                                "resourceId", l.resourceId,
                                "reason", l.reason,
                                "lastEvent", l.lastEvent))));
            }
            return Jsons.obj("running", out.size(),
                    "source", "memory",
                    "items", new Jsons.Raw(Jsons.arr(out)));
        }

        List<String> runs = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : runMapper.selectRunningProcessStatus(TENANT)) {
            long runId = ((Number) row.get("id")).longValue();
            runs.add(Jsons.obj(
                    "runId", runId,
                    "runNo", row.get("runNo"),
                    "attendance", row.get("attendance"),
                    "startedAt", String.valueOf(row.get("actualStart")),
                    "total", mint(row, "total"),
                    "done", mint(row, "done"),
                    "manual", mint(row, "manual"),
                    "failed", mint(row, "failed"),
                    "current", new Jsons.Raw(currentNodeJson(runId))));
        }
        return Jsons.obj("running", runs.size(),
                "items", new Jsons.Raw(Jsons.arr(runs)));
    }

    /** 实验执行历史（最近 20 次）。 */
    /**
     * 一次实验执行的**逐步**明细：哪些跑了、哪些没跑。
     *
     * <p>为什么不能只查 {@code experiment_run_step}：那张表是**步骤开始时**才插行，
     * 前面一步失败/暂停后，后面的步骤**根本没行** —— 只查表会让人以为"实验只有 1 步"。
     * 所以这里按 {@code experiment_process × loop_count} 展开出"计划步骤"，
     * 再和实际执行行做左连接，没行的一律标成 {@code pending}（未执行）。
     */
    /**
     * 一次执行的**流程图**：BPMN XML + 每个节点的实际状态。
     *
     * <p>为什么要这个：运行详情只有一张表，看不出"图上有哪几步、卡在哪一步"。
     * 这里把 {@code workflow.graph.xlm} 和 {@code run_node} 合到一起，
     * 前端用 bpmn-js 渲染后给节点染色。
     *
     * <p>两个细节：
     * <ul>
     *   <li>{@code node_key} 就是 BPMN 元素 id，可以直接对上；</li>
     *   <li>外壳建的是 {@code pending}，执行到哪个节点就改成 {@code running}、跑完改
     *       {@code completed}（见 {@code GraphRunner.withNodeStateRecorder}），
     *       所以运行期间能直接读到「已完成 / 执行中 / 还没轮到」三段。残留的
     *       {@code running}（崩溃/中断）在非运行态统一折算成 {@code pending}。</li>
     * </ul>
     */
    public String runGraphJson(long runId) throws java.sql.SQLException {
        java.util.Map<String, Object> runRow = runMapper.selectRunWorkflow(TENANT, runId);
        if (runRow == null) {
            throw new IllegalArgumentException("执行不存在: " + runId);
        }
        long workflowId = ((Number) runRow.get("workflowId")).longValue();
        String runStatus = String.valueOf(runRow.get("status"));
        String xml = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.WorkflowWebMapper.class)
                .selectWorkflowXml(TENANT, workflowId);

        // 只有真的在跑，"running" 才是"执行中"；暂停/结束后的 running 都是没执行到
        boolean live = "running".equals(runStatus);

        // 计划节点（图上的 task）——没跑过的也要出现在结果里
        java.util.Map<String, String> planName = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> pn
                : runMapper.selectWorkflowTaskNames(TENANT, workflowId)) {
            planName.put(String.valueOf(pn.get("nodeKey")), (String) pn.get("name"));
        }

        java.util.Map<String, String[]> actual = new java.util.HashMap<>();  // key → [status,name,err]
        for (java.util.Map<String, Object> an
                : runMapper.selectRunNodeStates(TENANT, runId)) {
            actual.put(String.valueOf(an.get("nodeKey")), new String[]{
                    (String) an.get("status"), (String) an.get("name"),
                    (String) an.get("errorCode")});
        }
        // 内置逻辑节点的一句人话（"等待 gate = open（已等 12 秒）"）——
        // 图上那个节点正在跑时，hover 能看出它在等什么。
        java.util.Map<String, String> builtinNotes = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> bn : runMapper.selectBuiltinNotes(TENANT, runId)) {
            builtinNotes.put(String.valueOf(bn.get("nodeKey")), (String) bn.get("note"));
        }

        List<String> nodes = new ArrayList<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(planName.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            String[] a = actual.get(key);
            String st = a == null ? "pending" : a[0];
            // 跑完的单子里残留 running = 压根没执行
            if (!live && "running".equals(st)) {
                st = "pending";
            }
            nodes.add(Jsons.obj(
                    "nodeKey", key,
                    "name", a == null ? planName.get(key) : a[1],
                    "status", st,
                    "note", builtinNotes.get(key),
                    "errorCode", a == null ? null : a[2]));
        }

        return Jsons.obj("runId", runId, "workflowId", workflowId,
                "runStatus", runStatus,
                "xml", xml,
                "nodes", new Jsons.Raw(Jsons.arr(nodes)));
    }

    /**
     * 甘特数据：一个 run 的节点/步骤时间轴。
     *
     * <p>返回相对毫秒（相对 run 开始），前端不用处理时区。
     */
    public String timelineJson(long runId) throws java.sql.SQLException {
        java.sql.Timestamp runStart = runMapper.selectRunActualStart(TENANT, runId);
        java.time.Instant start = runStart == null ? null : runStart.toInstant();
        if (start == null) {
            return Jsons.obj("runId", runId, "items", new Jsons.Raw(Jsons.arr(List.of())));
        }

        List<String> items = new ArrayList<>();
        long totalMs = 0;
        for (java.util.Map<String, Object> row : runMapper.selectTimelineNodes(runId)) {
            java.time.Instant st = minst(row.get("actualStart"));
            if (st == null) {
                continue;
            }
            long offsetMs = java.time.Duration.between(start, st).toMillis();
            Object dur = row.get("durMs");
            double rawDur = dur instanceof Number n ? n.doubleValue() : 0d;
            long durMs = (long) Math.max(0d, rawDur);
            totalMs = Math.max(totalMs, offsetMs + durMs);
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "nodeKey", row.get("nodeKey"),
                    "name", row.get("name"),
                    "status", row.get("status"),
                    "level", mint(row, "level"),
                    "parentNodeId", row.get("parentNodeId"),
                    "loopIndex", mint(row, "loopIndex"),
                    "stepSeq", row.get("stepSeq"),
                    "resourceId", row.get("resourceId"),
                    "offsetMs", offsetMs,
                    "durationMs", durMs));
        }
        return Jsons.obj(
                "runId", runId,
                "startedAt", start.toString(),
                "totalMs", totalMs,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-07：命令事件 Outbox 列表（可按状态过滤）。 */
    public String commandOutboxJson(String state, int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        String stateFilter = state == null || state.isBlank() ? null : state;
        for (java.util.Map<String, Object> row : runMapper.selectCommandOutbox(
                TENANT, stateFilter, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "ledgerId", row.get("ledgerId"),
                    "runId", row.get("runId"),
                    "commandId", row.get("commandId"),
                    "deviceId", row.get("deviceId"),
                    "method", row.get("method"),
                    "state", row.get("state"),
                    "attempts", mint(row, "attempts"),
                    "createdAt", row.get("createdAt"),
                    "deliveredAt", row.get("deliveredAt")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：TransferTransaction 列表。 */
    public String transferTransactionsJson(String status, int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        String statusFilter = status == null || status.isBlank() ? null : status;
        for (java.util.Map<String, Object> row : runMapper.selectTransferTransactions(
                TENANT, statusFilter, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "runId", row.get("runId"),
                    "plateBarcode", row.get("plateBarcode"),
                    "sourceResourceId", row.get("sourceResourceId"),
                    "targetResourceId", row.get("targetResourceId"),
                    "status", row.get("status"),
                    "errorCode", row.get("errorCode"),
                    "errorMessage", row.get("errorMessage"),
                    "createdAt", row.get("createdAt")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：物理位置证据列表（按板号可选过滤）。 */
    public String positionEvidenceJson(String plateBarcode, int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        String plateFilter = plateBarcode == null || plateBarcode.isBlank()
                ? null : plateBarcode;
        for (java.util.Map<String, Object> row : runMapper.selectPositionEvidence(
                TENANT, plateFilter, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "plateBarcode", row.get("plateBarcode"),
                    "resourceId", row.get("resourceId"),
                    "evidenceType", row.get("evidenceType"),
                    "runId", row.get("runId"),
                    "operator", row.get("operator"),
                    "note", row.get("note"),
                    "createdAt", row.get("createdAt")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：隔离中的 run 列表（处置页用）。 */
    public String quarantinedRunsJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : runMapper.selectQuarantinedRuns(
                TENANT, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("runId", row.get("id"),
                    "runNo", row.get("runNo"),
                    "errorCode", row.get("errorCode"),
                    "reason", row.get("reason"),
                    "createdAt", row.get("createdAt"),
                    "experimentId", row.get("experimentId")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** L-01：人工介入记录列表（最新优先）。 */
    public String interventionRecordsJson(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : runMapper.selectInterventionRecords(
                TENANT, Math.max(1, Math.min(limit, 500)))) {
            items.add(Jsons.obj("id", row.get("id"),
                    "manualWindowId", row.get("manualWindowId"),
                    "runId", row.get("runId"),
                    "experimentRunId", row.get("experimentRunId"),
                    "operator", row.get("operator"),
                    "action", row.get("decidedAction"),
                    "plateBarcode", row.get("plateBarcode"),
                    "sampleId", row.get("sampleId"),
                    "fromLocation", row.get("fromLocation"),
                    "toLocation", row.get("toLocation"),
                    "result", row.get("result"),
                    "note", row.get("note"),
                    "createdAt", row.get("createdAt")));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P-08：人工/扫码确认搬运物理位置。 */
    public boolean confirmTransfer(long id, String evidenceType, String operator,
                                   String note) throws java.sql.SQLException {
        return com.labos.kernel.workflow.TransferTransaction.confirm(
                db, TENANT, id, evidenceType, operator, note);
    }

    /** P-07：重放一条已派发的外部事件（不重发物理命令）。 */
    public boolean replayCommandOutbox(long id) throws java.sql.SQLException {
        return com.labos.kernel.driver.CommandOutbox.replay(db, TENANT, id);
    }

    /** L-01：记录一条人工介入（决策/Handoff 确认）。 */
    public long recordIntervention(long manualWindowId, Long runId, Long experimentRunId,
                                   String operator, String action, String plateBarcode,
                                   String sampleId, String fromLocation, String toLocation,
                                   String result, String note) throws java.sql.SQLException {
        Long id = runMapper.insertIntervention(TENANT,
                manualWindowId > 0 ? manualWindowId : null,
                runId, experimentRunId,
                operator == null ? "unknown" : operator,
                action, plateBarcode, sampleId, fromLocation, toLocation,
                result == null ? "unknown" : result, note);
        return id == null ? 0L : id;
    }

    /** P-08：记录一条物理位置证据。 */
    public long recordPositionEvidence(String plateBarcode, Long resourceId, String type,
                                       Long runId, String operator, String note)
            throws java.sql.SQLException {
        Long id = runMapper.insertPositionEvidence(TENANT, plateBarcode,
                resourceId, type == null ? "MANUAL" : type.toUpperCase(),
                runId, operator, note);
        return id == null ? 0L : id;
    }


    /** 当前卡住/正在执行的节点（取最早一个未完成的业务节点）。 */
    private String currentNodeJson(long runId) throws java.sql.SQLException {
        java.util.Map<String, Object> row = runMapper.selectCurrentNode(runId);
        if (row == null) {
            return "null";
        }
        return Jsons.obj(
                "nodeId", row.get("id"),
                "nodeKey", row.get("nodeKey"),
                "name", row.get("name"),
                "status", row.get("status"),
                "attempt", mint(row, "attempt"),
                "resourceId", row.get("resourceId"),
                "reason", row.get("reason"),
                "steps", row.get("steps"));
    }

}
