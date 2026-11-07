#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实验级：设备方法执行中发暂停 —— 实验会不会处于暂停状态？

回答用户 2026-11-06 的问题：
  「当我们发出暂停时候，设备方法正在执行，实验会处于暂停状态吗？」

三层 legacy status 仍作为 M-01 兼容回归，但不能单独证明物理暂停完成：
  · run            —— workflow 级执行行（设备指令挂在它下面）
  · experiment_run —— 实验级执行行（实验级暂停改的是它）
  · experiment     —— 实验本身（由 experiment_run 派生，StatusVocabulary 恒等映射）

PR-S04-A 还必须独立观测 Hold、设备动作、节点边界与命令关联事实。
当前 API 会立即返回 status=paused；这是 legacy 契约，不代表设备动作已收敛。
「未收敛时 API/UI 不得显示 PAUSED」属于 PR-S04-B，不在本脚本本阶段断言。

⚠ 三层最终**都要停在暂停**（2026-11-06 修"实验级暂停停不住底层 run"之后的 M-01 兼容口径）：
  实验级暂停抬的是现场扣留。底层 workflow 级 run 在**节点边界**查扣留
  （`GraphRunner.withPauseChecker` → `RuntimeHold.coveringForWorkflow`），
  命中就把自己落成 paused、停在那儿 —— 还没轮到的节点保持 pending，不再往下跑。
  ⇒ 流程必须**两个设备任务节点以上**，否则最后一个任务跑完就没有下一个边界可停，
     暂停"停不住"只是因为没有剩下的活要停，测不出问题（本脚本第 2 版踩到）。

⚠ 两个曾经踩过的坑（2026-11-06 定位，本脚本第 1 版就踩了两个）：
  1. **run_command 有行 ≠ 设备正在执行**。
     `WorkflowService` 的 run_command 是**步结束后**才 INSERT 的
     （status 直接写 completed/failed，sent_at/finished_at 都是 now()），
     它是「这一步跑完了」的证据，**不是**「指令刚发出去」的证据。
     ⇒ 判断「设备正在执行」必须看 **device_state**：
     `markDeviceBusy` 在 onStepStart 置 status='running' + current_method，
     `markDeviceIdle` 在 onStepEnd 复位 —— 那才是下发时刻。
  2. **不能用 `max(id) FROM run` 定位自己的 run**。
     表里还留着别的测试的 run（有指令、有完成态），
     `max(id)` 一旦命中那条旧 run，整个脚本就在**别人的证据**上做断言
     （第 1 版实测就抓到了 wf=1255 的旧 run，于是「设备正在执行」是假的）。
     ⇒ 一律按 `run.workflow_id = 本次新建的流程` 定位（天然唯一）。

流程：
  1. 建实验（绑一条**含两个机械臂任务节点**的流程）
  2. 触发实验执行（**同步** —— 异步时调度器会立刻跑完实验）
  3. 等 device_state 真的显示「设备方法执行中」（此时后续节点还没轮到）
  4. 发暂停
  5. 观察三层状态 + 后续节点是否被执行 + 暂停生效延迟

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-pause-during-experiment.py --api http://127.0.0.1:18080
"""
import argparse
import importlib.util
import json
import os
import sys
import time
import urllib.error
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_flow_matrix", os.path.join(_HERE, "e2e-flow-matrix.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["e2e_flow_matrix"] = F
_spec.loader.exec_module(F)

ARM_RESOURCE = 200214     # ID1731 的 resource_node.id（机械臂，TRANSFER）
ARM_INSTANCE = 214


def q1(sql):
    return F.q(sql)


def put_raw(base, path, xml_text, token):
    req = urllib.request.Request(base + path, data=xml_text.encode("utf-8"), method="PUT")
    req.add_header("Content-Type", "application/xml")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        raise RuntimeError("PUT %s → %s: %s" % (path, e.code, e.read().decode()[:300]))


def http_post(base, path, token):
    req = urllib.request.Request(base + path, method="POST")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()[:300]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def device_state():
    """设备此刻的执行状态（DB 侧，下发时刻的信号）。

    返回 (status, current_method, current_run_id)：
      status='running' + current_method 非空 ⇒ **设备方法正在执行**。
      current_run_id ⇒ 占用这个设备的 run（用来确认这条不是别人的执行）。
    """
    row = q1("SELECT coalesce(status,'-')||'|'||coalesce(current_method,'-')||'|'||"
             "coalesce(current_run_id::text,'-') FROM device_state "
             "WHERE tenant_id=1 AND resource_id=%d" % ARM_RESOURCE)
    if not row:
        return ("-", "-", "-")
    parts = (row + "||").split("|")
    return (parts[0], parts[1], parts[2])

def own_hold_snapshot(experiment_id, reason):
    """只读取本次请求的 Hold；不依赖全局最新 Hold。"""
    raw = q1("SELECT row_to_json(h)::text FROM ("
             "SELECT id, scope, scope_id, kind, reason, held_at, released_at "
             "FROM runtime_hold WHERE tenant_id=1 AND scope='experiment' "
             "AND scope_id=%d AND reason='%s' ORDER BY id DESC LIMIT 1) h" %
             (experiment_id, reason.replace("'", "''")))
    return json.loads(raw) if raw else None


def node_status(run_id, node_key):
    return q1("SELECT COALESCE((SELECT status::text FROM run_node "
              "WHERE tenant_id=1 AND run_id=%d AND level=0 AND node_key='%s' "
              "ORDER BY id DESC LIMIT 1), '-')" % (run_id, node_key))


def command_count(run_id, parent_key, status=None):
    status_filter = " AND c.status::text='%s'" % status if status else ""
    return int(q1("SELECT count(*) FROM run_command c "
                  "JOIN run_node n ON n.id=c.run_node_id "
                  "LEFT JOIN run_node p ON p.id=n.parent_node_id "
                  "WHERE c.tenant_id=1 AND c.run_id=%d "
                  "AND (n.node_key='%s' OR p.node_key='%s')%s" %
                  (run_id, parent_key, parent_key, status_filter)) or 0)


# 本次造出来的对象（供清理用）。⚠ 失败路径也必须清理 ——
# 残留的 run 留在库里会让别的脚本的 max(id) 抓到它们（2026-11-06 踩过）。
MADE = {"wf": 0, "exp": 0, "run": 0, "er": 0, "hold": 0}


def wait_device_idle(resource_id=200214, limit=90):
    """等设备回到 idle 再删数据；超时后必须保留现场。"""
    deadline = time.time() + limit
    while time.time() < deadline:
        row = q1("SELECT COALESCE(status,'-') FROM device_state "
                 "WHERE tenant_id=1 AND resource_id=%d" % resource_id)
        if row != "running":
            return True
        time.sleep(0.5)
    return False


def cleanup():
    """只清理本次数据；未确认设备动作收敛时 fail-closed 保留现场。"""
    eid, wf_id, hold_id = MADE["exp"], MADE["wf"], MADE["hold"]
    try:
        if not wait_device_idle():
            print("  ⚠ 设备仍在运行：保留运行记录和 Hold，不执行清理")
            return False
        if hold_id:
            q1("UPDATE runtime_hold SET released_at=now(), "
               "release_note='e2e-pause-during-experiment cleanup' "
               "WHERE tenant_id=1 AND id=%d AND released_at IS NULL;" % hold_id)
        if eid:
            q1(f"DELETE FROM experiment_run WHERE experiment_id={eid};")
            q1(f"DELETE FROM experiment_process WHERE experiment_id={eid};")
            q1(f"DELETE FROM experiment WHERE id={eid};")
        if wf_id:
            q1(f"DELETE FROM run_command WHERE run_id IN "
               f"(SELECT id FROM run WHERE workflow_id={wf_id});")
            q1(f"DELETE FROM run_node WHERE run_id IN "
               f"(SELECT id FROM run WHERE workflow_id={wf_id});")
            q1(f"DELETE FROM run WHERE workflow_id={wf_id};")
            q1(f"DELETE FROM workflow_node WHERE workflow_id={wf_id}; "
               f"DELETE FROM workflow WHERE id={wf_id};")
        return True
    except Exception as e:  # noqa: BLE001
        print("  （清理时出错：%s）" % e)
        return False


def finish(code=0):
    """清理 + 打印汇总行 + 退出。"""
    cleaned = cleanup()
    print("  （测试数据已清理）" if cleaned else "  （测试现场保留，需人工检查）")
    print()
    print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
    sys.exit(code if code is not None else (1 if F.FAIL else 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://127.0.0.1:18080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    if not F.have_db():
        print("缺 LABOS_DB_* 环境变量")
        sys.exit(2)
    os.environ.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))

    token = F.api("/api/auth/login", "POST",
                  {"username": args.user, "password": args.pwd})["token"]
    base = args.api

    print("══ 实验级 · 设备执行中发暂停 ══")

    # 1) 建流程（含机械臂节点）
    r = F.api("/api/workflows", "POST",
              {"name": "E2E-实验级暂停-%d" % int(time.time()), "category": "choreography"},
              token)
    wf_id = int(r["id"])
    MADE["wf"] = wf_id

    xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="defs_{wf_id}" targetNamespace="http://labos.local/bpmn">
  <bpmn:process id="p_{wf_id}" name="E2E-实验级暂停" isExecutable="true">
    <bpmn:startEvent id="S"><bpmn:outgoing>flow1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="ARM" name="机械臂搬运1" protocolName="Home" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow1</bpmn:incoming>
      <bpmn:outgoing>flow2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:task id="ARM2" name="机械臂搬运2" protocolName="Home" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow2</bpmn:incoming>
      <bpmn:outgoing>flow3</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="E"><bpmn:incoming>flow3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="flow1" sourceRef="S" targetRef="ARM"/>
    <bpmn:sequenceFlow id="flow2" sourceRef="ARM" targetRef="ARM2"/>
    <bpmn:sequenceFlow id="flow3" sourceRef="ARM2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="d_{wf_id}">
    <bpmndi:BPMNPlane id="pl_{wf_id}" bpmnElement="p_{wf_id}">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S">
        <dc:Bounds x="160" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="ARM_di" bpmnElement="ARM">
        <dc:Bounds x="250" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="ARM2_di" bpmnElement="ARM2">
        <dc:Bounds x="400" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E">
        <dc:Bounds x="550" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="flow1">
        <di:waypoint x="196" y="100"/><di:waypoint x="250" y="100"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="flow2">
        <di:waypoint x="350" y="100"/><di:waypoint x="400" y="100"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="flow3">
        <di:waypoint x="500" y="100"/><di:waypoint x="550" y="100"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>'''
    put_raw(base, f"/api/workflows/{wf_id}/graph", xml, token)

    # 2) 建实验（绑流程 + 岛）
    r = F.api("/api/experiments", "POST",
              {"name": "E2E-实验级暂停-%d" % int(time.time()),
               "workflowIds": [wf_id], "islandId": 2}, token)
    eid = int(r["id"])
    MADE["exp"] = eid
    print("  建流程 #%d、建实验 #%d" % (wf_id, eid))

    # 3) 触发实验执行（**同步**）。
    #    ⚠ 必须同步（不带 async=1）：异步派发时调度器会**立刻**把实验跑完 ——
    #    实测「暂停前 experiment_run 状态」已经是 completed，说明设备还在跑的时候
    #    实验级 run 已经收尾了，暂停发在一条已完成的 run 上（接口照样返回 200）。
    #    同步执行会阻塞 HTTP 响应 ⇒ 正好留出「设备执行中」的时间窗发暂停。
    #    放后台线程跑，主线程负责发暂停。
    import threading
    exec_result = {}

    def _run_sync():
        try:
            exec_result["r"] = F.api(f"/api/experiments/{eid}/run", "POST", {}, token)
        except Exception as e:  # noqa: BLE001
            exec_result["err"] = str(e)

    th = threading.Thread(target=_run_sync, daemon=True)
    th.start()
    print("  触发实验执行（同步，后台线程）")

    # 4) 等两件事：① 我们的 workflow run 建出来了 ② 实验级 run 建出来了。
    #    ⚠ 两个 id 空间：`run`（workflow 级，设备指令挂它下面）与
    #    `experiment_run`（实验级）是不同的表、不同的 id —— 实验级暂停接口
    #    `/api/experiments/runs/{id}/pause` 收的是 **experiment_run.id**
    #    （用 run.id 会报「实验运行记录不存在」）。
    #    ⚠ **按 workflow_id 定位自己的 run**，不用 max(id)：
    #    表里留着别的测试的 run，max(id) 会抓到别人的证据。
    deadline = time.time() + 90
    rid = 0
    erid = 0
    while time.time() < deadline:
        rows = q1(f"SELECT COALESCE(max(id),0) FROM run WHERE workflow_id={wf_id}")
        erows = q1(f"SELECT COALESCE(max(id),0) FROM experiment_run WHERE experiment_id={eid}")
        if rows and int(rows) > 0:
            rid = int(rows)
            erid = int(erows or 0)
            break
        time.sleep(0.1)
    if not rid or not erid:
        F.bad("实验执行没建出 run / experiment_run",
              f"run={rid} experiment_run={erid}")
        finish(1)
        return
    print("  ✓ run #%d / experiment_run #%d 已建立" % (rid, erid))

    # 5) 等「设备方法真的在执行」——看 device_state，不看 run_command。
    #    （run_command 是步结束后才落的库，看到它 = 那一步已经跑完了。）
    deadline = time.time() + 120
    busy = None
    while time.time() < deadline:
        st, meth, owner = device_state()
        if st == "running" and meth != "-" and owner == str(rid):
            busy = (st, meth, owner)
            break
        time.sleep(0.1)
    if not busy or busy[2] != str(rid):
        F.bad("没观测到归属本次 Workflow Run 的设备方法执行中",
              "device_state=%s expected_run=%s" % (str(device_state()), rid))
        finish(1)
        return
    F.ok("观测到设备方法执行中（device_state status=%s current_method=%s，"
         "归属 run #%s）" % (busy[0], busy[1], busy[2]))
    print("  ✓ 设备方法执行中（device_state: %s / %s / run %s）" % busy)

    # 6) 发暂停：确认没有历史 Hold，确保 API 不会幂等复用旧记录。
    import urllib.parse
    reason = "S04A e2e exp=%d erun=%d wrun=%d" % (eid, erid, rid)
    preexisting = int(q1("SELECT count(*) FROM runtime_hold WHERE tenant_id=1 "
                         "AND scope='experiment' AND scope_id=%d "
                         "AND released_at IS NULL" % eid) or 0)
    if preexisting:
        F.bad("测试前本实验已有 active Hold，拒绝复用历史 Hold", str(preexisting))
        finish(1)
        return

    qs = urllib.parse.urlencode({"scope": "experiment", "kind": "manual",
                                 "reason": reason})
    before_st = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
    t_pause_request = time.time()
    code, body = http_post(base, f"/api/experiments/runs/{erid}/pause?{qs}", token)
    t_pause_accepted = time.time()
    try:
        response = json.loads(body)
    except (TypeError, ValueError):
        response = {}
    after_st = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
    ex_after_accept = q1(f"SELECT status::text FROM experiment WHERE id={eid}")
    t_legacy_status_observed = time.time()
    after_device = device_state()
    hold = own_hold_snapshot(eid, reason)
    if hold:
        MADE["hold"] = int(hold["id"])
    MADE["run"], MADE["er"] = rid, erid

    print("  暂停前 experiment_run 状态: %s" % before_st)
    print("  暂停接口返回：HTTP %s %s" % (code, str(response)[:240]))
    print("  暂停后 experiment_run 状态: %s" % after_st)
    print("  请求接受后设备状态: %s" % (after_device,))

    F.ok("Pause API 保持 legacy HTTP 200 / status=paused 契约") \
        if code == 200 and response.get("status") == "paused" \
        else F.bad("Pause API legacy 契约不符", "HTTP=%s body=%s" % (code, body[:300]))
    F.ok("本次请求创建了可追溯的 active Experiment Hold") \
        if hold and hold.get("scope") == "experiment" \
        and int(hold.get("scope_id") or 0) == eid \
        and hold.get("kind") == "manual" and hold.get("reason") == reason \
        and hold.get("released_at") is None \
        else F.bad("未找到本次请求对应的 active Hold", str(hold))
    legacy_status_latency = t_legacy_status_observed - t_pause_request
    F.ok("Experiment legacy 两层在 5 秒内写入 paused（%.2f 秒）" %
         legacy_status_latency) \
        if after_st == "paused" and ex_after_accept == "paused" \
        and legacy_status_latency < 5.0 \
        else F.bad("Experiment legacy 两层未在 5 秒内 paused",
                   "experiment_run=%s experiment=%s latency=%.2f" %
                   (after_st, ex_after_accept, legacy_status_latency))

    # 7) 分别观测动作收敛与 Workflow 节点边界暂停。
    #    注意：DB 轮询时间是“观测时间”，不是硬件精确完成时间；不以三层 status
    #    替代设备状态、节点状态或命令关联证据。
    MADE["run"], MADE["er"] = rid, erid
    device_running_after_accept = (after_device[0] == "running"
                                   and after_device[2] == str(rid))
    if device_running_after_accept:
        F.ok("legacy experiment_run 已 paused 时，本次设备动作仍在运行")
    else:
        print("  说明：API 返回时设备已不再归属本 run（可能在请求响应窗口内自然完成）")

    t_device_settled = None
    t_workflow_paused = None
    deadline = time.time() + 420
    run_st = "-"
    er_st = after_st
    ex_st = ex_after_accept
    arm_st = node_status(rid, "ARM")
    while time.time() < deadline:
        er_st = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
        ex_st = q1(f"SELECT status::text FROM experiment WHERE id={eid}")
        run_st = q1(f"SELECT status::text FROM run WHERE id={rid}")
        arm_st = node_status(rid, "ARM")
        owner = device_state()[2]
        if t_device_settled is None and arm_st == "completed" and owner != str(rid):
            t_device_settled = time.time()
        if t_workflow_paused is None and run_st == "paused":
            t_workflow_paused = time.time()
        if t_device_settled is not None and t_workflow_paused is not None:
            break
        if run_st in ("completed", "failed", "aborted", "canceled"):
            break
        time.sleep(0.2)

    F.ok("实验级两层最终都 paused（experiment_run / experiment）") \
        if er_st == "paused" and ex_st == "paused" \
        else F.bad("实验级两层状态不一致",
                   "experiment_run=%s experiment=%s" % (er_st, ex_st))
    F.ok("Workflow Run 在节点边界进入 paused") \
        if run_st == "paused" and t_workflow_paused is not None \
        else F.bad("Workflow Run 未在节点边界 paused", run_st)
    F.ok("当前 ARM 节点已完成，设备租约不再属于本次 run") \
        if t_device_settled is not None \
        else F.bad("当前设备动作未确认收敛",
                   "ARM=%s device=%s" % (arm_st, str(device_state())))

    # M-01：后续业务节点保持 pending；同时用 run_command 的父子节点关联证明未下发。
    arm2_st = node_status(rid, "ARM2")
    arm_completed_commands = command_count(rid, "ARM", "completed")
    arm2_commands = command_count(rid, "ARM2")
    F.ok("后续 ARM2 节点保持 pending") \
        if arm2_st == "pending" \
        else F.bad("后续 ARM2 节点不是 pending", arm2_st)
    F.ok("当前 ARM 动作有 completed 命令证据") \
        if arm_completed_commands >= 1 \
        else F.bad("当前 ARM 节点缺少 completed 命令证据",
                   str(arm_completed_commands))
    F.ok("后续 ARM2 没有关联设备命令") \
        if arm2_commands == 0 \
        else F.bad("后续 ARM2 已产生设备命令", str(arm2_commands))

    hold_final = own_hold_snapshot(eid, reason)
    F.ok("Workflow paused 后，本次 Hold 仍 active") \
        if hold_final and hold_final.get("released_at") is None \
        else F.bad("暂停期间本次 Hold 未保持 active", str(hold_final))

    # 输出可机器读取的时间线。秒数是轮询观测时刻，不冒充设备硬件时间戳。
    def elapsed(ts):
        return None if ts is None else round(ts - t_pause_request, 3)

    timeline = {
        "experimentId": eid,
        "experimentRunId": erid,
        "workflowId": wf_id,
        "workflowRunId": rid,
        "holdId": MADE["hold"] or None,
        "pauseRequestedAtEpoch": t_pause_request,
        "pauseAcceptedAtEpoch": t_pause_accepted,
        "legacyStatusObservedAtEpoch": t_legacy_status_observed,
        "legacyStatusLatencySeconds": round(legacy_status_latency, 3),
        "deviceRunningObservedBeforeRequest": {
            "status": busy[0], "method": busy[1], "currentRunId": busy[2]
        },
        "deviceStateImmediatelyAfterAccept": {
            "status": after_device[0], "method": after_device[1],
            "currentRunId": after_device[2]
        },
        "legacyExperimentRunStatusBefore": before_st,
        "legacyExperimentRunStatusAfterAccept": after_st,
        "experimentStatusAfterAccept": ex_after_accept,
        "experimentStatusFinal": ex_st,
        "workflowRunStatusFinal": run_st,
        "armNodeStatusFinal": arm_st,
        "arm2NodeStatusFinal": arm2_st,
        "armCompletedCommandCount": arm_completed_commands,
        "arm2CommandCount": arm2_commands,
        "deviceSettledObservedSecondsAfterRequest": elapsed(t_device_settled),
        "workflowPausedObservedSecondsAfterRequest": elapsed(t_workflow_paused),
        "holdAtAccept": hold,
        "holdAtFinalObservation": hold_final
    }
    print("\n  S04A_TIMELINE_JSON=" + json.dumps(timeline, ensure_ascii=False, sort_keys=True))
    F.ok("设备收敛与 Workflow 暂停两个事实均已独立观测") \
        if t_device_settled is not None and t_workflow_paused is not None \
        else F.bad("暂停时间线证据不完整",
                   "device_settled=%s workflow_paused=%s" %
                   (str(t_device_settled), str(t_workflow_paused)))

    print()
    print("  结果：")
    print("    run（workflow 级）      : %s" % run_st)
    print("    experiment_run（实验级） : %s" % er_st)
    print("    experiment（实验本身）   : %s" % ex_st)
    print("    Hold                    : %s" % json.dumps(hold, ensure_ascii=False))
    print("    ARM / ARM2              : %s / %s" % (arm_st, arm2_st))
    print("    ARM completed commands : %d" % arm_completed_commands)
    print("    ARM2 commands           : %d" % arm2_commands)

    # 9) 清理（顺序要紧：run 先删，否则 workflow 删不掉）
    cleanup()
    print("  （测试数据已清理）")
    print()
    print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        cleaned = cleanup()
        if not cleaned:
            print("⚠ 测试现场保留，未确认设备收敛")
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
