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
  A. 设计器：palette 装配 start / task / 异或网关 / 并行网关 / end + 保存 + 预检 + 重开仍在
  B. 设计器：节点设置 —— 把 task 设成「变量节点（__setVar）」并落库
  C. 运行详情：流程图按状态着色 + 图例 + 节点表（有循环时出「圈次」）
  D. 运行控制：运行详情页点「暂停」→ 点「继续」，页面与库状态一致
  E. 设计器：左面板「逻辑节点」Tab 拖出 __setVar；连线条件表达式落库；
     流程级循环上限（maxIterations）落库
  F. 接缝：设计器里画出来的异或分支图（连线条件 + 变量节点参数）→ 真跑 →
     断言分支按页面写的条件走（A 命中、B bypassed）

边界：设计器 palette 已按 `webapp/src/bpmn/WorkflowPalette.ts` 收敛 —— 只留解析器认得的
  入口（start / end / task / 子流程 / 异或网关 / 并行网关 + 工具），中间/边界事件、
  数据对象、数据存储、泳道、分组已移除，避免拖到链路上被 `GraphRunner` 静默跳过。

前置：
  · 前端可访问（默认 http://localhost:5173；nginx 8080 也行，用 --base 指定）；
  · API 18080 在跑、admin/admin123 可登录；
  · 连库环境变量 LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME / LABOS_DB_USER / LABOS_DB_PASS
    （缺失则 D 节 SKIP，其余节仍可跑）。

用法：
  python3 scripts/e2e-flow-matrix.py
  python3 scripts/e2e-flow-matrix.py --base http://127.0.0.1:8080 --headed

  > **⚠ 已随旧基线退役（2026-11-11）**：本脚本针对的是**旧基线**
  > （三十座岛 + 一千多条流程的累积数据）。新基线重建后**不再纳入门禁**
  > （现行分段见 [测试总纲](docs/测试总纲.md) §2.2）。
  >
  > **为什么保留而不是删掉**：里面沉淀的是**页面交互的断言写法**（Playwright 操作、
  > DOM 选择器、断言模式）—— 重写页面轨时这些是最值钱的资产，删了等于重写。
  >
  > **重写时按新基线改**：岛用 `LAB-BASE-01`（或从接口动态取，别写死）；
  > 设备用基线 5 台（设备ID 200 / 229 / 231 / 413 / 414，
  > 能力 读板机 / 孵育箱 / 机器人 / 封膜机 / 离心机）。
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
    """跑一条 SQL，返回 stdout（去空白）。

    ⚠ 连不上库时**必须报错**，不能静默返回空串：
    空串会被后面的断言当成「状态为空 / 没查到」，表现为「整单没跑完」这种
    看起来像产品缺陷的失败，实际上是查询本身没跑成（2026-11-05 实踩：
    缺 PGPASSWORD 时 psql 报 "no password supplied"，q() 返回空，
    整段断言全红，差点被误判成执行侧坏了）。
    """
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    out = subprocess.run(
        [os.environ.get("PSQL", "psql"), "-h", os.environ["LABOS_DB_HOST"], "-p",
         os.environ.get("LABOS_DB_PORT", "5432"), "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        # 连接失败 / SQL 报错：把原因带出来，别让调用方拿到一个空串
        raise RuntimeError("SQL 执行失败：%s | stderr: %s"
                           % (sql[:120], (out.stderr or "").strip()[:300]))
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
    """点 palette 项 → 在画布落点；返回新图元 id（排除 *_label 附属图元）。

    bpmn-js：先选工具，再在画布上点一下。
    """
    before = set(canvas_ids(page))
    page.locator(f'.djs-palette .entry[data-action="{action}"]').first.click()
    page.wait_for_timeout(150)
    box = page.locator(".djs-container").bounding_box()
    # 设计器加了左侧「按岛设备」面板后画布会变窄；固定 x=700/900 会点到容器外，
    # 节点静默不生成（曾表现为「保存落库缺 exclusiveGateway」）。按容器宽度等比缩放。
    scale = min(1.0, (box["width"] - 40) / 900) if box["width"] < 940 else 1.0
    page.mouse.click(box["x"] + x * scale, box["y"] + y)
    page.wait_for_timeout(400)
    new = [i for i in sorted(set(canvas_ids(page)) - before)
           if not i.endswith("_label") and not i.startswith("Flow_")]
    return new[0] if len(new) == 1 else None


def drag_logic_node(page, method, x, y):
    """从左侧「逻辑节点」Tab 直接拖一个内置节点到画布；返回新图元 id。"""
    tab = page.locator(".ant-tabs-tab", has_text="逻辑节点").first
    if tab.count() == 0:
        return None
    tab.click()
    page.wait_for_timeout(300)
    row = page.locator(f'[data-testid="palette-logic"][data-method="{method}"]').first
    if row.count() == 0:
        return None
    before = set(canvas_ids(page))
    box = page.locator(".djs-container").bounding_box()
    scale = min(1.0, (box["width"] - 40) / 900) if box and box["width"] < 940 else 1.0
    row.drag_to(page.locator(".djs-container"),
                target_position={"x": x * scale, "y": y})
    page.wait_for_timeout(2500)
    new = [i for i in sorted(set(canvas_ids(page)) - before) if not i.endswith("_label")]
    return new[0] if len(new) == 1 else None


def canvas_ids(page):
    """画布上所有图元的 data-element-id（含连线；连线也是 .djs-element）。"""
    return page.locator(".djs-element[data-element-id]").evaluate_all(
        "els => els.map(e => e.getAttribute('data-element-id'))")


def center(page, eid):
    bb = page.locator(f'.djs-element[data-element-id="{eid}"]').first.bounding_box()
    return bb["x"] + bb["width"] / 2, bb["y"] + bb["height"] / 2


def connect(page, src, tgt):
    """global-connect-tool：点源图元 → 点目标图元。

    用裸鼠标点（bounding_box 求中心）而不是 locator.click()：bpmn-js 的图元
    带 transform，Playwright 的可点性检查会一直等（元素"不可点"）。
    """
    page.locator('.djs-palette .entry[data-action="global-connect-tool"]').first.click()
    page.wait_for_timeout(180)
    x, y = center(page, src)
    page.mouse.click(x, y)
    page.wait_for_timeout(280)
    x, y = center(page, tgt)
    page.mouse.click(x, y)
    page.wait_for_timeout(400)


class Ctx:
    def __init__(self, token):
        self.token = token
        self.made_wf = []
        self.made_run = []

    def wait_devices_idle(self, limit=420):
        """等设备回到 idle 再删数据。

        ⚠ 为什么必须等：设备方法耗时随机 30–300 秒（真机时序），
        设备还在执行时删掉 run_node ⇒ 设备回包后落库步骤行时父节点已不存在 ⇒
        `run_node_parent_node_id_fkey` 违反 ⇒ 门禁的「步骤落库失败」断言变红
        （2026-11-06 实测：设备链路段被别的段落的清理搞红）。
        """
        deadline = time.time() + limit
        while time.time() < deadline:
            busy = q("SELECT count(*) FROM device_state "
                     "WHERE tenant_id=1 AND status='running'")
            if not busy or busy == "0":
                return True
            time.sleep(0.5)
        return False

    def cleanup(self):
        self.wait_devices_idle()
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
    # 测试脚本允许保存中间态图（逐边连线/调设置/腾挪节点），走 draft=1；
    # 产品用户（无 localStorage 标记）仍会被服务端拒绝“有孤立节点/缺开始结束”的图。
    try:
        page.evaluate("localStorage.setItem('labos.designer.draft', '1')")
    except Exception:  # noqa: BLE001
        pass
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

    # 并行网关：入口在批 2 补齐，末尾做「拖出 + 落库」两条断言（见下）

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

    # 批 2 · 并行网关：palette 有入口 + 能拖出（后端 WorkflowGraphParser 完整支持）
    pw = page.locator('.djs-palette .entry[data-action="create.parallel-gateway"]')
    if pw.count() == 0:
        bad("设计器 palette 没有并行网关入口（批 2 未生效）")
    else:
        place(page, "create.parallel-gateway", 700, 320)
        page.wait_for_timeout(1200)
        n3 = page.locator(".djs-element[data-element-id]").count()
        ok(f"并行网关能拖出：画布图元 {n2} → {n3}") if n3 > n2 \
            else bad("拖出并行网关没生效", f"{n2} → {n3}")

        # palette 收敛：解析器不认的入口不该出现（否则拖上去会被 GraphRunner 静默跳过）
        gone = [a for a in ("create.intermediate-event", "create.data-object",
                            "create.data-store", "create.participant-expanded",
                            "create.group")
                if page.locator(f'.djs-palette .entry[data-action="{a}"]').count() > 0]
        ok("palette 已移除解析器不认的入口（中间/边界事件·数据对象·数据存储·泳道·分组）") if not gone \
            else bad("palette 仍有解析器不认的入口", ",".join(gone))

        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2500)
        xml3 = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
        xml3 = xml3 if isinstance(xml3, str) else json.dumps(xml3)
        ok("并行网关已落库（XML 含 bpmn:parallelGateway）") if "bpmn:parallelGateway" in xml3 \
            else bad("并行网关没落库", xml3[-200:])

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


# ---------------------------------------------------------------- F. 设计器 → 真跑接缝

def graph_edges(xml):
    """从图 XML 取连线：{id: (sourceRef, targetRef)}。不假设属性顺序。"""
    out = {}
    for m in re.finditer(r"<bpmn:sequenceFlow\b([^>]*)>", xml):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        if attrs.get("id"):
            out[attrs["id"]] = (attrs.get("sourceRef"), attrs.get("targetRef"))
    return out


def flow_bounds(xml):
    """从图 XML 的 BPMNDiagram 取节点坐标：{id: (x, y, w, h)}。"""
    out = {}
    for m in re.finditer(r"<bpmndi:BPMNShape\b([^>]*)>(.*?)</bpmndi:BPMNShape>", xml, re.S):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        nid = attrs.get("bpmnElement")
        b = re.search(r"<dc:Bounds\b([^>]*)/?>", m.group(2))
        if not nid or not b:
            continue
        ba = dict(re.findall(r'(\w+)="([^"]*)"', b.group(1)))
        try:
            out[nid] = (float(ba.get("x", 0)), float(ba.get("y", 0)),
                        float(ba.get("width", 0)), float(ba.get("height", 0)))
        except ValueError:
            continue
    return out


def validate_flow(xml):
    """检查页面拖出的图是否是一个合法流程；返回错误列表（空 = 合法）。

    规则（用户/运维对“一个流程”的直觉，也是引擎能跑的前提）：
      1. 恰好 1 个 startEvent、1 个 endEvent；
      2. 每条 sequenceFlow 的 sourceRef/targetRef 都存在；
      3. 除 start 外每个节点至少 1 条入边；除 end 外每个节点至少 1 条出边；
      4. 所有节点从 start 可达，且都能到达 end（不允许孤立/半截节点）；
      5. 禁止 1 进 1 出的网关（无意义直通；要么分叉要么汇合）；
      6. 每个分叉网关（出边>1）的每条分支都能到达一个同类型汇合网关（入边>1）；
      7. 每个汇合网关（入边>1）能从至少一个同类型分叉到达。
    """
    errs = []
    nodes = {}
    for m in re.finditer(
            r"<bpmn:(startEvent|endEvent|task|userTask|serviceTask|exclusiveGateway|parallelGateway)\b([^>]*)>",
            xml):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(2)))
        nid = attrs.get("id")
        if nid:
            nodes[nid] = m.group(1)
    starts = [k for k, v in nodes.items() if v == "startEvent"]
    ends = [k for k, v in nodes.items() if v == "endEvent"]
    if len(starts) != 1:
        errs.append("startEvent 数量 %d（必须 1）" % len(starts))
    if len(ends) != 1:
        errs.append("endEvent 数量 %d（必须 1）" % len(ends))

    flows = {}
    for m in re.finditer(r"<bpmn:sequenceFlow\b([^>]*)>", xml):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        src, tgt = attrs.get("sourceRef"), attrs.get("targetRef")
        if not src or not tgt or src not in nodes or tgt not in nodes:
            errs.append("连线 endpoint 不存在：%s -> %s" % (src, tgt))
            continue
        flows[attrs.get("id") or (src + "-" + tgt)] = (src, tgt)

    ins = {k: [] for k in nodes}
    outs = {k: [] for k in nodes}
    for src, tgt in flows.values():
        outs[src].append(tgt)
        ins[tgt].append(src)

    for k, kind in nodes.items():
        if kind == "startEvent":
            if ins[k]:
                errs.append("start 有入边：" + str(ins[k]))
            if not outs[k]:
                errs.append("start 没有出边")
        elif kind == "endEvent":
            if not ins[k]:
                errs.append("end 没有入边")
            if outs[k]:
                errs.append("end 有出边：" + str(outs[k]))
        else:
            if not ins[k]:
                errs.append("%s(%s) 没有入边（孤立/断线）" % (k, kind))
            if not outs[k]:
                errs.append("%s(%s) 没有出边（断线）" % (k, kind))

    def reach(seeds, adj):
        seen = set(seeds)
        stack = list(seeds)
        while stack:
            cur = stack.pop()
            for nx in adj.get(cur, []):
                if nx not in seen:
                    seen.add(nx)
                    stack.append(nx)
        return seen

    if starts:
        miss = [k for k in nodes if k not in reach([starts[0]], outs)]
        if miss:
            errs.append("从 start 走不到：" + ",".join(miss))
    if ends:
        miss = [k for k in nodes if k not in reach([ends[0]], ins)]
        if miss:
            errs.append("走不到 end：" + ",".join(miss))

    def gtype(kind):
        return "exclusive" if kind == "exclusiveGateway" else "parallel"

    for k, kind in nodes.items():
        if "Gateway" not in kind:
            continue
        if len(ins[k]) <= 1 and len(outs[k]) <= 1:
            errs.append("网关 %s 是 1 进 1 出（无意义直通）" % k)
        if len(outs[k]) > 1:
            joins = [j for j, jk in nodes.items()
                     if "Gateway" in jk and len(ins[j]) > 1 and gtype(jk) == gtype(kind) and j != k]
            ok_join = any(all(j in reach([b], outs) for b in outs[k]) for j in joins)
            # 异或分叉允许以同一个 end 事件作为「合并点」（BPMN 的 end 语义）；
            # 并行分叉必须到显式并行汇合，否则没有「等齐」语义。
            if gtype(kind) == "exclusive" and ends:
                ok_join = ok_join or all(ends[0] in reach([b], outs) for b in outs[k])
            if not ok_join:
                errs.append("分叉网关 %s(%s) 的分支没有共同汇合网关%s"
                            % (k, gtype(kind),
                               "（异或可用同一 end，但这里连 end 也不一致）" if gtype(kind) == "exclusive" else ""))
        if len(ins[k]) > 1:
            splits = [s for s, sk in nodes.items()
                      if "Gateway" in sk and len(outs[s]) > 1 and gtype(sk) == gtype(kind) and s != k]
            if not any(k in reach([s], outs) for s in splits):
                errs.append("汇合网关 %s(%s) 没有对应分叉" % (k, gtype(kind)))
    return errs


def page_save(page, timeout=20.0):
    """点画布「保存」，等设计器保存完成打点（父容器 data-graph-saved）。

    注意：bpmn-js 的 .djs-container 是容器里的**子元素**，打点在造 modeler 的
    父 div 上——所以用 [data-graph-saved] 读。
    """
    def saved_at():
        try:
            return page.evaluate(
                "() => (document.querySelector('[data-graph-saved]')?.getAttribute('data-graph-saved')) || ''")
        except Exception:  # noqa: BLE001
            return ""
    before = saved_at()
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    deadline = time.time() + timeout
    while time.time() < deadline:
        cur = saved_at()
        if cur and cur != before:
            page.wait_for_timeout(500)
            return True
        page.wait_for_timeout(120)
    return False


def set_node_builtin(page, label, key, var, value):
    """选中图元 → 节点设置 → 切内置节点（变量节点/表达式）→ 填两个参数 → 卡内保存。

    ⚠ 用裸鼠标点中心，不用 locator.click()：bpmn-js 图元在保存/compile 刷新窗口里
     actionability 检查会一直等（元素明明存在却 click 超时），connect() 早就用这个
    办法绕过了；4.15 的 run_and_check 连续设置多个分支节点时踩过。
    """
    card = None
    for _ in range(3):
        # 优先对 SVG 图元直接派发 click：右侧节点可能被「节点设置」卡/视口
        # 边界盖住，裸鼠标点击会打到覆盖层上，选中的还是上一个节点。
        try:
            sel = '.djs-element[data-element-id="%s"]' % key
            page.evaluate(
                "(sel) => { const el = document.querySelector(sel);"
                " if (el) el.dispatchEvent(new MouseEvent('click',"
                " {bubbles: true, cancelable: true, view: window})); }", sel)
        except Exception:  # noqa: BLE001
            cx, cy = center(page, key)
            page.mouse.click(cx, cy)
        page.wait_for_timeout(1200)
        c = page.locator(".ant-card", has_text="节点设置").first
        if c.count() == 0:
            continue
        # 卡片的标题带节点 id：必须确认“卡上就是刚点的那个节点”，否则连续设置
        # 多个分支节点时会拿上一张卡把设置反复写到同一个节点上（4.15 实测：
        # 4 个分支只有 1 个变内置节点，其余仍是 task → 执行转 manual 卡 paused）。
        try:
            title = c.locator(".ant-card-head-title").inner_text(timeout=2000)
        except Exception:  # noqa: BLE001
            title = ""
        if key in title:
            card = c
            break
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)
    if card is None:
        return False
    inputs = card.locator("input.ant-input")
    if inputs.count() < 2:
        sel = card.locator(".ant-select-selector").first
        if sel.count() == 0:
            return False
        sel.click()
        page.wait_for_timeout(400)
        page.locator(".ant-select-item-option", has_text=label).first.click()
        page.wait_for_timeout(700)
        # 节点设置卡的参数字段是异步回填的：切完类型后要等两个输入框出现，
        # 早 0.5s 直接 count<2 就 return False，会让 4 个分支只有个别真设上。
        deadline = time.time() + 6
        while inputs.count() < 2 and time.time() < deadline:
            page.wait_for_timeout(300)
        if inputs.count() < 2:
            return False
    inputs.nth(0).fill(var)
    inputs.nth(1).fill(value)
    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(1600)
    return True


def set_edge_condition(page, fid, cond="", default=False):
    """选中连线 → 「连线 · 条件」→ 写表达式/默认分支 → 卡内保存。"""
    conn = page.locator(f'g.djs-connection[data-element-id="{fid}"]').first
    if conn.count() == 0:
        return False
    try:
        page.evaluate(
            """(fid) => {
                 const p = document.querySelector(
                   `g.djs-connection[data-element-id="${fid}"] path`);
                 if (p) p.dispatchEvent(new MouseEvent('click', {
                   bubbles: true, cancelable: true, view: window
                 }));
               }""", fid)
        page.wait_for_timeout(800)
    except Exception:  # noqa: BLE001
        pass
    # 直线连线 bbox 中心就在线上；拐弯（曼哈顿路由）的 bbox 中心不在线上，
    # 点空会让面板不出现 —— 所以取 path 中点，用 getScreenCTM 换算到屏幕坐标。
    # 换算失败再退回 bbox 中心。
    try:
        pt = page.locator(
            f'g.djs-connection[data-element-id="{fid}"] path').first.evaluate(
                """p => {
                     const q = p.getPointAtLength(p.getTotalLength() / 2);
                     const m = p.getScreenCTM();
                     return [m.a * q.x + m.c * q.y + m.e, m.b * q.x + m.d * q.y + m.f];
                   }""")
    except Exception:  # noqa: BLE001
        pt = None
    if not pt:
        cbox = conn.bounding_box()
        pt = [cbox["x"] + cbox["width"] / 2, cbox["y"] + cbox["height"] / 2]
    page.mouse.click(pt[0], pt[1])
    page.wait_for_timeout(1200)
    card = page.locator(".ant-card", has_text="连线 · 条件").first
    if card.count() == 0:
        cbox = conn.bounding_box()
        page.mouse.click(cbox["x"] + cbox["width"] / 2,
                         cbox["y"] + cbox["height"] / 2)
        page.wait_for_timeout(1200)
        if card.count() == 0:
            return False
    card.locator("textarea").first.fill(cond)
    cb = card.locator('input[type="checkbox"]').first
    if cb.count() > 0:
        checked = cb.is_checked()
        if checked != default:
            cb.click()
            page.wait_for_timeout(200)
    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(1500)
    return True


def delete_node(page, key):
    """选中图元 → 点「删除选中节点」（设计器的显式按钮，删除即时落库）。"""
    el = page.locator(f'.djs-element[data-element-id="{key}"]')
    if el.count() == 0:
        return False
    el.first.click(force=True)
    page.wait_for_timeout(800)
    btn = page.get_by_role("button", name="删除选中节点")
    if btn.count() == 0:
        return False
    btn.first.click()
    page.wait_for_timeout(2500)
    return page.locator(f'.djs-element[data-element-id="{key}"]').count() == 0


def run_workflow(ctx, wf_id, wait=420):
    """等待预算：设备方法耗时随机 30–300 秒（真机时序）⇒ 90 秒不够用，
    会把「设备还在跑」误判成「流程没跑完」。只在设备真慢时才会等满。"""
    """触发一次真执行（与页面「执行实验」同一条执行链），返回 (run_id, status)。"""
    r = api(f"/api/workflows/{wf_id}/run?async=1", "POST", {}, ctx.token)
    rid = 0
    if isinstance(r, dict) and r.get("runId"):
        rid = int(r["runId"])
    if not rid:
        rid = int(q("SELECT COALESCE(max(id),0) FROM run WHERE tenant_id=1") or 0)
    if not rid:
        return 0, ""
    ctx.made_run.append(rid)
    deadline, st = time.time() + wait, ""
    while time.time() < deadline:
        st = q(f"SELECT COALESCE(status::text,'') FROM run WHERE id={rid}")
        if st in ("completed", "failed", "aborted", "paused"):
            break
        time.sleep(1)
    return rid, st


def sec_designer_to_run(page, ctx, base):
    """F. 接缝 · 设计器里画出来的异或分支图 → 真跑 → 分支走对。

    为什么单独有它：A / E 两节只验到「拖得出、存得下」，C 节借的是历史执行，
    后端矩阵的 xor/parallel 又是 build_xml() 直 POST —— **「页面上画的图，
    执行侧认不认」这条接缝一直没有断言**。这里把图从 palette 画出来（含连线
    条件、变量节点参数），再触发一次真执行，看分支是不是按页面上写的条件走。

    布局（收紧到画布左侧，避免侧栏变窄时右侧落点超界）：
        S(110,140) - SET(task→__setVar) - X(320,140) - A(task→__setVar)
                                             |
                                          B(task→__setVar) ---- E(560,180)

    2026-11-09 清理口径：
      这节验证的是「页面上配出来的图能不能真跑」。
      逻辑节点 Tab 的拖拽/落库已由 E 节单独覆盖；这里统一用普通 task，再经
      右侧节点设置切成 `__setVar`，能更稳定地聚焦“页面配置 -> 真执行”这条接缝。
    """
    step("F. 接缝 · 设计器画异或分支图 → 真跑 → 分支走对")
    if not have_db():
        skip("没给库连接，跳过（要断言 run_node 必须连库）")
        return
    wf_id = make_workflow(page, ctx, "EFM-接缝-异或-临时")

    # 新建流程自带种子图 start(S) → end(E)（WorkflowService.blankGraphXml），
    # 不删掉它，画布上就多一条 S→E 连线（它会淹没连线计数断言，也占着 S/E 两个 id）。
    # 先清空种子，再按自己的布局从头画。
    for seed in ("S", "E"):
        delete_node(page, seed)

    ids = {}
    for name, action, x, y in (("S", "create.start-event", 110, 140),
                               ("X", "create.exclusive-gateway", 320, 140),
                               ("E", "create.end-event", 560, 180)):
        ids[name] = place(page, action, x, y)
    ids["SET"] = place(page, "create.task", 210, 140)
    ids["A"] = place(page, "create.task", 450, 90)
    ids["B"] = place(page, "create.task", 450, 260)
    miss = [k for k, v in ids.items() if not v]
    if miss:
        bad("palette 落点失败（没拿到新图元 id）", ",".join(miss))
        return
    ok("按异或分支布局落 6 个节点（S/SET/X/A/B/E）")

    for a, b in (("S", "SET"), ("SET", "X"), ("X", "A"), ("X", "B"),
                 ("A", "E"), ("B", "E")):
        connect(page, ids[a], ids[b])
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)
    xml = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else json.dumps(xml)
    edges = graph_edges(xml)
    ok(f"6 条连线都落了库（{len(edges)} 条）") if len(edges) == 6 \
        else bad("连线数不对", f"{len(edges)}：{list(edges.values())}")

    if not set_node_builtin(page, "变量节点", ids["SET"], "counter", "1"):
        bad("SET 没写入参数（节点设置面板不配合）")
        return
    ok("SET 已写入变量参数 counter=1（节点本体直接来自逻辑节点 Tab）")
    ok_a = set_node_builtin(page, "变量节点", ids["A"], "hit", "yes")
    ok_b = set_node_builtin(page, "变量节点", ids["B"], "hit", "no")
    ok("A/B 两分支写入 hit=yes / hit=no") if (ok_a and ok_b) \
        else bad("A/B 参数写入失败", f"A={ok_a}, B={ok_b}")
    if not (ok_a and ok_b):
        return

    for label, key in (("SET", ids["SET"]), ("A", ids["A"]), ("B", ids["B"])):
        j = api(f"/api/workflows/{wf_id}/nodes/{key}", token=ctx.token)
        if isinstance(j, dict) and j.get("method") == "__setVar":
            ok(f"{label} 已落成内置逻辑节点（method=__setVar）")
        else:
            bad(f"{label} 不是内置逻辑节点", json.dumps(j, ensure_ascii=False)[:160])
            return

    # 只给 X→A 写命中条件即可：GraphRunner 在 XOR 上会先按出边顺序求值，
    # 第一个成立的条件直接胜出；另一条边无需额外再去页面里点一次，
    # 这样能把这节聚焦在「页面写出的条件是否真被执行器识别」。
    fa = next((f for f, e in edges.items()
               if e == (ids["X"], ids["A"])), None)
    if not fa or not set_edge_condition(page, fa, "${counter} == 1"):
        bad("X→A 的连线条件没写上", str(fa))
        return
    ok("X→A 条件已在页面上写好（${counter} == 1）")

    # ★ 关键顺序（与 e2e-sim-flow 同坑）：节点设置/连线条件只 patch 了图 XML，
    #   执行侧读的是 XML 里的 methodParams / conditionExpression，必须再点一次
    #   页面级「保存」把设置固化回 workflow_node。
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)
    xml2 = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml2 = xml2 if isinstance(xml2, str) else json.dumps(xml2)
    n_cond = xml2.count("conditionExpression")
    ok(f"条件已进图（conditionExpression ×{n_cond}）") if n_cond >= 1 \
        else bad("条件没进图 XML", xml2[-260:])
    ok("变量节点参数进了图（methodParams 带 counter）") if "counter" in xml2 \
        else bad("__setVar 参数没进图 XML", xml2[-260:])

    rid, st = run_workflow(ctx, wf_id)
    if not rid:
        bad("触发执行失败（没有新 run 落库）")
        return
    ok(f"触发真执行（run #{rid} → {st or '未终态'}）")
    nodes = q(f"SELECT node_key||'='||status::text FROM run_node "
              f"WHERE run_id={rid} AND level=0 ORDER BY id")
    ok("整单 completed") if st == "completed" else bad("整单没跑完", f"{st}；{nodes}")
    ok("SET 执行过（页面上设的 __setVar 被执行侧认到）") if f"{ids['SET']}=completed" in nodes \
        else bad("SET 没执行", nodes)
    ok("条件命中：走了 A（${counter} == 1 生效）") if f"{ids['A']}=completed" in nodes \
        else bad("没走 A", nodes)
    ok("条件落空：B 标 bypassed") if f"{ids['B']}=bypassed" in nodes \
        else bad("B 状态不是 bypassed", nodes)


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
            sec_designer_to_run(page, ctx, args.base)
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
