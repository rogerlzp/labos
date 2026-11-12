#!/usr/bin/env python3
"""S2 页面功能验证（Playwright 真实 UI + 截图 + 断言）。

验证项：
  P1 登录进入流程库
  P2 设计器打开（画布存在）
  P3 选中 TASK 节点后出现「物料效果声明」卡
  P4 页面内填 transition 声明（barcode+工艺阶段+膜状态）并保存，后端回读一致
  P5 用例台 /s2-test-cases 可见
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
    os.makedirs(OUT, exist_ok=True)
    pg.screenshot(path=os.path.join(OUT, name + ".png"), full_page=False)


def pick_select(pg, card, nth, option_text):
    """在指定卡片内点第 nth 个 Select，再选 option_text。"""
    sel = card.locator(".ant-select").nth(nth)
    sel.click()
    pg.wait_for_timeout(400)
    pg.locator(".ant-select-item-option", has_text=option_text).first.click()
    pg.wait_for_timeout(400)


def material_card(pg):
    """定位「物料效果声明」这个内层卡（含 head-title 的卡片里，`.last` 是内层、`.first` 是外层「节点设置」）。"""
    return pg.locator(".ant-card").filter(
        has=pg.locator(".ant-card-head-title", has_text="物料效果声明")
    ).last


def main():
    from playwright.sync_api import sync_playwright

    code, body = api("/api/auth/login", "POST", {"username": USER, "password": PWD})
    token = json.loads(body).get("token") if code == 200 else None
    if not token:
        bad("登录", f"HTTP {code}")
        return 1

    wf = os.environ.get("S2_WF", "1000783")
    node = os.environ.get("S2_NODE", "A3")

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1440, "height": 900})

        # P1
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', USER)
        pg.fill('input[type="password"]', PWD)
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)
        if "/login" not in pg.url:
            ok("P1 登录进入流程库", pg.url)
        else:
            bad("P1 登录未跳转", pg.url)
        shot(pg, "01-login")

        # P2
        pg.goto(WEB + f"/designer/{wf}", wait_until="domcontentloaded")
        pg.wait_for_timeout(4000)
        if pg.locator(".djs-container").count() > 0:
            ok("P2 设计器打开（画布存在）")
        else:
            bad("P2 设计器未打开")
        shot(pg, "02-designer")

        # P3 选中 TASK 节点 → 物料效果声明卡
        try:
            pg.locator(f'.djs-element[data-element-id="{node}"]').click()
            pg.wait_for_timeout(1500)
            title = pg.locator(".ant-card-head-title", has_text="物料效果声明")
            if title.count() > 0:
                ok(f"P3 选中 {node} 后出现「物料效果声明」卡")
            else:
                bad("P3 未见物料效果声明卡")
            shot(pg, "03-material-card")
        except Exception as e:
            bad("P3 节点选中异常", str(e)[:160])

        # P4 填 transition 声明 + 保存（scope 到「物料效果声明」这个内层卡）
        try:
            card = material_card(pg)
            # mode Select（内层卡第 0 个 .ant-select）→ transition
            pick_select(pg, card, 0, "transition")
            # barcode 输入（placeholder 为「目标板 barcode（必填）」）
            card.locator('input[placeholder*="barcode"]').first.fill("PL-TRACE-2183")
            # processState（卡内第 1 个 Select）→ 已就绪(ready)
            pick_select(pg, card, 1, "已就绪")
            # sealState（卡内第 2 个 Select）→ sealed
            pick_select(pg, card, 2, "sealed")
            shot(pg, "04-filled")
            # 保存（AntD 两字按钮会自动加空格，文本是「保 存」；直接点卡内第一个 button）
            card.locator("button").first.click()
            pg.wait_for_timeout(1500)
            if "已保存" in pg.inner_text("body"):
                ok("P4 页面保存成功（提示已保存）")
            else:
                bad("P4 页面保存无提示")
            shot(pg, "05-saved")
        except Exception as e:
            bad("P4 表单填写/保存异常", str(e)[:200])

        # P4 后端回读
        try:
            code2, body2 = api(f"/api/workflows/{wf}/nodes/{node}/material-effect", "GET", tok=token)
            d = json.loads(body2) if code2 == 200 else None
            decl = d.get("declaration") if d else None
            if decl and decl.get("mode") == "transition" and decl.get("target", {}).get("barcode") == "PL-TRACE-2183":
                ok("P4 回读：声明已持久化", json.dumps(decl, ensure_ascii=False)[:140])
            else:
                bad("P4 回读失败", f"HTTP {code2} {body2[:140]}")
        except Exception as e:
            bad("P4 回读异常", str(e)[:160])

        # 清理：清除刚保存的声明，避免污染后续流程
        try:
            api(f"/api/workflows/{wf}/nodes/{node}/material-effect", "DELETE", tok=token)
        except Exception:
            pass

        # P5 用例台
        pg.goto(WEB + "/s2-test-cases", wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        if "S2" in pg.inner_text("body") or "用例" in pg.inner_text("body"):
            ok("P5 用例台可见")
        else:
            bad("P5 用例台不可见")
        shot(pg, "06-test-cases")

        b.close()

    print(f"\n通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
