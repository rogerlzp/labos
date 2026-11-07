# LabOS 暂停、Hold、设备取消与人工干预：外部实践比较及架构决策记录

版本：2026-11-07
状态：Research + ADR draft（研究结论已形成；实现前需结合现有代码和设备能力逐项验收）
关联设计：docs/architecture/LABOS_PAUSE_AND_INTERVENTION_STATE_MODEL_20261107.md
范围：实验编排、节点执行、设备命令、设备报警、人工处置、恢复和审计

---

## 0. 执行摘要

本次研究覆盖 ISA-88 / PackML、ROS 2 Actions、SiLA 2 Observable Commands、OPC UA Alarms & Conditions，以及 Temporal、Apache Airflow 等工作流系统。

建议 LabOS 不照搬任何单一框架，而采用分层组合：

1. **ISA-88 / PackML：** 区分控制命令、瞬态状态和稳定状态；使用 Holding/Pausing 等过渡状态，直到控制器报告 Held/Paused；区分 Hold、Pause、Stop、Abort。
2. **ROS 2 Actions：** 取消是请求、接受、收敛、最终结果的协议，不是客户端直接写 canceled。
3. **SiLA 2：** 长时设备命令拥有稳定 Execution UUID，支持查询/订阅状态、进度和结果；但核心 Observable Command 状态不是通用暂停/取消协议。
4. **OPC UA Alarms & Conditions：** 报警发生、操作员知悉、纠正措施完成是不同事实；支持 Active、Acked、Confirmed、EventId、Branch 和 Retain 等语义。
5. **Temporal / Airflow：** 暂停编排层通常意味着停止派发后续工作，不代表中止已经运行的任务；任务状态与 Workflow/DAG Run 状态分开；恢复需要保留历史和 checkpoint。
6. **LabOS 必须额外实现：** 设备级停止确认、资源/样本物理状态、命令对账、安全联锁和人工恢复证据。工作流系统的暂停不能证明实验设备已经物理静止。

核心建议采用四个正交事实模型：
- Run Control State：实验/节点执行控制进度。
- Hold Ledger：仍有效的阻断原因集合。
- Command Execution State：设备命令的真实生命周期。
- Alarm / Intervention State：报警确认、处置、复位与恢复证据。

UI 可聚合展示“暂停处理中”“已暂停”“等待人工处理”“状态待核实”，但不能让单一 status 字段承担所有事实。

## 1. 研究范围、方法与证据等级

本次以标准组织或项目官方文档为主：
- ISA-88 / PackML：参考公开行业指南和厂商实现文档；完整标准正文并非全部公开，本文不声称完成规范符合性认证。
- ROS 2：官方设计文档。
- SiLA 2：SiLA 官方规范 PDF。
- OPC UA：OPC Foundation Part 9 官方在线规范。
- Temporal / Apache Airflow：官方产品文档。

证据分为规范/官方定义、实现示例、LabOS 设计推论三类。本研究是架构借鉴，不构成设备安全认证、GxP/21 CFR Part 11 合规意见或设备厂商协议保证。每个设备驱动仍需逐项确认能力。

## 2. 外部实践比较

### 2.1 ISA-88 / PackML：区分命令、瞬态状态和稳定状态

**观察到的模式**

ISA-88/PackML 风格的状态模型将过程分为执行中的 acting/transient 状态和等待/静止的 wait/quiescent 状态。常见转换形式包括：

- RUNNING → HOLDING → HELD → RESTARTING → RUNNING
- RUNNING → PAUSING → PAUSED → RUNNING
- RUNNING → STOPPING → STOPPED
- RUNNING → ABORTING → ABORTED

核心思想是：操作员发出 Hold/Pause/Stop/Abort 后，控制器先进入过渡状态，执行相应控制逻辑，之后再报告稳定状态。Hold/Pause 是可恢复的保持；Stop 是有序停止；Abort 是异常停止路径，实际语义由设备控制实现和风险评估决定。

一些实现明确规定 UI 只发送 command request，状态机组件等待控制器报告状态，不自行把设备状态改成目标状态。

**对 LabOS 的借鉴**
- PAUSE_REQUESTED / PAUSING / PAUSED 分别表达请求已登记、设备正在收敛、设备已到安全边界。
- 定义 Pause Boundary / Hold Boundary：哪些步骤可以安全停，哪些动作必须完成或执行设备专用 holding procedure。
- 将 Stop、Abort 与 Pause 分开，不允许 UI 上的“暂停”暗中调用语义不明的 abort。
- DeviceCapability 应声明 PAUSE_AT_BOUNDARY、CONTROLLED_STOP、ABORT、QUERY_EXECUTION_STATE 等能力和确认机制。
- 不支持设备级暂停的驱动，只能阻止下一条命令并等待当前动作到已定义安全边界，不能谎报物理停止。

**不应照搬**
- ISA-88/PackML 设备/批次状态模型不能取代 LabOS DAG、跨设备实验和样本/板位状态。
- 不假设所有设备均实现 Hold/Pause/Restart；设备适配层必须报告真实能力。
- ABORTED 不是 PAUSED；Abort 后可能需要设备复位/初始化和人工检查。

参考：
- Siemens, Batch processes with PCS 7 along ISA-88 models：https://cache.industry.siemens.com/dl/files/331/109784331/att_1052440/v2/109784331_Batch_processes_PCS_7_ISA_88_Docu_V1_en.pdf
- TIA Portal Automation Framework, States：https://docs.tia.siemens.cloud/r/en-us/v3.0/automation-framework/automation-framework-v3.0/3.-overview/3.3.-mode-and-state-model/3.3.3.-states
- OMAC Packaging Machinery Automation Guidelines：https://www.infoplc.net/files/documentacion/estandar_programacion/infoplc_net_opw_guidelines_v3_0.pdf

注：以上是公开实现资料和行业指南；正式符合性需核对适用版本的 ISA-88 / PackML 正文。

### 2.2 ROS 2 Actions：取消请求不等于取消完成

**官方状态语义**

ROS 2 Action Server 接受目标后，目标进入 ACCEPTED/EXECUTING；收到取消请求并接受后进入 CANCELING；Server 完成清理/停止后才以 CANCELED 终结。取消请求响应只说明哪些目标开始进入 CANCELING；最终是否 CANCELED 由状态和结果确认。Server 也可能拒绝取消请求。

**对 LabOS 的借鉴**
- DeviceCommand 的 stop/cancel 应是独立控制请求/attempt，保留原命令 ID。
- STOP_REQUESTED / CANCELING 与 CANCELED 必须分开。
- 收到设备 ACK 只代表接受请求，不一定代表运动已停止；仍需查询最终状态或取得设备/传感器证据。
- 设备可以拒绝取消、延迟取消或仅在安全点响应；驱动能力要明确声明。
- 每个取消请求记录 request ID、关联原 command ID、设备响应、最终结果和时间线。

**不应照搬**
ROS Action 是长时 RPC 交互模式，不替代 LabOS 持久化命令账本、跨服务 Outbox 或设备物理核验；也不能假设所有实验室设备都提供 ROS Action Server。

参考：
- ROS 2 Design, Actions：https://design.ros2.org/articles/actions.html
- ROS 2 Documentation, Understanding ROS 2 Actions：https://docs.ros.org/en/rolling/Concepts/Basic/About-Actions.html

### 2.3 SiLA 2：长时设备命令需要稳定 ID 和可观察状态

**官方规范中的 Observable Command 模式**
- Client 发起命令后，Server 返回 Command Execution UUID。
- Client 使用 UUID 查询或订阅 Command Execution Info。
- 状态包含 Waiting、Running、Finished Successfully、Finished With Error；可提供 progress、estimated remaining time 和 execution lifetime。
- 命令未结束时请求最终 Response，Server 应返回 Command Execution Not Finished Error。
- UUID 在定义的 lifetime 内有效；Client 可共享 UUID 给其他 Client 读取同一命令执行信息。

**对 LabOS 的借鉴**
- Device Gateway 将命令 ID 映射为稳定的设备侧 execution identifier（若设备协议支持）。
- 支持按 command ID 查询执行状态，而不是只靠一次 TCP response。
- 状态、进度、剩余时间、执行结果分开记录；进度不能作为完成证据。
- 设备断线重连后，优先通过 execution ID 查询/重新订阅，而不是重新下发命令。
- execution ID 有效期必须纳入 reconciliation 设计，不能等过期后才处理。

**重要限制**
SiLA 2 核心 Observable Command 状态不是通用 Pause/Cancel API。具体 Feature 可以定义设备能力，但 LabOS 仍需在 Capability 层表达 Pause/Stop/Abort/Query，不能因设备支持 SiLA 2 就认定它支持暂停或取消。

参考：
- SiLA 2 Part A, Core Specification v1.1：https://sila-standard.com/wp-content/uploads/2022/03/SiLA-2-Part-A-Overview-Concepts-and-Core-Specification-v1.1.pdf
- SiLA 2 Part B, Mapping Specification v1.1：https://sila-standard.com/wp-content/uploads/2022/03/SiLA-2-Part-B-Mapping-Specification-v1.1.pdf
- SiLA official downloads：https://sila-standard.com/downloads/

### 2.4 OPC UA Alarms & Conditions：知悉报警与完成处置是两件事

**官方规范中的关键区别**
- Alarm Condition 有 Active 状态，表示问题当前是否仍存在。
- Acknowledge 表示操作员已知悉某个报警事件。
- Confirm（可选）可表示操作员已经采取纠正措施。
- Condition/Event 有 EventId；需要保留历史状态时可有 BranchId，允许同一 Condition 多次激活而保留未确认的历史分支。
- Retain 用于让 Client 与 Server 同步仍需关注的 Condition。
- AlarmManager 重启后应恢复关键报警状态；无法确认 Acked/Confirmed 时应保守恢复为未确认。

**对 LabOS 的借鉴**
- 不用单个 acknowledged=true 代表设备故障已修复。
- 至少区分 ACTIVE（故障条件仍存在）、ACKNOWLEDGED（人已知悉）、RESOLVED（设备条件恢复正常）、INTERVENTION_CONFIRMED（人工处置已确认）、RESET_VERIFIED（设备/联锁复位证据通过）。
- 报警 event ID、源设备、发生时间、严重级别、原始 payload 和每次状态变化均需持久化。
- 报警恢复正常不能自动清除人工 Hold；报警已确认也不能自动清除故障。
- 处理重复、乱序、同一报警多次发生和系统重启后的报警恢复。

**不应照搬**
OPC UA Alarm Acknowledge/Confirm 是报警生命周期，不等同于实验 Run 的 Pause/Resume。报警抑制、Shelving 只影响告警呈现/管理，不能用作设备安全解除或资源释放。

参考：
- OPC Foundation, OPC 10000-9 §4.3：https://reference.opcfoundation.org/specs/OPC-10000-9/4.3
- OPC Foundation, OPC 10000-9 §4.8：https://reference.opcfoundation.org/specs/OPC-10000-9/4.8
- OPC Foundation, OPC 10000-9 §5：https://reference.opcfoundation.org/specs/OPC-10000-9/5
- OPC Foundation, OPC 10000-9 §5.8.2：https://reference.opcfoundation.org/specs/OPC-10000-9/5.8.2

### 2.5 Temporal：Workflow Pause 不会自动中止在途 Activity

**官方行为**
- Workflow Pause 阻止新的 Workflow Tasks / Activity Tasks 派发。
- 已经运行的 Activity attempt 不会自动中断；仍可能完成、失败、超时和 Heartbeat。
- Activity 完成事件可以被记录，但 Workflow 逻辑在 Unpause 前不会继续处理。
- Pause/Unpause 记入 Event History，包含操作者、原因和 request ID。
- 官方明确说明 Workflow Pause 没有“所有在途 Activity 已完成”的指标；Workflow Pause 与 Activity Pause 是不同控制。

**对 LabOS 的借鉴**
- 暂停编排层只负责阻止新节点、新命令和后续调度。
- 独立实现 quiescence/barrier，追踪每个 in-flight NodeExecution / DeviceCommand，直到完成、设备确认停止或转人工对账。
- 不能因为 Workflow Run 标记 Paused 就声称设备已暂停。
- 暂停请求记录操作者、原因、request ID 和审计事件。
- Resume 前处理暂停期间已到达的设备结果/报警事件，再恢复执行器。

**不应照搬**
Temporal 面向分布式业务工作流；它没有实验设备物理停止、板位确认或安全联锁语义。Workflow Pause 只停止编排进展，不承诺在途 Activity 全部静止。

参考：
- Temporal, Workflow Pause：https://docs.temporal.io/workflow-execution/workflow-pause
- Temporal, Cancellation and Termination：https://docs.temporal.io/workflow-execution/cancellation
- Temporal, Resumable Activity：https://docs.temporal.io/develop/go/workflows/activities#resumable-activities

### 2.6 Apache Airflow：DAG/Task 状态与暂停/重跑语义分离

**官方行为**
- Pause DAG 后，不再创建新的 DAG Runs；已运行任务可继续完成。
- Drain 停止创建新的 DAG Runs，并等待现有 runs 完成后再进入 paused。
- 清理/重跑 Task Instance 时，可以选择保留 task state store，以便从 checkpoint 恢复或重新连接外部作业；默认清理行为可能丢弃 task state。
- Task Instance History 保留重试/清理前的状态信息。

**对 LabOS 的借鉴**
- 区分“停止创建新实验/新节点”与“暂停某个已运行实验”。
- DAG Run 状态由任务/叶节点和依赖状态聚合，不应被任意节点直接覆盖。
- 恢复/重跑必须明确选择 checkpoint 恢复、reattach 外部设备作业，还是证明安全后重新执行。
- 保存每次 attempt 历史，不能覆盖旧 attempt 的执行事实。

**不应照搬**
Airflow Task 主要是软件任务；task state 不等于物理设备动作或样本位置状态。Airflow clear/retry 不能作为 LabOS 设备命令重试的安全依据。

参考：
- Apache Airflow, DAGs / Pausing and Draining：https://airflow.apache.org/docs/apache-airflow/stable/concepts/dags.html
- Apache Airflow, Dag Runs / Re-run Tasks / Task Instance History：https://airflow.apache.org/docs/apache-airflow/stable/dag-run.html

### 2.7 工业设备 Stop 与 Emergency Stop：安全路径独立

公开的 IEC 60204-1 概述资料区分 Stop Category 0（不受控停止）、Category 1（受控停止后移除执行器动力）和 Category 2（受控停止并保持动力）。Emergency Stop 应优先于其他普通控制功能，并按风险评估确定实现方式。

**对 LabOS 的借鉴**
- UI 的 Pause/Stop、设备控制器的 controlled stop、硬件 Emergency Stop 是不同层次。
- LabOS 不能把普通软件命令称为 E-Stop，也不能依赖数据库事务、消息队列或普通 worker 完成硬件安全动作。
- Safety PLC / 设备原生安全回路负责安全功能；LabOS 接收状态、封锁调度、记录证据和管理恢复。
- ABORT 不能被误认为符合某个安全等级的急停功能。

参考：
- IDEC, IEC 60204-1 overview：https://www.idec.com/en-us/solutions/safety/law/iso-iec/iec60204

## 3. 横向对比

| 系统/标准 | 控制对象 | 请求与完成分离 | 在途工作处理 | 人工确认/审计 | 最有价值的借鉴 |
|---|---|---|---|---|---|
| ISA-88 / PackML | 批次阶段/设备单元 | 是，acting → quiescent | 过渡状态执行设备专用逻辑 | 依实现 | Hold/Pause/Stop/Abort 语义 |
| ROS 2 Actions | 单个长时目标 | 是，CANCELING → CANCELED | Action Server 执行取消/清理 | Goal/result 状态 | 取消协议和最终确认 |
| SiLA 2 | 设备长时命令 | 是，UUID + Waiting/Running/Finished | 查询/订阅状态和结果 | 可共享 UUID | 可观察命令、稳定执行 ID |
| OPC UA A&C | 报警 Condition | 是，Active/Acked/Confirmed | 报警持续状态/历史分支 | Acknowledge/Confirm | 报警已知悉不等于已修复 |
| Temporal | Workflow / Activity | 是 | 在途 Activity 继续，暂停新派发 | Event History | 编排暂停不等于物理停止 |
| Airflow | DAG / Task Instance | 是，DAG 与 Task 分开 | 已运行任务可完成 | Task history | Drain、状态聚合、checkpoint/reattach |
| LabOS 目标 | Experiment / Node / Command / Resource | 必须 | 设备级安全边界收敛 + 对账 | Hold + Intervention Evidence | 组合以上模式并补足物理证据 |

## 4. 对此前状态设计的修订：采用正交维度

此前讨论把 WAITING_INTERVENTION 作为 ExperimentRun 的一个状态。外部实践比较后，建议进一步修订：**WAITING_INTERVENTION 更适合作为实验级可见的聚合状态/执行门禁结果，而底层不要把所有维度压进一个互斥 status 字段。**

原因：
- 一个实验可以同时存在用户 Pause、设备 Fault、Quality Hold。
- 设备故障处理完成后，用户 Pause 仍然有效。
- 一个 Hold 可以解除，其他 Hold 仍然阻断。
- 报警可以已确认但仍 Active；也可以已恢复但尚未人工确认。
- 一个节点可在等待设备确认，另一个节点已安全暂停。

建议分成四个正交事实模型：

### 4.1 Run Control State（实验/节点控制进度）

ExperimentRun 控制态建议：
CREATED、RUNNING、PAUSE_REQUESTED、PAUSING、PAUSED、RESUMING、STOPPING、STOPPED、ABORTING、ABORTED、COMPLETED、FAILED、CANCELED。

节点另有执行状态和安全点/continuation 信息。设备异常不一定改变 Run Control State 的唯一主状态；由 Hold 和节点状态表达原因与范围。

WAITING_INTERVENTION、RECONCILIATION_REQUIRED 可以作为聚合器输出的 execution_gate / attention_state，而不必强行与 PAUSED、PAUSING、FAILED 等竞争同一状态字段。

### 4.2 Hold Ledger（阻断原因集合）

每条 Hold 独立记录：
- hold_id
- source（USER / DEVICE / SAFETY_CONTROLLER / SCHEDULER / SYSTEM / OPERATOR）
- reason_code
- scope_type / scope_id
- source_event_id / idempotency_key
- severity
- active / cleared lifecycle
- clear actor / clear evidence
- resume_policy

多个 Hold 可以同时存在。Hold 清除必须按 hold_id；不能按 scope 一键清除所有原因。Hold 是逻辑执行闸门，不是物理设备状态。

### 4.3 Command Execution State（设备命令事实）

每个 command attempt 独立保存：
- CREATED / DISPATCHED / ACKNOWLEDGED / RUNNING
- SUCCEEDED / FAILED
- CANCEL_REQUESTED / CANCELING / CANCELED（仅在设备协议提供可信语义时使用）
- UNKNOWN / RECONCILING / MANUAL_INTERVENTION

停止请求与原始 command attempt 分开。设备 ACK 只表示请求已接受，不自动等于动作已停止。设备不支持取消时，状态应表达“等待当前动作完成/安全边界”，不能伪造 CANCELED。

### 4.4 Alarm / Intervention State（报警和人工处置事实）

报警状态和处置状态分开：
- Alarm: ACTIVE / INACTIVE；ACKNOWLEDGED 独立记录；需要时保留 event branches。
- Intervention: OPEN / ACKNOWLEDGED / IN_PROGRESS / RESOLVED / VERIFIED / REJECTED。
- Reset Evidence: 设备自检、联锁复位、校准状态、物理观测、操作员签名/时间等。

“报警已确认”不等于“故障已修复”；“人工点了处理完成”不等于“设备安全复位”。

## 5. 推荐的 LabOS 总体控制流程

### 5.1 用户主动暂停

1. 一个事务内创建 User Hold / PauseRequest，更新 Run Control State 为 PAUSE_REQUESTED，并给活动节点写暂停意图；同时写事件和 Outbox。
2. Worker 停止领取新节点和新命令。
3. 对每个 in-flight 命令，根据 Capability 选择：
   - 支持受控暂停：发送设备 Pause/Hold request，记录 request ID。
   - 只支持 Abort/Stop：按风险策略决定是否允许；不得把 Abort 伪装成 Pause。
   - 不支持中止：等待当前命令到安全边界；禁止下一个动作。
   - 状态查询失败/响应丢失：进入 UNKNOWN/RECONCILING，隔离资源。
4. 设备命令、节点状态和资源状态分别收敛。
5. 所有活动节点达到安全暂停点、无未解决未知命令、资源状态满足安全条件后，Run Control State 才进入 PAUSED。
6. 聚合器根据有效 Hold 和人工待办展示 PAUSED / WAITING_INTERVENTION / RECONCILIATION_REQUIRED 等 UI 状态。

### 5.2 设备故障/报警

1. 接收并验证设备报警，保存原始事件和稳定 EventId；去重但不丢弃不同时间发生的重复报警分支。
2. 依据 severity、设备、资源、样本、环境、安全域和 DAG 依赖创建 Device/Safety Hold。
3. 阻止受影响资源的新命令；已有动作按设备安全策略受控收敛。
4. 故障恢复后，Alarm Active 可变为 Inactive；但 Hold 仍保留，等待人工确认/处置或设备自检。
5. 人工处置记录与设备复位证据完整后，才允许清除对应 Hold。
6. 若仍有用户 Hold、其他故障 Hold 或 UNKNOWN 命令，不能恢复整个实验。

### 5.3 Resume

Resume 是控制命令，不是直接把 status 写回 RUNNING：
- 所有适用 Hold 均已清除或被明确豁免；
- 设备/安全联锁/校准/资源/板位状态通过检查；
- 所有命令完成 query/reattach/reconcile；
- Protocol Snapshot 和 ExecutionPlan 版本未改变；
- 节点 continuation/checkpoint 已验证，不会重放已产生物理副作用的动作；
- Worker ownership / fencing token 重新确认；
- 进入 RESUMING，节点逐个 READY_TO_RESUME，执行器接管后才 RUNNING。

## 6. 设备 Capability 契约

建议 DeviceCapability 逐项声明：
- supports_pause_request
- supports_hold_at_safe_point
- supports_controlled_stop
- supports_abort
- supports_command_status_query
- supports_command_subscription
- supports_execution_id
- supports_idempotency_key
- supports_position_feedback
- supports_safety_state_feedback
- safe_pause_boundary_definition
- stop_ack_semantics
- stop_completion_evidence
- recovery_policy

能力取值不能只有 true/false，至少区分：
- NATIVE：设备协议原生支持且有最终状态确认。
- ADAPTER_EMULATED：由驱动/网关组合实现，有明确限制。
- BOUNDARY_ONLY：不能停止当前动作，只能阻止后续动作并等待边界。
- UNSUPPORTED：不支持。
- UNKNOWN：未验证；按最保守策略处理。

设备不支持 Pause 时，不允许通过本地取消 Future 或线程中断，推断设备动作停止。

## 7. 状态投影与事务边界

建议写入事实与派生状态分开：
- 命令、报警、Hold、节点事件是事实来源。
- ExperimentRun 聚合状态是投影，可重建。
- 所有控制意图、状态事件和 Outbox 在同一数据库事务提交。
- 设备通信不放在长事务里；先持久化 request，再异步发送。
- 消费者幂等；事件至少一次投递；按 sequence/version/correlation/causation 检查乱序。
- Hold 查询失败、数据库连接失败或状态无法判断时，execution gate 必须 fail-closed。
- 投影与底层事实不一致时，进入 reconciliation，不自动“修正”为更乐观状态。

UI 同时展示 Run Control State、Execution Gate、Active Holds、In-flight Commands、Device/Resource Evidence 和 Operator Actions。

## 8. 实施优先级

### Batch 0：安全语义与当前实现修复
1. RuntimeHold 读失败改为 fail-closed。
2. Hold、实验/运行控制意图、事件、Outbox 原子提交。
3. 暂停 API 返回 request accepted / PAUSE_REQUESTED，而不是谎报 paused。
4. 增加 PAUSING 与节点在途命令收敛跟踪。
5. Resume 入口禁止绕过 UNKNOWN / 活动 Hold / 未完成设备确认。

### Batch 1：报警与人工干预
1. 统一 Alarm Event contract 和去重。
2. Alarm Active/Acked/Resolved 与 Hold/Intervention 分离。
3. Hold 多原因并存，按 hold_id 清除。
4. InterventionRecord + reset evidence + gated resume。

### Batch 2：设备协议与能力适配
1. 定义 DeviceCapability contract。
2. 对现有 TCP Driver 增加 execution ID / query / cancellation capability 声明。
3. 评估 SiLA 2 Observable Command adapter。
4. 评估 OPC UA Alarms & Conditions / LADS adapter。
5. 对无法查询设备状态的设备定义人工 reconciliation 流程。

### Batch 3：聚合器、并行流程与 HIL
1. ExperimentStateProjector / ExecutionGate projector。
2. 并行 DAG 故障传播和安全域作用范围。
3. Worker lease / fencing / restart recovery。
4. HIL 覆盖停止响应丢失、设备拒绝取消、报警风暴、报警重复/乱序、联锁未复位、人工移板、恢复时旧命令仍运行等情况。

## 9. 验收测试

| 测试 | 故障/操作 | 必须满足 |
|---|---|---|
| P-01 | 用户发暂停，节点无在途命令 | 不再派发新节点；到达 PAUSED |
| P-02 | 用户发暂停，设备命令仍运行且不能中止 | PAUSING；等待动作结束/安全边界；不能提前 PAUSED |
| P-03 | 设备接受 stop request 但未完成停止 | CANCELING/STOP_REQUESTED；不能显示 CANCELED/PAUSED |
| P-04 | 设备拒绝 stop request | Hold 保留；报告拒绝；按策略继续等待或人工处置 |
| P-05 | stop response 丢失 | UNKNOWN/RECONCILING；资源隔离；不重发原物理命令 |
| P-06 | SiLA execution UUID 查询返回 Running | 保持在途；不认为完成 |
| P-07 | SiLA UUID 已过期但结果未知 | RECONCILIATION_REQUIRED；不能猜测结果 |
| P-08 | 报警 Active，操作员 Acknowledge | 仅标记已知悉；Hold 仍在 |
| P-09 | 报警 Inactive，但未完成 intervention/复位 | 不自动清 Hold |
| P-10 | 人工处理完成但安全联锁未复位 | 禁止 Resume |
| P-11 | 同一 scope 用户 Hold + 设备 Hold 并存 | 清除设备 Hold 后用户 Hold 仍阻断 |
| P-12 | 重复/乱序报警 Event | 幂等且保留不同报警分支，不错误清除新事件 |
| P-13 | Workflow 暂停时 Activity 在途 | Run 不派发新工作，但追踪在途工作直到收敛 |
| P-14 | Worker/AlarmManager 重启 | Hold、Alarm、Command 和未确认 Intervention 可恢复 |
| P-15 | Run 投影与节点/命令事实冲突 | 转 reconciliation，不自动设为 PAUSED/RUNNING |
| P-16 | 并行 DAG 某设备故障 | 仅在独立性已证明时继续无关分支，否则扩大暂停范围 |

## 10. 架构决策（ADR）

### ADR-PAUSE-001：请求态与完成态必须分离
**Decision：ACCEPT。** 所有 Pause/Stop/Abort 请求先记录请求状态，只有设备/节点事实收敛后才记录最终状态。

### ADR-PAUSE-002：采用四个正交事实模型
**Decision：ACCEPT。** Run Control State、Hold Ledger、Command Execution State、Alarm/Intervention State 分开存储；UI 状态由聚合器投影。

### ADR-PAUSE-003：WAITING_INTERVENTION 是执行门禁/聚合态，不作为唯一 Hold 记录
**Decision：ACCEPT。** 人工待办由 InterventionRecord 表达，阻断原因由 Hold Ledger 表达；Run Control State 保持自身生命周期语义。

### ADR-PAUSE-004：暂停不等于设备取消或急停
**Decision：ACCEPT。** 设备 Pause/Controlled Stop/Abort/Emergency Stop 能力分别声明，硬件安全功能独立。

### ADR-PAUSE-005：Hold 多原因并存，按 hold_id 精确清除
**Decision：ACCEPT。** 不使用 scope-wide clear 作为默认操作。

### ADR-PAUSE-006：设备命令必须可查询或有人工对账路径
**Decision：ACCEPT WITH DEVICE-SPECIFIC LIMITS。** 优先使用稳定 execution ID + status query；不支持查询的设备必须采用序列化、保守资源隔离和人工 reconciliation。

### ADR-PAUSE-007：先收敛已有暂停实现，不新建第三套子系统
**Decision：ACCEPT。** 以 RuntimeHold / RunControl / PauseManager / ManualWindow 现有能力为基础，建立 PauseCoordinator 和兼容适配层，逐步迁移。

## 11. 未决问题

1. 旧 run 与 experiment_run 的关系：是否一对一、父子或历史兼容？需要 API/DB/调用图证据。
2. RuntimeHold 的 scope 层级是否足够表达 device / resource / safety-domain / sample / experiment？
3. 设备报警从 Gateway、Driver、设备 PLC、外部 OPC UA Server 的可信来源与认证如何建立？
4. 每个现有设备的 Pause/Stop/Abort/Query/Position Feedback 能力矩阵是什么？
5. 当前节点执行是否具备 continuation/checkpoint；哪些动作有不可逆副作用？
6. Outbox、Event Store、run_node、run_command 能否作为事实来源，还是需要统一事件 schema？
7. 是否需要将实验暂停与跨实验安全域暂停分为两种 aggregate？
8. 对温控、孵育、搅拌等“不能简单停掉”的设备，安全 Hold 可能是维持设定值而非停止设备；需要设备/工艺专家定义安全边界。
9. 报警解除与人工复位由谁授权；哪些需要双人审批或电子签名？
10. 是否需要定义最大暂停时长、样本稳定性/温度暴露计时和超时升级策略？

## 12. 研究结论

最值得借鉴的不是某一个框架的状态名字，而是共同原则：
- 状态由实际执行者报告，控制界面只提出命令。
- 过渡状态显式表达，不能把“请求已发送”当成“动作已完成”。
- 在途工作与后续派发分开处理。
- 设备命令必须可观察、可追踪、可对账。
- 报警知悉、问题恢复、人工处置确认分开。
- 恢复必须基于保存的执行历史和 checkpoint，避免重放物理副作用。
- 设备安全回路独立于上层软件工作流。

因此，LabOS 下一步先完成现有 PauseManager / RuntimeHold / RunControl 调用链梳理、RuntimeHold fail-closed、暂停事务原子化设计和 DeviceCapability 能力盘点。这会决定后续状态机和数据库迁移的真实边界。


---

## 13. 实验室自动化产品实践补充：GBG、Thermo Fisher Momentum、汇像 iMagicOS

本节将研究从通用工作流/工业协议扩展到实验室自动化产品。证据边界：公开用户手册、官方帮助和厂商产品页能证明其公开描述的用户可见行为；不能据此推断其内部数据库、消息总线、事务协议或未公开的设备安全实现。

### 13.1 Biosero Green Button Go（GBG）

公开 GBG Scheduler 产品资料强调集中式实时监控、仪器状态、样本位置、系统消息、错误处理和模拟环境。公开用户手册还描述了 Program / Workcell Process / Plate Process / Instrument Process / Procedure 等层次，以及运行时错误处理、流程修改、死锁处理、板位流转和 crash recovery 等主题。

对 LabOS 的借鉴：
- 流程模型需要区分实验意图、Workcell Process、Plate/Instrument Process、Procedure 和底层设备命令；不要把一个业务节点直接等同于一次设备 RPC。
- Workcell 需要成为真实的一等运行与故障隔离对象，具备成员设备、共享资源、物理边界、服务依赖和安全域关联。
- 调度不只按 DAG 拓扑排序，还要判断过程是否 Ready、设备是否可用、板/样本是否可交接、资源是否释放。
- 运行界面应同时提供流程视图、设备状态、样本/板位置、消息/报警和时间线。
- 错误处理应有受控的 Retry / Skip / Modify / Stop / Operator intervention 等策略，但 LabOS 必须基于命令副作用、幂等性和物理证据决定是否允许，不能把“用户手册有 Retry”推导为所有设备命令均可安全重试。
- 模拟运行与真机运行必须明确区分；模拟成功不能作为 HIL 或设备能力验收证据。

不照搬：GBG 的产品层级和错误处理界面不等于 LabOS 已验证的内部执行内核。Workcell Process 等术语可借鉴，但最终实体应由现有 LabOS 数据模型和业务场景验证后确定。

### 13.2 Thermo Fisher Momentum Workflow Scheduling Software

Momentum 官方帮助文档公开说明：
- Pause 时，如果设备正在执行属于该 Run 的操作，设备会先完成当前操作；之后 Run 进入暂停状态。
- Attended 模式下，发生错误会暂停受影响的 Run 并等待操作员确认问题已处理；Unattended 模式尝试适用的自动恢复，但不支持自动恢复的设备会退回类似 Attended 的处理。
- 设备池只从当前可用设备中选择；已在使用、Offline 或仍有未处理错误的设备不可用。设备池支持不同选择策略和错误/离线跳过策略。
- Work Unit、Batch、Process/Experiment/Campaign 有明确组织方式；可在 Batch 中设置资源可用后执行、指定时间执行或相对另一个 Experiment 延迟执行等约束。
- 7.1+ 文档允许按 Work Unit、Batch 或 Batch 内未执行工作等粒度终止，但明确提示中止某些必要 Batch 可能导致设备无法解锁或后续流程错误。

对 LabOS 的借鉴：
- 将暂停语义定义为“停止派发后续工作 + 在途动作按设备能力收敛”，而非强制立即停止所有设备。
- 引入 attended/unattended 运行策略，但自动恢复必须是设备/错误/方法级 allowlist，不能仅由全局 unattended 开关授权。
- 引入设备池/能力池：调度选择设备前检查 online、health、active fault、calibration、resource occupancy、safety domain 和能力匹配。
- 支持 Batch / Work Unit 一类的运行组织和优先级/时间约束，但不能把这些实体机械照搬进现有模型；先确认是否需要作为独立 aggregate。
- 中止粒度可以细化到尚未执行的工作，但必须做依赖闭包分析，避免跳过“解锁设备、释放板位、恢复环境”等清理步骤。
- Dashboard 与工程师配置界面可分层：操作员聚焦运行、告警、人工待办；工程师聚焦流程、设备配置、诊断与调试。

不照搬：Momentum 文档中的“当前设备操作完成后暂停”是其产品行为描述，不代表所有 LabOS 设备都适用。对安全联锁、机械碰撞、温控失控等故障，必须执行设备/安全控制器定义的即时安全响应，不能等待普通工艺步骤完成。

### 13.3 汇像 iMagicOS

汇像官方产品页公开描述其 iMagicOS 中控引擎采用分布式架构，面向生命科学、临床诊断、样品检测等场景，提供仪器驱动库和新增驱动接口；官方产品中心及公开案例呈现中控、数据中心、样本/耗材、AGV、LIMS 对接和自动化工作站等整体方案。

对 LabOS 的借鉴：
- 把实验室作为由设备、机器人/搬运、样本、耗材、环境、安全、数据系统组成的系统，而不是只把仪器 API 串成流程。
- 设备驱动生态需要版本、能力声明、认证状态、仿真/HIL 结果和兼容矩阵；“有驱动”不等于“支持全部控制能力”。
- 数据中心、实验执行记录和样本追溯应形成统一关联，但数据采集与设备控制的可靠性边界要分开。
- 对 LIMS/ELN/外部系统定义稳定 API 和事件契约；外部系统不能绕过 LabOS 执行内核直接发高风险设备命令。
- AI Copilot 应调用受权限约束的领域服务，生成流程草案、解释异常和提出恢复建议；最终控制由确定性内核、Capability、Safety Gate 和人工审批约束。

证据限制：厂商宣传的驱动数量、分布式、高可用或端到端能力属于公开产品声明，不能证明具体协议、故障恢复语义、数据一致性或安全认证。需要产品演示、技术交流、现场验收或合同技术附件进一步确认。

### 13.4 产品实践横向结论

| 设计主题 | GBG | Momentum | iMagicOS | LabOS 应形成的设计 |
|---|---|---|---|---|
| 流程层次 | Workcell/Plate/Instrument Process | Process/Experiment/Batch/Work Unit | 图形化流程/功能岛 | 统一 ExecutionPlan + 可追溯业务层次 |
| 调度 | Workcell/设备/板流转 | Work Unit、设备池、资源约束 | 跨设备与自动化模块协同 | Ready 判定、设备池、资源与安全域约束 |
| 暂停 | 运行时错误与流程修改 | 在途操作完成后暂停 | 公开资料不足以确认底层语义 | 请求、收敛、设备确认、聚合状态 |
| 故障处理 | 错误处理、Modify、恢复 | Attended/Unattended、设备级恢复 | 中控与设备协同 | Fault policy + Hold + Intervention + evidence |
| 资源/样本 | 板位置、Workcell | 容器、设备池、Batch | 样本、耗材、AGV | 逻辑资源 + 物理观测 + 安全状态 |
| 可观测性 | Dashboard、消息、状态 | Dashboard、Work Unit 状态 | 中控与数据中心 | Run/Node/Command/Device/Sample 全链路时间线 |
| 模拟与验证 | Simulation | Simulation/Normal | 公开细节待核实 | 仿真、故障注入、HIL、真机验收分层 |

### 13.5 本次产品研究形成的架构原则

1. Workcell/Functional Island 必须是显式领域对象，不只是设备分组标签。
2. Workcell 既是调度边界，也是默认故障隔离边界；但 Safety Domain 可以跨 Workcell，发生安全事件时可扩大影响范围。
3. 实验/流程可跨 Workcell；因此一个 Workcell 故障可能影响多个 ExperimentRun，必须有跨实验的影响传播与聚合机制。
4. 设备池只可包含能力、校准、状态和安全条件满足的设备；故障设备从池中隔离，不能仅因网络恢复自动重新可用。
5. Pause/Stop/Abort 的粒度可以是 Run、Batch、Node 或 Workcell，但必须分别定义作用范围、在途动作策略、依赖闭包和清理动作。
6. 自动恢复权限由设备能力、错误代码、方法风险、样本稳定性和安全策略联合决定；ATTENDED/UNATTENDED 只是运行模式，不是安全豁免。
7. LabOS 必须同时管理数字状态与物理证据：样本/板位/机械臂位置不能仅凭软件命令成功回包更新为已确认。
8. 所有厂商功能都应转成 LabOS 的可执行验收场景，而不是直接转成需求清单。

## 14. 新增架构决策：Workcell 级故障隔离与策略化继续执行

### ADR-FAULT-SCOPE-001：设备故障默认隔离整个 Workcell / Functional Island

**Decision：ACCEPT AS DEFAULT POLICY; SAFETY OVERRIDE REQUIRED。**

用户业务决策：设备故障默认暂停/隔离整个设备工作单元或功能岛；其他独立节点是否继续，由故障等级、安全域和依赖策略决定。

解释：
- 故障设备所在 Workcell/Functional Island 进入 FAULT_HOLD / ISOLATED，不再接受新的普通执行命令。
- Workcell 内所有运行实例停止领取新节点；在途命令按各设备的安全策略收敛，不将 Hold 误作设备 Stop。
- Workcell 外的任务不自动受影响；但若共享 Safety Domain、环境、公用设施、样本/板位、机器人通道或其他依赖，则按传播策略扩大。
- 只有经策略证明独立的分支才允许继续；独立性未知时默认阻断并扩大范围。
- 严重安全事件可由 Safety Domain 覆盖 Workcell 边界，触发多个 Workcell 或全局安全域隔离。

### 14.1 `island` 的业务定义与当前范围（用户已确认）

**LabOS 当前的 `island` 是物理协同工作单元，对应本设计中的 Workcell。** 典型形态是在一个房间/相对封闭的自动化区域内布置多台实验设备，由一台或多台机械臂连接设备间的板位与交接点；实验板可由机械臂从设备 A 搬到设备 B，完成连续生物实验流程。

当前版本范围：
- 纳入实验设备、机械臂、机械臂可达的设备/板位/交接点，以及它们之间的板转运关系。
- 一个 island 可以有 **一台或多台机械臂**；不得把“每个 island 恰好一台机械臂”写成领域不变量。
- **AGV 暂不纳入当前实现范围**。未来若出现跨 island / 房间物流需求，再扩展 TransportExecutor / TransportResource 等抽象；当前不实现 AGV 调度、路径规划或车队管理。
- 不为当前 MVP 额外引入一个与 `island` 重复的 Functional Island 实体。只有未来确实存在跨物理 island 的纯逻辑工艺分组时，才引入该概念。

因此建议关系收敛为：
- **Island / Workcell：** 当前物理自动化单元，含设备、机械臂、板位/储位、交接点、内部空间/可达边界、运行模式与健康状态。
- **RobotArm：** 一等共享资源；记录所属 island、能力、可达设备/位置、工作空间/互斥区、运行状态和当前任务。多个机械臂之间是否能并行，取决于可达范围与共享工作空间冲突，不能只按数量推断。
- **SafetyDomain：** 独立于 island 的风险影响边界，可覆盖一个或多个 island；其边界由风险分析和现场安全设计确定。
- **Resource / Position：** 设备、机械臂、板位、储位、端口和交接点等可占用/共享资源。
- **ExecutionPlan Binding：** 节点/Procedure 显式绑定 island、设备能力/设备池、RobotArm 或可接受的机械臂池、Position 与 SafetyDomain 需求。
- **Dependency Edge：** 显式表达工艺先后、样本/板位、机械臂搬运、设备共享、工作空间冲突、环境和安全依赖。

### 14.2 当前源码与上述业务定义的差距（初步审计，需继续 A–F）

以下是对 `review/labos-industrial-architecture` 分支当前源码的观察，不代表真机拓扑已核实：

1. `ExperimentScheduler` 以 `experiment.island_id` 作为队列和内存锁键；`tryLockIsland()` 使用 JVM 内 `ConcurrentHashMap.putIfAbsent`，锁 TTL 为 2 小时。当前互斥粒度是整个 island，而不是具体机械臂/共享工作空间/设备/板位。
2. `ExperimentExecutor.runStep()` 调用 `workflows.withVars(runVars).execute(...)`，没有把实验的 `island_id` 传给 `WorkflowService.withIsland()`。因此调度器的 island 锁并不能证明流程内实际选择的设备都属于该 island。
3. `WorkflowService.withIsland()` 当前按 `resource_node.parent_id = islandId` 查直接子设备；过滤某个 capability 后若候选为空，会使用原始全局候选池（`keep.isEmpty() ? e.getValue() : keep`）。即使该过滤器被调用，这个回退也可能越过 island 边界。此处应改为显式失败/等待配置修复，而不是全局 fallback；但先补复现测试和调用链证据，不在本轮直接改代码。
4. `PositionLock.byIsland()` 通过资源树查询 island 直接子位置及设备下的位置；它解决的是位置占用视图，不等于机械臂可达性或运动工作空间模型。
5. `ExperimentFlowRunner.enrichWithCoords()` 目前按 `robot_point.code` 读取坐标；该查询本身没有展示机械臂 ID、所属 island 或可达关系。需要继续调查 robot_point 配置、机械臂驱动和岛屿画布保存结构。
6. `ExperimentScheduler` 的 island 锁是进程内存态，2 小时 TTL 到期会清锁；这不是跨 Kernel 实例的分布式互斥，也不能作为机械臂物理安全租约。

**阶段性结论：** 当前 island 的领域含义已由用户澄清，但代码中的岛锁、设备绑定和机械臂资源拓扑尚未闭合。不得据此宣称“实验动作已被限制在物理 island 内”，也不得因为 island 有多台机械臂就直接放开同岛并发。



### 14.2 故障影响传播算法（设计要求）

收到已认证的 Fault/Alarm 后：
1. 解析设备身份、Workcell、Functional Island、SafetyDomain、关联资源、当前命令和在途样本。
2. 根据设备/错误/方法的故障策略确定 severity、required_response、resume_policy。
3. 立即为故障 Workcell 建立持久化 Hold/Isolation，禁止领取新的普通命令。
4. 计算影响闭包：同 Workcell 活动节点 + 共享资源使用者 + DAG 后继依赖 + 物理/环境依赖 + SafetyDomain 关联对象。
5. 按每个受影响对象的策略生成控制动作：BLOCK_NEW_WORK、FINISH_TO_SAFE_BOUNDARY、CONTROLLED_STOP、SAFETY_CONTROLLER_ACTION、WAIT_OPERATOR、RECONCILE。
6. 其他 Workcell 的节点只有满足全部 continue predicates 才能继续；任一关键事实 UNKNOWN 时 fail-closed。
7. 保存传播决策快照：policy version、输入事实、命中规则、受影响对象、允许继续的对象、人工豁免及其审批。
8. 故障解除后先恢复设备/Workcell 健康和安全证据，再逐一恢复受影响节点；不能清除 Workcell Hold 就自动恢复所有跨域实验。

### 14.3 Continue Predicate（允许其他独立节点继续的必要条件）

一个节点只有全部满足以下条件才允许继续：
- 不使用故障 Workcell、设备、资源或共享设备池；
- 不依赖受影响节点的输出、样本、板位或物理搬运；
- 不共享受影响 SafetyDomain 或故障传播定义中的公共设施；
- 环境条件（温度、湿度、气体、电力、排风等）不受故障影响；
- 设备状态、资源状态和样本位置均非 UNKNOWN/QUARANTINED；
- 当前 ExecutionPlan 版本和依赖图完整，传播计算无缺失；
- 没有更高优先级的 site-wide / safety-domain Hold；
- 继续执行不会阻止故障 Workcell 的安全恢复或必要清理动作。

Continue Predicate 失败或无法计算时，节点不得继续。

### 14.4 初步故障范围策略矩阵（阈值必须由设备/工艺风险分析确定）

| 故障类别（示例） | 默认范围 | 在途动作 | Workcell 外任务 |
|---|---|---|---|
| 可恢复的单设备通信短断，设备状态可查询 | Workcell Hold，设备隔离 | 查询/reattach；不重放未知命令 | 仅 Continue Predicate 全通过者可继续 |
| 设备执行错误、夹板/机械臂动作异常 | Workcell Hold；关联资源隔离 | 设备专用安全动作或等待/人工对账 | 依赖或共享安全域任务阻断 |
| 样本/板位未知、搬运结果不确定 | Workcell + 相关样本/位置隔离 | 禁止后续搬运和处理 | 使用同一未知样本/板位的任务阻断 |
| 温控、孵育、气体、电力、排风等公共条件异常 | 相关 SafetyDomain Hold，可能跨 Workcell | 按工艺安全策略维持/切换条件 | 受影响安全域任务阻断 |
| 安全联锁/E-stop/人员风险 | SafetyDomain 或现场风险分析规定范围 | 由硬件安全控制器执行，不等待普通 worker | 安全域内禁止继续，直至授权复位 |
| 质量/污染/交叉污染风险 | 相关样本、设备、区域和工艺链 | 隔离样本与设备，保留追溯 | 相关批次/样本链阻断 |

### 14.5 作用范围聚合

建议采用“多 Hold + 影响投影”：
- Hold Ledger 保存原因事实和各自 scope。
- Impact Projection 保存一次故障传播的计算结果和版本。
- Execution Gate 对每个 NodeExecution / Resource 判断 BLOCKED / ALLOWED / UNKNOWN。
- ExperimentRun 的展示状态由其节点、Hold 和 Gate 聚合；同一 ExperimentRun 可部分受影响，不应因某个 Workcell Hold 就无差别地将所有节点都标为设备已暂停。
- Workcell Health 与 Workcell Execution Gate 分开：设备在线不代表无活动故障；Hold 清除也不代表健康检查完成。

### 14.6 必须新增的验收测试

| ID | 场景 | 必须满足 |
|---|---|---|
| FS-01 | Workcell A 内设备故障，A/B/C 三个实验均有节点在该岛 | 整个 Workcell 禁止新普通命令；所有在途动作按各自能力收敛 |
| FS-02 | Workcell A 故障，Workcell B 无依赖 | B 只有 Continue Predicate 全通过才继续 |
| FS-03 | B 与 A 共享机械臂/储位/设备池 | B 的相关节点阻断，不能因设备名不同而误判独立 |
| FS-04 | A 故障影响跨岛样本搬运或板位 | 所有依赖该样本/位置的节点阻断 |
| FS-05 | 两 Workcell 共享温控/排风/电力安全域，公共设施异常 | 按 SafetyDomain 扩大 Hold |
| FS-06 | Workcell 故障传播计算缺少依赖边/资源映射 | UNKNOWN，默认扩大阻断，不放行 |
| FS-07 | 设备报警恢复但 Workcell 人工 Hold 未清 | Workcell 仍隔离，不接受新命令 |
| FS-08 | Workcell Hold 清除，但某实验另有 User Hold | 该实验仍不得恢复 |
| FS-09 | 允许无关节点继续，但其完成事件依赖故障岛数据 | 重新计算依赖，不能推进下游节点 |
| FS-10 | SafetyDomain E-stop 覆盖多个 Workcell | 软件记录和调度隔离跟随安全控制器状态；不得代替硬件动作 |
| FS-11 | 故障时有设备命令状态 UNKNOWN | 对应资源隔离，禁止 Continue Predicate 通过 |
| FS-12 | 故障传播策略版本在运行中更新 | 已有传播决策快照不可被静默改写；变更需重新评估并审计 |

## 15. 公开参考资料

### GBG / Biosero
- Green Button Go Scheduler official product page: https://biosero.com/products/green-button-go-scheduler/
- Green Button Go User Guide (public PDF mirror): https://www.moleculardevices.com/sites/default/files/en/assets/user-guide/dd/img/green-button-go-2015-user-manual.pdf

### Thermo Fisher Momentum
- Pausing, Resuming, and Aborting Work: https://apps.thermofisher.com/apps/lahr/LA_Online_Help_Resource/en-us/Content/Topics/Software/Momentum/Work%20Units/MMTM%20pausing%2C%20resuming%2C%20aborting%20runs.htm
- Attended and Unattended Modes: https://apps.thermofisher.com/apps/lahr/LA_Online_Help_Resource/en-us/Content/Topics/Software/Momentum/Work%20Units/MMTM%20setting%20modes.htm
- Creating and Configuring Pools: https://apps.thermofisher.com/apps/lahr/LA_Online_Help_Resource/en-us/Content/Topics/Software/Momentum/Configuration/MMTM%20configuring%20pools.htm
- Understanding Work Units and Runs: https://apps.thermofisher.com/apps/lahr/LA_Online_Help_Resource/en-us/Content/Topics/Software/Momentum/Work%20Units/MMTM%20work%20units%20intro.htm
- Thermo Fisher Momentum FAQs: https://www.thermofisher.com/order/catalog/product/MOMENTUM/faqs

### 汇像科技
- iMagicOS official product page: https://www.x-imaging.com/product/imagicos
- XImaging product center: https://www.x-imaging.com/product

注：厂商产品页与 FAQ 属厂商自述；涉及实际系统能力、内部架构、设备安全和恢复行为时，需以版本对应的技术手册、现场演示、设备型号/固件测试和合同技术附件进一步验证。
