#!/usr/bin/env python3
# ============================================================================
# 基线端到端验收：新基线上的一条流程 / 一个实验能不能真跑通。
#
# 这是新门禁的**核心段**。断言的都是"用户看得见的事实"：
#   ① 5 个节点都真的 completed（不是 skipped / manual / pending）
#   ② 5 条命令都有**真实的设备回包**（ok=true，不是空回包）
#   ③ 命令的发出时刻 ≤ 完成时刻（时间线自洽）
#   ④ 实验走的是"实验 → 流程"这条链路（不是直接跑流程）
#
# 用法：
#   python3 scripts/verify-baseline-flow.py                 # 用播种脚本建的基线流程
#   python3 scripts/verify-baseline-flow.py --workflow 123  # 指定流程
#   python3 scripts/verify-baseline-flow.py --experiment 456
# ============================================================================
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

PASS, FAIL = 0, 0


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def api(path, method="GET", body=None, token=None, raw=None, timeout=60):
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
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            txt = r.read().decode()
            try:
                return json.loads(txt)
            except json.JSONDecodeError:
                return txt
    except urllib.error.HTTPError as e:
        raise RuntimeError("%s %s → HTTP %d: %s" % (method, path, e.code, e.read().decode()[:300]))


def q(sql):
    """跑一条 SQL；**失败必须抛错**（空串会被当成"库里没有" ⇒ 假绿）。"""
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql.strip()[:200], out.stderr.strip()[:300]))
    return out.stdout.strip()


def wait_run(rid, budget):
    """等一个 run 收敛；返回 (status, 节点明细)。"""
    deadline = time.time() + budget
    while time.time() < deadline:
        st = q("SELECT status FROM run WHERE id=%d" % rid)
        if st in ("completed", "failed", "aborted", "paused"):
            break
        time.sleep(2)
    st = q("SELECT status FROM run WHERE id=%d" % rid)
    nodes = q("SELECT node_key || ' ' || status FROM run_node "
              "WHERE run_id=%d AND level=0 ORDER BY id" % rid)
    return st, nodes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow", type=int, default=0)
    ap.add_argument("--experiment", type=int, default=0)
    ap.add_argument("--budget", type=int, default=180)
    args = ap.parse_args()

    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]

    wf_id = args.workflow
    exp_id = args.experiment
    if not wf_id and not exp_id:
        # 没指定 ⇒ 用播种脚本建基线流程（保证"基线"始终是可复现的）
        seed = json.loads(subprocess.run(
            [sys.executable, os.path.join(os.path.dirname(__file__), "baseline-seed.py")],
            capture_output=True, text=True, check=True).stdout)
        wf_id = seed["workflowId"]
        exp_id = seed["experimentId"]
        print("基线：流程 #%d、实验 #%d（岛 %s）" % (wf_id, exp_id, seed["islandCode"]))
    elif exp_id and not wf_id:
        # 只给实验 ⇒ 从实验的编排里反查流程（实验的 process 里带着 workflow_id）
        wf_id = int(q("SELECT workflow_id FROM experiment_process WHERE experiment_id=%d "
                      "ORDER BY id LIMIT 1" % exp_id) or 0)
        if not wf_id:
            print("实验 #%d 没有关联流程" % exp_id, file=sys.stderr)
            return 2
        print("实验 #%d ⇒ 流程 #%d" % (exp_id, wf_id))

    print("\n── 流程执行 ──")
    run = api("/api/workflows/%d/run?async=1" % wf_id, "POST", {}, token)
    rid = run.get("runId") or int(q("SELECT max(id) FROM run"))
    st, nodes = wait_run(rid, args.budget)
    print("  run #%d 状态=%s" % (rid, st))
    print("  节点：%s" % (nodes.replace("\n", " | ") or "(无)"))

    if st == "completed":
        ok("流程整单 completed")
    else:
        bad("流程整单没跑完", st)

    # 5 个节点全 completed
    want = ["T1", "T2", "T3", "T4", "T5"]
    for k in want:
        if ("%s completed" % k) in nodes:
            ok("节点 %s completed" % k)
        else:
            bad("节点 %s 没 completed" % k, nodes.replace("\n", " | "))

    # 5 条命令都有真实回包
    cmds = q("SELECT method || '|' || status || '|' || coalesce(result::text,'') "
             "FROM run_command WHERE run_id=%d ORDER BY id" % rid).split("\n")
    cmds = [c for c in cmds if c.strip()]
    ok("命令条数 %d" % len(cmds)) if len(cmds) >= 5 else bad("命令条数不足 5", str(len(cmds)))
    for c in cmds:
        parts = c.split("|", 2)
        method, status = parts[0], parts[1]
        result = parts[2] if len(parts) > 2 else ""
        if status != "completed":
            bad("命令 %s 状态 %s" % (method, status))
            continue
        # 真回包：result 里要有 ok=true 或至少非空
        if '"ok":true' in result.replace(" ", "") or '"ok": true' in result:
            ok("命令 %s 有真实设备回包" % method)
        else:
            bad("命令 %s 的回包不是成功回包" % method, result[:80])

    # 时间线自洽：sent_at <= finished_at
    bad_ts = q("SELECT count(*) FROM run_command WHERE run_id=%d "
               "AND sent_at IS NOT NULL AND finished_at IS NOT NULL "
               "AND sent_at > finished_at" % rid)
    if bad_ts == "0":
        ok("命令时间线自洽（发出时刻 ≤ 完成时刻）")
    else:
        bad("有命令的发出时刻晚于完成时刻", bad_ts)

    # 实验链路
    if exp_id:
        print("\n── 实验执行 ──")
        try:
            api("/api/experiments/%d/run" % exp_id, "POST", {}, token)
        except RuntimeError as e:
            bad("实验派发失败", str(e)[:200])
        erid = int(q("SELECT max(id) FROM experiment_run"))
        deadline = time.time() + args.budget
        while time.time() < deadline:
            est = q("SELECT status FROM experiment_run WHERE id=%d" % erid)
            if est in ("completed", "failed", "aborted", "paused", "running"):
                if est != "running":
                    break
            time.sleep(2)
        est = q("SELECT status FROM experiment_run WHERE id=%d" % erid)
        print("  experiment_run #%d 状态=%s" % (erid, est))
        if est == "completed":
            ok("实验整单 completed")
        else:
            bad("实验整单没跑完", est)
        # 实验派发出的那一步（experiment_run_step → run）
        rid2 = q("SELECT run_id FROM experiment_run_step WHERE experiment_run_id=%d "
                 "ORDER BY seq LIMIT 1" % erid)
        if rid2:
            st2, nodes2 = wait_run(int(rid2), 30)
            if st2 == "completed":
                ok("实验派发出的流程也 completed（run #%s）" % rid2)
            else:
                bad("实验派发出的流程没跑完", st2)
        else:
            bad("实验没有关联到 run")

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
