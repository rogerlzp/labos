package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic concurrency integration tests proving conditional Split variable reads,
 * expression evaluation, and Token advancement execute atomically under the Run lock.
 */
class GraphCoordinatorConcurrencyIntegrationTest {

    private static final String PARALLEL_WITH_XOR_BPMN = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="parallel_xor_race_proc">
                <bpmn:startEvent id="start"/>
                <bpmn:parallelGateway id="split_par"/>
                <bpmn:task id="task_a" protocolName="OpA" resource="1"/>
                <bpmn:exclusiveGateway id="xor_a" default="f_old"/>
                <bpmn:endEvent id="new_end"/>
                <bpmn:endEvent id="old_end"/>
                <bpmn:task id="task_b" protocolName="OpB" resource="2"/>
                <bpmn:endEvent id="b_end"/>
                <bpmn:sequenceFlow id="f_start" sourceRef="start" targetRef="split_par"/>
                <bpmn:sequenceFlow id="f_a" sourceRef="split_par" targetRef="task_a"/>
                <bpmn:sequenceFlow id="f_a_xor" sourceRef="task_a" targetRef="xor_a"/>
                <bpmn:sequenceFlow id="f_new" sourceRef="xor_a" targetRef="new_end">
                  <bpmn:conditionExpression>route == 'v2'</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f_old" sourceRef="xor_a" targetRef="old_end"/>
                <bpmn:sequenceFlow id="f_b" sourceRef="split_par" targetRef="task_b"/>
                <bpmn:sequenceFlow id="f_b_end" sourceRef="task_b" targetRef="b_end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String INCLUSIVE_GATEWAY_BPMN = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <bpmn:process id="inclusive_race_proc">
                <bpmn:startEvent id="start"/>
                <bpmn:task id="task_init" protocolName="Init" resource="1"/>
                <bpmn:inclusiveGateway id="inc_split" default="f_fallback"/>
                <bpmn:task id="opt_a" protocolName="OptA" resource="2"/>
                <bpmn:task id="opt_b" protocolName="OptB" resource="3"/>
                <bpmn:task id="fallback" protocolName="Fallback" resource="4"/>
                <bpmn:inclusiveGateway id="inc_join"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="task_init"/>
                <bpmn:sequenceFlow id="f1" sourceRef="task_init" targetRef="inc_split"/>
                <bpmn:sequenceFlow id="f_opt_a" sourceRef="inc_split" targetRef="opt_a">
                  <bpmn:conditionExpression>enable_a == true</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f_opt_b" sourceRef="inc_split" targetRef="opt_b">
                  <bpmn:conditionExpression>enable_b == true</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f_fallback" sourceRef="inc_split" targetRef="fallback"/>
                <bpmn:sequenceFlow id="fa_join" sourceRef="opt_a" targetRef="inc_join"/>
                <bpmn:sequenceFlow id="fb_join" sourceRef="opt_b" targetRef="inc_join"/>
                <bpmn:sequenceFlow id="ff_join" sourceRef="fallback" targetRef="inc_join"/>
                <bpmn:sequenceFlow id="f_end" sourceRef="inc_join" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static DriverManagerDataSource dataSource() {
        String url = "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                + "/" + env("PGDATABASE", "runtime_test");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url, env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static UUID createTenant(DriverManagerDataSource ds, String prefix) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','" + prefix + "-" + tenant + "','coord-concurrency-it')");
        }
        return tenant;
    }

    private static long scalarLong(DriverManagerDataSource ds, String sql, Object... args) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static UUID createStartedRun(
            WorkflowRuntimeRepository repo,
            UUID tenant,
            String keyPrefix,
            String bpmnXml,
            String inputJson) {
        GraphPlan plan = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput(keyPrefix + "-" + tenant, "1", bpmnXml, Map.of()));
        var created = repo.createRun(tenant, keyPrefix + "-req-" + UUID.randomUUID(), "CREATE", "{}",
                keyPrefix + "-" + tenant, "1", HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(), GraphPlanCodec.toJson(plan), inputJson, "start");
        repo.startRun(tenant, created.runId());
        return created.runId();
    }

    private static UUID startTaskAttempt(
            WorkflowRuntimeRepository repo,
            UUID tenant,
            UUID runId,
            String nodeKey,
            String workerId) {
        var res = repo.reserveCapacity(tenant, runId, "cap-" + nodeKey + "-" + tenant, 1,
                nodeKey, "PURE", Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant, runId, res.attemptId(), "pure-q", "{}");
        var outbox = repo.beginOutboxSendForKind(tenant, "WORKER_DISPATCH", 20).stream()
                .filter(m -> m.dedupKey().equals("dispatch:" + res.attemptId()))
                .findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant, outbox.outboxId()));
        repo.acceptAttemptForExecution(tenant, runId, res.attemptId(), res.dispatchId(),
                workerId, nodeKey, "PURE");
        repo.startAttempt(tenant, runId, res.attemptId(), res.dispatchId(), workerId);
        return res.attemptId();
    }

    @Test
    void xorSplitReadsCommittedVariableAtomicallyAfterConcurrentBranchCommitBeforeSplitLock() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "xor-atomic");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        GraphCoordinator setupCoordinator = new GraphCoordinator(repo);

        UUID runId = createStartedRun(repo, tenant, "xor-atomic", PARALLEL_WITH_XOR_BPMN, "{\"route\":\"v1\"}");
        setupCoordinator.advance(tenant, runId);
        UUID attemptA = startTaskAttempt(repo, tenant, runId, "task_a", "w-a");
        UUID attemptB = startTaskAttempt(repo, tenant, runId, "task_b", "w-b");

        repo.completeAttempt(tenant, runId, attemptA, "out", "{\"ok\":true}");

        CountDownLatch beforeSplitTx = new CountDownLatch(1);
        CountDownLatch workerCommittedV2 = new CountDownLatch(1);
        CompletableFuture<Void> workerFuture = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(beforeSplitTx.await(5, TimeUnit.SECONDS), "Timed out waiting for coordinator hook");
                repo.completeAttempt(tenant, runId, attemptB, "out", "{\"ok\":true}", Map.of("route", "\"v2\""));
                workerCommittedV2.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        GraphCoordinator hookedCoordinator = new GraphCoordinator(repo, 100, 1000, () -> {
            beforeSplitTx.countDown();
            try {
                assertTrue(workerCommittedV2.await(5, TimeUnit.SECONDS), "Timed out waiting for worker V2 commit");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ie);
            }
        }, null);

        hookedCoordinator.advance(tenant, runId);
        workerFuture.get(5, TimeUnit.SECONDS);

        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='new_end' AND token_state='CONSUMED'",
                tenant, runId));
        assertEquals(0, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='old_end'",
                tenant, runId));
        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='b_end' AND token_state='CONSUMED'",
                tenant, runId));
        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=? AND status='SUCCEEDED' AND control_gate='CLOSED'",
                tenant, runId));
    }

    @Test
    void inclusiveSplitReadsCommittedVariableAtomicallyAfterConcurrentWriteBeforeSplitLock() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "inc-atomic");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        GraphCoordinator setupCoordinator = new GraphCoordinator(repo);

        UUID runId = createStartedRun(repo, tenant, "inc-atomic", INCLUSIVE_GATEWAY_BPMN,
                "{\"enable_a\":false,\"enable_b\":false}");
        setupCoordinator.advance(tenant, runId);
        UUID initAttempt = startTaskAttempt(repo, tenant, runId, "task_init", "w-init");
        repo.completeAttempt(tenant, runId, initAttempt, "out", "{\"ok\":true}");

        CountDownLatch beforeSplitTx = new CountDownLatch(1);
        CountDownLatch writerCommittedV2 = new CountDownLatch(1);
        CompletableFuture<Void> writerFuture = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(beforeSplitTx.await(5, TimeUnit.SECONDS), "Timed out waiting for inclusive split hook");
                repo.putFlowVariable(tenant, runId, "enable_a", "true");
                repo.putFlowVariable(tenant, runId, "enable_b", "true");
                writerCommittedV2.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        GraphCoordinator hookedCoordinator = new GraphCoordinator(repo, 100, 1000, () -> {
            beforeSplitTx.countDown();
            try {
                assertTrue(writerCommittedV2.await(5, TimeUnit.SECONDS), "Timed out waiting for variable writes");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ie);
            }
        }, null);

        hookedCoordinator.advance(tenant, runId);
        writerFuture.get(5, TimeUnit.SECONDS);

        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='opt_a' AND token_state='READY'",
                tenant, runId));
        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='opt_b' AND token_state='READY'",
                tenant, runId));
        assertEquals(0, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='fallback'",
                tenant, runId));
    }

    @Test
    void lockedSplitTransactionSerializesConcurrentWorkerVariableCommitWithZeroInterleaving() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds = dataSource();
        UUID tenant = createTenant(ds, "xor-lock-order");
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        GraphCoordinator coordinator = new GraphCoordinator(repo);

        UUID runId = createStartedRun(repo, tenant, "xor-lock-order", PARALLEL_WITH_XOR_BPMN, "{\"route\":\"v1\"}");
        coordinator.advance(tenant, runId);
        UUID attemptA = startTaskAttempt(repo, tenant, runId, "task_a", "w-a");
        UUID attemptB = startTaskAttempt(repo, tenant, runId, "task_b", "w-b");

        repo.completeAttempt(tenant, runId, attemptA, "out", "{\"ok\":true}");

        CountDownLatch insideLockedTxAfterRead = new CountDownLatch(1);
        CountDownLatch workerAboutToCommit = new CountDownLatch(1);
        AtomicBoolean workerCommitted = new AtomicBoolean(false);

        CompletableFuture<Void> workerFuture = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(insideLockedTxAfterRead.await(5, TimeUnit.SECONDS),
                        "Timed out waiting for coordinator to enter locked Split tx");
                workerAboutToCommit.countDown();
                repo.completeAttempt(tenant, runId, attemptB, "out", "{\"ok\":true}", Map.of("route", "\"v2\""));
                workerCommitted.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        GraphCoordinator lockedCoordinator = new GraphCoordinator(repo, 100, 1000, null, () -> {
            insideLockedTxAfterRead.countDown();
            try {
                assertTrue(workerAboutToCommit.await(5, TimeUnit.SECONDS),
                        "Timed out waiting for worker thread dispatch");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean observedLockWait = false;
                while (System.nanoTime() < deadline) {
                    long waiters = scalarLong(ds, """
                            SELECT count(*) FROM pg_stat_activity
                             WHERE datname = current_database()
                               AND usename = current_user
                               AND pid <> pg_backend_pid()
                               AND state = 'active'
                               AND wait_event_type = 'Lock'
                               AND query LIKE '%workflow_runtime.workflow_run%'
                            """);
                    if (waiters >= 1) {
                        observedLockWait = true;
                        break;
                    }
                    Thread.sleep(5);
                }
                assertTrue(observedLockWait,
                        "Worker variable commit must block on coordinator's workflow_run FOR UPDATE lock");
                assertFalse(workerCommitted.get(),
                        "Worker must not commit updated variable while split tx holds Run lock");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        lockedCoordinator.advance(tenant, runId);
        workerFuture.get(5, TimeUnit.SECONDS);
        coordinator.advance(tenant, runId);

        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='old_end' AND token_state='CONSUMED'",
                tenant, runId));
        assertEquals(0, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=? AND node_key='new_end'",
                tenant, runId));

        long splitSeq = scalarLong(ds,
                "SELECT min(event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='GRAPH_NODE_ADVANCED' AND payload->>'nodeKey'='xor_a'",
                tenant, runId);
        long varUpdateSeq = scalarLong(ds,
                "SELECT min(event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id=? AND run_id=? AND event_type='FLOW_VARIABLE_UPDATED' AND payload->>'key'='route'",
                tenant, runId);
        assertTrue(splitSeq > 0 && varUpdateSeq > splitSeq,
                "Expected strict serializability splitSeq (" + splitSeq + ") < varUpdateSeq (" + varUpdateSeq + ")");

        assertEquals(1, scalarLong(ds,
                "SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=? AND status='SUCCEEDED' AND control_gate='CLOSED'",
                tenant, runId));
    }
}
