#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
运维处置页 e2e：/manual-windows + /quarantined + /handoffs。

覆盖：
  1. 人工窗口页：待决窗口列表 → 处置弹窗 → 仍交人工 → 记录表刷新
  2. 隔离处置页：quarantined run 列表 → 确认解除弹窗 → run.failed + Hold 释放
  3. Handoff 页：待确认 handoff 列表 → 确认弹窗 → status=confirmed + L-01 记录

为什么要有这个：
  这三页是 2026-11-09 新增的主要运维入口，但当前只有 smoke，没有动作级断言。
  本脚本把“看得到”和“点得通”都压成真实页面证据，并核对 DB 终态。

前置：
  - 前端与 API 已启动
  - LABOS_DB_* 已设置（显式真实库，不回落 127.0.0.1）

输出契约：
  固定输出「通过 N / 失败 M」，供 ci-gate.sh 取数。

  > **⚠ 未纳入新门禁（2026-11-11）**：本脚本是 2026-11-09 新增的运维处置页
  > 动作级断言，**不依赖旧基线数据**，但新门禁（`scripts/ci-gate.sh`）重写时
  > 暂未把它列为一段 —— 因为页面轨整体还没按新基线重建。
  > 现行分段见 [测试总纲](docs/测试总纲.md) §2.2。
  >
  > **接入前要做的**：跑一遍确认它在新基线上仍然成立（它要造 quarantined run
  > 与 hold，新基线的现场扣留语义变过）。
"""


import argparse
import os
import re
import subprocess
import sys
import time

from playwright.sync_api import sync_playwright

PASS = 0
FAIL = 0

TENANT = 1
TS = int(time.time() * 1000)
BASE_ID = 990000000 + (TS % 1000000)
MW_RUN = BASE_ID + 1
Q_EXP = BASE_ID + 101
Q_ERUN = BASE_ID + 102
Q_RUN = BASE_ID + 103
Q_STEP = BASE_ID + 104
H_EXP = BASE_ID + 201
H_WC_A = BASE_ID + 211
H_WC_B = BASE_ID + 212

MW_KEY = f"e2e-ops-manual-{TS}"
MW_NOTE = f"manual-note-{TS}"
MW_PLATE = f"MW-PLATE-{TS}"
MW_SAMPLE = f"MW-SAMPLE-{TS}"
Q_RUN_NO = f"E2E-Q-{TS}"
Q_NOTE = f"quarantine-note-{TS}"
Q_PLATE = f"Q-PLATE-{TS}"
H_NOTE = f"handoff-note-{TS}"


def ok(msg):
    global PASS
    PASS += 1
    print(f"  ✓ {msg}")


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print(f"  ✗ {msg}" + (f"（实际：{detail}）" if detail else ""))


def step(title):
    print(f"\n══ {title} ══")


def db(sql: str) -> str:
    env = dict(os.environ)
    env.setdefault("PGPASSWORD", env.get("LABOS_DB_PASS", ""))
    psql = env.get("PSQL", "psql")
    r = subprocess.run(
        [psql, "-h", env["LABOS_DB_HOST"], "-p", env.get("LABOS_DB_PORT", "5432"),
         "-U", env["LABOS_DB_USER"], "-d", env["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True, check=False)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or r.stdout.strip() or f"psql rc={r.returncode}")
    return r.stdout.strip()


def login(page, base, user, pwd):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    text_inputs = page.locator('input[type="text"]')
    if text_inputs.count() > 0 and not text_inputs.first.input_value():
        text_inputs.first.fill(user)
    page.get_by_role("button", name=re.compile(r"登\s*录")).click()
    page.wait_for_selector("text=流程库", timeout=15000)


def seed_manual_window():
    db(f"""
        INSERT INTO manual_window
          (tenant_id, run_id, window_key, error_code, node_key, strategy,
           default_action, allowed_actions, decision_reason, status, timeout_at)
        VALUES
          ({TENANT}, {MW_RUN}, '{MW_KEY}', 'EC-MW-{TS}', 'N-MW-{TS}', 'MANUAL',
           'MANUAL', '["MANUAL","ABORT"]'::jsonb, 'e2e 手工窗口', 'pending',
           now() + interval '10 minutes');
    """)


def seed_quarantine():
    db(f"""
        INSERT INTO experiment (id, tenant_id, experiment_no, name, status)
        VALUES ({Q_EXP}, {TENANT}, 'E2E-Q-EXP-{TS}', 'E2E-Q-EXP-{TS}', 'created');
        INSERT INTO experiment_run (id, tenant_id, experiment_id, status)
        VALUES ({Q_ERUN}, {TENANT}, {Q_EXP}, 'running');
        INSERT INTO run (id, tenant_id, run_no, run_type, workflow_id, workflow_ver,
                         mode, status, error_code, input, actual_start, progress)
        VALUES ({Q_RUN}, {TENANT}, '{Q_RUN_NO}', 'workflow', 239, 1,
                'normal', 'quarantined', 'EC-99-0006',
                '{{"quarantine":true}}'::jsonb, now(), 0);
        INSERT INTO experiment_run_step
          (id, tenant_id, experiment_run_id, seq, workflow_id, run_id, status)
        VALUES ({Q_STEP}, {TENANT}, {Q_ERUN}, 1, 239, {Q_RUN}, 'running');
        INSERT INTO runtime_hold (tenant_id, scope, scope_id, kind, reason)
        VALUES ({TENANT}, 'experiment', {Q_EXP}, 'human', 'e2e quarantine hold');
    """)


def seed_handoff():
    db(f"""
        INSERT INTO experiment (id, tenant_id, experiment_no, name, status)
        VALUES ({H_EXP}, {TENANT}, 'E2E-H-EXP-{TS}', 'E2E-H-EXP-{TS}', 'created');
        INSERT INTO experiment_workcell (tenant_id, experiment_id, workcell_id)
        VALUES ({TENANT}, {H_EXP}, {H_WC_A}),
               ({TENANT}, {H_EXP}, {H_WC_B});
        INSERT INTO experiment_handoff
          (tenant_id, experiment_id, from_workcell_id, to_workcell_id, carrier, remark, status)
        VALUES ({TENANT}, {H_EXP}, {H_WC_A}, {H_WC_B}, 'ROBOT', 'e2e pending handoff', 'configured');
    """)


def cleanup():
    stmts = [
        f"DELETE FROM intervention_record WHERE tenant_id={TENANT} "
        f"AND (manual_window_id IN (SELECT id FROM manual_window WHERE tenant_id={TENANT} "
        f"AND window_key IN ('{MW_KEY}','handoff:{H_EXP}:{H_WC_A}:{H_WC_B}')) "
        f"OR note IN ('{MW_NOTE}','{Q_NOTE}','{H_NOTE}') "
        f"OR run_id IN ({MW_RUN},{Q_RUN}));",
        f"DELETE FROM runtime_hold WHERE tenant_id={TENANT} AND scope='experiment' "
        f"AND scope_id IN ({Q_EXP});",
        f"DELETE FROM experiment_handoff WHERE tenant_id={TENANT} AND experiment_id={H_EXP};",
        f"DELETE FROM manual_window WHERE tenant_id={TENANT} "
        f"AND window_key IN ('{MW_KEY}','handoff:{H_EXP}:{H_WC_A}:{H_WC_B}');",
        f"DELETE FROM experiment_workcell WHERE tenant_id={TENANT} AND experiment_id={H_EXP};",
        f"DELETE FROM experiment_run_step WHERE tenant_id={TENANT} AND id={Q_STEP};",
        f"DELETE FROM experiment_run WHERE tenant_id={TENANT} AND id={Q_ERUN};",
        f"DELETE FROM run WHERE tenant_id={TENANT} AND id={Q_RUN};",
        f"DELETE FROM experiment WHERE tenant_id={TENANT} AND id IN ({Q_EXP},{H_EXP});",
    ]
    for sql in stmts:
        try:
            db(sql)
        except Exception:
            pass


def reload_and_wait(page, path, title_text):
    page.goto(path, wait_until="networkidle")
    page.wait_for_selector(f"text={title_text}", timeout=15000)
    page.wait_for_timeout(1200)


def test_manual_windows(page, base):
    step("1. 人工窗口页")
    reload_and_wait(page, f"{base}/manual-windows", "人工值守窗口")
    body = page.inner_text("body")
    ok("人工窗口页标题已渲染") if "人工值守窗口" in body else bad("人工窗口页标题缺失", body[:120])
    ok("人工窗口页提示已渲染") if "等待人工确认" in body else bad("人工窗口页提示缺失", body[:120])

    row = page.locator("tr.ant-table-row", has_text=f"run #{MW_RUN}")
    ok("待决人工窗口行出现") if row.count() == 1 else bad("待决人工窗口行未出现", row.count())
    if row.count() == 0:
        return
    ok("动作集标签出现") if "MANUAL" in row.first.inner_text() and "ABORT" in row.first.inner_text() \
        else bad("动作集标签缺失", row.first.inner_text())

    row.first.locator("button").filter(has_text=re.compile(r"确认\s*/\s*处置")).first.click()
    modal = page.locator(".ant-modal:visible").last
    modal.wait_for(timeout=10000)
    ok("人工窗口处置弹窗出现") if "人工确认与介入记录" in modal.inner_text() else bad("人工窗口弹窗标题不对", modal.inner_text()[:120])

    inputs = modal.locator("input")
    inputs.nth(0).fill(MW_PLATE)
    inputs.nth(1).fill(MW_SAMPLE)
    inputs.nth(2).fill("P1")
    inputs.nth(3).fill("P2")
    modal.locator("textarea").fill(MW_NOTE)
    ok("人工窗口弹窗里有「仍交人工」按钮") \
        if modal.get_by_role("button", name="仍交人工").count() > 0 \
        else bad("人工窗口弹窗缺「仍交人工」按钮")
    modal.get_by_role("button", name="仍交人工").click()
    page.wait_for_timeout(1200)

    reload_and_wait(page, f"{base}/manual-windows", "人工值守窗口")
    row2 = page.locator("tr.ant-table-row", has_text=f"run #{MW_RUN}")
    ok("处置后待决窗口已消失") if row2.count() == 0 else bad("处置后待决窗口仍在", row2.count())
    status = db(f"SELECT status FROM manual_window WHERE tenant_id={TENANT} AND window_key='{MW_KEY}'")
    ok("manual_window.status=decided") if status == "decided" else bad("manual_window 状态不对", status)
    action = db(f"SELECT decided_action FROM manual_window WHERE tenant_id={TENANT} AND window_key='{MW_KEY}'")
    ok("manual_window.decided_action=MANUAL") if action == "MANUAL" else bad("manual_window 决策动作不对", action)
    rec = db(f"SELECT count(*) FROM intervention_record WHERE tenant_id={TENANT} AND note='{MW_NOTE}' AND run_id={MW_RUN}")
    ok("人工窗口处置已落 intervention_record") if rec == "1" else bad("人工窗口处置未落介入记录", rec)
    body2 = page.inner_text("body")
    ok("介入记录表显示 plate/sample") if MW_PLATE in body2 and MW_SAMPLE in body2 \
        else bad("介入记录表未显示 plate/sample", body2[:240])


def test_quarantine(page, base):
    step("2. 隔离处置页")
    reload_and_wait(page, f"{base}/quarantined", "隔离处置")
    body = page.inner_text("body")
    ok("隔离处置页标题已渲染") if "隔离处置" in body else bad("隔离处置页标题缺失", body[:120])
    ok("隔离处置页提示已渲染") if "物理结果未知" in body else bad("隔离处置页提示缺失", body[:120])

    row = page.locator("tr.ant-table-row", has_text=Q_RUN_NO)
    ok("quarantined run 行出现") if row.count() == 1 else bad("quarantined run 行未出现", row.count())
    if row.count() == 0:
        return
    ok("隔离行带实验链接") if f"#{Q_EXP}" in row.first.inner_text() else bad("隔离行缺实验信息", row.first.inner_text())
    row.first.get_by_role("button", name="确认并解除").click()
    modal = page.locator(".ant-modal:visible").last
    modal.wait_for(timeout=10000)
    ok("隔离解除弹窗出现") if "确认物理状态并解除隔离" in modal.inner_text() else bad("隔离解除弹窗标题不对", modal.inner_text()[:120])
    modal.locator("input").first.fill(Q_PLATE)
    modal.locator("textarea").fill(Q_NOTE)
    with page.expect_response(lambda r: f"/api/runs/{Q_RUN}/quarantine/release" in r.url and r.request.method == "POST") as resp_info:
        modal.locator(".ant-modal-footer .ant-btn-primary").first.click()
    resp = resp_info.value
    page.wait_for_timeout(1500)
    ok("隔离解除接口返回 200") if resp.status == 200 else bad("隔离解除接口失败", f"{resp.status} {resp.text()[:160]}")
    ok("隔离解除弹窗已关闭") if page.locator(".ant-modal:visible").count() == 0 else bad("隔离解除弹窗未关闭")

    reload_and_wait(page, f"{base}/quarantined", "隔离处置")
    row2 = page.locator("tr.ant-table-row", has_text=Q_RUN_NO)
    ok("解除后 quarantined run 已消失") if row2.count() == 0 else bad("解除后 quarantined run 仍在", row2.count())
    run_status = db(f"SELECT status::text FROM run WHERE tenant_id={TENANT} AND id={Q_RUN}")
    ok("解除后 run.status=failed") if run_status == "failed" else bad("解除后 run 状态不对", run_status)
    hold_count = db(f"SELECT count(*) FROM runtime_hold WHERE tenant_id={TENANT} AND scope='experiment' AND scope_id={Q_EXP} AND kind='human' AND released_at IS NULL")
    ok("解除后 HUMAN Hold 已释放") if hold_count == "0" else bad("解除后 HUMAN Hold 未释放", hold_count)
    rec = db(f"SELECT count(*) FROM intervention_record WHERE tenant_id={TENANT} AND note='{Q_NOTE}' AND run_id={Q_RUN}")
    ok("隔离解除已落 intervention_record") if rec == "1" else bad("隔离解除未落介入记录", rec)


def test_handoffs(page, base):
    step("3. Handoff 页")
    reload_and_wait(page, f"{base}/handoffs", "跨 Workcell 交接确认")
    body = page.inner_text("body")
    ok("Handoff 页标题已渲染") if "跨 Workcell 交接确认" in body else bad("Handoff 页标题缺失", body[:120])
    ok("Handoff 页提示已渲染") if "人工确认后" in body else bad("Handoff 页提示缺失", body[:120])

    row = page.locator("tr.ant-table-row", has_text=f"E2E-H-EXP-{TS}")
    ok("待确认 Handoff 行出现") if row.count() == 1 else bad("待确认 Handoff 行未出现", row.count())
    if row.count() == 0:
        return
    ok("Handoff 初始状态为 configured") if "configured" in row.first.inner_text() else bad("Handoff 初始状态不对", row.first.inner_text())
    row.first.get_by_role("button", name="确认交接").click()
    modal = page.locator(".ant-modal:visible").last
    modal.wait_for(timeout=10000)
    ok("Handoff 确认弹窗出现") if "确认跨 Workcell 交接" in modal.inner_text() else bad("Handoff 弹窗标题不对", modal.inner_text()[:120])
    modal.locator("textarea").fill(H_NOTE)
    with page.expect_response(lambda r: f"/api/experiments/{H_EXP}/handoffs/confirm" in r.url and r.request.method == "POST") as resp_info:
        modal.locator(".ant-modal-footer .ant-btn-primary").first.click()
    resp = resp_info.value
    page.wait_for_timeout(1500)
    ok("Handoff 确认接口返回 200") if resp.status == 200 else bad("Handoff 确认接口失败", f"{resp.status} {resp.text()[:160]}")
    ok("Handoff 确认弹窗已关闭") if page.locator(".ant-modal:visible").count() == 0 else bad("Handoff 确认弹窗未关闭")

    reload_and_wait(page, f"{base}/handoffs", "跨 Workcell 交接确认")
    row2 = page.locator("tr.ant-table-row", has_text=f"E2E-H-EXP-{TS}")
    ok("确认后 Handoff 行仍在") if row2.count() == 1 else bad("确认后 Handoff 行消失", row2.count())
    if row2.count() > 0:
        ok("确认后状态切到 confirmed") if "confirmed" in row2.first.inner_text() else bad("确认后状态未切换", row2.first.inner_text())
        btn_text = row2.first.get_by_role("button").first.inner_text()
        ok("确认后按钮变成已确认") if "已确认" in btn_text else bad("确认后按钮文案不对", btn_text)
    status = db(f"SELECT status FROM experiment_handoff WHERE tenant_id={TENANT} AND experiment_id={H_EXP}")
    ok("experiment_handoff.status=confirmed") if status == "confirmed" else bad("handoff 状态不对", status)
    iid = db(f"SELECT coalesce(intervention_record_id,0) FROM experiment_handoff WHERE tenant_id={TENANT} AND experiment_id={H_EXP}")
    ok("handoff 已关联 intervention_record_id") if iid not in ("", "0") else bad("handoff 未关联介入记录", iid)
    wid = db(f"SELECT coalesce(manual_window_id,0) FROM experiment_handoff WHERE tenant_id={TENANT} AND experiment_id={H_EXP}")
    ok("handoff 已关联 manual_window_id") if wid not in ("", "0") else bad("handoff 未关联窗口", wid)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("LABOS_WEB", "http://localhost:5173"))
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    required = ["LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"]
    missing = [k for k in required if not os.environ.get(k)]
    if missing:
        print("缺 LABOS_DB_* 环境变量")
        sys.exit(2)

    cleanup()
    seed_manual_window()
    seed_quarantine()
    seed_handoff()

    base = args.base.rstrip("/")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.set_default_timeout(20000)
        try:
            step("0. 登录")
            login(page, base, args.user, args.pwd)
            ok("登录成功（左侧菜单出现）")
            test_manual_windows(page, base)
            test_quarantine(page, base)
            test_handoffs(page, base)
        finally:
            browser.close()
            cleanup()

    print()
    print(f"通过 {PASS} / 失败 {FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
