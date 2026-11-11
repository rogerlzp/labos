#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证「只读运行态流程页」：/runs/{runId}/flow 里节点按这次执行的状态着色。

要点（2026-11-11 用户纠正后定的口径）：
  · 这是**只读页**（bpmn-js Viewer）⇒ 与流程设计器完全是两回事；
  · 断言里必须包含「页面上没有编辑控件」—— 否则改了设计器就会悄悄退回可编辑。
"""
import argparse
import sys

from playwright.sync_api import sync_playwright

STATES = ("completed", "running", "manual", "failed", "pending", "bypassed", "skipped")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--run", type=int, default=1001040, help="要查看的 run id")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 950})
        page.goto(f"{args.base}/runs/{args.run}/flow", wait_until="networkidle")
        if page.locator('input[type="password"]').count() > 0:
            page.locator('input[type="password"]').first.fill(args.pwd)
            ti = page.locator('input[type="text"]')
            if ti.count() > 0 and not ti.first.input_value():
                ti.first.fill(args.user)
            page.get_by_role("button", name="登 录").click()
            page.wait_for_timeout(2500)
            page.goto(f"{args.base}/runs/{args.run}/flow", wait_until="networkidle")
        page.wait_for_timeout(3000)

        counts = {st: page.locator(f".rg-{st}").count() for st in STATES}
        print("rg-* marker 计数：", counts)
        readonly = page.locator("text=只读视图").count()
        # 编辑控件：palette（左侧可拖拽的节点面板）+ 设计器才有的入口文字
        edit_controls = page.locator(".djs-palette").count() \
            + page.locator("text=编排设备").count() \
            + page.locator("button:has-text('保存')").count()
        print("只读提示条：", "有" if readonly else "无")
        print("编辑控件数（应为 0）：", edit_controls)
        page.screenshot(path="/tmp/runflowview.png")
        print("截图：/tmp/runflowview.png")
        browser.close()

    coloured = sum(counts.values()) > 0
    ok = coloured and readonly >= 1 and edit_controls == 0
    print("结论：", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
