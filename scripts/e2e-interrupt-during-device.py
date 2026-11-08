#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A1 真停：设备方法执行中发暂停 → 这次真的被停掉。

背景（2026-11-06，用户诉求）：
  「设备正在执行的方法会继续，只有机械臂收到暂停指令后会当前停止，
    你看看这个怎么实现」
  方案 A1：**方法自己声明能不能中断**（`device_method.interruptible`），
  执行侧在等回包时轮询「是否该停」，命中就 `driver.abort(commandId)`。

这个脚本验的正是「真停」：
  · 设备方法**声明可中断**（本脚本临时把机械臂的 Home 标成 interruptible=true）
  · 设备方法正在执行时发暂停
  · ⇒ 命令**在几秒内被中止**（而不是等 30–300 秒跑完）

对照：`scripts/e2e-pause-during-device.py` 验的是「不声明可中断」时的行为
（暂停生效，但设备动作跑完）。

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
    orig_intr = "false"
    if had_row:
        orig_intr = (q1("SELECT interruptible::text FROM device_method "
                        "WHERE tenant_id=1 AND device_id=%d AND method='%s'"
                        % (ARM_DEVICE, ARM_METHOD)) or "false")
    q1("INSERT INTO device_method (tenant_id, device_id, method, name, capability_id, "
       "param_schema, estimate_sec, timeout_sec, interruptible) "
       "VALUES (1, %d, '%s', '归位(Home)', "
       "(SELECT id FROM capability WHERE code='TRANSFER' LIMIT 1), "
       "'{}'::jsonb, 10, 600, false) "
       "ON CONFLICT (device_id, method) DO NOTHING"
       % (ARM_DEVICE, ARM_METHOD))
    # 产品写入口（S-04 补）：不再用 SQL 直接改，走真实 API，和页面/设备库同一路径。
    try:
        api_res = F.api("/api/devices/%d/methods/%s/interruptible"
                        % (ARM_DEVICE, ARM_METHOD),
                        "PUT", {"interruptible": True}, token)
        F.ok("产品写入口 PUT /api/devices/{id}/methods/{method}/interruptible 可用") \
            if api_res and api_res.get("interruptible") is True \
            else F.bad("产品写入口返回不对", str(api_res)[:200])
    except Exception as e:  # noqa: BLE001
        F.bad("产品写入口不可用", str(e)[:200])
    print("  ✓ 已请求把 %s 的 %s 标成可中断（产品 API；%s）"
          % (ARM_DEVICE, ARM_METHOD, "原有行，结束后还原原值" if had_row else "临时插行，结束后删除"))

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
    <bpmn:task id="ARM" name="机械臂搬运" protocolName="{ARM_METHOD}" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow1</bpmn:incoming>
      <bpmn:outgoing>flow2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="E"><bpmn:incoming>flow2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="flow1" sourceRef="S" targetRef="ARM"/>
    <bpmn:sequenceFlow id="flow2" sourceRef="ARM" targetRef="E"/>
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

        # ⑥ 等「命令被中止」—— 关键：必须**远快于**设备本身要跑的 30–300 秒
        t_abort = None
        deadline = time.time() + 60
        while time.time() < deadline:
            cmd_st = q1(f"SELECT coalesce(status::text,'-')||'|'||"
                        f"coalesce(error_code,'-') FROM run_command "
                        f"WHERE run_id={rid} ORDER BY id DESC LIMIT 1")
            if cmd_st and cmd_st.split("|")[0] in ("aborted", "failed", "completed"):
                t_abort = time.time()
                break
            time.sleep(0.1)
        cmd_st = q1(f"SELECT coalesce(status::text,'-')||'|'||coalesce(error_code,'-') "
                    f"FROM run_command WHERE run_id={rid} ORDER BY id DESC LIMIT 1")
        node_st = q1(f"SELECT node_key||'='||status::text FROM run_node "
                     f"WHERE run_id={rid} AND level=0 ORDER BY id")
        run_st = q1(f"SELECT status::text FROM run WHERE id={rid}")
        print()
        print("  结果：")
        print("    run 最终状态      : %s" % run_st)
        print("    设备指令最终状态  : %s" % cmd_st)
        print("    节点状态          : %s" % node_st)
        if t_abort:
            print("    从发暂停到命令结束: %.1f 秒" % (t_abort - t_pause))
        else:
            print("    从发暂停到命令结束: 未观测到命令落终态")

        # ⑦ 断言
        F.ok("命令在 10 秒内落了终态（%.1f 秒）—— 没等设备跑完" % (t_abort - t_pause)) \
            if t_abort and (t_abort - t_pause) < 10.0 \
            else F.bad("命令没有及时落终态（可能还在等设备跑完）",
                       "%.1f 秒" % ((t_abort - t_pause) if t_abort else -1))
        F.ok("设备指令被中止（%s）" % cmd_st) \
            if cmd_st and cmd_st.split("|")[0] == "aborted" \
            else F.bad("设备指令不是被中止的", cmd_st or "(无)")
        F.ok("run 停在 paused（等人工继续；恢复时这一步会重跑）") \
            if run_st == "paused" else F.bad("run 状态异常", run_st or "(无)")
    finally:
        # 清理：先删 run，再删 workflow；最后把临时的可中断标记撤掉
        if rid:
            # 先释放这把设备锁（删 run ⇒ 锁成孤儿 ⇒ 设备永远拿不到）
            q1("UPDATE device_state SET current_run_id=NULL, current_method=NULL, "
               "status='idle', updated_at=now() "
               f"WHERE tenant_id=1 AND current_run_id={rid};")
            q1(f"DELETE FROM run_command WHERE run_id={rid}; "
               f"DELETE FROM run_node WHERE run_id={rid}; DELETE FROM run WHERE id={rid};")
        if wf_id:
            q1(f"DELETE FROM workflow_node WHERE workflow_id={wf_id}; "
               f"DELETE FROM workflow WHERE id={wf_id};")
        # 走产品 API 还原（与设置同一条路径）；API 不可用时退回 SQL，保证现场可清理
        try:
            restore = ("true" if had_row and orig_intr == "true" else "false")
            F.api("/api/devices/%d/methods/%s/interruptible"
                  % (ARM_DEVICE, ARM_METHOD),
                  "PUT", {"interruptible": restore == "true"}, token)
        except Exception:  # noqa: BLE001
            if had_row:
                q1("UPDATE device_method SET interruptible = %s "
                   "WHERE tenant_id=1 AND device_id=%d AND method='%s'"
                   % (orig_intr, ARM_DEVICE, ARM_METHOD))
        if not had_row:
            q1(f"DELETE FROM device_method WHERE tenant_id=1 AND device_id={ARM_DEVICE} "
               f"AND method='{ARM_METHOD}' AND name='归位(Home)';")
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
        print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
        sys.exit(1)
