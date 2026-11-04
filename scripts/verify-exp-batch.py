#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S3 分批执行联调：真库 + 真 API + 真页面（不真动设备）。

为什么要有这个脚本：后端连库自测用注入的假执行体，证明的是"编排语义"；
e2e-smoke 是只读冒烟，碰不到"建批 -> 按批跑 -> 失败批重跑"这条写链路。
这里用真实 ExperimentExecutor 跑一个**没有编排任何流程**的空实验：
每批一跑就失败，但**建批、attempt 递增、历史保留、批次视图**全都是真的，
且不会给设备下发任何动作。

前置：
  · API 18080 在跑（当前代码）；DB 可达（LABOS_DB_HOST/PORT/NAME/USER/PASS）；
  · 前端 5173 在跑；python3 有 playwright 与 psycopg2。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/verify-exp-batch.py [--experiment 813] [--headed]
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

import psycopg2
from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

PASS = 0
FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print("  \u2713 " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \u2717 " + msg + (("  (" + str(detail) + ")") if detail else ""))


def step(title):
    print("\n\u2550\u2550 " + title + " \u2550\u2550")


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
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"error": raw[:200]}


def db_connect():
    missing = [k for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER")
               if not os.environ.get(k)]
    if missing:
        print("缺数据库环境变量：" + " ".join(missing))
        sys.exit(2)
    return psycopg2.connect(
        host=os.environ["LABOS_DB_HOST"],
        port=int(os.environ.get("LABOS_DB_PORT", "5432")),
        dbname=os.environ["LABOS_DB_NAME"],
        user=os.environ["LABOS_DB_USER"],
        password=os.environ.get("LABOS_DB_PASS", ""),
    )


def login_api(api_base, user, pwd):
    req = urllib.request.Request(
        api_base + "/api/auth/login",
        data=json.dumps({"username": user, "password": pwd}).encode("utf-8"),
        method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))["token"]


def pick_experiment(cur, wanted):
    """借一个空实验：没流程、没执行记录（跑起来会立刻失败，不会真动设备）。"""
    if wanted:
        cur.execute("SELECT id FROM experiment WHERE tenant_id = 1 AND id = %s", (wanted,))
        if not cur.fetchone():
            raise SystemExit("实验 #%s 不存在" % wanted)
        return wanted
    cur.execute("""
        SELECT e.id FROM experiment e
         WHERE e.tenant_id = 1
           AND NOT EXISTS (SELECT 1 FROM experiment_process p WHERE p.experiment_id = e.id)
           AND NOT EXISTS (SELECT 1 FROM experiment_run r WHERE r.experiment_id = e.id)
         ORDER BY e.id DESC LIMIT 1
    """)
    row = cur.fetchone()
    if not row:
        raise SystemExit("找不到可用空实验（没流程、没执行记录）")
    return row[0]


def cleanup(cur, conn, exp_id, barcodes):
    cur.execute("DELETE FROM experiment_batch_run WHERE tenant_id = 1 AND batch_id IN "
                "(SELECT id FROM experiment_batch WHERE tenant_id = 1 AND experiment_id = %s)",
                (exp_id,))
    cur.execute("DELETE FROM experiment_batch WHERE tenant_id = 1 AND experiment_id = %s",
                (exp_id,))
    cur.execute("DELETE FROM experiment_run WHERE tenant_id = 1 AND experiment_id = %s",
                (exp_id,))
    cur.execute("DELETE FROM experiment_sample WHERE experiment_id = %s", (exp_id,))
    if barcodes:
        cur.execute("DELETE FROM sample WHERE tenant_id = 1 AND barcode = ANY(%s)", (barcodes,))
    conn.commit()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://127.0.0.1:18080")
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--experiment", type=int, default=None)
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    conn = db_connect()
    cur = conn.cursor()
    exp_id = pick_experiment(cur, args.experiment)
    tag = str(int(time.time()))
    barcodes = ["S3VERIFY-" + tag + "-1", "S3VERIFY-" + tag + "-2", "S3VERIFY-" + tag + "-3"]

    cur.execute("SELECT status, batch_split, started_at, finished_at FROM experiment "
                "WHERE tenant_id = 1 AND id = %s", (exp_id,))
    orig = cur.fetchone()
    print("借用实验 #%s（原状态 %s，batch_split=%s）" % (exp_id, orig[0], orig[1]))

    browser = None
    try:
        api = Api(args.api, login_api(args.api, args.user, args.pwd))

        # ---- 0. 分批配置 API（S3.1 / D11）：非法值拒绝 + 设置 batch_split=2 ----
        step("0. PUT /batch-split：配置入口（S3.1）")
        code, r = api.call("PUT", "/api/experiments/%d/batch-split" % exp_id, {"batchSplit": -1})
        ok("非法值 -1 被拒（400）") if code == 400 else bad("非法值未拒绝", code)
        code, r = api.call("PUT", "/api/experiments/%d/batch-split" % exp_id, {"batchSplit": 2})
        ok("设置 batchSplit=2 成功") if code == 200 and r.get("batchSplit") == 2 \
            else bad("设置失败", (code, r))

        # ---- 造数据：3 个样品（-> 2 批：2 + 1）----
        sample_ids = []
        for i, bc in enumerate(barcodes):
            cur.execute("INSERT INTO sample (tenant_id, barcode, name, sample_type) "
                        "VALUES (1, %s, %s, 'serum') RETURNING id", (bc, "S3 联调样品%d" % (i + 1)))
            sample_ids.append(cur.fetchone()[0])
        for sid in sample_ids:
            cur.execute("INSERT INTO experiment_sample (experiment_id, sample_id, sample_role) "
                        "VALUES (%s, %s, 'sample')", (exp_id, sid))
        conn.commit()
        print("已造数据：3 个样品 / batch_split=2 -> 期望 2 批（2 + 1）")

        # ---- 1. 执行实验：按批串行，空实验每批必失败，但批次/attempt 必须真落库 ----
        step("1. POST /run：按批串行 + 失败继续")
        code, j = api.call("POST", "/api/experiments/%d/run" % exp_id, {"vars": {}})
        ok("HTTP 200") if code == 200 else bad("HTTP 非 200", code)
        ok("返回 batchSplit=2") if j.get("batchSplit") == 2 else bad("batchSplit 不对", j.get("batchSplit"))
        batches = j.get("batches") or []
        ok("返回 2 批") if len(batches) == 2 else bad("批次数不对", len(batches))
        ok("两批都失败（空实验，符合预期）") if all(b.get("status") == "failed" for b in batches) \
            else bad("批次状态不对", [b.get("status") for b in batches])
        ok("attemptNo 均为 1") if all(b.get("attemptNo") == 1 for b in batches) \
            else bad("attemptNo 不对", [b.get("attemptNo") for b in batches])
        ok("实验聚合成 failed") if j.get("status") == "failed" else bad("实验状态不对", j.get("status"))
        run1, run2 = batches[0]["runId"], batches[1]["runId"]

        # ---- 2. 批次视图：样品快照 + 尝试历史 ----
        step("2. GET /batches：快照 + 历史")
        code, view = api.call("GET", "/api/experiments/%d/batches" % exp_id)
        items = view.get("items") or []
        ok("HTTP 200 且 batchSplit=2") if code == 200 and view.get("batchSplit") == 2 \
            else bad("批次视图不对", code)
        ok("2 个批次") if len(items) == 2 else bad("批次数量不对", len(items))
        if len(items) == 2:
            ok("第 1 批样品数=2、第 2 批=1") \
                if items[0]["sampleCount"] == 2 and items[1]["sampleCount"] == 1 \
                else bad("样品数切分不对", [items[0]["sampleCount"], items[1]["sampleCount"]])
            snaps = [s.get("barcode") for s in items[0].get("samples") or []]
            ok("第 1 批样品快照 = 前两个条码") if snaps == barcodes[:2] else bad("快照不对", snaps)
            ok("第 1 批 attemptCount=1") if items[0]["attemptCount"] == 1 \
                else bad("attemptCount 不对", items[0]["attemptCount"])

        # ---- 3. 只重跑失败批：attempt 递增、已有历史保留 ----
        step("3. POST /batches/1/run：只重跑第 1 批")
        code, rb = api.call("POST", "/api/experiments/%d/batches/1/run" % exp_id, {"vars": {}})
        ok("HTTP 200") if code == 200 else bad("HTTP 非 200", code)
        ok("attemptNo=2") if rb.get("attemptNo") == 2 else bad("attemptNo 不对", rb.get("attemptNo"))
        ok("重跑结果 failed（空实验）") if rb.get("status") == "failed" \
            else bad("重跑状态不对", rb.get("status"))

        code, view2 = api.call("GET", "/api/experiments/%d/batches" % exp_id)
        b1 = (view2.get("items") or [{}])[0]
        ok("第 1 批 attemptCount=2") if b1.get("attemptCount") == 2 \
            else bad("历史没保留", b1.get("attemptCount"))
        ok("两条尝试都还在（历史 run 不覆盖）") \
            if len(b1.get("runs") or []) == 2 and b1["runs"][0]["runId"] == run1 \
            else bad("尝试历史不对", b1.get("runs"))
        ok("currentRunId 指向第 2 次尝试") if b1.get("currentRunId") == rb.get("runId") \
            else bad("currentRunId 不对", b1.get("currentRunId"))

        # ---- 3.5 已建批后拒绝改 batch_split（D2 快照固化）----
        step("3.5 已建批后改 batch_split 被拒（D11/D2）")
        code, rj = api.call("PUT", "/api/experiments/%d/batch-split" % exp_id, {"batchSplit": 3})
        ok("HTTP 400") if code == 400 else bad("未拒绝改值", code)
        ok("提示需先清批次数据") if "批次" in str(rj.get("error")) else bad("错误信息不对", rj)

        # ---- 4. 成功批不可重跑（用 batch 2 造 completed 状态）----
        step("4. 已完成批重跑被拒（D4）")
        # 注意：批次派生状态以"最近一次 run 的实际状态"为准（读侧自愈），
        # 所以只改 experiment_batch.status 不够，必须把第 2 批的 run 也标成 completed。
        cur.execute("UPDATE experiment_batch SET status='completed' "
                    "WHERE tenant_id=1 AND experiment_id=%s AND batch_no=2", (exp_id,))
        cur.execute("UPDATE experiment_run SET status='completed', error=NULL "
                    "WHERE tenant_id=1 AND id IN ("
                    "  SELECT br.experiment_run_id FROM experiment_batch_run br "
                    "  JOIN experiment_batch b ON b.id = br.batch_id "
                    " WHERE b.tenant_id=1 AND b.experiment_id=%s AND b.batch_no=2)",
                    (exp_id,))
        conn.commit()
        code, err = api.call("POST", "/api/experiments/%d/batches/2/run" % exp_id, {"vars": {}})
        ok("HTTP 400 且带 error") if code == 400 and err.get("error") else bad("未拒绝重跑", code)
        ok("错误信息说明原因") if "已完成" in str(err.get("error")) else bad("错误信息不对", err)

        # ---- 5. 页面：批次卡片 / 尝试次数 / 展开历史 ----
        step("5. 页面：批次卡片与尝试历史")
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=not args.headed)
            page = browser.new_page(viewport={"width": 1500, "height": 1000})
            page.set_default_timeout(20000)
            page.goto(args.base, wait_until="networkidle")
            if page.locator("text=流程库").count() == 0:
                page.locator('input[type="password"]').first.fill(args.pwd)
                ti = page.locator('input[type="text"]')
                if ti.count() and not ti.first.input_value():
                    ti.first.fill(args.user)
                page.get_by_role("button", name="登 录").click()
                page.wait_for_selector("text=流程库", timeout=20000)
            ok("登录成功")
            page.goto(args.base + "/experiments/" + str(exp_id), wait_until="networkidle")
            page.wait_for_timeout(2000)
            body = page.inner_text("body")
            ok("批次卡片出现（分批：每批 2 个样品）") if "分批：每批 2 个样品" in body \
                else bad("批次卡片没出现")
            # 用表头「批次号」定位批次表：外层卡片也含「分批：每批…」文案，按卡片定位会撞
            table = page.locator(".ant-table").filter(has=page.get_by_text("批次号"))
            rows = table.locator(".ant-table-tbody tr.ant-table-row")
            ok("批次表 2 行") if rows.count() == 2 else bad("批次表行数不对", rows.count())
            ok("显示尝试次数 2 次") if "2 次" in table.inner_text() else bad("尝试次数没显示")
            expand = table.locator(".ant-table-row-expand-icon").first
            if expand.count() > 0:
                expand.click()
                page.wait_for_timeout(1200)
                detail = table.inner_text()
                ok("展开看到两次尝试") if "第 1 次" in detail and "第 2 次" in detail \
                    else bad("展开历史不对")
            else:
                bad("批次行没有展开图标")
            # 分批设置入口（S3.1）：页面上必须能点出来（否则 batch_split 只能改库）
            btnCfg = page.get_by_role("button", name=re.compile(r"分批：每批 2 个样品"))
            ok("操作行有「分批设置」入口") if btnCfg.count() > 0 else bad("缺分批设置入口")
            if btnCfg.count() > 0:
                btnCfg.first.click()
                page.wait_for_selector(".ant-modal-content", timeout=10000)
                page.wait_for_timeout(500)
                mbody = page.inner_text(".ant-modal-content")
                ok("弹窗回填当前分批值") if "每批 2 个样品" in mbody else bad("弹窗当前值不对")
                page.locator(".ant-modal-footer button").first.click()
                page.wait_for_timeout(800)
            page.screenshot(path="/tmp/exp-batch.png")
            browser.close()
            browser = None

        # ---- 6. 续跑回写（S3.1 / D8-D10）：暂停的批内 run 恢复后批次层要同步 ----
        step("6. 续跑回写：批内 run 恢复后批次层同步（S3.1）")
        # 把第 1 批「第 2 次尝试」的 run 造成现场暂停态（模拟人工暂停后恢复）
        att2_run = rb.get("runId")
        cur.execute("UPDATE experiment_run SET status='paused', finished_at=NULL, "
                    "pause_kind='manual', pause_reason='联调暂停' "
                    "WHERE tenant_id=1 AND id=%s", (att2_run,))
        cur.execute("UPDATE experiment_batch_run SET status='paused', finished_at=NULL "
                    "WHERE tenant_id=1 AND experiment_run_id=%s", (att2_run,))
        cur.execute("UPDATE experiment_batch SET status='paused' "
                    "WHERE tenant_id=1 AND experiment_id=%s AND batch_no=1", (exp_id,))
        conn.commit()
        code, rj = api.call("POST", "/api/experiments/runs/%d/resume" % att2_run, {})
        ok("恢复 API 200 且 requeued") if code == 200 and rj.get("requeued") \
            else bad("恢复失败", (code, rj))
        # 调度器异步重跑（空实验 -> 立即失败）；轮询批次视图等状态回写
        att2_status = None
        for _ in range(30):
            code, v = api.call("GET", "/api/experiments/%d/batches" % exp_id)
            b1v = (v.get("items") or [{}])[0]
            for a in (b1v.get("runs") or []):
                if a.get("runId") == att2_run:
                    att2_status = a.get("status")
            if att2_status in ("failed", "completed", "aborted"):
                break
            time.sleep(1)
        ok("恢复后 attempt 状态被回写（failed）") if att2_status == "failed" \
            else bad("attempt 状态未回写", att2_status)
        code, v = api.call("GET", "/api/experiments/%d/batches" % exp_id)
        b1v = (v.get("items") or [{}])[0]
        ok("没有新增尝试（仍 2 次，续跑复用同一条 run）") if b1v.get("attemptCount") == 2 \
            else bad("attemptCount 变了", b1v.get("attemptCount"))
        ok("批次状态同步为 failed") if b1v.get("status") == "failed" \
            else bad("批次状态不对", b1v.get("status"))
        cur.execute("SELECT status FROM experiment WHERE tenant_id=1 AND id=%s", (exp_id,))
        exp_status = cur.fetchone()[0]
        ok("实验状态按聚合（failed），没被单 run 状态覆盖") if exp_status == "failed" \
            else bad("实验状态不对", exp_status)

    except PWTimeout as e:
        bad("页面超时", str(e).split("\n")[0][:160])
    except Exception as e:  # noqa: BLE001
        bad("异常", str(e)[:220])
    finally:
        if browser is not None:
            try:
                browser.close()
            except Exception:
                pass
        try:
            # 前面任何一步失败都可能把事务留在 aborted，先回滚再清理
            conn.rollback()
            cleanup(cur, conn, exp_id, barcodes)
            cur.execute("UPDATE experiment SET status=%s, batch_split=%s, started_at=%s, "
                        "finished_at=%s WHERE tenant_id=1 AND id=%s",
                        (orig[0], orig[1], orig[2], orig[3], exp_id))
            conn.commit()
            print("\n已清理测试数据（实验 #%s 还原）" % exp_id)
        except Exception as e:  # noqa: BLE001
            print("清理失败：" + str(e))
        conn.close()

    print()
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
