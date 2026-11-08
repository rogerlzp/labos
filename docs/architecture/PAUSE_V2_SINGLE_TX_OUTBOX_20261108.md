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
| 实验级 Resume（E-6） / Stop（E-5） | ❌ 仍 V1 | 下一步扩到同事务（Resume 还要释放 Hold + 重新入队） |
| Workflow 级 Pause / Resume（E-1~E-3） | ❌ 仍 V1 | PauseManager 仍多连接；依赖 P-07 命令账本一起做 |
| PAUSING 异步收敛 worker | ❌ 未做 | 当前 accepted→paused 由执行器边界/调度器收敛（S-04-B 最小闭环） |
| P-07 命令账本/Outbox | ❌ 未做 | 设备指令派发前/回包后/重启恢复的 exactly-once 证据链 |

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
