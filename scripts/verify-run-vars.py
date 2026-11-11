#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测：运行期变量**看得见、也改得动**，而且改了**真的影响正在跑的流程**。

**为什么要有这条**：变量以前只在设计器里声明，运行期到底是多少没人看得见，
想改更没门路。而"能改"如果只是写进库、流程读不到，那比"不能改"更糟
（现场会以为放行了，其实流程还卡着）。

判据（三层，从表到行为）：
  ① **看得见**：GET /api/runs/{id}/variables 同时给出
     「当前值」（这次执行此刻的值）与「初值」（流程里声明的默认值），两者不混；
  ② **改得动**：PUT 改完立刻读回，值变了；
  ③ **改得有用**：流程里有个 ``__wait`` 节点在等 ``gate == "open"``，
     改值之后它**自己就走下去了**（不用点恢复）—— 这才是"人工放行"的真语义。

③ 是关键：``__wait`` 每轮都重读 ``run.input->'vars}``
（``GraphRunner.withVariableRefresher``）⇒ 改值 = 唤醒。

前置：内核在跑 + 机器人 ID-X231 在线。
用法：
  python3 scripts/verify-run-vars.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("  ✓ " if ok else "  ✗ ") + name + (("  —— " + detail) if detail else ""))


def q(sql):
    env = dict(os.environ)
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", "$LABOS_DB_PASS"))
    p = subprocess.run(["psql", "-h", os.environ.get("LABOS_DB_HOST", "192.168.124.2"),
                        "-p", "5432", "-U", os.environ.get("LABOS_DB_USER", "labos"),
                        "-d", os.environ.get("LABOS_DB_NAME", "labdb"), "-tAc", sql],
                       capture_output=True, text=True, env=env)
    if p.returncode != 0:
        raise RuntimeError("SQL 失败: %s\n%s" % (sql, p.stderr.strip()))
    return p.stdout.strip()


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
        # 4xx/5xx 也要把响应体读出来（错误信息就在体里）
        txt = e.read().decode()
        try:
            body = json.loads(txt)
        except json.JSONDecodeError:
            body = {"ok": False, "error": txt[:200]}
        body["_httpStatus"] = e.code
        return body
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        return txt

XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Dvr" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Pvr" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="等人工放行" protocolName="__wait" resource="__LOGIC__"/>
    <bpmn:task id="T2" name="放行后该跑的一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Dvr2">
    <bpmndi:BPMNPlane id="Pvr2" bpmnElement="Pvr">
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


def node_status(run_id, key):
    v = q("SELECT status FROM run_node WHERE run_id=%d AND node_key='%s' "
          "AND level=0 ORDER BY id DESC LIMIT 1" % (run_id, key))
    return v


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    robot = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-X231'")
    if not robot:
        print("找不到机器人 ID-X231 的 device_instance —— 先跑 sim-up.sh")
        return 1
    wf = api("/api/workflows", "POST", {"name": "实测·运行期变量"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=XML.replace("__ROBOT__", robot),
        token=token)
    # 逻辑节点（__wait）：等 gate == "open"，超时 180 秒
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "__wait",
        "params": {"var": "gate", "value": "open", "timeout": 180, "interval": 1},
    })
    api("/api/workflows/%d/nodes/T2" % wf, "PUT", token=token, body={
        "method": "Home", "params": {},
    })
    # 声明 gate，初值 closed（⇒ 一上来就该等）
    api("/api/workflows/%d/variables" % wf, "POST", token=token, body={
        "name": "gate", "type": "string", "defaultValue": "closed",
    })

    r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
    run_id = r.get("runId") if isinstance(r, dict) else 0
    if not run_id:
        print("异步启动失败: %s" % json.dumps(r, ensure_ascii=False))
        return 1
    print("  run #%d 已启动" % run_id)

    # ---- ① 看得见 ----
    v = api("/api/runs/%d/variables" % run_id, "GET", token=token)
    items = {x["name"]: x for x in v.get("items", [])}
    check("变量清单里能看到 gate", "gate" in items, json.dumps(v.get("items", []), ensure_ascii=False))
    g = items.get("gate", {})
    check("初值和当前值是**两列**，不混", g.get("defaultValue") == "closed",
          "defaultValue=%r value=%r" % (g.get("defaultValue"), g.get("value")))
    check("当前值一开始就是初值（启动时写进变量表）", g.get("value") == "closed",
          "value=%r" % g.get("value"))
    check("运行中可编辑", v.get("editable") is True, json.dumps(v.get("status")))

    # ---- 等它真的在等 ----
    for _ in range(30):
        st = node_status(run_id, "T1")
        if st:
            break
        time.sleep(1)
    check("T1（__wait）在等，没有立刻跑完", st in ("running", "pending"), "status=%s" % st)

    # ---- ② 改得动 ----
    res = api("/api/runs/%d/variables" % run_id, "PUT", token=token,
              body={"name": "gate", "value": "open"})
    check("改值接口返回 ok", res.get("ok") is True, json.dumps(res, ensure_ascii=False))
    v2 = api("/api/runs/%d/variables" % run_id, "GET", token=token)
    g2 = {x["name"]: x for x in v2.get("items", [])}.get("gate", {})
    check("读回来就是新值 open", g2.get("value") == "open", "value=%r" % g2.get("value"))

    # ---- ③ 改得有用：流程自己走下去 ----
    woke = False
    for _ in range(40):
        st2 = node_status(run_id, "T1")
        st3 = node_status(run_id, "T2")
        if st2 == "completed" and st3 == "completed":
            woke = True
            break
        time.sleep(1)
    check("改值后 __wait 自己走下去了（不用点恢复）", woke,
          "T1=%s T2=%s" % (node_status(run_id, "T1"), node_status(run_id, "T2")))

    # ---- 非法变量名要挡 ----
    bad = api("/api/runs/%d/variables" % run_id, "PUT", token=token,
              body={"name": "1bad name", "value": "x"})
    check("非法变量名被挡（400）", bad.get("ok") is False, json.dumps(bad, ensure_ascii=False))

    print("\n=== %d 通过 / %d 失败 ===" % (len(PASS), len(FAIL)))
    for f in FAIL:
        print("  失败: " + f)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
