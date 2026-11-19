package com.labos.kernel.workflow.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostgresWorkflowRuntimeDispatchRepositoryTest {
    private record Fixture(PostgresWorkflowRuntimeDispatchRepository repository,
                           PostgresWorkflowRuntimeCompletionRepository completion,
                           JdbcTemplate jdbc) {}

    private Fixture fixture() {
        String url = System.getenv("LABOS_RUNTIME_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL test DB not configured");
        String schema = System.getenv().getOrDefault("LABOS_RUNTIME_TEST_SCHEMA", "public");
        Assumptions.assumeTrue(schema.matches("[A-Za-z_][A-Za-z0-9_]*"), "invalid test schema");
        String jdbcUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DriverManagerDataSource ds = new DriverManagerDataSource(
                jdbcUrl,
                System.getenv().getOrDefault("LABOS_RUNTIME_TEST_USER", ""),
                System.getenv().getOrDefault("LABOS_RUNTIME_TEST_PASSWORD", ""));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        PostgresRuntimeTransactionExecutor tx = new PostgresRuntimeTransactionExecutor(new DataSourceTransactionManager(ds));
        return new Fixture(new PostgresWorkflowRuntimeDispatchRepository(jdbc, tx),
                new PostgresWorkflowRuntimeCompletionRepository(jdbc, tx), jdbc);
    }

    private String hash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private record PreparedRun(UUID runId, UUID tokenId, UUID activationId, String capacityKey) {}

    private PreparedRun prepareRun(JdbcTemplate jdbc) {
        UUID run = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID activation = UUID.randomUUID();
        String definitionId = "definition-" + UUID.randomUUID();
        String definitionHash = hash();
        String capacityKey = "worker-pool-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wf_workflow_definition_version
                  (tenant_id,definition_id,definition_version,definition_hash,compiler_version,
                   definition_payload,graph_plan_payload)
                VALUES (7,?,'1',?,'compiler-v1','{}'::jsonb,'{}'::jsonb)
                """, definitionId, definitionHash);
        jdbc.update("""
                INSERT INTO wf_workflow_run
                  (run_id,tenant_id,request_id,request_payload_hash,definition_id,definition_version,
                   definition_hash,compiler_version,status,run_commit_version,event_seq,
                   dispatch_gate_open,max_in_flight,in_flight_count)
                VALUES (?,7,?,?,?,'1',?,'compiler-v1','RUNNING',1,1,true,1,0)
                """, run, "request-" + UUID.randomUUID(), hash(), definitionId, definitionHash);
        jdbc.update("""
                INSERT INTO wf_execution_token(token_id,run_id,current_node_id,status)
                VALUES (?,?,'task-node','READY')
                """, token, run);
        jdbc.update("""
                INSERT INTO wf_node_activation(activation_id,run_id,token_id,node_id,status)
                VALUES (?,?,?,'task-node','READY')
                """, activation, run, token);
        jdbc.update("""
                INSERT INTO wf_scheduler_capacity(capacity_key,capacity_limit,reserved_count)
                VALUES (?,1,0)
                """, capacityKey);
        return new PreparedRun(run, token, activation, capacityKey);
    }

    @Test
    void t03AndT04DispatchAcceptanceAndStartAreSeparateIdempotentTransactions() {
        Fixture f = fixture();
        PreparedRun p = prepareRun(f.jdbc());
        UUID attempt = UUID.randomUUID();
        UUID dispatch = UUID.randomUUID();
        UUID outbox = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        String payloadHash = hash();
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("tokenId", p.tokenId().toString()),
                Map.entry("activationId", p.activationId().toString()),
                Map.entry("attemptId", attempt.toString()),
                Map.entry("dispatchId", dispatch.toString()),
                Map.entry("outboxId", outbox.toString()),
                Map.entry("reservationId", reservation.toString()),
                Map.entry("capacityKey", p.capacityKey()),
                Map.entry("expiresAt", Instant.now().plusSeconds(120).toString()),
                Map.entry("sideEffectClass", "PURE"),
                Map.entry("attemptNo", 1),
                Map.entry("workerPayloadJson", "{}")
        );
        var dispatchRequest = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "dispatch-" + dispatch, "dispatch-message", payloadHash, 1, 0, payload);

        var committed = f.repository().reserveAndCommitDispatch(dispatchRequest);
        assertEquals("DISPATCH_COMMITTED", committed.outcome());
        assertEquals(2, committed.runCommitVersion());

        var replay = f.repository().reserveAndCommitDispatch(dispatchRequest);
        assertEquals("REPLAYED", replay.outcome());
        assertTrue(replay.replayed());

        UUID secondToken = UUID.randomUUID();
        UUID secondActivation = UUID.randomUUID();
        String secondCapacity = "separate-capacity-" + UUID.randomUUID();
        f.jdbc().update("INSERT INTO wf_execution_token(token_id,run_id,current_node_id,status) VALUES (?,?,'second-node','READY')",
                secondToken, p.runId());
        f.jdbc().update("INSERT INTO wf_node_activation(activation_id,run_id,token_id,node_id,status) VALUES (?,?,?,'second-node','READY')",
                secondActivation, p.runId(), secondToken);
        f.jdbc().update("INSERT INTO wf_scheduler_capacity(capacity_key,capacity_limit,reserved_count) VALUES (?,1,0)",
                secondCapacity);
        Map<String, Object> secondPayload = Map.ofEntries(
                Map.entry("tokenId", secondToken.toString()),
                Map.entry("activationId", secondActivation.toString()),
                Map.entry("attemptId", UUID.randomUUID().toString()),
                Map.entry("dispatchId", UUID.randomUUID().toString()),
                Map.entry("outboxId", UUID.randomUUID().toString()),
                Map.entry("reservationId", UUID.randomUUID().toString()),
                Map.entry("capacityKey", secondCapacity),
                Map.entry("expiresAt", Instant.now().plusSeconds(120).toString()),
                Map.entry("sideEffectClass", "PURE"),
                Map.entry("attemptNo", 1),
                Map.entry("workerPayloadJson", "{}")
        );
        var secondRequest = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "second-dispatch-" + UUID.randomUUID(), "second-dispatch-message", hash(), 2, 0, secondPayload);
        assertThrows(IllegalStateException.class, () -> f.repository().reserveAndCommitDispatch(secondRequest));
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT reserved_count FROM wf_scheduler_capacity WHERE capacity_key=?", Integer.class, secondCapacity));

        var claim = f.repository().claimNextWorkerDispatch("dispatcher-1", Duration.ofSeconds(30));
        assertNotNull(claim);
        assertEquals(dispatch, claim.dispatchId());

        Map<String, Object> workerPayload = Map.of(
                "tokenId", p.tokenId().toString(),
                "activationId", p.activationId().toString(),
                "attemptId", attempt.toString(),
                "dispatchId", dispatch.toString(),
                "dispatchEpoch", 0L,
                "dispatcherId", "dispatcher-1",
                "workerId", "worker-1"
        );
        var sendStarted = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "send-" + dispatch, "send-message", hash(), 2, 0, workerPayload);
        assertEquals("DISPATCH_STARTED", f.repository().workerDispatchStarted(sendStarted).outcome());

        var prematureStart = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "start-early-" + dispatch, "start-early", hash(), 3, 0, workerPayload);
        assertThrows(IllegalStateException.class, () -> f.repository().workerStarted(prematureStart));

        var accepted = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "accepted-" + dispatch, "accepted-message", hash(), 3, 0, workerPayload);
        assertEquals("WORKER_ACCEPTED", f.repository().workerAccepted(accepted).outcome());
        assertEquals("REPLAYED", f.repository().workerAccepted(accepted).outcome());

        var started = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "started-" + dispatch, "started-message", hash(), 4, 0, workerPayload);
        assertEquals("WORKER_STARTED", f.repository().workerStarted(started).outcome());
        assertEquals("REPLAYED", f.repository().workerStarted(started).outcome());

        assertEquals("STARTED", f.jdbc().queryForObject(
                "SELECT status FROM wf_node_attempt WHERE attempt_id=?", String.class, attempt));
        assertEquals("DELIVERED", f.jdbc().queryForObject(
                "SELECT status FROM wf_worker_dispatch_outbox WHERE dispatch_id=?", String.class, dispatch));
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT in_flight_count FROM wf_workflow_run WHERE run_id=?", Integer.class, p.runId()));
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT reserved_count FROM wf_scheduler_capacity WHERE capacity_key=?", Integer.class, p.capacityKey()));
        assertEquals(5L, f.jdbc().queryForObject(
                "SELECT event_seq FROM wf_workflow_run WHERE run_id=?", Long.class, p.runId()));

        UUID nextToken = UUID.randomUUID();
        Map<String, Object> completionPayload = Map.of(
                "tokenId", p.tokenId().toString(),
                "activationId", p.activationId().toString(),
                "attemptId", attempt.toString(),
                "dispatchId", dispatch.toString(),
                "dispatchEpoch", 0L,
                "outputPayloadJson", "{\"result\":\"ok\"}",
                "outputHash", hash(),
                "nextTokenId", nextToken.toString(),
                "nextNodeId", "next-task",
                "incomingEdgeId", "edge-next"
        );
        var completionRequest = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "complete-" + dispatch, "complete-message", hash(), 5, 0, completionPayload);

        // Fault injection: force the final Runtime Event insert to violate (run_id,event_seq).
        UUID collisionEvent = UUID.randomUUID();
        f.jdbc().update("""
                INSERT INTO wf_runtime_event(event_id,run_id,event_seq,run_commit_version,event_type,
                  aggregate_type,aggregate_id,payload)
                VALUES (?,?,6,6,'InjectedCollision','Run',?,'{}'::jsonb)
                """, collisionEvent, p.runId(), p.runId());
        assertThrows(RuntimeException.class, () -> f.completion().completeNode(completionRequest));
        assertEquals("STARTED", f.jdbc().queryForObject(
                "SELECT status FROM wf_node_attempt WHERE attempt_id=?", String.class, attempt));
        assertEquals("ACTIVE", f.jdbc().queryForObject(
                "SELECT status FROM wf_node_activation WHERE activation_id=?", String.class, p.activationId()));
        assertEquals("IN_FLIGHT", f.jdbc().queryForObject(
                "SELECT status FROM wf_execution_token WHERE token_id=?", String.class, p.tokenId()));
        assertEquals("COMMITTED", f.jdbc().queryForObject(
                "SELECT status FROM wf_reservation WHERE attempt_id=?", String.class, attempt));
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT in_flight_count FROM wf_workflow_run WHERE run_id=?", Integer.class, p.runId()));
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT reserved_count FROM wf_scheduler_capacity WHERE capacity_key=?", Integer.class, p.capacityKey()));
        assertEquals(5L, f.jdbc().queryForObject(
                "SELECT event_seq FROM wf_workflow_run WHERE run_id=?", Long.class, p.runId()));
        f.jdbc().update("DELETE FROM wf_runtime_event WHERE event_id=?", collisionEvent);

        var completed = f.completion().completeNode(completionRequest);
        assertEquals("COMPLETED", completed.outcome());
        assertEquals(6, completed.runCommitVersion());
        assertEquals("SUCCEEDED", f.jdbc().queryForObject(
                "SELECT status FROM wf_node_attempt WHERE attempt_id=?", String.class, attempt));
        assertEquals("COMPLETED", f.jdbc().queryForObject(
                "SELECT status FROM wf_node_activation WHERE activation_id=?", String.class, p.activationId()));
        assertEquals("CONSUMED", f.jdbc().queryForObject(
                "SELECT status FROM wf_execution_token WHERE token_id=?", String.class, p.tokenId()));
        assertEquals("READY", f.jdbc().queryForObject(
                "SELECT status FROM wf_execution_token WHERE token_id=?", String.class, nextToken));
        assertEquals("RELEASED", f.jdbc().queryForObject(
                "SELECT status FROM wf_reservation WHERE attempt_id=?", String.class, attempt));
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT in_flight_count FROM wf_workflow_run WHERE run_id=?", Integer.class, p.runId()));
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT reserved_count FROM wf_scheduler_capacity WHERE capacity_key=?", Integer.class, p.capacityKey()));
        assertEquals(6L, f.jdbc().queryForObject(
                "SELECT event_seq FROM wf_workflow_run WHERE run_id=?", Long.class, p.runId()));
        assertEquals("REPLAYED", f.completion().completeNode(completionRequest).outcome());
        assertEquals(6L, f.jdbc().queryForObject(
                "SELECT event_seq FROM wf_workflow_run WHERE run_id=?", Long.class, p.runId()));
    }

    @Test
    void activeRetryRegistrationBlocksDispatchUntilRetryDueCommits() {
        Fixture f = fixture();
        PreparedRun p = prepareRun(f.jdbc());
        UUID sourceAttempt = UUID.randomUUID();
        f.jdbc().update("""
                INSERT INTO wf_node_attempt(attempt_id,run_id,activation_id,attempt_no,status,
                  dispatch_id,dispatch_epoch,side_effect_class)
                VALUES (?,?,?,1,'RETRYABLE_FAILED',?,0,'PURE')
                """, sourceAttempt, p.runId(), p.activationId(), UUID.randomUUID());
        UUID retryId = UUID.randomUUID();
        f.jdbc().update("""
                INSERT INTO wf_retry_registration(retry_id,run_id,activation_id,token_id,
                  source_attempt_id,retry_count,retry_after,status)
                VALUES (?,?,?,?,?,1,clock_timestamp()+interval '1 minute','ACTIVE')
                """, retryId, p.runId(), p.activationId(), p.tokenId(), sourceAttempt);

        UUID attempt = UUID.randomUUID();
        UUID dispatch = UUID.randomUUID();
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("tokenId", p.tokenId().toString()),
                Map.entry("activationId", p.activationId().toString()),
                Map.entry("attemptId", attempt.toString()),
                Map.entry("dispatchId", dispatch.toString()),
                Map.entry("outboxId", UUID.randomUUID().toString()),
                Map.entry("reservationId", UUID.randomUUID().toString()),
                Map.entry("capacityKey", p.capacityKey()),
                Map.entry("expiresAt", Instant.now().plusSeconds(120).toString()),
                Map.entry("sideEffectClass", "PURE"),
                Map.entry("attemptNo", 2),
                Map.entry("retryOfAttemptId", sourceAttempt.toString()),
                Map.entry("workerPayloadJson", "{}")
        );
        var request = new WorkflowRuntimeTransactionPort.Request(
                p.runId(), "retry-dispatch-" + dispatch, "retry-dispatch-message", hash(), 1, 0, payload);
        assertThrows(IllegalStateException.class, () -> f.repository().reserveAndCommitDispatch(request));
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT reserved_count FROM wf_scheduler_capacity WHERE capacity_key=?", Integer.class, p.capacityKey()));
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT in_flight_count FROM wf_workflow_run WHERE run_id=?", Integer.class, p.runId()));

        // RetryDue's committed effect closes the registration before the scheduler can dispatch.
        f.jdbc().update("UPDATE wf_retry_registration SET status='SATISFIED',satisfied_at=clock_timestamp() WHERE retry_id=?", retryId);
        assertEquals("DISPATCH_COMMITTED", f.repository().reserveAndCommitDispatch(request).outcome());
        assertEquals(sourceAttempt, f.jdbc().queryForObject(
                "SELECT retry_of_attempt_id FROM wf_node_attempt WHERE attempt_id=?", UUID.class, attempt));
    }
}
