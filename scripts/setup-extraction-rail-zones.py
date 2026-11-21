#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给提取岛（LAB-EXT-02）配置**搬运路径 → 轨道分区集**。

**为什么需要这个脚本（2026-11-19 实测）**
轨道分区守卫（RailZoneGuard）是 fail-closed 的：搬运路径没有登记轨道分区集、
也没有登记豁免 ⇒ 搬运节点直接 EC-INTERLOCK-RAIL 转人工：
    「搬运路径未配置轨道分区集且无豁免，禁止自动运行（fail-closed）」
而路径键是**位置对**（"<源位置 resourceId>-><目标位置 resourceId>"），
不是设备对 ⇒ 换一批板位就换一批路径键，**上一轮登记的路径对新板位无效**。
（实测：上一轮 8 条路径是 STACK1-S09~S12 ↔ P_AUTO-N-EXT；
  单样品板流程改用 STACK1-S01/S13/S25/S33/S41/S49 ⇒ 全部未登记 ⇒ 第一段搬运就被拦。）

**口径**
  路径键 = 流程里每个 Transfer 节点的 sourcePosition → targetPosition（按位置码解析成 resourceId）。
  轨道分区 = 机械臂自己的轨道分区（RAIL-ARM-02-1）—— 同一时刻只允许一条搬运占用它。

用法：
    python3 scripts/setup-extraction-rail-zones.py            # 只看会补哪些（dry-run）
    python3 scripts/setup-extraction-rail-zones.py --apply    # 真写
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from extraction_config import ISLAND_CODE  # noqa: E402

DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_USER_DB", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")
WF_CODE = os.environ.get("LABOS_WF_CODE", "EXT-WORKFLOW-SINGLE")
RAIL_ZONE_CODE = os.environ.get("LABOS_RAIL_ZONE", "RAIL-ARM-02-1")


def psql(sql):
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
         "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return [ln for ln in p.stdout.strip().splitlines() if ln]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    rows = psql(
        "SELECT id FROM workflow w WHERE code = '%s' LIMIT 1" % WF_CODE)
    if not rows:
        sys.exit("✘ 找不到流程 %s" % WF_CODE)
    wf_id = rows[0]

    zone = psql("SELECT resource_id FROM rail_zone WHERE zone_code = '%s' LIMIT 1"
                % RAIL_ZONE_CODE)
    if not zone:
        sys.exit("✘ 找不到轨道分区 %s" % RAIL_ZONE_CODE)
    zone_id = int(zone[0])

    pairs = psql(
        "SELECT DISTINCT params->'method_params'->>'sourcePosition',"
        "       params->'method_params'->>'targetPosition'"
        "  FROM workflow_node"
        " WHERE workflow_id = %s"
        "   AND params->>'protocol_name' = 'Transfer'"
        "   AND params->'method_params'->>'sourcePosition' IS NOT NULL"
        "   AND params->'method_params'->>'targetPosition' IS NOT NULL"
        " ORDER BY 1, 2" % wf_id)

    # psql -A -t 的列分隔符是 '|' ⇒ 一行两列
    pairs = [(ln.split("|")[0], ln.split("|")[1]) for ln in pairs
             if ln.count("|") == 1]
    ids = {}
    for code in sorted({c for pr in pairs for c in pr if c}):
        r = psql("SELECT id FROM resource_node WHERE tenant_id = 1 AND code = '%s'"
                 % code)
        if not r:
            sys.exit("✘ 找不到位置 %s" % code)
        ids[code] = int(r[0])

    print("流程 %s：%d 条搬运路径；轨道分区 %s（resource %d）"
          % (WF_CODE, len(pairs), RAIL_ZONE_CODE, zone_id))
    todo = []
    for src, dst in pairs:
        path_key = "%d->%d" % (ids[src], ids[dst])
        have = psql("SELECT id FROM transfer_rail_zone"
                    " WHERE tenant_id = 1 AND path_key = '%s'" % path_key)
        mark = "✓ 已登记" if have else "＋ 待补"
        print("  %s  %-18s -> %-18s  %s" % (mark, src, dst, path_key))
        if not have:
            todo.append(path_key)

    if not todo:
        print("\n全部路径已登记，无需改动。")
        return
    if not args.apply:
        print("\n（dry-run：%d 条待补，加 --apply 才写）" % len(todo))
        return
    for pk in todo:
        psql("INSERT INTO transfer_rail_zone"
             " (tenant_id, path_key, zone_resource_id, seq)"
             " VALUES (1, '%s', %d, 1)" % (pk, zone_id))
    print("\n✔ 已补 %d 条路径的轨道分区登记" % len(todo))


if __name__ == "__main__":
    main()
