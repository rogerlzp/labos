#!/usr/bin/env bash
# ============================================================================
# CI 门禁（M8）
#
# 解决的问题：以前"改完有没有坏"靠人点。现在把可自动化的部分变成**硬阈值**：
#   · 纯逻辑单测必须 ≥ 624 且 0 失败（数字下降 = 有人删了用例，也算红灯）
#   · 前端 tsc 必须 0 错误
#   · 可选：并行能力（流程内并行 4 条 / 同岛并发 3 条，要库 + 模拟器 + API）
#   · 可选：完整流程矩阵 45 条（节点/网关/变量/循环/回边/并行/端口/暂停，要库 + 模拟器 + API）
#   · 可选：设备链路 41 条（要库 + 模拟器）
#   · 可选：前端 e2e 24 条（要前端/API 在跑）
#   · 可选：页面流程矩阵 21 条（设计器 palette/变量节点/运行详情/页面暂停恢复，要前端+API+库）
#   · 可选：实验运行控制 22 条（页面暂停/恢复/停止，要前端+API+库）
#   · 可选：仿真机联调 24 条（页面建流程→绑真机→建实验→执行→真回包，要仿真机+前端+API+库）
#   · 可选：页面 P0 四页（告警/策略/暂停队列/实验画布，要前端+API；见 4.8-4.11）
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

# 阈值：只许涨不许跌。改了用例要同步改这里，逼着人想清楚"少的那几条去哪了"。
MIN_PURE=624    # 2026-11-05：ManualWindowSelfTest 35（台账 6.5 有人值守窗口纯逻辑层）（589 → 624）
MIN_CHAIN=41
MIN_PFLOW=4     # 流程内并行：整单完成 / 两分支都在 / 两分支都完成 / 同设备命令不重叠
MIN_PEXP=3      # 同岛并发：有排队 / 峰值并行=1 / 全部完成
MIN_MATRIX=45   # 完整流程矩阵（verify-flow-matrix.py --force-online）：
                # serial4/builtin4/outvar2/xor6/xor3 3/loop3/loopback2/parallel3/pause10/ports4/experiment3/concurrent1
                # 前置：内核以 LABOS_TRANSFER_PLAN=1 启动（否则 ports 4 条整节 SKIP）
MIN_E2E=24        # 2026-11-03：权限收口 +Header 角色断言（23 → 24）
MIN_E2EFM=21      # 2026-11-04：页面流程矩阵（登录1/设计器装配6/变量节点4/运行详情5/页面暂停恢复5）
MIN_EXPCTL=22     # 实验运行控制：页面暂停/恢复/停止（verify-exp-control.py）
MIN_E2ESIM=24     # 2026-11-04：仿真机联调（页面建流程+绑真机+建实验+执行+真回包，e2e-sim-flow.py）
MIN_ALERTS=8      # 2026-11-05：告警页（心跳参数 + 告警表/空态；认领/关闭弹窗是有数据时的增量断言）
MIN_POLICIES=12   # 2026-11-05：策略页（表 + 编辑弹窗 + 生效校验；需 recovery_policy 有种子数据）
MIN_PAUSED=7      # 2026-11-05：暂停队列（统计卡 + 空态/队列形态自洽；空队列时即此下限）
MIN_CANVAS=12     # 2026-11-05：实验画布（岛屿/六类节点/reactflow/三按钮/属性面板）
MIN_METHOD_DROP=17 # 2026-11-05：按岛设备图片 / 拖方法带图节点 / 跨设备自动插机械臂 / 删除（无库 17 条常驻，有库 20）

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

# ---------------------------------------------------------------- 2.7 完整流程矩阵
# 覆盖：节点 / 网关 / 变量 / 循环 / 回边跨越并行 / 并行 / 端口拓扑 / 暂停恢复 / 实验 / 并发
# 前置：库 + 模拟器 + API；内核建议以 LABOS_TRANSFER_PLAN=1 启动（否则 ports 4 条 SKIP）
if [[ $WITH_DB -eq 1 ]]; then
  note "2.7 完整流程矩阵（verify-flow-matrix.py）"
  MOUT=$(python3 scripts/verify-flow-matrix.py --force-online 2>&1)
  if echo "$MOUT" | grep -q '^SKIP'; then
    echo "  跳过（库里没有设备实例）"
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
  note "2.7 完整流程矩阵：跳过（没给 --with-db）"
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
  if echo "$MOUT" | grep -q '^SKIP'; then
    echo "  跳过（API 不可用）"
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
      pass "页面流程矩阵 $MN / 0（设计器装配·变量节点·运行详情·页面暂停恢复）"
    fi
  fi

  # 4.6 实验运行控制：页面暂停（现场扣留）/恢复/停止
  note "4.6 实验运行控制（verify-exp-control.py）"
  COUT=$(python3 scripts/verify-exp-control.py --base "$E2E_BASE" 2>&1)
  if echo "$COUT" | grep -qE '找不到可用空实验'; then
    echo "  跳过（库里没有可借用的空实验）"
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
  if echo "$SOUT" | grep -qE '^SKIP|没有仿真机在监听'; then
    echo "  跳过（仿真机没起 / API 不可用）"
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

  # 4.11 实验画布：岛屿/六类节点/reactflow/三按钮/属性面板
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
    pass "实验画布 $VN / 0（岛屿·六类节点·reactflow·按箭头执行/保存/返回）"
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
else
  note "4. 前端 e2e / 4.5 页面流程矩阵 / 4.6 实验运行控制 / 4.7 仿真机联调 / 4.8-4.12 页面轨：跳过（没给 --e2e）"
fi

# ---------------------------------------------------------------- 结果
note "结果"
if [[ $FAIL -eq 0 ]]; then
  printf '  \033[32m门禁通过\033[0m\n'
  exit 0
fi
printf '  \033[31m门禁失败：%d 项\033[0m\n' "$FAIL"
exit 1
