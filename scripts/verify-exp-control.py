#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实验级运行控制联调：暂停（现场扣留）→ 恢复 → 停止，全程从界面点。

为什么不在 e2e-smoke 里做：这条链路要有"活动中的实验执行"才能点，
而 e2e-smoke 是只读冒烟（不造数据）。这里显式插两条 queued 的
experiment_run，跑完删掉并把 experiment 状态还原 —— 可复跑、不残留。

前置：
  · 前端 5173 / API 18080 在跑（默认 --base http://localhost:5173）；
  · python3 有 playwright（python3 -c "import playwright"）；
  · 库可达：LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME / LABOS_DB_USER / LABOS_DB_PASS；
  · 有一个"没流程、没执行记录、未分配岛"的实验可借用（脚本自动找，也可 --experiment 指定）。

断言 22 条：
  入口存在 / 现场扣留卡片 / 暂停落扣留 / 未分配岛退化为本实验 /
  pauseKind+原因可见 / 恢复清扣留 / 停止二次确认并进终态 / 控制台无错。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/verify-exp-control.py
  ... --experiment 813 --base http://localhost:5173 --headed
"""
import argparse
import os
import re
import sys

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


def pick_experiment(cur, wanted=None):
    """借一个空实验：没流程、没执行记录、未分配岛（才走得到作用域退化分支）。"""
    if wanted:
        cur.execute("""SELECT id FROM experiment
                        WHERE tenant_id = 1 AND id = %s""", (wanted,))
        row = cur.fetchone()
        if not row:
            raise SystemExit(f"实验 #{wanted} 不存在")
        return wanted
    cur.execute("""
        SELECT e.id
          FROM experiment e
         WHERE e.tenant_id = 1
           AND e.island_id IS NULL
           AND NOT EXISTS (SELECT 1 FROM experiment_process p WHERE p.experiment_id = e.id)
           AND NOT EXISTS (SELECT 1 FROM experiment_run r WHERE r.experiment_id = e.id)
         ORDER BY e.id DESC
         LIMIT 1
    """)
    row = cur.fetchone()
    if not row:
        raise SystemExit("找不到可用空实验：请先建一个没流程、没执行记录、未分配岛的实验")
    return row[0]


def login(page, base, user, pwd):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    text_inputs = page.locator('input[type="text"]')
    if text_inputs.count() > 0 and not text_inputs.first.input_value():
        text_inputs.first.fill(user)
    page.get_by_role("button", name=re.compile(r"登\s*录")).click()
    page.wait_for_selector("text=流程库", timeout=20000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--experiment", type=int, default=None,
                    help="借用的空实验 id（默认按条件自动找）")
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--keep", action="store_true", help="保留测试数据（排障用）")
    args = ap.parse_args()

    conn = db_connect()
    cur = conn.cursor()
    exp_id = pick_experiment(cur, args.experiment)
    cur.execute("SELECT status, started_at, finished_at FROM experiment WHERE id = %s", (exp_id,))
    orig = cur.fetchone()
    print(f"借用实验 #{exp_id}（原状态 {orig[0]}，{orig[1]} ~ {orig[2]}）")

    run_pause = run_resume = run_stop = None
    browser = None
    try:
        # --- 造两条排队中的执行记录（不碰设备：实验没有编排任何流程）---
        cur.execute("""INSERT INTO experiment_run
                         (tenant_id, experiment_id, status, expanded_steps, current_seq)
                       VALUES (1, %s, 'queued', 0, 0) RETURNING id""", (exp_id,))
        run_pause = cur.fetchone()[0]
        cur.execute("""INSERT INTO experiment_run
                         (tenant_id, experiment_id, status, expanded_steps, current_seq,
                          pause_kind, pause_reason, paused_at)
                       VALUES (1, %s, 'paused', 0, 0, 'manual',
                               '实验级入口联调（自测，自动清理）', now()) RETURNING id""", (exp_id,))
        run_resume = cur.fetchone()[0]
        cur.execute("""INSERT INTO experiment_run
                         (tenant_id, experiment_id, status, expanded_steps, current_seq)
                       VALUES (1, %s, 'queued', 0, 0) RETURNING id""", (exp_id,))
        run_stop = cur.fetchone()[0]
        cur.execute("""INSERT INTO runtime_hold
                         (tenant_id, scope, scope_id, kind, reason)
                       VALUES (1, 'experiment', %s, 'manual',
                               '实验级入口联调（自测，自动清理）')""", (exp_id,))
        conn.commit()
        print(f"插入测试执行：#{run_pause}（暂停请求）、#{run_resume}（恢复）、#{run_stop}（停止）")

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=not args.headed)
            page = browser.new_page(viewport={"width": 1500, "height": 950})
            page.set_default_timeout(20000)
            console_errors = []
            page.on("console", lambda m: console_errors.append(m.text)
                    if m.type == "error" else None)

            try:
                step("0. 登录并进入实验详情")
                login(page, args.base, args.user, args.pwd)
                ok("登录成功")
                page.goto(f"{args.base}/experiments/{exp_id}", wait_until="networkidle")
                page.wait_for_timeout(2000)
                body = page.inner_text("body")
                ok("进入实验详情") if "实验详情" in body else bad("未进入实验详情")

                step("1. 入口存在性")
                ok("有「现场扣留」卡片") if "现场扣留" in body else bad("缺现场扣留卡片")
                ok("初始已挂恢复夹具的人工暂停扣留") if ("人工暂停" in body and f"#{exp_id}" in body) \
                    else bad("初始扣留状态不对")
                ok("执行历史有[操作]列") if "操作" in body else bad("缺操作列")
                ok("执行历史有[暂停原因]列") if "暂停原因" in body else bad("缺暂停原因列")
                rows = page.locator(".ant-table-tbody tr.ant-table-row")
                ok(f"执行历史至少含 2 行测试执行（当前 {rows.count()} 行）") if rows.count() >= 2 \
                    else bad("执行历史行数不足", rows.count())

                row_pause = page.locator(".ant-table-tbody tr.ant-table-row", has_text="#" + str(run_pause))
                row_resume = page.locator(".ant-table-tbody tr.ant-table-row", has_text="#" + str(run_resume))
                row_stop = page.locator(".ant-table-tbody tr.ant-table-row", has_text="#" + str(run_stop))
                ok("queued 行有[暂停]按钮") if row_pause.get_by_role(
                    "button", name=re.compile(r"暂\s*停")).count() > 0 else bad("queued 行没有暂停按钮")
                ok("paused 行有[恢复]按钮") if row_resume.get_by_role(
                    "button", name=re.compile(r"恢\s*复")).count() > 0 else bad("paused 行没有恢复按钮")
                ok("queued 行有[停止]按钮") if row_stop.get_by_role(
                    "button", name=re.compile(r"停\s*止")).count() > 0 else bad("queued 行没有停止按钮")

                step("2. 暂停 = 现场扣留")
                row_pause.get_by_role("button", name=re.compile(r"暂\s*停")).first.click()
                page.wait_for_selector(".ant-modal-content", timeout=10000)
                modal = page.inner_text(".ant-modal-content")
                ok("暂停弹窗说明扣留语义") if "暂停 = 现场扣留" in modal else bad("弹窗缺扣留说明")
                ok("默认作用域整座岛（与接口默认一致）") if "整座岛" in modal \
                    else bad("默认作用域不对")
                page.locator(".ant-modal-content textarea").fill("实验级入口联调（自测，自动清理）")
                page.locator(".ant-modal-footer button.ant-btn-primary").click()
                page.wait_for_selector("text=暂停请求已受理", timeout=15000)
                toast = page.inner_text(".ant-message") if page.locator(".ant-message").count() else ""
                ok("暂停受理提示出现") if "暂停请求已受理" in toast else bad("暂停提示不对", toast)
                ok("未分配岛自动退化为本实验") if "本实验" in toast else bad("退化提示不对", toast)
                page.wait_for_timeout(2500)
                body = page.inner_text("body")
                ok("扣留卡片显示[人工暂停]") if "人工暂停" in body else bad("扣留卡片没显示人工暂停")
                ok(f"扣留范围标出 #{exp_id}") if f"#{exp_id}" in body else bad("扣留范围没标实验")
                ok("扣留原因可读") if "实验级入口联调" in body else bad("扣留原因没显示")
                row_pause = page.locator(".ant-table-tbody tr.ant-table-row", has_text="#" + str(run_pause))
                row_pause_text = row_pause.inner_text()
                ok(f"run#{run_pause} 保持排队并标出已请求暂停") \
                    if ("排队中" in row_pause_text and "已请求暂停" in row_pause_text) \
                    else bad("run 暂停中展示不对", row_pause_text[:120])

                step("3. 恢复：解扣留 + 回队列")
                row_resume.get_by_role("button", name=re.compile(r"恢\s*复")).first.click()
                page.wait_for_selector("text=已恢复", timeout=15000)
                ok("恢复成功提示出现")
                page.wait_for_timeout(3000)
                ok("恢复后扣留清空") if "没有生效的扣留" in page.inner_text("body") \
                    else bad("恢复后仍有扣留")
                row_resume = page.locator(".ant-table-tbody tr.ant-table-row", has_text="#" + str(run_resume))
                row_resume_text = row_resume.inner_text()
                ok(f"run#{run_resume} 恢复后回 queued") if ("排队中" in row_resume_text or "queued" in row_resume_text) \
                    else bad("恢复后 run 状态不对", row_resume_text[:120])

                step("4. 停止：二次确认 + 终态")
                row_stop.get_by_role("button", name=re.compile(r"停\s*止")).first.click()
                page.wait_for_selector(".ant-modal-confirm", timeout=10000)
                ok("停止有二次确认") if "不能恢复" in page.inner_text(".ant-modal-confirm") \
                    else bad("停止没有确认说明")
                page.locator(".ant-modal-confirm-btns button.ant-btn-dangerous").click()
                page.wait_for_selector("text=已请求停止", timeout=15000)
                page.wait_for_timeout(3000)
                row_stop = page.locator(".ant-table-tbody tr.ant-table-row", has_text="#" + str(run_stop))
                row_stop_text = row_stop.inner_text()
                ok(f"run#{run_stop} 已进入停止终态/停止请求态") if (
                    "已请求停止" in row_stop_text or "停止中" in row_stop_text
                    or "已终止" in row_stop_text or "aborted" in row_stop_text) \
                    else bad("run 停止请求未显示", row_stop_text[:120])

                step("5. 控制台")
                # antd 静态 message/Modal 的 context 警告是全站全站通用写法（PausedRuns/RunDetail
                # 同样用 Modal.confirm），不是本功能引入；只留真正的错误。
                errs = [e for e in console_errors
                        if "favicon" not in e and "Static function can not consume context" not in e]
                ok("浏览器控制台无错误") if not errs else bad("控制台有错误", " | ".join(errs[:3])[:220])
            except PWTimeout as e:
                bad("超时", str(e).split("\n")[0][:160])
            except Exception as e:
                bad("异常", str(e)[:220])
            finally:
                if browser:
                    browser.close()
    finally:
        if not args.keep and exp_id is not None:
            try:
                cur.execute("DELETE FROM experiment_run WHERE experiment_id = %s AND id IN (%s, %s, %s)",
                            (exp_id, run_pause, run_resume, run_stop))
                cur.execute("DELETE FROM runtime_hold WHERE scope = 'experiment' AND scope_id = %s",
                            (exp_id,))
                cur.execute("""UPDATE experiment
                                  SET status = %s, started_at = %s, finished_at = %s
                                WHERE id = %s""", (orig[0], orig[1], orig[2], exp_id))
                conn.commit()
                print("\n已清理测试数据（实验 #%s 状态还原为 %s）" % (exp_id, orig[0]))
            except Exception as e:  # noqa: BLE001
                print("清理失败（请手工处理）：" + str(e))
        conn.close()

    print()
    print(f"通过 {PASS} / 失败 {FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
