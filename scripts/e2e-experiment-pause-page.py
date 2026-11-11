#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""4.20 实验级暂停/恢复 · 页面版（Experiments 页「执行历史」行内 暂停/恢复 + 弹窗 scope/reason）。

和 4.17 的分工：
  · 4.17 走 API（Pause 契约 / Hold / 三层 status / 设备在途 / 时间线）；
  · 4.20 走**页面动作**：执行历史行内「暂停」→ 弹窗选作用域+填原因 → 确认；
    再点行内「恢复」；断言 DOM + API + DB 三段与 Hold 生命周期。
  · 执行本身仍由后台线程发起（同步执行会阻塞 HTTP，正好留出设备执行时间窗），
    页面只负责「暂停/恢复」这两个用户动作。

前置：数据库有 LABOS_DB_*、API/前端在跑、仿真机械臂在线（同 4.17）。
用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/e2e-experiment-pause-page.py --api http://127.0.0.1:18080 --base http://localhost:5173

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
import os
import re
import sys
import threading
import time
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_pause_exp", os.path.join(_HERE, "e2e-pause-during-experiment.py"))
PR = importlib.util.module_from_spec(_spec)
sys.modules["e2e_pause_exp"] = PR
_spec.loader.exec_module(PR)
F = PR.F

ARM_RESOURCE = PR.ARM_RESOURCE


def build_two_arm_workflow(token, api_base):
    r = F.api("/api/workflows", "POST",
              {"name": "E2E-实验暂停页面-%d" % int(time.time()),
               "category": "choreography"}, token)
    wf_id = int(r["id"])
    PR.MADE["wf"] = wf_id
    xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="defs_{wf_id}" targetNamespace="http://labos.local/bpmn">
  <bpmn:process id="p_{wf_id}" name="E2E-实验暂停页面" isExecutable="true">
    <bpmn:startEvent id="S"><bpmn:outgoing>flow1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="ARM" name="机械臂搬运1" protocolName="Home" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow1</bpmn:incoming>
      <bpmn:outgoing>flow2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:task id="ARM2" name="机械臂搬运2" protocolName="Home" resource="{ARM_RESOURCE}">
      <bpmn:incoming>flow2</bpmn:incoming>
      <bpmn:outgoing>flow3</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="E"><bpmn:incoming>flow3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="flow1" sourceRef="S" targetRef="ARM"/>
    <bpmn:sequenceFlow id="flow2" sourceRef="ARM" targetRef="ARM2"/>
    <bpmn:sequenceFlow id="flow3" sourceRef="ARM2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="d_{wf_id}">
    <bpmndi:BPMNPlane id="pl_{wf_id}" bpmnElement="p_{wf_id}">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S">
        <dc:Bounds x="160" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="ARM_di" bpmnElement="ARM">
        <dc:Bounds x="250" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="ARM2_di" bpmnElement="ARM2">
        <dc:Bounds x="400" y="60" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E">
        <dc:Bounds x="550" y="82" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="flow1">
        <di:waypoint x="196" y="100"/><di:waypoint x="250" y="100"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="flow2">
        <di:waypoint x="350" y="100"/><di:waypoint x="400" y="100"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="flow3">
        <di:waypoint x="500" y="100"/><di:waypoint x="550" y="100"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>'''
    PR.put_raw(api_base, f"/api/workflows/{wf_id}/graph", xml, token)
    return wf_id


def login_page(page, base):
    page.goto(base, wait_until="networkidle")
    # 有密码框 = 登录页；登录后默认落地页不固定，这里只等表单消失，
    # 调用方随后直接 goto 实验详情页。
    if page.locator('input[type="password"]').count() > 0:
        page.locator('input[type="password"]').first.fill("admin123")
        ti = page.locator('input[type="text"]')
        if ti.count() and not ti.first.input_value():
            ti.first.fill("admin")
        page.get_by_role("button", name=re.compile(r"登\s*录")).click()
        page.wait_for_timeout(2000)


def active_hold(reason):
    """读取本次页面暂停创建的 Hold（默认 island scope，也可能退化为 experiment）。"""
    raw = PR.q1("SELECT row_to_json(h)::text FROM ("
                "SELECT id, scope, scope_id, kind, reason, held_at, released_at "
                "FROM runtime_hold WHERE tenant_id=1 AND released_at IS NULL "
                "AND reason='%s' ORDER BY id DESC LIMIT 1) h" % reason.replace("'", "''"))
    import json as _json
    return _json.loads(raw) if raw else None


def refresh_runs(page):
    card = page.locator(".ant-card", has_text="执行历史").first
    card.locator("button.ant-btn", has_text=re.compile(r"刷\s*新")).first.click()
    page.wait_for_timeout(900)


def cleanup_stale_page_fixtures():
    """清理历史 4.20 失败残留，只碰本脚本自己造的实验/流程。"""
    stale_reason = "4.20 页面暂停验证".replace("'", "''")
    try:
        # 失败中断后，测试 run 可能已被删，但 device_state 仍挂着旧 current_run_id；
        # 只在 run 已不存在时清理这条仿真机械臂租约，避免污染下一次 4.20。
        PR.q1("UPDATE device_state SET status='idle', current_method=NULL, current_run_id=NULL "
              "WHERE tenant_id=1 AND resource_id=%d "
              "AND current_run_id IS NOT NULL "
              "AND NOT EXISTS (SELECT 1 FROM run WHERE id=device_state.current_run_id)"
              % ARM_RESOURCE)
        PR.q1("UPDATE runtime_hold SET released_at=now(), "
              "release_note='4.20 stale cleanup by reason' "
              "WHERE tenant_id=1 AND released_at IS NULL "
              "AND reason='%s'" % stale_reason)
        exp_ids = PR.q1(
            "SELECT string_agg(id::text, ',') FROM experiment "
            "WHERE tenant_id=1 AND name LIKE 'E2E-实验暂停页面-%'")
        if exp_ids:
            PR.q1("UPDATE runtime_hold SET released_at=now(), "
                  "release_note='4.20 stale cleanup' "
                  "WHERE tenant_id=1 AND released_at IS NULL "
                  "AND scope='experiment' AND scope_id IN (%s)" % exp_ids)
            PR.q1("DELETE FROM experiment_run_step WHERE tenant_id=1 "
                  "AND experiment_run_id IN (SELECT id FROM experiment_run "
                  "WHERE experiment_id IN (%s))" % exp_ids)
            PR.q1("DELETE FROM experiment_run WHERE tenant_id=1 AND experiment_id IN (%s)" % exp_ids)
            PR.q1("DELETE FROM experiment_process WHERE tenant_id=1 AND experiment_id IN (%s)" % exp_ids)
            PR.q1("DELETE FROM experiment WHERE tenant_id=1 AND id IN (%s)" % exp_ids)
        wf_ids = PR.q1(
            "SELECT string_agg(id::text, ',') FROM workflow "
            "WHERE tenant_id=1 AND name LIKE 'E2E-实验暂停页面-%'")
        if wf_ids:
            PR.q1("DELETE FROM run_command WHERE tenant_id=1 AND run_id IN "
                  "(SELECT id FROM run WHERE workflow_id IN (%s))" % wf_ids)
            PR.q1("DELETE FROM run_node WHERE tenant_id=1 AND run_id IN "
                  "(SELECT id FROM run WHERE workflow_id IN (%s))" % wf_ids)
            PR.q1("DELETE FROM command_ledger WHERE tenant_id=1 AND run_id IN "
                  "(SELECT id FROM run WHERE workflow_id IN (%s))" % wf_ids)
            PR.q1("DELETE FROM run WHERE tenant_id=1 AND workflow_id IN (%s)" % wf_ids)
            PR.q1("DELETE FROM workflow_node WHERE tenant_id=1 AND workflow_id IN (%s)" % wf_ids)
            PR.q1("DELETE FROM workflow WHERE tenant_id=1 AND id IN (%s)" % wf_ids)
    except Exception as e:  # noqa: BLE001
        print("  （4.20 历史夹具清理失败：%s）" % e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=os.environ.get("LABOS_API", "http://127.0.0.1:18080"))
    ap.add_argument("--base", default=os.environ.get("LABOS_WEB", "http://localhost:5173"))
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()
    if not F.have_db():
        print("缺 LABOS_DB_* 环境变量")
        sys.exit(2)
    os.environ.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    F._TOKEN = F.api("/api/auth/login", "POST",
                     {"username": args.user, "password": args.pwd})["token"]
    token = F._TOKEN
    cleanup_stale_page_fixtures()

    print("══ 4.20 实验级暂停/恢复 · 页面 ══")
    wf_id = build_two_arm_workflow(token, args.api)
    r = F.api("/api/experiments", "POST",
              {"name": "E2E-实验暂停页面-%d" % int(time.time()),
               "workflowIds": [wf_id], "islandId": 2}, token)
    eid = int(r["id"])
    PR.MADE["exp"] = eid
    print("  建流程 #%d、建实验 #%d" % (wf_id, eid))

    # 后台线程发起**同步**执行：HTTP 阻塞，正好留出设备执行中的时间窗给页面暂停。
    result = {}
    def _run():
        try:
            result["r"] = F.api("/api/experiments/%d/run" % eid, "POST", {}, token)
        except Exception as e:  # noqa: BLE001
            result["err"] = str(e)
    th = threading.Thread(target=_run, daemon=True)
    th.start()

    deadline = time.time() + 90
    rid = erid = 0
    while time.time() < deadline:
        rows = PR.q1("SELECT COALESCE(max(id),0) FROM run WHERE workflow_id=%d" % wf_id)
        erows = PR.q1("SELECT COALESCE(max(id),0) FROM experiment_run WHERE experiment_id=%d" % eid)
        if rows and int(rows) > 0 and erows and int(erows) > 0:
            rid, erid = int(rows), int(erows)
            break
        time.sleep(0.1)
    if not rid or not erid:
        F.bad("实验执行没建出 run / experiment_run", "run=%s er=%s" % (rid, erid))
        PR.finish(1)
    PR.MADE["run"], PR.MADE["er"] = rid, erid
    print("  ✓ run #%d / experiment_run #%d 已建立" % (rid, erid))

    from playwright.sync_api import sync_playwright
    reason = "4.20 页面暂停验证"
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 1500, "height": 1000})
        page.set_default_timeout(25000)
        pause_requests = []
        pause_failures = []
        pause_responses = []
        page_errors = []
        console_errors = []
        page.on("request", lambda req: pause_requests.append((req.method, req.url)))
        page.on("requestfailed", lambda req: pause_failures.append(
            (req.method, req.url, req.failure)))
        page.on("response", lambda resp: pause_responses.append(
            (resp.status, resp.url)) if "/api/" in resp.url else None)
        page.on("pageerror", lambda err: page_errors.append(str(err)))
        page.on("console", lambda msg: console_errors.append(
            (msg.type, msg.text)) if msg.type == "error" else None)
        try:
            login_page(page, args.base)
            page.goto("%s/experiments/%d" % (args.base.rstrip("/"), eid), wait_until="networkidle")
            page.wait_for_timeout(1500)

            # ---- A. 页面执行历史里找到本次运行（在跑） ----
            row = page.locator("tr.ant-table-row", has_text="#%d" % erid)
            deadline = time.time() + 60
            live = False
            while time.time() < deadline:
                refresh_runs(page)
                if row.count() > 0:
                    txt = row.first.inner_text()
                    if "执行中" in txt or "排队中" in txt:
                        live = True
                        break
                page.wait_for_timeout(300)
            F.ok("执行历史里能看到本次实验运行 #%d（在跑）" % erid) if live \
                else F.bad("执行历史里找不到本次运行/已不活动", row.first.inner_text() if row.count() else "无行")

            # ---- B. 页面「暂停」→ 弹窗选作用域/原因 → 确认 ----
            pause_btn = row.first.locator("button.ant-btn-link", has_text=re.compile(r"^暂\s*停$"))
            F.ok("行内有「暂停」入口") if pause_btn.count() > 0 else F.bad("行内没有暂停按钮")
            pause_btn.first.click()
            modal = page.locator(".ant-modal:visible").last
            modal.wait_for(state="visible", timeout=10000)
            F.ok("暂停弹窗出现，标题带本次实验运行编号") \
                if ("暂停实验执行" in modal.inner_text() and str(erid) in modal.inner_text()) \
                else F.bad("暂停弹窗标题不对", modal.inner_text()[:120])
            ta = modal.locator("textarea").first
            ta.fill(reason)
            footer = modal.locator(".ant-modal-footer")
            print("  [debug] pause modal footer:", footer.inner_text())
            print("  [debug] pause modal body:", modal.inner_text()[:300])
            try:
                with page.expect_response(
                        lambda r: f"/api/experiments/runs/{erid}/pause" in r.url
                        and r.request.method == "POST", timeout=8000) as resp_info:
                    modal.locator(".ant-modal-footer .ant-btn-primary").first.click()
                pause_resp = resp_info.value
            except Exception as e:
                print("  [debug] no pause response:", e)
                print("  [debug] requests around pause:",
                      [u for u in pause_requests if "/api/" in u[1]][-10:])
                print("  [debug] failed requests around pause:",
                      [u for u in pause_failures if "/api/" in u[1]][-10:])
                print("  [debug] responses around pause:",
                      [u for u in pause_responses if "/api/" in u[1]][-10:])
                print("  [debug] page errors:", page_errors[-5:])
                print("  [debug] console errors:", console_errors[-5:])
                print("  [debug] modal still open:",
                      page.locator(".ant-modal:visible").count())
                raise
            page.wait_for_timeout(1200)
            F.ok("暂停接口返回 200") if pause_resp.status == 200 \
                else F.bad("暂停接口失败", f"{pause_resp.status} {pause_resp.text()[:160]}")
            F.ok("弹窗提交后关闭") if page.locator(".ant-modal:visible").count() == 0 \
                else F.bad("弹窗没关闭")

            # ---- C. S-04-B 受理语义：先「已请求暂停」，动作到边界后才「已暂停」 ----
            accept_er = PR.q1("SELECT status::text FROM experiment_run WHERE id=%d" % erid)
            accept_ctrl = PR.q1("SELECT control_state FROM experiment_run WHERE id=%d" % erid)
            accept_exp = PR.q1("SELECT status::text FROM experiment WHERE id=%d" % eid)
            F.ok("受理时 experiment_run 仍不是 paused（%s）" % accept_er) \
                if accept_er in ("running", "queued") \
                else F.bad("受理时 experiment_run 已被提前写成 paused", str(accept_er))
            F.ok("受理时 control_state=PAUSE_REQUESTED") \
                if accept_ctrl == "PAUSE_REQUESTED" \
                else F.bad("受理时 control_state 不对", str(accept_ctrl))
            F.ok("受理时 experiment 仍不是 paused（%s）" % accept_exp) \
                if accept_exp != "paused" \
                else F.bad("受理时 experiment 已被提前写成 paused", str(accept_exp))
            deadline = time.time() + 20
            dom_pending = False
            while time.time() < deadline:
                refresh_runs(page)
                if row.count():
                    txt = row.first.inner_text()
                    if ("已请求暂停" in txt or "暂停中" in txt) \
                            and row.first.locator("button.ant-btn-link",
                                                  has_text=re.compile(r"^恢\s*复$")).count() == 0:
                        dom_pending = True
                        break
                page.wait_for_timeout(300)
            F.ok("受理后 DOM 显示控制状态（已请求暂停/暂停中），没有假「已暂停」") \
                if dom_pending else F.bad("DOM 没显示暂停控制状态",
                                          row.first.inner_text() if row.count() else "无行")

            # ---- D. 先等 DB 收敛（paused + control_state NONE），再 reload 页面核对 DOM ----
            deadline = time.time() + 180
            converged = False
            while time.time() < deadline:
                er_now = PR.q1("SELECT status::text FROM experiment_run WHERE id=%d" % erid)
                ctrl_now = PR.q1("SELECT control_state FROM experiment_run WHERE id=%d" % erid)
                if er_now == "paused" and ctrl_now == "NONE":
                    converged = True
                    break
                page.wait_for_timeout(500)
            F.ok("DB 收敛为 paused 且 control_state=NONE") if converged \
                else F.bad("DB 未收敛为 paused/NONE", "er=%s ctrl=%s" % (er_now, ctrl_now))

            # 页面「刷新」按钮在报告卡内、容易点错卡；reload 拿服务端最新数据更可靠。
            dom_paused = False
            for _ in range(5):
                page.reload(wait_until="networkidle")
                page.wait_for_timeout(1500)
                if row.count() and "已暂停" in row.first.inner_text() \
                        and row.first.locator("button.ant-btn-link",
                                              has_text=re.compile(r"^恢\s*复$")).count() > 0:
                    dom_paused = True
                    break
            F.ok("执行历史行显示「已暂停」，并出现「恢复」入口（DOM）") if dom_paused \
                else F.bad("DOM 没有显示已暂停/恢复", row.first.inner_text() if row.count() else "无行")

            # ---- E. API/DB/Hold 三段 ----
            er_st = PR.q1("SELECT status::text FROM experiment_run WHERE id=%d" % erid)
            ex_st = PR.q1("SELECT status::text FROM experiment WHERE id=%d" % eid)
            F.ok("DB：experiment_run = paused") if er_st == "paused" else F.bad("experiment_run 不是 paused", er_st)
            F.ok("DB：experiment = paused") if ex_st == "paused" else F.bad("experiment 不是 paused", ex_st)
            hold = active_hold(reason)
            PR.MADE["hold"] = int(hold["id"]) if hold else 0
            F.ok("DB：页面暂停创建了 Hold（默认 island，reason 原样落库，id=%s）" % (hold or {}).get("id")) \
                if hold and hold.get("scope") in ("island", "experiment") \
                and (hold.get("reason") or "") == reason \
                else F.bad("Hold 不对（reason 含有空格时应原样落库）", str(hold))
            # 页面把 reason 放在 query 里：服务端 paramStr 现在做 form decode，
            # 含空格的 reason 必须原样落库（之前的 + 编码 bug 已修）。
            st, meth, owner = PR.device_state()
            F.ok("暂停被接受时设备动作仍在途（device_state=%s/%s）" % (st, meth)) \
                if st == "running" and owner == str(rid) else \
                print("  （设备已收敛，接受时刻未观测到在途：%s/%s/%s）" % (st, meth, owner))
            # 等设备动作收敛 + workflow 在节点边界 paused
            deadline = time.time() + 120
            wf_paused = False
            while time.time() < deadline:
                run_st = PR.q1("SELECT status::text FROM run WHERE id=%d" % rid)
                st2, _m, owner2 = PR.device_state()
                if run_st == "paused" and st2 != "running" and owner2 != str(rid):
                    wf_paused = True
                    break
                time.sleep(0.5)
            F.ok("设备动作收敛后，workflow run 停在节点边界 paused") if wf_paused \
                else F.bad("workflow run 没在边界 paused")
            arm_st = PR.node_status(rid, "ARM")
            arm2_st = PR.node_status(rid, "ARM2")
            F.ok("暂停点前节点 ARM 已完成（%s）" % arm_st) if arm_st == "completed" \
                else F.bad("ARM 不是 completed", str(arm_st))
            F.ok("暂停点后的 ARM2 保持 pending（%s）" % arm2_st) if arm2_st == "pending" \
                else F.bad("ARM2 不是 pending", str(arm2_st))
            F.ok("本次 Hold 在 workflow paused 后仍 active（没被误释放）") \
                if active_hold(reason) else F.bad("Hold 被提前释放")

            # ---- F. 页面「恢复」→ Hold 释放、继续跑完 ----
            refresh_runs(page)
            resume_btn = row.first.locator("button.ant-btn-link", has_text=re.compile(r"^恢\s*复$"))
            F.ok("点行内「恢复」") if resume_btn.count() > 0 else F.bad("没有恢复入口")
            resume_btn.first.click()
            page.wait_for_timeout(1500)
            deadline = time.time() + 60
            released = False
            while time.time() < deadline:
                h = PR.own_hold_snapshot(eid, reason)
                if not h or h.get("released_at"):
                    released = True
                    break
                time.sleep(0.5)
            F.ok("恢复后本次 Hold 已释放") if released else F.bad("Hold 未释放")
            th.join(timeout=240)
            F.ok("恢复后同步执行返回（后台线程结束）") if not th.is_alive() \
                else F.bad("执行线程 240s 未结束")
            deadline = time.time() + 120
            final_er = ""
            while time.time() < deadline:
                final_er = PR.q1("SELECT status::text FROM experiment_run WHERE id=%d" % erid)
                if final_er in ("completed", "failed", "aborted", "cancelled"):
                    break
                time.sleep(1)
            F.ok("恢复后整实验执行 completed（API/DB）") if final_er == "completed" \
                else F.bad("实验执行没 completed", final_er)
            final_ctrl = PR.q1("SELECT control_state FROM experiment_run WHERE id=%d" % erid)
            F.ok("恢复/完成后 control_state 回到 NONE（%s）" % final_ctrl) \
                if final_ctrl == "NONE" else F.bad("control_state 没回 NONE", str(final_ctrl))
            old_run_st = PR.q1("SELECT status::text FROM run WHERE id=%d" % rid)
            F.ok("恢复沿用 M-01：旧 paused workflow run 被作废（%s）" % old_run_st) \
                if old_run_st == "aborted" else F.bad("旧 run 状态不是 aborted", old_run_st)
            new_rid = PR.q1("SELECT COALESCE(max(id),0) FROM run WHERE workflow_id=%d" % wf_id)
            new_run_st = PR.q1("SELECT status::text FROM run WHERE id=%s" % (new_rid or 0))
            F.ok("恢复后新 workflow run（#%s）最终 completed" % new_rid) \
                if new_run_st == "completed" else F.bad("新 workflow run 不是 completed", new_run_st)

            # ---- G. DOM 终态（刷新页面拿最新数据，避免列表缓存） ----
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(2000)
            deadline = time.time() + 40
            dom_done = False
            while time.time() < deadline:
                if row.count() and "已完成" in row.first.inner_text() \
                        and row.first.locator("button.ant-btn-link", has_text=re.compile(r"^暂\s*停$|^恢\s*复$")).count() == 0:
                    dom_done = True
                    break
                refresh_runs(page)
                page.wait_for_timeout(300)
            F.ok("执行历史行最终显示「已完成」且不再有暂停/恢复入口（DOM）") if dom_done \
                else F.bad("DOM 终态不对", row.first.inner_text() if row.count() else "无行")
        finally:
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass
            b.close()
    # 兜底：失败时 PR.cleanup 可能因 run 未收敛而 fail-closed 保留现场；
    # 本测试抬的现场 Hold 必须释放，否则会作为全局闸门阻断调度器派发。
    try:
        PR.q1("UPDATE runtime_hold SET released_at=now(), "
              "release_note='4.20 e2e cleanup' "
              "WHERE tenant_id=1 AND released_at IS NULL "
              "AND reason='%s'" % reason.replace("'", "''"))
    except Exception:  # noqa: BLE001
        pass
    PR.finish(0)


if __name__ == "__main__":
    main()
