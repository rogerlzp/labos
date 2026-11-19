## 文档状态：非规范性历史设计稿

> 本文包含旧的多实例、Coordinator epoch、Worker DB Lease、dispatchEpoch、Resource fencing、Outbox claim lease 等设计，已经被《LabOS Workflow Runtime V1 单实例架构规范 v1.0》取代。本文仅保留为历史设计记录，**不得作为 V1 实现、DDL、测试或评审依据**。所有 V1 设计和实现以单实例规范及其后续代码提交为准。当前代码尚未完成收敛；请勿据此宣称分布式机制已经从代码和数据库中删除。

规范文档：docs/LabOS-Workflow-Runtime-V1-单实例架构规范-v1.0-20261119.md

---

# 历史设计稿（非规范）

