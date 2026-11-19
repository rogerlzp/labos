package com.labos.kernel.workflow.runtime;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTransactionPort.Request;
import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTransactionPort.Result;

/** PostgreSQL T05 completion for PURE/IDEMPOTENT Worker attempts. */
public final class PostgresWorkflowRuntimeCompletionRepository {
    private final JdbcTemplate jdbc;
    private final PostgresRuntimeTransactionExecutor transactions;

    public PostgresWorkflowRuntimeCompletionRepository(
            JdbcTemplate jdbc,
            PostgresRuntimeTransactionExecutor transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Result completeNode(Request request) {
        return transactions.write(() -> {
            Map<String, Object> run = jdbc.queryForMap("""
                    SELECT status,coordinator_epoch,run_commit_version,event_seq,in_flight_count
                    FROM wf_workflow_run WHERE run_id=? FOR UPDATE
                    """, request.runId());
            if (((Number) run.get("coordinator_epoch")).longValue() != request.coordinatorEpoch()) {
                throw new IllegalStateException("T05 stale Coordinator epoch");
            }
            String runStatus = String.valueOf(run.get("status"));

            UUID tokenId = uuid(request, "tokenId");
            UUID activationId = uuid(request, "activationId");
            UUID attemptId = uuid(request, "attemptId");
            UUID dispatchId = uuid(request, "dispatchId");
            Map<String, Object> attemptBeforeLock = jdbc.queryForMap("""
                    SELECT status,dispatch_id,dispatch_epoch,side_effect_class
                    FROM wf_node_attempt WHERE attempt_id=? AND run_id=?
                    """, attemptId, request.runId());
            if (!dispatchId.equals(attemptBeforeLock.get("dispatch_id"))) {
                throw new IllegalStateException("T05 dispatchId mismatch");
            }
            if ("SUCCEEDED".equals(attemptBeforeLock.get("status"))) {
                return currentResult(request.runId(), "REPLAYED", true);
            }
            if (runStatus.equals("SUCCEEDED") || runStatus.equals("FAILED")
                    || runStatus.equals("STOPPED") || runStatus.equals("QUARANTINED")) {
                throw new IllegalStateException("T05 cannot complete an Attempt after Run terminalization");
            }
            if (!"PURE".equals(attemptBeforeLock.get("side_effect_class"))
                    && !"IDEMPOTENT".equals(attemptBeforeLock.get("side_effect_class"))) {
                throw new IllegalStateException("T05 external device Attempt must be completed through T14 evidence path");
            }
            Object dispatchEpoch = request.payload().get("dispatchEpoch");
            if (!(dispatchEpoch instanceof Number n)
                    || n.longValue() != ((Number) attemptBeforeLock.get("dispatch_epoch")).longValue()) {
                throw new IllegalStateException("T05 dispatchEpoch mismatch");
            }

            Map<String, Object> reservationIdentity = jdbc.queryForMap("""
                    SELECT reservation_id,capacity_key,status
                    FROM wf_reservation WHERE attempt_id=?
                    """, attemptId);
            String capacityKey = String.valueOf(reservationIdentity.get("capacity_key"));
            jdbc.queryForMap("""
                    SELECT reserved_count FROM wf_scheduler_capacity
                    WHERE capacity_key=? FOR UPDATE
                    """, capacityKey);

            Map<String, Object> token = jdbc.queryForMap("""
                    SELECT status FROM wf_execution_token
                    WHERE token_id=? AND run_id=? FOR UPDATE
                    """, tokenId, request.runId());
            Map<String, Object> activation = jdbc.queryForMap("""
                    SELECT status,current_attempt_id FROM wf_node_activation
                    WHERE activation_id=? AND run_id=? FOR UPDATE
                    """, activationId, request.runId());
            Map<String, Object> attempt = jdbc.queryForMap("""
                    SELECT status,dispatch_id,dispatch_epoch,side_effect_class
                    FROM wf_node_attempt WHERE attempt_id=? AND run_id=? FOR UPDATE
                    """, attemptId, request.runId());
            Map<String, Object> reservation = jdbc.queryForMap("""
                    SELECT status,reservation_id,capacity_key FROM wf_reservation
                    WHERE attempt_id=? FOR UPDATE
                    """, attemptId);

            if (!"IN_FLIGHT".equals(token.get("status"))
                    || !"ACTIVE".equals(activation.get("status"))
                    || !attemptId.equals(activation.get("current_attempt_id"))
                    || !"COMMITTED".equals(reservation.get("status"))) {
                throw new IllegalStateException("T05 current Attempt/Token/Reservation invariant failed");
            }
            if (!dispatchId.equals(attempt.get("dispatch_id"))) throw new IllegalStateException("T05 dispatchId changed");
            String outputJson = optional(request, "outputPayloadJson", "{}");
            String outputHash = required(request, "outputHash");

            int attemptRows = jdbc.update("""
                    UPDATE wf_node_attempt
                    SET status='SUCCEEDED',output_payload=?::jsonb,output_hash=?,
                        ended_at=clock_timestamp(),object_version=object_version+1
                    WHERE attempt_id=? AND status IN ('STARTED','UNKNOWN')
                    """, outputJson, outputHash, attemptId);
            int activationRows = jdbc.update("""
                    UPDATE wf_node_activation
                    SET status='COMPLETED',output_payload=?::jsonb,output_hash=?,
                        completed_at=clock_timestamp(),object_version=object_version+1,
                        updated_at=clock_timestamp()
                    WHERE activation_id=? AND status IN ('ACTIVE','UNKNOWN')
                      AND current_attempt_id=?
                    """, outputJson, outputHash, activationId, attemptId);
            int tokenRows = jdbc.update("""
                    UPDATE wf_execution_token
                    SET status='CONSUMED',object_version=object_version+1,updated_at=clock_timestamp()
                    WHERE token_id=? AND status='IN_FLIGHT'
                    """, tokenId);
            if (attemptRows != 1 || activationRows != 1 || tokenRows != 1) {
                throw new IllegalStateException("T05 projection CAS failed");
            }

            int reservationRows = jdbc.update("""
                    UPDATE wf_reservation SET status='RELEASED',released_at=clock_timestamp(),
                      object_version=object_version+1
                    WHERE reservation_id=? AND status='COMMITTED'
                    """, reservation.get("reservation_id"));
            int capacityRows = jdbc.update("""
                    UPDATE wf_scheduler_capacity
                    SET reserved_count=reserved_count-1,object_version=object_version+1,
                        updated_at=clock_timestamp()
                    WHERE capacity_key=? AND reserved_count > 0
                    """, capacityKey);
            int runRows = jdbc.update("""
                    UPDATE wf_workflow_run
                    SET in_flight_count=in_flight_count-1,
                        run_commit_version=run_commit_version+1,event_seq=event_seq+1,
                        updated_at=clock_timestamp()
                    WHERE run_id=? AND in_flight_count > 0
                    """, request.runId());
            if (reservationRows != 1 || capacityRows != 1 || runRows != 1) {
                throw new IllegalStateException("T05 capacity/reservation release CAS failed");
            }

            Object nextTokenValue = request.payload().get("nextTokenId");
            Object nextNodeValue = request.payload().get("nextNodeId");
            if ((nextTokenValue == null) != (nextNodeValue == null)) {
                throw new IllegalArgumentException("T05 nextTokenId and nextNodeId must be supplied together");
            }
            if (nextTokenValue != null) {
                jdbc.update("""
                        INSERT INTO wf_execution_token(token_id,run_id,parent_token_id,current_node_id,
                          incoming_edge_id,status)
                        VALUES (?,?,?,?,?,'READY')
                        """, UUID.fromString(String.valueOf(nextTokenValue)), request.runId(), tokenId,
                        String.valueOf(nextNodeValue), request.payload().get("incomingEdgeId"));
            }

            Map<String, Object> cursor = jdbc.queryForMap("""
                    SELECT run_commit_version,event_seq FROM wf_workflow_run WHERE run_id=?
                    """, request.runId());
            long version = number(cursor, "run_commit_version");
            long eventSeq = number(cursor, "event_seq");
            jdbc.update("""
                    INSERT INTO wf_runtime_event
                      (event_id,run_id,event_seq,run_commit_version,event_type,
                       aggregate_type,aggregate_id,message_id,payload)
                    VALUES (?,?,?,?,'NodeCompleted','Attempt',?,?,?::jsonb)
                    """, UUID.randomUUID(), request.runId(), eventSeq, version,
                    attemptId, request.messageId(),
                    "{\"outputHash\":\"" + outputHash + "\"}");
            return new Result(request.runId(), "COMPLETED", version, eventSeq, eventSeq, false);
        });
    }

    private Result currentResult(UUID runId, String outcome, boolean replayed) {
        Map<String, Object> cursor = jdbc.queryForMap("""
                SELECT run_commit_version,event_seq FROM wf_workflow_run WHERE run_id=?
                """, runId);
        long version = number(cursor, "run_commit_version");
        long seq = number(cursor, "event_seq");
        return new Result(runId, outcome, version, seq, seq, replayed);
    }

    private static String required(Request request, String key) {
        Object value = request.payload().get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("Missing " + key);
        return String.valueOf(value);
    }

    private static String optional(Request request, String key, String fallback) {
        Object value = request.payload().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static UUID uuid(Request request, String key) {
        return UUID.fromString(required(request, key));
    }

    private static long number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }
}
