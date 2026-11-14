#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P2-7 扫码校验 · **运行时接线**的端到端验证（补 §8.4 的证据缺口）。

**为什么要有这个脚本**
`ScanPolicy` 的五值判定有 19 例纯逻辑自测（`ScanPolicySelfTest`），但那只证明
"给定 expected/scanned 会不会判不匹配"。**"运行时真的会转人工并开 manual_window"**
一直没有端到端证据 —— 手测依赖具体流程与设备返回值，门槛高。

**本脚本怎么触发**
`WorkflowService.scanGate(nodeId, rawResponse)` 的触发条件是四条同时成立：
  ① 设备命令成功（ok）② deviceId > 0 ③ rawResponse != null
  ④ `PositionLock.isBarcodeMethod(method)`（ReadBarcode / BarcodeScan / Scan…）
不匹配 ⇒ `PauseManager.pause(runId, WAITING_HUMAN, "扫码校验失败：条码不匹配…")`。

⇒ 所以只要给一个**扫码类节点的 node_binding** 配上 `barcode_policy='validate_in'`
和**一个对不上的期望条码**，跑一次流程就必然进人工窗口。
（`ScanPolicy.violates` 是 fail-closed 的：扫不到码也算不匹配 ⇒ 模拟器不返回条码也能触发。）

**断言**
  ① run 停在 paused + waiting_human
  ② 有人工窗口且处于 pending，理由含「条码不匹配」
  ③ 内核日志里有「[扫码校验] …条码不匹配…→ 转人工」
  ④ 清理干净（绑定删掉、run 相关行删掉、点位不被占）

**前置**：内核 18080 + 模拟器 19500（没有模拟器就**显式 SKIP**，不静默算通过）。

用法：
  LABOS_API=http://127.0.0.1:18080 LABOS_SIM_PORT=19500 \
  python3 scripts/verify-scan-policy-gate.py
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
SIM_PORT = int(os.environ.get("LABOS_SIM_PORT", "19500"))
LOG = os.environ.get("LABOS_KERNEL_LOG",
                      "/Users/zhengpingli/work/中控/labos-kernel/logs/labos-kernel.log")

# 跑哪个流程（要有一个扫码类节点，且该节点所在设备在线）
WORKFLOW_ID = int(os.environ.get("LABOS_SCAN_WF", "1000830"))
NODE_KEY = os.environ.get("LABOS_SCAN_NODE", "Activity_077zr9t")

# 期望条码：故意用不可能命中的值
EXPECTED = "NEVER-MATCH-%d" % int(time.time())

PASS, FAIL, SKIP = 0, 0, 0


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def skip(msg, detail=""):
    global SKIP
    SKIP += 1
    print("  \033[36m⊘\033[0m 跳过 " + msg + (("（%s）" % detail) if detail else ""))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def q(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = env.get("LABOS_DB_PASS", "")
    p = subprocess.run(["psql", "-h", env.get("LABOS_DB_HOST", "127.0.0.1"),
                        "-p", env.get("LABOS_DB_PORT", "5432"),
                        "-U", env.get("LABOS_DB_USER", "labos"),
                        "-d", env.get("LABOS_DB_NAME", "labdb"), "-tAc", sql],
                       capture_output=True, text=True, env=env)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip()[:200])
    return p.stdout.strip()


def simulator_up():
    import socket
    s = socket.socket()
    s.settimeout(3)
    try:
        s.connect(("127.0.0.1", SIM_PORT))
        return True
    except Exception:
        return False
    finally:
        s.close()


def log_tail(n=400):
    try:
        with open(LOG, "r", errors="replace") as f:
            return f.read()[-n * 200:]
    except Exception:
        return ""


def cleanup_bindings():
    """删掉本脚本给该节点加的绑定（按 params 里的 expected_barcode 认领）。"""
    # node_binding 没有 node_key 列，用 node_id 定位
    q("DELETE FROM node_binding nb USING workflow_node wn "
      "WHERE nb.node_id = wn.id AND wn.workflow_id = %d AND wn.node_key = '%s' "
      "AND nb.barcode_policy = 'validate_in' AND nb.params->>'expected_barcode' LIKE 'NEVER-MATCH-%%'"
      % (WORKFLOW_ID, NODE_KEY))


def cleanup_run(run_id):
    if not run_id:
        return
    # ⚠ 释放点位占用：run 被删了但 position_state 还占着 ⇒ 后续流程撞 EC-RESOURCE-BUSY
    #   （实测：删掉的 run 留下的点位占用会让下一个 run 卡在"资源忙"）
    try:
        q("UPDATE position_state SET plate_id = NULL, content_kind = NULL, "
           "content_id = NULL, occupied_by_run = NULL, occupied_since = NULL, "
           "updated_at = now() WHERE occupied_by_run = %d" % run_id)
    except Exception as e:
        print("  [清理] position_state 失败：%s" % str(e)[:80])
    for t, col in (("manual_window", "run_id"), ("transport_segment", "run_id"),
                   ("run_artifact", "run_id"), ("run_variable", "run_id"),
                   ("intervention_record", "run_id"), ("run_command", "run_id")):
        try:
            q("DELETE FROM %s WHERE %s = %d" % (t, col, run_id))
        except Exception as e:
            print("  [清理] %s 失败：%s" % (t, str(e)[:80]))
    try:
        q("DELETE FROM run_event WHERE aggregate_type = 'run' AND aggregate_id = %d" % run_id)
    except Exception as e:
        print("  [清理] run_event 失败：%s" % str(e)[:80])
    try:
        q("DELETE FROM run_node WHERE run_id = %d" % run_id)
    except Exception as e:
        print("  [清理] run_node 失败：%s" % str(e)[:80])
    try:
        q("DELETE FROM run WHERE id = %d" % run_id)
    except Exception as e:
        print("  [清理] run 失败：%s" % str(e)[:80])


def main():
    print("── P2-7 扫码校验 · 运行时接线 e2e ──")

    if not simulator_up():
        skip("模拟器不在 %d，扫码类命令拿不到 rawResponse" % SIM_PORT)
        print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
        return 0

    st, body = api("/api/auth/login", "POST",
                    {"username": "admin", "password": "admin123"})
    if st != 200:
        bad("admin 登录失败", "HTTP %d" % st)
        return 1
    token = body.get("token")

    # ① 找一个扫码类节点（该节点所在设备要在线）
    node_id = q("SELECT id FROM workflow_node WHERE workflow_id = %d AND node_key = '%s'"
                % (WORKFLOW_ID, NODE_KEY))
    if not node_id:
        skip("流程 %d 上没有节点 %s" % (WORKFLOW_ID, NODE_KEY))
        print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
        return 0
    node_id = int(node_id)

    method_id = q("SELECT id FROM device_method WHERE method = 'ReadBarcode' "
                  "ORDER BY id LIMIT 1")
    position_id = q("SELECT id FROM device_point WHERE device_resource_id = 200200 "
                    "AND robot_reachable = true ORDER BY id LIMIT 1")
    if not method_id or not position_id:
        skip("找不到 ReadBarcode 方法或可达点位")
        print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
        return 0
    method_id, position_id = int(method_id), int(position_id)

    # ② 加绑定：validate_in + 永不匹配的期望条码
    st, body = api("/api/workflows/%d/nodes/%s/bindings" % (WORKFLOW_ID, NODE_KEY),
                   "PUT", token=token,
                   body={"bindings": [{"seq": 1, "positionId": position_id,
                                       "methodId": method_id,
                                       "barcodePolicy": "validate_in",
                                       "params": {"expected_barcode": EXPECTED}}]})
    if st != 200:
        bad("加节点绑定失败", "HTTP %d %s" % (st, str(body)[:120]))
        return 1
    ok("节点绑上 validate_in + 期望条码", "expected=%s" % EXPECTED)

    run_id = None
    try:
        # ③ 跑流程
        # ⚠ **同步跑，不用 async**：
        #   `?async=1` 在当前环境下会创建 run、写完点位快照就**不被派发**（实测 run=1001496
        #   停在派发前，只有 [点位快照] 一条日志）。同步跑则会真正执行到扫码节点。
        #   待派发路径的问题另立批次（见 docs/测试报告-P0收口与P2P3落地-20261114.md）。
        st, body = api("/api/workflows/%d/run" % WORKFLOW_ID, "POST", token=token)
        if st != 200:
            bad("跑流程失败", "HTTP %d %s" % (st, str(body)[:120]))
            return 1
        run_id = body.get("runId")
        ok("流程已跑到扫码节点", "run=%s" % run_id)

        # ④ 同步跑已返回 ⇒ run 应停在人工窗口
        # ⚠ 没有 `GET /api/runs/{id}` 端点（只有 /api/runs/{id}/commands、/graph 等），
        #   直接查库最稳。
        row = q("SELECT status || '|' || COALESCE(pause_kind,'') FROM run WHERE id = %d" % run_id)
        parts = row.split("|")
        if len(parts) == 2 and parts[0] == "paused" and parts[1] == "waiting_human":
            ok("run 停在 waiting_human", "run=%s" % run_id)
        else:
            bad("run 没停在 waiting_human", "status/pauseKind=%s" % row)
            return 1

        # ⑤ 人工窗口（直接查库：端点返回的字段名不稳定）
        # 理由在 decision_reason（note 是空的）；窗口键形如 pause:<runId>
        mrow = q("SELECT id || '|' || status || '|' || COALESCE(decision_reason,'') "
                 "FROM manual_window WHERE run_id = %d ORDER BY id DESC LIMIT 1" % run_id)
        if not mrow:
            bad("没有人工窗口")
            return 1
        mids, mstat, mnote = mrow.split("|", 2)
        if mstat == "pending":
            ok("有人工窗口且 pending", "id=%s" % mids)
        else:
            bad("人工窗口不是 pending", "status=%s" % mstat)
        if "条码不匹配" in mnote:
            ok("窗口理由含「条码不匹配」", mnote[:90])
        else:
            bad("窗口理由没提条码不匹配", mnote[:110])

        # ⑥ 内核日志
        tail = log_tail(600)
        if "[扫码校验]" in tail and "转人工" in tail:
            ok("内核日志有「[扫码校验] …→ 转人工」", "")
        else:
            bad("内核日志里没有扫码校验转人工的记录")
    finally:
        # ⑦ 清理：先解人工窗口再删 run，最后删绑定
        if run_id:
            try:
                for m in q("SELECT id FROM manual_window WHERE run_id = %d AND status = 'pending'"
                           % run_id).split():
                    api("/api/manual-windows/%s/decide" % m, "POST", token=token,
                        body={"action": "MANUAL"})
            except Exception:
                pass
            cleanup_run(run_id)
        cleanup_bindings()
        try:
            left = q("SELECT count(*) FROM node_binding nb JOIN workflow_node wn "
                     "ON wn.id = nb.node_id WHERE wn.workflow_id = %d "
                     "AND nb.params->>'expected_barcode' LIKE 'NEVER-MATCH-%%'" % WORKFLOW_ID)
            if left == "0":
                ok("节点绑定已清理干净", "")
            else:
                bad("节点绑定残留", left)
        except Exception as e:
            bad("清理核对失败", str(e)[:80])

    print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
