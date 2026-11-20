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

    @Test
    void graphFaultWithActivePureAttemptEntersFailureDrainAndBlocksLateTerminalCompletion() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','fault-drain-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        // Split -> taskA (active STARTED pure attempt) & taskB -> xorB (missing variable graph fault).
        GraphPlan validPlan=new GraphPlan(1,"ci","fault-drain-valid","1","hash",
                List.of(
                        new GraphPlan.Node("start","Start",GraphPlan.Kind.START,"startEvent",null,null,null,null,null,null),
                        new GraphPlan.Node("split","Split",GraphPlan.Kind.SPLIT_PARALLEL,"parallelGateway",null,null,null,null,null,null),
                        new GraphPlan.Node("taskA","TaskA",GraphPlan.Kind.TASK,"task","P1",1L,null,null,null,null),
                        new GraphPlan.Node("taskB","TaskB",GraphPlan.Kind.TASK,"task","P2",2L,null,null,null,null),
                        new GraphPlan.Node("xorB","XorB",GraphPlan.Kind.SPLIT_XOR,"exclusiveGateway",null,null,null,null,null,null),
                        new GraphPlan.Node("taskB1","TaskB1",GraphPlan.Kind.TASK,"task","P3",3L,null,null,null,null),
                        new GraphPlan.Node("taskB2","TaskB2",GraphPlan.Kind.TASK,"task","P4",4L,null,null,null,null),
                        new GraphPlan.Node("join","Join",GraphPlan.Kind.JOIN_PARALLEL,"parallelGateway",null,null,null,null,null,null),
                        new GraphPlan.Node("end","End",GraphPlan.Kind.END,"endEvent",null,null,null,null,null,null)),
                List.of(
                        new GraphPlan.Edge("e1","start","split",null,false),
                        new GraphPlan.Edge("e2","split","taskA",null,false),
                        new GraphPlan.Edge("e3","split","taskB",null,false),
                        new GraphPlan.Edge("e4","taskA","join",null,false),
                        new GraphPlan.Edge("e5","taskB","xorB",null,false),
                        new GraphPlan.Edge("e6","xorB","taskB1","missingFlag == true",false),
                        new GraphPlan.Edge("e7","xorB","taskB2","missingFlag == false",false),
                        new GraphPlan.Edge("e8","taskB1","join",null,false),
                        new GraphPlan.Edge("e9","taskB2","join",null,false),
                        new GraphPlan.Edge("e10","join","end",null,false)));
        GraphCoordinator.validateGraphPlan(validPlan);
        var run=repo.createRun(tenant,"fault-drain-"+UUID.randomUUID(),"CREATE","{}",
                "fault-drain-"+tenant,"1",new byte[32],"ci",GraphPlanCodec.toJson(validPlan),"{}","start");
        repo.startRun(tenant,run.runId());
        var coordinator=new GraphCoordinator(repo);
        coordinator.advance(tenant,run.runId());

        // Start both taskA and taskB.
        var resA=repo.reserveCapacity(tenant,run.runId(),"fd-cap",2,"taskA","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resA.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),resA.attemptId(),resA.dispatchId(),"w1");
        repo.startAttempt(tenant,run.runId(),resA.attemptId(),resA.dispatchId(),"w1");

        var resB=repo.reserveCapacity(tenant,run.runId(),"fd-cap",2,"taskB","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resB.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),resB.attemptId(),resB.dispatchId(),"w2");
        repo.startAttempt(tenant,run.runId(),resB.attemptId(),resB.dispatchId(),"w2");
        repo.completeAttempt(tenant,run.runId(),resB.attemptId(),"out","{\"ok\":true}");

        // Branch B advances to xorB and hits missingFlag while taskA is STILL STARTED (PURE).
        assertThrows(IllegalArgumentException.class, () -> coordinator.advance(tenant,run.runId()));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='STOP_REQUESTED' AND control_gate='STOP'"),
                "Graph fault with active PURE Attempt must enter FAILURE drain instead of jumping to FAILED");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.drain_record WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND drain_kind='FAILURE' AND drain_state='REQUESTED'"));

        // During STOP_REQUESTED drain, the in-flight taskA IS allowed to complete cleanly to a safe point.
        repo.completeAttempt(tenant,run.runId(),resA.attemptId(),"out","{\"drained\":true}");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+resA.attemptId()+"' AND attempt_state='SUCCEEDED'"));

        // Now verify that once a Run IS terminal (FAILED), a late STARTED Attempt CANNOT commit completion!
        var terminalRun=repo.createRun(tenant,"late-term-"+UUID.randomUUID(),"CREATE","{}",
                "late-term-"+tenant,"1",new byte[32],"ci","{}","{}","late-task");
        repo.startRun(tenant,terminalRun.runId());
        var lateRes=repo.reserveCapacity(tenant,terminalRun.runId(),"late-cap",1,"late-task","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,terminalRun.runId(),lateRes.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,terminalRun.runId(),lateRes.attemptId(),lateRes.dispatchId(),"w1");
        repo.startAttempt(tenant,terminalRun.runId(),lateRes.attemptId(),lateRes.dispatchId(),"w1");
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement(
                "UPDATE workflow_runtime.workflow_run SET status='FAILED',control_gate='CLOSED' WHERE tenant_id=? AND run_id=?")) {
            ps.setObject(1,tenant);ps.setObject(2,terminalRun.runId());ps.executeUpdate();
        }
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.completeAttempt(tenant,terminalRun.runId(),lateRes.attemptId(),"out","{\"late\":true}",
                        Map.of("shouldNotWrite","true")));
        int eventsBeforeTerminalReject=scalar(ds,"SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id='"+
                tenant+"' AND run_id='"+terminalRun.runId()+"'");
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.failAttempt(tenant,terminalRun.runId(),lateRes.attemptId(),"{\"late\":true}",false));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.cancelAttempt(tenant,terminalRun.runId(),lateRes.attemptId(),"late-cancel"));
        assertEquals(eventsBeforeTerminalReject,scalar(ds,"SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id='"+
                tenant+"' AND run_id='"+terminalRun.runId()+"'"),
                "Terminal Run must not append new events on rejected failAttempt/cancelAttempt");
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.branch_output WHERE tenant_id='"+
                tenant+"' AND run_id='"+terminalRun.runId()+"'"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.flow_variable WHERE tenant_id='"+
                tenant+"' AND run_id='"+terminalRun.runId()+"'"));
        // Clean synthetic STARTED test row so shared-DB startup recovery tests stay isolated.
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement(
                "UPDATE workflow_runtime.node_attempt SET attempt_state='CANCELLED' WHERE tenant_id=? AND attempt_id=?")) {
            ps.setObject(1,tenant);ps.setObject(2,lateRes.attemptId());ps.executeUpdate();
        }
    }

    @Test
    void graphFaultWithActiveDeviceSideEffectEscalatesToManualRequiredAndBlocksCompletion() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','fault-dev-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"fault-dev-"+UUID.randomUUID(),"CREATE","{}",
                "fault-dev-"+tenant,"1",new byte[32],"ci","{}","{}","dev-task");
        repo.startRun(tenant,run.runId());
        var lease=repo.acquireResourceLease(tenant,run.runId(),"robot-arm-1","RUN",run.runId(),
                Instant.now().plusSeconds(120),"{}");
        var res=repo.reserveCapacity(tenant,run.runId(),"fault-dev-cap",1,"dev-task","DEVICE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),res.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"w1");
        repo.startAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"w1");
        repo.recordSideEffectFact(tenant,run.runId(),res.attemptId(),"w1","send-arm","SIDE_EFFECT_STARTED","{}");

        var outcome=repo.failGraphRun(tenant,run.runId(),null,"IllegalArgumentException","missing condition var");
        assertEquals("MANUAL_REQUIRED",outcome.outcome());
        assertNotNull(outcome.interventionId());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='MANUAL_REQUIRED' AND control_gate='CLOSED' AND recovery_state='BLOCKED'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+res.attemptId()+"' AND attempt_state='UNKNOWN'"),
                "Escalation to MANUAL_REQUIRED must atomically mark side-effect-crossed Attempt UNKNOWN");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.resource_lease WHERE tenant_id='"+
                tenant+"' AND lease_id='"+lease.leaseId()+"' AND lease_state='ACTIVE'"));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.completeAttempt(tenant,run.runId(),res.attemptId(),"out","{\"done\":true}"));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.failAttempt(tenant,run.runId(),res.attemptId(),"{\"err\":true}",false));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.cancelAttempt(tenant,run.runId(),res.attemptId(),"cancel-blocked"));
    }

    @Test
    void closedControlGateBlocksDispatchAcceptStartAndNewCommandDuringDrain() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','gate-block-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"gate-block-"+UUID.randomUUID(),"CREATE","{}",
                "gate-block-"+tenant,"1",new byte[32],"ci","{}","{}","split");
        repo.startRun(tenant,run.runId());
        var splitTok=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        repo.advanceGraphToken(tenant,run.runId(),splitTok.tokenId(),"split","SPLIT",
                List.of("t-created","t-dispatched","t-accepted","t-started"));

        var resCreated=repo.reserveCapacity(tenant,run.runId(),"gb-cap",4,"t-created","PURE",Instant.now().plusSeconds(60));

        var resDispatched=repo.reserveCapacity(tenant,run.runId(),"gb-cap",4,"t-dispatched","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resDispatched.attemptId(),"q","{}");

        var resAccepted=repo.reserveCapacity(tenant,run.runId(),"gb-cap",4,"t-accepted","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resAccepted.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),resAccepted.attemptId(),resAccepted.dispatchId(),"w1");

        var resStarted=repo.reserveCapacity(tenant,run.runId(),"gb-cap",4,"t-started","DEVICE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resStarted.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),resStarted.attemptId(),resStarted.dispatchId(),"w2");
        repo.startAttempt(tenant,run.runId(),resStarted.attemptId(),resStarted.dispatchId(),"w2");

        // Close control gate via PAUSE drain.
        repo.requestDrain(tenant,run.runId(),"PAUSE");

        // Once gate is closed, NO new dispatch, acceptance, worker start, or new command creation is admitted.
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.dispatchAttempt(tenant,run.runId(),resCreated.attemptId(),"q","{}"));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.acceptAttempt(tenant,run.runId(),resDispatched.attemptId(),resDispatched.dispatchId(),"w3"));
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.startAttempt(tenant,run.runId(),resAccepted.attemptId(),resAccepted.dispatchId(),"w1"));
        UUID startedActivation=activationForAttempt(ds,tenant,resStarted.attemptId());
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.createCommand(tenant,run.runId(),startedActivation,resStarted.attemptId(),
                        "dev-1","Run","QUERYABLE","{}"));

        // Unstarted / no-side-effect attempts CAN still be safely cancelled during drain.
        assertEquals("CANCELLED",repo.cancelAttempt(tenant,run.runId(),resCreated.attemptId(),"drain").outcome());
        assertEquals("CANCELLED",repo.cancelAttempt(tenant,run.runId(),resDispatched.attemptId(),"drain").outcome());
        assertEquals("CANCELLED",repo.cancelAttempt(tenant,run.runId(),resAccepted.attemptId(),"drain").outcome());
        assertEquals("CANCELLED",repo.cancelAttempt(tenant,run.runId(),resStarted.attemptId(),"drain").outcome());
        assertEquals(0,scalar(ds,"SELECT reserved_count FROM workflow_runtime.scheduler_capacity WHERE tenant_id='"+
                tenant+"' AND capacity_key='gb-cap'"));
    }

    @Test
    void unsentCreatedCommandEntersFailureDrainInsteadOfFalseManualRequired() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','unsent-cmd-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"unsent-cmd-"+UUID.randomUUID(),"CREATE","{}",
                "unsent-cmd-"+tenant,"1",new byte[32],"ci","{}","{}","dev-task");
        repo.startRun(tenant,run.runId());
        var res=repo.reserveCapacity(tenant,run.runId(),"uc-cap",1,"dev-task","DEVICE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),res.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"w1");
        repo.startAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"w1");
        // Command is CREATED only (never SEND_STARTED, no side-effect boundary crossed).
        UUID cmdId=repo.createCommand(tenant,run.runId(),activationForAttempt(ds,tenant,res.attemptId()),
                res.attemptId(),"dev-1","Heat","QUERYABLE","{\"temp\":37}");

        var outcome=repo.failGraphRun(tenant,run.runId(),null,"IllegalArgumentException","sibling branch fault");
        assertEquals("DRAINING_FAILURE",outcome.outcome(),
                "Unsent CREATED command must enter FAILURE drain, not false MANUAL_REQUIRED");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='STOP_REQUESTED' AND control_gate='STOP'"));
        // Cancel cleanly cancels the unsent CREATED command and releases capacity.
        assertEquals("CANCELLED",repo.cancelAttempt(tenant,run.runId(),res.attemptId(),"failure-drain").outcome());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.command_ledger WHERE tenant_id='"+
                tenant+"' AND command_id='"+cmdId+"' AND command_state='CANCELLED'"));
    }

    @Test
    void completeAttemptReplayRejectsDifferentOutputKeyEvenWhenPayloadAndExportsMatch() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','outkey-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"outkey-"+UUID.randomUUID(),"CREATE","{}",
                "outkey-"+tenant,"1",new byte[32],"ci","{}","{}","task-1");
        repo.startRun(tenant,run.runId());
        var res=repo.reserveCapacity(tenant,run.runId(),"ok-cap",1,"task-1","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),res.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"w1");
        repo.startAttempt(tenant,run.runId(),res.attemptId(),res.dispatchId(),"w1");

        Map<String,String> exports=Map.of("k","1");
        repo.completeAttempt(tenant,run.runId(),res.attemptId(),"primary","{\"ok\":true}",exports);
        // Same result + same exports + same outputKey -> idempotent replay succeeds.
        repo.completeAttempt(tenant,run.runId(),res.attemptId(),"primary","{\"ok\":true}",exports);
        // Same result + same exports + DIFFERENT outputKey -> must fail with IdempotencyConflictException.
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,
                () -> repo.completeAttempt(tenant,run.runId(),res.attemptId(),"differentKey","{\"ok\":true}",exports));
    }

    @Test
    void sideEffectStartedWithoutCompletedIsUncertainWhileStartedPlusCompletedEntersFailureDrain() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','se-contrast-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));

        // Control A: SIDE_EFFECT_STARTED without SIDE_EFFECT_COMPLETED -> uncertain -> MANUAL_REQUIRED + Attempt UNKNOWN.
        var runA=repo.createRun(tenant,"se-uncertain-"+UUID.randomUUID(),"CREATE","{}",
                "se-uncertain-"+tenant,"1",new byte[32],"ci","{}","{}","task-a");
        repo.startRun(tenant,runA.runId());
        var resA=repo.reserveCapacity(tenant,runA.runId(),"se-cap-a",1,"task-a","REVERSIBLE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,runA.runId(),resA.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,runA.runId(),resA.attemptId(),resA.dispatchId(),"w1");
        repo.startAttempt(tenant,runA.runId(),resA.attemptId(),resA.dispatchId(),"w1");
        repo.recordSideEffectFact(tenant,runA.runId(),resA.attemptId(),"w1","step-1-start","SIDE_EFFECT_STARTED","{}");
        var outA=repo.failGraphRun(tenant,runA.runId(),null,"IllegalArgumentException","sibling fault");
        assertEquals("MANUAL_REQUIRED",outA.outcome());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+resA.attemptId()+"' AND attempt_state='UNKNOWN'"));

        // Control B: SIDE_EFFECT_STARTED + SIDE_EFFECT_COMPLETED (before completeAttempt) -> NOT uncertain -> FAILURE drain.
        var runB=repo.createRun(tenant,"se-completed-"+UUID.randomUUID(),"CREATE","{}",
                "se-completed-"+tenant,"1",new byte[32],"ci","{}","{}","task-b");
        repo.startRun(tenant,runB.runId());
        var resB=repo.reserveCapacity(tenant,runB.runId(),"se-cap-b",1,"task-b","REVERSIBLE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,runB.runId(),resB.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,runB.runId(),resB.attemptId(),resB.dispatchId(),"w2");
        repo.startAttempt(tenant,runB.runId(),resB.attemptId(),resB.dispatchId(),"w2");
        repo.recordSideEffectFact(tenant,runB.runId(),resB.attemptId(),"w2","step-1-start","SIDE_EFFECT_STARTED","{}");
        repo.recordSideEffectFact(tenant,runB.runId(),resB.attemptId(),"w2","step-1-done","SIDE_EFFECT_COMPLETED","{}");

        var outB=repo.failGraphRun(tenant,runB.runId(),null,"IllegalArgumentException","sibling fault");
        assertEquals("DRAINING_FAILURE",outB.outcome(),
                "Attempt with matching SIDE_EFFECT_COMPLETED must enter FAILURE drain, not false MANUAL_REQUIRED");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+runB.runId()+"' AND status='STOP_REQUESTED' AND control_gate='STOP'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+resB.attemptId()+"' AND attempt_state='STARTED'"));
        // cancelAttempt refuses to erase an already-completed side effect or mislabel it UNKNOWN.
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.cancelAttempt(tenant,runB.runId(),resB.attemptId(),"cannot-cancel-completed"));
        // Worker finishes committing the already-completed side effect to safe point during drain.
        repo.completeAttempt(tenant,runB.runId(),resB.attemptId(),"result","{\"done\":true}");
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+resB.attemptId()+"' AND attempt_state='SUCCEEDED'"));

        // Control C: second SIDE_EFFECT_STARTED after first COMPLETED (started=2 > completed=1) -> uncertain -> MANUAL_REQUIRED.
        var runC=repo.createRun(tenant,"se-multi-"+UUID.randomUUID(),"CREATE","{}",
                "se-multi-"+tenant,"1",new byte[32],"ci","{}","{}","task-c");
        repo.startRun(tenant,runC.runId());
        var resC=repo.reserveCapacity(tenant,runC.runId(),"se-cap-c",1,"task-c","REVERSIBLE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,runC.runId(),resC.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,runC.runId(),resC.attemptId(),resC.dispatchId(),"w3");
        repo.startAttempt(tenant,runC.runId(),resC.attemptId(),resC.dispatchId(),"w3");
        repo.recordSideEffectFact(tenant,runC.runId(),resC.attemptId(),"w3","step-1-start","SIDE_EFFECT_STARTED","{}");
        repo.recordSideEffectFact(tenant,runC.runId(),resC.attemptId(),"w3","step-1-done","SIDE_EFFECT_COMPLETED","{}");
        repo.recordSideEffectFact(tenant,runC.runId(),resC.attemptId(),"w3","step-2-start","SIDE_EFFECT_STARTED","{}");
        var outC=repo.failGraphRun(tenant,runC.runId(),null,"IllegalArgumentException","sibling fault");
        assertEquals("MANUAL_REQUIRED",outC.outcome());
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+resC.attemptId()+"' AND attempt_state='UNKNOWN'"));
    }

    @Test
    void corruptPersistedVariableJsonTriggersFailGraphRunAndPreventsPoisonPillLoop() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','corrupt-json-"+tenant+"','integration')");
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
                "corrupt-json-"+tenant,"1",xml,Map.of()));
        // Valid JSONB array, which fails ObjectMapper Map<String,Object> deserialization inside loadPersistedVariables().
        var run=repo.createRun(tenant,"corrupt-json-"+UUID.randomUUID(),"CREATE","{}",
                "corrupt-json-"+tenant,"1",java.util.HexFormat.of().parseHex(plan.bpmnSha256()),
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),"[1,2,3]","start");
        repo.startRun(tenant,run.runId());
        var coordinator=new GraphCoordinator(repo);
        assertThrows(IllegalStateException.class, () -> coordinator.advance(tenant,run.runId()));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND status='FAILED' AND control_gate='CLOSED'"));
        var replay=coordinator.advance(tenant,run.runId());
        assertEquals(0,replay.controlTokensAdvanced());
        assertFalse(replay.runCompleted());
    }

    @Test
    void parallelBranchesWritingSameFlowVariableAreRejectedAndReplayChecksFullExportSet() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','par-var-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));
        var run=repo.createRun(tenant,"par-var-"+UUID.randomUUID(),"CREATE","{}",
                "par-var-"+tenant,"1",new byte[32],"ci","{}","{}","split");
        repo.startRun(tenant,run.runId());
        var splitTok=repo.findReadyGraphTokens(tenant,run.runId(),10).get(0);
        repo.advanceGraphToken(tenant,run.runId(),splitTok.tokenId(),"split","SPLIT",List.of("branchA","branchB"));

        var resA=repo.reserveCapacity(tenant,run.runId(),"pv-cap",2,"branchA","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resA.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),resA.attemptId(),resA.dispatchId(),"w1");
        repo.startAttempt(tenant,run.runId(),resA.attemptId(),resA.dispatchId(),"w1");

        var resB=repo.reserveCapacity(tenant,run.runId(),"pv-cap",2,"branchB","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenant,run.runId(),resB.attemptId(),"q","{}");
        repo.acceptAttempt(tenant,run.runId(),resB.attemptId(),resB.dispatchId(),"w2");
        repo.startAttempt(tenant,run.runId(),resB.attemptId(),resB.dispatchId(),"w2");

        Map<String,String> exportsA=Map.of("sharedKey","1","onlyA","\"ok\"");
        repo.completeAttempt(tenant,run.runId(),resA.attemptId(),"out","{\"a\":1}",exportsA);

        // Full-set idempotency checks: omitting keys or passing subset must fail; exact full set replays cleanly.
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,
                () -> repo.completeAttempt(tenant,run.runId(),resA.attemptId(),"out","{\"a\":1}",Map.of()));
        assertThrows(WorkflowRuntimeRepository.IdempotencyConflictException.class,
                () -> repo.completeAttempt(tenant,run.runId(),resA.attemptId(),"out","{\"a\":1}",Map.of("sharedKey","1")));
        repo.completeAttempt(tenant,run.runId(),resA.attemptId(),"out","{\"a\":1}",exportsA);

        // Sibling branch B writing the same variable key must be rejected atomically without overwriting sharedKey.
        assertThrows(WorkflowRuntimeRepository.InvalidTransitionException.class,
                () -> repo.completeAttempt(tenant,run.runId(),resB.attemptId(),"out","{\"b\":2}",
                        Map.of("sharedKey","2","onlyB","\"no\"")));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                tenant+"' AND attempt_id='"+resB.attemptId()+"' AND attempt_state='STARTED'"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.flow_variable WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND variable_key='sharedKey' AND value='1'::jsonb"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM workflow_runtime.flow_variable WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND variable_key='onlyB'"));

        // Sibling branch B writing a distinct key succeeds cleanly.
        repo.completeAttempt(tenant,run.runId(),resB.attemptId(),"out","{\"b\":2}",Map.of("onlyB","\"ok\""));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.flow_variable WHERE tenant_id='"+
                tenant+"' AND run_id='"+run.runId()+"' AND variable_key='onlyB' AND value='\"ok\"'::jsonb"));
    }

    @Test
    void terminalGuardMatrixBlocksRunSuccessForQuarantineAndPendingDrainItem() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource(); UUID tenant=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenant+"','guard-qd-"+tenant+"','integration')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));

        // 1. ACTIVE quarantine_record blocks Run SUCCEEDED.
        var qRun=repo.createRun(tenant,"guard-q-"+UUID.randomUUID(),"CREATE","{}",
                "guard-q-"+tenant,"1",new byte[32],"ci","{}","{}","end");
        repo.startRun(tenant,qRun.runId());
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.quarantine_record
                  (tenant_id,resource_id,run_id,quarantine_state,reason_code,evidence)
                VALUES (?,?,?,'ACTIVE','TEST_BLOCK','{}')
                """)) {
            ps.setObject(1,tenant);ps.setString(2,"q-res-"+qRun.runId());ps.setObject(3,qRun.runId());ps.executeUpdate();
        }
        var qEnd=repo.findReadyGraphTokens(tenant,qRun.runId(),10).get(0);
        assertFalse(repo.advanceGraphToken(tenant,qRun.runId(),qEnd.tokenId(),"end","END",List.of()).runCompleted());

        // 2. PENDING drain_item blocks Run SUCCEEDED even if drain_record is COMPLETED.
        var dRun=repo.createRun(tenant,"guard-d-"+UUID.randomUUID(),"CREATE","{}",
                "guard-d-"+tenant,"1",new byte[32],"ci","{}","{}","end");
        repo.startRun(tenant,dRun.runId());
        UUID drainId=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.drain_record
                  (tenant_id,drain_id,run_id,drain_kind,drain_state)
                VALUES (?,?,?,'PAUSE','COMPLETED')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,drainId);ps.setObject(3,dRun.runId());ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.drain_item
                  (tenant_id,drain_id,item_kind,item_id,item_state)
                VALUES (?,?,'LEASE',?,'PENDING')
                """)) {
            ps.setObject(1,tenant);ps.setObject(2,drainId);ps.setObject(3,UUID.randomUUID());ps.executeUpdate();
        }
        var dEnd=repo.findReadyGraphTokens(tenant,dRun.runId(),10).get(0);
        assertFalse(repo.advanceGraphToken(tenant,dRun.runId(),dEnd.tokenId(),"end","END",List.of()).runCompleted());
    }

    @Test
    void lifecycleCoordinatorCoversWakeupAfterRejectedSignalFaultEvictionAndSameRunIdCrossTenant() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_RUNTIME_PG_IT")));
        var ds=dataSource();
        UUID tenantA=UUID.randomUUID(), tenantB=UUID.randomUUID();
        try(Connection c=ds.getConnection(); Statement st=c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenantA+"','lc-a-"+tenantA+"','tenantA')");
            st.executeUpdate("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES ('"+
                    tenantB+"','lc-b-"+tenantB+"','tenantB')");
        }
        WorkflowRuntimeRepository repo=new WorkflowRuntimeRepository(ds,new DataSourceTransactionManager(ds));

        // 1. Same run_id UUID in TWO different tenants (PK is (tenant_id, run_id)): strictly isolated!
        String simpleXml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan simplePlan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "shared-run","1",simpleXml,Map.of()));
        UUID sharedRunId=UUID.randomUUID();
        UUID defA=UUID.randomUUID(), defB=UUID.randomUUID();
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.definition_snapshot
                  (tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan)
                VALUES (?,?,?,'1',?,?,?::jsonb)
                """)) {
            byte[] hash=java.util.HexFormat.of().parseHex(simplePlan.bpmnSha256());
            String json=GraphPlanCodec.toJson(simplePlan);
            ps.setObject(1,tenantA);ps.setObject(2,defA);ps.setString(3,"kA");ps.setBytes(4,hash);ps.setString(5,simplePlan.compilerVersion());ps.setString(6,json);ps.executeUpdate();
            ps.setObject(1,tenantB);ps.setObject(2,defB);ps.setString(3,"kB");ps.setBytes(4,hash);ps.setString(5,simplePlan.compilerVersion());ps.setString(6,json);ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.workflow_run (tenant_id,run_id,definition_id,status)
                VALUES (?,?,?,'CREATED')
                """)) {
            ps.setObject(1,tenantA);ps.setObject(2,sharedRunId);ps.setObject(3,defA);ps.executeUpdate();
            ps.setObject(1,tenantB);ps.setObject(2,sharedRunId);ps.setObject(3,defB);ps.executeUpdate();
        }
        try(Connection c=ds.getConnection(); var ps=c.prepareStatement("""
                INSERT INTO workflow_runtime.execution_token (tenant_id,run_id,token_state,node_key)
                VALUES (?,?,'READY','start')
                """)) {
            ps.setObject(1,tenantA);ps.setObject(2,sharedRunId);ps.executeUpdate();
            ps.setObject(1,tenantB);ps.setObject(2,sharedRunId);ps.executeUpdate();
        }

        var executor=Executors.newFixedThreadPool(2);
        try(var lifecycle=new WorkflowRunLifecycleCoordinator(repo,new GraphCoordinator(repo),executor,4)) {
            lifecycle.startRun(tenantA,sharedRunId).get(5,TimeUnit.SECONDS);
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                    tenantA+"' AND run_id='"+sharedRunId+"' AND status='SUCCEEDED'"));
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                    tenantB+"' AND run_id='"+sharedRunId+"' AND status='CREATED'"),
                    "Tenant B's Run with identical run_id must remain completely untouched");

            // 2. Graph fault through LifecycleCoordinator lands FAILED and evicts mailbox to 0.
            String faultXml="""
                    <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                      <bpmn:process id="p">
                        <bpmn:startEvent id="start"/>
                        <bpmn:exclusiveGateway id="g"/>
                        <bpmn:endEvent id="e1"/>
                        <bpmn:endEvent id="e2"/>
                        <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="g"/>
                        <bpmn:sequenceFlow id="f2" sourceRef="g" targetRef="e1"><bpmn:conditionExpression>missing == true</bpmn:conditionExpression></bpmn:sequenceFlow>
                        <bpmn:sequenceFlow id="f3" sourceRef="g" targetRef="e2"><bpmn:conditionExpression>missing == false</bpmn:conditionExpression></bpmn:sequenceFlow>
                      </bpmn:process>
                    </bpmn:definitions>
                    """;
            GraphPlan faultPlan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                    "fault-lc-"+tenantA,"1",faultXml,Map.of()));
            var faultRun=repo.createRun(tenantA,"fault-lc-"+UUID.randomUUID(),"CREATE","{}",
                    "fault-lc-"+tenantA,"1",java.util.HexFormat.of().parseHex(faultPlan.bpmnSha256()),
                    faultPlan.compilerVersion(),GraphPlanCodec.toJson(faultPlan),"{}","start");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> lifecycle.startRun(tenantA,faultRun.runId()).get(5,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(lifecycle.activeMailboxCount()!=0 && System.nanoTime()<deadline) Thread.yield();
            assertEquals(0,lifecycle.activeMailboxCount(),"Faulted Run mailbox must be reclaimed");
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                    tenantA+"' AND run_id='"+faultRun.runId()+"' AND status='FAILED' AND control_gate='CLOSED'"));
        } finally {
            executor.shutdownNow();
        }

        // 3. Attempt completion commits durably even when mailbox rejects signal (capacity full), then wake() finishes Run!
        String taskXml="""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="p">
                    <bpmn:startEvent id="start"/>
                    <bpmn:task id="t1" protocolName="Calc" resource="1"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan taskPlan=new BpmnGraphCompiler().compile(new BpmnGraphCompiler.CompilationInput(
                "wake-lc-"+tenantA,"1",taskXml,Map.of()));
        var taskRun=repo.createRun(tenantA,"wake-lc-"+UUID.randomUUID(),"CREATE","{}",
                "wake-lc-"+tenantA,"1",java.util.HexFormat.of().parseHex(taskPlan.bpmnSha256()),
                taskPlan.compilerVersion(),GraphPlanCodec.toJson(taskPlan),"{}","start");
        repo.startRun(tenantA,taskRun.runId());
        new GraphCoordinator(repo).advance(tenantA,taskRun.runId());
        var res=repo.reserveCapacity(tenantA,taskRun.runId(),"wake-cap",1,"t1","PURE",Instant.now().plusSeconds(60));
        repo.dispatchAttempt(tenantA,taskRun.runId(),res.attemptId(),"q","{}");
        var ob=repo.beginOutboxSend(tenantA,10).stream().filter(m -> m.dedupKey().equals("dispatch:"+res.attemptId())).findFirst().orElseThrow();
        repo.markOutboxDelivered(tenantA,ob.outboxId());
        repo.acceptAttempt(tenantA,taskRun.runId(),res.attemptId(),res.dispatchId(),"w1");
        repo.startAttempt(tenantA,taskRun.runId(),res.attemptId(),res.dispatchId(),"w1");

        // Executor that rejects submission on first signal (simulating mailbox/executor rejection), then accepts wake().
        var realExec=Executors.newSingleThreadExecutor();
        var rejectFirst=new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.Executor flakyExec=command -> {
            if(rejectFirst.compareAndSet(true,false)) throw new java.util.concurrent.RejectedExecutionException("simulated overload");
            realExec.execute(command);
        };
        try(var flakyLifecycle=new WorkflowRunLifecycleCoordinator(repo,new GraphCoordinator(repo),flakyExec,1)) {
            var failedSignal=flakyLifecycle.completeAttempt(tenantA,taskRun.runId(),res.attemptId(),"out","{\"v\":9}");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> failedSignal.get(2,TimeUnit.SECONDS));
            // Attempt completion MUST have remained committed in PostgreSQL despite failed signal.
            assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.node_attempt WHERE tenant_id='"+
                    tenantA+"' AND attempt_id='"+res.attemptId()+"' AND attempt_state='SUCCEEDED'"));
            // Now wake() using a healthy coordinator recovers the missed signal and completes the Run!
            try(var healthyLifecycle=new WorkflowRunLifecycleCoordinator(repo,new GraphCoordinator(repo),realExec,4)) {
                healthyLifecycle.wake(tenantA,taskRun.runId()).get(5,TimeUnit.SECONDS);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(healthyLifecycle.activeMailboxCount()!=0 && System.nanoTime()<deadline) Thread.yield();
                assertEquals(0,healthyLifecycle.activeMailboxCount());
                assertEquals(1,scalar(ds,"SELECT count(*) FROM workflow_runtime.workflow_run WHERE tenant_id='"+
                        tenantA+"' AND run_id='"+taskRun.runId()+"' AND status='SUCCEEDED' AND control_gate='CLOSED'"));
            }
        } finally {
            realExec.shutdownNow();
        }
    }

}
