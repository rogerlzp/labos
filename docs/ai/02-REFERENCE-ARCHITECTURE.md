# AI-native LabOS 参考架构 V1.0

- 日期：2026-11-18
- 状态：架构提案，需与当前源码逐模块核对
- 原则：不替换既有中控领域模型；先建立映射，再逐步扩展

## 1. 架构总览

```text
L4  Scientific Intelligence
    Research Copilot / Personal Agent / Project Agent / Domain Agents
    Literature · Hypothesis · Design · Analysis · Optimization
                         |
                         v
L3  Agent & Experiment Orchestration
    Agent Runtime · Planner · Workflow Compiler · Simulation
    Policy / Approval · Knowledge Retrieval · Experiment Workspace
                         |
                         v
L2  LabOS Core / Control Plane
    Experiment · Workflow/Node · Scheduler · Resource
    Device/Capability · Command Lifecycle · Material/Sample/Location
    State Reconciliation · Permission · Audit · Recovery
                         |
                         v
L1  Device & Edge Integration
    Device Drivers · Sidecars · Protocol Adapters · Edge Collector
    开放设备互操作协议 / Vendor API / TCP / Serial / OPC-UA (as applicable)
                         |
                         v
L0  Physical Laboratory
    Instruments · Liquid Handlers · Robots · Storage · Sensors · Operators
```

说明：分层是逻辑边界，不要求第一阶段拆成微服务。可先以模块化单体/现有服务边界实现，避免为架构图而过度拆分。

## 2. 五层职责与不可越权边界

### L4 科研智能层
- 理解自然语言研究目标，检索文献和实验室知识。
- 生成假设、候选方案、分析代码草案和优化建议。
- 通过 L3 暴露的受控工具使用系统。
- 不直接访问数据库、设备网络或驱动私有协议。
- 不可自行认定实验成功、物料已移动、设备已完成动作。

### L3 Agent 与实验编排层
- Agent 生命周期、任务分配、上下文隔离、工具权限、超时与恢复。
- Workflow DSL 解析、Schema 校验、编译、静态检查、资源计划和仿真。
- 将用户批准的执行计划提交给 L2。
- 对 Agent 运行、工具调用、模型版本、输入输出和人工批准留痕。
- Agent 失败不能改变 L2 已确认的设备/物料/实验事实。

### L2 LabOS Core
- 实验、流程节点、设备、物料、样本、位置、资源和命令的权威状态。
- 资源锁、调度、命令生命周期、状态转换、权限、安全和审计。
- 接收设备事实并与预期执行状态进行对账。
- 处理超时、迟到事件、重复事件、设备离线、恢复与人工接管。
- L2 是执行事实的权威来源；L3/L4 不维护平行事实。

### L1 设备与边缘接入层
- 协议适配、连接管理、设备事件采集、健康状态和边缘缓存。
- 把设备厂商协议映射到标准能力与事件。
- 保留原始设备响应、错误码和时间戳，避免适配器丢失诊断信息。
- 网络分区时按策略安全停机/排队，不允许边缘层自行编造执行成功。

### L0 物理实验室
- 真实设备、耗材、样本、实验人员及环境。
- 物理事实可能与系统预期不一致；必须通过传感器、扫码、视觉、设备反馈或人工确认纠偏。

## 3. 核心领域对象

建议以现有实体为基础逐项核查，不重复造表：

| 对象 | 语义 | 关键不变量 |
|---|---|---|
| Experiment | 科学实验/研究批次 | 具有负责人、项目归属、目标、状态和审计 |
| WorkflowDefinition | 可复用流程定义 | 版本化；修改不改变已冻结执行计划 |
| WorkflowNode | 流程中的步骤 | 依赖、输入输出、状态转换有明确约束 |
| ExecutionPlan | 某次执行的冻结计划 | 来源版本、参数、资源分配、校验结果不可悄然变更 |
| Run/Execution | 实际执行实例 | 记录计划、开始/结束、暂停/恢复/失败和实际偏差 |
| Device / Capability | 设备及其可执行能力 | 能力声明包含参数类型、单位、范围、前置条件和副作用 |
| DeviceCommand | 发给设备的命令 | 唯一关联、幂等策略、超时、确认和终态清晰 |
| Material / Sample | 试剂、耗材、样本及其身份 | 身份稳定、批次/来源/有效期和生命周期可追溯 |
| Location / Placement | 物理位置和占用关系 | 一个物料不能同时占用互斥位置；移动需有事实证据 |
| Observation | 设备/传感器/人工观察 | 来源、时间、对象、可信度和原始载荷可追溯 |
| Result / Dataset | 原始数据与派生结果 | 关联样本、步骤、设备、方法和分析版本 |
| AgentRun / ToolCall | AI 任务和工具调用 | 模型、提示、工具参数、权限、输出和审批可审计 |

上表是目标语义，不是要求所有对象新增独立数据库表。

## 4. 双事实流：命令事实与物理事实

必须避免将设备命令完成等同于物料物理位置已确认。

- **命令事实流**：命令已创建、已发送、设备已接受、执行中、设备报告完成/失败、超时、取消等。
- **物理事实流**：样本/板/耗材实际在哪个位置、是否被抓取、是否落位、位置是否占用、是否扫码/视觉确认。
- **流程事实流**：节点前置条件满足、节点运行、等待、暂停、完成、失败、人工接管。
- **实验事实流**：实验级状态与整体运行条件。

这些事实相互约束，但不是简单的单向级联。比如设备返回“搬运完成”并不能自动证明板已在目标槽；视觉/扫码/位置传感器确认失败时，节点不能被标记为物理完成。相反，人工确认物料已到位也不能伪造设备命令成功。

建议引入统一 Observation / Evidence 记录和状态对账器（Reconciler），按证据来源、可信度、时间顺序与冲突策略更新权威状态。冲突无法判定时进入 UNKNOWN / NEEDS_RECONCILIATION，而不是乐观推进。

## 5. Workflow Compiler 与执行快照

### 5.1 处理链路
1. Intent：用户自然语言目标和约束。
2. Workflow DSL：有类型的实验步骤、输入输出、参数、单位、条件和质量要求。
3. Static Validation：Schema、变量、依赖、设备能力、参数范围、样本身份和安全策略校验。
4. Planning：设备/资源/位置/耗材/时间窗分配。
5. Simulation：检查逻辑、资源冲突、容量、位置和可模拟物理约束。
6. Approval：展示差异、风险、资源占用、预计人工任务，记录批准人和批准版本。
7. Execution Snapshot：冻结 DSL、编译器版本、能力版本、设备配置、物料分配和校验结果。
8. Dispatch：由 L2 调度器创建节点和命令。
9. Reconciliation：将设备事件与物理观察对账，处理偏差。
10. Results：原始数据、派生数据、质量指标、偏差和分析版本关联到实验。

### 5.2 DSL 最小设计要求
- Schema 有版本，字段类型明确。
- 数量、体积、温度、时间必须带单位；不接受无单位裸数。
- 样本、容器、位置、试剂引用使用稳定 ID，不用显示名称作为身份。
- 每个动作声明 capability、参数、前置条件、预期副作用、完成证据和失败处理。
- 支持人工步骤、设备步骤、等待/确认、质控门和异常分支。
- 执行时不解释自然语言；只执行已编译、校验、冻结的计划。

示意（非正式 Schema）：

```yaml
schema_version: labos.workflow/v1
experiment:
  objective: sample_preparation
  inputs:
    - sample_group_id: S-GROUP-001
      count: 24
  steps:
    - id: transfer-01
      action: liquid.transfer
      capability: liquid.transfer
      source: container://source-plate
      target: container://reaction-plate
      volume: 5 uL
      requires:
        - source_identity_verified
        - destination_capacity_sufficient
      completion_evidence:
        - device_command_terminal
        - liquid_transfer_observation
  safety:
    approval_required: true
    sample_traceability_required: true
```

此示意只说明建模方向，不是可直接部署的规范。正式 DSL 需以当前中控节点类型、设备方法、单位、异常语义和物料模型为依据。

## 6. 设备能力模型与互操作

每个 Capability 至少描述：
- capability_id / version / device_model / firmware compatibility
- input/output schema、单位、范围、精度与默认值
- preconditions、resource locks、side effects
- timeout、idempotency、retry policy、compensation/recovery
- completion evidence、error taxonomy、safety classification
- simulation support、verification status、validation evidence

设备接口建议：
- 内部定义稳定的 Lab Capability API。
- 设备适配器将厂商协议映射到 Capability。
- 对支持的设备研究 开放设备互操作协议 Features/Commands/Properties。
- 不支持标准协议的设备通过 Adapter/Sidecar 接入，保持内部语义一致。
- 不将 开放设备互操作协议 当作实验流程语言，也不要求第一阶段全面重写驱动。

## 7. 数字孪生边界

四类模型：
1. 设备孪生：设备状态、能力、健康、校准和工作区。
2. 物料孪生：身份、容器、容量、批次、位置和占用。
3. 流程孪生：节点依赖、时间、资源冲突、分支与并行。
4. 实验孪生：实验目标、样本谱系、参数、质量指标和结果。

仿真分级：
- S0 结构校验：Schema、依赖、参数、变量。
- S1 资源校验：设备能力、占用、容量、排程。
- S2 物理近似：位置可达、体积/容量、搬运路径和碰撞约束。
- S3 经设备/实验数据校准的模型：仅对明确设备与场景有效。

仿真结果必须标明级别、模型版本、已覆盖约束和未覆盖风险。Simulation PASS 不等于真实执行保证。

## 8. 安全与治理

- 默认最小权限；工具按 user / project / experiment / lab scope 授权。
- 高风险动作（启动、取消、修改关键参数、解锁、恢复、物料报废等）按策略审批。
- Agent 只能调用业务 API，不得 SQL 直写。
- 所有写工具支持幂等键、调用主体、请求摘要和审计事件。
- Prompt injection、文献/文件中的恶意指令均视为不可信输入。
- 模型和工具版本固定到 AgentRun；输出可重放/可审计。
- 敏感实验和受监管数据按租户、项目和角色隔离。
- 网络中断、模型不可用时，已批准的确定性流程仍可按策略运行；AI 不应成为安全关键执行链路的单点依赖。

## 9. 与现有中控的映射方法

先做“现有模块—目标职责—代码入口—API—数据对象—状态机—测试证据”矩阵。不能仅凭文档标题或界面截图认定实现完成。

首轮盘点范围：
- Experiment / Run / Workflow / Node 状态机；
- Device / Driver / Capability / Method；
- Command 生命周期与设备事件；
- Material / Sample / Position / Placement 及物理位置确认；
- Scheduler / Resource Pool / Interlock；
- Permission / Audit / Recovery；
- Data ingestion / Result / Report。

项目近期新增的提取线从零建岛实测显示：设备编号/端口需要全局隔离；方法集必须匹配实际型号；物料位置账本、轨道互锁和实验模板实例化是流程起跑的前置条件。这些是能力模型与实验编译器必须显式建模的真实约束，不能只依赖“设备有某 capability”这一粗粒度判断。

## 10. 架构决策记录（ADR 候选）

- ADR-001：Agent 不直接控制设备；所有动作经 LabOS Core。
- ADR-002：Workflow DSL 与执行快照分离；执行中不被源模板修改影响。
- ADR-003：命令事实与物理事实双流建模；由 Reconciler 处理一致性。
- ADR-004：Capability 版本化；设备/方法适配有验证等级。
- ADR-005：Agent Runtime 先采用主 Agent + 专业工具/子 Agent，不做无约束自治群。
- ADR-006：先模块化实现，只有在部署/隔离/扩展需求证实后再拆服务。

## 11. 验收门槛

- 编译器输出可确定性复现；相同输入和版本产生相同计划。
- 计划冻结后任何变更生成新版本并要求重新校验/审批。
- 设备命令成功与物理完成分别有证据。
- 重复事件、迟到事件、断线、超时、重启均不造成状态回退或双重执行。
- 任何无法判断的事实进入 UNKNOWN / NEEDS_RECONCILIATION。
- Agent 工具调用可审计、可授权、可拒绝。
- 至少一个真实设备流程完成端到端验收，报告计划/实际差异与人工干预。
