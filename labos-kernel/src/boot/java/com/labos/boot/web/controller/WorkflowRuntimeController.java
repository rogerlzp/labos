package com.labos.boot.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.WorkflowRuntimeKernelFacade;
import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticated Kernel HTTP operations, observability, T21 manual reconciliation, and
 * isolated trial-run control API for the embedded Workflow Runtime.
 *
 * <p>Mounted strictly under {@code /api/runtime/**} so legacy {@code /api/runs/**} and
 * {@code GraphRunner} remain completely untouched. Protected automatically by
 * {@link ApiAuthInterceptor} (read endpoints require valid login session when auth is enabled;
 * all write endpoints require {@code admin} or {@code operator} via {@code canOperate()} and
 * record audit entries via {@link AuditRecorder}).
 */
@RestController
@RequestMapping("/api/runtime")
public class WorkflowRuntimeController {

    private static final long DEFAULT_KERNEL_TENANT_ID = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=utf-8");

    private final WorkflowRuntimeKernelFacade facade;
    private final AuditRecorder audit;

    public WorkflowRuntimeController(WorkflowRuntimeKernelFacade facade, AuditRecorder audit) {
        this.facade = facade;
        this.audit = audit;
    }

    /** Health & diagnostics snapshot for the embedded RuntimeHost and in-process DeviceGateway. */
    @GetMapping(value = "/health", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> health() {
        Optional<EmbeddedWorkflowRuntimeHost.HealthSnapshot> snapOpt = facade.hostHealth();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", snapOpt.isPresent());
        out.put("host", snapOpt.map(WorkflowRuntimeController::serializeHealth).orElse(null));
        return jsonResponse(200, out);
    }

    /** List Runs currently blocked in {@code MANUAL_REQUIRED}. */
    @GetMapping(value = "/runs/manual-required", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> listManualRequiredRuns(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            int bounded = clampLimit(limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (var r : facade.listManualRequiredRuns(DEFAULT_KERNEL_TENANT_ID, bounded)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("runId", r.runId().toString());
                m.put("status", r.status());
                m.put("controlGate", r.controlGate());
                m.put("updatedAt", iso(r.updatedAt()));
                items.add(m);
            }
            return jsonResponse(200, Map.of("items", items, "count", items.size()));
        } catch (IllegalArgumentException bad) {
            return errorResponse(400, bad.getMessage());
        }
    }

    /** List uncertain device Commands ({@code UNKNOWN} / {@code DELIVERY_UNKNOWN}) awaiting T21 reconciliation. */
    @GetMapping(value = "/commands/unknown", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> listUnknownCommands(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            int bounded = clampLimit(limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (var c : facade.listUnknownCommands(DEFAULT_KERNEL_TENANT_ID, bounded)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("commandId", c.commandId().toString());
                m.put("runId", c.runId().toString());
                m.put("attemptId", c.attemptId().toString());
                m.put("deviceId", c.deviceId());
                m.put("commandKind", c.commandKind());
                m.put("commandState", c.commandState());
                m.put("updatedAt", iso(c.updatedAt()));
                items.add(m);
            }
            return jsonResponse(200, Map.of("items", items, "count", items.size()));
        } catch (IllegalArgumentException bad) {
            return errorResponse(400, bad.getMessage());
        }
    }

    /**
     * T21 manual reconciliation for an {@code UNKNOWN} / {@code DELIVERY_UNKNOWN} device Command.
     * Body: {@code {"resolvedState":"SUCCEEDED|FAILED|CANCELLED|MANUAL_REQUIRED","deviceSequence":"...","evidence":{...}}}.
     */
    @PostMapping(value = "/commands/{commandId}/reconcile", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> reconcileUnknownCommand(
            @PathVariable String commandId,
            @RequestBody(required = false) String body,
            HttpServletRequest req) {
        UUID cmdUuid;
        try {
            cmdUuid = UUID.fromString(commandId.trim());
        } catch (IllegalArgumentException badUuid) {
            return errorResponse(400, "Invalid commandId UUID: " + commandId);
        }
        try {
            JsonNode root = parseJsonObject(body);
            String resolvedState = requiredText(root, "resolvedState");
            String deviceSequence = optionalText(root, "deviceSequence");
            String evidenceJson = root.has("evidence") && !root.get("evidence").isNull()
                    ? JSON.writeValueAsString(root.get("evidence"))
                    : "{}";
            String actor = operator(req);
            boolean reconciled = facade.reconcileUnknownCommand(
                    DEFAULT_KERNEL_TENANT_ID, cmdUuid, resolvedState, deviceSequence, evidenceJson, actor);
            if (!reconciled) {
                return errorResponse(409, "Command not found or no longer in UNKNOWN/DELIVERY_UNKNOWN state: " + cmdUuid);
            }
            Map<String, Object> auditDetail = new LinkedHashMap<>();
            auditDetail.put("resolvedState", resolvedState);
            if (deviceSequence != null) auditDetail.put("deviceSequence", deviceSequence);
            audit.record(session(req), "runtime.command.reconcile", "runtime_command", null,
                    cmdUuid.toString(), JSON.writeValueAsString(auditDetail), remoteAddr(req));
            return jsonResponse(200, Map.of(
                    "reconciled", true,
                    "commandId", cmdUuid.toString(),
                    "resolvedState", resolvedState,
                    "actor", actor));
        } catch (IllegalArgumentException bad) {
            return errorResponse(400, bad.getMessage());
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | WorkflowRuntimeRepository.StaleRuntimeWriteException conflict) {
            return errorResponse(409, conflict.getMessage());
        } catch (Exception ex) {
            return errorResponse(500, "Reconciliation failed: " + ex.getMessage());
        }
    }

    /**
     * Create (and optionally auto-start) an isolated Workflow Runtime trial Run from an existing Kernel workflow ID.
     * Body: {@code {"workflowId": 123, "input": {...}, "autoStart": true}}.
     */
    @PostMapping(value = "/runs", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createRun(
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Request-Id", required = false) String requestIdHeader,
            HttpServletRequest req) {
        try {
            JsonNode root = parseJsonObject(body);
            if (!root.has("workflowId") || !root.get("workflowId").canConvertToLong()) {
                return errorResponse(400, "Field 'workflowId' (positive integer) is required");
            }
            long workflowId = root.get("workflowId").asLong();
            if (workflowId <= 0) {
                return errorResponse(400, "Field 'workflowId' must be positive");
            }
            boolean autoStart = !root.has("autoStart") || root.get("autoStart").asBoolean(true);
            if (autoStart && !facade.isHostEnabled()) {
                return errorResponse(503, "Embedded WorkflowRuntimeHost is disabled; set autoStart=false or enable host");
            }
            String inputJson = root.has("input") && !root.get("input").isNull()
                    ? JSON.writeValueAsString(root.get("input"))
                    : "{}";
            String requestId = (requestIdHeader != null && !requestIdHeader.isBlank())
                    ? requestIdHeader.trim()
                    : "runtime-api-" + UUID.randomUUID();
            String canonicalReq = JSON.writeValueAsString(Map.of(
                    "workflowId", workflowId,
                    "input", JSON.readTree(inputJson),
                    "autoStart", autoStart));
            WorkflowRuntimeRepository.CreatedRun created = facade.createRun(
                    DEFAULT_KERNEL_TENANT_ID, workflowId, requestId, inputJson, canonicalReq);
            boolean started = false;
            if (autoStart) {
                facade.startRun(DEFAULT_KERNEL_TENANT_ID, created.runId());
                started = true;
            }
            audit.record(session(req), "runtime.run.create", "runtime_run", workflowId,
                    created.runId().toString(),
                    JSON.writeValueAsString(Map.of("workflowId", workflowId, "autoStart", autoStart, "replayed", created.replayed())),
                    remoteAddr(req));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("runId", created.runId().toString());
            resp.put("workflowId", workflowId);
            resp.put("replayed", created.replayed());
            resp.put("started", started);
            return jsonResponse(created.replayed() ? 200 : 201, resp);
        } catch (IllegalArgumentException bad) {
            return errorResponse(400, bad.getMessage());
        } catch (WorkflowRuntimeRepository.IdempotencyConflictException
                | WorkflowRuntimeRepository.InvalidTransitionException
                | WorkflowRuntimeRepository.StaleRuntimeWriteException conflict) {
            return errorResponse(409, conflict.getMessage());
        } catch (IllegalStateException stateErr) {
            return errorResponse(classifyIllegalStateStatus(stateErr), stateErr.getMessage());
        } catch (Exception ex) {
            return errorResponse(500, "Failed to create Runtime Run: " + ex.getMessage());
        }
    }

    /** Point-in-time durable status, nodes, variables, and commands overview for a single Runtime Run. */
    @GetMapping(value = "/runs/{runId}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> getRun(@PathVariable String runId) {
        UUID runUuid;
        try {
            runUuid = UUID.fromString(runId.trim());
        } catch (IllegalArgumentException badUuid) {
            return errorResponse(400, "Invalid runId UUID: " + runId);
        }
        Optional<WorkflowRuntimeRepository.RunOverview> overviewOpt =
                facade.getRunOverview(DEFAULT_KERNEL_TENANT_ID, runUuid);
        if (overviewOpt.isEmpty()) {
            return errorResponse(404, "Runtime Run not found: " + runUuid);
        }
        return jsonResponse(200, serializeRunOverview(overviewOpt.get()));
    }

    /** Start a {@code CREATED} Runtime Run on the embedded host. */
    @PostMapping(value = "/runs/{runId}/start", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> startRun(@PathVariable String runId, HttpServletRequest req) {
        UUID runUuid;
        try {
            runUuid = UUID.fromString(runId.trim());
        } catch (IllegalArgumentException badUuid) {
            return errorResponse(400, "Invalid runId UUID: " + runId);
        }
        try {
            facade.startRun(DEFAULT_KERNEL_TENANT_ID, runUuid);
            audit.record(session(req), "runtime.run.start", "runtime_run", null,
                    runUuid.toString(), null, remoteAddr(req));
            return jsonResponse(200, Map.of("runId", runUuid.toString(), "action", "START", "accepted", true));
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | WorkflowRuntimeRepository.StaleRuntimeWriteException conflict) {
            return errorResponse(409, conflict.getMessage());
        } catch (IllegalStateException stateErr) {
            return errorResponse(classifyIllegalStateStatus(stateErr), stateErr.getMessage());
        }
    }

    /** Request a durable {@code PAUSE} drain for a running Runtime Run (converges automatically to {@code PAUSED}). */
    @PostMapping(value = "/runs/{runId}/pause", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> pauseRun(@PathVariable String runId, HttpServletRequest req) {
        UUID runUuid;
        try {
            runUuid = UUID.fromString(runId.trim());
        } catch (IllegalArgumentException badUuid) {
            return errorResponse(400, "Invalid runId UUID: " + runId);
        }
        try {
            UUID drainId = facade.requestPause(DEFAULT_KERNEL_TENANT_ID, runUuid);
            audit.record(session(req), "runtime.run.pause", "runtime_run", null,
                    runUuid.toString(), "{\"drainId\":\"" + drainId + "\"}", remoteAddr(req));
            return jsonResponse(200, Map.of(
                    "runId", runUuid.toString(),
                    "action", "PAUSE",
                    "drainId", drainId.toString()));
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | WorkflowRuntimeRepository.StaleRuntimeWriteException conflict) {
            return errorResponse(409, conflict.getMessage());
        } catch (IllegalStateException notFoundOrState) {
            return errorResponse(classifyIllegalStateStatus(notFoundOrState), notFoundOrState.getMessage());
        }
    }

    /** Resume a {@code PAUSED} Runtime Run on the embedded host (re-opens control gate and wakes coordinator). */
    @PostMapping(value = "/runs/{runId}/resume", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> resumeRun(@PathVariable String runId, HttpServletRequest req) {
        UUID runUuid;
        try {
            runUuid = UUID.fromString(runId.trim());
        } catch (IllegalArgumentException badUuid) {
            return errorResponse(400, "Invalid runId UUID: " + runId);
        }
        try {
            facade.resumeRun(DEFAULT_KERNEL_TENANT_ID, runUuid);
            audit.record(session(req), "runtime.run.resume", "runtime_run", null,
                    runUuid.toString(), null, remoteAddr(req));
            return jsonResponse(200, Map.of("runId", runUuid.toString(), "action", "RESUME", "resumed", true));
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | WorkflowRuntimeRepository.StaleRuntimeWriteException conflict) {
            return errorResponse(409, conflict.getMessage());
        } catch (IllegalStateException stateErr) {
            return errorResponse(classifyIllegalStateStatus(stateErr), stateErr.getMessage());
        }
    }

    /** Request a durable {@code STOP} drain for a Runtime Run (converges automatically to {@code STOPPED}). */
    @PostMapping(value = "/runs/{runId}/stop", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> stopRun(@PathVariable String runId, HttpServletRequest req) {
        UUID runUuid;
        try {
            runUuid = UUID.fromString(runId.trim());
        } catch (IllegalArgumentException badUuid) {
            return errorResponse(400, "Invalid runId UUID: " + runId);
        }
        try {
            UUID drainId = facade.requestStop(DEFAULT_KERNEL_TENANT_ID, runUuid);
            audit.record(session(req), "runtime.run.stop", "runtime_run", null,
                    runUuid.toString(), "{\"drainId\":\"" + drainId + "\"}", remoteAddr(req));
            return jsonResponse(200, Map.of(
                    "runId", runUuid.toString(),
                    "action", "STOP",
                    "drainId", drainId.toString()));
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | WorkflowRuntimeRepository.StaleRuntimeWriteException conflict) {
            return errorResponse(409, conflict.getMessage());
        } catch (IllegalStateException notFoundOrState) {
            return errorResponse(classifyIllegalStateStatus(notFoundOrState), notFoundOrState.getMessage());
        }
    }

    private static Map<String, Object> serializeHealth(EmbeddedWorkflowRuntimeHost.HealthSnapshot h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hostId", h.hostId());
        m.put("hostState", h.hostState().name());
        m.put("schedulingGateOpen", h.schedulingGateOpen());
        m.put("hostLockValid", h.hostLockValid());
        m.put("activeLocalWorkers", h.activeLocalWorkers());
        m.put("activeMailboxes", h.activeMailboxes());
        if (h.recoveryResult() != null) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("scanned", h.recoveryResult().scanned());
            rec.put("safeToRetry", h.recoveryResult().safeToRetry());
            rec.put("manualRequired", h.recoveryResult().manualRequired());
            rec.put("skipped", h.recoveryResult().skipped());
            if (h.recoveryResult().outbox() != null) {
                rec.put("outboxSafelyRequeued", h.recoveryResult().outbox().safelyRequeued());
                rec.put("outboxLeftUncertain", h.recoveryResult().outbox().leftUncertain());
            }
            m.put("recoveryResult", rec);
        } else {
            m.put("recoveryResult", null);
        }
        Map<String, Object> devices = new LinkedHashMap<>();
        for (Map.Entry<String, TcpDeviceConnection.HealthSnapshot> e : h.devices().entrySet()) {
            TcpDeviceConnection.HealthSnapshot d = e.getValue();
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("deviceId", d.deviceId());
            dm.put("status", d.status().name());
            dm.put("connectionEpoch", d.connectionEpoch());
            dm.put("lastConnectedAt", iso(d.lastConnectedAt()));
            dm.put("lastPongAt", iso(d.lastPongAt()));
            dm.put("reconnectCount", d.reconnectCount());
            dm.put("commandsWritten", d.commandsWritten());
            dm.put("heartbeatsSent", d.heartbeatsSent());
            dm.put("heartbeatsReceived", d.heartbeatsReceived());
            dm.put("eventsDelivered", d.eventsDelivered());
            dm.put("eventsRejected", d.eventsRejected());
            dm.put("spoofedFramesRejected", d.spoofedFramesRejected());
            dm.put("malformedFrames", d.malformedFrames());
            devices.put(e.getKey(), dm);
        }
        m.put("devices", devices);
        var ds = h.dispatchStats();
        Map<String, Object> dsm = new LinkedHashMap<>();
        dsm.put("running", ds.running());
        dsm.put("cyclesCompleted", ds.cyclesCompleted());
        dsm.put("cycleErrors", ds.cycleErrors());
        dsm.put("tasksDispatched", ds.tasksDispatched());
        dsm.put("workersSubmitted", ds.workersSubmitted());
        dsm.put("commandsDelivered", ds.commandsDelivered());
        dsm.put("lastCycleAt", iso(ds.lastCycleAt()));
        dsm.put("lastError", ds.lastError());
        m.put("dispatchStats", dsm);
        return m;
    }

    private static Map<String, Object> serializeRunOverview(WorkflowRuntimeRepository.RunOverview o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", o.runId().toString());
        m.put("status", o.status());
        m.put("controlGate", o.controlGate());
        m.put("commitVersion", o.commitVersion());
        m.put("definitionKey", o.definitionKey());
        m.put("definitionVersion", o.definitionVersion());
        m.put("terminalReason", o.terminalReason());
        m.put("createdAt", iso(o.createdAt()));
        m.put("startedAt", iso(o.startedAt()));
        m.put("updatedAt", iso(o.updatedAt()));
        m.put("finishedAt", iso(o.finishedAt()));
        m.put("activeTokens", o.activeTokens());
        m.put("activeAttempts", o.activeAttempts());
        m.put("openInterventions", o.openInterventions());
        m.put("completedNodes", o.completedNodes());
        m.put("activeNodes", o.activeNodes());
        m.put("variables", o.variables());
        List<Map<String, Object>> cmds = new ArrayList<>();
        for (var c : o.commands()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("commandId", c.commandId().toString());
            cm.put("deviceId", c.deviceId());
            cm.put("commandKind", c.commandKind());
            cm.put("commandState", c.commandState());
            cm.put("deviceSequence", c.deviceSequence());
            cm.put("createdAt", iso(c.createdAt()));
            cm.put("completedAt", iso(c.completedAt()));
            cmds.add(cm);
        }
        m.put("commands", cmds);
        return m;
    }

    private static int clampLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be in 1..500");
        }
        return limit;
    }

    private static JsonNode parseJsonObject(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (!node.isObject()) {
                throw new IllegalArgumentException("Request body must be a JSON object");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JSON body: " + e.getMessage());
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        return n.asText().trim();
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) return null;
        String text = n.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static AuthService.Session session(HttpServletRequest req) {
        if (req == null) return null;
        Object attr = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return (attr instanceof AuthService.Session s) ? s : null;
    }

    private static String operator(HttpServletRequest req) {
        AuthService.Session s = session(req);
        return s == null ? "anonymous" : s.username();
    }

    private static String remoteAddr(HttpServletRequest req) {
        return req == null ? "local" : req.getRemoteAddr();
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static ResponseEntity<String> jsonResponse(int status, Object payload) {
        try {
            return ResponseEntity.status(status).contentType(JSON_UTF8).body(JSON.writeValueAsString(payload));
        } catch (Exception e) {
            return ResponseEntity.status(500).contentType(JSON_UTF8)
                    .body("{\"error\":\"JSON serialization failed\"}");
        }
    }

    private static ResponseEntity<String> errorResponse(int status, String message) {
        return jsonResponse(status, Map.of("error", message == null ? "Unknown error" : message));
    }

    private static int classifyIllegalStateStatus(IllegalStateException ex) {
        String msg = ex.getMessage();
        if (msg == null) return 409;
        String lower = msg.toLowerCase();
        if (lower.contains("disabled")) return 503;
        if (lower.contains("not found")) return 404;
        return 409;
    }
}
