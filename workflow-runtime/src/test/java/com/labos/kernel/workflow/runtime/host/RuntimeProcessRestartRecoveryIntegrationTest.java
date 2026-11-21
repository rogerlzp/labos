package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.gateway.DeviceEventSink;
import com.labos.kernel.workflow.runtime.gateway.DeviceGateway;
import com.labos.kernel.workflow.runtime.gateway.MockTcpDeviceServer;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4-A integration tests proving deterministic single-instance crash recovery across process restarts
 * without duplicate physical device side effects or wedged outbox/inbox/drain state.
 */
class RuntimeProcessRestartRecoveryIntegrationTest {

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

    private static String querySingleText(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? String.valueOf(rs.getString(1)) : "<none>";
        }
    }

    private static UUID createTenant(DriverManagerDataSource ds, String prefix) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','" + prefix + "-" + tenant + "','g4a-it')");
        }
        return tenant;
    }

    private static GraphPlan compilePlan(String planKey, String xml) {
        return new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                planKey, "1", xml, Map.of()));
    }

    private static GraphPlan compileSingleDevicePlan(String planKey) {
        return compilePlan(planKey, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="dev_op" protocolName="Dispense" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="dev_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="dev_op" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);
    }

    private static TcpDeviceConnection.Config stableGatewayConfig() {
        return new TcpDeviceConnection.Config(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                Duration.ofMillis(200),
                4096,
                64);
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
        assertTrue(cond.getAsBoolean(),
                () -> "Condition not satisfied within " + timeout + " | " + diagnosticOnFailure.get());
    }

    private static String diagnoseRun(DriverManagerDataSource ds, UUID tenant, UUID runId,
                                      EmbeddedWorkflowRuntimeHost host) {
        try {
            var snap = host.healthSnapshot();
            String status = querySingleText(ds,
                    "SELECT status||':'||control_gate||':'||recovery_state FROM workflow_runtime.workflow_run WHERE tenant_id='"
                            + tenant + "' AND run_id='" + runId + "'");
            String attempts = querySingleText(ds,
                    "SELECT string_agg(attempt_no||':'||attempt_state, ',') FROM workflow_runtime.node_attempt WHERE tenant_id='"
                            + tenant + "' AND run_id='" + runId + "'");
            String cmds = querySingleText(ds,
                    "SELECT string_agg(command_state, ',') FROM workflow_runtime.command_ledger WHERE tenant_id='"
                            + tenant + "' AND run_id='" + runId + "'");
            String outbox = querySingleText(ds,
                    "SELECT string_agg(message_kind||'='||outbox_state, ',') FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                            + tenant + "' AND run_id='" + runId + "'");
            return "run=" + status + ", attempts=" + attempts + ", cmds=" + cmds
                    + ", outbox=" + outbox + ", hostState=" + snap.hostState()
                    + ", recovery=" + snap.recoveryResult()
                    + ", dispatchStats=" + snap.dispatchStats();
        } catch (Exception e) {
            return "diag-error: " + e;
        }
    }

    @Test
    void crashBeforeSendStartedWithSendingOutboxCancelsAndRetriesCleanlyOnce() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g4a-pre-send");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        String deviceId = "g4a-dev-presend-" + tenant.toString().substring(0, 8);
        String planKey = "g4a-plan-presend-" + tenant;
        String capKey = "g4a-cap-presend-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        Path lockFile = tempDir.resolve("presend.lock");

        // Pre-crash Host 1 state: Attempt #1 started, Command #1 CREATED, DEVICE_COMMAND outbox SENDING,
        // capacity reserved, crashed BEFORE markCommandSendStarted commits.
        var run = repo.createRun(tenant, "g4a-presend-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());

        var res1 = repo.reserveCapacity(tenant, run.runId(), capKey, 1,
                "dev_op", "DEVICE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res1.attemptId(), "dev-q", "{}");
        var wd1 = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res1.attemptId())).findFirst().orElseThrow();
        repo.markOutboxDelivered(tenant, wd1.outboxId());
        repo.acceptAttemptForExecution(tenant, run.runId(), res1.attemptId(), res1.dispatchId(),
                "host1-w1", "dev_op", "DEVICE");
        repo.startAttempt(tenant, run.runId(), res1.attemptId(), res1.dispatchId(), "host1-w1");
        UUID cmd1 = repo.createCommandForAttempt(tenant, run.runId(), res1.attemptId(),
                "host1-w1", deviceId, "Dispense", "NON_IDEMPOTENT", "{\"vol\":25}");
        var dcOutbox1 = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 10).stream()
                .filter(m -> cmd1.toString().equals(m.payloadJson().contains(cmd1.toString()) ? cmd1.toString() : ""))
                .findFirst().orElseThrow();
        assertEquals(1, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                + tenant + "' AND capacity_key='" + capKey + "'"));

        CountDownLatch terminalApplied = new CountDownLatch(1);
        AtomicReference<UUID> retryCmdRef = new AtomicReference<>();

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> {
                try {
                    session.sendDeviceEvent(deviceId, cmdNode.get("runId").asText(), cmdNode.get("commandId").asText(),
                            "g4a-ok-" + cmdNode.get("commandId").asText(),
                            "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-1", "{\"vol\":25}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-2", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            capKey, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmd2 = ctx.createDeviceCommand(deviceId, "Dispense", "NON_IDEMPOTENT", "{\"vol\":25}");
                        retryCmdRef.set(cmd2);
                        assertTrue(terminalApplied.await(8, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"vol\":25}");
                    }),
                    notification -> {
                        if (notification.error() == null
                                && notification.outcome() != null
                                && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED
                                && "SUCCEEDED".equals(notification.event().targetState())) {
                            terminalApplied.countDown();
                        }
                    })) {
                host2.start();
                assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                var recovery = host2.healthSnapshot().recoveryResult();
                assertNotNull(recovery);
                assertEquals(1, recovery.safeToRetry());
                assertEquals(0, recovery.manualRequired());

                // Attempt #1 / Command #1 / SENDING Outbox safely cancelled before opening dispatch
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                        + tenant + "' AND attempt_id='" + res1.attemptId() + "' AND attempt_state='ABANDONED_SAFE'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmd1 + "' AND command_state='CANCELLED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenant + "' AND outbox_id='" + dcOutbox1.outboxId() + "' AND outbox_state='DEAD_LETTER'"));

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId()
                                + "' AND status='SUCCEEDED' AND control_gate='CLOSED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> diagnoseRun(ds, tenant, run.runId(), host2));

                UUID cmd2 = retryCmdRef.get();
                assertNotNull(cmd2);
                assertNotEquals(cmd1, cmd2);
                assertEquals(1, server.receivedCommands().size(),
                        "Physical device must receive only retry Command #2, never cancelled Command #1");
                assertEquals(cmd2.toString(), server.receivedCommands().get(0).get("commandId").asText());
                assertEquals(2, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND activation_no=2 AND activation_state='SUCCEEDED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND attempt_id<>'" + res1.attemptId() + "' AND attempt_state='SUCCEEDED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + cmd2 + "' AND command_state='SUCCEEDED'"));
                assertEquals(0, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                        + tenant + "' AND capacity_key='" + capKey + "'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.dispatch_reservation WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND reservation_state='HELD'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND outbox_state IN ('PENDING','SENDING')"));
                assertTrue(scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND event_type='ATTEMPT_ABANDONED_SAFE'") >= 1);
            }
        }
    }

    @Test
    void crashWithTerminalAndMissingDeviceCommandOutboxInSendingReconcilesBeforeCompletion() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID isolatedTenant = createTenant(ds, "g4a-outbox-partA");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        // Part A: partition test for recoverOutboxAfterRestartForTenant across all terminal/missing/uncertain states
        GraphPlan singlePlan = compileSingleDevicePlan("g4a-partA-" + isolatedTenant);
        record RowCase(UUID cmdId, String finalCommandState, String expectOutboxState, UUID outboxId) {}
        List<String> targetStates = List.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCEL_REQUESTED");
        java.util.ArrayList<RowCase> cases = new java.util.ArrayList<>();

        UUID firstRunId = null;
        for (int i = 0; i < targetStates.size(); i++) {
            String state = targetStates.get(i);
            var syntheticRun = repo.createRun(isolatedTenant, "g4a-outboxA-" + i + "-" + UUID.randomUUID(), "CREATE", "{}",
                    "g4a-partA-" + isolatedTenant, "1", HexFormat.of().parseHex(singlePlan.bpmnSha256()),
                    singlePlan.compilerVersion(), GraphPlanCodec.toJson(singlePlan), "{}", "start");
            if (firstRunId == null) firstRunId = syntheticRun.runId();
            repo.startRun(isolatedTenant, syntheticRun.runId());
            new GraphCoordinator(repo).advance(isolatedTenant, syntheticRun.runId());
            var resA = repo.reserveCapacity(isolatedTenant, syntheticRun.runId(), "capA-" + isolatedTenant + "-" + i, 1,
                    "dev_op", "DEVICE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(isolatedTenant, syntheticRun.runId(), resA.attemptId(), "q", "{}");
            var wdA = repo.beginOutboxSendForKind(isolatedTenant, "WORKER_DISPATCH", 10).get(0);
            repo.markOutboxDelivered(isolatedTenant, wdA.outboxId());
            repo.acceptAttemptForExecution(isolatedTenant, syntheticRun.runId(), resA.attemptId(),
                    resA.dispatchId(), "wA-" + i, "dev_op", "DEVICE");
            repo.startAttempt(isolatedTenant, syntheticRun.runId(), resA.attemptId(), resA.dispatchId(), "wA-" + i);
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                UUID actId = UUID.fromString(querySingleText(ds,
                        "SELECT activation_id FROM workflow_runtime.node_attempt WHERE tenant_id='"
                                + isolatedTenant + "' AND attempt_id='" + resA.attemptId() + "'"));
                UUID cmdId = UUID.randomUUID();
                UUID outboxId = UUID.randomUUID();
                st.executeUpdate("""
                    INSERT INTO workflow_runtime.command_ledger
                      (tenant_id,command_id,run_id,activation_id,attempt_id,device_id,command_kind,
                       command_state,idempotency_class,command_payload,payload_hash)
                    VALUES ('%s','%s','%s','%s','%s','dev-A','Dispense','%s','QUERYABLE','{}'::jsonb,
                            decode(repeat('00',32),'hex'))
                    """.formatted(isolatedTenant, cmdId, syntheticRun.runId(), actId, resA.attemptId(), state));
                st.executeUpdate("""
                    INSERT INTO workflow_runtime.runtime_outbox
                      (tenant_id,outbox_id,run_id,message_kind,dedup_key,payload,outbox_state)
                    VALUES ('%s','%s','%s','DEVICE_COMMAND','synth-dc-%d',
                            '{"commandId":"%s"}'::jsonb,'SENDING')
                    """.formatted(isolatedTenant, outboxId, syntheticRun.runId(), i, cmdId));
                String expected = switch (state) {
                    case "SUCCEEDED", "FAILED" -> "DELIVERED";
                    case "CANCELLED" -> "DEAD_LETTER";
                    default -> "SENDING";
                };
                cases.add(new RowCase(cmdId, state, expected, outboxId));
            }
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            UUID missingCmdOutbox = UUID.randomUUID();
            UUID missingCmdId = UUID.randomUUID();
            st.executeUpdate("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,outbox_id,run_id,message_kind,dedup_key,payload,outbox_state)
                VALUES ('%s','%s','%s','DEVICE_COMMAND','synth-dc-missing',
                        '{"commandId":"%s"}'::jsonb,'SENDING')
                """.formatted(isolatedTenant, missingCmdOutbox, firstRunId, missingCmdId));
            cases.add(new RowCase(missingCmdId, "MISSING", "DEAD_LETTER", missingCmdOutbox));
        }

        try {
            var outboxResult = repo.recoverOutboxAfterRestartForTenant(isolatedTenant);
            assertEquals(0, outboxResult.safelyRequeued());
            assertEquals(1, outboxResult.leftUncertain());
            assertEquals(4, outboxResult.reconciledTerminal());
            for (RowCase rc : cases) {
                assertEquals(rc.expectOutboxState(), querySingleText(ds,
                        "SELECT outbox_state FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                                + isolatedTenant + "' AND outbox_id='" + rc.outboxId() + "'"),
                        "Mismatch for " + rc.finalCommandState());
                if ("DELIVERED".equals(rc.expectOutboxState())) {
                    assertEquals(1, scalar(ds,
                            "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                                    + isolatedTenant + "' AND outbox_id='" + rc.outboxId()
                                    + "' AND delivered_at IS NOT NULL"));
                }
            }
        } finally {
            // Reconcile Part A's synthetic STARTED attempts and dead-letter uncertain SENDING outbox rows
            // so shared-DB tests in other classes remain completely isolated.
            new WorkerExecutionRecoveryService(repo, new WorkerExecutionRegistry(), isolatedTenant)
                    .recoverAtStartup(20, "partA-cleanup");
            for (RowCase rc : cases) {
                repo.markOutboxDeadLetter(isolatedTenant, rc.outboxId());
            }
        }

        // Part B: End-to-end 2-step Run where Step 1 succeeded yet stranded DEVICE_COMMAND outbox in SENDING;
        // Host 2 must reconcile it to DELIVERED so Step 2 + maybeCompleteGraphRun never block forever!
        UUID tenantB = createTenant(ds, "g4a-outbox-partB");
        String deviceId = "g4a-dev-outboxB-" + tenantB.toString().substring(0, 8);
        String planKeyB = "g4a-plan-2step-" + tenantB;
        GraphPlan twoStepPlan = compilePlan(planKeyB, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="step1" protocolName="Prep" resource="1"/>
                    <bpmn:task id="step2" protocolName="Finish" resource="2"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="step1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="step1" targetRef="step2"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="step2" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);
        var runB = repo.createRun(tenantB, "g4a-2step-" + UUID.randomUUID(), "CREATE", "{}",
                planKeyB, "1", HexFormat.of().parseHex(twoStepPlan.bpmnSha256()),
                twoStepPlan.compilerVersion(), GraphPlanCodec.toJson(twoStepPlan), "{}", "start");
        repo.startRun(tenantB, runB.runId());
        new GraphCoordinator(repo).advance(tenantB, runB.runId());

        var resS1 = repo.reserveCapacity(tenantB, runB.runId(), "capB-" + tenantB, 1,
                "step1", "DEVICE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenantB, runB.runId(), resS1.attemptId(), "dev-q", "{}");
        var wdS1 = repo.beginOutboxSendForKind(tenantB, "WORKER_DISPATCH", 10).get(0);
        repo.markOutboxDelivered(tenantB, wdS1.outboxId());
        repo.acceptAttemptForExecution(tenantB, runB.runId(), resS1.attemptId(),
                resS1.dispatchId(), "w-s1", "step1", "DEVICE");
        repo.startAttempt(tenantB, runB.runId(), resS1.attemptId(), resS1.dispatchId(), "w-s1");
        UUID cmdS1 = repo.createCommandForAttempt(tenantB, runB.runId(), resS1.attemptId(),
                "w-s1", deviceId, "Prep", "QUERYABLE", "{\"s\":1}");
        var dcS1 = repo.beginOutboxSendForKind(tenantB, "DEVICE_COMMAND", 10).get(0);
        long verS1 = repo.loadCommand(tenantB, cmdS1).orElseThrow().version();
        assertTrue(repo.markCommandSendStarted(tenantB, cmdS1, verS1, dcS1.outboxId(),
                UUID.randomUUID(), 1, "disp-1"));
        var inboxS1 = repo.receiveInbox(tenantB, deviceId, "s1-msg-" + cmdS1, "DEVICE_COMMAND_RESULT", runB.runId(),
                "{\"commandId\":\"" + cmdS1 + "\",\"deviceId\":\"" + deviceId
                        + "\",\"messageType\":\"DEVICE_COMMAND_RESULT\",\"state\":\"SUCCEEDED\",\"deviceSequence\":\"s1\",\"outcome\":{\"ok\":1}}");
        assertTrue(repo.applyCommandEvent(tenantB, runB.runId(), inboxS1.inboxId(), cmdS1,
                "SUCCEEDED", "s1", "{\"ok\":1}"));
        // Step 1 Worker committed attempt SUCCEEDED while dcS1 remained SENDING due to crash right before markOutboxDelivered
        repo.completeAttempt(tenantB, runB.runId(), resS1.attemptId(), "s1", "{\"ok\":1}");

        CountDownLatch step2Applied = new CountDownLatch(1);
        Path lockFile = tempDir.resolve("outbox-partb.lock");
        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> {
                try {
                    session.sendDeviceEvent(deviceId, cmdNode.get("runId").asText(), cmdNode.get("commandId").asText(),
                            "s2-msg-" + cmdNode.get("commandId").asText(),
                            "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-2", "{\"ok\":2}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-outboxB", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(server.endpoint()), stableGatewayConfig(), tenantB);

            try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "capB-" + tenantB, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        assertEquals("step2", req.nodeKey());
                        ctx.createDeviceCommand(deviceId, "Finish", "QUERYABLE", "{\"s\":2}");
                        assertTrue(step2Applied.await(8, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("s2", "{\"ok\":2}");
                    }),
                    notification -> {
                        if (notification.error() == null
                                && notification.outcome() != null
                                && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED
                                && "SUCCEEDED".equals(notification.event().targetState())) {
                            step2Applied.countDown();
                        }
                    })) {
                host2.start();
                assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));
                assertTrue(host2.healthSnapshot().recoveryResult().outbox().reconciledTerminal() >= 1);
                assertEquals("DELIVERED", querySingleText(ds,
                        "SELECT outbox_state FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                                + tenantB + "' AND outbox_id='" + dcS1.outboxId() + "'"));

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenantB + "' AND run_id='" + runB.runId()
                                + "' AND status='SUCCEEDED' AND control_gate='CLOSED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> diagnoseRun(ds, tenantB, runB.runId(), host2));

                assertEquals(1, server.receivedCommands().size(), "Only step2 command should hit TCP device");
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenantB + "' AND run_id='" + runB.runId()
                        + "' AND outbox_state IN ('PENDING','SENDING')"));
            }
        }
    }

    @Test
    void crashBetweenReceiveInboxAndApplyCommandEventReplaysInboxAndBlocksDuplicateExecution() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g4a-inbox-gap");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        String deviceId = "g4a-dev-inbox-" + tenant.toString().substring(0, 8);
        String planKey = "g4a-plan-inbox-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        Path lockFile = tempDir.resolve("inbox-gap.lock");

        var run = repo.createRun(tenant, "g4a-inbox-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());

        var res1 = repo.reserveCapacity(tenant, run.runId(), "cap-inbox-" + tenant, 1,
                "dev_op", "DEVICE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res1.attemptId(), "dev-q", "{}");
        var wd1 = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).get(0);
        repo.markOutboxDelivered(tenant, wd1.outboxId());
        repo.acceptAttemptForExecution(tenant, run.runId(), res1.attemptId(),
                res1.dispatchId(), "w-inbox", "dev_op", "DEVICE");
        repo.startAttempt(tenant, run.runId(), res1.attemptId(), res1.dispatchId(), "w-inbox");
        UUID cmd1 = repo.createCommandForAttempt(tenant, run.runId(), res1.attemptId(),
                "w-inbox", deviceId, "Dispense", "NON_IDEMPOTENT", "{\"vol\":42}");
        var dc1 = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 10).get(0);
        UUID dispatchId1 = UUID.randomUUID();
        long ver1 = repo.loadCommand(tenant, cmd1).orElseThrow().version();
        assertTrue(repo.markCommandSendStarted(tenant, cmd1, ver1, dc1.outboxId(),
                dispatchId1, 1, "disp-inbox"));

        // Receive terminal SUCCEEDED Inbox receipt, plus a later stale RUNNING receipt; crash BEFORE applyCommandEvent!
        String outcomeJson = "{\"dispensed\":42}";
        var terminalReceipt = repo.receiveInbox(tenant, deviceId, "res-" + cmd1,
                "DEVICE_COMMAND_RESULT", run.runId(),
                "{\"commandId\":\"" + cmd1 + "\",\"deviceId\":\"" + deviceId
                        + "\",\"messageType\":\"DEVICE_COMMAND_RESULT\",\"state\":\"SUCCEEDED\",\"deviceSequence\":\"seq-crash-42\",\"outcome\":"
                        + outcomeJson + "}");
        var staleProgressReceipt = repo.receiveInbox(tenant, deviceId, "prog-late-" + cmd1,
                "PROGRESS", run.runId(),
                "{\"commandId\":\"" + cmd1 + "\",\"deviceId\":\"" + deviceId
                        + "\",\"messageType\":\"PROGRESS\",\"state\":\"RUNNING\",\"deviceSequence\":\"seq-late\",\"outcome\":{\"pct\":50}}");
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE workflow_runtime.inbox_receipt SET received_at = received_at + interval '10 milliseconds' "
                             + "WHERE tenant_id=? AND inbox_id=?")) {
            ps.setObject(1, tenant);
            ps.setObject(2, staleProgressReceipt.inboxId());
            ps.executeUpdate();
        }

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-inbox2", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "cap-inbox-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        fail("Host must never retry an Attempt whose command already crossed SEND_STARTED / SUCCEEDED");
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    }))) {
                host2.start();
                assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                var recovery = host2.healthSnapshot().recoveryResult();
                assertNotNull(recovery);
                assertEquals(1, recovery.inbox().applied());
                assertEquals(1, recovery.inbox().rejected());
                assertEquals(0, recovery.safeToRetry());
                assertEquals(1, recovery.manualRequired());
                assertEquals(1, recovery.outbox().reconciledTerminal());
                assertEquals(0, recovery.outbox().leftUncertain());

                // Run >= 2 live cycles to confirm zero retry dispatch!
                awaitCondition(Duration.ofSeconds(4),
                        () -> host2.healthSnapshot().dispatchStats().cyclesCompleted() >= 2,
                        () -> diagnoseRun(ds, tenant, run.runId(), host2));

                // Multi-table invariants:
                assertEquals("PROCESSED", querySingleText(ds,
                        "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                                + tenant + "' AND inbox_id='" + terminalReceipt.inboxId() + "'"));
                assertEquals("REJECTED", querySingleText(ds,
                        "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                                + tenant + "' AND inbox_id='" + staleProgressReceipt.inboxId() + "'"));
                assertEquals("SUCCEEDED", querySingleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                + tenant + "' AND command_id='" + cmd1 + "'"));
                assertEquals("seq-crash-42", querySingleText(ds,
                        "SELECT device_sequence FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                + tenant + "' AND command_id='" + cmd1 + "'"));
                assertEquals("DELIVERED", querySingleText(ds,
                        "SELECT dispatch_state FROM workflow_runtime.command_dispatch_attempt WHERE tenant_id='"
                                + tenant + "' AND dispatch_id='" + dispatchId1 + "'"));
                assertEquals("DELIVERED", querySingleText(ds,
                        "SELECT outbox_state FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                                + tenant + "' AND outbox_id='" + dc1.outboxId() + "'"));
                assertEquals("UNKNOWN", querySingleText(ds,
                        "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id='"
                                + tenant + "' AND attempt_id='" + res1.attemptId() + "'"));
                assertEquals("MANUAL_REQUIRED:CLOSED:BLOCKED", querySingleText(ds,
                        "SELECT status||':'||control_gate||':'||recovery_state FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId() + "'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND intervention_state='OPEN'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                        + tenant + "' AND attempt_id='" + res1.attemptId()
                        + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND event_type='INBOX_REJECTED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_audit WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND action='INBOX_RECEIPT_REJECTED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "'"));
                assertTrue(server.receivedCommands().isEmpty());
            }
        }
    }

    @Test
    void crashAfterSendStartedAndCancelRequestedEscalatesToManualRequiredWithoutReexecution() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g4a-uncertain");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        String deviceId = "g4a-dev-uncertain-" + tenant.toString().substring(0, 8);
        String planKey = "g4a-plan-uncertain-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        Path lockFile = tempDir.resolve("uncertain.lock");

        CountDownLatch cmdArrivedOnServer = new CountDownLatch(1);
        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> cmdArrivedOnServer.countDown());

            // Run A: real DeviceGateway TCP send crosses SEND_STARTED and delivers Command #1 over wire before crash
            var runA = repo.createRun(tenant, "g4a-unc-A-" + UUID.randomUUID(), "CREATE", "{}",
                    planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            repo.startRun(tenant, runA.runId());
            new GraphCoordinator(repo).advance(tenant, runA.runId());
            var resA = repo.reserveCapacity(tenant, runA.runId(), "cap-uncA-" + tenant, 1,
                    "dev_op", "DEVICE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runA.runId(), resA.attemptId(), "dev-q", "{}");
            var wdA = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).get(0);
            repo.markOutboxDelivered(tenant, wdA.outboxId());
            repo.acceptAttemptForExecution(tenant, runA.runId(), resA.attemptId(),
                    resA.dispatchId(), "wA", "dev_op", "DEVICE");
            repo.startAttempt(tenant, runA.runId(), resA.attemptId(), resA.dispatchId(), "wA");
            UUID cmdA = repo.createCommandForAttempt(tenant, runA.runId(), resA.attemptId(),
                    "wA", deviceId, "Dispense", "NON_IDEMPOTENT", "{\"vol\":55}");

            try (DeviceGateway preCrashGateway = new DeviceGateway(
                    List.of(server.endpoint()), stableGatewayConfig(),
                    event -> DeviceEventSink.DeliveryResult.acked("APPLIED"))) {
                preCrashGateway.start();
                assertTrue(preCrashGateway.awaitOnline(deviceId, Duration.ofSeconds(3)));
                DeviceCommandExecutionService cmdService =
                        new DeviceCommandExecutionService(repo, preCrashGateway.asCommandAdapter());
                var delivery = cmdService.deliverPendingCommands(tenant, 10, "pre-crash-disp");
                assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC,
                        delivery.outcomes().get(cmdA));
                assertTrue(cmdArrivedOnServer.await(5, TimeUnit.SECONDS));
            }
            assertEquals(1, server.receivedCommands().size());

            // Run B: synthetic attempt holding a command in CANCEL_REQUESTED (Gap 3 explicit check)
            var runB = repo.createRun(tenant, "g4a-unc-B-" + UUID.randomUUID(), "CREATE", "{}",
                    planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            repo.startRun(tenant, runB.runId());
            new GraphCoordinator(repo).advance(tenant, runB.runId());
            var resB = repo.reserveCapacity(tenant, runB.runId(), "cap-uncB-" + tenant, 1,
                    "dev_op", "DEVICE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runB.runId(), resB.attemptId(), "dev-q", "{}");
            var wdB = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).get(0);
            repo.markOutboxDelivered(tenant, wdB.outboxId());
            repo.acceptAttemptForExecution(tenant, runB.runId(), resB.attemptId(),
                    resB.dispatchId(), "wB", "dev_op", "DEVICE");
            repo.startAttempt(tenant, runB.runId(), resB.attemptId(), resB.dispatchId(), "wB");
            UUID cmdB = repo.createCommandForAttempt(tenant, runB.runId(), resB.attemptId(),
                    "wB", deviceId, "Dispense", "NON_IDEMPOTENT", "{\"vol\":66}");
            var dcB = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 10).get(0);
            UUID dispB = UUID.randomUUID();
            long verB = repo.loadCommand(tenant, cmdB).orElseThrow().version();
            assertTrue(repo.markCommandSendStarted(tenant, cmdB, verB, dcB.outboxId(),
                    dispB, 1, "disp-uncB"));
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE workflow_runtime.command_ledger SET command_state='CANCEL_REQUESTED' "
                                 + "WHERE tenant_id=? AND command_id=?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, cmdB);
                ps.executeUpdate();
            }

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-unc2", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);
            try {
                try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                        cfg, repo,
                        node -> new LocalTaskDispatchService.TaskDispatchSpec(
                                "cap-unc2-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                        node -> Optional.of((req, ctx) -> {
                            fail("Host 2 must never re-execute ambiguous SEND_STARTED / CANCEL_REQUESTED attempts");
                            return WorkerTaskHandler.TaskResult.of("out", "{}");
                        }))) {
                    host2.start();
                    assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                    var recovery = host2.healthSnapshot().recoveryResult();
                    assertEquals(2, recovery.manualRequired());
                    assertEquals(0, recovery.safeToRetry());
                    assertEquals(1, recovery.outbox().leftUncertain());

                    awaitCondition(Duration.ofSeconds(4),
                            () -> host2.healthSnapshot().dispatchStats().cyclesCompleted() >= 3,
                            () -> diagnoseRun(ds, tenant, runA.runId(), host2));

                    for (UUID rId : List.of(runA.runId(), runB.runId())) {
                        assertEquals("MANUAL_REQUIRED:CLOSED:BLOCKED", querySingleText(ds,
                                "SELECT status||':'||control_gate||':'||recovery_state FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                        + tenant + "' AND run_id='" + rId + "'"));
                        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id='"
                                + tenant + "' AND run_id='" + rId + "' AND intervention_state='OPEN'"));
                    }
                    assertEquals("UNKNOWN", querySingleText(ds,
                            "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                    + tenant + "' AND command_id='" + cmdA + "'"));
                    assertEquals("UNKNOWN", querySingleText(ds,
                            "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                    + tenant + "' AND command_id='" + cmdB + "'"));
                    assertEquals("DELIVERY_UNKNOWN", querySingleText(ds,
                            "SELECT dispatch_state FROM workflow_runtime.command_dispatch_attempt WHERE tenant_id='"
                                    + tenant + "' AND dispatch_id='" + dispB + "'"));
                    assertEquals(1, server.receivedCommands().size(),
                            "Host 2 restart must NEVER resend Command A or trigger duplicate physical execution");
                }
            } finally {
                // Dead-letter uncertain SENDING outbox so shared-DB tests in other classes stay isolated.
                repo.markOutboxDeadLetter(tenant, dcB.outboxId());
            }
        }
    }

    @Test
    void crashAfterStepOneBeforeXorEvaluationResumesFromPersistedFlowVariables() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g4a-flowvar");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        String deviceId = "g4a-dev-xor-" + tenant.toString().substring(0, 8);
        String planKey = "g4a-plan-xor-" + tenant;
        GraphPlan plan = compilePlan(planKey, """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="task_eval" protocolName="Eval" resource="0"/>
                    <bpmn:exclusiveGateway id="xor" default="f_low"/>
                    <bpmn:task id="path_high" protocolName="HighOp" resource="1"/>
                    <bpmn:task id="path_low" protocolName="LowOp" resource="2"/>
                    <bpmn:exclusiveGateway id="merge" gatewayDirection="Converging"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="task_eval"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="task_eval" targetRef="xor"/>
                    <bpmn:sequenceFlow id="f_high" sourceRef="xor" targetRef="path_high">
                      <bpmn:conditionExpression>score &gt;= 80</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="f_low" sourceRef="xor" targetRef="path_low"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="path_high" targetRef="merge"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="path_low" targetRef="merge"/>
                    <bpmn:sequenceFlow id="f5" sourceRef="merge" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);
        Path lockFile = tempDir.resolve("flowvar.lock");

        // Pre-crash Step 1 completes task_eval & persists score=95 in flow_variable, crashing BEFORE GraphCoordinator advances xor!
        var run = repo.createRun(tenant, "g4a-xor-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());
        var resEval = repo.reserveCapacity(tenant, run.runId(), "cap-eval-" + tenant, 1,
                "task_eval", "PURE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), resEval.attemptId(), "pure-q", "{}");
        var wdEval = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).get(0);
        repo.markOutboxDelivered(tenant, wdEval.outboxId());
        repo.acceptAttemptForExecution(tenant, run.runId(), resEval.attemptId(),
                resEval.dispatchId(), "w-eval", "task_eval", "PURE");
        repo.startAttempt(tenant, run.runId(), resEval.attemptId(), resEval.dispatchId(), "w-eval");
        repo.completeAttempt(tenant, run.runId(), resEval.attemptId(),
                "eval", "{\"score\":95}", Map.of("score", "95"));
        assertEquals("95", querySingleText(ds,
                "SELECT value::text FROM workflow_runtime.flow_variable WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND variable_key='score'"));

        CountDownLatch highCmdApplied = new CountDownLatch(1);
        AtomicInteger lowBranchInvocations = new AtomicInteger(0);

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            server.setCommandHandler((cmdNode, session) -> {
                try {
                    session.sendDeviceEvent(deviceId, cmdNode.get("runId").asText(), cmdNode.get("commandId").asText(),
                            "xor-high-" + cmdNode.get("commandId").asText(),
                            "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-h", "{\"done\":true}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-xor2", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);

            try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "cap-xor-" + tenant, 1,
                            "path_high".equals(node.key()) ? "DEVICE" : "PURE",
                            "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        if ("path_low".equals(req.nodeKey())) {
                            lowBranchInvocations.incrementAndGet();
                            fail("XOR must route to path_high from persisted score=95");
                        }
                        assertEquals("path_high", req.nodeKey());
                        ctx.createDeviceCommand(deviceId, "HighOp", "QUERYABLE", "{\"high\":true}");
                        assertTrue(highCmdApplied.await(8, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"high\":true}");
                    }),
                    notification -> {
                        if (notification.error() == null
                                && notification.outcome() != null
                                && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED
                                && "SUCCEEDED".equals(notification.event().targetState())) {
                            highCmdApplied.countDown();
                        }
                    })) {
                host2.start();
                assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId()
                                + "' AND status='SUCCEEDED' AND control_gate='CLOSED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> diagnoseRun(ds, tenant, run.runId(), host2));

                assertEquals(0, lowBranchInvocations.get());
                assertEquals(1, server.receivedCommands().size());
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND node_key='path_high' AND activation_state='SUCCEEDED'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND node_key='path_low'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND outbox_state IN ('PENDING','SENDING')"));
            }
        }
    }

    @Test
    void crashWithInFlightFailureDrainConvergesToFailedNotStopped() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g4a-fail-drain");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        String deviceId = "g4a-dev-fail-" + tenant.toString().substring(0, 8);
        String planKey = "g4a-plan-fail-" + tenant;
        GraphPlan plan = compileSingleDevicePlan(planKey);
        Path lockFile = tempDir.resolve("fail-drain.lock");

        // Attempt #1 is STARTED with unsent Command #1 (zero side effects), then failGraphRun creates a FAILURE drain;
        // process crashes before drain converges.
        var run = repo.createRun(tenant, "g4a-faild-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());
        var res1 = repo.reserveCapacity(tenant, run.runId(), "cap-fail-" + tenant, 1,
                "dev_op", "DEVICE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res1.attemptId(), "dev-q", "{}");
        var wd1 = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 10).get(0);
        repo.markOutboxDelivered(tenant, wd1.outboxId());
        repo.acceptAttemptForExecution(tenant, run.runId(), res1.attemptId(),
                res1.dispatchId(), "w-fail", "dev_op", "DEVICE");
        repo.startAttempt(tenant, run.runId(), res1.attemptId(), res1.dispatchId(), "w-fail");
        UUID cmd1 = repo.createCommandForAttempt(tenant, run.runId(), res1.attemptId(),
                "w-fail", deviceId, "Dispense", "QUERYABLE", "{\"v\":1}");

        var failRes = repo.failGraphRun(tenant, run.runId(), null, "UPSTREAM_FATAL", "simulated fatal graph fault");
        assertNotNull(failRes.drainId());
        assertEquals("STOP_REQUESTED:STOP", querySingleText(ds,
                "SELECT status||':'||control_gate FROM workflow_runtime.workflow_run WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "'"));
        assertEquals("FAILURE:REQUESTED", querySingleText(ds,
                "SELECT drain_kind||':'||drain_state FROM workflow_runtime.drain_record WHERE tenant_id='"
                        + tenant + "' AND drain_id='" + failRes.drainId() + "'"));

        try (MockTcpDeviceServer server = new MockTcpDeviceServer(deviceId)) {
            EmbeddedWorkflowRuntimeHost.Config cfg = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-fail2", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(server.endpoint()), stableGatewayConfig(), tenant);
            try (EmbeddedWorkflowRuntimeHost host2 = new EmbeddedWorkflowRuntimeHost(
                    cfg, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "cap-fail-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        fail("FAILURE drain must invalidate READY tokens and never dispatch Retry Attempt #2");
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    }))) {
                host2.start();
                assertTrue(host2.deviceGateway().awaitOnline(deviceId, Duration.ofSeconds(3)));

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.drain_record WHERE tenant_id='"
                                + tenant + "' AND drain_id='" + failRes.drainId()
                                + "' AND drain_state='COMPLETED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> diagnoseRun(ds, tenant, run.runId(), host2));

                assertEquals("FAILED:CLOSED", querySingleText(ds,
                        "SELECT status||':'||control_gate FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenant + "' AND run_id='" + run.runId() + "'"));
                assertEquals("FAILURE:COMPLETED", querySingleText(ds,
                        "SELECT drain_kind||':'||drain_state FROM workflow_runtime.drain_record WHERE tenant_id='"
                                + tenant + "' AND drain_id='" + failRes.drainId() + "'"));
                assertEquals("CANCELLED", querySingleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                + tenant + "' AND command_id='" + cmd1 + "'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND token_state NOT IN ('CONSUMED','INVALIDATED')"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND node_key='dev_op' AND token_state='INVALIDATED'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId()
                        + "' AND outbox_state IN ('PENDING','SENDING')"));
                assertTrue(server.receivedCommands().isEmpty());
            }
        }
    }

    @Test
    void hostRestartPreservesTenantIsolationAndReleasesSchedulerCapacity() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenantA = createTenant(ds, "g4a-iso-A");
        UUID tenantB = createTenant(ds, "g4a-iso-B");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        String devA = "g4a-dev-isoA-" + tenantA.toString().substring(0, 8);
        String devB = "g4a-dev-isoB-" + tenantB.toString().substring(0, 8);
        GraphPlan planA = compileSingleDevicePlan("g4a-plan-isoA-" + tenantA);
        GraphPlan planB = compileSingleDevicePlan("g4a-plan-isoB-" + tenantB);
        String capA = "g4a-cap-isoA-" + tenantA;
        String capB = "g4a-cap-isoB-" + tenantB;
        Path lockFile = tempDir.resolve("tenant-iso.lock");

        // Tenant A: DISPATCHED attempt holding capacity & reservation with WORKER_DISPATCH outbox in SENDING
        var runA = repo.createRun(tenantA, "g4a-isoA-" + UUID.randomUUID(), "CREATE", "{}",
                "g4a-plan-isoA-" + tenantA, "1", HexFormat.of().parseHex(planA.bpmnSha256()),
                planA.compilerVersion(), GraphPlanCodec.toJson(planA), "{}", "start");
        repo.startRun(tenantA, runA.runId());
        new GraphCoordinator(repo).advance(tenantA, runA.runId());
        var resA = repo.reserveCapacity(tenantA, runA.runId(), capA, 1,
                "dev_op", "DEVICE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenantA, runA.runId(), resA.attemptId(), "dev-q", "{}");
        var wdA = repo.beginOutboxSendForKind(tenantA, "WORKER_DISPATCH", 10).get(0);
        assertEquals("SENDING", querySingleText(ds,
                "SELECT outbox_state FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenantA + "' AND outbox_id='" + wdA.outboxId() + "'"));

        // Tenant B: STARTED attempt crossing SEND_STARTED + durable RECEIVED SUCCEEDED inbox receipt
        var runB = repo.createRun(tenantB, "g4a-isoB-" + UUID.randomUUID(), "CREATE", "{}",
                "g4a-plan-isoB-" + tenantB, "1", HexFormat.of().parseHex(planB.bpmnSha256()),
                planB.compilerVersion(), GraphPlanCodec.toJson(planB), "{}", "start");
        repo.startRun(tenantB, runB.runId());
        new GraphCoordinator(repo).advance(tenantB, runB.runId());
        var resB = repo.reserveCapacity(tenantB, runB.runId(), capB, 1,
                "dev_op", "DEVICE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenantB, runB.runId(), resB.attemptId(), "dev-q", "{}");
        var wdB = repo.beginOutboxSendForKind(tenantB, "WORKER_DISPATCH", 10).get(0);
        repo.markOutboxDelivered(tenantB, wdB.outboxId());
        repo.acceptAttemptForExecution(tenantB, runB.runId(), resB.attemptId(),
                resB.dispatchId(), "wB", "dev_op", "DEVICE");
        repo.startAttempt(tenantB, runB.runId(), resB.attemptId(), resB.dispatchId(), "wB");
        UUID cmdB = repo.createCommandForAttempt(tenantB, runB.runId(), resB.attemptId(),
                "wB", devB, "Dispense", "QUERYABLE", "{\"v\":7}");
        var dcB = repo.beginOutboxSendForKind(tenantB, "DEVICE_COMMAND", 10).get(0);
        UUID dispB = UUID.randomUUID();
        long verB = repo.loadCommand(tenantB, cmdB).orElseThrow().version();
        assertTrue(repo.markCommandSendStarted(tenantB, cmdB, verB, dcB.outboxId(), dispB, 1, "disp-B"));
        repo.markOutboxDelivered(tenantB, dcB.outboxId());
        var inboxB = repo.receiveInbox(tenantB, devB, "isoB-ok-" + cmdB,
                "DEVICE_COMMAND_RESULT", runB.runId(),
                "{\"commandId\":\"" + cmdB + "\",\"deviceId\":\"" + devB
                        + "\",\"messageType\":\"DEVICE_COMMAND_RESULT\",\"state\":\"SUCCEEDED\",\"deviceSequence\":\"seqB\",\"outcome\":{\"v\":7}}");

        CountDownLatch cmdAFinished = new CountDownLatch(1);
        try (MockTcpDeviceServer serverA = new MockTcpDeviceServer(devA);
             MockTcpDeviceServer serverB = new MockTcpDeviceServer(devB)) {
            serverA.setCommandHandler((cmdNode, session) -> {
                try {
                    session.sendDeviceEvent(devA, cmdNode.get("runId").asText(), cmdNode.get("commandId").asText(),
                            "isoA-ok-" + cmdNode.get("commandId").asText(),
                            "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seqA", "{\"ok\":1}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Host A scoped strictly to Tenant A
            EmbeddedWorkflowRuntimeHost.Config cfgA = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-isoA", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(serverA.endpoint()), stableGatewayConfig(), tenantA);
            try (EmbeddedWorkflowRuntimeHost hostA = new EmbeddedWorkflowRuntimeHost(
                    cfgA, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            capA, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        ctx.createDeviceCommand(devA, "Dispense", "QUERYABLE", "{\"ok\":1}");
                        assertTrue(cmdAFinished.await(8, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"ok\":1}");
                    }),
                    notification -> {
                        if (notification.error() == null
                                && notification.outcome() != null
                                && notification.outcome().status() == DeviceCommandExecutionService.InboxStatus.APPLIED
                                && "SUCCEEDED".equals(notification.event().targetState())) {
                            cmdAFinished.countDown();
                        }
                    })) {
                hostA.start();
                assertTrue(hostA.deviceGateway().awaitOnline(devA, Duration.ofSeconds(3)));
                var recA = hostA.healthSnapshot().recoveryResult();
                assertEquals(1, recA.safeToRetry());
                assertEquals(0, recA.manualRequired());
                assertEquals(0, recA.inbox().applied());

                awaitCondition(Duration.ofSeconds(8), () -> {
                    try {
                        return scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenantA + "' AND run_id='" + runA.runId()
                                + "' AND status='SUCCEEDED' AND control_gate='CLOSED'") == 1;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, () -> diagnoseRun(ds, tenantA, runA.runId(), hostA));

                assertEquals(0, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                        + tenantA + "' AND capacity_key='" + capA + "'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.dispatch_reservation WHERE tenant_id='"
                        + tenantA + "' AND run_id='" + runA.runId() + "' AND reservation_state='HELD'"));

                // Verify Tenant B remains 100% untouched while Tenant A's scoped host ran!
                assertEquals("STARTED", querySingleText(ds,
                        "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id='"
                                + tenantB + "' AND attempt_id='" + resB.attemptId() + "'"));
                assertEquals("SEND_STARTED", querySingleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                + tenantB + "' AND command_id='" + cmdB + "'"));
                assertEquals("RECEIVED", querySingleText(ds,
                        "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                                + tenantB + "' AND inbox_id='" + inboxB.inboxId() + "'"));
                assertEquals(1, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                        + tenantB + "' AND capacity_key='" + capB + "'"));
                assertEquals("RUNNING:OPEN", querySingleText(ds,
                        "SELECT status||':'||control_gate FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenantB + "' AND run_id='" + runB.runId() + "'"));
            }

            // Host B scoped strictly to Tenant B recovers Tenant B cleanly afterwards
            EmbeddedWorkflowRuntimeHost.Config cfgB = new EmbeddedWorkflowRuntimeHost.Config(
                    lockFile, "host-isoB", 2, 16, 4, 2, 32, 100,
                    Duration.ofMillis(35), 16, List.of(serverB.endpoint()), stableGatewayConfig(), tenantB);
            try (EmbeddedWorkflowRuntimeHost hostB = new EmbeddedWorkflowRuntimeHost(
                    cfgB, repo,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            capB, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        fail("Host B must not retry Attempt whose Inbox ALREADY committed SUCCEEDED");
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    }))) {
                hostB.start();
                assertTrue(hostB.deviceGateway().awaitOnline(devB, Duration.ofSeconds(3)));
                var recB = hostB.healthSnapshot().recoveryResult();
                assertEquals(1, recB.inbox().applied());
                assertEquals(1, recB.manualRequired());
                assertEquals(0, recB.safeToRetry());

                assertEquals("PROCESSED", querySingleText(ds,
                        "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                                + tenantB + "' AND inbox_id='" + inboxB.inboxId() + "'"));
                assertEquals("SUCCEEDED", querySingleText(ds,
                        "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id='"
                                + tenantB + "' AND command_id='" + cmdB + "'"));
                assertEquals("DELIVERED", querySingleText(ds,
                        "SELECT dispatch_state FROM workflow_runtime.command_dispatch_attempt WHERE tenant_id='"
                                + tenantB + "' AND dispatch_id='" + dispB + "'"));
                assertEquals("UNKNOWN", querySingleText(ds,
                        "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id='"
                                + tenantB + "' AND attempt_id='" + resB.attemptId() + "'"));
                assertEquals("MANUAL_REQUIRED:CLOSED:BLOCKED", querySingleText(ds,
                        "SELECT status||':'||control_gate||':'||recovery_state FROM workflow_runtime.workflow_run WHERE tenant_id='"
                                + tenantB + "' AND run_id='" + runB.runId() + "'"));
                assertTrue(serverB.receivedCommands().isEmpty());
            }
        }
    }
}
