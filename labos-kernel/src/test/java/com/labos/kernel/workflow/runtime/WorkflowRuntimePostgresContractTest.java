package com.labos.kernel.workflow.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL contract tests. Set LABOS_RUNTIME_TEST_URL and apply
 * pg_rebuild/schema/100_workflow_runtime_v1.sql into LABOS_RUNTIME_TEST_SCHEMA
 * before running. The suite is skipped, not silently mocked, when no DB is set.
 */
class WorkflowRuntimePostgresContractTest {
    private record Db(String url, String user, String password, String schema) {}

    private Db db() {
        String url = System.getenv("LABOS_RUNTIME_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "PostgreSQL test DB not configured");
        String schema = System.getenv().getOrDefault("LABOS_RUNTIME_TEST_SCHEMA", "public");
        Assumptions.assumeTrue(schema.matches("[A-Za-z_][A-Za-z0-9_]*"), "invalid test schema");
        return new Db(url, System.getenv().getOrDefault("LABOS_RUNTIME_TEST_USER", ""),
                System.getenv().getOrDefault("LABOS_RUNTIME_TEST_PASSWORD", ""), schema);
    }

    private Connection connect(Db db) throws SQLException {
        Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO \"" + db.schema() + "\"");
        }
        return c;
    }

    private UUID createRun(Connection c) throws SQLException {
        UUID runId = UUID.randomUUID();
        String definitionId = "test-" + UUID.randomUUID();
        String definitionVersion = "1";
        String hash = "a".repeat(64);
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO wf_workflow_definition_version
                  (tenant_id, definition_id, definition_version, definition_hash,
                   compiler_version, definition_payload, graph_plan_payload)
                VALUES (1, ?, ?, ?, 'test-compiler', '{}'::jsonb, '{}'::jsonb)
                """)) {
            p.setString(1, definitionId);
            p.setString(2, definitionVersion);
            p.setString(3, hash);
            p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO wf_workflow_run
                  (run_id, tenant_id, request_id, definition_id, definition_version,
                   definition_hash, compiler_version, status)
                VALUES (?, 1, ?, ?, ?, ?, 'test-compiler', 'RUNNING')
                """)) {
            p.setObject(1, runId);
            p.setString(2, "request-" + UUID.randomUUID());
            p.setString(3, definitionId);
            p.setString(4, definitionVersion);
            p.setString(5, hash);
            p.executeUpdate();
        }
        return runId;
    }

    @Test
    void runRequestIdentityIsUnique() throws Exception {
        Db db = db();
        try (Connection c = connect(db)) {
            UUID runId = createRun(c);
            String requestId;
            try (PreparedStatement q = c.prepareStatement("SELECT request_id FROM wf_workflow_run WHERE run_id=?")) {
                q.setObject(1, runId);
                try (var rs = q.executeQuery()) { rs.next(); requestId = rs.getString(1); }
            }
            UUID duplicateRun = UUID.randomUUID();
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO wf_workflow_run
                      (run_id, tenant_id, request_id, definition_id, definition_version,
                       definition_hash, compiler_version, status)
                    SELECT ?, tenant_id, request_id, definition_id, definition_version,
                           definition_hash, compiler_version, 'CREATED'
                    FROM wf_workflow_run WHERE run_id=?
                    """)) {
                p.setObject(1, duplicateRun);
                p.setObject(2, runId);
                assertThrows(SQLException.class, p::executeUpdate);
            }
        }
    }

    @Test
    void oneDeviceCommandPerAttemptIsEnforcedByUniqueConstraint() throws Exception {
        Db db = db();
        try (Connection c = connect(db)) {
            UUID run = createRun(c), token = UUID.randomUUID(), activation = UUID.randomUUID(), attempt = UUID.randomUUID();
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO wf_execution_token(token_id,run_id,current_node_id,status)
                    VALUES (?,?,'device-node','IN_FLIGHT')
                    """)) { p.setObject(1, token); p.setObject(2, run); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO wf_node_activation(activation_id,run_id,token_id,node_id,status)
                    VALUES (?,?,?,'device-node','ACTIVE')
                    """)) { p.setObject(1, activation); p.setObject(2, run); p.setObject(3, token); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO wf_node_attempt(attempt_id,run_id,activation_id,attempt_no,status,
                      dispatch_id,side_effect_class)
                    VALUES (?,?,?,1,'STARTED',?,'EXTERNAL')
                    """)) { p.setObject(1, attempt); p.setObject(2, run); p.setObject(3, activation);
                p.setObject(4, UUID.randomUUID()); p.executeUpdate(); }
            insertCommand(c, run, activation, attempt);
            assertThrows(SQLException.class, () -> insertCommand(c, run, activation, attempt));
        }
    }

    private void insertCommand(Connection c, UUID run, UUID activation, UUID attempt) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO wf_command_ledger(command_id,run_id,activation_id,attempt_id,
                  device_id,operation,payload,payload_hash,status)
                VALUES (?,?,?,?, 'device-1','test-op','{}'::jsonb,?,'CREATED')
                """)) {
            p.setObject(1, UUID.randomUUID());
            p.setObject(2, run);
            p.setObject(3, activation);
            p.setObject(4, attempt);
            p.setString(5, "b".repeat(64));
            p.executeUpdate();
        }
    }

    @Test
    void waitingTokenRequiresWaitOwner() throws Exception {
        Db db = db();
        try (Connection c = connect(db)) {
            UUID run = createRun(c);
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO wf_execution_token(token_id,run_id,current_node_id,status)
                    VALUES (?,?,'wait-node','WAITING')
                    """)) {
                p.setObject(1, UUID.randomUUID());
                p.setObject(2, run);
                assertThrows(SQLException.class, p::executeUpdate);
            }
        }
    }

    @Test
    void onlyOneActiveLeaseCanOwnAResource() throws Exception {
        Db db = db();
        try (Connection c = connect(db)) {
            UUID run = createRun(c);
            insertLease(c, run, UUID.randomUUID());
            assertThrows(SQLException.class, () -> insertLease(c, run, UUID.randomUUID()));
        }
    }

    private void insertLease(Connection c, UUID run, UUID lease) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO wf_resource_lease(lease_id,run_id,resource_id,owner_type,owner_id,status)
                VALUES (?,?,'resource-shared','ATTEMPT',?,'ACQUIRED')
                """)) {
            p.setObject(1, lease);
            p.setObject(2, run);
            p.setObject(3, UUID.randomUUID());
            p.executeUpdate();
        }
    }

    @Test
    void repeatableReadCheckpointUsesOneMvccSnapshot() throws Exception {
        Db db = db();
        UUID runId;
        try (Connection setup = connect(db)) { runId = createRun(setup); }

        try (Connection reader = connect(db); Connection writer = connect(db)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long before;
            try (PreparedStatement p = reader.prepareStatement("SELECT run_commit_version FROM wf_workflow_run WHERE run_id=?")) {
                p.setObject(1, runId);
                try (var rs = p.executeQuery()) { rs.next(); before = rs.getLong(1); }
            }

            writer.setAutoCommit(false);
            try (PreparedStatement p = writer.prepareStatement("""
                    UPDATE wf_workflow_run
                    SET run_commit_version=run_commit_version+1, event_seq=event_seq+1
                    WHERE run_id=?
                    """)) {
                p.setObject(1, runId);
                assertEquals(1, p.executeUpdate());
            }
            writer.commit();

            long after;
            try (PreparedStatement p = reader.prepareStatement("SELECT run_commit_version FROM wf_workflow_run WHERE run_id=?")) {
                p.setObject(1, runId);
                try (var rs = p.executeQuery()) { rs.next(); after = rs.getLong(1); }
            }
            assertEquals(before, after, "REPEATABLE READ must retain the original MVCC snapshot");
            reader.commit();
        }
    }

    @Test
    void pauseRequestWinsAgainstLaterDispatchCommitThroughRunRowLock() throws Exception {
        Db db = db();
        UUID runId;
        try (Connection setup = connect(db)) { runId = createRun(setup); }

        CountDownLatch updaterStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection coordinator = connect(db)) {
            coordinator.setAutoCommit(false);
            try (PreparedStatement lock = coordinator.prepareStatement(
                    "SELECT run_id FROM wf_workflow_run WHERE run_id=? FOR UPDATE")) {
                lock.setObject(1, runId);
                try (var rs = lock.executeQuery()) { assertTrue(rs.next()); }
            }
            Future<Integer> dispatchUpdate = pool.submit(() -> {
                try (Connection dispatcher = connect(db);
                     PreparedStatement p = dispatcher.prepareStatement("""
                       UPDATE wf_workflow_run SET run_commit_version=run_commit_version+1
                       WHERE run_id=? AND status='RUNNING' AND dispatch_gate_open=true
                       """)) {
                    dispatcher.setAutoCommit(false);
                    p.setObject(1, runId);
                    updaterStarted.countDown();
                    int changed = p.executeUpdate();
                    dispatcher.commit();
                    return changed;
                }
            });
            assertTrue(updaterStarted.await(5, TimeUnit.SECONDS));
            try (PreparedStatement pause = coordinator.prepareStatement("""
                    UPDATE wf_workflow_run
                    SET status='PAUSE_REQUESTED', dispatch_gate_open=false,
                        run_commit_version=run_commit_version+1
                    WHERE run_id=?
                    """)) {
                pause.setObject(1, runId);
                assertEquals(1, pause.executeUpdate());
            }
            coordinator.commit();
            assertEquals(0, dispatchUpdate.get(10, TimeUnit.SECONDS),
                    "Dispatch Commit must fail after Pause Request wins the Run row lock");
        } finally {
            pool.shutdownNow();
        }
    }
}
