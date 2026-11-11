#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测（页面层）：运行详情页上的「运行变量」卡片 —— 看得到当前值，也能就地改。

**为什么要有这条**：API 能改 ≠ 现场能改。现场只认页面：
变量值得**看得到**（当前值 / 初值分开摆，别让人以为初值就是现值），
也得**改得动**（点一下就能改，改完流程真的往下走）。

判据：
  ① 运行详情页有「运行变量」卡片，列出变量；
  ② 「当前值」和「初值」是**两列**，各写各的；
  ③ 点「改」→ 输入新值 → 保存 ⇒ 表格里变成新值；
  ④ 改完流程**自己往下走**（__wait 节点被唤醒，下游节点也完成）
     —— 这一条最关键：改值要是只写进库、流程读不到，比不能改更糟。

前置：内核 + 前端在跑，机器人 ID-X231 在线。
用法：
  python3 scripts/verify-run-vars-page.py
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
            body2 = json.loads(txt)
        except json.JSONDecodeError:
            body2 = {"ok": False, "error": txt[:200]}
        body2["_httpStatus"] = e.code
        return body2
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
  id="DvP" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="PvP" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="等人工放行" protocolName="__wait" resource="__LOGIC__"/>
    <bpmn:task id="T2" name="放行后该跑的一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="DvP2">
    <bpmndi:BPMNPlane id="PvP2" bpmnElement="PvP">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="150" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="310" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="470" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="150" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="250" y="180"/><di:waypoint x="310" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="f3"><di:waypoint x="410" y="180"/><di:waypoint x="470" y="178"/></bpmndi:BPMNEdge>
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
    wf = api("/api/workflows", "POST", {"name": "实测·页面运行变量"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=XML.replace("__ROBOT__", robot),
        token=token)
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "__wait",
        "params": {"var": "gate", "value": "open", "timeout": 180, "interval": 1},
    })
    api("/api/workflows/%d/nodes/T2" % wf, "PUT", token=token, body={
        "method": "Home", "params": {},
    })
    api("/api/workflows/%d/variables" % wf, "POST", token=token, body={
        "name": "gate", "type": "string", "defaultValue": "closed",
    })
    r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
    run_id = r.get("runId") if isinstance(r, dict) else 0
    if not run_id:
        bad("异步启动失败", json.dumps(r, ensure_ascii=False))
        return 1
    print("  run #%d 已启动" % run_id)

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

        card = pg.locator(".ant-card", has_text="运行变量")
        if card.count() == 0:
            bad("运行详情页没有「运行变量」卡片")
            pg.screenshot(path="/tmp/runvars-card-missing.png", full_page=False)
            b.close()
            return 1
        ok("运行详情页有「运行变量」卡片")
        if card.get_by_text("当前值").count() > 0 and card.get_by_text("初值").count() > 0:
            ok("卡片里「当前值」和「初值」是两列（不混）")
        else:
            bad("卡片里没分开「当前值」和「初值」")
        if card.get_by_text("closed").count() >= 2:
            ok("gate 的当前值与初值都显示为 closed")
        else:
            bad("卡片里没看到 gate = closed", card.inner_text()[:120].replace("\n", " | "))

        # ---- 就地改值 ----
        edit = card.get_by_role("button", name="改").first
        if edit.count() == 0:
            bad("变量行上没有「改」按钮")
            pg.screenshot(path="/tmp/runvars-no-edit.png", full_page=False)
        else:
            edit.click()
            pg.wait_for_timeout(700)
            box = card.locator("input.ant-input").first
            box.fill("open")
            box.press("Enter")
            pg.wait_for_timeout(3000)
            txt = card.inner_text()
            if "open" in txt:
                ok("改完之后卡片里显示新值 open")
            else:
                bad("改完之后卡片里没看到 open", txt[:160].replace("\n", " | "))
            pg.screenshot(path="/tmp/runvars-edited.png", full_page=False)
        b.close()

    # ---- 改完流程自己往下走 ----
    woke = False
    for _ in range(45):
        st2 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T1' "
                "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
        st3 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T2' "
                "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
        if st2 == "completed" and st3 == "completed":
            woke = True
            break
        time.sleep(1)
    if woke:
        ok("页面上改值后，等待中的节点被唤醒、流程自己跑完了")
    else:
        bad("页面上改值后流程没往下走", "T1=%s T2=%s" % (st2, st3))

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
