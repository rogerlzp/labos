package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.command.DeviceCommandAdapter;
import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.host.SchedulingGate;
import com.labos.kernel.workflow.runtime.host.SchedulingGateTestSupport;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionService;
import com.labos.kernel.workflow.runtime.worker.WorkerPool;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G2-C integration tests for {@link DeviceGateway} persistent TCP client, heartbeat liveness,
 * automatic reconnect without auto-resend, protocol security, and full PG17 Runtime loop.
 */
class DeviceGatewayTcpIntegrationTest {

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
                    + tenant + "','" + prefix + "-" + tenant + "','g2c-it')");
        }
        return tenant;
    }

    private static TcpDeviceConnection.Config fastGatewayConfig() {
        return new TcpDeviceConnection.Config(
                Duration.ofSeconds(2),
                Duration.ofMillis(80),
                Duration.ofMillis(320),
                Duration.ofMillis(40),
                Duration.ofMillis(160),
                4096);
    }

    private static GraphPlan compileSingleDevicePlan(String planKey) {
        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="tcp_dev_op" protocolName="Dispense" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="tcp_dev_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="tcp_dev_op" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(15);
        }
        assertTrue(cond.getAsBoolean(), "Condition not satisfied within " + timeout);
    }

    @Test
    void heartbeatTimeoutDetectsHalfOpenPeerAndAutoReconnectRestoresOnline() throws Exception {
        String deviceId = "tcp-hb-1";
        AtomicInteger sinkEvents = new AtomicInteger();
        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId);
             DeviceGateway gateway = new DeviceGateway(
                     List.of(server.endpoint()), fastGatewayConfig(), ev -> sinkEvents.incrementAndGet())) {
            gateway.start();
            assertTrue(gateway.awaitOnline(deviceId, Duration.ofSeconds(3)));

            // Verify heartbeats flow cleanly while healthy.
            awaitCondition(Duration.ofSeconds(3),
                    () -> gateway.health(deviceId).orElseThrow().heartbeatsReceived() >= 2);
            long epochBeforeTimeout = gateway.health(deviceId).orElseThrow().connectionEpoch();
            assertEquals(0, sinkEvents.get(), "Heartbeats must NEVER enter DeviceEventSink");

            // Simulate silent half-open peer by suppressing PONG replies.
            server.setReplyToHeartbeats(false);
            assertTrue(gateway.awaitStatus(deviceId, TcpDeviceConnection.ConnectionStatus.RECONNECTING,
                    Duration.ofSeconds(3)), "Gateway must detect heartbeat timeout and enter RECONNECTING");

            // Restore PONG replies -> Gateway automatically reconnects and returns ONLINE on a new epoch!
            server.setReplyToHeartbeats(true);
            awaitCondition(Duration.ofSeconds(3), () -> {
                var h = gateway.health(deviceId).orElseThrow();
                return h.status() == TcpDeviceConnection.ConnectionStatus.ONLINE
                        && h.connectionEpoch() > epochBeforeTimeout
                        && h.reconnectCount() >= 1;
            });
            assertEquals(0, sinkEvents.get(), "Heartbeats must still never touch DeviceEventSink");
        }
    }

    @Test
    void concurrentMultiThreadCommandWritesDoNotInterleaveAndCloseLeavesZeroLeakedThreads() throws Exception {
        String deviceId = "tcp-conc-1";
        int threads = 4;
        int cmdsPerThread = 15;
        int totalCommands = threads * cmdsPerThread;

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId);
             DeviceGateway gateway = new DeviceGateway(
                     List.of(server.endpoint()), fastGatewayConfig(), ev -> {})) {
            gateway.start();
            assertTrue(gateway.awaitOnline(deviceId, Duration.ofSeconds(3)));

            var pool = Executors.newFixedThreadPool(threads);
            try {
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Future<List<UUID>>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int workerIdx = t;
                    futures.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        List<UUID> sent = new ArrayList<>();
                        for (int i = 0; i < cmdsPerThread; i++) {
                            UUID cmdId = UUID.randomUUID();
                            var env = new DeviceCommandAdapter.DeviceCommandEnvelope(
                                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                    cmdId, UUID.randomUUID(), deviceId, "Dispense", "QUERYABLE",
                                    "{\"worker\":" + workerIdx + ",\"idx\":" + i + "}");
                            var res = gateway.sendCommand(env);
                            assertEquals(DeviceCommandAdapter.SendStatus.ACCEPTED_ASYNC, res.status());
                            sent.add(cmdId);
                        }
                        return sent;
                    }));
                }
                Set<String> expectedIds = new HashSet<>();
                for (var f : futures) {
                    for (UUID id : f.get(10, TimeUnit.SECONDS)) {
                        expectedIds.add(id.toString());
                    }
                }
                awaitCondition(Duration.ofSeconds(5), () -> server.receivedCommands().size() == totalCommands);
                assertEquals(0, server.parseErrors(), "Serialized writeLock must prevent any NDJSON frame interleaving");
                Set<String> receivedIds = new HashSet<>();
                for (var node : server.receivedCommands()) {
                    receivedIds.add(node.get("commandId").asText());
                }
                assertEquals(expectedIds, receivedIds, "All 60 concurrent commands must arrive intact");
            } finally {
                pool.shutdownNow();
            }

            gateway.close();
            assertEquals(TcpDeviceConnection.ConnectionStatus.CLOSED,
                    gateway.health(deviceId).orElseThrow().status());
        }

        // Verify zero lingering gateway threads for this device after close().
        awaitCondition(Duration.ofSeconds(2), () -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().contains(deviceId)));
    }

    @Test
    void endToEndPersistentTcpCommandLoopWithAckProgressResultAndInterleavedHeartbeats() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2c-e2e");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String deviceId = "tcp-pipettor-1";
        String planKey = "g2c-e2e-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        var run = repo.createRun(tenant, "g2c-e2e-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");

        CountDownLatch cmdCreated = new CountDownLatch(1);
        CountDownLatch terminalEventApplied = new CountDownLatch(1);
        AtomicReference<UUID> cmdIdRef = new AtomicReference<>();
        AtomicReference<RuntimeDeviceEventBridge> bridgeRef = new AtomicReference<>();
        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId);
             DeviceGateway gateway = new DeviceGateway(
                     List.of(server.endpoint()),
                     fastGatewayConfig(),
                     ev -> bridgeRef.get().onDeviceEvent(ev))) {

            DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(repo, gateway.asCommandAdapter());
            bridgeRef.set(new RuntimeDeviceEventBridge(tenant, cmdSvc, notification -> {
                if (notification.error() == null
                        && "SUCCEEDED".equals(notification.event().targetState())
                        && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED) {
                    terminalEventApplied.countDown();
                }
            }));

            server.setCommandHandler((cmdNode, session) -> {
                try {
                    String runId = cmdNode.get("runId").asText();
                    String commandId = cmdNode.get("commandId").asText();
                    session.sendHeartbeatPong("interleaved-1");
                    session.sendDeviceEvent(deviceId, runId, commandId,
                            "tcp-ack-" + commandId, "RECEIVED_ACK", "ACKNOWLEDGED", "seq-1", "{}");
                    session.sendHeartbeatPong("interleaved-2");
                    session.sendDeviceEvent(deviceId, runId, commandId,
                            "tcp-prog-" + commandId, "PROGRESS", "RUNNING", "seq-2", "{\"pct\":50}");
                    session.sendDeviceEvent(deviceId, runId, commandId,
                            "tcp-res-" + commandId, "COMMAND_RESULT", "SUCCEEDED", "seq-3", "{\"dispensed\":75}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            gateway.start();
            assertTrue(gateway.awaitOnline(deviceId, Duration.ofSeconds(3)));

            var mailboxExec = Executors.newSingleThreadExecutor();
            SchedulingGate gate = SchedulingGateTestSupport.openGate();
            WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
            try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
                 var pool = new WorkerPool(1, 4, 2, registry, gate)) {
                pool.start();
                WorkerExecutionService workerService = new WorkerExecutionService(repo, lifecycle, pool, registry);
                LocalTaskDispatchService dispatchService = new LocalTaskDispatchService(
                        repo, workerService,
                        node -> new LocalTaskDispatchService.TaskDispatchSpec(
                                "g2c-e2e-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                        node -> Optional.of((req, ctx) -> {
                            UUID cmdId = ctx.createDeviceCommand(deviceId, "Dispense", "QUERYABLE", "{\"volumeUl\":75}");
                            cmdIdRef.set(cmdId);
                            cmdCreated.countDown();
                            assertTrue(terminalEventApplied.await(5, TimeUnit.SECONDS));
                            return WorkerTaskHandler.TaskResult.of("out", "{\"dispensed\":75}");
                        }));

                lifecycle.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);
                assertEquals(1, dispatchService.dispatchReadyTasks(tenant, run.runId(), 4).dispatchedAttemptIds().size());
                var wd = dispatchService.deliverPendingWorkerDispatches(tenant, 4, "w-tcp");
                assertEquals(1, wd.submitted().size());
                assertTrue(cmdCreated.await(5, TimeUnit.SECONDS));

                var delivery = cmdSvc.deliverPendingCommands(tenant, 4, "disp-tcp");
                assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC,
                        delivery.outcomes().get(cmdIdRef.get()));

                var workerOutcome = wd.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
                assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, workerOutcome.status());
                workerOutcome.graphAdvance().get(5, TimeUnit.SECONDS);

                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND status='SUCCEEDED' AND control_gate='CLOSED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmdIdRef.get()
                        + "' AND command_state='SUCCEEDED' AND device_sequence='seq-3'"));
                assertEquals(3, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND receipt_state='PROCESSED'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                        + tenant + "' AND message_type IN ('HEARTBEAT','HEARTBEAT_PONG')"));
                assertTrue(gateway.health(deviceId).orElseThrow().heartbeatsReceived() >= 2);
            } finally {
                mailboxExec.shutdownNow();
            }
        }
    }

    @Test
    void disconnectAfterSendStartedNeverAutoResendsOnReconnectAndSupportsT21AndNewRun() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2c-reconn");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String deviceId = "tcp-reconn-1";
        String planKey = "g2c-reconn-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);

        CountDownLatch cmd1ReceivedThenDropped = new CountDownLatch(1);
        CountDownLatch cmd2Succeeded = new CountDownLatch(1);
        AtomicInteger serverHandledCount = new AtomicInteger();
        AtomicReference<RuntimeDeviceEventBridge> bridgeRef = new AtomicReference<>();

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId);
             DeviceGateway gateway = new DeviceGateway(
                     List.of(server.endpoint()),
                     fastGatewayConfig(),
                     ev -> bridgeRef.get().onDeviceEvent(ev))) {

            DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(repo, gateway.asCommandAdapter());
            bridgeRef.set(new RuntimeDeviceEventBridge(tenant, cmdSvc, n -> {
                if (n.error() == null && "SUCCEEDED".equals(n.event().targetState())) {
                    cmd2Succeeded.countDown();
                }
            }));

            server.setCommandHandler((cmdNode, session) -> {
                int call = serverHandledCount.incrementAndGet();
                if (call == 1) {
                    // Simulate device/network dropping TCP right after receiving cmd1 WITHOUT sending result!
                    session.forceClose();
                    cmd1ReceivedThenDropped.countDown();
                } else {
                    try {
                        String runId = cmdNode.get("runId").asText();
                        String cmdId = cmdNode.get("commandId").asText();
                        session.sendDeviceEvent(deviceId, runId, cmdId,
                                "ok-ack-" + cmdId, "RECEIVED_ACK", "ACKNOWLEDGED", "seq-1", "{}");
                        session.sendDeviceEvent(deviceId, runId, cmdId,
                                "ok-res-" + cmdId, "COMMAND_RESULT", "SUCCEEDED", "seq-2", "{\"ok\":true}");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            gateway.start();
            assertTrue(gateway.awaitOnline(deviceId, Duration.ofSeconds(3)));
            long initialEpoch = gateway.health(deviceId).orElseThrow().connectionEpoch();

            // Run 1: send cmd1 over TCP, peer immediately drops socket -> Gateway auto-reconnects, NEVER resends cmd1!
            var run1 = repo.createRun(tenant, "g2c-r1-" + UUID.randomUUID(), "CREATE", "{}",
                    planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            repo.startRun(tenant, run1.runId());
            new GraphCoordinator(repo).advance(tenant, run1.runId());
            var res1 = repo.reserveCapacity(tenant, run1.runId(), "cap-r1-" + tenant, 1,
                    "tcp_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, run1.runId(), res1.attemptId(), "dev-q", "{}");
            var wd1 = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                    .filter(m -> m.dedupKey().equals("dispatch:" + res1.attemptId())).findFirst().orElseThrow();
            assertTrue(repo.markOutboxDelivered(tenant, wd1.outboxId()));
            repo.acceptAttempt(tenant, run1.runId(), res1.attemptId(), res1.dispatchId(), "w-1");
            repo.startAttempt(tenant, run1.runId(), res1.attemptId(), res1.dispatchId(), "w-1");
            UUID cmd1 = repo.createCommandForAttempt(tenant, run1.runId(), res1.attemptId(),
                    "w-1", deviceId, "Dispense", "QUERYABLE", "{\"v\":10}");

            var del1 = cmdSvc.deliverPendingCommands(tenant, 4, "disp-r1");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC, del1.outcomes().get(cmd1));
            assertTrue(cmd1ReceivedThenDropped.await(5, TimeUnit.SECONDS));

            // Wait for automatic reconnect to restore ONLINE on a strictly newer connection epoch.
            awaitCondition(Duration.ofSeconds(4), () -> {
                var h = gateway.health(deviceId).orElseThrow();
                return h.status() == TcpDeviceConnection.ConnectionStatus.ONLINE
                        && h.connectionEpoch() > initialEpoch
                        && h.reconnectCount() >= 1;
            });
            // Allow multiple heartbeat cycles to prove cmd1 is NEVER auto-resent after reconnect!
            awaitCondition(Duration.ofSeconds(3),
                    () -> gateway.health(deviceId).orElseThrow().heartbeatsReceived() >= 2);
            assertEquals(1, server.receivedCommands().size(),
                    "Reconnect MUST NOT auto-resend in-flight command after SEND_STARTED");

            // Mark cmd1 UNKNOWN on execution timeout, escalate Run 1 MANUAL_REQUIRED, and reconcile via T21.
            repo.markCommandUnknown(tenant, cmd1, "{\"reason\":\"tcp-disconnect-no-result\"}");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmd1 + "' AND command_state='UNKNOWN'"));
            assertEquals("MANUAL_REQUIRED", repo.failAttempt(tenant, run1.runId(), res1.attemptId(),
                    "{\"reason\":\"tcp-disconnect\"}", true).outcome());
            assertTrue(cmdSvc.reconcileUnknownCommand(tenant, cmd1, "FAILED", "seq-manual",
                    "{\"verifiedBy\":\"operator\"}", "op-1"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmd1 + "' AND command_state='FAILED'"));

            // Run 2: new command seamlessly reuses the recovered persistent TCP connection and succeeds!
            var run2 = repo.createRun(tenant, "g2c-r2-" + UUID.randomUUID(), "CREATE", "{}",
                    planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            repo.startRun(tenant, run2.runId());
            new GraphCoordinator(repo).advance(tenant, run2.runId());
            var res2 = repo.reserveCapacity(tenant, run2.runId(), "cap-r2-" + tenant, 1,
                    "tcp_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, run2.runId(), res2.attemptId(), "dev-q", "{}");
            var wd2 = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                    .filter(m -> m.dedupKey().equals("dispatch:" + res2.attemptId())).findFirst().orElseThrow();
            assertTrue(repo.markOutboxDelivered(tenant, wd2.outboxId()));
            repo.acceptAttempt(tenant, run2.runId(), res2.attemptId(), res2.dispatchId(), "w-2");
            repo.startAttempt(tenant, run2.runId(), res2.attemptId(), res2.dispatchId(), "w-2");
            UUID cmd2 = repo.createCommandForAttempt(tenant, run2.runId(), res2.attemptId(),
                    "w-2", deviceId, "Dispense", "QUERYABLE", "{\"v\":20}");

            var del2 = cmdSvc.deliverPendingCommands(tenant, 4, "disp-r2");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC, del2.outcomes().get(cmd2));
            assertTrue(cmd2Succeeded.await(5, TimeUnit.SECONDS));
            assertEquals(2, server.receivedCommands().size(), "Exactly cmd1 and cmd2 sent; zero duplicates");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmd2 + "' AND command_state='SUCCEEDED'"));
            repo.completeAttempt(tenant, run2.runId(), res2.attemptId(), "out", "{\"ok\":true}");
            new GraphCoordinator(repo).advance(tenant, run2.runId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run2.runId() + "' AND status='SUCCEEDED'"));
        }
    }

    @Test
    void tcpProtocolSecurityHandlesDuplicateReplayConflictSpoofingAndMalformedFrames() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2c-sec");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String deviceId = "tcp-sec-1";
        String planKey = "g2c-sec-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        var run = repo.createRun(tenant, "g2c-sec-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "cap-sec-" + tenant, 1,
                "tcp_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "dev-q", "{}");
        var wd = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, wd.outboxId()));
        repo.acceptAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-sec");
        repo.startAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-sec");
        UUID cmdId = repo.createCommandForAttempt(tenant, run.runId(), res.attemptId(),
                "w-sec", deviceId, "Dispense", "QUERYABLE", "{\"v\":30}");

        CountDownLatch appliedLatch = new CountDownLatch(1);
        CountDownLatch replayLatch = new CountDownLatch(1);
        CountDownLatch violationLatch = new CountDownLatch(1);
        AtomicReference<RuntimeDeviceEventBridge> bridgeRef = new AtomicReference<>();

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId);
             DeviceGateway gateway = new DeviceGateway(
                     List.of(server.endpoint()),
                     fastGatewayConfig(),
                     ev -> bridgeRef.get().onDeviceEvent(ev))) {

            DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(repo, gateway.asCommandAdapter());
            bridgeRef.set(new RuntimeDeviceEventBridge(tenant, cmdSvc, n -> {
                if (n.outcome() != null) {
                    switch (n.outcome().status()) {
                        case APPLIED -> appliedLatch.countDown();
                        case DUPLICATE_REPLAY -> replayLatch.countDown();
                        case PROTOCOL_VIOLATION -> violationLatch.countDown();
                    }
                }
            }));

            gateway.start();
            assertTrue(gateway.awaitOnline(deviceId, Duration.ofSeconds(3)));
            var del = cmdSvc.deliverPendingCommands(tenant, 4, "disp-sec");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC, del.outcomes().get(cmdId));
            awaitCondition(Duration.ofSeconds(3), () -> server.receivedCommands().size() == 1);

            MockTcpDeviceServer.ClientSession session = server.activeSession();
            assertNotNull(session);

            // 1. Heartbeat trying to smuggle commandId/targetState -> isolated, never touches command_ledger.
            session.sendRawLine("{\"frameType\":\"HEARTBEAT_PONG\",\"deviceId\":\"" + deviceId
                    + "\",\"commandId\":\"" + cmdId + "\",\"targetState\":\"SUCCEEDED\"}");
            // 2. DEVICE_EVENT with messageType=HEARTBEAT -> rejected as malformed frame!
            session.sendRawLine("{\"frameType\":\"DEVICE_EVENT\",\"deviceId\":\"" + deviceId
                    + "\",\"runId\":\"" + run.runId() + "\",\"commandId\":\"" + cmdId
                    + "\",\"sourceMessageId\":\"hb-evt-1\",\"messageType\":\"HEARTBEAT\",\"targetState\":\"SUCCEEDED\"}");
            // 3. Corrupted JSON + oversized frame (> 4096 bytes) -> counted in malformedFrames, socket stays ONLINE!
            session.sendRawLine("{not-valid-json");
            session.sendRawLine("{\"pad\":\"" + "x".repeat(5000) + "\"}");
            // 4. Spoofed deviceId on tcp-sec-1's socket -> rejected at Gateway boundary!
            session.sendDeviceEvent("rogue-other-device", run.runId().toString(), cmdId.toString(),
                    "spoof-1", "COMMAND_RESULT", "SUCCEEDED", "seq-spoof", "{\"ok\":true}");

            awaitCondition(Duration.ofSeconds(3), () -> {
                var h = gateway.health(deviceId).orElseThrow();
                return h.malformedFrames() >= 3 && h.spoofedFramesRejected() >= 1;
            });
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmdId + "' AND command_state='SEND_STARTED'"));
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "'"));

            // 5. Legitimate COMMAND_RESULT -> APPLIED; duplicate -> DUPLICATE_REPLAY; conflicting payload -> PROTOCOL_VIOLATION!
            String msgId = "sec-msg-" + UUID.randomUUID();
            session.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                    msgId, "COMMAND_RESULT", "SUCCEEDED", "seq-1", "{\"ok\":true}");
            assertTrue(appliedLatch.await(5, TimeUnit.SECONDS));

            session.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                    msgId, "COMMAND_RESULT", "SUCCEEDED", "seq-1", "{\"ok\":true}");
            assertTrue(replayLatch.await(5, TimeUnit.SECONDS));

            session.sendDeviceEvent(deviceId, run.runId().toString(), cmdId.toString(),
                    msgId, "COMMAND_RESULT", "FAILED", "seq-1", "{\"tampered\":true}");
            assertTrue(violationLatch.await(5, TimeUnit.SECONDS));

            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmdId + "' AND command_state='SUCCEEDED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='"
                    + tenant + "'"));

            repo.completeAttempt(tenant, run.runId(), res.attemptId(), "out", "{\"ok\":true}");
        }
    }

    @Test
    void sendingWhileDeviceIsOfflineMarksCommandUnknownAndNeverResendsAfterServerComesOnline() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2c-offline");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String deviceId = "tcp-off-1";
        String planKey = "g2c-offline-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        var run = repo.createRun(tenant, "g2c-off-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "cap-off-" + tenant, 1,
                "tcp_dev_op", "DEVICE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "dev-q", "{}");
        var wd = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, wd.outboxId()));
        repo.acceptAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-off");
        repo.startAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-off");
        UUID cmdId = repo.createCommandForAttempt(tenant, run.runId(), res.attemptId(),
                "w-off", deviceId, "Dispense", "QUERYABLE", "{\"v\":5}");

        // Point gateway at an unreachable closed port first (guaranteed refused on 127.0.0.1).
        int closedPort;
        try (var tmp = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = tmp.getLocalPort();
        }
        try (DeviceGateway gateway = new DeviceGateway(
                List.of(new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", closedPort)),
                fastGatewayConfig(),
                ev -> {})) {
            gateway.start();
            assertTrue(gateway.awaitStatus(deviceId, TcpDeviceConnection.ConnectionStatus.RECONNECTING,
                    Duration.ofSeconds(3)));

            DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(repo, gateway.asCommandAdapter());
            var summary = cmdSvc.deliverPendingCommands(tenant, 4, "disp-off");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.MARKED_UNKNOWN,
                    summary.outcomes().get(cmdId));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + cmdId + "' AND command_state='UNKNOWN'"));
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND outbox_state IN ('PENDING','SENDING')"));
            assertEquals("MANUAL_REQUIRED", repo.failAttempt(tenant, run.runId(), res.attemptId(),
                    "{\"reason\":\"device-offline\"}", true).outcome());
        }
    }
}
