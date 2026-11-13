#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自动对齐「前后端对齐」诊断：把每个图元的 id/类型/x/y 打出来。

用途：流程 1000830 上「同一条分支上的节点 x 对齐」断言失败时，
定位到底是**排版错位**（真 bug）还是**脚本断言口径**与实现不一致。

只读：不保存、不改库，只点「自动对齐」再读坐标。

用法：
  python3 scripts/diag-align-lanes.py
"""
import json
import os
import re
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
WF = os.environ.get("LABOS_WF", "1000830")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "private", "workflows-review")


def api(path, method="GET", body=None, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    data = json.dumps(body).encode() if body is not None else None
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=30) as r:
        txt = r.read().decode()
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        return txt          # /graph 返回的是 BPMN XML，不是 JSON


def main():
    os.makedirs(OUT, exist_ok=True)
    tok = api("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    xml = api(f"/api/workflows/{WF}/graph", tok=tok)
    import xml.etree.ElementTree as ET
    root = ET.fromstring(xml if isinstance(xml, str) else json.dumps(xml))
    name_of, type_of = {}, {}
    for e in root.iter():
        tag = e.tag.split("}")[-1]
        if tag in ("task", "exclusiveGateway", "parallelGateway", "inclusiveGateway",
                   "startEvent", "endEvent"):
            name_of[e.get("id")] = e.get("name") or e.get("id")
            type_of[e.get("id")] = tag

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1440, "height": 900})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.locator('input[type="password"]').first.fill(PWD)
        pg.get_by_role("button", name="登 录").click()
        pg.wait_for_timeout(2500)
        pg.goto(f"{WEB}/designer/{WF}", wait_until="networkidle")
        pg.wait_for_timeout(5000)
        pg.get_by_role("button", name=re.compile("自动对齐")).first.click()
        pg.wait_for_timeout(3000)
        els = pg.evaluate("""() => {
          const reg = window.__bpmnModeler.get('elementRegistry');
          return reg.getAll().filter(e => e.type !== 'bpmn:SequenceFlow')
            .map(e => ({ id: e.id, type: e.type, name: e.businessObject.name || '',
                         x: Math.round(e.x), y: Math.round(e.y),
                         w: e.width, h: e.height }));
        }""")
        b.close()

    rows = {}
    for e in els:
        rows.setdefault(e["y"], []).append(e)
    print(f"=== {WF} 自动对齐后布局（共 {len(els)} 个图元）===")
    for y in sorted(rows):
        lane = sorted(rows[y], key=lambda e: e["x"])
        print(f"\n行 y={y}  （{len(lane)} 个）")
        for e in lane:
            tag = type_of.get(e["id"], e["type"])
            print(f"   x={e['x']:>5}  {tag:<20} {e['id']:<26} {name_of.get(e['id'],'')}")


if __name__ == "__main__":
    main()
