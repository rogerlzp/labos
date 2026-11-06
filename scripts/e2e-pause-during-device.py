#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""长耗时设备执行中发暂停 —— 实测「设备会不会被打断」。

背景（2026-11-06 用户提问）：
  "大部分时候暂停命令发出去，但是设备正在执行的方法会继续，
   只有机械臂收到暂停指令后会当前停止，你看看这个怎么实现"

这个脚本把那条现象**跑出来并量化**：
  · 设备方法**真的在执行**时发暂停
  · 看暂停多久生效（pause 后 run 变 paused 的时刻）
  · 看设备指令最终是 completed 还是 aborted/timeout
  · 看机械臂（TRANSFER）与普通设备行为是否不同

⚠ 判据修正（2026-11-06，第 1 版踩的坑）：
  **run_command 有行 ≠ 设备正在执行**。
  `WorkflowService` 的 run_command 是**步结束后**才 INSERT 的
  （status 直接写 completed/failed，sent_at/finished_at 都是 now()），
  它是「这一步跑完了」的证据。第 1 版拿它当「指令刚发出去」的信号，
  于是**暂停发出去时设备其实早就跑完了** —— 测的不是「执行中暂停」。
  ⇒ 判断「设备正在执行」必须看 **device_state**：
  `markDeviceBusy` 在 onStepStart 置 status='running' + current_method，
  `markDeviceIdle` 在 onStepEnd 复位 —— 那才是下发时刻。

设备耗时用模拟器的 --rand-delay（10–18 秒），够长到「暂停信号能在动作中间到达」，
又不会让测试等太久。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-pause-during-device.py --api http://127.0.0.1:18080
"""
import argparse
import importlib.util
import os
import re
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
ARM_RESOURCE = 200214
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


def device_state():
    """设备此刻的执行状态（DB 侧，下发时刻的信号）。"""
    row = q1("SELECT coalesce(status,'-')||'|'||coalesce(current_method,'-')||'|'||"
             "coalesce(current_run_id::text,'-') FROM device_state "
             "WHERE tenant_id=1 AND resource_id=%d" % ARM_RESOURCE)
    if not row:
        return ("-", "-", "-")
    parts = (row + "||").split("|")
    return (parts[0], parts[1], parts[2])


# 本次造出来的对象（供清理用）。⚠ 失败/异常路径也必须清理 ——
# 残留的 run 留在库里会让别的脚本的 max(id) 抓到它们（2026-11-06 踩过）。
MADE = {"wf": 0, "run": 0}


def cleanup():
    """清理现场。顺序要紧：run 先删，否则 workflow 删不掉（FK）。"""
    rid, wf_id = MADE["run"], MADE["wf"]
    try:
        if rid:
            F.q(f"DELETE FROM run_command WHERE run_id={rid}; "
                f"DELETE FROM run_node WHERE run_id={rid}; DELETE FROM run WHERE id={rid};")
        if wf_id:
            F.q(f"DELETE FROM workflow_node WHERE workflow_id={wf_id}; "
                f"DELETE FROM workflow WHERE id={wf_id};")
    except Exception as e:  # noqa: BLE001
        print("  （清理时出错：%s）" % e)


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
    MADE["wf"] = wf_id
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
    # ⚠ F.api 会把 body 做 JSON 序列化 —— 图是 **XML**，必须自己发原始 body
    put_raw(f"/api/workflows/{wf_id}/graph", xml, token)

    # 2) 触发执行
    r = F.api(f"/api/workflows/{wf_id}/run?async=1", "POST", {}, token)
    rid = int(r.get("runId")) if isinstance(r, dict) and r.get("runId") else 0
    if not rid:
        # 兜底也**按 workflow_id 定位**，不用 max(id)（会抓到别的测试的 run）
        rows = q1(f"SELECT COALESCE(max(id),0) FROM run WHERE workflow_id={wf_id}")
        rid = int(rows or 0)
    if not rid:
        F.bad("触发执行失败")
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
    MADE["run"] = rid
    print("  触发执行 run #%d" % rid)

    # 3) 等设备方法**真的在执行**（device_state，不是 run_command —— 那是步结束才落的库）
    t_cmd = None
    busy = None
    deadline = time.time() + 120
    while time.time() < deadline:
        st, meth, owner = device_state()
        if st == "running" and meth != "-" and owner in ("-", str(rid)):
            busy = (st, meth, owner)
            t_cmd = time.time()
            break
        time.sleep(0.1)
    if not t_cmd:
        F.bad("没观测到设备方法执行中", str(device_state()))
        cleanup()
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
    F.ok("观测到设备方法执行中（device_state status=%s current_method=%s，归属 run #%s）"
         % (busy[0], busy[1], busy[2]))
    print("  ✓ 设备方法执行中（device_state: %s / %s / run %s）" % busy)

    # 4) **在设备执行中发暂停**
    #    ⚠ 不能带 `AND status='running'`：那一刻 run 可能已不是 running
    #    （异步提交时先 queued，被调度后才 running），条件不匹配就是**静默 0 行更新** ——
    #    暂停压根没发出去，却会让人以为「暂停对设备无效」。
    #    所以：去掉状态条件，并**检查影响行数** —— 0 行就必须报失败。
    paused_rows = q1(f"UPDATE run SET status='paused', pause_kind='manual', "
                     f"pause_reason='e2e 长耗时设备执行中暂停', pause_node_key='ARM', "
                     f"paused_at=now(), updated_at=now() WHERE id={rid}")
    # psql -c "UPDATE ..." 打印的是 "UPDATE N"，取末尾的数字
    m = re.search(r"(\d+)\s*$", (paused_rows or "").strip())
    n_rows = int(m.group(1)) if m else 0
    if n_rows < 1:
        F.bad("暂停 UPDATE 影响 0 行 —— 暂停根本没发出去（run id=%s）" % rid)
        cleanup()
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
    F.ok("已在设备执行中发出暂停（UPDATE 影响 %d 行）" % n_rows)
    t_pause = time.time()

    # 5) 观察：暂停什么时候真正生效（run 状态、节点状态）
    t_paused = None
    deadline = time.time() + 180
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

    # 6) 断言（语义要求，不钉「当前行为」）
    F.ok("暂停在 5 秒内生效（%.1f 秒）" % (t_paused - t_pause)) \
        if t_paused and (t_paused - t_pause) < 5.0 \
        else F.bad("暂停生效太慢或没生效", str(t_paused))
    F.ok("设备方法有终态记录（%s）—— 暂停没有让设备动作凭空消失" % (cmd_st or "(无)")) \
        if cmd_st in ("completed", "failed", "aborted", "timeout") \
        else F.bad("设备指令没有终态记录（可能卡在执行中）", str(cmd_st))

    # 7) 结论（当前行为：设备动作**不会被**暂停打断）
    print()
    if cmd_st == "completed":
        print("  ⇒ 设备指令**跑完了**（completed）：暂停没有中断正在执行的设备动作")
    elif cmd_st in ("timeout", "failed", "aborted"):
        print("  ⇒ 设备指令**被中断**（%s）：执行中的设备动作被停掉了" % cmd_st)
    else:
        print("  ⇒ 设备指令状态 %s（未跑完也未明确中断）" % cmd_st)

    # 8) 清理
    cleanup()
    print("  （测试数据已清理）")
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
