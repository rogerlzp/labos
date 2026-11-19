# Workflow Runtime 数据库与事务层反向评审 v1.0

日期：2026-11-19  
评审基线：docs/workflow-runtime-architecture-v2-20261119 分支  
评审对象：db/workflow_runtime/**、workflow-runtime/**、.github/workflows/workflow-runtime-db.yml

## 0. 评审性质与结论

这是一次与实现顺序分离的反向审查：先从不变量、事务边界和故障路径出发，再逐项检查 SQL/Repository。它由同一 ChatGPT 会话执行，**不是另一位工程师或第三方 AI 的独立签字**；因此不能把“反向评审完成”解释为外部独立复核已经完成。

结论分层：

- **PASS（已有实测证据）**：PostgreSQL 17 空库 DDL、Schema 合同、两类基础竞态、CON-01..CON-14 双连接测试、Run/Event/Outbox 原子回滚、Inbox 冲突留证、当前 Repository 的部分集成路径。
- **UNKNOWN（不可由数据库单测证明）**：真实 Adapter 的 commandId 幂等/查询、设备物理回读、设备/网关 fencing 强制执行、Worker 在 Started/副作用边界崩溃后的事实恢复。
- **NOT IMPLEMENTED（运行时组件缺失）**：Coordinator mailbox、WorkerPool、Outbox/Inbox 后台调度服务、Recovery Orchestrator、设备 Adapter 发送/回读执行器。Repository 不是完整 Workflow Runtime。
- **NOT YET PROVEN（测试范围不足）**：所有 T01–T23 的逐事务崩溃矩阵、T20 长时间并发快照压力测试、T19 所有结构关闭组合、跨 Run 共享资源高竞争死锁测试。

## 1. 已发现并修正的问题

| ID | 问题 | 风险 | 修正 |
|---|---|---|---|
| REV-01 | 定义快照冲突目标与实际唯一键不一致 | T01 运行时 SQL 报错 | 改为 tenant + key + version + hash |
| REV-02 | Checkpoint 写入被放在只读快照事务内部 | T20 只读事务写失败 | 快照读取结束后单独短事务写入 |
| REV-03 | Java Instant 直接绑定 PG timestamptz | JDBC 参数类型推断失败 | 使用 Timestamp |
| REV-04 | Worker Started 未核验 dispatchId/dispatchEpoch | 旧 Worker 可能越权启动 | CAS 精确匹配 Attempt 派发身份 |
| REV-05 | Inbox 只保存 hash，不能核对事件 commandId | 事件归属可被调用方参数伪造 | 保存原始 message_payload，并按其 commandId 核验 |
| REV-06 | Device Attempt 可以先于 Command 成功提交输出 | 软件成功可能掩盖物理未完成 | T05 要求关联 Command=SUCCEEDED |
| REV-07 | T19 把 MANUAL_REQUIRED 当作可终结状态 | 不安全地报告 STOPPED | MANUAL_REQUIRED 保持阻断，单独升级 Run/Drain |
| REV-08 | T19 漏查活动 Branch/Loop/Token/Attempt/Reservation/Outbox | Drain 可能过早终结 | 扩展 StopSafePredicate 查询集合 |
| REV-09 | T17 先锁 Reservation 再锁 Capacity | 与统一锁序相反 | 改为 Capacity → Reservation |
| REV-10 | T03-R 先锁 Capacity 再锁 Token | 与统一锁序相反 | 改为 Token → Capacity |
| REV-11 | T05/T04-S/T08/T09 的对象锁顺序不一致 | 共享资源场景可能死锁 | 调整为 Run 后 Token → Activation → Attempt → Wait |
| REV-12 | Pause 后 Stop 无法覆盖已有 Pause Drain | Stop 优先级无法实现 | Stop 复用开放 Drain 并写入新的 Stop Outbox |
| REV-13 | 成功完成 Attempt 未释放 Scheduler reservation | 容量泄漏 | T05 同事务释放 Reservation 并返还 Capacity |
| REV-14 | Command/Retry 创建不是按业务身份幂等 | 重试可能重复建立逻辑对象 | Attempt 唯一 Command；Activation/attemptNo 唯一 Retry |

## 2. 事务覆盖检查

| 事务 | Repository primitive | 当前评审判定 |
|---|---|---|
| T01 | createRun | SQL 已实现；requestId/hash 幂等已集成测试 |
| T02 | startRun | SQL 已实现；完整重启/接管测试待补 |
| T03-R | reserveCapacity | SQL 已实现；容量竞态由双连接测试覆盖 |
| T03-D | dispatchAttempt | SQL 已实现；与 Worker 实际消费尚未集成 |
| T04-A | acceptAttempt | dispatchId/epoch CAS 已实现；Worker crash window 待测 |
| T04-S | startAttempt | 严格 ACCEPTED→STARTED；副作用开始证据仍缺 |
| T05 | completeAttempt | BranchOutput 与 Token 同事务；设备命令前置检查已实现 |
| T06 | scheduleRetry | SQL 已实现；Timer 重复/Stop 竞态由 CON-07 模拟 |
| T07 | registerWait | SQL 已实现；Continuation 结构约束待强化 |
| T08 | closeWait | 只允许可安全关闭的未开始 Attempt；设备长任务完成/核验后的专门路径待实现 |
| T09 | createSplit | Split 与完整 Branch/Token 集合同事务；异常回滚专项待补 |
| T10 | arriveBranch | Arrival CAS 与 Join 单次释放；CON-02/03 覆盖 |
| T11 | advanceLoop/completeLoop | SQL 已实现；复杂嵌套 Loop 测试待补 |
| T12 | createCommand | 单 Attempt 单 Command；payload 不同冲突拒绝 |
| T13 | claimOutbox/markCommandSendStarted | claim fencing/CAS 已实现；真实 Adapter 发送结果未知窗口待 HIL |
| T14 | receiveInbox/applyCommandEvent | Receipt/hash/payload/commandId 归属已实现；完整重复/乱序/设备序号规则待补 |
| T15 | requestDrain | Pause/Stop gate；Stop 覆盖 Pause 已实现 |
| T16 | advanceDrainScan | cursor + item 同事务；分页游标稳定性压力测试待补 |
| T17 | releaseReservation | CAS 单次返还 Capacity；专门双释放测试待补 |
| T18 | acquireResourceLease/recordSafePointEvidence | DB fencing/Lease 状态已实现；设备强制 fencing 未证明 |
| T19 | tryFinalizeDrain/markDrainManualRequired | 全局安全谓词已实现；完整终态组合矩阵待补 |
| T20 | createCheckpoint | REPEATABLE READ 快照；本轮有集成测试，长事务并发压力待补 |
| T21 | markCommandUnknown/resolveUnknownCommand | UNKNOWN 记录与证据核验 primitive；设备查询能力未接入 |
| T22 | decideIntervention | version CAS + Audit/Event；集成测试覆盖 |
| T23 | clearQuarantine | 要求 Lease RELEASED + 非空证据；集成测试覆盖 |

## 3. CON-01 至 CON-14

当前 Python 双连接测试覆盖以下竞争模型：

1. CON-01 同 Attempt 双 completion；
2. CON-02 同 Branch 双 Arrival；
3. CON-03 最后两个 Branch 并发到达；
4. CON-04 Pause 与 Dispatch；
5. CON-05 Stop 覆盖 Pause；
6. CON-06 WaitSatisfied 与 WaitExpired；
7. CON-07 RetryDue 与 Stop；
8. CON-08 Device final event 与 Cancel；
9. CON-09 Lease renew 与 TTL expiry；
10. CON-10 Coordinator takeover 与 Inbox 接收；
11. CON-11 Outbox 双 Dispatcher claim；
12. CON-12 Quarantine 双 Clear；
13. CON-13 人工决策 CAS；
14. CON-14 Checkpoint REPEATABLE READ 快照与并发提交。

这些测试是 PostgreSQL 双连接层的竞态模型测试，不等价于调用全部 Java Repository 方法，也不覆盖多进程 Coordinator/真实设备。PR CI 必须运行并全绿。

## 4. 必须继续关闭的风险

### R-01 Worker 副作用事实账本

当前 Attempt 有 ACCEPTED/STARTED，但尚无独立的 HandlerStarted、SideEffectStarted、ExecutionRightReleased 持久化协议。若 Worker 在设备调用前后崩溃，仅靠 Started 不足以证明设备是否收到指令。

要求：增加 Worker execution fact / lease 记录与崩溃恢复测试；不能将缺少 Started 当作未执行。

### R-02 Adapter 与设备 fencing

resource_fence_counter 能保证数据库 token 单调递增，但设备若不检查 fencing token，旧 owner 仍可能控制硬件。

要求：每个 Adapter/设备给出 commandId 查询、幂等分类、设备序号和 fencing enforcement 证据。无能力的设备必须进入 UNKNOWN/隔离，不得自动接管。

### R-03 Outbox 到 Adapter 的真实发送闭环

Repository 已实现 claim 和 SEND_STARTED 前置提交，但没有后台 Dispatcher，也没有真实 Adapter 调用/回执状态回写。

要求：发送只能发生在 claim/SEND_STARTED 提交之后；发送结果不确定时保持 UNKNOWN；不可用新 commandId 重发非幂等动作。

### R-04 T19 全状态空间

虽然 SQL 已扩展检查 Command、Lease、Wait、Retry、Split、Branch、Join、Loop、Token、Attempt、DrainItem、ManualIntervention、Reservation、Outbox，但尚未对所有组合做穷举与故障注入。

要求：对 Pause、Stop、Failure、ManualRequired 分别构建终态真值表，证明任何未决物理副作用都不能落到 PAUSED/STOPPED。

### R-05 事务锁序的全局证明

Repository 中主要锁路径已调整，但需要对 T01–T23 的完整 SQL trace 做锁顺序审计，特别是 ResourceFenceCounter、ResourceLease、Command、Outbox、Reservation 的跨 Run 交叉竞争。

要求：统一锁顺序清单与数据库死锁重试测试；不能只凭代码阅读声称无死锁。

## 5. 冻结结论

- DDL/Schema：可作为当前 PG17 实现基线，但每次结构改动需 CI 验证。
- Repository：已有可执行事务 primitives；不可标记为完整 Workflow Runtime。
- CON-01..14：测试实现已纳入 CI；以最新 commit 的全绿记录为准。
- T01–T23：方法映射已建立；逐事务故障矩阵尚未完成。
- Hardware/Adapter/Worker：UNKNOWN，未达到可安全自动执行设备命令的冻结条件。
- 独立第三方评审：未完成；本文件不冒充第三方签字。

**最终意见：** 可以继续在该分支实现 Coordinator、Dispatcher、Worker 与 Recovery Orchestrator；禁止在 Adapter/HIL/fencing 未验证前将设备型 Workflow Runtime 标记为生产安全可用。


## 6. 最新 CI 复核结果

截至 commit `10d99fd3ca0a22f03fdb186b6a88bef9b8dbb9d5`，GitHub Actions Run ID `44498938264` 为 SUCCESS。日志明确输出 CON-01 至 CON-14 全部 PASS；Java Repository 集成测试 5 tests、0 failures、0 errors、0 skipped；空库 DDL、Schema 合同、事务原子性/Inbox 冲突和最终 Schema 表数核对均通过。

这关闭了本轮实现中的编译与数据库集成阻塞。它不关闭第 4 节的 Worker execution fact、Adapter/HIL、设备 fencing、全量 T01–T23 故障矩阵和第三方独立签字等 UNKNOWN。


## 7. Worker execution fact / lease 复核（2026-11-19）

原 R-01（Worker 副作用事实账本）已部分关闭，新增：
- worker_execution_lease：Worker/dispatch 身份、leaseEpoch、expiresAt、heartbeatAt、version；
- worker_execution_fact：append-only HANDLER_STARTED / SIDE_EFFECT_STARTED / SIDE_EFFECT_COMPLETED / SIDE_EFFECT_UNKNOWN / EXECUTION_RIGHT_RELEASED；
- 数据库 trigger 阻止事实 UPDATE/DELETE；
- T04-S 原子创建 lease + HANDLER_STARTED；
- T13 在 Adapter 调用前原子提交 SEND_STARTED + SIDE_EFFECT_STARTED；
- T05 原子释放 Worker execution right；
- 过期恢复根据 SideEffect 事实区分 SAFE_TO_RETRY 和 MANUAL_REQUIRED。

本轮 CI Run 44502256816（commit 18f6583ab25e687bd9b56db1d591171e52259bad）全绿：CON-01..14 通过，Java Repository 集成测试 7/7，无失败/异常。

### R-01 剩余限制

这只是 Repository 层的执行事实与恢复原语，不是已经部署的 WorkerPool/Recovery Orchestrator。数据库租约无法阻止暂停后复活的旧进程绕过 Repository 直接操作硬件；因此 Adapter 必须检查有效执行权，设备/网关必须执行 fencingToken，并支持 commandId 查询与物理状态回读。以上仍为 UNKNOWN，须 HIL 验证。

### 新增审查项 REV-15

| ID | 问题 | 风险 | 当前处理 |
|---|---|---|---|
| REV-15 | Worker lease 过期后仅按超时重试，可能重复执行外部副作用 | 设备动作重复或旧 Worker 与新 Worker 同时执行 | 新增 append-only Worker fact；无副作用证据才回 READY，有副作用或模糊 Command 则 UNKNOWN + MANUAL_REQUIRED |

## 8. 最新验证基线

- Commit：18f6583ab25e687bd9b56db1d591171e52259bad
- GitHub Actions：44502256816 SUCCESS
- PostgreSQL：17
- Schema：33 张表
- CON-01..CON-14：14/14 PASS
- Java Repository integration：7 tests，0 failures，0 errors，0 skipped
- DDL / Schema contract / atomicity / Inbox conflict：PASS

此验证不覆盖真实 Worker 多进程、Coordinator takeover 服务、Recovery Orchestrator 服务或 Adapter/HIL。
