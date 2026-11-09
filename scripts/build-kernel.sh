#!/usr/bin/env bash
# ============================================================================
# 构建内核（Spring Boot 容器 + 现有 JDK HttpServer 路由）
#
#   ./scripts/build-kernel.sh            # 编译 main + boot
#   ./scripts/build-kernel.sh --verify   # 编译并跑 Spring 事务桥接自测
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
KERNEL="$ROOT/labos-kernel"
cd "$KERNEL"

# ---- ① 准备 Maven（本机没有就下载到 ~/.cache，不污染仓库）----
find_mvn() {
  if command -v mvn >/dev/null 2>&1; then command -v mvn; return; fi
  local cache="$HOME/.cache/labos-maven/apache-maven-3.9.9/bin/mvn"
  if [[ -x "$cache" ]]; then echo "$cache"; return; fi
  echo "本机没有 Maven，下载 3.9.9 到 ~/.cache/labos-maven ..." >&2
  mkdir -p "$HOME/.cache/labos-maven"
  curl -sS -m 180 -o /tmp/labos-apache-maven.tar.gz \
    https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz
  tar -xzf /tmp/labos-apache-maven.tar.gz -C "$HOME/.cache/labos-maven"
  echo "$cache"
}

if [[ ! -f lib/spring/spring-jdbc-6.1.6.jar ]]; then
  MVN="$(find_mvn)"
  "$MVN" -q -B dependency:copy-dependencies \
      -DincludeScope=runtime -DoutputDirectory=lib/spring
  # Spring Boot 管理的 pg 驱动版本与仓库现用的 42.7.3 不同，统一用现用的
  rm -f lib/spring/postgresql-*.jar
  echo "依赖就位：$(ls -1 lib/spring | wc -l | tr -d ' ') 个 jar"
fi

# ---- ② 编译既有源码（classpath 不含 spring：DbConfig 用反射桥接）----
javac -encoding UTF-8 -parameters -d out -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
      $(find src/main -name '*.java')
echo "✓ 既有源码编译完成（DbConfig 直接使用 Spring JDBC）"

# ---- ②b 清理已删除源文件的 stale class（javac 不删旧产物，删类后残留会继续生效）
rm -rf out/com/labos/boot
rm -f out/com/labos/kernel/api/LegacyRouteController.class \
      out/com/labos/kernel/api/ServletHttpExchange.class

# ---- ③ 资源（logback / application.properties / mapper XML）----
mkdir -p out/mapper
cp -f src/boot/resources/* out/ 2>/dev/null || true
cp -f src/boot/resources/mapper/*.xml out/mapper/ 2>/dev/null || true

# ---- ③ 编译 Spring Boot 容器层 ----
javac -encoding UTF-8 -parameters -d out -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
      $(find src/boot/java -name '*.java')
echo "✓ Spring Boot 容器层编译完成"

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
