#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""测试脚本的产物清理（把「实测·xxx」从业务台账里撤掉）。

**为什么需要它**：本仓的 verify 脚本大多会 `POST /api/workflows` 建一条
「实测·xxx」流程、再建同名实验，但**普遍不删** —— 结果流程库/实验列表里
堆着几十上百条测试数据（2026-11-15 实测：流程 173 条里 165 条、实验 85 个
全是这么来的）。这正是 `docs/评审-流程库产品评审与改进方案-20261113.md`
点名的"台账污染"。

**为什么不能直接删 workflow / experiment**：外键链很长，顺序错了会报错：
```
workflow ─┬─ workflow_node / workflow_edge / workflow_node_step / workflow_variable
          ├─ run ── run_node / run_command / …（CASCADE）
          │        schedule_item、experiment.run_id ← 无级联，先摘引用
          └─ experiment_process ── experiment ── experiment_run（无级联！）── …
```
所以这里按依赖顺序删（子表 → 主表），并**顺带清点位占用**，
免得把设备留在"被这条 run 预占"的状态。

用法（在 verify 脚本里，三种入口任选）：
```python
from test_artifact_cleanup import drop_workflow, drop_experiment, cleanup_created

drop_experiment(exp_id)     # 有实验的先删实验
drop_workflow(wf_id)        # 再删流程（默认连 run 一起删）

# 或者一次性（跑完自清理，`LABOS_KEEP_TEST_DATA=1` 时保留现场）：
cleanup_created([wf1, wf2], [exp1, exp2])
```
"""
import os
import subprocess

DB_HOST = os.environ.get("LABOS_DB_HOST", "127.0.0.1")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_USER = os.environ.get("LABOS_DB_USER", "zhengpingli")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_PASS = os.environ.get("LABOS_DB_PASS", "")


def q(sql: str) -> str:
    env = dict(os.environ)
    env["PGPASSWORD"] = DB_PASS
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME, "-tAc", sql],
        env=env, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("SQL 失败：%s | %s" % (sql[:140], (p.stderr or "")[:300]))
    return p.stdout.strip()


def _safe(sql: str) -> None:
    """清理是"尽力而为"：某张表不存在/没有这行都不该把测试脚本带崩。"""
    try:
        q(sql)
    except RuntimeError as e:
        print("  \033[33m·\033[0m 清理跳过：%s" % str(e)[:120])


def _ids(ids) -> str:
    vals = [str(int(x)) for x in ids if str(x).strip()]
    return ",".join(vals) if vals else "NULL"


def drop_workflow(wf_id: int, with_runs: bool = True) -> None:
    """删一条测试流程（默认连 run 一起删；with_runs=False 时有 run 则跳过）。"""
    if not wf_id:
        return
    runs = q("SELECT string_agg(id::text, ',') FROM run WHERE workflow_id = %d" % wf_id)
    if runs:
        if not with_runs:
            print("  \033[33m·\033[0m 流程 %d 有 run，按 with_runs=False 跳过" % wf_id)
            return
        ids = runs.strip()
        # run 被两处**无级联**外键引着：排程项、实验的 run_id
        _safe("DELETE FROM schedule_item WHERE run_id IN (%s)" % ids)
        _safe("UPDATE experiment SET run_id = NULL WHERE run_id IN (%s)" % ids)
        _safe("DELETE FROM intervention_record WHERE run_id IN (%s)" % ids)
        _safe("DELETE FROM run_command WHERE run_id IN (%s)" % ids)
        _safe("DELETE FROM run_node WHERE run_id IN (%s)" % ids)
        # run_event 是事件聚合表，用 aggregate_id 指向 run（不是 run_id 列）
        _safe("DELETE FROM run_event WHERE aggregate_type = 'run' "
              "AND aggregate_id IN (%s)" % ids)
        _safe("UPDATE position_state SET reserved_by_run=NULL, block_reason=NULL, "
              "reserved_until=NULL WHERE reserved_by_run IN (%s)" % ids)
        _safe("DELETE FROM run WHERE id IN (%s)" % ids)
    _safe("DELETE FROM experiment_process WHERE workflow_id = %d" % wf_id)
    _safe("DELETE FROM workflow_node_step WHERE workflow_id = %d" % wf_id)
    _safe("DELETE FROM workflow_variable WHERE workflow_id = %d" % wf_id)
    _safe("DELETE FROM workflow_node WHERE workflow_id = %d" % wf_id)
    _safe("DELETE FROM workflow_edge WHERE workflow_id = %d" % wf_id)
    _safe("DELETE FROM workflow_graph_history WHERE workflow_id = %d" % wf_id)
    _safe("DELETE FROM workflow WHERE id = %d" % wf_id)


def drop_experiment(exp_id: int) -> None:
    """删一条测试实验（连带它派生的 experiment_run 与全部子表）。"""
    if not exp_id:
        return
    # experiment_run → experiment 是**无级联**外键，必须先删 run；run 的子表再先删
    _safe("DELETE FROM intervention_record WHERE experiment_run_id IN "
          "(SELECT id FROM experiment_run WHERE experiment_id = %d)" % exp_id)
    _safe("DELETE FROM experiment_batch_run WHERE experiment_run_id IN "
          "(SELECT id FROM experiment_run WHERE experiment_id = %d)" % exp_id)
    _safe("DELETE FROM experiment_run_step WHERE experiment_run_id IN "
          "(SELECT id FROM experiment_run WHERE experiment_id = %d)" % exp_id)
    _safe("DELETE FROM manifest_placement WHERE run_id IN "
          "(SELECT id FROM experiment_run WHERE experiment_id = %d)" % exp_id)
    _safe("DELETE FROM experiment_run WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_process WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_variable WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_graph WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_sample WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_batch WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_workcell WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_handoff WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment WHERE id = %d" % exp_id)


# --------------------------------------------------------------- 批量入口
def delete_experiments(eids) -> int:
    n = 0
    for x in eids or []:
        if str(x).strip():
            drop_experiment(int(x))
            n += 1
    return n


def delete_workflows(wids, with_runs: bool = True) -> int:
    n = 0
    for x in wids or []:
        if str(x).strip():
            drop_workflow(int(x), with_runs=with_runs)
            n += 1
    return n


def cleanup(workflow_ids=(), experiment_ids=(), with_runs: bool = True,
            verbose: bool = True) -> dict:
    """删掉**本次脚本自己建的**流程/实验（只传 id，不做名字匹配 ⇒ 不会误伤别人的数据）。

    先删实验再删流程：实验的 `experiment_process` 引用流程，留着会挡住流程删除。
    """
    ne = delete_experiments([x for x in experiment_ids if str(x).strip()])
    nw = delete_workflows([x for x in workflow_ids if str(x).strip()], with_runs=with_runs)
    if verbose:
        print("  [cleanup] 已删除实验 %d 个、流程 %d 个" % (ne, nw))
    return {"experiments": ne, "workflows": nw}


def cleanup_created(workflow_ids, experiment_ids) -> None:
    """生成脚本的"跑完自清理"入口：只删自己建的 id，失败不抛出。

    `LABOS_KEEP_TEST_DATA=1` 时保留现场（排查用）。
    """
    wf, exp = list(workflow_ids), list(experiment_ids)
    if os.environ.get("LABOS_KEEP_TEST_DATA") == "1":
        print("  [cleanup] LABOS_KEEP_TEST_DATA=1，保留本次数据 %d 流程 / %d 实验"
              % (len(wf), len(exp)))
        return
    if not wf and not exp:
        return
    try:
        cleanup(workflow_ids=wf, experiment_ids=exp, with_runs=True)
    except Exception as e:      # noqa: BLE001 —— 清理失败不能影响测试结论
        print("  [cleanup] 失败（不影响测试结论）：%s" % e)
