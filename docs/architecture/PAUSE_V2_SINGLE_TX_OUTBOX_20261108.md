# 暂停控制 V2 第一切片：单事务 + pause_event Outbox（2026-11-08）

> 对应工作项：P-06 V2（单事务）、P-07 前置（事件 Outbox 第一块）。
> 上游设计：[PauseCoordinator V1 设计](LABOS_PAUSE_COORDINATOR_V1_DESIGN_20261107.md)、
> [ADR-PAUSE-007/008/009](ADR_INDEX.md §2.12~§2.14)。

## 一、这版解决了什么

V1 的暂停受理是**多连接、多事务**：

1. `pause_request` 幂等键（独立连接插入）；
2. `runtime_hold` 抬闸门（独立连接）；
3. `experiment_run.control_state` 置 PAUSE_REQUESTED（独立连接）；
4. `pause_request` 回写结果（独立连接）。

任何一步之间进程崩溃，都会留下「幂等键在、Hold 不在」或「Hold 在、事件丢」的半成品。

V2 第一切片把上述 4 步 + **pause_event Outbox** 收进**同一数据库事务**：

```
BEGIN
  1) INSERT pause_request ... ON CONFLICT DO NOTHING   -- 幂等键
  2) INSERT runtime_hold ...                            -- 闸门
  3) UPDATE experiment_run SET control_state=PAUSE_REQUESTED ...
  4) UPDATE pause_request SET result_state/result_detail
  5) INSERT pause_event (PAUSE_REQUESTED, scope, scopeId, payload)
COMMIT
```

失败/异常整体 `ROLLBACK`：幂等键、Hold、控制状态、事件一起消失，客户端可带同 requestId 重试。

## 二、事件 Outbox

- 表：`pause_event`（`pg_rebuild/schema/99e_pause_event.sql`，已拼入 `90_all_in_one.sql`）；
- 行：`event_type` / `target_type` / `target_id` / `scope` / `payload` / `dispatched_at` / `attempts`；
- 派发：`PauseEventOutbox.dispatchPending(db, tenantId, limit)` 在调度器每 tick 执行，
  `FOR UPDATE SKIP LOCKED` 抢未派发行并标记 `dispatched_at`（幂等；重复调用不会重复处理）；
- 本切片**不接外部事件总线**：派发=内部审计/日志标记；对接 Kafka/审计中心是 V2.1。

## 三、当前覆盖边界（重要）

| 入口 | V2 单事务 | 说明 |
|---|---|---|
| 实验级 Pause（E-4：POST /api/experiments/runs/{id}/pause） | ✅ 本切片 | Hold+control+幂等键+事件同事务 |
| 实验级 Resume（E-6） | ✅ 第二切片 | 解 Hold / run 回 queued / 实验表同步 / 作废旧 paused run / RESUME_REQUESTED 事件同事务；批次聚合与重新入队 commit 后做 |
| 实验级 Stop（E-5） | ✅ 第三切片 | stop_requested / aborted / 实验表同步 / STOP_REQUESTED 事件同事务；批次聚合 commit 后补 |
| Workflow 级 Pause / Resume（E-1~E-3） | ❌ 仍 V1 | PauseManager 仍多连接；依赖 P-07 命令账本一起做 |
| PAUSING 异步收敛 worker | 🟡 第四切片（最小） | 受理超过 5 秒仍未到边界的 running run 标 PAUSING（只展示，不写 paused）；settle 收尾回 NONE |
| P-07 命令账本/Outbox | ❌ 未做 | 设备指令派发前/回包后/重启恢复的 exactly-once 证据链 |

## 三·补、第二切片：实验级 Resume（E-6）单事务（2026-11-08）

恢复原实现跨 5 个连接：读当前扣留 → 清 Hold → experiment_run 回 queued →
同步实验表 → 作废旧 paused workflow run → 写 pause_request 结果；中间崩溃会留下
「Hold 解了但 run 没回 queued」或「run 回了 queued 但旧 workflow run 没作废」的半成品。

第二切片把以下写集收进同一事务（PauseCoordinator.resumeExperimentRun）：

1. pause_request 幂等键（claimTx）；
2. RuntimeHold.coveringForExperimentCheckedTx 读覆盖（事务内可见已清 Hold）；
3. RuntimeHold.clearTx 解除可解扣留；
4. RunControl.resumeTx：experiment_run 回 queued / control_state=NONE、
   同步 experiment 表（非批次）、作废旧 paused workflow run（M-01 契约）、事务内复查扣留；
5. pause_request 结果 + pause_event(RESUME_REQUESTED)。

commit 之后才做批次聚合（BatchOrchestrator.onRunStatusChanged）和
scheduler.resumeRun（内存队列）；commit 后崩溃由启动 rehydrateQueued 兜底。

测试：PauseCoordinatorDbSelfTest 36/0（新增恢复单事务/RESUME_REQUESTED 事件/
重复恢复不新增事件 9 条断言）；test-all 1136/0；4.20 页面恢复链路回归。

## 三·补二、第三切片：实验级 Stop（E-5）单事务（2026-11-08）

停止原实现跨多连接：写 stop_requested / aborted、同步实验表、写幂等结果；中间崩溃会
留下「run 已 aborted 但实验状态还是 running」或「幂等键在、停止事实丢」。

第三切片（PauseCoordinator.stopExperimentRun）：

1. pause_request 幂等键（claimTx）；
2. RunControl.stopTx：running 分支只落 stop_requested=true（在途动作由执行器边界收尾）；
   非 running 分支落 aborted + 非批次时同步实验状态；
3. pause_request 结果 + pause_event(STOP_REQUESTED) 同事务 commit；
4. 批次聚合在 commit 后补（启动 rehydrate 兜底）。

测试：PauseCoordinatorDbSelfTest 46/0（新增运行中/非运行态停止、STOP_REQUESTED 事件、
重复停止不新增事件等）；test-all 1146/0。

边界：Workflow 级 Pause（E-1~E-3）仍由 PauseManager 多连接实现；PAUSING 异步收敛
worker 与真实事件总线消费者仍未做。

## 三·补三、第四切片：PAUSING 异步中间态（2026-11-08）

experiment_run 新增 pause_requested_at（受理时间）；调度器每 tick 调
RunControl.markPausing(db, tenant, 5)：只把「受理超过 5 秒、仍 status=running、
control_state=PAUSE_REQUESTED」的 run 标成 PAUSING（界面显示「暂停中」），
绝不写 paused；执行器动作边界 settle 时清 pause_requested_at 并回 NONE。
queued run 仍由 convergeQueuedPauses 直接收成 paused。

测试：RunControlDbSelfTest 51/0（超阈值标 PAUSING / 刚受理不误标）；
test-all 1148/0。

边界：这是可观测中间态的最小 worker；暂停请求没有 requestId 时仍能靠
pause_requested_at 推进（该列由受理事务写入）。

## 四、不变量与测试

- **原子性**：成功受理后 `pause_request` / `runtime_hold` / `control_state` / `pause_event` 同时存在；
  目标不存在等失败时四者都不存在（`pause_event` 无半成品）；
- **幂等**：同 requestId 重复请求不新增 Hold / 不新增事件；
- **派发幂等**：`dispatchPending` 重复调用不会重复标记已派发事件；
- 测试：`PauseCoordinatorDbSelfTest` 27/0（新增 6 条 Outbox/回滚断言）；
- 回归：`./scripts/test-all.sh` 1110/0；4.17 / 4.20 实验级暂停链路（S-04-B 语义）通过。

## 五、下一步（V2 完整 / P-07）

1. 把 Resume/Stop、Workflow 级 Pause 逐步改为同事务（复用 `raiseTx` / `markControlRequestedTx` 模式）；
2. 增加 `PAUSING` 异步收敛 worker（受理后先写 PAUSE_REQUESTED，收敛完成再写 paused/NONE）；
3. pause_event 对接真实事件总线/审计中心；
4. P-07：命令账本（CREATED→DISPATCH_STARTED→…→UNKNOWN→RECONCILING）+ 四个崩溃点不重复物理动作。
