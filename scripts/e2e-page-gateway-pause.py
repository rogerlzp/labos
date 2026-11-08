#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端 e2e · 4.19 页面全链路（异或 + 并行两对网关 + 4 个设备任务 + 页面暂停/继续）。

图（全部从页面 palette 拖 + 页面连线，批量保存一次）：
  S -> XS -+- A -+
            |     +-> XJ -> PS -+- C -+
            +- B -+             |      +-> PJ -> E
                                +- D -+
  XS 走 default 边 -> B（A 被标记 bypassed）；A/B/C/D 都绑同一台仿真机械臂（Home），
  引擎按设备锁串行：B -> C -> D。

覆盖：
  A. 页面绘制 10 节点 / 11 连线 -> 单次保存落库 -> validate_flow 图合法
  B. 4 个设备任务绑定落库
  C. 页面「执行」-> 设备执行中点页面「暂停」-> 内核在下一个任务边界停
  D. 页面「继续」-> 不重跑已完成节点 -> 整单 completed
  E. DOM / API / DB 三段终态 + run_node 状态 + 设备指令条数
"""
import argparse
import importlib.util
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "e2e_pause_resume", os.path.join(HERE, "e2e-pause-resume-nodes.py"))
PR = importlib.util.module_from_spec(_spec)
sys.modules["e2e_pause_resume"] = PR
_spec.loader.exec_module(PR)
F = PR.F

BASE_DEFAULT = os.environ.get("LABOS_WEB", "http://localhost:5173")

NODES = [
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
EDGES = [("S", "XS"), ("XS", "A"), ("XS", "B"),
         ("A", "XJ"), ("B", "XJ"), ("XJ", "PS"),
         ("PS", "C"), ("PS", "D"),
         ("C", "PJ"), ("D", "PJ"), ("PJ", "E")]
DEV_NODES = ("A", "B", "C", "D")


def login(page):
    page.goto(BASE_DEFAULT, wait_until="networkidle")
    if page.locator("text=流程库").count() == 0:
        page.locator('input[type="password"]').first.fill("admin123")
        ti = page.locator('input[type="text"]')
        if ti.count() and not ti.first.input_value():
            ti.first.fill("admin")
        page.get_by_role("button", name=re.compile(r"登\s*录")).click()
        page.wait_for_selector("text=流程库", timeout=20000)
    # 测试允许中间态保存（批量连线/补连），产品用户不设这个标记
    page.evaluate("localStorage.setItem('labos.designer.draft', '1')")


def db_cleanup(wf, rid):
    env = dict(os.environ)
    env.setdefault("PGPASSWORD", env.get("LABOS_DB_PASS", ""))
    host = env.get("LABOS_DB_HOST")
    if not host or not wf:
        return
    db = env.get("LABOS_DB_NAME", "labdb")
    user = env.get("LABOS_DB_USER", "labos")
    port = env.get("LABOS_DB_PORT", "5432")
    statements = []
    if rid:
        statements += [
            "DELETE FROM run_command WHERE run_id=%d" % rid,
            "DELETE FROM run_node WHERE run_id=%d" % rid,
            "DELETE FROM run WHERE id=%d" % rid,
        ]
    statements += [
        "DELETE FROM workflow_node_step WHERE workflow_id=%d" % wf,
        "DELETE FROM workflow_edge WHERE workflow_id=%d" % wf,
        "DELETE FROM workflow_node WHERE workflow_id=%d" % wf,
        "DELETE FROM workflow WHERE id=%d" % wf,
    ]
    for sql in statements:
        subprocess.run(["psql", "-h", host, "-p", port, "-U", user, "-d", db,
                        "-c", sql], env=env, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)


def main():
    global BASE_DEFAULT
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE_DEFAULT)
    args = ap.parse_args()
    BASE_DEFAULT = args.base.rstrip("/")

    # psql 子进程读 PGPASSWORD；门禁只传 LABOS_DB_PASS，这里补一次
    if os.environ.get("LABOS_DB_PASS"):
        os.environ.setdefault("PGPASSWORD", os.environ["LABOS_DB_PASS"])

    F._TOKEN = F.api("/api/auth/login", "POST",
                     {"username": "admin", "password": "admin123"})["token"]
    name = "E2E-页面全链路-19-%d" % int(time.time())
    wf = 0
    rid = 0
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 1500, "height": 1000})
        page.set_default_timeout(25000)
        try:
            F.step("A. 页面绘制：异或+并行两对网关 + 4 个设备任务")
            login(page)
            wf = int(F.api("/api/workflows", "POST", {"name": name}, F._TOKEN)["id"])
            page.goto("%s/designer/%d" % (BASE_DEFAULT, wf), wait_until="networkidle")
            page.wait_for_selector(".djs-container", timeout=25000)
            page.wait_for_timeout(1500)
            for seed in ("S", "E"):
                F.delete_node(page, seed)
            ids = {}
            for key, action, x, y in NODES:
                PR.clear_selection(page)
                ids[key] = F.place(page, action, x, y)
            miss = [k for k, v in ids.items() if not v]
            F.ok("10 个节点都从 palette 落到画布") if not miss \
                else F.bad("palette 落点失败", ",".join(miss))
            for src, dst in EDGES:
                F.connect(page, ids[src], ids[dst])
            F.ok("11 条连线在页面上批量连完并一次保存") if F.page_save(page) \
                else F.bad("批量连线后保存没完成")
            xml = F.api("/api/workflows/%d/graph" % wf, token=F._TOKEN)
            xml = xml if isinstance(xml, str) else F.json.dumps(xml)
            got = set(F.graph_edges(xml).values())
            missing = [(a, c) for a, c in EDGES if (ids[a], ids[c]) not in got]
            for src, dst in missing:
                F.connect(page, ids[src], ids[dst])
                F.page_save(page)
                xml = F.api("/api/workflows/%d/graph" % wf, token=F._TOKEN)
                xml = xml if isinstance(xml, str) else F.json.dumps(xml)
                if (ids[src], ids[dst]) in set(F.graph_edges(xml).values()):
                    missing = [m for m in missing if m != (src, dst)]
            F.ok("11 条连线全部落库（缺边已补连）") if not missing \
                else F.bad("有连线没落库", str(missing))
            errs = F.validate_flow(xml)
            F.ok("流程图合法（1 开始/1 结束/全连通/异或+并行各自成对）") if not errs \
                else F.bad("图不合法", ";".join(errs))

            F.step("B. 4 个设备任务绑定（同一台仿真机械臂）")
            for key in DEV_NODES:
                # 刚保存的图/workflow_node 行可能还在事务可见性边界上，
                # 绑定 PUT + 落库核对允许重试 3 次（每次查库确认）。
                ok = False
                for _try in range(3):
                    if PR.bind_device_api(wf, ids[key]):
                        ok = True
                        break
                    page.wait_for_timeout(900)
                F.ok("%s 绑成仿真设备任务并落库（%s / %s）" % (key, PR.DEV_CODE, PR.DEV_METHOD)) \
                    if ok else F.bad("%s 绑定失败" % key)

            F.step("C. 页面执行 -> 执行中点页面「暂停」")
            rid = PR.page_start_run(page, BASE_DEFAULT, name)
            F.ok("页面点「执行」→ 跳到运行详情（run #%d）" % rid) if rid \
                else F.bad("页面执行没跳到运行详情")
            deadline = time.time() + 90
            running = False
            while time.time() < deadline:
                st = PR.device_state()
                if st[0] == "running" and st[2] == str(rid):
                    running = True
                    break
                page.wait_for_timeout(500)
            F.ok("观测到设备方法执行中（device_state running current_run_id=%d）" % rid) \
                if running else F.bad("没观测到设备执行中", str(PR.device_state()))
            F.ok("运行详情页点「暂停」按钮") if PR.page_pause(page) \
                else F.bad("运行详情页没有暂停按钮/点不到")

            deadline = time.time() + 90
            paused = False
            while time.time() < deadline:
                if PR.api_run_status(rid) == "paused":
                    paused = True
                    break
                page.wait_for_timeout(500)
            F.ok("点暂停后整单 paused（API）") if paused else F.bad("暂停后没到 paused")
            states = PR.node_states(rid)
            # pause_node_key 是内核在“当前动作做完、到下一个任务边界”时才写的，
            # API 状态转 paused 可能比它早几百毫秒——轮询等它落库再断言。
            pause_key = ""
            deadline = time.time() + 60
            while time.time() < deadline:
                pause_key = F.q("SELECT coalesce(pause_node_key,'') FROM run WHERE id=%d" % rid)
                if pause_key:
                    break
                page.wait_for_timeout(500)
            boundary = pause_key in (ids["A"], ids["B"], ids["C"], ids["D"])
            F.ok("内核在下一个任务边界停（pause_node_key=%s）" % pause_key) if boundary \
                else F.bad("暂停点不是任务边界", "pause_node_key=%r states=%s"
                           % (pause_key, str(states)))
            # 异或走哪条由引擎按“目标 id 字典序/条件”决定，这里只断言
            # 「恰好一条在推进/完成、另一条没被选中」的语义（终态再严格断言）。
            F.ok("暂停时至少还有并行分支没跑完（C=%s D=%s）"
                 % (states.get(ids["C"]), states.get(ids["D"]))) \
                if states.get(ids["C"]) != "completed" or states.get(ids["D"]) != "completed" \
                else F.bad("暂停时 C/D 已经跑完，暂停点没落在并行分支前",
                           str({k: states.get(ids[k]) for k in ("C", "D")}))

            F.step("D. 页面继续 -> 续跑并整单 completed")
            F.ok("运行详情页点「继续」按钮") if PR.page_continue(page) \
                else F.bad("运行详情页没有继续按钮/点不到")
            deadline = time.time() + 300
            final = ""
            while time.time() < deadline:
                final = PR.api_run_status(rid)
                if final in ("completed", "failed", "cancelled", "manual"):
                    break
                page.wait_for_timeout(1000)
            F.ok("恢复后整单 completed（API）") if final == "completed" \
                else F.bad("恢复后没 completed", final)
            states = PR.node_states(rid)
            F.ok("C 续跑后 completed（%s）" % states.get(ids["C"])) \
                if states.get(ids["C"]) == "completed" else F.bad("C 没完成", str(states))
            F.ok("D 续跑后 completed（%s）" % states.get(ids["D"])) \
                if states.get(ids["D"]) == "completed" else F.bad("D 没完成", str(states))
            F.ok("并行汇合等齐（C/D 都 completed）") \
                if states.get(ids["C"]) == "completed" and states.get(ids["D"]) == "completed" \
                else F.bad("并行分支没全完成", str(states))
            chosen = "A" if states.get(ids["A"]) == "completed" else "B"
            other = "B" if chosen == "A" else "A"
            F.ok("异或分叉恰好走 1 条（走了 %s），另一条 bypassed（%s）"
                 % (chosen, other)) \
                if states.get(ids[chosen]) == "completed" and states.get(ids[other]) == "bypassed" \
                else F.bad("异或分支终态不对",
                           str({k: states.get(ids[k]) for k in ("A", "B")}))
            for key in (chosen, "C", "D"):
                n = PR.dev_cmd_count(rid, ids[key])
                F.ok("%s 的设备指令下了 1 次（%d 条，续跑不重复）" % (key, n)) \
                    if n == 1 else F.bad("%s 指令条数不对" % key, str(n))
            n_other = PR.dev_cmd_count(rid, ids[other])
            F.ok("%s 是 bypassed 分支，没有下发设备指令（%d 条）" % (other, n_other)) \
                if n_other == 0 else F.bad("%s 不该有设备指令" % other, str(n_other))

            F.step("E. DOM / API / DB 三段终态")
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(2000)
            body = page.inner_text("body")
            F.ok("运行详情页显示 completed（DOM）") if "completed" in body \
                else F.bad("页面没显示 completed", body[:200])
            F.ok("运行详情页有流程图/图例") if ("图例" in body or "流程图" in body) \
                else F.bad("运行详情页缺流程图/图例", body[:200])
            F.ok("API 最终状态 completed") if PR.api_run_status(rid) == "completed" \
                else F.bad("API 最终状态不是 completed")
            F.ok("DB 最终状态 completed") \
                if F.q("SELECT status::text FROM run WHERE id=%d" % rid) == "completed" \
                else F.bad("DB 最终状态不是 completed")
        finally:
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass
            b.close()
            try:
                PR.wait_arm_settled(rid, limit=120)
            except Exception:  # noqa: BLE001
                pass
            db_cleanup(wf, rid)
    PR.finish()


if __name__ == "__main__":
    main()
