#!/usr/bin/env python3
"""S2 规模测试：生成 N 节点简化流程（5 台模拟设备轮换），跑通并计时。

用法：
  python3 scripts/s2-scale-test.py 20   # 20 节点
  python3 scripts/s2-scale-test.py 30 40   # 30 + 40 节点
"""
import json
import os
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

# 5 台模拟设备：method -> resource_id
DEVICES = [
    ("Home", "200231"),        # 机械臂 TRANSFER
    ("ReadBarcode", "200200"),  # 读码 READ_PLATE
    ("RunProgram", "504831"),   # 离心 CENTRIFUGE
    ("Seal", "504830"),         # 封膜 SEAL
    ("Incubate", "200229"),     # 孵育 INCUBATE
]


def api(path, method="GET", body=None, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def login():
    c, b = api("/api/auth/login", "POST", {"username": "admin", "password": "admin123"})
    return json.loads(b)["token"] if c == 200 else None


def build_bpmn(n):
    """S → T1 → T2 → ... → Tn → E（纯顺序，设备轮换）。"""
    tasks = []
    flows = []
    prev = "S"
    for i in range(1, n + 1):
        method, res = DEVICES[(i - 1) % len(DEVICES)]
        tid = f"T{i}"
        tasks.append(f'    <task id="{tid}" name="步骤{i}" protocolName="{method}" resource="{res}"/>')
        flows.append(f'    <sequenceFlow id="f{i}" sourceRef="{prev}" targetRef="{tid}"/>')
        prev = tid
    flows.append(f'    <sequenceFlow id="f{n+1}" sourceRef="{prev}" targetRef="E"/>')
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Dscale">'
        '<process id="Pscale" isExecutable="true">'
        '  <startEvent id="S" name="开始"/>'
        + "\n".join(tasks) + "\n"
        '  <endEvent id="E" name="结束"/>'
        + "\n".join(flows) + "\n"
        '</process></definitions>'
    )


def api_raw(path, method, body, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Content-Type", "application/xml")
    try:
        with urllib.request.urlopen(req, data=body.encode(), timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def run(n, tok):
    c, b = api("/api/workflows", "POST", {"name": f"S2-规模-{n}节点"}, tok=tok)
    wf = json.loads(b)["id"] if c == 200 else None
    if not wf:
        return f"创建失败 HTTP {c}"
    xml = build_bpmn(n)
    api_raw(f"/api/workflows/{wf}/graph", "PUT", xml, tok=tok)
    t0 = time.time()
    c, b = api(f"/api/workflows/{wf}/run?async=1", "POST", tok=tok)
    run_id = json.loads(b).get("runId") if c == 200 else None
    if not run_id:
        return f"启动失败 HTTP {c}"
    # 轮询
    status = "running"
    for _ in range(120):  # 最多 6 分钟
        time.sleep(3)
        c, b = api(f"/api/runs/{run_id}", "GET", tok=tok)
        try:
            status = json.loads(b)["run"]["status"]
        except Exception:
            continue
        if status in ("completed", "failed", "aborted", "paused", "cancelled"):
            break
    elapsed = time.time() - t0
    return f"wf={wf} run={run_id} status={status} 耗时={elapsed:.0f}s"


def main():
    tok = login()
    if not tok:
        print("登录失败")
        return 1
    ns = [int(a) for a in sys.argv[1:]] or [10, 20, 30, 40]
    for n in ns:
        print(f"\n=== {n} 节点顺序流程 ===")
        print("  ", run(n, tok))
    return 0


if __name__ == "__main__":
    sys.exit(main())
