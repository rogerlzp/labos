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


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (f"（{detail}）" if detail else ""))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=30) as r:
        return json.loads(r.read().decode() or "{}")


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
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

    island = args.island or int(q("""
        SELECT p.id FROM resource_node d JOIN resource_node p ON p.id = d.parent_id
         WHERE d.kind = 'device' AND d.tenant_id = 1
         GROUP BY p.id ORDER BY count(*) DESC LIMIT 1"""))
    wf = args.workflow or int(q("""
        SELECT id FROM workflow WHERE tenant_id = 1 AND deleted_at IS NULL
         ORDER BY id DESC LIMIT 1"""))
    print(f"岛 = {island}，流程 = {wf}，并发数 = {args.count}")

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
        q(f"DELETE FROM experiment_process WHERE experiment_id={eid}; "
          f"DELETE FROM experiment WHERE id={eid};")
    print(f"通过 {PASS} / 失败 {FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
