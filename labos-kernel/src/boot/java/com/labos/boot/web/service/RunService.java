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
    private final com.labos.kernel.api.QueryService query;

    public RunService(DbConfig db, WorkflowService workflows,
                      ExperimentScheduler scheduler, RunMapper runMapper,
                      com.labos.kernel.api.QueryService query) {
        this.db = db;
        this.workflows = workflows;
        this.scheduler = scheduler;
        this.runMapper = runMapper;
        this.query = query;
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
        long recordId = query.recordIntervention(windowId, w.runId(), null, operator, action,
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
        long recordId = query.recordIntervention(0L, runId, null, operator,
                "QUARANTINE_RELEASE", field(body, "plateBarcode"),
                field(body, "sampleId"), field(body, "fromLocation"),
                field(body, "toLocation"), "resolved", note);
        return Result.of(200, Jsons.obj("ok", true, "runId", runId,
                "interventionRecordId", recordId), runId, note,
                Jsons.obj("recordId", recordId));
    }

    // ================================================================ 命令 outbox

    public Result replayOutbox(long id) throws java.sql.SQLException {
        boolean ok = query.replayCommandOutbox(id);
        if (!ok) {
            return Result.of(409, Jsons.obj("ok", false, "error", "事件不存在或不是 DELIVERED"), null, null, null);
        }
        return Result.ok(Jsons.obj("ok", true, "id", id, "state", "PENDING"));
    }

    // ================================================================ 搬运确认

    public Result confirmTransfer(long id, String body, String operator)
            throws java.sql.SQLException {
        boolean ok = query.confirmTransfer(id, field(body, "evidenceType"),
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
        long id = query.recordPositionEvidence(plate,
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
            PauseCoordinator.WorkflowPause r = new PauseCoordinator(db, TENANT, workflows, scheduler)
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
            PauseManager.ResumeResult r = new PauseCoordinator(db, TENANT, workflows, scheduler)
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
        int n = new PauseCoordinator(db, TENANT, workflows, scheduler)
                .dismissBatchWorkflowRuns(ids, kind, before);
        return Result.of(200, Jsons.obj("dismissed", n), null,
                ids.isEmpty() ? "filter kind=" + kind + ",beforeHours=" + before
                        : "count=" + ids.size(), null);
    }

    public Result dismissOne(long runId, String requestId) throws java.sql.SQLException {
        boolean done = new PauseCoordinator(db, TENANT, workflows, scheduler)
                .dismissWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.DISMISS,
                        null, null, requestId));
        return Result.of(done ? 200 : 404, Jsons.obj("runId", runId, "dismissed", done), runId, null, null);
    }

    // ================================================================ 只读

    public String pausedRuns() throws java.sql.SQLException {
        return keyValueArrayJson(new PauseManager(db, TENANT).pausedRuns());
    }

    public String unknownPlates() throws java.sql.SQLException {
        return keyValueArrayJson(new PauseManager(db, TENANT).unknownPlates());
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

    // ================================================================ 内部

    private static String field(String body, String key) {
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
}
