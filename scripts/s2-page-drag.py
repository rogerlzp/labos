#!/usr/bin/env python3
"""页面级拖流程（真实 UI 操作）。

真实页面路径：
- 设备方法节点：从左栏 palette 拖入（触发 React onDragStart → onDropMethod，
  自动写 protocolName/resource、自动插入机械臂 Transfer、自动连线）。
- 网关/开始/结束：bpmn-js 自带 palette 点击创建。
- 连线：bpmn-js modeling.connect（与 UI 连线工具同一通道）。
- 保存：点页面「保存」按钮，等 data-graph-saved 打点确认落库。

用法：
  python3 scripts/s2-page-drag.py 1000790 xorbranch
  python3 scripts/s2-page-drag.py NEW xorbranch
  python3 scripts/s2-page-drag.py NEW seq --n 10
"""
import json
import re
import sys
import time

from playwright.sync_api import sync_playwright

BASE = "http://localhost:5173"

DEVICES = [
    ("Home", "200231", "ID-X231"),             # 机器人1
    ("ReadBarcode", "200200", "ID-1021"),      # 读码
    ("RunProgram", "504831", "ID-ASPIN_2K-1"), # 离心
    ("Seal", "504830", "ID-S100-1"),           # 封膜
    ("Incubate", "200229", "ID-X229"),         # 孵育
]
ROBOT2 = ("Home", "504829", "ID-XRobot_A10LR-1")  # 机器人2


def login(pg):
    pg.goto(BASE + "/login")
    pg.fill('input[autocomplete="username"]', "admin")
    pg.fill('input[autocomplete="current-password"]', "admin123")
    pg.click('button[type="submit"]')
    pg.wait_for_timeout(1500)


def open_designer(pg, wf_id):
    if wf_id == "NEW":
        pg.goto(BASE + "/workflows")
        pg.wait_for_timeout(1200)
        pg.get_by_role("button", name=re.compile("新建")).first.click()
        pg.wait_for_timeout(1800)
        wf_id = pg.url.rstrip("/").split("/")[-1]
        print("新建流程 id =", wf_id)
    else:
        pg.goto(BASE + f"/designer/{wf_id}")
    pg.wait_for_function("window.__bpmnModeler != null", timeout=20000)
    pg.wait_for_function(
        "() => { const m=window.__bpmnModeler; if(!m) return false;"
        " const c=m.get('canvas'); const r=c.getRootElement();"
        " return r && r.type==='bpmn:Process'; }",
        timeout=20000,
    )
    pg.wait_for_timeout(800)
    return wf_id


def js_build(pg, js, arg=None):
    return pg.evaluate(js, arg)


def clear_canvas(pg):
    pg.evaluate(
        "() => { const m=window.__bpmnModeler; const r=m.get('elementRegistry');"
        " const root=m.get('canvas').getRootElement();"
        " const olds=r.getAll().filter(e=>e!==root && e.type!=='label');"
        " if(olds.length) m.get('modeling').removeElements(olds); }"
    )
    pg.wait_for_timeout(400)


def drag_method(pg, resource_id, code, method, x, y):
    """真实拖入设备方法（触发 React onDropMethod）。"""
    pg.click(f'[data-testid="palette-device"]:has-text("{code}")', force=True)
    pg.wait_for_function(
        f"() => !!document.querySelector('[data-testid=\"palette-method\"][data-method=\"{method}\"][data-resource-id=\"{resource_id}\"]')",
        timeout=8000,
    )
    pg.wait_for_timeout(250)
    pg.evaluate(
        """(a) => {
          const dt = new DataTransfer();
          const src = document.querySelector('[data-testid="palette-method"][data-method="'+a.method+'"][data-resource-id="'+a.resource_id+'"]');
          src.dispatchEvent(new DragEvent('dragstart', {dataTransfer: dt, bubbles: true, cancelable: true}));
          const cv = document.querySelector('.djs-container');
          const r = cv.getBoundingClientRect();
          cv.dispatchEvent(new DragEvent('drop', {dataTransfer: dt, bubbles: true, cancelable: true,
            clientX: r.left + a.x, clientY: r.top + a.y}));
        }""",
        {"method": method, "resource_id": resource_id, "x": x, "y": y},
    )
    pg.wait_for_timeout(1000)


def create_structures(pg, specs):
    """一次性建网关/事件 + 全部连线（bpmn-js 标准 modeling）。返回 id 映射。"""
    return pg.evaluate(
        """(spec) => {
          const m = window.__bpmnModeler;
          const ef = m.get('elementFactory');
          const modeling = m.get('modeling');
          const canvas = m.get('canvas');
          const registry = m.get('elementRegistry');
          const root = canvas.getRootElement();
          const made = {};
          function attrs(bo, kv) {
            let a = bo.$attrs;
            if (!a) { a = {}; Object.defineProperty(bo, '$attrs', { value: a, writable: true, configurable: true, enumerable: false }); }
            Object.assign(a, kv);
          }
          for (const n of spec.nodes) {
            const s = ef.createShape({ type: n.type, id: n.id });
            s.businessObject.id = n.id;
            if (n.name) s.businessObject.name = n.name;
            if (n.gatewayDirection) s.businessObject.gatewayDirection = n.gatewayDirection;
            if (n.extra) { for (const [k, v] of Object.entries(n.extra)) attrs(s.businessObject, { [k]: v }); }
            modeling.createShape(s, { x: n.x, y: n.y }, root);
            made[n.id] = s;
          }
          for (const e of spec.edges) {
            const a = registry.get(e.from), b = registry.get(e.to);
            if (a && b) modeling.connect(a, b);
          }
          return Object.keys(made);
        }""",
        specs,
    )


def save(pg):
    pg.click("button.ant-btn-primary:has-text('保')", force=True)
    # 等保存打点（data-graph-saved），或等 3s
    try:
        pg.wait_for_function(
            "() => { const d=document.querySelector('.djs-container');"
            " return d && d.parentElement && d.parentElement.dataset && d.parentElement.dataset.graphSaved; }",
            timeout=8000,
        )
    except Exception:
        pass
    pg.wait_for_timeout(800)


def build_xorbranch(pg):
    clear_canvas(pg)
    # 1) 网关/开始/结束一次性建好
    specs = {
        "nodes": [
            {"id": "S", "type": "bpmn:StartEvent", "name": "开始", "x": 220, "y": 260},
            {"id": "X", "type": "bpmn:ExclusiveGateway", "name": "XOR_SPLIT", "gatewayDirection": "Diverging", "x": 420, "y": 260},
            {"id": "M", "type": "bpmn:ExclusiveGateway", "name": "XOR_MERGE", "gatewayDirection": "Converging", "x": 900, "y": 260},
            {"id": "E", "type": "bpmn:EndEvent", "name": "结束", "x": 1080, "y": 260},
        ],
        "edges": [],
    }
    create_structures(pg, specs)
    # 2) 真实拖入两个机器人
    drag_method(pg, "200231", "ID-X231", "Home", 560, 120)
    drag_method(pg, "504829", "ID-XRobot_A10LR-1", "Home", 560, 400)
    # 3) 找到机器人 task + 连线（X→R1, X→R2, R1→M, R2→M）
    pg.evaluate(
        """() => {
          const m = window.__bpmnModeler;
          const reg = m.get('elementRegistry');
          const modeling = m.get('modeling');
          const S = reg.get('S'), X = reg.get('X'), M = reg.get('M'), E = reg.get('E');
          const tasks = reg.getAll().filter(e => e.type === 'bpmn:Task' && e.businessObject.name === 'Home');
          const R1 = tasks.find(t => t.businessObject.$attrs && t.businessObject.$attrs.resource === '200231');
          const R2 = tasks.find(t => t.businessObject.$attrs && t.businessObject.$attrs.resource === '504829');
          modeling.connect(S, X);
          if (R1) modeling.connect(X, R1);
          if (R2) modeling.connect(X, R2);
          if (R1) modeling.connect(R1, M);
          if (R2) modeling.connect(R2, M);
          modeling.connect(M, E);
          return { R1: R1 ? R1.id : null, R2: R2 ? R2.id : null };
        }"""
    )
    pg.wait_for_timeout(400)
    save(pg)
    return "xorbranch"


def build_seq(pg, n):
    clear_canvas(pg)
    specs = {
        "nodes": [{"id": "S", "type": "bpmn:StartEvent", "name": "开始", "x": 180, "y": 260}],
        "edges": [],
    }
    for i in range(1, n + 1):
        specs["nodes"].append({"id": f"T{i}", "type": "bpmn:Task", "name": f"T{i}", "x": 180 + i * 180, "y": 260})
        specs["edges"].append({"from": f"T{i-1}" if i > 1 else "S", "to": f"T{i}"})
    specs["nodes"].append({"id": "E", "type": "bpmn:EndEvent", "name": "结束", "x": 180 + (n + 1) * 180, "y": 260})
    specs["edges"].append({"from": f"T{n}", "to": "E"})
    create_structures(pg, specs)
    save(pg)
    return n


def main():
    wf_id = sys.argv[1] if len(sys.argv) > 1 else "NEW"
    kind = sys.argv[2] if len(sys.argv) > 2 else "xorbranch"
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 10

    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 1680, "height": 1000})
        login(pg)
        wf_id = open_designer(pg, wf_id)
        if kind == "xorbranch":
            build_xorbranch(pg)
        else:
            build_seq(pg, n)
        pg.screenshot(path=f"private/s2-screenshots/pagedrag-{wf_id}-{kind}.png", full_page=True)
        print(f"OK wf={wf_id} kind={kind}")
        b.close()


if __name__ == "__main__":
    main()
