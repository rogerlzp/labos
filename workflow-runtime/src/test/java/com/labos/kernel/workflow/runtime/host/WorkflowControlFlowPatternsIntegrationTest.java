package com.labos.kernel.workflow.runtime.host;

import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end PG17 integration tests verifying real BPMN control-flow topologies
 * (serial, parallel Split/Join, XOR conditional branch) and mid-execution
 * {@code PAUSE -> RESUME} / {@code STOP} drains on {@link EmbeddedWorkflowRuntimeHost}.
 */
class WorkflowControlFlowPatternsIntegrationTest {

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

    private static UUID createTenant(DriverManagerDataSource ds, String prefix) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','" + prefix + "-" + tenant + "','flow-patterns-it')");
        }
        return tenant;
    }

    private static long scalar(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond, String desc)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(15);
        }
        assertTrue(cond.getAsBoolean(), "Condition not satisfied within " + timeout + ": " + desc);
    }

    private static EmbeddedWorkflowRuntimeHost.Config hostConfig(Path lockFile, String hostId, UUID tenant) {
        return new EmbeddedWorkflowRuntimeHost.Config(
                lockFile, hostId, 4, 32, 8, 2, 64, 50,
                Duration.ofMillis(25), 16,
                List.of(),
                TcpDeviceConnection.Config.defaults(),
                tenant);
    }

    private static WorkflowRuntimeRepository.CreatedRun createCompiledRun(
            WorkflowRuntimeRepository repo, UUID tenant, String planKey, String bpmnXml, String inputJson) {
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(planKey, "1", bpmnXml, Map.of()));
        return repo.createRun(tenant, "req-" + planKey, "CREATE", "{}",
                planKey, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), inputJson, "start");
    }

    private static final String SERIAL_BPMN = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="serial_proc">
                <bpmn:startEvent id="start"/>
                <bpmn:task id="step_a" name="Step A" protocolName="OpA" resource="1"/>
                <bpmn:task id="step_b" name="Step B" protocolName="OpB" resource="1"/>
                <bpmn:task id="step_c" name="Step C" protocolName="OpC" resource="1"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="step_a"/>
                <bpmn:sequenceFlow id="f2" sourceRef="step_a" targetRef="step_b"/>
                <bpmn:sequenceFlow id="f3" sourceRef="step_b" targetRef="step_c"/>
                <bpmn:sequenceFlow id="f4" sourceRef="step_c" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String PARALLEL_BPMN = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="parallel_proc">
                <bpmn:startEvent id="start"/>
                <bpmn:parallelGateway id="split"/>
                <bpmn:task id="a1" name="Branch A Step 1" protocolName="OpA1" resource="1"/>
                <bpmn:task id="a2" name="Branch A Step 2" protocolName="OpA2" resource="1"/>
                <bpmn:task id="b1" name="Branch B Step 1" protocolName="OpB1" resource="2"/>
                <bpmn:task id="b2" name="Branch B Step 2" protocolName="OpB2" resource="2"/>
                <bpmn:parallelGateway id="join"/>
                <bpmn:task id="final_step" name="Final Merge" protocolName="OpFinal" resource="3"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="split"/>
                <bpmn:sequenceFlow id="fa1" sourceRef="split" targetRef="a1"/>
                <bpmn:sequenceFlow id="fa2" sourceRef="a1" targetRef="a2"/>
                <bpmn:sequenceFlow id="fa3" sourceRef="a2" targetRef="join"/>
                <bpmn:sequenceFlow id="fb1" sourceRef="split" targetRef="b1"/>
                <bpmn:sequenceFlow id="fb2" sourceRef="b1" targetRef="b2"/>
                <bpmn:sequenceFlow id="fb3" sourceRef="b2" targetRef="join"/>
                <bpmn:sequenceFlow id="fj" sourceRef="join" targetRef="final_step"/>
                <bpmn:sequenceFlow id="fe" sourceRef="final_step" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String XOR_BPMN = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="xor_proc">
                <bpmn:startEvent id="start"/>
                <bpmn:task id="decide" name="Evaluate Score" protocolName="Evaluate" resource="1"/>
                <bpmn:exclusiveGateway id="xor" default="f_fail"/>
                <bpmn:task id="path_pass" name="Pass Step 1" protocolName="Pass1" resource="1"/>
                <bpmn:task id="pass_followup" name="Pass Step 2" protocolName="Pass2" resource="1"/>
                <bpmn:task id="path_fail" name="Fail Step" protocolName="Fail1" resource="1"/>
                <bpmn:exclusiveGateway id="merge"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="decide"/>
                <bpmn:sequenceFlow id="f1" sourceRef="decide" targetRef="xor"/>
                <bpmn:sequenceFlow id="f_pass" sourceRef="xor" targetRef="path_pass">
                  <bpmn:conditionExpression>score &gt;= 80</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f_pass2" sourceRef="path_pass" targetRef="pass_followup"/>
                <bpmn:sequenceFlow id="f_pass3" sourceRef="pass_followup" targetRef="merge"/>
                <bpmn:sequenceFlow id="f_fail" sourceRef="xor" targetRef="path_fail"/>
                <bpmn:sequenceFlow id="f_fail2" sourceRef="path_fail" targetRef="merge"/>
                <bpmn:sequenceFlow id="fe" sourceRef="merge" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void serialWorkflowWithMidExecutionPauseAndResume() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "flow-serial");

        CopyOnWriteArrayList<String> executed = new CopyOnWriteArrayList<>();
        CountDownLatch stepBStarted = new CountDownLatch(1);
        CountDownLatch allowStepBFinish = new CountDownLatch(1);

        try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                hostConfig(tempDir.resolve("serial.lock"), "serial-host", tenant),
                repo,
                node -> new LocalTaskDispatchService.TaskDispatchSpec(
                        "serial-cap-" + tenant + "-" + node.key(), 2, "PURE", "q", Duration.ofSeconds(30), "{}"),
                node -> Optional.of((req, ctx) -> {
                    executed.add(req.nodeKey());
                    if ("step_b".equals(req.nodeKey())) {
                        stepBStarted.countDown();
                        assertTrue(allowStepBFinish.await(8, TimeUnit.SECONDS));
                    }
                    return WorkerTaskHandler.TaskResult.of("out", "{\"node\":\"" + req.nodeKey() + "\"}");
                }))) {
            host.start();
            var run = createCompiledRun(repo, tenant, "serial-" + UUID.randomUUID(), SERIAL_BPMN, "{}");
            host.startRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);

            // Pause while step_b is actively running -> step_b finishes, step_c must NOT start.
            assertTrue(stepBStarted.await(8, TimeUnit.SECONDS), "step_b did not start");
            host.requestPause(tenant, run.runId());
            allowStepBFinish.countDown();

            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run.runId()).orElseThrow();
                return "PAUSED".equals(ov.status()) && "PAUSE".equals(ov.controlGate())
                        && host.healthSnapshot().activeLocalWorkers() == 0;
            }, "Serial run must converge automatically to PAUSED");

            assertEquals(List.of("step_a", "step_b"), executed, "step_c must not run while PAUSED");

            // Resume -> step_c executes and run completes SUCCEEDED.
            host.resumeRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);
            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run.runId()).orElseThrow();
                return "SUCCEEDED".equals(ov.status()) && "CLOSED".equals(ov.controlGate());
            }, "Serial run must complete SUCCEEDED after RESUME");

            assertEquals(List.of("step_a", "step_b", "step_c"), executed);
        }
    }

    @Test
    void parallelSplitJoinWithMidBranchPauseResumeAndStop() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "flow-parallel");

        // --- Part 1: Mid-branch PAUSE -> RESUME across parallel Split/Join ---
        CopyOnWriteArrayList<String> run1Executed = new CopyOnWriteArrayList<>();
        CountDownLatch a2Done = new CountDownLatch(1);
        CountDownLatch b1Started = new CountDownLatch(1);
        CountDownLatch allowB1Finish = new CountDownLatch(1);
        AtomicInteger finalCount = new AtomicInteger(0);

        // --- Part 2: Mid-branch STOP across parallel Split/Join ---
        CopyOnWriteArrayList<String> run2Executed = new CopyOnWriteArrayList<>();
        CountDownLatch run2B1Started = new CountDownLatch(1);
        CountDownLatch run2AllowB1Finish = new CountDownLatch(1);
        UUID[] run1IdHolder = new UUID[1];

        try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                hostConfig(tempDir.resolve("parallel.lock"), "parallel-host", tenant),
                repo,
                node -> new LocalTaskDispatchService.TaskDispatchSpec(
                        "par-cap-" + tenant + "-" + node.key(), 4, "PURE", "q", Duration.ofSeconds(30), "{}"),
                node -> Optional.of((req, ctx) -> {
                    boolean isRun1 = req.runId().equals(run1IdHolder[0]);
                    if (isRun1) {
                        run1Executed.add(req.nodeKey());
                        if ("a2".equals(req.nodeKey())) {
                            a2Done.countDown();
                        } else if ("b1".equals(req.nodeKey())) {
                            b1Started.countDown();
                            assertTrue(allowB1Finish.await(8, TimeUnit.SECONDS));
                        } else if ("final_step".equals(req.nodeKey())) {
                            finalCount.incrementAndGet();
                        }
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    } else {
                        run2Executed.add(req.nodeKey());
                        if ("b1".equals(req.nodeKey())) {
                            run2B1Started.countDown();
                            assertTrue(run2AllowB1Finish.await(8, TimeUnit.SECONDS));
                        }
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    }
                }))) {
            host.start();

            // Run 1: Parallel PAUSE while Branch A already finished (a1 -> a2) and Branch B is inside b1!
            var run1 = createCompiledRun(repo, tenant, "par-pause-" + UUID.randomUUID(), PARALLEL_BPMN, "{}");
            run1IdHolder[0] = run1.runId();
            host.startRun(tenant, run1.runId()).get(5, TimeUnit.SECONDS);

            assertTrue(a2Done.await(8, TimeUnit.SECONDS), "Branch A should complete a2");
            assertTrue(b1Started.await(8, TimeUnit.SECONDS), "Branch B should start b1");
            awaitCondition(Duration.ofSeconds(5), () -> {
                try {
                    return scalar(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"
                            + tenant + "' AND run_id='" + run1.runId() + "' AND join_state='WAITING'") == 1L;
                } catch (Exception e) {
                    return false;
                }
            }, "Branch A should arrive at Join (WAITING) while Branch B is still executing b1");

            // Request PAUSE mid-parallel, then let b1 finish safely.
            host.requestPause(tenant, run1.runId());
            allowB1Finish.countDown();

            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run1.runId()).orElseThrow();
                return "PAUSED".equals(ov.status()) && "PAUSE".equals(ov.controlGate())
                        && host.healthSnapshot().activeLocalWorkers() == 0;
            }, "Parallel run must converge automatically to PAUSED while preserving open Split/Join");

            // Verify Branch B's b2 and final_step have NOT executed yet, and Split/Join remain resumable.
            assertFalse(run1Executed.contains("b2"), "b2 must not run while PAUSED");
            assertFalse(run1Executed.contains("final_step"), "final_step must not run while PAUSED");
            assertEquals(1L, scalar(ds, "SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run1.runId() + "' AND split_state='OPEN'"));
            assertEquals(1L, scalar(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run1.runId() + "' AND join_state='WAITING'"));

            // RESUME -> b2 runs -> Join releases -> final_step runs ONCE -> SUCCEEDED!
            host.resumeRun(tenant, run1.runId()).get(5, TimeUnit.SECONDS);
            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run1.runId()).orElseThrow();
                return "SUCCEEDED".equals(ov.status()) && "CLOSED".equals(ov.controlGate());
            }, "Parallel run must complete SUCCEEDED after RESUME");

            assertTrue(run1Executed.containsAll(List.of("a1", "a2", "b1", "b2", "final_step")));
            assertEquals(1, finalCount.get(), "Join must release final_step exactly once");
            assertTrue(run1Executed.indexOf("final_step") > run1Executed.indexOf("a2"));
            assertTrue(run1Executed.indexOf("final_step") > run1Executed.indexOf("b2"));

            // Run 2: Parallel STOP mid-branch (after Branch A arrived at Join while Branch B is inside b1) ->
            // converges to STOPPED, cancels Split & Join, never runs b2 or final_step!
            var run2 = createCompiledRun(repo, tenant, "par-stop-" + UUID.randomUUID(), PARALLEL_BPMN, "{}");
            host.startRun(tenant, run2.runId()).get(5, TimeUnit.SECONDS);

            assertTrue(run2B1Started.await(8, TimeUnit.SECONDS), "run2 b1 should start");
            awaitCondition(Duration.ofSeconds(5), () -> {
                try {
                    return scalar(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"
                            + tenant + "' AND run_id='" + run2.runId() + "' AND join_state='WAITING'") == 1L;
                } catch (Exception e) {
                    return false;
                }
            }, "run2 Branch A should arrive at Join (WAITING) while Branch B is still executing b1");
            host.requestStop(tenant, run2.runId());
            run2AllowB1Finish.countDown();

            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run2.runId()).orElseThrow();
                return "STOPPED".equals(ov.status()) && "CLOSED".equals(ov.controlGate())
                        && ov.activeTokens() == 0 && ov.activeAttempts() == 0
                        && host.healthSnapshot().activeLocalWorkers() == 0;
            }, "Parallel run must converge automatically to STOPPED");

            assertFalse(run2Executed.contains("b2"), "b2 must never run after STOP");
            assertFalse(run2Executed.contains("final_step"), "final_step must never run after STOP");
            assertEquals(1L, scalar(ds, "SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run2.runId() + "' AND split_state='CANCELLED'"));
            assertEquals(1L, scalar(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run2.runId() + "' AND join_state='CANCELLED'"));
        }
    }

    @Test
    void xorBranchWorkflowBothPathsAndMidBranchPauseResume() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "flow-xor");

        CopyOnWriteArrayList<String> passRunNodes = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> failRunNodes = new CopyOnWriteArrayList<>();
        CountDownLatch pathPassStarted = new CountDownLatch(1);
        CountDownLatch allowPathPassFinish = new CountDownLatch(1);
        UUID[] passRunIdHolder = new UUID[1];

        try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                hostConfig(tempDir.resolve("xor.lock"), "xor-host", tenant),
                repo,
                node -> new LocalTaskDispatchService.TaskDispatchSpec(
                        "xor-cap-" + tenant + "-" + node.key(), 4, "PURE", "q", Duration.ofSeconds(30), "{}"),
                node -> Optional.of((req, ctx) -> {
                    boolean isPassRun = req.runId().equals(passRunIdHolder[0]);
                    if (isPassRun) {
                        passRunNodes.add(req.nodeKey());
                        if ("decide".equals(req.nodeKey())) {
                            return new WorkerTaskHandler.TaskResult("out", "{\"score\":95}", Map.of("score", "95"));
                        }
                        if ("path_pass".equals(req.nodeKey())) {
                            pathPassStarted.countDown();
                            assertTrue(allowPathPassFinish.await(8, TimeUnit.SECONDS));
                        }
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    } else {
                        failRunNodes.add(req.nodeKey());
                        if ("decide".equals(req.nodeKey())) {
                            return new WorkerTaskHandler.TaskResult("out", "{\"score\":40}", Map.of("score", "40"));
                        }
                        return WorkerTaskHandler.TaskResult.of("out", "{}");
                    }
                }))) {
            host.start();

            // Run 1: score=95 -> XOR takes path_pass -> PAUSE mid-branch -> RESUME -> pass_followup -> SUCCEEDED!
            var passRun = createCompiledRun(repo, tenant, "xor-pass-" + UUID.randomUUID(), XOR_BPMN, "{}");
            passRunIdHolder[0] = passRun.runId();
            host.startRun(tenant, passRun.runId()).get(5, TimeUnit.SECONDS);

            assertTrue(pathPassStarted.await(8, TimeUnit.SECONDS), "XOR should route score=95 to path_pass");
            host.requestPause(tenant, passRun.runId());
            allowPathPassFinish.countDown();

            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, passRun.runId()).orElseThrow();
                return "PAUSED".equals(ov.status()) && "PAUSE".equals(ov.controlGate())
                        && host.healthSnapshot().activeLocalWorkers() == 0;
            }, "XOR pass branch must converge to PAUSED before pass_followup");

            assertEquals(List.of("decide", "path_pass"), passRunNodes);

            host.resumeRun(tenant, passRun.runId()).get(5, TimeUnit.SECONDS);
            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, passRun.runId()).orElseThrow();
                return "SUCCEEDED".equals(ov.status()) && "CLOSED".equals(ov.controlGate());
            }, "XOR pass run must finish SUCCEEDED after RESUME");

            assertEquals(List.of("decide", "path_pass", "pass_followup"), passRunNodes);
            assertFalse(passRunNodes.contains("path_fail"));

            // Run 2: score=40 -> XOR takes default path_fail -> SUCCEEDED!
            var failRun = createCompiledRun(repo, tenant, "xor-fail-" + UUID.randomUUID(), XOR_BPMN, "{}");
            host.startRun(tenant, failRun.runId()).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, failRun.runId()).orElseThrow();
                return "SUCCEEDED".equals(ov.status()) && "CLOSED".equals(ov.controlGate());
            }, "XOR default fail branch run must finish SUCCEEDED");

            assertEquals(List.of("decide", "path_fail"), failRunNodes);
            assertFalse(failRunNodes.contains("path_pass"));
            assertFalse(failRunNodes.contains("pass_followup"));
        }
    }

    @Test
    void pauseDuringPreStartDispatchedAttemptDoesNotBlockRunCompletionAfterResume() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(ds, "flow-pause-prestart");

        // Deterministically exercise abortPreStartAttemptOnGateClosed during PAUSE so attempt #1 and
        // activation #1 are persisted as CANCELLED while the step_a token is reverted to READY.
        var run = createCompiledRun(repo, tenant, "prestart-" + UUID.randomUUID(), SERIAL_BPMN, "{}");
        repo.startRun(tenant, run.runId());
        new com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator(repo).advance(tenant, run.runId());
        var res = repo.reserveCapacity(tenant, run.runId(), "prestart-cap-" + tenant, 2,
                "step_a", "PURE", java.time.Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, run.runId(), res.attemptId(), "q", "{}");
        repo.requestDrain(tenant, run.runId(), "PAUSE");
        repo.abortPreStartAttemptOnGateClosed(tenant, run.runId(), res.attemptId(), "pause-before-start");

        CopyOnWriteArrayList<String> executed = new CopyOnWriteArrayList<>();
        try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                hostConfig(tempDir.resolve("prestart.lock"), "prestart-host", tenant),
                repo,
                node -> new LocalTaskDispatchService.TaskDispatchSpec(
                        "prestart-cap-" + tenant + "-" + node.key(), 2, "PURE", "q", Duration.ofSeconds(30), "{}"),
                node -> Optional.of((req, ctx) -> {
                    executed.add(req.nodeKey());
                    return WorkerTaskHandler.TaskResult.of("out", "{}");
                }))) {
            host.start();

            // Host must abort pre-start attempt #1 (CANCELLED), preserve step_a READY token, and converge to PAUSED.
            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run.runId()).orElseThrow();
                return "PAUSED".equals(ov.status()) && "PAUSE".equals(ov.controlGate());
            }, "Pre-start paused run must converge to PAUSED");
            assertEquals(1L, scalar(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"
                    + tenant + "' AND run_id='" + run.runId() + "' AND attempt_state='CANCELLED'"));
            assertTrue(executed.isEmpty(), "No task should execute while PAUSED");

            // RESUME -> step_a re-dispatches as attempt #2 -> step_a, step_b, step_c complete -> Run MUST reach SUCCEEDED!
            host.resumeRun(tenant, run.runId()).get(5, TimeUnit.SECONDS);
            awaitCondition(Duration.ofSeconds(8), () -> {
                var ov = host.getRunOverview(tenant, run.runId()).orElseThrow();
                return "SUCCEEDED".equals(ov.status()) && "CLOSED".equals(ov.controlGate());
            }, "Run with superseded pre-pause CANCELLED attempt must converge to SUCCEEDED after RESUME");

            assertEquals(List.of("step_a", "step_b", "step_c"), executed);
        }
    }
}
