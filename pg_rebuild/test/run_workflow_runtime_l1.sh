#!/usr/bin/env bash
set -euo pipefail

: "${LABOS_RUNTIME_TEST_URL:?set LABOS_RUNTIME_TEST_URL to a disposable PostgreSQL database}"
SCHEMA="${LABOS_RUNTIME_TEST_SCHEMA:-wf_runtime_test}"
[[ "$SCHEMA" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || { echo "invalid schema name" >&2; exit 2; }

psql "$LABOS_RUNTIME_TEST_URL" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\""
for pass in 1 2; do
  PGOPTIONS="-c search_path=$SCHEMA" psql "$LABOS_RUNTIME_TEST_URL" -v ON_ERROR_STOP=1 \
    -f "$(dirname "$0")/../schema/100_workflow_runtime_v1.sql"
done

export LABOS_RUNTIME_TEST_SCHEMA="$SCHEMA"
export LABOS_RUNTIME_TEST_USER="${LABOS_RUNTIME_TEST_USER:-${PGUSER:-}}"
export LABOS_RUNTIME_TEST_PASSWORD="${LABOS_RUNTIME_TEST_PASSWORD:-${PGPASSWORD:-}}"
mvn -f "$(dirname "$0")/../../labos-kernel/pom.xml" -Dtest=WorkflowRuntimePostgresContractTest,PostgresWorkflowRuntimeBootstrapRepositoryTest,PostgresWorkflowRuntimeDispatchRepositoryTest test
