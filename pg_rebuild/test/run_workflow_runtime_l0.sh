#!/usr/bin/env bash
set -euo pipefail
mvn -f "$(dirname "$0")/../../labos-kernel/pom.xml" -Dtest=WorkflowRuntimeStateMachineTest test
