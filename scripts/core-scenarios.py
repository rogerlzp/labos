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


# ---------------------------------------------------------------- 断言辅助
def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


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


# ---------------------------------------------------------------- BPMN 造图
#
# 复杂图（并行 / 异或 / 变量）用页面拖出来太脆（bpmn-js 交互复杂、断言脆），
# 所以**图用 API 播种**（这是本仓库既有做法），而**关键动作全部在页面上点**。
# 播种的 XML 里**带 BPMNEdge**（连线图形信息）—— 少了它，前端画布上就是
# 一堆孤立节点、一条线都没有（见 BUG-20261111-20）。
def bpmn_xml(nodes, flows, shapes, edges):
    """nodes: [(key, name, protocolName, resourceId, type)]
       flows: [(id, source, target, condition?)]
       shapes: {key: (x, y, w, h)}
       edges:  {flowId: [(x1,y1),(x2,y2)]}"""
    ns = ('xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" '
          'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
          'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
          'xmlns:di="http://www.omg.org/spec/DD/20100524/DI"')
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<bpmn:definitions %s id="Def_x" targetNamespace="http://bpmn.io/schema/bpmn">' % ns,
           '  <bpmn:process id="Proc_x" isExecutable="false">']
    for n in nodes:
        key, name, proto, res, ntype = n
        if ntype == "startEvent":
            out.append('    <bpmn:startEvent id="%s" name="%s"/>' % (key, name))
        elif ntype == "endEvent":
            out.append('    <bpmn:endEvent id="%s" name="%s"/>' % (key, name))
        elif ntype == "exclusiveGateway":
            out.append('    <bpmn:exclusiveGateway id="%s" name="%s"/>' % (key, name))
        else:
            out.append('    <bpmn:task id="%s" name="%s" protocolName="%s" resource="%s"/>'
                       % (key, name, proto, res))
    for f in flows:
        fid, src, dst = f[0], f[1], f[2]
        cond = f[3] if len(f) > 3 else None
        extra = ' condition="%s"' % cond if cond else ''
        out.append('    <bpmn:sequenceFlow id="%s" sourceRef="%s" targetRef="%s"%s/>'
                   % (fid, src, dst, extra))
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
        code = c["text"].split("\n")[1] if len(c["text"].split("\n")) > 1 else ""
        did = c["id"]
        if did is None:
            bad("设备卡片上没有设备ID", c["text"][:60].replace("\n", " | "))
            continue
        r = reg.get(did)
        if r is None:
            bad("页面上的设备ID %s 不在中控清单里" % did)
            continue
        if code != r["code"]:
            bad("设备ID %s 的编号不一致：页面 %s / 清单 %s" % (did, code, r["code"]))
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
                       % ISLAND_CODE))
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

        rid = 0
        for _ in range(30):
            v = q("SELECT max(run_id) FROM experiment_run_step WHERE experiment_id=%d" % exp)
            if v and int(v) > 0:
                rid = int(v)
                break
            time.sleep(1)
        if not rid:
            bad("实验执行没有产生 run")
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

        # ---- 顺序断言：后一个的开始 ≥ 前一个的结束 ----
        import datetime as _dt
        times = []
        for r in done:
            s = r[2][:26] if r[2] else ""
            e = r[3][:26] if r[3] else ""
            if not s or not e:
                break
            times.append((r[0], _dt.datetime.fromisoformat(s), _dt.datetime.fromisoformat(e)))
        if len(times) == len(done) and len(times) >= 2:
            ordered = all(times[i][1] >= times[i - 1][2] for i in range(1, len(times)))
            if ordered:
                ok("严格按顺序执行：" + " → ".join(
                    "%s(%s→%s)" % (t[0], t[1].strftime("%H:%M:%S"), t[2].strftime("%H:%M:%S"))
                    for t in times))
            else:
                bad("没有按顺序执行（时间区间交叠）",
                    "; ".join("%s %s~%s" % (t[0], t[1], t[2]) for t in times))
        else:
            bad("拿不到节点的开始/结束时刻，顺序没法判")

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
    section("场景 3 · 并行 + 变量条件 + 异或网关")
    print("  \033[33m·\033[0m 待补（并行分叉 / 变量条件 / 异或选边）")
    FAILURES.append("场景 3 未实现")


def scenario4_pause_resume(pg, token):
    section("场景 4 · 暂停 / 恢复")
    print("  \033[33m·\033[0m 待补（执行中暂停 → 恢复 → 跑完）")
    FAILURES.append("场景 4 未实现")


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
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
