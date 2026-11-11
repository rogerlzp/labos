#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设计器页面操作的**自包含**辅助模块（Playwright + API + 库）。

为什么单独有它：
  原来这些写法散在 `scripts/e2e-flow-matrix.py` 里。那个文件在 2026-11-11
  「旧门禁体系整体退役」时被删了（`7c9a2f7`），但里面沉淀的是**页面交互的
  断言写法**（Playwright 操作、DOM 选择器、bpmn-js 的落点与连线手法）——
  删了等于重写。所以把还在用的那部分抽到这里，自包含，谁都能 import。

被谁用：
  · `scripts/verify-gateway-pairs.py`（网关成对入口验证）
  · `scripts/shot-gateways.py` / `scripts/shot-gateway-labels.py`（截图素材）

⚠ **bpmn-js 的两个坑**（抽出来时保留原注释）：
  ① 图元带 transform，Playwright 的可点性检查会一直等 ⇒ 连线用**裸鼠标点**
     （bounding_box 求中心），不用 `locator.click()`；
  ② 设计器左侧有「按岛设备」面板，画布会变窄 ⇒ 固定 x 会点到容器外、
     节点静默不生成 ⇒ 落点要按容器宽度等比缩放。
"""
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request

BASE_API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
BASE_WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")


# ---------------------------------------------------------------- 断言辅助
def ok(msg):
    print("  \033[32m✓\033[32m " + msg)


def bad(msg, detail=""):
    print("  \033[31m✗\033[31m " + msg + (("：%s" % detail) if detail else ""))


# ---------------------------------------------------------------- 库
def have_db():
    return all(os.environ.get(k) for k in
               ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER", "LABOS_DB_PASS"))


def q(sql):
    """跑一条 SQL 并返回 stdout（多行）。连不上就抛错，别静默返回空串。"""
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "")
    out = subprocess.run(
        [os.environ.get("PSQL", "psql"), "-h", os.environ["LABOS_DB_HOST"], "-p",
         os.environ.get("LABOS_DB_PORT", "5432"), "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 执行失败：%s | stderr: %s"
                           % (sql[:120], (out.stderr or "").strip()[:300]))
    return out.stdout.strip()


# ---------------------------------------------------------------- API
def api(path, method="GET", body=None, token=None, raw=None, timeout=60):
    req = urllib.request.Request(BASE_API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if raw is not None:
        data = raw.encode() if isinstance(raw, str) else raw
        req.add_header("Content-Type", "application/xml")
    elif body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    else:
        data = None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            txt = r.read().decode()
            try:
                return json.loads(txt)
            except json.JSONDecodeError:
                return txt
    except urllib.error.HTTPError as e:
        raise RuntimeError("%s %s → HTTP %d: %s"
                           % (method, path, e.code, e.read().decode()[:300]))


def login(page, base=BASE_WEB, user="admin", pwd="admin123"):
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() > 0:
        return
    page.locator('input[type="password"]').first.fill(pwd)
    ti = page.locator('input[type="text"]')
    if ti.count() > 0 and not ti.first.input_value():
        ti.first.fill(user)
    page.get_by_role("button", name=re.compile(r"登\s*录")).click()
    page.wait_for_selector("text=流程库", timeout=20000)


# ---------------------------------------------------------------- 画布操作
def canvas_ids(page):
    """画布上所有图元的 data-element-id（含连线；连线也是 .djs-element）。"""
    return page.locator(".djs-element[data-element-id]").evaluate_all(
        "els => els.map(e => e.getAttribute('data-element-id'))")


def place(page, action, x, y):
    """点 palette 项 → 在画布落点；返回新图元 id（排除 *_label 附属图元）。

    bpmn-js：先选工具，再在画布上点一下。
    """
    before = set(canvas_ids(page))
    entry = page.locator(f'.djs-palette .entry[data-action="{action}"]').first
    if entry.count() == 0:
        return None
    entry.click()
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


def clear_selection(page):
    """取消选中（bpmn-js 的 context-pad 会盖住 palette，导致下一个点击一直 retry）。

    用 Esc 而不是点空白：context-pad 就挂在画布上，点「空白」可能正好点在它身上。
    """
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    try:
        page.evaluate("""() => {
            document.querySelectorAll('.djs-context-pad-parent').forEach(el => el.remove());
        }""")
    except Exception:  # noqa: BLE001
        pass
    page.wait_for_timeout(200)


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


def save(page):
    """点「保存」并等落库完成（画布容器上会打 data-graph-saved 时间戳）。"""
    page.locator("button.ant-btn", has_text=re.compile(r"保\s*存")).first.click()
    page.wait_for_timeout(2500)


# ---------------------------------------------------------------- 流程
def nav(page, menu):
    page.locator(f'.ant-menu-item:has-text("{menu}")').first.click()
    page.wait_for_timeout(1200)


class Ctx:
    """建出来的东西（流程 / run）都记着，cleanup 时删掉。"""

    def __init__(self, token):
        self.token = token
        self.made_wf = []
        self.made_run = []

    def wait_devices_idle(self, limit=420):
        """等设备回到 idle 再删数据。

        ⚠ 为什么必须等：设备方法耗时随机 30–300 秒（真机时序），
        设备还在执行时删掉 run_node ⇒ 设备回包后落库步骤行时父节点已不存在 ⇒
        外键违反 ⇒ 别处的「步骤落库失败」断言变红。
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
    """从界面新建一条流程并进设计器；返回 wf_id。"""
    # 测试脚本允许保存中间态图（逐边连线/调设置/腾挪节点），走 draft=1；
    # 产品用户（无 localStorage 标记）仍会被服务端拒绝「有孤立节点/缺开始结束」的图。
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


def run_workflow(ctx, wf_id, wait=420):
    """触发一次真执行（与页面「执行」同一条执行链），返回 (run_id, status)。"""
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
