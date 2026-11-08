#!/usr/bin/env bash
# ============================================================================
# CI 门禁（M8）
#
# 解决的问题：以前"改完有没有坏"靠人点。现在把可自动化的部分变成**硬阈值**：
#   · 纯逻辑单测必须 ≥ 651 且 0 失败（数字下降 = 有人删了用例，也算红灯）
#   · 前端 tsc 必须 0 错误
#   · 可选：并行能力（流程内并行 4 条 / 同岛并发 3 条，要库 + 模拟器 + API）
#   · 可选：完整流程矩阵 55 条（节点/网关/变量/延时/等待/循环/回边/并行/端口/暂停，要库 + 模拟器 + API）
#   · 可选：设备链路 41 条（要库 + 模拟器）
#   · 可选：前端 e2e 24 条（要前端/API 在跑）
#   · 可选：页面流程矩阵 45 条（设计器 palette/逻辑节点/变量节点/接缝真跑/运行详情/页面暂停恢复，要前端+API+库）
#   · 可选：实验运行控制 22 条（页面暂停/恢复/停止，要前端+API+库）
#   · 可选：仿真机联调 24 条（页面建流程→绑真机→建实验→执行→真回包，要仿真机+前端+API+库）
#   · 可选：页面 P0 四页（告警/策略/暂停队列/实验画布，要前端+API；见 4.8-4.11）
#   · 可选：崩溃恢复端到端 34 条（kill -9 内核进程 → 重启 → 启动兜底 settle，要库+前端+API；见 4.13）
#
# 用法：
#   ./scripts/ci-gate.sh                 # 纯逻辑 + tsc（不需要数据库）
#   ./scripts/ci-gate.sh --with-db       # 再加设备链路（需要 LABOS_DB_*）
#   ./scripts/ci-gate.sh --e2e           # 再加前端 e2e（需要 5173/18080）
#   ./scripts/ci-gate.sh --all           # 全跑
# 前端地址可用 LABOS_WEB 覆盖（默认 http://localhost:5173，nginx 8080 亦可）。
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

# 环境提示：带库段快不快，主要取决于仿真机 --rand-delay。只提示，不阻断。
if [[ $WITH_DB -eq 1 ]]; then
  SIM_RAND=$(pgrep -fl DeviceSimulator 2>/dev/null | grep -oE -- '--rand-delay [0-9]+:[0-9]+' | head -1)
  if [[ -n "$SIM_RAND" ]]; then
    SIM_MIN=$(echo "$SIM_RAND" | grep -oE '[0-9]+' | head -1)
    if (( SIM_MIN >= 5000 )); then
      printf '  \033[33m⚠ 当前仿真机 %s：设备段会明显变慢；门禁建议 ./labos-kernel/scripts/sim-up.sh LAB-DEMO-02 0 fast（2–5s），暂停段用 pause（8–15s）\033[0m\n' "$SIM_RAND"
    fi
  fi
fi

# 阈值：只许涨不许跌。改了用例要同步改这里，逼着人想清楚"少的那几条去哪了"。
MIN_PURE=673    # 2026-11-06：GraphRunnerTest 新增 __sleep / __wait 五条（sleep 真停 / wait 满足 / wait 超时转人工 / 外部刷新不盖本图写值 / __ 前缀判 LOGIC）（644 → 649）；
                # 2026-11-06 收尾：再新增「干跑内置节点直通」一条（评审③相邻缝隙）（649 → 650）；
                # 2026-11-06 演示：新增「干跑任务节点也直通」一条（TASK 分不到设备时不误报下游走不到，PF-03 假报）（650 → 651）；
                # 2026-11-07 V1-1：RuntimeHoldSelfTest 新增安全闸门三态 11 条（CLEAR 放行 / HELD 阻断 / UNKNOWN 阻断 /
                #                 覆盖判定纯函数 4 条 / 读库失败 fail-closed 3 条：siteState、coverageFor、coverageForWorkflow）（651 → 662）；
                # 2026-11-07 L-02：StatusVocabularySelfTest 新增 control scope 2 条（7b 契约固定 1 条 + JSON 元素形状 1 条）（662 → 664）；
                # 2026-11-07 S-03：新增 ExperimentStateProjectorSelfTest 9 条（源码树可枚举 2 / 单写者 0 违规 1 /
                #                 收口恰 5 条 + 常量名 + 非空 3 / 映射同源 3）（664 → 673）
MIN_CHAIN=41
MIN_PFLOW=4     # 流程内并行：整单完成 / 两分支都在 / 两分支都完成 / 同设备命令不重叠
MIN_PEXP=3      # 同岛并发：有排队 / 峰值并行=1 / 全部完成
MIN_MATRIX=55   # 完整流程矩阵（verify-flow-matrix.py --force-online）：
                # serial4/builtin4/waitsleep10/outvar2/xor6/xor3 3/loop3/loopback2/parallel3/pause10/ports4/experiment3/concurrent1
                # 前置：内核以 LABOS_TRANSFER_PLAN=1 启动（否则 ports 4 条整节 SKIP）
MIN_E2E=24        # 2026-11-03：权限收口 +Header 角色断言（23 → 24）
MIN_E2EFM=45      # 2026-11-05：页面流程矩阵（登录1/设计器装配8+并行网关1+palette收敛1/变量节点4/E 逻辑节点Tab+连线条件+流程选项9/F 接缝·设计器画异或分支图→真跑12/运行详情5/页面暂停恢复5）
MIN_EXPCTL=22     # 实验运行控制：页面暂停/恢复/停止（verify-exp-control.py）
MIN_E2ESIM=24     # 2026-11-04：仿真机联调（页面建流程+绑真机+建实验+执行+真回包，e2e-sim-flow.py）
MIN_ALERTS=8      # 2026-11-05：告警页（心跳参数 + 告警表/空态；认领/关闭弹窗是有数据时的增量断言）
MIN_POLICIES=12   # 2026-11-05：策略页（表 + 编辑弹窗 + 生效校验；需 recovery_policy 有种子数据）
MIN_PAUSED=7      # 2026-11-05：暂停队列（统计卡 + 空态/队列形态自洽；空队列时即此下限）
MIN_CANVAS=12     # 2026-11-05：实验画布（跨流程编排：仅「流程」可拖/设计器提示/reactflow/两按钮/开始结束自动铺/属性卡）
MIN_METHOD_DROP=17 # 2026-11-05：按岛设备图片 / 拖方法带图节点 / 跨设备自动插机械臂 / 删除（无库 17 条常驻，有库 20）
MIN_CRASH=34      # 2026-11-05：崩溃恢复端到端（kill -9 → 重启 → queued rehydrate + running 孤儿启动兜底 settle + paused 不被误杀 + 排队僵尸被收，31 → 34）
MIN_MULTIPAR=21   # 2026-11-05：页面实操 · 多并行网关 + 多异或网关 + 变量节点（页面拖 12 节点/13 连线 → 真跑 → 三条并行分支都 completed）
MIN_GWPAIR=34     # 2026-11-06：网关成对 · 异或/并行各 N=2+N=3（分叉与汇合必须成对；角色由页面「用建议名」推出并与入/出边数一致；并行全跑 vs 异或只走一条）
MIN_PAUSERES=45   # 2026-11-06：暂停点与恢复起点（25）。2026-11-08 重写为真边界暂停（25 → 32）；同日把图改成并行 fork/join 并改为页面全链路（页面执行/暂停/继续 + DOM/API/DB 三段断言）（32 → 45）：
                # 图增加第二个设备任务 DEV2；在 DEV 的 device_state=running 且 current_run_id=本次 run 时发暂停；
                # 内核把 pause_node_key 写成 DEV2（探针不预写），DEV completed、DEV2 pending；
                # 恢复后 DEV 不重跑、DEV2 才下发 1 条指令。网关和 __setVar 仍不是暂停点。
MIN_PAUSEEXP=5    # S-04-A：保留 M-01 三层 legacy paused 回归；另验 Hold、设备 current_run_id/动作收敛、Workflow 边界、后续节点 pending 与无关联命令；legacy paused 可早于物理收敛。阈值不变
MIN_INTR=3        # 2026-11-06：A1 真停 · 可中断方法执行中发暂停 → 命令真被中止（aborted|EC-99-0005），5 条断言（门禁 21 → 22 段）

FAIL=0
SKIP=0
GATE_START=$(date +%s)
LAST_NOTE=""
LAST_TS=0
# note：段标题 + 上一段耗时（耗时是优化门禁速度的依据，哪段慢一眼看到）。
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
# SKIP 判定单一入口：子脚本历史上有三种真实输出格式——
#   "SKIP ..."（老脚本）/ "  ⏭ ..."（F.skip）/ "缺 LABOS_DB_*"（退出码 2）
# 只按 ^SKIP 判会把后两种当成失败（4.14/4.15/4.16 缺库误报红的根因）。
is_skip() { grep -qE '^SKIP|⏭|缺 LABOS_DB|没有仿真机在监听|找不到可用空实验' <<< "$1"; }

# 解析子脚本的「通过 N / 失败 M」汇总行。
# 为什么要专门写这个：汇总行**缺失**有两种完全不同的原因 ——
#   ① 脚本里有失败用例（该报失败明细）
#   ② 脚本**没跑到最后一行**（异常退出 / 被 kill / 压根没打印汇总）
# 第 ② 种以前会被兜底成「失败 1 条」，但一条 ✗ 明细都打不出来，
# 表现为「门禁红了却看不出为什么」—— 2026-11-06 的 4.17 段就是这么查了半天
# （根因：脚本没打印汇总行，门禁解析不到 ⇒ 兜底 1 ⇒ 报「有失败」）。
# ⇒ 汇总行缺失单独判一类，并把**输出尾部**打出来。
# 用法：parse_summary "$OUT" → 读 SUM_PASS / SUM_FAIL / SUM_MISSING
parse_summary() {
  SUM_PASS=$(printf '%s' "$1" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  SUM_FAIL=$(printf '%s' "$1" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  SUM_MISSING=0
  [[ -z "$SUM_PASS" || -z "$SUM_FAIL" ]] && SUM_MISSING=1
  SUM_PASS=${SUM_PASS:-0}
  SUM_FAIL=${SUM_FAIL:-1}
}

# ---------------------------------------------------------------- 0. 阈值只许涨
# 为什么必须机器校验：阈值「只许涨」原本只是本文件顶部的一句注释 + 提交纪律。
# 纪律不是机制——把 MIN_PURE=624 改成 500，没有任何检查会拦，CI 照绿。
# 这里把历史里每个 MIN_* 出现过的最高值当基线，当前值低于它即红灯。
#
# 基线怎么取：扫 git 历史里所有版本的 ci-gate.sh，对每个 MIN_* 取最大值。
# 用「历史最高」而不是「上一版」：上一版可能正好是被下调过的那版，比它还宽松；
# 历史最高才是这条阈值曾经承诺过的最强约束。
note "0. 阈值只许涨校验（MIN_* 不得低于历史最高值）"
threshold_drops=""
while read -r key cur; do
  [[ -z "$key" || -z "$cur" ]] && continue
  hist_max=$(for c in $(git log --format=%H --all -- scripts/ci-gate.sh 2>/dev/null); do
    git show "$c:scripts/ci-gate.sh" 2>/dev/null \
      | grep -E "^${key}=" | head -1 \
      | sed -E "s/^${key}=([0-9]+).*/\1/"
  done | sort -n | tail -1)
  [[ -z "$hist_max" ]] && continue
  if (( cur < hist_max )); then
    threshold_drops="${threshold_drops}${key}(${cur} < ${hist_max}) "
  fi
done < <(grep -E "^MIN_[A-Z0-9_]+=" scripts/ci-gate.sh \
  | sed -E "s/^(MIN_[A-Z0-9_]+)=([0-9]+).*/\1 \2/")
if [[ -z "$threshold_drops" ]]; then
  pass "所有 MIN_* 阈值均不低于历史最高值"
else
  fail "阈值被下调：${threshold_drops}（只许涨：下调会掩盖用例被删）"
fi

# ---------------------------------------------------------------- 1. 纯逻辑
note "1. 纯逻辑单测（不需要数据库）"
OUT=$(./scripts/test-all.sh --pure-only 2>&1)
N=$(echo "$OUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
F=$(echo "$OUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
N=${N:-0}; F=${F:-1}
if [[ "$F" != "0" ]]; then
  fail "有失败用例（$F 条）"
  # 必须把**每个测试类的结果行**都打出来：失败可能来自"无法解析输出"
  # （run_one 的异常分支），那一行不含 ✗ 符号，只 grep ✗ 会一条都打不出来，
  # 让人以为"有失败但没有失败详情"。实际就藏在这一行里。
  echo "$OUT" | grep -E '通过 [0-9]+, 失败 [0-9]+|通过 [0-9]+ / 失败 [0-9]+|无法解析输出|FAIL|编译失败' | head -40
  echo "$OUT" | grep -E '✘|✗' | head -10
  # 失败详情（异常堆栈 / 原始输出尾部）——run_one 会把这些缩进打印出来
  echo "$OUT" | sed -n '/无法解析输出/,+4p' | head -40
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
  fail "建库产物过期（BUG-20261103-01 会复发）"
  grep '✗' /tmp/ci-schema.log | head -6
fi

# ---------------------------------------------------------------- 2.6 并行能力
#
# 放在设备链路**之前**：链路脚本跑完会把自己的 sidecar 停掉，
# 而这两个脚本要靠环境里已经在跑的 sidecar + 模拟器。
if [[ $WITH_DB -eq 1 ]]; then
  note "2.6 并行能力（流程内并行 / 同岛多实验）"

  POUT=$(python3 scripts/verify-parallel-flow.py 2>&1)
  if is_skip "$POUT"; then
    skip "流程内并行（环境里没有可用设备）"
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
  if is_skip "$EOUT"; then
    skip "同岛并发（API 不可用）"
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
  skip "并行能力（没给 --with-db）"
fi

# ---------------------------------------------------------------- 2.7 完整流程矩阵
# 覆盖：节点 / 网关 / 变量 / 循环 / 回边跨越并行 / 并行 / 端口拓扑 / 暂停恢复 / 实验 / 并发
# 前置：库 + 模拟器 + API；内核建议以 LABOS_TRANSFER_PLAN=1 启动（否则 ports 4 条 SKIP）
if [[ $WITH_DB -eq 1 ]]; then
  note "2.7 完整流程矩阵（verify-flow-matrix.py）"
  MOUT=$(python3 scripts/verify-flow-matrix.py --force-online 2>&1)
  if is_skip "$MOUT"; then
    skip "完整流程矩阵（库里没有设备实例）"
  else
    MN=$(echo "$MOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    MF=$(echo "$MOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    MN=${MN:-0}; MF=${MF:-1}
    if [[ "$MF" != "0" ]]; then
      fail "流程矩阵有失败（$MF 条）"
      echo "$MOUT" | grep '✗' | head -8
    elif (( MN < MIN_MATRIX )); then
      fail "流程矩阵断言 $MN < 阈值 ${MIN_MATRIX}（ports 需 LABOS_TRANSFER_PLAN=1）"
    else
      pass "流程矩阵 $MN / 0（覆盖矩阵见 docs/完整流程测试矩阵-设计.md）"
    fi
  fi
else
  skip "完整流程矩阵（没给 --with-db）"
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
  skip "设备全链路（没给 --with-db）"
fi

# ---------------------------------------------------------------- 4. e2e
E2E_BASE="${LABOS_WEB:-http://localhost:5173}"
if [[ $WITH_E2E -eq 1 ]]; then
  note "4. 前端 e2e（5 条主链路）"
  OUT=$(python3 scripts/e2e-smoke.py --base "$E2E_BASE" 2>&1)
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

  # 4.5 页面流程矩阵：设计器 palette/变量节点 + 运行详情着色 + 页面暂停恢复
  note "4.5 页面流程矩阵（e2e-flow-matrix.py）"
  MOUT=$(python3 scripts/e2e-flow-matrix.py --base "$E2E_BASE" 2>&1)
  if is_skip "$MOUT"; then
    skip "页面流程矩阵（API 不可用）"
  else
    MN=$(echo "$MOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    MF=$(echo "$MOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    MN=${MN:-0}; MF=${MF:-1}
    if [[ "$MF" != "0" ]]; then
      fail "页面流程矩阵有失败（$MF 条）"
      echo "$MOUT" | grep '✗' | head -8
    elif (( MN < MIN_E2EFM )); then
      fail "页面流程矩阵断言 $MN < 阈值 ${MIN_E2EFM}"
    else
      pass "页面流程矩阵 $MN / 0（设计器装配含并行网关·变量节点·逻辑节点Tab/连线条件/流程选项·接缝画异或分支图真跑·运行详情·页面暂停恢复）"
    fi
  fi

  # 4.6 实验运行控制：页面暂停（现场扣留）/恢复/停止
  note "4.6 实验运行控制（verify-exp-control.py）"
  COUT=$(python3 scripts/verify-exp-control.py --base "$E2E_BASE" 2>&1)
  if is_skip "$COUT"; then
    skip "实验运行控制（库里没有可借用的空实验）"
  else
    CN=$(echo "$COUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    CF=$(echo "$COUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    CN=${CN:-0}; CF=${CF:-1}
    if [[ "$CF" != "0" ]]; then
      fail "实验运行控制有失败（$CF 条）"
      echo "$COUT" | grep '✗' | head -8
    elif (( CN < MIN_EXPCTL )); then
      fail "实验运行控制断言 $CN < 阈值 ${MIN_EXPCTL}"
    else
      pass "实验运行控制 $CN / 0（页面暂停=现场扣留 / 恢复 / 停止）"
    fi
  fi

  # 4.7 仿真机联调：补齐"设备那一段"的端到端缺口 —— 前面几节要么只到 API、
  # 要么用占位设备；这一节是**页面建流程 → 设计器绑真机 → 页面建实验 → 页面执行
  # → 断言设备真回包**。只复用已跑起来的 DeviceSimulator（19500..19506），
  # 没起来就整体 SKIP（去 labos-kernel/scripts/sim-up.sh 起）。
  note "4.7 仿真机联调（e2e-sim-flow.py）"
  SOUT=$(python3 scripts/e2e-sim-flow.py --base "$E2E_BASE" 2>&1)
  if is_skip "$SOUT"; then
    skip "仿真机联调（仿真机没起 / API 不可用）"
  else
    SN=$(echo "$SOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    SF=$(echo "$SOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    SN=${SN:-0}; SF=${SF:-1}
    if [[ "$SF" != "0" ]]; then
      fail "仿真机联调有失败（$SF 条）"
      echo "$SOUT" | grep '✗' | head -8
    elif (( SN < MIN_E2ESIM )); then
      fail "仿真机联调断言 $SN < 阈值 ${MIN_E2ESIM}"
    else
      pass "仿真机联调 $SN / 0（页面建流程·绑真机·建实验·执行·真回包）"
    fi
  fi

  # 4.8 告警页：心跳参数 + 告警表/空态 + 认领/关闭弹窗
  # 补 CI 盲区 —— 这四页（/alerts /policies /paused /canvas）此前零页面级断言。
  note "4.8 告警页（e2e-alerts.py）"
  AOUT=$(python3 scripts/e2e-alerts.py --base "$E2E_BASE" 2>&1)
  AN=$(echo "$AOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  AF=$(echo "$AOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  AN=${AN:-0}; AF=${AF:-1}
  if [[ "$AF" != "0" ]]; then
    fail "告警页有失败（$AF 条）"
    echo "$AOUT" | grep '✗' | head -6
  elif (( AN < MIN_ALERTS )); then
    fail "告警页断言 $AN < 阈值 ${MIN_ALERTS}"
  else
    pass "告警页 $AN / 0（心跳参数·告警表/空态·认领/关闭弹窗）"
  fi

  # 4.9 策略页：表 + 编辑弹窗 + 生效校验
  note "4.9 策略页（e2e-policies.py）"
  POUT=$(python3 scripts/e2e-policies.py --base "$E2E_BASE" 2>&1)
  PN=$(echo "$POUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  PF=$(echo "$POUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  PN=${PN:-0}; PF=${PF:-1}
  if [[ "$PF" != "0" ]]; then
    fail "策略页有失败（$PF 条）"
    echo "$POUT" | grep '✗' | head -6
  elif (( PN < MIN_POLICIES )); then
    fail "策略页断言 $PN < 阈值 ${MIN_POLICIES}"
  else
    pass "策略页 $PN / 0（恢复策略表·编辑弹窗·生效校验）"
  fi

  # 4.10 暂停队列：统计卡 + 空态/队列形态自洽
  note "4.10 暂停队列（e2e-paused.py）"
  QOUT=$(python3 scripts/e2e-paused.py --base "$E2E_BASE" 2>&1)
  QN=$(echo "$QOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  QF=$(echo "$QOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  QN=${QN:-0}; QF=${QF:-1}
  if [[ "$QF" != "0" ]]; then
    fail "暂停队列有失败（$QF 条）"
    echo "$QOUT" | grep '✗' | head -6
  elif (( QN < MIN_PAUSED )); then
    fail "暂停队列断言 $QN < 阈值 ${MIN_PAUSED}"
  else
    pass "暂停队列 $QN / 0（四格统计·空态或队列·形态自洽）"
  fi

  # 4.11 实验画布：跨流程编排（仅「流程」节点/reactflow/两按钮/属性面板）
  note "4.11 实验画布（e2e-canvas.py）"
  VOUT=$(python3 scripts/e2e-canvas.py --base "$E2E_BASE" 2>&1)
  VN=$(echo "$VOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  VF=$(echo "$VOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  VN=${VN:-0}; VF=${VF:-1}
  if [[ "$VF" != "0" ]]; then
    fail "实验画布有失败（$VF 条）"
    echo "$VOUT" | grep '✗' | head -6
  elif (( VN < MIN_CANVAS )); then
    fail "实验画布断言 $VN < 阈值 ${MIN_CANVAS}"
  else
    pass "实验画布 $VN / 0（跨流程编排·仅流程节点·reactflow·返回/保存）"
  fi
  # 4.12 按岛设备 → 拖方法生成节点（设计器主路径，e2e-method-drop.py）
  note "4.12 拖方法生成节点（e2e-method-drop.py）"
  DOUT=$(python3 scripts/e2e-method-drop.py --base "$E2E_BASE" 2>&1)
  DN=$(echo "$DOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  DF=$(echo "$DOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  DN=${DN:-0}; DF=${DF:-1}
  if [[ "$DF" != "0" ]]; then
    fail "拖方法用例有失败（$DF 条）"
    echo "$DOUT" | grep '✗' | head -6
  elif (( DN < MIN_METHOD_DROP )); then
    fail "拖方法断言 $DN < 阈值 $MIN_METHOD_DROP"
  else
    pass "拖方法 $DN / 0（岛→设备→方法→XML/compile/落库）"
  fi

  # 4.13 崩溃恢复端到端：真 kill -9 内核进程 → 重启 → 断言启动兜底 settle 收掉 running 孤儿。
  # 补的是 BUG-14 的**生产入口接线**：L2 的 StartupSettleSelfTest 直接调包内重载、
  # 进程内造数，只证明方法本身对，证明不了 ApiServer 启动时真的调了 settle/rehydrate。
  # 重启继承本进程环境，故先兜底设内核能力开关，避免重启后能力与重启前不一致
  # （丢了 LABOS_TRANSFER_PLAN=1 会让 2.7 的 ports 4 条整节 SKIP）。
  note "4.13 崩溃恢复（verify-s3-2-residuals.py：kill -9 → 重启 → 启动兜底 settle）"
  export LABOS_TRANSFER_PLAN="${LABOS_TRANSFER_PLAN:-1}"
  export LABOS_EXEC_GRAPH="${LABOS_EXEC_GRAPH:-1}"
  export LABOS_DRIVER_MODE="${LABOS_DRIVER_MODE:-persistent}"
  export LABOS_AUTH="${LABOS_AUTH:-1}"
  ROUT=$(python3 scripts/verify-s3-2-residuals.py --base "$E2E_BASE" 2>&1)
  RN=$(echo "$ROUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
  RF=$(echo "$ROUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
  RN=${RN:-0}; RF=${RF:-1}
  if [[ "$RF" != "0" ]]; then
    fail "崩溃恢复有失败（$RF 条）"
    echo "$ROUT" | grep '✗' | head -8
  elif (( RN < MIN_CRASH )); then
    fail "崩溃恢复断言 $RN < 阈值 ${MIN_CRASH}"
  else
    pass "崩溃恢复 $RN / 0（kill -9 → 重启 → queued rehydrate + running 孤儿 settle）"
  fi

  # 4.14 页面实操 · 多并行网关 + 多异或网关 + 变量节点（e2e-multi-parallel.py）
  # 补的是「多个网关共存」这条接缝：F 节只画了单异或，后端矩阵的 parallel
  # 又是 build_xml() 直 POST 绕过界面 —— 页面上同时出现 2 个并行网关 +
  # 2 个异或网关 + 变量节点，从没被拖过。
  note "4.14 页面实操 · 多并行+多网关+变量（e2e-multi-parallel.py）"
  MOUT2=$(python3 scripts/e2e-multi-parallel.py --base "$E2E_BASE" 2>&1)
  if is_skip "$MOUT2"; then
    skip "多并行多网关（没给库连接）"
  else
    M2N=$(echo "$MOUT2" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    M2F=$(echo "$MOUT2" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    M2N=${M2N:-0}; M2F=${M2F:-1}
    if [[ "$M2F" != "0" ]]; then
      fail "多并行多网关有失败（$M2F 条）"
      echo "$MOUT2" | grep '✗' | head -8
    elif (( M2N < MIN_MULTIPAR )); then
      fail "多并行多网关断言 $M2N < 阈值 ${MIN_MULTIPAR}"
    else
      pass "多并行多网关 $M2N / 0（2 并行网关 + 2 异或网关 + 变量节点，页面拖→真跑）"
    fi
  fi

  # 4.15 网关「成对」与多分支（e2e-gateway-pairs.py）
  # 补的是「异或/并行分叉必须与汇合成对出现」+「N=2 与 N=3 都要测」：
  # 上一版 4.14 的图按后端口径一算是 2 个分叉 + 2 个**直通**（X/X2 入1出1），
  # 名字叫「异或分叉」却名实不符，也没有任何一个汇合点（2026-11-06 用户指出）。
  note "4.15 网关成对 · N=2/N=3（e2e-gateway-pairs.py）"
  GOUT=$(python3 scripts/e2e-gateway-pairs.py --base "$E2E_BASE" 2>&1)
  if is_skip "$GOUT"; then
    skip "网关成对（没给库连接）"
  else
    GN=$(echo "$GOUT" | grep -oE '通过 [0-9]+ /' | grep -oE '[0-9]+' | tail -1)
    GF=$(echo "$GOUT" | grep -oE '失败 [0-9]+' | grep -oE '[0-9]+' | tail -1)
    GN=${GN:-0}; GF=${GF:-1}
    if [[ "$GF" != "0" ]]; then
      fail "网关成对有失败（$GF 条）"
      echo "$GOUT" | grep '✗' | head -8
    elif (( GN < MIN_GWPAIR )); then
      fail "网关成对断言 $GN < 阈值 ${MIN_GWPAIR}"
    else
      pass "网关成对 $GN / 0（异或/并行各 N=2+N=3，分叉与汇合成对）"
    fi
  fi
  # 4.16 暂停点与恢复起点（e2e-pause-resume-nodes.py）
  # 真边界暂停：DEV 还在执行（device_state running 且 current_run_id=本次 run）时发暂停。
  # pauseChecker 在下一个 Kind.TASK（DEV2）的 executeNode 之前停下。
  # 网关不是暂停点，__setVar 也不是；__sleep/__wait 另有用例。
  # 恢复靠 skipKeys：已 completed 的 DEV 不重跑，pending 的 DEV2 才执行。
  note "4.16 暂停点与恢复起点（e2e-pause-resume-nodes.py）"
  POUT2=$(python3 scripts/e2e-pause-resume-nodes.py --base "$E2E_BASE" 2>&1)
  if is_skip "$POUT2"; then
    skip "暂停与恢复（没给库连接）"
  else
    parse_summary "$POUT2"
    if [[ "$SUM_MISSING" == "1" ]]; then
      fail "暂停与恢复脚本没有输出「通过 N / 失败 M」汇总行（脚本可能异常退出）"
      printf '%s\n' "$POUT2" | tail -15
    elif [[ "$SUM_FAIL" != "0" ]]; then
      fail "暂停与恢复有失败（$SUM_FAIL 条）"
      printf '%s\n' "$POUT2" | grep '✗' | head -8
    elif (( SUM_PASS < MIN_PAUSERES )); then
      fail "暂停与恢复断言 $SUM_PASS < 阈值 ${MIN_PAUSERES}"
    else
      pass "暂停与恢复 $SUM_PASS / 0（页面执行/暂停/继续；停在下一个设备任务 DEV2；DEV 不重跑，DEV2 恢复后才执行；PARJ 并行汇合等齐后整单才 completed）"
    fi
  fi
  # 4.17 实验级暂停：设备执行中发暂停 → 三层都停在暂停
  # 断言的是**语义要求**：
  #   设备方法正在执行时发暂停，实验必须真的进入暂停状态，
  #   experiment_run / experiment 两层一致（2026-11-06 实测发现过
  #   「experiment_run 被收尾覆盖成 completed」「experiment 表不同步」两个 bug）。
  #   M-01 兼容回归：底层 workflow 级 run 最终也必须停在 paused，后续节点 pending。
  #   S-04-A 额外验证 Hold / device_state / 命令与节点关联事实；legacy paused
  #   可早于设备动作收敛，不在本阶段断言 API/UI 隐藏 PAUSED（S-04-B 再切换）。
  note "4.17 实验级暂停 · 设备执行中（e2e-pause-during-experiment.py）"
  EOUT=$(python3 scripts/e2e-pause-during-experiment.py 2>&1)
  if is_skip "$EOUT"; then
    skip "实验级暂停（没给库连接）"
  else
    parse_summary "$EOUT"
    if [[ "$SUM_MISSING" == "1" ]]; then
      fail "实验级暂停脚本没有输出「通过 N / 失败 M」汇总行（脚本可能异常退出）"
      printf '%s\n' "$EOUT" | tail -15
    elif [[ "$SUM_FAIL" != "0" ]]; then
      fail "实验级暂停有失败（$SUM_FAIL 条）"
      printf '%s\n' "$EOUT" | grep '✗' | head -8
    elif (( SUM_PASS < MIN_PAUSEEXP )); then
      fail "实验级暂停断言 $SUM_PASS < 阈值 ${MIN_PAUSEEXP}"
    else
      pass "实验级暂停 $SUM_PASS / 0（M-01 三层 legacy paused + Hold/设备收敛/节点边界/后续节点与命令证据）"
    fi
  fi
  # 4.18 A1 真停：方法声明「可中断」时，执行中发暂停 → 命令真的被中止
  # 断言的是**语义要求**：可中断方法在暂停后必须**很快**落终态
  # （远快于设备本身要跑的 30–300 秒），而不是等设备跑完。
  # 脚本会临时把机械臂的 Home 标成 interruptible=true，结束前还原。
  note "4.18 A1 真停 · 可中断方法执行中（e2e-interrupt-during-device.py）"
  IOUT=$(python3 scripts/e2e-interrupt-during-device.py 2>&1)
  if is_skip "$IOUT"; then
    skip "A1 真停（没给库连接）"
  else
    parse_summary "$IOUT"
    if [[ "$SUM_MISSING" == "1" ]]; then
      fail "A1 真停脚本没有输出「通过 N / 失败 M」汇总行（脚本可能异常退出）"
      printf '%s\n' "$IOUT" | tail -15
    elif [[ "$SUM_FAIL" != "0" ]]; then
      fail "A1 真停有失败（$SUM_FAIL 条）"
      printf '%s\n' "$IOUT" | grep '✗' | head -8
    elif (( SUM_PASS < MIN_INTR )); then
      fail "A1 真停断言 $SUM_PASS < 阈值 ${MIN_INTR}"
    else
      pass "A1 真停 $SUM_PASS / 0（可中断方法执行中发暂停 → 命令在 10 秒内被中止）"
    fi
  fi
else
  skip "前端 e2e / 4.5-4.18 页面轨（没给 --e2e）"
fi

# ---------------------------------------------------------------- 结果
note "结果"
printf '  总用时 %ss\n' "$(( $(date +%s) - GATE_START ))"
if [[ $FAIL -eq 0 ]]; then
  if [[ $SKIP -gt 0 ]]; then
    # 关键：跳过不等于通过。缺环境时也要让「哪些没跑」显式可见，
    # 否则「门禁通过」会被误读成「全绿」（2026-11-05 评审 P1-2）。
    printf '  \033[32m门禁通过\033[0m（但有 %d 段跳过——这些没跑，不算通过）\n' "$SKIP"
    exit 0
  fi
  printf '  \033[32m门禁通过\033[0m（无跳过）\n'
  exit 0
fi
printf '  \033[31m门禁失败：%d 项\033[0m' "$FAIL"
if [[ $SKIP -gt 0 ]]; then
  printf '（另有 %d 段跳过）' "$SKIP"
fi
printf '\n'
exit 1
