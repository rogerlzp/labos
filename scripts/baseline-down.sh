#!/usr/bin/env bash
# ============================================================================
# 基线环境收工：把 baseline-up.sh 拉起来的东西停掉。
#
#   ./scripts/baseline-down.sh          # 停 tcp client + 模拟器
#   ./scripts/baseline-down.sh --all    # 连 Redis 和内核一起停
# ============================================================================
set -uo pipefail

MANAGER_URL="${LABOS_MANAGER_URL:-http://127.0.0.1:8600}"
ALL=0
[[ "${1:-}" == "--all" ]] && ALL=1

echo "=== 停 tcp client ==="
if curl -s -m 3 -o /dev/null "$MANAGER_URL/health"; then
  curl -s -X POST "$MANAGER_URL/api/devices/stop-all" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print('  已下线 %s 台' % d.get('stopped',0))" || true
else
  pkill -f "Labos.Sidecar.dll --link-only" 2>/dev/null && echo "  已停（直接 kill）"
fi

echo "=== 停模拟器 ==="
pkill -f "com.labos.kernel.sim.DeviceSimulator" 2>/dev/null && echo "  已停"

if [[ "$ALL" == "1" ]]; then
  echo "=== 停内核 ==="
  pkill -f "com.labos.boot.LabosApplication" 2>/dev/null && echo "  已停"
  echo "=== 停 Redis ==="
  redis-cli shutdown nosave 2>/dev/null && echo "  已停" || echo "  没跑"
fi
