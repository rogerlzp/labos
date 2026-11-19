package com.labos.kernel.workflow.runtime;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTransactionPort.Request;
import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTransactionPort.Result;

/**
 * PostgreSQL implementation of the T03/T04 Worker dispatch boundary.
 * Adapter/device dispatch (T13) is intentionally separate.
 */
public final class PostgresWorkflowRuntimeDispatchRepository {
    private final JdbcTemplate jdbc;
    private final PostgresRuntimeTransactionExecutor transactions;

    public PostgresWorkflowRuntimeDispatchRepository(
            JdbcTemplate jdbc,
            PostgresRuntimeTransactionExecutor transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** T03: reserve software capacity and atomically commit a Worker dispatch. */
    public Result reserveAndCommitDispatch(Request request) {
        return transactions.write(() -> {
            Map<String, Object> run = lockRun(request.runId());
            long epoch = longValue(run, "coordinator_epoch");
            long version = longValue(run, "run_commit_version");
            long eventSeq = longValue(run, "event_seq");
            if (!"RUNNING".equals(run.get("status")) || !Boolean.TRUE.equals(run.get("dispatch_gate_open"))) {
                throw new IllegalStateException("T03 dispatch gate is closed");
            }
            if (epoch != request.coordinatorEpoch()) throw new IllegalStateException("T03 coordinator epoch mismatch");
            if (version != request.expectedRunCommitVersion()) throw new IllegalStateException("T03 stale Run version");
            if (longValue(run, "in_flight_count") >= longValue(run, "max_in_flight")) {
                throw new IllegalStateException("T03 per-Run in-flight capacity exhausted");
            }

            UUID tokenId = uuid(request, "tokenId");
            UUID activationId = uuid(request, "activationId");
            UUID attemptId = uuid(request, "attemptId");
            UUID dispatchId = uuid(request, "dispatchId");
            UUID outboxId = uuid(request, "outboxId");
            UUID reservationId = uuid(request, "reservationId");
            String capacityKey = required(request, "capacityKey");
            String expiresAt = required(request, "expiresAt");
            String sideEffectClass = required(request, "sideEffectClass");
            String workerPayload = optional(request, "workerPayloadJson", "{}");
            UUID leaseId = optionalUuid(request, "leaseId");

            List<Map<String, Object>> duplicate = jdbc.queryForList("""
                    SELECT attempt_id,payload_hash,run_id FROM wf_worker_dispatch_outbox
                    WHERE dispatch_id=?
                    """, dispatchId);
            if (!duplicate.isEmpty()) {
                Map<String, Object> old = duplicate.get(0);
                UUID oldRunId = (UUID) old.get("run_id");
                if (oldRunId.equals(request.runId())
                        && request.payloadHash().equals(String.valueOf(old.get("payload_hash")).trim())) {
                    return currentResult(oldRunId, "REPLAYED", true);
                }
                jdbc.update("""
                        INSERT INTO wf_protocol_violation
                          (violation_id,run_id,source,message_id,original_payload_hash,
                           conflicting_payload_hash,raw_payload)
                        VALUES (?,?, 'T03', ?, ?, ?, jsonb_build_object('dispatchId',?::text))
                        ON CONFLICT (source,message_id,conflicting_payload_hash) DO NOTHING
                        """, UUID.randomUUID(), request.runId(), dispatchId.toString(),
                        String.valueOf(old.get("payload_hash")).trim(), request.payloadHash(), dispatchId);
                return currentResult(oldRunId, "PROTOCOL_VIOLATION", true);
            }

            Map<String, Object> capacity = jdbc.queryForMap("""
                    SELECT capacity_limit,reserved_count FROM wf_scheduler_capacity
                    WHERE capacity_key=? FOR UPDATE
                    """, capacityKey);
            int limit = ((Number) capacity.get("capacity_limit")).intValue();
            int reserved = ((Number) capacity.get("reserved_count")).intValue();
            if (reserved >= limit) throw new IllegalStateException("T03 scheduler capacity exhausted");
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT (?::timestamptz > clock_timestamp())", Boolean.class, expiresAt))) {
                throw new IllegalArgumentException("T03 Reservation expiry must be in the future");
            }

            if (leaseId != null) {
                Map<String, Object> lease = jdbc.queryForMap("""
                        SELECT status,owner_type,owner_id,fencing_token FROM wf_resource_lease
                        WHERE lease_id=? AND run_id=? FOR UPDATE
                        """, leaseId, request.runId());
                if (!"ACQUIRED".equals(lease.get("status"))
                        || !"ACTIVATION".equals(lease.get("owner_type"))
                        || !activationId.equals(lease.get("owner_id"))
                        || longValue(lease, "fencing_token") != number(request, "fencingToken")) {
                    throw new IllegalStateException("T03 ResourceLease owner/fencing mismatch");
                }
            }

            Map<String, Object> token = jdbc.queryForMap("""
                    SELECT status,object_version FROM wf_execution_token
                    WHERE token_id=? AND run_id=? FOR UPDATE
                    """, tokenId, request.runId());
            Map<String, Object> activation = jdbc.queryForMap("""
                    SELECT status,current_attempt_id,object_version FROM wf_node_activation
                    WHERE activation_id=? AND run_id=? FOR UPDATE
                    """, activationId, request.runId());
            if (!"READY".equals(token.get("status")) || !"READY".equals(activation.get("status"))
                    || activation.get("current_attempt_id") != null) {
                throw new IllegalStateException("T03 Token/Activation is not dispatchable");
            }
            if (sideEffectClass.equals("PURE") == false
                    && sideEffectClass.equals("IDEMPOTENT") == false
                    && sideEffectClass.equals("EXTERNAL") == false) {
                throw new IllegalArgumentException("T03 invalid sideEffectClass");
            }

            int capacityRows = jdbc.update("""
                    UPDATE wf_scheduler_capacity
                    SET reserved_count=reserved_count+1,object_version=object_version+1,
                        updated_at=clock_timestamp()
                    WHERE capacity_key=? AND reserved_count < capacity_limit
                    """, capacityKey);
            if (capacityRows != 1) throw new IllegalStateException("T03 capacity CAS failed");

            jdbc.update("""
                    INSERT INTO wf_node_attempt
                      (attempt_id,run_id,activation_id,attempt_no,status,dispatch_id,
                       dispatch_epoch,side_effect_class)
                    VALUES (?,?,?,?, 'DISPATCH_PENDING', ?, ?, ?)
                    """, attemptId, request.runId(), activationId,
                    integer(request, "attemptNo"), dispatchId, epoch, sideEffectClass);

            jdbc.update("""
                    UPDATE wf_node_activation
                    SET status='ACTIVE',current_attempt_id=?,object_version=object_version+1,
                        activated_at=clock_timestamp(),updated_at=clock_timestamp()
                    WHERE activation_id=? AND status='READY' AND current_attempt_id IS NULL
                    """, attemptId, activationId);

            jdbc.update("""
                    UPDATE wf_execution_token
                    SET status='IN_FLIGHT',object_version=object_version+1,updated_at=clock_timestamp()
                    WHERE token_id=? AND status='READY'
                    """, tokenId);

            jdbc.update("""
                    INSERT INTO wf_reservation
                      (reservation_id,run_id,token_id,activation_id,attempt_id,capacity_key,status,expires_at)
                    VALUES (?,?,?,?,?,?,'COMMITTED',?::timestamptz)
                    """, reservationId, request.runId(), tokenId, activationId, attemptId, capacityKey, expiresAt);

            jdbc.update("""
                    INSERT INTO wf_worker_dispatch_outbox
                      (outbox_id,run_id,activation_id,attempt_id,dispatch_id,dispatch_epoch,
                       payload_hash,status,payload)
                    VALUES (?,?,?,?,?,?,?,'PENDING',?::jsonb)
                    """, outboxId, request.runId(), activationId, attemptId, dispatchId,
                    epoch, request.payloadHash(), workerPayload);

            long[] cursor = bumpRunAndReserveInFlight(request.runId());
            insertEvent(request.runId(), cursor[0], cursor[1], "WorkerDispatchCommitted",
                    "Attempt", attemptId, request.messageId(),
                    "{\"dispatchId\":\"" + dispatchId + "\"}");

            return new Result(request.runId(), "DISPATCH_COMMITTED", cursor[0],
                    cursor[1], cursor[1], false);
        });
    }

    /** Dispatcher has crossed the external send boundary; this is not Worker acceptance. */
    public Result workerDispatchStarted(Request request) {
        return transactions.write(() -> {
            Map<String, Object> run = lockRun(request.runId());
            requireCurrentEpoch(run, request);
            UUID tokenId = uuid(request, "tokenId");
            UUID activationId = uuid(request, "activationId");
            UUID attemptId = uuid(request, "attemptId");
            UUID dispatchId = uuid(request, "dispatchId");
            lockTokenActivationAttempt(request.runId(), tokenId, activationId, attemptId);

            Map<String, Object> attempt = jdbc.queryForMap("""
                    SELECT status,dispatch_id,dispatch_epoch,worker_id FROM wf_node_attempt
                    WHERE attempt_id=? FOR UPDATE
                    """, attemptId);
            requireDispatchIdentity(attempt, dispatchId, request);
            if ("DISPATCHED".equals(attempt.get("status"))
                    || "ACCEPTED".equals(attempt.get("status"))
                    || "STARTED".equals(attempt.get("status"))) {
                return currentResult(request.runId(), "REPLAYED", true);
            }
            if (!"DISPATCH_PENDING".equals(attempt.get("status"))) {
                throw new IllegalStateException("T03 send boundary requires DISPATCH_PENDING");
            }

            int attemptRows = jdbc.update("""
                    UPDATE wf_node_attempt SET status='DISPATCHED',object_version=object_version+1
                    WHERE attempt_id=? AND dispatch_id=? AND status='DISPATCH_PENDING'
                    """, attemptId, dispatchId);
            int outboxRows = jdbc.update("""
                    UPDATE wf_worker_dispatch_outbox
                    SET status='SEND_STARTED',sent_at=clock_timestamp(),attempts=attempts+1
                    WHERE dispatch_id=? AND status IN ('PENDING','CLAIMED')
                    """, dispatchId);
            if (attemptRows != 1 || outboxRows != 1) throw new IllegalStateException("T03 send CAS failed");

            long[] cursor = bumpRun(request.runId());
            insertEvent(request.runId(), cursor[0], cursor[1], "WorkerDispatchStarted",
                    "Attempt", attemptId, request.messageId(),
                    "{\"dispatchId\":\"" + dispatchId + "\"}");
            return new Result(request.runId(), "DISPATCH_STARTED", cursor[0], cursor[1], cursor[1], false);
        });
    }

    /** T04a: Worker acceptance is distinct from Handler start. */
    public Result workerAccepted(Request request) {
        return transactions.write(() -> {
            Map<String, Object> run = lockRun(request.runId());
            requireCurrentEpoch(run, request);
            UUID tokenId = uuid(request, "tokenId");
            UUID activationId = uuid(request, "activationId");
            UUID attemptId = uuid(request, "attemptId");
            UUID dispatchId = uuid(request, "dispatchId");
            lockTokenActivationAttempt(request.runId(), tokenId, activationId, attemptId);

            Map<String, Object> attempt = jdbc.queryForMap("""
                    SELECT status,dispatch_id,dispatch_epoch,worker_id FROM wf_node_attempt
                    WHERE attempt_id=? FOR UPDATE
                    """, attemptId);
            requireDispatchIdentity(attempt, dispatchId, request);
            String status = String.valueOf(attempt.get("status"));
            String workerId = required(request, "workerId");
            if ("ACCEPTED".equals(status) || "STARTED".equals(status)) {
                if (!workerId.equals(attempt.get("worker_id"))) {
                    throw new IllegalStateException("WorkerAccepted identity conflict");
                }
                return currentResult(request.runId(), "REPLAYED", true);
            }
            if (!"DISPATCHED".equals(status)) {
                throw new IllegalStateException("WorkerAccepted requires DISPATCHED Attempt");
            }

            int changed = jdbc.update("""
                    UPDATE wf_node_attempt
                    SET status='ACCEPTED',worker_id=?,accepted_at=clock_timestamp(),
                        object_version=object_version+1
                    WHERE attempt_id=? AND status='DISPATCHED' AND dispatch_id=? AND dispatch_epoch=?
                    """, workerId, attemptId, dispatchId, longValue(attempt, "dispatch_epoch"));
            if (changed != 1) throw new IllegalStateException("WorkerAccepted CAS failed");
            jdbc.update("""
                    UPDATE wf_worker_dispatch_outbox SET status='DELIVERED'
                    WHERE dispatch_id=? AND status='SEND_STARTED'
                    """, dispatchId);

            long[] cursor = bumpRun(request.runId());
            insertEvent(request.runId(), cursor[0], cursor[1], "WorkerAccepted",
                    "Attempt", attemptId, request.messageId(),
                    "{\"dispatchId\":\"" + dispatchId + "\"}");
            return new Result(request.runId(), "WORKER_ACCEPTED", cursor[0], cursor[1], cursor[1], false);
        });
    }

    /** T04b: Handler start is only accepted after WorkerAccepted. */
    public Result workerStarted(Request request) {
        return transactions.write(() -> {
            Map<String, Object> run = lockRun(request.runId());
            requireCurrentEpoch(run, request);
            UUID tokenId = uuid(request, "tokenId");
            UUID activationId = uuid(request, "activationId");
            UUID attemptId = uuid(request, "attemptId");
            UUID dispatchId = uuid(request, "dispatchId");
            lockTokenActivationAttempt(request.runId(), tokenId, activationId, attemptId);

            Map<String, Object> attempt = jdbc.queryForMap("""
                    SELECT status,dispatch_id,dispatch_epoch,worker_id FROM wf_node_attempt
                    WHERE attempt_id=? FOR UPDATE
                    """, attemptId);
            requireDispatchIdentity(attempt, dispatchId, request);
            String status = String.valueOf(attempt.get("status"));
            String workerId = required(request, "workerId");
            if ("STARTED".equals(status)) {
                if (!workerId.equals(attempt.get("worker_id"))) {
                    throw new IllegalStateException("WorkerStarted identity conflict");
                }
                return currentResult(request.runId(), "REPLAYED", true);
            }
            if (!"ACCEPTED".equals(status)) {
                throw new IllegalStateException("WorkerStarted requires ACCEPTED Attempt");
            }
            if (!workerId.equals(attempt.get("worker_id"))) {
                throw new IllegalStateException("WorkerStarted workerId mismatch");
            }

            int changed = jdbc.update("""
                    UPDATE wf_node_attempt
                    SET status='STARTED',started_at=clock_timestamp(),object_version=object_version+1
                    WHERE attempt_id=? AND status='ACCEPTED' AND dispatch_id=? AND dispatch_epoch=?
                    """, attemptId, dispatchId, longValue(attempt, "dispatch_epoch"));
            if (changed != 1) throw new IllegalStateException("WorkerStarted CAS failed");

            long[] cursor = bumpRun(request.runId());
            insertEvent(request.runId(), cursor[0], cursor[1], "NodeStarted",
                    "Attempt", attemptId, request.messageId(),
                    "{\"dispatchId\":\"" + dispatchId + "\"}");
            return new Result(request.runId(), "WORKER_STARTED", cursor[0], cursor[1], cursor[1], false);
        });
    }

    private Map<String, Object> lockRun(UUID runId) {
        return jdbc.queryForMap("""
                SELECT status,dispatch_gate_open,coordinator_epoch,run_commit_version,event_seq,
                       max_in_flight,in_flight_count
                FROM wf_workflow_run WHERE run_id=? FOR UPDATE
                """, runId);
    }

    private void requireCurrentEpoch(Map<String, Object> run, Request request) {
        if (longValue(run, "coordinator_epoch") != request.coordinatorEpoch()) {
            throw new IllegalStateException("Runtime message consumed by stale Coordinator epoch");
        }
    }

    private void lockTokenActivationAttempt(UUID runId, UUID tokenId, UUID activationId, UUID attemptId) {
        jdbc.queryForMap("""
                SELECT status FROM wf_execution_token WHERE token_id=? AND run_id=? FOR UPDATE
                """, tokenId, runId);
        jdbc.queryForMap("""
                SELECT status FROM wf_node_activation WHERE activation_id=? AND run_id=? FOR UPDATE
                """, activationId, runId);
        jdbc.queryForMap("""
                SELECT status FROM wf_node_attempt WHERE attempt_id=? AND run_id=? FOR UPDATE
                """, attemptId, runId);
    }

    private void requireDispatchIdentity(Map<String, Object> attempt, UUID dispatchId, Request request) {
        if (!dispatchId.equals(attempt.get("dispatch_id"))) {
            throw new IllegalStateException("dispatchId does not match current Attempt");
        }
        Object suppliedEpoch = request.payload().get("dispatchEpoch");
        if (suppliedEpoch != null && ((Number) suppliedEpoch).longValue()
                != longValue(attempt, "dispatch_epoch")) {
            throw new IllegalStateException("dispatchEpoch does not match persisted Attempt");
        }
    }

    private long[] bumpRunAndReserveInFlight(UUID runId) {
        int changed = jdbc.update("""
                UPDATE wf_workflow_run
                SET in_flight_count=in_flight_count+1,
                    run_commit_version=run_commit_version+1,event_seq=event_seq+1,
                    updated_at=clock_timestamp()
                WHERE run_id=? AND in_flight_count < max_in_flight
                """, runId);
        if (changed != 1) throw new IllegalStateException("T03 per-Run capacity CAS failed");
        Map<String, Object> cursor = jdbc.queryForMap("""
                SELECT run_commit_version,event_seq FROM wf_workflow_run WHERE run_id=?
                """, runId);
        return new long[]{longValue(cursor, "run_commit_version"), longValue(cursor, "event_seq")};
    }

    private long[] bumpRun(UUID runId) {
        jdbc.update("""
                UPDATE wf_workflow_run
                SET run_commit_version=run_commit_version+1,event_seq=event_seq+1,
                    updated_at=clock_timestamp()
                WHERE run_id=?
                """, runId);
        Map<String, Object> cursor = jdbc.queryForMap("""
                SELECT run_commit_version,event_seq FROM wf_workflow_run WHERE run_id=?
                """, runId);
        return new long[]{longValue(cursor, "run_commit_version"), longValue(cursor, "event_seq")};
    }

    private void insertEvent(UUID runId, long version, long eventSeq, String eventType,
                             String aggregateType, UUID aggregateId, String messageId, String payload) {
        jdbc.update("""
                INSERT INTO wf_runtime_event
                  (event_id,run_id,event_seq,run_commit_version,event_type,
                   aggregate_type,aggregate_id,message_id,payload)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), runId, eventSeq, version, eventType,
                aggregateType, aggregateId, messageId, payload);
    }

    private Result currentResult(UUID runId, String outcome, boolean replayed) {
        Map<String, Object> cursor = jdbc.queryForMap("""
                SELECT run_commit_version,event_seq FROM wf_workflow_run WHERE run_id=?
                """, runId);
        long version = longValue(cursor, "run_commit_version");
        long eventSeq = longValue(cursor, "event_seq");
        return new Result(runId, outcome, version, eventSeq, eventSeq, replayed);
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

    private static UUID optionalUuid(Request request, String key) {
        Object value = request.payload().get(key);
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    private static long number(Request request, String key) {
        Object value = request.payload().get(key);
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Numeric field required: " + key);
        return n.longValue();
    }

    private static int integer(Request request, String key) {
        Object value = request.payload().get(key);
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Numeric field required: " + key);
        return n.intValue();
    }

    private static long longValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
