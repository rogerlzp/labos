# Workflow Runtime 数据库物理设计 v1.0（历史稿，已废止）

本文仅作为旧设计记录，不再是 V1 数据库物理设计依据。旧稿包含 33 表、Worker DB Lease、Coordinator epoch、dispatchEpoch 和资源 fencing 等多实例机制。

V1 当前权威物理设计已更新为：

- [LabOS Workflow Runtime V1 数据库物理设计 v1.1（单实例版）](LabOS-Workflow-Runtime-数据库物理设计-v1.1-单实例-20261119.md)
- 初始化 DDL：`db/workflow_runtime/001_init.sql`
- Schema Contract：`db/workflow_runtime/002_schema_contract.sql`

当前目标 Schema 为 31 张表，不包含 Worker DB Lease、Coordinator/dispatch/lease epoch、fencing token 或 Outbox claim owner/epoch/lease。旧稿中的表数、字段和测试结果不得用于 V1 验收。
