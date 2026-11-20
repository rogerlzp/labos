package com.labos.kernel.workflow.runtime.persistence;

import com.labos.kernel.workflow.runtime.coordinator.GraphCoordinator;
import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.labos.kernel.workflow.runtime.recovery.WorkerExecutionRecoveryService;
import com.labos.kernel.workflow.runtime.worker.WorkerExecutionRegistry;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

        var sharedTransactionManager = new DataSourceTransactionManager(ds);
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,sharedTransactionManager);
        String rollbackKey="kernel-tenant:rollback-"+UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(sharedTransactionManager).execute(status -> {
            repo.resolveTenantId(rollbackKey,"must roll back with outer Kernel transaction");
            throw new IllegalStateException("force outer rollback");
        }));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.tenant WHERE tenant_key='"+rollbackKey+"'"));
        String stableTenantKey="kernel-tenant:"+UUID.randomUUID();
        UUID mappedTenant=repo.resolveTenantId(stableTenantKey,"Kernel integration tenant");
        assertEquals(mappedTenant,repo.resolveTenantId(stableTenantKey,"Kernel integration tenant"));
        assertEquals(stableTenantKey,repo.findTenantKey(mappedTenant));
        String requestId="request-"+UUID.randomUUID();
        byte[] definitionHash=new byte[32];
        var first=repo.createRun(tenant,requestId,"CREATE","{\"input\":1}",
                "it-flow-"+tenant,"1",definitionHash,"ci","{}","{\"input\":1}","node-1");
        var replay=repo.createRun(tenant,requestId,"CREATE","{\"input\":1}",
                "it-flow-"+tenant,"1",definitionHash,"ci","{}","{\"input\":1}","node-1");
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.runId(),replay.runId());
        var snapshot=repo.loadRunDefinitionSnapshot(tenant,first.runId());
        assertEquals("ci",snapshot.compilerVersion());
        assertEquals("{}",snapshot.graphPlanJson());
        assertTrue(repo.findNonTerminalRunSnapshots(1000).stream()
                .anyMatch(x -> x.runId().equals(first.runId()) && x.tenantId().equals(tenant)));

        repo.startRun(tenant,first.runId());
        var reservation=repo.reserveCapacity(tenant,first.runId(),"it-capacity",1,
                "node-1","DEVICE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,first.runId(),reservation.attemptId(),"worker-q",
                "{\"attemptId\":\""+reservation.attemptId()+"\"}");
        var workerOutbox=repo.beginOutboxSend(tenant,10).stream()
                .filter(x -> x.dedupKey().equals("dispatch:"+reservation.attemptId())).findFirst().orElseThrow();
        assertEquals("WORKER_DISPATCH",workerOutbox.messageKind());
        assertEquals(first.runId(),workerOutbox.runId());
        assertTrue(repo.markOutboxDelivered(tenant,workerOutbox.outboxId()));

        assertThrows(WorkflowRuntimeRepository.StaleRuntimeWriteException.class, () ->
                repo.startAttempt(tenant,first.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1"));
        repo.acceptAttempt(tenant,first.runId(),reservation.attemptId(),
                reservation.dispatchId(),"worker-1");
        repo.startAttempt(tenant,first.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        assertTrue(repo.recordSideEffectFact(tenant,first.runId(),reservation.attemptId(),
                "worker-1","worker-call-1","SIDE_EFFECT_STARTED","{\"phase\":\"before-adapter\"}"));
        assertTrue(repo.recordSideEffectFact(tenant,first.runId(),reservation.attemptId(),
                "worker-1","worker-call-1","SIDE_EFFECT_STARTED","{\"phase\":\"before-adapter\"}"));
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,() ->
                repo.recordSideEffectFact(tenant,first.runId(),reservation.attemptId(),
                        "worker-1","worker-call-1","SIDE_EFFECT_STARTED","{\"phase\":\"different\"}"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND fact_type='HANDLER_STARTED'"));

        UUID command=repo.createCommand(tenant,first.runId(),
                activationForAttempt(ds,tenant,reservation.attemptId()),reservation.attemptId(),
                "device-1","RunMethod","QUERYABLE","{\"method\":\"test\"}");
        assertNotNull(command);
        var commandOutbox=repo.beginOutboxSend(tenant,10)
                .stream().filter(x -> x.dedupKey().equals("command:"+command)).findFirst().orElseThrow();
        assertEquals("DEVICE_COMMAND",commandOutbox.messageKind());
        assertEquals(first.runId(),commandOutbox.runId());
        UUID dispatchId=UUID.randomUUID();
        assertTrue(repo.markCommandSendStarted(tenant,command,0L,commandOutbox.outboxId(),
                dispatchId,1,"dispatcher-1"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.worker_execution_fact WHERE tenant_id='"+tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND fact_type='SIDE_EFFECT_STARTED' AND actor_kind='DISPATCHER'"));
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
        UUID completedActivation=activationForAttempt(ds,tenant,reservation.attemptId());
        var routed=repo.advanceCompletedGraphActivation(tenant,first.runId(),completedActivation,
                "SEQUENCE",java.util.List.of("next-node"));
        assertEquals(1,routed.tokensCreated());
        var routedReplay=repo.advanceCompletedGraphActivation(tenant,first.runId(),completedActivation,
                "SEQUENCE",java.util.List.of("next-node"));
        assertEquals("REPLAYED",routedReplay.outcome());
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,()->repo.advanceCompletedGraphActivation(
                tenant,first.runId(),completedActivation,"SEQUENCE",java.util.List.of("different-node")));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+tenant+
                "' AND run_id='"+first.runId()+"' AND parent_token_id=(SELECT token_id FROM workflow_runtime.node_activation WHERE tenant_id='"+tenant+
                "' AND activation_id='"+completedActivation+"') AND node_key='next-node'"));
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
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
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
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
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
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND token_id='"+token+"' AND token_state='WAITING'"));
        assertTrue(repo.closeWait(tenant,created.runId(),wait,true,attempt));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND token_id='"+token+"' AND token_state='CONSUMED'"));

        var splitToken=repo.findReadyGraphTokens(tenant,created.runId(),10).stream()
                .filter(t -> t.nodeKey().equals("after-wait")).findFirst().orElseThrow();
        var splitResult=repo.advanceGraphToken(tenant,created.runId(),splitToken.tokenId(),
                "after-wait","SPLIT",List.of("branch-a","branch-b"));
        assertEquals(2,splitResult.tokensCreated());
        for(var branchToken : repo.findReadyGraphTokens(tenant,created.runId(),10)) {
            repo.advanceGraphToken(tenant,created.runId(),branchToken.tokenId(),
                    branchToken.nodeKey(),"SEQUENCE",List.of("join-node"));
        }
        int releasedTokens=0;
        for(var joinToken : repo.findReadyGraphTokens(tenant,created.runId(),10)) {
            releasedTokens += repo.advanceGraphToken(tenant,created.runId(),joinToken.tokenId(),
                    "join-node","JOIN",List.of("after-join"),"join-node").tokensCreated();
        }
        assertEquals(1,releasedTokens);
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id='"+
                tenant+"' AND run_id='"+created.runId()+"' AND split_state='CLOSED'"));

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

    @Test
    void graphTransitionTransactionRollsBackAndReleasesParallelJoinOnce() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','graph-tx-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));

        var rollbackRun=repo.createRun(tenant,"graph-rollback-"+UUID.randomUUID(),"CREATE","{}",
                "graph-rollback-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,rollbackRun.runId());
        var rollbackToken=repo.findReadyGraphTokens(tenant,rollbackRun.runId(),10).get(0);
        assertThrows(IllegalArgumentException.class,()->repo.advanceGraphToken(tenant,rollbackRun.runId(),
                rollbackToken.tokenId(),"start","SPLIT",java.util.List.of("same","same")));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND token_id='"+rollbackToken.tokenId()+"' AND token_state='READY'"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_activation WHERE tenant_id='"+
                tenant+"' AND token_id='"+rollbackToken.tokenId()+"'"));

        var run=repo.createRun(tenant,"graph-join-"+UUID.randomUUID(),"CREATE","{}",
                "graph-join-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,run.runId());
        var start=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        assertEquals("ADVANCED",repo.advanceGraphToken(tenant,run.runId(),start.tokenId(),
                "start","SEQUENCE",java.util.List.of("split")).outcome());
        var splitToken=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        var split=repo.advanceGraphToken(tenant,run.runId(),splitToken.tokenId(),
                "split","SPLIT",java.util.List.of("branch-a","branch-b"));
        assertEquals(2,split.tokensCreated());

        var branches=repo.findReadyGraphTokens(tenant,run.runId(),10);
        assertEquals(2,branches.size());
        var branchA=branches.stream().filter(t->t.nodeKey().equals("branch-a")).findFirst().orElseThrow();
        var branchB=branches.stream().filter(t->t.nodeKey().equals("branch-b")).findFirst().orElseThrow();
        repo.advanceGraphToken(tenant,run.runId(),branchA.tokenId(),
                "branch-a","SEQUENCE",java.util.List.of("join"));
        repo.advanceGraphToken(tenant,run.runId(),branchB.tokenId(),
                "branch-b","SEQUENCE",java.util.List.of("join"));
        var joinTokens=repo.findReadyGraphTokens(tenant,run.runId(),10);
        assertEquals(2,joinTokens.size());
        var joinA=joinTokens.get(0);
        var joinB=joinTokens.get(1);
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,()->repo.advanceGraphToken(
                tenant,run.runId(),joinA.tokenId(),"join","JOIN",java.util.List.of("end"),"not-the-gateway"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND token_id='"+joinA.tokenId()+"' AND token_state='READY'"));
        var firstArrival=repo.advanceGraphToken(tenant,run.runId(),joinA.tokenId(),
                "join","JOIN",java.util.List.of("end"),"join");
        assertEquals(0,firstArrival.tokensCreated());
        assertFalse(firstArrival.runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"+
                tenant+"' AND split_id='"+joinA.splitId()+"' AND join_key='join' AND join_state='WAITING'"));
        var lastArrival=repo.advanceGraphToken(tenant,run.runId(),joinB.tokenId(),
                "join","JOIN",java.util.List.of("end"),"join");
        assertEquals(1,lastArrival.tokensCreated());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"+
                tenant+"' AND split_id='"+joinA.splitId()+"' AND join_key='join' AND join_state='RELEASED'"));
        var end=repo.findReadyGraphTokens(tenant,run.runId(),10).stream()
                .filter(t->t.nodeKey().equals("end")).findFirst().orElseThrow();
        var terminal=repo.advanceGraphToken(tenant,run.runId(),end.tokenId(),"end","END",java.util.List.of());
        assertTrue(terminal.runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='SUCCEEDED'"));
    }

    @Test
    void graphCoordinatorAdvancesCompiledExclusiveGatewayFromPersistedVariables() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','graph-coordinator-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        String xml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:exclusiveGateway id="choice" default="choice-b"/>
                    <bpmn:endEvent id="end-a"/>
                    <bpmn:endEvent id="end-b"/>
                    <bpmn:sequenceFlow id="start-choice" sourceRef="start" targetRef="choice"/>
                    <bpmn:sequenceFlow id="choice-a" sourceRef="choice" targetRef="end-a">
                      <bpmn:conditionExpression>takeA == true</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="choice-b" sourceRef="choice" targetRef="end-b"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "graph-coordinator-"+tenant,"1",xml,java.util.Map.of()));
        GraphCoordinator.validateGraphPlan(plan);
        var run=repo.createRun(tenant,"graph-coordinator-"+UUID.randomUUID(),"CREATE","{}",
                "graph-coordinator-"+tenant,"1",java.util.HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),"{\"takeA\":false}","choice");
        repo.startRun(tenant,run.runId());
        assertEquals(1L,repo.putFlowVariable(tenant,run.runId(),"takeA","true"));

        var coordinator=new GraphCoordinator(repo);
        var summary=coordinator.advance(tenant,run.runId());
        assertTrue(summary.runCompleted());
        assertEquals(2,summary.controlTokensAdvanced());
        assertEquals(1,summary.successorTokensCreated());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='end-a' AND token_state='CONSUMED'"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='end-b'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='SUCCEEDED'"));
    }

    @Test
    void inclusiveSingleBranchAndMixedEndJoinAreHandledFailClosed() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','graph-inclusive-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));

        var single=repo.createRun(tenant,"inclusive-one-"+UUID.randomUUID(),"CREATE","{}",
                "inclusive-one-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,single.runId());
        var start=repo.findReadyGraphTokens(tenant,single.runId(),10).get(0);
        repo.advanceGraphToken(tenant,single.runId(),start.tokenId(),"start","SEQUENCE",java.util.List.of("inclusive"));
        var inclusive=repo.findReadyGraphTokens(tenant,single.runId(),10).get(0);
        assertEquals(1,repo.advanceGraphToken(tenant,single.runId(),inclusive.tokenId(),
                "inclusive","SPLIT",java.util.List.of("end")).tokensCreated());
        var end=repo.findReadyGraphTokens(tenant,single.runId(),10).get(0);
        assertTrue(repo.advanceGraphToken(tenant,single.runId(),end.tokenId(),"end","END",java.util.List.of()).runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"+
                tenant+"' AND join_key='' AND join_state='RELEASED'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id='"+
                tenant+"' AND run_id='"+single.runId()+"' AND split_state='CLOSED'"));

        var mixed=repo.createRun(tenant,"mixed-end-join-"+UUID.randomUUID(),"CREATE","{}",
                "mixed-end-join-"+tenant,"1",new byte[32],"ci","{}","{}","start");
        repo.startRun(tenant,mixed.runId());
        var mixedStart=repo.findReadyGraphTokens(tenant,mixed.runId(),10).get(0);
        repo.advanceGraphToken(tenant,mixed.runId(),mixedStart.tokenId(),"start","SEQUENCE",java.util.List.of("split"));
        var mixedSplit=repo.findReadyGraphTokens(tenant,mixed.runId(),10).get(0);
        repo.advanceGraphToken(tenant,mixed.runId(),mixedSplit.tokenId(),"split","SPLIT",
                java.util.List.of("end-branch","join-branch"));
        var branches=repo.findReadyGraphTokens(tenant,mixed.runId(),10);
        var endBranch=branches.stream().filter(t->t.nodeKey().equals("end-branch")).findFirst().orElseThrow();
        var joinBranch=branches.stream().filter(t->t.nodeKey().equals("join-branch")).findFirst().orElseThrow();
        repo.advanceGraphToken(tenant,mixed.runId(),endBranch.tokenId(),"end-branch","END",java.util.List.of());
        repo.advanceGraphToken(tenant,mixed.runId(),joinBranch.tokenId(),"join-branch","SEQUENCE",
                java.util.List.of("join"));
        var joinToken=repo.findReadyGraphTokens(tenant,mixed.runId(),10).get(0);
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,()->repo.advanceGraphToken(
                tenant,mixed.runId(),joinToken.tokenId(),"join","JOIN",java.util.List.of("after"),"join"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND token_id='"+joinToken.tokenId()+"' AND token_state='READY'"));
    }

    @Test
    void graphCoordinatorRoutesCompletedWorkerTaskAndFinishesRun() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','graph-task-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        GraphPlan plan=new GraphPlan(1,"ci","graph-task","1","test-hash",
                java.util.List.of(
                        new GraphPlan.Node("start","Start",GraphPlan.Kind.START,"start",null,null,null,null,null,null),
                        new GraphPlan.Node("task","Task",GraphPlan.Kind.TASK,"task",null,null,null,null,null,null),
                        new GraphPlan.Node("end","End",GraphPlan.Kind.END,"end",null,null,null,null,null,null)),
                java.util.List.of(
                        new GraphPlan.Edge("start-task","start","task",null,false),
                        new GraphPlan.Edge("task-end","task","end",null,false)));
        var run=repo.createRun(tenant,"graph-task-"+UUID.randomUUID(),"CREATE","{}",
                "graph-task-"+tenant,"1",new byte[32],"ci",GraphPlanCodec.toJson(plan),"{}","task");
        repo.startRun(tenant,run.runId());
        var coordinator=new GraphCoordinator(repo);
        var first=coordinator.advance(tenant,run.runId());
        assertEquals(0,first.controlTokensAdvanced());
        assertEquals(0,first.successorTokensCreated());
        var reservation=repo.reserveCapacity(tenant,run.runId(),"graph-task-capacity",1,
                "task","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),reservation.attemptId(),"worker-q","{}");
        var dispatchOutbox=repo.beginOutboxSend(tenant,10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:"+reservation.attemptId())).findFirst().orElseThrow();
        assertTrue(repo.markOutboxDelivered(tenant,dispatchOutbox.outboxId()));
        repo.acceptAttempt(tenant,run.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        repo.startAttempt(tenant,run.runId(),reservation.attemptId(),reservation.dispatchId(),"worker-1");
        repo.completeAttempt(tenant,run.runId(),reservation.attemptId(),"result","{\"value\":42}");

        var second=coordinator.advance(tenant,run.runId());
        assertEquals(1,second.completedTasksRouted());
        assertEquals(1,second.controlTokensAdvanced());
        assertTrue(second.runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='SUCCEEDED'"));
    }

    @Test
    void graphRunCannotSucceedWhileFailedTokenRemains() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','graph-failed-token-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"failed-token-"+UUID.randomUUID(),"CREATE","{}",
                "failed-token-"+tenant,"1",new byte[32],"ci","{}","{}","end");
        repo.startRun(tenant,run.runId());
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,run_id,token_state,node_key) VALUES (?,?,'FAILED','failed-branch')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,run.runId());ps.executeUpdate();
        }
        var end=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        var result=repo.advanceGraphToken(tenant,run.runId(),end.tokenId(),"end","END",java.util.List.of());
        assertFalse(result.runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='RUNNING'"));
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
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
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
        assertTrue(startup.scanned() >= 1);
        var recoveredItem=startup.items().stream()
                .filter(item -> item.candidate().attemptId().equals(reservation.attemptId()))
                .findFirst().orElseThrow();
        var result=recoveredItem.result();
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
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
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
        var recovery=new WorkerExecutionRecoveryService(repo,new WorkerExecutionRegistry());
        var startup=recovery.recoverAtStartup(100,"startup");
        assertEquals(1,startup.outbox().leftUncertain());
        var recoveredItem=startup.items().stream()
                .filter(item -> item.candidate().attemptId().equals(reservation.attemptId()))
                .findFirst().orElseThrow();
        var result=recoveredItem.result();
        assertEquals("MANUAL_REQUIRED",result.outcome());
        assertNotNull(result.interventionId());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"+tenant+"' AND dedup_key='command:"+command+"' AND outbox_state='SENDING'"));
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
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
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
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
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

    @Test
    void startupRecoveryScansBeyondOneBatch() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource();
        UUID tenant=UUID.randomUUID();
        try(Connection connection=ds.getConnection(); Statement statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','recovery-"+tenant+"','integration')");
        }

        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        for(int i=0;i<2;i++) {
            String requestId="recovery-request-"+i+"-"+UUID.randomUUID();
            var run=repo.createRun(tenant,requestId,"CREATE","{\"recovery\":"+i+"}",
                    "recovery-flow-"+i+"-"+tenant,"1",new byte[32],"ci","{}","{}","node-1");
            repo.startRun(tenant,run.runId());
            var reservation=repo.reserveCapacity(tenant,run.runId(),"recovery-capacity-"+i,1,
                    "node-1","PURE",Instant.now().plusSeconds(60));
            repo.dispatchAttempt(tenant,run.runId(),reservation.attemptId(),"worker-q","{}");
            repo.acceptAttempt(tenant,run.runId(),reservation.attemptId(),
                    reservation.dispatchId(),"worker-1");
            repo.startAttempt(tenant,run.runId(),reservation.attemptId(),
                    reservation.dispatchId(),"worker-1");
        }

        var recovery=new WorkerExecutionRecoveryService(repo,new WorkerExecutionRegistry());
        var result=recovery.recoverAtStartup(1,"startup-recovery-test");
        assertEquals(2,result.scanned(),"startup must continue until no STARTED Attempt remains");
        assertEquals(2,result.safeToRetry());
        assertEquals(0,result.manualRequired());
        assertTrue(repo.findInterruptedAttempts(10).isEmpty());
    }
    @Test
    void interruptedDispatchedAttemptIsAbandonedAndItsOutboxIsNotReplayed() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection connection=ds.getConnection(); Statement statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','restart-dispatched-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"restart-dispatched-"+UUID.randomUUID(),"CREATE","{}",
                "restart-dispatched-flow-"+tenant,"1",new byte[32],"ci","{}","{}","dispatch-node");
        repo.startRun(tenant,run.runId());
        var reservation=repo.reserveCapacity(tenant,run.runId(),"restart-dispatched-cap",1,
                "dispatch-node","PURE",Instant.now().plusSeconds(120));
        repo.dispatchAttempt(tenant,run.runId(),reservation.attemptId(),"worker-q",
                "{\"attemptId\":\""+reservation.attemptId()+"\"}");
        var workerMessage=repo.beginOutboxSend(tenant,10).stream()
                .filter(message -> message.dedupKey().equals("dispatch:"+reservation.attemptId()))
                .findFirst().orElseThrow();

        var recovery=new WorkerExecutionRecoveryService(repo,new WorkerExecutionRegistry());
        var startup=recovery.recoverAtStartup(20,"startup");
        var recovered=startup.items().stream()
                .filter(item -> item.candidate().attemptId().equals(reservation.attemptId()))
                .findFirst().orElseThrow();

        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+reservation.attemptId()+"' AND attempt_state='ABANDONED_SAFE'"));
        assertEquals("SAFE_TO_RETRY",recovered.result().outcome());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"+
                tenant+"' AND outbox_id='"+workerMessage.outboxId()+"' AND outbox_state='DEAD_LETTER'"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id='"+
                tenant+"' AND dedup_key='dispatch:"+reservation.attemptId()+"' AND outbox_state IN ('PENDING','SENDING')"));
    }

    @Test
    void lifecycleCoordinatorHandlesStartIdempotencyTenantIsolationAndMailboxEviction() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource();
        UUID tenant=UUID.randomUUID(), otherTenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','lc-"+tenant+"','integration')");
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    otherTenant+"','lc-other-"+otherTenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        String xml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "lc-"+tenant,"1",xml,Map.of()));
        var run=repo.createRun(tenant,"lc-"+UUID.randomUUID(),"CREATE","{}",
                "lc-"+tenant,"1",java.util.HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),"{}","start");

        assertTrue(repo.startRunIfCreated(tenant,run.runId()));
        assertFalse(repo.startRunIfCreated(tenant,run.runId()), "repeated startRunIfCreated on RUNNING+OPEN must be idempotent");
        assertTrue(repo.isOpenRun(tenant,run.runId()));
        assertFalse(repo.isOpenRun(otherTenant,run.runId()), "wrong tenant must never see Run as open");

        var executor=Executors.newFixedThreadPool(2);
        try(var lifecycle=new WorkflowRunLifecycleCoordinator(repo,new GraphCoordinator(repo),executor,8)) {
            assertThrows(IllegalStateException.class, () -> lifecycle.wake(otherTenant,run.runId()));
            lifecycle.startRun(tenant,run.runId()).get(5,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(lifecycle.activeMailboxCount()!=0 && System.nanoTime()<deadline) Thread.yield();
            assertEquals(0,lifecycle.activeMailboxCount(),"completed Run mailbox must be reclaimed");
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                    tenant+"' AND run_id='"+run.runId()+"' AND status='SUCCEEDED' AND control_gate='CLOSED'"));
            assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                    () -> repo.startRunIfCreated(tenant,run.runId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void graphCoordinatorDoesNotStarveControlTokensBehindReadyTasksWithBatchSizeOne() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','starve-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        GraphPlan plan=new GraphPlan(1,"ci","starve","1","hash",
                List.of(
                        new GraphPlan.Node("start","Start",GraphPlan.Kind.START,"startEvent",null,null,null,null,null,null),
                        new GraphPlan.Node("split","Split",GraphPlan.Kind.SPLIT_PARALLEL,"parallelGateway",null,null,null,null,null,null),
                        new GraphPlan.Node("task1","Task1",GraphPlan.Kind.TASK,"task","P1",1L,null,null,null,null),
                        new GraphPlan.Node("task2","Task2",GraphPlan.Kind.TASK,"task","P2",2L,null,null,null,null),
                        new GraphPlan.Node("xor","Xor",GraphPlan.Kind.SPLIT_XOR,"exclusiveGateway",null,null,null,null,null,null),
                        new GraphPlan.Node("task3","Task3",GraphPlan.Kind.TASK,"task","P3",3L,null,null,null,null),
                        new GraphPlan.Node("task4","Task4",GraphPlan.Kind.TASK,"task","P4",4L,null,null,null,null),
                        new GraphPlan.Node("join","Join",GraphPlan.Kind.JOIN_PARALLEL,"parallelGateway",null,null,null,null,null,null),
                        new GraphPlan.Node("end","End",GraphPlan.Kind.END,"endEvent",null,null,null,null,null,null)),
                List.of(
                        new GraphPlan.Edge("e1","start","split",null,false),
                        new GraphPlan.Edge("e2","split","task1",null,false),
                        new GraphPlan.Edge("e3","split","task2",null,false),
                        new GraphPlan.Edge("e4","split","xor",null,false),
                        new GraphPlan.Edge("e5","xor","task3","goThree == true",false),
                        new GraphPlan.Edge("e6","xor","task4",null,true),
                        new GraphPlan.Edge("e7","task1","join",null,false),
                        new GraphPlan.Edge("e8","task2","join",null,false),
                        new GraphPlan.Edge("e9","task3","join",null,false),
                        new GraphPlan.Edge("e10","task4","join",null,false),
                        new GraphPlan.Edge("e11","join","end",null,false)));
        GraphCoordinator.validateGraphPlan(plan);
        var run=repo.createRun(tenant,"starve-"+UUID.randomUUID(),"CREATE","{}",
                "starve-"+tenant,"1",new byte[32],"ci",GraphPlanCodec.toJson(plan),"{\"goThree\":true}","start");
        repo.startRun(tenant,run.runId());

        // batchSize=1: task1 and task2 stay READY ahead of xor, yet xor MUST still advance to task3!
        var summary=new GraphCoordinator(repo,1,100).advance(tenant,run.runId());
        assertEquals(3,summary.controlTokensAdvanced(),"start, split, and xor must all advance despite READY tasks");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='task3' AND token_state='READY'"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='xor' AND token_state='READY'"));
    }

    @Test
    void graphCoordinatorExplicitOverrideMergesWithPersistedInputAndFlowVariables() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','merge-vars-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        String xml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:exclusiveGateway id="choice" default="choice-b"/>
                    <bpmn:endEvent id="end-a"/>
                    <bpmn:endEvent id="end-b"/>
                    <bpmn:sequenceFlow id="s1" sourceRef="start" targetRef="choice"/>
                    <bpmn:sequenceFlow id="choice-a" sourceRef="choice" targetRef="end-a">
                      <bpmn:conditionExpression>baseOk == true &amp;&amp; persistedCount == 20 &amp;&amp; overrideFlag == true</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="choice-b" sourceRef="choice" targetRef="end-b"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "merge-vars-"+tenant,"1",xml,Map.of()));
        var run=repo.createRun(tenant,"merge-vars-"+UUID.randomUUID(),"CREATE","{}",
                "merge-vars-"+tenant,"1",java.util.HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),
                "{\"baseOk\":true,\"persistedCount\":10}","start");
        repo.startRun(tenant,run.runId());
        repo.putFlowVariable(tenant,run.runId(),"persistedCount","20");

        var summary=new GraphCoordinator(repo).advance(tenant,run.runId(),Map.of("overrideFlag",true));
        assertTrue(summary.runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='end-a' AND token_state='CONSUMED'"));
    }

    @Test
    void endBranchDuplicateArrivalFailsClosedAndRunSucceededEventFollowsNodeAdvance() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','end-dup-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"end-dup-"+UUID.randomUUID(),"CREATE","{}",
                "end-dup-"+tenant,"1",new byte[32],"ci","{}","{}","split");
        repo.startRun(tenant,run.runId());
        var splitTok=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        repo.advanceGraphToken(tenant,run.runId(),splitTok.tokenId(),"split","SPLIT",List.of("end-a","end-b"));
        var tokens=repo.findReadyGraphTokens(tenant,run.runId(),10);
        var tokA=tokens.stream().filter(t -> t.nodeKey().equals("end-a")).findFirst().orElseThrow();
        var tokB=tokens.stream().filter(t -> t.nodeKey().equals("end-b")).findFirst().orElseThrow();

        // First branch arrives at END.
        assertFalse(repo.advanceGraphToken(tenant,run.runId(),tokA.tokenId(),"end-a","END",List.of()).runCompleted());

        // Inject a corrupted duplicate READY token carrying the already-arrived branchId.
        UUID dupTokenId=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.execution_token
                  (tenant_id,token_id,run_id,token_state,node_key,split_id,branch_id)
                VALUES (?,?,?,'READY','end-a',?,?)
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,dupTokenId);ps.setObject(3,run.runId());
            ps.setObject(4,tokA.splitId());ps.setObject(5,tokA.branchId());ps.executeUpdate();
        }
        assertThrows(WorkflowRuntimeRepository.StaleRuntimeWriteException.class,
                () -> repo.advanceGraphToken(tenant,run.runId(),dupTokenId,"end-a","END",List.of()));
        // Remove the injected duplicate token so the valid second branch can cleanly complete the Run.
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement(
                "DELETE FROM workflow_runtime.execution_token WHERE tenant_id=? AND token_id=?")) {
            ps.setObject(1,tenant);ps.setObject(2,dupTokenId);ps.executeUpdate();
        }

        var done=repo.advanceGraphToken(tenant,run.runId(),tokB.tokenId(),"end-b","END",List.of());
        assertTrue(done.runCompleted());
        int lastAdvanceSeq=scalar(ds,"SELECT max(event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND event_type='GRAPH_NODE_ADVANCED'");
        int succeededSeq=scalar(ds,"SELECT max(event_seq) FROM workflow_runtime.runtime_event WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND event_type='RUN_SUCCEEDED'");
        assertTrue(succeededSeq > lastAdvanceSeq,
                "RUN_SUCCEEDED event_seq ("+succeededSeq+") must strictly follow final GRAPH_NODE_ADVANCED ("+lastAdvanceSeq+")");
    }

    @Test
    void terminalGuardMatrixBlocksRunSuccessWhileLeaseOrInterventionIsActive() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','guard-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"guard-"+UUID.randomUUID(),"CREATE","{}",
                "guard-"+tenant,"1",new byte[32],"ci","{}","{}","end");
        repo.startRun(tenant,run.runId());
        repo.acquireResourceLease(tenant,run.runId(),"centrifuge-1","RUN",run.runId(),
                Instant.now().plusSeconds(60),"{\"guard\":true}");
        var endTok=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        var res=repo.advanceGraphToken(tenant,run.runId(),endTok.tokenId(),"end","END",List.of());
        assertFalse(res.runCompleted(),"ACTIVE resource_lease must prevent Run from becoming SUCCEEDED");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='RUNNING'"));
    }

    @Test
    void workerExportedFlowVariablesDriveDownstreamNestedJsonXorRouting() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','export-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        String xml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="read-plate" protocolName="ReadPlate" resource="10"/>
                    <bpmn:exclusiveGateway id="qc" default="qc-fail"/>
                    <bpmn:endEvent id="end-pass"/>
                    <bpmn:endEvent id="end-fail"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="read-plate"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="read-plate" targetRef="qc"/>
                    <bpmn:sequenceFlow id="qc-pass" sourceRef="qc" targetRef="end-pass">
                      <bpmn:conditionExpression>readPlate.ok == true &amp;&amp; readPlate.od &gt; 1.0</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="qc-fail" sourceRef="qc" targetRef="end-fail"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "export-"+tenant,"1",xml,Map.of()));
        GraphCoordinator.validateGraphPlan(plan);
        var run=repo.createRun(tenant,"export-"+UUID.randomUUID(),"CREATE","{}",
                "export-"+tenant,"1",java.util.HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),"{}","start");
        repo.startRun(tenant,run.runId());
        var coordinator=new GraphCoordinator(repo);
        coordinator.advance(tenant,run.runId());

        var res=repo.reserveCapacity(tenant,run.runId(),"export-cap",1,"read-plate","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),res.attemptId(),"worker-q","{}");
        var outbox=repo.beginOutboxSend(tenant,10).stream()
                .filter(m -> m.dedupKey().equals("dispatch:"+res.attemptId())).findFirst().orElseThrow();
        repo.markOutboxDelivered(tenant,outbox.outboxId());
        repo.acceptAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"worker-1");
        repo.startAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"worker-1");
        Map<String,String> exports=Map.of("readPlate","{\"ok\":true,\"od\":1.42}");
        repo.completeAttempt(tenant,run.runId(),res.attemptId(),"result","{\"ok\":true,\"od\":1.42}",exports);
        // Identical replay succeeds; conflicting export value is rejected.
        repo.completeAttempt(tenant,run.runId(),res.attemptId(),"result","{\"ok\":true,\"od\":1.42}",exports);
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,
                () -> repo.completeAttempt(tenant,run.runId(),res.attemptId(),"result",
                        "{\"ok\":true,\"od\":1.42}",Map.of("readPlate","{\"ok\":false,\"od\":0.1}")));

        var done=coordinator.advance(tenant,run.runId());
        assertTrue(done.runCompleted());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='end-pass' AND token_state='CONSUMED'"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND node_key='end-fail'"));
    }

    @Test
    void createdAttemptCrashRecoveryAndFailAttemptSeparatePureFromUncertainDeviceSideEffects() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','fail-rec-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));

        // 1. Crash between reserveCapacity (CREATED) and dispatchAttempt must recover token & release capacity.
        var createdCrashRun=repo.createRun(tenant,"created-crash-"+UUID.randomUUID(),"CREATE","{}",
                "created-crash-"+tenant,"1",new byte[32],"ci","{}","{}","task-1");
        repo.startRun(tenant,createdCrashRun.runId());
        var createdRes=repo.reserveCapacity(tenant,createdCrashRun.runId(),"created-cap",1,
                "task-1","PURE",Instant.now().plusSeconds(60));
        assertEquals(1,scalar(ds,"SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"+
                tenant+"' AND capacity_key='created-cap'"));
        var recovery=new WorkerExecutionRecoveryService(repo,new WorkerExecutionRegistry());
        var startup=recovery.recoverAtStartup(50,"startup-created-test");
        assertTrue(startup.items().stream().anyMatch(i -> i.candidate().attemptId().equals(createdRes.attemptId())
                && "SAFE_TO_RETRY".equals(i.result().outcome())));
        assertEquals(0,scalar(ds,"SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"+
                tenant+"' AND capacity_key='created-cap'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                tenant+"' AND run_id='"+createdCrashRun.runId()+"' AND token_state='READY'"));

        // 2. Deterministic PURE failAttempt marks Run FAILED and releases capacity.
        var pureFailRun=repo.createRun(tenant,"pure-fail-"+UUID.randomUUID(),"CREATE","{}",
                "pure-fail-"+tenant,"1",new byte[32],"ci","{}","{}","pure-task");
        repo.startRun(tenant,pureFailRun.runId());
        var pureRes=repo.reserveCapacity(tenant,pureFailRun.runId(),"pure-fail-cap",1,
                "pure-task","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,pureFailRun.runId(),pureRes.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,pureFailRun.runId(),pureRes.attemptId(),pureRes.dispatchId(),"w1");
        repo.startAttempt(tenant,pureFailRun.runId(),pureRes.attemptId(),pureRes.dispatchId(),"w1");
        var pureOutcome=repo.failAttempt(tenant,pureFailRun.runId(),pureRes.attemptId(),"{\"code\":\"CALC_ERR\"}",false);
        assertEquals("FAILED",pureOutcome.outcome());
        assertNull(pureOutcome.interventionId());
        assertEquals(0,scalar(ds,"SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"+
                tenant+"' AND capacity_key='pure-fail-cap'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+pureFailRun.runId()+"' AND status='FAILED' AND control_gate='CLOSED'"));

        // 3. Failure after SIDE_EFFECT_STARTED escalates to UNKNOWN + MANUAL_REQUIRED without releasing device lease.
        var devFailRun=repo.createRun(tenant,"dev-fail-"+UUID.randomUUID(),"CREATE","{}",
                "dev-fail-"+tenant,"1",new byte[32],"ci","{}","{}","dev-task");
        repo.startRun(tenant,devFailRun.runId());
        var lease=repo.acquireResourceLease(tenant,devFailRun.runId(),"sealer-1","RUN",devFailRun.runId(),
                Instant.now().plusSeconds(120),"{}");
        var devRes=repo.reserveCapacity(tenant,devFailRun.runId(),"dev-fail-cap",1,
                "dev-task","DEVICE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,devFailRun.runId(),devRes.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,devFailRun.runId(),devRes.attemptId(),devRes.dispatchId(),"w1");
        repo.startAttempt(tenant,devFailRun.runId(),devRes.attemptId(),devRes.dispatchId(),"w1");
        repo.recordSideEffectFact(tenant,devFailRun.runId(),devRes.attemptId(),"w1","send-1","SIDE_EFFECT_STARTED","{}");
        var devOutcome=repo.failAttempt(tenant,devFailRun.runId(),devRes.attemptId(),"{\"code\":\"IO_TIMEOUT\"}",false);
        assertEquals("MANUAL_REQUIRED",devOutcome.outcome());
        assertNotNull(devOutcome.interventionId());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+devRes.attemptId()+"' AND attempt_state='UNKNOWN'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+devFailRun.runId()+"' AND status='MANUAL_REQUIRED' AND recovery_state='BLOCKED'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.resource_lease WHERE tenant_id='"+
                tenant+"' AND lease_id='"+lease.leaseId()+"' AND lease_state='ACTIVE'"));
    }

    @Test
    void coordinatorPoisonPillFaultTerminalizesRunAndPreventsInfiniteReplay() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','poison-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        String xml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:exclusiveGateway id="choice"/>
                    <bpmn:endEvent id="end-a"/>
                    <bpmn:endEvent id="end-b"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="choice"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="choice" targetRef="end-a">
                      <bpmn:conditionExpression>missingVar == true</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="f3" sourceRef="choice" targetRef="end-b">
                      <bpmn:conditionExpression>missingVar == false</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan plan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "poison-"+tenant,"1",xml,Map.of()));
        var run=repo.createRun(tenant,"poison-"+UUID.randomUUID(),"CREATE","{}",
                "poison-"+tenant,"1",java.util.HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),"{}","start");
        repo.startRun(tenant,run.runId());
        var coordinator=new GraphCoordinator(repo);
        assertThrows(IllegalArgumentException.class, () -> coordinator.advance(tenant,run.runId()));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='FAILED' AND control_gate='CLOSED'"));
        // Subsequent replay sees FAILED/CLOSED and does not loop or throw.
        var replay=coordinator.advance(tenant,run.runId());
        assertEquals(0,replay.controlTokensAdvanced());
        assertFalse(replay.runCompleted());
    }

    @Test
    void concurrentJavaAdvanceGraphTokenReleasesParallelJoinExactlyOnce() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','conc-join-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"conc-join-"+UUID.randomUUID(),"CREATE","{}",
                "conc-join-"+tenant,"1",new byte[32],"ci","{}","{}","split");
        repo.startRun(tenant,run.runId());
        var splitTok=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        repo.advanceGraphToken(tenant,run.runId(),splitTok.tokenId(),"split","SPLIT",List.of("b1","b2"));
        for(var t : repo.findReadyGraphTokens(tenant,run.runId(),10)) {
            repo.advanceGraphToken(tenant,run.runId(),t.tokenId(),t.nodeKey(),"SEQUENCE",List.of("join"));
        }
        var joinTokens=repo.findReadyGraphTokens(tenant,run.runId(),10);
        assertEquals(2,joinTokens.size());

        var pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2);
        CountDownLatch go=new CountDownLatch(1);
        try {
            Future<WorkflowRuntimeRepository.GraphAdvanceResult> f1=pool.submit(() -> {
                ready.countDown();
                go.await(5,TimeUnit.SECONDS);
                return repo.advanceGraphToken(tenant,run.runId(),joinTokens.get(0).tokenId(),
                        "join","JOIN",List.of("end"),"join");
            });
            Future<WorkflowRuntimeRepository.GraphAdvanceResult> f2=pool.submit(() -> {
                ready.countDown();
                go.await(5,TimeUnit.SECONDS);
                return repo.advanceGraphToken(tenant,run.runId(),joinTokens.get(1).tokenId(),
                        "join","JOIN",List.of("end"),"join");
            });
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            go.countDown();
            var r1=f1.get(5,TimeUnit.SECONDS);
            var r2=f2.get(5,TimeUnit.SECONDS);
            assertEquals(1,r1.tokensCreated()+r2.tokensCreated(),"Parallel Join must release exactly one successor Token");
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id='"+
                    tenant+"' AND run_id='"+run.runId()+"' AND node_key='end' AND token_state='READY'"));
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.join_activation WHERE tenant_id='"+
                    tenant+"' AND run_id='"+run.runId()+"' AND join_key='join' AND join_state='RELEASED'"));
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.split_activation WHERE tenant_id='"+
                    tenant+"' AND run_id='"+run.runId()+"' AND split_state='CLOSED'"));
        } finally {
            pool.shutdownNow();
        }
    }

}
