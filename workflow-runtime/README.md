# Workflow Runtime 独立模块

本模块是全新 Workflow Runtime 的 PostgreSQL 持久化层，不依赖旧 GraphRunner 表。

## V1 部署约束

V1 只部署一个 Runtime Host 进程，由部署配置保证实例唯一。V1 不实现多实例、Leader Election、分布式锁、数据库启动锁、分布式 Worker Lease、Coordinator epoch、dispatchEpoch、Resource fencing token 或多实例 Outbox claim lease。

单实例仍然使用数据库事务、行锁、唯一约束、普通对象 version/CAS、进程内有界 WorkerPool、Command Ledger、Inbox/Outbox 和崩溃恢复。这些机制服务于单进程线程并发、持久化和设备副作用安全，不承担实例选举或跨进程协调。

规范基线：docs/LabOS-Workflow-Runtime-V1-单实例架构规范-v1.0-20261119.md。

## 当前实现状态

**重要：当前 DDL、Repository 和测试仍包含旧分布式字段/逻辑，尚未完成 V1 收敛。** 旧版 33 表 Schema 与此前 CI 成功结果不能作为单实例 Schema 的验收结果。后续必须同步更新 DDL、Schema Contract、Repository、RecoveryService、测试和 CI。

## 目标验证

- PostgreSQL 17 空库初始化；
- Schema Contract 不含被删除的表/字段；
- 单实例 Host 部署约束（不使用数据库锁或选举）；
- WorkerPool 全局容量和每 Run 配额；
- ResourceLease 单实例资源互斥；
- Worker 超时和 Runtime 崩溃恢复；
- SEND_STARTED 后不确定命令不自动重发；
- Inbox 幂等、事务原子性和故障注入。
