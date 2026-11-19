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
import java.sql.Timestamp;
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
    public record DrainItemRef(String itemKind, UUID itemId) {}

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

    private long appendWorkerFactInTransaction(UUID tenantId, UUID runId, UUID attemptId,
                                                String factKey, String factType, String actorKind,
                                                String actorId, UUID dispatchId, long dispatchEpoch,
                                                long leaseEpoch, String payloadJson) {
        List<Long> existing=jdbc.query("""
            SELECT fact_seq FROM workflow_runtime.worker_execution_fact
             WHERE tenant_id=? AND attempt_id=? AND fact_key=?
            """,(rs,n)->rs.getLong(1),tenantId,attemptId,factKey);
        if(!existing.isEmpty()) {
            Boolean same=jdbc.queryForObject("""
                SELECT fact_type=? AND actor_kind=? AND actor_id=? AND dispatch_id IS NOT DISTINCT FROM ?::uuid
                   AND dispatch_epoch=? AND lease_epoch=? AND payload=?::jsonb
                  FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_key=?
                """,Boolean.class,factType,actorKind,actorId,dispatchId==null?null:dispatchId.toString(),
                    dispatchEpoch,leaseEpoch,payloadJson,tenantId,attemptId,factKey);
            if(!Boolean.TRUE.equals(same))
                throw new IdempotencyConflictException("Worker fact key replay differs from original fact");
            return existing.get(0);
        }
        Long seq=jdbc.queryForObject("""
            UPDATE workflow_runtime.worker_execution_lease SET last_fact_seq=last_fact_seq+1
             WHERE tenant_id=? AND attempt_id=? RETURNING last_fact_seq
            """,Long.class,tenantId,attemptId);
        if(seq==null) throw new IllegalStateException("Worker execution lease missing for fact");
        jdbc.update("""
            INSERT INTO workflow_runtime.worker_execution_fact
              (tenant_id,run_id,attempt_id,fact_seq,fact_key,fact_type,actor_kind,actor_id,
               dispatch_id,dispatch_epoch,lease_epoch,payload)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
            """,tenantId,runId,attemptId,seq,factKey,factType,actorKind,actorId,
                dispatchId,dispatchEpoch,leaseEpoch,payloadJson==null?"{}":payloadJson);
        return seq;
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

    private boolean releaseReservationInTransaction(UUID tenantId, UUID attemptId) {
        List<String> keys=jdbc.query("""
            SELECT capacity_key FROM workflow_runtime.dispatch_reservation
             WHERE tenant_id=? AND attempt_id=? AND reservation_state='HELD'
            """,(rs,n)->rs.getString(1),tenantId,attemptId);
        if(keys.isEmpty()) return false;
        String key=keys.get(0);
        jdbc.queryForObject("""
            SELECT capacity_key FROM workflow_runtime.scheduler_capacity
             WHERE tenant_id=? AND capacity_key=? FOR UPDATE
            """,String.class,tenantId,key);
        int released=jdbc.update("""
            UPDATE workflow_runtime.dispatch_reservation SET reservation_state='RELEASED',
                   version=version+1,released_at=clock_timestamp()
             WHERE tenant_id=? AND attempt_id=? AND reservation_state='HELD'
            """,tenantId,attemptId);
        if(released==0) return false;
        int decremented=jdbc.update("""
            UPDATE workflow_runtime.scheduler_capacity SET reserved_count=reserved_count-1,
                   capacity_version=capacity_version+1,updated_at=clock_timestamp()
             WHERE tenant_id=? AND capacity_key=? AND reserved_count>0
            """,tenantId,key);
        if(decremented!=1) throw new IllegalStateException("Capacity counter underflow/inconsistency");
        return true;
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
                ON CONFLICT (tenant_id,definition_key,definition_version,definition_hash) DO NOTHING
                """, tenantId, definitionId, definitionKey, definitionVersion, definitionHash,
                    compilerVersion, graphPlanJson);
            UUID actualDefinitionId = jdbc.queryForObject("""
                SELECT definition_id FROM workflow_runtime.definition_snapshot
                 WHERE tenant_id=? AND definition_key=? AND definition_version=? AND definition_hash=?
                """, UUID.class, tenantId, definitionKey, definitionVersion, definitionHash);
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
            if (expectedEpoch <= head.coordinatorEpoch())
                throw new StaleRuntimeWriteException("Coordinator epoch must strictly advance");
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
            UUID tokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key=? AND token_state='READY'
                 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, UUID.class, tenantId, runId, nodeKey);
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
                """, tenantId, reservationId, runId, attemptId, capacityKey, Timestamp.from(expiresAt));
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
            int activationReserved=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='RESERVED',
                       current_attempt_id=?,activation_version=activation_version+1
                 WHERE tenant_id=? AND run_id=? AND activation_id=(SELECT activation_id
                   FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?)
                   AND activation_state='RESERVED'
                """, attemptId, tenantId, runId, tenantId, attemptId);
            if(activationReserved!=1) throw new StaleRuntimeWriteException("Activation reservation lost");
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'WORKER_DISPATCH',?,jsonb_set(?::jsonb,'{queue}',to_jsonb(?::text),true))
                """, tenantId, runId, "dispatch:"+attemptId, payloadJson, workerQueue);
            appendEvents(tenantId, runId, epoch, List.of(new EventDraft(
                    "ATTEMPT_DISPATCHED","ATTEMPT",attemptId,1L,null,null,"{}")));
            return null;
        });
    }

    /** T04-A: accept only the exact dispatch identity. */
    public void acceptAttempt(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                              long dispatchEpoch, String workerId) {
        required(() -> {
            RunHead head=lockRun(tenantId, runId);
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='ACCEPTED',
                       worker_id=?,attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND dispatch_id=?
                   AND dispatch_epoch=? AND attempt_state='DISPATCHED'
                """, workerId, tenantId, runId, attemptId, dispatchId, dispatchEpoch);
            if (changed != 1) throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            appendEvents(tenantId, runId, head.coordinatorEpoch(), List.of(new EventDraft(
                    "ATTEMPT_ACCEPTED","ATTEMPT",attemptId,null,null,null,"{}")));
            return null;
        });
    }

    /** T04-S: Started is strictly ACCEPTED -> STARTED, never DISPATCHED -> STARTED. */
    public void startAttempt(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                             long dispatchEpoch, String workerId, Instant leaseExpiresAt) {
        required(() -> {
            if(leaseExpiresAt==null || !leaseExpiresAt.isAfter(Instant.now()))
                throw new IllegalArgumentException("Worker lease expiry must be in the future");
            RunHead head = lockRun(tenantId, runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,UUID.class,tenantId,runId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='STARTED',
                       started_at=clock_timestamp(),attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND dispatch_id=?
                   AND dispatch_epoch=? AND worker_id=? AND attempt_state='ACCEPTED'
                """, tenantId, runId, attemptId, dispatchId, dispatchEpoch, workerId);
            if (changed != 1) throw new StaleRuntimeWriteException("Only accepted owning Worker may start");
            int activationChanged=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='RUNNING',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND current_attempt_id=?
                   AND activation_state IN ('RESERVED','READY')
                """,tenantId,runId,attemptId);
            if(activationChanged!=1) throw new StaleRuntimeWriteException("Activation is not owned by this Attempt");
            int tokenChanged=jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='RUNNING',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=(SELECT token_id FROM workflow_runtime.node_activation
                   WHERE tenant_id=? AND current_attempt_id=?)
                   AND token_state='RESERVED'
                """,tenantId,tenantId,attemptId);
            if(tokenChanged!=1) throw new StaleRuntimeWriteException("Token is not RESERVED for this Attempt");
            jdbc.update("""
                INSERT INTO workflow_runtime.worker_execution_lease
                  (tenant_id,run_id,attempt_id,worker_id,dispatch_id,dispatch_epoch,
                   lease_epoch,lease_state,expires_at)
                VALUES (?,?,?,?,?,?,1,'ACTIVE',?)
                """,tenantId,runId,attemptId,workerId,dispatchId,dispatchEpoch,Timestamp.from(leaseExpiresAt));
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"handler-start","HANDLER_STARTED",
                    "WORKER",workerId,dispatchId,dispatchEpoch,1L,"{}");
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
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """, UUID.class, tenantId, runId, attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            Integer liveWorkerLease=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND lease_state='ACTIVE'
                   AND expires_at>clock_timestamp() FOR UPDATE
                """,Integer.class,tenantId,runId,attemptId);
            if(liveWorkerLease==null||liveWorkerLease!=1)
                throw new StaleRuntimeWriteException("Expired or released Worker cannot complete an Attempt");
            String sideEffectClass=jdbc.queryForObject("""
                SELECT side_effect_class FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND attempt_id=?
                """,String.class,tenantId,attemptId);
            if("DEVICE".equals(sideEffectClass)) {
                Integer commandSucceeded=jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.command_ledger
                     WHERE tenant_id=? AND attempt_id=? AND command_state='SUCCEEDED'
                    """,Integer.class,tenantId,attemptId);
                if(commandSucceeded==null||commandSucceeded!=1)
                    throw new IllegalStateException("Device Attempt requires a SUCCEEDED Command before output commit");
            }
            List<String> existingAttemptState=jdbc.query("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,runId,attemptId);
            if(existingAttemptState.size()!=1) throw new IllegalStateException("Attempt not found");
            if("SUCCEEDED".equals(existingAttemptState.get(0))) {
                String stored=jdbc.queryForObject("""
                    SELECT result::text FROM workflow_runtime.node_attempt
                     WHERE tenant_id=? AND attempt_id=?
                    """,String.class,tenantId,attemptId);
                if(stored!=null && jdbc.queryForObject("SELECT ?::jsonb = ?::jsonb",Boolean.class,stored,outputJson))
                    return null;
                throw new IdempotencyConflictException("Attempt completion replay differs from committed result");
            }
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
            int activationChanged=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='SUCCEEDED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state='RUNNING'
                """, tenantId, activationId);
            if(activationChanged!=1) throw new StaleRuntimeWriteException("Activation is not RUNNING");
            int tokenChanged=jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=(SELECT token_id FROM workflow_runtime.node_activation
                   WHERE tenant_id=? AND activation_id=?) AND token_state IN ('RUNNING','RESERVED','WAITING')
                """, tenantId, tenantId, activationId);
            if(tokenChanged!=1) throw new StaleRuntimeWriteException("Token is not active");
            releaseReservationInTransaction(tenantId,attemptId);
            List<String> leaseIdentity=jdbc.query("""
                SELECT dispatch_id::text||'|'||dispatch_epoch||'|'||lease_epoch
                  FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND attempt_id=? AND lease_state='ACTIVE' FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,attemptId);
            if(leaseIdentity.size()!=1) throw new StaleRuntimeWriteException("Worker execution lease is not active at completion");
            String[] leaseParts=leaseIdentity.get(0).split("\\|");
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"execution-right-released","EXECUTION_RIGHT_RELEASED",
                    "WORKER","completion",UUID.fromString(leaseParts[0]),Long.parseLong(leaseParts[1]),
                    Long.parseLong(leaseParts[2]),"{\"reason\":\"attempt-succeeded\"}");
            jdbc.update("""
                UPDATE workflow_runtime.worker_execution_lease SET lease_state='RELEASED',
                       lease_version=lease_version+1,ended_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND lease_state='ACTIVE'
                """,tenantId,attemptId);
            appendEvents(tenantId, runId, head.coordinatorEpoch(), List.of(new EventDraft(
                    "ATTEMPT_SUCCEEDED","ATTEMPT",attemptId,null,null,null,
                    "{\"activationId\":\""+activationId+"\",\"outputKey\":\""+jsonEscape(outputKey)+"\"}")));
            return null;
        });
    }

    /** Allocate a monotonically increasing fencing token and acquire one DB Lease. */
    public record AcquiredLease(UUID leaseId, long fencingToken) {}
    public AcquiredLease acquireResourceLease(UUID tenantId, UUID runId, long epoch,
                                               String resourceId, String ownerKind, UUID ownerId,
                                               Instant expiresAt, String evidenceJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            if(!"OPEN".equals(head.controlGate()))
                throw new InvalidTransitionException("Run gate blocks new resource Lease");
            jdbc.update("""
                INSERT INTO workflow_runtime.resource_fence_counter
                  (tenant_id,resource_id,last_fencing_token)
                VALUES (?,?,0) ON CONFLICT (tenant_id,resource_id) DO NOTHING
                """,tenantId,resourceId);
            jdbc.queryForObject("""
                SELECT last_fencing_token FROM workflow_runtime.resource_fence_counter
                 WHERE tenant_id=? AND resource_id=? FOR UPDATE
                """,Long.class,tenantId,resourceId);
            Long fencing=jdbc.queryForObject("""
                UPDATE workflow_runtime.resource_fence_counter
                   SET last_fencing_token=last_fencing_token+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND resource_id=? RETURNING last_fencing_token
                """,Long.class,tenantId,resourceId);
            UUID leaseId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.resource_lease
                  (tenant_id,lease_id,run_id,resource_id,lease_state,owner_kind,owner_id,
                   fencing_token,expires_at,evidence)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,?,?::jsonb)
                """,tenantId,leaseId,runId,resourceId,ownerKind,ownerId,fencing,
                    Timestamp.from(expiresAt),evidenceJson==null?"{}":evidenceJson);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("RESOURCE_LEASE_ACQUIRED",
                    "LEASE",leaseId,0L,null,null,"{\"resourceId\":\""+jsonEscape(resourceId)+
                    "\",\"fencingToken\":"+fencing+"}")));
            return new AcquiredLease(leaseId,fencing);
        });
    }

    /** T21: reconcile an UNKNOWN Command only from explicit device/query evidence. */
    public boolean resolveUnknownCommand(UUID tenantId, UUID commandId, String resolvedState,
                                          String deviceSequence, String evidenceJson, String actor) {
        return required(() -> {
            if(!List.of("SUCCEEDED","FAILED","CANCELLED","MANUAL_REQUIRED").contains(resolvedState))
                throw new IllegalArgumentException("T21 resolution must be terminal or manual");
            List<String> identity=jdbc.query("""
                SELECT run_id::text||'|'||attempt_id::text FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND command_id=?
                """,(rs,n)->rs.getString(1),tenantId,commandId);
            if(identity.size()!=1) throw new IllegalStateException("Command not found");
            String[] ids=identity.get(0).split("\\|");
            UUID runId=UUID.fromString(ids[0]), attemptId=UUID.fromString(ids[1]);
            RunHead head=lockRun(tenantId,runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND attempt_id=?
                """,UUID.class,tenantId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            List<String> lease=jdbc.query("""
                SELECT dispatch_id::text||'|'||dispatch_epoch||'|'||lease_epoch
                  FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,attemptId);
            if(lease.size()!=1) throw new IllegalStateException("Worker lease missing for Command");
            String[] leaseParts=lease.get(0).split("\\|");
            jdbc.queryForObject("SELECT command_id FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_id=? FOR UPDATE",
                    UUID.class,tenantId,commandId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state=?,device_sequence=?,
                       version=version+1,updated_at=clock_timestamp(),
                       completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN clock_timestamp() ELSE completed_at END
                 WHERE tenant_id=? AND command_id=? AND command_state IN ('UNKNOWN','DELIVERY_UNKNOWN')
                """,resolvedState,deviceSequence,resolvedState,tenantId,commandId);
            if(changed==0) return false;
            String factType="MANUAL_REQUIRED".equals(resolvedState)?"SIDE_EFFECT_UNKNOWN":"SIDE_EFFECT_COMPLETED";
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"command-resolved:"+commandId,
                    factType,"RECOVERY",actor,UUID.fromString(leaseParts[0]),
                    Long.parseLong(leaseParts[1]),Long.parseLong(leaseParts[2]),evidenceJson);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,?,'SYSTEM',?,'UNKNOWN_COMMAND_RESOLVED','COMMAND',?,?::jsonb,?)
                """,tenantId,runId,actor,commandId,evidenceJson,"explicit external verification");
            appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                    "COMMAND_VERIFIED_"+resolvedState,"COMMAND",commandId,null,null,null,evidenceJson)));
            return true;
        });
    }

    /** T06: register retry intent; timer scheduling is an Outbox effect after commit. */
    public UUID scheduleRetry(UUID tenantId, UUID runId, long epoch, UUID activationId,
                              Instant dueAt, int nextAttemptNo, String policyJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            UUID candidate=UUID.randomUUID();
            int inserted=jdbc.update("""
                INSERT INTO workflow_runtime.retry_registration
                  (tenant_id,retry_id,run_id,activation_id,retry_state,due_at,attempt_no,retry_policy)
                VALUES (?,?,?,?,'SCHEDULED',?,?,?::jsonb)
                ON CONFLICT (tenant_id,activation_id,attempt_no) DO NOTHING
                """,tenantId,candidate,runId,activationId,Timestamp.from(dueAt),nextAttemptNo,policyJson);
            UUID retryId=jdbc.queryForObject("""
                SELECT retry_id FROM workflow_runtime.retry_registration
                 WHERE tenant_id=? AND activation_id=? AND attempt_no=?
                """,UUID.class,tenantId,activationId,nextAttemptNo);
            if(inserted==1) {
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_outbox
                      (tenant_id,run_id,message_kind,dedup_key,payload)
                    VALUES (?,?,'RETRY_TIMER',?,jsonb_build_object('retryId',?::text))
                    """,tenantId,runId,"retry:"+retryId,retryId);
                appendEvents(tenantId,runId,epoch,List.of(new EventDraft("RETRY_SCHEDULED","RETRY",
                        retryId,0L,null,null,"{}")));
            }
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
                """,tenantId,waitId,runId,activationId,waitKind,correlationKey,deadline==null?null:Timestamp.from(deadline),continuationJson);
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
            List<String> continuationRows=jdbc.query("""
                SELECT continuation->>'nodeKey' FROM workflow_runtime.wait_registration
                 WHERE tenant_id=? AND wait_id=? AND run_id=? AND wait_state='ACTIVE'
                """,(rs,n)->rs.getString(1),tenantId,waitId,runId);
            if(continuationRows.isEmpty()) return false;
            String continuationNode=continuationRows.get(0);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.wait_registration
                 WHERE tenant_id=? AND wait_id=? AND run_id=?
                """,UUID.class,tenantId,waitId,runId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            // Global lock order after Run: Token -> Activation -> Attempt -> Wait.
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,oldAttemptId);
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
            releaseReservationInTransaction(tenantId,oldAttemptId);
            jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='ABANDONED_SAFE',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state='WAITING'
                """,tenantId,activationId);
            jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state='WAITING'
                """,tenantId,tokenId);
            if(continuationNode==null||continuationNode.isBlank())
                throw new IllegalStateException("Wait continuation has no nodeKey");
            jdbc.update("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,run_id,token_state,node_key,parent_token_id,payload)
                VALUES (?,?,'READY',?,?,?::jsonb)
                """,tenantId,runId,continuationNode,tokenId,"{}");
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
            UUID sourceToken=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND run_id=? AND activation_id=?
                """,UUID.class,tenantId,runId,sourceActivationId);
            jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND token_id=? FOR UPDATE
                """,UUID.class,tenantId,sourceToken);
            jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=? FOR UPDATE
                """,UUID.class,tenantId,sourceActivationId);
            int sourceActivationChanged=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='SUCCEEDED',
                       activation_version=activation_version+1
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('RUNNING','RESERVED')
                """,tenantId,sourceActivationId);
            if(sourceActivationChanged!=1) throw new StaleRuntimeWriteException("Split source Activation is not active");
            int sourceTokenChanged=jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1
                 WHERE tenant_id=? AND token_id=? AND token_state IN ('RUNNING','RESERVED')
                """,tenantId,sourceToken);
            if(sourceTokenChanged!=1) throw new StaleRuntimeWriteException("Split source Token is not active");
            UUID splitId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.split_activation
                  (tenant_id,split_id,run_id,source_activation_id,split_state,expected_branch_count)
                VALUES (?,?,?,?,'OPEN',?)
                """,tenantId,splitId,runId,sourceActivationId,branchKeys.size());
            for(String key:branchKeys) {
                UUID branchId=UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO workflow_runtime.branch_activation
                      (tenant_id,branch_id,run_id,split_id,branch_key,branch_state)
                    VALUES (?,?,?, ?,?,'ACTIVE')
                    """,tenantId,branchId,runId,splitId,key);
                jdbc.update("""
                    INSERT INTO workflow_runtime.execution_token
                      (tenant_id,run_id,token_state,node_key,split_id,branch_id)
                    VALUES (?,?,'READY',?,?,?)
                    """,tenantId,runId,key,splitId,branchId);
            }
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
            UUID branchId=jdbc.queryForObject("""
                SELECT branch_id FROM workflow_runtime.branch_activation
                 WHERE tenant_id=? AND split_id=? AND branch_key=?
                """,UUID.class,tenantId,splitId,branchKey);
            int branchTokenChanged=jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1
                 WHERE tenant_id=? AND branch_id=? AND token_state IN ('READY','RUNNING','WAITING')
                """,tenantId,branchId);
            if(branchTokenChanged==0) {
                String tokenState=jdbc.queryForObject("""
                    SELECT token_state FROM workflow_runtime.execution_token
                     WHERE tenant_id=? AND branch_id=?
                    """,String.class,tenantId,branchId);
                if(!"CONSUMED".equals(tokenState))
                    throw new StaleRuntimeWriteException("Branch Token is neither active nor already consumed");
            }
            int splitAdvanced=jdbc.update("""
                UPDATE workflow_runtime.split_activation SET arrived_branch_count=arrived_branch_count+1,
                       version=version+1
                 WHERE tenant_id=? AND split_id=? AND split_state='OPEN'
                """,tenantId,splitId);
            if(splitAdvanced!=1) throw new StaleRuntimeWriteException("Split is no longer OPEN");
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
            if(Objects.equals(expected,count)) {
                int released=jdbc.update("""
                    UPDATE workflow_runtime.join_activation SET join_state='RELEASED',
                           released_at=clock_timestamp(),version=version+1
                     WHERE tenant_id=? AND split_id=? AND join_key=? AND join_state='WAITING'
                    """,tenantId,splitId,joinKey);
                if(released==1) jdbc.update("""
                    INSERT INTO workflow_runtime.execution_token
                      (tenant_id,run_id,token_state,node_key,split_id)
                    VALUES (?,?,'READY',?,?)
                    """,tenantId,runId,joinKey,splitId);
            }
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("BRANCH_ARRIVED","SPLIT",
                    splitId,null,null,null,"{\"branchKey\":\""+jsonEscape(branchKey)+"\"}")));
            return true;
        });
    }

    /** T11: compare-and-set Loop iteration and persist next iteration in the same transaction. */
    public long advanceLoop(UUID tenantId, UUID runId, long epoch, UUID loopId,
                            long expectedVersion, long expectedIteration, String nextNodeKey,
                            String contextJson) {
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
            jdbc.update("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,run_id,token_state,node_key,loop_context_id)
                VALUES (?,?,'READY',?,?)
                """,tenantId,runId,nextNodeKey,loopId);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("LOOP_ADVANCED","LOOP",
                    loopId,expectedVersion+1,null,null,"{\"iteration\":"+next+"}")));
            return next;
        });
    }

    public boolean completeLoop(UUID tenantId, UUID runId, long epoch, UUID loopId,
                                long expectedVersion) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.loop_context SET loop_state='COMPLETED',version=version+1
                 WHERE tenant_id=? AND run_id=? AND loop_context_id=? AND version=? AND loop_state='ACTIVE'
                """,tenantId,runId,loopId,expectedVersion);
            if(changed==1) appendEvents(tenantId,runId,epoch,List.of(new EventDraft(
                    "LOOP_COMPLETED","LOOP",loopId,expectedVersion+1,null,null,"{}")));
            return changed==1;
        });
    }

    /** T12: create one immutable Command per device Attempt and its durable dispatch Outbox. */
    public UUID createCommand(UUID tenantId, UUID runId, long epoch, UUID activationId,
                              UUID attemptId, String deviceId, String commandKind,
                              String idempotencyClass, String payloadJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); requireCoordinator(head,epoch);
            List<UUID> existing=jdbc.query("""
                SELECT command_id FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),tenantId,attemptId);
            byte[] hash=sha256(payloadJson);
            if(!existing.isEmpty()) {
                byte[] existingHash=jdbc.queryForObject("""
                    SELECT payload_hash FROM workflow_runtime.command_ledger
                     WHERE tenant_id=? AND command_id=?
                    """,byte[].class,tenantId,existing.get(0));
                if(!MessageDigest.isEqual(existingHash,hash))
                    throw new IdempotencyConflictException("Attempt already owns a different immutable Command payload");
                return existing.get(0);
            }
            UUID commandId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.command_ledger
                  (tenant_id,command_id,run_id,activation_id,attempt_id,device_id,command_kind,
                   command_state,idempotency_class,command_payload,payload_hash)
                VALUES (?,?,?,?,?, ?,?,'CREATED',?,?::jsonb,?)
                """,tenantId,commandId,runId,activationId,attemptId,deviceId,commandKind,
                    idempotencyClass,payloadJson,hash);
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

    public record OutboxClaim(UUID outboxId, String dedupKey, String payloadJson,
                              String owner, long claimEpoch) {}

    /** Claim pending/expired Outbox rows with SKIP LOCKED; the sender acts only after commit. */
    public List<OutboxClaim> claimOutbox(UUID tenantId, String owner, int batchSize, Instant claimUntil) {
        return required(() -> jdbc.query("""
            WITH picked AS (
              SELECT outbox_id FROM workflow_runtime.runtime_outbox
               WHERE tenant_id=? AND (outbox_state='PENDING' OR
                 (outbox_state='CLAIMED' AND claim_until<clock_timestamp()))
               ORDER BY available_at,created_at
               FOR UPDATE SKIP LOCKED LIMIT ?
            )
            UPDATE workflow_runtime.runtime_outbox o
               SET outbox_state='CLAIMED',claim_owner=?,claim_epoch=claim_epoch+1,
                   claim_until=?,delivery_attempts=delivery_attempts+1
              FROM picked p WHERE o.tenant_id=? AND o.outbox_id=p.outbox_id
            RETURNING o.outbox_id,o.dedup_key,o.payload::text,o.claim_owner,o.claim_epoch
            """,(rs,n)->new OutboxClaim(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getString(3),rs.getString(4),rs.getLong(5)),
                tenantId,batchSize,owner,java.sql.Timestamp.from(claimUntil),tenantId));
    }

    /** Mark delivery only if the exact owner and fencing epoch still own the claim. */
    public boolean markOutboxDelivered(UUID tenantId, UUID outboxId, String owner, long epoch) {
        return required(() -> jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED',
                   delivered_at=clock_timestamp(),claim_until=NULL
             WHERE tenant_id=? AND outbox_id=? AND outbox_state='CLAIMED'
               AND claim_owner=? AND claim_epoch=?
            """,tenantId,outboxId,owner,epoch)==1);
    }

    /** T13: persist SEND_STARTED before calling an Adapter outside this repository. */
    public boolean markCommandSendStarted(UUID tenantId, UUID commandId, long expectedVersion,
                                          UUID outboxId, String outboxOwner, long outboxClaimEpoch,
                                          UUID dispatchId, int dispatchNo, String dispatcherId,
                                          long fencingToken) {
        return required(() -> {
            List<String> identity=jdbc.query("""
                SELECT run_id::text||'|'||attempt_id::text FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND command_id=?
                """,(rs,n)->rs.getString(1),tenantId,commandId);
            if(identity.size()!=1) throw new IllegalStateException("Command not found");
            String[] ids=identity.get(0).split("\\|");
            UUID runId=UUID.fromString(ids[0]), attemptId=UUID.fromString(ids[1]);
            RunHead head=lockRun(tenantId,runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,UUID.class,tenantId,runId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            String attemptState=jdbc.queryForObject("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND attempt_id=? FOR UPDATE
                """,String.class,tenantId,attemptId);
            if(!"STARTED".equals(attemptState) || !"OPEN".equals(head.controlGate())) return false;
            List<String> leaseRows=jdbc.query("""
                SELECT dispatch_epoch::text||'|'||lease_epoch FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND attempt_id=? AND lease_state='ACTIVE'
                   AND expires_at>clock_timestamp() FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,attemptId);
            if(leaseRows.size()!=1)
                throw new StaleRuntimeWriteException("Worker execution right expired before Command dispatch");
            String[] leaseParts=leaseRows.get(0).split("\\|");
            long workerDispatchEpoch=Long.parseLong(leaseParts[0]), leaseEpoch=Long.parseLong(leaseParts[1]);
            jdbc.queryForObject("""
                SELECT command_id FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND command_id=? FOR UPDATE
                """,UUID.class,tenantId,commandId);
            List<String> outboxPayload=jdbc.query("""
                SELECT payload->>'commandId' FROM workflow_runtime.runtime_outbox
                 WHERE tenant_id=? AND outbox_id=? AND outbox_state='CLAIMED'
                   AND claim_owner=? AND claim_epoch=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,outboxId,outboxOwner,outboxClaimEpoch);
            if(outboxPayload.size()!=1 || !commandId.toString().equals(outboxPayload.get(0)))
                throw new StaleRuntimeWriteException("Dispatcher does not own the Command Outbox claim");
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
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"command-send:"+dispatchId,
                    "SIDE_EFFECT_STARTED","DISPATCHER",dispatcherId,dispatchId,workerDispatchEpoch,
                    leaseEpoch,"{\"commandId\":\""+commandId+"\",\"dispatchNo\":"+dispatchNo+"}");
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
                  (tenant_id,inbox_id,source,source_message_id,payload_hash,message_type,message_payload,run_id)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (tenant_id,source,source_message_id) DO NOTHING
                """,tenantId,inboxId,source,messageId,hash,messageType,payloadJson,runId);
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

    /** T14: attribute device evidence exclusively by command_id and process Inbox atomically. */
    public boolean applyCommandEvent(UUID tenantId, UUID runId, UUID inboxId, UUID commandId,
                                     String targetState, String deviceSequence, String outcomeJson) {
        return required(() -> {
            List<String> identity=jdbc.query("""
                SELECT attempt_id::text||'|'||device_id FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND run_id=? AND command_id=?
                """,(rs,n)->rs.getString(1),tenantId,runId,commandId);
            if(identity.size()!=1) throw new IllegalStateException("command_id does not belong to this Run");
            String[] parts=identity.get(0).split("\\|",2);
            UUID attemptId=UUID.fromString(parts[0]); String deviceId=parts[1];
            RunHead head=lockRun(tenantId,runId);
            if (!List.of("ACKNOWLEDGED","RUNNING","SUCCEEDED","FAILED","CANCELLED",
                    "DELIVERY_UNKNOWN","UNKNOWN","MANUAL_REQUIRED").contains(targetState))
                throw new IllegalArgumentException("Unsupported device Command state: "+targetState);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,UUID.class,tenantId,runId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            List<String> leaseRows=jdbc.query("""
                SELECT dispatch_id::text||'|'||dispatch_epoch||'|'||lease_epoch
                  FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,attemptId);
            if(leaseRows.size()!=1) throw new IllegalStateException("Worker execution fact ledger missing for Command Attempt");
            String[] lease=leaseRows.get(0).split("\\|");
            List<String> states=jdbc.query("""
                SELECT command_state FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND run_id=? AND command_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,runId,commandId);
            if(states.size()!=1) throw new IllegalStateException("Command disappeared while applying device event");
            String inboxCommandId=jdbc.queryForObject("""
                SELECT message_payload->>'commandId' FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND inbox_id=? AND run_id=? AND receipt_state IN ('RECEIVED','PROCESSING')
                """,String.class,tenantId,inboxId,runId);
            if(!commandId.toString().equals(inboxCommandId))
                throw new IllegalStateException("Inbox commandId does not match command row; event attribution rejected");
            String current=states.get(0);
            boolean allowed=switch(current) {
                case "SEND_STARTED" -> List.of("ACKNOWLEDGED","RUNNING","SUCCEEDED","FAILED","DELIVERY_UNKNOWN","UNKNOWN").contains(targetState);
                case "ACKNOWLEDGED" -> List.of("RUNNING","SUCCEEDED","FAILED","CANCEL_REQUESTED","DELIVERY_UNKNOWN").contains(targetState);
                case "RUNNING" -> List.of("SUCCEEDED","FAILED","CANCEL_REQUESTED","DELIVERY_UNKNOWN").contains(targetState);
                case "CANCEL_REQUESTED" -> List.of("CANCELLED","SUCCEEDED","FAILED","DELIVERY_UNKNOWN").contains(targetState);
                case "DELIVERY_UNKNOWN" -> List.of("ACKNOWLEDGED","RUNNING","SUCCEEDED","FAILED","UNKNOWN").contains(targetState);
                default -> false;
            };
            if(!allowed) throw new InvalidTransitionException("Illegal Command transition "+current+" -> "+targetState);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state=?,device_sequence=?,
                       version=version+1,updated_at=clock_timestamp(),
                       completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN clock_timestamp() ELSE completed_at END
                 WHERE tenant_id=? AND command_id=? AND command_state=?
                """,targetState,deviceSequence,targetState,tenantId,commandId,current);
            if(changed!=1) throw new StaleRuntimeWriteException("Command state changed concurrently");
            int receiptChanged=jdbc.update("""
                UPDATE workflow_runtime.inbox_receipt SET receipt_state='PROCESSED',
                       processed_at=clock_timestamp(),outcome=?::jsonb
                 WHERE tenant_id=? AND inbox_id=? AND run_id=?
                   AND receipt_state IN ('RECEIVED','PROCESSING')
                """,outcomeJson,tenantId,inboxId,runId);
            if(receiptChanged!=1) throw new StaleRuntimeWriteException("Inbox receipt was already consumed");
            if("SUCCEEDED".equals(targetState) || List.of("FAILED","CANCELLED","DELIVERY_UNKNOWN","UNKNOWN","MANUAL_REQUIRED").contains(targetState)) {
                String factType="SUCCEEDED".equals(targetState)?"SIDE_EFFECT_COMPLETED":"SIDE_EFFECT_UNKNOWN";
                appendWorkerFactInTransaction(tenantId,runId,attemptId,"command-result:"+inboxId,factType,
                        "DEVICE",deviceId,UUID.fromString(lease[0]),Long.parseLong(lease[1]),
                        Long.parseLong(lease[2]),outcomeJson);
            }
            appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                    "DEVICE_COMMAND_"+targetState,"COMMAND",commandId,null,inboxId,null,outcomeJson)));
            return true;
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
            if(!List.of("PAUSE","STOP","FAILURE").contains(drainKind))
                throw new IllegalArgumentException("Unsupported drain kind");
            String requestedGate="PAUSE".equals(drainKind)?"PAUSE":"STOP";
            List<UUID> openDrains=jdbc.query("""
                SELECT drain_id FROM workflow_runtime.drain_record
                 WHERE tenant_id=? AND run_id=? AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT','MANUAL_REQUIRED')
                 ORDER BY created_at DESC FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),tenantId,runId);

            if("STOP".equals(requestedGate) && "PAUSE".equals(head.controlGate()) && !openDrains.isEmpty()) {
                UUID drainId=openDrains.get(0);
                jdbc.update("""
                    UPDATE workflow_runtime.workflow_run SET control_gate='STOP',status='STOP_REQUESTED',
                           updated_at=clock_timestamp()
                     WHERE tenant_id=? AND run_id=?
                    """,tenantId,runId);
                jdbc.update("""
                    UPDATE workflow_runtime.drain_record SET drain_kind='STOP',version=version+1
                     WHERE tenant_id=? AND drain_id=? AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT')
                    """,tenantId,drainId);
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_outbox
                      (tenant_id,run_id,message_kind,dedup_key,payload)
                    VALUES (?,?,'DRAIN_REQUEST',?,jsonb_build_object('drainId',?::text,'kind','STOP'))
                    ON CONFLICT (tenant_id,dedup_key) DO NOTHING
                    """,tenantId,runId,"drain-stop:"+drainId,drainId);
                appendEvents(tenantId,runId,epoch,List.of(new EventDraft(
                        "STOP_SUPERSEDES_PAUSE","DRAIN",drainId,null,null,null,"{}")));
                return drainId;
            }
            if(!openDrains.isEmpty()) {
                if("STOP".equals(requestedGate) && "STOP".equals(head.controlGate()))
                    return openDrains.get(0);
                if("PAUSE".equals(requestedGate) && "PAUSE".equals(head.controlGate()))
                    return openDrains.get(0);
            }
            if(!"OPEN".equals(head.controlGate()))
                throw new InvalidTransitionException("Run control gate does not admit this drain request");
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET control_gate=?,status=?,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND control_gate='OPEN'
                """,requestedGate,"PAUSE".equals(requestedGate)?"PAUSE_REQUESTED":"STOP_REQUESTED",tenantId,runId);
            UUID drainId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.drain_record
                  (tenant_id,drain_id,run_id,drain_kind,drain_state)
                VALUES (?,?,?,?,'REQUESTED')
                """,tenantId,drainId,runId,drainKind);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'DRAIN_REQUEST',?,jsonb_build_object('drainId',?::text,'kind',?))
                """,tenantId,runId,"drain:"+drainId,drainId,drainKind);
            appendEvents(tenantId,runId,epoch,List.of(new EventDraft("DRAIN_REQUESTED","DRAIN",
                    drainId,0L,null,null,"{}")));
            return drainId;
        });
    }

    /** T16: advance a bounded drain scan generation and persist its cursor. */
    public long advanceDrainScan(UUID tenantId, UUID runId, UUID drainId,
                                 long expectedVersion, String cursorJson, List<DrainItemRef> items) {
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
            if (items != null) for (DrainItemRef item : items) {
                jdbc.update("""
                    INSERT INTO workflow_runtime.drain_item
                      (tenant_id,drain_id,item_kind,item_id,item_state)
                    VALUES (?,?,?,?,'PENDING')
                    ON CONFLICT (tenant_id,drain_id,item_kind,item_id) DO NOTHING
                    """,tenantId,drainId,item.itemKind(),item.itemId());
            }
            return generation;
        });
    }

    /** T17: release a held reservation once; decrement capacity only for the CAS winner. */
    public boolean releaseReservation(UUID tenantId, UUID reservationId) {
        return required(() -> {
            String capacityKey=jdbc.queryForObject("""
                SELECT capacity_key FROM workflow_runtime.dispatch_reservation
                 WHERE tenant_id=? AND reservation_id=?
                """,String.class,tenantId,reservationId);
            jdbc.queryForObject("""
                SELECT capacity_key FROM workflow_runtime.scheduler_capacity
                 WHERE tenant_id=? AND capacity_key=? FOR UPDATE
                """,String.class,tenantId,capacityKey);
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
            if("LEASE".equals(itemKind)) {
                List<String> leaseRows=jdbc.query("""
                    SELECT resource_id FROM workflow_runtime.resource_lease
                     WHERE tenant_id=? AND run_id=? AND lease_id=? FOR UPDATE
                    """,(rs,n)->rs.getString(1),tenantId,runId,itemId);
                if(leaseRows.size()!=1) throw new IllegalStateException("Drain Lease item not found");
                String leaseState=safe?"RELEASED":"QUARANTINED";
                int leaseChanged=jdbc.update("""
                    UPDATE workflow_runtime.resource_lease SET lease_state=?,lease_version=lease_version+1,
                           released_at=CASE WHEN ? THEN clock_timestamp() ELSE released_at END,
                           evidence=?::jsonb
                     WHERE tenant_id=? AND lease_id=? AND lease_state IN ('ACTIVE','RELEASING','EXPIRED_UNVERIFIED','QUARANTINED')
                    """,leaseState,safe,evidenceJson,tenantId,itemId);
                if(leaseChanged!=1) throw new StaleRuntimeWriteException("Lease state changed during physical verification");
                if(!safe) jdbc.update("""
                    INSERT INTO workflow_runtime.quarantine_record
                      (tenant_id,resource_id,run_id,lease_id,quarantine_state,reason_code,evidence)
                    VALUES (?,?,?,?,'ACTIVE','DRAIN_PHYSICAL_STATE_UNVERIFIED',?::jsonb)
                    ON CONFLICT (tenant_id,resource_id) WHERE quarantine_state IN ('ACTIVE','CLEAR_PENDING')
                    DO NOTHING
                    """,tenantId,leaseRows.get(0),runId,itemId,evidenceJson);
            }
            return null;
        });
    }

    /** T19: terminalize only when every unsafe side effect and control-flow continuation is closed. */
    public boolean tryFinalizeDrain(UUID tenantId, UUID runId, UUID drainId, String terminalStatus) {
        return required(() -> {
            if(!List.of("PAUSED","STOPPED","FAILED").contains(terminalStatus))
                throw new IllegalArgumentException("T19 terminalStatus must be PAUSED, STOPPED or FAILED");
            RunHead head=lockRun(tenantId,runId);
            List<String> drainMeta=jdbc.query("""
                SELECT drain_kind,drain_state FROM workflow_runtime.drain_record
                 WHERE tenant_id=? AND run_id=? AND drain_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1)+"|"+rs.getString(2),tenantId,runId,drainId);
            if(drainMeta.size()!=1) return false;
            String[] meta=drainMeta.get(0).split("\\|",2);
            String drainKind=meta[0], drainState=meta[1];
            if(!List.of("REQUESTED","DRAINING","SAFE_POINT").contains(drainState)) return false;
            Long unsafe=jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM workflow_runtime.command_ledger
                    WHERE tenant_id=? AND run_id=? AND command_state NOT IN
                      ('SUCCEEDED','FAILED','CANCELLED')) +
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
                  (SELECT count(*) FROM workflow_runtime.branch_activation
                    WHERE tenant_id=? AND run_id=? AND branch_state IN ('ACTIVE','WAITING')) +
                  (SELECT count(*) FROM workflow_runtime.loop_context
                    WHERE tenant_id=? AND run_id=? AND loop_state='ACTIVE') +
                  (SELECT count(*) FROM workflow_runtime.execution_token
                    WHERE tenant_id=? AND run_id=? AND
                      (token_state IN ('RESERVED','RUNNING','WAITING') OR (? <> 'PAUSE' AND token_state='READY'))) +
                  (SELECT count(*) FROM workflow_runtime.node_attempt
                    WHERE tenant_id=? AND run_id=? AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN')) +
                  (SELECT count(*) FROM workflow_runtime.drain_item
                    WHERE tenant_id=? AND drain_id=? AND item_state <> 'SAFE') +
                  (SELECT count(*) FROM workflow_runtime.manual_intervention
                    WHERE tenant_id=? AND run_id=? AND intervention_state='OPEN') +
                  (SELECT count(*) FROM workflow_runtime.dispatch_reservation
                    WHERE tenant_id=? AND run_id=? AND reservation_state='HELD') +
                  (SELECT count(*) FROM workflow_runtime.runtime_outbox
                    WHERE tenant_id=? AND run_id=? AND outbox_state IN ('PENDING','CLAIMED'))
                """,Long.class,
                    tenantId,runId,  // Commands
                    tenantId,runId,  // Leases
                    tenantId,runId,  // Waits
                    tenantId,runId,  // Retries
                    tenantId,runId,  // Splits
                    tenantId,runId,  // Joins
                    tenantId,runId,  // Branches
                    tenantId,runId,  // Loops
                    tenantId,runId,drainKind, // Tokens (Pause preserves READY; Stop invalidates)
                    tenantId,runId,  // Attempts
                    tenantId,drainId, // Drain items
                    tenantId,runId,   // Open manual interventions
                    tenantId,runId,   // Held reservations
                    tenantId,runId    // Undelivered Outbox
                );
            if(unsafe==null||unsafe>0) return false;
            int changed=jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET status=?,
                       control_gate=CASE WHEN ?='PAUSED' THEN 'PAUSE' ELSE 'CLOSED' END,
                       terminal_reason='DRAIN_COMPLETED',finished_at=CASE WHEN ?='PAUSED' THEN NULL ELSE clock_timestamp() END,
                       commit_version=commit_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND status IN
                   ('PAUSING','STOPPING','PAUSE_REQUESTED','STOP_REQUESTED','RUNNING')
                """,terminalStatus,terminalStatus,terminalStatus,tenantId,runId);
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

    public void markDrainManualRequired(UUID tenantId, UUID runId, UUID drainId,
                                        String reason, String evidenceJson) {
        required(() -> {
            RunHead head=lockRun(tenantId,runId);
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET status='MANUAL_REQUIRED',
                       control_gate='STOP',terminal_reason=?,commit_version=commit_version+1,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND status NOT IN
                   ('SUCCEEDED','FAILED','STOPPED','MANUAL_REQUIRED','QUARANTINED')
                """,reason,tenantId,runId);
            jdbc.update("""
                UPDATE workflow_runtime.drain_record SET drain_state='MANUAL_REQUIRED',
                       version=version+1 WHERE tenant_id=? AND drain_id=?
                """,tenantId,drainId);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (? ,?,'SYSTEM','runtime','DRAIN_MANUAL_REQUIRED','DRAIN',?,?::jsonb,?)
                """,tenantId,runId,drainId,evidenceJson,reason);
            appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                    "DRAIN_MANUAL_REQUIRED","DRAIN",drainId,null,null,null,evidenceJson)));
            return null;
        });
    }

    /** T20: all checkpoint projections are read in one REPEATABLE READ snapshot. */
    public Checkpoint createCheckpoint(UUID tenantId, UUID runId, String checkpointKind) {
        Checkpoint draft = snapshotTx.execute(status -> {
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
            String splits=readJsonArray("split_activation",tenantId,runId);
            String branches=readJsonArray("branch_activation",tenantId,runId);
            String joins=readJsonArray("join_activation",tenantId,runId);
            String loops=readJsonArray("loop_context",tenantId,runId);
            String variables=readJsonArray("flow_variable",tenantId,runId);
            String outputs=readJsonArray("branch_output",tenantId,runId);
            String reservations=readJsonArray("dispatch_reservation",tenantId,runId);
            String drains=readJsonArray("drain_record",tenantId,runId);
            String interventions=readJsonArray("manual_intervention",tenantId,runId);
            String quarantines=jdbc.queryForObject("""
                SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY to_jsonb(q)::text),'[]'::jsonb)::text
                  FROM workflow_runtime.quarantine_record q
                 WHERE tenant_id=? AND run_id=?
                """,String.class,tenantId,runId);
            String snapshot="{\"run\":"+runJson+",\"tokens\":"+tokens+",\"activations\":"+activations+
                    ",\"attempts\":"+attempts+",\"waits\":"+waits+",\"retries\":"+retries+
                    ",\"commands\":"+commands+",\"leases\":"+leases+",\"splits\":"+splits+
                    ",\"branches\":"+branches+",\"joins\":"+joins+",\"loops\":"+loops+
                    ",\"variables\":"+variables+",\"outputs\":"+outputs+",\"reservations\":"+reservations+
                    ",\"drains\":"+drains+",\"interventions\":"+interventions+
                    ",\"quarantines\":"+quarantines+"}";
            byte[] hash=sha256(snapshot);
            UUID checkpointId=UUID.randomUUID();
            return new Checkpoint(checkpointId,head.commitVersion(),head.eventSeq(),
                    head.coordinatorEpoch(),snapshot,hash);
        });
        if (draft == null) throw new IllegalStateException("Checkpoint snapshot was not produced");
        UUID persistedId=tx.execute(status -> {
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_checkpoint
                  (tenant_id,checkpoint_id,run_id,checkpoint_kind,run_commit_version,event_seq,
                   coordinator_epoch,snapshot_payload,snapshot_hash)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (tenant_id,run_id,run_commit_version) DO NOTHING
                """,tenantId,draft.checkpointId(),runId,checkpointKind,draft.commitVersion(),
                    draft.eventSeq(),draft.coordinatorEpoch(),draft.snapshotJson(),draft.sha256());
            UUID id=jdbc.queryForObject("""
                SELECT checkpoint_id FROM workflow_runtime.runtime_checkpoint
                 WHERE tenant_id=? AND run_id=? AND run_commit_version=?
                """,UUID.class,tenantId,runId,draft.commitVersion());
            byte[] storedHash=jdbc.queryForObject("""
                SELECT snapshot_hash FROM workflow_runtime.runtime_checkpoint
                 WHERE tenant_id=? AND checkpoint_id=?
                """,byte[].class,tenantId,id);
            if(!MessageDigest.isEqual(storedHash,draft.sha256()))
                throw new IllegalStateException("Checkpoint version already exists with different snapshot hash");
            return id;
        });
        return new Checkpoint(persistedId,draft.commitVersion(),draft.eventSeq(),
                draft.coordinatorEpoch(),draft.snapshotJson(),draft.sha256());
    }

    private String readJsonArray(String table, UUID tenantId, UUID runId) {
        // Table names are fixed internal constants, never user input.
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb)::text FROM workflow_runtime."
                + table + " x WHERE tenant_id=? AND run_id=?",String.class,tenantId,runId);
    }

    /** T21: mark an ambiguous physical command UNKNOWN; a DB timeout is not proof of no effect. */
    public void markCommandUnknown(UUID tenantId, UUID commandId, String evidenceJson) {
        required(() -> {
            List<String> identity=jdbc.query("""
                SELECT run_id::text||'|'||attempt_id::text FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND command_id=?
                """,(rs,n)->rs.getString(1),tenantId,commandId);
            if(identity.size()!=1) throw new IllegalStateException("Command not found");
            String[] ids=identity.get(0).split("\\|");
            UUID runId=UUID.fromString(ids[0]), attemptId=UUID.fromString(ids[1]);
            RunHead head=lockRun(tenantId,runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND attempt_id=?
                """,UUID.class,tenantId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            List<String> lease=jdbc.query("""
                SELECT dispatch_id::text||'|'||dispatch_epoch||'|'||lease_epoch
                  FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,attemptId);
            if(lease.size()!=1) throw new IllegalStateException("Worker lease missing for Command");
            String[] leaseParts=lease.get(0).split("\\|");
            jdbc.queryForObject("SELECT command_id FROM workflow_runtime.command_ledger WHERE tenant_id=? AND command_id=? FOR UPDATE",
                    UUID.class,tenantId,commandId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='UNKNOWN',
                       version=version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND command_id=? AND command_state IN
                   ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN')
                """,tenantId,commandId);
            if(changed==1) {
                appendWorkerFactInTransaction(tenantId,runId,attemptId,"command-unknown:"+commandId,
                        "SIDE_EFFECT_UNKNOWN","RECOVERY","command-timeout",UUID.fromString(leaseParts[0]),
                        Long.parseLong(leaseParts[1]),Long.parseLong(leaseParts[2]),evidenceJson);
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_audit
                      (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                    VALUES (? ,?,'SYSTEM','runtime','COMMAND_MARKED_UNKNOWN','COMMAND',?,?::jsonb,'ambiguous external side effect')
                    """,tenantId,runId,commandId,evidenceJson);
                appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                        "COMMAND_MARKED_UNKNOWN","COMMAND",commandId,null,null,null,evidenceJson)));
            }
            return null;
        });
    }

    public record WorkerRecoveryResult(String outcome, UUID interventionId) {}
    public record ExpiredWorkerAttempt(UUID tenantId, UUID runId, UUID attemptId) {}

    /** Candidate scan only; each candidate is revalidated and row-locked by recovery. */
    public List<ExpiredWorkerAttempt> findExpiredWorkerAttempts(int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Recovery batch limit must be 1..1000");
        return jdbc.query("""
            SELECT tenant_id,run_id,attempt_id FROM workflow_runtime.worker_execution_lease
             WHERE lease_state='ACTIVE' AND expires_at<=clock_timestamp()
             ORDER BY expires_at,tenant_id,attempt_id LIMIT ?
            """,(rs,n)->new ExpiredWorkerAttempt(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)),limit);
    }

    /** Persist a Worker fact before the corresponding external side effect. */
    public boolean recordWorkerFact(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                                    long dispatchEpoch, long leaseEpoch, String workerId,
                                    String factKey, String factType, String payloadJson) {
        if(!List.of("SIDE_EFFECT_STARTED","SIDE_EFFECT_COMPLETED","SIDE_EFFECT_UNKNOWN").contains(factType))
            throw new IllegalArgumentException("Unsupported externally submitted Worker fact");
        return required(() -> {
            lockRun(tenantId,runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,UUID.class,tenantId,runId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            List<Long> leases=jdbc.query("""
                SELECT lease_epoch FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND attempt_id=? AND worker_id=? AND dispatch_id=?
                   AND dispatch_epoch=? AND lease_epoch=? AND lease_state='ACTIVE'
                   AND expires_at>clock_timestamp() FOR UPDATE
                """,(rs,n)->rs.getLong(1),tenantId,attemptId,workerId,dispatchId,dispatchEpoch,leaseEpoch);
            if(leases.size()!=1) throw new StaleRuntimeWriteException("Worker execution lease is expired or fenced");
            if(!"SIDE_EFFECT_STARTED".equals(factType)) {
                Integer started=jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.worker_execution_fact
                     WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_STARTED'
                    """,Integer.class,tenantId,attemptId);
                if(started==null||started==0)
                    throw new InvalidTransitionException("Side-effect completion/unknown requires a prior SIDE_EFFECT_STARTED fact");
            }
            return appendWorkerFactInTransaction(tenantId,runId,attemptId,factKey,factType,
                    "WORKER",workerId,dispatchId,dispatchEpoch,leaseEpoch,payloadJson)>0;
        });
    }

    /** Lease renewal is a CAS and cannot resurrect an expired execution right. */
    public boolean renewWorkerExecutionLease(UUID tenantId, UUID runId, UUID attemptId,
                                             String workerId, long leaseEpoch, long expectedVersion,
                                             Instant newExpiresAt) {
        if(newExpiresAt==null || !newExpiresAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("New Worker lease expiry must be in the future");
        return required(() -> {
            lockRun(tenantId,runId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.worker_execution_lease
                   SET expires_at=?,heartbeat_at=clock_timestamp(),lease_version=lease_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND worker_id=?
                   AND lease_epoch=? AND lease_version=? AND lease_state='ACTIVE'
                   AND expires_at>clock_timestamp()
                """,Timestamp.from(newExpiresAt),tenantId,runId,attemptId,workerId,leaseEpoch,expectedVersion);
            return changed==1;
        });
    }

    /**
     * Recover an expired Worker. No SIDE_EFFECT_STARTED fact and no ambiguous
     * Command means the external-call protocol proves no side effect was issued.
     * Otherwise freeze the Run and require explicit physical reconciliation.
     */
    public WorkerRecoveryResult recoverExpiredWorkerAttempt(UUID tenantId, UUID runId,
                                                              UUID attemptId, String recoveryActor) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,UUID.class,tenantId,runId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            List<String> attemptRows=jdbc.query("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,runId,attemptId);
            if(attemptRows.size()!=1 || !"STARTED".equals(attemptRows.get(0)))
                return new WorkerRecoveryResult("NOT_RECOVERABLE",null);
            List<String> leaseRows=jdbc.query("""
                SELECT worker_id,dispatch_id,dispatch_epoch,lease_epoch FROM workflow_runtime.worker_execution_lease
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND lease_state='ACTIVE'
                   AND expires_at<=clock_timestamp() FOR UPDATE
                """,(rs,n)->rs.getString(1)+"|"+rs.getObject(2)+"|"+rs.getLong(3)+"|"+rs.getLong(4),
                    tenantId,runId,attemptId);
            if(leaseRows.size()!=1) return new WorkerRecoveryResult("LEASE_NOT_EXPIRED",null);
            String[] lease=leaseRows.get(0).split("\\|");
            String workerId=lease[0]; UUID dispatchId=UUID.fromString(lease[1]);
            long dispatchEpoch=Long.parseLong(lease[2]), leaseEpoch=Long.parseLong(lease[3]);
            Integer sideEffectFacts=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_type IN
                   ('SIDE_EFFECT_STARTED','SIDE_EFFECT_COMPLETED','SIDE_EFFECT_UNKNOWN')
                """,Integer.class,tenantId,attemptId);
            Integer ambiguousCommands=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN
                   ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
                """,Integer.class,tenantId,attemptId);
            if((sideEffectFacts==null||sideEffectFacts==0) &&
               (ambiguousCommands==null||ambiguousCommands==0)) {
                jdbc.update("""
                    UPDATE workflow_runtime.command_ledger SET command_state='CANCELLED',
                           version=version+1,completed_at=clock_timestamp(),updated_at=clock_timestamp()
                     WHERE tenant_id=? AND attempt_id=? AND command_state IN ('CREATED','DISPATCH_PENDING')
                    """,tenantId,attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER',
                           claim_until=NULL
                     WHERE tenant_id=? AND outbox_state IN ('PENDING','CLAIMED')
                       AND payload->>'commandId' IN
                         (SELECT command_id::text FROM workflow_runtime.command_ledger WHERE tenant_id=? AND attempt_id=?)
                    """,tenantId,tenantId,attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.node_attempt SET attempt_state='ABANDONED_SAFE',
                           attempt_version=attempt_version+1,finished_at=clock_timestamp()
                     WHERE tenant_id=? AND attempt_id=? AND attempt_state='STARTED'
                    """,tenantId,attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.node_activation SET activation_state='ABANDONED_SAFE',
                           activation_version=activation_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND activation_id=? AND activation_state='RUNNING'
                    """,tenantId,activationId);
                jdbc.update("""
                    UPDATE workflow_runtime.execution_token SET token_state='READY',
                           token_version=token_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND token_id=? AND token_state='RUNNING'
                    """,tenantId,tokenId);
                appendWorkerFactInTransaction(tenantId,runId,attemptId,"execution-right-released","EXECUTION_RIGHT_RELEASED",
                        "RECOVERY",recoveryActor,dispatchId,dispatchEpoch,leaseEpoch,
                        "{\"reason\":\"expired-before-side-effect\"}");
                jdbc.update("""
                    UPDATE workflow_runtime.worker_execution_lease SET lease_state='EXPIRED_SAFE',
                           lease_version=lease_version+1,ended_at=clock_timestamp()
                     WHERE tenant_id=? AND attempt_id=? AND lease_state='ACTIVE'
                    """,tenantId,attemptId);
                releaseReservationInTransaction(tenantId,attemptId);
                appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                        "WORKER_LEASE_EXPIRED_SAFE","ATTEMPT",attemptId,null,null,null,"{}")));
                return new WorkerRecoveryResult("SAFE_TO_RETRY",null);
            }
            jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='UNKNOWN',
                       version=version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN
                   ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN')
                """,tenantId,attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.command_dispatch_attempt SET dispatch_state='DELIVERY_UNKNOWN',
                       finished_at=clock_timestamp()
                 WHERE tenant_id=? AND command_id IN
                   (SELECT command_id FROM workflow_runtime.command_ledger WHERE tenant_id=? AND attempt_id=?)
                   AND dispatch_state='SEND_STARTED'
                """,tenantId,tenantId,attemptId);
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"recovery-side-effect-unknown","SIDE_EFFECT_UNKNOWN",
                    "RECOVERY",recoveryActor,dispatchId,dispatchEpoch,leaseEpoch,
                    "{\"reason\":\"expired-after-side-effect-boundary\"}");
            jdbc.update("""
                UPDATE workflow_runtime.worker_execution_lease SET lease_state='EXPIRED_UNVERIFIED',
                       lease_version=lease_version+1,ended_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND lease_state='ACTIVE'
                """,tenantId,attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='UNKNOWN',
                       attempt_version=attempt_version+1
                 WHERE tenant_id=? AND attempt_id=? AND attempt_state='STARTED'
                """,tenantId,attemptId);
            UUID interventionId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.manual_intervention
                  (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,
                   reason_code,evidence)
                VALUES (?, ?, ?, 'ATTEMPT', ?, 'OPEN','WORKER_LEASE_EXPIRED_WITH_SIDE_EFFECT',
                        jsonb_build_object('workerId',?,'dispatchId',?,'dispatchEpoch',?,'leaseEpoch',?))
                """,tenantId,interventionId,runId,attemptId,workerId,dispatchId.toString(),dispatchEpoch,leaseEpoch);
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET status='MANUAL_REQUIRED',
                       control_gate='CLOSED',recovery_state='BLOCKED',
                       terminal_reason='Worker lease expired after external side-effect boundary',
                       updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=?
                """,tenantId,runId);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,?,'SYSTEM',?,'WORKER_RECOVERY_BLOCKED','ATTEMPT',?,?::jsonb,
                        'expired Worker may have performed an external side effect')
                """,tenantId,runId,recoveryActor,attemptId,
                    "{\"leaseEpoch\":"+leaseEpoch+",\"dispatchEpoch\":"+dispatchEpoch+"}");
            appendEvents(tenantId,runId,head.coordinatorEpoch(),List.of(new EventDraft(
                    "WORKER_LEASE_EXPIRED_UNKNOWN","ATTEMPT",attemptId,null,null,null,
                    "{\"interventionId\":\""+interventionId+"\"}")));
            return new WorkerRecoveryResult("MANUAL_REQUIRED",interventionId);
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
                RunHead decidedHead=lockRun(tenantId,runId);
                appendEvents(tenantId,runId,decidedHead.coordinatorEpoch(),List.of(new EventDraft("INTERVENTION_DECIDED",
                        "INTERVENTION",interventionId,expectedVersion+1,null,null,"{}")));
            }
            return changed==1;
        });
    }

    /** T23: clear quarantine only with explicit evidence; associated Lease must be RELEASED. */
    public boolean clearQuarantine(UUID tenantId, UUID quarantineId, String actor, String evidenceJson) {
        return required(() -> {
            if(evidenceJson==null||evidenceJson.isBlank()||"{}".equals(evidenceJson.trim()))
                throw new IllegalArgumentException("Quarantine clearance requires non-empty evidence");
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
