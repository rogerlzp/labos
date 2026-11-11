package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.boot.web.service.RunService;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

/**
 * 执行域**写动作**端点（11 个）——纯 HTTP 层：解析参数 → 调 {@link RunService}
 * → 落审计 → 回响应。业务与数据访问都在 Service/Mapper。
 *
 * <p>审计沿用原语义：暂停/恢复/值守/盘点/定位本身不写审计（原实现没有），
 * 只有 decide / quarantine.release / outbox.replay / transfer.confirm /
 * position.evidence / dismiss 这些「人工处置」落审计。
 */
@RestController
@RequestMapping("/api")
public class RunActionController {

    private final RunService runService;
    private final AuditRecorder audit;

    public RunActionController(RunService runService, AuditRecorder audit) {
        this.runService = runService;
        this.audit = audit;
    }

    @PostMapping(value = "/manual-windows/{id:\\d+}/decide",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> decideManualWindow(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest req) throws SQLException {
        var result = runService.decideWindow(id, body, requestId, operator(req));
        // 人工介入落证（L-01）已由 Service 写入 intervention_record；审计记操作本身。
        audit.record(session(req), "manual_window.decide", "manual_window", id,
                result.auditKey(), result.auditDetail(), req.getRemoteAddr());
        return to(result);
    }

    @PostMapping(value = "/runs/{id:\\d+}/quarantine/release",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> releaseQuarantine(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        var result = runService.releaseQuarantine(id, body, operator(req), actorId(req));
        audit.record(session(req), "run.quarantine.release", "run", id,
                result.auditKey(), result.auditDetail(), req.getRemoteAddr());
        return to(result);
    }

    @PostMapping(value = "/command-outbox/{id:\\d+}/replay",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> replayOutbox(@PathVariable long id,
                                               HttpServletRequest req) throws SQLException {
        var result = runService.replayOutbox(id);
        audit.record(session(req), "command.outbox.replay", "command_outbox", id,
                null, null, req.getRemoteAddr());
        return to(result);
    }

    @PostMapping(value = "/transfers/{id:\\d+}/confirm",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> confirmTransfer(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        var result = runService.confirmTransfer(id, body, operator(req));
        audit.record(session(req), "transfer.confirm", "transfer_transaction", id,
                result.auditKey(), result.auditDetail(), req.getRemoteAddr());
        return to(result);
    }

    @PostMapping(value = "/plate-position-evidence",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> recordPositionEvidence(
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        var result = runService.recordEvidence(body, operator(req));
        audit.record(session(req), "position.evidence.record", "position_evidence",
                result.auditEntityId(), result.auditKey(), result.auditDetail(),
                req.getRemoteAddr());
        return to(result);
    }

    @PostMapping(value = "/plates/{barcode}/locate",
            produces = "application/json; charset=utf-8")
    public String locatePlate(@PathVariable String barcode,
                              @RequestBody(required = false) String body) {
        return runService.locatePlate(barcode, body);
    }

    @PostMapping(value = "/runs/{id:\\d+}/pause", produces = "application/json; charset=utf-8")
    public String pauseRun(@PathVariable long id, HttpServletRequest req,
                           @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return runService.pauseRun(id, req.getQueryString(), requestId);
    }

    @PostMapping(value = "/runs/{id:\\d+}/resume", produces = "application/json; charset=utf-8")
    public String resumeRun(@PathVariable long id,
                            @RequestBody(required = false) String body,
                            HttpServletRequest req,
                            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return runService.resumeRun(id, req.getQueryString(), body, requestId);
    }

    @PostMapping(value = "/runs/{id:\\d+}/attendance",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> attendance(@PathVariable long id, HttpServletRequest req)
            throws SQLException {
        return to(runService.attendance(id, ApiServer.modeParam(req.getQueryString())));
    }

    @PatchMapping(value = "/runs/{id:\\d+}/attendance",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> patchAttendance(@PathVariable long id, HttpServletRequest req)
            throws SQLException {
        return attendance(id, req);
    }

    /**
     * **跳过这个失败节点，继续往下跑**。
     *
     * <p>body: {@code {"nodeKey":"Activity_xxx"}}（也可放 query）。
     * 节点必须是 manual / skipped 状态；跳过后立刻续跑（其余未完成节点重做）。</p>
     */
    @PostMapping(value = "/runs/{id:\\d+}/skip-node", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> skipNode(@PathVariable long id,
                            @RequestBody(required = false) String body,
                            HttpServletRequest req,
                            @RequestHeader(value = "X-Request-Id", required = false) String requestId)
            throws SQLException {
        String nodeKey = RunService.field(body, "nodeKey");
        if (nodeKey == null || nodeKey.isBlank()) {
            nodeKey = ApiServer.paramStr(req.getQueryString(), "nodeKey");
        }
        var result = runService.skipNode(id, nodeKey, requestId);
        if (result.status() == 200) {
            audit.record(session(req), "run.skip_node", "run", id, null, null,
                    req.getRemoteAddr());
        }
        return to(result);
    }

    @PostMapping(value = "/runs/dismiss", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> dismissBatch(@RequestBody(required = false) String body,
                                               HttpServletRequest req) throws SQLException {
        var result = runService.dismissBatch(body);
        audit.record(session(req), "run.dismiss_batch", "run", null,
                result.auditKey(), result.auditDetail(), req.getRemoteAddr());
        return to(result);
    }

    @PostMapping(value = "/runs/{id:\\d+}/dismiss",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> dismissOne(
            @PathVariable long id,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest req) throws SQLException {
        var result = runService.dismissOne(id, requestId);
        if (result.status() == 200) {
            audit.record(session(req), "run.dismiss", "run", id, null, null,
                    req.getRemoteAddr());
        }
        return to(result);
    }

    private static ResponseEntity<String> to(RunService.Result result) {
        return ResponseEntity.status(result.status()).body(result.json());
    }

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
