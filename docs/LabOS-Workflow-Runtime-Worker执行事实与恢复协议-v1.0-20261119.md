# Workflow Runtime Worker 执行事实与单实例恢复协议 v1.3

日期：2026-11-19  
适用架构：LabOS Workflow Runtime V1 单实例规范  
状态：Repository、DDL、Registry 和恢复入口已实现；代码基线 b4df784 已通过 PostgreSQL 17 CI

## 1. 设计边界

V1 只部署一个 Runtime Host 进程，由部署配置保证唯一。V1 不使用数据库 Worker Lease，不使用 leaseEpoch、leaseVersion、dispatchEpoch、Coordinator epoch 或设备 fencing token。Worker 执行登记保存在进程内 WorkerExecutionRegistry 中。

数据库仍保存 Attempt 状态和 append-only 执行事实。进程内 Registry 用于同一 Host 内的执行登记和尽力取消；数据库事务/version 用于持久化状态转换。二者均不能撤销已发送的设备命令，也不能证明设备已停止。

## 2. 执行事实与顺序

worker_execution_fact 是 append-only 证据账本，记录：
- HANDLER_STARTED
- SIDE_EFFECT_STARTED
- SIDE_EFFECT_COMPLETED
- SIDE_EFFECT_UNKNOWN
- EXECUTION_RIGHT_RELEASED

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
- 能证明没有 SIDE_EFFECT_STARTED、没有 SEND_STARTED、没有未决/不确定 Command，才可将 Attempt 标记 ABANDONED_SAFE；
- 已越过副作用边界或证据不完整，必须转 UNKNOWN/MANUAL_REQUIRED，关闭 Run control gate；
- 不得通过简单重试来覆盖不确定的设备动作。

## 5. Runtime 重启恢复

唯一 Host 启动时调用 WorkerExecutionRecoveryService.recoverAtStartup：
1. 对 SENDING Outbox 做恢复判断；
2. 非设备消息及尚未越过 SEND_STARTED 的设备命令可回到 PENDING；
3. 已越过 SEND_STARTED 的命令不自动重发；
4. 扫描 STARTED Attempt，并在 Run/Attempt 锁下重新判断执行事实和 Command Ledger；
5. 无副作用边界证据且无不确定命令时，Attempt→ABANDONED_SAFE，释放容量预留，Token 恢复 READY；
6. 已有副作用证据或命令结果不确定时，Command/Attempt→UNKNOWN，Run→MANUAL_REQUIRED，controlGate=CLOSED，并创建人工介入记录。

恢复服务不做多实例 Recovery 竞争、接管或 Leader 选举。

## 6. 设备命令与人工核验

稳定 commandId、设备回执回显 commandId、命令查询、Inbox 去重和物理状态回读仍是 V1 必需。ACK 仅表示接收。只有设备完成事件或状态回读能够证明物理动作完成。

UNKNOWN 不能因超时自动转 FAILED，也不能自动重发。必须通过 commandId 查询、设备状态回读或人工核验明确最终结果。资源状态不确定时保持 ResourceLease 占用或 QUARANTINED，不得仅因 expiresAt 已到就释放设备。

## 7. 验证边界

PostgreSQL 17 CI 覆盖空库 DDL、Schema Contract、并发矩阵、事务原子性、Repository 集成测试和 Registry 生命周期。它不替代真机 Adapter 的命令幂等、commandId 回显、设备状态查询、物理安全点和断电/断网故障注入验收。
