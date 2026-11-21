# Workflow Runtime V1：BPMN → GraphPlan → Run Snapshot 实施记录

- 日期：2026-11-19
- 实施分支：`feat/workflow-runtime-t24-integration-20261119`
- 设计约束：单 Runtime Host + PostgreSQL 17；不引入分布式协调

## 1. 已核实的 BPMN 权威存储

Kernel 当前以 `workflow.graph->>'xml'` 保存原始 BPMN XML。证据：

- `GraphMapper.selectGraphXml(tenantId, workflowId)`：从 `workflow` 表按 tenant + workflow 读取 XML。
- `GraphMapper.updateWorkflowGraph(...)`：保存原始 XML 到 `graph.xml`。
- `GraphMapper.selectWorkflowNodes(workflowId)`：读取节点投影；节点投影包含 node_key、能力码、protocol_name、资源绑定等运行元数据。
- `GraphMapper.selectEdges(workflowId)`：读取 `workflow_edge` 投影，但 `insertEdge` 只写 from_key/to_key/edge_type，不保存 BPMN sequenceFlow id、conditionExpression 或 gateway default。

因此：
1. 原始 BPMN XML 是控制流语义的权威来源。
2. `workflow_node` 是节点身份/能力/绑定等关系型投影，可为编译器补充元数据。
3. `workflow_edge` 是不完整的关系投影，不能用它重建 GraphPlan。
4. 保存侧 `BpmnGraphParser` 的 ParsedEdge 只有 from/to；旧执行侧 `WorkflowGraphParser` 另行读取条件和 default。这是历史双解析器的信息不对称，新 Runtime 不继续依赖这两种内部对象。

## 2. 新 Runtime 的分层契约

- BPMN：可编辑、可变更的设计定义。
- WorkflowDefinitionSource：Kernel 到 Runtime 的只读端口，按 Runtime tenant UUID + workflow id 读取 BPMN XML 与节点元数据。
- BpmnGraphCompiler：纯编译逻辑，不读数据库、不执行设备命令。
- GraphPlan：带 schemaVersion/compilerVersion/source hash 的不可变执行图。
- definition_snapshot：Run 创建时保存 GraphPlan JSON；同一 Run 后续恢复只使用 snapshot，不重新读取最新 BPMN。
- Run/Token/Activation/Attempt：执行态，不混入 GraphPlan。

V1 GraphPlan 包含：
- 节点：key/name/kind/sourceType/protocol/resourceId/methodParams/stageCode/capabilityCode/workflowNodeId；
- 边：key/from/to/condition/defaultFlow；
- 来源：definitionKey/definitionVersion/bpmnSha256/compilerVersion/schemaVersion。

## 3. 本次新增代码

- `graph/GraphPlan.java`：不可变 record 与枚举。
- `graph/GraphPlanCodec.java`：依赖无关的确定性 JSON 序列化和 SHA-256。
- `graph/BpmnGraphCompiler.java`：安全解析 BPMN XML，保留 sequenceFlow 条件与 default，生成 GraphPlan；遇到 V1 不支持的可执行元素、重复 ID、悬空边、缺少设备任务协议/能力绑定时 fail closed。
- `definition/WorkflowDefinitionSource.java`：只读定义来源端口。
- `definition/WorkflowRunFactory.java`：读取定义、编译 GraphPlan，并调用 Repository.createRun 固化 definition snapshot。

## 4. 当前 V1 编译器明确支持/拒绝

支持：
- startEvent / endEvent；
- task / userTask / serviceTask / scriptTask / sendTask / receiveTask；
- custom 前缀任务（含存量 customDevice）；
- exclusiveGateway / parallelGateway / inclusiveGateway；
- sequenceFlow、conditionExpression 文本、gateway default 引用；
- 节点 protocolName、resource、methodParams、stageCode 和关系型节点元数据。

明确拒绝：
- subProcess、callActivity、businessRuleTask、manualTask；
- intermediateCatchEvent / intermediateThrowEvent / boundaryEvent；
- eventBasedGateway / complexGateway / transaction / adHocSubProcess；
- 重复节点/边 ID、缺失节点引用；
- V1 暂要求恰好一个 startEvent，且该 startEvent 恰好一条出边；
- 设备任务缺少 protocolName，或既没有资源绑定也没有 capabilityCode。

拒绝不等于 BPMN 本身非法，而是当前 Runtime V1 子集不支持；必须在创建 Run 前失败，不可静默忽略。

## 5. 尚未完成的集成项（不能提前标记完成）

1. **Kernel DefinitionSource Adapter：已实现首版。** `WorkflowRuntimeKernelConfiguration` 组合 tenant-scoped `selectWorkflowHeader`、`selectGraphXml` 与新增 `selectRuntimeWorkflowNodeMetadata`。元数据只映射 capabilityCode / workflowNodeId；protocol/resource/methodParams/stageCode 从原始 BPMN XML 解析。
2. **Tenant identity bridge：已实现首版。** Repository `resolveTenantId` / `findTenantKey` 维护 stable tenant_key；`WorkflowRuntimeTenantResolver` 以 `kernel-tenant:<bigint>` 解析；`LegacyKernelWorkflowDefinitionSource` 严格拒绝非正数/格式错误的旧租户 key。
3. **Kernel Spring Boot 装配：已实现首版。** Kernel POM 声明普通 Maven 依赖；`ensure-deps.sh` 与 `compile-kernel.sh` 构建 Runtime JAR 并放入既有 classpath；`WorkflowRuntimeKernelConfiguration` 注入 Kernel 同一个 DataSource / PlatformTransactionManager。Repository 主构造函数不再自行创建事务管理器。
4. **Snapshot read API / codec：基础已实现。** 创建路径固化 GraphPlan；Repository 提供按 Run 读取与非终态 Run 枚举；Codec 提供 JSON 反序列化及 schemaVersion/hash 校验。Coordinator 尚未接入这些 API。
5. **GraphCoordinator**：还需读取 Token/Activation 状态并以 Repository 事务原语推进；Worker 不得修改图状态。
6. **条件表达式**：本次只保留条件文本；尚未接入受限、确定性的表达式求值器。条件表达式未实现前，不允许把 XOR/Inclusive 分支判定宣称为可执行。
7. **Outbox/Adapter/Inbox、Mock E2E、故障注入**：仍按 T24-C/D/E 推进；真机 readback/reconciliation 仍不在本轮完成范围。

## 6. 重要 tenant 设计约束

Runtime DDL 的 tenant 主键是 UUID，另有唯一 `tenant_key`。Kernel 现有 Mapper 使用 bigint tenantId。当前 Kernel 集成通过 `kernel-tenant:<bigint>` 稳定 key 持久化映射到 Runtime UUID；禁止直接 cast 或每次启动随机生成 UUID。

## 7. 已完成的 Kernel 集成与下一阶段顺序

已完成：
1. Kernel 注册 workflow-runtime JAR；保留既有 javac/out 构建方式。
2. MyBatis reader tenant-scoped 读取 BPMN XML、Header、节点元数据。
3. Spring Boot 注入共享 DataSource + PlatformTransactionManager；Repository 主构造函数显式依赖共享事务管理器。
4. 稳定映射 `kernel-tenant:<id>` → Runtime UUID，创建 Run 时调用 WorkflowRunFactory。

下一阶段：
5. 增加 Spring ApplicationContext 装配测试，验证真实 Bean 注册与 Mapper statement binding。
6. RuntimeHost 启动时分页读取非终态 Run snapshot，构造 GraphCoordinator；完成 Token/Activation 只读装载 API。
7. 实现串行 GraphCoordinator 状态推进与受限表达式求值；先覆盖顺序节点，再覆盖 XOR/Parallel/Inclusive Split/Join，未支持语义继续编译拒绝。
8. 接入 Outbox/Adapter/Inbox，完成 Mock E2E 与故障注入。

## 8. 验收

- 编译器单测覆盖正常任务/网关/default/节点元数据、拒绝不支持子流程、拒绝悬空边。
- CI 结果以本分支最新 SHA 对应的 GitHub Actions 为准；本记录不把正在运行的 CI 写成 PASS。
