package com.labos.kernel.workflow.runtime.command;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    /** Last tenant built by newStartedChain(); cleaned in @AfterEach to avoid cross-test pollution. */
    private UUID lastTenant;

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void assumePg() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
    }

    /**
     * These tests intentionally leave device commands UNKNOWN / outbox SENDING / Run MANUAL_REQUIRED.
     * Settle this test's tenant so it cannot pollute OTHER tests that run a global (non-tenant-scoped)
     * recoverAtStartup() and assert a global leftUncertain / intervention count on a shared PG17 DB.
     */
    @AfterEach
    void settleTestTenant() throws Exception {
        if (lastTenant == null) return;
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            UUID t = lastTenant;
            st.executeUpdate("UPDATE workflow_runtime.runtime_outbox SET outbox_state='DEAD_LETTER' WHERE tenant_id='"
                    + t + "' AND outbox_state IN ('PENDING','SENDING')");
            st.executeUpdate("UPDATE workflow_runtime.manual_intervention SET intervention_state='CANCELLED' WHERE tenant_id='"
                    + t + "' AND intervention_state='OPEN'");
            st.executeUpdate("UPDATE workflow_runtime.workflow_run SET status='FAILED', control_gate='CLOSED', recovery_state='RECOVERY_COMPLETED' WHERE tenant_id='"
                    + t + "' AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','MANUAL_REQUIRED')");
            // MANUAL_REQUIRED runs from recovery tests are settled explicitly too.
            st.executeUpdate("UPDATE workflow_runtime.workflow_run SET status='FAILED', control_gate='CLOSED', recovery_state='RECOVERY_COMPLETED' WHERE tenant_id='"
                    + t + "' AND status='MANUAL_REQUIRED'");
            // Global recoverAtStartup() scans node_attempt by state and does NOT look at Run status.
            // Settle every in-flight attempt/activation/token of this tenant, otherwise a later test
            // asserting an exact global scanned()/manualRequired() count sees our residue (order-dependent).
            st.executeUpdate("UPDATE workflow_runtime.node_attempt SET attempt_state='FAILED', finished_at=clock_timestamp() WHERE tenant_id='"
                    + t + "' AND attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED')");
            st.executeUpdate("UPDATE workflow_runtime.node_activation SET activation_state='FAILED', updated_at=clock_timestamp() WHERE tenant_id='"
                    + t + "' AND activation_state IN ('READY','RESERVED','RUNNING','WAITING')");
            st.executeUpdate("UPDATE workflow_runtime.execution_token SET token_state='FAILED', updated_at=clock_timestamp() WHERE tenant_id='"
                    + t + "' AND token_state IN ('READY','RESERVED','RUNNING','WAITING')");
            // Late-receipt scenarios leave inbox_receipt=RECEIVED (applyCommandEvent rolls back after
            // receiveInbox committed). G4-A global inbox recovery would re-drive those on a shared DB.
            st.executeUpdate("UPDATE workflow_runtime.inbox_receipt SET receipt_state='REJECTED' WHERE tenant_id='"
                    + t + "' AND receipt_state IN ('RECEIVED','PROCESSING')");
        }
        lastTenant = null;
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
        this.lastTenant = tenant;
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
    // Scenario 1: device OFFLINE reported by adapter AFTER SEND_STARTED was committed.
    // The runtime has already crossed the durable send boundary; the adapter judges no physical
    // frame was written, but the runtime cannot confirm that, so the outcome stays UNKNOWN.
    // The true pre-boundary case is scenario 6c (gate rejection, adapter never called).
    // ---------------------------------------------------------------------------------------------
    @Test
    void deviceOfflineAfterSendStarted_NoPhysicalExecutionButOutcomeUnknown() throws Exception {
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

        // A late SUCCEEDED inbox event must NOT silently flip UNKNOWN -> SUCCEEDED (fail-closed, G4-D durable REJECTED).
        var lateOutcome = svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "late-ok-" + cmdId,
                "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-1", "{\"dispensed\":60}");
        assertEquals(DeviceCommandExecutionService.InboxStatus.REJECTED, lateOutcome.status(),
                "runtime must durably reject (not silently accept) an auto-terminalizing late receipt");
        assertEquals("REJECTED", scalar(ds, "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                + chain.tenant + "' AND inbox_id='" + lateOutcome.inboxId() + "'"));
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

        // A late success with a NEW message id must not resurrect a terminal FAILED command (G4-D durable REJECTED).
        var lateOutcome = svc.ingestDeviceEvent(chain.tenant, chain.runId, DEVICE, "late-success-" + cmdId,
                "COMMAND_RESULT", cmdId, "SUCCEEDED", "seq-2", "{\"dispensed\":60}");
        assertEquals(DeviceCommandExecutionService.InboxStatus.REJECTED, lateOutcome.status(),
                "runtime must durably reject a late receipt that rewrites a terminal result");
        assertEquals("REJECTED", scalar(ds, "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id='"
                + chain.tenant + "' AND inbox_id='" + lateOutcome.inboxId() + "'"));
        assertEquals("FAILED", commandState(repo, chain, cmdId),
                "a terminal physical result must never be rewritten by a late receipt");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 6a: ordinary Outbox delivery must not re-select an UNKNOWN command.
    // NOTE: this is NOT the startup-recovery path — see scenario 6b for the real recoverAtStartup().
    // ---------------------------------------------------------------------------------------------
    @Test
    void unknownCommandIsNotReselectedByOrdinaryOutboxDelivery() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo,
                env -> DeviceCommandAdapter.SendResult.acceptedAsync());
        svc.deliverPendingCommands(chain.tenant, 4, "g4c-s6a");
        repo.markCommandUnknown(chain.tenant, cmdId, "{\"reason\":\"host_crash_in_flight\"}");

        assertTrue(repo.findUnknownCommands(chain.tenant, 10).stream()
                .anyMatch(u -> u.commandId().equals(cmdId)));

        AtomicInteger sendCalls = new AtomicInteger();
        DeviceCommandExecutionService restartSvc = new DeviceCommandExecutionService(repo, env -> {
            sendCalls.incrementAndGet();
            return DeviceCommandAdapter.SendResult.acceptedAsync();
        });
        restartSvc.deliverPendingCommands(chain.tenant, 4, "g4c-s6a-restart");
        assertEquals(0, sendCalls.get(), "ordinary delivery must not resend an UNKNOWN command");
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId));
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 6b (P2-1 fix): REAL startup recovery after crossing SEND_STARTED.
    // Verifies the full recovery chain: command UNKNOWN, dispatch DELIVERY_UNKNOWN, attempt UNKNOWN,
    // Run MANUAL_REQUIRED, one OPEN manual intervention, and outbox left non-requeueable.
    // ---------------------------------------------------------------------------------------------
    @Test
    void realCrashWindowAfterSendStarted_RecoverAtStartupConvergesToManualRequired() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        // ---- Simulate the REAL crash window: SEND_STARTED durably committed, process dies
        //      BEFORE adapter.send() is invoked/returns. No exception handling runs, so the
        //      command must still be SEND_STARTED (NOT already UNKNOWN), outbox SENDING,
        //      attempt STARTED, Run RUNNING, SIDE_EFFECT_STARTED present, no COMPLETED fact.
        var claimed = repo.beginOutboxSendForKind(chain.tenant, "DEVICE_COMMAND", 4);
        assertEquals(1, claimed.size());
        var outbox = claimed.get(0);
        long version = repo.loadCommand(chain.tenant, cmdId).orElseThrow().version();
        boolean crossed = repo.markCommandSendStarted(
                chain.tenant, cmdId, version, outbox.outboxId(), UUID.randomUUID(), 1, "g4c-crash-dispatcher");
        assertTrue(crossed, "SEND_STARTED must commit before the crash");

        // Assert the exact pre-crash durable snapshot the reviewer asked for.
        assertEquals("SEND_STARTED", commandState(repo, chain, cmdId),
                "command must remain SEND_STARTED at the crash instant, not pre-marked UNKNOWN");
        assertEquals("SENDING", outboxState(ds, cmdId));
        assertEquals("STARTED", scalar(ds, "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id='"
                + chain.tenant + "' AND attempt_id='" + chain.attemptId + "'"));
        assertEquals("RUNNING", scalar(ds, "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id='"
                + chain.tenant + "' AND run_id='" + chain.runId + "'"));
        assertEquals("SEND_STARTED", scalar(ds, "SELECT dispatch_state FROM workflow_runtime.command_dispatch_attempt WHERE tenant_id='"
                + chain.tenant + "' AND command_id='" + cmdId + "'"));
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + chain.tenant + "' AND fact_type='SIDE_EFFECT_STARTED' AND side_effect_key='command:" + cmdId + "'"));
        assertEquals(0, count(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + chain.tenant + "' AND fact_type='SIDE_EFFECT_COMPLETED' AND side_effect_key='command:" + cmdId + "'"));

        // No adapter invocation at all: the process is gone. Run the REAL startup recovery.
        var recovery = new WorkerExecutionRecoveryService(repo, new WorkerExecutionRegistry(), chain.tenant);
        WorkerExecutionRecoveryService.StartupResult result = recovery.recoverAtStartup(50, "g4c-recovery-actor");

        // Full durable state assertions — not just command_state.
        assertEquals("UNKNOWN", commandState(repo, chain, cmdId));
        assertEquals("UNKNOWN", scalar(ds, "SELECT attempt_state FROM workflow_runtime.node_attempt WHERE tenant_id='"
                + chain.tenant + "' AND attempt_id='" + chain.attemptId + "'"));
        assertEquals("MANUAL_REQUIRED", scalar(ds, "SELECT status FROM workflow_runtime.workflow_run WHERE tenant_id='"
                + chain.tenant + "' AND run_id='" + chain.runId + "'"));
        assertEquals("DELIVERY_UNKNOWN", scalar(ds, "SELECT dispatch_state FROM workflow_runtime.command_dispatch_attempt WHERE tenant_id='"
                + chain.tenant + "' AND command_id='" + cmdId + "'"));
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id='"
                + chain.tenant + "' AND run_id='" + chain.runId + "' AND intervention_state='OPEN'"));
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.runtime_audit WHERE tenant_id='"
                + chain.tenant + "' AND action='ATTEMPT_RECOVERY_BLOCKED'"));
        assertTrue(result.manualRequired() >= 1, "recovery must report MANUAL_REQUIRED for the in-flight Attempt");
        assertEquals(0, result.outbox().safelyRequeued(), "uncertain device outbox must never be requeued");

        // Post-recovery ordinary delivery still must not resend.
        AtomicInteger sendCalls = new AtomicInteger();
        DeviceCommandExecutionService postSvc = new DeviceCommandExecutionService(repo, env -> {
            sendCalls.incrementAndGet();
            return DeviceCommandAdapter.SendResult.acceptedAsync();
        });
        postSvc.deliverPendingCommands(chain.tenant, 4, "g4c-s6b-post");
        assertEquals(0, sendCalls.get(), "after real crash recovery, no physical re-send");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 6c (P2-3 fix): three DISTINCT physical-send fault points separated explicitly.
    // Point A (pre-boundary): gate rejects -> adapter never called, no SIDE_EFFECT_STARTED (this test).
    // Point B (post-boundary, before frame): SEND_STARTED committed, process dies before adapter
    //   returns -> real crash window, covered by scenario 6b (recoverAtStartup).
    // Point C (frame written, receipt lost): adapter ACCEPTED_ASYNC, receipt never ingested -> command
    //   stays SEND_STARTED until timeout marks UNKNOWN (scenario 3); no false SUCCEEDED.
    // ---------------------------------------------------------------------------------------------
    @Test
    void faultPointA_PreBoundaryOfflineHasNoSideEffectStarted() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        // The ONLY way to fail strictly BEFORE the durable boundary is markCommandSendStarted returning
        // false (gate/state guard), which the service handles before crossing. Simulate by closing the
        // gate: set control_gate=PAUSE so requireGraphRunOpen fails the pre-send guard.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE workflow_runtime.workflow_run SET control_gate='PAUSE' WHERE tenant_id='"
                    + chain.tenant + "' AND run_id='" + chain.runId + "'");
        }
        AtomicInteger adapterCalls = new AtomicInteger();
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo, env -> {
            adapterCalls.incrementAndGet();
            return DeviceCommandAdapter.SendResult.acceptedAsync();
        });
        svc.deliverPendingCommands(chain.tenant, 4, "g4c-6c-a");

        assertEquals(0, adapterCalls.get(), "pre-boundary gate rejection must not invoke adapter at all");
        assertEquals(0, count(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + chain.tenant + "' AND fact_type='SIDE_EFFECT_STARTED' AND side_effect_key='command:" + cmdId + "'"),
                "pre-boundary failure must not create any side-effect evidence");
        assertEquals("CREATED", commandState(repo, chain, cmdId),
                "pre-boundary failure leaves command CREATED (still retryable, nothing was sent)");
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 6d (P2-4 fix): two Dispatchers racing for the SAME command.
    // Only one may cross SEND_STARTED, call the adapter once, and create one dispatch/fact;
    // the loser must not requeue the already-sent command back to PENDING.
    // ---------------------------------------------------------------------------------------------
    @Test
    void concurrentDispatchers_OnlyOneCrossesSendStartedAndExecutesPhysically() throws Exception {
        assumePg();
        var ds = dataSource();
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        Chain chain = newStartedChain(ds, repo);
        UUID cmdId = createCommand(repo, chain);

        AtomicInteger adapterCalls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        DeviceCommandExecutionService svc = new DeviceCommandExecutionService(repo, env -> {
            adapterCalls.incrementAndGet();
            try { Thread.sleep(30); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return DeviceCommandAdapter.SendResult.acceptedAsync();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String dispatcher = "g4c-race-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    svc.deliverPendingCommands(chain.tenant, 4, dispatcher);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, adapterCalls.get(), "exactly one physical adapter invocation under concurrent dispatch");
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.command_dispatch_attempt WHERE tenant_id='"
                + chain.tenant + "' AND command_id='" + cmdId + "' AND dispatch_state='SEND_STARTED'"),
                "exactly one SEND_STARTED dispatch attempt");
        assertEquals(1, count(ds, "SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"
                + chain.tenant + "' AND fact_type='SIDE_EFFECT_STARTED' AND side_effect_key='command:" + cmdId + "'"),
                "exactly one SIDE_EFFECT_STARTED fact");
        assertEquals(0, count(ds, "SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE payload->>'commandId'='"
                + cmdId + "' AND outbox_state='PENDING'"),
                "losing dispatcher must not requeue an already-sent command to PENDING");
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
