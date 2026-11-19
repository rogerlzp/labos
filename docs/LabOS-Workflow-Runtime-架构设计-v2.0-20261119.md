## 文档状态：非规范性历史设计稿

> 本文包含旧的多实例、Coordinator epoch、Worker DB Lease、dispatchEpoch、Resource fencing、Outbox claim lease 等设计，已经被《LabOS Workflow Runtime V1 单实例架构规范 v1.0》取代。本文仅保留为历史设计记录，**不得作为 V1 实现、DDL、测试或评审依据**。单实例 Repository/DDL 核心收敛已在分支 docs/workflow-runtime-single-instance-20261119 实施；本文中的旧字段、旧事务和旧恢复机制均已失效。最新实现状态以单实例规范、SQL 实现契约和当前 CI 为准。

规范文档：docs/LabOS-Workflow-Runtime-V1-单实例架构规范-v1.0-20261119.md

---

# 历史设计稿（非规范）

