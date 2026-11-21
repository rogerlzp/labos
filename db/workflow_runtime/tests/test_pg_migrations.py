import hashlib
import os
import pathlib
import subprocess
import uuid

import psycopg
from psycopg.errors import ActiveSqlTransaction, UniqueViolation

ROOT = pathlib.Path(__file__).resolve().parents[3]
MIG_DIR = ROOT / "db" / "workflow_runtime"
RUNNER = ROOT / "scripts" / "apply-workflow-runtime-migrations.sh"

PGHOST = os.environ.get("PGHOST", "localhost")
PGPORT = os.environ.get("PGPORT", "5432")
PGUSER = os.environ.get("PGUSER", "runtime")
PGPASSWORD = os.environ.get("PGPASSWORD", "runtime")
PGMAINT_DB = os.environ.get("PGMAINT_DB", "postgres")


def pg_env(dbname: str | None = None) -> dict[str, str]:
    env = os.environ.copy()
    env["PGHOST"] = PGHOST
    env["PGPORT"] = PGPORT
    env["PGUSER"] = PGUSER
    env["PGPASSWORD"] = PGPASSWORD
    if dbname is not None:
        env["PGDATABASE"] = dbname
    return env


def dsn(dbname: str) -> str:
    return (
        f"host={PGHOST} port={PGPORT} dbname={dbname} "
        f"user={PGUSER} password={PGPASSWORD}"
    )


def sha256_of(filename: str) -> str:
    return hashlib.sha256((MIG_DIR / filename).read_bytes()).hexdigest()


def create_temp_db(prefix: str) -> str:
    dbname = f"runtime_mig_{prefix}_{uuid.uuid4().hex[:8]}"
    with psycopg.connect(dsn(PGMAINT_DB), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(f'CREATE DATABASE "{dbname}"')
    return dbname


def drop_temp_db(dbname: str) -> None:
    with psycopg.connect(dsn(PGMAINT_DB), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(f'DROP DATABASE IF EXISTS "{dbname}" WITH (FORCE)')


def run_migrator(dbname: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    res = subprocess.run(
        ["bash", str(RUNNER), *args],
        cwd=str(ROOT),
        env=pg_env(dbname),
        text=True,
        capture_output=True,
    )
    if check and res.returncode != 0:
        raise AssertionError(
            f"apply-workflow-runtime-migrations.sh {' '.join(args)} failed (rc={res.returncode}):\n"
            f"STDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}"
        )
    return res


def psql_file(dbname: str, filename: str) -> None:
    env = pg_env(dbname)
    path = MIG_DIR / filename
    res = subprocess.run(
        ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-f", str(path)],
        cwd=str(ROOT),
        env=env,
        text=True,
        capture_output=True,
    )
    if res.returncode != 0:
        raise AssertionError(f"psql {filename} failed: {res.stderr}")


def fetch_ledger(dbname: str) -> dict[str, tuple[str, str, str, object]]:
    with psycopg.connect(dsn(dbname)) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT version, filename, sha256, mode, applied_at
                  FROM workflow_runtime.schema_migration
                 ORDER BY version
                """
            )
            return {r[0]: (r[1], r[2], r[3], r[4]) for r in cur.fetchall()}


def index_info(dbname: str, index_name: str) -> tuple[str | None, bool | None]:
    with psycopg.connect(dsn(dbname)) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT pg_get_indexdef(i.indexrelid), i.indisvalid
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indexrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'workflow_runtime'
                   AND c.relname = %s
                """,
                (index_name,),
            )
            row = cur.fetchone()
            return (row[0], row[1]) if row else (None, None)


def test_fresh_install_idempotent_and_checksum_drift() -> None:
    db = create_temp_db("fresh")
    try:
        r1 = run_migrator(db, "--verify")
        assert "APPLY 001" in r1.stdout and "APPLY 003" in r1.stdout
        assert "APPLY 004" in r1.stdout and "APPLY 005" in r1.stdout
        assert "APPLY 006" in r1.stdout
        assert "VERIFY 002_schema_contract.sql PASSED" in r1.stdout

        ledger1 = fetch_ledger(db)
        assert set(ledger1.keys()) == {"001", "003", "004", "005", "006"}
        expected_files = {
            "001": "001_init.sql",
            "003": "003_g5_wait_correlation.sql",
            "004": "004_g5_wait_inbox_idx.sql",
            "005": "005_migration_ledger.sql",
            "006": "006_device_execution_contract_v1.sql",
        }
        for ver, fname in expected_files.items():
            rec_fname, rec_sha, rec_mode, _ = ledger1[ver]
            assert rec_fname == fname
            assert rec_sha == sha256_of(fname)
            assert rec_mode == "STANDARD"

        # Idempotent re-run skips all versions without modifying applied_at
        r2 = run_migrator(db, "--verify")
        for ver in expected_files:
            assert f"SKIP {ver}" in r2.stdout
        assert "APPLY " not in r2.stdout
        ledger2 = fetch_ledger(db)
        for ver in expected_files:
            assert ledger2[ver] == ledger1[ver], f"Ledger changed on idempotent re-run for {ver}"

        # Tamper sha256 -> fail closed
        orig_sha_003 = ledger1["003"][1]
        with psycopg.connect(dsn(db)) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE workflow_runtime.schema_migration SET sha256 = %s WHERE version = '003'",
                    ("0" * 64,),
                )
        bad_sha = run_migrator(db, "--verify", check=False)
        assert bad_sha.returncode != 0
        assert "checksum drift" in bad_sha.stderr.lower()

        # Restore 003, tamper filename on 004 -> fail closed
        with psycopg.connect(dsn(db)) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE workflow_runtime.schema_migration SET sha256 = %s WHERE version = '003'",
                    (orig_sha_003,),
                )
                cur.execute(
                    "UPDATE workflow_runtime.schema_migration SET filename = '999_bogus.sql' WHERE version = '004'"
                )
        bad_fname = run_migrator(db, "--verify", check=False)
        assert bad_fname.returncode != 0
        assert "drift" in bad_fname.stderr.lower()
    finally:
        drop_temp_db(db)
    print("PASS: fresh install idempotency and checksum/filename drift rejection")


def test_003_preflight_blocks_dirty_duplicate_active_wait_data() -> None:
    db = create_temp_db("dirty003")
    try:
        psql_file(db, "001_init.sql")
        psql_file(db, "005_migration_ledger.sql")
        with psycopg.connect(dsn(db)) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO workflow_runtime.schema_migration(version, filename, sha256, mode)
                    VALUES ('001', '001_init.sql', %s, 'STANDARD'),
                           ('005', '005_migration_ledger.sql', %s, 'STANDARD')
                    """,
                    (sha256_of("001_init.sql"), sha256_of("005_migration_ledger.sql")),
                )
                t, d, r = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
                tok1, tok2 = uuid.uuid4(), uuid.uuid4()
                act1, act2 = uuid.uuid4(), uuid.uuid4()
                cur.execute(
                    "INSERT INTO workflow_runtime.tenant(tenant_id, tenant_key, display_name) VALUES (%s, %s, 'dirty')",
                    (t, f"dirty-{t}"),
                )
                cur.execute(
                    """
                    INSERT INTO workflow_runtime.definition_snapshot
                      (tenant_id, definition_id, definition_key, definition_version, definition_hash, compiler_version, graph_plan)
                    VALUES (%s, %s, 'dirty-def', '1', decode(repeat('ab', 32), 'hex'), 'ci', '{}')
                    """,
                    (t, d),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.workflow_run(tenant_id, run_id, definition_id, status) VALUES (%s, %s, %s, 'RUNNING')",
                    (t, r, d),
                )
                for tok in (tok1, tok2):
                    cur.execute(
                        "INSERT INTO workflow_runtime.execution_token(tenant_id, token_id, run_id, token_state, node_key) VALUES (%s, %s, %s, 'WAITING', 'w')",
                        (t, tok, r),
                    )
                cur.execute(
                    "INSERT INTO workflow_runtime.node_activation(tenant_id, activation_id, run_id, token_id, activation_no, node_key, activation_state) VALUES (%s, %s, %s, %s, 1, 'w1', 'WAITING')",
                    (t, act1, r, tok1),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.node_activation(tenant_id, activation_id, run_id, token_id, activation_no, node_key, activation_state) VALUES (%s, %s, %s, %s, 1, 'w2', 'WAITING')",
                    (t, act2, r, tok2),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.wait_registration(tenant_id, run_id, activation_id, wait_state, wait_kind, correlation_key, continuation) VALUES (%s, %s, %s, 'ACTIVE', 'MESSAGE', 'dirty-dup-key', '{\"nodeKey\":\"e1\"}')",
                    (t, r, act1),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.wait_registration(tenant_id, run_id, activation_id, wait_state, wait_kind, correlation_key, continuation) VALUES (%s, %s, %s, 'ACTIVE', 'MESSAGE', 'dirty-dup-key', '{\"nodeKey\":\"e2\"}')",
                    (t, r, act2),
                )

        # Direct SQL execution raises SQLSTATE 23505 with blocked preflight message
        sql_003 = (MIG_DIR / "003_g5_wait_correlation.sql").read_text(encoding="utf-8")
        try:
            with psycopg.connect(dsn(db), autocommit=True) as conn:
                with conn.cursor() as cur:
                    cur.execute(sql_003)
            raise AssertionError("Expected UniqueViolation from 003 preflight on duplicate ACTIVE Waits")
        except UniqueViolation as exc:
            assert exc.sqlstate == "23505"
            assert "G5 migration blocked: duplicate ACTIVE Wait correlation key" in str(exc)

        # Runner also blocks, leaves index uncreated, leaves 003 unrecorded, leaves rows intact
        blocked = run_migrator(db, "--verify", check=False)
        assert blocked.returncode != 0
        assert "G5 migration blocked: duplicate ACTIVE Wait correlation key" in blocked.stderr
        assert "003" not in fetch_ledger(db)
        assert index_info(db, "wait_active_correlation_uq")[0] is None

        with psycopg.connect(dsn(db)) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT count(*) FROM workflow_runtime.wait_registration WHERE tenant_id=%s AND wait_state='ACTIVE'",
                    (t,),
                )
                assert cur.fetchone()[0] == 2
                # Remediate by cancelling one duplicate ACTIVE row
                cur.execute(
                    """
                    UPDATE workflow_runtime.wait_registration
                       SET wait_state = 'CANCELLED', closed_at = clock_timestamp()
                     WHERE tenant_id = %s AND activation_id = %s
                    """,
                    (t, act2),
                )

        # Re-run now succeeds and validates schema contract
        fixed = run_migrator(db, "--verify")
        assert "APPLY 003" in fixed.stdout and "APPLY 004" in fixed.stdout
        assert "APPLY 006" in fixed.stdout
        assert set(fetch_ledger(db).keys()) == {"001", "003", "004", "005", "006"}
        idx_def, idx_valid = index_info(db, "wait_active_correlation_uq")
        assert idx_def is not None and idx_valid is True
    finally:
        drop_temp_db(db)
    print("PASS: 003 dirty-data preflight blocks cleanly and succeeds after remediation")


def test_004_online_concurrently_non_transactional_and_equivalent() -> None:
    db_std = create_temp_db("std004")
    db_con = create_temp_db("con004")
    try:
        run_migrator(db_std, "--verify")
        res = run_migrator(db_con, "--online-concurrently", "--verify")
        assert "APPLY 004 (004_g5_wait_inbox_idx_concurrently.sql, mode=ONLINE_CONCURRENTLY)" in res.stdout

        ledger_con = fetch_ledger(db_con)
        assert ledger_con["004"][0] == "004_g5_wait_inbox_idx_concurrently.sql"
        assert ledger_con["004"][2] == "ONLINE_CONCURRENTLY"

        std_def, std_valid = index_info(db_std, "inbox_wait_signal_pending_idx")
        con_def, con_valid = index_info(db_con, "inbox_wait_signal_pending_idx")
        assert std_valid is True and con_valid is True
        assert std_def == con_def, f"Index mismatch:\nSTD: {std_def}\nCON: {con_def}"

        # Re-running without --online-concurrently still cleanly skips recorded 004
        res_skip = run_migrator(db_con, "--verify")
        assert "SKIP 004 (004_g5_wait_inbox_idx_concurrently.sql, mode=ONLINE_CONCURRENTLY" in res_skip.stdout

        # Prove 004_g5_wait_inbox_idx_concurrently.sql has NO transaction wrapper and triggers 25001 inside BEGIN
        sql_con = (MIG_DIR / "004_g5_wait_inbox_idx_concurrently.sql").read_text(encoding="utf-8")
        try:
            with psycopg.connect(dsn(db_con)) as conn:
                with conn.cursor() as cur:
                    cur.execute(sql_con)
            raise AssertionError("Expected 25001 when running CREATE INDEX CONCURRENTLY inside tx")
        except ActiveSqlTransaction as exc:
            assert exc.sqlstate == "25001"
    finally:
        drop_temp_db(db_std)
        drop_temp_db(db_con)
    print("PASS: 004 CONCURRENTLY runs non-transactionally and builds identical valid index")


def test_adopt_existing_pre_ledger_database() -> None:
    db = create_temp_db("adopt")
    try:
        psql_file(db, "001_init.sql")
        psql_file(db, "003_g5_wait_correlation.sql")
        psql_file(db, "004_g5_wait_inbox_idx.sql")

        no_flag = run_migrator(db, "--verify", check=False)
        assert no_flag.returncode != 0
        assert "--adopt-existing" in no_flag.stderr

        adopted = run_migrator(db, "--adopt-existing", "--verify")
        assert "ADOPT 001" in adopted.stdout
        assert "APPLY 005" in adopted.stdout
        assert "ADOPT 003" in adopted.stdout
        assert "ADOPT 004" in adopted.stdout
        assert "APPLY 006" in adopted.stdout

        ledger = fetch_ledger(db)
        assert ledger["001"][2] == "BASELINE_ADOPTED"
        assert ledger["003"][2] == "BASELINE_ADOPTED"
        assert ledger["004"][2] == "BASELINE_ADOPTED"
        assert ledger["005"][2] == "STANDARD"
        assert ledger["006"][2] == "STANDARD"

        rerun = run_migrator(db, "--verify")
        assert "APPLY " not in rerun.stdout and "ADOPT " not in rerun.stdout
    finally:
        drop_temp_db(db)
    print("PASS: pre-ledger database baseline adoption and idempotent follow-up")


def test_adopt_existing_partial_missing_004() -> None:
    db = create_temp_db("partial004")
    try:
        psql_file(db, "001_init.sql")
        psql_file(db, "003_g5_wait_correlation.sql")

        res = run_migrator(db, "--adopt-existing", "--online-concurrently", "--verify")
        assert "ADOPT 001" in res.stdout
        assert "ADOPT 003" in res.stdout
        assert "APPLY 004 (004_g5_wait_inbox_idx_concurrently.sql, mode=ONLINE_CONCURRENTLY)" in res.stdout
        assert "APPLY 005" in res.stdout
        assert "APPLY 006" in res.stdout

        ledger = fetch_ledger(db)
        assert ledger["001"][2] == "BASELINE_ADOPTED"
        assert ledger["003"][2] == "BASELINE_ADOPTED"
        assert ledger["004"][2] == "ONLINE_CONCURRENTLY"
        assert ledger["005"][2] == "STANDARD"
        assert ledger["006"][2] == "STANDARD"
    finally:
        drop_temp_db(db)
    print("PASS: partial pre-ledger database adopts 001/003 and applies 004 CONCURRENTLY + 006")


def test_006_preflight_blocks_dirty_command_ledger_rows() -> None:
    db = create_temp_db("dirty006")
    try:
        psql_file(db, "001_init.sql")
        psql_file(db, "003_g5_wait_correlation.sql")
        psql_file(db, "004_g5_wait_inbox_idx.sql")
        psql_file(db, "005_migration_ledger.sql")
        with psycopg.connect(dsn(db)) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO workflow_runtime.schema_migration(version, filename, sha256, mode)
                    VALUES ('001', '001_init.sql', %s, 'STANDARD'),
                           ('003', '003_g5_wait_correlation.sql', %s, 'STANDARD'),
                           ('004', '004_g5_wait_inbox_idx.sql', %s, 'STANDARD'),
                           ('005', '005_migration_ledger.sql', %s, 'STANDARD')
                    """,
                    (
                        sha256_of("001_init.sql"),
                        sha256_of("003_g5_wait_correlation.sql"),
                        sha256_of("004_g5_wait_inbox_idx.sql"),
                        sha256_of("005_migration_ledger.sql"),
                    ),
                )
                t, d, r = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
                tok, act, att, cmd = uuid.uuid4(), uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
                cur.execute(
                    "INSERT INTO workflow_runtime.tenant(tenant_id, tenant_key, display_name) VALUES (%s, %s, 'dirty006')",
                    (t, f"dirty006-{t}"),
                )
                cur.execute(
                    """
                    INSERT INTO workflow_runtime.definition_snapshot
                      (tenant_id, definition_id, definition_key, definition_version, definition_hash, compiler_version, graph_plan)
                    VALUES (%s, %s, 'dirty006-def', '1', decode(repeat('ab', 32), 'hex'), 'ci', '{}')
                    """,
                    (t, d),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.workflow_run(tenant_id, run_id, definition_id, status) VALUES (%s, %s, %s, 'RUNNING')",
                    (t, r, d),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.execution_token(tenant_id, token_id, run_id, token_state, node_key) VALUES (%s, %s, %s, 'RUNNING', 'c1')",
                    (t, tok, r),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.node_activation(tenant_id, activation_id, run_id, token_id, activation_no, node_key, activation_state) VALUES (%s, %s, %s, %s, 1, 'c1', 'RUNNING')",
                    (t, act, r, tok),
                )
                cur.execute(
                    "INSERT INTO workflow_runtime.node_attempt(tenant_id, attempt_id, run_id, activation_id, attempt_no, dispatch_id, attempt_state, side_effect_class) VALUES (%s, %s, %s, %s, 1, %s, 'STARTED', 'PURE')",
                    (t, att, r, act, uuid.uuid4()),
                )
                cur.execute(
                    """
                    INSERT INTO workflow_runtime.command_ledger
                      (tenant_id, command_id, run_id, activation_id, attempt_id, device_id, command_kind, command_state, idempotency_class, command_payload, payload_hash)
                    VALUES (%s, %s, %s, %s, %s, 'dev-legacy', 'legacy_op', 'SUCCEEDED', 'IDEMPOTENT', '{}', decode(repeat('cd', 32), 'hex'))
                    """,
                    (t, cmd, r, act, att),
                )

        blocked = run_migrator(db, "--verify", check=False)
        assert blocked.returncode != 0
        assert "006 migration blocked" in blocked.stderr
        assert "CORE §6.1" in blocked.stderr
        assert "006" not in fetch_ledger(db)
    finally:
        drop_temp_db(db)
    print("PASS: 006 dirty-row preflight blocks pre-006 command_ledger row without partial commit")


def main() -> None:
    test_fresh_install_idempotent_and_checksum_drift()
    test_003_preflight_blocks_dirty_duplicate_active_wait_data()
    test_004_online_concurrently_non_transactional_and_equivalent()
    test_adopt_existing_pre_ledger_database()
    test_adopt_existing_partial_missing_004()
    test_006_preflight_blocks_dirty_command_ledger_rows()
    print("ALL MIGRATION LEDGER & PREFLIGHT TESTS PASSED")


if __name__ == "__main__":
    main()
