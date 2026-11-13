#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""流程库页面（/workflows）评审用截图。

用途：产品评审时把「当前长什么样」固定成图片，避免评审靠记忆/靠想象。
只读：登录 + 截图，不新建、不执行、不改任何业务数据。

用法：
  python3 scripts/shot-workflows-review.py

产物：
  private/workflows-review/*.png
"""
import json
import os
import urllib.error
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "private", "workflows-review")


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


def shot(pg, name, full=False):
    path = os.path.join(OUT, name + ".png")
    pg.screenshot(path=path, full_page=full)
    print(f"  📸 {name} → {path}")
    return path


def main():
    os.makedirs(OUT, exist_ok=True)
    from playwright.sync_api import sync_playwright

    code, body = api("/api/auth/login", "POST", {"username": USER, "password": PWD})
    tok = json.loads(body).get("token") if code == 200 else None
    print(f"登录：HTTP {code} token={'OK' if tok else 'FAIL'}")
    if not tok:
        raise SystemExit("登录失败，截图中止")

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1440, "height": 900})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        shot(pg, "01-login")

        # 登录（页面内账号，dev 默认值）
        pg.locator('input[type="password"]').first.fill(PWD)
        pg.get_by_role("button", name="登 录").click()
        pg.wait_for_timeout(2500)

        # 1. 流程库整页
        pg.goto(WEB + "/workflows", wait_until="networkidle")
        pg.wait_for_timeout(2000)
        shot(pg, "02-workflows-viewport")
        shot(pg, "03-workflows-full", full=True)

        # 2. 点「详情」看详情卡（要点某一条）
        btn = pg.get_by_role("button", name="详情").first
        if btn.count() > 0:
            btn.click()
            pg.wait_for_timeout(2000)
            pg.mouse.wheel(0, 900)
            pg.wait_for_timeout(800)
            shot(pg, "04-workflows-detail")

        # 3. 新建流程弹窗
        pg.goto(WEB + "/workflows", wait_until="networkidle")
        pg.wait_for_timeout(1500)
        nb = pg.get_by_role("button", name="新建流程").first
        if nb.count() > 0:
            nb.click()
            pg.wait_for_timeout(1200)
            shot(pg, "05-workflows-create-modal")
            pg.keyboard.press("Escape")
            pg.wait_for_timeout(500)

        # 4. 设计器（同一功能的另一半）
        code, body = api("/api/workflows?limit=1", "GET", None, tok)
        items = json.loads(body).get("items", []) if code == 200 else []
        if items:
            wid = items[0]["id"]
            pg.goto(f"{WEB}/designer/{wid}", wait_until="networkidle")
            pg.wait_for_timeout(4000)
            shot(pg, "06-designer")

        # 5. 对照：运行列表（首页信息密度参照）
        pg.goto(WEB + "/runs", wait_until="networkidle")
        pg.wait_for_timeout(2000)
        shot(pg, "07-runs")

        b.close()
    print("done")


if __name__ == "__main__":
    main()
