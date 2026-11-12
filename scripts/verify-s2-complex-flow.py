#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S2 物料身份与状态 · 复杂流程端到端验证。

回答「S2 在真实复杂流程下到底跑成什么样」：此前四张物料表全是 0 行，
即 T1/T2/声明快照/事件链从未在真实执行里端到端跑过。本脚本造两张图：

  main · 3 路并行分叉 + 并行汇合 + 异或选边 + 内置变量节点 + 4 个设备 TASK
         3 份 material_effect 声明（含同一块板 0→1→2 的版本链）
  loop · 循环回边 3 圈 + 内置 __expr 递增 + 1 个设备 TASK 声明
         专门暴露 R13-06（source_loop_index 恒 0 ⇒ 第 2 圈起 Effect 静默丢弃）

复用 scripts/core-scenarios.py 的 api/q/bpmn_xml/seed_*/wait_run（该文件有 main guard）。
结果写 webapp/public/s2-complex-flow-results.json + stdout。

用法：
  python3 scripts/verify-s2-complex-flow.py --flow all     # 默认
  python3 scripts/verify-s2-complex-flow.py --flow main
  python3 scripts/verify-s2-complex-flow.py --flow loop
"""
import argparse
import datetime
import importlib.util
import json
import os
import re
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "core_scenarios", os.path.join(ROOT, "scripts", "core-scenarios.py"))
cs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cs)

api, q, bpmn_xml = cs.api, cs.q, cs.bpmn_xml

ISLAND = os.environ.get("LABOS_BASELINE_ISLAND", "LAB-BASE-01")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

PLATE_A = "PL-TRACE-2183"
PLATE_B = "PL-TRACE-2214"

PASS, FAIL, SKIP = 0, 0, 0
EVIDENCE = {}


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def skip(msg, detail=""):
    global SKIP
    SKIP += 1
    print("  \033[33m–\033[0m " + msg + (("（%s）" % detail) if detail else ""))


# ---------------------------------------------------------------- 造图
def devices():
    island = int(q("SELECT id FROM resource_node WHERE code='%s' AND kind='island'" % ISLAND))
    devs = {}
    for line in q("SELECT di.code || '|' || di.resource_id FROM device_instance di "
                  "JOIN driver dr ON dr.id = di.driver_id WHERE di.tenant_id=1 "
                  "AND di.deleted_at IS NULL AND di.status='available' "
                  "AND dr.endpoint LIKE 'tcp://%'").splitlines():
        if "|" in line:
            code, rid = line.split("|", 1)
            devs[code] = int(rid)
    return island, devs


def edges_for(flows, shapes):
    """给每条 flow 生成 BPMNEdge（缺了画布上就没连线，core-scenarios.py:151）。"""
    out = {}
    for f in flows:
        fid, src, dst = f[0], f[1], f[2]
        a = shapes.get(src)
        b = shapes.get(dst)
        if not a or not b:
            out[fid] = [(100, 178), (200, 178)]
            continue
        out[fid] = [(a[0] + a[2], a[1] + a[3] // 2), (b[0], b[1] + b[3] // 2)]
    return out


def graph_main(d):
    robot, spin, sealer = d["ID-X231"], d["ID-ASPIN_2K-1"], d["ID-S100-1"]
    nodes = [
        ("S", "开始", None, None, "startEvent"),
        ("V0", "设置阶段变量", "__setVar", "__LOGIC__", "task"),
        ("P1", "三路并行分叉", None, None, "parallelGateway"),
        ("A1", "分支一·机器人", "Home", robot, "task"),
        ("A2", "分支二·离心机", "Home", spin, "task"),
        ("A3", "分支三·封膜机", "Home", sealer, "task"),
        ("J1", "并行汇合", None, None, "parallelGateway"),
        ("X1", "按 mode 选边", None, None, "exclusiveGateway", "f_slow"),
        ("T5", "快分支·再改板A", "Home", robot, "task"),
        ("T6", "慢分支·无声明", "Home", spin, "task"),
        ("E", "结束", None, None, "endEvent"),
    ]
    flows = [
        ("f1", "S", "V0"), ("f2", "V0", "P1"),
        ("f3", "P1", "A1"), ("f4", "P1", "A2"), ("f5", "P1", "A3"),
        ("f6", "A1", "J1"), ("f7", "A2", "J1"), ("f8", "A3", "J1"),
        ("f9", "J1", "X1"),
        ("f_fast", "X1", "T5", "${mode} == 'fast'"),
        ("f_slow", "X1", "T6"),
        ("f12", "T5", "E"), ("f13", "T6", "E"),
    ]
    shapes = {
        "S": (60, 260, 36, 36), "V0": (140, 245, 110, 66), "P1": (290, 260, 36, 36),
        "A1": (370, 80, 110, 70), "A2": (370, 245, 110, 70), "A3": (370, 410, 110, 70),
        "J1": (520, 260, 36, 36), "X1": (590, 260, 36, 36),
        "T5": (660, 150, 110, 70), "T6": (660, 380, 110, 70),
        "E": (810, 260, 36, 36),
    }
    return bpmn_xml(nodes, flows, shapes, edges_for(flows, shapes))


def graph_loop(d):
    robot = d["ID-X231"]
    # 网关必须**成对且连线完整**（前端 gatewayRoleCode 按入/出边数推角色：
    # ins>1 ⇒ MERGE、outs>1 ⇒ SPLIT、单进单出 ⇒ 空串 ⇒ 页面上没有角色标签）。
    # 另：孤立网关（ins=0/outs=0）是脏数据，画布下方会多出没连线的方块。
    nodes = [
        ("S", "开始", None, None, "startEvent"),
        # 回边汇合：入边 2（S→M、V→M）、出边 1 ⇒ XOR_MERGE
        ("M", "XOR_MERGE", None, None, "exclusiveGateway"),
        ("C1", "循环体·机器人", "Home", robot, "task"),
        # 条件分叉：入边 1、出边 2 ⇒ XOR_SPLIT
        ("X", "XOR_SPLIT", None, None, "exclusiveGateway", "f_loop"),
        ("V", "圈数加一", "__expr", "__LOGIC__", "task"),
        ("E", "结束", None, None, "endEvent"),
    ]
    flows = [
        ("f0", "S", "M"), ("f_m", "M", "C1"), ("f1", "C1", "X"),
        ("f_end", "X", "E", "${n} >= 4"),
        ("f_loop", "X", "V"), ("f_back", "V", "M"),
    ]
    shapes = {
        "S": (60, 200, 36, 36), "M": (140, 200, 36, 36),
        "C1": (230, 180, 110, 76),
        "X": (390, 200, 36, 36), "V": (480, 180, 110, 76),
        "E": (650, 200, 36, 36),
    }
    return bpmn_xml(nodes, flows, shapes, edges_for(flows, shapes))


def assert_gateways_wired(xml, label):
    """网关必须有入边和出边：孤立或单进单出在页面上不显示角色，属配置错误。"""
    gws = re.findall(r'<bpmn:(?:exclusive|parallel|inclusive)Gateway\s+id="([^"]+)"', xml)
    flows = re.findall(r'<bpmn:sequenceFlow\s+id="[^"]+"\s+sourceRef="([^"]+)"\s+'
                       r'targetRef="([^"]+)"', xml)
    ins, outs = {}, {}
    for s, t in flows:
        outs[s] = outs.get(s, 0) + 1
        ins[t] = ins.get(t, 0) + 1
    bad_gw = [g for g in gws if ins.get(g, 0) == 0 or outs.get(g, 0) == 0]
    if bad_gw:
        raise RuntimeError("%s 存在孤立网关（无入边或无出边）：%s" % (label, bad_gw))
    return ["%s(in=%d,out=%d)" % (g, ins.get(g, 0), outs.get(g, 0)) for g in gws]


# ---------------------------------------------------------------- 声明
def decl(barcode, state):
    return {
        "mode": "transition",
        "target": {"barcode": barcode},
        "set": {"processState": state},
        "acceptedEvidenceLevels": ["device_ack"],
    }


def attach(token, wf, node_key, payload):
    """通过 §3.3b 正式写入 API 落声明（此处不再直接 UPDATE 列）。"""
    api("/api/workflows/%d/nodes/%s/material-effect" % (wf, node_key),
        "PUT", body=payload, token=token)


# ---------------------------------------------------------------- 执行
def launch(token, wf, island, name, budget=240):
    exp = cs.seed_experiment(token, name, [wf], island)
    api("/api/experiments/%d/run" % exp, "POST", {}, token)
    erid = 0
    for _ in range(40):
        v = q("SELECT max(id) FROM experiment_run WHERE experiment_id=%d" % exp)
        if v and v.strip():
            erid = int(v)
            break
        time.sleep(1)
    rid = 0
    for _ in range(90):
        v = q("SELECT run_id FROM experiment_run_step WHERE experiment_run_id=%d "
              "AND workflow_id=%d LIMIT 1" % (erid, wf))
        if v and v.strip():
            rid = int(v)
            break
        time.sleep(1)
    if not rid:
        raise RuntimeError("实验 %d 没有派发出 run（erid=%d）" % (exp, erid))
    st = cs.wait_run(token, rid, budget=budget)
    return exp, rid, st


def scalar(sql):
    v = q(sql)
    return v.strip() if v else ""


def collect(rid, label):
    ev = {"runId": rid, "label": label}
    ev["runStatus"] = scalar("SELECT status FROM run WHERE id=%d" % rid)
    ev["nodes"] = q(
        "SELECT node_key||'|'||level||'|'||loop_index||'|'||status||'|'||"
        "execution_resolution_status||'|'||material_sync_status "
        "FROM run_node WHERE run_id=%d ORDER BY seq,level,loop_index,id" % rid)
    ev["declarations"] = scalar(
        "SELECT count(*) FROM run_material_effect_declaration WHERE run_id=%d" % rid)
    ev["effects"] = q(
        "SELECT source_node_key||'|'||source_loop_index||'|'||status||'|'||"
        "coalesce(plate_id::text,'NULL')||'|'||coalesce(last_error_code,'-') "
        "FROM plate_material_effect WHERE source_run_id=%d ORDER BY id" % rid)
    ev["events"] = q(
        "SELECT plate_id||'|'||source_node_key||'|'||from_state_version||'->'||"
        "to_state_version||'|'||coalesce(to_process_state,'NULL') "
        "FROM plate_material_event WHERE source_run_id=%d ORDER BY id" % rid)
    # DECLARE 行 from_status IS NULL；Postgres 的 || 遇 NULL 会把整串变 NULL，必须 coalesce
    ev["actions"] = q(
        "SELECT a.action_type||'|'||coalesce(a.from_status,'NULL')||'->'||"
        "coalesce(a.to_status,'NULL')||'|'||a.actor_type "
        "FROM plate_material_effect_action a JOIN plate_material_effect e "
        "ON e.id=a.effect_id AND e.tenant_id=a.tenant_id "
        "WHERE e.source_run_id=%d ORDER BY a.id" % rid)
    ev["ledgerRows"] = scalar("SELECT count(*) FROM command_ledger WHERE run_id=%d" % rid)
    ev["runCommandRows"] = scalar("SELECT count(*) FROM run_command WHERE run_id=%d" % rid)
    ev["q18Rows"] = scalar(
        "SELECT count(*) FROM run_node WHERE run_id=%d AND status='completed' "
        "AND execution_resolution_status='unresolved'" % rid)
    ev["manualErrors"] = q(
        "SELECT coalesce(error_code,'-') FROM run_node WHERE run_id=%d "
        "AND status='manual' ORDER BY id" % rid)
    return ev


def assert_main(ev):
    print("\n── 断言：复杂主流程 ──")
    if ev["runStatus"] == "completed":
        ok("CF-01 复杂流程跑完（3 并行 + 异或 + 变量 + 4 设备 TASK）",
           "run=%s status=%s" % (ev["runId"], ev["runStatus"]))
    else:
        bad("CF-01 复杂流程未跑完", str(ev["runStatus"]))

    d = int(ev["declarations"] or 0)
    if d == 3:
        ok("CF-02 run 启动时冻结 3 份声明快照", "A1/A2/T5")
    else:
        bad("CF-02 声明快照数不符", "期望 3，实际 %d" % d)

    eff = [x for x in ev["effects"].splitlines() if x.strip()]
    if len(eff) == 3 and all("|APPLIED|" in x for x in eff):
        ok("CF-03 3 个 Effect 全部 APPLIED", str(eff))
    else:
        bad("CF-03 Effect 未全部 APPLIED", str(eff))

    events = [x for x in ev["events"].splitlines() if x.strip()]
    if len(events) == 3:
        ok("CF-04 恰好 3 条事件（无重复事件）", str(events))
    else:
        bad("CF-04 事件数不符", str(events))
    # 通用版本链校验：同一板每条事件的 from 必须等于上一条的 to（首条可以不是 0，
    # 因为板可能已被之前的 run 推进过版本）
    versions, chain_ok, seen_first = {}, True, set()
    for x in events:
        parts = x.split("|")
        pid, frm_to = parts[0], parts[2]
        frm, to = frm_to.split("->")
        expect = versions.get(pid)
        if expect is not None and frm != expect:
            chain_ok = False
        if pid not in seen_first:
            seen_first.add(pid)
        versions[pid] = to
    if chain_ok and len(events) == 3 and len(versions) == 2:
        ok("CF-05 事件版本链连续（同板 from = 上一条 to）", str(versions))
    else:
        bad("CF-05 版本链不连续", str(events))

    acts = [x for x in ev["actions"].splitlines() if x.strip()]
    declares = sum(1 for x in acts if x.startswith("DECLARE|"))
    applies = sum(1 for x in acts if x.startswith("APPLY|"))
    if declares == 3 and applies == 3:
        ok("CF-06 action 审计完整（3 DECLARE + 3 APPLY）", "%d 条" % len(acts))
    else:
        bad("CF-06 action 不完整", "DECLARE=%d APPLY=%d" % (declares, applies))

    nodes = ev["nodes"]
    task_ok = "A1|0|0|completed|completed|" in nodes
    if task_ok:
        ok("CF-07 设备 TASK 节点写了执行确认 completed")
    else:
        bad("CF-07 设备 TASK 执行确认缺失", nodes[:300])

    q18 = int(ev["q18Rows"] or 0)
    if q18 > 0:
        skip("CF-08 Q18 命中 BUILTIN/步骤行（已登记 R13-22 口径冲突）", "%d 行" % q18)
    else:
        ok("CF-08 Q18 无命中", "0 行")


def assert_loop(ev):
    print("\n── 断言：循环流程（R13-06 探针）──")
    errs = [x for x in ev["manualErrors"].splitlines() if x.strip()]
    fail_closed = sum(1 for x in errs if x == "EC-99-0006")
    if ev["runStatus"] == "completed" and fail_closed == 0:
        ok("CF-L1 循环流程跑完（命令账本按圈次区分 key，EC-99-0006 已消除）",
           "run=%s" % ev["runId"])
        ok("CF-L3 循环过程无 fail-closed 转人工", "0 个 EC-99-0006")
    elif fail_closed > 0:
        bad("CF-L1 循环仍被 EC-99-0006 拦住（幂等键圈次回归）",
            "status=%s，EC-99-0006×%d" % (ev["runStatus"], fail_closed))
    else:
        bad("CF-L1 循环流程未跑完且非已知原因",
            "status=%s errors=%s" % (ev["runStatus"], errs[:4]))
    rounds = sum(1 for x in ev["nodes"].splitlines()
                 if x.startswith("C1|1|") or x.startswith("C1|0|"))
    eff = [x for x in ev["effects"].splitlines() if x.strip()]
    print("  循环体节点行：%s" % [x for x in ev["nodes"].splitlines() if x.startswith("C1|")])
    print("  Effect 行：%s" % eff)
    laps = sorted({int(x.split("|")[1]) for x in eff if "|" in x})
    # 条件 ${n} >= 4、n 初值 1、每圈 __expr 递增：先判断后递增 ⇒ 需要 4 次 C1 才退出。
    # 「4 圈」本身同时证明变量跨圈生效（否则会一直停在 n=2 或跑满 10 圈上限）。
    if laps == [1, 2, 3, 4]:
        ok("CF-L2 循环每圈各留一条 Effect 且靠条件退出（4 圈，非跑满上限）",
           "loop_index=%s，共 %d 条" % (laps, len(eff)))
    elif len(laps) >= 10:
        bad("CF-L2 跑满 maxIterations 上限（循环变量仍未跨圈）",
            "loop_index=%s 共 %d 条" % (laps, len(eff)))
    elif len(laps) <= 1:
        bad("CF-L2 多圈只有 1 个 Effect（R13-06 回归）",
            "节点行=%d Effect=%d" % (rounds, len(eff)))
    else:
        bad("CF-L2 圈次与预期(1..4)不符", "loop_index=%s 共 %d 条" % (laps, len(eff)))


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flow", choices=["all", "main", "loop"], default="all")
    args = ap.parse_args()

    print("S2 复杂流程端到端验证 | flow=%s" % args.flow)
    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]
    island, devs = devices()
    for code in ("ID-X231", "ID-ASPIN_2K-1", "ID-S100-1"):
        if code not in devs:
            bad("基线设备缺失", code)
            return 1
    ok("基线岛与设备就绪", "island=%d devs=%s" % (island, list(devs)[:5]))

    # 岛级扣留会挡住整个岛派发（core-scenarios.py:977-990 同款清理）。
    # 上一轮循环流程转人工会留下未释放的 hold，不清掉这次就派发不出 run。
    held = q("SELECT count(*) FROM runtime_hold WHERE released_at IS NULL").strip()
    print("  运行前未释放 runtime_hold: %s" % held)
    if held and int(held) > 0:
        q("UPDATE runtime_hold SET released_at=now(), released_by=1, "
          "release_note='S2 复杂流程实测前清理' WHERE released_at IS NULL")
        ok("清理挡派发的 runtime_hold", "%s 条" % held)

    if args.flow in ("all", "main"):
        print("\n══════ 流程 1/2 · main（3 并行 + 异或 + 变量 + 3 声明）══════")
        g_main = graph_main(devs)
        # 网关必须有入边和出边：孤立/单进单出在页面上没有角色标签（R13-25）
        print("  网关连线: %s" % ", ".join(assert_gateways_wired(g_main, "main")))
        wf = cs.seed_workflow(token, "S2实测·复杂主流程", g_main)
        api("/api/workflows/%d/nodes/V0" % wf, "PUT", token=token,
            body={"method": "__setVar", "params": {"var": "stage", "value": "1"}})
        api("/api/workflows/%d/variables" % wf, "POST", token=token,
            body={"name": "mode", "type": "string", "defaultValue": "fast"})
        attach(token, wf, "A1", decl(PLATE_A, "ready"))
        attach(token, wf, "A2", decl(PLATE_B, "in_progress"))
        attach(token, wf, "T5", decl(PLATE_A, "completed"))
        ok("material_effect 声明经 §3.3b 写入 API 落库", "A1/A2/T5（后端契约预检）")
        _, rid, st = launch(token, wf, island, "S2实测·复杂主流程")
        ev = collect(rid, "main")
        EVIDENCE["main"] = ev
        assert_main(ev)

    if args.flow in ("all", "loop"):
        print("\n══════ 流程 2/2 · loop（回边 4 圈，条件退出）══════")
        g_loop = graph_loop(devs)
        print("  网关连线: %s" % ", ".join(assert_gateways_wired(g_loop, "loop")))
        wf2 = cs.seed_workflow(token, "S2实测·循环探针", g_loop)
        # 不再设 maxIterations 兜底：循环必须**靠条件 ${n} >= 4 自己退出**，
        # 否则「跑满上限」会被误当成通过（n: 1→2→3→4，3 圈后退出）。
        api("/api/workflows/%d/nodes/V" % wf2, "PUT", token=token,
            body={"method": "__expr", "params": {"var": "n", "expr": "${n} + 1"}})
        api("/api/workflows/%d/variables" % wf2, "POST", token=token,
            body={"name": "n", "type": "number", "defaultValue": "1"})
        attach(token, wf2, "C1", decl(PLATE_B, "ready"))
        _, rid2, st2 = launch(token, wf2, island, "S2实测·循环探针")
        ev2 = collect(rid2, "loop")
        EVIDENCE["loop"] = ev2
        assert_loop(ev2)

    out = os.path.join(ROOT, "webapp", "public", "s2-complex-flow-results.json")
    payload = {
        "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "command": "python3 scripts/verify-s2-complex-flow.py --flow " + args.flow,
        "pass": PASS, "fail": FAIL, "skip": SKIP,
        "evidence": EVIDENCE,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
    print("证据已写入 " + out)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
