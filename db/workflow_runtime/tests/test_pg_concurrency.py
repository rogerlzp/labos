import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import psycopg
from psycopg.errors import UniqueViolation

DSN = os.environ.get("DATABASE_URL") or (
    f"host={os.environ.get('PGHOST','localhost')} "
    f"port={os.environ.get('PGPORT','5432')} "
    f"dbname={os.environ.get('PGDATABASE','runtime_test')} "
    f"user={os.environ.get('PGUSER','runtime')} "
    f"password={os.environ.get('PGPASSWORD','runtime')}"
)

def connect():
    return psycopg.connect(DSN)

def seed():
    tenant, definition, run, token, activation, capacity = [uuid.uuid4() for _ in range(6)]
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES(%s,%s,'race')",
                        (tenant, f"race-{tenant}"))
            cur.execute("""INSERT INTO workflow_runtime.definition_snapshot
                (tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan)
                VALUES(%s,%s,'race','1',decode(repeat('aa',32),'hex'),'ci','{}')""", (tenant, definition))
            cur.execute("""INSERT INTO workflow_runtime.workflow_run
                (tenant_id,run_id,definition_id,status) VALUES(%s,%s,%s,'RUNNING')""",
                (tenant, run, definition))
            cur.execute("""INSERT INTO workflow_runtime.execution_token
                (tenant_id,token_id,run_id,token_state,node_key) VALUES(%s,%s,%s,'READY','race')""",
                (tenant, token, run))
            cur.execute("""INSERT INTO workflow_runtime.node_activation
                (tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
                VALUES(%s,%s,%s,%s,1,'race','RUNNING')""",
                (tenant, activation, run, token))
            cur.execute("""INSERT INTO workflow_runtime.scheduler_capacity
                (tenant_id,capacity_key,capacity_limit,reserved_count) VALUES(%s,'race-capacity',1,0)""",
                (tenant,))
    return tenant, run, token, activation

def race_two(fn):
    barrier = threading.Barrier(2)
    def wrapped(i):
        barrier.wait()
        return fn(i)
    with ThreadPoolExecutor(max_workers=2) as pool:
        return list(pool.map(wrapped, (1, 2)))

def main():
    tenant, run, token, activation = seed()

    def create_live_activation(i):
        try:
            with connect() as conn:
                with conn.cursor() as cur:
                    cur.execute("""INSERT INTO workflow_runtime.node_activation
                      (tenant_id,run_id,token_id,activation_no,node_key,activation_state)
                      VALUES(%s,%s,%s,%s,'race','READY')""",
                      (tenant, run, token, i + 1))
            return "inserted"
        except UniqueViolation:
            return "rejected"
    activation_results = race_two(create_live_activation)
    assert sorted(activation_results) == ["inserted", "rejected"], activation_results

    def reserve_capacity(_):
        with connect() as conn:
            with conn.cursor() as cur:
                cur.execute("""UPDATE workflow_runtime.scheduler_capacity
                  SET reserved_count=reserved_count+1,capacity_version=capacity_version+1
                  WHERE tenant_id=%s AND capacity_key='race-capacity'
                    AND reserved_count<capacity_limit RETURNING reserved_count""", (tenant,))
                return "reserved" if cur.fetchone() else "full"
    capacity_results = race_two(reserve_capacity)
    assert sorted(capacity_results) == ["full", "reserved"], capacity_results

    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute("""SELECT reserved_count,capacity_limit FROM workflow_runtime.scheduler_capacity
              WHERE tenant_id=%s AND capacity_key='race-capacity'""", (tenant,))
            reserved, limit = cur.fetchone()
            assert reserved == 1 and reserved <= limit, (reserved, limit)

    print("PASS: two-session live Activation race")
    print("PASS: two-session SchedulerCapacity no-oversell race")
    print("PASS: final reserved_count <= capacity_limit")

if __name__ == "__main__":
    main()
