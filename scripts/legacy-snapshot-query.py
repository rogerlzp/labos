#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""旧基线备份（`legacy_snapshot` 表）的**只读查询**入口。

这张表是 2026-11-11 基线重建前的结构快照：流程族 + 实验族 + 岛与资源树，
共约 3.7 万行（运行流水按约定**不备份** —— 12 万 + 11.7 万行，量巨大且
事后排查价值低于存储成本）。

**为什么是"查询"而不是"恢复"**：
2026-11-11 实测盘过备份里的 1076 条流程 —— `category='experiment'` 991 条
（实验自动生成的副本）、`choreography` 83 条、`test` 1 条、`error_handling` 1 条；
那 83 条 choreography 逐条看过，**全部是旧门禁的测试夹具**
（MTRX-* 矩阵用例、E2E-*、演示岛编排、示例8、verify-并行分支、人工验收-*），
**没有一条是真实生产工艺**。
⇒ 批量恢复旧流程**没有价值**（恢复回来还是一堆跑不通的旧用例）。
⇒ 本脚本只提供**查询**：想查"当年那条流程长什么样、绑了哪台设备"时能查到，
而不是让备份躺在表里没人知道它能干什么。

**真要恢复某一条流程时怎么办**（一次性、按需）：
`legacy_snapshot` 里 `entity='workflow'` 的行是整行 JSON，
把 `payload` 反插回 `workflow` 表，再把 `entity='workflow_node'` /
`'workflow_edge'` 里 `payload->>'workflow_id'` 等于该 id 的行反插回去即可。
**注意**节点上的 `params->>'bound_resource_id'` 指向的是**旧岛里的资源 id**，
而那些资源节点在 `entity='resource_node'` 里 —— 要一起恢复，且新岛里的设备
id 与旧的不同 ⇒ 恢复后必须重新绑定设备，否则节点会被判"设备不属于本岛"而拒绝执行。

用法：
  # 看各类备份了多少行
  python3 scripts/legacy-snapshot-query.py --stats

  # 按关键词搜流程
  python3 scripts/legacy-snapshot-query.py --entity workflow --name 孵育

  # 看某条流程的完整节点与连线（默认 id=1000546，可用 --id 换）
  python3 scripts/legacy-snapshot-query.py --entity workflow --id 1000546 --detail

  # 导出某条流程的 BPMN XML（可直接 PUT 回 /api/workflows/{id}/graph）
  python3 scripts/legacy-snapshot-query.py --entity workflow --id 1000546 --bpmn
"""
import argparse
import json
import os
import subprocess
import sys

PASS, FAIL = 0, 0


def q(sql):
    """跑一条 SQL。**失败必须抛错**（返回空串会被当成"库里没有" ⇒ 假绿）。"""
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql.strip()[:200], out.stderr.strip()[:300]))
    return out.stdout.strip()


def rows(sql):
    out = q(sql)
    return [ln for ln in out.split("\n") if ln.strip()]


def cmd_stats():
    print("── 备份统计（entity → 行数）──")
    for ln in rows("SELECT entity || '|' || count(*) FROM legacy_snapshot "
                   "GROUP BY entity ORDER BY count(*) DESC, entity"):
        e, n = ln.rsplit("|", 1)
        print("  %-26s %s" % (e, n))
    meta = q("SELECT payload->>'note' FROM legacy_snapshot WHERE entity='__meta__'")
    if meta:
        print("\n  说明：%s" % meta)


def cmd_search(entity, name):
    print("── 备份里名字含 %r 的 %s ──" % (name, entity))
    hit = 0
    for ln in rows("SELECT id || '|' || coalesce(payload->>'name','') "
                   "FROM legacy_snapshot WHERE entity='%s' "
                   "AND payload->>'name' ILIKE '%%%s%%' ORDER BY id LIMIT 50" % (entity, name)):
        rid, nm = ln.split("|", 1)
        print("  id=%-10s %s" % (rid, nm))
        hit += 1
    if not hit:
        print("  （没有匹配）")


def cmd_detail(entity, rid):
    row = q("SELECT payload::text FROM legacy_snapshot WHERE entity='%s' AND row_id=%s"
            % (entity, rid))
    if not row:
        print("备份里没有 %s id=%s" % (entity, rid))
        return 1
    p = json.loads(row)
    print("── %s #%s ──" % (entity, rid))
    for k in ("name", "code", "category", "island_id", "status"):
        if p.get(k) is not None:
            print("  %-12s %s" % (k, p[k]))
    if entity == "workflow":
        nodes = rows("SELECT payload::text FROM legacy_snapshot WHERE entity='workflow_node' "
                     "AND payload->>'workflow_id'='%s' ORDER BY (payload->>'seq')" % rid)
        edges = rows("SELECT payload::text FROM legacy_snapshot WHERE entity='workflow_edge' "
                     "AND payload->>'workflow_id'='%s' ORDER BY id" % rid)
        print("\n  节点（%d 个）：" % len(nodes))
        for n in nodes:
            np = json.loads(n)
            params = np.get("params") or {}
            # 设备绑定在**顶层** resource_id（resource_node.id），
            # 不是 params.bound_resource_id —— 备份里 params 只有
            # origin / method_params / protocol_name。
            bound = np.get("resource_id") or params.get("bound_resource_id")
            extra = ""
            if params.get("method_params"):
                extra = " 参数=%s" % json.dumps(params["method_params"], ensure_ascii=False)
            print("    seq=%-3s %-22s %-10s 绑定资源=%-8s 方法=%s%s"
                  % (np.get("seq"), np.get("name"), np.get("node_type"),
                     bound, params.get("protocol_name"), extra))
        print("\n  连线（%d 条）：" % len(edges))
        for e in edges:
            ep = json.loads(e)
            print("    %s → %s" % (ep.get("from_key"), ep.get("to_key")))
    return 0


def cmd_bpmn(entity, rid):
    """导出 BPMN XML（可直接 PUT 回 /api/workflows/{id}/graph）。"""
    row = q("SELECT payload::text FROM legacy_snapshot WHERE entity='%s' AND row_id=%s"
            % (entity, rid))
    if not row:
        print("备份里没有 %s id=%s" % (entity, rid))
        return 1
    p = json.loads(row)
    graph = p.get("graph") or {}
    xml = graph.get("xml") or graph.get("bpmn") or ""
    if not xml:
        print("这条流程的备份里没有可用的 XML（graph 字段：%s）" % list(graph.keys()))
        return 1
    print(xml)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--entity", default="workflow",
                    help="备份里的表名：workflow / workflow_node / experiment / resource_node …")
    ap.add_argument("--name", help="按名字模糊搜")
    ap.add_argument("--id", type=int, help="按原 id 看详情")
    ap.add_argument("--detail", action="store_true", help="打印完整节点与连线")
    ap.add_argument("--bpmn", action="store_true", help="导出 BPMN XML")
    ap.add_argument("--stats", action="store_true", help="只打印统计")
    args = ap.parse_args()

    try:
        if args.stats:
            cmd_stats()
            return 0
        if args.name:
            cmd_search(args.entity, args.name)
            return 0
        if args.bpmn:
            return cmd_bpmn(args.entity, args.id)
        if args.detail:
            return cmd_detail(args.entity, args.id)
        cmd_stats()
        return 0
    except Exception as e:   # noqa: BLE001
        print("查询失败：%s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
