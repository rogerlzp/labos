#!/usr/bin/env bash
# ============================================================================
# 用 Spring Boot 容器启动内核（**唯一**入口，A 批后旧方式已删除）
#
#   ./scripts/start-kernel.sh                 # 前台
#   nohup ./scripts/start-kernel.sh >> log 2>&1 &   # 后台
#
# 启动时 Spring 会：
#   ① 建 HikariDataSource（LABOS_DB_* 环境变量）
#   ② 建 DataSourceTransactionManager / TransactionTemplate
#   ③ 把 DataSource 绑定到 DbConfig，让全仓 396 处 db.open() 进入 Spring 事务
#   ④ 由 Spring MVC 的 12 个 Controller 承接全部端点（Tomcat 监听 18080），
#      再由 KernelRunner 在独立线程执行启动收尾（崩溃孤儿 settle / 命令账本对账 / queued rehydrate）
#
# A 批：`java -cp out ... ApiServer 18080` 已删除（ApiServer.main 不再存在），
# 门禁 4.13 的 kill -9 重启也走本脚本。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/labos-kernel"

bash "$ROOT/scripts/build-kernel.sh" >&2

# 轨道分区锁等待上限（RailZoneGuard）：默认 60s，在「多个 run 并行」时不够。
# 实测（2026-11-17）：Transfer 均值 9.33s ⇒ 单臂吞吐 ≈6.4 次/分钟，而单 run 的搬运频率
# 就有 3.5~4 次/分钟 ⇒ 2 个 run 并行已到容量边缘、4 个必排队；run 1001411 的 Transfer
# 等满 62.7s 后 EC-RESOURCE-BUSY。⇒ 默认抬到 120s（同名环境变量可覆盖，0 表示不等待）。
export LABOS_RAIL_WAIT_TIMEOUT_MS="${LABOS_RAIL_WAIT_TIMEOUT_MS:-120000}"

# ⚠ **搬运保护的三个开关必须默认打开**（2026-11-18 实测踩坑）。
#
#   DriverStepExecutor.executeWithPlan 的守卫是
#     `System.getenv("LABOS_TRANSFER_PLAN") != null`（**非 null 即开**，不看值）。
#   没开 ⇒ 搬运**静默降级**：不抢位置锁、不建 transfer_transaction、不做轨道分区互锁、
#   不走扫码证据确认 ⇒ 板位账本不落账、两个 run 会选到同一格物理相撞。
#   现场表现：搬运节点全部 completed，但 transfer_transaction **零行**、板位账实不符。
#   （2026-11-18 实测：run 1001777/1001779/1001781 三次 completed、0 条事务；
#     同一内核开着开关时 run 1001790/1001791/1001792 三段各 3/4/4 条事务全部 CONFIRMED。）
#
#   口径与 scripts/ci-gate.sh 第 52-54 行保持一致：**门禁跑得通 ≠ 内核跑得通**，
#   两者必须用同一套开关，否则门禁绿灯掩盖了生产内核的降级。
#
#   ⚠ **三个开关的「怎么关」语义不一样**（内核读法不同，别想当然）：
#     LABOS_AUTH          ApiServer.java:102          `!"0".equals(getenv())`  ⇒ =0 才算关
#     LABOS_EXEC_GRAPH    WorkflowService.java:1207  `!"0".equals(getenv())`  ⇒ =0 才算关
#     LABOS_TRANSFER_PLAN DriverStepExecutor.java:354 `getenv() != null`      ⇒ **非 null 即开**
#     ⇒ 对 LABOS_TRANSFER_PLAN 来说，`=0` 和 `=`（空串）都是**开**！
#     ⇒ `env -u LABOS_TRANSFER_PLAN` 也没用：本脚本的默认值会立刻把它设回 1。
#     ⇒ 所以本脚本只认 off/none/false/no 这几个**显式 token**，命中就真的 unset。
#
#   关闭搬运保护：`LABOS_TRANSFER_PLAN=off ./scripts/start-kernel.sh`
export LABOS_EXEC_GRAPH="${LABOS_EXEC_GRAPH:-1}"
export LABOS_AUTH="${LABOS_AUTH:-1}"
# 「关」用显式 token：大小写不敏感 + 前后空格忽略（OFF / Off / " off " 都算关）
_tp="${LABOS_TRANSFER_PLAN:-}"
_tp="${_tp#"${_tp%%[![:space:]]*}"}"
_tp="${_tp%"${_tp##*[![:space:]]}"}"
_tp="$(printf '%s' "$_tp" | tr '[:upper:]' '[:lower:]')"
case "$_tp" in
  off|none|false|no) unset LABOS_TRANSFER_PLAN ;;
  *)                  export LABOS_TRANSFER_PLAN="${LABOS_TRANSFER_PLAN:-1}" ;;
esac
unset _tp
# 生效值回显：现场/门禁靠这一行判断内核到底带没带搬运保护（不依赖另一个 ps eww）
echo "[start-kernel] LABOS_TRANSFER_PLAN=${LABOS_TRANSFER_PLAN:-<未设置=搬运保护关闭>} LABOS_EXEC_GRAPH=${LABOS_EXEC_GRAPH} LABOS_AUTH=${LABOS_AUTH}" >&2

exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
     com.labos.boot.LabosApplication "$@"
