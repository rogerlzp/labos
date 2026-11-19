# Workflow Runtime Worker 执行事实与单实例恢复协议 v1.4

日期：2026-11-19  
适用架构：LabOS Workflow Runtime V1 单实例规范  
代码基线：f1e6ec0fb7a429bb894b7cd14770b85b67b44828  
验证：PostgreSQL 17 CI Run 44562728674，成功

## 1. 设计边界

V1 只部署一个 Runtime Host 进程，由部署配置保证唯一。V1 不使用数据库 Worker Lease，不使用 leaseEpoch、leaseVersion、dispatchEpoch、Coordinator epoch 或设备 fencing token。Worker 执行登记保存在进程内 WorkerExecutionRegistry。

数据库保存 Attempt 状态和 append-only 执行事实。Registry 用于同一 Host 内的执行登记和尽力取消；数据库事务/version 用于持久化状态转换。两者均不能撤销已发送的设备命令，也不能证明设备已停止。

## 2. 执行事实与顺序

worker_execution_fact 是 append-only 证据账本，记录：
- HANDLER_STARTED
- SIDE_EFFECT_STARTED
- SIDE_EFFECT_COMPLETED
- SIDE_EFFECT_UNKNOWN
- EXECUTION_RIGHT_RELEASED（执行结束审计事实，不是分布式执行权）

事实包含 tenantId、runId、attemptId、factSeq、factKey、factType、actor、dispatchId、payload、createdAt。factSeq 在锁定 Attempt 行的事务内通过 MAX(fact_seq)+1 分配；factKey 同内容幂等、不同内容拒绝。数据库 Trigger 禁止 UPDATE/DELETE。

Worker 的外部副作用必须按以下顺序执行：

1. 校验 Attempt 为 STARTED；
2. 调用 Repository.recordSideEffectFact(..., SIDE_EFFECT_STARTED, ...)；
3. 确认该事务提交成功；
4. 调用设备 Adapter / 外部系统；
5. 成功时记录 SIDE_EFFECT_COMPLETED；结果不确定时记录 SIDE_EFFECT_UNKNOWN；
6. 只有最终设备状态被确认后，才允许完成 Attempt。

设备命令另有 Command Ledger：markCommandSendStarted 必须先提交 SEND_STARTED 和 SIDE_EFFECT_STARTED，再由 Dispatcher 调用 Adapter。Adapter ACK 不代表物理动作完成。

## 3. WorkerExecutionRegistry

Registry 是单 Host 进程内的 ConcurrentHashMap，不是数据库租约：
- Attempt 同时只允许登记一个本地执行 handle；
- 可请求取消并中断本地 Worker 线程；
- Worker 正常结束后按 Attempt + Thread identity 注销；
- Registry 丢失（例如进程重启）不提供任何“没有发生副作用”的证明。

Future.cancel、线程中断、线程退出、超时都不是设备停止证据。

## 4. Worker 超时

Host 存活时：
- 通过 Registry 请求本地取消；
- Repository 在 Run → Token → Activation → Attempt 的锁序下重新核验 Attempt 状态；
- 能证明没有 SIDE_EFFECT_STARTED、没有 SEND_STARTED、没有未决/不确定 Command，才可将 Attempt 标记 ABANDONED_SAFE；
- 已越过副作用边界或证据不完整，必须转 UNKNOWN/MANUAL_REQUIRED，关闭 Run control gate；
- 不得通过简单重试来覆盖不确定的设备动作。

## 5. Runtime 重启恢复

部署保证只有一个 Host 进程后，在启动 WorkerPool / Outbox Dispatcher 前调用 WorkerExecutionRecoveryService.recoverAtStartup：

1. 先扫描非终态派发中的 Attempt：DISPATCHED、ACCEPTED、STARTED；每批 1..1000 条；
2. 每个候选在 Repository 事务内按 Run → Token → Activation → Attempt 顺序加锁并重新核验；
3. 未越过副作用边界的 Attempt 转 ABANDONED_SAFE，释放 Scheduler reservation，Activation 结束，Token 恢复 READY；
4. 已有 SIDE_EFFECT_STARTED、SEND_STARTED、ACKNOWLEDGED/RUNNING 或不确定 Command 时，Command/Attempt 转 UNKNOWN，Run 转 MANUAL_REQUIRED，controlGate=CLOSED，并创建人工介入记录。CANCEL_REQUESTED 仍属于不确定命令状态，不能视为已取消；同 Attempt 尚未越过 SEND_STARTED 的 CREATED/DISPATCH_PENDING 命令则取消并将对应 Outbox 标记 DEAD_LETTER；
5. 对已恢复 Attempt 的旧 WORKER_DISPATCH Outbox 标记 DEAD_LETTER，防止旧派发再次执行；
6. 最后恢复剩余 SENDING Outbox：内部消息可回到 PENDING；设备命令只有仍处于 CREATED/DISPATCH_PENDING、未越过 SEND_STARTED 边界时才可回到 PENDING；不确定设备命令（包括 CANCEL_REQUESTED）保持不自动重发；进入人工恢复时，未发送命令会被取消并 DEAD_LETTER。

若本批 scanned 达到 limit，Host 必须继续调用恢复服务，直到某批 scanned 小于 limit，之后才允许打开 WorkerPool 和 Dispatcher。恢复服务不是多实例 Recovery 竞争、接管或 Leader 选举。

## 6. 设备命令与人工核验

稳定 commandId、设备回执回显 commandId、命令查询、Inbox 去重和物理状态回读仍是 V1 必需。ACK 仅表示接收。只有设备完成事件或状态回读能够证明物理动作完成。

UNKNOWN 不能因超时自动转 FAILED，也不能自动重发。必须通过 commandId 查询、设备状态回读或人工核验明确最终结果。资源状态不确定时保持 ResourceLease 占用或 QUARANTINED，不得仅因 expiresAt 已到就释放设备。

## 7. 验证边界

CI Run 44562728674（PostgreSQL 17）：
- 空库 DDL 初始化、31 表 Schema Contract：PASS；
- 双会话数据库并发基础测试：PASS；
- CON-01..CON-14：14/14 PASS；
- 事务原子性与 Inbox 冲突：PASS；
- Java Repository 集成测试：8/8 PASS；
- WorkerExecutionRegistry 测试：2/2 PASS。

CI 不替代真机 Adapter 的命令幂等、commandId 回显、设备状态查询、物理安全点和断电/断网故障注入验收。完整 WorkerPool、Coordinator、Dispatcher 与设备 Adapter 端到端装配仍需单独验收。
