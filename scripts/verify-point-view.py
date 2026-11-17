#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0 · 点位视角设计器的页面级验收（真浏览器 + 真 API，跑完自清理）。

验收四点（对应 docs/architecture/点位视角设计器-设计_20261116.md §8 P0）：
  ① 左侧出现「点位视角」tab，能看到管理员预配的点位×方法；
  ② 拖「DISP1-PLATE · Run」到画布 → 图上 1 个节点；
  ③ **图里 0 个机械臂节点**（搬运归执行期，不是应用者画的）；
  ④ 该节点自动写入 1 条 node_binding（positionId/methodId/装板/扫码都对）。

用法（需本机服务在跑：后端 18080、前端 5173）：
    python3 scripts/verify-point-view.py

⚠ 会创建一条 `实测·点位视角P0-*` 测试流程，**结束时调用 cleanup-test-workflows.py
   --only-ids 自清理**（库里没有 DELETE /api/workflows 端点）。中途 Ctrl-C 也可能残留，
   用同一脚本按名字清理即可。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
WEB = os.environ.get("LABOS_WEB", "http://127.0.0.1:5173").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ISLAND_CODE = "LAB-EXT-01"
POINT_CODE = "DISP1-PLATE"
METHOD_NAME = "Run"


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    payload = json.dumps(body).encode() if body is not None else None
    if payload:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=payload, timeout=20) as r:
        return json.loads(r.read() or b"{}")


def check(cond, msg, detail=""):
    print(("  ✔ " if cond else "  ✘ ") + msg + ((" — " + str(detail)) if detail and not cond else ""))
    if not cond:
        raise AssertionError(msg + " " + str(detail))


def cleanup(wid, code, token):
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "cleanup-test-workflows.py")
    subprocess.run([sys.executable, script, "--apply", "--only-ids", str(wid)],
                   capture_output=True, text=True, check=False)
    # 清理脚本连的是 **LABOS_DB_*** 指向的库，而后端可能连着另一套库
    # （本机实测：后端连 192.168.124.2，清理默认连 127.0.0.1 ⇒ 删了个寂寞）。
    # 所以这里按**流程编码**回查一次 API（编码唯一，不会误判），确认真的删掉了。
    still_there = True
    try:
        lst = api(f"/api/workflows?limit=20&q={code}", token=token)
        still_there = int(lst.get("total", 0)) > 0
    except Exception as e:
        print("  ⚠ 清理结果回查失败：", e)
    if still_there:
        print(f"  ⚠ 测试流程未删除（清理脚本连的库与后端不是同一套）：{wid}")
        print("     请用后端那套库重跑：")
        print(f"     LABOS_DB_HOST=... LABOS_DB_USER=... LABOS_DB_PASS=... \\")
        print(f"       python3 scripts/cleanup-test-workflows.py --apply --only-ids {wid}")
    else:
        print("  测试流程已清理：", wid)


def main():
    token = api("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    topo = api("/api/topology", token=token)
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    check(island is not None, f"岛 {ISLAND_CODE} 存在")
    island_id = island["id"]

    positions = api(f"/api/positions?islandId={island_id}", token=token).get("items", [])
    point = next((p for p in positions if p.get("code") == POINT_CODE), None)
    check(point is not None, f"点位 {POINT_CODE} 存在（管理员已配）")
    point_id = point["pointId"]
    methods = api(f"/api/positions/{point_id}/methods", token=token).get("bindings", [])
    method = next((m for m in methods if m.get("methodName") == METHOD_NAME), None)
    check(method is not None, f"点位 {POINT_CODE} 允许方法 {METHOD_NAME}")
    method_id = method["methodId"]

    ts = int(time.time())
    code = f"POSVIEW-P0-{ts}"
    wf = api("/api/workflows", "POST", {
        "code": code,
        "name": f"实测·点位视角P0-{ts}",
        "category": "verify",
        "islandId": island_id,
    }, token=token)
    wid = wf.get("id") or wf.get("workflow", {}).get("id")

    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            page = browser.new_page(viewport={"width": 1600, "height": 1000})
            page_errors = []
            page.on("pageerror", lambda e: page_errors.append(str(e)[:200]))

            page.goto(WEB + "/login", wait_until="domcontentloaded")
            page.wait_for_timeout(900)
            page.locator('input[type="password"]').first.fill(PWD)
            page.get_by_role("button", name="登 录").click()
            page.wait_for_timeout(1600)
            page.goto(WEB + f"/designer/{wid}?islandId={island_id}",
                      wait_until="domcontentloaded")
            tab = page.get_by_role("tab", name="点位视角")
            tab.wait_for(timeout=15000)
            check(True, "① 设计器出现「点位视角」tab")
            tab.click()
            page.wait_for_timeout(600)

            row = page.locator(f'[data-testid="palette-position"][data-position-id="{point_id}"]')
            row.wait_for(timeout=10000)
            row.click()
            page.wait_for_timeout(900)
            method_row = page.locator(
                f'[data-testid="palette-position-method"][data-method="{METHOD_NAME}"]'
                f'[data-position-id="{point_id}"]')
            method_row.wait_for(timeout=10000)
            check(True, "① palette 列出点位 × 允许的方法")

            canvas = page.locator('[data-testid="designer-canvas-wrap"]')
            canvas.wait_for()
            method_row.drag_to(canvas, target_position={"x": 500, "y": 300})
            comp = {}
            for _ in range(20):
                page.wait_for_timeout(500)
                comp = api(f"/api/workflows/{wid}/compile", token=token)
                if comp.get("nodes"):
                    break
            nodes = comp.get("nodes", [])
            check(len(nodes) == 1, "② 拖出后图上 1 个节点",
                  [n.get("name") for n in nodes])

            arm = [n for n in nodes
                   if "ARM_TRANSFER" in str(n.get("name", ""))
                   or any(s.get("method") == "Transfer" for s in n.get("steps", []))]
            check(not arm, "③ 图上没有机械臂节点", arm)

            binds = []
            for n in nodes:
                binds += api(f"/api/workflows/{wid}/nodes/{n['nodeKey']}/bindings",
                             token=token).get("bindings", [])
            check(len(binds) == 1, "④ 节点自动写入 1 条绑定", binds)
            b = binds[0]
            check(b.get("positionId") == point_id, "④ 绑定点位正确", b.get("positionId"))
            check(b.get("methodId") == method_id, "④ 绑定方法正确", b.get("methodId"))
            check(b.get("loadingPattern") == "load_single", "④ 装板方式默认正确",
                  b.get("loadingPattern"))
            check(b.get("barcodePolicy") == "validate_in", "④ 扫码策略默认正确",
                  b.get("barcodePolicy"))
            check(not page_errors, "无页面未捕获异常", page_errors)
            browser.close()
        print("PASS · P0 点位视角拖放 + 自动绑定 + 无机械臂节点")
    finally:
        cleanup(wid, code, token)


if __name__ == "__main__":
    main()
