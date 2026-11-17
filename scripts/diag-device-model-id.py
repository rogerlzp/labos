#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设备型号登记体检与回填（2026-11-17，BUG-20261116-02 的遗留项）。

**为什么需要**：`device_instance.device_model_id` 为空时，
`DeviceIdentityResolver.modelIdOf()` 返回 0 ⇒ 实例级与型号级超时全部跳过
⇒ 只能退回能力默认（600s）。现象与那个 id 混用事故一样（标定好的耗时调不出来），
但根因是**数据**不是代码。代码侧只能让它看得见（WARN + `TimeoutDiagnostics`），
修数据要靠本脚本。

**检查项（默认 --check）**
  1. 型号未登记：`device_model_id IS NULL`
  2. 型号悬空：`device_model_id` 指向不存在的 `device.id`
  3. 撞号：`device_instance.id` 恰好等于某个 `device.id`（只报告不修 —— 编号冲突，
     改 id 风险远大于收益；代码已按类型区分，见 DeviceIds）
  4. 型号在但没有配超时：该型号 `device_method` 里没有任何 `timeout_sec > 0`
  5. 实例级覆盖统计：`device_method` 里 `scope='instance'` 的行数

**回填（--fix，默认 dry-run）**
候选来源：`device_instance.attr->>'device_model'` →
`resource_node.attr->>'device_model'`（该实例的资源节点）。
匹配 `device.code` / `device.attr->>'device_model'` / `device.model` 三个键，
**只在唯一命中时回填**。多个候选一律跳过并列出来 —— 猜错型号比不填更糟：
填错会静默套上另一个型号的超时与可中断配置，正是那个事故的形态。

用法：
    LABOS_DB_HOST=... LABOS_DB_USER=... LABOS_DB_NAME=... \\
      python3 scripts/diag-device-model-id.py                     # 体检
    ... python3 scripts/diag-device-model-id.py --fix --dry-run   # 回填演练
    ... python3 scripts/diag-device-model-id.py --fix             # 真的写库
    ... python3 scripts/diag-device-model-id.py --tenant 1 --json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

PASS, WARN, BAD = 0, 0, 0


def _ok(m, d=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m + (("（%s）" % d) if d else ""))


def _warn(m, d=""):
    global WARN
    WARN += 1
    print("  \033[33m!\033[0m " + m + (("（%s）" % d) if d else ""))


def _bad(m, d=""):
    global BAD
    BAD += 1
    print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))


def sql(q: str) -> list[list[str]]:
    """跑一条 SQL，返回行列表。失败必须抛错（空结果 ≠ 查询失败）。"""
    for key in ("LABOS_DB_HOST", "LABOS_DB_USER", "LABOS_DB_NAME"):
        if not os.environ.get(key):
            sys.exit("缺环境变量 %s（见脚本头部用法）" % key)
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-q", "-tA", "-F", "\x1f", "-c", q],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("SQL 失败：%s\n%s" % (q.strip()[:200], out.stderr.strip()[:800]))
    return [ln.split("\x1f") for ln in out.stdout.splitlines() if ln != ""]


def one(q: str) -> str:
    rows = sql(q)
    return rows[0][0] if rows and rows[0] else ""


def need_columns():
    rows = sql("SELECT table_name || '.' || column_name FROM information_schema.columns "
               "WHERE table_schema = current_schema() "
               "  AND table_name IN ('device','device_instance','device_method')")
    cols = {r[0] for r in rows}
    for need in ("device_instance.device_model_id", "device_method.device_model_id"):
        if need not in cols:
            sys.exit("表结构不符：缺 %s（先跑 pg_rebuild/migration/20261116_device_model_id_rename.sql）"
                     % need)
    return cols


# ---------------------------------------------------------------- 体检


def check(tenant: int) -> dict:
    cols = need_columns()
    rep: dict = {}
    t = tenant

    print("\n── ① 型号未登记（device_model_id IS NULL）──")
    n_no_model = int(one(f"SELECT count(*) FROM device_instance "
                         f"WHERE tenant_id = {t} AND deleted_at IS NULL AND device_model_id IS NULL"))
    rep["no_model"] = n_no_model
    if n_no_model == 0:
        _ok("没有未登记型号的实例")
    else:
        _bad("%d 台实例没登记型号 ⇒ 超时/可中断解析必然落空" % n_no_model)
        for r in sql(f"""
            SELECT di.id, di.code, di.name,
                   coalesce(di.attr->>'device_model',''),
                   coalesce(rn.attr->>'device_model','')
              FROM device_instance di
              LEFT JOIN resource_node rn ON rn.id = di.resource_id
             WHERE di.tenant_id = {t} AND di.deleted_at IS NULL
               AND di.device_model_id IS NULL
             ORDER BY di.id LIMIT 30
        """):
            print("      · 实例 %s %s（%s）attr=%r 资源 attr=%r"
                  % (r[0], r[1], r[2], r[3] if len(r) > 3 else "", r[4] if len(r) > 4 else ""))

    print("\n── ② 型号悬空（指向不存在的 device.id）──")
    n_dangling = int(one(f"""
        SELECT count(*) FROM device_instance di
         WHERE di.tenant_id = {t} AND di.deleted_at IS NULL
           AND di.device_model_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM device d WHERE d.id = di.device_model_id)"""))
    rep["dangling"] = n_dangling
    if n_dangling == 0:
        _ok("没有悬空的 device_model_id")
    else:
        _bad("%d 台实例的 device_model_id 指向不存在的型号行" % n_dangling)

    print("\n── ③ 撞号（实例号恰好等于某个型号行号，只报告）──")
    n_inst = int(one(f"SELECT count(*) FROM device_instance "
                     f"WHERE tenant_id = {t} AND deleted_at IS NULL"))
    n_collide = int(one(f"""
        SELECT count(*) FROM device_instance di JOIN device d ON d.id = di.id
         WHERE di.tenant_id = {t} AND di.deleted_at IS NULL
           AND (di.device_model_id IS NULL OR d.id <> di.device_model_id)"""))
    rep.update({"instances": n_inst, "collide": n_collide})
    if n_collide == 0:
        _ok("没有撞号（实例号不与任何型号行号重合）")
    else:
        _warn("%d/%d 台实例的 id 与某个型号行号重合" % (n_collide, n_inst),
              "不影响正确性（列名已分离），但排障时容易看错；见 DeviceIds")

    print("\n── ④ 型号已登记但没有配任何超时 ──")
    n_flat = int(one(f"""
        SELECT count(*) FROM device_instance di
         WHERE di.tenant_id = {t} AND di.deleted_at IS NULL
           AND di.device_model_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM device_method dm
                            WHERE dm.device_model_id = di.device_model_id
                              AND dm.scope = 'model' AND dm.timeout_sec > 0)"""))
    rep["model_without_timeout"] = n_flat
    if n_flat == 0:
        _ok("每台已登记型号的实例都能读到至少一条型号级超时")
    else:
        _warn("%d 台实例的型号在 device_method 里没有任何 timeout_sec>0" % n_flat,
              "会落到能力默认；TimeoutDiagnostics.deviceLevelMissing 会持续增长")

    print("\n── ⑤ 实例级覆盖（scope='instance'）──")
    if "device_method.scope" in cols:
        n_inst_scope = int(one(f"SELECT count(*) FROM device_method "
                               f"WHERE tenant_id = {t} AND scope = 'instance'"))
        n_inst_to = int(one(f"SELECT count(*) FROM device_method "
                            f"WHERE tenant_id = {t} AND scope = 'instance' AND timeout_sec > 0"))
        n_inst_ir = int(one(f"SELECT count(*) FROM device_method "
                            f"WHERE tenant_id = {t} AND scope = 'instance' AND interruptible"))
        rep.update({"instance_scope_rows": n_inst_scope,
                    "instance_scope_timeout": n_inst_to,
                    "instance_scope_interruptible": n_inst_ir})
        _ok("实例级覆盖行 %d 行（其中配了超时 %d、可中断 %d）"
            % (n_inst_scope, n_inst_to, n_inst_ir))
    else:
        rep["instance_scope_rows"] = None
        _warn("device_method 没有 scope 列", "先跑 20261117_device_method_instance_scope.sql")

    return rep


# ---------------------------------------------------------------- 回填


def plan_backfill(tenant: int) -> list[tuple]:
    """返回可直接回填的 (instance_id, code, candidate, device_id)。"""
    rows = sql(f"""
        WITH cand AS (
          SELECT di.id AS inst, di.code, v AS cand
            FROM device_instance di
            LEFT JOIN resource_node rn ON rn.id = di.resource_id
            CROSS JOIN LATERAL (VALUES
                (NULLIF(di.attr->>'device_model','')),
                (NULLIF(rn.attr->>'device_model',''))
            ) AS t(v)
           WHERE di.tenant_id = {tenant} AND di.deleted_at IS NULL
             AND di.device_model_id IS NULL AND v IS NOT NULL
        ), hit AS (
          SELECT c.inst, c.code, c.cand, d.id AS dev,
                 count(*) OVER (PARTITION BY c.inst) AS n,
                 row_number() OVER (PARTITION BY c.inst ORDER BY d.id) AS rn
            FROM cand c
            JOIN device d ON d.tenant_id = {tenant} AND d.deleted_at IS NULL
                         AND (d.code = c.cand
                              OR d.attr->>'device_model' = c.cand
                              OR d.model = c.cand)
        )
        SELECT inst, code, cand, dev FROM hit WHERE n = 1 ORDER BY inst
    """)
    return [(int(r[0]), r[1], r[2], int(r[3])) for r in rows]


def plan_ambiguous(tenant: int) -> list[tuple]:
    """候选命中多台的实例（跳过并列出）。"""
    rows = sql(f"""
        WITH cand AS (
          SELECT di.id AS inst, di.code, v AS cand
            FROM device_instance di
            LEFT JOIN resource_node rn ON rn.id = di.resource_id
            CROSS JOIN LATERAL (VALUES
                (NULLIF(di.attr->>'device_model','')),
                (NULLIF(rn.attr->>'device_model',''))
            ) AS t(v)
           WHERE di.tenant_id = {tenant} AND di.deleted_at IS NULL
             AND di.device_model_id IS NULL AND v IS NOT NULL
        )
        SELECT c.inst, c.code, c.cand, count(*) AS n
          FROM cand c
          JOIN device d ON d.tenant_id = {tenant} AND d.deleted_at IS NULL
                       AND (d.code = c.cand
                            OR d.attr->>'device_model' = c.cand
                            OR d.model = c.cand)
         GROUP BY c.inst, c.code, c.cand HAVING count(*) > 1
         ORDER BY c.inst
    """)
    return [(int(r[0]), r[1], r[2], int(r[3])) for r in rows]


def do_fix(tenant: int, dry: bool) -> dict:
    print("\n── ⑥ 回填 device_model_id ──")
    plan = plan_backfill(tenant)
    amb = plan_ambiguous(tenant)
    applied = 0
    for inst, code, cand, dev in plan:
        if dry:
            print("      · [dry] 实例 %s %s：%r → 型号行 %s" % (inst, code, cand, dev))
        else:
            sql(f"UPDATE device_instance SET device_model_id = {dev}, updated_at = now() "
                f" WHERE id = {inst} AND tenant_id = {tenant} AND device_model_id IS NULL")
            applied += 1
    if plan:
        (_ok if not dry else _warn)(
            "%d 台可唯一回填%s" % (len(plan), "（已写入）" if not dry else "（dry-run，未写库）"))
    else:
        _ok("没有可回填的实例（要么没有空型号，要么没有候选）")

    for inst, code, cand, n in amb:
        _warn("实例 %s %s：候选 %r 命中 %d 个型号，跳过（需人工指定）" % (inst, code, cand, n))

    return {"backfill_ready": len(plan), "backfill_applied": applied,
            "ambiguous": len(amb)}


# ---------------------------------------------------------------- main


def main() -> int:
    ap = argparse.ArgumentParser(description="设备型号登记体检与回填")
    ap.add_argument("--tenant", type=int, default=1, help="租户 id（默认 1）")
    ap.add_argument("--fix", action="store_true", help="回填 device_model_id")
    ap.add_argument("--dry-run", action="store_true", help="与 --fix 一起用：只演练不写库")
    ap.add_argument("--json", action="store_true", help="额外输出 JSON 汇总")
    ap.add_argument("--only-fix", action="store_true", help="只做回填，跳过 ①~⑤ 体检")
    args = ap.parse_args()

    print("════ 设备型号登记体检（tenant=%d）════" % args.tenant)
    rep: dict = {}
    if not args.only_fix:
        rep.update(check(args.tenant))
    if args.fix:
        rep.update(do_fix(args.tenant, dry=args.dry_run or not args.fix))

    print("\n──────────────────────────────────────")
    print("通过 %d / 提示 %d / 失败 %d" % (PASS, WARN, BAD))
    if args.json:
        print(json.dumps(rep, ensure_ascii=False, indent=2))
    # 有"型号未登记/悬空"这类硬问题时返回 1，便于挂到巡检
    return 1 if BAD else 0


if __name__ == "__main__":
    sys.exit(main())
