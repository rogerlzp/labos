#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跑一次「高通量核酸提取工作站」标准流程并取证（改造前/后对照用）。

用法：
    python3 scripts/run-extraction-line.py                 # 默认流程 1000929
    python3 scripts/run-extraction-line.py --workflow 1000929 --timeout 300

它做什么：
    1. 登录取 token
    2. POST /api/workflows/{id}/run?async=1  起一次异步运行
    3. 轮询 /api/runs/{id} 到终态
    4. 打印终态、错误码、节点状态分布，并把**第一个失败节点**的细节挖出来

退出码：0 = 跑到 completed；1 = 失败/暂停/超时（细节打在 stdout）。

⚠ 只发一次运行请求，不重试、不清理库内既有数据。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

API = "http://127.0.0.1:18080"
USER = "admin"
PASSWORD = "admin123"
TERMINAL = ("completed", "failed", "aborted", "paused", "cancelled")


def api(path, method="GET", body=None, token=None, timeout=60):
    req = urllib.request.Request(
        API + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow", type=int, default=1000929)
    ap.add_argument("--timeout", type=int, default=300)
    args = ap.parse_args()

    st, body = api("/api/auth/login", "POST", {"username": USER, "password": PASSWORD})
    if st != 200 or not body.get("token"):
        print("✗ 登录失败 HTTP %s %s" % (st, body))
        return 1
    token = body["token"]

    st, body = api("/api/workflows/%d/run?async=1" % args.workflow, "POST", {}, token)
    run_id = body.get("runId")
    print("▶ 流程 %d 已发起，runId=%s" % (args.workflow, run_id))
    if not run_id:
        print("✗ 发起失败 HTTP %s %s" % (st, body))
        return 1

    deadline = time.time() + args.timeout
    status, last = None, {}
    while time.time() < deadline:
        time.sleep(3)
        st, last = api("/api/runs/%d" % run_id, "GET", token=token)
        if st != 200:
            print("  查询失败 HTTP %s" % st)
            continue
        run = last.get("run") or {}
        status = run.get("status")
        print("  status=%s" % status)
        if status in TERMINAL:
            break

    run = (last or {}).get("run") or {}
    print("\n════════ 终态 ════════")
    print("runId=%s  status=%s  errorCode=%s" % (run_id, status, run.get("error_code")))
    print("error=%s" % (run.get("error") or ""))

    nodes = (last or {}).get("nodes") or []
    dist = {}
    for n in nodes:
        s = n.get("status") or "?"
        dist[s] = dist.get(s, 0) + 1
    print("节点状态分布：%s（共 %d）" % (dist, len(nodes)))

    bad = [n for n in nodes if (n.get("status") or "") not in ("completed", "skipped", "bypassed")]
    for n in bad[:6]:
        print("\n── 未完成节点 %s ──" % (n.get("node_key") or n.get("nodeKey")))
        print("   status=%s errorCode=%s" % (n.get("status"), n.get("error_code")))
        print("   error=%s" % (n.get("error") or n.get("message") or ""))
    return 0 if status == "completed" else 1


if __name__ == "__main__":
    sys.exit(main())
