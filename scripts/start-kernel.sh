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

exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
     com.labos.boot.LabosApplication "$@"
