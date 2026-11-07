#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设备执行中请求暂停：不支持物理 Abort 时，当前动作完成后在下一任务边界暂停。

安全约束：
  · `device_method.interruptible=true` 只是方法配置，不代表驱动有物理停止能力。
  · 当前 TCP 驱动协议没有设备侧 Abort 消息，不能取消本地 Future 冒充设备已停止。
  · 因此当前动作必须自然完成；随后 GraphRunner 在下一个 task 边界暂停。
  · 该测试同时确认前一节点不会因 resume 语义而被重复派发。

本脚本故意把 Home 标成 interruptible=true，以证明配置本身不能越过驱动能力门槛。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-interrupt-during-device.py --api http://127.0.0.1:18080
"""
import argparse
import importlib.util
import os
import re
import sys
import time
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_flow_matrix", os.path.join(_HERE, "e2e-flow-matrix.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["e2e_flow_matrix"] = F
_spec.loader.exec_module(F)

ARM_DEVICE = 214          # device_instance.id（ID1731 机械臂）
ARM_RESOURCE = 200214     # resource_node.id
ARM_METHOD = "Home"


def q1(sql):
    return F.q(sql)


def put_raw(path, xml_text, token):
    req = urllib.request.Request(F.BASE_API + path, data=xml_text.encode("utf-8"),
                                 method="PUT")
    req.add_header("Content-Type", "application/xml")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        raise RuntimeError("PUT %s → %s: %s" % (path, e.code, e.read().decode()[:300]))


def device_state():
    row = q1("SELECT coalesce(status,'-')||'|'||coalesce(current_method,'-')||'|'||"
             "coalesce(current_run_id::text,'-') FROM device_state "
             "WHERE tenant_id=1 AND resource_id=%d" % ARM_RESOURCE)
    if not row:
        return ("-", "-", "-")
    parts = (row + "||").split("|")
    return (parts[0], parts[1], parts[2])


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

    print("══ A1 真停 · 可中断方法执行中发暂停 ══")

    # ① 临时给机械臂的 Home 方法标上「可中断」。
    #    ⚠ 设备库里这台设备登记的方法名是「液体」，与实际下发的 Home 对不上
    #    （演示岛设备库不完整）—— 这里补一条真实方法名的行。
    #    现场清单到位后，这条由设备库本身维护，脚本不再需要临时插。
    # 原来有没有这一行？（有就只改标志位，结束时要还原；没有才临时插，结束删掉）
    existed = q1("SELECT count(*) FROM device_method "
                 "WHERE tenant_id=1 AND device_id=%d AND method='%s'"
                 % (ARM_DEVICE, ARM_METHOD))
    had_row = existed == "1"
    q1("INSERT INTO device_method (tenant_id, device_id, method, name, capability_id, "
       "param_schema, estimate_sec, timeout_sec, interruptible) "
       "VALUES (1, %d, '%s', '归位(Home)', "
       "(SELECT id FROM capability WHERE code='TRANSFER' LIMIT 1), "
       "'{}'::jsonb, 10, 600, true) "
       "ON CONFLICT (device_id, method) DO UPDATE SET interruptible = true"
       % (ARM_DEVICE, ARM_METHOD))
    print("  ✓ 已把 %s 的 %s 标成可中断（interruptible=true，%s）"
          % (ARM_DEVICE, ARM_METHOD, "原有行，结束后还原" if had_row else "临时行，结束后删除"))

    wf_id = 0
    rid = 0
    try:
        # ② 建流程（机械臂节点）
        r = F.api("/api/workflows", "POST",
                  {"name": "E2E-真停-%d" % int(time.time()), "category": "choreography"},
                  token)
        wf_id = int(r["id"])
        xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="defs_{wf_id}" targetNamespace="http://labos.local/bpmn">
  <bpmn:process id="p_{wf_id}" isExecutable="true">
    <bpmn:startEvent id="S"><bpmn:outgoing>flow1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="ARM" name="机械臂动作（当前动作）" protocolName="{ARM_METHOD}" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow1</bpmn:incoming>
      <bpmn:outgoing>flow2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:task id="AFTER" name="暂停后的下一动作" protocolName="{ARM_METHOD}" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow2</bpmn:incoming>
      <bpmn:outgoing>flow3</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="E"><bpmn:incoming>flow3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="flow1" sourceRef="S" targetRef="ARM"/>
    <bpmn:sequenceFlow id="flow2" sourceRef="ARM" targetRef="AFTER"/>
    <bpmn:sequenceFlow id="flow3" sourceRef="AFTER" targetRef="E"/>
  </bpmn:process>
</bpmn:definitions>'''
        put_raw(f"/api/workflows/{wf_id}/graph", xml, token)

        # ③ 触发执行
        r = F.api(f"/api/workflows/{wf_id}/run?async=1", "POST", {}, token)
        rid = int(r.get("runId")) if isinstance(r, dict) and r.get("runId") else 0
        if not rid:
            F.bad("触发执行失败")
            print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
            return
        print("  触发执行 run #%d" % rid)

        # ④ 等设备方法真的在执行（看 device_state，不看 run_command —— 那是步结束才落的库）
        deadline = time.time() + 120
        busy = None
        while time.time() < deadline:
            st, meth, owner = device_state()
            if st == "running" and meth != "-" and owner in ("-", str(rid)):
                busy = (st, meth, owner)
                break
            time.sleep(0.1)
        if not busy:
            F.bad("没观测到设备方法执行中", str(device_state()))
            print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
            return
        F.ok("观测到设备方法执行中（device_state %s / %s / run %s）" % busy)

        # ⑤ 发暂停（workflow 级：等价于页面上的暂停按钮）
        paused_rows = q1(f"UPDATE run SET status='paused', pause_kind='manual', "
                         f"pause_reason='e2e 真停：可中断方法执行中', pause_node_key='ARM', "
                         f"paused_at=now(), updated_at=now() WHERE id={rid}")
        m = re.search(r"(\d+)\s*$", (paused_rows or "").strip())
        n_rows = int(m.group(1)) if m else 0
        F.ok("已在设备执行中发出暂停（UPDATE 影响 %d 行）" % n_rows) if n_rows >= 1 \
            else F.bad("暂停 UPDATE 影响 0 行 —— 暂停没发出去")
        t_pause = time.time()

        # ⑥ 等当前命令自然完成；本 TCP driver 不支持设备侧 Abort，不能伪报 aborted。
        t_done = None
        deadline = time.time() + 180
        while time.time() < deadline:
            cmd_st = q1(f"SELECT coalesce(status::text,'-')||'|'||"
                        f"coalesce(error_code,'-') FROM run_command "
                        f"WHERE run_id={rid} ORDER BY id DESC LIMIT 1")
            if cmd_st and cmd_st.split("|")[0] in ("completed", "failed", "aborted"):
                t_done = time.time()
                break
            time.sleep(0.1)
        cmd_st = q1(f"SELECT coalesce(status::text,'-')||'|'||coalesce(error_code,'-') "
                    f"FROM run_command WHERE run_id={rid} ORDER BY id DESC LIMIT 1")
        node_st = q1(f"SELECT node_key||'='||status::text FROM run_node "
                     f"WHERE run_id={rid} AND level=0 ORDER BY id")
        run_st = q1(f"SELECT status::text FROM run WHERE id={rid}")
        cmd_count = int(q1(f"SELECT count(*) FROM run_command WHERE run_id={rid}") or "0")
        arm_st = q1(f"SELECT status::text FROM run_node WHERE run_id={rid} "
                    f"AND level=0 AND node_key='ARM' ORDER BY id LIMIT 1")
        after_st = q1(f"SELECT status::text FROM run_node WHERE run_id={rid} "
                      f"AND level=0 AND node_key='AFTER' ORDER BY id LIMIT 1")
        print()
        print("  结果：")
        print("    run 最终状态      : %s" % run_st)
        print("    设备指令最终状态  : %s" % cmd_st)
        print("    节点状态          : %s" % node_st)
        print("    ARM / AFTER       : %s / %s" % (arm_st, after_st))
        print("    命令数            : %d" % cmd_count)
        if t_done:
            print("    从发暂停到命令结束: %.1f 秒" % (t_done - t_pause))
        else:
            print("    从发暂停到命令结束: 未观测到命令落终态")

        # ⑦ 断言：当前动作成功完成；下一节点因暂停未执行；不伪报物理中止。
        F.ok("当前设备动作已自然完成（%s）" % cmd_st) \
            if cmd_st and cmd_st.split("|")[0] == "completed" \
            else F.bad("当前设备动作未成功完成", cmd_st or "(无)")
        F.ok("暂停后 run 停在下一个 task 边界") \
            if run_st == "paused" else F.bad("run 未停在 paused", run_st or "(无)")
        F.ok("当前节点完成、后续节点未执行") \
            if arm_st == "completed" and after_st != "completed" \
            else F.bad("节点边界状态不符合预期", "%s / %s" % (arm_st, after_st))
        F.ok("没有重放或额外派发命令（仅 1 条）") \
            if cmd_count == 1 else F.bad("命令数不是 1，可能发生重复派发", str(cmd_count))
        F.ok("没有把本地取消伪装成 aborted") \
            if cmd_st and cmd_st.split("|")[0] != "aborted" \
            else F.bad("不支持物理 Abort 的驱动却报告 aborted", cmd_st or "(无)")
    finally:
        # 只有设备和命令都确认不在运行，才允许清理测试记录/释放软件锁。
        final_device_state = device_state()
        final_cmd = q1(f"SELECT coalesce(status::text,'-') FROM run_command "
                       f"WHERE run_id={rid} ORDER BY id DESC LIMIT 1") if rid else None
        safe_to_cleanup = final_device_state[0] != "running" and final_cmd not in ("running", "pending")
        if safe_to_cleanup:
            if rid:
                q1("UPDATE device_state SET current_run_id=NULL, current_method=NULL, "
                   "status='idle', updated_at=now() "
                   f"WHERE tenant_id=1 AND current_run_id={rid};")
                q1(f"DELETE FROM run_command WHERE run_id={rid}; "
                   f"DELETE FROM run_node WHERE run_id={rid}; DELETE FROM run WHERE id={rid};")
            if wf_id:
                q1(f"DELETE FROM workflow_node WHERE workflow_id={wf_id}; "
                   f"DELETE FROM workflow WHERE id={wf_id};")
            print("  （设备已确认不在运行，测试数据已清理）")
        else:
            print("  ⚠ 设备/命令仍可能在运行：保留 run、命令和资源锁，必须人工核验后清理")
        if had_row:
            q1("UPDATE device_method SET interruptible = false "
               "WHERE tenant_id=1 AND device_id=%d AND method='%s'"
               % (ARM_DEVICE, ARM_METHOD))
        else:
            q1(f"DELETE FROM device_method WHERE tenant_id=1 AND device_id={ARM_DEVICE} "
               f"AND method='{ARM_METHOD}' AND name='归位(Home)';")
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
