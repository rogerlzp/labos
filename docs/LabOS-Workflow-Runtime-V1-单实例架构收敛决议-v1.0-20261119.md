# LabOS Workflow Runtime V1 单实例架构收敛决议

- 日期：2026-11-19
- 状态：V1 架构约束，取代旧设计中与多实例/分布式协调有关的条款
- 适用分支：docs/workflow-runtime-single-instance-20261119
- 基线：d4ef67c0e52a9a1ed9fd953e2e7a8cc5a908bca7
- 目标数据库：PostgreSQL 17

## 1. 决议

LabOS Workflow Runtime V1 **只运行一个 Runtime 进程实例**。V1 不支持多 Runtime 实例，不支持多个 Coordinator/Dispatcher/Recovery 节点协同，不做水平扩展，也不为未来分布式部署预留需要当前实现承担的协议复杂度。

单实例约束是部署与运行时硬约束，不是“当前先单实例、代码仍按分布式设计”。V1 的实现、DDL、事务合同、测试和运维说明均以此决议为准。

## 2. 保留与删除的边界

### 2.1 必须删除的分布式协调机制

- Coordinator owner/epoch、leader election、跨实例 owner fencing。
- Worker DB lease、leaseEpoch、leaseVersion、dispatchEpoch 以及基于租约超时的多执行者恢复协议。
- 多实例 Worker/Dispatcher/Recovery 的竞争、抢占和重复扫描保护逻辑。
- Resource fencing counter、fencing token，以及要求设备/网关识别分布式执行世代的协议。
- 任何“未来多实例阶段启用”的隐藏路径、配置项、扩展点或测试假设。
- 为多节点竞争而引入、但单实例正确性并不需要的数据库锁与状态字段。

### 2.2 必须保留的单实例正确性机制

- PostgreSQL 事务、外键、唯一约束、部分唯一索引、行锁和必要的 CAS/version 检查。
- 一个 Run 一个 GraphCoordinator；Coordinator 通过进程内串行 Mailbox 独占图状态写入。
- 一个全局有界 WorkerPool、每 Run 并发额度、进程内 Attempt 执行登记与取消信号。
- Attempt/Activation/Token 的持久化状态机和不可变执行事实。
- Command Ledger、稳定 commandId、Inbox 去重、Outbox 持久化投递、设备回执和状态回读。
- ResourceLease 作为**业务资源占用记录**：防止同一设备被多个 Run 同时使用；不再包含 fencing token，不承担跨实例执行权协调。
- UNKNOWN、MANUAL_REQUIRED、QUARANTINED 等保守状态；无法证明物理动作结果时禁止自动重发。
- Runtime 进程重启后的数据库恢复扫描。重启恢复是单实例故障恢复，不是多实例协调。

## 3. 单实例运行模型

### 3.1 进程与组件

一个 Runtime Host 进程持有唯一的：
- RunCoordinatorRegistry；
- 全局有界 WorkerPool；
- OutboxDispatcher；
- EventIngress/EventDispatcher；
- RecoveryService；
- ResourceAdmissionService。

每个 Run 只有一个 Coordinator。Coordinator 通过串行 Mailbox 修改图状态；Worker 只执行节点动作并返回结果，不直接推进图。

上述组件可以有多个线程，但不能在 V1 中运行于多个 Runtime 进程。进程启动时必须取得单实例启动锁；若已有实例持有锁，新实例启动失败，不允许自动形成双活。

### 3.2 并发与状态保护

- 进程内互斥/并发控制负责 Worker 执行登记、取消和本地调度。
- 数据库 version/CAS 负责拒绝旧状态上的迟到写入；它不是 fencing token。
- 数据库事务负责 Run 内多表状态原子提交。
- attemptId 识别一次执行尝试；commandId 识别一次逻辑设备命令。
- 不再使用 coordinatorEpoch、dispatchEpoch 或 leaseEpoch 判定执行者身份。

### 3.3 Worker 超时和进程崩溃

V1 不使用 DB Worker Lease。Worker 的运行中状态由 Attempt 持久化状态和进程内执行登记共同表达。

- 进程仍存活、Worker 超时：向本地执行登记发送取消请求；只有能证明无外部副作用时才允许结束 Attempt 并重试。
- 已越过外部副作用边界：记录 SIDE_EFFECT_STARTED；若结果不确定，转 UNKNOWN/MANUAL_REQUIRED，不自动重发。
- Runtime 进程崩溃：进程内 Worker 随进程终止；重启后扫描 STARTED/未终结 Attempt，根据持久化副作用事实和 Command 状态分类恢复。
- 不得把“进程崩溃”或“线程中断”当作设备动作已停止的证据。物理动作仍需 commandId 查询、设备状态回读或人工核验。

## 4. 资源占用模型

ResourceLease 保留为数据库业务事实，字段只表达：
- leaseId、resourceId、ownerRunId/ownerAttemptId；
- ACTIVE/RELEASING/RELEASED/QUARANTINED/EXPIRED_UNVERIFIED；
- leaseVersion、acquiredAt、expiresAt、releasedAt、evidence。

删除 fencingToken 与 resource_fence_counter。资源准入在单实例中由 ResourceAdmissionService 串行化，并在事务中创建唯一活动 ResourceLease。唯一约束用于防止错误占用，不代表分布式锁。

Lease 过期不代表物理设备空闲。只有设备状态和安全点证据满足释放条件，才能释放资源；否则保持隔离或人工处置。

## 5. Outbox / Inbox 简化

Outbox 保留持久化和重启后续发能力，但不使用跨实例 claim owner/epoch/租约协议。

单实例 Dispatcher 通过数据库事务领取一条待发送消息并标记本进程处理状态，提交后在事务外调用 Adapter。若进程在发送边界后崩溃，设备命令按 commandId 查询；无法确定结果时转 UNKNOWN，禁止盲目重发。

Inbox 继续按来源与 messageId 去重，payload hash 冲突写入协议违规证据。Inbox 与业务状态投影仍在同一事务提交。

## 6. V1 Schema 删除清单

必须从最终初始化 DDL 和 Schema 合同中删除：
- worker_execution_lease；
- resource_fence_counter；
- node_attempt.dispatch_epoch；
- worker_execution_fact.dispatch_epoch / lease_epoch；
- runtime_event.coordinator_epoch；
- runtime_checkpoint.coordinator_epoch；
- command_dispatch_attempt.fencing_token；
- resource_lease.fencing_token。

worker_execution_fact 可保留为 append-only 执行证据表，但其身份只使用 attemptId、factKey、factSeq、actor、commandId/dispatchId（如适用）和 payload，不记录分布式 epoch。

注意：这是最终 Schema 目标。现有基线 DDL/Repository 仍含上述字段和表；在完成后续实现提交及 PG17 CI 前，不得宣称数据库已经完成收敛。

## 7. 测试要求

删除多连接/多实例竞争作为架构验收目标；保留真正验证数据库约束的测试。新增/保留以下单实例测试：

1. 同一 Runtime 进程只允许一个 Host 启动；第二次启动明确失败。
2. 单个 Run 的 Coordinator Mailbox 串行处理消息。
3. WorkerPool 全局容量和每 Run 配额不超限。
4. 同一设备资源不能被两个 Run 同时占用。
5. Worker 取消成功且无副作用时允许安全重试。
6. Worker 已提交 SIDE_EFFECT_STARTED 后超时，不自动重发。
7. 进程崩溃后，未发送命令可安全恢复；发送结果不明的命令进入 UNKNOWN。
8. 重复/迟到设备事件按 commandId 幂等处理。
9. Outbox 发送后进程崩溃，重启后先查询 commandId，不盲目发送第二次。
10. PostgreSQL 17 空库初始化、Schema 合同、事务原子性和故障注入全部通过。

不再编写或维护多 Runtime 实例抢占、Lease 过期后新实例接管、设备 fencing token 拒绝旧实例等 V1 测试。

## 8. 文档优先级与实施状态

本决议优先于以下旧文档中与多实例、分布式 fencing、Worker DB Lease 相冲突的段落：
- docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md
- docs/LabOS-Workflow-Runtime-Worker执行事实与恢复协议-v1.0-20261119.md
- docs/LabOS-Workflow-Runtime-数据库物理设计-v1.0-20261119.md
- docs/LabOS-Workflow-Runtime-T01-T23-SQL实现契约-v1.0-20261119.md

本文件是架构收敛决议，不等于代码已完成。后续实施必须同步更新架构正文、DDL、Schema contract、Repository、测试、CI 和 README；每一阶段以实际提交和 PG17 CI 结果为准。

## 9. 不在 V1 目标内

- 多 Runtime 实例部署与水平扩展；
- Leader Election、分布式锁、分布式 Worker Lease；
- Coordinator/Worker/Dispatcher 的跨进程 owner fencing；
- 设备侧 fencing token；
- 多节点 Recovery Orchestrator；
- 为未来分布式扩展预埋字段或抽象。

若未来确实需要多实例，应作为新的架构版本重新设计和验证，不得把 V1 的单实例 version/CAS 或 ResourceLease 重新解释为分布式 fencing。
