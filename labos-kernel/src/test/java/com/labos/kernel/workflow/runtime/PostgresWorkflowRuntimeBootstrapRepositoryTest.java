package com.labos.kernel.workflow.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostgresWorkflowRuntimeBootstrapRepositoryTest {
    private record Fixture(PostgresWorkflowRuntimeBootstrapRepository repository, JdbcTemplate jdbc) {}

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
        return new Fixture(new PostgresWorkflowRuntimeBootstrapRepository(
                jdbc, new PostgresRuntimeTransactionExecutor(new DataSourceTransactionManager(ds))), jdbc);
    }

    private String hash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private void insertDefinition(JdbcTemplate jdbc, String definitionId, String version,
                                  String definitionHash, String compilerVersion) {
        jdbc.update("""
                INSERT INTO wf_workflow_definition_version
                  (tenant_id,definition_id,definition_version,definition_hash,compiler_version,
                   definition_payload,graph_plan_payload)
                VALUES (42,?,?,?,?,'{}'::jsonb,'{}'::jsonb)
                """, definitionId, version, definitionHash, compilerVersion);
    }

    @Test
    void t01CreateRunIsIdempotentAndDetectsPayloadConflict() {
        Fixture f = fixture();
        String definitionId = "definition-" + UUID.randomUUID();
        String definitionVersion = "1";
        String definitionHash = hash();
        String compilerVersion = "compiler-v1";
        insertDefinition(f.jdbc(), definitionId, definitionVersion, definitionHash, compilerVersion);

        UUID runId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "tenantId", 42L,
                "definitionId", definitionId,
                "definitionVersion", definitionVersion,
                "definitionHash", definitionHash,
                "compilerVersion", compilerVersion,
                "initialNodeId", "start",
                "initialVariablesJson", "{}"
        );
        var request = new WorkflowRuntimeTransactionPort.Request(
                runId, "create-" + UUID.randomUUID(), "message-1", hash(), 0, 0, payload);

        var first = f.repository().createRun(request);
        assertEquals("CREATED", first.outcome());
        assertEquals(runId, first.runId());
        assertEquals(1, first.runCommitVersion());
        assertEquals(1, first.lastEventSeq());

        var replay = f.repository().createRun(request);
        assertEquals("REPLAYED", replay.outcome());
        assertEquals(runId, replay.runId());
        assertTrue(replay.replayed());

        var conflict = new WorkflowRuntimeTransactionPort.Request(
                UUID.randomUUID(), request.requestId(), "message-2", hash(), 0, 0, payload);
        var rejected = f.repository().createRun(conflict);
        assertEquals("PROTOCOL_VIOLATION", rejected.outcome());
        assertEquals(runId, rejected.runId());

        assertEquals(1L, f.jdbc().queryForObject(
                "SELECT count(*) FROM wf_workflow_run WHERE run_id=?", Long.class, runId));
        assertEquals(1L, f.jdbc().queryForObject(
                "SELECT count(*) FROM wf_execution_token WHERE run_id=?", Long.class, runId));
        assertEquals(1L, f.jdbc().queryForObject(
                "SELECT count(*) FROM wf_runtime_event WHERE run_id=?", Long.class, runId));
        assertEquals(1L, f.jdbc().queryForObject(
                "SELECT count(*) FROM wf_protocol_violation WHERE run_id=?", Long.class, runId));
    }

    @Test
    void t02StartRunAtomicallyOpensGateAndMakesInitialTokenReady() {
        Fixture f = fixture();
        String definitionId = "definition-" + UUID.randomUUID();
        String definitionVersion = "1";
        String definitionHash = hash();
        String compilerVersion = "compiler-v1";
        insertDefinition(f.jdbc(), definitionId, definitionVersion, definitionHash, compilerVersion);
        UUID runId = UUID.randomUUID();
        var create = new WorkflowRuntimeTransactionPort.Request(
                runId, "create-" + UUID.randomUUID(), "create-message", hash(), 0, 0,
                Map.of("tenantId", 42L, "definitionId", definitionId,
                        "definitionVersion", definitionVersion, "definitionHash", definitionHash,
                        "compilerVersion", compilerVersion, "initialNodeId", "start"));
        f.repository().createRun(create);

        var start = new WorkflowRuntimeTransactionPort.Request(
                runId, "start-" + UUID.randomUUID(), "start-message", hash(), 1, 0, Map.of());
        var result = f.repository().startRun(start);
        assertEquals("STARTED", result.outcome());
        assertEquals(2, result.runCommitVersion());
        assertEquals(2, result.lastEventSeq());

        assertEquals("RUNNING", f.jdbc().queryForObject(
                "SELECT status FROM wf_workflow_run WHERE run_id=?", String.class, runId));
        assertTrue(Boolean.TRUE.equals(f.jdbc().queryForObject(
                "SELECT dispatch_gate_open FROM wf_workflow_run WHERE run_id=?", Boolean.class, runId)));
        assertEquals("READY", f.jdbc().queryForObject(
                "SELECT status FROM wf_execution_token WHERE run_id=?", String.class, runId));
        assertEquals(2L, f.jdbc().queryForObject(
                "SELECT count(*) FROM wf_runtime_event WHERE run_id=?", Long.class, runId));

        var replay = f.repository().startRun(start);
        assertEquals("ALREADY_STARTED", replay.outcome());
        assertTrue(replay.replayed());
        assertEquals(2L, f.jdbc().queryForObject(
                "SELECT count(*) FROM wf_runtime_event WHERE run_id=?", Long.class, runId));
    }
}
