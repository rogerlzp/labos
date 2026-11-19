# LabOS Workflow Runtime V1 数据库物理设计 v1.1（单实例版）

- 日期：2026-11-19
- 数据库：PostgreSQL 17
- 运行模型：一个 Runtime Host 进程 + 一个 PostgreSQL 数据库
- 状态：当前空库初始化 DDL / Schema Contract 已实现并通过 CI
- 代码基线：f1e6ec0fb7a429bb894b7cd14770b85b67b44828
- CI：Run 44562728674（success）

## 1. 设计原则

这是全新 Workflow Runtime 的独立数据库，不依赖旧 GraphRunner 表，不做历史表迁移、双写或双读。初始化文件只用于空数据库建库。

V1 只部署一个 Runtime Host 进程。数据库不承担 Host 选举、跨进程租约或多实例接管。允许一个 Host 内多个线程和多个 Run 并行，因此仍保留事务、行锁、唯一约束、部分唯一索引和 version/CAS。

## 2. 最终 Schema

最终 Schema 为 31 张表：

| 领域 | 表 |
|---|---|
| 租户与定义 | tenant、definition_snapshot |
| Run 与幂等 | workflow_run、run_request_dedup |
| 图执行 | execution_token、node_activation、node_attempt |
| Worker 执行证据 | worker_execution_fact |
| 调度容量 | scheduler_capacity、dispatch_reservation |
| 并行与汇合 | split_activation、branch_activation、join_activation |
| 循环与等待 | loop_context、wait_registration、retry_registration |
| 变量与输出 | flow_variable、branch_output |
| 事件与消息 | runtime_event、inbox_receipt、inbox_protocol_violation、runtime_outbox |
| 设备命令 | command_ledger、command_dispatch_attempt |
| 资源占用 | resource_lease |
| Drain 与安全点 | drain_record、drain_item |
| 检查点与人工处置 | runtime_checkpoint、manual_intervention、quarantine_record、runtime_audit |

## 3. 明确不存在的分布式结构

以下表和字段不属于 V1 Schema，且由 Schema Contract 主动检查禁止出现：

- 表：worker_execution_lease、resource_fence_counter；
- workflow_run.coordinator_id / coordinator_epoch；
- node_attempt.dispatch_epoch；
- worker_execution_fact.dispatch_epoch / lease_epoch；
- runtime_event.coordinator_epoch；
- runtime_checkpoint.coordinator_epoch；
- command_dispatch_attempt.fencing_token；
- resource_lease.fencing_token；
- runtime_outbox.claim_owner / claim_epoch / claim_until。

不存在 Leader Election、DB Worker Lease、跨实例 Coordinator ownership、Resource fencing counter 或 Outbox claim lease。

## 4. 容易混淆但仍需保留的业务结构

### 4.1 worker_execution_fact

append-only 执行证据账本。按 tenantId + attemptId 归属，以 factKey 幂等，以 factSeq 排序。factSeq 由 Repository 在锁定 Attempt 行的事务内分配。数据库 Trigger 禁止 UPDATE / DELETE。

它不是 Worker 执行权租约，不包含 Worker lease epoch。

### 4.2 resource_lease

设备/资源的业务占用记录，用于防止同一设备被多个 Run 同时占用。保留 lease_state、lease_version、expires_at、evidence 等业务字段。

它不包含 fencingToken 或 fence counter。expires_at 到期不等于物理设备空闲；未获得设备安全点/状态回读证据时必须保留占用或隔离。

### 4.3 dispatch_reservation

SchedulerCapacity 的容量预留，控制本地 WorkerPool 的全局容量和每 Run 配额。它不是 Worker DB Lease，也不授予跨进程执行权。

### 4.4 command_dispatch_attempt

记录同一 commandId 的实际发送尝试、dispatchNo、发送状态和 Adapter 结果。它不保存 fencing token。V1 一个设备型 Attempt 仍只有一个逻辑 commandId。

### 4.5 runtime_outbox

持久化发送意图，状态为 PENDING / SENDING / DELIVERED / DEAD_LETTER。没有 claim owner、claim epoch 或 claim_until。唯一 Dispatcher 负责领取和发送；外部 Adapter 调用在事务外执行。

## 5. 关键数据库约束

- 同一 Token 最多一个 live Activation；
- 同一 Activation 最多一个 live Attempt；
- 同一 Attempt 最多一个逻辑 Command；
- 同一 Resource 同时最多一个 ACTIVE / RELEASING / QUARANTINED / EXPIRED_UNVERIFIED ResourceLease；
- Inbox source + source_message_id 唯一；相同身份不同 payload hash 另记协议违规；
- Worker execution fact 同一 Attempt 的 factKey 和 factSeq 唯一；
- Worker execution fact 禁止 UPDATE / DELETE；
- Run eventSeq 在 Run 行锁保护下递增；
- Capacity reservation 通过容量行锁和条件更新防止超卖。

## 6. Outbox 与进程重启恢复

启动恢复必须先核验 in-flight Attempt，再恢复 Outbox：

- DISPATCHED / ACCEPTED / STARTED Attempt 都是启动恢复候选；
- 没有副作用边界和不确定 Command 的 Attempt 可 ABANDONED_SAFE，释放容量并恢复 Token；
- 已恢复 Attempt 的旧 WORKER_DISPATCH 必须 DEAD_LETTER；
- 内部 Outbox 消息可以恢复为 PENDING；
- WORKER_DISPATCH 只有对应 Attempt 仍为 DISPATCHED 时才可重排；
- DEVICE_COMMAND 只有 Command 仍为 CREATED / DISPATCH_PENDING 且未越过 SEND_STARTED 时才可重排；
- SEND_STARTED 之后结果不确定的设备命令不得自动重发，必须按 commandId 查询、状态回读或人工核验。

## 7. 初始化与验证

权威文件：
- `db/workflow_runtime/001_init.sql`：空库初始化；
- `db/workflow_runtime/002_schema_contract.sql`：表清单、禁止字段、唯一索引和行为约束；
- `db/workflow_runtime/tests/`：PostgreSQL 并发与原子性测试；
- `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/persistence/WorkflowRuntimeRepository.java`：事务边界。

PostgreSQL 17 CI Run 44562728674：
- 空库初始化：PASS；
- 31 张表 Schema Contract：PASS；
- 双会话基础并发测试：PASS；
- CON-01..CON-14：14/14 PASS；
- 事务原子性与 Inbox 冲突：PASS；
- Java Repository 集成测试：8/8 PASS；
- WorkerExecutionRegistry 测试：2/2 PASS。

双会话测试验证的是单 Host 内多线程可能触发的数据库事务竞争，不代表多个 Runtime Host 实例协同。CI 不证明真实 WorkerPool、Coordinator、Adapter 和设备固件已完成 HIL 验证；这些属于运行时集成与硬件验收，不是数据库物理设计的通过项。
