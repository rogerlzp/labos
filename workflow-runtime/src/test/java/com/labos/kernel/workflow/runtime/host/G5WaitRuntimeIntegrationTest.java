package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionService;
import com.labos.kernel.workflow.runtime.worker.WorkerPool;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end PG17 integration tests for AI-3 runtime scheduling with real AI-1 Repository
 * and AI-2 GraphPlan/Compiler: WAIT-only non-spinning runs, TIMER expiry, TIMEOUT racing,
 * Parallel TASK+WAIT non-starvation, PAUSE->Signal->Deadline->RESUME->Replay priority,
 * SchedulingGate enforcement, restart recovery, and tenant scoping.
 */
class G5WaitRuntimeIntegrationTest {

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private DriverManagerDataSource dataSource() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                        + "/" + env("PGDATABASE", "runtime_test"),
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID registerTenant(JdbcTemplate jdbc, String prefix) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runtime.tenant(tenant_id, tenant_key, display_name)
            VALUES (?, ?, ?)
            """, tenant, prefix + "-" + tenant, prefix);
        return tenant;
    }

    private static UUID createAndStartRun(WorkflowRuntimeRepository repo, UUID tenant, GraphPlan plan) throws Exception {
        String json = GraphPlanCodec.toJson(plan);
        var created = repo.createRun(tenant, "req-" + UUID.randomUUID(), "CREATE",
                "{\"k\":\"" + plan.definitionKey() + "\"}",
                plan.definitionKey(), plan.definitionVersion(), sha256(json),
                plan.compilerVersion(), json, "{}", "s");
        repo.startRun(tenant, created.runId());
        return created.runId();
    }

    private static GraphPlan compileMessageWait(String corrKey) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="%s"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(corrKey);
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("msg-" + UUID.randomUUID(), "1", xml, Map.of()));
    }

    private static GraphPlan compileTimerOnly(String durationIso) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:intermediateCatchEvent id="t1">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>%s</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(durationIso);
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("tmr-" + UUID.randomUUID(), "1", xml, Map.of()));
    }

    private static GraphPlan compileMessageWithTimeout(String corrKey, String durationIso) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="%s"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>%s</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:endEvent id="e_to"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e_ok"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e_to"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(corrKey, durationIso);
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("mto-" + UUID.randomUUID(), "1", xml, Map.of()));
    }

    private static RuntimeDispatchLoop buildLoop(
            WorkflowRuntimeRepository repo,
            GraphCoordinator coordinator,
            WorkflowRunLifecycleCoordinator lifecycle,
            WorkerPool pool,
            WorkerExecutionRegistry registry,
            SchedulingGate gate,
            UUID scopedTenantId,
            String capKey,
            int capLimit) {
        WorkerExecutionService wes = new WorkerExecutionService(repo, lifecycle, pool, registry);
        LocalTaskDispatchService dispatch = new LocalTaskDispatchService(
                repo, wes,
                node -> new LocalTaskDispatchService.TaskDispatchSpec(
                        capKey, capLimit, "PURE", "q", Duration.ofSeconds(30), "{}"),
                node -> Optional.of((req, ctx) -> new WorkerTaskHandler.TaskResult("ok", "{\"ok\":true}", Map.of())));
        DeviceCommandExecutionService cmd = new DeviceCommandExecutionService(
                repo, env -> { throw new AssertionError("No device commands expected"); });
        return new RuntimeDispatchLoop(
                repo, dispatch, cmd, gate, Duration.ofMillis(50), 20, "test-loop", scopedTenantId,
                attemptId -> {}, (t, r) -> {
                    if (repo.isOpenRun(t, r)) {
                        lifecycle.wake(t, r).join();
                    }
                });
    }

    @Test
    void waitOnlyRunDoesNotHotLoopAndTimerAndTimeoutCompleteViaDispatchLoop() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-e2e");
        GraphCoordinator coordinator = new GraphCoordinator(repo);
        SchedulingGate gate = new SchedulingGate();
        gate.open();

        var exec = Executors.newSingleThreadExecutor();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        AtomicInteger advanceWakeups = new AtomicInteger();
        try (WorkflowRunLifecycleCoordinator lifecycle = new WorkflowRunLifecycleCoordinator(repo, coordinator, exec, 2);
             WorkerPool pool = new WorkerPool(1, 4, 2, registry, gate)) {
            pool.start();

            WorkerExecutionService wes = new WorkerExecutionService(repo, lifecycle, pool, registry);
            LocalTaskDispatchService dispatch = new LocalTaskDispatchService(
                    repo, wes,
                    node -> new LocalTaskDispatchService.TaskDispatchSpec("c", 1, "PURE", "q", Duration.ofSeconds(30), "{}"),
                    node -> Optional.empty());
            DeviceCommandExecutionService cmd = new DeviceCommandExecutionService(repo, e -> {
                throw new AssertionError("No commands");
            });
            RuntimeDispatchLoop loop = new RuntimeDispatchLoop(
                    repo, dispatch, cmd, gate, Duration.ofMillis(50), 20, "e2e", tenant,
                    a -> {}, (t, r) -> {
                advanceWakeups.incrementAndGet();
                coordinator.advance(t, r);
            });

            // 1. MESSAGE Wait registers cleanly without hot-looping over idle cycles.
            UUID msgRun = createAndStartRun(repo, tenant, compileMessageWait("corr-msg-1"));
            loop.runOneCycle();
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, msgRun));
            int wakeupsAfterRegister = advanceWakeups.get();

            // Subsequent idle cycles while waiting MUST NOT trigger redundant coordinator wakeups!
            loop.runOneCycle();
            loop.runOneCycle();
            assertEquals(wakeupsAfterRegister, advanceWakeups.get(),
                    "Active WAIT must NOT hot-loop graphWakeup across idle poll cycles");

            // Deliver signal -> synchronous Wait satisfaction -> next cycle routes END -> SUCCEEDED!
            assertEquals(WaitOutcomes.COMPLETED, repo.receiveWaitInbox(
                    tenant, msgRun, "webhook", "m1", "corr-msg-1", "{\"v\":1}").outcome());
            loop.runOneCycle();
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, msgRun));

            // 2. TIMER catch event expires cleanly on DB clock!
            UUID timerRun = createAndStartRun(repo, tenant, compileTimerOnly("PT10S"));
            loop.runOneCycle();
            jdbc.update("""
                UPDATE workflow_runtime.wait_registration
                   SET deadline_at = clock_timestamp() - interval '1 second'
                 WHERE tenant_id=? AND run_id=?
                """, tenant, timerRun);
            loop.runOneCycle();
            assertEquals("EXPIRED", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, timerRun));
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, timerRun));

            // 3. MESSAGE_WITH_TIMEOUT timeout branch completes to SUCCEEDED along e_to!
            UUID timeoutRun = createAndStartRun(repo, tenant, compileMessageWithTimeout("corr-mto", "PT10S"));
            loop.runOneCycle();
            jdbc.update("""
                UPDATE workflow_runtime.wait_registration
                   SET deadline_at = clock_timestamp() - interval '1 second'
                 WHERE tenant_id=? AND run_id=?
                """, tenant, timeoutRun);
            loop.runOneCycle();
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, timeoutRun));
            assertEquals("CONSUMED", jdbc.queryForObject("""
                SELECT token_state FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key='e_to'
                """, String.class, tenant, timeoutRun));
            assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key='e_ok'
                """, Long.class, tenant, timeoutRun));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void parallelTaskPlusWaitRegistersWaitEvenWhenTaskDispatchedOrDeferredAndCompletesJoin() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-par");
        GraphCoordinator coordinator = new GraphCoordinator(repo);
        SchedulingGate gate = new SchedulingGate();
        gate.open();

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:parallelGateway id="ps"/>
                    <bpmn:serviceTask id="t1" protocolName="HTTP" resource="1"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="par-corr"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:parallelGateway id="pj"/>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="ps"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="ps" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="ps" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="t1" targetRef="pj"/>
                    <bpmn:sequenceFlow id="f5" sourceRef="w1" targetRef="pj"/>
                    <bpmn:sequenceFlow id="f6" sourceRef="pj" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan parallelPlan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("par-" + UUID.randomUUID(), "1", xml, Map.of()));

        var exec = Executors.newSingleThreadExecutor();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (WorkflowRunLifecycleCoordinator lifecycle = new WorkflowRunLifecycleCoordinator(repo, coordinator, exec, 4);
             WorkerPool pool = new WorkerPool(1, 8, 2, registry, gate)) {
            pool.start();

            String capKey = "par-cap-" + tenant;
            RuntimeDispatchLoop loop = buildLoop(
                    repo, coordinator, lifecycle, pool, registry, gate, tenant, capKey, 2);

            UUID runId = createAndStartRun(repo, tenant, parallelPlan);
            // Advance START -> SPLIT -> creates READY TASK t1 and READY WAIT w1.
            coordinator.advance(tenant, runId);

            // Cycle 1: dispatches t1 AND hasReadyControlTokens==true -> immediately wakes coordinator to register w1!
            loop.runOneCycle();
            assertEquals(0L, loop.stats().cycleErrors());
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, runId),
                    "TASK dispatch must not starve READY WAIT registration");

            // Satisfy WAIT and wait for WorkerPool to finish t1 + Join + END -> SUCCEEDED.
            assertEquals(WaitOutcomes.COMPLETED, repo.receiveWaitInbox(
                    tenant, runId, "webhook", "par-sig-1", "par-corr", "{\"ok\":true}").outcome());

            long deadlineMs = System.currentTimeMillis() + 8_000;
            String status = "RUNNING";
            while (System.currentTimeMillis() < deadlineMs) {
                loop.runOneCycle();
                status = jdbc.queryForObject(
                        "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                        String.class, tenant, runId);
                if ("SUCCEEDED".equals(status)) break;
                Thread.sleep(30);
            }
            assertEquals("SUCCEEDED", status);
            assertEquals(0L, loop.stats().cycleErrors(), () -> "lastError=" + loop.stats().lastError());

            // Verify capacity-deferred TASK branch also does not starve READY WAIT registration!
            String fullCapKey = "par-full-cap-" + tenant;
            jdbc.update("""
                INSERT INTO workflow_runtime.scheduler_capacity(tenant_id, capacity_key, capacity_limit, reserved_count)
                VALUES (?, ?, 1, 1)
                """, tenant, fullCapKey);
            RuntimeDispatchLoop deferredLoop = buildLoop(
                    repo, coordinator, lifecycle, pool, registry, gate, tenant, fullCapKey, 1);
            UUID deferredRun = createAndStartRun(repo, tenant, parallelPlan);
            coordinator.advance(tenant, deferredRun);
            deferredLoop.runOneCycle();
            assertEquals(0L, deferredLoop.stats().cycleErrors());
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, deferredRun),
                    "Capacity-deferred TASK must still wake GraphCoordinator to register READY WAIT");
            // Release occupied capacity -> task dispatches + signal satisfies -> SUCCEEDED!
            jdbc.update("""
                UPDATE workflow_runtime.scheduler_capacity SET reserved_count=0
                 WHERE tenant_id=? AND capacity_key=?
                """, tenant, fullCapKey);
            assertEquals(WaitOutcomes.COMPLETED, repo.receiveWaitInbox(
                    tenant, deferredRun, "webhook", "par-sig-2", "par-corr", "{\"ok\":2}").outcome());
            long dl2 = System.currentTimeMillis() + 8_000;
            String st2 = "RUNNING";
            while (System.currentTimeMillis() < dl2) {
                deferredLoop.runOneCycle();
                st2 = jdbc.queryForObject(
                        "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                        String.class, tenant, deferredRun);
                if ("SUCCEEDED".equals(st2)) break;
                Thread.sleep(30);
            }
            assertEquals("SUCCEEDED", st2);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void pauseSignalBeforeDeadlineWinsTimerAfterResumeAndGateClosedAndTenantIsolationHold() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenantA = registerTenant(jdbc, "g5-tA");
        UUID tenantB = registerTenant(jdbc, "g5-tB");
        GraphCoordinator coordinator = new GraphCoordinator(repo);
        SchedulingGate gate = new SchedulingGate();
        gate.open();

        var exec = Executors.newSingleThreadExecutor();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (WorkflowRunLifecycleCoordinator lifecycle = new WorkflowRunLifecycleCoordinator(repo, coordinator, exec, 2);
             WorkerPool pool = new WorkerPool(1, 4, 2, registry, gate)) {
            pool.start();

            RuntimeDispatchLoop loopA = buildLoop(
                    repo, coordinator, lifecycle, pool, registry, gate, tenantA, "capA-" + tenantA, 2);

            // Tenant A Run: PAUSE -> signal before deadline -> wall clock passes deadline -> RESUME -> signal wins!
            UUID runA = createAndStartRun(repo, tenantA, compileMessageWithTimeout("corr-resume", "PT10S"));
            loopA.runOneCycle();

            // Tenant B Run: overdue Wait (must NEVER be touched by tenantA-scoped loopA!)
            UUID runB = createAndStartRun(repo, tenantB, compileTimerOnly("PT5S"));
            coordinator.advance(tenantB, runB);
            jdbc.update("""
                UPDATE workflow_runtime.wait_registration
                   SET deadline_at = clock_timestamp() - interval '5 seconds'
                 WHERE tenant_id=? AND run_id=?
                """, tenantB, runB);

            // PAUSE Tenant A Run via loopA drain progression
            UUID pauseDrain = repo.requestDrain(tenantA, runA, "PAUSE");
            loopA.runOneCycle();
            assertEquals("PAUSED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenantA, runA));
            // Confirm tenant B's overdue Wait was untouched by loopA!
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenantB, runB));

            // Signal arrives while PAUSED -> stored RECEIVED; then deadline becomes overdue
            var pausedSig = repo.receiveWaitInbox(tenantA, runA, "webhook", "sig-while-paused",
                    "corr-resume", "{\"winner\":\"signal\"}");
            assertEquals(WaitOutcomes.DEFERRED_RUN_NOT_OPEN, pausedSig.outcome());
            jdbc.update("""
                UPDATE workflow_runtime.inbox_receipt
                   SET received_at = clock_timestamp() - interval '5 seconds'
                 WHERE tenant_id=? AND inbox_id=?
                """, tenantA, pausedSig.inboxId());
            jdbc.update("""
                UPDATE workflow_runtime.wait_registration
                   SET deadline_at = clock_timestamp() - interval '2 seconds'
                 WHERE tenant_id=? AND run_id=?
                """, tenantA, runA);

            // While PAUSED, dispatch loop leaves WAIT and Inbox untouched!
            loopA.runOneCycle();
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenantA, runA));

            // Close SchedulingGate -> resumePausedRun happens, but closed gate blocks dispatch loop replay & timer!
            assertTrue(repo.resumePausedRun(tenantA, runA));
            gate.close();
            loopA.runOneCycle();
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenantA, runA),
                    "Closed SchedulingGate must block Wait Inbox replay and due Wait completion");

            // Reopen gate -> Step 1 (Inbox replay) beats Step 2 (due Timer) -> Wait SATISFIED -> e_ok -> SUCCEEDED!
            gate.open();
            loopA.runOneCycle();
            assertEquals("SATISFIED", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenantA, runA));
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenantA, runA));
            assertEquals("CONSUMED", jdbc.queryForObject("""
                SELECT token_state FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key='e_ok'
                """, String.class, tenantA, runA));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?",
                    String.class, tenantB, runB),
                    "Tenant A dispatch loop must never expire Tenant B's overdue Wait");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void freshRestartLoopRecoversStaleProcessingWaitInboxAndOverdueTimer() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-rst");
        GraphCoordinator coordinator = new GraphCoordinator(repo);
        SchedulingGate gate = new SchedulingGate();
        gate.open();

        // Prepare Run 1 with a PROCESSING Wait Inbox receipt left behind by an interrupted process.
        UUID runProcessing = createAndStartRun(repo, tenant, compileMessageWait("corr-proc"));
        coordinator.advance(tenant, runProcessing);
        UUID pDrain = repo.requestDrain(tenant, runProcessing, "PAUSE");
        jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED', delivered_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
            """, tenant, runProcessing);
        assertTrue(repo.tryFinalizeDrain(tenant, runProcessing, pDrain, "PAUSED"));
        UUID procInbox = repo.receiveWaitInbox(
                tenant, runProcessing, "webhook", "proc-1", "corr-proc", "{\"ok\":1}").inboxId();
        assertTrue(repo.resumePausedRun(tenant, runProcessing));
        jdbc.update("""
            UPDATE workflow_runtime.inbox_receipt SET receipt_state='PROCESSING'
             WHERE tenant_id=? AND inbox_id=?
            """, tenant, procInbox);

        // Prepare Run 2 with an overdue TIMER Wait from before process restart.
        UUID runOverdue = createAndStartRun(repo, tenant, compileTimerOnly("PT5S"));
        coordinator.advance(tenant, runOverdue);
        jdbc.update("""
            UPDATE workflow_runtime.wait_registration
               SET deadline_at = clock_timestamp() - interval '10 seconds'
             WHERE tenant_id=? AND run_id=?
            """, tenant, runOverdue);

        // Start a brand-new RuntimeDispatchLoop instance (simulating post-restart recovery).
        var exec = Executors.newSingleThreadExecutor();
        WorkerExecutionRegistry registry = new WorkerExecutionRegistry();
        try (WorkflowRunLifecycleCoordinator lifecycle = new WorkflowRunLifecycleCoordinator(repo, coordinator, exec, 2);
             WorkerPool pool = new WorkerPool(1, 4, 2, registry, gate)) {
            pool.start();
            RuntimeDispatchLoop restartedLoop = buildLoop(
                    repo, coordinator, lifecycle, pool, registry, gate, tenant, "rst-cap-" + tenant, 2);
            restartedLoop.runOneCycle();
            assertEquals(0L, restartedLoop.stats().cycleErrors());

            assertEquals("PROCESSED", jdbc.queryForObject(
                    "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                    String.class, tenant, procInbox));
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, runProcessing));
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                    String.class, tenant, runOverdue));
        } finally {
            exec.shutdownNow();
        }
    }
}
