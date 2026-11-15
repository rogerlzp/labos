#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建「高通量核酸提取工作站」的流程骨架：**流程图 + 节点绑定，一起生成**。

**这个流程的形状来自客户文档 §1.1 / §1.2 / §3**，不是拍脑袋：

- §1.1 明确「提取仪在操作位执行提取反应时，交互位可同步执行机械臂取放板、
  耗材更新，**互不阻塞**」⇒ 4 台提取仪是**图上并行分支**
  （2 个 `parallelGateway` 分叉 + 2 个 AND 汇合），不是 4 段串行。
- §1.2 明确「**分段自驱**」：上位机下发【启动提取流程】→ 提取仪自己初始化 →
  **主动上报【等待交互】** → 上位机调度机械臂换板 → 下发【继续信号】→ 提取仪自主跑内部工序。
  ⇒ 每个「等板」的点就是一个 **`__awaitEvent`** 节点（内核里叫"等设备主动上报的一次事件"）。
  ⇒ 三拍顺序必须是 **设备动作 → 等交互 → 机械臂**（事件由**设备动作**触发，
     等交互排在前面就永远等不到）。
- §3 的 8 个阶段 ⇒ 阶段 1 是 4 台提取仪并行加载磁套；阶段 2~7 是 6 台分液器依次分液；
  阶段 8 是洗脱 + 取产物板。

⚠ **不编时长**：客户文档没给各阶段时长。骨架里不写死任何秒数，
   时长由 `duration_model` 表 + 页面上可改（见 /api/durations）。

⚠ **节点绑定也在这里生成**（2026-11-15 移进来）：`node_binding` 挂在 `node_id` 上，
   而 **node_key 是按位置分配的** ⇒ 流程图一改，旧绑定会**原地存活、指向别的节点**
   ⇒ 运行期「绑定优先级最高」⇒ **静默用错设备**（实测：把 P_AUTO-3 的洗脱发给 P_AUTO-1）。
   ⇒ 图与绑定同源生成 + 写完**回读自检**（设备实例/点位/扫码策略/装板方式/方法 id 五项全对）。

用法：
    LABOS_API=http://127.0.0.1:18084 python3 scripts/setup-extraction-flow.py

⚠ 幂等：按流程 code 找，已存在就**只更新图与绑定**，不重复建。
"""

import json
import os
import sys
import urllib.error
import urllib.parse
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

    def parallel_gateway(self, name="并行拆分"):
        """并行网关（`parallelGateway` ⇒ 内核 node_type = `gateway_parallel`）。

        <p>客户文档 §1.1：4 台提取仪**并行**跑各自的内层工序，
        「提取仪在操作位执行提取反应时，交互位可同步执行机械臂取放板、耗材更新，**互不阻塞**」
        ⇒ 图上必须是**并行分支**，不是 4 段串行。
        """
        nid = self._nid("Gateway")
        self.nodes.append((nid, "parallelGateway", name, {}))
        self.shapes.append((nid, 50, 50))
        return nid

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


def bind_node(wf_id, node_key, device_code, device, position_id, method_id):
    """写一个节点的绑定（整体替换语义：先删后插）。

    ⚠ `{"bindings": []}` = **清空**该节点的绑定（后端先删后插，空数组即清空）
    ⇒ 所以"这个节点不该绑设备"要显式写一次空绑定，
    否则**上一次**留下的绑定会原地存活（见下面 main 里的说明）。
    """
    if device is None:
        payload = {"bindings": []}
    else:
        is_arm = device_code == "ARM1"
        payload = {"bindings": [{
            # 机械臂没有自己的板位（它去别人的点位取放板）⇒ 只绑设备实例
            "positionId": position_id,
            "deviceInstanceId": device.get("instanceId"),
            "methodId": method_id,
            # 装板方式：设备节点装一块板；机械臂节点不放板
            "loadingPattern": "do_not_load" if is_arm else "load_single",
            # 扫码策略（客户文档 §1.1：机械臂含扫码枪，每次抓板扫码记录）：
            #   机械臂搬运 = 进出都校验；设备换板 = 进板时校验
            "barcodePolicy": "validate_both" if is_arm else "validate_in",
            "leaveAtStation": False,
            "params": {},
        }]}
    st, r = call("PUT",
                 f"/api/workflows/{wf_id}/nodes/{urllib.parse.quote(node_key, safe='')}/bindings",
                 payload)
    return st, r


def main():
    print("=" * 68)
    print("建「高通量核酸提取工作站」流程骨架（流程图 + 节点绑定，一起生成）")
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
    section("4. 生成流程图（8 阶段 + 8 个 __awaitEvent）")
    b = Bpmn(f"Proc_{wf_id}", WF_NAME)
    arm = devs["ARM1"]["resourceId"]

    # 节点键 → (设备码, 方法名)：**图和绑定一起生成**，
    # 保证「节点绑的设备」与「节点要执行的设备」永远是同一台。
    node_bind = {}

    cur = b.start()
    chain = [cur]

    # ---- 阶段 1：磁套加载（4 台提取仪依次）
    #     每个换板点都是三拍：**等交互 → 机械臂换板 → 设备动作**
    #     （机械臂节点不能省 —— 少了就没人把板送进去）
    #
    # ⚠ **方法名用 PAuto 的真实指令**（Bind/Elute/PickTipComb/DropTipComb/…），
    #   **不是**客户文档里的抽象名（【启动提取流程】/【继续信号】）。
    #   原因：PAuto 协议里**没有** StartProcess/Continue 这两个指令
    #   （见 docs/设备/PAuto-磁珠法核酸提取仪.md §6.2 的方法表）⇒
    #   引用不存在的指令 ⇒ 派发时必然找不到方法、节点永远 MANUAL。
    #   ⚠ 而【等待交互】**保留** —— 那是**平台能力**（__awaitEvent 等设备上报），
    #   与设备指令是两回事；PAuto 协议不支持主动上报 ⇒ 这一拍在 PAuto 上
    #   **要等真机手册确认**（见审核清单的待定项）。
    # ============ 并行结构（客户文档 §1.1 / §3）============
    #
    # 客户文档：4 台提取仪**并行**跑各自内部工序；6 台分液器是**依次**用的
    # （DISP1 洗完 4 块板才能进 DISP2）。
    # ⇒ 图上是 **两段并行 + 中间一段串行**：
    #     [split] → 4 条提取仪链（取磁套/等交互/放板/卸磁套）→ [join]
    #     → 6 个分液阶段（依次，各送 4 块板）
    #     → [split] → 4 条提取仪链（等交互/送板/内部工序）→ [join]
    #     → 收尾
    #
    # ⚠ 2026-11-15 实测踩过的坑（别改回去）：
    #   ① 4 台提取仪**串成顺序** ⇒ P_AUTO-1 的事件被阶段 1 吃掉，
    #      阶段 2 等 P_AUTO-1 时永远等不到（事件先到先得，一个 run 只消费一次）。
    #   ② 6 个分液阶段全等"第一台"提取仪 ⇒ 同样事件不够用。
    #   ⇒ 现在：每台提取仪在自己的链里等**自己**的【等待交互】。

    stage_names = {
        "DISP1": "裂解液+蛋白酶K 分液",
        "DISP2": "磁珠分装（顶置搅拌）",
        "DISP3": "W1 洗涤分液",
        "DISP4": "W2 洗涤分液",
        "DISP5": "W3 洗涤分液",
        "DISP6": "洗脱液分液",
    }
    # 每个分液阶段对应的**提取仪真实动作**（客户文档 §3 阶段 → PAuto 指令）
    stage_action = {
        "DISP1": ("Incubate", "孵育（裂解）"),
        "DISP2": ("Bind", "结合（磁珠）"),
        "DISP3": ("Wash", "漂洗 W1"),
        "DISP4": ("Wash", "漂洗 W2"),
        "DISP5": ("Wash", "漂洗 W3"),
        "DISP6": ("Elute", "洗脱"),
    }

    # ---- 并行段 A：4 台提取仪各自加载磁套
    split_a = b.parallel_gateway("并行拆分（磁套加载）")
    b.flow(cur, split_a)
    cur = split_a
    chain_a_ends = []
    for code in ext_codes:
        d = devs[code]
        n_pick = b.task(f"{code} 取磁套", protocol="PickTipComb",
                        resource=d["resourceId"], params={"plateIndex": 0},
                        icon="/devices/EXTRACT.svg")
        n_wait = b.task(f"等 {code} 等待交互", protocol="__awaitEvent",
                        params={"device": code, "eventType": EV_WAIT_INTERACTION,
                                "timeout": 600, "into": f"{code}_wait"})
        n_move = b.task(f"机械臂 放磁套板到 {code} 交互位", protocol="Transfer",
                        resource=arm, params={"source": "STACK1-S02", "target": f"{code}-EXT"},
                        icon="/devices/TRANSFER.svg")
        n_drop = b.task(f"{code} 卸磁套", protocol="DropTipComb",
                        resource=d["resourceId"], params={"plateIndex": 0},
                        icon="/devices/EXTRACT.svg")
        node_bind[n_pick] = (code, "PickTipComb")
        node_bind[n_drop] = (code, "DropTipComb")
        node_bind[n_move] = ("ARM1", "Transfer")
        # ⚠ 每条链都从 **split_a** 出发（不是从上一条链的尾巴）⇒ 才是真正的并行分叉。
        #   写成 `b.flow(cur, nxt)` 且 cur 在循环里推进 ⇒ 4 条链被串成一条
        #   ⇒ 预检 PF-15 报「网关 1 进 1 出（直通）」。
        node = split_a
        for nxt in (n_pick, n_wait, n_move, n_drop):
            b.flow(node, nxt)
            node = nxt
        chain_a_ends.append(node)
    join_a = b.parallel_gateway("并行汇合（磁套加载完成）")
    for e in chain_a_ends:
        b.flow(e, join_a)
    cur = join_a

    # ---- 串行段：6 个分液阶段（依次；每个分液器给 4 台提取仪各送一块板）
    for code in disp_codes:
        d = devs[code]
        # ⚠ 方法名用设备**真实**支持的那个：分液器（型号 EQ165）登记的方法是 `Run`，
        #   不是泛型画像里的 `Dispense` ⇒ 写 `Dispense` 会"节点找不到方法"
        #   ⇒ 派发失败（策略耗尽）⇒ 节点转人工。
        n_disp = b.task(f"{code} {stage_names[code]}", protocol="Run",
                        resource=d["resourceId"], params={},
                        icon="/devices/WASH.svg")
        node_bind[n_disp] = (code, "Run")
        b.flow(cur, n_disp)
        cur = n_disp

    # ---- 并行段 B：4 台提取仪各自跑内部工序（等交互 → 收板）
    split_b = b.parallel_gateway("并行拆分（内部工序）")
    b.flow(cur, split_b)
    cur = split_b
    chain_b_ends = []
    for code in ext_codes:
        d = devs[code]
        n_wait = b.task(f"等 {code} 等待交互（收尾）", protocol="__awaitEvent",
                        params={"device": code, "eventType": EV_WAIT_INTERACTION,
                                "timeout": 900, "into": f"{code}_wait2"})
        n_take = b.task(f"机械臂 从 {code} 取产物板", protocol="Transfer",
                        resource=arm, params={"source": f"{code}-EXT", "target": "STACK1"},
                        icon="/devices/TRANSFER.svg")
        action, action_cn = stage_action["DISP6"]
        n_action = b.task(f"{code} {action_cn}", protocol=action,
                          resource=d["resourceId"], params={"plateIndex": 0},
                          icon="/devices/EXTRACT.svg")
        node_bind[n_action] = (code, action)
        node_bind[n_take] = ("ARM1", "Transfer")
        # ⚠ **拍序：设备动作 → 等交互 → 机械臂**（2026-11-15 修）
        #   【等待交互】是**设备动作触发**的：设备自己跑完内部工序才报
        #   （模拟器：命令成功 → 300ms 后推 waiting_interaction）。
        #   ⇒ 写成「等交互 → 机械臂 → 设备动作」的话，等交互排在最前面，
        #      而那一刻该设备**还没执行过任何会报事件的动作**（上一段的事件
        #      又已被本段 A 的等待节点消费掉）⇒ 永远等不到 ⇒ 卡到超时。
        #   ⇒ 客户文档 §1.2 的顺序本来就是「下发【启动提取流程】→ 设备自主跑
        #      内部工序 → 主动上报【等待交互】→ 上位机调度机械臂换板」。
        #   （段 A 是「取磁套 → 等交互 → 放板 → 卸磁套」，同样是动作在前的三拍。）
        # 每条链都从 **split_b** 出发（见并行段 A 的说明）
        node = split_b
        for nxt in (n_action, n_wait, n_take):
            b.flow(node, nxt)
            node = nxt
        chain_b_ends.append(node)
    join_b = b.parallel_gateway("并行汇合（单批完成）")
    for e in chain_b_ends:
        b.flow(e, join_b)
    cur = join_b

    n_done = b.task("收尾：读状态", protocol="GetStatus",
                    resource=devs[ext_codes[0]]["resourceId"], params={},
                    icon="/devices/EXTRACT.svg")
    node_bind[n_done] = (ext_codes[0], "GetStatus")
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

    # ---- 节点绑定
    section("5. 写节点绑定（**和图一起生成** ⇒ 不可能张冠李戴）")
    #
    # ⚠ **为什么绑定必须跟图一起生成**（2026-11-15 实踩的真 bug）：
    #   节点绑定挂在 `node_binding.node_id` 上，而 **node_key 是按位置分配的**
    #   （Activity_0004 = 第 4 个建出来的节点）。流程图一改，
    #   同一个 Activity_0004 可能变成**另一台设备的节点**，
    #   而 node_id 还在（upsert 按 key 命中）⇒ **旧绑定原地存活、指向新节点**
    #   ⇒ 运行期「绑定优先级最高」⇒ **静默用错设备**：
    #   实测「P_AUTO-3 洗脱」的 Elute 发给了 P_AUTO-1 ⇒ P_AUTO-3 永不报
    #   【等待交互】⇒ 流程卡死，而日志/界面**没有一处指向真凶**。
    #   ⇒ 现在：图与绑定同一脚本同一次生成；并且**每个节点都显式写一次**
    #      （不该绑的写空绑定 ⇒ 把陈旧绑定清掉），最后**回读自检**。

    # 点位：提取仪绑交互位（机械臂可达那侧），分液器绑板位
    st, pos = call("GET", "/api/positions", query={"islandId": island_id})
    point_by_code = {p.get("code"): p.get("pointId") for p in pos.get("items", [])}
    ok(f"点位 {len(point_by_code)} 个（绑定用 pointId）")

    # 方法 id：绑定的 methodId 取**该节点自己要执行的那个方法**，不是随便第一个
    method_id_by = {}
    for code, d in devs.items():
        st, m = call("GET", f"/api/devices/{d['resourceId']}/methods")
        for it in m.get("items", []):
            if it.get("methodId") and it.get("method"):
                method_id_by[(code, it["method"])] = it["methodId"]

    def position_for(code):
        if code.startswith("P_AUTO-"):
            suffix = "EXT"      # 交互位：机械臂够得到
        elif code.startswith("DISP"):
            suffix = "PLATE"    # 板位
        else:
            return None         # 机械臂/耗材栈：没有自己的板位
        return point_by_code.get(f"{code}-{suffix}")

    # 全部节点都写一次：绑设备的写绑定，不绑的写空绑定（清陈旧）
    written = cleared = 0
    for nid, tag, name, attrs in b.nodes:
        target = node_bind.get(nid)
        if target is None:
            st, r = bind_node(wf_id, nid, None, None, None, None)
            if st == 200:
                cleared += 1
            else:
                bad(f"清空节点绑定失败：{nid}（{st}）", str(r)[:150])
            continue
        code, method = target
        st, r = bind_node(wf_id, nid, code, devs[code], position_for(code),
                          method_id_by.get((code, method)))
        if st == 200:
            written += 1
        else:
            bad(f"节点绑定失败：{nid} {name}（{st}）", str(r)[:180])
    ok(f"节点绑定：写入 {written} · 清空 {cleared}")

    # ---- 回读自检：绑定里的设备，必须就是节点自己要执行的那台
    section("6. 回读自检（绑定设备 == 节点要执行的设备）")
    st, detail = call("GET", f"/api/workflows/{wf_id}")
    tasks = detail.get("tasks", [])
    n_await = sum(1 for t in tasks if t.get("method") == "__awaitEvent")
    ok(f"图里有 {n_await} 个 __awaitEvent 节点")

    mismatched = []
    for t in tasks:
        key = t.get("key")
        st, r = call("GET", f"/api/workflows/{wf_id}/nodes/{urllib.parse.quote(key, safe='')}/bindings")
        rows = r.get("bindings") or [] if isinstance(r, dict) else []
        bound_inst = rows[0].get("deviceInstanceId") if rows else None
        bound_pos = rows[0].get("positionId") if rows else None
        bound_bar = rows[0].get("barcodePolicy") if rows else None
        bound_load = rows[0].get("loadingPattern") if rows else None
        bound_mid = rows[0].get("methodId") if rows else None
        want = node_bind.get(key)
        want_inst = devs[want[0]].get("instanceId") if want else None
        want_pos = position_for(want[0]) if want else None
        want_mid = method_id_by.get((want[0], want[1])) if want else None
        # 扫码策略与装板方式也自检（客户文档 §1.1：机械臂含扫码枪，每次抓板扫码记录）
        want_bar = "validate_both" if want and want[0] == "ARM1" else ("validate_in" if want else None)
        want_load = "do_not_load" if want and want[0] == "ARM1" else ("load_single" if want else None)
        if (bound_inst != want_inst or bound_pos != want_pos
                or bound_bar != want_bar or bound_load != want_load
                or bound_mid != want_mid):
            mismatched.append((key, t.get("name"),
                               (bound_inst, bound_pos, bound_bar, bound_load, bound_mid),
                               (want_inst, want_pos, want_bar, want_load, want_mid)))
    if mismatched:
        for key, name, got, want in mismatched:
            bad(f"绑定与节点设备不符：{key} {name}",
                f"实际(实例,点位,扫码,装板,方法)={got} ｜ 应为={want}")
        sys.exit("✘ 绑定自检不通过 —— 拒绝留下半对的配置")
    ok(f"绑定自检通过（{len(tasks)} 个节点）")

    print()
    print("=" * 68)
    print("流程骨架已建（流程图 + 节点绑定一起落库）。设计器地址：/designer/%d" % wf_id)
    print("下一步：① 给每个点位配方法（setup-extraction-bindings.py）"
          " ② 填各阶段时长（seed-extraction-durations.py）")
    print("=" * 68)
    return wf_id


if __name__ == "__main__":
    main()
