#!/usr/bin/env bash
# ============================================================================
# 用 Spring Boot 容器启动内核（正式入口）
#
#   ./scripts/start-kernel-spring.sh                 # 前台
#   nohup ./scripts/start-kernel-spring.sh >> log 2>&1 &   # 后台
#
# 启动时 Spring 会：
#   ① 建 HikariDataSource（LABOS_DB_* 环境变量）
#   ② 建 DataSourceTransactionManager / TransactionTemplate
#   ③ 把 DataSource 绑定到 DbConfig，让全仓 396 处 db.open() 进入 Spring 事务
#   ④ 由 KernelRunner 在独立线程拉起原有 ApiServer（18080 + 全部端点）
#
# 旧的 `java -cp out:lib/postgresql-42.7.3.jar ...ApiServer 18080` 仍然可用
# （无 Spring 时自动回落原有连接池），用于门禁 4.13 的 kill -9 重启等场景。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/labos-kernel"

bash "$ROOT/scripts/build-kernel.sh" >&2

exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
     com.labos.boot.LabosApplication "$@"
