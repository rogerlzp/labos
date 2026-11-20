package com.labos.kernel.workflow.runtime.worker;

import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.host.SchedulingGate;
import com.labos.kernel.workflow.runtime.host.SchedulingGateTestSupport;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkerExecutionServiceTest {
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
                    + tenant + "','" + prefix + "-" + tenant + "','worker-service-it')");
        }
        return tenant;
    }

    private static void deliverDispatchOutbox(WorkflowRuntimeRepository repo, UUID tenant, UUID attemptId) {
        var msg = repo.beginOutboxSend(tenant, 20).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + attemptId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing WORKER_DISPATCH outbox row for " + attemptId));
        assertTrue(repo.markOutboxDelivered(tenant, msg.outboxId()));
    }

    private static void deliverDrainOutbox(WorkflowRuntimeRepository repo, UUID tenant, UUID drainId) {
        var msg = repo.beginOutboxSend(tenant, 20).stream()
                .filter(m -> m.dedupKey().equals("drain:" + drainId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing DRAIN_REQUEST outbox row for " + drainId));
        assertTrue(repo.markOutboxDelivered(tenant, msg.outboxId()));
    }

    private static WorkflowRuntimeRepository.CreatedRun createSingleTaskRun(
            WorkflowRuntimeRepository repo, UUID tenant, String prefix, String nodeKey) {
        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="%s" protocolName="Op" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="%s"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="%s" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(nodeKey, nodeKey, nodeKey);
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(prefix + "-" + tenant, "1", xml, Map.of()));
        return repo.createRun(tenant, prefix + "-" + UUID.randomUUID(), "CREATE", "{}",
                prefix + "-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", nodeKey);
    }

    @Test
    void pureLogicTaskExportsVariablesDrivesDownstreamXorAndCompletesRun() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-e2e");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="compute" protocolName="Score" resource="1"/>
                    <bpmn:exclusiveGateway id="xor"/>
                    <bpmn:endEvent id="pass_end"/>
                    <bpmn:endEvent id="fail_end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="compute"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="compute" targetRef="xor"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="xor" targetRef="pass_end">
                      <bpmn:conditionExpression>score >= 0.8</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="f4" sourceRef="xor" targetRef="fail_end">
                      <bpmn:conditionExpression>score &lt; 0.8</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("ws-e2e-" + tenant, "1", xml, Map.of()));
        var run = repo.createRun(tenant, "ws-e2e-" + UUID.randomUUID(), "CREATE", "{}",
                "ws-e2e-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), "{}", "start");

        var mailboxExec = Executors.newFixedThreadPool(2);
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 8);
             var pool = new WorkerPool(2, 8, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            lifecycle.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);
            var res = repo.reserveCapacity(tenant, run.runId(), "ws-cap-" + tenant, 2,
                    "compute", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "pure-q", "{\"input\":1}");
            deliverDispatchOutbox(repo, tenant, res.attemptId());

            AtomicReference<WorkerExecutionContext> leakedContext = new AtomicReference<>();
            var request = new WorkerTaskHandler.TaskRequest(
                    tenant, run.runId(), res.attemptId(), res.dispatchId(),
                    "worker-pure-1", "compute", "PURE", "{\"input\":1}");
            var outcome = service.submit(request, (req, ctx) -> {
                leakedContext.set(ctx);
                assertEquals(tenant, ctx.tenantId());
                assertEquals(run.runId(), ctx.runId());
                assertEquals(res.attemptId(), ctx.attemptId());
                assertEquals("compute", ctx.nodeKey());
                assertFalse(ctx.isCancellationRequested());
                return WorkerTaskHandler.TaskResult.of(
                        "result", "{\"score\":0.92}", Map.of("score", "0.92"));
            }).get(5, TimeUnit.SECONDS);

            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, outcome.status());
            assertTrue(outcome.wakeupScheduled());
            outcome.graphAdvance().get(5, TimeUnit.SECONDS);

            assertThrows(IllegalStateException.class, () ->
                    leakedContext.get().recordSideEffectStarted("late-op", "late-start", "{}"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (lifecycle.activeMailboxCount() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, lifecycle.activeMailboxCount());
            assertEquals(0, service.trackedCount());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND status='SUCCEEDED' AND control_gate='CLOSED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND node_key='pass_end' AND token_state='CONSUMED'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void mockSideEffectClosedDuringDrainCommitsSucceededWithoutFalseFailure() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-drain");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var run = repo.createRun(tenant, "ws-drain-" + UUID.randomUUID(), "CREATE", "{}",
                "ws-drain-" + tenant, "1", new byte[32], "ci", "{}", "{}", "mock-task");
        repo.startRun(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "ws-drain-cap-" + tenant, 1,
                "mock-task", "REVERSIBLE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "mock-q", "{}");
        deliverDispatchOutbox(repo, tenant, res.attemptId());

        CountDownLatch sideEffectStarted = new CountDownLatch(1);
        CountDownLatch drainRequested = new CountDownLatch(1);
        AtomicBoolean newSideEffectRejectedDuringDrain = new AtomicBoolean();

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(2, 4, 2, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);
            var request = new WorkerTaskHandler.TaskRequest(
                    tenant, run.runId(), res.attemptId(), res.dispatchId(),
                    "worker-mock-1", "mock-task", "REVERSIBLE", "{}");

            var future = service.submit(request, (req, ctx) -> {
                assertTrue(ctx.recordSideEffectStarted("mock-op-1", "op-1-start", "{\"op\":1}"));
                sideEffectStarted.countDown();
                assertTrue(drainRequested.await(5, TimeUnit.SECONDS));
                try {
                    ctx.recordSideEffectStarted("mock-op-2", "op-2-start", "{\"op\":2}");
                } catch (WorkflowRuntimeRepository.InvalidTransitionException expected) {
                    newSideEffectRejectedDuringDrain.set(true);
                }
                assertTrue(ctx.recordSideEffectCompleted("mock-op-1", "op-1-done", "{\"ok\":true}"));
                return WorkerTaskHandler.TaskResult.of("out", "{\"ok\":true}");
            });

            assertTrue(sideEffectStarted.await(5, TimeUnit.SECONDS));
            repo.requestDrain(tenant, run.runId(), "PAUSE");
            drainRequested.countDown();

            var outcome = future.get(5, TimeUnit.SECONDS);
            assertTrue(newSideEffectRejectedDuringDrain.get(),
                    "New SIDE_EFFECT_STARTED must be rejected once PAUSE drain closes Run gate");
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, outcome.status());
            assertFalse(outcome.wakeupScheduled(),
                    "Mailbox wakeup must be skipped cleanly while Run is draining without failing Attempt");
            assertNull(outcome.error());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + res.attemptId() + "' AND attempt_state='SUCCEEDED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND output_key='out'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void handlerReturningSuccessWithUnclosedSideEffectEscalatesToManualRequired() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-unclosed");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var run = repo.createRun(tenant, "ws-unclosed-" + UUID.randomUUID(), "CREATE", "{}",
                "ws-unclosed-" + tenant, "1", new byte[32], "ci", "{}", "{}", "mock-task");
        repo.startRun(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "ws-unclosed-cap-" + tenant, 1,
                "mock-task", "REVERSIBLE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "mock-q", "{}");
        deliverDispatchOutbox(repo, tenant, res.attemptId());

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(1, 4, 2, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);
            var request = new WorkerTaskHandler.TaskRequest(
                    tenant, run.runId(), res.attemptId(), res.dispatchId(),
                    "worker-buggy-1", "mock-task", "REVERSIBLE", "{}");

            var outcome = service.submit(request, (req, ctx) -> {
                assertTrue(ctx.recordSideEffectStarted("unclosed-op", "unclosed-start", "{}"));
                return WorkerTaskHandler.TaskResult.of("out", "{\"pretendOk\":true}");
            }).get(5, TimeUnit.SECONDS);

            assertEquals(WorkerExecutionService.OutcomeStatus.MANUAL_REQUIRED, outcome.status());
            assertNotNull(outcome.interventionId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + res.attemptId() + "' AND attempt_state='UNKNOWN'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND status='MANUAL_REQUIRED' AND control_gate='CLOSED'"));
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void deterministicFailureSeparatesCleanlyFromPostSideEffectFailure() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-fail");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(2, 4, 2, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            var runA = repo.createRun(tenant, "ws-fail-a-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-fail-a-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-a");
            repo.startRun(tenant, runA.runId());
            var resA = repo.reserveCapacity(tenant, runA.runId(), "ws-fail-cap-a-" + tenant, 1,
                    "task-a", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runA.runId(), resA.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resA.attemptId());
            var reqA = new WorkerTaskHandler.TaskRequest(
                    tenant, runA.runId(), resA.attemptId(), resA.dispatchId(),
                    "worker-a", "task-a", "PURE", "{}");
            var outA = service.submit(reqA, (req, ctx) -> {
                throw WorkerTaskHandler.TaskFailureException.deterministic("BAD_INPUT", "negative volume");
            }).get(5, TimeUnit.SECONDS);

            assertEquals(WorkerExecutionService.OutcomeStatus.FAILED, outA.status());
            assertEquals("FAILED", outA.runOutcome());
            assertNull(outA.interventionId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resA.attemptId() + "' AND attempt_state='FAILED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runA.runId() + "' AND status='FAILED' AND control_gate='CLOSED'"));

            var runB = repo.createRun(tenant, "ws-fail-b-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-fail-b-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-b");
            repo.startRun(tenant, runB.runId());
            var resB = repo.reserveCapacity(tenant, runB.runId(), "ws-fail-cap-b-" + tenant, 1,
                    "task-b", "REVERSIBLE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runB.runId(), resB.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resB.attemptId());
            var reqB = new WorkerTaskHandler.TaskRequest(
                    tenant, runB.runId(), resB.attemptId(), resB.dispatchId(),
                    "worker-b", "task-b", "REVERSIBLE", "{}");
            var outB = service.submit(reqB, (req, ctx) -> {
                assertTrue(ctx.recordSideEffectStarted("op-b", "op-b-start", "{}"));
                assertTrue(ctx.recordSideEffectCompleted("op-b", "op-b-done", "{}"));
                throw new IllegalStateException("serialization crash after external effect");
            }).get(5, TimeUnit.SECONDS);

            assertEquals(WorkerExecutionService.OutcomeStatus.MANUAL_REQUIRED, outB.status());
            assertNotNull(outB.interventionId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resB.attemptId() + "' AND attempt_state='UNKNOWN'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runB.runId() + "' AND status='MANUAL_REQUIRED'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void localCancellationSeparatesPureFromStartedAndCompletedSideEffects() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-cancel");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(2, 4, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            // A. Cancel pure Attempt before any side effect -> CANCELLED.
            var runA = repo.createRun(tenant, "ws-can-a-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-can-a-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-a");
            repo.startRun(tenant, runA.runId());
            var resA = repo.reserveCapacity(tenant, runA.runId(), "ws-can-cap-a-" + tenant, 1,
                    "task-a", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runA.runId(), resA.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resA.attemptId());
            CountDownLatch enteredA = new CountDownLatch(1);
            var futA = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runA.runId(), resA.attemptId(), resA.dispatchId(),
                    "worker-a", "task-a", "PURE", "{}"), (req, ctx) -> {
                enteredA.countDown();
                new CountDownLatch(1).await();
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            });
            assertTrue(enteredA.await(5, TimeUnit.SECONDS));
            assertTrue(service.requestCancel(resA.attemptId()));
            var outA = futA.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.CANCELLED, outA.status());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resA.attemptId() + "' AND attempt_state='CANCELLED'"));

            // B. Cancel Attempt after SIDE_EFFECT_STARTED -> MANUAL_REQUIRED (Attempt UNKNOWN).
            var runB = repo.createRun(tenant, "ws-can-b-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-can-b-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-b");
            repo.startRun(tenant, runB.runId());
            var resB = repo.reserveCapacity(tenant, runB.runId(), "ws-can-cap-b-" + tenant, 1,
                    "task-b", "REVERSIBLE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runB.runId(), resB.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resB.attemptId());
            CountDownLatch startedB = new CountDownLatch(1);
            var futB = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runB.runId(), resB.attemptId(), resB.dispatchId(),
                    "worker-b", "task-b", "REVERSIBLE", "{}"), (req, ctx) -> {
                assertTrue(ctx.recordSideEffectStarted("op-b", "op-b-start", "{}"));
                startedB.countDown();
                new CountDownLatch(1).await();
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            });
            assertTrue(startedB.await(5, TimeUnit.SECONDS));
            assertTrue(service.requestCancel(resB.attemptId()));
            var outB = futB.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.MANUAL_REQUIRED, outB.status());
            assertNotNull(outB.interventionId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resB.attemptId() + "' AND attempt_state='UNKNOWN'"));

            // C. Cancel Attempt after SIDE_EFFECT_STARTED + SIDE_EFFECT_COMPLETED -> MANUAL_REQUIRED.
            var runC = repo.createRun(tenant, "ws-can-c-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-can-c-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-c");
            repo.startRun(tenant, runC.runId());
            var resC = repo.reserveCapacity(tenant, runC.runId(), "ws-can-cap-c-" + tenant, 1,
                    "task-c", "REVERSIBLE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runC.runId(), resC.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resC.attemptId());
            CountDownLatch completedC = new CountDownLatch(1);
            var futC = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runC.runId(), resC.attemptId(), resC.dispatchId(),
                    "worker-c", "task-c", "REVERSIBLE", "{}"), (req, ctx) -> {
                assertTrue(ctx.recordSideEffectStarted("op-c", "op-c-start", "{}"));
                assertTrue(ctx.recordSideEffectCompleted("op-c", "op-c-done", "{}"));
                completedC.countDown();
                new CountDownLatch(1).await();
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            });
            assertTrue(completedC.await(5, TimeUnit.SECONDS));
            assertTrue(service.requestCancel(resC.attemptId()));
            var outC = futC.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.MANUAL_REQUIRED, outC.status());
            assertNotNull(outC.interventionId());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resC.attemptId() + "' AND attempt_state='UNKNOWN'"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void queuedCancellationPersistsCancelInDbAndReportsSettlementFailureWhenRunIsTerminal() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-qcan");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(1, 4, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            // Occupy the single worker thread so subsequent submissions stay queued.
            var runBusy = createSingleTaskRun(repo, tenant, "ws-qcan-busy", "task-busy");
            repo.startRun(tenant, runBusy.runId());
            var resBusy = repo.reserveCapacity(tenant, runBusy.runId(), "ws-qcan-busy-cap-" + tenant, 1,
                    "task-busy", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runBusy.runId(), resBusy.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resBusy.attemptId());

            CountDownLatch workerOccupied = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            var futBusy = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runBusy.runId(), resBusy.attemptId(), resBusy.dispatchId(),
                    "worker-busy", "task-busy", "PURE", "{}"), (req, ctx) -> {
                workerOccupied.countDown();
                assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
                return WorkerTaskHandler.TaskResult.of("out", "{\"ok\":true}");
            });
            assertTrue(workerOccupied.await(5, TimeUnit.SECONDS));

            // 1. Queued Attempt cancelled -> durably CANCELLED in PostgreSQL with ZERO manual caller cleanup.
            String capKey = "ws-qcan-queued-cap-" + tenant;
            var runQueued = repo.createRun(tenant, "ws-qcan-1-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-qcan-1-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-q1");
            repo.startRun(tenant, runQueued.runId());
            var resQueued = repo.reserveCapacity(tenant, runQueued.runId(), capKey, 1,
                    "task-q1", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runQueued.runId(), resQueued.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resQueued.attemptId());
            UUID stopDrainId = repo.requestDrain(tenant, runQueued.runId(), "STOP");

            AtomicBoolean queuedHandlerRan = new AtomicBoolean();
            var futQueued = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runQueued.runId(), resQueued.attemptId(), resQueued.dispatchId(),
                    "worker-q1", "task-q1", "PURE", "{}"), (req, ctx) -> {
                queuedHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            });

            assertTrue(service.requestCancel(resQueued.attemptId()),
                    "Queued cancellation must return true only after durable DB cancel succeeds");
            var outQueued = futQueued.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.CANCELLED, outQueued.status());
            assertFalse(queuedHandlerRan.get(), "Cancelled queued handler must never execute");

            // Verify Attempt, Activation, Token, Reservation, Capacity, and Drain all converged in DB.
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resQueued.attemptId() + "' AND attempt_state='CANCELLED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runQueued.runId() + "' AND activation_state='CANCELLED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runQueued.runId() + "' AND token_state='INVALIDATED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.dispatch_reservation WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resQueued.attemptId() + "' AND reservation_state='RELEASED'"));
            assertEquals(0, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                    + tenant + "' AND capacity_key='" + capKey + "'"));
            deliverDrainOutbox(repo, tenant, stopDrainId);
            assertTrue(repo.tryFinalizeDrain(tenant, runQueued.runId(), stopDrainId, "STOPPED"),
                    "STOP Drain must finalize cleanly once queued Attempt is durably cancelled");

            // 2. Queued Attempt whose DB cancel fails (Run already terminal) -> SETTLEMENT_FAILED, never false CANCELLED.
            var runTerm = repo.createRun(tenant, "ws-qcan-term-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-qcan-term-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-term");
            repo.startRun(tenant, runTerm.runId());
            var resTerm = repo.reserveCapacity(tenant, runTerm.runId(), "ws-qcan-term-cap-" + tenant, 1,
                    "task-term", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runTerm.runId(), resTerm.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resTerm.attemptId());
            // Already cancelled out-of-band & Run finalized to STOPPED before requestCancel runs.
            UUID termDrain = repo.requestDrain(tenant, runTerm.runId(), "STOP");
            deliverDrainOutbox(repo, tenant, termDrain);
            assertEquals("CANCELLED",
                    repo.cancelAttempt(tenant, runTerm.runId(), resTerm.attemptId(), "external-stop").outcome());
            assertTrue(repo.tryFinalizeDrain(tenant, runTerm.runId(), termDrain, "STOPPED"));

            var futTerm = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runTerm.runId(), resTerm.attemptId(), resTerm.dispatchId(),
                    "worker-term", "task-term", "PURE", "{}"), (req, ctx) ->
                    WorkerTaskHandler.TaskResult.of("out", "{}"));
            assertFalse(service.requestCancel(resTerm.attemptId()),
                    "requestCancel must return false when durable cancellation settlement is rejected");
            var outTerm = futTerm.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SETTLEMENT_FAILED, outTerm.status());
            assertNotNull(outTerm.error());

            releaseWorker.countDown();
            var outBusy = futBusy.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, outBusy.status());
            outBusy.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(0, service.trackedCount());
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void gateClosedBetweenAcceptAndStartConvergesForBothStopAndPauseDrain() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-race");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(2, 4, 4, registry, gate)) {
            pool.start();

            // 1. STOP Drain injected deterministically AFTER acceptAttempt commits ACCEPTED and BEFORE startAttempt.
            var runStop = repo.createRun(tenant, "ws-race-stop-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-race-stop-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-stop");
            repo.startRun(tenant, runStop.runId());
            String stopCap = "ws-race-stop-cap-" + tenant;
            var resStop = repo.reserveCapacity(tenant, runStop.runId(), stopCap, 1,
                    "task-stop", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runStop.runId(), resStop.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resStop.attemptId());

            AtomicReference<UUID> stopDrainId = new AtomicReference<>();
            AtomicBoolean sawAcceptedBeforeStop = new AtomicBoolean();
            WorkerExecutionService stopRaceService = new WorkerExecutionService(repo, lifecycle, pool, registry, () -> {
                try {
                    long accepted = scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                            + tenant + "' AND attempt_id='" + resStop.attemptId() + "' AND attempt_state='ACCEPTED'");
                    sawAcceptedBeforeStop.set(accepted == 1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                stopDrainId.set(repo.requestDrain(tenant, runStop.runId(), "STOP"));
            });

            AtomicBoolean stopHandlerRan = new AtomicBoolean();
            var stopOutcome = stopRaceService.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runStop.runId(), resStop.attemptId(), resStop.dispatchId(),
                    "worker-stop-race", "task-stop", "PURE", "{}"), (req, ctx) -> {
                stopHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            }).get(5, TimeUnit.SECONDS);

            assertTrue(sawAcceptedBeforeStop.get(), "Hook must have fired strictly after acceptAttempt committed ACCEPTED");
            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, stopOutcome.status());
            assertEquals("CANCELLED", stopOutcome.runOutcome());
            assertFalse(stopHandlerRan.get(), "Handler must never execute after gate closed before startAttempt");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resStop.attemptId() + "' AND attempt_state='CANCELLED'"),
                    "ACCEPTED Attempt must converge to CANCELLED, never remain orphaned");
            assertEquals(0, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                    + tenant + "' AND capacity_key='" + stopCap + "'"));
            deliverDrainOutbox(repo, tenant, stopDrainId.get());
            assertTrue(repo.tryFinalizeDrain(tenant, runStop.runId(), stopDrainId.get(), "STOPPED"),
                    "STOP Drain must finalize cleanly after accept/start gate race");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runStop.runId() + "' AND status='STOPPED' AND control_gate='CLOSED'"));

            // 2. PAUSE Drain injected deterministically AFTER acceptAttempt commits ACCEPTED and BEFORE startAttempt.
            var runPause = repo.createRun(tenant, "ws-race-pause-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-race-pause-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-pause");
            repo.startRun(tenant, runPause.runId());
            String pauseCap = "ws-race-pause-cap-" + tenant;
            var resPause = repo.reserveCapacity(tenant, runPause.runId(), pauseCap, 1,
                    "task-pause", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runPause.runId(), resPause.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resPause.attemptId());

            AtomicReference<UUID> pauseDrainId = new AtomicReference<>();
            AtomicBoolean sawAcceptedBeforePause = new AtomicBoolean();
            WorkerExecutionService pauseRaceService = new WorkerExecutionService(repo, lifecycle, pool, registry, () -> {
                try {
                    long accepted = scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                            + tenant + "' AND attempt_id='" + resPause.attemptId() + "' AND attempt_state='ACCEPTED'");
                    sawAcceptedBeforePause.set(accepted == 1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                pauseDrainId.set(repo.requestDrain(tenant, runPause.runId(), "PAUSE"));
            });

            AtomicBoolean pauseHandlerRan = new AtomicBoolean();
            var pauseOutcome = pauseRaceService.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runPause.runId(), resPause.attemptId(), resPause.dispatchId(),
                    "worker-pause-race", "task-pause", "PURE", "{}"), (req, ctx) -> {
                pauseHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            }).get(5, TimeUnit.SECONDS);

            assertTrue(sawAcceptedBeforePause.get());
            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, pauseOutcome.status());
            assertEquals("ABORTED_FOR_PAUSE", pauseOutcome.runOutcome());
            assertFalse(pauseHandlerRan.get());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resPause.attemptId() + "' AND attempt_state='CANCELLED'"));
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runPause.runId() + "' AND node_key='task-pause' AND token_state='READY'"),
                    "PAUSE Drain must restore pre-start token back to READY so Run can resume");
            assertEquals(0, scalar(ds, "SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"
                    + tenant + "' AND capacity_key='" + pauseCap + "'"));
            deliverDrainOutbox(repo, tenant, pauseDrainId.get());
            assertTrue(repo.tryFinalizeDrain(tenant, runPause.runId(), pauseDrainId.get(), "PAUSED"),
                    "PAUSE Drain must finalize cleanly to PAUSED while preserving READY token");
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"
                    + tenant + "' AND run_id='" + runPause.runId() + "' AND status='PAUSED' AND control_gate='PAUSE'"));

            // 3. Gate closed BEFORE acceptAttempt also converges DISPATCHED Attempt without caller cleanup.
            var runBeforeAccept = repo.createRun(tenant, "ws-closed-acc-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-closed-acc-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-before");
            repo.startRun(tenant, runBeforeAccept.runId());
            var resBefore = repo.reserveCapacity(tenant, runBeforeAccept.runId(), "ws-before-cap-" + tenant, 1,
                    "task-before", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runBeforeAccept.runId(), resBefore.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resBefore.attemptId());
            UUID beforeDrain = repo.requestDrain(tenant, runBeforeAccept.runId(), "STOP");

            WorkerExecutionService normalService = new WorkerExecutionService(repo, lifecycle, pool, registry);
            AtomicBoolean beforeHandlerRan = new AtomicBoolean();
            var beforeOutcome = normalService.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runBeforeAccept.runId(), resBefore.attemptId(), resBefore.dispatchId(),
                    "worker-before", "task-before", "PURE", "{}"), (req, ctx) -> {
                beforeHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            }).get(5, TimeUnit.SECONDS);

            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, beforeOutcome.status());
            assertFalse(beforeHandlerRan.get());
            deliverDrainOutbox(repo, tenant, beforeDrain);
            assertTrue(repo.tryFinalizeDrain(tenant, runBeforeAccept.runId(), beforeDrain, "STOPPED"));
        } finally {
            mailboxExec.shutdownNow();
        }
    }

    @Test
    void executionContextRejectsOffThreadCallsAndTaskRequestBindingMismatchRejectsBeforeHandler() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-ctx");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = SchedulingGateTestSupport.openGate();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(2, 4, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            // 1. Off-thread background invocation of WorkerExecutionContext is rejected and writes 0 facts.
            var runCtx = createSingleTaskRun(repo, tenant, "ws-ctx-1", "task-ctx");
            repo.startRun(tenant, runCtx.runId());
            var resCtx = repo.reserveCapacity(tenant, runCtx.runId(), "ws-ctx-cap-" + tenant, 1,
                    "task-ctx", "REVERSIBLE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runCtx.runId(), resCtx.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resCtx.attemptId());

            AtomicReference<Throwable> offThreadError = new AtomicReference<>();
            var ctxOutcome = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runCtx.runId(), resCtx.attemptId(), resCtx.dispatchId(),
                    "worker-ctx", "task-ctx", "REVERSIBLE", "{}"), (req, ctx) -> {
                Thread bg = new Thread(() -> {
                    try {
                        ctx.recordSideEffectStarted("bg-op", "bg-start", "{}");
                    } catch (Throwable t) {
                        offThreadError.set(t);
                    }
                }, "rogue-handler-bg");
                bg.start();
                bg.join(5000);
                return WorkerTaskHandler.TaskResult.of("out", "{\"ok\":true}");
            }).get(5, TimeUnit.SECONDS);

            assertInstanceOf(IllegalStateException.class, offThreadError.get(),
                    "Off-thread side-effect write must be rejected synchronously by WorkerExecutionContext");
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, ctxOutcome.status());
            ctxOutcome.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(0, scalar(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + resCtx.attemptId() + "' AND fact_type='SIDE_EFFECT_STARTED'"));

            // 2. TaskRequest with mismatched nodeKey or sideEffectClass is rejected at acceptance before handler runs.
            var runBind = createSingleTaskRun(repo, tenant, "ws-bind-1", "real-node");
            repo.startRun(tenant, runBind.runId());
            var resBind = repo.reserveCapacity(tenant, runBind.runId(), "ws-bind-cap-" + tenant, 1,
                    "real-node", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runBind.runId(), resBind.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resBind.attemptId());

            AtomicBoolean mismatchHandlerRan = new AtomicBoolean();
            var wrongNodeOutcome = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runBind.runId(), resBind.attemptId(), resBind.dispatchId(),
                    "worker-bind", "forged-node", "PURE", "{}"), (req, ctx) -> {
                mismatchHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            }).get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, wrongNodeOutcome.status());
            assertInstanceOf(WorkflowRuntimeRepository.StaleRuntimeWriteException.class, wrongNodeOutcome.error());
            assertFalse(mismatchHandlerRan.get());
            assertEquals(0, pool.inFlightForRun(runBind.runId()),
                    "WorkerPool must release task and per-Run permit BEFORE outcomeFuture completes");
            assertEquals(0, registry.activeCount());

            var wrongClassOutcome = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runBind.runId(), resBind.attemptId(), resBind.dispatchId(),
                    "worker-bind", "real-node", "DEVICE", "{}"), (req, ctx) -> {
                mismatchHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            }).get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, wrongClassOutcome.status());
            assertInstanceOf(WorkflowRuntimeRepository.StaleRuntimeWriteException.class, wrongClassOutcome.error());
            assertFalse(mismatchHandlerRan.get());
            assertEquals(0, pool.inFlightForRun(runBind.runId()));
            assertEquals(0, registry.activeCount());

            // The legitimate request with matching (nodeKey, sideEffectClass) executes and completes cleanly.
            var validOutcome = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runBind.runId(), resBind.attemptId(), resBind.dispatchId(),
                    "worker-bind", "real-node", "PURE", "{}"), (req, ctx) ->
                    WorkerTaskHandler.TaskResult.of("out", "{\"bound\":true}")).get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, validOutcome.status());
            validOutcome.graphAdvance().get(5, TimeUnit.SECONDS);
            assertEquals(0, pool.inFlightForRun(runBind.runId()));
            assertEquals(0, registry.activeCount());
        } finally {
            mailboxExec.shutdownNow();
        }
    }
}
