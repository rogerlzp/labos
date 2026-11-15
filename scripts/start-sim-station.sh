#!/usr/bin/env bash
# ============================================================================
# 启动「仿真环境」：内核 + 模拟器 + sidecar（一条命令拉起整条线）
#
#   ./scripts/start-sim-station.sh            # 拉起（幂等：已在跑的会跳过）
#   ./scripts/start-sim-station.sh --restart  # 全停再全起
#   ./scripts/start-sim-station.sh --status   # 只看现状
#
# 为什么要有这个脚本（2026-11-15 踩过的坑都在这收口）：
#   ① **设备事件入站**：内核默认关着（LABOS_DEVICE_EVENT_TOKEN 没配 ⇒ 503），
#      侧车上报的 waiting_interaction 全部被拒 ⇒ 流程卡在「等交互」。
#      ⇒ 仿真环境用**本机联调开关**：LABOS_DEVICE_EVENT_ALLOW_ANONYMOUS=1。
#      ⚠ 真机必须换成真令牌，不能开匿名。
#   ② **自动确认**（抓板证据闸门）：仿真态用 LABOS_TRANSFER_AUTO_CONFIRM 打开，
#      否则「抓板前强制扫码」会让仿真跑不动（真机禁）。
#   ③ **模拟器认领设备**：sim-all.py 已把 --device/--code/--model 直接传下去
#      （模拟器自己再读一遍中控清单会超时 ⇒ device=0 ⇒ 收不下任何命令）。
#   ④ **在线状态 = Redis link 键**，不是 TCP 探测 ⇒ 必须有 sidecar。
#
# 环境变量从 /tmp/labos-env.txt 取（裸 KEY=value ⇒ 必须 set -a 才会导出给子进程）。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${LABOS_ENV_FILE:-/tmp/labos-env.txt}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

# ---- 仿真专用开关（真机不要照抄）----
export LABOS_AUTH="${LABOS_AUTH:-1}"
export LABOS_EXEC_GRAPH="${LABOS_EXEC_GRAPH:-1}"
export LABOS_TRANSFER_PLAN="${LABOS_TRANSFER_PLAN:-1}"
export LABOS_REDIS_HOST="${LABOS_REDIS_HOST:-127.0.0.1}"
export LABOS_REDIS_PORT="${LABOS_REDIS_PORT:-6379}"
# ① 设备事件入站：本机联调模式（匿名）
export LABOS_DEVICE_EVENT_ALLOW_ANONYMOUS="${LABOS_DEVICE_EVENT_ALLOW_ANONYMOUS:-1}"
# ② 抓板证据闸门：仿真态放开（按流程 id 粒度；真机禁用）
export LABOS_TRANSFER_AUTO_CONFIRM="${LABOS_TRANSFER_AUTO_CONFIRM:-1000830,1000929}"

MODE="${1:-start}"

if [[ "$MODE" == "--status" ]]; then
  python3 scripts/sim-all.py --status
  echo "内核：$(lsof -nP -iTCP:18080 -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print "在（PID "$2"）"}')"
  exit 0
fi

# ---- 内核 ----
if [[ "$MODE" == "--restart" ]]; then
  PID="$(lsof -nP -iTCP:18080 -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $2}')"
  if [[ -n "$PID" ]]; then
    echo "停内核 PID=$PID"
    kill "$PID" 2>/dev/null || true
    sleep 4
  fi
  nohup ./scripts/start-kernel.sh > /tmp/labos-kernel-sim.log 2>&1 &
  echo "内核启动中（日志 /tmp/labos-kernel-sim.log）…"
  for _ in $(seq 1 40); do
    sleep 2
    if lsof -nP -iTCP:18080 -sTCP:LISTEN >/dev/null 2>&1; then
      echo "✓ 内核已就绪（18080）"
      break
    fi
  done
fi

if ! lsof -nP -iTCP:18080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "内核没在 18080 上 —— 先用 --restart，或看 /tmp/labos-kernel-sim.log"
  exit 1
fi

# ---- 模拟器 + sidecar ----
python3 scripts/sim-all.py --restart --with-sidecar
