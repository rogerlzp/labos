package com.labos.kernel.workflow.runtime.persistence;

import com.labos.kernel.workflow.runtime.persistence.FaultInjectingTransactionManager.InjectedCommitAbort;
import com.labos.kernel.workflow.runtime.persistence.FaultInjectingTransactionManager.InjectedLostResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G4-B: database transaction crash-consistency fault-injection tests.
 *
 * <p>Scope is intentionally narrow and read-only with respect to production code. These tests
 * exercise only the two Repository transaction boundaries that matter for crash consistency:
 * the write templates in {@link WorkflowRuntimeRepository} ({@code ISOLATION_READ_COMMITTED}
 * via {@code required(...)}) and the graph-advance primitives
 * ({@code advanceGraphToken*}, {@code advanceCompletedGraphActivation}, {@code completeAttempt},
 * {@code createRun}). No core Repository, Schema, RuntimeHost or RuntimeDispatchLoop code is
 * modified; the only new production-adjacent artefact is the test-only
 * {@link FaultInjectingTransactionManager} decorator, which injects the fault exactly at the
 * JDBC commit boundary.
 *
 * <p>Two fault points are covered, and they map one-to-one onto the real-world crash modes:
 * <ul>
 *   <li>{@code ABORT_BEFORE_COMMIT} — the physical transaction is rolled back and the caller
 *       observes an exception. Nothing may be durable. This is "crash before commit".</li>
 *   <li>{@code LOST_RESULT_AFTER_COMMIT} — the physical transaction is committed and only then
 *       does the caller lose the result. Everything must be durable and fully consistent, and an
 *       idempotent replay must be safe. This is "committed but the caller never got the answer".</li>
 * </ul>
 *
 * <p>Each scenario verifies cross-table consistency of Token / Activation / Branch(Output) /
 * FlowVariable / WorkerExecutionFact / Event / Run. Read-only (snapshot) transactions are never
 * faulted, so the fault never silently corrupts the read path.
 */
@DisplayName("G4-B database transaction crash consistency")
class DatabaseTransactionCrashConsistencyIntegrationTest {

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static DriverManagerDataSource dataSource() {
        String host = env("PGHOST", "localhost"), port = env("PGPORT", "5432");
        String db = env("PGDATABASE", "runtime_test");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + host + ":" + port + "/" + db,
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static void assumeEnabled() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")),
                "PostgreSQL Runtime integration tests are enabled only in CI");
    }

    private UUID newTenant(DriverManagerDataSource ds) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','g4b-" + tenant + "','g4b-tx-fault')");
        }
        return tenant;
    }

    // ---------------------------------------------------------------------
    // T1: createRun aborted before commit -> nothing durable, safe fresh retry
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T1 createRun abort-before-commit leaves no dedup/definition/run/token/event")
    void createRunAbortBeforeCommitLeavesNothingDurable() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        String requestId = "g4b-t1-" + UUID.randomUUID();
        faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(InjectedCommitAbort.class, () -> repo.createRun(tenant, requestId, "CREATE", "{}",
                "g4b-t1-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start"));
        faults.disarm();

        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.run_request_dedup WHERE tenant_id=? AND request_id=?",
                tenant, requestId), "dedup receipt must not survive an aborted commit");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.definition_snapshot WHERE tenant_id=?", tenant));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id=?", tenant));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=?", tenant));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=?", tenant));
        System.out.println("[T1] abort-before-commit: dedup=definition=run=token=event=0 (nothing durable)");

        var retry = repo.createRun(tenant, requestId, "CREATE", "{}",
                "g4b-t1-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        assertFalse(retry.replayed(), "a retry after a rolled-back createRun must be a fresh create");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?",
                tenant, retry.runId()));
        assertEquals(1L, runEventSeq(ds, tenant, retry.runId()));
        assertEventLogContiguous(ds, tenant, retry.runId());
        System.out.println("[T1] retry after abort: fresh run created, event_seq=1, contiguous");
    }

    // ---------------------------------------------------------------------
    // T2: createRun committed but result lost -> everything durable + replay is idempotent
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T2 createRun lost-result-after-commit is durable and replay-safe")
    void createRunLostResultAfterCommitIsDurableAndReplaySafe() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        String requestId = "g4b-t2-" + UUID.randomUUID();
        faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(InjectedLostResult.class, () -> repo.createRun(tenant, requestId, "CREATE", "{}",
                "g4b-t2-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start"));
        faults.disarm();

        UUID runId = uuid(ds, "SELECT run_id FROM workflow_runtime.run_request_dedup WHERE tenant_id=? AND request_id=?",
                tenant, requestId);
        assertNotNull(runId, "committed createRun must leave a durable dedup receipt pointing at the Run");
        assertEquals("CREATED", runStatus(ds, tenant, runId));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND token_state='READY' AND node_key='start'",
                tenant, runId));
        assertEquals(1L, runCommitVersion(ds, tenant, runId));
        assertEquals(1L, runEventSeq(ds, tenant, runId));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='RUN_CREATED'",
                tenant, runId));
        assertEventLogContiguous(ds, tenant, runId);
        System.out.println("[T2] lost-result-after-commit: run=" + runId + " CREATED, token READY, commit_version=1, event_seq=1");

        var replay = repo.createRun(tenant, requestId, "CREATE", "{}",
                "g4b-t2-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        assertTrue(replay.replayed(), "the same requestId must replay the committed Run");
        assertEquals(runId, replay.runId());
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id=?", tenant));
        assertEquals(1L, runCommitVersion(ds, tenant, runId), "replay must not append events or bump commit_version");
        assertEquals(1L, runEventSeq(ds, tenant, runId));
        System.out.println("[T2] replay after lost result: replayed=true, same runId, no extra events");
    }

    // ---------------------------------------------------------------------
    // T3: graph advance aborted before commit -> Token stays READY, no Activation/Event
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T3 advanceGraphToken abort-before-commit keeps Token READY with no Activation/Event")
    void advanceGraphTokenAbortBeforeCommitRollsBackFully() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t3-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t3-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID tokenId = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        long versionBefore = runCommitVersion(ds, tenant, run.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(InjectedCommitAbort.class, () -> repo.advanceGraphToken(tenant, run.runId(), tokenId,
                "start", "SEQUENCE", List.of("end")));
        faults.disarm();

        assertEquals("READY", tokenState(ds, tenant, tokenId), "Token must roll back to READY");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                tenant, tokenId), "no Activation may be durable");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, run.runId()), "no successor Token may be durable");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId()));
        assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
        assertEquals(versionBefore, runCommitVersion(ds, tenant, run.runId()));
        assertEquals("RUNNING", runStatus(ds, tenant, run.runId()));
        System.out.println("[T3] abort-before-commit: Token READY, activation=0, successor=0, event_seq/commit_version unchanged");

        var retry = repo.advanceGraphToken(tenant, run.runId(), tokenId, "start", "SEQUENCE", List.of("end"));
        assertEquals("ADVANCED", retry.outcome());
        assertEquals(1, retry.tokensCreated());
        assertEquals("CONSUMED", tokenState(ds, tenant, tokenId), "a committed SEQUENCE advance consumes the source Token");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=? AND activation_state='SUCCEEDED'",
                tenant, tokenId));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end' AND token_state='READY'",
                tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T3] retry after abort: ADVANCED, successor READY, event log contiguous");
    }

    // ---------------------------------------------------------------------
    // T4: graph advance committed but result lost -> durable successor, replay is a safe no-op
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T4 advanceGraphToken lost-result-after-commit is durable and a replay is a safe no-op")
    void advanceGraphTokenLostResultAfterCommitIsDurableAndReplaySafe() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t4-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t4-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID tokenId = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        long versionBefore = runCommitVersion(ds, tenant, run.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(InjectedLostResult.class, () -> repo.advanceGraphToken(tenant, run.runId(), tokenId,
                "start", "SEQUENCE", List.of("end")));
        faults.disarm();

        assertEquals("CONSUMED", tokenState(ds, tenant, tokenId), "committed advance must consume the source Token");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=? AND activation_state='SUCCEEDED'",
                tenant, tokenId), "committed advance must mark the source Activation SUCCEEDED");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end' AND token_state='READY' AND parent_token_id=?",
                tenant, run.runId(), tokenId), "the committed successor Token must be durable and discoverable");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId()));
        assertEquals(seqBefore + 1, runEventSeq(ds, tenant, run.runId()));
        assertEquals(versionBefore + 1, runCommitVersion(ds, tenant, run.runId()));
        assertEquals("RUNNING", runStatus(ds, tenant, run.runId()), "a READY successor blocks Run completion");
        assertEventLogContiguous(ds, tenant, run.runId());
        var ready = repo.findReadyGraphTokens(tenant, run.runId(), 10);
        assertEquals(1, ready.size());
        assertEquals("end", ready.get(0).nodeKey());
        System.out.println("[T4] lost-result-after-commit: source Token CONSUMED, Activation SUCCEEDED, successor READY discoverable, event_seq=" +
                runEventSeq(ds, tenant, run.runId()) + " contiguous");

        assertThrows(WorkflowRuntimeRepository.StaleRuntimeWriteException.class,
                () -> repo.advanceGraphToken(tenant, run.runId(), tokenId, "start", "SEQUENCE", List.of("end")),
                "replaying the advance on an already-advanced Token must fail closed, not double-activate");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                tenant, tokenId), "replay must not create a second Activation");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, run.runId()), "replay must not create a second successor");
        assertEquals(seqBefore + 1, runEventSeq(ds, tenant, run.runId()));
        System.out.println("[T4] replay after lost result: StaleRuntimeWriteException, no duplicate Activation/Event");
    }

    // ---------------------------------------------------------------------
    // T5: completeAttempt aborted before commit -> Attempt/Activation/Branch/Event all rolled back
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T5 completeAttempt abort-before-commit rolls back Attempt/Activation/Branch/Event")
    void completeAttemptAbortBeforeCommitRollsBackFully() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var w = startPureTaskRun(ds, repo, tenant, "g4b-t5");
        long seqBefore = runEventSeq(ds, tenant, w.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(InjectedCommitAbort.class, () -> repo.completeAttempt(tenant, w.runId(), w.attemptId(),
                "result", "{\"ok\":true}", Map.of("out", "1")));
        faults.disarm();

        assertEquals("STARTED", attemptState(ds, tenant, w.attemptId()), "Attempt must stay STARTED");
        assertEquals("RUNNING", activationState(ds, tenant, w.activationId()), "Activation must stay RUNNING");
        assertEquals("RUNNING", tokenState(ds, tenant, w.tokenId()), "Token must stay RUNNING while the Attempt is open");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id=? AND activation_id=?",
                tenant, w.activationId()), "no Branch output may be durable");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.flow_variable WHERE tenant_id=? AND run_id=? AND variable_key='out'",
                tenant, w.runId()));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id=? AND attempt_id=? AND fact_type='EXECUTION_RIGHT_RELEASED'",
                tenant, w.attemptId()));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='ATTEMPT_SUCCEEDED'",
                tenant, w.runId()));
        assertEquals(seqBefore, runEventSeq(ds, tenant, w.runId()), "aborted completion must not advance event_seq");
        System.out.println("[T5] abort-before-commit: Attempt STARTED, Activation/Token RUNNING, branch/flow/fact/event=0");

        repo.completeAttempt(tenant, w.runId(), w.attemptId(), "result", "{\"ok\":true}", Map.of("out", "1"));
        assertEquals("SUCCEEDED", attemptState(ds, tenant, w.attemptId()));
        assertEquals("CONSUMED", tokenState(ds, tenant, w.tokenId()));
        assertEquals(1L, flowVariableVersion(ds, tenant, w.runId(), "out"));
        assertEventLogContiguous(ds, tenant, w.runId());
        System.out.println("[T5] retry after abort: Attempt SUCCEEDED, Token CONSUMED, flow_variable v1");
    }

    // ---------------------------------------------------------------------
    // T6: completeAttempt committed but result lost -> durable, replay no-op, divergent replay fails
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T6 completeAttempt lost-result-after-commit is durable, idempotent, and conflict-detecting")
    void completeAttemptLostResultAfterCommitIsDurableAndIdempotent() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var w = startPureTaskRun(ds, repo, tenant, "g4b-t6");
        long seqBefore = runEventSeq(ds, tenant, w.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(InjectedLostResult.class, () -> repo.completeAttempt(tenant, w.runId(), w.attemptId(),
                "result", "{\"ok\":true}", Map.of("out", "1")));
        faults.disarm();

        assertEquals("SUCCEEDED", attemptState(ds, tenant, w.attemptId()));
        assertEquals("SUCCEEDED", activationState(ds, tenant, w.activationId()));
        assertEquals("CONSUMED", tokenState(ds, tenant, w.tokenId()));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id=? AND activation_id=? AND output_key='result'",
                tenant, w.activationId()));
        assertEquals(1L, flowVariableVersion(ds, tenant, w.runId(), "out"));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id=? AND attempt_id=? AND fact_type='EXECUTION_RIGHT_RELEASED'",
                tenant, w.attemptId()));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='ATTEMPT_SUCCEEDED'",
                tenant, w.runId()));
        long seqAfterCommit = runEventSeq(ds, tenant, w.runId());
        assertTrue(seqAfterCommit > seqBefore);
        assertEventLogContiguous(ds, tenant, w.runId());
        System.out.println("[T6] lost-result-after-commit: Attempt/Activation SUCCEEDED, Token CONSUMED, 1 branch_output, 1 fact, event_seq=" + seqAfterCommit);

        repo.completeAttempt(tenant, w.runId(), w.attemptId(), "result", "{\"ok\":true}", Map.of("out", "1"));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id=? AND activation_id=?",
                tenant, w.activationId()), "identical replay must not duplicate Branch output");
        assertEquals(1L, flowVariableVersion(ds, tenant, w.runId(), "out"), "identical replay must not bump flow_variable version");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id=? AND attempt_id=? AND fact_type='EXECUTION_RIGHT_RELEASED'",
                tenant, w.attemptId()));
        assertEquals(seqAfterCommit, runEventSeq(ds, tenant, w.runId()), "identical replay must not append events");
        System.out.println("[T6] identical replay after lost result: single branch_output, no extra fact/event");

        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,
                () -> repo.completeAttempt(tenant, w.runId(), w.attemptId(), "result", "{\"ok\":false}", Map.of("out", "1")),
                "a divergent completion replay must be rejected");
        assertEquals(seqAfterCommit, runEventSeq(ds, tenant, w.runId()));
        System.out.println("[T6] divergent replay after lost result: IdempotencyConflictException, no state change");
    }

    // ---------------------------------------------------------------------
    // T7: routing a completed Activation committed but result lost -> durable successor + replay REPLAYED
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T7 advanceCompletedGraphActivation lost-result-after-commit is durable and replay returns REPLAYED")
    void advanceCompletedGraphActivationLostResultAfterCommitIsDurableAndReplays() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var w = startPureTaskRun(ds, repo, tenant, "g4b-t7");
        repo.completeAttempt(tenant, w.runId(), w.attemptId(), "result", "{\"ok\":true}");
        long seqBefore = runEventSeq(ds, tenant, w.runId());
        long versionBefore = runCommitVersion(ds, tenant, w.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(InjectedLostResult.class, () -> repo.advanceCompletedGraphActivation(
                tenant, w.runId(), w.activationId(), "SEQUENCE", List.of("end")));
        faults.disarm();

        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end' AND token_state='READY' AND parent_token_id=?",
                tenant, w.runId(), w.tokenId()), "the committed successor Token must be durable");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_SUCCESSORS_CREATED'",
                tenant, w.runId()));
        assertEquals(seqBefore + 1, runEventSeq(ds, tenant, w.runId()));
        assertEquals(versionBefore + 1, runCommitVersion(ds, tenant, w.runId()));
        assertEquals("RUNNING", runStatus(ds, tenant, w.runId()));
        assertEventLogContiguous(ds, tenant, w.runId());
        System.out.println("[T7] lost-result-after-commit: successor 'end' READY, GRAPH_SUCCESSORS_CREATED durable");

        var replay = repo.advanceCompletedGraphActivation(tenant, w.runId(), w.activationId(), "SEQUENCE", List.of("end"));
        assertEquals("REPLAYED", replay.outcome());
        assertEquals(0, replay.tokensCreated());
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, w.runId()), "replay must not duplicate the successor");
        assertEquals(seqBefore + 1, runEventSeq(ds, tenant, w.runId()));
        System.out.println("[T7] replay after lost result: outcome=REPLAYED, no duplicate successor/event");

        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,
                () -> repo.advanceCompletedGraphActivation(tenant, w.runId(), w.activationId(), "SEQUENCE", List.of("other")),
                "divergent successor replay must be rejected");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, w.runId()));
        System.out.println("[T7] divergent replay after lost result: IdempotencyConflictException");
    }

    // ---------------------------------------------------------------------
    // T8: routing a completed Activation aborted before commit -> source untouched, retry works
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T8 advanceCompletedGraphActivation abort-before-commit creates no successor and retry advances")
    void advanceCompletedGraphActivationAbortBeforeCommitRollsBack() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var w = startPureTaskRun(ds, repo, tenant, "g4b-t8");
        repo.completeAttempt(tenant, w.runId(), w.attemptId(), "result", "{\"ok\":true}");
        long seqBefore = runEventSeq(ds, tenant, w.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(InjectedCommitAbort.class, () -> repo.advanceCompletedGraphActivation(
                tenant, w.runId(), w.activationId(), "SEQUENCE", List.of("end")));
        faults.disarm();

        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, w.runId()));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_SUCCESSORS_CREATED'",
                tenant, w.runId()));
        assertEquals(seqBefore, runEventSeq(ds, tenant, w.runId()));
        assertEquals("SUCCEEDED", activationState(ds, tenant, w.activationId()), "source Activation unchanged");
        assertEquals("CONSUMED", tokenState(ds, tenant, w.tokenId()), "source Token unchanged");
        System.out.println("[T8] abort-before-commit: no successor, source Activation/Token unchanged");

        var retry = repo.advanceCompletedGraphActivation(tenant, w.runId(), w.activationId(), "SEQUENCE", List.of("end"));
        assertEquals("ADVANCED", retry.outcome());
        assertEquals(1, retry.tokensCreated());
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, w.runId()));
        assertEventLogContiguous(ds, tenant, w.runId());
        System.out.println("[T8] retry after abort: ADVANCED, successor durable, event log contiguous");
    }

    // ---------------------------------------------------------------------
    // T9: explicit rollback - SPLIT with duplicate successors aborts the whole multi-statement tx
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T9 SPLIT duplicate-successor exception rolls back Activation insertion, Token CAS and Event")
    void splitDuplicateSuccessorsRollsBackWholeTransaction() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var run = repo.createRun(tenant, "g4b-t9-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t9-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID tokenId = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        long versionBefore = runCommitVersion(ds, tenant, run.runId());

        assertThrows(IllegalArgumentException.class, () -> repo.advanceGraphToken(tenant, run.runId(), tokenId,
                "start", "SPLIT", List.of("same", "same")),
                "a SPLIT to duplicate successors must fail after the Activation insert");

        assertEquals("READY", tokenState(ds, tenant, tokenId), "Token CAS must roll back to READY");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                tenant, tokenId), "the freshly inserted Activation must roll back");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='same'",
                tenant, run.runId()));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId()));
        assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
        assertEquals(versionBefore, runCommitVersion(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T9] SPLIT rollback: Token READY, activation=0, event_seq/commit_version unchanged");
    }

    // ---------------------------------------------------------------------
    // T10: explicit rollback - parallel branches writing the same flow_variable aborts after writes
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T10 parallel-branch flow_variable conflict rolls back Attempt/Branch writes")
    void parallelBranchFlowVariableConflictRollsBackAttemptAndBranchWrites() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var run = repo.createRun(tenant, "g4b-t10-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t10-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "split");
        repo.startRun(tenant, run.runId());
        var split = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0);
        repo.advanceGraphToken(tenant, run.runId(), split.tokenId(), "split", "SPLIT",
                List.of("branch-a", "branch-b"));

        var branchA = startPureTaskRunAtNode(ds, repo, tenant, "g4b-t10-a", run.runId(), "branch-a");
        repo.completeAttempt(tenant, run.runId(), branchA.attemptId(), "res", "{\"v\":1}", Map.of("shared", "1"));
        assertEquals(1L, flowVariableVersion(ds, tenant, run.runId(), "shared"));

        var branchB = startPureTaskRunAtNode(ds, repo, tenant, "g4b-t10-b", run.runId(), "branch-b");
        long seqBefore = runEventSeq(ds, tenant, run.runId());

        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.completeAttempt(tenant, run.runId(), branchB.attemptId(), "res", "{\"v\":2}", Map.of("shared", "2")),
                "a sibling parallel branch may not overwrite the same flow_variable key");

        assertEquals("STARTED", attemptState(ds, tenant, branchB.attemptId()), "Attempt update must roll back");
        assertEquals("RUNNING", tokenState(ds, tenant, branchB.tokenId()), "Token must roll back to RUNNING");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id=? AND activation_id=?",
                tenant, branchB.activationId()), "Branch output must roll back");
        assertEquals(1L, flowVariableVersion(ds, tenant, run.runId(), "shared"), "the first branch's value must be preserved");
        assertEquals("1", flowVariableValue(ds, tenant, run.runId(), "shared"));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id=? AND attempt_id=? AND fact_type='EXECUTION_RIGHT_RELEASED'",
                tenant, branchB.attemptId()));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='ATTEMPT_SUCCEEDED'",
                tenant, run.runId()), "only branch-a's ATTEMPT_SUCCEEDED may survive");
        assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T10] parallel-branch rollback: branch-b Attempt STARTED, branch-a value preserved, event_seq unchanged");
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private record WorkerRun(UUID runId, UUID tokenId, UUID activationId, UUID attemptId, UUID dispatchId) {}

    /** createRun(start) + startRun + reserveCapacity(PURE) + dispatch/accept/start on the single READY token. */
    private WorkerRun startPureTaskRun(DriverManagerDataSource ds, WorkflowRuntimeRepository repo,
                                       UUID tenant, String tag) throws Exception {
        var run = repo.createRun(tenant, tag + "-" + UUID.randomUUID(), "CREATE", "{}",
                tag + "-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task");
        repo.startRun(tenant, run.runId());
        return reserveAndStart(ds, repo, tenant, tag, run.runId(), "task");
    }

    /** Same as {@link #startPureTaskRun} but on a specific node of an existing Run. */
    private WorkerRun startPureTaskRunAtNode(DriverManagerDataSource ds, WorkflowRuntimeRepository repo,
                                             UUID tenant, String tag, UUID runId, String nodeKey) throws Exception {
        return reserveAndStart(ds, repo, tenant, tag, runId, nodeKey);
    }

    private WorkerRun reserveAndStart(DriverManagerDataSource ds, WorkflowRuntimeRepository repo,
                                      UUID tenant, String tag, UUID runId, String nodeKey) throws Exception {
        var reservation = repo.reserveCapacity(tenant, runId, tag + "-cap-" + tenant, 1, nodeKey, "PURE",
                Instant.now().plusSeconds(300));
        repo.dispatchAttempt(tenant, runId, reservation.attemptId(), "g4b-worker-q", "{}");
        var outbox = repo.beginOutboxSend(tenant, 50).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + reservation.attemptId()))
                .findFirst().orElseThrow();
        repo.markOutboxDelivered(tenant, outbox.outboxId());
        repo.acceptAttempt(tenant, runId, reservation.attemptId(), reservation.dispatchId(), "g4b-worker-1");
        repo.startAttempt(tenant, runId, reservation.attemptId(), reservation.dispatchId(), "g4b-worker-1");
        UUID activationId = activationForAttempt(ds, tenant, reservation.attemptId());
        UUID tokenId = tokenForActivation(ds, tenant, activationId);
        return new WorkerRun(runId, tokenId, activationId, reservation.attemptId(), reservation.dispatchId());
    }

    private int cnt(DriverManagerDataSource ds, String sql, Object... args) {
        return ((Number) one(ds, sql, args)).intValue();
    }

    private long lng(DriverManagerDataSource ds, String sql, Object... args) {
        return ((Number) one(ds, sql, args)).longValue();
    }

    private String str(DriverManagerDataSource ds, String sql, Object... args) {
        return (String) one(ds, sql, args);
    }

    private UUID uuid(DriverManagerDataSource ds, String sql, Object... args) {
        return (UUID) one(ds, sql, args);
    }

    private Object one(DriverManagerDataSource ds, String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected one row for: " + sql);
                return rs.getObject(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String runStatus(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        return str(ds, "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?", tenant, runId);
    }

    private long runEventSeq(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        return lng(ds, "SELECT event_seq FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?", tenant, runId);
    }

    private long runCommitVersion(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        return lng(ds, "SELECT commit_version FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?", tenant, runId);
    }

    private String tokenState(DriverManagerDataSource ds, UUID tenant, UUID tokenId) {
        return str(ds, "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?", tenant, tokenId);
    }

    private String activationState(DriverManagerDataSource ds, UUID tenant, UUID activationId) {
        return str(ds, "SELECT activation_state FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?", tenant, activationId);
    }

    private String attemptState(DriverManagerDataSource ds, UUID tenant, UUID attemptId) {
        return str(ds, "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?", tenant, attemptId);
    }

    private long flowVariableVersion(DriverManagerDataSource ds, UUID tenant, UUID runId, String key) {
        return lng(ds, "SELECT value_version FROM workflow_runtime.flow_variable WHERE tenant_id=? AND run_id=? AND variable_key=?",
                tenant, runId, key);
    }

    private String flowVariableValue(DriverManagerDataSource ds, UUID tenant, UUID runId, String key) {
        return str(ds, "SELECT value::text FROM workflow_runtime.flow_variable WHERE tenant_id=? AND run_id=? AND variable_key=?",
                tenant, runId, key);
    }

    private UUID activationForAttempt(DriverManagerDataSource ds, UUID tenant, UUID attemptId) {
        return uuid(ds, "SELECT activation_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?", tenant, attemptId);
    }

    private UUID tokenForActivation(DriverManagerDataSource ds, UUID tenant, UUID activationId) {
        return uuid(ds, "SELECT token_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?", tenant, activationId);
    }

    /**
     * The appendEvents contract is: one {@code commit_version+1}, {@code event_seq += N} UPDATE and
     * N contiguous {@code runtime_event} inserts. So {@code event_seq} must equal the row count and
     * the sequence must be exactly {@code 1..N} with no gaps or duplicates.
     */
    private void assertEventLogContiguous(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        int rows = cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=?", tenant, runId);
        long seq = runEventSeq(ds, tenant, runId);
        assertEquals(rows, seq, "event_seq must equal the number of persisted events (gapless append contract)");
        if (rows == 0) return;
        assertEquals(1L, lng(ds, "SELECT MIN(event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=?", tenant, runId));
        assertEquals(seq, lng(ds, "SELECT MAX(event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=?", tenant, runId));
        assertEquals(rows, lng(ds, "SELECT count(DISTINCT event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=?", tenant, runId));
    }
}
