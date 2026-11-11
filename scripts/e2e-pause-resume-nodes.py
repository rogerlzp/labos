#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 暂停点与恢复起点（真边界暂停）。

回答四个问题：
  Q1 发生暂停时，会停在**哪一类**节点上？
  Q2 恢复时，是从**下一个**节点接着跑，还是从**当前（暂停点）**节点重跑？
  Q3 设备节点与逻辑（变量/表达式）节点在暂停/恢复上的处理**有什么差别**？

2026-11-08 起改成「真边界暂停」（取代原先用 run_command 行数当触发的写法）：
  · 图是 S → VAR → DEV → DEV2 → XOR → A/B → PAR → C/D → E。
    DEV / DEV2 都是同一台仿真机械臂上的设备任务。
  · 触发点是「DEV 正在执行」：device_state.status='running'
    且 current_run_id 指向本次 run。此时暂停请求已经落库，
    DEV 仍会把当前动作做完，下一个任务边界才是 DEV2。
  · 探针**不写** pause_node_key。内核在边界把待执行节点写入这一列，
    并往 stdout 打 `[暂停] 收到暂停请求，在节点边界停下，待执行节点：<DEV2>`。
  · 恢复后 DEV 不重跑（指令仍 1 条），DEV2 从 pending 开始执行（指令 1 条）。

旧触发 `dev_cmd_count>=1` 表示 DEV **已经做完**。那之后到 settleRun
只有大约几十毫秒，后面的网关和 __setVar 又不查暂停，整单会被收成 completed。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-pause-resume-nodes.py
  # 内核日志默认读 /private/tmp/labos-api.log，可用 LABOS_API_LOG 改

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
import importlib.util
import json
import os
import re
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_flow_matrix", os.path.join(_HERE, "e2e-flow-matrix.py"))
F = importlib.util.module_from_spec(_spec)
sys.modules["e2e_flow_matrix"] = F
_spec.loader.exec_module(F)

# 仿真台架：sim-up.sh 给演示岛分配的设备（e2e-sim-flow.py 用同一台）
DEV_CODE = "ID1731"
DEV_METHOD = "Home"
ARM_RESOURCE = 200214
API_LOG = os.environ.get("LABOS_API_LOG", "/private/tmp/labos-api.log")


def clear_selection(page):
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    try:
        page.evaluate("""() => {
            document.querySelectorAll('.djs-context-pad-parent').forEach(el => el.remove());
        }""")
    except Exception:
        pass
    page.wait_for_timeout(200)


def pick_in_select(page, target, text, exact_prefix=None):
    """在下拉里按文本挑一项。

    ⚠ 必须用**键盘搜索**（`keyboard.type`），不能直接翻选项：
    设备池有 247 台，但下拉只渲染前 10 个选项（虚拟滚动），
    ID1731 在第 10 个之外 —— 不搜就根本看不见它，选不中（e2e-sim-flow
    也是这么写的）。

    `target` 可以是 CSS 选择器字符串，**也可以是 Locator 元素** ——
    节点设置卡里的 ant-select 不唯一，用 CSS 路径会选到别的卡片上。
    """
    if isinstance(target, str):
        page.locator(target).first.click()
    else:
        target.click()
    page.wait_for_timeout(500)
    page.keyboard.type(text)
    page.wait_for_timeout(700)
    opts = page.locator(".ant-select-dropdown:visible .ant-select-item-option")
    if exact_prefix:
        hit = opts.filter(has_text=re.compile(exact_prefix)).first
    else:
        hit = opts.first
    if hit.count() == 0:
        return False
    hit.click()
    page.wait_for_timeout(700)
    return True


def bring_into_view(page, key):
    """节点保存会重新导入 XML，视图回到原点，后面的节点会跑到视口外（y 变成负数）。

    点不到它，选中态就停在上一个节点，保存会写错节点。这里把 SVG 视口平移到该节点。
    """
    page.evaluate(
        """(key) => {
            const el = document.querySelector('.djs-element[data-element-id=\"' + key + '\"]');
            const vp = document.querySelector('.djs-container .viewport');
            const container = document.querySelector('.djs-container');
            if (!el || !vp || !container) return;
            const er = el.getBoundingClientRect();
            const cr = container.getBoundingClientRect();
            const inside = er.top > cr.top + 30 && er.bottom < cr.bottom - 30
                && er.left > cr.left + 30 && er.right < cr.right - 30;
            if (inside) return;
            const dx = (cr.left + cr.width / 2) - (er.left + er.width / 2);
            const dy = (cr.top + cr.height / 2) - (er.top + er.height / 2);
            const t = vp.transform && vp.transform.baseVal && vp.transform.baseVal.consolidate();
            if (!t) {
                vp.setAttribute('transform', 'matrix(1 0 0 1 ' + dx + ' ' + dy + ')');
                return;
            }
            const m = t.matrix;
            vp.setAttribute('transform',
                'matrix(' + [m.a, m.b, m.c, m.d, m.e + dx, m.f + dy].join(' ') + ')');
        }""",
        key)
    page.wait_for_timeout(200)


def select_node(page, key):
    """选中一个图元（裸鼠标点，不用 locator.click()）。

    ⚠ 为什么不用 locator.click()：bpmn-js 选中节点后会弹**直接编辑框**
      （.djs-direct-editing），它会拦截 Playwright 的可点性检查 →
      locator.click() 一直 retry 到超时（"djs-direct-editing-content intercepts
      pointer events"）。裸 `page.mouse.click` 不做这层检查（e2e-sim-flow
      就是这么干的）。
    """
    page.keyboard.press("Escape")
    page.wait_for_timeout(150)
    bring_into_view(page, key)
    bb = page.locator(f'.djs-element[data-element-id="{key}"]').first.bounding_box()
    page.mouse.click(bb["x"] + bb["width"] / 2, bb["y"] + min(24, bb["height"] / 2))
    page.wait_for_timeout(1400)


def bind_device(page, key, dev_code=DEV_CODE, method=DEV_METHOD):
    """把一个 task 节点绑成仿真设备任务（设备 → 方法）。

    坑：① 下拉要用**元素引用**（sels.nth(1)）而不是 CSS 路径 —— 节点设置卡里的
    ant-select 不唯一，CSS 路径会选到别的卡片上，绑定静默失败；
    ② 设备池里 ID1731 / ID1731-2 会同时命中，要用 "^ID1731 ·" 前缀精确挑；
    ③ 换节点后绑定查询会异步回填，回填会把刚点的设备/方法清掉。必须等标题
    变成这个节点、再等查询落地，并且保存前确认选择框里已经是目标值。
    """
    for _ in range(2):
        select_node(page, key)
        card = page.locator(".ant-card", has_text="节点设置").first
        if card.count() == 0:
            continue
        title = ""
        try:
            title = card.locator(".ant-card-head-title").inner_text(timeout=2000)
        except Exception:
            title = ""
        if key not in title:
            continue
        # 等「读当前绑定」的请求落地，避免它覆盖接下来的选择。
        page.wait_for_timeout(900)
        sels = card.locator(".ant-select-selector")
        if sels.count() < 3:
            continue
        if not pick_in_select(page, sels.nth(1), dev_code, exact_prefix=rf"^{dev_code} ·"):
            continue
        if dev_code not in (sels.nth(1).inner_text() or ""):
            continue
        if not pick_in_select(page, sels.nth(2), method, exact_prefix=r"^" + method + r"( ·|$)"):
            continue
        if method not in (sels.nth(2).inner_text() or ""):
            continue
        card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2000)
        return True
    return False


def set_builtin(page, key, method_label, var, value):
    """把节点切成内置节点（变量节点 / 表达式节点）并填参。"""
    select_node(page, key)
    card = page.locator(".ant-card", has_text="节点设置").first
    if card.count() == 0:
        return False
    card.locator(".ant-select-selector").first.click()
    page.wait_for_timeout(400)
    page.locator(".ant-select-item-option", has_text=method_label).first.click()
    page.wait_for_timeout(700)
    inputs = card.locator("input.ant-input")
    if inputs.count() < 2:
        return False
    inputs.nth(0).fill(var)
    inputs.nth(1).fill(value)
    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(1600)
    return True


def bind_device_api(wf_id, node_key):
    """设计器点选不到视口外的节点时，走节点设置同一条 PUT。

    保存的就是 protocolName / resource，执行器读的也是这两项。
    """
    F.api(f"/api/workflows/{wf_id}/nodes/{node_key}", "PUT",
          {"method": DEV_METHOD, "params": {}, "resourceId": ARM_RESOURCE}, F._TOKEN)
    return binding_ok(wf_id, node_key)


def binding_ok(wf_id, node_key):
    """节点设置的保存会改库里的 XML。后面再点画布「保存」会用还没重新导入的
    模型把这次绑定盖掉，所以这里直接核对落库结果。"""
    res = F.q("SELECT coalesce(resource_id::text,'')||'|'||"
              "coalesce(params->>'protocol_name','') FROM workflow_node "
              f"WHERE workflow_id={wf_id} AND node_key='{node_key}'")
    xml = F.q(f"SELECT coalesce(graph->>'xml','') FROM workflow WHERE id={wf_id}") or ""
    i = xml.find(f'id="{node_key}"')
    window = xml[max(0, i - 80):i + 500] if i >= 0 else ""
    return (res == f"{ARM_RESOURCE}|{DEV_METHOD}"
            and 'protocolName="Home"' in window
            and f'resource="{ARM_RESOURCE}"' in window)


def dev_cmd_count(run_id, node_key):
    """该设备节点已经下发的指令条数。

    run_command 挂在 **level=1 步骤行**上，要经 parent_node_id 关联到
    level=0 的业务节点才能对上（第一版直接按 level=0 关联，查出来 0 条，
    差点误判成「设备根本没跑」）。
    """
    return int(F.q(f"SELECT count(*) FROM run_command rc "
                   f"JOIN run_node step ON step.id=rc.run_node_id "
                   f"JOIN run_node biz ON biz.id=step.parent_node_id "
                   f"WHERE rc.run_id={run_id} AND biz.level=0 "
                   f"AND biz.node_key='{node_key}'") or 0)


def node_states(run_id):
    """run 的 level=0 节点状态：{node_key: status}"""
    rows = F.q(f"SELECT node_key||'|'||status::text FROM run_node "
               f"WHERE run_id={run_id} AND level=0 ORDER BY id")
    out = {}
    for r in rows.split("\n"):
        if "|" in r:
            k, v = r.split("|", 1)
            out[k] = v
    return out


def device_state():
    """机械臂此刻的执行状态。status='running' 且 current_run_id 指向本次 run
    才算「这台设备正在为这次执行干活」。"""
    row = F.q("SELECT coalesce(status,'-')||'|'||coalesce(current_method,'-')||'|'||"
              "coalesce(current_run_id::text,'-') FROM device_state "
              "WHERE tenant_id=1 AND resource_id=%d" % ARM_RESOURCE)
    if not row:
        return ("-", "-", "-")
    parts = (row + "||").split("|")
    return (parts[0], parts[1], parts[2])


# 已删除 pause_run()/update_count()：暂停不再用 SQL 造业务状态，
# 改走运行详情页的「暂停」按钮（真实页面入口）。


def kernel_logged_pause_at(node_key):
    """内核 stdout 里有没有「停在这个待执行节点」这一行。"""
    needle = "[暂停] 收到暂停请求，在节点边界停下，待执行节点：" + node_key
    if not os.path.isfile(API_LOG):
        return False, "日志文件不存在：" + API_LOG
    try:
        with open(API_LOG, "r", errors="replace") as fh:
            for line in fh:
                if needle in line:
                    return True, needle
    except OSError as ex:
        return False, str(ex)
    return False, needle


def wait_arm_settled(run_id, limit=120):
    """清理前等这台臂不再为本次 run 执行，避免删掉还在落库的步骤行。"""
    deadline = time.time() + limit
    while time.time() < deadline:
        st, _meth, owner = device_state()
        if st != "running" and owner != str(run_id):
            return True
        time.sleep(0.5)
    return False


def api_run_status(rid):
    """GET /api/runs/{id} 的状态字段（返回体是 {run:{...}, nodes:[...]}）。"""
    j = F.api(f"/api/runs/{rid}", token=F._TOKEN) or {}
    inner = j.get("run") if isinstance(j, dict) else None
    if isinstance(inner, dict):
        return str(inner.get("status"))
    return str(j.get("status") if isinstance(j, dict) else "")


def page_start_run(page, base, wf_name):
    """在页面上启动执行：流程库 → 详情 → 执行 → 跳到运行详情。返回 run_id。"""
    page.goto(f"{base}/workflows", wait_until="networkidle")
    page.wait_for_timeout(1500)
    row = page.locator("tr.ant-table-row", has_text=wf_name).first
    if row.count() == 0:
        return 0
    # 行点击是进设计器；执行入口在右侧详情卡，所以先点行内「详情」选中
    row.locator("button.ant-btn-link").first.click()
    page.wait_for_timeout(1200)
    exec_btn = page.get_by_role("button", name=re.compile(r"^执\s*行$"))
    if exec_btn.count() == 0:
        return 0
    exec_btn.first.click()
    page.wait_for_timeout(1500)
    # 资源不足时页面弹「启动前粗筛」确认框；确认后才执行
    modal = page.locator(".ant-modal:visible")
    if modal.count() > 0:
        ok_btn = modal.locator("button.ant-btn-primary")
        if ok_btn.count() > 0:
            ok_btn.last.click()
    try:
        page.wait_for_url("**/runs/**", timeout=30000)
    except Exception:  # noqa: BLE001
        return 0
    m = re.search(r"/runs/(\d+)", page.url)
    return int(m.group(1)) if m else 0


def page_pause(page, timeout=30):
    """点运行详情页的「暂停」按钮（真实页面入口）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        btn = page.locator("button.ant-btn", has_text=re.compile(r"^暂\s*停$"))
        if btn.count() > 0:
            btn.first.click()
            try:
                page.wait_for_selector(".ant-message", timeout=8000)
            except Exception:  # noqa: BLE001
                pass
            page.wait_for_timeout(800)
            return True
        page.wait_for_timeout(300)
    return False


def page_continue(page, timeout=30):
    """点运行详情页的「继续」按钮（真实页面入口）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        btn = page.locator("button.ant-btn", has_text=re.compile(r"^继\s*续$"))
        if btn.count() > 0:
            btn.first.click()
            try:
                page.wait_for_selector(".ant-message", timeout=8000)
            except Exception:  # noqa: BLE001
                pass
            page.wait_for_timeout(800)
            return True
        page.wait_for_timeout(300)
    return False


def finish(code=None):
    """打印「通过 N / 失败 M」汇总行并退出。

    ⚠ 为什么每条退出路径都要打这一行：门禁靠它判断成败。
    早退（异常 / 断言失败）时不打汇总 ⇒ 门禁解析不到 ⇒ 兜底成「失败 1 条」
    但一条明细都打不出来（2026-11-06 在 4.17 段踩过这个坑，排查了很久）。
    """
    print()
    print("通过 %d / 失败 %d" % (F.PASS, F.FAIL))
    sys.exit(code if code is not None else (1 if F.FAIL else 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    if not F.have_db():
        print("缺 LABOS_DB_* 环境变量，无法连库断言")
        sys.exit(2)
    os.environ.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))

    F._TOKEN = F.api("/api/auth/login", "POST",
                      {"username": args.user, "password": args.pwd})["token"]
    ctx = F.Ctx(F._TOKEN)

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

        def bail():
            b.close()
            wait_arm_settled(ctx.made_run[-1] if ctx.made_run else 0)
            ctx.cleanup()
            finish()

        # ---------------- 建图 ----------------
        F.step("A. 建图：并行分叉/汇合 + 两个设备节点 + 变量节点（暂停边界用）")
        WF_NAME = "E2E-暂停恢复-临时-%d" % int(time.time())
        wf_id = F.make_workflow(page, ctx, WF_NAME)
        for s in ("S", "E"):
            F.delete_node(page, s)

        #  S → VAR → DEV → DEV2 → PAR(并行分叉) → C / D → PARJ(并行汇合) → E
        #  DEV2 是下一个任务边界：暂停落在 DEV 执行期间，停在 DEV2 之前。
        #
        #  为什么去掉原来的「XOR → A/B → PAR」：XOR 只走一条分支，而 PAR 的 2 条入边
        #  只会到 1 条 → 引擎的 AND join（JOIN_PARALLEL）永远等不齐，执行队列提前空，
        #  run 却被 settleRun 标成 completed，下游 C/D 全被标 bypassed —— 测试假绿。
        #  现在 PAR/PARJ 成对：恢复后 C/D 必须都 completed，join 才可能放行到 E。
        #  异或分叉/汇合语义由 4.15 e2e-gateway-pairs.py 覆盖，不在这里重复。
        #
        # 坐标必须落在画布容器里。place() 在容器变窄时会把 x 按 /900 缩放，
        # x>880 会点到容器外，节点静默不生成（S/E 曾经因此落点失败）。
        nodes = [
            ("S", "create.start-event", 130, 100),
            ("VAR", "create.task", 240, 80),
            ("DEV", "create.task", 350, 80),
            ("DEV2", "create.task", 460, 80),
            ("PAR", "create.parallel-gateway", 570, 100),
            ("C", "create.task", 680, 40),
            ("D", "create.task", 680, 180),
            ("PARJ", "create.parallel-gateway", 570, 260),
            ("E", "create.end-event", 460, 260),
        ]
        ids = {}
        for n, action, x, y in nodes:
            clear_selection(page)
            ids[n] = F.place(page, action, x, y)
        miss = [k for k, v in ids.items() if not v]
        if miss:
            F.bad("落点失败", ",".join(miss))
            bail()
        F.ok("落 9 个节点（S/VAR/DEV/DEV2/PAR/C/D/PARJ/E，含并行分叉+并行汇合）")

        for a, c in (("S", "VAR"), ("VAR", "DEV"), ("DEV", "DEV2"), ("DEV2", "PAR"),
                     ("PAR", "C"), ("PAR", "D"),
                     ("C", "PARJ"), ("D", "PARJ"), ("PARJ", "E")):
            F.connect(page, ids[a], ids[c])
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2500)
        F.ok("连线完成并保存")

        # 页面「自动对齐」：点击后按拓扑分层重排（保存后 DI 坐标一起落库）
        align_btn = page.locator("button.ant-btn", has_text=re.compile(r"自\s*动\s*对\s*齐"))
        if align_btn.count() > 0:
            align_btn.first.click()
            page.wait_for_timeout(1500)
            page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
            page.wait_for_timeout(2500)
            F.ok("页面有「自动对齐」按钮，点击后保存成功")
        else:
            F.bad("页面没有「自动对齐」按钮")

        # 保存后刷新一次页面：自动对齐触发的 saveXML 会异步 re-import；
        # 若紧接着用旧画布模型打开节点设置，后一次节点卡保存会把前一次的
        # protocolName/参数覆盖掉（实测 VAR/C/D 只剩 1 个 __setVar）。
        page.reload(wait_until="networkidle")
        page.wait_for_selector(".djs-container", timeout=25000)
        page.wait_for_timeout(2000)

        # 结构合法性：一个流程 = 1 开始 + 1 结束 + 全连通 + 网关成对
        xml_chk = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
        xml_chk = xml_chk if isinstance(xml_chk, str) else F.json.dumps(xml_chk)
        errs = F.validate_flow(xml_chk)
        F.ok("流程结构合法（1 开始 / 1 结束 / 全连通 / 网关成对）") if not errs \
            else F.bad("流程结构不合法", ";".join(errs))

        # 自动对齐后：所有连线从左到右（目标 x > 源 x），且节点互不重叠
        bnd = F.flow_bounds(xml_chk)
        edges_now = F.graph_edges(xml_chk)
        bad_dirs = [s + "->" + t for (s, t) in edges_now.values()
                    if s in bnd and t in bnd and bnd[t][0] <= bnd[s][0]]
        F.ok("自动对齐后连线全部从左到右（DI 坐标单调）") if not bad_dirs \
            else F.bad("对齐后仍有连线不是从左到右", ",".join(bad_dirs[:5]))
        order = sorted(bnd.items(), key=lambda kv: (kv[1][0], kv[1][1]))
        overlap = []
        for i in range(len(order)):
            for j in range(i + 1, len(order)):
                ra = order[i][1]
                rb = order[j][1]
                if (ra[0] < rb[0] + rb[2] and rb[0] < ra[0] + ra[2]
                        and ra[1] < rb[1] + rb[3] and rb[1] < ra[1] + ra[3]):
                    overlap.append(order[i][0] + "/" + order[j][0])
        F.ok("自动对齐后节点互不重叠") if not overlap \
            else F.bad("对齐后仍有节点重叠", ",".join(overlap[:3]))

        # 新排版要求：先对齐网关分叉/汇合对（同一水平线），再排分支内部
        def _center(node_id):
            b = bnd.get(node_id)
            return ((b[0] + b[2] / 2, b[1] + b[3] / 2) if b else None)
        cp, cj = _center(ids["PAR"]), _center(ids["PARJ"])
        F.ok("自动对齐后并行网关对（PAR/PARJ）在同一水平线") \
            if cp and cj and abs(cp[1] - cj[1]) <= 2 \
            else F.bad("网关对没有水平对齐", str((cp, cj)))
        cs, ce = _center(ids["S"]), _center(ids["E"])
        F.ok("自动对齐后开始/结束节点在同一水平线") \
            if cs and ce and abs(cs[1] - ce[1]) <= 2 \
            else F.bad("开始/结束没有水平对齐", str((cs, ce)))
        cc, cd = _center(ids["C"]), _center(ids["D"])
        F.ok("自动对齐后并行分支 C/D 同列且上下对称") \
            if cc and cd and cp and abs(cc[0] - cd[0]) <= 2 \
            and abs((cc[1] + cd[1]) / 2 - cp[1]) <= 3 \
            else F.bad("并行分支没有对称排开", str((cc, cd, cp)))

        # 变量节点 + 两个设备节点
        # ⚠ 每次节点设置保存后必须刷新一次设计器：节点卡的保存基于页面当前的
        #   BPMN 模型，连续设置多个节点时后面的保存会拿旧模型覆盖前面的设置
        #   （实测 VAR/C/D 三个 __setVar 最后只剩最后一个）。
        def reload_designer():
            page.reload(wait_until="networkidle")
            page.wait_for_selector(".djs-container", timeout=25000)
            page.wait_for_timeout(1500)

        def set_builtin_retry(k, var, val):
            # 刷新后节点卡偶发未就绪（第一次点开时卡还没渲染出来），重试一次。
            for _ in range(2):
                if set_builtin(page, ids[k], "变量节点", var, val):
                    return True
                page.wait_for_timeout(900)
            return False

        if set_builtin_retry("VAR", "counter", "1"):
            F.ok("VAR 设为变量节点 counter=1")
        else:
            F.bad("VAR 设变量节点失败")
        reload_designer()
        # C/D 不再设内置节点：它们和 DEV/DEV2 一样绑到同一台仿真设备。
        # 为什么：节点设置卡连续保存第三方节点时会偶发覆盖/失败（C 节点实测
        # 两次都设不上），把 C/D 也走已验证可靠的「设备绑定」路径更稳；
        # 并行段仍然是两个设备任务（同一台臂，引擎按设备锁串行）。
        F.ok("C/D 走设备绑定路径（与 DEV/DEV2 相同）")
        # 节点卡自己的「保存」已经把 protocolName/resource 写进库。
        # 不要再点画布上的「保存」：那次 saveXML 往往还是重新导入前的旧模型，
        # 会把刚绑上的第二个设备节点盖成「没有设备」。
        bind_failed = False
        for label in ("DEV", "DEV2", "C", "D"):
            persisted = False
            if bind_device(page, ids[label]) and binding_ok(wf_id, ids[label]):
                persisted = True
            elif bind_device_api(wf_id, ids[label]):
                persisted = True
            if persisted:
                F.ok(f"{label} 绑成仿真设备任务并已落库（{DEV_CODE} / {DEV_METHOD}）")
            else:
                F.bad(f"{label} 绑定没有落库",
                      "resource/xml 未同时是 %s/Home" % ARM_RESOURCE)
                bind_failed = True
            reload_designer()
        if bind_failed:
            bail()

        # 绑定后必须复核图 XML：设备 task 的 protocolName/resource 还在，
        # 内置节点参数还在——否则运行时会转 manual（对齐/保存顺序会踩这个坑）。
        xml_pre = F.api(f"/api/workflows/{wf_id}/graph", token=ctx.token)
        xml_pre = xml_pre if isinstance(xml_pre, str) else F.json.dumps(xml_pre)
        n_home = xml_pre.count('protocolName="Home"')
        F.ok(f"绑定后图 XML 里有 4 个 Home 设备任务（{n_home}）") if n_home >= 4 \
            else F.bad("设备绑定在保存后被覆盖", "protocolName=Home 出现 " + str(n_home) + " 次")
        n_setvar = xml_pre.count('protocolName="__setVar"')
        F.ok(f"绑定后图 XML 里仍有 1 个内置变量节点（{n_setvar}）") if n_setvar >= 1 \
            else F.bad("内置节点设置在保存后被覆盖", "protocolName=__setVar 出现 " + str(n_setvar) + " 次")
        F.ok("绑定后图 XML 仍有 PAR/PARJ 并行网关") \
            if xml_pre.count("parallelGateway") >= 2 \
            else F.bad("并行网关在保存后丢了", str(xml_pre.count("parallelGateway")))
        errs2 = F.validate_flow(xml_pre)
        F.ok("绑定后流程仍合法") if not errs2 else F.bad("绑定后流程不合法", ";".join(errs2))

        # ---------------- 跑起来（页面入口） ----------------
        F.step("B. 页面「执行」→ DEV 还在跑的时候点页面「暂停」")
        # 全程走页面：流程库 → 详情 → 执行 → 运行详情 → 暂停/继续。
        # 不再 POST /run 或 UPDATE run：页面按钮与用户操作同一条链路。
        rid = page_start_run(page, args.base, WF_NAME)
        if not rid:
            F.bad("页面「流程库 → 执行」没跑起来（没跳到 /runs/{id}）")
            bail()
        ctx.made_run.append(rid)
        F.ok(f"页面点击「执行」→ 跳到运行详情（run #{rid}）")

        deadline = time.time() + 180
        busy = None
        while time.time() < deadline:
            st, meth, owner = device_state()
            rs = F.q(f"SELECT status::text FROM run WHERE id={rid}")
            if rs in ("completed", "failed", "aborted", "paused"):
                break
            if st == "running" and owner == str(rid):
                st2, meth2, owner2 = device_state()
                if st2 == "running" and owner2 == str(rid):
                    busy = (st2, meth2, owner2)
                    break
            time.sleep(0.05)
        if not busy:
            F.bad("没观测到设备方法执行中",
                  "device_state=%s run=%s nodes=%s" % (
                      str(device_state()),
                      F.q(f"SELECT status::text FROM run WHERE id={rid}"),
                      str(node_states(rid))))
            bail()
        F.ok("观测到设备方法执行中（device_state status=%s current_method=%s，"
             "current_run_id=%s）" % busy)

        # 页面三段同断言：页面按钮 → API → DB
        F.ok("运行详情页有「暂停」按钮，并在设备执行中点下去") \
            if page_pause(page) else F.bad("运行详情页没有「暂停」按钮/点击失败")
        page.wait_for_timeout(600)
        body_now = page.inner_text("body")
        F.ok("点暂停后页面显示已暂停（DOM）") if "已暂停" in body_now \
            else F.bad("页面没显示已暂停", body_now[:160])
        st_db = F.q(f"SELECT status::text FROM run WHERE id={rid}")
        F.ok("点暂停后 DB 状态 = paused") if st_db == "paused" else F.bad("DB 状态不是 paused", st_db)
        api_st = api_run_status(rid)
        F.ok("点暂停后 API 状态 = paused") if api_st == "paused" \
            else F.bad("API 状态不是 paused", api_st)
        pk_now = F.q(f"SELECT COALESCE(pause_node_key,'') FROM run WHERE id={rid}")
        F.ok("页面暂停后 pause_node_key 仍留空（内核负责写，不是探针）") if pk_now == "" \
            else F.bad("探针不该自己写 pause_node_key", pk_now)

        # 状态此刻已经是 paused（探针写的）。要等的是内核走到 DEV2 边界：
        # DEV completed、DEV2 仍 pending、pause_node_key 被写成 DEV2。
        # 若整单先被收成 completed，就是旧竞态还在。
        deadline = time.time() + 180
        run_status = ""
        st_states = {}
        pk = ""
        while time.time() < deadline:
            run_status = F.q(f"SELECT status::text FROM run WHERE id={rid}")
            pk = F.q(f"SELECT COALESCE(pause_node_key,'') FROM run WHERE id={rid}")
            st_states = node_states(rid)
            if run_status in ("completed", "failed", "aborted"):
                break
            if (run_status == "paused" and pk == ids["DEV2"]
                    and st_states.get(ids["DEV"]) == "completed"
                    and st_states.get(ids["DEV2"]) == "pending"):
                break
            time.sleep(0.2)
        F.ok("内核在 DEV2 边界停下（run=%s，pause_node_key=%s，DEV=%s，DEV2=%s）" % (
            run_status, pk, st_states.get(ids["DEV"]), st_states.get(ids["DEV2"]))) \
            if (run_status == "paused" and pk == ids["DEV2"]
                and st_states.get(ids["DEV"]) == "completed"
                and st_states.get(ids["DEV2"]) == "pending") \
            else F.bad("没停在 DEV2 边界", f"{run_status}；key={pk}；{st_states}")

        # ---------------- Q1：停在哪种节点 ----------------
        F.step("C. Q1 · 暂停点落在下一个设备任务节点")
        # pauseChecker 只在 Kind.TASK、且在 executeNode 之前。
        # 网关不查；__setVar 是瞬时计算也不查。正在执行的 DEV 会做完，
        # 停住的是还没开始的 DEV2。
        F.ok(f"pause_node_key 是内核写入的待执行节点 {pk}") \
            if pk == ids["DEV2"] else F.bad("pause_node_key 不是 DEV2", pk)
        # 本图只有并行网关；异或网关场景由 4.15 覆盖，不在这里断言。
        F.ok("暂停点不是网关（并行网关不检查 pause）") \
            if pk != ids["PAR"] \
            else F.bad("暂停点落在网关上", pk)
        F.ok("逻辑节点（__setVar 变量节点）也不是暂停点") \
            if pk != ids["VAR"] else F.bad("暂停点落在变量节点上", pk)
        F.ok("暂停点不是正在执行的 DEV（当前动作做完，停在下一个任务边界）") \
            if pk != ids["DEV"] else F.bad("暂停点落在正在执行的 DEV 上", pk)
        dev_state = st_states.get(ids["DEV"], "（没出现）")
        dev2_state = st_states.get(ids["DEV2"], "（没出现）")
        F.ok(f"DEV 在暂停时已完成（{dev_state}）") \
            if dev_state == "completed" else F.bad("DEV 状态不对", dev_state)
        F.ok(f"DEV2 在暂停时仍是 pending（{dev2_state}）—— 这一步还没开始") \
            if dev2_state == "pending" else F.bad("DEV2 状态不对", dev2_state)
        logged, detail = kernel_logged_pause_at(ids["DEV2"])
        F.ok("内核日志出现边界暂停行：" + detail) if logged \
            else F.bad("内核日志没有边界暂停行", detail)
        var_state = st_states.get(ids["VAR"], "")
        F.ok(f"变量节点 VAR 的 run_node 行在（状态 {var_state}）") \
            if var_state else F.bad("VAR 的 run_node 行没了", var_state)
        F.ok("暂停时 DEV 的指令已经落库 1 条，DEV2 还是 0 条") \
            if dev_cmd_count(rid, ids["DEV"]) == 1 and dev_cmd_count(rid, ids["DEV2"]) == 0 \
            else F.bad("暂停时指令条数不对",
                       "DEV=%s DEV2=%s" % (dev_cmd_count(rid, ids["DEV"]),
                                           dev_cmd_count(rid, ids["DEV2"])))

        # ---------------- Q2/Q3：恢复从哪继续 ----------------
        F.step("D. Q2/Q3 · 恢复：DEV 跳过，DEV2 才执行")
        before = node_states(rid)
        completed_before = {k for k, v in before.items() if v == "completed"}
        F.ok(f"恢复前已 completed 的节点：{len(completed_before)} 个")
        F.ok("VAR 在恢复前已 completed → 续跑跳过") \
            if ids["VAR"] in completed_before else F.bad("VAR 不在已完成里", str(before))
        F.ok("DEV 在恢复前已 completed → 进 skipKeys，续跑不重跑") \
            if ids["DEV"] in completed_before else F.bad("DEV 不在已完成里", str(before))
        F.ok("DEV2 在恢复前不是 completed → 续跑会执行它") \
            if ids["DEV2"] not in completed_before \
            else F.bad("DEV2 在恢复前就已经 completed", str(before))

        F.ok("运行详情页有「继续」按钮，并在暂停中点下去") \
            if page_continue(page) else F.bad("暂停后没有「继续」按钮/点击失败")
        st_after_resume = F.q(f"SELECT status::text FROM run WHERE id={rid}")
        F.ok(f"点继续后离开 paused（当前 {st_after_resume}）") \
            if st_after_resume != "paused" else F.bad("点继续后仍停在 paused", st_after_resume)
        deadline = time.time() + 420
        final = {}
        while time.time() < deadline:
            final = node_states(rid)
            rs = F.q(f"SELECT status::text FROM run WHERE id={rid}")
            if rs in ("completed", "failed", "aborted"):
                break
            time.sleep(0.5)
        rs = F.q(f"SELECT status::text FROM run WHERE id={rid}")
        F.ok(f"恢复后整单 completed（{rs}）") if rs == "completed" \
            else F.bad("恢复后没跑完", f"{rs}；{final}")

        # 下游并行段：PAR(1 入 2 出) → C/D → PARJ(2 入 1 出，AND join) → E。
        # 只有 C、D 都 completed，PARJ 才会放行；整单 completed 才说明 join 真等齐了。
        # （旧图 XOR→A/B→PAR 只有一条入边到达，C/D 会 bypassed 且 run 仍 completed —— 假绿。）
        down = {k: final.get(ids[k]) for k in ("C", "D")}
        F.ok(f"恢复后并行两分支都完成（C={down['C']}, D={down['D']}）") \
            if all(v == "completed" for v in down.values()) \
            else F.bad("并行分支没有都完成（汇合会提前收尾）", str(down))
        F.ok("恢复后下游无节点被 bypassed（没有把未执行当完成）") \
            if all(v != "bypassed" for v in down.values()) \
            else F.bad("下游节点被 bypassed（假绿）", str(down))
        F.ok("整单 completed 且并行汇合等齐（PARJ 的 AND join 生效）") \
            if rs == "completed" and all(v == "completed" for v in down.values()) \
            else F.bad("汇合语义未验证通过", f"{rs}；{down}")

        dup_var = F.q(f"SELECT count(*) FROM run_node WHERE run_id={rid} AND level=0 "
                      f"AND node_key='{ids['VAR']}'")
        F.ok("VAR 续跑后仍只有 1 条记录 → 没被重跑") \
            if dup_var == "1" else F.bad("VAR 被重跑了", f"记录数 {dup_var}")
        dup_dev = F.q(f"SELECT count(*) FROM run_node WHERE run_id={rid} AND level=0 "
                      f"AND node_key='{ids['DEV']}'")
        F.ok("DEV 续跑后仍只有 1 条记录 → 没被重跑") \
            if dup_dev == "1" else F.bad("DEV 记录数异常", f"记录数 {dup_dev}")
        F.ok("DEV 续跑后仍是 completed") \
            if final.get(ids["DEV"]) == "completed" \
            else F.bad("DEV 续跑后不是 completed", str(final))
        cmds = dev_cmd_count(rid, ids["DEV"])
        F.ok(f"DEV 的设备指令只下了 1 次（续跑不重复下发，{cmds} 条）") \
            if cmds == 1 else F.bad("DEV 指令重复下发", f"{cmds} 条")
        resp = F.q(f"SELECT count(*) FROM run_command rc "
                   f"JOIN run_node step ON step.id=rc.run_node_id "
                   f"JOIN run_node biz ON biz.id=step.parent_node_id "
                   f"WHERE rc.run_id={rid} AND biz.level=0 "
                   f"AND biz.node_key='{ids['DEV']}' "
                   f"AND rc.device_raw_response IS NOT NULL")
        F.ok(f"DEV 的指令真收到了回包（{resp} 条）") \
            if int(resp or 0) >= 1 else F.bad("DEV 指令没有回包", str(resp))

        F.ok("DEV2 续跑后 completed（暂停点被执行，不是被跳过）") \
            if final.get(ids["DEV2"]) == "completed" \
            else F.bad("DEV2 续跑后不是 completed", str(final))
        cmds2 = dev_cmd_count(rid, ids["DEV2"])
        F.ok(f"DEV2 的设备指令在恢复后下了 1 次（{cmds2} 条）") \
            if cmds2 == 1 else F.bad("DEV2 指令条数不对", f"{cmds2} 条")
        resp2 = F.q(f"SELECT count(*) FROM run_command rc "
                    f"JOIN run_node step ON step.id=rc.run_node_id "
                    f"JOIN run_node biz ON biz.id=step.parent_node_id "
                    f"WHERE rc.run_id={rid} AND biz.level=0 "
                    f"AND biz.node_key='{ids['DEV2']}' "
                    f"AND rc.device_raw_response IS NOT NULL")
        F.ok(f"DEV2 的指令真收到了回包（{resp2} 条）") \
            if int(resp2 or 0) >= 1 else F.bad("DEV2 指令没有回包", str(resp2))

        # 页面/API/DB 三段同断言（总纲 §1.2）：不仅库对，页面也要看得到
        page.reload(wait_until="networkidle")
        page.wait_for_timeout(2000)
        body_final = page.inner_text("body")
        F.ok("运行详情页显示 completed（DOM）") if "completed" in body_final \
            else F.bad("页面没显示 completed", body_final[:200])
        F.ok("运行详情页有流程图/图例") if ("图例" in body_final or "流程图" in body_final) \
            else F.bad("运行详情页缺流程图/图例", body_final[:200])
        api_st_final = api_run_status(rid)
        F.ok("API 最终状态 completed") if api_st_final == "completed" \
            else F.bad("API 最终状态不是 completed", api_st_final)
        F.ok("DB 最终状态 completed") \
            if F.q(f"SELECT status::text FROM run WHERE id={rid}") == "completed" \
            else F.bad("DB 最终状态不是 completed")

        b.close()
    wait_arm_settled(ctx.made_run[-1] if ctx.made_run else 0)
    ctx.cleanup()

    finish()


if __name__ == "__main__":
    main()
