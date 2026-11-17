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

exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
     com.labos.boot.LabosApplication "$@"
