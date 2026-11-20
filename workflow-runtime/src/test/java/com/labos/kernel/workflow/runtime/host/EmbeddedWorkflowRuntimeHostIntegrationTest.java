package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.gateway.MockTcpDeviceServer;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 end-to-end integration tests for {@link EmbeddedWorkflowRuntimeHost} combining
 * {@link SingleInstanceHostLock}, in-process TCP {@code DeviceGateway}, startup recovery,
 * automatic {@link RuntimeDispatchLoop}, observability snapshots, and T21 manual reconciliation.
 */
class EmbeddedWorkflowRuntimeHostIntegrationTest {

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

    private static UUID createTenant(DriverManagerDataSource ds, String prefix) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','" + prefix + "-" + tenant + "','g3-it')");
        }
        return tenant;
    }

    private static GraphPlan compileSingleDevicePlan(String planKey) {
        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="host_dev_op" protocolName="Dispense" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="host_dev_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="host_dev_op" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
    }

    private static TcpDeviceConnection.Config stableGatewayConfig() {
        // Stable config for end-to-end execution, lock, recovery, and shutdown tests.
        return new TcpDeviceConnection.Config(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                Duration.ofMillis(200),
                4096,
                64);
    }

    private static TcpDeviceConnection.Config disconnectReconnectGatewayConfig() {
        // Dedicated config for explicit EOF disconnect + fast reconnect + T21 observability test.
        return new TcpDeviceConnection.Config(
                Duration.ofSeconds(2),
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                Duration.ofMillis(40),
                Duration.ofMillis(160),
                4096,
                64);
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        awaitCondition(timeout, cond, () -> "Condition not satisfied within " + timeout);
    }

    private static void awaitCondition(
            Duration timeout,
            java.util.function.BooleanSupplier cond,
            java.util.function.Supplier<String> diagnosticOnFailure)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(15);
        }
        assertTrue(cond.getAsBoolean(), () -> "Condition not satisfied within " + timeout + " | " + diagnosticOnFailure.get());
    }

    private static String diagnoseRunState(
            DriverManagerDataSource ds, UUID tenant, UUID runId, UUID commandId,
            EmbeddedWorkflowRuntimeHost host, String deviceId) {
        try {
            var snap = host.healthSnapshot();
            var dev = snap.devices().get(deviceId);
            String runStatus = querySingleText(ds, "SELECT status||':'||control_gate FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runId + "'");
            String attemptStates = querySingleText(ds, "SELECT string_agg(attempt_state||'(err='||(error IS NOT NULL)||')', ',') FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runId + "'");
            String cmdState = commandId == null ? "null" : querySingleText(ds, "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + commandId + "'");
            String outboxStates = querySingleText(ds, "SELECT string_agg(message_kind||'='||outbox_state, ',') FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runId + "'");
            long inboxCount = scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runId + "'");
            return "run=" + runStatus + ", attempts=" + attemptStates + ", cmd=" + cmdState
                    + ", outbox=" + outboxStates + ", inboxCount=" + inboxCount
                    + ", hostState=" + snap.hostState() + ", gateOpen=" + snap.schedulingGateOpen()
                    + ", activeWorkers=" + snap.activeLocalWorkers()
                    + ", dispatchStats=" + snap.dispatchStats()
                    + ", device=" + dev;
        } catch (Exception e) {
            return "diagnostic-error: " + e;
        }
    }

    private static String querySingleText(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? String.valueOf(rs.getString(1)) : "<none>";
        }
    }

    @Test
    void embeddedHostAcquiresSingleInstanceLockRejectsSecondHostAndReleasesCleanlyOnClose() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g3-lock");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Path lockFile = tempDir.resolve("labos-host.lock");
        String deviceId = "g3-lock-dev-1";

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            EmbeddedWorkflowRuntimeHost.Config cfg1 = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-1", 2, 16, 4, 1, 32, 100,
                    Duration.ofMillis(50), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            EmbeddedWorkflowRuntimeHost host1 = new EmbeddedWorkflowRuntimeHost(
                    cfg1, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "cap-" + node.key(), 1, "PURE", "q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.empty());
            try {
                host1.start();
                assertTrue(host1.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                var snap = host1.healthSnapshot();
                assertEquals(RuntimeHost.State.RUNNING, snap.hostState());
                assertTrue(snap.schedulingGateOpen());
                assertTrue(snap.hostLockValid());
                assertEquals(TcpDeviceConnection.ConnectionStatus.ONLINE, snap.devices().get(deviceId).status());

                // Second host pointing at the SAME lock file MUST fail fast without starting!
                EmbeddedWorkflowRuntimeHost.Config cfg2 = new EmbeddedWorkflowRuntimeHost.Config(
                        lockFile, "host-2", 2, 16, 4, 1, 32, 100,
                        Duration.ofMillis(50), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);
                try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                        cfg2, repo,
                        node -> new LocalTaskDispatchService.TaskDispatchSpec(
                                "cap-" + node.key(), 1, "PURE", "q", Duration.ofSeconds(30), "{}"),
                        node -> Optional.empty())) {
                    assertThrows(IllegalStateException.class, host2::start,
                            "Second host instance on the same lock file must be rejected");
                    assertEquals(RuntimeHost.State.NEW, host2.healthSnapshot().hostState());
                    assertFalse(host2.healthSnapshot().hostLockValid());
                }
            } finally {
                host1.close();
            }

            var afterClose = host1.healthSnapshot();
            assertEquals(RuntimeHost.State.STOPPED, afterClose.hostState());
            assertFalse(afterClose.schedulingGateOpen());
            assertFalse(afterClose.hostLockValid());

            // Verify zero lingering runtime/gateway threads for host-1 and device after close().
            awaitCondition(Duration.ofSeconds(2), () -> Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(t -> t.isAlive()
                            && t.getName().startsWith("labos-")
                            && (t.getName().contains("host-1") || t.getName().contains(deviceId))));

            // A third host can cleanly re-acquire the released lock!
            EmbeddedWorkflowRuntimeHost.Config cfg3 = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-3", 1, 8, 2, 1, 16, 100,
                    Duration.ofMillis(50), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);
            try (EmbeddedWorkflowRuntimeHost host3 = new EmbeddedWorkflowRuntimeHost(
                    cfg3, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "cap-" + node.key(), 1, "PURE", "q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.empty())) {
                host3.start();
                assertEquals(RuntimeHost.State.RUNNING, host3.healthSnapshot().hostState());
                assertTrue(host3.healthSnapshot().hostLockValid());
            }
        }
    }

    @Test
    void embeddedHostRecoversInterruptedAttemptBeforeOpeningGateAndCompletesFullTcpDeviceRunAutomatically()
            throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g3-auto");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Path lockFile = tempDir.resolve("labos-auto.lock");
        String deviceId = "g3-auto-dev-1";
        String planKey = "g3-auto-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);

        // Simulate a prior process crash AFTER WORKER_DISPATCH outbox was DELIVERED and BEFORE WorkerPool accepted it!
        var crashedRun = repo.createRun(tenant, "g3-auto-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, crashedRun.runId());
        new GraphCoordinator(repo).advance(tenant, crashedRun.runId());
        var preCrashRes = repo.reserveCapacity(tenant, crashedRun.runId(), "g3-cap-" + tenant, 1,
                "host_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, crashedRun.runId(), preCrashRes.attemptId(), "dev-q", "{}");
        var wdOutbox = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + preCrashRes.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, wdOutbox.outboxId()));

        CountDownLatch terminalApplied = new CountDownLatch(1);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> {
                try {
                    String runId = cmdNode.get("runId").asText();
                    String cmdId = cmdNode.get("commandId").asText();
                    session.sendDeviceEvent(deviceId, runId, cmdId,
                            "g3-ack-" + cmdId, "RECEIVED_ACK", "ACKNOWLEDGED", "seq-1", "{}");
                    session.sendDeviceEvent(deviceId, runId, cmdId,
                            "g3-prog-" + cmdId, "PROGRESS", "RUNNING", "seq-2", "{\"pct\":50}");
                    session.sendDeviceEvent(deviceId, runId, cmdId,
                            "g3-res-" + cmdId, "COMMAND_RESULT", "SUCCEEDED", "seq-3", "{\"dispensed\":80}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-auto", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(40), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g3-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"v\":80}");
                        cmdIdRef.set(cmdId);
                        assertTrue(terminalApplied.await(8, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"dispensed\":80}");
                    }),
                    notification -> {
                        if (notification.error() == null
                                && notification.outcome() != null
                                && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED
                                && "SUCCEEDED".equals(notification.event().targetState())) {
                            terminalApplied.countDown();
                        }
                    })) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                // Verify startup recovery reconciled the crashed DISPATCHED attempt BEFORE opening dispatch!
                var startupSnap = host.healthSnapshot();
                assertNotNull(startupSnap.recoveryResult());
                assertTrue(startupSnap.recoveryResult().safeToRetry() >= 1);
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                        + tenant + "' AND attempt_id='" + preCrashRes.attemptId() + "' AND attempt_state='ABANDONED_SAFE'"));

                // Zero manual dispatch calls! RuntimeDispatchLoop + DeviceGateway + WorkerPool finish the Run automatically!
                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenant + "' AND run_id='" + crashedRun.runId()
                                + "' AND status='SUCCEEDED' AND control_gate='CLOSED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> "terminalApplied=" + (terminalApplied.getCount() == 0) + ", "
                        + diagnoseRunState(ds, tenant, crashedRun.runId(), cmdIdRef.get(), host, deviceId));

                awaitCondition(Duration.ofSeconds(3), () -> server.receivedEventAcks().size() == 3);
                assertNotNull(cmdIdRef.get());
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmdIdRef.get() + "' AND command_state='SUCCEEDED'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenant + "' AND run_id='" + crashedRun.runId() + "' AND outbox_state IN ('PENDING','SENDING')"));

                var endSnap = host.healthSnapshot();
                assertTrue(endSnap.dispatchStats().cyclesCompleted() >= 1);
                assertTrue(endSnap.dispatchStats().tasksDispatched() >= 1);
                assertTrue(endSnap.dispatchStats().workersSubmitted() >= 1);
                assertTrue(endSnap.dispatchStats().commandsDelivered() >= 1);
            }
        }
    }

    @Test
    void embeddedHostExposesUnknownCommandAndManualRequiredObservabilityAndSupportsT21Reconciliation()
            throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g3-obs");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Path lockFile = tempDir.resolve("labos-obs.lock");
        String deviceId = "g3-obs-dev-1";
        String planKey = "g3-obs-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);

        CountDownLatch cmdDroppedOnServer = new CountDownLatch(1);
        CountDownLatch allowWorkerExit = new CountDownLatch(1);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> {
                // Force disconnect right after receiving command without sending result!
                session.forceClose();
                cmdDroppedOnServer.countDown();
            });

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-obs", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(40), 16, List.of(server.endpoint()), disconnectReconnectGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g3-obs-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"v\":15}");
                        cmdIdRef.set(cmdId);
                        assertTrue(allowWorkerExit.await(8, TimeUnit.SECONDS));
                        throw new RuntimeException("simulated command execution timeout after TCP drop");
                    }))) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                awaitCondition(Duration.ofSeconds(3), () -> server.activeSession() != null);

                var run = repo.createRun(tenant, "g3-obs-" + UUID.randomUUID(), "CREATE", "{}",
                        planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                        plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
                host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

                // Automatic dispatch sends command over TCP -> server drops socket -> gateway reconnects without resending!
                assertTrue(cmdDroppedOnServer.await(8, TimeUnit.SECONDS),
                        () -> "Command did not reach MockTcpDeviceServer: "
                                + diagnoseRunState(ds, tenant, run.runId(), cmdIdRef.get(), host, deviceId));
                awaitCondition(Duration.ofSeconds(4), () -> {
                    var h = host.healthSnapshot().devices().get(deviceId);
                    return h.status() == TcpDeviceConnection.ConnectionStatus.ONLINE && h.reconnectCount() >= 1;
                }, () -> "Gateway did not reconnect: " + host.healthSnapshot().devices().get(deviceId));
                assertEquals(1, server.receivedCommands().size(), "Reconnect must never auto-resend command");

                // Mark command UNKNOWN and let worker fail -> Run escalates to MANUAL_REQUIRED.
                UUID cmdId = cmdIdRef.get();
                assertNotNull(cmdId);
                repo.markCommandUnknown(tenant, cmdId, "{\"reason\":\"tcp-disconnect-timeout\"}");
                allowWorkerExit.countDown();

                // Wait deterministically until the Worker has completely exited (activeLocalWorkers == 0),
                // node_attempt has settled to attempt_state='UNKNOWN' with error recorded and OPEN
                // manual_intervention row, AND Run is listed in MANUAL_REQUIRED!
                awaitCondition(Duration.ofSeconds(5), () -> {
                    try {
                        return !host.listManualRequiredRuns(tenant, 10).isEmpty()
                                && host.healthSnapshot().activeLocalWorkers() == 0
                                && scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId()
                                + "' AND attempt_state='UNKNOWN' AND error IS NOT NULL") == 1
                                && scalar(ds, "SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId()
                                + "' AND intervention_state='OPEN'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> diagnoseRunState(ds, tenant, run.runId(), cmdId, host, deviceId));

                // Verify observability lists expose BOTH the UNKNOWN command and MANUAL_REQUIRED run!
                var unknownCommands = host.listUnknownCommands(tenant, 10);
                assertEquals(1, unknownCommands.size());
                assertEquals(cmdId, unknownCommands.get(0).commandId());
                assertEquals(deviceId, unknownCommands.get(0).deviceId());
                assertEquals("UNKNOWN", unknownCommands.get(0).commandState());

                var manualRuns = host.listManualRequiredRuns(tenant, 10);
                assertEquals(1, manualRuns.size());
                assertEquals(run.runId(), manualRuns.get(0).runId());
                assertEquals("MANUAL_REQUIRED", manualRuns.get(0).status());

                // Operator reconciles via T21 API on EmbeddedWorkflowRuntimeHost -> UNKNOWN list clears!
                assertTrue(host.reconcileUnknownCommand(tenant, cmdId, "FAILED", "seq-op-1",
                        "{\"verifiedBy\":\"operator\"}", "operator-1"));
                assertTrue(host.listUnknownCommands(tenant, 10).isEmpty());
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmdId + "' AND command_state='FAILED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_audit WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND action='UNKNOWN_COMMAND_RESOLVED'"));
            }
        }
    }

    @Test
    void deviceEventsArrivingDuringStartupRecoveryDoNotMutateStateBeforeRecoveryCompletes() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g3-rec-race");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Path lockFile = tempDir.resolve("labos-rec-race.lock");
        String deviceId = "g3-rec-race-dev-1";
        String planKey = "g3-rec-race-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);

        // Pre-crash durable state: STARTED Attempt + SEND_STARTED Command (host crashed mid-flight).
        var crashedRun = repo.createRun(tenant, "g3-rec-race-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, crashedRun.runId());
        new GraphCoordinator(repo).advance(tenant, crashedRun.runId());
        var res = repo.reserveCapacity(tenant, crashedRun.runId(), "g3-rr-cap-" + tenant, 1,
                "host_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, crashedRun.runId(), res.attemptId(), "dev-q", "{}");
        var wd = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, wd.outboxId()));
        repo.acceptAttempt(tenant, crashedRun.runId(), res.attemptId(), res.dispatchId(), "w-crash");
        repo.startAttempt(tenant, crashedRun.runId(), res.attemptId(), res.dispatchId(), "w-crash");
        UUID cmdId = repo.createCommandForAttempt(tenant, crashedRun.runId(), res.attemptId(),
                "w-crash", deviceId, "Dispense", "QUERYABLE", "{\"v\":50}");
        var cmdOutbox = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 10).stream()
                .filter(m -> m.dedupKey().equals("command:" + cmdId)).findFirst().orElseThrow();
        assertTrue(repo.markCommandSendStarted(tenant, cmdId, 0L, cmdOutbox.outboxId(),
                UUID.randomUUID(), 1, "disp-crash"));

        CountDownLatch enteredRecovery = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        var starterExec = java.util.concurrent.Executors.newSingleThreadExecutor();

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-rec-race", 2, 16, 4, 1, 32, 100,
                    Duration.ofMillis(50), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g3-rr-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.empty(),
                    n -> {},
                    delegate -> (limit, actor) -> {
                        enteredRecovery.countDown();
                        try {
                            if (!releaseRecovery.await(8, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting to release recovery");
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(ie);
                        }
                        return delegate.recover(limit, actor);
                    })) {

                var startFuture = starterExec.submit(() -> {
                    host.start();
                    return null;
                });

                // Wait until Host is actively in RECOVERING AND TCP ingress is ONLINE!
                assertTrue(enteredRecovery.await(5, TimeUnit.SECONDS));
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                awaitCondition(Duration.ofSeconds(3), () -> server.activeSession() != null);
                assertEquals(RuntimeHost.State.RECOVERING, host.runtimeHost().state());
                assertFalse(host.healthSnapshot().schedulingGateOpen());

                // Fire a real TCP COMMAND_RESULT(SUCCEEDED) for cmdId WHILE startup recovery is still running!
                server.activeSession().sendDeviceEvent(
                        deviceId, crashedRun.runId().toString(), cmdId.toString(),
                        "mid-recovery-msg-" + cmdId, "COMMAND_RESULT", "SUCCEEDED", "seq-mid", "{\"ok\":true}");

                // Hold recovery briefly to prove event processing does NOT run concurrently with recovery!
                Thread.sleep(200);
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmdId + "' AND command_state='SEND_STARTED'"),
                        "Device event arriving during RECOVERING must NOT mutate command_ledger before recovery completes");
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                        + tenant + "' AND run_id='" + crashedRun.runId() + "'"),
                        "Device event arriving during RECOVERING must NOT be processed into Inbox before recovery finishes");
                assertEquals(0, server.receivedEventAcks().size());
                assertEquals(0, server.receivedEventNacks().size());

                // Now allow recovery to finish -> recovery escalates cmdId to UNKNOWN + Run MANUAL_REQUIRED!
                releaseRecovery.countDown();
                startFuture.get(8, TimeUnit.SECONDS);
                assertEquals(RuntimeHost.State.RUNNING, host.runtimeHost().state());

                // Post-recovery event processor now drains the buffered mid-recovery event against UNKNOWN -> permanent NACK!
                awaitCondition(Duration.ofSeconds(3), () -> server.receivedEventNacks().stream()
                        .anyMatch(n -> !n.path("retryable").asBoolean(true)
                                && ("mid-recovery-msg-" + cmdId).equals(n.path("sourceMessageId").asText())));
                assertEquals(0, server.receivedEventAcks().size(),
                        "Late SUCCEEDED event after recovery escalated UNKNOWN must NOT be ACKed");
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmdId + "' AND command_state='UNKNOWN'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                        + tenant + "' AND run_id='" + crashedRun.runId() + "' AND status='MANUAL_REQUIRED'"));
            } finally {
                // Dead-letter this test's uncertain SENDING outbox so shared-DB tests in other classes stay isolated.
                repo.markOutboxDeadLetter(tenant, cmdOutbox.outboxId());
            }
        } finally {
            starterExec.shutdownNow();
        }
    }

    @Test
    void shutdownWithBufferedUnprocessedEventEmitsNoAckAndReconnectRetryConvergesOnce() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g3-stop-retry");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Path lockFile = tempDir.resolve("labos-stop-retry.lock");
        String deviceId = "g3-stop-retry-dev-1";
        String planKey = "g3-stop-retry-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);

        // Prepare an active STARTED Attempt + SEND_STARTED Command (outbox already DELIVERED).
        var run = repo.createRun(tenant, "g3-sr-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "g3-sr-cap-" + tenant, 1,
                "host_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "dev-q", "{}");
        var wd = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, wd.outboxId()));
        repo.acceptAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-sr");
        repo.startAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-sr");
        UUID cmdId = repo.createCommandForAttempt(tenant, run.runId(), res.attemptId(),
                "w-sr", deviceId, "Dispense", "QUERYABLE", "{\"v\":55}");
        var cmdOutbox = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 10).stream()
                .filter(m -> m.dedupKey().equals("command:" + cmdId)).findFirst().orElseThrow();
        assertTrue(repo.markCommandSendStarted(tenant, cmdId, 0L, cmdOutbox.outboxId(),
                UUID.randomUUID(), 1, "disp-sr"));
        assertTrue(repo.markOutboxDelivered(tenant, cmdOutbox.outboxId()));

        String firstMsgId = "sr-ack-" + cmdId;
        String retryMsgId = "sr-res-" + cmdId;
        CountDownLatch enteredFirstEventListener = new CountDownLatch(1);
        CountDownLatch releaseFirstEventListener = new CountDownLatch(1);

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            EmbeddedWorkflowRuntimeHost.Config cfg1 = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-sr-1", 1, 8, 2, 1, 16, 100,
                    Duration.ofMillis(200), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            // Host 1 starts with clean recovery no-op so our prepared SEND_STARTED command stays ready for live event tests.
            try (EmbeddedWorkflowRuntimeHost host1 = new EmbeddedWorkflowRuntimeHost(
                    cfg1, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g3-sr-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.empty(),
                    notification -> {
                        if (firstMsgId.equals(notification.event().sourceMessageId())) {
                            enteredFirstEventListener.countDown();
                            try {
                                releaseFirstEventListener.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    },
                    delegate -> (limit, actor) -> new com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService.StartupResult(
                            new WorkflowRuntimeRepository.OutboxRestartResult(0, 0), 0, 0, 0, 0, List.of()))) {

                host1.start();
                assertTrue(host1.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                awaitCondition(Duration.ofSeconds(3), () -> server.activeSession() != null);
                var session1 = server.activeSession();

                // 1. Send Event #1 (RECEIVED_ACK) which blocks eventLoop inside listener; then send Event #2 (COMMAND_RESULT)
                //    so Event #2 sits buffered and UNPROCESSED in Host 1's bounded eventQueue!
                session1.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                        firstMsgId, "RECEIVED_ACK", "ACKNOWLEDGED", "seq-1", "{}");
                assertTrue(enteredFirstEventListener.await(5, TimeUnit.SECONDS));

                session1.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                        retryMsgId, "COMMAND_RESULT", "SUCCEEDED", "seq-2", "{\"dispensed\":55}");
                // Deterministically confirm Event #2 has been read from TCP and enqueued into the bounded queue!
                awaitCondition(Duration.ofSeconds(3),
                        () -> host1.deviceGateway().queuedEventCount(deviceId) == 1);

                // 2. Stop Host 1 WHILE Event #1 is still blocked so stopEventProcessing() sets processingStopped=true
                //    BEFORE interrupting Event #1 — guaranteeing Event #2 is NEVER polled or ACKed before shutdown!
                host1.close();
                releaseFirstEventListener.countDown();
            }

            // 3. Verify device NEVER received EVENT_ACK for Event #2 and DB has ZERO Inbox row for Event #2!
            assertTrue(server.receivedEventAcks().stream()
                            .noneMatch(a -> retryMsgId.equals(a.path("sourceMessageId").asText())),
                    "Unprocessed buffered event discarded on Host shutdown must NEVER receive EVENT_ACK");
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                    + tenant + "' AND source_message_id='" + retryMsgId + "'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmdId + "' AND command_state='ACKNOWLEDGED'"));

            // 4. Reconnect with Host 2 -> device retransmits the SAME un-ACKed retryMsgId (twice to verify Inbox dedup)!
            EmbeddedWorkflowRuntimeHost.Config cfg2 = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-sr-2", 1, 8, 2, 1, 16, 100,
                    Duration.ofMillis(200), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                    cfg2, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g3-sr-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.empty(),
                    n -> {},
                    delegate -> (limit, actor) -> new com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService.StartupResult(
                            new WorkflowRuntimeRepository.OutboxRestartResult(0, 0), 0, 0, 0, 0, List.of()))) {

                host2.start();
                assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                awaitCondition(Duration.ofSeconds(3), () -> server.activeSession() != null);
                var session2 = server.activeSession();

                // Device retransmits un-ACKed Event #2 with SAME sourceMessageId -> APPLIED + EVENT_ACK(APPLIED)!
                session2.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                        retryMsgId, "COMMAND_RESULT", "SUCCEEDED", "seq-2", "{\"dispensed\":55}");
                awaitCondition(Duration.ofSeconds(3), () -> server.receivedEventAcks().stream()
                        .anyMatch(a -> retryMsgId.equals(a.path("sourceMessageId").asText())
                                && "APPLIED".equals(a.path("status").asText())));

                // Duplicate retransmission of SAME sourceMessageId -> idempotent EVENT_ACK(DUPLICATE_REPLAY)!
                session2.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                        retryMsgId, "COMMAND_RESULT", "SUCCEEDED", "seq-2", "{\"dispensed\":55}");
                awaitCondition(Duration.ofSeconds(3), () -> server.receivedEventAcks().stream()
                        .anyMatch(a -> retryMsgId.equals(a.path("sourceMessageId").asText())
                                && "DUPLICATE_REPLAY".equals(a.path("status").asText())));

                // Verify exactly ONE durable Inbox row and ONE SIDE_EFFECT_COMPLETED fact (idempotent convergence!).
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                        + tenant + "' AND source_message_id='" + retryMsgId + "' AND receipt_state='PROCESSED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmdId + "' AND command_state='SUCCEEDED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                        + tenant + "' AND attempt_id='" + res.attemptId() + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));
            }

            // Cleanly complete the test's Attempt so shared-DB tests stay isolated.
            repo.completeAttempt(tenant, run.runId(), res.attemptId(), "out", "{\"dispensed\":55}");
        }
    }

    /**
     * 回归护栏：租户级 dispatch 发现必须在 SQL 层收窄，不能被其它租户的 RUNNING/OPEN 行挤掉。
     *
     * <p>事故背景（2026-11-20 远端 PG17 红灯）：原先 {@code findRunnableWorkTargets} 用
     * {@code DISTINCT + 无序 LIMIT} 取候选行，而租户过滤是**在 SQL 之后**用内存谓词做的
     * ⇒ 多租户共享库里目标租户的 run 会被 LIMIT 截断，且同一份数据下顺序稳定
     * ⇒ 目标 run 被**永久饥饿**（不是变慢）⇒ 两个端到端用例 8s 超时。
     *
     * <p>本用例造 48 个竞争租户（{@code batchSize=32} 足以挤满限额），并把它们的
     * {@code updated_at} 调旧，使得"先取最旧的 32 条"这种有序实现同样会排除目标 run，
     * 然后验证两件事：
     * <ol>
     *   <li><b>SQL 契约（确定性）</b>：{@code findRunnableWorkTargetsForTenant} 只返回本租户的行；</li>
     *   <li><b>端到端</b>：限定租户的 Host 仍能把本租户的 run 自动跑到 {@code SUCCEEDED}。</li>
     * </ol>
     *
     * <p>护栏有效性：把 dispatch 发现改回"无租户条件 + 内存过滤"的旧语义时，
     * 竞争租户会持续占满 LIMIT，本用例的端到端断言必然超时失败。
     */
    @Test
    void scopedDispatchIsNotStarvedByCompetingTenants() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g3-starve");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Path lockFile = tempDir.resolve("labos-starve.lock");
        String deviceId = "g3-starve-dev-1";
        String planKey = "g3-starve-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);

        // ① 造 48 个竞争租户：RUNNING/OPEN 且带 READY token + PENDING WORKER_DISPATCH/DEVICE_COMMAND Outbox
        int competitors = 48;
        List<UUID> competitorTenants = new ArrayList<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (int i = 0; i < competitors; i++) {
                UUID other = createTenant(ds, "g3-starve-c" + i);
                competitorTenants.add(other);
                var otherRun = repo.createRun(other, "g3-starve-req-" + i, "CREATE", "{}",
                        planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                        plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
                repo.startRun(other, otherRun.runId());
                // ② 把竞争 run 的 updated_at 与 outbox 的 available_at 调旧：即便实现按"最旧优先"排序，LIMIT 也会先取走它们
                st.executeUpdate("UPDATE workflow_runtime.workflow_run SET updated_at = clock_timestamp() - interval '1 hour' "
                        + "WHERE tenant_id = '" + other + "'");
                st.executeUpdate("INSERT INTO workflow_runtime.runtime_outbox"
                        + "(tenant_id,run_id,message_kind,dedup_key,payload,outbox_state,available_at) VALUES ('"
                        + other + "','" + otherRun.runId() + "','WORKER_DISPATCH','starve-wd-" + other
                        + "','{}'::jsonb,'PENDING',clock_timestamp() - interval '1 hour'),('"
                        + other + "','" + otherRun.runId() + "','DEVICE_COMMAND','starve-cmd-" + other
                        + "','{}'::jsonb,'PENDING',clock_timestamp() - interval '1 hour')");
            }
        }

        // ③ 本租户的 run（与端到端用例同构：startRun + advance ⇒ 设备任务节点持 READY token）
        var run = repo.createRun(tenant, "g3-starve-req-self", "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());

        // ④ 前提：竞争租户必须同时挤满无租户条件的 Run 与 Outbox LIMIT，否则本护栏证明不了饥饿
        var unscoped = repo.findRunnableWorkTargets(32);
        assertEquals(32, unscoped.size(), "前提失效：竞争租户未挤满 LIMIT(32)，本护栏将失去意义");
        assertTrue(unscoped.stream().noneMatch(r -> r.runId().equals(run.runId())),
                "前提失效：目标 run 未被无租户条件的发现排除，饥饿场景没有复现");
        var unscopedWdOutbox = repo.findTenantsWithPendingOutbox("WORKER_DISPATCH", 32);
        assertEquals(32, unscopedWdOutbox.size());
        assertFalse(unscopedWdOutbox.contains(tenant));
        var unscopedCmdOutbox = repo.findTenantsWithPendingOutbox("DEVICE_COMMAND", 32);
        assertEquals(32, unscopedCmdOutbox.size());
        assertFalse(unscopedCmdOutbox.contains(tenant));

        // ⑤ SQL 契约：租户级发现只返回本租户的行，不受竞争租户行数影响
        var scoped = repo.findRunnableWorkTargetsForTenant(tenant, 32);
        assertEquals(1, scoped.size());
        assertEquals(run.runId(), scoped.get(0).runId());

        // ⑥ 端到端：限定租户的 Host 仍能自动把本租户的 run 跑到 SUCCEEDED
        CountDownLatch terminalApplied = new CountDownLatch(1);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();
        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> {
                try {
                    String runId = cmdNode.get("runId").asText();
                    String cmdId = cmdNode.get("commandId").asText();
                    session.sendDeviceEvent(deviceId, runId, cmdId,
                            "g3-starve-ack-" + cmdId, "RECEIVED_ACK", "ACKNOWLEDGED", "seq-1", "{}");
                    session.sendDeviceEvent(deviceId, runId, cmdId,
                            "g3-starve-res-" + cmdId, "COMMAND_RESULT", "SUCCEEDED", "seq-2", "{\"dispensed\":80}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-starve", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(40), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g3-starve-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"v\":80}");
                        cmdIdRef.set(cmdId);
                        assertTrue(terminalApplied.await(8, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"dispensed\":80}");
                    }),
                    notification -> {
                        if (notification.error() == null
                                && notification.outcome() != null
                                && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED
                                && "SUCCEEDED".equals(notification.event().targetState())) {
                            terminalApplied.countDown();
                        }
                    })) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                awaitCondition(Duration.ofSeconds(20), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId()
                                + "' AND status='SUCCEEDED' AND control_gate='CLOSED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> "scoped dispatch starved: "
                        + diagnoseRunState(ds, tenant, run.runId(), cmdIdRef.get(), host, deviceId));
            }
        }
    }
}
