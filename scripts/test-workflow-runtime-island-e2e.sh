#!/usr/bin/env bash
# ============================================================================
# Workflow Runtime + Real DeviceCore (#!LABSEP!#) + PostgreSQL 17 E2E Runner
#
# Usage:
#   ./scripts/test-workflow-runtime-island-e2e.sh          # ephemeral local PG17, 1 run
#   REPEAT=3 ./scripts/test-workflow-runtime-island-e2e.sh # ephemeral local PG17, 3 runs
#   USE_EXISTING_PG=1 ./scripts/test-workflow-runtime-island-e2e.sh # CI / existing PG17
#
# Environment variables (when USE_EXISTING_PG=1):
#   PGHOST (default: localhost), PGPORT (default: 5432),
#   PGDATABASE (default: runtime_test), PGUSER (default: runtime), PGPASSWORD (default: runtime)
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPEAT="${REPEAT:-1}"
USE_EXISTING_PG="${USE_EXISTING_PG:-0}"

# ---- Locate JDK 17 ----
if [[ -x "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/javac" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# ---- Locate PostgreSQL 17 binaries (macOS Homebrew or Linux PATH) ----
if [[ -d "/opt/homebrew/opt/postgresql@17/bin" ]]; then
  export PATH="/opt/homebrew/opt/postgresql@17/bin:$PATH"
elif [[ -d "/usr/local/opt/postgresql@17/bin" ]]; then
  export PATH="/usr/local/opt/postgresql@17/bin:$PATH"
fi

PGDATA_DIR=""
cleanup() {
  if [[ -n "$PGDATA_DIR" && -d "$PGDATA_DIR" ]]; then
    pg_ctl -D "$PGDATA_DIR" -m immediate stop >/dev/null 2>&1 || true
    rm -rf "$PGDATA_DIR"
  fi
}
trap cleanup EXIT

# ---- 1. Compile workflow-runtime + labos-kernel (main + boot) + E2E self-test ----
EXTRA_CP=""
if [[ "${SKIP_COMPILE:-0}" != "1" ]]; then
  MVN_BIN="$(command -v mvn || true)"
  if [[ -z "$MVN_BIN" && -x "$HOME/.cache/labos-maven/apache-maven-3.9.9/bin/mvn" ]]; then
    MVN_BIN="$HOME/.cache/labos-maven/apache-maven-3.9.9/bin/mvn"
  fi

  if [[ -n "$MVN_BIN" && -x "$MVN_BIN" && "${FORCE_LOCAL_JAVAC:-0}" != "1" ]]; then
    bash "$ROOT/scripts/compile-kernel.sh"
  else
    # Offline / no-mvn fallback using local ~/.m2/repository + labos-kernel/lib
    mkdir -p "$ROOT/workflow-runtime/target/classes" "$ROOT/labos-kernel/out"
    M2_JARS="$(find "$HOME/.m2/repository" -name '*.jar' 2>/dev/null | tr '\n' ':')"
    EXTRA_CP="$ROOT/workflow-runtime/target/classes:${M2_JARS}"
    WR_CP="${EXTRA_CP}$ROOT/labos-kernel/lib/postgresql-42.7.3.jar:$ROOT/labos-kernel/lib/spring/*"
    WR_ARG="$(mktemp)"
    find "$ROOT/workflow-runtime/src/main/java" -name '*.java' | LC_ALL=C sort > "$WR_ARG"
    javac -encoding UTF-8 -parameters -d "$ROOT/workflow-runtime/target/classes" -cp "$WR_CP" "@$WR_ARG"
    rm -f "$WR_ARG"

    KERNEL_CP="$ROOT/labos-kernel/out:${EXTRA_CP}$ROOT/labos-kernel/lib/postgresql-42.7.3.jar:$ROOT/labos-kernel/lib/spring/*"
    K_ARG="$(mktemp)"
    find "$ROOT/labos-kernel/src/main" "$ROOT/labos-kernel/src/boot/java" -name '*.java' | LC_ALL=C sort > "$K_ARG"
    javac -encoding UTF-8 -parameters -d "$ROOT/labos-kernel/out" -cp "$KERNEL_CP" "@$K_ARG"
    rm -f "$K_ARG"
  fi

  mkdir -p "$ROOT/labos-kernel/out-test"
  TEST_CP="$ROOT/labos-kernel/out:$ROOT/labos-kernel/out-test:${EXTRA_CP}$ROOT/labos-kernel/lib/postgresql-42.7.3.jar:$ROOT/labos-kernel/lib/spring/*"
  javac -encoding UTF-8 -d "$ROOT/labos-kernel/out-test" -cp "$TEST_CP" \
    "$ROOT/labos-kernel/src/test/java/com/labos/boot/WorkflowRuntimeIslandSimulatorSelfTest.java"
elif [[ ! -d "$ROOT/labos-kernel/lib/spring" ]]; then
  M2_JARS="$(find "$HOME/.m2/repository" -name '*.jar' 2>/dev/null | tr '\n' ':')"
  EXTRA_CP="$ROOT/workflow-runtime/target/classes:${M2_JARS}"
fi

# ---- 2. Prepare PostgreSQL 17 ----
if [[ "$USE_EXISTING_PG" == "1" ]]; then
  export PGHOST="${PGHOST:-localhost}"
  export PGPORT="${PGPORT:-5432}"
  export PGDATABASE="${PGDATABASE:-runtime_test}"
  export PGUSER="${PGUSER:-runtime}"
  export PGPASSWORD="${PGPASSWORD:-runtime}"
else
  PGDATA_DIR="$(mktemp -d /tmp/labos-island-e2e-pg17.XXXXXX)"
  PGPORT_LOCAL="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
  initdb -D "$PGDATA_DIR" -U runtime --auth=trust --encoding=UTF8 >/dev/null
  pg_ctl -D "$PGDATA_DIR" -o "-p $PGPORT_LOCAL -k $PGDATA_DIR -c listen_addresses=127.0.0.1" \
    -l "$PGDATA_DIR/pg.log" start >/dev/null
  export PGHOST="127.0.0.1"
  export PGPORT="$PGPORT_LOCAL"
  export PGDATABASE="runtime_test"
  export PGUSER="runtime"
  export PGPASSWORD="runtime"
fi

# ---- 3. Execute Real DeviceCore Island E2E Self-Test ----
RUN_CP="$ROOT/labos-kernel/out:$ROOT/labos-kernel/out-test:${EXTRA_CP}$ROOT/labos-kernel/lib/postgresql-42.7.3.jar:$ROOT/labos-kernel/lib/spring/*"
for ((i = 1; i <= REPEAT; i++)); do
  if [[ "$USE_EXISTING_PG" != "1" ]]; then
    dropdb --if-exists -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$PGDATABASE" >/dev/null 2>&1 || true
    createdb -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$PGDATABASE"
    psql -v ON_ERROR_STOP=1 -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
      -f "$ROOT/db/workflow_runtime/001_init.sql" >/dev/null
  fi
  echo "==> Island Real DeviceCore E2E Run ${i}/${REPEAT} (PG ${PGHOST}:${PGPORT}/${PGDATABASE})"
  java -cp "$RUN_CP" com.labos.boot.WorkflowRuntimeIslandSimulatorSelfTest
done
