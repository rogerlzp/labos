# LabOS Workflow Runtime：T01–T23 数据库事务与验证设计 v1.0

> 日期：2026-11-19  
> 适用架构基线：Workflow Runtime v2.1（状态机闭合修订版）  
> 架构基线提交：`e4f6efd1c9f37c168549f8946f0c73952e4ab6c6`  
> 本文性质：实现前数据库事务设计、自动化测试规范、故障注入规范  
> 状态：工程设计基线（不是已实现/已验证声明）

---

## 0. 目的、范围与裁决

本文把架构文档 §31–§38 的 T01–T23 转换成可供数据库实现、测试实现和集成验收使用的工程规格。它不改变 v2.1 的状态机语义，不重新编号 T01–T23，也不把多个数据库事务合并成跨网络长事务。

本文件覆盖：

1. 逻辑数据模型、约束、索引、幂等身份和事务锁顺序；
2. T01–T23 每个事务的输入、读集合、写集合、锁/CAS、提交后动作、冲突与失败语义；
3. 状态转移自动化测试、属性测试、并发测试；
4. 事务崩溃点、外部副作用不确定窗口和恢复验证；
5. 测试通过标准及尚待技术选型验证的项目。

不覆盖：具体 ORM、具体 SQL 方言、具体消息队列、具体设备协议、生产容量参数。此类选择不得改变本文规定的逻辑原子边界。

### 0.1 重要语义边界

- 数据库事务只包含数据库操作。不得在事务内调用 Worker、设备 Adapter、外部 API、文件系统或人工操作。
- 任何状态推进都必须由当前有效 Coordinator 串行化；外部 Worker/设备消息只提交事实，由 Coordinator 验证后推进控制流投影。
- 设备调用的未知结果不是失败，也不是可重试的普通投递失败；必须进入 UNKNOWN/核验/隔离路径。
- Run 行锁/CAS 是 Run 级控制流写入的线性化点；对象版本 CAS 是对象级防重复/防过期写入的第二道保护。
- Outbox 保证消息可恢复投递，不保证外部副作用天然幂等。
- 文中“原子”均指同一个数据库事务的原子提交，不意味着数据库与设备之间存在分布式原子事务。

### 0.2 事务命名

T01–T23 是稳定的规范编号。为避免一个编号掩盖多个数据库提交边界，个别事务在实现规格中拆成有序子阶段，例如 T03-R（Reservation）与 T03-D（Dispatch Commit）、T04-A（Accepted）与 T04-S（Started）。子阶段仍属于原事务编号的业务范围，但每个子阶段都是独立短事务；不得跨阶段持有数据库锁。

---

# 1. 逻辑持久化模型与数据库约束

## 1.1 逻辑表清单

下表是逻辑模型名称，不要求物理表名逐字一致；映射必须在 DDL 评审中显式列出。

| 逻辑表 | 主键/唯一身份 | 主要用途 |
|---|---|---|
| workflow_run | run_id | Run 状态、runCommitVersion、eventSeq、Coordinator owner/epoch、定义版本 |
| run_request_dedup | (tenant_id, request_id) | CreateRun/Pause/Stop 等请求幂等 |
| workflow_definition_snapshot | (definition_hash, compiler_version) | 不可变定义与编译产物引用 |
| execution_token | token_id | 控制流 Token、状态、owner 关联、版本 |
| node_activation | activation_id | 节点逻辑激活及当前 Attempt 指针 |
| node_attempt | attempt_id；dispatch_id UNIQUE | 单次 Worker 执行尝试 |
| dispatch_reservation | reservation_id | 软件并发额度、过期时间、回收状态 |
| worker_dispatch_outbox | outbox_id；(attempt_id, dispatch_id) UNIQUE | Worker 派发消息 |
| worker_dispatch_lease | dispatch_id | Dispatcher claim、lease、fencing |
| split_activation | split_activation_id | 一次 Split 的实际分支集合与关闭状态 |
| branch_activation | branch_activation_id；(split_activation_id, branch_id) UNIQUE | 分支生命周期 |
| join_activation | join_activation_id；split_activation_id UNIQUE（按语义约束） | Join 到达与唯一释放 |
| loop_context | loop_context_id | iteration、maxIterations、版本 |
| wait_registration | wait_id | 持久化 Wait 状态、correlationKey、deadline |
| retry_registration | retry_id | Retry due、sourceAttempt、目标 Activation |
| run_variable | (run_id, variable_key) | Run 级变量值与版本 |
| branch_output | (branch_activation_id, output_key) | 分支局部输出 |
| runtime_event | (run_id, event_seq) UNIQUE | Run 事件账本 |
| inbox_message | (source, message_id) UNIQUE | 外部输入去重、payloadHash、处理结果 |
| protocol_violation | violation_id | 同身份不同 payload 等协议冲突的独立审计 |
| command_ledger | command_id UNIQUE | 设备动作事实及状态 |
| command_outbox | outbox_id；command_id UNIQUE（每个逻辑命令一个初始投递） | 设备命令待投递/派发状态 |
| command_dispatch_attempt | dispatch_attempt_id | 每次实际 Adapter 调用尝试 |
| resource_lease | lease_id；有效资源 owner 约束 | 物理资源占用、fencingToken、隔离 |
| resource_acquisition_group | acquisition_group_id | 多资源获取组与补偿状态 |
| drain_record | drain_id | Pause/Stop 收敛过程和游标 |
| drain_item | (drain_id, object_type, object_id) | Drain 待处理对象与已核验事实 |
| checkpoint | checkpoint_id；(run_id, event_seq_high_watermark) | 一致快照和 payloadHash |
| manual_work_item | work_item_id | 人工核验/处置工作项 |
| manual_resolution | intervention_id UNIQUE | 不可变人工决策、证据、授权 |
| quarantine_record | quarantine_id | 隔离原因、核验证据、解除记录 |
| scheduler_capacity | capacity_key | 全局/节点类型并发额度（可用独立容量服务实现） |

所有表必须有 created_at、updated_at（适用时）、row_version 或等价版本字段。不可变账本表不得通过 UPDATE 覆盖事实内容；状态投影可更新，但必须保留对应 Runtime Event。

## 1.2 核心列约束

以下约束是逻辑要求，物理实现可用 UNIQUE、部分唯一索引、排他约束、锁表或事务化分配器实现。

1. `workflow_run(run_id)` 主键；同一 Run 的 `run_commit_version` 单调递增。
2. `runtime_event(run_id, event_seq)` 唯一；已提交事件按 event_seq 严格递增。
3. `inbox_message(source, message_id)` 唯一；payload_hash 首次写入后不可变。
4. `command_ledger(command_id)` 唯一；payload_hash、device_id、operation、创建时关联身份不可被覆盖。
5. `node_attempt(dispatch_id)` 唯一；一个 Attempt 只能有一个逻辑 dispatchId。
6. `worker_dispatch_outbox(attempt_id, dispatch_id)` 唯一；Outbox 重投沿用原 message identity。
7. `branch_activation(split_activation_id, branch_id)` 唯一。
8. 同一 Split 只能有一个 Join release 结果；可用 join_activation 唯一键与 released_at CAS 实现。
9. 同一 Wait 只能从 ACTIVE 成功 CAS 到 SATISFIED/EXPIRED/CANCELLED 中的一个终态。
10. 同一 RetryRegistration 只能被一个 RetryDue 消息消费一次。
11. 同一资源在 ACQUIRING、ACQUIRED、RELEASE_PENDING、EXPIRED_UNVERIFIED、QUARANTINED 状态下最多一个有效 owner；QUARANTINED 仍占据准入。
12. 一个 Activation 同时最多一个有效 Attempt；推荐 active_attempt_id 指针 + 状态校验 + 唯一活动 Attempt 约束。
13. 一个 Token 同时最多一个有效 Activation owner；Token 被消费后不可重新 READY。
14. `run_request_dedup(tenant_id, request_id)` 唯一，保存 request_payload_hash 和最终 outcome。
15. `manual_resolution(intervention_id)` 唯一且不可变；同一 work item 的决策通过版本 CAS 保证单一胜者。

## 1.3 推荐索引

- `workflow_run(status, updated_at)`：恢复扫描与运维列表。
- `workflow_run(coordinator_owner_id, coordinator_lease_until)`：owner lease 续期/过期扫描。
- `execution_token(run_id, status, priority)`：Scheduler 候选集。
- `node_activation(run_id, status)`、`node_attempt(run_id, status)`：运行态核验。
- `worker_dispatch_outbox(status, available_at, claim_until)`：Worker Dispatcher。
- `command_outbox(status, available_at, claim_until)`：设备 Dispatcher。
- `command_ledger(run_id, status)`、`command_ledger(device_id, status)`：命令核验。
- `resource_lease(resource_id, status)`：资源互斥与隔离检查。
- `inbox_message(processing_status, received_at)`：Inbox 恢复扫描。
- `runtime_event(run_id, event_seq)`：事件重放/Checkpoint 游标。
- `wait_registration(status, deadline)`、`retry_registration(status, due_at)`：定时器扫描。
- `drain_item(drain_id, status)`：Drain 收敛进度。

索引不得替代唯一约束；查询性能优化不得改变锁顺序或线性化点。

## 1.4 统一锁顺序

所有涉及多个对象的写事务必须按以下全局顺序获取锁；未涉及的层级跳过：

1. `workflow_run`（按 run_id 排序；跨 Run 事务原则上禁止）；
2. `scheduler_capacity`（按 capacity_key 排序；仅涉及额度分配/回收时锁定）；
3. `resource_acquisition_group`（按 group_id）；
4. `resource_lease`（按 resource_id 排序）；
5. `split_activation` → `loop_context` → `wait_registration` → `retry_registration`（按此固定类型顺序，再按 ID 排序）；
6. `execution_token`（按 token_id）；
7. `node_activation`（按 activation_id）；
8. `node_attempt`（按 attempt_id）；
9. `command_ledger`（按 command_id）；
10. `branch_activation` / `join_activation`（按 ID）；
11. `run_variable` / `branch_output`（按 key）；
12. `inbox_message` / `outbox` / `runtime_event` / audit rows。

若某事务需要先定位子对象，可先做不加锁的只读查找取得候选 ID，再按统一顺序锁定并重新验证所有关联关系。禁止先锁子对象再回头锁 Run。数据库死锁仍可能发生，必须把 deadlock victim 视为事务整体回滚并以原幂等身份重试；不得拆分已定义的原子事务来“绕过”死锁。

## 1.5 Run 级写事务模板

每个会改变 Run 聚合投影的事务遵循：

1. Begin。
2. 插入 Inbox（若输入来自消息）；同身份同 hash 返回既有 outcome，同身份不同 hash 写 ProtocolViolation 并拒绝。
3. 锁定 Run，读取 status、runCommitVersion、coordinatorEpoch。
4. 校验 owner 控制命令 epoch；事实消息按对象身份核验，不因旧 dispatchEpoch 单独拒绝。
5. 锁定关联对象，校验对象版本、状态和身份。
6. 计算纯 TransitionPlan（不做 IO）。
7. 写全部投影；runCommitVersion 加一。
8. 在同一事务内分配连续 eventSeq 并写 Runtime Event。
9. 写 Outbox；标记 Inbox processed 和 outcome。
10. Commit。
11. 提交后唤醒 Scheduler/Dispatcher；唤醒可丢失，Outbox 扫描必须可恢复。

所有条件更新必须检查 affected_rows=1；为 0 时重新读取并区分重复、过期、冲突或非法状态，不可默认为成功。

### 1.5.1 Inbox Receipt 与业务处理的事务分离

Inbox 采用两阶段持久化，但只有第二阶段与业务投影原子提交：

- **Receipt transaction：** 只负责以 `(source,messageId)` 唯一键持久化 RECEIVED、payloadHash、原始消息引用和 receivedAt。它不锁 workflow_run，不写 Runtime Event，不修改 Run/Token/Activation/Command/Lease，不得标记 PROCESSED。
- **Processing transaction：** 先锁 Run，再按 §1.4 锁定业务对象，最后锁 Inbox 行；验证 Receipt 身份和 payloadHash，执行状态转移，并在同一事务内写业务投影、Runtime Event、Outbox、Inbox outcome。
- **Crash recovery：** Receipt 已提交但 Processing 尚未开始/提交时，Inbox 保持 RECEIVED，可由当前 Coordinator 重新领取。Processing 事务提交响应丢失时，按 Inbox identity 查询；若已 PROCESSED/REJECTED，返回原 outcome；若仍 RECEIVED，使用原消息身份重试。
- **同身份不同 payload：** 不覆盖原 Receipt。写独立 ProtocolViolation，保存 originalPayloadHash/newPayloadHash 和新输入引用。该冲突审计不改变原消息处理结果。
- **并发处理：** Inbox processing claim 必须带 owner/lease/version；业务处理事务仍须验证 claim 和 Inbox version。旧 Coordinator 的 claim 失效不能提交业务状态。
- **顺序保证：** 不允许先把 Inbox 标记 PROCESSED 再提交业务状态；不允许业务状态提交后 Inbox 仍永久停留在 RECEIVED。两者必须在 Processing transaction 同一提交。

这项拆分避免先锁 Inbox 再锁 Run 造成与全局锁顺序相反的死锁，也避免将消息接收事实与业务处理事实混为一谈。

## 1.6 通用错误分类

| 分类 | 例子 | 事务处理 | 重试策略 |
|---|---|---|---|
| IDEMPOTENT_DUPLICATE | 相同 request/message 与 hash 已处理 | 返回首次 outcome，不新增副作用 | 不需要 |
| IDENTITY_CONFLICT | 相同身份不同 payloadHash | 写 ProtocolViolation，拒绝 | 不自动重试 |
| STALE_VERSION | expectedVersion 不匹配 | 不写状态；记录可观测拒绝 | 重新读取后由 Coordinator 决策 |
| ILLEGAL_TRANSITION | 当前状态不允许该事件 | 不写投影；审计拒绝 | 不重试同一事件 |
| TRANSIENT_DB | deadlock、serialization failure、连接中断且确认未提交 | 整体回滚 | 原幂等身份有限退避重试 |
| COMMIT_OUTCOME_UNKNOWN | Commit 结果未知 | 不生成新身份 | 用原 requestId/messageId 查询 |
| EXTERNAL_OUTCOME_UNKNOWN | Adapter/Worker 可能已收到/执行 | 持久化 UNKNOWN/核验任务 | 禁止盲目重发 |
| INVARIANT_VIOLATION | 多个活动 Attempt、孤儿 Token | 阻断提交/报警 | 不自动修复 |

---

# 2. T01–T23 事务规格

> 每项的“读集合/写集合”是逻辑集合。实现评审必须将其展开为具体 SQL 表、列和索引；“锁”遵循 §1.4。

## T01 — Create Run

**输入/幂等键：** CreateRun(tenantId, requestId, definitionHash, compilerVersion, initialVariablesHash)。幂等键 `(tenantId, requestId)`。

**读集合：** request dedup；不可变 Definition/GraphPlan snapshot；必要的租户配额/授权记录。

**写集合：** workflow_run(CREATED, version=0, epoch 初值)；run_request_dedup；Run variables 初始版本；初始 Token；RunCreated Event；可选 StartRun Outbox。

**锁/CAS：** 先以唯一键争抢 requestId；锁定/校验 Definition snapshot 的 hash 与 compilerVersion；新 Run ID 只在唯一插入成功后生效。

**事务步骤：**
1. 查/插入 dedup identity；并发重复插入由唯一约束裁决。
2. 若已存在，比较 payloadHash：相同返回既有 runId，不同写冲突审计并拒绝。
3. 校验 Definition snapshot 存在且不可变。
4. 插入 Run、变量、初始 Token。
5. 写 RunCreated Event（eventSeq 从 1 开始）及 StartRun Outbox（若采用异步启动）。
6. 写 dedup outcome，提交。

**提交后：** 通知 Coordinator；通知失败由 StartRun Outbox 扫描恢复。

**失败/恢复：** 任一步失败整体回滚。Commit 响应丢失时按 requestId 查询；不得创建新 requestId 代替原请求。

**核心测试：** DB-T01-01 正常创建；02 同 request/hash 并发仅一个 Run；03 同 request 不同 hash 拒绝；04 Definition 不存在回滚；05 Commit 后响应丢失返回原 Run；06 初始 Token/Event/变量不存在部分提交。

## T02 — Start Run

**输入/幂等键：** StartRun(runId, startRequestId, expectedRunVersion, definitionHash)。

**读集合：** Run、Definition/GraphPlan、初始 Token、变量初始化状态。

**写集合：** NodeActivation；Token CREATED→READY；Run CREATED→STARTING/RUNNING；Runtime Event；SchedulerWakeup Outbox；Inbox outcome（消息触发时）。

**锁/CAS：** Run 行锁 + expectedRunVersion + CREATED 状态；Activation/Token 唯一 owner 约束。

**事务步骤：**
1. 去重 StartRun 消息。
2. 锁 Run 并校验 definitionHash/compilerVersion。
3. 校验 Run=CREATED；创建起始 Activation。
4. Token CREATED→READY；按初始化是否需要异步步骤将 Run 置 STARTING 或 RUNNING。
5. 写 RunStarted/TokenReady 事件及 SchedulerWakeup Outbox。
6. runCommitVersion++，标记 Inbox processed，提交。

**提交后：** Scheduler 唤醒。若 STARTING，只有初始化完成消息可以推进到 RUNNING。

**失败/恢复：** 重复 StartRun 返回首次 outcome；错误定义或非法状态拒绝，不得重复创建 Activation。

**核心测试：** DB-T02-01 正常启动；02 两个 Coordinator 并发启动仅一个成功；03 重复消息不重复 Activation；04 STARTING 时 Scheduler 不派发普通 Token；05 Run 版本过期拒绝。

## T03 — Reservation 与 Dispatch Commit

T03 包含两个独立短事务：T03-R Reservation、T03-D Dispatch Commit。两者之间 Pause/Stop 可以线性化获胜。

### T03-R Reservation

**输入/幂等键：** ReserveToken(runId, tokenId, reservationRequestId)。幂等键为 reservationRequestId，逻辑唯一键为 tokenId + 当前 READY 版本。

**读集合：** Run 状态/epoch/额度；Token；节点并发限制；资源准入/Lease 状态；容量计数。

**写集合：** dispatch_reservation；Token READY→RESERVED；容量占用计数；ReservationCreated Event。

**锁/CAS：** Run → scheduler_capacity（按 capacity_key 排序）→ ResourceLease（若本事务仅验证已有租约，不得反向锁顺序）→ Token → Reservation。容量必须用原子计数/CAS 或锁定容量行，禁止 read-then-write 超卖。

**步骤：**
1. 锁 Run，必须 RUNNING 且 epoch 有效。
2. 锁 Token，必须 READY 且版本匹配。
3. 原子检查并扣减 Run/global/node capacity。
4. 校验所需 ResourceLease=ACQUIRED 且 owner 对应当前 Activation；资源获取本身由资源服务流程完成，不得把外部物理获取塞入本事务。
5. 创建 reservation，Token→RESERVED，写 Event。
6. 提交。

**提交后：** Coordinator 可尝试 T03-D；Reservation TTL 由回收扫描器观察，但 TTL 到期不代表物理资源释放。

### T03-D Dispatch Commit

**输入/幂等键：** CommitDispatch(runId, reservationId, dispatchId)。

**读集合：** Run、Reservation、Token、Activation、Attempt、Lease、容量占用。

**写集合：** Attempt CREATED→DISPATCH_PENDING；Activation→ACTIVE；Token RESERVED→IN_FLIGHT；WorkerDispatch Outbox；DispatchCommitted Event。

**锁/CAS：** Run → Lease → Token → Activation → Attempt；校验 Run 仍 RUNNING、Reservation 未过期、dispatchId 未用、Lease 仍有效。

**步骤：**
1. 校验 reservation 与 Token/Activation/Attempt 身份一致。
2. 若 Pause/Stop 已先提交，拒绝 Dispatch Commit；释放软件 reservation 并按 Stop 策略使 Token READY/INVALIDATED。
3. 若 Dispatch Commit 先提交，写入 Outbox，之后 Pause/Stop 必须将其纳入 Drain。
4. 写 Attempt、Activation、Token、Outbox、Event 同事务提交。

**失败/恢复：** 两个阶段不能拼成一个长事务。Reservation 重复请求返回同 reservation；Dispatch 重复请求返回原 dispatchId/outbox；过期 reservation 不得发送。

**核心测试：** DB-T03-01 capacity 无超卖；02 token 只 reserve 一次；03 Pause 先提交则 Dispatch 拒绝；04 Dispatch 先提交则纳入 Drain；05 Lease 无效拒绝；06 DispatchCommit 响应丢失仍只一个 Outbox；07 reservation 过期回收不释放物理 Lease。

## T04 — Worker Accepted 与 Started

T04 必须拆为两个独立事实事务，不能把接收与 Handler 开始合并。

### T04-A Worker Accepted

**输入/幂等键：** DispatchAccepted(messageId, runId, activationId, attemptId, dispatchId, dispatchEpoch)。

**读集合：** Inbox Receipt、Run、Activation、Attempt、Worker dispatch lease/outbox；业务处理事务锁顺序为 Run → Activation → Attempt → Inbox。

**写集合：** Inbox；Attempt DISPATCHED→ACCEPTED；Worker 接收事实/租约；Runtime Event；可选 WorkerAck Outbox。

**校验：** 必须匹配当前有效 Attempt 和 dispatchId；dispatchEpoch 必须等于该 Attempt 持久化 dispatchEpoch。旧 Coordinator 控制消息不能推进状态，但有效 Worker 事实消息可由当前 Coordinator 核验。

### T04-S Worker Started

**输入/幂等键：** NodeStarted(messageId, attemptId, dispatchId, attemptVersion)。

**读集合：** Inbox、Run、Activation、Attempt。

**写集合：** Attempt ACCEPTED→STARTED；NodeAttemptStarted Event；Inbox processed。

**校验：** 仅 ACCEPTED→STARTED。DISPATCHED 直接 STARTED 属非法转移。Attempt 已关闭或版本过期则记录 STALE_COMPLETION/拒绝。

**失败/恢复：** Worker Accepted 后、Started 前崩溃不能直接判定未执行；由 Worker 执行器持久化事实和副作用分类核验。纯计算且证明未开始才可 ABANDONED_SAFE；否则 UNKNOWN/人工核验。

**核心测试：** DB-T04-01 Accepted 不等于 Started；02 Started 越级拒绝；03 重复 Accepted/Started 幂等；04 旧 dispatchEpoch 但身份有效的 Worker 结果可由新 Coordinator 验证；05 旧 attempt 的 Started 拒绝；06 Worker 接收回执丢失不自动重发设备动作。

## T05 — 纯计算节点成功完成

**输入/幂等键：** NodeCompleted(messageId, attemptId, dispatchId, expectedAttemptVersion, outputHash)。

**读集合：** Inbox、Run、Token、Activation、Attempt、变量版本、Definition output schema、关联 Split/Join/Loop。

**写集合：** Node Output；Run Variable（CAS）；Attempt→SUCCEEDED；Activation→COMPLETED；Token IN_FLIGHT→CONSUMED；后继 Token 或结构控制对象；Runtime Event；SchedulerWakeup Outbox。

**锁/CAS：** Run → Split/Loop（如适用）→ Token → Activation → Attempt → Variables（key 排序）；Attempt 必须 STARTED 且是 Activation 当前 Attempt。

**步骤：**
1. 去重并校验身份/版本。
2. 校验 Run 允许消费该完成事实；若 Run 正在 Drain，按 Drain policy 接收完成事实但不得创建新的普通 Dispatch。
3. 校验输出 Schema、outputHash 和变量写入冲突策略。
4. 原子写输出、变量、Attempt/Activation/Token、后继 Token/控制流状态、事件和 Outbox。
5. 提交后唤醒 Scheduler。

**失败/恢复：** 同一完成消息重复返回首次结果；旧 Attempt completion 只记 STALE_COMPLETION，不覆盖当前状态。变量 CAS 冲突不能静默 last-write-wins。

**核心测试：** DB-T05-01 正常完成；02 双 completion 并发仅一次消费 Token；03 output schema 失败全回滚；04 variable CAS 冲突无部分输出；05 Drain 期间完成可收敛但不新派发；06 旧 Attempt 结果不覆盖新 Attempt。

## T06 — 节点失败与 Retry Registration

**输入/幂等键：** NodeFailed(messageId, attemptId, errorClass, sideEffectClass, evidenceRef)。

**读集合：** Run、Token、Activation、Attempt、Command/Lease 未决状态、Retry policy。

**写集合：** Attempt 失败状态；Activation RETRY_WAIT/FAILED/UNKNOWN/MANUAL_REQUIRED；RetryRegistration（若可重试）；Timer/Retry Outbox；Runtime Event；失败协调 Outbox。

**锁/CAS：** Run → Lease → Token → Activation → Attempt → Command。Attempt 必须是当前 Attempt 且尚未终结。

**裁决：**
- RETRYABLE：仅在没有未决副作用且可证明安全重试时，Attempt→RETRYABLE_FAILED、Activation→RETRY_WAIT，创建唯一 RetryRegistration。
- PERMANENT：Attempt→PERMANENT_FAILED、Activation→FAILED。
- UNKNOWN：Attempt→UNKNOWN、Activation→UNKNOWN，创建核验工作项，不创建普通 Retry。
- MANUAL：Activation→MANUAL_REQUIRED，创建人工工作项。
- Run 已 Pause/Stop：不得新建可派发 Retry；记录失败事实并交给 Drain。

**核心测试：** DB-T06-01 四类错误分别转移；02 retryable 但有未决设备命令强制 UNKNOWN；03 RetryRegistration 唯一；04 Retry 与 Stop 竞态；05 重复 NodeFailed 幂等；06 timeout 不等于无副作用。

## T07 — Suspend

**输入/幂等键：** SuspendActivation(messageId, activationId, attemptId, waitSpecHash)。

**读集合：** Run、Token、Activation、Attempt、WaitRegistration。

**写集合：** WaitRegistration(ACTIVE)；Activation→SUSPENDED；Attempt→SUSPENDED；Token IN_FLIGHT→WAITING；WaitSubscription/Timer Outbox；Runtime Event；Inbox outcome。

**锁/CAS：** Run → WaitRegistration（唯一活动 wait）→ Token → Activation → Attempt。

**前置条件：** Activation=ACTIVE、Attempt=STARTED、WaitSpec 完整；该 Attempt 释放 Worker 执行权。若设备动作仍在执行，Command Ledger 独立保持运行态。

**失败/恢复：** 同一 suspend identity 重放返回原 Wait；任何一个对象状态不符则整体不提交；不能先发订阅再写 Wait。

**核心测试：** DB-T07-01 四对象同事务一致；02 Wait 唯一；03 Worker 释放事实可追踪；04 Command 仍运行时 Suspend 不伪造命令完成；05 提交前崩溃不产生有效外部订阅。

## T08 — Wait Satisfied / Expired

**输入/幂等键：** WaitEvent(source, messageId, waitId, correlationKey, payloadHash) 或 WaitExpired(timerMessageId, waitId)。

**读集合：** Inbox Receipt、Run、Wait、Token、Activation、旧 Attempt；业务处理事务锁顺序为 Run → Wait → Token → Activation → Attempt → Inbox。

**写集合：** Wait ACTIVE→SATISFIED 或 ACTIVE→EXPIRED；Token WAITING→READY（仅满足路径）；Activation SUSPENDED→READY；旧 Attempt SUSPENDED→ABANDONED_SAFE；超时路径按编译好的 timeout edge/failure policy 写失败/后继状态；Runtime Event；Scheduler Outbox；Inbox processed。

**锁/CAS：** Run → Wait → Token → Activation → Attempt。Wait 状态 CAS 是 Satisfied/Expired 唯一胜者。

**步骤：**
1. Inbox 去重并核验 source/correlationKey/payload。
2. CAS Wait ACTIVE 到唯一终态。
3. Satisfied：同事务关闭旧 Attempt 为 ABANDONED_SAFE，Activation/Token READY，保留 continuation metadata。
4. Expired：按 Definition timeout policy 唯一推进；不得同时创建 satisfied continuation。
5. 写事件、Outbox、Inbox outcome，提交。

**核心测试：** DB-T08-01 Satisfied；02 Expired；03 两者并发单一胜者；04 重复 Satisfied 不重复 Token；05 旧 Attempt completion 不覆盖 continuation；06 旧 Attempt 关闭与 Token READY 原子；07 correlation 不匹配拒绝。

## T09 — Split Expand

**输入/幂等键：** ExpandSplit(messageId, parentTokenId, splitNodeId, gatewayPlanHash)。

**读集合：** Run、父 Token、Activation、GraphPlan、已存在 Split。

**写集合：** SplitActivation(OPEN)；实际分支 BranchActivation；每个分支 Token(READY)；父 Token CONSUMED；Event；SchedulerWakeup Outbox。

**锁/CAS：** Run → Split identity guard（新建时以唯一键争抢，不持有子表锁）→ parent Token → Activation；随后插入本次 Split 的 BranchActivation 与 branch Token。若实现需要锁定已存在的结构行，必须按 §1.4 的 Split→Token→Branch 顺序。Split identity 对 parent activation + split node + execution version 唯一。

**步骤：** 计算实际激活集合并校验 GatewayPlan；集合必须非空且符合 AND/XOR/OR 编译语义；一次事务创建完整集合并消费父 Token。不得分批创建分支。

**核心测试：** DB-T09-01 AND 全部分支；02 XOR 仅选中分支；03 OR 动态集合与 expectedBranchIds 一致；04 空集合拒绝或走定义的显式空路径；05 任一插入失败全回滚；06 重复 Expand 不产生第二个 Split。

## T10 — Branch Arrival / Join Release

**输入/幂等键：** BranchArrived(messageId, splitActivationId, branchActivationId, joinNodeId, branchOutputHash)。

**读集合：** Inbox、Run、Split、Branch、Branch Token、Join、内层 Split/Join 闭合状态。

**写集合：** Branch→ARRIVED；Branch Token→WAITING；BranchOutput；若 predicate 满足则 Split→CLOSED_SUCCESS、Join→RELEASED、全部参与 Branch Token→CONSUMED、Branch 关闭、唯一 downstream Token→READY；Events/Outbox。

**锁/CAS：** Run → Split → 所有参与 Branch Token（token_id 排序）→ BranchActivation/Join（按 ID 排序）→ BranchOutput。该顺序服从 §1.4，禁止先锁 Branch/Join 再锁 Token。

**步骤：**
1. 校验 branch 属于 split、目标 Join 配对正确、内层结构已闭合。
2. 首次 Arrival 写 Branch/Token/Output。
3. 重新计算完整 predicate（expectedBranchIds + JoinPolicy），不得只用 arrivedCount。
4. 满足时在同一事务关闭所有参与 Branch、消费全部 Branch Token、释放 Join、关闭 Split、创建唯一 downstream Token。
5. 不满足则只提交本次 Arrival。

**核心测试：** DB-T10-01 普通 Arrival；02 最后两个 Branch 并发仅释放一次；03 Join release 同时消费全部 Branch Token；04 重复 Arrival 幂等；05 未激活 OR 分支不计入 expected；06 FAILED/UNKNOWN 不当作 ARRIVED；07 Join 后迟到 Arrival 不产生新 Token；08 内层 Split 未闭合禁止 Arrival。

## T11 — Loop Iteration Advance

**输入/幂等键：** AdvanceLoop(messageId, loopContextId, expectedIteration, closeReason)。

**读集合：** Run、Loop、当前 iteration Tokens/Activations、Split/Join closure、Loop policy。

**写集合：** Loop iteration/version；新一轮 Token/Activation；Loop Event；SchedulerWakeup Outbox。

**锁/CAS：** Run → Loop → 当前轮 Token（排序）→ 相关结构对象。CAS `iteration=expectedIteration`。

**步骤：** 校验当前轮全部 Token 已完成、Split/Join 已闭合；校验 iteration+1 不超过 maxIterations；原子推进 iteration 并创建下一轮 Token。达到上限按定义终止/失败，不允许溢出。

**核心测试：** DB-T11-01 正常推进；02 并发推进只加一次；03 未闭合分支拒绝；04 maxIterations 边界；05 恢复后不重复创建下一轮；06 iteration 不回退。

## T12 — Command Create

**输入/幂等键：** CreateCommand(commandRequestId, activationId, attemptId, deviceId, operation, payloadHash, resourceLeaseId)。

**读集合：** Run、Activation、Attempt、Adapter capability、ResourceLease、命令请求 dedup。

**写集合：** Command Ledger(CREATED)；CommandOutbox(PENDING)；Command→Lease/Attempt 关联；CommandCreated Event。

**锁/CAS：** Run → Lease → Activation → Attempt → Command request dedup。验证当前 Attempt 有权创建该 operation。

**步骤：** 同 requestId 同 hash 返回原 commandId；不同 hash 协议冲突。验证 Adapter capability 和资源要求，生成 commandId，写不可变 payloadHash 和关联关系，创建 Outbox。Command 创建与设备调用严格分离。

**核心测试：** DB-T12-01 正常创建；02 重复 requestId 单命令；03 同 request 不同 payload 拒绝；04 无 Lease/能力拒绝；05 Command 与 Outbox 原子；06 Attempt 已关闭拒绝创建。

## T13 — Command Dispatch

**输入/幂等键：** DispatchCommand(commandId, outboxId, dispatcherClaimId, dispatchAttemptId)。

**读集合：** Run、Command、CommandOutbox、Dispatcher claim/lease、ResourceLease、已有 DispatchAttempt。

**写集合：** Command DISPATCHING；Outbox claim/SEND_STARTED；DispatchAttempt；发送前 Event。调用结果另由结果事务更新。

**锁/CAS：** Run → ResourceLease → Command → Outbox → DispatchAttempt。Claim lease + fencingToken 必须匹配当前 Dispatcher。

**发送前事务：** 提交 SEND_STARTED、commandId、payloadHash、dispatcher claim、dispatchAttemptId 和 attempt 序号。只有提交后才允许 Adapter 调用。

**事务外：** 使用原 commandId 和完全相同 payload 调用 Adapter。

**结果事务：** 明确拒绝→REJECTED；ACK→ACKNOWLEDGED；协议保证完整结束→SUCCEEDED；不能证明是否送达→DELIVERY_UNKNOWN/UNKNOWN。调用结果必须关联 dispatchAttemptId。

**重试裁决：** 仅 Adapter 明确证明未送达，或提供已验证的 commandId 幂等+查询协议，才允许沿用原 commandId 重试；禁止创建新 commandId 掩盖未知结果。

**核心测试：** DB-T13-01 Claim fencing；02 SEND_STARTED 先于网络调用；03 发送前崩溃恢复为未知待核验；04 调用后响应丢失不盲重发；05 Adapter 明确拒绝；06 重复调用同 commandId 按能力矩阵；07 旧 Dispatcher claim 不能发送。

## T14 — Device Event Consume

**输入/幂等键：** DeviceEvent(source, sourceEventId/messageId, commandId, deviceId, operation, sequence, payloadHash)。

**读集合：** Inbox、Command、Attempt/Activation、ResourceLease、设备事件序号/协议状态。

**写集合：** Inbox；Command Ledger 状态；Command Event；ResourceLease（仅有权威物理证据时）；Runtime Event/Outbox；Inbox processed；ProtocolViolation（身份冲突时）。

**锁/CAS：** Inbox Receipt 在独立短事务中只写 RECEIVED，不锁 Run；业务处理事务按 Run → Lease → Command → Inbox 的顺序加锁，并在业务投影、Event、Outbox 写入的同一事务中将 Inbox 标记 PROCESSED。

**步骤：** 按 commandId 唯一归属；核验 deviceId/operation/sequence/payload；只接受合法单调状态迁移；物理释放必须有权威释放证据。无 commandId/未知 commandId 进入 UNATTRIBUTED，不推进 Activation、不释放 Lease。

**核心测试：** DB-T14-01 合法事件；02 重复事件；03 同 messageId 不同 hash 协议冲突；04 乱序/旧序号事件不回退；05 未知 commandId 不猜归属；06 ACK 不代表完成；07 final event + cancel 并发单一裁决；08 无物理空闲证据不得 RELEASED；09 Receipt 提交后、业务处理前崩溃可重新处理；10 业务提交后响应丢失返回首次 outcome；11 不允许 Inbox 先于业务投影标记 PROCESSED。

## T15 — Pause/Stop Request

**输入/幂等键：** PauseRun/StopRun(runId, requestId, expectedRunVersion)。

**读集合：** Run、未派发 Reservation、活动 Dispatch/Command、已有 Drain。

**写集合：** Run→PAUSE_REQUESTED 或 STOP_REQUESTED；Drain request metadata；关闭 Dispatch 准入标志；DrainCoordinator/DisableDispatch Outbox；Request dedup；Runtime Event。

**锁/CAS：** Run 行锁为 Pause/Stop 与 T03-D 的唯一线性化点。Stop 优先于 Pause；若 Stop 到达，创建新的 stop drain identity 并继承未收敛对象，旧 pause drain 不再拥有完成权。

**步骤：** 相同 requestId 幂等；不同 requestId 依据当前 Run 状态和优先级裁决；同事务关闭新 Dispatch 准入、写请求 Event/Outbox；不把设备命令标记为停止，不释放未决 Lease。

**核心测试：** DB-T15-01 Pause 正常；02 Stop 正常；03 Pause/Dispatch 竞争；04 Stop/Dispatch 竞争；05 Stop 覆盖 Pause 且继承 drain items；06 重复请求幂等；07 请求后仍在途命令不被伪造为完成。

## T16 — Drain Start

**输入/幂等键：** StartDrain(runId, drainId, drainKind, requestId)。

**读集合：** Run、全部未终结 Token/Reservation/Attempt/Dispatch/Command/Wait/Lease 的一致查询范围。

**写集合：** DrainRecord(STARTED)；DrainItem 快照/游标；DrainStarted Event；Drain worker Outbox。

**锁/CAS：** Run 行锁；验证 Run 已 PAUSE_REQUESTED/STOP_REQUESTED；snapshot cursor 必须绑定 runCommitVersion/eventSeq。对象扫描可以分页，但必须使用稳定游标/版本，后续新增对象因 Dispatch gate 关闭不得逃逸。

**步骤：** 创建唯一 drainId；记录开始版本与扫描边界；登记需要核验的对象类别和扫描游标。DrainItem 初始快照不是完成证明，后续 T17/T18 仍须追踪新发现对象。

**核心测试：** DB-T16-01 Drain 创建；02 重复 StartDrain 幂等；03 分页扫描无漏项；04 Dispatch gate 关闭后不存在新合法 Dispatch；05 Stop 替代 Pause drain 的归属切换。

## T17 — Reservation Reconcile

**输入/幂等键：** ReconcileReservation(drainId, reservationId, expectedVersion)。

**读集合：** Run、Drain、所有 READY/RESERVED Token、Reservation、Activation、Attempt、WorkerDispatchOutbox/DispatchLease、容量计数。

**写集合：** 未提交 Dispatch 的 Reservation→RELEASED/CANCELLED；未派发 READY/RESERVED Token 按 Pause/Stop policy 保留 READY 或→INVALIDATED；对应未派发 Attempt→ABANDONED_SAFE、Activation→READY/CANCELLED；软件容量返还；DrainItem 状态；Event。

**锁/CAS：** Run → scheduler_capacity（按 capacity_key 排序）→ Token → Activation → Attempt → Reservation/Outbox（依统一锁序）；核验无 DispatchCommitted 或 Outbox 未进入可发送 SEND_STARTED 前沿。

**步骤：** T17 不只回收 RESERVED reservation，还必须重复扫描 Drain 期间新产生的、尚未 Dispatch 的 READY Token（例如在途节点完成后产生的后继 Token）。Pause 保留 READY Token 作为 Resume continuation；Stop 必须将 READY/RESERVED Token 置 INVALIDATED，并关闭其未派发 Activation/Attempt。仅对可证明尚未 DispatchCommitted 的对象释放软件额度。若已有 DispatchCommitted/可能发送，不能按 reservation 回收路径处理，必须转入 T18。绝不在本事务释放物理 ResourceLease。扫描必须可续跑，直至没有未处理 READY/RESERVED 对象。

**核心测试：** DB-T17-01 未派发 reservation 回收；02 Pause 下 Token 恢复/保留 READY 但 gate 仍阻止派发；03 Stop 下 READY 与 RESERVED Token 均 INVALIDATED；04 在途完成产生新 READY Token 后被后续扫描捕获；05 已提交 Dispatch 不得误回收；06 重复 reconcile 幂等；07 容量返还恰好一次。

## T18 — Safe Point / Device Stop Progress

**输入/幂等键：** SafePointObserved(messageId, drainId, objectType, objectId, evidenceHash, observedVersion)。

**读集合：** Run、DrainItem、Activation/Attempt、Command、ResourceLease、Inbox。

**写集合：** 对象已核验安全状态；Command 状态（如收到设备事实）；Lease RELEASED 或 QUARANTINED（只按证据）；Attempt/Activation 收敛状态；Stop 时的 Wait/Retry/Split/Branch/Join/Loop 关闭投影；DrainItem；Runtime Event/Outbox。

**锁/CAS：** Run → Lease → Split/Loop/Wait/Retry（按 §1.4 固定顺序，涉及 Stop 结构关闭时）→ Token → Activation → Attempt → Command → Branch/Join → Inbox；对象版本和 evidence identity 必须匹配。

**边界：** 外部暂停/取消/停止命令在事务外调用；调用请求先由 Command Ledger/Outbox 持久化。T18 只提交 Worker/设备/资源已发生的可验证事实。ACK 不自动等于物理安全点。对 Stop，T18 还负责在所有相关物理副作用已安全收敛后，关闭持久化 Wait/RetryRegistration、Split/Branch/Join/Loop 等控制流结构并使残余 Token 进入合法终结/INVALIDATED 状态；不得在物理副作用未核验时先关闭结构以制造 StopSafePredicate。Pause 则保留可恢复的 Wait/Loop/Join continuation。

**核心测试：** DB-T18-01 纯计算 Worker 安全结束；02 设备明确停止且物理回读通过；03 ACK 但物理仍运行不得安全；04 未知结果进入 MANUAL_REQUIRED/QUARANTINED；05 迟到完成只影响匹配对象；06 Release 未确认不得释放 Lease；07 重复证据幂等；08 Stop 物理收敛后关闭 Wait/Retry/Split/Branch/Join/Loop；09 物理未收敛时禁止提前关闭控制流结构；10 Pause 保留可恢复 continuation。

## T19 — Drain Complete

**输入/幂等键：** CompleteDrain(runId, drainId, expectedRunVersion)。

**读集合：** Run、Drain、所有 Token/Activation/Attempt/Wait/Command/Outbox/Lease/Inbox/人工工作项及其版本。

**写集合：** Drain 终态；Run→PAUSED/STOPPED/FAILED/MANUAL_REQUIRED/QUARANTINED；最终 Event；必要的人工工作项/通知 Outbox。

**锁/CAS：** Run 行锁 + drainId 当前 owner + runCommitVersion；按统一顺序核验关联对象。必须重新计算全量谓词，不能信任 T16 快照。

**唯一判定：**
- PauseSafePredicate 满足才 PAUSED；
- StopSafePredicate 满足才 STOPPED；
- 业务失败且副作用安全收敛才 FAILED；
- 物理/执行结果未知进入 MANUAL_REQUIRED/QUARANTINED；
- 仍有确定在途动作则 Drain 保持进行中，不得提前终结。

**核心测试：** DB-T19-01 Pause 成功谓词；02 Stop 成功谓词；03 任一未决 Command 阻止普通终态；04 任一非 RELEASED Lease 阻止普通终态；05 UNKNOWN 转隔离；06 Drain 完成与迟到事件并发；07 重复 CompleteDrain 返回既有结果。

## T20 — Checkpoint

**输入/幂等键：** CreateCheckpoint(runId, checkpointRequestId, targetEventSeq)。

**读集合：** Run 与全部运行态投影：Token、Activation、Attempt、Split/Branch/Join、Loop、Variable、Wait/Retry、Command/Outbox、Lease、Inbox 投影。

**写集合：** Checkpoint payload、payloadHash、runCommitVersion、eventSeqHighWatermark、coordinatorEpoch、definitionHash。

**快照协议：** 必须使用数据库一致 MVCC snapshot/read timestamp；若不支持，获取 Run checkpoint barrier 并阻止该 Run 状态提交，完成一致读取后释放 barrier。禁止多次非一致查询拼接。

**锁/CAS：** Checkpoint metadata 唯一 requestId；记录实际 snapshot 游标，校验 eventSeqHighWatermark 不大于快照中的 Run eventSeq。

**核心测试：** DB-T20-01 同一快照多表一致；02 并发 Event 提交不会混入半个事务；03 Snapshot 不支持时 barrier 正确；04 payloadHash 可复算；05 Checkpoint 写入失败不影响 Runtime；06 恢复从 checkpoint + 后续 Event 得到相同投影。

## T21 — Unknown Reconciliation

**输入/幂等键：** ReconcileUnknown(intervention/requestId, object identity/version, evidenceRef, evidenceHash, decision)。

**读集合：** Run、Command/Attempt/Activation/Token/Lease、Adapter 查询证据、当前人工工作项、相关事件。

**写集合：** 核验记录；Command/Attempt/Lease 等关联对象合法状态推进；人工工作项；Runtime Event；必要 Outbox。

**锁/CAS：** Run → Lease → Token → Activation → Attempt → Command；每个对象 expectedVersion CAS；证据不可变并与 commandId/resourceId 绑定。

**允许决策：** PROVEN_NOT_SENT、PROVEN_COMPLETED、PROVEN_RUNNING、PROVEN_FAILED_SAFE、STATE_UNDETERMINED。每个决策必须有对应 Adapter/Worker 证据能力；STATE_UNDETERMINED 维持 UNKNOWN/隔离，不得解除。

**核心测试：** DB-T21-01 有证据证明未送达；02 证明已完成；03 证明仍运行；04 证据不足保持 UNKNOWN；05 缺 evidenceHash 拒绝；06 过期对象版本拒绝；07 同一核验请求幂等；08 Command/Lease/Attempt 关联状态同事务一致。

## T22 — Manual Resolution

**输入/幂等键：** ResolveManual(interventionId, workItemId, expectedObjectVersions, decision, evidenceRef, evidenceHash, actorId, authorizationRef, reason)。

**读集合：** Run、工作项、所有受影响对象及版本、操作者授权、证据引用。

**写集合：** 不可变 ManualResolution；关联 Command/Lease/Attempt/Activation/Token/Run 合法状态推进；WorkItem 关闭/升级；Runtime Event；Outbox。

**锁/CAS：** Run → Lease → Token → Activation → Attempt → Command → WorkItem；授权在事务前可验证，但授权版本/凭据必须在提交时仍有效或带有可验证签名。

**规则：** 只允许预定义 decision enum；禁止直接写状态字段绕过状态机；一次 workItem 只允许一个成功决策，后续更正需新 interventionId 并引用前一记录。

**核心测试：** DB-T22-01 合法人工决策；02 无授权拒绝；03 无证据拒绝（决策类型要求证据时）；04 同一 workItem 并发只一个胜者；05 只改 Run 不改对象被拒绝；06 审计不可变；07 后续更正形成新记录。

## T23 — Quarantine Clear

**输入/幂等键：** ClearQuarantine(interventionId, resourceId, leaseId, expectedLeaseVersion, authorityEvidenceRef, evidenceHash, authorizedActor)。

**读集合：** Run、ResourceLease、QuarantineRecord、资源权威方物理状态回读、相关 Command。

**写集合：** QuarantineClear audit；Lease QUARANTINED→RELEASED（仅物理空闲得到权威证明）；QuarantineRecord CLEARED；资源准入恢复；Runtime Event/Outbox。

**锁/CAS：** Run（如关联 Run）→ ResourceLease（resourceId 唯一准入锁）→ Command；expectedLeaseVersion + quarantineId CAS。

**规则：** 资源权威方证明物理空闲、没有未决 command、证据与 resourceId/leaseId 匹配且授权有效，才可解除。数据库人工勾选、TTL 到期、Worker 消失、ACK 或超时均不是解除证据。

**核心测试：** DB-T23-01 有权威空闲证据解除；02 仍运行拒绝；03 证据不匹配拒绝；04 过期版本拒绝；05 并发 Clear 只成功一次；06 Clear 前新 owner 获取被拒绝；07 Clear 后才恢复准入；08 审计记录不可变。

---

# 3. 状态转移自动化测试设计

## 3.1 测试分层

| 层 | 测试对象 | 是否连真实 DB | 目的 |
|---|---|---:|---|
| L0 Pure State Machine | 纯转移函数 | 否 | 状态×事件合法性、唯一裁决 |
| L1 Repository/Constraint | Repository + 约束 | 是（真实选型 DB） | CAS、唯一约束、锁、事务回滚 |
| L2 Transaction Integration | T01–T23 | 是 | 多对象原子性、Inbox/Outbox |
| L3 Concurrency | 多连接/多进程 | 是 | 线性化、死锁、竞争 |
| L4 Fault Injection | 进程/连接/网络/Adapter | 是 + 测试桩 | 崩溃恢复与外部不确定性 |
| L5 Hardware-in-loop | 真实 Adapter/设备或等价网关 | 是 | commandId 幂等、物理状态、fencing |

L0 快速且覆盖全面，但不能代替 L1–L5。任何“状态机测试通过”的结论必须注明测试层级。

## 3.2 State × Event 测试矩阵

对以下聚合生成完整或约束生成的状态×事件测试：

| 聚合 | 状态轴 | 事件轴 | 必须断言 |
|---|---|---|---|
| Run | CREATED/STARTING/RUNNING/PAUSE_REQUESTED/PAUSE_DRAINING/PAUSED/STOP_REQUESTED/STOP_DRAINING/STOPPED/FAILED/MANUAL_REQUIRED/QUARANTINED | Start/Pause/Stop/DrainComplete/Failure/Recovery | 只允许定义转移；终态谓词成立 |
| Token | CREATED/READY/RESERVED/IN_FLIGHT/WAITING/CONSUMED/INVALIDATED/TERMINAL | Reserve/Dispatch/Complete/Suspend/Wait/Retry/Join/Stop | 不重复消费、不双 owner |
| Activation | CREATED/READY/ACTIVE/SUSPENDED/RETRY_WAIT/COMPLETED/FAILED/UNKNOWN/MANUAL_REQUIRED/CANCELLED | Dispatch/Started/Complete/Fail/Suspend/Wait/Retry/Drain | 当前 Attempt 唯一 |
| Attempt | CREATED/DISPATCH_PENDING/DISPATCHED/ACCEPTED/STARTED/SUSPENDED/ABANDONED_SAFE/RETRYABLE_FAILED/PERMANENT_FAILED/SUCCEEDED/UNKNOWN/CANCELLED_CONFIRMED | Dispatch/Accepted/Started/Suspend/Complete/Fail/Timeout/Cancel/Reconcile | 禁止越级与旧 Attempt 覆盖 |
| Wait | ACTIVE/SATISFIED/EXPIRED/CANCELLED | Satisfied/Expired/Cancel | 仅一个终态胜者 |
| Split/Branch/Join | OPEN/ARRIVED/CLOSED/RELEASED/FAILED 等定义状态 | Expand/Arrival/Fail/JoinRelease | expected 集合闭合、Join 单次释放 |
| Loop | ACTIVE/ITERATION_CLOSING/ITERATION_ADVANCED/COMPLETED/FAILED | Close/Advance/Limit | iteration 单调、无重复轮次 |
| Command | CREATED/PREPARED/DISPATCHING/ACKNOWLEDGED/RUNNING/SUCCEEDED/REJECTED/FAILED/CANCEL_REQUESTED/CANCELLED_CONFIRMED/DELIVERY_UNKNOWN/UNKNOWN | Dispatch/Ack/Progress/Final/Cancel/Reconcile | ACK 不等于完成、未知不重发 |
| ResourceLease | ACQUIRING/ACQUIRED/RELEASE_PENDING/RELEASED/EXPIRED_UNVERIFIED/QUARANTINED | Acquire/Release/TTL/PhysicalState/Clear | 未证实释放不得重新分配 |
| Inbox | RECEIVED/PROCESSING/PROCESSED/REJECTED | Receive/Retry/Conflict | 同身份同 hash 幂等、不同 hash 冲突审计 |
| Coordinator | owner epoch/lease 状态 | Acquire/Renew/Expire/Recover | 旧 owner 控制命令拒绝，事实消息按对象核验 |

每个矩阵行必须由规范状态枚举驱动生成，禁止测试代码维护另一份不受校验的状态枚举副本。

## 3.3 每条转移的标准断言

每个合法转移至少断言：

1. 目标对象状态与 row_version 正确；
2. 所有被关联对象状态同步；
3. runCommitVersion 恰好按一次成功聚合提交增加一次；
4. eventSeq 与 Runtime Event 数量、顺序正确；
5. Inbox outcome 与状态提交原子一致；
6. Outbox 消息数量、身份、payloadHash 正确；
7. 事务回滚时所有投影、Event、Outbox、Inbox processed 均未部分提交；
8. 重放相同消息返回相同 outcome，不重复副作用；
9. 旧版本/旧 Attempt/错误 owner 不能覆盖新状态；
10. 不变量查询返回零违规记录。

每条非法转移至少断言：拒绝原因稳定、无投影变化、无 Event 伪造成功、无 Scheduler 可调度 Token；若输入本身需要审计，则只写拒绝审计/ProtocolViolation。

## 3.4 属性测试（Property-based）

采用可重放随机种子，生成有效/无效事件序列。每次失败必须输出 seed、最短失败序列、初始状态和数据库快照引用。

必须持续验证：

- P01 Token 不会同时归属两个有效 Activation。
- P02 Token 最多消费一次，终态不可回到 READY。
- P03 Activation 同时最多一个有效 Attempt。
- P04 Attempt 的 dispatchId 不变且唯一。
- P05 旧 Attempt completion 不改变当前 Activation/Token。
- P06 一个 Split 的 expectedBranchIds 创建后不可变。
- P07 Join 仅在 predicate 满足时释放，且最多一次。
- P08 Join RELEASED 时所有参与 Branch Token 均 CONSUMED，Branch 均 CLOSED。
- P09 Loop iteration 单调递增且不超过 maxIterations。
- P10 Wait/RetryRegistration 只消费一次，不重复创建 READY Token。
- P11 Run 非 RUNNING 时不产生新的普通 Dispatch Commit。
- P12 Worker ACCEPTED 不代表 STARTED。
- P13 Command UNKNOWN 不会普通自动重发。
- P14 Lease TTL 到期不会变成 RELEASED。
- P15 Lease 非 RELEASED/QUARANTINED 时资源不得授予新 owner。
- P16 Inbox 相同身份不同 hash 不覆盖原始 payload/hash。
- P17 同一 Run 已提交 eventSeq 严格递增，runCommitVersion 单调。
- P18 Checkpoint 可由同一快照游标重放验证。
- P19 Run SUCCEEDED/STOPPED/FAILED 必须满足对应终态谓词。
- P20 人工处置不能跳过合法状态转移或缺少审计。

建议再做 model-based differential testing：同一事件序列分别运行纯状态机模型和真实 Repository/DB，比较所有聚合投影与事件序列。差异即失败，不以最终 Run 状态相同视为通过。

## 3.5 数据库不变量巡检 SQL 规格

以下是逻辑查询，不绑定具体 SQL 方言；实现时必须交付可运行 SQL 和空结果证明：

| ID | 违规检测 | 期望结果 |
|---|---|---|
| INV-DB-01 | 同一 Token 多个有效 Activation | 0 行 |
| INV-DB-02 | 同一 Activation 多个活动 Attempt | 0 行 |
| INV-DB-03 | Attempt dispatchId 重复 | 0 行 |
| INV-DB-04 | Join RELEASED 仍有参与 Branch Token 非 CONSUMED | 0 行 |
| INV-DB-05 | Split CLOSED 仍有 Branch ACTIVE/WAITING | 0 行 |
| INV-DB-06 | Wait 非 ACTIVE 却存在有效 continuation owner 冲突 | 0 行 |
| INV-DB-07 | Command UNKNOWN 被 Outbox 标记普通可重试 | 0 行 |
| INV-DB-08 | Lease QUARANTINED/EXPIRED_UNVERIFIED 却存在新 owner | 0 行 |
| INV-DB-09 | Inbox 同 source/messageId 多 payloadHash 被覆盖/缺 ProtocolViolation | 0 行 |
| INV-DB-10 | Run 终态但仍有未决 Command/Lease/Token | 0 行 |
| INV-DB-11 | Event eventSeq 重复或超出 Run 游标 | 0 行 |
| INV-DB-12 | RunCommitVersion 变化但无对应事务 Event/审计 | 0 行 |

巡检应作为 CI 集成测试的一部分，并能对测试数据库全量执行。

---

# 4. 并发与线性化测试

## 4.1 测试执行原则

- 使用真实目标数据库，不用单线程 mock 模拟锁行为。
- 至少两个独立连接；关键竞态使用两个独立进程。
- 用 barrier/latch 控制两个事务到达指定 SQL 前后，不用 sleep 猜测时序。
- 每个竞态测试记录事务开始、锁获取、CAS、提交时间和最终 eventSeq。
- 每个场景必须定义允许的唯一序列化结果集合；集合外结果即失败。
- 测试失败时保留数据库日志、deadlock graph、事务 trace 和 seed。

## 4.2 强制竞态清单

| ID | 并发双方 | 唯一允许裁决 |
|---|---|---|
| CON-01 | 两个 Worker completion 同一 Attempt | 一个提交成功；另一个幂等返回或 STALE，不重复消费 Token |
| CON-02 | 两个 Branch Arrival 同一 Branch | 一次 Arrival 生效 |
| CON-03 | 最后两个 Branch 同时 Arrival | 一个事务执行 Join release；另一个看到 RELEASED 幂等结束 |
| CON-04 | Pause Request vs Dispatch Commit | Run 行锁先提交者决定：Pause 先则拒绝 Dispatch；Dispatch 先则纳入 Drain |
| CON-05 | Stop vs Pause | Stop 优先；继承未完成对象，旧 Pause drain 不得终结 Run |
| CON-06 | WaitSatisfied vs WaitExpired | Wait CAS 单一胜者；败者记迟到输入 |
| CON-07 | RetryDue vs Stop | Stop 先则不产生 READY/Dispatch；Retry 先则后续 Stop 纳入 Drain |
| CON-08 | Device final event vs Cancel | 依 Command 状态机和设备序号唯一裁决，不得双终态 |
| CON-09 | Lease renew vs TTL expiry scan | 由 Lease version/权威状态决定；不能仅按本地扫描时间释放 |
| CON-10 | Coordinator takeover vs 新消息入 Inbox | Inbox 可接收；RecoveryCompleted 前不推进状态/不开放 Scheduler |
| CON-11 | 两个 Dispatcher claim 同一 Outbox | fencing/claim CAS 仅一个有效 sender |
| CON-12 | 两个 T23 Quarantine Clear | 一个成功，另一个幂等返回或 stale |
| CON-13 | 两个 T21/T22 核验/人工决策 | 对象版本与 work item CAS 只允许一个决策提交 |
| CON-14 | Checkpoint vs 多表状态提交 | Checkpoint 只观察一个一致快照版本，不出现混合态 |

---

# 5. 故障注入测试设计

## 5.1 故障注入框架要求

测试框架必须支持可命名故障点（FaultPoint），并允许在 SQL statement、事务提交、消息投递、网络调用和回执处理边界注入：

- 进程 SIGKILL/容器强制终止；
- DB 连接断开、事务超时、死锁、serialization failure；
- Commit 请求发出后丢弃响应（模拟提交结果未知）；
- Outbox send 成功后丢弃本地状态更新；
- Worker/Adapter 请求送达但丢弃响应；
- 延迟、重复、乱序、篡改、丢失消息；
- Coordinator lease 过期与双 owner 竞争；
- 设备物理状态回读不可用/矛盾；
- 时钟偏差、Timer 重复触发、TTL 扫描延迟。

每个 FaultPoint 必须记录注入配置、随机种子、发生阶段、恢复动作和最终数据库快照。

## 5.2 数据库事务通用故障点

对 T01–T23 每个 DB transaction 执行：

- F-DB-01 BEGIN 前崩溃；
- F-DB-02 每个 SQL 写入后崩溃；
- F-DB-03 COMMIT 前崩溃；
- F-DB-04 COMMIT 成功但响应丢失；
- F-DB-05 Commit 后、Outbox Dispatcher 唤醒前崩溃；
- F-DB-06 Outbox 已发送、投递状态未更新时崩溃；
- F-DB-07 死锁/序列化冲突后整体重试；
- F-DB-08 同幂等身份并发重试；
- F-DB-09 DB 主从切换/连接池失效（若生产架构包含）。

**统一期望：** 每个事务要么全部提交，要么全部回滚；Commit outcome unknown 时按原幂等身份查询；Outbox 可重扫；不存在部分 Event/Outbox/Inbox 状态。

## 5.3 T01–T23 故障矩阵

| 事务 | 注入点 | 恢复后必须成立 |
|---|---|---|
| T01 | Run/Token/变量写后、Commit 前 | 全部回滚；相同 requestId 可安全重试 |
| T02 | Activation 写后、Token 更新前 | 无半启动；Run 与初始 Token 一致 |
| T03-R | 容量扣减后、Reservation 写入前 | 容量扣减回滚；无超卖/泄漏 |
| T03-D | Dispatch Outbox 写后、Commit 前 | 全部回滚；不可能外部发送 |
| T03-D | Commit 后发送前 | Outbox 可重扫；Pause/Stop 能看到 Dispatch |
| T04-A | Worker 收到 Dispatch、Accepted 入库前 | Worker 事实可查询；不能无证据重派副作用任务 |
| T04-S | Handler 开始后、Started 入库前 | Worker 执行事实核验；不把缺 Started 当未执行 |
| T05 | 输出写后、Token consume 前 | 全部回滚；重放只提交一次 |
| T06 | RetryRegistration 写后、Event 前 | 全部回滚；不存在 RetryWait 无注册 |
| T07 | Wait 写后、Token WAITING 前 | 全部回滚；不能存在孤儿 Wait |
| T08 | Wait CAS 后、旧 Attempt 关闭前 | 全部回滚；不能出现 continuation 与旧 Attempt 同时有效 |
| T09 | 部分 Branch 插入后 | 整个 Split 回滚；无半个分支集合 |
| T10 | 最后 Arrival 后、Join Release 中途 | 全部回滚或完整释放；无双 Join/孤儿 Branch Token |
| T11 | iteration 更新后、新 Token 创建前 | 全部回滚；iteration 与 Token 同步 |
| T12 | Command 写后、Outbox 写前 | 全部回滚；不存在无投递记录的 Command |
| T13 | SEND_STARTED 提交后、Adapter 调用前 | DELIVERY_UNKNOWN/核验；除非可证明未送达不得自动重发 |
| T13 | Adapter 收到请求后、响应前 | 使用原 commandId 查询/核验；非幂等设备隔离 |
| T14 | Inbox Receipt 写后、业务处理前 | Inbox 保持 RECEIVED；恢复后由当前 Coordinator 重新处理，不产生业务投影副作用 |
| T14 | Processing 中 Inbox/Command/Event/Outbox 写入后、Commit 前 | Processing transaction 全部回滚；Receipt 仍 RECEIVED，可按原身份重试 |
| T14 | 业务提交成功但响应丢失 | Inbox 已 PROCESSED 且业务投影完整；重放返回首次 outcome |
| T14 | 设备事件已提交、ACK 丢失 | 重复事件幂等；不重复推进 |
| T15 | Run gate 关闭后、Outbox 写前 | 同事务回滚；不可出现 gate 关闭但无 Drain 请求 |
| T16 | DrainRecord 写后、DrainItem 分页中 | 通过稳定游标续扫；不漏对象 |
| T17 | 容量返还后、Reservation/Token 状态更新前 | 全部回滚；不重复返还；READY/RESERVED 扫描可继续 |
| T17 | Drain 期间 T05 新建 READY 后继 Token | 下一轮 reconcile 捕获；Pause 保留，Stop INVALIDATED |
| T18 | 物理收敛后、控制流结构关闭前 | 事务回滚后可重放；不提前满足 StopSafePredicate |
| T18 | Wait/Join/Loop 结构关闭中途 | 全部回滚；不存在半关闭控制流结构 |
| T18 | 外部停止请求已发出、结果未回写 | 命令状态 UNKNOWN/进行中；不宣称物理安全 |
| T19 | 最终谓词检查后、Run 状态写前 | 事务回滚；重试时重新检查谓词 |
| T20 | 多表快照期间并发提交 | checkpoint 对应单一 MVCC 游标；不拼接混合版本 |
| T21 | 证据保存后、对象更新前 | 全部回滚或保留独立不可变证据暂存；不得半核验 |
| T22 | 人工决策写后、Run/Lease 更新前 | 同事务回滚；不出现无审计状态修改 |
| T23 | 资源核验后、Lease RELEASED 前 | 保持隔离；重试需核验版本/证据仍有效 |

## 5.4 设备外部副作用专项矩阵

| 场景 | 故障 | 允许行为 | 禁止行为 |
|---|---|---|---|
| Adapter 调用前崩溃 | SEND_STARTED 已提交但调用未发生 | 进入 UNKNOWN；通过 commandId 查询或有证据证明未发送 | 仅凭进程日志认定未发送并新建 commandId |
| Adapter 收到后崩溃 | 设备可能已执行 | 原 commandId 查询/幂等重试；否则隔离 | 创建新 commandId 重复动作 |
| ACK 丢失 | 设备可能接受 | 查询 commandId/设备状态 | 把超时当拒绝 |
| ACK 到达但无完成事件 | 设备已接受但未必完成 | Command=ACKNOWLEDGED/RUNNING | Command=SUCCEEDED、释放 Lease |
| 完成事件迟到 | Run 已进入 Drain | 按 commandId 核验事实，继续收敛 | 旧事件覆盖新 Command 状态 |
| 设备报告完成但物理回读矛盾 | 证据冲突 | UNKNOWN/MANUAL_REQUIRED/QUARANTINED | 仅凭软件事件释放资源 |
| 设备取消响应成功但仍在运动 | 软件确认与物理事实不一致 | 继续 Drain/隔离 | PAUSED/STOPPED |
| 资源 Lease TTL 到期 | 设备可能仍占用 | EXPIRED_UNVERIFIED | 新 owner 获取 |

## 5.5 Coordinator/Worker 恢复专项

1. Coordinator A 持有 epoch N；暂停其心跳并让 B 获取 epoch N+1。
2. A 随后发送旧控制命令：必须被拒绝。
3. 旧 Worker 返回 completion，携带 dispatchEpoch=N：若匹配当前 Activation/Attempt/dispatchId/attemptVersion，允许进入 Inbox，由 B 核验后决定提交。
4. B 尚未提交 RecoveryCompleted 时，Scheduler 不得开放。
5. 恢复期间 Inbox 可接收但不推进控制流；RecoveryCompleted 与待处理 Inbox 的消费顺序必须可证明。
6. 两个 Coordinator 同时认为自己有效时，数据库 CAS/fencing 必须保证只有一个可写 Run 控制流投影。
7. Worker dispatch lease 的旧 fencing token 不能继续发送新的 Worker/设备动作。

## 5.6 故障恢复后的统一断言

每次故障注入结束后运行完整不变量巡检，至少确认：

- 无半提交事务；
- 无双重 Token owner/Activation Attempt；
- 无半个 Split、双 Join、孤儿 WAITING Token；
- 无重复逻辑 commandId；
- 无 UNKNOWN Command 被普通自动重发；
- 无未核验 Lease 被释放或重新分配；
- 无旧 Coordinator 覆盖新状态；
- 无 Run 终态隐藏未决副作用；
- 无 Inbox 已标记 processed 但业务状态未提交；
- 无业务状态提交但缺少对应 Event；
- 无 Outbox 永久不可发现；
- 无无证据人工状态跳转；
- 所有恢复动作可由 eventSeq、对象版本、messageId、commandId 和证据引用审计。

---

# 6. 测试数据、环境与运行策略

## 6.1 必备测试夹具

- 最小 Definition：单节点纯计算、两节点串行、AND Split/Join、XOR、OR 动态分支、Loop、Wait、Retry、设备命令、Pause/Stop。
- 至少两种 Adapter stub：一种支持 commandId 幂等和查询；一种不支持幂等/查询，用于验证 UNKNOWN/隔离路径。
- Worker stub：可在 Accepted、Started、SideEffectStarted、Completion 前后崩溃。
- Resource authority stub：可模拟 fencing、物理占用、回读矛盾、释放确认丢失。
- 消息注入器：重复、乱序、延迟、丢失、payloadHash 冲突。
- Coordinator 双实例：可强制 epoch takeover 和旧实例复活。
- DB fault proxy：断连、Commit response drop、deadlock/serialization failure。

## 6.2 CI 分层执行

| 阶段 | 测试集 | 执行频率 | Gate |
|---|---|---|---|
| PR 快速 | L0 全转移矩阵 + L1 关键约束 + 确定性单测 | 每次 PR | 必须全绿 |
| PR 集成 | T01–T23 happy/negative/idempotency | 每次 PR | 必须全绿 |
| Nightly | 属性测试、并发竞态、DB fault injection | 每夜 | 必须全绿；失败阻断合并 |
| Release candidate | 全量崩溃矩阵、恢复重放、性能/死锁 | 每个 RC | 必须全绿 |
| Hardware-in-loop | 真实 Adapter/设备能力、fencing、物理状态核验 | 设备版本/协议变更时及发布前 | UNKNOWN 项关闭 |

## 6.3 失败报告格式

每个失败必须报告：

- Test ID / FaultPoint；
- Git commit、数据库版本、隔离级别、DDL migration version；
- 随机 seed 和事件序列；
- 事务 trace（锁顺序、SQL、affected_rows、提交结果）；
- Run/Token/Activation/Attempt/Command/Lease 快照；
- Inbox/Outbox/Event 序列；
- 预期状态与实际状态差异；
- 是否存在物理副作用不确定性；
- 是否需要人工核验；
- 最小复现步骤。

不能只报告“测试失败”或只贴最终 Run status。

---

# 7. 实施交付物与冻结 Gate

## 7.1 数据库实现必须交付

1. 物理 ERD 与本文件逻辑表映射。
2. 完整 DDL、索引、唯一约束、部分唯一索引/等价机制。
3. T01–T23 SQL 读集合/写集合与锁顺序清单。
4. 每个 CAS 的 WHERE 条件和 affected_rows 处理。
5. 每个幂等键及 payloadHash 规范。
6. Event/Inbox/Outbox 同事务实现证明。
7. Checkpoint 一致快照技术证明。
8. 死锁检测、重试上限、幂等重试策略。
9. DB 不变量巡检 SQL。

## 7.2 自动化测试必须交付

1. 规范状态枚举自动生成的 State × Event 矩阵。
2. T01–T23 每项正常、拒绝、重复、过期版本测试。
3. 属性测试及可复现 seed。
4. CON-01 至 CON-14 并发测试。
5. F-DB-01 至 F-DB-09 通用 DB 故障测试。
6. T01–T23 故障矩阵。
7. Adapter/Worker/Resource authority 故障注入测试。
8. 恢复后全量不变量巡检。
9. CI/nightly/RC Gate 配置与机器可读测试报告。

## 7.3 实施冻结条件

以下全部满足才允许把相应能力标记为 Implemented：

- [ ] T01–T23 均有物理 SQL/事务实现及代码定位；
- [ ] 所有写事务遵循统一锁顺序，死锁重试不破坏幂等；
- [ ] 唯一约束、CAS 和 affected_rows 检查已在真实目标 DB 验证；
- [ ] 每个状态转移均有正向、反向、重复、旧版本测试；
- [ ] CON-01 至 CON-14 全部通过；
- [ ] T01–T23 故障矩阵覆盖完成；
- [ ] Outbox/Inbox 重放与 Commit outcome unknown 测试通过；
- [ ] UNKNOWN/Manual/Quarantine 流程有完整证据与审计测试；
- [ ] Checkpoint 快照一致性经数据库实测；
- [ ] Adapter commandId 幂等、查询、物理状态回读逐设备分类；
- [ ] fencingToken 在设备/网关实际生效，或明确标注该资源不支持自动安全接管；
- [ ] 真实设备 HIL 测试通过，且不存在被软件状态掩盖的物理不确定窗口；
- [ ] 第二个独立实现/评审者复核事务矩阵与故障报告。

## 7.4 当前 UNKNOWN 与关闭方法

| UNKNOWN | 当前不能声称 | 关闭所需证据 |
|---|---|---|
| DB isolation/snapshot | 多表 checkpoint 一致 | 选定 DB + 隔离级别 + 并发快照测试报告 |
| DB lock/CAS | Run 线性化、资源唯一 owner | DDL + 双连接/多进程竞态测试 |
| Adapter commandId | 设备命令可安全重试 | 每种设备协议的幂等/查询契约和 HIL 证据 |
| fencing 落点 | 旧 owner 无法控制物理设备 | 设备/网关拒绝旧 token 的集成测试 |
| Worker execution facts | Accepted/Started/副作用边界可恢复 | Worker 崩溃注入和执行账本核验 |
| T01–T23 SQL set | 事务读写集合无遗漏 | 物理 SQL trace、锁顺序审查、约束巡检 |

---

# 8. 最终工程判定

本文件完成的是**实现前可执行规格**，不是 Runtime 实现完成证明。

当 T01–T23 的逻辑事务、数据库约束、状态转移矩阵、并发竞态和故障注入按本文落地后，才能对每个能力分别给出：

- **PASS**：在明确的数据库/Worker/Adapter/设备版本与测试环境中，所有对应测试通过；
- **FAIL**：存在可复现违反事务原子性、状态不变量或安全谓词的行为；
- **UNKNOWN**：缺少真实技术能力或外部证据，不能证明安全。

不得用“测试覆盖率高”“文档已完整”替代 PASS 证据；不得把 UNKNOWN 解释为默认安全。

