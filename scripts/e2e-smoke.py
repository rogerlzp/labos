#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
前端 e2e 冒烟：锁住 5 条主链路（M8）。

为什么用 Python Playwright 而不是 @playwright/test：
  · 本机 `python3 -c "import playwright"` 就有，Chromium 也在缓存里；
  · 不往 webapp 里加 npm 依赖，CI 上一条 `pip install playwright` 就能跑。

覆盖：
  1. 登录 → 流程库列表有数据
  2. 点流程行 → 进设计器，BPMN 画布真的渲染出节点
  3. 执行流程 → 跳运行详情 → 流程图图例出现"已完成/未执行"
  4. 实验列表 → 点行进实验详情（现场扣留卡片 / 执行历史运行控制列）
     → 展开执行历史 → 出步骤明细
  5. 错误码页 → 列表有码 + 有策略/未覆盖提示
  6. 调度器页 → 统计卡 / 岛锁表 / 排列表 / 正常或堵塞结论

用法：
  python3 scripts/e2e-smoke.py                       # 默认 http://localhost:5173
  python3 scripts/e2e-smoke.py --base http://host:5173 --user admin --pass admin123
  python3 scripts/e2e-smoke.py --headed              # 看着它点

前置：前端 5173、API 18080、数据可登录（admin 账号存在）。
"""
import argparse
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
    # 已登录时直接有菜单
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    # 用户名框（有的版本预填 admin）
    text_inputs = page.locator('input[type="text"]')
    if text_inputs.count() > 0 and not text_inputs.first.input_value():
        text_inputs.first.fill(user)
    page.get_by_role("button", name="登 录").click()
    page.wait_for_selector("text=流程库", timeout=15000)


def nav(page, menu):
    """点左侧菜单（Ant Menu 的 item）。"""
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
            # ---------------- 1. 登录 + 流程库 ----------------
            step("1. 登录与流程库")
            login(page, args.base, args.user, args.pwd)
            ok("登录成功（左侧菜单出现）")
            header_text = page.inner_text("header")
            if "管理员" in header_text and args.user in header_text:
                ok("Header 显示当前账号与角色（admin · 管理员）")
            else:
                bad("Header 未显示账号角色", header_text[:120])

            nav(page, "流程库")
            rows = page.locator(".ant-table-tbody tr.ant-table-row")
            if rows.count() > 0:
                ok(f"流程库有数据（{rows.count()} 行）")
            else:
                bad("流程库列表为空")

            # ---------------- 2. 点行 → 设计器 ----------------
            step("2. 点流程 → 设计器画布")
            rows.first.click()
            page.wait_for_url("**/designer/**", timeout=15000)
            ok("已跳转设计器")

            # bpmn-js 渲染完成后才有 .djs-element
            page.wait_for_selector(".djs-container", timeout=20000)
            page.wait_for_timeout(1500)
            nodes = page.locator(".djs-element")
            if nodes.count() >= 2:
                ok(f"BPMN 画布渲染出 {nodes.count()} 个图元（含开始/任务/连线）")
            else:
                bad("画布没渲染出图元", f"{nodes.count()} 个")

            # ---------------- 3. 执行 → 运行详情 ----------------
            step("3. 执行流程 → 运行详情流程图")

            def open_runs():
                """打开「执行记录」并返回第一行；空弹窗返回 None。"""
                btn = page.get_by_role("button", name="执行记录")
                if btn.count() == 0:
                    return None
                btn.click()
                page.wait_for_timeout(1400)
                row = page.locator(
                    ".ant-modal .ant-table-row, .ant-drawer .ant-table-row").first
                return row if row.count() > 0 else None

            def close_runs():
                c = page.locator(".ant-modal-close, .ant-drawer-close")
                if c.count() > 0:
                    c.first.click()
                    page.wait_for_timeout(400)

            rec = page.get_by_role("button", name="执行记录")
            if rec.count() == 0:
                bad("设计器上没有「执行记录」按钮")
            else:
                first_run = open_runs()
                # 流程库首行可能是刚建、还没跑过的流程（例：门禁自建的 test /
                # 演示流程），此时执行记录是空的。不能把它当成功能缺陷，也不能
                # 把空弹窗留在页面上——否则下一步点菜单会被遮罩挡住而超时。
                if first_run is None:
                    close_runs()
                    nav(page, "流程库")
                    page.wait_for_timeout(1200)
                    rows2 = page.locator(".ant-table-tbody tr.ant-table-row")
                    for i in range(min(rows2.count(), 12)):
                        rows2.nth(i).click()
                        page.wait_for_url("**/designer/**", timeout=15000)
                        page.wait_for_timeout(1000)
                        first_run = open_runs()
                        if first_run is not None:
                            break
                        close_runs()
                        nav(page, "流程库")
                        page.wait_for_timeout(900)
                        rows2 = page.locator(".ant-table-tbody tr.ant-table-row")
                if first_run is None:
                    bad("前若干条流程都没有执行记录")
                else:
                    first_run.click()
                    page.wait_for_url("**/runs/**", timeout=15000)
                    ok("从执行记录进入运行详情")

            # 运行详情：流程图卡片 + 图例
            if "/runs/" in page.url:
                page.wait_for_selector("text=流程图（按执行状态着色）", timeout=15000)
                ok("运行详情有流程图卡片")
                # 图例是异步来的（要等图数据和状态都到），先等一会儿再整页取文本
                page.wait_for_timeout(2500)
                body = page.inner_text("body")
                if "图例" in body:
                    has = ("已完成" in body) or ("未执行" in body) or ("待人工" in body)
                    ok("图例显示了状态计数") if has else bad("图例没有状态计数")
                else:
                    bad("没有图例")
                # 节点表存在
                if page.locator("text=节点（").count() > 0:
                    ok("节点表已渲染")

            # ---------------- 4. 实验列表 → 详情 → 执行历史 ----------------
            step("4. 实验列表 → 详情 → 执行历史展开")
            nav(page, "实验列表")
            page.wait_for_timeout(1500)
            # 2026-11-03 起实验拆成「列表页 → 详情页」两层（菜单也从「实验编排」改名），
            # 原来是单页 + 下拉选实验，e2e 一直没跟着改 —— 这条断言此前是坏的。
            exp_rows = page.locator(".ant-table-tbody tr.ant-table-row")
            if exp_rows.count() == 0:
                bad("实验列表为空")
            else:
                ok(f"实验列表有 {exp_rows.count()} 行")
                body = page.inner_text("body")
                if "预计等待" in body:
                    ok("实验列表有「预计等待」列（M9）")
                else:
                    bad("实验列表缺「预计等待」列")
                # BUG-20261104-01 修复：列表按 id DESC，首行可能是"刚建、还没有任何 run"
                # 的实验（执行历史为空 → 无展开图标），不能拿首行当"一定有执行历史"。
                # 改为按行依次进详情，取第一条"有可展开执行历史"的；最多试 8 行。
                total = exp_rows.count()
                expanded_ok = False
                card_checked = False
                for i in range(min(total, 8)):
                    page.locator(".ant-table-tbody tr.ant-table-row").nth(i).click()
                    page.wait_for_selector("text=实验详情", timeout=15000)
                    page.wait_for_timeout(1200)
                    if i == 0:
                        ok("点行进实验详情")
                    body = page.inner_text("body")
                    if not card_checked:
                        ok("实验详情有「现场扣留」卡片（实验级暂停的闸门）") \
                            if "现场扣留" in body else bad("缺「现场扣留」卡片")
                        card_checked = True
                    expand = page.locator(".ant-table-row-expand-icon").first
                    if expand.count() > 0:
                        ok("执行历史有运行控制列（暂停/停止/恢复）") \
                            if "操作" in body else bad("执行历史缺运行控制列")
                        expand.click()
                        page.wait_for_timeout(2000)
                        body = page.inner_text("body")
                        if "共" in body and "步" in body:
                            ok("执行历史展开出步骤明细（共 N 步）")
                        else:
                            bad("展开后没有步骤明细")
                        expanded_ok = True
                        break
                    nav(page, "实验列表")
                    page.wait_for_timeout(1000)
                if not expanded_ok:
                    bad(f"前 {min(total, 8)} 条实验均无可展开的执行历史")

            # ---------------- 5. 错误码 ----------------
            step("5. 错误码页")
            nav(page, "错误码")
            page.wait_for_timeout(1500)
            ec_rows = page.locator(".ant-table-tbody tr.ant-table-row")
            if ec_rows.count() > 0:
                ok(f"错误码列表有 {ec_rows.count()} 条")
                body = page.inner_text("body")
                if "EC-" in body:
                    ok("能看到错误码编号")
                else:
                    bad("列表里没有 EC- 编号")
                if ("未覆盖本作用域" in body) or ("·" in body):
                    ok("策略标签/未覆盖提示已渲染")
                else:
                    bad("既没有策略标签也没有未覆盖提示")
            else:
                bad("错误码列表为空")

            # ---------------- 6. 调度器页 ----------------
            step("6. 调度器页（M9 可观测性）")
            nav(page, "调度器")
            page.wait_for_timeout(1800)
            body = page.inner_text("body")
            if "并发上限" in body and "worker 池" in body:
                ok("调度器统计卡已渲染（运行中/排队/并发上限/worker 池）")
            else:
                bad("调度器统计卡缺失")
            # ⚠ 文案跟着实现走：调度器原来的「岛锁」已改成**资源级锁**
            # （同岛不同设备可以并行），前端表标题因此是「设备占用（n）」。
            # 断言还写死「岛锁（」⇒ 永远匹配不上（2026-11-11 实测踩到）。
            if "设备占用（" in body and "排队（" in body:
                ok("设备占用/排队两张表已渲染")
            else:
                bad("设备占用/排队表缺失")
            if ("调度正常" in body) or ("堵塞" in body):
                ok("给出调度结论（正常 / 堵塞）")
            else:
                bad("没有调度结论告警")

            page.screenshot(path="/tmp/e2e-last.png")

            # ---------------- 7. 型号库页（M10 前置） ----------------
            step("7. 型号库页（M10 前置：哪些型号能对接/哪些等手册）")
            nav(page, "型号库")
            page.wait_for_timeout(2000)
            body = page.inner_text("body")
            if "可对接" in body and "待手册" in body:
                ok("型号库统计卡已渲染（可对接 / 待手册 / 产线在跑待接）")
            else:
                bad("型号库统计卡缺失")
            if "已适配型号" in body and "待手册型号" in body:
                ok("MANUAL / PLANNED 两张型号表已渲染")
            else:
                bad("型号表缺失")
            if ("360R" in body) and ("PlateLoc" in body):
                ok("产线在跑但缺手册的 360R / PlateLoc 已标出")
            else:
                bad("360R / PlateLoc 未标出")
            page.screenshot(path="/tmp/e2e-models.png")

        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:120])
            try:
                page.screenshot(path="/tmp/e2e-timeout.png")
                print("     截图：/tmp/e2e-timeout.png")
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
