#!/usr/bin/env bash
# ============================================================================
# 内核编译的**单一入口**：main + boot + 资源同步
#
#   ./scripts/compile-kernel.sh
#
# 为什么要有这个入口（2026-11-15 修「第三个构建坑」）：
#
#   ① src/test 里有 9 个类**直接引用 com.labos.boot.***（TestSpringSupport /
#      ExperimentGraphCompileSelfTest / DeviceEventApiSelfTest …），
#      而旧 test-all.sh 只编 src/main ⇒ **out/com/labos/boot 不在时，
#      编译测试代码直接报「程序包 com.labos.boot 不存在」**。
#      旧做法平时不炸，纯粹因为 out/ 里恰好留着上一次 build-kernel.sh 编出来的
#      boot class —— 典型的「靠磁盘残留兜底」：换个机器、清一次 out/、
#      或并发会话重建了 out/，单测就红在"编译"而不是"断言"。
#
#   ② 编译参数会漂移：build-kernel.sh 用 -parameters（Spring MVC 按参数名绑定
#      需要它），test-all.sh 不用 ⇒ 跑一次单测就把 out/ 里的 class 覆盖成
#      「没有参数名」的版本，接着起内核时 MVC 绑定会悄悄失配。
#
# 所以「编 main + boot + 同步资源」**只有这一个实现**：
#   scripts/build-kernel.sh（构建/启动内核）
#   scripts/test-all.sh   （回归单测）
#   .github/workflows/*.yml（CI）
# 三处都调它，避免各写一份、迟早漂移。
#
# 产物一律落 labos-kernel/out/（已 gitignore），classpath 约定不变：
#   out : lib/postgresql-42.7.3.jar : lib/spring/*
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/labos-kernel"

CP="out:lib/postgresql-42.7.3.jar:lib/spring/*"

# ---- ① 依赖就位（boot 依赖 Spring ⇒ 编 boot 前必须先有 jar）----
bash "$ROOT/scripts/ensure-deps.sh"

# ---- ② 清 stale（boot 整目录 + 两个已从源码删除的历史类）----
# 为什么必须清：javac **不会删除源码里已经没有的 class**，
# 删掉的方法会以旧 class 的形式继续留在 out/ 里被 classpath 命中。
rm -rf out/com/labos/boot
rm -f out/com/labos/kernel/api/LegacyRouteController.class \
      out/com/labos/kernel/api/ServletHttpExchange.class

# ---- ③ 编译 main + boot（一次 javac）----
# 为什么合成一步：boot 依赖 main，javac 单次调用会一并解析跨源引用，
# 不必再分两次（2026-11-12 实测单条 javac 编译 main+boot exit=0）。
# 为什么用 @argfile 而不是 $(find ...)：源文件多起来后命令行会撞长度上限，
# 且路径里出现空格时 unquoted 展开会被拆成两个参数。
ARGFILE="$(mktemp)"
trap 'rm -f "$ARGFILE"' EXIT
find src/main src/boot/java -name '*.java' | LC_ALL=C sort > "$ARGFILE"
javac -encoding UTF-8 -parameters -d out -cp "$CP" "@$ARGFILE"
echo "✓ main + boot 编译完成（$(wc -l < "$ARGFILE" | tr -d ' ') 个源文件）"

# ---- ④ mapper XML 合法性自检（**编译查不出来，只有起内核才炸**）----
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

# ---- ⑤ 资源同步（logback / application.properties / mapper XML）----
# 单一实现抽到 scripts/sync-resources.sh（build-kernel.sh / test-all.sh 也调用它）：
# 两处各写一份复制逻辑，迟早出现「编译产物新 / 资源旧」的混合态
#（2026-11-15 实测过：mapper XML 没同步，自测报 BindingException）。
bash "$ROOT/scripts/sync-resources.sh"
