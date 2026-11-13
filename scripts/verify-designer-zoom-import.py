#!/usr/bin/env python3
"""验证设计器的**画布视图**（缩放 / 适应 / 全屏）与**导入流程规格**。

两条链路都是"流程一多就没法手工拖"的解法，必须真的能用才算数：
  ① 缩放/全屏：点按钮 → 比例真的变、视口真的重排、全屏真的铺满；
  ② 导入规格：贴一份 Flow Spec → 画布真的长出节点 → 真的落库 → compile/preflight 过。

用法：
  python3 scripts/verify-designer-zoom-import.py
  python3 scripts/verify-designer-zoom-import.py --headed
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
OUT = ROOT / "private" / "s2-screenshots"

# 一条带分支的规格：异或分叉 → 两条支路 → 异或汇合（同时覆盖网关与自动布局）
BRANCH_SPEC = {
    "name": "验证-读码离心封膜带分支",
    "nodes": [
        {"id": "S", "type": "start"},
        {"id": "READ", "type": "method", "code": "ID-1021", "method": "ReadBarcode"},
        {"id": "G1", "type": "exclusive"},
        {"id": "SPIN", "type": "method", "code": "ID-ASPIN_2K-1", "method": "RunProgram"},
        {"id": "SEAL", "type": "method", "code": "ID-S100-1", "method": "Seal"},
        {"id": "G2", "type": "exclusive"},
        {"id": "E", "type": "end"},
    ],
    "edges": [
        {"from": "S", "to": "READ"},
        {"from": "READ", "to": "G1"},
        {"from": "G1", "to": "SPIN"},
        {"from": "G1", "to": "SEAL"},
        {"from": "SPIN", "to": "G2"},
        {"from": "SEAL", "to": "G2"},
        {"from": "G2", "to": "E"},
    ],
}

FAILED: list[str] = []

# 验证用的流程名（复用同一条，别每次新建 —— 流程没有删除接口）
VERIFY_WF_NAME = "验证-缩放与导入规格"


def check(cond: bool, label: str, detail: str = "") -> bool:
    mark = "✓" if cond else "✗"
    print(f"  {mark} {label}" + (f"  ({detail})" if detail else ""))
    if not cond:
        FAILED.append(label)
    return cond


def api(path: str, method: str = "GET", body=None, token=None, timeout=60):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            text = resp.read().decode()
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"{method} {path} → HTTP {exc.code}: {exc.read().decode()[:300]}")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def ui_login(pg) -> None:
    pg.goto(BASE + "/login", wait_until="domcontentloaded")
    pg.fill('input[autocomplete="username"]', USER)
    pg.fill('input[autocomplete="current-password"]', PWD)
    pg.click('button[type="submit"]')
    pg.wait_for_timeout(1500)
    if "/login" in pg.url:
        raise RuntimeError("页面登录失败")


def wait_designer(pg) -> None:
    pg.wait_for_function("window.__bpmnModeler != null", timeout=25000)
    pg.wait_for_function(
        "() => { const m=window.__bpmnModeler; if(!m) return false;"
        " const r=m.get('canvas').getRootElement();"
        " return r && r.type==='bpmn:Process'; }",
        timeout=25000,
    )
    pg.wait_for_timeout(600)


def zoom_of(pg) -> float:
    return pg.evaluate("() => window.__bpmnModeler.get('canvas').zoom()")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]
    # 复用同一条验证流程（**不要每次新建** —— 流程没有删除接口，
    # 每次新建都会在库里堆一条垃圾）。
    wf_id = 0
    listing = api("/api/workflows?limit=200", token=token)
    for it in (listing.get("items") or []):
        if it.get("name") == VERIFY_WF_NAME:
            wf_id = int(it.get("id"))
            break
    if not wf_id:
        created = api("/api/workflows", "POST",
                      {"name": VERIFY_WF_NAME, "category": "verify"}, token=token)
        wf_id = int(created.get("id") or created.get("workflowId") or 0)
        print(f"新建验证流程 id={wf_id}")
    else:
        print(f"复用验证流程 id={wf_id}")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        pg = browser.new_page(viewport={"width": 1600, "height": 950})
        errors: list[str] = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: errors.append(f"console.{m.type}: {m.text}")
              if m.type == "error" else None)

        ui_login(pg)
        pg.goto(f"{BASE}/designer/{wf_id}", wait_until="domcontentloaded")
        wait_designer(pg)

        # ---------------- ① 导入流程规格 ----------------
        print("\n[①] 导入流程规格")
        pg.get_by_role("button", name="导入流程").click()
        pg.wait_for_timeout(500)
        check(pg.locator('[data-testid="flow-spec-input"]').is_visible(),
              "导入弹窗已打开")

        # 1a. 非法规格要被挡住，且要把错说清楚
        pg.fill('[data-testid="flow-spec-input"]',
                '{"nodes":[{"id":"A","type":"method","code":"不存在设备","method":"X"}],'
                '"edges":[]}')
        pg.get_by_role("button", name="导入并保存").click()
        pg.wait_for_timeout(900)
        err_text = pg.locator(".ant-modal-content").last.inner_text()
        check("不存在" in err_text or "不合法" in err_text,
              "非法规格被挡下并给出原因",
              re.sub(r"\s+", " ", err_text)[-160:])
        pg.screenshot(path=str(OUT / "designer-import-invalid.png"))

        # 1b. 合法规格 → 建图 + 落库
        pg.fill('[data-testid="flow-spec-input"]',
                json.dumps(BRANCH_SPEC, ensure_ascii=False))
        pg.get_by_role("button", name="导入并保存").click()
        pg.wait_for_timeout(2500)

        nodes = pg.evaluate(
            """() => window.__bpmnModeler.get('elementRegistry').getAll()
              .filter(e => e.type !== 'label')
              .map(e => ({ id:e.id, type:e.type,
                           name:e.businessObject && e.businessObject.name }))""")
        kinds = [n["type"] for n in nodes]
        check(len([k for k in kinds if k == "bpmn:Task"]) == 3,
              "画布上出现 3 个任务节点",
              str(sorted(kinds)))
        check(len([k for k in kinds if k == "bpmn:ExclusiveGateway"]) == 2,
              "画布上出现 2 个异或网关")
        check(len([k for k in kinds if k == "bpmn:StartEvent"]) == 1
              and len([k for k in kinds if k == "bpmn:EndEvent"]) == 1,
              "开始/结束事件各一个")

        # 网关自动命名：按拓扑算角色（异或分叉/异或汇合）
        gnames = [n["name"] for n in nodes
                  if n["type"] == "bpmn:ExclusiveGateway"]
        check("XOR_SPLIT" in gnames and "XOR_MERGE" in gnames,
              "网关按拓扑自动命名为中性 code", str(gnames))

        # 落库：XML 里设备节点的 resource 属性（与手工拖拽完全一致）
        xml = api(f"/api/workflows/{wf_id}/graph", token=token)
        if not isinstance(xml, str):
            xml = json.dumps(xml, ensure_ascii=False)
        check(xml.count('resource="') == 3, "XML 里 3 个设备任务都带 resource 属性",
              f'count={xml.count(chr(34) + "resource=" + chr(34))}')
        check('resource="200200"' in xml, "设备绑定落到正确的 resourceId")
        check('protocolName="ReadBarcode"' in xml, "方法名落库（protocolName）")

        # 落库：workflow_node 里要有这些节点（compile 只数设备任务）
        comp = api(f"/api/workflows/{wf_id}/compile", token=token)
        cnodes = comp.get("nodes") or []
        check(len(cnodes) == 3, "compile 出 3 个设备节点",
              f"{len(cnodes)} 节点 / {comp.get('totalSteps')} 步")
        check(all((n.get("steps") or [{}])[0].get("method") for n in cnodes),
              "每个节点都带方法",
              str([(n.get("name"), (n.get("steps") or [{}])[0].get("method"))
                   for n in cnodes]))

        pre = api(f"/api/workflows/{wf_id}/preflight", token=token)
        check(not pre.get("blocking"), "预检无阻断",
              f"errors={pre.get('errors')} warnings={pre.get('warnings')}")

        pg.screenshot(path=str(OUT / "designer-imported.png"))
        print(f"截图：{OUT}/designer-imported.png")

        # ---------------- ② 缩放 / 适应 / 全屏（画布已有内容时测）----------------
        print("\n[②] 画布视图")
        check(pg.locator('[data-testid="canvas-toolbar"]').is_visible(),
              "缩放工具条已渲染")

        z0 = zoom_of(pg)
        pg.click('[aria-label="zoom-in"]')
        pg.wait_for_timeout(400)
        z1 = zoom_of(pg)
        check(z1 > z0, "点「放大」比例真的变大", f"{z0:.2f} → {z1:.2f}")

        # 缩小是**绝对步进**（0.1 一档），不是"回到原比例" ⇒ 只断言变小
        pg.click('[aria-label="zoom-out"]')
        pg.wait_for_timeout(400)
        z2 = zoom_of(pg)
        check(z2 < z1, "点「缩小」比例变小", f"{z1:.2f} → {z2:.2f}")

        # 比例文字要跟**当前**缩放一致
        label = pg.locator('[data-testid="canvas-toolbar"]').inner_text()
        check(f"{round(z2 * 100)}%" in label, "工具条显示当前缩放比例",
              f"zoom={z2:.2f} 文案={re.sub(r'\\s+', ' ', label).strip()}")

        # 滚轮缩放后，比例文字要跟着变（viewbox.changed 订阅生效）
        box = pg.locator(".djs-container").bounding_box()
        pg.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
        pg.mouse.wheel(0, -300)
        pg.wait_for_timeout(500)
        z3 = zoom_of(pg)
        label2 = pg.locator('[data-testid="canvas-toolbar"]').inner_text()
        check(f"{round(z3 * 100)}%" in label2,
              "滚轮缩放后比例文字同步更新",
              f"zoom={z3:.2f} 文案={re.sub(r'\\s+', ' ', label2).strip()}")

        # 「适应画布」：视口要真的重排（用整个 viewbox 比，不只看比例 ——
        # 缩放比例不变但平移了也算重排）
        vb_before = pg.evaluate(
            "() => { const v=window.__bpmnModeler.get('canvas').viewbox();"
            " return {x:Math.round(v.x),y:Math.round(v.y),"
            " w:Math.round(v.width),h:Math.round(v.height)}; }")
        pg.get_by_role("button", name="适应画布").click()
        pg.wait_for_timeout(700)
        vb_after = pg.evaluate(
            "() => { const v=window.__bpmnModeler.get('canvas').viewbox();"
            " return {x:Math.round(v.x),y:Math.round(v.y),"
            " w:Math.round(v.width),h:Math.round(v.height)}; }")
        check(vb_before != vb_after, "「适应画布」重排了视口",
              f"{vb_before} → {vb_after}")

        # 全屏
        pg.click('[aria-label="fullscreen"]')
        pg.wait_for_timeout(900)
        fs = pg.evaluate("() => !!document.fullscreenElement")
        check(fs, "点「全屏」进入全屏")
        if fs:
            size = pg.evaluate(
                "() => { const e=document.fullscreenElement;"
                " const r=e.getBoundingClientRect();"
                " return {w:Math.round(r.width), h:Math.round(r.height)}; }")
            check(size["w"] >= 1500, "全屏铺满宽度", f"{size['w']}×{size['h']}")
            # 全屏后画布必须重排（canvas.resized）
            z5 = zoom_of(pg)
            check(z5 > 0, "全屏后画布仍可渲染", f"zoom={z5:.2f}")
            pg.screenshot(path=str(OUT / "designer-fullscreen.png"))
            pg.click('[aria-label="fullscreen"]')
            pg.wait_for_timeout(700)
            check(not pg.evaluate("() => !!document.fullscreenElement"),
                  "再点一次退出全屏")

        # ---------------- ③ 无 JS 报错 ----------------
        print("\n[③] 运行时")
        # antd 的 "Static function can not consume context" 是**全站既有**的
        # console.error（页面到处用 message.xxx 静态方法），与本次改动无关 —— 过滤掉。
        real = [e for e in errors
                if "Static function can not consume context" not in e]
        check(not real, "页面无 JS 报错", "; ".join(real[:3]))

        browser.close()

    print("\n" + ("全部通过 ✅" if not FAILED else f"失败 {len(FAILED)} 项 ❌: {FAILED}"))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())
