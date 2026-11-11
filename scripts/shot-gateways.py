#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""截「异或网关 vs 并行网关」的对照图（给人看的，不是断言脚本）。

在设计器里真拖两条最小流程，各自截图：
  A. 异或网关（palette 文案 "Create gateway"，图标 bpmn-icon-gateway-none，X 形）
  B. 并行网关（palette 文案 "Create parallel gateway"，图标 bpmn-icon-gateway-parallel，＋形）

产物：docs/media/designer-gateways-<A|B>.png

用法：
  python3 scripts/shot-gateways.py --base http://localhost:5173
"""
import argparse
import importlib.util
import os
import re
import sys

# 辅助函数在 designer-helpers.py（自包含）。
# ⚠ 原先这里 import 的是 e2e-flow-matrix.py —— 那个文件在 2026-11-11
#   「旧门禁体系整体退役」时被删了（7c9a2f7），本脚本从那时起就**跑不起来**。
#   页面交互的写法沉淀到了 designer-helpers.py，这里改指过去。
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "dh", os.path.join(_HERE, "designer-helpers.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["dh"] = F
_spec.loader.exec_module(F)

OUT_DIR = os.path.join(os.path.dirname(_HERE), "docs", "media")


def shot(page, ctx, name, title, nodes, edges, out):
    """拖一张图 → 保存 → 截图。"""
    wf_id = F.make_workflow(page, ctx, "SHOT-" + name)
    for seed in ("S", "E"):
        F.delete_node(page, seed)
    ids = {}
    for n, action, x, y in nodes:
        ids[n] = F.place(page, action, x, y)
    miss = [k for k, v in ids.items() if not v]
    if miss:
        print("落点失败：", miss)
        return
    for a, b in edges:
        F.connect(page, ids[a], ids[b])
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2000)
    # 截画布
    page.locator(".djs-container").screenshot(path=out)
    print("已截图：%s（流程 #%s）%s" % (out, wf_id, title))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    token = F.api("/api/auth/login", "POST",
                  {"username": args.user, "password": args.pwd})["token"]
    ctx = F.Ctx(token)

    from playwright.sync_api import sync_playwright
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

        # A：异或网关（X 形）—— 一分为多选一
        # ⚠ palette 入口 2026-11-11 起改成**成对**（分叉/汇合各一个），
        #   通用「异或网关」入口已撤下。
        shot(page, ctx, "xor", "异或网关",
             [("S", "create.start-event", 140, 120),
              ("X", "create.exclusive-split", 300, 120),
              ("A", "create.task", 480, 60),
              ("B", "create.task", 480, 200),
              ("E", "create.end-event", 560, 300)],
             [("S", "X"), ("X", "A"), ("X", "B"), ("A", "E"), ("B", "E")],
             os.path.join(OUT_DIR, "designer-gateways-A.png"))

        # B：并行网关（＋形）—— 一分为多，全走
        shot(page, ctx, "par", "并行网关",
             [("S", "create.start-event", 140, 120),
              ("P", "create.parallel-split", 300, 120),
              ("A", "create.task", 480, 60),
              ("B", "create.task", 480, 200),
              ("E", "create.end-event", 560, 300)],
             [("S", "P"), ("P", "A"), ("P", "B"), ("A", "E"), ("B", "E")],
             os.path.join(OUT_DIR, "designer-gateways-B.png"))

        b.close()
    ctx.cleanup()


if __name__ == "__main__":
    main()
