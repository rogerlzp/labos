# LabOS Workflow Runtime 架构设计 v2.0

- 日期：2026-11-19
- 版本：2.0
- 文档性质：新系统目标架构基线
- 前序讨论稿：docs/LabOS-Workflow-Runtime-重构设计-v1.0-20261119.md
- 本版定位：从零定义 Workflow Runtime 的执行语义、状态模型、并发协议、持久化协议和设备命令生命周期
- 兼容性原则：不承担旧流程定义、旧 GraphRunner 行为或旧持久化结构的兼容责任
- 实施状态：纯设计；本文不表示相关代码、数据库结构或设备协议已经实现或验证
- 部署假设：第一阶段单实例运行；设计保留未来多实例所需的身份和 fencing 扩展点，但本版不承诺分布式调度

---

# 1. 架构目标与基本立场

## 1.1 我们要构建什么

LabOS Workflow Runtime 是一个**持久化、事件驱动、Token 驱动的工作流状态机**。它负责把流程定义解释为一组可审计的执行激活，协调这些激活与设备、物料、人工操作、外部事件和持久化状态之间的关系。

它不是：

- 一个 while 循环里不断 poll ready queue 的流程解释器；
- 一个把每条分支交给独立 GraphRunner 的线程框架；
- 一个只负责把节点放进线程池的调度器；
- 一个依赖线程存活来保存流程状态的任务执行器；
- 一个把设备 ACK 当作实验动作完成的命令代理。

它必须回答并持久化以下事实：

1. 当前 Run 中有哪些控制流 Token？
2. 每个 Token 当前处于什么节点、什么循环和什么分支激活中？
3. 哪些节点激活已经执行、正在执行、等待外部事件或失败？
4. 某个并行分叉本次实际激活了哪些分支，哪些分支已经到达汇合点？
5. 某条设备命令是否已经被创建、派发、确认、完成或进入未知状态？
6. 某个节点完成后，哪些状态变化已经原子提交，哪些仍待处理？
7. 进程在任意时刻崩溃，重启后从哪些持久化事实恢复，如何避免重复物理动作？
8. 暂停、停止和失败时，系统如何证明设备和物料处于可接受的安全状态？

## 1.2 核心架构原则

| 原则 | 约束 |
|---|---|
| Definition / Runtime 分离 | 流程定义不可变；Run 状态独立 |
| Token 驱动 | 一次控制流激活由 Token 表达，不由线程或 nodeKey 表达 |
| 单写者协调 | 一个 Run 的图状态由一个串行协调器修改 |
| 执行与协调分离 | Worker 执行节点动作，不推进图 |
| 原生异步 | 等待设备/人工/外部事件时不占用 Worker |
| 显式 Join | 并行分叉和汇合通过 SplitActivation / JoinActivation 配对 |
| 显式循环 | 循环激活和迭代上下文独立建模 |
| 事务化状态变更 | 影响恢复正确性的状态必须有原子提交边界 |
| 至少一次投递 + 幂等消费 | 不假设消息只投递一次 |
| 物理动作保守恢复 | 无法证明命令结果时进入 UNKNOWN / QUARANTINED，不自动重发 |
| 可审计 | 决策、状态转移、命令身份和人工处置均有可追踪记录 |
| 确定性 | 相同定义、输入和事件序列应产生相同的控制流状态 |

## 1.3 非目标

本版不设计：

- 多实例协调器选主、分布式锁和跨实例租约续期；
- 任意非结构化 BPMN 图的完整支持；
- 任意脚本的静态变量读写推断；
- exactly-once 物理设备执行；
- 以线程中断实现设备取消；
- 让 LLM 在运行时自由决定图推进；
- 以最终状态覆盖完整事件历史；
- 为旧系统做行为兼容或迁移。

---

# 2. 总体架构

## 2.1 逻辑分层

    Workflow Definition
           |
           v
    Definition Validator / Compiler
           |
           v
       Immutable GraphPlan
           |
           v
    Run Creation Service
           |
           v
    RunCoordinator  <------ Runtime Inbox
       |    |    |                  ^
       |    |    |                  |
       |    |    +--- Lifecycle ----+
       |    |
       |    +-------- Token / Split / Join / Loop
       |
       +------------- Scheduler
                          |
                          v
                    Node Dispatcher
                          |
                    Bounded Worker Pool
                          |
                       NodeExecutor
                          |
                     NodeHandler
                          |
               Domain / Device Services
                          |
                 Node Result / Wait
                          |
                          v
                    Runtime Inbox

    Persistence:
      Run State + Token State + Activation State
      Split/Join/Loop State + Command Ledger
      Event Log + Outbox + Checkpoints

    Event Ingress:
      Device Adapter / Human Task / Timer / External System
                          |
                    Event Inbox
                          |
                    Correlation & Dedup
                          |
                    RunCoordinator

## 2.2 组件职责

### Definition Validator / Compiler

- 校验定义结构和类型；
- 将图编译成不可变 GraphPlan；
- 生成网关配对、循环区域、分支集合和静态依赖信息；
- 拒绝无法证明执行语义的图；
- 为 GraphPlan 生成稳定版本与内容哈希。

### RunCoordinator

- 一个 Run 的唯一图状态写入者；
- 消费 RuntimeMessage；
- 推进 Token、SplitActivation、JoinActivation、LoopActivation；
- 决定节点是否可调度；
- 处理节点完成、等待恢复、暂停、停止和失败；
- 将状态变化通过事务化 RuntimeRepository 提交。

Coordinator 不执行设备 IO、不等待设备完成、不在自身串行执行上下文中运行用户脚本、不阻塞等待 Worker。

### Scheduler

- 在多个 ready Token 之间选择可调度激活；
- 应用 Run 级并发额度、全局并发额度、优先级和公平性；
- 检查资源准入条件；
- 生成 Dispatch 请求；
- 不改变 Token 的图位置和 Join 语义。

### NodeExecutor / NodeHandler

- NodeExecutor 统一处理一次 NodeActivation；
- NodeHandler 实现具体节点行为；
- Handler 返回 Completed、Suspended、Failed、ManualRequired 等明确结果；
- Handler 不直接推进 Token；
- Handler 不直接修改 Coordinator 独占的图状态。

### Event Ingress

- 接收设备、人工、定时器和外部系统事件；
- 校验身份、来源、签名/协议字段和关联信息；
- 去重并持久化原始事件；
- 将已接受事件转化为 RuntimeMessage；
- 不在设备回调线程中直接推进流程图。

### RuntimeRepository

- 保存 Run、Token、Activation、Split/Join/Loop、变量版本、命令账本和事件；
- 为 Coordinator 提供事务化状态变更接口；
- 支持幂等消息处理和恢复扫描；
- 不允许业务 Handler 任意拼 SQL 修改运行时核心表。

---

# 3. 流程定义语言与图模型

## 3.1 只支持可验证的图形

新系统不以“任意有向图都能跑”为目标。流程图必须能被编译器证明其控制流语义。

V1 定义支持：

1. START；
2. END；
3. TASK；
4. BUILTIN；
5. WAIT / AWAIT_EVENT；
6. XOR_SPLIT；
7. XOR_MERGE；
8. PARALLEL_SPLIT；
9. PARALLEL_JOIN；
10. INCLUSIVE_SPLIT；
11. INCLUSIVE_JOIN；
12. LOOP_ENTER / LOOP_BACK / LOOP_EXIT；
13. MANUAL；
14. 明确声明的子流程调用（可在后续版本启用）。

节点和边必须有稳定 ID。节点显示名称不是身份。图中不允许通过数组顺序隐式决定关键执行语义，除非该顺序明确写入定义并参与版本哈希。

## 3.2 图定义对象

建议的概念模型：

    WorkflowDefinition {
      workflowId
      version
      schemaVersion
      startNodeId
      nodes[]
      edges[]
      variables[]
      policies
    }

    NodeDefinition {
      nodeId
      nodeType
      config
      inputSchema
      outputSchema
      readSet
      writeSet
      retryPolicy
      timeoutPolicy
      resourceRequirements
    }

    EdgeDefinition {
      edgeId
      sourceNodeId
      targetNodeId
      edgeType
      condition
      priority
    }

    GatewayDefinition {
      gatewayId
      gatewayType
      pairedGatewayId
      branchIds[]
      defaultBranchId
    }

上述为逻辑结构，不限定具体 JSON、数据库或 Java 类型。

## 3.3 编译产物 GraphPlan

GraphPlan 必须不可变，包含：

- 节点和边索引；
- 每个节点的入边、出边；
- GatewayPlan；
- Split/Join 配对；
- LoopPlan；
- 可达性和终止性检查结果；
- 静态变量读写依赖；
- 节点 Handler 能力要求；
- 流程定义版本、schemaVersion 和内容哈希。

GraphPlan 不包含：

- Run 状态；
- Token；
- 节点运行状态；
- 变量运行值；
- 设备锁；
- commandId；
- 线程或 Future。

## 3.4 编译期必须拒绝的定义

以下情况不得带入 Runtime 再猜：

- 节点或边引用不存在；
- 多个 START 或无合法 START；
- 存在无法到达的可执行节点（除非显式标记为 library/subflow fragment）；
- 存在无合法退出条件的循环；
- SPLIT 无法配对到唯一 JOIN；
- 并行分支存在无法归属的交叉汇合；
- 一个 JOIN 可能接收来自不同 SplitActivation 的 Token；
- 分支跳过规则不能被静态识别；
- 存在没有明确优先级/default 的互斥条件冲突；
- 节点声明的输入输出 Schema 不匹配；
- 并行变量依赖形成未定义的读写竞争；
- Handler 不存在或能力不满足；
- 设备资源声明不完整；
- 可能无限产生 Token 且没有显式限额。

编译器可以支持扩展节点，但扩展节点必须声明控制流、变量和资源契约。

## 3.5 稳定性与定义版本

每个 Run 必须绑定不可变的 definitionVersion + definitionHash + compilerVersion。

Run 创建后不读取“最新流程定义”替换自身 GraphPlan。流程升级只影响新 Run。

编译产物应可由 definitionHash 和 compilerVersion 重建。若 compilerVersion 变化导致语义变化，必须产生新的编译版本，不能静默改变历史 Run 的恢复语义。

---

# 4. 控制流形式化语义

## 4.1 Token 的定义

Token 表示一次流程控制权。一个 Token 在任意时刻最多处于一个控制位置。

    Token {
      tokenId
      runId
      state
      currentNodeId
      incomingEdgeId
      parentTokenId
      splitActivationId
      loopContextStack[]
      activationOrdinal
      version
    }

Token 不代表：

- 一个线程；
- 一个设备命令；
- 一个静态节点；
- 一个分支定义本身。

Token ID 在 Run 内唯一且不可复用。Token 创建后不允许通过修改身份字段来“变成”另一个 Token；流转应通过状态迁移或创建后继 Token 表达。

## 4.2 Token 状态机

    CREATED
       |
       v
    READY
       |
       v
    DISPATCHED
       |
       v
    RUNNING
       |----------------------|
       |          |           |
       v          v           v
    COMPLETED  SUSPENDED    FAILED
                  |
                  v
               READY
                  |
                  v
              DISPATCHED

其他终态/控制态：

    CANCELLED
    SKIPPED
    MANUAL_REQUIRED
    QUARANTINED

语义：

- READY：已具备图执行条件，等待调度；
- DISPATCHED：已被 Scheduler 接受并分配给执行器；
- RUNNING：执行器已确认开始处理；
- SUSPENDED：等待外部事件/时间/人工/资源条件，不占 Worker；
- COMPLETED：本次节点激活成功结束；
- FAILED：本次激活失败；
- SKIPPED：被确定的控制流规则跳过；
- MANUAL_REQUIRED：必须人工介入；
- QUARANTINED：状态不确定，禁止自动继续；
- CANCELLED：仅用于尚未产生不可逆外部副作用且可证明安全取消的激活。

Token 的终态不自动等同 Run 的终态。Run 是否完成由 Coordinator 检查所有 Token、Join、Loop 和待处理外部操作共同决定。

## 4.3 Token 流转

节点完成后，Coordinator 根据 NodeCompletion 和 GraphPlan 决定：

1. 当前 Token 结束当前 NodeActivation；
2. 选择一条或多条出边；
3. 更新或创建后继 Token；
4. 更新 Split/Join/Loop 运行时对象；
5. 将所有状态变化原子提交；
6. 提交成功后，Scheduler 才能看到新 READY Token。

Worker 不得在节点完成时自行调用“执行下一个节点”。

## 4.4 节点激活与尝试

必须区分四种身份：

| 身份 | 表示什么 | 生命周期 |
|---|---|---|
| nodeId | 定义中的静态节点 | 流程版本内固定 |
| activationId | 某个 Run 中该节点的一次逻辑激活 | 一次节点业务执行 |
| attemptId | 同一激活的一次执行尝试 | 可因可重试失败而增加 |
| commandId | 一次逻辑设备命令身份 | 按命令重试规则复用或新建 |

一个 NodeActivation 可包含多个 attempt，但只有一个逻辑 activationId。一个 attempt 可以涉及多个 commandId，但每条命令身份独立。

重试策略必须声明是在同一 activation 内增加 attempt，还是结束旧 activation 并创建新的业务激活。默认采用前者。

## 4.5 节点结果

    NodeResult =
      Completed(output)
      Suspended(waitRegistration)
      RetryableFailure(error, retryAfter)
      PermanentFailure(error)
      ManualRequired(reason)
      SafeCancelled(reason)
      UnknownOutcome(reason)

各结果的含义：

- Completed：节点业务动作及必要副作用已完成；
- Suspended：已建立等待登记，等待外部事实；
- RetryableFailure：当前 attempt 失败，满足明确重试策略；
- PermanentFailure：不可自动重试；
- ManualRequired：业务需要人工决策；
- SafeCancelled：已证明没有未决物理副作用，可以安全取消；
- UnknownOutcome：无法证明外部动作是否发生，必须隔离，禁止自动重试。

禁止用 null、boolean 或含义不清的 STOP 表示上述多种结果。

---

# 5. 网关、分支与 JOIN 语义

## 5.1 结构化并行

并行必须采用结构化区域：

    PARALLEL_SPLIT
       |         |
     Branch A  Branch B
       |         |
       +----+----+
            |
      PARALLEL_JOIN
            |
        Downstream

每个 SPLIT 在定义中配对唯一 JOIN。分支必须在 JOIN 前结束于该 JOIN，不允许跨越配对边界任意跳转。

## 5.2 SplitActivation

每次运行时经过 SPLIT 都创建新的 SplitActivation：

    SplitActivation {
      splitActivationId
      runId
      splitNodeId
      joinNodeId
      parentSplitActivationId
      parentTokenId
      expectedBranchIds[]
      branchStates{}
      status
      version
    }

BranchState 至少包括：

    NOT_STARTED
    ACTIVE
    ARRIVED
    SKIPPED
    FAILED
    CANCELLED
    UNKNOWN

expectedBranchIds 是本次分叉实际激活的分支集合，不是静态 JOIN 入边总数。

同一 branchId 的重复到达必须幂等。SplitActivation 关闭后不得再接受新的有效到达；迟到到达作为异常事件记录，不得重新打开已关闭 Join。

## 5.3 Parallel Split

Coordinator 在一个事务中：

1. 验证当前 Token 位于 SPLIT；
2. 创建 SplitActivation；
3. 为每个静态分支入口创建 branch Token；
4. 将所有分支标记为 ACTIVE；
5. 结束/转移父 Token；
6. 提交后才允许调度分支 Token。

分叉创建必须原子化，避免只创建一半分支后崩溃。

## 5.4 Parallel Join

当某个 branch Token 到达配对 JOIN：

1. 验证其 splitActivationId；
2. 验证 branchId 属于 expectedBranchIds；
3. 对该 branch 的 ARRIVED 状态做幂等更新；
4. 将 Token 置为等待 Join；
5. 检查所有 expectedBranchIds 是否均为 ARRIVED 或按定义可接受的 SKIPPED；
6. 满足条件时原子关闭 SplitActivation 并创建一个下游 Token；
7. 否则继续等待。

PARALLEL_JOIN 的默认规则是所有实际激活分支必须 ARRIVED。失败、未知、人工介入不等于到达；除非流程显式配置补偿/容错策略，否则 Join 不得放行。

## 5.5 Inclusive Split / Join

INCLUSIVE_SPLIT 根据条件激活一个或多个分支。运行时 expectedBranchIds 只包含本次实际选中的分支。

被条件明确排除的分支标记 SKIPPED。条件求值错误、变量未知或表达式超时不能被解释为 SKIPPED；应使节点失败或转人工。

INCLUSIVE_JOIN 只等待本次 SplitActivation 中实际激活的分支。它不通过观察“当前没有其他 Token”推测分支已结束。

## 5.6 XOR Split / Merge

XOR_SPLIT 必须恰好选择一条出边：

- 条件按稳定优先级求值；
- 同优先级冲突由编译器拒绝，或由明确配置的 first-match 语义处理；
- 可配置 default；
- 没有条件满足且无 default 时，返回定义错误/业务失败，不静默选择第一条边。

XOR_MERGE 是控制流合并点，不等待多个 Token。由于结构化控制流保证一次 XOR 选择仅产生一个活动 Token，Merge 只接受该次合法路径上的 Token。

## 5.7 普通节点多入边

普通可执行节点不允许承担隐式 AND/OR 汇合语义。

若多条控制流可能同时到达同一节点，必须显式使用 JOIN 网关。编译器拒绝存在并发多 Token 到达风险的普通多入边节点。

这条规则消除“靠 done 去重”决定是否执行的歧义。

## 5.8 分支共享节点

在配对 JOIN 之前，不允许两个并行分支共享同一可执行静态节点。JOIN 之后的下游节点由 JOIN 输出 Token 执行一次。

如果业务需要两个分支都执行相同逻辑，应在定义中复制两个节点，或调用同一个可复用子流程定义但创建独立 activation。静态节点共享不能隐式代表执行共享或执行复制。

---

# 6. 循环语义

## 6.1 LoopActivation

每次进入循环区域创建独立 LoopActivation：

    LoopActivation {
      loopActivationId
      runId
      loopId
      parentLoopActivationId
      iteration
      status
      maxIterations
      version
    }

LoopContext 是 Token 上下文栈的一部分，支持嵌套循环。

## 6.2 循环结构

V1 只支持结构化循环：

    LOOP_ENTER
        |
     Loop Body
        |
    LOOP_DECISION
      |       |
    BACK    EXIT

编译器必须证明：

- 每个 LOOP_BACK 归属唯一 LOOP_ENTER；
- 循环区域有明确出口；
- 回边不会绕过循环计数；
- 嵌套循环的父子关系无歧义；
- 循环内并行区域必须在本次迭代结束前完整汇合；
- 不允许跨迭代遗留未关闭 SplitActivation。

## 6.3 迭代推进

循环条件只在指定决策节点求值。进入下一轮时：

- iteration 增加；
- 创建/更新 LoopActivation；
- 循环内 Token 继承正确的 LoopContext；
- 新一轮激活使用新的 activationId；
- 不复用上一轮节点激活身份。

## 6.4 循环保护

必须同时有：

- 每个 LoopActivation 的 maxIterations；
- Run 级 maxTokenActivations；
- Run 级 maxNodeActivations；
- 可选的 wall-clock budget；
- 运行时异常循环检测。

达到限额是明确的 Run 失败/人工状态，不得静默截断后宣称成功。

---

# 7. 变量与数据依赖模型

## 7.1 三种变量作用域

1. Definition Constant：流程定义常量，不可在 Run 中修改；
2. Run Variable：Run 级共享变量；
3. Branch Output：某个分支激活私有输出，只能由该分支写入。

可选的 Node Output 是 activation 的不可变输出记录，不等同共享变量。

## 7.2 变量版本

Run Variable 每次成功写入都产生版本号。一次 NodeActivation 读取变量时记录 readVersion。

写入接口要求：

    write(
      runId,
      key,
      value,
      expectedVersion,
      activationId,
      idempotencyKey
    )

如果 expectedVersion 与当前版本不一致，按变量策略处理：

- 普通写：冲突失败，不覆盖；
- Compare-and-set：只有版本匹配才成功；
- Aggregate：由明确的聚合节点/操作执行；
- Append-only：追加不可变记录，不覆盖既有值。

不得把 ConcurrentHashMap 或数据库最后写入者胜出当作业务一致性规则。

## 7.3 并行读写规则

编译器使用节点 readSet/writeSet 建立数据依赖：

- 多分支并发只读同一变量：允许；
- 多分支写不同变量：允许；
- 多分支写同一 Run Variable：默认拒绝；
- 一个分支写、另一个并行分支读同一变量：默认拒绝；
- 分支局部 Branch Output 不跨分支直接可见；
- JOIN 后通过显式 Aggregate Node 汇总 Branch Output。

动态变量名、任意脚本和隐藏副作用无法静态分析时，节点必须声明为 opaque effect。Opaque 节点默认不能与存在数据依赖的分支并行。

## 7.4 累加与聚合

分支累加采用结果聚合：

    Branch A -> output.amount = 3
    Branch B -> output.amount = 5
    JOIN -> Aggregate -> Run Variable total = 8

聚合节点输入是已关闭 SplitActivation 的不可变分支输出快照。聚合写入以 aggregateActivationId 作为幂等键。

不提供隐式共享变量自增。若业务必须使用计数器，必须定义线性化顺序、幂等键、重试去重和恢复语义。

## 7.5 变量提交边界

节点输出、Run Variable 更新、NodeActivation 完成和 Token 后继状态，若共同决定下游是否可执行，应在一个 Runtime 状态事务中提交。

外部设备动作不能与数据库事务形成普通 ACID 原子事务。设备副作用必须通过 Command Ledger + Outbox/Inbox 协议处理，详见第 10、11 章。

---

# 8. 调度与并发模型

## 8.1 单 Run Coordinator

一个 Run 同一时刻只有一个有效 Coordinator 状态写入者。

单写者不等于必须为每个 Run 永久占用一个操作系统线程。推荐使用串行 Mailbox/Serial Executor：

- 收到消息后排入 Run Inbox；
- 同一 Run 消息串行处理；
- 处理器不阻塞等待 Worker、设备或定时器；
- 处理完一条消息后释放执行线程；
- 多个 Run 可共享 Coordinator 线程资源。

消息处理必须短小、有限时，不允许在 Coordinator 中执行阻塞 SQL、网络 IO 或设备调用。持久化操作可通过异步命令完成，但状态提交的线性化顺序必须由 Run mailbox 明确维护。

## 8.2 全局 WorkerPool

- 应用级共享有界线程池；
- 每 Run 有 maxInFlight；
- 每节点类型可有独立容量配额；
- 阻塞式兼容执行器必须与 Coordinator、Event Ingress 和持久化线程隔离；
- 不使用无界线程池；
- 不使用 commonPool；
- 不在 Worker 中递归提交任务并阻塞等待；
- 队列满时保留 READY Token，进入 backpressure。

## 8.3 Scheduler 公平性

Scheduler 至少支持：

- Run 间公平：避免一个 Run 长期占满全局 Worker；
- Run 内公平：避免某个分支一直抢占；
- 资源公平：设备/仪器专属容量；
- 优先级：优先级必须有上限和老化机制，防止低优先级永久饥饿；
- 背压：队列长度和等待时间受控。

建议采用两级调度：

1. Run 级选择：Weighted Fair Queueing 或 Deficit Round Robin；
2. Run 内 Token 选择：按 READY 时间、节点优先级和分支轮转。

同一 Run 的 Token 调度顺序必须稳定可审计。若多个 READY Token 同优先级，使用 readyOrdinal 作为确定性 tie-breaker。

## 8.4 资源准入

NodeActivation 在提交 Worker 前必须通过资源准入：

- Run 并发额度；
- 全局 Worker 容量；
- 节点类型容量；
- 设备/仪器资源；
- 物料占用；
- 其他声明的独占资源。

资源准入和节点开始之间存在竞争时，必须通过资源租约/占用记录解决，不得仅靠“先查空闲再执行”。

## 8.5 避免协调死锁

禁止：

- Coordinator 等待 Worker Future；
- Worker 等待同池中的子任务完成；
- Event Dispatcher 同步调用 Coordinator 并等待；
- 持有设备租约时等待无法由当前租约持有者触发的资源；
- 在数据库事务内等待设备动作。

依赖关系必须通过消息和状态机表达，而不是线程阻塞表达。

---

# 9. 节点执行与异步等待协议

## 9.1 NodeExecutor 统一入口

一次 NodeActivation 的执行请求包含：

- RunIdentity；
- definitionHash；
- activationId；
- attemptId；
- tokenId；
- nodeId；
- branchId；
- splitActivationId；
- LoopContext；
- 变量快照/版本；
- RunControlSnapshot；
- correlation/trace context。

所有身份和上下文显式传入，不依赖 ThreadLocal 保存当前 Token、当前圈次或当前分支。

## 9.2 Handler 契约

    interface NodeHandler {
      NodeType type();
      HandlerDescriptor descriptor();
      CompletionStage<NodeResult> execute(NodeExecutionContext context);
    }

HandlerDescriptor 至少声明：

- readSet / writeSet；
- resourceRequirements；
- sideEffectClass；
- idempotencyMode；
- supportsSuspend；
- supportsCompensation；
- maxConcurrency；
- cancellationSafety；
- requiredCapabilities。

Handler 不直接修改 Token、SplitActivation、JoinActivation 或 Run 生命周期状态。

## 9.3 不占 Worker 的等待

设备/人工/定时等待必须采用 continuation 模式：

1. Handler 准备等待条件；
2. Runtime 持久化 WaitRegistration；
3. 将 NodeActivation 标记 SUSPENDED；
4. 提交事务；
5. 释放 Worker；
6. 外部事件到达并完成校验、去重、关联；
7. Event Ingress 投递 WaitSatisfied 消息；
8. Coordinator 校验等待仍有效；
9. 创建新的 attempt 或恢复 continuation；
10. Scheduler 重新调度。

等待期间不得保留线程、Future 或 JVM 回调对象作为唯一恢复依据。

## 9.4 WaitRegistration

    WaitRegistration {
      waitId
      runId
      activationId
      attemptId
      waitType
      correlationKey
      expectedEventType
      commandId
      deadline
      timeoutPolicy
      status
      version
    }

waitId 在 Run 内唯一。一个等待登记必须明确：

- 等待什么；
- 哪个事件源有权满足；
- 如何验证事件；
- 是否有 deadline；
- 超时后的业务策略；
- 重复事件如何处理；
- 取消等待是否安全；
- 等待是否与 commandId 绑定。

## 9.5 定时等待

定时器不是睡眠线程。保存 dueAt 和 timerId，由 Timer Service 在到期后产生可去重的 TimerFired 事件。

系统时钟采用 UTC 持久化；需要表达实验室本地时间时，定义时区和夏令时策略。计时器重启后必须能够重新扫描未触发的 due timer。

## 9.6 超时的分类

不得把所有 timeout 混为节点超时：

- Business Deadline：业务定义的最长等待；
- Device Operation Deadline：设备协议/业务明确给出的命令期限；
- Resource Wait Deadline：等待资源的期限；
- Coordinator Health Threshold：协调器健康告警阈值；
- Pause/Stop Drain Threshold：暂停/停止收敛告警阈值。

超过期限后的动作必须由策略决定。超时不能证明设备已停止，也不能自动释放未知物理占用。

---

# 10. 设备命令生命周期与物理资源安全

## 10.1 命令账本是物理动作的身份来源

每个设备命令必须先在持久化 Command Ledger 中建立身份，再进入派发流程。

    DeviceCommand {
      commandId
      runId
      activationId
      attemptId
      deviceId
      operation
      payloadHash
      status
      idempotencyKey
      createdAt
      dispatchedAt
      acknowledgedAt
      completedAt
      result
      version
    }

commandId 是一次逻辑设备操作的身份。它与 Token、activationId、attemptId 均不同。

规则：

- 同一逻辑命令的安全重试复用 commandId；
- 新的逻辑执行必须创建新 commandId；
- payloadHash 不一致时禁止复用 commandId；
- 事件归属只认 commandId；
- 不允许用“设备当前只有一个命令”推断事件归属。

## 10.2 Command 状态机

    CREATED
       |
       v
    PREPARED
       |
       v
    DISPATCHING
       |-------------------|
       v                   v
    ACKNOWLEDGED         UNKNOWN
       |
       v
    RUNNING
       |-------------------|
       v                   v
    SUCCEEDED           FAILED

其他状态：

    REJECTED
    CANCEL_REQUESTED
    CANCELLED_CONFIRMED
    QUARANTINED

ACKNOWLEDGED 仅表示设备/网关确认接收，不代表动作完成。

UNKNOWN 表示系统不能证明命令是否已被设备执行。UNKNOWN 不可自动重发，必须通过设备查询、事件核验或人工处置转为确定状态。

## 10.3 Outbox 派发协议

数据库和设备之间没有普通 ACID 事务。采用持久化 Outbox：

1. Runtime 事务中创建 Command Ledger 记录；
2. 同一事务写入 CommandOutbox；
3. Outbox Dispatcher 读取待派发记录；
4. 发送前记录派发尝试和 attempt metadata；
5. 设备适配器按协议发送；
6. 收到 ACK/最终事件后写入 Event Inbox；
7. Event Processor 按 commandId 幂等更新 Command Ledger；
8. Coordinator 收到命令状态变化消息；
9. 节点在满足完成条件后返回 Completed。

如果进程在“设备收到命令”与“本地记录已发送”之间崩溃，恢复后必须把命令视为可能已发送，不能直接创建新 commandId 重发。

## 10.4 设备协议能力分级

每个 Device Adapter 必须声明：

| 能力 | 说明 |
|---|---|
| SupportsCommandIdEcho | 最终事件是否回显 commandId |
| SupportsQueryByCommandId | 能否按 commandId 查询执行结果 |
| SupportsIdempotentRetry | 设备是否支持同一命令身份安全重试 |
| SupportsCancel | 是否支持取消 |
| CancelHasPhysicalConfirmation | 是否能确认物理停止 |
| SupportsStateReadback | 是否能读取设备当前状态 |
| EventOrderingGuarantee | 事件是否有序 |
| EventDeliveryGuarantee | 事件是否可能重复/丢失 |

没有 commandId 回显且无法可靠查询的设备，不能获得自动关联能力。若事件无法可靠归属，进入 UNATTRIBUTED/UNKNOWN 处理，不得弱匹配到“看起来正在运行”的命令。

## 10.5 资源租约

资源租约独立于线程和 Future：

    ResourceLease {
      leaseId
      resourceId
      ownerRunId
      ownerActivationId
      ownerCommandId
      status
      acquiredAt
      heartbeatAt
      expiresAt
      fencingToken
      version
    }

单实例阶段也使用显式 leaseId/owner identity，不把线程锁持有状态当作业务租约。

租约过期不等于物理设备空闲。若设备状态未知，租约转为 QUARANTINED 或需人工核验，不得自动释放并让新命令进入。

## 10.6 命令完成条件

设备节点只有在以下条件全部满足后才可 Completed：

1. 命令身份已持久化；
2. 设备资源占用已正确建立；
3. 命令派发状态可判定；
4. 最终结果按 commandId 可靠关联；
5. 设备状态满足操作后置条件；
6. 物料/板位/耗材等必要副作用已提交；
7. Command Ledger 和节点结果状态一致；
8. 下游激活与节点完成在 Runtime 事务中原子提交。

设备 ACK 不满足上述条件。

---

# 11. 持久化模型与事务边界

## 11.1 设计目标

持久化模型必须回答：

- 崩溃前最后一个已提交控制流状态是什么？
- 某条消息是否已经处理？
- 某个 Token 是否已推进？
- 某次分叉是否已完整创建所有分支？
- 某个 Join 是否已释放？
- 某个节点是否已完成并提交输出？
- 某条命令是否可能已发送到设备？
- 某个事件是否已经被关联并消费？

不能仅靠 Run 最终状态和日志重建这些事实。

## 11.2 核心持久化实体

建议逻辑实体：

| 实体 | 用途 |
|---|---|
| workflow_definition | 不可变定义版本 |
| workflow_compiled_plan | 编译产物和编译器版本 |
| workflow_run | Run 总状态与版本 |
| runtime_token | Token 当前状态 |
| node_activation | 节点逻辑激活 |
| node_attempt | 激活执行尝试 |
| split_activation | 并行分叉运行时实例 |
| split_branch_state | 每个分支状态 |
| join_activation | Join 等待和释放状态 |
| loop_activation | 循环实例和迭代 |
| run_variable | Run 变量当前值与版本 |
| branch_output | 分支局部输出 |
| wait_registration | 外部等待登记 |
| device_command | 设备命令账本 |
| resource_lease | 资源占用/租约 |
| runtime_inbox | 已接收消息及处理状态 |
| runtime_event | 不可变运行事件 |
| runtime_outbox | 待发送的内部/外部消息 |
| command_outbox | 待派发设备命令 |
| runtime_checkpoint | 快照/恢复检查点 |
| manual_intervention | 人工介入与处置记录 |

表结构、索引、分区和归档策略另行形成物理数据模型，不在此文档中伪造具体 DDL。

## 11.3 事务化消息处理

Coordinator 消费 RuntimeMessage 时，必须执行：

1. 验证 messageId 是否已处理；
2. 加载 Run 和相关 Token/Activation 版本；
3. 校验消息对应的状态转移是否合法；
4. 计算状态变化；
5. 在一个 DB 事务内写入状态变化；
6. 写入 Runtime Event；
7. 写入需要发送的 Outbox 消息；
8. 标记 Inbox 消息已处理；
9. 提交事务；
10. 提交成功后才触发下游异步派发。

消息处理必须幂等。重复消息不能重复创建 Token、释放 Join、提交变量或创建命令。

## 11.4 乐观版本与单写者

即使单实例单写者，也为 Run 和关键 Runtime 实体保留 version。

更新采用 compare-and-set：

    UPDATE workflow_run
       SET version = version + 1, ...
     WHERE run_id = ? AND version = ?

更新行数为 0 表示状态版本冲突，必须重新加载并处理，不能盲目覆盖。

未来多实例扩展必须增加 Coordinator epoch/fencingToken；本版单实例不把普通 version 误称为分布式 fencing。

## 11.5 Event Log 与状态快照

Runtime Event 是不可变事实记录。状态表是当前投影。Checkpoint 是加速恢复的快照。

- Event 不可被后续状态覆盖；
- Snapshot 必须带 event sequence / cursor；
- Snapshot 之后的事件用于增量恢复；
- Event 投影可重建状态，但外部物理动作不能仅靠重放 Runtime Event 再执行；
- 命令派发由 Command Ledger 决定，不由流程事件重放直接触发。

## 11.6 事件序号

建议每 Run 有单调递增的 eventSeq，由 RunCoordinator 的事务化消息处理分配。

- 同一 Run 的已提交 Runtime Event 序号严格递增；
- 不承诺全局跨 Run 有序；
- 事务回滚不产生已提交事件；
- 数据库序列可能有缺口时，不把无缺口作为语义要求；
- Event Outbox 消费顺序按 Run/eventSeq 保序；
- 并发 Worker 只提交完成消息，不直接分配 Run eventSeq。

## 11.7 Outbox / Inbox

所有跨进程或跨线程边界都视为可能重复、延迟、乱序。

Outbox 解决“状态已提交但消息未发出”；Inbox 解决“消息重复到达或消费后崩溃”。

Outbox 至少包含：

- outboxId；
- aggregateId；
- messageType；
- payload；
- createdAt；
- attempts；
- nextAttemptAt；
- status；
- lastError。

Inbox 至少包含：

- messageId；
- source；
- receivedAt；
- payloadHash；
- status；
- processedAt；
- rejectionReason。

消息必须有稳定幂等身份。不能以消息到达时间或线程顺序作为去重依据。

---

# 12. 崩溃恢复协议

## 12.1 恢复基本原则

恢复的对象是持久化状态机，不是恢复某个 Java 线程或 Future。

进程重启后：

1. 加载所有非终态 Run；
2. 获取单实例运行权；
3. 校验 definitionHash/compilerVersion；
4. 加载 Run、Token、Activation、Split/Join/Loop 状态；
5. 扫描 Inbox 中未处理消息；
6. 扫描 Outbox 中未发送消息；
7. 扫描 SUSPENDED 等待；
8. 扫描未完成命令和资源租约；
9. 对设备 UNKNOWN 状态进行核验或隔离；
10. 重建 READY Token 的调度队列；
11. 仅在恢复检查通过后重新开放调度。

## 12.2 恢复分类

| 崩溃时状态 | 恢复策略 |
|---|---|
| READY，尚未派发 | 可重新调度 |
| DISPATCHED，但 Worker 未确认开始 | 通过 dispatch lease 判断；无法确认时回滚为 READY 或进入检查 |
| RUNNING，纯计算且无外部副作用 | 按 Handler 幂等策略重试 |
| RUNNING，可能已有外部副作用 | UNKNOWN，先核验 |
| SUSPENDED，等待定时器 | 重建 Timer |
| SUSPENDED，等待设备事件 | 保留 waitId/commandId，恢复事件监听 |
| 命令 CREATED/PREPARED | 按 outbox 协议派发 |
| 命令 DISPATCHING | 视为可能已发送，核验后决定 |
| 命令 ACKNOWLEDGED/RUNNING | 查询设备或等待最终事件 |
| 命令 UNKNOWN | 禁止自动重发 |
| Join 部分到达 | 从持久化 branch state 继续等待 |
| Loop 中间态 | 恢复 LoopContext 和 iteration |
| Pause Draining | 继续收敛，不恢复新派发 |
| Stop Draining | 继续停止收敛，不恢复新派发 |
| QUARANTINED | 需要明确核验/人工处置 |

## 12.3 Worker Dispatch Lease

Worker 提交任务时创建 DispatchLease，包含 activationId、attemptId、worker lease、startedAt 和 heartbeat。

Worker 心跳只证明软件执行器存活，不证明设备动作状态。

DispatchLease 过期后：
- 无外部副作用的纯计算可按幂等策略重新执行；
- 有外部副作用的节点必须先核验副作用；
- 不得只因 worker lease 过期就释放设备资源。

## 12.4 恢复期间的消息竞争

恢复期间外部事件仍可能到达。Event Ingress 可以持久化 Inbox，但 Coordinator 在 Run 恢复完成前不处理状态推进。

恢复完成和重新开放调度必须通过 Run 状态版本控制，避免恢复扫描与实时事件并发造成重复激活。

## 12.5 Checkpoint

Checkpoint 不是唯一事实源。它是某个 eventSeq 之后状态投影的快照。

创建 Checkpoint 时必须保证：
- Run 和相关 Token/Activation 版本一致；
- split/join/loop 状态完整；
- 变量版本一致；
- command/wait 状态与 event cursor 一致；
- checkpoint.eventSeq 对应已提交事件。

---

# 13. 暂停、停止、失败与人工介入

## 13.1 Run 生命周期状态机

    CREATED
       |
       v
    STARTING
       |
       v
    RUNNING
      |   |   |
      |   |   +----> STOP_REQUESTED
      |   +--------> PAUSE_REQUESTED
      +------------> FAILED

    PAUSE_REQUESTED
       |
       v
    PAUSE_DRAINING
       |
       v
    PAUSED
       |
       v
    RESUME_REQUESTED
       |
       v
    RUNNING

    STOP_REQUESTED
       |
       v
    STOP_DRAINING
       |
       +----> STOPPED
       +----> FAILED
       +----> MANUAL_REQUIRED
       +----> QUARANTINED

具体状态可以按产品语义收敛，但 REQUESTED / DRAINING / FINAL 必须区分。

## 13.2 暂停

暂停意味着停止开始新的节点激活，并让在途动作到达安全边界。

- Coordinator 原子切换到 PAUSE_REQUESTED；
- 不再接受新的 Worker Dispatch；
- READY Token 保留；
- RUNNING 节点按其 cancellationSafety 执行安全收尾；
- SUSPENDED 节点保留 WaitRegistration；
- 设备命令继续等待真实结果；
- 所有激活都到达可恢复安全点后进入 PAUSED。

暂停协调阈值仅告警，不构成设备取消或释放租约依据。

## 13.3 停止

停止是一个有序收敛过程，不是把所有线程 cancel。

- 停止新派发；
- 未开始的 READY/DISPATCHED 激活按安全条件取消；
- 已运行节点按 handler/device 的安全策略处理；
- 设备命令根据协议决定是否发出取消请求；
- 只有设备确认物理停止，才可记录 CANCELLED_CONFIRMED；
- UNKNOWN 命令和资源进入隔离；
- 所有必要收尾完成后才形成最终 Run 状态。

## 13.4 失败

任一不可恢复分支失败时，默认 Run 进入 STOP_REQUESTED：

- 不再派发新 Token；
- 已派发动作进入收敛；
- Join 不因失败自动放行；
- 失败原因和首个失败节点不可被后续失败覆盖；
- 所有在途动作状态确定后进入 FAILED；
- 物理状态不确定时优先 QUARANTINED / MANUAL_REQUIRED。

是否支持“某个分支失败但其他分支继续”必须由流程级容错策略显式声明，默认不支持。

## 13.5 人工介入

MANUAL_REQUIRED 不是错误日志，而是一个可追踪的工作项。

记录：
- 触发原因；
- 关联 Run/Token/Activation/Command/Resource；
- 当前物理状态；
- 允许的人工动作；
- 禁止的人工动作；
- 操作人、时间、理由；
- 处置结果；
- 后续恢复检查。

人工确认不能绕过 commandId、设备状态和资源租约核验。

---

# 14. 事件模型与确定性

## 14.1 事件类型

建议至少区分：

- RunCreated / RunStarted；
- TokenCreated / TokenReady / TokenDispatched；
- NodeActivationStarted / NodeActivationCompleted；
- NodeSuspended / NodeResumed；
- NodeFailed / NodeManualRequired；
- SplitOpened / BranchActivated / BranchArrived / BranchSkipped；
- JoinReleased；
- LoopEntered / LoopIterationAdvanced / LoopExited；
- VariableWritten / BranchOutputCommitted；
- CommandCreated / CommandDispatched / CommandAcknowledged；
- CommandCompleted / CommandFailed / CommandUnknown；
- ResourceLeaseAcquired / ResourceLeaseReleased / ResourceQuarantined；
- PauseRequested / RunPaused；
- StopRequested / RunStopped；
- RunFailed / RunQuarantined；
- ManualInterventionOpened / ManualInterventionResolved。

事件名应描述已经发生的事实。请求类事件与完成类事件必须分开。

## 14.2 确定性边界

相同的：
- definitionHash；
- 初始变量；
- 外部事件序列；
- 设备结果；
- 调度 tie-breaker 规则；

应产生相同的控制流状态和事件序列（时间戳、随机 trace ID 等非语义字段除外）。

禁止在 Coordinator 中使用未固定顺序的 Map 遍历来决定出边选择或 Token 调度。所有排序规则必须显式且稳定。

## 14.3 事件乱序

事件可能重复、延迟或乱序。

- commandId 是设备命令事件关联的必要身份；
- eventId/sourceEventId 用于事件去重；
- command 状态迁移必须验证前置状态；
- 迟到事件不能覆盖更高版本的状态；
- 旧 command 的迟到事件不得清除新 command 的资源租约；
- 无法关联的事件进入隔离队列。

## 14.4 可观测性

必须能够按 Run 查询完整时间线，并关联到：
- Token；
- NodeActivation；
- Attempt；
- Split/Join；
- Loop；
- commandId；
- resource lease；
- inbox/outbox messageId；
- 人工处置记录。

监控视图必须区分：
- 排队时间；
- Worker 执行时间；
- 设备等待时间；
- 资源等待时间；
- 人工等待时间；
- Coordinator 处理延迟；
- 事件投递延迟。

不能把节点总耗时误读为 CPU 执行时间。

---

# 15. 安全性与不变量

以下是不允许被实现细节破坏的核心不变量。

## 15.1 控制流不变量

1. 每个 Token 在任意时刻最多处于一个有效控制位置。
2. 一个 Token 不得同时被两个 Worker 执行。
3. 一个 NodeActivation 不得被两个 attempt 同时标记为当前有效 attempt，除非明确实现 speculative execution（本版禁止）。
4. 一个 SplitActivation 的 expectedBranchIds 在创建后不可改变。
5. 一个 branchId 对同一 SplitActivation 最多成功到达一次。
6. 一个 JoinActivation 最多释放一次。
7. JOIN 只能消费属于同一 SplitActivation 的分支到达。
8. LoopContext 的迭代只能单调推进。
9. 普通多入边节点不得隐式承担 JOIN。
10. Run 进入 PAUSED 后不得有新节点开始执行。

## 15.2 持久化不变量

1. 状态变化与对应 Runtime Event 在同一事务提交。
2. 需要发出的消息与状态变化在同一事务写入 Outbox。
3. Inbox 消息只有在处理结果提交后才标记完成。
4. 重复 messageId 不得重复产生业务效果。
5. NodeActivation 完成、输出提交和 Token 推进必须有一致的提交边界。
6. SPLIT 创建所有分支 Token 必须原子提交。
7. JOIN 关闭与下游 Token 创建必须原子提交。
8. 命令账本和命令 Outbox 创建必须原子提交。
9. 不可确定的设备命令不能自动重发。
10. Checkpoint 必须引用确定的 eventSeq/cursor。

## 15.3 设备安全不变量

1. Worker 结束不代表设备动作结束。
2. ACK 不代表物理完成。
3. 租约过期不代表设备空闲。
4. Future cancel 不代表设备停止。
5. 事件归属只认 commandId。
6. 无法归属的事件不推进节点。
7. 设备 UNKNOWN 时不允许自动释放独占资源。
8. 迟到事件不得影响新命令。
9. 物理状态确认前不得重新分配冲突资源。
10. 人工处置必须留痕并明确后续安全状态。

## 15.4 并发不变量

1. Run 图状态只有 Coordinator 写入。
2. Worker 只返回不可变结果。
3. 同一 Run 的 RuntimeMessage 串行应用。
4. 全局 WorkerPool 有界。
5. 每 Run in-flight 有界。
6. 设备资源独占由 lease/adapter 保证，不由线程池保证。
7. Event Ingress 不直接执行图推进。
8. Event Dispatcher 不阻塞等待 Coordinator。
9. 所有跨线程/进程消息可去重。
10. 队列满不得静默丢失关键消息。

---

# 16. 故障模型与处理矩阵

| 故障 | 检测 | 处理 | 是否自动重试 |
|---|---|---|---|
| Worker 进程内异常，纯计算 | attempt 失败 | 记录失败 | 按策略 |
| Worker 崩溃，无外部副作用 | DispatchLease 过期 | 重建 attempt | 可 |
| Worker 崩溃，可能已派发设备命令 | Command Ledger | UNKNOWN/查询设备 | 不直接重发 |
| DB 暂时不可用 | 事务失败/连接池指标 | 保留消息/重试事务 | 可幂等重试 |
| Outbox 发送失败 | attempts/error | 指数退避 | 可 |
| Inbox 重复消息 | messageId 唯一约束 | 返回既有结果 | 不重复执行 |
| 设备 ACK 丢失 | command 查询/事件 | UNKNOWN 后核验 | 不直接重发 |
| 设备最终事件重复 | sourceEventId/eventId | 去重 | 不重复推进 |
| 设备最终事件迟到 | commandId + 状态版本 | 记录迟到事件并判定 | 不覆盖新命令 |
| Coordinator 崩溃 | Run heartbeat/lease | 重启后恢复 mailbox | 状态驱动 |
| Join 分支重复到达 | branch 状态唯一约束 | 幂等忽略并审计 | 不重复释放 |
| Timer 重复触发 | timerId 唯一约束 | 幂等处理 | 不重复推进 |
| 资源状态未知 | 设备状态核验失败 | QUARANTINED | 不自动释放 |
| 变量版本冲突 | expectedVersion 不匹配 | 冲突处理/失败 | 按节点策略 |
| Outbox 堆积 | queue age/depth | 降载/告警 | 不丢关键事件 |

---

# 17. 安全、权限与审计

Runtime 不应把“能执行节点”视为无限权限。

每个 NodeHandler 只能访问其声明的能力：
- 指定设备/设备组；
- 指定物料类型；
- 指定变量读写集合；
- 指定外部 API；
- 指定文件/脚本资源；
- 指定人工操作权限。

运行时需要校验：
- Run 所属项目/实验室；
- 流程定义授权；
- 操作人启动权限；
- 节点所需设备权限；
- 人工恢复/跳过/取消权限；
- 变量敏感级别；
- 审计记录访问权限。

流程定义中脚本、表达式和外部命令必须受沙箱、超时、资源和权限限制。任意脚本不能直接访问 RuntimeRepository 或设备连接池。

---

# 18. 测试策略与验收标准

## 18.1 编译器测试

- 正确 SPLIT/JOIN 配对；
- 未配对网关拒绝；
- 交叉分支拒绝；
- 普通节点多入边拒绝；
- 循环回边归属；
- 嵌套循环；
- 并行分支内循环；
- 循环内并行完整汇合；
- 不可达节点；
- 无出口循环；
- XOR 条件优先级/default；
- Inclusive 条件求值错误；
- 变量读写冲突；
- Opaque Handler 并行限制；
- definitionHash 稳定性。

## 18.2 Token / Coordinator 状态机测试

- Token 每次只处于一个状态；
- 重复 NodeCompletion 不重复推进；
- 过期 attempt 的 completion 被拒绝；
- Token 只能从合法前置状态迁移；
- Run PAUSE_REQUESTED 后不再新派发；
- Coordinator 消息乱序/重复时状态仍正确；
- 单 Run 消息串行应用；
- 多 Run mailbox 互不阻塞。

## 18.3 SPLIT/JOIN 测试

- 两分支真实重叠；
- Join 等待所有实际分支；
- 条件跳过分支不等待；
- 条件求值错误不能当作跳过；
- 同一分支重复到达幂等；
- Join 只释放一次；
- Join 后共享节点只执行一次；
- 嵌套 SplitActivation；
- 循环中多次经过同一静态 Split；
- 失败分支不伪造到达；
- UNKNOWN 分支不放行 Join；
- 崩溃后部分到达状态可恢复。

## 18.4 调度测试

- 全局 Worker 上限；
- 每 Run in-flight 上限；
- 节点类型容量；
- 同设备互斥；
- 不同设备并发；
- Run 间公平性；
- Run 内分支公平性；
- 队列满背压；
- 长时间等待不占 Worker；
- 无嵌套阻塞死锁；
- Scheduler 重启后 READY Token 重建。

## 18.5 持久化和故障注入

在以下每个事务边界前后强制 kill/restart：

- Run 创建；
- SPLIT 创建；
- Worker Dispatch；
- NodeActivation Started；
- Node 输出提交；
- Token 推进；
- JOIN 释放；
- Loop iteration 增加；
- Command Ledger 创建；
- Command Outbox 发送前后；
- Device ACK 处理；
- 最终设备事件处理；
- Pause/Stop 状态变化；
- Checkpoint 创建。

验收不是“进程重启后还能跑起来”，而是：
- 不丢 Token；
- 不重复释放 Join；
- 不重复提交变量；
- 不重复产生逻辑命令；
- 不把 UNKNOWN 当成功；
- 不错误释放设备租约；
- 所有恢复决策可审计。

## 18.6 设备模拟器与真机验证

先使用具备故障注入能力的设备模拟器，覆盖：
- ACK 丢失；
- 最终事件丢失；
- 重复事件；
- 乱序事件；
- 延迟事件；
- 设备重启；
- 网络分区；
- 命令查询结果不确定；
- 取消请求成功但状态回读失败。

模拟器通过不等于真机通过。真机验收必须有设备安全负责人确认，且先在非危险动作/隔离环境中执行。

## 18.7 可量化验收

至少测量：
- Token 创建/推进吞吐；
- Coordinator mailbox 延迟；
- Worker queue wait；
- Worker 执行时间；
- 外部等待时长；
- Event Ingress 到 Coordinator 延迟；
- Outbox 最大年龄；
- Inbox 重复率；
- Join 等待时间；
- 资源等待时间；
- Run 恢复耗时；
- 故障后 UNKNOWN/QUARANTINED 数量。

不能以吞吐提升抵消设备安全或状态一致性缺陷。

---

# 19. 实施路线

不采用“先照旧代码做串行版本”的路线。按新 Runtime 的正确模型分阶段交付。

## Stage A：定义与形式化语义

交付：
- Workflow Definition Schema；
- GraphPlan Schema；
- 编译器规则；
- Token/Activation 状态机；
- SPLIT/JOIN 规则；
- Loop 规则；
- 变量读写契约；
- 节点 Handler 契约。

退出条件：
- 状态转移表完整；
- 所有支持的图形有正反例；
- 不支持的图形可被编译器拒绝；
- 不存在靠运行时猜测的控制流语义。

## Stage B：内存状态机与确定性测试

交付：
- 纯状态转移核心；
- Coordinator Mailbox；
- Token Engine；
- Split/Join/Loop Engine；
- Scheduler；
- Fake Handler。

退出条件：
- 状态机单元测试和属性测试通过；
- 随机合法图生成测试通过；
- 重复/乱序消息测试通过；
- 同一输入事件序列产生相同控制流结果。

此阶段可使用内存 Repository，但接口必须与持久化实现一致。

## Stage C：持久化与恢复

交付：
- Runtime 数据模型；
- RuntimeRepository；
- Inbox/Outbox；
- Event Log；
- Checkpoint；
- 崩溃恢复扫描器。

退出条件：
- 事务边界明确；
- 故障注入覆盖关键提交点；
- 重复消息不会重复产生业务效果；
- 恢复状态与崩溃前最后提交状态一致。

## Stage D：异步设备与资源协议

交付：
- Command Ledger；
- Command Outbox；
- Device Adapter 能力声明；
- Event Ingress；
- Resource Lease；
- WaitRegistration；
- UNKNOWN/QUARANTINED 处置。

退出条件：
- ACK 与完成分离；
- commandId 严格关联；
- 未知命令不自动重发；
- 资源租约不会因线程/进程退出而错误释放；
- 模拟器故障注入通过。

## Stage E：生产级调度与运行控制

交付：
- 全局有界 WorkerPool；
- 多 Run 公平调度；
- 节点类型容量；
- Pause/Stop/Manual；
- 运维监控与告警；
- 限流和背压。

退出条件：
- 压测达到容量目标；
- 多 Run 无明显饥饿；
- 队列过载时系统可控降载；
- 暂停/停止在途设备动作符合安全协议。

## Stage F：受控真机验证

交付：
- 设备白名单；
- 非危险动作测试计划；
- 现场回滚/隔离预案；
- 真机测试报告；
- 设备安全负责人签署。

退出条件：
- 每种设备协议能力得到验证；
- 命令身份与事件归属可靠；
- 物理状态核验满足要求；
- 无未解释的命令重复、丢失或资源误释放。

---

# 20. 关键架构决策记录

| ID | 决策 | 选择 | 不选方案及原因 |
|---|---|---|---|
| ADR-001 | 图状态所有权 | 单 Run Coordinator 单写者 | 多 worker 直接改图状态会产生竞态 |
| ADR-002 | 并行表达 | Token + SplitActivation | 每分支一个 GraphRunner 会复制 Run 状态并引入合并问题 |
| ADR-003 | JOIN | 显式配对、按实际激活分支汇合 | done 并集/max 无法表达循环和嵌套激活 |
| ADR-004 | 普通多入边 | 编译期拒绝隐式汇合 | 避免 OR/AND 语义猜测 |
| ADR-005 | 循环 | LoopActivation + LoopContext | 全局 visits 不能表达嵌套循环与并行 |
| ADR-006 | 节点扩展 | Handler Registry + Descriptor | 避免中心化巨型 if/else |
| ADR-007 | 等待 | SUSPENDED continuation | 阻塞 Worker 会耗尽执行容量 |
| ADR-008 | 调度 | 全局有界池 + 分层公平调度 | 每 Run 独立线程池资源不可控 |
| ADR-009 | 变量 | Run Variable + Branch Output + 显式聚合 | 隐式共享写导致竞争与重试重复 |
| ADR-010 | 事件 | Inbox/Outbox + Event Log | 直接 listener 调用不能保证可靠投递 |
| ADR-011 | 持久化 | 状态与事件同事务提交 | 只存最终状态无法恢复 |
| ADR-012 | 设备命令 | Command Ledger + commandId | 不能用 Token 或 nodeId 关联设备事件 |
| ADR-013 | 资源 | 显式 ResourceLease | 线程锁不适合跨异步等待和崩溃恢复 |
| ADR-014 | 不确定物理状态 | UNKNOWN / QUARANTINED | 自动重试可能造成重复物理动作 |
| ADR-015 | 兼容策略 | 新定义、新状态模型 | 不为旧实现的历史偶然行为背负约束 |

---

# 21. 必须在详细设计阶段关闭的问题

本文给出架构基线，但以下参数和协议细节必须在进入对应实施阶段前关闭：

| ID | 问题 | 关闭时点 | 必须提供的证据 |
|---|---|---|---|
| WRT2-01 | Workflow Definition 的正式 Schema 与版本升级策略 | Stage A | JSON Schema/类型定义及正反例 |
| WRT2-02 | Inclusive Join 是否需要支持复杂非结构化分支 | Stage A | 形式化语义；否则明确拒绝 |
| WRT2-03 | Loop 与并行嵌套的完整支持矩阵 | Stage A | 图形矩阵与属性测试 |
| WRT2-04 | Node Handler side-effect/idempotency 分类 | Stage A/D | Handler Descriptor 规范 |
| WRT2-05 | Coordinator 状态提交是同步事务还是持久化命令队列 | Stage B/C | 线性化和故障模型 |
| WRT2-06 | 数据库类型、隔离级别、索引和事件分区 | Stage C | 物理数据模型和并发测试 |
| WRT2-07 | Outbox 投递语义、退避、死信和人工重放 | Stage C | 重复/失败注入测试 |
| WRT2-08 | Device Adapter commandId 能力分级与不支持设备策略 | Stage D | 每种设备协议能力表 |
| WRT2-09 | ResourceLease 的设备侧核验、续期和隔离流程 | Stage D | 设备模拟器/真机验证 |
| WRT2-10 | Pause/Stop 各 NodeType 的 cancellationSafety | Stage E | 节点类型安全矩阵 |
| WRT2-11 | WorkerPool 容量、Run 配额和公平性权重 | Stage E | 压测与资源预算 |
| WRT2-12 | 多实例 fencing 与 Coordinator epoch | 多实例阶段 | 独立分布式一致性设计 |

这些问题不妨碍确定架构方向，但在对应阶段没有关闭前，不得把相关能力标记为已完成。

---

# 22. 交付定义

必须严格区分以下状态：

| 交付状态 | 定义 |
|---|---|
| Architecture Approved | 架构决策已评审通过 |
| Definition Engine Implemented | Schema、Compiler、GraphPlan 已实现 |
| Runtime State Machine Implemented | Token/Coordinator/Join/Loop 状态机已实现 |
| Persistence Implemented | 状态、事件、Inbox/Outbox 已持久化 |
| Recovery Verified | 崩溃恢复故障注入通过 |
| Device Protocol Verified | 设备命令和事件关联通过模拟器/真机验证 |
| Production Ready | 安全、容量、权限、监控、运维和回滚均通过验收 |

不能把“设计文档完成”称为 Runtime 完成，也不能把“单元测试通过”称为设备安全验证完成。

---

# 附录 A：建议的 Runtime 消息

    RuntimeMessage =
      StartRun(runId, messageId)
      DispatchAccepted(activationId, attemptId, dispatchId)
      DispatchRejected(activationId, reason)
      NodeStarted(activationId, attemptId)
      NodeCompleted(activationId, attemptId, output)
      NodeSuspended(activationId, attemptId, waitRegistration)
      NodeFailed(activationId, attemptId, failure)
      WaitSatisfied(waitId, sourceEventId, payload)
      WaitExpired(waitId, timerId)
      DeviceCommandUpdated(commandId, commandVersion)
      PauseRequested(runId, requestId, reason)
      StopRequested(runId, requestId, reason)
      ResumeRequested(runId, requestId, reason)
      ResourceAvailabilityChanged(resourceId, version)
      ManualDecisionRecorded(interventionId, decision)

每条消息必须有 messageId、aggregateId、occurredAt、source 和 schemaVersion。消息 payload 不可携带任意可执行代码。

# 附录 B：关键状态转移的事务边界

| 操作 | 同一事务内必须完成 |
|---|---|
| 创建 Run | Run + 初始 Token + RunCreated Event |
| 并行分叉 | SplitActivation + 全部分支 Token + SplitOpened Event |
| 节点完成 | Activation Completed + Output/Variable + Token 后继 + Event |
| Join 到达 | BranchState Arrived + Token WaitJoin + Event |
| Join 释放 | Split Closed + 下游 Token + JoinReleased Event |
| 循环推进 | Loop iteration + 新 Token/Activation + Event |
| 设备命令创建 | Command Ledger + Command Outbox + Event |
| 事件消费 | Inbox processed + Command/Wait 状态 + Runtime Message Outbox |
| 暂停完成 | Run PAUSED + 所有可恢复检查点 + Event |
| 停止完成 | Run terminal state + 在途命令/资源最终状态 + Event |

# 附录 C：关键术语

| 术语 | 定义 |
|---|---|
| GraphPlan | 编译后的不可变流程图 |
| Token | 一次流程控制权 |
| NodeActivation | 静态节点在某 Run 中的一次逻辑执行 |
| Attempt | 一次具体执行尝试 |
| SplitActivation | 某次并行分叉的运行时实例 |
| JoinActivation | 对某次 SplitActivation 的汇合状态 |
| LoopActivation | 某次循环的运行时实例 |
| Run Variable | Run 级共享变量 |
| Branch Output | 分支私有输出 |
| WaitRegistration | 外部等待的持久化登记 |
| Command Ledger | 设备命令身份与状态账本 |
| ResourceLease | 物理资源的显式占用记录 |
| Inbox | 已接收消息的持久化去重入口 |
| Outbox | 与状态事务原子提交的待发送消息 |
| Runtime Event | 已提交的不可变运行事实 |
| Checkpoint | 某个事件游标处的状态快照 |
| UNKNOWN | 无法证明外部动作结果 |
| QUARANTINED | 状态不确定，禁止自动继续或释放资源 |

---

## 最终架构原则

**Definition 静态不可变；Run 状态由 Token 表达；Coordinator 单写者；节点异步执行；等待不占 Worker；SPLIT/JOIN 和循环显式建模；状态、事件与 Outbox 原子提交；设备命令独立记账；UNKNOWN 必须隔离；恢复以持久化事实为准。**

这才是 LabOS 新系统应当建立的 Workflow Runtime 内核。
