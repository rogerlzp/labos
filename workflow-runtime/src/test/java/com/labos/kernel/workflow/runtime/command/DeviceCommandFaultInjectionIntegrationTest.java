package com.labos.kernel.workflow.runtime.command;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4-C fault-injection tests for the device-command state machine: disconnect before/after
 * SEND_STARTED, lost/duplicate/late receipts, UNKNOWN reconciliation, and the two core invariants:
 * (1) a physical command is never re-sent, (2) an uncertain physical outcome is never falsely
 * terminalized as SUCCEEDED/FAILED.
 *
 * <p>All faults are injected via a programmable in-process {@link DeviceCommandAdapter} and direct
 * {@link DeviceCommandExecutionService#ingestDeviceEvent} calls — no real devices, no MQTT, no
 * distributed infrastructure. Requires RUN_RUNTIME_PG_IT=1 and an initialized PG17 schema.
 */
class DeviceCommandFaultInjectionIntegrationTest {

    private static final String DEVICE = "g4c-pipettor";

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void assumePg() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                        + "/" + env("PGDATABASE", "runtime_g4c"),
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static UUID createTenant(DriverManagerDataSource ds) throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"
                    + tenant + "','g4c-" + tenant + "','g4c-fault-injection')");
        }
        return tenant;
    }

    private static String scalar(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getString(1);
        }
    }

    private static long count(DriverManagerDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    /** Minimal single-device plan; only used to satisfy createRun's graph-plan dependency. */
    private static GraphPlan plan() {
        String xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="dev_op" protocolName="Dispense" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="dev_op"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="dev_op" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        return new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("g4c-" + UUID.randomUUID(), "1", xml, java.util.Map.of()));
    }

    /** A harness holding a RUNNING Run with one STARTED device Attempt ready to create a Command. */
    private static final class Chain {
        final UUID tenant;
        final UUID runId;
        final UUID attemptId;
        final String workerId = "g4c-worker";

        Chain(UUID tenant, UUID runId, UUID attemptId) {
            this.tenant = tenant;
            this.runId = runId;
            this.attemptId = attemptId;
        }
    }

    /**
     * Directly build token -> activation -> STARTED attempt under a RUNNING/OPEN Run, mirroring the
     * durable pre-command state that a real Worker would have produced. Avoids pulling in WorkerPool.
     */
    private Chain newStartedChain(DriverManagerDataSource ds, WorkflowRuntimeRepository repo) throws Exception {
        UUID tenant = createTenant(ds);
        GraphPlan p = plan();
        var run = repo.createRun(tenant, "g4c-req-" + UUID.randomUUID(), "CREATE", "{}",
                "g4c-def-" + tenant, "1", HexFormat.of().parseHex(p.bpmnSha256()),
                p.compilerVersion(), GraphPlanCodec.toJson(p), "{}", "start");
        repo.startRun(tenant, run.runId());
        UUID tokenId = UUID.randomUUID();
        UUID activationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID dispatchId = UUID.randomUUID();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("SET LOCAL statement_timeout='5s'");
            st.executeUpdate("INSERT INTO workflow_runtime.execution_token"
                    + "(tenant_id,token_id,run_id,token_state,node_key) VALUES ('"
                    + tenant + "','" + tokenId + "','" + run.runId() + "','RUNNING','dev_op')");
            st.executeUpdate("INSERT INTO workflow_runtime.node_activation"
                    + "(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state) VALUES ('"
                    + tenant + "','" + activationId + "','" + run.runId() + "','" + tokenId
                    + "',1,'dev_op','RUNNING')");
            st.executeUpdate("INSERT INTO workflow_runtime.node_attempt"
                    + "(tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,attempt_state,side_effect_class,worker_id) VALUES ('"
                    + tenant + "','" + attemptId + "','" + run.runId() + "','" + activationId
                    + "',1,'" + dispatchId + "','STARTED','DEVICE','g4c-worker')");
        }
        return new Chain(tenant, run.runId(), attemptId);
    }

    private UUID createCommand(WorkflowRuntimeRepository repo, Chain chain) {
        return repo.createCommandForAttempt(chain.tenant, chain.runId, chain.attemptId,
                chain.workerId, DEVICE, "Dispense", "QUERYABLE", "{\"volumeUl\":60}");
    }

    private String commandState(WorkflowRuntimeRepository repo, Chain chain, UUID cmdId) {
        return repo.loadCommand(chain.tenant, cmdId).orElseThrow().commandState();
    }

    private String outboxState(DriverManagerDataSource ds, UUID cmdId) throws Exception {
        return scalar(ds, "SELECT outbox_state FROM workflow_runtime.runtime_outbox WHERE payload->>'commandId'='"
                + cmdId + "'");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 1: device OFFLINE before send -> adapter returns DELIVERY_UNCERTAIN, no physical exec.
    // This mirrors DeviceGateway.sendCommand on an unregistered/closed gateway (no TCP write happened).
    // ---------------------------------------------------------------------------------------------
    @Test
    void deviceOfflineBeforeSend_DoesNotPhysicallyExecute() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        AtomicInteger sendCalls = new AtomicInteger();
        // Gateway detects offline device and reports DELIVERY_UNCERTAIN without writing any frame.
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo, env -> {
            sendCalls.incrementAndGet();
            return DeviceCommandAdapter.SendResult.deliveryUncertain("device offline / gateway closed");
        });
        var result = svc.deliverPendingCommands(chain.tenant, 4, "g4c-s1");

        assertEquals(1, sendCalls.get(), "adapter was reached once (send attempt itself)");
        assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.MARKED_UNKNOWN,
                result.outcomes().get(cmdId));
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId));
        assertEquals("DEAD_LETTER", outboxState(ds, cmdId),
                "device offline before physical write must dead-letter the outbox (no resend)");
        assertEquals(0, count(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact"
                        + " WHERE tenant_id='" + chain.tenant + "' AND fact_type='SIDE_EFFECT_COMPLETED'"),
                "no side-effect-completed fact: adapter never confirmed a physical execution");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 2: disconnect AFTER SEND_STARTED -> command UNKNOWN, no auto-resend on redelivery.
    // ---------------------------------------------------------------------------------------------
    @Test
    void disconnectAfterSendStarted_MarksUnknownAndNeverAutoResends() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        AtomicInteger physicalSends = new AtomicInteger();
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo, env -> {
            physicalSends.incrementAndGet();
            throw new java.io.IOException("TCP reset after SEND_STARTED");
        });
        var first = svc.deliverPendingCommands(chain.tenant, 4, "g4c-s2");
        assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.MARKED_UNKNOWN,
                first.outcomes().get(cmdId));
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId));

        // A second delivery pass must not call the adapter again (no physical re-execution).
        var second = svc.deliverPendingCommands(chain.tenant, 4, "g4c-s2");
        assertEquals(1, physicalSends.get(), "physical command must not be re-sent after SEND_STARTED");
        assertTrue(second.outcomes().isEmpty(), "no further command should be delivered");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 3: device executed successfully but receipt lost -> stays UNKNOWN, never SUCCEEDED
    // until explicit T21 reconciliation.
    // ---------------------------------------------------------------------------------------------
    @Test
    void lostSuccessReceipt_StaysUnknownUntilManualReconciliation() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        // Adapter accepted async, but the device's success reply never arrives.
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo,
                env -> DeviceCommandAdapter.SendResult.acceptedAsync());
        var delivered = svc.deliverPendingCommands(chain.tenant, 4, "g4c-s3");
        assertEquals(DeviceCommandExecutionService.CommandDeliveryStatus.SENT_ASYNC,
                delivered.outcomes().get(cmdId));

        // Simulate bridge timeout -> UNKNOWN (physical outcome is actually SUCCESS but unknown to runtime).
        repo.markCommandUnknown(chain.tenant, cmdId, "{\"reason\":\"bridge_command_timeout\"}");
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId));

        // A late SUCCEEDED inbox event must NOT silently flip UNKNOWN -> SUCCEEDED (fail-closed).
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "late-ok-" + cmdId,
                        "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-1", "{\"dispensed\":60}"),
                "runtime must reject (not silently accept) an auto-terminalizing late receipt");
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId),
                "uncertain physical outcome must not be auto-terminalized as SUCCEEDED");

        // Only explicit T21 reconciliation may resolve it.
        assertTrue(svc.reconcileUnknownCommand(chain.tenant, cmdId, "SUCCEEDED", "seq-1",
                "{\"operator\":\"verified-device-log\"}", "g4c-operator"));
        assertEquals("SUCCEEDED", commandState(repo, chain, cmdId));
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 4: duplicate receipt -> DUPLICATE_REPLAY, single terminal transition.
    // ---------------------------------------------------------------------------------------------
    @Test
    void duplicateReceipt_IsIdempotent() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo,
                env -> DeviceCommandAdapter.SendResult.acceptedAsync());
        svc.deliverPendingCommands(chain.tenant, 4, "g4c-s4");

        var first = svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "result-" + cmdId,
                "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-1", "{\"dispensed\":60}");
        var dup = svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "result-" + cmdId,
                "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-1", "{\"dispensed\":60}");

        assertEquals(DeviceCommandExecutionService.InboxStatus.APPLIED, first.status());
        assertEquals(DeviceCommandExecutionService.InboxStatus.DUPLICATE_REPLAY, dup.status());
        assertEquals("SUCCEEDED", commandState(repo, chain, cmdId));
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id='"
                + chain.tenant + "' AND event_type='DEVICE_COMMAND_SUCCEEDED' AND aggregate_id='"
                + cmdId + "'"));
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 5: late receipt after a terminal FAILED -> ignored, terminal state cannot be rewritten.
    // ---------------------------------------------------------------------------------------------
    @Test
    void lateReceiptAfterTerminalFailure_DoesNotRewriteTerminalState() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo,
                env -> DeviceCommandAdapter.SendResult.acceptedAsync());
        svc.deliverPendingCommands(chain.tenant, 4, "g4c-s5");

        svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "fail-" + cmdId,
                "COMMAND_RESULT", cmdId, "FAILED", "seq-1", "{\"error\":\"E1\"}");
        assertEquals("FAILED", commandState(repo, chain, cmdId));

        // A late success with a NEW message id must not resurrect a terminal FAILED command.
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "late-success-" + cmdId,
                        "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-2", "{\"dispensed\":60}"),
                "runtime must reject a late receipt that rewrites a terminal result");
        assertEquals("FAILED", commandState(repo, chain, cmdId),
                "a terminal physical result must never be rewritten by a late receipt");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 6: UNKNOWN recovery via restart must NOT requeue the in-flight command for resend.
    // ---------------------------------------------------------------------------------------------
    @Test
    void unknownCommandRecovery_LeavesItUncertainAndDoesNotRequeue() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo, env -> {
            // Send accepted but we simulate a crash before the async receipt is processed.
            return DeviceCommandAdapter.SendResult.acceptedAsync();
        });
        svc.deliverPendingCommands(chain.tenant, 4, "g4c-s6");
        // Force the command into UNKNOWN as a crash-recovery would.
        repo.markCommandUnknown(chain.tenant, cmdId, "{\"reason\":\"host_crash_in_flight\"}");

        var unknown = repo.findUnknownCommands(chain.tenant, 10);
        assertTrue(unknown.stream().anyMatch(u -> u.commandId().equals(cmdId)));

        AtomicInteger sendCalls = new AtomicInteger();
        DeviceCommandExecutionService restartSvc = new DeviceCommandExecutionService(repo, env -> {
            sendCalls.incrementAndGet();
            return DeviceCommandAdapter.SendResult.acceptedAsync();
        });
        restartSvc.deliverPendingCommands(chain.tenant, 4, "g4c-s6-restart");
        assertEquals(0, sendCalls.get(), "restart must never resend an UNKNOWN command");
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId));
    }

    // ---------------------------------------------------------------------------------------------
    // Invariant A: across all disconnect scenarios, no more than one SIDE_EFFECT_STARTED fact exists
    // per command (single physical execution boundary).
    // ---------------------------------------------------------------------------------------------
    @Test
    void invariant_SingleSideEffectStartedPerCommand() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo, env -> {
            throw new java.io.IOException("disconnect");
        });
        // Run delivery repeatedly; even with retries the side-effect boundary is crossed once.
        for (int i = 0; i < 3; i++) svc.deliverPendingCommands(chain.tenant, 4, "g4c-inv-" + i);

        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact"
                + " WHERE tenant_id='" + chain.tenant + "' AND fact_type='SIDE_EFFECT_STARTED'"
                + " AND side_effect_key='command:" + cmdId + "'"),
                "exactly one physical side-effect boundary per command");
    }

    // ---------------------------------------------------------------------------------------------
    // Invariant B: an UNKNOWN command can only be resolved to a terminal state via T21, and a
    // conflicting payload replay is recorded as a protocol violation (not silently applied).
    // ---------------------------------------------------------------------------------------------
    @Test
    void invariant_ConflictingReceiptIsProtocolViolation() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo,
                env -> DeviceCommandAdapter.SendResult.acceptedAsync());
        svc.deliverPendingCommands(chain.tenant, 4, "g4c-inv-b");

        svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "conflict-" + cmdId,
                "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-1", "{\"dispensed\":60}");
        var conflict = svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "conflict-" + cmdId,
                "COMMAND_RESULT", cmdId, "FAILED", "seq-1", "{\"error\":\"E9\"}");

        assertEquals(DeviceCommandExecutionService.InboxStatus.PROTOCOL_VIOLATION, conflict.status());
        assertEquals("SUCCEEDED", commandState(repo, chain, cmdId),
                "conflicting replay must not alter the applied terminal state");
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.inbox_protocol_violation"
                + " WHERE tenant_id='" + chain.tenant + "'"));
    }
}
