#!/usr/bin/env python3
# ============================================================================
# 基线用例 · 设备互斥：同一台设备上的命令**不能重叠**。
#
# 为什么这是基线必须有的用例：
#   设备是**独占资源**。两件事同时用同一台设备时，必须排队 ——
#   不排队会出现"两台板同时进同一个孵育箱"这种物理上不可能的状态，
#   而且设备侧回包会串（命令 A 的回包被 B 收到）。
#
# 断言（都是"看得见的事实"）：
#   ① 两个并发 run 都 completed（互斥不该把活卡死）
#   ② 同一台设备上的命令时间段**零重叠**
#   ③ 至少有一次排队（后一条命令的发出时刻 ≥ 前一条的完成时刻）
#
# 用法：python3 scripts/verify-baseline-mutex.py
# ============================================================================
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ISLAND = os.environ.get("LABOS_BASELINE_ISLAND", "LAB-BASE-01")

PASS, FAIL = 0, 0


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def api(path, method="GET", body=None, token=None, raw=None, timeout=60):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if raw is not None:
        data = raw.encode()
        req.add_header("Content-Type", "application/xml")
    elif body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            txt = r.read().decode()
            try:
                return json.loads(txt)
            except json.JSONDecodeError:
                return txt
    except urllib.error.HTTPError as e:
        raise RuntimeError("%s %s → HTTP %d: %s" % (method, path, e.code, e.read().decode()[:300]))


def q(sql):
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


# 一个流程：两个节点都用**同一台**机器人（Home 与 Transfer，都在 robot 画像里）
GRAPH = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_mtx" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_mtx" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="归位" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:task id="T2" name="取板" protocolName="Transfer" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_mtx">
    <bpmndi:BPMNPlane id="P_mtx" bpmnElement="Proc_mtx">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="150" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="300" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="460" y="160" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def main():
    print("── 基线设备互斥 ──")
    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]

    robot = q("SELECT d.id FROM resource_node isl JOIN resource_node d "
              "ON d.parent_id = isl.id AND d.kind = 'device' "
              "WHERE isl.code = '%s' AND d.code = 'X231' AND d.deleted_at IS NULL"
              % ISLAND)
    if not robot:
        bad("基线岛里找不到机器人 X231")
        print("\n通过 %d / 失败 %d" % (PASS, FAIL))
        return 1

    wf = api("/api/workflows", "POST", {"name": "基线·互斥（同设备两节点）"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT",
        raw=GRAPH.replace("__ROBOT__", robot), token=token)

    # 两个并发 run —— 都要用这台机器人
    ids = []
    for _ in range(2):
        r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
        ids.append(r.get("runId") or int(q("SELECT max(id) FROM run")))
    print("  两个并发 run：%s" % ids)

    budget = 240
    deadline = time.time() + budget
    while time.time() < deadline:
        sts = [q("SELECT status FROM run WHERE id=%d" % i) for i in ids]
        if all(s in ("completed", "failed", "aborted", "paused") for s in sts):
            break
        time.sleep(2)

    for i in ids:
        st = q("SELECT status FROM run WHERE id=%d" % i)
        if st == "completed":
            ok("run #%d completed（互斥没把活卡死）" % i)
        else:
            bad("run #%d 没跑完" % i, st)

    # 同一台设备上的命令不能重叠
    overlap = q("""SELECT count(*) FROM run_command a JOIN run_command b
                     ON a.id < b.id AND a.run_id <> b.run_id
                    WHERE a.run_id IN (%s) AND b.run_id IN (%s)
                      AND a.device_id = b.device_id
                      AND a.sent_at < b.finished_at AND b.sent_at < a.finished_at"""
                 % (",".join(str(i) for i in ids), ",".join(str(i) for i in ids)))
    if overlap == "0":
        ok("同一台设备上的命令零重叠")
    else:
        bad("同一台设备的命令重叠了", overlap)

    # 至少有一次排队（后发的那条 ≥ 前一条的完成时刻）
    queued = q("""SELECT count(*) FROM run_command a JOIN run_command b
                   ON a.id < b.id AND a.run_id <> b.run_id
                  WHERE a.run_id IN (%s) AND b.run_id IN (%s)
                    AND a.device_id = b.device_id
                    AND b.sent_at >= a.finished_at"""
               % (",".join(str(i) for i in ids), ",".join(str(i) for i in ids)))
    if queued and queued != "0":
        ok("确实排了队（后一条命令等到了前一条完成）")
    else:
        bad("看不到排队（同设备命令没有先后）", queued)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
