#!/usr/bin/env python3
"""页面级流程搭建（真实 bpmn-js 建模，落库带 DI，画布可渲染）。

用法：
  python3 scripts/s2-designer-build.py 1000790 xorbranch   # 在已有流程里重建
  python3 scripts/s2-designer-build.py NEW xorbranch      # 新建流程再建
  python3 scripts/s2-designer-build.py NEW seq --n 10      # 顺序链

内置 flow spec：
  xorbranch : S → 异或分叉 → [机器人1, 机器人2] → 异或汇合 → E
  seq       : S → T1 → ... → Tn → E（设备轮换）
"""
import json
import sys
import time

from playwright.sync_api import sync_playwright

BASE = "http://localhost:5173"
DEVICES = [
    ("Home", "200231"),        # 机器人1 XRobot A10LR-1 (TRANSFER)
    ("ReadBarcode", "200200"),  # 读码 (READ_PLATE)
    ("RunProgram", "504831"),   # 离心 (CENTRIFUGE)
    ("Seal", "504830"),         # 封膜 (SEAL)
    ("Incubate", "200229"),     # 孵育 (INCUBATE)
]
ROBOT2 = ("Home", "504829")     # 机器人2 XRobot A10LR #1 (TRANSFER)

BUILD_JS = r"""
(spec) => {
  const m = window.__bpmnModeler;
  const ef = m.get('elementFactory');
  const modeling = m.get('modeling');
  const canvas = m.get('canvas');
  const root = canvas.getRootElement();
  // 先清空旧图（避免 ID 冲突）：删所有非根元素
  const registry = m.get('elementRegistry');
  const olds = registry.getAll().filter((el) => el !== root && el.type !== 'label');
  if (olds.length) { modeling.removeElements(olds); }
  function attrs(bo, kv) {
    let a = bo.$attrs;
    if (!a) { a = {}; Object.defineProperty(bo, '$attrs', { value: a, writable: true, configurable: true, enumerable: false }); }
    Object.assign(a, kv);
  }
  function node(id, type, name, x, y, extra) {
    const s = ef.createShape({ type, id });
    if (name) s.businessObject.name = name;
    if (extra) { for (const [k, v] of Object.entries(extra)) { if (k === 'protocolName' || k === 'resource' || k === 'methodParams') attrs(s.businessObject, { [k]: v }); else s.businessObject[k] = v; } }
    modeling.createShape(s, { x, y }, root);
    return s;
  }
  const made = {};
  for (const n of spec.nodes) { made[n.id] = node(n.id, n.type, n.name, n.x, n.y, n.extra); }
  for (const e of spec.edges) { modeling.connect(made[e.from], made[e.to]); }
  return Object.keys(made);
}
"""


def xorbranch():
    return {
        "nodes": [
            {"id": "S", "type": "bpmn:StartEvent", "name": "开始", "x": 80, "y": 180},
            {"id": "X", "type": "bpmn:ExclusiveGateway", "name": "XOR_SPLIT", "x": 220, "y": 180,
             "extra": {"gatewayDirection": "Diverging"}},
            {"id": "R1", "type": "bpmn:Task", "name": "Home", "x": 380, "y": 80,
             "extra": {"protocolName": "Home", "resource": "200231", "methodParams": "{}"}},
            {"id": "R2", "type": "bpmn:Task", "name": "Home", "x": 380, "y": 280,
             "extra": {"protocolName": "Home", "resource": "504829", "methodParams": "{}"}},
            {"id": "M", "type": "bpmn:ExclusiveGateway", "name": "XOR_MERGE", "x": 540, "y": 180,
             "extra": {"gatewayDirection": "Converging"}},
            {"id": "E", "type": "bpmn:EndEvent", "name": "结束", "x": 680, "y": 180},
        ],
        "edges": [
            {"from": "S", "to": "X"},
            {"from": "X", "to": "R1"},
            {"from": "X", "to": "R2"},
            {"from": "R1", "to": "M"},
            {"from": "R2", "to": "M"},
            {"from": "M", "to": "E"},
        ],
    }


def seq(n):
    nodes = [{"id": "S", "type": "bpmn:StartEvent", "name": "开始", "x": 80, "y": 180}]
    edges = []
    prev = "S"
    for i in range(1, n + 1):
        method, res = DEVICES[(i - 1) % len(DEVICES)]
        nodes.append({"id": f"T{i}", "type": "bpmn:Task", "name": method,
                      "x": 80 + i * 160, "y": 180,
                      "extra": {"protocolName": method, "resource": res, "methodParams": "{}"}})
        edges.append({"from": prev, "to": f"T{i}"})
        prev = f"T{i}"
    nodes.append({"id": "E", "type": "bpmn:EndEvent", "name": "结束", "x": 80 + (n + 1) * 160, "y": 180})
    edges.append({"from": prev, "to": "E"})
    return {"nodes": nodes, "edges": edges}


def main():
    wf_id = sys.argv[1] if len(sys.argv) > 1 else "NEW"
    kind = sys.argv[2] if len(sys.argv) > 2 else "xorbranch"
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    spec = xorbranch() if kind == "xorbranch" else seq(n)

    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 1600, "height": 1000})
        # 登录
        pg.goto(BASE + "/login")
        pg.fill('input[autocomplete="username"]', "admin")
        pg.fill('input[autocomplete="current-password"]', "admin123")
        pg.click('button[type="submit"]')
        pg.wait_for_timeout(1500)

        # 新建或打开
        if wf_id == "NEW":
            pg.goto(BASE + "/workflows")
            pg.wait_for_timeout(1200)
            # 点「新建流程」按钮
            pg.get_by_role("button", name="新建").first.click()
            pg.wait_for_timeout(1500)
            wf_id = pg.url.rstrip("/").split("/")[-1]
            print("新建流程 id =", wf_id)
        else:
            pg.goto(BASE + f"/designer/{wf_id}")

        # 等 modeler 就绪 + 图导入完成（有 process 根）
        pg.wait_for_function("window.__bpmnModeler != null", timeout=20000)
        pg.wait_for_function(
            "() => { const m=window.__bpmnModeler; if(!m) return false;"
            " const c=m.get('canvas'); const r=c.getRootElement();"
            " return r && r.type==='bpmn:Process'; }",
            timeout=20000,
        )
        pg.wait_for_timeout(600)

        # 建模（spec 作为参数传入）
        made = pg.evaluate(BUILD_JS, spec)
        print("已建节点:", made)
        pg.wait_for_timeout(800)

        # 保存（走页面保存按钮）
        pg.get_by_role("button", name="保存").click()
        pg.wait_for_timeout(2500)

        pg.screenshot(path=f"private/s2-screenshots/designer-{wf_id}-{kind}.png", full_page=True)
        print(f"已建流程 wf={wf_id} kind={kind} nodes={len(spec['nodes'])} edges={len(spec['edges'])}")
        print(f"截图 private/s2-screenshots/designer-{wf_id}-{kind}.png")
        b.close()


if __name__ == "__main__":
    main()
