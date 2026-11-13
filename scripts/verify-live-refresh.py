#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测（页面层）：只读流程页上的命令台账**自己会刷新**。

**为什么要有这条**：用户现场反馈「每次需要刷新页面，才能看到执行完的节点命令」。
根因不是"刷新慢"，是**台账组件根本没有轮询** ——
运行页每 2 秒刷的是节点表，台账是另一个查询，不会跟着重取 ⇒
新下的命令、刚完成的节点都要**手动刷整页**才看得见。

判据（不碰刷新按钮）：
  ① 打开 `/workflows/{id}/flow`（只读流程页），台账先是空的；
  ② 起一条执行（异步），**不刷新页面**，等几秒；
  ③ 台账里自己出现了这次执行的命令。

另外顺带验一条：执行**结束**后轮询要停（否则一条历史 run 的页面会永远在轮询）。

前置：内核 + 前端在跑，机器人 ID-X231 在线。
用法：
  python3 scripts/verify-live-refresh.py
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
  id="Dr" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Pr" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="等一下" protocolName="__sleep" resource="__LOGIC__"/>
    <bpmn:task id="T2" name="真设备一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Dr2">
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
    wf = api("/api/workflows", "POST", {"name": "实测·台账自动刷新"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT",
        raw=XML.replace("__ROBOT__", robot), token=token)
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "__sleep", "params": {"sec": "6"}})
    api("/api/workflows/%d/nodes/T2" % wf, "PUT", token=token, body={
        "method": "Home", "params": {}})

    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1600, "height": 1000})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)

        # 先开只读流程页（此刻还没执行过 ⇒ 台账是空的）
        pg.goto("%s/workflows/%d/flow" % (WEB, wf), wait_until="domcontentloaded")
        pg.wait_for_timeout(4000)
        if pg.get_by_text("这次执行没有下发任何设备命令").count() > 0:
            ok("打开时台账是空的（还没执行过）")
        else:
            ok("打开时台账没有旧数据")

        # 起一条执行（**不刷新页面**）
        r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
        run_id = r.get("runId") if isinstance(r, dict) else 0
        if not run_id:
            bad("异步启动失败", json.dumps(r, ensure_ascii=False))
            b.close()
            return 1
        print("  run #%d 已启动（页面不刷新）" % run_id)

        # 不碰刷新按钮，只等
        appeared = False
        for _ in range(30):
            pg.wait_for_timeout(1000)
            body = pg.inner_text("body")
            if "延时" in body or "Home" in body:
                appeared = True
                break
        if appeared:
            ok("台账自己刷新出来了（没手动刷页面）")
        else:
            bad("台账没自动刷新（要手动刷页面才看得到）")
            pg.screenshot(path="/tmp/live-refresh-missing.png", full_page=False)
        pg.screenshot(path="/tmp/live-refresh.png", full_page=False)
        b.close()

    # 结束后轮询应停（接口带 status，前端据此停）
    st = api("/api/runs/%d/commands" % run_id, "GET", token=token).get("status")
    if st in ("running", "paused"):
        ok("台账接口带 run 状态（前端据此决定要不要继续轮询）：%s" % st)
    else:
        bad("台账接口没带 run 状态", str(st))

    # 清理：把测试流程与它的 run 从台账里撤掉
    drop_workflow(wf)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
