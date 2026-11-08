#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 网关「成对」与多分支场景（页面实操专项）。

为什么单独有它：
  `e2e-multi-parallel.py` 那一版虽然画了 4 个网关，但按后端口径
  （入边>1 汇合 / 出边>1 分叉）一算，实际是：

      P1  入=1 出=2 → 分叉
      P2  入=1 出=2 → 分叉
      X   入=1 出=1 → **直通**（名字叫「异或分叉」，名不副实）
      X2  入=1 出=1 → **直通**

  即：**没有���个汇合点**，异或那两个是直通不是分叉 —— 名字和结构对不上，
  用户一眼也看不出「从哪里分、从哪里合」。用户 2026-11-06 明确指出：
  「异或不对……而且异或一样，需要有异或开始和异或结束作为一起的才能看清楚」。

这一节测两件事：
  1. **成对**：异或分叉 + 异或汇合、并行分叉 + 并行汇合，**必须成对出现**；
  2. **N 值**：并行和异或**各自**都要测 N=2 与 N=3（不止一条分支）。

  命名**走页面上的「用建议名」按钮**（不是脚本硬写名字）——
  这样断言的是「引擎按入/出边数推出的角色」与「图上显示的名字」一致，
  名字对不上结构的问题会被当场抓住。

图的形状（两张）：

  图 1（N=2）：
      S ─ XS ─┬─ A ─┐
               └─ B ─┴─ XJ ─ PS ─┬─ C ─┐
                                  └─ D ─┴─ PJ ─ E
      XS=异或分叉  XJ=异或汇合  PS=并行分叉  PJ=并行汇合

  图 2（N=3）：
      S ─ XS ─┬─ A ─┐
               ├─ B ─┼─ XJ ─ PS ─┬─ P ─┐
               └─ C ─┘           ├─ Q ─┼─ PJ ─ E
                                  └─ R ─┘

前置：
  · 前端可访问（默认 http://localhost:5173）；API 18080 在跑；admin/admin123 可登录；
  · 连库环境变量 LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME / LABOS_DB_USER / LABOS_DB_PASS
    （缺失则整脚本 SKIP——要断言 run_node 必须连库）。

用法：
  python3 scripts/e2e-gateway-pairs.py
  python3 scripts/e2e-gateway-pairs.py --base http://127.0.0.1:8080 --headed
"""
import argparse
import importlib.util
import os
import re
import sys

# 复用 e2e-flow-matrix.py 里已经踩过坑的函数（place/connect/set_node_builtin/
# make_workflow/run_workflow/graph_edges/nav…），不重写——
# 那些函数的每个坐标/时序参数都是踩坑调出来的。
# 文件名带连字符，不能直接 import，按路径加载。
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_flow_matrix", os.path.join(_HERE, "e2e-flow-matrix.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["e2e_flow_matrix"] = F
_spec.loader.exec_module(F)


def clear_selection(page):
    """取消选中（bpmn-js 的 context-pad 会盖住 palette，导致下一个点击一直 retry）。

    用 Esc 而不是点空白：context-pad 就挂在画布上，点「空白」可能正好点在它身上。
    Esc 是 bpmn-js 自己认可的取消方式，稳。
    """
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    try:
        page.evaluate("""() => {
            document.querySelectorAll('.djs-context-pad-parent').forEach(el => el.remove());
        }""")
    except Exception:
        pass
    page.wait_for_timeout(200)


def connect_retry(page, ctx, wf_id, src, tgt, tries=3):
    """连一条线；连不上就重试（保存后查库验证）。

    判据用「保存后库里的连线」而不是画布 DOM —— DOM 里存在 ≠ 执行侧能看见。
    """
    for _ in range(tries):
        F.connect(page, src, tgt)
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2000)
        xml = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
        xml = xml if isinstance(xml, str) else F.json.dumps(xml)
        if any(v == (src, tgt) for v in F.graph_edges(xml).values()):
            return True
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)
    return False


def use_suggested_name(page, key):
    """选中网关 → 点右栏「用建议名」按钮 → 返回引擎建议的名字。

    **不硬写名字**：走页面上的建议按钮，断言的才是「引擎按入/出边数推出的角色」。
    如果名字和结构对不上（名实不符），这里会直接取到错的期望值而暴露出来。
    """
    clear_selection(page)
    page.locator(f'.djs-element[data-element-id="{key}"]').first.click()
    page.wait_for_timeout(1400)
    card = page.locator(".ant-card", has_text="网关设置").first
    if card.count() == 0:
        return None
    # 「在本图中的作用」标签上的文字就是引擎给的角色（并行分叉/并行汇合/异或分叉/异或汇合）
    role = ""
    tags = card.locator(".ant-tag")
    for i in range(tags.count()):
        t = (tags.nth(i).inner_text() or "").strip()
        if t in ("并行分叉", "并行汇合", "异或分叉", "异或汇合"):
            role = t
            break
    if not role:
        return None
    # 点「用建议名」
    link = card.locator("button.ant-btn-link", has_text=re.compile(r"用建议名")).first
    if link.count() == 0:
        return None
    link.click()
    page.wait_for_timeout(900)
    return role


def shot_pairs(page, stem):
    """截设计器画布（成对网关 + 名字），存 docs/media/。"""
    out = os.path.join(os.path.dirname(_HERE), "docs", "media", stem + ".png")
    try:
        page.locator(".djs-container").screenshot(path=out)
        print("已截图：docs/media/%s.png" % stem)
    except Exception as e:  # noqa: BLE001
        print("截图失败：", str(e)[:120])


def build_graph(page, ctx, name, nodes, edges):
    """拖一张图 → 批量连线 → 单次保存 → 查库补连（缺哪条补哪条）。

    为什么不再逐边保存：每次保存都会触发设计器 compile/preflight 异步刷新，
    画布 DOM 在刷新窗口里会短暂不稳定；逐边保存 11 次很容易踩到「元素查不到」。
    批量连完只保存一次，缺边再补，把保存次数从 O(E) 降到 1 + 缺边数。
    """
    wf_id = F.make_workflow(page, ctx, name)
    for seed in ("S", "E"):
        F.delete_node(page, seed)
    ids = {}
    for n, action, x, y in nodes:
        clear_selection(page)
        ids[n] = F.place(page, action, x, y)
    miss = [k for k, v in ids.items() if not v]
    if miss:
        F.bad("palette 落点失败", ",".join(miss))
        return wf_id, ids

    def db_edges():
        xml = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
        xml = xml if isinstance(xml, str) else F.json.dumps(xml)
        return set(F.graph_edges(xml).values())

    for a, b in edges:
        F.connect(page, ids[a], ids[b])
    if not F.page_save(page):
        F.bad("批量连线后保存没完成", "20s 内没等到 data-graph-saved")
        return wf_id, ids
    got = db_edges()
    missing = [(a, b) for a, b in edges if (ids[a], ids[b]) not in got]
    for a, b in missing:
        F.connect(page, ids[a], ids[b])
        if not F.page_save(page):
            break
        if (ids[a], ids[b]) in db_edges():
            missing = [p for p in missing if p != (a, b)]
            continue
        # 补连仍失败 → 刷新再试一次（图在库里是好的，刷新让设计器重新 importXML）
        page.reload(wait_until="networkidle")
        page.wait_for_selector(".djs-container", timeout=25000)
        page.wait_for_timeout(1800)
        F.connect(page, ids[a], ids[b])
        F.page_save(page)
        if (ids[a], ids[b]) in db_edges():
            missing = [p for p in missing if p != (a, b)]
    if missing:
        F.bad("有连线连不上（批量连 + 补连后仍缺）", ",".join(f"{a}→{b}" for a, b in missing))
    else:
        F.ok(f"{len(edges)} 条连线一次批量保存全部落库（缺边已补连）")
    return wf_id, ids


def check_pair(page, ctx, wf_id, ids, xs, xj, ps, pj, n):
    """核验一张图：四个网关的角色 + 成对 + N 值。"""
    F.step(f"H{2 if n == 2 else 3}. 网关成对 · N={n}")

    # 角色来自页面上的「用建议名」（引擎按入/出边数推出）
    roles = {}
    for key, expect in ((xs, "异或分叉"), (xj, "异或汇合"),
                        (ps, "并行分叉"), (pj, "并行汇合")):
        got = use_suggested_name(page, ids[key])
        roles[key] = got
        F.ok(f"{key} 的引擎角色是「{expect}」") if got == expect \
            else F.bad(f"{key} 角色不对", f"期望 {expect}，实际 {got}")

    # 保存后名字要真的进图
    if not F.page_save(page):
        F.bad("保存没完成", "20s 内没等到 data-graph-saved")
    xml = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else F.json.dumps(xml)
    for nm in ("异或分叉", "异或汇合", "并行分叉", "并行汇合"):
        F.ok(f"「{nm}」进了图 XML") if nm in xml else F.bad(f"「{nm}」没进图 XML")

    # 结构核对：入/出边数（与后端 WorkflowGraphParser 同一口径）
    edges = F.graph_edges(xml)
    from collections import defaultdict
    ins, outs = defaultdict(int), defaultdict(int)
    for a, b in edges.values():
        outs[a] += 1
        ins[b] += 1
    for key, label, want_in, want_out in ((xs, "异或分叉", 1, n),
                                          (xj, "异或汇合", n, 1),
                                          (ps, "并行分叉", 1, n),
                                          (pj, "并行汇合", n, 1)):
        F.ok(f"{key}（{label}）入 {ins[ids[key]]} 出 {outs[ids[key]]}") \
            if (ins[ids[key]] == want_in and outs[ids[key]] == want_out) \
            else F.bad(f"{key} 边数不对",
                       f"期望 入{want_in}/出{want_out}，实际 入{ins[ids[key]]}/出{outs[ids[key]]}")


def builtin_in_xml(ctx, wf_id, key):
    """库里这张图里，该节点是否已经是内置节点（protocolName="__setVar"）。"""
    xml = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else F.json.dumps(xml)
    i = xml.find(f'id="{key}"')
    if i < 0:
        return False
    return 'protocolName="__setVar"' in xml[max(0, i - 80):i + 500]


def run_and_check(ctx, page, wf_id, ids, expect_ran, xor_branches, label, all_branches):
    """真跑一次，同时核对「异或选一」与「并行全跑」。

    ⚠ **所有**分支节点（all_branches）都要设成内置节点，不能只设期望跑的那几个：
    异或分叉走的是「排序第一条」那条支路，若那条支路上的节点还是普通 task
    （设备任务），它会去找设备 → 找不到就转 manual 并停下，整单卡 paused
    （实测 N=2/N=3 都因此失败：节点是 manual/running 而不是 completed）。
    """
    for k in all_branches:
        # 设置后直接查库核对（服务端 patch 立即生效）；不生效就重试。
        # 节点设置卡是异步回填的，UI 显示“保存成功”不等于这个节点真被改了。
        for _try in range(3):
            F.set_node_builtin(page, "变量节点", ids[k], f"v_{k}", "1")
            if builtin_in_xml(ctx, wf_id, ids[k]):
                break
        F.ok(f"{k} 设成内置变量节点并落库（__setVar）") \
            if builtin_in_xml(ctx, wf_id, ids[k]) \
            else F.bad(f"{k} 内置节点设置没落库", ids[k])
    # ⚠ 这里不能再点画布「保存」：节点设置走服务端 patch（只改这个节点的
    # protocolName），但画布模型还是普通 task；再 saveXML 整图回写会把刚设置的
    # 内置节点全部覆盖回 task → 执行时找不到设备转 manual、整单卡 paused
    # （4.15 N=2/N=3 实测踩过）。节点设置本身已经落库，不需要再保存画布。

    rid, st = F.run_workflow(ctx, wf_id)
    if not rid:
        F.bad("触发执行失败（没有新 run 落库）")
        return
    F.ok(f"触发真执行（run #{rid} → {st or '未终态'}）")
    nodes = F.q(f"SELECT node_key||'='||status::text FROM run_node "
                 f"WHERE run_id={rid} AND level=0 ORDER BY id")
    F.ok("整单 completed") if st == "completed" else F.bad("整单没跑完", f"{st}；{nodes}")

    # ① 并行分叉：所有分支都跑（这才是并行）
    ran = [k for k in expect_ran if f"{ids[k]}=completed" in nodes]
    F.ok(f"{label}：并行的 {len(expect_ran)} 条分支全跑了（{','.join(expect_ran)}）") \
        if len(ran) == len(expect_ran) \
        else F.bad(f"{label}：并行分支没全跑（{len(ran)}/{len(expect_ran)}）", nodes)

    # ② 异或分叉：恰好走 1 条，其余 bypassed
    #    不能断言「具体哪一条」：choose() 在无条件、无 default 时按**目标节点 id
    #    字典序**取第一条（GraphRunner.choose），而节点 id 是 bpmn-js 随机生成的
    #    （Activity_0ajn4aq 这类）—— 走哪条**不确定**，只能断言「恰好一条」。
    completed = [k for k in xor_branches if f"{ids[k]}=completed" in nodes]
    bypassed = [k for k in xor_branches if f"{ids[k]}=bypassed" in nodes]
    F.ok(f"{label}：异或分叉恰好走了 1 条（走了 {','.join(completed) or '无'}）") \
        if len(completed) == 1 else F.bad(f"{label}：异或走了 {len(completed)} 条", nodes)
    F.ok(f"{label}：其余 {len(bypassed)} 条被 bypassed（异或是选一，不是全跑）") \
        if len(bypassed) == len(xor_branches) - 1 \
        else F.bad(f"{label}：bypassed 数不对（{len(bypassed)}/{len(xor_branches) - 1}）", nodes)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    if F.have_db() and os.environ.get("LABOS_DB_PASS"):
        os.environ.setdefault("PGPASSWORD", os.environ["LABOS_DB_PASS"])
        if not os.environ.get("PGPASSWORD"):
            os.environ["PGPASSWORD"] = os.environ["LABOS_DB_PASS"]

    token = None
    ctx = None
    try:
        token = F.api("/api/auth/login", "POST",
                      {"username": args.user, "password": args.pwd})["token"]
        ctx = F.Ctx(token)
    except Exception as e:  # noqa: BLE001
        print("登录失败：", str(e)[:200])
        sys.exit(2)

    try:
        with F.sync_playwright() as p:
            b = p.chromium.launch(headless=not args.headed)
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

            if not F.have_db():
                F.skip("没给库连接，跳过（要断言 run_node 必须连库）")
            else:
                # ---------------- 图 1：N=2 ----------------
                # S ─ XS ─┬─ A ─┐
                #          └─ B ─┴─ XJ ─ PS ─┬─ C ─┐
                #                             └─ D ─┴─ PJ ─ E
                nodes2 = [
                    ("S", "create.start-event", 120, 120),
                    ("XS", "create.exclusive-gateway", 260, 120),
                    ("A", "create.task", 400, 60),
                    ("B", "create.task", 400, 200),
                    ("XJ", "create.exclusive-gateway", 540, 130),
                    ("PS", "create.parallel-gateway", 680, 120),
                    ("C", "create.task", 800, 60),
                    ("D", "create.task", 800, 200),
                    ("PJ", "create.parallel-gateway", 900, 130),
                    ("E", "create.end-event", 900, 260),
                ]
                edges2 = [("S", "XS"), ("XS", "A"), ("XS", "B"),
                          ("A", "XJ"), ("B", "XJ"), ("XJ", "PS"),
                          ("PS", "C"), ("PS", "D"),
                          ("C", "PJ"), ("D", "PJ"), ("PJ", "E")]
                wf2, ids2 = build_graph(page, ctx, "EFM-网关成对-N2", nodes2, edges2)
                if len(ids2) == len(nodes2):
                    check_pair(page, ctx, wf2, ids2, "XS", "XJ", "PS", "PJ", 2)
                    shot_pairs(page, "designer-gateway-pairs-N2")
                    # 一次执行同时验两件事：
                    #   · 异或分叉：恰好走 1 条（A/B），其余 bypassed
                    #   · 并行分叉：两条（C/D）都跑，汇合点等齐才收尾
                    run_and_check(ctx, page, wf2, ids2,
                                  expect_ran=["C", "D"],
                                  xor_branches=["A", "B"],
                                  label="N=2", all_branches=["A", "B", "C", "D"])

                # ---------------- 图 2：N=3 ----------------
                nodes3 = [
                    ("S", "create.start-event", 110, 120),
                    ("XS", "create.exclusive-gateway", 230, 120),
                    ("A", "create.task", 350, 40),
                    ("B", "create.task", 350, 120),
                    ("C", "create.task", 350, 200),
                    ("XJ", "create.exclusive-gateway", 470, 120),
                    ("PS", "create.parallel-gateway", 590, 120),
                    ("P", "create.task", 700, 40),
                    ("Q", "create.task", 700, 120),
                    ("R", "create.task", 700, 200),
                    ("PJ", "create.parallel-gateway", 810, 120),
                    ("E", "create.end-event", 900, 120),
                ]
                edges3 = [("S", "XS"), ("XS", "A"), ("XS", "B"), ("XS", "C"),
                          ("A", "XJ"), ("B", "XJ"), ("C", "XJ"),
                          ("XJ", "PS"), ("PS", "P"), ("PS", "Q"), ("PS", "R"),
                          ("P", "PJ"), ("Q", "PJ"), ("R", "PJ"), ("PJ", "E")]
                wf3, ids3 = build_graph(page, ctx, "EFM-网关成对-N3", nodes3, edges3)
                if len(ids3) == len(nodes3):
                    check_pair(page, ctx, wf3, ids3, "XS", "XJ", "PS", "PJ", 3)
                    shot_pairs(page, "designer-gateway-pairs-N3")
                    run_and_check(ctx, page, wf3, ids3,
                                  expect_ran=["P", "Q", "R"],
                                  xor_branches=["A", "B", "C"],
                                  label="N=3", all_branches=["A", "B", "C", "P", "Q", "R"])

            b.close()
    except Exception as e:  # noqa: BLE001
        F.bad("异常", str(e)[:240])
    finally:
        if ctx:
            ctx.cleanup()

    print()
    print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
    sys.exit(1 if F.FAIL else 0)


if __name__ == "__main__":
    main()
