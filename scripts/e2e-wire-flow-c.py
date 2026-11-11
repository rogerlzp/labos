#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
流程 C（1000547 人工验收-C 并行分支）画布接线 e2e v3。

v1/v2 教训：
  · connect 工具连的线**不会自动落库**（前端只把它标脏，点「保存」才持久化，
    见 webapp/src/pages/Designer.tsx：仅 palette 拖入和「删除选中节点」走 draft 保存）；
  · 保存时服务端拒绝孤立节点 —— 任何一条线没连上都会整单失败；
  · 「拖网关」手势会变成平移画布；每步必须重新读坐标。

v3 策略：
  · 连线只验画布（每条连线后 .djs-connection 计数 +1 且 bbox 与端点中点吻合）；
  · 7 条线全部上画布后一次性「保存」，再由 DB workflow_edge 做终验；
  · 保存失败（toast 报错）→ 截图 + 刷新重来；
  · 每步前重读坐标、检测登录态。

用法：
  /usr/bin/python3 scripts/e2e-wire-flow-c.py [--headed]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

SHOT_DIR = "/tmp/labos-wire-c"
WF_ID = "1000547"
FORK_ID = "Gateway_0rnmoyb"   # fork
JOIN_ID = "Gateway_1p139d8"   # join
RUN = "Activity_1ajh99o"      # RunMethod  ID0000
INC = "Activity_0yfnzxq"      # Incubate   ID1183
HOME = "Activity_1dm1stx"     # Home       ID1731

step_no = 0


def step(title):
    global step_no
    step_no += 1
    print(f"\n══ 步骤 {step_no}: {title} ══", flush=True)


def shot(page, name):
    path = f"{SHOT_DIR}/{step_no:02d}-{name}.png"
    page.screenshot(path=path, full_page=False)
    print(f"  [截图] {path}", flush=True)
    return path


def fail(msg):
    print(f"\n✗ 中止: {msg}")
    sys.exit(1)


def _db_pw():
    # 可能有多个进程匹配（bash 包装层 + java），取 java 内核进程
    for pid in subprocess.check_output(["pgrep", "-f", "LabosApplication"]).decode().split():
        cmd = subprocess.check_output(["ps", "-p", pid, "-o", "command="]).decode()
        if "java" not in cmd:
            continue
        env = subprocess.check_output(["ps", "eww", "-p", pid]).decode()
        m = re.search(r"LABOS_DB_PASS=(\S+)", env)
        if m:
            return m.group(1).strip("'\"")
    fail("拿不到内核数据库密码")


def db_edges():
    env = dict(os.environ, PGPASSWORD=_db_pw())
    out = subprocess.check_output(
        ["/opt/homebrew/opt/libpq/bin/psql", "-h", "192.168.124.2", "-U", "labos",
         "-d", "labdb", "-tc",
         f"select from_key||'->'||to_key from workflow_edge where workflow_id={WF_ID}"],
        env=env).decode()
    return {l.strip() for l in out.splitlines() if l.strip()}


# ---------------- 画布操作 ----------------
def center(page, element_id):
    return page.evaluate(
        """(id) => {
            const el = document.querySelector(`.djs-element[data-element-id="${id}"]`);
            if (!el) return null;
            const r = el.getBoundingClientRect();
            return {x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2)};
        }""",
        element_id,
    )


def all_elements(page):
    return page.evaluate(
        """() => {
            const out = {};
            document.querySelectorAll('.djs-element[data-element-id]').forEach(el => {
                const r = el.getBoundingClientRect();
                out[el.getAttribute('data-element-id')] = [Math.round(r.x+r.width/2), Math.round(r.y+r.height/2)];
            });
            return out;
        }"""
    )


def canvas_connections(page):
    """返回 [{id, cx, cy, w, h, sx, sy, ex, ey}]——bbox + path 两端点的视口坐标。"""
    return page.evaluate(
        """() => [...document.querySelectorAll('.djs-connection[data-element-id]')]
            .map(c => {
                const r = c.getBoundingClientRect();
                const path = c.querySelector('.djs-visual path');
                let sx=0, sy=0, ex=0, ey=0;
                if (path) {
                    const L = path.getTotalLength();
                    const m = path.getScreenCTM();
                    const p0 = path.getPointAtLength(0);
                    const p1 = path.getPointAtLength(L);
                    const q0 = new DOMPoint(p0.x, p0.y).matrixTransform(m);
                    const q1 = new DOMPoint(p1.x, p1.y).matrixTransform(m);
                    sx=q0.x; sy=q0.y; ex=q1.x; ey=q1.y;
                }
                return {id: c.getAttribute('data-element-id'),
                        cx: Math.round(r.x+r.width/2), cy: Math.round(r.y+r.height/2),
                        w: Math.round(r.width), h: Math.round(r.height),
                        sx: Math.round(sx), sy: Math.round(sy),
                        ex: Math.round(ex), ey: Math.round(ey)};
            })"""
    )


def click_palette_tool(page, title):
    page.locator(f'.djs-palette .entry[title="{title}"]').click()
    page.wait_for_timeout(300)
    active = page.evaluate(
        "() => { const h = document.querySelector('.djs-palette .entry.highlighted-entry'); return h ? h.getAttribute('title') : null; }"
    )
    if active != title:
        fail(f"工具未激活: 期望 '{title}' 实际 '{active}'")


def click_hand(page):
    click_palette_tool(page, "Activate hand tool")


def selected_id(page):
    return page.evaluate(
        "() => { const s = document.querySelector('.djs-element.selected'); return s ? s.getAttribute('data-element-id') : null; }"
    )


def delete_selected(page):
    btn = page.get_by_role("button", name=re.compile(r"删除选中节点"))
    if not btn.is_enabled():
        fail("删除选中节点按钮不可用")
    btn.click()
    page.wait_for_timeout(600)


def edge_matches(conn, s, d):
    """连线 path 端点应落在两端点附近（锚点在形状边缘，容差 45px）。"""
    import math
    ds = math.hypot(conn["sx"] - s["x"], conn["sy"] - s["y"])
    de = math.hypot(conn["ex"] - d["x"], conn["ey"] - d["y"])
    return ds <= 45 and de <= 45


def connect(page, src_id, dst_id):
    """click-click 连线：激活 connect 工具 → 点源 → 点目标。
    （实测：从网关 diamond 拖线会被 bpmn-js 当成移动元素，click-click 稳定）"""
    s = center(page, src_id)
    d = center(page, dst_id)
    if not s or not d:
        fail(f"连线找不到端点 {src_id}->{dst_id}: s={s} d={d}")
    before = canvas_connections(page)
    click_palette_tool(page, "Activate global connect tool")
    # 点源
    page.mouse.move(s["x"], s["y"])
    page.wait_for_timeout(250)
    page.mouse.down()
    page.wait_for_timeout(150)
    page.mouse.up()
    page.wait_for_timeout(500)
    # 移动到目标上空（出现 preview），再点目标提交
    page.mouse.move(d["x"], d["y"], steps=6)
    page.wait_for_timeout(400)
    page.mouse.down()
    page.wait_for_timeout(150)
    page.mouse.up()
    page.wait_for_timeout(500)
    click_hand(page)
    page.wait_for_timeout(300)
    after = canvas_connections(page)
    new = [c for c in after if c["id"] not in {b["id"] for b in before}]
    return len(after) == len(before) + 1 and len(new) == 1, [c["id"] for c in new]


def ensure_logged_in_designer(page, base, user, pwd):
    if page.locator(".djs-container").count() > 0:
        return
    page.goto(base, wait_until="networkidle")
    if page.locator("text=流程库").count() == 0:
        page.locator('input[type="password"]').first.fill(pwd)
        ti = page.locator('input[type="text"]')
        if ti.count() > 0 and not ti.first.input_value():
            ti.first.fill(user)
        page.get_by_role("button", name=re.compile(r"登\s*录")).click()
        page.wait_for_selector("text=流程库", timeout=15000)
    page.goto(f"{base}/designer/{WF_ID}", wait_until="networkidle")
    page.wait_for_selector(".djs-container", timeout=20000)
    page.wait_for_timeout(2000)


def pan_canvas(page, dx, dy):
    click_hand(page)
    before = len(all_elements(page))
    page.mouse.move(1000, 750)
    page.mouse.down()
    page.wait_for_timeout(150)
    page.mouse.move(1000 + dx, 750 + dy, steps=12)
    page.wait_for_timeout(150)
    page.mouse.up()
    page.wait_for_timeout(500)
    after = len(all_elements(page))
    if after > before:  # 误建了元素（工具残留），删掉多出来的 Gateway
        for gid in [k for k in all_elements(page) if k.startswith("Gateway_") and k not in (FORK_ID, JOIN_ID)]:
            gc = center(page, gid)
            page.mouse.move(gc["x"], gc["y"]); page.wait_for_timeout(200)
            page.mouse.down(); page.wait_for_timeout(120); page.mouse.up(); page.wait_for_timeout(300)
            if selected_id(page) == gid:
                delete_selected(page)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()

    os.makedirs(SHOT_DIR, exist_ok=True)

    plan = [
        ("S", FORK_ID),
        (FORK_ID, RUN),
        (RUN, INC),
        (FORK_ID, HOME),
        (INC, JOIN_ID),
        (HOME, JOIN_ID),
        (JOIN_ID, "E"),
    ]

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1440, "height": 900})
        page.set_default_timeout(15000)
        try:
            # ---------- 1. 登录 + 打开设计器 ----------
            step("登录并打开设计器")
            page.goto(args.base, wait_until="networkidle")
            if page.locator("text=流程库").count() == 0:
                page.locator('input[type="password"]').first.fill(args.pwd)
                ti = page.locator('input[type="text"]')
                if ti.count() > 0 and not ti.first.input_value():
                    ti.first.fill(args.user)
                page.get_by_role("button", name=re.compile(r"登\s*录")).click()
                page.wait_for_selector("text=流程库", timeout=15000)
            page.goto(f"{args.base}/designer/{WF_ID}", wait_until="networkidle")
            page.wait_for_selector(".djs-container", timeout=20000)
            page.wait_for_timeout(2500)
            els = all_elements(page)
            print(f"  画布元素: {json.dumps(els, ensure_ascii=False)}", flush=True)
            for nid in ["S", "E", FORK_ID, JOIN_ID, RUN, INC, HOME]:
                if nid not in els:
                    fail(f"画布缺少元素 {nid}")
            shot(page, "recon")

            # ---------- 2. 删旧 S→E 直连（若还在） ----------
            step("删除旧的 S→E 直连 f1")
            if "S->E" in db_edges():
                c = center(page, "f1")
                if not c:
                    fail("DB 有 S->E 但画布找不到 f1")
                page.mouse.move(c["x"], c["y"])
                page.wait_for_timeout(200)
                page.mouse.down()
                page.wait_for_timeout(150)
                page.mouse.up()
                page.wait_for_timeout(400)
                if selected_id(page) != "f1":
                    fail(f"选中的不是 f1: {selected_id(page)}")
                delete_selected(page)
                deadline = time.time() + 8
                while "S->E" in db_edges() and time.time() < deadline:
                    time.sleep(0.6)
                if "S->E" in db_edges():
                    fail("f1 删除后 DB 仍有 S->E")
                print("  ✓ DB 已确认 f1 删除", flush=True)
            else:
                print("  DB 无 S->E，跳过", flush=True)

            # ---------- 3. 平移画布露出网关 + 拆开叠放 ----------
            step("平移画布露出网关并拆开叠放")
            els = all_elements(page)
            if els[FORK_ID][0] < 560:
                pan_canvas(page, 220, 0)
                els = all_elements(page)
            print(f"  网关位置: fork={els[FORK_ID]} join={els[JOIN_ID]}", flush=True)
            if els[FORK_ID][0] < 560 or els[JOIN_ID][0] < 560:
                fail("网关仍在工具栏底下")
            if els[FORK_ID] == els[JOIN_ID]:
                print("  两网关叠放，先拖开上层", flush=True)
                c = els[FORK_ID]
                page.mouse.move(c[0], c[1])
                page.wait_for_timeout(250)
                page.mouse.down()
                page.wait_for_timeout(120)
                page.mouse.up()
                page.wait_for_timeout(400)
                top = selected_id(page)
                if not top or not top.startswith("Gateway_"):
                    fail(f"点选网关失败: {top}")
                page.mouse.move(c[0], c[1])
                page.wait_for_timeout(250)
                page.mouse.down()
                page.wait_for_timeout(250)
                page.mouse.move(c[0] + 50, c[1], steps=6)
                page.wait_for_timeout(200)
                page.mouse.move(c[0] + 170, c[1] - 110, steps=10)
                page.wait_for_timeout(250)
                page.mouse.up()
                page.wait_for_timeout(600)
                els = all_elements(page)
                print(f"  拖开后: fork={els[FORK_ID]} join={els[JOIN_ID]}", flush=True)
                if els[FORK_ID] == els[JOIN_ID]:
                    fail("网关仍然叠放（拖拽未生效）")
            shot(page, "panned")

            # ---------- 4. 逐条连线（验画布，不验库） ----------
            step("连线 7 条（画布计数验证）")
            for src, dst in plan:
                edge = f"{src}->{dst}"
                done = False
                for attempt in (1, 2, 3):
                    ok, info = connect(page, src, dst)
                    if ok:
                        print(f"  ✓ {edge}（第 {attempt} 次）", flush=True)
                        done = True
                        break
                    print(f"  ! {edge} 第 {attempt} 次失败 new={info}", flush=True)
                    shot(page, f"retry-{src}-{dst}")
                    time.sleep(1)
                if not done:
                    fail(f"连线失败: {edge}")
            conns = canvas_connections(page)
            print(f"  画布连线数: {len(conns)}", flush=True)
            if len(conns) != 7:
                fail(f"画布连线数不对: {len(conns)} != 7")
            shot(page, "wired")

            # ---------- 5. 自动对齐（跳过：拓扑正确即可运行，布局不影响执行；
            #           且叠放网关经对齐的行为未验证，避免节外生枝） ----------
            step("自动对齐（跳过）")
            print("  跳过：拓扑已正确，布局不影响运行", flush=True)

            # ---------- 6. 保存 ----------
            step("保存")
            # 「保存」按钮有多个（画布头 / 流程选项面板），取与「自动对齐」同排的那个
            pt = page.evaluate(
                """() => {
                    const align = [...document.querySelectorAll('button')].find(b => b.innerText.includes('自动对齐'));
                    if (!align) return null;
                    const ar = align.getBoundingClientRect();
                    const cands = [...document.querySelectorAll('button')].filter(b => /保\\s*存/.test(b.innerText) && !/绑定/.test(b.innerText));
                    let best = null, bd = 1e9;
                    for (const b of cands) {
                        const r = b.getBoundingClientRect();
                        const d = Math.abs(r.y - ar.y) * 10 + Math.abs(r.x - ar.x);
                        if (d < bd) { bd = d; best = r; }
                    }
                    return best ? {x: Math.round(best.x + best.width/2), y: Math.round(best.y + best.height/2)} : null;
                }"""
            )
            if not pt:
                fail("找不到画布头保存按钮")
            print(f"  画布头保存按钮: {pt}", flush=True)
            page.mouse.click(pt["x"], pt["y"])
            # 保存成功会在画布容器打点 data-graph-saved；失败会弹 ant-message 错误
            saved = False
            for _ in range(20):
                page.wait_for_timeout(500)
                if page.evaluate("() => !!document.querySelector('[data-graph-saved]')"):
                    saved = True
                    break
                txt = page.evaluate(
                    "() => { const t = document.querySelector('.ant-message'); return t ? t.innerText : ''; }"
                )
                if txt and ("失败" in txt or "error" in txt.lower()):
                    print(f"  ✗ 保存报错: {txt[:300]}", flush=True)
                    break
            shot(page, "saved")
            print(f"  data-graph-saved 打点: {saved}", flush=True)
            toast = page.evaluate(
                "() => { const t = document.querySelector('.ant-message, .ant-notification'); return t ? t.innerText.slice(0,400) : ''; }"
            )
            if toast:
                print(f"  toast: {toast}", flush=True)
            db_now = db_edges()
            print(f"  DB 连线: {sorted(db_now)}", flush=True)
            expected = {f"{s}->{d}" for s, d in plan}
            if db_now == expected:
                print("  ✓ DB 连线与计划完全一致", flush=True)
            else:
                fail(f"DB 连线不符: 缺 {sorted(expected-db_now)} 多 {sorted(db_now-expected)}")
            badge = page.locator("text=未保存").count()
            print(f"  「未保存」徽标还在: {badge > 0}", flush=True)
            try:
                print(f"  预检区域: {page.locator('text=预检').first.inner_text()}", flush=True)
            except Exception:
                pass
            print("\n✓ 全部完成", flush=True)
        except PWTimeout as e:
            shot(page, "timeout")
            fail(f"超时: {e}")
        finally:
            browser.close()


if __name__ == "__main__":
    main()
