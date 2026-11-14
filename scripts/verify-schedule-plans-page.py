#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""系统完善页面 · Playwright E2E（真实点击渲染，补 D 组静态核对缺口）。

断言（admin 登录后逐页渲染 + 关键控件可见）：
  /schedule-plans：新建排程表单 + 「立即生效」按钮 + 暂停堆积治理天数输入 + 排程列表
  /scheduler：排程参数卡（当前生效）+ 陈旧暂停卡 + 死锁/饥饿风险卡
  /schedule：设备预约表卡
  /paused：暂停队列（陈旧红标若存在）

**清理（2026-11-14 修）**
本脚本以前**完全不清理**：Playwright 真实点「新建」建的排程计划留在库里，
每跑一次多一条 ⇒ 「数据基线 plan=0」不成立（实测三条 queued plan 对应三次运行）。
现在：
  · 前置清理：删掉**自动生成号**的排程计划（`PLAN-<数字>`，页面上「新建」按钮产出的那种）
    —— 手工建的排程计划有业务 planNo（`VERIFY-*` 之类），不受影响；
  · 后置清理：删掉本次「新建」真实产生的那些（前后列表差集）；
  · 断言：点完「新建」后列表里**真的多了一条**（以前只断言"有反馈"，没断言数据落地）。

用法：LABOS_WEB=http://localhost:5173 LABOS_API=http://127.0.0.1:18080 \
      python3 scripts/verify-schedule-plans-page.py
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
PASS, FAIL = 0, 0

# 页面上「新建」按钮在服务端生成的排程编号格式（ScheduleController.createPlan：
# "PLAN-" + System.currentTimeMillis() % 100000000L）⇒ 只清这一类，别误删手工排程。
AUTO_PLAN_NO = re.compile(r"^PLAN-\d{1,8}$")


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def api_login(user, pw):
    req = urllib.request.Request(API + "/api/auth/login", method="POST",
                                 data=json.dumps({"username": user, "password": pw}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode()).get("token")


def api_call(token, path, method="GET"):
    req = urllib.request.Request(API + path, method=method,
                                 headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


def list_plans(token):
    st, body = api_call(token, "/api/schedule-plans")
    if st != 200:
        return []
    return body.get("items") or []


def delete_plans(token, plans):
    """按 id 物理删除（DELETE 端点是 2026-11-14 新加的）。"""
    n = 0
    for p in plans:
        st, _ = api_call(token, "/api/schedule-plans/%d" % int(p["id"]), "DELETE")
        if st == 200:
            n += 1
        else:
            bad("清理排程 %s 失败" % p.get("planNo"), "HTTP %d" % st)
    return n


def preclean(token):
    """前置清理：删掉自动生成号的排程计划（页面上「新建」按钮产出的那些）。"""
    stale = [p for p in list_plans(token)
             if AUTO_PLAN_NO.match(str(p.get("planNo", "")))]
    n = delete_plans(token, stale)
    ok("前置清理自动生成号排程 %d 条" % n)


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

    token = api_login("admin", "admin123")
    preclean(token)
    before = {str(p.get("planNo")) for p in list_plans(token)}

    created = []
    try:
        with sync_playwright() as pw:
            b = pw.chromium.launch(headless=True)
            pg = b.new_page(viewport={"width": 1400, "height": 950})
            login_ui(pg, "admin", "admin123")

            # /schedule-plans（designer/admin）
            goto_and_check(pg, "/schedule-plans",
                           ["新建排程", "立即生效", "暂停堆积治理", "优化因子"],
                           "schedule-plans")

            # 新建一个 plan 走真实点击：点「新建」按钮（表单默认 50/4/0）
            try:
                pg.goto(WEB + "/schedule-plans", wait_until="domcontentloaded")
                pg.wait_for_timeout(1500)
                btn = pg.get_by_role("button", name="新建").first
                btn.click()
                pg.wait_for_timeout(1800)
                if body_has(pg, "已创建") or body_has(pg, "新建排程"):
                    ok("schedule-plans 新建按钮可点击", "")
                else:
                    bad("schedule-plans 新建后无反馈", "")
            except Exception as e:
                bad("schedule-plans 新建点击异常", str(e)[:100])

            # 断言数据**真的落地**了（以前只断言"有反馈"，没断言库里多了一条）
            after = list_plans(token)
            new_nos = [str(p.get("planNo")) for p in after
                       if str(p.get("planNo")) not in before]
            if new_nos:
                ok("新建后排程真的落库", "新增 %d 条：%s" % (len(new_nos), ",".join(new_nos)))
                created = [p for p in after if str(p.get("planNo")) in new_nos]
            else:
                bad("新建后排程没落库（列表没多出新编号）", "")

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
    finally:
        # 兜底：无论上面成败，本次新建的都要删掉（幂等的关键）
        if created:
            n = delete_plans(token, created)
            ok("清理本次新建排程 %d 条" % n)
        preclean(token)

    left = list_plans(token)
    if left:
        bad("收尾后排程表不为空", "剩 %d 条" % len(left))
    else:
        ok("收尾后排程表归零（plan=0）")

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
