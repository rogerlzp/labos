# G5 Wait / Timer / Timeout 设计冻结候选稿

- 状态：**冻结候选稿，待最终架构签字；不是实现完成声明**
- 代码审查基线：`491cd2cc88968203461f18c038948dce5b7eddf8`（G4-A/B/C/D 集成基线；实现前必须再次确认目标分支 HEAD）
- 文档演进：基于 v2 评审稿 `ae7fe1c88733bd57c061e76a35beb82734f7b374` 修订
- V1 部署边界：单实例、单进程、单 PostgreSQL；不引入分布式租约、fencing token、消息中间件或多实例协调
- 本文为唯一规范版本：旧稿中与本文冲突的条款不再有效；不再依赖“后附章节优先”解释冲突

## 1. 目标、范围与结论

G5 在现有 Workflow Runtime 上增量实现纯图 Wait、持久化 Timer、外部事件 Wait 和可选 Timeout 分支。复用既有 `wait_registration`、`node_activation`、`execution_token`、`inbox_receipt`、`runtime_event` 与 `RuntimeDispatchLoop`，不建立平行 Wait/Timer 子系统。

纯图 Wait 有 WAITING Activation、没有 node_attempt；task-backed Wait 的既有 API 和行为必须保持兼容。所有 Wait 注册、完成、取消、Inbox 消费及 successor Token 创建均以 Repository 事务为边界。外部事件与 Timer 通过 PostgreSQL Run 行锁串行化，并以 Wait ACTIVE 条件更新实现 single-winner。

G5 不包含 Retry/Backoff（G6）、Error Boundary、Compensation/Saga、非中断 Timeout、广播式事件订阅、Cron/周期 Timer/日历、动态 correlationKey 表达式、真实设备协议扩展或任何分布式协调。

## 2. 代码事实与设计约束

以下是实现前必须再次按目标 SHA 核验的已知基线事实：

- `registerWait(...)` 面向 task-backed Wait，要求 Activation 为 RUNNING/RESERVED。
- `closeWait(..., oldAttemptId)` 依赖非空 oldAttemptId，并将旧 Attempt 安全放弃；不得把 null 偷渡进此 API。
- 既有 `closeWait` 从 `continuation->>'nodeKey'` 读取成功后继；缺失会失败。既有 task-backed continuation 格式必须继续可读。
- GraphPlan Node/Edge 为定长 record；GraphPlanCodec 的 JSON writer 手工逐字段写出，reader 当前只接受 schemaVersion=1。新增字段必须同步修改读写两端。
- GraphCoordinator 将非 TASK/BUILTIN 节点作为控制节点；WAIT 必须有独立注册分支，不能让 WAIT 落入返回 null 后继续循环的路径。
- `LegacyConditionNormalizer.normalizePlan` 重建 Edge 时必须透传所有新增 Edge 字段，包括 `routeKind`。
- 既有 wait_registration 有 `wait_due_idx` 与 `wait_one_active_per_activation_uq`；新增 correlation 唯一索引与它们叠加生效，不替代它们。
- 既有 runtime_event.event_type 为 text；事件名虽无需枚举 DDL，仍须统一词表。
- G4-A 启动恢复 Step 0 只处理设备 Command Inbox 白名单，不是通用 Inbox 消费者。

上述事实是设计约束，不代表 G5 代码已经实现或测试通过。

## 3. 数据模型与状态机

### 3.1 纯图 Wait 状态

| 阶段 | Token | Activation | Attempt | Wait |
|---|---|---|---|---|
| 到达节点 | READY | 不存在 | 不存在 | 不存在 |
| 注册提交 | WAITING | WAITING，current_attempt_id=NULL | 不存在 | ACTIVE |
| Event 完成 | 原 Token CONSUMED | SUCCEEDED | 不存在 | SATISFIED |
| Timer 完成 | 原 Token CONSUMED | SUCCEEDED | 不存在 | EXPIRED |
| STOP/FAILURE 清理 | INVALIDATED | CANCELLED | 不存在 | CANCELLED |

注册时在一个 Repository 事务中：锁 Run；校验 RUNNING/OPEN；锁 READY Token 并校验 nodeKey；创建 WAITING Activation（activation_no 在 Run 锁保护下分配）；创建 ACTIVE Wait；READY Token -> WAITING；写 WAIT_REGISTERED。任一步失败整体回滚。

完成时在一个 Repository 事务中：锁 Run -> Token -> Activation -> Wait（事件路径最后再锁 Inbox）；校验 Run 状态/Gate、Wait ACTIVE、version 和 outcome 条件；Wait CAS 到 SATISFIED/EXPIRED；Activation WAITING -> SUCCEEDED；原 Token WAITING -> CONSUMED；创建唯一 successor READY Token；写 Runtime Event；若由 Inbox 触发，同事务 Inbox -> PROCESSED。任一步失败整体回滚。

纯 Wait 的 Activation 置 SUCCEEDED，与旧 task-backed `closeWait` 将 Activation 置 ABANDONED_SAFE 是不同语义：纯 Wait 没有被放弃的 Attempt。必须保留 task-backed 既有行为，并分别测试两种终态的 `maybeCompleteGraphRun` 计数语义。

### 3.2 Repository API

至少提供以下明确 API（具体命名可调整，但契约不可弱化）：

- `registerGraphWait(tenantId, runId, tokenId, expectedNodeKey, WaitSpec)`
- `completeGraphWait(tenantId, runId, waitId, outcome, inboxId?)`
- `findDueWaits(limit)`：只读、有界候选发现
- `findPendingWaitInboxReceipts(limit)`：只读、有界 Inbox 候选发现
- `receiveWaitInbox(...)`：幂等接收，并按本节定义同步处理或持久化待重放
- `cancelActiveWaitsForStop(...)`：与 STOP/FAILURE continuation 清理在同一事务中完成

结果类型至少区分 COMPLETED、ALREADY_COMPLETED、DEFERRED_RUN_NOT_OPEN、NOT_FOUND、STALE_VERSION、WAIT_FOR_INBOX、REJECTED；不得用 boolean 混淆暂停、竞争失败和永久拒绝。

### 3.3 continuation 向后兼容（H1，硬约束）

不得把既有 `{"nodeKey":"..."}` 直接替换为仅含 `successNodeKey` 的新结构。G5 新 continuation 必须保留 `nodeKey` 作为成功/普通路径的兼容字段，并新增 `continuationVersion` 和 timeout 字段，例如：

```json
{
  "continuationVersion": 2,
  "nodeKey": "successNode",
  "timeoutNodeKey": "timeoutNode"
}
```

读取规则：成功路径优先读取 `successNodeKey`（若未来引入），否则读取既有 `nodeKey`；Timeout 路径读取 `timeoutNodeKey`。写入 G5 v2 continuation 时仍必须写 `nodeKey`。task-backed `closeWait` 对旧格式与新兼容格式均可正确读取。不得仅靠 JSON 字段猜测未编译的目标；写入前必须验证目标属于该 GraphPlan 的对应合法边。

### 3.4 Activation 完成性不变量（H5/H6）

纯图 Wait Activation 可以是 SUCCEEDED + current_attempt_id=NULL，但这不能成为绕过“已路由完成”的安全网的方式：

- Wait 完成、原 Token 消费、successor Token 创建必须在同一事务中提交。
- Wait 信号入口必须由新的 `receiveWaitInbox(...)` 在同一个 `required()` 事务内完成 Inbox 落账、Wait 推进和 Inbox -> PROCESSED；**禁止复用现有 `receiveInbox` + `applyCommandEvent` 两段式事务路径**，否则会重新引入 Inbox 与业务推进之间的崩溃窗口。
- G5 必须在 Repository 完成事务中断言恰好一个 successor Token 被创建（除非模型明确定义的终止节点；G5 Wait 节点强制恰好一条正常边，因此本版不适用终止例外）。
- 增加等价于现有“未路由完成”守卫的纯 Wait 断言：任何已完成的纯 Wait Activation 必须能关联到已消费的 Wait Token 和唯一 successor Token；若无法证明，Run 不得被判定 SUCCEEDED。
- 增加事务中途失败测试：Wait/Activation/Token/Event/Successor 任一步写入失败，不能残留部分提交。

## 4. GraphPlan 与 Codec（H2/H3）

### 4.1 GraphPlan v2

新增 `GraphPlan.Kind.WAIT`、`WaitSpec` 和 `Edge.routeKind`（NORMAL/TIMEOUT）。WaitSpec 至少包含 waitKind、静态 correlationKey（MESSAGE 类）、duration（TIMER/Timeout 类）。Timeout Boundary 归一化到宿主 WAIT Node，不生成独立 Node/Token。

GraphPlan v1 的 Node/Edge 历史字段和含义完全不变。普通无 Wait 图继续输出 schemaVersion=1；含 WAIT/WaitSpec/routeKind 的图输出 schemaVersion=2。v1 快照携带 WAIT 扩展必须 fail-closed。

### 4.2 Codec 双向兼容

GraphPlanCodec 必须同时修改：
- `fromJson`：接受 schemaVersion 1 和 2；拒绝未知版本；v1 缺少新增字段时按旧语义解读。
- `toJson`：按版本手工序列化全部新增字段；不得只扩 reader。禁止新增字段在持久化时被静默丢弃。
- v1 历史快照 round-trip 后语义不变；v2 Wait 快照完整 round-trip。
- `LegacyConditionNormalizer.normalizePlan` 重建 Edge 时透传 `routeKind` 及其他新增字段。

第一个实现提交必须包含 Codec 双向改动、v1/v2 round-trip 测试和 LegacyConditionNormalizer 字段透传测试。

### 4.3 GraphCoordinator WAIT 独立分支（H3）

GraphCoordinator.advance() 遇到 READY WAIT Token 时，必须进入独立分支调用 `registerGraphWait` 并结束该 Token 的本次推进。不得把 WAIT 仅加入 `controlNodeKeys` 后交给 `transitionFor(WAIT)` 返回 null；否则 READY Token 会永久滞留并在 RuntimeDispatchLoop 的自愈唤醒下形成空转热循环。

硬性不变量：任何被 GraphCoordinator 选中的 READY WAIT Token，单次 advance 后必须满足以下之一：成功注册并变为 WAITING；因 Run Gate 关闭而明确 DEFERRED；因并发状态变化而明确 STALE/NOT_FOUND。不得保持 READY 且无明确延迟原因。必须测试 WAIT-only Run 不空转、WAIT 注册失败可重试、TASK+WAIT 并行分支都能推进。

## 5. BPMN XML 与 Compiler fail-closed 规则（P1-C）

### 5.1 receiveTask 兼容规则

- 既有 receiveTask 带旧任务执行配置时继续编译为 TASK。
- 只有恰好一个 LabOS Wait 扩展、且不含任务执行配置时，才编译为 WAIT。
- 旧任务执行配置检查必须覆盖 XML 属性及 `CompilationInput.nodeMetadata()` 中的 `capabilityCode`、resource/protocol 等字段。
- Wait 扩展与任一任务执行配置同时存在时 fail-closed。
- 无 Wait 扩展且不满足旧 TASK 完整配置时 fail-closed。
- 带 Wait 扩展时 `methodParams` 必须为空；`stageCode` 仅作为非执行性元数据允许保留，不得隐式触发 TASK 语义。若现有模型无法证明 stageCode 纯元数据，则本版也拒绝 stageCode。
- namespace 固定 `urn:labos:workflow-runtime:v1`；correlationKey 仅支持非空静态 literal。

### 5.2 Timer Catch Event

仅允许 `timerEventDefinition` 出现在：
1. `intermediateCatchEvent`；或
2. 附着于 G5 Wait receiveTask 的 interrupting `boundaryEvent`。

挂在 startEvent、endEvent、普通 task 或其他节点上一律 fail-closed。Timer Catch Event 必须恰好一个 timerEventDefinition、恰好一个 timeDuration；不支持 timeDate/timeCycle/表达式。

Timer Catch Event：in >= 1、out == 1；唯一 outgoing flow 不得含 conditionExpression，也不得标记为 default。

### 5.3 Timeout Boundary Event

Boundary Event 必须：
- attachedToRef 指向显式 LabOS Wait receiveTask；
- 显式 `cancelActivity="true"`；
- in == 0、out == 1；
- 恰好一个 timerEventDefinition + timeDuration；
- 唯一 outgoing flow 无 conditionExpression、无 default 标记；
- 不生成独立 GraphPlan Node/Token，其唯一 outgoing flow 归一化为宿主 WAIT 的 TIMEOUT Edge。

Boundary Event 的 id 仍参与全图 BPMN 元素 ID 唯一性校验。归一化后的 TIMEOUT edge key 不得与宿主 NORMAL edge key 重名。NORMAL 与 TIMEOUT 不得指向同一目标节点（冗余且会抹去 outcome 区分，故 fail-closed）。每个 Wait 最多一个 Timeout Boundary。

### 5.4 Wait receiveTask 图约束

显式 Wait receiveTask 必须 in >= 1、out == 1；其唯一正常 outgoing flow 不得含 conditionExpression 或 default 标记。所有引用目标必须存在；所有 ID 全图唯一。未知扩展、多个 Wait 扩展、多个 Timer Definition、缺少 timeDuration 或混用不支持的事件定义均 fail-closed。

Duration 必须正数、非零、无溢出；初始安全上限为 365 天（该上限是 G5 V1 产品/安全约束，不是数据库既有约束）。Java 解析后以数值参数传 SQL；deadline 由 PostgreSQL `clock_timestamp() + interval` 计算，禁止使用应用机 `Instant.now()` 作为持久化 deadline。

## 6. Wait Inbox 接收、重放与 Timer 仲裁（P1-A/P1-B）

### 6.1 唯一消息契约

消息类型固定 `WORKFLOW_WAIT_SIGNAL`；必须提供 tenant_id、run_id、source、source_message_id、payload hash、correlationKey 和业务 payload。G5 V1 不支持 early-message buffering：Run RUNNING/OPEN 但不存在匹配 ACTIVE Wait 时，永久拒绝并写 INBOX_REJECTED；上游必须在 Wait 注册后发送。

### 6.2 接收语义：同步直达 + 暂停时持久化

`receiveWaitInbox` 采用明确的两种路径，不允许语义含混：

- **RUNNING/OPEN 且匹配 ACTIVE Wait**：在当前接收 Repository 事务内，锁 Run -> Token -> Activation -> Wait -> Inbox，完成 Inbox 幂等判定、Wait ACTIVE -> SATISFIED、Activation/Token 更新、successor 创建、WAIT_SATISFIED 事件及 Inbox -> PROCESSED。即同步直达，不等待下一轮轮询。
- **PAUSE_REQUESTED/PAUSING/PAUSED**：只持久化 Inbox 为 RECEIVED，返回 DEFERRED；不触碰 Wait/Activation/Token，不标为 REJECTED。Resume 后由 RuntimeDispatchLoop 持续重放。暂停态判定以 Run `status` 或 `control_gate` 任一进入暂停语义为准；两者短暂不一致时按暂停处理，不能因 Gate 仍为 OPEN 而同步推进 Wait。
- **Run 终态、STOP/FAILURE Drain、Gate 已关闭且非暂停语义**：永久拒绝，写 REJECTED + INBOX_REJECTED。
- **RUNNING/OPEN 但无匹配 ACTIVE Wait**：按 V1 不支持 early buffering，永久拒绝，写 REJECTED + INBOX_REJECTED。
- **payload JSON 畸形、缺 correlationKey、字段类型错误或业务 schema 校验永久失败**：同一处理事务内 REJECTED + INBOX_REJECTED；不得保持 RECEIVED/PROCESSING 并无限重试。

若同一 `(tenant_id, source, source_message_id)` 已存在但 payload hash 不同，按 G4-D 写 PROTOCOL_VIOLATION。该协议违规记录必须在独立、可提交的事务中正常提交；不得与 Wait 状态更新共用一个最终回滚的事务，也不得触碰 wait_registration。返回 PROTOCOL_VIOLATION。

### 6.3 持续重放与队头公平性（P1-A）

RuntimeDispatchLoop 必须持续消费暂停期间持久化的 Wait Inbox。候选查询必须 JOIN workflow_run 并排除暂停态 Run，避免暂停记录占满 LIMIT 首页：

- 只扫描 message_type=`WORKFLOW_WAIT_SIGNAL` 且 receipt_state IN (RECEIVED, PROCESSING)；
- 排除 status IN (PAUSE_REQUESTED, PAUSING, PAUSED) 或 control_gate=PAUSE 的 Run；
- RUNNING/OPEN 的可处理记录优先；STOP/FAILURE/终态 Run 的记录也必须进入清退路径，转为 REJECTED + INBOX_REJECTED，不得永久占配额；
- 按 received_at、tenant_id、inbox_id 稳定排序并 LIMIT；
- PROCESSING 是可恢复状态：单实例无租约；每轮按 Run 锁重新校验并可安全重放。任何永久错误必须转 REJECTED，不得形成 PROCESSING 活锁。

建议新增 partial index（是否落地以 EXPLAIN 与实际查询为准）：

```sql
CREATE INDEX inbox_wait_signal_pending_idx
  ON workflow_runtime.inbox_receipt (tenant_id, run_id, received_at)
  WHERE message_type = 'WORKFLOW_WAIT_SIGNAL'
    AND receipt_state IN ('RECEIVED', 'PROCESSING');
```

Timer 的 pending Inbox 检查也必须使用 tenant_id + run_id + message_type + receipt_state + correlationKey/received_at 限定，不得扫描无关 Inbox。

### 6.4 Timer 仲裁与有界 WAIT_FOR_INBOX

Timer 到期完成事务持有 Run 锁后读取一次 PostgreSQL `clock_timestamp()` 作为 decision_at，并重新校验 Wait ACTIVE、version 和 deadline。若存在同 Wait/correlationKey、payload 已通过基本解析、receipt_state 为 RECEIVED/PROCESSING 且 `received_at < deadline_at` 的 Inbox，则返回 WAIT_FOR_INBOX，不得先 EXPIRED。

重放遇到永久解析/校验错误必须在同一事务转 REJECTED + INBOX_REJECTED，因此 WAIT_FOR_INBOX 只会因可处理的合法 Inbox 暂时返回，不能被坏 payload 永久阻塞。deadline 精确相等归 Timeout：事件有效条件严格为 received_at < deadline_at，Timer 条件为 decision_at >= deadline_at。deadline_at NULL 表示无 Timeout，永不进入 due scan。

Run 行锁决定并发可见性：事件接收事务先拿 Run 锁并提交后，Timer 才能检查到 Inbox；若 Timer 先取得 Run 锁并提交 EXPIRED，后续事件只能被拒绝，不能复活 Wait。

## 7. 锁顺序与 Inbox 状态契约

全局锁顺序固定为 **Run -> Token -> Activation -> Wait -> Inbox**。不得先锁 Inbox 再锁 Run。外部调用方只提供 tenant_id、run_id、correlationKey、inbox identity；不得要求暴露内部 wait_id。

- 接收/重放均先锁 Run，再定位唯一 ACTIVE Wait，再按 Token -> Activation -> Wait -> Inbox 锁定关联记录。
- PAUSE 路径只锁 Run/Inbox 并持久化 RECEIVED；不得因 Gate 关闭而将合法事件 REJECTED。
- Wait 完成、Token/Activation 转换、successor 创建、Runtime Event、Inbox PROCESSED 在同一事务。
- 同一 Inbox 重复且 hash 相同：按 G4-D 幂等重放，不重复推进。
- 同一 Inbox identity 但 hash 冲突：独立提交 PROTOCOL_VIOLATION，不更新 Wait。
- 永久领域/协议错误：REJECTED + INBOX_REJECTED。
- Run 暂停：DEFERRED，保持 RECEIVED。
- 任何数据库异常：整体回滚；若 Inbox 已持久化为 RECEIVED/PROCESSING，后续可安全重放。

## 8. Timer 扫描与 RuntimeDispatchLoop（含 H8）

### 8.1 Due 查询

`findDueWaits(limit)` 只读、有界发现候选，SQL 使用 PostgreSQL 时间：

```sql
SELECT w.tenant_id, w.run_id, w.wait_id, w.deadline_at, w.version, w.wait_kind
  FROM workflow_runtime.wait_registration w
  JOIN workflow_runtime.workflow_run r
    ON r.tenant_id = w.tenant_id AND r.run_id = w.run_id
 WHERE w.wait_state = 'ACTIVE'
   AND w.deadline_at IS NOT NULL
   AND w.deadline_at <= clock_timestamp()
   AND r.status = 'RUNNING'
   AND r.control_gate = 'OPEN'
 ORDER BY w.deadline_at, w.tenant_id, w.run_id, w.wait_id
 LIMIT :limit;
```

候选发现不是抢占；完成事务内必须重新校验 Wait 状态/version、Run 状态/Gate、deadline 和 pending eligible Inbox。V1 单实例不使用 SKIP LOCKED。复用既有 wait_due_idx；新增索引须由 EXPLAIN 证明必要。

### 8.2 周期顺序与 Gate

在 Startup Recovery 完成且 SchedulingGate 开启后，每周期有界执行：
1. Graph/control-token wakeup；
2. Wait Inbox pending replay；
3. due Wait scan + Repository completion；
4. Task dispatch；
5. Worker/Device Outbox；
6. STOP/FAILURE/PAUSE Drain progression。

每个步骤均遵守现有 stopped/gate 检查模式；Gate 关闭时不得处理 due Wait 或完成 Inbox。单条失败不能阻断整批。

### 8.3 READY 控制 Token 唤醒

派发结果必须暴露 `hasReadyControlTokens`，或提供等价的有界查询。只要存在 READY 控制 Token（包括 WAIT），即使本周期已派发 TASK 或发生 capacity defer，也必须唤醒 GraphCoordinator。Run 发现查询必须覆盖 READY 控制 Token Run，不能只查 READY TASK Run。

WAIT Token 在 advance 中必须走独立注册分支（见 §4.3），不能只依赖“无 Task 派发时唤醒”。必须测试 Parallel Split -> (TASK branch + WAIT branch)：Task 已派发或 capacity defer 时，Wait 仍注册；WAIT-only Run 不得每周期空转。

## 9. PAUSE / STOP / FAILURE Drain（P1-E）

### 9.1 PAUSE 精确放行

只有同时满足以下条件的记录才是 PAUSE 安全挂起点：
1. ACTIVE wait_registration；
2. 关联 Activation 为 WAITING 且 current_attempt_id IS NULL；
3. 关联 Token 为 WAITING。

`tryFinalizeDrain(PAUSED)` 仅从 ACTIVE Wait unsafe 计数中排除上述纯图 Wait；仅从 WAITING Token unsafe 计数中排除与上述 Wait 一一对应的 Token。task-backed Wait、无 ACTIVE Wait 的孤立 WAITING Token、三方状态不一致记录仍计入 unsafe。不得放行所有 WAITING Token。G4 对 split/join/branch/loop/READY Token 的既有 PAUSE 行为保持不变。

### 9.2 STOP / FAILURE 清理作用域与幂等事件

STOP 与 FAILURE 共用清理路径，但目标 Run 状态仍分别是 STOPPED 与 FAILED。清理必须在既有 `invalidateStopContinuations` 事务中幂等完成：

- READY Token -> INVALIDATED（保留既有行为）；
- 仅对**纯图 Wait**（关联 Activation.current_attempt_id IS NULL）的 ACTIVE Wait -> CANCELLED；
- 对应 WAITING Activation -> CANCELLED；
- 对应 WAITING Token -> INVALIDATED；
- 不得误伤 task-backed Wait 或仍关联在途 Worker/设备 Attempt 的 Activation/Token；
- 保留现有 branch/join/split/loop/retry 清理。

新增 WAIT_CANCELLED 事件只能对本次真正从 ACTIVE 转为 CANCELLED 的 Wait 写一次，使用条件 UPDATE ... WHERE wait_state='ACTIVE' RETURNING wait_id；仅对 RETURNING 的行追加事件。RuntimeDispatchLoop 每轮重复调用清理入口不得重复写事件。

暂停期间暂存的同 Run Wait Inbox 必须在 STOP/FAILURE 后收口：可在清理事务内将 RECEIVED/PROCESSING 的 WORKFLOW_WAIT_SIGNAL 转 REJECTED + INBOX_REJECTED；或由非暂停终态清退查询在下一周期完成。无论选哪种，Run Drain 完成前后都不得留下永久 RECEIVED/PROCESSING。若选择下一周期清退，Drain 完成谓词必须明确等待这些记录归零。

### 9.3 Drain 验收

- PAUSE 纯图 Wait：Wait ACTIVE、Activation WAITING/null Attempt、Token WAITING，Run 可 PAUSED。
- PAUSE task-backed Wait / orphan WAITING Token / 三方不一致：仍阻止 PAUSED。
- PAUSE 期间 Timer 到期不推进；Resume 后按 Inbox 仲裁再推进 Timer。
- STOP 与 FAILURE：纯图 Wait、Activation、Token 全部终态；task-backed 在途 Attempt 不被误取消；重复执行不重复写 WAIT_CANCELLED。
- STOP 最终 STOPPED；FAILURE 最终 FAILED。
- STOP/FAILURE Drain 收敛时没有残留 Wait Inbox RECEIVED/PROCESSING。

## 10. 并行拓扑约束（P1-D）

G5 V1 禁止带 TIMEOUT Edge 的 WAIT 位于任一匹配并行/包容网关分支区域内，覆盖：
- SPLIT_PARALLEL 到其匹配 JOIN_PARALLEL；
- SPLIT_INCLUSIVE 到其匹配 JOIN_INCLUSIVE。

Compiler 必须在编译期进行结构化可达性判定：从任一 SPLIT_PARALLEL/SPLIT_INCLUSIVE 出发，在不经过其匹配 JOIN 的路径可达集合中，若存在带 TIMEOUT Edge 的 WAIT，则 fail-closed。不得只检查 Parallel，不得只依赖人工命名或简单邻接。普通无 Timeout 的 Message Wait / Timer Wait 可以位于上述区域。

未来开放并行 Timeout 前，必须证明 EVENT 与 TIMEOUT 两条路径都抵达同一匹配 JOIN，且不会重复到达、绕过 Join 或留下 split/branch/join activation。

## 11. 索引、唯一性与事件词表

### 11.1 Active correlation key

G5 V1 采用同 tenant + run 同一时刻一个 ACTIVE Wait 对应一个 correlationKey 的单消费者语义。新增 partial unique index 与既有 `wait_one_active_per_activation_uq` **叠加**：
- 既有索引保证每个 Activation 最多一个 ACTIVE Wait；
- 新索引保证每个 Run/correlationKey 最多一个 ACTIVE Wait。

迁移前检查重复 ACTIVE correlationKey；发现冲突必须 fail-closed，不自动取消/合并记录。

```sql
CREATE UNIQUE INDEX wait_active_correlation_uq
  ON workflow_runtime.wait_registration (tenant_id, run_id, correlation_key)
  WHERE wait_state = 'ACTIVE' AND correlation_key IS NOT NULL;
```

### 11.2 Inbox 查询索引

可评估 `inbox_wait_signal_pending_idx`（见 §6.3）；只有 EXPLAIN 与基准证明有收益后才新增。不得无测量地堆叠高度重叠索引。

### 11.3 Runtime Event 词表

只使用以下 Wait 事件名：
- WAIT_REGISTERED
- WAIT_SATISFIED
- WAIT_EXPIRED
- WAIT_CANCELLED

Timeout 分支使用既有 WAIT_EXPIRED；不新增 WAIT_TIMED_OUT、TIMER_FIRED 等同义事件。Timer/Message 类型由 wait_kind 与业务字段区分。runtime_event.event_type 是 text，因此不需要仅为事件名增加 DDL 枚举；但需同步更新事件常量、审计测试和文档词表。

## 12. 验收矩阵（冻结门槛）

### 12.1 Compiler / GraphPlan
- 旧 receiveTask TASK 配置与 LabOS Wait receiveTask 并存。
- nodeMetadata.capabilityCode / resource / protocol 等任务配置会阻止误编译 WAIT。
- Wait + methodParams fail-closed；stageCode 按 §5.1 规则。
- Timer Definition 只能出现在允许的 catch/boundary 容器；所有 in/out、condition/default、ID 唯一性约束生效。
- Boundary Event 不生成 Node/Token；NORMAL/TIMEOUT edge 唯一且目标不同。
- Parallel 与 Inclusive 匹配分支区域中的 Timeout WAIT 编译失败。
- GraphPlan v1/v2 双向 round-trip；旧 v1 快照语义不变；v2 新字段不丢失。
- LegacyConditionNormalizer 透传 routeKind。

### 12.2 Repository / PG17
- 纯图 Wait 注册无 node_attempt；旧 task-backed registerWait/closeWait 回归。
- continuation 新格式仍保留 nodeKey，旧 task-backed continuation 正常关闭。
- Run 非 RUNNING/OPEN 时不能完成 Wait；PAUSE 返回 DEFERRED。
- Event 与 Timer 并发恰好一个胜出、恰好一个 successor。
- deadline 前、等于 deadline、deadline 后边界正确。
- Inbox PROCESSED 与 Wait/Activation/Token/Successor/Event 同事务。
- payload hash 冲突的 PROTOCOL_VIOLATION 独立提交且不触碰 Wait。
- 畸形 payload 同事务 REJECTED，Timer 不会被永久 WAIT_FOR_INBOX 阻塞。
- 纯图 Wait Activation SUCCEEDED + NULL Attempt 有完成性断言；事务中途失败不残留部分状态。

### 12.3 Inbox / Dispatch / Recovery
- PAUSED Run 的 RECEIVED Inbox 不占满 pending LIMIT，不饿死 RUNNING Run。
- Resume 后暂停期间合法收到的事件可重放并满足 Wait。
- STOP/FAILURE 终态 Inbox 被清退，不遗留 RECEIVED/PROCESSING。
- PROCESSING 记录在进程重启后可安全重放。
- WAIT-only Run 不形成 READY Token 热循环；TASK+WAIT 并行分支不漏推进。
- Task dispatch/capacity defer 不阻止 READY 控制 Token wakeup。
- due Wait 在有界周期内完成；重启后从 DB 扫描恢复。
- Gate 关闭时不处理 Wait Inbox 或 due Wait。

### 12.4 Drain / 并发
- PAUSE 只放行三方关联纯图 Wait；task-backed/orphan/inconsistent 仍 unsafe。
- STOP/FAILURE 清理纯图 Wait 三方记录；不误伤 task-backed in-flight Attempt。
- 多次 Drain 周期不重复写 WAIT_CANCELLED。
- PAUSE 与 Wait 注册、STOP/FAILURE 与 Wait 注册竞争均由 Run 锁串行化。
- 同一 due Wait 重复扫描不重复创建 successor。
- STOP/FAILURE Drain 收敛时无残留 Wait Inbox pending receipt。

## 13. 实施顺序与提交切分

1. **Repository/DB 契约先行**：continuation 兼容、纯图 Wait register/complete、Inbox 单事务接收/重放、Timer 仲裁、Drain 谓词与清理、必要索引。
2. **GraphPlan/Compiler**：Kind/WaitSpec/routeKind、Codec 双向 v1/v2、LegacyConditionNormalizer 透传、XML fail-closed 校验、Inclusive/Parallel 拓扑拒绝。
3. **GraphCoordinator/DispatchLoop**：WAIT 独立注册分支、控制 Token 发现与唤醒、Inbox replay、due scan、Gate/Drain 集成。
4. **测试验收**：先 PG17 Repository，再 Compiler/Codec/Kernel，再 Island E2E；每个提交给出精确 SHA、文件差异、测试命令/输出和 CI run IDs。
5. RuntimeHost 保持禁用；不接真实设备；不合并 PR，除非用户明确批准。

## 14. 最终设计裁定

| 事项 | 冻结决定 |
|---|---|
| 纯图 Wait Attempt | 不创建 Attempt；独立 Repository API |
| task-backed Wait | 保留旧 registerWait/closeWait 语义 |
| continuation | 保留 nodeKey 成功路径兼容字段；新增 version/timeoutNodeKey |
| GraphPlan | WAIT + WaitSpec + routeKind；v1/v2 Codec 双向兼容 |
| WAIT 推进 | GraphCoordinator 独立分支调用 registerGraphWait |
| Wait Inbox | RUNNING/OPEN 命中 Wait 同步直达；PAUSE 持久化 RECEIVED 后重放 |
| Inbox 坏 payload | 永久错误同事务 REJECTED，不能无限 PROCESSING |
| payload hash 冲突 | PROTOCOL_VIOLATION 独立正常提交，不触碰 Wait |
| Timer 仲裁 | Run 锁 + DB clock + eligible Inbox 检查；等于 deadline 归 Timeout |
| pending Inbox fairness | 候选查询排除暂停 Run，终态记录进入清退 |
| PAUSE | 只放行三方关联的纯图 Wait；Timer 墙钟继续 |
| STOP/FAILURE | 只清理纯图 Wait 三方状态；事件 RETURNING 幂等 |
| Parallel Timeout | Parallel 与 Inclusive 匹配分支区域均 fail-closed |
| Event names | WAIT_REGISTERED / WAIT_SATISFIED / WAIT_EXPIRED / WAIT_CANCELLED |
| V1 部署 | 单进程单实例单 PostgreSQL，无分布式机制 |

**当前状态：冻结候选稿，尚未获得最终冻结批准。** 本次只修改设计文档；没有修改 Java、SQL 或生产配置，没有执行测试，没有启用 RuntimeHost，也没有合并 PR。
