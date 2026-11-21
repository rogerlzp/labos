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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

            // Wrong device source spoofing the same commandId is rejected; duplicate SUCCEEDED with new msgId rejected by state machine.
            assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                    asyncCmdService.ingestDeviceEvent(tenant, runAsync.runId(), "rogue-device-9",
                            "rogue-msg-" + UUID.randomUUID(), "DEVICE_COMMAND_RESULT",
                            asyncCmdId.get(), "SUCCEEDED", "seq-2", "{\"c\":65}"));
            var lateRejected = asyncCmdService.ingestDeviceEvent(tenant, runAsync.runId(), "mock-heater-1",
                    "late-msg-" + UUID.randomUUID(), "DEVICE_COMMAND_RESULT",
                    asyncCmdId.get(), "SUCCEEDED", "seq-3", "{}");
            assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION, lateRejected.status());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                    + tenant + "' AND inbox_id='" + lateRejected.inboxId() + "' AND receipt_state='REJECTED'"));

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

            // P1-03: While UNKNOWN/MANUAL_REQUIRED, late ordinary ACK cannot wash away UNKNOWN,
            // and dispatching a new Attempt to bypass manual reconciliation is rejected (gate CLOSED).
            var lateTimeoutEvent = timeoutCmdService.ingestDeviceEvent(tenant, runTimeout.runId(), "mock-heater-2",
                    "late-ack-" + UUID.randomUUID(), "DEVICE_COMMAND_RESULT",
                    toCmdId.get(), "SUCCEEDED", "seq-late", "{}");
            assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION, lateTimeoutEvent.status());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                    + tenant + "' AND inbox_id='" + lateTimeoutEvent.inboxId() + "' AND receipt_state='REJECTED'"));
            assertTrue(timeoutDispatch.dispatchReadyTasks(tenant, runTimeout.runId(), 4).gateClosed());

            // Operator explicitly reconciles the UNKNOWN command via T21 external evidence.
            assertTrue(timeoutCmdService.reconcileUnknownCommand(tenant, toCmdId.get(),
                    "SUCCEEDED", "dev-seq-verified-77", "{\"verifiedBy\":\"operator\"}", "operator-1"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + toCmdId.get() + "' AND command_state='SUCCEEDED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_audit WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runTimeout.runId() + "' AND action='UNKNOWN_COMMAND_RESOLVED'"));

            // Part 3 (P1-04): Concurrent SUCCEEDED Inbox arriving inside adapter.send() is NEVER overwritten by late exception!
            var runRace = repo.createRun(tenant, "g2b-race-" + UUID.randomUUID(), "CREATE", "{}",
                    "g2b-cmd-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            CountDownLatch raceCmdCreated = new CountDownLatch(1);
            CountDownLatch raceSendFinished = new CountDownLatch(1);
            AtomicReference<UUID> raceCmdId = new AtomicReference<>();
            AtomicReference<DeviceCommandExecutionService> raceCmdServiceRef = new AtomicReference<>();
            DeviceCommandExecutionService raceCmdService = new DeviceCommandExecutionService(repo, env -> {
                // Simulate device executing & delivering SUCCEEDED Inbox BEFORE adapter.send() fails with late timeout!
                raceCmdServiceRef.get().ingestDeviceEvent(tenant, runRace.runId(), env.deviceId(),
                        "race-msg-" + env.commandId(), "DEVICE_COMMAND_RESULT",
                        env.commandId(), "SUCCEEDED", "seq-race", "{\"c\":72}");
                throw new java.net.SocketTimeoutException("late transport error after device SUCCEEDED");
            });
            raceCmdServiceRef.set(raceCmdService);
            LocalTaskDispatchService raceDispatch = new LocalTaskDispatchService(
                    repo, workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-race-cap-" + tenant, 1, "DEVICE", "dev-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        UUID cmdId = ctx.createDeviceCommand("mock-heater-3", "Heat", "QUERYABLE", "{\"c\":72}");
                        raceCmdId.set(cmdId);
                        raceCmdCreated.countDown();
                        assertTrue(raceSendFinished.await(5, TimeUnit.SECONDS));
                        return WorkerTaskHandler.TaskResult.of("out", "{\"heated\":true}");
                    }));

            lifecycle.startRun(tenant, runRace.runId()).get(5, TimeUnit.SECONDS);
            assertEquals(1, raceDispatch.dispatchReadyTasks(tenant, runRace.runId(), 4).dispatchedAttemptIds().size());
            var raceSub = raceDispatch.deliverPendingWorkerDispatches(tenant, 4, "w-race");
            assertTrue(raceCmdCreated.await(5, TimeUnit.SECONDS));

            var raceSend = raceCmdService.deliverPendingCommands(tenant, 4, "disp-race");
            assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.COMPLETED_SYNC,
                    raceSend.outcomes().get(raceCmdId.get()),
                    "Late adapter exception after concurrent SUCCEEDED Inbox must NOT overwrite SUCCEEDED with UNKNOWN");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + raceCmdId.get() + "' AND command_state='SUCCEEDED'"));
            raceSendFinished.countDown();
            var raceOutcome = raceSub.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, raceOutcome.status());
            raceOutcome.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runRace.runId() + "' AND status='SUCCEEDED'"));
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
            assertTrue(outboxRecovery.safelyRequeued() >= 1,
                    "Pre-send SENDING DEVICE_COMMAND (command_state=CREATED) must be safely requeued");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runRec.runId()
                    + "' AND message_kind='DEVICE_COMMAND' AND outbox_state='PENDING'"));
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runRec.runId()
                    + "' AND message_kind='DEVICE_COMMAND' AND outbox_state='SENDING'"));

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

            // 3 (P1-01): Crash between WORKER_DISPATCH DELIVERED and WorkerPool.submit() -> recovered once, no lost/duplicate task!
            var runDeliveredCrash = repo.createRun(tenant, "g2b-delcrash-" + UUID.randomUUID(), "CREATE", "{}",
                    "g2b-rec-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            AtomicInteger deliveredCrashExecutions = new AtomicInteger();
            LocalTaskDispatchService pureDispatch = new LocalTaskDispatchService(
                    repo, workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-delcrash-cap-" + tenant, 1, "PURE", "pure-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.of((req, ctx) -> {
                        deliveredCrashExecutions.incrementAndGet();
                        return WorkerTaskHandler.TaskResult.of("out", "{\"recovered\":true}");
                    }));
            lifecycle.startRun(tenant, runDeliveredCrash.runId()).get(5, TimeUnit.SECONDS);
            var firstDisp = pureDispatch.dispatchReadyTasks(tenant, runDeliveredCrash.runId(), 4);
            assertEquals(1, firstDisp.dispatchedAttemptIds().size());
            UUID crashedAttemptId = firstDisp.dispatchedAttemptIds().get(0);
            // Simulate marking WORKER_DISPATCH outbox DELIVERED then host crashing BEFORE WorkerPool.submit()!
            var wdOutbox = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 4).stream()
                    .filter(m -> m.dedupKey().equals("dispatch:" + crashedAttemptId)).findFirst().orElseThrow();
            assertTrue(repo.markOutboxDelivered(tenant, wdOutbox.outboxId()));

            // On restart, WorkerExecutionRecoveryService scans DISPATCHED Attempts regardless of DELIVERED outbox.
            var recRes = new WorkerExecutionRecoveryService(repo, new WorkerExecutionRegistry())
                    .recoverAtStartup(10, "restart-after-delivered-crash");
            assertTrue(recRes.safeToRetry() >= 1);
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + crashedAttemptId + "' AND attempt_state='ABANDONED_SAFE'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runDeliveredCrash.runId() + "' AND token_state='READY'"));

            // Re-dispatch executes the recovered task EXACTLY ONCE and finishes Run SUCCEEDED!
            assertEquals(1, pureDispatch.dispatchReadyTasks(tenant, runDeliveredCrash.runId(), 4).dispatchedAttemptIds().size());
            var redeliv = pureDispatch.deliverPendingWorkerDispatches(tenant, 4, "w-redeliv");
            assertEquals(1, redeliv.submitted().size());
            var redelivOut = redeliv.submitted().values().iterator().next().get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, redelivOut.status());
            redelivOut.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(1, deliveredCrashExecutions.get(), "Recovered task must execute exactly once without duplication");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runDeliveredCrash.runId() + "' AND status='SUCCEEDED'"));

            // 4 (P1-02): Crash AFTER markCommandSendStarted and BEFORE adapter.send() -> NEVER requeued, escalates MANUAL_REQUIRED!
            var runPostSendCrash = repo.createRun(tenant, "g2b-postsend-" + UUID.randomUUID(), "CREATE", "{}",
                    "g2b-rec-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                    plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
            lifecycle.startRun(tenant, runPostSendCrash.runId()).get(5, TimeUnit.SECONDS);
            var psRes = repo.reserveCapacity(tenant, runPostSendCrash.runId(), "g2b-ps-cap-" + tenant, 1,
                    "dev_step", "DEVICE", java.time.Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runPostSendCrash.runId(), psRes.attemptId(), "dev-q", "{}");
            var psWd = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 4).stream()
                    .filter(m -> m.dedupKey().equals("dispatch:" + psRes.attemptId())).findFirst().orElseThrow();
            assertTrue(repo.markOutboxDelivered(tenant, psWd.outboxId()));
            repo.acceptAttempt(tenant, runPostSendCrash.runId(), psRes.attemptId(), psRes.dispatchId(), "w-ps");
            repo.startAttempt(tenant, runPostSendCrash.runId(), psRes.attemptId(), psRes.dispatchId(), "w-ps");
            UUID psCmdId = repo.createCommandForAttempt(tenant, runPostSendCrash.runId(), psRes.attemptId(),
                    "w-ps", "mock-mixer-2", "Mix", "QUERYABLE", "{\"rpm\":400}");
            var psCmdOutbox = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 4).stream()
                    .filter(m -> m.dedupKey().equals("command:" + psCmdId)).findFirst().orElseThrow();
            assertTrue(repo.markCommandSendStarted(tenant, psCmdId, 0L, psCmdOutbox.outboxId(),
                    UUID.randomUUID(), 1, "disp-ps"));
            // Host crashes RIGHT HERE after SEND_STARTED committed and BEFORE adapter.send() runs!
            var postSendOutboxRec = repo.recoverOutboxAfterRestart();
            assertTrue(postSendOutboxRec.leftUncertain() >= 1);
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                    + tenant + "' AND outbox_id='" + psCmdOutbox.outboxId() + "' AND outbox_state='SENDING'"),
                    "Post-SEND_STARTED DEVICE_COMMAND outbox must NEVER be auto-requeued to PENDING");
            var psAttemptRec = repo.recoverInterruptedAttempt(tenant, runPostSendCrash.runId(), psRes.attemptId(), "restart-ps");
            assertEquals("MANUAL_REQUIRED", psAttemptRec.outcome());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                    + tenant + "' AND command_id='" + psCmdId + "' AND command_state='UNKNOWN'"));
            // Subsequent DeviceCommandExecutionService poll MUST NOT re-send the command.
            AtomicInteger postCrashAdapterCalls = new AtomicInteger();
            DeviceCommandExecutionService postCrashCmdSvc = new DeviceCommandExecutionService(repo, env -> {
                postCrashAdapterCalls.incrementAndGet();
                return DeviceCommandAdapter.SendResult.acceptedAsync();
            });
            postCrashCmdSvc.deliverPendingCommands(tenant, 10, "disp-after-crash");
            assertEquals(0, postCrashAdapterCalls.get(), "Post-SEND_STARTED command must never be auto-re-sent after restart");
            // Dead-letter this test's uncertain SENDING outbox so shared-DB tests in other classes stay isolated.
            assertTrue(repo.markOutboxDeadLetter(tenant, psCmdOutbox.outboxId()));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    private record StartedCommandFixture(UUID runId, UUID attemptId, UUID commandId, UUID outboxId) {}

    private StartedCommandFixture createStartedDeviceCommand(
            WorkflowRuntimeRepository repo, UUID tenant, GraphPlan plan, String planKey,
            String deviceId, boolean markSendStarted) {
        var run = repo.createRun(tenant, "fix-" + UUID.randomUUID(), "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");
        repo.startRun(tenant, run.runId());
        new GraphCoordinator(repo).advance(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "cap-" + UUID.randomUUID(), 1,
                "dev_step", "DEVICE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "dev-q", "{}");
        var wd = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 20).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, wd.outboxId()));
        repo.acceptAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-fix");
        repo.startAttempt(tenant, run.runId(), res.attemptId(), res.dispatchId(), "w-fix");
        UUID cmdId = repo.createCommandForAttempt(tenant, run.runId(), res.attemptId(),
                "w-fix", deviceId, "Mix", "QUERYABLE", "{\"rpm\":300}");
        UUID outboxId = null;
        if (markSendStarted) {
            var cmdOutbox = repo.beginOutboxSendForKind(tenant, "DEVICE_COMMAND", 20).stream()
                    .filter(m -> m.dedupKey().equals("command:" + cmdId)).findFirst().orElseThrow();
            outboxId = cmdOutbox.outboxId();
            assertTrue(repo.markCommandSendStarted(tenant, cmdId, 0L, outboxId,
                    UUID.randomUUID(), 1, "disp-fix"));
            assertTrue(repo.markOutboxDelivered(tenant, outboxId));
        }
        return new StartedCommandFixture(run.runId(), res.attemptId(), cmdId, outboxId);
    }

    @Test
    void inboxMessageTypeBindingAndProtocolIntegrityEnforcedAtServiceAndRepositoryBoundaries() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-proto");
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
        String planKey = "g2b-proto-" + tenant;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
        var fix = createStartedDeviceCommand(repo, tenant, plan, planKey, "mock-proto-1", true);
        DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(
                repo, env -> DeviceCommandAdapter.SendResult.acceptedAsync());

        // 1. HEARTBEAT MUST NEVER mutate command_ledger — rejected at Service AND Repository boundaries.
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        "hb-svc-" + UUID.randomUUID(), "HEARTBEAT",
                        fix.commandId(), "SUCCEEDED", "seq-hb", "{}"));
        String sharedMsgId = "proto-msg-" + UUID.randomUUID();
        String rawPayload = "{\"commandId\":\"" + fix.commandId()
                + "\",\"deviceId\":\"mock-proto-1\",\"state\":\"SUCCEEDED\"}";
        var hbReceipt = repo.receiveInbox(tenant, "mock-proto-1", sharedMsgId, "HEARTBEAT", fix.runId(), rawPayload);
        assertTrue(hbReceipt.inserted());
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEvent(tenant, fix.runId(), hbReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-hb", "{}"),
                "Repository persistence boundary must reject HEARTBEAT even if inbox_receipt exists");
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SEND_STARTED'"));

        // 2. Reusing same (source, source_message_id, payload) with DIFFERENT messageType -> PROTOCOL_VIOLATION!
        var conflictType = repo.receiveInbox(tenant, "mock-proto-1", sharedMsgId, "COMMAND_RESULT", fix.runId(), rawPayload);
        assertFalse(conflictType.inserted());
        assertTrue(conflictType.payloadConflict(),
                "Same (source, messageId, payload) with changed messageType must trigger PROTOCOL_VIOLATION");
        assertEquals(hbReceipt.inboxId(), conflictType.inboxId());
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='"
                + tenant + "' AND inbox_id='" + hbReceipt.inboxId() + "'"));

        var svcConflict = cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                sharedMsgId, "COMMAND_RESULT", fix.commandId(), "SUCCEEDED", "seq-hb", "{}");
        assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION, svcConflict.status());
        assertEquals(2, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='"
                + tenant + "' AND inbox_id='" + hbReceipt.inboxId() + "'"));

        // 3. Mismatched messageType <-> targetState and unknown messageType rejected at BOTH layers.
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        "bad-ack-" + UUID.randomUUID(), "RECEIVED_ACK",
                        fix.commandId(), "SUCCEEDED", "seq-1", "{}"));
        var badAckReceipt = repo.receiveInbox(tenant, "mock-proto-1", "raw-ack-" + UUID.randomUUID(),
                "RECEIVED_ACK", fix.runId(), rawPayload);
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEvent(tenant, fix.runId(), badAckReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-1", "{}"));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        "unk-" + UUID.randomUUID(), "UNKNOWN_EVENT_KIND",
                        fix.commandId(), "SUCCEEDED", "seq-1", "{}"));
        // Repository boundary also rejects spoofed deviceId inside message_payload!
        var spoofReceipt = repo.receiveInbox(tenant, "mock-proto-1", "spoof-" + UUID.randomUUID(),
                "COMMAND_RESULT", fix.runId(),
                "{\"commandId\":\"" + fix.commandId() + "\",\"deviceId\":\"evil-device\",\"state\":\"SUCCEEDED\"}");
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEvent(tenant, fix.runId(), spoofReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-evil", "{}"));

        // 4. Strict allowlist happy path: RECEIVED_ACK -> ACKNOWLEDGED, EXECUTING -> RUNNING, COMMAND_RESULT -> SUCCEEDED.
        assertEquals(DeviceCommandExecutionService.InboxStatus.APPLIED,
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        "ack-" + UUID.randomUUID(), "RECEIVED_ACK",
                        fix.commandId(), "ACKNOWLEDGED", "seq-1", "{}").status());
        String execMsgId = "exec-" + UUID.randomUUID();
        assertEquals(DeviceCommandExecutionService.InboxStatus.APPLIED,
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        execMsgId, "EXECUTING",
                        fix.commandId(), "RUNNING", "seq-2", "{\"pct\":50}").status());
        // Same messageId + targetState + outcome but changed messageType (EXECUTING -> PROGRESS) => PROTOCOL_VIOLATION!
        assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION,
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        execMsgId, "PROGRESS",
                        fix.commandId(), "RUNNING", "seq-2", "{\"pct\":50}").status());
        assertEquals(DeviceCommandExecutionService.InboxStatus.APPLIED,
                cmdSvc.ingestDeviceEvent(tenant, fix.runId(), "mock-proto-1",
                        "res-" + UUID.randomUUID(), "COMMAND_RESULT",
                        fix.commandId(), "SUCCEEDED", "seq-3", "{\"done\":true}").status());
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SUCCEEDED'"));

        // Cleanly complete attempt so shared-DB tests see zero leaked active attempts.
        repo.completeAttempt(tenant, fix.runId(), fix.attemptId(), "out", "{\"done\":true}");
    }

    @Test
    void concurrentTwoThreadInboxAndTimeoutRaceSerializesCleanlyWithoutOverwritingSuccess() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-race2t");
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
        String planKey = "g2b-race2t-" + tenant;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));

        var exec = Executors.newFixedThreadPool(2);
        try {
            for (int iter = 0; iter < 5; iter++) {
                final int round = iter;
                String deviceId = "mock-race-dev-" + round;
                var fix = createStartedDeviceCommand(repo, tenant, plan, planKey, deviceId, false);
                CountDownLatch sendStartedCommitted = new CountDownLatch(1);
                CyclicBarrier raceStart = new CyclicBarrier(2);

                DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(repo, env -> {
                    sendStartedCommitted.countDown();
                    try {
                        raceStart.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    throw new java.net.SocketTimeoutException("concurrent transport timeout iter=" + env.commandId());
                });

                Future<DeviceCommandExecutionService.CommandDeliverySummary> deliveryFuture = exec.submit(
                        () -> cmdSvc.deliverPendingCommands(tenant, 4, "disp-race-" + round));
                Future<Object> inboxFuture = exec.submit(() -> {
                    assertTrue(sendStartedCommitted.await(5, TimeUnit.SECONDS));
                    raceStart.await(5, TimeUnit.SECONDS);
                    try {
                        return cmdSvc.ingestDeviceEvent(tenant, fix.runId(), deviceId,
                                "race-inbox-" + fix.commandId(), "COMMAND_RESULT",
                                fix.commandId(), "SUCCEEDED", "seq-race", "{\"ok\":true}");
                    } catch (WorkflowRuntimeRepository.InvalidTransitionException rejectedAfterUnknown) {
                        return rejectedAfterUnknown;
                    }
                });

                var delivery = deliveryFuture.get(10, TimeUnit.SECONDS);
                Object inboxOutcome = inboxFuture.get(10, TimeUnit.SECONDS);
                var cmd = repo.loadCommand(tenant, fix.commandId()).orElseThrow();
                var deliveryStatus = delivery.outcomes().get(fix.commandId());

                if ("SUCCEEDED".equals(cmd.commandState())) {
                    assertInstanceOf(DeviceCommandExecutionService.InboxIngestionOutcome.class, inboxOutcome);
                    assertEquals(DeviceCommandExecutionService.InboxStatus.APPLIED,
                            ((DeviceCommandExecutionService.InboxIngestionOutcome) inboxOutcome).status());
                    assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.COMPLETED_SYNC, deliveryStatus,
                            "Concurrent timeout MUST NOT overwrite SUCCEEDED with UNKNOWN");
                    assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                            + tenant + "' AND attempt_id='" + fix.attemptId() + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));
                    assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                            + tenant + "' AND attempt_id='" + fix.attemptId() + "' AND fact_type='SIDE_EFFECT_UNKNOWN'"));
                    repo.completeAttempt(tenant, fix.runId(), fix.attemptId(), "out", "{\"ok\":true}");
                } else if ("UNKNOWN".equals(cmd.commandState())) {
                    assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.MARKED_UNKNOWN, deliveryStatus);
                    assertInstanceOf(DeviceCommandExecutionService.InboxIngestionOutcome.class, inboxOutcome,
                            "Once UNKNOWN commits first, late ordinary SUCCEEDED must be fail-closed rejected");
                    var rejectedInbox = (DeviceCommandExecutionService.InboxIngestionOutcome) inboxOutcome;
                    assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION, rejectedInbox.status());
                    assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                            + tenant + "' AND inbox_id='" + rejectedInbox.inboxId() + "' AND receipt_state='REJECTED'"));
                    assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                            + tenant + "' AND attempt_id='" + fix.attemptId() + "' AND fact_type='SIDE_EFFECT_UNKNOWN'"));
                    assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                            + tenant + "' AND attempt_id='" + fix.attemptId() + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));
                    var failRes = repo.failAttempt(tenant, fix.runId(), fix.attemptId(),
                            "{\"reason\":\"race-iter-" + iter + "\"}", true);
                    assertEquals("MANUAL_REQUIRED", failRes.outcome());
                } else {
                    fail("Unexpected command state after concurrent race: " + cmd.commandState());
                }
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                        + tenant + "' AND run_id='" + fix.runId() + "' AND outbox_state IN ('PENDING','SENDING')"));
            }
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void concurrentTwoThreadDuplicateInboxDeliveryIsIdempotentWithoutException() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-dup2t");
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
        String planKey = "g2b-dup2t-" + tenant;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
        DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(
                repo, env -> DeviceCommandAdapter.SendResult.acceptedAsync());

        var exec = Executors.newFixedThreadPool(2);
        try {
            for (int iter = 0; iter < 5; iter++) {
                String deviceId = "mock-dup-dev-" + iter;
                var fix = createStartedDeviceCommand(repo, tenant, plan, planKey, deviceId, true);
                String duplicateMsgId = "dup-2t-" + fix.commandId();
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<DeviceCommandExecutionService.InboxIngestionOutcome> f1 = exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return cmdSvc.ingestDeviceEvent(tenant, fix.runId(), deviceId, duplicateMsgId,
                            "COMMAND_RESULT", fix.commandId(), "SUCCEEDED", "seq-dup", "{\"ok\":true}");
                });
                Future<DeviceCommandExecutionService.InboxIngestionOutcome> f2 = exec.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return cmdSvc.ingestDeviceEvent(tenant, fix.runId(), deviceId, duplicateMsgId,
                            "COMMAND_RESULT", fix.commandId(), "SUCCEEDED", "seq-dup", "{\"ok\":true}");
                });

                var o1 = f1.get(10, TimeUnit.SECONDS);
                var o2 = f2.get(10, TimeUnit.SECONDS);
                assertEquals(o1.inboxId(), o2.inboxId(), "Both concurrent deliveries must resolve to the same inboxId");
                List<DeviceCommandExecutionService.InboxStatus> statuses = List.of(o1.status(), o2.status());
                assertTrue(statuses.contains(DeviceCommandExecutionService.InboxStatus.APPLIED)
                                && statuses.contains(DeviceCommandExecutionService.InboxStatus.DUPLICATE_REPLAY),
                        "Expected exact {APPLIED, DUPLICATE_REPLAY} without exception, got: " + statuses);
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                        + tenant + "' AND inbox_id='" + o1.inboxId() + "' AND receipt_state='PROCESSED'"));
                assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id='"
                        + tenant + "' AND inbox_id='" + o1.inboxId() + "'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                        + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SUCCEEDED'"));
                assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                        + tenant + "' AND attempt_id='" + fix.attemptId() + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));

                repo.completeAttempt(tenant, fix.runId(), fix.attemptId(), "out", "{\"ok\":true}");
            }
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void deliverPendingCommandsIsolatesPerRowSyncFailureSoRemainingBatchContinues() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-iso");
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
        String planKey = "g2b-iso-" + tenant;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));

        var fix1 = createStartedDeviceCommand(repo, tenant, plan, planKey, "mock-iso-1", false);
        var fix2 = createStartedDeviceCommand(repo, tenant, plan, planKey, "mock-iso-2", false);

        // Row 1 returns invalid JSON outcome in COMPLETED_IMMEDIATELY (triggers RuntimeException inside ingestDeviceEvent);
        // Row 2 in the SAME batch MUST still be processed cleanly and not stranded in SENDING!
        DeviceCommandExecutionService cmdSvc = new DeviceCommandExecutionService(repo, env -> {
            if (env.commandId().equals(fix1.commandId())) {
                return DeviceCommandAdapter.SendResult.completedImmediately(
                        "iso-bad-" + env.commandId(), "seq-1", "{malformed-json");
            }
            return DeviceCommandAdapter.SendResult.completedImmediately(
                    "iso-ok-" + env.commandId(), "seq-2", "{\"ok\":true}");
        });

        var summary = cmdSvc.deliverPendingCommands(tenant, 10, "disp-iso");
        assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.MARKED_UNKNOWN,
                summary.outcomes().get(fix1.commandId()));
        assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.COMPLETED_SYNC,
                summary.outcomes().get(fix2.commandId()),
                "Second claimed command in batch must still succeed when first row fails sync ingestion");
        assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"
                + tenant + "' AND message_kind='DEVICE_COMMAND' AND outbox_state IN ('PENDING','SENDING')"));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix1.commandId() + "' AND command_state='UNKNOWN'"));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix2.commandId() + "' AND command_state='SUCCEEDED'"));

        // Clean up both active attempts so shared-DB tests stay isolated.
        assertEquals("MANUAL_REQUIRED", repo.failAttempt(tenant, fix1.runId(), fix1.attemptId(),
                "{\"reason\":\"iso-cleanup\"}", true).outcome());
        repo.completeAttempt(tenant, fix2.runId(), fix2.attemptId(), "out", "{\"ok\":true}");
    }

    @Test
    void repositoryApplyCommandEventEnforcesInboxSourceAndPayloadStateBinding() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-repo-bind");
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
        String planKey = "g2b-repo-bind-" + tenant;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", xml, Map.of()));
        var fix = createStartedDeviceCommand(repo, tenant, plan, planKey, "real-device-1", true);

        // P1-01: Wrong inbox_receipt.source with valid commandId & deviceId in payload MUST be rejected at Repository boundary!
        var attackerReceipt = repo.receiveInbox(tenant, "attacker-device", "atk-" + UUID.randomUUID(),
                "COMMAND_RESULT", fix.runId(),
                "{\"commandId\":\"" + fix.commandId() + "\",\"deviceId\":\"real-device-1\",\"state\":\"SUCCEEDED\"}");
        assertTrue(attackerReceipt.inserted());
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEvent(tenant, fix.runId(), attackerReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-atk", "{\"spoofed\":true}"),
                "Repository must reject Inbox whose source != command_ledger.device_id even when payload deviceId matches");
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SEND_STARTED'"));
        assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + tenant + "' AND attempt_id='" + fix.attemptId()
                + "' AND fact_type IN ('SIDE_EFFECT_COMPLETED','SIDE_EFFECT_UNKNOWN')"));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                + tenant + "' AND inbox_id='" + attackerReceipt.inboxId() + "' AND receipt_state='RECEIVED'"));

        // P1-02a: Contradictory Inbox payload state (FAILED) vs applyCommandEvent targetState (SUCCEEDED) MUST be rejected!
        var mismatchStateReceipt = repo.receiveInbox(tenant, "real-device-1", "mis-" + UUID.randomUUID(),
                "COMMAND_RESULT", fix.runId(),
                "{\"commandId\":\"" + fix.commandId() + "\",\"deviceId\":\"real-device-1\",\"state\":\"FAILED\"}");
        assertTrue(mismatchStateReceipt.inserted());
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEvent(tenant, fix.runId(), mismatchStateReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-mis", "{\"ok\":true}"),
                "Repository must reject contradictory targetState when Inbox payload declares FAILED");
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SEND_STARTED'"));
        assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + tenant + "' AND attempt_id='" + fix.attemptId()
                + "' AND fact_type IN ('SIDE_EFFECT_COMPLETED','SIDE_EFFECT_UNKNOWN')"));

        // P1-02b: Missing state field in Inbox payload MUST also fail closed!
        var missingStateReceipt = repo.receiveInbox(tenant, "real-device-1", "nostate-" + UUID.randomUUID(),
                "COMMAND_RESULT", fix.runId(),
                "{\"commandId\":\"" + fix.commandId() + "\",\"deviceId\":\"real-device-1\"}");
        assertTrue(missingStateReceipt.inserted());
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEvent(tenant, fix.runId(), missingStateReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-nostate", "{\"ok\":true}"),
                "Repository must reject Inbox payload missing declared state");
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SEND_STARTED'"));

        // G2-B P2: Contradictory deviceSequence or outcomeJson vs locked Inbox payload MUST be rejected!
        var seqAndOutcomeReceipt = repo.receiveInbox(tenant, "real-device-1", "p2-" + UUID.randomUUID(),
                "COMMAND_RESULT", fix.runId(),
                "{\"commandId\":\"" + fix.commandId()
                        + "\",\"deviceId\":\"real-device-1\",\"state\":\"SUCCEEDED\",\"deviceSequence\":\"seq-real\",\"outcome\":{\"temp\":65}}");
        assertTrue(seqAndOutcomeReceipt.inserted());
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEventOrReject(tenant, fix.runId(), seqAndOutcomeReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-tampered", "{\"temp\":65}"),
                "Caller deviceSequence mismatch must not permanently reject the persisted receipt");
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                + tenant + "' AND inbox_id='" + seqAndOutcomeReceipt.inboxId() + "' AND receipt_state='RECEIVED'"));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class, () ->
                repo.applyCommandEventOrReject(tenant, fix.runId(), seqAndOutcomeReceipt.inboxId(), fix.commandId(),
                        "SUCCEEDED", "seq-real", "{\"temp\":99}"),
                "Caller outcomeJson mismatch must not permanently reject the persisted receipt");
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                + tenant + "' AND inbox_id='" + seqAndOutcomeReceipt.inboxId() + "' AND receipt_state='RECEIVED'"));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id='"
                + tenant + "' AND run_id='" + fix.runId() + "' AND event_type='INBOX_REJECTED'"));

        // Matching source == device_id, payload state == targetState, deviceSequence and outcomeJson applies cleanly!
        assertTrue(repo.applyCommandEventOrReject(tenant, fix.runId(), seqAndOutcomeReceipt.inboxId(), fix.commandId(),
                "SUCCEEDED", "seq-real", "{\"temp\":65}"));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + fix.commandId() + "' AND command_state='SUCCEEDED'"));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + tenant + "' AND attempt_id='" + fix.attemptId() + "' AND fact_type='SIDE_EFFECT_COMPLETED'"));
        repo.completeAttempt(tenant, fix.runId(), fix.attemptId(), "out", "{\"ok\":true}");

        // Synchronous immediate failure result (COMPLETED_IMMEDIATELY_FAILED) records FAILED, never SUCCEEDED!
        var failFix = createStartedDeviceCommand(repo, tenant, plan, planKey, "sync-fail-dev", false);
        DeviceCommandExecutionService failSvc = new DeviceCommandExecutionService(repo, env ->
                DeviceCommandAdapter.SendResult.completedImmediatelyFailed(
                        "sync-fail-" + env.commandId(), "seq-fail", "{\"errorCode\":\"E_NO_TIP\"}"));
        var failSummary = failSvc.deliverPendingCommands(tenant, 4, "disp-sync-fail");
        assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.COMPLETED_FAILED_SYNC,
                failSummary.outcomes().get(failFix.commandId()));
        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"
                + tenant + "' AND command_id='" + failFix.commandId() + "' AND command_state='FAILED'"));
        assertEquals("MANUAL_REQUIRED", repo.failAttempt(tenant, failFix.runId(), failFix.attemptId(),
                "{\"errorCode\":\"E_NO_TIP\"}", true).outcome());
    }

    @Test
    void corruptPersistedGraphPlanIsTerminalizedWithoutPoisonPillLoop() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "g2b-poison");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        UUID definitionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.definition_snapshot"
                    + "(tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan) "
                    + "VALUES ('" + tenant + "','" + definitionId + "','poison','1',decode(repeat('aa',32),'hex'),'ci','{}'::jsonb)");
            st.executeUpdate("INSERT INTO workflow_runtime.workflow_run"
                    + "(tenant_id,run_id,definition_id,status,control_gate) "
                    + "VALUES ('" + tenant + "','" + runId + "','" + definitionId + "','RUNNING','OPEN')");
            st.executeUpdate("INSERT INTO workflow_runtime.execution_token"
                    + "(tenant_id,token_id,run_id,token_state,node_key) "
                    + "VALUES ('" + tenant + "','" + tokenId + "','" + runId + "','READY','node')");
        }

        var targetsBefore = repo.findRunnableWorkTargetsForTenant(tenant, 10);
        assertEquals(1, targetsBefore.size());
        assertEquals(runId, targetsBefore.get(0).runId());

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
             var pool = new WorkerPool(1, 4, 2, registry, gate)) {
            pool.start();
            WorkerExecutionService workerService = new WorkerExecutionService(repo, lifecycle, pool, registry);
            LocalTaskDispatchService dispatch = new LocalTaskDispatchService(
                    repo,
                    workerService,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec(
                            "g2b-poison-cap-" + tenant, 1, "PURE", "pure-q", Duration.ofSeconds(60), "{}"),
                    node -> Optional.empty());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> dispatch.dispatchReadyTasks(tenant, runId, 4));
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("Invalid persisted GraphPlan JSON"),
                    "Expected Invalid persisted GraphPlan JSON, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(runId.toString()), "Missing runId in message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(definitionId.toString()), "Missing definitionId in message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("definitionKey=poison"), "Missing definitionKey in message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("definitionVersion=1"), "Missing definitionVersion in message: " + ex.getMessage());
            assertInstanceOf(java.io.IOException.class, ex.getCause());
        } finally {
            mailboxExec.shutdownNow();
        }

        assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                + tenant + "' AND run_id='" + runId + "' AND status='FAILED' AND control_gate='CLOSED'"));
        assertTrue(repo.findRunnableWorkTargetsForTenant(tenant, 10).isEmpty(),
                "Terminalized corrupt-GraphPlan run must no longer appear in runnable discovery");
    }
}
