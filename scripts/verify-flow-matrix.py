#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""完整流程测试矩阵：节点 / 网关 / 变量 / 循环 / 并行 / 端口拓扑 / 暂停恢复。

为什么单独有这个脚本：
  现有 verify-parallel-flow.py / verify-m5.sh / verify-m4.sh 各覆盖一块，
  但"异或网关""内置节点""循环回边""节点输出驱动条件""设备进出口拓扑"
  这些当时是**手工 curl 验过一次**（见 docs/流程控制-测试报告.md §4），
  没有沉淀成可重复脚本 —— 回归时覆盖不到。这里把它们收口成一个入口。

覆盖的用例（--section 可选其一）：
  serial    START / END / TASK 串行
  builtin   __setVar / __expr / 变量驱动网关
  outvar    设备回包字段 → 变量 → 条件
  xor       异或网关（条件真 / 条件假走 default）
  xor3      三分支异或（按声明顺序求值）
  loop      回边循环 + maxIterations + loop_index
  loopback  回边跨越并行分叉（循环体里再进并行）
  parallel  并行分叉/汇合（同设备串行 / 跨设备并行）
  pause     暂停三态 + 恢复 + 异常暂停自动续（heartbeat）
  ports     设备进出口拓扑（both / in+out 分开 / 多进口）—— 需内核开 LABOS_TRANSFER_PLAN
  experiment 实验内多个流程（拓扑展开：多个 workflow 依次跑）
  concurrent 多流程并发（多个独立 run 同时跑）

用法：
    export LABOS_DB_HOST=... LABOS_DB_PORT=5432 LABOS_DB_NAME=... \
           LABOS_DB_USER=... LABOS_DB_PASS=...
    python3 scripts/verify-flow-matrix.py                 # 跑全部
    python3 scripts/verify-flow-matrix.py --section xor   # 只跑异或
不连库/没模拟器的场景会打印 SKIP，不算失败。
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PASS, FAIL, SKIP = 0, 0, 0


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
    print(f"\n\033[1m── {title} ──\033[0m")


# ---------------------------------------------------------------- 基础设施

def api(path, method="GET", body=None, token=None, raw=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if raw is not None:
        data = raw.encode()
        req.add_header("Content-Type", "application/xml")
    elif body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=120) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    # 口令只从 LABOS_DB_PASS 来：不设 PGPASSWORD 时 psql 会要交互口令，
    # 非 tty 下直接失败并返回空串 —— 会被当成"库里没有设备实例"而静默跳过。
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-p",
         os.environ.get("LABOS_DB_PORT", "5432"), "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    return out.stdout.strip()


def build_xml(nodes, edges, def_id="Def_m", proc_id="Proc_m"):
    """nodes: [(id, name, bpmn_tag, extra_attr)]；edges: [(id, src, tgt, cond_or_None)]。

    extra_attr 里放 protocolName / resource / default 等属性。
    DI 段按顺序摆一排（执行不看坐标，但缺了 bpmn.io 重开会报错）。
    """
    x = 80
    shapes, nes = [], []
    for (nid, _name, tag, _extra) in nodes:
        if tag in ("startEvent", "endEvent"):
            w, h = 36, 36
        elif "Gateway" in tag:
            w = h = 50
        else:
            w, h = 110, 80
        shapes.append(f'<bpmndi:BPMNShape id="{nid}_di" bpmnElement="{nid}">'
                      f'<dc:Bounds x="{x}" y="160" width="{w}" height="{h}" /></bpmndi:BPMNShape>')
        x += 150
    for (eid, *_rest) in edges:
        nes.append(f'<bpmndi:BPMNEdge id="{eid}_di" bpmnElement="{eid}">'
                   f'<di:waypoint x="0" y="0" /><di:waypoint x="1" y="1" /></bpmndi:BPMNEdge>')

    body_nodes = "\n".join(
        f'    <bpmn:{tag} id="{nid}" name="{name}" {extra} />'
        for (nid, name, tag, extra) in nodes).rstrip()
    body_edges = ""
    for (eid, src, tgt, cond) in edges:
        if cond:
            body_edges += (f'    <bpmn:sequenceFlow id="{eid}" sourceRef="{src}" targetRef="{tgt}">\n'
                           f'      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">'
                           f'{cond}</bpmn:conditionExpression>\n'
                           f'    </bpmn:sequenceFlow>\n')
        else:
            body_edges += f'    <bpmn:sequenceFlow id="{eid}" sourceRef="{src}" targetRef="{tgt}" />\n'
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"\n'
        '  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"\n'
        '  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"\n'
        '  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"\n'
        '  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n'
        f'  id="{def_id}" targetNamespace="http://bpmn.io/schema/bpmn">\n'
        f'  <bpmn:process id="{proc_id}" isExecutable="false">\n'
        f'{body_nodes}\n{body_edges}'
        '  </bpmn:process>\n'
        f'  <bpmndi:BPMNDiagram id="{def_id}_diag">\n'
        f'    <bpmndi:BPMNPlane id="{def_id}_plane" bpmnElement="{proc_id}">\n'
        '      ' + "\n      ".join(shapes) + "\n"
        '      ' + "\n      ".join(nes) + "\n"
        '    </bpmndi:BPMNPlane>\n'
        '  </bpmndi:BPMNDiagram>\n'
        '</bpmn:definitions>\n')


class Ctx:
    def __init__(self, token):
        self.token = token
        self.made_wf = []
        self.made_run = []
        self.made_exp = []

    def new_wf(self, name, nodes, edges, attr=None):
        xml = build_xml(nodes, edges)
        wid = api("/api/workflows", "POST",
                  {"name": name, "code": f"MTRX-{int(time.time()*1000)}"}, self.token)["id"]
        api(f"/api/workflows/{wid}/graph", "PUT", raw=xml, token=self.token)
        if attr:
            q(f"UPDATE workflow SET attr = COALESCE(attr,'{{}}')::jsonb || '{json.dumps(attr)}'::jsonb "
              f"WHERE id={wid};")
        self.made_wf.append(wid)
        return wid

    def bind(self, wid, key, method, params=None, resource=None):
        body = {"method": method}
        if params is not None:
            body["params"] = params
        if resource is not None:
            body["resourceId"] = resource
        api(f"/api/workflows/{wid}/nodes/{key}", "PUT", body, self.token)

    # 等待预算：设备方法耗时随机 30–300 秒（真机时序）⇒ 90 秒不够用，
    # 会把「设备还在跑」误判成「流程没跑完」。这里放宽到 420 秒：
    # 只在设备真的慢时才会等满，正常秒级完成不受影响。
    def run(self, wid, vars_=None, wait=420):
        path = f"/api/workflows/{wid}/run?async=1"
        if vars_:
            path += "&vars=" + urllib.parse.quote(",".join(f"{k}={v}" for k, v in vars_.items()), safe="")
        r = api(path, "POST", {}, self.token)
        rid = int(r.get("runId") or q("SELECT max(id) FROM run"))
        self.made_run.append(rid)
        deadline = time.time() + wait
        while time.time() < deadline:
            st = q(f"SELECT COALESCE(status::text,'') FROM run WHERE id={rid}")
            if st in ("completed", "failed", "aborted", "paused"):
                return rid, st
            time.sleep(1)
        return rid, q(f"SELECT COALESCE(status::text,'') FROM run WHERE id={rid}")

    def nodes(self, rid):
        return q(f"SELECT node_key||'='||status::text FROM run_node "
                 f"WHERE run_id={rid} AND level=0 ORDER BY id")

    def wait_devices_idle(self, limit=120):
        """等设备回到 idle 再删数据。

        ⚠ 为什么必须等：设备方法耗时随机 30–300 秒（真机时序），
        设备还在执行时删掉 run_node ⇒ 设备回包后落库步骤行时父节点已不存在 ⇒
        `run_node_parent_node_id_fkey` 违反 ⇒ 门禁的「步骤落库失败」断言变红
        （2026-11-06 实测：设备链路段被别的段落的清理搞红）。
        """
        deadline = time.time() + limit
        while time.time() < deadline:
            busy = q("SELECT count(*) FROM device_state "
                     "WHERE tenant_id=1 AND status='running'")
            if not busy or busy == "0":
                return True
            time.sleep(0.5)
        return False

    def cleanup(self):
        self.wait_devices_idle()
        for eid in self.made_exp:
            q(f"DELETE FROM experiment_process_edge WHERE experiment_id={eid};"
              f"DELETE FROM experiment_process WHERE experiment_id={eid};"
              f"DELETE FROM experiment_run WHERE experiment_id={eid};"
              f"DELETE FROM experiment WHERE id={eid};")
        for rid in self.made_run:
            q(f"DELETE FROM run_command WHERE run_id={rid};"
              f"DELETE FROM run_node WHERE run_id={rid};"
              f"UPDATE position_state SET reserved_by_run=NULL, block_reason=NULL, reserved_until=NULL "
              f"WHERE tenant_id=1 AND reserved_by_run={rid};"
              f"DELETE FROM run WHERE id={rid};")
        for wf in self.made_wf:
            q(f"DELETE FROM workflow_node WHERE workflow_id={wf};"
              f"DELETE FROM workflow WHERE id={wf};")


def expect_run(ctx, wid, vars_, want, label):
    rid, st = ctx.run(wid, vars_)
    if st == want:
        ok(f"{label}：整单 {st}")
    else:
        bad(f"{label}：整单应为 {want}", f"实际 {st}；节点 {ctx.nodes(rid)}")
    return rid


# ---------------------------------------------------------------- 各节

def sec_serial(ctx, d1, d2, m1):
    step("serial · START / END / TASK 串行")
    wid = ctx.new_wf("MTRX-串行",
                     [("S", "开始", "startEvent", ""),
                      ("T1", "任务一", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("T2", "任务二", "task", f'protocolName="{m1}" resource="{d2}"'),
                      ("E", "结束", "endEvent", "")],
                     [("f1", "S", "T1", None), ("f2", "T1", "T2", None), ("f3", "T2", "E", None)])
    rid = expect_run(ctx, wid, None, "completed", "serial")
    n = ctx.nodes(rid)
    ok("T1 completed") if "T1=completed" in n else bad("T1 未完成", n)
    ok("T2 completed") if "T2=completed" in n else bad("T2 未完成", n)
    c = q(f"SELECT count(*) FROM run_command WHERE run_id={rid}")
    ok(f"两条设备命令都发出（{c}）") if c == "2" else bad("命令数不对", c)


def sec_builtin(ctx, d1, m1):
    step("builtin · __setVar / __expr / 变量驱动网关")
    wid = ctx.new_wf("MTRX-内置节点",
                     [("S", "开始", "startEvent", ""),
                      ("SET", "初始化计数", "task", 'protocolName="__setVar"'),
                      ("INC", "计数+10", "task", 'protocolName="__expr"'),
                      ("X", "计数==10?", "exclusiveGateway", 'default="f_b"'),
                      ("A", "走A", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("B", "走B(default)", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("E", "结束", "endEvent", "")],
                     [("f1", "S", "SET", None),
                      ("f2", "SET", "INC", None),
                      ("f3", "INC", "X", None),
                      ("f_a", "X", "A", "${counter} == 10"),
                      ("f_b", "X", "B", None),
                      ("f4", "A", "E", None), ("f5", "B", "E", None)])
    ctx.bind(wid, "SET", "__setVar", {"var": "counter", "value": "0"})
    ctx.bind(wid, "INC", "__expr", {"var": "counter", "expr": "${counter} + 10"})
    rid = expect_run(ctx, wid, None, "completed", "builtin")
    n = ctx.nodes(rid)
    ok("SET/INC 都执行过") if "SET=completed" in n and "INC=completed" in n else bad("内置节点没跑", n)
    ok("变量 counter=10 成立 → 走 A（证明 __setVar+__expr 真的改了变量）") if "A=completed" in n \
        else bad("变量没生效（没走 A）", n)
    ok("未选中的 B 为 bypassed") if "B=bypassed" in n else bad("B 状态不对", n)


def sec_outvar(ctx, robot, m1):
    step("outvar · 设备回包字段 → 变量 → 条件")
    if not robot:
        skip("没有可用的机械臂设备（需要 GetPosition 返回 data.position），跳过")
        return
    wid = ctx.new_wf("MTRX-输出驱动条件",
                     [("S", "开始", "startEvent", ""),
                      ("T", "读位置", "task", f'protocolName="GetPosition" resource="{robot}"'),
                      ("X", "位置=A1?", "exclusiveGateway", 'default="f_b"'),
                      ("A", "走A", "task", f'protocolName="{m1}" resource="{robot}"'),
                      ("B", "走B(default)", "task", f'protocolName="{m1}" resource="{robot}"'),
                      ("E", "结束", "endEvent", "")],
                     [("f1", "S", "T", None), ("f2", "T", "X", None),
                      ("f_a", "X", "A", "${position} == 'A1'"),
                      ("f_b", "X", "B", None),
                      ("f4", "A", "E", None), ("f5", "B", "E", None)])
    rid = expect_run(ctx, wid, None, "completed", "outvar")
    n = ctx.nodes(rid)
    ok("设备回包字段进变量表 → 条件成立走 A") if "A=completed" in n \
        else bad("输出没进变量（没走 A）", n)


def sec_xor(ctx, d1, m1):
    step("xor · 异或网关（真 / 假走 default）")
    def mk(tag):
        return ctx.new_wf(f"MTRX-异或-{tag}",
                          [("S", "开始", "startEvent", ""),
                           ("X", "分支", "exclusiveGateway", 'default="f_b"'),
                           ("A", "走A", "task", f'protocolName="{m1}" resource="{d1}"'),
                           ("B", "走B(default)", "task", f'protocolName="{m1}" resource="{d1}"'),
                           ("E", "结束", "endEvent", "")],
                          [("f1", "S", "X", None),
                           ("f_a", "X", "A", "${ok} == true"),
                           ("f_b", "X", "B", None),
                           ("f4", "A", "E", None), ("f5", "B", "E", None)])
    wid = mk("1")
    rid = expect_run(ctx, wid, {"ok": "true"}, "completed", "xor(真)")
    n = ctx.nodes(rid)
    ok("条件为真走 A") if "A=completed" in n else bad("没走 A", n)
    ok("B 被标记 bypassed") if "B=bypassed" in n else bad("B 未标记未选中", n)

    rid = expect_run(ctx, wid, {"ok": "false"}, "completed", "xor(假)")
    n = ctx.nodes(rid)
    ok("条件为假走 default(B)") if "B=completed" in n else bad("没走 B", n)
    ok("A 被标记 bypassed") if "A=bypassed" in n else bad("A 未标记未选中", n)


def sec_xor3(ctx, d1, m1):
    step("xor3 · 三分支异或（声明顺序求值）")
    wid = ctx.new_wf("MTRX-三分支",
                     [("S", "开始", "startEvent", ""),
                      ("X", "三选一", "exclusiveGateway", 'default="f_c"'),
                      ("A", "A", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("B", "B", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("C", "C", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("E", "结束", "endEvent", "")],
                     [("f1", "S", "X", None),
                      ("f_a", "X", "A", "${v} == 1"),
                      ("f_b", "X", "B", "${v} == 2"),
                      ("f_c", "X", "C", None),
                      ("f4", "A", "E", None), ("f5", "B", "E", None), ("f6", "C", "E", None)])
    for v, want in (("1", "A"), ("2", "B"), ("9", "C")):
        rid, st = ctx.run(wid, {"v": v})
        n = ctx.nodes(rid)
        if st == "completed" and f"{want}=completed" in n:
            ok(f"v={v} → 走 {want}")
        else:
            bad(f"v={v} 应走 {want}", f"st={st}；{n}")


def sec_loop(ctx, d1, m1):
    step("loop · 回边循环 + maxIterations + loop_index")
    wid = ctx.new_wf("MTRX-循环",
                     [("S", "开始", "startEvent", ""),
                      ("T", "循环体", "task", f'protocolName="{m1}" resource="{d1}"'),
                      ("X", "还继续?", "exclusiveGateway", 'default="f_end"'),
                      ("E", "结束", "endEvent", "")],
                     [("f1", "S", "T", None), ("f2", "T", "X", None),
                      ("f_back", "X", "T", "${go} == 'yes'"),
                      ("f_end", "X", "E", None)],
                     attr={"maxIterations": 3})
    rid, st = ctx.run(wid, {"go": "yes"})
    c = q(f"SELECT count(*) FROM run_command WHERE run_id={rid}")
    rows = q(f"SELECT node_key||':loop='||loop_index||':'||status::text "
             f"FROM run_node WHERE run_id={rid} AND level=0 ORDER BY id")
    rounds = q(f"SELECT count(*) FROM run_node WHERE run_id={rid} AND node_key='T' AND loop_index>0")
    print("      节点圈次：\n" + "\n".join("        " + x for x in rows.splitlines()))
    if st == "completed" and c == "3":
        ok("回边跑满上限：整单 completed，命令 3 条")
    else:
        bad("循环圈数不对", f"st={st} cmds={c}")
    ok(f"每圈都有独立 run_node（中间态 {rounds} 圈）") if rounds == "3" \
        else bad("loop_index 不完整", rounds)

    rid, st = ctx.run(wid, None)
    c2 = q(f"SELECT count(*) FROM run_command WHERE run_id={rid}")
    if st == "completed" and c2 == "1":
        ok("不满足条件时不走回边（命令 1 条）")
    else:
        bad("条件为假仍循环", f"st={st} cmds={c2}")


def sec_loopback(ctx, d1, d2, m1):
    step("loopback · 回边跨越并行分叉（循环体里再进并行）")
    da, db = d1, (d2 or d1)

    # 支持形态：回边指向并行区**之前**的任务 —— 循环体整段包含并行区
    wid = ctx.new_wf("MTRX-回边并行",
                     [("S", "开始", "startEvent", ""),
                      ("T0", "循环入口", "task", f'protocolName="{m1}" resource="{da}"'),
                      ("SP", "分叉", "parallelGateway", ""),
                      ("T1", "分支一", "task", f'protocolName="{m1}" resource="{da}"'),
                      ("T2", "分支二", "task", f'protocolName="{m1}" resource="{db}"'),
                      ("JN", "汇合", "parallelGateway", ""),
                      ("X", "还继续?", "exclusiveGateway", 'default="f_end"'),
                      ("E", "结束", "endEvent", "")],
                     [("f0", "S", "T0", None), ("f1", "T0", "SP", None),
                      ("f2", "SP", "T1", None), ("f3", "SP", "T2", None),
                      ("f4", "T1", "JN", None), ("f5", "T2", "JN", None),
                      ("f6", "JN", "X", None),
                      ("f_back", "X", "T0", "${go} == 'yes'"),
                      ("f_end", "X", "E", None)],
                     attr={"maxIterations": 2})
    rid, st = ctx.run(wid, {"go": "yes"})
    rows = q(f"SELECT node_key||':loop='||loop_index||':'||status::text "
             f"FROM run_node WHERE run_id={rid} AND level=0 "
             f"AND node_key IN ('T0','T1','T2') ORDER BY id")
    print("      节点圈次：\n" + "\n".join("        " + x for x in rows.splitlines()))
    ok("整单 completed（循环体含并行区不死锁）") if st == "completed" \
        else bad("回边并行未完成", f"st={st}；{ctx.nodes(rid)}")
    # 关键：每一圈里并行两支都必须 completed —— 若汇合计数器未按圈复位，
    # 会出现某圈只有一个分支完成（或整单卡死）
    pairs = q(f"""SELECT count(*) FROM (
                   SELECT loop_index,
                          count(*) FILTER (WHERE node_key='T1' AND status='completed') a,
                          count(*) FILTER (WHERE node_key='T2' AND status='completed') b
                     FROM run_node WHERE run_id={rid} AND level=0
                    GROUP BY loop_index) t
                  WHERE a=1 AND b=1""")
    ok(f"每圈并行两分支都 completed（{pairs} 圈成对完成）") if int(pairs or 0) >= 2 \
        else bad("某圈并行分支未配齐（汇合可能未按圈汇合）", pairs)

    # 边界观察：回边**直接指向并行分叉网关**时，并行体被整体 bypass（已知限制）
    widc = ctx.new_wf("MTRX-回边直入网关",
                      [("S", "开始", "startEvent", ""),
                       ("SP", "分叉", "parallelGateway", ""),
                       ("T1", "分支一", "task", f'protocolName="{m1}" resource="{da}"'),
                       ("T2", "分支二", "task", f'protocolName="{m1}" resource="{db}"'),
                       ("JN", "汇合", "parallelGateway", ""),
                       ("X", "还继续?", "exclusiveGateway", 'default="f_end"'),
                       ("E", "结束", "endEvent", "")],
                      [("f1", "S", "SP", None),
                       ("f2", "SP", "T1", None), ("f3", "SP", "T2", None),
                       ("f4", "T1", "JN", None), ("f5", "T2", "JN", None),
                       ("f6", "JN", "X", None),
                       ("f_back", "X", "SP", "${go} == 'yes'"),
                       ("f_end", "X", "E", None)],
                      attr={"maxIterations": 2})
    ridc, stc = ctx.run(widc, {"go": "yes"})
    exc = q(f"SELECT count(*) FROM run_command WHERE run_id={ridc}")
    if stc == "completed" and exc == "0":
        print("  \033[33m·\033[0m 观察：回边**直接指向并行分叉网关**时整单报 completed，但并行体被整体 "
              "bypass（0 条命令）→ 已知限制；回边应指向并行区之前的任务 "
              "（见 docs/完整流程测试矩阵-设计.md C 类边界）")
    elif int(exc or 0) > 0:
        ok(f"回边直入并行网关也可执行（{exc} 条命令）")
    else:
        bad("回边直入并行网关行为异常", f"st={stc} cmds={exc}")


def sec_parallel(ctx, d1, d2, m1):
    step("parallel · 并行分叉/汇合（同设备串行 / 跨设备并行）")
    def mk(name, da, db):
        return ctx.new_wf(name,
                          [("S", "开始", "startEvent", ""),
                           ("SP", "分叉", "parallelGateway", ""),
                           ("T1", "分支一", "task", f'protocolName="{m1}" resource="{da}"'),
                           ("T2", "分支二", "task", f'protocolName="{m1}" resource="{db}"'),
                           ("JN", "汇合", "parallelGateway", ""),
                           ("E", "结束", "endEvent", "")],
                          [("f1", "S", "SP", None), ("f2", "SP", "T1", None), ("f3", "SP", "T2", None),
                           ("f4", "T1", "JN", None), ("f5", "T2", "JN", None), ("f6", "JN", "E", None)])
    wid = mk("MTRX-并行-同设备", d1, d1)
    rid, st = ctx.run(wid)
    n = ctx.nodes(rid)
    ov = q(f"""SELECT count(*) FROM run_command a JOIN run_command b
                ON a.run_id=b.run_id AND a.id<b.id
               WHERE a.run_id={rid} AND a.sent_at<b.finished_at AND b.sent_at<a.finished_at""")
    ok("同设备两分支都 completed") if st == "completed" and "T1=completed" in n and "T2=completed" in n \
        else bad("同设备并行未完成", f"st={st}；{n}")
    ok("同设备命令时间段不重叠（DeviceLock 串行）") if ov == "0" else bad("命令重叠", ov)

    if d2 != d1:
        wid2 = mk("MTRX-并行-跨设备", d1, d2)
        rid2, st2 = ctx.run(wid2)
        n2 = ctx.nodes(rid2)
        ok("跨设备两分支都 completed") if st2 == "completed" and "T1=completed" in n2 and "T2=completed" in n2 \
            else bad("跨设备并行未完成", f"st={st2}；{n2}")
    else:
        skip("只有一台设备，跨设备并行未验证")


def sec_pause(ctx, d1, args):
    step("pause · 暂停三态 + 恢复 + 异常暂停自动续")
    base_wf = q("SELECT workflow_id FROM run WHERE workflow_id IS NOT NULL ORDER BY id DESC LIMIT 1")
    base_mode = q("SELECT COALESCE(mode::text,'normal') FROM run ORDER BY id DESC LIMIT 1")
    if not base_wf:
        skip("库里没有可借用的 run/workflow，跳过暂停节")
        return

    def mk_run(tag):
        rid = q(f"""INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                     mode, status, actual_start, progress)
                    SELECT 1, 'MTRX-{tag}-{int(time.time()*1000)}', 'workflow', {base_wf}, 1,
                           '{base_mode}', 'running', now(), 0
                    FROM run ORDER BY id DESC LIMIT 1 RETURNING id""")
        rid = rid.strip().split()[0] if rid else ""
        if rid:
            ctx.made_run.append(int(rid))
        return rid

    # ① 人工暂停（waiting_human）→ 恢复（retry，不动执行体）
    rid = mk_run("human")
    r = api(f"/api/runs/{rid}/pause?kind=waiting_human&reason=matrix", "POST", {}, ctx.token)
    k = q(f"SELECT COALESCE(pause_kind,'') FROM run WHERE id={rid}")
    st = q(f"SELECT status::text FROM run WHERE id={rid}")
    ok("waiting_human 暂停成功（API+库一致）") if r.get("ok") and k == "waiting_human" and st == "paused" \
        else bad("人工暂停失败", f"{r} kind={k} st={st}")
    lst = api("/api/runs/paused", token=ctx.token)
    ok("暂停队列能看到它") if any(x.get("runId") == int(rid) for x in lst) else bad("暂停队列里没有")
    r = api(f"/api/runs/{rid}/resume?action=retry", "POST", {}, ctx.token)
    st = q(f"SELECT status::text FROM run WHERE id={rid}")
    ok("恢复后回到 queued（不假装 running）") if r.get("ok") and st == "queued" \
        else bad("恢复未回到 queued", f"{r} st={st}")

    # ② paused_unknown：无 corrections 必须被拒
    rid = mk_run("unknown")
    api(f"/api/runs/{rid}/pause?kind=paused_unknown&reason=matrix", "POST", {}, ctx.token)
    r = api(f"/api/runs/{rid}/resume?action=continue", "POST", {}, ctx.token)
    st = q(f"SELECT status::text FROM run WHERE id={rid}")
    ok("位置未知、无校正 → 拒绝恢复") if r.get("ok") is False else bad("竟然恢复成功", r)
    ok("拒绝原因提到 corrections") if "corrections" in (r.get("error") or "") else bad("原因不清", r)
    ok("被拒后仍停在 paused") if st == "paused" else bad("状态被改动", st)

    # ③ paused_unknown + corrections → 恢复成功，且把"被这块板封住"的位置解封
    #    （action=retry：不走真续跑，只验校正 + 解封链路）
    res = q("SELECT resource_id FROM device_state WHERE tenant_id=1 AND online=true "
            "ORDER BY resource_id LIMIT 1")
    if not res:
        skip("没有在线设备可作为盘点位置载体，跳过带校正恢复")
    else:
        res = int(res)
        q(f"INSERT INTO position_state (tenant_id, resource_id, updated_at) "
          f"VALUES (1, {res}, now()) ON CONFLICT (tenant_id, resource_id) DO UPDATE SET updated_at = now();")
        q(f"UPDATE position_state SET reserved_by_run = -1, block_reason = 'PLATE_UNKNOWN', "
          f"reserved_until = now() + interval '24 hours', updated_at = now() "
          f"WHERE tenant_id = 1 AND resource_id = {res};")
        q(f"""INSERT INTO plate (tenant_id, barcode, status, position_status, possible_locations)
              VALUES (1, 'PL-MTRX', 'active', 'UNKNOWN', ARRAY[{res}]::bigint[])
              ON CONFLICT (tenant_id, barcode) DO UPDATE
              SET position_status='UNKNOWN', possible_locations=ARRAY[{res}]::bigint[]""")
        r = api(f"/api/runs/{rid}/resume?action=retry", "POST",
                {"corrections": {"PL-MTRX": "ID1183-L2"}}, ctx.token)
        pst = q("SELECT position_status FROM plate WHERE tenant_id=1 AND barcode='PL-MTRX'")
        blk = q(f"SELECT COALESCE(block_reason,'') FROM position_state "
                f"WHERE tenant_id=1 AND resource_id={res}")
        ok("带校正恢复成功，板状态回到 KNOWN") if r.get("ok") and pst == "KNOWN" \
            else bad("带校正恢复失败", f"{r} plate={pst}")
        ok("被这块板封住的位置已解封（block_reason 清空）") if blk == "" \
            else bad("位置没解封", f"block_reason={blk}")
        q("DELETE FROM plate_location_audit WHERE tenant_id=1 AND barcode='PL-MTRX';")
        q("DELETE FROM plate WHERE tenant_id=1 AND barcode='PL-MTRX';")
        q(f"DELETE FROM position_state WHERE tenant_id=1 AND resource_id={res};")

    # ④ paused_fault：设备侧恢复 → 心跳自动续（异常暂停 + 自动恢复）
    #    产品语义：autoResumeFaulted 要求**租户内没有任何离线设备**才自动续。
    #    本库有一批连不上的历史 driver（tcp://192.168.*），心跳每轮把它们探成离线，
    #    所以默认永不触发 —— 这是环境前提，不是脚本 bug。
    #    --force-online：临时停用这些不可达 driver（心跳就不再改写它们的状态）、
    #    把离线设备置在线，实测自动续，跑完立即还原。
    def wait_running(rid, secs=60):
        for _ in range(secs):
            time.sleep(1)
            if q(f"SELECT status::text FROM run WHERE id={rid}") == "running":
                return True
        return False

    rid = mk_run("fault")
    api(f"/api/runs/{rid}/pause?kind=paused_fault&reason=matrix", "POST", {}, ctx.token)
    st0 = q(f"SELECT status::text FROM run WHERE id={rid}")
    ok("paused_fault 已暂停") if st0 == "paused" else bad("故障暂停失败", st0)

    offline = int(q("SELECT count(*) FROM device_state WHERE tenant_id=1 AND online=false") or 0)
    if offline == 0:
        ok("设备侧全在线 → 心跳自动恢复（无需人工）") if wait_running(rid) \
            else bad("未自动恢复（检查心跳服务）")
    elif not args.force_online:
        skip(f"autoResumeFaulted 要求租户内无离线设备才自动续，当前有 {offline} 台离线 → 不触发；"
             f"--force-online 可临时停用不可达 driver 实测（见 docs/完整流程测试矩阵-设计.md C 类边界）")
    else:
        # 快照：被离线设备牵连的 driver（跑完按原样还原）
        snap = [x for x in q("""SELECT DISTINCT di.driver_id || '|' || d.status
                                  FROM device_state ds
                                  JOIN device_instance di ON di.resource_id = ds.resource_id
                                       AND di.tenant_id = 1
                                  JOIN driver d ON d.id = di.driver_id
                                 WHERE ds.tenant_id = 1 AND ds.online = false""").split() if x]
        drv_ids = [s.split("|")[0] for s in snap]
        offline_ids = [x for x in q("SELECT resource_id FROM device_state "
                                    "WHERE tenant_id=1 AND online=false").split() if x]
        if drv_ids:
            q("UPDATE driver SET status = 0 WHERE id IN (" + ",".join(drv_ids) + ")")

        # 规避与「在途心跳周期」的写竞争：
        # 心跳 probeOnce() 先 loadDrivers() 再 writeDeviceOnline()。若在它两步之间
        # 才停用 driver，这一轮仍会把旧 driver 名下设备写回离线；而 driver 已停用，
        # 后续轮次不再覆盖 → 离线值永久卡住。先等一个周期让在途轮次落完，再置在线并确认稳定为 0。
        def settle_online(tries=8):
            for _ in range(tries):
                q("UPDATE device_state SET online = true WHERE tenant_id = 1 AND online = false")
                time.sleep(2)
                if int(q("SELECT count(*) FROM device_state WHERE tenant_id=1 "
                         "AND online=false") or 0) == 0:
                    return True
            return False

        time.sleep(6)
        settled = settle_online()
        print(f"      · 临时停用 {len(drv_ids)} 个不可达 driver、置在线 {len(offline_ids)} 台设备"
              f"（稳定={'是' if settled else '否'}，跑完还原）")
        if not settled:
            bad("无法把租户内离线设备清零（心跳写竞争未收敛）")
        else:
            ok("设备侧全在线 → 心跳自动恢复（无需人工）") if wait_running(rid) \
                else bad("未自动恢复（检查心跳服务）")

        # 还原
        for s in snap:
            did, st = s.split("|")
            q(f"UPDATE driver SET status = {st} WHERE id = {did}")
        if offline_ids:
            q("UPDATE device_state SET online = false WHERE tenant_id = 1 AND resource_id IN ("
              + ",".join(offline_ids) + ")")
        print(f"      · 已还原 {len(drv_ids)} 个 driver、{len(offline_ids)} 台设备")


def sec_ports(ctx, robot, m1):
    step("ports · 进出口拓扑（both / in+out 分开 / 多进口）")
    if "LABOS_TRANSFER_PLAN" not in kernel_env():
        skip("内核未开 LABOS_TRANSFER_PLAN，TransferPlan 通道锁不会启用 —— "
             "需以该环境变量重启 18080 后重跑本节")
        return
    if not robot:
        skip("没有可用的机械臂设备，跳过")
        return
    src = q("SELECT resource_id FROM device_state WHERE tenant_id=1 AND online=true "
            "ORDER BY resource_id LIMIT 1")
    dst = q("SELECT resource_id FROM device_state WHERE tenant_id=1 AND online=true "
            f"AND resource_id <>{src or '0'} ORDER BY resource_id LIMIT 1")
    if not src or not dst:
        skip("设备不足，跳过进出口拓扑")
        return
    src_code = q(f"SELECT code FROM resource_node WHERE id={src}")
    dst_code = q(f"SELECT code FROM resource_node WHERE id={dst}")
    if not src_code or not dst_code:
        skip("设备没有 resource_node.code，跳过")
        return

    tag = f"MTRX{int(time.time() % 100000)}"

    def add_port(code, direction, capacity):
        pid = q(f"""INSERT INTO device_port (tenant_id, resource_id, code, direction, port_type, capacity)
                    VALUES (1, {src}, '{code}', '{direction}', 'slot', {capacity}) RETURNING id""")
        pid = pid.strip().split()[0]
        q(f"""INSERT INTO device_port_usage (tenant_id, port_id, in_use, max_capacity)
              VALUES (1, {pid}, 0, {capacity}) ON CONFLICT DO NOTHING""")
        return pid

    def transfer_wf(name):
        wid = ctx.new_wf(name,
                         [("S", "开始", "startEvent", ""),
                          ("T", "搬运", "task", f'protocolName="Transfer" resource="{robot}"'),
                          ("E", "结束", "endEvent", "")],
                         [("f1", "S", "T", None), ("f2", "T", "E", None)])
        ctx.bind(wid, "T", "Transfer", {"source": src_code, "target": dst_code}, robot)
        return wid

    def used(pid):
        return q(f"SELECT in_use FROM device_port_usage WHERE tenant_id=1 AND port_id={pid}")

    # A) 共用（both）
    pa = add_port(f"{tag}-BOTH", "both", 2)
    wid = transfer_wf("MTRX-端口-共用")
    ctx.run(wid)
    ok("共用通道(both)：结束后占用归零") if used(pa) == "0" else bad("both 通道没释放", used(pa))

    # B) 进 / 出 分开
    pin = add_port(f"{tag}-IN", "in", 1)
    pout = add_port(f"{tag}-OUT", "out", 1)
    t0 = q(f"SELECT COALESCE(max(updated_at)::text,'') FROM device_port_usage WHERE port_id IN ({pin},{pout})")
    wid = transfer_wf("MTRX-端口-分开")
    ctx.run(wid)
    in_t = q(f"SELECT updated_at::text FROM device_port_usage WHERE port_id={pin}")
    out_t = q(f"SELECT updated_at::text FROM device_port_usage WHERE port_id={pout}")
    ok("分开(in/out)：结束后占用归零") if used(pin) == "0" and used(pout) == "0" \
        else bad("分开通道没释放", f"in={used(pin)} out={used(pout)}")
    # 断言"out 从未被消费"——这是当前实现的**已知边界**，不是 bug
    ok("in 通道被占用过（updated_at 变化）") if in_t and in_t != t0 else bad("in 通道没被用", in_t)
    if out_t == t0:
        print("  \033[33m·\033[0m 观察：out 通道 updated_at 未变 → 源/目标通道固定取 'in'，"
              "out 方向在当前执行链未被消费（已知边界，见 docs/完整流程测试矩阵-设计.md）")
    else:
        bad("out 通道被占用（与设计不符，应固定取 in）", out_t)

    # C) 多进口
    p1 = add_port(f"{tag}-IN1", "in", 1)
    p2 = add_port(f"{tag}-IN2", "in", 3)
    wid = transfer_wf("MTRX-端口-多进口")
    ctx.run(wid)
    ok("多进口：结束后全部归零") if used(p1) == "0" and used(p2) == "0" \
        else bad("多进口没释放", f"{used(p1)}/{used(p2)}")

    q(f"DELETE FROM device_port_usage WHERE port_id IN "
      f"(SELECT id FROM device_port WHERE code LIKE '{tag}%');")
    q(f"DELETE FROM device_port WHERE code LIKE '{tag}%';")


def sec_experiment(ctx, island, d1, m1):
    step("experiment · 实验内多个流程（拓扑展开）")
    if not island:
        skip("没有可用的岛，跳过实验节")
        return

    def one(name, dev):
        return ctx.new_wf(name,
                          [("S", "开始", "startEvent", ""),
                           ("T", "任务", "task", f'protocolName="{m1}" resource="{dev}"'),
                           ("E", "结束", "endEvent", "")],
                          [("f1", "S", "T", None), ("f2", "T", "E", None)])

    w1, w2 = one("MTRX-实验-流程1", d1), one("MTRX-实验-流程2", d1)
    base = int(q("SELECT COALESCE(max(id),0) FROM run") or 0)
    eid = api("/api/experiments", "POST",
              {"name": f"MTRX-实验-{int(time.time()*1000)}", "islandId": island,
               "workflowIds": [w1, w2]}, ctx.token)["id"]
    ctx.made_exp.append(eid)
    procs = q(f"SELECT count(*) FROM experiment_process WHERE tenant_id=1 AND experiment_id={eid}")
    ok(f"实验内登记了两个流程（process={procs}）") if procs == "2" else bad("实验流程数不对", procs)

    r = api(f"/api/experiments/{eid}/run?async=1", "POST", {}, ctx.token)
    erid = int(r.get("experimentRunId") or r.get("id") or 0)
    st = ""
    deadline = time.time() + 120
    while erid and time.time() < deadline:
        st = q(f"SELECT status::text FROM experiment_run WHERE id={erid}")
        if st in ("completed", "failed", "aborted"):
            break
        time.sleep(1)
    ok(f"实验整单 {st}") if st == "completed" else bad("实验未完成", f"st={st} erid={erid}")
    n = q(f"SELECT count(DISTINCT workflow_id) FROM run "
          f"WHERE id>{base} AND workflow_id IN ({w1},{w2})")
    ok("实验内两个流程都产生了执行单") if n == "2" else bad("流程未全部执行", n)
    for rid in [x for x in q(f"SELECT id FROM run WHERE id>{base} AND workflow_id IN ({w1},{w2})").split() if x]:
        ctx.made_run.append(int(rid))


def sec_concurrent(ctx, d1, d2, m1):
    step("concurrent · 多流程并发（多个独立 run 同时跑）")

    def one(name, dev):
        return ctx.new_wf(name,
                          [("S", "开始", "startEvent", ""),
                           ("T", "任务", "task", f'protocolName="{m1}" resource="{dev}"'),
                           ("E", "结束", "endEvent", "")],
                          [("f1", "S", "T", None), ("f2", "T", "E", None)])

    w1, w2 = one("MTRX-并发-1", d1), one("MTRX-并发-2", d2)
    res, lock = {}, threading.Lock()

    def go(tag, wid):
        _rid, st = ctx.run(wid)
        with lock:
            res[tag] = st

    ths = [threading.Thread(target=go, args=("A", w1)),
           threading.Thread(target=go, args=("B", w2))]
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    ok("两个流程并发提交、各自完成") if res.get("A") == "completed" and res.get("B") == "completed" \
        else bad("并发流程未全部完成", str(res))


def kernel_env():
    """聚合**所有** ApiServer 相关进程的环境变量。

    为什么不能只取第一个 pid：内核常以 `bash -c '... java ... ApiServer ...'` 两层起，
    pgrep 会同时命中外壳与 java，而环境只挂在 java 那层；取 pid[0] 若恰是外壳，
    就读不到 LABOS_TRANSFER_PLAN，ports 一节会假 SKIP（45 → 41 条）。
    """
    try:
        pids = subprocess.run(["pgrep", "-f", "ApiServer"], capture_output=True, text=True).stdout.split()
        if not pids:
            return ""
        return "\n".join(
            subprocess.run(["ps", "eww", p], capture_output=True, text=True).stdout for p in pids
        )
    except Exception:  # noqa: BLE001
        return ""


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--section", default="all",
                    help="serial,builtin,outvar,xor,xor3,loop,loopback,parallel,pause,ports,experiment,concurrent | all")
    ap.add_argument("--device", type=int, default=0)
    ap.add_argument("--device2", type=int, default=0)
    ap.add_argument("--method", default="Home")
    ap.add_argument("--force-online", action="store_true",
                    help="pause 节：临时停用不可达 driver 并置设备在线，实测异常暂停的心跳自动续（跑完还原）")
    args = ap.parse_args()

    for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"):
        if not os.environ.get(k):
            print(f"缺少环境变量 {k}")
            return 2

    try:
        token = api("/api/auth/login", "POST",
                    {"username": "admin", "password": "admin123"})["token"]
    except Exception as e:  # noqa: BLE001
        print(f"SKIP: 连不上 API {BASE}（{e}）")
        return 0

    # 选设备：只在**当前在线**（有模拟器+驱动）的设备里挑，否则会挑到没接驱动的
    # 存量设备，跑出来全是 manual/failed，看着像流程问题其实不是。
    recent = q("""SELECT n.resource_id FROM run_command c JOIN run_node n ON n.id=c.run_node_id
                   WHERE c.status='completed' AND n.resource_id IS NOT NULL
                     AND c.finished_at > now() - interval '6 hours'
                   GROUP BY 1 ORDER BY count(*) DESC LIMIT 1""")
    online = ("SELECT resource_id FROM device_state WHERE tenant_id=1 AND online=true")
    d1 = args.device or (int(recent) if recent and q(
        f"SELECT 1 FROM ({online}) x WHERE resource_id={recent}") else 0) \
        or int(q(f"{online} ORDER BY resource_id LIMIT 1") or 0)
    d2 = args.device2 or int(q(f"{online} AND resource_id<>{d1} ORDER BY resource_id LIMIT 1") or d1)
    robot = int(q("SELECT rc.resource_id FROM resource_capability rc "
                  "JOIN capability c ON c.id=rc.capability_id "
                  f"JOIN ({online}) ds ON ds.resource_id=rc.resource_id "
                  "WHERE c.code='TRANSFER' ORDER BY rc.resource_id LIMIT 1") or 0)
    island = int(q("""SELECT p.id FROM resource_node d JOIN resource_node p ON p.id = d.parent_id
                       WHERE d.kind = 'device' AND d.tenant_id = 1
                       GROUP BY p.id ORDER BY count(*) DESC LIMIT 1""") or 0)

    if not d1:
        print("SKIP: 库里没有任何设备实例")
        return 0
    print(f"设备：D1={d1} D2={d2} robot={robot or '-'} island={island or '-'}  "
          f"方法={args.method}  场景={args.section}")

    ctx = Ctx(token)
    sections = {
        "serial": lambda: sec_serial(ctx, d1, d2, args.method),
        "builtin": lambda: sec_builtin(ctx, d1, args.method),
        "outvar": lambda: sec_outvar(ctx, robot, args.method),
        "xor": lambda: sec_xor(ctx, d1, args.method),
        "xor3": lambda: sec_xor3(ctx, d1, args.method),
        "loop": lambda: sec_loop(ctx, d1, args.method),
        "loopback": lambda: sec_loopback(ctx, d1, d2, args.method),
        "parallel": lambda: sec_parallel(ctx, d1, d2, args.method),
        "pause": lambda: sec_pause(ctx, d1, args),
        "ports": lambda: sec_ports(ctx, robot, args.method),
        "experiment": lambda: sec_experiment(ctx, island, d1, args.method),
        "concurrent": lambda: sec_concurrent(ctx, d1, d2, args.method),
    }
    try:
        for name, fn in sections.items():
            if args.section in ("all", name):
                fn()
    finally:
        print("\n--- 清理 ---")
        ctx.cleanup()

    print()
    print("-" * 42)
    extra = f"（跳过 {SKIP} 节）" if SKIP else ""
    print(f"通过 {PASS} / 失败 {FAIL}{extra}")
    print("-" * 42)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
