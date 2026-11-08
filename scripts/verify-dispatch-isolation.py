#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""N-10/P-03 故障注入：A Workcell 扣留时，无关实验 B 仍能派发完成。

口径（ADR-FAULT-SCOPE-001/002）：
  - 同一实验内任一所属 Workcell 故障/扣留 ⇒ 整实验不派发；
  - 其他独立实验不受牵连（P-03 逐候选，而不是旧的全局闸门）。

做法：两个只含 start→end 的实验，分别绑两个岛；给 A 岛抬一道 island Hold，
异步提交两个实验。断言 A 保持 queued、B completed；解除 A 岛 Hold 后 A 也能完成。
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
        return json.loads(r.read().decode())


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_iso" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_iso" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_iso">
    <bpmndi:BPMNPlane id="P_iso" bpmnElement="Proc_iso">
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
    isl_a, isl_b = islands[0], islands[1]
    q("DELETE FROM runtime_hold WHERE tenant_id=1 AND scope='island' AND scope_id IN (%d,%d)" % (isl_a, isl_b))
    wf = api("/api/workflows", "POST", {"name": "verify-dispatch-iso", "category": "choreography"}, token)["id"]
    # graph 接口要 raw XML，走 urllib 手写
    req = urllib.request.Request(BASE + f"/api/workflows/{wf}/graph", data=XML.encode(),
                                 method="PUT", headers={"Content-Type": "application/xml",
                                                        "Authorization": "Bearer " + token})
    urllib.request.urlopen(req, timeout=30).read()
    exp = []
    for i, isl in enumerate((isl_a, isl_b)):
        exp.append(api("/api/experiments", "POST",
                       {"name": f"verify-dispatch-iso-{i}", "islandId": isl,
                        "workflowIds": [wf]}, token)["id"])
    q("INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason) VALUES (1,'island',%d,'human','e2e 故障注入 A 岛')" % isl_a)
    try:
        er = []
        for eid in exp:
            er.append(api(f"/api/experiments/{eid}/run?async=1", "POST", {}, token).get("experimentRunId"))
        deadline = time.time() + 90
        seen = {"a_queued": False, "b_started": False, "b_status": None}
        while time.time() < deadline:
            sts = q("SELECT id||':'||status FROM experiment_run WHERE id IN (%s)"
                    % ",".join(str(int(x)) for x in er if x)).split()
            m = dict(s.split(":") for s in sts)
            sa = m.get(str(er[0]))
            sb = m.get(str(er[1]))
            if sa == "queued":
                seen["a_queued"] = True
            if sb in ("running", "completed", "failed", "aborted"):
                seen["b_started"] = True
                seen["b_status"] = sb
            if sa == "completed" and sb == "completed":
                break
            time.sleep(1)
        ok("A 岛扣留时，A 实验保持 queued（未派发）") if seen["a_queued"] else bad("A 实验被错误派发")
        ok("无关实验 B 被正常派发（逐候选，不是全局闸门；状态 %s）" % seen["b_status"]) \
            if seen["b_started"] else bad("B 实验没有被派发")
        sa = q("SELECT status FROM experiment_run WHERE id=%d" % er[0])
        ok("A 实验仍不是 completed（扣留期间不跑）") if sa != "completed" else bad("A 被错误放行", sa)
    finally:
        q("UPDATE runtime_hold SET released_at=now(), release_note='e2e clean' WHERE tenant_id=1 AND scope='island' AND scope_id IN (%d,%d)" % (isl_a, isl_b))
        for eid in exp:
            q(f"DELETE FROM experiment_run_step WHERE experiment_run_id IN (SELECT id FROM experiment_run WHERE experiment_id={eid});"
              f"DELETE FROM experiment_run WHERE experiment_id={eid};"
              f"DELETE FROM experiment_process WHERE experiment_id={eid};"
              f"DELETE FROM experiment WHERE id={eid};")
        q(f"DELETE FROM workflow_node WHERE workflow_id={wf}; DELETE FROM workflow WHERE id={wf};")
    print(f"通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
