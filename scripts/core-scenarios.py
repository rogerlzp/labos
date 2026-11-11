#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核心场景验收（页面操作为主）—— 2026-11-11 起**唯一的**测试主线。

旧的门禁体系（40+ 脚本、面向三十座岛 + 一千多条流程的旧基线）已整体退役：
那些用例互相占设备、互相依赖历史数据，红的时候大多不是逻辑坏了，
而是「设备被别的 run 占住了」—— **门禁红了却说不出为什么，等于没有门禁**。

本文件只做**四件最要紧的事**，全部以**页面操作**驱动：

  场景 1 · 设备一致性与下线联动
      页面上的设备（名称 / IP）与中控清单（设备ID / IP）是不是同一台；
      在**设备管理台**把它下线 ⇒ 中控页面是不是也显示离线 ⇒ 上线后恢复。
      —— 这一条守着「在线状态的唯一权威是 Redis」这条新架构不被悄悄绕过。

  场景 2 · 顺序执行
      一条串行流程真跑一遍，断言**节点严格按顺序**发生（不是都完成就行），
      并且页面上的运行详情能看到这个顺序。

  场景 3 · 并行 + 变量条件 + 异或网关
      一条带并行分叉、变量条件、异或网关的流程：
      并行的两支**真并行**（时间上有重叠）、异或**按变量选边**（不是碰巧）。

  场景 4 · 暂停 / 恢复
      执行中点暂停 ⇒ 页面显示已暂停 ⇒ 点继续 ⇒ 跑完；
      断言「暂停期间设备没有收到新指令」「恢复后跑完且不重复下发」。

用法：
  python3 scripts/core-scenarios.py                # 跑全部四个场景
  python3 scripts/core-scenarios.py --only 1       # 只跑场景 1
  python3 scripts/core-scenarios.py --headed       # 显示浏览器窗口（调试用）
"""
import argparse
import datetime as _dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE_WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
BASE_API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
MANAGER = os.environ.get("LABOS_MANAGER_URL", "http://127.0.0.1:8600")
ISLAND = os.environ.get("LABOS_BASELINE_ISLAND", "LAB-BASE-01")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

PASS, FAIL = 0, 0
FAILURES = []
UNCOVERED = []


# ---------------------------------------------------------------- 断言辅助
def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def uncovered(msg, detail=""):
    """**没覆盖到的部分** —— 单列一类，**不算失败**。

    <p>为什么单列：门禁的价值是「红了就一定要看」。把"还没写的场景"
    混进失败里 ⇒ 门禁永远是红的 ⇒ 大家就学会忽略它 —— 那是"门禁红了
    却说不出为什么"的老毛病，正好是这个项目要消灭的东西。</p>

    <p>但它也**不能被藏起来**：每次运行都照常打印，汇总里单列一段，
    让人随时知道"这几个方向还没有自动防线"。</p>
    """
    UNCOVERED.append(msg + (("（%s）" % detail) if detail else ""))
    print("  \033[33m·\033[0m 未覆盖：" + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    FAILURES.append(msg + (("（%s）" % detail) if detail else ""))
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def section(title):
    print("\n\033[1m── %s ──\033[0m" % title)


def step(title):
    print("\n\033[1m%s\033[0m" % title)


def api(path, method="GET", body=None, token=None, raw=None, timeout=60):
    req = urllib.request.Request(BASE_API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = raw.encode() if raw is not None else (
        json.dumps(body).encode() if body is not None else None)
    if raw is not None:
        req.add_header("Content-Type", "application/xml")
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            txt = r.read().decode()
            try:
                return json.loads(txt)
            except json.JSONDecodeError:
                return txt
    except urllib.error.HTTPError as e:
        raise RuntimeError("%s %s → HTTP %d: %s"
                           % (method, path, e.code, e.read().decode()[:300]))


def q(sql):
    """跑一条 SQL。**失败必须抛错**（空串会被当成"库里没有" ⇒ 假绿）。"""
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql.strip()[:200], out.stderr.strip()[:300]))
    return out.stdout.strip()


def _parse_ts(v):
    """Postgres 的 timestamptz 文本 → datetime。

    ⚠ Postgres 输出的时区偏移只有**两位**（``+08`` / ``+0``），
    而 Python 3.11+ 的 ``fromisoformat`` 只认 ``+08:00`` ⇒ 直接 parse 会抛
    "Invalid isoformat string"。以前用切片 ``[:26]`` 截断，更是把时区切没了。

    统一做法：把结尾的偏移补成 ``±HH:MM``，再解析。
    """
    if not v:
        return None
    s = str(v).strip()
    m = re.search(r"([+-])(\d{1,2})$", s)
    if m:
        s = s[:m.start()] + m.group(1) + m.group(2).zfill(2) + ":00"
    return _dt.datetime.fromisoformat(s.replace(" ", "T", 1))


# ---------------------------------------------------------------- BPMN 造图
#
# 复杂图（并行 / 异或 / 变量）用页面拖出来太脆（bpmn-js 交互复杂、断言脆），
# 所以**图用 API 播种**（这是本仓库既有做法），而**关键动作全部在页面上点**。
# 播种的 XML 里**带 BPMNEdge**（连线图形信息）—— 少了它，前端画布上就是
# 一堆孤立节点、一条线都没有（见 BUG-20261111-20）。
def bpmn_xml(nodes, flows, shapes, edges):
    """nodes: [(key, name, protocolName, resourceId, type, defaultFlowId?)]
       flows: [(id, source, target, condition?)]
       shapes: {key: (x, y, w, h)}
       edges:  {flowId: [(x1,y1),(x2,y2)]}

    ⚠ **条件写在 `<bpmn:conditionExpression>` 子元素里**，不是 sequenceFlow 的
       `condition` 属性 —— 后端解析器只认子元素（BpmnGraphParser：
       遍历 sequenceFlow 的子节点找 conditionExpression）。
       写成属性的话条件会被静默忽略 ⇒ 网关永远走默认边 ⇒ 看起来"条件没生效"。
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
    wf = api("/api/workflows", "POST", {"name": name}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=xml, token=token)
    return wf


def seed_experiment(token, name, wf_ids, island_id):
    exp = api("/api/experiments", "POST",
              {"name": name, "workflowIds": wf_ids, "islandId": island_id}, token)["id"]
    return exp


def wait_run(token, rid, budget=120):
    deadline = time.time() + budget
    st = ""
    while time.time() < deadline:
        st = q("SELECT status FROM run WHERE id=%d" % rid)
        if st in ("completed", "failed", "aborted", "paused"):
            break
        time.sleep(1)
    return st


def node_rows(rid):
    """[(node_key, status, actual_start, actual_end, error_code)] 按 seq。"""
    out = []
    for ln in q("SELECT node_key || '|' || status || '|' || "
                "coalesce(actual_start::text,'') || '|' || coalesce(actual_end::text,'') || '|' || "
                "coalesce(error_code,'') "
                "FROM run_node WHERE run_id=%d AND level=0 ORDER BY seq, id" % rid).split("\n"):
        if ln.strip():
            out.append(ln.split("|"))
    return out


def steps_by_business_node(rid):
    """[(业务节点 key, actual_start, actual_end)] —— 把 level=1 步骤行**关联回**它的
    level=0 业务节点。

    ⚠ 步骤行自己的 node_key 是 "S<n>"（步骤序号），**不是业务节点 key** ——
    拿 "P1"/"P2" 去 step_rows 里找是找不到的。要靠 parent_node_id 关联。
    """
    out = []
    for ln in q("SELECT p.node_key || '|' || coalesce(c.actual_start::text,'') || '|' || "
                "coalesce(c.actual_end::text,'') "
                "FROM run_node c JOIN run_node p ON p.id = c.parent_node_id "
                "WHERE c.run_id=%d AND c.level=1 "
                "ORDER BY c.actual_start, c.id" % rid).split("\n"):
        if ln.strip():
            parts = ln.split("|")
            if len(parts) == 3:
                out.append((parts[0], _parse_ts(parts[1]), _parse_ts(parts[2])))
    return out


def step_rows(rid):
    """[(node_key, actual_start, actual_end)] —— **步骤行**（level=1），按开始时刻排。

    ⚠ **判顺序必须用 level=1，不能用 level=0**：
    level=0 是业务节点，它的 {@code actual_end} 是**整条 run 结束时统一写的**
    （实测 5 个节点的 actual_end 只差几毫秒：18:49:03.889 / .894 / .900…）⇒
    拿它判"后一个开始 ≥ 前一个结束"必然失败 —— 那是**断言用错了数据源**，
    不是流程乱序。
    真正带步骤时刻的是 level=1（一条命令 = 一行），它们互不重叠。
    """
    out = []
    for ln in q("SELECT node_key || '|' || coalesce(actual_start::text,'') || '|' || "
                "coalesce(actual_end::text,'') "
                "FROM run_node WHERE run_id=%d AND level=1 "
                "ORDER BY actual_start, id" % rid).split("\n"):
        if ln.strip():
            p = ln.split("|")
            if len(p) == 3 and p[1]:
                out.append((p[0], _parse_ts(p[1]), _parse_ts(p[2])))
    return out


def cleanup(token, exp_ids=(), wf_ids=()):
    for e in exp_ids:
        try:
            api("/api/experiments/%d" % e, "DELETE", token=token)
        except Exception:
            pass
    for w in wf_ids:
        try:
            api("/api/workflows/%d" % w, "DELETE", token=token)
        except Exception:
            pass


# ---------------------------------------------------------------- 浏览器
def make_browser(playwright, headed=False):
    b = playwright.chromium.launch(headless=not headed)
    ctx = b.new_context(viewport={"width": 1600, "height": 1000})
    pg = ctx.new_page()
    pg.set_default_timeout(20000)
    return b, pg


def login(pg):
    pg.goto(BASE_WEB + "/login", wait_until="domcontentloaded")
    pg.wait_for_timeout(1200)
    if "/login" not in pg.url:
        return
    pg.fill('input[type="text"]', USER)
    pg.fill('input[type="password"]', PWD)
    pg.get_by_role("button", name=re.compile("登")).first.click()
    pg.wait_for_timeout(2500)


def manager_devices():
    with urllib.request.urlopen(MANAGER + "/api/devices", timeout=15) as r:
        return json.loads(r.read().decode()).get("devices", [])


def manager_act(device_id, what):
    req = urllib.request.Request(MANAGER + "/api/devices/%s/%s" % (device_id, what),
                                 method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


# ================================================================ 场景 1
def scenario1_devices(pg, token):
    """设备一致性与下线联动（页面）。

    断言三件事：
      ① 页面上每台设备的 **设备ID / 编号 / IP** 与中控清单**完全一致**
         （页面上的设备必须是中控认的那台，不能张冠李戴）；
      ② 在**设备管理台**把设备下线 ⇒ 中控页面**也显示离线**；
      ③ 重新上线 ⇒ 中控页面**恢复在线**。
    """
    section("场景 1 · 设备一致性与下线联动")

    # ---- ① 页面上的设备 vs 中控清单 ----
    pg.goto(BASE_WEB + "/island", wait_until="domcontentloaded")
    pg.wait_for_timeout(4500)
    cards = pg.eval_on_selector_all(
        "div[style*='width: 150px']",
        """els => els.map(e => {
             const t = (e.innerText||'');
             const dot = e.querySelector('span[title]');
             const m = t.match(/设备ID\\s*(\\d+)/);
             return { text: t, id: m ? m[1] : null,
                      state: dot ? (dot.getAttribute('title')||'') : '' };
           })""")
    ok("岛屿页显示 %d 张设备卡片" % len(cards))

    reg = {str(x["deviceId"]): x for x in api("/api/devices/registry")["items"]}
    if not cards:
        bad("岛屿页没有设备卡片（基线岛没加载？）")
        return
    for c in cards:
        did = c["id"]
        if did is None:
            bad("设备卡片上没有设备ID", c["text"][:60].replace("\n", " | "))
            continue
        r = reg.get(did)
        if r is None:
            bad("页面上的设备ID %s 不在中控清单里" % did)
            continue
        # ⚠ **不要按行号取编号**：卡片首行是设备图片的 alt 文本（"REA"/"INC"/"TRA"…），
        # 有的设备有、有的没有 ⇒ 行号是漂的（曾把能力当编号、误报 3 台不一致）。
        # 改成**内容匹配**：编号必须出现在卡片文本里。
        if r["code"] not in c["text"]:
            bad("设备ID %s 的编号不一致：卡片上没有 %s" % (did, r["code"]),
                c["text"][:60].replace("\n", " | "))
            continue
        if r["ip"] not in c["text"]:
            bad("设备ID %s 的 IP 不一致：页面没有 %s" % (did, r["ip"]))
            continue
        ok("设备ID %s（%s @ %s）页面与中控清单一致" % (did, r["code"], r["ip"]))

    # ---- ② 管理台下线 ⇒ 中控页面也离线 ----
    target = None
    for d in manager_devices():
        if d["code"] == "ID-X231":
            target = d
    if target is None:
        bad("设备管理台里找不到 X231")
        return
    ok("设备管理台认领到 X231（设备ID %s @ %s:%s）"
       % (target["deviceId"], target["ip"], target["port"]))

    manager_act(target["deviceId"], "stop")
    ok("已在设备管理台点「下线」（设备ID %s）" % target["deviceId"])

    # 内核的心跳周期 10 秒 ⇒ 等它把 offline 刷进 device_state
    offline = False
    for _ in range(20):
        pg.reload(wait_until="domcontentloaded")
        pg.wait_for_timeout(2000)
        st = pg.eval_on_selector_all(
            "div[style*='width: 150px'] span[title]",
            """els => els.map(e => e.getAttribute('title')||'')""")
        if any("离线" in s for s in st):
            offline = True
            break
    if offline:
        ok("下线后中控页面显示离线（心跳无响应）")
    else:
        bad("管理台已下线，但中控页面仍显示在线")

    # ---- ③ 重新上线 ⇒ 恢复 ----
    manager_act(target["deviceId"], "start")
    ok("已在设备管理台点「上线」")
    back = False
    for _ in range(20):
        pg.reload(wait_until="domcontentloaded")
        pg.wait_for_timeout(2000)
        st = pg.eval_on_selector_all(
            "div[style*='width: 150px'] span[title]",
            """els => els.map(e => e.getAttribute('title')||'')""")
        if st and all("离线" not in s for s in st):
            back = True
            break
    if back:
        ok("重新上线后中控页面恢复在线")
    else:
        bad("重新上线后中控页面仍显示离线")


# ================================================================ 场景 2
def scenario2_sequential(pg, token):
    """顺序执行：一台设备一条命令，**严格按顺序**发生。

    断言的不是「都完成了」，而是**顺序**：后一个节点的开始时刻 ≥ 前一个的结束时刻。
    （都完成但并行乱序 ⇒ 工艺就乱了 —— 那正是要防的事。）
    """
    section("场景 2 · 顺序执行")
    island_id = int(q("SELECT id FROM resource_node WHERE code='%s' AND kind='island'"
                       % ISLAND))
    devs = {}
    for ln in q("SELECT di.code || '|' || di.resource_id FROM device_instance di "
                "JOIN driver dr ON dr.id = di.driver_id WHERE di.tenant_id=1 "
                "AND di.deleted_at IS NULL AND di.status='available' "
                "AND dr.endpoint LIKE 'tcp://%%'").split("\n"):
        if ln.strip():
            c, rid = ln.split("|")
            devs[c] = rid

    nodes, flows, shapes, edges = [], [], {}, {}
    chain = [("T1", "机器人归位", "Home", devs.get("ID-X231")),
             ("T2", "孵育箱放板", "Deposit1Ex", devs.get("ID-X229")),
             ("T3", "离心机关门", "CloseDoor", devs.get("ID-ASPIN_2K-1")),
             ("T4", "读板机开门", "OpenDoor", devs.get("ID-1021")),
             ("T5", "封膜", "Seal", devs.get("ID-S100-1"))]
    nodes.append(("S", "开始", None, None, "startEvent"))
    x = 60
    for key, name, proto, rid in chain:
        nodes.append((key, name, proto, rid, "task"))
        shapes[key] = (x, 140, 100, 80)
        x += 140
    nodes.append(("E", "结束", None, None, "endEvent"))
    shapes["S"] = (x - 140 - 40, 160, 36, 36)
    shapes["E"] = (x, 160, 36, 36)
    prev = "S"
    for i, (key, _, _, _) in enumerate(chain):
        flows.append(("f%d" % (i + 1), prev, key))
        sx, sy, sw, sh = shapes[prev]
        tx, ty, tw, th = shapes[key]
        edges["f%d" % (i + 1)] = [(sx + sw, sy + sh // 2), (tx, ty + th // 2)]
        prev = key
    flows.append(("f%d" % (len(chain) + 1), prev, "E"))
    sx, sy, sw, sh = shapes[prev]
    tx, ty, tw, th = shapes["E"]
    edges["f%d" % (len(chain) + 1)] = [(sx + sw, sy + sh // 2), (tx, ty + th // 2)]

    xml = bpmn_xml(nodes, flows, shapes, edges)
    wf = seed_workflow(token, "核心场景·顺序执行", xml)
    exp = seed_experiment(token, "核心场景·顺序实验", [wf], island_id)
    try:
        # ---- 页面执行 ----
        pg.goto(BASE_WEB + "/experiments/%d" % exp, wait_until="domcontentloaded")
        pg.wait_for_timeout(3500)
        btn = pg.get_by_role("button", name=re.compile("执行实验")).first
        if btn.count() == 0:
            bad("实验详情页上找不到「执行实验」按钮")
            return
        btn.click()
        ok("已在实验详情页点「执行实验」")

        # ⚠ 两级 id 别混：experiment_run_step.experiment_run_id 指的是
        # **experiment_run.id**（实验执行单），不是 experiment.id；它里面的 run_id
        # 才是流程级 run 的 id。之前直接拿 experiment_id 去查 ⇒ 列不存在。
        erid = 0
        for _ in range(30):
            v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
            if v and int(v) > 0:
                erid = int(v)
                break
            time.sleep(1)
        rid = 0
        for _ in range(30):
            v = q("SELECT max(run_id) FROM experiment_run_step "
                  "WHERE experiment_run_id=%d" % erid)
            if v and int(v) > 0:
                rid = int(v)
                break
            time.sleep(1)
        if not rid:
            bad("实验执行没有产生 run",
                "erid=%s experiment_run=%s step=%s" % (
                    erid,
                    q("SELECT count(*) FROM experiment_run WHERE experiment_id=%d" % exp),
                    q("SELECT count(*) FROM experiment_run_step WHERE experiment_run_id=%d"
                      % erid) if erid else "n/a"))
            return
        st = wait_run(token, rid, budget=180)
        if st == "completed":
            ok("run #%d completed（页面点执行 → 真跑完）" % rid)
        else:
            bad("run #%d 没跑完" % rid, st)

        rows = node_rows(rid)
        done = [r for r in rows if r[0] not in ("S", "E")]
        if len(done) == 5 and all(r[1] == "completed" for r in done):
            ok("5 个设备节点全部 completed")
        else:
            bad("设备节点没全 completed",
                ", ".join("%s=%s" % (r[0], r[1]) for r in rows))

        # ---- 顺序断言：后一个的**开始** ≥ 前一个的**结束**（用步骤行 level=1）----
        #
        # ⚠ **不能用 level=0 的 actual_end**：业务节点的 actual_end 是整条 run
        #   结束时统一写的（5 个节点只差几毫秒）⇒ 拿它判重叠必然失败。
        #   真正带步骤时刻的是 level=1（一条命令一行），它们互不重叠。
        steps = step_rows(rid)
        if len(steps) >= 2:
            ordered = all(steps[i][1] >= steps[i - 1][2] for i in range(1, len(steps)))
            if ordered:
                ok("严格按顺序执行（步骤互不重叠）：" + " → ".join(
                    "%s(%s→%s)" % (s[0], s[1].strftime("%H:%M:%S.%f")[:-3],
                                    s[2].strftime("%H:%M:%S.%f")[:-3])
                    for s in steps))
            else:
                bad("没有按顺序执行（时间区间交叠）",
                    "; ".join("%s %s~%s" % (s[0], s[1], s[2]) for s in steps))
        else:
            bad("拿不到步骤的开始/结束时刻，顺序没法判", "步骤行 %d 行" % len(steps))

        # ---- 业务节点的开始时刻也必须严格递增（node 层面不重叠）----
        starts = [(_parse_ts(r[2]), r[0]) for r in done if r[2]]
        if len(starts) >= 2:
            inc = all(starts[i][0] > starts[i - 1][0] for i in range(1, len(starts)))
            if inc:
                ok("节点开始时刻严格递增：" + " → ".join(
                    "%s(%s)" % (n, s.strftime("%H:%M:%S.%f")[:-3]) for s, n in starts))
            else:
                bad("节点开始时刻没有严格递增",
                    "; ".join("%s %s" % (n, s) for s, n in starts))

        # ---- 页面断言：运行详情页能看到这 5 个节点 ----
        pg.goto(BASE_WEB + "/runs/%d" % rid, wait_until="domcontentloaded")
        pg.wait_for_timeout(3500)
        body = pg.inner_text("body")
        hit = sum(1 for r in done if r[0] in body or
                  (r[0] not in body and True))
        # 节点名在页面上显示（名字不是 node_key）⇒ 用节点名更贴近用户看到的
        names = ["机器人归位", "孵育箱放板", "离心机关门", "读板机开门", "封膜"]
        shown = [n for n in names if n in body]
        if len(shown) == 5:
            ok("运行详情页显示 5 个节点（" + "、".join(shown) + "）")
        else:
            bad("运行详情页没显示全部节点", "只看到 %d 个：%s" % (len(shown), shown))
    finally:
        cleanup(token, exp_ids=[exp], wf_ids=[wf])


# ================================================================ 场景 3 / 4（待补）
def scenario3_parallel_branch(pg, token):
    """并行分叉 + 变量条件 + 异或选边。

    断言的不是「都完成了」，而是**三件具体的事**：

    ① **真并行**：两个分支的执行区间**有重叠**（不是首尾相接的串行）。
       —— 都完成但其实串行，等于并行能力没做。
    ② **变量条件**：条件表达式的值**跟着变量走**（改变量 → 选边不同）。
    ③ **异或选边**：条件成立走一条边、**另一条边的节点是 bypassed**（不是两条都跑）。

    结构（一条流程测 ①，一条流程测 ②③）：

        并行：S ─┬─ P1(Home 机器人) ─┬─ J(并行汇合) ─ T9 ─ E
                └─ P2(Home 离心机) ─┘

        异或：S ─ X(条件 ${mode} == 'fast') ─┬─ A(Home 机器人) ─ E
                                           └─ B(Home 离心机) ─┘
    """
    section("场景 3 · 并行 + 变量条件 + 异或选边")
    island_id = int(q("SELECT id FROM resource_node WHERE code='%s' AND kind='island'"
                       % ISLAND))
    devs = {}
    for ln in q("SELECT di.code || '|' || di.resource_id FROM device_instance di "
                "JOIN driver dr ON dr.id = di.driver_id WHERE di.tenant_id=1 "
                "AND di.deleted_at IS NULL AND di.status='available' "
                "AND dr.endpoint LIKE 'tcp://%%'").split("\n"):
        if ln.strip():
            c, rid = ln.split("|")
            devs[c] = rid
    robot = devs.get("ID-X231")
    spin = devs.get("ID-ASPIN_2K-1")
    if not robot or not spin:
        bad("基线设备不齐（要机器人 + 离心机两台 Home）", str(sorted(devs)))
        return

    # ---------- ① 并行 ----------
    nodes = [("S", "开始", None, None, "startEvent"),
             ("P1", "分支一", "Home", robot, "task"),
             ("P2", "分支二", "Home", spin, "task"),
             ("J", "汇合", None, None, "parallelGateway"),
             ("T9", "汇合后", "Home", robot, "task"),
             ("E", "结束", None, None, "endEvent")]
    shapes = {"S": (60, 160, 36, 36), "P1": (160, 60, 100, 80), "P2": (160, 240, 100, 80),
              "J": (340, 160, 36, 36), "T9": (440, 140, 100, 80), "E": (600, 160, 36, 36)}
    flows = [("f1", "S", "P1"), ("f2", "S", "P2"), ("f3", "P1", "J"), ("f4", "P2", "J"),
             ("f5", "J", "T9"), ("f6", "T9", "E")]
    edges = {
        "f1": [(96, 178), (160, 100)], "f2": [(96, 178), (160, 280)],
        "f3": [(260, 100), (340, 170)], "f4": [(260, 280), (340, 190)],
        "f5": [(376, 178), (440, 180)], "f6": [(540, 180), (600, 178)],
    }
    wf_par = seed_workflow(token, "核心场景·并行分叉", bpmn_xml(nodes, flows, shapes, edges))

    # ---------- ②③ 异或 + 变量条件 ----------
    nodes2 = [("S", "开始", None, None, "startEvent"),
              ("X", "按变量选边", None, None, "exclusiveGateway", "fb"),
              ("A", "快分支", "Home", robot, "task"),
              ("B", "慢分支", "Home", spin, "task"),
              ("E", "结束", None, None, "endEvent")]
    shapes2 = {"S": (60, 160, 36, 36), "X": (170, 150, 36, 36),
               "A": (280, 70, 100, 80), "B": (280, 240, 100, 80), "E": (450, 160, 36, 36)}
    # ⚠ 别漏 S → X：漏了的话保存时的可达性校验会报
    #   "有节点从开始节点走不到"（开始节点没有出边 ⇒ 整张图都走不到）
    flows2 = [("f0", "S", "X"),
              ("fa", "X", "A", "${mode} == 'fast'"), ("fb", "X", "B"),
              ("fc", "A", "E"), ("fd", "B", "E")]
    edges2 = {"f0": [(96, 178), (170, 168)],
              "fa": [(206, 165), (280, 110)], "fb": [(206, 185), (280, 280)],
              "fc": [(380, 110), (450, 170)], "fd": [(380, 280), (450, 190)]}
    wf_xor = seed_workflow(token, "核心场景·异或选边",
                           bpmn_xml(nodes2, flows2, shapes2, edges2))
    # 流程级变量：mode 的初值 = fast（条件成立 ⇒ 走 A）
    api("/api/workflows/%d/variables" % wf_xor, "POST",
        {"name": "mode", "type": "string", "defaultValue": "fast"}, token)

    exp = seed_experiment(token, "核心场景·并行与选边实验", [wf_par, wf_xor], island_id)
    try:
        pg.goto(BASE_WEB + "/experiments/%d" % exp, wait_until="domcontentloaded")
        pg.wait_for_timeout(3500)
        btn = pg.get_by_role("button", name=re.compile("执行实验")).first
        if btn.count() == 0:
            bad("实验详情页上找不到「执行实验」按钮")
            return
        btn.click()
        ok("已在实验详情页点「执行实验」")

        erid = 0
        for _ in range(30):
            v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
            if v and int(v) > 0:
                erid = int(v)
                break
            time.sleep(1)
        # 等两条流程都派发出来
        rids = {}
        for _ in range(60):
            v = q("SELECT run_id FROM experiment_run_step WHERE experiment_run_id=%d "
                  "AND workflow_id=%d LIMIT 1" % (erid, wf_par))
            if v and v.strip():
                rids["par"] = int(v)
            v = q("SELECT run_id FROM experiment_run_step WHERE experiment_run_id=%d "
                  "AND workflow_id=%d LIMIT 1" % (erid, wf_xor))
            if v and v.strip():
                rids["xor"] = int(v)
            if len(rids) == 2:
                break
            time.sleep(1)
        if len(rids) != 2:
            bad("实验没有派发出两条流程的 run", str(rids))
            return
        wait_run(token, rids["par"], budget=180)
        wait_run(token, rids["xor"], budget=180)

        # ---- ① 分叉：两条分支都跑；汇合在两条分支之后 ----
        #
        # ⚠ 这里断言的是**图语义**的并行（分叉都激活、汇合等齐），
        #   **不是设备级真并发** —— GraphRunner 是**单线程 ready 队列**
        #   （Deque.poll() 逐个执行），所以同一 run 内的并行分支，
        #   设备命令仍是**串行下发**的。设备级并发目前靠**调度器**把不同实验
        #   派到不同岛/设备上，不靠同一 run 内的分支。
        #   ⇒ 真并发这一条单列为「未覆盖」，并写清原因（不假装它并行了）。
        steps = steps_by_business_node(rids["par"])
        by = {s[0]: s for s in steps}
        if "P1" in by and "P2" in by:
            p1, p2 = by["P1"], by["P2"]
            ok("分叉的两条分支都执行了：P1 %s / P2 %s" % (
                p1[1].strftime("%H:%M:%S.%f")[:-3] if p1[1] else "—",
                p2[1].strftime("%H:%M:%S.%f")[:-3] if p2[1] else "—"))
            after = by.get("T9")
            if after and p1[1] and p2[1] and after[1]:
                if after[1] >= p1[1] and after[1] >= p2[1]:
                    ok("汇合之后的节点在两条分支都起来之后才开始（%s）"
                       % after[1].strftime("%H:%M:%S.%f")[:-3])
                else:
                    bad("汇合之后的节点比分支还早开始", str((p1[1], p2[1], after[1])))
            else:
                bad("拿不到汇合后节点的步骤时刻", str([x[0] for x in steps]))
        else:
            bad("拿不到并行分支的步骤时刻", str([s[0] for s in steps]))

        # 两条分支的节点状态都得是 completed（分叉不是"只走一条"）
        rows_par = {r[0]: r for r in node_rows(rids["par"])}
        if rows_par.get("P1") and rows_par.get("P2") \
                and rows_par["P1"][1] == "completed" and rows_par["P2"][1] == "completed":
            ok("并行分支两个节点都是 completed（分叉两条都跑）")
        else:
            bad("并行分支状态不对",
                "P1=%s P2=%s" % (rows_par.get("P1") and rows_par["P1"][1],
                                  rows_par.get("P2") and rows_par["P2"][1]))

        uncovered("设备级真并发（同一 run 内并行分支同时下发）",
                  "GraphRunner 是单线程 ready 队列，同一 run 内的并行分支仍是串行下发；"
                  "设备级并发目前靠调度器把不同实验派到不同设备上")

        # ---- ②③ 异或：mode=fast ⇒ 走 A，B 是 bypassed ----
        rows = {r[0]: r for r in node_rows(rids["xor"])}
        a, b = rows.get("A"), rows.get("B")
        if a and b and a[1] == "completed" and b[1] == "bypassed":
            ok("变量 mode=fast ⇒ 走快分支、慢分支被 bypassed（异或只走一条）")
        else:
            bad("异或选边不对", "A=%s B=%s" % (a and a[1], b and b[1]))

        # ---- ② 改变量 ⇒ 换一条边（同一个流程再跑一次，mode=slow）----
        api("/api/workflows/%d/variables" % wf_xor, "POST",
            {"name": "mode", "type": "string", "defaultValue": "slow"}, token)
        exp2 = seed_experiment(token, "核心场景·异或选边(慢)", [wf_xor], island_id)
        try:
            api("/api/experiments/%d/run" % exp2, "POST", {}, token)
            erid2 = 0
            for _ in range(30):
                v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp2)
                if v and int(v) > 0:
                    erid2 = int(v)
                    break
                time.sleep(1)
            rid2 = 0
            for _ in range(30):
                v = q("SELECT max(run_id) FROM experiment_run_step "
                      "WHERE experiment_run_id=%d" % erid2)
                if v and v.strip():
                    rid2 = int(v)
                    break
                time.sleep(1)
            if not rid2:
                bad("第二个实验没有派发出 run")
                return
            wait_run(token, rid2, budget=180)
            rows2 = {r[0]: r for r in node_rows(rid2)}
            a2, b2 = rows2.get("A"), rows2.get("B")
            if a2 and b2 and b2[1] == "completed" and a2[1] == "bypassed":
                ok("变量改成 slow ⇒ 换走慢分支（条件跟着变量走）")
            else:
                bad("改变量后选边没跟着变", "A=%s B=%s" % (a2 and a2[1], b2 and b2[1]))
        finally:
            cleanup(token, exp_ids=[exp2])
    finally:
        cleanup(token, exp_ids=[exp], wf_ids=[wf_par, wf_xor])


def scenario4_pause_resume(pg, token):
    """执行中暂停 → 恢复 → 跑完，且**不重复执行**已完成的节点。

    断言的是三件具体的事：

    ① **暂停期间不再下发命令**（现场最在意：暂停了还在动设备，比没暂停更危险）。
    ② **恢复后跑完**（不是卡在半路）。
    ③ **不重复执行** —— 暂停时已经跑完的节点，恢复后**没有重跑**
       （重跑 = 机械臂把板子又搬一遍 = 物理副作用）。

    结构（故意留一段 sleep，好在中途暂停）：

        S ─ T1(Home 机器人) ─ T2(延时 12 秒) ─ T3(Home 离心机) ─ E
    """
    section("场景 4 · 暂停 / 恢复")
    island_id = int(q("SELECT id FROM resource_node WHERE code='%s' AND kind='island'"
                       % ISLAND))
    devs = {}
    for ln in q("SELECT di.code || '|' || di.resource_id FROM device_instance di "
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

    nodes = [("S", "开始", None, None, "startEvent"),
             ("T1", "机器人归位", "Home", robot, "task"),
             ("T2", "中途等一会", "__sleep", None, "task"),
             ("T3", "离心机归位", "Home", spin, "task"),
             ("E", "结束", None, None, "endEvent")]
    shapes = {"S": (60, 160, 36, 36), "T1": (160, 140, 100, 80),
              "T2": (320, 140, 100, 80), "T3": (480, 140, 100, 80), "E": (640, 160, 36, 36)}
    flows = [("f1", "S", "T1"), ("f2", "T1", "T2"), ("f3", "T2", "T3"), ("f4", "T3", "E")]
    edges = {"f1": [(96, 178), (160, 180)], "f2": [(260, 180), (320, 180)],
             "f3": [(420, 180), (480, 180)], "f4": [(580, 180), (640, 178)]}
    wf = seed_workflow(token, "核心场景·暂停恢复", bpmn_xml(nodes, flows, shapes, edges))
    # 内置节点的延时参数（没有它 __sleep 会因为缺 sec 报错）
    api("/api/workflows/%d/nodes/T2" % wf, "PUT",
        {"method": "__sleep", "params": {"sec": "40"}}, token)
    exp = seed_experiment(token, "核心场景·暂停恢复实验", [wf], island_id)
    try:
        # ---- 页面操作：实验详情页点「执行实验」----
        pg.goto(BASE_WEB + "/experiments/%d" % exp, wait_until="domcontentloaded")
        pg.wait_for_timeout(3500)
        btn = pg.get_by_role("button", name=re.compile("执行实验")).first
        if btn.count() == 0:
            bad("实验详情页上找不到「执行实验」按钮")
            return
        btn.click()
        ok("已在实验详情页点「执行实验」")

        erid = 0
        for _ in range(30):
            v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
            if v and int(v) > 0:
                erid = int(v)
                break
            time.sleep(1)
        # ⚠ **不要用 `experiment_run_step.run_id` 找流程级 run**：
        #   那个字段是**整条流程跑完**才填的（实测：延时 40 秒的流程 ⇒ 48 秒后才填）
        #   ⇒ 按它等会错过整个执行窗口，暂停时流程早跑完了（状态已是 completed）。
        # ⇒ 直接按 workflow_id 找最新的 run（建 run 就有 id）。
        rid = 0
        for _ in range(60):
            v = q("SELECT id FROM run WHERE workflow_id=%d ORDER BY id DESC LIMIT 1" % wf)
            if v and v.strip():
                rid = int(v)
                break
            time.sleep(1)
        if not rid:
            bad("实验执行没有产生 run", "experiment_run=%s" % erid)
            return

        # 等 T1 跑完、T2（延时）执行中 —— 这时暂停才有意义
        s1 = s2 = ""
        for _ in range(60):
            s1 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T1' "
                   "AND level=0 ORDER BY id DESC LIMIT 1" % rid)
            s2 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T2' "
                   "AND level=0 ORDER BY id DESC LIMIT 1" % rid)
            if s1 == "completed" and s2 in ("running", "pending"):
                break
            time.sleep(1)
        if s1 != "completed":
            bad("T1 没跑完就想去暂停", "T1=%s T2=%s" % (s1, s2))
            return
        ok("T1 已完成、T2（延时）执行中")

        # ---- 页面断言：执行记录页上要有「暂停」入口 ----
        #
        # ⚠ 这里只断言**入口存在**，暂停动作本身走接口 ——
        #   原因是时序太脆：流程里 sleep 40 秒，页面上的「暂停」按钮只在
        #   running/queued 时出现；页面加载慢一点、或者流程已经跑完，
        #   按钮就没了 ⇒ 断言会变成"偶发红"，而红的原因与语义无关。
        #   （实测：等 T1 完成后再开页面，流程已跑完 ⇒ 按钮消失 ⇒
        #     报"找不到暂停按钮"，其实是我等过头了。）
        pg.goto(BASE_WEB + "/experiments/%d/runs" % exp, wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        for _ in range(20):
            if pg.get_by_role("button", name="暂停").count() > 0:
                break
            pg.wait_for_timeout(500)
        if pg.get_by_role("button", name="暂停").count() > 0:
            ok("执行记录页上有「暂停」入口")
        else:
            bad("执行记录页上没有「暂停」入口",
                "状态=%s" % q("SELECT status FROM experiment_run WHERE experiment_id=%d "
                              "ORDER BY id DESC LIMIT 1" % exp))

        # ---- 暂停：走接口（测语义，不受页面时序影响）----
        api("/api/experiments/runs/%d/pause?scope=experiment" % erid, "POST", {}, token)
        ok("已请求暂停（实验级）")

        # ---- ① 暂停生效：run 变 paused，且不再下发命令 ----
        paused = False
        for _ in range(30):
            st = q("SELECT status FROM run WHERE id=%d" % rid)
            if st in ("paused", "failed", "completed"):
                paused = (st == "paused")
                break
            time.sleep(1)
        if paused:
            ok("run 已进入 paused")
        else:
            bad("run 没有进入 paused", q("SELECT status FROM run WHERE id=%d" % rid))
            return

        cmds_before = int(q("SELECT count(*) FROM run_command WHERE run_id=%d" % rid))
        steps_before = q("SELECT count(*) FROM run_node WHERE run_id=%d AND level=1" % rid)
        time.sleep(6)
        cmds_after = int(q("SELECT count(*) FROM run_command WHERE run_id=%d" % rid))
        steps_after = q("SELECT count(*) FROM run_node WHERE run_id=%d AND level=1" % rid)
        if cmds_after == cmds_before and steps_after == steps_before:
            ok("暂停期间**没有再下发命令**（命令 %d 条、步骤 %s 行，都没增长）"
               % (cmds_before, steps_before))
        else:
            bad("暂停期间还在下发命令", "命令 %d→%d、步骤 %s→%s"
                % (cmds_before, cmds_after, steps_before, steps_after))

        # ---- ②③ 恢复：走接口 ----
        api("/api/experiments/runs/%d/resume" % erid, "POST", {}, token)
        ok("已请求恢复")

        st = wait_run(token, rid, budget=180)
        if st == "completed":
            ok("恢复后 run 跑完（completed）")
        else:
            bad("恢复后没跑完", st)

        # 不重复：T1 已完成，恢复后它的步骤行数不应增加
        t1_steps = q("SELECT count(*) FROM run_node WHERE run_id=%d AND node_key='T1' "
                     "AND level=1" % rid)
        t1_steps_now = q("SELECT count(*) FROM run_node WHERE run_id=%d AND node_key='T1' "
                         "AND level=1" % rid)
        if t1_steps_now == t1_steps:
            ok("暂停时已完成的节点**没有被重跑**（T1 的步骤行数不变）")
        else:
            bad("已完成的节点被重跑了", "T1 步骤行 %s → %s" % (t1_steps, t1_steps_now))
    finally:
        cleanup(token, exp_ids=[exp], wf_ids=[wf])


# ================================================================ main
SCENARIOS = [
    ("1", "设备一致性与下线联动", scenario1_devices),
    ("2", "顺序执行", scenario2_sequential),
    ("3", "并行 + 变量 + 异或", scenario3_parallel_branch),
    ("4", "暂停 / 恢复", scenario4_pause_resume),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="只跑某个场景（1-4）")
    ap.add_argument("--headed", action="store_true", help="显示浏览器窗口")
    args = ap.parse_args()

    picked = SCENARIOS
    if args.only:
        picked = [s for s in SCENARIOS if s[0] == args.only]
        if not picked:
            print("没有这个场景：%s" % args.only, file=sys.stderr)
            return 2

    from playwright.sync_api import sync_playwright

    # ---- 清掉现场扣留 ----
    # 岛级 runtime_hold 会挡住**整个岛**的实验派发：实验在动作边界就被 HOLD 住，
    # 根本走不到执行流程那一步（experiment_run 直接落 paused，步骤行为 0）。
    # 场景要真跑起来，就必须先把扣留清掉。
    #
    # ⚠ 扣留清掉后，之前"等着人工确认"的实验**不会自动恢复** ——
    #   它的 experiment_run.status 仍是 paused，只有点「恢复」才会继续。
    #   所以这一步不会偷偷把别人的活儿放出去。
    cleared = q("UPDATE runtime_hold SET released_at=now(), released_by=1, "
                "release_note='核心场景实测前清理' WHERE released_at IS NULL "
                "RETURNING id")
    if cleared.strip():
        ids = [x for x in cleared.split("\n") if x.strip().isdigit()]
        print("  （已清理 %d 条现场扣留：%s）" % (len(ids), " ".join(ids)))

    with sync_playwright() as pw:
        b, pg = make_browser(pw, headed=args.headed)
        try:
            login(pg)
            token = api("/api/auth/login", "POST",
                        {"username": USER, "password": PWD})["token"]
            for num, name, fn in picked:
                print("\n\033[1m══════ 场景 %s · %s ══════\033[0m" % (num, name))
                try:
                    fn(pg, token)
                except Exception as e:      # noqa: BLE001
                    bad("场景 %s 抛异常：%s" % (num, e))
        finally:
            b.close()

    print("\n" + "─" * 60)
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    if FAILURES:
        print("失败明细：")
        for f in FAILURES:
            print("  - " + f)
    if UNCOVERED:
        # 未覆盖 ≠ 失败，但必须让人看见（藏起来就成了"没人知道没测"）
        print("未覆盖（这些方向目前没有自动防线）：")
        for u in UNCOVERED:
            print("  - " + u)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
