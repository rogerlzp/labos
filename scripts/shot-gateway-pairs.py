#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拍「网关成对入口」的设计器画布图（给人看的素材，不是断言脚本）。

拖出并行分叉 / 并行汇合 / 条件分叉 / 条件汇合，连成一对，截整页：
左边 palette 的 6 个成对入口、画布上绿边（分叉）/橙边（汇合）、
每个网关下方的角色标签，一眼就能看出「哪里是分、哪里是合」。

用法：
  python3 scripts/shot-gateway-pairs.py --base http://localhost:5173
"""
import argparse
import importlib.util
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "dh", os.path.join(_HERE, "designer-helpers.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["dh"] = F
_spec.loader.exec_module(F)

OUT = os.path.join(os.path.dirname(_HERE), "docs", "media")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=F.BASE_WEB)
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    token = F.api("/api/auth/login", "POST",
                  {"username": args.user, "password": args.pwd})["token"]
    ctx = F.Ctx(token)

    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 1500, "height": 950})
        page.set_default_timeout(25000)
        F.login(page, args.base, args.user, args.pwd)

        wf_id = F.make_workflow(page, ctx, "SHOT-网关成对")
        for s in ("S", "E"):
            F.delete_node(page, s)

        ids = {}
        for n, action, x, y in (("PS", "create.parallel-split", 190, 90),
                               ("A", "create.task", 370, 40),
                               ("B", "create.task", 370, 190),
                               ("PM", "create.parallel-merge", 560, 120),
                               ("CS", "create.conditional-split", 190, 330),
                               ("CA", "create.task", 370, 300),
                               ("CB", "create.task", 370, 430),
                               ("CM", "create.conditional-merge", 560, 370),
                               ("T", "create.task", 730, 240)):
            F.clear_selection(page)
            eid = F.place(page, action, x, y)
            if not eid:
                print("落点失败：", n)
                b.close()
                ctx.cleanup()
                return
            ids[n] = eid

        for a, c in (("PS", "A"), ("PS", "B"), ("A", "PM"), ("B", "PM"),
                     ("PM", "T"),
                     ("CS", "CA"), ("CS", "CB"), ("CA", "CM"), ("CB", "CM"),
                     ("T", "CS")):
            F.connect(page, ids[a], ids[c])
        page.wait_for_timeout(1200)
        F.save(page)
        page.wait_for_timeout(2500)

        path = os.path.join(OUT, "designer-gateway-pairs.png")
        page.screenshot(path=path, full_page=True)
        print("已截图：docs/media/designer-gateway-pairs.png")

        b.close()
    ctx.cleanup()


if __name__ == "__main__":
    main()
