package com.labos.kernel.workflow.runtime;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTransactionPort.Request;
import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTransactionPort.Result;

/**
 * Executable PostgreSQL bootstrap slice for T01/T02.
 * T03-T23 are declared in WorkflowRuntimeTransactionPort and are deliberately
 * not represented as successful no-op implementations.
 */
public final class PostgresWorkflowRuntimeBootstrapRepository {
    private final JdbcTemplate jdbc;
    private final PostgresRuntimeTransactionExecutor transactions;

    public PostgresWorkflowRuntimeBootstrapRepository(
            JdbcTemplate jdbc,
            PostgresRuntimeTransactionExecutor transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Result createRun(Request request) {
        Objects.requireNonNull(request, "request");
        return transactions.write(() -> {
            long tenantId = number(request, "tenantId");
            String definitionId = required(request, "definitionId");
            String definitionVersion = required(request, "definitionVersion");
            String definitionHash = required(request, "definitionHash");
            String compilerVersion = required(request, "compilerVersion");
            String initialNodeId = required(request, "initialNodeId");
            String initialVariablesJson = optional(request, "initialVariablesJson", "{}");
            UUID initialTokenId = uuid(request, "initialTokenId", UUID.randomUUID());

            List<Map<String, Object>> existing = jdbc.queryForList("""
                    SELECT run_id, request_payload_hash, run_commit_version, event_seq
                    FROM wf_workflow_run
                    WHERE tenant_id=? AND request_id=?
                    """, tenantId, request.requestId());

            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.get(0);
                UUID existingRunId = (UUID) row.get("run_id");
                String originalHash = String.valueOf(row.get("request_payload_hash")).trim();
                long version = ((Number) row.get("run_commit_version")).longValue();
                long eventSeq = ((Number) row.get("event_seq")).longValue();
                if (originalHash.equals(request.payloadHash())) {
                    return new Result(existingRunId, "REPLAYED", version, eventSeq, eventSeq, true);
                }
                jdbc.update("""
                        INSERT INTO wf_protocol_violation
                          (violation_id,run_id,source,message_id,original_payload_hash,
                           conflicting_payload_hash,raw_payload)
                        VALUES (?,?,'T01',?,?,?,jsonb_build_object('requestId',?,'incomingRunId',?::text))
                        ON CONFLICT (source,message_id,conflicting_payload_hash) DO NOTHING
                        """,
                        UUID.randomUUID(), existingRunId, request.requestId(), originalHash,
                        request.payloadHash(), request.requestId(), request.runId());
                return new Result(existingRunId, "PROTOCOL_VIOLATION", version, eventSeq, eventSeq, true);
            }

            jdbc.update("""
                    INSERT INTO wf_workflow_run
                      (run_id,tenant_id,request_id,request_payload_hash,
                       definition_id,definition_version,definition_hash,compiler_version,
                       status,run_commit_version,event_seq,dispatch_gate_open,initial_variables)
                    VALUES (?,?,?,?,?,?,?,?,'CREATED',1,1,false,?::jsonb)
                    """,
                    request.runId(), tenantId, request.requestId(), request.payloadHash(),
                    definitionId, definitionVersion, definitionHash, compilerVersion,
                    initialVariablesJson);

            jdbc.update("""
                    INSERT INTO wf_execution_token
                      (token_id,run_id,current_node_id,status)
                    VALUES (?,?,?,'CREATED')
                    """, initialTokenId, request.runId(), initialNodeId);

            jdbc.update("""
                    INSERT INTO wf_runtime_event
                      (event_id,run_id,event_seq,run_commit_version,event_type,
                       aggregate_type,aggregate_id,message_id,payload)
                    VALUES (?, ?, 1, 1, 'RunCreated', 'Run', ?, ?, 
                            jsonb_build_object('requestId',?,'definitionHash',?))
                    """,
                    UUID.randomUUID(), request.runId(), request.runId(), request.requestId(),
                    request.requestId(), definitionHash);

            return new Result(request.runId(), "CREATED", 1, 1, 1, false);
        });
    }

    public Result startRun(Request request) {
        Objects.requireNonNull(request, "request");
        return transactions.write(() -> {
            Map<String, Object> run = jdbc.queryForMap("""
                    SELECT status,run_commit_version,event_seq,coordinator_epoch
                    FROM wf_workflow_run WHERE run_id=? FOR UPDATE
                    """, request.runId());

            String status = String.valueOf(run.get("status"));
            long version = ((Number) run.get("run_commit_version")).longValue();
            long eventSeq = ((Number) run.get("event_seq")).longValue();
            long epoch = ((Number) run.get("coordinator_epoch")).longValue();

            if (epoch != request.coordinatorEpoch()) {
                throw new IllegalStateException("T02 coordinator epoch mismatch");
            }
            if ("RUNNING".equals(status)) {
                return new Result(request.runId(), "ALREADY_STARTED", version, eventSeq, eventSeq, true);
            }
            if (!"CREATED".equals(status)) {
                throw new IllegalStateException("T02 requires CREATED Run, actual=" + status);
            }
            if (request.expectedRunCommitVersion() != version) {
                throw new IllegalStateException("T02 Run version mismatch");
            }

            int tokenRows = jdbc.update("""
                    UPDATE wf_execution_token
                    SET status='READY',object_version=object_version+1,updated_at=clock_timestamp()
                    WHERE run_id=? AND status='CREATED'
                    """, request.runId());
            if (tokenRows != 1) {
                throw new IllegalStateException("T02 requires exactly one initial CREATED Token, rows=" + tokenRows);
            }

            int runRows = jdbc.update("""
                    UPDATE wf_workflow_run
                    SET status='RUNNING',dispatch_gate_open=true,
                        run_commit_version=run_commit_version+1,event_seq=event_seq+1,
                        started_at=clock_timestamp(),updated_at=clock_timestamp()
                    WHERE run_id=? AND status='CREATED'
                      AND run_commit_version=? AND coordinator_epoch=?
                    """, request.runId(), version, epoch);
            if (runRows != 1) {
                throw new IllegalStateException("T02 Run CAS failed");
            }

            long nextVersion = version + 1;
            long nextEventSeq = eventSeq + 1;
            jdbc.update("""
                    INSERT INTO wf_runtime_event
                      (event_id,run_id,event_seq,run_commit_version,event_type,
                       aggregate_type,aggregate_id,message_id,payload)
                    VALUES (?,?,?,?, 'RunStarted','Run',?,?, '{}'::jsonb)
                    """,
                    UUID.randomUUID(), request.runId(), nextEventSeq, nextVersion,
                    request.runId(), request.messageId());

            return new Result(request.runId(), "STARTED", nextVersion,
                    nextEventSeq, nextEventSeq, false);
        });
    }

    private static String required(Request request, String key) {
        Object value = request.payload().get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("T01 missing payload field: " + key);
        }
        return String.valueOf(value);
    }

    private static String optional(Request request, String key, String fallback) {
        Object value = request.payload().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static long number(Request request, String key) {
        Object value = request.payload().get(key);
        if (!(value instanceof Number n)) throw new IllegalArgumentException("T01 numeric field required: " + key);
        return n.longValue();
    }

    private static UUID uuid(Request request, String key, UUID fallback) {
        Object value = request.payload().get(key);
        return value == null ? fallback : UUID.fromString(String.valueOf(value));
    }
}
