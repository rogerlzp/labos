# LabOS Workflow Runtime V1 单实例架构（规范基线）

- 日期：2026-11-19
- 版本：V1 Single Instance
- 状态：实现基线；Repository/DDL/Schema Contract/恢复服务已按单实例模型重构，最新 HEAD 的 PostgreSQL 17 CI 已通过
- PostgreSQL：17
- 部署：一个 Runtime Host 进程，一个 PostgreSQL 数据库

## 1. 架构决议

V1 只部署一个 Runtime Host 进程。它可以使用多个线程；部署配置负责保证只有一个 Host 实例，不在数据库中实现实例选举、锁竞争或接管协议。意外启动第二个进程属于部署错误，应由部署方式避免，而不是增加数据库协调子系统。

V1 不设计、不实现、不预留：
- 多 Runtime 实例、水平扩展、跨进程协调；
- Leader Election、分布式锁、分布式 Worker Lease；
- Coordinator owner/epoch、Worker leaseEpoch、dispatchEpoch；
- Resource fencing counter / fencing token；
- 多实例 Dispatcher claim owner/epoch/claim lease；
- 多节点 Recovery 竞争、抢占和接管；
- 以未来分布式部署为由增加的身份字段、协议和扩展点；
- PostgreSQL advisory lock、数据库租约或其他用于 Host 选举/互斥的机制。

未来如确有多实例需求，另立架构版本，不得把 V1 普通 version/CAS 重新解释成分布式 fencing。

## 2. 保留的正确性机制

单实例不等于单线程。以下机制仍是 V1 必需：
- PostgreSQL 事务、行锁、外键、唯一约束、部分唯一索引；
- Run 级串行状态提交、Coordinator Mailbox；
- 全局有界 WorkerPool、每 Run 并发额度；
- Attempt/Activation/Token 持久化状态机；
- 对象 version/CAS，用于拒绝重复或迟到状态转换；
- append-only 执行事实、Runtime Event、Checkpoint；
- Command Ledger、稳定 commandId、Inbox 去重、Outbox 持久化；
- ResourceLease 作为设备/资源的业务占用记录；
- UNKNOWN、MANUAL_REQUIRED、QUARANTINED 和人工核验；
- Runtime 进程重启后的恢复扫描。

这些机制解决的是单进程线程并发、事务原子性、消息重复和进程崩溃问题，不是多实例协调。

## 3. 进程拓扑

一个 Runtime Host 进程中只有一套 RunCoordinatorRegistry、全局有界 WorkerPool、OutboxDispatcher、EventIngress/EventDispatcher、RecoveryService 和 ResourceAdmissionService。

每个 Run 对应一个 GraphCoordinator。Coordinator 通过串行 Mailbox 独占该 Run 的图状态推进。Worker 只执行 Handler，返回结果；不得自行推进 Token/Activation/Run 状态。

Host 唯一性由部署约束保证。V1 不使用 PostgreSQL advisory lock、数据库租约、Leader Election 或启动锁表；Runtime 不承担 Host 实例选举职责。

## 4. 调度与 Attempt 生命周期

典型生命周期：

READY Token → RESERVED → DISPATCHED → ACCEPTED → STARTED → SUCCEEDED / FAILED / UNKNOWN

- attemptId 标识一次节点执行尝试。
- dispatchId 标识一次本地派发消息；不附带 epoch。
- commandId 标识一次逻辑设备命令，生命周期独立于 Worker 线程。
- 同一 Activation 同时最多一个 live Attempt，由唯一索引和事务状态转换保证。
- Worker 执行登记保存在进程内；WorkerPool 全局有界，每 Run 另有并发额度。
- Worker 超时先发出本地取消请求。线程中断/取消成功不是物理设备停止的证据。

## 5. 执行事实与副作用边界

Worker 执行事实保留为 append-only 证据，不再依赖 DB Worker Lease：HANDLER_STARTED、SIDE_EFFECT_STARTED、SIDE_EFFECT_COMPLETED、SIDE_EFFECT_UNKNOWN、EXECUTION_RIGHT_RELEASED。

事实至少包含 tenantId、runId、attemptId、factSeq、factKey、factType、actor、payload、createdAt。factSeq 在同一 Attempt 的锁保护事务内分配；同 factKey 同内容幂等，不同内容拒绝。数据库 Trigger 禁止 UPDATE/DELETE。

所有外部副作用必须先提交 SIDE_EFFECT_STARTED，再调用 Adapter。若调用结果不确定，记录 SIDE_EFFECT_UNKNOWN，Command/Attempt 进入 UNKNOWN；禁止自动重发。

## 6. 设备命令与 Outbox

Outbox 是持久化发送意图，不是分布式队列。单实例 Dispatcher 在事务内选取一条 PENDING 消息并将其标记为本地处理中，提交事务后在事务外调用 Adapter。发送结果按 commandId 进入 Inbox，再在一个业务事务中更新 Command、Inbox、Attempt/Event。

不需要 claim owner、claim epoch 或 claim lease。Runtime 重启后，未越过 SEND_STARTED 边界的消息可回到 PENDING；已越过边界但无确定结果的命令先按 commandId 查询，查询仍不确定则转 UNKNOWN，不得盲目重发。

Adapter/设备回执必须回显 commandId。ACK 仅表示接收，不表示物理动作完成。完成状态需设备完成事件或状态回读证明。

## 7. 资源占用

ResourceLease 是业务资源占用事实，用于防止两个 Run 同时占用同一设备。字段表达 leaseId、resourceId、ownerRunId/ownerAttemptId、state、version、时间和 evidence。

不使用 fencingToken 或 resource_fence_counter。单实例 ResourceAdmissionService 串行化准入，并由数据库唯一活动租约约束兜底。

Lease 超时不表示设备空闲。只有设备状态回读、安全点和释放证据满足条件才释放；无法确认则 QUARANTINED / EXPIRED_UNVERIFIED 并人工核验。

## 8. 崩溃与恢复

### 8.1 Worker 超时（Host 仍存活）

- 本地 Worker 执行登记发出取消请求。
- 能证明没有 SIDE_EFFECT_STARTED、没有 SEND_STARTED、没有不确定 Command：Attempt 可 ABANDONED_SAFE，释放 Scheduler reservation，重新生成 Attempt。
- 已经越过副作用边界或证据不完整：Attempt/Command 标记 UNKNOWN，Run 进入 MANUAL_REQUIRED，关闭控制闸门，不重新发设备命令。
- 不能只依赖超时或线程中断认定无副作用。

### 8.2 Runtime 进程崩溃后重启

进程内 Worker 已随进程退出，但物理设备可能仍在动作。启动恢复扫描所有非终态 Attempt：未越过外部副作用边界的可安全 ABANDONED_SAFE 并重新调度；有 SIDE_EFFECT_STARTED、SEND_STARTED、ACKNOWLEDGED/RUNNING 或状态未知的先查询 commandId / 读取设备状态；不能确认时转 UNKNOWN/MANUAL_REQUIRED；设备明确完成则按已持久化设备回执完成 Attempt，不重复执行动作。

恢复服务只在唯一 Runtime Host 启动流程内执行，不存在多实例接管或 Recovery 竞争。

## 9. 数据库目标 Schema

最终空库初始化 DDL 不得包含：
- worker_execution_lease；
- resource_fence_counter；
- workflow_run.coordinator_id / coordinator_epoch；
- node_attempt.dispatch_epoch；
- worker_execution_fact.dispatch_epoch / lease_epoch；
- runtime_event.coordinator_epoch；
- runtime_checkpoint.coordinator_epoch；
- command_dispatch_attempt.fencing_token；
- resource_lease.fencing_token；
- runtime_outbox.claim_owner / claim_epoch / claim_until。

worker_execution_fact、resource_lease、command_dispatch_attempt 本身仍有单实例审计/业务用途，不应因字段中存在旧 fencing 设计而整表删除。

最终 Schema 表数须由新的 DDL 与 Schema Contract 共同定义，不再沿用旧版 33 表断言。

## 10. V1 验收

1. 部署配置保证只运行一个 Runtime Host；V1 不实现数据库启动锁或实例选举。
2. 同一 Run 的 Coordinator Mailbox 串行推进状态。
3. WorkerPool 全局容量和每 Run 并发额度始终不超限。
4. 同一设备资源不能被两个 Run 同时占用。
5. Attempt 状态转换由事务和 version/CAS 保护。
6. Worker 取消且可证明无副作用时允许安全重试。
7. SIDE_EFFECT_STARTED / SEND_STARTED 后不确定时禁止自动重发。
8. Runtime 崩溃重启后按 Command Ledger、Inbox 和设备回读恢复。
9. Inbox 重复消息幂等；相同 messageId 不同 payload hash 记录协议违规。
10. PostgreSQL 17 空库初始化、Schema Contract、事务原子性和故障注入通过。

## 11. 实施状态声明

本文件定义目标架构，不宣称当前代码已经达到目标。代码收敛必须同步更新 DDL、Schema Contract、Repository、RecoveryService、测试、CI 和实现文档。只有 PG17 CI 对最终提交成功，才可将相应项目标记 PASS。
