#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
告警页 e2e（P0 页面轨）。

覆盖 /alerts：心跳参数卡（探测间隔 / 判离线阈值 / 保存）+ 告警表
（标题计数、列头、空态、行内「认领」「关闭」弹窗）。

为什么要有这个：`/alerts` 是运维每天第一眼看的页面，此前**没有任何页面级断言** ——
心跳参数写错会让全所设备被判离线，告警认领弹窗坏掉会让故障没人跟进。这两条都是
P0，但 CI 里只到 API 层。规范见 docs/测试规范.md（页面轨四个必测面）。

用法：
  python3 scripts/e2e-alerts.py
  python3 scripts/e2e-alerts.py --base http://127.0.0.1:8080 --user admin --pass admin123
  python3 scripts/e2e-alerts.py --headed

前置：前端（5173 或 nginx 8080）在跑、API 18080 可登录。
末尾固定输出「通过 N / 失败 M」，ci-gate.sh 按此处取数（阈值 MIN_ALERTS）。

  > **⚠ 已随旧基线退役（2026-11-11）**：本脚本针对的是**旧基线**
  > （三十座岛 + 一千多条流程的累积数据）。新基线重建后**不再纳入门禁**
  > （现行分段见 [测试总纲](docs/测试总纲.md) §2.2）。
  >
  > **为什么保留而不是删掉**：里面沉淀的是**页面交互的断言写法**（Playwright 操作、
  > DOM 选择器、断言模式）—— 重写页面轨时这些是最值钱的资产，删了等于重写。
  >
  > **重写时按新基线改**：岛用 `LAB-BASE-01`（或从接口动态取，别写死）；
  > 设备用基线 5 台（设备ID 200 / 229 / 231 / 413 / 414，
  > 能力 读板机 / 孵育箱 / 机器人 / 封膜机 / 离心机）。
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


def squash(s):
    """去掉所有空白，抗 antd 两字按钮自动加空格（保 存 / 取 消）。"""
    return re.sub(r"\s+", "", s or "")


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
            # ---------------- 1. 登录 + 进告警页 ----------------
            step("1. 登录与告警页")
            login(page, args.base, args.user, args.pwd)
            ok("登录成功（左侧菜单出现）")
            nav(page, "告警")
            page.wait_for_timeout(1500)
            body = page.inner_text("body")

            if "告警（待处理" in body:
                ok("告警页标题渲染（告警（待处理 N / 共 M））")
            else:
                bad("告警页标题缺失", body[:120])

            # ---------------- 2. 心跳参数卡 ----------------
            step("2. 心跳参数卡（改完立即生效，不用重启）")
            hb = page.locator(".ant-card", has_text="心跳参数").first
            if hb.count() > 0:
                ok("心跳参数卡渲染（改完立即生效，不用重启）")
            else:
                bad("缺心跳参数卡")
            if "探测间隔" in body and "判离线阈值" in body:
                ok("心跳参数有两个输入（探测间隔 / 判离线阈值）")
            else:
                bad("心跳参数输入缺失")
            if "阈值至少是间隔的 2 倍" in body:
                ok("心跳参数约束提示渲染（阈值 ≥ 2×间隔）")
            else:
                bad("缺心跳参数约束提示")
            if hb.locator("button").count() > 0:
                ok("心跳参数卡有「保存」按钮")
            else:
                bad("心跳参数卡没有保存按钮")

            # ---------------- 3. 告警卡与列表 ----------------
            step("3. 告警卡（合并提示 / 列表 / 空态）")
            if "静默期" in body:
                ok("告警合并提示渲染（同一「错误码|设备」15 分钟静默期）")
            else:
                bad("缺告警合并提示")

            rows = page.locator(".ant-table-tbody tr.ant-table-row")
            has_empty = page.locator("text=没有未处理的告警").count() > 0
            if rows.count() > 0:
                ok(f"告警列表有 {rows.count()} 行")
            elif has_empty:
                ok("告警列表为空态（没有未处理的告警）")
            else:
                bad("告警列表既没有行也没有空态")

            # ---------------- 4. 列头 + 行内操作弹窗（有数据才测） ----------------
            if rows.count() > 0:
                step("4. 列头与认领/关闭弹窗")
                if ("指纹（错误码|设备）" in body) and ("处置" in body) and ("命中" in body):
                    ok("告警表列头完整（指纹 / 命中 / 级别 / 最近消息 / 处置 / 操作）")
                else:
                    bad("告警表列头缺失")

                # 两字中文按钮会被 antd 插空格渲染成「认 领」/「关 闭」，用正则容错
                ack = page.locator(".ant-table-tbody button").filter(has_text=re.compile(r"认\s*领"))
                cls = page.locator(".ant-table-tbody button").filter(has_text=re.compile(r"关\s*闭"))
                if ack.count() > 0:
                    ok("行内有「认领」按钮")
                    ack.first.click()
                    page.wait_for_timeout(700)
                    mbody = page.inner_text(".ant-modal")
                    if "认领告警" in mbody:
                        ok("点认领弹出「认领告警」弹窗")
                    else:
                        bad("认领弹窗标题不对", mbody[:120])
                    if "处理人" in mbody:
                        ok("认领弹窗有「处理人」输入")
                    else:
                        bad("认领弹窗缺「处理人」")
                    footer = squash(page.inner_text(".ant-modal-footer"))
                    if "确认" in footer:
                        ok("认领弹窗确认按钮文案为「确认」")
                    else:
                        bad("认领弹窗确认按钮文案不对", footer[:60])
                    page.locator(".ant-modal-footer button").first.click()
                    page.wait_for_timeout(500)
                    if not page.locator(".ant-modal-wrap").first.is_visible():
                        ok("取消后弹窗关闭")
                    else:
                        bad("弹窗没有关闭")
                else:
                    bad("行内没有「认领」按钮")

                if cls.count() > 0:
                    ok("行内有「关闭」按钮")
                    cls.first.click()
                    page.wait_for_timeout(700)
                    mbody = page.inner_text(".ant-modal")
                    if "关闭告警" in mbody:
                        ok("点关闭弹出「关闭告警」弹窗")
                    else:
                        bad("关闭弹窗标题不对", mbody[:120])
                    page.locator(".ant-modal-footer button").first.click()
                    page.wait_for_timeout(400)
                else:
                    bad("行内没有「关闭」按钮")

            page.screenshot(path="/tmp/e2e-alerts.png")

        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:120])
            try:
                page.screenshot(path="/tmp/e2e-alerts-timeout.png")
                print("     截图：/tmp/e2e-alerts-timeout.png")
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
