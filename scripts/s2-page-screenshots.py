#!/usr/bin/env python3
"""S2 页面功能验证 + 截图（Playwright）。

用法：
  python3 scripts/s2-page-screenshots.py
产物：
  private/s2-screenshots/ 下的 PNG（登录/流程库/设计器-声明卡/用例台/闸门列表）
"""
import json
import os
import subprocess
import sys
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "private", "s2-screenshots")


def api(path, method="GET", body=None, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def shot(pg, name):
    path = os.path.join(OUT, name + ".png")
    pg.screenshot(path=path, full_page=False)
    print(f"  📸 {name} → {path}")
    return path


def main():
    os.makedirs(OUT, exist_ok=True)
    from playwright.sync_api import sync_playwright

    code, body = api("/api/auth/login", "POST", {"username": USER, "password": PWD})
    token = json.loads(body).get("token") if code == 200 else None
    print(f"登录：HTTP {code}，token={'OK' if token else 'FAIL'}")

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1440, "height": 900})

        # 1. 登录页 → 主流程库
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        shot(pg, "01-login")
        pg.fill('input[type="text"]', USER)
        pg.fill('input[type="password"]', PWD)
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)
        shot(pg, "02-workflows")

        # 2. 设计器（含物料声明卡 MaterialEffectCard）
        wf = os.environ.get("S2_WF", "1000783")
        pg.goto(WEB + f"/designer/{wf}", wait_until="domcontentloaded")
        pg.wait_for_timeout(3500)
        shot(pg, "03-designer")

        # 点击一个 task 节点，露出节点设置卡（含 MaterialEffectCard）
        try:
            # bpmn-js 的 task 元素有 data-element-id；点第一个 task
            pg.locator(".djs-element[data-element-id]").first.click()
            pg.wait_for_timeout(1500)
            shot(pg, "04-designer-node-selected")
        except Exception as e:
            print("  点击节点失败（不影响后续）：", e)

        # 3. 用例台
        pg.goto(WEB + "/s2-test-cases", wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        shot(pg, "05-s2-test-cases")

        # 4. 物料效果列表（若有数据）——直接打开对账台用 GET 端点（页面若无专门 UI 则跳过）
        b.close()
    print(f"\n截图目录：{OUT}")


if __name__ == "__main__":
    sys.exit(main())
