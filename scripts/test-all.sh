#!/usr/bin/env bash
# ============================================================================
# 单测总入口
#
# 背景（评审 P1-33）：原来 106 个测试**全部直接连真实数据库**，
# 没有库就跑不了，CI 上等于没有测试。
#
# 实际情况：106 个里 **93 个是纯逻辑**（表达式/等待图/告警/图执行/恢复策略），
# 只有 **13 个**真的需要库（位置锁、暂停恢复、配方编译）。
#
# 所以不搞 mock JDBC / H2（本项目用 PG 方言：::类型 cast、jsonb、数组、
# 分区表，H2 兼容性差，全 mock 成本远大于收益），而是：
#
#   1. 纯逻辑测试：**任何时候都跑**，不需要库
#   2. 连库测试：**探测到库就跑，探不到就明确跳过**（不是静默通过）
#
# 用法：
#   ./scripts/test-all.sh              # 自动探测
#   ./scripts/test-all.sh --pure-only  # 只跑纯逻辑（CI 无库场景）
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
cd labos-kernel || exit 1

CP="out:lib/postgresql-42.7.3.jar"

# 干净检出（没有 out/）时先编主代码，否则 src/test 引用不到主类。
# 原来只编 src/test，`git clone` 后直接跑会报"找不到符号"。
if [[ ! -d out ]] || [[ -z "$(ls -A out 2>/dev/null)" ]]; then
  echo "（首次运行：先编译 src/main）"
  if ! javac -encoding UTF-8 -d out -cp "$CP" $(find src/main -name '*.java') 2>/tmp/main-compile.err; then
    echo "主代码编译失败："
    head -20 /tmp/main-compile.err
    exit 1
  fi
fi

# 再把 src/test 的用例编进 out —— 否则新加的测试类"找不到主类"，
# 而脚本只会把它算成一次失败，看不出是"没编译"还是"真挂了"。
if ! javac -encoding UTF-8 -d out -cp "$CP" $(find src/test -name '*.java') 2>/tmp/test-compile.err; then
  echo "测试源码编译失败："
  head -20 /tmp/test-compile.err
  exit 1
fi
PASS=0
FAIL=0
SKIP=0

PURE_ONLY=0
[[ "${1:-}" == "--pure-only" ]] && PURE_ONLY=1

# --pure-only 必须**真的纯**：把连库变量清掉。
# 有些自检里带"设了 LABOS_DB_* 才跑"的分支（如 DeviceErrorChainSelfTest 有 5 条真库断言），
# 不清的话同一台机器有没有这个变量会跑出 368 / 373 两个数 ——
# 文档里写哪个都不对，第三方按文档执行第一时间就会以为"少了 5 条，疑似回归"。
if [[ $PURE_ONLY -eq 1 ]]; then
  unset LABOS_DB_HOST LABOS_DB_NAME LABOS_DB_USER LABOS_DB_PASS
fi

# 纯逻辑：不碰数据库
PURE=(
  "api.ExperimentGraphCompileSelfTest"
  # 实验编排偏序求序（S2-A 迁移边界：无边=seq 序，有边=拓扑序，环要拒绝）
  "workflow.ExperimentOrderSelfTest"
  # 作用域扣留覆盖优先级 + 边界闸门（暂停是现场事件：experiment>island>site）
  "workflow.RuntimeHoldSelfTest"
  # 实验级运行控制：作用域退化（无岛退实验级）+ site 扣留不可顺手解除
  "workflow.RunControlSelfTest"
  # S3 分批执行纯逻辑：96/24=4、余数成批、空/NULL 集合、D5 批变量、D6 状态聚合
  "workflow.BatchPlannerSelfTest"
  "workflow.SchedulerEtaSelfTest"
  # P2 状态映射收口：五 scope 词表契约 / terminal-active 集合 / run→experiment 映射
  # / syncExperimentStatus 生成的 SQL CASE 逐字钉死 + 与改前行为逐状态等价
  "workflow.StatusVocabularySelfTest"
  "expr.ExpressionSelfTest"
  "driver.WaitForGraphSelfTest"
  "alert.AlertSelfTest"
  "engine.EngineSelfTest"
  # 条件表达式求值：比较/逻辑/变量/拒绝注入
  "engine.ConditionEvaluatorSelfTest"
  # 时间约束（L1-1.6，P0）：最短补等 / 最长超限 EC-11-0001 / 前驱未跑不检查 / 补等上限守卫
  "engine.TimeConstraintSelfTest"
  "workflow.GraphRunnerTest"
  # 调度队列排序收口（规格 §14.6）：EDD → priority DESC → FIFO，直接排 QUEUE_ORDER
  "workflow.SchedulerOrderSelfTest"
  "recovery.RecoverySelfTest"
  # 设备型号库 / 错误码映射（起模拟器，但不连库）
  "device.DeviceModelSelfTest"
  # M10 型号库元数据：manual/planned 口径、生产库 model 别名、libraryJson 契约
  "device.DeviceModelLibrarySelfTest"
  # 权限收口：角色白名单 / 写权限矩阵 / 最后管理员保护判定（纯逻辑）
  "api.RbacRulesSelfTest"
  # JSON 取值转义解码：Unicode 转义（\uXXXX）解成中文 / 脏数据不崩（纯逻辑）
  "api.JsonFieldSelfTest"
  "sim.SimulatorBehaviorSelfTest"
  "driver.DeviceErrorChainSelfTest"
)

# 连库：位置锁 / 暂停恢复 / 配方编译
DB=(
  "api.ExperimentGraphPersistenceSelfTest"
  "driver.TransferPlanSelfTest"
  "workflow.PauseManagerSelfTest"
  # 作用域扣留落库（幂等 / 同 scope 唯一 / 解除）
  "workflow.RuntimeHoldDbSelfTest"
  # 实验级暂停/停止/恢复落库（覆盖范围 / 停止双分支 / 断点续跑基线）
  "workflow.RunControlDbSelfTest"
  # S3 分批执行落库：建批幂等/快照、失败批重跑 attempt_no=2、成功批拒重跑、
  # NULL 回归、状态聚合、批边界扣留（用假执行体，不真动设备）
  "workflow.BatchDbSelfTest"
  # BUG-14 启动兜底 settle：孤儿 running 落 failed + 批次/实验回写；不碰合法数据
  "workflow.StartupSettleSelfTest"
  # S3.2-A 重启 rehydrate：queued run 装回调度队列 / running+paused 不枚举 / 幂等
  "workflow.SchedulerRehydrateSelfTest"
  # 权限收口落库：建/改用户的角色白名单 + 最后管理员保护按剩余数判定（自建自清）
  "api.UserRoleDbSelfTest"
)

run_one() {
  local t="$1"
  local out
  out=$(java -cp "$CP" "com.labos.kernel.$t" 2>&1)
  local line
  line=$(echo "$out" | grep -oE "通过 [0-9]+, 失败 [0-9]+" | tail -1)
  if [[ -z "$line" ]]; then
    printf "  %-28s 无法解析输出\n" "${t##*.}"
    FAIL=$((FAIL + 1))
    # 点名是哪个类出的问题：CI 里这行是唯一的线索（外层脚本会吞掉细节）
    echo "      ↓ 失败详情：${t}"
    echo "$out" | tail -8 | sed 's/^/      /'
    return
  fi
  local p f
  p=$(echo "$line" | grep -oE "通过 [0-9]+" | grep -oE "[0-9]+")
  f=$(echo "$line" | grep -oE "失败 [0-9]+" | grep -oE "[0-9]+")
  PASS=$((PASS + p))
  FAIL=$((FAIL + f))
  printf "  %-28s %s\n" "${t##*.}" "$line"
}

echo "════════ 纯逻辑测试（不需要数据库）════════"
for t in "${PURE[@]}"; do
  run_one "$t"
done

# 中控 → sidecar → 模拟器 全链路（没有 dotnet 或 SDK 版本不够就跳过，不影响其它结论）
# 注意：本脚本已 cd 到 labos-kernel，路径要回到仓库根
if command -v dotnet >/dev/null 2>&1 && [[ -x ../scripts/test-sidecar.sh ]]; then
  if out=$(../scripts/test-sidecar.sh 2>&1); then
    line=$(echo "$out" | grep -E '^通过 [0-9]+ / 失败' | tail -1)
    if [[ -z "$line" ]]; then
      # 脚本退出码 0 但没有通过统计 = 它跳过了（环境不满足），不能算成通过，
      # 也不能虚增用例数 —— 否则阈值 589 会因为"跳过"而被误判成"删了用例"。
      SKIP=$((SKIP + 1))
      printf "  %-28s %s\n" "SidecarChainTest" "$(echo "$out" | grep -E '跳过' | tail -1)"
    else
      p=$(echo "$line" | grep -oE '通过 [0-9]+' | grep -oE '[0-9]+')
      PASS=$((PASS + p))
      printf "  %-28s %s\n" "SidecarChainTest" "$line"
    fi
  else
    FAIL=$((FAIL + 1))
    printf "  %-28s %s\n" "SidecarChainTest" "FAIL（单独跑 scripts/test-sidecar.sh 看详情）"
    echo "$out" | tail -8 | sed 's/^/      /'
  fi
else
  SKIP=$((SKIP + 1))
  printf "  %-28s %s\n" "SidecarChainTest" "跳过（没有 dotnet）"
fi

DB_HOST="${LABOS_DB_HOST:-}"
DB_PORT="${LABOS_DB_PORT:-5432}"

db_reachable() {
  [[ -n "$DB_HOST" ]] && (echo > "/dev/tcp/${DB_HOST}/${DB_PORT}") >/dev/null 2>&1
}

echo
if [[ $PURE_ONLY -eq 1 ]]; then
  echo "════════ 连库测试：--pure-only，跳过 ════════"
  SKIP=${#DB[@]}
else
  if [[ -z "$DB_HOST" ]]; then
    echo "════════ 连库测试：未设置 LABOS_DB_HOST，跳过 ════════"
    echo "  （连库验证必须显式指定目标库，避免误连默认本地 127.0.0.1）"
    SKIP=${#DB[@]}
  elif db_reachable; then
    echo "════════ 连库测试（${DB_HOST}:${DB_PORT} 可达）════════"
    for t in "${DB[@]}"; do
      run_one "$t"
    done
  else
    echo "════════ 连库测试：数据库不可达，跳过 ════════"
    echo "  （连不上 ${DB_HOST}:${DB_PORT}。设 LABOS_DB_HOST 后重跑；"
    echo "   纯逻辑部分的结论不受影响）"
    SKIP=${#DB[@]}
  fi
fi

echo
echo "──────────────────────────────────────────"
printf "通过 %d / 失败 %d" "$PASS" "$FAIL"
[[ $SKIP -gt 0 ]] && printf "（跳过 %d 个连库测试）" "$SKIP"
echo
echo "──────────────────────────────────────────"

[[ $FAIL -eq 0 ]] && exit 0 || exit 1
