package com.labos.kernel.workflow.runtime.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.FaultInjectingTransactionManager;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI-4 G5 independent acceptance suite integrated onto G5: schema/index catalog audits,
 * GraphPlan/Codec v1+v2 black-box contract checks, correlation uniqueness bidirectional check,
 * DR4 Run-lock concurrency proof (PAUSE/STOP/FAILURE vs registerGraphWait), R6 two-phase commit
 * fault injection (ABORT_BEFORE_COMMIT &amp; LOST_RESULT_AFTER_COMMIT), and pure-Wait E2E settlement.
 *
 * <p>Requires {@code RUN_RUNTIME_PG_IT=1} and an initialized PostgreSQL 17 schema
 * ({@code 001_init.sql + 003_g5_wait_correlation.sql + 004_g5_wait_inbox_idx.sql + 002_schema_contract.sql}).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class G5AcceptanceContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static DriverManagerDataSource ds() {
        return dsWithApp(null);
    }

    private static DriverManagerDataSource dsWithApp(String applicationName) {
        String url = "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                + "/" + env("PGDATABASE", "runtime_test");
        if (applicationName != null && !applicationName.isBlank()) {
            url = url + "?ApplicationName=" + applicationName;
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url, env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static void assumePg() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> queryAll(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = ds().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private long count(String sql) throws Exception {
        List<String> rows = queryAll(sql);
        return rows.isEmpty() ? 0 : Long.parseLong(rows.get(0));
    }

    private static GraphPlan compileWaitWithTimeoutPlan(String defKey, String corrKey, String durationIso) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1" name="Await Signal">
                      <bpmn:extensionElements><labos:wait correlationKey="%s"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>%s</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:endEvent id="e_to"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f_ok" sourceRef="w1" targetRef="e_ok"/>
                    <bpmn:sequenceFlow id="f_to" sourceRef="b1" targetRef="e_to"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(corrKey, durationIso);
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(defKey, "1", xml, Map.of()));
    }

    private static GraphPlan.Node findNode(GraphPlan plan, String nodeKey) {
        return plan.nodes().stream()
                .filter(n -> nodeKey.equals(n.key()))
                .findFirst()
                .orElseThrow();
    }

    private static UUID registerQaTenant(JdbcTemplate jdbc, String suffix) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_runtime.tenant(tenant_id, tenant_key, display_name)
                VALUES (?, ?, ?)
                """, tenant, "g5qa-" + suffix + "-" + tenant, "g5qa-" + suffix);
        return tenant;
    }

    private record StartedWaitRun(UUID runId, UUID waitTokenId, GraphPlan.WaitSpec waitSpec) {}

    private static StartedWaitRun createStartedRunAtWait(
            WorkflowRuntimeRepository repo, JdbcTemplate jdbc, UUID tenant,
            String defPrefix, String corrKey, String durationIso) throws Exception {
        GraphPlan plan = compileWaitWithTimeoutPlan(defPrefix + "-" + UUID.randomUUID(), corrKey, durationIso);
        String planJson = GraphPlanCodec.toJson(plan);
        var created = repo.createRun(tenant, "g5qa-req-" + UUID.randomUUID(), "CREATE",
                "{\"k\":\"" + plan.definitionKey() + "\"}",
                plan.definitionKey(), plan.definitionVersion(), sha256(planJson),
                plan.compilerVersion(), planJson, "{}", "s");
        repo.startRun(tenant, created.runId());
        UUID startTokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key='s'
                """, UUID.class, tenant, created.runId());
        repo.advanceGraphToken(tenant, created.runId(), startTokenId, "s",
                "SEQUENCE", List.of("w1"), null);
        UUID waitTokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key='w1' AND token_state='READY'
                """, UUID.class, tenant, created.runId());
        return new StartedWaitRun(created.runId(), waitTokenId, findNode(plan, "w1").waitSpec());
    }

    // =============================================================================================
    // Group A — Schema, Codec & Correlation Index Audits
    // =============================================================================================

    @Test
    @DisplayName("A1 [AUDIT] wait_registration columns required by frozen G5 exist")
    void a1_waitRegistrationColumnsExist() throws Exception {
        assumePg();
        List<String> cols = queryAll("SELECT column_name FROM information_schema.columns WHERE table_schema='workflow_runtime' AND table_name='wait_registration'");
        for (String required : List.of("wait_id", "run_id", "activation_id", "wait_state", "wait_kind",
                "correlation_key", "deadline_at", "version", "continuation", "created_at", "closed_at")) {
            assertTrue(cols.contains(required), "frozen G5 requires wait_registration." + required);
        }
        assertFalse(cols.contains("attempt_id"), "pure Wait must not add a direct attempt_id column to wait_registration");
    }

    @Test
    @DisplayName("A2 [AUDIT] wait_registration index definitions match frozen §2/§11.1")
    void a2_waitIndexDefinitions() throws Exception {
        assumePg();
        List<String> due = queryAll("SELECT indexdef FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='wait_due_idx'");
        assertEquals(1, due.size(), "frozen §2/§8.1 reuses wait_due_idx");
        assertTrue(due.get(0).contains("(deadline_at)"), "wait_due_idx must be on deadline_at: " + due.get(0));
        assertTrue(due.get(0).contains("wait_state = 'ACTIVE'"), "wait_due_idx must be partial on ACTIVE: " + due.get(0));

        List<String> perActivation = queryAll("SELECT indexdef FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='wait_one_active_per_activation_uq'");
        assertEquals(1, perActivation.size(), "frozen §11.1 keeps the per-activation active Wait constraint");
        assertTrue(perActivation.get(0).startsWith("CREATE UNIQUE INDEX"), "per-activation Wait index must be UNIQUE: " + perActivation.get(0));
        assertTrue(perActivation.get(0).contains("(tenant_id, activation_id)"),
                "per-activation Wait index column order: " + perActivation.get(0));
        assertTrue(perActivation.get(0).contains("wait_state = 'ACTIVE'"), "per-activation Wait index partial predicate: " + perActivation.get(0));
    }

    @Test
    @DisplayName("A3 [AUDIT] wait_state CHECK constraint admits the four frozen states")
    void a3_waitStateCheckConstraint() throws Exception {
        assumePg();
        List<String> defs = queryAll("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum = ANY(c.conkey)
                 WHERE c.conrelid='workflow_runtime.wait_registration'::regclass
                   AND c.contype='c' AND a.attname='wait_state'
                """);
        assertEquals(1, defs.size(), "exactly one CHECK constraint on wait_registration.wait_state");
        String def = defs.get(0);
        for (String state : List.of("ACTIVE", "SATISFIED", "EXPIRED", "CANCELLED")) {
            assertTrue(def.contains("'" + state + "'"), "frozen §3.1 requires wait_state " + state + " in " + def);
        }
    }

    @Test
    @DisplayName("A4 [AUDIT] inbox_receipt columns and UNIQUE identity match frozen §6/§7")
    void a4_inboxColumnsAudit() throws Exception {
        assumePg();
        List<String> cols = queryAll("SELECT column_name FROM information_schema.columns WHERE table_schema='workflow_runtime' AND table_name='inbox_receipt'");
        assertTrue(cols.contains("received_at"), "frozen §6.4 arbitrates by inbox received_at");
        assertTrue(cols.contains("message_type"), "frozen §6.1 fixes message_type=WORKFLOW_WAIT_SIGNAL");
        assertTrue(cols.contains("source_message_id"), "frozen §7 Inbox identity is (tenant, source, source_message_id)");
        assertTrue(cols.contains("payload_hash"), "frozen §7 payload-hash conflict => PROTOCOL_VIOLATION");

        List<String> uniqueDefs = queryAll("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                 WHERE c.conrelid='workflow_runtime.inbox_receipt'::regclass AND c.contype='u'
                """);
        assertTrue(uniqueDefs.stream().anyMatch(d -> d.contains("tenant_id")
                        && d.contains("source") && d.contains("source_message_id")),
                "frozen §7 requires UNIQUE (tenant_id, source, source_message_id) on inbox_receipt: " + uniqueDefs);
    }

    @Test
    @DisplayName("A5 [AUDIT] GraphPlan retains all v1 record fields required by frozen §4.1")
    void a5_graphPlanRetainsV1RecordFields() {
        List<String> components = new ArrayList<>();
        for (var rc : GraphPlan.class.getRecordComponents()) components.add(rc.getName());
        assertTrue(components.containsAll(List.of("schemaVersion", "compilerVersion", "definitionKey",
                        "definitionVersion", "bpmnSha256", "nodes", "edges")),
                "frozen §4.1 requires v1 GraphPlan history fields to survive the v2 upgrade");
    }

    @Test
    @DisplayName("A5b [BEHAVIOR] GraphPlanCodec v1 + real v2 WAIT+TIMEOUT round-trip & fail-closed checks")
    void a5b_codecV1AndV2WaitRoundTripAndFailClosed() {
        String v1Xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="t" protocolName="Calc" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="t"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan originalV1 = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g5-codec-v1", "1", v1Xml, Map.of()));
        assertEquals(GraphPlan.SCHEMA_VERSION_V1, originalV1.schemaVersion());
        String v1Json = GraphPlanCodec.toJson(originalV1);
        assertEquals(originalV1, GraphPlanCodec.fromJson(v1Json), "v1 plan survives round-trip");

        // v2/v3 dual-read retains v1 history; unsupported schemaVersion=4 and v1-carrying-v2 fields fail closed.
        String promotedV2Json = v1Json.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        GraphPlan promotedV2 = GraphPlanCodec.fromJson(promotedV2Json);
        assertEquals(GraphPlan.SCHEMA_VERSION_V2, promotedV2.schemaVersion());
        assertEquals(originalV1.definitionKey(), promotedV2.definitionKey());

        String v4Json = v1Json.replace("\"schemaVersion\":1", "\"schemaVersion\":4");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v4Json),
                "frozen §4.2: unknown schemaVersion=4 must fail closed");
        String v1WithRouteKind = v1Json.replace("\"defaultFlow\":false", "\"defaultFlow\":false,\"routeKind\":\"TIMEOUT\"");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v1WithRouteKind),
                "frozen §4.1: v1 payload carrying routeKind must be rejected");

        // Real compiled v2 WAIT + TIMEOUT plan must round-trip all WaitSpec & RouteKind metadata cleanly.
        GraphPlan waitV2 = compileWaitWithTimeoutPlan("g5-codec-wait-v2", "qa-corr", "PT30S");
        assertEquals(GraphPlan.SCHEMA_VERSION_V2, waitV2.schemaVersion());
        String waitV2Json = GraphPlanCodec.toJson(waitV2);
        GraphPlan restoredV2 = GraphPlanCodec.fromJson(waitV2Json);
        assertEquals(waitV2, restoredV2, "compiled v2 WAIT+TIMEOUT plan must round-trip losslessly");

        GraphPlan.Node waitNode = findNode(restoredV2, "w1");
        assertEquals(GraphPlan.Kind.WAIT, waitNode.kind());
        assertNotNull(waitNode.waitSpec());
        assertEquals(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, waitNode.waitSpec().waitKind());
        assertEquals("qa-corr", waitNode.waitSpec().correlationKey());
        assertEquals("PT30S", waitNode.waitSpec().durationIso());
        assertEquals(30_000L, waitNode.waitSpec().durationMillis());

        GraphPlan.Edge timeoutEdge = restoredV2.edges().stream()
                .filter(e -> "w1".equals(e.from()) && e.routeKind() == GraphPlan.RouteKind.TIMEOUT)
                .findFirst().orElseThrow();
        assertEquals("e_to", timeoutEdge.to());

        // Downgrading a real v2 WAIT snapshot to schemaVersion=1 must fail closed.
        String downgradedV1 = waitV2Json.replace("\"schemaVersion\":2", "\"schemaVersion\":1");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(downgradedV1),
                "frozen §4.1: schemaVersion=1 containing WAIT node / routeKind must be rejected");
    }

    @Test
    @DisplayName("A7 [INDEPENDENT QA / PG17] correlation index rejects same-key (23505) and allows different keys")
    void a7_correlationIndexRejectsSameKeyAllowsDifferentKeys() throws Exception {
        assumePg();
        UUID tenant = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID def = UUID.randomUUID();
        UUID act1 = UUID.randomUUID();
        UUID act2 = UUID.randomUUID();
        UUID tok1 = UUID.randomUUID();
        UUID tok2 = UUID.randomUUID();
        try {
            try (Connection c = ds().getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                        + tenant + "','g5qa-" + tenant + "','g5qa')");
                st.executeUpdate("INSERT INTO workflow_runtime.definition_snapshot"
                        + "(tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan) VALUES ('"
                        + tenant + "','" + def + "','g5qa-def','1',decode(repeat('00',32),'hex'),'qa','{}')");
                st.executeUpdate("INSERT INTO workflow_runtime.workflow_run(tenant_id,run_id,definition_id,status,control_gate) VALUES ('"
                        + tenant + "','" + run + "','" + def + "','RUNNING','OPEN')");
                for (UUID tok : List.of(tok1, tok2)) {
                    st.executeUpdate("INSERT INTO workflow_runtime.execution_token"
                            + "(tenant_id,token_id,run_id,token_state,node_key) VALUES ('"
                            + tenant + "','" + tok + "','" + run + "','WAITING','w')");
                }
                st.executeUpdate("INSERT INTO workflow_runtime.node_activation"
                        + "(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state) VALUES ('"
                        + tenant + "','" + act1 + "','" + run + "','" + tok1 + "',1,'w1','WAITING')");
                st.executeUpdate("INSERT INTO workflow_runtime.node_activation"
                        + "(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state) VALUES ('"
                        + tenant + "','" + act2 + "','" + run + "','" + tok2 + "',1,'w2','WAITING')");
            }
            try (Connection c = ds().getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO workflow_runtime.wait_registration"
                        + "(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation) VALUES ('"
                        + tenant + "','" + run + "','" + act1 + "','ACTIVE','MESSAGE','g5qa-key-A','{\"nodeKey\":\"ok\"}')");
                st.executeUpdate("INSERT INTO workflow_runtime.wait_registration"
                        + "(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation) VALUES ('"
                        + tenant + "','" + run + "','" + act2 + "','ACTIVE','MESSAGE','g5qa-key-B','{\"nodeKey\":\"ok\"}')");
                assertEquals(2, count("SELECT count(*) FROM workflow_runtime.wait_registration WHERE tenant_id='"
                                + tenant + "' AND wait_state='ACTIVE'"),
                        "frozen §11.1: different correlation_key in one Run must NOT be rejected");

                UUID act3 = UUID.randomUUID();
                UUID tok3 = UUID.randomUUID();
                try (Connection c2 = ds().getConnection(); Statement st2 = c2.createStatement()) {
                    st2.executeUpdate("INSERT INTO workflow_runtime.execution_token"
                            + "(tenant_id,token_id,run_id,token_state,node_key) VALUES ('"
                            + tenant + "','" + tok3 + "','" + run + "','WAITING','w')");
                    st2.executeUpdate("INSERT INTO workflow_runtime.node_activation"
                            + "(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state) VALUES ('"
                            + tenant + "','" + act3 + "','" + run + "','" + tok3 + "',1,'w3','WAITING')");
                    SQLException ex = assertThrows(SQLException.class, () ->
                                    st.executeUpdate("INSERT INTO workflow_runtime.wait_registration"
                                            + "(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation) VALUES ('"
                                            + tenant + "','" + run + "','" + act3 + "','ACTIVE','MESSAGE','g5qa-key-A','{\"nodeKey\":\"ok\"}')"),
                            "frozen §11.1: duplicate ACTIVE same correlation_key must be rejected");
                    assertEquals("23505", ex.getSQLState(), "expected PostgreSQL unique_violation 23505: " + ex);
                    assertTrue(ex.getMessage().contains("wait_active_correlation_uq"),
                            "expected constraint wait_active_correlation_uq in error: " + ex.getMessage());
                }
            }
        } finally {
            try (Connection c = ds().getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM workflow_runtime.wait_registration WHERE tenant_id='" + tenant + "'");
                st.executeUpdate("DELETE FROM workflow_runtime.node_activation WHERE tenant_id='" + tenant + "'");
                st.executeUpdate("DELETE FROM workflow_runtime.execution_token WHERE tenant_id='" + tenant + "'");
                st.executeUpdate("DELETE FROM workflow_runtime.workflow_run WHERE tenant_id='" + tenant + "'");
                st.executeUpdate("DELETE FROM workflow_runtime.definition_snapshot WHERE tenant_id='" + tenant + "'");
                st.executeUpdate("DELETE FROM workflow_runtime.tenant WHERE tenant_id='" + tenant + "'");
            }
        }
    }

    // =============================================================================================
    // Group B — Black-Box Behavioral Acceptance & New Index Audits
    // =============================================================================================

    private void runAndVerifyConcurrentWaitVsDrain(
            JdbcTemplate jdbc, WorkflowRuntimeRepository helperRepo,
            UUID tenant, String drainKind, boolean holdOuterRunLock, int round) throws Exception {
        StartedWaitRun run = createStartedRunAtWait(
                helperRepo, jdbc, tenant, "def-dr4-" + drainKind.toLowerCase() + "-" + round,
                "corr-dr4-" + drainKind.toLowerCase() + "-" + round, "PT60S");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection holdConn = null;
        try {
            WorkflowRuntimeRepository regRepo;
            WorkflowRuntimeRepository drainRepo;
            String appTag = null;
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            if (holdOuterRunLock) {
                appTag = "g5qa-dr4-" + UUID.randomUUID().toString().substring(0, 8);
                DriverManagerDataSource taggedDs = dsWithApp(appTag);
                regRepo = new WorkflowRuntimeRepository(taggedDs, new DataSourceTransactionManager(taggedDs));
                drainRepo = new WorkflowRuntimeRepository(taggedDs, new DataSourceTransactionManager(taggedDs));
                holdConn = ds().getConnection();
                holdConn.setAutoCommit(false);
                try (PreparedStatement ps = holdConn.prepareStatement(
                        "SELECT run_id FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                    ps.setObject(1, tenant);
                    ps.setObject(2, run.runId());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "outer tx must lock started Run");
                    }
                }
            } else {
                DriverManagerDataSource raceDs = ds();
                regRepo = new WorkflowRuntimeRepository(raceDs, new DataSourceTransactionManager(raceDs));
                drainRepo = new WorkflowRuntimeRepository(raceDs, new DataSourceTransactionManager(raceDs));
            }

            Future<WorkflowRuntimeRepository.GraphWaitResult> regFuture = pool.submit(() -> {
                if (!holdOuterRunLock) {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                }
                return regRepo.registerGraphWait(tenant, run.runId(), run.waitTokenId(), "w1", run.waitSpec());
            });
            Future<UUID> drainFuture = pool.submit(() -> {
                if (!holdOuterRunLock) {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                }
                return drainRepo.requestDrain(tenant, run.runId(), drainKind);
            });

            if (holdOuterRunLock) {
                boolean bothWaiting = false;
                long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadlineNanos) {
                    Integer waiters = jdbc.queryForObject("""
                            SELECT count(*) FROM pg_stat_activity
                             WHERE datname = current_database()
                               AND usename = current_user
                               AND application_name = ?
                               AND wait_event_type = 'Lock'
                            """, Integer.class, appTag);
                    if (waiters != null && waiters == 2) {
                        bothWaiting = true;
                        break;
                    }
                    Thread.sleep(25);
                }
                assertTrue(bothWaiting,
                        "expected both registerGraphWait and requestDrain(" + drainKind + ") blocked on PG Run Lock");
                holdConn.commit();
                holdConn.close();
                holdConn = null;
            } else {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
            }

            WorkflowRuntimeRepository.GraphWaitResult regResult = regFuture.get(10, TimeUnit.SECONDS);
            UUID drainId = drainFuture.get(10, TimeUnit.SECONDS);
            assertNotNull(drainId);

            assertEquals(0L, jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=?
                    """, Long.class, tenant, run.runId()), "pure Wait race must never create node_attempt");

            boolean regWon = WaitOutcomes.COMPLETED.equals(regResult.outcome());
            if (regWon) {
                assertNotNull(regResult.waitId());
                assertNotNull(regResult.activationId());
                assertEquals(1L, jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.wait_registration
                         WHERE tenant_id=? AND run_id=? AND wait_id=? AND wait_state='ACTIVE'
                        """, Long.class, tenant, run.runId(), regResult.waitId()));
                assertEquals(1L, jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.node_activation
                         WHERE tenant_id=? AND run_id=? AND activation_id=?
                           AND activation_state='WAITING' AND current_attempt_id IS NULL
                        """, Long.class, tenant, run.runId(), regResult.activationId()));
                assertEquals("WAITING", jdbc.queryForObject("""
                        SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?
                        """, String.class, tenant, run.waitTokenId()));
            } else {
                assertEquals(WaitOutcomes.DEFERRED_RUN_NOT_OPEN, regResult.outcome(),
                        "drain winning Run lock must defer registerGraphWait cleanly");
                assertEquals(0L, jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.wait_registration WHERE tenant_id=? AND run_id=?
                        """, Long.class, tenant, run.runId()));
                assertEquals(0L, jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.node_activation
                         WHERE tenant_id=? AND run_id=? AND token_id=?
                        """, Long.class, tenant, run.runId(), run.waitTokenId()));
                assertEquals("READY", jdbc.queryForObject("""
                        SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?
                        """, String.class, tenant, run.waitTokenId()));
            }

            helperRepo.deliverDrainRequestOutbox(tenant, run.runId());
            if ("PAUSE".equals(drainKind)) {
                assertTrue(helperRepo.tryFinalizeDrain(tenant, run.runId(), drainId, "PAUSED"),
                        "PAUSE drain must converge after serialized race (regWon=" + regWon + ")");
                assertEquals("PAUSED", jdbc.queryForObject(
                        "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                        String.class, tenant, run.runId()));

                // Cleanly retire any ACTIVE Wait under PAUSED before test ends so g5qa-% leaks zero active Waits.
                UUID stopDrain = helperRepo.requestDrain(tenant, run.runId(), "STOP");
                helperRepo.deliverDrainRequestOutbox(tenant, run.runId());
                helperRepo.invalidateStopContinuations(tenant, run.runId());
                assertTrue(helperRepo.tryFinalizeDrain(tenant, run.runId(), stopDrain, "STOPPED"));
            } else {
                helperRepo.invalidateStopContinuations(tenant, run.runId());
                helperRepo.invalidateStopContinuations(tenant, run.runId());
                String expectedTerminal = "STOP".equals(drainKind) ? "STOPPED" : "FAILED";
                assertTrue(helperRepo.tryFinalizeDrain(tenant, run.runId(), drainId, expectedTerminal),
                        drainKind + " drain must converge cleanly");
                assertEquals(expectedTerminal, jdbc.queryForObject(
                        "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                        String.class, tenant, run.runId()));
                assertEquals(regWon ? 1L : 0L, jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.runtime_event
                         WHERE tenant_id=? AND run_id=? AND event_type='WAIT_CANCELLED'
                        """, Long.class, tenant, run.runId()),
                        "WAIT_CANCELLED event count must equal (regWon ? 1 : 0)");
            }
            assertEquals(0L, jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.wait_registration
                     WHERE tenant_id=? AND run_id=? AND wait_state='ACTIVE'
                    """, Long.class, tenant, run.runId()));
        } finally {
            if (holdConn != null) {
                try {
                    holdConn.rollback();
                } catch (SQLException ignored) {
                }
                try {
                    holdConn.close();
                } catch (SQLException ignored) {
                }
            }
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("B1 [DR4 / PG17] concurrent PAUSE/STOP/FAILURE vs registerGraphWait serialized by Run lock")
    void b1_concurrentPauseStopFailureVsRegisterGraphWaitSerializedByRunLock() throws Exception {
        assumePg();
        DriverManagerDataSource ds = ds();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerQaTenant(jdbc, "dr4");

        // Part 1: Deterministic PG Lock contention proof via pg_stat_activity before releasing Run lock.
        runAndVerifyConcurrentWaitVsDrain(jdbc, repo, tenant, "PAUSE", true, 0);
        runAndVerifyConcurrentWaitVsDrain(jdbc, repo, tenant, "STOP", true, 0);

        // Part 2: Natural concurrent races across PAUSE, STOP, and FAILURE.
        for (int i = 1; i <= 6; i++) {
            runAndVerifyConcurrentWaitVsDrain(jdbc, repo, tenant, "PAUSE", false, i);
            runAndVerifyConcurrentWaitVsDrain(jdbc, repo, tenant, (i % 2 == 0) ? "FAILURE" : "STOP", false, i);
        }
    }

    @Test
    @DisplayName("B2 [R6 / PG17] receiveWaitInbox ABORT_BEFORE_COMMIT & LOST_RESULT_AFTER_COMMIT + idempotent replay")
    void b2_waitInboxTransactionBoundaryAbortBeforeAndLostResultAfterCommit() throws Exception {
        assumePg();
        DriverManagerDataSource ds = ds();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        FaultInjectingTransactionManager faultTx = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, faultTx);
        UUID tenant = registerQaTenant(jdbc, "r6");

        StartedWaitRun run = createStartedRunAtWait(repo, jdbc, tenant, "def-r6", "corr-r6", "PT60S");
        WorkflowRuntimeRepository.GraphWaitResult reg = repo.registerGraphWait(
                tenant, run.runId(), run.waitTokenId(), "w1", run.waitSpec());
        assertEquals(WaitOutcomes.COMPLETED, reg.outcome());

        String msgId = "msg-r6-" + UUID.randomUUID();
        String payload = "{\"step\":\"r6-verify\"}";

        // 1. Crash BEFORE commit -> complete rollback, zero durable state.
        faultTx.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(FaultInjectingTransactionManager.InjectedCommitAbort.class, () ->
                repo.receiveWaitInbox(tenant, run.runId(), "webhook", msgId, "corr-r6", payload));

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, reg.waitId()));
        assertEquals("WAITING", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, run.waitTokenId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND parent_token_id=?
                """, Long.class, tenant, run.waitTokenId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND source='webhook' AND source_message_id=?
                """, Long.class, tenant, msgId));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.runtime_event
                 WHERE tenant_id=? AND run_id=? AND event_type='WAIT_SATISFIED'
                """, Long.class, tenant, run.runId()));

        // 2. Crash AFTER commit -> caller sees exception, DB MUST contain full atomic settlement.
        faultTx.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(FaultInjectingTransactionManager.InjectedLostResult.class, () ->
                repo.receiveWaitInbox(tenant, run.runId(), "webhook", msgId, "corr-r6", payload));

        assertEquals("SATISFIED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, reg.waitId()));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT activation_state FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?",
                String.class, tenant, reg.activationId()));
        assertNull(jdbc.queryForObject(
                "SELECT current_attempt_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?",
                UUID.class, tenant, reg.activationId()));
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, run.waitTokenId()));
        assertEquals("PROCESSED", jdbc.queryForObject("""
                SELECT receipt_state FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND source='webhook' AND source_message_id=?
                """, String.class, tenant, msgId));
        UUID durableInboxId = jdbc.queryForObject("""
                SELECT inbox_id FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND source='webhook' AND source_message_id=?
                """, UUID.class, tenant, msgId);
        UUID successorTokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND parent_token_id=?
                """, UUID.class, tenant, run.waitTokenId());
        assertEquals("READY", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, successorTokenId));
        assertEquals("e_ok", jdbc.queryForObject(
                "SELECT node_key FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, successorTokenId));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.runtime_event
                 WHERE tenant_id=? AND run_id=? AND event_type='WAIT_SATISFIED'
                """, Long.class, tenant, run.runId()));

        // 3. Caller retry after lost commit -> idempotent ALREADY_COMPLETED with exact same inboxId, zero duplicates.
        WorkflowRuntimeRepository.GraphWaitResult replay = repo.receiveWaitInbox(
                tenant, run.runId(), "webhook", msgId, "corr-r6", payload);
        assertEquals(WaitOutcomes.ALREADY_COMPLETED, replay.outcome());
        assertEquals(durableInboxId, replay.inboxId());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND parent_token_id=?
                """, Long.class, tenant, run.waitTokenId()));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.runtime_event
                 WHERE tenant_id=? AND run_id=? AND event_type='WAIT_SATISFIED'
                """, Long.class, tenant, run.runId()));
        assertTrue(repo.advanceGraphToken(tenant, run.runId(), successorTokenId, "e_ok", "END", List.of(), null)
                .runCompleted());
    }

    private static boolean hasMethod(Class<?> type, String name, int paramCount) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return true;
        }
        return false;
    }

    @Test
    @DisplayName("B3 [API + E2E / PG17] frozen API signatures and pure-Wait SATISFIED + EXPIRED end-to-end paths")
    void b3_frozenApiSignaturesAndEndToEndPureWaitCompileRegisterComplete() throws Exception {
        assumePg();
        // Verify exact landed G5 API signatures (fixing earlier stale 5-arg receiveWaitInbox probe).
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "registerGraphWait", 5));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "completeGraphWait", 5));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "completeGraphWait", 6));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "receiveWaitInbox", 6));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "receiveWaitInbox", 7));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "replayPendingWaitInbox", 3));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "findDueWaits", 1));
        assertTrue(hasMethod(WorkflowRuntimeRepository.class, "findPendingWaitInboxReceipts", 1));
        assertNotNull(GraphPlan.Kind.valueOf("WAIT"));

        DriverManagerDataSource ds = ds();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerQaTenant(jdbc, "e2e");

        // Path 1: signal satisfies Wait -> normal continuation 'e_ok' -> Run SUCCEEDED (0 node_attempt).
        StartedWaitRun okRun = createStartedRunAtWait(repo, jdbc, tenant, "def-b3-ok", "corr-b3-ok", "PT60S");
        var regOk = repo.registerGraphWait(tenant, okRun.runId(), okRun.waitTokenId(), "w1", okRun.waitSpec());
        assertEquals(WaitOutcomes.COMPLETED, regOk.outcome());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=?",
                Long.class, tenant, okRun.runId()));

        String contJsonText = jdbc.queryForObject(
                "SELECT continuation::text FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, regOk.waitId());
        JsonNode contJson = JSON.readTree(contJsonText);
        assertEquals("e_ok", contJson.get("nodeKey").asText());
        assertEquals("e_to", contJson.get("timeoutNodeKey").asText());
        assertEquals(2, contJson.get("continuationVersion").asInt());

        var sat = repo.receiveWaitInbox(tenant, okRun.runId(), "webhook", "b3-sig-1", "corr-b3-ok", "{\"ok\":true}");
        assertEquals(WaitOutcomes.COMPLETED, sat.outcome());
        assertEquals("SATISFIED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, regOk.waitId()));
        assertTrue(repo.advanceGraphToken(tenant, okRun.runId(), sat.successorTokenId(), "e_ok", "END", List.of(), null)
                .runCompleted());

        // Path 2: backdated due Wait -> findDueWaitsForTenant -> completeGraphWait EXPIRED -> 'e_to' -> SUCCEEDED.
        StartedWaitRun toRun = createStartedRunAtWait(repo, jdbc, tenant, "def-b3-to", "corr-b3-to", "PT60S");
        var regTo = repo.registerGraphWait(tenant, toRun.runId(), toRun.waitTokenId(), "w1", toRun.waitSpec());
        assertEquals(WaitOutcomes.COMPLETED, regTo.outcome());

        jdbc.update("""
                UPDATE workflow_runtime.wait_registration
                   SET deadline_at = clock_timestamp() - interval '1 second'
                 WHERE tenant_id=? AND wait_id=?
                """, tenant, regTo.waitId());

        var dueList = repo.findDueWaitsForTenant(tenant, 10);
        var due = dueList.stream().filter(d -> d.waitId().equals(regTo.waitId())).findFirst().orElseThrow();
        var exp = repo.completeGraphWait(
                tenant, toRun.runId(), due.waitId(), WaitOutcomes.OUTCOME_EXPIRED, due.version(), null);
        assertEquals(WaitOutcomes.COMPLETED, exp.outcome());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, regTo.waitId()));
        assertEquals("e_to", jdbc.queryForObject(
                "SELECT node_key FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, exp.successorTokenId()));
        assertTrue(repo.advanceGraphToken(tenant, toRun.runId(), exp.successorTokenId(), "e_to", "END", List.of(), null)
                .runCompleted());
    }

    @Test
    @DisplayName("B4 [INTEGRATION-GATE] wait_active_correlation_uq partial unique index (frozen §11.1 / A1-1)")
    void b4_correlationUniqueIndex() throws Exception {
        assumePg();
        List<String> defs = queryAll("SELECT indexdef FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='wait_active_correlation_uq'");
        assertEquals(1, defs.size(), "frozen §11.1 requires wait_active_correlation_uq index");
        String def = defs.get(0);
        assertTrue(def.startsWith("CREATE UNIQUE INDEX"), "§11.1 must be UNIQUE: " + def);
        assertTrue(def.contains("(tenant_id, run_id, correlation_key)"), "§11.1 column order: " + def);
        assertTrue(def.contains("wait_state = 'ACTIVE'") && def.contains("correlation_key IS NOT NULL"),
                "§11.1 partial predicate: " + def);
    }

    @Test
    @DisplayName("B5 [AUDIT / 004] inbox_wait_signal_pending_idx partial index definition matches §6.3")
    void b5_waitInboxPendingPartialIndex() throws Exception {
        assumePg();
        List<String> defs = queryAll("SELECT indexdef FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='inbox_wait_signal_pending_idx'");
        assertEquals(1, defs.size(), "004 migration must create inbox_wait_signal_pending_idx");
        String def = defs.get(0);
        assertTrue(def.contains("(tenant_id, run_id, received_at)"), "004 index columns: " + def);
        assertTrue(def.contains("'WORKFLOW_WAIT_SIGNAL'")
                        && def.contains("'RECEIVED'")
                        && def.contains("'PROCESSING'"),
                "004 partial predicate: " + def);
    }

    // =============================================================================================
    // Deterministic Final Check — always executes after all A*/B* tests
    // =============================================================================================

    @Test
    @DisplayName("Z1 [ENV HYGIENE] no ACTIVE waits leaked by this QA class (scoped to g5qa-% tenants)")
    void z1_qaClassLeavesNoActiveWaits() throws Exception {
        assumePg();
        long leaked = count("SELECT count(*) FROM workflow_runtime.wait_registration w "
                + "JOIN workflow_runtime.tenant t ON t.tenant_id=w.tenant_id "
                + "WHERE w.wait_state='ACTIVE' AND t.tenant_key LIKE 'g5qa-%'");
        assertEquals(0, leaked, "this QA class must leak no ACTIVE waits across g5qa-% tenants");
    }
}
