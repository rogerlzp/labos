#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P-01 只读盘点：workflow 的「岛归属」现在到底存在哪里、指向什么。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/workflow_island_inventory.py [--json]

⚠ 本脚本**只读**：全部语句都是 SELECT，不 INSERT/UPDATE/DELETE、不建表。
   它是 ADR-ISLAND-001/005 与 P-01 要求的「先出只读盘点，不自动推断、
   不破坏性迁移」的落点 —— 在搞清楚 id 空间之前不许加 FK、不许回填。
"""
import argparse
import json
import os
import sys

import psycopg2


def dsn():
    missing = [k for k in ("LABOS_DB_HOST", "LABOS_DB_NAME",
                           "LABOS_DB_USER", "LABOS_DB_PASS")
               if not os.environ.get(k)]
    if missing:
        print("缺 LABOS_DB_* 环境变量：" + ", ".join(missing))
        sys.exit(2)
    return ("host={LABOS_DB_HOST} port={port} dbname={LABOS_DB_NAME} "
            "user={LABOS_DB_USER} password={LABOS_DB_PASS}").format(
                port=os.environ.get("LABOS_DB_PORT", "5432"), **os.environ)


TENANT = 1

QUERIES = {
    # ① workflow 总量与 attr 里 island 键的形态
    "workflow_attr": """
        SELECT count(*) AS total,
               count(*) FILTER (WHERE attr ? 'island_id') AS has_key,
               count(*) FILTER (WHERE attr ? 'islandId') AS has_camel_key,
               count(*) FILTER (WHERE NOT (attr ? 'island_id')) AS no_key,
               count(*) FILTER (WHERE attr->>'island_id' IS NULL
                                  OR attr->>'island_id' = '') AS no_value,
               count(*) FILTER (WHERE (attr->>'island_id') ~ '^[0-9]+$') AS numeric_value
          FROM workflow WHERE tenant_id = %s AND deleted_at IS NULL
    """,
    # ② attr.island_id 的数值是不是合法的 resource_node 岛 id？
    "attr_vs_resource_node": """
        SELECT count(*) AS numeric_rows,
               count(*) FILTER (WHERE EXISTS (
                   SELECT 1 FROM resource_node r
                    WHERE r.id = (w.attr->>'island_id')::bigint
                      AND r.kind = 'island')) AS resolves_to_island,
               count(*) FILTER (WHERE NOT EXISTS (
                   SELECT 1 FROM resource_node r
                    WHERE r.id = (w.attr->>'island_id')::bigint
                      AND r.kind = 'island')) AS dangling
          FROM workflow w
         WHERE w.tenant_id = %s AND w.deleted_at IS NULL
           AND (w.attr->>'island_id') ~ '^[0-9]+$'
    """,
    # ③ attr 里出现过的 island 值（看是不是另一套 id 空间）
    "attr_values": """
        SELECT (attr->>'island_id') AS island_id, count(*) AS n
          FROM workflow WHERE tenant_id = %s AND deleted_at IS NULL
         GROUP BY 1 ORDER BY n DESC LIMIT 20
    """,
    # ④ 权威资源树里的岛 id（对照用）
    "resource_node_islands": """
        SELECT id, code, name FROM resource_node
         WHERE tenant_id = %s AND kind = 'island' AND deleted_at IS NULL
         ORDER BY id LIMIT 20
    """,
    # ⑤ experiment.island_id（另一处「岛」字段，口径是否一致）
    "experiment_island": """
        SELECT island_id, count(*) AS n FROM experiment
         WHERE tenant_id = %s GROUP BY 1 ORDER BY n DESC LIMIT 20
    """,
    # ⑥ 流程节点上实际绑定的设备，属于哪个岛（与 attr 声明值是否一致）
    "bound_device_vs_declared": """
        WITH b AS (
          SELECT w.id AS wf, (w.attr->>'island_id')::bigint AS declared,
                 d.parent_id AS actual, count(*) AS n
            FROM workflow w
            JOIN workflow_node n ON n.workflow_id = w.id AND n.tenant_id = w.tenant_id
            JOIN resource_node d ON d.id = n.resource_id
           WHERE w.tenant_id = %s AND w.deleted_at IS NULL
             AND (w.attr->>'island_id') ~ '^[0-9]+$'
           GROUP BY 1, 2, 3)
        SELECT count(*) AS pairs,
               count(*) FILTER (WHERE declared = actual) AS same_island,
               count(*) FILTER (WHERE declared <> actual) AS cross_island,
               coalesce(sum(n) FILTER (WHERE declared <> actual), 0) AS cross_nodes
          FROM b
    """,
    # ⑦ 候选映射（**只读推断，不许据此回填**）：历史值 + 100000 能否对上权威岛 id
    "candidate_offset_mapping": """
        SELECT count(*) AS numeric_rows,
               count(*) FILTER (WHERE EXISTS (
                   SELECT 1 FROM resource_node r
                    WHERE r.kind = 'island'
                      AND r.id = (w.attr->>'island_id')::bigint + 100000)) AS maps_plus_100000,
               count(*) FILTER (WHERE NOT EXISTS (
                   SELECT 1 FROM resource_node r
                    WHERE r.kind = 'island'
                      AND r.id = (w.attr->>'island_id')::bigint + 100000)) AS still_unmapped
          FROM workflow w
         WHERE w.tenant_id = %s AND w.deleted_at IS NULL
           AND (w.attr->>'island_id') ~ '^[0-9]+$'
    """,
    # ⑦ 有 equipment/capability 绑定但没有岛声明的流程数（"回落全部岛"的影响面）
    "no_island_with_bindings": """
        SELECT count(DISTINCT w.id) AS workflows
          FROM workflow w
          JOIN workflow_node n ON n.workflow_id = w.id AND n.tenant_id = w.tenant_id
         WHERE w.tenant_id = %s AND w.deleted_at IS NULL
           AND n.resource_id IS NOT NULL
           AND (w.attr->>'island_id' IS NULL OR w.attr->>'island_id' = '')
    """,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true", help="只输出 JSON（便于机器消费）")
    args = ap.parse_args()
    conn = psycopg2.connect(dsn())
    out = {}
    try:
        for name, sql in QUERIES.items():
            cur = conn.cursor()
            cur.execute(sql, (TENANT,))
            rows = cur.fetchall()
            cols = [d[0] for d in cur.description]
            out[name] = [dict(zip(cols, r)) for r in rows]
            cur.close()
    finally:
        conn.close()

    if args.json:
        print(json.dumps(out, ensure_ascii=False, indent=2, default=str))
        return

    a = out["workflow_attr"][0]
    v = out["attr_vs_resource_node"][0]
    b = out["bound_device_vs_declared"][0]
    print("══ P-01 只读盘点 · workflow 岛归属（不做任何写入）══\n")
    print("① workflow 共 %(total)s 张：attr 有 island_id 键 %(has_key)s，"
          "无键 %(no_key)s，键在但为空 %(no_value)s，数值型 %(numeric_value)s"
          % a)
    print("② 数值型 %(numeric_rows)s 条里，能解析到 resource_node(kind='island') "
          "的 %(resolves_to_island)s 条，**悬空 %(dangling)s 条**" % v)
    print("③ attr.island_id 取值 Top：")
    for r in out["attr_values"][:8]:
        print("     %-10s %s 条" % (r["island_id"] if r["island_id"] else "(空)",
                                   r["n"]))
    print("④ resource_node 里的岛 id（权威口径）：")
    for r in out["resource_node_islands"][:8]:
        print("     %-10s %-12s %s" % (r["id"], r["code"], r["name"]))
    print("⑤ experiment.island_id 取值：")
    for r in out["experiment_island"][:6]:
        print("     %-10s %s 条" % (r["island_id"] if r["island_id"] else "(空)",
                                   r["n"]))
    print("⑥ 绑定了设备的流程：声明岛与设备父岛相同的 %(same_island)s 对，"
          "跨岛 %(cross_island)s 对（涉及 %(cross_nodes)s 个节点）" % b)
    print("⑦ 有设备绑定却没有岛声明的流程：%s 张（现在走「回落全部岛」）"
          % out["no_island_with_bindings"][0]["workflows"])
    c = out["candidate_offset_mapping"][0]
    print("⑧ 【候选、未应用】历史值 +100000 能对上 resource_node 岛 id 的："
          "%(maps_plus_100000)s / %(numeric_rows)s 条，仍对不上 %(still_unmapped)s 条" % c)
    print("   这只是给决策用的证据，本脚本和 P-01 都不得据此自动回填/FK。")


if __name__ == "__main__":
    main()
