# LabOS 暂停、故障保持与人工干预状态模型

版本：2026-11-07 · 设计讨论稿（待实现与评审）
适用范围：ExperimentRun、Workflow Run、NodeExecution、DeviceCommand、Resource/Safety Domain

## 1. 设计结论

暂停不是同步布尔值，也不只是用户点击按钮。它是异步控制过程：系统先持久化暂停/保持意图，再通知执行器和设备在可证明的安全边界停止推进，最后根据节点、命令和资源的实际状态聚合出实验级状态。

统一使用 Pause/Hold 机制，记录触发来源、原因、作用范围、请求者/事件源、时间、关联设备/节点、恢复条件与证据。至少覆盖：
- 用户主动暂停：USER_REQUEST。
- 设备故障或报警：DEVICE_FAULT / DEVICE_ALARM。
- 安全联锁：SAFETY_INTERLOCK。
- 资源不可用或状态不确定：RESOURCE_UNAVAILABLE / RESOURCE_UNKNOWN。
- 执行异常需人工判断：EXECUTION_EXCEPTION / MANUAL_REVIEW。
- 维护、校准或质量问题：MAINTENANCE / QUALITY_HOLD。

关键安全语义：
1. PAUSE_REQUESTED 不等于设备已停止。
2. PAUSING 表示系统正在收敛到安全边界；当前命令可能仍在执行。
3. 只有活动节点、命令和受影响资源均达到可证明的安全/静止条件，实验才可成为 PAUSED。
4. 需要人工处理时，实验进入 WAITING_INTERVENTION；不能因操作员点击“继续”就跳过故障复核。
5. 命令或设备状态不确定时，必须保持 UNKNOWN/RECONCILING 或隔离态；不能为了 UI 显示暂停完成而伪造 PAUSED。
6. 软件暂停不能替代设备硬件急停、安全 PLC 或设备原生安全回路。

## 2. 建议的状态层级

### 2.1 ExperimentRun（实验级聚合状态）

| 状态 | 含义 | 是否允许调度新节点 |
|---|---|---|
| CREATED | 已创建，尚未运行 | 否 |
| RUNNING | 正常运行 | 是（受资源/依赖约束） |
| PAUSE_REQUESTED | 已登记暂停/保持意图，尚未完成通知与收敛 | 否 |
| PAUSING | 活动节点正在到达安全边界；可能仍有在途命令 | 否 |
| PAUSED | 所有受影响活动节点已安全暂停，且无未解决的不确定状态 | 否 |
| WAITING_INTERVENTION | 已停止自动推进，等待指定人工处理/确认 | 否 |
| RESUMING | 暂停条件已解除，正在做恢复前检查和节点恢复 | 否 |
| RECONCILIATION_REQUIRED | 命令、设备或物理资源状态不确定，必须先对账 | 否 |
| COMPLETED / FAILED / CANCELED | 正常完成 / 终止性失败 / 已取消并完成收敛 | 否 |

PAUSE_REQUESTED 与 PAUSING 建议分开，分别表达请求持久化和执行器收敛中。WAITING_INTERVENTION 与 PAUSED 不应合并：前者存在人工处置义务；后者满足安全暂停条件。RECONCILIATION_REQUIRED 是更严格的阻断态，不能误报为安全暂停。

实验状态是聚合状态，不是某个节点状态的简单镜像。建议同时保存 hold_state、hold_reason、hold_scope 等字段。

### 2.2 NodeExecution（节点级状态）

建议明确：
- RUNNING：节点正在执行。
- PAUSE_REQUESTED：已向节点发出暂停/保持请求。
- FINISHING_CURRENT_COMMAND：驱动不支持物理中止，允许当前命令运行至安全边界。
- STOP_REQUESTED：已向支持中止的设备发送停止请求，尚未确认。
- WAITING_DEVICE_CONFIRMATION：等待设备侧停止/空闲确认。
- PAUSED：节点已达到安全暂停点，保存 continuation 后可恢复。
- BLOCKED_BY_FAULT：因设备/资源故障而阻断。
- WAITING_OPERATOR：等待人工处理、复位或确认。
- UNKNOWN / RECONCILING：命令或物理动作状态不确定。
- READY_TO_RESUME：恢复前检查通过，等待执行器重新调度。
- COMPLETED / FAILED / CANCELED：结束状态。

STOP_REQUESTED、FINISHING_CURRENT_COMMAND、WAITING_DEVICE_CONFIRMATION 不能显示成“已暂停”。UI 应显示“暂停处理中”“等待设备确认”“当前动作完成后暂停”等真实进度。

### 2.3 DeviceCommand（命令级状态）

命令执行事实独立于节点和实验：
CREATED → DISPATCHED → ACKNOWLEDGED → RUNNING → SUCCEEDED/FAILED。
不确定状态：UNKNOWN → RECONCILING → SUCCEEDED / FAILED / MANUAL_INTERVENTION。

停止请求是独立控制动作/attempt，不应覆盖原命令事实。建议单独保存 abort_request_status / stop_request_id 与设备证据。STOP_REQUESTED 不能直接把原命令改成 ABORTED；只有设备协议确认及必要的状态查询/传感器证据满足后，才能记录停止结论。

## 3. 触发来源与原因模型

不要为每种故障创建独立状态机。统一建立 RunHold / PauseRequest 记录：
- hold_id、experiment_run_id、workflow_run_id：唯一标识并精确关联，不通过 workflowId 查询“最近一个实验”。
- source：USER / DEVICE / SAFETY_CONTROLLER / SCHEDULER / SYSTEM / OPERATOR。
- reason_code：USER_REQUEST、DEVICE_ALARM、DEVICE_FAULT、INTERLOCK、RESOURCE_UNAVAILABLE、RESOURCE_UNKNOWN、QUALITY_HOLD、MAINTENANCE、EXECUTION_EXCEPTION。
- scope_type / scope_id：NODE、DEVICE、RESOURCE、SAFETY_DOMAIN、EXPERIMENT。
- severity：INFO / WARNING / CRITICAL；安全等级应使用受控策略，不只依赖自由文本。
- status：REQUESTED / PROPAGATING / SETTLING / WAITING_INTERVENTION / CLEARED / REJECTED。过期不得自动解除安全保持。
- requested_by / source_event_id、created_at、acknowledged_at、cleared_at。
- operator_resolution、证据引用、校准/复位结果、审批人。
- resume_policy：AUTO_AFTER_SAFE_POINT（仅明确允许的低风险场景）、OPERATOR_CONFIRM、DUAL_APPROVAL、NO_AUTO_RESUME。

同一实验允许多个并发 Hold。解除一个 Hold 不得清除其他有效 Hold。只有所有阻断性 Hold 已解除、资源和命令满足恢复条件，才允许进入 RESUMING。

## 4. 状态转换与时序

### 4.1 用户主动暂停

1. API 校验权限与 run 版本。
2. 在一个 PostgreSQL 事务中：创建 PauseRequest；ExperimentRun 设为 PAUSE_REQUESTED；当前活动节点设为 PAUSE_REQUESTED；写审计事件和 Outbox。
3. Worker 收到事件后进入 PAUSING，不再领取新节点/新命令。
4. 对活动节点按能力处理：
   - 驱动不支持物理中止：进入 FINISHING_CURRENT_COMMAND，等待当前命令自然完成，再停在下一个安全任务边界。
   - 驱动支持停止：记录独立 stop request，进入 STOP_REQUESTED / WAITING_DEVICE_CONFIRMATION；等待设备 ACK、活动命令查询和所需安全证据。
   - 设备响应丢失或状态不确定：进入 UNKNOWN/RECONCILING，隔离资源；实验进入 RECONCILIATION_REQUIRED 或保持 PAUSING，不得进入可恢复的 PAUSED。
5. 所有活动节点均已安全暂停，且没有未解决的在途命令、未知设备状态或危险资源状态后，实验才进入 PAUSED。

### 4.2 设备报警/故障触发人工干预

1. 设备事件先做认证、来源校验、时间戳/序列号去重和设备-资源映射校验；不能信任任意客户端直接写实验状态。
2. 创建 DEVICE/SAFETY 来源的 Hold，阻止受影响设备接收新命令，并阻止依赖该资源的后续节点调度。
3. 按 scope 扩大影响范围：
   - 节点局部故障：阻断该节点及其后继依赖。
   - 设备/资源故障：隔离设备及占用该资源的节点。
   - 安全域/公共设施故障，或无法证明隔离边界：保守暂停整个实验，必要时暂停同一安全域内其他实验。
4. 已在执行的命令不会因 Hold 自动停止；必须走设备停止确认、自然到达安全边界或 UNKNOWN 对账。
5. 实验 RUNNING → PAUSE_REQUESTED → PAUSING；相关动作安全收敛后进入 WAITING_INTERVENTION。
6. 操作员处理现场问题，记录处理人、原因、维修/清洁/复位/校准证据；设备自检与安全联锁复位通过后，Hold 才可 CLEARED。
7. 实验进入 RESUMING，执行恢复前检查；通过后回到 RUNNING。任何命令/位置仍 UNKNOWN 时，转 RECONCILIATION_REQUIRED，不得恢复。

### 4.3 安全联锁

若硬件安全系统要求立即停机，应由设备安全 PLC/硬件急停回路执行。LabOS 负责接收事件、锁定资源、阻止新命令、记录证据和协调恢复。不能等待普通工作流线程处理完毕才触发硬件急停，也不能以软件数据库事务代替硬件安全功能。

## 5. 实验状态与节点状态：谁先更新？

建议：**先确立实验级 Hold 意图，同时在同一事务中标记受影响节点的暂停请求；不是先提交实验状态，再另起事务改节点，也不是先把节点标成 PAUSED 再更新实验。**

### 阶段 A：原子登记控制意图（一个数据库事务）
- 锁定/版本校验 ExperimentRun。
- 插入 PauseRequest / Hold。
- ExperimentRun → PAUSE_REQUESTED。
- 当前活动且受影响的 NodeExecution → PAUSE_REQUESTED。
- 写 RunEvent / NodeEvent / AuditEvent 与 Outbox。
- 一次提交。

实验级意图是逻辑因果起点；数据库提交必须原子化，避免外部看到实验仍 RUNNING 但节点已暂停，或实验 PAUSING 却没有节点收到暂停请求的半状态。

### 阶段 B：异步执行与节点事实收敛
Worker 消费 Outbox，向节点/设备发出控制动作。节点根据设备能力进入 FINISHING_CURRENT_COMMAND、STOP_REQUESTED、WAITING_DEVICE_CONFIRMATION、WAITING_OPERATOR 或 UNKNOWN。只有获得真实证据后，节点才进入 PAUSED / WAITING_OPERATOR；节点状态更新和事件在事务内提交。

### 阶段 C：聚合实验状态
由统一 ExperimentStateProjector/Aggregator 根据活动节点、有效 Hold、命令状态和资源安全状态计算实验状态：
- 有节点仍在收敛：PAUSING。
- 全部安全停住且存在未解除人工 Hold：WAITING_INTERVENTION。
- 全部安全停住、没有人工待办但存在有效用户暂停：PAUSED。
- 任一关键命令/设备状态不确定：RECONCILIATION_REQUIRED。
- 所有阻断解除且恢复检查通过：RESUMING → RUNNING。

聚合结果可存储为查询投影，但必须能从事件和事实重建。不能由任意节点直接写 ExperimentRun.status 覆盖其他节点或 Hold。

## 6. Resume 不是简单把 paused 改成 running

恢复请求必须验证：
1. 操作者权限；人工故障有已完成的 intervention record。
2. 所有阻断性 Hold 已 CLEARED；硬件联锁确已复位。
3. 所有在途命令已 query/reattach/reconcile；UNKNOWN 不允许跳过。
4. 设备健康、校准状态、资源 lease、板位物理观测和样本/耗材状态满足要求。
5. Protocol/ExecutionPlan 仍是启动时冻结版本。
6. 节点按 continuation token/checkpoint 决定继续、重新执行或人工处置；只有证明动作未发生且具备幂等条件时才能重试。
7. ExperimentRun → RESUMING；节点进入 READY_TO_RESUME，由单一 worker owner 重新获取执行权。
8. 节点满足运行条件、执行器确认接管后，ExperimentRun 才回到 RUNNING。

恢复失败时保留 Hold 或转 RECONCILIATION_REQUIRED，不回滚成看似正常的 RUNNING。

## 7. 作用范围与并行流程

不要默认任何设备报警都必须暂停全实验，也不能默认只暂停一个节点就足够。ExecutionPlan 应表达 DAG 依赖、节点资源/安全域、共享与互斥约束、故障传播范围、安全 checkpoint/pause boundary，以及允许独立分支继续运行的显式策略与验证证据。

默认原则：不能证明独立性时扩大暂停范围。只有被证明与故障设备、样本、环境和安全域无依赖的分支才可继续。安全联锁或公共设施故障可升级到跨实验/安全域暂停。

## 8. 并发、一致性与审计

- 暂停请求使用幂等键；重复报警/点击不得创建互相覆盖的控制动作。
- ExperimentRun 使用 version 乐观锁或行锁；转换校验合法前置状态。
- 同一事务写状态、事件和 Outbox；消息至少一次投递，消费者幂等。
- 每个 NodeExecution 只能有一个有效 worker owner；多实例需数据库 lease + fencing token，JVM-local guard 不够。
- Hold 是可叠加集合；清除必须按 hold_id，不能用一个 resume=true 清掉全部。
- 事件保存 source、correlation_id、causation_id、actor、device_id、firmware、method、command_id、occurred_at、received_at。
- 设备事件乱序/重复按序列号、命令 ID、版本与状态转换规则处理；旧事件不能把 PAUSED 改回 RUNNING。
- UI 分开展示实验状态、暂停原因/触发源、收敛进度、待人工操作、设备实际状态和最后确认时间。

## 9. 最小验收测试矩阵

| 场景 | 预期实验状态 | 预期节点/命令行为 |
|---|---|---|
| 用户暂停，设备命令不可中止 | PAUSING → PAUSED | 当前命令完成；安全边界暂停；无重跑 |
| 用户暂停，设备支持停止且 ACK | PAUSING → PAUSED | stop request 与原命令分开记录 |
| 已发停止请求但设备 ACK 未到 | PAUSING | WAITING_DEVICE_CONFIRMATION；不能显示 PAUSED |
| 停止响应丢失、设备可能仍运行 | RECONCILIATION_REQUIRED | 命令 UNKNOWN；资源隔离；拒绝 Resume |
| 设备报警，需要人工清理/复位 | PAUSING → WAITING_INTERVENTION | 相关节点 BLOCKED_BY_FAULT / WAITING_OPERATOR |
| 人工处理完成但联锁未复位 | WAITING_INTERVENTION | 不允许 Resume |
| 人工处理、联锁复位、自检通过 | RESUMING → RUNNING | 检查命令、位置、校准和资源后恢复 |
| 并行分支发生单设备故障 | 按故障传播策略 | 未证明独立则均暂停；证明独立才允许无关分支继续 |
| 两实验共享设备，一个设备报警 | 相关实验/安全域保持 | 共享资源禁止新命令，不影响无关设备 |
| 用户暂停与设备报警同时发生 | 多个 Hold 同时存在 | 清除设备 Hold 后仍保留用户 Hold |
| 重复报警、乱序恢复事件 | 状态不回退 | 幂等去重，旧事件不能清除新 Hold |
| Worker 崩溃后重启 | PAUSING / RECONCILIATION_REQUIRED | 从持久化 Hold、NodeEvent、Command Ledger 恢复 |

## 10. 分阶段实施建议

### P0：状态语义与防误恢复
1. 新增 ExperimentRun 状态 PAUSE_REQUESTED、PAUSING、WAITING_INTERVENTION、RESUMING、RECONCILIATION_REQUIRED；迁移旧 paused 的兼容映射。
2. 新增 PauseRequest/Hold 领域对象，先对齐持久化结构；正式表结构另做 migration review。
3. 暂停入口绑定精确 experiment_run_id / workflow_run_id，不再按 workflowId 反查最近实验。
4. 暂停请求事务内写实验意图、活动节点请求、事件和 Outbox。
5. Worker 处理暂停时不领取新节点；无物理 Abort 能力的驱动等待当前命令到安全边界。
6. Resume 只允许从 PAUSED 或已解除 Hold 的 WAITING_INTERVENTION 进入 RESUMING；拒绝 PAUSING、UNKNOWN、未清除 Hold 和 worker 仍持有执行权的 run。

### P1：设备报警与人工干预闭环
1. 定义设备报警事件契约、事件去重、告警等级和设备/资源/安全域映射。
2. 实现 DEVICE_FAULT / INTERLOCK Hold 与故障传播策略。
3. 建立 InterventionRecord：处理人、操作、证据、设备自检、联锁复位、审批。
4. UI 展示待人工处理队列、确认动作和恢复条件。
5. 将资源隔离与 Hold 生命周期绑定；不得因 TTL 过期释放物理安全隔离。

### P2：可靠聚合、恢复与 HIL
1. 建立事件驱动 ExperimentStateProjector，定期检查投影与事实一致。
2. 多实例 worker lease/fencing；测试事件乱序、重复、崩溃恢复。
3. 并行 DAG 的故障传播和安全域隔离。
4. HIL 覆盖报警发生在命令执行中、停止响应丢失、人工处理失败、联锁未复位、人工复位后设备仍 busy 等情况。

## 11. 明确非目标

- 不把 LabOS 软件暂停描述成硬件急停。
- 不承诺所有设备都能在任意时刻中止动作。
- 不在没有设备/传感器证据时把 UNKNOWN 自动改成 PAUSED、IDLE 或 FREE。
- 不在人工处理后无检查地自动恢复。
- 不预设最终数据库 schema，也不要求立即重写 RunStateMachine；先对齐领域状态、事件和验收行为，再做兼容迁移。


## 12. 与当前仓库实现的映射（基于 main 源码核对，2026-11-07）

本设计不是从零引入暂停系统。当前仓库已经有 PauseManager、RuntimeHold、RunControl、ManualWindow 和板位盘点流程。下一步应**收敛和扩展已有机制，不再平行新建第三套 Hold 表/状态机**。

### 12.1 已有能力

| 当前实现 | 已有语义 | 当前边界 |
|---|---|---|
| PauseManager | 旧 run 表上的 paused、pause_kind、pause_reason；Kind 有 paused_fault / waiting_human / paused_unknown | 主要服务旧 run 模型；pause() 立即写 paused 并随后释放部分资源 |
| RuntimeHold | runtime_hold 表；site / island / experiment 三级范围；fault / plate_unknown / human / manual 四类 | 现场/调度闸门，不代表设备动作已停止，也不是节点级暂停进度 |
| RunControl | experiment_run 的 pause / stop / resume；按作用域创建 Hold；将覆盖范围内 queued/running run 设为 paused | 暂停 API 立即回 paused；没有 PAUSE_REQUESTED/PAUSING 的中间事实 |
| ManualWindow | 人工处理窗口与决策动作 | 需核对它与 Hold 的清除、证据、恢复门禁是否在所有故障路径中一致 |
| GraphRunner / DriverStepExecutor | 在动作/节点边界检查暂停或 Hold，当前动作可继续完成 | 尚未把“正在收敛”“设备停止确认”“人工等待”完整表达为 NodeExecution 状态 |

### 12.2 当前已确认的具体差距

1. **状态过早标为 paused。** `RunControl.pause()` 先调用 `RuntimeHold.raise()`，再调用 `markCoveredPaused()`；后者把覆盖范围内的 experiment_run 从 queued/running 直接改为 paused。设备动作可能仍在执行，API 却立即返回 status=paused。需要改成 PAUSE_REQUESTED → PAUSING → 聚合确认后的 PAUSED/WAITING_INTERVENTION。
2. **实验与实验运行的更新不是一个事务。** `markCoveredPaused()` 先用一个数据库连接更新 experiment_run，再用另一个连接更新 experiment.status；RuntimeHold.raise() 也单独使用连接。中间失败/进程退出会留下 Hold、experiment_run、experiment 三者不一致。应由单一事务服务原子写入 Hold、受影响 run 的暂停意图、experiment 聚合意图、事件和 Outbox。
3. **runtime_hold 当前不支持同一 scope 的多个并发原因。** `pg_rebuild/schema/98_m95_run_hold.sql` 的唯一索引 uk_runtime_hold_active 限制同一 tenant/scope/scope_id 只能有一条活动 Hold；`RuntimeHold.raise()` 以 scope + scope_id 做 NOT EXISTS 幂等；`clear()` 按 scope + scope_id 清除。若同一岛同时发生设备故障与用户暂停，当前模型无法分别保留和清除原因。目标设计应改为 source_event_id/idempotency_key 级去重、hold_id 级清除，允许多个 Hold 叠加。
4. **Hold 查询失败是 fail-open。** `RuntimeHold.active()` 捕获 SQLException 后记录日志并返回空列表；`coveringFor()` / `anyActive()` 会因此把“数据库读失败”表现成“没有 Hold”。安全闸门必须 fail-closed：查询失败返回明确错误/UNKNOWN，阻止新派发和恢复，而不是返回空集合。
5. **两套暂停模型并存。** PauseManager 作用于旧 run；RuntimeHold/RunControl 作用于 experiment_run 和实验/岛级闸门。不能直接把两套字段合并或让一个入口只更新其中一套。先做入口调用图和数据模型映射，再建立统一服务/兼容适配器。
6. **故障自动恢复策略需要分级。** PauseManager 文档把 paused_fault 定义为心跳恢复后自动继续；RuntimeHold 的注释又规定扣留必须由人显式解除。设备“短暂离线且安全恢复”的故障可以配置自动恢复，但夹板、机械臂异常、温控超限、联锁、板位未知等需要现场确认的故障必须使用人工 Hold，不能仅凭心跳恢复解除。
7. **人工恢复与设备恢复不是同一件事。** 当前 RunControl.resume() 会解除覆盖该实验的最内层 Hold，并把 experiment_run 设为 queued；它没有在这个方法内验证完整的 InterventionRecord、设备自检、联锁复位、命令 UNKNOWN 对账和物理位置证据。需要将“清 Hold”与“准许恢复”分成有门禁的步骤。
8. **节点状态尚未成为暂停事实的权威来源。** 当前 PauseManager/RunControl 主要更新 run / experiment_run 状态；设计目标要求 NodeExecution 保存当前命令是否仍在执行、是否等待设备确认、是否在安全边界暂停或等待人工。

### 12.3 代码证据路径

- `labos-kernel/src/main/java/com/labos/kernel/workflow/PauseManager.java`：旧 run 级 pause_kind、pause() 直接写 paused、资源释放策略、resume()。
- `labos-kernel/src/main/java/com/labos/kernel/workflow/RuntimeHold.java`：作用域、Kind、raise/clear/active/coveringFor；active() 读失败返回空列表。
- `labos-kernel/src/main/java/com/labos/kernel/workflow/RunControl.java`：pause()、markCoveredPaused()、resume()；实验运行与 experiment 状态分开更新。
- `labos-kernel/src/main/java/com/labos/kernel/api/ExperimentApi.java`：POST /api/experiments/runs/{id}/pause|resume|stop 的入口与立即返回状态。
- `labos-kernel/src/main/java/com/labos/kernel/api/RunApi.java`：旧 run pause/resume、runtime holds 查询与人工窗口入口。
- `pg_rebuild/schema/94_m4_pause.sql`：旧 run 的 pause_kind 等字段。
- `pg_rebuild/schema/98_m95_run_hold.sql`：runtime_hold 表、唯一活动 Hold 索引、experiment_run 控制列。
- `labos-kernel/src/main/java/com/labos/kernel/driver/DriverStepExecutor.java`：设备步骤执行和动作边界；与节点暂停状态的整合点。

### 12.4 对前文目标设计的实施修订

- RunHold/PauseRequest 不应直接作为“再建一张同义表”的要求。优先扩展 runtime_hold；若需要独立 PauseRequest/InterventionRecord，必须有不同职责：Hold 表示持续生效的安全闸门，PauseRequest 表示一次异步控制过程，InterventionRecord 表示人工处理证据。
- 暂停事务建议先把 Hold 和实验级 PAUSE_REQUESTED 意图写入，再把受影响节点置 PAUSE_REQUESTED；三者及事件/Outbox 同事务提交。设备侧确认后才推进节点状态，实验级 PAUSED/WAITING_INTERVENTION 由聚合器计算。
- 先解决 fail-open 和跨连接非原子更新，再扩充状态枚举/字段。当前 experiment_run.status 是 varchar 语义；长状态如 WAITING_INTERVENTION / RECONCILIATION_REQUIRED 需要先核实列长度并通过 migration 扩容，或使用短 code + display label，不能直接假设写入可用。
- 不要直接删除 PauseManager。先梳理旧 run 与 experiment_run 的 API、前端和调度调用；建立统一 PauseCoordinator 后由旧入口适配，逐步迁移。
