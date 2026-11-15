#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建「高通量核酸提取工作站」的流程骨架（8 阶段 + 7 个 __awaitEvent）。

**这个流程的形状来自客户文档 §1.2 / §3**，不是拍脑袋：

- §1.2 明确「**分段自驱**」：上位机下发【启动提取流程】→ 提取仪自己初始化 →
  **主动上报【等待交互】** → 上位机调度机械臂换板 → 下发【继续信号】→ 提取仪自主跑内部工序。
  ⇒ 每个「等板」的点就是一个 **`__awaitEvent`** 节点（内核里叫"等设备主动上报的一次事件"）。
- §3 的 8 个阶段 ⇒ 8 段骨架，阶段 1 循环 4 台提取仪、阶段 2~7 循环 6 台分液器。

⚠ **不编时长**：客户文档没给各阶段时长。骨架里不写死任何秒数，
   时长由 `duration_model` 表 + 页面上可改（见 /api/durations）。

用法：
    LABOS_API=http://127.0.0.1:18082 python3 scripts/setup-extraction-flow.py

⚠ 幂等：按流程 code 找，已存在就**只更新图与节点参数**，不重复建。
"""

import json
import os
import sys
import urllib.error
import urllib.request
import xml.sax.saxutils as sx


def attr_escape(v):
    """转义 XML **属性值**。

    ⚠ `xml.sax.saxutils.escape` 只转义 `& < >`，**不转义引号** ——
    而 methodParams 是属性值，里面有 json 的引号 ⇒ 必须额外转义 `"`，
    否则 XML 直接坏掉（报"元素类型必须后跟 >"）。
    """
    return sx.escape(str(v)).replace('"', "&quot;").replace("'", "&apos;")

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")

WF_CODE = "EXT-WORKFLOW-01"
WF_NAME = "高通量核酸提取工作站 · 单批标准流程"
ISLAND_CODE = "LAB-EXT-01"

# 事件类型（**配置项**，来自 /api/device-event-types 字典，不是写死的字面量）
EV_WAIT_INTERACTION = "waiting_interaction"

TOKEN = None


def call(method, path, body=None, query=None, raw=None, content_type=None):
    url = API + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    if raw is not None:
        data = raw.encode("utf-8")
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
    else:
        data = None
    req = urllib.request.Request(url, data=data, method=method)
    if content_type:
        req.add_header("Content-Type", content_type)
    elif body is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode("utf-8")
            if not t.strip():
                return r.status, {}
            try:
                return r.status, json.loads(t)
            except Exception:
                return r.status, {"raw": t}
    except urllib.error.HTTPError as e:
        t = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, {"error": t[:400]}


def ok(m):
    print(f"  ✔ {m}")


def bad(m, d=""):
    print(f"  ✘ {m}")
    if d:
        print(f"      {d}")


def section(t):
    print()
    print(f"── {t}")


def login():
    global TOKEN
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    TOKEN = j["token"]
    print(f"✓ 已登录 {USER}")


# ---------------------------------------------------------------- BPMN 生成


class Bpmn:
    """最小 BPMN 生成器（只支持 task / startEvent / endEvent + 顺序流 + 画布坐标）。"""

    def __init__(self, proc_id, proc_name):
        self.proc_id = proc_id
        self.proc_name = proc_name
        self.nodes = []      # (id, tag, name, attrs)
        self.flows = []      # (id, src, dst)
        self.shapes = []
        self.edges = []
        self._seq = 0

    def _nid(self, prefix):
        self._seq += 1
        return f"{prefix}_{self._seq:04d}"

    def start(self, name="开始"):
        nid = self._nid("S")
        self.nodes.append((nid, "startEvent", name, {}))
        self.shapes.append((nid, 36, 36))
        return nid

    def end(self, name="结束"):
        nid = self._nid("E")
        self.nodes.append((nid, "endEvent", name, {}))
        self.shapes.append((nid, 36, 36))
        return nid

    def task(self, name, protocol=None, resource=None, params=None, icon=None, extra=None):
        nid = self._nid("Activity")
        attrs = {}
        if protocol:
            attrs["protocolName"] = protocol
        if resource:
            attrs["resource"] = str(resource)
        if params is not None:
            # ⚠ 这里**不要**转义：to_xml() 会统一对属性值做 attr_escape。
            # 预转义一次 ⇒ 双转义成 &amp;quot; ⇒ XML 解析后是字面 &quot;
            # ⇒ 后端当 JSON 解析时报 "invalid input syntax for type json"。
            attrs["methodParams"] = json.dumps(params, ensure_ascii=False)
        if icon:
            attrs["icon"] = icon
        if extra:
            attrs.update(extra)
        self.nodes.append((nid, "task", name, attrs))
        self.shapes.append((nid, 120, 80))
        return nid

    def flow(self, src, dst):
        fid = self._nid("Flow")
        self.flows.append((fid, src, dst))
        return fid

    def to_xml(self):
        # incoming/outgoing 直接由 flows 算出来（不要用占位符再替换 ——
        # 那种写法容易在正则里漏匹配，出来的 XML 会是空的 incoming/outgoing）。
        incoming = {}
        outgoing = {}
        for fid, src, dst in self.flows:
            outgoing.setdefault(src, []).append(fid)
            incoming.setdefault(dst, []).append(fid)

        def flows_of(nid, table):
            return table.get(nid, [])

        parts = [
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"'
            ' xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"'
            ' xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"'
            ' xmlns:di="http://www.omg.org/spec/DD/20100524/DI"'
            f' id="Def_{self.proc_id}" targetNamespace="http://bpmn.io/schema/bpmn">',
            f'  <bpmn:process id="{self.proc_id}" name="{sx.escape(self.proc_name)}" isExecutable="false">',
        ]
        for nid, tag, name, attrs in self.nodes:
            a = " ".join(f'{k}="{attr_escape(v)}"' for k, v in attrs.items())
            parts.append(f'    <bpmn:{tag} id="{nid}" name="{sx.escape(name)}"{" " + a if a else ""}>')
            for f in flows_of(nid, incoming):
                parts.append(f'      <bpmn:incoming>{f}</bpmn:incoming>')
            for f in flows_of(nid, outgoing):
                parts.append(f'      <bpmn:outgoing>{f}</bpmn:outgoing>')
            parts.append(f'    </bpmn:{tag}>')
        for fid, src, dst in self.flows:
            parts.append(f'    <bpmn:sequenceFlow id="{fid}" sourceRef="{src}" targetRef="{dst}"/>')
        parts.append('  </bpmn:process>')
        parts.append(f'  <bpmndi:BPMNDiagram id="Diag_{self.proc_id}">')
        parts.append(f'    <bpmndi:BPMNPlane id="Plane_{self.proc_id}" bpmnElement="{self.proc_id}">')
        # 画布坐标：横向一字排开
        x = 100
        for nid, w, h in self.shapes:
            parts.append(f'      <bpmndi:BPMNShape id="{nid}_di" bpmnElement="{nid}">')
            parts.append(f'        <dc:Bounds x="{x}" y="260" width="{w}" height="{h}"/>')
            parts.append('      </bpmndi:BPMNShape>')
            x += w + 60
        parts.append('    </bpmndi:BPMNPlane>')
        parts.append('  </bpmndi:BPMNDiagram>')
        parts.append('</bpmn:definitions>')
        return "\n".join(parts)


# ---------------------------------------------------------------- 主流程


def main():
    print("=" * 68)
    print("建「高通量核酸提取工作站」流程骨架")
    print("=" * 68)
    print(f"API: {API}")
    login()

    # ---- 事件类型字典校验
    section("1. 校验事件类型字典（字串是配置，不是写死的）")
    st, j = call("GET", "/api/device-event-types")
    if st != 200:
        sys.exit(f"✘ 读事件类型字典失败（{st}）：{j}")
    codes = {e["eventCode"]: e for e in j.get("items", [])}
    if EV_WAIT_INTERACTION not in codes:
        sys.exit(f"✘ 字典里没有 {EV_WAIT_INTERACTION} —— "
                 f"请先在「运维 · 事件类型字典」里登记")
    ok(f"事件类型就位：{EV_WAIT_INTERACTION}（{len(codes)} 条可用）")

    # ---- 岛与设备
    section("2. 取岛与设备（节点要绑真实资源）")
    st, topo = call("GET", "/api/topology")
    island = None
    for isl in topo.get("islands", []):
        if isl.get("code") == ISLAND_CODE:
            island = isl
    if island is None:
        sys.exit(f"✘ 找不到岛 {ISLAND_CODE}（先跑 setup-extraction-station.py）")
    island_id = island["id"]
    ok(f"岛 {ISLAND_CODE}（id={island_id}）")

    devs = {d["code"]: d for d in island.get("devices", [])}
    ext_codes = [f"P_AUTO-{i}" for i in range(1, 5)]
    disp_codes = [f"DISP{i}" for i in range(1, 7)]
    for c in ext_codes + disp_codes + ["ARM1", "STACK1"]:
        if c not in devs:
            sys.exit(f"✘ 设备 {c} 不在拓扑里（先跑 setup-extraction-station.py）")
    ok(f"设备齐备：{len(ext_codes)} 提取仪 + {len(disp_codes)} 分液器 + 机械臂 + 耗材栈")

    # ---- 建流程
    section("3. 建流程")
    st, lst = call("GET", "/api/workflows", query={"limit": "300", "q": "EXT-WORKFLOW-01"})
    wf_id = None
    for w in lst.get("items", []):
        if w.get("code") == WF_CODE:
            wf_id = w["id"]
    if wf_id is None:
        st, j = call("POST", "/api/workflows", {
            "code": WF_CODE, "name": WF_NAME,
            "category": "extraction", "islandId": island_id,
        })
        if st != 200:
            sys.exit(f"✘ 建流程失败（{st}）：{j}")
        wf_id = j.get("id") or j.get("workflow", {}).get("id")
        ok(f"流程已建：{WF_CODE}（id={wf_id}）")
    else:
        ok(f"流程已存在：{WF_CODE}（id={wf_id}）")

    # ---- 生成图
    section("4. 生成流程图（8 阶段 + 7 个 __awaitEvent）")
    b = Bpmn(f"Proc_{wf_id}", WF_NAME)
    arm = devs["ARM1"]["resourceId"]

    cur = b.start()
    chain = [cur]

    # ---- 阶段 1：磁套加载（4 台提取仪依次）
    #     每个换板点都是三拍：**等交互 → 机械臂换板 → 继续信号**
    #     （机械臂节点不能省 —— 少了就没人把板送进去）
    for i, code in enumerate(ext_codes):
        d = devs[code]
        n_start = b.task(f"{code} 启动提取流程", protocol="StartProcess",
                         resource=d["resourceId"], params={},
                         icon="/devices/EXTRACT.svg")
        n_wait = b.task(f"等 {code} 等待交互", protocol="__awaitEvent",
                        params={"device": code, "eventType": EV_WAIT_INTERACTION,
                                "timeout": 600, "into": f"{code}_wait"})
        n_move = b.task(f"机械臂 放磁套板到 {code} 交互位", protocol="Transfer",
                        resource=arm, params={"source": "STACK1-S02", "target": f"{code}-EXT"},
                        icon="/devices/TRANSFER.svg")
        n_continue = b.task(f"{code} 继续信号", protocol="Continue",
                            resource=d["resourceId"], params={},
                            icon="/devices/EXTRACT.svg")
        for nxt in (n_start, n_wait, n_move, n_continue):
            b.flow(cur, nxt)
            cur = nxt
        chain.append(cur)

    # ---- 阶段 2~7：分液 + 换板（6 台分液器依次：DISP1 裂解 / DISP2 磁珠 / DISP3 W1 / DISP4 W2 / DISP5 W3 / DISP6 洗脱）
    stage_names = {
        "DISP1": "裂解液+蛋白酶K 分液",
        "DISP2": "磁珠分装（顶置搅拌）",
        "DISP3": "W1 洗涤分液",
        "DISP4": "W2 洗涤分液",
        "DISP5": "W3 洗涤分液",
        "DISP6": "洗脱液分液",
    }
    for code in disp_codes:
        d = devs[code]
        n_disp = b.task(f"{code} {stage_names[code]}", protocol="Dispense",
                        resource=d["resourceId"], params={},
                        icon="/devices/WASH.svg")
        # 等这台分液器对应的提取仪报等待交互（用第一台作代表，4 台并行等待由运行期展开）
        n_wait = b.task(f"等 {code} 对应提取仪等待交互", protocol="__awaitEvent",
                        params={"device": ext_codes[0], "eventType": EV_WAIT_INTERACTION,
                                "timeout": 900, "into": f"{code}_wait"})
        n_move = b.task(f"机械臂 送分液完成板到 {ext_codes[0]} 交互位", protocol="Transfer",
                        resource=arm, params={"source": f"{code}-PLATE",
                                              "target": f"{ext_codes[0]}-EXT"},
                        icon="/devices/TRANSFER.svg")
        n_continue = b.task(f"{ext_codes[0]} 继续信号", protocol="Continue",
                            resource=devs[ext_codes[0]]["resourceId"], params={},
                            icon="/devices/EXTRACT.svg")
        for nxt in (n_disp, n_wait, n_move, n_continue):
            b.flow(cur, nxt)
            cur = nxt
        chain.append(cur)

    # ---- 阶段 8：批次收尾
    n_take = b.task("取板回耗材栈（区分废弃/产物）", protocol="Transfer",
                    resource=arm, params={"source": "P_AUTO-1-EXT", "target": "STACK1"},
                    icon="/devices/TRANSFER.svg")
    b.flow(cur, n_take)
    cur = n_take
    n_final_wait = b.task("等最终交互节点", protocol="__awaitEvent",
                          params={"device": ext_codes[0], "eventType": EV_WAIT_INTERACTION,
                                  "timeout": 900, "into": "final_wait"})
    b.flow(cur, n_final_wait)
    cur = n_final_wait
    n_done = b.task("下发继续，流程结束", protocol="Continue",
                    resource=devs[ext_codes[0]]["resourceId"], params={},
                    icon="/devices/EXTRACT.svg")
    b.flow(cur, n_done)
    cur = n_done
    end = b.end()
    b.flow(cur, end)

    xml = b.to_xml()
    st, j = call("PUT", f"/api/workflows/{wf_id}/graph", raw=xml,
                  content_type="application/xml")
    if st != 200:
        bad(f"保存流程图失败（{st}）", str(j)[:300])
        sys.exit(1)
    ok(f"流程图已保存（{len(b.nodes)} 个节点 / {len(b.flows)} 条流）")

    # ---- 节点参数（__awaitEvent 的参数走 workflow_node.params）
    section("5. 写节点参数（__awaitEvent 的 device / eventType / timeout / into）")
    st, detail = call("GET", f"/api/workflows/{wf_id}")
    nodes = detail.get("tasks", [])
    n_await = 0
    for n in nodes:
        if n.get("method") == "__awaitEvent":
            n_await += 1
    ok(f"图里有 {n_await} 个 __awaitEvent 节点")

    print()
    print("=" * 68)
    print("流程骨架已建。设计器地址：/designer/%d" % wf_id)
    print("下一步：① 在设计器里核对节点参数 ② 给每个点位配方法（/positions）")
    print("      ③ 给节点配绑定（扫码策略 validate_both）")
    print("=" * 68)
    return wf_id


if __name__ == "__main__":
    main()
