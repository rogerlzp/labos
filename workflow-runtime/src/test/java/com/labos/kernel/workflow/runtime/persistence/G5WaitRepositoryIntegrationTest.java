package com.labos.kernel.workflow.runtime.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PG17 integration tests for G5 pure-graph Wait, Timer arbitration, Wait Inbox single-tx
 * reception/replay, completion completeness guards, rollback atomicity, and PAUSE/STOP/FAILURE Drain.
 */
class G5WaitRepositoryIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private DriverManagerDataSource dataSource() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://" + env("PGHOST", "localhost") + ":" + env("PGPORT", "5432")
                        + "/" + env("PGDATABASE", "runtime_test"),
                env("PGUSER", "runtime"), env("PGPASSWORD", "runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static GraphPlan compileMessageWithTimeoutPlan(String key, String corrKey, String durationIso) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="%s"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                      <bpmn:timerEventDefinition><bpmn:timeDuration>%s</bpmn:timeDuration></bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:endEvent id="e_to"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f_norm" sourceRef="w1" targetRef="e_ok"/>
                    <bpmn:sequenceFlow id="f_to" sourceRef="b1" targetRef="e_to"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(corrKey, durationIso);
        return new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(key, "1", xml, Map.of()));
    }

    private static GraphPlan compilePlainWaitPlan(String key, String corrKey) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements><labos:wait correlationKey="%s"/></bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f_norm" sourceRef="w1" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(corrKey);
        return new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(key, "1", xml, Map.of()));
    }

    private static GraphPlan compileTaskPlan(String key) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:serviceTask id="t1" protocolName="HTTP" resource="1"/>
                    <bpmn:endEvent id="e_ok"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e_ok"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        return new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(key, "1", xml, Map.of()));
    }

    private static UUID registerTenant(JdbcTemplate jdbc, String prefix) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runtime.tenant(tenant_id, tenant_key, display_name)
            VALUES (?, ?, ?)
            """, tenant, prefix + "-" + tenant, prefix);
        return tenant;
    }

    private record TaskActivationAttempt(UUID activationId, UUID attemptId) {}

    private static TaskActivationAttempt createTaskActivationAndAttempt(
            JdbcTemplate jdbc, UUID tenant, UUID runId, UUID tokenId, String nodeKey) {
        UUID activationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID dispatchId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runtime.node_activation
              (tenant_id, activation_id, run_id, token_id, activation_no, node_key, activation_state)
            VALUES (?, ?, ?, ?, 1, ?, 'RESERVED')
            """, tenant, activationId, runId, tokenId, nodeKey);
        jdbc.update("""
            INSERT INTO workflow_runtime.node_attempt
              (tenant_id, attempt_id, run_id, activation_id, attempt_no, dispatch_id, attempt_state)
            VALUES (?, ?, ?, ?, 1, ?, 'CREATED')
            """, tenant, attemptId, runId, activationId, dispatchId);
        jdbc.update("""
            UPDATE workflow_runtime.node_activation SET current_attempt_id=?
             WHERE tenant_id=? AND activation_id=?
            """, attemptId, tenant, activationId);
        return new TaskActivationAttempt(activationId, attemptId);
    }

    private static UUID createStartedRunAtWait(WorkflowRuntimeRepository repo, JdbcTemplate jdbc,
                                               UUID tenantId, GraphPlan plan, String waitNodeKey) throws Exception {
        String planJson = GraphPlanCodec.toJson(plan);
        var created = repo.createRun(tenantId, "req-" + UUID.randomUUID(), "CREATE",
                "{\"k\":\"" + plan.definitionKey() + "\"}",
                plan.definitionKey(), plan.definitionVersion(), sha256(planJson),
                plan.compilerVersion(), planJson, "{}", "s");
        repo.startRun(tenantId, created.runId());
        UUID startTokenId = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='s'
            """, UUID.class, tenantId, created.runId());
        repo.advanceGraphToken(tenantId, created.runId(), startTokenId, "s",
                "SEQUENCE", List.of(waitNodeKey), null);
        return created.runId();
    }

    @Test
    void pureGraphWaitRegistersWithoutAttemptPreservesContinuationCompatAndRejectsBadRegistration() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-reg");

        GraphPlan plan = compileMessageWithTimeoutPlan("def-reg-" + UUID.randomUUID(), "corr-reg", "PT30S");
        UUID runId = createStartedRunAtWait(repo, jdbc, tenant, plan, "w1");
        UUID waitTokenId = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, runId);
        GraphPlan.WaitSpec waitSpec = plan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();

        // Register while RUNNING/OPEN
        WorkflowRuntimeRepository.GraphWaitResult res = repo.registerGraphWait(tenant, runId, waitTokenId, "w1", waitSpec);
        assertEquals(WaitOutcomes.COMPLETED, res.outcome());
        assertNotNull(res.waitId());
        assertNotNull(res.activationId());

        // Zero node_attempt rows; Activation WAITING + NULL attempt; Token WAITING; continuation keeps nodeKey + timeoutNodeKey.
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id=? AND run_id=?",
                Long.class, tenant, runId));
        assertEquals("WAITING", jdbc.queryForObject(
                "SELECT activation_state FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?",
                String.class, tenant, res.activationId()));
        assertNull(jdbc.queryForObject(
                "SELECT current_attempt_id FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?",
                UUID.class, tenant, res.activationId()));
        assertEquals("WAITING", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, waitTokenId));

        String contJson = jdbc.queryForObject(
                "SELECT continuation::text FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, res.waitId());
        JsonNode cont = JSON.readTree(contJson);
        assertEquals(2, cont.get("continuationVersion").asInt());
        assertEquals("e_ok", cont.get("nodeKey").asText());
        assertEquals("e_to", cont.get("timeoutNodeKey").asText());

        // Replay registerGraphWait -> ALREADY_COMPLETED with same waitId/activationId
        WorkflowRuntimeRepository.GraphWaitResult replay = repo.registerGraphWait(tenant, runId, waitTokenId, "w1", waitSpec);
        assertEquals(WaitOutcomes.ALREADY_COMPLETED, replay.outcome());
        assertEquals(res.waitId(), replay.waitId());

        // Register while paused -> DEFERRED_RUN_NOT_OPEN cleanly
        GraphPlan pausedPlan = compilePlainWaitPlan("def-p-" + UUID.randomUUID(), "corr-p");
        UUID pausedRun = createStartedRunAtWait(repo, jdbc, tenant, pausedPlan, "w1");
        UUID pToken = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, pausedRun);
        repo.requestDrain(tenant, pausedRun, "PAUSE");
        GraphPlan.WaitSpec pSpec = pausedPlan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        assertEquals(WaitOutcomes.DEFERRED_RUN_NOT_OPEN,
                repo.registerGraphWait(tenant, pausedRun, pToken, "w1", pSpec).outcome());

        // Legacy task-backed registerWait/closeWait can read BOTH v1 {"nodeKey"} and v2 {"continuationVersion":2,"nodeKey":...,"timeoutNodeKey":...}
        GraphPlan taskPlan = compileTaskPlan("def-task-" + UUID.randomUUID());
        UUID taskRun = createStartedRunAtWait(repo, jdbc, tenant, taskPlan, "t1");
        UUID taskToken = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='t1'
            """, UUID.class, tenant, taskRun);
        TaskActivationAttempt taskRef = createTaskActivationAndAttempt(jdbc, tenant, taskRun, taskToken, "t1");
        UUID taskWait = repo.registerWait(tenant, taskRun, taskRef.activationId(), "DEVICE_EVENT",
                "dev-corr", Instant.now().plusSeconds(60),
                "{\"continuationVersion\":2,\"nodeKey\":\"e_ok\",\"timeoutNodeKey\":\"e_to\"}");
        assertTrue(repo.closeWait(tenant, taskRun, taskWait, true, taskRef.attemptId()));
        assertEquals("ABANDONED_SAFE", jdbc.queryForObject(
                "SELECT activation_state FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?",
                String.class, tenant, taskRef.activationId()));
        assertEquals("e_ok", jdbc.queryForObject("""
            SELECT node_key FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND parent_token_id=?
            """, String.class, tenant, taskToken));
    }

    @Test
    void waitInboxSyncPauseRejectionAndProtocolViolationWorkAsFrozenSpec() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-inbox");

        GraphPlan plan = compileMessageWithTimeoutPlan("def-ib-" + UUID.randomUUID(), "corr-sync", "PT60S");
        UUID runId = createStartedRunAtWait(repo, jdbc, tenant, plan, "w1");
        UUID waitTokenId = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, runId);
        GraphPlan.WaitSpec waitSpec = plan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        UUID waitId = repo.registerGraphWait(tenant, runId, waitTokenId, "w1", waitSpec).waitId();

        // 1. Wrong correlationKey while RUNNING/OPEN -> REJECTED (no early-message buffering)
        WorkflowRuntimeRepository.GraphWaitResult earlyMiss = repo.receiveWaitInbox(
                tenant, runId, "webhook", "msg-miss", "wrong-corr", "{\"x\":1}");
        assertEquals(WaitOutcomes.REJECTED, earlyMiss.outcome());
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                String.class, tenant, earlyMiss.inboxId()));
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.runtime_event
             WHERE tenant_id=? AND run_id=? AND event_type='INBOX_REJECTED' AND causation_id=?
            """, Long.class, tenant, runId, earlyMiss.inboxId()));

        // 2. Malformed payload -> REJECTED + INBOX_REJECTED in same tx
        WorkflowRuntimeRepository.GraphWaitResult badJson = repo.receiveWaitInbox(
                tenant, runId, "webhook", "msg-bad", "corr-sync", "{not-json");
        assertEquals(WaitOutcomes.REJECTED, badJson.outcome());
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                String.class, tenant, badJson.inboxId()));

        // 3. Matching signal while RUNNING/OPEN -> synchronous completion in same tx!
        WorkflowRuntimeRepository.GraphWaitResult syncHit = repo.receiveWaitInbox(
                tenant, runId, "webhook", "msg-ok", "corr-sync", "{\"result\":\"pass\"}");
        assertEquals(WaitOutcomes.COMPLETED, syncHit.outcome());
        assertEquals("SATISFIED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, waitId));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT activation_state FROM workflow_runtime.node_activation WHERE tenant_id=? AND activation_id=?",
                String.class, tenant, syncHit.activationId()));
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, waitTokenId));
        assertEquals("PROCESSED", jdbc.queryForObject(
                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                String.class, tenant, syncHit.inboxId()));
        assertEquals("e_ok", jdbc.queryForObject(
                "SELECT node_key FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, syncHit.successorTokenId()));

        // 4. Duplicate identical replay -> ALREADY_COMPLETED; conflict hash -> PROTOCOL_VIOLATION in REQUIRES_NEW
        WorkflowRuntimeRepository.GraphWaitResult dupOk = repo.receiveWaitInbox(
                tenant, runId, "webhook", "msg-ok", "corr-sync", "{\"result\":\"pass\"}");
        assertEquals(WaitOutcomes.ALREADY_COMPLETED, dupOk.outcome());

        WorkflowRuntimeRepository.GraphWaitResult conflict = repo.receiveWaitInbox(
                tenant, runId, "webhook", "msg-ok", "corr-sync", "{\"result\":\"tampered\"}");
        assertEquals(WaitOutcomes.PROTOCOL_VIOLATION, conflict.outcome());
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.inbox_protocol_violation
             WHERE tenant_id=? AND inbox_id=?
            """, Long.class, tenant, syncHit.inboxId()));

        // 5. Signal during PAUSE -> durable RECEIVED + DEFERRED_RUN_NOT_OPEN; Resume + replay -> COMPLETED!
        GraphPlan pausePlan = compileMessageWithTimeoutPlan("def-p2-" + UUID.randomUUID(), "corr-pause", "PT60S");
        UUID pauseRun = createStartedRunAtWait(repo, jdbc, tenant, pausePlan, "w1");
        UUID pwToken = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, pauseRun);
        GraphPlan.WaitSpec pwSpec = pausePlan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        UUID pwId = repo.registerGraphWait(tenant, pauseRun, pwToken, "w1", pwSpec).waitId();

        UUID drainId = repo.requestDrain(tenant, pauseRun, "PAUSE");
        jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED', delivered_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
            """, tenant, pauseRun);
        assertTrue(repo.tryFinalizeDrain(tenant, pauseRun, drainId, "PAUSED"));

        WorkflowRuntimeRepository.GraphWaitResult duringPause = repo.receiveWaitInbox(
                tenant, pauseRun, "webhook", "msg-paused", "corr-pause", "{\"ok\":true}");
        assertEquals(WaitOutcomes.DEFERRED_RUN_NOT_OPEN, duringPause.outcome());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, pwId));
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                String.class, tenant, duringPause.inboxId()));

        // While PAUSED, findPendingWaitInboxReceipts must NOT surface this paused Run's receipt!
        assertTrue(repo.findPendingWaitInboxReceiptsForTenant(tenant, 50).stream()
                .noneMatch(r -> duringPause.inboxId().equals(r.inboxId())));

        assertTrue(repo.resumePausedRun(tenant, pauseRun));
        List<WorkflowRuntimeRepository.PendingWaitInboxReceipt> pendingAfterResume =
                repo.findPendingWaitInboxReceiptsForTenant(tenant, 50);
        assertTrue(pendingAfterResume.stream().anyMatch(r -> duringPause.inboxId().equals(r.inboxId())));

        WorkflowRuntimeRepository.GraphWaitResult replayed =
                repo.replayPendingWaitInbox(tenant, pauseRun, duringPause.inboxId());
        assertEquals(WaitOutcomes.COMPLETED, replayed.outcome());
        assertEquals("SATISFIED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, pwId));
    }

    @Test
    void timerBoundaryBeforeEqualAfterDeadlineAndInboxArbitrationMatchSpec() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-tmr");

        GraphPlan plan = compileMessageWithTimeoutPlan("def-tm-" + UUID.randomUUID(), "corr-tm", "PT30S");
        UUID runId = createStartedRunAtWait(repo, jdbc, tenant, plan, "w1");
        UUID waitTokenId = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, runId);
        GraphPlan.WaitSpec waitSpec = plan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        UUID waitId = repo.registerGraphWait(tenant, runId, waitTokenId, "w1", waitSpec).waitId();

        // 1. Before deadline -> STALE_VERSION (cannot expire early!)
        assertEquals(WaitOutcomes.STALE_VERSION,
                repo.completeGraphWait(tenant, runId, waitId, WaitOutcomes.OUTCOME_EXPIRED, 0L, null).outcome());

        // Set deadline to now minus 5 seconds so wait is due; insert eligible RECEIVED inbox strictly BEFORE deadline -> WAIT_FOR_INBOX!
        Instant deadline = jdbc.queryForObject("""
            UPDATE workflow_runtime.wait_registration
               SET deadline_at = clock_timestamp() - interval '5 seconds'
             WHERE tenant_id=? AND wait_id=?
         RETURNING deadline_at
            """, Instant.class, tenant, waitId);
        UUID eligibleInbox = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runtime.inbox_receipt
              (tenant_id, inbox_id, source, source_message_id, payload_hash, message_type,
               message_payload, run_id, received_at, receipt_state)
            VALUES (?, ?, 'webhook', ?, ?, ?, ?::jsonb, ?, ?, 'RECEIVED')
            """, tenant, eligibleInbox, "pre-dl-" + eligibleInbox, sha256("x"),
                WorkflowRuntimeRepository.WAIT_SIGNAL_MESSAGE_TYPE,
                "{\"correlationKey\":\"corr-tm\",\"payload\":{\"ok\":1}}",
                runId, Timestamp.from(deadline.minusSeconds(1)));

        // Eligible inbox strictly before deadline blocks expiration -> WAIT_FOR_INBOX
        assertEquals(WaitOutcomes.WAIT_FOR_INBOX,
                repo.completeGraphWait(tenant, runId, waitId, WaitOutcomes.OUTCOME_EXPIRED, 0L, null).outcome());

        // Exact deadline equality (received_at == deadline_at) is NOT < deadline_at -> goes to TIMEOUT!
        jdbc.update("""
            UPDATE workflow_runtime.inbox_receipt SET received_at=? WHERE tenant_id=? AND inbox_id=?
            """, Timestamp.from(deadline), tenant, eligibleInbox);
        WorkflowRuntimeRepository.GraphWaitResult expired =
                repo.completeGraphWait(tenant, runId, waitId, WaitOutcomes.OUTCOME_EXPIRED, 0L, null);
        assertEquals(WaitOutcomes.COMPLETED, expired.outcome());
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, waitId));
        assertEquals("e_to", jdbc.queryForObject(
                "SELECT node_key FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, expired.successorTokenId()));

        // Replaying that late equal-deadline inbox now retires cleanly as REJECTED!
        assertEquals(WaitOutcomes.REJECTED, repo.replayPendingWaitInbox(tenant, runId, eligibleInbox).outcome());
    }

    @Test
    void concurrentEventVsTimerAndDuplicateScansGuaranteeSingleWinnerAndOneSuccessor() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-race");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 6; round++) {
                final int roundIdx = round;
                GraphPlan plan = compileMessageWithTimeoutPlan("def-r-" + roundIdx + "-" + UUID.randomUUID(),
                        "corr-race-" + roundIdx, "PT1S");
                UUID runId = createStartedRunAtWait(repo, jdbc, tenant, plan, "w1");
                UUID waitTokenId = jdbc.queryForObject("""
                    SELECT token_id FROM workflow_runtime.execution_token
                     WHERE tenant_id=? AND run_id=? AND node_key='w1'
                    """, UUID.class, tenant, runId);
                GraphPlan.WaitSpec waitSpec = plan.nodes().stream()
                        .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
                UUID waitId = repo.registerGraphWait(tenant, runId, waitTokenId, "w1", waitSpec).waitId();
                jdbc.update("""
                    UPDATE workflow_runtime.wait_registration
                       SET deadline_at = clock_timestamp() - interval '2 seconds'
                     WHERE tenant_id=? AND wait_id=?
                    """, tenant, waitId);

                CountDownLatch startGate = new CountDownLatch(1);
                Callable<String> eventTask = () -> {
                    startGate.await();
                    return repo.receiveWaitInbox(tenant, runId, "wh", "race-msg-" + runId,
                            "corr-race-" + roundIdx, "{\"r\":1}").outcome();
                };
                Callable<String> timerTask1 = () -> {
                    startGate.await();
                    return repo.completeGraphWait(tenant, runId, waitId, WaitOutcomes.OUTCOME_EXPIRED, 0L, null).outcome();
                };
                Callable<String> timerTask2 = () -> {
                    startGate.await();
                    return repo.completeGraphWait(tenant, runId, waitId, WaitOutcomes.OUTCOME_EXPIRED, 0L, null).outcome();
                };

                Future<String> fEv = pool.submit(eventTask);
                Future<String> fT1 = pool.submit(timerTask1);
                Future<String> fT2 = pool.submit(timerTask2);
                startGate.countDown();

                String rEv = fEv.get(15, TimeUnit.SECONDS);
                String rT1 = fT1.get(15, TimeUnit.SECONDS);
                String rT2 = fT2.get(15, TimeUnit.SECONDS);

                long winners = List.of(rEv, rT1, rT2).stream().filter(WaitOutcomes.COMPLETED::equals).count();
                assertEquals(1L, winners, "Exactly one winner across Event + 2 Timer racers: "
                        + rEv + "/" + rT1 + "/" + rT2);
                assertEquals(1L, jdbc.queryForObject("""
                    SELECT count(*) FROM workflow_runtime.execution_token
                     WHERE tenant_id=? AND run_id=? AND parent_token_id=?
                    """, Long.class, tenant, runId, waitTokenId));
                String wState = jdbc.queryForObject(
                        "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                        String.class, tenant, waitId);
                assertTrue(List.of("SATISFIED", "EXPIRED").contains(wState));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rollbackOnMidTxFailureLeavesZeroPartialStateAndUnroutedGuardBlocksCompletion() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-rb");

        GraphPlan plan = compilePlainWaitPlan("def-rb-" + UUID.randomUUID(), "corr-rb");
        UUID runId = createStartedRunAtWait(repo, jdbc, tenant, plan, "w1");
        UUID waitTokenId = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, runId);
        GraphPlan.WaitSpec waitSpec = plan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        UUID waitId = repo.registerGraphWait(tenant, runId, waitTokenId, "w1", waitSpec).waitId();

        // Inject a mid-tx failure on WAIT_SATISFIED runtime_event insert via scoped DB trigger.
        String fnName = "workflow_runtime.fail_wait_event_" + UUID.randomUUID().toString().replace("-", "");
        String trgName = "trg_fail_wait_event_" + UUID.randomUUID().toString().replace("-", "");
        try {
            jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                  IF NEW.run_id = '%s'::uuid AND NEW.event_type = 'WAIT_SATISFIED' THEN
                    RAISE EXCEPTION 'injected mid-tx fault after wait/token/successor/inbox writes';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(fnName, runId));
            jdbc.execute("""
                CREATE TRIGGER %s BEFORE INSERT ON workflow_runtime.runtime_event
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(trgName, fnName));

            assertThrows(RuntimeException.class, () -> repo.receiveWaitInbox(
                    tenant, runId, "webhook", "msg-rb-1", "corr-rb", "{\"step\":1}"));

            // Wait, Activation, Token, Successor, and Inbox must ALL be completely rolled back!
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                    String.class, tenant, waitId));
            assertEquals("WAITING", jdbc.queryForObject(
                    "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                    String.class, tenant, waitTokenId));
            assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND parent_token_id=?
                """, Long.class, tenant, waitTokenId));
            assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM workflow_runtime.inbox_receipt
                 WHERE tenant_id=? AND source='webhook' AND source_message_id='msg-rb-1'
                """, Long.class, tenant));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trgName + " ON workflow_runtime.runtime_event");
            jdbc.execute("DROP FUNCTION IF EXISTS " + fnName + "()");
        }

        // Now complete normally -> end node 'e_ok' becomes READY -> advancing END completes Run to SUCCEEDED!
        WorkflowRuntimeRepository.GraphWaitResult ok = repo.receiveWaitInbox(
                tenant, runId, "webhook", "msg-rb-1", "corr-rb", "{\"step\":1}");
        assertEquals(WaitOutcomes.COMPLETED, ok.outcome());
        assertTrue(repo.advanceGraphToken(tenant, runId, ok.successorTokenId(), "e_ok", "END", List.of(), null).runCompleted());
    }

    @Test
    void pauseSafeVsUnsafeThreeWayCheckStopFailureIdempotencyAndPendingInboxFairness() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        WorkflowRuntimeRepository repo = new WorkflowRuntimeRepository(ds, new DataSourceTransactionManager(ds));
        UUID tenant = registerTenant(jdbc, "g5-drain");

        // 1. PAUSE admits 3-way pure-graph Wait, then STOP superseding PAUSE cancels it idempotently once
        GraphPlan pWait = compilePlainWaitPlan("def-dw-" + UUID.randomUUID(), "corr-dw");
        UUID runPure = createStartedRunAtWait(repo, jdbc, tenant, pWait, "w1");
        UUID tokPure = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, runPure);
        GraphPlan.WaitSpec wSpec = pWait.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        UUID waitPure = repo.registerGraphWait(tenant, runPure, tokPure, "w1", wSpec).waitId();

        UUID pauseDrain = repo.requestDrain(tenant, runPure, "PAUSE");
        jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED', delivered_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
            """, tenant, runPure);
        assertTrue(repo.tryFinalizeDrain(tenant, runPure, pauseDrain, "PAUSED"),
                "3-way pure-graph Wait must allow PAUSED convergence");

        // Receive a signal while PAUSED, then issue STOP -> invalidateStopContinuations retires both Wait + Inbox idempotently!
        UUID pausedInbox = repo.receiveWaitInbox(
                tenant, runPure, "webhook", "stop-inbox-1", "corr-dw", "{\"k\":1}").inboxId();
        UUID stopDrain = repo.requestDrain(tenant, runPure, "STOP");
        jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED', delivered_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
            """, tenant, runPure);

        repo.invalidateStopContinuations(tenant, runPure);
        repo.invalidateStopContinuations(tenant, runPure); // second cycle must NOT emit duplicate WAIT_CANCELLED!

        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, waitPure));
        assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT token_state FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?",
                String.class, tenant, tokPure));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT receipt_state FROM workflow_runtime.inbox_receipt WHERE tenant_id=? AND inbox_id=?",
                String.class, tenant, pausedInbox));
        assertEquals(1L, jdbc.queryForObject("""
            SELECT count(*) FROM workflow_runtime.runtime_event
             WHERE tenant_id=? AND run_id=? AND event_type='WAIT_CANCELLED'
            """, Long.class, tenant, runPure));
        assertTrue(repo.tryFinalizeDrain(tenant, runPure, stopDrain, "STOPPED"));

        // 2. Task-backed Wait STILL blocks PAUSED and is NOT cancelled by invalidateStopContinuations!
        GraphPlan taskPlan = compileTaskPlan("def-tw-" + UUID.randomUUID());
        UUID runTaskWait = createStartedRunAtWait(repo, jdbc, tenant, taskPlan, "t1");
        UUID tokTaskWait = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='t1'
            """, UUID.class, tenant, runTaskWait);
        TaskActivationAttempt taskWaitAttempt = createTaskActivationAndAttempt(jdbc, tenant, runTaskWait, tokTaskWait, "t1");
        UUID taskBackedWaitId = repo.registerWait(tenant, runTaskWait, taskWaitAttempt.activationId(),
                "DEVICE_EVENT", "dev-c", Instant.now().plusSeconds(60), "{\"nodeKey\":\"e_ok\"}");

        UUID pTaskDrain = repo.requestDrain(tenant, runTaskWait, "PAUSE");
        jdbc.update("""
            UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED', delivered_at=clock_timestamp()
             WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
            """, tenant, runTaskWait);
        assertFalse(repo.tryFinalizeDrain(tenant, runTaskWait, pTaskDrain, "PAUSED"),
                "Task-backed Wait must STILL block PAUSED");

        // Supersede with FAILURE drain -> Run target MUST stay FAILED (not STOPPED!), task-backed Wait untouched
        UUID failDrain = repo.requestDrain(tenant, runTaskWait, "FAILURE");
        assertEquals("FAILURE", jdbc.queryForObject(
                "SELECT drain_kind FROM workflow_runtime.drain_record WHERE tenant_id=? AND drain_id=?",
                String.class, tenant, failDrain));
        repo.invalidateStopContinuations(tenant, runTaskWait);
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT wait_state FROM workflow_runtime.wait_registration WHERE tenant_id=? AND wait_id=?",
                String.class, tenant, taskBackedWaitId),
                "invalidateStopContinuations must not touch task-backed Wait");

        // 3. Pending Inbox fairness: many paused RECEIVED receipts must not starve a RUNNING Run under LIMIT 1!
        UUID pausedFloodRun = createStartedRunAtWait(repo, jdbc, tenant,
                compilePlainWaitPlan("def-fl-" + UUID.randomUUID(), "corr-fl"), "w1");
        repo.requestDrain(tenant, pausedFloodRun, "PAUSE");
        for (int i = 0; i < 5; i++) {
            repo.receiveWaitInbox(tenant, pausedFloodRun, "webhook", "flood-" + i + "-" + UUID.randomUUID(),
                    "corr-fl", "{\"i\":" + i + "}");
        }
        GraphPlan runOkPlan = compilePlainWaitPlan("def-rok-" + UUID.randomUUID(), "corr-rok");
        UUID runActive = createStartedRunAtWait(repo, jdbc, tenant, runOkPlan, "w1");
        UUID insertedRunningInbox = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runtime.inbox_receipt
              (tenant_id, inbox_id, source, source_message_id, payload_hash, message_type,
               message_payload, run_id, receipt_state)
            VALUES (?, ?, 'webhook', ?, ?, ?, ?::jsonb, ?, 'RECEIVED')
            """, tenant, insertedRunningInbox, "active-" + insertedRunningInbox, sha256("active"),
                WorkflowRuntimeRepository.WAIT_SIGNAL_MESSAGE_TYPE,
                "{\"correlationKey\":\"corr-rok\",\"payload\":{}}", runActive);

        List<WorkflowRuntimeRepository.PendingWaitInboxReceipt> page1 =
                repo.findPendingWaitInboxReceiptsForTenant(tenant, 1);
        assertEquals(1, page1.size());
        assertEquals(insertedRunningInbox, page1.get(0).inboxId());

        // 4. H5 unrouted Wait guard: SUCCEEDED pure-graph Wait Activation without child token blocks SUCCEEDED.
        GraphPlan unroutedPlan = compilePlainWaitPlan("def-ug-" + UUID.randomUUID(), "corr-ug");
        UUID runGuard = createStartedRunAtWait(repo, jdbc, tenant, unroutedPlan, "w1");
        UUID tokGuard = jdbc.queryForObject("""
            SELECT token_id FROM workflow_runtime.execution_token
             WHERE tenant_id=? AND run_id=? AND node_key='w1'
            """, UUID.class, tenant, runGuard);
        GraphPlan.WaitSpec ugSpec = unroutedPlan.nodes().stream()
                .filter(n -> "w1".equals(n.key())).findFirst().orElseThrow().waitSpec();
        UUID actGuard = repo.registerGraphWait(tenant, runGuard, tokGuard, "w1", ugSpec).activationId();
        // Force a corrupt state: Activation SUCCEEDED, Wait SATISFIED, Token CONSUMED, 0 child tokens, plus another READY->END token
        jdbc.update("UPDATE workflow_runtime.wait_registration SET wait_state='SATISFIED' WHERE tenant_id=? AND activation_id=?", tenant, actGuard);
        jdbc.update("UPDATE workflow_runtime.node_activation SET activation_state='SUCCEEDED' WHERE tenant_id=? AND activation_id=?", tenant, actGuard);
        jdbc.update("UPDATE workflow_runtime.execution_token SET token_state='CONSUMED' WHERE tenant_id=? AND token_id=?", tenant, tokGuard);
        // Complete a dummy second token via END so maybeCompleteGraphRun runs; it MUST return false!
        UUID dummyTok = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workflow_runtime.execution_token(tenant_id, token_id, run_id, token_state, node_key)
            VALUES (?, ?, ?, 'READY', 'e_ok')
            """, tenant, dummyTok, runGuard);
        var endRes = repo.advanceGraphToken(tenant, runGuard, dummyTok, "e_ok", "END", List.of(), null);
        assertFalse(endRes.runCompleted(),
                "maybeCompleteGraphRun must refuse SUCCEEDED when a pure-graph Wait lacks its successor Token");
    }
}
