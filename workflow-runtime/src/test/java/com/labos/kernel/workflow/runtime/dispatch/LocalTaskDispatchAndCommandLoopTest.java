package com.labos.kernel.workflow.runtime.dispatch;

import com.labos.kernel.workflow.runtime.command.DeviceCommandAdapter;
import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.host.SchedulingGate;
import com.labos.kernel.workflow.runtime.host.SchedulingGateTestSupport;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalTaskDispatchAndCommandLoopTest {
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
                    + tenant + "','" + prefix + "-" + tenant + "','g2b-it')");
        }
        return tenant;
    }

    @Test
    void endToEndPureParallelSplitJoinMockDeviceCommandAndXorCompletesRun() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-e2e");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:parallelGateway id="split" gatewayDirection="Diverging"/>
                    <bpmn:task id="pure_calc" protocolName="Calc" resource="1"/>
                    <bpmn:task id="mock_dispense" protocolName="Dispense" resource="2"/>
                    <bpmn:parallelGateway id="join" gatewayDirection="Converging"/>
                    <bpmn:exclusiveGateway id="xor"/>
                    <bpmn:endEvent id="ok_end"/>
                    <bpmn:endEvent id="reject_end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="split"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="split" targetRef="pure_calc"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="split" targetRef="mock_dispense"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="pure_calc" targetRef="join"/>
                    <bpmn:sequenceFlow id="f5" sourceRef="mock_dispense" targetRef="join"/>
                    <bpmn:sequenceFlow id="f6" sourceRef="join" targetRef="xor"/>
                    <bpmn:sequenceFlow id="f7" sourceRef="xor" targetRef="ok_end">
                      <bpmn:conditionExpression>dispensed >= 50 &amp;&amp; ratio > 0.5</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="f8" sourceRef="xor" targetRef="reject_end">
                      <bpmn:conditionExpression>dispensed &lt; 50 || ratio &lt;= 0.5</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g2b-e2e-" + tenant, "1", xml, Map.of()));
        var run = repo.createRun(tenant, "g2b-e2e-" + UUID.randomUUID(), "CREATE", "{}",
                "g2b-e2e-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");

        CountDownLatch mockCommandCreated = new CountDownLatch(1);
        CountDownLatch mockCommandFinished = new CountDownLatch(1);
        AtomicReference<UUID> deviceCommandId = new AtomicReference<>();

        // Mock synchronous DeviceCommandAdapter: returns COMPLETED_IMMEDIATELY and routes through Inbox.
        DeviceCommandAdapter mockAdapter = env -> DeviceCommandAdapter.SendResult.completedImmediately(
                "mock-msg-" + env.commandId(), "dev-seq-101", "{\"dispensed\":60}");
        DeviceCommandExecutionService commandService = new DeviceCommandExecutionService(repo, mockAdapter);

        var mailboxExec = Executors.newFixedThreadPool(2);
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
             var pool = new WorkerPool(2, 8, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService workerService = new WorkerExecutionService(repo, lifecycle, pool, registry);

            LocalTaskDispatchService dispatchService = new LocalTaskDispatchService(
                    repo,
                    workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-cap-" + node.key() + "-" + tenant,
                            2,
                            "mock_dispense".equals(node.key()) ? "DEVICE" : "PURE",
                            "mock_dispense".equals(node.key()) ? "device-q" : "pure-q",
                            Duration.ofSeconds(60),
                            "{\"node\":\"" + node.key() + "\"}"),
                    node -> switch (node.key()) {
                        case "pure_calc" -> Optional.of((req, ctx) ->
                                WorkerTaskHandler.TaskResult.of("out", "{\"ratio\":0.85}", Map.of("ratio", "0.85")));
                        case "mock_dispense" -> Optional.of((req, ctx) -> {
                            UUID cmdId = ctx.createDeviceCommand(
                                    "mock-pipettor-1", "Dispense", "QUERYABLE", "{\"volumeUl\":60}");
                            deviceCommandId.set(cmdId);
                            mockCommandCreated.countDown();
                            assertTrue(mockCommandFinished.await(5, TimeUnit.SECONDS));
                            return WorkerTaskHandler.TaskResult.of(
                                    "out", "{\"dispensed\":60}", Map.of("dispensed", "60"));
                        });
                        default -> Optional.empty();
                    });

            // 1. Start Run -> GraphCoordinator advances start -> split -> 2 READY task tokens.
            lifecycle.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

            // 2. LocalTaskDispatchService reserves + dispatches BOTH ready tasks and delivers WORKER_DISPATCH outbox.
            var reserved = dispatchService.dispatchReadyTasks(tenant, run.runId(), 10);
            assertEquals(2, reserved.dispatchedAttemptIds().size());
            assertEquals(0, reserved.capacityDeferred());
            assertFalse(reserved.gateClosed());

            var delivered = dispatchService.deliverPendingWorkerDispatches(tenant, 10, "g2b-worker");
            assertEquals(2, delivered.submitted().size());
            assertEquals(0, delivered.requeuedOnBackpressure());
            assertEquals(0, delivered.deadLetteredStale());

            // 3. Deliver DEVICE_COMMAND outbox via DeviceCommandExecutionService (Mock adapter + durable Inbox).
            assertTrue(mockCommandCreated.await(5, TimeUnit.SECONDS));
            var cmdDelivery = commandService.deliverPendingCommands(tenant, 10, "g2b-cmd-disp");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.COMPLETED_SYNC,
                    cmdDelivery.outcomes().get(deviceCommandId.get()));
            mockCommandFinished.countDown();

            // 4. Both Workers settle SUCCEEDED and drive GraphCoordinator through parallel join + XOR -> ok_end!
            for (var fut : delivered.submitted().values()) {
                var out = fut.get(5, TimeUnit.SECONDS);
                assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, out.status());
                if (out.wakeupScheduled()) {
                    out.graphAdvance().get(5, TimeUnit.SECONDS);
                }
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (lifecycle.activeMailboxCount() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, lifecycle.activeMailboxCount());

            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND status='SUCCEEDED' AND control_gate='CLOSED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND node_key='ok_end' AND token_state='CONSUMED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + deviceCommandId.get() + "' AND command_state='SUCCEEDED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND receipt_state='PROCESSED'"));
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND outbox_state IN ('PENDING','SENDING')"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void capacityExhaustionAndWorkerPoolBackpressureDeferSafelyWithoutLosingTasks() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-bp");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:parallelGateway id="split" gatewayDirection="Diverging"/>
                    <bpmn:task id="t1" protocolName="Op" resource="1"/>
                    <bpmn:task id="t2" protocolName="Op" resource="1"/>
                    <bpmn:parallelGateway id="join" gatewayDirection="Converging"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="split"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="split" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="split" targetRef="t2"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="t1" targetRef="join"/>
                    <bpmn:sequenceFlow id="f5" sourceRef="t2" targetRef="join"/>
                    <bpmn:sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g2b-bp-" + tenant, "1", xml, Map.of()));
        var run = repo.createRun(tenant, "g2b-bp-" + UUID.randomUUID(), "CREATE", "{}",
                "g2b-bp-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");

        // 1. Capacity limit = 1 on shared capacity key: first task dispatches, second stays READY!
        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate closedGate = new SchedulingGate(); // closed initially to test WorkerPool backpressure!
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
             var closedPool = new WorkerPool(1, 1, 1, registry, closedGate)) {
            closedPool.start();
            WorkerExecutionService closedService = new WorkerExecutionService(repo, lifecycle, closedPool, registry);
            String sharedCap = "shared-cap-1-" + tenant;

            LocalTaskDispatchService closedDispatch = new LocalTaskDispatchService(
                    repo,
                    closedService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            sharedCap, 1, "PURE", "pure-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> WorkerTaskHandler.TaskResult.of("out", "{\"ok\":true}")));

            lifecycle.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

            var batch1 = closedDispatch.dispatchReadyTasks(tenant, run.runId(), 10);
            assertEquals(1, batch1.dispatchedAttemptIds().size(), "Only 1 task may reserve shared capacityLimit=1");
            assertEquals(1, batch1.capacityDeferred(), "Second task must be cleanly deferred without failing");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND token_state='READY'"));

            // 2. WorkerPool gate is closed -> deliverPendingWorkerDispatches requeues outbox back to PENDING safely!
            var bpDelivery = closedDispatch.deliverPendingWorkerDispatches(tenant, 10, "bp-w");
            assertEquals(0, bpDelivery.submitted().size());
            assertEquals(1, bpDelivery.requeuedOnBackpressure(),
                    "Backpressure / closed SchedulingGate must requeue WORKER_DISPATCH back to PENDING");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND message_kind='WORKER_DISPATCH' AND outbox_state='PENDING'"));

            // 3. Now open SchedulingGate on a running pool: task 1 executes and frees capacity, then task 2 completes Run!
            SchedulingGate openGate = SchedulingGateTestSupport.openGate();
            try (var openPool = new WorkerPool(1, 4, 1, registry, openGate)) {
                openPool.start();
                WorkerExecutionService openService = new WorkerExecutionService(repo, lifecycle, openPool, registry);
                LocalTaskDispatchService openDispatch = new LocalTaskDispatchService(
                        repo,
                        openService,
                        node -> new LocalTaskDispatchService.TaskDispatchSpec(
                                sharedCap, 1, "PURE", "pure-q", Duration.ofSeconds(60), "{}"),
                        node -> Optional.of((req, ctx) -> WorkerTaskHandler.TaskResult.of("out", "{\"ok\":true}")));

                var del1 = openDispatch.deliverPendingWorkerDispatches(tenant, 10, "ok-w");
                assertEquals(1, del1.submitted().size());
                var out1 = del1.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
                assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, out1.status());
                out1.graphAdvance().get(5, TimeUnit.SECONDS);

                // Capacity is now free -> deferred 2nd task reserves, dispatches, executes, and finishes Run SUCCEEDED!
                var batch2 = openDispatch.dispatchReadyTasks(tenant, run.runId(), 10);
                assertEquals(1, batch2.dispatchedAttemptIds().size());
                assertEquals(0, batch2.capacityDeferred());
                var del2 = openDispatch.deliverPendingWorkerDispatches(tenant, 10, "ok-w");
                assertEquals(1, del2.submitted().size());
                var out2 = del2.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
                assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, out2.status());
                out2.graphAdvance().get(5, TimeUnit.SECONDS);

                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                        + tenant + "' AND run_id='" + run.runId() + "' AND status='SUCCEEDED' AND control_gate='CLOSED'"));
            }
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void mockDeviceCommandTimeoutEscalatesManualRequiredAndInboxHandlesReplayConflictAndReconciliation() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-cmd");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="dev_op" protocolName="Heat" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="dev_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="dev_op" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g2b-cmd-" + tenant, "1", xml, Map.of()));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
             var pool = new WorkerPool(2, 8, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService workerService = new WorkerExecutionService(repo, lifecycle, pool, registry);

            // Part 1: Async Mock Adapter + Duplicate Replay + Protocol Violation on Conflicting Inbox Payload.
            var runAsync = repo.createRun(tenant, "g2b-async-" + UUID.randomUUID(), "CREATE", "{}",
                    "g2b-cmd-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            CountDownLatch asyncCmdCreated = new CountDownLatch(1);
            CountDownLatch asyncInboxApplied = new CountDownLatch(1);
            AtomicReference<UUID> asyncCmdId = new AtomicReference<>();

            DeviceCommandExecutionService asyncCmdService = new DeviceCommandExecutionService(
                    repo, env -> DeviceCommandAdapter.SendResult.acceptedAsync());
            LocalTaskDispatchService asyncDispatch = new LocalTaskDispatchService(
                    repo, workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-async-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand("mock-heater-1", "Heat", "QUERYABLE", "{\"c\":65}");
                        asyncCmdId.set(cmdId);
                        asyncCmdCreated.countDown();
                        assertTrue(asyncInboxApplied.await(5, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"heated\":true}");
                    }));

            lifecycle.startRun(tenant, runAsync.runId()).get(5, TimeUnit.SECONDS);
            assertEquals(1, asyncDispatch.dispatchReadyTasks(tenant, runAsync.runId(), 4).dispatchedAttemptIds().size());
            var asyncSub = asyncDispatch.deliverPendingWorkerDispatches(tenant, 4, "w-async");
            assertEquals(1, asyncSub.submitted().size());
            assertTrue(asyncCmdCreated.await(5, TimeUnit.SECONDS));

            var asyncSend = asyncCmdService.deliverPendingCommands(tenant, 4, "disp-async");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC,
                    asyncSend.outcomes().get(asyncCmdId.get()));

            // Apply async device SUCCEEDED event via durable Inbox -> APPLIED.
            String msgId = "heater-msg-" + UUID.randomUUID();
            var ing1 = asyncCmdService.ingestDeviceEvent(tenant, runAsync.runId(), "mock-heater-1",
                    msgId, "DEVICE_COMMAND_RESULT", asyncCmdId.get(), "SUCCEEDED", "seq-1", "{\"c\":65}");
            assertEquals(DeviceCommandExecutionService.InboxStatus.APPLIED, ing1.status());

            // Exact duplicate replay -> DUPLICATE_REPLAY (idempotent).
            var ingReplay = asyncCmdService.ingestDeviceEvent(tenant, runAsync.runId(), "mock-heater-1",
                    msgId, "DEVICE_COMMAND_RESULT", asyncCmdId.get(), "SUCCEEDED", "seq-1", "{\"c\":65}");
            assertEquals(DeviceCommandExecutionService.InboxStatus.DUPLICATE_REPLAY, ingReplay.status());
            assertEquals(ing1.inboxId(), ingReplay.inboxId());

            // Conflicting payload under same source_message_id -> PROTOCOL_VIOLATION recorded!
            var ingConflict = asyncCmdService.ingestDeviceEvent(tenant, runAsync.runId(), "mock-heater-1",
                    msgId, "DEVICE_COMMAND_RESULT", asyncCmdId.get(), "FAILED", "seq-1", "{\"c\":99}");
            assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION, ingConflict.status());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='"
                    + tenant + "' AND inbox_id='" + ing1.inboxId() + "'"));

            asyncInboxApplied.countDown();
            var asyncOutcome = asyncSub.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, asyncOutcome.status());
            asyncOutcome.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runAsync.runId() + "' AND status='SUCCEEDED'"));

            // Part 2: Transport Timeout after SEND_STARTED -> UNKNOWN + MANUAL_REQUIRED + T21 Reconciliation.
            var runTimeout = repo.createRun(tenant, "g2b-to-" + UUID.randomUUID(), "CREATE", "{}",
                    "g2b-cmd-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            CountDownLatch toCmdCreated = new CountDownLatch(1);
            CountDownLatch toMarkedUnknown = new CountDownLatch(1);
            AtomicReference<UUID> toCmdId = new AtomicReference<>();

            DeviceCommandExecutionService timeoutCmdService = new DeviceCommandExecutionService(
                    repo, env -> { throw new java.net.SocketTimeoutException("mock transport timeout"); });
            LocalTaskDispatchService timeoutDispatch = new LocalTaskDispatchService(
                    repo, workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-to-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand("mock-heater-2", "Heat", "QUERYABLE", "{\"c\":70}");
                        toCmdId.set(cmdId);
                        toCmdCreated.countDown();
                        assertTrue(toMarkedUnknown.await(5, TimeUnit.SECONDS));
                        // Even if a buggy handler tries to return success, completeAttempt rejects & escalates MANUAL_REQUIRED!
                        return WorkerTaskHandler.TaskResult.of("out", "{\"pretendOk\":true}");
                    }));

            lifecycle.startRun(tenant, runTimeout.runId()).get(5, TimeUnit.SECONDS);
            assertEquals(1, timeoutDispatch.dispatchReadyTasks(tenant, runTimeout.runId(), 4).dispatchedAttemptIds().size());
            var toSub = timeoutDispatch.deliverPendingWorkerDispatches(tenant, 4, "w-to");
            assertEquals(1, toSub.submitted().size());
            assertTrue(toCmdCreated.await(5, TimeUnit.SECONDS));

            var toSend = timeoutCmdService.deliverPendingCommands(tenant, 4, "disp-to");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.MARKED_UNKNOWN,
                    toSend.outcomes().get(toCmdId.get()));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + toCmdId.get() + "' AND command_state='UNKNOWN'"));
            toMarkedUnknown.countDown();

            var toOutcome = toSub.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.MANUAL_REQUIRED, toOutcome.status());
            assertNotNull(toOutcome.interventionId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runTimeout.runId() + "' AND status='MANUAL_REQUIRED'"));

            // Operator explicitly reconciles the UNKNOWN command via T21 external evidence.
            assertTrue(timeoutCmdService.reconcileUnknownCommand(tenant, toCmdId.get(),
                    "SUCCEEDED", "dev-seq-verified-77", "{\"verifiedBy\":\"operator\"}", "operator-1"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + toCmdId.get() + "' AND command_state='SUCCEEDED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_audit WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runTimeout.runId() + "' AND action='UNKNOWN_COMMAND_RESOLVED'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void preSendCrashRecoveryRequeuesSafelyAndPauseDrainConvergesPreservingReadyToken() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-rec");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="dev_step" protocolName="Mix" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="dev_step"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="dev_step" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g2b-rec-" + tenant, "1", xml, Map.of()));

        // 1. Pre-send crash while DEVICE_COMMAND outbox is SENDING (command still CREATED) -> safe requeue & send!
        var runRec = repo.createRun(tenant, "g2b-rec-" + UUID.randomUUID(), "CREATE", "{}",
                "g2b-rec-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
             var pool = new WorkerPool(2, 8, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService workerService = new WorkerExecutionService(repo, lifecycle, pool, registry);
            CountDownLatch cmdCreated = new CountDownLatch(1);
            CountDownLatch cmdDelivered = new CountDownLatch(1);
            AtomicReference<UUID> cmdIdRef = new AtomicReference<>();

            LocalTaskDispatchService dispatch = new LocalTaskDispatchService(
                    repo, workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-rec-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand("mock-mixer-1", "Mix", "QUERYABLE", "{\"rpm\":300}");
                        cmdIdRef.set(cmdId);
                        cmdCreated.countDown();
                        assertTrue(cmdDelivered.await(5, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"mixed\":true}");
                    }));

            lifecycle.startRun(tenant, runRec.runId()).get(5, TimeUnit.SECONDS);
            assertEquals(1, dispatch.dispatchReadyTasks(tenant, runRec.runId(), 4).dispatchedAttemptIds().size());
            var sub = dispatch.deliverPendingWorkerDispatches(tenant, 4, "w-rec");
            assertTrue(cmdCreated.await(5, TimeUnit.SECONDS));

            // Simulate host crash right after claiming DEVICE_COMMAND outbox (SENDING) BEFORE markCommandSendStarted!
            var claimed = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 4);
            assertEquals(1, claimed.size());
            var outboxRecovery = repo.recoverOutboxAfterRestart();
            assertEquals(1, outboxRecovery.safelyRequeued(),
                    "Pre-send SENDING DEVICE_COMMAND (command_state=CREATED) must be safely requeued");
            assertEquals(0, outboxRecovery.leftUncertain());

            // Now DeviceCommandExecutionService picks up the requeued outbox and completes cleanly!
            AtomicInteger adapterCalls = new AtomicInteger();
            DeviceCommandExecutionService cmdService = new DeviceCommandExecutionService(repo, env -> {
                adapterCalls.incrementAndGet();
                return DeviceCommandAdapter.SendResult.completedImmediately(
                        "mix-msg-" + env.commandId(), "seq-9", "{\"mixed\":true}");
            });
            var deliv = cmdService.deliverPendingCommands(tenant, 4, "disp-rec");
            assertEquals(1, adapterCalls.get());
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.COMPLETED_SYNC,
                    deliv.outcomes().get(cmdIdRef.get()));
            cmdDelivered.countDown();

            var recOutcome = sub.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, recOutcome.status());
            recOutcome.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runRec.runId() + "' AND status='SUCCEEDED'"));

            // 2. PAUSE Drain with dispatched-but-unaccepted Attempt -> converges cleanly to PAUSED preserving READY token!
            var runPause = repo.createRun(tenant, "g2b-pause-" + UUID.randomUUID(), "CREATE", "{}",
                    "g2b-rec-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            lifecycle.startRun(tenant, runPause.runId()).get(5, TimeUnit.SECONDS);
            var pauseDisp = dispatch.dispatchReadyTasks(tenant, runPause.runId(), 4);
            assertEquals(1, pauseDisp.dispatchedAttemptIds().size());
            UUID pauseDrainId = repo.requestDrain(tenant, runPause.runId(), "PAUSE");

            // Deliver WORKER_DISPATCH outbox while Run is PAUSE_REQUESTED -> worker rejects before start & restores token READY.
            var pauseSub = dispatch.deliverPendingWorkerDispatches(tenant, 4, "w-pause");
            assertEquals(1, pauseSub.submitted().size());
            var pauseOut = pauseSub.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, pauseOut.status());
            assertEquals("ABORTED_FOR_PAUSE", pauseOut.runOutcome());

            // Deliver DRAIN_REQUEST outbox and finalize PAUSE Drain.
            var drainOutbox = repo.beginOutboxSendForKind(tenant, "DRAIN_REQUEST", 4).stream()
                    .filter(m -> m.dedupKey().equals("drain:" + pauseDrainId)).findFirst().orElseThrow();
            assertTrue(repo.markOutboxDelivered(tenant, drainOutbox.outboxId()));
            assertTrue(repo.tryFinalizeDrain(tenant, runPause.runId(), pauseDrainId, "PAUSED"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runPause.runId() + "' AND status='PAUSED' AND control_gate='PAUSE'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runPause.runId() + "' AND node_key='dev_step' AND token_state='READY'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }
}
