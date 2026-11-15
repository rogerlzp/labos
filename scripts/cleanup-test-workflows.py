#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清理测试脚本留在业务台账里的**流程 + 实验**（默认 dry-run）。

**为什么需要它**：本仓的 verify 脚本大多会 `POST /api/workflows` 建一条
「实测·xxx」流程、再 `POST /api/experiments` 建一个同名实验，但**普遍不删**
（库里没有 DELETE /api/workflows 端点）。结果是流程库/实验列表里堆着几十上百条
测试数据 —— 2026-11-15 实测：流程 173 条里 165 条是测试残留、实验 85 条全是。

清理规则（每条都有理由，别乱改）：
  ① **有执行记录的流程默认跳过** —— 说明有人真的用过它，或别的会话正在跑。
     （实验不受此限：实验清理由 `--only-exp-ids` 或名称模式驱动，
       删除时会**连 experiment_run 一起删**，因为 `experiment_run → experiment`
       是无级联外键，只删实验会直接报错。）
  ② **被非测试实验引用的流程跳过** —— 那是业务数据，不能跟着测试流程一起删。
  ③ **只引用测试流程的测试实验一并删除** —— 否则 experiment_process 的外键
     会挡住删除（workflow ← experiment_process ← experiment）。
  ④ 子表先删（见 scripts/test_artifact_cleanup.py 的删除语义，两处共用一份实现）。
  ⑤ 整个删除放在**一个事务**里：中途出错整体回滚，不留半截状态。

用法：
  python3 scripts/cleanup-test-workflows.py            # 只列清单（dry-run）
  python3 scripts/cleanup-test-workflows.py --apply    # 真删
  python3 scripts/cleanup-test-workflows.py --apply --pattern '实测%'
  # 生成脚本跑完自清理（只删自己建的，最安全）：
  python3 scripts/cleanup-test-workflows.py --apply --only-ids 1000792,1000793 \
      --only-exp-ids 1000428,1000429
"""
import argparse
import glob
import importlib.util
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "test_artifact_cleanup", os.path.join(_HERE, "test_artifact_cleanup.py"))
tdc = importlib.util.module_from_spec(_spec)
sys.modules["test_artifact_cleanup"] = tdc
_spec.loader.exec_module(tdc)

# 默认模式：测试脚本起的名字（2026-11-15 补全：核心场景/探针/性能测试/探查/S2-*）
DEFAULT_PATTERNS = ["实测%", "S2实测%", "测试%", "验证%", "页面批量%", "自检%",
                    "demo%", "test%", "dbg-%", "校验%", "用例%",
                    "核心场景%", "探针%", "性能测试%", "探查-%", "sc test%", "S2-%"]


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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真删（默认只列清单）")
    ap.add_argument("--pattern", action="append", default=None,
                    help="名称匹配模式（LIKE），可重复；默认用内置的一组")
    ap.add_argument("--tenant", default="1")
    ap.add_argument("--keep", action="append", default=None,
                    help="额外保留的流程 id（可重复）")
    ap.add_argument("--only-ids", default=None,
                    help="只处理这些流程 id（逗号分隔）；生成脚本自清理用")
    ap.add_argument("--only-exp-ids", default=None,
                    help="只处理这些实验 id（逗号分隔）；生成脚本自清理用")
    ap.add_argument("--with-runs", action="store_true",
                    help="连有 run 的流程也删（run 及其子表一并删，谨慎）")
    ap.add_argument("--no-experiments", action="store_true",
                    help="只清流程，不动实验")
    args = ap.parse_args()

    # ---------------------------------------------------------------- 定向模式
    if args.only_ids or args.only_exp_ids:
        wids = [int(x) for x in (args.only_ids or "").split(",") if x.strip()]
        eids = [int(x) for x in (args.only_exp_ids or "").split(",") if x.strip()]
        print("定向清理：流程 %d 个 %s｜实验 %d 个 %s"
              % (len(wids), wids, len(eids), eids))
        if not args.apply:
            print(" dry-run：加 --apply 执行。")
            return 0
        r = tdc.cleanup(workflow_ids=wids, experiment_ids=eids,
                        with_runs=args.with_runs)
        print("✓ 已删除实验 %d 个、流程 %d 个" % (r["experiments"], r["workflows"]))
        return 0

    # ---------------------------------------------------------------- 模式扫描
    keep = script_fixture_ids(_HERE)
    for x in (args.keep or []):
        keep.add(str(x).strip())

    patterns = args.pattern or DEFAULT_PATTERNS
    where = " OR ".join("w.name LIKE '%s'" % p for p in patterns)
    exp_where = " OR ".join("e.name LIKE '%s'" % p for p in patterns)

    cands = tdc.q(f"""
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
        b = tdc.q(f"""SELECT count(*) FROM experiment_process ep
                        JOIN experiment e ON e.id = ep.experiment_id
                       WHERE ep.workflow_id = {wid}
                         AND NOT ({exp_where})""")
        if b and b != "0":
            blocked.add(wid)

    todo = [r.split("|")[0] for r in rows if r.split("|")[0] not in blocked]

    # ---------------------------------------------------------------- 实验
    exps = []
    if not args.no_experiments:
        ecands = tdc.q(f"""
            SELECT e.id || '|' || e.name || '|' || e.created_at::text || '|' ||
                   (SELECT count(*) FROM experiment_run er WHERE er.experiment_id = e.id)
              FROM experiment e
             WHERE e.tenant_id = {args.tenant}
               AND e.deleted_at IS NULL
               AND ({exp_where})
             ORDER BY e.created_at DESC
        """)
        exps = [r for r in ecands.split("\n") if r.strip()]

    print(f"命中流程 {len(rows)} 条（模式：{', '.join(patterns)}）")
    print(f"  可删流程 {len(todo)} 条｜保留(夹具/业务引用) {len(blocked)} 条"
          f"｜夹具保护 {sorted(keep) or '无'}")
    print(f"  可删实验 {len(exps)} 个（含其 experiment_run）\n")
    for r in rows:
        wid, code, name, created = (r.split("|") + [""] * 4)[:4]
        flag = "门禁夹具,保留" if wid in keep else ("业务引用,跳过" if wid in blocked else "可删")
        print(f"  {flag:<14} 流程 id={wid:<8} {name[:36]:<36} {created[:16]}")
    for r in exps:
        eid, name, created, runs = (r.split("|") + [""] * 4)[:4]
        print(f"  {'可删(含run)' if runs != '0' else '可删':<14} 实验 id={eid:<8} "
              f"{name[:34]:<34} runs={runs:<3} {created[:16]}")

    if not args.apply:
        print(f"\n dry-run：可删 {len(todo)} 条流程 + {len(exps)} 个实验。加 --apply 执行。")
        return 0
    if not todo and not exps:
        print("\n没有可删的。")
        return 0

    # 实验先删（experiment_process 引用流程，留着会挡流程删除）
    if exps:
        eids = [int(r.split("|")[0]) for r in exps]
        n = tdc.delete_experiments(eids)
        print(f"\n✓ 已删除 {n} 个测试实验（含其 experiment_run）。")
    if todo:
        wids = [int(x) for x in todo]
        n = tdc.delete_workflows(wids, with_runs=args.with_runs)
        print(f"✓ 已删除 {n} 条测试流程。")

    after_w = tdc.q(f"SELECT count(*) FROM workflow WHERE tenant_id={args.tenant} "
                    f"AND deleted_at IS NULL")
    after_e = tdc.q(f"SELECT count(*) FROM experiment WHERE tenant_id={args.tenant} "
                    f"AND deleted_at IS NULL")
    print(f"  流程库剩余：{after_w} 条｜实验列表剩余：{after_e} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
