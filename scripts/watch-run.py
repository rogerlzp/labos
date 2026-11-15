#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""看一次流程运行的节点状态分布（只读，不改任何东西）。

用法：
    LABOS_API=http://127.0.0.1:18084 python3 scripts/watch-run.py <runId> [--wait 60]

⛔ **只读**：只 GET /api/runs/{id}，不发任何写操作。
"""

import json
import os
import sys
import time
import urllib.request
from collections import Counter

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
TOKEN = None


def call(method, path, body=None):
    url = API + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    with urllib.request.urlopen(req, timeout=60) as r:
        t = r.read().decode("utf-8")
        return json.loads(t) if t.strip() else {}


def login():
    global TOKEN
    j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    TOKEN = j["token"]


def snapshot(run_id):
    d = call("GET", f"/api/runs/{run_id}")
    run = d.get("run", {})
    nodes = d.get("nodes", [])
    # 只看顶层节点（level=0）：子节点（device_step）是内核的派发明细
    top = [n for n in nodes if (n.get("level") or 0) == 0]
    dist = Counter(n.get("status") for n in top)
    return run, top, nodes, dist


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    run_id = sys.argv[1]
    wait = 0
    if "--wait" in sys.argv:
        wait = int(sys.argv[sys.argv.index("--wait") + 1])

    login()
    deadline = time.time() + wait
    while True:
        run, top, allnodes, dist = snapshot(run_id)
        print(f"— run {run_id} status={run.get('status')} "
              f"attendance={run.get('attendance')} "
              f"pause={run.get('pauseKind')}/{run.get('pauseReason')} "
              f"node={run.get('pauseNodeKey')}")
        print(f"  顶层节点 {len(top)} 个：{dict(dist)}")
        bad = [n for n in top if n.get("status") not in ("completed", "skipped")]
        for n in bad:
            print(f"    · {n.get('status'):10s} {n.get('nodeKey'):16s} "
                  f"{n.get('name')} | err={n.get('errorCode')} "
                  f"{(n.get('reason') or n.get('note') or '')[:110]}")
        if run.get("status") not in ("running", "queued", "pending") or time.time() > deadline:
            break
        time.sleep(5)
    print()
    print("── 全部顶层节点（含已完成）")
    for n in top:
        print(f"    {n.get('status'):10s} {n.get('nodeKey'):16s} {n.get('name')}")


if __name__ == "__main__":
    main()
