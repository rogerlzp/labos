#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S3.2 残余收尾联调：async 变量持久化 / 扣留解除自动续队 / 重启 rehydrate 待派发。

全部用"没有编排任何流程的空实验"（执行器展开为空立即失败，不碰设备），
走的却是真实调度链路（排队/派发/批续队/rehydrate）。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/verify-s3-2-residuals.py [--api http://127.0.0.1:18080] [--api-log /tmp/labos-api.log]

注意：Case 3 会 kill 并重启 18080 的 API 进程（用同一组 LABOS_DB_* 环境变量），
      跑之前确认没有别的测试/验收在跑。
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

    conn = db_connect()
    cur = conn.cursor()
    api = Api(args.api, login_api(args.api, args.user, args.pwd))
    tag = int(time.time())
    created = []
    workflows_created = []

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

        # ================= 3. 重启 rehydrate 待派发（S3.2-A） =================
        step("3. 重启 rehydrate 待派发（S3.2-A）")
        eid3 = create_exp(api, "S3.2残余-rehydrate-%d" % tag)
        created.append(eid3)
        cur.execute("INSERT INTO experiment_run (tenant_id, experiment_id, status) "
                    "VALUES (1, %s, 'queued') RETURNING id", (eid3,))
        queued_run = cur.fetchone()[0]
        conn.commit()
        offset = log_size(args.api_log)
        ok("API 已重启") if restart_api(args.api_log) else bad("API 重启失败")
        # 重启后先前 token 可能失效，重新登录
        api = Api(args.api, login_api(args.api, args.user, args.pwd))
        time.sleep(0.5)
        new_log = log_since(args.api_log, offset)
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
        ok("API 重启后仍可用") if health(args.api) else bad("重启后 API 不可用")

        # 再重启一次：无残留 -> rehydrate 0 条（幂等）
        offset = log_size(args.api_log)
        ok("二次重启成功") if restart_api(args.api_log) else bad("二次重启失败")
        api = Api(args.api, login_api(args.api, args.user, args.pwd))
        time.sleep(0.5)
        new_log = log_since(args.api_log, offset)
        m2 = re.search(r"待派发 rehydrate：(\d+) 条", new_log)
        ok("二次重启 rehydrate=0（幂等）") if m2 and int(m2.group(1)) == 0 \
            else bad("二次重启 rehydrate 非 0", m2.group(0) if m2 else new_log[-200:])
    except Exception as e:  # noqa: BLE001
        bad("异常", str(e)[:240])
    finally:
        try:
            conn.rollback()
            for eid in created:
                cleanup_exp(cur, conn, eid)
            for wf in workflows_created:
                cleanup_workflow(cur, conn, wf)
            print("\n已清理联调数据（含测试用空白流程）")
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
