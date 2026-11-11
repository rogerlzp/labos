#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测两条最容易"看起来做了、其实没做"的执行语义：

  ① **实验级恢复真正续跑**：恢复前 manual 节点被跳过、后续节点没跑，
     却报了 completed（**假完成**）。判据：恢复后那个 manual 节点的
     **attempt 递增** ⇒ 它被**重做**了，续跑真的触发了。

  ② **跳过失败节点并继续执行**：节点转 manual 后，页面原有的动作只有
     「恢复」（重新排队）与「忽略」（标记已忽略、从队列消失），
     **两个都不会让流程从那个节点往下跑**。判据：跳过后下游节点 completed，
     且被跳过的节点留痕 `skip_confirmed=true`（状态是"跳过"不是"完成"）。

**用例怎么造**：一个**必然失败**的节点（给读板机一个它没有的方法 NoSuchMethod）
+ 后面一个真节点（机器人 Home）。「必然失败」保证它一定转人工，
不依赖下线设备那种要等心跳刷新的时序。

用法（要内核 + 管理台 + 5 台设备在线）：
  python3 scripts/verify-resume-skip.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

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
    with urllib.request.urlopen(req, data=data, timeout=90) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt

MANAGER = os.environ.get("LABOS_MANAGER_URL", "http://127.0.0.1:8600")


def manager_devices():
    with urllib.request.urlopen(MANAGER + "/api/devices", timeout=15) as r:
        return json.loads(r.read().decode()).get("devices", [])


def manager_act(device_id, what):
    """管理台的动作。**已经在线/已经下线时它会返回 400**（幂等提示）⇒ 容忍。"""
    req = urllib.request.Request(MANAGER + "/api/devices/%s/%s" % (device_id, what),
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"ok": False, "http": e.code, "body": e.read().decode()[:120]}


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
  id="Def_t" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_t" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="必失败的一步" protocolName="NoSuchMethod" resource="__READER__"/>
    <bpmn:task id="T2" name="该继续跑的一步" protocolName="Home" resource="__ROBOT__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_t">
    <bpmndi:BPMNPlane id="P_t" bpmnElement="Proc_t">
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

def nodes(rid):
    return {ln.split("|")[0]: ln.split("|")[1] for ln in q(
        "SELECT node_key || '|' || status FROM run_node WHERE run_id=%d AND level=0" % rid
    ).split("\n") if ln.strip()}

def build(token, name, fail_mode=False):
    reader = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-1021'")
    robot = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-X231'")
    xml = XML.replace("__READER__", reader).replace("__ROBOT__", robot)
    wf = api("/api/workflows", "POST", {"name": name}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=xml, token=token)
    isl = int(q("SELECT id FROM resource_node WHERE code='LAB-BASE-01' AND kind='island'"))
    exp = api("/api/experiments", "POST",
              {"name": name, "workflowIds": [wf], "islandId": isl}, token)["id"]
    return wf, exp

def run_exp(token, exp):
    api("/api/experiments/%d/run" % exp, "POST", {}, token)
    for _ in range(30):
        v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
        if v and int(v) > 0:
            return int(v)
        time.sleep(1)
    return 0

def wait_online(res, budget=40):
    end = time.time() + budget
    while time.time() < end:
        st = q("SELECT coalesce((SELECT CASE WHEN ds.online THEN 't' ELSE 'f' END "
               "FROM device_state ds WHERE ds.resource_id=%s), 'f')" % res)
        if st == "t":
            return True
        time.sleep(2)
    return False


def wait_offline(res, budget=40):
    end = time.time() + budget
    while time.time() < end:
        st = q("SELECT coalesce((SELECT CASE WHEN ds.online THEN 't' ELSE 'f' END "
               "FROM device_state ds WHERE ds.resource_id=%s), 'f')" % res)
        if st == "f":
            return True
        time.sleep(2)
    return False


def clear_holds():
    """清掉岛级扣留 —— 扣留是**全局闸门**，一条未释放的扣留会让整个岛派发不出去
    （我第一版测试就是被自己上一条用例留下的扣留锁住，流程压根没派发）。"""
    q("UPDATE runtime_hold SET released_at=now(), released_by=1, "
      "release_note='测试前清理' WHERE released_at IS NULL")


def wait_dispatch(er, budget=60):
    """等流程被派发（experiment_run_step.run_id 有值）—— 派发前它一直是空的。"""
    end = time.time() + budget
    while time.time() < end:
        v = q("SELECT run_id FROM experiment_run_step WHERE experiment_run_id=%d LIMIT 1" % er)
        if v and v.strip():
            return int(v)
        time.sleep(1)
    return 0


def wait(rid, budget=60):
    end = time.time() + budget
    while time.time() < end:
        st = q("SELECT status FROM run WHERE id=%d" % rid)
        if st in ("completed", "failed", "paused", "aborted"):
            return st
        time.sleep(2)
    return q("SELECT status FROM run WHERE id=%d" % rid)

PASS = FAIL = 0
def ok(m):
    global PASS; PASS += 1; print("  \033[32m✓\033[0m " + m)
def bad(m, d=""):
    global FAIL; FAIL += 1; print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))

def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    # 开头先确保所有设备在线（上一轮可能下线过某台）⇒ 否则流程压根派发不出来
    for d in manager_devices():
        if not d["online"]:
            manager_act(d["deviceId"], "start")
    time.sleep(12)

    # ---------- 缺陷 1：实验级恢复要真正续跑 ----------
    print("\n\033[1m── 缺陷 1 · 实验级恢复真正续跑 ──\033[0m")
    clear_holds()
    # 先把机器人下线 ⇒ T1 会失败（拿不到设备）⇒ 转人工
    robot_dev = next(d for d in manager_devices() if d["code"] == "ID-X231")
    robot_res = q("SELECT di.resource_id FROM device_instance di WHERE di.code='ID-X231'")
    wf, exp = build(token, "实测·恢复续跑", fail_mode=True)
    er = run_exp(token, exp)
    rid = wait_dispatch(er)
    if not rid:
        bad("流程没派发出来（experiment_run_step.run_id 一直为空）")
        return 1
    wait(rid, 40)
    n = nodes(rid)
    if n.get("T1") == "manual":
        ok("T1 按预期转人工（%s）" % n.get("T1"))
    else:
        bad("T1 没转人工", str(n))
    if n.get("T2") in ("pending", None):
        ok("T2 还没跑（卡在 T1）")
    else:
        bad("T2 意外跑了", str(n))

    attempt_before = q("SELECT coalesce(max(attempt),0) FROM run_node "
                       "WHERE run_id=%d AND node_key='T1' AND level=0" % rid)
    cmd_before = q("SELECT count(*) FROM run_command c JOIN run_node n ON n.id=c.run_node_id "
                   "WHERE c.run_id=%d AND n.node_key='T1'" % rid)
    r = api("/api/experiments/runs/%d/resume" % er, "POST", {}, token)
    ok("点了恢复：%s" % json.dumps(r, ensure_ascii=False)[:120])
    st = wait(rid, 60)
    n = nodes(rid)
    attempt_after = q("SELECT coalesce(max(attempt),0) FROM run_node "
                      "WHERE run_id=%d AND node_key='T1' AND level=0" % rid)
    cmd_after = q("SELECT count(*) FROM run_command c JOIN run_node n ON n.id=c.run_node_id "
                  "WHERE c.run_id=%d AND n.node_key='T1'" % rid)
    print("     恢复后节点：%s（T1 attempt %s→%s，命令 %s→%s）"
          % (n, attempt_before, attempt_after, cmd_before, cmd_after))
    # T1 必然再失败一次 ⇒ 判据是"它被**重做**了"，不是"T2 跑完了"
    if int(attempt_after) > int(attempt_before) or int(cmd_after) > int(cmd_before):
        ok("恢复后 T1 被**重做**了（attempt/命令递增）⇒ 续跑真的触发了，不是假完成")
    else:
        bad("恢复后 T1 没被重做 ⇒ 还是假完成（manual 节点被跳过了）",
            "attempt %s→%s" % (attempt_before, attempt_after))

    # ---------- 缺陷 2：跳过失败节点并继续 ----------
    print("\n\033[1m── 缺陷 2 · 跳过失败节点并继续执行 ──\033[0m")
    clear_holds()
    # 机器人必须在线（场景 1 下线过一次）⇒ 上线并等内核心跳刷新在线态，
    # 否则续跑时内核仍判它离线 ⇒ T2 拿不到设备 ⇒ 又转人工（那是**时序**不是实现问题）
    manager_act(robot_dev["deviceId"], "start")
    wait_online(robot_res)
    print("     已确保 ID-X231 在线并等内核心跳刷新")
    wf2, exp2 = build(token, "实测·跳过继续", fail_mode=True)
    er2 = run_exp(token, exp2)
    rid2 = wait_dispatch(er2)
    if not rid2:
        bad("流程没派发出来")
        return 1
    wait(rid2, 40)
    n2 = nodes(rid2)
    if n2.get("T1") != "manual":
        bad("T1 没转人工，跳过无从谈起", str(n2))
    else:
        ok("T1 转人工（%s）" % n2.get("T1"))
    r2 = api("/api/runs/%d/skip-node" % rid2, "POST", {"nodeKey": "T1"}, token)
    ok("点了「跳过此节点并继续」：%s" % json.dumps(r2, ensure_ascii=False)[:160])
    st2 = wait(rid2, 60)
    n2 = nodes(rid2)
    print("     跳过后的节点：%s（run=%s）" % (n2, st2))
    if n2.get("T2") == "completed":
        ok("跳过后 T2 跑了（继续执行生效）")
    else:
        bad("跳过后 T2 没跑", str(n2))
    flag = q("SELECT coalesce(input->>'skip_confirmed','') FROM run_node "
             "WHERE run_id=%d AND node_key='T1' AND level=0" % rid2)
    if flag == "true":
        ok("T1 留痕：skip_confirmed=true（界面能看出是被跳过的，不是做成了）")
    else:
        bad("T1 没有留痕", flag)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
