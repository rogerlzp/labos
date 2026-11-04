#!/usr/bin/env bash
# 重新录制 README 顶部的演示动画。
#
# 动画源是 docs/media/labos-flow-source.html —— 单文件 SVG，无构建步骤。
# 本脚本负责：渲染每一帧 → 导出 GIF / MP4 → 更新社交预览图。
#
# 用法：
#   ./scripts/render-demo.sh                 # 渲染 + 导出（默认 25fps）
#   ./scripts/render-demo.sh --fps 20        # 换帧率
#   ./scripts/render-demo.sh --check         # 只做几何自检，不渲染
#
# 依赖：python3 + playwright(chromium) + ffmpeg
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/docs/media/labos-flow-source.html"
OUT="$ROOT/docs/media"
TMP="$(mktemp -d)"
FPS=25
CHECK_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --fps) FPS="$2"; shift 2 ;;
    --check) CHECK_ONLY=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

[[ -f "$SRC" ]] || { echo "找不到动画源：$SRC" >&2; exit 1; }

# ---------- 依赖检查 ----------
need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖：$1" >&2; exit 1; }; }
need python3
need ffmpeg
python3 -c "import playwright" 2>/dev/null || { echo "缺少 playwright（pip install playwright && playwright install chromium）" >&2; exit 1; }

# ---------- 几何自检 ----------
# 动画里的板子/设备位置是硬编码坐标，改了画布尺寸就必须重算。
# 这里检查几个关键不变量，避免"录出来才发现抓不到板子"。
python3 - "$SRC" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
def num(name, default=None):
    m = re.search(rf"const {name}\s*=\s*([0-9.]+)", src)
    return float(m.group(1)) if m else default

rail   = num("RAIL_Y");   bench = num("BENCH_Y")
checks = []
if rail is None or bench is None:
    print("  ⚠ 读不到 RAIL_Y / BENCH_Y，跳过几何自检")
else:
    # 抓爪携带的板子：世界坐标 = RAIL_Y + 112（工具组内偏移）
    plate_top = rail + 112
    plate_center = plate_top + 6
    # 各工位目标高度 → 需要的伸长量
    targets = {
        "存储位板位": bench - 42,
        "设备舱内":   bench - 150 + 100,
        "出料位叠顶": bench - 53,
    }
    for k, y in targets.items():
        need_ext = y - plate_center
        checks.append((k, y, need_ext))
        print(f"  · {k:12} 板位 y={y:.0f}  需要伸长 ext≈{need_ext:.0f}")
    # 抓取段的 ext 上限必须 ≥ 存储位所需
    m = re.search(r"kin\.ext\s*=\s*54\s*\+\s*([0-9]+)\*k", src)
    if m:
        top_ext = 54 + int(m.group(1))
        need = targets["存储位板位"] - plate_center
        status = "✓" if top_ext >= need else "✗"
        print(f"  {status} 抓取段最大伸长 {top_ext} vs 存储位需要 {need:.0f}")
        if top_ext < need:
            print("    → 抓不到板子：调大抓取段的最大伸长")
PY

if [[ $CHECK_ONLY -eq 1 ]]; then
    echo "几何自检完成（未渲染）"
    exit 0
fi

# ---------- 渲染帧 ----------
echo "渲染 ${FPS}fps …"
python3 - "$SRC" "$TMP" "$FPS" <<'PY'
import asyncio, sys, os
from playwright.async_api import async_playwright
src, tmp, fps = sys.argv[1], sys.argv[2], int(sys.argv[3])
os.makedirs(tmp, exist_ok=True)
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--force-color-profile=srgb","--font-render-hinting=none"])
        pg = await b.new_page(viewport={"width":1280,"height":640}, device_scale_factor=1)
        await pg.goto("file://" + src)
        await pg.wait_for_timeout(500)
        total = await pg.evaluate("window.__total")
        frames = int(total/1000*fps)
        print(f"  总时长 {total}ms，{frames} 帧")
        for i in range(frames):
            await pg.evaluate(f"window.__renderFrame({i*1000.0/fps})")
            await pg.screenshot(path=f"{tmp}/f{i:04d}.png")
        await b.close()
asyncio.run(main())
PY

# ---------- 导出 ----------
echo "导出 GIF …"
ffmpeg -y -framerate "$FPS" -i "$TMP/f%04d.png" \
  -vf "fps=${FPS},split[s0][s1];[s0]palettegen=max_colors=192[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
  -loop 0 "$OUT/labos-flow.gif" 2>/dev/null

echo "导出 MP4 …"
ffmpeg -y -framerate "$FPS" -i "$TMP/f%04d.png" \
  -c:v libx264 -pix_fmt yuv420p -crf 20 -movflags +faststart "$OUT/labos-flow.mp4" 2>/dev/null

# ---------- 社交预览图（取一帧） ----------
echo "更新社交预览图 …"
python3 - "$TMP" "$ROOT/.github/assets/social-preview.jpg" <<'PY'
import sys, glob
from PIL import Image
tmp, out = sys.argv[1], sys.argv[2]
frames = sorted(glob.glob(f"{tmp}/f*.png"))
if not frames:
    print("  没有可用的帧"); sys.exit(1)
# 取 25% 处：通常是机械臂正夹着板子横移的瞬间
idx = min(len(frames)-1, int(len(frames)*0.25))
Image.open(frames[idx]).convert("RGB").save(out, "JPEG", quality=90, optimize=True)
print(f"  预览图取自第 {idx} 帧")
PY

rm -rf "$TMP"
echo "完成："
ls -lh "$OUT/labos-flow.gif" "$OUT/labos-flow.mp4" "$ROOT/.github/assets/social-preview.jpg"
