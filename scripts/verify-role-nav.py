#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0 · 角色导航裁剪验证（Playwright）。

断言：
  ① admin 登录 → 菜单可见「用户」「审计」（roles=['admin'] 项对 admin 可见）
  ② 临时 designer 账号登录 → 「用户」「审计」不可见（admin-only 项被裁掉）

用法：LABOS_WEB=http://localhost:5173 python3 scripts/verify-role-nav.py
"""
import json
import os
import sys
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PASS, FAIL = 0, 0


def ok(m, d=""):
    global PASS
    PASS += 1
    print(f"  \033[32m✓\033[0m {m} {d}")


def bad(m, d=""):
    global FAIL
    FAIL += 1
    print(f"  \033[31m✗\033[0m {m} {d}")


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def login_api(u, p):
    code, body = api("/api/auth/login", "POST", {"username": u, "password": p})
    return json.loads(body).get("token") if code == 200 else None


def login_ui(pg, u, p):
    """清 localStorage 后走 UI 登录，返回 body 文本。"""
    pg.goto(WEB + "/login", wait_until="domcontentloaded")
    pg.wait_for_timeout(800)
    pg.evaluate("() => localStorage.clear()")
    pg.reload(wait_until="domcontentloaded")
    pg.wait_for_timeout(800)
    pg.fill('input[type="text"]', u)
    pg.fill('input[type="password"]', p)
    pg.get_by_role("button", name="登").first.click()
    pg.wait_for_timeout(2200)
    return pg.inner_text("body")


def menu_has(pg, text):
    # 展开所有子菜单后再看可见文本（「系统」默认折叠）
    for it in pg.locator(".ant-menu-submenu-title").all():
        try:
            it.click()
        except Exception:
            pass
    pg.wait_for_timeout(300)
    return pg.locator(".ant-menu").filter(has_text=text).count() > 0


def menu_missing(pg, text):
    return pg.locator(".ant-menu").filter(has_text=text).count() == 0


def main():
    from playwright.sync_api import sync_playwright

    admin_tok = login_api("admin", "admin123")
    if not admin_tok:
        bad("admin 登录失败")
        return 1

    # 建临时 designer 账号（幂等：已存在就跳过）
    _, body = api("/api/users", "POST",
                  {"username": "verify-designer", "displayName": "验证编排",
                   "role": "designer", "password": "verify123"}, admin_tok)
    try:
        created = json.loads(body).get("created")
    except json.JSONDecodeError:
        created = None
    if created:
        ok("临时 designer 账号创建成功")
    elif created is not None:
        ok("临时 designer 账号已存在（幂等）")

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1400, "height": 950})

        # ① admin
        login_ui(pg, "admin", "admin123")
        if menu_has(pg, "用户") and menu_has(pg, "审计"):
            ok("admin 菜单可见「用户」「审计」")
        else:
            bad("admin 菜单缺「用户/审计」")
        pg.screenshot(path="/tmp/role-nav-admin.png")

        # ② designer
        login_ui(pg, "verify-designer", "verify123")
        if not menu_has(pg, "用户") and not menu_has(pg, "审计"):
            ok("designer 菜单裁掉「用户」「审计」（admin-only）")
        else:
            bad("designer 菜单仍见 admin-only 项")
        pg.screenshot(path="/tmp/role-nav-designer.png")

        b.close()

    print(f"\n通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
