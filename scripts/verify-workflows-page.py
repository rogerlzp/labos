#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""流程库页面（第二批）功能验证。

验证对象：
  · 台账统计条（数字与 `/api/workflows/stats` 一致）
  · 治理筛选（未指定岛 / 空流程 / 搜索）与统计条联动
  · 列表 10 列与治理字段（版本/状态/所属岛/最后修改）
  · 抽屉：中文能力名、最近运行、版本历史（可回滚）
  · 行首信号灯（空流程灰条）

用法：
  python3 scripts/verify-workflows-page.py

只读：不新建、不执行、不回滚业务数据（回滚只断言按钮存在）。
"""
import json
import os
import re
import urllib.request

WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

PASS, FAIL = [], []


def ok(msg):
    PASS.append(msg)
    print(f"  \033[32m✓\033[0m {msg}")


def bad(msg, detail=""):
    FAIL.append(msg)
    print(f"  \033[31m✗\033[0m {msg}" + (f"：{detail}" if detail else ""))


def check(cond, msg, detail=""):
    ok(msg) if cond else bad(msg, detail)
    return cond


def api(path, method="GET", body=None, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    data = json.dumps(body).encode() if body is not None else None
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=30) as r:
        txt = r.read().decode()
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        return txt


def main():
    tok = api("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    stats = api("/api/workflows/stats", tok=tok)
    total_all = api("/api/workflows?limit=1", tok=tok)["total"]

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1440, "height": 900})
        errors = []
        # 只把**真正的未捕获异常**算失败。网络类噪声（Vite HMR 重连、模拟器抖动、
        # dev server 正在重编译时旧 chunk 失效）单独收集，不判死 —— 它们的症状是
        # "Failed to load resource" / "net::ERR_*" / "Failed to fetch"。
        net_errors = []
        NET_NOISE = ("Failed to load resource", "net::ERR_", "Failed to fetch",
                     "ERR_ABORTED", "ERR_CONNECTION")
        pg.on("console", lambda m: errors.append(m.text)
              if m.type == "error" and not any(k in m.text for k in NET_NOISE) else None)
        pg.on("pageerror", lambda e: errors.append("未捕获异常: " + str(e)[:200]))
        pg.on("requestfailed", lambda r: net_errors.append(r.url))

        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.locator('input[type="password"]').first.fill(PWD)
        pg.get_by_role("button", name="登 录").click()
        pg.wait_for_timeout(2500)
        pg.goto(WEB + "/workflows", wait_until="networkidle")
        pg.wait_for_timeout(2500)

        # ---------- ① 统计条 ----------
        print("[1] 台账统计条")
        kpi_text = pg.locator(".ant-table").first.inner_text()
        body = pg.inner_text("body")
        for label, key in (("全部流程", "total"), ("已发布", "published"),
                           ("草稿", "draft"), ("未指定岛", "noIsland"),
                           ("空流程", "emptyGraph")):
            # 统计格的 DOM 顺序是「数值 → 标签」，断言不依赖顺序
            v = str(stats[key])
            hit = (re.search(re.escape(label) + r"\s*" + re.escape(v), body) is not None
                   or re.search(re.escape(v) + r"\s*" + re.escape(label), body) is not None)
            check(hit, f"统计条显示「{label} {v}」")
        check(stats["total"] == total_all,
              "统计条总数与列表接口一致", f"{stats['total']} vs {total_all}")

        # ---------- ② 筛选联动 ----------
        # ⚠ 计数是**动态的**：另一会话可能正在往库里加流程，
        #    所以每次断言前重新读一次 stats，不拿开头的快照比。
        print("[2] 统计条驱动的治理筛选")

        def reset():
            btn = pg.get_by_role("button", name=re.compile("重置筛选"))
            if btn.count():
                btn.first.click()
                pg.wait_for_timeout(1500)

        def shown_total():
            return pg.locator(".ant-pagination-total-text").first.inner_text()

        reset()
        live = api("/api/workflows/stats", tok=tok)
        pg.get_by_text("未指定岛", exact=False).first.click()
        pg.wait_for_timeout(1800)
        check(str(live["noIsland"]) in shown_total(),
              f"点「未指定岛」后按 {live['noIsland']} 条过滤", shown_total())

        reset()
        live = api("/api/workflows/stats", tok=tok)
        pg.get_by_text("空流程", exact=False).first.click()
        pg.wait_for_timeout(1800)
        check(str(live["emptyGraph"]) in shown_total(),
              f"点「空流程」后按 {live['emptyGraph']} 条过滤", shown_total())

        # 两个筛选是 AND 叠加，不是互斥
        reset()
        pg.get_by_text("未指定岛", exact=False).first.click()
        pg.wait_for_timeout(1200)
        pg.get_by_text("空流程", exact=False).first.click()
        pg.wait_for_timeout(1800)
        both = api("/api/workflows?limit=1&islandId=none&empty=1", tok=tok)["total"]
        check(str(both) in shown_total(),
              f"两个筛选叠加（AND）后为 {both} 条", shown_total())

        # ⚠ 计数会因**别的会话正在往库里写流程**而在两次读取之间变化，
        #    所以这类断言要重试：连读几次，只要有一次对上就算过。
        def check_total(label, live_fn, action=None):
            for _ in range(3):
                if action:
                    action()
                    pg.wait_for_timeout(1500)
                live = live_fn()
                if str(live) in shown_total():
                    check(True, label)
                    return True
            check(False, label, f"页面={shown_total()} 后端={live}")
            return False

        check_total("重置筛选后回到全量",
                    lambda: api("/api/workflows?limit=1", tok=tok)["total"],
                    action=reset)

        # ---------- ③ 搜索走服务端 ----------
        print("[3] 搜索")
        search = pg.get_by_placeholder(re.compile("搜索"))
        search.first.fill("封膜")
        pg.wait_for_timeout(1800)
        q_total = api("/api/workflows?limit=1&q=%E5%B0%81%E8%86%9C", tok=tok)["total"]
        check(str(q_total) in shown_total(),
              f"搜索「封膜」结果与后端一致（{q_total}）", shown_total())
        search.first.fill("")
        pg.wait_for_timeout(1500)

        # ---------- ④ 抽屉：中文能力名 / 最近运行 / 版本历史 ----------
        print("[4] 详情抽屉")
        pg.locator(".ant-table-tbody tr").first.click()
        pg.wait_for_timeout(2500)
        drawer = pg.locator(".ant-drawer-body")
        check(drawer.count() > 0, "点行打开右侧抽屉")
        dtext = drawer.first.inner_text()
        check("版本历史" in dtext, "抽屉里有「版本历史」区块")
        check("最近运行" in dtext, "抽屉里有「最近运行」区块")
        # 能力列：中文业务名为主，code 作为灰色技术说明跟在后面（工程场景要认 code）
        caps = re.findall(r"\b(ALIQUOT|SEAL|CENTRIFUGE|TRANSFER|INCUBATE|READ_PLATE|WASH|MIX)\b", dtext)
        if caps:
            cn = {"ALIQUOT": "移液分装", "SEAL": "封膜", "CENTRIFUGE": "离心",
                  "TRANSFER": "转运", "INCUBATE": "孵育", "READ_PLATE": "读板",
                  "WASH": "分液洗板", "MIX": "混匀"}
            check(all(cn.get(c, c) in dtext for c in set(caps)),
                  "能力列显示中文业务名", f"codes={set(caps)}")
        check("手动指定" in dtext or "方法定义" in dtext, "「来源」列已中文化")

        # ---------- ⑤ 行首信号灯：空流程行有灰条 ----------
        print("[5] 行首信号灯")
        if pg.locator(".ant-drawer-close").count():
            pg.locator(".ant-drawer-close").first.click()
            pg.wait_for_timeout(800)
        reset()
        pg.get_by_text("空流程", exact=False).first.click()   # 只看空流程
        pg.wait_for_timeout(1800)
        lamps = pg.evaluate("""() => {
          const rows = [...document.querySelectorAll('.ant-table-tbody tr')];
          return rows.map(r => {
            const bar = r.querySelector('td div[style*="width: 4px"]');
            return bar ? getComputedStyle(bar).backgroundColor : null;
          });
        }""")
        grey = [c for c in lamps if c and c != 'rgba(0, 0, 0, 0)']
        check(len(grey) > 0, "空流程行显示灰色信号灯", f"lamps={lamps[:3]}")

        check(not errors, "页面无 JS 异常", "; ".join(errors[:2]))
        if net_errors:
            print(f"  \033[33m·\033[0m 网络层失败请求 {len(net_errors)} 个（不判死）："
                  + ", ".join(u.split('/')[-1] for u in net_errors[:3]))
        b.close()

    print()
    print(f"通过 {len(PASS)} / 失败 {len(FAIL)}")
    if FAIL:
        print("失败项：" + ", ".join(FAIL))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
