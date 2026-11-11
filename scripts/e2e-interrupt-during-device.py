#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""4.18 暂停安全边界：驱动没有物理 Abort 时，暂停只能等当前动作跑到安全边界。

背景（B-02，2026-11-09）：
  这条安全语义有两层，**不能混为一谈**：

    ① 方法能不能被打断  → `device_method.interruptible`（工艺安全，配置）
    ② 驱动能不能让设备真停 → `DeviceDriver.supportsPhysicalAbort()`（物理能力，HIL）

  改动前只要 ① 为真，内核就会 `driver.abort()`，而 TCP 驱动的实现是「把本地
  等待中的 Future 摘掉、塞一个『被中止』」。看上去很干净：节点记 aborted、
  界面显示已停 —— 但**设备从来没收到停止指令，动作还在继续**；恢复时那一步
  因为不是 completed 会被重跑，于是同一条物理动作执行两次。

  现在的契约：TCP 协议没有停止帧 ⇒ `supportsPhysicalAbort()` 恒 false ⇒
  只能等当前动作自然跑完，然后在**下一个任务边界**暂停。

本脚本验的正是这条边界（故意把 Home 标成 interruptible=true，证明配置本身
不能越过驱动能力门槛）：
  · 当前设备动作**自然完成**（completed），而不是被伪报成 aborted
  · 暂停落在**下一个 task 边界**（ARM completed / AFTER 未执行）
  · **不重复派发**（命令数仍是 1）
  · 清理必须以「设备确认不在运行」为前提

「声明可中断 + 驱动具备物理 Abort ⇒ 命令被真中止」这条正向路径当前环境
无法验证（没有任何驱动做过物理 Abort 的 HIL）⇒ 归 P-12 真机验证，不在此段假装通过。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \\
  python3 scripts/e2e-interrupt-during-device.py --api http://127.0.0.1:18080

  > **⚠ 已随旧基线退役（2026-11-11）**：本脚本针对的是**旧基线**
  > （三十座岛 + 一千多条流程的累积数据）。新基线重建后**不再纳入门禁**
  > （现行分段见 [测试总纲](docs/测试总纲.md) §2.2）。
  >
  > **为什么保留而不是删掉**：里面沉淀的是**页面交互的断言写法**（Playwright 操作、
  > DOM 选择器、断言模式）—— 重写页面轨时这些是最值钱的资产，删了等于重写。
  >
  > **重写时按新基线改**：岛用 `LAB-BASE-01`（或从接口动态取，别写死）；
  > 设备用基线 5 台（设备ID 200 / 229 / 231 / 413 / 414，
  > 能力 读板机 / 孵育箱 / 机器人 / 封膜机 / 离心机）。
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

    print("══ 4.18 暂停安全边界 · 驱动无物理 Abort（当前动作跑完，下一 task 才停）══")

    # ① 临时给机械臂的 Home 方法标上「可中断」。
    #    ⚠ 设备库里这台设备登记的方法名是「液体」，与实际下发的 Home 对不上
    #    （演示岛设备库不完整）—— 这里补一条真实方法名的行。
    #    现场清单到位后，这条由设备库本身维护，脚本不再需要临时插。
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
    # 产品写入口：不再用 SQL 直接改，走真实 API，和页面/设备库同一路径。
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
        # ② 建流程：两个设备任务（ARM → AFTER）。
        #    第二个节点是「暂停边界」的观察点 —— 只有一个节点时，"停在下一边界"
        #    和"跑完了"在库上长得一样，验不出来。
        r = F.api("/api/workflows", "POST",
                  {"name": "E2E-安全边界-%d" % int(time.time()), "category": "choreography"},
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
    <bpmn:task id="ARM" name="当前动作" protocolName="{ARM_METHOD}" resource="{ARM_RESOURCE}">
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
                         f"pause_reason='e2e 安全边界：驱动无物理 Abort', pause_node_key='ARM', "
                         f"paused_at=now(), updated_at=now() WHERE id={rid}")
        m = re.search(r"(\d+)\s*$", (paused_rows or "").strip())
        n_rows = int(m.group(1)) if m else 0
        F.ok("已在设备执行中发出暂停（UPDATE 影响 %d 行）" % n_rows) if n_rows >= 1 \
            else F.bad("暂停 UPDATE 影响 0 行 —— 暂停没发出去")
        t_pause = time.time()

        # ⑥ 等命令落终态。关键：**不能**在 10 秒内落，因为设备侧根本没收到停止指令
        #    （如果很快就 aborted，说明又回到"本地取消冒充设备停止"的老路）。
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
        node_st = q1(f"SELECT string_agg(node_key||'='||status::text, ',' ORDER BY id) "
                     f"FROM run_node WHERE run_id={rid} AND level=0")
        run_st = q1(f"SELECT status::text FROM run WHERE id={rid}")
        cmd_count = int(q1(f"SELECT count(*) FROM run_command WHERE run_id={rid}") or "0")
        # B-04：中止结果账本。驱动没有物理 Abort ⇒ 这条分支根本不会进入 ⇒
        # abort_state 必须为空。一旦这里出现 REQUESTED/UNKNOWN 却把命令记成
        # aborted，就是又回到了「本地取消冒充设备已停」。
        abort_state = q1(f"SELECT coalesce(abort_state,'-') FROM command_ledger "
                         f"WHERE run_id={rid} ORDER BY id DESC LIMIT 1")
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
        print("    中止结果(账本)    : %s" % abort_state)
        if t_done:
            print("    从发暂停到命令结束: %.1f 秒（等设备自己跑完）" % (t_done - t_pause))
        else:
            print("    从发暂停到命令结束: 未观测到命令落终态")

        # ⑦ 断言：安全边界语义
        F.ok("当前设备动作自然完成（%s）" % cmd_st) \
            if cmd_st and cmd_st.split("|")[0] == "completed" \
            else F.bad("当前设备动作未自然完成", cmd_st or "(无)")
        F.ok("没有把本地取消伪装成 aborted") \
            if cmd_st and cmd_st.split("|")[0] != "aborted" \
            else F.bad("不支持物理 Abort 的驱动却报告 aborted（本地取消冒充设备停止）",
                       cmd_st or "(无)")
        F.ok("暂停落在下一个 task 边界（run 停在 paused）") \
            if run_st == "paused" else F.bad("run 未停在 paused", run_st or "(无)")
        F.ok("当前节点完成、后续节点未执行") \
            if arm_st == "completed" and after_st != "completed" \
            else F.bad("节点边界状态不符合预期", "%s / %s" % (arm_st, after_st))
        F.ok("没有重放或额外派发命令（仅 1 条）") \
            if cmd_count == 1 else F.bad("命令数不是 1，可能发生重复派发", str(cmd_count))
        F.ok("没有把中止请求记成已停（abort_state 为空）") \
            if abort_state in (None, "", "-") \
            else F.bad("无物理中止能力的驱动却写了 abort_state", abort_state)
        # 注：若哪天有驱动真的做了物理 Abort 的 HIL（supportsPhysicalAbort=true），
        # 这一段必须改写成「真停」分支（命令在数秒内 aborted）。届时 MIN_INTR
        # 只能上调；不允许把「安全边界」当成「真停」的替代品悄悄留下。
    finally:
        # 清理前提：设备和命令都确认不在运行。否则保留现场 —— 这条比"跑完就清库"重要：
        # 把还在动的设备记录删掉，等于把可核查的现场消灭掉。
        final_device_state = device_state()
        final_cmd = q1(f"SELECT coalesce(status::text,'-') FROM run_command "
                       f"WHERE run_id={rid} ORDER BY id DESC LIMIT 1") if rid else None
        safe_to_cleanup = (final_device_state[0] != "running"
                           and final_device_state[2] != str(rid)
                           and final_cmd not in ("running", "pending", "unknown"))
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
        F.ok("清理前已确认设备不在运行") if safe_to_cleanup \
            else F.bad("设备/命令未确认停止，拒绝清理", str(final_device_state))
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
        print("  （可中断标记已还原）")
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
