#!/usr/bin/env bash
# ============================================================================
# 构建内核（Spring Boot 容器 + Spring MVC HTTP 层）
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

# 依赖：只在**关键 jar 齐了**时才跳过拉取。
# 为什么看多个 jar 而不是单个标记：加新依赖（如 2026-11-10 的 lettuce-core）时，
# 已有 lib/spring/ 的本地环境会因为「老标记还在」而跳过下载 ⇒ 编译报
# 「程序包io.lettuce.core不存在」。加依赖时在这里补一个判断即可。
# CI 是全新检出，没有 lib/spring/，一律全量拉。
if [[ ! -f lib/spring/spring-jdbc-6.1.6.jar || ! -f lib/spring/lettuce-core-6.3.2.RELEASE.jar ]]; then
  MVN="$(find_mvn)"
  "$MVN" -q -B dependency:copy-dependencies \
      -DincludeScope=runtime -DoutputDirectory=lib/spring
  # Spring Boot 管理的 pg 驱动版本与仓库现用的 42.7.3 不同，统一用现用的
  rm -f lib/spring/postgresql-*.jar
  echo "依赖就位：$(ls -1 lib/spring | wc -l | tr -d ' ') 个 jar"
fi

# ---- ② 编译 main + boot（一次 javac 同时编译；先清理 boot 的 stale class）----
# 为什么合成一步：boot 依赖 main，javac 单次调用会一并解析跨源引用，
# 不必再分两次（2026-11-12 实测单条 javac 编译 main+boot exit=0）。
rm -rf out/com/labos/boot
rm -f out/com/labos/kernel/api/LegacyRouteController.class \
      out/com/labos/kernel/api/ServletHttpExchange.class
javac -encoding UTF-8 -parameters -d out -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
      $(find src/main src/boot/java -name '*.java')
echo "✓ main + boot 编译完成（一次 javac）"

# ---- ②a mapper XML 合法性自检（**编译查不出来，只有起内核才炸**）----
#
# 为什么必须有：XML 注释里**不允许出现 `--`**（`<!-- ---- xxx ---- -->` 是非法的），
# 而 mapper XML 不参与 javac ⇒ 写错时编译照样过、测试也照样跑（连不上库也走不到解析），
# 只有**真启动内核**时 MyBatis 才抛 "Error parsing Mapper XML"，容器起不来、
# 端口不监听 ⇒ 现场表现为"登录不进去 / 服务没起来"，极难联想到是 XML 注释的问题。
# 2026-11-10 实测踩到：3 个 mapper 里的 `<!-- ---- … ---- -->` 让 HEAD 的内核直接起不来。
python3 - <<'PY'
import os, sys, xml.etree.ElementTree as ET
bad = []
for dp, _dn, fn in os.walk('src'):
    for f in fn:
        if not f.endswith('.xml'):
            continue
        p = os.path.join(dp, f)
        try:
            ET.parse(p)
        except Exception as e:
            bad.append(f"{p}: {e}")
if bad:
    print("✗ mapper/资源 XML 不合法（内核会起不来）：", file=sys.stderr)
    for b in bad:
        print("   " + b, file=sys.stderr)
    sys.exit(1)
PY
echo "✓ XML 合法性自检通过（mapper / 资源）"

# ---- ③ 资源（logback / application.properties / mapper XML）----
mkdir -p out/mapper
cp -f src/boot/resources/* out/ 2>/dev/null || true
cp -f src/boot/resources/mapper/*.xml out/mapper/ 2>/dev/null || true

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
