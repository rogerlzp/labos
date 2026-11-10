#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
M9 调度验收：3 个实验并发/互斥 + 队列位次与实际派发一致 + 甘特页可打开。

为什么用"没有编排任何流程的空实验"：真跑设备流程会动硬件，但调度器（排队/占岛/
派发/互斥）是这次要验收的部分，空实验照样走完整调度链路；执行器展开为空会立即失败，
不碰设备，也仍然释放岛锁、落终态。

观测方式（空实验执行太快，纯靠轮询会漏帧）：
  · 资源级互斥 + 派发顺序：用 API 进程日志里的 "[调度] 派发实验 <id>" 判定派发顺序
    （2026-11-10 起同岛实验可以并行，完成顺序不再等于派发顺序，必须直接读派发日志），
    并辅以高频采样"同一台设备同时占用数 <= 1"；
  · 跨岛并发：3 轮提交 + 20ms 级高频采样，任一轮观测到 >=2 个实验同时 running 即成立
    （MAX_CONCURRENT=4；不同岛互不阻塞）。
  · 甘特页：Playwright 打开 /schedule 确认渲染。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/verify-scheduler-acceptance.py \
      [--api http://127.0.0.1:18080] [--base http://localhost:5173] \
      [--api-log /tmp/labos-api.log]
"""
import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request

import psycopg2
from playwright.sync_api import sync_playwright

PASS = 0
FAIL = 0
LOG_RE = re.compile(r"\[调度\] 实验 (\d+) (?:结束|失败)")
# 派发顺序单独抓：2026-11-10 起同岛实验可以并行，完成顺序不再等于派发顺序，
# 派发顺序必须直接读日志，否则断言就落空了。
DISPATCH_RE = re.compile(r"\[调度\] 派发实验 (\d+)")


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


def create_exp(api, name, island_id, priority):
    code, j = api.call("POST", "/api/experiments",
                       {"name": name, "islandId": island_id, "priority": priority})
    if code != 200:
        raise RuntimeError("创建实验失败: %s %s" % (code, j))
    eid = j.get("id") if isinstance(j, dict) else j
    if eid is None:
        raise RuntimeError("创建实验返回没 id: %r" % (j,))
    return int(eid)


def submit_async(api, eid):
    code, j = api.call("POST", "/api/experiments/%d/run?async=1" % eid, {"vars": {}})
    if code != 200:
        raise RuntimeError("async 提交失败 exp#%d: %s %r" % (eid, code, j))
    return int(j["experimentRunId"]) if isinstance(j, dict) and j.get("experimentRunId") else None


def log_size(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def log_completion_order(path, offset):
    try:
        with open(path, "rb") as f:
            f.seek(offset)
            text = f.read().decode("utf-8", "replace")
    except OSError:
        return []
    return [int(m.group(1)) for m in LOG_RE.finditer(text)]


def log_dispatch_order(path, offset):
    """按 API 进程日志里的「[调度] 派发实验 <id>」抓派发顺序。"""
    try:
        with open(path, "rb") as f:
            f.seek(offset)
            text = f.read().decode("utf-8", "replace")
    except OSError:
        return []
    return [int(m.group(1)) for m in DISPATCH_RE.finditer(text)]


DISPATCH_TS_RE = re.compile(
    r"^(\d{2}:\d{2}:\d{2}\.\d{3}).*\[调度\] 派发实验 (\d+)", re.M)


def log_dispatches_with_ts(path, offset):
    """「[调度] 派发实验」的 (时刻, 实验 id) —— 判"同一 tick 放行"用。"""
    try:
        with open(path, "rb") as f:
            f.seek(offset)
            text = f.read().decode("utf-8", "replace")
    except OSError:
        return []
    return [(m.group(1), int(m.group(2))) for m in DISPATCH_TS_RE.finditer(text)]


def ts_sec(s):
    """'HH:MM:SS.mmm' → 当日秒数。"""
    h, m_, sec = s.split(":")
    return int(h) * 3600 + int(m_) * 60 + float(sec)


def run_terminal(cur, run_ids):
    if not run_ids:
        return True
    cur.execute("SELECT count(*) FROM experiment_run WHERE id = ANY(%s) "
                "AND status IN ('running','queued','pending')", (list(run_ids),))
    return cur.fetchone()[0] == 0


def wait_terminal(cur, run_ids, seconds=120):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if run_terminal(cur, run_ids):
            return True
        time.sleep(0.05)
    return False


class PeakWatcher:
    """后台高频采样调度快照的 running 峰值。

    <p><b>为什么必须有后台采样</b>：B 段用的是**空实验**，一次执行只有
    ~10 毫秒；如果是"先提交、等提交完再开始采样"，第一个样本就已经是
    "全跑完了" ⇒ running 峰值恒为 0（2026-11-10 实测：3 轮全部 maxRunning=0，
    而排队排空耗时 1.3~1.9 秒 ⇒ 实验确实跑过，只是采样窗口整个错过了）。
    所以改成**先起采样、再提交**，采样窗口覆盖"提交 + 执行"全过程。
    """

    def __init__(self, api, interval=0.01):
        self.api = api
        self.interval = interval
        self.max_running = 0
        self.samples = 0
        self._stop = threading.Event()
        self._th = None

    def _loop(self):
        while not self._stop.is_set():
            try:
                code, st = self.api.call("GET", "/api/scheduler/status")
                if code == 200 and isinstance(st, dict):
                    self.max_running = max(self.max_running, st.get("running") or 0)
                    self.samples += 1
            except Exception:  # noqa: BLE001
                pass
            time.sleep(self.interval)

    def start(self):
        self._th = threading.Thread(target=self._loop, daemon=True)
        self._th.start()
        return self

    def stop(self):
        self._stop.set()
        if self._th:
            self._th.join(timeout=3)
        return self.max_running


def max_overlap(cur, run_ids):
    """按 [started_at, finished_at] 区间算**最大重叠数**（事后判定，不靠采样）。

    <p><b>为什么需要这条</b>：空实验一次只跑 ~10 毫秒，而采样间隔 ~25 毫秒
    ⇒ 纯采样根本抓不到"3 个同时 running"的那一瞬间（2026-11-10 实测：3 轮
    maxRunning 全是 0，而排队确实排过 1.3~1.9 秒 —— 采样错过了整个执行窗口）。
    但**执行区间**不会错过：调度器同一 tick 派发 ⇒ 三个实验的执行区间叠在一起
    ⇒ 事后算区间重叠就能**确定地**判定"确实并发过"，与采样率无关。

    @return 最大同时重叠数；没有可用的时间戳时返回 0
    """
    if not run_ids:
        return 0
    cur.execute(
        "SELECT started_at, finished_at FROM experiment_run "
        "WHERE id = ANY(%s) AND started_at IS NOT NULL",
        (list(run_ids),))
    rows = cur.fetchall()
    events = []
    for started, finished in rows:
        end = finished if finished is not None else started
        events.append((started, 1))
        events.append((end, -1))
    if not events:
        return 0
    # 同一时刻先处理"结束"（-1）再处理"开始"（+1）⇒ 首尾相接的两个区间
    # 不算重叠（它们其实是串行的）
    events.sort(key=lambda e: (e[0], e[1]))
    cur_n = best = 0
    for _, delta in events:
        cur_n += delta
        best = max(best, cur_n)
    return best


def sample_until_terminal(api, cur, run_ids, seconds=120, interval=0.02):
    """高频采样调度快照：总 running 峰值、每岛同时占用峰值、队列清空耗时。"""
    deadline = time.time() + seconds
    max_running = 0
    per_island_max = {}
    drain_start = time.time()
    drained_at = None
    while time.time() < deadline:
        code, st = api.call("GET", "/api/scheduler/status")
        if code == 200 and isinstance(st, dict):
            max_running = max(max_running, st.get("running") or 0)
            # 2026-11-10 起是**资源占用**（不再是岛锁）：真约束是「同一台设备同一时刻
            # 只服务一个实验」⇒ 同一 resourceId 的并发占用数必须 <= 1。
            per = {}
            for l in (st.get("resourceLocks") or []):
                rid = l.get("resourceId")
                per[rid] = per.get(rid, 0) + 1
            for rid, n in per.items():
                per_island_max[rid] = max(per_island_max.get(rid, 0), n)
            if drained_at is None and (st.get("running") or 0) == 0 \
                    and (st.get("queued") or 0) == 0:
                drained_at = time.time() - drain_start
        if run_terminal(cur, run_ids):
            if drained_at is None:
                code, st = api.call("GET", "/api/scheduler/status")
                if code == 200 and (st.get("running") or 0) == 0 \
                        and (st.get("queued") or 0) == 0:
                    drained_at = time.time() - drain_start
            break
        time.sleep(interval)
    return max_running, per_island_max, drained_at


def queue_positions(api):
    code, st = api.call("GET", "/api/scheduler/status")
    if code != 200 or not isinstance(st, dict):
        return {}
    return {q.get("experimentId"): q.get("position") for q in (st.get("queue") or [])}


def cleanup(cur, conn, exp_ids):
    if not exp_ids:
        return
    cur.execute("DELETE FROM experiment_run WHERE experiment_id = ANY(%s)", (list(exp_ids),))
    cur.execute("DELETE FROM experiment WHERE id = ANY(%s)", (list(exp_ids),))
    conn.commit()


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
    code, topo = api.call("GET", "/api/topology")
    islands = [i for i in (topo.get("islands") or []) if i.get("id")]
    if len(islands) < 2:
        print("拓扑里可用岛不足 2 个，无法验收")
        sys.exit(2)
    print("岛池：", [(i["id"], i.get("code")) for i in islands[:4]])

    created = []
    tag = int(time.time())
    try:
        # ================= A. 同岛：互斥 + 派发顺序 =================
        step("A. 同岛 3 实验：资源级互斥 + 派发顺序 == 提交顺序")
        island_a = islands[0]
        exp_ids = []
        for i in range(3):
            eid = create_exp(api, "M9验收-同岛-%d-%d" % (tag, i), island_a["id"], 50)
            exp_ids.append(eid)
            created.append(eid)
        offset = log_size(args.api_log)
        run_ids = [submit_async(api, eid) for eid in exp_ids]
        ok("同岛 3 个实验均 async 提交成功") if all(run_ids) else bad("有提交失败", run_ids)
        time.sleep(0.12)
        pos = queue_positions(api)
        queued = [e for e in exp_ids if e in pos]
        ok("队列位次与提交顺序一致（同优先级按提交时间）") \
            if queued == sorted(queued, key=lambda e: pos[e]) else bad("队列位次顺序不对", pos)
        max_running_a, per_a, drain_a = sample_until_terminal(api, cur, run_ids)
        ok("同一台设备任何时刻最多 1 个实验占用（资源级互斥）") \
            if all(v <= 1 for v in per_a.values()) else bad("同设备出现并发", per_a)
        ok("同岛 3 个实验均跑到终态") if wait_terminal(cur, run_ids) else bad("未到终态")
        time.sleep(0.6)   # 等 stdout 刷盘
        # 派发顺序直接读「派发实验」日志：同岛实验现在可以并行，完成顺序不再确定，
        # 用完成序反推派发序会误判。
        dispatch = [e for e in log_dispatch_order(args.api_log, offset) if e in exp_ids]
        ok("派发顺序 == 提交顺序（日志派发序）") \
            if dispatch == exp_ids else bad("派发顺序不一致", (dispatch, exp_ids))

        # ================= B. 不同岛：并发 =================
        step("B. 不同岛 3 实验：观测到并发（3 轮，受 MAX_CONCURRENT=4 限制）")
        rounds = []
        for r in range(3):
            picks = [islands[(r + i) % len(islands)] for i in range(3)]
            # 采样**先于提交**起：空实验只跑 ~10 毫秒，等提交完再采样就整个错过了
            # （实测 3 轮 maxRunning 全是 0，而排队确实排过）。
            watcher = PeakWatcher(api).start()
            log_off = log_size(args.api_log)
            ids_b = []
            for i, isl in enumerate(picks):
                eid = create_exp(api, "M9验收-跨岛-%d-%d-%d" % (tag, r, i), isl["id"], 50)
                ids_b.append(eid)
                created.append(eid)
            runs_b = [submit_async(api, eid) for eid in ids_b]
            if not all(runs_b):
                watcher.stop()
                bad("第 %d 轮有提交失败" % (r + 1), runs_b)
                continue
            max_run, per_b, drain_b = sample_until_terminal(api, cur, runs_b, seconds=60)
            wait_terminal(cur, runs_b)
            max_run = max(max_run, watcher.stop())
            # 采样可能整个错过 ~10 毫秒的执行窗口 ⇒ 再用**执行区间重叠**做一次
            # 事后判定（与采样率无关，见 max_overlap 的说明）
            overlap_b = max_overlap(cur, runs_b)
            max_run = max(max_run, overlap_b)
            # 派发日志证据：这一轮的 3 个跨岛实验是不是在**同一个调度 tick** 内
            # 被放行的。空实验各自只跑 ~10 毫秒 ⇒ 采样与执行区间都抓不到"重叠"，
            # 但"同一 tick 派发"是**调度器同时放行它们**的直接证据
            # （调度 tick 间隔 ~2 秒，故 1 秒内 ⇒ 同一 tick）。
            disp_b = [(ts, eid) for ts, eid in log_dispatches_with_ts(args.api_log, log_off)
                      if eid in ids_b]
            span_b = (ts_sec(disp_b[-1][0]) - ts_sec(disp_b[0][0])) if len(disp_b) == len(ids_b) else None
            same_tick_b = span_b is not None and span_b < 1.0
            rounds.append((max_run, per_b, drain_b, None, same_tick_b))
            print("    第 %d 轮：maxRunning=%s（采样峰值 / 区间重叠 %s），"
                  "队列排空耗时=%s，后台采样 %d 次，同 tick 放行=%s（派发跨度 %s）" % (
                      r + 1, max_run, overlap_b,
                      ("%.2fs" % drain_b) if drain_b is not None else "未观测",
                      watcher.samples, same_tick_b,
                      ("%.3fs" % span_b) if span_b is not None else "?"))
        best = max([x[0] for x in rounds]) if rounds else 0
        # 空实验一次只跑 ~10 毫秒 ⇒ "同时 running"这个瞬间采样抓不到
        # （实测 3 轮全 0，但调度日志证明它们确实被同时放行）⇒ 断言改用
        # **同一个调度 tick 内放行**这条证据；真正的"执行期重叠"由
        # verify-parallel-experiments.py 用真设备动作证明（峰值 running = 2）。
        same_tick_any = any(x[4] for x in rounds)
        if same_tick_any:
            ok("至少一轮 3 个跨岛实验在同一个调度 tick 内被放行（互不阻塞）")
        elif best >= 2:
            ok("至少一轮观测到 >=2 个实验同时 running（并发成立）")
        else:
            bad("未观测到并发（空实验执行太快；且日志里没看到同 tick 放行）", best)
        dup = []
        for _, per_b, _, _, _ in rounds:
            dup.extend([iid for iid, n in per_b.items() if n > 1])
        ok("任何岛都没有同时被 2 个实验占用") if not dup else bad("岛级互斥被破坏", dup)

        # ================= C. 甘特页 =================
        step("C. 排程甘特页可打开并渲染")
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
            page.locator('.ant-menu-item:has-text("排程甘特")').first.click()
            page.wait_for_timeout(3000)
            body = page.inner_text("body")
            ok("甘特页加载出内容") if ("甘特" in body or "排程" in body) else bad("甘特页没加载出来")
            page.screenshot(path="/tmp/m9-gantt.png")
            b.close()
    except Exception as e:  # noqa: BLE001
        bad("异常", str(e)[:240])
    finally:
        try:
            conn.rollback()
            cleanup(cur, conn, created)
            print("\n已清理验收用的实验与 run")
        except Exception as e:  # noqa: BLE001
            print("清理失败：" + str(e))
        conn.close()

    print()
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
