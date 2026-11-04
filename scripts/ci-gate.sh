#!/usr/bin/env bash
# ============================================================================
# CI 门禁（M8）
#
# 解决的问题：以前"改完有没有坏"靠人点。现在把可自动化的部分变成**硬阈值**：
#   · 纯逻辑单测必须 ≥ 536 且 0 失败（数字下降 = 有人删了用例，也算红灯）
#   · 前端 tsc 必须 0 错误
#   · 可选：并行能力（流程内并行 4 条 / 同岛并发 3 条，要库 + 模拟器 + API）
#   · 可选：设备链路 41 条（要库 + 模拟器）
#   · 可选：前端 e2e 24 条（要前端/API 在跑）
#
# 用法：
#   ./scripts/ci-gate.sh                 # 纯逻辑 + tsc（不需要数据库）
#   ./scripts/ci-gate.sh --with-db       # 再加设备链路（需要 LABOS_DB_*）
#   ./scripts/ci-gate.sh --e2e           # 再加前端 e2e（需要 5173/18080）
#   ./scripts/ci-gate.sh --all           # 全跑
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

WITH_DB=0
WITH_E2E=0
for a in "$@"; do
  case "$a" in
    --with-db) WITH_DB=1 ;;
    --e2e) WITH_E2E=1 ;;
    --all) WITH_DB=1; WITH_E2E=1 ;;
    *) echo "未知参数：$a"; exit 2 ;;
  esac
done

# 阈值：只许涨不许跌。改了用例要同步改这里，逼着人想清楚"少的那几条去哪了"。
MIN_PURE=536    # 2026-11-03：权限收口 RbacRulesSelfTest 15 条（521 → 536）
MIN_CHAIN=41
MIN_PFLOW=4     # 流程内并行：整单完成 / 两分支都在 / 两分支都完成 / 同设备命令不重叠
MIN_PEXP=3      # 同岛并发：有排队 / 峰值并行=1 / 全部完成
MIN_E2E=24        # 2026-11-03：权限收口 +Header 角色断言（23 → 24）

FAIL=0
note() { printf '\n\033[1m%s\033[0m\n' "$1"; }
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }

# ---------------------------------------------------------------- 1. 纯逻辑
note "1. 纯逻辑单测（不需要数据库）"
OUT=$(./scripts/test-all.sh --pure-only 2>&1)
N=$(echo "$OUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
F=$(echo "$OUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
N=${N:-0}; F=${F:-1}
if [[ "$F" != "0" ]]; then
  fail "有失败用例（$F 条）"
  echo "$OUT" | grep -E '✘|✗' | head -10
elif (( N < MIN_PURE )); then
  fail "用例数 $N < 阈值 ${MIN_PURE}（有人删了用例？）"
else
  pass "通过 $N / 失败 0（阈值 ${MIN_PURE}）"
fi

# ---------------------------------------------------------------- 2. tsc
note "2. 前端类型检查"
if ( cd webapp && npx tsc -b --force >/tmp/ci-tsc.log 2>&1 ); then
  pass "tsc 0 错误"
else
  fail "tsc 有错误"
  head -15 /tmp/ci-tsc.log
fi

# ---------------------------------------------------------------- 2.5 建库产物
note "2.5 建库产物一致性（90_all_in_one vs 分片）"
if ./scripts/check-schema-sync.sh >/tmp/ci-schema.log 2>&1; then
  pass "产物与分片同源（含 loop_index / 4 列唯一键 / parent 外键）"
else
  fail "建库产物陈旧（BUG-20261103-01 会复发）"
  grep '✗' /tmp/ci-schema.log | head -6
fi

# ---------------------------------------------------------------- 2.6 并行能力
#
# 放在设备链路**之前**：链路脚本跑完会把自己的 sidecar 停掉，
# 而这两个脚本要靠环境里已经在跑的 sidecar + 模拟器。
if [[ $WITH_DB -eq 1 ]]; then
  note "2.6 并行能力（流程内并行 / 同岛多实验）"

  POUT=$(python3 scripts/verify-parallel-flow.py 2>&1)
  if echo "$POUT" | grep -q '^SKIP'; then
    echo "  跳过（环境里没有可用设备）"
  else
    PN=$(echo "$POUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    PF=$(echo "$POUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    PN=${PN:-0}; PF=${PF:-1}
    if [[ "$PF" != "0" ]]; then
      fail "流程内并行有失败（$PF 条）"
      echo "$POUT" | grep '✗' | head -6
    elif (( PN < MIN_PFLOW )); then
      fail "流程内并行断言 $PN < 阈值 ${MIN_PFLOW}"
    else
      pass "流程内并行 $PN / 0（两条分支都跑、同设备自动串行）"
    fi
  fi

  EOUT=$(python3 scripts/verify-parallel-experiments.py --count 4 2>&1)
  if echo "$EOUT" | grep -q '^SKIP'; then
    echo "  跳过（API 不可用）"
  else
    EN=$(echo "$EOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    EF=$(echo "$EOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    EN=${EN:-0}; EF=${EF:-1}
    if [[ "$EF" != "0" ]]; then
      fail "同岛并发有失败（$EF 条）"
      echo "$EOUT" | grep '✗' | head -6
    elif (( EN < MIN_PEXP )); then
      fail "同岛并发断言 $EN < 阈值 ${MIN_PEXP}"
    else
      pass "同岛并发 $EN / 0（峰值并行 = 1，岛级互斥生效）"
    fi
  fi
else
  note "2.6 并行能力：跳过（没给 --with-db）"
fi

# ---------------------------------------------------------------- 3. 设备链路
if [[ $WITH_DB -eq 1 ]]; then
  note "3. 设备全链路（中控 → sidecar → 模拟器 → 库）"
  OUT=$(./scripts/verify-device-chain.sh 2>&1)
  CN=$(echo "$OUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  CF=$(echo "$OUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  CN=${CN:-0}; CF=${CF:-1}
  if [[ "$CF" != "0" ]]; then
    fail "链路有失败（$CF 条）"
    echo "$OUT" | grep '✗' | head -10
  elif (( CN < MIN_CHAIN )); then
    fail "断言数 $CN < 阈值 $MIN_CHAIN"
  else
    pass "通过 $CN / 失败 0（阈值 ${MIN_CHAIN}）"
  fi
else
  note "3. 设备全链路：跳过（没给 --with-db）"
fi

# ---------------------------------------------------------------- 4. e2e
if [[ $WITH_E2E -eq 1 ]]; then
  note "4. 前端 e2e（5 条主链路）"
  OUT=$(python3 scripts/e2e-smoke.py 2>&1)
  EN=$(echo "$OUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  EF=$(echo "$OUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  EN=${EN:-0}; EF=${EF:-1}
  if [[ "$EF" != "0" ]]; then
    fail "e2e 有失败（$EF 条）"
    echo "$OUT" | grep '✗' | head -10
  elif (( EN < MIN_E2E )); then
    fail "断言数 $EN < 阈值 $MIN_E2E"
  else
    pass "通过 $EN / 失败 0（阈值 ${MIN_E2E}）"
  fi
else
  note "4. 前端 e2e：跳过（没给 --e2e）"
fi

# ---------------------------------------------------------------- 结果
note "结果"
if [[ $FAIL -eq 0 ]]; then
  printf '  \033[32m门禁通过\033[0m\n'
  exit 0
fi
printf '  \033[31m门禁失败：%d 项\033[0m\n' "$FAIL"
exit 1
