#!/usr/bin/env python3
"""同岛多实验并发验证：同时提交 N 个实验，断言"岛级互斥 → 峰值并行 = 1"。

为什么要这个脚本：并行能力一直是"读代码觉得没事"，但**没人量过**。
同岛并发时如果出现两个 running，说明岛锁失效 —— 那意味着两个实验会同时命令同一台设备。

用法：
    export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
    python3 scripts/verify-parallel-experiments.py [--count 4] [--island <id>] [--workflow <id>]
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PASS, FAIL = 0, 0

# 极简流程：start → task（绑设备+方法） → end。自己造，不reuse库里的存量流程——
# 那些流程绑的设备可能早就离线了（跑出来 0/4 完成，看着像岛锁失效其实不是）。
XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Def_c" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_c" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="任务一" protocolName="%(m1)s" resource="%(d1)s"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="D_c">
    <bpmndi:BPMNPlane id="P_c" bpmnElement="Proc_c">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="200" y="120" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="400" y="160" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""

# 探方法时按"有时长的动作类"优先：并发/排队断言要靠命令真的占一段时长。
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
    with urllib.request.urlopen(req, data=data, timeout=30) as r:
        return json.loads(r.read().decode() or "{}")


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    # 口令只从 LABOS_DB_PASS 来：不设 PGPASSWORD 时 psql 会要交互口令，
    # 非 tty 下直接失败并返回空串 —— int('') 会让整个用例崩掉。
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    return subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True).stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=4)
    ap.add_argument("--island", type=int, default=0)
    ap.add_argument("--workflow", type=int, default=0)
    args = ap.parse_args()

    for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"):
        if not os.environ.get(k):
            print(f"缺少环境变量 {k}（口令不写进脚本）")
            return 2

    try:
        token = api("/api/auth/login", "POST",
                    {"username": "admin", "password": "admin123"})["token"]
    except Exception as e:  # noqa: BLE001
        print(f"SKIP: API 不可用（{e}），无法验证同岛并发")
        return 0

    def probe(rid, method):
        """单命令真下发一次：ok=True 才算"这台设备真的能跑这个方法"。"""
        try:
            r = api(f"/api/shadow/probe?resourceId={rid}&method={method}", "POST", {}, token)
            return bool(r.get("ok"))
        except Exception:  # noqa: BLE001
            return False

    # 选设备：按"最近成功过命令 + 本地端点 + 库里顺序"排，逐台**真探**。
    #
    # 不要用 device_state.online 选：心跳只证明 TCP 连得上。实测环境里就有
    # "心跳绿但驱动端口已关"的岛，跑出来 0/4 完成 —— 看着像岛锁失效其实不是。
    # 也不要只按"最近成功过"选：那台设备成功是**几十分钟前**另一个台架（sidecar）
    # 在跑时的记录，台架一停就全失败。所以一律用单命令探针**真探**。
    dev, method = 0, ""
    candidates = [int(x) for x in q("""
        SELECT r.id FROM resource_node r
          JOIN device_instance di ON di.resource_id = r.id AND di.deleted_at IS NULL
          LEFT JOIN driver dr ON dr.id = di.driver_id AND dr.deleted_at IS NULL
         WHERE r.kind = 'device' AND r.tenant_id = 1 AND r.deleted_at IS NULL
         ORDER BY (SELECT count(*) FROM run_command c
                     JOIN run_node n ON n.id = c.run_node_id
                    WHERE n.resource_id = r.id AND c.status = 'completed'
                      AND c.finished_at > now() - interval '2 hours') DESC,
                  -- endpoint 为 NULL 时 LIKE 出 NULL，DESC 会把它们排最前白占名额
                  COALESCE(dr.endpoint LIKE 'tcp://127.0.0.1%', false) DESC,
                  r.id
         LIMIT 12""").split()]
    for rid in candidates:
        ms = [m for m in METHOD_CANDIDATES if probe(rid, m)]
        if ms:
            dev, method = rid, ms[0]
            break
    if not dev:
        print("SKIP: 库里没有真能跑命令的设备（CI 没起模拟器？），无法验证同岛并发")
        return 0

    island = args.island or int(q(f"SELECT parent_id FROM resource_node WHERE id={dev}") or 0)
    if not island:
        print(f"SKIP: 设备 {dev} 没挂在任何岛上，无法验证同岛并发")
        return 0
    # 自建流程并绑这台设备：不 reuse 库里存量流程，避免"流程绑的设备早离线"的假红。
    wf = api("/api/workflows", "POST", {"name": "verify-同岛并发"}, token)["id"]
    api(f"/api/workflows/{wf}/graph", "PUT",
        raw=XML % {"m1": method, "d1": dev}, token=token)
    print(f"岛 = {island}，设备 = {dev}（{method}），流程 = {wf}，并发数 = {args.count}")

    ids = []
    for i in range(args.count):
        ids.append(api("/api/experiments", "POST",
                       {"name": f"verify-并发-{i+1}", "islandId": island,
                        "workflowIds": [wf]}, token)["id"])

    erids, lock = [], threading.Lock()

    def submit(eid):
        try:
            r = api(f"/api/experiments/{eid}/run?async=1", "POST", {}, token)
            with lock:
                erids.append(r.get("experimentRunId") or r.get("id"))
        except Exception as e:  # noqa: BLE001
            bad(f"提交失败 {eid}", str(e))

    ths = [threading.Thread(target=submit, args=(e,)) for e in ids]
    t0 = time.time()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    print(f"{args.count} 次提交耗时 {time.time()-t0:.2f}s")

    erids = [x for x in erids if x]
    inlist = ",".join(str(int(x)) for x in erids)

    peak, queued_peak, samples = 0, 0, []
    deadline = time.time() + 180
    while time.time() < deadline:
        sts = [s for s in q(f"SELECT status FROM experiment_run WHERE id IN ({inlist})").split("\n") if s]
        running = sum(1 for s in sts if s == "running")
        queued = sum(1 for s in sts if s == "queued")
        peak = max(peak, running)
        queued_peak = max(queued_peak, queued)
        samples.append(f"{running}R/{queued}Q")
        if len(sts) == len(erids) and all(s in ("completed", "failed", "aborted") for s in sts):
            break
        time.sleep(0.4)

    print("采样:", " ".join(samples[:30]))
    ok(f"同岛并发出现排队（峰值排队 {queued_peak}）") if queued_peak > 0 \
        else bad("没有观察到排队，并发可能没生效")
    ok(f"峰值同时 running = {peak}（同岛互斥应为 1）") if peak == 1 \
        else bad(f"峰值并行 = {peak}，岛锁可能失效")

    done = q(f"SELECT count(*) FROM experiment_run WHERE id IN ({inlist}) "
             f"AND status = 'completed'")
    ok(f"{done}/{len(erids)} 个实验执行完成") if done == str(len(erids)) \
        else bad("有实验没完成", f"{done}/{len(erids)}")

    print("\n--- 清理 ---")
    for eid in ids:
        q(f"DELETE FROM experiment_run_step WHERE experiment_run_id IN "
          f"(SELECT id FROM experiment_run WHERE experiment_id={eid}); "
          f"DELETE FROM experiment_batch_run WHERE experiment_run_id IN "
          f"(SELECT id FROM experiment_run WHERE experiment_id={eid}); "
          f"DELETE FROM experiment_run WHERE experiment_id={eid}; "
          f"DELETE FROM experiment_process_edge WHERE experiment_id={eid}; "
          f"DELETE FROM experiment_process WHERE experiment_id={eid}; "
          f"DELETE FROM experiment_variable WHERE experiment_id={eid}; "
          f"DELETE FROM experiment_graph WHERE experiment_id={eid}; "
          f"DELETE FROM experiment_batch WHERE experiment_id={eid}; "
          f"DELETE FROM experiment WHERE id={eid};")
    q(f"DELETE FROM workflow_node WHERE workflow_id={wf}; DELETE FROM workflow WHERE id={wf};")
    # 本次并发可能停在 queued/paused，会留下**岛级扣留**；不释放的话：
    #   · 同一个岛下一个用例永远排不到队（假红）；
    #   · 更狠的是调度器是"全局闸门"（RuntimeHold.anyActive()），任何一条未释放的
    #     扣留会让**所有岛**的新实验都派发不出去。
    # released_by 是 bigint，写字符串会 invalid input syntax 而静默失败（q() 只取 stdout）。
    q(f"UPDATE runtime_hold SET released_at=now(), released_by=coalesce(held_by, 1), "
      f"release_note='用例收尾复位' WHERE scope='island' AND scope_id={island} "
      f"AND released_at IS NULL")
    print(f"     已释放岛 {island} 上的扣留（若有）")
    print(f"通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
