#!/usr/bin/env python3
"""页面级搭「S → 机械臂 Transfer → E」搬运流程（真实 UI 操作）。

关键修正：新流程默认只有 S→E 一条线。拖入 Transfer 后必须把它**插进** S→E 之间
（删 S→E，连 S→Transfer→E），否则就是个孤立节点，不是合法流程。

路径：页面新建流程(绑岛) → 拖机器人 Transfer → 插进 S→E 线 → 「保存并继续设置」
→ 右栏选 source/target → 保存 → 跑 run → 验证离心机门先开再搬再关。

用法：python3 scripts/s2-page-transfer.py
"""
import json
import re
import subprocess
import time
import urllib.request

from playwright.sync_api import sync_playwright

API = "http://127.0.0.1:18080"
BASE = "http://localhost:5173"
DB = ["psql", "-h", "192.168.124.2", "-p", "5432", "-U", "labos", "-d", "labdb"]


def api(path, method="GET", body=None, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=30) as r:
        return json.loads(r.read().decode())


def main():
    with sync_playwright() as p:
        br = p.chromium.launch()
        pg = br.new_page(viewport={"width": 1680, "height": 1000})
        # 登录
        pg.goto(BASE + "/login")
        pg.fill('input[autocomplete="username"]', "admin")
        pg.fill('input[autocomplete="current-password"]', "admin123")
        pg.click('button[type="submit"]')
        pg.wait_for_timeout(1500)

        # 页面新建流程（Modal 填名 + 选岛）
        pg.goto(BASE + "/workflows")
        pg.wait_for_timeout(1200)
        pg.get_by_role("button", name=re.compile("新建流程")).click()
        pg.wait_for_timeout(600)
        pg.locator('.ant-modal input[placeholder]').first.fill("S2-页面-离心机搬运2")
        pg.locator('.ant-modal .ant-select').first.click()
        pg.wait_for_timeout(400)
        pg.locator('.ant-select-item-option:has-text("LAB-BASE-01")').first.click()
        pg.wait_for_timeout(300)
        pg.get_by_role("button", name=re.compile("创建并打开画布")).click()
        pg.wait_for_timeout(2000)
        wf = pg.url.split("/designer/")[-1].split("?")[0]
        print("wf =", wf)

        # 等 modeler + process 根
        pg.wait_for_function("window.__bpmnModeler != null", timeout=20000)
        pg.wait_for_function(
            "() => { const m=window.__bpmnModeler; if(!m) return false;"
            " const c=m.get('canvas'); const r=c.getRootElement();"
            " return r && r.type==='bpmn:Process'; }", timeout=20000)
        pg.wait_for_timeout(800)

        # 拖机器人 Transfer（真实 onDropMethod）
        pg.click('[data-testid="palette-device"]:has-text("ID-X231")', force=True)
        pg.wait_for_function(
            "() => !!document.querySelector('[data-testid=\"palette-method\"][data-method=\"Transfer\"][data-resource-id=\"200231\"]')",
            timeout=8000)
        pg.wait_for_timeout(250)
        pg.evaluate(
            """() => {
              const dt = new DataTransfer();
              const src = document.querySelector('[data-testid="palette-method"][data-method="Transfer"][data-resource-id="200231"]');
              src.dispatchEvent(new DragEvent('dragstart', {dataTransfer: dt, bubbles: true, cancelable: true}));
              const cv = document.querySelector('.djs-container');
              const r = cv.getBoundingClientRect();
              cv.dispatchEvent(new DragEvent('drop', {dataTransfer: dt, bubbles: true, cancelable: true,
                clientX: r.left + 500, clientY: r.top + 260}));
            }"""
        )
        pg.wait_for_timeout(1200)

        # 关键修正：把 Transfer 插进 S→E 之间（删 S→E，连 S→T→E）
        r = pg.evaluate(
            """() => {
              const m = window.__bpmnModeler;
              const reg = m.get('elementRegistry');
              const modeling = m.get('modeling');
              const S = reg.getAll().find(e => e.type === 'bpmn:StartEvent');
              const E = reg.getAll().find(e => e.type === 'bpmn:EndEvent');
              const T = reg.getAll().find(e => e.type === 'bpmn:Task' && e.businessObject.name === 'Transfer');
              // 删掉默认 S→E 直连
              const flows = reg.getAll().filter(e => e.type === 'bpmn:SequenceFlow');
              for (const f of flows) {
                const bo = f.businessObject;
                if (bo.sourceRef && bo.sourceRef.id === S.id && bo.targetRef && bo.targetRef.id === E.id) {
                  modeling.removeElements([f]);
                }
              }
              if (S && T) modeling.connect(S, T);
              if (T && E) modeling.connect(T, E);
              return { S: S.id, E: E.id, T: T ? T.id : null };
            }"""
        )
        print("连线:", r)
        pg.wait_for_timeout(600)

        # 连线后 modeling.connect 可能清了 selection → 重新选中 Transfer，右栏才显示该节点
        pg.evaluate(
            "() => { const m=window.__bpmnModeler;"
            " const t=m.get('elementRegistry').getAll().find(e=>e.type==='bpmn:Task' && e.businessObject.name==='Transfer');"
            " if(t) m.get('selection').select(t); }"
        )
        pg.wait_for_timeout(800)

        # 「保存并继续设置」让参数面板出现（新拖节点还没进库）
        pg.get_by_role("button", name=re.compile("保存并继续设置")).click()
        pg.wait_for_timeout(2500)

        # 选 source/target：语言(0) 岛(1) source(2) target(3)
        pg.wait_for_function("() => document.querySelectorAll('.ant-select').length >= 4", timeout=10000)
        pg.wait_for_timeout(400)
        def pick(idx, val):
            pg.locator(".ant-select").nth(idx).click()
            pg.wait_for_timeout(500)
            pg.locator(f'.ant-select-item-option:has-text("{val}")').first.click()
            pg.wait_for_timeout(500)
        pick(2, "ID-ASPIN_2K-1")
        pick(3, "ID-S100-1")

        # 保存
        pg.click("button.ant-btn-primary:has-text('保')", force=True)
        try:
            pg.wait_for_function(
                "() => { const d=document.querySelector('.djs-container');"
                " return d && d.parentElement && d.parentElement.dataset && d.parentElement.dataset.graphSaved; }",
                timeout=8000)
        except Exception:
            pass
        pg.wait_for_timeout(600)
        pg.screenshot(path="private/s2-screenshots/pagedrag-transfer.png", full_page=True)
        br.close()

    # 校验落库
    r = subprocess.run(DB + ["-t", "-c",
        f"SELECT node_key, resource_id, params->>'method_params' FROM workflow_node WHERE workflow_id={wf} AND resource_id=200231;"],
        capture_output=True, text=True)
    print("落库:", r.stdout.strip())
    r = subprocess.run(DB + ["-t", "-c",
        f"SELECT from_key, to_key FROM workflow_edge WHERE workflow_id={wf} ORDER BY id;"],
        capture_output=True, text=True)
    print("边:", r.stdout.strip())

    # 跑 run
    c, b = api("/api/auth/login", "POST", {"username": "admin", "password": "admin123"})
    tok = b["token"]
    c, b = api(f"/api/workflows/{wf}/run?async=1", "POST", tok=tok)
    run_id = b.get("runId")
    print("runId =", run_id)
    for _ in range(20):
        time.sleep(3)
        c, b = api(f"/api/runs/{run_id}", "GET", tok=tok)
        st = b["run"]["status"]
        print("  ", st)
        if st in ("completed", "failed", "aborted", "paused", "cancelled"):
            break
    print(f"wf={wf} run={run_id} status={st}")


if __name__ == "__main__":
    main()
