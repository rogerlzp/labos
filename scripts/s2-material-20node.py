#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给「页面批量-20节点复杂流程」(wf=1000830) 挂物料效果声明，重跑后验证板状态流转可追溯。

对应 private/实际流程/物料视角-NGS流程-20节点简化与物料映射.md 三.2 的 6 条声明：
  节点4 质粒稀释  → PCR-稀释质粒  processState=in_progress
  节点6 封膜      → PCR-稀释质粒  sealState=sealed
  节点8 PCR扩增   → PCR-稀释质粒  processState=completed
  节点10 浓度检测 → PCR-扩增产物  processState=completed
  节点16 封膜     → PCR-检测样品  sealState=sealed
  节点18 Elisa孵育→ PCR-检测样品  processState=completed

跑完后证据：run_material_effect_declaration(快照) + plate_material_effect(APPLIED)
+ plate_material_event(版本链) + plate_material_effect_action(DECLARE/APPLY)。

用法：python3 scripts/s2-material-20node.py [--workflow 1000830] [--no-run]
"""
import argparse
import importlib.util
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "core_scenarios", os.path.join(ROOT, "scripts", "core-scenarios.py"))
cs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cs)
api, q = cs.api, cs.q

USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

# 板：barcode / plate_type / 初始 process_state（对齐映射文档 三.1）
PLATES = [
    ("DWP-质粒母液", "deep96", "ready"),
    ("PCR-稀释质粒", "pcr96", "raw"),
    ("PCR-扩增产物", "pcr96", "raw"),
    ("PCR-检测样品", "pcr96", "raw"),
    ("ELISA-检测", "elisa96", "raw"),
]

# 声明：(node_key, barcode, processState, sealState)
DECLS = [
    ("Activity_05av8e1", "PCR-稀释质粒", "in_progress", None),  # 节点4 质粒稀释
    ("Activity_0pzrtta", "PCR-稀释质粒", None, "sealed"),       # 节点6 封膜
    ("Activity_02cvemm", "PCR-稀释质粒", "completed", None),    # 节点8 PCR扩增
    ("Activity_0g40ih6", "PCR-扩增产物", "completed", None),    # 节点10 浓度检测
    ("Activity_12jr4do", "PCR-检测样品", None, "sealed"),       # 节点16 封膜
    ("Activity_0oed6kj", "PCR-检测样品", "completed", None),    # 节点18 Elisa孵育
]


def create_plates():
    n = 0
    for barcode, ptype, pstate in PLATES:
        # 用 ON CONFLICT DO NOTHING 而不是「先 SELECT 再裸 INSERT」：后者在并发/重跑
        # 时会在 uk_plate_barcode 上撞唯一键（TOCTOU）。DO NOTHING 命中时
        # RETURNING 返回 0 行 ⇒ pid 为空串，表示板已存在，不是失败。
        pid = q(f"INSERT INTO plate (tenant_id, barcode, plate_type, process_state) "
                f"VALUES (1, '{barcode}', '{ptype}', '{pstate}') "
                f"ON CONFLICT (tenant_id, barcode) DO NOTHING RETURNING id")
        if pid:
            print(f"  建板: {barcode} (id={pid}, {ptype}, {pstate})")
            n += 1
        else:
            print(f"  板已存在: {barcode}")
    return n


def attach_decls(token, wf):
    for node_key, barcode, pstate, sstate in DECLS:
        set_ = {}
        if pstate:
            set_["processState"] = pstate
        if sstate:
            set_["sealState"] = sstate
        body = {
            "mode": "transition",
            "target": {"barcode": barcode},
            "set": set_,
            "acceptedEvidenceLevels": ["device_ack"],
        }
        api(f"/api/workflows/{wf}/nodes/{node_key}/material-effect",
            "PUT", body=body, token=token)
        print(f"  声明已挂: {node_key:16s} -> {barcode} {set_}")


def run_flow(token, wf):
    r = api(f"/api/workflows/{wf}/run?async=1", "POST", token=token)
    return int(r["runId"])


def wait_terminal(rid, token, budget=180):
    # 内核开了 LABOS_AUTH=1，不带 token 轮询会 401（run 其实已经在跑）。
    for _ in range(budget // 2):
        time.sleep(2)
        st = api(f"/api/runs/{rid}", token=token)["run"]["status"]
        if st not in ("running", "queued", "pending", "planned", "starting"):
            return st
    return "TIMEOUT"


def collect(rid):
    print("\n=== 证据（run %d）===" % rid)
    print("[声明快照 run_material_effect_declaration]")
    # 表名是 run_material_effect_declaration，列名是 declaration（不是 payload）。
    rows = q("SELECT node_key||' -> '||coalesce((declaration->'target'->>'barcode'),'?') "
             "FROM run_material_effect_declaration WHERE run_id=%d ORDER BY id" % rid)
    print("  " + ("\n  ".join(rows.splitlines()) if rows else "(空)"))

    print("[效果 plate_material_effect]")
    rows = q("SELECT source_node_key||' | loop='||source_loop_index||' | '||status||' | '||"
             "coalesce(plate_id::text,'NULL')||' | '||coalesce(last_error_code,'-') "
             "FROM plate_material_effect WHERE source_run_id=%d ORDER BY id" % rid)
    print("  " + ("\n  ".join(rows.splitlines()) if rows else "(空)"))

    print("[事件 plate_material_event（版本链）]")
    rows = q("""SELECT plate_id||' | '||source_node_key||' | '||coalesce(from_process_state,'-')||
           '->'||coalesce(to_process_state,'-')||' | seal '||coalesce(from_seal_state,'-')||
           '->'||coalesce(to_seal_state,'-')||' | v'||from_state_version||'->v'||to_state_version
           FROM plate_material_event WHERE source_run_id=%d ORDER BY id""" % rid)
    print("  " + ("\n  ".join(rows.splitlines()) if rows else "(空)"))

    print("[动作 plate_material_effect_action]")
    rows = q("""SELECT a.action_type||' | '||coalesce(a.from_status,'NULL')||'->'||
           coalesce(a.to_status,'NULL')||' | '||a.actor_type
           FROM plate_material_effect_action a JOIN plate_material_effect e
           ON e.id=a.effect_id AND e.tenant_id=a.tenant_id
           WHERE e.source_run_id=%d ORDER BY a.id""" % rid)
    print("  " + ("\n  ".join(rows.splitlines()) if rows else "(空)"))

    print("[板最终状态]")
    rows = q("SELECT barcode||' | '||coalesce(process_state,'-')||' | '||"
             "coalesce(seal_state,'-')||' | v'||state_version "
             "FROM plate WHERE barcode IN ('DWP-质粒母液','PCR-稀释质粒','PCR-扩增产物',"
             "'PCR-检测样品','ELISA-检测') ORDER BY barcode")
    print("  " + ("\n  ".join(rows.splitlines()) if rows else "(空)"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow", type=int, default=1000830)
    ap.add_argument("--no-run", action="store_true", help="只建板挂声明，不跑")
    args = ap.parse_args()

    token = api("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    print("=== 1. 建板 ===")
    create_plates()
    print("=== 2. 挂物料声明（wf=%d）===" % args.workflow)
    attach_decls(token, args.workflow)

    if args.no_run:
        print("（--no-run，跳过执行）")
        return 0

    print("=== 3. 跑 ===")
    rid = run_flow(token, args.workflow)
    st = wait_terminal(rid, token)
    print("run %d -> %s" % (rid, st))

    collect(rid)
    print("\n完成。板状态流转见上面 plate_material_event / plate 最终状态。")
    return 0 if st == "completed" else 1


if __name__ == "__main__":
    sys.exit(main())
