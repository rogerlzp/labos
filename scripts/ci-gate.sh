#!/usr/bin/env bash
# ============================================================================
# 门禁（2026-11-11 重写）—— **只做两件事**，其余一律不测。
#
# 旧门禁（40+ 脚本、面向三十座岛 + 一千多条流程的旧基线）已整体退役：
# 那些用例互相占设备、互相依赖历史数据，红的时候大多不是逻辑坏了，
# 而是「设备被别的 run 占住了」—— **门禁红了却说不出为什么，等于没有门禁**。
#
# 现在只有两段：
#   段 0 · 代码级兜底（每次 push 都跑，约 30 秒）
#         Java 纯逻辑单测 + 前端类型检查 + 建库产物一致性
#         —— 不碰设备、不碰库，拦住的是"改代码改坏了"这一类。
#   段 1 · 核心场景（要环境，约 3–5 分钟）
#         `scripts/core-scenarios.py` 的四个场景，全部以**页面操作**驱动：
#           ① 设备一致性与下线联动（页面的设备ID/IP 与中控清单一致；
#              管理台下线 ⇒ 中控页面也显示离线 ⇒ 上线后恢复）
#           ② 顺序执行（节点严格按顺序发生，不只是"都完成了"）
#           ③ 并行 + 变量条件 + 异或网关（真并行、按变量选边）
#           ④ 暂停 / 恢复（暂停期间不再下发指令，恢复后跑完且不重复）
#
# 用法：
#   ./scripts/ci-gate.sh              # 两段都跑
#   ./scripts/ci-gate.sh --fast       # 只跑段 0（不需要设备/库）
#   ./scripts/ci-gate.sh --scenarios  # 只跑段 1（核心场景）
#   ./scripts/ci-gate.sh --only 1     # 只跑场景 1（透传给 core-scenarios.py）
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

export LABOS_DB_HOST="${LABOS_DB_HOST:-192.168.124.2}"
export LABOS_DB_PORT="${LABOS_DB_PORT:-5432}"
export LABOS_DB_USER="${LABOS_DB_USER:-labos}"
export LABOS_DB_NAME="${LABOS_DB_NAME:-labdb}"
export LABOS_DB_PASS="${LABOS_DB_PASS:-$LABOS_DB_PASS}"
export PGPASSWORD="$LABOS_DB_PASS"
export LABOS_API="${LABOS_API:-http://127.0.0.1:18080}"
export LABOS_WEB="${LABOS_WEB:-http://localhost:5173}"
export LABOS_MANAGER_URL="${LABOS_MANAGER_URL:-http://127.0.0.1:8600}"
export LABOS_BASELINE_ISLAND="${LABOS_BASELINE_ISLAND:-LAB-BASE-01}"
export LABOS_REDIS_HOST="${LABOS_REDIS_HOST:-127.0.0.1}"
export LABOS_REDIS_PORT="${LABOS_REDIS_PORT:-6379}"
export LABOS_EXEC_GRAPH="${LABOS_EXEC_GRAPH:-1}"
export LABOS_TRANSFER_PLAN="${LABOS_TRANSFER_PLAN:-1}"
export LABOS_AUTH="${LABOS_AUTH:-1}"

WANT_FAST=1
WANT_SCEN=1
ONLY=""
for a in "$@"; do
  case "$a" in
    --fast)      WANT_SCEN=0 ;;
    --scenarios) WANT_FAST=0 ;;
    --only)      ONLY="--only ${2:-1}"; shift ;;
    -h|--help)   sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done

FAIL=0
GATE_START=$(date +%s)

note() { printf '\n\033[1m%s\033[0m\n' "$1"; }
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }

# ---------------------------------------------------------------- 段 0
if [[ "$WANT_FAST" == "1" ]]; then
  note "0. 代码级兜底（Java 单测 / tsc / 建库产物）"

  OUT=$(./scripts/test-all.sh --pure-only 2>&1)
  N=$(echo "$OUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  F=$(echo "$OUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  N=${N:-0}; F=${F:-1}
  if [[ "$F" != "0" ]]; then
    fail "Java 纯逻辑单测有失败（$F 条）"
    echo "$OUT" | grep -E '✘|✗|无法解析输出|编译失败' | head -20
  else
    pass "Java 纯逻辑单测 $N / 0"
  fi

  if ( cd webapp && npx tsc -b --force >/tmp/gate-tsc.log 2>&1 ); then
    pass "前端类型检查 0 错误"
  else
    fail "前端类型检查有错误"
    head -15 /tmp/gate-tsc.log
  fi

  if ./scripts/check-schema-sync.sh >/tmp/gate-schema.log 2>&1; then
    pass "建库产物与分片同源"
  else
    fail "建库产物过期"
    grep '✗' /tmp/gate-schema.log | head -6
  fi
fi

# ---------------------------------------------------------------- 段 1
if [[ "$WANT_SCEN" == "1" ]]; then
  note "1. 核心场景（页面操作驱动）"
  echo "  需要：中控 ${LABOS_API} · 前端 ${LABOS_WEB} · 设备管理台 ${LABOS_MANAGER_URL} · Redis ${LABOS_REDIS_HOST}:${LABOS_REDIS_PORT}"
  OUT=$(python3 scripts/core-scenarios.py $ONLY 2>&1)
  echo "$OUT"
  N=$(echo "$OUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  F=$(echo "$OUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  N=${N:-0}; F=${F:-1}
  if [[ "$F" != "0" ]]; then
    fail "核心场景有失败（$F 条）"
  else
    pass "核心场景 $N / 0"
  fi
fi

printf '\n\033[1m结果\033[0m\n'
printf '  总用时 %ss　失败 %d\n' "$(( $(date +%s) - GATE_START ))" "$FAIL"
if (( FAIL == 0 )); then
  printf '  \033[32m门禁通过\033[0m\n'
  exit 0
fi
printf '  \033[31m门禁未通过（%d 条）\033[0m\n' "$FAIL"
exit 1
