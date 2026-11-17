#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P1/P2 验收：点位绑定的预检（PF-21）+ 执行期搬运插入（`__move__*`）。

对应 `docs/architecture/点位视角设计器-设计_20261116.md` §8 P1/P2。

做四件事（真 API + 真库，跑完自清理）：
  ① 正向：两个跨设备点位各绑一个节点 ⇒ 预检**不报** PF-21/PF-27；
  ② 正向：起一次执行 ⇒ `run_node` 里出现 `__move__*`（能力 TRANSFER），
     说明规划器把机械臂插进去了，而不是"图上看着有、实际没跑"；
  ③ 反向：把 Transfer 方法绑到只授权 Run 的点位 ⇒ 预检报 **PF-21**；
  ④ 清理测试流程（含 run）。

⚠ 搬运能否**成功**取决于源位有没有板：没有板时驱动回 `EC-POSITION-OCCUPIED`
   ⇒ 节点转人工、run 暂停（这是正确语义，不是失败）。所以本脚本只断言
   "搬运节点被执行到了"，不把"搬运成功"当必要条件。

用法：
    LABOS_DB_HOST=... LABOS_DB_USER=... LABOS_DB_PASS=... LABOS_DB_NAME=... \\
      python3 scripts/verify-transport-plan.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ISLAND_CODE = os.environ.get("LABOS_ISLAND", "LAB-EXT-01")
POS_A = os.environ.get("LABOS_POS_A", "DISP1-PLATE")
POS_B = os.environ.get("LABOS_POS_B", "DISP3-PLATE")
METHOD_RUN = os.environ.get("LABOS_METHOD", "Run")
# 一个"肯定不该授权给上面那个点位"的方法，用来验 PF-21
METHOD_BAD = os.environ.get("LABOS_BAD_METHOD", "Transfer")

DB_HOST = os.environ.get("LABOS_DB_HOST", "127.0.0.1")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_USER = os.environ.get("LABOS_DB_USER", "zhengpingli")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_PASS = os.environ.get("LABOS_DB_PASS", "")


def api(path, method="GET", body=None, token=None, raw=None):
    req = urllib.request.Request(API + path, method=method)
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
        return json.loads(r.read() or b"{}")


def q(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = DB_PASS
    p = subprocess.run(["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER,
                        "-d", DB_NAME, "-tAc", sql], env=env, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("SQL 失败：" + (p.stderr or "")[:300])
    return p.stdout.strip()


def check(cond, msg, detail=""):
    print(("  ✔ " if cond else "  ✘ ") + msg + ((" — " + str(detail)) if detail and not cond else ""))
    if not cond:
        raise AssertionError(msg + " " + str(detail))


def graph_xml(dev_a, dev_b):
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
 <bpmn:process id="P1" isExecutable="true">
  <bpmn:startEvent id="S"/><bpmn:endEvent id="E"/>
  <bpmn:task id="A" name="{METHOD_RUN}" protocolName="{METHOD_RUN}" resource="{dev_a}"/>
  <bpmn:task id="B" name="{METHOD_RUN}" protocolName="{METHOD_RUN}" resource="{dev_b}"/>
  <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="A"/>
  <bpmn:sequenceFlow id="f2" sourceRef="A" targetRef="B"/>
  <bpmn:sequenceFlow id="f3" sourceRef="B" targetRef="E"/>
 </bpmn:process>
</bpmn:definitions>'''


def cleanup(wids):
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "cleanup-test-workflows.py")
    ids = ",".join(str(w) for w in wids)
    subprocess.run([sys.executable, script, "--apply", "--with-runs", "--only-ids", ids],
                   capture_output=True, text=True, check=False)
    left = q(f"SELECT count(*) FROM workflow WHERE id IN ({ids})")
    check(left == "0", f"测试流程已清理（{ids}）", f"剩余 {left}")


def main():
    token = api("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    topo = api("/api/topology", token=token)
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    check(island is not None, f"岛 {ISLAND_CODE} 存在")

    pos = api(f"/api/positions?islandId={island['id']}", token=token).get("items", [])
    by_code = {p.get("code"): p for p in pos}
    pa, pb = by_code.get(POS_A), by_code.get(POS_B)
    check(pa is not None and pb is not None, f"点位 {POS_A} / {POS_B} 存在")
    check(pa["parentDeviceId"] != pb["parentDeviceId"],
          "两点位**跨设备**（同设备不会插搬运）")

    def methods_of(pid):
        return api(f"/api/positions/{pid}/methods", token=token).get("bindings", [])

    m_run = next((m for m in methods_of(pa["pointId"]) if m.get("methodName") == METHOD_RUN), None)
    check(m_run is not None, f"{POS_A} 已授权方法 {METHOD_RUN}")
    # 反向用例只需要「一个**没**授权给该点位的方法」：跨设备取（分液器没有 Transfer，
    # 但 position_method 是按 (点位, 方法) 判的，任何别的方法都构成未授权）。
    bad_id = int(q(f"SELECT id FROM device_method WHERE method = '{METHOD_BAD}' "
                   f"ORDER BY id LIMIT 1") or 0)
    check(bad_id > 0, f"库里存在方法 {METHOD_BAD}（用于反向用例）")

    ts = int(time.time())
    wids = []

    def new_wf(tag):
        j = api("/api/workflows", "POST", {
            "code": f"TRANSPORT-{tag}-{ts}", "name": f"实测·搬运{tag}-{ts}",
            "category": "verify", "islandId": island["id"]}, token=token)
        wid = j.get("id") or j.get("workflow", {}).get("id")
        wids.append(wid)
        return wid

    try:
        # ---------- ① 正向：预检不误报
        wid = new_wf("OK")
        api(f"/api/workflows/{wid}/graph?draft=1", "PUT",
            raw=graph_xml(pa["parentDeviceId"], pb["parentDeviceId"]), token=token)
        for key, pid in (("A", pa["pointId"]), ("B", pb["pointId"])):
            api(f"/api/workflows/{wid}/nodes/{key}/bindings", "PUT", {"bindings": [{
                "positionId": pid, "methodId": m_run["methodId"], "materialRole": None,
                "loadingPattern": "load_single", "barcodePolicy": "validate_in",
                "leaveAtStation": False, "params": {}}]}, token=token)
        pre = api(f"/api/workflows/{wid}/preflight", token=token)
        codes = [i.get("code") for i in pre.get("issues", [])]
        check(not pre.get("blocking"), "① 正向用例预检不阻塞", codes)
        check("PF-21" not in codes and "PF-27" not in codes,
              "① 正向用例不误报 PF-21/PF-27", codes)

        # ---------- ② 执行：搬运节点真的被跑到
        run = api(f"/api/workflows/{wid}/run?async=1", "POST", {}, token=token)
        rid = run.get("runId") or run.get("id")
        status = ""
        for _ in range(45):
            time.sleep(2)
            status = q(f"SELECT status::text FROM run WHERE id={rid}")
            if status not in ("running", "queued"):
                break
        rows = q(f"SELECT node_key||'|'||status::text FROM run_node "
                 f"WHERE run_id={rid} AND node_key LIKE '__move__%'")
        print(f"  · run {rid} 终态={status} 搬运节点={rows or '（无）'}")
        check(bool(rows), "② 执行期插入了搬运节点（run_node 有 __move__*）", rows)
        check("TRANSFER" in q(
            f"SELECT coalesce(input->>'capability','') FROM run_node "
            f"WHERE run_id={rid} AND node_key LIKE '__move__%' LIMIT 1"),
            "② 搬运节点能力是 TRANSFER")

        # ---------- ③ 反向：未授权方法 → PF-21
        wid2 = new_wf("BAD")
        api(f"/api/workflows/{wid2}/graph?draft=1", "PUT",
            raw=graph_xml(pa["parentDeviceId"], pb["parentDeviceId"]), token=token)
        api(f"/api/workflows/{wid2}/nodes/A/bindings", "PUT", {"bindings": [{
            "positionId": pa["pointId"], "methodId": bad_id, "materialRole": None,
            "loadingPattern": "load_single", "barcodePolicy": "none",
            "leaveAtStation": False, "params": {}}]}, token=token)
        pre2 = api(f"/api/workflows/{wid2}/preflight", token=token)
        codes2 = [(i.get("code"), i.get("level")) for i in pre2.get("issues", [])]
        check(any(c == "PF-21" for c, _ in codes2), "③ 未授权的点位方法报 PF-21", codes2)
        print("\nPASS · P1 预检 + P2 执行期搬运")
    finally:
        cleanup(wids)


if __name__ == "__main__":
    main()
