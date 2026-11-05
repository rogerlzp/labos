#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 多并行 + 多网关 + 变量节点（页面实操专项）。

为什么单独有它：
  `e2e-flow-matrix.py` 的 F 节只画了一张**单异或**分支图（S→SET→X→A/B→E），
  验的是「一个网关 + 一条条件 + 变量节点」这条最简接缝。
  但「**多个并行网关 + 多个异或网关 + 变量**」组合在一起时，页面拖出来的图
  能不能被解析器认全、并行分支会不会真并行、汇合点会不会等齐，
  **从来没有人拖过**——后端矩阵的 parallel 用 build_xml() 直 POST，
  绕过了界面；页面上画的图从没同时出现过两个网关。

这一节要做的（全部在**页面上真拖**，不直写 XML）：
  1. 拖出 8 个节点：S / SET(变量) / P1(并行) / A / B / X(异或) / C / D / E
  2. 连成「并行分两支 → 每支再各走一个异或 → 汇合到 E」
  3. 保存 → 断言图 XML 里**两个** parallelGateway + **一个** exclusiveGateway
     + 变量节点参数都在
  4. 真跑 → 断言：
     · 整单 completed
     · 并行两支都执行（不互斥）
     · 每支的异或按页面写的条件命中/落空
     · 汇合点 E 真等到两支都完成才执行

前置：
  · 前端可访问（默认 http://localhost:5173）；API 18080 在跑；admin/admin123 可登录；
  · 连库环境变量 LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME / LABOS_DB_USER / LABOS_DB_PASS
    （缺失则整脚本 SKIP——要断言 run_node 必须连库）。

用法：
  python3 scripts/e2e-multi-parallel.py
  python3 scripts/e2e-multi-parallel.py --base http://127.0.0.1:8080 --headed
"""
import argparse
import importlib.util
import os
import re
import sys

# 复用 e2e-flow-matrix.py 里已经踩过坑的函数（place/connect/set_node_builtin/
# set_edge_condition/make_workflow/run_workflow/graph_edges/nav…），
# 不重写——那些函数的每个坐标/时序参数都是踩坑调出来的。
# 文件名带连字符，不能直接 import，按路径加载。
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_flow_matrix", os.path.join(_HERE, "e2e-flow-matrix.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["e2e_flow_matrix"] = F
_spec.loader.exec_module(F)


def connect_retry(page, ctx, wf_id, src, tgt, tries=3):
    """连一条线；连不上就重试（保存后查库验证）。

    为什么需要重试：bpmn-js 的 global-connect-tool 是「点源 → 点目标」两步，
    点源落在图元自身框内时会命中自己的连接锚点，导致这条线连不上
    （实测 D→E 因源点被自身框遮挡而失败，其余 12 条都正常）。
    判据用「保存后库里的连线」而不是画布 DOM —— DOM 里连线已存在但没保存，
    查库才是执行侧真正能看到的（也是最终断言的依据）。
    """
    for i in range(tries):
        F.connect(page, src, tgt)
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2000)
        xml = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
        xml = xml if isinstance(xml, str) else F.json.dumps(xml)
        edges = F.graph_edges(xml)
        if any(v == (src, tgt) for v in edges.values()):
            return True
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)
    return False


def run(ctx, page):
    """G. 多并行 + 多网关 + 变量：页面拖 → 保存 → 真跑 → 断言。"""
    F.step("G. 页面实操 · 多并行网关 + 异或网关 + 变量节点")
    if not F.have_db():
        F.skip("没给库连接，跳过（要断言 run_node 必须连库）")
        return

    wf_id = F.make_workflow(page, ctx, "EFM-多并行多网关-临时")

    # 新建流程自带种子图 start(S) → end(E)，先清空再按自己的布局从头画。
    for seed in ("S", "E"):
        F.delete_node(page, seed)

    # 布局（横平竖直，便于点中连线中点写条件）：
    #
    #   S(140,120) ─ SET(250,120) ─ P1(360,120) ─┬─ A(470,120) ─ X(580,120) ─ C(690,120) ─┐
    #                                          │                                          │
    #                                          └─ P2(470,270) ─┬─ B(580,270) ─ X2(690,270) ─ D(690,360) ─┤
    #                                                        └─ F(580,400) ────────────────────┤
    #                                                                                        │
    #                                                                                     E(690,240)
    #
    # 这是「**多个并行**」+「**多个网关**」+「变量」三者共存的形状：
    #   · P1 是并行网关（一分为二）
    #   · P2 是**第二个**并行网关（嵌套在 P1 的一支里）
    #   · X / X2 是异或网关（条件分支）
    #   · SET / A / B / F 是变量节点，C / D 是表达式节点
    #
    # ⚠ 坐标必须落在画布容器的**可视区**里：容器左侧被「按岛设备」面板挤掉，
    #   实测容器 x≈468、宽≈690（视口坐标），且 x≥980 或 x<110 会静默落不上
    #   （实测 x=900 成功、x=980 与 x=90 返回 None）。
    ids = {}
    for name, action, x, y in (("S", "create.start-event", 140, 120),
                               ("SET", "create.task", 250, 120),
                               ("P1", "create.parallel-gateway", 360, 120),
                               ("A", "create.task", 470, 120),
                               ("X", "create.exclusive-gateway", 580, 120),
                               ("C", "create.task", 690, 120),
                               ("P2", "create.parallel-gateway", 470, 270),
                               ("B", "create.task", 580, 270),
                               ("X2", "create.exclusive-gateway", 690, 270),
                               ("D", "create.task", 700, 340),
                               ("F", "create.task", 470, 420),
                               ("E", "create.end-event", 600, 500)):
        ids[name] = F.place(page, action, x, y)
    miss = [k for k, v in ids.items() if not v]
    if miss:
        F.bad("palette 落点失败（没拿到新图元 id）", ",".join(miss))
        return
    F.ok("按多并行布局落 12 个节点（S/SET/P1/A/X/C/P2/B/X2/D/F/E，"
         "含 2 个并行网关 + 2 个异或网关）")

    # 连线（每条都带重试：点源被自身框遮挡时会连不上）
    want = (("S", "SET"), ("SET", "P1"),
            ("P1", "A"), ("P1", "P2"),
            ("A", "X"), ("X", "C"),
            ("P2", "B"), ("P2", "F"),
            ("B", "X2"), ("X2", "D"),
            ("C", "E"), ("D", "E"), ("F", "E"))
    bad_edges = []
    for a, b in want:
        if not connect_retry(page, ctx, wf_id, ids[a], ids[b]):
            bad_edges.append(f"{a}→{b}")
    if bad_edges:
        F.bad("有连线连不上（重试 3 次仍失败）", ",".join(bad_edges))
        return
    F.ok(f"13 条连线全部连上并落库（{len(want)} 条）")

    xml = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else F.json.dumps(xml)
    edges = F.graph_edges(xml)
    F.ok(f"13 条连线都落了库（{len(edges)} 条）") if len(edges) == 13 \
        else F.bad("连线数不对", f"{len(edges)}：{list(edges.values())}")

    # 断言图里**真的**有两个并行网关和两个异或网关（不是画布上看着像）。
    n_par = xml.count("parallelGateway")
    n_xor = xml.count("exclusiveGateway")
    F.ok(f"图里有 2 个并行网关（parallelGateway ×{n_par}）") if n_par >= 2 \
        else F.bad("并行网关数量不对", f"parallelGateway ×{n_par}")
    F.ok(f"图里有 2 个异或网关（exclusiveGateway ×{n_xor}）") if n_xor >= 2 \
        else F.bad("异或网关数量不对", f"exclusiveGateway ×{n_xor}")

    # 变量节点：SET 设 counter=1；并行各支设标记变量。
    #
    # ⚠ 为什么 A/B/C/D/F 全设成**内置节点**而不是普通 task：
    #   普通 task 是「设备任务」，执行时要找设备实例；库里没有对应设备时
    #   会走 GraphRunner 的失败分支 → 节点转 manual 并停下（实测 run 卡在
    #   manual/running，整单 paused）。要验的是「并行 + 网关 + 变量」的
    #   **编排语义**，不是设备寻址，所以用不碰设备的内置节点。
    if not F.set_node_builtin(page, "变量节点", ids["SET"], "counter", "1"):
        F.bad("SET 没切成「变量节点」（节点设置面板不配合）")
        return
    F.ok("SET 设为变量节点 counter=1（走节点设置面板）")
    F.set_node_builtin(page, "变量节点", ids["A"], "branchA", "yes")
    F.set_node_builtin(page, "变量节点", ids["B"], "branchB", "yes")
    F.set_node_builtin(page, "变量节点", ids["F"], "branchF", "yes")
    F.ok("三条并行分支各设为变量节点（branchA / branchB / branchF = yes）")
    # 条件分支用表达式节点：读回 counter 并落成结果变量，
    # 这样「异或条件用的是上游变量」这件事也被钉住。
    F.set_node_builtin(page, "表达式节点", ids["C"], "hitC", "${counter}")
    F.set_node_builtin(page, "表达式节点", ids["D"], "hitD", "${counter}")
    F.ok("两条异或分支设为表达式节点（hitC / hitD 读 ${counter}）")

    # 两条异或出边写条件：counter==1 命中 C，counter==2 命中 D。
    fa = next((f for f, e in edges.items()
               if e == (ids["X"], ids["C"])), None)
    fb = next((f for f, e in edges.items()
               if e == (ids["X2"], ids["D"])), None)
    if not fa or not F.set_edge_condition(page, fa, "${counter} == 1"):
        F.bad("X→C 的连线条件没写上", str(fa))
        return
    if not fb or not F.set_edge_condition(page, fb, "${counter} == 1"):
        F.bad("X2→D 的连线条件没写上", str(fb))
        return
    F.ok("两条异或出边的条件都在页面上写好（${counter} == 1）")

    # ★ 关键顺序（与 F 节同坑）：节点设置/连线条件只 patch 了图 XML，
    #   执行侧读的是 XML 里的 methodParams / conditionExpression，
    #   必须再点一次页面级「保存」把设置固化回 workflow_node。
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)
    xml2 = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml2 = xml2 if isinstance(xml2, str) else F.json.dumps(xml2)
    n_cond = xml2.count("conditionExpression")
    F.ok(f"两条连线条件进了图（conditionExpression ×{n_cond}）") if n_cond >= 2 \
        else F.bad("条件没进图 XML", xml2[-260:])
    F.ok("变量节点参数进了图（methodParams 带 counter）") if "counter" in xml2 \
        else F.bad("__setVar 参数没进图 XML", xml2[-260:])

    rid, st = F.run_workflow(ctx, wf_id)
    if not rid:
        F.bad("触发执行失败（没有新 run 落库）")
        return
    F.ok(f"触发真执行（run #{rid} → {st or '未终态'}）")
    nodes = F.q(f"SELECT node_key||'='||status::text FROM run_node "
                 f"WHERE run_id={rid} AND level=0 ORDER BY id")
    F.ok("整单 completed") if st == "completed" else F.bad("整单没跑完", f"{st}；{nodes}")

    # 变量节点 SET 真被执行（页面上设的 __setVar 被执行侧认到）
    F.ok("SET 执行过（页面上设的 __setVar 被执行侧认到）") \
        if f"{ids['SET']}=completed" in nodes else F.bad("SET 没执行", nodes)

    # 并行分支都执行 —— 这是并行网关的语义核心
    F.ok("并行分支 A 执行了（P1 的第一支）") if f"{ids['A']}=completed" in nodes \
        else F.bad("并行分支 A 没执行", nodes)
    F.ok("并行分支 B 执行了（P2 的第一支）") if f"{ids['B']}=completed" in nodes \
        else F.bad("并行分支 B 没执行", nodes)
    F.ok("并行分支 F 执行了（P2 的第二支）") if f"{ids['F']}=completed" in nodes \
        else F.bad("并行分支 F 没执行", nodes)

    # 两条异或分支各命中 C / D
    F.ok("异或分支 1 命中 C（${counter} == 1 生效）") \
        if f"{ids['C']}=completed" in nodes else F.bad("没走 C", nodes)
    F.ok("异或分支 2 命中 D（${counter} == 1 生效）") \
        if f"{ids['D']}=completed" in nodes else F.bad("没走 D", nodes)

    # 汇合语义：E 是 end 事件（Kind.END），**本来就不进 run_node**
    # （只有 Kind.TASK / Kind.BUILTIN 才上报节点状态，见 GraphRunner
    #   的 progress.complete 只在 task/builtin 分支调用）。
    # 所以汇合不能断言「E 在 run_node 里 completed」——那永远不成立。
    # 可观测的判据是：三条并行分支都 completed **且** 整单 completed
    # —— 若汇合没等齐（只跑完一支就收尾），其余支不会 completed。
    F.ok("三条并行分支都 completed（汇合等齐了：只完成一支不会是这个结果）") \
        if all(f"{ids[k]}=completed" in nodes for k in ("A", "B", "F")) \
        else F.bad("并行分支没都完成", nodes)
    F.ok("整单 completed 说明所有分支都汇合到 end 后才收尾") if st == "completed" \
        else F.bad("整单没跑完（汇合未完成）", f"{st}；{nodes}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    if F.have_db() and os.environ.get("LABOS_DB_PASS"):
        os.environ.setdefault("PGPASSWORD", os.environ["LABOS_DB_PASS"])
        # psql 认 PGPASSWORD；不设它会交互式要口令 → 查询静默失败 →
        # 断言全红（2026-11-05 实踩，见 F.q 的注释）。
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

            run(ctx, page)
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
