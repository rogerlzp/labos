#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「演示回放剧本」渲染成**动态演示视频**（mp4）。

**为什么要有这个**（2026-11-16）：
客户演示的三条腿里，第三条是"把模拟跑的各个状态做成一个动态视频"。
而"每个环节在做什么、此刻哪台设备在干什么"**本来就是结构化数据**
（`scripts/replay-run.py` 产出的机读剧本）⇒
直接**渲染**就得到视频，不需要另做一套动画，也不需要真的再跑一遍。

**关键取舍**：
    · 帧是**逐帧算出来的**，不是屏幕录制 ⇒ 节奏可控（`--speed`）、
      可复现（同剧本同参数 ⇒ 同视频）、零环境风险（不依赖模拟器在线）。
    · 用 canvas 画 ⇒ 中文排版、配色、字幕都在一个文件里（`demo-video-player.html`），
      客户要改版式只改那一个 html，不用碰 Python。
    · 视频里每句话都能对回系统里的真实记录（剧本就是从流水生成的）。

**依赖**：playwright（chromium）+ ffmpeg（都在本机）。
    pip install playwright && playwright install chromium
    brew install ffmpeg

用法：
    python3 scripts/render-demo-video.py <剧本.json> --out <video.mp4> \
        [--fps 30] [--speed 4] [--start-ms 0] [--end-ms 0]

    --speed   加速倍率：视频时长 = 真实时长 / speed（真实 47s ⇒ speed 4 ⇒ 约 12s）
    --fps     帧率（默认 30）
"""

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE = os.path.join(HERE, "demo-video-player.html")


def die(msg):
    print(f"✘ {msg}", file=sys.stderr)
    sys.exit(1)


def check_deps():
    for tool, hint in (("ffmpeg", "brew install ffmpeg"),):
        if not shutil.which(tool):
            die(f"缺 {tool}（{hint}）")
    try:
        from playwright.sync_api import sync_playwright  # noqa: F401
    except Exception as e:
        die(f"缺 playwright（pip install playwright && playwright install chromium）：{e}")


def build_page(data, tmpdir):
    """把数据注入模板，产出一个可离线打开的 html。"""
    with open(TEMPLATE, encoding="utf-8") as f:
        html = f.read()
    if "window.__DATA__" not in html:
        die("模板里找不到 window.__DATA__ 注入点")
    html = html.replace(
        "const D = window.__DATA__ || {};",
        "const D = " + json.dumps(data, ensure_ascii=False) + ";")
    path = os.path.join(tmpdir, "player.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    return path


def render(data, out_path, fps=30, speed=4.0, start_ms=0, end_ms=0):
    from playwright.sync_api import sync_playwright

    total = int(data.get("totalMs") or 0)
    if total <= 0:
        die("剧本里没有 totalMs（不是一份可渲染的回放剧本）")
    end_ms = end_ms or total
    if end_ms > total:
        end_ms = total

    # 视频时长 = 真实时长 / speed
    span = max(1, end_ms - start_ms)
    video_ms = span / speed
    frames = max(1, int(round(video_ms / 1000.0 * fps)))

    tmpdir = tempfile.mkdtemp(prefix="labos-video-")
    page_path = build_page(data, tmpdir)

    out_dir = os.path.dirname(os.path.abspath(out_path))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "image2pipe", "-vcodec", "mjpeg", "-r", str(fps), "-i", "-",
        "-c:v", "libx264", "-preset", "medium", "-crf", "20",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        out_path,
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    t0 = time.time()
    err = None

    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(args=["--force-color-profile=srgb"])
            page = browser.new_page(viewport={"width": 1920, "height": 1080},
                                    device_scale_factor=1)
            page.goto("file://" + page_path)
            page.wait_for_function("window.__ready === true", timeout=30000)

            # 先画一帧验证不抛异常（模板/数据有问题要立刻报，而不是渲到一半白屏）
            page.evaluate("t => window.__renderAt(t)", start_ms)
            page.screenshot(path=os.path.join(tmpdir, "probe.png"))

            for i in range(frames):
                t = start_ms + span * (i / max(1, frames - 1))
                url = page.evaluate("t => window.__renderAt(t) || document.getElementById('c').toDataURL('image/jpeg', 0.92)", t)
                if not url or not url.startswith("data:image/jpeg"):
                    die(f"第 {i} 帧渲染返回异常：{str(url)[:80]}")
                proc.stdin.write(base64.b64decode(url.split(",", 1)[1]))
                if i % 30 == 0:
                    done = i + 1
                    el = time.time() - t0
                    print(f"  · {done}/{frames} 帧（{done/frames*100:.0f}%，"
                          f"预计还需 {el/done*(frames-done):.0f}s）", flush=True)
            browser.close()
    except SystemExit:
        raise
    except BaseException as e:      # noqa: BLE001 —— 原始异常必须露出来，别被 ffmpeg 的退出码盖住
        err = e
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        rc = proc.wait()
        shutil.rmtree(tmpdir, ignore_errors=True)

    if err is not None:
        import traceback
        traceback.print_exception(type(err), err, err.__traceback__)
        raise SystemExit("✘ 渲染失败（详见上面的 traceback）")
    if rc != 0:
        die(f"ffmpeg 退出码 {rc}")

    size = os.path.getsize(out_path)
    print(f"✓ 视频已生成：{out_path}")
    print(f"  {frames} 帧 @ {fps}fps ⇒ {frames/fps:.1f}s 视频（真实 {span/1000:.1f}s ÷ {speed}× 加速）"
          f"　{size/1024/1024:.1f} MB")
    return out_path


def main():
    ap = argparse.ArgumentParser(description="把演示回放剧本渲染成动态演示视频")
    ap.add_argument("script", help="replay-run.py 产出的机读剧本 .json")
    ap.add_argument("--out", required=True, help="输出 mp4 路径")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--speed", type=float, default=4.0, help="加速倍率（视频时长=真实时长/speed）")
    ap.add_argument("--start-ms", type=int, default=0)
    ap.add_argument("--end-ms", type=int, default=0)
    args = ap.parse_args()

    check_deps()
    if not os.path.exists(args.script):
        die(f"剧本不存在：{args.script}")
    with open(args.script, encoding="utf-8") as f:
        data = json.load(f)

    n_stage = len(data.get("stages") or [])
    n_node = sum(len(s.get("nodes") or []) for s in data.get("stages") or [])
    print(f"▶ 渲染 run {data.get('runId')}：{n_stage} 个阶段 · {n_node} 个节点 · "
          f"{len(data.get('devices') or [])} 台设备")
    render(data, args.out, fps=args.fps, speed=args.speed,
           start_ms=args.start_ms, end_ms=args.end_ms)
    return 0


if __name__ == "__main__":
    sys.exit(main())
