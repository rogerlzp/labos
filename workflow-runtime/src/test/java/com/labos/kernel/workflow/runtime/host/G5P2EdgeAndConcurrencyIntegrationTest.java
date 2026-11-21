package com.labos.kernel.workflow.runtime.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.labos.kernel.workflow.runtime.bridge.GatewayDeviceTaskBridge;
import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.gateway.MockTcpDeviceServer;
import com.labos.kernel.workflow.runtime.gateway.RuntimeDeviceEventBridge;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 hardening integration tests covering residual G5 A1-1 {@code correlation_key}
 * boundaries and high-concurrency multi-Run execution under out-of-order / duplicate / late
 * TCP device events.
 */
class G5P2EdgeAndConcurrencyIntegrationTest {

    @TempDir
    Path tempDir;

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

    private static UUID createTenant(JdbcTemplate jdbc, String prefix) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_runtime.tenant(tenant_id, tenant_key, display_name)
                VALUES (?, ?, ?)
                """, tenant, prefix + "-" + tenant, prefix);
        return tenant;
    }

    private static WorkflowRuntimeRepository.CreatedRun createCompiledRun(
            WorkflowRuntimeRepository repo, UUID tenant, String defKey, String bpmnXml) {
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(defKey, "1", bpmnXml, Map.of()));
        return repo.createRun(
                tenant,
                "req-" + UUID.randomUUID(),
                "CREATE",
                "{\"k\":\"" + defKey + "\"}",
                plan.definitionKey(),
                plan.definitionVersion(),
                HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),
                GraphPlanCodec.toJson(plan),
                "{}",
                "s");
    }

    private static EmbeddedWorkflowRuntimeHost.Config waitOnlyHostConfig(
            Path lockFile, String hostId, UUID tenant) {
        return new EmbeddedWorkflowRuntimeHost.Config(
                lockFile, hostId, 4, 32, 8, 2, 64, 50,
                Duration.ofMillis(25), 16,
                List.of(),
                TcpDeviceConnection.Config.defaults(),
                tenant);
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier cond, String desc)
            throws InterruptedException {
        awaitCondition(timeout, cond, () -> desc);
    }

    private static void awaitCondition(
            Duration timeout, java.util.function.BooleanSupplier cond, java.util.function.Supplier<String> desc)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(15);
        }
        assertTrue(cond.getAsBoolean(), () -> "Condition not satisfied within " + timeout + ": " + desc.get());
    }

    private static String runStatus(JdbcTemplate jdbc, UUID tenant, UUID runId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                String.class, tenant, runId);
    }

    private static List<String> waitStatesForRun(JdbcTemplate jdbc, UUID tenant, UUID runId) {
        return jdbc.queryForList("""
                SELECT wait_state
                  FROM workflow_runtime.wait_registration
                 WHERE tenant_id=? AND run_id=?
                 ORDER BY created_at ASC
                """, String.class, tenant, runId);
    }

    private static SQLException findSqlException(Throwable error) {
        for (Throwable cur = error; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException sqlEx) {
                return sqlEx;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // G5 A1-1 Case 1: Two concurrent Runs sharing identical correlation_key under the same tenant
    // ---------------------------------------------------------------------------------------------

    @Test
    void a1_twoConcurrentRunsSameTenantSameCorrelationKeyRouteStrictlyWithoutCrossLeakage() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(jdbc, "g5p2-a1-multi");

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="shared.qc.batch"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                waitOnlyHostConfig(tempDir.resolve("a1-multi.lock"), "a1-multi-host", tenant),
                repo,
                node -> { throw new AssertionError("No tasks expected"); },
                node -> Optional.empty())) {
            host.start();

            UUID runA = createCompiledRun(repo, tenant, "a1-run-a-" + UUID.randomUUID(), xml).runId();
            UUID runB = createCompiledRun(repo, tenant, "a1-run-b-" + UUID.randomUUID(), xml).runId();
            host.startRun(tenant, runA).get(5, TimeUnit.SECONDS);
            host.startRun(tenant, runB).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () ->
                    List.of("ACTIVE").equals(waitStatesForRun(jdbc, tenant, runA))
                            && List.of("ACTIVE").equals(waitStatesForRun(jdbc, tenant, runB)),
                    "Both concurrent Runs should register ACTIVE Waits for shared.qc.batch");

            // Signal ONLY runA; runB must remain ACTIVE and RUNNING with zero cross-Run leakage.
            var resA = repo.receiveWaitInbox(
                    tenant, runA, "http-api", "sig-runA-1", "shared.qc.batch", "{\"target\":\"A\"}");
            assertEquals(WaitOutcomes.COMPLETED, resA.outcome());
            host.wakeRunIfRunning(tenant, runA).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () -> "SUCCEEDED".equals(runStatus(jdbc, tenant, runA)),
                    "runA should complete SUCCEEDED after receiving its Wait signal");
            assertEquals(List.of("SATISFIED"), waitStatesForRun(jdbc, tenant, runA));

            assertEquals("RUNNING", runStatus(jdbc, tenant, runB),
                    "Signalling runA must NOT satisfy or complete runB");
            assertEquals(List.of("ACTIVE"), waitStatesForRun(jdbc, tenant, runB),
                    "runB's ACTIVE Wait must remain untouched");

            // Now signal runB cleanly to completion.
            var resB = repo.receiveWaitInbox(
                    tenant, runB, "http-api", "sig-runB-1", "shared.qc.batch", "{\"target\":\"B\"}");
            assertEquals(WaitOutcomes.COMPLETED, resB.outcome());
            host.wakeRunIfRunning(tenant, runB).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () -> "SUCCEEDED".equals(runStatus(jdbc, tenant, runB)),
                    "runB should complete SUCCEEDED after its own Wait signal");
            assertEquals(List.of("SATISFIED"), waitStatesForRun(jdbc, tenant, runB));

            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id=?",
                    Long.class, tenant));
            assertEquals(0L, host.healthSnapshot().dispatchStats().cycleErrors());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // G5 A1-1 Case 2: Same Run sequentially reuses correlation_key after SATISFIED and EXPIRED
    // ---------------------------------------------------------------------------------------------

    @Test
    void a1_sameRunSequentialWaitsReuseSameCorrelationKeyAfterSatisfiedAndAfterExpired() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(jdbc, "g5p2-a1-seq");

        String seqXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_seq" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="seq.reused.key"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:receiveTask id="w2">
                      <bpmn:extensionElements><labos:wait correlationKey="seq.reused.key"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="w2"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="w2" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        String expXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_exp" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="exp.reused.key"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>PT0.25S</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:receiveTask id="w2">
                      <bpmn:extensionElements><labos:wait correlationKey="exp.reused.key"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:endEvent id="e_recovered"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f_norm" sourceRef="w1" targetRef="e_ok"/>
                    <bpmn:sequenceFlow id="f_to" sourceRef="b1" targetRef="w2"/>
                    <bpmn:sequenceFlow id="f_rec" sourceRef="w2" targetRef="e_recovered"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                waitOnlyHostConfig(tempDir.resolve("a1-seq.lock"), "a1-seq-host", tenant),
                repo,
                node -> { throw new AssertionError("No tasks expected"); },
                node -> Optional.empty())) {
            host.start();

            // Subcase A: second Wait with same correlation_key after first SATISFIED
            UUID seqRun = createCompiledRun(repo, tenant, "a1-seq-sat-" + UUID.randomUUID(), seqXml).runId();
            host.startRun(tenant, seqRun).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () ->
                    List.of("ACTIVE").equals(waitStatesForRun(jdbc, tenant, seqRun)),
                    "w1 should become ACTIVE");
            assertEquals(WaitOutcomes.COMPLETED,
                    repo.receiveWaitInbox(tenant, seqRun, "http-api", "seq-msg-1", "seq.reused.key", "{\"step\":1}").outcome());
            host.wakeRunIfRunning(tenant, seqRun).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () ->
                    List.of("SATISFIED", "ACTIVE").equals(waitStatesForRun(jdbc, tenant, seqRun)),
                    "w2 with identical correlation_key must register cleanly once w1 is SATISFIED");
            assertEquals(WaitOutcomes.COMPLETED,
                    repo.receiveWaitInbox(tenant, seqRun, "http-api", "seq-msg-2", "seq.reused.key", "{\"step\":2}").outcome());
            host.wakeRunIfRunning(tenant, seqRun).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () -> "SUCCEEDED".equals(runStatus(jdbc, tenant, seqRun)),
                    "Sequential reused-key Run should reach SUCCEEDED");
            assertEquals(List.of("SATISFIED", "SATISFIED"), waitStatesForRun(jdbc, tenant, seqRun));

            // Subcase B: timeout Wait EXPIRED -> downstream Wait reusing same correlation_key
            UUID expRun = createCompiledRun(repo, tenant, "a1-seq-exp-" + UUID.randomUUID(), expXml).runId();
            host.startRun(tenant, expRun).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () ->
                    List.of("EXPIRED", "ACTIVE").equals(waitStatesForRun(jdbc, tenant, expRun)),
                    "After w1 expires, w2 with identical correlation_key must become ACTIVE");
            assertEquals(WaitOutcomes.COMPLETED,
                    repo.receiveWaitInbox(tenant, expRun, "http-api", "exp-msg-after-to", "exp.reused.key", "{\"recovered\":true}").outcome());
            host.wakeRunIfRunning(tenant, expRun).get(5, TimeUnit.SECONDS);

            awaitCondition(Duration.ofSeconds(8), () -> "SUCCEEDED".equals(runStatus(jdbc, tenant, expRun)),
                    "Timeout-fallback reused-key Run should reach SUCCEEDED");
            assertEquals(List.of("EXPIRED", "SATISFIED"), waitStatesForRun(jdbc, tenant, expRun));
            assertEquals(2L, jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.wait_registration
                     WHERE tenant_id=? AND run_id=? AND correlation_key='exp.reused.key'
                    """, Long.class, tenant, expRun));
            assertEquals(0L, host.healthSnapshot().dispatchStats().cycleErrors());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // G5 A1-1 Case 3: Same Run parallel concurrent ACTIVE Waits with identical correlation_key
    // ---------------------------------------------------------------------------------------------

    @Test
    void a1_sameRunConcurrentParallelWaitsWithSameCorrelationKeyFailClosedWithoutCorruptingFirst() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(jdbc, "g5p2-a1-dup");

        String parallelWaitXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_dup" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:parallelGateway id="split" gatewayDirection="Diverging"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="dup.active.key"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:receiveTask id="w2">
                      <bpmn:extensionElements><labos:wait correlationKey="dup.active.key"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:parallelGateway id="join" gatewayDirection="Converging"/>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f0" sourceRef="s" targetRef="split"/>
                    <bpmn:sequenceFlow id="fa" sourceRef="split" targetRef="w1"/>
                    <bpmn:sequenceFlow id="fb" sourceRef="split" targetRef="w2"/>
                    <bpmn:sequenceFlow id="faj" sourceRef="w1" targetRef="join"/>
                    <bpmn:sequenceFlow id="fbj" sourceRef="w2" targetRef="join"/>
                    <bpmn:sequenceFlow id="fe" sourceRef="join" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("a1-dup-" + UUID.randomUUID(), "1", parallelWaitXml, Map.of()));
        String planJson = GraphPlanCodec.toJson(plan);
        UUID runId = repo.createRun(
                tenant,
                "req-" + UUID.randomUUID(),
                "CREATE",
                "{}",
                plan.definitionKey(),
                plan.definitionVersion(),
                HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),
                planJson,
                "{}",
                "s").runId();
        repo.startRun(tenant, runId);

        // Advance s -> split -> (w1, w2 READY tokens) deterministically before invoking registerGraphWait directly.
        UUID sToken = jdbc.queryForObject(
                "SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='s'",
                UUID.class, tenant, runId);
        repo.advanceGraphToken(tenant, runId, sToken, "s", "SEQUENCE", List.of("split"), null);
        UUID splitToken = jdbc.queryForObject(
                "SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='split'",
                UUID.class, tenant, runId);
        repo.advanceGraphToken(tenant, runId, splitToken, "split", "SPLIT", List.of("w1", "w2"), null);

        UUID tok1 = jdbc.queryForObject(
                "SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='w1'",
                UUID.class, tenant, runId);
        UUID tok2 = jdbc.queryForObject(
                "SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='w2'",
                UUID.class, tenant, runId);
        GraphPlan.WaitSpec w1Spec = plan.nodes().stream().filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        GraphPlan.WaitSpec w2Spec = plan.nodes().stream().filter(n -> "w2".equals(n.key())).findFirst().orElseThrow().waitSpec();

        var first = repo.registerGraphWait(tenant, runId, tok1, "w1", w1Spec);
        assertEquals(WaitOutcomes.COMPLETED, first.outcome());

        // Second concurrent ACTIVE Wait on identical correlation_key inside the same Run MUST fail closed (23505)
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                repo.registerGraphWait(tenant, runId, tok2, "w2", w2Spec));
        SQLException sqlEx = findSqlException(ex);
        assertNotNull(sqlEx, "Expected SQLException in causal chain: " + ex);
        assertEquals("23505", sqlEx.getSQLState(), "Expected unique_violation 23505: " + sqlEx);
        assertTrue(sqlEx.getMessage() != null && sqlEx.getMessage().contains("wait_active_correlation_uq"),
                "Expected wait_active_correlation_uq constraint violation: " + sqlEx.getMessage());

        // Verify rollback left tok2 and first ACTIVE Wait completely uncorrupted
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                Long.class, tenant, tok2));
        assertEquals("READY", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, tok2));
        assertEquals(List.of("ACTIVE"), waitStatesForRun(jdbc, tenant, runId));

        // Satisfy w1 -> now w2 can register cleanly and converge through join to SUCCEEDED
        assertEquals(WaitOutcomes.COMPLETED,
                repo.receiveWaitInbox(tenant, runId, "http-api", "dup-resolve-1", "dup.active.key", "{\"ok\":1}").outcome());
        assertEquals(WaitOutcomes.COMPLETED, repo.registerGraphWait(tenant, runId, tok2, "w2", w2Spec).outcome());
        assertEquals(WaitOutcomes.COMPLETED,
                repo.receiveWaitInbox(tenant, runId, "http-api", "dup-resolve-2", "dup.active.key", "{\"ok\":2}").outcome());

        new GraphCoordinator(repo).advance(tenant, runId);
        assertEquals("SUCCEEDED", runStatus(jdbc, tenant, runId));
        assertEquals(List.of("SATISFIED", "SATISFIED"), waitStatesForRun(jdbc, tenant, runId));
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-Run Concurrency (8 Runs across Serial, Parallel, MESSAGE Wait, TIMER Wait) + Chaotic TCP
    // ---------------------------------------------------------------------------------------------

    @Test
    void multiRunConcurrencyWithOutOfOrderDuplicateAndLateDeviceEventsConvergesCleanly() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = createTenant(jdbc, "g5p2-concurrent-ooo");

        String tag = tenant.toString().substring(0, 6);
        String devA = "ooo-devA-" + tag;
        String devB = "ooo-devB-" + tag;

        ScheduledExecutorService faultScheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "ooo-device-fault-injector");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger cmdSeq = new AtomicInteger(0);
        AtomicInteger pendingScheduledFrames = new AtomicInteger(0);
        AtomicInteger sentFrames = new AtomicInteger(0);
        AtomicInteger bridgeHandledFrames = new AtomicInteger(0);

        try (MockTcpDeviceServer serverA = new MockTcpDeviceServer(devA);
             MockTcpDeviceServer serverB = new MockTcpDeviceServer(devB)) {

            attachChaoticHandler(serverA, devA, faultScheduler, cmdSeq, pendingScheduledFrames, sentFrames);
            attachChaoticHandler(serverB, devB, faultScheduler, cmdSeq, pendingScheduledFrames, sentFrames);

            List<TcpDeviceConnection.Endpoint> endpoints = List.of(serverA.endpoint(), serverB.endpoint());
            Map<Long, String> resourceMap = Map.of(501L, devA, 502L, devB);
            GatewayDeviceTaskBridge bridge = new GatewayDeviceTaskBridge(
                    repo, Set.of(devA, devB), resourceMap, Duration.ofSeconds(15), Duration.ofMillis(20));

            TcpDeviceConnection.Config gatewayCfg = new TcpDeviceConnection.Config(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    Duration.ofMillis(50),
                    Duration.ofMillis(200),
                    8192,
                    256);

            EmbeddedWorkflowRuntimeHost.Config hostCfg = new EmbeddedWorkflowRuntimeHost.Config(
                    tempDir.resolve("concurrent-ooo.lock"),
                    "concurrent-ooo-host",
                    6, 64, 12, 4, 128, 100,
                    Duration.ofMillis(25), 32,
                    endpoints, gatewayCfg, tenant);

            try (EmbeddedWorkflowRuntimeHost host = new EmbeddedWorkflowRuntimeHost(
                    hostCfg, repo, bridge.specResolver(), bridge.handlerResolver(),
                    notification -> bridgeHandledFrames.incrementAndGet())) {
                host.start();
                assertTrue(host.deviceGateway().awaitOnline(devA, Duration.ofSeconds(5)), devA + " offline");
                assertTrue(host.deviceGateway().awaitOnline(devB, Duration.ofSeconds(5)), devB + " offline");

                List<UUID> allRuns = new ArrayList<>();
                // Runs 0..1: Serial devA -> devB (2 cmds each = 4)
                for (int i = 0; i < 2; i++) {
                    UUID r = createCompiledRun(repo, tenant, "ooo-serial-" + i + "-" + tag, serialDeviceBpmn()).runId();
                    allRuns.add(r);
                }
                // Runs 2..3: Parallel split (devA || devB) -> join -> devA (3 cmds each = 6)
                for (int i = 0; i < 2; i++) {
                    UUID r = createCompiledRun(repo, tenant, "ooo-parallel-" + i + "-" + tag, parallelDeviceBpmn()).runId();
                    allRuns.add(r);
                }
                // Runs 4..5: devA -> MESSAGE Wait (same correlationKey!) -> devB (2 cmds each = 4)
                List<UUID> msgWaitRuns = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    UUID r = createCompiledRun(repo, tenant, "ooo-msgwait-" + i + "-" + tag, msgWaitDeviceBpmn()).runId();
                    msgWaitRuns.add(r);
                    allRuns.add(r);
                }
                // Runs 6..7: devA -> TIMER Wait (PT0.3S) -> devB (2 cmds each = 4)
                for (int i = 0; i < 2; i++) {
                    UUID r = createCompiledRun(repo, tenant, "ooo-timerwait-" + i + "-" + tag, timerWaitDeviceBpmn()).runId();
                    allRuns.add(r);
                }

                // Start all 8 Runs simultaneously
                for (UUID r : allRuns) {
                    host.startRun(tenant, r).get(5, TimeUnit.SECONDS);
                }

                // Wait until both MESSAGE Wait runs have registered ACTIVE Waits, then fire concurrent signals + duplicate retries
                awaitCondition(Duration.ofSeconds(15), () ->
                        List.of("ACTIVE").equals(waitStatesForRun(jdbc, tenant, msgWaitRuns.get(0)))
                                && List.of("ACTIVE").equals(waitStatesForRun(jdbc, tenant, msgWaitRuns.get(1))),
                        "Both concurrent MESSAGE Wait Runs should become ACTIVE after first device step");

                var signalExec = Executors.newFixedThreadPool(2);
                try {
                    Future<Void> f0 = signalExec.submit(() -> {
                        UUID r = msgWaitRuns.get(0);
                        assertEquals(WaitOutcomes.COMPLETED, repo.receiveWaitInbox(
                                tenant, r, "http-api", "ooo-w-0", "concurrent.qc", "{\"run\":0}").outcome());
                        assertEquals(WaitOutcomes.ALREADY_COMPLETED, repo.receiveWaitInbox(
                                tenant, r, "http-api", "ooo-w-0", "concurrent.qc", "{\"run\":0}").outcome());
                        host.wakeRunIfRunning(tenant, r).get(5, TimeUnit.SECONDS);
                        return null;
                    });
                    Future<Void> f1 = signalExec.submit(() -> {
                        UUID r = msgWaitRuns.get(1);
                        assertEquals(WaitOutcomes.COMPLETED, repo.receiveWaitInbox(
                                tenant, r, "http-api", "ooo-w-1", "concurrent.qc", "{\"run\":1}").outcome());
                        assertEquals(WaitOutcomes.ALREADY_COMPLETED, repo.receiveWaitInbox(
                                tenant, r, "http-api", "ooo-w-1", "concurrent.qc", "{\"run\":1}").outcome());
                        host.wakeRunIfRunning(tenant, r).get(5, TimeUnit.SECONDS);
                        return null;
                    });
                    f0.get(10, TimeUnit.SECONDS);
                    f1.get(10, TimeUnit.SECONDS);
                } finally {
                    signalExec.shutdownNow();
                }

                // Wait for all 8 Runs to reach SUCCEEDED AND all chaotic out-of-order late frames to settle
                awaitCondition(Duration.ofSeconds(30), () -> {
                    Integer done = jdbc.queryForObject(
                            "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id=? AND status='SUCCEEDED'",
                            Integer.class, tenant);
                    return done != null && done == 8;
                }, () -> "All 8 concurrent Runs must converge to SUCCEEDED; statuses=" + jdbc.queryForList(
                        "SELECT run_id::text||':'||status FROM workflow_runtime.workflow_run WHERE tenant_id=?",
                        String.class, tenant));

                awaitCondition(Duration.ofSeconds(10), () ->
                        pendingScheduledFrames.get() == 0
                                && bridgeHandledFrames.get() >= sentFrames.get(),
                        "All scheduled out-of-order/duplicate/late device frames must finish delivery");

                awaitCondition(Duration.ofSeconds(5), () -> {
                    Long pendingInbox = jdbc.queryForObject("""
                            SELECT count(*) FROM workflow_runtime.inbox_receipt
                             WHERE tenant_id=? AND receipt_state IN ('RECEIVED', 'PROCESSING')
                            """, Long.class, tenant);
                    return pendingInbox != null && pendingInbox == 0L;
                }, "All inbox_receipt rows must settle");

                // Verify strict transactional & physical-side-effect invariants
                assertEquals(0L, host.healthSnapshot().dispatchStats().cycleErrors(),
                        "Zero dispatch cycle errors expected");
                assertEquals(18, serverA.receivedCommands().size() + serverB.receivedCommands().size(),
                        "Exactly 18 physical commands across 8 Runs (zero duplicate resend)");
                assertEquals(18L, jdbc.queryForObject(
                        "SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_state='SUCCEEDED'",
                        Long.class, tenant));
                assertEquals(18L, jdbc.queryForObject(
                        "SELECT count(distinct activation_id) FROM workflow_runtime.command_ledger WHERE tenant_id=?",
                        Long.class, tenant));

                long processedInbox = jdbc.queryForObject(
                        "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND receipt_state='PROCESSED'",
                        Long.class, tenant);
                long rejectedInbox = jdbc.queryForObject(
                        "SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND receipt_state='REJECTED'",
                        Long.class, tenant);
                assertTrue(processedInbox >= 18L, "Expected >= 18 PROCESSED inbox receipts, got " + processedInbox);
                assertTrue(rejectedInbox >= 18L, "Expected >= 18 REJECTED out-of-order/late receipts, got " + rejectedInbox);

                assertEquals(0L, jdbc.queryForObject(
                        "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation WHERE tenant_id=?",
                        Long.class, tenant));
                assertEquals(0L, jdbc.queryForObject(
                        "SELECT count(*) FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_state='ACTIVE'",
                        Long.class, tenant));
                assertEquals(0L, jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.execution_token
                         WHERE tenant_id=? AND token_state IN ('READY','RESERVED','RUNNING','WAITING')
                        """, Long.class, tenant));
                assertEquals(0L, jdbc.queryForObject(
                        "SELECT coalesce(sum(reserved_count), 0) FROM workflow_runtime.scheduler_capacity WHERE tenant_id=?",
                        Long.class, tenant));
            }
        } finally {
            faultScheduler.shutdownNow();
        }
    }

    private static void attachChaoticHandler(
            MockTcpDeviceServer server,
            String deviceId,
            ScheduledExecutorService scheduler,
            AtomicInteger cmdSeq,
            AtomicInteger pendingScheduledFrames,
            AtomicInteger sentFrames) {
        server.setCommandHandler((cmdNode, session) -> {
            String runId = cmdNode.get("runId").asText();
            String cmdId = cmdNode.get("commandId").asText();
            int mode = cmdSeq.getAndIncrement() % 3;

            switch (mode) {
                case 0 -> {
                    // Immediate SUCCEEDED, then late RUNNING (rejected), then exact duplicate SUCCEEDED (replay)
                    safeSend(session, deviceId, runId, cmdId, "msg-ok-" + cmdId,
                            "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-0", "{\"mode\":0}", sentFrames);
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 20, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-late-run-" + cmdId,
                                    "PROGRESS", "RUNNING", "seq-0r", "{\"late\":true}", sentFrames));
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 35, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-ok-" + cmdId,
                                    "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-0", "{\"mode\":0}", sentFrames));
                }
                case 1 -> {
                    // Immediate RUNNING, then out-of-order ACKNOWLEDGED (rejected), then SUCCEEDED,
                    // exact duplicate SUCCEEDED (replay), and distinct late SUCCEEDED (rejected)
                    safeSend(session, deviceId, runId, cmdId, "msg-run-" + cmdId,
                            "EXECUTING", "RUNNING", "seq-1a", "{\"mode\":1}", sentFrames);
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 15, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-late-ack-" + cmdId,
                                    "RECEIVED_ACK", "ACKNOWLEDGED", "seq-1b", "{\"ooo\":true}", sentFrames));
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 30, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-ok-" + cmdId,
                                    "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-1c", "{\"mode\":1}", sentFrames));
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 45, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-ok-" + cmdId,
                                    "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-1c", "{\"mode\":1}", sentFrames));
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 55, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-extra-ok-" + cmdId,
                                    "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-1d", "{\"extra\":true}", sentFrames));
                }
                default -> {
                    // Delayed interleaved RUNNING -> SUCCEEDED -> late RUNNING
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 35, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-run-" + cmdId,
                                    "EXECUTING", "RUNNING", "seq-2a", "{\"mode\":2}", sentFrames));
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 70, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-ok-" + cmdId,
                                    "DEVICE_COMMAND_RESULT", "SUCCEEDED", "seq-2b", "{\"mode\":2}", sentFrames));
                    scheduleEvent(server, scheduler, pendingScheduledFrames, 90, () ->
                            safeSend(server.activeSession(), deviceId, runId, cmdId, "msg-late-run-" + cmdId,
                                    "PROGRESS", "RUNNING", "seq-2c", "{\"late\":true}", sentFrames));
                }
            }
        });
    }

    private static void safeSend(
            MockTcpDeviceServer.ClientSession session,
            String deviceId,
            String runId,
            String commandId,
            String sourceMessageId,
            String messageType,
            String targetState,
            String deviceSequence,
            String outcomeJson,
            AtomicInteger sentFrames) {
        if (session == null) return;
        try {
            session.sendDeviceEvent(
                    deviceId, runId, commandId, sourceMessageId,
                    messageType, targetState, deviceSequence, outcomeJson);
            sentFrames.incrementAndGet();
        } catch (Exception ignored) {
        }
    }

    private static void scheduleEvent(
            MockTcpDeviceServer server,
            ScheduledExecutorService scheduler,
            AtomicInteger pendingScheduledFrames,
            long delayMs,
            Runnable task) {
        pendingScheduledFrames.incrementAndGet();
        try {
            scheduler.schedule(() -> {
                try {
                    if (server.activeSession() != null) {
                        task.run();
                    }
                } finally {
                    pendingScheduledFrames.decrementAndGet();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            pendingScheduledFrames.decrementAndGet();
        }
    }

    private static String serialDeviceBpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p_ser" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:task id="t1" name="Dispense" protocolName="Dispense" resource="501" methodParams="{&quot;vol&quot;:50}"/>
                    <bpmn:task id="t2" name="Spin" protocolName="Spin" resource="502" methodParams="{&quot;rpm&quot;:1200}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="t2" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String parallelDeviceBpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p_par" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:parallelGateway id="sp" gatewayDirection="Diverging"/>
                    <bpmn:task id="pa" name="Dispense" protocolName="Dispense" resource="501" methodParams="{}"/>
                    <bpmn:task id="pb" name="Spin" protocolName="Spin" resource="502" methodParams="{}"/>
                    <bpmn:parallelGateway id="jn" gatewayDirection="Converging"/>
                    <bpmn:task id="pf" name="Mix" protocolName="Mix" resource="501" methodParams="{}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f0" sourceRef="s" targetRef="sp"/>
                    <bpmn:sequenceFlow id="fa" sourceRef="sp" targetRef="pa"/>
                    <bpmn:sequenceFlow id="fb" sourceRef="sp" targetRef="pb"/>
                    <bpmn:sequenceFlow id="faj" sourceRef="pa" targetRef="jn"/>
                    <bpmn:sequenceFlow id="fbj" sourceRef="pb" targetRef="jn"/>
                    <bpmn:sequenceFlow id="fj" sourceRef="jn" targetRef="pf"/>
                    <bpmn:sequenceFlow id="fe" sourceRef="pf" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String msgWaitDeviceBpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_mw" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:task id="t1" name="Dispense" protocolName="Dispense" resource="501" methodParams="{}"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="concurrent.qc"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:task id="t2" name="Spin" protocolName="Spin" resource="502" methodParams="{}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="w1" targetRef="t2"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="t2" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String timerWaitDeviceBpmn() {
        return """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p_tw" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:task id="t1" name="Dispense" protocolName="Dispense" resource="501" methodParams="{}"/>
                    <bpmn:intermediateCatchEvent id="tm1">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>PT0.3S</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:task id="t2" name="Spin" protocolName="Spin" resource="502" methodParams="{}"/>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="tm1"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="tm1" targetRef="t2"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="t2" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
