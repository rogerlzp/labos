# Workflow Runtime 嵌入 Kernel：实施记录 v1.0

- 日期：2026-11-19
- 分支：feat/workflow-runtime-t24-integration-20261119
- 目标：将独立 Maven 模块 workflow-runtime 作为普通 JAR 嵌入 LabOS Kernel Spring Boot 进程；不拆独立服务、不增加第二个连接池/事务管理器。
- 部署仍为：单 Runtime Host 进程 + PostgreSQL 17。

## 1. 已实现

### 1.1 Maven 模块与 Kernel 构建链

- labos-kernel/pom.xml 增加普通 Maven 依赖 com.labos:workflow-runtime:1.0.0-SNAPSHOT。
- scripts/ensure-deps.sh 保证 Maven 可用；在依赖缺失、需要解析 Kernel Maven 依赖时，先安装 workflow-runtime 模块。
- scripts/compile-kernel.sh 每次编译前执行 mvn -f workflow-runtime/pom.xml -DskipTests install，把 JAR 复制到 labos-kernel/lib/spring/。
- Kernel 保持既有 javac → labos-kernel/out 构建和运行布局；JAR 由既有 lib/spring/* classpath 加载。
- 不提交二进制产物。

### 1.2 共享 DataSource 与事务管理器

- WorkflowRuntimeRepository 主构造函数现在必须同时接收 DataSource 和 PlatformTransactionManager。
- Repository 的写事务模板和 Snapshot 只读/Repeatable Read 模板使用传入的同一个事务管理器。
- Kernel 配置从既有 DataSourceConfiguration 注入唯一 Hikari DataSource 与唯一 PlatformTransactionManager。
- Kernel 集成路径不创建新的 DataSourceTransactionManager，不创建新的连接池。
- PostgreSQL 集成测试新增外层事务回滚验证：Repository 内部 resolveTenantId 必须加入外层事务；外层回滚后租户映射不得残留。
- 新增 WorkflowRuntimeKernelConfigurationSelfTest：启动 AnnotationConfigApplicationContext，验证唯一 DataSource / PlatformTransactionManager 和 Runtime Bean 组合；断言未注册 RuntimeHost；解析 GraphMapper.xml，并对 Runtime Header/XML/Runtime metadata 三个 statement 执行 BoundSql 参数映射与 SQL 租户过滤断言（21 个检查点）。

### 1.3 Kernel MyBatis BPMN DefinitionSource Adapter

新增 WorkflowRuntimeKernelConfiguration，注册：
- WorkflowRuntimeRepository；
- BpmnGraphCompiler；
- WorkflowDefinitionSource；
- WorkflowRunFactory；
- 稳定租户身份解析器 WorkflowRuntimeTenantResolver。

读取路径：
1. Runtime UUID → Repository findTenantKey；
2. LegacyKernelWorkflowDefinitionSource 只接受 kernel-tenant:<positive bigint>；
3. Kernel MyBatis GraphMapper.selectWorkflowHeader(tenantId, workflowId) 读取版本/更新时间；
4. GraphMapper.selectGraphXml(tenantId, workflowId) 读取原始 BPMN XML；
5. 新增 selectRuntimeWorkflowHeader(tenantId, workflowId)，仅读取 Runtime 所需字段，并排除 deleted_at 非空的软删除流程；
6. 新增 selectRuntimeWorkflowNodeMetadata(tenantId, workflowId)，同时按 tenant_id + workflow_id 过滤，只取 node_key、node id、capability code；
7. Header、XML、节点元数据三次 MyBatis 读取被包装在同一个 REQUIRES_NEW + REPEATABLE_READ + readOnly 事务，避免并发保存期间拼接出混合版本；
8. 编译 GraphPlan；
7. WorkflowRunFactory 将 GraphPlan snapshot 写入 Runtime Repository。

新增 Mapper 查询，不改变旧的 selectWorkflowNodes(workflowId) 等历史查询行为。

### 1.4 定义版本与租户隔离

- Runtime UUID 通过唯一稳定 key kernel-tenant:<legacy bigint> 映射。
- BPMN definition key 采用 <runtimeTenantUUID>:<workflowId>，避免不同租户同 workflow id 冲突。
- BPMN definition version 使用 workflow.version@workflow.updated_at。原因：现有 updateWorkflowGraph 更新 graph 与 updated_at，但不保证业务 version 每次保存都递增。
- Definition Hash 仍是 BPMN 原始 XML 的 SHA-256；version 不代替 Hash。
- 读取 XML、Header、节点元数据均 tenant-scoped；不使用不带 tenant 的旧边查询重建控制流。

## 2. 明确没有做的事情

- 没有把 Runtime 拆成独立进程或微服务。
- 没有创建第二个 DataSource、连接池或事务管理器。
- 没有将 RuntimeHost 伪装成已投入运行：RuntimeHost 的真实启动装配仍等待 EventIngress、OutboxDispatcher、DeviceAdapter、EventDispatcher、GraphCoordinator 等闭环组件完成。
- 没有将旧 workflow_edge 投影当成 BPMN 控制流来源。
- 没有实现网关条件表达式求值器；编译器只保留条件文本。
- 没有启用新 Runtime 替换现有 GraphRunner，也没有迁移生产执行流量。

## 3. 关键代码

| 文件 | 职责 |
|---|---|
| workflow-runtime/.../WorkflowRuntimeRepository.java | Runtime 持久化、共享事务管理器 |
| workflow-runtime/.../WorkflowDefinitionSource.java | 只读定义端口 |
| workflow-runtime/.../LegacyKernelWorkflowDefinitionSource.java | bigint tenant → stable tenant key 适配 |
| workflow-runtime/.../WorkflowRunFactory.java | 编译并创建带 snapshot 的 Run |
| workflow-runtime/.../BpmnGraphCompiler.java | BPMN → GraphPlan |
| labos-kernel/.../WorkflowRuntimeKernelConfiguration.java | Spring Bean 装配 |
| labos-kernel/.../GraphMapper.java | tenant-scoped Mapper 声明 |
| labos-kernel/.../GraphMapper.xml | BPMN XML / 节点元数据 SQL |
| labos-kernel/pom.xml | 普通 Maven 模块依赖 |
| scripts/ensure-deps.sh | Maven 与依赖准备 |
| scripts/compile-kernel.sh | 构建 Runtime JAR 后再编译 Kernel |

## 4. 构建与验证

标准入口仍是：

    ./scripts/compile-kernel.sh

该入口先准备 Maven/依赖，再安装 workflow-runtime JAR 到本机 Maven 仓库并复制到 Kernel classpath，最后按既有流程 javac 编译 Kernel main + boot 并同步资源。

Runtime PostgreSQL 17 测试：

    RUN_RUNTIME_PG_IT=1 mvn -B -f workflow-runtime/pom.xml test

Kernel 全量 CI 还包括前端与现有纯逻辑门禁。不要只凭 Maven Runtime 测试通过就宣称 Kernel 集成全绿。

## 5. 审核必须关注的风险

1. Maven 依赖安装顺序与 ensure-deps.sh 的缓存分支是否在全新机器/已有依赖机器都成立。
2. compile-kernel.sh 生成的 Runtime JAR 是否始终与当前源码一致，classpath 是否有重复旧版本。
3. Kernel Spring Bean 是否确实注入同一个 DataSource / PlatformTransactionManager；Repository 是否还存在隐式创建事务管理器的路径；定义读取事务是否在同一 REPEATABLE_READ snapshot 中。
4. MyBatis Mapper XML namespace、方法名、参数名和结果 Map key 是否完全匹配。
5. Runtime Header/XML/metadata 查询是否均租户限定；Header 是否过滤软删除；REPEATABLE_READ 是否确实覆盖三次查询；是否有 workflow_node 与 workflow 表租户归属不一致的异常需要显式拒绝。
6. workflow.version + updated_at 是否足以作为 definition_version；Definition Hash 是否始终由原始 XML 计算。
7. 节点元数据只补 capabilityCode / workflowNodeId；protocol/resource/methodParams/stageCode 是否始终从 BPMN XML 来，且和存量 XML 兼容。
8. Kernel 配置是否被 @SpringBootApplication 扫描；Mapper 是否被 @MapperScan 注册。
9. 是否有未完成的 RuntimeHost 生命周期组件被错误注册/提前开放调度（本阶段不应有）。
10. 是否影响旧 GraphRunner、旧 Mapper 查询或旧构建/测试入口。

## 6. 验收状态

CI 结果以本分支最新 SHA 的 Actions 为准。此文档不把正在运行或历史运行写成 PASS。Runtime PostgreSQL 测试、Kernel CI、人工源码审查必须分别给结论。


## 7. 最新 CI 验证（PR HEAD 0de6619c1c5791e6793734544da924b658320ba8）

- Kernel CI #556：success。
  - Workflow Runtime JAR 构建 / 安装 + Kernel main/boot javac + 资源同步：success。
  - Java 纯逻辑单测：1551 / 0。
  - 前端 TypeScript：0 错误。
  - 设备事件入站端点：17 / 0。
  - Sidecar 设备事件全链路：8 / 0。
  - 门禁总结果：失败 0。
- Workflow Runtime PostgreSQL 17 #227：success。
  - Schema Contract、双会话并发、CON-01..14、事务原子性 / Inbox 冲突：success。
  - Java Workflow Runtime Repository 集成测试：28 tests，0 failures，0 errors。
  - Bootstrap repeatable isolation：success。

CI 链接：
- https://github.com/rogerlzp/labos/actions/runs/44558687714
- https://github.com/rogerlzp/labos/actions/runs/44558687751

注意：这证明构建链和当前自动化门禁通过；仍不等于 Spring ApplicationContext 启动测试或 Runtime 端到端设备执行闭环已经完成。


## 8. Spring / MyBatis Smoke Test

新增纯逻辑自测：labos-kernel/src/test/java/com/labos/boot/WorkflowRuntimeKernelConfigurationSelfTest.java，并加入 scripts/test-all.sh 的 PURE 列表。该测试不连接数据库、不启动 RuntimeHost，验证 Runtime Spring Bean composition、唯一 DataSource / PlatformTransactionManager、未提前创建 RuntimeHost，以及 GraphMapper.xml 三个关键 statement 的 MyBatis 解析绑定。最新 CI 结果以新提交对应 Actions 为准。
