#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实验画布 e2e（P0 页面轨）。

覆盖 /canvas：顶部说明（保存画布会自动编译流程顺序）、左面板「节点」卡
（可拖节点只剩「流程」一类 + 提示「流程内逻辑去流程设计器」）、中卡
（实验画布标题 + 选实验下拉 + reactflow 画布 + 开始/结束自动铺 + 返回/保存
两个按钮）、右面板「属性」卡。

为什么要有这个：画布是实验编排的入口，保存会同时落 graph 与 process。
此前 /canvas 零页面级断言。规范见 docs/测试规范.md。

用法：
  python3 scripts/e2e-canvas.py
  python3 scripts/e2e-canvas.py --base http://127.0.0.1:8080 --user admin --pass admin123
  python3 scripts/e2e-canvas.py --headed

前置：前端在跑、API 可登录、库里有实验（画布靠 /api/experiments 拉下拉）。
末尾固定输出「通过 N / 失败 M」（阈值 MIN_CANVAS）。

  > **⚠ 已随旧基线退役（2026-11-11）**：本脚本针对的是**旧基线**
  > （三十座岛 + 一千多条流程的累积数据）。新基线重建后**不再纳入门禁**
  > （现行分段见 [测试总纲](docs/测试总纲.md) §2.2）。
  >
  > **为什么保留而不是删掉**：里面沉淀的是**页面交互的断言写法**（Playwright 操作、
  > DOM 选择器、断言模式）—— 重写页面轨时这些是最值钱的资产，删了等于重写。
  >
  > **重写时按新基线改**：岛用 `LAB-BASE-01`（或从接口动态取，别写死）；
  > 设备用基线 5 台（设备ID 200 / 229 / 231 / 413 / 414，
  > 能力 读板机 / 孵育箱 / 机器人 / 封膜机 / 离心机）。
"""


import argparse
import re
import sys

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

PASS = 0
FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print(f"  \u2713 {msg}")


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print(f"  \u2717 {msg}" + (f"  ({detail})" if detail else ""))


def step(title):
    print(f"\n\u2550\u2550 {title} \u2550\u2550")


def login(page, base, user, pwd):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    text_inputs = page.locator('input[type="text"]')
    if text_inputs.count() > 0 and not text_inputs.first.input_value():
        text_inputs.first.fill(user)
    page.get_by_role("button", name="登 录").click()
    page.wait_for_selector("text=流程库", timeout=15000)


def nav(page, menu):
    page.locator(f'.ant-menu-item:has-text("{menu}")').first.click()
    page.wait_for_timeout(1200)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.set_default_timeout(15000)

        try:
            # ---------------- 1. 登录 + 进实验画布 ----------------
            step("1. 登录与实验画布")
            login(page, args.base, args.user, args.pwd)
            ok("登录成功（左侧菜单出现）")
            nav(page, "实验画布")
            page.wait_for_selector(".react-flow", timeout=20000)
            page.wait_for_timeout(1200)
            body = page.inner_text("body")

            if "保存画布会自动编译流程顺序" in body:
                ok("顶部说明渲染（保存画布会自动编译流程顺序）")
            else:
                bad("缺画布顶部说明")

            # ---------------- 2. 左面板：只剩「流程」一类可拖节点 ----------------
            step("2. 左面板（只提供「流程」一类节点）")
            left = page.locator(".ant-card", has_text="可拖节点").first
            if left.count() > 0 and "节点" in left.inner_text():
                ok("左面板「节点」卡渲染")
            else:
                bad("缺左面板节点卡")
            palette = left.locator('div[draggable="true"]')
            if palette.count() == 1 and "流程" in palette.first.inner_text():
                ok("可拖节点只剩「流程」一类")
            else:
                bad("可拖节点不是只有「流程」一类", f"count={palette.count()}")
            if "流程设计器" in left.inner_text():
                ok("左面板提示：流程内逻辑去「流程设计器」")
            else:
                bad("缺「流程内逻辑去流程设计器」提示")

            # ---------------- 3. 中卡：标题 + 选实验 + 画布 + 按钮 ----------------
            step("3. 中卡（实验画布 + 选实验 + reactflow + 两个按钮）")
            center = page.locator(".ant-card", has_text="实验画布").first
            if center.count() > 0:
                ok("中卡标题渲染（实验画布）")
            else:
                bad("缺中卡标题")
            if center.locator(".ant-select").count() > 0:
                ok("有「选一个实验」下拉（切换实验）")
            else:
                bad("缺选实验下拉")
            if page.locator(".react-flow__pane, .react-flow").count() > 0:
                ok("reactflow 画布容器已渲染")
            else:
                bad("reactflow 画布没渲染")
            # 两字中文按钮会被 antd 插空格渲染成「返 回」/「保 存」，用正则容错
            if center.locator("button").filter(has_text=re.compile(r"返\s*回")).count() > 0:
                ok("有「返回」按钮")
            else:
                bad("缺返回按钮")
            if center.locator("button").filter(has_text=re.compile(r"保\s*存")).count() > 0:
                ok("有「保存」按钮")
            else:
                bad("缺保存按钮")
            # 开始 / 结束由画布自动铺好（不依赖用户拖拽）
            try:
                page.wait_for_selector(".react-flow__node", timeout=8000)
                ntext = page.inner_text("body")
                if "开始" in ntext and "结束" in ntext:
                    ok("开始 / 结束节点自动铺好")
                else:
                    bad("自动节点缺「开始」或「结束」")
            except PWTimeout:
                bad("画布没有自动铺出开始/结束节点（库里没有实验？）")

            # ---------------- 4. 右面板：属性 ----------------
            step("4. 右面板属性卡")
            if page.locator(".ant-card", has_text="属性").count() > 0:
                ok("右面板「属性」卡渲染")
            else:
                bad("缺右面板属性卡")

            page.screenshot(path="/tmp/e2e-canvas.png")

        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:120])
            try:
                page.screenshot(path="/tmp/e2e-canvas-timeout.png")
                print("     截图：/tmp/e2e-canvas-timeout.png")
            except Exception:
                pass
        finally:
            browser.close()

    print(f"\n{'─' * 42}")
    print(f"通过 {PASS} / 失败 {FAIL}")
    print(f"{'─' * 42}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
