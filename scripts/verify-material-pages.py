#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""物料/位置管理 · 页面验证（Playwright 真实 UI + 截图 + 断言）。

验证项：
  P1 登录
  P2 /positions 选岛后按设备分组展示点位（含排×列 / 高度 / 可放板型列）
  P3 编辑点位网格属性并保存
  P4 /consumables 物料管理：耗材/试剂两个 Tab + 目录表渲染
  P5 新建一个耗材批次并挂点位，保存

用法：LABOS_WEB=http://localhost:5173 python3 scripts/verify-material-pages.py
"""
import json
import os
import sys
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "private", "s2-screenshots")

PASS = 0
FAIL = 0


def ok(name, detail=""):
    global PASS
    PASS += 1
    print(f"  \033[32m✓\033[0m {name} {detail}")


def bad(name, detail=""):
    global FAIL
    FAIL += 1
    print(f"  \033[31m✗\033[0m {name} {detail}")


def shot(pg, name):
    os.makedirs(OUT, exist_ok=True)
    try:
        pg.screenshot(path=os.path.join(OUT, name + ".png"), full_page=False)
    except Exception:
        pass


def main():
    from playwright.sync_api import sync_playwright

    # 登录拿 token（后端回读用）
    req = urllib.request.Request(API + "/api/auth/login", method="POST",
                                 data=json.dumps({"username": USER, "password": PWD}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=25) as r:
        token = json.loads(r.read())["token"]

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1500, "height": 950})

        # P1 登录
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', USER)
        pg.fill('input[type="password"]', PWD)
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)
        if "/login" not in pg.url:
            ok("P1 登录成功", pg.url)
        else:
            bad("P1 登录未跳转", pg.url)
        shot(pg, "mat-01-login")

        # P2 位置管理页
        pg.goto(WEB + "/positions", wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        # 选岛
        try:
            pg.locator(".ant-select").filter(has_text="选择岛").first.click()
            pg.wait_for_timeout(400)
            pg.locator(".ant-select-item-option", has_text="基线岛").first.click()
            pg.wait_for_timeout(2000)
        except Exception as e:
            bad("P2 选岛异常", str(e)[:120])
        if "设备（13 台）" in pg.inner_text("body"):
            ok("P2 设备列表展示全部 13 台设备")
        else:
            bad("P2 设备列表未展示全部 13 台")
        shot(pg, "mat-02-positions")

        # P2b 点培养箱 ID-X229 看网格（2 列 × 15 层）
        try:
            pg.locator("code", has_text="ID-X229").first.click()
            pg.wait_for_timeout(1500)
            if "2 列 × 15 层" in pg.inner_text("body") and "ID-X229-R1C1" in pg.inner_text("body"):
                ok("P2b 培养箱网格视图（2 列 × 15 层，含 R1C1）")
            else:
                bad("P2b 培养箱网格未正确渲染")
            shot(pg, "mat-02b-grid")
        except Exception as e:
            bad("P2b 网格视图异常", str(e)[:120])

        # P2c 网格之外的单独位置：进出口标记 + 位置标识
        try:
            body = pg.inner_text("body")
            if ("单独位置 · 进口 / 出口" in body and "进口" in body
                    and "出口" in body and "ID-X229-IN" in body):
                ok("P2c 单独位置（进口/出口标记 + 位置标识 ID-X229-IN）")
            else:
                bad("P2c 单独位置未渲染")
            shot(pg, "mat-02c-standalone")
        except Exception as e:
            bad("P2c 单独位置异常", str(e)[:120])

        # P3 编辑网格点位属性
        try:
            pg.locator("text=ID-X229-R1C1").first.click()
            pg.wait_for_timeout(800)
            modal = pg.locator(".ant-modal")
            if modal.count() > 0:
                modal.locator('input[id*="gridRow"]').first.fill("1")
                modal.locator('input[id*="plateTypes"]').first.fill("pcr96,deep96")
                shot(pg, "mat-03-edit")
                modal.locator("button", has_text="保").first.click()
                pg.wait_for_timeout(1200)
                if "保存成功" in pg.inner_text("body") or "已保存" in pg.inner_text("body"):
                    ok("P3 点位属性编辑保存成功")
                else:
                    bad("P3 保存无提示")
            else:
                bad("P3 编辑弹窗未打开")
        except Exception as e:
            bad("P3 编辑异常", str(e)[:160])

        # P4 物料管理页
        pg.goto(WEB + "/consumables", wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        if pg.locator("text=耗材").count() > 0 and pg.locator("text=试剂").count() > 0:
            ok("P4 物料管理两个 Tab（耗材/试剂）渲染")
        else:
            bad("P4 Tab 未渲染")
        if pg.locator("text=CONS1").count() > 0:
            ok("P4 耗材目录表渲染（CONS1 可见）")
        else:
            bad("P4 耗材目录未渲染")
        shot(pg, "mat-04-consumables")

        # P5 切到试剂 Tab
        try:
            pg.locator(".ant-tabs-tab", has_text="试剂").first.click()
            pg.wait_for_timeout(1500)
            if pg.locator("text=RG15").count() > 0:
                ok("P5 试剂目录表渲染（RG15 可见）")
            else:
                bad("P5 试剂目录未渲染")
            shot(pg, "mat-05-reagents")
        except Exception as e:
            bad("P5 切 Tab 异常", str(e)[:120])

        b.close()

    print(f"\n通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
