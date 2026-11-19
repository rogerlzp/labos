# Workflow Runtime 独立模块

本模块是全新 Workflow Runtime 的 PostgreSQL 持久化与事务边界，不依赖旧 GraphRunner 表，也不属于 labos-kernel 的旧 Maven 编译单元。

## 运行

要求 Java 17、Maven、PostgreSQL 17。

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/workflow_runtime/001_init.sql
mvn -f workflow-runtime/pom.xml test
```

启用真实 PostgreSQL 集成测试：

```bash
RUN_RUNTIME_PG_IT=1 \
PGHOST=localhost PGPORT=5432 PGUSER=runtime PGPASSWORD=runtime PGDATABASE=runtime_test \
mvn -f workflow-runtime/pom.xml test
```

GitHub Actions 在 PostgreSQL 17 服务容器内执行空库 DDL、Schema 合同、双连接并发测试、CON-01..CON-14、原子性测试和 Java Repository 集成测试。

## 事务实现入口

主类：**com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository**

| 事务 | Repository 方法 |
|---|---|
| T01 | createRun |
| T02 | startRun |
| T03-R / T03-D | reserveCapacity / dispatchAttempt |
| T04-A / T04-S | acceptAttempt / startAttempt |
| T05 | completeAttempt |
| T06 | scheduleRetry |
| T07 / T08 | registerWait / closeWait |
| T09 / T10 | createSplit / arriveBranch |
| T11 | advanceLoop / completeLoop |
| T12 / T13 | createCommand / claimOutbox / markCommandSendStarted / markOutboxDelivered |
| T14 | receiveInbox / applyCommandEvent / markInboxProcessed |
| T15 / T16 | requestDrain / advanceDrainScan |
| T17 | releaseReservation |
| T18 | acquireResourceLease / recordSafePointEvidence |
| T19 | tryFinalizeDrain / markDrainManualRequired |
| T20 | createCheckpoint |
| T21 | markCommandUnknown / resolveUnknownCommand |
| T22 | decideIntervention |
| T23 | clearQuarantine |

## 必须保持的边界

- 所有 Repository 方法只执行数据库操作；Adapter、设备、Worker 网络调用不得放进事务回调。
- Outbox claim 提交后才能尝试外部发送；SEND_STARTED 提交后调用 Adapter；结果再由 Inbox 按 commandId 核验。
- Worker lease + append-only execution facts 记录执行权与外部副作用边界；过期恢复区分 SAFE_TO_RETRY 与 MANUAL_REQUIRED。它不能物理终止复活的旧 Worker。
- DB resource fencing token 只证明数据库单调递增，不证明设备/网关拒绝旧 token。设备侧 fencing 必须逐设备 HIL。
- 设备 Attempt 的输出必须等关联 Command 达到 SUCCEEDED 后才能写入 BranchOutput。
- Stop 不能把 UNKNOWN、MANUAL_REQUIRED、QUARANTINED 或未释放 Lease 视为安全完成。
- Checkpoint 多表读取在一个 PostgreSQL REPEATABLE READ READ ONLY 快照中完成；Checkpoint 元数据在快照事务结束后单独短事务写入。

## 实现成熟度

当前是可执行的事务持久化层与 Worker 执行事实/过期恢复原语，不是完整运行时。Coordinator mailbox、WorkerPool、设备 Adapter 调用器、Outbox 后台 Dispatcher、Recovery Orchestrator 服务和硬件 fencing 尚未由本模块交付。T01–T23 有 SQL Repository primitives，但并非所有崩溃/外部副作用场景均已验证；请以 CI 与反向评审状态文档为准。
