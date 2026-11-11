#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证「设计器运行态视图」：/designer/{wfId}?run={runId} 里节点按状态着色。"""
import sys
import time

from playwright.sync_api import sync_playwright

BASE = "http://localhost:5173"
WF = "1000546"
RUN = "1001035"
OUT = "/tmp/runstate-designer.png"


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 950})
        page.goto(f"{BASE}/designer/{WF}?run={RUN}", wait_until="networkidle")
        # 登录（未登录会被挡在登录页）
        if page.locator('input[type="password"]').count() > 0:
            page.locator('input[type="password"]').first.fill("admin123")
            ti = page.locator('input[type="text"]')
            if ti.count() > 0 and not ti.first.input_value():
                ti.first.fill("admin")
            page.get_by_role("button", name="登 录").click()
            page.wait_for_timeout(2500)
            page.goto(f"{BASE}/designer/{WF}?run={RUN}", wait_until="networkidle")

        page.wait_for_timeout(3000)
        # 画布元素是否带上了状态 marker
        counts = {}
        for st in ("running", "pending", "completed", "failed", "manual", "bypassed", "skipped"):
            counts[st] = page.locator(f".djs-element.run-state-{st}").count()
        print("marker 计数：", counts)
        # 运行态信息条
        info = page.locator("text=运行态视图").count()
        print("运行态信息条：", "有" if info else "无")
        # 每个带 marker 的节点的 id 与状态
        for st in ("running", "pending"):
            for i in range(counts[st]):
                el = page.locator(f".djs-element.run-state-{st}").nth(i)
                print(f"  {st}: {el.get_attribute('data-element-id')}")
        page.screenshot(path=OUT)
        print("截图：", OUT)
        browser.close()

    ok = counts.get("running", 0) >= 1 and counts.get("pending", 0) >= 1 and info >= 1
    print("结论：", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
