#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把提取线的流程图导出成 SVG（**按阶段分列的泳道图**，可直接放进手册/交付物）。

为什么要有这个（2026-11-18）：
① 交付物里只有"57 个节点"这种数字，客户看不出流程长什么样；
② 流程图存在 workflow.graph->>'xml'（BPMN 2.0 XML）里，带 bpmndi 图形信息
   （每个元素的 Bounds、每条连线的 waypoints）—— 但那是**设计器里的原始布局**：
   57 个节点横排几千像素，**放进文档没法看**。
⇒ 本脚本按"**阶段 × 并行分支**"重排成泳道图：纵向是阶段（磁套加载 / 分液 / 收尾 / 读状态），
   每列里每条并行分支是一条竖链，节点上标设备徽标 + 方法 + 实测耗时。

用法：
    python3 scripts/export-extraction-flow-diagram.py                  # 流程图
    python3 scripts/export-extraction-flow-diagram.py --run 1001803    # 顺带导出这次 run 的轨迹

输出（private/实际流程/附件/）：
    高通量核酸提取工作站-流程图.svg
    高通量核酸提取工作站-执行轨迹-run<id>.md
    高通量核酸提取工作站-执行轨迹-run<id>.svg

⚠ 节点上的设备/方法取自 node_binding（**绑定链的真实来源**），不是从节点名猜的。
"""

import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"
DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
NS = {"dc": "http://www.omg.org/spec/DD/20100524/DC"}

DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_DB_USER", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")
ISLAND_CODE = os.environ.get("LABOS_ISLAND_CODE", "LAB-EXT-02")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "private", "实际流程", "附件")

CAP_ICON = {
    "EXTRACT": ("提取仪", "#dbeafe", "#1d4ed8"),
    "WASH": ("分液器", "#dcfce7", "#15803d"),
    "ALIQUOT": ("分液器", "#dcfce7", "#15803d"),
    "MIX": ("搅拌", "#ede9fe", "#6d28d9"),
    "TRANSFER": ("机械臂", "#fef3c7", "#b45309"),
    "STORE": ("耗材栈", "#f1f5f9", "#334155"),
    "SCAN": ("扫码", "#fce7f3", "#be185d"),
}

BOX_W, BOX_H = 168, 50
GAP_X, GAP_Y = 16, 16
HEAD_H = 74


def psql(sql):
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
         "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return [ln for ln in p.stdout.strip().splitlines() if ln]


def psql_full(sql):
    """完整输出（不按行切）—— BPMN XML 是多行的。"""
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
         "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return p.stdout


def esc(s):
    return (str(s or "").replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def fetch_workflow(island_code, want_code=None):
    """取流程。want_code 非空 = 按流程号取；否则取岛上的第一条。

    ⚠ 2026-11-19：提取岛现在有 **3 条**流程（EXT-WORKFLOW-02 单批 57 节点 /
      BASELINE-SERPAR-01 基准 9 节点 / EXT-WORKFLOW-SINGLE 单样品板 80 节点），
      原来「取第一条」会把单样品板流程导成旧的 57 节点那条 ⇒ 必须能按流程号选。
    """
    if want_code:
        rows = psql("SELECT id, code, name FROM workflow WHERE tenant_id=1"
                    " AND code = '%s'" % want_code)
        if not rows:
            sys.exit("✘ 找不到流程 %s" % want_code)
    else:
        rows = psql("SELECT id, code, name FROM workflow WHERE tenant_id=1"
                    " AND island_id = (SELECT id FROM resource_node"
                    " WHERE code = '%s' AND kind = 'island')" % island_code)
        if not rows:
            sys.exit("✘ 找不到岛 %s 的流程" % island_code)
    wid, code, name = rows[0].split("|", 2)
    xml = psql_full("SELECT graph->>'xml' FROM workflow WHERE id = %s" % wid).strip()
    return wid, code, name, xml


def fetch_bindings(wid):
    """节点 -> (设备编号, 能力, 方法名)。绑定链是唯一真实来源。"""
    rows = psql("""
        SELECT wn.node_key, wn.name, COALESCE(c.code,''), COALESCE(cap.code,''),
               COALESCE(dm.method,'')
          FROM workflow_node wn
          LEFT JOIN node_binding nb ON nb.node_id = wn.id
          LEFT JOIN device_method dm ON dm.id = nb.method_id
          LEFT JOIN device_instance di ON di.id = nb.device_instance_id
          LEFT JOIN resource_node c ON c.id = di.resource_id
          LEFT JOIN device d ON d.id = di.device_model_id
          LEFT JOIN capability cap ON cap.id = d.capability_id
         WHERE wn.workflow_id = %s""" % wid)
    out = {}
    for r in rows:
        p = (r.split("|") + [""] * 5)[:5]
        out[p[0]] = {"name": p[1], "device": p[2], "cap": p[3], "method": p[4]}
    return out


def parse_bpmn(xml, bindings):
    root = ET.fromstring(xml)
    nodes, flows = {}, {}
    order = []
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag in ("task", "startEvent", "endEvent", "parallelGateway", "gateway"):
            nid = el.get("id")
            if nid:
                nodes[nid] = {"type": tag, "name": el.get("name") or "",
                              "protocol": el.get("protocolName") or ""}
                order.append(nid)
        elif tag == "sequenceFlow":
            flows.setdefault(el.get("sourceRef"), []).append(el.get("targetRef"))
    for nid, n in nodes.items():
        b = bindings.get(nid)
        if b:
            n["device"] = b["device"]
            n["cap"] = b["cap"]
            n["method"] = b["method"] or n["protocol"]
    return nodes, flows, order


def stages_of(nodes, flows, order):
    """按并行网关切阶段。

    结构：**每个网关开启一个阶段**，该网关的每个下游 target 是**一条并行分支**（一条竖链）。
    ⚠ 不要用"全局 visited 递归走图"来切分支：汇合网关会先被访问，
      把它标记成 visited ⇒ 其余并行分支走到这里就被挡掉 ⇒ 只剩一条链（2026-11-18 实测踩到）。

    返回 (阶段列表, 阶段名表)。
    """
    start = next((k for k, v in nodes.items() if v["type"] == "startEvent"), None)
    if not start:
        return [], {}

    def chain_from(nid):
        """从 nid 出发，沿着**单出边**走到下一个网关/终点。"""
        chain, cur = [], nid
        while cur and cur not in chain and cur in nodes:
            chain.append(cur)
            outs = flows.get(cur, [])
            if len(outs) != 1:
                break
            cur = outs[0]
        return chain

    stages, titles = [], {}
    # 阶段 0：开始 → 第一个网关
    first_gw = next((g for g in order if nodes[g]["type"] in ("parallelGateway", "gateway")), None)
    head_chain = chain_from(start)
    if head_chain:
        stages.append((0, [head_chain]))
    si = 1
    for g in order:
        if nodes[g]["type"] not in ("parallelGateway", "gateway"):
            continue
        chains = [chain_from(t) for t in flows.get(g, [])]
        chains = [c for c in chains if c]
        if not chains:
            continue
        stages.append((si, chains))
        titles[si] = nodes[g]["name"]
        si += 1
    return stages, titles


def stage_titles(nodes, flows):
    """阶段名 = 进入该阶段的并行网关的名字（去掉"并行拆分/汇合"前缀）。"""
    titles = {}
    for nid, n in nodes.items():
        if n["type"] in ("parallelGateway", "gateway"):
            nm = n["name"] or ""
            for kw in ("并行拆分（", "并行汇合（", "并行拆分", "并行汇合"):
                if nm.startswith(kw):
                    nm = nm[len(kw):]
                    break
            nm = nm.rstrip("）)")
            # 汇合网关的阶段名沿用上一个拆分网关
            key = "汇合" in n["name"]
            titles.setdefault(nid, (nm, key))
    return titles


def render_svg(nodes, flows, stages, title, subtitle, dur_by_node, stage_names=None):
    stage_names = stage_names or {}
    o = []
    # 画布尺寸：**纵向分段**（阶段从上到下；段内并行分支横排，每条分支一条竖链）
    # ⚠ 横向分段会宽到 5000+ 像素 ⇒ 放进文档没法看。
    lanes_max = max([len(c) for _, c in stages] or [1])
    W = 40 + lanes_max * (BOX_W + GAP_X) + 40
    H = 120
    for st, chs in stages:
        depth = max([len(c) for c in chs] or [1])
        H += depth * (BOX_H + GAP_Y) + 54
    o.append('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
             'font-family="PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif">' % (W, H, W, H))
    o.append('<rect width="%d" height="%d" fill="#ffffff"/>' % (W, H))
    o.append('<text x="30" y="34" font-size="21" font-weight="600" fill="#0f172a">%s</text>' % esc(title))
    o.append('<text x="30" y="56" font-size="12.5" fill="#64748b">%s</text>' % esc(subtitle))
    o.append('<defs><marker id="arrow" markerWidth="9" markerHeight="9" refX="8" refY="3" '
             'orient="auto"><path d="M0,0 L0,6 L8,3 z" fill="#94a3b8"/></marker></defs>')

    y = 100
    for si, (st, chs) in enumerate(stages):
        cx = 40
        # 阶段标题（取该阶段第一个网关名）
        # 阶段名按**这一段里实际做的事**判断（网关名会滞后一段：
        # "并行汇合（磁套加载完成）"这个网关的下游其实是"分液"）
        head = "阶段 %d" % (si + 1)
        raw = stage_names.get(st, "")
        if raw:
            for kw in ("并行拆分（", "并行汇合（", "并行拆分", "并行汇合"):
                if raw.startswith(kw):
                    raw = raw[len(kw):]
                    break
            raw = raw.rstrip("）)")
        seg = " ".join(nodes[c[0]]["name"] for c in chs if c)
        if "取磁套" in seg:
            head = "磁套加载（4 台提取仪并行）"
        elif "分液" in seg:
            head = "分液（6 台分液器并行）"
        elif "洗脱" in seg or "等待交互" in seg:
            head = "内部工序（4 台提取仪并行收尾）"
        elif "读状态" in seg or "结束" in seg:
            head = "收尾与结束"
        elif raw:
            head = raw
        lanes = max(len(chs), 1)
        colw = lanes * (BOX_W + GAP_X)
        o.append('<rect x="%d" y="%d" width="%d" height="30" rx="6" fill="#f1f5f9"/>' % (cx, y - 26, colw))
        o.append('<text x="%d" y="%d" font-size="13" font-weight="600" fill="#334155">%s</text>'
                 % (cx + 10, y - 5, esc(head)))
        # 分支内竖链
        for li, chain in enumerate(chs):
            bx = cx + li * (BOX_W + GAP_X)
            for k, nid in enumerate(chain):
                n = nodes[nid]
                by = y + k * (BOX_H + GAP_Y)
                cap = n.get("cap", "")
                label, lfill, lstroke = CAP_ICON.get(cap, ("动作", "#ffffff", "#334155"))
                o.append('<rect x="%d" y="%d" width="%d" height="%d" rx="6" fill="#ffffff" '
                         'stroke="#cbd5e1" stroke-width="1.2"/>' % (bx, by, BOX_W, BOX_H))
                o.append('<rect x="%d" y="%d" width="%d" height="15" rx="6" fill="%s"/>'
                         % (bx, by, BOX_W, lfill))
                o.append('<rect x="%d" y="%d" width="%d" height="8" fill="%s"/>' % (bx, by + 7, BOX_W, lfill))
                o.append('<text x="%d" y="%d" font-size="9.5" fill="%s">%s</text>'
                         % (bx + 6, by + 11, lstroke, esc(label)))
                nm = n["name"]
                if len(nm) > 17:
                    nm = nm[:16] + "…"
                o.append('<text x="%d" y="%d" font-size="11" fill="#0f172a">%s</text>'
                         % (bx + 6, by + 30, esc(nm)))
                sub = " · ".join([v for v in [n.get("method", ""), n.get("device", "")] if v])
                if len(sub) > 22:
                    sub = sub[:21] + "…"
                o.append('<text x="%d" y="%d" font-size="9.5" fill="#64748b">%s</text>'
                         % (bx + 6, by + 43, esc(sub)))
                d = dur_by_node.get(nid)
                if d:
                    o.append('<text x="%d" y="%d" font-size="9.5" fill="#1d4ed8">实测 %.1f s</text>'
                             % (bx + BOX_W - 6, by + 43, d))
                if k > 0:
                    o.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#94a3b8" stroke-width="1.3" '
                             'marker-end="url(#arrow)"/>'
                             % (bx + BOX_W / 2, by - GAP_Y, bx + BOX_W / 2, by - 2))
        depth = max([len(c) for c in chs] or [1])
        y += depth * (BOX_H + GAP_Y) + 54
    # 阶段之间的箭头（竖向，落在左侧）
    yy = 100
    for si, (st, chs) in enumerate(stages):
        depth = max([len(c) for c in chs] or [1])
        band_bottom = yy + depth * (BOX_H + GAP_Y) + 4
        if si + 1 < len(stages):
            o.append('<line x1="52" y1="%d" x2="52" y2="%d" stroke="#94a3b8" stroke-width="2" '
                     'marker-end="url(#arrow)"/>' % (band_bottom, band_bottom + 26))
        yy = band_bottom + 40
    # 图例
    lx, ly = 30, H - 24
    seen_cap = set()
    for cap, (label, fill, stroke) in CAP_ICON.items():
        if label in seen_cap:
            continue
        seen_cap.add(label)
        o.append('<rect x="%d" y="%d" width="11" height="11" rx="2" fill="%s" stroke="%s"/>'
                 % (lx, ly - 9, fill, stroke))
        o.append('<text x="%d" y="%d" font-size="11" fill="#475569">%s</text>' % (lx + 16, ly, esc(label)))
        lx += 30 + len(label) * 12
    o.append("</svg>")
    return "\n".join(o)


def fetch_run_trace(run_id):
    """一次真实 run：节点 -> (节点名, 设备, 方法, 耗时, 起始偏移)。"""
    rows = psql("""
        SELECT COALESCE(wn.node_key, ''), COALESCE(wn.name, rn.name), rn.status,
               COALESCE(c.code,''), COALESCE(rc.method, dm.method, ''),
               COALESCE(MAX(EXTRACT(EPOCH FROM (rc.finished_at - rc.sent_at))), 0),
               COALESCE(MIN(EXTRACT(EPOCH FROM (rc.sent_at - r.actual_start))), 0)
          FROM run_node rn
          JOIN run r ON r.id = rn.run_id
          LEFT JOIN run_node pn ON pn.id = rn.parent_node_id
          LEFT JOIN workflow_node wn ON wn.workflow_id = r.workflow_id
               AND wn.node_key = COALESCE(pn.node_key, rn.node_key)
          LEFT JOIN node_binding nb ON nb.node_id = wn.id
          LEFT JOIN device_method dm ON dm.id = nb.method_id
          LEFT JOIN device_instance di ON di.id = nb.device_instance_id
          LEFT JOIN resource_node c ON c.id = di.resource_id
          LEFT JOIN run_command rc ON rc.run_node_id = rn.id
         WHERE rn.run_id = %s
         GROUP BY 1, 2, 3, 4, 5
         ORDER BY 7, 2""" % run_id)
    out = []
    for r in rows:
        p = (r.split("|") + [""] * 7)[:7]
        out.append({"key": p[0], "name": p[1], "status": p[2], "device": p[3],
                    "method": p[4],
                    "dur": float(p[5] or 0), "start": float(p[6] or 0)})
    return out


def export_run_trace(run_id, trace):
    head = psql("SELECT status, actual_start, actual_end FROM run WHERE id = %s" % run_id)[0]
    h = (head.split("|") + ["", "", ""])[:3]
    md = ["# 执行轨迹 · run %s" % run_id, "",
          "| 项 | 值 |", "|---|---|",
          "| 状态 | %s |" % h[0],
          "| 开始 | %s |" % h[1],
          "| 结束 | %s |" % h[2],
          "", "## 节点执行序列（按开始时间排序）", "",
          "| # | 节点 | 设备 | 方法 | 耗时(s) | 状态 |",
          "|---|---|---|---|---|---|"]
    bars = []
    for i, t in enumerate(trace, start=1):
        md.append("| %d | %s | %s | %s | %s | %s |"
                  % (i, t["name"], t["device"] or "—", t["method"] or "—",
                     ("%.1f" % t["dur"]) if t["dur"] else "—", t["status"]))
        if t["dur"]:
            bars.append((t["name"], t["device"], t["dur"], t["start"]))
    md += ["", "> 耗时取设备命令的「发出→回包」区间；只列有设备动作的节点，",
           "> 「等…等待交互」这类事件节点没有设备命令。", ""]
    with open(os.path.join(OUT_DIR, "高通量核酸提取工作站-执行轨迹-run%s.md" % run_id),
              "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    if not bars:
        return
    bars.sort(key=lambda b: b[3])
    rowh = 22
    W = 1200
    H = 70 + rowh * len(bars) + 50
    t0 = min(b[3] for b in bars)
    t1 = max(b[3] + b[2] for b in bars)
    span = max(t1 - t0, 1)
    lx, lw = 340, W - 400
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
           'font-family="PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif">' % (W, H, W, H),
           '<rect width="%d" height="%d" fill="#ffffff"/>' % (W, H),
           '<text x="20" y="30" font-size="19" font-weight="600" fill="#0f172a">'
           '执行时间轴 · run %s</text>' % run_id,
           '<text x="20" y="52" font-size="12" fill="#64748b">'
           '横轴 = 从批次开始算起的秒数；条长 = 该动作实际耗时</text>']
    for i, (name, dev, dur, start) in enumerate(bars):
        y = 70 + i * rowh
        x = lx + lw * (start - t0) / span
        w = max(lw * dur / span, 2)
        nm = name if len(name) <= 22 else name[:21] + "…"
        svg.append('<text x="20" y="%d" font-size="11" fill="#334155">%s</text>'
                   % (y + 13, esc(nm)))
        svg.append('<rect x="%.1f" y="%d" width="%.1f" height="14" rx="3" fill="#93c5fd" '
                   'stroke="#1d4ed8" stroke-width="0.8"/>' % (x, y, w))
        svg.append('<text x="%.1f" y="%d" font-size="10" fill="#1d4ed8">%.0fs</text>'
                   % (x + w + 5, y + 11, dur))
    svg.append("</svg>")
    with open(os.path.join(OUT_DIR, "高通量核酸提取工作站-执行轨迹-run%s.svg" % run_id),
              "w", encoding="utf-8") as f:
        f.write("\n".join(svg))


def to_png(svg_path):
    """SVG → PNG（用本机 Chrome headless；没有 Chrome 就跳过，SVG 本身也能看）。"""
    chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    if not os.path.exists(chrome):
        print("· 没找到 Chrome ⇒ 跳过 PNG（SVG 照常可用）")
        return
    head = open(svg_path, encoding="utf-8").read(400)
    import re
    m = re.search(r'width="(\d+)" height="(\d+)"', head)
    if not m:
        return
    w, h = m.group(1), m.group(2)
    png = svg_path[:-4] + ".png"
    cmd = [chrome, "--headless", "--disable-gpu", "--hide-scrollbars",
           "--screenshot=" + png, "--window-size=%s,%s" % (w, h),
           "file://" + os.path.abspath(svg_path)]
    subprocess.run(cmd, capture_output=True)
    if os.path.exists(png):
        print("✔ PNG：%s" % png)
    else:
        print("· PNG 生成失败（SVG 不受影响）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default=None)
    ap.add_argument("--island", default=ISLAND_CODE)
    ap.add_argument("--workflow", default=None,
                    help="按流程号导出（如 EXT-WORKFLOW-SINGLE）；不指定则取岛上的第一条")
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)
    wid, code, name, xml = fetch_workflow(args.island, args.workflow)
    bindings = fetch_bindings(wid)
    nodes, flows, order = parse_bpmn(xml, bindings)
    stages, stage_names = stages_of(nodes, flows, order)
    trace = fetch_run_trace(args.run) if args.run else []
    dur_by_node = {}
    for t in trace:
        if t["key"] and t["dur"]:
            dur_by_node[t["key"]] = t["dur"]
    subtitle = ("岛 %s · 流程 %s · %d 个节点 / %d 条连线 · %d 个阶段 · "
                "设备与方法取自绑定链%s"
                % (args.island, code, len(nodes),
                   sum(len(f) for f in flows.values()), len(stages),
                   "，标注为该次 run 的实测耗时" if args.run else ""))
    svg = render_svg(nodes, flows, stages, name, subtitle, dur_by_node, stage_names)
    # 产物按流程号分文件：同一岛有多条流程，共用文件名会互相覆盖
    #   （实测：不带 --workflow 时导出的是旧的 57 节点那条，把单样品板流程盖了）。
    #   旧流程 EXT-WORKFLOW-02 保持原名（客户交付物已在用），其它流程带流程号后缀。
    suffix = "" if code == "EXT-WORKFLOW-02" else "-" + code
    path = os.path.join(OUT_DIR, "高通量核酸提取工作站-流程图%s.svg" % suffix)
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)
    print("✔ 流程图：%s（%d 节点 / %d 阶段）" % (path, len(nodes), len(stages)))
    to_png(path)
    if args.run:
        export_run_trace(args.run, trace)
        tsvg = os.path.join(OUT_DIR, "高通量核酸提取工作站-执行轨迹-run%s.svg" % args.run)
        print("✔ 执行轨迹：run %s 的表与时间轴已导出" % args.run)
        to_png(tsvg)


if __name__ == "__main__":
    main()

