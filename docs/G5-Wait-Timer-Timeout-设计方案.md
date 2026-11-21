# G5 Wait / Timer / Timeout 增量架构与契约设计

- 状态：设计稿，待架构评审；**不是实现完成声明**
- 审查基线：`1a1b3bd004d5cda6b4497c5b3c0b5d40156768c4`（G4-D 当前分支 HEAD）
- 数据库基线：`db/workflow_runtime/001_init.sql`
- 目标：在现有 Workflow Runtime 上增量实现 Wait、Timer、Timeout，不建立平行的等待/定时器子系统
- V1 约束：单实例、单进程、单 PostgreSQL；不引入分布式租约、fencing token、消息中间件或多实例协调

## 1. 结论摘要

G5 应复用现有 `wait_registration`、`node_activation`、`execution_token`、`inbox_receipt`、`runtime_event` 和 `RuntimeDispatchLoop`。

当前数据库模型已经允许 `node_activation.current_attempt_id IS NULL`，且 `wait_registration` 通过 `activation_id` 关联 Activation，而不是直接关联 Attempt。因此，**纯 Wait 不需要新建表，也不需要创建虚假的 node_attempt**。

但当前 Repository API 尚不支持纯 Wait：
- `registerWait(...)` 要求 Activation 已处于 RUNNING/RESERVED，再将 Activation 和 Token 置为 WAITING。
- `closeWait(..., oldAttemptId)` 要求提供旧 Attempt，并将 Attempt / Activation 标记为 ABANDONED_SAFE。
- `GraphCoordinator` 目前没有 WAIT GraphPlan Kind，也没有注册纯图 Wait 的路径。
- `RuntimeDispatchLoop` 没有扫描到期 Wait 的步骤。
- `tryFinalizeDrain()` 将 ACTIVE Wait 和 WAITING Token 都视为 Drain 未完成；当前 PAUSE 因此会被活跃 Wait 阻塞。
- STOP 会将 ACTIVE Wait Registration 改为 CANCELLED，但没有同步把其 WAITING Activation / Token 终结。

因此 G5 是**现有 Wait 生命周期、图编译和调度契约的扩展**，不是新增一套 Wait Engine。

## 2. 现有实现盘点

### 2.1 数据库：`db/workflow_runtime/001_init.sql`

| 对象 | 现状 | G5 结论 |
|---|---|---|
| `execution_token` | 已有 READY / WAITING / CONSUMED / INVALIDATED 等状态 | 复用，不新增 Token 类型 |
| `node_activation` | 已有 WAITING；`current_attempt_id` 可为空 | 复用；纯 Wait 创建 WAITING Activation，不创建 Attempt |
| `wait_registration` | 已有 ACTIVE / SATISFIED / EXPIRED / CANCELLED、wait_kind、correlation_key、deadline_at、version、continuation | G5 主记录，禁止新建 timer_registration / wait_task 表 |
| `inbox_receipt` | 已有 RECEIVED / PROCESSING / PROCESSED / REJECTED 等状态 | 外部事件仍走现有 Inbox 幂等入口 |
| `runtime_event` | 已有聚合事件与 causation/correlation 字段 | 记录 WAIT_REGISTERED / WAIT_SATISFIED / WAIT_TIMED_OUT / TIMER_FIRED 等事件 |
| `retry_registration` | 独立的 Retry 语义 | 不复用为 Timer；G6 Retry 保持独立 |

现有索引：
- `wait_due_idx(deadline_at) WHERE wait_state='ACTIVE'`
- `wait_one_active_per_activation_uq(tenant_id, activation_id) WHERE wait_state='ACTIVE'`

现有 `wait_registration` 的 `continuation` 为 JSONB，可承载选定的后继路由，但必须由已编译 GraphPlan 生成，不能接受任意调用方传入的未校验 nodeKey。

### 2.2 Repository

- `WorkflowRuntimeRepository.registerWait(...)`：约 L1742；Run -> Token -> Activation 锁定后插入 Wait，并将 Activation / Token 置为 WAITING。
- `WorkflowRuntimeRepository.closeWait(...)`：约 L1781；当前必须提供 `oldAttemptId`，只允许 CREATED / DISPATCHED / ACCEPTED Attempt 被安全放弃；随后消费旧 Token 并创建 successor READY Token。
- `WorkflowRuntimeRepository.tryFinalizeDrain(...)`：约 L3528；ACTIVE Wait、WAITING Token 都被计入 unsafe。
- `invalidateStopContinuations(...)`：约 L3656；取消 ACTIVE Wait Registration，但没有同步关闭 Wait Activation、使 WAITING Token INVALIDATED。

G5 应保留现有 task-backed Wait 行为，增加纯图 Wait 的明确 API，避免把 nullable Attempt 偷偷塞进原方法并改变其既有语义。

### 2.3 Compiler / GraphPlan

- `BpmnGraphCompiler` 当前 executable 子集包含 task、userTask、serviceTask、scriptTask、sendTask、receiveTask、customDevice、Start/End、Gateway。
- `intermediateCatchEvent`、`boundaryEvent` 和 BPMN eventDefinition 当前 fail-closed。
- `GraphPlan.Kind` 当前没有 WAIT。
- `GraphCoordinator.transitionFor()` 对 TASK / BUILTIN 返回 null；其余控制节点按现有图规则推进。completed Activation 路由只支持一个普通后继。

G5 必须显式扩展 GraphPlan 和 Compiler；不能把 Wait 伪装成 TASK / BUILTIN，否则 RuntimeDispatchLoop 会将它送进 Worker / Device dispatch 路径。

### 2.4 RuntimeDispatchLoop

`RuntimeDispatchLoop.runOneCycle()` 当前顺序包含：
1. 自愈等待 Graph advance 的 Run；
2. READY Token 的任务派发；
3. Worker Dispatch Outbox；
4. Device Command Outbox；
5. PAUSE / STOP Drain。

没有 due Wait 扫描。G5 应在每个受 SchedulingGate 保护的周期中加入有界 `findDueWaits` 查询，并通过 Repository 事务完成到期；不得使用 Thread.sleep 或仅驻留内存的 DelayQueue。

## 3. G5 范围与非目标

### 3.1 G5 包含

- 纯 Wait：不创建 Attempt、不占用 Worker。
- Timer：持久化的绝对到期时间，重启后可继续扫描。
- 外部事件 Wait：使用 Inbox + correlation key。
- Timeout：外部事件 Wait 的可选到期分支。
- Wait / Timer 的事务性注册、完成、取消、恢复和重复触发保护。
- PAUSE / STOP 与活跃 Wait 的明确语义。
- BPMN Compiler / GraphPlan 对 G5 节点的受限支持。

### 3.2 G5 不包含

- Retry / Backoff（G6，继续使用 retry_registration）。
- Error Boundary、Compensation、Saga。
- 非中断 Timeout、多个竞争事件订阅、事件广播。
- Cron、周期性 Timer、复杂日历、工作日/节假日计算。
- 任意表达式脚本、任意 BPMN Event Subprocess。
- 分布式调度、数据库租约、SKIP LOCKED 多实例消费者协议。
- 真实设备协议扩展。

## 4. BPMN / GraphPlan 表示建议

建议 G5 V1 只实现两类清晰、可审计的 Wait：

### 4.1 外部消息 Wait

使用 BPMN `receiveTask` 表示纯 Wait：
- 编译为新的 `GraphPlan.Kind.WAIT`，而不是 TASK。
- 必须有且只有一条正常 outgoing sequenceFlow。
- 必须配置稳定的 correlation key（G5 V1 建议先支持静态 literal；动态表达式延后，避免引入新的表达式求值安全面）。
- 不创建 Worker Attempt；GraphCoordinator 直接调用 Repository 注册 Wait。

### 4.2 Timer

使用只含一个 `timerEventDefinition` 的 `intermediateCatchEvent`：
- G5 V1 仅支持 `timeDuration`，格式为 ISO-8601 Duration，例如 `PT30S`、`PT5M`。
- 编译为 `GraphPlan.Kind.WAIT`，WaitSpec.kind=TIMER。
- 必须且只能有一条正常 outgoing sequenceFlow。
- 不支持 timeCycle；timeDate 可在后续评估。

### 4.3 Timeout 分支

使用 interrupting Timer Boundary Event 附着到 `receiveTask`：
- `boundaryEvent.attachedToRef` 必须指向一个 G5 receiveTask。
- 只接受一个 timerEventDefinition，且 `cancelActivity=true`（缺省语义按 BPMN interrupting 处理，但编译结果应明确记录）。
- receiveTask 正常 outgoing edge 是 EVENT/SUCCESS 路径。
- boundaryEvent 的唯一 outgoing edge 是 TIMEOUT 路径。
- 编译时把 Boundary Timer 归一化到被附着的 WAIT 节点，不为 Boundary Event 创建独立 Execution Token。
- 一个 receiveTask 最多一个 Timeout Boundary Event；G5 V1 不支持多个 Timer Boundary Event。

建议 GraphPlan schemaVersion 升至 2，并给 Node 增加明确的 WaitSpec（至少包含 waitKind、correlationKey、timeoutDuration）；Edge 增加明确的 routeKind（NORMAL / TIMEOUT），或采用等价且不含歧义的编码。GraphPlanCodec 必须继续读取 schemaVersion=1 的既有快照；旧 GraphPlan 的解释不得改变。

> 不建议仅靠 `continuation` JSON 猜测 success/timeout 的边含义。编译期必须验证并形成显式路由契约，运行时只从编译结果读取目标 nodeKey。

## 5. 纯 Wait 的 Token / Activation 生命周期

纯 Wait 应有 Activation，但不应有 Attempt：

| 阶段 | execution_token | node_activation | node_attempt | wait_registration |
|---|---|---|---|---|
| 到达 Wait 节点 | READY | 不存在 | 不存在 | 不存在 |
| 注册完成 | WAITING | WAITING，current_attempt_id=NULL | 不存在 | ACTIVE |
| 外部事件胜出 | 原 Token -> CONSUMED | SUCCEEDED | 不存在 | SATISFIED |
| Timer 到期胜出 | 原 Token -> CONSUMED | SUCCEEDED | 不存在 | EXPIRED |
| 创建后继 | 新 Token -> READY | 后继节点尚未激活 | 不存在 | 原记录终态保留 |
| STOP 取消 | INVALIDATED | CANCELLED | 不存在 | CANCELLED |

注册必须是一个 Repository 事务：
1. 锁 Run，确认 RUNNING / control_gate=OPEN。
2. 锁定目标 Token，确认 READY 且 node_key 与 GraphPlan WAIT 节点一致。
3. 创建一个 WAITING `node_activation`，current_attempt_id=NULL；activation_no 在 Run 锁保护下按 Token 历史最大值 + 1 生成。
4. 创建 ACTIVE `wait_registration`，写入 wait_kind、correlation_key、deadline_at 和已校验 continuation。
5. 将原 Token 从 READY 改为 WAITING。
6. 写 WAIT_REGISTERED Runtime Event。
7. 任一步失败必须整体回滚。

建议新增明确 API（命名可调整）：
- `registerGraphWait(tenantId, runId, tokenId, expectedNodeKey, WaitSpec)`
- `completeGraphWait(tenantId, runId, waitId, outcome, inboxId?)`
- `findDueWaits(now由DB确定, limit)` / tenant-scoped 版本

不要为纯 Wait 构造虚假的 node_attempt，也不要把 null Attempt 当作“安全放弃的 Attempt”。

完成时，在同一事务中：
1. 锁 Run -> Token -> Activation -> Wait（保持项目既有锁顺序；如果调整，必须全局统一并补并发测试）。
2. 确认 Run 仍是 RUNNING / OPEN；PAUSE 中的完成请求应返回 DEFERRED，不得将 Inbox 标成永久拒绝。
3. 对 Wait 做 ACTIVE -> SATISFIED / EXPIRED 的条件更新，version + 1；只有一个事务可以成功。
4. Activation WAITING -> SUCCEEDED。
5. 原 Token WAITING -> CONSUMED。
6. 按 outcome 从编译后的 continuation 选择唯一 successor nodeKey，创建一个 READY Token，继承 parent/split/branch/loop lineage。
7. 写对应 Runtime Event；若由 Inbox 触发，Inbox 状态更新必须与 Wait 完成处于同一事务。
8. 返回明确结果：COMPLETED / ALREADY_COMPLETED / DEFERRED / NOT_FOUND；不要用 boolean 混淆竞争失败和 Run 暂停。

对现有 task-backed `registerWait/closeWait` 保持兼容；建议内部抽取共享完成逻辑，而不是直接把旧参数改成可空。

## 6. 到期扫描契约：findDueWaits

### 6.1 查询语义

`findDueWaits(limit)` 是候选发现接口，不负责抢占或完成 Wait。建议返回：
- tenantId、runId、waitId
- deadlineAt、waitVersion
- waitKind
- 可用于诊断的 correlationKey（不必返回 continuation 全文）

SQL 语义：

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

- 时间判断以 PostgreSQL `clock_timestamp()` 为准，避免应用主机时钟漂移。
- 继续复用现有 `wait_due_idx`；查询必须有界 limit。
- 查询结果只是候选；每个候选再调用 Repository 完成事务，事务内重新校验 deadline、wait_state、version、Run status 和 control_gate。
- 不使用内存队列作为事实来源；重启后从数据库重新扫描即可。
- V1 单实例不需要 `FOR UPDATE SKIP LOCKED`。若未来并发扫描器增加，必须另行设计，不提前引入分布式协议。
- 扫描异常不能改变 Wait 状态；下一个周期可重试。

### 6.2 RuntimeDispatchLoop 集成

在 SchedulingGate 打开后执行有界 due Wait 扫描。建议顺序：
1. Graph advance 自愈；
2. due Wait 候选扫描与事务性完成；
3. READY Token 派发；
4. Worker Outbox；
5. Device Command Outbox；
6. Drain 推进。

完成 Wait 后创建的 READY Token 可以在同一周期后续派发，也可以下一周期派发；二者都可接受，但测试必须固定预期。扫描必须有 batchSize 上限，单个 Wait 失败不能阻断本周期其他候选。

## 7. 外部事件与 Timer 到期的 single-winner 规则

唯一事实来源是同一条 `wait_registration` 的状态，不使用进程内锁作为正确性保障。

建议规则：
- 外部事件路径与 Timer 路径都必须进入同一个 Repository 完成事务。
- 两者都先锁 Run，再锁 Wait 相关 Token / Activation / Wait，遵守统一锁顺序。
- 外部事件胜出：仅当 Wait 仍 ACTIVE 且未超过 deadline；同一事务将 Wait 置为 SATISFIED、Inbox 置为 PROCESSED、创建 success successor Token、追加 WAIT_SATISFIED。
- Timer 胜出：仅当 Wait 仍 ACTIVE 且 deadline_at <= DB clock；同一事务将 Wait 置为 EXPIRED、创建 timeout successor Token、追加 WAIT_TIMED_OUT。
- Timer-only Wait 到期时创建其唯一 successor，并追加 TIMER_FIRED。
- 对同一 Wait，只有 ACTIVE -> 终态的条件更新成功者可以推进 Token。后到者返回 ALREADY_COMPLETED，不创建 Token、不重复写业务推进事件。
- 同一 source_message_id 的重复 Inbox 仍由现有 Inbox 唯一约束和 payload hash 规则处理；不同 message_id 但相同 correlationKey 的重复事件，在 Wait 已终结后不得再次推进。
- G5 V1 建议定义“事件必须在 deadline 前完成数据库接收/处理的有效性检查”；deadline 到达后，超时路径具备资格。最终胜者以 PostgreSQL Run 锁串行化 + Wait ACTIVE 条件更新为准。边界时刻（deadline 精确相等）归 Timeout，即事件有效条件为 DB clock < deadline，Timer 条件为 DB clock >= deadline。
- 若未来要求“事件在 deadline 前已进入 Inbox，即使暂停或排队后才处理也必须优先于 Timeout”，需要增加 Inbox 与 Wait 的原子仲裁查询/索引；不应在 G5 V1 隐式承诺这一语义。

外部事件必须先落 Inbox，再关联 Wait。不得先关闭 Wait、再单独标记 Inbox PROCESSED，否则进程崩溃可能造成 Wait 已推进但 Inbox 仍重放；反向顺序也会造成 Inbox 已消费但 Wait 未推进。

## 8. PAUSE / STOP Drain 语义

### 8.1 PAUSE：保留 Wait，Timer 继续走墙钟，但暂停期间不推进

建议语义：
- PAUSE 不取消 ACTIVE Wait。
- PAUSE 不等待 ACTIVE Wait 结束；Wait 本身是持久化安全挂起点，不占 Worker。
- `wait_registration` 保持 ACTIVE，Activation 保持 WAITING，Token 保持 WAITING。
- Timer 的 deadline_at 使用绝对时间，不冻结、不平移。暂停期间到期只表示“已到期”，不执行路由。
- Run 处于 PAUSE_REQUESTED / PAUSING / PAUSED 或 control_gate=PAUSE 时，due scanner 不完成 Wait；外部 Inbox 事件可以先持久化，但处理返回 DEFERRED / 保持 RECEIVED，不得 REJECTED。
- Resume 后 Run 变成 RUNNING / OPEN，先处理可用的 Inbox 事件，再处理 due Timer；若二者竞争，遵循第 7 节 single-winner 规则。
- `tryFinalizeDrain(PAUSED)` 不应把 ACTIVE Wait、WAITING Token 作为 unsafe；但仍须等待实际 Worker Attempt、设备命令、资源租约、未交付 Outbox 等安全条件。

当前 `tryFinalizeDrain` 对 Wait 和 WAITING Token 的计数没有区分 PAUSE / STOP，这是必须修复的 P1 级 G5 集成点。不能只删除 Wait 计数而保留 WAITING Token 计数。

### 8.2 STOP：取消 Wait 并使等待 Token 失效

STOP 不保留未来续跑能力：
- ACTIVE wait_registration -> CANCELLED。
- WAITING Activation -> CANCELLED。
- WAITING Token -> INVALIDATED。
- 不创建 successor Token。
- 写 WAIT_CANCELLED / DRAIN 相关 Runtime Event（按项目事件规范）。
- 已经 SATISFIED / EXPIRED 的 Wait 保留终态，不重复取消。
- STOP 之后到达的外部事件不能重新打开 Wait 或 Run；按 Inbox/Run 终态规则记录为不可应用的终态结果。
- Timer 扫描只查询 RUNNING / OPEN Run，因此 STOP 中不会触发新的超时推进。

当前 `invalidateStopContinuations` 只取消 Wait Registration，没有同步取消 WAITING Activation / Token；G5 必须在同一事务补齐。否则 STOP 可能留下永久 WAITING Token，且 `tryFinalizeDrain(STOPPED)` 无法安全终结。

### 8.3 Drain 语义验收矩阵

| 场景 | Wait Registration | Activation | Token | Run 结果 |
|---|---|---|---|---|
| 正常 PAUSE，Wait 未到期 | ACTIVE | WAITING | WAITING | 可 PAUSED |
| PAUSE 期间 Timer 到期 | ACTIVE | WAITING | WAITING | 仍可 PAUSED；Resume 后超时推进 |
| PAUSE 期间收到外部事件 | ACTIVE，Inbox RECEIVED/DEFERRED | WAITING | WAITING | 仍可 PAUSED；Resume 后仲裁 |
| Resume 后事件先胜出 | SATISFIED | SUCCEEDED | 原 Token CONSUMED + successor READY | RUNNING |
| Resume 后 Timer 先胜出 | EXPIRED | SUCCEEDED | 原 Token CONSUMED + timeout successor READY | RUNNING |
| STOP 遇 ACTIVE Wait | CANCELLED | CANCELLED | INVALIDATED | 可继续 STOP Drain |
| STOP 后迟到事件 | 保持 CANCELLED | CANCELLED | INVALIDATED | 不得恢复 Run |

## 9. 最小增量 DDL 草案

**不新增表。** 现有 wait_registration 的核心列足以表达 G5。建议只考虑以下增量索引 / 约束；最终须根据实际事件关联字段和数据模型验证后再迁移。

### 9.1 correlation key 唯一性（建议）

若 G5 V1 采用单消费者 Wait 语义，同一 Run 同一时刻一个 correlationKey 只能有一个 ACTIVE Wait：

```sql
CREATE UNIQUE INDEX wait_active_correlation_uq
  ON workflow_runtime.wait_registration (tenant_id, run_id, correlation_key)
  WHERE wait_state = 'ACTIVE' AND correlation_key IS NOT NULL;
```

这是产品语义约束，不是纯性能索引。若业务确实需要同一事件广播到多个并行 Wait，则不要加此唯一索引，应另行设计显式 fan-out 语义；不能静默地让一个事件随机满足多个 Wait。

### 9.2 到期扫描索引

当前 `wait_due_idx(deadline_at) WHERE wait_state='ACTIVE'` 已支持核心查询，**第一版不需要重复创建同类索引**。如 EXPLAIN 证明 tenant/run 过滤成本过高，再评估：

```sql
CREATE INDEX wait_due_tenant_idx
  ON workflow_runtime.wait_registration (deadline_at, tenant_id, run_id, wait_id)
  WHERE wait_state = 'ACTIVE' AND deadline_at IS NOT NULL;
```

不要在未测量前同时保留两个高度重叠的 due 索引。

### 9.3 现有表字段

- 不新增 `timer_registration`。
- 不新增 `wait_execution`。
- 不新增 `scheduler_job`。
- 不新增内存队列持久化镜像。
- `continuation` JSONB 扩展为版本化对象，例如 `{"version":1,"successNodeKey":"...","timeoutNodeKey":"..."}`；写入前校验目标节点确实属于对应 GraphPlan 的合法边。
- `wait_kind` 使用明确枚举约束或 Java 常量；建议值：`MESSAGE`、`TIMER`、`MESSAGE_WITH_TIMEOUT`。是否增加 DB CHECK 应在兼容既有数据后确定。

若决定增加 `wait_active_correlation_uq`，迁移前必须检查现存 ACTIVE 重复 correlationKey；有重复数据时迁移应 fail closed，不得自动取消或合并 Wait。

## 10. Repository API 契约草案

建议 API 以业务结果类型而不是 boolean 暴露竞争结果：

| API | 职责 | 事务边界 |
|---|---|---|
| `registerGraphWait(...)` | READY 图 Token -> WAITING Token + WAITING Activation + ACTIVE Wait | 单事务 |
| `findDueWaits(limit)` | 只读发现 DB 时间已到期的 ACTIVE Wait | 只读 |
| `completeGraphWait(..., EVENT)` | Inbox + Wait SATISFIED + successor Token + Event | 单事务 |
| `completeGraphWait(..., TIMEOUT)` | Wait EXPIRED + timeout successor Token + Event | 单事务 |
| `cancelActiveWaitsForStop(...)` | ACTIVE Wait + WAITING Activation/Token 取消/失效 | 与 STOP continuation 清理同事务 |
| `resumePausedRun(...)` | 只恢复 Run Gate；Wait 仍由后续调度周期处理 | 现有 Resume 事务 |

建议完成结果枚举：`COMPLETED`、`ALREADY_COMPLETED`、`DEFERRED_RUN_NOT_OPEN`、`NOT_FOUND`、`STALE_VERSION`。具体命名可调整，但不能把“Run 暂停”错误地解释成“协议永久违规”。

## 11. 测试矩阵与验收标准

### 11.1 Compiler / GraphPlan

- receiveTask 编译为 WAIT，不生成 TASK，不要求 protocol/resource/capability。
- Timer Catch Event 只接受单一 timerEventDefinition + timeDuration。
- Boundary Timer 只允许附着到 receiveTask，且为 interrupting。
- 普通成功边和 TIMEOUT 边均唯一、目标存在、无重复。
- 不支持的 eventDefinition、non-interrupting boundary、timeCycle、多个 boundary timer 均 fail closed。
- GraphPlan schemaVersion=1 历史快照仍可读取，schemaVersion=2 WAIT 快照可往返序列化。

### 11.2 Repository / PG17

- 纯 Wait 注册不产生 node_attempt。
- 注册中任一步故障，Token / Activation / Wait / Event 全部回滚。
- 同一 Wait 的 EVENT 与 TIMEOUT 两事务并发，恰好一个完成、恰好一个 successor Token。
- 重复 Event、重复 Timer 扫描不产生第二个 successor。
- 旧 Attempt-backed Wait 既有测试不回归。
- 事件在 deadline 前、等于 deadline、deadline 后三种边界符合明确规则。
- tenant_id/run_id 不匹配不能关闭其他 Run 的 Wait。
- wait_version / activation_version / token_version 正确递增。

### 11.3 Runtime / Recovery

- Timer 到期后不需要重启即可在有界轮询周期内完成。
- 在注册后、扫描前、扫描后但完成事务前、完成事务提交后四个崩溃点重启，最终状态正确且无重复推进。
- RuntimeHost 启动恢复完成前 SchedulingGate 关闭，不处理 due Wait。
- due Wait 候选查询有界，单条失败不阻塞整批。
- Run PAUSED 时 Timer 不推进；Resume 后能处理已到期 Timer。
- STOP 能取消 Wait Activation / Token 并成功完成 Drain。
- 外部事件与 Timeout 竞争符合 single-winner 规则，Inbox 与 Wait 推进原子一致。

### 11.4 Drain / 并发

- PAUSE 遇活跃纯 Wait 可进入 PAUSED，Wait 记录保持 ACTIVE。
- PAUSE 遇到期 Timer 不会在 PAUSED 状态创建 successor Token。
- STOP 遇活跃 Wait 后，Wait/Activation/Token 三者全部终态化。
- PAUSE 与 Wait 注册竞争、STOP 与 Wait 注册竞争均以 Run 锁串行化。
- 两个 RuntimeDispatchLoop 周期重复扫描同一 due Wait 不会重复推进（单实例中以重复调用模拟）。

## 12. 风险与待决策项

| 项目 | 建议 | 状态 |
|---|---|---|
| correlationKey 是单消费者还是广播 | G5 V1 单消费者；同 Run ACTIVE 唯一 | 待产品确认 |
| 外部事件有效时间 | 以数据库接收/处理时间与 deadline 比较；deadline 精确相等归 Timeout | 待确认 |
| PAUSE 期间收到的 Inbox | 持久化但延迟处理；Resume 后再仲裁 | 建议 |
| PAUSE 是否冻结 Timer | 不冻结墙钟，只暂停推进 | 建议 |
| Timeout Boundary Event | 仅支持 interrupting Timer Boundary | 建议 |
| 动态 correlationKey 表达式 | G5 V1 先不支持，静态 literal | 建议 |
| absolute timeDate / cron / timeCycle | 不纳入 G5 V1 | 建议 |
| 旧 task-backed Wait | 保持现有 API 语义并做回归 | 必须 |
| WAIT Activation 的历史状态 | EVENT/TIMEOUT 完成均为 SUCCEEDED；Wait outcome 由 Wait state + Runtime Event 区分 | 建议 |

## 13. 实施顺序建议

1. **先冻结 GraphPlan 契约**：WAIT Kind、WaitSpec、routeKind、schemaVersion=2 和 schemaVersion=1 兼容。
2. **再实现 Repository 原子接口**：纯 Wait 注册、事件/Timeout single-winner、Stop 取消、PAUSE Drain 修正。
3. **再实现 GraphCoordinator**：WAIT Token 注册时挂起，不创建 Attempt。
4. **再实现 RuntimeDispatchLoop**：findDueWaits + bounded completion。
5. **最后接入 Inbox / Gateway**：外部事件只通过 Inbox 驱动 Wait，不绕过幂等与审计。
6. 每一步先加 PG17 Repository 测试，再加 Kernel / Island E2E；不启用 RuntimeHost、不接真实设备，直到整体验收完成。

## 14. 代码审查结论

**架构结论：有条件可行（设计 PASS WITH CONDITIONS）。**

通过项：
- 复用 wait_registration 是合理的；现有 DDL 无需平行 Wait/Timer 表。
- Activation 与 Attempt 已分离，纯 Wait 可通过 WAITING Activation + NULL Attempt 表达。
- 现有 Token WAITING / CONSUMED / INVALIDATED 足以表达挂起与终结。
- RuntimeDispatchLoop 是合适的单实例有界扫描入口。
- PostgreSQL Run 锁 + Wait ACTIVE 条件更新可作为单实例 single-winner 的最终正确性边界。

必须在进入生产实现前关闭：
1. 纯 Wait 的注册/完成 API 不得依赖 oldAttemptId。
2. GraphCoordinator / GraphPlan 必须显式识别 WAIT，禁止将其派发给 Worker。
3. PAUSE 必须允许保留 ACTIVE Wait / WAITING Token；STOP 必须同时终结 Wait Registration、Activation、Token。
4. Inbox 事件处理必须与 Wait 完成、后继 Token 创建处于同一事务。
5. Compiler 必须对 Timeout 路由做显式建模和 fail-closed 校验。
6. G4 当前 `closeWait` 的 Attempt-backed 行为必须保留回归测试。

本文件仅为增量设计方案。未修改 Java、SQL、GraphPlan、Compiler、Repository 或 RuntimeDispatchLoop 生产实现；未执行测试；未声称 G5 已实现。
