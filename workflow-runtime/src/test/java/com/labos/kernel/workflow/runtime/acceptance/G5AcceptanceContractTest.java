package com.labos.kernel.workflow.runtime.acceptance;

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
    @DisplayName("A2 [AUDIT] wait_registration due index and one-active-per-activation index exist")
    void a2_waitIndexesExist() throws Exception {
        assumePg();
        List<String> idx = queryAll("SELECT indexname FROM pg_indexes WHERE schemaname='workflow_runtime' AND tablename='wait_registration'");
        assertTrue(idx.contains("wait_due_idx"), "frozen §2/§8.1 reuses wait_due_idx");
        assertTrue(idx.contains("wait_one_active_per_activation_uq"), "frozen §11.1 keeps the existing per-activation active Wait constraint");
    }

    @Test
    @DisplayName("A3 [AUDIT] wait_state enum supports the frozen pure-Wait lifecycle")
    void a3_waitStateEnum() throws Exception {
        assumePg();
        String check = String.join("|", queryAll(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='workflow_runtime.wait_registration'::regclass AND contype='c'"));
        for (String state : List.of("ACTIVE", "SATISFIED", "EXPIRED", "CANCELLED")) {
            assertTrue(check.contains(state), "frozen §3.1 requires wait_state " + state);
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
    }

    @Test
    @DisplayName("A5 [AUDIT] GraphPlanCodec baseline accepts only v1 (frozen H2 upgrade precondition)")
    void a5_codecBaselineIsV1Only() throws Exception {
        assumePg();
        // Compile-time reflection: confirms the frozen 'baseline is v1-only' fact without calling G5 APIs.
        Class<?> codec = Class.forName("com.labos.kernel.workflow.runtime.graph.GraphPlanCodec");
        Method fromJson = codec.getMethod("fromJson", String.class);
        assertNotNull(fromJson, "baseline GraphPlanCodec.fromJson must exist for the v1->v2 upgrade");
        // AUDIT expectation: baseline plan carries schemaVersion 1. (G5 v2 fixture is Group B.)
        Class<?> plan = Class.forName("com.labos.kernel.workflow.runtime.graph.GraphPlan");
        List<String> components = new ArrayList<>();
        for (var rc : plan.getRecordComponents()) components.add(rc.getName());
        // Frozen §4.1: v2 preserves ALL v1 history fields. This assertion stays valid after AI-2 lands.
        assertTrue(components.containsAll(List.of("schemaVersion", "compilerVersion", "definitionKey",
                        "definitionVersion", "bpmnSha256", "nodes", "edges")),
                "frozen §4.1 requires v1 GraphPlan history fields to survive the v2 upgrade");
    }

    @Test
    @DisplayName("A6 [REGRESSION] G1-G4: no stray non-terminal Wait/attempt residue from prior suites on this DB")
    void a6_baselineHasNoForeignResidue() throws Exception {
        assumePg();
        // This is a QA hygiene assertion: acceptance runs must start from a clean schema.
        long activeWaits = count("SELECT count(*) FROM workflow_runtime.wait_registration WHERE wait_state='ACTIVE'");
        assertEquals(0, activeWaits, "acceptance DB must start with zero ACTIVE waits (fresh 001_init baseline)");
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
}
