#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 仿真机联调（页面建实验 → 建流程 → 绑真机 → 执行 → 真回包）。

为什么单独有它：
  scripts/verify-flow-matrix.py（接口/XML 侧）与 scripts/e2e-flow-matrix.py
  （页面侧）覆盖的是"节点/网关/变量/循环/暂停"这些**逻辑与界面**能不能用；
  整条链里**设备那一段是空的** —— 页面里没绑过真设备，执行器走的是空跑。
  这个用例把"仿真机在跑"接进来，验证**一条指令真的到了设备再回来**：

  · 台架：labos-kernel/scripts/sim-up.sh 起的 DeviceSimulator（tcp://19500..19506）
  · 链路：登录
          → 流程库建流程 → 设计器装配 start→task→end，在「节点设置」里把 task
            绑成仿真设备任务（ID1731 / Home）→ 保存
          → 实验列表进详情 →「新建实验」选岛 LAB-DEMO-02 + 挂上刚建的流程 → 创建
          → 详情页点「执行实验」
  · 断言：experiment.status=finished、run.status=completed，且
          **run_command.device_raw_response 非空**（= 真跟设备来回了一趟，
          不是空跑；空跑这条一定是 null）。
  · 收尾：删掉本次造的 experiment / workflow / run（含 run_command / run_node），
          复位 position_state。

边界（C 类，只观察不判失败）：
  · 只**复用**已经跑起来的仿真机；没起来就整体 SKIP 并提示去跑 sim-up.sh，
    **不主动拉起、不 kill** —— 拉起/回收是台架（sim-up.sh）的事。
  · 设计器 palette 没有并行网关入口（并行结构只能在 XML/接口侧建），
    本用例只用 start / task / end。

前置：
  · ApiServer 18080、前端可访问（默认 http://localhost:5173；nginx 8080 用 --base）；
  · 7 台 DeviceSimulator 在 19500..19506 监听（labos-kernel/scripts/sim-up.sh）；
  · 连库环境变量 LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_NAME / LABOS_DB_USER /
    LABOS_DB_PASS（缺失则页面步骤照跑，库断言与清理跳过）。

用法：
  python3 scripts/e2e-sim-flow.py
  python3 scripts/e2e-sim-flow.py --base http://127.0.0.1:8080 --headed
"""
import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

PASS, FAIL, SKIP = 0, 0, 0
BASE_API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

# 仿真台架：sim-up.sh 给某座岛的设备依次分配的端口
SIM_PORTS = list(range(19500, 19507))
# 示例岛 + 岛上的机械臂（ID1731 的方法集里一定有 Home）
ISLAND_CODE = "LAB-DEMO-02"
DEV_CODE = "ID1731"
DEV_RID = 200214
DEV_METHOD = "Home"


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
    with urllib.request.urlopen(req, data=data, timeout=90) as r:
        txt = r.read().decode()
        try:
            return json.loads(txt)
        except json.JSONDecodeError:
            return txt


def tcp_open(port, host="127.0.0.1", timeout=1.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


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


def nav(page, menu):
    page.locator(f'.ant-menu-item:has-text("{menu}")').first.click()
    page.wait_for_timeout(1200)


# ---------------------------------------------------------------- 设计器装配

def canvas_ids(page):
    return page.locator(".djs-element[data-element-id]").evaluate_all(
        "els => els.map(e => e.getAttribute('data-element-id'))")


def place(page, action, x, y):
    """点 palette 项 → 在画布落点；返回新图元的 id（排除 *_label 附属图元）。"""
    before = set(canvas_ids(page))
    page.locator(f'.djs-palette .entry[data-action="{action}"]').first.click()
    page.wait_for_timeout(150)
    box = page.locator(".djs-container").bounding_box()
    # 设计器加了左侧「按岛设备」面板后画布变窄，固定 x=950 会点到容器外，
    # 节点静默不生成（曾表现为「palette 落点失败 end=None」）。按容器宽度等比缩放。
    scale = min(1.0, (box["width"] - 40) / 950) if box["width"] < 990 else 1.0
    page.mouse.click(box["x"] + x * scale, box["y"] + y)
    page.wait_for_timeout(400)
    new = [i for i in sorted(set(canvas_ids(page)) - before) if not i.endswith("_label")]
    return new[0] if len(new) == 1 else None


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


def pick_in_select(page, selector, text, exact_prefix=None):
    """打开一个 antd Select，用键盘搜（showSearch）后点选项。

    exact_prefix：设备池里 ID1731 / ID1731-2 会同时命中，用「^ID1731 ·」这种
    锚定前缀把两者分开。
    """
    selector.click()
    page.wait_for_timeout(500)
    page.keyboard.type(text)
    page.wait_for_timeout(600)
    opts = page.locator(".ant-select-dropdown:visible .ant-select-item-option")
    if exact_prefix:
        opts.filter(has_text=re.compile(exact_prefix)).first.click()
    else:
        opts.first.click()
    page.wait_for_timeout(700)


# ---------------------------------------------------------------- 清理

class Ctx:
    def __init__(self, token):
        self.token = token
        self.made_wf = []
        self.made_exp = []
        self.made_run = []

    def _run(self, *stmts):
        """逐条执行：-c 的多语句是一批，错一句整批回滚，这里要的是"能删多少删多少"。"""
        for s in stmts:
            q(s)

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
            self._run(
                f"DELETE FROM run_command WHERE run_id={rid}",
                f"DELETE FROM run_node WHERE run_id={rid}",
                f"DELETE FROM run_variable WHERE run_id={rid}",
                f"DELETE FROM run_artifact WHERE run_id={rid}",
                f"UPDATE experiment SET run_id=NULL WHERE run_id={rid}",
                f"UPDATE error_occurrence SET run_id=NULL WHERE run_id={rid}",
                f"UPDATE schedule_item SET run_id=NULL WHERE run_id={rid}",
                f"UPDATE position_state SET reserved_by_run=NULL, block_reason=NULL, "
                f"reserved_until=NULL WHERE tenant_id=1 AND reserved_by_run={rid}",
                f"DELETE FROM run WHERE id={rid}",
            )
        for eid in self.made_exp:
            # 顺序有讲究：experiment_run 对 experiment 是 NO ACTION（非 CASCADE），
            # 必须先删 experiment_run 再删 experiment，否则被外键挡住。
            self._run(
                f"DELETE FROM experiment_run_step WHERE experiment_run_id IN "
                f"(SELECT id FROM experiment_run WHERE experiment_id={eid})",
                f"DELETE FROM experiment_batch_run WHERE experiment_run_id IN "
                f"(SELECT id FROM experiment_run WHERE experiment_id={eid})",
                f"DELETE FROM experiment_run WHERE experiment_id={eid}",
                f"DELETE FROM experiment_process_edge WHERE experiment_id={eid}",
                f"DELETE FROM experiment_process WHERE experiment_id={eid}",
                f"DELETE FROM experiment_variable WHERE experiment_id={eid}",
                f"DELETE FROM experiment_graph WHERE experiment_id={eid}",
                f"DELETE FROM experiment_batch WHERE experiment_id={eid}",
                f"UPDATE experiment SET run_id=NULL WHERE id={eid}",
                f"DELETE FROM experiment WHERE id={eid}",
            )
        for wf in self.made_wf:
            self._run(
                f"DELETE FROM workflow_node WHERE workflow_id={wf}",
                f"DELETE FROM workflow WHERE id={wf}",
            )


# ---------------------------------------------------------------- 0. 台架在位

ISLAND_HOLD_COND = (
    "tenant_id=1 AND released_at IS NULL AND scope='island' AND scope_id="
    "(SELECT id FROM resource_node WHERE tenant_id=1 AND kind::text='island' "
    "AND code='" + ISLAND_CODE + "')")


def reset_island_holds(note):
    """解掉演示岛上遗留的 island 级现场扣留，返回解掉的条数。

    为什么必须做：上一轮 e2e 只要有一条流程跑成"转人工/跳过"，执行器就会按
    "现场暂停是安全事件"把**整座岛**扣住（ExperimentExecutor 里 raise ISLAND），
    这条 runtime_hold 不会自己消失。之后同岛上的**任何**新实验都会在动作边界
    直接落 paused，流程级 run 一个都不会创建 —— 表现成"执行实验后没有新 run
    落库"，看着像执行链路坏了，其实是上一轮留下的现场状态。

    这是测试残留，不是被测行为，所以开跑前先复位；收尾时再复位一次，别把
    残留留给下一次。
    """
    n = q(f"SELECT count(*) FROM runtime_hold WHERE {ISLAND_HOLD_COND}") or "0"
    if int(n) > 0:
        q(f"UPDATE runtime_hold SET released_at=now(), release_note='{note}' "
          f"WHERE {ISLAND_HOLD_COND}")
    return int(n)


def sec_reset_holds():
    step("0b. 前置复位：解掉演示岛上遗留的现场扣留（runtime_hold）")
    if not have_db():
        skip("没给库连接，跳过现场扣留复位")
        return
    n = reset_island_holds("e2e-sim-flow 前置复位")
    left = q(f"SELECT count(*) FROM runtime_hold WHERE {ISLAND_HOLD_COND}") or "0"
    if n == 0:
        ok("演示岛上没有遗留扣留")
    elif int(left) == 0:
        ok(f"解掉 {n} 条上一轮遗留的岛级扣留（解不干净会让执行直接落 paused）")
    else:
        bad("遗留扣留没解干净", f"before={n} after={left}")


def sec_sim_online():
    step("0. 仿真机在位（DeviceSimulator 19500..19506 + device_state.online）")
    down = [p for p in SIM_PORTS if not tcp_open(p)]
    if len(down) == len(SIM_PORTS):
        skip("没有仿真机在监听 19500..19506 —— 先跑 labos-kernel/scripts/sim-up.sh "
             "再回来（本用例只复用、不拉起）")
        return False
    if down:
        print(f"  \033[33m·\033[0m 观察：端口 {down} 没在听（少几台也能跑，继续）")
    ok(f"仿真机在监听：{len(SIM_PORTS) - len(down)}/{len(SIM_PORTS)} 台"
       + (f"（缺 {down}）" if down else ""))

    if not have_db():
        skip("没给库连接，跳过 device_state.online 校验")
        return True
    n = q(f"SELECT count(*) FROM device_state WHERE online AND resource_id "
          f"IN (200214, 504824, 200206, 200171, 200210, 504825, 504826)")
    ok(f"device_state.online=true 的设备（演示岛）：{n} 台") if n and int(n) > 0 \
        else bad("演示岛上没有 online=true 的设备", str(n))
    return True


# ---------------------------------------------------------------- A. 建流程 + 绑真机

def sec_designer_bind(page, ctx, base):
    step("A. 页面建流程 → 设计器装配 → 节点设置绑仿真设备（ID1731 / Home）→ 保存")
    nav(page, "流程库")
    page.get_by_role("button", name=re.compile(r"新建流程")).first.click()
    page.wait_for_selector(".ant-modal-content", timeout=10000)
    wf_name = f"E2E-SIM-流程-{int(time.time())}"
    page.locator(".ant-modal-content input").first.fill(wf_name)
    page.locator(".ant-modal-footer button.ant-btn-primary").click()
    page.wait_for_url("**/designer/**", timeout=20000)
    page.wait_for_selector(".djs-container", timeout=25000)
    page.wait_for_timeout(1800)
    wf_id = int(re.search(r"/designer/(\d+)", page.url).group(1))
    ctx.made_wf.append(wf_id)
    ok(f"界面新建流程并进入设计器（#{wf_id} · {wf_name}）")

    s = place(page, "create.start-event", 150, 150)
    t1 = place(page, "create.task", 450, 150)
    e = place(page, "create.end-event", 950, 150)
    if s and t1 and e:
        ok(f"palette 落点成功：start={s} task={t1} end={e}")
    else:
        bad("palette 落点失败", f"start={s} task={t1} end={e}")
        return wf_id, wf_name, None

    connect(page, s, t1)
    connect(page, t1, e)
    ok("已用连线工具串成 start→task→end")

    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)
    xml = api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
    xml = xml if isinstance(xml, str) else json.dumps(xml)
    n_flow = xml.count("sequenceFlow")
    ok(f"保存落库：图里有 {n_flow} 条 sequenceFlow + task 节点") if n_flow >= 2 \
        and "bpmn:task" in xml else bad("保存落库不完整", f"flow={n_flow}")

    # 节点设置：绑仿真设备
    page.locator(f'.djs-element[data-element-id="{t1}"]').first.click()
    page.wait_for_timeout(1500)
    card = page.locator(".ant-card", has_text="节点设置").first
    if card.count() == 0:
        bad("选中 task 后没有「节点设置」面板")
        return wf_id, wf_name, None
    sels = card.locator(".ant-select-selector")
    if sels.count() < 3:
        bad("节点设置的三个下拉没齐", f"select={sels.count()}")
        return wf_id, wf_name, None
    ok("「节点设置」出现，且 kind/设备/方法 三个下拉都在")

    # 第 1 个下拉默认就是「设备任务（走驱动）」；第 2 个选设备；第 3 个选方法
    pick_in_select(page, sels.nth(1), DEV_CODE, exact_prefix=rf"^{DEV_CODE} ·")
    pick_in_select(page, sels.nth(2), DEV_METHOD, exact_prefix=r"^Home( ·|$)")
    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2000)

    j = api(f"/api/workflows/{wf_id}/nodes/{t1}", token=ctx.token)
    got = j if isinstance(j, dict) else {}
    if got.get("resourceId") == DEV_RID and got.get("method") == DEV_METHOD:
        ok(f"节点设置落库：resourceId={DEV_RID}（{DEV_CODE}）method={DEV_METHOD}")
    else:
        bad("设备绑定没落库", json.dumps(got, ensure_ascii=False)[:160])

    # ★ 关键顺序：节点设置只改了一次 workflow_node + patch 了图 XML，执行侧读的是
    #   图 XML 解析出来的 protocol_name（见 WorkflowService.saveGraph）。
    #   所以绑完设备必须**再点一次页面级「保存」**，让 XML 里的 protocolName/resource
    #   回流进 workflow_node，否则执行时 method 会退化成 UNKNOWN。
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)
    plan = api(f"/api/workflows/{wf_id}/compile", token=ctx.token)
    plan_s = plan if isinstance(plan, str) else json.dumps(plan, ensure_ascii=False)
    ok("再保存后编译计划里带上了设备方法（method=Home）") if DEV_METHOD in plan_s \
        else bad("编译计划里没有 Home —— 执行会退化成 UNKNOWN", plan_s[:160])
    if have_db():
        proto = q(f"SELECT COALESCE(params->>'protocol_name','') FROM workflow_node "
                  f"WHERE workflow_id={wf_id} AND node_key='{t1}'")
        ok(f"workflow_node.params.protocol_name = {proto}") if proto == DEV_METHOD \
            else bad("protocol_name 没回流进 workflow_node", str(proto))
    return wf_id, wf_name, t1


# ---------------------------------------------------------------- B. 页面建实验

def sec_create_experiment(page, ctx, base, wf_name):
    step("B. 页面建实验（详情页「新建实验」：选岛 LAB-DEMO-02 + 挂上刚建的流程）")
    nav(page, "实验列表")
    rows = page.locator(".ant-table-tbody tr.ant-table-row")
    if rows.count() == 0:
        # 列表空就没有能进的详情页；用列表页自己的「新建实验」先垫一个
        page.locator("button.ant-btn", has_text=re.compile(r"新\s*建\s*实\s*验")).first.click()
        page.wait_for_selector(".ant-modal-content:visible", timeout=10000)
        page.locator(".ant-modal-content:visible .ant-input").first.fill(
            f"E2E-SIM-垫场-{int(time.time())}")
        page.locator(".ant-modal-content:visible .ant-modal-footer button.ant-btn-primary").click()
        page.wait_for_url(re.compile(r"/experiments/\d+"), timeout=20000)
    else:
        rows.first.click()
        page.wait_for_url(re.compile(r"/experiments/\d+"), timeout=20000)
        page.wait_for_timeout(1500)
    ok("已进入实验详情页")

    before = {e["id"] for e in (api("/api/experiments?limit=200", token=ctx.token).get("items") or [])}

    page.locator("button.ant-btn", has_text=re.compile(r"新\s*建\s*实\s*验")).first.click()
    page.wait_for_selector(".ant-modal-content:visible", timeout=10000)
    modal = page.locator(".ant-modal-content:visible")
    exp_name = f"E2E-SIM-实验-{int(time.time())}"
    modal.locator(".ant-input").first.fill(exp_name)

    # 岛（单选框，label = "<code> <name>（n 台）"）
    pick_in_select(page, modal.locator(".ant-select-selector").nth(0), ISLAND_CODE)
    ok(f"已选岛 {ISLAND_CODE}")

    # 挂流程（多选框，label = "<name> (code)"）
    pick_in_select(page, modal.locator(".ant-select-selector").nth(1), wf_name)
    ok(f"已挂流程 {wf_name}")

    # 关掉还开着的下拉（它在 body 层的 portal 里，会盖住弹窗底部的「创建」按钮）
    page.keyboard.press("Escape")
    page.wait_for_timeout(400)
    foot = page.locator(".ant-modal-footer").filter(has=page.locator("button.ant-btn-primary"))
    n_foot = foot.count()
    if n_foot == 0:
        bad("弹窗没有「创建」按钮")
        return None, exp_name
    try:
        foot.last.locator("button.ant-btn-primary").click(timeout=8000)
    except PWTimeout:
        print(f"  \033[33m·\033[0m 常规点击超时（footer 命中 {n_foot} 个），改用强点")
        foot.last.locator("button.ant-btn-primary").click(force=True)
    page.wait_for_timeout(2500)

    after = {e["id"] for e in (api("/api/experiments?limit=200", token=ctx.token).get("items") or [])}
    new_ids = sorted(after - before)
    if not new_ids:
        bad("页面上建完实验，列表接口里找不到新实验")
        return None, exp_name
    exp_id = new_ids[-1]
    ctx.made_exp.append(exp_id)

    # 详情页的「新建实验」建完不会跳（页面停在原实验），显式打开新实验
    page.goto(f"{base}/experiments/{exp_id}", wait_until="networkidle")
    page.wait_for_timeout(2000)
    ok(f"页面新建实验成功（#{exp_id} · {exp_name}）")

    procs = api(f"/api/experiments/{exp_id}/processes", token=ctx.token).get("items") or []
    ok(f"实验已编排 {len(procs)} 条流程（seq={[p.get('seq') for p in procs]}）") \
        if procs else bad("实验编排里没有流程（执行按钮会是灰的）")
    return exp_id, exp_name


# ---------------------------------------------------------------- C. 页面执行 + 真回包

def sec_run_and_assert(page, ctx, exp_id):
    step("C. 页面「执行实验」→ 断言跑完 + run_command.device_raw_response 非空")
    if not have_db():
        skip("没给库连接，跳过执行断言")
        return
    before_max = int(q("SELECT COALESCE(MAX(id),0) FROM run WHERE tenant_id=1") or 0)

    btn = page.locator("button.ant-btn", has_text=re.compile(r"执\s*行\s*实\s*验")).first
    if btn.count() == 0:
        bad("详情页上没有「执行实验」按钮")
        return
    ok("详情页有「执行实验」按钮")
    btn.click()

    # 同步执行：轮询库，等这次产生的新 run 落库
    deadline = time.time() + 420
    new_runs = []
    while time.time() < deadline:
        rows = q(f"SELECT id || ':' || status FROM run WHERE tenant_id=1 AND id > {before_max} "
                 f"ORDER BY id")
        new_runs = [r for r in rows.split() if r]
        if any(r.split(":")[1] in ("completed", "failed", "aborted") for r in new_runs):
            break
        page.wait_for_timeout(2000)
    if not new_runs:
        bad("执行实验后没有新 run 落库")
        return
    ids = [int(r.split(":")[0]) for r in new_runs]
    ctx.made_run.extend(ids)
    ok(f"这次执行落了 {len(ids)} 条 run（id={ids}）")

    done = q(f"SELECT count(*) FROM run WHERE id IN ({','.join(map(str, ids))}) "
             f"AND status='completed'")
    ok(f"run.status=completed：{done} 条") if done and int(done) > 0 \
        else bad("没有一条 run 跑到 completed",
                 q(f"SELECT string_agg(DISTINCT status::text,',') FROM run WHERE id IN "
                   f"({','.join(map(str, ids))})"))

    st = q(f"SELECT status FROM experiment WHERE id={exp_id}")
    ok(f"experiment.status={st}") if st == "finished" else bad("实验顶层状态不是 finished", st)

    n_cmd = q(f"SELECT count(*) FROM run_command WHERE run_id IN ({','.join(map(str, ids))})")
    raw = q(f"SELECT count(*) FROM run_command WHERE run_id IN ({','.join(map(str, ids))}) "
            f"AND device_raw_response IS NOT NULL")
    ok(f"run_command {n_cmd} 条，其中 {raw} 条有设备回包原文") \
        if n_cmd and int(n_cmd) > 0 and raw and int(raw) > 0 \
        else bad("run_command 没有设备回包（等于空跑）", f"cmd={n_cmd} raw={raw}")

    meth = q(f"SELECT string_agg(DISTINCT method, ',') FROM run_command WHERE run_id IN "
             f"({','.join(map(str, ids))})")
    ok(f"落库指令方法 = {meth}（页面绑的 {DEV_METHOD}）") if meth and DEV_METHOD in meth \
        else bad("落库指令方法不含绑定方法", str(meth))

    sample = q(f"SELECT device_raw_response::text FROM run_command WHERE run_id IN "
               f"({','.join(map(str, ids))}) AND device_raw_response IS NOT NULL LIMIT 1")
    if sample:
        print(f"  \033[33m·\033[0m 设备回包样例：{sample[:140]}")

    # 页面侧也应该显示这次执行（执行历史表里出现 run）
    body = page.inner_text("body")
    ok("页面执行历史里能看到本次执行") if str(ids[-1]) in body or "已完成" in body or \
        "completed" in body else bad("页面没反映本次执行")


# ---------------------------------------------------------------- 主流程

def main():
    global ISLAND_CODE, DEV_CODE, DEV_RID, DEV_METHOD
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

    if not sec_sim_online():
        print()
        print("-" * 42)
        print(f"通过 {PASS} / 失败 {FAIL}（跳过 1 节）")
        print("-" * 42)
        return 0
    sec_reset_holds()

    ctx = Ctx(token)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1500, "height": 950})
        page.set_default_timeout(20000)
        try:
            login(page, args.base, args.user, args.pwd)
            ok("登录成功")
            wf_id, wf_name, _t = sec_designer_bind(page, ctx, args.base)
            exp_id, _n = sec_create_experiment(page, ctx, args.base, wf_name) if wf_name else (None, "")
            if exp_id:
                sec_run_and_assert(page, ctx, exp_id)
            else:
                bad("实验没建成，后面跳过")
            page.screenshot(path="/tmp/e2e-sim-flow.png")
        except PWTimeout as e:
            bad("超时", str(e).split("\n")[0][:160])
            try:
                page.screenshot(path="/tmp/e2e-sim-flow-timeout.png")
                print("     截图：/tmp/e2e-sim-flow-timeout.png")
            except Exception:
                pass
        except Exception as e:  # noqa: BLE001
            bad("异常", str(e)[:220])
        finally:
            browser.close()
            print("\n--- 清理 ---")
            ctx.cleanup()
            if have_db() and ctx.made_exp:
                left = q(f"SELECT count(*) FROM experiment WHERE id IN "
                         f"({','.join(map(str, ctx.made_exp))})")
                print(f"     残留 experiment={left}")
            if have_db():
                n = reset_island_holds("e2e-sim-flow 收尾复位")
                if n:
                    print(f"     解掉本次跑出来的岛级扣留 {n} 条（不影响下一次）")

    print()
    print("-" * 42)
    extra = f"（跳过 {SKIP} 节）" if SKIP else ""
    print(f"通过 {PASS} / 失败 {FAIL}{extra}")
    print("-" * 42)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
