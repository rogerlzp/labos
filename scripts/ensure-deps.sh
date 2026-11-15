#!/usr/bin/env bash
# ============================================================================
# 依赖就位：Spring / MyBatis / Redis 客户端等 jar 落到 labos-kernel/lib/spring/
#
#   ./scripts/ensure-deps.sh
#
# 为什么单独成脚本（2026-11-15）：
#   **编 boot 需要 Spring**，所以依赖是「编译」的前置条件，不是「启动内核」
#   才需要的东西。原先这段逻辑埋在 build-kernel.sh 里 ⇒ 想编 boot 的其它入口
#   （test-all.sh / CI workflow）没有依赖可拿，只能编 src/main ——
#   而 src/test 里有 9 个类直接引用 com.labos.boot.*，于是「程序包不存在」。
#
# 依赖落在 labos-kernel/lib/spring/（已 gitignore），不把二进制提交进仓库。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/labos-kernel"

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

# ---- ② 关键 jar 齐了才跳过拉取 ----
# 为什么看多个 jar 而不是单个标记：加新依赖（如 2026-11-10 的 lettuce-core）时，
# 已有 lib/spring/ 的本地环境会因为「老标记还在」而跳过下载 ⇒ 编译报
# 「程序包io.lettuce.core不存在」。加依赖时在这里补一个判断即可。
# CI 是全新检出，没有 lib/spring/，一律全量拉。
REQUIRED_JARS=(
  "spring-jdbc-6.1.6.jar"
  "lettuce-core-6.3.2.RELEASE.jar"
)

missing=0
for j in "${REQUIRED_JARS[@]}"; do
  [[ -f "lib/spring/$j" ]] || missing=1
done

if [[ $missing -eq 1 ]]; then
  MVN="$(find_mvn)"
  "$MVN" -q -B dependency:copy-dependencies \
      -DincludeScope=runtime -DoutputDirectory=lib/spring
fi

mkdir -p lib/spring

# PG 驱动统一用仓库里已入库的那份（lib/postgresql-42.7.3.jar）：
# Spring Boot 管理的版本与仓库现用的 42.7.3 不同，两份同时在 classpath 上会打架
# （classpath 里 `lib/spring/*` 与 `lib/postgresql-*.jar` 是两处）。
# 原先这条清理只在「跑了 mvn」时执行 ⇒ 本机老 lib/spring/ 里可能还留着
# 另一份 PG 驱动，长期潜伏。
rm -f lib/spring/postgresql-*.jar

echo "✓ 依赖就位（lib/spring $(find lib/spring -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ') 个 jar）"
