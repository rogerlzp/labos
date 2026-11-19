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
MAVEN_VERSION="3.9.9"
MAVEN_HOME="$HOME/.cache/labos-maven/apache-maven-${MAVEN_VERSION}"
# 允许覆盖下载地址（国内网络到 repo.maven.apache.org 实测只有 ~5KB/s，
# 9.1MB 要半个多小时）：LABOS_MAVEN_URL=https://<你的镜像>/apache-maven-3.9.9-bin.tar.gz
MAVEN_URL="${LABOS_MAVEN_URL:-https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz}"

# 缓存里的 Maven 要**能真跑**才算数。
# 为什么不能只看「文件在不在、可不可执行」：2026-11-15 实测过 ——
# curl 超时把压缩包截断 ⇒ tar 解压出**半个 Maven**（bin/ 在、lib/ 缺）
#   ⇒ 后面报的是 `NoClassDefFoundError: org/apache/maven/BuildAbort`，
#   与"下载失败"八竿子打不着；更糟的是这份坏缓存会被**每次构建都复用**。
maven_works() {
  [[ -x "$MAVEN_HOME/bin/mvn" ]] || return 1
  "$MAVEN_HOME/bin/mvn" -v >/dev/null 2>&1
}

find_mvn() {
  if command -v mvn >/dev/null 2>&1 && mvn -v >/dev/null 2>&1; then
    command -v mvn; return
  fi
  if maven_works; then echo "$MAVEN_HOME/bin/mvn"; return; fi

  # 坏缓存先清掉，别让它一直污染后续每一次构建
  rm -rf "$MAVEN_HOME"
  echo "本机没有可用的 Maven，下载 ${MAVEN_VERSION} 到 ~/.cache/labos-maven ..." >&2
  mkdir -p "$HOME/.cache/labos-maven"

  local tmpdir tgz
  tmpdir="$(mktemp -d)"
  tgz="$tmpdir/maven.tar.gz"

  # 下载要**加长超时 + 重试 + 完整性校验**：
  #   原写法 `curl -m 180` 在慢网下会把压缩包截断（实测 1.1MB / 应为 9.1MB），
  #   而 `tar -xzf` 对截断包**不报错**（解一半就结束）⇒ 坏 Maven 静默落地。
  if ! curl -sSL --retry 3 --retry-delay 2 -m 900 -o "$tgz" "$MAVEN_URL"; then
    echo "✗ Maven 下载失败（网络不通或超时）：$MAVEN_URL" >&2
    rm -rf "$tmpdir"; exit 1
  fi
  if ! tar -tzf "$tgz" >/dev/null 2>&1; then
    echo "✗ Maven 压缩包不完整（下载被截断，本地 $(wc -c < "$tgz" | tr -d ' ') 字节）：$MAVEN_URL" >&2
    echo "  重跑本脚本会重新下载。" >&2
    rm -rf "$tmpdir"; exit 1
  fi

  # 解到临时目录再整体搬过去 ⇒ 不会出现"搬一半"的半成品缓存
  tar -xzf "$tgz" -C "$tmpdir"
  mv "$tmpdir/apache-maven-${MAVEN_VERSION}" "$MAVEN_HOME"
  rm -rf "$tmpdir"

  if ! maven_works; then
    echo "✗ Maven 解压后仍不可用：$MAVEN_HOME（请检查网络后重跑）" >&2
    exit 1
  fi
  echo "$MAVEN_HOME/bin/mvn"
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

# Runtime is now a first-class Kernel dependency and compile-kernel rebuilds it
# on every invocation. Resolve Maven even when the other jars are already cached.
MVN="$(find_mvn)"

if [[ $missing -eq 1 ]]; then
  # Kernel declares workflow-runtime as a normal Maven dependency. Install this
  # independent module first so dependency:copy-dependencies can resolve it.
  "$MVN" -q -B -f "$ROOT/workflow-runtime/pom.xml" -DskipTests install
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
