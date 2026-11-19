import os
import uuid

import psycopg

DSN = os.environ.get("DATABASE_URL") or (
    f"host={os.environ.get('PGHOST','localhost')} port={os.environ.get('PGPORT','5432')} "
    f"dbname={os.environ.get('PGDATABASE','runtime_test')} user={os.environ.get('PGUSER','runtime')} "
    f"password={os.environ.get('PGPASSWORD','runtime')}"
)

def main():
    tenant, definition, run = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    with psycopg.connect(DSN) as conn:
        with conn.cursor() as cur:
            cur.execute("INSERT INTO workflow_runtime.tenant(tenant_id,tenant_key,display_name) VALUES(%s,%s,'atomicity')",
                        (tenant, f"atomicity-{tenant}"))
            cur.execute("""INSERT INTO workflow_runtime.definition_snapshot
              (tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan)
              VALUES(%s,%s,'atomicity','1',decode(repeat('12',32),'hex'),'ci','{}')""",
              (tenant, definition))
            cur.execute("""INSERT INTO workflow_runtime.workflow_run
              (tenant_id,run_id,definition_id,status) VALUES(%s,%s,%s,'CREATED')""",
              (tenant, run, definition))

    # Inject an application failure after projection + Event + Outbox writes but before COMMIT.
    try:
        with psycopg.connect(DSN) as conn:
            with conn.cursor() as cur:
                cur.execute("""UPDATE workflow_runtime.workflow_run
                  SET commit_version=commit_version+1,event_seq=event_seq+1
                  WHERE tenant_id=%s AND run_id=%s""", (tenant, run))
                cur.execute("""INSERT INTO workflow_runtime.runtime_event
                  (tenant_id,run_id,event_seq,event_type,aggregate_type,payload)
                  VALUES(%s,%s,1,'ATOMICITY_ROLLBACK','RUN','{}')""", (tenant, run))
                cur.execute("""INSERT INTO workflow_runtime.runtime_outbox
                  (tenant_id,run_id,message_kind,dedup_key,payload)
                  VALUES(%s,%s,'TEST','atomicity-rollback-'||%s::text,'{}')""", (tenant, run, run))
                raise RuntimeError("fault injection: crash before commit")
    except RuntimeError:
        pass

    with psycopg.connect(DSN) as conn:
        with conn.cursor() as cur:
            cur.execute("""SELECT commit_version,event_seq FROM workflow_runtime.workflow_run
              WHERE tenant_id=%s AND run_id=%s""", (tenant, run))
            assert cur.fetchone() == (0, 0), "Run projection leaked across rollback"
            cur.execute("SELECT count(*) FROM workflow_runtime.runtime_event WHERE tenant_id=%s AND run_id=%s",
                        (tenant, run))
            assert cur.fetchone()[0] == 0, "Event leaked across rollback"
            cur.execute("SELECT count(*) FROM workflow_runtime.runtime_outbox WHERE tenant_id=%s AND run_id=%s",
                        (tenant, run))
            assert cur.fetchone()[0] == 0, "Outbox leaked across rollback"

    # A committed projection + Event + Outbox is visible atomically.
    with psycopg.connect(DSN) as conn:
        with conn.cursor() as cur:
            cur.execute("""UPDATE workflow_runtime.workflow_run
              SET commit_version=commit_version+1,event_seq=event_seq+1
              WHERE tenant_id=%s AND run_id=%s""", (tenant, run))
            cur.execute("""INSERT INTO workflow_runtime.runtime_event
              (tenant_id,run_id,event_seq,event_type,aggregate_type,payload)
              VALUES(%s,%s,1,'ATOMICITY_COMMIT','RUN','{}')""", (tenant, run))
            cur.execute("""INSERT INTO workflow_runtime.runtime_outbox
              (tenant_id,run_id,message_kind,dedup_key,payload)
              VALUES(%s,%s,'TEST','atomicity-commit-'||%s::text,'{}')""", (tenant, run, run))
    with psycopg.connect(DSN) as conn:
        with conn.cursor() as cur:
            cur.execute("""SELECT commit_version,event_seq FROM workflow_runtime.workflow_run
              WHERE tenant_id=%s AND run_id=%s""", (tenant, run))
            assert cur.fetchone() == (1, 1)
            cur.execute("""SELECT count(*) FROM workflow_runtime.runtime_event
              WHERE tenant_id=%s AND run_id=%s AND event_seq=1""", (tenant, run))
            assert cur.fetchone()[0] == 1
            cur.execute("""SELECT count(*) FROM workflow_runtime.runtime_outbox
              WHERE tenant_id=%s AND run_id=%s AND dedup_key='atomicity-commit-'||%s::text""",
              (tenant, run, run))
            assert cur.fetchone()[0] == 1

    # Inbox same identity with a different payload hash must preserve first receipt
    # and record the conflicting hash separately.
    source, message_id = "ci-adapter", f"message-{uuid.uuid4()}"
    first_hash = bytes.fromhex("34" * 32)
    conflict_hash = bytes.fromhex("56" * 32)
    with psycopg.connect(DSN) as conn:
        with conn.cursor() as cur:
            cur.execute("""INSERT INTO workflow_runtime.inbox_receipt
              (tenant_id,source,source_message_id,payload_hash,message_type,run_id)
              VALUES(%s,%s,%s,%s,'DEVICE_EVENT',%s) RETURNING inbox_id""",
              (tenant, source, message_id, first_hash, run))
            inbox_id = cur.fetchone()[0]
            cur.execute("""INSERT INTO workflow_runtime.inbox_receipt
              (tenant_id,source,source_message_id,payload_hash,message_type,run_id)
              VALUES(%s,%s,%s,%s,'DEVICE_EVENT',%s)
              ON CONFLICT(tenant_id,source,source_message_id) DO NOTHING RETURNING inbox_id""",
              (tenant, source, message_id, conflict_hash, run))
            assert cur.fetchone() is None, "duplicate identity unexpectedly created a new receipt"
            cur.execute("""INSERT INTO workflow_runtime.inbox_protocol_violation
              (tenant_id,inbox_id,conflicting_payload_hash,conflicting_payload)
              VALUES(%s,%s,%s,'{}')""", (tenant, inbox_id, conflict_hash))
            cur.execute("""SELECT payload_hash FROM workflow_runtime.inbox_receipt
              WHERE tenant_id=%s AND inbox_id=%s""", (tenant, inbox_id))
            assert cur.fetchone()[0] == first_hash, "first payload hash was overwritten"
            cur.execute("""SELECT count(*) FROM workflow_runtime.inbox_protocol_violation
              WHERE tenant_id=%s AND inbox_id=%s""", (tenant, inbox_id))
            assert cur.fetchone()[0] == 1

    print("PASS: Run/Event/Outbox all roll back together on injected pre-commit failure")
    print("PASS: committed Run/Event/Outbox projection is complete")
    print("PASS: Inbox conflicting hash preserves original receipt and stores violation evidence")

if __name__ == "__main__":
    main()
