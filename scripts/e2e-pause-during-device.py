#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""长耗时设备执行中发暂停 —— 实测「设备会不会被打断」。

背景（2026-11-06 用户提问）：
  "大部分时候暂停命令发出去，但是设备正在执行的方法会继续，
   只有机械臂收到暂停指令后会当前停止，你看看这个怎么实现"

这个脚本把那条现象**跑出来并量化**：
  · 设备节点正在执行（run_command 已发出、回包未到）时发暂停
  · 看暂停多久生效（pause 后 run 变 paused 的时刻）
  · 看设备指令最终是 completed 还是 aborted/timeout
  · 看机械臂（TRANSFER）与普通设备行为是否不同

设备耗时用模拟器的 --rand-delay（20–40 秒），够长到「暂停信号能在动作中间到达」，
又不会让测试等太久。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-pause-during-device.py --api http://127.0.0.1:18080
"""
import argparse
import importlib.util
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_flow_matrix", os.path.join(_HERE, "e2e-flow-matrix.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["e2e_flow_matrix"] = F
_spec.loader.exec_module(F)

# 机械臂设备（TRANSFER 能力）：ID1731 / device 214
ARM_DEVICE = 214
ARM_CODE = "ID1731"


def put_raw(path, xml_text, token):
    """PUT 一段原始 XML（图接口不是 JSON，通用 api() 会把它 JSON 序列化 → 400）。"""
    import urllib.request
    req = urllib.request.Request(F.BASE_API + path, data=xml_text.encode("utf-8"),
                                 method="PUT")
    req.add_header("Content-Type", "application/xml")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        raise RuntimeError("PUT %s → %s: %s" % (path, e.code, e.read().decode()[:300]))


def q1(sql):
    return F.q(sql)


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

    print("══ 长耗时设备执行中发暂停 ══")

    # 1) 造一条最小流程：开始 → 机械臂节点 → 结束
    #    机械臂节点绑 ID1731 的方法（Home/Transfer 之类），走真设备。
    r = F.api("/api/workflows", "POST",
              {"name": "E2E-长耗时暂停-%d" % int(time.time()), "category": "choreography"},
              token)
    wf_id = int(r["id"])
    print("  建流程 #%d" % wf_id)

    # 用 XML 直接建（页面拖太慢，这里要的是执行语义不是页面交互）
    # 开始 → 机械臂节点(绑 ID1731) → 结束
    xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="defs_{wf_id}" targetNamespace="http://labos.local/bpmn">
  <bpmn:process id="p_{wf_id}" isExecutable="true">
    <bpmn:startEvent id="S"><bpmn:outgoing>flow1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="ARM" name="机械臂搬运" protocolName="Home" resource="200214">
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
    # ⚠ F.api 会把 body 做 JSON 序列化 —— 图是 **XML**，必须自己发原始 body
    put_raw(f"/api/workflows/{wf_id}/graph", xml, token)

    # 2) 触发执行
    r = F.api(f"/api/workflows/{wf_id}/run?async=1", "POST", {}, token)
    rid = int(r.get("runId")) if isinstance(r, dict) and r.get("runId") else 0
    if not rid:
        print("  ✗ 触发执行失败")
        sys.exit(1)
    print("  触发执行 run #%d" % rid)

    # 3) 等设备指令**真的发出去了**（run_command 有行 = 指令已下发、设备正在执行）
    t_cmd = None
    deadline = time.time() + 40
    while time.time() < deadline:
        n = q1(f"SELECT count(*) FROM run_command WHERE run_id={rid}")
        if n and int(n) >= 1:
            t_cmd = time.time()
            break
        time.sleep(0.05)
    if not t_cmd:
        print("  ✗ 设备指令一直没发出去")
        F.q(f"DELETE FROM run_command WHERE run_id={rid}; "
            f"DELETE FROM run_node WHERE run_id={rid}; DELETE FROM run WHERE id={rid};")
        sys.exit(1)
    print("  ✓ 设备指令已发出（设备正在执行）")

    # 4) **在设备执行中发暂停**
    q1(f"UPDATE run SET status='paused', pause_kind='manual', "
       f"pause_reason='e2e 长耗时设备执行中暂停', pause_node_key='ARM', "
       f"paused_at=now(), updated_at=now() WHERE id={rid} AND status='running'")
    t_pause = time.time()
    print("  ✓ 已在设备执行中发出暂停")

    # 5) 观察：暂停什么时候真正生效（run 状态、节点状态）
    t_paused = None
    deadline = time.time() + 120
    while time.time() < deadline:
        st = q1(f"SELECT status::text FROM run WHERE id={rid}")
        if st == "paused" and t_paused is None:
            t_paused = time.time()
        cmd_st = q1(f"SELECT status::text FROM run_command WHERE run_id={rid} "
                    f"ORDER BY id DESC LIMIT 1")
        if cmd_st and cmd_st in ("completed", "failed", "aborted", "timeout"):
            break
        if st in ("completed", "failed", "aborted"):
            break
        time.sleep(0.2)

    run_st = q1(f"SELECT status::text FROM run WHERE id={rid}")
    cmd_st = q1(f"SELECT status::text FROM run_command WHERE run_id={rid} "
                f"ORDER BY id DESC LIMIT 1")
    node_st = q1(f"SELECT node_key||'='||status::text FROM run_node "
                 f"WHERE run_id={rid} AND level=0 ORDER BY id")
    print()
    print("  结果：")
    print("    run 最终状态      : %s" % run_st)
    print("    设备指令最终状态  : %s" % cmd_st)
    print("    节点状态          : %s" % node_st)
    # 关键：指令为什么 failed —— 超时(EC-06 timeout) 还是被中断(EC-99-0005 被中止)
    cmd_detail = q1(f"SELECT coalesce(error_code,'(无)')||' | sent='||"
                    f"coalesce(sent_at::text,'-')||' | finished='||"
                    f"coalesce(finished_at::text,'-')||' | latency='||"
                    f"coalesce(ack_latency_ms::text,'-') FROM run_command "
                    f"WHERE run_id={rid} ORDER BY id DESC LIMIT 1")
    print("    指令明细          : %s" % cmd_detail)
    if t_paused:
        print("    暂停生效延迟      : %.1f 秒（从发暂停到 run 变 paused）"
              % (t_paused - t_pause))
    else:
        print("    暂停生效延迟      : 未观测到 run 变 paused（它直接跑到了终态）")

    # 6) 结论
    print()
    if cmd_st == "completed":
        print("  ⇒ 设备指令**跑完了**（completed）：暂停没有中断正在执行的设备动作")
    elif cmd_st in ("timeout", "failed", "aborted"):
        print("  ⇒ 设备指令**被中断**（%s）：执行中的设备动作被停掉了" % cmd_st)
    else:
        print("  ⇒ 设备指令状态 %s（未跑完也未明确中断）" % cmd_st)

    # 7) 清理
    F.q(f"DELETE FROM run_command WHERE run_id={rid}; "
        f"DELETE FROM run_node WHERE run_id={rid}; DELETE FROM run WHERE id={rid};")
    F.q(f"DELETE FROM workflow_node WHERE workflow_id={wf_id}; "
        f"DELETE FROM workflow WHERE id={wf_id};")
    print("  （测试数据已清理）")


if __name__ == "__main__":
    main()
