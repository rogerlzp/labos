#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证「网关可读性」改造：命名 + 自动默认名 + 结束标识高亮。

在页面上真拖一条含异或 + 并行的流程，给网关**起名**（走节点设置面板），
再截图对比「改造前 / 改造后」的可读性。

产物：
  docs/media/designer-gateway-labels.png   —— 带名字的设计器画布
  docs/media/rundetail-gateway-labels.png —— 运行详情图（结束标识 + 网关描边）

用法：
  python3 scripts/shot-gateway-labels.py --base http://localhost:5173
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

OUT = os.path.join(os.path.dirname(_HERE), "docs", "media")


def clear_selection(page):
    """取消选中（bpmn-js 的 context-pad 会盖住 palette，导致下一个点击一直 retry）。

    用 Esc 而不是点空白：context-pad 就挂在画布上，点「空白」可能正好点在它身上
    （实测点画布左下角仍被 "text-annotation entry intercepts pointer events" 挡住）。
    Esc 是 bpmn-js 自己认可的取消方式，稳。
    """
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    # 兜底：把 context-pad 从 DOM 里摘掉（bpmn-js 不会立刻自己移除）
    try:
        page.evaluate("""() => {
            document.querySelectorAll('.djs-context-pad-parent').forEach(el => el.remove());
        }""")
    except Exception:
        pass
    page.wait_for_timeout(200)


def set_name(page, key, name):
    """选中图元 → 右栏「网关设置」卡 → 「显示名」输入框 → 填值 → 失焦触发保存。

    ⚠ 网关有**自己的一张卡**（不是「节点设置」卡）：网关不需要绑定设备/方法，
      而「节点设置」卡挂在 compile 结果上（compile 只认 task），网关选中后那里
      只有一句「还没保存，先保存画布才能绑定设备与方法」。
    """
    page.locator(f'.djs-element[data-element-id="{key}"]').first.click()
    page.wait_for_timeout(1400)
    card = page.locator(".ant-card", has_text="网关设置").first
    if card.count() == 0:
        return False
    inputs = card.locator("input.ant-input")
    if inputs.count() == 0:
        return False
    inputs.first.fill(name)
    inputs.first.blur()
    page.wait_for_timeout(900)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
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

        wf_id = F.make_workflow(page, ctx, "SHOT-带名")
        for s in ("S", "E"):
            F.delete_node(page, s)

        # 异或分叉 → 并行分叉 →（两条支路）→ 并行汇合 → 结束
        # P2 是**真汇合**：两条入边（A、B 都汇到它）+ 一条出边。
        #   （上一版 P2 只有 1 进 1 出，按后端口径就是「直通」，
        #     右栏如实显示「直通（单进单出）」—— 这不是 bug，是图没画对。）
        # ⚠ 两个 ＋ 形网关（并行分叉 / 并行汇合）之间要留够横向距离，
        #   否则它们的标签（都在节点正下方）会叠在一起。
        # ⚠ palette 入口是**成对**的（2026-11-11）：并行分叉 / 并行汇合各一个，
        #   不再有一个不分叉汇合的通用「并行网关」入口。
        ids = {}
        for n, action, x, y in (("S", "create.start-event", 120, 120),
                               ("X1", "create.exclusive-split", 230, 120),
                               ("P1", "create.parallel-split", 340, 120),
                               ("A", "create.task", 460, 50),
                               ("B", "create.task", 460, 200),
                               ("P2", "create.parallel-merge", 620, 130),
                               ("C", "create.task", 760, 130),
                               ("E", "create.end-event", 760, 250)):
            clear_selection(page)
            ids[n] = F.place(page, action, x, y)
        miss = [k for k, v in ids.items() if not v]
        if miss:
            print("落点失败：", miss)
            b.close()
            return

        for a, c in (("S", "X1"), ("X1", "P1"), ("P1", "A"), ("P1", "B"),
                     ("A", "P2"), ("B", "P2"), ("P2", "C"), ("C", "E")):
            F.connect(page, ids[a], ids[c])
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2500)

        # ⚠ 必须先保存，右栏才会出现「节点设置」卡：那个卡挂在 compile 结果上，
        #   新拖的节点还没落库 → compile 里没有它 → 右栏只给一个「保存并继续设置」按钮。
        #   （这是设计器的既有结构，不是 bug。）
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(3000)

        # 给网关起名（走真实 UI：节点设置面板）
        names = {"X1": "异或分叉", "P1": "并行分叉", "P2": "并行汇合"}
        okc = 0
        for k, nm in names.items():
            clear_selection(page)
            if set_name(page, ids[k], nm):
                okc += 1
            else:
                print("命名失败：", k)
        print("命名成功 %d/%d" % (okc, len(names)))

        # 名字要真的落到 XML 里
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2500)
        xml = F.api(f"/api/workflows/{wf_id}/graph", token=token)
        xml = xml if isinstance(xml, str) else F.json.dumps(xml)
        for nm in names.values():
            print("  XML 含「%s」: %s" % (nm, nm in xml))

        # 截图：整页（要含右侧「节点设置」卡，才能看到「显示名」输入框与建议名按钮）
        page.screenshot(path=os.path.join(OUT, "designer-gateway-labels.png"),
                        full_page=True)
        print("已截图：docs/media/designer-gateway-labels.png")

        # 跑一次，拿运行详情图
        rid, st = F.run_workflow(ctx, wf_id)
        print("run #%s → %s" % (rid, st))
        if rid:
            page.goto(args.base + "/runs/" + str(rid), wait_until="networkidle")
            page.wait_for_timeout(3500)
            page.screenshot(path=os.path.join(OUT, "rundetail-gateway-labels.png"),
                            full_page=True)
            print("已截图：docs/media/rundetail-gateway-labels.png")

        b.close()
    ctx.cleanup()


if __name__ == "__main__":
    main()
