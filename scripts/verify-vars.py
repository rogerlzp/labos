#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测「流程级变量的初始值」到底有没有进运行期。

**为什么要有这条**：变量在设计器里能声明、能下拉选，但运行期的变量表
（``run.input->'vars'``）以前是空的 ⇒ 声明了初值也替换不掉
（现场表现：设备参数里还留着 ``${plateCount}`` 字面量）。

三层优先级（后者盖前者），本脚本逐层验证：

  ① **流程级初值**      —— 流程变量管理台 / 设计器「流程变量」卡片里声明的 default_value
  ② **请求传入的变量**  —— ``POST /api/workflows/{id}/run?async=1&vars=k=v``
  ③ **实验级变量**      —— 实验自己的变量（同名覆盖流程级）

**判据**（不看页面，只看事实）：
  - ``run_command.payload`` 里参数是**替换后的值**，不是 ``${xxx}`` 字面量；
  - ``run.input->'vars'`` 里变量确实存在。

前置：内核在跑 + 基线岛的设备在线（机器人 ID-X231 至少在线）。
用法：
  python3 scripts/verify-vars.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PSQL = ["psql", "-h", os.environ.get("LABOS_DB_HOST", "192.168.124.2"),
        "-p", "5432", "-U", os.environ.get("LABOS_DB_USER", "labos"),
        "-d", os.environ.get("LABOS_DB_NAME", "labdb"), "-tAc"]

PASS, FAIL = [], []


def q(sql):
    """跑 SQL。**失败必须抛错** —— 返回空串会被当成「库里没有」⇒ 假绿。"""
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "$LABOS_DB_PASS")
    p = subprocess.run(PSQL + [sql], capture_output=True, text=True, env=env)
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
    with urllib.request.urlopen(req, data=data, timeout=120) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("  ✓ " if ok else "  ✗ ") + name + (("  —— " + detail) if detail else ""))


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_v" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_v" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="读一个变量" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_v">
    <bpmndi:BPMNPlane id="P_v" bpmnElement="Proc_v">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="150" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="300" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="150" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="250" y="180"/><di:waypoint x="300" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def build_workflow(token, name):
    robot = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-X231'")
    if not robot:
        raise RuntimeError("找不到机器人 ID-X231 的 device_instance —— 先跑 sim-up.sh")
    xml = XML.replace("__ROBOT__", robot)
    wf = api("/api/workflows", "POST", {"name": name}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=xml, token=token)
    # 绑方法 + 参数（参数里引用 ${plateCount}）
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "Home",
        "params": {"plateCount": "${plateCount}"},
        "resourceId": int(robot),
    })
    return wf


def last_run_id(wf):
    v = q("SELECT max(id) FROM run WHERE workflow_id=%d" % wf)
    return int(v) if v else 0


def cmd_payload(run_id):
    """run_command 里这条 run 下发出去的参数（判据看它，不看页面）。"""
    rows = q("SELECT payload::text FROM run_command WHERE run_id=%d ORDER BY id" % run_id)
    return rows


def payload_value(run_id, key):
    for row in cmd_payload(run_id).split("\n"):
        row = row.strip()
        if not row:
            continue
        try:
            p = json.loads(row)
        except json.JSONDecodeError:
            continue
        if key in p:
            return str(p[key])
    return None


def wait_run_done(run_id, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        st = q("SELECT status FROM run WHERE id=%d" % run_id)
        if st in ("completed", "failed", "aborted", "cancelled"):
            return st
        time.sleep(1)
    return q("SELECT status FROM run WHERE id=%d" % run_id)


class IslandHold:
    """岛级扣留的**临时放开 + 原样恢复**。

    为什么需要：岛级 {@code runtime_hold} 会挡住整个岛的实验派发
    （实验在动作边界就被 HOLD 住，根本走不到执行流程那一步）。
    测「实验级变量覆盖流程级初值」必须先把岛放行。

    ⚠ **必须原样恢复**：现场可能正有一条人工确认中的实验靠这条扣留停着
    （用户点「恢复」前它一直停在那儿）。所以这里**放行同一条 hold 行**
    （released_at 置空），不新建行 ⇒ hold id / held_at / reason 都不变，
    用户的现场状态原样保留。测试期间新冒出来的岛级扣留（测试自己触发的）
    会一并释放掉。
    """

    def __init__(self):
        self.hold_id = None
        self.max_id = 0

    def __enter__(self):
        row = q("SELECT id FROM runtime_hold WHERE released_at IS NULL "
                "ORDER BY id DESC LIMIT 1")
        self.max_id = int(q("SELECT COALESCE(max(id),0) FROM runtime_hold"))
        if row:
            self.hold_id = int(row)
            q("UPDATE runtime_hold SET released_at = now() WHERE id=%d" % self.hold_id)
            print("    （临时放行岛级扣留 id=%d，测完原样恢复）" % self.hold_id)
        return self

    def __exit__(self, *exc):
        # 测试期间新冒出来的扣留（id 更大的）一并释放
        q("UPDATE runtime_hold SET released_at = now() "
          "WHERE released_at IS NULL AND id > %d" % self.max_id)
        if self.hold_id:
            q("UPDATE runtime_hold SET released_at = NULL WHERE id=%d" % self.hold_id)
            print("    （岛级扣留 id=%d 已恢复）" % self.hold_id)
        return False


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    isl = q("SELECT id FROM resource_node WHERE code='LAB-BASE-01' AND kind='island'")
    name = "实测·变量初值"

    # ---------- ① 流程级初值 ----------
    print("\n[①] 流程级变量的初始值 ⇒ 进运行期变量表")
    wf = build_workflow(token, name)
    api("/api/workflows/%d/variables" % wf, "POST", token=token, body={
        "name": "plateCount", "type": "number", "defaultValue": "3",
        "description": "板数",
    })
    vars_json = api("/api/workflows/%d/variables" % wf, "GET", token=token)
    check("变量已声明（设计器下拉的数据源）",
          any(v["name"] == "plateCount" for v in vars_json.get("items", [])),
          json.dumps(vars_json.get("items", []), ensure_ascii=False))

    r = api("/api/workflows/%d/run" % wf, "POST", {}, token)
    if isinstance(r, dict) and r.get("error"):
        check("同步执行", False, r["error"])
        return finish()
    rid = last_run_id(wf)
    st = wait_run_done(rid)
    check("同步执行跑完（status=%s）" % st, st == "completed")
    got = payload_value(rid, "plateCount")
    check("设备参数里的 ${plateCount} 被替换成初值 3", got == "3", "实际=%r" % got)
    iv = q("SELECT input::text FROM run WHERE id=%d" % rid)
    check("run.input->vars 里有 plateCount", '"plateCount"' in iv, iv[:160])

    # ---------- ② 请求传入的变量覆盖初值 ----------
    print("\n[②] 请求传入的变量（?vars=）覆盖流程级初值")
    r = api("/api/workflows/%d/run?async=1&vars=plateCount=9" % wf, "POST", {}, token)
    rid2 = r.get("runId") if isinstance(r, dict) else 0
    if not rid2:
        check("异步启动", False, json.dumps(r, ensure_ascii=False))
        return finish()
    st2 = wait_run_done(rid2)
    check("异步执行跑完（status=%s）" % st2, st2 == "completed")
    got2 = payload_value(rid2, "plateCount")
    check("请求变量 9 盖过初值 3", got2 == "9", "实际=%r" % got2)
    iv2 = q("SELECT input::text FROM run WHERE id=%d" % rid2)
    check("异步 run 的变量表里 plateCount=9", '"plateCount":"9"' in iv2.replace(" ", ""),
          iv2[:200])

    # ---------- ③ 实验级变量覆盖流程级 ----------
    print("\n[③] 实验级变量覆盖流程级同名变量")
    with IslandHold():
        exp = api("/api/experiments", "POST",
                  {"name": name + "·实验", "workflowIds": [wf], "islandId": int(isl)},
                  token)["id"]
        api("/api/experiments/%d/variables" % exp, "PUT", token=token, body=[
            {"name": "plateCount", "type": "number", "defaultValue": "7"}])
        api("/api/experiments/%d/run" % exp, "POST", {}, token)
        er = 0
        for _ in range(30):
            v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
            if v and int(v) > 0:
                er = int(v)
                break
            time.sleep(1)
        check("实验 run 已创建", er > 0)
        if er:
            evars = q("SELECT variables::text FROM experiment_run WHERE id=%d" % er)
            check("实验级变量进了 experiment_run.variables", '"plateCount"' in evars, evars[:120])
            # 等实验这一步真的派发出流程 run（experiment_run_step.run_id 指向它）
            wfr = 0
            end = time.time() + 120
            while time.time() < end:
                v = q("SELECT COALESCE(max(run_id),0) FROM experiment_run_step "
                      "WHERE experiment_run_id=%d" % er)
                if v and int(v) > 0:
                    wfr = int(v)
                    break
                time.sleep(1)
            check("实验派出了流程级 run", wfr > 0)
            if wfr:
                end = time.time() + 120
                while time.time() < end:
                    if q("SELECT status FROM run WHERE id=%d" % wfr) in (
                            "completed", "failed", "aborted", "cancelled"):
                        break
                    time.sleep(1)
                got3 = payload_value(wfr, "plateCount")
                check("实验级变量 7 盖过流程级初值 3", got3 == "7", "实际=%r" % got3)
                iv3 = q("SELECT input::text FROM run WHERE id=%d" % wfr)
                check("流程 run 的变量表里 plateCount=7", '"plateCount":"7"' in iv3.replace(" ", ""),
                      iv3[:200])

    return finish()


def finish():
    print("\n=== %d 通过 / %d 失败 ===" % (len(PASS), len(FAIL)))
    for f in FAIL:
        print("  失败: " + f)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
