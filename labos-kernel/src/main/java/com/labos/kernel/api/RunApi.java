package com.labos.kernel.api;

import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcManualWindowStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.ManualWindow;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentExecutor;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.PauseCommand;
import com.labos.kernel.workflow.PauseCoordinator;
import com.labos.kernel.workflow.PauseManager;
import com.labos.kernel.workflow.RunControl;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.SchedulerEta;
import com.labos.kernel.workflow.StatusVocabulary;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.labos.kernel.api.ApiServer.*;


/**
 * RunApi：从 {@link ApiServer#route} 拆出的 Run 资源域路由（25 个路由块）。
 *
 * <p>路径：{@code /api/process-status}、{@code /api/runs}、{@code /api/runs/dismiss}、{@code /api/runs/recoverable}、{@code /api/runtime/holds}
 * <p>正则路由：{@code PLATES_UNKNOWN}、{@code PLATE_LOCATE}、{@code RUNS_PAUSED}、{@code RUN_ATTENDANCE}、{@code RUN_DETAIL}、{@code RUN_DISMISS}、{@code RUN_GRAPH}、{@code RUN_PAUSE}、{@code RUN_RESUME}、{@code RUN_TIMELINE}
 *
 * <p><b>拆分约定</b>：路由顺序按首次出现顺序分发（跨 controller 稳定）；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class RunApi {

    /** 宿主：提供库连接/服务与共享 helper（与 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    RunApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        // ---- 台账 6.5：有人值守窗口（待决列表 / 决策回写）----
        if ("/api/manual-windows".equals(path)) {
            s.requireGet(method, ex);
            JdbcManualWindowStore store = new JdbcManualWindowStore(s.db, TENANT);
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
            s.send(ex, 200, Jsons.obj("total", items.size(),
                    "items", new Jsons.Raw(Jsons.arr(items))));
            return true;
        }
        Matcher manualWindowDecide = MANUAL_WINDOW_DECIDE.matcher(path);
        if (manualWindowDecide.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long wid = Long.parseLong(manualWindowDecide.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String action;
            try {
                action = ManualWindow.normalizeAction(jsonField(body, "action"));
            } catch (IllegalArgumentException e) {
                s.send(ex, 400, Jsons.obj("ok", false, "error", e.getMessage()));
                return true;
            }
            JdbcManualWindowStore store = new JdbcManualWindowStore(s.db, TENANT);
            store.expireOverdue();
            JdbcManualWindowStore.Window w = store.byId(wid);
            if (w == null || !"pending".equals(w.status())) {
                s.send(ex, 409, Jsons.obj("ok", false, "error", "窗口已被处置或已超时"));
                return true;
            }
            String op = operator(ex);
            if (!store.decide(wid, action, op, jsonField(body, "note"))) {
                s.send(ex, 409, Jsons.obj("ok", false, "error", "窗口已被处置或已超时"));
                return true;
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
                resume = resumeRunJson(w.runId(), "action=" + a, body,
                        ex.getRequestHeaders().getFirst("X-Request-Id"));
            }
            // L-01：人工介入落证（确认人/时间/板样本/交接点/动作/结果）
            long recordId = s.query.recordIntervention(wid, w.runId(), null, op, action,
                    jsonField(body, "plateBarcode"), jsonField(body, "sampleId"),
                    jsonField(body, "fromLocation"), jsonField(body, "toLocation"),
                    "MANUAL".equals(action) ? "waiting" : "resolved",
                    jsonField(body, "note"));
            s.audited("manual_window.decide", "manual_window", wid,
                    action + " run=" + w.runId(), null, ex);
            s.send(ex, 200, Jsons.obj(
                    "ok", true,
                    "windowId", wid,
                    "runId", w.runId(),
                    "action", action,
                    "interventionRecordId", recordId,
                    "resume", new Jsons.Raw(resume)));
            return true;
        }
        Matcher quarantineRelease = MANUAL_QUARANTINE_RELEASE.matcher(path);
        if (quarantineRelease.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long runId = Long.parseLong(quarantineRelease.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String note = jsonField(body, "note");
            String op = operator(ex);
            boolean ok = com.labos.kernel.workflow.StartupRecovery.resolveQuarantine(
                    s.db, TENANT, runId, actorId(ex), note);
            if (!ok) {
                s.send(ex, 409, Jsons.obj("ok", false,
                        "error", "该 run 不在隔离状态或已处置"));
                return true;
            }
            long recordId = s.query.recordIntervention(0L, runId, null, op,
                    "QUARANTINE_RELEASE", jsonField(body, "plateBarcode"),
                    jsonField(body, "sampleId"), jsonField(body, "fromLocation"),
                    jsonField(body, "toLocation"), "resolved", note);
            s.audited("run.quarantine.release", "run", runId, note,
                    Jsons.obj("recordId", recordId), ex);
            s.send(ex, 200, Jsons.obj("ok", true, "runId", runId,
                    "interventionRecordId", recordId));
            return true;
        }
        if ("/api/command-outbox".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.commandOutboxJson(
                    paramStr(ex.getRequestURI().getQuery(), "state"),
                    intParam(ex, "limit", 100)));
            return true;
        }
        Matcher outboxReplay = COMMAND_OUTBOX_REPLAY.matcher(path);
        if (outboxReplay.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long id = Long.parseLong(outboxReplay.group(1));
            boolean ok = s.query.replayCommandOutbox(id);
            if (!ok) {
                s.send(ex, 409, Jsons.obj("ok", false,
                        "error", "事件不存在或不是 DELIVERED"));
                return true;
            }
            s.audited("command.outbox.replay", "command_outbox", id, null, null, ex);
            s.send(ex, 200, Jsons.obj("ok", true, "id", id, "state", "PENDING"));
            return true;
        }
        if ("/api/transfers".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.transferTransactionsJson(
                    paramStr(ex.getRequestURI().getQuery(), "status"),
                    intParam(ex, "limit", 100)));
            return true;
        }
        Matcher transferConfirm = TRANSFER_CONFIRM.matcher(path);
        if (transferConfirm.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long tid = Long.parseLong(transferConfirm.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String op = jsonField(body, "operator");
            if (op == null || op.isBlank()) {
                op = operator(ex);
            }
            boolean ok = s.query.confirmTransfer(tid, jsonField(body, "evidenceType"),
                    op, jsonField(body, "note"));
            if (!ok) {
                s.send(ex, 409, Jsons.obj("ok", false,
                        "error", "搬运事务不存在或已确认"));
                return true;
            }
            s.audited("transfer.confirm", "transfer_transaction", tid,
                    jsonField(body, "evidenceType"), null, ex);
            s.send(ex, 200, Jsons.obj("ok", true, "id", tid, "status", "CONFIRMED"));
            return true;
        }
        if ("/api/plate-position-evidence".equals(path)) {
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.positionEvidenceJson(
                        paramStr(ex.getRequestURI().getQuery(), "plateBarcode"),
                        intParam(ex, "limit", 100)));
                return true;
            }
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String plate = jsonField(body, "plateBarcode");
            if (plate == null || plate.isBlank()) {
                s.send(ex, 400, err("需要 plateBarcode"));
                return true;
            }
            Long resourceId = jsonLong(body, "resourceId");
            Long runId = jsonLong(body, "runId");
            String op = jsonField(body, "operator");
            if (op == null || op.isBlank()) {
                op = operator(ex);
            }
            long id = s.query.recordPositionEvidence(plate,
                    resourceId, jsonField(body, "evidenceType"), runId,
                    op, jsonField(body, "note"));
            s.audited("position.evidence.record", "position_evidence", id,
                    plate, Jsons.obj("type", jsonField(body, "evidenceType")), ex);
            s.send(ex, 200, Jsons.obj("id", id, "plateBarcode", plate));
            return true;
        }
        if ("/api/runs/quarantined".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.quarantinedRunsJson(intParam(ex, "limit", 100)));
            return true;
        }
        if ("/api/intervention-records".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.interventionRecordsJson(intParam(ex, "limit", 100)));
            return true;
        }
        if ("/api/runtime/holds".equals(path)) {
            s.requireGet(method, ex);
            RuntimeHold.HoldView view = new RuntimeHold(s.db, TENANT).activeView();
            boolean unknown = view.state() == RuntimeHold.HoldState.UNKNOWN;
            s.send(ex, 200, Jsons.obj(
                    "anyActive", !view.holds().isEmpty(),
                    // ADR-PAUSE-010：读不到扣留(=UNKNOWN)会让调度器 fail-closed 阻断新派发，
                    // 必须让运维在界面上看得见，而不是只落日志。
                    "unknown", unknown,
                    "unknownReason", unknown
                            ? "读取现场扣留失败：调度器已按 fail-closed 阻断新实验派发，"
                            + "请检查数据库连通性，确认后再放行。"
                            : null,
                    "holds", new Jsons.Raw(holdsJson(view.holds()))));
            return true;
        }
        Matcher runsPaused = RUNS_PAUSED.matcher(path);
        if (runsPaused.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, pausedRunsJson());
            return true;
        }
        Matcher platesUnknown = PLATES_UNKNOWN.matcher(path);
        if (platesUnknown.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, unknownPlatesJson());
            return true;
        }
        Matcher plateLocate = PLATE_LOCATE.matcher(path);
        if (plateLocate.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            s.send(ex, 200, locatePlateJson(plateLocate.group(1),
                    new String(s.readBody(ex), StandardCharsets.UTF_8)));
            return true;
        }
        Matcher runPause = RUN_PAUSE.matcher(path);
        if (runPause.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            s.send(ex, 200, pauseRunJson(Long.parseLong(runPause.group(1)),
                    ex.getRequestURI().getQuery(),
                    ex.getRequestHeaders().getFirst("X-Request-Id")));
            return true;
        }
        Matcher runResume = RUN_RESUME.matcher(path);
        if (runResume.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            s.send(ex, 200, resumeRunJson(Long.parseLong(runResume.group(1)),
                    ex.getRequestURI().getQuery(),
                    new String(s.readBody(ex), StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst("X-Request-Id")));
            return true;
        }
        Matcher runAttendance = RUN_ATTENDANCE.matcher(path);
        if (runAttendance.matches()) {
            if (!"POST".equals(method) && !"PATCH".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long rid = Long.parseLong(runAttendance.group(1));
            String target = modeParam(ex.getRequestURI().getQuery());
            int updated = updateAttendance(rid, target);
            s.send(ex, 200, Jsons.obj(
                    "runId", rid,
                    "attendance", target,
                    "updated", updated,
                    "note", updated == 0 ? "该 run 已结束，模式未改变" : "已切换"));
            return true;
        }
        Matcher runGraph = RUN_GRAPH.matcher(path);
        if (runGraph.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.runGraphJson(Long.parseLong(runGraph.group(1))));
            return true;
        }
        Matcher runTimeline = RUN_TIMELINE.matcher(path);
        if (runTimeline.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.timelineJson(Long.parseLong(runTimeline.group(1))));
            return true;
        }
        if ("/api/runs/dismiss".equals(path)) {
            // 批量忽略暂停队列：body {"runIds":[...]} 或 {} = 全部
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            java.util.List<Long> ids = new ArrayList<>();
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
            // 过滤条件：没给 runIds 时按 kind / beforeHours 收敛范围（都为空 = 全部）
            String kind = jsonField(body, "kind");
            Integer before = jsonInt(body, "beforeHours");
            int n = new PauseCoordinator(s.db, TENANT, s.workflows, s.scheduler)
                    .dismissBatchWorkflowRuns(ids, kind, before);
            s.audited("run.dismiss_batch", "run", null,
                    ids.isEmpty()
                            ? "filter kind=" + kind + ",beforeHours=" + before
                            : "count=" + ids.size(), null, ex);
            s.send(ex, 200, Jsons.obj("dismissed", n));
            return true;
        }
        Matcher runDismiss = RUN_DISMISS.matcher(path);
        if (runDismiss.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long rid = Long.parseLong(runDismiss.group(1));
            boolean done = new PauseCoordinator(s.db, TENANT, s.workflows, s.scheduler)
                    .dismissWorkflowRun(PauseCommand.workflow(rid, PauseCommand.Action.DISMISS,
                            null, null, ex.getRequestHeaders().getFirst("X-Request-Id")));
            if (done) {
                s.audited("run.dismiss", "run", rid, null, null, ex);
            }
            s.send(ex, done ? 200 : 404, Jsons.obj("runId", rid, "dismissed", done));
            return true;
        }
        if ("/api/runs/recoverable".equals(path)) {
            s.requireGet(method, ex);
            List<String> items = new ArrayList<>();
            for (var o : StartupRecovery.listRecoverable(s.db, TENANT)) {
                items.add(Jsons.obj(
                        "runId", o.runId(),
                        "runNo", o.runNo(),
                        "completedNodes", o.completedNodes(),
                        "totalNodes", o.totalNodes(),
                        "failedNodes", o.failedNodes()));
            }
            s.send(ex, 200, Jsons.obj("total", items.size(),
                    "items", new Jsons.Raw(Jsons.arr(items))));
            return true;
        }
        if ("/api/process-status".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.processStatusJson());
            return true;
        }
        if ("/api/runs".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.recentRuns(intParam(ex, "limit", 20)));
            return true;
        }
        Matcher m = RUN_DETAIL.matcher(path);
        if (m.matches()) {
            s.requireGet(method, ex);
            String body = s.query.runDetail(Long.parseLong(m.group(1)));
            if (body == null) {
                s.send(ex, 404, err("run not found"));
            } else {
                s.send(ex, 200, body);
            }
            return true;
        }
        return false;
    }


    /** 生效扣留列表 -> JSON 数组。 */
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


    /** 人工盘点：报实际位置。actualLocation 为 "LOST" 表示确认丢失。 */
    private String locatePlateJson(String barcode, String bodyText) {
        try {
            String loc = null;
            if (bodyText != null) {
                int i = bodyText.indexOf("\"actualLocation\"");
                if (i >= 0) {
                    int colon = bodyText.indexOf(':', i);
                    if (colon >= 0) {
                        String rest = bodyText.substring(colon + 1).trim();
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
            boolean ok = new com.labos.kernel.workflow.PauseManager(s.db, 1L)
                    .locatePlate(barcode, loc, "api", null);
            return Jsons.obj("ok", ok, "barcode", barcode,
                    "actualLocation", loc == null ? "LOST" : loc);
        } catch (RuntimeException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }


    /** 从 body 里解析 {"corrections":{"板号":"位置"}}。 */
    private static Map<String, String> parseCorrections(String bodyText) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
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
        // 找到与之配对的右括号
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


    // ================================================================ 暂停/恢复

    /** 暂停一个执行。kind 可选 paused_fault / waiting_human / paused_unknown。 */
    private String pauseRunJson(long runId, String query, String requestId) {
        try {
            Map<String, String> q = queryParams(query);
            // 统一走 PauseCoordinator（S-02 / P-06）：这里只解析 HTTP 参数，
            // 路由与暂停语义不在此处重写。
            PauseCoordinator.WorkflowPause r = new PauseCoordinator(s.db, TENANT, s.workflows, s.scheduler)
                    .pauseWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.PAUSE,
                            q.get("kind"), q.get("reason"), requestId), q.get("nodeKey"));
            return Jsons.obj("ok", true, "runId", runId,
                    "pauseKind", r.pauseKindCode(), "releasedResources", r.releasedResources());
        } catch (RuntimeException | SQLException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }


    private String pausedRunsJson() {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> m : new com.labos.kernel.workflow.PauseManager(s.db, 1L).pausedRuns()) {
            List<String> kvs = new ArrayList<>();
            m.forEach((k, v) -> kvs.add(Jsons.quote(k) + ":" + jsonVal(v)));
            items.add("{" + String.join(",", kvs) + "}");
        }
        return Jsons.arr(items);
    }


    /** 简单 query 解析：a=1&b=2 → {a:1, b:2} */
    private static Map<String, String> queryParams(String query) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return m;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                m.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignore) {
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return m;
    }


    /**
     * 恢复一个执行。
     *
     * <p>body 可以带 corrections：<pre>{"corrections":{"PL-001":"ID1183-L2"}}</pre>
     */
    private String resumeRunJson(long runId, String query, String bodyText, String requestId) {
        try {
            Map<String, String> q = queryParams(query);
            Map<String, String> corrections = parseCorrections(bodyText);

            // 统一走 PauseCoordinator：PauseManager 只把 run 置回 queued，
            // coordinator 内部接着真的续跑（已完成节点跳过，从卡住那一步继续）。
            // 不接这一步，界面上点"继续"就永远停在 queued（现场换完纸点继续没反应）。
            PauseManager.ResumeResult r = new PauseCoordinator(s.db, TENANT, s.workflows, s.scheduler)
                    .resumeWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.RESUME,
                            q.get("action"), null, requestId), corrections);
            return r.toJsonString();
        } catch (RuntimeException | SQLException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }


    private String unknownPlatesJson() {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> m : new com.labos.kernel.workflow.PauseManager(s.db, 1L).unknownPlates()) {
            List<String> kvs = new ArrayList<>();
            m.forEach((k, v) -> kvs.add(Jsons.quote(k) + ":" + jsonVal(v)));
            items.add("{" + String.join(",", kvs) + "}");
        }
        return Jsons.arr(items);
    }


    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"");
    }


    /** 切换运行中的 run 的值守模式；已结束的 run 不受影响。 */
    private int updateAttendance(long runId, String attendance) throws SQLException {
        try (Connection c = s.db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE run SET attendance = ?
                     WHERE tenant_id = ? AND id = ? AND status = 'running'
                     """)) {
            ps.setString(1, attendance);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ 路由正则

    private static final Pattern RUN_DETAIL = Pattern.compile("^/api/runs/(\\d+)$");

    private static final Pattern RUN_ATTENDANCE = Pattern.compile("^/api/runs/(\\d+)/attendance$");

    /** 暂停：POST /api/runs/{id}/pause?kind=paused_fault&reason=xxx */
    private static final Pattern RUN_PAUSE = Pattern.compile("^/api/runs/(\\d+)/pause$");

    /** 恢复：POST /api/runs/{id}/resume?action=continue  body 带 corrections */
    private static final Pattern RUN_RESUME = Pattern.compile("^/api/runs/(\\d+)/resume$");

    /** 忽略一条"可恢复"记录（人判断不该续跑）。 */
    private static final Pattern RUN_DISMISS = Pattern.compile("^/api/runs/(\\d+)/dismiss$");

    /** 当前所有暂停的执行。 */
    private static final Pattern RUNS_PAUSED = Pattern.compile("^/api/runs/paused$");

    /** 台账 6.5：POST /api/manual-windows/{id}/decide */
    /** 当前操作人（登录用户名）；无会话时退化为 api。 */
    private String operator(HttpExchange ex) {
        var sess = s.currentSession(ex);
        return sess == null ? "api" : sess.username();
    }

    private long actorId(HttpExchange ex) {
        var sess = s.currentSession(ex);
        return sess == null ? 1L : sess.userId();
    }

    private static final Pattern COMMAND_OUTBOX_REPLAY =
            Pattern.compile("^/api/command-outbox/(\\d+)/replay$");

    private static final Pattern TRANSFER_CONFIRM =
            Pattern.compile("^/api/transfers/(\\d+)/confirm$");

    private static final Pattern MANUAL_QUARANTINE_RELEASE =
            Pattern.compile("^/api/runs/(\\d+)/quarantine/release$");

    private static final Pattern MANUAL_WINDOW_DECIDE =
            Pattern.compile("^/api/manual-windows/(\\d+)/decide$");

    /** 位置未知的板。 */
    private static final Pattern PLATES_UNKNOWN = Pattern.compile("^/api/plates/unknown$");

    /** 人工盘点：POST /api/plates/{barcode}/locate */
    private static final Pattern PLATE_LOCATE =
            Pattern.compile("^/api/plates/([^/]+)/locate$");

    private static final Pattern RUN_TIMELINE = Pattern.compile("^/api/runs/(\\d+)/timeline$");

    /** 一次执行的流程图（BPMN + 节点状态）。 */
    private static final Pattern RUN_GRAPH = Pattern.compile("^/api/runs/(\\d+)/graph$");
}
