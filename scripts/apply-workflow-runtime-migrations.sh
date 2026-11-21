#!/usr/bin/env bash
# ============================================================================
# Idempotent Version-Ledgered Migration Runner for workflow_runtime (PG 17+)
#
# Usage:
#   ./scripts/apply-workflow-runtime-migrations.sh [options]
#
# Options:
#   --online-concurrently  Apply 004 via CREATE INDEX CONCURRENTLY outside tx
#   --adopt-existing       Baseline-adopt an existing pre-ledger workflow_runtime schema
#   --verify               Run 002_schema_contract.sql after applying migrations
#   --quiet                Suppress psql stdout while keeping migration status lines
#   -h, --help             Show this help message
#
# Connection uses standard PostgreSQL environment variables (PGHOST, PGPORT,
# PGDATABASE, PGUSER, PGPASSWORD, PGSSLMODE, etc.).
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIG_DIR="${WORKFLOW_RUNTIME_MIGRATIONS_DIR:-$ROOT/db/workflow_runtime}"

ONLINE_CONCURRENTLY=0
ADOPT_EXISTING=0
VERIFY=0
QUIET=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --online-concurrently) ONLINE_CONCURRENTLY=1; shift ;;
    --adopt-existing) ADOPT_EXISTING=1; shift ;;
    --verify) VERIFY=1; shift ;;
    --quiet) QUIET=1; shift ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "✗ Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

# Locate PostgreSQL 17 binaries when installed via Homebrew
if [[ -d "/opt/homebrew/opt/postgresql@17/bin" ]]; then
  export PATH="/opt/homebrew/opt/postgresql@17/bin:$PATH"
elif [[ -d "/usr/local/opt/postgresql@17/bin" ]]; then
  export PATH="/usr/local/opt/postgresql@17/bin:$PATH"
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "✗ psql not found on PATH" >&2
  exit 2
fi

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print tolower($1)}'
  else
    python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$path"
  fi
}

psql_scalar() {
  local sql="$1"
  psql -X -v ON_ERROR_STOP=1 -Atc "$sql"
}

run_sql_file() {
  local file="$1"
  if [[ "$QUIET" == "1" ]]; then
    psql -X -v ON_ERROR_STOP=1 -q -f "$file" >/dev/null
  else
    psql -X -v ON_ERROR_STOP=1 -f "$file"
  fi
}

record_ledger() {
  local ver="$1"
  local fname="$2"
  local sum="$3"
  local mode="$4"
  psql -X -v ON_ERROR_STOP=1 -q -At \
    -v ver="$ver" -v fname="$fname" -v sum="$sum" -v mode="$mode" <<'SQL' >/dev/null
INSERT INTO workflow_runtime.schema_migration (version, filename, sha256, mode)
VALUES (:'ver', :'fname', :'sum', :'mode')
ON CONFLICT (version) DO NOTHING;
SQL
}

query_ledger_row() {
  local ver="$1"
  psql -X -v ON_ERROR_STOP=1 -At -v ver="$ver" <<'SQL'
SELECT filename || '|' || sha256 || '|' || mode
  FROM workflow_runtime.schema_migration
 WHERE version = :'ver';
SQL
}

index_state() {
  local idx="$1"
  psql -X -v ON_ERROR_STOP=1 -At -v idx="$idx" <<'SQL'
SELECT CASE
         WHEN count(*) = 0 THEN 'MISSING'
         WHEN bool_and(i.indisvalid) THEN 'VALID'
         ELSE 'INVALID'
       END
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'workflow_runtime'
   AND c.relname = :'idx';
SQL
}

is_allowed_filename() {
  local ver="$1"
  local fname="$2"
  case "$ver" in
    001) [[ "$fname" == "001_init.sql" ]] ;;
    003) [[ "$fname" == "003_g5_wait_correlation.sql" ]] ;;
    004) [[ "$fname" == "004_g5_wait_inbox_idx.sql" || "$fname" == "004_g5_wait_inbox_idx_concurrently.sql" ]] ;;
    005) [[ "$fname" == "005_migration_ledger.sql" ]] ;;
    *) return 1 ;;
  esac
}

# 1. Verify PostgreSQL 17+
SERVER_VER="$(psql_scalar "SHOW server_version_num")"
if [[ "$SERVER_VER" -lt 170000 ]]; then
  echo "✗ workflow_runtime requires PostgreSQL 17+ (server_version_num=${SERVER_VER})" >&2
  exit 2
fi

HAS_SCHEMA="$(psql_scalar "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='workflow_runtime') THEN '1' ELSE '0' END")"
HAS_LEDGER="$(psql_scalar "SELECT CASE WHEN to_regclass('workflow_runtime.schema_migration') IS NOT NULL THEN '1' ELSE '0' END")"

# 2. Branch A / Branch B initialization before ordered loop
if [[ "$HAS_SCHEMA" == "0" ]]; then
  F001="$MIG_DIR/001_init.sql"
  F005="$MIG_DIR/005_migration_ledger.sql"
  run_sql_file "$F001"
  run_sql_file "$F005"
  S001="$(sha256_file "$F001")"
  S005="$(sha256_file "$F005")"
  record_ledger "001" "001_init.sql" "$S001" "STANDARD"
  record_ledger "005" "005_migration_ledger.sql" "$S005" "STANDARD"
  echo "[migrate] APPLY 001 (001_init.sql, mode=STANDARD)"
  echo "[migrate] APPLY 005 (005_migration_ledger.sql, mode=STANDARD)"
elif [[ "$HAS_LEDGER" == "0" ]]; then
  if [[ "$ADOPT_EXISTING" != "1" ]]; then
    echo "✗ Existing workflow_runtime schema found without schema_migration ledger. Re-run with --adopt-existing to baseline-adopt." >&2
    exit 2
  fi
  CORE_PRESENT="$(psql_scalar "SELECT CASE WHEN to_regclass('workflow_runtime.workflow_run') IS NOT NULL AND to_regclass('workflow_runtime.wait_registration') IS NOT NULL AND to_regclass('workflow_runtime.inbox_receipt') IS NOT NULL THEN '1' ELSE '0' END")"
  if [[ "$CORE_PRESENT" != "1" ]]; then
    echo "✗ Existing workflow_runtime schema is missing core tables (workflow_run/wait_registration/inbox_receipt); refusing --adopt-existing." >&2
    exit 2
  fi
  IDX003_STATE="$(index_state "wait_active_correlation_uq")"
  IDX004_STATE="$(index_state "inbox_wait_signal_pending_idx")"
  if [[ "$IDX003_STATE" == "INVALID" ]]; then
    echo "✗ Existing index workflow_runtime.wait_active_correlation_uq is INVALID. Drop it manually (DROP INDEX CONCURRENTLY) before adopting." >&2
    exit 2
  fi
  if [[ "$IDX004_STATE" == "INVALID" ]]; then
    echo "✗ Existing index workflow_runtime.inbox_wait_signal_pending_idx is INVALID. Drop it manually (DROP INDEX CONCURRENTLY) before adopting." >&2
    exit 2
  fi

  F005="$MIG_DIR/005_migration_ledger.sql"
  run_sql_file "$F005"
  S001="$(sha256_file "$MIG_DIR/001_init.sql")"
  S005="$(sha256_file "$F005")"
  record_ledger "001" "001_init.sql" "$S001" "BASELINE_ADOPTED"
  echo "[migrate] ADOPT 001 (001_init.sql, mode=BASELINE_ADOPTED)"
  record_ledger "005" "005_migration_ledger.sql" "$S005" "STANDARD"
  echo "[migrate] APPLY 005 (005_migration_ledger.sql, mode=STANDARD)"

  if [[ "$IDX003_STATE" == "VALID" ]]; then
    S003="$(sha256_file "$MIG_DIR/003_g5_wait_correlation.sql")"
    record_ledger "003" "003_g5_wait_correlation.sql" "$S003" "BASELINE_ADOPTED"
    echo "[migrate] ADOPT 003 (003_g5_wait_correlation.sql, mode=BASELINE_ADOPTED)"
  fi
  if [[ "$IDX004_STATE" == "VALID" ]]; then
    S004="$(sha256_file "$MIG_DIR/004_g5_wait_inbox_idx.sql")"
    record_ledger "004" "004_g5_wait_inbox_idx.sql" "$S004" "BASELINE_ADOPTED"
    echo "[migrate] ADOPT 004 (004_g5_wait_inbox_idx.sql, mode=BASELINE_ADOPTED)"
  fi
fi

# 3. Branch C: ordered ledger verification & pending migration application
for ver in 001 003 004 005; do
  row="$(query_ledger_row "$ver")"
  if [[ -n "$row" ]]; then
    IFS='|' read -r rec_fname rec_sha rec_mode <<<"$row"
    if ! is_allowed_filename "$ver" "$rec_fname"; then
      echo "✗ Migration ledger drift for version ${ver}: disallowed recorded filename '${rec_fname}'" >&2
      exit 2
    fi
    fpath="$MIG_DIR/$rec_fname"
    if [[ ! -f "$fpath" ]]; then
      echo "✗ Migration ledger drift for version ${ver}: recorded file '${rec_fname}' not found at ${fpath}" >&2
      exit 2
    fi
    actual_sha="$(sha256_file "$fpath")"
    if [[ "$actual_sha" != "$rec_sha" ]]; then
      echo "✗ Migration checksum drift for version ${ver} (${rec_fname}): expected=${rec_sha}, actual=${actual_sha}" >&2
      exit 2
    fi
    echo "[migrate] SKIP ${ver} (${rec_fname}, mode=${rec_mode}, sha256 verified)"
    continue
  fi

  case "$ver" in
    001)
      fname="001_init.sql"
      mode="STANDARD"
      ;;
    003)
      fname="003_g5_wait_correlation.sql"
      mode="STANDARD"
      IDX003_STATE="$(index_state "wait_active_correlation_uq")"
      if [[ "$IDX003_STATE" == "INVALID" ]]; then
        echo "✗ Existing index workflow_runtime.wait_active_correlation_uq is INVALID. Drop it before applying 003." >&2
        exit 2
      fi
      ;;
    004)
      IDX004_STATE="$(index_state "inbox_wait_signal_pending_idx")"
      if [[ "$IDX004_STATE" == "INVALID" ]]; then
        echo "✗ Existing index workflow_runtime.inbox_wait_signal_pending_idx is INVALID. Drop it (DROP INDEX CONCURRENTLY workflow_runtime.inbox_wait_signal_pending_idx;) before re-running 004." >&2
        exit 2
      fi
      if [[ "$ONLINE_CONCURRENTLY" == "1" ]]; then
        fname="004_g5_wait_inbox_idx_concurrently.sql"
        mode="ONLINE_CONCURRENTLY"
      else
        fname="004_g5_wait_inbox_idx.sql"
        mode="STANDARD"
      fi
      ;;
    005)
      fname="005_migration_ledger.sql"
      mode="STANDARD"
      ;;
  esac

  fpath="$MIG_DIR/$fname"
  fsha="$(sha256_file "$fpath")"
  run_sql_file "$fpath"

  if [[ "$ver" == "003" ]]; then
    IDX003_POST="$(index_state "wait_active_correlation_uq")"
    if [[ "$IDX003_POST" != "VALID" ]]; then
      echo "✗ Migration 003 completed but wait_active_correlation_uq state is '${IDX003_POST}'; refusing ledger record." >&2
      exit 2
    fi
  elif [[ "$ver" == "004" ]]; then
    IDX004_POST="$(index_state "inbox_wait_signal_pending_idx")"
    if [[ "$IDX004_POST" != "VALID" ]]; then
      echo "✗ Migration 004 completed but inbox_wait_signal_pending_idx state is '${IDX004_POST}'; refusing ledger record." >&2
      exit 2
    fi
  fi

  record_ledger "$ver" "$fname" "$fsha" "$mode"
  echo "[migrate] APPLY ${ver} (${fname}, mode=${mode})"
done

if [[ "$VERIFY" == "1" ]]; then
  run_sql_file "$MIG_DIR/002_schema_contract.sql"
  echo "[migrate] VERIFY 002_schema_contract.sql PASSED"
fi
