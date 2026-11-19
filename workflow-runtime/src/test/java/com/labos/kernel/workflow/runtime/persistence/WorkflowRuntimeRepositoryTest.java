package com.labos.kernel.workflow.runtime.persistence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRuntimeRepositoryTest {
    private static String env(String key, String fallback) {
        String value=System.getenv(key);
        return value==null||value.isBlank()?fallback:value;
    }

    private DriverManagerDataSource dataSource() {
        String host=env("PGHOST","localhost"), port=env("PGPORT","5432");
        String db=env("PGDATABASE","runtime_test");
        DriverManagerDataSource ds=new DriverManagerDataSource(
                "jdbc:postgresql://"+host+":"+port+"/"+db,
                env("PGUSER","runtime"),env("PGPASSWORD","runtime"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @Test
    void runCreationIsIdempotentAndWorkerLifecycleIsStrict() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")),
                "PostgreSQL Runtime integration tests are enabled only in CI");
        var ds=dataSource();
        UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','it-"+tenant+"','integration')");
        }

        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        String requestId="request-"+UUID.randomUUID();
        byte[] definitionHash=new byte[32];
        var first=repo.createRun(tenant,requestId,"CREATE","{\"input\":1}",
                "it-flow-"+tenant,"1",definitionHash,"ci","{}","{\"input\":1}","node-1");
        var replay=repo.createRun(tenant,requestId,"CREATE","{\"input\":1}",
                "it-flow-"+tenant,"1",definitionHash,"ci","{}","{\"input\":1}","node-1");
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.runId(),replay.runId());

        repo.startRun(tenant,first.runId());
        var reservation=repo.reserveCapacity(tenant,first.runId(),"it-capacity",1,
                "node-1","DEVICE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,first.runId(),reservation.attemptId(),"worker-q",
                "{\"attemptId\":\""+reservation.attemptId()+"\"}");

        assertThrows(WorkflowRuntimeRepository.StaleRuntimeWriteException.class, () ->
                repo.startAttempt(tenant,first.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1"));
        repo.acceptAttempt(tenant,first.runId(),reservation.attemptId(),
                reservation.dispatchId(),"worker-1");
        repo.startAttempt(tenant,first.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        assertTrue(repo.recordSideEffectFact(tenant,first.runId(),reservation.attemptId(),
                "worker-1","worker-call-1","SIDE_EFFECT_STARTED","{\\"phase\\":\\"before-adapter\\"}"));
        assertTrue(repo.recordSideEffectFact(tenant,first.runId(),reservation.attemptId(),
                "worker-1","worker-call-1","SIDE_EFFECT_STARTED","{\\"phase\\":\\"before-adapter\\"}"));
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,() ->
                repo.recordSideEffectFact(tenant,first.runId(),reservation.attemptId(),
                        "worker-1","worker-call-1","SIDE_EFFECT_STARTED","{\\"phase\\":\\"different\\"}"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND fact_type='HANDLER_STARTED'"));

        UUID command=repo.createCommand(tenant,first.runId(),
                activationForAttempt(ds,tenant,reservation.attemptId()),reservation.attemptId(),
                "device-1","RunMethod","QUERYABLE","{\"method\":\"test\"}");
        assertNotNull(command);
        var commandOutbox=repo.beginOutboxSend(tenant,10)
                .stream().filter(x -> x.dedupKey().equals("command:"+command)).findFirst().orElseThrow();
        UUID dispatchId=UUID.randomUUID();
        assertTrue(repo.markCommandSendStarted(tenant,command,0L,commandOutbox.outboxId(),
                dispatchId,1,"dispatcher-1"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND fact_type='SIDE_EFFECT_STARTED'"));
        assertTrue(repo.markOutboxDelivered(tenant,commandOutbox.outboxId()));
        assertThrows(RuntimeException.class, () ->
                repo.markCommandSendStarted(tenant,command,0L,commandOutbox.outboxId(),
                        UUID.randomUUID(),2,"dispatcher-1"));
        var receipt=repo.receiveInbox(tenant,"it-device","device-msg-"+UUID.randomUUID(),
                "DEVICE_COMMAND_RESULT",first.runId(),"{\"commandId\":\""+command+"\",\"state\":\"SUCCEEDED\"}");
        assertTrue(repo.applyCommandEvent(tenant,first.runId(),receipt.inboxId(),command,
                "SUCCEEDED","seq-1","{\"verified\":true}"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND fact_type='SIDE_EFFECT_COMPLETED' AND actor_kind='DEVICE'"));

        repo.completeAttempt(tenant,first.runId(),reservation.attemptId(),"result",
                "{\"ok\":true}");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND fact_type='EXECUTION_RIGHT_RELEASED'"));
        assertFalse(repo.releaseReservation(tenant,reservation.reservationId()));
        assertFalse(repo.releaseReservation(tenant,reservation.reservationId()));
    }

    @Test
    void inboxConflictingPayloadIsNotOverwritten() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource();
        UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','inbox-"+tenant+"','integration')");
        } catch(Exception e) { throw new RuntimeException(e); }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        String msg="msg-"+UUID.randomUUID();
        var first=repo.receiveInbox(tenant,"it-adapter",msg,"DEVICE_EVENT",null,"{\"v\":1}");
        var replay=repo.receiveInbox(tenant,"it-adapter",msg,"DEVICE_EVENT",null,"{\"v\":1}");
        var conflict=repo.receiveInbox(tenant,"it-adapter",msg,"DEVICE_EVENT",null,"{\"v\":2}");
        assertTrue(first.inserted());
        assertFalse(replay.inserted());
        assertFalse(replay.payloadConflict());
        assertTrue(conflict.payloadConflict());
        assertEquals(first.inboxId(),conflict.inboxId());
    }

    @Test
    void retryWaitSplitJoinLoopAndCheckpointUseOneConsistentRunModel() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','graph-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        String request="graph-"+UUID.randomUUID();
        var created=repo.createRun(tenant,request,"CREATE","{\"graph\":1}",
                "graph-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,created.runId());
        UUID token;
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement(
                "SELECT token_id FROM workflow_runtime.execution_token WHERE tenant_id=? AND run_id=?")) {
            ps.setObject(1,tenant); ps.setObject(2,created.runId());
            try(var rs=ps.executeQuery()){assertTrue(rs.next());token=rs.getObject(1,UUID.class);}
        }
        UUID activation=UUID.randomUUID(), attempt=UUID.randomUUID(), dispatch=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.node_activation
                  (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
                VALUES (?,?,?, ?,1,'wait-node','RESERVED')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,activation);ps.setObject(3,created.runId());
            ps.setObject(4,token);ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.node_attempt
                  (tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,attempt_state)
                VALUES (?,?,?, ?,1,?,'CREATED')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,attempt);ps.setObject(3,created.runId());
            ps.setObject(4,activation);ps.setObject(5,dispatch);ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                UPDATE workflow_runtime.node_activation SET current_attempt_id=?
                 WHERE tenant_id=? AND activation_id=?
                """)) {
            ps.setObject(1,attempt);ps.setObject(2,tenant);ps.setObject(3,activation);ps.executeUpdate();
        }
        repo.scheduleRetry(tenant,created.runId(),activation,Instant.now().plusSeconds(5),2,"{\"max\":3}");
        UUID wait=repo.registerWait(tenant,created.runId(),activation,"EVENT","corr-1",
                Instant.now().plusSeconds(30),"{\"nodeKey\":\"after-wait\"}");
        assertTrue(repo.closeWait(tenant,created.runId(),wait,true,attempt));

        UUID splitToken=UUID.randomUUID(), sourceActivation=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,token_id,run_id,token_state,node_key) VALUES (?,?,?,'RUNNING','split-source')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,splitToken);ps.setObject(3,created.runId());ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.node_activation
                  (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
                VALUES (?,?,?, ?,1,'split-source','RUNNING')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,sourceActivation);ps.setObject(3,created.runId());
            ps.setObject(4,splitToken);ps.executeUpdate();
        }
        UUID split=repo.createSplit(tenant,created.runId(),sourceActivation,java.util.List.of("branch-a","branch-b"));
        for(String branch:java.util.List.of("branch-a","branch-b")) {
            UUID branchId, branchToken, arrivalActivation=UUID.randomUUID();
            try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                    SELECT b.branch_id,t.token_id FROM workflow_runtime.branch_activation b
                    JOIN workflow_runtime.execution_token t ON t.tenant_id=b.tenant_id AND t.branch_id=b.branch_id
                    WHERE b.tenant_id=? AND b.split_id=? AND b.branch_key=?
                    """)) {
                ps.setObject(1,tenant);ps.setObject(2,split);ps.setString(3,branch);
                try(var rs=ps.executeQuery()){assertTrue(rs.next());branchId=rs.getObject(1,UUID.class);branchToken=rs.getObject(2,UUID.class);}
            }
            try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                    INSERT INTO workflow_runtime.node_activation
                      (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
                    VALUES (?,?,?, ?,1,?,'SUCCEEDED')
                    """)) {
                ps.setObject(1,tenant);ps.setObject(2,arrivalActivation);ps.setObject(3,created.runId());
                ps.setObject(4,branchToken);ps.setString(5,branch);ps.executeUpdate();
            }
            assertTrue(repo.arriveBranch(tenant,created.runId(),split,branch,arrivalActivation,"join-node"));
        }

        UUID loop=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.loop_context
                  (tenant_id,loop_context_id,run_id,loop_key,iteration,loop_state)
                VALUES (?,?,?,'loop-a',0,'ACTIVE')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,loop);ps.setObject(3,created.runId());ps.executeUpdate();
        }
        assertEquals(1L,repo.advanceLoop(tenant,created.runId(),loop,0L,0L,"loop-body","{}"));
        assertTrue(repo.completeLoop(tenant,created.runId(),loop,1L));
        var checkpoint=repo.createCheckpoint(tenant,created.runId(),"TEST");
        assertTrue(checkpoint.eventSeq()>0);
        assertEquals(32,checkpoint.sha256().length);
    }

    private int scalar(DriverManagerDataSource ds, String sql) throws Exception {
        try(Connection c=ds.getConnection(); var st=c.createStatement(); var rs=st.executeQuery(sql)) {
            assertTrue(rs.next()); return rs.getInt(1);
        }
    }

    @Test
    void interruptedPureAttemptIsSafeToAbandonAfterHostRestart() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','restart-pure-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        var run=repo.createRun(tenant,"restart-pure-"+UUID.randomUUID(),"CREATE","{}",
                "restart-pure-flow-"+tenant,"1",new byte[32],"ci","{}","{}","pure-node");
        repo.startRun(tenant,run.runId());
        var reservation=repo.reserveCapacity(tenant,run.runId(),"restart-pure-cap",1,
                "pure-node","PURE",Instant.now().plusSeconds(120));
        repo.dispatchAttempt(tenant,run.runId(),reservation.attemptId(),"worker-q","{}");
        repo.acceptAttempt(tenant,run.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        repo.startAttempt(tenant,run.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");

        var recovery=new WorkerExecutionRecoveryService(repo,new WorkerExecutionRegistry());
        var startup=recovery.recoverAtStartup(20,"startup");
        assertEquals(1,startup.scanned());
        assertEquals(1,startup.safeToRetry());
        var result=startup.items().get(0).result();
        assertEquals("SAFE_TO_RETRY",result.outcome());
        assertNull(result.interventionId());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND attempt_state='ABANDONED_SAFE'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+tenant+"' AND run_id='"+run.runId()+"' AND token_state='READY'"));
    }

    @Test
    void interruptedAttemptAfterSendStartedRequiresManualReconciliation() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','restart-unknown-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        var run=repo.createRun(tenant,"restart-unknown-"+UUID.randomUUID(),"CREATE","{}",
                "restart-unknown-flow-"+tenant,"1",new byte[32],"ci","{}","{}","device-node");
        repo.startRun(tenant,run.runId());
        var reservation=repo.reserveCapacity(tenant,run.runId(),"restart-unknown-cap",1,
                "device-node","DEVICE",Instant.now().plusSeconds(120));
        repo.dispatchAttempt(tenant,run.runId(),reservation.attemptId(),"worker-q","{}");
        repo.acceptAttempt(tenant,run.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        repo.startAttempt(tenant,run.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        UUID command=repo.createCommand(tenant,run.runId(),
                activationForAttempt(ds,tenant,reservation.attemptId()),reservation.attemptId(),
                "device-1","RunMethod","QUERYABLE","{}");
        var outbox=repo.beginOutboxSend(tenant,10).stream()
                .filter(x -> x.dedupKey().equals("command:"+command)).findFirst().orElseThrow();
        assertTrue(repo.markCommandSendStarted(tenant,command,0L,outbox.outboxId(),
                UUID.randomUUID(),1,"dispatcher"));
        var result=repo.recoverInterruptedAttempt(tenant,run.runId(),reservation.attemptId(),"startup");
        assertEquals("MANUAL_REQUIRED",result.outcome());
        assertNotNull(result.interventionId());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND attempt_state='UNKNOWN'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.manual_intervention WHERE tenant_id='"+tenant+"' AND intervention_id='"+result.interventionId()+"' AND intervention_state='OPEN'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+tenant+"' AND run_id='"+run.runId()+"' AND status='MANUAL_REQUIRED' AND recovery_state='BLOCKED'"));
    }

    @Test
    void pauseDrainPreservesReadyTokensAtSafePoint() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','pause-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        var created=repo.createRun(tenant,"pause-"+UUID.randomUUID(),"CREATE","{}",
                "pause-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,created.runId());
        UUID drain=repo.requestDrain(tenant,created.runId(),"PAUSE");
        repo.advanceDrainScan(tenant,created.runId(),drain,0L,"{\"page\":1}",java.util.List.of());
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED',delivered_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
                """)) {ps.setObject(1,tenant);ps.setObject(2,created.runId());ps.executeUpdate();}
        assertTrue(repo.tryFinalizeDrain(tenant,created.runId(),drain,"PAUSED"));
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                SELECT status,control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=? AND run_id=?
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,created.runId());
            try(var rs=ps.executeQuery()){assertTrue(rs.next());assertEquals("PAUSED",rs.getString(1));assertEquals("PAUSE",rs.getString(2));}
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                SELECT count(*) FROM workflow_runtime.execution_token
                 WHERE tenant_id=? AND run_id=? AND token_state='READY'
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,created.runId());
            try(var rs=ps.executeQuery()){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
        }
    }

    @Test
    void stopDrainLeaseEvidenceAndManualQuarantineDecisionsAreAudited() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','drain-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds);
        var created=repo.createRun(tenant,"drain-"+UUID.randomUUID(),"CREATE","{}",
                "drain-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,created.runId());
        var acquired=repo.acquireResourceLease(tenant,created.runId(),"drain-resource",
                "RUN",created.runId(),Instant.now().plusSeconds(120),"{\"lease\":\"integration\"}");
        UUID lease=acquired.leaseId();
        UUID pauseDrain=repo.requestDrain(tenant,created.runId(),"PAUSE");
        UUID drain=repo.requestDrain(tenant,created.runId(),"STOP");
        assertEquals(pauseDrain,drain,"Stop must supersede the in-flight Pause drain");
        repo.advanceDrainScan(tenant,created.runId(),drain,1L,"{\"page\":1}",
                java.util.List.of(new WorkflowRuntimeRepository.DrainItemRef("LEASE",lease)));
        repo.recordSafePointEvidence(tenant,created.runId(),drain,"LEASE",lease,0L,
                "{\"deviceReadback\":\"released\"}",true);
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                UPDATE workflow_runtime.execution_token SET token_state='INVALIDATED'
                 WHERE tenant_id=? AND run_id=? AND token_state='READY'
                """)) {ps.setObject(1,tenant);ps.setObject(2,created.runId());ps.executeUpdate();}
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                UPDATE workflow_runtime.runtime_outbox SET outbox_state='DELIVERED',delivered_at=clock_timestamp()
                 WHERE tenant_id=? AND run_id=? AND outbox_state='PENDING'
                """)) {ps.setObject(1,tenant);ps.setObject(2,created.runId());ps.executeUpdate();}
        assertTrue(repo.tryFinalizeDrain(tenant,created.runId(),drain,"STOPPED"));

        UUID intervention=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.manual_intervention
                  (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,reason_code)
                VALUES (?,?,?,'RUN',?,'OPEN','TEST')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,intervention);ps.setObject(3,created.runId());
            ps.setObject(4,created.runId());ps.executeUpdate();
        }
        assertTrue(repo.decideIntervention(tenant,created.runId(),intervention,0L,"operator-1","{\"decision\":\"verified\"}"));

        UUID quarantine=UUID.randomUUID(), releasedLease=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.resource_lease
                  (tenant_id,lease_id,run_id,resource_id,lease_state,owner_kind,owner_id)
                VALUES (?,?,?,'quarantine-resource','RELEASED','RUN',?)
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,releasedLease);ps.setObject(3,created.runId());
            ps.setObject(4,created.runId());ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.quarantine_record
                  (tenant_id,quarantine_id,resource_id,run_id,lease_id,quarantine_state,reason_code,evidence)
                VALUES (?,?, 'quarantine-resource',?,?,'CLEAR_PENDING','TEST','{}')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,quarantine);ps.setObject(3,created.runId());
            ps.setObject(4,releasedLease);ps.executeUpdate();
        }
        assertTrue(repo.clearQuarantine(tenant,quarantine,"operator-1","{\"physicalCheck\":\"passed\"}"));
    }

    private UUID activationForAttempt(DriverManagerDataSource ds, UUID tenant, UUID attempt) {
        try(Connection c=ds.getConnection();
            var ps=c.prepareStatement("SELECT activation_id FROM workflow_runtime.node_attempt WHERE tenant_id=? AND attempt_id=?")) {
            ps.setObject(1,tenant); ps.setObject(2,attempt);
            try(var rs=ps.executeQuery()) { assertTrue(rs.next()); return rs.getObject(1,UUID.class); }
        } catch(Exception e) { throw new RuntimeException(e); }
    }
}
