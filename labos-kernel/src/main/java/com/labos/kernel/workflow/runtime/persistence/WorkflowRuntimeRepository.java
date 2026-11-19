package com.labos.kernel.workflow.runtime.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-backed transaction boundary for the new Workflow Runtime.
 *
 * <p>This repository deliberately uses its own schema and tables. It does not
 * read or write the legacy GraphRunner tables. Every public mutation executes
 * in one Spring-managed transaction; no device/network call is made here.
 *
 * <p>Methods are transaction-sized primitives, not an orchestration engine.
 * The Coordinator is responsible for choosing the transition; this class
 * enforces tenant/run scoping, lock order, CAS and atomic Event/Outbox writes.
 */
public final class WorkflowRuntimeRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshotTx;

    public WorkflowRuntimeRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.snapshotTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
    }

    public record RunHead(String status, String controlGate, long commitVersion,
                          long eventSeq, long coordinatorEpoch, String recoveryState) {}
    public record CreatedRun(UUID runId, boolean replayed) {}
    public record InboxReceipt(UUID inboxId, boolean inserted, boolean payloadConflict) {}
    public record EventDraft(String type, String aggregateType, UUID aggregateId,
                             Long aggregateVersion, UUID causationId, UUID correlationId,
                             String payloadJson) {}
    public record Checkpoint(UUID checkpointId, long commitVersion, long eventSeq,
                             long coordinatorEpoch, String snapshotJson, byte[] sha256) {}
    public record CapacityReservation(UUID attemptId, UUID dispatchId, UUID reservationId) {}

    private static final RowMapper<RunHead> RUN_HEAD = (rs, n) -> new RunHead(
            rs.getString("status"), rs.getString("control_gate"),
            rs.getLong("commit_version"), rs.getLong("event_seq"),
            rs.getLong("coordinator_epoch"), rs.getString("recovery_state"));

    private <T> T required(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private RunHead lockRun(UUID tenantId, UUID runId) {
        List<RunHead> rows = jdbc.query("""
            SELECT status, control_gate, commit_version, event_seq,
                   coordinator_epoch, recovery_state
              FROM workflow_runtime.workflow_run
             WHERE tenant_id=? AND run_id=?
             FOR UPDATE
            """, RUN_HEAD, tenantId, runId);
        if (rows.size() != 1) throw new IllegalStateException("Run not found or tenant mismatch");
        return rows.get(0);
    }

    private long appendEvents(UUID tenantId, UUID runId, long coordinatorEpoch,
                              List<EventDraft> events) {
        if (events == null || events.isEmpty()) return currentEventSeq(tenantId, runId);
        Long last = jdbc.queryForObject("""
            UPDATE workflow_runtime.workflow_run
               SET commit_version=commit_version+1,
                   event_seq=event_seq+?,
                   updated_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=?
         RETURNING event_seq
            """, Long.class, events.size(), tenantId, runId);
        if (last == null) throw new IllegalStateException("Run disappeared while appending events");
        long first = last - events.size() + 1;
        for (int i = 0; i < events.size(); i++) {
            EventDraft e = events.get(i);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_event
                  (tenant_id,run_id,event_seq,event_type,aggregate_type,aggregate_id,
                   aggregate_version,coordinator_epoch,causation_id,correlation_id,payload)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """, tenantId, runId, first + i, e.type(), e.aggregateType(), e.aggregateId(),
                    e.aggregateVersion(), coordinatorEpoch, e.causationId(), e.correlationId(),
                    e.payloadJson() == null ? "{}" : e.payloadJson());
        }
        return last;
    }

    private long currentEventSeq(UUID tenantId, UUID runId) {
        Long seq = jdbc.queryForObject("""
            SELECT event_seq FROM workflow_runtime.workflow_run
             WHERE tenant_id=? AND run_id=?
            """, Long.class, tenantId, runId);
        return seq == null ? 0 : seq;
    }

    private void requireCoordinator(RunHead head, long expectedEpoch) {
        if (head.coordinatorEpoch() != expectedEpoch)
            throw new StaleRuntimeWriteException("Coordinator epoch is stale");
        if ("RECOVERING".equals(head.recoveryState()) || "BLOCKED".equals(head.recoveryState()))
            throw new IllegalStateException("Run recovery gate blocks mutation");
    }

    /** T01: idempotently create a Run and its initial READY token. */
    public CreatedRun createRun(UUID tenantId, String requestId, String requestKind,
                                String canonicalPayloadJson, String definitionKey,
                                String definitionVersion, byte[] definitionHash,
                                String compilerVersion, String graphPlanJson,
                                String inputJson, String firstNodeKey) {
        return required(() -> {
            byte[] payloadHash = sha256(canonicalPayloadJson);
            int inserted = jdbc.update("""
                INSERT INTO workflow_runtime.run_request_dedup
                  (tenant_id,request_id,request_kind,payload_hash)
                VALUES (?,?,?,?)
                ON CONFLICT (tenant_id,request_id) DO NOTHING
                """, tenantId, requestId, requestKind, payloadHash);
            if (inserted == 0) {
                byte[] existing = jdbc.queryForObject("""
                    SELECT payload_hash FROM workflow_runtime.run_request_dedup
                     WHERE tenant_id=? AND request_id=?
                    """, byte[].class, tenantId, requestId);
                if (!MessageDigest.isEqual(existing, payloadHash))
                    throw new IdempotencyConflictException("request_id reused with different payload");
                UUID existingRun = jdbc.queryForObject("""
                    SELECT run_id FROM workflow_runtime.run_request_dedup
                     WHERE tenant_id=? AND request_id=?
                    """, UUID.class, tenantId, requestId);
                if (existingRun == null) throw new IllegalStateException("Dedup receipt has no Run result");
                return new CreatedRun(existingRun, true);
            }

            UUID definitionId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.definition_snapshot
                  (tenant_id,definition_id,definition_key,definition_version,definition_hash,
                   compiler_version,graph_plan)
                VALUES (?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (tenant_id,definition_key,definition_version) DO NOTHING
                """, tenantId, definitionId, definitionKey, definitionVersion, definitionHash,
                    compilerVersion, graphPlanJson);
            UUID actualDefinitionId = jdbc.queryForObject("""
                SELECT definition_id FROM workflow_runtime.definition_snapshot
                 WHERE tenant_id=? AND definition_key=? AND definition_version=?
                """, UUID.class, tenantId, definitionKey, definitionVersion);
            UUID runId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.workflow_run
                  (tenant_id,run_id,definition_id,status,input)
                VALUES (?,?,?,'CREATED',?::jsonb)
                """, tenantId, runId, actualDefinitionId, inputJson == null ? "{}" : inputJson);
            UUID tokenId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,token_id,run_id,token_state,node_key)
                VALUES (?,?,?,'READY',?)
                """, tenantId, tokenId, runId, firstNodeKey);
            jdbc.update("""
                UPDATE workflow_runtime.run_request_dedup SET run_id=?
                 WHERE tenant_id=? AND request_id=?
                """, runId, tenantId, requestId);
            appendEvents(tenantId, runId, 0, List.of(new EventDraft(
                    "RUN_CREATED","RUN",runId,0L,null,null,
                    "{\"tokenId\":\""+tokenId+"\",\"nodeKey\":\""+jsonEscape(firstNodeKey)+"\"}")));
            return new CreatedRun(runId, false);
        });
    }

    /** T02: CREATED -> RUNNING; a stale coordinator cannot start the Run. */
    public void startRun(UUID tenantId, UUID runId, UUID coordinatorId, long expectedEpoch) {
        required(() -> {
            RunHead head = lockRun(tenantId, runId);
            if (!"CREATED".equals(head.status())) throw new InvalidTransitionException("T02 requires CREATED");
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run
                   SET status='RUNNING', coordinator_id=?, coordinator_epoch=?,
                       started_at=clock_timestamp(), updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND status='CREATED'
                """, coordinatorId, expectedEpoch, tenantId, runId);
            appendEvents(tenantId, runId, expectedEpoch, List.of(new EventDraft(
                    "RUN_STARTED","RUN",runId,head.commitVersion()+1,null,null,"{}")));
            return null;
        });
    }

    /** T03-R: reserve global capacity with a single conditional UPDATE (no oversell). */
    public CapacityReservation reserveCapacity(UUID tenantId, UUID runId, long epoch,
                                                String capacityKey, int capacityLimit,
                                                String nodeKey, String sideEffectClass,
                                                Instant expiresAt) {
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            requireCoordinator(head, epoch);
            if (!"RUNNING".equals(head.status()) || !"OPEN".equals(head.controlGate()))
                throw new InvalidTransitionException("Run gate does not admit dispatch");
            jdbc.update("""
                INSERT INTO workflow_runtime.scheduler_capacity
                  (tenant_id,capacity_key,capacity_limit,reserved_count)
                VALUES (?,?,?,0)
                ON CONFLICT (tenant_id,capacity_key) DO NOTHING
                """, tenantId, capacityKey, capacityLimit);
            Integer updatedLimit = jdbc.queryForObject("""
                SELECT capacity_limit FROM workflow_runtime.scheduler_capacity
                 WHERE tenant_id=? AND capacity_key=? FOR UPDATE
                """, Integer.class, tenantId, capacityKey);
            if (updatedLimit == null || updatedLimit != capacityLimit)
                throw new IllegalStateException("Capacity limit differs from configured value");
            UUID tokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key=? AND token_state='READY'
                 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, UUID.class, tenantId, runId, nodeKey);
            if (tokenId == null) throw new IllegalStateException("No READY token");
            int reserved = jdbc.update("""
                UPDATE workflow_runtime.scheduler_capacity
                   SET reserved_count=reserved_count+1,capacity_version=capacity_version+1,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=? AND capacity_key=? AND reserved_count<capacity_limit
                """, tenantId, capacityKey);
            if (reserved != 1) throw new CapacityExhaustedException(capacityKey);
            int tokenUpdated = jdbc.update("""
                UPDATE workflow_runtime.execution_token
                   SET token_state='RESERVED',token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state='READY'
                """, tenantId, tokenId);
            if (tokenUpdated != 1) throw new StaleRuntimeWriteException("Token was concurrently reserved");
            UUID activationId = UUID.randomUUID(), attemptId = UUID.randomUUID(), dispatchId = UUID.randomUUID();
            Integer activationNo = jdbc.queryForObject("""
                SELECT COALESCE(MAX(activation_no),0)+1 FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND token_id=?
                """, Integer.class, tenantId, tokenId);
            jdbc.update("""
                INSERT INTO workflow_runtime.node_activation
                  (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
                VALUES (?,?,?,?,?,?,'RESERVED')
                """, tenantId, activationId, runId, tokenId, activationNo, nodeKey);
            jdbc.update("""
                INSERT INTO workflow_runtime.node_attempt
                  (tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,dispatch_epoch,
                   attempt_state,side_effect_class)
                VALUES (?,?,?,?,1,?,?,'CREATED',?)
                """, tenantId, attemptId, runId, activationId, dispatchId, epoch, sideEffectClass);
            UUID reservationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.dispatch_reservation
                  (tenant_id,reservation_id,run_id,attempt_id,capacity_key,reservation_state,expires_at)
                VALUES (?,?,?, ?,?,'HELD',?)
                """, tenantId, reservationId, runId, attemptId, capacityKey, expiresAt);
            appendEvents(tenantId, runId, epoch, List.of(new EventDraft(
                    "DISPATCH_RESERVED","ATTEMPT",attemptId,0L,null,null,
                    "{\"activationId\":\""+activationId+"\",\"dispatchId\":\""+dispatchId+"\"}")));
            return new CapacityReservation(attemptId, dispatchId, reservationId);
        });
    }

    /** T03-D: make a reserved Attempt dispatchable and enqueue a durable Worker message. */
    public void dispatchAttempt(UUID tenantId, UUID runId, long epoch, UUID attemptId,
                                String workerQueue, String payloadJson) {
        required(() -> {
            RunHead head = lockRun(tenantId, runId); requireCoordinator(head, epoch);
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='DISPATCHED',
                       attempt_version=attempt_version+1
                 WHERE tenant_id=? AND attempt_id=? AND run_id=? AND attempt_state='CREATED'
                """, tenantId, attemptId, runId);
            if (changed != 1) throw new StaleRuntimeWriteException("Attempt is not CREATED");
            jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='RESERVED',
                       activation_version=activation_version+1
                 WHERE tenant_id=? AND run_id=? AND current_attempt_id IS NULL
                   AND activation_id=(SELECT activation_id FROM workflow_runtime.node_attempt
                     WHERE tenant_id=? AND attempt_id=?)
                """, tenantId, runId, tenantId, attemptId);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'WORKER_DISPATCH',?,?::jsonb)
                """, tenantId, runId, "dispatch:"+attemptId, payloadJson);
            appendEvents(tenantId, runId, epoch, List.of(new EventDraft(
                    "ATTEMPT_DISPATCHED","ATTEMPT",attemptId,1L,null,null,"{}")));
            return null;
        });
    }

    /** T04-A: accept only the exact dispatch identity. */
    public void acceptAttempt(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                              long dispatchEpoch, String workerId) {
        required(() -> {
            lockRun(tenantId, runId);
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='ACCEPTED',
                       worker_id=?,attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND dispatch_id=?
                   AND dispatch_epoch=? AND attempt_state='DISPATCHED'
                """, workerId, tenantId, runId, attemptId, dispatchId, dispatchEpoch);
            if (changed != 1) throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            appendEvents(tenantId, runId, dispatchEpoch, List.of(new EventDraft(
                    "ATTEMPT_ACCEPTED","ATTEMPT",attemptId,null,null,null,"{}")));
            return null;
        });
    }

    /** T04-S: Started is strictly ACCEPTED -> STARTED, never DISPATCHED -> STARTED. */
    public void startAttempt(UUID tenantId, UUID runId, UUID attemptId, String workerId) {
        required(() -> {
            RunHead head = lockRun(tenantId, runId);
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='STARTED',
                       started_at=clock_timestamp(),attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND worker_id=?
                   AND attempt_state='ACCEPTED'
                """, tenantId, runId, attemptId, workerId);
            if (changed != 1) throw new StaleRuntimeWriteException("Only accepted owning Worker may start");
            appendEvents(tenantId, runId, head.coordinatorEpoch(), List.of(new EventDraft(
                    "ATTEMPT_STARTED","ATTEMPT",attemptId,null,null,null,"{}")));
            return null;
        });
    }

    /** T05: persist immutable BranchOutput before any later shared-variable aggregation. */
    public void completeAttempt(UUID tenantId, UUID runId, UUID attemptId,
                                String outputKey, String outputJson) {
        required(() -> {
            RunHead head = lockRun(tenantId, runId);
            UUID activationId = jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, UUID.class, tenantId, runId, attemptId);
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='SUCCEEDED',
                       finished_at=clock_timestamp(),attempt_version=attempt_version+1,
                       result=?::jsonb
                 WHERE tenant_id=? AND attempt_id=? AND run_id=? AND attempt_state='STARTED'
                """, outputJson, tenantId, attemptId, runId);
            if (changed != 1) throw new StaleRuntimeWriteException("Attempt is not STARTED");
            jdbc.update("""
                INSERT INTO workflow_runtime.branch_output
                  (tenant_id,run_id,activation_id,output_key,value)
                VALUES (?,?,?,?,?::jsonb)
                ON CONFLICT (tenant_id,activation_id,output_key) DO NOTHING
                """, tenantId, runId, activationId, outputKey, outputJson);
            jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='SUCCEEDED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('RUNNING','RESERVED')
                """, tenantId, activationId);
            jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=(SELECT token_id FROM workflow_runtime.node_activation
                   WHERE tenant_id=? AND activation_id=?)
                """, tenantId, tenantId, activationId);
            appendEvents(tenantId, runId, head.coordinatorEpoch(), List.of(new EventDraft(
                    "ATTEMPT_SUCCEEDED","ATTEMPT",attemptId,null,null,null,
                    "{\"activationId\":\""+activationId+"\",\"outputKey\":\""+jsonEscape(outputKey)+"\"}")));
            return null;
        });
    }

    /** T06: register retry intent; timer scheduling is an Outbox effect after commit. */
    public UUID scheduleRetry(UUID tenantId, UUID runId, long epoch, UUID activationId,
                              Instant dueAt, int nextAttemptNo, String policyJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            UUID retryId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.retry_registration
                  (tenant_id,retry_id,run_id,activation_id,retry_state,due_at,attempt_no,retry_policy)
                VALUES (?,?,?,?,'SCHEDULED',?,?,?::jsonb)
                """,tenantId,retryId,runId,activationId,dueAt,nextAttemptNo,policyJson);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'RETRY_TIMER',?,jsonb_build_object('retryId',?::text))
                """,tenantId,runId,"retry:"+retryId,retryId);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("RETRY_SCHEDULED","RETRY",
                    retryId,0L,null,null,"{}")));
            return retryId;
        });
    }

    /** T07: suspend activation and persist continuation without occupying a Worker. */
    public UUID registerWait(UUID tenantId, UUID runId, long epoch, UUID activationId,
                             String waitKind, String correlationKey, Instant deadline,
                             String continuationJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            UUID waitId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.wait_registration
                  (tenant_id,wait_id,run_id,activation_id,wait_state,wait_kind,
                   correlation_key,deadline_at,continuation)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,?::jsonb)
                """,tenantId,waitId,runId,activationId,waitKind,correlationKey,deadline,continuationJson);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='WAITING',
                       activation_version=activation_version+1
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('RUNNING','RESERVED')
                """,tenantId,activationId);
            if(changed!=1) throw new StaleRuntimeWriteException("Activation cannot enter WAITING");
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("WAIT_REGISTERED","WAIT",
                    waitId,0L,null,null,"{}")));
            return waitId;
        });
    }

    /** T08: one winner closes Wait; the old Attempt is explicitly ABANDONED_SAFE. */
    public boolean closeWait(UUID tenantId, UUID runId, long epoch, UUID waitId,
                             boolean satisfied, UUID oldAttemptId) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.wait_registration SET wait_state=?,
                       version=version+1,closed_at=clock_timestamp()
                 WHERE tenant_id=? AND wait_id=? AND run_id=? AND wait_state='ACTIVE'
                """,satisfied?"SATISFIED":"EXPIRED",tenantId,waitId,runId);
            if(changed==0) return false;
            int attempt=jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='ABANDONED_SAFE',
                       attempt_version=attempt_version+1,finished_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED')
                """,tenantId,oldAttemptId);
            if(attempt==0) throw new IllegalStateException("Wait closed but old Attempt was not safely abandonable");
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft(
                    satisfied?"WAIT_SATISFIED":"WAIT_EXPIRED","WAIT",waitId,null,null,null,"{}")));
            return true;
        });
    }

    /** T09: create Split and its full expected Branch set atomically. */
    public UUID createSplit(UUID tenantId, UUID runId, long epoch, UUID sourceActivationId,
                            List<String> branchKeys) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            if(branchKeys==null||branchKeys.isEmpty()) throw new IllegalArgumentException("Split needs branches");
            UUID splitId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.split_activation
                  (tenant_id,split_id,run_id,source_activation_id,split_state,expected_branch_count)
                VALUES (?,?,?,?,'OPEN',?)
                """,tenantId,splitId,runId,sourceActivationId,branchKeys.size());
            for(String key:branchKeys) jdbc.update("""
                INSERT INTO workflow_runtime.branch_activation
                  (tenant_id,run_id,split_id,branch_key,branch_state)
                VALUES (?,?,?,?,'ACTIVE')
                """,tenantId,runId,splitId,key);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("SPLIT_CREATED","SPLIT",
                    splitId,0L,null,null,"{\"expectedBranches\":"+branchKeys.size()+"}")));
            return splitId;
        });
    }

    /** T10: idempotent branch arrival; the final arrival releases Join exactly once. */
    public boolean arriveBranch(UUID tenantId, UUID runId, long epoch, UUID splitId,
                                String branchKey, UUID arrivalActivationId, String joinKey) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            jdbc.queryForObject("""
                SELECT split_id FROM workflow_runtime.split_activation
                 WHERE tenant_id=? AND run_id=? AND split_id=? FOR UPDATE
                """,UUID.class,tenantId,runId,splitId);
            int arrived=jdbc.update("""
                UPDATE workflow_runtime.branch_activation SET branch_state='ARRIVED',
                       arrival_activation_id=?,arrived_at=clock_timestamp()
                 WHERE tenant_id=? AND split_id=? AND branch_key=? AND branch_state IN ('ACTIVE','WAITING')
                """,arrivalActivationId,tenantId,splitId,branchKey);
            if(arrived==0) return false;
            jdbc.update("""
                UPDATE workflow_runtime.split_activation SET arrived_branch_count=arrived_branch_count+1,
                       version=version+1
                 WHERE tenant_id=? AND split_id=? AND split_state='OPEN'
                """,tenantId,splitId);
            UUID joinId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.join_activation
                  (tenant_id,join_id,run_id,split_id,join_key,join_state)
                VALUES (?,?,?, ?,?,'WAITING') ON CONFLICT (tenant_id,split_id,join_key) DO NOTHING
                """,tenantId,joinId,runId,splitId,joinKey);
            Integer expected=jdbc.queryForObject("""
                SELECT expected_branch_count FROM workflow_runtime.split_activation
                 WHERE tenant_id=? AND split_id=?
                """,Integer.class,tenantId,splitId);
            Integer count=jdbc.queryForObject("""
                SELECT arrived_branch_count FROM workflow_runtime.split_activation
                 WHERE tenant_id=? AND split_id=?
                """,Integer.class,tenantId,splitId);
            if(Objects.equals(expected,count)) jdbc.update("""
                UPDATE workflow_runtime.join_activation SET join_state='RELEASED',
                       released_at=clock_timestamp(),version=version+1
                 WHERE tenant_id=? AND split_id=? AND join_key=? AND join_state='WAITING'
                """,tenantId,splitId,joinKey);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("BRANCH_ARRIVED","SPLIT",
                    splitId,null,null,null,"{\"branchKey\":\""+jsonEscape(branchKey)+"\"}")));
            return true;
        });
    }

    /** T11: compare-and-set Loop iteration and persist next iteration in the same transaction. */
    public long advanceLoop(UUID tenantId, UUID runId, long epoch, UUID loopId,
                            long expectedVersion, long expectedIteration, String contextJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            Long next=jdbc.queryForObject("""
                UPDATE workflow_runtime.loop_context SET iteration=iteration+1,version=version+1,
                       context_data=?::jsonb
                 WHERE tenant_id=? AND run_id=? AND loop_context_id=? AND loop_state='ACTIVE'
                   AND version=? AND iteration=?
             RETURNING iteration
                """,Long.class,contextJson,tenantId,runId,loopId,expectedVersion,expectedIteration);
            if(next==null) throw new StaleRuntimeWriteException("Loop version/iteration stale");
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("LOOP_ADVANCED","LOOP",
                    loopId,expectedVersion+1,null,null,"{\"iteration\":"+next+"}")));
            return next;
        });
    }

    /** T12: create one immutable Command per device Attempt and its durable dispatch Outbox. */
    public UUID createCommand(UUID tenantId, UUID runId, long epoch, UUID activationId,
                              UUID attemptId, String deviceId, String commandKind,
                              String idempotencyClass, String payloadJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            UUID commandId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.command_ledger
                  (tenant_id,command_id,run_id,activation_id,attempt_id,device_id,command_kind,
                   command_state,idempotency_class,command_payload,payload_hash)
                VALUES (?,?,?,?,?, ?,?,'CREATED',?,?::jsonb,?)
                """,tenantId,commandId,runId,activationId,attemptId,deviceId,commandKind,
                    idempotencyClass,payloadJson,sha256(payloadJson));
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'DEVICE_COMMAND',?,jsonb_build_object('commandId',?::text))
                """,tenantId,runId,"command:"+commandId,commandId);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("COMMAND_CREATED","COMMAND",
                    commandId,0L,null,null,"{}")));
            return commandId;
        });
    }

    /** T13: persist SEND_STARTED before calling an Adapter outside this repository. */
    public boolean markCommandSendStarted(UUID tenantId, UUID commandId, long expectedVersion,
                                          UUID dispatchId, int dispatchNo, String dispatcherId,
                                          long fencingToken) {
        return required(() -> {
            int changed=jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='SEND_STARTED',
                       version=version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND command_id=? AND version=?
                   AND command_state IN ('CREATED','DISPATCH_PENDING')
                """,tenantId,commandId,expectedVersion);
            if(changed==0) return false;
            jdbc.update("""
                INSERT INTO workflow_runtime.command_dispatch_attempt
                  (tenant_id,dispatch_id,command_id,dispatch_no,dispatch_state,dispatcher_id,
                   fencing_token,started_at)
                VALUES (?,?,?,?,'SEND_STARTED',?,?,clock_timestamp())
                """,tenantId,dispatchId,commandId,dispatchNo,dispatcherId,fencingToken);
            return true;
        });
    }

    /** T14a: durable Inbox receipt; duplicate same hash is replay, different hash is a protocol violation. */
    public InboxReceipt receiveInbox(UUID tenantId, String source, String messageId,
                                     String messageType, UUID runId, String payloadJson) {
        return required(() -> {
            byte[] hash=sha256(payloadJson);
            UUID inboxId=UUID.randomUUID();
            int inserted=jdbc.update("""
                INSERT INTO workflow_runtime.inbox_receipt
                  (tenant_id,inbox_id,source,source_message_id,payload_hash,message_type,run_id)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,source,source_message_id) DO NOTHING
                """,tenantId,inboxId,source,messageId,hash,messageType,runId);
            if(inserted==1) return new InboxReceipt(inboxId,true,false);
            UUID originalId=jdbc.queryForObject("""
                SELECT inbox_id FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND source=? AND source_message_id=?
                """,UUID.class,tenantId,source,messageId);
            byte[] originalHash=jdbc.queryForObject("""
                SELECT payload_hash FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND inbox_id=?
                """,byte[].class,tenantId,originalId);
            boolean conflict=!MessageDigest.isEqual(originalHash,hash);
            if(conflict) jdbc.update("""
                INSERT INTO workflow_runtime.inbox_protocol_violation
                  (tenant_id,inbox_id,conflicting_payload_hash,conflicting_payload)
                VALUES (?,?,?,?::jsonb)
                """,tenantId,originalId,hash,payloadJson);
            return new InboxReceipt(originalId,false,conflict);
        });
    }

    /** T14b: process a receipt under Run -> domain row -> Inbox lock order. */
    public boolean markInboxProcessed(UUID tenantId, UUID runId, UUID inboxId, String outcomeJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.inbox_receipt SET receipt_state='PROCESSED',
                       processed_at=clock_timestamp(),outcome=?::jsonb
                 WHERE tenant_id=? AND inbox_id=? AND run_id=?
                   AND receipt_state IN ('RECEIVED','PROCESSING')
                """,outcomeJson,tenantId,inboxId,runId);
            if(changed==1) appendEvents(tenantId,runId,head.coordinatorEpoch(),
                    List.of(new EventDraft("INBOX_PROCESSED","INBOX",inboxId,null,null,null,"{}")));
            return changed==1;
        });
    }

    /** T15: close dispatch gate and persist Drain request atomically. */
    public UUID requestDrain(UUID tenantId, UUID runId, long epoch, String drainKind) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            String gate="PAUSE".equals(drainKind)?"PAUSE":"STOP";
            int changed=jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET control_gate=?,status=?,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND control_gate='OPEN'
                """,gate,"PAUSE".equals(gate)?"PAUSE_REQUESTED":"STOP_REQUESTED",tenantId,runId);
            if(changed!=1) throw new InvalidTransitionException("Run gate is not OPEN");
            UUID drainId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.drain_record
                  (tenant_id,drain_id,run_id,drain_kind,drain_state)
                VALUES (?,?,?,?,'REQUESTED')
                """,tenantId,drainId,runId,drainKind);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'DRAIN_REQUEST',?,jsonb_build_object('drainId',?::text))
                """,tenantId,runId,"drain:"+drainId,drainId);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("DRAIN_REQUESTED","DRAIN",
                    drainId,0L,null,null,"{}")));
            return drainId;
        });
    }

    /** T16: advance a bounded drain scan generation and persist its cursor. */
    public long advanceDrainScan(UUID tenantId, UUID runId, UUID drainId,
                                 long expectedVersion, String cursorJson, String itemsJson) {
        return required(() -> {
            lockRun(tenantId,runId);
            Long generation=jdbc.queryForObject("""
                UPDATE workflow_runtime.drain_record SET drain_state='DRAINING',
                       scan_cursor=?::jsonb,scan_generation=scan_generation+1,version=version+1
                 WHERE tenant_id=? AND run_id=? AND drain_id=? AND version=?
                   AND drain_state IN ('REQUESTED','DRAINING')
             RETURNING scan_generation
                """,Long.class,cursorJson,tenantId,runId,drainId,expectedVersion);
            if(generation==null) throw new StaleRuntimeWriteException("Drain scan version stale");
            // itemsJson is a JSON array of {itemKind,itemId}; parse/insert is intentionally
            // delegated to the typed caller, which uses addDrainItem in the same tx API.
            return generation;
        });
    }

    /** T16 helper: idempotently materialize one drain item. */
    public void addDrainItem(UUID tenantId, UUID drainId, String itemKind, UUID itemId) {
        required(() -> {
            jdbc.update("""
                INSERT INTO workflow_runtime.drain_item
                  (tenant_id,drain_id,item_kind,item_id,item_state)
                VALUES (?,?,?,?,'PENDING')
                ON CONFLICT (tenant_id,drain_id,item_kind,item_id) DO NOTHING
                """,tenantId,drainId,itemKind,itemId);
            return null;
        });
    }

    /** T17: release a held reservation once; decrement capacity only for the CAS winner. */
    public boolean releaseReservation(UUID tenantId, UUID reservationId) {
        return required(() -> {
            String capacityKey=jdbc.queryForObject("""
                SELECT capacity_key FROM workflow_runtime.dispatch_reservation
                 WHERE tenant_id=? AND reservation_id=? FOR UPDATE
                """,String.class,tenantId,reservationId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.dispatch_reservation SET reservation_state='RELEASED',
                       version=version+1,released_at=clock_timestamp()
                 WHERE tenant_id=? AND reservation_id=? AND reservation_state='HELD'
                """,tenantId,reservationId);
            if(changed==0) return false;
            int decremented=jdbc.update("""
                UPDATE workflow_runtime.scheduler_capacity SET reserved_count=reserved_count-1,
                       capacity_version=capacity_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND capacity_key=? AND reserved_count>0
                """,tenantId,capacityKey);
            if(decremented!=1) throw new IllegalStateException("Capacity counter underflow/inconsistency");
            return true;
        });
    }

    /** T18: persist verified physical safe-point evidence; never call Adapter in this transaction. */
    public void recordSafePointEvidence(UUID tenantId, UUID runId, UUID drainId,
                                        String itemKind, UUID itemId, long observedVersion,
                                        String evidenceJson, boolean safe) {
        required(() -> {
            lockRun(tenantId,runId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.drain_item SET item_state=?,last_observed_version=?,
                       evidence=?::jsonb,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND drain_id=? AND item_kind=? AND item_id=?
                   AND item_state IN ('PENDING','IN_PROGRESS','MANUAL_REQUIRED')
                """,safe?"SAFE":"MANUAL_REQUIRED",observedVersion,evidenceJson,
                    tenantId,drainId,itemKind,itemId);
            if(changed!=1) throw new StaleRuntimeWriteException("Drain item already terminal or missing");
            return null;
        });
    }

    /** T19: terminalize only when every unsafe side effect and control-flow continuation is closed. */
    public boolean tryFinalizeDrain(UUID tenantId, UUID runId, UUID drainId, String terminalStatus) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            Long unsafe=jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM workflow_runtime.command_ledger
                    WHERE tenant_id=? AND run_id=? AND command_state NOT IN
                      ('SUCCEEDED','FAILED','CANCELLED','MANUAL_REQUIRED')) +
                  (SELECT count(*) FROM workflow_runtime.resource_lease
                    WHERE tenant_id=? AND run_id=? AND lease_state NOT IN ('RELEASED')) +
                  (SELECT count(*) FROM workflow_runtime.wait_registration
                    WHERE tenant_id=? AND run_id=? AND wait_state='ACTIVE') +
                  (SELECT count(*) FROM workflow_runtime.retry_registration
                    WHERE tenant_id=? AND run_id=? AND retry_state='SCHEDULED') +
                  (SELECT count(*) FROM workflow_runtime.split_activation
                    WHERE tenant_id=? AND run_id=? AND split_state='OPEN') +
                  (SELECT count(*) FROM workflow_runtime.join_activation
                    WHERE tenant_id=? AND run_id=? AND join_state='WAITING') +
                  (SELECT count(*) FROM workflow_runtime.drain_item
                    WHERE tenant_id=? AND drain_id=? AND item_state NOT IN ('SAFE','MANUAL_REQUIRED'))
                """,Long.class,tenantId,runId,tenantId,runId,tenantId,runId,tenantId,runId,
                    tenantId,runId,tenantId,runId,tenantId,drainId);
            if(unsafe==null||unsafe>0) return false;
            int changed=jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET status=?,control_gate='CLOSED',
                       terminal_reason='DRAIN_COMPLETED',finished_at=clock_timestamp(),
                       commit_version=commit_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND status IN
                   ('PAUSING','STOPPING','PAUSE_REQUESTED','STOP_REQUESTED','RUNNING')
                """,terminalStatus,tenantId,runId);
            if(changed==1) jdbc.update("""
                UPDATE workflow_runtime.drain_record SET drain_state='COMPLETED',
                       completed_at=clock_timestamp(),version=version+1
                 WHERE tenant_id=? AND drain_id=? AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT')
                """,tenantId,drainId);
            appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                    "DRAIN_FINALIZED","RUN",runId,null,null,null,"{\"status\":\""+jsonEscape(terminalStatus)+"\"}")));
            return changed==1;
        });
    }

    /** T20: all checkpoint projections are read in one REPEATABLE READ snapshot. */
    public Checkpoint createCheckpoint(UUID tenantId, UUID runId, String checkpointKind) {
        return snapshotTx.execute(status -> {
            RunHead head=jdbc.queryForObject("""
                SELECT status,control_gate,commit_version,event_seq,coordinator_epoch,recovery_state
                  FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?
                """,RUN_HEAD,tenantId,runId);
            if(head==null) throw new IllegalStateException("Run not found");
            String runJson=jdbc.queryForObject("""
                SELECT to_jsonb(r)::text FROM workflow_runtime.workflow_run r
                 WHERE tenant_id=? AND run_id=?
                """,String.class,tenantId,runId);
            String tokens=readJsonArray("execution_token",tenantId,runId);
            String activations=readJsonArray("node_activation",tenantId,runId);
            String attempts=readJsonArray("node_attempt",tenantId,runId);
            String waits=readJsonArray("wait_registration",tenantId,runId);
            String retries=readJsonArray("retry_registration",tenantId,runId);
            String commands=readJsonArray("command_ledger",tenantId,runId);
            String leases=readJsonArray("resource_lease",tenantId,runId);
            String snapshot="{\"run\":"+runJson+",\"tokens\":"+tokens+",\"activations\":"+activations+
                    ",\"attempts\":"+attempts+",\"waits\":"+waits+",\"retries\":"+retries+
                    ",\"commands\":"+commands+",\"leases\":"+leases+"}";
            byte[] hash=sha256(snapshot);
            UUID checkpointId=UUID.randomUUID();
            // Snapshot is created in a separate short write transaction after the consistent read.
            tx.execute(s -> {
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_checkpoint
                      (tenant_id,checkpoint_id,run_id,checkpoint_kind,run_commit_version,event_seq,
                       coordinator_epoch,snapshot_payload,snapshot_hash)
                    VALUES (?,?,?,?,?,?,?,?::jsonb,?)
                    ON CONFLICT (tenant_id,run_id,run_commit_version) DO NOTHING
                    """,tenantId,checkpointId,runId,checkpointKind,head.commitVersion(),
                        head.eventSeq(),head.coordinatorEpoch(),snapshot,hash);
                return null;
            });
            return new Checkpoint(checkpointId,head.commitVersion(),head.eventSeq(),
                    head.coordinatorEpoch(),snapshot,hash);
        });
    }

    private String readJsonArray(String table, UUID tenantId, UUID runId) {
        // Table names are fixed internal constants, never user input.
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(x)),'[]'::jsonb)::text FROM workflow_runtime."
                + table + " x WHERE tenant_id=? AND run_id=?",String.class,tenantId,runId);
    }

    /** T21: mark an ambiguous physical command UNKNOWN; a DB timeout is not proof of no effect. */
    public void markCommandUnknown(UUID tenantId, UUID commandId, String evidenceJson) {
        required(() -> {
            int changed=jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='UNKNOWN',
                       version=version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND command_id=? AND command_state IN
                   ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN')
                """,tenantId,commandId);
            if(changed==1) jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,'SYSTEM','runtime','COMMAND_MARKED_UNKNOWN','COMMAND',?,?::jsonb,'ambiguous external side effect')
                """,tenantId,commandId,evidenceJson);
            return null;
        });
    }

    /** T22: explicit, versioned human decision with immutable audit evidence. */
    public boolean decideIntervention(UUID tenantId, UUID runId, UUID interventionId,
                                      long expectedVersion, String actor, String decisionJson) {
        return required(() -> {
            lockRun(tenantId,runId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.manual_intervention SET intervention_state='DECIDED',
                       decision=?::jsonb,decided_by=?,decided_at=clock_timestamp(),version=version+1
                 WHERE tenant_id=? AND run_id=? AND intervention_id=? AND version=?
                   AND intervention_state='OPEN'
                """,decisionJson,actor,tenantId,runId,interventionId,expectedVersion);
            if(changed==1) {
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_audit
                      (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state)
                    VALUES (? ,?,'HUMAN',?,'INTERVENTION_DECIDED','INTERVENTION',?,?::jsonb)
                    """,tenantId,runId,actor,interventionId,decisionJson);
                appendEvents(tenantId,runId,0,List.of(new EventDraft("INTERVENTION_DECIDED",
                        "INTERVENTION",interventionId,expectedVersion+1,null,null,"{}")));
            }
            return changed==1;
        });
    }

    /** T23: clear quarantine only with explicit evidence; associated Lease must be RELEASED. */
    public boolean clearQuarantine(UUID tenantId, UUID quarantineId, String actor, String evidenceJson) {
        return required(() -> {
            UUID leaseId=jdbc.queryForObject("""
                SELECT lease_id FROM workflow_runtime.quarantine_record
                 WHERE tenant_id=? AND quarantine_id=? FOR UPDATE
                """,UUID.class,tenantId,quarantineId);
            if(leaseId!=null) {
                String leaseState=jdbc.queryForObject("""
                    SELECT lease_state FROM workflow_runtime.resource_lease
                     WHERE tenant_id=? AND lease_id=? FOR UPDATE
                    """,String.class,tenantId,leaseId);
                if(!"RELEASED".equals(leaseState))
                    throw new IllegalStateException("Cannot clear quarantine while Lease is not RELEASED");
            }
            int changed=jdbc.update("""
                UPDATE workflow_runtime.quarantine_record SET quarantine_state='CLEARED',
                       cleared_by=?,cleared_at=clock_timestamp(),version=version+1,
                       evidence=evidence || ?::jsonb
                 WHERE tenant_id=? AND quarantine_id=? AND quarantine_state='CLEAR_PENDING'
                """,actor,evidenceJson,tenantId,quarantineId);
            if(changed==1) jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state)
                VALUES (?,'HUMAN',?,'QUARANTINE_CLEARED','QUARANTINE',?,?::jsonb)
                """,tenantId,actor,quarantineId,evidenceJson);
            return changed==1;
        });
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String jsonEscape(String s) {
        if(s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }

    public static final class StaleRuntimeWriteException extends RuntimeException {
        public StaleRuntimeWriteException(String message) { super(message); }
    }
    public static final class InvalidTransitionException extends RuntimeException {
        public InvalidTransitionException(String message) { super(message); }
    }
    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) { super(message); }
    }
    public static final class CapacityExhaustedException extends RuntimeException {
        public CapacityExhaustedException(String key) { super("Capacity exhausted: "+key); }
    }
}
