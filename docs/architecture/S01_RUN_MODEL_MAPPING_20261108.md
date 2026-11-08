# S-01 两套 run 模型映射表（2026-11-08）

> 目的：PauseManager 写 `run`（workflow 级）、RunControl 写 `experiment_run`（实验级）
> 并存且独立写库。本文把字段、调用链、`pause_kind` 词表差异、创建/关闭/重启恢复
> 行为与关联基数钉住，供后续统一（S-03/S-04）与排障对照。

## 一、字段映射

| 语义 | workflow 级 `run` | 实验级 `experiment_run` | 备注 |
|---|---|---|---|
| 主键 | `run.id` | `experiment_run.id` | 两套 id 空间不互认 |
| 编号 | `run.run_no` | `experiment_run` 无独立编号，页面用 experiment_no + id | |
| 生命周期 | `run.status`（run_status 枚举） | `experiment_run.status`（varchar） | 取值不同源 |
| 暂停原因 | `run.pause_kind` | `experiment_run.pause_kind` | 词表需对齐 |
| 暂停说明 | `run.pause_reason` | `experiment_run.pause_reason` | |
| 暂停控制阶段 | 无 | `experiment_run.control_state`（NONE/PAUSE_REQUESTED/PAUSING） | L-02/S-04-B |
| 暂停时间 | `run.paused_at` / `resumed_at` | `experiment_run.paused_at` + `pause_requested_at` | PAUSING 判据 |
| 停止请求 | `run` 无 stop_requested 列 | `experiment_run.stop_requested` | 实验级停止先落请求 |
| 进度 | `run.progress` | 批次/步骤聚合 | |
| 错误 | `run.error_code` | `experiment_run.error` | |
| 归属 | `run.workflow_id` | `experiment_run.experiment_id` | |
| 父 run | `run.parent_run_id` | `experiment_run_step.experiment_run_id` | 关联靠 step 表 |

## 二、调用链

| 入口 | 路由 | 写谁 |
|---|---|---|
| 流程运行页暂停/恢复 | `POST /api/runs/{id}/pause|resume` → RunApi → PauseManager | `run` + 资源/板位 |
| 实验运行页暂停/恢复/停止 | `POST /api/experiments/runs/{id}/(pause|resume|stop)` → ExperimentApi → PauseCoordinator → RunControl/RuntimeHold | `experiment_run` + `runtime_hold` + `pause_request` + `pause_event` |
| 调度派发 | `ExperimentScheduler.dispatch` | `experiment_run.status=queued/running` |
| 启动恢复 | `StartupRecovery.settleOrphanExperimentRuns` + `SchedulerRehydrate` + `CommandLedger.reconcileOnStartup` | 孤儿 running → failed；queued 回内存队列；陈旧命令 → UNKNOWN |

## 三、pause_kind 词表差异

- `run.pause_kind`：manual / waiting_human / paused_unknown / paused_fault / …（PauseManager.Kind）
- `experiment_run.pause_kind`：manual / waiting_human / paused_unknown / paused_fault（RunControl/RuntimeHold.Kind 侧）
- 现状：两边都按字符串写库，**没有共享枚举**；统一前新增/改名必须两边同时改，
  并在 `StatusVocabulary` 里登记。

## 四、创建 / 关闭 / 重启恢复

| 阶段 | workflow 级 | 实验级 |
|---|---|---|
| 创建 | WorkflowService/ExperimentExecutor 每步新建 `run` | ExperimentExecutor 建 experiment_run + step；批次由 BatchOrchestrator 建 attempt |
| 关闭 | `WorkflowService.settleRun` 写终态；暂停时 `pauseRunAtBoundary` 写 paused | 执行器 `ExperimentExecutor.settle` 写终态/paused，control_state 回 NONE |
| 重启 | StartupRecovery settle 孤儿 running run；rehydrate queued | settleOrphanExperimentRuns；scheduler.rehydrateQueued；CommandLedger 对账 |

## 五、关联基数

- 1 experiment_run → N experiment_run_step（seq）→ 每条 step 关联 1 个 workflow 级 run；
- 1 experiment_run → N experiment_process（workflow 段）；跨 Workcell 由 N-10 的
  experiment_workcell 绑定表达；
- `runtime_hold` 按 (tenant, scope, scope_id) 唯一生效；`pause_request` 按 request_id 幂等；
- 批次：experiment_batch_run(experiment_id, batch_no, attempt_no) 唯一；experiment_run 是批次 run。

## 六、风险与后续

1. 两套 `status` 取值/写点仍分散（PauseManager 约 10 处、RunControl 约 9 处），S-03 投影器只收敛了“暂停态同步”这一条；
2. `pause_kind` 没有共享枚举，文档/页面/DB 三处容易漂移；
3. Workflow 级 Pause 仍是 PauseManager 多连接实现（P-06 V2 剩余）；
4. 后续统一方案：保持两张表（事实不同层），但把「入口 → 事实 → 投影」收口到 PauseCoordinator/ExperimentStateProjector，词表进 StatusVocabulary。
