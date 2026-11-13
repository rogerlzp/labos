#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「实验室中控 / LabOS Console」应用图标（favicon + 顶栏 logo）。

为什么要有这个脚本：图标是**几何定义**，不是美术文件。
SVG（浏览器/矢量场景）与 PNG（favicon/触屏/旧浏览器场景）必须长得一模一样，
手工分别画一定会漂 —— 漂了就出现"网页标签是新版、顶栏是旧版"的割裂感。

所以这里把几何常量写在**一处**，一次跑出全部产物：
  · webapp/src/assets/labos-mark.svg   —— 前端 import 用的矢量图（顶栏 / 登录页）
  · webapp/public/favicon.svg          —— 浏览器标签页（现代 Chrome/Edge）
  · webapp/public/favicon-{16,32,48,64}.png —— PNG 回退（Safari/旧版/书签）
  · webapp/public/favicon.ico          —— IE/旧 Windows/部分企业网关的老式引用
  · webapp/public/apple-touch-icon.png —— iOS 添加到主屏（180×180，**不透明背景**）
  · webapp/public/icon-{192,512}.png + site.webmanifest —— PWA / 安装图标

图形语义：**"中"字骨架 + 控制中枢**
  口（方框）= 中控的"面"，中间那根竖线贯穿上下 = 中枢贯穿到每一台设备。
  竖线顶端收成圆头、下端探出方框 = 从中心往下发指令的"调度轴"。
  16×16 缩到浏览器标签尺寸时仍能分辨（描边 ≥ 1.3px），这是选它而不是画
  复杂流程图的原因。

改颜色 / 改形状 → 只改下面 GEOMETRY / PALETTE，然后重跑：
    python3 scripts/gen_app_icon.py
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

# ---------------------------------------------------------------- 画布与几何
# 所有坐标用 64×64 的"设计单位"，放大靠 SS 倍超采样，缩小时靠 LANCZOS。
U = 64

GEOMETRY = {
    "radius": 14.0,          # 背板圆角（iOS 会给图标加圆角遮罩，这里先自己收圆角）
    # "中"字的方框（口）
    "box_x0": 17.0, "box_y0": 20.0, "box_x1": 47.0, "box_y1": 45.0,
    "box_radius": 6.0,
    # 贯穿的竖线（中枢）
    "stem_x": 32.0, "stem_y0": 9.0, "stem_y1": 55.0,
    "stroke": 5.2,           # 描边宽度（设计单位）
}

PALETTE = {
    "grad_from": (74, 155, 255),   # 左上：#4A9BFF
    "grad_to": (11, 69, 184),      # 右下：#0B45B8
    "glyph": (255, 255, 255),
    "highlight": (255, 255, 255),  # 左上高光（径向）
    "highlight_alpha": 46,         # 0-255
    "highlight_r": 26.0,           # 高光半径（设计单位）
    "theme": "#0d1014",            # 与 index.css 的页面底色一致
}

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "webapp" / "src" / "assets"
PUBLIC_DIR = ROOT / "webapp" / "public"
# 内核自带的看板页（labos-kernel/web/）也要同一张图标：那条路径是
# "直接打开中控端口"的第一入口，不能只有 React 前端有图标。
KERNEL_WEB_DIR = ROOT / "labos-kernel" / "web"

SS = 8  # 超采样倍数


def _lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def _diagonal_gradient(size: int) -> Image.Image:
    """左上→右下的对角渐变（小图生成后放大，避免逐像素算大图）。"""
    small = Image.new("RGB", (64, 64))
    px = small.load()
    c0, c1 = PALETTE["grad_from"], PALETTE["grad_to"]
    for y in range(64):
        for x in range(64):
            t = (x + y) / (2 * 63)
            px[x, y] = _lerp(c0, c1, t)
    return small.resize((size, size), Image.LANCZOS)


def _rounded_mask(size: int) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    r = GEOMETRY["radius"] * SS
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def _highlight_layer(size: int) -> Image.Image:
    """左上角柔光：让纯色徽章看起来有厚度，而不是一块塑料色块。"""
    hl = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(hl)
    cx = 19.0 * SS
    cy = 15.0 * SS
    r = PALETTE["highlight_r"] * SS
    steps = 26
    for i in range(steps, 0, -1):
        t = i / steps
        alpha = int(PALETTE["highlight_alpha"] * (1 - t) ** 2)
        d.ellipse([cx - r * t, cy - r * t, cx + r * t, cy + r * t],
                  fill=PALETTE["highlight"] + (alpha,))
    return hl


def _glyph_mask(size: int) -> Image.Image:
    """白色"中"字描边（单通道，LANCZOS 缩小时不会糊成一坨）。"""
    g = GEOMETRY
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    w = int(round(g["stroke"] * SS))
    # 口：圆角矩形描边
    d.rounded_rectangle(
        [g["box_x0"] * SS, g["box_y0"] * SS, g["box_x1"] * SS, g["box_y1"] * SS],
        radius=g["box_radius"] * SS, outline=255, width=w,
    )
    # 竖线：画线 + 两端圆头
    x = g["stem_x"] * SS
    y0, y1 = g["stem_y0"] * SS, g["stem_y1"] * SS
    d.line([(x, y0), (x, y1)], fill=255, width=w)
    rad = w / 2
    for yy in (y0, y1):
        d.ellipse([x - rad, yy - rad, x + rad, yy + rad], fill=255)
    return m


def render_master() -> Image.Image:
    """出 512×512 的母版（RGBA，圆角外透明）。"""
    size = 512
    bg = _diagonal_gradient(size).convert("RGBA")
    bg = Image.alpha_composite(bg, _highlight_layer(size))

    glyph = Image.new("RGBA", (size, size), PALETTE["glyph"] + (0,))
    glyph.putalpha(_glyph_mask(size))
    out = Image.alpha_composite(bg, glyph)

    # 背板圆角：把圆角外的透明挖掉
    mask = _rounded_mask(size)
    out.putalpha(Image.composite(out.getchannel("A"), Image.new("L", (size, size), 0), mask))
    return out


def svg_source() -> str:
    """与 PNG 同一份几何的矢量版本。"""
    g = GEOMETRY
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {U} {U}" width="{U}" height="{U}" role="img" aria-label="实验室中控 LabOS">
  <defs>
    <linearGradient id="labos-bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="rgb{PALETTE['grad_from']}"/>
      <stop offset="1" stop-color="rgb{PALETTE['grad_to']}"/>
    </linearGradient>
    <radialGradient id="labos-hl" cx="0.30" cy="0.23" r="0.55">
      <stop offset="0" stop-color="rgb{PALETTE['highlight']}" stop-opacity="{PALETTE['highlight_alpha'] / 255:.3f}"/>
      <stop offset="1" stop-color="rgb{PALETTE['highlight']}" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <!-- 背板：圆角徽章 + 对角渐变 + 左上柔光 -->
  <rect x="0" y="0" width="{U}" height="{U}" rx="{g['radius']}" fill="url(#labos-bg)"/>
  <rect x="0" y="0" width="{U}" height="{U}" rx="{g['radius']}" fill="url(#labos-hl)"/>
  <!-- "中"字：方框(口) + 贯穿竖线(中枢) -->
  <g fill="none" stroke="rgb{PALETTE['glyph']}" stroke-width="{g['stroke']}" stroke-linecap="round" stroke-linejoin="round">
    <rect x="{g['box_x0']}" y="{g['box_y0']}" width="{g['box_x1'] - g['box_x0']}" height="{g['box_y1'] - g['box_y0']}" rx="{g['box_radius']}"/>
    <line x1="{g['stem_x']}" y1="{g['stem_y0']}" x2="{g['stem_x']}" y2="{g['stem_y1']}"/>
  </g>
</svg>
"""


def apple_touch(master: Image.Image) -> Image.Image:
    """iOS 主屏图标：iOS 自己加圆角，所以底板必须铺满（不透明），否则边缘发黑。"""
    size = 180
    flat = Image.new("RGB", (size, size), PALETTE["grad_to"])
    small = _diagonal_gradient(size)
    flat = small
    mark = master.resize((size, size), Image.LANCZOS)
    # 把母版的"字形层"抠出来叠到不透明底上
    glyph = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glyph.putalpha(_glyph_mask(size).resize((size, size), Image.LANCZOS))
    composed = Image.alpha_composite(flat.convert("RGBA"), glyph)
    # 背板本身是圆角矩形，iOS 会再套一层圆角遮罩 —— 这里保持圆角外透明，
    # 但用底色补齐 iOS 圆角遮罩切掉的部分，避免黑边。
    canvas = Image.new("RGB", (size, size), PALETTE["grad_to"])
    canvas.paste(composed.convert("RGB"), (0, 0), composed.getchannel("A"))
    return canvas


def main() -> None:
    SRC_DIR.mkdir(parents=True, exist_ok=True)
    PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    KERNEL_WEB_DIR.mkdir(parents=True, exist_ok=True)

    master = render_master()

    svg = svg_source()
    (SRC_DIR / "labos-mark.svg").write_text(svg, encoding="utf-8")
    (PUBLIC_DIR / "favicon.svg").write_text(svg, encoding="utf-8")
    (KERNEL_WEB_DIR / "favicon.svg").write_text(svg, encoding="utf-8")

    for s in (16, 32, 48, 64, 192, 512):
        img = master.resize((s, s), Image.LANCZOS)
        if s <= 64:
            img.save(PUBLIC_DIR / f"favicon-{s}.png", format="PNG", optimize=True)
            # 内核看板只需要 16/32 两个尺寸 + ico（其它尺寸给 React 前端用）
            if s in (16, 32):
                img.save(KERNEL_WEB_DIR / f"favicon-{s}.png", format="PNG", optimize=True)
        else:
            img.save(PUBLIC_DIR / f"icon-{s}.png", format="PNG", optimize=True)

    apple_touch(master).save(PUBLIC_DIR / "apple-touch-icon.png", format="PNG", optimize=True)
    apple_touch(master).save(KERNEL_WEB_DIR / "apple-touch-icon.png", format="PNG", optimize=True)

    # ICO：一次写入 16/32/48/64 四个尺寸，老浏览器只认这个格式
    ico_base = master.resize((64, 64), Image.LANCZOS)
    ico_base.save(PUBLIC_DIR / "favicon.ico", format="ICO",
                  sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])
    ico_base.save(KERNEL_WEB_DIR / "favicon.ico", format="ICO",
                  sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])

    manifest = f"""{{
  "name": "实验室中控 LabOS Console",
  "short_name": "LabOS",
  "description": "实验室中控 / LabOS Console —— 实验流程编排与设备调度",
  "start_url": "/",
  "display": "standalone",
  "background_color": "{PALETTE['theme']}",
  "theme_color": "{PALETTE['theme']}",
  "icons": [
    {{ "src": "/icon-192.png", "sizes": "192x192", "type": "image/png" }},
    {{ "src": "/icon-512.png", "sizes": "512x512", "type": "image/png" }},
    {{ "src": "/favicon.svg", "sizes": "any", "type": "image/svg+xml" }}
  ]
}}
"""
    (PUBLIC_DIR / "site.webmanifest").write_text(manifest, encoding="utf-8")

    # 顺带打印各尺寸的实际像素尺寸，方便肉眼核对有没有画歪
    for p in sorted(PUBLIC_DIR.glob("favicon*")) + sorted(PUBLIC_DIR.glob("icon*")) + \
             [PUBLIC_DIR / "apple-touch-icon.png"]:
        if p.suffix == ".svg":
            continue
        with Image.open(p) as im:
            print(f"  {p.relative_to(ROOT)}  {im.size}  {im.mode}")
    print(f"  {(SRC_DIR / 'labos-mark.svg').relative_to(ROOT)}  (vector)")
    print(f"  {(PUBLIC_DIR / 'favicon.svg').relative_to(ROOT)}  (vector)")
    print(f"  {(PUBLIC_DIR / 'site.webmanifest').relative_to(ROOT)}  (manifest)")


if __name__ == "__main__":
    main()
