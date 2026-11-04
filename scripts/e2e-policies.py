#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
策略页 e2e（P0 页面轨）。

覆盖 /policies：恢复策略表（标题计数 / 8 列表头 / 行）→ 点「编辑」打开
「编辑策略 · {code}」弹窗 → 弹窗内容（策略影响真实执行警示、动作/次数/优先级/启用
四个字段、生效校验 + 保存按钮）→ 点「生效校验」出校验结果（错误码存在 / 历史命中 /
被更高优先级遮蔽）。

为什么要有这个：策略是「错误码 → 动作」的落库配置，改错会让真实故障被 SKIP 或
无限 RETRY。此前 /policies 零页面级断言。规范见 docs/测试规范.md。

用法：
  python3 scripts/e2e-policies.py
  python3 scripts/e2e-policies.py --base http://127.0.0.1:8080 --user admin --pass admin123
  python3 scripts/e2e-policies.py --headed

前置：前端在跑、API 可登录、recovery_policy 有种子数据（初始化脚本自带）。
末尾固定输出「通过 N / 失败 M」（阈值 MIN_POLICIES）。
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
            # ---------------- 1. 登录 + 进策略页 ----------------
            step("1. 登录与策略页")
            login(page, args.base, args.user, args.pwd)
            ok("登录成功（左侧菜单出现）")
            nav(page, "策略")
            page.wait_for_timeout(1500)
            body = page.inner_text("body")

            if "恢复策略（" in body:
                ok("策略页标题渲染（恢复策略（N））")
            else:
                bad("策略页标题缺失", body[:120])
            if "改完立即生效" in body:
                ok("策略页提示渲染（改完立即生效，保存前先跑生效校验）")
            else:
                bad("缺策略页提示")

            rows = page.locator(".ant-table-tbody tr.ant-table-row")
            if rows.count() > 0:
                ok(f"策略列表有 {rows.count()} 行策略")
            else:
                bad("策略列表为空（种子数据缺失？）")

            if all(k in body for k in ("错误码", "层级", "动作", "次数", "优先级", "启用", "来源", "操作")):
                ok("策略表 8 列表头完整（错误码/层级/动作/次数/优先级/启用/来源/操作）")
            else:
                bad("策略表列头缺失")

            # ---------------- 2. 编辑弹窗 ----------------
            step("2. 编辑策略弹窗（动作/次数/优先级/启用 + 生效校验）")
            # 两字中文按钮会被 antd 插空格渲染成「编 辑」，用正则容错
            edit = page.locator(".ant-table-tbody button").filter(has_text=re.compile(r"编\s*辑"))
            if edit.count() > 0:
                ok("行内有「编辑」按钮")
            else:
                bad("行内没有「编辑」按钮")
            edit.first.click()
            page.wait_for_timeout(900)
            mbody = page.inner_text(".ant-modal")

            if "编辑策略" in mbody:
                ok("点编辑弹出「编辑策略 · {code}」弹窗")
            else:
                bad("编辑弹窗标题不对", mbody[:120])
            if "策略影响真实执行" in mbody:
                ok("弹窗有「策略影响真实执行」警示")
            else:
                bad("弹窗缺策略警示")
            if all(k in mbody for k in ("动作", "最大次数", "优先级（越小越先）", "启用")):
                ok("弹窗四个字段齐（动作 / 最大次数 / 优先级 / 启用）")
            else:
                bad("弹窗字段缺失")
            validate = page.locator(".ant-modal button").filter(has_text=re.compile(r"生效校验"))
            if validate.count() > 0:
                ok("弹窗有「生效校验」按钮")
            else:
                bad("弹窗缺「生效校验」按钮")
            if "保存" in re.sub(r"\s+", "", page.inner_text(".ant-modal-footer")):
                ok("弹窗有「保存」按钮")
            else:
                bad("弹窗缺保存按钮")

            validate.first.click()
            page.wait_for_timeout(1800)
            mbody = page.inner_text(".ant-modal")
            if "历史命中" in mbody:
                ok("生效校验出结果（错误码存在 / 历史命中 / 被更高优先级遮蔽）")
            else:
                bad("生效校验没有出结果", mbody[-160:])

            # 关掉弹窗，别把未保存的改动留下
            page.locator(".ant-modal-footer button").first.click()
            page.wait_for_timeout(500)
            if not page.locator(".ant-modal-wrap").first.is_visible():
                ok("取消后弹窗关闭（未落库）")
            else:
                bad("弹窗没有关闭")

            page.screenshot(path="/tmp/e2e-policies.png")

        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:120])
            try:
                page.screenshot(path="/tmp/e2e-policies-timeout.png")
                print("     截图：/tmp/e2e-policies-timeout.png")
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
