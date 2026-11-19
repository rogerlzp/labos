# Workflow Runtime 数据库 SQL 实现契约 v1.0

本文件将 T01–T23 推进到 SQL 级实现合同。SQL 为 PostgreSQL 17 模板，参数由驱动绑定。默认 READ COMMITTED；同 Run 控制流写事务先锁 Run 行；禁止跨 Run 事务。

## 0. 公共事务协议

### 0.1 Run 写事务头

```sql
BEGIN;
SELECT status,control_gate,commit_version,event_seq,coordinator_id,coordinator_epoch,recovery_state
FROM workflow_runtime.workflow_run
WHERE tenant_id=:tenant_id AND run_id=:run_id FOR UPDATE;
-- 校验 coordinator_id / epoch；检查 gate 与 recovery_state
```

后续对象按统一表顺序和主键升序锁定。每个 CAS 检查 affected_rows：1 成功；0 按事务定义解释为 STALE/幂等重复/拒绝；大于 1 回滚并视为实现错误。

### 0.2 多 Event 游标分配

每个事务按实际事件条数预留连续区间，不能固定 +1：

```sql
UPDATE workflow_runtime.workflow_run
SET commit_version=commit_version+1,event_seq=event_seq+:event_count,updated_at=clock_timestamp()
WHERE tenant_id=:tenant_id AND run_id=:run_id
RETURNING commit_version,event_seq-:event_count+1 AS first_event_seq,event_seq AS last_event_seq;
```

随后同事务插入该连续区间的 Event，插入行数必须等于 event_count。Run 投影、Event、Outbox、Audit 在同一事务提交。

### 0.3 Inbox Receipt

```sql
INSERT INTO workflow_runtime.inbox_receipt(tenant_id,source,source_message_id,payload_hash,message_type,run_id)
VALUES(:tenant_id,:source,:message_id,:hash,:type,:run_id)
ON CONFLICT(tenant_id,source,source_message_id) DO NOTHING RETURNING inbox_id;
```

无返回行时读取首次 Receipt：hash 相同为重复；hash 不同写 inbox_protocol_violation 和 Audit，不覆盖原始身份。Receipt 首次落库不持有 Run 锁；T14 消费事务按 Run→业务对象→Inbox 顺序处理。

### 0.4 Outbox claim

```sql
SELECT tenant_id,outbox_id,dedup_key,payload
FROM workflow_runtime.runtime_outbox
WHERE outbox_state='PENDING' AND available_at<=clock_timestamp()
ORDER BY available_at,created_at
FOR UPDATE SKIP LOCKED LIMIT :batch;
```

claim owner/epoch/lease 提交后才发送。发送后回写必须匹配 outbox_id + owner + epoch；不得在事务内调用外部系统。

## 1. T01–T23 事务 SQL 合同

| ID | 关键读写与 CAS | 幂等键/约束 | 事务外动作 |
|---|---|---|---|
| T01 | request dedup 插入冲突忽略；成功后创建 Run、首 Token、Event，再回填 run_id | tenant_id,request_id + payload_hash | 返回 Run |
| T02 | Run 锁；CREATED→RUNNING CAS；创建初始 Activation/Token | Run 状态与版本 | 唤醒 Coordinator |
| T03-R | Run→Token→SchedulerCapacity 锁；检查余量；capacity+1 与 Reservation 插入同事务；Token READY→RESERVED | Token version；capacity key | 无 |
| T03-D | 写 Attempt/Activation；Reservation 释放或消费；Outbox+Event 同事务 | dispatch_id、attempt_id、outbox dedup_key | 唤醒 Worker |
| T04-A | Attempt DISPATCHED→ACCEPTED CAS，匹配 dispatch_id/epoch/version | dispatch_id | 无 |
| T04-S | Attempt 仅 ACCEPTED→STARTED CAS | attempt_id + version | 无 |
| T05 | Attempt STARTED→SUCCEEDED；BranchOutput 插入；Token consume；Activation 完成；创建后继 Token | attempt_id；activation_id/output_key | 唤醒 Scheduler |
| T06 | Attempt→FAILED；RetryRegistration 与状态变化同事务 | retry_id；activation_id/attempt_no | Timer 唤醒 |
| T07 | WaitRegistration 插入；Token/Activation→WAITING | wait_id | 注册订阅/Timer |
| T08 | Wait ACTIVE→SATISFIED/EXPIRED CAS；旧 Attempt→ABANDONED_SAFE；唯一 continuation | wait_id + version | 唤醒 Scheduler |
| T09 | Split、完整 Branch 集合及 Token 原子插入；源 Token 消费 | split_id + branch_key | 唤醒 Scheduler |
| T10 | Branch Arrival CAS；锁 Split/Join；最后 Arrival 单次 Join WAITING→RELEASED | split_id/branch_key；join version | 唤醒 Scheduler |
| T11 | Loop iteration CAS；创建下一轮 Token；或 Loop→COMPLETED | loop_context_id + version | 唤醒 Scheduler |
| T12 | CommandLedger + Outbox + Event 同事务 | UNIQUE(tenant_id,attempt_id) | 唤醒设备 Dispatcher |
| T13 | Command→SEND_STARTED；插 DispatchAttempt 并提交后调用 Adapter | command_id；dispatch_id | Adapter 调用 |
| T14 | Inbox PROCESSING CAS；按 command_id 核验；更新 Command/Lease/Event/Outbox；Receipt→PROCESSED | source/message_id；command_id/device sequence | ACK（不等于物理完成） |
| T15 | Run gate OPEN→PAUSE/STOP；DrainRecord + Event + Outbox 同事务 | 一个开放 Drain/Run | 唤醒 Drain |
| T16 | Drain REQUESTED→DRAINING；分页插 DrainItem，更新 cursor/generation | drain_id + version + item PK | 继续分页 |
| T17 | Reservation HELD→RELEASED CAS；容量-1 同事务；Pause 保留 READY，Stop INVALIDATED | reservation_id + version | 重复扫描 |
| T18 | 设备停止/回读在事务外；结果核验后更新 Command/Lease；关闭 Wait/Retry/Split/Branch/Join/Loop | 对象 version / evidence | Adapter / 设备核验 |
| T19 | Run 锁内重新检查所有未决 Command、Lease、Token、Wait、Retry、Split/Branch/Join/Loop、DrainItem；全真才终态 CAS | Run version + drain version | 无 |
| T20 | REPEATABLE READ READ ONLY 单一快照读所有投影；保存 cursor、规范化 snapshot/hash | Run + commit_version 唯一 | 快照持久化可在后续短事务 |
| T21 | 保存不可变证据；UNKNOWN Command/Lease 转核验或隔离 | subject + version + evidence hash | 查询 Adapter/设备 |
| T22 | ManualIntervention OPEN→DECIDED CAS；状态更新、Audit、Event 同事务 | intervention_id + version | 通知操作者 |
| T23 | Quarantine CLEAR_PENDING→CLEARED CAS；证据确认后 Lease RELEASED；Audit/Event 同事务 | quarantine_id + version + evidence hash | 通知资源调度 |

## 2. 关键 SQL

### 2.1 Scheduler capacity（T03-R）

```sql
UPDATE workflow_runtime.scheduler_capacity
SET reserved_count=reserved_count+1,capacity_version=capacity_version+1,updated_at=clock_timestamp()
WHERE tenant_id=:tenant_id AND capacity_key=:capacity_key AND reserved_count<capacity_limit
RETURNING reserved_count,capacity_version;
```

0 行代表容量不足。释放时 Reservation 必须先 CAS HELD→RELEASED；仅 CAS 成功者在同事务 capacity-1，且断言不为负。

### 2.2 Command 未知窗口（T13）

```sql
UPDATE workflow_runtime.command_ledger
SET command_state='SEND_STARTED',version=version+1,updated_at=clock_timestamp()
WHERE tenant_id=:tenant_id AND command_id=:command_id
AND command_state IN ('CREATED','DISPATCH_PENDING') AND version=:expected_version
RETURNING version;
```

提交后调用 Adapter。提交后、调用前崩溃也进入 UNKNOWN，不能仅凭本地日志认定未发送。NON_IDEMPOTENT 且不可查询时进入隔离/人工处置。

### 2.3 Fencing token

```sql
INSERT INTO workflow_runtime.resource_fence_counter(tenant_id,resource_id,last_fencing_token)
VALUES(:tenant_id,:resource_id,0) ON CONFLICT DO NOTHING;
SELECT last_fencing_token FROM workflow_runtime.resource_fence_counter
WHERE tenant_id=:tenant_id AND resource_id=:resource_id FOR UPDATE;
UPDATE workflow_runtime.resource_fence_counter
SET last_fencing_token=last_fencing_token+1,updated_at=clock_timestamp()
WHERE tenant_id=:tenant_id AND resource_id=:resource_id RETURNING last_fencing_token;
```

同事务插入 Lease。数据库单调 token 不代表设备/网关已强制拒绝旧 token，必须 HIL 证明。

### 2.4 Checkpoint

在 REPEATABLE READ READ ONLY 事务内先读 Run commit_version/event_seq/epoch，再读全部 Run 投影；规范化序列化后算 SHA-256。Checkpoint 写入可在后续短事务，保留读取到的游标与完整 payload/hash。分块必须来自同一快照；禁止跨事务分页拼接。

## 3. 锁顺序、重试和重要裁决

推荐锁序：Run → Split/Join → Token → Activation → Attempt → Wait/Retry → Command → ResourceLease（resource_id 排序）→ SchedulerCapacity（capacity_key 排序）→ Reservation → Inbox（T14 最后）。T03-R 必须与此锁序一致，不能先锁 Reservation 再锁 Capacity。

- deadlock_detected / serialization_failure：整个事务回滚，以原幂等身份有限次退避重试。
- Commit 响应丢失：按 request_id/event_id/command_id 查询原结果，禁止盲目重复物理动作。
- CAS 0 行必须按业务语义分类，不能统一重试。
- T17 重复扫描：Drain 中 T05 可能产生 READY 后继 Token；Pause 保留 READY，Stop INVALIDATED。
- T18 设备操作在事务外；事务仅记录设备核验结果与控制流收敛。
- T19 必须检查全部未决副作用及控制流结构。
- V1 一个设备 Attempt 一个 command_id；设备完成事实先写 BranchOutput，再由纯计算节点更新共享变量。

本文件是 SQL 实现合同，不是 Repository 代码或测试通过证明。


## 4. Repository 实现映射与实测（2026-11-19）

独立 Java 17 模块：`workflow-runtime/`。主实现：`WorkflowRuntimeRepository`。当前 Repository primitive 已映射 T01–T23：T01 createRun；T02 startRun；T03-R reserveCapacity / T03-D dispatchAttempt；T04-A acceptAttempt / T04-S startAttempt；T05 completeAttempt；T06 scheduleRetry；T07 registerWait；T08 closeWait；T09 createSplit；T10 arriveBranch；T11 advanceLoop / completeLoop；T12 createCommand；T13 claimOutbox / markCommandSendStarted / markOutboxDelivered；T14 receiveInbox / applyCommandEvent / markInboxProcessed；T15 requestDrain；T16 advanceDrainScan；T17 releaseReservation；T18 acquireResourceLease / recordSafePointEvidence；T19 tryFinalizeDrain / markDrainManualRequired；T20 createCheckpoint；T21 markCommandUnknown / resolveUnknownCommand；T22 decideIntervention；T23 clearQuarantine。

**当前 PG17 实测：** GitHub Actions Run ID `44498938264`，commit `10d99fd3ca0a22f03fdb186b6a88bef9b8dbb9d5`，全绿。包含空库初始化、Schema 合同、双连接基础竞态、CON-01..CON-14、事务原子性/Inbox 冲突测试、Java Repository 集成测试（5 tests，0 failures/errors）、最终表数核对。

**实现边界：** 这证明当前 SQL schema、Repository 覆盖的事务路径及当前测试集可在 PostgreSQL 17.12 执行；不证明全部 T01–T23 崩溃矩阵已经完成，不证明 Worker/Coordinator/Adapter 已接入，更不证明设备物理 fencing 生效。T08 当前只允许将 CREATED/DISPATCHED/ACCEPTED 的旧 Attempt 安全关闭；已 STARTED 的设备任务必须先走 Command/设备核验，不允许本方法强制关闭。


## 5. Worker 执行事实与过期恢复协议（2026-11-19）

新增表：worker_execution_lease、worker_execution_fact。Worker fact 由数据库 trigger 强制 append-only；factKey 唯一，重复必须内容一致。

- T04-S：Attempt STARTED、Activation/Token RUNNING、Worker lease ACTIVE、HANDLER_STARTED 在一个事务中提交。接口必须提供明确的 leaseExpiresAt。
- Worker lease renewal：tenant/run/attempt/worker/leaseEpoch/expectedVersion 精确 CAS；只允许未过期 ACTIVE lease 续期。
- Worker 外部副作用：先提交 SIDE_EFFECT_STARTED，再允许执行。任何外部调用不得绕过该门槛。
- Command dispatch：T13 在 Run → Token → Activation → Attempt → WorkerLease → Command → Outbox 锁序下，验证 Attempt STARTED、Worker lease ACTIVE、Outbox claim 所有权；同事务写 Command SEND_STARTED、CommandDispatchAttempt 和 SIDE_EFFECT_STARTED。事务提交后才能调用 Adapter。
- T05：BranchOutput/Attempt/Activation/Token 完成、EXECUTION_RIGHT_RELEASED fact、Worker lease RELEASED、Scheduler reservation 释放同事务完成。
- Recovery：Worker lease 过期且无 SideEffect fact、无模糊 Command 时，撤销未发送 Command/Outbox，Attempt/Activation ABANDONED_SAFE、Token READY、Lease EXPIRED_SAFE、释放 reservation。若有 SideEffect fact 或模糊 Command，则 Command UNKNOWN、Attempt UNKNOWN、Lease EXPIRED_UNVERIFIED、Run MANUAL_REQUIRED/BLOCKED，并创建人工干预；禁止自动重试。

**重要限制：** DB lease 不会杀死暂停后复活的旧进程；SideEffectStarted 也不代表物理动作已完成。设备/网关 fencing、commandId 幂等和物理状态回读仍须 Adapter/HIL 验证。

**当前 PG17 实测：** Actions Run 44502256816，commit 18f6583ab25e687bd9b56db1d591171e52259bad，全绿。CON-01..14 全部 PASS；Java Repository 集成测试 7/7 PASS；空库 DDL、Schema 合同、事务原子性/Inbox 冲突和 33 张表核对全部 PASS。
