# Workflow Runtime 独立模块

本模块是全新 Workflow Runtime 的 PostgreSQL 持久化层，不依赖旧 GraphRunner 表。

## V1 部署约束

V1 只部署一个 Runtime Host 进程，由部署配置保证实例唯一。V1 不实现多实例、Leader Election、分布式锁、数据库启动锁、数据库 Worker Lease、Coordinator/dispatch epoch、Resource fencing token 或多实例 Outbox claim lease。

单实例仍使用 PostgreSQL 事务、行锁、唯一约束、普通 version/CAS、进程内有界 WorkerPool、WorkerExecutionRegistry、Command Ledger、Inbox/Outbox 和崩溃恢复。这些机制服务于单进程线程并发、持久化和设备副作用安全，不承担实例选举或跨进程协调。

## 当前实现

已完成单实例 Repository/DDL 核心收敛：
- 移除 Worker DB Lease、Coordinator/dispatch epoch、resource fencing counter/token；
- Worker Attempt 生命周期改为 Attempt 状态 + 进程内 Registry；
- Worker 执行事实 append-only，factSeq 由锁定 Attempt 行后分配；
- ResourceLease 仅表达设备/资源业务占用；
- Outbox 改为单 Host PENDING→SENDING，无 claim owner/epoch/lease；
- 新增 Host 重启 Outbox/Attempt 恢复逻辑，副作用边界后不确定时转 UNKNOWN/MANUAL_REQUIRED；
- Java Repository 集成测试 7/7、WorkerExecutionRegistry 测试 2/2，以及 PostgreSQL 17 Schema/CON-01..14/原子性测试均已通过代码基线 b4df784 的 CI。

实现边界仍需注意：当前模块还新增了 RuntimeHost 生命周期骨架和 SchedulingGate，明确 EventIngress 先接收持久化 Inbox、Recovery 完成后才启动后续组件并开放调度。WorkerPool/Coordinator/Outbox Dispatcher/Adapter/EventDispatcher 尚未实现；设备回读验收也未完成。

## 验证

CI：.github/workflows/workflow-runtime-db.yml

验收覆盖 PostgreSQL 17 空库初始化、31 表 Schema Contract、CON-01..14、事务原子性、Inbox 冲突、Repository 集成测试和本地 Worker Registry 测试。
