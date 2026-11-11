#!/usr/bin/env bash
# ============================================================================
# 基线环境准备：把新基线要用的东西全部拉起来。
#
# 新基线需要四样同时在位，缺一样就会出现"看着红、其实不是逻辑坏了"：
#   ① Redis        —— 设备在线状态的唯一权威（tcp client 往里写，内核从里读）
#   ② 模拟器       —— 每台基线设备一个（sim-up.sh 按岛内 code 排序分配端口）
#   ③ tcp client   —— sidecar 的 link-only 进程（保活长连接 + 写 Redis）
#   ④ 内核         —— 中控（18080）
#
# 这几样以前是"手工各起各的"，起漏一样现象就很难解释
# （比如设备明明在跑，页面显示离线 —— 其实只是 Redis 里没那条记录）。
# 现在收在一个脚本里：门禁与手工验证用同一套启动方式。
#
# 用法：
#   ./scripts/baseline-up.sh              # 全部拉起来
#   ./scripts/baseline-up.sh --no-kernel  # 不起内核（内核已在跑时）
# ============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export LABOS_DB_HOST="${LABOS_DB_HOST:-192.168.124.2}"
export LABOS_DB_PORT="${LABOS_DB_PORT:-5432}"
export LABOS_DB_USER="${LABOS_DB_USER:-labos}"
export LABOS_DB_NAME="${LABOS_DB_NAME:-labdb}"
export PGPASSWORD="${LABOS_DB_PASS:-$LABOS_DB_PASS}"
export LABOS_REDIS_HOST="${LABOS_REDIS_HOST:-127.0.0.1}"
export LABOS_REDIS_PORT="${LABOS_REDIS_PORT:-6379}"
ISLAND="${LABOS_BASELINE_ISLAND:-LAB-BASE-01}"
MANAGER_URL="${LABOS_MANAGER_URL:-http://127.0.0.1:8600}"

NO_KERNEL=0
[[ "${1:-}" == "--no-kernel" ]] && NO_KERNEL=1

echo "=== ① Redis ==="
if redis-cli -h "$LABOS_REDIS_HOST" -p "$LABOS_REDIS_PORT" ping >/dev/null 2>&1; then
  echo "  已在跑（${LABOS_REDIS_HOST}:${LABOS_REDIS_PORT}）"
else
  redis-server --daemonize yes --port "$LABOS_REDIS_PORT" --save '' --appendonly no
  sleep 1
  if redis-cli -h "$LABOS_REDIS_HOST" -p "$LABOS_REDIS_PORT" ping >/dev/null 2>&1; then
    echo "  已启动"
  else
    echo "  ✗ 起不来 —— 设备在线状态会全部判离线" >&2
    exit 1
  fi
fi

if [[ "$NO_KERNEL" == "0" ]]; then
  echo "=== ④ 内核（18080）==="
  if curl -s -m 3 -o /dev/null http://127.0.0.1:18080/health; then
    echo "  已在跑"
  else
    (nohup ./scripts/start-kernel.sh > /tmp/labos-kernel.log 2>&1 &)
    for _ in $(seq 1 90); do
      sleep 2
      if curl -s -m 2 -o /dev/null http://127.0.0.1:18080/health; then
        echo "  已就绪"
        break
      fi
    done
  fi
fi

echo "=== ② 模拟器（岛 ${ISLAND}）==="
"$ROOT/labos-kernel/scripts/sim-up.sh" "$ISLAND" 0 fast 2>&1 | sed -n '/已启动/,+2p'
sleep 2

echo "=== ③ tcp client（经设备管理台）==="
if ! curl -s -m 3 -o /dev/null "$MANAGER_URL/health"; then
  # 管理台不在 ⇒ 直接起 link-only sidecar（不监听端口的纯保活进程）
  DLL="$ROOT/sidecar-dotnet/Labos.Sidecar/bin/Debug/net10.0/Labos.Sidecar.dll"
  echo "  管理台没跑，直接起 link-only 进程"
  for line in $(psql -h "$LABOS_DB_HOST" -U "$LABOS_DB_USER" -d "$LABOS_DB_NAME" -tAc "
      SELECT di.id || '|' || di.ip || '|' || split_part(replace(d.endpoint,'tcp://',''),':',2)
        FROM device_instance di JOIN driver d ON d.id = di.driver_id
       WHERE di.tenant_id = 1 AND di.deleted_at IS NULL AND di.status = 'available'
         AND d.endpoint IS NOT NULL"); do
    IFS='|' read -r did dip dport <<< "$line"
    [ -z "$did" ] && continue
    nohup dotnet "$DLL" --link-only --transport sim --sim "$dip:$dport" \
      --redis-host "$LABOS_REDIS_HOST" --redis-port "$LABOS_REDIS_PORT" \
      --registry-url http://127.0.0.1:18080 \
      > "/tmp/sidecar-link-$did.log" 2>&1 &
  done
else
  curl -s -X POST "$MANAGER_URL/api/devices/start-all" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print('  已上线 %s 台' % d.get('started',0)); [print('  ! %s'%e) for e in d.get('errors',[])]"
fi
sleep 3

echo
echo "=== 基线就绪状态 ==="
curl -s "$MANAGER_URL/api/devices" 2>/dev/null | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    for x in d['devices']:
        print('  %-14s 设备ID=%-5s %s:%-6s %s' % (x['code'], x['deviceId'], x['ip'], x['port'],
              '在线' if x['online'] else '离线'))
except Exception:
    print('  （管理台没跑，看 Redis key 判断）')
" || true
redis-cli -h "$LABOS_REDIS_HOST" -p "$LABOS_REDIS_PORT" --scan --pattern 'labos:link:*' 2>/dev/null | sort | sed 's/^/  /'
