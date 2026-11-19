# Workflow Runtime 独立模块

本模块是全新 Workflow Runtime 的 PostgreSQL 持久化层，不依赖旧 GraphRunner 表。

## V1 部署约束

V1 只支持一个 Runtime Host 进程。禁止多实例、Leader Election、分布式 Worker Lease、Coordinator epoch、dispatchEpoch、Resource fencing token 和多实例 Outbox claim lease。单实例仍然使用数据库事务、约束、CAS、进程内有界 WorkerPool、Command Ledger、Inbox/Outbox 和崩溃恢复。

规范基线：docs/LabOS-Workflow-Runtime-V1-单实例架构规范-v1.0-20261119.md。

## 当前实现状态

**重要：当前 DDL、Repository 和测试仍包含旧分布式字段/逻辑，尚未完成 V1 收敛。** 旧版 33 表 Schema 与此前 CI 成功结果不能作为单实例 Schema 的验收结果。后续必须同步更新 DDL、Schema Contract、Repository、RecoveryService、测试和 CI。

## 目标验证

- PostgreSQL 17 空库初始化；
- Schema Contract 不含被删除的表/字段；
- 单实例 Host 锁与 fail-stop；
- WorkerPool 全局容量和每 Run 配额；
- ResourceLease 单实例资源互斥；
- Worker 超时和 Runtime 崩溃恢复；
- SEND_STARTED 后不确定命令不自动重发；
- Inbox 幂等、事务原子性和故障注入。
