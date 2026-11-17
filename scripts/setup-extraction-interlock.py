#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取线的**互锁配置**：轨道分区（rail_zone）+ 搬运路径→分区（transfer_rail_zone）。

**为什么必须有这一步**（2026-11-17 从零建岛实测）：
新岛建好后第一次跑流程 ⇒ 机械臂一搬板就报 `EC-INTERLOCK-RAIL` ⇒ 节点转人工。
原因：**轨道分区互锁是 fail-closed** —— 路径没配分区集也没配豁免 ⇒ 直接转人工。
（老岛有配置，新岛没有；这个配置既无 UI 也无 API ⇒ 只能脚本落库。）

做法：**从本岛流程图里把搬运路径抠出来**（每个搬运节点的 sourcePosition → targetPosition），
给机械臂建一个轨道分区，再把这些路径挂到该分区上（正反两个方向都配）。
⇒ 路径跟着流程走，不抄老岛 ⇒ 流程改了重跑本脚本即可。

用法：
    LABOS_ISLAND_CODE=LAB-EXT-02 LABOS_DEV_TAG=-02- LABOS_WF_CODE=EXT-WORKFLOW-02 \\
    python3 scripts/setup-extraction-interlock.py
"""

import html
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import ISLAND_CODE, arm_code  # noqa: E402

HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
PORT = os.environ.get("LABOS_DB_PORT", "5432")
NAME = os.environ.get("LABOS_DB_NAME", "labdb")
USER = os.environ.get("LABOS_DB_USER", "labos")
PASS = os.environ.get("LABOS_DB_PASS", os.environ.get("LABOS_DB_PASSWORD", ""))
SEED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "seed-p2-interlock.py")
WF_CODE = os.environ.get("LABOS_WF_CODE", "EXT-WORKFLOW-01")


def main():
    import psycopg2

    print("=" * 68)
    print(f"{ISLAND_CODE} · 互锁配置（轨道分区 + 搬运路径→分区）")
    print("=" * 68)
    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    cur = conn.cursor()

    # ---- 1. 本岛的机械臂 + 本岛流程
    cur.execute("SELECT id FROM resource_node WHERE tenant_id=1 AND kind='device' AND code=%s",
                (arm_code(),))
    row = cur.fetchone()
    if not row:
        print(f"✘ 本岛找不到机械臂 {arm_code()}")
        sys.exit(1)
    arm_res = row[0]
    cur.execute("SELECT id, graph->'xml' FROM workflow WHERE tenant_id=1 AND code=%s", (WF_CODE,))
    row = cur.fetchone()
    if not row:
        print(f"✘ 找不到流程 {WF_CODE}")
        sys.exit(1)
    wf_id, xml = row
    print(f"✔ 机械臂 {arm_code()}（res={arm_res}）· 流程 {WF_CODE}（id={wf_id}）")

    # ---- 2. 从流程图抠搬运路径（逐个节点解析；别把两个列表 zip —— 数量不等会错位）
    paths = set()
    for m in re.finditer(r'<bpmn:task\b[^>]*methodParams="([^"]*)"', xml):
        raw = html.unescape(m.group(1))
        try:
            d = json.loads(raw)
        except Exception:
            continue
        if not isinstance(d, dict):
            continue
        s, t = d.get("sourcePosition"), d.get("targetPosition")
        if s and t:
            paths.add((s, t))
    print(f"✔ 流程里的搬运路径 {len(paths)} 条")
    if not paths:
        print("✘ 没抠到搬运路径 —— 流程图的 methodParams 里没有 sourcePosition/targetPosition")
        sys.exit(1)

    # ---- 3. 位置码 → 资源 id
    cur.execute("SELECT id, code FROM resource_node WHERE tenant_id=1")
    by_code = {c: i for i, c in cur.fetchall()}
    path_keys = []
    for s, t in sorted(paths):
        if s not in by_code or t not in by_code:
            print(f"  ! 跳过（点位不存在）：{s} -> {t}")
            continue
        path_keys.append(f"{by_code[s]}->{by_code[t]}")
        path_keys.append(f"{by_code[t]}->{by_code[s]}")
    path_keys = sorted(set(path_keys))
    print(f"✔ 可用路径（含反向）{len(path_keys)} 条")

    # ---- 4. 新建轨道分区（虚拟资源）
    cur.execute("SELECT max(id) FROM resource_node")
    zone_res = (cur.fetchone()[0] or 0) + 1
    zone_code = f"RAIL-{arm_code()}"
    print(f"✔ 新建轨道分区 {zone_code}（虚拟资源 res={zone_res}）")

    cfg = {
        "tenant_id": 1,
        "rail_zones": [{"code": zone_code, "resource_id": zone_res,
                        "name": f"轨道分区 · {arm_code()}"}],
        "transfer_rail_zones": [{"path_key": pk, "zones": [zone_res]}
                                 for pk in path_keys],
        "rail_zone_exemptions": [],
        "stirrer_dispenser_pairs": [],
    }
    tmp = f"/tmp/interlock-{ISLAND_CODE}.json"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f"✔ 配置已写：{tmp}")
    conn.close()

    print()
    print("── 落库（scripts/seed-p2-interlock.py）──")
    rc = subprocess.run([sys.executable, SEED, tmp]).returncode
    if rc != 0:
        sys.exit(rc)
    print()
    print(f"✔ {ISLAND_CODE} 的轨道互锁已就绪")


if __name__ == "__main__":
    main()
