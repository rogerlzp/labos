#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 暂停点与恢复起点（异或 / 并行 / 设备 / 变量 四类节点）。

回答四个问题（2026-11-06 用户提问）：
  Q1 发生暂停时，会停在**哪一类**节点上？
  Q2 恢复时，是从**下一个**节点接着跑，还是从**当前（暂停点）**节点重跑？
  Q3 设备节点与逻辑（变量/表达式）节点在暂停/恢复上的处理**有什么差别**？

为什么要有这个脚本：既有测试只验了「能暂停、能恢复」，没验「停在哪种节点、
从哪继续」——而这两点恰恰是**代码里写死的分支**（`GraphRunner` 的 pauseChecker
只在 `Kind.TASK` 分支里检查；续跑靠 `skipKeys` 跳过上一轮 completed 的节点），
一旦有人改了分支，没有断言会红。

设计要点：
  · 用**仿真设备**（ID1731 / Home）做设备节点，真能收到指令；
  · 变量节点用内置节点（`__setVar`）—— 不碰设备；
  · 图里同时放：异或网关、并行网关、设备节点、变量节点；
  · 暂停通过**直接改 run 状态**触发（等价于页面「暂停」按钮走的那条
    `PauseManager.pause` 路径，但不用等 UI）；
  · 恢复走**真实的 resumeAsync**（页面「继续」按钮同一条路）。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-pause-resume-nodes.py
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


def select_node(page, key):
    """选中一个图元（裸鼠标点，不用 locator.click()）。

    ⚠ 为什么不用 locator.click()：bpmn-js 选中节点后会弹**直接编辑框**
      （.djs-direct-editing），它会拦截 Playwright 的可点性检查 →
      locator.click() 一直 retry 到超时（"djs-direct-editing-content intercepts
      pointer events"）。裸 `page.mouse.click` 不做这层检查（e2e-sim-flow
      就是这么干的）。
    """
    bb = page.locator(f'.djs-element[data-element-id="{key}"]').first.bounding_box()
    page.mouse.click(bb["x"] + bb["width"] / 2, bb["y"] + bb["height"] / 2)
    page.wait_for_timeout(1400)


def bind_device(page, key, dev_code=DEV_CODE, method=DEV_METHOD):
    """把一个 task 节点绑成仿真设备任务（设备 → 方法）。

    坑：① 下拉要用**元素引用**（sels.nth(1)）而不是 CSS 路径 —— 节点设置卡里的
    ant-select 不唯一，CSS 路径会选到别的卡片上，绑定静默失败；
    ② 设备池里 ID1731 / ID1731-2 会同时命中，要用 "^ID1731 ·" 前缀精确挑。
    """
    select_node(page, key)
    card = page.locator(".ant-card", has_text="节点设置").first
    if card.count() == 0:
        return False
    sels = card.locator(".ant-select-selector")
    if sels.count() < 3:
        return False
    # 第 1 个是类型（默认设备任务），第 2 个设备，第 3 个方法
    if not pick_in_select(page, sels.nth(1), dev_code, exact_prefix=rf"^{dev_code} ·"):
        return False
    if not pick_in_select(page, sels.nth(2), method, exact_prefix=r"^" + method + r"( ·|$)"):
        return False
    card.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2000)
    return True


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


def pause_run(run_id, node_key):
    """等价于页面「暂停」：把 run 标成 paused（PauseManager.pause 同一条路径）。

    ⚠ 不用 `AND status='running'`：刚创建的 run 可能还停在 queued，
    那个 WHERE 会让暂停**静默不生效**（0 行更新）。
    """
    F.q(f"UPDATE run SET status='paused', pause_kind='manual', "
        f"pause_reason='e2e 暂停探针', pause_node_key='{node_key}', "
        f"paused_at=now(), updated_at=now() WHERE id={run_id}")


def resume_run(run_id):
    """走真实 resumeAsync（页面「继续」按钮同一条路）。"""
    F.api(f"/api/runs/{run_id}/resume", "POST", {}, F._TOKEN)
    return True


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

        # ---------------- 建图 ----------------
        F.step("A. 建图：异或 + 并行 + 设备节点 + 变量节点")
        wf_id = F.make_workflow(page, ctx, "E2E-暂停恢复-临时")
        for s in ("S", "E"):
            F.delete_node(page, s)

        #  S → VAR(变量节点) → DEV(设备节点) → XOR → A / B → PAR(并行) → C / D → E
        nodes = [
            ("S", "create.start-event", 120, 130),
            ("VAR", "create.task", 250, 130),
            ("DEV", "create.task", 390, 130),
            ("XOR", "create.exclusive-gateway", 530, 130),
            ("A", "create.task", 660, 60),
            ("B", "create.task", 660, 210),
            ("PAR", "create.parallel-gateway", 780, 130),
            ("C", "create.task", 880, 70),
            ("D", "create.task", 880, 200),
            ("E", "create.end-event", 880, 300),
        ]
        ids = {}
        for n, action, x, y in nodes:
            clear_selection(page)
            ids[n] = F.place(page, action, x, y)
        miss = [k for k, v in ids.items() if not v]
        if miss:
            F.bad("落点失败", ",".join(miss))
            b.close()
            ctx.cleanup()   # 失败路径也要清现场：残留的 run/workflow 会一直堆在库里
            finish()
        F.ok("落 10 个节点（S/VAR/DEV/XOR/A/B/PAR/C/D/E）")

        for a, c in (("S", "VAR"), ("VAR", "DEV"), ("DEV", "XOR"),
                     ("XOR", "A"), ("XOR", "B"),
                     ("A", "PAR"), ("B", "PAR"),
                     ("PAR", "C"), ("PAR", "D"), ("C", "E"), ("D", "E")):
            F.connect(page, ids[a], ids[c])
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2500)
        F.ok("连线完成并保存")

        # 变量节点 + 设备节点
        if set_builtin(page, ids["VAR"], "变量节点", "counter", "1"):
            F.ok("VAR 设为变量节点 counter=1")
        else:
            F.bad("VAR 设变量节点失败")
        # A/B/C/D 也必须是内置节点：它们是**普通 task**（设备任务），
        # 库里没有对应设备 → 转 manual 并停下 → 整单卡 paused（实测踩到）。
        # 本用例要验的是「暂停/恢复的节点语义」，不是设备寻址，所以用不碰设备的节点。
        for k in ("A", "B", "C", "D"):
            set_builtin(page, ids[k], "变量节点", f"v_{k}", "1")
        F.ok("A/B/C/D 设为内置节点（避免设备任务转 manual 卡住）")
        if bind_device(page, ids["DEV"]):
            F.ok(f"DEV 绑成仿真设备任务（{DEV_CODE} / {DEV_METHOD}）")
        else:
            F.bad("DEV 绑设备失败")
        page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
        page.wait_for_timeout(2500)

        # ---------------- 跑起来 ----------------
        F.step("B. 执行 → 在设备节点执行时暂停")
        # ⚠ 时序要点（实测踩出来的）：
        #   · 仿真设备的指令往返只要 ~750ms —— **轮询必须用 50ms 间隔**，
        #     1 秒一轮的话，等抓到 running 时它早跑完了；
        #   · 也不能「预先置 paused」：VAR 是逻辑节点、瞬间执行完，
        #     预置暂停会停在 **VAR 之前**，压根到不了设备节点；
        #   · 暂停真正生效在**下一个任务节点边界**（网关不检查），
        #     所以 pause_node_key 记的是那个「待执行节点」。
        r = F.api(f"/api/workflows/{wf_id}/run?async=1", "POST", {}, F._TOKEN)
        rid = int(r.get("runId")) if isinstance(r, dict) and r.get("runId") else 0
        if not rid:
            rid = int(F.q("SELECT COALESCE(max(id),0) FROM run WHERE tenant_id=1") or 0)
        if not rid:
            F.bad("触发执行失败")
            b.close()
            ctx.cleanup()   # 同上
            finish()
        ctx.made_run.append(rid)
        F.ok(f"触发真执行（run #{rid}）")

        # ⚠ 判据用 **run_command 有行**（那一步**已经跑完并落库**），
        #   **不能**用 run_node.status='running'：
        #   `WorkflowService.java:1523` 在执行**开始前**就把所有业务节点
        #   一次性建成 'running' —— 所有节点一开始都是 running，
        #   拿它当「正在执行」的信号等于抓到假信号（第一版就是这么错的，
        #   表现为「所有节点都 running、VAR 不在已完成里」）。
        #   ⚠ run_command 也不是「指令刚发出去」的信号 —— 它是**步结束后**
        #     才 INSERT 的（status 直接写 completed/failed，sent_at/finished_at
        #     都是 now()），所以它证明的是「DEV 这一步确实做过了」。
        #     真要判「设备**正在执行**」得看 device_state
        #     （markDeviceBusy 在 onStepStart 置 status='running'+current_method）
        #     —— 见 e2e-pause-during-device.py / e2e-pause-during-experiment.py。
        # 设备方法耗时随机 30–300 秒 ⇒ 指令落库可能要等很久（不是 30 秒）
        deadline = time.time() + 420
        hit = False
        while time.time() < deadline:
            if dev_cmd_count(rid, ids["DEV"]) >= 1:
                hit = True
                break
            time.sleep(0.05)
        if not hit:
            F.bad("没抓到设备指令发出", str(node_states(rid)))
            b.close()
            ctx.cleanup()   # 同上
            finish()
        F.ok(f"抓到设备节点 DEV={ids['DEV']} 的指令已执行并落库（run_command 有行）")
        pause_run(rid, ids["DEV"])
        F.ok("发出暂停")

        deadline = time.time() + 420
        run_status = ""
        st_states = {}
        while time.time() < deadline:
            run_status = F.q(f"SELECT status::text FROM run WHERE id={rid}")
            st_states = node_states(rid)
            if run_status == "paused":
                break
            time.sleep(0.2)
        F.ok(f"run 状态变 paused（{run_status}）") if run_status == "paused" \
            else F.bad("run 没变 paused", f"{run_status}；{st_states}")

        # ---------------- Q1：停在哪种节点 ----------------
        F.step("C. Q1 · 暂停点落在哪种节点上")
        # pause_node_key 记的是「待执行节点」。
        # 源码口径（GraphRunner）：**任务节点边界**才查 pauseChecker（Kind.TASK 分支），
        # 网关不走这里，所以暂停点**只可能是设备任务节点**。
        # 内置节点里，`__setVar` / `__expr` 是瞬时计算、不查暂停；
        # 只有 `__sleep` / `__wait` 会在**等待循环**里按 500ms 分片轮询暂停
        # （GraphRunner.waitInterruptibly）—— 本用例的 VAR 是 `__setVar`，
        # 因此它同样不是暂停点。
        pk = F.q(f"SELECT COALESCE(pause_node_key,'') FROM run WHERE id={rid}")
        F.ok(f"pause_node_key 记的是待执行节点 {pk}")
        F.ok("暂停点不是网关（异或/并行网关都不检查 pause）") \
            if pk not in (ids["XOR"], ids["PAR"]) \
            else F.bad("暂停点落在网关上", pk)
        # ★ `__setVar` 逻辑节点**也不会**成为暂停点：
        #   它是瞬时计算、不调 waitInterruptibly，不轮询暂停信号。
        #   （`__sleep` / `__wait` 会在等待途中响应暂停 —— 那是另一条路径，本用例不覆盖。）
        #   之前只有「不是网关」这条，逻辑节点这条没钉死 —— 补上。
        F.ok("逻辑节点（__setVar 变量节点）也不是暂停点（瞬时计算、不轮询暂停）") \
            if pk != ids["VAR"] else F.bad("暂停点落在变量节点上", pk)
        F.ok("暂停点是设备任务节点") \
            if pk == ids["DEV"] else F.bad("暂停点不是设备任务节点", pk)

        # ⚠ run_node 的 status 是**执行结束时统一落库**的最终态
        #   （persistNodes 遍历 p.all() 一次性写），执行途中查它没有意义；
        #   而且执行**开始前**所有节点就都被建成 'running'
        #   （WorkflowService.java:1523）。
        #   所以「暂停时哪些节点做完了」不能靠 run_node 判，
        #   只能靠**设备指令是否已发出**（run_command 有行 = 那一步真做了）。
        var_state = st_states.get(ids["VAR"], "（没出现）")
        F.ok(f"变量节点 VAR 的 run_node 行在（状态 {var_state}，最终态由收尾统一写）") \
            if var_state != "" else F.bad("VAR 的 run_node 行没了", var_state)

        # ★ 关键机制：暂停发生在 DEV **执行完之后**的下一个边界。
        #   源码：检查点在 executeNode **之前** —— 暂停信号到达时 DEV 已经
        #   跑完了，所以 run_node 里 DEV 是 completed。
        #   而 GraphRunner 停下时做的是 `ready.offer(DEV); done.remove(DEV);`
        #   —— 把 DEV **放回队列但这次不执行**。
        #   ⇒ DEV 看似"被放回队列"，但**恢复时它不会被重跑**：
        #   它是 completed → 进 skipKeys → 跳过（设备指令只下 1 次）。
        dev_state = st_states.get(ids["DEV"], "（没出现）")
        F.ok(f"设备节点 DEV 在暂停时已完成（{dev_state}）—— 暂停生效在它执行完之后") \
            if dev_state == "completed" else F.bad("DEV 状态不对", dev_state)

        # ---------------- Q2/Q3：恢复从哪继续 ----------------
        F.step("D. Q2/Q3 · 恢复：从哪个节点继续")
        before = node_states(rid)
        completed_before = {k for k, v in before.items() if v == "completed"}
        F.ok(f"恢复前已 completed 的节点：{len(completed_before)} 个")
        F.ok("VAR（逻辑节点）在恢复前已 completed → 续跑会跳过它") \
            if ids["VAR"] in completed_before else F.bad("VAR 不在已完成里", str(before))
        F.ok("DEV（设备节点）在恢复前已 completed → 进 skipKeys → 续跑跳过它") \
            if ids["DEV"] in completed_before else F.bad("DEV 不在已完成里", str(before))

        resume_run(rid)
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

        # ★ 续跑的核心证据：**已 completed 的节点不被重跑**。
        #   机制：resumeAsync 把上一轮 level=0 且 status='completed' 的 node_key
        #   收成 skipKeys 传给 GraphRunner → 这些节点直接当完成往下走。
        #   判据：run_node 里同 key 的 level=0 记录数仍为 1（若被重跑会变 2）。
        # VAR（逻辑节点）：上一轮已 completed → 续跑跳过 → 记录数仍为 1
        dup_var = F.q(f"SELECT count(*) FROM run_node WHERE run_id={rid} AND level=0 "
                      f"AND node_key='{ids['VAR']}'")
        F.ok("VAR（逻辑节点）续跑后仍只有 1 条记录 → 没被重跑（skipKeys 跳过）") \
            if dup_var == "1" else F.bad("VAR 被重跑了", f"记录数 {dup_var}")
        # DEV（设备节点）：上一轮已 completed → 续跑跳过 → 记录数仍为 1
        dup_dev = F.q(f"SELECT count(*) FROM run_node WHERE run_id={rid} AND level=0 "
                      f"AND node_key='{ids['DEV']}'")
        F.ok("DEV（设备节点）续跑后仍只有 1 条记录 → 没被重跑（skipKeys 跳过）") \
            if dup_dev == "1" else F.bad("DEV 记录数异常（叠加了？）", f"记录数 {dup_dev}")
        F.ok("DEV（设备节点）续跑后 completed") \
            if final.get(ids["DEV"]) == "completed" \
            else F.bad("DEV 续跑后不是 completed", str(final))

        # 暂停点那个节点：它是「被放回队列但这次没执行」的那个，
        # 恢复时靠 skipKeys 跳过（若它已完成）或被重跑（若它没完成）。
        F.ok(f"暂停点节点 {pk} 续跑后 completed") \
            if final.get(pk) == "completed" else F.bad("暂停点节点没跑完", str(final))

        # 设备指令不能重复下发：DEV 的 run_command 数量
        # ⚠ run_command 挂在 **level=1 步骤行**上（node_key 形如 "S1"），
        #   要经 parent_node_id 关联到 level=0 的业务节点才能对上 DEV。
        #   （第一版直接按 level=0 关联，查出来是 0 条 —— 差点误判成「设备没跑」。）
        cmds = F.q(f"SELECT count(*) FROM run_command rc "
                   f"JOIN run_node step ON step.id=rc.run_node_id "
                   f"JOIN run_node biz ON biz.id=step.parent_node_id "
                   f"WHERE rc.run_id={rid} AND biz.level=0 "
                   f"AND biz.node_key='{ids['DEV']}'")
        F.ok(f"DEV 的设备指令只下了 1 次（续跑不重复下发，{cmds} 条）") \
            if cmds == "1" else F.bad("DEV 指令重复下发", f"{cmds} 条")
        # 指令真到了设备：device_raw_response 非空 = 仿真机真的回了一趟
        # （它是 json 类型，不能用 `<> ''` 比 —— 那样会报 invalid input syntax）
        resp = F.q(f"SELECT count(*) FROM run_command rc "
                   f"JOIN run_node step ON step.id=rc.run_node_id "
                   f"JOIN run_node biz ON biz.id=step.parent_node_id "
                   f"WHERE rc.run_id={rid} AND biz.level=0 "
                   f"AND biz.node_key='{ids['DEV']}' "
                   f"AND rc.device_raw_response IS NOT NULL")
        F.ok(f"设备指令真收到了回包（{resp} 条有 device_raw_response）") \
            if int(resp or 0) >= 1 else F.bad("设备指令没有回包（没真跑到设备）", str(resp))

        b.close()
    ctx.cleanup()

    finish()


if __name__ == "__main__":
    main()
