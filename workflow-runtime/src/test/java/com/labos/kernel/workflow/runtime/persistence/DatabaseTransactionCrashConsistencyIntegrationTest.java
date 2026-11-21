package com.labos.kernel.workflow.runtime.persistence;

import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.FaultInjectingTransactionManager.InjectedCommitAbort;
import com.labos.kernel.workflow.runtime.persistence.FaultInjectingTransactionManager.InjectedLostResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        try {
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
        } finally {
            // The rolled-back branch-b Attempt is left STARTED with a HELD reservation. Abandon it so
            // the shared runtime_test DB is not polluted for order-sensitive startup-recovery suites
            // (WorkflowRuntimeRepositoryTest#startupRecoveryScansBeyondOneBatch counts ALL STARTED Attempts).
            if ("STARTED".equals(attemptStateOrNull(ds, tenant, branchB.attemptId()))) {
                repo.recoverInterruptedAttempt(tenant, run.runId(), branchB.attemptId(), "g4b-t10-cleanup");
            }
        }
    }

    // ---------------------------------------------------------------------
    // T11: conditional-split variable read aborted before commit -> branch Token stays READY
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T11 advanceGraphTokenWithVariables abort-before-commit leaves the branch Token READY and nothing durable")
    void advanceGraphTokenWithVariablesAbortBeforeCommitRollsBackFully() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t11-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t11-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID startToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        repo.advanceGraphToken(tenant, run.runId(), startToken, "start", "SEQUENCE", List.of("choice"));
        UUID choiceToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        long versionBefore = runCommitVersion(ds, tenant, run.runId());
        int advancedBefore = cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(InjectedCommitAbort.class, () -> repo.advanceGraphTokenWithVariables(
                tenant, run.runId(), choiceToken, "choice", snapshot -> {
                    assertNotNull(snapshot, "the conditional split must read variables inside the same Run-locked transaction");
                    return new WorkflowRuntimeRepository.GraphTokenTransitionDecision(
                            "SEQUENCE", List.of("end-a"), null);
                }));
        faults.disarm();

        assertEquals("READY", tokenState(ds, tenant, choiceToken), "conditional-split Token must roll back to READY");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                tenant, choiceToken), "no Activation may be durable");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a'",
                tenant, run.runId()), "no successor Token may be durable");
        assertEquals(advancedBefore, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId()), "the aborted conditional split must not append a GRAPH_NODE_ADVANCED event");
        assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
        assertEquals(versionBefore, runCommitVersion(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T11] conditional-split abort-before-commit: choice Token READY, no Activation/successor/event");

        var retry = repo.advanceGraphTokenWithVariables(tenant, run.runId(), choiceToken, "choice",
                snapshot -> new WorkflowRuntimeRepository.GraphTokenTransitionDecision(
                        "SEQUENCE", List.of("end-a"), null));
        assertEquals("ADVANCED", retry.outcome());
        assertEquals("CONSUMED", tokenState(ds, tenant, choiceToken));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a' AND token_state='READY'",
                tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T11] retry after abort: ADVANCED, selected successor end-a READY, event log contiguous");
    }

    // ---------------------------------------------------------------------
    // T12: conditional-split lost-result-after-commit -> durable, replay fails closed
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T12 advanceGraphTokenWithVariables lost-result-after-commit is durable and replay fails closed")
    void advanceGraphTokenWithVariablesLostResultAfterCommitIsDurableAndReplayFailsClosed() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t12-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t12-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID startToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        repo.advanceGraphToken(tenant, run.runId(), startToken, "start", "SEQUENCE", List.of("choice"));
        UUID choiceToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        long versionBefore = runCommitVersion(ds, tenant, run.runId());
        int advancedBefore = cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId());

        faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(InjectedLostResult.class, () -> repo.advanceGraphTokenWithVariables(
                tenant, run.runId(), choiceToken, "choice",
                snapshot -> new WorkflowRuntimeRepository.GraphTokenTransitionDecision(
                        "SEQUENCE", List.of("end-a"), null)));
        faults.disarm();

        assertEquals("CONSUMED", tokenState(ds, tenant, choiceToken), "committed split must consume the source Token");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=? AND activation_state='SUCCEEDED'",
                tenant, choiceToken));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a' AND token_state='READY' AND parent_token_id=?",
                tenant, run.runId(), choiceToken), "the committed successor Token must be durable");
        assertEquals(advancedBefore + 1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId()));
        assertEquals(seqBefore + 1, runEventSeq(ds, tenant, run.runId()));
        assertEquals(versionBefore + 1, runCommitVersion(ds, tenant, run.runId()));
        assertEquals("RUNNING", runStatus(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T12] conditional-split lost-result-after-commit: choice CONSUMED, end-a READY, event_seq=" +
                runEventSeq(ds, tenant, run.runId()));

        assertThrows(WorkflowRuntimeRepository.StaleRuntimeWriteException.class,
                () -> repo.advanceGraphTokenWithVariables(tenant, run.runId(), choiceToken, "choice",
                        snapshot -> new WorkflowRuntimeRepository.GraphTokenTransitionDecision(
                                "SEQUENCE", List.of("end-b"), null)),
                "replaying the conditional split on a consumed Token must fail closed");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a'",
                tenant, run.runId()), "replay must not create a second successor");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-b'",
                tenant, run.runId()));
        assertEquals(seqBefore + 1, runEventSeq(ds, tenant, run.runId()));
        System.out.println("[T12] replay after lost result: StaleRuntimeWriteException, no duplicate successor/event");
    }

    // ---------------------------------------------------------------------
    // T13: conditional-split resolver throws BEFORE any write -> no partial state, retry succeeds.
    // NOTE: this is NOT a "write-then-rollback" case. The production order in
    // advanceGraphTokenInternal() is lockRun -> lock READY token -> load variable snapshot ->
    // transitionResolver.apply(snapshot) -> INSERT node_activation -> UPDATE execution_token ->
    // advance successors -> appendEvents. The resolver runs before the first write, so at the
    // moment of the fault no SQL has executed. True write-then-rollback is covered by T9/T10.
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T13 conditional-split resolver exception before any write leaves no partial state and retry succeeds")
    void resolverExceptionBeforeMutationLeavesNoPartialState() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var run = repo.createRun(tenant, "g4b-t13-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t13-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID startToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        repo.advanceGraphToken(tenant, run.runId(), startToken, "start", "SEQUENCE", List.of("choice"));
        UUID choiceToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        int advancedBefore = cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId());

        assertThrows(IllegalStateException.class, () -> repo.advanceGraphTokenWithVariables(
                tenant, run.runId(), choiceToken, "choice",
                snapshot -> { throw new IllegalStateException("no matching conditional flow"); }),
                "a resolver fault is raised before the first write, so no partial state may remain");

        assertEquals("READY", tokenState(ds, tenant, choiceToken), "Token must still be READY (it was never mutated)");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                tenant, choiceToken), "no Activation may exist after the resolver fault");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a'",
                tenant, run.runId()));
        assertEquals(advancedBefore, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, run.runId()));
        assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T13] resolver exception before any write: choice Token READY, no activation/successor/event");

        var retry = repo.advanceGraphTokenWithVariables(tenant, run.runId(), choiceToken, "choice",
                snapshot -> new WorkflowRuntimeRepository.GraphTokenTransitionDecision(
                        "SEQUENCE", List.of("end-a"), null));
        assertEquals("ADVANCED", retry.outcome());
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T13] retry after resolver exception: ADVANCED");
    }

    // ---------------------------------------------------------------------
    // T14: SPLIT -> JOIN -> END; abort on the Join release rolls back branch/split/join touches
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T14 SPLIT -> JOIN -> END abort on Join release rolls back branch/split/join and retry completes the Run")
    void splitJoinEndAbortOnJoinReleaseRollsBackAndRetryCompletes() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));

        var run = repo.createRun(tenant, "g4b-t14-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t14-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID startToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        repo.advanceGraphToken(tenant, run.runId(), startToken, "start", "SEQUENCE", List.of("split"));
        UUID splitToken = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        repo.advanceGraphToken(tenant, run.runId(), splitToken, "split", "SPLIT", List.of("branch-a", "branch-b"));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id=? AND run_id=? AND split_state='OPEN'",
                tenant, run.runId()));
        assertEquals(2, cnt(ds, "SELECT count(*) FROM workflow_runtime.branch_activation WHERE tenant_id=? AND run_id=? AND branch_state='ACTIVE'",
                tenant, run.runId()));

        // Branch A arrives at the Join first: not all branches arrived, so the Join must NOT release yet.
        var a = startTaskRunAtNode(ds, repo, tenant, "g4b-t14-a", run.runId(), "branch-a", "PURE");
        repo.completeAttempt(tenant, run.runId(), a.attemptId(), "res", "{\"v\":1}");
        repo.advanceCompletedGraphActivation(tenant, run.runId(), a.activationId(), "SEQUENCE", List.of("join"));
        UUID joinTokenA = readyTokenAtNode(ds, tenant, run.runId(), "join");
        assertFalse(repo.advanceGraphToken(tenant, run.runId(), joinTokenA, "join", "JOIN", List.of("end"), "join").runCompleted());
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id=? AND run_id=? AND join_state='WAITING'",
                tenant, run.runId()));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, run.runId()));

        var b = startTaskRunAtNode(ds, repo, tenant, "g4b-t14-b", run.runId(), "branch-b", "PURE");
        repo.completeAttempt(tenant, run.runId(), b.attemptId(), "res", "{\"v\":2}");
        repo.advanceCompletedGraphActivation(tenant, run.runId(), b.activationId(), "SEQUENCE", List.of("join"));
        UUID joinTokenB = readyTokenAtNode(ds, tenant, run.runId(), "join");
        long seqBefore = runEventSeq(ds, tenant, run.runId());

        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        // Rebuild the Repository over a fault-injecting transaction manager only for the abort step.
        var fRepo = new WorkflowRuntimeRepository(ds, faults);
        faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertThrows(InjectedCommitAbort.class, () -> fRepo.advanceGraphToken(tenant, run.runId(),
                joinTokenB, "join", "JOIN", List.of("end"), "join"));
        faults.disarm();

        assertEquals("READY", tokenState(ds, tenant, joinTokenB), "the Join arrival Token must roll back to READY");
        assertEquals("ACTIVE", branchState(ds, tenant, run.runId(), "branch-b"), "branch-b must not stay ARRIVED");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id=? AND run_id=? AND split_state='OPEN' AND arrived_branch_count=1",
                tenant, run.runId()), "the Split arrival counter must roll back");
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id=? AND run_id=? AND join_state='WAITING'",
                tenant, run.runId()), "the Join must remain WAITING");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end'",
                tenant, run.runId()), "no successor Token may be durable");
        assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T14] abort on Join release: branch-b ACTIVE, split OPEN(arrived=1), join WAITING, no end Token, event_seq unchanged");

        var released = repo.advanceGraphToken(tenant, run.runId(), joinTokenB, "join", "JOIN", List.of("end"), "join");
        assertEquals(1, released.tokensCreated());
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id=? AND run_id=? AND split_state='CLOSED'",
                tenant, run.runId()));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id=? AND run_id=? AND join_state='RELEASED'",
                tenant, run.runId()));
        UUID endToken = readyTokenAtNode(ds, tenant, run.runId(), "end");
        assertTrue(repo.advanceGraphToken(tenant, run.runId(), endToken, "end", "END", List.of()).runCompleted(),
                "reaching END with no remaining blocker must complete the Run");
        assertEquals("SUCCEEDED", runStatus(ds, tenant, run.runId()));
        assertEventLogContiguous(ds, tenant, run.runId());
        System.out.println("[T14] retry after abort: Join RELEASED, Split CLOSED, end Token consumed, Run SUCCEEDED");
    }

    // ---------------------------------------------------------------------
    // T15: GraphCoordinator.advance multi-transaction crash - never terminalizes a healthy Run
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T15 GraphCoordinator.advance aborting the 2nd transaction keeps step 1 durable, rolls step 2 back, and never calls failGraphRun")
    void graphCoordinatorAdvanceAbortOnSecondTransactionDoesNotFailRunAndResumes() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        UUID runId = createConditionalRun(repo, tenant, "g4b-t15", "{\"takeA\":true}");
        var coordinator = new GraphCoordinator(repo);
        UUID startToken = repo.findReadyGraphTokens(tenant, runId, 10).get(0).tokenId();

        faults.armAtWrite(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT, 2);
        assertThrows(InjectedCommitAbort.class, () -> coordinator.advance(tenant, runId));

        // A DB-level fault is NOT one of IllegalStateException/IllegalArgumentException/InvalidTransitionException,
        // so the Coordinator must NOT call failGraphRun and the healthy Run must stay RUNNING / OPEN.
        assertEquals("RUNNING", runStatus(ds, tenant, runId));
        assertEquals("OPEN", controlGate(ds, tenant, runId));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id=? AND run_id=? AND intervention_state='OPEN'",
                tenant, runId));
        // Step 1 (START SEQUENCE) is durable; step 2 (conditional split) is fully rolled back.
        assertEquals("CONSUMED", tokenState(ds, tenant, startToken));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=? AND activation_state='SUCCEEDED'",
                tenant, startToken));
        UUID choiceToken = repo.findReadyGraphTokens(tenant, runId, 10).get(0).tokenId();
        assertEquals("choice", tokenNodeKey(ds, tenant, choiceToken));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND token_id=?",
                tenant, choiceToken), "the aborted step must leave no Activation behind");
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a'",
                tenant, runId));
        assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED'",
                tenant, runId), "only step 1's GRAPH_NODE_ADVANCED may survive");
        System.out.println("[T15] 2nd-tx abort: Run RUNNING/OPEN, no failGraphRun; step 1 durable, step 2 rolled back");

        var summary = coordinator.advance(tenant, runId);
        assertTrue(summary.runCompleted(), "a subsequent advance must resume and complete the Run");
        assertEquals("SUCCEEDED", runStatus(ds, tenant, runId));
        assertEventLogContiguous(ds, tenant, runId);
        System.out.println("[T15] resume after 2nd-tx abort: Run SUCCEEDED");
    }

    // ---------------------------------------------------------------------
    // T16: GraphCoordinator.advance lost result after commit - recovery by re-discovery
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T16 GraphCoordinator.advance lost result after commit keeps the Run healthy and the next advance re-discovers and completes")
    void graphCoordinatorAdvanceLostResultRecoversByRediscovery() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        UUID runId = createConditionalRun(repo, tenant, "g4b-t16", "{\"takeA\":true}");
        var coordinator = new GraphCoordinator(repo);
        UUID startToken = repo.findReadyGraphTokens(tenant, runId, 10).get(0).tokenId();

        faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
        assertThrows(InjectedLostResult.class, () -> coordinator.advance(tenant, runId));

        // The first transaction committed but the caller lost the summary: no failGraphRun, and the
        // committed step must be durable and re-discoverable on the next advance.
        assertEquals("RUNNING", runStatus(ds, tenant, runId));
        assertEquals("OPEN", controlGate(ds, tenant, runId));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id=? AND run_id=? AND intervention_state='OPEN'",
                tenant, runId));
        assertEquals("CONSUMED", tokenState(ds, tenant, startToken));
        UUID choiceToken = repo.findReadyGraphTokens(tenant, runId, 10).get(0).tokenId();
        assertEquals("choice", tokenNodeKey(ds, tenant, choiceToken));
        assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='end-a'",
                tenant, runId));
        System.out.println("[T16] lost result after commit: Run RUNNING/OPEN, no failGraphRun, committed step re-discoverable");

        var summary = coordinator.advance(tenant, runId);
        assertTrue(summary.runCompleted(), "re-discovery must auto-advance the committed successor to completion");
        assertEquals("SUCCEEDED", runStatus(ds, tenant, runId));
        assertEventLogContiguous(ds, tenant, runId);
        System.out.println("[T16] re-discovery after lost result: Run SUCCEEDED");
    }

    // ---------------------------------------------------------------------
    // T17: reserveCapacity abort-before-commit -> capacity/token/activation/attempt/reservation/event all roll back
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T17 reserveCapacity abort-before-commit rolls back capacity/token/activation/attempt/reservation/event")
    void reserveCapacityAbortBeforeCommitRollsBackFully() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t17-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t17-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task");
        repo.startRun(tenant, run.runId());
        UUID tokenId = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
        long seqBefore = runEventSeq(ds, tenant, run.runId());
        try {
            faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
            assertThrows(InjectedCommitAbort.class, () -> repo.reserveCapacity(tenant, run.runId(),
                    "g4b-t17-cap", 1, "task", "PURE", Instant.now().plusSeconds(300)));
            faults.disarm();

            assertEquals(0, capacityReservedCount(ds, tenant, "g4b-t17-cap"), "capacity must not stay reserved");
            assertEquals("READY", tokenState(ds, tenant, tokenId), "Token must roll back to READY");
            assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND run_id=?", tenant, run.runId()));
            assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=?", tenant, run.runId()));
            assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.dispatch_reservation WHERE tenant_id=? AND run_id=?", tenant, run.runId()));
            assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='DISPATCH_RESERVED'",
                    tenant, run.runId()));
            assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
            System.out.println("[T17] reserveCapacity abort: capacity=0, Token READY, no activation/attempt/reservation/event");

            var retry = repo.reserveCapacity(tenant, run.runId(), "g4b-t17-cap", 1, "task", "PURE",
                    Instant.now().plusSeconds(300));
            assertNotNull(retry.attemptId());
            assertEquals("RESERVED", tokenState(ds, tenant, tokenId));
            assertEquals(1, capacityReservedCount(ds, tenant, "g4b-t17-cap"));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.dispatch_reservation WHERE tenant_id=? AND run_id=? AND reservation_state='HELD'",
                    tenant, run.runId()));
            assertEventLogContiguous(ds, tenant, run.runId());
            System.out.println("[T17] retry after abort: capacity reserved, Token RESERVED, reservation HELD");
        } finally {
            faults.disarm();
            abandonIfInterrupted(repo, ds, tenant, run.runId(), "g4b-t17-cleanup");
        }
    }

    // ---------------------------------------------------------------------
    // T18: reserveCapacity lost-result-after-commit -> durable across all tables, blind retry fails closed
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T18 reserveCapacity lost-result-after-commit is durable and a blind retry fails closed")
    void reserveCapacityLostResultAfterCommitIsDurable() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t18-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t18-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "task");
        repo.startRun(tenant, run.runId());
        try {
            UUID tokenId = repo.findReadyGraphTokens(tenant, run.runId(), 10).get(0).tokenId();
            long seqBefore = runEventSeq(ds, tenant, run.runId());
            long versionBefore = runCommitVersion(ds, tenant, run.runId());

            faults.armOneShot(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
            assertThrows(InjectedLostResult.class, () -> repo.reserveCapacity(tenant, run.runId(),
                    "g4b-t18-cap", 1, "task", "PURE", Instant.now().plusSeconds(300)));
            faults.disarm();

            assertEquals("RESERVED", tokenState(ds, tenant, tokenId), "the committed reservation must be durable");
            assertEquals(1, capacityReservedCount(ds, tenant, "g4b-t18-cap"));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id=? AND run_id=? AND activation_state='RESERVED'",
                    tenant, run.runId()));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=? AND attempt_state='CREATED'",
                    tenant, run.runId()));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.dispatch_reservation WHERE tenant_id=? AND run_id=? AND reservation_state='HELD'",
                    tenant, run.runId()));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='DISPATCH_RESERVED'",
                    tenant, run.runId()));
            assertEquals(seqBefore + 1, runEventSeq(ds, tenant, run.runId()));
            assertEquals(versionBefore + 1, runCommitVersion(ds, tenant, run.runId()));
            assertEventLogContiguous(ds, tenant, run.runId());
            System.out.println("[T18] reserveCapacity lost-result-after-commit: Token RESERVED, capacity=1, reservation HELD, ev=" +
                    runEventSeq(ds, tenant, run.runId()));

            // reserveCapacity carries no caller idempotency key: a blind retry must fail closed, never oversell.
            assertThrows(IllegalStateException.class, () -> repo.reserveCapacity(tenant, run.runId(),
                    "g4b-t18-cap", 1, "task", "PURE", Instant.now().plusSeconds(300)));
            assertEquals(1, capacityReservedCount(ds, tenant, "g4b-t18-cap"), "a rejected retry must not double-reserve");
            System.out.println("[T18] blind retry after lost result: fail-closed, capacity still 1");
        } finally {
            faults.disarm();
            abandonIfInterrupted(repo, ds, tenant, run.runId(), "g4b-t18-cleanup");
        }
    }

    // ---------------------------------------------------------------------
    // T19: applyCommandEvent abort-before-commit -> command_ledger/inbox_receipt/fact/event roll back together
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T19 applyCommandEvent abort-before-commit rolls back command_ledger/inbox_receipt/fact/event together")
    void applyCommandEventAbortBeforeCommitRollsBackAllTables() throws Exception {
        assumeEnabled();
        var ds = dataSource();
        UUID tenant = newTenant(ds);
        var faults = new FaultInjectingTransactionManager(new DataSourceTransactionManager(ds));
        var repo = new WorkflowRuntimeRepository(ds, faults);

        var run = repo.createRun(tenant, "g4b-t19-" + UUID.randomUUID(), "CREATE", "{}",
                "g4b-t19-flow-" + tenant, "1", new byte[32], "ci", "{}", "{}", "dev-task");
        repo.startRun(tenant, run.runId());
        try {
            var w = startTaskRunAtNode(ds, repo, tenant, "g4b-t19", run.runId(), "dev-task", "DEVICE");
            repo.registerWorkstationSidecar(tenant, "ws-device-1", "ndjson://127.0.0.1:20060", "DEV_LOOPBACK_PLAINTEXT");
            UUID commandId = repo.createCommand(tenant, run.runId(), w.activationId(), w.attemptId(),
                    "ws-device-1", "device-1", "RunMethod", "RunMethod", "QUERYABLE", "{\"method\":\"x\"}");
            var commandOutbox = repo.beginOutboxSend(tenant, 20).stream()
                    .filter(m -> m.dedupKey().equals("command:" + commandId)).findFirst().orElseThrow();
            assertTrue(repo.markCommandSendStarted(tenant, commandId, 0L, commandOutbox.outboxId(),
                    UUID.randomUUID(), 1, "dispatcher-1"));
            // Match the real async-send path: the dispatcher marks the Outbox DELIVERED once the
            // device accepted the send, and only then does the device result arrive. Doing this
            // before receiveInbox() keeps T19 focused on applyCommandEvent's transaction and avoids
            // leaving a SENDING+SUCCEEDED Outbox behind.
            assertTrue(repo.markOutboxDelivered(tenant, commandOutbox.outboxId()));
            var receipt = repo.receiveInbox(tenant, "device-1", "g4b-t19-msg-" + UUID.randomUUID(),
                    "DEVICE_COMMAND_RESULT", run.runId(),
                    "{\"commandId\":\"" + commandId + "\",\"state\":\"SUCCEEDED\"}");
            long seqBefore = runEventSeq(ds, tenant, run.runId());

            faults.armOneShot(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
            assertThrows(InjectedCommitAbort.class, () -> repo.applyCommandEvent(tenant, run.runId(),
                    receipt.inboxId(), commandId, "SUCCEEDED", "seq-1", "{\"verified\":true}"));
            faults.disarm();

            assertEquals("SEND_STARTED", str(ds, "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_id=?",
                    tenant, commandId), "Command state must roll back");
            assertEquals("RECEIVED", str(ds, "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                    tenant, receipt.inboxId()), "Inbox receipt must stay unconsumed");
            assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_COMPLETED'",
                    tenant, w.attemptId()));
            assertEquals(0, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='DEVICE_COMMAND_SUCCEEDED'",
                    tenant, run.runId()));
            assertEquals(seqBefore, runEventSeq(ds, tenant, run.runId()));
            assertEventLogContiguous(ds, tenant, run.runId());
            System.out.println("[T19] applyCommandEvent abort: command SEND_STARTED, receipt RECEIVED, no fact/event");

            assertTrue(repo.applyCommandEvent(tenant, run.runId(), receipt.inboxId(), commandId,
                    "SUCCEEDED", "seq-1", "{\"verified\":true}"));
            assertEquals("SUCCEEDED", str(ds, "SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_id=?",
                    tenant, commandId));
            assertEquals("PROCESSED", str(ds, "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                    tenant, receipt.inboxId()));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_COMPLETED'",
                    tenant, w.attemptId()));
            assertEquals(1, cnt(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='DEVICE_COMMAND_SUCCEEDED'",
                    tenant, run.runId()));
            assertEquals("DELIVERED", str(ds, "SELECT outbox_state FROM workflow_runtime.runtime_outbox WHERE tenant_id=? AND outbox_id=?",
                    tenant, commandOutbox.outboxId()), "T19 must not leave a SENDING Outbox behind");
            assertEventLogContiguous(ds, tenant, run.runId());
            System.out.println("[T19] retry after abort: command SUCCEEDED, receipt PROCESSED, outbox DELIVERED, fact/event durable");
        } finally {
            faults.disarm();
            abandonIfInterrupted(repo, ds, tenant, run.runId(), "g4b-t19-cleanup");
        }
    }

    // ---------------------------------------------------------------------
    // T20: the test-only fault-injection decorator itself must be concurrency-safe. Arming a
    // one-shot fault and racing many write commits must fault exactly one of them. This pins the
    // CAS-loop fix so a future refactor cannot silently reintroduce the old
    // get()+updateAndGet() race (two threads both observing writeOrdinal==1 and double-injecting).
    // It needs no database: only the decorator's commit-boundary decision is exercised.
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("T20 armOneShot is concurrency-safe: exactly one of many racing write commits is faulted")
    void oneShotFaultInjectionIsConcurrencySafeUnderRace() throws Exception {
        assertExactlyOneFaultUnderRace(FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT);
        assertExactlyOneFaultUnderRace(FaultInjectingTransactionManager.Mode.LOST_RESULT_AFTER_COMMIT);
    }

    private void assertExactlyOneFaultUnderRace(FaultInjectingTransactionManager.Mode mode) throws Exception {
        int threads = 16;
        int iterations = 30; // repeat so a reintroduced race is caught reliably, not just by luck
        int normalCommitTotal = 0;
        int rollbackTotal = 0;
        for (int iter = 0; iter < iterations; iter++) {
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger rollbacks = new AtomicInteger();
            AtomicInteger aborts = new AtomicInteger();
            AtomicInteger lost = new AtomicInteger();
            PlatformTransactionManager stub = new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    commits.incrementAndGet();
                }

                @Override
                public void rollback(TransactionStatus status) {
                    rollbacks.incrementAndGet();
                }
            };
            var faults = new FaultInjectingTransactionManager(stub);
            faults.armOneShot(mode);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    TransactionStatus status = stub.getTransaction(null);
                    try {
                        start.await();
                        faults.commit(status);
                    } catch (InjectedCommitAbort e) {
                        aborts.incrementAndGet();
                    } catch (InjectedLostResult e) {
                        lost.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }, "g4b-t20-race-" + i).start();
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all racing commits must finish");

            assertEquals(1, aborts.get() + lost.get(),
                    mode + ": exactly one of " + threads + " racing commits may be faulted");
            if (mode == FaultInjectingTransactionManager.Mode.ABORT_BEFORE_COMMIT) {
                assertEquals(1, aborts.get(), "ABORT mode must abort exactly one commit");
                assertEquals(1, rollbacks.get(), "ABORT mode must physically roll back exactly once");
                assertEquals(threads - 1, commits.get(), "every other racing commit must commit normally");
            } else {
                assertEquals(1, lost.get(), "LOST_RESULT mode must lose exactly one result");
                assertEquals(0, aborts.get(), "LOST_RESULT mode must not abort");
                assertEquals(0, rollbacks.get(), "LOST_RESULT mode must not roll back");
                assertEquals(threads, commits.get(), "every write is physically committed; only one caller loses the result");
            }
            normalCommitTotal += commits.get();
            rollbackTotal += rollbacks.get();
        }
        System.out.println("[T20] " + mode + ": " + iterations + "x" + threads + " racing commits -> exactly 1 fault each, "
                + normalCommitTotal + " normal commits, " + rollbackTotal + " rollbacks");
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

    /** Same as {@link #reserveAndStart} but with an explicit side-effect class (PURE or DEVICE). */
    private WorkerRun startTaskRunAtNode(DriverManagerDataSource ds, WorkflowRuntimeRepository repo,
                                         UUID tenant, String tag, UUID runId, String nodeKey,
                                         String sideEffectClass) throws Exception {
        var reservation = repo.reserveCapacity(tenant, runId, tag + "-cap-" + tenant, 1, nodeKey, sideEffectClass,
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

    private String attemptStateOrNull(DriverManagerDataSource ds, UUID tenant, UUID attemptId) {
        return strOrNull(ds, "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?",
                tenant, attemptId);
    }

    private UUID singleAttemptId(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        return uuid(ds, "SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=? ORDER BY created_at LIMIT 1",
                tenant, runId);
    }

    private List<UUID> attemptIds(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=? ORDER BY created_at,attempt_id")) {
            ps.setObject(1, tenant);
            ps.setObject(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getObject(1, UUID.class));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    /**
     * Best-effort test cleanup that keeps the shared {@code runtime_test} DB clean for order-sensitive
     * suites such as {@code WorkflowRuntimeRepositoryTest#startupRecoveryScansBeyondOneBatch}, which
     * globally counts ALL Attempts still in the startup-recovery scan set
     * ({@code CREATED/DISPATCHED/ACCEPTED/STARTED}). Each G4-B fixture uses a fresh random tenant, so
     * any Attempt left behind would otherwise be picked up by an unrelated suite's recovery scan.
     *
     * <p>A never-dispatched Attempt with no resolved side effect is abandoned safely; a DEVICE Attempt
     * whose Command already reached {@code SUCCEEDED} (and whose side effect is resolved) is completed
     * instead, so that recovery never escalates the Run to {@code MANUAL_REQUIRED} for a completed
     * external side effect.
     */
    private void abandonIfInterrupted(WorkflowRuntimeRepository repo, DriverManagerDataSource ds,
                                      UUID tenant, UUID runId, String actor) {
        for (UUID attemptId : attemptIds(ds, tenant, runId)) {
            String state = attemptStateOrNull(ds, tenant, attemptId);
            if (state == null || !List.of("CREATED", "DISPATCHED", "ACCEPTED", "STARTED").contains(state)) continue;
            boolean sideEffectCompleted = cnt(ds, """
                    SELECT count(*) FROM workflow_runtime.worker_execution_fact
                     WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_COMPLETED'
                    """, tenant, attemptId) > 0;
            if (sideEffectCompleted) {
                repo.completeAttempt(tenant, runId, attemptId, "cleanup-output", "{}", Map.of());
            } else {
                repo.recoverInterruptedAttempt(tenant, runId, attemptId, actor);
            }
        }
    }

    private String tokenNodeKey(DriverManagerDataSource ds, UUID tenant, UUID tokenId) {
        return str(ds, "SELECT node_key FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?", tenant, tokenId);
    }

    private String controlGate(DriverManagerDataSource ds, UUID tenant, UUID runId) {
        return str(ds, "SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?", tenant, runId);
    }

    private UUID readyTokenAtNode(DriverManagerDataSource ds, UUID tenant, UUID runId, String nodeKey) {
        return uuid(ds, """
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key=? AND token_state='READY'
                 ORDER BY created_at,token_id LIMIT 1
                """, tenant, runId, nodeKey);
    }

    private String branchState(DriverManagerDataSource ds, UUID tenant, UUID runId, String branchKey) {
        return str(ds, "SELECT branch_state FROM workflow_runtime.branch_activation WHERE tenant_id=? AND run_id=? AND branch_key=?",
                tenant, runId, branchKey);
    }

    /** Returns 0 when no scheduler_capacity row exists (an aborted reserveCapacity rolls the whole row back). */
    private int capacityReservedCount(DriverManagerDataSource ds, UUID tenant, String capacityKey) {
        return cnt(ds, "SELECT COALESCE(MAX(reserved_count),0) FROM workflow_runtime.scheduler_capacity WHERE tenant_id=? AND capacity_key=?",
                tenant, capacityKey);
    }

    /**
     * createRun(start) + startRun for a minimal XOR plan: {@code start -> choice -> {end-a (takeA == true), end-b (default)}}.
     * The graph plan is persisted so {@link GraphCoordinator} can re-read and advance it in multiple transactions.
     */
    private UUID createConditionalRun(WorkflowRuntimeRepository repo, UUID tenant, String tag,
                                      String inputJson) {
        GraphPlan plan = new GraphPlan(1, "ci", tag + "-flow", "1", "test-hash",
                List.of(
                        new GraphPlan.Node("start", "Start", GraphPlan.Kind.START, "start", null, null, null, null, null, null),
                        new GraphPlan.Node("choice", "Choice", GraphPlan.Kind.SPLIT_XOR, "choice", null, null, null, null, null, null),
                        new GraphPlan.Node("end-a", "End A", GraphPlan.Kind.END, "end-a", null, null, null, null, null, null),
                        new GraphPlan.Node("end-b", "End B", GraphPlan.Kind.END, "end-b", null, null, null, null, null, null)),
                List.of(
                        new GraphPlan.Edge("start-choice", "start", "choice", null, false),
                        new GraphPlan.Edge("choice-a", "choice", "end-a", "takeA == true", false),
                        new GraphPlan.Edge("choice-b", "choice", "end-b", null, true)));
        GraphCoordinator.validateGraphPlan(plan);
        var run = repo.createRun(tenant, tag + "-" + UUID.randomUUID(), "CREATE", "{}",
                tag + "-flow-" + tenant, "1", new byte[32], "ci", GraphPlanCodec.toJson(plan), inputJson, "start");
        repo.startRun(tenant, run.runId());
        return run.runId();
    }

    private String strOrNull(DriverManagerDataSource ds, String sql, Object... args) {
        return (String) oneOrNull(ds, sql, args);
    }

    private Object oneOrNull(DriverManagerDataSource ds, String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
