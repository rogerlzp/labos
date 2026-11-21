#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单样品板流程的**板位静态推演**（起跑前自查，跑之前就知道板号/板位对不对）。

**为什么要有这个脚本（2026-11-19 实测）**
板链里最容易错的是「哪块板在哪个位置」：
① 「取回上一块板」搬的是**上一阶段留在交互位的那块**，写成新板 ⇒ 扫码证据按
   **位置账本**比对时对不上 ⇒ EC-SCAN-MISMATCH（实测 run 1001819 节点 Activity_0029）；
② 同一块板两次占用同一位（没让出交互位）⇒ EC-POSITION-OCCUPIED（实测 run 1001818）；
③ 目标位没腾空就放板 ⇒ 同样是 EC-POSITION-OCCUPIED。
这三种都要跑十几分钟才暴露 ⇒ **起跑前先在纸上走一遍**。

**推演口径**
  · 账本 = 「点位 → 板号」；
  · 每条 Transfer 节点：源位上的板必须等于节点声明的板号；目标位必须为空；
    然后把板从源位挪到目标位；
  · 设备动作（PickTipComb / DropTipComb / Bind / Wash / Elute）**不改板位** ——
    平台没有「转盘切换（操作位↔交互位换板）」这个动作（平台缺口，见实测报告）；
  · 每块板的**初始位** = 它在流程里第一次出现的那条 Transfer 的源位
    （并要求那个源位是**耗材栈**位 —— 板只能从耗材栈起手）。

用法：
    python3 scripts/verify-extraction-plate-chain.py            # 推演并打印判定
    python3 scripts/verify-extraction-plate-chain.py --strict   # 有问题就退出码 1
"""
import argparse
import json
import os
import subprocess
import sys

DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_USER_DB", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")
WF_CODE = os.environ.get("LABOS_WF_CODE", "EXT-WORKFLOW-SINGLE")
PSQL = os.environ.get("LABOS_PSQL", "psql")

SQL_NODES = (
    "SELECT json_agg(json_build_array(node_key, name, "
    "params->'method_params'->>'sourcePosition', "
    "params->'method_params'->>'targetPosition', "
    "params->'method_params'->>'barcode', params->>'protocol_name') ORDER BY id) "
    "FROM workflow_node WHERE workflow_id = "
    "(SELECT id FROM workflow WHERE code = '" + WF_CODE + "') AND node_type = 'task'"
)
SQL_VARS = (
    "SELECT json_agg(json_build_array(name, default_value)) FROM workflow_variable "
    "WHERE workflow_id = (SELECT id FROM workflow WHERE code = '" + WF_CODE + "')"
)


def q(sql):
    p = subprocess.run([PSQL, "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
                        "-A", "-t", "-c", sql], capture_output=True, text=True,
                       env=dict(os.environ, PGPASSWORD=DB_PASS))
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    out = p.stdout.strip()
    return json.loads(out) if out else []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true",
                    help="有问题就退出码 1（供 CI / 起跑前卡口用）")
    args = ap.parse_args()

    vm = {a: b for a, b in (q(SQL_VARS) or [])}
    rows = q(SQL_NODES) or []
    transfers = []
    for nk, name, sp, tp, bc, proto in rows:
        if proto != "Transfer":
            continue
        barcode = (bc or "").strip()
        if barcode.startswith("$") and barcode.endswith("}"):
            barcode = vm.get(barcode[2:-1], barcode)
        transfers.append((nk, name or "", sp, tp, barcode))
    if not transfers:
        sys.exit("✘ 流程 %s 里没有 Transfer 节点" % WF_CODE)

    # 每块板的初始位 = 它第一次出现的那条 Transfer 的源位（且必须是耗材栈位）
    home = {}
    for nk, name, sp, tp, bc in transfers:
        if bc not in home:
            home[bc] = sp
    ledger = {}
    bad = 0
    for bc, pos in home.items():
        if not (pos or "").startswith("STACK"):
            print("✘ %s 的起手位是 %s —— 板只能从耗材栈起手" % (bc, pos))
            bad += 1
            continue
        ledger[pos] = bc

    print("%-13s %-42s %-24s %-24s %s" % ("节点", "名称", "源位→板", "目标位", "判定"))
    for nk, name, sp, tp, bc in transfers:
        src_plate = ledger.get(sp)
        if src_plate != bc:
            print("✘ %s %s：源位上是 %s，节点声明 %s"
                  % (nk, name[:36], src_plate or "空", bc))
            bad += 1
        elif tp in ledger:
            print("✘ %s %s：目标位 %s 已被 %s 占用"
                  % (nk, name[:36], tp, ledger[tp]))
            bad += 1
        else:
            print("✔ %-13s %-42s %-24s %-24s OK"
                  % (nk, name[:40], "%s → %s" % (sp, src_plate or "空"),
                     "%s → 空" % tp))
        ledger.pop(sp, None)
        ledger[tp] = bc

    print()
    print("终态账本：")
    for pos in sorted(ledger):
        print("   %-14s %s" % (pos, ledger[pos]))
    print()
    if bad:
        print("✘ 发现 %d 处板号/板位问题（起跑前必须修）" % bad)
        sys.exit(1 if args.strict else 0)
    print("✔ 板链推演通过：%d 次搬运，板号与板位全程自洽" % len(transfers))


if __name__ == "__main__":
    main()
