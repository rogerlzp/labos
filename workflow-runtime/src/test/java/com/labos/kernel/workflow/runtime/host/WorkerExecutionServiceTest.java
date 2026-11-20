package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionContext;
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
        SchedulingGate gate = new SchedulingGate();
        gate.open();
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

            // Context must reject post-return side-effect attempts (defense in depth).
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
        SchedulingGate gate = new SchedulingGate();
        gate.open();
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
        SchedulingGate gate = new SchedulingGate();
        gate.open();
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
                // Buggy handler forgets to record SIDE_EFFECT_COMPLETED and returns success.
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
        SchedulingGate gate = new SchedulingGate();
        gate.open();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(2, 4, 2, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            // A. Pure deterministic failure (no side effect recorded) -> FAILED.
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

            // B. Failure thrown AFTER a side effect was executed and completed -> fail-closed MANUAL_REQUIRED.
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
        SchedulingGate gate = new SchedulingGate();
        gate.open();
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

            // C. Cancel Attempt after SIDE_EFFECT_STARTED + SIDE_EFFECT_COMPLETED -> never erases evidence; MANUAL_REQUIRED.
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
    void closedGateAndQueuedCancellationPreventHandlerExecution() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "ws-gate");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var mailboxExec = Executors.newSingleThreadExecutor();
        SchedulingGate gate = new SchedulingGate();
        gate.open();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (var lifecycle = new WorkflowRunLifecycleCoordinator(repo, new GraphCoordinator(repo), mailboxExec, 4);
             var pool = new WorkerPool(1, 4, 4, registry, gate)) {
            pool.start();
            WorkerExecutionService service = new WorkerExecutionService(repo, lifecycle, pool, registry);

            // 1. Run gate closed before worker accepts -> REJECTED_BEFORE_START, handler never invoked.
            var runClosed = repo.createRun(tenant, "ws-closed-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-closed-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-1");
            repo.startRun(tenant, runClosed.runId());
            var resClosed = repo.reserveCapacity(tenant, runClosed.runId(), "ws-closed-cap-" + tenant, 1,
                    "task-1", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runClosed.runId(), resClosed.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, resClosed.attemptId());
            repo.requestDrain(tenant, runClosed.runId(), "STOP");

            AtomicBoolean closedHandlerRan = new AtomicBoolean();
            var closedOutcome = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runClosed.runId(), resClosed.attemptId(), resClosed.dispatchId(),
                    "worker-closed", "task-1", "PURE", "{}"), (req, ctx) -> {
                closedHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            }).get(5, TimeUnit.SECONDS);

            assertEquals(WorkerExecutionService.OutcomeStatus.REJECTED_BEFORE_START, closedOutcome.status());
            assertFalse(closedHandlerRan.get(), "Handler must never run when Run gate rejected admission");
            // Clean up DISPATCHED attempt so shared test DB has zero leaked active attempts.
            assertEquals("CANCELLED",
                    repo.cancelAttempt(tenant, runClosed.runId(), resClosed.attemptId(), "test-cleanup").outcome());

            // 2. Queued Attempt cancelled before entering Worker thread -> CANCELLED_BEFORE_START, handler never runs.
            var runBusy = repo.createRun(tenant, "ws-busy-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-busy-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-a");
            repo.startRun(tenant, runBusy.runId());
            var res1 = repo.reserveCapacity(tenant, runBusy.runId(), "ws-busy-cap-" + tenant, 2,
                    "task-a", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runBusy.runId(), res1.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, res1.attemptId());

            var runQueued = repo.createRun(tenant, "ws-queued-" + UUID.randomUUID(), "CREATE", "{}",
                    "ws-queued-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task-b");
            repo.startRun(tenant, runQueued.runId());
            var res2 = repo.reserveCapacity(tenant, runQueued.runId(), "ws-queued-cap-" + tenant, 1,
                    "task-b", "PURE", Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant, runQueued.runId(), res2.attemptId(), "q", "{}");
            deliverDispatchOutbox(repo, tenant, res2.attemptId());

            CountDownLatch workerOccupied = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            var fut1 = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runBusy.runId(), res1.attemptId(), res1.dispatchId(),
                    "worker-busy", "task-a", "PURE", "{}"), (req, ctx) -> {
                workerOccupied.countDown();
                assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
                return WorkerTaskHandler.TaskResult.of("out", "{\"ok\":true}");
            });
            assertTrue(workerOccupied.await(5, TimeUnit.SECONDS));

            AtomicBoolean queuedHandlerRan = new AtomicBoolean();
            var fut2 = service.submit(new WorkerTaskHandler.TaskRequest(
                    tenant, runQueued.runId(), res2.attemptId(), res2.dispatchId(),
                    "worker-queued", "task-b", "PURE", "{}"), (req, ctx) -> {
                queuedHandlerRan.set(true);
                return WorkerTaskHandler.TaskResult.of("out", "{}");
            });

            assertTrue(service.requestCancel(res2.attemptId()));
            var out2 = fut2.get(5, TimeUnit.SECONDS);
            assertEquals(WorkerExecutionService.OutcomeStatus.CANCELLED_BEFORE_START, out2.status());
            assertFalse(queuedHandlerRan.get());
            assertEquals(1, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND attempt_id='" + res2.attemptId() + "' AND attempt_state='DISPATCHED'"));

            releaseWorker.countDown();
            assertEquals(WorkerExecutionService.OutcomeStatus.SUCCEEDED, fut1.get(5, TimeUnit.SECONDS).status());
            assertEquals("CANCELLED",
                    repo.cancelAttempt(tenant, runQueued.runId(), res2.attemptId(), "test-cleanup").outcome());
            assertEquals(0, service.trackedCount());
        } finally {
            mailboxExec.shutdownNow();
        }
    }
}
