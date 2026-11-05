#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 完整流程矩阵（页面侧）。

为什么单独有它：
  scripts/verify-flow-matrix.py 从**接口/XML**这一侧把「节点 / 网关 / 变量 /
  循环 / 回边 / 并行 / 端口 / 暂停 / 实验 / 并发」都锁住了，但它绕过了界面 ——
  "后端能跑"不等于"页面上能用"：palette 建不出某类节点、保存没落库、
  运行详情画不出图、页面上的暂停按钮点不动，这些它都发现不了。
  这里补上**页面侧**那一段。

覆盖（页面侧，对应后端矩阵的分类）：
  A. 设计器：palette 装配 start / task / 异或网关 / end + 保存 + 预检 + 重开仍在
  B. 设计器：节点设置 —— 把 task 设成「变量节点（__setVar）」并落库
  C. 运行详情：流程图按状态着色 + 图例 + 节点表（有循环时出「圈次」）
  D. 运行控制：运行详情页点「暂停」→ 点「继续」，页面与库状态一致
  E. 设计器：左面板「逻辑节点」Tab 拖出 __setVar；连线条件表达式落库；
     流程级循环上限（maxIterations）落库

边界（C 类，只观察不判失败）：
  · 设计器 palette **没有并行网关入口**（只有 exclusiveGateway）——
    并行结构只能在 XML / 接口侧建立，页面上只负责「看」（见 C 节与设计文档）。

前置：
  · 前端可访问（默认 http://localhost:5173；nginx 8080 也行，用 --base 指定）；
  · API 18080 在跑、admin/admin123 可登录；
  · 连库环境变量 LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME / LABOS_DB_USER / LABOS_DB_PASS
    （缺失则 D 节 SKIP，其余节仍可跑）。

用法：
  python3 scripts/e2e-flow-matrix.py
  python3 scripts/e2e-flow-matrix.py --base http://127.0.0.1:8080 --headed
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

PASS, FAIL, SKIP = 0, 0, 0
BASE_API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (f"（{detail}）" if detail else ""))


def skip(msg):
    global SKIP
    SKIP += 1
    print("  \033[33m⏭\033[0m " + msg)


def step(title):
    print(f"\n\033[1m══ {title} ══\033[0m")


# ---------------------------------------------------------------- 基础设施

def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    out = subprocess.run(
        [os.environ.get("PSQL", "psql"), "-h", os.environ["LABOS_DB_HOST"], "-p",
         os.environ.get("LABOS_DB_PORT", "5432"), "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    return out.stdout.strip()


def have_db():
    return all(os.environ.get(k) for k in
               ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE_API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=60) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def login(page, base, user, pwd):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    ti = page.locator('input[type="text"]')
    if ti.count() > 0 and not ti.first.input_value():
        ti.first.fill(user)
    page.get_by_role("button", name=re.compile(r"登\s*录")).click()
    page.wait_for_selector("text=流程库", timeout=20000)


def place(page, action, x, y):
    """点 palette 项 → 在画布落点。bpmn-js：先选工具，再在画布上点一下。"""
    page.locator(f'.djs-palette .entry[data-action="{action}"]').first.click()
    page.wait_for_timeout(150)
    box = page.locator(".djs-container").bounding_box()
    # 设计器加了左侧「按岛设备」面板后画布会变窄；固定 x=700/900 会点到容器外，
    # 节点静默不生成（曾表现为「保存落库缺 exclusiveGateway」）。按容器宽度等比缩放。
    scale = min(1.0, (box["width"] - 40) / 900) if box["width"] < 940 else 1.0
    page.mouse.click(box["x"] + x * scale, box["y"] + y)
    page.wait_for_timeout(350)


def canvas_ids(page):
    """画布上所有图元的 data-element-id（含连线；连线也是 .djs-element）。"""
    return page.locator(".djs-element[data-element-id]").evaluate_all(
        "els => els.map(e => e.getAttribute('data-element-id'))")


class Ctx:
    def __init__(self, token):
        self.token = token
        self.made_wf = []
        self.made_run = []

    def cleanup(self):
        if not have_db():
            return
        for rid in self.made_run:
            q(f"DELETE FROM run_command WHERE run_id={rid};"
              f"DELETE FROM run_node WHERE run_id={rid};"
              f"UPDATE position_state SET reserved_by_run=NULL, block_reason=NULL, "
              f"reserved_until=NULL WHERE tenant_id=1 AND reserved_by_run={rid};"
              f"DELETE FROM run WHERE id={rid};")
        for wf in self.made_wf:
            q(f"DELETE FROM workflow_node WHERE workflow_id={wf};"
              f"DELETE FROM workflow WHERE id={wf};")


def make_workflow(page, ctx, name):
    """从界面新建一条流程并进设计器；返回 (wf_id, task_ids)。"""
    nav(page, "流程库")
    page.get_by_role("button", name=re.compile(r"新建流程")).first.click()
    page.wait_for_selector(".ant-modal-content", timeout=10000)
    page.locator(".ant-modal-content input").first.fill(name)
    page.locator(".ant-modal-footer button.ant-btn-primary").click()
    page.wait_for_url("**/designer/**", timeout=20000)
    page.wait_for_selector(".djs-container", timeout=25000)
    page.wait_for_timeout(1500)
    wf_id = int(re.search(r"/designer/(\d+)", page.url).group(1))
    ctx.made_wf.append(wf_id)
    return wf_id


# ---------------------------------------------------------------- A. palette 装配

def sec_designer_palette(page, ctx, base):
    step("A. 设计器 · palette 装配全类型节点 + 保存 + 预检 + 重开仍在")
    wf_id = make_workflow(page, ctx, "EFM-装配-临时")
    ok(f"界面新建流程并进入设计器（#{wf_id}）")

    before = page.locator(".djs-element[data-element-id]").count()
    place(page, "create.start-event", 110, 130)
    place(page, "create.task", 300, 130)
    place(page, "create.task", 500, 130)
    place(page, "create.exclusive-gateway", 700, 130)
    place(page, "create.end-event", 900, 130)
    after = page.locator(".djs-element[data-element-id]").count()
    if after > before:
        ok(f"palette 落点成功：画布图元 {before} → {after}")
    else:
        bad("palette 落点没生效", f"{before} → {after}")

    # palette 是否提供并行网关（真实边界，只观察）
    pgw = page.locator('.djs-palette .entry[data-action="create.parallel-gateway"]').count()
    if pgw == 0:
        print("  \033[33m·\033[0m 观察：设计器 palette 只有 exclusiveGateway，"
              "**没有并行网关入口** → 并行结构只能在 XML/接口侧建立（C 类边界）")

    # 保存
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_selector(".ant-message", timeout=10000)
    toast = page.inner_text(".ant-message")
    ok("保存成功（页面提示已保存）") if "保存" in toast else bad("保存无提示", toast)
    page.wait_for_timeout(1500)

    # 预检标签（通过 / 告警 / 阻塞 之一）
    body = page.inner_text("body")
    if any(k in body for k in ("阻塞", "告警", "预检")) or re.search(r"预检", body):
        ok("预检结论已渲染（通过/告警/阻塞）")
    else:
        bad("预检结论缺失")

    # 落库校验：页面上保存的图真的进了库
    xml = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else json.dumps(xml)
    kinds = {"startEvent": "bpmn:startEvent", "task": "bpmn:task",
             "exclusiveGateway": "bpmn:exclusiveGateway", "endEvent": "bpmn:endEvent"}
    missing = [k for k, tag in kinds.items() if tag not in xml]
    ok("保存落库：start/task/异或网关/end 四类节点都在图里") if not missing \
        else bad("保存落库缺节点", ",".join(missing))

    # 重开：刷新页面后节点仍在（页面「保存 = 可重开」）
    page.reload(wait_until="networkidle")
    page.wait_for_selector(".djs-container", timeout=25000)
    page.wait_for_timeout(2000)
    n2 = page.locator(".djs-element[data-element-id]").count()
    ok(f"刷新后画布仍有 {n2} 个图元（保存=可重开）") if n2 >= after \
        else bad("刷新后图元变少", f"{n2} < {after}")

    tasks = re.findall(r'<bpmn:task[^>]*id="([^"]+)"', xml)
    return wf_id, tasks


# ---------------------------------------------------------------- B. 变量节点设置

def sec_designer_varnode(page, ctx, wf_id, tasks):
    step("B. 设计器 · 节点设置：把 task 设为「变量节点（__setVar）」并落库")
    if not tasks:
        bad("上一步没在库里找到 task 节点，无法测节点设置")
        return
    key = tasks[0]
    el = page.locator(f'.djs-element[data-element-id="{key}"]')
    if el.count() == 0:
        bad("画布上找不到该 task 图元", key)
        return
    el.first.click()
    page.wait_for_timeout(1200)
    body = page.inner_text("body")
    if "节点设置" not in body:
        bad("选中 task 后没有「节点设置」面板", body[:120])
        return
    ok(f"选中 task 后出现「节点设置」（{key}）")

    card = page.locator(".ant-card", has_text="节点设置").first
    # 切到「变量节点（__setVar）」
    card.locator(".ant-select-selector").first.click()
    page.wait_for_timeout(400)
    page.locator('.ant-select-item-option', has_text="变量节点").first.click()
    page.wait_for_timeout(600)
    ok("已切到「变量节点（__setVar）」") if card.locator("input.ant-input").count() >= 2 \
        else bad("没切到变量节点（输入框没出来）")

    # 填 var / value（变量节点下的两个输入框）
    inputs = card.locator("input.ant-input")
    if inputs.count() >= 2:
        inputs.nth(0).fill("go")
        inputs.nth(1).fill("yes")
        ok("已填变量名 go / 值 yes")
    else:
        bad("变量节点的输入框没渲染出来", f"inputs={inputs.count()}")

    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_selector(".ant-message", timeout=10000)
    page.wait_for_timeout(1200)

    # 落库校验：接口读回来应是 __setVar + var=go
    j = api(f"/api/workflows/{wf_id}/nodes/{key}", token=ctx.token)
    if isinstance(j, dict) and j.get("method") == "__setVar" and (j.get("params") or {}).get("var") == "go":
        ok("变量节点已落库（method=__setVar，var=go）")
    else:
        bad("变量节点没落库", json.dumps(j, ensure_ascii=False)[:160])


# ---------------------------------------------------------------- C. 运行详情观测

def sec_run_detail(page, ctx, base):
    step("C. 运行详情 · 流程图着色 + 图例 + 节点表（有循环时出「圈次」）")
    if not have_db():
        skip("没给库连接，跳过运行详情观测（需库找一条历史执行）")
        return
    # 借一条**真的画得出图**的执行：有 run_node，且 /graph 返回非空 xml。
    # 优先挑**有循环圈次**的（才能顺带验「圈次」列）。
    # 注意：带圈次的执行很稀少（全库可能就几条），会被正常执行挤出"最近 60 条"
    # 窗口 —— 只按窗口取会让「圈次」断言时有时无（曾出现 20 < 阈值 21）。所以把
    # **全库带圈次的执行**也并进候选，再按"循环优先 + id 新优先"排序。
    cand = [int(x) for x in q(
        "SELECT r.id FROM run r JOIN run_node n ON n.run_id=r.id "
        "WHERE r.tenant_id=1 GROUP BY r.id ORDER BY r.id DESC LIMIT 60").split() if x]
    loop_ids = {int(x) for x in q(
        "SELECT DISTINCT run_id FROM run_node WHERE level=0 AND loop_index>0").split() if x}
    cand = list(dict.fromkeys(cand + sorted(loop_ids, reverse=True)))
    cand.sort(key=lambda c: (0 if c in loop_ids else 1, -c))
    rid = 0
    for c in cand:
        try:
            g = api(f"/api/runs/{c}/graph", token=ctx.token)
        except Exception:  # noqa: BLE001
            continue
        if isinstance(g, dict) and g.get("xml"):
            rid = c
            break
    if not rid:
        skip("最近 60 条执行里没有画得出流程图的，跳过")
        return
    page.goto(f"{base}/runs/{rid}", wait_until="networkidle")
    page.wait_for_timeout(2500)
    body = page.inner_text("body")
    ok(f"运行详情打开（#{rid}）") if f"#{rid}" in body else bad("没进运行详情", page.url)
    ok("有流程图卡片（按执行状态着色）") if "流程图（按执行状态着色）" in body \
        else bad("缺流程图卡片")
    ok("图例已渲染") if "图例" in body else bad("缺图例")
    ok("节点表已渲染") if "节点（" in body else bad("缺节点表")

    if rid in loop_ids:
        ok("循环执行在页面上有「圈次」列/标签") if ("圈次" in body or "第 " in body) \
            else bad("有循环节点但页面没显示圈次")
    else:
        print("  \033[33m·\033[0m 这条执行没有循环圈次，圈次列未触发"
              "（loop 断言由 verify-flow-matrix.py 覆盖）")


# ---------------------------------------------------------------- D. 页面暂停 / 恢复

def sec_run_control(page, ctx, base, wf_id):
    step("D. 运行控制 · 运行详情页「暂停」→「继续」")
    if not have_db():
        skip("没给库连接，跳过页面暂停/恢复（需造一条 running 的执行）")
        return
    if not wf_id:
        skip("没有可借用的流程，跳过")
        return
    mode = q("SELECT COALESCE(mode::text,'normal') FROM run ORDER BY id DESC LIMIT 1") or "normal"
    rid = q(f"""INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                 mode, status, actual_start, progress)
                SELECT 1, 'EFM-{int(time.time()*1000)}', 'workflow', {wf_id}, 1,
                       '{mode}', 'running', now(), 0
                FROM run ORDER BY id DESC LIMIT 1 RETURNING id""")
    rid = int(rid.strip().split()[0]) if rid else 0
    if not rid:
        bad("造 running 执行失败（库写入被拒）")
        return
    ctx.made_run.append(rid)

    page.goto(f"{base}/runs/{rid}", wait_until="networkidle")
    page.wait_for_timeout(2000)
    pause_btn = page.locator("button.ant-btn", has_text=re.compile(r"暂\s*停"))
    if pause_btn.count() == 0:
        bad("running 执行上没有「暂停」按钮", page.inner_text("body")[:120])
        return
    ok("running 执行上出现「暂停」按钮")
    pause_btn.first.click()
    page.wait_for_selector(".ant-message", timeout=10000)
    page.wait_for_timeout(2000)
    page.wait_for_timeout(1500)
    st = q(f"SELECT status::text FROM run WHERE id={rid}")
    body = page.inner_text("body")
    ok("点暂停后库状态 = paused") if st == "paused" else bad("库状态没变 paused", st)
    ok("点暂停后页面显示已暂停") if ("paused" in body or "已暂停" in body) \
        else bad("页面没显示已暂停")

    cont_btn = page.locator("button.ant-btn", has_text=re.compile(r"继\s*续"))
    if cont_btn.count() == 0:
        bad("暂停后没有「继续」按钮")
        return
    ok("暂停后出现「继续」按钮")
    cont_btn.first.click()
    page.wait_for_selector(".ant-message", timeout=10000)
    page.wait_for_timeout(3000)
    st2 = q(f"SELECT status::text FROM run WHERE id={rid}")
    ok(f"点继续后离开 paused（当前 {st2}）") if st2 != "paused" \
        else bad("点继续后仍停在 paused")


# ---------------------------------------------------------------- E. 逻辑节点 / 连线条件 / 流程选项

def sec_logic_and_flow(page, ctx, base, wf_id):
    """批 3+4：左面板「逻辑节点」Tab、连线条件表达式、流程级循环上限。

    这一节盯的是「设计器有没有入口」——后端能跑不等于页面上拖得出来：
      E1 左面板 Tabs 常驻可见，逻辑节点能拖出并落库（无需再点保存）；
      E2 选中 SequenceFlow → 写条件 → 落进 bpmn:conditionExpression；
      E3 流程选项卡改 maxIterations → 落进 workflow.attr->>'maxIterations'。
    """
    step("E. 设计器 · 逻辑节点 Tab / 连线条件 / 流程选项（批 3+4）")
    if not wf_id:
        skip("没有可借用的流程，跳过")
        return

    page.goto(f"{base}/designer/{wf_id}", wait_until="networkidle")
    page.wait_for_selector(".djs-container", timeout=25000)
    page.wait_for_timeout(1500)

    # E1 左面板「逻辑节点」Tab
    tab = page.locator(".ant-tabs-tab", has_text="逻辑节点")
    if tab.count() == 0:
        bad("左面板没有「逻辑节点」Tab（入口被设备列表挤掉了？）")
        return
    ok("左面板「逻辑节点」Tab 常驻可见（与设备列表平级，不被设备数量顶掉）")
    tbox = tab.first.bounding_box()
    if tbox and tbox["y"] < 900:
        ok(f"Tab 入口在首屏可视区内（y={int(tbox['y'])}）")
    else:
        bad("Tab 入口不在首屏可视区", str(tbox))

    tab.first.click()
    page.wait_for_timeout(400)
    rows = page.locator('[data-testid="palette-logic"]')
    if rows.count() < 2:
        bad("「逻辑节点」Tab 里没有 __setVar / __expr 两项", str(rows.count()))
        return
    ok(f"「逻辑节点」Tab 提供 {rows.count()} 个可拖逻辑节点")

    before = set(canvas_ids(page))
    rows.first.drag_to(page.locator(".djs-container"), target_position={"x": 300, "y": 420})
    page.wait_for_timeout(3500)
    new = [i for i in sorted(set(canvas_ids(page)) - before) if not i.endswith("_label")]
    if not new:
        bad("拖出逻辑节点后画布没有新节点")
        return
    key = new[0]
    ok(f"拖出逻辑节点 {key}（无需手动点保存）")
    j = api(f"/api/workflows/{wf_id}/nodes/{key}", token=ctx.token)
    if isinstance(j, dict) and j.get("method") == "__setVar":
        ok("逻辑节点已落库（method=__setVar）")
    else:
        bad("逻辑节点没落库", json.dumps(j, ensure_ascii=False)[:160])

    # 再拖一个 → 自动连线，产生一条可写条件的 sequenceFlow
    rows.nth(1).drag_to(page.locator(".djs-container"), target_position={"x": 700, "y": 420})
    page.wait_for_timeout(3500)
    xml = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else json.dumps(xml)
    flows = re.findall(r'<bpmn:sequenceFlow[^>]*id="([^"]+)"', xml)
    if not flows:
        bad("连续拖入逻辑节点后没有自动连线", xml[-200:])
        return
    fid = flows[-1]
    ok(f"连续拖入自动生成连线 {fid}")

    # E2 选中连线 → 写条件 → 落库
    conn = page.locator(f'g.djs-connection[data-element-id="{fid}"]').first
    if conn.count() == 0:
        bad("画布上找不到该连线图元", fid)
        return
    cbox = conn.bounding_box()
    page.mouse.click(cbox["x"] + cbox["width"] / 2, cbox["y"] + cbox["height"] / 2)
    page.wait_for_timeout(1200)
    if "连线 · 条件" not in page.inner_text("body"):
        bad("选中连线后没有「连线 · 条件」面板", page.inner_text("body")[:160])
        return
    ok("选中连线后出现「连线 · 条件」面板")
    fcard = page.locator(".ant-card", has_text="连线 · 条件").first
    fcard.locator("textarea").first.fill("${counter} > 3")
    fcard.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(3000)
    xml2 = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml2 = xml2 if isinstance(xml2, str) else json.dumps(xml2)
    # XML 里 '>' 会被转义成 &gt;，两种形式都认
    if "conditionExpression" in xml2 and ("&gt; 3" in xml2 or "> 3" in xml2):
        ok("连线条件已写进图（bpmn:conditionExpression 落库）")
    else:
        bad("连线条件没落库", xml2[-260:])

    # E3 流程选项 · 循环上限
    card = page.locator(".ant-card", has_text="流程选项").first
    if card.count() == 0:
        bad("右侧没有「流程选项」卡片")
        return
    inp = card.locator("input").first
    inp.click()
    inp.fill("5")
    inp.press("Enter")
    page.wait_for_timeout(300)
    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)
    o = api(f"/api/workflows/{wf_id}/options", token=ctx.token)
    if isinstance(o, dict) and o.get("maxIterations") == 5:
        ok("流程级循环上限已落库（maxIterations=5）")
    else:
        bad("循环上限没落库", json.dumps(o, ensure_ascii=False)[:160])


# ---------------------------------------------------------------- 路由

def nav(page, menu):
    page.locator(f'.ant-menu-item:has-text("{menu}")').first.click()
    page.wait_for_timeout(1200)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    if have_db() and os.environ.get("LABOS_DB_PASS"):
        os.environ.setdefault("PGPASSWORD", os.environ["LABOS_DB_PASS"])

    try:
        token = api("/api/auth/login", "POST",
                    {"username": args.user, "password": args.pwd})["token"]
    except Exception as e:  # noqa: BLE001
        print(f"SKIP: 连不上 API {BASE_API}（{e}）")
        return 0

    ctx = Ctx(token)
    wf_id, tasks = 0, []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1500, "height": 950})
        page.set_default_timeout(20000)
        try:
            login(page, args.base, args.user, args.pwd)
            ok("登录成功")
            wf_id, tasks = sec_designer_palette(page, ctx, args.base)
            sec_designer_varnode(page, ctx, wf_id, tasks)
            sec_logic_and_flow(page, ctx, args.base, wf_id)
            sec_run_detail(page, ctx, args.base)
            sec_run_control(page, ctx, args.base, wf_id)
            page.screenshot(path="/tmp/e2e-flow-matrix.png")
        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:160])
            try:
                page.screenshot(path="/tmp/e2e-flow-matrix-timeout.png")
                print("     截图：/tmp/e2e-flow-matrix-timeout.png")
            except Exception:
                pass
        except Exception as e:  # noqa: BLE001
            bad("异常", str(e)[:220])
        finally:
            browser.close()
            print("\n--- 清理 ---")
            ctx.cleanup()

    print()
    print("-" * 42)
    extra = f"（跳过 {SKIP} 节）" if SKIP else ""
    print(f"通过 {PASS} / 失败 {FAIL}{extra}")
    print("-" * 42)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
