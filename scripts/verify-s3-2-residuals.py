#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S3.2 残余收尾联调 + 崩溃恢复端到端（BUG-14）：
  · async 变量持久化；
  · 扣留解除自动续队；
  · 重启 rehydrate 待派发（S3.2-A）；
  · 崩溃时 running 孤儿 → 重启后启动兜底 settle（BUG-14 端到端，见 Case 3 §3b/§3c）。

全部用"没有编排任何流程的空实验"（执行器展开为空立即失败，不碰设备），
走的却是真实调度链路（排队/派发/批续队/rehydrate/启动兜底）。

为什么这一节必须端到端：StartupSettleSelfTest 直接调包内重载、进程内造数，
只证明「方法本身」对；证明不了「生产入口」（ApiServer 启动时那三行
settle/rehydrate）真的被调用。本脚本 kill 真进程 → 重启 → 断言库里 running
孤儿真被收掉，补的就是这段接线。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/verify-s3-2-residuals.py [--api http://127.0.0.1:18080] [--api-log /tmp/labos-api.log]

注意：Case 3 会 kill 并重启 18080 的 API 进程（用同一组 LABOS_DB_* 环境变量），
      跑之前确认没有别的测试/验收在跑。重启继承本进程环境变量，所以调用方要
      保证 LABOS_TRANSFER_PLAN / LABOS_EXEC_GRAPH / LABOS_DRIVER_MODE / LABOS_AUTH
      已导出（ci-gate.sh 会兜底设默认值），否则重启后的内核能力与重启前不一致。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

import psycopg2
from playwright.sync_api import sync_playwright

PASS = 0
FAIL = 0
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def ok(msg):
    global PASS
    PASS += 1
    print("  \u2713 " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \u2717 " + msg + (("  (" + str(detail) + ")") if detail else ""))


def step(t):
    print("\n\u2550\u2550 " + t + " \u2550\u2550")


class Api:
    def __init__(self, base, token):
        self.base = base
        self.token = token

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read().decode("utf-8")
                try:
                    return r.status, json.loads(raw)
                except json.JSONDecodeError:
                    return r.status, raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except json.JSONDecodeError:
                return e.code, {"error": raw[:200]}


def db_connect():
    need = [k for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER") if not os.environ.get(k)]
    if need:
        print("缺数据库环境变量：" + " ".join(need))
        sys.exit(2)
    return psycopg2.connect(
        host=os.environ["LABOS_DB_HOST"],
        port=int(os.environ.get("LABOS_DB_PORT", "5432")),
        dbname=os.environ["LABOS_DB_NAME"],
        user=os.environ["LABOS_DB_USER"],
        password=os.environ.get("LABOS_DB_PASS", ""),
    )


def login_api(base, user, pwd):
    req = urllib.request.Request(base + "/api/auth/login",
                                 data=json.dumps({"username": user, "password": pwd}).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())["token"]


def health(base, timeout=3):
    try:
        with urllib.request.urlopen(base + "/health", timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False


def create_exp(api, name, island_id=None, workflow_ids=None):
    body = {"name": name}
    if island_id is not None:
        body["islandId"] = island_id
    if workflow_ids:
        body["workflowIds"] = workflow_ids
    code, j = api.call("POST", "/api/experiments", body)
    if code != 200:
        raise RuntimeError("创建实验失败: %s %s" % (code, j))
    eid = j.get("id") if isinstance(j, dict) else j
    return int(eid)


def create_workflow(api, name):
    """建一张空白流程（开始→结束，无设备任务）——给"有流程可执行"的实验做 UI 测试用。"""
    code, j = api.call("POST", "/api/workflows", {"name": name, "category": "choreography"})
    if code != 200:
        raise RuntimeError("创建流程失败: %s %s" % (code, j))
    return int(j["id"])


def cleanup_workflow(cur, conn, wf_id):
    """删测试用空白流程（节点/边级联；run/experiment 引用是 SET NULL）。"""
    try:
        cur.execute("DELETE FROM workflow WHERE id=%s", (wf_id,))
        conn.commit()
    except Exception:
        conn.rollback()


def cleanup_run(cur, conn, run_id):
    """删测试用的 run（run_node/run_command 级联）。"""
    try:
        cur.execute("DELETE FROM run WHERE id=%s", (run_id,))
        conn.commit()
    except Exception:
        conn.rollback()


def submit_async(api, eid, vars_=None):
    body = {"vars": vars_ or {}}
    return api.call("POST", "/api/experiments/%d/run?async=1" % eid, body)


def cleanup_exp(cur, conn, exp_id):
    cur.execute("DELETE FROM experiment_batch_run WHERE batch_id IN "
                "(SELECT id FROM experiment_batch WHERE experiment_id=%s)", (exp_id,))
    cur.execute("DELETE FROM experiment_batch WHERE experiment_id=%s", (exp_id,))
    cur.execute("DELETE FROM runtime_hold WHERE scope='experiment' AND scope_id=%s", (exp_id,))
    cur.execute("DELETE FROM experiment_run WHERE experiment_id=%s", (exp_id,))
    cur.execute("DELETE FROM experiment_sample WHERE experiment_id=%s", (exp_id,))
    cur.execute("DELETE FROM sample WHERE barcode LIKE 'S3RES-%'")
    cur.execute("DELETE FROM experiment_process WHERE experiment_id=%s", (exp_id,))
    cur.execute("DELETE FROM experiment WHERE id=%s", (exp_id,))
    conn.commit()


def log_size(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def log_since(path, offset):
    try:
        with open(path, "rb") as f:
            f.seek(offset)
            return f.read().decode("utf-8", "replace")
    except OSError:
        return ""


def api_pids():
    out = subprocess.run(["pgrep", "-f", "com.labos.kernel.api.ApiServer"],
                         capture_output=True, text=True).stdout.strip()
    return [int(x) for x in out.split()] if out else []


def detect_api_log(fallback):
    """当前 ApiServer 进程的 stdout 落到哪个文件（pgrep + lsof 反查）。

    为什么需要：脚本要在启动日志里找"待派发 rehydrate"/"启动收尾"这类行，
    但内核是运维手工起的，日志位置随启动方式而变（/tmp/apiserver.log、
    /tmp/api.log…）。写死 --api-log 会让日志类断言在别人的环境里假红。
    反查不到就退回 fallback（重启会 `>> fallback` 建出来，仍是自洽的）。
    """
    for pid in api_pids():
        try:
            out = subprocess.run(["lsof", "-p", str(pid)],
                                 capture_output=True, text=True).stdout
        except Exception:  # noqa: BLE001
            continue
        for line in out.splitlines():
            parts = line.split()
            # lsof 行：COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME
            if len(parts) >= 9 and parts[3] in ("1w", "2w") and parts[-1].endswith(".log"):
                return parts[-1]
    return fallback


def port_free(port=18080):
    import socket
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=0.3)
        s.close()
        return False
    except OSError:
        return True


def restart_api(log_path):
    """kill 所有 API 实例 -> 等端口真正释放 -> 起新进程 -> 等健康。

    为什么必须等端口释放：kill -9 后残留 JVM 可能还握着 18080 一小会儿，
    立刻起新进程会 HttpServer.create 绑定失败（脚本第二轮重启就踩过）。
    """
    env = dict(os.environ)
    cmd = ("cd %s/labos-kernel && nohup java -cp 'out:lib/postgresql-42.7.3.jar' "
           "com.labos.kernel.api.ApiServer 18080 >> %s 2>&1 & disown"
           % (ROOT, log_path))
    for _ in range(2):
        for pid in api_pids():
            subprocess.run(["kill", "-9", str(pid)], capture_output=True)
        deadline = time.time() + 25
        while time.time() < deadline:
            if port_free(18080) and not health("http://127.0.0.1:18080", timeout=1):
                break
            time.sleep(0.2)
        if not port_free(18080):
            time.sleep(1.0)   # 端口还没放，再等一拍
        subprocess.Popen(["bash", "-c", cmd], env=env, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
        deadline = time.time() + 60
        while time.time() < deadline:
            if health("http://127.0.0.1:18080"):
                return True
            time.sleep(0.3)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://127.0.0.1:18080")
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--api-log", default="/tmp/labos-api.log")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    # 日志路径自适应：内核多是运维手工起的，stdout 落到哪个文件得反查（pgrep+lsof）。
    # 写死 --api-log 会让"扣留解除自动续跑""启动兜底 settle"这类日志断言在别人的环境里假红。
    args.api_log = detect_api_log(args.api_log)

    conn = db_connect()
    cur = conn.cursor()
    api = Api(args.api, login_api(args.api, args.user, args.pwd))
    tag = int(time.time())
    created = []
    workflows_created = []
    runs_created = []

    try:
        # ================= 1. async 变量持久化（S3.2-C） =================
        step("1. async 变量 overrides 落库（S3.2-C）")
        eid = create_exp(api, "S3.2残余-变量-%d" % tag)
        created.append(eid)
        code, j = submit_async(api, eid, {"k": "v", "dose": "12"})
        ok("async 提交成功") if code == 200 and j.get("experimentRunId") else bad("提交失败", (code, j))
        time.sleep(3)
        cur.execute("SELECT variables->>'dose', variables->>'k' FROM experiment_run "
                    "WHERE experiment_id=%s ORDER BY id DESC LIMIT 1", (eid,))
        row = cur.fetchone()
        ok("run.variables 持久化用户变量（dose=12, k=v）") \
            if row == ("12", "v") else bad("variables 没落库", row)

        # ================= 2. 扣留解除自动续队（S3.2-B） =================
        step("2. 扣留解除后自动续队（S3.2-B）")
        eid2 = create_exp(api, "S3.2残余-续队-%d" % tag)
        created.append(eid2)
        cur.execute("UPDATE experiment SET batch_split=2 WHERE id=%s", (eid2,))
        barcodes = ["S3RES-%d-%d" % (tag, i) for i in range(3)]
        for i, bc in enumerate(barcodes):
            cur.execute("INSERT INTO sample (tenant_id, barcode, name, sample_type) "
                        "VALUES (1, %s, %s, 'serum') RETURNING id", (bc, "s3res%d" % i))
            sid = cur.fetchone()[0]
            cur.execute("INSERT INTO experiment_sample (experiment_id, sample_id, sample_role) "
                        "VALUES (%s, %s, 'sample')", (eid2, sid))
        cur.execute("INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason) "
                    "VALUES (1, 'experiment', %s, 'manual', 'S3.2残余联调扣留')", (eid2,))
        conn.commit()
        code, j = submit_async(api, eid2)
        held = (code == 200 and j.get("held") is True and j.get("experimentRunId") is None)
        ok("扣留中 async 返回 held:true 且不建 attempt") if held else bad("HELD 响应不对", (code, j))
        cur.execute("SELECT count(*) FROM experiment_batch_run WHERE batch_id IN "
                    "(SELECT id FROM experiment_batch WHERE experiment_id=%s)", (eid2,))
        ok("扣留中 attempt=0") if cur.fetchone()[0] == 0 else bad("扣留中却建了 attempt")
        # 解除扣留（直接删 hold 行），等调度器每 5 tick（~10s）自动续队
        cur.execute("DELETE FROM runtime_hold WHERE scope='experiment' AND scope_id=%s", (eid2,))
        conn.commit()
        offset = log_size(args.api_log)
        deadline = time.time() + 40
        attempts = 0
        while time.time() < deadline:
            cur.execute("SELECT count(*) FROM experiment_batch_run WHERE batch_id IN "
                        "(SELECT id FROM experiment_batch WHERE experiment_id=%s)", (eid2,))
            attempts = cur.fetchone()[0]
            if attempts >= 2:
                break
            time.sleep(0.5)
        ok("删除扣留后自动续队跑完 2 批（attempts=2）") if attempts >= 2 \
            else bad("自动续队没发生", attempts)
        new_log = log_since(args.api_log, offset)
        ok("调度日志有「扣留解除，自动续跑」") if "扣留解除，自动续跑实验 %d" % eid2 in new_log \
            else bad("日志没有自动续队记录")

        # ================= 4. 页面异步入口（S3.2 UI，§11.3-3） =================
        step("4. 页面「异步执行」入口（S3.2 UI）")
        # 空白流程（开始→结束，无设备任务）让实验"有编排"，按钮才可用；执行不碰设备。
        wf = create_workflow(api, "S3.2UI-空流程-%d" % tag)
        workflows_created.append(wf)
        eid4 = create_exp(api, "S3.2残余-UI异步-%d" % tag, workflow_ids=[wf])
        created.append(eid4)
        cur.execute("UPDATE experiment SET batch_split=2 WHERE id=%s", (eid4,))
        bcs = ["S3RES-UI-%d-%d" % (tag, i) for i in range(3)]
        for i, bc in enumerate(bcs):
            cur.execute("INSERT INTO sample (tenant_id, barcode, name, sample_type) "
                        "VALUES (1, %s, %s, 'serum') RETURNING id", (bc, "s3resui%d" % i))
            sid = cur.fetchone()[0]
            cur.execute("INSERT INTO experiment_sample (experiment_id, sample_id, sample_role) "
                        "VALUES (%s, %s, 'sample')", (eid4, sid))
        cur.execute("INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason) "
                    "VALUES (1, 'experiment', %s, 'manual', 'S3.2 UI 扣留')", (eid4,))
        conn.commit()

        def attempts_of(exp_id):
            cur.execute("SELECT count(*) FROM experiment_batch_run WHERE batch_id IN "
                        "(SELECT id FROM experiment_batch WHERE experiment_id=%s)", (exp_id,))
            return cur.fetchone()[0]

        with sync_playwright() as p:
            b = p.chromium.launch()
            page = b.new_page(viewport={"width": 1500, "height": 1000})
            page.set_default_timeout(25000)
            page.goto(args.base, wait_until="networkidle")
            if page.locator("text=流程库").count() == 0:
                page.locator('input[type="password"]').first.fill(args.pwd)
                ti = page.locator('input[type="text"]')
                if ti.count() and not ti.first.input_value():
                    ti.first.fill(args.user)
                page.get_by_role("button", name=re.compile(r"登\s*录")).click()
                page.wait_for_selector("text=流程库", timeout=25000)
            page.goto(args.base + "/experiments/" + str(eid4), wait_until="networkidle")
            page.wait_for_timeout(1800)
            btn = page.get_by_role("button", name="异步执行")
            ok("分批实验详情页有「异步执行」入口") if btn.count() > 0 else bad("缺「异步执行」入口")
            ok("有流程时「异步执行」可用（未被禁用）") if btn.count() > 0 and btn.first.is_enabled() \
                else bad("按钮被禁用")
            btn.first.click()
            page.wait_for_selector("text=现场扣留", timeout=10000)
            ok("扣留中提示「现场扣留」而不是已排队")
            ok("扣留中未建 attempt") if attempts_of(eid4) == 0 else bad("扣留中却建了 attempt")
            cur.execute("DELETE FROM runtime_hold WHERE scope='experiment' AND scope_id=%s",
                        (eid4,))
            conn.commit()
            deadline = time.time() + 60
            while time.time() < deadline and attempts_of(eid4) < 2:
                time.sleep(0.5)
            ok("解除扣留后自动续队跑完 2 批") if attempts_of(eid4) >= 2 \
                else bad("自动续队没发生", attempts_of(eid4))
            page.wait_for_timeout(1500)
            page.get_by_role("button", name="异步执行").first.click()
            page.wait_for_timeout(1800)
            toast = page.inner_text(".ant-message") if page.locator(".ant-message").count() else ""
            ok("再点「异步执行」给出明确结论（已排队 / 没有待执行批次）") \
                if ("已排队" in toast or "没有待执行批次" in toast) else bad("toast 不明确", toast)
            page.screenshot(path="/tmp/m9-async-batch.png")
            b.close()

        # ================= 3. 重启恢复（S3.2-A rehydrate + BUG-14 启动兜底 settle） =================
        step("3. 重启恢复（queued rehydrate + 崩溃时 running 孤儿 settle，BUG-14 端到端）")

        # --- 3a. queued run：重启后 rehydrate 装回内存队列（S3.2-A） ---
        eid3 = create_exp(api, "S3.2残余-rehydrate-%d" % tag)
        created.append(eid3)
        cur.execute("INSERT INTO experiment_run (tenant_id, experiment_id, status) "
                    "VALUES (1, %s, 'queued') RETURNING id", (eid3,))
        queued_run = cur.fetchone()[0]

        # --- 3b. 崩溃时"实验还在跑"：experiment_run=running + experiment=running ---
        # 这是进程被 kill -9 时最真实的残留形态（库里有 running，但没有任何线程持有）。
        eid5 = create_exp(api, "S3.2残余-崩溃实验-%d" % tag)
        created.append(eid5)
        cur.execute("INSERT INTO experiment_run (tenant_id, experiment_id, status, started_at) "
                    "VALUES (1, %s, 'running', now()) RETURNING id", (eid5,))
        orphan_exp_run = cur.fetchone()[0]
        cur.execute("UPDATE experiment SET status='running' WHERE id=%s", (eid5,))

        # --- 3c. 崩溃时"流程还在跑"：run=running + 一个已完成节点（→ 可恢复） ---
        crash_run_no = "S3RES-CRASH-%d" % tag
        cur.execute("INSERT INTO run (tenant_id, run_no, run_type, status) "
                    "VALUES (1, %s, 'workflow', 'running') RETURNING id", (crash_run_no,))
        orphan_run = cur.fetchone()[0]
        runs_created.append(orphan_run)
        cur.execute("INSERT INTO run_node (tenant_id, run_id, node_key, name, seq, status, level) "
                    "VALUES (1, %s, 'S1', 'S1', 0, 'completed', 0)", (orphan_run,))
        # 合法已完成（不许被兜底 settle 碰到），用来对照"只收 running"
        keep_run_no = "S3RES-KEEP-%d" % tag
        cur.execute("INSERT INTO run (tenant_id, run_no, run_type, status) "
                    "VALUES (1, %s, 'workflow', 'completed') RETURNING id", (keep_run_no,))
        keep_run = cur.fetchone()[0]
        runs_created.append(keep_run)

        # --- 3d. 现场扣留态 paused：重启兜底**不许**误杀 ---
        # StartupRecovery.settle 只收 running（及超 1 小时的 queued/pending）；
        # paused 是人工处置中的单子，被自动判死就是事故。必须钉住。
        paused_run_no = "S3RES-PAUSED-%d" % tag
        cur.execute("INSERT INTO run (tenant_id, run_no, run_type, status) "
                    "VALUES (1, %s, 'workflow', 'paused') RETURNING id", (paused_run_no,))
        paused_run = cur.fetchone()[0]
        runs_created.append(paused_run)

        # --- 3e. 排队僵尸：queued 且 created_at 超过 1 小时 ---
        # settle 的扫描条件：
        #   status='running' OR (status IN ('queued','pending') AND created_at < now()-1 hour)
        # 「刚入队几秒的正常记录」不能被误杀，但排了 1 小时以上的僵尸必须被收。
        # 这条分支此前无任何断言覆盖（2026-11-05 评审 P2-1）。
        zombie_run_no = "S3RES-ZOMBIE-%d" % tag
        cur.execute("INSERT INTO run (tenant_id, run_no, run_type, status, created_at) "
                    "VALUES (1, %s, 'workflow', 'queued', now() - interval '2 hours') "
                    "RETURNING id", (zombie_run_no,))
        zombie_run = cur.fetchone()[0]
        runs_created.append(zombie_run)
        # 反例对照：刚入队的 queued（几秒内）**不许**被收——否则会把正常排队误杀
        fresh_run_no = "S3RES-FRESH-%d" % tag
        cur.execute("INSERT INTO run (tenant_id, run_no, run_type, status) "
                    "VALUES (1, %s, 'workflow', 'queued') RETURNING id", (fresh_run_no,))
        fresh_run = cur.fetchone()[0]
        runs_created.append(fresh_run)
        conn.commit()

        offset = log_size(args.api_log)
        ok("API 已重启") if restart_api(args.api_log) else bad("API 重启失败")
        # 重启后先前 token 可能失效，重新登录
        api = Api(args.api, login_api(args.api, args.user, args.pwd))
        time.sleep(0.5)
        new_log = log_since(args.api_log, offset)

        # 3a 断言：rehydrate
        m = re.search(r"待派发 rehydrate：(\d+) 条", new_log)
        ok("启动日志报 rehydrate >=1 条") if m and int(m.group(1)) >= 1 \
            else bad("启动日志没有 rehydrate 记录", m.group(0) if m else new_log[-300:])
        deadline = time.time() + 40
        status = "queued"
        while time.time() < deadline:
            cur.execute("SELECT status FROM experiment_run WHERE id=%s", (queued_run,))
            status = cur.fetchone()[0]
            if status not in ("queued", "running"):
                break
            time.sleep(0.4)
        ok("rehydrate 后的 queued run 被派发并跑到终态（%s）" % status) \
            if status not in ("queued", "running") else bad("run 一直 queued", status)

        # 3b/3c 断言：启动兜底 settle（BUG-14）——running 孤儿必须被收掉
        ok("启动日志报被中断的 run >=1（run 表兜底）") \
            if re.search(r"发现 [1-9]\d* 个被中断的 run", new_log) \
            else bad("启动日志没有被中断 run 记录", new_log[-300:])
        ok("启动日志报孤儿 running settle >=1（experiment_run 兜底）") \
            if re.search(r"孤儿 running settle：[1-9]\d* 条", new_log) \
            else bad("启动日志没有 settle 记录", new_log[-300:])

        cur.execute("SELECT status, actual_end, input->>'interrupted', "
                    "input->>'recoverable', input->>'completedNodes' FROM run WHERE id=%s",
                    (orphan_run,))
        row = cur.fetchone()
        ok("崩溃时 running 的 run 落 failed") if row and row[0] == "failed" \
            else bad("run 没收尾", row)
        ok("run.actual_end 已落库") if row and row[1] is not None else bad("actual_end 为空", row)
        ok("run 标记 interrupted=true（被中断）") if row and row[2] == "true" \
            else bad("未标 interrupted", row)
        ok("有已完成节点 → recoverable=true") if row and row[3] == "true" \
            else bad("未标 recoverable", row)
        ok("completedNodes 计数正确（=1）") if row and row[4] == "1" \
            else bad("completedNodes 不对", row)

        cur.execute("SELECT status, error, finished_at FROM experiment_run WHERE id=%s",
                    (orphan_exp_run,))
        r2 = cur.fetchone()
        ok("崩溃时 running 的 experiment_run 落 failed") if r2 and r2[0] == "failed" \
            else bad("experiment_run 没收尾", r2)
        ok("experiment_run.error 写启动兜底文案") \
            if r2 and r2[1] == "API 重启：遗留 running 由启动兜底 settle（无活动线程持有）" \
            else bad("error 文案不对", r2)
        ok("experiment_run.finished_at 落库") if r2 and r2[2] is not None \
            else bad("finished_at 为空", r2)
        cur.execute("SELECT status FROM experiment WHERE id=%s", (eid5,))
        ok("实验状态同步 failed") if cur.fetchone()[0] == "failed" \
            else bad("实验状态没同步")
        cur.execute("SELECT status FROM run WHERE id=%s", (keep_run,))
        ok("合法 completed run 未被触碰") if cur.fetchone()[0] == "completed" \
            else bad("合法 run 被改")

        # 3d 断言：paused（现场扣留态）不得被启动兜底 settle 误杀
        cur.execute("SELECT status FROM run WHERE id=%s", (paused_run,))
        p1 = cur.fetchone()
        ok("paused（现场扣留态）run 未被兜底 settle 误杀，仍是 paused") \
            if p1 and p1[0] == "paused" else bad("paused run 被误杀", p1)

        # 3e 断言：排队僵尸（queued 超 1 小时）必须被收成 failed
        cur.execute("SELECT status FROM run WHERE id=%s", (zombie_run,))
        z1 = cur.fetchone()
        ok("排队僵尸 run（queued 超 1 小时）被收成 failed") \
            if z1 and z1[0] == "failed" else bad("僵尸 run 没被收", z1)
        # 反例：刚入队的 queued 不得被误杀（settle 的 1 小时宽限在此生效）
        cur.execute("SELECT status FROM run WHERE id=%s", (fresh_run,))
        f1 = cur.fetchone()
        ok("刚入队的 queued run 未被误杀（1 小时宽限生效，仍是 queued）") \
            if f1 and f1[0] == "queued" else bad("刚入队的 queued 被误杀", f1)

        ok("API 重启后仍可用") if health(args.api) else bad("重启后 API 不可用")

        # 再重启一次：验证启动兜底 settle 幂等——已被收掉的孤儿不会被二次改判/重复收。
        # 为什么不断言"全局 rehydrate=0"：那是全库计数，库里任何一条合法 queued run
        # 都会让它非 0，断言全局为 0 会把"别人有排队"误判成本用例失败。幂等性必须落到
        # 本用例自己造的实体上。
        offset = log_size(args.api_log)
        ok("二次重启成功") if restart_api(args.api_log) else bad("二次重启失败")
        api = Api(args.api, login_api(args.api, args.user, args.pwd))
        time.sleep(0.5)
        cur.execute("SELECT status FROM run WHERE id=%s", (orphan_run,))
        row = cur.fetchone()
        ok("二次重启后孤儿 run 仍是 failed（幂等，不重复收）") \
            if row and row[0] == "failed" else bad("孤儿 run 状态漂移", row)
        cur.execute("SELECT status, error FROM experiment_run WHERE id=%s", (orphan_exp_run,))
        r3 = cur.fetchone()
        ok("二次重启后孤儿 experiment_run 仍是 failed（幂等）") \
            if r3 and r3[0] == "failed" else bad("孤儿 experiment_run 状态漂移", r3)
    except Exception as e:  # noqa: BLE001
        bad("异常", str(e)[:240])
    finally:
        try:
            conn.rollback()
            for eid in created:
                cleanup_exp(cur, conn, eid)
            for wf in workflows_created:
                cleanup_workflow(cur, conn, wf)
            for rid in runs_created:
                cleanup_run(cur, conn, rid)
            print("\n已清理联调数据（含测试用空白流程与 run）")
        except Exception as e:  # noqa: BLE001
            print("清理失败：" + str(e))
        conn.close()
        if not health(args.api):
            print("警告：API 当前不可用，尝试拉起…")
            restart_api(args.api_log)

    print()
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
