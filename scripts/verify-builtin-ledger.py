#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测（页面层 + 接口层）：内置逻辑节点也进命令台账，且**看得懂**。

**为什么要有这条**：内置节点（`__sleep` / `__wait` / `__setVar` / `__expr`）不碰设备
⇒ 从来不经过设备步骤的落库路径 ⇒ 命令台账里**一条都没有**。现场看到的现象：
「延时节点显示正在运行中，但台账里什么都没有」「点等待节点，没看到任何提示，
不知道它在做什么」。

断言（从接口到页面）：
  ① 台账里有内置节点的行（method = `__sleep` / `__wait`，device 为空）；
  ② 台账的「在做什么」列写的是**人话**（"延时 60 秒" / "等待 gate = open"），
     不是 `__wait` 这种内部名；
  ③ 等待中的节点每 5 秒更新一次进度（"已等 N 秒"）—— 长等待不是一块死板；
  ④ 运行详情页的节点行也显示这句人话（点等待节点看得到它在等什么）。

前置：内核 + 前端在跑。
用法：
  python3 scripts/verify-builtin-ledger.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from playwright.sync_api import sync_playwright

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")

PASS = FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m)


def bad(m, d=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))


def api(path, method="GET", body=None, token=None, raw=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = raw.encode() if raw is not None else (
        json.dumps(body).encode() if body is not None else None)
    if raw is not None:
        req.add_header("Content-Type", "application/xml")
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=120) as r:
            txt = r.read().decode()
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            j = json.loads(txt)
        except json.JSONDecodeError:
            j = {"ok": False, "error": txt[:200]}
        j["_httpStatus"] = e.code
        return j
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        return txt


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    out = subprocess.run(["psql", "-h", os.environ["LABOS_DB_HOST"],
                          "-U", os.environ["LABOS_DB_USER"],
                          "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
                         env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return out.stdout.strip()


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Db" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Pb" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="延时一下" protocolName="__sleep" resource="__LOGIC__"/>
    <bpmn:task id="T2" name="等人工放行" protocolName="__wait" resource="__LOGIC__"/>
    <bpmn:task id="T3" name="真设备一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="T3"/>
    <bpmn:sequenceFlow id="f4" sourceRef="T3" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Db2">
    <bpmndi:BPMNPlane id="Pb2" bpmnElement="Pb">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="150" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="310" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T3_di" bpmnElement="T3"><dc:Bounds x="470" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="630" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="150" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="250" y="180"/><di:waypoint x="310" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="f3"><di:waypoint x="410" y="180"/><di:waypoint x="470" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f4_di" bpmnElement="f4"><di:waypoint x="570" y="180"/><di:waypoint x="630" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    robot = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-X231'")
    if not robot:
        bad("找不到机器人 ID-X231 —— 先跑 sim-up.sh")
        return 1
    wf = api("/api/workflows", "POST", {"name": "实测·内置节点台账"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT",
        raw=XML.replace("__ROBOT__", robot), token=token)
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "__sleep", "params": {"sec": "3"}})
    api("/api/workflows/%d/nodes/T2" % wf, "PUT", token=token, body={
        "method": "__wait",
        "params": {"var": "gate", "value": "open", "timeout": 120, "interval": 1}})
    api("/api/workflows/%d/nodes/T3" % wf, "PUT", token=token, body={
        "method": "Home", "params": {}})
    api("/api/workflows/%d/variables" % wf, "POST", token=token, body={
        "name": "gate", "type": "string", "defaultValue": "closed"})

    r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
    run_id = r.get("runId") if isinstance(r, dict) else 0
    if not run_id:
        bad("异步启动失败", json.dumps(r, ensure_ascii=False))
        return 1
    print("  run #%d 已启动" % run_id)

    # ---- 等延时节点结束、等待节点开始 ----
    sleep_done = False
    for _ in range(40):
        st = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T1' "
               "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
        st2 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T2' "
                "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
        if st == "completed" and st2 == "running":
            sleep_done = True
            break
        time.sleep(1)
    if not sleep_done:
        bad("延时节点没走完 / 等待节点没开始")
        return 1
    ok("延时节点已执行完、等待节点已在运行")

    # ---- ① 台账里有内置节点的行 ----
    cmds = api("/api/runs/%d/commands" % run_id, "GET", token=token)["commands"]
    builtin = [c for c in cmds if c.get("method") in ("__sleep", "__wait")]
    if len(builtin) >= 2:
        ok("台账里有内置节点的行（%d 条）" % len(builtin))
    else:
        bad("台账里没有内置节点的行", json.dumps(cmds, ensure_ascii=False)[:200])
    if any(c.get("method") == "__sleep" for c in builtin):
        ok("延时节点在台账里")
    else:
        bad("台账里没有 __sleep")
    if any(c.get("method") == "__wait" for c in builtin):
        ok("等待节点在台账里")
    else:
        bad("台账里没有 __wait")
    if all(c.get("deviceId") is None for c in builtin):
        ok("内置节点的台账行没有设备（本来就没碰设备）")
    else:
        bad("内置节点的台账行不该有设备")

    # ---- ② 人话描述 ----
    notes = {c["method"]: (c.get("rawResponse") or "") for c in builtin}
    if "延时" in notes.get("__sleep", ""):
        ok("延时节点的人话说明写清了在延时什么：%s" % notes.get("__sleep"))
    else:
        bad("延时节点没有可读说明", notes.get("__sleep"))
    if "等待" in notes.get("__wait", "") and "gate" in notes.get("__wait", ""):
        ok("等待节点的人话说明写清了在等什么：%s" % notes.get("__wait"))
    else:
        bad("等待节点没有可读说明", notes.get("__wait"))

    # ---- ③ 等待进度会更新（已等 N 秒）----
    progressed = False
    for _ in range(20):
        time.sleep(1)
        n = q("SELECT rc.result->>'note' FROM run_command rc "
              "JOIN run_node rn ON rn.id = rc.run_node_id "
              "WHERE rc.run_id=%d AND rc.method='__wait' AND rn.level=0 "
              "LIMIT 1" % run_id)
        if "已等" in (n or ""):
            progressed = True
            break
    if progressed:
        ok("等待途中台账会更新进度（已等 N 秒）：%s" % n)
    else:
        bad("等待途中台账没有进度更新", n)

    # ---- ④ 页面层：运行详情页节点行有这句人话 ----
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1600, "height": 1000})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)
        pg.goto("%s/runs/%d" % (WEB, run_id), wait_until="domcontentloaded")
        pg.wait_for_timeout(5000)
        body = pg.inner_text("body")
        if "等待" in body and "gate" in body:
            ok("运行详情页看得到等待节点在等什么（人话）")
        else:
            bad("运行详情页没显示等待节点在等什么")
        if "延时" in body:
            ok("运行详情页看得到延时节点在延时什么")
        else:
            bad("运行详情页没显示延时节点的说明")
        # 台账里也要有「在做什么」这一列
        if pg.get_by_text("在做什么").count() > 0:
            ok("命令台账有「在做什么」列")
        else:
            bad("命令台账没有「在做什么」列")
        pg.screenshot(path="/tmp/builtin-ledger.png", full_page=False)
        b.close()

    # ---- 放行：改变量让等待节点走下去 ----
    api("/api/runs/%d/variables" % run_id, "PUT", token=token,
        body={"name": "gate", "value": "open"})
    done = False
    for _ in range(40):
        st3 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T3' "
                "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
        if st3 == "completed":
            done = True
            break
        time.sleep(1)
    if done:
        ok("改值后等待节点放行、下游设备节点跑完")
    else:
        bad("改值后下游没跑完", st3)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
