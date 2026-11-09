package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcManualWindowStore;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.recovery.ManualWindow;
import com.labos.kernel.workflow.PauseCommand;
import com.labos.kernel.workflow.PauseCoordinator;
import com.labos.kernel.workflow.PauseManager;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonInt;
import static com.labos.kernel.api.ApiServer.jsonLong;
import static com.labos.kernel.api.ApiServer.modeParam;

/**
 * 从 {@code RunApi} 拆出的**写动作** Controller（11 个端点）。
 *
 * <p>暂停/恢复/忽略全部走 {@link PauseCoordinator}，与原实现同一条路径：
 * {@code PauseManager} 只把 run 置回 queued，coordinator 内部才真的续跑
 * （已完成节点跳过，从卡住那一步继续）。这里只做 HTTP 参数解析，
 * **不在 Controller 里重写暂停语义**。
 *
 * <p>错误语义保持原样：非法 action → 400；窗口/事务已被处置 → 409；
 * run 不存在 → 404；暂停/恢复内部异常 → 200 + {@code {"ok":false,"error":...}}
 * （原实现就是这样吞异常返回的，前端按 ok 字段判断）。
 */
@RestController
@RequestMapping("/api")
public class RunActionController {

    private static final long TENANT = 1L;

    private final QueryService query;
    private final DbConfig db;
    private final WorkflowService workflows;
    private final ExperimentScheduler scheduler;
    private final AuditRecorder audit;

    public RunActionController(QueryService query, DbConfig db,
                               WorkflowService workflows, ExperimentScheduler scheduler,
                               AuditRecorder audit) {
        this.query = query;
        this.db = db;
        this.workflows = workflows;
        this.scheduler = scheduler;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 人工窗口处置

    @PostMapping(value = "/manual-windows/{id:\\d+}/decide",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> decideManualWindow(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest req) throws SQLException {
        String action;
        try {
            action = ManualWindow.normalizeAction(jsonField(body, "action"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Jsons.obj("ok", false, "error", e.getMessage()));
        }
        JdbcManualWindowStore store = new JdbcManualWindowStore(db, TENANT);
        store.expireOverdue();
        JdbcManualWindowStore.Window w = store.byId(id);
        if (w == null || !"pending".equals(w.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Jsons.obj("ok", false, "error", "窗口已被处置或已超时"));
        }
        String op = operator(req);
        if (!store.decide(id, action, op, jsonField(body, "note"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Jsons.obj("ok", false, "error", "窗口已被处置或已超时"));
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
            resume = resumeRunJson(w.runId(), "action=" + a, body, requestId);
        }
        // L-01：人工介入落证（确认人/时间/板样本/交接点/动作/结果）
        long recordId = query.recordIntervention(id, w.runId(), null, op, action,
                jsonField(body, "plateBarcode"), jsonField(body, "sampleId"),
                jsonField(body, "fromLocation"), jsonField(body, "toLocation"),
                "MANUAL".equals(action) ? "waiting" : "resolved",
                jsonField(body, "note"));
        audit.record(session(req), "manual_window.decide", "manual_window", id,
                action + " run=" + w.runId(), null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj(
                "ok", true,
                "windowId", id,
                "runId", w.runId(),
                "action", action,
                "interventionRecordId", recordId,
                "resume", new Jsons.Raw(resume)));
    }

    // ---------------------------------------------------------------- 隔离放行

    @PostMapping(value = "/runs/{id:\\d+}/quarantine/release",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> releaseQuarantine(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        String note = jsonField(body, "note");
        String op = operator(req);
        boolean ok = StartupRecovery.resolveQuarantine(db, TENANT, id, actorId(req), note);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Jsons.obj("ok", false, "error", "该 run 不在隔离状态或已处置"));
        }
        long recordId = query.recordIntervention(0L, id, null, op,
                "QUARANTINE_RELEASE", jsonField(body, "plateBarcode"),
                jsonField(body, "sampleId"), jsonField(body, "fromLocation"),
                jsonField(body, "toLocation"), "resolved", note);
        audit.record(session(req), "run.quarantine.release", "run", id, note,
                Jsons.obj("recordId", recordId), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("ok", true, "runId", id,
                "interventionRecordId", recordId));
    }

    // ---------------------------------------------------------------- 命令 outbox 重放

    @PostMapping(value = "/command-outbox/{id:\\d+}/replay",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> replayOutbox(@PathVariable long id,
                                               HttpServletRequest req) throws SQLException {
        boolean ok = query.replayCommandOutbox(id);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Jsons.obj("ok", false, "error", "事件不存在或不是 DELIVERED"));
        }
        audit.record(session(req), "command.outbox.replay", "command_outbox", id,
                null, null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("ok", true, "id", id, "state", "PENDING"));
    }

    // ---------------------------------------------------------------- 搬运确认

    @PostMapping(value = "/transfers/{id:\\d+}/confirm",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> confirmTransfer(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        String op = jsonField(body, "operator");
        if (op == null || op.isBlank()) {
            op = operator(req);
        }
        boolean ok = query.confirmTransfer(id, jsonField(body, "evidenceType"),
                op, jsonField(body, "note"));
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Jsons.obj("ok", false, "error", "搬运事务不存在或已确认"));
        }
        audit.record(session(req), "transfer.confirm", "transfer_transaction", id,
                jsonField(body, "evidenceType"), null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("ok", true, "id", id, "status", "CONFIRMED"));
    }

    // ---------------------------------------------------------------- 板位证据 / 盘点

    @PostMapping(value = "/plate-position-evidence",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> recordPositionEvidence(
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        String plate = jsonField(body, "plateBarcode");
        if (plate == null || plate.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 plateBarcode"));
        }
        Long resourceId = jsonLong(body, "resourceId");
        Long runId = jsonLong(body, "runId");
        String op = jsonField(body, "operator");
        if (op == null || op.isBlank()) {
            op = operator(req);
        }
        long id = query.recordPositionEvidence(plate,
                resourceId, jsonField(body, "evidenceType"), runId,
                op, jsonField(body, "note"));
        audit.record(session(req), "position.evidence.record", "position_evidence", id,
                plate, Jsons.obj("type", jsonField(body, "evidenceType")),
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("id", id, "plateBarcode", plate));
    }

    /** 人工盘点：报实际位置。actualLocation 为 "LOST" 表示确认丢失。 */
    @PostMapping(value = "/plates/{barcode}/locate",
            produces = "application/json; charset=utf-8")
    public String locatePlate(@PathVariable String barcode,
                              @RequestBody(required = false) String body) {
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

    // ---------------------------------------------------------------- 暂停 / 恢复

    @PostMapping(value = "/runs/{id:\\d+}/pause", produces = "application/json; charset=utf-8")
    public String pauseRun(@PathVariable long id,
                           HttpServletRequest req,
                           @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return pauseRunJson(id, req.getQueryString(), requestId);
    }

    @PostMapping(value = "/runs/{id:\\d+}/resume", produces = "application/json; charset=utf-8")
    public String resumeRun(@PathVariable long id,
                            @RequestBody(required = false) String body,
                            HttpServletRequest req,
                            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return resumeRunJson(id, req.getQueryString(), body, requestId);
    }

    /** 暂停一个执行。kind 可选 paused_fault / waiting_human / paused_unknown。 */
    private String pauseRunJson(long runId, String queryString, String requestId) {
        try {
            Map<String, String> q = queryParams(queryString);
            // 统一走 PauseCoordinator（S-02 / P-06）：这里只解析 HTTP 参数，
            // 暂停语义不在此处重写。
            PauseCoordinator.WorkflowPause r = new PauseCoordinator(db, TENANT, workflows, scheduler)
                    .pauseWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.PAUSE,
                            q.get("kind"), q.get("reason"), requestId), q.get("nodeKey"));
            return Jsons.obj("ok", true, "runId", runId,
                    "pauseKind", r.pauseKindCode(), "releasedResources", r.releasedResources());
        } catch (RuntimeException | SQLException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 恢复一个执行。
     *
     * <p>body 可以带 corrections：{@code {"corrections":{"PL-001":"ID1183-L2"}}}。
     */
    private String resumeRunJson(long runId, String queryString, String bodyText,
                                 String requestId) {
        try {
            Map<String, String> q = queryParams(queryString);
            Map<String, String> corrections = parseCorrections(bodyText);
            // 统一走 PauseCoordinator：PauseManager 只把 run 置回 queued，
            // coordinator 内部接着真的续跑（已完成节点跳过，从卡住那一步继续）。
            PauseManager.ResumeResult r = new PauseCoordinator(db, TENANT, workflows, scheduler)
                    .resumeWorkflowRun(PauseCommand.workflow(runId, PauseCommand.Action.RESUME,
                            q.get("action"), null, requestId), corrections);
            return r.toJsonString();
        } catch (RuntimeException | SQLException e) {
            return Jsons.obj("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 值守模式

    @PostMapping(value = "/runs/{id:\\d+}/attendance",
            produces = "application/json; charset=utf-8")
    public String attendance(@PathVariable long id, HttpServletRequest req) throws SQLException {
        String target = modeParam(req.getQueryString());
        int updated = updateAttendance(id, target);
        return Jsons.obj(
                "runId", id,
                "attendance", target,
                "updated", updated,
                "note", updated == 0 ? "该 run 已结束，模式未改变" : "已切换");
    }

    @PatchMapping(value = "/runs/{id:\\d+}/attendance",
            produces = "application/json; charset=utf-8")
    public String patchAttendance(@PathVariable long id, HttpServletRequest req) throws SQLException {
        return attendance(id, req);
    }

    // ---------------------------------------------------------------- 忽略（单个 / 批量）

    @PostMapping(value = "/runs/dismiss", produces = "application/json; charset=utf-8")
    public String dismissBatch(@RequestBody(required = false) String body,
                               HttpServletRequest req) throws SQLException {
        // 批量忽略暂停队列：body {"runIds":[...]} 或 {} = 全部
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
        // 过滤条件：没给 runIds 时按 kind / beforeHours 收敛范围（都为空 = 全部）
        String kind = jsonField(body, "kind");
        Integer before = jsonInt(body, "beforeHours");
        int n = new PauseCoordinator(db, TENANT, workflows, scheduler)
                .dismissBatchWorkflowRuns(ids, kind, before);
        audit.record(session(req), "run.dismiss_batch", "run", null,
                ids.isEmpty()
                        ? "filter kind=" + kind + ",beforeHours=" + before
                        : "count=" + ids.size(), null, req.getRemoteAddr());
        return Jsons.obj("dismissed", n);
    }

    @PostMapping(value = "/runs/{id:\\d+}/dismiss",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> dismissOne(
            @PathVariable long id,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest req) throws SQLException {
        boolean done = new PauseCoordinator(db, TENANT, workflows, scheduler)
                .dismissWorkflowRun(PauseCommand.workflow(id, PauseCommand.Action.DISMISS,
                        null, null, requestId));
        if (done) {
            audit.record(session(req), "run.dismiss", "run", id, null, null,
                    req.getRemoteAddr());
        }
        return ResponseEntity.status(done ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .body(Jsons.obj("runId", id, "dismissed", done));
    }

    // ---------------------------------------------------------------- 内部

    /** 切换运行中的 run 的值守模式；已结束的 run 不受影响。 */
    private int updateAttendance(long runId, String attendance) throws SQLException {
        try (Connection c = db.open();
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

    /** 当前操作人（登录用户名）；无会话时退化为 api。 */
    private static String operator(HttpServletRequest req) {
        AuthService.Session s0 = session(req);
        return s0 == null ? "api" : s0.username();
    }

    private static long actorId(HttpServletRequest req) {
        AuthService.Session s0 = session(req);
        return s0 == null ? 1L : s0.userId();
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }
}
