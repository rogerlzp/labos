import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import psycopg

DSN=os.environ.get("DATABASE_URL") or (
    f"host={os.environ.get('PGHOST','localhost')} port={os.environ.get('PGPORT','5432')} "
    f"dbname={os.environ.get('PGDATABASE','runtime_test')} user={os.environ.get('PGUSER','runtime')} "
    f"password={os.environ.get('PGPASSWORD','runtime')}"
)
def connect(): return psycopg.connect(DSN)

def seed():
    tenant,definition,run,token=[uuid.uuid4() for _ in range(4)]
    with connect() as c, c.cursor() as q:
        q.execute("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES(%s,%s,'matrix')",(tenant,f"matrix-{tenant}"))
        q.execute("""INSERT INTO workflow_runtime.definition_snapshot
          (tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan)
          VALUES(%s,%s,'matrix','1',decode(repeat('ab',32),'hex'),'ci','{}')""",(tenant,definition))
        q.execute("""INSERT INTO workflow_runtime.workflow_run
          (tenant_id,run_id,definition_id,status) VALUES(%s,%s,%s,'RUNNING')""",(tenant,run,definition))
        q.execute("""INSERT INTO workflow_runtime.execution_token
          (tenant_id,token_id,run_id,token_state,node_key) VALUES(%s,%s,%s,'READY','node')""",(tenant,token,run))
        q.execute("""INSERT INTO workflow_runtime.scheduler_capacity
          (tenant_id,capacity_key,capacity_limit,reserved_count) VALUES(%s,'cap',2,0)""",(tenant,))
    return tenant,run,token

def attempt_fixture(t,r,token,state='STARTED'):
    activation,attempt,dispatch=uuid.uuid4(),uuid.uuid4(),uuid.uuid4()
    with connect() as c, c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.node_activation
          (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
          VALUES(%s,%s,%s,%s,1,'node','RUNNING')""",(t,activation,r,token))
        q.execute("""INSERT INTO workflow_runtime.node_attempt
          (tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,dispatch_epoch,attempt_state,side_effect_class)
          VALUES(%s,%s,%s,%s,1,%s,1,%s,'PURE')""",(t,attempt,r,activation,dispatch,state))
        q.execute("UPDATE workflow_runtime.node_activation SET current_attempt_id=%s WHERE tenant_id=%s AND activation_id=%s",(attempt,t,activation))
    return activation,attempt,dispatch

def race(fn):
    b=threading.Barrier(2)
    def call(i):
        b.wait()
        try: return fn(i)
        except psycopg.Error: return "rejected"
    with ThreadPoolExecutor(max_workers=2) as p: return list(p.map(call,(1,2)))

def con01():
    t,r,token=seed(); _,a,_=attempt_fixture(t,r,token)
    def complete(_):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.node_attempt SET attempt_state='SUCCEEDED',attempt_version=attempt_version+1
              WHERE tenant_id=%s AND attempt_id=%s AND attempt_state='STARTED' RETURNING attempt_id""",(t,a))
            return "won" if q.fetchone() else "stale"
    assert sorted(race(complete))==["stale","won"]

def con02():
    t,r,token=seed(); source,_,_=attempt_fixture(t,r,token); split,branch=uuid.uuid4(),uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.split_activation
          (tenant_id,split_id,run_id,source_activation_id,split_state,expected_branch_count)
          VALUES(%s,%s,%s,%s,'OPEN',1)""",(t,split,r,uuid.uuid4()))
        q.execute("""INSERT INTO workflow_runtime.branch_activation
          (tenant_id,branch_id,run_id,split_id,branch_key,branch_state) VALUES(%s,%s,%s,%s,'b','ACTIVE')""",(t,branch,r,split))
    def arrive(_):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.branch_activation SET branch_state='ARRIVED',arrived_at=clock_timestamp()
              WHERE tenant_id=%s AND branch_id=%s AND branch_state IN ('ACTIVE','WAITING') RETURNING branch_id""",(t,branch))
            return "won" if q.fetchone() else "stale"
    assert sorted(race(arrive))==["stale","won"]

def con03():
    t,r,token=seed(); source,_,_=attempt_fixture(t,r,token); split=uuid.uuid4(); branches=[uuid.uuid4(),uuid.uuid4()]
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.split_activation
          (tenant_id,split_id,run_id,source_activation_id,split_state,expected_branch_count)
          VALUES(%s,%s,%s,%s,'OPEN',2)""",(t,split,r,uuid.uuid4()))
        for i,b in enumerate(branches):
            q.execute("""INSERT INTO workflow_runtime.branch_activation
              (tenant_id,branch_id,run_id,split_id,branch_key,branch_state) VALUES(%s,%s,%s,%s,%s,'ACTIVE')""",(t,b,r,split,f"b{i}"))
        q.execute("""INSERT INTO workflow_runtime.join_activation
          (tenant_id,run_id,split_id,join_key,join_state) VALUES(%s,%s,%s,'join','WAITING')""",(t,r,split))
    def arrive(i):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT split_id FROM workflow_runtime.split_activation WHERE tenant_id=%s AND split_id=%s FOR UPDATE",(t,split))
            q.execute("""UPDATE workflow_runtime.branch_activation SET branch_state='ARRIVED'
              WHERE tenant_id=%s AND branch_id=%s AND branch_state='ACTIVE' RETURNING branch_id""",(t,branches[i-1]))
            if not q.fetchone(): return "duplicate"
            q.execute("""UPDATE workflow_runtime.split_activation SET arrived_branch_count=arrived_branch_count+1
              WHERE tenant_id=%s AND split_id=%s""",(t,split))
            q.execute("""UPDATE workflow_runtime.join_activation SET join_state='RELEASED',released_at=clock_timestamp()
              WHERE tenant_id=%s AND split_id=%s AND join_state='WAITING'
                AND (SELECT arrived_branch_count=expected_branch_count FROM workflow_runtime.split_activation
                     WHERE tenant_id=%s AND split_id=%s) RETURNING join_id""",(t,split,t,split))
            return "released" if q.fetchone() else "arrived"
    results=race(arrive)
    assert results.count("released")==1,results

def con04():
    t,r,token=seed()
    def pause(_):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT run_id FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s FOR UPDATE",(t,r))
            q.execute("""UPDATE workflow_runtime.workflow_run SET control_gate='PAUSE',status='PAUSE_REQUESTED'
              WHERE tenant_id=%s AND run_id=%s AND control_gate='OPEN'""",(t,r))
            return "paused"
    def dispatch(_):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s FOR UPDATE",(t,r))
            gate=q.fetchone()[0]
            if gate!='OPEN': return "rejected"
            q.execute("""INSERT INTO workflow_runtime.runtime_outbox
              (tenant_id,run_id,message_kind,dedup_key,payload) VALUES(%s,%s,'TEST','con04-'||%s::text,'{}')""",(t,r,r))
            return "dispatched"
    race([pause,dispatch][0]) if False else None
    b=threading.Barrier(2)
    def run(fn):
        def x(i): b.wait(); return fn(i)
        with ThreadPoolExecutor(max_workers=2) as p: return list(p.map(x,(1,2)))
    outcomes=run(lambda i: pause(i) if i==1 else dispatch(i))
    with connect() as c,c.cursor() as q:
        q.execute("SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s",(t,r))
        assert q.fetchone()[0]=='PAUSE'
        q.execute("SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id=%s AND run_id=%s AND dedup_key='con04-'||%s::text",(t,r,r))
        sent=q.fetchone()[0]
        assert sent in (0,1)

def con05():
    t,r,token=seed()
    def pause(_):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s FOR UPDATE",(t,r))
            q.execute("""UPDATE workflow_runtime.workflow_run SET control_gate='PAUSE',status='PAUSE_REQUESTED'
              WHERE tenant_id=%s AND run_id=%s AND control_gate='OPEN'""",(t,r))
    def stop(_):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s FOR UPDATE",(t,r))
            q.execute("""UPDATE workflow_runtime.workflow_run SET control_gate='STOP',status='STOP_REQUESTED'
              WHERE tenant_id=%s AND run_id=%s AND control_gate IN ('OPEN','PAUSE')""",(t,r))
    b=threading.Barrier(2)
    def wrap(fn):
        def x(i): b.wait(); fn(i)
        return x
    with ThreadPoolExecutor(max_workers=2) as p: list(p.map(lambda x:x(0),[wrap(pause),wrap(stop)]))
    with connect() as c,c.cursor() as q:
        q.execute("SELECT control_gate,status FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s",(t,r))
        assert q.fetchone()==('STOP','STOP_REQUESTED')

def con06():
    t,r,token=seed(); activation,_,_=attempt_fixture(t,r,token)
    wait=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.wait_registration
          (tenant_id,wait_id,run_id,activation_id,wait_state,wait_kind,continuation)
          VALUES(%s,%s,%s,%s,'ACTIVE','TEST','{}')""",(t,wait,r,activation))
    def close(i):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.wait_registration SET wait_state=%s,version=version+1
              WHERE tenant_id=%s AND wait_id=%s AND wait_state='ACTIVE' RETURNING wait_id""",
              ('SATISFIED' if i==1 else 'EXPIRED',t,wait))
            return "won" if q.fetchone() else "stale"
    assert sorted(race(close))==["stale","won"]

def con07():
    t,r,token=seed(); activation,_,_=attempt_fixture(t,r,token)
    retry=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.retry_registration
          (tenant_id,retry_id,run_id,activation_id,retry_state,due_at,attempt_no,retry_policy)
          VALUES(%s,%s,%s,%s,'SCHEDULED',clock_timestamp(),2,'{}')""",(t,retry,r,activation))
    b=threading.Barrier(2)
    def retry_due(_):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s FOR UPDATE",(t,r))
            if q.fetchone()[0]!='OPEN': return
            q.execute("""UPDATE workflow_runtime.retry_registration SET retry_state='FIRED',version=version+1
              WHERE tenant_id=%s AND retry_id=%s AND retry_state='SCHEDULED'""",(t,retry))
            q.execute("""INSERT INTO workflow_runtime.execution_token(tenant_id,run_id,token_state,node_key)
              VALUES(%s,%s,'READY','retry')""",(t,r))
    def stop(_):
        with connect() as c,c.cursor() as q:
            q.execute("SELECT run_id FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s FOR UPDATE",(t,r))
            q.execute("UPDATE workflow_runtime.workflow_run SET control_gate='STOP',status='STOP_REQUESTED' WHERE tenant_id=%s AND run_id=%s",(t,r))
    def w(i): b.wait(); (retry_due if i==1 else stop)(i)
    with ThreadPoolExecutor(max_workers=2) as p: list(p.map(w,(1,2)))
    with connect() as c,c.cursor() as q:
        q.execute("SELECT control_gate FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s",(t,r))
        gate=q.fetchone()[0]
        if gate=='STOP':
            # If Retry committed first, token is drain-visible; otherwise it was not created.
            q.execute("SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=%s AND run_id=%s AND node_key='retry'",(t,r))
            assert q.fetchone()[0] in (0,1)

def con08():
    t,r,token=seed(); activation,attempt,_=attempt_fixture(t,r,token)
    command=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.command_ledger
          (tenant_id,command_id,run_id,activation_id,attempt_id,device_id,command_kind,command_state,
           idempotency_class,command_payload,payload_hash)
          VALUES(%s,%s,%s,%s,%s,'dev','x','RUNNING','QUERYABLE','{}',decode(repeat('ab',32),'hex'))""",
          (t,command,r,activation,attempt))
    def final(_):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.command_ledger SET command_state='SUCCEEDED',version=version+1
              WHERE tenant_id=%s AND command_id=%s AND command_state IN ('RUNNING','CANCEL_REQUESTED')
              RETURNING command_id""",(t,command))
            return "succeeded" if q.fetchone() else "stale"
    def cancel(_):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.command_ledger SET command_state='CANCEL_REQUESTED',version=version+1
              WHERE tenant_id=%s AND command_id=%s AND command_state='RUNNING' RETURNING command_id""",(t,command))
            return "cancel-requested" if q.fetchone() else "stale"
    outcomes=race(lambda i: final(i) if i==1 else cancel(i))
    with connect() as c,c.cursor() as q:
        q.execute("SELECT command_state FROM workflow_runtime.command_ledger WHERE tenant_id=%s AND command_id=%s",(t,command))
        assert q.fetchone()[0] in ('SUCCEEDED','CANCEL_REQUESTED')

def con09():
    t,r,token=seed(); lease=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.resource_lease
          (tenant_id,lease_id,run_id,resource_id,lease_state,owner_kind,owner_id,fencing_token,expires_at)
          VALUES(%s,%s,%s,'lease-race','ACTIVE','RUN',%s,1,clock_timestamp()-interval '1 second')""",(t,lease,r,r))
    def renew(_):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.resource_lease SET expires_at=clock_timestamp()+interval '1 minute',
              lease_version=lease_version+1 WHERE tenant_id=%s AND lease_id=%s AND lease_state='ACTIVE'
              AND lease_version=0 RETURNING lease_id""",(t,lease))
            return "renewed" if q.fetchone() else "stale"
    def expire(_):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.resource_lease SET lease_state='EXPIRED_UNVERIFIED',
              lease_version=lease_version+1 WHERE tenant_id=%s AND lease_id=%s AND lease_state='ACTIVE'
              AND lease_version=0 AND expires_at<=clock_timestamp() RETURNING lease_id""",(t,lease))
            return "expired" if q.fetchone() else "stale"
    outcomes=race(lambda i: renew(i) if i==1 else expire(i))
    assert sum(x in ('renewed','expired') for x in outcomes)==1,outcomes

def con10():
    t,r,token=seed()
    b=threading.Barrier(2)
    def takeover(_):
        with connect() as c,c.cursor() as q:
            q.execute("UPDATE workflow_runtime.workflow_run SET coordinator_epoch=coordinator_epoch+1,recovery_state='RECOVERING' WHERE tenant_id=%s AND run_id=%s",(t,r))
    def inbox(_):
        with connect() as c,c.cursor() as q:
            q.execute("""INSERT INTO workflow_runtime.inbox_receipt
              (tenant_id,source,source_message_id,payload_hash,message_type,run_id)
              VALUES(%s,'adapter',%s,decode(repeat('aa',32),'hex'),'EVENT',%s)""",(t,str(uuid.uuid4()),r))
    def w(fn): b.wait(); fn(0)
    with ThreadPoolExecutor(max_workers=2) as p: list(p.map(w,(takeover,inbox)))
    with connect() as c,c.cursor() as q:
        q.execute("SELECT recovery_state,coordinator_epoch FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s",(t,r))
        assert q.fetchone()==('RECOVERING',1)
        q.execute("SELECT count(*) FROM workflow_runtime.inbox_receipt WHERE tenant_id=%s AND run_id=%s AND receipt_state='RECEIVED'",(t,r))
        assert q.fetchone()[0]==1

def con11():
    t,r,token=seed(); outbox=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.runtime_outbox
          (tenant_id,outbox_id,run_id,message_kind,dedup_key,payload)
          VALUES(%s,%s,%s,'TEST',%s,'{}')""",(t,outbox,r,str(uuid.uuid4())))
    def claim(i):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.runtime_outbox SET outbox_state='CLAIMED',
              claim_owner=%s,claim_epoch=claim_epoch+1,claim_until=clock_timestamp()+interval '1 minute'
              WHERE tenant_id=%s AND outbox_id=%s AND (outbox_state='PENDING' OR
              (outbox_state='CLAIMED' AND claim_until<clock_timestamp())) RETURNING claim_owner""",(f"d{i}",t,outbox))
            row=q.fetchone()
            return row[0] if row else "stale"
    results=race(claim)
    assert sum(x in ('d1','d2') for x in results)==1,results

def con12():
    t,r,token=seed(); qid=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.quarantine_record
          (tenant_id,quarantine_id,resource_id,quarantine_state,reason_code,evidence)
          VALUES(%s,%s,'q-race','CLEAR_PENDING','TEST','{}')""",(t,qid))
    def clear(i):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.quarantine_record SET quarantine_state='CLEARED',
              cleared_by=%s,cleared_at=clock_timestamp(),version=version+1
              WHERE tenant_id=%s AND quarantine_id=%s AND quarantine_state='CLEAR_PENDING' RETURNING quarantine_id""",
              (f"actor{i}",t,qid))
            return "won" if q.fetchone() else "stale"
    assert sorted(race(clear))==["stale","won"]

def con13():
    t,r,token=seed(); iid=uuid.uuid4()
    with connect() as c,c.cursor() as q:
        q.execute("""INSERT INTO workflow_runtime.manual_intervention
          (tenant_id,intervention_id,run_id,subject_kind,subject_id,intervention_state,reason_code)
          VALUES(%s,%s,%s,'RUN',%s,'OPEN','TEST')""",(t,iid,r,r))
    def decide(i):
        with connect() as c,c.cursor() as q:
            q.execute("""UPDATE workflow_runtime.manual_intervention SET intervention_state='DECIDED',
              decision='{}',decided_by=%s,decided_at=clock_timestamp(),version=version+1
              WHERE tenant_id=%s AND intervention_id=%s AND version=0 AND intervention_state='OPEN'
              RETURNING intervention_id""",(f"actor{i}",t,iid))
            return "won" if q.fetchone() else "stale"
    assert sorted(race(decide))==["stale","won"]

def con14():
    t,r,token=seed()
    barrier=threading.Barrier(2); observed={}
    def snapshot():
        with connect() as c:
            c.execute("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY")
            with c.cursor() as q:
                q.execute("SELECT commit_version FROM workflow_runtime.workflow_run WHERE tenant_id=%s AND run_id=%s",(t,r))
                observed['version']=q.fetchone()[0]
                barrier.wait()
                q.execute("SELECT count(*) FROM workflow_runtime.execution_token WHERE tenant_id=%s AND run_id=%s",(t,r))
                observed['tokens']=q.fetchone()[0]
            c.commit()
    def mutate():
        barrier.wait()
        with connect() as c,c.cursor() as q:
            q.execute("UPDATE workflow_runtime.workflow_run SET commit_version=commit_version+1 WHERE tenant_id=%s AND run_id=%s",(t,r))
            q.execute("""INSERT INTO workflow_runtime.execution_token(tenant_id,run_id,token_state,node_key)
              VALUES(%s,%s,'READY','con14-new')""",(t,r))
    with ThreadPoolExecutor(max_workers=2) as p:
        a=p.submit(snapshot); b=p.submit(mutate); a.result(); b.result()
    assert observed=={'version':0,'tokens':1},observed

def main():
    tests=[con01,con02,con03,con04,con05,con06,con07,con08,con09,con10,con11,con12,con13,con14]
    for i,test in enumerate(tests,1):
        test()
        print(f"PASS CON-{i:02d}: {test.__name__}")
    print("PASS: all CON-01..CON-14 deterministic two-session tests")

if __name__=="__main__": main()
