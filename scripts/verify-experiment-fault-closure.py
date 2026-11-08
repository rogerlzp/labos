#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""N-10 同实验跨 Workcell 故障闭包 e2e。

口径（ADR-FAULT-SCOPE-002）：同一实验内任一所属 Workcell 扣留 ⇒ 整实验不派发，
不允许只挡那一段。做法：一个实验显式绑定两个 Workcell，给 B 抬 island Hold，
异步提交后断言实验 run 仍在 queued（scheduler 不派发）；解除后离开队列。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PASS = FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print("  ✓ " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  ✗ " + msg + (f"（{detail}）" if detail else ""))


def q(sql):
    env = dict(os.environ)
    env.setdefault("PGPASSWORD", env.get("LABOS_DB_PASS", ""))
    return subprocess.run(
        ["psql", "-h", env["LABOS_DB_HOST"], "-p", env.get("LABOS_DB_PORT", "5432"),
         "-U", env["LABOS_DB_USER"], "-d", env["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True).stdout.strip()


def api(path, method="GET", body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode() or "{}")


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_fc" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_fc" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_fc">
    <bpmndi:BPMNPlane id="P_fc" bpmnElement="Proc_fc">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="260" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="260" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""


def main():
    token = api("/api/auth/login", "POST", {"username": "admin", "password": "admin123"})["token"]
    islands = [int(x) for x in q("SELECT id FROM resource_node WHERE tenant_id=1 AND kind='island' ORDER BY id LIMIT 2").split()]
    if len(islands) < 2:
        print("SKIP: 库里没有两个岛")
        return 0
    a, b = islands[0], islands[1]
    q("DELETE FROM runtime_hold WHERE tenant_id=1 AND scope='island' AND scope_id IN (%d,%d)" % (a, b))
    wf = api("/api/workflows", "POST", {"name": "verify-fault-closure", "category": "choreography"}, token)["id"]
    req = urllib.request.Request(BASE + f"/api/workflows/{wf}/graph", data=XML.encode(),
                                 method="PUT", headers={"Content-Type": "application/xml",
                                                        "Authorization": "Bearer " + token})
    urllib.request.urlopen(req, timeout=30).read()
    eid = api("/api/experiments", "POST",
              {"name": "verify-fault-closure", "islandId": a, "workflowIds": [wf]}, token)["id"]
    api(f"/api/experiments/{eid}/workcells", "PUT",
        {"workcellIds": [a, b]}, token)
    q("INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason) "
      "VALUES (1,'island',%d,'human','e2e 同实验跨 Workcell 故障')" % b)
    try:
        er = api(f"/api/experiments/{eid}/run?async=1", "POST", {}, token).get("experimentRunId")
        time.sleep(12)
        st = q("SELECT status FROM experiment_run WHERE id=%d" % er)
        ok("B Workcell 扣留时，整实验 run 保持 queued（不派发）") if st == "queued" else bad("实验被派发", st)
        st_json = api("/api/scheduler/status", "GET", None, token)
        in_queue = any(int(x.get("experimentRunId") or 0) == int(er)
                       for x in st_json.get("queue", []))
        ok("调度队列里能看到该实验（等闸门解除）") if in_queue else bad("调度队列里没有该实验")
        q("UPDATE runtime_hold SET released_at=now(), release_note='e2e clean' "
          "WHERE tenant_id=1 AND scope='island' AND scope_id=%d" % b)
        deadline = time.time() + 30
        left = False
        while time.time() < deadline:
            st = q("SELECT status FROM experiment_run WHERE id=%d" % er)
            if st != "queued":
                left = True
                break
            time.sleep(1)
        ok("解除 B Workcell 扣留后离开 queued（已派发）") if left else bad("解除后仍 queued", st)
    finally:
        q("UPDATE runtime_hold SET released_at=now(), release_note='e2e clean' "
          "WHERE tenant_id=1 AND scope='island' AND scope_id IN (%d,%d)" % (a, b))
        q(f"DELETE FROM experiment_run_step WHERE experiment_run_id={er if 'er' in dir() and er else -1};"
          f"DELETE FROM experiment_run WHERE experiment_id={eid};"
          f"DELETE FROM experiment_process WHERE experiment_id={eid};"
          f"DELETE FROM experiment_workcell WHERE experiment_id={eid};"
          f"DELETE FROM experiment WHERE id={eid};")
        q(f"DELETE FROM workflow_node WHERE workflow_id={wf}; DELETE FROM workflow WHERE id={wf};")
    print(f"通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
