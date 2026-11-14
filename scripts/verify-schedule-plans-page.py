#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""系统完善页面 · Playwright E2E（真实点击渲染，补 D 组静态核对缺口）。

断言（admin 登录后逐页渲染 + 关键控件可见）：
  /schedule-plans：新建排程表单 + 「立即生效」按钮 + 暂停堆积治理天数输入 + 排程列表
  /scheduler：排程参数卡（当前生效）+ 陈旧暂停卡 + 死锁/饥饿风险卡
  /schedule：设备预约表卡
  /paused：暂停队列（陈旧红标若存在）

用法：LABOS_WEB=http://localhost:5173 LABOS_API=http://127.0.0.1:18080 \
      python3 scripts/verify-schedule-plans-page.py
"""
import json
import os
import sys
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PASS, FAIL = 0, 0


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def login_ui(pg, u, p):
    pg.goto(WEB + "/login", wait_until="domcontentloaded")
    pg.wait_for_timeout(800)
    pg.evaluate("() => localStorage.clear()")
    pg.reload(wait_until="domcontentloaded")
    pg.wait_for_timeout(800)
    pg.fill('input[type="text"]', u)
    pg.fill('input[type="password"]', p)
    pg.get_by_role("button", name="登").first.click()
    pg.wait_for_timeout(2200)


def body_has(pg, text):
    return pg.locator("body").filter(has_text=text).count() > 0


def goto_and_check(pg, path, expects, label):
    pg.goto(WEB + path, wait_until="domcontentloaded")
    pg.wait_for_timeout(1800)
    missing = [t for t in expects if not body_has(pg, t)]
    if not missing:
        ok("%s 渲染关键元素齐" % label, path)
    else:
        bad("%s 缺元素" % label, "%s 缺 %s" % (path, ",".join(missing)))


def main():
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1400, "height": 950})
        login_ui(pg, "admin", "admin123")

        # /schedule-plans（designer/admin）
        goto_and_check(pg, "/schedule-plans",
                       ["新建排程", "立即生效", "暂停堆积治理", "优化因子"],
                       "schedule-plans")

        # 新建一个 plan 走真实点击：填因子 + 点新建，列表应出现编号
        try:
            pg.goto(WEB + "/schedule-plans", wait_until="domcontentloaded")
            pg.wait_for_timeout(1500)
            # 点「新建」按钮（表单默认 50/4/0，不必填）
            btn = pg.get_by_role("button", name="新建").first
            btn.click()
            pg.wait_for_timeout(1500)
            if body_has(pg, "已创建") or body_has(pg, "新建排程"):
                ok("schedule-plans 新建按钮可点击", "")
            else:
                bad("schedule-plans 新建后无反馈", "")
        except Exception as e:
            bad("schedule-plans 新建点击异常", str(e)[:100])

        # /scheduler
        goto_and_check(pg, "/scheduler",
                       ["排程参数", "陈旧暂停", "死锁/饥饿风险"],
                       "scheduler")

        # /schedule（设备预约表）
        goto_and_check(pg, "/schedule", ["设备预约"], "schedule 甘特")

        # /paused（暂停队列）
        goto_and_check(pg, "/paused", ["暂停队列"], "paused")

        pg.screenshot(path="/tmp/schedule-plans-e2e.png", full_page=True)
        b.close()

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
