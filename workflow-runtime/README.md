# Workflow Runtime 独立模块

本模块是全新 Workflow Runtime 的 PostgreSQL 持久化层，不依赖旧 GraphRunner 表。

## V1 部署约束

V1 只部署一个 Runtime Host 进程，由部署配置保证实例唯一。V1 不实现多实例、Leader Election、分布式锁、数据库启动锁、数据库 Worker Lease、Coordinator/dispatch epoch、Resource fencing token 或多实例 Outbox claim lease。

单实例仍使用 PostgreSQL 事务、行锁、唯一约束、普通 version/CAS、进程内有界 WorkerPool、WorkerExecutionRegistry、Command Ledger、Inbox/Outbox 和崩溃恢复。这些机制服务于单进程线程并发、持久化和设备副作用安全，不承担实例选举或跨进程协调。

## 当前实现

V1 单实例 Repository/DDL 核心收敛已完成，具体包括：
- Schema 采用 31 张 Runtime 专属表；不存在 worker_execution_lease、resource_fence_counter 或分布式 epoch/fencing/Outbox claim 字段；
- Worker Attempt 执行登记使用进程内 WorkerExecutionRegistry，不使用 DB Worker Lease；
- Worker 执行事实 append-only，factSeq 在锁定 Attempt 行后分配；
- ResourceLease 仅表达设备/资源业务占用，不包含 fencing token；
- Outbox 使用单 Host PENDING→SENDING，不含 claim owner/epoch/lease；
- 启动恢复覆盖 DISPATCHED、ACCEPTED、STARTED Attempt；先完成 Attempt 核验，再恢复 Outbox；
- 已恢复 Attempt 的旧 WORKER_DISPATCH 标记 DEAD_LETTER，避免重复派发；
- 设备命令仅在可证明未越过 SEND_STARTED 时允许恢复投递；结果不确定时转 UNKNOWN/MANUAL_REQUIRED，不自动重发。

## 验证

- PostgreSQL 17 CI Run：44560926448
- 代码基线：40c1033a59167631c724678b1bfadbfdf7a7188a
- Schema Contract：31 张表，PASS
- PostgreSQL 两会话基础并发测试：PASS
- CON-01..CON-14：14/14 PASS
- 事务原子性与 Inbox 冲突：PASS
- Java Repository 集成测试：8/8 PASS
- WorkerExecutionRegistry 测试：2/2 PASS

CI 配置：.github/workflows/workflow-runtime-db.yml

## 未完成边界

本模块提供 Repository、进程内 Worker Registry 和启动恢复服务；它不等于完整 Runtime Host 已装配完成。完整 Coordinator/WorkerPool/Outbox Dispatcher/Adapter 的端到端运行、真机 commandId 幂等、设备状态回读、硬件安全点以及断电/断网 HIL 测试仍需单独完成。
