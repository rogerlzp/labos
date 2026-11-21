package com.labos.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.boot.web.controller.WorkflowRuntimeController;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.sim.DeviceCore;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end PG17 + REAL {@link DeviceCore} ({@code #!LABSEP!#}) Island workflow self-test.
 *
 * <p>Exercises the exact production composition:
 * {@code WorkflowRuntimeController} -> {@code WorkflowRuntimeKernelFacade}
 * -> {@code LegacyKernelWorkflowDefinitionSource} -> {@code BpmnGraphCompiler}
 * -> {@code EmbeddedWorkflowRuntimeHost} -> {@code GatewayDeviceTaskBridge}
 * -> {@code DeviceGateway} ({@code LabSepWireCodec}) -> REAL {@link DeviceCore} TCP simulators
 * -> REAL PostgreSQL 17.
 *
 * <p>Uses realistic {@code bpmn-js} Island-dragged BPMN XML (including {@code <bpmn:incoming>},
 * {@code <bpmn:outgoing>}, {@code <bpmndi:BPMNDiagram>}, {@code gatewayDirection},
 * {@code protocolName="__setVar"} / {@code "__sleep"}, and {@code resource="<resourceId>"}
 * mapped automatically from {@code DeviceMapper.selectDeviceRegistry} rows to {@code device_instance.id}).
 */
public final class WorkflowRuntimeIslandSimulatorSelfTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger PASSED_ASSERTIONS = new AtomicInteger(0);

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public static void main(String[] args) throws Exception {
        String pgHost = env("PGHOST", "localhost");
        String pgPort = env("PGPORT", "5432");
        String pgDb = env("PGDATABASE", "runtime_test");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb,
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        PlatformTransactionManager tm = new DataSourceTransactionManager(ds);

        // 1. Spin up TWO REAL LabOS DeviceCore TCP simulators (same class as scripts/sim-all.py & 19500-19506)!
        //    Device 206 (resourceId=501): liquid_handler with spontaneous message_type=6 broadcast after Dispense.
        //    Device 207 (resourceId=502): centrifuge in --long mode (sends method_status=1 RUNNING then method_status=2 SUCCEEDED).
        DeviceCore pipettor = new DeviceCore(
                0, "206", "LH-01", "ALIQUOT",
                60, false, 0, "EC-05-0001", false, "liquid_handler", null)
                .withSpontaneousScript(DeviceCore.parseSpontaneousScript("Dispense=process_done@30*"));
        DeviceCore centrifuge = new DeviceCore(
                0, "207", "CF-01", "CENTRIFUGE",
                400, true, 0, "EC-05-0001", false, "centrifuge", null);

        pipettor.startServer("127.0.0.1", 0);
        centrifuge.startServer("127.0.0.1", 0);

        Path tempDir = Files.createTempDirectory("labos-island-sim-test-");
        try {
            int p1 = pipettor.port();
            int p2 = centrifuge.port();

            // 2. Build realistic Island workflows in bpmn-js saved XML format!
            Map<Long, String> workflowsById = Map.of(
                    1001L, serialIslandBpmn(),
                    1002L, parallelIslandBpmn(),
                    1003L, xorIslandBpmn("92"),
                    1004L, xorIslandBpmn("40"),
                    1005L, unsupportedMethodFaultBpmn(),
                    1006L, waitMessageIslandBpmn(),
                    1007L, waitTimeoutRaceIslandBpmn("PT0.4S"));

            GraphMapper graphMapper = (GraphMapper) Proxy.newProxyInstance(
                    GraphMapper.class.getClassLoader(), new Class<?>[]{GraphMapper.class},
                    (proxy, method, arguments) -> {
                        String m = method.getName();
                        long wfId = ((Number) arguments[1]).longValue();
                        String xml = workflowsById.get(wfId);
                        if ("selectRuntimeWorkflowHeader".equals(m)) {
                            if (xml == null) return null;
                            return Map.of("id", wfId, "version", "v1", "updatedAt", Instant.parse("2026-11-20T12:00:00Z"));
                        }
                        if ("selectGraphXml".equals(m)) {
                            return xml;
                        }
                        if ("selectRuntimeWorkflowNodeMetadata".equals(m)) {
                            return List.of();
                        }
                        throw new AssertionError("Unexpected GraphMapper call: " + m);
                    });

            DeviceMapper deviceMapper = (DeviceMapper) Proxy.newProxyInstance(
                    DeviceMapper.class.getClassLoader(), new Class<?>[]{DeviceMapper.class},
                    (proxy, method, arguments) -> {
                        if ("selectDeviceRegistry".equals(method.getName())) {
                            return List.of(
                                    Map.of("deviceId", 206L, "resourceId", 501L, "ip", "", "endpoint", "tcp://127.0.0.1:" + p1),
                                    Map.of("deviceId", 207L, "resourceId", 502L, "ip", "127.0.0.1", "endpoint", "tcp://0.0.0.0:" + p2));
                        }
                        throw new AssertionError("Unexpected DeviceMapper call: " + method.getName());
                    });

            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("island-sim-props", Map.of(
                        WorkflowRuntimeHostProperties.PROP_ENABLED, "true",
                        WorkflowRuntimeHostProperties.PROP_BRIDGE_ENABLED, "true",
                        WorkflowRuntimeHostProperties.PROP_DISCOVER_FROM_REGISTRY, "true",
                        WorkflowRuntimeHostProperties.PROP_LOCK_FILE, tempDir.resolve("island-host.lock").toString(),
                        WorkflowRuntimeHostProperties.PROP_HOST_ID, "island-sim-host",
                        WorkflowRuntimeHostProperties.PROP_POLL_MS, "25",
                        WorkflowRuntimeHostProperties.PROP_SCOPED_KERNEL_TENANT_ID, "1",
                        WorkflowRuntimeHostProperties.PROP_BRIDGE_COMMAND_TIMEOUT_MS, "15000")));
                ctx.registerBean("dataSource", DataSource.class, () -> ds);
                ctx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
                ctx.registerBean("graphMapper", GraphMapper.class, () -> graphMapper);
                ctx.registerBean("deviceMapper", DeviceMapper.class, () -> deviceMapper);
                ctx.register(WorkflowRuntimeKernelConfiguration.class);
                ctx.refresh();

                EmbeddedWorkflowRuntimeHost host = ctx.getBean(EmbeddedWorkflowRuntimeHost.class);
                WorkflowRuntimeKernelFacade facade = ctx.getBean(WorkflowRuntimeKernelFacade.class);
                record CapturedAudit(
                        AuthService.Session who,
                        String action,
                        String entityType,
                        Long entityId,
                        String entityKey,
                        String detail,
                        String ip) {}
                List<String> auditActions = new java.util.concurrent.CopyOnWriteArrayList<>();
                List<CapturedAudit> capturedAudits = new java.util.concurrent.CopyOnWriteArrayList<>();
                AuditRecorder recordingAudit = new AuditRecorder(null) {
                    @Override
                    public void record(AuthService.Session who, String action,
                                       String entityType, Long entityId, String entityKey,
                                       String detail, String ip) {
                        auditActions.add(action);
                        capturedAudits.add(new CapturedAudit(
                                who, action, entityType, entityId, entityKey, detail, ip));
                    }
                };
                WorkflowRuntimeController controller = new WorkflowRuntimeController(facade, recordingAudit);

                require(host.deviceGateway().awaitOnline("206", Duration.ofSeconds(5)), "Device 206 did not come ONLINE");
                require(host.deviceGateway().awaitOnline("207", Duration.ofSeconds(5)), "Device 207 did not come ONLINE");

                // Health endpoint check via WorkflowRuntimeController & clean-startup isolation check
                var initialHealth = host.healthSnapshot();
                require(initialHealth.activeMailboxes() == 0,
                        "Expected 0 activeMailboxes on clean E2E startup, got " + initialHealth.activeMailboxes());
                require(initialHealth.recoveryResult() != null && initialHealth.recoveryResult().scanned() == 0,
                        "Expected 0 interrupted Attempts recovered on clean E2E startup, got "
                                + (initialHealth.recoveryResult() == null ? null : initialHealth.recoveryResult().scanned()));
                try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT count(*) FROM workflow_runtime.workflow_run "
                                     + "WHERE status NOT IN ('SUCCEEDED','FAILED','STOPPED')")) {
                    require(rs.next() && rs.getLong(1) == 0L,
                            "Expected 0 pre-existing non-terminal Runs in isolated E2E DB, got " + rs.getLong(1));
                }
                var healthResp = controller.health();
                require(healthResp.getStatusCode().value() == 200, "GET /api/runtime/health expected 200");
                JsonNode healthJson = JSON.readTree(healthResp.getBody());
                require(healthJson.get("enabled").asBoolean(), "Host health must report enabled=true");
                require("ONLINE".equals(healthJson.get("host").get("devices").get("206").get("status").asText()),
                        "Health must show device 206 ONLINE");
                require("ONLINE".equals(healthJson.get("host").get("devices").get("207").get("status").asText()),
                        "Health must show device 207 ONLINE");

                // ---- Scenario 1: Serial Island Workflow + HTTP PAUSE -> RESUME ----
                var s1Resp = controller.createRun("{\"workflowId\":1001,\"autoStart\":true}", "island-serial-" + UUID.randomUUID(), null);
                require(s1Resp.getStatusCode().value() == 201, "Serial run create expected 201, got " + s1Resp.getBody());
                String serialRunId = JSON.readTree(s1Resp.getBody()).get("runId").asText();

                // Wait until step_dispense is completed (while __sleep or centrifuge is about to run), then PAUSE via HTTP!
                awaitCondition(Duration.ofSeconds(10), () -> {
                    JsonNode ov = getRunJson(controller, serialRunId);
                    return containsText(ov.get("completedNodes"), "step_dispense");
                }, () -> "Serial workflow step_dispense should complete on DeviceCore 206 | run="
                        + getRunJson(controller, serialRunId) + " | health=" + controller.health().getBody());

                var pauseResp = controller.pauseRun(serialRunId, null);
                require(pauseResp.getStatusCode().value() == 200, "PAUSE expected 200: " + pauseResp.getBody());

                awaitCondition(Duration.ofSeconds(10), () -> {
                    JsonNode ov = getRunJson(controller, serialRunId);
                    return "PAUSED".equals(ov.get("status").asText())
                            && host.healthSnapshot().activeLocalWorkers() == 0;
                }, "Serial run should pause cleanly via HTTP /pause");

                var resumeResp = controller.resumeRun(serialRunId, null);
                require(resumeResp.getStatusCode().value() == 200, "RESUME expected 200: " + resumeResp.getBody());

                awaitCondition(Duration.ofSeconds(12), () ->
                        "SUCCEEDED".equals(getRunJson(controller, serialRunId).get("status").asText()),
                        () -> "Serial run should complete SUCCEEDED after HTTP /resume | run="
                                + getRunJson(controller, serialRunId) + " | health=" + controller.health().getBody());
                JsonNode serialFinal = getRunJson(controller, serialRunId);
                require(containsText(serialFinal.get("completedNodes"), "step_dispense")
                                && containsText(serialFinal.get("completedNodes"), "step_spin"),
                        "Serial run must complete both DeviceCore 206 and 207 steps");

                // ---- Scenario 2: Parallel Split/Join Island Workflow (Device 207 in --long mode) + PAUSE -> RESUME ----
                var p1Resp = controller.createRun("{\"workflowId\":1002,\"autoStart\":true}", "island-par-pause-" + UUID.randomUUID(), null);
                require(p1Resp.getStatusCode().value() == 201, "Parallel run create expected 201");
                String parRunId = JSON.readTree(p1Resp.getBody()).get("runId").asText();

                // Wait until Branch A (pipettor 206, fast 60ms) completes while Branch B (centrifuge 207, --long 1000ms+) is STILL running!
                awaitCondition(Duration.ofSeconds(10), () -> {
                    JsonNode ov = getRunJson(controller, parRunId);
                    return containsText(ov.get("completedNodes"), "par_dispense")
                            && !containsText(ov.get("completedNodes"), "par_spin");
                }, "Branch A should complete while long-task Branch B is still running on DeviceCore 207");

                // Request PAUSE mid-parallel while DeviceCore 207 is executing its --long Spin!
                require(controller.pauseRun(parRunId, null).getStatusCode().value() == 200, "Parallel PAUSE expected 200");

                awaitCondition(Duration.ofSeconds(12), () -> {
                    JsonNode ov = getRunJson(controller, parRunId);
                    return "PAUSED".equals(ov.get("status").asText())
                            && host.healthSnapshot().activeLocalWorkers() == 0;
                }, () -> "Parallel run must finish in-flight DeviceCore 207 --long command then converge to PAUSED | run="
                        + getRunJson(controller, parRunId) + " | health=" + controller.health().getBody());

                JsonNode parPaused = getRunJson(controller, parRunId);
                require(containsText(parPaused.get("completedNodes"), "par_spin"),
                        "In-flight DeviceCore 207 long-task should finish before PAUSED");
                require(!containsText(parPaused.get("completedNodes"), "par_final"),
                        "Post-Join par_final must NOT execute while PAUSED");

                require(controller.resumeRun(parRunId, null).getStatusCode().value() == 200, "Parallel RESUME expected 200");
                awaitCondition(Duration.ofSeconds(10), () ->
                        "SUCCEEDED".equals(getRunJson(controller, parRunId).get("status").asText()),
                        "Parallel run must converge to SUCCEEDED after RESUME");
                require(containsText(getRunJson(controller, parRunId).get("completedNodes"), "par_final"),
                        "Post-Join par_final must execute after RESUME");

                // ---- Scenario 3: Parallel Split/Join + HTTP STOP mid-branch ----
                var p2Resp = controller.createRun("{\"workflowId\":1002,\"autoStart\":true}", "island-par-stop-" + UUID.randomUUID(), null);
                require(p2Resp.getStatusCode().value() == 201, "Parallel stop run create expected 201");
                String parStopId = JSON.readTree(p2Resp.getBody()).get("runId").asText();

                awaitCondition(Duration.ofSeconds(10), () ->
                        containsText(getRunJson(controller, parStopId).get("completedNodes"), "par_dispense"),
                        "Parallel stop run Branch A should complete");
                require(controller.stopRun(parStopId, null).getStatusCode().value() == 200, "Parallel STOP expected 200");

                awaitCondition(Duration.ofSeconds(12), () -> {
                    JsonNode ov = getRunJson(controller, parStopId);
                    return "STOPPED".equals(ov.get("status").asText())
                            && ov.get("activeTokens").asInt() == 0
                            && host.healthSnapshot().activeLocalWorkers() == 0;
                }, () -> "Parallel run must converge to STOPPED after HTTP /stop | run="
                        + getRunJson(controller, parStopId) + " | health=" + controller.health().getBody());
                require(!containsText(getRunJson(controller, parStopId).get("completedNodes"), "par_final"),
                        "Post-Join par_final must NEVER run after STOP");

                // ---- Scenario 4: XOR Branch Island Workflow (__setVar string "92" vs "40") ----
                var xorPassResp = controller.createRun("{\"workflowId\":1003,\"autoStart\":true}", "island-xor-pass-" + UUID.randomUUID(), null);
                String xorPassId = JSON.readTree(xorPassResp.getBody()).get("runId").asText();
                awaitCondition(Duration.ofSeconds(10), () ->
                        "SUCCEEDED".equals(getRunJson(controller, xorPassId).get("status").asText()),
                        "XOR pass run should succeed");
                JsonNode xorPassOv = getRunJson(controller, xorPassId);
                require(containsText(xorPassOv.get("completedNodes"), "xor_pass_dispense")
                                && !containsText(xorPassOv.get("completedNodes"), "xor_fail_spin"),
                        "XOR score=92 must take pass branch only");

                var xorFailResp = controller.createRun("{\"workflowId\":1004,\"autoStart\":true}", "island-xor-fail-" + UUID.randomUUID(), null);
                String xorFailId = JSON.readTree(xorFailResp.getBody()).get("runId").asText();
                awaitCondition(Duration.ofSeconds(12), () ->
                        "SUCCEEDED".equals(getRunJson(controller, xorFailId).get("status").asText()),
                        "XOR fail run should succeed");
                JsonNode xorFailOv = getRunJson(controller, xorFailId);
                require(containsText(xorFailOv.get("completedNodes"), "xor_fail_spin")
                                && !containsText(xorFailOv.get("completedNodes"), "xor_pass_dispense"),
                        "XOR score=40 must take default fail branch only");

                // Baseline check: Scenarios 1-4 must produce 0 inbox_protocol_violation rows prior to explicit G5 conflict tests.
                require(scalarLong(ds,
                                "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation "
                                        + "WHERE tenant_id = (SELECT tenant_id FROM workflow_runtime.tenant "
                                        + "WHERE tenant_key = 'kernel-tenant:1')") == 0L,
                        "Expected 0 inbox_protocol_violation rows before G5 conflict scenarios");

                // ---- Scenario 5: G5 HTTP Signal Ingress (404 / 409 REJECTED / 200 COMPLETED / Replay / Conflict) ----
                HttpServletRequest opReq = fakeOperatorRequest("op-signal", "operator");
                var badRunSigResp = controller.receiveWaitSignal(
                        UUID.randomUUID().toString(),
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-unknown-run\",\"correlationKey\":\"qc.approved\",\"payload\":{\"ok\":true}}",
                        opReq);
                require(badRunSigResp.getStatusCode().value() == 404
                                && "NOT_FOUND".equals(JSON.readTree(badRunSigResp.getBody()).get("outcome").asText()),
                        "Unknown runId must return 404 NOT_FOUND, got: " + badRunSigResp.getBody());

                var sigCreateResp = controller.createRun(
                        "{\"workflowId\":1006,\"autoStart\":true}",
                        "island-signal-happy-" + UUID.randomUUID(), opReq);
                require(sigCreateResp.getStatusCode().value() == 201, "Wait workflow create expected 201");
                String sigRunId = JSON.readTree(sigCreateResp.getBody()).get("runId").asText();
                UUID sigRunUuid = UUID.fromString(sigRunId);

                awaitCondition(Duration.ofSeconds(10), () -> {
                    JsonNode ov = getRunJson(controller, sigRunId);
                    return containsText(ov.get("completedNodes"), "wait_pre_dispense")
                            && scalarLong(ds,
                            "SELECT count(*) FROM workflow_runtime.wait_registration WHERE run_id=? AND wait_state='ACTIVE' AND correlation_key='qc.approved'",
                            sigRunUuid) == 1L;
                }, () -> "Signal workflow must reach ACTIVE Wait with correlation_key=qc.approved | run="
                        + getRunJson(controller, sigRunId)
                        + " | waits=" + scalarLong(ds,
                        "SELECT count(*) FROM workflow_runtime.wait_registration WHERE run_id=?", sigRunUuid)
                        + " | health=" + controller.health().getBody());

                var wrongCorrResp = controller.receiveWaitSignal(
                        sigRunId,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-wrong-corr\",\"correlationKey\":\"wrong.key\",\"payload\":{}}",
                        opReq);
                JsonNode wrongCorrJson = JSON.readTree(wrongCorrResp.getBody());
                require(wrongCorrResp.getStatusCode().value() == 409
                                && "REJECTED".equals(wrongCorrJson.get("outcome").asText())
                                && wrongCorrJson.hasNonNull("inboxId"),
                        "Wrong correlationKey must return 409 REJECTED, got: " + wrongCorrResp.getBody());
                UUID wrongCorrInboxId = UUID.fromString(wrongCorrJson.get("inboxId").asText());
                require("REJECTED".equals(scalarString(ds,
                                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE inbox_id=?",
                                wrongCorrInboxId)),
                        "Wrong correlationKey inbox_receipt must be REJECTED");
                require("ACTIVE".equals(scalarString(ds,
                                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE run_id=?",
                                sigRunUuid)),
                        "Wrong correlationKey must leave Wait ACTIVE");

                var happySigResp = controller.receiveWaitSignal(
                        sigRunId,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-qc-1\",\"correlationKey\":\"qc.approved\",\"payload\":{\"qcPass\":true,\"plate\":\"P-01\"}}",
                        opReq);
                JsonNode happySigJson = JSON.readTree(happySigResp.getBody());
                require(happySigResp.getStatusCode().value() == 200
                                && "COMPLETED".equals(happySigJson.get("outcome").asText())
                                && !happySigJson.get("replayed").asBoolean()
                                && happySigJson.hasNonNull("waitId")
                                && happySigJson.hasNonNull("successorTokenId")
                                && happySigJson.hasNonNull("inboxId"),
                        "Valid HTTP signal must return 200 COMPLETED, got: " + happySigResp.getBody());
                String happyInboxId = happySigJson.get("inboxId").asText();

                awaitCondition(Duration.ofSeconds(12), () ->
                        "SUCCEEDED".equals(getRunJson(controller, sigRunId).get("status").asText()),
                        () -> "Signal run should complete SUCCEEDED | run=" + getRunJson(controller, sigRunId));
                require(containsText(getRunJson(controller, sigRunId).get("completedNodes"), "wait_post_spin"),
                        "Downstream DeviceCore 207 wait_post_spin must complete after HTTP signal");

                // Duplicate replay with reordered JSON keys -> 200 ALREADY_COMPLETED, same inboxId, zero duplicate execution
                var replaySigResp = controller.receiveWaitSignal(
                        sigRunId,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-qc-1\",\"correlationKey\":\"qc.approved\",\"payload\":{\"plate\":\"P-01\",\"qcPass\":true}}",
                        opReq);
                JsonNode replaySigJson = JSON.readTree(replaySigResp.getBody());
                require(replaySigResp.getStatusCode().value() == 200
                                && "ALREADY_COMPLETED".equals(replaySigJson.get("outcome").asText())
                                && replaySigJson.get("replayed").asBoolean()
                                && happyInboxId.equals(replaySigJson.get("inboxId").asText()),
                        "Duplicate replay with reordered JSON keys must return 200 ALREADY_COMPLETED with same inboxId, got: "
                                + replaySigResp.getBody());
                require(scalarLong(ds,
                                "SELECT count(*) FROM workflow_runtime.node_activation WHERE run_id=? AND node_key='wait_post_spin'",
                                sigRunUuid) == 1L,
                        "Duplicate replay must not create extra downstream node_activation");
                require(scalarLong(ds,
                                "SELECT count(*) FROM workflow_runtime.command_ledger WHERE run_id=?",
                                sigRunUuid) == 2L,
                        "Duplicate replay must not dispatch extra device command (expected 2 total)");

                // Conflicting payload on completed sourceMessageId -> 409 PROTOCOL_VIOLATION
                var conflictPayloadResp = controller.receiveWaitSignal(
                        sigRunId,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-qc-1\",\"correlationKey\":\"qc.approved\",\"payload\":{\"qcPass\":false,\"plate\":\"P-99\"}}",
                        opReq);
                require(conflictPayloadResp.getStatusCode().value() == 409
                                && "PROTOCOL_VIOLATION".equals(JSON.readTree(conflictPayloadResp.getBody()).get("outcome").asText()),
                        "Conflicting payload must return 409 PROTOCOL_VIOLATION, got: " + conflictPayloadResp.getBody());
                require(scalarLong(ds,
                                "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation "
                                        + "WHERE tenant_id = (SELECT tenant_id FROM workflow_runtime.tenant WHERE tenant_key = 'kernel-tenant:1')") == 1L,
                        "Expected 1 inbox_protocol_violation after conflicting payload");

                // Conflicting runId against a second ACTIVE Wait -> 409 PROTOCOL_VIOLATION, Wait untouched
                var sig2CreateResp = controller.createRun(
                        "{\"workflowId\":1006,\"autoStart\":true}",
                        "island-signal-run2-" + UUID.randomUUID(), opReq);
                require(sig2CreateResp.getStatusCode().value() == 201, "Second Wait workflow create expected 201");
                String sigRun2Id = JSON.readTree(sig2CreateResp.getBody()).get("runId").asText();
                UUID sigRun2Uuid = UUID.fromString(sigRun2Id);
                awaitCondition(Duration.ofSeconds(10), () -> scalarLong(ds,
                        "SELECT count(*) FROM workflow_runtime.wait_registration WHERE run_id=? AND wait_state='ACTIVE' AND correlation_key='qc.approved'",
                        sigRun2Uuid) == 1L,
                        "Second signal run must reach ACTIVE Wait");

                var conflictRunResp = controller.receiveWaitSignal(
                        sigRun2Id,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-qc-1\",\"correlationKey\":\"qc.approved\",\"payload\":{\"qcPass\":true,\"plate\":\"P-01\"}}",
                        opReq);
                require(conflictRunResp.getStatusCode().value() == 409
                                && "PROTOCOL_VIOLATION".equals(JSON.readTree(conflictRunResp.getBody()).get("outcome").asText()),
                        "Reusing sourceMessageId on different runId must return 409 PROTOCOL_VIOLATION, got: "
                                + conflictRunResp.getBody());
                require("ACTIVE".equals(scalarString(ds,
                                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE run_id=?",
                                sigRun2Uuid)),
                        "Conflicting runId signal must leave Run 2 Wait ACTIVE");
                require(scalarLong(ds,
                                "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation "
                                        + "WHERE tenant_id = (SELECT tenant_id FROM workflow_runtime.tenant WHERE tenant_key = 'kernel-tenant:1')") == 2L,
                        "Expected 2 inbox_protocol_violation rows after conflicting runId");

                var sig2SatisfyResp = controller.receiveWaitSignal(
                        sigRun2Id,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-qc-2\",\"correlationKey\":\"qc.approved\",\"payload\":{\"qcPass\":true}}",
                        opReq);
                require(sig2SatisfyResp.getStatusCode().value() == 200
                                && "COMPLETED".equals(JSON.readTree(sig2SatisfyResp.getBody()).get("outcome").asText()),
                        "Fresh sourceMessageId must satisfy Run 2 Wait");
                awaitCondition(Duration.ofSeconds(12), () ->
                        "SUCCEEDED".equals(getRunJson(controller, sigRun2Id).get("status").asText()),
                        "Run 2 should reach SUCCEEDED after fresh signal");

                // ---- Scenario 6: PAUSED Run Deferred Signal (202) -> RESUME Auto-Replay ----
                var pauseSigCreateResp = controller.createRun(
                        "{\"workflowId\":1006,\"autoStart\":true}",
                        "island-signal-pause-" + UUID.randomUUID(), opReq);
                require(pauseSigCreateResp.getStatusCode().value() == 201, "Paused signal run create expected 201");
                String pauseSigRunId = JSON.readTree(pauseSigCreateResp.getBody()).get("runId").asText();
                UUID pauseSigRunUuid = UUID.fromString(pauseSigRunId);
                awaitCondition(Duration.ofSeconds(10), () -> scalarLong(ds,
                        "SELECT count(*) FROM workflow_runtime.wait_registration WHERE run_id=? AND wait_state='ACTIVE' AND correlation_key='qc.approved'",
                        pauseSigRunUuid) == 1L,
                        () -> "Paused signal run must reach ACTIVE Wait | run=" + getRunJson(controller, pauseSigRunId));

                require(controller.pauseRun(pauseSigRunId, opReq).getStatusCode().value() == 200,
                        "PAUSE before deferred signal expected 200");
                awaitCondition(Duration.ofSeconds(10), () ->
                        "PAUSED".equals(getRunJson(controller, pauseSigRunId).get("status").asText())
                                && host.healthSnapshot().activeLocalWorkers() == 0,
                        "Run must converge to PAUSED before sending deferred signal");

                var deferredResp = controller.receiveWaitSignal(
                        pauseSigRunId,
                        "{\"source\":\"lims\",\"sourceMessageId\":\"msg-paused-1\",\"correlationKey\":\"qc.approved\",\"payload\":{\"deferred\":true}}",
                        opReq);
                JsonNode deferredJson = JSON.readTree(deferredResp.getBody());
                require(deferredResp.getStatusCode().value() == 202
                                && "DEFERRED_RUN_NOT_OPEN".equals(deferredJson.get("outcome").asText())
                                && deferredJson.get("accepted").asBoolean()
                                && deferredJson.get("deferred").asBoolean()
                                && deferredJson.hasNonNull("inboxId"),
                        "Signal while PAUSED must return 202 DEFERRED_RUN_NOT_OPEN, got: " + deferredResp.getBody());
                UUID deferredInboxId = UUID.fromString(deferredJson.get("inboxId").asText());
                require("ACTIVE".equals(scalarString(ds,
                                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE run_id=?",
                                pauseSigRunUuid)),
                        "Deferred signal must leave Wait ACTIVE while PAUSED");
                require("RECEIVED".equals(scalarString(ds,
                                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE inbox_id=?",
                                deferredInboxId)),
                        "Deferred signal inbox_receipt must remain RECEIVED while PAUSED");
                require(!containsText(getRunJson(controller, pauseSigRunId).get("completedNodes"), "wait_post_spin"),
                        "Downstream wait_post_spin must NOT execute while PAUSED");

                require(controller.resumeRun(pauseSigRunId, opReq).getStatusCode().value() == 200,
                        "RESUME after deferred signal expected 200");
                awaitCondition(Duration.ofSeconds(12), () ->
                        "SUCCEEDED".equals(getRunJson(controller, pauseSigRunId).get("status").asText()),
                        () -> "Paused run with deferred signal should resume and SUCCEED | run="
                                + getRunJson(controller, pauseSigRunId));
                require(containsText(getRunJson(controller, pauseSigRunId).get("completedNodes"), "wait_post_spin"),
                        "Downstream wait_post_spin must complete after RESUME auto-replay");
                require("PROCESSED".equals(scalarString(ds,
                                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE inbox_id=?",
                                deferredInboxId)),
                        "Deferred inbox_receipt must transition to PROCESSED after RESUME replay");
                require("SATISFIED".equals(scalarString(ds,
                                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE run_id=?",
                                pauseSigRunUuid)),
                        "Wait must transition to SATISFIED after RESUME replay");

                // ---- Scenario 7: Event vs Timer Timeout Race (MESSAGE_WITH_TIMEOUT PT0.4S on live PG17) ----
                long[] offsetsMs = {-220L, -30L, 0L, 30L, 260L};
                int satisfiedCount = 0;
                int expiredCount = 0;
                for (int t = 0; t < offsetsMs.length; t++) {
                    var raceCreateResp = controller.createRun(
                            "{\"workflowId\":1007,\"autoStart\":true}",
                            "island-race-" + t + "-" + UUID.randomUUID(), opReq);
                    require(raceCreateResp.getStatusCode().value() == 201, "Race trial create expected 201");
                    String raceRunId = JSON.readTree(raceCreateResp.getBody()).get("runId").asText();
                    UUID raceRunUuid = UUID.fromString(raceRunId);

                    awaitCondition(Duration.ofSeconds(10), () -> scalarLong(ds,
                            "SELECT count(*) FROM workflow_runtime.wait_registration WHERE run_id=? AND wait_state='ACTIVE'",
                            raceRunUuid) == 1L,
                            "Race trial Wait did not become ACTIVE");

                    Double remainingMs = scalarDouble(ds,
                            "SELECT EXTRACT(EPOCH FROM (deadline_at - clock_timestamp())) * 1000.0 "
                                    + "FROM workflow_runtime.wait_registration WHERE run_id=? AND wait_state='ACTIVE'",
                            raceRunUuid);
                    long sleepMs = remainingMs == null ? 0L : Math.max(0L, Math.round(remainingMs) + offsetsMs[t]);
                    if (sleepMs > 0L) {
                        Thread.sleep(sleepMs);
                    }

                    var raceSigResp = controller.receiveWaitSignal(
                            raceRunId,
                            "{\"source\":\"lims\",\"sourceMessageId\":\"msg-race-" + t + "\",\"correlationKey\":\"race.go\",\"payload\":{\"trial\":" + t + "}}",
                            opReq);
                    int raceStatus = raceSigResp.getStatusCode().value();
                    String raceOutcome = JSON.readTree(raceSigResp.getBody()).get("outcome").asText();
                    require((raceStatus == 200 && "COMPLETED".equals(raceOutcome))
                                    || (raceStatus == 409 && "REJECTED".equals(raceOutcome)),
                            "Race trial HTTP outcome must be 200 COMPLETED or 409 REJECTED, got "
                                    + raceStatus + " body=" + raceSigResp.getBody());

                    awaitCondition(Duration.ofSeconds(12), () ->
                            "SUCCEEDED".equals(getRunJson(controller, raceRunId).get("status").asText()),
                            () -> "Race trial should reach SUCCEEDED | run=" + getRunJson(controller, raceRunId));
                    JsonNode raceOv = getRunJson(controller, raceRunId);
                    boolean hasNormal = containsText(raceOv.get("completedNodes"), "race_normal_dispense");
                    boolean hasTimeout = containsText(raceOv.get("completedNodes"), "race_timeout_spin");
                    require(hasNormal ^ hasTimeout,
                            "Race trial " + t + " must execute strictly one branch (normal=" + hasNormal
                                    + ", timeout=" + hasTimeout + ")");
                    String expectedWaitState = hasNormal ? "SATISFIED" : "EXPIRED";
                    String actualWaitState = scalarString(ds,
                            "SELECT wait_state FROM workflow_runtime.wait_registration WHERE run_id=?",
                            raceRunUuid);
                    require(expectedWaitState.equals(actualWaitState),
                            "Race trial " + t + " expected wait_state=" + expectedWaitState + ", got " + actualWaitState);
                    require(scalarLong(ds,
                                    "SELECT count(*) FROM workflow_runtime.node_activation "
                                            + "WHERE run_id=? AND node_key IN ('race_normal_dispense', 'race_timeout_spin')",
                                    raceRunUuid) == 1L,
                            "Race trial " + t + " must create strictly 1 downstream node_activation");
                    if (hasNormal) {
                        satisfiedCount++;
                    } else {
                        expiredCount++;
                    }
                }
                require(satisfiedCount >= 1 && expiredCount >= 1,
                        "Staggered race trials must exercise both SATISFIED (" + satisfiedCount
                                + ") and EXPIRED (" + expiredCount + ") paths");

                // ---- Scenario 8: Real DeviceCore EC-06-0004 fault -> MANUAL_REQUIRED observability ----
                // Kept last among workflow scenarios because failAttempt keeps device:206 capacity reserved until reconciled.
                var faultResp = controller.createRun("{\"workflowId\":1005,\"autoStart\":true}", "island-fault-" + UUID.randomUUID(), null);
                String faultRunId = JSON.readTree(faultResp.getBody()).get("runId").asText();
                awaitCondition(Duration.ofSeconds(10), () -> {
                    try {
                        JsonNode mr = JSON.readTree(controller.listManualRequiredRuns(50).getBody());
                        for (JsonNode item : mr.get("items")) {
                            if (faultRunId.equals(item.get("runId").asText())
                                    && host.healthSnapshot().activeLocalWorkers() == 0) {
                                return true;
                            }
                        }
                        return false;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, "Unsupported method on DeviceCore 206 must surface in GET /api/runtime/runs/manual-required");

                // ---- Scenario 9: Final Health, Spontaneous Frames & Audit Trail Verification ----
                Thread.sleep(120); // allow last spontaneous Dispense=process_done@30* frame to arrive
                var dev206Health = host.healthSnapshot().devices().get("206");
                var dev207Health = host.healthSnapshot().devices().get("207");
                require(dev206Health.malformedFrames() == 0,
                        "Device 206 spontaneous frames must cause 0 malformedFrames, got " + dev206Health.malformedFrames());
                require(dev207Health.malformedFrames() == 0,
                        "Device 207 long-task frames must cause 0 malformedFrames, got " + dev207Health.malformedFrames());
                require(dev206Health.heartbeatsSent() == 0 && dev207Health.heartbeatsSent() == 0,
                        "LabSep devices must receive 0 unsolicited NDJSON heartbeats");
                var dispatchStats = host.healthSnapshot().dispatchStats();
                require(dispatchStats.cycleErrors() == 0,
                        "Expected 0 dispatch cycleErrors for isolated island tenant, got "
                                + dispatchStats.cycleErrors() + " (lastError=" + dispatchStats.lastError() + ")");
                require(scalarLong(ds,
                                "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation "
                                        + "WHERE tenant_id = (SELECT tenant_id FROM workflow_runtime.tenant "
                                        + "WHERE tenant_key = 'kernel-tenant:1')") == 2L,
                        "Expected strictly 2 inbox_protocol_violation rows from the explicit conflict scenarios");
                require(auditActions.containsAll(List.of(
                        "runtime.run.create", "runtime.run.pause", "runtime.run.resume", "runtime.run.stop",
                        "runtime.wait.signal")),
                        "All write actions must record audit entries, got: " + auditActions);

                boolean sawCompletedAudit = false;
                boolean sawConflictAudit = false;
                boolean sawDeferredAudit = false;
                for (CapturedAudit entry : capturedAudits) {
                    if (!"runtime.wait.signal".equals(entry.action()) || entry.detail() == null) {
                        continue;
                    }
                    JsonNode d = JSON.readTree(entry.detail());
                    String outcome = d.path("outcome").asText();
                    if (sigRunId.equals(entry.entityKey())
                            && entry.who() != null
                            && "op-signal".equals(entry.who().username())
                            && "lims".equals(d.path("source").asText())
                            && "msg-qc-1".equals(d.path("sourceMessageId").asText())
                            && "qc.approved".equals(d.path("correlationKey").asText())
                            && "COMPLETED".equals(outcome)) {
                        sawCompletedAudit = true;
                    } else if ("PROTOCOL_VIOLATION".equals(outcome)) {
                        sawConflictAudit = true;
                    } else if ("DEFERRED_RUN_NOT_OPEN".equals(outcome)) {
                        sawDeferredAudit = true;
                    }
                }
                require(sawCompletedAudit && sawConflictAudit && sawDeferredAudit,
                        "Audit trail must capture COMPLETED, PROTOCOL_VIOLATION, and DEFERRED_RUN_NOT_OPEN signal events");
            }

            System.out.println("Workflow Runtime Island + Real DeviceCore Simulator E2E：通过 "
                    + PASSED_ASSERTIONS.get() + ", 失败 0");
        } finally {
            pipettor.stopServer();
            centrifuge.stopServer();
            deleteRecursively(tempDir);
        }
    }

    private static JsonNode getRunJson(WorkflowRuntimeController controller, String runId) {
        try {
            var resp = controller.getRun(runId);
            if (resp.getStatusCode().value() != 200) {
                throw new AssertionError("GET /api/runtime/runs/" + runId + " returned " + resp.getStatusCode());
            }
            return JSON.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsText(JsonNode arrayNode, String target) {
        if (arrayNode == null || !arrayNode.isArray()) return false;
        for (JsonNode item : arrayNode) {
            if (target.equals(item.asText())) return true;
        }
        return false;
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond, String desc)
            throws InterruptedException {
        awaitCondition(timeout, cond, () -> desc);
    }

    private static void awaitCondition(
            Duration timeout,
            java.util.function.BooleanSupplier cond,
            java.util.function.Supplier<String> desc)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        require(cond.getAsBoolean(), "Timeout (" + timeout + "): " + desc.get());
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        PASSED_ASSERTIONS.incrementAndGet();
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Realistic bpmn-js saved XML with BPMNDiagram, incoming/outgoing, __sleep (sec), and Island device resources 501/502. */
    private static String serialIslandBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                                  id="Defs_Serial" targetNamespace="http://labos.local/bpmn">
                  <bpmn:process id="Proc_Serial" isExecutable="true">
                    <bpmn:startEvent id="Start_1" name="START">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:task id="step_dispense" name="Dispense" protocolName="Dispense" resource="501" methodParams="{&quot;volume&quot;:120}" icon="liquid">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:task>
                    <bpmn:task id="step_sleep" name="__sleep" protocolName="__sleep" methodParams="{&quot;sec&quot;:&quot;0.25&quot;}">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:task>
                    <bpmn:task id="step_spin" name="Load" protocolName="Load" resource="502" methodParams="{}" icon="centrifuge">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                      <bpmn:outgoing>Flow_4</bpmn:outgoing>
                    </bpmn:task>
                    <bpmn:endEvent id="End_1" name="END">
                      <bpmn:incoming>Flow_4</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="step_dispense"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="step_dispense" targetRef="step_sleep"/>
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="step_sleep" targetRef="step_spin"/>
                    <bpmn:sequenceFlow id="Flow_4" sourceRef="step_spin" targetRef="End_1"/>
                  </bpmn:process>
                  <bpmndi:BPMNDiagram id="Diagram_Serial">
                    <bpmndi:BPMNPlane id="Plane_Serial" bpmnElement="Proc_Serial">
                      <bpmndi:BPMNShape id="Shape_Start" bpmnElement="Start_1">
                        <dc:Bounds x="100" y="100" width="36" height="36"/>
                      </bpmndi:BPMNShape>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """;
    }

    private static String parallelIslandBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  id="Defs_Parallel" targetNamespace="http://labos.local/bpmn">
                  <bpmn:process id="Proc_Parallel" isExecutable="true">
                    <bpmn:startEvent id="Start_1" name="START"/>
                    <bpmn:parallelGateway id="Gw_Split" name="PARALLEL_SPLIT" gatewayDirection="Diverging"/>
                    <bpmn:task id="par_dispense" name="Dispense" protocolName="Dispense" resource="501" methodParams="{&quot;volume&quot;:80}"/>
                    <bpmn:task id="par_spin" name="Load" protocolName="Load" resource="502" methodParams="{}"/>
                    <bpmn:parallelGateway id="Gw_Join" name="PARALLEL_MERGE" gatewayDirection="Converging"/>
                    <bpmn:task id="par_final" name="Mix" protocolName="Mix" resource="501" methodParams="{&quot;volume&quot;:50}"/>
                    <bpmn:endEvent id="End_1" name="END"/>
                    <bpmn:sequenceFlow id="f0" sourceRef="Start_1" targetRef="Gw_Split"/>
                    <bpmn:sequenceFlow id="fa" sourceRef="Gw_Split" targetRef="par_dispense"/>
                    <bpmn:sequenceFlow id="fb" sourceRef="Gw_Split" targetRef="par_spin"/>
                    <bpmn:sequenceFlow id="faj" sourceRef="par_dispense" targetRef="Gw_Join"/>
                    <bpmn:sequenceFlow id="fbj" sourceRef="par_spin" targetRef="Gw_Join"/>
                    <bpmn:sequenceFlow id="fj" sourceRef="Gw_Join" targetRef="par_final"/>
                    <bpmn:sequenceFlow id="fe" sourceRef="par_final" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String xorIslandBpmn(String scoreValue) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  id="Defs_Xor" targetNamespace="http://labos.local/bpmn">
                  <bpmn:process id="Proc_Xor" isExecutable="true">
                    <bpmn:startEvent id="Start_1" name="START"/>
                    <bpmn:task id="set_score" name="__setVar" protocolName="__setVar" methodParams="{&quot;var&quot;:&quot;score&quot;,&quot;value&quot;:&quot;%s&quot;}"/>
                    <bpmn:exclusiveGateway id="Gw_Xor" name="XOR_SPLIT" gatewayDirection="Diverging" default="f_fail"/>
                    <bpmn:task id="xor_pass_dispense" name="Dispense" protocolName="Dispense" resource="501" methodParams="{&quot;volume&quot;:100}"/>
                    <bpmn:task id="xor_fail_spin" name="Unload" protocolName="Unload" resource="502" methodParams="{}"/>
                    <bpmn:exclusiveGateway id="Gw_Merge" name="XOR_MERGE" gatewayDirection="Converging"/>
                    <bpmn:endEvent id="End_1" name="END"/>
                    <bpmn:sequenceFlow id="f0" sourceRef="Start_1" targetRef="set_score"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="set_score" targetRef="Gw_Xor"/>
                    <bpmn:sequenceFlow id="f_pass" sourceRef="Gw_Xor" targetRef="xor_pass_dispense">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${score} &gt;= 80</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="f_fail" sourceRef="Gw_Xor" targetRef="xor_fail_spin"/>
                    <bpmn:sequenceFlow id="fp2" sourceRef="xor_pass_dispense" targetRef="Gw_Merge"/>
                    <bpmn:sequenceFlow id="ff2" sourceRef="xor_fail_spin" targetRef="Gw_Merge"/>
                    <bpmn:sequenceFlow id="fe" sourceRef="Gw_Merge" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(scoreValue);
    }

    private static String unsupportedMethodFaultBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="Proc_Fault" isExecutable="true">
                    <bpmn:startEvent id="Start_1" name="START"/>
                    <bpmn:task id="bad_op" name="NonExistentCmd" protocolName="NonExistentCmd" resource="501" methodParams="{}"/>
                    <bpmn:endEvent id="End_1" name="END"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="Start_1" targetRef="bad_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="bad_op" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String waitMessageIslandBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1"
                                  id="Defs_WaitMessage" targetNamespace="http://labos.local/bpmn">
                  <bpmn:process id="Proc_WaitMessage" isExecutable="true">
                    <bpmn:startEvent id="Start_1" name="START"/>
                    <bpmn:task id="wait_pre_dispense" name="Dispense" protocolName="Dispense" resource="501" methodParams="{&quot;volume&quot;:50}"/>
                    <bpmn:receiveTask id="wait_qc" name="WaitQC">
                      <bpmn:extensionElements><labos:wait correlationKey="qc.approved"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:task id="wait_post_spin" name="Load" protocolName="Load" resource="502" methodParams="{}"/>
                    <bpmn:endEvent id="End_1" name="END"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="Start_1" targetRef="wait_pre_dispense"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="wait_pre_dispense" targetRef="wait_qc"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="wait_qc" targetRef="wait_post_spin"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="wait_post_spin" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String waitTimeoutRaceIslandBpmn(String durationIso) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1"
                                  id="Defs_WaitRace" targetNamespace="http://labos.local/bpmn">
                  <bpmn:process id="Proc_WaitRace" isExecutable="true">
                    <bpmn:startEvent id="Start_1" name="START"/>
                    <bpmn:receiveTask id="wait_race" name="WaitRace">
                      <bpmn:extensionElements><labos:wait correlationKey="race.go"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="bd_race_timeout" attachedToRef="wait_race" cancelActivity="true">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>%s</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:task id="race_normal_dispense" name="Dispense" protocolName="Dispense" resource="501" methodParams="{&quot;volume&quot;:60}"/>
                    <bpmn:task id="race_timeout_spin" name="Load" protocolName="Load" resource="502" methodParams="{}"/>
                    <bpmn:endEvent id="End_Normal" name="END"/>
                    <bpmn:endEvent id="End_Timeout" name="END"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="Start_1" targetRef="wait_race"/>
                    <bpmn:sequenceFlow id="f_ok" sourceRef="wait_race" targetRef="race_normal_dispense"/>
                    <bpmn:sequenceFlow id="f_to" sourceRef="bd_race_timeout" targetRef="race_timeout_spin"/>
                    <bpmn:sequenceFlow id="f_ok_end" sourceRef="race_normal_dispense" targetRef="End_Normal"/>
                    <bpmn:sequenceFlow id="f_to_end" sourceRef="race_timeout_spin" targetRef="End_Timeout"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(durationIso);
    }

    private static HttpServletRequest fakeOperatorRequest(String username, String role) {
        AuthService.Session session = new AuthService.Session(
                "tok-" + username, 1L, username, username, role, Instant.now().plusSeconds(3600));
        return (HttpServletRequest) Proxy.newProxyInstance(
                WorkflowRuntimeIslandSimulatorSelfTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getAttribute" -> ApiAuthInterceptor.SESSION_ATTR.equals(a[0]) ? session : null;
                    case "getRemoteAddr" -> "127.0.0.1";
                    default -> {
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) yield false;
                        if (rt == int.class) yield 0;
                        if (rt == long.class) yield 0L;
                        yield null;
                    }
                });
    }

    private interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws Exception;
    }

    private static <T> T queryScalar(DataSource ds, String sql, ResultSetExtractor<T> extractor, Object... args) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? extractor.extract(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("SQL scalar query failed: " + sql, e);
        }
    }

    private static long scalarLong(DataSource ds, String sql, Object... args) {
        Long value = queryScalar(ds, sql, rs -> {
            Number n = (Number) rs.getObject(1);
            return n == null ? 0L : n.longValue();
        }, args);
        return value == null ? 0L : value;
    }

    private static String scalarString(DataSource ds, String sql, Object... args) {
        return queryScalar(ds, sql, rs -> rs.getString(1), args);
    }

    private static Double scalarDouble(DataSource ds, String sql, Object... args) {
        return queryScalar(ds, sql, rs -> {
            Number n = (Number) rs.getObject(1);
            return n == null ? null : n.doubleValue();
        }, args);
    }

    private WorkflowRuntimeIslandSimulatorSelfTest() {}
}
