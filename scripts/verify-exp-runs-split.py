#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测（页面层）：实验的**配置**与**执行记录**分成两页。

**为什么要有这条**：用户现场反馈「实验列表，和实验执行列表，需要分开来，
在一个实验里面，不需要把执行记录也放到下面，这样会看起来很乱」。

之前实验详情页（`/experiments/:id`）把编排、变量、批次、扣留、**执行记录表**
全堆在一页 ⇒ 执行记录一多就把配置挤到下面，现场看着很乱。

判据：
  ① 实验详情页**没有**执行记录表，只有「打开执行记录」入口；
  ② 点入口跳到 `/experiments/:id/runs`；
  ③ 执行记录页有执行历史表（能展开看步骤）、有「返回实验」；
  ④ 实验列表页每行有「执行记录」入口。

前置：内核 + 前端在跑。
用法：
  python3 scripts/verify-exp-runs-split.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from playwright.sync_api import sync_playwright

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")

PASS = FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m)


def bad(m, d=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=60) as r:
        return json.loads(r.read().decode())


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    out = subprocess.run(["psql", "-h", os.environ["LABOS_DB_HOST"],
                          "-U", os.environ["LABOS_DB_USER"],
                          "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
                         env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return out.stdout.strip()


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    exps = api("/api/experiments", "GET", token=token)["items"]
    if not exps:
        bad("库里没有实验 —— 先造一个")
        return 1
    exp = exps[0]
    runs = api("/api/experiments/%d/runs" % exp["id"], "GET", token=token).get("items", [])
    print("  实验 %d「%s」，执行记录 %d 条" % (exp["id"], exp["name"], len(runs)))

    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1600, "height": 1100})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)

        # ---- ① 实验详情页：没有执行记录表，只有入口 ----
        pg.goto("%s/experiments/%d" % (WEB, exp["id"]), wait_until="domcontentloaded")
        pg.wait_for_timeout(4500)
        body = pg.inner_text("body")
        if "打开执行记录" in body:
            ok("实验详情页有「打开执行记录」入口")
        else:
            bad("实验详情页没有「打开执行记录」入口")
        # 执行记录表的特征：列头 "run" + 状态 + 进度 + 暂停原因
        has_table = ("暂停原因" in body and "进度" in body
                     and pg.locator("th", has_text="run").count() > 0)
        if not has_table:
            ok("实验详情页**不再有**执行记录表")
        else:
            bad("实验详情页仍然挂着执行记录表（还是挤在一起）")
        # 页面仍然保留配置内容
        if "实验用到的变量" in body or "实验变量" in body:
            ok("实验详情页仍然是配置页（变量/编排还在）")
        else:
            bad("实验详情页的配置内容不见了")
        pg.screenshot(path="/tmp/exp-detail-no-runs.png", full_page=False)

        # ---- ②③ 点入口 → 执行记录页 ----
        btn = pg.get_by_role("button", name="打开执行记录").first
        if btn.count() == 0:
            bad("没有入口按钮可点")
            b.close()
            return 1
        btn.click()
        pg.wait_for_timeout(4000)
        if pg.url.rstrip("/").endswith("/experiments/%d/runs" % exp["id"]):
            ok("入口跳到 /experiments/%d/runs" % exp["id"])
        else:
            bad("入口没有跳到执行记录页", pg.url)
        body2 = pg.inner_text("body")
        if "实验执行记录" in body2:
            ok("执行记录页有标题")
        else:
            bad("执行记录页没有标题")
        if pg.locator("th", has_text="run").count() > 0:
            ok("执行记录页有执行历史表")
        else:
            bad("执行记录页没有执行历史表")
        if "返回实验" in body2:
            ok("执行记录页有「返回实验」")
        else:
            bad("执行记录页没有「返回实验」")
        # 步骤表是默认展开的（现场反馈过"点不进去、看不到流程状态"）
        if "打开流程图" in body2:
            ok("执行记录页默认展开步骤表（能看到「打开流程图」入口）")
        else:
            bad("执行记录页没有展开步骤表")
        pg.screenshot(path="/tmp/exp-runs-page.png", full_page=False)

        # ---- ④ 实验列表页每行的入口 ----
        pg.goto(WEB + "/experiments", wait_until="domcontentloaded")
        pg.wait_for_timeout(4000)
        rows = pg.locator(".ant-table-row")
        found = False
        for i in range(min(rows.count(), 5)):
            r = rows.nth(i)
            if r.get_by_role("button", name="执行记录").count() > 0:
                found = True
                break
        if found:
            ok("实验列表页每行有「执行记录」入口")
        else:
            bad("实验列表页没有「执行记录」入口")
        pg.screenshot(path="/tmp/exp-list-runs-entry.png", full_page=False)
        b.close()

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
