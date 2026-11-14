#!/usr/bin/env bash
# ============================================================================
# 把 Spring Boot 资源从 src/boot/resources 同步到运行目录 out/，并校验一致。
#
#   ./scripts/sync-resources.sh
#
# 为什么必须有：内核与连库自测的 classpath 只有 out/（`java -cp out:lib/...`），
# 而 javac 只编 Java、**不搬资源**。改完 mapper XML 不复制时，新 SQL 永远不生效，
# 而且**除走到那条语句外不报任何错**。
# 2026-11-15 实测：新增 mapper 语句后直接跑 RunControlDbSelfTest，报
#   BindingException: Invalid bound statement (not found)
# 排查成本很高（看起来像 MyBatis 配置问题，实为 out/mapper 是旧副本）。
#
# 单一实现：build-kernel.sh（启动前构建）与 test-all.sh（回归）都调用本脚本，
# 避免两处复制逻辑各改一半、出现「编译产物新 / 资源旧」的混合态。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/labos-kernel"

SRC="src/boot/resources"
OUT="out"

mkdir -p "$OUT/mapper"

# 顶层资源（properties / logback）：用通配，新增配置文件不必改脚本。
# shellcheck disable=SC2013
for f in "$SRC"/*; do
  [ -f "$f" ] && cp -f "$f" "$OUT"/
done

# mapper XML：唯一合法落点（见 ExperimentStateProjectorSelfTest 的扫描断言）
cp -f "$SRC"/mapper/*.xml "$OUT"/mapper/

# 校验：源与运行目录逐文件一致（多为漏拷、少为脏文件、内容不同为改完忘了同步）。
if ! diff -rq "$SRC/mapper" "$OUT/mapper" >/tmp/labos-mapper-sync.diff 2>&1; then
  echo "✗ mapper 资源未同步（out/mapper 与 src/boot/resources/mapper 不一致）：" >&2
  head -20 /tmp/labos-mapper-sync.diff | sed 's/^/   /' >&2
  exit 1
fi

echo "✓ 资源已同步（mapper $(ls -1 "$OUT"/mapper/*.xml | wc -l | tr -d ' ') 个 XML）"
