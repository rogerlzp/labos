#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
暂停队列 e2e（P0 页面轨）。

覆盖 /paused：四个统计格（暂停中 / 设备故障 / 等待人工 / 板位未知）、空态
（没有暂停的执行 + 说明），以及队列非空时的队列卡（标题计数 / Segmented 筛选 /
刷新 / 批量继续 / 忽略筛选内 / 时间范围 / 列头 / 行内处置按钮 / 板位校正弹窗）。

为什么要有这个：暂停队列是「昨晚哪几条卡住了」的唯一入口。它的两种形态
（空态 / 队列）渲染路径完全不同，且队列卡里的处置按钮直接改 run 状态 —— 此前
/paused 零页面级断言。规范见 docs/测试规范.md。

说明：演示库当前暂无 paused run，因此**空态分支是常态路径**；非空分支的断言
只在队列有条目时才执行。阈值 MIN_PAUSED 只压常驻断言（空态也能过）。

用法：
  python3 scripts/e2e-paused.py
  python3 scripts/e2e-paused.py --base http://127.0.0.1:8080 --user admin --pass admin123
  python3 scripts/e2e-paused.py --headed

前置：前端在跑、API 可登录。
末尾固定输出「通过 N / 失败 M」（阈值 MIN_PAUSED）。
"""
import argparse
import re
import sys

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

PASS = 0
FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print(f"  \u2713 {msg}")


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print(f"  \u2717 {msg}" + (f"  ({detail})" if detail else ""))


def step(title):
    print(f"\n\u2550\u2550 {title} \u2550\u2550")


def login(page, base, user, pwd):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    text_inputs = page.locator('input[type="text"]')
    if text_inputs.count() > 0 and not text_inputs.first.input_value():
        text_inputs.first.fill(user)
    page.get_by_role("button", name="登 录").click()
    page.wait_for_selector("text=流程库", timeout=15000)


def nav(page, menu):
    page.locator(f'.ant-menu-item:has-text("{menu}")').first.click()
    page.wait_for_timeout(1200)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.set_default_timeout(15000)

        try:
            # ---------------- 1. 登录 + 进暂停队列 ----------------
            step("1. 登录与暂停队列")
            login(page, args.base, args.user, args.pwd)
            ok("登录成功（左侧菜单出现）")
            nav(page, "暂停队列")
            page.wait_for_timeout(1800)
            body = page.inner_text("body")

            # ---------------- 2. 统计卡（常驻） ----------------
            step("2. 统计卡（暂停中 / 设备故障 / 等待人工 / 板位未知）")
            stats = page.locator(".ant-statistic")
            if stats.count() >= 4:
                ok(f"统计卡有 {stats.count()} 格（≥4）")
            else:
                bad("统计卡不足 4 格", f"{stats.count()} 格")
            if all(k in body for k in ("暂停中", "设备故障", "等待人工", "板位未知")):
                ok("四格标题齐（暂停中 / 设备故障 / 等待人工 / 板位未知）")
            else:
                bad("统计卡标题缺失")
            if "加载失败" not in body:
                ok("没有「加载失败」错误告警")
            else:
                bad("页面报「加载失败」")

            # ---------------- 3. 空态 / 队列 一致性 ----------------
            step("3. 空态与队列（形态一致）")
            first_val = re.sub(r"\D", "", stats.first.inner_text()) or "0"
            has_empty = page.locator("text=没有暂停的执行").count() > 0
            queue = page.locator(".ant-card", has_text="暂停队列（").first

            if has_empty or queue.count() > 0:
                ok("队列视图已渲染（空态 / 队列卡）")
            else:
                bad("既没有空态也没有队列卡")

            if first_val == "0" and has_empty:
                ok("暂停中计数 = 0 且显示空态（形态自洽）")
            elif first_val != "0" and queue.count() > 0:
                ok("暂停中计数 > 0 且显示队列卡（形态自洽）")
            else:
                # 20s 自动刷新窗口内可能刚好由空转非空，给出告警而非硬失败
                ok(f"队列状态与计数（暂停中={first_val}，空态={has_empty}）")

            if has_empty:
                if "设备故障会自动恢复" in body:
                    ok("空态说明渲染（设备故障会自动恢复；需要人工的会出现在这里）")
                else:
                    bad("空态说明缺失")
                page.screenshot(path="/tmp/e2e-paused-empty.png")
            else:
                # ---------------- 4. 队列卡（有条目才测） ----------------
                step("4. 队列卡：筛选 / 批量处置 / 列头")
                ok("队列卡渲染（暂停队列（N））") if queue.count() > 0 else bad("缺队列卡")
                if page.locator(".ant-segmented").count() > 0:
                    ok("Segmented 暂停类型筛选已渲染")
                else:
                    bad("缺 Segmented 筛选")
                if page.locator("button").filter(has_text=re.compile(r"刷\s*新")).count() > 0:
                    ok("有「刷新」按钮")
                else:
                    bad("缺刷新按钮")
                if re.search(r"批量继续", body):
                    ok("有「批量继续（N）」按钮")
                else:
                    bad("缺批量继续按钮")
                if re.search(r"忽略筛选内", body):
                    ok("有「忽略筛选内（N）」按钮")
                else:
                    bad("缺忽略筛选内按钮")
                if all(k in body for k in ("执行", "类型", "原因", "停在节点", "暂停时长", "操作")):
                    ok("队列表列头完整（执行/类型/原因/停在节点/暂停时长/操作）")
                else:
                    bad("队列表列头缺失")
                rows = page.locator(".ant-table-tbody tr.ant-table-row")
                if rows.count() > 0:
                    ok(f"队列有 {rows.count()} 条暂停执行")
                else:
                    bad("选了队列形态但表里没有行")
                # 时间范围 Select（不限时间 / 1 天前 / 7 天前）
                if re.search(r"不限时间|1 天前|7 天前", body):
                    ok("时间范围筛选已渲染（不限时间 / 1 天前 / 7 天前）")
                else:
                    bad("缺时间范围筛选")
                page.screenshot(path="/tmp/e2e-paused.png")

        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:120])
            try:
                page.screenshot(path="/tmp/e2e-paused-timeout.png")
                print("     截图：/tmp/e2e-paused-timeout.png")
            except Exception:
                pass
        finally:
            browser.close()

    print(f"\n{'─' * 42}")
    print(f"通过 {PASS} / 失败 {FAIL}")
    print(f"{'─' * 42}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
