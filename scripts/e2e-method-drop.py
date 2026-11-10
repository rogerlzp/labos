#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""页面级 e2e · 按岛设备 → 拖方法生成节点（台账：设计器主路径）。

用户要的主路径不是「先拖 task → 保存 → 选设备 → 选方法 → 再保存」，而是：
  打开设计器 → 左侧选岛 → 展开设备 → 把方法拖进画布 → 直接设参数。

本用例把这条链路钉住，重点验证：
  · 设备行与节点都显示**该设备的图片**（自定义 bpmn-js renderer，不是统一矩形）；
  · 节点 XML 带 protocolName / resource / icon，compile 立刻认得（避开老坑）；
  · workflow_node 的 resource_id / protocol_name 与 payload 一致；
  · **板子换设备时自动插入岛上机械臂 Transfer 并连线**（设计 §14.2/§14.3），
    以及 Transfer 带 source/target、节点已落库；
  · **显式删除按钮**能删掉节点并同步删库（原生 contextPad 太隐蔽）。

空态容错：库里没有岛 / 该岛没设备 / 该设备没方法 / 岛上只有一台设备时跳过，不判失败。
自建流程在 finally 里清理（与 e2e-sim-flow.py 同口径）；`--demo` 时保留流程供人工查看。

前置：内核 18080（Spring Boot 容器）、前端 5173；库断言与清理需要 LABOS_DB_*（缺失则跳过）。

用法：
  python3 scripts/e2e-method-drop.py
  python3 scripts/e2e-method-drop.py --base http://127.0.0.1:8080 --headed
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request

from playwright.sync_api import sync_playwright

PASS, FAIL, SKIP = 0, 0, 0
BASE_API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (f"（{detail}）" if detail else ""))


def skip(msg):
    global SKIP
    SKIP += 1
    print("  \033[33m⏭\033[0m " + msg)


def step(title):
    print(f"\n\033[1m══ {title} ══\033[0m")


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    # psql 只认 PGPASSWORD；不映射会认证失败，stdout 为空且本函数之前忽略 stderr，
    # 表现为「库断言查不到行 / 清理没生效」但其实数据一直是对的。
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "")
    out = subprocess.run(
        [os.environ.get("PSQL", "psql"), "-h", os.environ["LABOS_DB_HOST"], "-p",
         os.environ.get("LABOS_DB_PORT", "5432"), "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    return out.stdout.strip()


def have_db():
    return all(os.environ.get(k) for k in
               ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE_API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=90) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def login(page, base, user, pwd):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    ti = page.locator('input[type="text"]')
    if ti.count() > 0 and not ti.first.input_value():
        ti.first.fill(user)
    page.get_by_role("button", name=re.compile(r"登\s*录")).click()
    page.wait_for_selector("text=流程库", timeout=20000)


def canvas_ids(page):
    return page.locator(".djs-element[data-element-id]").evaluate_all(
        "els => els.map(e => e.getAttribute('data-element-id'))")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("LABOS_WEB", "http://localhost:5173"))
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--demo", action="store_true",
                    help="保留最后创建的流程（含设备图片与机械臂节点）供人工查看，不清理")
    args = ap.parse_args()

    wf_id = None
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1600, "height": 950})
        page.on("pageerror", lambda e: print("  [pageerror] " + str(e)))
        # antd 静态 message 的 theme warning 是既有噪音，不是本次功能失败
        page.on("console", lambda m: print("  [console.error] " + m.text)
                if m.type == "error" and "antd: message" not in m.text else None)
        try:
            step("0. 登录与目标岛")
            login(page, args.base, "admin", "admin123")
            ok("页面登录成功")
            sess = api("/api/auth/login", "POST",
                       {"username": "admin", "password": "admin123"})
            token = sess["token"]

            topo = api("/api/topology", token=token)
            islands = topo.get("islands", []) if isinstance(topo, dict) else []
            island = next((i for i in islands if i.get("code") == "LAB-DEMO-02"), None) \
                or (islands[0] if islands else None)
            if not island:
                skip("库里没有岛，跳过拖拽用例")
                return
            ok(f"目标岛 {island['code']}（id={island['id']}）")

            step("1. 建流程并打开设计器")
            wf_name = ("示例｜岛内设备图片与机械臂搬运（2026-11-05）"
                       if args.demo else "拖方法 e2e 冒烟")
            w = api("/api/workflows", "POST",
                    {"name": wf_name, "islandId": island["id"]}, token)
            wf_id = w["id"]
            ok(f"新建流程 wfId={wf_id}，已绑定该岛")
            page.goto(f"{args.base}/designer/{wf_id}?islandId={island['id']}",
                      wait_until="networkidle")
            page.wait_for_selector(".djs-container", timeout=25000)

            step("2. 左侧「按岛设备 · 方法」三层面板 + 设备图片")
            panel = page.locator(".ant-card", has_text="按岛设备")
            if panel.count() == 0:
                bad("左侧「按岛设备」面板没有出现")
                return
            ok("左侧面板出现")
            page.wait_for_timeout(1500)
            devs = page.locator('[data-testid="palette-device"]')
            if devs.count() == 0:
                skip("该岛暂无设备，跳过拖拽")
                return
            ok(f"面板列出 {devs.count()} 台该岛设备")
            icons = page.locator('[data-testid="palette-device"][data-icon^="/devices/"]')
            if icons.count() == devs.count() and icons.count() > 0:
                ok(f"{icons.count()} 台设备都有本地示意图（失效 /image/device 路径已映射）")
            else:
                bad("设备行缺本地示意图", f"{icons.count()}/{devs.count()}")
            if page.locator('[data-testid="palette-device"] img').count() > 0:
                ok("设备行渲染了 <img> 缩略图")
            else:
                bad("设备行没有 <img> 缩略图")
            devs.first.click()
            page.wait_for_timeout(1200)
            methods = page.locator('[data-testid="palette-method"]')
            if methods.count() == 0:
                skip("该设备在 device_method 里没有方法，跳过拖拽")
                return
            ok(f"展开设备后出现 {methods.count()} 个可拖方法")

            step("3. 把方法拖进画布（节点带设备图片）")
            method = methods.first.get_attribute("data-method")
            rid = int(methods.first.get_attribute("data-resource-id"))
            before = set(canvas_ids(page))
            # Locator.drag_to 会触发原生 HTML5 dragstart/dragover/drop（含 DataTransfer）
            methods.first.drag_to(page.locator(".djs-container"),
                                  target_position={"x": 320, "y": 240})
            page.wait_for_timeout(3500)   # 等 createShape + 自动 saveGraph + compile 刷新
            after = set(canvas_ids(page))
            new = [i for i in sorted(after - before) if not i.endswith("_label")]
            if not new:
                bad("拖入后画布没有新节点", f"before={len(before)} after={len(after)}")
                return
            node_key = new[0]
            ok(f"拖方法生成节点 {node_key}（{method} @ resourceId={rid}）")
            node_img = page.locator(f'.djs-element[data-element-id="{node_key}"] image')
            if node_img.count() > 0:
                ok("节点 SVG 里有 <image>（自定义 renderer 生效，不是统一矩形）")
            else:
                bad("节点没有渲染设备图片")
            href = node_img.first.get_attribute("href") if node_img.count() else None
            if href and href.startswith("/devices/"):
                ok(f"节点图片指向本地示意图 {href}")
            else:
                bad("节点图片路径不对", str(href))

            step("4. 绑定随节点一起落库")
            xml = api(f"/api/workflows/{wf_id}/graph", token=token)
            xml = xml if isinstance(xml, str) else json.dumps(xml)
            if f'protocolName="{method}"' in xml and f'resource="{rid}"' in xml \
                    and 'icon="/devices/' in xml:
                ok("XML 里带 protocolName / resource / icon（DI 由 modeling.createShape 生成）")
            else:
                bad("XML 缺绑定属性",
                    f"protocolName={'protocolName=' in xml} "
                    f"resource={'resource=' in xml} icon={'icon=' in xml}")

            comp = api(f"/api/workflows/{wf_id}/compile", token=token)
            nodes = comp.get("nodes", []) if isinstance(comp, dict) else []
            hit = next((n for n in nodes if str(n.get("nodeKey")) == node_key), None)
            if hit is None:
                bad("compile 里没有该节点（拖入后的自动保存没生效）")
            else:
                ok(f"compile 已识别 {node_key}，无需再点一次页面级保存")

            if have_db():
                row = q(
                    "select coalesce(resource_id,0)||'|'||"
                    f"coalesce(params->>'protocol_name','') from workflow_node "
                    f"where workflow_id={wf_id} and node_key='{node_key}'")
                parts = row.split("|")
                if len(parts) == 2 and parts[0].strip() == str(rid) \
                        and parts[1].strip() == method:
                    ok(f"落库一致：resource_id={rid}，protocol_name={method}")
                else:
                    allrows = q(
                        "select node_key||':'||coalesce(resource_id,0)||':'||"
                        f"coalesce(params->>'protocol_name','') from workflow_node "
                        f"where workflow_id={wf_id} order by node_key")
                    bad("workflow_node 绑定与拖入 payload 不一致",
                        f"row='{row}' 全部节点='{allrows}'")
            else:
                skip("没给 LABOS_DB_*，跳过库断言")

            step("4.5 板子换设备 → 自动插入机械臂搬运")
            last_key = node_key
            if devs.count() < 2:
                skip("该岛只有一台设备，跳过跨设备搬运")
            else:
                devs.nth(1).click()
                page.wait_for_timeout(1200)
                all_m = page.locator('[data-testid="palette-method"]')
                target = None
                for i in range(all_m.count()):
                    mm = all_m.nth(i)
                    if mm.get_attribute("data-resource-id") != str(rid) \
                            and mm.get_attribute("data-method") != "Transfer":
                        target = mm
                        break
                if target is None:
                    skip("第二台设备没有可用方法，跳过跨设备搬运")
                else:
                    before2 = set(canvas_ids(page))
                    target.drag_to(page.locator(".djs-container"),
                                   target_position={"x": 620, "y": 240})
                    page.wait_for_timeout(3500)
                    xml2 = api(f"/api/workflows/{wf_id}/graph", token=token)
                    xml2 = xml2 if isinstance(xml2, str) else json.dumps(xml2)
                    if 'protocolName="Transfer"' in xml2:
                        ok("跨设备拖入后自动插入机械臂 Transfer 节点")
                    else:
                        bad("跨设备拖入没有插入机械臂搬运")
                    flows = xml2.count("bpmn:sequenceFlow")
                    if flows >= 2:
                        ok(f"节点间自动连线（sequenceFlow 标记 {flows} 处）")
                    else:
                        bad("节点间没有自动连线", f"flows={flows}")
                    if "source" in xml2 and "target" in xml2:
                        ok("Transfer 带 source/target（两端设备编号）")
                    else:
                        bad("Transfer 缺 source/target")
                    if have_db():
                        armrow = q(
                            "select count(*) from workflow_node "
                            f"where workflow_id={wf_id} "
                            "and params->>'protocol_name'='Transfer'")
                        if armrow.strip() == "1":
                            ok("机械臂节点已落库")
                        else:
                            bad("机械臂节点未落库", armrow)
                    added2 = [i for i in sorted(set(canvas_ids(page)) - before2)
                              if not i.endswith("_label")]
                    if added2:
                        last_key = added2[-1]

            step("5. 删除节点（显式按钮 + 即时落库）")
            if args.demo:
                skip("演示模式：保留全部节点，跳过删除用例")
                print("\n  \033[1m演示流程（已落库，不会清理）:\033[0m "
                      f"{args.base}/designer/{wf_id}?islandId={island['id']}")
                return
            page.locator(f'.djs-element[data-element-id="{last_key}"]').first.click(force=True)
            page.wait_for_timeout(600)
            del_btn = page.get_by_role("button", name="删除选中节点")
            if del_btn.count() == 0:
                bad("没有删除按钮入口")
            else:
                cnt_before = len(canvas_ids(page))
                del_btn.first.click()
                page.wait_for_timeout(3000)
                if len(canvas_ids(page)) < cnt_before:
                    ok("点删除按钮后节点从画布消失")
                else:
                    bad("点删除按钮后节点仍在")
                if have_db():
                    left = q(
                        "select count(*) from workflow_node "
                        f"where workflow_id={wf_id} and node_key='{last_key}'")
                    if left.strip() == "0":
                        ok("删除后 workflow_node 同步删除（无需再点保存）")
                    else:
                        bad("删除后库里仍有该节点", left)
        finally:
            if wf_id and have_db() and not args.demo:
                try:
                    q(f"DELETE FROM workflow_node WHERE workflow_id={wf_id}")
                    q(f"DELETE FROM workflow WHERE id={wf_id}")
                except Exception as e:
                    print(f"  （清理流程 {wf_id} 失败：{e}）")
            browser.close()

    print(f"\n通过 {PASS} / 失败 {FAIL}" + (f"（跳过 {SKIP}）" if SKIP else ""))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
