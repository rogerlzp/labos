#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清理测试脚本留在业务台账里的流程（默认 dry-run）。

**为什么需要它**：本仓的 verify 脚本大多会 `POST /api/workflows` 建一条
「实测·xxx」流程来测功能，但**普遍不删**（库里没有 DELETE /api/workflows 端点）。
结果是流程库里堆着几十条测试流程 —— 这正是
`docs/评审-流程库产品评审与改进方案-20261113.md` 里点名的"台账污染"。

清理规则（每条都有理由，别乱改）：

  ① **有执行记录的跳过** —— 说明有人真的用过它，或别的会话正在跑。
  ② **被非测试实验引用的跳过** —— 那是业务数据，不能跟着测试流程一起删。
  ③ **只引用测试流程的测试实验一并删除** —— 否则 experiment_process 的外键
     会挡住删除（workflow ← experiment_process ← experiment）。
  ④ 子表先删：workflow_node / workflow_edge / workflow_variable /
     workflow_node_step / workflow_graph_history。
  ⑤ 整个删除放在**一个事务**里：中途出错整体回滚，不留半截状态。

用法：
  python3 scripts/cleanup-test-workflows.py            # 只列清单（dry-run）
  python3 scripts/cleanup-test-workflows.py --apply    # 真删
  python3 scripts/cleanup-test-workflows.py --apply --pattern '实测%'
"""
import argparse
import glob
import os
import re
import subprocess
import sys

DB_HOST = os.environ.get("LABOS_DB_HOST", "127.0.0.1")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_USER = os.environ.get("LABOS_DB_USER", "zhengpingli")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_PASS = os.environ.get("LABOS_DB_PASS", "")

# 默认模式：测试脚本起的名字
DEFAULT_PATTERNS = ["实测%", "S2实测%", "测试%", "验证%", "页面批量%", "自检%",
                    "demo%", "test%", "dbg-%", "校验%", "用例%"]


def script_fixture_ids(scripts_dir: str) -> set:
    """扫描脚本里**硬编码的流程 id** —— 那些是门禁脚本的固定夹具，不能删。

    例：``verify-designer-autolayout.py`` 里写死 ``/designer/1000854``。
    删了它，那条门禁就直接失去被测对象（不是红，是没法跑）。
    自动扫描 ⇒ 以后别人新增夹具也自动被保护。
    """
    pat = re.compile(r"(?:designer|workflows?|workflow_id)[=/]?\s*[^0-9]{0,3}(1[0-9]{6})")
    found = set()
    for f in glob.glob(os.path.join(scripts_dir, "*.py")):
        try:
            txt = open(f, encoding="utf-8").read()
        except OSError:
            continue
        found |= set(pat.findall(txt))
    return found


def q(sql: str) -> str:
    env = dict(os.environ)
    env["PGPASSWORD"] = DB_PASS
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME, "-tAc", sql],
        env=env, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("SQL 失败：%s | %s" % (sql[:140], (p.stderr or "")[:300]))
    return p.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真删（默认只列清单）")
    ap.add_argument("--pattern", action="append", default=None,
                    help="名称匹配模式（LIKE），可重复；默认用内置的一组")
    ap.add_argument("--tenant", default="1")
    ap.add_argument("--keep", action="append", default=None,
                    help="额外保留的流程 id（可重复）")
    args = ap.parse_args()

    # 夹具保护：脚本里写死的流程 id 一律保留
    scripts_dir = os.path.dirname(os.path.abspath(__file__))
    keep = script_fixture_ids(scripts_dir)
    for x in (args.keep or []):
        keep.add(str(x).strip())

    patterns = args.pattern or DEFAULT_PATTERNS
    where = " OR ".join("w.name LIKE '%s'" % p for p in patterns)
    # 「是不是测试残留」用同一组模式判断（实验也按名字判定）
    exp_where = " OR ".join("e.name LIKE '%s'" % p for p in patterns)

    cands = q(f"""
        SELECT w.id || '|' || w.code || '|' || w.name || '|' || w.created_at::text
          FROM workflow w
         WHERE w.tenant_id = {args.tenant}
           AND w.deleted_at IS NULL
           AND ({where})
           AND NOT EXISTS (SELECT 1 FROM run r WHERE r.workflow_id = w.id)
         ORDER BY w.created_at DESC
    """)
    rows = [r for r in cands.split("\n") if r.strip()]

    # 被非测试实验引用的（规则②）：跳过
    blocked = set()
    for r in rows:
        wid = r.split("|")[0]
        if wid in keep:
            blocked.add(wid)
    for r in rows:
        wid = r.split("|")[0]
        b = q(f"""SELECT count(*) FROM experiment_process ep
                     JOIN experiment e ON e.id = ep.experiment_id
                    WHERE ep.workflow_id = {wid}
                      AND NOT ({exp_where})""")
        if b and b != "0":
            blocked.add(wid)

    # 只引用测试流程的测试实验（规则③）：一并删
    exp_ids = set()
    for r in rows:
        wid = r.split("|")[0]
        e = q(f"""SELECT DISTINCT e.id FROM experiment_process ep
                   JOIN experiment e ON e.id = ep.experiment_id
                  WHERE ep.workflow_id = {wid} AND ({exp_where})
                    AND NOT EXISTS (SELECT 1 FROM experiment_process ep2
                                     WHERE ep2.experiment_id = e.id
                                       AND ep2.workflow_id NOT IN ({wid}))""")
        for x in (e.split("\n") if e else []):
            if x.strip():
                exp_ids.add(x.strip())

    todo = [r.split("|")[0] for r in rows if r.split("|")[0] not in blocked]

    print(f"命中 {len(rows)} 条（模式：{', '.join(patterns)}）")
    print(f"  可删流程 {len(todo)} 条｜连带删除测试实验 {len(exp_ids)} 个"
          f"｜跳过 {len(blocked)} 条（门禁夹具或被业务引用）"
          f"｜夹具保护 {sorted(keep) or '无'}\n")
    for r in rows:
        wid, code, name, created = (r.split("|") + [""] * 4)[:4]
        if wid in keep:
            flag = "门禁夹具,保留"
        elif wid in blocked:
            flag = "业务引用,跳过"
        elif wid in exp_ids:
            flag = "可删(连带实验)"
        else:
            flag = "可删"
        print(f"  {flag:<14} id={wid:<8} {name[:36]:<36} {created[:16]}")

    if not args.apply:
        print(f"\n dry-run：可删 {len(todo)} 条流程 + {len(exp_ids)} 个测试实验。"
              f"加 --apply 执行。")
        return 0
    if not todo and not exp_ids:
        print("\n没有可删的。")
        return 0

    wids = ",".join(todo)
    eids = ",".join(exp_ids) if exp_ids else "NULL"
    # 一个事务：中途出错整体回滚
    sql = f"""
        BEGIN;
        -- 实验侧（先摘引用，再删实验）
        DELETE FROM experiment_process WHERE workflow_id IN ({wids});
        DELETE FROM experiment_variable WHERE experiment_id IN ({eids});
        DELETE FROM experiment_run WHERE experiment_id IN ({eids});
        DELETE FROM experiment_graph WHERE experiment_id IN ({eids});
        DELETE FROM experiment_sample WHERE experiment_id IN ({eids});
        DELETE FROM experiment_batch WHERE experiment_id IN ({eids});
        DELETE FROM experiment_workcell WHERE experiment_id IN ({eids});
        DELETE FROM experiment_handoff WHERE experiment_id IN ({eids});
        DELETE FROM experiment WHERE id IN ({eids});
        -- 流程侧（子表 → 主表）
        DELETE FROM workflow_node_step WHERE workflow_id IN ({wids});
        DELETE FROM workflow_variable WHERE workflow_id IN ({wids});
        DELETE FROM workflow_node WHERE workflow_id IN ({wids});
        DELETE FROM workflow_edge WHERE workflow_id IN ({wids});
        DELETE FROM workflow_graph_history WHERE workflow_id IN ({wids});
        DELETE FROM workflow WHERE id IN ({wids});
        COMMIT;
    """
    try:
        out = q(sql)
    except RuntimeError as e:
        print("\n✗ 删除失败（事务已回滚）：%s" % str(e)[:400])
        return 1
    print(f"\n✓ 已清理 {len(todo)} 条测试流程 + {len(exp_ids)} 个测试实验。")
    after = q(f"SELECT count(*) FROM workflow WHERE tenant_id={args.tenant} "
              f"AND deleted_at IS NULL")
    print(f"  流程库剩余：{after} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
