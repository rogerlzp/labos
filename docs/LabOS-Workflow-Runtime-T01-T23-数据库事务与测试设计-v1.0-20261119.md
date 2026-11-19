# 历史文档：T01–T23 数据库事务与测试设计 v1.0

本文原来基于多 Coordinator owner/epoch、Worker/Dispatcher Lease、fencing token 和多实例 Inbox claim 设计。该模型不再适用于 LabOS Workflow Runtime V1。

从 2026-11-19 起，V1 的唯一实现依据为：

- docs/LabOS-Workflow-Runtime-V1-单实例架构规范-v1.0-20261119.md
- docs/LabOS-Workflow-Runtime-T01-T23-SQL实现契约-v1.0-20261119.md
- db/workflow_runtime/001_init.sql
- db/workflow_runtime/002_schema_contract.sql

本文不再作为设计、实现、评审、测试或验收依据。历史内容仅用于追溯旧方案的演进，不得从本文复制旧字段、旧表、旧事务锁协议或旧恢复流程。

单实例并不取消 PostgreSQL 事务、行锁、唯一约束、CAS、Inbox/Outbox、Command Ledger、ResourceLease 业务占用、append-only 执行事实以及设备副作用 UNKNOWN 保护。具体边界以单实例架构规范为准。
