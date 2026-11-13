#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测（页面层）：只读流程页上「看得懂 + 改得动」。

**为什么要有这条**：用户现场反馈「/runs/{id}/flow 里还是没能看到逻辑节点是什么」
以及「实验变量已经 100 了，流程却没往下走」。这两件事的答案都在**这一页**上：

  ① 节点清单：逻辑节点（延时/等待/赋值/表达式）在这一页要有**人话说明**
     （"等待 VAR001 = 100（超时 300 秒）"）——
     图上只看得到形状和颜色，看不出这个节点在等什么。
  ② 运行变量卡片：**这一页**就能看到「这条执行此刻的变量值」并改它。
     （关键：改的是**这条执行**的变量表，不是实验级变量 ——
     实验级变量只在实验**启动时**注入，对已经在跑的这次执行无效。）
  ③ 改值 + 恢复 ⇒ 等待中的节点被放行、整条流程走完。

前置：内核 + 前端在跑，机器人 ID-X231 在线。
用法：
  python3 scripts/verify-flow-readonly.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from playwright.sync_api import sync_playwright
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_artifact_cleanup import drop_workflow, drop_experiment  # noqa: E402

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
  id="Fr" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Pr" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="等人工放行" protocolName="__wait" resource="__LOGIC__"/>
    <bpmn:task id="T2" name="真设备一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Fr2">
    <bpmndi:BPMNPlane id="Pr2" bpmnElement="Pr">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="160" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="330" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="490" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="160" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="260" y="180"/><di:waypoint x="330" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="f3"><di:waypoint x="430" y="180"/><di:waypoint x="490" y="178"/></bpmndi:BPMNEdge>
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
    wf = api("/api/workflows", "POST", {"name": "实测·只读流程页"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT",
        raw=XML.replace("__ROBOT__", robot), token=token)
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "__wait",
        "params": {"var": "VAR001", "value": "100", "timeout": 120, "interval": 1}})
    api("/api/workflows/%d/nodes/T2" % wf, "PUT", token=token, body={
        "method": "Home", "params": {}})
    # 流程级初值故意设成 99（≠ 等待条件 100）⇒ 必须人工放行才能往下走
    api("/api/workflows/%d/variables" % wf, "POST", token=token, body={
        "name": "VAR001", "type": "number", "defaultValue": "99"})

    r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
    run_id = r.get("runId") if isinstance(r, dict) else 0
    if not run_id:
        bad("异步启动失败", json.dumps(r, ensure_ascii=False))
        return 1
    print("  run #%d 已启动" % run_id)

    # 等等待节点进入 running
    for _ in range(40):
        st = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T1' "
               "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
        if st == "running":
            break
        time.sleep(1)

    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1600, "height": 1100})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)

        pg.goto("%s/runs/%d/flow" % (WEB, run_id), wait_until="domcontentloaded")
        pg.wait_for_timeout(6000)

        # ---- ① 节点清单：逻辑节点有人话 ----
        if pg.get_by_text("节点在做什么").count() > 0:
            ok("只读流程页有「节点在做什么」清单")
        else:
            bad("只读流程页没有「节点在做什么」清单")
        body = pg.inner_text("body")
        if "等待" in body and "VAR001" in body and "100" in body:
            ok("等待节点显示的是人话：等待 VAR001 = 100（超时 120 秒）")
        else:
            bad("等待节点没有可读说明", body[:200].replace("\n", " | "))

        # ---- ② 运行变量卡片在这一页 ----
        if pg.get_by_text("运行变量").count() > 0:
            ok("只读流程页上有「运行变量」卡片")
        else:
            bad("只读流程页上没有「运行变量」卡片")
        if "99" in body:
            ok("变量卡片上能看到这条执行此刻的值（99）")
        else:
            bad("变量卡片上没看到当前值")

        # ---- ③ 在这一页直接改值 ----
        vcard = pg.locator(".ant-card", has_text="运行变量").last
        btn = vcard.get_by_role("button", name="改").first
        if btn.count() == 0:
            bad("这一页的变量行上没有「改」按钮")
        else:
            btn.click()
            pg.wait_for_timeout(800)
            box = vcard.locator("input.ant-input").first
            box.fill("100")
            box.press("Enter")
            pg.wait_for_timeout(3000)
            v = api("/api/runs/%d/variables" % run_id, "GET", token=token)
            item = next((i for i in v.get("items", []) if i["name"] == "VAR001"), None)
            if item and item.get("value") == "100":
                ok("在只读流程页上改值成功（当前值 = 100）")
            else:
                bad("改值没生效", json.dumps(item, ensure_ascii=False))
            pg.screenshot(path="/tmp/flow-readonly-vars.png", full_page=False)

        # ---- ④ 改值后等待节点被放行 ----
        woke = False
        for _ in range(40):
            st2 = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T2' "
                    "AND level=0 ORDER BY id DESC LIMIT 1" % run_id)
            if st2 == "completed":
                woke = True
                break
            time.sleep(1)
        if woke:
            ok("改值后等待节点被放行、下游设备节点跑完")
        else:
            bad("改值后流程没往下走", st2)
        b.close()

    # 清理：把测试流程与它的 run 从台账里撤掉
    drop_workflow(wf)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
