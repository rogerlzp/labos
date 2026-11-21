package com.labos.kernel.workflow.runtime.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end PG17 + loopback TCP integration tests for {@link LabSepWireCodec}
 * ({@code #!LABSEP!#} legacy {@code .NET} driver protocol) running through
 * {@link TcpDeviceConnection}, {@link DeviceGateway}, and {@link EmbeddedWorkflowRuntimeHost}.
 */
class LabSepGatewayIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private DriverManagerDataSource dataSource() {
        String host = env("PGHOST", "localhost");
        String port = env("PGPORT", "5432");
        String db = env("PGDATABASE", "runtime_test");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + host + ":" + port + "/" + db,
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static long scalar(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String singleText(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static UUID createTenant(DriverManagerDataSource ds, String prefix) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','" + prefix + "-" + tenant + "','labsep-it')");
        }
        return tenant;
    }

    private static GraphPlan compileSingleDevicePlan(String planKey) {
        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="labsep_op" protocolName="Dispense" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="labsep_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="labsep_op" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
    }

    private static TcpDeviceConnection.Config gatewayConfig(int maxFrameBytes) {
        return new TcpDeviceConnection.Config(
                Duration.ofSeconds(2),
                Duration.ofMillis(120),
                Duration.ofMillis(400),
                Duration.ofMillis(40),
                Duration.ofMillis(150),
                maxFrameBytes,
                64);
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond, String desc)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(15);
        }
        assertTrue(cond.getAsBoolean(), "Condition not satisfied within " + timeout + ": " + desc);
    }

    private static WorkflowRuntimeRepository.CreatedRun seedRun(
            WorkflowRuntimeRepository repo, UUID tenant, String planKey) {
        GraphPlan plan = compileSingleDevicePlan(planKey);
        return repo.createRun(tenant, "req-" + planKey, "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
    }

    private static WorkerTaskHandler pollingDeviceWorker(
            DriverManagerDataSource ds,
            String deviceId,
            Duration pollTimeout,
            AtomicReference<UUID> capturedCommandId) {
        return (req, ctx) -> {
            UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"volumeUl\":250}");
            if (capturedCommandId != null) {
                capturedCommandId.set(cmdId);
            }
            long deadline = System.nanoTime() + pollTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                String state = singleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                + req.tenantId() + "' AND command_id='" + cmdId + "'");
                if ("SUCCEEDED".equals(state)) {
                    return WorkerTaskHandler.TaskResult.of("out", "{\"dispensed\":250}");
                }
                if ("FAILED".equals(state)) {
                    throw new RuntimeException("device reported FAILED for command " + cmdId);
                }
                if ("UNKNOWN".equals(state)) {
                    throw new RuntimeException("command entered UNKNOWN: " + cmdId);
                }
                Thread.sleep(15);
            }
            throw new RuntimeException("device command timed out waiting for reply: " + cmdId);
        };
    }

    @Test
    void labSepLongTaskProgressAndSuccessConvergesWithoutAppHeartbeatOrEventAck() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "labsep-happy");
        String deviceId = "pipettor-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();

        try (MockLabSepServer server = new MockLabSepServer((req, conn) -> {
            String msgId = req.get("message_id").asText();
            String method = req.get("method").asText();
            String eqId = req.get("equipment_id").asText();
            try {
                // Step 1: long-task RUNNING (method_status = 1)
                conn.sendReply(msgId, method, eqId, 1, null, Map.of("progress", 50), "2026-11-20T23:00:01Z");
                Thread.sleep(60);
                // Step 2: terminal SUCCEEDED (method_status = 2)
                conn.sendReply(msgId, method, eqId, 2, null, Map.of("dispensed", 250), "2026-11-20T23:00:02Z");
            } catch (Exception ignored) {
            }
        })) {
            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("labsep-happy.lock"),
                    "labsep-happy-host",
                    2, 16, 4, 2, 32, 50,
                    Duration.ofMillis(25), 16,
                    List.of(new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", server.port(), LabSepWireCodec.INSTANCE)),
                    gatewayConfig(4096),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "labsep-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.of(pollingDeviceWorker(ds, deviceId, Duration.ofSeconds(8), cmdIdRef)))) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                // Wait longer than heartbeatTimeout (400ms) while idle to prove LabSep codec does NOT emit
                // HEARTBEAT_PING or falsely disconnect idle legacy drivers.
                Thread.sleep(550);
                var idleHealth = host.healthSnapshot().devices().get(deviceId);
                assertEquals(TcpDeviceConnection.ConnectionStatus.ONLINE, idleHealth.status());
                assertEquals(0L, idleHealth.heartbeatsSent());
                assertEquals(0L, idleHealth.reconnectCount());

                var run = seedRun(repo, tenant, "labsep-happy-" + UUID.randomUUID());
                host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return "SUCCEEDED".equals(singleText(ds,
                                "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id='" + tenant
                                        + "' AND run_id='" + run.runId() + "'"));
                    } catch (Exception e) {
                        return false;
                    }
                }, "Run should reach SUCCEEDED via LabSep protocol");

                UUID cmdId = cmdIdRef.get();
                assertNotNull(cmdId);
                assertEquals("SUCCEEDED", singleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='" + tenant
                                + "' AND command_id='" + cmdId + "'"));
                String inboxOutcome = singleText(ds,
                        "SELECT message_payload::text FROM workflow_runtime.inbox_receipt WHERE tenant_id='" + tenant
                                + "' AND run_id='" + run.runId() + "' AND message_type='COMMAND_RESULT'");
                assertTrue(inboxOutcome != null && inboxOutcome.contains("250"),
                        "Inbox COMMAND_RESULT payload should contain data: " + inboxOutcome);

                assertEquals(2L, scalar(ds,
                        "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='" + tenant
                                + "' AND run_id='" + run.runId() + "' AND receipt_state='PROCESSED'"));
                assertEquals(0L, scalar(ds,
                        "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='" + tenant + "'"));

                // Verify wire frames sent to the legacy server: strictly ONE message_type=1 command, NO ping/ACK frames.
                assertEquals(1, server.receivedFrames().size());
                JsonNode sent = server.receivedFrames().get(0);
                assertEquals(1, sent.get("message_type").asInt());
                assertEquals(cmdId.toString(), sent.get("message_id").asText());
                assertEquals("Dispense", sent.get("method").asText());
                assertEquals(deviceId, sent.get("equipment_id").asText());
                assertEquals(250, sent.get("parameters").get("volumeUl").asInt());

                var finalHealth = host.healthSnapshot().devices().get(deviceId);
                assertEquals(0L, finalHealth.heartbeatsSent());
                assertEquals(0L, finalHealth.malformedFrames());
                assertEquals(2L, finalHealth.eventsDelivered());
            }
        }
    }

    @Test
    void labSepDuplicateReplayUnsolicitedBroadcastOversizedAndSpoofing() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "labsep-edge");
        String deviceId = "centrifuge-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();
        CopyOnWriteArrayList<RuntimeDeviceEventBridge.BridgeNotification> notifications = new CopyOnWriteArrayList<>();

        try (MockLabSepServer server = new MockLabSepServer((req, conn) -> {
            String msgId = req.get("message_id").asText();
            String method = req.get("method").asText();
            String eqId = req.get("equipment_id").asText();
            try {
                // 1. Unsolicited legacy broadcast without message_id (must be ignored cleanly).
                conn.sendRawJson("{\"message_type\":5,\"equipment_status\":1,\"connected\":1}");
                // 2. Oversized frame exceeding maxFrameBytes=512 (must increment malformedFrames without disconnecting).
                conn.sendRawJson("{\"padding\":\"" + "X".repeat(700) + "\"}");
                // 3. Spoofed equipment_id reply (must increment spoofedFramesRejected).
                conn.sendReply(msgId, method, "spoofed-other-device", 2, null, Map.of("ok", true), "2026-11-20T23:01:00Z");
                // 4. Legitimate SUCCEEDED reply sent TWICE (second must converge as DUPLICATE_REPLAY).
                conn.sendReply(msgId, method, eqId, 2, null, Map.of("rpm", 3000), "2026-11-20T23:01:01Z");
                conn.sendReply(msgId, method, eqId, 2, null, Map.of("rpm", 3000), "2026-11-20T23:01:01Z");
            } catch (Exception ignored) {
            }
        })) {
            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("labsep-edge.lock"),
                    "labsep-edge-host",
                    2, 16, 4, 2, 32, 50,
                    Duration.ofMillis(25), 16,
                    List.of(new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", server.port(), LabSepWireCodec.INSTANCE)),
                    gatewayConfig(512),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "labsep-edge-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.of(pollingDeviceWorker(ds, deviceId, Duration.ofSeconds(8), cmdIdRef)),
                    notifications::add)) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                var run = seedRun(repo, tenant, "labsep-edge-" + UUID.randomUUID());
                host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        boolean succeeded = "SUCCEEDED".equals(singleText(ds,
                                "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id='" + tenant
                                        + "' AND run_id='" + run.runId() + "'"));
                        boolean sawReplay = notifications.stream().anyMatch(n ->
                                n.outcome() != null && n.outcome().status()
                                        == DeviceCommandExecutionService.InboxStatus.DUPLICATE_REPLAY);
                        return succeeded && sawReplay;
                    } catch (Exception e) {
                        return false;
                    }
                }, "Run should succeed and duplicate LabSep frame should be handled as DUPLICATE_REPLAY");

                var health = host.healthSnapshot().devices().get(deviceId);
                assertEquals(1L, health.malformedFrames(), "Only the oversized frame should count as malformed");
                assertEquals(1L, health.spoofedFramesRejected(), "Spoofed equipment_id must be rejected");
                assertEquals(0L, scalar(ds,
                        "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='" + tenant + "'"));
                assertEquals(1L, scalar(ds,
                        "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='" + tenant
                                + "' AND run_id='" + run.runId() + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));
            }
        }
    }

    @Test
    void labSepDeviceFaultMethodStatus3PreservesErrorAndEscalatesManualRequired() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "labsep-fault");
        String deviceId = "printer-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();

        try (MockLabSepServer server = new MockLabSepServer((req, conn) -> {
            String msgId = req.get("message_id").asText();
            String method = req.get("method").asText();
            String eqId = req.get("equipment_id").asText();
            try {
                conn.sendReply(msgId, method, eqId, 3,
                        Map.of("error_code", "0x8000000C", "error_text", "No paper detected"),
                        Map.of(), "2026-11-20T23:02:00Z");
            } catch (Exception ignored) {
            }
        })) {
            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("labsep-fault.lock"),
                    "labsep-fault-host",
                    2, 16, 4, 2, 32, 50,
                    Duration.ofMillis(25), 16,
                    List.of(new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", server.port(), LabSepWireCodec.INSTANCE)),
                    gatewayConfig(4096),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "labsep-fault-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.of(pollingDeviceWorker(ds, deviceId, Duration.ofSeconds(8), cmdIdRef)))) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                var run = seedRun(repo, tenant, "labsep-fault-" + UUID.randomUUID());
                host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        var snap = host.healthSnapshot();
                        boolean manualRun = host.listManualRequiredRuns(tenant, 10).stream()
                                .anyMatch(r -> r.runId().equals(run.runId()));
                        long unknownAttempts = scalar(ds,
                                "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='" + tenant
                                        + "' AND run_id='" + run.runId() + "' AND attempt_state='UNKNOWN' AND error IS NOT NULL");
                        return manualRun && snap.activeLocalWorkers() == 0 && unknownAttempts == 1L;
                    } catch (Exception e) {
                        return false;
                    }
                }, "Device fault method_status=3 should escalate Run to MANUAL_REQUIRED");

                UUID cmdId = cmdIdRef.get();
                assertNotNull(cmdId);
                assertEquals("FAILED", singleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='" + tenant
                                + "' AND command_id='" + cmdId + "'"));
                String inboxError = singleText(ds,
                        "SELECT message_payload::text FROM workflow_runtime.inbox_receipt WHERE tenant_id='" + tenant
                                + "' AND run_id='" + run.runId() + "' AND message_type='COMMAND_RESULT'");
                assertTrue(inboxError != null && inboxError.contains("0x8000000C"),
                        "Device error_code must be preserved in inbox_receipt.message_payload: " + inboxError);
            }
        }
    }

    @Test
    void labSepDisconnectAfterSendStartedReconnectsWithoutAutoResendAndSupportsT21() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "labsep-drop");
        String deviceId = "arm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicBoolean dropFirst = new AtomicBoolean(true);
        CountDownLatch firstDropped = new CountDownLatch(1);
        CountDownLatch allowFirstWorkerExit = new CountDownLatch(1);
        AtomicReference<UUID> firstCmdRef = new AtomicReference<>();

        try (MockLabSepServer server = new MockLabSepServer((req, conn) -> {
            if (dropFirst.compareAndSet(true, false)) {
                // Drop TCP socket right after receiving command #1 without replying.
                conn.forceClose();
                firstDropped.countDown();
                return;
            }
            try {
                conn.sendReply(req.get("message_id").asText(), req.get("method").asText(),
                        req.get("equipment_id").asText(), 2, null, Map.of("recovered", true), "2026-11-20T23:03:00Z");
            } catch (Exception ignored) {
            }
        })) {
            AtomicBoolean firstRunFlag = new AtomicBoolean(true);
            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("labsep-drop.lock"),
                    "labsep-drop-host",
                    2, 16, 4, 2, 32, 50,
                    Duration.ofMillis(25), 16,
                    List.of(new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", server.port(), LabSepWireCodec.INSTANCE)),
                    gatewayConfig(4096),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "labsep-drop-cap-" + tenant, 2, "DEVICE", "dev-q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        if (firstRunFlag.compareAndSet(true, false)) {
                            UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"volumeUl\":100}");
                            firstCmdRef.set(cmdId);
                            assertTrue(allowFirstWorkerExit.await(8, TimeUnit.SECONDS));
                            throw new RuntimeException("simulated timeout after LabSep EOF");
                        }
                        return pollingDeviceWorker(ds, deviceId, Duration.ofSeconds(8), null).execute(req, ctx);
                    }))) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                var run1 = seedRun(repo, tenant, "labsep-drop1-" + UUID.randomUUID());
                host.startRun(tenant, run1.runId()).get(5, TimeUnit.SECONDS);

                assertTrue(firstDropped.await(8, TimeUnit.SECONDS), "Command #1 did not reach MockLabSepServer");
                awaitCondition(Duration.ofSeconds(5), () -> {
                    var dev = host.healthSnapshot().devices().get(deviceId);
                    return dev.reconnectCount() >= 1 && dev.status() == TcpDeviceConnection.ConnectionStatus.ONLINE;
                }, "LabSep connection must reconnect automatically after EOF");

                // Safety invariant: NEVER auto-resend command #1 after reconnect!
                assertEquals(1, server.receivedFrames().size(), "Command #1 must NEVER be auto-resent on reconnect");
                assertEquals(1L, host.healthSnapshot().devices().get(deviceId).commandsWritten());

                // Mark command #1 UNKNOWN and release worker -> Run #1 escalates to MANUAL_REQUIRED.
                UUID cmd1 = firstCmdRef.get();
                assertNotNull(cmd1);
                repo.markCommandUnknown(tenant, cmd1, "{\"reason\":\"eof_after_send\"}");
                allowFirstWorkerExit.countDown();

                awaitCondition(Duration.ofSeconds(6), () -> {
                    try {
                        boolean manualRun = host.listManualRequiredRuns(tenant, 10).stream()
                                .anyMatch(r -> r.runId().equals(run1.runId()));
                        long unknownAttempts = scalar(ds,
                                "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='" + tenant
                                        + "' AND run_id='" + run1.runId() + "' AND attempt_state='UNKNOWN' AND error IS NOT NULL");
                        return manualRun && host.healthSnapshot().activeLocalWorkers() == 0 && unknownAttempts == 1L;
                    } catch (Exception e) {
                        return false;
                    }
                }, "Run #1 should escalate to MANUAL_REQUIRED");

                // Reconcile command #1 via T21 operator API.
                assertTrue(host.reconcileUnknownCommand(
                        tenant, cmd1, "SUCCEEDED", "labsep-manual-seq-1",
                        "{\"verifiedBy\":\"operator\"}", "lab-admin"));
                assertEquals("SUCCEEDED", singleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='" + tenant
                                + "' AND command_id='" + cmd1 + "'"));

                // Run #2 over the recovered LabSep TCP connection succeeds cleanly.
                var run2 = seedRun(repo, tenant, "labsep-drop2-" + UUID.randomUUID());
                host.startRun(tenant, run2.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return "SUCCEEDED".equals(singleText(ds,
                                "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id='" + tenant
                                        + "' AND run_id='" + run2.runId() + "'"));
                    } catch (Exception e) {
                        return false;
                    }
                }, "Run #2 should succeed over recovered LabSep connection");
                assertEquals(2, server.receivedFrames().size());
            }
        }
    }

    @Test
    void labSepInvalidMethodStatusAndMessageTypeNeverCauseFalseTerminalTransitionOrGraphAdvance() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "labsep-strict");
        String deviceId = "strict-dev-" + UUID.randomUUID().toString().substring(0, 8);

        String twoStepXml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p_strict">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="step1" protocolName="Dispense" resource="1"/>
                    <bpmn:task id="step2" protocolName="Mix" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="step1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="step1" targetRef="step2"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="step2" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        AtomicBoolean firstCmd = new AtomicBoolean(true);
        AtomicReference<UUID> cmd1Ref = new AtomicReference<>();
        CountDownLatch allowStep1Timeout = new CountDownLatch(1);
        CountDownLatch step2RanOnRun1 = new CountDownLatch(1);

        try (MockLabSepServer server = new MockLabSepServer((req, conn) -> {
            String msgId = req.get("message_id").asText();
            String method = req.get("method").asText();
            String eqId = req.get("equipment_id").asText();
            try {
                // Fire ALL 7 invalid protocol frames + 1 benign message_type=6 spontaneous event for command #1!
                if (firstCmd.compareAndSet(true, false)) {
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":5,\"equipment_id\":\"" + eqId + "\",\"method_status\":0,\"error\":null}");
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":5,\"equipment_id\":\"" + eqId + "\",\"method_status\":4,\"error\":null}");
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":5,\"equipment_id\":\"" + eqId + "\",\"method_status\":-1,\"error\":null}");
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":5,\"equipment_id\":\"" + eqId + "\",\"error\":null}");
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":5,\"equipment_id\":\"" + eqId + "\",\"method_status\":\"2\",\"error\":null}");
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"equipment_id\":\"" + eqId + "\",\"method_status\":2,\"error\":null}");
                    conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":\"5\",\"equipment_id\":\"" + eqId + "\",\"method_status\":2,\"error\":null}");
                    conn.sendRawJson("{\"message_type\":6,\"equipment_id\":\"" + eqId + "\",\"event_type\":\"process_done\",\"data\":{}}");
                    return;
                }
                // For subsequent commands (run #2): also inject an invalid method_status=4 first, then valid 1 -> 2!
                conn.sendRawJson("{\"message_id\":\"" + msgId + "\",\"message_type\":5,\"equipment_id\":\"" + eqId + "\",\"method_status\":4,\"error\":null}");
                conn.sendReply(msgId, method, eqId, 1, null, Map.of("pct", 50), "2026-11-21T07:05:01Z");
                conn.sendReply(msgId, method, eqId, 2, null, Map.of("ok", true), "2026-11-21T07:05:02Z");
            } catch (Exception ignored) {
            }
        })) {
            UUID[] run1IdHolder = new UUID[1];
            var cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("labsep-strict.lock"),
                    "labsep-strict-host",
                    2, 16, 4, 2, 32, 50,
                    Duration.ofMillis(25), 16,
                    List.of(new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", server.port(), LabSepWireCodec.INSTANCE)),
                    gatewayConfig(4096),
                    tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "labsep-strict-cap-" + tenant, 2, "DEVICE", "dev-q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        if (req.runId().equals(run1IdHolder[0])) {
                            if ("step2".equals(req.nodeKey())) {
                                step2RanOnRun1.countDown();
                            }
                            UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"volumeUl\":100}");
                            cmd1Ref.set(cmdId);
                            assertTrue(allowStep1Timeout.await(8, TimeUnit.SECONDS));
                            repo.markCommandUnknown(req.tenantId(), cmdId, "{\"reason\":\"invalid_status_no_valid_reply\"}");
                            throw new RuntimeException("timed out waiting for valid method_status 1/2/3");
                        }
                        return pollingDeviceWorker(ds, deviceId, Duration.ofSeconds(8), null).execute(req, ctx);
                    }))) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                GraphPlan plan1 = new BpmnGraphCompiler().compile(
                        new BpmnGraphCompiler.CompilationInput("strict-1-" + tenant, "1", twoStepXml, Map.of()));
                var run1 = repo.createRun(tenant, "req-strict-1-" + UUID.randomUUID(), "CREATE", "{}",
                        plan1.definitionKey(), "1", HexFormat.of().parseHex(plan1.bpmnSha256()),
                        plan1.compilerVersion(), GraphPlanCodec.toJson(plan1), "{}", "start");
                run1IdHolder[0] = run1.runId();
                host.startRun(tenant, run1.runId()).get(5, TimeUnit.SECONDS);

                // Wait until all 7 malformed/unsupported frames have been rejected by LabSepWireCodec.
                awaitCondition(Duration.ofSeconds(8), () ->
                        host.healthSnapshot().devices().get(deviceId).malformedFrames() == 7L,
                        "All 7 invalid frames (method_status 0/4/-1/missing/string + message_type missing/string) must be rejected");

                // Critical P1 invariants: ZERO inbox writes, command stays SEND_STARTED (NEVER FAILED/SUCCEEDED), step2 NEVER starts!
                UUID cmd1 = cmd1Ref.get();
                assertNotNull(cmd1);
                assertEquals(0L, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run1.runId() + "'"));
                assertEquals("SEND_STARTED", singleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='" + tenant
                                + "' AND command_id='" + cmd1 + "'"));
                assertEquals(0L, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run1.runId() + "' AND node_key='step2'"));
                assertEquals(1L, step2RanOnRun1.getCount(), "step2 must NOT execute on invalid protocol frames");

                // Let run1 enter timeout -> UNKNOWN + MANUAL_REQUIRED (never false FAILED/SUCCEEDED!).
                allowStep1Timeout.countDown();
                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return !host.listUnknownCommands(tenant, 10).isEmpty()
                                && !host.listManualRequiredRuns(tenant, 10).isEmpty()
                                && host.healthSnapshot().activeLocalWorkers() == 0;
                    } catch (Exception e) {
                        return false;
                    }
                }, "Run #1 must escalate to UNKNOWN command + MANUAL_REQUIRED run");
                assertEquals("UNKNOWN", singleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='" + tenant
                                + "' AND command_id='" + cmd1 + "'"));
                assertEquals(0L, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run1.runId() + "' AND node_key='step2'"));

                // Run #2 on the same TCP connection: invalid method_status=4 followed by valid 1 -> 2 converges cleanly!
                GraphPlan plan2 = new BpmnGraphCompiler().compile(
                        new BpmnGraphCompiler.CompilationInput("strict-2-" + tenant, "1", twoStepXml, Map.of()));
                var run2 = repo.createRun(tenant, "req-strict-2-" + UUID.randomUUID(), "CREATE", "{}",
                        plan2.definitionKey(), "1", HexFormat.of().parseHex(plan2.bpmnSha256()),
                        plan2.compilerVersion(), GraphPlanCodec.toJson(plan2), "{}", "start");
                host.startRun(tenant, run2.runId()).get(5, TimeUnit.SECONDS);

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return "SUCCEEDED".equals(singleText(ds,
                                "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id='" + tenant
                                        + "' AND run_id='" + run2.runId() + "'"));
                    } catch (Exception e) {
                        return false;
                    }
                }, "Run #2 should still succeed when valid 1 -> 2 frames follow an invalid method_status=4 frame");
                assertEquals(2L, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run2.runId() + "' AND command_state='SUCCEEDED'"));
            }
        }
    }

    /** Minimal loopback mock server speaking the exact {@code #!LABSEP!#} legacy driver wire protocol. */
    private static final class MockLabSepServer implements AutoCloseable {
        private static final byte[] SEP = LabSepWireCodec.SEPARATOR.getBytes(StandardCharsets.UTF_8);

        private final ServerSocket serverSocket;
        private final BiConsumer<JsonNode, ClientConn> onCommand;
        private final CopyOnWriteArrayList<JsonNode> receivedFrames = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread acceptThread;

        MockLabSepServer(BiConsumer<JsonNode, ClientConn> onCommand) throws IOException {
            this.onCommand = onCommand;
            this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            this.acceptThread = new Thread(this::acceptLoop, "mock-labsep-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        List<JsonNode> receivedFrames() {
            return receivedFrames;
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket s = serverSocket.accept();
                    sockets.add(s);
                    Thread t = new Thread(() -> handleClient(s), "mock-labsep-client-" + s.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (closed.get()) break;
                }
            }
        }

        private void handleClient(Socket s) {
            try (s) {
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                ClientConn conn = new ClientConn(s, s.getOutputStream());
                ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
                byte[] tail = new byte[SEP.length];
                int tailLen = 0;
                int b;
                while (!closed.get() && (b = in.read()) != -1) {
                    buf.write(b);
                    if (tailLen < SEP.length) {
                        tail[tailLen++] = (byte) b;
                    } else {
                        System.arraycopy(tail, 1, tail, 0, SEP.length - 1);
                        tail[SEP.length - 1] = (byte) b;
                    }
                    if (tailLen == SEP.length && Arrays.equals(tail, SEP)) {
                        byte[] raw = buf.toByteArray();
                        String text = new String(raw, 0, raw.length - SEP.length, StandardCharsets.UTF_8);
                        buf.reset();
                        tailLen = 0;
                        JsonNode node = JSON.readTree(text);
                        receivedFrames.add(node);
                        onCommand.accept(node, conn);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { serverSocket.close(); } catch (IOException ignored) {}
            for (Socket s : sockets) {
                try { s.close(); } catch (IOException ignored) {}
            }
        }

        static final class ClientConn {
            private final Socket socket;
            private final OutputStream out;

            ClientConn(Socket socket, OutputStream out) {
                this.socket = socket;
                this.out = out;
            }

            synchronized void sendReply(
                    String messageId, String method, String equipmentId,
                    int methodStatus, Map<String, Object> error,
                    Map<String, Object> data, String timestamp) throws IOException {
                ObjectNode root = JSON.createObjectNode();
                root.put("message_id", messageId);
                root.put("message_type", 5);
                root.put("method", method);
                root.put("equipment_id", equipmentId);
                root.put("equipment_status", 1);
                root.put("method_status", methodStatus);
                root.set("error", error == null ? JSON.nullNode() : JSON.valueToTree(error));
                root.set("data", data == null ? JSON.createObjectNode() : JSON.valueToTree(data));
                root.set("process_info", JSON.createObjectNode());
                root.put("timestamp", timestamp);
                root.put("checksum", "");
                root.put("connected", 1);
                sendRawJson(JSON.writeValueAsString(root));
            }

            synchronized void sendRawJson(String json) throws IOException {
                out.write((json + LabSepWireCodec.SEPARATOR).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            void forceClose() {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
