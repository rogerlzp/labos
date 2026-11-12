#!/usr/bin/env python3
"""按 JSON 清单，通过设计器页面批量新建并拖出多个流程。

设备方法始终走真实 React dragstart/drop；网关和连线走 bpmn-js modeling（与页面
建模工具同一通道）。拖放产生的隐式边和自动机械臂节点会被清理，最终图完全由
清单中的 nodes/edges 决定，避免 lastDroppedRef 在首节点和分支场景造成错连。

用法：
  python3 scripts/s2-page-batch.py
  python3 scripts/s2-page-batch.py --only transfer,chain --run
  python3 scripts/s2-page-batch.py --manifest scripts/s2-page-flows.json --headed

默认只搭建并校验；加 --run 才执行流程。环境可用 LABOS_WEB/LABOS_API/
LABOS_USER/LABOS_PASS 覆盖。
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
OUT = ROOT / "private" / "s2-screenshots"

GATEWAY_TYPES = {
    "exclusive": "bpmn:ExclusiveGateway",
    "parallel": "bpmn:ParallelGateway",
    "inclusive": "bpmn:InclusiveGateway",
}
TERMINAL = {"completed", "failed", "aborted", "paused", "cancelled"}


def api(path, method="GET", body=None, token=None, timeout=40):
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
        raise RuntimeError(
            f"{method} {path} → HTTP {exc.code}: {exc.read().decode()[:400]}"
        ) from exc
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def login(pg):
    pg.goto(BASE + "/login", wait_until="domcontentloaded")
    pg.fill('input[autocomplete="username"]', USER)
    pg.fill('input[autocomplete="current-password"]', PWD)
    pg.click('button[type="submit"]')
    pg.wait_for_timeout(1500)
    if "/login" in pg.url:
        raise RuntimeError("页面登录失败")


def wait_designer(pg):
    pg.wait_for_function("window.__bpmnModeler != null", timeout=25000)
    pg.wait_for_function(
        "() => { const m=window.__bpmnModeler; if(!m) return false;"
        " const r=m.get('canvas').getRootElement();"
        " return r && r.type==='bpmn:Process'; }",
        timeout=25000,
    )
    pg.wait_for_timeout(700)


def new_workflow(pg, name, island):
    """通过页面新建流程并绑定岛，返回 workflow id。"""
    pg.goto(BASE + "/workflows", wait_until="domcontentloaded")
    pg.wait_for_timeout(900)
    pg.get_by_role("button", name=re.compile("新建流程")).first.click()
    modal = pg.locator(".ant-modal-content").last
    modal.wait_for(state="visible", timeout=10000)
    modal.locator("input[placeholder]").first.fill(name)
    modal.locator(".ant-select").first.click()
    option = pg.locator(
        ".ant-select-dropdown:visible .ant-select-item-option", has_text=island
    ).first
    option.wait_for(state="visible", timeout=10000)
    option.click()
    modal.locator(".ant-modal-footer button.ant-btn-primary").click()
    pg.wait_for_url("**/designer/*", timeout=25000)
    wait_designer(pg)
    return int(re.search(r"/designer/(\d+)", pg.url).group(1))


def initial_events(pg):
    events = pg.evaluate(
        """() => {
          const all = window.__bpmnModeler.get('elementRegistry').getAll();
          const S = all.find(e => e.type === 'bpmn:StartEvent');
          const E = all.find(e => e.type === 'bpmn:EndEvent');
          return { S: S && S.id, E: E && E.id };
        }"""
    )
    if not events["S"] or not events["E"]:
        raise RuntimeError(f"新流程缺少开始/结束事件：{events}")
    return events


def create_gateways(pg, nodes):
    gateways = [n for n in nodes if n.get("type") in GATEWAY_TYPES]
    if not gateways:
        return
    pg.evaluate(
        """(nodes) => {
          const m = window.__bpmnModeler;
          const factory = m.get('elementFactory');
          const modeling = m.get('modeling');
          const root = m.get('canvas').getRootElement();
          const types = { exclusive:'bpmn:ExclusiveGateway', parallel:'bpmn:ParallelGateway',
                          inclusive:'bpmn:InclusiveGateway' };
          for (const n of nodes) {
            const shape = factory.createShape({ type: types[n.type], id: n.id });
            shape.businessObject.id = n.id;
            shape.businessObject.name = n.name || n.id;
            modeling.createShape(shape, { x:n.x || 400, y:n.y || 260 }, root);
          }
        }""",
        gateways,
    )
    pg.wait_for_timeout(300)


def drag_method(pg, node, ordinal):
    """真实拖入一个设备方法，返回主节点 id；清理隐式生成的额外机械臂。"""
    resource = str(node["resourceId"])
    code = node["code"]
    method = node["method"]
    x = node.get("x", 260 + ordinal * 180)
    y = node.get("y", 260)
    selector = (
        f'[data-testid="palette-method"][data-method="{method}"]'
        f'[data-resource-id="{resource}"]'
    )
    # 设备标题是折叠开关：同一设备第二次拖方法时若已展开，再点会把它收起。
    # 只在目标方法不可见时展开，支持一条流程多次使用同一台机器人。
    if not pg.locator(selector).is_visible():
        pg.click(f'[data-testid="palette-device"]:has-text("{code}")', force=True)
    pg.wait_for_selector(selector, state="visible", timeout=10000)
    before = pg.evaluate(
        "() => window.__bpmnModeler.get('elementRegistry').getAll()"
        ".filter(e => e.type === 'bpmn:Task').map(e => e.id)"
    )
    pg.evaluate(
        """(a) => {
          const src = document.querySelector(a.selector);
          if (!src) throw new Error('palette method not found: '+a.selector);
          const dt = new DataTransfer();
          src.dispatchEvent(new DragEvent('dragstart',
            { dataTransfer:dt, bubbles:true, cancelable:true }));
          const cv = document.querySelector('.djs-container');
          const r = cv.getBoundingClientRect();
          cv.dispatchEvent(new DragEvent('drop', {
            dataTransfer:dt, bubbles:true, cancelable:true,
            clientX:r.left+a.x, clientY:r.top+a.y
          }));
        }""",
        {"selector": selector, "x": x, "y": y},
    )
    pg.wait_for_function(
        """(arg) => {
          const m = window.__bpmnModeler;
          const before = new Set(arg.before);
          return m.get('elementRegistry').getAll().some(e =>
            e.type === 'bpmn:Task' && !before.has(e.id) &&
            e.businessObject.$attrs && e.businessObject.$attrs.resource === arg.resource &&
            e.businessObject.$attrs.protocolName === arg.method);
        }""",
        arg={"before": before, "resource": resource, "method": method},
        timeout=12000,
    )
    pg.wait_for_timeout(2200)  # 等 onDropMethod 的草稿落库与 compile 刷新（大图草稿 saveGraph 慢，留足避免并发死锁）
    made = pg.evaluate(
        """(arg) => {
          const m = window.__bpmnModeler;
          const reg = m.get('elementRegistry');
          const before = new Set(arg.before);
          const fresh = reg.getAll().filter(e => e.type === 'bpmn:Task' && !before.has(e.id));
          const selected = m.get('selection').get().map(e => e.id);
          let primary = fresh.find(e => selected.includes(e.id) &&
            e.businessObject.$attrs && e.businessObject.$attrs.resource === arg.resource &&
            e.businessObject.$attrs.protocolName === arg.method);
          if (!primary) primary = fresh.find(e =>
            e.businessObject.$attrs && e.businessObject.$attrs.resource === arg.resource &&
            e.businessObject.$attrs.protocolName === arg.method);
          if (!primary) throw new Error('cannot identify dropped task');
          // 清单显式声明所有节点；onDropMethod 为上一个设备自动补的 ARM_TRANSFER
          // 不属于清单，删除它。最终还会统一删边，故 prev→cur 隐式边也不会残留。
          const extras = fresh.filter(e => e.id !== primary.id);
          if (extras.length) m.get('modeling').removeElements(extras);
          return { primary:primary.id, removed:extras.map(e => e.id) };
        }""",
        {"before": before, "resource": resource, "method": method},
    )
    return made["primary"]


def normalize_graph(pg, spec, aliases):
    """删除所有隐式边，按清单重连，并恢复清单指定的布局。"""
    return pg.evaluate(
        """(arg) => {
          const m = window.__bpmnModeler;
          const reg = m.get('elementRegistry');
          const modeling = m.get('modeling');
          const moddle = m.get('moddle');
          const ids = arg.aliases;
          const flows = reg.getAll().filter(e => e.type === 'bpmn:SequenceFlow');
          if (flows.length) modeling.removeElements(flows);
          for (const n of arg.nodes) {
            const el = reg.get(ids[n.id]);
            if (!el || n.x === undefined || n.y === undefined) continue;
            modeling.moveShape(el, { x:n.x-el.x, y:n.y-el.y });
          }
          const made = [];
          for (const e of arg.edges) {
            const a = reg.get(ids[e.from]), b = reg.get(ids[e.to]);
            if (!a || !b) throw new Error(`missing edge endpoint ${e.from}→${e.to}`);
            const flow = modeling.connect(a, b);
            if (e.condition) {
              modeling.updateProperties(flow, {
                conditionExpression:moddle.create('bpmn:FormalExpression', { body:e.condition })
              });
            }
            if (e.default) modeling.updateProperties(a, { default:flow.businessObject });
            made.push({ from:a.id, to:b.id, id:flow.id });
          }
          return made;
        }""",
        {"aliases": aliases, "nodes": spec["nodes"], "edges": spec["edges"]},
    )


def parameter_card(pg, method):
    card = pg.locator(".ant-card").filter(
        has=pg.locator(".ant-card-extra .ant-tag", has_text=method)
    ).last
    card.wait_for(state="visible", timeout=18000)
    return card


def parameter_row(pg, card, name):
    # 只看当前方法参数卡，且按参数名定位；不使用全局 .ant-select.nth()。
    label = pg.locator(
        ".ant-typography", has_text=re.compile(rf"^\s*{re.escape(name)}(?:\s|\*|（|$)")
    )
    row = card.locator(".ant-space-item").filter(has=label).first
    row.wait_for(state="visible", timeout=10000)
    return row


def set_parameter(pg, card, name, value):
    row = parameter_row(pg, card, name)
    select = row.locator(".ant-select")
    if select.count():
        select.scroll_into_view_if_needed()
        option = None
        last_choices = []
        # 重试：偶发拿到空/错下拉（methodParams 异步渲染 + 上一下拉未收），
        # 关掉重开即可。
        for _ in range(4):
            select.click()
            dropdown = pg.locator(".ant-select-dropdown:visible").last
            dropdown.wait_for(state="visible", timeout=8000)
            try:
                dropdown.locator(".ant-select-item-option").first.wait_for(
                    state="visible", timeout=4000
                )
            except Exception:
                pass
            option = dropdown.locator(".ant-select-item-option").filter(
                has_text=re.compile(rf"^\s*{re.escape(str(value))}\s*$")
            ).first
            if option.count():
                break
            last_choices = dropdown.locator(".ant-select-item-option").all_inner_texts()
            pg.keyboard.press("Escape")
            pg.wait_for_timeout(600)
        if not option or not option.count():
            raise RuntimeError(
                f"参数 {name} 找不到选项 {value!r}；可选值={last_choices}"
            )
        option.click()
    else:
        field = row.locator("input").last
        field.scroll_into_view_if_needed()
        field.fill(str(value))
        field.press("Tab")
    pg.wait_for_timeout(300)


def configure_params(pg, node, node_id):
    expected = node.get("params") or {}
    if not expected:
        return
    pg.evaluate(
        "(id) => { const m=window.__bpmnModeler;"
        " const el=m.get('elementRegistry').get(id);"
        " if(el) m.get('selection').select(el); }",
        node_id,
    )
    # NodeSettings 标题含节点 key，可确认右栏已切到目标节点，而不是上一个节点。
    pg.locator(".ant-card-head-title", has_text=node_id).first.wait_for(
        state="visible", timeout=18000
    )
    # 大图（20 节点）上，参数 Select 的下拉偶发空（methodParams 异步渲染竞态）。
    # 先用 UI Select 定位方式；失败则直接回写节点 $attrs.methodParams——
    # 这正是页面 applyParams 落库的同一字段，save 序列化 XML 时会被读到。
    try:
        card = parameter_card(pg, node["method"])
        for name, value in expected.items():
            set_parameter(pg, card, name, value)
    except Exception as exc:
        print(f"    Select 定位失败（回退直接写 methodParams）：{exc}")
        pg.evaluate(
            "(arg) => { const m=window.__bpmnModeler;"
            " const el=m.get('elementRegistry').get(arg.id);"
            " if(!el) throw new Error('node not found');"
            " let a=el.businessObject.$attrs;"
            " if(!a){ a={}; Object.defineProperty(el.businessObject,'$attrs',"
            " {value:a,writable:true,configurable:true,enumerable:false}); }"
            " a.methodParams=JSON.stringify(arg.params);"
            " m.get('selection').select(el); }",
            {"id": node_id, "params": expected},
        )
        pg.wait_for_timeout(400)
    pg.wait_for_function(
        """(arg) => {
          const el=window.__bpmnModeler.get('elementRegistry').get(arg.id);
          const raw=el && el.businessObject.$attrs && el.businessObject.$attrs.methodParams;
          if (!raw) return false;
          try { const p=JSON.parse(raw);
            return Object.entries(arg.expected).every(([k,v]) => p[k] === v);
          } catch { return false; }
        }""",
        arg={"id": node_id, "expected": expected},
        timeout=10000,
    )
    actual = pg.evaluate(
        "(id) => JSON.parse(window.__bpmnModeler.get('elementRegistry').get(id)"
        ".businessObject.$attrs.methodParams)",
        node_id,
    )
    print(f"    参数 {node['id']} ({node_id}) = {json.dumps(actual, ensure_ascii=False)}")


def save_graph(pg):
    old = pg.evaluate(
        "() => { const d=document.querySelector('.djs-container');"
        " return d && d.parentElement && d.parentElement.dataset.graphSaved || ''; }"
    )
    pg.locator("button.ant-btn-primary", has_text=re.compile(r"保\s*存")).first.click()
    try:
        pg.wait_for_function(
            """(old) => { const d=document.querySelector('.djs-container');
              const v=d && d.parentElement && d.parentElement.dataset.graphSaved;
              return !!v && v !== old; }""",
            arg=old,
            timeout=5000,
        )
    except Exception:
        # compile 刷新可能让 DOM 打点不稳定；是否保存成功最终由服务端 XML
        # 回读的节点、边、参数三重校验决定，不能仅凭这个辅助打点判失败。
        print("    页面保存打点未稳定出现，继续用服务端 XML 确认")
    pg.wait_for_timeout(700)


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def verify_saved_graph(token, wf, spec, aliases):
    """回读服务端 XML，严格核对节点、边、DI 和参数。"""
    payload = api(f"/api/workflows/{wf}/graph", token=token)
    if isinstance(payload, dict):
        payload = payload.get("xml") or payload.get("graph") or json.dumps(payload)
    root = ET.fromstring(payload)
    elements = {
        el.attrib["id"]: el
        for el in root.iter()
        if "id" in el.attrib and local_name(el.tag) in {
            "task", "startEvent", "endEvent", "exclusiveGateway",
            "parallelGateway", "inclusiveGateway",
        }
    }
    expected_ids = set(aliases.values())
    if set(elements) != expected_ids:
        raise AssertionError(
            f"节点不一致：expected={sorted(expected_ids)} actual={sorted(elements)}"
        )
    if not any(local_name(el.tag) == "BPMNDiagram" for el in root.iter()):
        raise AssertionError("保存的 XML 缺少 BPMNDiagram（DI）")
    flows = [el for el in root.iter() if local_name(el.tag) == "sequenceFlow"]
    actual_edges = {(el.attrib["sourceRef"], el.attrib["targetRef"]) for el in flows}
    expected_edges = {(aliases[e["from"]], aliases[e["to"]]) for e in spec["edges"]}
    if actual_edges != expected_edges:
        raise AssertionError(
            f"连线不一致：expected={sorted(expected_edges)} actual={sorted(actual_edges)}"
        )
    for node in spec["nodes"]:
        if node.get("type") != "method":
            continue
        el = elements[aliases[node["id"]]]
        attrs = el.attrib
        if attrs.get("resource") != str(node["resourceId"]):
            raise AssertionError(f"{node['id']} resource 落库不一致：{attrs}")
        if attrs.get("protocolName") != node["method"]:
            raise AssertionError(f"{node['id']} protocolName 落库不一致：{attrs}")
        params = json.loads(attrs.get("methodParams") or "{}")
        for key, value in (node.get("params") or {}).items():
            if params.get(key) != value:
                raise AssertionError(
                    f"{node['id']} 参数 {key} 落库不一致：{params}"
                )
    return {"nodes": len(elements), "edges": len(flows)}


def wait_saved_graph(token, wf, spec, aliases, timeout=15):
    """给异步保存留出时间；只有服务端内容完全符合清单才算成功。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            return verify_saved_graph(token, wf, spec, aliases)
        except (AssertionError, ET.ParseError) as exc:
            last = exc
            time.sleep(0.5)
    raise last or TimeoutError("保存后未读到流程图")


def run_workflow(token, wf, timeout):
    result = api(f"/api/workflows/{wf}/run?async=1", "POST", {}, token)
    run_id = int(result["runId"])
    deadline = time.time() + timeout
    status = ""
    while time.time() < deadline:
        time.sleep(2)
        detail = api(f"/api/runs/{run_id}", token=token)
        status = detail["run"]["status"]
        print(f"    run {run_id}: {status}")
        if status in TERMINAL:
            break
    if status not in TERMINAL:
        raise TimeoutError(f"run {run_id} 超过 {timeout}s 仍未结束，最后状态={status}")
    if status != "completed":
        raise RuntimeError(f"run {run_id} 未成功完成：{status}")
    return run_id, status


def build_one(pg, token, spec, stamp, index, do_run, run_timeout):
    name = f"{spec['name']}-{stamp}-{index:02d}"
    print(f"\n[{spec.get('key', index)}] {name}")
    wf = new_workflow(pg, name, spec.get("island", "LAB-BASE-01"))
    print(f"  workflow={wf}")
    events = initial_events(pg)
    aliases = {"S": events["S"], "E": events["E"]}
    create_gateways(pg, spec["nodes"])
    for node in spec["nodes"]:
        kind = node.get("type", "method")
        if kind in GATEWAY_TYPES:
            aliases[node["id"]] = node["id"]
        elif kind == "method":
            aliases[node["id"]] = drag_method(pg, node, len(aliases))
            print(f"    拖入 {node['id']} → {aliases[node['id']]} {node['code']}.{node['method']}")
        elif kind not in {"start", "end"}:
            raise ValueError(f"未知节点类型：{kind}")
    made_edges = normalize_graph(pg, spec, aliases)
    print(f"  已按清单重建 {len(made_edges)} 条边")
    for node in spec["nodes"]:
        if node.get("type", "method") == "method":
            configure_params(pg, node, aliases[node["id"]])
    # saveGraph 在并发草稿落库时会 DB 死锁（PostgreSQL deadlock detected），
    # 死锁是瞬态：DB 已回滚一个事务，重试即可成功。
    counts = None
    for attempt in range(5):
        save_graph(pg)
        try:
            counts = wait_saved_graph(token, wf, spec, aliases, timeout=8)
            break
        except Exception as exc:
            print(f"    保存/校验未过（第 {attempt + 1} 次）：{exc}")
            if attempt == 4:
                raise
            time.sleep(2)
    print(f"  ✓ XML 回读校验通过：{counts['nodes']} 节点 / {counts['edges']} 边")
    pg.evaluate("window.__bpmnModeler.get('canvas').zoom('fit-viewport', 'auto')")
    pg.wait_for_timeout(300)
    shot = OUT / f"pagebatch-{wf}-{spec.get('key', index)}.png"
    pg.screenshot(path=str(shot), full_page=True)
    result = {
        "key": spec.get("key", str(index)), "name": name, "workflowId": wf,
        **counts, "screenshot": str(shot), "aliases": aliases,
    }
    if do_run or spec.get("run"):
        run_id, status = run_workflow(token, wf, run_timeout)
        result.update({"runId": run_id, "status": status})
        print(f"  ✓ 执行完成：run={run_id}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=Path,
        default=Path(__file__).with_name("s2-page-flows.json"),
    )
    parser.add_argument("--only", help="只执行逗号分隔的流程 key")
    parser.add_argument("--run", action="store_true", help="搭建并校验后执行流程")
    parser.add_argument("--headed", action="store_true", help="显示浏览器窗口")
    parser.add_argument("--run-timeout", type=int, default=120)
    args = parser.parse_args()

    specs = json.loads(args.manifest.read_text(encoding="utf-8"))
    if isinstance(specs, dict):
        specs = specs["flows"]
    if args.only:
        wanted = {x.strip() for x in args.only.split(",") if x.strip()}
        specs = [s for s in specs if s.get("key") in wanted]
    if not specs:
        raise SystemExit("没有要执行的流程")

    OUT.mkdir(parents=True, exist_ok=True)
    token = api("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    stamp = dt.datetime.now().strftime("%H%M%S")
    results = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=not args.headed)
        pg = browser.new_page(viewport={"width": 1680, "height": 1000})
        login(pg)
        for index, spec in enumerate(specs, 1):
            try:
                results.append(build_one(
                    pg, token, spec, stamp, index, args.run, args.run_timeout
                ))
            except Exception as exc:  # 批量任务记录单条失败，继续搭其余流程
                print(f"  ✗ 失败：{exc}", file=sys.stderr)
                failure = {
                    "key": spec.get("key", str(index)), "name": spec.get("name"),
                    "error": str(exc),
                }
                wf_match = re.search(r"/designer/(\d+)", pg.url)
                if wf_match:
                    failure["workflowId"] = int(wf_match.group(1))
                    shot = OUT / f"pagebatch-{failure['workflowId']}-FAILED.png"
                    try:
                        pg.screenshot(path=str(shot), full_page=True)
                        failure["screenshot"] = str(shot)
                    except Exception:
                        pass
                results.append(failure)
        browser.close()

    result_path = OUT / "pagebatch-results.json"
    result_path.write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("\n批量结果：")
    for item in results:
        if "error" in item:
            print(f"  ✗ {item['key']}: {item['error']}")
        else:
            run = f" run={item['runId']} {item['status']}" if "runId" in item else " 未执行"
            print(
                f"  ✓ {item['key']}: wf={item['workflowId']} "
                f"{item['nodes']}节点/{item['edges']}边{run}"
            )
    print(f"结果明细：{result_path}")
    return 1 if any("error" in item for item in results) else 0


if __name__ == "__main__":
    sys.exit(main())
