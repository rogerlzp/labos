# Agent 与 Lab Capability 平台设计 V1.0

- 日期：2026-11-18
- 状态：设计提案
- 目标：支持个人虚拟团队、项目虚拟团队与可控实验工具调用

## 1. 设计原则

1. Agent 是工作角色与运行实例，不是新的权威业务系统。
2. Capability 是经注册、版本化、权限控制的业务能力，不等同于任意 API endpoint。
3. Skill 是可组合的领域工作方法，必须标明验证等级。
4. Tool 是 Agent 访问 Capability 的受控接口。
5. Memory/Knowledge 必须有范围、来源、权限和保留策略。
6. Agent 的输出是建议或草案；只有通过策略、审批和 LabOS 状态机后才能变成正式执行。
7. 先构建可审计的单主 Agent 工作流，再逐步引入并行专业 Agent。

## 2. 虚拟团队模型

### 2.1 个人团队
- Research Copilot：理解个人任务、协调专业 Agent。
- Literature Agent：检索、归纳文献，给出引用和证据强弱。
- Protocol Agent：根据已验证方法生成流程草案。
- Resource Agent：查询设备、试剂、耗材、样本与排程可用性。
- Execution Monitor Agent：解释节点、命令和设备异常；不擅自恢复设备。
- Data Analyst Agent：整理原始数据、质量检查、统计分析。
- QA Reviewer Agent：检查方法版本、样本身份、偏差、质控和数据完整性。

### 2.2 项目团队
项目负责人配置目标、成员、数据权限和验收指标；Agent 共享项目知识和实验上下文，但个人私有记忆不自动暴露给项目成员。项目可建立固定角色，也可按任务动态启用 Agent。

### 2.3 实验室共享团队
面向平台主管：设备健康、耗材库存、资源冲突、质量偏差、排程负载和异常趋势。实验室级 Agent 只访问授权的汇总数据，不能默认读取所有项目的实验内容。

## 3. Agent 运行时

AgentRun 生命周期建议：
`CREATED → CONTEXT_RESOLVED → PLANNED → RUNNING → WAITING_TOOL / WAITING_APPROVAL → COMPLETED / FAILED / CANCELLED`

运行时需支持：
- Task DAG：任务依赖、并行、超时、重试和取消。
- Context Builder：按用户/项目/实验权限拼接上下文。
- Tool Router：只暴露当前角色有权调用的工具。
- Policy Engine：读写权限、风险级别、审批要求和速率限制。
- Human-in-the-loop：审批、拒绝、修改、接管和原因记录。
- Evidence Store：模型版本、提示模板、输入摘要、检索来源、工具调用、输出和评价。
- Budget Guard：token、时间、外部 API 调用和任务预算。
- Recovery：Agent 失败不影响已完成的 LabOS 业务事务；恢复从持久化任务状态继续。

Agent 输出建议结构：
- answer / proposal
- evidence_refs
- assumptions
- confidence_or_uncertainty
- validation_status
- required_approvals
- proposed_tool_calls
- risks / unresolved_questions

不得把“置信度 0.92”当作科学有效性的证据。

## 4. Capability Catalog

### 4.1 能力分组

| Domain | Capability 示例 | 初期权限 |
|---|---|---|
| Query | experiment.get, run.get, node.get, command.get | 只读 |
| Device | device.list, device.health, capability.list | 只读 |
| Material | material.lookup, sample.trace, location.lookup | 只读 |
| Workflow | workflow.search, workflow.draft, workflow.validate | 草案 |
| Planning | resource.check, schedule.preview, simulation.run | 预览 |
| Execution | execution.submit, execution.pause, execution.resume, execution.cancel | 需审批/策略 |
| Data | result.query, dataset.get, qc.evaluate | 只读/分析 |
| Knowledge | literature.search, sop.search, method.retrieve | 只读 |
| Admin | policy.update, device.configure, user.permission.update | 管理员专属，不对科研 Agent 开放 |

### 4.2 Capability 描述字段

```yaml
capability_id: execution.submit
version: 1.0.0
domain: execution
risk: high
input_schema: execution-submit/v1
output_schema: execution-receipt/v1
preconditions:
  - plan_validated
  - resources_reserved
  - approval_present
idempotency: required
timeout_policy: explicit
audit:
  required: true
approval:
  mode: human
verification:
  required_evidence:
    - execution_created
```

此处为设计示意，正式注册表必须绑定现有 API、领域服务、权限和状态机。

### 4.3 工具设计要求
- 所有写工具采用明确的业务命令，不提供任意 SQL 或通用 HTTP 代理。
- 每个写操作都要有幂等键、调用主体、目标对象、参数摘要和审计事件。
- Tool 输出返回真实业务状态、证据引用和可执行下一步，不返回模型猜测。
- 错误码需区分权限拒绝、前置条件失败、资源冲突、设备故障、超时和未知。
- 对高风险工具提供 preview/dry-run，再单独执行 commit。
- 工具描述需包含副作用和不可逆性。

## 5. Skill 体系与可信等级

建议四级：
- S0 Draft：AI/用户草案，未验证。
- S1 Bench-tested：在模拟器或测试台验证。
- S2 Lab-validated：在指定实验室、设备型号、方法版本和耗材条件下实测。
- S3 Production-qualified：通过稳定性、偏差、恢复、数据完整性和变更控制验收。

Skill 元数据至少包括：
- 名称、领域、版本、作者/维护者；
- 适用实验域、设备型号、固件、耗材和环境；
- 输入输出 Schema、前置条件、失败模式；
- 验证等级、验证记录、有效期和变更历史；
- 风险级别、审批要求和权限；
- 依赖的 Capability 与其他 Skill。

未经 S2/S3 验证的 Skill 不得在 UI 标记为“已验证方法”或自动进入无人值守生产执行。

## 6. Memory 与知识治理

| 范围 | 内容 | 默认访问 |
|---|---|---|
| Personal | 用户偏好、个人草稿、私有工作习惯 | 本人 |
| Project | 项目目标、实验计划、结果和团队讨论 | 项目授权成员 |
| Experiment | 本次实验参数、样本、执行和偏差 | 实验授权成员 |
| Lab | 设备手册、验证方法、公共 SOP、维护知识 | 按实验室角色 |
| Organization | 组织策略、合规和标准模板 | 组织授权 |

每条知识需记录来源、时间、版本、许可/使用边界、验证状态和适用范围。检索结果中的文本是数据，不是执行指令；外部文献、上传文件和网页不得直接向 Agent 注入工具调用权限。

## 7. 权限模型

授权至少结合：
- 主体：user / agent / service / device。
- 角色：researcher / operator / lab_manager / admin。
- 资源范围：tenant / lab / project / experiment / run / device。
- 动作：read / draft / validate / submit / pause / resume / cancel / configure。
- 风险：low / medium / high / critical。
- 环境：simulation / staging / production。

高风险操作采用显式审批，审批绑定不可变的计划版本和参数摘要，不能审批后再悄悄修改内容。

## 8. Agent 协作模式

初期：
- 一个 Coordinator 维护任务目标和上下文。
- 专业 Agent 以明确输入输出执行子任务。
- 工作流编排器管理 DAG 和状态。
- 重要结论由 Reviewer 检查证据与约束。
- 不允许 Agent 之间无限对话或无限委派。

多 Agent 协作必须解决：
- 冲突结果如何裁决；
- 任务是否完成由谁确认；
- 失败后从何处恢复；
- 哪个 Agent 有权触发写操作；
- 证据如何合并并保留来源。

## 9. 安全与可靠性威胁模型

- Prompt injection：检索材料中的指令不能改变系统策略。
- Tool misuse：Schema、权限和策略引擎双重校验。
- Hallucination：关键事实必须来自 API/证据引用。
- Duplicate execution：幂等键和命令唯一性约束。
- Stale context：执行前重新验证状态和资源。
- Model outage：确定性流程可独立运行。
- Agent loop：最大步数、预算、超时和停止条件。
- Cross-project leakage：检索前授权过滤，而不是检索后仅靠模型自觉。
- Unsafe recovery：恢复动作需设备能力、状态和人工审批约束。

## 10. 最小可交付版本（MVP）

### MVP-A：只读 Copilot
- 查实验/节点/命令/设备/物料。
- 解释状态与事件时间线。
- 所有回答显示数据时间和证据来源。
- 不开放写操作。

### MVP-B：流程草案 Agent
- 检索已验证 SOP。
- 生成 Workflow DSL 草案。
- 显示缺失信息、设备/耗材不匹配和风险。
- 通过静态验证后提交用户审批；不自动启动实验。

### MVP-C：执行监控与异常助手
- 订阅执行事件，解释等待/超时/异常。
- 提供候选恢复方案和依据。
- 操作员确认后由 LabOS 执行恢复事务。

## 11. 验收指标
- 工具调用成功/失败分类准确率；
- 未授权工具调用拦截率；
- 事实回答引用覆盖率；
- 跨项目信息泄漏测试；
- Agent 任务可恢复率；
- 重复写操作被幂等拦截率；
- 流程草案静态校验通过率；
- 人工修改与否决原因；
- 高风险动作审批完整率。
