#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""测试脚本的产物清理（把「实测·xxx」从业务台账里撤掉）。

**为什么需要它**：本仓的 verify 脚本大多会 `POST /api/workflows` 建一条
「实测·xxx」流程来测功能，但**普遍不删** —— 结果流程库里堆着几十条测试流程。
这正是 `docs/评审-流程库产品评审与改进方案-20261113.md` 点名的"台账污染"：
台账里混着测试产物，管理员分不清哪条是真工艺。

**为什么不能直接删 workflow**：外键链很长，顺序错了会报错：
```
workflow ─┬─ workflow_node / workflow_edge / workflow_node_step / workflow_variable
          ├─ run ── run_node / run_command
          └─ experiment_process ── experiment ── experiment_run / experiment_batch / …
```
所以这里按依赖顺序删（子表 → 主表），并**顺带清点位占用**，
免得把设备留在"被这条 run 预占"的状态。

用法（在 verify 脚本里）：
```python
from test_artifact_cleanup import drop_workflow, drop_experiment
...
finally:
    drop_experiment(exp_id)     # 有实验的先删实验
    drop_workflow(wf_id)        # 再删流程
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


def drop_workflow(wf_id: int) -> None:
    """删一条测试流程（含 run、节点、连线、变量、实验关联）。"""
    if not wf_id:
        return
    # 先把该流程下的 run 连同子表清掉
    runs = q("SELECT string_agg(id::text, ',') FROM run WHERE workflow_id = %d" % wf_id)
    if runs:
        ids = runs.strip()
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
    """删一条测试实验（连带它的流程关联与子表）。"""
    if not exp_id:
        return
    _safe("DELETE FROM experiment_process WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_variable WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_run WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_graph WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_sample WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_batch WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_workcell WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment_handoff WHERE experiment_id = %d" % exp_id)
    _safe("DELETE FROM experiment WHERE id = %d" % exp_id)
