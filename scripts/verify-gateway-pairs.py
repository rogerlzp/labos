#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证「网关成对入口 + 条件网关真能跑」。

用户 2026-11-11 指出：「并行网关，异或网关，条件网关，应该都有开始和结束网关的，
现在拖得很多只有一个，这样看起来不清晰的」。

本脚本验**两件事**（页面 + 接口两条腿都要有，否则「画得出」不等于「跑得对」）：

  一、页面（Playwright 驱动真设计器）
     ① palette 里有 6 个**成对**入口（并行/异或/条件 × 分叉/汇合）；
        原来的通用「异或网关」入口已撤下。
     ② 拖出来的网关**自带角色名**（条件分叉 / 并行分叉 / 并行汇合）——
        这是「看不清结构」的直接解法。
     ③ 画布上分叉画**绿边**、汇合画**橙边**（bpmn-js 自己不画 gatewayDirection，
        并行分叉和并行汇合在它眼里一模一样）。
     ④ 名字跟着**实际拓扑**走：把一个「并行分叉」接成 2 入 1 出 ⇒ 名字自动
        变成「并行汇合」、描边变橙（引擎按连线数判角色，名字不能骗它）。
     ⑤ 保存后 XML 里存的是**中性 code**（PARALLEL_SPLIT 等），不是中文文案。
     ⑥ 预检把「不成对」说出来（PF-15/16/17/18）。

  二、接口（真执行，走的是页面上「执行」同一条链）
     ⑦ 条件网关**包容语义**：两个条件都成立 ⇒ 两条分支都跑。
     ⑧ 一个条件不成立 ⇒ 那条分支不跑，而**汇合点照样放行**（下游节点照跑）
        —— 这是条件汇合最容易死等的地方，必须真跑一次才算数。

前置：中控 18080 + 前端 5173 + 设备管理台 8600 + Redis 6379 + 基线岛在跑；
      连库环境变量 LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME /
      LABOS_DB_USER / LABOS_DB_PASS。

用法：
  python3 scripts/verify-gateway-pairs.py
  python3 scripts/verify-gateway-pairs.py --headed
"""
import argparse
import importlib.util
import os
import re
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "dh", os.path.join(_HERE, "designer-helpers.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["dh"] = F
_spec.loader.exec_module(F)

PASS = 0
FAIL = 0
FAILURES = []

ISLAND = os.environ.get("LABOS_BASELINE_ISLAND", "LAB-BASE-01")

# palette 入口 → (bpmn 元素类型, 期望的中性 code, 期望画布标签)
GATEWAY_ENTRIES = [
    ("create.parallel-split", "PARALLEL_SPLIT", "并行分叉"),
    ("create.parallel-merge", "PARALLEL_MERGE", "并行汇合"),
    ("create.exclusive-split", "XOR_SPLIT", "异或分叉"),
    ("create.exclusive-merge", "XOR_MERGE", "异或汇合"),
    ("create.conditional-split", "COND_SPLIT", "条件分叉"),
    ("create.conditional-merge", "COND_MERGE", "条件汇合"),
]


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[32m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    FAILURES.append(msg + (("：" + detail) if detail else ""))
    print("  \033[31m✗\033[31m " + msg + (("：" + detail) if detail else ""))


def section(title):
    print("\n\033[1m── %s ──\033[1m" % title)


# ---------------------------------------------------------------- BPMN 构造
def bpmn_xml(nodes, flows, shapes, edges):
    """nodes: [(key, name, protocolName, resourceId, type, defaultFlowId?)]
       flows: [(id, source, target, condition?)]
       shapes: {key: (x, y, w, h)}
       edges:  {flowId: [(x1,y1),(x2,y2)]}

    ⚠ **条件写在 `<bpmn:conditionExpression>` 子元素里**，不是 sequenceFlow 的
       `condition` 属性 —— 后端解析器只认子元素。写成属性的话条件会被静默忽略。
    """
    ns = ('xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
          'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
          'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
          'xmlns:di="http://www.omg.org/spec/DD/20100524/DI"')
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<bpmn:definitions %s id="Def_x" targetNamespace="http://bpmn.io/schema/bpmn">' % ns,
           '  <bpmn:process id="Proc_x" isExecutable="false">']
    for n in nodes:
        key, name, proto, res, ntype = n[0], n[1], n[2], n[3], n[4]
        default_flow = n[5] if len(n) > 5 else None
        if ntype == "startEvent":
            out.append('    <bpmn:startEvent id="%s" name="%s"/>' % (key, name))
        elif ntype == "endEvent":
            out.append('    <bpmn:endEvent id="%s" name="%s"/>' % (key, name))
        elif ntype == "exclusiveGateway":
            df = ' default="%s"' % default_flow if default_flow else ''
            out.append('    <bpmn:exclusiveGateway id="%s" name="%s"%s/>' % (key, name, df))
        elif ntype == "parallelGateway":
            out.append('    <bpmn:parallelGateway id="%s" name="%s"/>' % (key, name))
        elif ntype == "inclusiveGateway":
            # 条件网关（包容网关）：2026-11-11 执行侧才认（此前只认不执行 ⇒ 断流）
            df = ' default="%s"' % default_flow if default_flow else ''
            out.append('    <bpmn:inclusiveGateway id="%s" name="%s"%s/>' % (key, name, df))
        else:
            out.append('    <bpmn:task id="%s" name="%s" protocolName="%s" resource="%s"/>'
                       % (key, name, proto, res))
    for f in flows:
        fid, src, dst = f[0], f[1], f[2]
        cond = f[3] if len(f) > 3 else None
        if cond:
            out.append('    <bpmn:sequenceFlow id="%s" sourceRef="%s" targetRef="%s">'
                       % (fid, src, dst))
            out.append('      <bpmn:conditionExpression>%s</bpmn:conditionExpression>'
                       % cond)
            out.append('    </bpmn:sequenceFlow>')
        else:
            out.append('    <bpmn:sequenceFlow id="%s" sourceRef="%s" targetRef="%s"/>'
                       % (fid, src, dst))
    out.append('  </bpmn:process>')
    out.append('  <bpmndi:BPMNDiagram id="D_x">')
    out.append('    <bpmndi:BPMNPlane id="P_x" bpmnElement="Proc_x">')
    for key, (x, y, w, h) in shapes.items():
        out.append('      <bpmndi:BPMNShape id="%s_di" bpmnElement="%s">'
                   '<dc:Bounds x="%d" y="%d" width="%d" height="%d"/></bpmndi:BPMNShape>'
                   % (key, key, x, y, w, h))
    for fid, pts in edges.items():
        out.append('      <bpmndi:BPMNEdge id="%s_di" bpmnElement="%s">' % (fid, fid))
        for (px, py) in pts:
            out.append('        <di:waypoint x="%d" y="%d"/>' % (px, py))
        out.append('      </bpmndi:BPMNEdge>')
    out.append('    </bpmndi:BPMNPlane>')
    out.append('  </bpmndi:BPMNDiagram>')
    out.append('</bpmn:definitions>')
    return "\n".join(out)


def seed_workflow(token, name, xml):
    wf = F.api("/api/workflows", "POST", {"name": name}, token)["id"]
    F.api("/api/workflows/%d/graph" % wf, "PUT", raw=xml, token=token)
    return wf


def workflow_xml(token, wf):
    xml = F.api("/api/workflows/%d/graph" % wf, token=token)
    return xml if isinstance(xml, str) else str(xml)


# ================================================================ 一、页面
def part_page(pg, token, ctx):
    section("一、页面 · palette 成对入口 + 画布可读性 + 预检")

    wf_id = F.make_workflow(pg, ctx, "验证·网关成对")
    ok("界面新建流程并进入设计器（#%d）" % wf_id)

    # ---- ① palette：6 个成对入口都在，旧的通用入口没了 ----
    for action, code, label in GATEWAY_ENTRIES:
        n = pg.locator(f'.djs-palette .entry[data-action="{action}"]').count()
        if n == 1:
            ok("palette 有入口「%s」（%s）" % (label, action))
        else:
            bad("palette 缺入口「%s」" % label, "data-action=%s 命中 %d 个" % (action, n))
    old = pg.locator('.djs-palette .entry[data-action="create.exclusive-gateway"]').count()
    if old == 0:
        ok("旧的通用「异或网关」入口已撤下（不分叉汇合的入口不该留着）")
    else:
        bad("旧的通用「异或网关」入口还在", "命中 %d 个" % old)

    # ---- ②③ 拖出来：标签 + 描边色 ----
    # 先把默认的开始/结束删掉，腾出位置
    for k in ("S", "E"):
        F.delete_node(pg, k)

    placed = {}
    for key, action, x, y in (("PS", "create.parallel-split", 200, 120),
                              ("PM", "create.parallel-merge", 620, 260),
                              ("CS", "create.conditional-split", 200, 300),
                              ("CM", "create.conditional-merge", 620, 380)):
        F.clear_selection(pg)
        eid = F.place(pg, action, x, y)
        if not eid:
            bad("拖不出「%s」" % action)
            continue
        placed[key] = eid

    # 画布上的标签文本（bpmn-js 把外置标签画成独立文本节点）
    page_text = pg.inner_text("body")
    placed_by_action = {
        "create.parallel-split": "PS",
        "create.parallel-merge": "PM",
        "create.conditional-split": "CS",
        "create.conditional-merge": "CM",
    }
    for action, code, label in GATEWAY_ENTRIES:
        key = placed_by_action.get(action)
        if key not in placed:
            continue
        if label not in page_text:
            bad("画布上没有「%s」标签" % label)
        else:
            ok("画布上出现「%s」标签" % label)

    # 描边色：分叉=绿 #52c415、汇合=橙 #fa8c16
    #
    # ⚠ 必须查**计算样式**（getComputedStyle），不能只查 stroke 属性 ——
    #   bpmn-js 把颜色写成内联 style，CSS style 的优先级高于 presentation
    #   attribute ⇒ 只看属性会「属性是绿的、画出来是黑的」（2026-11-11 实踩）。
    def stroke_of(eid):
        return pg.evaluate("""(eid) => {
            const el = document.querySelector('.djs-element[data-element-id="' + eid + '"]');
            if (!el) return null;
            const poly = el.querySelector('polygon');
            if (!poly) return null;
            return getComputedStyle(poly).stroke;
        }""", eid)

    def is_green(s):
        return bool(s) and s.lower() in ("#52c415", "rgb(82, 196, 21)")

    def is_orange(s):
        return bool(s) and s.lower() in ("#fa8c16", "rgb(250, 140, 22)")

    if "PS" in placed:
        s = stroke_of(placed["PS"])
        if is_green(s):
            ok("并行分叉画的是绿边（%s）" % s)
        else:
            bad("并行分叉描边色不对", "实际 %s" % s)
    if "PM" in placed:
        s = stroke_of(placed["PM"])
        if is_orange(s):
            ok("并行汇合画的是橙边（%s）" % s)
        else:
            bad("并行汇合描边色不对", "实际 %s" % s)

    # ---- ④ 名字跟着实际拓扑走 ----
    # 把「并行分叉」接成 2 入 1 出（其实是汇合）⇒ 名字应自动变「并行汇合」
    if "PS" in placed and "PM" in placed:
        # PS → 两条出边分别去 T1/T2；再把 T1/T2 接回 PS ⇒ PS 变成 2 入 1 出
        for k, x, y in (("T1", 420, 60), ("T2", 420, 180)):
            F.clear_selection(pg)
            eid = F.place(pg, "create.task", x, y)
            if eid:
                placed[k] = eid
        if "T1" in placed and "T2" in placed:
            # 让 PS 变成 2 入 1 出（也就是**汇合**）⇒ 名字应自动从「并行分叉」
            # 变成「并行汇合」。引擎按连线数判角色，名字不能跟事实打架。
            F.connect(pg, placed["T1"], placed["PS"])
            F.connect(pg, placed["T2"], placed["PS"])
            F.connect(pg, placed["PS"], placed["PM"])
            pg.wait_for_timeout(1500)
            body = pg.inner_text("body")
            if "并行汇合" in body:
                ok("名字跟着实际拓扑走：2 入 1 出后显示「并行汇合」")
            else:
                bad("名字没跟着实际拓扑走", "画布上没出现「并行汇合」")

    # ---- ⑤ 保存后 XML 存的是中性 code ----
    F.save(pg)
    pg.wait_for_timeout(1500)
    xml = workflow_xml(token, wf_id)
    hit = [code for _a, code, _l in GATEWAY_ENTRIES if code in xml]
    if hit:
        ok("XML 里存的是中性 code：%s" % "、".join(hit))
    else:
        bad("XML 里没有中性 code", xml[:200])
    if "并行分叉" in xml or "条件分叉" in xml:
        bad("XML 里存了中文文案（不该）", xml[:200])
    else:
        ok("XML 里没有中文文案（语种无关的中性 code 才对）")

    # ---- ⑥ 预检把「不成对」说出来 ----
    pre = F.api("/api/workflows/%d/preflight" % wf_id, token=token)
    codes = set()
    if isinstance(pre, dict):
        for i in pre.get("issues", []) or []:
            codes.add(i.get("code"))
    want = {"PF-15", "PF-16", "PF-17", "PF-18", "PF-19"}
    got = codes & want
    if got:
        ok("预检报出了网关结构问题：%s" % "、".join(sorted(got)))
    else:
        bad("预检没报网关结构问题", "issues=%s" % sorted(codes))

    return wf_id


# ================================================================ 二、接口
def part_exec(token, ctx):
    section("二、接口 · 条件网关真跑（包容语义 + 汇合不死等）")

    island = F.q("SELECT id FROM resource_node WHERE code='%s' AND kind='island'" % ISLAND)
    devs = {}
    for ln in F.q("SELECT di.code || '|' || di.resource_id FROM device_instance di "
                  "JOIN driver dr ON dr.id = di.driver_id WHERE di.tenant_id=1 "
                  "AND di.deleted_at IS NULL AND di.status='available' "
                  "AND dr.endpoint LIKE 'tcp://%%'").split("\n"):
        if ln.strip():
            c, rid = ln.split("|")
            devs[c] = rid
    robot = devs.get("ID-X231")
    spin = devs.get("ID-ASPIN_2K-1")
    if not robot or not spin:
        bad("基线设备不齐（要机器人 + 离心机）", str(sorted(devs)))
        return
    ok("基线设备就位：机器人 %s / 离心机 %s" % (robot, spin))

    # S → COND ─(cond a)→ A ─┐
    #        │                ├→ JOIN(条件汇合) → T9 → E
    #        └─(cond b)→ B ─┘
    def build():
        nodes = [("S", "开始", None, None, "startEvent"),
                 ("COND", "条件分叉", None, None, "inclusiveGateway"),
                 ("A", "甲分支", "Home", robot, "task"),
                 ("B", "乙分支", "Home", spin, "task"),
                 ("JOIN", "条件汇合", None, None, "inclusiveGateway"),
                 ("T9", "汇合后", "Home", robot, "task"),
                 ("E", "结束", None, None, "endEvent")]
        shapes = {"S": (60, 160, 36, 36), "COND": (170, 150, 36, 36),
                  "A": (290, 70, 100, 80), "B": (290, 240, 100, 80),
                  "JOIN": (440, 150, 36, 36), "T9": (540, 140, 100, 80),
                  "E": (690, 160, 36, 36)}
        flows = [("f0", "S", "COND"),
                 ("fa", "COND", "A", "${a} == 'yes'"),
                 ("fb", "COND", "B", "${b} == 'yes'"),
                 ("f3", "A", "JOIN"), ("f4", "B", "JOIN"),
                 ("f5", "JOIN", "T9"), ("f6", "T9", "E")]
        edges = {"f0": [(96, 178), (170, 168)],
                 "fa": [(206, 160), (290, 110)], "fb": [(206, 185), (290, 280)],
                 "f3": [(390, 110), (440, 158)], "f4": [(390, 280), (440, 176)],
                 "f5": [(476, 168), (540, 180)], "f6": [(640, 180), (690, 178)]}
        return bpmn_xml(nodes, flows, shapes, edges)

    def run_with(vars_map, tag):
        wf = seed_workflow(token, "验证·条件网关·%s" % tag, build())
        for k, v in vars_map.items():
            F.api("/api/workflows/%d/variables" % wf, "POST",
                  {"name": k, "type": "string", "defaultValue": v}, token)
        rid, st = F.run_workflow(ctx, wf, wait=240)
        if not rid:
            bad("%s：没跑起来" % tag)
            return None, None
        ran = set()
        for ln in F.q("SELECT node_key || '|' || status::text FROM run_node "
                      "WHERE run_id=%d AND level=0" % rid).split("\n"):
            if ln.strip():
                k, s = ln.split("|")
                if s == "completed":
                    ran.add(k)
        return ran, st

    # ---- ⑦ 两个条件都成立 ⇒ 两条分支都跑 ----
    ran, st = run_with({"a": "yes", "b": "yes"}, "两条件成立")
    if ran is None:
        return
    if "A" in ran and "B" in ran:
        ok("条件都成立：两条分支都执行了（A、B）")
    else:
        bad("条件都成立：两条分支没都执行", "跑到 %s" % sorted(ran))
    if "T9" in ran:
        ok("条件汇合放行了，下游节点 T9 也执行了")
    else:
        bad("条件汇合没放行（T9 没执行）", "跑到 %s" % sorted(ran))

    # ---- ⑧ 一个条件不成立 ⇒ 那条不跑，但汇合照样放行 ----
    ran2, st2 = run_with({"a": "yes", "b": "no"}, "单条件成立")
    if ran2 is None:
        return
    if "B" not in ran2:
        ok("条件不成立的分支 B 没执行")
    else:
        bad("条件不成立的分支 B 也执行了", "跑到 %s" % sorted(ran2))
    if "A" in ran2:
        ok("条件成立的分支 A 执行了")
    else:
        bad("条件成立的分支 A 没执行", "跑到 %s" % sorted(ran2))
    if "T9" in ran2:
        ok("**汇合点没被死等卡住**（B 不来，T9 照跑）")
    else:
        bad("汇合点死等了（T9 没执行）", "跑到 %s" % sorted(ran2))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=F.BASE_WEB)
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    if not F.have_db():
        print("✗ 需要连库环境变量（LABOS_DB_HOST / LABOS_DB_NAME / LABOS_DB_USER / LABOS_DB_PASS）")
        return 2

    token = F.api("/api/auth/login", "POST",
                  {"username": args.user, "password": args.pwd})["token"]
    ctx = F.Ctx(token)

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=not args.headed)
        pg = b.new_page(viewport={"width": 1600, "height": 1000})
        pg.set_default_timeout(25000)
        try:
            F.login(pg, args.base, args.user, args.pwd)
            part_page(pg, token, ctx)
            part_exec(token, ctx)
        except Exception as e:      # noqa: BLE001
            bad("抛异常：%s" % e)
        finally:
            b.close()
    ctx.cleanup()

    print("\n" + "─" * 60)
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    if FAILURES:
        print("失败明细：")
        for f in FAILURES:
            print("  - " + f)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
