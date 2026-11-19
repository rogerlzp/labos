#!/usr/bin/env bash
set -euo pipefail
mvn -f "$(dirname "$0")/../../labos-kernel/workflow-runtime-test-pom.xml" -Dtest=WorkflowRuntimeStateMachineTest test
