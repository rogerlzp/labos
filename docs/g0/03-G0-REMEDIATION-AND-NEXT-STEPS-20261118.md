# G0 整改与下一阶段执行计划

- 日期：2026-11-18
- 基线：`43d137b0081045b6f86770391fb36df3c6f8d638`
- 目标：先让现有中控成为可被 AI 安全调用的执行底座，再建设 Agent Runtime 和实验优化闭环。

---

## 1. 推进原则

1. 先修安全门禁和事实边界，再开放 Agent 写工具。
2. 复用现有服务和领域模型，不另建一套平行实验状态数据库。
3. AI 输出结构化草案；只有服务端编译、校验、授权、审批、冻结的计划才可提交执行。
4. 任何 UNKNOWN 都不能被 Agent 自行解释成成功或自动重试。
5. 先只读助手，再人工批准的流程草案，再有限写操作，最后单一实验域闭环。
6. 每项交付必须有源码、测试、运行记录和适用范围，不以“测试总数”代替覆盖证明。

## 2. G0 修复优先级

| 顺序 | ID | 工作项 | 级别 | 交付 | 验收 |
|---|---|---|---|---|---|
| 1 | G0-01 | WorkflowRunGate UNKNOWN/ERROR fail-closed | P1 | 门禁返回三态/错误态；生产禁止静默放行 | DB 断开、词表为空、参数非法、并发竞态均不允许启动 |
| 2 | G0-03 | 设备事件身份与幂等 | P1 | 设备身份、租户绑定、sourceEventId 必填、run 归属校验 | 重复/迟到/伪造/跨租户事件全部拒绝或幂等 |
| 3 | G0-11 | 统一 tenant/user/project context | P1 | 请求上下文和服务调用统一传递，不再硬编码 TENANT=1 | 不同 tenant 数据隔离测试；无上下文 fail-closed |
| 4 | G0-05 | 高风险审计可靠化 | P1 | 审计 Outbox 或与业务事务原子落库 | 审计不可写时高风险动作不产生未审计副作用 |
| 5 | G0-06 | 对象级授权与 Agent 委托策略 | P1 | project/experiment/dataset/resource ACL + delegated principal | 越权读写测试；Agent 不能超过发起用户权限 |
| 6 | G0-02 | Experiment EvidenceSummary / 状态聚合 | P1 | 多事实只读摘要和确定性投影规则 | 命令、节点、物料、搬运、Hold、数据质量冲突时给 UNKNOWN/阻断 |
| 7 | G0-04 | 服务端 Workflow Compiler 边界 | P1 | schema/version/compile/validate/approval API 设计与首个实现切片 | 无效参数、未授权能力、缺物料/位置、过期版本均拒绝 |
| 8 | G0-07 | 修复 RecipeCompiler 能力映射透传 | P2 | compileAll 使用 capabilityByResource | 新增设备能力映射回归测试；预览与执行解析一致 |
| 9 | G0-08 | 驱动阶段观测能力 | P2 | 设备 ACK/执行事件接口契约 | 只在设备真实回调存在时推进 RECEIVED/EXECUTING |
| 10 | G0-09 | Agent Runtime 最小骨架 | P2 | AgentRun/ToolCall/Approval/DecisionEvidence | 任务超时、取消、重试、人工接管和审计可追溯 |

## 3. G1：只读 AI 实验助手

G0 的安全边界设计完成后，先做只读工具，不开放设备写操作。

### 3.1 首批工具

| Tool | 输入 | 输出 | 权限 |
|---|---|---|---|
| get_experiment_status | experiment_id | 展示状态 + EvidenceSummary + 未决事实 | project/experiment read |
| get_run_timeline | run_id | 节点、命令、事件、暂停、恢复时间线 | experiment read |
| get_device_health | device_id | 在线、心跳、设备事实新鲜度、告警 | lab/device read |
| get_material_location | material_id/barcode | 身份、位置、位置证据、状态 | project/material read |
| explain_blocker | run_id/node_id | 阻断规则、源证据、建议人工动作 | experiment read |
| search_verified_protocols | query | 已验证 SOP/版本/适用设备 | knowledge read |

要求：
- 工具返回结构化字段和证据引用，不只返回自然语言摘要。
- 读操作按用户、项目、实验 ACL 过滤。
- Agent 不得直接 SQL、Mapper、设备网络或厂商协议。
- 工具输出标注数据时间、来源、可信度和 UNKNOWN 原因。
- 对外部 LLM 发送内容按数据等级脱敏/最小化。

### 3.2 G1 验收

- 用户问“为什么这个实验停住”，AI 能引用实际 Hold、节点、命令或物料证据。
- 同一问题在同一数据快照下，工具结果可复现。
- 无权限的项目、样本、结果不能通过提示词绕过 ACL。
- 设备离线或位置未知时明确回答 UNKNOWN，不臆测“已经完成”。
- 只读 Agent 无任何可调用写工具。

## 4. G2：人工批准的流程草案

- AI 从已验证 SOP 和实验目标生成版本化 DSL 草案。
- 服务端执行 schema、参数单位、设备能力、物料、点位、资源、权限和安全校验。
- 生成资源计划和差异报告；仿真级别明确标注 S0/S1/S2/S3。
- 用户审批的是不可变计划 hash；审批后任何修改必须生成新版本并重新审批。
- 执行仍由现有 LabOS Core 调度和命令链路完成。

验收：同一 DSL + compiler + capability version 得到可复现的 plan hash；过期能力、缺失物料、冲突资源和未知位置全部阻断。

## 5. G3：单一实验域 DBTL

选择一个流程稳定、质量指标明确、设备接口成熟的实验，不先做通用科学家。

闭环：
1. AI 生成候选参数组合和科学依据；
2. 服务端校验与人工审批；
3. LabOS 执行并记录计划/实际差异；
4. 原始数据和质控指标关联样本、试剂批次、设备校准、方法版本；
5. 分析 Agent 解释结果并提出下一轮候选；
6. 科学负责人决定是否进入下一轮。

验收：
- 方案、审批、执行快照、命令、位置证据、人工干预、原始数据、分析版本全链可追溯；
- AI 不把失败实验或低质量数据作为有效优化样本；
- 对照组/重复实验和停止标准由领域专家制定；
- 优化结论能在预先定义的指标上通过独立复验。

## 6. 需要明确的架构决策（ADR 候选）

- ADR-G0-01：安全门禁的 UNKNOWN 不等于 ALLOW。
- ADR-G0-02：Experiment 展示状态与 ExecutionReadiness 分离。
- ADR-G0-03：设备身份独立于人类用户 Session；事件必须有稳定来源 ID。
- ADR-G0-04：Agent 使用 delegated principal，权限不高于发起用户。
- ADR-G0-05：高风险业务事实与审计必须可靠关联。
- ADR-G0-06：Workflow Compiler 先编译现有 BPMN/节点，不另造并行执行引擎。
- ADR-G0-07：AI 只提交经审批的 ExecutionPlan，不直接发底层设备命令。

## 7. G1 准入门槛

以下全部满足，才建议开始只读 AI 原型接入真实项目数据：
- [ ] G0-01 门禁 fail-closed 修复并有数据库故障测试
- [ ] tenant/user/project 上下文和对象级读取 ACL 已明确
- [ ] DeviceEvent 身份、租户与幂等边界已明确
- [ ] EvidenceSummary API 有确定性 schema
- [ ] AI 工具调用可审计，敏感数据有外发策略
- [ ] 只读工具与写工具权限分离，默认没有写工具

高风险 Agent 写操作另需 G0-05/06/04 完成，并通过端到端审批、幂等、超时、重启、撤销和人工接管测试。

## 8. 本轮未执行事项

本轮没有提交业务代码修改，没有运行本地 Gradle/Maven/脚本、测试数据库、CI workflow 或真实设备。明确代码问题 G0-07 建议作为独立修复提交；G0-01 等安全问题应先写测试再修改，避免只改注释或放宽门禁。
