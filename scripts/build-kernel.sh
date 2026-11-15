#!/usr/bin/env bash
# ============================================================================
# 构建内核（Spring Boot 容器 + Spring MVC HTTP 层）
#
#   ./scripts/build-kernel.sh            # 编译 main + boot + 同步资源
#   ./scripts/build-kernel.sh --verify   # 编译并跑 Spring 事务桥接自测
#
# 真正的编译在 **scripts/compile-kernel.sh（单一入口）**：
#   依赖就位 → 编 main + boot → mapper XML 合法性自检 → 资源同步。
#   test-all.sh 与三个 CI workflow 都调同一个入口，不再各编各的
#   （2026-11-15 修：test-all.sh 只编 src/main，靠 out/ 里残留的 boot class
#    撑着；CI 里则连 boot 都没编，`com.labos.boot.LabosApplication` 起不来）。
#
# 为什么不用 `mvn package` 一把梭：现有门禁/e2e 全部按 `out/` 目录 + 裸 javac
# 的方式工作（见 test-all.sh / verify-s3-2-residuals.py），改成 Maven 生命周期
# 会同时改掉启动脚本、崩溃恢复段的重启命令、以及所有自测的 classpath。
# 这里 Maven 只负责**拉依赖**，编译仍沿用既有 javac 流程，产物照样落 out/。
#
# 依赖落在 labos-kernel/lib/spring/（已 gitignore），不把二进制提交进仓库。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/labos-kernel"

bash "$ROOT/scripts/compile-kernel.sh"

if [[ "${1:-}" == "--verify" ]]; then
  if [[ -z "${LABOS_DB_HOST:-}" ]]; then
    # 无库环境（CI / 没导出凭证的 push）：显式 SKIP，不静默当通过、也不挡推送。
    echo "SKIP Spring 事务自测：未设置 LABOS_DB_HOST（需要连库的那一档请设好后重跑 --verify）"
  else
    java -Dlabos.kernel.http.enabled=false \
         -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
         com.labos.boot.SpringTxSelfTest
  fi
fi
