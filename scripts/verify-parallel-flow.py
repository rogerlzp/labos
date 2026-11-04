#!/usr/bin/env python3
"""流程内并行分支端到端验证：parallel fork → 两条分支 → join。

验证三件事：
  1. 两条分支都执行（两个 task 都 completed）
  2. 汇合后才继续（整单 completed）
  3. 同一台设备被两条分支同时要时，DeviceLock 串行（命令时间段不重叠）

用法：
    export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
    python3 scripts/verify-parallel-flow.py
    python3 scripts/verify-parallel-flow.py --device 504830 --device2 504831 --method2 Seal

不传设备就自动挑一台**真能跑命令**的设备，并在它上挑两个能跑的方法；
`--device2` 用来验证"不同设备是否真并行"。
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_p" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_p" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:parallelGateway id="SPLIT" name="分叉"/>
    <bpmn:task id="T1" name="任务一" protocolName="%(m1)s" resource="%(d1)s"/>
    <bpmn:task id="T2" name="任务二" protocolName="%(m2)s" resource="%(d2)s"/>
    <bpmn:parallelGateway id="JOIN" name="汇合"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="SPLIT"/>
    <bpmn:sequenceFlow id="f2" sourceRef="SPLIT" targetRef="T1"/>
    <bpmn:sequenceFlow id="f3" sourceRef="SPLIT" targetRef="T2"/>
    <bpmn:sequenceFlow id="f4" sourceRef="T1" targetRef="JOIN"/>
    <bpmn:sequenceFlow id="f5" sourceRef="T2" targetRef="JOIN"/>
    <bpmn:sequenceFlow id="f6" sourceRef="JOIN" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_p">
    <bpmndi:BPMNPlane id="P_p" bpmnElement="Proc_p">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="SPLIT_di" bpmnElement="SPLIT"><dc:Bounds x="140" y="153" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="240" y="80" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="240" y="220" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="JOIN_di" bpmnElement="JOIN"><dc:Bounds x="390" y="153" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="490" y="160" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""

PASS, FAIL = 0, 0

# 探方法时按"有时长的动作类"优先：同设备串行断言要靠命令真的占一段时长，
# 查询类/无参放最后（它们可能瞬时完成，断言会变成永远成立的空断言）。
METHOD_CANDIDATES = ["Seal", "OpenDoor", "Grip", "Mix", "SetTemp", "Deposit1",
                     "Transfer", "Home", "GetStatus"]


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (f"（{detail}）" if detail else ""))


def api(path, method="GET", body=None, token=None, raw=None):
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
    with urllib.request.urlopen(req, data=data, timeout=60) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    # 口令只从 LABOS_DB_PASS 来：不设 PGPASSWORD 时 psql 会要交互口令，
    # 非 tty 下直接失败并返回空串 —— 会被当成"环境没设备"而静默跳过。
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    return out.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", type=int, default=0)
    ap.add_argument("--device2", type=int, default=0)
    ap.add_argument("--method1", default="")
    ap.add_argument("--method2", default="")
    args = ap.parse_args()

    for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"):
        if not os.environ.get(k):
            print(f"缺少环境变量 {k}（和 test-all.sh 一样，口令不写进脚本）")
            return 2

    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    def probe(rid, method):
        """单命令真下发一次：ok=True 才算"这台设备真的能跑这个方法"。"""
        try:
            r = api(f"/api/shadow/probe?resourceId={rid}&method={method}", "POST", {}, token)
            return bool(r.get("ok"))
        except Exception:  # noqa: BLE001
            return False

    def working_methods(rid):
        return [m for m in METHOD_CANDIDATES if probe(rid, m)]

    # 选设备：按"最近成功过命令 + 本地端点 + 库里顺序"排，逐台**真探**。
    #
    # 不要用 device_state.online 选：心跳只证明 TCP 连得上，不代表中控发下去的指令
    # 会被设备接受。实测环境里就有"心跳绿但驱动端口已关"（连接被拒）和"型号不支持
    # 该方法"（EC-06-0004）的设备，挑中它们跑出来全是 manual/failed ——
    # 看着像并发坏了，其实是选错了设备。
    def candidates():
        return [int(x) for x in q("""
            SELECT r.id FROM resource_node r
              JOIN device_instance di ON di.resource_id = r.id AND di.deleted_at IS NULL
              LEFT JOIN driver dr ON dr.id = di.driver_id AND dr.deleted_at IS NULL
             WHERE r.kind = 'device' AND r.tenant_id = 1 AND r.deleted_at IS NULL
             ORDER BY (SELECT count(*) FROM run_command c
                         JOIN run_node n ON n.id = c.run_node_id
                        WHERE n.resource_id = r.id AND c.status = 'completed'
                          AND c.finished_at > now() - interval '2 hours') DESC,
                      -- endpoint 为 NULL 的行 LIKE 出来是 NULL，DESC 会把它们排在最前，
                      -- 白占候选名额；用 coalesce 归成 false。
                      COALESCE(dr.endpoint LIKE 'tcp://127.0.0.1%', false) DESC,
                      r.id
             LIMIT 12""").split()]

    picked, ms = 0, []
    if args.device:
        ms = working_methods(args.device)
        if not ms:
            print(f"SKIP: 设备 {args.device} 对候选方法一个都不回 ok，无法验证流程内并行")
            return 0
        picked = args.device
    else:
        for rid in candidates():
            ms = working_methods(rid)
            if ms:
                picked = rid
                break
        if not picked:
            print("SKIP: 库里没有真能跑命令的设备（CI 没起模拟器？），无法验证流程内并行")
            return 0

    d1 = picked
    m1 = args.method1 if args.method1 in ms else ms[0]
    if args.method2 in ms and args.method2 != m1:
        m2 = args.method2
    else:
        m2 = next((m for m in ms if m != m1), m1)

    d2 = args.device2 or d1
    same_device = d1 == d2
    if not same_device:
        ms2 = working_methods(d2)
        if args.method2 in ms2:
            m2 = args.method2
        elif ms2:
            m2 = ms2[0]

    print(f"设备：T1={d1}（{m1}） T2={d2}（{m2}）"
          f"{'  [同一台]' if same_device else '  [不同设备]'}")

    wf = api("/api/workflows", "POST", {"name": "verify-并行分支"}, token)["id"]
    api(f"/api/workflows/{wf}/graph", "PUT",
        raw=XML % {"m1": m1, "d1": d1, "m2": m2, "d2": d2}, token=token)

    run = api(f"/api/workflows/{wf}/run?async=1", "POST", {}, token)
    rid = run.get("runId") or q("SELECT max(id) FROM run")
    for _ in range(60):
        if q(f"SELECT status FROM run WHERE id={rid}") in ("completed", "failed", "aborted", "paused"):
            break
        time.sleep(1)

    status = q(f"SELECT status FROM run WHERE id={rid}")
    nodes = q(f"SELECT node_key||' '||status FROM run_node WHERE run_id={rid} AND level=0 ORDER BY id")
    cmds = q(f"SELECT method||' | '||to_char(sent_at,'HH24:MI:SS.MS')||' → '"
             f"||to_char(finished_at,'HH24:MI:SS.MS')||' | '||status "
             f"FROM run_command WHERE run_id={rid} ORDER BY id")
    overlap = q(f"""SELECT count(*) FROM run_command a JOIN run_command b
                     ON a.run_id = b.run_id AND a.id < b.id
                    WHERE a.run_id = {rid}
                      AND a.sent_at < b.finished_at AND b.sent_at < a.finished_at""")

    print("\n节点：\n" + nodes)
    print("\n命令：\n" + cmds)

    if status == "completed":
        ok("整单 completed（汇合后正常结束）")
    else:
        bad("整单未完成", status)
    ok("两条分支都有节点记录") if "T1" in nodes and "T2" in nodes else bad("分支节点缺失", nodes)
    ok("两条分支都 completed") if "T1 completed" in nodes and "T2 completed" in nodes \
        else bad("有分支没完成", nodes.replace("\n", " | "))

    if same_device:
        ok("同一台设备：命令时间段不重叠（DeviceLock 串行）") if overlap == "0" \
            else bad("同一台设备的命令重叠了", overlap)
    else:
        print(f"  提示：不同设备，命令重叠对数 = {overlap}"
              "（>0 表示真并行；=0 也可能只是两条命令都瞬时完成）")

    print("\n--- 清理 ---")
    q(f"DELETE FROM run_command WHERE run_id={rid}; DELETE FROM run_node WHERE run_id={rid};")
    q(f"DELETE FROM run WHERE id={rid};")
    q(f"DELETE FROM workflow_node WHERE workflow_id={wf}; DELETE FROM workflow WHERE id={wf};")
    # 整单若停在 paused，会留下**岛级扣留**；不释放下一次在同一个岛排不到队（假红），
    # 而且调度器是全局闸门（RuntimeHold.anyActive()）—— 任何一条未释放的扣留会让
    # **所有岛**的新实验都派发不出去。
    # released_by 是 bigint，写字符串会 invalid input syntax 而静默失败（q() 只取 stdout）。
    q(f"UPDATE runtime_hold SET released_at=now(), released_by=coalesce(held_by, 1), "
      f"release_note='用例收尾复位' WHERE scope='island' AND released_at IS NULL "
      f"AND scope_id=(SELECT parent_id FROM resource_node WHERE id={d1})")
    print(f"     已释放设备 {d1} 所在岛上的扣留（若有）")

    print(f"\n通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
