# G0 源码级核查报告

- 日期：2026-11-18
- 仓库：`rogerlzp/labos`
- 分支：`docs/ai-native-labos-strategy-20261118`
- 核查基线：`43d137b0081045b6f86770391fb36df3c6f8d638`
- 评审对象：AI-native LabOS 战略方案所依赖的现有中控执行底座
- 核查类型：关键路径源码静态核查 + 测试/迁移证据核对
- 总体结论：**PARTIAL — 已有相当数量的执行、安全和物料一致性基础，但尚未达到可直接承载自主 Agent 的完整、闭环、可审计平台标准。**

---

## 1. 执行摘要

本轮对分支固定 commit 的 Git tree 做了全量文件清单盘点（1,216 个 blob），并重点检查 LabOS Kernel、Boot/API、Mapper XML、数据库迁移、关键 DB SelfTest 和 AI 流程规划代码。重点覆盖命令生命周期、实验/Run/Node 状态、设备事件、物料效果、搬运与位置证据、调度门禁、权限审计、AI 规划与流程编译。

### 1.1 已有基础（PARTIAL，不等于整项 PASS）

1. **设备命令账本已落地**：派发前写 `command_ledger`，同幂等键阻止再次派发；启动恢复时查询设备状态，无法确认进入 UNKNOWN，不自动重发。证据：`CommandLedger.java` 约 36–80、105–220 行；`DriverStepExecutor.java` 约 1145–1230 行；`CommandLedgerDbSelfTest`、`CommandQueryReconcileDbSelfTest`。
2. **物料效果闭环有核心事务边界**：节点执行确认与效果意图 T1 同事务，T2 物料投影独立提交；闸门读取失败会 fail-closed，阻止后继设备动作。证据：`MaterialEffectService.java` 约 25–125 行。
3. **搬运报告与物理位置确认已区分**：设备回包只记 REPORTED；位置证据确认后才原子写位置、CONFIRMED 和板 KNOWN；UNKNOWN 需要人工盘点。证据：`TransferTransaction.java` 约 20–235 行；`TransferConfirmWithPositionDbSelfTest.java`。
4. **设备事件具有持久化和消费幂等设计**：事件表、source_event_id 唯一索引、run 游标、单事件消费和单等待者约束均有迁移。证据：`20261115_device_event.sql`。
5. **全局角色权限已有服务端拦截**：admin/operator/designer/viewer 角色、编排与运行权限分开，审计读取和部分治理参数有额外限制。证据：`RbacRules.java`、`ApiAuthInterceptor.java`、`RbacRouteGuardSelfTest.java`。

### 1.2 关键缺口

| ID | 级别 | 结论 | 影响 |
|---|---|---|---|
| G0-01 | P1 | 同流程并发门禁在查询异常等情况下 fail-open | 设备事件可能被并发 Run 抢占，物理资源冲突防线被绕过 |
| G0-02 | P1 | 实验级状态尚未由多组执行事实完整聚合 | UI 的 experiment.status 不能作为 AI 判定实验真实完成/可继续的唯一事实 |
| G0-03 | P1 | 设备事件入站使用全局共享令牌且租户固定为 1；sourceEventId 可缺省 | 多租户/多设备身份、重放与事件归属边界不足 |
| G0-04 | P1 | AI 规划结果的业务校验与应用在前端，不是服务端执行编译链 | 不能把当前 AI 功能当成受控的服务端 Workflow Compiler |
| G0-05 | P1 | 审计是 best-effort，写失败不阻断；AI 请求审计未形成完整决策证据包 | 不适合作为高风险 Agent 写操作的唯一审计保障 |
| G0-06 | P1 | RBAC 主要是全局角色级，未见项目/实验/数据对象级授权闭环 | 不能直接满足个人 Agent 与项目 Agent 的隔离要求 |
| G0-07 | P2 | RecipeCompiler.compileAll 的设备能力重载参数未向 compile 透传 | 编译预览可能忽略绑定设备能力，产生错误能力解析 |
| G0-08 | P2 | 命令 phase 的 RECEIVED/EXECUTING 对当前同步驱动不可达 | 运行中观测粒度不足；AI 只能看到较粗阶段 |
| G0-09 | P2 | 当前 AI 是流程图修改 Copilot，不是 Agent Runtime | 无个人/项目 Agent 生命周期、工具权限、上下文隔离、任务恢复和版本审计 |
| G0-10 | P2 | 数字孪生/数据谱系已有局部能力，尚不能证明完整实验级 DBTL | 无法据此承诺自动优化实验结果 |
| G0-11 | P1 | 核心执行路径仍可见固定 TENANT=1 | 多租户部署/项目级隔离前必须统一身份上下文与租户边界 |
| G0-12 | P1 | 缺少本轮可核验的 CI/现场验收证据 | 代码存在和自测文件存在不等于本轮构建、运行或真实设备验收通过 |

> 级别解释：P1 表示在引入 AI 受控写操作或扩大真实运行前应处理；P2 表示不宜忽略，但可在 G1 只读阶段并行修复。不是对现有生产事故严重性的断言。

---

## 2. 详细发现

### G0-01 — 同流程并发门禁不是严格 fail-closed（P1 / FAIL）

`WorkflowRunGate.check` 在以下情况返回 allow：
- db / tenantId / workflowId 参数不完整（约 96–100 行）；
- active status 列表为空（约 103–105 行）；
- SQL 查询抛出 SQLException（约 147–150 行）。

但 `ExperimentExecutor` 的调用处约 168–184 行明确把该门禁描述为“开跑前 fail-closed”。因此实现与调用方安全承诺不一致。默认禁止并发的环境变量开关也可显式放开。

影响：如果 DB 短暂故障或状态词表加载异常，门禁会放行；而该门禁本来是为避免同一 workflow 的多个 Run 抢消费设备事件、争用唯一机械臂资源。其他设备锁可能降低风险，但不能证明同流程事件互斥仍然成立。

建议：
1. 将“门禁无法判断”独立建模为 UNKNOWN / ERROR，不与 ALLOW 合并。
2. 对生产配置禁止无审批的并发绕过；开发 bypass 必须显式标环境并进入审计。
3. 增加 DB 不可用、词表为空、租户/流程参数无效、并发竞争的判别性测试。
4. 确认 Web 单流程启动和 ExperimentExecutor 两条入口都消费同一个决策结果。

### G0-02 — Experiment 状态投影尚未成为事实聚合器（P1 / PARTIAL）

`ExperimentStateProjector` 已经是 `experiment.status` 的唯一写入入口，并将 SQL 收口到 Mapper；这是重要的结构性进展。

但源码注释明确指出：它**不是**由四组事实聚合状态的平台，L-05 仍标记 DEFERRED；当前主要把既有 Run/Batch 状态计算结果投影到 experiment。证据：`ExperimentStateProjector.java` 约 10–31 行、110–176 行。

因此，不能仅凭 `experiment.status=completed/paused` 让 Agent 推断：
- 所有设备命令是否终态；
- 所有物料效果是否 APPLIED；
- 搬运是否有物理位置确认；
- 是否存在 Hold / reconciliation_required；
- 原始数据是否齐全且质控合格。

建议：保留 Experiment 展示状态，但提供单独的 `ExecutionReadiness / EvidenceSummary` 只读业务查询，明确列出 command、node、material、transfer、hold、data-quality 六类事实的状态与证据。聚合规则必须来自真实状态机，而不是让 LLM 自行归纳。

### G0-03 — 设备事件入站的身份与重放边界不足（P1 / PARTIAL）

`DeviceEventController`：
- 使用固定 `TENANT=1L`（约 43 行）；
- 由 `LABOS_DEVICE_EVENT_TOKEN` 校验一个全局共享令牌（约 63–80 行及文件末尾 eventToken）；
- `sourceEventId` 从请求读取，但允许缺省（约 89–102 行）。

迁移文件 `20261115_device_event.sql` 为非空 source_event_id 建立唯一索引，但索引是部分唯一索引；没有 sourceEventId 的事件不会得到该幂等约束。设备事件 POST 在 `ApiAuthInterceptor` 中绕过用户 Session 校验，依赖 Controller 自己的令牌校验（约 48–52 行）。

优点：生产默认要求设备令牌，未配令牌只有显式设置匿名联调开关才允许；这不是“完全匿名开放”。

缺口：当前证据不足以证明每台设备有独立身份、令牌轮换/吊销、事件签名/时间窗、设备与租户绑定，以及 runId 与设备归属的服务端交叉校验。固定租户也与未来多租户/多项目 Agent 模型冲突。

建议：设备身份凭据与人类用户身份分离；每台设备或网关有独立身份，事件必须有稳定 sourceEventId，校验 device↔tenant↔run↔workflow 关系，保留原始帧 hash、接收时间和来源。

### G0-04 — 现有 AI 规划不等于 Workflow Compiler（P1 / PARTIAL）

当前 `AiPlanService` 的职责是拼 Prompt、调用配置的 LLM、抽取 JSON；`WorkflowController` 说明业务校验与应用在前端 `flowPlan.ts / flowOps.ts`，模型输出 ops/plan 由浏览器展开和应用。证据：`AiPlanService.java` 约 15–40、160–240 行；`WorkflowController.java` 约 330–385 行。

这有积极的一面：AI 没有直接向设备发命令，且 API Key 放在服务端。

但它不具备目标架构中的服务端闭环：
Intent → typed DSL → schema/version validation → capability validation → resource planning → simulation → approval → immutable execution snapshot → L2 dispatch。

另一个重要边界：当前请求把 instruction、currentSpec、catalog 发给配置的外部 LLM 服务；本轮未找到数据分级、脱敏策略、模型供应商 allowlist、按项目禁用外发或请求保留策略。涉及客户实验配方、专利前数据和样本元数据前必须补齐治理。

建议：先把 AI 输出限制为不可执行草案；服务端建立统一 Schema、编译器和验证 API；浏览器只展示差异和发起审批，不承担安全关键业务校验。

### G0-05 — 审计写入是 best-effort（P1 / PARTIAL）

`AuthService.audit` 捕获 RuntimeException 后只记录日志，不向调用方失败；`AuditRecorder.record` 以 Controller 层辅助调用为主，且在 `AUTH_ENABLED=false` 时跳过。证据：`AuthService.java` 约 170–205 行；`AuditRecorder.java` 全文件。

这对普通遥测可接受，但不能作为 Agent 高风险写操作的唯一审计保障。若 Agent 启动实验、修改参数、解除隔离或确认物料，而审计落库失败，系统仍可能继续操作。

建议区分：
- 普通查询/诊断日志：允许 best-effort；
- 高风险命令和状态变更：业务事实与审计事件同事务或可靠 Outbox；
- Agent 决策链：保存 actor、user/project/experiment scope、model/provider/version、prompt/input hash、tool name/args hash、policy decision、approval id、execution snapshot id、结果与拒绝原因。

### G0-06 / G0-11 — 权限与租户上下文不足以支持个人/项目 Agent（P1 / PARTIAL）

`RbacRules` 当前是 admin/operator/designer/viewer 全局角色判断；`ApiAuthInterceptor` 把设计与运行端点按角色分流。尚未在本轮核查的授权路径中找到统一的 user→project→experiment→dataset 对象级策略引擎。

同时，`WorkflowController`、`DeviceEventController` 和 `RealWorkflowRunner` 存在固定 `TENANT=1L` 的代码。证据：`RbacRules.java` 约 20–65 行；`ApiAuthInterceptor.java` 约 45–120 行；`WorkflowController.java` 约 68–75 行；`DeviceEventController.java` 约 40–50 行；`RealWorkflowRunner.java` 约 45–60 行。

这不代表当前单实验室部署必然不可用；它意味着不能直接把当前角色体系等同于多项目、多租户、Agent 委托权限。

建议：
1. 请求上下文统一解析 tenant/user/role，不允许 Controller 自己常量指定租户。
2. 所有业务服务以显式 tenant context 运行；禁止跨租户 fallback。
3. Agent 的权限是委托权限，不能高于发起用户；工具每次调用按 project/experiment/resource 重新授权。
4. 记忆按 user/project/experiment/lab 分层，并在检索时做对象级 ACL 过滤。

### G0-07 — RecipeCompiler.compileAll 忽略设备能力映射参数（P2 / FAIL）

`compileAll(nodes, protocolCap, capabilityByResource)` 的签名明确接收设备能力映射，但循环内调用的是 `compile(n, protocolCap)`，而不是 `compile(n, protocolCap, capabilityByResource)`。证据：`RecipeCompiler.java` 约 118–126 行。

`WorkflowService.compile` 调用时确实传入 `RealWorkflowRunner.loadResourceCapability(db)`（约 520–524 行）。因此当前编译预览没有使用调用方提供的设备能力映射。现有 `RecipeCompilerTest` 覆盖单节点模板展开，但没有覆盖 compileAll 的设备能力映射透传。

建议：修正透传并增加“节点声明能力与绑定设备能力冲突时，预览使用预期优先级”的回归测试。此项属于明确可修复代码问题。

### G0-08 — 命令 phase 的观测阶段仍受同步驱动限制（P2 / PARTIAL）

`CommandLedger` 与 `20261115_command_phase.sql` 明确指出当前驱动是同步阻塞调用，实际可达 PENDING/SENT/DONE；RECEIVED/EXECUTING 是预留常量，当前没有设备 ACK/开始执行回调。重启查询可把确认仍运行的命令推进 EXECUTING，但实时执行过程本身缺少该阶段事件。

建议：短期不要伪造阶段；把状态显示为“已发送/等待设备结果”，中期通过驱动适配层支持事件/查询回调，再推进真实阶段。

### G0-09 — 当前 AI 不是 Agent Runtime（P2 / PARTIAL）

源码中已存在 AI 流程规划入口，但未在本轮目标代码中发现完整的 AgentRun/ToolCall 生命周期、任务编排、跨 Agent handoff、上下文隔离、记忆 ACL、预算/超时/重试/人工接管、模型版本与决策重放链路。

建议：先只读工具目录与 Agent Runtime 最小骨架，不要在第一版开放任意设备写工具。

### G0-10 — 数字孪生与数据谱系是局部能力（P2 / PARTIAL）

已有 `twin_diff_report` 离线差分报告、run position snapshot、物料声明快照/效果事件、扫码证据、Run/Node 执行事件等数据基础。但离线 twin diff 本身不等于真实设备校准模型；`twin_diff_report` 迁移明确允许 NO_DATA，且是离线 dry-run 产物。

本轮没有证明每一条结果数据都能追溯到样本、试剂批次、设备校准状态、原始命令、物理证据、质控与分析版本。结论 PARTIAL，不得描述为完整 DBTL。

### G0-12 — 测试存在，但本轮没有运行证据（P1 / UNKNOWN）

本轮确认仓库包含大量判别性 DB SelfTest，例如：
- `CommandLedgerDbSelfTest`
- `CommandQueryReconcileDbSelfTest`
- `TransferConfirmWithPositionDbSelfTest`
- `ReconcilingGateDbSelfTest`
- `ExperimentStateProjectorSelfTest`
- `RbacRouteGuardSelfTest`
- `RecipeCompilerTest)

这些测试的存在及断言设计是源码证据，不是本轮测试执行证据。GitHub 对基线 commit 的 combined status 查询未返回 status checks；本轮没有可验证的 CI run 结果，也未连接用户本地运行环境。因此构建、测试库迁移、完整测试套件和真实设备验收均为 UNKNOWN。

---

## 3. 对 AI-native LabOS 架构提案的调整

### 3.1 保留并复用

- L2 LabOS Core 的命令账本、Run/Node 执行、物料效果闸门、位置锁、扫码证据、TransferTransaction、恢复/隔离机制。
- 现有 BPMN/Workflow 作为当前流程表达和执行载体。
- 现有设备模型、方法参数、绑定、点位和资源能力数据。
- 现有 RBAC 作为初始角色基础，但不能直接当作 Agent 委托授权模型。

### 3.2 暂缓

- 任意 Python/LLM 生成代码直接执行。
- Agent 直接调用设备驱动、SQL 或内部 Mapper。
- 多 Agent 自由自治、Agent 自己决定设备重试或解除 UNKNOWN。
- 在没有完整数据谱系前做自动实验结论优化。
- 把仿真 PASS 当作真实设备执行保证。

### 3.3 必须新增或补齐

1. `LabCapability API`：服务端业务工具，具备稳定 schema、版本、权限、幂等和审计。
2. `ExecutionReadiness / EvidenceSummary`：把多组事实转成机器可读的证据摘要。
3. 服务端 Workflow Compiler：先编译现有 BPMN/节点模型，不先另造一套并行执行引擎。
4. 统一 tenant/user/project/experiment scope 与对象级授权。
5. AgentRun / ToolCall / Approval / DecisionEvidence 数据模型。
6. AI 输入数据分级、外部模型路由和脱敏策略。

---

## 4. 核查范围与限制

- Git tree 文件清单 1,216 个 blob；本轮对关键执行路径进行了重点抽样，不是 1,216 个文件逐行审查。
- 重点读取 Java 主代码、Boot Controller/Service、MyBatis Mapper/迁移、关键测试类。
- 本轮没有执行本地构建、测试、数据库迁移、压力测试、断网重启测试或真实设备运行。
- 没有客户现场验收包、设备原始帧、视觉/扫码/传感器原始数据作为本轮输入。
- 结论仅反映核查基线 commit `43d137b0081045b6f86770391fb36df3c6f8d638`；后续代码变化需重新核查。

## 5. 最终判断

**中控已有能力足以进入 G1：只读 AI 助手的设计与受控原型；不建议直接进入 Agent 自动写入或自主执行阶段。**

进入 G1 前先处理 G0-01、G0-03、G0-04 的边界设计；在任何高风险 Agent 写操作上线前，必须完成 G0-05、G0-06、G0-11。G0-07 可作为低风险明确缺陷立即修复。G0-02 的完整事实聚合需要列入 G1/G2 的核心基础工作，而不是当成已完成状态功能。
