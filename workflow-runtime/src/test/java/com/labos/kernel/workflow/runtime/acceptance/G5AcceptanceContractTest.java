package com.labos.kernel.workflow.runtime.acceptance;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI-4 G5 independent acceptance: schema/runtime CONTRACT AUDIT + integration-gated probes.
 *
 * <p>Frozen design: commit {@code ba9ecf46}, code baseline {@code 491cd2cc}. This class is
 * QA-only: it never modifies production code and never relaxes a frozen contract.
 *
 * <p>Two result classes are deliberately separated:
 * <ul>
 *   <li><b>AUDIT tests (Group A)</b> run against the current baseline with no G5 production code.
 *       They assert the DDL/schema preconditions the frozen design requires and that G1-G4
 *       invariants still hold. A failure here is a REAL FAILURE, not integration debt.</li>
 *   <li><b>INTEGRATION probes (Group B)</b> use reflection to detect the frozen G5 Repository API.
 *       When an API is absent they call {@link Assumptions#abort(String)}, which JUnit records as
 *       skipped/UNKNOWN — never PASS. They become executable only after AI-1/AI-2 land.</li>
 * </ul>
 *
 * <p>Requires RUN_RUNTIME_PG_IT=1 and an initialized PostgreSQL 17 schema (001_init.sql +
 * 002_schema_contract.sql). No Mock substitutes for any PG17 assertion.
 */
class G5AcceptanceContractTest {

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private DriverManagerDataSource ds() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                        + "/" + env("PGDATABASE", "runtime_test"),
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private void assumePg() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
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

    // =============================================================================================
    // Group A — executable NOW on baseline 491cd2cc (real PASS/FAIL)
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
        // Frozen §3.1: pure Wait has NO attempt column on wait_registration (linked via activation).
        assertFalse(cols.contains("attempt_id"), "pure Wait must not add a direct attempt_id column to wait_registration");
    }

    @Test
    @DisplayName("A2 [AUDIT] wait_registration index DEFINITIONS (columns/unique/predicate) match frozen §2/§11.1")
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
    @DisplayName("A3 [AUDIT] wait_state CHECK constraint (specifically) admits the four frozen states")
    void a3_waitStateCheckConstraint() throws Exception {
        assumePg();
        // Lock onto the CHECK constraint on the wait_state COLUMN, not a concatenation of all table CHECKs.
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
    @DisplayName("A4 [AUDIT] inbox_receipt has received_at/message_type required by frozen §6")
    void a4_inboxColumnsAudit() throws Exception {
        assumePg();
        List<String> cols = queryAll("SELECT column_name FROM information_schema.columns WHERE table_schema='workflow_runtime' AND table_name='inbox_receipt'");
        assertTrue(cols.contains("received_at"), "frozen §6.4 arbitrates by inbox received_at");
        assertTrue(cols.contains("message_type"), "frozen §6.1 fixes message_type=WORKFLOW_WAIT_SIGNAL");
        assertTrue(cols.contains("source_message_id"), "frozen §7 Inbox identity is (tenant, source, source_message_id)");
        assertTrue(cols.contains("payload_hash"), "frozen §7 payload-hash conflict => PROTOCOL_VIOLATION");

        // Frozen §7 idempotency relies on the (tenant_id, source, source_message_id) UNIQUE constraint.
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
    @DisplayName("A5 [AUDIT] GraphPlan retains all v1 record fields required to survive frozen §4.1 v2 upgrade")
    void a5_graphPlanRetainsV1RecordFields() {
        Class<?> plan = GraphPlan.class;
        List<String> components = new ArrayList<>();
        for (var rc : plan.getRecordComponents()) components.add(rc.getName());
        // Frozen §4.1: v2 preserves ALL v1 history fields. Valid on baseline and after AI-2 lands.
        assertTrue(components.containsAll(List.of("schemaVersion", "compilerVersion", "definitionKey",
                        "definitionVersion", "bpmnSha256", "nodes", "edges")),
                "frozen §4.1 requires v1 GraphPlan history fields to survive the v2 upgrade");
    }

    @Test
    @DisplayName("A5b [BEHAVIOR] GraphPlanCodec v1 round-trip; v2 accepted when integrated; v3/v1-with-WAIT rejected")
    void a5b_codecV1RoundTripBehavior() {
        String xml = """
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
        GraphPlan original = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g5-codec-audit", "1", xml, java.util.Map.of()));
        assertEquals(1, original.schemaVersion(), "baseline compile emits v1 plan");
        String json = GraphPlanCodec.toJson(original);
        GraphPlan restored = GraphPlanCodec.fromJson(json);
        assertEquals(original.schemaVersion(), restored.schemaVersion());
        assertEquals(original.definitionKey(), restored.definitionKey());
        assertEquals(original.bpmnSha256(), restored.bpmnSha256());
        assertEquals(original.nodes().size(), restored.nodes().size(), "v1 nodes survive round-trip");
        assertEquals(original.edges().size(), restored.edges().size(), "v1 edges survive round-trip");

        // Frozen §4.2 (post-integration): v2 is ACCEPTED with dual-read; v3 and v1-carrying-v2-fields are rejected.
        String v2Json = json.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        GraphPlan fromV2 = GraphPlanCodec.fromJson(v2Json);
        assertEquals(2, fromV2.schemaVersion(), "frozen §4.2: Codec accepts schemaVersion=2 after AI-2");
        assertEquals(original.definitionKey(), fromV2.definitionKey(), "v2 dual-read keeps history fields");

        String v3Json = json.replace("\"schemaVersion\":1", "\"schemaVersion\":3");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v3Json),
                "frozen §4.2: unknown schemaVersion=3 must fail closed");

        // v1 payload that smuggles a v2-only field must be rejected (frozen §4.1 fail-closed).
        String v1WithRouteKind = json.replace("\"defaultFlow\":false", "\"defaultFlow\":false,\"routeKind\":\"TIMEOUT\"");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v1WithRouteKind),
                "frozen §4.1: v1 payload carrying routeKind must be rejected");
    }

    @Test
    @DisplayName("A6 [ENV HYGIENE] no ACTIVE waits leaked by this QA class (scoped to its own tenant)")
    void a6_qaClassLeavesNoActiveWaits() throws Exception {
        assumePg();
        // Scoped to QA-created tenants only: a full suite shares the DB with producer tests whose
        // own fixtures may legitimately remain. Checking global ACTIVE count would be a false failure.
        long leaked = count("SELECT count(*) FROM workflow_runtime.wait_registration w "
                + "JOIN workflow_runtime.tenant t ON t.tenant_id=w.tenant_id "
                + "WHERE w.wait_state='ACTIVE' AND t.tenant_key LIKE 'g5qa-%'");
        assertEquals(0, leaked, "this QA class must leak no ACTIVE waits (own tenants only)");
    }

    @Test
    @DisplayName("A7 [INDEPENDENT QA / PG17] correlation index rejects same-key duplicate but ALLOWS different keys in one Run (frozen §11.1)")
    void a7_correlationIndexRejectsSameKeyAllowsDifferentKeys() throws Exception {
        assumePg();
        // Independent cross-check of A1-1 migration: the partial unique index must be keyed by
        // (tenant, run, correlation_key) — NOT an over-broad (tenant, run) constraint.
        // Producer tests only assert the same-key rejection; this asserts the inverse (no false rejection).
        UUID tenant = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID def = UUID.randomUUID();
        UUID act1 = UUID.randomUUID();
        UUID act2 = UUID.randomUUID();
        UUID tok1 = UUID.randomUUID();
        UUID tok2 = UUID.randomUUID();
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
            // Two DIFFERENT correlation keys in one Run must coexist.
            st.executeUpdate("INSERT INTO workflow_runtime.wait_registration"
                    + "(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation) VALUES ('"
                    + tenant + "','" + run + "','" + act1 + "','ACTIVE','MESSAGE','g5qa-key-A','{\"nodeKey\":\"ok\"}')");
            st.executeUpdate("INSERT INTO workflow_runtime.wait_registration"
                    + "(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation) VALUES ('"
                    + tenant + "','" + run + "','" + act2 + "','ACTIVE','MESSAGE','g5qa-key-B','{\"nodeKey\":\"ok\"}')");
            assertEquals(2, count("SELECT count(*) FROM workflow_runtime.wait_registration WHERE tenant_id='"
                    + tenant + "' AND wait_state='ACTIVE'"),
                    "frozen §11.1: different correlation_key in one Run must NOT be rejected");

            // A duplicate of key A must still be rejected (same-key uniqueness).
            UUID act3 = UUID.randomUUID();
            UUID tok3 = UUID.randomUUID();
            try (Connection c2 = ds().getConnection(); Statement st2 = c2.createStatement()) {
                st2.executeUpdate("INSERT INTO workflow_runtime.execution_token"
                        + "(tenant_id,token_id,run_id,token_state,node_key) VALUES ('"
                        + tenant + "','" + tok3 + "','" + run + "','WAITING','w')");
                st2.executeUpdate("INSERT INTO workflow_runtime.node_activation"
                        + "(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state) VALUES ('"
                        + tenant + "','" + act3 + "','" + run + "','" + tok3 + "',1,'w3','WAITING')");
                assertThrows(java.sql.SQLException.class, () ->
                        st.executeUpdate("INSERT INTO workflow_runtime.wait_registration"
                                + "(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation) VALUES ('"
                                + tenant + "','" + run + "','" + act3 + "','ACTIVE','MESSAGE','g5qa-key-A','{\"nodeKey\":\"ok\"}')"),
                        "frozen §11.1: duplicate ACTIVE same correlation_key must be rejected");
            }
        }
        // QA hygiene: remove this test's fixtures so a6 (clean-DB assertion) and other tests stay isolated.
        try (Connection c = ds().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM workflow_runtime.wait_registration WHERE tenant_id='" + tenant + "'");
            st.executeUpdate("DELETE FROM workflow_runtime.node_activation WHERE tenant_id='" + tenant + "'");
            st.executeUpdate("DELETE FROM workflow_runtime.execution_token WHERE tenant_id='" + tenant + "'");
            st.executeUpdate("DELETE FROM workflow_runtime.workflow_run WHERE tenant_id='" + tenant + "'");
            st.executeUpdate("DELETE FROM workflow_runtime.definition_snapshot WHERE tenant_id='" + tenant + "'");
            st.executeUpdate("DELETE FROM workflow_runtime.tenant WHERE tenant_id='" + tenant + "'");
        }
    }

    // =============================================================================================
    // Group B — INTEGRATION PROBES (UNKNOWN until AI-1/AI-2 land; never false PASS)
    // =============================================================================================

    /** Detect a frozen Repository API by reflection. Aborts (UNKNOWN) when absent. */
    private Method requireRepoApi(String name, int paramCount) {
        try {
            Class<?> repo = Class.forName("com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository");
            for (Method m : repo.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
            }
        } catch (ClassNotFoundException ignored) {
            Assumptions.abort("UNKNOWN: G5 Repository class not found");
        }
        Assumptions.abort("UNKNOWN: frozen G5 API " + name + "(" + paramCount + " params) not integrated yet (AI-1 pending)");
        return null; // unreachable
    }

    /**
     * Group B is deliberately kept as a presence GATE only. When an API is absent the test is
     * UNKNOWN (Assumptions.abort). When present it STILL aborts as UNKNOWN until the dedicated
     * behavioral suite (G5AcceptanceIntegrationTest) is authored against the landed signatures —
     * we never emit a PASS from a mere reflection check, because that would violate the frozen
     * QA rule "no PASS without a real assertion on observable behavior".
     */
    private void presenceGateOnly(String api) {
        Assumptions.abort("UNKNOWN: frozen G5 API " + api
                + " detected, but behavioral assertions require integrated signatures (AI-1/AI-2 branch). No false PASS.");
    }

    @Test
    @DisplayName("B1 [INTEGRATION-GATE] registerGraphWait API presence")
    void b1_registerGraphWaitApi() {
        requireRepoApi("registerGraphWait", 5);
        presenceGateOnly("registerGraphWait");
    }

    @Test
    @DisplayName("B2 [INTEGRATION-GATE] completeGraphWait / receiveWaitInbox / findDueWaits APIs presence")
    void b2_coreWaitApis() {
        requireRepoApi("completeGraphWait", 5);
        requireRepoApi("receiveWaitInbox", 5);
        requireRepoApi("findDueWaits", 1);
        presenceGateOnly("completeGraphWait/receiveWaitInbox/findDueWaits");
    }

    @Test
    @DisplayName("B3 [INTEGRATION-UNKNOWN] GraphPlan.Kind.WAIT present")
    void b3_waitKind() throws Exception {
        Class<?> kind = Class.forName("com.labos.kernel.workflow.runtime.graph.GraphPlan$Kind");
        boolean hasWait = false;
        for (Object c : kind.getEnumConstants()) if ("WAIT".equals(((Enum<?>) c).name())) hasWait = true;
        if (!hasWait) Assumptions.abort("UNKNOWN: GraphPlan.Kind.WAIT not integrated yet (AI-2 pending)");
        presenceGateOnly("GraphPlan.Kind.WAIT");
    }

    @Test
    @DisplayName("B4 [INTEGRATION-GATE] wait_active_correlation_uq partial unique index (frozen §11.1 / A1-1)")
    void b4_correlationUniqueIndex() throws Exception {
        assumePg();
        List<String> defs = queryAll("SELECT indexdef FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='wait_active_correlation_uq'");
        if (defs.isEmpty())
            Assumptions.abort("UNKNOWN: wait_active_correlation_uq not present on baseline; A1-1 migration adds it");
        // A1-1 integrated: real DDL assertion, not a presence stub.
        assertEquals(1, defs.size());
        String def = defs.get(0);
        assertTrue(def.startsWith("CREATE UNIQUE INDEX"), "§11.1 must be UNIQUE: " + def);
        assertTrue(def.contains("(tenant_id, run_id, correlation_key)"), "§11.1 column order: " + def);
        assertTrue(def.contains("wait_state = 'ACTIVE'") && def.contains("correlation_key IS NOT NULL"),
                "§11.1 partial predicate: " + def);
    }
}
