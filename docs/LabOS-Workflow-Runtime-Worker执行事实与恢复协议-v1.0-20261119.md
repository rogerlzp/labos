# Workflow Runtime Worker 执行事实与租约恢复协议 v1.0

日期：2026-11-19  
适用数据库：PostgreSQL 17  
实现入口：WorkflowRuntimeRepository

## 1. 目标与边界

解决 Worker 进程在执行中失联时的核心问题：系统不能仅凭 lease timeout 推断外部动作没有发生。必须有一条事务化、不可变的证据链，决定能否安全重新调度。

本协议只提供数据库侧的执行权与恢复原语，不代表 Worker 进程已被实际部署，也不代表数据库能物理终止一个暂停后复活的旧进程。Adapter/设备侧 fencing 仍是独立安全条件。

## 2. 两张新增表

### worker_execution_lease

每个 Attempt 一个执行租约，记录 workerId、dispatchId、dispatchEpoch、leaseEpoch、leaseVersion、expiresAt、heartbeatAt、leaseState 和 lastFactSeq。

租约状态：ACTIVE / RELEASED / EXPIRED_SAFE / EXPIRED_UNVERIFIED。

租约续期使用 expectedVersion CAS，且要求旧租约尚未过期。过期租约不能被原 Worker 自行复活；需要创建新的 Attempt，而不是复用旧执行权。

### worker_execution_fact

不可变追加账本，包含：
- HANDLER_STARTED；
- SIDE_EFFECT_STARTED；
- SIDE_EFFECT_COMPLETED；
- SIDE_EFFECT_UNKNOWN；
- EXECUTION_RIGHT_RELEASED。

每条事实具有 factKey、Attempt 内单调 factSeq、Worker/Dispatcher/Recovery actor、dispatch 身份、leaseEpoch、payload。factKey 重放必须完全相同；相同 key 不同内容是幂等冲突。数据库 trigger 拒绝 UPDATE/DELETE。

## 3. 写入协议

1. T04-S 在同一个数据库事务中完成 Attempt STARTED、Activation/Token RUNNING、Worker lease ACTIVE 和 HANDLER_STARTED fact。
2. Worker 在执行任何可能产生外部副作用的动作前，必须先提交 SIDE_EFFECT_STARTED fact。
3. 设备 Command 发送路径更严格：markCommandSendStarted 必须持有有效 Worker lease、验证 Attempt 仍 STARTED、验证 Outbox claim，并在同一事务写入 Command SEND_STARTED、CommandDispatchAttempt 和 SIDE_EFFECT_STARTED fact。
4. 事务提交后，Dispatcher 才能调用 Adapter。数据库事务中禁止网络/设备调用。
5. Attempt 成功提交时，同一事务写入 BranchOutput、Token/Activation/Attempt 完成、EXECUTION_RIGHT_RELEASED fact、Worker lease RELEASED 和 Capacity 释放。

## 4. Lease renewal

renewWorkerExecutionLease 使用以下条件：
- tenant/run/attempt/worker/leaseEpoch 精确匹配；
- expected leaseVersion 精确匹配；
- leaseState 必须 ACTIVE；
- 当前 expiresAt 必须晚于数据库当前时间。

续租更新 expiresAt、heartbeatAt 和 leaseVersion。旧版本、过期租约和已释放租约均不能续期。

## 5. 过期恢复判定

恢复器调用 recoverExpiredWorkerAttempt，先锁 Run，再按 Token → Activation → Attempt → WorkerLease 顺序锁定对象。

### SAFE_TO_RETRY

必须同时满足：
- Attempt 仍是 STARTED；
- Worker lease 已过期且仍标记 ACTIVE；
- 不存在 SIDE_EFFECT_STARTED / SIDE_EFFECT_COMPLETED / SIDE_EFFECT_UNKNOWN；
- 不存在 SEND_STARTED / ACKNOWLEDGED / RUNNING / DELIVERY_UNKNOWN / UNKNOWN / MANUAL_REQUIRED Command。

在此条件下，同一事务：
- 将尚未发送的 Command 标记 CANCELLED；
- 将相关未发送 Outbox 标记 DEAD_LETTER；
- Attempt / Activation 标记 ABANDONED_SAFE；
- Token 回到 READY；
- Worker lease 标记 EXPIRED_SAFE；
- 写入 EXECUTION_RIGHT_RELEASED fact；
- 释放 Scheduler reservation；
- 写入 Runtime Event。

这不是自动重跑旧 Attempt，而是允许 Coordinator 为 READY Token 创建新的 Activation/Attempt。

### MANUAL_REQUIRED

只要存在任何 SideEffectStarted 事实或模糊 Command 状态：
- Command 的发送中/运行中状态收敛为 UNKNOWN；
- DispatchAttempt 收敛为 DELIVERY_UNKNOWN；
- Attempt 标记 UNKNOWN；
- Worker lease 标记 EXPIRED_UNVERIFIED；
- Run 标记 MANUAL_REQUIRED / recoveryState BLOCKED / controlGate CLOSED；
- 创建 OPEN ManualIntervention；
- 写入 Audit 与 Runtime Event。

此路径绝不创建 READY Token，也不自动重发 Command。

## 6. 关键安全假设

- 所有外部副作用都必须经过 SIDE_EFFECT_STARTED 持久化门槛，不能绕过 Repository 直接调用设备。
- Dispatcher 必须先提交 SEND_STARTED，再调用 Adapter。
- UNKNOWN 不得被解释为失败或“没有发生”。
- DB lease 只约束遵守协议的数据库写入者；不能阻止一个暂停后复活的旧进程直接操作硬件。
- 设备/网关必须执行 commandId 幂等、状态查询与 fencingToken 检查；缺少这些能力的设备进入 UNKNOWN/隔离。
- SIDE_EFFECT_STARTED 证明“可能已经发生”，并不证明设备已执行。物理完成必须由 Adapter 回执和设备状态回读确认。

## 7. 实测范围与限制

当前 PostgreSQL 17 CI 实测：
- Worker lease 与 HANDLER_STARTED 原子创建；
- Worker lease version CAS 续期；
- Worker fact 相同 key 相同 payload 幂等；
- 相同 key 不同 payload 拒绝；
- Worker fact UPDATE/DELETE 被数据库 trigger 拒绝；
- 无 SideEffect fact 的过期 Worker 可以安全释放 Attempt 并恢复 READY Token；
- 有 SideEffect fact 的过期 Worker 进入 UNKNOWN / MANUAL_REQUIRED，Run 被阻断；
- Command SEND_STARTED 与 SIDE_EFFECT_STARTED 在同一事务持久化；
- Attempt 完成时写入 EXECUTION_RIGHT_RELEASED 并释放 Worker lease。

CI 不是 Worker 多进程/Adapter/HIL 测试。Coordinator、WorkerPool、Recovery Orchestrator 服务本身尚未交付；设备 fencing 仍 UNKNOWN。


## 8. 有界恢复批次服务

新增 WorkerLeaseRecoveryService，提供 recoverExpiredBatch(limit, recoveryActor)：
- 从 ACTIVE 且 expires_at 已过期的 Worker lease 中按过期时间扫描候选；
- limit 限制为 1..1000；
- 每个候选再由 Repository 在事务内重新核验并锁定；
- SAFE_TO_RETRY / MANUAL_REQUIRED 分别计数，已被其他恢复批次处理的候选作为 skipped；
- 重复扫描安全，不依赖进程内状态。

该类是可被宿主调度器调用的批次服务，不是后台 daemon。触发频率、leader election、shutdown、监控告警和多实例部署仍由未来 Runtime Host 实现。


**最新验证：** GitHub Actions Run 44503834970（workflow head 00dd157beaaa7e2a49112508475ed371f6cc04b3；Worker/Recovery 代码基线 3364fa30fcb912ae6aadf86e238f8976270b60a5）SUCCESS。Java 集成测试 7/7，CON-01..14 14/14，Schema 33 表检查通过。

## 9. 完成路径与设备回执的补充约束

- Attempt completion 也必须持有未过期 ACTIVE Worker lease；过期 Worker 即使尚未被 Recovery 扫描，也不能提交 BranchOutput。
- Device Inbox 回执按 Run → Token → Activation → Attempt → WorkerLease → Command → Inbox 顺序锁定，避免 Command 事件处理与 Command Dispatch 在 WorkerLease/Command 锁上形成反向等待。
- SUCCEEDED 设备回执写入 SIDE_EFFECT_COMPLETED；FAILED/CANCELLED/DELIVERY_UNKNOWN 等非成功终态写入 SIDE_EFFECT_UNKNOWN，不能仅凭“命令终态”推断设备无副作用。
- markCommandUnknown 与 resolveUnknownCommand 也按同一对象锁序处理，并追加 SideEffect 事实。

**最新验证：** Actions Run 44504186093 SUCCESS，测试代码基线 9547f4ec4ecf5506f34b29a5bab42be4c6c235f3；PG17，CON-01..14 14/14，Java 集成测试 7/7。
