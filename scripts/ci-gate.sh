#!/usr/bin/env bash
# ============================================================================
# 门禁（新基线）
#
# 旧门禁测的是「三十座岛 + 一千多条流程 + 六百多个实验」累积出来的基线。
# 那些用例互相占设备、互相依赖历史数据 —— 红的时候大多不是逻辑坏了，
# 而是"设备被别的 run 占住了"（实测 2.6 段多次红，根因都是设备竞争）。
# 门禁红而说不清为什么，等于没有门禁。
#
# 新基线：**一座岛 + 5 台设备 + 一条流程** ⇒ 每条用例只测一件事，
# 失败时立刻知道是哪一类能力（机器人 / 孵育箱 / 离心机 / 读板机 / 封膜机）坏了。
#
# 段：
#   T0    阈值只许涨校验（机械校验：扫 git 历史，改用例不许把阈值调低）
#   T1    纯逻辑单测（不需要数据库）
#   T2    前端类型检查
#   T2.5  建库产物一致性（90_all_in_one vs 分片）
#   T3    基线设备三方对齐（中控清单 ↔ Redis ↔ device_state）
#   T4    基线端到端（流程 5 台设备真跑 + 实验派发 + 真实设备回包）
#   T5    设备互斥（同设备命令零重叠 + 确实排了队）
#   T6    上线/下线联动（管理台动作 ⇒ Redis ⇒ 内核判定 ⇒ 流程不假成功）
#   T7    崩溃恢复（kill -9 内核 → 重启 → 状态收敛）
#
# 分段（每段能单独跑，单段不超 CI 的单次时限）：
#   --fast      T0 T1 T2 T2.5     （不需要库 / 设备 / Redis）
#   --baseline  T3 T4 T5          （要库 + Redis + 模拟器 + tcp client + 内核）
#   --online    T6                （额外要设备管理台 8600）
#   --recovery  T7
#   --all       全部
#
# 环境变量（缺省值按本地开发机写；CI 上用环境注入）：
#   LABOS_DB_HOST/PORT/USER/PASS/NAME   数据库
#   LABOS_API                            内核地址（默认 127.0.0.1:18080）
#   LABOS_MANAGER_URL                    设备管理台（默认 127.0.0.1:8600）
#   LABOS_BASELINE_ISLAND                基线岛（默认 LAB-BASE-01）
#
# 用法：
#   ./scripts/ci-gate.sh --fast
#   ./scripts/ci-gate.sh --baseline
#   ./scripts/ci-gate.sh --all
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

# ---------------------------------------------------------------- 环境默认值
export LABOS_DB_HOST="${LABOS_DB_HOST:-192.168.124.2}"
export LABOS_DB_PORT="${LABOS_DB_PORT:-5432}"
export LABOS_DB_USER="${LABOS_DB_USER:-labos}"
export LABOS_DB_NAME="${LABOS_DB_NAME:-labdb}"
export LABOS_DB_PASS="${LABOS_DB_PASS:-$LABOS_DB_PASS}"
export PGPASSWORD="$LABOS_DB_PASS"
export LABOS_API="${LABOS_API:-http://127.0.0.1:18080}"
export LABOS_MANAGER_URL="${LABOS_MANAGER_URL:-http://127.0.0.1:8600}"
export LABOS_BASELINE_ISLAND="${LABOS_BASELINE_ISLAND:-LAB-BASE-01}"
export LABOS_REDIS_HOST="${LABOS_REDIS_HOST:-127.0.0.1}"
export LABOS_REDIS_PORT="${LABOS_REDIS_PORT:-6379}"
# 内核重启后能力必须与重启前一致（崩溃恢复段会 kill 再起内核）
export LABOS_EXEC_GRAPH="${LABOS_EXEC_GRAPH:-1}"
export LABOS_TRANSFER_PLAN="${LABOS_TRANSFER_PLAN:-1}"
export LABOS_AUTH="${LABOS_AUTH:-1}"

SECTIONS=",0,1,2,2.5,"
PROFILES=()

add_sections() {
  local s
  for s in "$@"; do
    if [[ "$SECTIONS" != *",$s,"* ]]; then
      SECTIONS+="$s,"
    fi
  done
}

want_section() { [[ "$SECTIONS" == *",$1,"* ]]; }

note_profile() { PROFILES+=("$1"); }

for a in "$@"; do
  case "$a" in
    --fast)     note_profile "fast"; ;;
    --baseline) note_profile "baseline"; add_sections 3 4 5 ;;
    --online)   note_profile "online"; add_sections 6 ;;
    --recovery) note_profile "recovery"; add_sections 7 ;;
    --all)      note_profile "all"; add_sections 3 4 5 6 7 ;;
    -h|--help)  sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "未知参数：${a}（--fast / --baseline / --online / --recovery / --all）" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- 阈值
# 只许涨不许跌：改了用例要同步改这里，逼着人想清楚"少的那几条去哪了"。
#
# 前缀用 MIN_BASE_ 而不是 MIN_：旧门禁的 MIN_* 阈值（对应旧基线的一堆用例）
# 与新基线不是一回事，拿旧阈值卡新门禁只会卡住重构。这里另起一套前缀，
# 但**机制照旧**：扫 git 历史里所有版本的 ci-gate.sh，对每个 MIN_BASE_*
# 取最大值，当前值低于它即红灯。
MIN_BASE_PURE=738      # 纯逻辑单测（Java，不连库）
MIN_BASE_DEVICES=6     # 设备三方对齐
MIN_BASE_FLOW=13       # 基线流程端到端（不含实验段）
MIN_BASE_EXP=2         # 实验派发段
MIN_BASE_MUTEX=4       # 设备互斥
MIN_BASE_ONLINE=10     # 上线/下线联动
MIN_BASE_CRASH=1       # 崩溃恢复（脚本整体通过即 1）

FAIL=0
SKIP=0
GATE_START=$(date +%s)
LAST_NOTE=""
LAST_TS=0

note() {
  local now; now=$(date +%s)
  if [[ -n "$LAST_NOTE" ]]; then
    printf '  \033[2m… %s 用时 %ss\033[0m\n' "$LAST_NOTE" "$((now - LAST_TS))"
  fi
  LAST_NOTE="$1"; LAST_TS=$now
  printf '\n\033[1m%s\033[0m\n' "$1"
}
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }
skip() { printf '  \033[33m⏭\033[0m %s\n' "$1"; SKIP=$((SKIP + 1)); }

# 解析子脚本的「通过 N / 失败 M」汇总行。
# 汇总行**缺失**要单独判一类（脚本没跑到最后一行 / 异常退出），
# 否则会被兜底成"失败 1 条"却一条明细都打不出来 —— "门禁红了却看不出为什么"。
parse_summary() {
  SUM_PASS=$(printf '%s' "$1" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  SUM_FAIL=$(printf '%s' "$1" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  SUM_MISSING=0
  [[ -z "$SUM_PASS" || -z "$SUM_FAIL" ]] && SUM_MISSING=1
  SUM_PASS=${SUM_PASS:-0}
  SUM_FAIL=${SUM_FAIL:-1}
}

# ---------------------------------------------------------------- T0 阈值只许涨
if want_section "0"; then
  note "0. 阈值只许涨校验（MIN_BASE_* 不得低于历史最高值）"
  DROPS=""
  # 注意用 `while read` 而不是 `for kv in $(...)`：
  # 后者会按 IFS 把 "KEY 738" 拆成两个 token，键值就散了（$2 未绑定）。
  while read -r key cur; do
    [[ -z "$key" || -z "$cur" ]] && continue
    # 历史最高值：扫 git 里所有版本的 ci-gate.sh
    hist=$(git log --format=%H --all -- scripts/ci-gate.sh 2>/dev/null | head -40 | while read -r c; do
      git show "$c:scripts/ci-gate.sh" 2>/dev/null \
        | grep -E "^${key}=" | sed -E "s/^${key}=([0-9]+).*/\1/" | sort -rn | head -1
    done | sort -rn | head -1)
    [[ -z "$hist" ]] && continue
    if (( cur < hist )); then
      DROPS="$DROPS $key($cur<$hist)"
    fi
  done < <(grep -E "^MIN_BASE_[A-Z0-9_]+=" "$0" \
    | sed -E "s/^(MIN_BASE_[A-Z0-9_]+)=([0-9]+).*/\1 \2/")
  if [[ -z "$DROPS" ]]; then
    pass "所有 MIN_BASE_* 阈值均不低于历史最高值"
  else
    fail "阈值被下调：${DROPS}（只许涨：下调会掩盖用例被删）"
  fi
fi

# ---------------------------------------------------------------- T1 纯逻辑
if want_section "1"; then
  note "1. 纯逻辑单测（不需要数据库）"
  OUT=$(./scripts/test-all.sh --pure-only 2>&1)
  parse_summary "$OUT"
  if [[ "$SUM_MISSING" == "1" ]]; then
    fail "单测脚本没打印汇总行（异常退出？）"
    echo "$OUT" | tail -20
  elif [[ "$SUM_FAIL" != "0" ]]; then
    fail "有失败用例（$SUM_FAIL 条）"
    echo "$OUT" | grep -E '✘|✗|无法解析输出|编译失败' | head -20
  elif (( SUM_PASS < MIN_BASE_PURE )); then
    fail "用例数 $SUM_PASS < 阈值 ${MIN_BASE_PURE}（有人删了用例？）"
  else
    pass "通过 $SUM_PASS / 失败 0（阈值 ${MIN_BASE_PURE}）"
  fi
fi

# ---------------------------------------------------------------- T2 tsc
if want_section "2"; then
  note "2. 前端类型检查"
  if ( cd webapp && npx tsc -b --force >/tmp/ci-tsc.log 2>&1 ); then
    pass "tsc 0 错误"
  else
    fail "tsc 有错误"
    head -15 /tmp/ci-tsc.log
  fi
fi

# ---------------------------------------------------------------- T2.5 建库产物
if want_section "2.5"; then
  note "2.5 建库产物一致性（90_all_in_one vs 分片）"
  if ./scripts/check-schema-sync.sh >/tmp/ci-schema.log 2>&1; then
    pass "产物与分片同源（含 loop_index / 4 列唯一键 / parent 外键）"
  else
    fail "建库产物过期（BUG-20261103-01 会复发）"
    grep '✗' /tmp/ci-schema.log | head -6
  fi
fi

# ---------------------------------------------------------------- 基线环境
#
# T3 之后的段都要"环境真的就绪"：Redis、模拟器、tcp client、内核四样。
# 以前这四样是手工各起各的，起漏一样现象就很难解释
# （设备明明在跑，页面显示离线 —— 其实只是 Redis 里没那条记录）。
# baseline-up.sh 幂等：已在跑的就跳过。
prepare_baseline() {
  echo "  准备基线环境（Redis / 模拟器 / tcp client / 内核）……"
  # 用 `bash <脚本>` 而不是直接执行：脚本刚 clone 出来时可能没有可执行位，
  # 直接执行会 Permission denied —— 门禁因此报"环境准备失败"，很难看出真因。
  if ! bash ./scripts/baseline-up.sh >/tmp/ci-baseline-up.log 2>&1; then
    fail "基线环境准备失败"
    tail -20 /tmp/ci-baseline-up.log
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------- T3 三方对齐
if want_section "3"; then
  note "3. 基线设备三方对齐（清单 ↔ Redis ↔ device_state）"
  prepare_baseline || true
  OUT=$(python3 scripts/verify-baseline-devices.py 2>&1)
  parse_summary "$OUT"
  if [[ "$SUM_MISSING" == "1" ]]; then
    fail "对齐脚本没打印汇总行"
    echo "$OUT" | tail -20
  elif [[ "$SUM_FAIL" != "0" ]]; then
    fail "三方对不齐（$SUM_FAIL 条）"
    echo "$OUT" | grep '✗' | head -10
  elif (( SUM_PASS < MIN_BASE_DEVICES )); then
    fail "断言数 $SUM_PASS < 阈值 ${MIN_BASE_DEVICES}"
  else
    pass "三方对齐 $SUM_PASS / 0（清单 / Redis / device_state 说同一件事）"
  fi
fi

# ---------------------------------------------------------------- T4 端到端
if want_section "4"; then
  note "4. 基线端到端（流程 5 台设备真跑 + 实验派发）"
  prepare_baseline || true
  OUT=$(python3 scripts/verify-baseline-flow.py --budget 180 2>&1)
  parse_summary "$OUT"
  if [[ "$SUM_MISSING" == "1" ]]; then
    fail "基线脚本没打印汇总行"
    echo "$OUT" | tail -20
  elif [[ "$SUM_FAIL" != "0" ]]; then
    fail "基线端到端有失败（$SUM_FAIL 条）"
    echo "$OUT" | grep '✗' | head -12
  elif (( SUM_PASS < MIN_BASE_FLOW + MIN_BASE_EXP )); then
    fail "断言数 $SUM_PASS < 阈值 $((MIN_BASE_FLOW + MIN_BASE_EXP))"
  else
    pass "基线端到端 $SUM_PASS / 0（5 类能力各真跑一遍 + 实验派发）"
  fi
fi

# ---------------------------------------------------------------- T5 设备互斥
if want_section "5"; then
  note "5. 设备互斥（同设备命令零重叠）"
  prepare_baseline || true
  OUT=$(python3 scripts/verify-baseline-mutex.py 2>&1)
  parse_summary "$OUT"
  if [[ "$SUM_MISSING" == "1" ]]; then
    fail "互斥脚本没打印汇总行"
    echo "$OUT" | tail -20
  elif [[ "$SUM_FAIL" != "0" ]]; then
    fail "设备互斥有失败（$SUM_FAIL 条）"
    echo "$OUT" | grep '✗' | head -10
  elif (( SUM_PASS < MIN_BASE_MUTEX )); then
    fail "断言数 $SUM_PASS < 阈值 ${MIN_BASE_MUTEX}"
  else
    pass "设备互斥 $SUM_PASS / 0（命令零重叠 + 确实排队）"
  fi
fi

# ---------------------------------------------------------------- T6 上下线联动
if want_section "6"; then
  note "6. 上线/下线联动（管理台 ⇒ Redis ⇒ 内核判定 ⇒ 流程不假成功）"
  prepare_baseline || true
  OUT=$(python3 scripts/verify-baseline-online.py 2>&1)
  parse_summary "$OUT"
  if [[ "$SUM_MISSING" == "1" ]]; then
    fail "上下线脚本没打印汇总行"
    echo "$OUT" | tail -20
  elif [[ "$SUM_FAIL" != "0" ]]; then
    fail "上下线联动有失败（$SUM_FAIL 条）"
    echo "$OUT" | grep '✗' | head -10
  elif (( SUM_PASS < MIN_BASE_ONLINE )); then
    fail "断言数 $SUM_PASS < 阈值 ${MIN_BASE_ONLINE}"
  else
    pass "上下线联动 $SUM_PASS / 0（离线即判离线，流程不假成功）"
  fi
fi

# ---------------------------------------------------------------- T7 崩溃恢复
if want_section "7"; then
  note "7. 崩溃恢复（kill -9 内核 → 重启 → 状态收敛）"
  prepare_baseline || true
  OUT=$(python3 scripts/verify-s3-2-residuals.py --api "$LABOS_API" 2>&1)
  RC=$?
  if [[ "$RC" != "0" ]]; then
    fail "崩溃恢复用例没通过（退出码 ${RC}）"
    echo "$OUT" | grep -E '✗|失败' | head -12
  else
    pass "崩溃恢复通过（孤儿 settle / 待派发 rehydrate）"
  fi
fi

# ---------------------------------------------------------------- 结果
if [[ -n "$LAST_NOTE" ]]; then
  now=$(date +%s)
  printf '  \033[2m… %s 用时 %ss\033[0m\n' "$LAST_NOTE" "$((now - LAST_TS))"
fi
printf '\n\033[1m结果\033[0m\n'
printf '  总用时 %ss　失败 %d　跳过 %d　段：%s\n' \
  "$(( $(date +%s) - GATE_START ))" "$FAIL" "$SKIP" "${PROFILES[*]:-无}"
if (( FAIL == 0 )); then
  printf '  \033[32m门禁通过\033[0m\n'
  exit 0
fi
printf '  \033[31m门禁未通过（%d 条）\033[0m\n' "$FAIL"
exit 1
