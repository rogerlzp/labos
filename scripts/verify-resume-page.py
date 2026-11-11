#!/usr/bin/env python3
"""实测（页面层）：实验详情页点「恢复」→ 流程真的从卡住的节点接着跑。

流程：造一个必然失败的节点 → 执行 → 节点转人工、实验 paused
     → 在**实验详情页**的执行历史行点「恢复」
     → 断言那个节点被**重做**（attempt 递增），而不是被跳过。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from playwright.sync_api import sync_playwright

API = "http://127.0.0.1:18080"
WEB = "http://localhost:5173"


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
    with urllib.request.urlopen(req, data=data, timeout=120) as r:
        t = r.read().decode()
        try:
            return json.loads(t)
        except Exception:
            return t


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    out = subprocess.run(["psql", "-h", os.environ["LABOS_DB_HOST"],
                          "-U", os.environ["LABOS_DB_USER"],
                          "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
                         env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:200])
    return out.stdout.strip()


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="D" targetNamespace="x">
  <bpmn:process id="P" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="会失败的一步" protocolName="NoSuchMethod" resource="__READER__"/>
    <bpmn:task id="T2" name="该继续跑的一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Dg">
    <bpmndi:BPMNPlane id="Pl" bpmnElement="P">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="150" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="300" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="460" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="150" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="250" y="180"/><di:waypoint x="300" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="f3"><di:waypoint x="400" y="180"/><di:waypoint x="460" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""

PASS = FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m)


def bad(m, d=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    # 清掉现场扣留（它会挡住整个岛派发）
    q("UPDATE runtime_hold SET released_at=now(), released_by=1, "
      "release_note='页面恢复实测前清理' WHERE released_at IS NULL")

    reader = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-1021'")
    robot = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-X231'")
    xml = XML.replace("__READER__", reader).replace("__ROBOT__", robot)
    wf = api("/api/workflows", "POST", {"name": "实测·页面恢复"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=xml, token=token)
    isl = int(q("SELECT id FROM resource_node WHERE code='LAB-BASE-01' AND kind='island'"))
    exp = api("/api/experiments", "POST",
              {"name": "实测·页面恢复", "workflowIds": [wf], "islandId": isl}, token)["id"]
    api("/api/experiments/%d/run" % exp, "POST", {}, token)

    erid = 0
    for _ in range(30):
        v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
        if v and int(v) > 0:
            erid = int(v)
            break
        time.sleep(1)
    rid = 0
    for _ in range(30):
        v = q("SELECT run_id FROM experiment_run_step WHERE experiment_run_id=%d LIMIT 1" % erid)
        if v and v.strip():
            rid = int(v)
            break
        time.sleep(1)
    if not rid:
        bad("流程没派发出来")
        return 1
    # 等它转人工
    for _ in range(30):
        st = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='T1' AND level=0" % rid)
        if st in ("manual", "failed"):
            break
        time.sleep(2)
    before = q("SELECT coalesce(max(attempt),0) FROM run_node WHERE run_id=%d AND node_key='T1'" % rid)
    print("  执行前：T1 attempt=%s，实验=%s" % (
        before, q("SELECT status FROM experiment_run WHERE id=%d" % erid)))
    if q("SELECT status FROM experiment_run WHERE id=%d" % erid) != "paused":
        bad("实验没有处于暂停状态")
        return 1

    # ---- 页面层：在实验详情页点「恢复」 ----
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1500, "height": 950})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)
        pg.goto("%s/experiments/%d" % (WEB, exp), wait_until="domcontentloaded")
        pg.wait_for_timeout(4000)
        btn = pg.get_by_role("button", name="恢复").first
        if btn.count() == 0:
            bad("实验详情页上找不到「恢复」按钮")
            pg.screenshot(path="/tmp/resume-btn-missing.png", full_page=False)
            b.close()
            return 1
        ok("实验详情页执行历史行上有「恢复」按钮")
        btn.click()
        pg.wait_for_timeout(4000)
        pg.screenshot(path="/tmp/resume-clicked.png", full_page=False)
        b.close()

    # ---- 断言：节点被重做（而不是被跳过） ----
    after = 0
    for _ in range(30):
        after = int(q("SELECT coalesce(max(attempt),0) FROM run_node "
                      "WHERE run_id=%d AND node_key='T1'" % rid) or 0)
        if after > int(before):
            break
        time.sleep(2)
    print("  恢复后：T1 attempt=%s" % after)
    if after > int(before):
        ok("点「恢复」后那个节点被**重做**了 ⇒ 续跑真的触发（不是假完成）")
    else:
        bad("点「恢复」后节点没被重做", "attempt %s→%s" % (before, after))

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
