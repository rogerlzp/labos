# Workflow Runtime V1：T01–T23 单实例 SQL 实现契约

- 日期：2026-11-19
- 数据库：PostgreSQL 17
- 架构：单 Runtime Host 进程 + 单 PostgreSQL 数据库
- 状态：与当前单实例 Repository/DDL 对齐；以 CI 结果为验收证据

## 1. 权威实现文件

- 初始化 DDL：db/workflow_runtime/001_init.sql
- Schema Contract：db/workflow_runtime/002_schema_contract.sql
- Repository：workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/persistence/WorkflowRuntimeRepository.java
- 本地 Worker 执行登记：workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/worker/WorkerExecutionRegistry.java
- 启动恢复：workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/recovery/WorkerExecutionRecoveryService.java
- 集成测试：workflow-runtime/src/test/java/com/labos/kernel/workflow/runtime/persistence/WorkflowRuntimeRepositoryTest.java
- PostgreSQL 测试：db/workflow_runtime/tests/

## 2. 单实例边界

V1 由部署约束保证一个 Runtime Host。数据库不负责 Host 唯一性、选举或跨进程租约。允许进程内多线程。

禁止出现：worker_execution_lease、resource_fence_counter、Coordinator/dispatch/lease epoch、fencing token、Outbox claim owner/epoch/lease、Leader Election、多实例 Recovery 接管。

保留：事务、Run/Attempt 行锁、唯一约束、部分唯一索引、普通 version/CAS、全局容量计数、ResourceLease 业务占用、Command Ledger、Inbox/Outbox、append-only Worker 执行事实。

## 3. T01–T23 事务映射

| 编号 | Repository 入口/边界 | 关键约束 |
|---|---|---|
| T01 | createRun | requestId 幂等、定义快照不可变、初始 Token/Event 同事务 |
| T02 | startRun | CREATED CAS；不含 Coordinator 身份/epoch |
| T03-R | reserveCapacity | Run 锁、容量行锁、Token READY→RESERVED |
| T03-D | dispatchAttempt | Attempt/Activation 状态提交与 Worker Outbox 同事务 |
| T04-A | acceptAttempt | dispatchId + Attempt 状态校验 |
| T04-S | startAttempt | ACCEPTED→STARTED；不创建 DB Worker Lease |
| T05 | completeAttempt | Attempt/Activation/Token/Output/Event 原子提交 |
| T06 | scheduleRetry | RetryRegistration 唯一身份和状态约束 |
| T07 | registerWait / closeWait | Wait 状态 CAS |
| T08 | createSplit | Split 和分支 Token 原子创建 |
| T09 | arriveBranch | 分支到达幂等、Join 唯一释放 |
| T10 | advanceLoop / completeLoop | Loop 版本与迭代边界 |
| T11 | createCommand | commandId 稳定、命令 payload 不可变 |
| T12 | beginOutboxSend | 单 Host 本地 Dispatcher 将 PENDING→SENDING |
| T13 | markCommandSendStarted | SEND_STARTED 必须先于 Adapter 调用持久化 |
| T14 | receiveInbox / applyCommandEvent | messageId 去重、payload 冲突审计、commandId 归属 |
| T15 | requestDrain | Run gate 与 Drain 记录同事务 |
| T16 | advanceDrainScan | 有界游标推进 |
| T17 | releaseReservation | 容量释放 CAS，防止重复扣减 |
| T18 | recordSafePointEvidence | ResourceLease 仅凭核验证据释放或隔离 |
| T19 | tryFinalizeDrain | 全部不安全对象闭合后才终态化 |
| T20 | createCheckpoint | Repeatable Read 一致快照 |
| T21 | markCommandUnknown / resolveUnknownCommand | 外部结果不确定时禁止盲目重发 |
| T22 | decideIntervention | 人工决策版本 CAS 与审计 |
| T23 | clearQuarantine | 仅凭显式证据解除隔离 |

## 4. Worker 执行与恢复

- Worker 执行 handle 仅存于进程内 WorkerExecutionRegistry，不持久化执行权租约。
- Worker 外部副作用必须先调用 recordSideEffectFact(..., SIDE_EFFECT_STARTED, ...)，再调用 Adapter。
- 同一 Attempt 的 factSeq 由锁定 Attempt 行后查询 MAX+1 分配；factKey 幂等，冲突拒绝。
- 启动恢复扫描 DISPATCHED、ACCEPTED、STARTED Attempt；按 Run → Token → Activation → Attempt 锁序重新核验。
- 无 SIDE_EFFECT_STARTED 且无 SEND_STARTED/不确定 Command：Attempt、Activation 安全结束，Token 恢复 READY，Scheduler reservation 释放。
- 已越过副作用边界：Command/Attempt UNKNOWN，Run MANUAL_REQUIRED，等待 commandId 查询、设备回读或人工核验。
- 已恢复 Attempt 的旧 WORKER_DISPATCH Outbox 必须 DEAD_LETTER，不能再次派发。
- 启动恢复按 1..1000 条分批；若 scanned 达到 batch limit，必须继续恢复下一批，直到 scanned 小于 limit 后才能启动 WorkerPool/Dispatcher。
- Future.cancel、线程中断、Worker 消失都不代表设备动作停止。

## 5. Outbox 单实例语义

Outbox 不使用 claim owner/epoch/claim_until。唯一 Dispatcher 在短事务中锁定 PENDING 行并改为 SENDING，提交后调用外部 Adapter。设备命令发送前持久化 SEND_STARTED。Runtime 重启恢复顺序必须是**先核验 Attempt，再恢复 Outbox**：
- 普通内部消息可恢复为 PENDING；
- WORKER_DISPATCH 只有在其 Attempt 仍为 DISPATCHED 时才允许重新排队；已 ACCEPTED/STARTED 或已恢复的 Attempt 对应旧消息必须 DEAD_LETTER；
- DEVICE_COMMAND 只有在 Command 仍为 CREATED/DISPATCH_PENDING、且未越过 SEND_STARTED 边界时才允许恢复为 PENDING；
- 已越过 SEND_STARTED 的设备命令（包括 CANCEL_REQUESTED）保持不自动重发，进入 commandId 查询/UNKNOWN/MANUAL_REQUIRED 恢复路径；进入人工恢复时，同 Attempt 尚未发送的命令取消并 DEAD_LETTER；
- dispatchAttempt 必须把 attemptId 写入 Outbox payload，供重启恢复核验消息归属。

## 6. 测试和完成标准

CI 必须在 PostgreSQL 17 上依次通过：
1. 空库初始化；
2. Schema Contract（31 张表，禁止分布式字段/表）；
3. 双会话数据库事务并发矩阵 CON-01..14（模拟单 Host 多线程的 DB 竞争，不是多实例 Runtime 测试）；
4. 事务原子性和 Inbox 冲突；
5. Java Repository 集成测试（8/8）；
6. WorkerExecutionRegistry 生命周期测试（2/2）。

当前代码基线 f1e6ec0fb7a429bb894b7cd14770b85b67b44828 的 PostgreSQL 17 CI Run 44562728674 已通过：31 表 Schema Contract、CON-01..14、事务原子性、Inbox 冲突、Repository 8/8、Registry 2/2。CI 通过只证明上述测试覆盖范围内的正确性，不代表真机 Adapter、设备状态回读或完整 Runtime Host 装配已经验收。
