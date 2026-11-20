# Workflow Runtime G1：事务性图推进与 GraphCoordinator 实施记录

- 日期：2026-11-20
- 分支：\`feat/workflow-runtime-t24-integration-20261119\`
- 阶段：G1 实现中；本记录不代表 G1 最终验收
- 架构：单 Kernel 进程、单 Runtime 实例、单 PostgreSQL 17
- RuntimeHost：仍未启用；不接设备、不接 Adapter / Outbox / Inbox 新执行闭环

## 1. 本轮实现

### 1.1 Repository 事务性图推进接口

\`WorkflowRuntimeRepository\` 新增：

- \`findReadyGraphTokens\`：按 Run 读取有限批次 READY Token。
- \`findCompletedGraphActivations\`：查找已成功、已消费 Token、尚未产生后继 Token 的 Worker Activation。
- \`advanceGraphToken\`：原子处理一个 READY 控制流 Token 的激活与推进。
- \`advanceCompletedGraphActivation\`：在 Attempt 成功后推进其后继；通过 source Token 的 parent-child 历史判断是否已推进，支持进程在 Attempt 完成与图推进之间退出后的安全重试。
- \`loadGraphVariableSnapshot\`：在一个 Repeatable Read 快照中读取 Run input 与 flow_variable。
- \`putFlowVariable\`：在 Run 锁内写入持久化 flow_variable，并追加 Runtime Event。

一次控制流推进在同一数据库事务中包含：

1. 锁定 Run，并验证 RUNNING + OPEN。
2. 锁定 READY Token（或锁定已完成的 Activation / Token）。
3. 创建控制节点 Activation（Worker Activation 已由既有 Attempt 生命周期创建）。
4. 创建下游 Token，或创建 Split / Branch / Join 状态。
5. 更新 Activation 与 Token 终态。
6. 判断 Run 是否满足 SUCCEEDED 条件。
7. 追加 Runtime Event。

任何一步失败都会使整个事务回滚。Repository 不调用 Worker、设备、网络或外部服务。

### 1.2 GraphCoordinator

新增 \`coordinator/GraphCoordinator.java\`：

- 从 Run 的不可变 definition snapshot 解码 GraphPlan。
- 校验 GraphPlan 节点/边引用与 G1 支持边界。
- 读取持久化 Run input 和 flow_variable；显式传入变量仅作为覆盖层。
- 推进 START、END、XOR / Parallel / Inclusive Split 和 Join。
- 读取已成功的 Worker Attempt Activation，并为其创建后继 Token。
- 循环推进控制流节点直到没有可推进 Token。
- TASK / BUILTIN Token 留给既有 Worker 执行路径，不在 Coordinator 中直接执行。
- Run 不处于 RUNNING + OPEN 时保持不推进。

GraphCoordinator 不持有 SQL，不创建线程，不调度 Worker，不调用设备，不做分布式协调。WorkflowRunFactory 在创建 Run 前调用 `GraphCoordinator.validateGraphPlan`；Coordinator 推进时还会对持久化 snapshot 再校验一次。条件表达式语法也在创建 Run 前校验，变量是否存在及最终结果是否为 boolean 在求值时校验。

## 2. 网关语义

### XOR Split

- 对非 default 出边按 GraphPlan 顺序求值。
- 恰好一个条件匹配时选择该边。
- 无条件边作为候选边。
- 没有匹配边时选择 BPMN default flow。
- 多个候选同时匹配时拒绝推进，不静默随机选择。

### Parallel Split

- 所有出边均被激活。
- 不接受 conditionExpression 或 default flow。
- 至少两个分支。

### Inclusive Split

- 激活所有条件为 true 的分支。
- 若没有分支匹配，选择 default flow。
- 允许仅激活一个分支；该分支仍有独立 Split / Branch 身份，后续 Inclusive Join 的 expected count 为 1。

### Parallel / Inclusive Join

- 每个到达 Join 的 Token 必须携带所属 Split 与 Branch 身份。
- Join 按 Split ID + Join Gateway node key 关联；Repository 校验 joinNodeKey 必须等于当前 Token 的 node_key。
- 每个 Branch 只能到达一次。
- 只有 arrived_branch_count == expected_branch_count 时才释放一个后继 Token。
- Join 状态 RELEASED 和后继 Token 创建在同一事务中。

### XOR Join

- 不执行同步等待；每个到达 Token 独立经过 Join。
- 外层 Split / Branch 身份继续沿顺序边传递，供后续真正的同步 Join 使用。

### End Event

- 普通 End 消费 Token。
- Split 分支直接到 End 时记录为 \`$END\` 到达。
- 同一 Split 不允许混合“部分分支直接 End、部分分支等待 Join”，也不允许不同分支进入不同 Join。直接 End 的分支使用空 Join key 作为终止标记，避免与 BPMN 节点 ID 冲突。
- 全部 Token 与 Attempt 都终结后才将 Run 置为 SUCCEEDED。

## 3. G1 当前明确拒绝的情况

- Parallel / Inclusive Split 嵌套在尚未汇合的 Parallel / Inclusive 分支内部。
- TASK / BUILTIN 有多个出边或条件出边；G1 目前只允许一个无条件后继。
- Parallel Split 带条件边或 default flow。
- XOR Split 多个候选同时匹配，或没有匹配且没有 default。
- 同一 Split 的分支进入不同 Join，或混合 End 与 Join。
- 同步 Join 收到没有 Split / Branch 身份的 Token。
- 图推进时 Run 不是 RUNNING + OPEN。

这些情况必须 fail closed，不允许 Coordinator 猜测 BPMN 语义。

## 4. 事务与幂等边界

- 控制节点的 Token → Activation → Successor Token 在同一个 Repository 事务中完成。
- Split、Branch、子 Token 在同一事务内完整创建。
- Join 的 Branch arrival、计数更新、Join RELEASED、后继 Token 创建在同一事务内完成。
- Worker Attempt 完成与图推进是两个事务；中间退出时，成功 Activation 会留在可查询状态。
- 后续 Coordinator 扫描成功 Attempt Activation；只要源 Token 尚无子 Token，就创建后继；如果已有子 Token，必须验证后继 node_key 与 Split / Branch lineage 完全一致才返回 REPLAYED；不同目标重放按幂等冲突拒绝。
- 同一 Run 由既有串行 Mailbox 模型负责串行化；Repository 仍通过 Run 行锁和状态条件保护事务边界。

## 5. 测试覆盖

新增/扩展：

- \`GraphCoordinatorTest\`：XOR 条件/默认路径、Inclusive 多分支/单分支、重复 default、Parallel 条件边拒绝、Join Gateway 身份、TASK 条件出边拒绝、嵌套同步 Split 拒绝。
- \`WorkflowRuntimeRepositoryTest\` PostgreSQL 17 集成测试：
  - 无效 Split 推进时 Activation / Token 全部回滚。
  - Parallel Split 两分支经过 Join，只有最后一个 Branch 到达时释放一次。
  - 单分支 Inclusive Split 可以正常到达 End 并关闭 Split。
  - 同一 Split 混合 End 与 Join 时拒绝，且事务回滚。
  - GraphCoordinator 使用持久化 flow_variable 选择 XOR 分支。
  - Worker Attempt 成功后 Coordinator 路由到 End 并完成 Run。
  - 成功 Activation 路由后重试返回 REPLAYED，不重复创建子 Token。

截至代码提交 `67d2385d`，Workflow Runtime PostgreSQL 17 工作流通过：62 tests、0 failures、0 errors，包含 14 个 Repository PostgreSQL 集成测试。随后 `c5d1239c` 仅增加 per-Run SerialMailbox 调用契约注释；该提交的最新 CI 仍需最终确认。CI 全绿仍不替代源码语义复核。

## 6. 尚未完成 / 不属于本轮

- GraphCoordinator 尚未注册到 RuntimeHost，也未启用真实调度。
- 未接入新的 Worker 调度闭环、EventIngress、OutboxDispatcher、DeviceAdapter、Inbox EventDispatcher。
- 未实现设备 E2E、故障注入 E2E、设备 readback / reconciliation。
- 未实现失败 Attempt 的 BPMN Error / Boundary Event / Retry / Compensation 语义。
- 未实现并行分支输出的自动变量合并策略；条件求值使用 Run input、flow_variable 和显式变量覆盖。
- 不支持嵌套 Parallel / Inclusive Split。
- 不接管旧 GraphRunner，不迁移真实设备流量。
- 不引入分布式 Coordinator、Worker Lease、Fencing、多实例恢复。
- PR #5 保持 Draft，不合并。

## 7. 后续 G1 验收要求

1. 最新 HEAD 的 Kernel CI 与 PostgreSQL 17 Schema / Repository 集成工作流全绿。
2. 源码复核确认锁顺序、事务原子性、Join 只释放一次、Replay 不重复创建 Token。
3. 对 XOR / Parallel / Inclusive 的正向路径和拒绝路径做完整测试。
4. 明确确认 G1 支持边界；不把本轮已拒绝的嵌套 Split 或 TASK 条件出边描述成已支持。
5. G1 验收前不进入恢复机制阶段，不启用 RuntimeHost，不接设备。
