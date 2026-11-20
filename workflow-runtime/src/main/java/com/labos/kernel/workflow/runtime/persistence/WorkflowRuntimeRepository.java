package com.labos.kernel.workflow.runtime.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
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

    public WorkflowRuntimeRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
    }

    public record RunHead(String status, String controlGate, long commitVersion,
                          long eventSeq, String recoveryState) {}
    public record CreatedRun(UUID runId, boolean replayed) {}
    public record InboxReceipt(UUID inboxId, boolean inserted, boolean payloadConflict) {}
    public record EventDraft(String type, String aggregateType, UUID aggregateId,
                             Long aggregateVersion, UUID causationId, UUID correlationId,
                             String payloadJson) {}
    public record Checkpoint(UUID checkpointId, long commitVersion, long eventSeq,
                             String snapshotJson, byte[] sha256) {}
    public record CapacityReservation(UUID attemptId, UUID dispatchId, UUID reservationId) {}
    public record DrainItemRef(String itemKind, UUID itemId) {}

    private static final RowMapper<RunHead> RUN_HEAD = (rs, n) -> new RunHead(
            rs.getString("status"), rs.getString("control_gate"),
            rs.getLong("commit_version"), rs.getLong("event_seq"), rs.getString("recovery_state"));

    private <T> T required(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private RunHead lockRun(UUID tenantId, UUID runId) {
        List<RunHead> rows = jdbc.query("""
            SELECT status, control_gate, commit_version, event_seq, recovery_state
              FROM workflow_runtime.workflow_run
             WHERE tenant_id=? AND run_id=?
             FOR UPDATE
            """, RUN_HEAD, tenantId, runId);
        if (rows.size() != 1) throw new IllegalStateException("Run not found or tenant mismatch");
        return rows.get(0);
    }

    private long appendWorkerFactInTransaction(UUID tenantId, UUID runId, UUID attemptId,
                                                String factKey, String factType, String actorKind,
                                                String actorId, UUID dispatchId, String payloadJson) {
        return appendWorkerFactInTransaction(tenantId, runId, attemptId, factKey, null,
                factType, actorKind, actorId, dispatchId, payloadJson);
    }

    private long appendWorkerFactInTransaction(UUID tenantId, UUID runId, UUID attemptId,
                                                String factKey, String sideEffectKey, String factType,
                                                String actorKind, String actorId, UUID dispatchId,
                                                String payloadJson) {
        String normalizedPayload = payloadJson == null ? "{}" : payloadJson;
        jdbc.queryForObject("""
            SELECT attempt_id FROM workflow_runtime.node_attempt
             WHERE tenant_id=? AND attempt_id=? FOR UPDATE
            """,UUID.class,tenantId,attemptId);
        List<Long> existing=jdbc.query("""
            SELECT fact_seq FROM workflow_runtime.worker_execution_fact
             WHERE tenant_id=? AND attempt_id=? AND fact_key=?
            """,(rs,n)->rs.getLong(1),tenantId,attemptId,factKey);
        if(!existing.isEmpty()) {
            Boolean same=jdbc.queryForObject("""
                SELECT fact_type=? AND actor_kind=? AND actor_id=?
                   AND dispatch_id IS NOT DISTINCT FROM ?::uuid
                   AND side_effect_key IS NOT DISTINCT FROM ?
                   AND payload=?::jsonb
                  FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_key=?
                """,Boolean.class,factType,actorKind,actorId,dispatchId==null?null:dispatchId.toString(),
                    sideEffectKey,normalizedPayload,tenantId,attemptId,factKey);
            if(!Boolean.TRUE.equals(same))
                throw new IdempotencyConflictException("Worker fact key replay differs from original fact");
            return existing.get(0);
        }
        Long seq=jdbc.queryForObject("""
            SELECT COALESCE(MAX(fact_seq),0)+1
              FROM workflow_runtime.worker_execution_fact
             WHERE tenant_id=? AND attempt_id=?
            """,Long.class,tenantId,attemptId);
        if(seq==null) throw new IllegalStateException("Unable to allocate Worker fact sequence");
        jdbc.update("""
            INSERT INTO workflow_runtime.worker_execution_fact
              (tenant_id,run_id,attempt_id,fact_seq,fact_key,side_effect_key,fact_type,actor_kind,actor_id,
               dispatch_id,payload)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
            """,tenantId,runId,attemptId,seq,factKey,sideEffectKey,factType,actorKind,actorId,
                dispatchId,normalizedPayload);
        return seq;
    }

    /**
     * Single-source-of-truth SQL predicate for alias {@code a} ({@code workflow_runtime.node_attempt}):
     * true when the Attempt has any {@code SIDE_EFFECT_UNKNOWN} without a later matching
     * {@code SIDE_EFFECT_COMPLETED}, or any {@code SIDE_EFFECT_STARTED} without a later matching
     * {@code SIDE_EFFECT_COMPLETED}.
     *
     * <p>By design, Attempt-level {@code SIDE_EFFECT_UNKNOWN} rows with {@code side_effect_key IS NULL}
     * (written when {@code failAttempt}, {@code cancelAttempt}, or crash recovery marks an Attempt
     * {@code UNKNOWN} and escalates the Run to {@code MANUAL_REQUIRED}) are permanently unresolved
     * fail-closed markers: that Attempt cannot later be auto-completed and requires manual reconciliation
     * or a new Attempt.
     */
    private static final String UNRESOLVED_SIDE_EFFECT_PREDICATE_FOR_ALIAS_A = """
        EXISTS (
          SELECT 1 FROM workflow_runtime.worker_execution_fact u
           WHERE u.tenant_id=a.tenant_id AND u.attempt_id=a.attempt_id
             AND u.fact_type='SIDE_EFFECT_UNKNOWN'
             AND (
               u.side_effect_key IS NULL
               OR NOT EXISTS (
                 SELECT 1 FROM workflow_runtime.worker_execution_fact c
                  WHERE c.tenant_id=u.tenant_id AND c.attempt_id=u.attempt_id
                    AND c.fact_type='SIDE_EFFECT_COMPLETED'
                    AND c.side_effect_key=u.side_effect_key
                    AND c.fact_seq > u.fact_seq
               )
             )
        )
        OR EXISTS (
          SELECT 1 FROM workflow_runtime.worker_execution_fact s
           WHERE s.tenant_id=a.tenant_id AND s.attempt_id=a.attempt_id
             AND s.fact_type='SIDE_EFFECT_STARTED'
             AND NOT EXISTS (
               SELECT 1 FROM workflow_runtime.worker_execution_fact c
                WHERE c.tenant_id=s.tenant_id AND c.attempt_id=s.attempt_id
                  AND c.fact_type='SIDE_EFFECT_COMPLETED'
                  AND c.side_effect_key IS NOT NULL
                  AND c.side_effect_key=s.side_effect_key
                  AND c.fact_seq > s.fact_seq
             )
        )
        """;

    /**
     * Exact identity-paired check sharing {@link #UNRESOLVED_SIDE_EFFECT_PREDICATE_FOR_ALIAS_A}.
     */
    private boolean hasUnresolvedSideEffectForAttempt(UUID tenantId, UUID attemptId) {
        Boolean unresolved = jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM workflow_runtime.node_attempt a
               WHERE a.tenant_id=? AND a.attempt_id=?
                 AND (
            """ + UNRESOLVED_SIDE_EFFECT_PREDICATE_FOR_ALIAS_A + """
                 )
            )
            """, Boolean.class, tenantId, attemptId);
        return Boolean.TRUE.equals(unresolved);
    }

    /**
     * True when this Attempt already has at least one durable {@code SIDE_EFFECT_COMPLETED} fact
     * or a {@code SUCCEEDED} row in {@code command_ledger}. Used by application-layer cancellation
     * settlement so completed DEVICE commands also fail closed to {@code MANUAL_REQUIRED} even if
     * the handler itself never wrote a Worker-scoped side-effect fact.
     */
    public boolean hasCompletedSideEffectForAttempt(UUID tenantId, UUID attemptId) {
        Boolean completed = jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM workflow_runtime.worker_execution_fact
               WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_COMPLETED'
            ) OR EXISTS (
              SELECT 1 FROM workflow_runtime.command_ledger
               WHERE tenant_id=? AND attempt_id=? AND command_state='SUCCEEDED'
            )
            """, Boolean.class, tenantId, attemptId, tenantId, attemptId);
        return Boolean.TRUE.equals(completed);
    }

    private long appendEvents(UUID tenantId, UUID runId, List<EventDraft> events) {
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
                   aggregate_version,causation_id,correlation_id,payload)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)
                """, tenantId, runId, first + i, e.type(), e.aggregateType(), e.aggregateId(),
                    e.aggregateVersion(), e.causationId(), e.correlationId(),
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

    private UUID commandDispatchId(UUID tenantId, UUID attemptId) {
        return jdbc.queryForObject("SELECT dispatch_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?",
                UUID.class,tenantId,attemptId);
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

    /**
     * Resolve a stable application tenant key to its Runtime UUID.
     * Callers must use a durable key (e.g. kernel-tenant:<legacy bigint>), never a random per-start key.
     */
    public UUID resolveTenantId(String tenantKey, String displayName) {
        if(tenantKey==null || tenantKey.isBlank()) throw new IllegalArgumentException("tenantKey is required");
        if(displayName==null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        return required(() -> jdbc.queryForObject("""
            INSERT INTO workflow_runtime.tenant(tenant_key,display_name)
            VALUES (?,?)
            ON CONFLICT (tenant_key) DO UPDATE SET display_name=EXCLUDED.display_name
            RETURNING tenant_id
            """, UUID.class, tenantKey, displayName));
    }

    /** Reverse lookup used by a Kernel DefinitionSource adapter; no bigint/UUID casting is allowed. */
    public String findTenantKey(UUID runtimeTenantId) {
        List<String> keys=jdbc.query("""
            SELECT tenant_key FROM workflow_runtime.tenant WHERE tenant_id=?
            """,(rs,n)->rs.getString(1),runtimeTenantId);
        if(keys.size()!=1) throw new IllegalStateException("Runtime tenant not found");
        return keys.get(0);
    }

    public record RunDefinitionSnapshot(UUID tenantId, UUID runId, String runStatus,
                                         String controlGate, String compilerVersion,
                                         String graphPlanJson) {}

    /** Read the immutable definition snapshot referenced by one Run. */
    public RunDefinitionSnapshot loadRunDefinitionSnapshot(UUID tenantId, UUID runId) {
        List<RunDefinitionSnapshot> rows = snapshotTx.execute(status -> jdbc.query("""
            SELECT r.tenant_id, r.run_id, r.status, r.control_gate,
                   d.compiler_version, d.graph_plan::text AS graph_plan_json
              FROM workflow_runtime.workflow_run r
              JOIN workflow_runtime.definition_snapshot d
                ON d.tenant_id=r.tenant_id AND d.definition_id=r.definition_id
             WHERE r.tenant_id=? AND r.run_id=?
            """, (rs,n) -> new RunDefinitionSnapshot(rs.getObject("tenant_id",UUID.class),
                    rs.getObject("run_id",UUID.class),rs.getString("status"),
                    rs.getString("control_gate"),rs.getString("compiler_version"),
                    rs.getString("graph_plan_json")), tenantId, runId));
        if(rows==null || rows.size()!=1) throw new IllegalStateException("Run definition snapshot not found or tenant mismatch");
        return rows.get(0);
    }

    /** Check the active mailbox admission state for one tenant-scoped Run. */
    public boolean isOpenRun(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Boolean active = snapshotTx.execute(status -> jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM workflow_runtime.workflow_run
                 WHERE tenant_id=? AND run_id=? AND status='RUNNING' AND control_gate='OPEN'
            )
            """, Boolean.class, tenantId, runId));
        return Boolean.TRUE.equals(active);
    }

    /** Enumerate non-terminal Runs for single-Host Coordinator restoration. */
    public List<RunDefinitionSnapshot> findNonTerminalRunSnapshots(int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("limit must be 1..1000");
        List<RunDefinitionSnapshot> rows = snapshotTx.execute(status -> jdbc.query("""
            SELECT r.tenant_id, r.run_id, r.status, r.control_gate,
                   d.compiler_version, d.graph_plan::text AS graph_plan_json
              FROM workflow_runtime.workflow_run r
              JOIN workflow_runtime.definition_snapshot d
                ON d.tenant_id=r.tenant_id AND d.definition_id=r.definition_id
             WHERE r.status NOT IN ('SUCCEEDED','FAILED','STOPPED')
             ORDER BY r.created_at, r.tenant_id, r.run_id
             LIMIT ?
            """, (rs,n) -> new RunDefinitionSnapshot(rs.getObject("tenant_id",UUID.class),
                    rs.getObject("run_id",UUID.class),rs.getString("status"),
                    rs.getString("control_gate"),rs.getString("compiler_version"),
                    rs.getString("graph_plan_json")), limit));
        return rows==null ? List.of() : List.copyOf(rows);
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
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "RUN_CREATED","RUN",runId,0L,null,null,
                    "{\"tokenId\":\""+tokenId+"\",\"nodeKey\":\""+jsonEscape(firstNodeKey)+"\"}")));
            return new CreatedRun(runId, false);
        });
    }

    /** T02: CREATED -> RUNNING. */
    public void startRun(UUID tenantId, UUID runId) {
        required(() -> {
            RunHead head=lockRun(tenantId,runId);
            if(!"CREATED".equals(head.status())) throw new InvalidTransitionException("T02 requires CREATED");
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run
                   SET status='RUNNING',started_at=clock_timestamp(),updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND status='CREATED'
                """,tenantId,runId);
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "RUN_STARTED","RUN",runId,head.commitVersion()+1,null,null,"{}")));
            return null;
        });
    }

    /**
     * Idempotent T02 start for lifecycle wakeup callers.
     * Transitions CREATED -> RUNNING once (returning true), returns false when the Run
     * is already RUNNING with OPEN control gate, and rejects all other states.
     */
    public boolean startRunIfCreated(UUID tenantId, UUID runId) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            if("CREATED".equals(head.status())) {
                int changed=jdbc.update("""
                    UPDATE workflow_runtime.workflow_run
                       SET status='RUNNING',started_at=clock_timestamp(),updated_at=clock_timestamp()
                     WHERE tenant_id=? AND run_id=? AND status='CREATED'
                    """,tenantId,runId);
                if(changed!=1) throw new StaleRuntimeWriteException("Run state changed concurrently");
                appendEvents(tenantId,runId,List.of(new EventDraft(
                        "RUN_STARTED","RUN",runId,head.commitVersion()+1,null,null,"{}")));
                return true;
            }
            if("RUNNING".equals(head.status()) && "OPEN".equals(head.controlGate())) {
                return false;
            }
            throw new InvalidTransitionException("Run cannot start from status=" + head.status()
                    + " controlGate=" + head.controlGate());
        });
    }

    /** T03-R: reserve global capacity with a single conditional UPDATE (no oversell). */
    public CapacityReservation reserveCapacity(UUID tenantId, UUID runId,
                                                String capacityKey, int capacityLimit,
                                                String nodeKey, String sideEffectClass,
                                                Instant expiresAt) {
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);

            if (!"RUNNING".equals(head.status()) || !"OPEN".equals(head.controlGate()))
                throw new InvalidTransitionException("Run gate does not admit dispatch");
            List<UUID> tokenIds = jdbc.query("""
                SELECT token_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND node_key=? AND token_state='READY'
                 ORDER BY created_at,token_id FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId, nodeKey);
            if (tokenIds.isEmpty()) throw new IllegalStateException("No READY token");
            UUID tokenId = tokenIds.get(0);
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
                  (tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,
                   attempt_state,side_effect_class)
                VALUES (?,?,?,?,1,?,'CREATED',?)
                """, tenantId, attemptId, runId, activationId, dispatchId, sideEffectClass);
            UUID reservationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.dispatch_reservation
                  (tenant_id,reservation_id,run_id,attempt_id,capacity_key,reservation_state,expires_at)
                VALUES (?,?,?, ?,?,'HELD',?)
                """, tenantId, reservationId, runId, attemptId, capacityKey, Timestamp.from(expiresAt));
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "DISPATCH_RESERVED","ATTEMPT",attemptId,0L,null,null,
                    "{\"activationId\":\""+activationId+"\",\"dispatchId\":\""+dispatchId+"\"}")));
            return new CapacityReservation(attemptId, dispatchId, reservationId);
        });
    }

    /** T03-D: make a reserved Attempt dispatchable and enqueue a durable Worker message. */
    public void dispatchAttempt(UUID tenantId, UUID runId, UUID attemptId,
                                String workerQueue, String payloadJson) {
        required(() -> {
            RunHead head=lockRun(tenantId,runId);
            requireRunOpenForExecutionStart(head,"dispatch");
            int changed=jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='DISPATCHED',
                       attempt_version=attempt_version+1
                 WHERE tenant_id=? AND attempt_id=? AND run_id=? AND attempt_state='CREATED'
                """,tenantId,attemptId,runId);
            if(changed!=1) throw new StaleRuntimeWriteException("Attempt is not CREATED");
            int activationReserved=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='RESERVED',
                       current_attempt_id=?,activation_version=activation_version+1
                 WHERE tenant_id=? AND run_id=? AND activation_id=(SELECT activation_id
                   FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?)
                   AND activation_state='RESERVED'
                """,attemptId,tenantId,runId,tenantId,attemptId);
            if(activationReserved!=1) throw new StaleRuntimeWriteException("Activation reservation lost");
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'WORKER_DISPATCH',?,
                        jsonb_set(jsonb_set(?::jsonb,'{queue}',to_jsonb(?::text),true),
                                  '{attemptId}',to_jsonb(?::text),true))
                """,tenantId,runId,"dispatch:"+attemptId,payloadJson,workerQueue,attemptId.toString());
            appendEvents(tenantId,runId,List.of(new EventDraft("ATTEMPT_DISPATCHED",
                    "ATTEMPT",attemptId,1L,null,null,"{}")));
            return null;
        });
    }

    /** T04-A: accept the exact locally issued dispatch identity. */
    public void acceptAttempt(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                              String workerId) {
        required(() -> {
            RunHead head=lockRun(tenantId,runId);
            requireRunOpenForExecutionStart(head,"worker acceptance");
            int changed=jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='ACCEPTED',
                       worker_id=?,attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND dispatch_id=?
                   AND attempt_state='DISPATCHED'
                """,workerId,tenantId,runId,attemptId,dispatchId);
            if(changed!=1) throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "ATTEMPT_ACCEPTED","ATTEMPT",attemptId,null,null,null,"{}")));
            return null;
        });
    }

    /**
     * T04-A (binding-verified): accept a locally dispatched Attempt only after verifying that
     * {@code expectedNodeKey} and {@code expectedSideEffectClass} match the persisted
     * {@code node_activation} and {@code node_attempt} rows under global lock order
     * {@code Run -> Token -> Activation -> Attempt}.
     */
    public void acceptAttemptForExecution(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                                          String workerId, String expectedNodeKey,
                                          String expectedSideEffectClass) {
        if (expectedNodeKey == null || expectedNodeKey.isBlank())
            throw new IllegalArgumentException("expectedNodeKey is required");
        if (expectedSideEffectClass == null || expectedSideEffectClass.isBlank())
            throw new IllegalArgumentException("expectedSideEffectClass is required");
        required(() -> {
            RunHead head = lockRun(tenantId, runId);
            requireRunOpenForExecutionStart(head, "worker acceptance");
            List<UUID> activationIds = jdbc.query("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId, attemptId);
            if (activationIds.size() != 1)
                throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            UUID activationId = activationIds.get(0);
            UUID tokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """, UUID.class, tenantId, activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class, tenantId, tokenId);
            String persistedNodeKey = jdbc.queryForObject("""
                SELECT node_key FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=? FOR UPDATE
                """, String.class, tenantId, activationId);
            List<String> attemptMeta = jdbc.query("""
                SELECT attempt_state || '|' || side_effect_class || '|' || COALESCE(dispatch_id::text,'')
                  FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), tenantId, runId, attemptId);
            if (attemptMeta.size() != 1)
                throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            String[] meta = attemptMeta.get(0).split("\\|", 3);
            if (!"DISPATCHED".equals(meta[0]) || !dispatchId.toString().equals(meta[2]))
                throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            if (!expectedNodeKey.equals(persistedNodeKey) || !expectedSideEffectClass.equals(meta[1]))
                throw new StaleRuntimeWriteException(
                        "TaskRequest binding mismatch (expected nodeKey=" + expectedNodeKey
                                + ", sideEffectClass=" + expectedSideEffectClass
                                + "; persisted nodeKey=" + persistedNodeKey
                                + ", sideEffectClass=" + meta[1] + ")");
            int changed = jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='ACCEPTED',
                       worker_id=?,attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND dispatch_id=?
                   AND attempt_state='DISPATCHED'
                """, workerId, tenantId, runId, attemptId, dispatchId);
            if (changed != 1) throw new StaleRuntimeWriteException("Worker acceptance does not match dispatch");
            appendEvents(tenantId, runId, List.of(new EventDraft(
                    "ATTEMPT_ACCEPTED", "ATTEMPT", attemptId, null, null, null, "{}")));
            return null;
        });
    }

    /** T04-S: ACCEPTED -> STARTED. Worker ownership is process-local, not a DB lease. */
    public void startAttempt(UUID tenantId, UUID runId, UUID attemptId, UUID dispatchId,
                             String workerId) {
        required(() -> {
            RunHead head=lockRun(tenantId,runId);
            requireRunOpenForExecutionStart(head,"worker start");
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
            int changed=jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='STARTED',
                       started_at=clock_timestamp(),attempt_version=attempt_version+1
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? AND dispatch_id=?
                   AND worker_id=? AND attempt_state='ACCEPTED'
                """,tenantId,runId,attemptId,dispatchId,workerId);
            if(changed!=1) throw new StaleRuntimeWriteException("Only accepted local Worker may start");
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
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"handler-start","HANDLER_STARTED",
                    "WORKER",workerId,dispatchId,"{}");
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "ATTEMPT_STARTED","ATTEMPT",attemptId,null,null,null,"{}")));
            return null;
        });
    }

    /** T05: commit Attempt output after its durable side-effect conditions are satisfied. */
    public void completeAttempt(UUID tenantId, UUID runId, UUID attemptId,
                                String outputKey, String outputJson) {
        completeAttempt(tenantId, runId, attemptId, outputKey, outputJson, Map.of());
    }

    /**
     * T05: atomically commit Attempt output to immutable branch_output and export
     * declared flow variables into flow_variable under the same Run lock.
     *
     * <p>New completions are admitted while the Run is RUNNING or draining to a safe point
     * (PAUSE_REQUESTED/PAUSING/STOP_REQUESTED/STOPPING) and rejected once the Run has
     * entered a terminal or manual-blocked state (FAILED/STOPPED/SUCCEEDED/MANUAL_REQUIRED/
     * QUARANTINED/PAUSED). Identical replays against an already-SUCCEEDED Attempt verify both
     * the committed result and the complete exported variable set. Sibling parallel branches
     * of the same Split may not overwrite each other's flow_variable keys.
     */
    public void completeAttempt(UUID tenantId, UUID runId, UUID attemptId,
                                String outputKey, String outputJson,
                                Map<String, String> exportedFlowVariables) {
        if (outputKey == null || outputKey.isBlank())
            throw new IllegalArgumentException("outputKey is required");
        TreeMap<String, String> sortedExports = new TreeMap<>();
        if (exportedFlowVariables != null) {
            for (Map.Entry<String, String> entry : exportedFlowVariables.entrySet()) {
                String k = entry.getKey(), v = entry.getValue();
                if (k == null || k.isBlank()) throw new IllegalArgumentException("Exported flow variable key is required");
                if (v == null || v.isBlank()) throw new IllegalArgumentException("Exported flow variable value JSON is required: " + k);
                sortedExports.put(k, v);
            }
        }
        String exportedManifestJson = canonicalJsonObject(sortedExports);
        required(() -> {
            RunHead head=lockRun(tenantId,runId);
            UUID activationId=jdbc.queryForObject("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,UUID.class,tenantId,runId,attemptId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            List<UUID[]> tokenLineageRows = jdbc.query("""
                SELECT split_id,branch_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND token_id=? FOR UPDATE
                """, (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                tenantId, tokenId);
            if (tokenLineageRows.size() != 1) throw new IllegalStateException("Token not found");
            UUID tokenSplitId = tokenLineageRows.get(0)[0];
            UUID tokenBranchId = tokenLineageRows.get(0)[1];
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            jdbc.queryForObject("SELECT attempt_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=? FOR UPDATE",
                    UUID.class,tenantId,attemptId);
            List<String> states=jdbc.query("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,runId,attemptId);
            if(states.size()!=1) throw new IllegalStateException("Attempt not found");
            if("SUCCEEDED".equals(states.get(0))) {
                Boolean identical = jdbc.queryForObject("""
                    SELECT a.result IS NOT NULL
                       AND a.result = ?::jsonb
                       AND a.exported_variables = ?::jsonb
                       AND EXISTS (
                           SELECT 1 FROM workflow_runtime.branch_output o
                            WHERE o.tenant_id = a.tenant_id
                              AND o.activation_id = a.activation_id
                              AND o.output_key = ?
                              AND o.value = ?::jsonb
                       )
                       AND (
                           SELECT count(*) FROM workflow_runtime.branch_output o
                            WHERE o.tenant_id = a.tenant_id
                              AND o.activation_id = a.activation_id
                       ) = 1
                      FROM workflow_runtime.node_attempt a
                     WHERE a.tenant_id=? AND a.attempt_id=?
                    """, Boolean.class, outputJson, exportedManifestJson, outputKey, outputJson, tenantId, attemptId);
                if (Boolean.TRUE.equals(identical)) return null;
                throw new IdempotencyConflictException(
                        "Attempt completion replay differs from committed outputKey, result, or exported variables");
            }
            requireRunAdmitsAttemptTermination(head, "complete");
            if (!"STARTED".equals(states.get(0))) {
                throw new StaleRuntimeWriteException("Attempt is not STARTED");
            }
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
            if (hasUnresolvedSideEffectForAttempt(tenantId, attemptId)) {
                throw new InvalidTransitionException(
                        "Cannot complete Attempt while unresolved or unknown side-effect facts remain");
            }
            int changed=jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='SUCCEEDED',
                       finished_at=clock_timestamp(),attempt_version=attempt_version+1,
                       result=?::jsonb,exported_variables=?::jsonb
                 WHERE tenant_id=? AND attempt_id=? AND run_id=? AND attempt_state='STARTED'
                """,outputJson,exportedManifestJson,tenantId,attemptId,runId);
            if(changed!=1) throw new StaleRuntimeWriteException("Attempt is not STARTED");
            jdbc.update("""
                INSERT INTO workflow_runtime.branch_output(tenant_id,run_id,activation_id,output_key,value)
                VALUES (?,?,?,?,?::jsonb)
                ON CONFLICT (tenant_id,activation_id,output_key) DO NOTHING
                """,tenantId,runId,activationId,outputKey,outputJson);
            List<EventDraft> events = new ArrayList<>();
            for (Map.Entry<String, String> entry : sortedExports.entrySet()) {
                if (tokenSplitId != null) {
                    List<UUID[]> existingWriters = jdbc.query("""
                        SELECT source_split_id,source_branch_id FROM workflow_runtime.flow_variable
                         WHERE tenant_id=? AND run_id=? AND variable_key=? FOR UPDATE
                        """, (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                        tenantId, runId, entry.getKey());
                    if (!existingWriters.isEmpty()) {
                        UUID existingSplit = existingWriters.get(0)[0];
                        UUID existingBranch = existingWriters.get(0)[1];
                        if (tokenSplitId.equals(existingSplit) && !Objects.equals(tokenBranchId, existingBranch)) {
                            throw new InvalidTransitionException(
                                    "Parallel branches of the same Split cannot write the same flow_variable key: "
                                            + entry.getKey());
                        }
                    }
                }
                Long version = jdbc.queryForObject("""
                    INSERT INTO workflow_runtime.flow_variable
                      (tenant_id,run_id,variable_key,value,value_version,
                       source_activation_id,source_split_id,source_branch_id)
                    VALUES (?,?,?,?::jsonb,1,?,?,?)
                    ON CONFLICT (tenant_id,run_id,variable_key) DO UPDATE
                       SET value=EXCLUDED.value,value_version=workflow_runtime.flow_variable.value_version+1,
                           source_activation_id=EXCLUDED.source_activation_id,
                           source_split_id=EXCLUDED.source_split_id,
                           source_branch_id=EXCLUDED.source_branch_id,
                           updated_at=clock_timestamp()
                    RETURNING value_version
                    """, Long.class, tenantId, runId, entry.getKey(), entry.getValue(),
                        activationId, tokenSplitId, tokenBranchId);
                events.add(new EventDraft("FLOW_VARIABLE_UPDATED", "RUN", runId,
                        head.commitVersion() + 1, null, null,
                        "{\"key\":\"" + jsonEscape(entry.getKey()) + "\",\"version\":" + version
                                + ",\"attemptId\":\"" + attemptId + "\"}"));
            }
            if(jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='SUCCEEDED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state='RUNNING'
                """,tenantId,activationId)!=1) throw new StaleRuntimeWriteException("Activation is not RUNNING");
            if(jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=(SELECT token_id FROM workflow_runtime.node_activation
                   WHERE tenant_id=? AND activation_id=?) AND token_state IN ('RUNNING','RESERVED','WAITING')
                """,tenantId,tenantId,activationId)!=1) throw new StaleRuntimeWriteException("Token is not active");
            releaseReservationInTransaction(tenantId,attemptId);
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"execution-completed","EXECUTION_RIGHT_RELEASED",
                    "WORKER","completion",commandDispatchId(tenantId,attemptId),"{\"reason\":\"attempt-succeeded\"}");
            events.add(new EventDraft("ATTEMPT_SUCCEEDED","ATTEMPT",attemptId,
                    null,null,null,"{\"activationId\":\""+activationId+"\",\"outputKey\":\""+jsonEscape(outputKey)+"\"}"));
            appendEvents(tenantId,runId,events);
            return null;
        });
    }

    private static String canonicalJsonObject(TreeMap<String, String> sortedJsonValues) {
        if (sortedJsonValues == null || sortedJsonValues.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : sortedJsonValues.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(jsonEscape(entry.getKey())).append("\":").append(entry.getValue());
        }
        return sb.append('}').toString();
    }

    private static void requireRunOpenForExecutionStart(RunHead head, String action) {
        if (!"RUNNING".equals(head.status()) || !"OPEN".equals(head.controlGate())) {
            throw new InvalidTransitionException("Run gate does not admit " + action
                    + " (status=" + head.status() + ", controlGate=" + head.controlGate() + ")");
        }
    }

    private static void requireRunAdmitsAttemptTermination(RunHead head, String action) {
        if (!List.of("RUNNING", "PAUSE_REQUESTED", "PAUSING", "STOP_REQUESTED", "STOPPING")
                .contains(head.status())) {
            throw new InvalidTransitionException(
                    "Cannot " + action + " Attempt when Run is in status=" + head.status());
        }
    }

    public record AttemptFailureResult(String outcome, UUID interventionId) {}

    /**
     * Record a Worker Attempt failure with strict separation between deterministic
     * no-side-effect failures (FAILED) and uncertain / device side-effect failures (UNKNOWN + MANUAL_REQUIRED).
     * Uncertain failures never release physical device leases and escalate the Run to MANUAL_REQUIRED.
     * Terminal or manually-blocked Runs reject further Attempt failure mutations.
     */
    public AttemptFailureResult failAttempt(UUID tenantId, UUID runId, UUID attemptId,
                                            String errorJson, boolean sideEffectUncertain) {
        String normalizedError = (errorJson == null || errorJson.isBlank()) ? "{}" : errorJson;
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            requireRunAdmitsAttemptTermination(head, "fail");
            List<UUID> activationIds = jdbc.query("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId, attemptId);
            if (activationIds.size() != 1) throw new IllegalStateException("Attempt not found");
            UUID activationId = activationIds.get(0);
            UUID tokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """, UUID.class, tenantId, activationId);
            // Global lock order: Run -> Token -> Activation -> Attempt.
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class, tenantId, tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class, tenantId, activationId);
            List<String> attemptRows = jdbc.query("""
                SELECT attempt_state || '|' || side_effect_class FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), tenantId, runId, attemptId);
            if (attemptRows.size() != 1) throw new IllegalStateException("Attempt not found");
            String[] meta = attemptRows.get(0).split("\\|", 2);
            String attemptState = meta[0], sideEffectClass = meta[1];
            if (!List.of("CREATED", "DISPATCHED", "ACCEPTED", "STARTED").contains(attemptState)) {
                throw new StaleRuntimeWriteException("Attempt is not active: " + attemptState);
            }

            Integer ambiguousCommands = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN
                   ('SEND_STARTED','ACKNOWLEDGED','RUNNING','CANCEL_REQUESTED',
                    'DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
                """, Integer.class, tenantId, attemptId);
            Integer succeededCommands = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=? AND command_state='SUCCEEDED'
                """, Integer.class, tenantId, attemptId);

            boolean unresolvedSideEffect = hasUnresolvedSideEffectForAttempt(tenantId, attemptId);
            boolean hasAmbiguousCommand = (ambiguousCommands != null && ambiguousCommands > 0);
            boolean deviceAlreadySucceeded = ("DEVICE".equals(sideEffectClass)
                    && succeededCommands != null && succeededCommands > 0);
            boolean mustEscalateManual = sideEffectUncertain || unresolvedSideEffect
                    || hasAmbiguousCommand || deviceAlreadySucceeded;

            if (mustEscalateManual) {
                jdbc.update("""
                    UPDATE workflow_runtime.command_ledger SET command_state='UNKNOWN',
                           version=version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND attempt_id=? AND command_state IN
                       ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN')
                    """, tenantId, attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.node_attempt SET attempt_state='UNKNOWN',
                           error=?::jsonb,attempt_version=attempt_version+1
                     WHERE tenant_id=? AND attempt_id=?
                    """, normalizedError, tenantId, attemptId);
                appendWorkerFactInTransaction(tenantId, runId, attemptId, "attempt-failed-unknown:" + attemptId,
                        "SIDE_EFFECT_UNKNOWN", "WORKER", "failure-handler",
                        commandDispatchId(tenantId, attemptId), normalizedError);
                UUID interventionId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO workflow_runtime.manual_intervention
                      (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,reason_code,evidence)
                    VALUES (?,?,?,'ATTEMPT',?,'OPEN','ATTEMPT_FAILED_AFTER_SIDE_EFFECT',?::jsonb)
                    """, tenantId, interventionId, runId, attemptId, normalizedError);
                jdbc.update("""
                    UPDATE workflow_runtime.workflow_run SET status='MANUAL_REQUIRED',
                           control_gate='CLOSED',recovery_state='BLOCKED',
                           terminal_reason='ATTEMPT_SIDE_EFFECT_UNCERTAIN',
                           updated_at=clock_timestamp()
                     WHERE tenant_id=? AND run_id=?
                       AND status NOT IN ('SUCCEEDED','FAILED','STOPPED')
                    """, tenantId, runId);
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_audit
                      (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                    VALUES (?,?,'SYSTEM','runtime','ATTEMPT_FAILED_MANUAL_REQUIRED','ATTEMPT',?,?::jsonb,
                            'failure crossed side-effect boundary; device/resource state must be verified')
                    """, tenantId, runId, attemptId, normalizedError);
                appendEvents(tenantId, runId, List.of(
                        new EventDraft("ATTEMPT_FAILED_UNKNOWN", "ATTEMPT", attemptId, null, null, null,
                                "{\"interventionId\":\"" + interventionId + "\"}"),
                        new EventDraft("RUN_MANUAL_REQUIRED", "RUN", runId, head.commitVersion() + 1, null, null,
                                "{\"reason\":\"ATTEMPT_SIDE_EFFECT_UNCERTAIN\"}")));
                return new AttemptFailureResult("MANUAL_REQUIRED", interventionId);
            }

            // Deterministic failure with no uncertain external side effect.
            jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='CANCELLED',
                       version=version+1,completed_at=clock_timestamp(),updated_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN ('CREATED','DISPATCH_PENDING')
                """, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='DEVICE_COMMAND'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'commandId' IN (
                     SELECT command_id::text FROM workflow_runtime.command_ledger
                      WHERE tenant_id=? AND attempt_id=? AND command_state='CANCELLED'
                   )
                """, tenantId, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='WORKER_DISPATCH'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'attemptId'=?
                """, tenantId, attemptId.toString());
            jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='FAILED',
                       finished_at=clock_timestamp(),attempt_version=attempt_version+1,error=?::jsonb
                 WHERE tenant_id=? AND attempt_id=? AND run_id=?
                """, normalizedError, tenantId, attemptId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='FAILED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('READY','RESERVED','RUNNING','WAITING')
                """, tenantId, activationId);
            jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='FAILED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state IN ('READY','RESERVED','RUNNING','WAITING')
                """, tenantId, tokenId);
            releaseReservationInTransaction(tenantId, attemptId);
            appendWorkerFactInTransaction(tenantId, runId, attemptId, "execution-failed",
                    "EXECUTION_RIGHT_RELEASED", "WORKER", "failure-handler",
                    commandDispatchId(tenantId, attemptId), "{\"reason\":\"attempt-failed\"}");
            appendEvents(tenantId, runId, List.of(new EventDraft("ATTEMPT_FAILED", "ATTEMPT", attemptId,
                    null, null, null, normalizedError)));
            GraphFailureResult runOutcome = transitionRunAfterDeterministicFailure(
                    tenantId, runId, "ATTEMPT_FAILED", normalizedError, "ATTEMPT", attemptId);
            return new AttemptFailureResult(runOutcome.outcome(), runOutcome.interventionId());
        });
    }

    /**
     * Cancel an active Attempt only when no external side effect was started; otherwise
     * escalate fail-closed to UNKNOWN / MANUAL_REQUIRED without releasing device ownership.
     * Terminal or manually-blocked Runs reject further Attempt cancellation mutations.
     */
    public AttemptFailureResult cancelAttempt(UUID tenantId, UUID runId, UUID attemptId, String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? "cancelled" : reason;
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            requireRunAdmitsAttemptTermination(head, "cancel");
            List<UUID> activationIds = jdbc.query("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId, attemptId);
            if (activationIds.size() != 1) throw new IllegalStateException("Attempt not found");
            UUID activationId = activationIds.get(0);
            UUID tokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """, UUID.class, tenantId, activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class, tenantId, tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class, tenantId, activationId);
            List<String> attemptStates = jdbc.query("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), tenantId, runId, attemptId);
            if (attemptStates.size() != 1
                    || !List.of("CREATED", "DISPATCHED", "ACCEPTED", "STARTED").contains(attemptStates.get(0))) {
                throw new StaleRuntimeWriteException("Attempt cannot be cancelled in its current state");
            }
            Integer completedFacts = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_COMPLETED'
                """, Integer.class, tenantId, attemptId);
            Integer ambiguousSentCommands = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=?
                   AND command_state IN ('SEND_STARTED','ACKNOWLEDGED','RUNNING','CANCEL_REQUESTED',
                                         'DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
                """, Integer.class, tenantId, attemptId);
            Integer completedCommands = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=? AND command_state='SUCCEEDED'
                """, Integer.class, tenantId, attemptId);

            int completedCount = completedFacts == null ? 0 : completedFacts;
            boolean hasUncertainSideEffect = hasUnresolvedSideEffectForAttempt(tenantId, attemptId)
                    || (ambiguousSentCommands != null && ambiguousSentCommands > 0);
            if (hasUncertainSideEffect) {
                UUID interventionId = UUID.randomUUID();
                jdbc.update("""
                    UPDATE workflow_runtime.node_attempt SET attempt_state='UNKNOWN',
                           attempt_version=attempt_version+1
                     WHERE tenant_id=? AND attempt_id=?
                    """, tenantId, attemptId);
                appendWorkerFactInTransaction(tenantId, runId, attemptId, "cancel-uncertain:" + attemptId,
                        "SIDE_EFFECT_UNKNOWN", "WORKER", "cancel-handler",
                        commandDispatchId(tenantId, attemptId), "{\"reason\":\"" + jsonEscape(safeReason) + "\"}");
                jdbc.update("""
                    INSERT INTO workflow_runtime.manual_intervention
                      (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,reason_code,evidence)
                    VALUES (?,?,?,'ATTEMPT',?,'OPEN','CANCEL_AFTER_SIDE_EFFECT',?::jsonb)
                    """, tenantId, interventionId, runId, attemptId, "{\"reason\":\"" + jsonEscape(safeReason) + "\"}");
                jdbc.update("""
                    UPDATE workflow_runtime.workflow_run SET status='MANUAL_REQUIRED',
                           control_gate='CLOSED',recovery_state='BLOCKED',
                           terminal_reason='CANCEL_SIDE_EFFECT_UNCERTAIN',
                           updated_at=clock_timestamp()
                     WHERE tenant_id=? AND run_id=?
                       AND status NOT IN ('SUCCEEDED','FAILED','STOPPED')
                    """, tenantId, runId);
                appendEvents(tenantId, runId, List.of(new EventDraft("ATTEMPT_CANCEL_UNKNOWN", "ATTEMPT",
                        attemptId, null, null, null, "{\"interventionId\":\"" + interventionId + "\"}")));
                return new AttemptFailureResult("MANUAL_REQUIRED", interventionId);
            }
            if (completedCount > 0 || (completedCommands != null && completedCommands > 0)) {
                throw new InvalidTransitionException(
                        "Cannot cancel Attempt after external side effect has completed; commit completion or reconcile");
            }

            jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='CANCELLED',
                       version=version+1,completed_at=clock_timestamp(),updated_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN ('CREATED','DISPATCH_PENDING')
                """, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='DEVICE_COMMAND'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'commandId' IN (
                     SELECT command_id::text FROM workflow_runtime.command_ledger
                      WHERE tenant_id=? AND attempt_id=? AND command_state='CANCELLED'
                   )
                """, tenantId, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='WORKER_DISPATCH'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'attemptId'=?
                """, tenantId, attemptId.toString());
            jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='CANCELLED',
                       finished_at=clock_timestamp(),attempt_version=attempt_version+1
                 WHERE tenant_id=? AND attempt_id=?
                """, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='CANCELLED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('READY','RESERVED','RUNNING','WAITING')
                """, tenantId, activationId);
            jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='INVALIDATED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state IN ('READY','RESERVED','RUNNING','WAITING')
                """, tenantId, tokenId);
            releaseReservationInTransaction(tenantId, attemptId);
            appendWorkerFactInTransaction(tenantId, runId, attemptId, "execution-cancelled",
                    "EXECUTION_RIGHT_RELEASED", "WORKER", "cancel-handler",
                    commandDispatchId(tenantId, attemptId), "{\"reason\":\"" + jsonEscape(safeReason) + "\"}");
            appendEvents(tenantId, runId, List.of(new EventDraft("ATTEMPT_CANCELLED", "ATTEMPT", attemptId,
                    null, null, null, "{\"reason\":\"" + jsonEscape(safeReason) + "\"}")));
            return new AttemptFailureResult("CANCELLED", null);
        });
    }

    /**
     * Converge a pre-start Attempt ({@code CREATED}, {@code DISPATCHED}, or {@code ACCEPTED}) whose
     * Run closed its dispatch gate for an active {@code PAUSE}, {@code STOP}, or {@code FAILURE} Drain
     * before handler execution began.
     *
     * <p>During {@code PAUSE} Drain, the reservation is released, the Attempt and Activation are
     * cancelled, and the {@code execution_token} is restored from {@code RESERVED} back to {@code READY}
     * so {@link #tryFinalizeDrain} can reach {@code PAUSED} without losing the paused task.
     * During {@code STOP}/{@code FAILURE} Drain, the token is invalidated so Drain can reach
     * {@code STOPPED}/{@code FAILED}. Never touches {@code STARTED} Attempts or any Attempt with
     * recorded external side effects.
     */
    public AttemptFailureResult abortPreStartAttemptOnGateClosed(
            UUID tenantId, UUID runId, UUID attemptId, String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? "gate-closed-before-start" : reason;
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            boolean pauseDrain = "PAUSE_REQUESTED".equals(head.status()) || "PAUSING".equals(head.status());
            boolean stopDrain = "STOP_REQUESTED".equals(head.status()) || "STOPPING".equals(head.status());
            if (!pauseDrain && !stopDrain) {
                throw new InvalidTransitionException(
                        "Pre-start gate-closed abort requires active Drain (status=" + head.status() + ")");
            }
            List<UUID> activationIds = jdbc.query("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId, attemptId);
            if (activationIds.size() != 1) throw new IllegalStateException("Attempt not found");
            UUID activationId = activationIds.get(0);
            UUID tokenId = jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """, UUID.class, tenantId, activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class, tenantId, tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class, tenantId, activationId);
            List<String> attemptStates = jdbc.query("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), tenantId, runId, attemptId);
            if (attemptStates.size() != 1
                    || !List.of("CREATED", "DISPATCHED", "ACCEPTED").contains(attemptStates.get(0))) {
                throw new StaleRuntimeWriteException("Attempt is not in a pre-start state");
            }
            Integer anySideEffectFact = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=?
                   AND fact_type IN ('SIDE_EFFECT_STARTED','SIDE_EFFECT_COMPLETED','SIDE_EFFECT_UNKNOWN')
                """, Integer.class, tenantId, attemptId);
            Integer sentCommands = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=?
                   AND command_state NOT IN ('CREATED','DISPATCH_PENDING','CANCELLED')
                """, Integer.class, tenantId, attemptId);
            if ((anySideEffectFact != null && anySideEffectFact > 0)
                    || (sentCommands != null && sentCommands > 0)) {
                throw new InvalidTransitionException(
                        "Cannot abort pre-start Attempt that already recorded external side-effect evidence");
            }

            jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='CANCELLED',
                       version=version+1,completed_at=clock_timestamp(),updated_at=clock_timestamp()
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN ('CREATED','DISPATCH_PENDING')
                """, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='DEVICE_COMMAND'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'commandId' IN (
                     SELECT command_id::text FROM workflow_runtime.command_ledger
                      WHERE tenant_id=? AND attempt_id=? AND command_state='CANCELLED'
                   )
                """, tenantId, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='WORKER_DISPATCH'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'attemptId'=?
                """, tenantId, attemptId.toString());
            jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='CANCELLED',
                       finished_at=clock_timestamp(),attempt_version=attempt_version+1
                 WHERE tenant_id=? AND attempt_id=?
                """, tenantId, attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='CANCELLED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('READY','RESERVED')
                """, tenantId, activationId);
            if (pauseDrain) {
                jdbc.update("""
                    UPDATE workflow_runtime.execution_token SET token_state='READY',
                           token_version=token_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND token_id=? AND token_state='RESERVED'
                    """, tenantId, tokenId);
            } else {
                jdbc.update("""
                    UPDATE workflow_runtime.execution_token SET token_state='INVALIDATED',
                           token_version=token_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND token_id=? AND token_state IN ('READY','RESERVED')
                    """, tenantId, tokenId);
            }
            releaseReservationInTransaction(tenantId, attemptId);
            appendWorkerFactInTransaction(tenantId, runId, attemptId, "execution-aborted-gate-closed",
                    "EXECUTION_RIGHT_RELEASED", "WORKER", "gate-abort-handler",
                    commandDispatchId(tenantId, attemptId), "{\"reason\":\"" + jsonEscape(safeReason) + "\"}");
            appendEvents(tenantId, runId, List.of(new EventDraft("ATTEMPT_ABORTED_GATE_CLOSED", "ATTEMPT",
                    attemptId, null, null, null,
                    "{\"reason\":\"" + jsonEscape(safeReason) + "\",\"drainMode\":\""
                            + (pauseDrain ? "PAUSE" : "STOP") + "\"}")));
            return new AttemptFailureResult(pauseDrain ? "ABORTED_FOR_PAUSE" : "CANCELLED", null);
        });
    }

    public record GraphFailureResult(String outcome, UUID drainId, UUID interventionId) {}

    /**
     * Handle a deterministic graph-routing or expression-evaluation fault without falsely
     * assuming in-flight Worker or physical device actions have stopped:
     * <ul>
     *   <li>Zero active attempts/commands/leases/waits -> terminalize Run to FAILED/CLOSED.</li>
     *   <li>Any uncertain/device side effect or unreleased lease -> escalate to MANUAL_REQUIRED/CLOSED/BLOCKED.</li>
     *   <li>Only non-side-effect in-flight attempts/waits -> close dispatch gate and enter FAILURE drain.</li>
     * </ul>
     */
    public GraphFailureResult failGraphRun(UUID tenantId, UUID runId, UUID tokenId,
                                           String errorCode, String errorMessage) {
        String code = (errorCode == null || errorCode.isBlank()) ? "GRAPH_FAULT" : errorCode;
        String msg = (errorMessage == null) ? "" : errorMessage;
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            if (!"RUNNING".equals(head.status()) || !"OPEN".equals(head.controlGate())) {
                return new GraphFailureResult("IGNORED", null, null);
            }
            if (tokenId != null) {
                jdbc.update("""
                    UPDATE workflow_runtime.execution_token SET token_state='FAILED',
                           token_version=token_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND run_id=? AND token_id=? AND token_state='READY'
                    """, tenantId, runId, tokenId);
            }
            String payload = "{\"errorCode\":\"" + jsonEscape(code)
                    + "\",\"message\":\"" + jsonEscape(msg) + "\""
                    + (tokenId == null ? "" : ",\"tokenId\":\"" + tokenId + "\"") + "}";
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,?,'SYSTEM','graph-coordinator','GRAPH_ADVANCEMENT_FAILED','RUN',?,?::jsonb,?)
                """, tenantId, runId, runId, payload, code);
            appendEvents(tenantId, runId, List.of(new EventDraft("GRAPH_ADVANCEMENT_FAILED", "RUN",
                    runId, head.commitVersion() + 1, null, null, payload)));
            return transitionRunAfterDeterministicFailure(tenantId, runId, code, payload, "RUN", runId);
        });
    }

    private GraphFailureResult transitionRunAfterDeterministicFailure(
            UUID tenantId, UUID runId, String reasonCode, String payloadJson,
            String subjectKind, UUID subjectId) {
        Integer activeAttempts = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.node_attempt
             WHERE tenant_id=? AND run_id=?
               AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN')
            """, Integer.class, tenantId, runId);
        Integer unknownAttempts = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.node_attempt
             WHERE tenant_id=? AND run_id=? AND attempt_state='UNKNOWN'
            """, Integer.class, tenantId, runId);
        // Only commands that crossed SEND_STARTED represent an uncertain external side effect;
        // CREATED / DISPATCH_PENDING commands have not been sent and can be cancelled in FAILURE drain.
        Integer sentOrUncertainCommands = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.command_ledger
             WHERE tenant_id=? AND run_id=?
               AND command_state IN ('SEND_STARTED','ACKNOWLEDGED','RUNNING','CANCEL_REQUESTED',
                                     'DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
            """, Integer.class, tenantId, runId);
        Integer unsentCommands = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.command_ledger
             WHERE tenant_id=? AND run_id=?
               AND command_state IN ('CREATED','DISPATCH_PENDING')
            """, Integer.class, tenantId, runId);
        Integer activeSideEffects = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.node_attempt a
             WHERE a.tenant_id=? AND a.run_id=?
               AND a.attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN')
               AND (
            """ + UNRESOLVED_SIDE_EFFECT_PREDICATE_FOR_ALIAS_A + """
               )
            """, Integer.class, tenantId, runId);
        Integer unreleasedLeases = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.resource_lease
             WHERE tenant_id=? AND run_id=? AND lease_state <> 'RELEASED'
            """, Integer.class, tenantId, runId);
        Integer activeWaits = jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.wait_registration
             WHERE tenant_id=? AND run_id=? AND wait_state='ACTIVE'
            """, Integer.class, tenantId, runId);

        boolean hasActiveSideEffect = (unknownAttempts != null && unknownAttempts > 0)
                || (sentOrUncertainCommands != null && sentOrUncertainCommands > 0)
                || (activeSideEffects != null && activeSideEffects > 0)
                || (unreleasedLeases != null && unreleasedLeases > 0);
        if (hasActiveSideEffect) {
            jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='UNKNOWN',
                       version=version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=?
                   AND command_state IN ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN')
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.node_attempt a SET attempt_state='UNKNOWN',
                       attempt_version=attempt_version+1
                 WHERE a.tenant_id=? AND a.run_id=?
                   AND a.attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED')
                   AND (
                """ + UNRESOLVED_SIDE_EFFECT_PREDICATE_FOR_ALIAS_A + """
                     OR EXISTS (
                       SELECT 1 FROM workflow_runtime.command_ledger c
                        WHERE c.tenant_id=a.tenant_id AND c.attempt_id=a.attempt_id
                          AND c.command_state IN ('SEND_STARTED','ACKNOWLEDGED','RUNNING',
                                                  'CANCEL_REQUESTED','DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
                     )
                   )
                """, tenantId, runId);
            UUID interventionId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.manual_intervention
                  (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,reason_code,evidence)
                VALUES (?,?,?,?,?,'OPEN','FAILURE_WITH_ACTIVE_SIDE_EFFECT',?::jsonb)
                """, tenantId, interventionId, runId, subjectKind, subjectId, payloadJson);
            List<Long> versions = jdbc.query("""
                UPDATE workflow_runtime.workflow_run SET status='MANUAL_REQUIRED',
                       control_gate='CLOSED',recovery_state='BLOCKED',
                       terminal_reason=?,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=?
                   AND status NOT IN ('SUCCEEDED','FAILED','STOPPED')
             RETURNING commit_version
                """, (rs, n) -> rs.getLong(1), reasonCode, tenantId, runId);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,?,'SYSTEM','runtime','FAILURE_ESCALATED_MANUAL_REQUIRED',?,?,?::jsonb,
                        'active device/resource side effect prevents direct FAILED terminalization')
                """, tenantId, runId, subjectKind, subjectId, payloadJson);
            Long nextVer = versions.isEmpty() ? null : versions.get(0) + 1;
            appendEvents(tenantId, runId, List.of(new EventDraft("RUN_MANUAL_REQUIRED", "RUN", runId,
                    nextVer, null, null,
                    "{\"reason\":\"" + jsonEscape(reasonCode) + "\",\"interventionId\":\"" + interventionId + "\"}")));
            return new GraphFailureResult("MANUAL_REQUIRED", null, interventionId);
        }

        boolean hasInFlightWork = (activeAttempts != null && activeAttempts > 0)
                || (unsentCommands != null && unsentCommands > 0)
                || (activeWaits != null && activeWaits > 0);
        if (hasInFlightWork) {
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET status='STOP_REQUESTED',
                       control_gate='STOP',terminal_reason=?,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=?
                   AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','MANUAL_REQUIRED','QUARANTINED')
                """, reasonCode, tenantId, runId);
            List<UUID> openDrains = jdbc.query("""
                SELECT drain_id FROM workflow_runtime.drain_record
                 WHERE tenant_id=? AND run_id=?
                   AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT','MANUAL_REQUIRED')
                 ORDER BY created_at DESC FOR UPDATE
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId);
            UUID drainId;
            if (!openDrains.isEmpty()) {
                drainId = openDrains.get(0);
                jdbc.update("""
                    UPDATE workflow_runtime.drain_record SET drain_kind='FAILURE',version=version+1
                     WHERE tenant_id=? AND drain_id=? AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT')
                    """, tenantId, drainId);
            } else {
                drainId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO workflow_runtime.drain_record
                      (tenant_id,drain_id,run_id,drain_kind,drain_state)
                    VALUES (?,?,?,'FAILURE','REQUESTED')
                    """, tenantId, drainId, runId);
            }
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                VALUES (?,?,'DRAIN_REQUEST',?,jsonb_build_object('drainId',?::text,'kind','FAILURE'))
                ON CONFLICT (tenant_id,dedup_key) DO NOTHING
                """, tenantId, runId, "drain-failure:" + drainId, drainId);
            appendEvents(tenantId, runId, List.of(new EventDraft("FAILURE_DRAIN_REQUESTED", "DRAIN", drainId,
                    null, null, null, "{\"reason\":\"" + jsonEscape(reasonCode) + "\"}")));
            return new GraphFailureResult("DRAINING_FAILURE", drainId, null);
        }

        List<Long> versions = jdbc.query("""
            UPDATE workflow_runtime.workflow_run SET status='FAILED',
                   control_gate='CLOSED',finished_at=clock_timestamp(),
                   terminal_reason=?,updated_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=?
               AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','MANUAL_REQUIRED','QUARANTINED')
         RETURNING commit_version
            """, (rs, n) -> rs.getLong(1), reasonCode, tenantId, runId);
        if (versions.size() == 1) {
            appendEvents(tenantId, runId, List.of(new EventDraft("RUN_FAILED", "RUN",
                    runId, versions.get(0) + 1, null, null, payloadJson)));
            return new GraphFailureResult("FAILED", null, null);
        }
        return new GraphFailureResult("IGNORED", null, null);
    }

    /**
     * Persist a Worker-side effect boundary before/after the corresponding Adapter call.
     * For single-operation attempts, SIDE_EFFECT_STARTED binds {@code side_effect_key = factKey}
     * and SIDE_EFFECT_COMPLETED/UNKNOWN pairs uniquely with the sole open operation.
     * Use the 8-arg overload with an explicit {@code sideEffectKey} whenever multiple
     * side-effect operations may overlap within one Attempt.
     */
    public boolean recordSideEffectFact(UUID tenantId, UUID runId, UUID attemptId,
                                        String workerId, String factKey, String factType,
                                        String payloadJson) {
        return recordSideEffectFactInternal(tenantId, runId, attemptId, workerId,
                null, factKey, factType, payloadJson);
    }

    /**
     * Persist a Worker-side effect fact bound to an explicit {@code sideEffectKey} operation identity.
     *
     * <ul>
     *   <li>Exact replay of an existing {@code factKey} is idempotent even after the Run gate closes.</li>
     *   <li>A new {@code SIDE_EFFECT_STARTED} requires the Run to be {@code RUNNING + OPEN}.</li>
     *   <li>{@code SIDE_EFFECT_COMPLETED} and {@code SIDE_EFFECT_UNKNOWN} remain allowed during active
     *       Drain so an already-started operation can record its outcome, and must match an existing
     *       {@code SIDE_EFFECT_STARTED} with the same {@code sideEffectKey}.</li>
     * </ul>
     */
    public boolean recordSideEffectFact(UUID tenantId, UUID runId, UUID attemptId,
                                        String workerId, String sideEffectKey, String factKey,
                                        String factType, String payloadJson) {
        if (sideEffectKey == null || sideEffectKey.isBlank())
            throw new IllegalArgumentException("sideEffectKey is required");
        return recordSideEffectFactInternal(tenantId, runId, attemptId, workerId,
                sideEffectKey, factKey, factType, payloadJson);
    }

    private boolean recordSideEffectFactInternal(UUID tenantId, UUID runId, UUID attemptId,
                                                 String workerId, String explicitSideEffectKey,
                                                 String factKey, String factType, String payloadJson) {
        if (!List.of("SIDE_EFFECT_STARTED", "SIDE_EFFECT_COMPLETED", "SIDE_EFFECT_UNKNOWN").contains(factType))
            throw new IllegalArgumentException("Unsupported Worker side-effect fact type");
        if (factKey == null || factKey.isBlank())
            throw new IllegalArgumentException("factKey is required");
        String normalizedPayload = payloadJson == null ? "{}" : payloadJson;
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            List<String> states = jdbc.query("""
                SELECT attempt_state || chr(31) || COALESCE(worker_id,'')
                  FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), tenantId, runId, attemptId);
            if (states.size() != 1) throw new StaleRuntimeWriteException("Attempt not found");
            String[] owner = states.get(0).split(String.valueOf((char) 31), 2);
            if (!"STARTED".equals(owner[0]) || !workerId.equals(owner[1]))
                throw new StaleRuntimeWriteException("Only the owning local Worker may append side-effect facts");

            UUID dispatchId = commandDispatchId(tenantId, attemptId);

            // Check exact idempotent replay BEFORE gate checks so replaying an already-recorded
            // SIDE_EFFECT_STARTED after gate closure succeeds without creating any new fact.
            List<String> existingSideEffectKeys = jdbc.query("""
                SELECT COALESCE(side_effect_key,'') FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_key=?
                """, (rs, n) -> rs.getString(1), tenantId, attemptId, factKey);
            if (!existingSideEffectKeys.isEmpty()) {
                String expectedSeKey = explicitSideEffectKey != null
                        ? explicitSideEffectKey
                        : ("SIDE_EFFECT_STARTED".equals(factType) ? factKey
                        : (existingSideEffectKeys.get(0).isEmpty() ? null : existingSideEffectKeys.get(0)));
                return appendWorkerFactInTransaction(tenantId, runId, attemptId, factKey, expectedSeKey,
                        factType, "WORKER", workerId, dispatchId, normalizedPayload) > 0;
            }

            String resolvedSideEffectKey;
            if ("SIDE_EFFECT_STARTED".equals(factType)) {
                requireRunOpenForExecutionStart(head, "side-effect start");
                resolvedSideEffectKey = explicitSideEffectKey != null ? explicitSideEffectKey : factKey;
                Integer alreadyStarted = jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.worker_execution_fact
                     WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_STARTED'
                       AND side_effect_key=?
                    """, Integer.class, tenantId, attemptId, resolvedSideEffectKey);
                if (alreadyStarted != null && alreadyStarted > 0) {
                    throw new InvalidTransitionException("Side effect already started: " + resolvedSideEffectKey);
                }
            } else {
                requireRunAdmitsAttemptTermination(head, "side-effect outcome");
                if (explicitSideEffectKey != null) {
                    resolvedSideEffectKey = explicitSideEffectKey;
                    Integer matchingStart = jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.worker_execution_fact
                         WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_STARTED'
                           AND side_effect_key=?
                        """, Integer.class, tenantId, attemptId, resolvedSideEffectKey);
                    if (matchingStart == null || matchingStart == 0) {
                        throw new InvalidTransitionException(
                                "Side-effect completion/unknown requires matching SIDE_EFFECT_STARTED: "
                                        + resolvedSideEffectKey);
                    }
                } else {
                    List<String> openKeys = jdbc.query("""
                        SELECT s.side_effect_key FROM workflow_runtime.worker_execution_fact s
                         WHERE s.tenant_id=? AND s.attempt_id=? AND s.fact_type='SIDE_EFFECT_STARTED'
                           AND s.side_effect_key IS NOT NULL
                           AND NOT EXISTS (
                             SELECT 1 FROM workflow_runtime.worker_execution_fact c
                              WHERE c.tenant_id=s.tenant_id AND c.attempt_id=s.attempt_id
                                AND c.fact_type='SIDE_EFFECT_COMPLETED'
                                AND c.side_effect_key=s.side_effect_key
                           )
                         ORDER BY s.fact_seq
                        """, (rs, n) -> rs.getString(1), tenantId, attemptId);
                    if (openKeys.isEmpty()) {
                        throw new InvalidTransitionException(
                                "Side-effect completion/unknown requires an open SIDE_EFFECT_STARTED");
                    }
                    if (openKeys.size() > 1) {
                        throw new InvalidTransitionException(
                                "Multiple open SIDE_EFFECT_STARTED operations; explicit sideEffectKey is required");
                    }
                    resolvedSideEffectKey = openKeys.get(0);
                }
                if ("SIDE_EFFECT_COMPLETED".equals(factType)) {
                    Integer alreadyCompleted = jdbc.queryForObject("""
                        SELECT count(*) FROM workflow_runtime.worker_execution_fact
                         WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_COMPLETED'
                           AND side_effect_key=?
                        """, Integer.class, tenantId, attemptId, resolvedSideEffectKey);
                    if (alreadyCompleted != null && alreadyCompleted > 0) {
                        throw new InvalidTransitionException(
                                "Side effect already completed: " + resolvedSideEffectKey);
                    }
                }
            }
            return appendWorkerFactInTransaction(tenantId, runId, attemptId, factKey, resolvedSideEffectKey,
                    factType, "WORKER", workerId, dispatchId, normalizedPayload) > 0;
        });
    }

    /** Acquire a business resource lease; uniqueness is enforced by the active-lease index. */
    public record AcquiredLease(UUID leaseId) {}
    public AcquiredLease acquireResourceLease(UUID tenantId, UUID runId,
                                               String resourceId, String ownerKind, UUID ownerId,
                                               Instant expiresAt, String evidenceJson) {
        return required(() -> {
            lockRun(tenantId,runId);
            UUID leaseId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.resource_lease
                  (tenant_id,lease_id,run_id,resource_id,lease_state,owner_kind,owner_id,expires_at,evidence)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,?::jsonb)
                """,tenantId,leaseId,runId,resourceId,ownerKind,ownerId,
                    expiresAt==null?null:Timestamp.from(expiresAt),evidenceJson==null?"{}":evidenceJson);
            appendEvents(tenantId,runId,List.of(new EventDraft("RESOURCE_LEASE_ACQUIRED",
                    "LEASE",leaseId,0L,null,null,"{\"resourceId\":\""+jsonEscape(resourceId)+"\"}")));
            return new AcquiredLease(leaseId);
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
                    "command:"+commandId,factType,"RECOVERY",actor,commandDispatchId(tenantId,attemptId),evidenceJson);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,?,'SYSTEM',?,'UNKNOWN_COMMAND_RESOLVED','COMMAND',?,?::jsonb,?)
                """,tenantId,runId,actor,commandId,evidenceJson,"explicit external verification");
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "COMMAND_VERIFIED_"+resolvedState,"COMMAND",commandId,null,null,null,evidenceJson)));
            return true;
        });
    }

    /** T06: register retry intent; timer scheduling is an Outbox effect after commit. */
    public UUID scheduleRetry(UUID tenantId, UUID runId, UUID activationId,
                              Instant dueAt, int nextAttemptNo, String policyJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); 
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
                appendEvents(tenantId,runId,List.of(new EventDraft("RETRY_SCHEDULED","RETRY",
                        retryId,0L,null,null,"{}")));
            }
            return retryId;
        });
    }

    /** T07: suspend activation and persist continuation without occupying a Worker. */
    public UUID registerWait(UUID tenantId, UUID runId, UUID activationId,
                             String waitKind, String correlationKey, Instant deadline,
                             String continuationJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND run_id=? AND activation_id=?
                """,UUID.class,tenantId,runId,activationId);
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            UUID waitId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.wait_registration
                  (tenant_id,wait_id,run_id,activation_id,wait_state,wait_kind,
                   correlation_key,deadline_at,continuation)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,?::jsonb)
                """,tenantId,waitId,runId,activationId,waitKind,correlationKey,deadline==null?null:Timestamp.from(deadline),continuationJson);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='WAITING',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state IN ('RUNNING','RESERVED')
                """,tenantId,activationId);
            if(changed!=1) throw new StaleRuntimeWriteException("Activation cannot enter WAITING");
            int tokenChanged=jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='WAITING',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state IN ('READY','RESERVED','RUNNING')
                """,tenantId,tokenId);
            if(tokenChanged!=1) throw new StaleRuntimeWriteException("Token cannot enter WAITING");
            appendEvents(tenantId,runId,List.of(new EventDraft("WAIT_REGISTERED","WAIT",
                    waitId,0L,null,null,"{}")));
            return waitId;
        });
    }

    /** T08: one winner closes Wait; the old Attempt is explicitly ABANDONED_SAFE. */
    public boolean closeWait(UUID tenantId, UUID runId, UUID waitId,
                             boolean satisfied, UUID oldAttemptId) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); 
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
            List<UUID[]> tokenContext=jdbc.query("""
                SELECT split_id,branch_id,loop_context_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND token_id=? FOR UPDATE
                """,(rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)},
                tenantId,tokenId);
            if(tokenContext.size()!=1) throw new IllegalStateException("Wait Token not found");
            UUID[] lineage=tokenContext.get(0);
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
            int tokenConsumed=jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state='WAITING'
                """,tenantId,tokenId);
            if(tokenConsumed!=1) throw new StaleRuntimeWriteException("Wait Token is not WAITING");
            if(continuationNode==null||continuationNode.isBlank())
                throw new IllegalStateException("Wait continuation has no nodeKey");
            jdbc.update("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,run_id,token_state,node_key,parent_token_id,split_id,branch_id,loop_context_id,payload)
                VALUES (?,?,'READY',?,?,?,?,?,?::jsonb)
                """,tenantId,runId,continuationNode,tokenId,lineage[0],lineage[1],lineage[2],"{}");
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    satisfied?"WAIT_SATISFIED":"WAIT_EXPIRED","WAIT",waitId,null,null,null,"{}")));
            return true;
        });
    }


    /** Persist one scalar-or-JSON Run variable; Run locking serializes writers with graph advancement. */
    public long putFlowVariable(UUID tenantId, UUID runId, String key, String valueJson) {
        if(key==null || key.isBlank()) throw new IllegalArgumentException("flow variable key is required");
        if(valueJson==null || valueJson.isBlank()) throw new IllegalArgumentException("flow variable value JSON is required");
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            if(!"RUNNING".equals(head.status()))
                throw new InvalidTransitionException("Flow variables may only change while Run is RUNNING");
            Long version=jdbc.queryForObject("""
                INSERT INTO workflow_runtime.flow_variable
                  (tenant_id,run_id,variable_key,value,value_version,
                   source_activation_id,source_split_id,source_branch_id)
                VALUES (?,?,?,?::jsonb,1,NULL,NULL,NULL)
                ON CONFLICT (tenant_id,run_id,variable_key) DO UPDATE
                   SET value=EXCLUDED.value,value_version=workflow_runtime.flow_variable.value_version+1,
                       source_activation_id=NULL,source_split_id=NULL,source_branch_id=NULL,
                       updated_at=clock_timestamp()
                RETURNING value_version
                """,Long.class,tenantId,runId,key,valueJson);
            if(version==null) throw new IllegalStateException("Flow variable write returned no version");
            appendEvents(tenantId,runId,List.of(new EventDraft("FLOW_VARIABLE_UPDATED","RUN",
                    runId,head.commitVersion()+1,null,null,"{\"key\":\""+jsonEscape(key)+"\",\"version\":"+version+"}")));
            return version;
        });
    }

    public record GraphVariable(String key, String valueJson) {}
    public record GraphVariableSnapshot(String inputJson, List<GraphVariable> variables) {}

    /** Read Run input and persisted flow variables from one repeatable-read snapshot. */
    public GraphVariableSnapshot loadGraphVariableSnapshot(UUID tenantId, UUID runId) {
        return snapshotTx.execute(status -> {
            String input=jdbc.queryForObject("""
                SELECT input::text FROM workflow_runtime.workflow_run
                 WHERE tenant_id=? AND run_id=?
                """,String.class,tenantId,runId);
            List<GraphVariable> variables=jdbc.query("""
                SELECT variable_key,value::text FROM workflow_runtime.flow_variable
                 WHERE tenant_id=? AND run_id=? ORDER BY variable_key
                """,(rs,n)->new GraphVariable(rs.getString(1),rs.getString(2)),tenantId,runId);
            return new GraphVariableSnapshot(input,variables==null?List.of():List.copyOf(variables));
        });
    }

    public record GraphToken(UUID tokenId, String nodeKey, UUID splitId, UUID branchId) {}
    public record CompletedGraphActivation(UUID activationId, UUID tokenId, String nodeKey,
                                           UUID splitId, UUID branchId) {}
    public record GraphAdvanceResult(String outcome, UUID activationId, int tokensCreated,
                                     boolean runCompleted) {}

    /** Read a bounded, stable batch of READY tokens for one Run. */
    public List<GraphToken> findReadyGraphTokens(UUID tenantId, UUID runId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        List<GraphToken> rows = snapshotTx.execute(status -> jdbc.query("""
            SELECT token_id,node_key,split_id,branch_id
              FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND token_state='READY'
             ORDER BY created_at,token_id
             LIMIT ?
            """, (rs,n) -> new GraphToken(rs.getObject("token_id",UUID.class),
                    rs.getString("node_key"),rs.getObject("split_id",UUID.class),
                    rs.getObject("branch_id",UUID.class)), tenantId,runId,limit));
        return rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * Read a bounded, stable batch of READY tokens restricted to the given node keys
     * (e.g. control-flow nodes), preventing waiting TASK/BUILTIN tokens from starving
     * downstream graph-control advancement.
     */
    public List<GraphToken> findReadyGraphTokensForNodes(UUID tenantId, UUID runId,
                                                         List<String> nodeKeys, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        if (nodeKeys == null || nodeKeys.isEmpty()) return List.of();
        String[] keyArray = nodeKeys.toArray(String[]::new);
        List<GraphToken> rows = snapshotTx.execute(status -> jdbc.query(con -> {
            var ps = con.prepareStatement("""
                SELECT token_id,node_key,split_id,branch_id
                  FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND token_state='READY'
                   AND node_key = ANY(?)
                 ORDER BY created_at,token_id
                 LIMIT ?
                """);
            ps.setObject(1, tenantId);
            ps.setObject(2, runId);
            Array sqlArray = con.createArrayOf("text", keyArray);
            ps.setArray(3, sqlArray);
            ps.setInt(4, limit);
            return ps;
        }, (rs, n) -> new GraphToken(rs.getObject("token_id", UUID.class),
                rs.getString("node_key"), rs.getObject("split_id", UUID.class),
                rs.getObject("branch_id", UUID.class))));
        return rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * Find completed task Activations whose consumed Token has not yet produced successor Tokens.
     * Completion and graph routing are separate durable steps; this query makes the gap replayable.
     */
    public List<CompletedGraphActivation> findCompletedGraphActivations(UUID tenantId, UUID runId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        List<CompletedGraphActivation> rows = snapshotTx.execute(status -> jdbc.query("""
            SELECT a.activation_id,t.token_id,a.node_key,t.split_id,t.branch_id
              FROM workflow_runtime.node_activation a
              JOIN workflow_runtime.execution_token t
                ON t.tenant_id=a.tenant_id AND t.token_id=a.token_id
              JOIN workflow_runtime.workflow_run r
                ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id
             WHERE a.tenant_id=? AND a.run_id=? AND r.status='RUNNING'
               AND a.activation_state='SUCCEEDED' AND t.token_state='CONSUMED'
               AND a.current_attempt_id IS NOT NULL
               AND EXISTS (SELECT 1 FROM workflow_runtime.node_attempt completed_attempt
                            WHERE completed_attempt.tenant_id=a.tenant_id
                              AND completed_attempt.attempt_id=a.current_attempt_id
                              AND completed_attempt.attempt_state='SUCCEEDED')
               AND NOT EXISTS (
                   SELECT 1 FROM workflow_runtime.execution_token child
                    WHERE child.tenant_id=t.tenant_id AND child.parent_token_id=t.token_id
               )
             ORDER BY a.updated_at,a.activation_id
             LIMIT ?
            """, (rs,n) -> new CompletedGraphActivation(rs.getObject("activation_id",UUID.class),
                    rs.getObject("token_id",UUID.class),rs.getString("node_key"),
                    rs.getObject("split_id",UUID.class),rs.getObject("branch_id",UUID.class)),
                tenantId,runId,limit));
        return rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * Atomically activate and advance one READY control-flow Token.
     * transitionKind is SEQUENCE, SPLIT, JOIN, or END; JOIN is the synchronizing
     * Parallel/Inclusive join path, not an XOR merge.
     */
    public GraphAdvanceResult advanceGraphToken(UUID tenantId, UUID runId, UUID tokenId,
                                                 String expectedNodeKey, String transitionKind,
                                                 List<String> successorNodeKeys) {
        return advanceGraphToken(tenantId,runId,tokenId,expectedNodeKey,transitionKind,successorNodeKeys,null);
    }

    public GraphAdvanceResult advanceGraphToken(UUID tenantId, UUID runId, UUID tokenId,
                                                 String expectedNodeKey, String transitionKind,
                                                 List<String> successorNodeKeys, String joinNodeKey) {
        return required(() -> {
            RunHead head = lockRun(tenantId,runId);
            requireGraphRunOpen(head);
            List<GraphToken> rows = jdbc.query("""
                SELECT token_id,node_key,split_id,branch_id
                  FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND token_id=? AND token_state='READY'
                 FOR UPDATE
                """, (rs,n) -> new GraphToken(rs.getObject(1,UUID.class),rs.getString(2),
                        rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),
                tenantId,runId,tokenId);
            if (rows.size()!=1) throw new StaleRuntimeWriteException("Graph Token is no longer READY");
            GraphToken token=rows.get(0);
            if (!token.nodeKey().equals(expectedNodeKey))
                throw new StaleRuntimeWriteException("Graph Token nodeKey changed");
            int activationNoValue = jdbc.queryForObject("""
                SELECT COALESCE(MAX(activation_no),0)+1
                  FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND token_id=?
                """,Integer.class,tenantId,tokenId);
            UUID activationId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.node_activation
                  (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
                VALUES (?,?,?,?,?,?,'RUNNING')
                """,tenantId,activationId,runId,tokenId,activationNoValue,expectedNodeKey);
            if (jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='RUNNING',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state='READY'
                """,tenantId,tokenId)!=1)
                throw new StaleRuntimeWriteException("Graph Token was concurrently claimed");
            int created=advanceGraphSuccessorsInTransaction(tenantId,runId,token,activationId,
                    transitionKind,successorNodeKeys,joinNodeKey,false);
            appendEvents(tenantId,runId,List.of(new EventDraft("GRAPH_NODE_ADVANCED","ACTIVATION",
                    activationId,1L,null,null,"{\"nodeKey\":\""+jsonEscape(expectedNodeKey)
                    +"\",\"transition\":\""+jsonEscape(transitionKind)+"\",\"tokensCreated\":"+created+"}")));
            boolean completed=maybeCompleteGraphRun(tenantId,runId);
            return new GraphAdvanceResult("ADVANCED",activationId,created,completed);
        });
    }

    /** Atomically route the durable output of a successful task Attempt. Safe to replay after a crash. */
    public GraphAdvanceResult advanceCompletedGraphActivation(UUID tenantId, UUID runId,
                                                                UUID activationId, String transitionKind,
                                                                List<String> successorNodeKeys) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            requireGraphRunOpen(head);
            List<CompletedGraphActivation> rows=jdbc.query("""
                SELECT a.activation_id,t.token_id,a.node_key,t.split_id,t.branch_id
                  FROM workflow_runtime.node_activation a
                  JOIN workflow_runtime.execution_token t
                    ON t.tenant_id=a.tenant_id AND t.token_id=a.token_id
                 WHERE a.tenant_id=? AND a.run_id=? AND a.activation_id=?
                   AND a.activation_state='SUCCEEDED' AND t.token_state='CONSUMED'
                   AND a.current_attempt_id IS NOT NULL
                   AND EXISTS (SELECT 1 FROM workflow_runtime.node_attempt completed_attempt
                                WHERE completed_attempt.tenant_id=a.tenant_id
                                  AND completed_attempt.attempt_id=a.current_attempt_id
                                  AND completed_attempt.attempt_state='SUCCEEDED')
                 FOR UPDATE OF a,t
                """,(rs,n)->new CompletedGraphActivation(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),
                    rs.getObject(5,UUID.class)),tenantId,runId,activationId);
            if(rows.size()!=1) throw new StaleRuntimeWriteException("Completed graph Activation not found");
            CompletedGraphActivation source=rows.get(0);
            if(!"SEQUENCE".equals(transitionKind) || successorNodeKeys==null || successorNodeKeys.size()!=1)
                throw new IllegalArgumentException("Completed Worker Activation currently supports exactly one SEQUENCE successor");
            List<GraphToken> existingChildren=jdbc.query("""
                SELECT token_id,node_key,split_id,branch_id FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND parent_token_id=?
                 ORDER BY node_key,token_id
                """,(rs,n)->new GraphToken(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),tenantId,source.tokenId());
            if(!existingChildren.isEmpty()) {
                if(existingChildren.size()==1
                        && existingChildren.get(0).nodeKey().equals(successorNodeKeys.get(0))
                        && Objects.equals(existingChildren.get(0).splitId(),source.splitId())
                        && Objects.equals(existingChildren.get(0).branchId(),source.branchId()))
                    return new GraphAdvanceResult("REPLAYED",activationId,0,false);
                throw new IdempotencyConflictException("Completed Activation was already routed to different successor Token(s)");
            }
            GraphToken token=new GraphToken(source.tokenId(),source.nodeKey(),source.splitId(),source.branchId());
            int created=advanceGraphSuccessorsInTransaction(tenantId,runId,token,activationId,
                    transitionKind,successorNodeKeys,null,true);
            appendEvents(tenantId,runId,List.of(new EventDraft("GRAPH_SUCCESSORS_CREATED","ACTIVATION",
                    activationId,null,null,null,"{\"transition\":\""+jsonEscape(transitionKind)
                    +"\",\"tokensCreated\":"+created+"}")));
            boolean completed=maybeCompleteGraphRun(tenantId,runId);
            return new GraphAdvanceResult("ADVANCED",activationId,created,completed);
        });
    }

    private static void requireGraphRunOpen(RunHead head) {
        if (!"RUNNING".equals(head.status()) || !"OPEN".equals(head.controlGate()))
            throw new InvalidTransitionException("Graph advancement requires a RUNNING Run with OPEN gate");
    }

    private int advanceGraphSuccessorsInTransaction(UUID tenantId, UUID runId, GraphToken source,
                                                     UUID activationId, String transitionKind,
                                                     List<String> successorNodeKeys, String joinNodeKey,
                                                     boolean sourceAlreadyCompleted) {
        List<String> successors=successorNodeKeys==null?List.of():List.copyOf(successorNodeKeys);
        if (transitionKind==null) throw new IllegalArgumentException("transitionKind is required");
        int tokensCreated=0;
        switch (transitionKind) {
            case "SEQUENCE" -> {
                if(successors.size()!=1) throw new IllegalArgumentException("SEQUENCE requires exactly one successor");
                insertGraphToken(tenantId,runId,successors.get(0),source.tokenId(),source.splitId(),source.branchId());
                tokensCreated=1;
            }
            case "SPLIT" -> {
                if(successors.isEmpty()) throw new IllegalArgumentException("SPLIT requires at least one selected branch");
                if(source.branchId()!=null)
                    throw new InvalidTransitionException("Nested Split inside an active branch is not supported in G1");
                if(successors.stream().distinct().count()!=successors.size())
                    throw new IllegalArgumentException("Split branch target keys must be unique");
                UUID splitId=UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO workflow_runtime.split_activation
                      (tenant_id,split_id,run_id,source_activation_id,split_state,expected_branch_count)
                    VALUES (?,?,?,?,'OPEN',?)
                    """,tenantId,splitId,runId,activationId,successors.size());
                for(String key:successors) {
                    UUID branchId=UUID.randomUUID();
                    jdbc.update("""
                        INSERT INTO workflow_runtime.branch_activation
                          (tenant_id,branch_id,run_id,split_id,branch_key,branch_state)
                        VALUES (?,?,?, ?,?,'ACTIVE')
                        """,tenantId,branchId,runId,splitId,key);
                    insertGraphToken(tenantId,runId,key,source.tokenId(),splitId,branchId);
                    tokensCreated++;
                }
            }
            case "JOIN" -> {
                if(successors.size()!=1) throw new IllegalArgumentException("JOIN requires exactly one successor");
                if(source.splitId()==null || source.branchId()==null)
                    throw new InvalidTransitionException("Synchronizing Join requires a Token from an active Split");
                if(joinNodeKey==null || joinNodeKey.isBlank())
                    throw new IllegalArgumentException("JOIN requires its BPMN join node key");
                if(!source.nodeKey().equals(joinNodeKey))
                    throw new InvalidTransitionException("JOIN key must match the Token's current gateway node");
                UUID splitId=source.splitId();
                List<UUID> openSplits=jdbc.query("""
                    SELECT split_id FROM workflow_runtime.split_activation
                     WHERE tenant_id=? AND run_id=? AND split_id=? AND split_state='OPEN'
                     FOR UPDATE
                    """,(rs,n)->rs.getObject(1,UUID.class),tenantId,runId,splitId);
                if(openSplits.size()!=1)
                    throw new StaleRuntimeWriteException("Split is no longer OPEN for Join arrival");
                List<String> existingJoinKeys=jdbc.query("""
                    SELECT join_key FROM workflow_runtime.join_activation
                     WHERE tenant_id=? AND split_id=? FOR UPDATE
                    """,(rs,n)->rs.getString(1),tenantId,splitId);
                if(existingJoinKeys.stream().anyMatch(key -> !key.equals(joinNodeKey)))
                    throw new InvalidTransitionException("Branches of one Split reached different Join/End nodes");
                int arrived=jdbc.update("""
                    UPDATE workflow_runtime.branch_activation SET branch_state='ARRIVED',
                           arrival_activation_id=?,arrived_at=clock_timestamp()
                     WHERE tenant_id=? AND run_id=? AND split_id=? AND branch_id=?
                       AND branch_state IN ('ACTIVE','WAITING')
                    """,activationId,tenantId,runId,splitId,source.branchId());
                if(arrived!=1) throw new StaleRuntimeWriteException("Branch already arrived or is not active");
                int splitIncremented=jdbc.update("""
                    UPDATE workflow_runtime.split_activation SET arrived_branch_count=arrived_branch_count+1,
                           version=version+1
                     WHERE tenant_id=? AND split_id=? AND split_state='OPEN'
                    """,tenantId,splitId);
                if(splitIncremented!=1) throw new StaleRuntimeWriteException("Split is no longer OPEN");
                Integer expected=jdbc.queryForObject("""
                    SELECT expected_branch_count FROM workflow_runtime.split_activation
                     WHERE tenant_id=? AND split_id=?
                    """,Integer.class,tenantId,splitId);
                Integer count=jdbc.queryForObject("""
                    SELECT arrived_branch_count FROM workflow_runtime.split_activation
                     WHERE tenant_id=? AND split_id=?
                    """,Integer.class,tenantId,splitId);
                String joinKey=joinNodeKey;
                jdbc.update("""
                    INSERT INTO workflow_runtime.join_activation
                      (tenant_id,join_id,run_id,split_id,join_key,join_state)
                    VALUES (?,?,?, ?,?,'WAITING')
                    ON CONFLICT (tenant_id,split_id,join_key) DO NOTHING
                    """,tenantId,UUID.randomUUID(),runId,splitId,joinKey);
                if(Objects.equals(expected,count)) {
                    int released=jdbc.update("""
                        UPDATE workflow_runtime.join_activation SET join_state='RELEASED',
                               released_at=clock_timestamp(),version=version+1
                         WHERE tenant_id=? AND split_id=? AND join_key=? AND join_state='WAITING'
                        """,tenantId,splitId,joinKey);
                    if(released!=1) throw new StaleRuntimeWriteException("Join release was not uniquely claimable");
                    int closed=jdbc.update("""
                        UPDATE workflow_runtime.split_activation SET split_state='CLOSED',
                               closed_at=clock_timestamp(),version=version+1
                         WHERE tenant_id=? AND split_id=? AND split_state='OPEN'
                        """,tenantId,splitId);
                    if(closed!=1) throw new StaleRuntimeWriteException("Split could not be closed upon Join release");
                    insertGraphToken(tenantId,runId,successors.get(0),source.tokenId(),null,null);
                    tokensCreated=1;
                }
            }
            case "END" -> {
                if(!successors.isEmpty()) throw new IllegalArgumentException("END cannot have successors");
                if((source.branchId()==null) != (source.splitId()==null))
                    throw new InvalidTransitionException("Token has incomplete Split/Branch lineage at END");
                if(source.branchId()!=null && source.splitId()!=null) {
                    UUID splitId=source.splitId();
                    // Keep identical lock order to JOIN: split_activation -> join_activation -> branch_activation.
                    List<UUID> openSplits=jdbc.query("""
                        SELECT split_id FROM workflow_runtime.split_activation
                         WHERE tenant_id=? AND run_id=? AND split_id=? AND split_state='OPEN'
                         FOR UPDATE
                        """,(rs,n)->rs.getObject(1,UUID.class),tenantId,runId,splitId);
                    if(openSplits.size()!=1)
                        throw new StaleRuntimeWriteException("Split is no longer OPEN for terminal branch arrival");
                    List<String> existingJoinKeys=jdbc.query("""
                        SELECT join_key FROM workflow_runtime.join_activation
                         WHERE tenant_id=? AND split_id=? FOR UPDATE
                        """,(rs,n)->rs.getString(1),tenantId,splitId);
                    if(existingJoinKeys.stream().anyMatch(key -> !key.isEmpty()))
                        throw new InvalidTransitionException("A Split cannot mix terminal branches with a synchronizing Join");
                    int arrived=jdbc.update("""
                        UPDATE workflow_runtime.branch_activation SET branch_state='ARRIVED',
                               arrival_activation_id=?,arrived_at=clock_timestamp()
                         WHERE tenant_id=? AND run_id=? AND split_id=? AND branch_id=?
                           AND branch_state IN ('ACTIVE','WAITING')
                        """,activationId,tenantId,runId,splitId,source.branchId());
                    if(arrived!=1)
                        throw new StaleRuntimeWriteException("Terminal branch already arrived or is not active");
                    int splitIncremented=jdbc.update("""
                        UPDATE workflow_runtime.split_activation SET arrived_branch_count=arrived_branch_count+1,
                               version=version+1
                         WHERE tenant_id=? AND split_id=? AND split_state='OPEN'
                        """,tenantId,splitId);
                    if(splitIncremented!=1)
                        throw new StaleRuntimeWriteException("Split is no longer OPEN");
                    jdbc.update("""
                        INSERT INTO workflow_runtime.join_activation
                          (tenant_id,join_id,run_id,split_id,join_key,join_state)
                        VALUES (?,?,?,?,'','WAITING')
                        ON CONFLICT (tenant_id,split_id,join_key) DO NOTHING
                        """,tenantId,UUID.randomUUID(),runId,splitId);
                    Integer expected=jdbc.queryForObject("SELECT expected_branch_count FROM workflow_runtime.split_activation WHERE tenant_id=? AND split_id=?",
                            Integer.class,tenantId,splitId);
                    Integer count=jdbc.queryForObject("SELECT arrived_branch_count FROM workflow_runtime.split_activation WHERE tenant_id=? AND split_id=?",
                            Integer.class,tenantId,splitId);
                    if(Objects.equals(expected,count)) {
                        int released=jdbc.update("""
                            UPDATE workflow_runtime.join_activation SET join_state='RELEASED',
                                   released_at=clock_timestamp(),version=version+1
                             WHERE tenant_id=? AND split_id=? AND join_key='' AND join_state='WAITING'
                            """,tenantId,splitId);
                        if(released!=1)
                            throw new StaleRuntimeWriteException("Terminal split marker was not uniquely claimable");
                        int closed=jdbc.update("""
                            UPDATE workflow_runtime.split_activation SET split_state='CLOSED',
                                   closed_at=clock_timestamp(),version=version+1
                             WHERE tenant_id=? AND split_id=? AND split_state='OPEN'
                            """,tenantId,splitId);
                        if(closed!=1)
                            throw new StaleRuntimeWriteException("Split could not be closed upon terminal completion");
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported graph transition: "+transitionKind);
        }
        if(!sourceAlreadyCompleted) {
            if(jdbc.update("""
                UPDATE workflow_runtime.node_activation SET activation_state='SUCCEEDED',
                       activation_version=activation_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND activation_id=? AND activation_state='RUNNING'
                """,tenantId,activationId)!=1)
                throw new StaleRuntimeWriteException("Graph Activation is not RUNNING");
            if(jdbc.update("""
                UPDATE workflow_runtime.execution_token SET token_state='CONSUMED',
                       token_version=token_version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND token_id=? AND token_state='RUNNING'
                """,tenantId,source.tokenId())!=1)
                throw new StaleRuntimeWriteException("Graph Token is not RUNNING");
        }
        return tokensCreated;
    }

    private void insertGraphToken(UUID tenantId, UUID runId, String nodeKey, UUID parentTokenId,
                                  UUID splitId, UUID branchId) {
        jdbc.update("""
            INSERT INTO workflow_runtime.execution_token
              (tenant_id,run_id,token_state,node_key,parent_token_id,split_id,branch_id)
            VALUES (?,?,'READY',?,?,?,?)
            """,tenantId,runId,nodeKey,parentTokenId,splitId,branchId);
    }

    /**
     * Transition a RUNNING Run to SUCCEEDED only when every Run-scoped state table
     * has zero non-terminal / blocking records. Each table is checked by its exact
     * non-terminal / blocking state set rather than raw row counts.
     */
    private boolean maybeCompleteGraphRun(UUID tenantId, UUID runId) {
        Long blockers = jdbc.queryForObject("""
            SELECT
              (SELECT count(*) FROM workflow_runtime.execution_token
                WHERE tenant_id=? AND run_id=? AND token_state <> 'CONSUMED') +
              (SELECT count(*) FROM workflow_runtime.node_activation
                WHERE tenant_id=? AND run_id=?
                  AND activation_state IN ('READY','RESERVED','RUNNING','WAITING','FAILED','CANCELLED')) +
              (SELECT count(*) FROM workflow_runtime.node_attempt
                WHERE tenant_id=? AND run_id=?
                  AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','FAILED','CANCELLED','UNKNOWN')) +
              (SELECT count(*) FROM workflow_runtime.node_activation a
                WHERE a.tenant_id=? AND a.run_id=? AND a.activation_state='SUCCEEDED'
                  AND a.current_attempt_id IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM workflow_runtime.execution_token child
                       WHERE child.tenant_id=a.tenant_id AND child.parent_token_id=a.token_id
                  )) +
              (SELECT count(*) FROM workflow_runtime.split_activation
                WHERE tenant_id=? AND run_id=? AND split_state <> 'CLOSED') +
              (SELECT count(*) FROM workflow_runtime.join_activation
                WHERE tenant_id=? AND run_id=? AND join_state <> 'RELEASED') +
              (SELECT count(*) FROM workflow_runtime.branch_activation
                WHERE tenant_id=? AND run_id=? AND branch_state <> 'ARRIVED') +
              (SELECT count(*) FROM workflow_runtime.loop_context
                WHERE tenant_id=? AND run_id=? AND loop_state <> 'COMPLETED') +
              (SELECT count(*) FROM workflow_runtime.wait_registration
                WHERE tenant_id=? AND run_id=? AND wait_state='ACTIVE') +
              (SELECT count(*) FROM workflow_runtime.retry_registration
                WHERE tenant_id=? AND run_id=? AND retry_state='SCHEDULED') +
              (SELECT count(*) FROM workflow_runtime.command_ledger
                WHERE tenant_id=? AND run_id=?
                  AND command_state NOT IN ('SUCCEEDED','FAILED','CANCELLED')) +
              (SELECT count(*) FROM workflow_runtime.resource_lease
                WHERE tenant_id=? AND run_id=? AND lease_state <> 'RELEASED') +
              (SELECT count(*) FROM workflow_runtime.dispatch_reservation
                WHERE tenant_id=? AND run_id=? AND reservation_state <> 'RELEASED') +
              (SELECT count(*) FROM workflow_runtime.manual_intervention
                WHERE tenant_id=? AND run_id=? AND intervention_state='OPEN') +
              (SELECT count(*) FROM workflow_runtime.drain_record
                WHERE tenant_id=? AND run_id=?
                  AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT','MANUAL_REQUIRED')) +
              (SELECT count(*) FROM workflow_runtime.drain_item di
                JOIN workflow_runtime.drain_record dr
                  ON dr.tenant_id=di.tenant_id AND dr.drain_id=di.drain_id
                WHERE di.tenant_id=? AND dr.run_id=? AND di.item_state <> 'SAFE') +
              (SELECT count(*) FROM workflow_runtime.quarantine_record
                WHERE tenant_id=? AND run_id=?
                  AND quarantine_state IN ('ACTIVE','CLEAR_PENDING')) +
              (SELECT count(*) FROM workflow_runtime.runtime_outbox
                WHERE tenant_id=? AND run_id=? AND outbox_state IN ('PENDING','SENDING'))
            """, Long.class,
                tenantId, runId, // execution_token
                tenantId, runId, // node_activation
                tenantId, runId, // node_attempt
                tenantId, runId, // unrouted completed Worker activation
                tenantId, runId, // split_activation
                tenantId, runId, // join_activation
                tenantId, runId, // branch_activation
                tenantId, runId, // loop_context
                tenantId, runId, // wait_registration
                tenantId, runId, // retry_registration
                tenantId, runId, // command_ledger
                tenantId, runId, // resource_lease
                tenantId, runId, // dispatch_reservation
                tenantId, runId, // manual_intervention
                tenantId, runId, // drain_record
                tenantId, runId, // drain_item
                tenantId, runId, // quarantine_record
                tenantId, runId  // runtime_outbox
        );
        if (blockers == null || blockers != 0) return false;
        List<Long> versions = jdbc.query("""
            UPDATE workflow_runtime.workflow_run SET status='SUCCEEDED',
                   control_gate='CLOSED',
                   finished_at=clock_timestamp(),terminal_reason='GRAPH_COMPLETED',
                   updated_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=? AND status='RUNNING' AND control_gate='OPEN'
         RETURNING commit_version
            """, (rs, n) -> rs.getLong(1), tenantId, runId);
        if (versions.size() == 1) {
            appendEvents(tenantId, runId, List.of(new EventDraft("RUN_SUCCEEDED", "RUN", runId,
                    versions.get(0) + 1, null, null, "{\"reason\":\"GRAPH_COMPLETED\"}")));
            return true;
        }
        return false;
    }

    /** T11: compare-and-set Loop iteration and persist next iteration in the same transaction. */
    public long advanceLoop(UUID tenantId, UUID runId, UUID loopId,
                            long expectedVersion, long expectedIteration, String nextNodeKey,
                            String contextJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            List<Long> nextRows=jdbc.query("""
                UPDATE workflow_runtime.loop_context SET iteration=iteration+1,version=version+1,
                       context_data=?::jsonb
                 WHERE tenant_id=? AND run_id=? AND loop_context_id=? AND loop_state='ACTIVE'
                   AND version=? AND iteration=?
             RETURNING iteration
                """,(rs,n)->rs.getLong(1),contextJson,tenantId,runId,loopId,expectedVersion,expectedIteration);
            if(nextRows.size()!=1) throw new StaleRuntimeWriteException("Loop version/iteration stale");
            long next=nextRows.get(0);
            jdbc.update("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,run_id,token_state,node_key,loop_context_id)
                VALUES (?,?,'READY',?,?)
                """,tenantId,runId,nextNodeKey,loopId);
            appendEvents(tenantId,runId,List.of(new EventDraft("LOOP_ADVANCED","LOOP",
                    loopId,expectedVersion+1,null,null,"{\"iteration\":"+next+"}")));
            return next;
        });
    }

    public boolean completeLoop(UUID tenantId, UUID runId, UUID loopId,
                                long expectedVersion) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            int changed=jdbc.update("""
                UPDATE workflow_runtime.loop_context SET loop_state='COMPLETED',version=version+1
                 WHERE tenant_id=? AND run_id=? AND loop_context_id=? AND version=? AND loop_state='ACTIVE'
                """,tenantId,runId,loopId,expectedVersion);
            if(changed==1) appendEvents(tenantId,runId,List.of(new EventDraft(
                    "LOOP_COMPLETED","LOOP",loopId,expectedVersion+1,null,null,"{}")));
            return changed==1;
        });
    }

    /** T12: create one immutable Command per device Attempt and its durable dispatch Outbox. */
    public UUID createCommand(UUID tenantId, UUID runId, UUID activationId,
                              UUID attemptId, String deviceId, String commandKind,
                              String idempotencyClass, String payloadJson) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            List<String> attemptRows=jdbc.query("""
                SELECT attempt_state,activation_id::text FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1)+"|"+rs.getString(2),tenantId,runId,attemptId);
            if(attemptRows.size()!=1 || !"STARTED".equals(attemptRows.get(0).split("\\|",2)[0])
                    || !activationId.toString().equals(attemptRows.get(0).split("\\|",2)[1]))
                throw new InvalidTransitionException("Command requires the matching STARTED Attempt");
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
            requireRunOpenForExecutionStart(head,"command creation");
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
            appendEvents(tenantId,runId,List.of(new EventDraft("COMMAND_CREATED","COMMAND",
                    commandId,0L,null,null,"{}")));
            return commandId;
        });
    }

    /**
     * Worker-scoped T12 entry: verifies {@code workerId} owns the {@code STARTED} Attempt
     * and creates (or idempotently replays) its single immutable Command + {@code DEVICE_COMMAND}
     * Outbox entry without exposing raw {@code activationId} plumbing to handlers.
     */
    public UUID createCommandForAttempt(UUID tenantId, UUID runId, UUID attemptId,
                                        String workerId, String deviceId, String commandKind,
                                        String idempotencyClass, String payloadJson) {
        if (workerId == null || workerId.isBlank())
            throw new IllegalArgumentException("workerId is required");
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            List<String> rows = jdbc.query("""
                SELECT attempt_state || '|' || COALESCE(worker_id,'') || '|' || activation_id::text
                  FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), tenantId, runId, attemptId);
            if (rows.size() != 1) throw new StaleRuntimeWriteException("Attempt not found");
            String[] meta = rows.get(0).split("\\|", 3);
            if (!"STARTED".equals(meta[0]) || !workerId.equals(meta[1]))
                throw new StaleRuntimeWriteException("Only the owning local Worker may create a Command");
            UUID activationId = UUID.fromString(meta[2]);
            return createCommand(tenantId, runId, activationId, attemptId,
                    deviceId, commandKind, idempotencyClass, payloadJson);
        });
    }

    public record DispatchedAttemptRecord(
            UUID runId, UUID activationId, UUID attemptId, UUID dispatchId,
            String nodeKey, String sideEffectClass, String attemptState) {}

    public Optional<DispatchedAttemptRecord> loadDispatchedAttempt(UUID tenantId, UUID attemptId) {
        List<DispatchedAttemptRecord> rows = jdbc.query("""
            SELECT a.run_id, a.activation_id, a.attempt_id, a.dispatch_id,
                   act.node_key, a.side_effect_class, a.attempt_state
              FROM workflow_runtime.node_attempt a
              JOIN workflow_runtime.node_activation act
                ON act.tenant_id=a.tenant_id AND act.activation_id=a.activation_id
             WHERE a.tenant_id=? AND a.attempt_id=?
            """, (rs, n) -> new DispatchedAttemptRecord(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)), tenantId, attemptId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record CommandRecord(
            UUID commandId, UUID runId, UUID activationId, UUID attemptId,
            String deviceId, String commandKind, String commandState,
            String idempotencyClass, String payloadJson, long version) {}

    public Optional<CommandRecord> loadCommand(UUID tenantId, UUID commandId) {
        List<CommandRecord> rows = jdbc.query("""
            SELECT command_id, run_id, activation_id, attempt_id, device_id, command_kind,
                   command_state, idempotency_class, command_payload::text, version
              FROM workflow_runtime.command_ledger
             WHERE tenant_id=? AND command_id=?
            """, (rs, n) -> new CommandRecord(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getLong(10)), tenantId, commandId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<String> findInboxReceiptState(UUID tenantId, UUID inboxId) {
        List<String> rows = jdbc.query("""
            SELECT receipt_state FROM workflow_runtime.inbox_receipt
             WHERE tenant_id=? AND inbox_id=?
            """, (rs, n) -> rs.getString(1), tenantId, inboxId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Resolve the authoritative tenant owning {@code (runId, commandId)} for multi-tenant Device Gateway ingress. */
    public Optional<UUID> findTenantIdForCommand(UUID runId, UUID commandId) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(commandId, "commandId");
        List<UUID> rows = jdbc.query("""
            SELECT tenant_id FROM workflow_runtime.command_ledger
             WHERE run_id=? AND command_id=?
            """, (rs, n) -> rs.getObject(1, UUID.class), runId, commandId);
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    public record CommandRouting(UUID tenantId, UUID runId) {}

    /**
     * Resolve authoritative {@code (tenantId, runId)} by {@code commandId} alone for legacy
     * wire protocols (such as {@code #!LABSEP!#}) whose replies carry {@code message_id=commandId}
     * without repeating {@code runId}.
     */
    public Optional<CommandRouting> findCommandRoutingById(UUID commandId) {
        Objects.requireNonNull(commandId, "commandId");
        List<CommandRouting> rows = jdbc.query("""
            SELECT tenant_id, run_id FROM workflow_runtime.command_ledger
             WHERE command_id=?
            """, (rs, n) -> new CommandRouting(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), commandId);
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    public record RunnableRunRef(UUID tenantId, UUID runId) {}

    /** Single-host dispatcher discovery: active RUNNING/OPEN runs that currently have READY tokens (oldest updated first). */
    public List<RunnableRunRef> findRunnableWorkTargets(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT r.tenant_id, r.run_id
              FROM workflow_runtime.workflow_run r
             WHERE r.status = 'RUNNING'
               AND r.control_gate = 'OPEN'
               AND EXISTS (
                 SELECT 1 FROM workflow_runtime.execution_token t
                  WHERE t.tenant_id = r.tenant_id AND t.run_id = r.run_id AND t.token_state = 'READY'
               )
             ORDER BY r.updated_at ASC, r.run_id ASC
             LIMIT ?
            """, (rs, n) -> new RunnableRunRef(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), limit);
    }

    /** Tenant-scoped dispatcher discovery pushing {@code tenant_id} directly into SQL before {@code LIMIT}. */
    public List<RunnableRunRef> findRunnableWorkTargetsForTenant(UUID tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT r.tenant_id, r.run_id
              FROM workflow_runtime.workflow_run r
             WHERE r.tenant_id = ?
               AND r.status = 'RUNNING'
               AND r.control_gate = 'OPEN'
               AND EXISTS (
                 SELECT 1 FROM workflow_runtime.execution_token t
                  WHERE t.tenant_id = r.tenant_id AND t.run_id = r.run_id AND t.token_state = 'READY'
               )
             ORDER BY r.updated_at ASC, r.run_id ASC
             LIMIT ?
            """, (rs, n) -> new RunnableRunRef(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), tenantId, limit);
    }

    /** Single-host outbox discovery: tenants with due PENDING outbox rows (oldest available_at first). */
    public List<UUID> findTenantsWithPendingOutbox(String messageKind, int limit) {
        if (messageKind == null || messageKind.isBlank())
            throw new IllegalArgumentException("messageKind is required");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT tenant_id
              FROM workflow_runtime.runtime_outbox
             WHERE message_kind = ?
               AND outbox_state = 'PENDING'
               AND available_at <= clock_timestamp()
             GROUP BY tenant_id
             ORDER BY min(available_at) ASC, tenant_id ASC
             LIMIT ?
            """, (rs, n) -> rs.getObject(1, UUID.class), messageKind, limit);
    }

    /** Tenant-scoped check whether due PENDING outbox rows exist for {@code messageKind}. */
    public boolean hasPendingOutboxForTenant(UUID tenantId, String messageKind) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (messageKind == null || messageKind.isBlank())
            throw new IllegalArgumentException("messageKind is required");
        Boolean found = jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM workflow_runtime.runtime_outbox
               WHERE tenant_id = ?
                 AND message_kind = ?
                 AND outbox_state = 'PENDING'
                 AND available_at <= clock_timestamp()
            )
            """, Boolean.class, tenantId, messageKind);
        return Boolean.TRUE.equals(found);
    }

    public record ManualRequiredRunSummary(UUID runId, String status, String controlGate, Instant updatedAt) {}

    /** Read-only observability: list Runs awaiting operator intervention for {@code tenantId}. */
    public List<ManualRequiredRunSummary> findManualRequiredRuns(UUID tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT run_id, status, control_gate, updated_at
              FROM workflow_runtime.workflow_run
             WHERE tenant_id = ?
               AND status = 'MANUAL_REQUIRED'
             ORDER BY updated_at DESC
             LIMIT ?
            """, (rs, n) -> new ManualRequiredRunSummary(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getTimestamp(4).toInstant()), tenantId, limit);
    }

    public record UnknownCommandSummary(
            UUID commandId, UUID runId, UUID attemptId,
            String deviceId, String commandKind, String commandState, Instant updatedAt) {}

    /** Read-only observability: list uncertain Commands eligible for T21 manual reconciliation. */
    public List<UnknownCommandSummary> findUnknownCommands(UUID tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT command_id, run_id, attempt_id, device_id, command_kind, command_state, updated_at
              FROM workflow_runtime.command_ledger
             WHERE tenant_id = ?
               AND command_state IN ('UNKNOWN', 'DELIVERY_UNKNOWN')
             ORDER BY updated_at DESC
             LIMIT ?
            """, (rs, n) -> new UnknownCommandSummary(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant()), tenantId, limit);
    }

    public record OpenDrainRef(
            UUID tenantId, UUID runId, UUID drainId, String drainKind, String drainState, long version) {}

    /** Global discovery of open PAUSE/STOP drains awaiting local host convergence. */
    public List<OpenDrainRef> findOpenDrains(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT d.tenant_id, d.run_id, d.drain_id, d.drain_kind, d.drain_state, d.version
              FROM workflow_runtime.drain_record d
              JOIN workflow_runtime.workflow_run r
                ON r.tenant_id = d.tenant_id AND r.run_id = d.run_id
             WHERE d.drain_state IN ('REQUESTED', 'DRAINING', 'SAFE_POINT')
               AND d.drain_kind IN ('PAUSE', 'STOP')
               AND r.status IN ('PAUSE_REQUESTED', 'PAUSING', 'STOP_REQUESTED', 'STOPPING')
             ORDER BY d.created_at ASC, d.drain_id ASC
             LIMIT ?
            """, (rs, n) -> new OpenDrainRef(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5),
                rs.getLong(6)), limit);
    }

    /** Tenant-scoped discovery of open PAUSE/STOP drains awaiting local host convergence. */
    public List<OpenDrainRef> findOpenDrainsForTenant(UUID tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        return jdbc.query("""
            SELECT d.tenant_id, d.run_id, d.drain_id, d.drain_kind, d.drain_state, d.version
              FROM workflow_runtime.drain_record d
              JOIN workflow_runtime.workflow_run r
                ON r.tenant_id = d.tenant_id AND r.run_id = d.run_id
             WHERE d.tenant_id = ?
               AND d.drain_state IN ('REQUESTED', 'DRAINING', 'SAFE_POINT')
               AND d.drain_kind IN ('PAUSE', 'STOP')
               AND r.status IN ('PAUSE_REQUESTED', 'PAUSING', 'STOP_REQUESTED', 'STOPPING')
             ORDER BY d.created_at ASC, d.drain_id ASC
             LIMIT ?
            """, (rs, n) -> new OpenDrainRef(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5),
                rs.getLong(6)), tenantId, limit);
    }

    /** Mark local DRAIN_REQUEST outbox rows as DELIVERED once observed by the host drain loop. */
    public int deliverDrainRequestOutbox(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        return required(() -> jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox
               SET outbox_state = 'DELIVERED', delivered_at = clock_timestamp()
             WHERE tenant_id = ? AND run_id = ?
               AND message_kind = 'DRAIN_REQUEST'
               AND outbox_state IN ('PENDING', 'SENDING')
            """, tenantId, runId));
    }

    /** Return currently active local attempt IDs for cooperative cancellation during STOP drain. */
    public List<UUID> findActiveAttemptIdsForRun(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        return jdbc.query("""
            SELECT attempt_id
              FROM workflow_runtime.node_attempt
             WHERE tenant_id = ? AND run_id = ?
               AND attempt_state IN ('CREATED', 'DISPATCHED', 'ACCEPTED', 'STARTED')
             ORDER BY created_at ASC
            """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, runId);
    }

    public record CommandOverview(
            UUID commandId, String deviceId, String commandKind, String commandState,
            String deviceSequence, Instant createdAt, Instant completedAt) {}

    public record RunOverview(
            UUID runId,
            String status,
            String controlGate,
            long commitVersion,
            String definitionKey,
            String definitionVersion,
            String terminalReason,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant finishedAt,
            int activeTokens,
            int activeAttempts,
            int openInterventions,
            List<String> completedNodes,
            List<String> activeNodes,
            Map<String, String> variables,
            List<CommandOverview> commands) {}

    /** Read-only point-in-time overview of a single Run for Kernel HTTP observability & trial runs. */
    public Optional<RunOverview> findRunOverview(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        return required(() -> {
            record HeadRow(
                    String status, String controlGate, long commitVersion,
                    String defKey, String defVer, String terminalReason,
                    Instant createdAt, Instant startedAt, Instant updatedAt, Instant finishedAt) {}
            List<HeadRow> heads = jdbc.query("""
                SELECT r.status, r.control_gate, r.commit_version,
                       d.definition_key, d.definition_version, r.terminal_reason,
                       r.created_at, r.started_at, r.updated_at, r.finished_at
                  FROM workflow_runtime.workflow_run r
                  JOIN workflow_runtime.definition_snapshot d
                    ON d.tenant_id = r.tenant_id AND d.definition_id = r.definition_id
                 WHERE r.tenant_id = ? AND r.run_id = ?
                """, (rs, n) -> new HeadRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                    rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                    rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
                    rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()),
                tenantId, runId);
            if (heads.isEmpty()) {
                return Optional.empty();
            }
            HeadRow h = heads.get(0);
            Integer activeTokens = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.execution_token
                 WHERE tenant_id = ? AND run_id = ? AND token_state IN ('READY','RESERVED','RUNNING','WAITING')
                """, Integer.class, tenantId, runId);
            Integer activeAttempts = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.node_attempt
                 WHERE tenant_id = ? AND run_id = ? AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN')
                """, Integer.class, tenantId, runId);
            Integer openInterventions = jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.manual_intervention
                 WHERE tenant_id = ? AND run_id = ? AND intervention_state = 'OPEN'
                """, Integer.class, tenantId, runId);
            List<String> completedNodes = jdbc.query("""
                SELECT node_key FROM workflow_runtime.node_activation
                 WHERE tenant_id = ? AND run_id = ? AND activation_state = 'SUCCEEDED'
                 ORDER BY updated_at ASC, activation_id ASC
                """, (rs, n) -> rs.getString(1), tenantId, runId);
            List<String> activeNodes = jdbc.query("""
                SELECT node_key FROM workflow_runtime.execution_token
                 WHERE tenant_id = ? AND run_id = ? AND token_state IN ('READY','RESERVED','RUNNING','WAITING')
                 ORDER BY created_at ASC, token_id ASC
                """, (rs, n) -> rs.getString(1), tenantId, runId);
            Map<String, String> vars = new java.util.LinkedHashMap<>();
            jdbc.query("""
                SELECT variable_key, value::text FROM workflow_runtime.flow_variable
                 WHERE tenant_id = ? AND run_id = ?
                 ORDER BY variable_key ASC
                """, rs -> {
                vars.put(rs.getString(1), rs.getString(2));
            }, tenantId, runId);
            List<CommandOverview> commands = jdbc.query("""
                SELECT command_id, device_id, command_kind, command_state, device_sequence, created_at, completed_at
                  FROM workflow_runtime.command_ledger
                 WHERE tenant_id = ? AND run_id = ?
                 ORDER BY created_at ASC, command_id ASC
                """, (rs, n) -> new CommandOverview(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                    rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                tenantId, runId);
            return Optional.of(new RunOverview(
                    runId, h.status(), h.controlGate(), h.commitVersion(),
                    h.defKey(), h.defVer(), h.terminalReason(),
                    h.createdAt(), h.startedAt(), h.updatedAt(), h.finishedAt(),
                    activeTokens == null ? 0 : activeTokens,
                    activeAttempts == null ? 0 : activeAttempts,
                    openInterventions == null ? 0 : openInterventions,
                    List.copyOf(completedNodes),
                    List.copyOf(activeNodes),
                    Map.copyOf(vars),
                    List.copyOf(commands)));
        });
    }

    public record OutboxMessage(UUID outboxId, UUID runId, String messageKind, String dedupKey, String payloadJson) {}

    /** Single-host dispatcher: lock and transition pending rows without distributed claims. */
    public List<OutboxMessage> beginOutboxSend(UUID tenantId, int batchSize) {
        if(batchSize<1 || batchSize>500) throw new IllegalArgumentException("batchSize must be 1..500");
        return required(() -> jdbc.query("""
            WITH picked AS (
              SELECT outbox_id FROM workflow_runtime.runtime_outbox
               WHERE tenant_id=? AND outbox_state='PENDING' AND available_at<=clock_timestamp()
               ORDER BY available_at,created_at
               FOR UPDATE SKIP LOCKED LIMIT ?
            )
            UPDATE workflow_runtime.runtime_outbox o
               SET outbox_state='SENDING',delivery_attempts=delivery_attempts+1
              FROM picked p WHERE o.tenant_id=? AND o.outbox_id=p.outbox_id
            RETURNING o.outbox_id,o.run_id,o.message_kind,o.dedup_key,o.payload::text
            """,(rs,n)->new OutboxMessage(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5)),
                tenantId,batchSize,tenantId));
    }

    /** Single-host kind-scoped outbox claim so Worker and Command dispatchers never steal each other's rows. */
    public List<OutboxMessage> beginOutboxSendForKind(UUID tenantId, String messageKind, int batchSize) {
        if (messageKind == null || messageKind.isBlank())
            throw new IllegalArgumentException("messageKind is required");
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("batchSize must be 1..500");
        return required(() -> jdbc.query("""
            WITH picked AS (
              SELECT outbox_id FROM workflow_runtime.runtime_outbox
               WHERE tenant_id=? AND message_kind=? AND outbox_state='PENDING'
                 AND available_at<=clock_timestamp()
               ORDER BY available_at,created_at
               FOR UPDATE SKIP LOCKED LIMIT ?
            )
            UPDATE workflow_runtime.runtime_outbox o
               SET outbox_state='SENDING',delivery_attempts=delivery_attempts+1
              FROM picked p WHERE o.tenant_id=? AND o.outbox_id=p.outbox_id
            RETURNING o.outbox_id,o.run_id,o.message_kind,o.dedup_key,o.payload::text
            """, (rs, n) -> new OutboxMessage(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5)),
                tenantId, messageKind, batchSize, tenantId));
    }

    /** Safely requeue a SENDING outbox row back to PENDING ONLY before crossing any external side-effect boundary. */
    public boolean requeueOutboxToPending(UUID tenantId, UUID outboxId) {
        return required(() -> jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='PENDING'
             WHERE tenant_id=? AND outbox_id=? AND outbox_state='SENDING'
            """, tenantId, outboxId) == 1);
    }

    /**
     * Revert a {@code WORKER_DISPATCH} outbox row from {@code DELIVERED} back to {@code PENDING}
     * when synchronous {@code WorkerPool} submission rejected due to backpressure or closed host gate,
     * guarded by verifying the Attempt is still {@code DISPATCHED} (never accepted or started).
     */
    public boolean requeueWorkerDispatchOnBackpressure(UUID tenantId, UUID outboxId, UUID attemptId) {
        return required(() -> jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox o SET outbox_state='PENDING', delivered_at=NULL
             WHERE o.tenant_id=? AND o.outbox_id=? AND o.message_kind='WORKER_DISPATCH'
               AND o.outbox_state IN ('SENDING','DELIVERED')
               AND EXISTS (
                 SELECT 1 FROM workflow_runtime.node_attempt a
                  WHERE a.tenant_id=o.tenant_id AND a.attempt_id=? AND a.attempt_state='DISPATCHED'
               )
            """, tenantId, outboxId, attemptId) == 1);
    }

    /** Terminalize a stale, cancelled, or uncertain SENDING outbox row so it is never blindly re-sent. */
    public boolean markOutboxDeadLetter(UUID tenantId, UUID outboxId) {
        return required(() -> jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
             WHERE tenant_id=? AND outbox_id=? AND outbox_state IN ('PENDING','SENDING')
            """, tenantId, outboxId) == 1);
    }

    public boolean markOutboxDelivered(UUID tenantId, UUID outboxId) {
        return required(() -> jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED',
                   delivered_at=clock_timestamp()
             WHERE tenant_id=? AND outbox_id=? AND outbox_state='SENDING'
            """,tenantId,outboxId)==1);
    }

    /**
     * Persist {@code SEND_STARTED} before invoking the Adapter outside this repository.
     *
     * <p><strong>Single-send invariant (G1.5/G2 contract):</strong> once a {@code command_id}
     * crosses {@code SEND_STARTED}, it can never be re-dispatched under the same {@code command_id}.
     * Any retry after an external send attempt must allocate a new {@code node_attempt} and a new
     * {@code command_id}.
     */
    public boolean markCommandSendStarted(UUID tenantId, UUID commandId, long expectedVersion,
                                          UUID outboxId, UUID dispatchId, int dispatchNo,
                                          String dispatcherId) {
        return required(() -> {
            List<String> identity=jdbc.query("""
                SELECT run_id::text||'|'||attempt_id::text FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND command_id=?
                """,(rs,n)->rs.getString(1),tenantId,commandId);
            if(identity.size()!=1) throw new IllegalStateException("Command not found");
            String[] ids=identity.get(0).split("\\|");
            UUID runId=UUID.fromString(ids[0]), attemptId=UUID.fromString(ids[1]);
            RunHead head=lockRun(tenantId,runId);
            if(!"RUNNING".equals(head.status()) || !"OPEN".equals(head.controlGate())) return false;
            String attemptState=jdbc.queryForObject("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND attempt_id=? FOR UPDATE
                """,String.class,tenantId,attemptId);
            if(!"STARTED".equals(attemptState)) return false;
            List<String> payloadCommands=jdbc.query("""
                SELECT payload->>'commandId' FROM workflow_runtime.runtime_outbox
                 WHERE tenant_id=? AND outbox_id=? AND outbox_state='SENDING' FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,outboxId);
            if(payloadCommands.size()!=1 || !commandId.toString().equals(payloadCommands.get(0)))
                throw new StaleRuntimeWriteException("Outbox row is not SENDING or does not identify this Command");
            String commandSeKey="command:"+commandId;
            Integer priorStarts=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_type='SIDE_EFFECT_STARTED'
                   AND side_effect_key=?
                """,Integer.class,tenantId,attemptId,commandSeKey);
            if(priorStarts!=null && priorStarts>0) {
                throw new InvalidTransitionException(
                        "Command already crossed SEND_STARTED; retries must allocate a new Attempt and Command: "
                                + commandId);
            }
            int changed=jdbc.update("""
                UPDATE workflow_runtime.command_ledger SET command_state='SEND_STARTED',
                       version=version+1,updated_at=clock_timestamp()
                 WHERE tenant_id=? AND command_id=? AND version=?
                   AND command_state IN ('CREATED','DISPATCH_PENDING')
                """,tenantId,commandId,expectedVersion);
            if(changed==0) return false;
            jdbc.update("""
                INSERT INTO workflow_runtime.command_dispatch_attempt
                  (tenant_id,dispatch_id,command_id,dispatch_no,dispatch_state,dispatcher_id,started_at)
                VALUES (?,?,?,?,'SEND_STARTED',?,clock_timestamp())
                """,tenantId,dispatchId,commandId,dispatchNo,dispatcherId);
            appendWorkerFactInTransaction(tenantId,runId,attemptId,"command-send:"+dispatchId,
                    commandSeKey,"SIDE_EFFECT_STARTED","DISPATCHER",dispatcherId,commandDispatchId(tenantId,attemptId),
                    "{\"commandId\":\""+commandId+"\",\"dispatchNo\":"+dispatchNo+"}");
            return true;
        });
    }

    /**
     * Strict protocol allowlist binding device Inbox {@code message_type} to permitted
     * {@code command_ledger} target states. {@code HEARTBEAT} and unknown message types
     * MUST NEVER mutate Command state.
     */
    public static boolean isAllowedCommandEventMessageType(String messageType, String targetState) {
        if (messageType == null || targetState == null) return false;
        return switch (messageType) {
            case "RECEIVED_ACK" -> "ACKNOWLEDGED".equals(targetState);
            case "EXECUTING", "PROGRESS" -> "RUNNING".equals(targetState);
            case "COMMAND_RESULT", "DEVICE_COMMAND_RESULT" ->
                    List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(targetState);
            case "DELIVERY_UNCERTAIN", "COMMAND_UNKNOWN" ->
                    List.of("DELIVERY_UNKNOWN", "UNKNOWN").contains(targetState);
            default -> false;
        };
    }

    /** T14a: durable Inbox receipt; duplicate same (message_type, payload) is replay, mismatch is a protocol violation. */
    public InboxReceipt receiveInbox(UUID tenantId, String source, String messageId,
                                     String messageType, UUID runId, String payloadJson) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        return required(() -> {
            byte[] hash=sha256(messageType + "\n" + payloadJson);
            UUID inboxId=UUID.randomUUID();
            int inserted=jdbc.update("""
                INSERT INTO workflow_runtime.inbox_receipt
                  (tenant_id,inbox_id,source,source_message_id,payload_hash,message_type,message_payload,run_id)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (tenant_id,source,source_message_id) DO NOTHING
                """,tenantId,inboxId,source,messageId,hash,messageType,payloadJson,runId);
            if(inserted==1) return new InboxReceipt(inboxId,true,false);
            record ExistingInbox(UUID inboxId, byte[] payloadHash, String messageType) {}
            ExistingInbox existing=jdbc.queryForObject("""
                SELECT inbox_id, payload_hash, message_type FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND source=? AND source_message_id=?
                """,(rs,n)->new ExistingInbox(
                    rs.getObject(1,UUID.class), rs.getBytes(2), rs.getString(3)),
                tenantId,source,messageId);
            boolean conflict=!MessageDigest.isEqual(existing.payloadHash(),hash)
                    || !Objects.equals(existing.messageType(),messageType);
            if(conflict) jdbc.update("""
                INSERT INTO workflow_runtime.inbox_protocol_violation
                  (tenant_id,inbox_id,conflicting_payload_hash,conflicting_payload)
                VALUES (?,?,?,?::jsonb)
                """,tenantId,existing.inboxId(),hash,payloadJson);
            return new InboxReceipt(existing.inboxId(),false,conflict);
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
            List<String> states=jdbc.query("""
                SELECT command_state FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND run_id=? AND command_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,runId,commandId);
            if(states.size()!=1) throw new IllegalStateException("Command disappeared while applying device event");
            record LockedInbox(String source, String messageType, String commandId,
                               String deviceId, String payloadState,
                               boolean hasDeviceSequence, String payloadDeviceSequence,
                               boolean hasOutcome, boolean outcomeMatches) {}
            List<LockedInbox> inboxRows=jdbc.query("""
                SELECT source, message_type,
                       message_payload->>'commandId',
                       message_payload->>'deviceId',
                       message_payload->>'state',
                       (jsonb_typeof(message_payload->'deviceSequence') IS NOT NULL),
                       message_payload->>'deviceSequence',
                       (jsonb_typeof(message_payload->'outcome') IS NOT NULL),
                       CASE WHEN jsonb_typeof(message_payload->'outcome') IS NOT NULL
                            THEN (message_payload->'outcome' = ?::jsonb)
                            ELSE true END
                  FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND inbox_id=? AND run_id=? AND receipt_state IN ('RECEIVED','PROCESSING')
                   FOR UPDATE
                """,(rs,n)->new LockedInbox(
                    rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                    rs.getBoolean(6),rs.getString(7),rs.getBoolean(8),rs.getBoolean(9)),
                outcomeJson,tenantId,inboxId,runId);
            if(inboxRows.isEmpty())
                throw new StaleRuntimeWriteException("Inbox receipt was already consumed or not found");
            LockedInbox inbox=inboxRows.get(0);
            if(!deviceId.equals(inbox.source()))
                throw new InvalidTransitionException("Inbox source " + inbox.source()
                        + " does not match command device " + deviceId + "; event attribution rejected");
            if(!commandId.toString().equals(inbox.commandId()))
                throw new IllegalStateException("Inbox commandId does not match command row; event attribution rejected");
            if(inbox.deviceId()!=null && !inbox.deviceId().isBlank() && !deviceId.equals(inbox.deviceId()))
                throw new InvalidTransitionException("Inbox deviceId does not match command row; event attribution rejected");
            if(inbox.payloadState()==null || !targetState.equals(inbox.payloadState()))
                throw new InvalidTransitionException("Inbox payload state " + inbox.payloadState()
                        + " does not match target Command state " + targetState);
            if(inbox.hasDeviceSequence() && !Objects.equals(inbox.payloadDeviceSequence(),deviceSequence))
                throw new InvalidTransitionException("Inbox payload deviceSequence " + inbox.payloadDeviceSequence()
                        + " does not match applied deviceSequence " + deviceSequence);
            if(inbox.hasOutcome() && !inbox.outcomeMatches())
                throw new InvalidTransitionException("Inbox payload outcome does not match applied outcomeJson");
            if(!isAllowedCommandEventMessageType(inbox.messageType(),targetState))
                throw new InvalidTransitionException("Inbox message_type " + inbox.messageType()
                        + " is not allowed to transition Command to " + targetState);
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
                appendWorkerFactInTransaction(tenantId,runId,attemptId,"command-result:"+inboxId,
                        "command:"+commandId,factType,"DEVICE",deviceId,commandDispatchId(tenantId,attemptId),outcomeJson);
            }
            appendEvents(tenantId,runId,List.of(new EventDraft(
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
            if(changed==1) appendEvents(tenantId,runId,List.of(new EventDraft("INBOX_PROCESSED","INBOX",inboxId,null,null,null,"{}")));
            return changed==1;
        });
    }

    /** T15: close dispatch gate and persist Drain request atomically. */
    public UUID requestDrain(UUID tenantId, UUID runId, String drainKind) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId); 
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
                appendEvents(tenantId,runId,List.of(new EventDraft(
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
            appendEvents(tenantId,runId,List.of(new EventDraft("DRAIN_REQUESTED","DRAIN",
                    drainId,0L,null,null,"{}")));
            return drainId;
        });
    }

    /** T16: advance a bounded drain scan generation and persist its cursor. */
    public long advanceDrainScan(UUID tenantId, UUID runId, UUID drainId,
                                 long expectedVersion, String cursorJson, List<DrainItemRef> items) {
        return required(() -> {
            lockRun(tenantId,runId);
            List<Long> generations=jdbc.query("""
                UPDATE workflow_runtime.drain_record SET drain_state='DRAINING',
                       scan_cursor=?::jsonb,scan_generation=scan_generation+1,version=version+1
                 WHERE tenant_id=? AND run_id=? AND drain_id=? AND version=?
                   AND drain_state IN ('REQUESTED','DRAINING')
             RETURNING scan_generation
                """,(rs,n)->rs.getLong(1),cursorJson,tenantId,runId,drainId,expectedVersion);
            if(generations.size()!=1) throw new StaleRuntimeWriteException("Drain scan version stale");
            long generation=generations.get(0);
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
            List<String> keys=jdbc.query("""
                SELECT capacity_key FROM workflow_runtime.dispatch_reservation
                 WHERE tenant_id=? AND reservation_id=?
                """,(rs,n)->rs.getString(1),tenantId,reservationId);
            if(keys.size()!=1) return false;
            String capacityKey=keys.get(0);
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
                    WHERE tenant_id=? AND run_id=? AND ? <> 'PAUSE' AND split_state='OPEN') +
                  (SELECT count(*) FROM workflow_runtime.join_activation
                    WHERE tenant_id=? AND run_id=? AND ? <> 'PAUSE' AND join_state='WAITING') +
                  (SELECT count(*) FROM workflow_runtime.branch_activation
                    WHERE tenant_id=? AND run_id=? AND ? <> 'PAUSE' AND branch_state IN ('ACTIVE','WAITING')) +
                  (SELECT count(*) FROM workflow_runtime.loop_context
                    WHERE tenant_id=? AND run_id=? AND ? <> 'PAUSE' AND loop_state='ACTIVE') +
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
                    WHERE tenant_id=? AND run_id=? AND outbox_state IN ('PENDING','SENDING'))
                """,Long.class,
                    tenantId,runId,  // Commands
                    tenantId,runId,  // Leases
                    tenantId,runId,  // Waits
                    tenantId,runId,  // Retries
                    tenantId,runId,drainKind,  // Splits (Pause preserves open parallel split for resume)
                    tenantId,runId,drainKind,  // Joins (Pause preserves waiting join for resume)
                    tenantId,runId,drainKind,  // Branches (Pause preserves active branches for resume)
                    tenantId,runId,drainKind,  // Loops (Pause preserves active loop context for resume)
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
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "DRAIN_FINALIZED","RUN",runId,null,null,null,"{\"status\":\""+jsonEscape(terminalStatus)+"\"}")));
            return changed==1;
        });
    }

    /**
     * Resume a {@code PAUSED} Run by reopening its control gate ({@code PAUSE -> OPEN}) and
     * transitioning {@code PAUSED -> RUNNING}. Returns {@code false} if already {@code RUNNING/OPEN}.
     * Rejects Runs with open manual interventions or incomplete drains.
     */
    public boolean resumePausedRun(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        return required(() -> {
            RunHead head = lockRun(tenantId, runId);
            if ("RUNNING".equals(head.status()) && "OPEN".equals(head.controlGate())) {
                return false;
            }
            if (!"PAUSED".equals(head.status()) || !"PAUSE".equals(head.controlGate())) {
                throw new InvalidTransitionException(
                        "Cannot resume Run in status=" + head.status() + ", controlGate=" + head.controlGate());
            }
            Long blockers = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM workflow_runtime.manual_intervention
                    WHERE tenant_id=? AND run_id=? AND intervention_state='OPEN') +
                  (SELECT count(*) FROM workflow_runtime.drain_record
                    WHERE tenant_id=? AND run_id=? AND drain_state IN ('REQUESTED','DRAINING','SAFE_POINT','MANUAL_REQUIRED'))
                """, Long.class, tenantId, runId, tenantId, runId);
            if (blockers != null && blockers > 0) {
                throw new InvalidTransitionException(
                        "Cannot resume Run while open manual interventions or active drains remain");
            }
            int updated = jdbc.update("""
                UPDATE workflow_runtime.workflow_run
                   SET status='RUNNING', control_gate='OPEN', terminal_reason=NULL,
                       commit_version=commit_version+1, updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND status='PAUSED' AND control_gate='PAUSE'
                """, tenantId, runId);
            if (updated != 1) {
                throw new StaleRuntimeWriteException("Run was modified concurrently during resume");
            }
            appendEvents(tenantId, runId, List.of(new EventDraft(
                    "RUN_RESUMED", "RUN", runId, head.commitVersion() + 1, null, null, "{}")));
            return true;
        });
    }

    /**
     * Invalidate pending control-flow continuations during an active {@code STOP} drain so
     * {@link #tryFinalizeDrain} can safely converge to {@code STOPPED} once active executions drain.
     */
    public void invalidateStopContinuations(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        required(() -> {
            RunHead head = lockRun(tenantId, runId);
            if (!"STOP".equals(head.controlGate())
                    || !List.of("STOP_REQUESTED", "STOPPING").contains(head.status())) {
                return null;
            }
            jdbc.update("""
                UPDATE workflow_runtime.execution_token
                   SET token_state='INVALIDATED', token_version=token_version+1, updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND token_state='READY'
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.branch_activation
                   SET branch_state='CANCELLED'
                 WHERE tenant_id=? AND run_id=? AND branch_state IN ('ACTIVE','WAITING')
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.join_activation
                   SET join_state='CANCELLED', version=version+1
                 WHERE tenant_id=? AND run_id=? AND join_state='WAITING'
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.split_activation
                   SET split_state='CANCELLED', closed_at=clock_timestamp(), version=version+1
                 WHERE tenant_id=? AND run_id=? AND split_state='OPEN'
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.loop_context
                   SET loop_state='CANCELLED', version=version+1
                 WHERE tenant_id=? AND run_id=? AND loop_state='ACTIVE'
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.wait_registration
                   SET wait_state='CANCELLED', closed_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND wait_state='ACTIVE'
                """, tenantId, runId);
            jdbc.update("""
                UPDATE workflow_runtime.retry_registration
                   SET retry_state='CANCELLED'
                 WHERE tenant_id=? AND run_id=? AND retry_state='SCHEDULED'
                """, tenantId, runId);
            return null;
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
            appendEvents(tenantId,runId,List.of(new EventDraft(
                    "DRAIN_MANUAL_REQUIRED","DRAIN",drainId,null,null,null,evidenceJson)));
            return null;
        });
    }

    /** T20: all checkpoint projections are read in one REPEATABLE READ snapshot. */
    public Checkpoint createCheckpoint(UUID tenantId, UUID runId, String checkpointKind) {
        Checkpoint draft = snapshotTx.execute(status -> {
            RunHead head=jdbc.queryForObject("""
                SELECT status,control_gate,commit_version,event_seq,recovery_state
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
                    snapshot,hash);
        });
        if (draft == null) throw new IllegalStateException("Checkpoint snapshot was not produced");
        UUID persistedId=tx.execute(status -> {
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_checkpoint
                  (tenant_id,checkpoint_id,run_id,checkpoint_kind,run_commit_version,event_seq,
                   snapshot_payload,snapshot_hash)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (tenant_id,run_id,run_commit_version) DO NOTHING
                """,tenantId,draft.checkpointId(),runId,checkpointKind,draft.commitVersion(),
                    draft.eventSeq(),draft.snapshotJson(),draft.sha256());
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
                draft.snapshotJson(),draft.sha256());
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
                        "command:"+commandId,"SIDE_EFFECT_UNKNOWN","RECOVERY","command-timeout",commandDispatchId(tenantId,attemptId),evidenceJson);
                jdbc.update("""
                    INSERT INTO workflow_runtime.runtime_audit
                      (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                    VALUES (? ,?,'SYSTEM','runtime','COMMAND_MARKED_UNKNOWN','COMMAND',?,?::jsonb,'ambiguous external side effect')
                    """,tenantId,runId,commandId,evidenceJson);
                appendEvents(tenantId,runId,List.of(new EventDraft(
                        "COMMAND_MARKED_UNKNOWN","COMMAND",commandId,null,null,null,evidenceJson)));
            }
            return null;
        });
    }

    public record WorkerRecoveryResult(String outcome, UUID interventionId) {}
    public record InterruptedAttempt(UUID tenantId, UUID runId, UUID attemptId) {}
    public record OutboxRestartResult(int safelyRequeued, int leftUncertain) {}

    /** Requeue only SENDING messages whose external side-effect boundary was not crossed. */
    public OutboxRestartResult recoverOutboxAfterRestart() {
        return required(() -> {
            int workerRequeued=jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox o SET outbox_state='PENDING'
                 WHERE o.outbox_state='SENDING' AND o.message_kind='WORKER_DISPATCH'
                   AND EXISTS (
                     SELECT 1 FROM workflow_runtime.node_attempt a
                      WHERE a.tenant_id=o.tenant_id
                        AND a.attempt_id::text=o.payload->>'attemptId'
                        AND a.attempt_state='DISPATCHED'
                   )
                """);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox o SET outbox_state='DEAD_LETTER'
                 WHERE o.outbox_state='SENDING' AND o.message_kind='WORKER_DISPATCH'
                   AND NOT EXISTS (
                     SELECT 1 FROM workflow_runtime.node_attempt a
                      WHERE a.tenant_id=o.tenant_id
                        AND a.attempt_id::text=o.payload->>'attemptId'
                        AND a.attempt_state='DISPATCHED'
                   )
                """);
            int otherRequeued=jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='PENDING'
                 WHERE outbox_state='SENDING'
                   AND message_kind NOT IN ('DEVICE_COMMAND','WORKER_DISPATCH')
                """);
            int commandRequeued=jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox o SET outbox_state='PENDING'
                 WHERE o.outbox_state='SENDING' AND o.message_kind='DEVICE_COMMAND'
                   AND EXISTS (
                     SELECT 1 FROM workflow_runtime.command_ledger c
                      WHERE c.tenant_id=o.tenant_id
                        AND c.command_id::text=o.payload->>'commandId'
                        AND c.command_state IN ('CREATED','DISPATCH_PENDING')
                   )
                """);
            Integer uncertain=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.runtime_outbox o
                 WHERE o.outbox_state='SENDING' AND o.message_kind='DEVICE_COMMAND'
                   AND EXISTS (
                     SELECT 1 FROM workflow_runtime.command_ledger c
                      WHERE c.tenant_id=o.tenant_id
                        AND c.command_id::text=o.payload->>'commandId'
                        AND c.command_state IN ('SEND_STARTED','ACKNOWLEDGED','RUNNING',
                                                'DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
                   )
                """,Integer.class);
            return new OutboxRestartResult(workerRequeued+otherRequeued+commandRequeued,
                    uncertain==null?0:uncertain);
        });
    }

    /** Startup candidates that were in flight (including CREATED reservations) when the sole Host stopped. */
    public List<InterruptedAttempt> findInterruptedAttempts(int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Recovery batch limit must be 1..1000");
        return jdbc.query("""
            SELECT tenant_id,run_id,attempt_id FROM workflow_runtime.node_attempt
             WHERE attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED')
             ORDER BY COALESCE(started_at,created_at),tenant_id,run_id,attempt_id LIMIT ?
            """,(rs,n)->new InterruptedAttempt(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)),limit);
    }


    /**
     * Reconcile an interrupted Attempt during the sole Host's startup.
     * Absence of a process-local Worker handle is not proof of absence of a
     * device side effect; the durable command ledger is authoritative.
     */
    public WorkerRecoveryResult recoverInterruptedAttempt(UUID tenantId, UUID runId,
                                                            UUID attemptId, String recoveryActor) {
        return required(() -> {
            RunHead head=lockRun(tenantId,runId);
            List<UUID> activationIds=jdbc.query("""
                SELECT activation_id FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """,(rs,n)->rs.getObject(1,UUID.class),tenantId,runId,attemptId);
            if(activationIds.size()!=1) return new WorkerRecoveryResult("NOT_RECOVERABLE",null);
            UUID activationId=activationIds.get(0);
            UUID tokenId=jdbc.queryForObject("""
                SELECT token_id FROM workflow_runtime.node_activation
                 WHERE tenant_id=? AND activation_id=?
                """,UUID.class,tenantId,activationId);
            // Keep the documented single-Run lock order: Run -> Token -> Activation -> Attempt.
            jdbc.queryForObject("SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=? FOR UPDATE",
                    UUID.class,tenantId,tokenId);
            jdbc.queryForObject("SELECT activation_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=? FOR UPDATE",
                    UUID.class,tenantId,activationId);
            List<String> attemptStates=jdbc.query("""
                SELECT attempt_state FROM workflow_runtime.node_attempt
                 WHERE tenant_id=? AND run_id=? AND attempt_id=? FOR UPDATE
                """,(rs,n)->rs.getString(1),tenantId,runId,attemptId);
            String interruptedState=attemptStates.isEmpty()?null:attemptStates.get(0);
            if(!List.of("CREATED","DISPATCHED","ACCEPTED","STARTED").contains(interruptedState))
                return new WorkerRecoveryResult("NOT_RECOVERABLE",null);
            Integer sideEffects=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.worker_execution_fact
                 WHERE tenant_id=? AND attempt_id=? AND fact_type IN
                   ('SIDE_EFFECT_STARTED','SIDE_EFFECT_COMPLETED','SIDE_EFFECT_UNKNOWN')
                """,Integer.class,tenantId,attemptId);
            Integer ambiguousCommands=jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.command_ledger
                 WHERE tenant_id=? AND attempt_id=? AND command_state IN
                   ('SEND_STARTED','ACKNOWLEDGED','RUNNING','DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')
                """,Integer.class,tenantId,attemptId);
            if((sideEffects==null||sideEffects==0) && (ambiguousCommands==null||ambiguousCommands==0)) {
                jdbc.update("""
                    UPDATE workflow_runtime.command_ledger SET command_state='CANCELLED',
                           version=version+1,completed_at=clock_timestamp(),updated_at=clock_timestamp()
                     WHERE tenant_id=? AND attempt_id=? AND command_state IN ('CREATED','DISPATCH_PENDING')
                    """,tenantId,attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                     WHERE tenant_id=? AND outbox_state IN ('PENDING','SENDING')
                       AND payload->>'commandId' IN
                         (SELECT command_id::text FROM workflow_runtime.command_ledger
                           WHERE tenant_id=? AND attempt_id=?)
                    """,tenantId,tenantId,attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.node_attempt SET attempt_state='ABANDONED_SAFE',
                           attempt_version=attempt_version+1,finished_at=clock_timestamp()
                     WHERE tenant_id=? AND attempt_id=? AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED')
                    """,tenantId,attemptId);
                jdbc.update("""
                    UPDATE workflow_runtime.node_activation SET activation_state='ABANDONED_SAFE',
                           activation_version=activation_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND activation_id=? AND activation_state IN ('RESERVED','RUNNING')
                    """,tenantId,activationId);
                jdbc.update("""
                    UPDATE workflow_runtime.execution_token SET token_state='READY',
                           token_version=token_version+1,updated_at=clock_timestamp()
                     WHERE tenant_id=? AND token_id=? AND token_state IN ('RESERVED','RUNNING')
                    """,tenantId,tokenId);
                jdbc.update("""
                    UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                     WHERE tenant_id=? AND message_kind='WORKER_DISPATCH'
                       AND outbox_state IN ('PENDING','SENDING')
                       AND payload->>'attemptId'=?
                    """,tenantId,attemptId.toString());
                releaseReservationInTransaction(tenantId,attemptId);
                appendEvents(tenantId,runId,List.of(new EventDraft(
                        "ATTEMPT_ABANDONED_SAFE","ATTEMPT",attemptId,null,null,null,
                        "{"+(char)34+"previousState"+(char)34+":"+(char)34+interruptedState+(char)34+"}")));
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
            UUID dispatchId=commandDispatchId(tenantId,attemptId);
            if(sideEffects==null || sideEffects==0) {
                appendWorkerFactInTransaction(tenantId,runId,attemptId,"recovery-unknown","SIDE_EFFECT_UNKNOWN",
                        "RECOVERY",recoveryActor,dispatchId,"{\"reason\":\"command-send-boundary-crossed\"}");
            }
            jdbc.update("""
                UPDATE workflow_runtime.node_attempt SET attempt_state='UNKNOWN',
                       attempt_version=attempt_version+1
                 WHERE tenant_id=? AND attempt_id=? AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN')
                """,tenantId,attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER'
                 WHERE tenant_id=? AND message_kind='WORKER_DISPATCH'
                   AND outbox_state IN ('PENDING','SENDING')
                   AND payload->>'attemptId'=?
                """,tenantId,attemptId.toString());
            UUID interventionId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO workflow_runtime.manual_intervention
                  (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,reason_code,evidence)
                VALUES (?,?,?,'ATTEMPT',?,'OPEN','INTERRUPTED_AFTER_SIDE_EFFECT_BOUNDARY',
                        jsonb_build_object('attemptId',?::text))
                """,tenantId,interventionId,runId,attemptId,attemptId);
            jdbc.update("""
                UPDATE workflow_runtime.workflow_run SET status='MANUAL_REQUIRED',
                       control_gate='CLOSED',recovery_state='BLOCKED',
                       terminal_reason='Runtime restarted after an uncertain external side effect',
                       updated_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=?
                   AND status NOT IN ('SUCCEEDED','FAILED','STOPPED')
                """,tenantId,runId);
            jdbc.update("""
                INSERT INTO workflow_runtime.runtime_audit
                  (tenant_id,run_id,actor_kind,actor_id,action,subject_kind,subject_id,after_state,reason)
                VALUES (?,?,'SYSTEM',?,'ATTEMPT_RECOVERY_BLOCKED','ATTEMPT',?,?::jsonb,
                        'physical side effect requires command query or operator verification')
                """,tenantId,runId,recoveryActor,attemptId,
                    "{\"attemptId\":\""+attemptId+"\"}");
            appendEvents(tenantId,runId,List.of(new EventDraft("ATTEMPT_RECOVERY_UNKNOWN",
                    "ATTEMPT",attemptId,null,null,null,"{\"interventionId\":\""+interventionId+"\"}")));
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
                appendEvents(tenantId,runId,List.of(new EventDraft("INTERVENTION_DECIDED",
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
