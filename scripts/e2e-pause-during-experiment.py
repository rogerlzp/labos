#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实验级：设备方法执行中发暂停 —— 实验会不会处于暂停状态？

回答用户 2026-11-06 的问题：
  「当我们发出暂停时候，设备方法正在执行，实验会处于暂停状态吗？」

三层状态都要看（它们不是一回事）：
  · run            —— workflow 级执行行（设备指令挂在它下面）
  · experiment_run —— 实验级执行行（实验级暂停改的是它）
  · experiment     —— 实验本身（由 experiment_run 派生，StatusVocabulary 恒等映射）

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
  1. 建实验（绑一条含机械臂节点的流程）
  2. 触发实验执行（**同步** —— 异步时调度器会立刻跑完实验）
  3. 等 device_state 真的显示「设备方法执行中」
  4. 发暂停
  5. 观察三层状态 + 设备指令最终状态 + 暂停生效延迟

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-pause-during-experiment.py --api http://127.0.0.1:18080
"""
import argparse
import importlib.util
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


# 本次造出来的对象（供清理用）。⚠ 失败路径也必须清理 ——
# 残留的 run 留在库里会让别的脚本的 max(id) 抓到它们（2026-11-06 踩过）。
MADE = {"wf": 0, "exp": 0}


def cleanup():
    """清理现场。顺序要紧：run 先删，否则 workflow 删不掉（FK）。"""
    eid, wf_id = MADE["exp"], MADE["wf"]
    try:
        q1("DELETE FROM runtime_hold WHERE released_at IS NULL;")
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
    except Exception as e:  # noqa: BLE001
        print("  （清理时出错：%s）" % e)


def finish(code=0):
    """清理 + 打印汇总行 + 退出。"""
    cleanup()
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
    <bpmn:task id="ARM" name="机械臂搬运" protocolName="Home" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow1</bpmn:incoming>
      <bpmn:outgoing>flow2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="E"><bpmn:incoming>flow2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="flow1" sourceRef="S" targetRef="ARM"/>
    <bpmn:sequenceFlow id="flow2" sourceRef="ARM" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="d_{wf_id}">
    <bpmndi:BPMNPlane id="pl_{wf_id}" bpmnElement="p_{wf_id}">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S">
        <dc:Bounds x="160" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="ARM_di" bpmnElement="ARM">
        <dc:Bounds x="250" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E">
        <dc:Bounds x="420" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="flow1">
        <di:waypoint x="196" y="100"/><di:waypoint x="250" y="100"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="flow2">
        <di:waypoint x="350" y="100"/><di:waypoint x="420" y="100"/></bpmndi:BPMNEdge>
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
        if st == "running" and meth != "-" and owner in ("-", str(rid)):
            busy = (st, meth, owner)
            break
        time.sleep(0.1)
    if not busy:
        F.bad("没观测到设备方法执行中（device_state 一直是 idle）",
              str(device_state()))
        finish(1)
        return
    F.ok("观测到设备方法执行中（device_state status=%s current_method=%s，"
         "归属 run #%s）" % (busy[0], busy[1], busy[2]))
    print("  ✓ 设备方法执行中（device_state: %s / %s / run %s）" % busy)

    # 6) 发暂停（走真实的实验级暂停接口：现场扣留）
    import urllib.parse
    qs = urllib.parse.urlencode({"scope": "experiment", "kind": "manual",
                                 "reason": "e2e 设备执行中暂停"})
    before_st = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
    code, body = http_post(base, f"/api/experiments/runs/{erid}/pause?{qs}", token)
    after_st = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
    print("  暂停前 experiment_run 状态: %s" % before_st)
    print("  暂停接口返回：HTTP %s %s" % (code, str(body)[:120]))
    print("  暂停后 experiment_run 状态: %s" % after_st)
    t_pause = time.time()

    # 7) 观察
    t_paused = None
    deadline = time.time() + 180
    while time.time() < deadline:
        er = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
        ex = q1(f"SELECT status FROM experiment WHERE id={eid}")
        if t_paused is None and (er == "paused" or ex == "paused"):
            t_paused = time.time()
        # workflow 级 run 跑完 = 设备指令那一步结束了（run_command 落库）
        rst = q1(f"SELECT status::text FROM run WHERE id={rid}")
        if rst in ("completed", "failed", "aborted"):
            break
        time.sleep(0.2)

    # 断言：**语义要求**（实现「设备执行中真停」后也不该变），
    # 而不是「当前行为」（设备指令最终 completed —— 那条会随真停实现而变，
    # 放进门禁就会假红）。设备指令的最终状态只作信息输出。
    #
    # ⚠ 只断言**实验级的两层**（experiment_run / experiment）：
    #   workflow 级的 `run` 是**另一条独立执行** —— 它跑完就是 completed，
    #   不受实验级暂停影响（这正是设计：实验暂停 ≠ 底层 workflow run 暂停）。
    #   第一版把三层一起断言 ⇒ workflow run 一旦先收尾就假红。
    er_st = q1(f"SELECT status::text FROM experiment_run WHERE id={erid}")
    ex_st = q1(f"SELECT status FROM experiment WHERE id={eid}")
    F.ok("实验在设备执行中暂停后，实验级两层都变 paused"
         "（experiment_run / experiment）") \
        if (er_st == "paused" and ex_st == "paused") \
        else F.bad("实验级两层状态不一致",
                   "experiment_run=%s experiment=%s" % (er_st, ex_st))
    F.ok("暂停在 5 秒内生效（%.1f 秒）" % (t_paused - t_pause)) \
        if t_paused and (t_paused - t_pause) < 5.0 \
        else F.bad("暂停生效太慢或没生效", str(t_paused))
    run_st = q1(f"SELECT status::text FROM run WHERE id={rid}")
    F.ok("workflow 级 run 独立跑完（不受实验级暂停影响）：%s" % run_st) \
        if run_st in ("completed", "failed", "aborted") \
        else F.bad("workflow 级 run 状态异常", run_st)

    print()
    print("  结果：")
    print("    run（workflow 级）      : %s" % run_st)
    print("    experiment_run（实验级） : %s" % er_st)
    print("    experiment（实验本身）   : %s" % ex_st)
    print("    设备指令最终状态        : %s" % q1(
        f"SELECT status::text FROM run_command WHERE run_id={rid} ORDER BY id DESC LIMIT 1"))
    print("    节点状态                : %s" % q1(
        f"SELECT node_key||'='||status::text FROM run_node WHERE run_id={rid} AND level=0"))
    print("    现场扣留记录            : %s" % q1(
        "SELECT scope||'/'||kind||'/'||coalesce(reason,'') FROM runtime_hold "
        "WHERE released_at IS NULL ORDER BY id DESC LIMIT 1"))
    if t_paused:
        print("    暂停生效延迟            : %.1f 秒" % (t_paused - t_pause))
    else:
        print("    暂停生效延迟            : 未观测到状态变 paused")
    cmd_st = q1(f"SELECT status::text FROM run_command WHERE run_id={rid} "
                f"ORDER BY id DESC LIMIT 1")
    if cmd_st == "completed":
        print("    ⇒ 设备方法**跑完了**：暂停没有打断正在执行的设备动作")
    elif cmd_st in ("aborted", "timeout", "failed"):
        print("    ⇒ 设备方法被中断（%s）" % cmd_st)
    else:
        print("    ⇒ 设备指令状态 %s" % cmd_st)

    # 8) 时间线（保留现场，便于定位覆盖发生在哪一步）
    print()
    print("  时间线：")
    print("    experiment_run : %s" % q1(
        f"SELECT id||' status='||status||' started='||coalesce(started_at::text,'-')"
        f"||' paused='||coalesce(paused_at::text,'-')"
        f"||' finished='||coalesce(finished_at::text,'-')"
        f"||' resumed='||coalesce(resumed_at::text,'-') FROM experiment_run WHERE id={erid}"))
    print("    run           : %s" % q1(
        f"SELECT id||' status='||status||' started='||coalesce(actual_start::text,'-')"
        f"||' finished='||coalesce(actual_end::text,'-') FROM run WHERE id={rid}"))
    print("    experiment    : %s" % q1(
        f"SELECT id||' status='||status||' started='||coalesce(started_at::text,'-')"
        f"||' finished='||coalesce(finished_at::text,'-') FROM experiment WHERE id={eid}"))
    print("    保留现场：experiment=%d workflow=%d run=%d experiment_run=%d"
          % (eid, wf_id, rid, erid))

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
        cleanup()
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
