# LabOS AI 安全、对象授权与数据治理标准

- 日期：2026-11-18
- 状态：设计规范；需由实现、测试和安全评审证明，不代表已实现
- 适用范围：AI Workbench、Agent Runtime、Capability、Skill、Memory、Task、Artifact、Evidence 和未来模型服务集成

## 1. 安全原则

1. LabOS Core 是授权、状态和执行事实的唯一权威；AI 不得自行推断权限或改变事实。
2. Tenant 隔离与 Project 对象授权是两道独立边界；tenant 条件不能替代 Project ACL。
3. Agent 是受委托主体，不是拥有独立超权限的服务账号。
4. 模型输入、输出、检索内容、设备事件和用户文本均视为不可信数据。
5. 默认最小化、默认不出域、默认拒绝；明确批准后才开放特定数据和模型路径。
6. 安全关键业务动作必须可追溯、可幂等、可恢复；审计不可用时不得静默成功。

## 2. 对象级授权模型

### 2.1 授权判断

对资源 R 和动作 A，只有以下条件全部成立才允许：

`Allow = TenantMatch \land ProjectMembership \land SubjectPermission \land CapabilityPolicy \land ResourceStatePolicy`

其中：
- TenantMatch：资源 tenant 与当前会话/服务身份一致；
- ProjectMembership：调用者在资源所属项目具有有效成员关系；
- SubjectPermission：调用者对 Experiment/Run/Artifact 等具体主体具有对应动作权限；
- CapabilityPolicy：当前 Capability 声明允许该动作和数据范围；
- ResourceStatePolicy：资源状态允许该动作。

管理员权限如需跨项目访问，必须是明确、可审计的管理策略，不得由角色名隐式推断。

### 2.2 资源继承

| 对象 | 必须校验 |
|---|---|
| Project | 当前用户成员关系/显式管理权限 |
| Experiment / ExperimentRun / Run | 通过服务端关系解析所属 Project，再做成员授权 |
| AITask / AIArtifact | 校验 task/artifact 所属 Project；不能只检查创建者或 tenant |
| EvidenceRef / TwinDiff / RunEvent | 校验证据所属主体和 Project；防止跨项目拼接 |
| Project Memory / Agent Context | 读取、写入、检索均限制在授权 Project |
| 导出、附件、缓存、搜索索引 | 与源对象相同的 ACL；撤权后失效或重新校验 |

禁止仅凭客户端传入的 projectId 建立授权。必须由服务端读取目标资源关系，不能信任模型返回的主体 ID。

### 2.3 Agent 委托权限

Agent 每次工具调用都携带：
- initiating_user_id；
- project_id；
- agent_id / agent_instance_id；
- task_id / trace_id；
- capability_id + version；
- requested_action；
- authorization_decision_id。

有效权限取交集，不得取并集。工具执行前重新授权，不能只在 Agent Session 创建时检查一次。长任务在项目成员撤权后，后续工具调用必须被拒绝；已运行任务按取消/安全停止策略处理。

### 2.4 跨执行链的 principal 传播

```text
user → agent → run → device → callback
```

| 环节 | 授权/认证主体 | 校验时点 | 审计必须记录 |
|---|---|---|---|
| user → agent | 发起用户及其 Project 成员关系；Agent 只获得委托权限 | 创建 Agent 任务时及每次工具调用前 | initiating user、agent/task、Project、Capability、授权决策 ID |
| agent → run | 发起用户委托权限与 Run 所属资源策略的交集 | 创建/关联 Run、提交计划及每个后续 Agent 写动作前 | initiating user、agent/task/run、ExecutionPlan/Artifact hash、授权决策 ID |
| run → device | 设备的独立机器身份及其 tenant/device/run 绑定；不伪造用户 Session | 下发前校验 Run 授权快照、实时资源状态和设备绑定 | run、device identity、命令/计划 hash、下发策略与设备来源 |
| device → callback | 回调设备机器身份、稳定 sourceEventId 及其 tenant/run 归属 | 每次接收回调时校验 device↔tenant↔run、事件幂等与状态转换 | device/source event、tenant/run、接收时间、接受/拒绝/幂等结果 |

Run 保存发起主体和授权决策引用，但异步 callback 不会因为关联了 Run 就重新获得发起用户权限；它只能提交该设备身份被允许的事实事件。审计同时保留 initiating user、agent/task/run 和设备来源，不能只记录其中一个主体。

用户撤权必须阻断后续 Agent 工具调用；设备身份撤销按设备安全策略阻断后续下发与回调。设备↔tenant↔run 绑定、稳定设备身份和回调幂等未实现并测试前，对应隔离能力保持 UNKNOWN。

## 3. 数据分类与模型出域

### 3.1 数据分类

| 等级 | 示例 | 默认模型策略 |
|---|---|---|
| D0 Public | 已公开论文、公开产品文档 | 可使用批准的外部模型 |
| D1 Internal | 内部流程元数据、非敏感系统说明 | 仅批准模型；按最小化策略 |
| D2 Confidential | 客户实验方案、项目上下文、未公开结果、内部工艺 | 默认不出域；需项目授权和供应商批准 |
| D3 Restricted | 个人身份信息、样本可识别信息、受监管/高敏数据、凭据 | 禁止发送外部模型；需专门合规评估与隔离方案 |

分级是默认策略；具体项目可提高限制，不能由 Agent 或用户提示词降低级别。

### 3.2 外部模型供应商准入

每个供应商/模型版本必须有可审查记录：
- 数据是否用于训练或改进模型；
- 输入/输出保留期限与删除方式；
- 处理地区、跨境传输与子处理方；
- 企业/API 数据处理条款和保密承诺；
- 日志、滥用监测和人工访问策略；
- 模型版本变化、服务终止和数据删除流程。

缺少关键条款信息时，状态为 UNKNOWN，禁止 D2/D3 数据出域。不能仅因使用 API Key 或供应商宣称“安全”就判定合规。

### 3.3 出域决策点

在创建 AI Task 前，由服务端策略引擎执行：
1. 根据 Project、数据源、字段和用途计算最高数据等级；
2. 对上下文做字段 allowlist 和最小化；
3. 检查项目是否允许外部模型及允许的供应商/区域；
4. 检查当前模型版本是否通过供应商准入；
5. 拒绝包含密钥、Authorization、样本身份信息等禁止字段；
6. 记录策略版本、决策结果、数据等级和模型路由，不记录敏感原文。

拒绝时返回稳定错误码，例如 `MODEL_ROUTE_NOT_APPROVED`、`DATA_CLASSIFICATION_BLOCKED`、`PROJECT_EXTERNAL_MODEL_DISABLED`。

## 4. 审计和留存

### 4.1 必须记录的元数据

- 操作者/委托主体、Project、Task/Artifact/Run；
- action、时间、结果、拒绝原因；
- Capability/Agent/Skill 版本；
- model/provider/version、prompt/schema version；
- 输入快照哈希、输出哈希、证据引用 ID；
- 数据等级、模型出域策略版本、授权决策 ID；
- 重试 attempt、lease/fencing 信息及幂等键的不可逆摘要。

### 4.2 审计可靠性等级

| 动作 | 要求 |
|---|---|
| 普通只读查询 | 可异步遥测；安全日志不得包含敏感原文 |
| Task/Artifact 创建、重试、取消 | 持久化审计；可关联完整任务生命周期 |
| Draft 确认/Experiment 实例化 | 事务内审计或 transactional outbox；审计故障不得造成无记录业务成功 |
| 设备控制/高风险写 | 本期不开放 AI；未来需独立审批、不可变执行快照和可靠审计 |

建议使用 transactional outbox 将业务事务与审计事件原子写入，再异步投递到审计存储；消费者按 event_id 幂等。若当前存储不支持该模式，必须明确等价的可靠实现和恢复测试。

### 4.3 留存与删除

每类数据明确 retention、legal hold、项目关闭后的归档/删除和备份过期策略。审计记录不得因普通业务对象删除而级联丢失。删除操作本身需要授权、审批和审计。

## 5. 安全验收用例

- 同租户不同 Project 的成员互相不能读取任务、Artifact、Evidence、Memory 和附件。
- 撤销项目成员后，已有会话、长任务、缓存和搜索结果不再返回项目数据。
- Agent 无法通过 prompt injection 改变 projectId、role、tenant 或 Capability 权限。
- 未批准模型/区域/数据等级组合被服务端拒绝。
- D2/D3 输入不会出现在外部请求、日志、异常或追踪系统中。
- 审计写失败时，实例化操作回滚或进入可恢复待处理状态；无孤儿业务对象。
- 同一幂等键不同 input hash 返回 409；旧 worker fencing token 不能写入。
- 导出、证据深链、附件下载和搜索索引均执行相同 ACL。

## 6. 实施状态

本文件是目标标准。每项能力必须在实现矩阵中标注 PASS / PARTIAL / FAIL / UNKNOWN，并附源码、迁移、测试和运行证据。不得把本规范的存在描述为系统已经具备这些安全能力。
