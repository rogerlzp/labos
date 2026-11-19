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


---

# 23. Runtime State Machine 规范（Normative）

本章是 v2.0 的规范性补充。若前文的概念性描述与本章的状态、转移条件或事务边界存在不一致，以本章为准。实现、测试和评审均必须按本章定义，不允许各模块自行解释状态含义。

本章将状态拆成五个互相独立但通过事务关联的状态机：

1. Run Lifecycle：Run 是否允许继续产生新工作；
2. Token：控制流权利目前处于哪个阶段；
3. NodeActivation / Attempt：一次节点逻辑激活及其执行尝试；
4. Split / Branch / Join / Loop：结构化控制流上下文；
5. Command / ResourceLease / Wait：外部副作用、资源和等待状态。

**禁止用一个 status 字段同时表达以上不同维度。** 例如 NodeActivation=SUSPENDED 不代表 Token 已经完成；Command=ACKNOWLEDGED 不代表 NodeActivation=COMPLETED；Run=PAUSE_DRAINING 不代表设备已经暂停。

## 23.1 身份、版本与状态所有权

每个运行时对象必须有不可变身份和单调递增版本：

| 对象 | 主身份 | 版本所有者 |
|---|---|---|
| Run | runId | RunCoordinator |
| Token | tokenId | RunCoordinator |
| NodeActivation | activationId | RunCoordinator |
| NodeAttempt | attemptId | RunCoordinator 创建，Executor 回报事实 |
| SplitActivation | splitActivationId | RunCoordinator |
| BranchActivation | branchActivationId | RunCoordinator |
| JoinActivation | joinActivationId | RunCoordinator |
| LoopActivation | loopActivationId | RunCoordinator |
| WaitRegistration | waitId | RunCoordinator |
| DeviceCommand | commandId | Command Service / Coordinator 协议 |
| ResourceLease | leaseId | Resource Service |
| Inbox message | messageId | Inbox 唯一约束 |
| Outbox message | outboxId | 创建方事务，Dispatcher 更新投递状态 |

所有来自 Worker、设备、Timer、人工界面的消息必须携带对象身份及其生成时的版本/attempt 信息。接收方不得仅凭 nodeId、设备当前状态或时间接近程度猜测对象身份。

### 23.1.1 版本与 fencing 的含义

- objectVersion 用于拒绝基于旧状态的更新；
- attemptId 用于拒绝旧执行尝试的迟到结果；
- coordinatorEpoch 用于拒绝旧 Coordinator owner 的消息；
- lease fencingToken 用于拒绝旧资源持有者的操作；
- commandId 用于唯一关联一次逻辑设备命令。

这些身份不能互相替代。单实例部署也必须校验 objectVersion 和 attemptId。多实例部署启用时，必须额外校验 coordinatorEpoch；不能把数据库 version 当成完整的分布式 fencing。

## 23.2 Run Lifecycle 状态机

Run 状态集合：

- CREATED
- STARTING
- RUNNING
- PAUSE_REQUESTED
- PAUSE_DRAINING
- PAUSED
- RESUME_REQUESTED
- STOP_REQUESTED
- STOP_DRAINING
- SUCCEEDED
- FAILED
- STOPPED
- MANUAL_REQUIRED
- QUARANTINED

终态只有 SUCCEEDED、FAILED、STOPPED。MANUAL_REQUIRED 和 QUARANTINED 是非自动推进状态，必须经显式处置才能离开；其间不得调度新的普通节点。

### 23.2.1 合法转移表

| 当前状态 | 触发 | 前置条件 | 下一状态 | 原子副作用 |
|---|---|---|---|---|
| CREATED | StartRun | 定义版本可用；初始变量校验通过 | STARTING | 建立启动记录 |
| STARTING | StartCommitted | 初始 Token 创建成功 | RUNNING | RunStarted Event；READY Token 可调度 |
| STARTING | StartFailed | 初始化不可恢复 | FAILED | 记录失败原因 |
| RUNNING | PauseRequested | 有权限；requestId 未处理 | PAUSE_REQUESTED | 关闭新 Dispatch 准入 |
| PAUSE_REQUESTED | DrainStarted | 已停止接收新的 Dispatch | PAUSE_DRAINING | 标记待收敛对象 |
| PAUSE_DRAINING | DrainComplete | 无 RUNNING/DISPATCHED 激活；无未确认的必须收敛动作；等待登记已持久化 | PAUSED | RunPaused Event |
| PAUSED | ResumeRequested | 人工/系统有权恢复；恢复前检查通过 | RESUME_REQUESTED | 建立恢复请求 |
| RESUME_REQUESTED | ResumeCommitted | 设备、租约、等待、命令状态均核验通过 | RUNNING | RunResumed Event；重新开放调度 |
| RUNNING | StopRequested | requestId 未处理 | STOP_REQUESTED | 关闭新 Dispatch 准入 |
| PAUSE_REQUESTED / PAUSE_DRAINING / PAUSED / RESUME_REQUESTED | StopRequested | 未终结 | STOP_REQUESTED | Stop 优先级高于 Pause |
| STOP_REQUESTED | DrainStarted | 已停止接收新的 Dispatch | STOP_DRAINING | 建立停止收敛集合 |
| STOP_DRAINING | DrainComplete | 所有副作用已确定且安全收敛 | STOPPED | RunStopped Event |
| STOP_DRAINING | UnresolvedPhysicalState | 有无法核验的命令/资源 | QUARANTINED | 隔离资源和命令 |
| STOP_DRAINING | ManualDispositionRequired | 需业务/实验人员决策 | MANUAL_REQUIRED | 创建人工工作项 |
| RUNNING | AllWorkCompleted | 无活动 Token、未关闭 Split/Loop、未完成 Activation、未决 Wait/Command | SUCCEEDED | RunSucceeded Event |
| RUNNING | FatalFailure | 失败策略为终止 Run | STOP_REQUESTED | 记录首个致命失败，停止新派发 |
| 任意非终态 | SafetyViolation | 发现可能危害设备/物料安全的不变量破坏 | QUARANTINED | 关闭调度并隔离相关资源 |
| MANUAL_REQUIRED | ManualResolutionCommitted | 决策合法、证据齐全、后置条件验证通过 | 按决策进入 RUNNING / STOP_DRAINING / FAILED | 审计人工处置 |
| QUARANTINED | QuarantineCleared | 物理状态已核验，授权人批准 | 按明确恢复决策进入 PAUSED / STOP_DRAINING / FAILED | 记录核验依据 |

任何未列出的 Run 状态转移均非法，必须拒绝并记录 RuntimeTransitionRejected，不得“尽量继续”。

### 23.2.2 Run 调度闸门

只有 RUNNING 状态允许创建新的普通 NodeAttempt Dispatch。

以下状态一律禁止新 Dispatch：

- CREATED、STARTING；
- PAUSE_REQUESTED、PAUSE_DRAINING、PAUSED、RESUME_REQUESTED；
- STOP_REQUESTED、STOP_DRAINING；
- MANUAL_REQUIRED、QUARANTINED；
- SUCCEEDED、FAILED、STOPPED。

已经被 Worker 接受但尚未确认开始的 DISPATCHED 激活，必须按 23.4 的规则收敛；不能因为 Run 状态变化就假定它没有开始。

### 23.2.3 Pause 与 Stop 竞争

- Stop 优先于 Pause；
- Pause 请求已提交后收到 Stop：Run 转 STOP_REQUESTED，不得再进入 PAUSED；
- Pause 已完成后收到 Stop：PAUSED → STOP_REQUESTED；
- Stop 已提交后收到 Pause：拒绝 Pause 请求，返回 Run 正在停止；
- 相同 requestId 重复提交：返回第一次处理结果；
- 不同 requestId 的重复请求：按当前状态幂等返回或拒绝，不重复创建收敛任务。

## 23.3 Token 状态机：控制流权利

为避免把“节点执行状态”和“Token 位置”混在一起，Token 只表达控制流权利，不表达 Worker 的物理运行状态。

Token 状态集合：

- READY：位于一个可执行节点，尚未被调度；
- RESERVED：已被 Scheduler 原子预留，尚未交给 Worker；
- IN_FLIGHT：已提交 Dispatch，等待 Worker 接受/开始/返回结果；
- WAITING：当前控制流停在 Join、Wait 或外部条件上；
- CONSUMED：该 Token 已被某次节点激活消费，不再可调度；
- TERMINAL：该 Token 路径已明确结束；
- INVALIDATED：因 Run 终止、分支取消等原因被撤销，且无未决外部副作用。

### 23.3.1 Token 合法转移

| 当前 | 事件 | 前置条件 | 下一状态 |
|---|---|---|---|
| CREATED | TokenReady | 所属 Run 允许初始化/推进 | READY |
| READY | ReserveToken | Run=RUNNING；配额/资源准入成功 | RESERVED |
| RESERVED | DispatchCommitted | Dispatch outbox 已同事务写入 | IN_FLIGHT |
| RESERVED | ReservationExpired | 未写入 Dispatch outbox，且确认无 Worker 接收 | READY |
| IN_FLIGHT | NodeStarted | activationId/attemptId 匹配当前派发 | IN_FLIGHT（Token 不变） |
| IN_FLIGHT | NodeSuspended | WaitRegistration 已持久化 | WAITING |
| IN_FLIGHT | NodeCompleted | 完成结果已在事务中提交 | CONSUMED；事务同时创建后继 Token 或 TERMINAL |
| IN_FLIGHT | NodeFailed | 失败结果已提交 | 按 retry/failure policy 转 READY（新 attempt）或 TERMINAL |
| WAITING | WaitSatisfied | waitId 有效、事件合法、尚未消费 | READY |
| WAITING | JoinReleased | 所属 SplitActivation 已原子关闭 | CONSUMED；创建一个下游 READY Token |
| READY / RESERVED | BranchSkipped | 分支在控制流规则中明确未激活或可跳过 | TERMINAL |
| READY / RESERVED | CancelBeforeDispatch | 无 Dispatch、无副作用 | INVALIDATED |
| IN_FLIGHT | CancelRequested | 不直接改变 Token，进入 activation/command 收敛 | IN_FLIGHT |
| 任意非终态 | RunTerminalized | 无未决副作用且路径已关闭 | TERMINAL 或 INVALIDATED |

**重要：** NodeStarted 不改变 Token 状态；它改变 NodeAttempt 状态。Token 在节点执行期间保持 IN_FLIGHT。NodeActivation 成功完成时 Token 被消费，并在同一事务中创建后继 Token。这样不会出现一个 Token 既处于 RUNNING 又同时 READY 的双重含义。

### 23.3.2 Token 消费与后继创建

节点完成时，Coordinator 必须在同一事务内完成：

1. 校验 Token=IN_FLIGHT；
2. 校验 activationId、attemptId 为当前有效身份；
3. 校验 NodeActivation 可完成；
4. 提交节点输出和变量变更；
5. 将当前 Token 置为 CONSUMED；
6. 根据 GraphPlan 创建 0..N 个后继 Token，或标记路径 TERMINAL；
7. 更新 Split/Join/Loop 上下文；
8. 写入 Runtime Event 和必要 Outbox；
9. 提交事务。

禁止先提交 Token=CONSUMED，之后再异步创建后继 Token。也禁止先创建后继 Token，再异步标记父 Token 已消费。

## 23.4 NodeActivation 与 NodeAttempt 状态机

### 23.4.1 NodeActivation 状态

- CREATED
- READY
- ACTIVE
- SUSPENDED
- RETRY_WAIT
- COMPLETED
- FAILED
- MANUAL_REQUIRED
- UNKNOWN
- CANCELLED
- QUARANTINED

状态含义：

| 状态 | 精确定义 |
|---|---|
| CREATED | 逻辑激活已建立，尚未进入调度 |
| READY | 满足执行前置条件，等待调度 |
| ACTIVE | 至少一个当前 Attempt 已进入派发/执行生命周期 |
| SUSPENDED | 等待登记已提交，当前无 Worker 持有 |
| RETRY_WAIT | 当前 Attempt 可重试，但 retryAfter 尚未到 |
| COMPLETED | 业务后置条件已证明满足，输出提交完成 |
| FAILED | 按策略不再自动重试，且无未知外部副作用 |
| MANUAL_REQUIRED | 需要人工决策，禁止自动重试 |
| UNKNOWN | 外部副作用结果无法判定 |
| CANCELLED | 已证明没有未决副作用且取消安全 |
| QUARANTINED | 激活关联的设备/资源/物料处于隔离状态 |

### 23.4.2 Activation 合法转移表

| 当前 | 事件 | 条件 | 下一状态 |
|---|---|---|---|
| CREATED | ActivationReady | 输入和依赖满足 | READY |
| READY | AttemptCreated | Run=RUNNING，资源准入通过 | ACTIVE |
| ACTIVE | AttemptSuspended | WaitRegistration 同事务创建 | SUSPENDED |
| SUSPENDED | WaitSatisfied | waitId 有效且事件被接受 | READY |
| ACTIVE | AttemptRetryableFailure | 无未知副作用；retry policy 允许；次数未超 | RETRY_WAIT |
| RETRY_WAIT | RetryDue | Run=RUNNING，资源准入重新通过 | ACTIVE |
| ACTIVE | AttemptSucceeded | 后置条件和输出校验通过 | COMPLETED |
| ACTIVE | AttemptPermanentFailure | 无未知副作用 | FAILED |
| ACTIVE | AttemptManualRequired | 需要人工处置 | MANUAL_REQUIRED |
| ACTIVE | AttemptUnknownOutcome | 可能发生外部副作用且无法证明结果 | UNKNOWN |
| ACTIVE / UNKNOWN | SafetyIsolation | 物理资源状态不确定或违反安全约束 | QUARANTINED |
| READY / RETRY_WAIT | CancelSafe | 无命令派发且无副作用 | CANCELLED |
| SUSPENDED | CancelWaitSafe | Wait 可安全注销，且无未决命令 | CANCELLED |
| MANUAL_REQUIRED | ManualRetryApproved | 证据充分，创建新 Attempt | READY |
| UNKNOWN | OutcomeReconciledSuccess | 设备/外部事实证明动作成功 | COMPLETED |
| UNKNOWN | OutcomeReconciledNoEffect | 证明动作未发生，可按策略重试 | RETRY_WAIT 或 READY |
| UNKNOWN | OutcomeCannotBeReconciled | 无法证明结果 | QUARANTINED |

FAILED、COMPLETED、CANCELLED 是激活终态。UNKNOWN 和 QUARANTINED 不是普通失败，不能自动套用 retry policy。

### 23.4.3 Attempt 状态

Attempt 状态集合：

- CREATED
- DISPATCH_PENDING
- DISPATCHED
- STARTED
- SUSPENDED
- SUCCEEDED
- RETRYABLE_FAILED
- PERMANENT_FAILED
- UNKNOWN
- ABANDONED_SAFE

关键规则：

1. 每个 activation 同时最多一个有效 Attempt；
2. 新 Attempt 只能在旧 Attempt 已明确进入 SUCCEEDED、RETRYABLE_FAILED、PERMANENT_FAILED、ABANDONED_SAFE 或经核验关闭后创建；
3. Attempt 进入 DISPATCHED 前，Dispatch outbox 必须已经在事务中提交；
4. DISPATCHED 不代表 Worker 已开始；
5. STARTED 只代表 Handler 已进入执行，不代表设备命令已完成；
6. Worker 迟到的结果必须匹配 activationId + attemptId + attemptVersion；
7. 旧 Attempt 的完成消息不得覆盖新 Attempt；
8. 对设备副作用未知的 Attempt 不得直接标记 ABANDONED_SAFE。

### 23.4.4 重试规则

重试是新 Attempt，不是把旧 Attempt 状态改回 STARTED。

创建新 Attempt 的事务必须：
- 校验 Activation=RETRY_WAIT；
- 校验 retryAfter 已到；
- 校验次数/总时限未超；
- 校验旧 Attempt 已确定失败且无未决副作用；
- 生成新的 attemptId；
- 保持 activationId 不变；
- 记录 retryOfAttemptId；
- 写入 AttemptCreated Event。

若 Handler 有设备命令：
- 同一逻辑命令的安全重发复用 commandId；
- 重新开始一项新的物理操作必须新建 commandId；
- 如果上一次命令结果 UNKNOWN，不能通过创建新 attempt 绕过 UNKNOWN。

## 23.5 SPLIT / Branch / JOIN 状态机

### 23.5.1 SplitActivation 状态

- OPENING
- OPEN
- JOIN_READY
- CLOSED_SUCCESS
- CLOSED_FAILED
- QUARANTINED

创建 SplitActivation 和全部分支 Token 是一个事务。OPENING 只允许存在于事务内部，不得作为已提交的半初始化状态暴露给 Scheduler。

### 23.5.2 BranchActivation 状态

- ACTIVE
- WAITING_JOIN
- ARRIVED
- SKIPPED
- FAILED
- CANCEL_PENDING
- CANCELLED
- UNKNOWN
- QUARANTINED

BranchActivation 以 branchActivationId 唯一标识。branchId 是静态定义身份；同一静态分支在不同 SplitActivation/循环迭代中必须创建不同 branchActivationId。

合法转移：

| 当前 | 事件 | 条件 | 下一状态 |
|---|---|---|---|
| ACTIVE | ArriveJoin | Token 到达匹配 Join；本分支所有内层结构已闭合 | ARRIVED |
| ACTIVE | ExplicitSkip | 仅允许定义语义明确的跳过 | SKIPPED |
| ACTIVE | BranchFailure | 发生不可恢复失败 | FAILED |
| ACTIVE | CancelRequested | Run 正在停止或失败收敛 | CANCEL_PENDING |
| CANCEL_PENDING | CancelSafeConfirmed | 无未决副作用 | CANCELLED |
| ACTIVE / CANCEL_PENDING | UnknownEffect | 有未决物理副作用 | UNKNOWN |
| UNKNOWN | IsolationRequired | 无法核验资源状态 | QUARANTINED |

FAILED、UNKNOWN、QUARANTINED 不得伪装成 ARRIVED。

### 23.5.3 Join 释放条件

Join 只在以下条件全部满足时释放：

1. SplitActivation.status=OPEN；
2. 所有 expectedBranchIds 都存在唯一 BranchActivation；
3. 每个 expected branch 状态均为 ARRIVED，或在该 Join 明确允许时为 SKIPPED；
4. 没有 FAILED、CANCEL_PENDING、CANCELLED（除非策略明示可接受）、UNKNOWN、QUARANTINED；
5. 所有嵌套 SplitActivation 已关闭；
6. 所有分支输出已经提交并冻结；
7. Join 聚合规则输入完整且通过 Schema 校验。

Join 释放事务：

- 锁定/版本校验 SplitActivation；
- 校验 expected branch 集合和所有 Branch 状态；
- 将 SplitActivation 标记 CLOSED_SUCCESS；
- 将 JoinActivation 标记 RELEASED；
- 固化 BranchOutput snapshot；
- 创建唯一 downstream Token；
- 写入 JoinReleased Event；
- 写入必要 Outbox；
- 一次提交。

数据库必须有唯一约束保证一个 splitActivationId 最多存在一个成功释放记录/下游 Token。两个最后分支同时到达时，由 Coordinator 串行化；重复消息由 Inbox 和 branch 唯一约束双重去重。

### 23.5.4 分支失败策略

默认策略：任意一个并行分支不可恢复失败，Run 进入 STOP_REQUESTED；不再派发其他分支的新节点；已派发分支进入停止收敛。

此时：
- SplitActivation 不关闭为成功；
- Join 不释放；
- 未开始的分支可标记 INVALIDATED/CANCELLED（需证明无副作用）；
- 已开始的分支必须等待结果或进入 UNKNOWN；
- 所有物理状态收敛后 Run=FAILED；
- 任意设备结果未知时 Run=QUARANTINED 或 MANUAL_REQUIRED。

若未来支持容错并行，必须定义独立的 SplitFailurePolicy（ALL_REQUIRED、QUORUM、COMPENSATE、IGNORE_BRANCH 等），并在编译期验证 Join 语义。本版默认只实现 ALL_REQUIRED。

## 23.6 Loop 状态机

LoopActivation 状态：

- ACTIVE
- ITERATION_CLOSING
- ITERATION_ADVANCED
- EXITING
- CLOSED_SUCCESS
- FAILED
- QUARANTINED

转移规则：

| 当前 | 事件 | 条件 | 下一状态 |
|---|---|---|---|
| 不存在 | LoopEnter | Token 到达 LOOP_ENTER | ACTIVE，iteration=1 |
| ACTIVE | LoopBackRequested | 本轮循环体完成；无未闭合 Split/Join | ITERATION_CLOSING |
| ITERATION_CLOSING | AdvanceCommitted | 迭代计数未超限；新 Token 创建成功 | ITERATION_ADVANCED |
| ITERATION_ADVANCED | IterationActivated | 新一轮 Token READY | ACTIVE，iteration+1 |
| ACTIVE | LoopExitRequested | 退出条件求值为 true；本轮结构全部闭合 | EXITING |
| EXITING | LoopExitCommitted | 退出 Token 创建成功 | CLOSED_SUCCESS |
| ACTIVE / ITERATION_CLOSING | LoopFailure | 循环体失败 | FAILED 或 Run 收敛 |
| 任意非终态 | UnknownEffect | 循环体存在未决副作用 | QUARANTINED |

LoopBack 不是普通边跳转。Loop iteration 更新、上一轮闭合、新一轮 Token 创建必须同事务提交。若崩溃发生在提交前，仍处于旧 iteration；提交后恢复只能看到新 iteration，不得重复增加。

## 23.7 WaitRegistration 状态机

Wait 状态：

- CREATED
- ACTIVE
- SATISFIED
- EXPIRED
- CANCELLED
- REJECTED

转移：

| 当前 | 事件 | 条件 | 下一状态 |
|---|---|---|---|
| CREATED | WaitCommit | 等待条件完整且与 Activation 同事务 | ACTIVE |
| ACTIVE | MatchingEvent | 来源合法、关联字段匹配、事件未过期 | SATISFIED |
| ACTIVE | TimerDeadline | deadline 已到，timerId 匹配 | EXPIRED |
| ACTIVE | CancelWait | 允许取消且无未决设备动作 | CANCELLED |
| ACTIVE | InvalidEvent | 事件不能证明满足条件 | ACTIVE，记录拒绝 |
| ACTIVE | RunStop | 等待可安全撤销 | CANCELLED |
| ACTIVE | RunStop | 关联命令仍未决 | 保持 ACTIVE，进入命令收敛 |

Wait 满足事务必须包含：
- Inbox 去重；
- Wait 状态 ACTIVE→SATISFIED；
- 事件与 waitId 的关联记录；
- Activation SUSPENDED→READY；
- Token WAITING→READY；
- Runtime Event；
- 后续调度 Outbox（如采用异步 Dispatch）。

重复事件命中已 SATISFIED 的 wait 时，只记录 duplicate/late event，不重复创建 READY Token。

---

# 24. Runtime Message 处理协议与拒绝规则

## 24.1 消息信封

每条消息必须使用统一信封：

    RuntimeEnvelope {
      messageId
      messageType
      schemaVersion
      source
      runId
      aggregateType
      aggregateId
      aggregateVersion
      coordinatorEpoch
      occurredAt
      receivedAt
      correlationId
      causationId
      payloadHash
      payload
    }

字段要求：
- messageId：生产者生成的稳定唯一身份；
- source：worker/device-adapter/timer/user/system 等；
- aggregateVersion：消息基于哪个对象版本产生；
- coordinatorEpoch：发送方知道的当前 owner epoch；
- occurredAt：来源时间，仅用于诊断，不用于控制流排序；
- receivedAt：系统接收时间；
- payloadHash：防止同一 messageId 携带不同 payload；
- causationId：触发当前消息的上游消息/命令。

同一 messageId 携带不同 payloadHash 是协议违规，进入安全告警，不得按普通重复消息忽略。

## 24.2 消息处理顺序

每个 Run 的 Inbox Consumer 必须按以下逻辑执行：

1. 以 messageId 插入 Inbox；唯一冲突表示重复；
2. 若重复，比较 payloadHash：
   - 相同：返回已记录结果，不重复执行；
   - 不同：拒绝并记录安全事件；
3. 验证 runId、aggregateId、schemaVersion；
4. 验证 coordinatorEpoch（启用时）；
5. 验证 activationId/attemptId/commandId 等对象身份；
6. 验证消息的前置状态和版本；
7. 计算纯状态转移；
8. 在同一事务中写状态、Runtime Event、Outbox、Inbox processed；
9. 提交；
10. 提交后才通知 Scheduler/Dispatcher。

若事务失败，Inbox 不得标记 processed；可重试事务。若错误属于永久协议错误，则将 Inbox 标记 REJECTED 并保存原因，不能无限重试毒消息。

## 24.3 消息分类

| 分类 | 示例 | 处理方式 |
|---|---|---|
| 当前有效 | 当前 attempt 的 NodeCompleted | 执行状态转移 |
| 幂等重复 | 相同 messageId / payloadHash | 返回既有处理结果 |
| 语义重复 | 不同 messageId，但同一 branch 已 ARRIVED | 记录重复事实，不重复推进 |
| 过期消息 | 旧 attempt 的 NodeCompleted | 拒绝状态变更，保留审计 |
| 乱序可等待 | Device RUNNING 先于 ACK 到达，协议允许 | Inbox 暂存，按设备状态机重排或接受单调状态 |
| 非法跃迁 | CREATED 直接收到 CommandCompleted | REJECTED，触发协议告警 |
| 无法关联 | 设备事件无 commandId | UNATTRIBUTED 隔离队列 |
| 版本冲突 | aggregateVersion 过期 | 重新加载；若身份仍有效则按当前状态判定，否则拒绝 |
| 旧 owner | coordinatorEpoch 过期 | 拒绝图状态写入，记录 stale-owner 事件 |

## 24.4 Worker Completion 的唯一接受条件

NodeCompleted / NodeFailed 消息只有满足以下全部条件才可改变 Runtime 状态：

- Run 当前允许处理在途结果（暂停/停止期间允许收敛，不允许产生新的普通工作）；
- activationId 存在；
- attemptId 是当前有效 Attempt；
- Attempt 状态为 DISPATCHED 或 STARTED；
- tokenId 与 Activation 绑定一致；
- definitionHash 与 Run 固定版本一致；
- completion payload 通过 outputSchema；
- 必需设备命令均已达到可接受的最终状态；
- 消息不是旧 coordinatorEpoch；
- 幂等键未被用于不同结果。

不满足时，消息只能被拒绝、隔离或作为迟到事实记录，不得推进 Token。

---

# 25. 数据库事务边界（精确到操作）

## 25.1 总规则

事务只覆盖数据库内部状态。设备网络调用、Worker 执行、外部 API、文件 IO、人工操作均不放在数据库事务内。

每个事务必须有：
- 明确的命令/消息输入；
- 明确的读集合；
- 明确的写集合；
- 唯一幂等键；
- 明确的提交后副作用；
- 明确的失败重试规则。

事务隔离级别、数据库产品和 DDL 在物理数据模型阶段确定；但以下逻辑原子边界不可更改。

## 25.2 T01：创建 Run

输入：CreateRun(requestId, definitionHash, initialVariables)

同一事务：
1. 校验 requestId 未使用，或返回既有 Run；
2. 插入 workflow_run(CREATED)；
3. 固化 definitionHash/compilerVersion；
4. 写入初始变量及版本；
5. 创建初始 Token；
6. 写 RunCreated Event；
7. 写 StartRun Outbox（若异步启动）；
8. 记录 requestId→runId 幂等映射。

提交后：Coordinator 收到 StartRun。

失败：
- 任一步失败整体回滚；
- 相同 requestId 重试返回原 Run；
- 相同 requestId 不同参数 hash，拒绝。

## 25.3 T02：启动 Run

同一事务：
1. 校验 Run=CREATED；
2. 校验 Definition/GraphPlan 版本；
3. 创建起始 NodeActivation；
4. Token CREATED→READY；
5. Run CREATED→STARTING→RUNNING（若初始化无异步步骤）；
6. 写 RunStarted / TokenReady Event；
7. 写 SchedulerWakeup Outbox。

若启动依赖外部初始化，Run 保持 STARTING，直到初始化结果消息提交。

## 25.4 T03：预留 Token 和 Dispatch

Scheduler Reservation 事务：
1. 校验 Run=RUNNING；
2. 校验 Token=READY；
3. 校验 Run in-flight 配额；
4. 校验全局/节点类型配额预留；
5. 校验 ResourceLease 已获取或资源准入已原子完成；
6. Token READY→RESERVED；
7. 创建 activation/attempt（若尚不存在）；
8. 写 Reservation Event。

Dispatch Commit 事务：
1. 校验 Token=RESERVED；
2. 校验 Run 仍 RUNNING；
3. 校验 reservation 未过期；
4. Attempt CREATED→DISPATCH_PENDING；
5. Token RESERVED→IN_FLIGHT；
6. 写 WorkerDispatch Outbox；
7. 写 DispatchCommitted Event。

提交后 Worker Dispatcher 才可发送任务。

若 Run 在 Reservation 与 Dispatch Commit 之间进入 PAUSE/STOP：
- Dispatch Commit 必须失败；
- Token RESERVED→READY 或按 Stop 策略 INVALIDATED；
- 释放仅属于软件调度的容量预留；
- 真实设备租约只能按资源协议释放。

## 25.5 T04：Worker 接受与开始

Worker 接收 Dispatch 后发送 DispatchAccepted / NodeStarted。

事务：
1. 校验 activationId/attemptId/dispatchId；
2. 校验 Attempt=DISPATCH_PENDING 或 DISPATCHED；
3. Attempt→STARTED；
4. NodeActivation 保持 ACTIVE；
5. 写 NodeAttemptStarted Event；
6. 更新 Worker dispatch lease。

重复 NodeStarted 幂等。若 Attempt 已完成或旧 attempt，拒绝状态变更。

Worker 接受消息丢失时，不能据此直接重发设备命令；按 DispatchLease 与副作用分类恢复。

## 25.6 T05：纯计算节点成功完成

同一事务：
1. 校验 completion 身份和版本；
2. 校验 Activation=ACTIVE、Attempt=STARTED；
3. 校验无外部副作用未决；
4. 校验输出 Schema；
5. 写 Node Output；
6. 写 Run Variable（按版本/CAS）；
7. Attempt→SUCCEEDED；
8. Activation→COMPLETED；
9. Token IN_FLIGHT→CONSUMED；
10. 创建后继 Token 或终止路径；
11. 更新 Loop/Split/Join（如相关）；
12. 写 NodeActivationCompleted 等 Runtime Event；
13. 写 SchedulerWakeup Outbox。

任一步失败整体回滚。Worker 不得因收到超时就自行把节点标为失败。

## 25.7 T06：节点失败与重试

同一事务：
1. 校验当前 Attempt；
2. 分类错误为 retryable/permanent/unknown/manual；
3. 保存完整错误类别、摘要和证据引用；
4. 若 retryable 且无未决副作用：Attempt→RETRYABLE_FAILED，Activation→RETRY_WAIT；
5. 若 permanent：Attempt→PERMANENT_FAILED，Activation→FAILED；
6. 若 unknown：Attempt→UNKNOWN，Activation→UNKNOWN；
7. 若 manual：Activation→MANUAL_REQUIRED；
8. 写 Runtime Event；
9. retryable 时创建 Timer/Retry Outbox；
10. permanent/unknown 时创建 Run failure/stop coordination message。

不得把所有异常统一映射成 FAILED。超时也必须先判定副作用是否可能已发生。

## 25.8 T07：SUSPEND

同一事务：
1. 校验当前 Activation=ACTIVE；
2. 校验 WaitRegistration 输入完整；
3. 插入 WaitRegistration(ACTIVE)；
4. Activation→SUSPENDED；
5. Attempt→SUSPENDED；
6. Token IN_FLIGHT→WAITING；
7. 写 NodeSuspended Event；
8. 写 WaitSubscription/Timer Outbox。

事务提交前不得向外部事件源宣称等待已生效。提交后 Worker 释放。

## 25.9 T08：Wait 满足

同一事务：
1. Inbox message 去重；
2. 校验 WaitRegistration=ACTIVE；
3. 校验事件来源、correlationKey、commandId、payload；
4. Wait→SATISFIED；
5. Activation SUSPENDED→READY；
6. 创建后续 Attempt 或 continuation metadata；
7. Token WAITING→READY；
8. 写 WaitSatisfied Event；
9. 标记 Inbox processed；
10. 写 SchedulerWakeup Outbox。

若事件不匹配，Inbox REJECTED 或保留待人工核验；Wait 不改变。

## 25.10 T09：SPLIT 原子展开

同一事务：
1. 校验当前 Token=IN_FLIGHT 且位于 SPLIT；
2. 计算本次实际激活的 branch 集合；
3. 校验集合非空且符合 GatewayPlan；
4. 创建 SplitActivation(OPEN)；
5. 为每个 branch 创建 BranchActivation(ACTIVE)；
6. 为每个 branch 创建独立 Token(READY)；
7. 父 Token→CONSUMED；
8. 写 SplitOpened / BranchActivated Event；
9. 写 SchedulerWakeup Outbox。

若任一分支创建失败，整个事务回滚，不允许半个 Split。

## 25.11 T10：Branch Arrival 与 JOIN

Branch Arrival 事务：
1. 校验 branchActivationId 和 splitActivationId；
2. 校验 Branch=ACTIVE；
3. 校验该分支内层 Split 已闭合；
4. 校验到达节点等于配对 Join；
5. Branch→ARRIVED；
6. 分支 Token→WAITING；
7. 固化 BranchOutput；
8. 写 BranchArrived Event；
9. 检查是否满足 Join release predicate。

若满足，必须在同一事务中继续：
10. Split→CLOSED_SUCCESS；
11. Join→RELEASED；
12. 创建唯一 downstream Token(READY)；
13. 写 JoinReleased Event；
14. 写 SchedulerWakeup Outbox。

若不满足，事务只提交 arrival，不创建下游 Token。

## 25.12 T11：Loop iteration advance

同一事务：
1. 校验 Loop=ITERATION_CLOSING；
2. 校验本轮所有 Token 已完成；
3. 校验本轮所有 Split/Join 均已闭合；
4. 校验 iteration+1 未超过 maxIterations；
5. 更新 iteration；
6. 创建下一轮 Token/Activation；
7. Loop→ITERATION_ADVANCED；
8. 写 LoopIterationAdvanced Event；
9. 写 SchedulerWakeup Outbox。

事务提交后恢复只会看到新 iteration。禁止通过进程内计数器推进。

## 25.13 T12：设备命令创建

同一事务：
1. 校验 NodeActivation/Attempt 有权创建命令；
2. 校验 Device Adapter capability；
3. 校验 resource requirements；
4. 生成 commandId；
5. 插入 Command Ledger(CREATED)；
6. 插入 CommandOutbox(PENDING)；
7. 关联 ResourceLease（若需）；
8. 写 CommandCreated Event。

提交后 Dispatcher 才能发送设备请求。

## 25.14 T13：设备命令派发

Dispatcher 先 claim outbox，claim 具有租约和 fencing。

发送前数据库事务：
1. 校验 Command=CREATED/PREPARED；
2. 校验 outbox 仍归当前 dispatcher claim；
3. Command→DISPATCHING；
4. 写 DispatchAttempt；
5. 提交。

事务外调用设备 Adapter。

调用返回后：
- 明确设备拒绝：写 REJECTED；
- 明确 ACK：写 ACKNOWLEDGED；
- 明确同步完成：只有协议保证结果完整时可写 SUCCEEDED；
- 网络超时/连接断开/进程崩溃且无法证明是否送达：写 UNKNOWN；
- 不能证明未送达时不得退回 CREATED 并创建新 commandId。

## 25.15 T14：设备事件消费

同一事务：
1. 插入/去重 Device Event Inbox；
2. 按 commandId 查找唯一命令；
3. 验证 deviceId、operation、payloadHash/协议字段；
4. 校验状态迁移合法且单调；
5. 更新 Command Ledger；
6. 更新 ResourceLease（仅在状态证据满足释放条件时）；
7. 写 Command Event；
8. 写 DeviceCommandUpdated Runtime Outbox；
9. 标记 Inbox processed。

无 commandId 或 commandId 不存在：
- 进入 UNATTRIBUTED；
- 不推进 NodeActivation；
- 不释放 ResourceLease；
- 不尝试按设备当前活动命令猜测归属。

## 25.16 T15：Pause/Stop 请求

同一事务：
1. 校验 Run 当前状态和 requestId；
2. Run→PAUSE_REQUESTED 或 STOP_REQUESTED；
3. 写请求事件；
4. 写 DisableDispatch / DrainCoordinator Outbox；
5. 标记所有尚未 DispatchCommitted 的 reservation 待撤销；
6. 不修改设备命令为已停止；
7. 不释放可能对应未决物理动作的资源租约。

## 25.17 T16：Pause/Stop Drain 完成

同一事务：
1. 校验 Run=PAUSE_DRAINING 或 STOP_DRAINING；
2. 检查所有 DispatchLease；
3. 检查所有 NodeActivation；
4. 检查所有 Command Ledger；
5. 检查所有 ResourceLease；
6. 检查所有 WaitRegistration；
7. 检查无未处理关键 Inbox；
8. 计算安全收敛结果；
9. 写 Run=PAUSED / STOPPED / FAILED / MANUAL_REQUIRED / QUARANTINED；
10. 写对应 Runtime Event。

若任何设备命令 UNKNOWN 或资源物理状态不明，不允许进入普通 PAUSED/STOPPED 并释放租约；必须进入 QUARANTINED 或 MANUAL_REQUIRED。

## 25.18 T17：Checkpoint

Checkpoint 必须在一致性快照边界创建：
- 读取 Run.version 和 eventSeq；
- 读取全部 Token/Activation/Split/Join/Loop/Variable/Wait/Command/Lease 状态；
- 确保这些状态对应同一已提交游标；
- 写 checkpoint payload/hash；
- 写 checkpoint.eventSeq。

如果无法获得一致性快照，不创建 checkpoint；不能用多次非一致读取拼成看似完整的快照。

---

# 26. 异常路径决策表（必须唯一处理）

| 异常 | 判断依据 | 唯一处理 | 禁止行为 |
|---|---|---|---|
| Worker 在 Dispatch 前崩溃 | 无 DispatchCommitted | Token 可回 READY | 假定节点已执行 |
| Dispatch 已提交但 Worker 未 ACK | DispatchLease/worker inbox | 等待/过期后按副作用分类 | 直接重发设备命令 |
| Worker 纯计算中崩溃 | Handler sideEffectClass=PURE | 旧 Attempt 关闭后新 Attempt | 保留旧 Attempt 为有效 |
| Worker 有副作用时崩溃 | sideEffectClass 非 PURE | 查询外部事实；未知则 UNKNOWN | 盲目 retry |
| Worker 返回过期 Attempt 结果 | attemptId 不匹配 | 记录 STALE_COMPLETION，拒绝状态变更 | 覆盖新 Attempt |
| NodeCompleted 重复 | messageId 或 activation result key 重复 | 返回首次处理结果 | 再创建后继 Token |
| 节点输出 Schema 错误 | outputSchema 校验失败 | Attempt 失败；按副作用分类决定 retry/unknown | 提交部分输出 |
| 变量 CAS 冲突 | expectedVersion 不匹配 | 整个完成事务回滚；按冲突策略重算或失败 | last-write-wins |
| SPLIT 创建中 DB 崩溃 | 事务原子性 | 全部未提交则全部回滚；已提交则所有分支存在 | 修补半个 Split |
| 最后两个 Branch 同时到达 | Coordinator 串行 + 唯一约束 | 一个先到，另一个触发唯一 Join release | 双重创建下游 Token |
| Branch Arrival 重复 | branchActivationId 已 ARRIVED | 幂等记录 | 增加 arrived count |
| Branch 失败 | FailurePolicy=ALL_REQUIRED | Run STOP_REQUESTED；不释放 Join | 把失败当 ARRIVED |
| Branch UNKNOWN | 外部结果不明 | Run 收敛并隔离 | 继续 Join |
| Join 到达时有嵌套 Split 未闭合 | 结构校验失败 | 拒绝 Arrival，标记协议错误 | 外层 Join 先释放 |
| LoopBack 重复 | loop iteration/version 已推进 | 幂等拒绝第二次推进 | iteration 再加一 |
| Timer 重复触发 | timerId 唯一 | 首次满足 Wait，后续重复忽略 | 创建多个 READY Token |
| 设备 ACK 丢失 | 设备查询或协议无法确认 | Command UNKNOWN，查询/人工核验 | 新 commandId 重发 |
| 设备最终事件乱序 | commandId + event sequence/state transition | 暂存或按单调状态接受；非法状态隔离 | 时间戳较新就覆盖 |
| 设备事件无 commandId | 无法可靠关联 | UNATTRIBUTED 隔离 | 按 deviceId 猜当前命令 |
| 设备事件重复 | sourceEventId/eventId | Inbox 去重 | 重复完成 NodeActivation |
| 命令最终状态成功但设备回读不满足后置条件 | postcondition check | Activation 不完成，转失败/人工/隔离 | 只凭成功事件放行 |
| ResourceLease 过期 | TTL 到期 | 进入核验，不自动释放物理资源 | TTL=空闲 |
| Coordinator 崩溃 | owner heartbeat/epoch | 恢复持久化状态，重建 mailbox | 恢复旧线程/Future |
| DB commit 结果未知 | 客户端断连，无法确认 commit | 用幂等 request/message ID 查询事务结果 | 盲目执行第二次非幂等操作 |
| Outbox 发送后状态未更新 | 发送结果未知 | 按消息/命令协议核验；设备命令转 UNKNOWN | 直接重新创建命令 |
| Inbox 消费时崩溃 | processed 未提交 | 重放同 messageId | 丢弃消息 |
| Inbox 毒消息 | 永久 schema/身份错误 | REJECTED + DLQ/告警 | 无限快速重试 |
| Pause 时设备仍运行 | 设备未确认停止 | PAUSE_DRAINING 持续；达到阈值告警 | 标记 PAUSED |
| Stop 时设备状态未知 | Command/Lease UNKNOWN | QUARANTINED 或 MANUAL_REQUIRED | 标记 STOPPED 并释放资源 |
| 人工强制跳过节点 | 有未决副作用 | 拒绝直接跳过，先核验/处置副作用 | 改 Token 状态绕过命令账本 |

## 26.1 数据库提交结果未知

这是必须单独处理的故障。客户端发送 COMMIT 后连接断开，不能判断事务是否提交。

处理规则：
- 所有外部请求都必须带稳定 requestId/messageId；
- 重新连接后按幂等身份查询结果；
- 若记录存在且 payloadHash 相同，视为已提交；
- 若记录不存在，可重新提交；
- 若存在同身份不同 payload，协议冲突并停止；
- 不允许因“客户端没收到成功响应”就创建新的 commandId 或新的 Run。

## 26.2 Outbox 的 at-least-once 边界

Outbox Dispatcher 可能重复发送。接收方必须按 outboxId/messageId 幂等。

对普通内部消息，重复投递由 Inbox 去重。

对设备命令，接收方设备若不支持 commandId 幂等，则 Outbox 重发可能导致重复物理动作。因此：
- Adapter 必须声明是否支持 commandId 幂等；
- 不支持时，发送结果不确定必须转 UNKNOWN 并核验；
- 不得把通用 Outbox 的 at-least-once 语义直接套在非幂等设备命令上；
- 只有证明设备未收到命令时才允许重新派发。

---

# 27. 状态不变量与数据库约束映射

| 不变量 | 应用层校验 | 数据库约束/机制 |
|---|---|---|
| 一个 Run 一个有效 Coordinator | owner/epoch 校验 | owner lease 唯一/版本 CAS |
| 一个 Token 一个当前 Activation | Coordinator 校验 | token_id + active_activation 唯一部分索引 |
| 一个 Activation 一个当前 Attempt | Attempt 创建事务校验 | activation_id + active_attempt 标记唯一 |
| 一个 Split 每个 branch 一条状态 | Split Engine 校验 | (split_activation_id, branch_id) UNIQUE |
| 一个 Split 最多释放一个 Join | Join Engine 校验 | split_activation_id UNIQUE on released join |
| 一个 Wait 只满足一次 | Wait Engine 校验 | wait_id 唯一 + 状态 CAS |
| 一个 commandId 一个 payloadHash | Adapter 校验 | command_id UNIQUE；payload_hash immutable |
| 一个事件只消费一次 | Inbox 校验 | (source, message_id) UNIQUE |
| 一个 Outbox 逻辑消息唯一 | Outbox 创建方校验 | outbox_id UNIQUE |
| 变量无静默覆盖 | Variable Store 校验 | version CAS |
| 一个资源只能有一个有效 owner | Resource Service 校验 | resource_id 有效租约唯一约束 |
| 旧 Attempt 不可覆盖新 Attempt | Coordinator 校验 | attempt_id + version CAS |
| Event 序号单 Run 单调 | Event Store 分配 | (run_id, event_seq) UNIQUE |

数据库约束是最后防线，不替代状态机校验。唯一约束冲突必须被转换为确定的幂等/冲突结果，不能泄漏成未处理异常。

---

# 28. Coordinator 处理循环的参考算法

以下是规范性伪代码，具体语言实现可不同，但处理顺序和事务边界必须保持：

    handle(envelope):
      begin transaction

      inboxResult = inbox.insertIfAbsent(
        source = envelope.source,
        messageId = envelope.messageId,
        payloadHash = envelope.payloadHash
      )

      if inboxResult == DUPLICATE_SAME_PAYLOAD:
        return previouslyCommittedOutcome

      if inboxResult == DUPLICATE_DIFFERENT_PAYLOAD:
        recordProtocolViolation()
        markRejected()
        commit
        return REJECTED

      run = loadRunForUpdate(envelope.runId)

      validateSchema(envelope)
      validateCoordinatorEpoch(envelope, run)
      validateAggregateIdentity(envelope)
      validateExpectedVersion(envelope)

      transition = pureStateMachine.apply(run, envelope)

      if transition == REJECT:
        persistRejectedInboxAndAudit()
        commit
        return REJECTED

      persistAllStateChanges(transition)
      appendRuntimeEvents(transition.events)
      appendOutboxMessages(transition.outbox)
      markInboxProcessed(envelope.messageId, transition.outcome)

      commit

      notifySchedulerIfNeeded()
      return transition.outcome

要求：
- pureStateMachine.apply 不做 IO；
- transaction 内不调用 Worker、设备或外部 API；
- commit 前不通知 Scheduler；
- 事务失败不确认消息已处理；
- 通知丢失可由 Outbox/周期扫描恢复；
- Coordinator 的任何重入消息都重新走 Inbox 幂等流程。

---

# 29. 状态机验收矩阵

在 Runtime State Machine 被标记 Implemented 前，以下测试必须全部通过。

## 29.1 状态转移穷举

对每种状态和每种事件：
- 合法转移必须成功；
- 非法转移必须拒绝；
- 重复事件必须幂等；
- 过期版本必须拒绝或按明确定义重新判定；
- 任何拒绝都不得产生部分状态写入。

测试应自动生成状态×事件矩阵，不只依赖手工挑选 happy path。

## 29.2 属性测试

至少验证：

1. 任意事件序列下 Token 不会同时属于两个有效 Activation；
2. 一个 Token 最多消费一次；
3. 一个 Activation 最多有一个有效 Attempt；
4. 一个 Split 的 Join 最多释放一次；
5. Join 不会在所有 expected branches 满足前释放；
6. Branch FAILED/UNKNOWN 不会被计为 ARRIVED；
7. Loop iteration 不回退、不重复增加；
8. 重复 WaitSatisfied 不产生多个 READY Token；
9. 旧 Attempt completion 不改变当前状态；
10. Run 非 RUNNING 时不会创建新的普通 Dispatch；
11. UNKNOWN Command 不会被自动重发；
12. 资源租约不因 TTL 或 Worker 消失自动释放；
13. 同一初始状态和同一有效消息序列产生相同控制流投影。

## 29.3 事务故障注入

在每个 T01–T17 的：
- BEGIN 前；
- 每个 SQL 写入后；
- COMMIT 前；
- COMMIT 成功但响应丢失后；
- COMMIT 后、Outbox Dispatcher 前；
- Outbox 发送后、投递状态更新前；

注入崩溃/断连。

恢复后必须证明：
- 所有数据库事务要么完整提交，要么完整回滚；
- 没有半个 Split；
- 没有双 Join；
- 没有丢失后继 Token；
- 没有重复变量提交；
- 没有重复创建逻辑 command；
- 设备结果不确定时进入 UNKNOWN/QUARANTINED；
- 所有待发送消息仍可由 Outbox 扫描发现。

## 29.4 并发竞态测试

必须专门覆盖：
- 两个 Worker 同时提交同一 Activation completion；
- 同一 Branch 两条 Arrival 并发；
- 最后两个 Branch 同时 Arrival；
- Pause 与 Dispatch Commit 并发；
- Stop 与 Pause 并发；
- WaitSatisfied 与 WaitExpired 并发；
- RetryDue 与 StopRequested 并发；
- Device final event 与 CancelRequested 并发；
- Resource lease renew 与 expiry scan 并发；
- Coordinator recovery 与新事件入 Inbox 并发。

每组竞态必须定义唯一线性化结果，不允许依赖测试运行时“通常谁先执行”。

## 29.5 通过标准

任何核心状态机测试失败均阻止 Stage B/C 通过。不能以集成测试 happy path 通过覆盖状态转移或故障注入失败。

---

# 30. 本规范的实施优先级与冻结规则

## 30.1 必须先冻结的语义

进入 Runtime State Machine 编码前必须冻结：

- Token 与 NodeActivation 分离；
- Attempt 重试身份规则；
- Split/Branch/Join 状态和释放条件；
- Loop iteration 原子推进；
- Run Pause/Stop 闸门；
- Inbox 幂等处理；
- Node completion 的原子事务；
- UNKNOWN 与 QUARANTINED 规则。

## 30.2 可在实现阶段选择、但不得改变语义的内容

以下可在技术选型时决定：
- Java record/class 具体结构；
- Mailbox 使用何种库；
- 数据库产品；
- 事务隔离级别；
- Outbox Polling 或 CDC；
- Event 表分区；
- Scheduler 的具体公平队列实现；
- Worker Pool 的具体执行器；
- 指标系统和 Trace 系统。

技术选择必须满足本章的状态转移和事务语义，不得反过来削弱语义。

## 30.3 冻结规则

状态机语义变更必须：
1. 提交 ADR；
2. 更新状态转移表；
3. 更新事务边界；
4. 更新异常路径表；
5. 增加正向、反向和故障注入测试；
6. 说明对已有 Run 的影响。

不能只修改枚举或代码分支而不更新协议文档。

---

## Runtime State Machine 规范结论

**Run 决定是否允许产生新工作；Token 表达控制流权利；NodeActivation 表达一次逻辑节点执行；Attempt 表达一次执行尝试；Split/Join/Loop 表达结构化控制流；Wait 表达可恢复等待；Command Ledger 表达物理动作事实；ResourceLease 表达资源占用。**

**每次状态转移必须由唯一消息触发、通过身份和版本校验、在明确事务中提交，并有唯一的重复、迟到、乱序、崩溃和未知结果处理路径。任何无法证明安全的外部副作用都进入 UNKNOWN / QUARANTINED，不得靠重试、超时或线程取消掩盖不确定性。**


---

# 31. v2.1 状态机闭合修订（规范性覆盖条款）

> **版本说明：** 本章及 §32–§39 是 v2.0 §23–§30 的规范性修订。它们不是旁注或建议。凡本章与前文状态集合、状态转移、事务编号、恢复规则或验收条款不一致之处，以本章及 §32–§39 为准。未被明确修改的既有定义继续有效。
>
> 本修订不引入历史流程兼容或旧运行时迁移要求；定义、状态、事务和持久化模型均按新系统的规范设计。

## 31.1 统一状态变更原则

1. 一个业务事件可以同时改变多个 Runtime 聚合，但必须由一个明确的 Coordinator 事务提交。
2. Token、NodeActivation、NodeAttempt、WaitRegistration、Split/Branch/Join、Loop、Command、ResourceLease、Run 的状态不能由各自组件独立写入；跨对象变化必须走本规范定义的事务。
3. 状态表中的“当前状态”是已提交事实。事务中间态不得被 Scheduler、恢复扫描或外部 API 观察。
4. 每个状态转移必须校验对象身份、当前状态、objectVersion、所属 runId，以及适用时的 attemptId、dispatchId、waitId、commandId、coordinatorEpoch 和 fencingToken。
5. 同一对象的并发事件通过锁定聚合根或等价 CAS 形成唯一线性化顺序。冲突方重新读取后按新状态重新判定，不得覆盖。
6. 事务提交结果未知时，只能使用原幂等身份查询结果；不得生成新业务身份重做非幂等操作。
7. 本规范禁止 speculative execution。一个 Activation 同一时刻最多有一个可执行 Attempt；旧 Attempt 的迟到消息只能被记录，不能改变当前投影。

## 31.2 Token、Activation、Attempt 的统一关系

三者语义固定如下：

- **Token**：控制流权利和当前位置；不表示 Worker 是否存活。
- **NodeActivation**：一次逻辑节点激活；重试和等待恢复不改变 activationId。
- **NodeAttempt**：一次实际执行尝试；每次重新进入 Worker 执行都创建新的 attemptId。
- **WaitRegistration**：一次持久化等待；其 waitId 与 activationId 绑定，并记录恢复原因。
- **Dispatch**：一次将 Attempt 交给 Worker 的派发过程；dispatchId 与 attemptId 一一对应。对同一 Attempt 的传输重试复用 dispatchId，不得因此创建新 Attempt。

Token 状态集合仍为 CREATED、READY、RESERVED、IN_FLIGHT、WAITING、CONSUMED、TERMINAL、INVALIDATED。Token 在 RETRY_WAIT 和 SUSPENDED 期间统一使用 WAITING；WAITING 必须通过 waitKind 区分 RETRY、EXTERNAL_EVENT、TIMER、JOIN 等等待原因，并引用唯一 waitId 或结构化等待 owner。

NodeActivation 状态集合补充 READY 的恢复语义：CREATED→READY→ACTIVE；ACTIVE 可转 SUSPENDED、RETRY_WAIT、COMPLETED、FAILED、MANUAL_REQUIRED、UNKNOWN、CANCELLED、QUARANTINED；SUSPENDED 或 RETRY_WAIT 只能在对应等待满足后转 READY。UNKNOWN 只有经事实核验才可转 COMPLETED、RETRY_WAIT/READY 或 QUARANTINED。FAILED、COMPLETED、CANCELLED 是 Activation 终态；MANUAL_REQUIRED、UNKNOWN、QUARANTINED 是阻断自动调度的挂起/隔离态。

Attempt 状态集合采用：
- CREATED
- DISPATCH_PENDING
- DISPATCHED
- ACCEPTED
- STARTED
- SUSPENDED
- SUCCEEDED
- RETRYABLE_FAILED
- PERMANENT_FAILED
- UNKNOWN
- ABANDONED_SAFE

Attempt 的合法转移：

| 当前状态 | 事件 | 条件 | 下一状态 |
|---|---|---|---|
| CREATED | DispatchOutboxCommitted | Dispatch 记录与 Outbox 同事务存在 | DISPATCH_PENDING |
| DISPATCH_PENDING | DispatcherSendStarted | 当前 dispatchId/claim/fencing 有效 | DISPATCHED |
| DISPATCHED | WorkerAccepted | Worker 身份和 dispatchId 匹配 | ACCEPTED |
| ACCEPTED | WorkerStarted | 当前 attemptId/dispatchId 匹配 | STARTED |
| ACCEPTED | WorkerStartAbandoned | 可证明 Handler 未开始且无副作用 | ABANDONED_SAFE |
| STARTED | SuspendCommitted | WaitRegistration 与 Token/Activation 同事务提交，Worker 已释放 | SUSPENDED |
| STARTED | AttemptSucceeded | 输出及后置条件已验证 | SUCCEEDED |
| STARTED | RetryableFailure | 无未决副作用 | RETRYABLE_FAILED |
| STARTED | PermanentFailure | 无未决副作用 | PERMANENT_FAILED |
| STARTED / DISPATCHED / ACCEPTED | UnknownOutcome | 可能已有未决副作用 | UNKNOWN |
| SUSPENDED | ContinuationClosed | continuation 已持久化，原 Worker 不再拥有执行权 | ABANDONED_SAFE |
| UNKNOWN | ReconciledNoEffect | 有权威证据证明动作未发生 | ABANDONED_SAFE |
| UNKNOWN | ReconciledSuccess | 外部事实证明动作完成 | SUCCEEDED |

Attempt 的 SUSPENDED 表示该次 Worker 执行已释放，不代表关联设备命令已结束。设备动作仍由 Command Ledger 表达。若等待满足后需要重新进入 Worker，必须创建新 Attempt，并记录 continuationOfAttemptId；旧 Attempt 不得复活。

### 31.2.1 正常执行与完成

正常执行状态组合：

| 阶段 | Token | Activation | Attempt |
|---|---|---|---|
| 可调度 | READY | READY | 尚无当前 Attempt |
| 已预留 | RESERVED | READY | CREATED |
| Dispatch 已提交 | IN_FLIGHT | ACTIVE | DISPATCH_PENDING |
| Dispatcher 已开始发送 | IN_FLIGHT | ACTIVE | DISPATCHED |
| Worker 已接收 | IN_FLIGHT | ACTIVE | ACCEPTED |
| Handler 已开始 | IN_FLIGHT | ACTIVE | STARTED |
| 成功提交 | CONSUMED | COMPLETED | SUCCEEDED |
| 永久失败提交 | TERMINAL | FAILED | PERMANENT_FAILED |

T03 Reservation 创建 Attempt 时，Activation 可保持 READY；只有 Dispatch Commit 时才将 Activation 置 ACTIVE。这样 RESERVED 不会被误认为节点已开始执行。

### 31.2.2 Retry 的唯一协议

Retryable Failure 必须在同一事务完成：

1. 校验 Run、Token、Activation、Attempt 的当前身份和版本；
2. 校验 Handler 的 sideEffectClass，证明不存在未决副作用；
3. 当前 Attempt→RETRYABLE_FAILED；
4. Activation→RETRY_WAIT；
5. Token IN_FLIGHT→WAITING，waitKind=RETRY；
6. 创建唯一 RetryRegistration，包含 retryId、retryAfter、retryCount、sourceAttemptId；
7. 写事件和 Timer/Retry Outbox；
8. Inbox processed；
9. 提交。

RetryDue 事务必须同时校验 Run=RUNNING、RetryRegistration=ACTIVE、retryId 匹配、retryAfter 已到、sourceAttemptId 仍为该 Activation 的最近失败 Attempt，且不存在未决副作用。成功时 RetryRegistration→SATISFIED、Token WAITING→READY、Activation RETRY_WAIT→READY，并写 SchedulerWakeup Outbox。此事务不创建 Attempt。

随后由 T03 为 READY Token 创建新 Attempt。新 Attempt 必须使用新 attemptId，记录 retryOfAttemptId；旧 Attempt 保持 RETRYABLE_FAILED。RetryDue 与 Pause/Stop 并发时，Run 行锁/CAS 决定唯一顺序：若 Pause/Stop 先提交，RetryDue 只能记录到期但不得使 Token READY；恢复 RUNNING 后再重新准入，Stop 则取消该 RetryRegistration 并按停止策略关闭路径。

### 31.2.3 Suspend / Wait / Resume 的唯一协议

Suspend 事务必须原子提交 WaitRegistration(ACTIVE)、Activation=SUSPENDED、Attempt=SUSPENDED、Token=WAITING，以及对应 Event/Outbox。事务提交后 Worker 释放执行权。

WaitSatisfied 与 WaitExpired 竞争时，对 waitId 执行唯一状态 CAS；只有一个事件可将 ACTIVE 改为 SATISFIED 或 EXPIRED。另一事件作为迟到/冲突事件记录，不得再创建 Token 或 Attempt。

WaitSatisfied 的恢复事务将 Wait→SATISFIED、Token WAITING→READY、Activation SUSPENDED→READY，并在同一事务将旧 Attempt SUSPENDED→ABANDONED_SAFE（reason=CONTINUATION_PERSISTED），写 continuation metadata。该关闭只表示旧 Worker 执行权已释放，不表示设备命令完成。只有在 Worker 已确认释放执行权、continuation 已持久化且不存在未跟踪的 Worker 副作用时，才允许关闭旧 Attempt。它不复活旧 Attempt。下一次 Dispatch 创建新 Attempt，记录 continuationOfAttemptId。若等待期间设备命令仍在运行，WaitSatisfied 只有在匹配 commandId 且命令事件满足定义的等待条件时才有效；不能因通用定时器或无关设备事件恢复节点。WaitExpired 必须按编译后的 timeout edge/failure policy 形成唯一结果；若关闭当前 continuation，同一事务也必须将旧 Attempt SUSPENDED→ABANDONED_SAFE，更新 Token/Activation 并创建唯一超时后继或失败收敛消息。

### 31.2.4 UNKNOWN 核验

UNKNOWN 不允许直接套用普通 Retry。核验结果必须是以下三类之一：

- **EffectConfirmed**：外部动作已发生且后置条件满足。Command/Resource/Attempt/Activation/Token 在一个协调事务中推进；若原节点输出可确定，则提交输出、Activation=COMPLETED、Token=CONSUMED 并创建后继 Token。
- **NoEffectProven**：有权威证据证明动作没有发生。Command 标记为经核验未执行，Attempt→ABANDONED_SAFE，Activation→READY 或 RETRY_WAIT，Token→READY 或 WAITING，按 retry policy 决定。
- **Unresolved**：证据不足或互相矛盾。Activation/Attempt/Command/相关 ResourceLease 进入 UNKNOWN/QUARANTINED；Run 进入 MANUAL_REQUIRED 或 QUARANTINED；不得创建新 Attempt 或下游 Token。

核验事务必须记录 evidenceRef、evidenceHash、核验来源、操作者/自动核验器身份、核验时间、对象版本和决策。核验事实、状态变化、Runtime Event、审计记录及必要 Outbox 同事务提交。

## 31.3 Split / Branch / Join 的闭合语义

### 31.3.1 实际激活集合

Split 创建时必须先由编译后的 GatewayPlan 确定本次实际激活集合 A。A 是本次 SplitActivation 的不可变快照；expectedBranchIds=A。AND 语义下 A 为全部配置分支；XOR 语义下 A 必须恰有一个分支；OR 语义下 A 为运行时条件选中的非空子集。未被选中的静态分支不创建 BranchActivation、不创建 Token，也不计入 Join expectedBranchIds。

### 31.3.2 SKIPPED 的定义

SKIPPED 仅表示“已激活的 BranchActivation 按显式业务规则完成了无动作跳过”，不是“该静态分支没有被激活”。未激活分支不在 expectedBranchIds 中。Join 是否接受 SKIPPED 必须由编译期 JoinPolicy 固定；默认 ALL_REQUIRED 不接受 SKIPPED。若允许，必须明确该分支的输出为空值/默认值语义并通过输出 Schema。

### 31.3.3 Join 状态机和释放事务

JoinActivation 状态集合：CREATED、WAITING、RELEASED、CLOSED_FAILED、QUARANTINED。创建 Split 时同步创建唯一 JoinActivation(CREATED)，事务提交前不得暴露半初始化状态。所有分支 Token 到达后 Join 为 WAITING。

Branch Arrival 事务校验 Branch=ACTIVE、Token=IN_FLIGHT、身份与配对 Join 匹配；同一事务将 Branch→ARRIVED、Branch Token→WAITING、冻结 BranchOutput 并写事件。重复到达不得重复计数。

满足 Join predicate 时，在同一事务中执行：
1. 锁定 SplitActivation、JoinActivation 及本 Split 全部 BranchActivation/Token；
2. 校验 expectedBranchIds 与实际 BranchActivation 集合完全一致；
3. 校验所有 expected branch 为 ARRIVED，或为 JoinPolicy 明确接受的 SKIPPED；
4. 校验嵌套结构闭合、BranchOutput 完整；
5. Split→CLOSED_SUCCESS；
6. Join→RELEASED；
7. 所有参与该 Join 的 Branch Token WAITING→CONSUMED；
8. 所有 BranchActivation ARRIVED/SKIPPED→终结态 CLOSED（Branch 状态增加 CLOSED；保留原 arrivalOutcome 字段）；
9. 创建唯一 downstream Token=READY；
10. 写 JoinReleased Event 和 SchedulerWakeup Outbox；
11. 提交。

数据库唯一约束至少保证 (splitActivationId, branchId) 唯一、每个 splitActivationId 只有一个 Join release、每个 Join 只有一个 downstream Token。两个最后分支并发到达时，通过 Split/Join 聚合锁或 CAS 串行化；第二个事务读取第一个已提交的 ARRIVED 后再判断是否释放。失败、UNKNOWN、QUARANTINED 分支永远不能伪装成 ARRIVED。Branch Token 在 Join 释放后不得残留 WAITING。

## 31.4 Pause / Stop / Failure Drain 协议

### 31.4.1 统一准入线性化点

所有 Dispatch Commit 和 Pause/Stop Request 都必须锁定同一 workflow_run 行，或使用等价的单行 CAS 条件。Dispatch Commit 的条件更新必须包含 run.status=RUNNING、coordinatorEpoch 匹配、Run version 匹配。Pause/Stop Request 也在同一 Run 行上提交状态变化。

因此：
- Dispatch Commit 先提交：该 Dispatch 属于在途集合，必须被 Drain 收敛；
- Pause/Stop Request 先提交：之后 Dispatch Commit 必须失败，Reservation 按本节规则撤销；
- 不存在“Pause 已提交但后续普通 Dispatch 仍成功提交”的合法执行序列。

### 31.4.2 Drain 状态转移

| 当前 Run | 事件 | 条件 | 下一状态 | 原子动作 |
|---|---|---|---|---|
| RUNNING | PauseRequested | requestId 新且授权有效 | PAUSE_REQUESTED | 关闭 Dispatch 准入 |
| PAUSE_REQUESTED | DrainStarted | Drain Coordinator 已取得当前 Run epoch | PAUSE_DRAINING | 固化 drainId 与在途对象快照/游标 |
| RUNNING / PAUSE_REQUESTED / PAUSE_DRAINING / PAUSED / RESUME_REQUESTED | StopRequested | requestId 新 | STOP_REQUESTED | Stop 优先，关闭 Dispatch 准入 |
| STOP_REQUESTED | DrainStarted | Drain Coordinator 已取得当前 Run epoch | STOP_DRAINING | 固化 drainId 与停止收敛集合 |
| PAUSE_DRAINING | DrainComplete | 满足 PauseSafePredicate | PAUSED | 写 RunPaused |
| STOP_DRAINING | DrainCompleteSuccess | 满足 StopSafePredicate | STOPPED | 写 RunStopped |
| STOP_DRAINING | DrainCompleteFailure | 失败收敛完成、无未知物理状态 | FAILED | 写 RunFailed |
| PAUSE_DRAINING / STOP_DRAINING | ManualDispositionRequired | 需要授权业务决策 | MANUAL_REQUIRED | 建立人工工作项 |
| PAUSE_DRAINING / STOP_DRAINING | UnresolvedPhysicalState | 命令/资源无法核验 | QUARANTINED | 隔离命令和资源 |

Run 状态变更与 drainId 创建、DrainStarted Event、DrainCoordinator Outbox 同事务提交。DrainStarted 消费可重复；同一 Run 同一时刻只有一个活动 drainId。Stop 请求可将任何尚未终结的 Pause Drain 转为 Stop Drain；旧 drainId 失效，未完成收敛对象由新 Stop Drain 继承，不得丢弃。

### 31.4.3 Reservation 和在途对象处理

Drain 快照中的对象按以下规则处理：

- READY 且尚无 Dispatch Commit：Pause 保留 READY Token 作为 Resume continuation，Scheduler 仍受关闭的 Dispatch gate 约束；Stop 将 READY Token→INVALIDATED，并将其未派发 Activation 转 CANCELLED。该规则也适用于在 Drain 期间由已完成在途节点新产生的 READY 后继 Token，必须由 Reservation Reconcile 重复扫描捕获。
- RESERVED 且无 Dispatch Outbox：Token RESERVED→READY（Pause）或 INVALIDATED（Stop）；若已创建 Attempt，则 Attempt CREATED→ABANDONED_SAFE，Activation 保持 READY（Pause）或转 CANCELLED（Stop）；撤销软件调度配额。仅当 ResourceLease 尚未取得物理资源所有权时才可释放该软件预留。
- DISPATCH_PENDING 但 Dispatch Commit 尚未提交：按 RESERVED 处理。
- Dispatch Commit 已提交（Token=IN_FLIGHT）：视为已派发，不得假定 Worker 未接收；等待 Worker/DispatchLease 核验。纯计算可按 Handler 的安全取消协议收敛；副作用节点必须核验。
- ACTIVE 纯计算节点：仅在 Handler 明确声明 cancellationSafe 且报告安全检查点后，才能在同一事务创建/更新 WaitRegistration、将 Activation→SUSPENDED、Attempt→SUSPENDED、Token IN_FLIGHT→WAITING，并记录 checkpoint/continuation；否则等待完成或转 MANUAL_REQUIRED。
- SUSPENDED 且无未决命令的 Activation：可作为可恢复安全点保留。
- 设备命令 ACKNOWLEDGED/RUNNING：只有设备明确确认进入 PauseSafeState 或 StopSafeState，或自然完成且后置条件已核验，才视为收敛。
- Command/Resource UNKNOWN：不满足 PauseSafePredicate/StopSafePredicate，必须 MANUAL_REQUIRED 或 QUARANTINED。

### 31.4.4 Drain 完成谓词

PauseSafePredicate 必须全部满足：
1. Run 已关闭新 Dispatch 准入；
2. 不存在 RESERVED Token 和未完成的 Dispatch Commit；
3. 所有已派发 Worker 都已完成、已安全挂起或经证明未开始；
4. 所有设备动作均已完成，或设备明确确认处于定义的 PauseSafeState；
5. 所有 WaitRegistration 已持久化，且其关联命令状态符合暂停策略；
6. 所有资源租约均保持有效或已通过设备事实安全释放；不得因 TTL 到期视为空闲；
7. 无未决 UNKNOWN、无未处理关键 Inbox、无待执行的会改变本 Run 状态的必要内部消息。

StopSafePredicate 必须全部满足：
1. 不存在新 Dispatch；
2. 所有已派发 Worker 已完成或安全终止；
3. 所有设备命令均已进入允许的确定最终状态；不得存在 DELIVERY_UNKNOWN、UNKNOWN、ACKNOWLEDGED、RUNNING 或 CANCEL_REQUESTED；
4. 所有 ResourceLease 均为 RELEASED；若资源只能隔离而不能证明释放，则本谓词不成立，必须走 MANUAL_REQUIRED/QUARANTINED 分支；
5. 无未决物料/板位/耗材副作用；
6. 所有 Token/Activation/Split/Join/Loop/Wait 均已关闭、终结或 INVALIDATED；STOPPED/FAILED 不得保留可恢复的 READY/WAITING/RESERVED/IN_FLIGHT 路径。Drain 必须重复扫描并处理在途节点完成后新产生的 READY Token；在物理副作用安全收敛前，不得提前关闭 Wait/Retry/Split/Branch/Join/Loop 以满足本谓词；
7. 无未处理关键 Inbox。

设备仍在运行但没有得到设备暂停确认时，不得进入 PAUSED。设备状态未知时，不得进入普通 STOPPED 或释放资源。Drain 超时只触发告警/人工介入，不改变上述谓词。

## 31.5 Command、CommandOutbox 与 Dispatch 的分离状态机

三者是不同事实，必须分别持久化。

### 31.5.1 Command Ledger

Command 状态集合：CREATED、PREPARED、DISPATCHING、DELIVERY_UNKNOWN、ACKNOWLEDGED、RUNNING、CANCEL_REQUESTED、SUCCEEDED、FAILED、REJECTED、CANCELLED_CONFIRMED、QUARANTINED。

- CREATED→PREPARED：参数、能力、资源前置条件已冻结；
- PREPARED→DISPATCHING：DispatchAttempt 已持久化，准备调用 Adapter；
- DISPATCHING→ACKNOWLEDGED：收到可关联的设备接收确认；
- DISPATCHING→DELIVERY_UNKNOWN：调用超时、进程崩溃或无法证明送达；
- DISPATCHING→REJECTED：设备明确证明拒绝且未执行；
- ACKNOWLEDGED→RUNNING：设备报告开始执行；
- ACKNOWLEDGED/RUNNING→SUCCEEDED/FAILED：收到最终事件且后置条件核验完成；
- ACKNOWLEDGED/RUNNING→CANCEL_REQUESTED：发出取消请求，但不代表停止；
- CANCEL_REQUESTED→CANCELLED_CONFIRMED：设备确认物理停止且状态回读满足；
- DELIVERY_UNKNOWN→ACKNOWLEDGED/RUNNING/SUCCEEDED/FAILED/REJECTED：仅凭权威查询/事件核验；
- 无法核验的命令或资源→QUARANTINED。

任何最终事件都必须校验 commandId、deviceId、operation、设备事件身份/序号和允许的状态迁移。ACK 不等于动作完成；成功事件不替代节点后置条件核验。

### 31.5.2 CommandOutbox

CommandOutbox 状态集合：PENDING、CLAIMED、SEND_STARTED、DELIVERY_CONFIRMED、DELIVERY_UNKNOWN、CLOSED、DEAD_LETTER。

Outbox claim 的租约只用于 Dispatcher 所有权，不证明设备是否收到命令。CLAIMED 过期可重新 claim；若已经进入 SEND_STARTED，过期后不能按普通消息策略自动重新调用 Adapter。必须先按 Adapter 能力核验或转 DELIVERY_UNKNOWN。CommandOutbox 状态与 Command Ledger 状态必须在同一数据库内的对应事务中协调更新，但不得把它们合并成一个 status 字段。

### 31.5.3 安全重发决策

| Adapter 能力 | SEND_STARTED 后结果未知 | 唯一允许行为 |
|---|---|---|
| 支持相同 commandId 幂等 | 可证明设备端幂等键覆盖整个执行生命周期 | 使用原 commandId、原 payloadHash 重试；不得创建新 commandId |
| 支持按 commandId 权威查询 | 查询接口能区分不存在、执行中、成功、失败 | 先查询；仅查询证明未接收/未执行时按协议重发 |
| 两者均不支持 | 无法证明是否执行 | Command→DELIVERY_UNKNOWN；隔离资源并人工核验，不自动重发 |

即使支持 commandId 幂等，也必须保证相同 commandId 对应不可变 payloadHash；同一 commandId 不同 payload 一律协议冲突。新 commandId 代表新的逻辑物理操作，不能用于规避旧命令 UNKNOWN。

## 31.6 ResourceLease 状态机

ResourceLease 状态集合：REQUESTED、ACQUIRING、ACQUIRED、RELEASE_PENDING、RELEASED、EXPIRED_UNVERIFIED、QUARANTINED。

| 当前 | 事件 | 条件 | 下一状态 |
|---|---|---|---|
| REQUESTED | AcquireStarted | 资源准入成功 | ACQUIRING |
| ACQUIRING | AcquireConfirmed | 资源服务/设备确认占用，fencingToken 已分配 | ACQUIRED |
| ACQUIRING | AcquireFailedNoEffect | 证明未占用 | RELEASED |
| ACQUIRED | ReleaseRequested | 关联命令已达到允许释放条件 | RELEASE_PENDING |
| RELEASE_PENDING | ReleaseConfirmed | 设备/资源服务确认空闲 | RELEASED |
| ACQUIRED / RELEASE_PENDING | LeaseTtlExpired | TTL 到期但物理状态未核验 | EXPIRED_UNVERIFIED |
| EXPIRED_UNVERIFIED | ReconciledFree | 权威状态回读证明空闲 | RELEASED |
| 任意非 RELEASED | StateUnknown/OwnershipConflict | 物理占用或 owner 无法核验 | QUARANTINED |
| QUARANTINED | QuarantineCleared | 授权核验确认物理空闲并完成审计 | RELEASED |

同一 resourceId 同一时刻最多一个 ACQUIRING/ACQUIRED/RELEASE_PENDING/EXPIRED_UNVERIFIED/QUARANTINED owner；QUARANTINED 仍然占据资源准入，不允许新 owner 获取。多资源原子获取采用 ResourceAcquisitionGroup：所有资源全部确认后才将组置 ACQUIRED；部分成功必须逐项补偿释放，无法确认释放的资源隔离，不能假装整个组回滚。

fencingToken 必须由资源权威方单调递增分配，并由实际接受操作的一侧验证。若设备本身不理解 fencingToken，Adapter/设备网关必须提供等价的单 owner 串行化；单纯在 Runtime 数据库递增 token 不能阻止旧请求到达物理设备。

## 31.7 Inbox、Event、Checkpoint 与 Coordinator 一致性

### 31.7.1 Inbox identity conflict

Inbox 的唯一键为 (source, messageId)。同 key 同 payloadHash 是重复消息，返回首次处理结果；同 key 不同 payloadHash 是身份冲突，第二条冲突输入必须作为独立 ProtocolViolation 记录，包含原 hash、新 hash、source、接收时间和原始输入引用。不得覆盖原 Inbox payload/hash，也不得把原消息改写成第二条消息的拒绝记录。

### 31.7.2 RunCommitVersion 与 eventSeq

每个 Run 增加单调递增的 runCommitVersion。任何改变 Run 聚合状态的事务：
1. 锁定 workflow_run；
2. 校验 expectedVersion/coordinatorEpoch；
3. runCommitVersion 加一；
4. 在同一事务中为本次提交产生的 Runtime Event 分配连续 eventSeq 区间；
5. 写状态、Event、Outbox、Inbox processed；
6. 一次提交。

eventSeq 在同一 Run 内严格递增，但允许因事务回滚前的数据库实现细节产生未提交序号空洞；语义只要求已提交事件严格有序，不要求数值无缺口。Event 的排序键为 (runId, eventSeq)，同一事务内事件顺序由 TransitionPlan 明确给出，不得依赖 Map 遍历或数据库返回顺序。

### 31.7.3 Checkpoint 一致快照

Checkpoint 必须在一个一致性数据库快照中读取 Run、Token、Activation、Attempt、Split/Branch/Join、Loop、Variable、Wait、Command、Lease 和 Inbox/Outbox 投影。记录 runCommitVersion、eventSeqHighWatermark、coordinatorEpoch、definitionHash、payloadHash。所有读取必须对应同一 MVCC snapshot/read timestamp；若数据库不支持跨表一致快照，必须通过 Run 锁定并暂停该 Run 的状态提交，或使用等价快照协议。禁止多次非一致读取拼接快照。Checkpoint 只加速恢复，不能取代 Event/Command Ledger 的事实记录。

### 31.7.4 Coordinator epoch fencing

Coordinator owner 获取/续期事务必须在 Run 行上 CAS 更新 ownerId、coordinatorEpoch 和 ownerLeaseVersion。**只有由 Coordinator/Dispatcher owner 发出的控制类命令**必须校验 envelope.coordinatorEpoch 等于当前 Run epoch；旧 Coordinator/Dispatcher 发出的调度、取消、租约管理等控制消息不得推进 Runtime。Worker completion、设备事件和外部 Wait 事件不是 Coordinator owner 命令，不得仅因其携带派发时的旧 epoch 而丢弃：Worker completion 必须匹配当前有效的 activationId、attemptId、dispatchId、attemptVersion，并匹配该 Attempt 持久化的 dispatchEpoch；当前 Coordinator 在自己的有效 epoch 下消费并提交该结果。设备事件按 commandId/deviceId/sourceEventId/设备序号核验，外部 Wait 事件按 waitId/correlationKey 核验。旧 epoch 的 Worker/设备/Wait 消息可以进入 Inbox；只有对象身份和当前状态校验通过时，才由当前 Coordinator 推进 Runtime。Worker/Dispatcher 只能提交结果，不得直接写 Run 控制流投影。恢复期间 Inbox 可以接收消息，但在新 Coordinator 完成恢复检查并提交 RecoveryCompleted 之前，不处理会推进状态的消息或开放 Scheduler。

## 31.8 Run 终态谓词与人工处置事务

### 31.8.1 SUCCEEDED

Run 只有在以下条件全部成立时才能 SUCCEEDED：
- 所有控制流 Token 均为 CONSUMED、TERMINAL 或按定义安全关闭；不存在 READY、RESERVED、IN_FLIGHT、WAITING；
- 所有 Activation 均为 COMPLETED、FAILED/CANCELLED 的允许终结路径，且成功 Run 不存在未处置 FAILED、UNKNOWN、MANUAL_REQUIRED、QUARANTINED；
- 所有 Split/Join/Loop 均闭合；
- 所有 WaitRegistration 均 SATISFIED/EXPIRED/CANCELLED 且无未决 continuation；
- 所有 Attempt 均处于允许的关闭状态，不存在 DISPATCH_PENDING、DISPATCHED、ACCEPTED、STARTED、UNKNOWN；
- 所有 Command 均为 SUCCEEDED、FAILED（仅在流程显式接受该结果时）或 CANCELLED_CONFIRMED；不存在 DELIVERY_UNKNOWN、ACKNOWLEDGED、RUNNING、CANCEL_REQUESTED；
- 所有 ResourceLease 均 RELEASED；不存在 EXPIRED_UNVERIFIED/QUARANTINED；
- 无未处理关键 Inbox、无会改变 Run 结果的必要 Outbox、无未处置人工工作项；
- 所有节点输出、物料/板位/耗材副作用及后置条件均已提交。

### 31.8.2 FAILED / STOPPED

FAILED 表示业务执行失败且所有必要副作用已确定并安全收敛；STOPPED 表示经请求停止且所有必要副作用已确定并安全收敛。二者均不得携带未决物理动作或被普通终态隐藏的资源隔离。若有未知物理状态，Run 必须保持 QUARANTINED 或 MANUAL_REQUIRED，而不是强行置 FAILED/STOPPED。

### 31.8.3 人工处置

ManualResolution 必须是不可变记录，包含 interventionId、对象身份/版本、decision、evidenceRef/evidenceHash、操作者、授权依据、时间、理由、后置条件验证结果。T_ManualResolution 事务同时更新关联 Command/Lease/Attempt/Activation/Token/Run 状态、写 Runtime Event、关闭或更新人工工作项、写必要 Outbox。人工处置只能选择预定义合法决策；不得直接编辑状态字段或跳过状态机。QUARANTINED 解除必须有独立 T_QuarantineClear 事务和资源权威方核验。

## 31.9 事务目录修订

原 T01–T14 保持不变；原 T15 保持 Pause/Stop Request 语义；原 T16 的 Drain Complete 语义拆分为 T16–T19；原 T17 Checkpoint 顺延为 T20。自本章起，T01–T23 是唯一规范事务编号；新增事务不得与设备/Worker 外部调用包在数据库事务内。

| 事务 | 名称 | 核心原子边界 |
|---|---|---|
| T01–T14 | 原定义事务 | 按 §25 保留；并受 §31.2–§31.7 的补充约束 |
| T15 | Pause/Stop Request | Run 状态、requestId、关闭 Dispatch 准入、Drain Outbox |
| T16 | Drain Start | drainId、在途对象快照/游标、DrainStarted Event |
| T17 | Reservation Reconcile | RESERVED Token、Dispatch Outbox/Dispatch 状态、软件配额回收 |
| T18 | Safe Point / Device Stop Progress | 单个 Activation/Command/Lease 的安全收敛事实及事件；设备调用在事务外 |
| T19 | Drain Complete | 重算安全谓词、Run 最终状态、DrainCompleted Event |
| T20 | Checkpoint | 一致性快照、runCommitVersion、eventSeqHighWatermark |
| T21 | Unknown Reconciliation | 核验证据、Command/Lease/Attempt/Activation/Token 一致推进 |
| T22 | Manual Resolution | 人工决策、证据、相关对象状态、审计与 Outbox |
| T23 | Quarantine Clear | 资源物理核验、解除隔离、准入恢复 |

T17–T19 的 Drain 过程可以由多个事务多次推进；每次只提交已证明的事实。T19 必须重新读取并验证完整 Drain predicate，不能信任早先 Drain Start 时的快照作为完成证明。T21–T23 必须使用对象版本 CAS，并与 Run 行锁/Coordinator epoch 校验组合。

## 31.10 RSM-01 至 RSM-11 修订追踪

| 第一轮问题 | 本版落实位置 | 修订结果 |
|---|---|---|
| RSM-01 Retry/Suspend/Resume 不闭合 | §31.2、§31.2.2–§31.2.4 | 统一三对象状态组合；Wait/Retry 唯一恢复路径；UNKNOWN 核验事务 |
| RSM-02 Pause/Stop Drain 不完整 | §31.4、§31.9 | 增加 Drain Start、Reservation Reconcile、安全点和最终判定 |
| RSM-03 Join 后 Branch Token 残留 | §31.3 | Join release 同事务消费全部分支 Token、关闭 Branch |
| RSM-04 Dispatch 状态不一致 | §31.2、§32.1 | 增加 ACCEPTED、dispatchId，分离派发与执行 |
| RSM-05 Command/Outbox 未知结果 | §31.5 | 分离 Command/Outbox 状态，定义安全重发能力矩阵 |
| RSM-06 UNKNOWN/人工处置缺闭环 | §31.2.4、§31.8、§31.9 | 增加核验、人工处置、解除隔离事务 |
| RSM-07 ResourceLease 无状态机 | §31.6 | 增加租约状态转移、组获取和 fencing 约束 |
| RSM-08 Inbox 冲突处理不足 | §31.7.1 | 保留原消息，冲突独立审计 |
| RSM-09 Event/Snapshot 线性化不足 | §31.7.2–§31.7.3 | runCommitVersion、eventSeq、MVCC 快照 |
| RSM-10 Run 终态谓词不足 | §31.8.1–§31.8.2 | 明确 SUCCEEDED/FAILED/STOPPED 前置条件 |
| RSM-11 Coordinator fencing 不完整 | §31.7.4 | epoch CAS、旧 owner 拒绝、恢复期间消息闸门 |

---

# 32. 修订后的关键事务补充规范

## 32.1 T03/T04 Dispatch 的原子边界

Reservation 事务只保留软件配额和 Token RESERVED，不得把“已获得设备物理资源”与“调度配额预留”混为一谈。物理资源必须通过 ResourceLease 状态机独立获取并持久化。

Dispatch Commit 事务必须锁定 Run、Token、Activation、Attempt，验证 Run=RUNNING、Coordinator epoch、Token=RESERVED、Reservation 未过期、Lease 有效、attemptId/dispatchId 未使用；同事务写 Attempt=DISPATCH_PENDING、Activation=ACTIVE、Token=IN_FLIGHT、WorkerDispatch Outbox 和事件。Dispatcher 领取 Outbox 后事务外调用 Worker；Dispatcher 开始发送时将 Attempt DISPATCH_PENDING→DISPATCHED 并记录 dispatchEpoch。Worker 接收消息只将 Attempt DISPATCHED→ACCEPTED；Handler 真正开始时由独立 NodeStarted 消息将 Attempt ACCEPTED→STARTED。两条消息均按 messageId/dispatchId 幂等处理。若 Worker 在接受后、开始前崩溃，必须通过 Worker 执行器的接受/开始事实及副作用分类核验；不能仅因没有 NodeStarted 就断言未执行。

## 32.2 T07/T08 Suspend 与 Wait 恢复

T07 提交 Wait、Activation、Attempt、Token 四者状态必须原子一致。T08 的成功恢复路径将 Wait 从 ACTIVE 改为 SATISFIED，将 Token/Activation 置 READY，并将旧 Attempt SUSPENDED→ABANDONED_SAFE；若 Wait 到期则按 Definition 中编译的 timeout edge/failure policy 提交唯一超时结果，不得同时执行满足路径。不得在 T08 内复用旧 Attempt。下一次 T03 创建新 Attempt。设备仍未完成的动作继续由 Command Ledger 管理，不因 Worker 已释放而被当作完成。

## 32.3 T10 Join 原子关闭

T10 满足 Join predicate 时，必须把所有参与分支 Token 从 WAITING 改为 CONSUMED，并关闭 BranchActivation；同时 Split CLOSED_SUCCESS、Join RELEASED、唯一 downstream Token READY。所有写入同一事务。T10 的幂等结果以 splitActivationId/joinActivationId 为键，不以 arrivedCount 单独判断。

## 32.4 T13 Command Dispatch

T13 的 SEND_STARTED 是物理调用可能发生的线性化前沿。事务外调用 Adapter 前，必须已提交 commandId、payloadHash、DispatchAttempt、Dispatcher claim 和 SEND_STARTED。调用结果无法确认时必须写 DELIVERY_UNKNOWN；若进程在 SEND_STARTED 提交后、实际调用前崩溃，也按可能送达处理，除非 Adapter 查询/幂等协议能证明未执行。不得根据本地“调用代码还没运行”的推测自动重发。

## 32.5 T15–T19 Pause/Stop

T15 只负责关闭 Dispatch 准入并记录请求；T16 固化 Drain 身份和待收敛对象；T17 处理尚未 DispatchCommitted 的 Reservation；T18 逐项提交 Worker/设备/资源的安全收敛事实；T19 重新计算完整安全谓词并提交 PAUSED、STOPPED、FAILED、MANUAL_REQUIRED 或 QUARANTINED。外部设备取消/暂停调用必须在事务外执行，其请求和结果通过 Command Ledger 与 Inbox 回到 Runtime。

## 32.6 T20–T23

Checkpoint 使用 §31.7.3 的一致快照协议。Unknown Reconciliation、Manual Resolution、Quarantine Clear 使用 §31.2.4、§31.6、§31.8 定义的证据和 CAS 规则。任何事务不得只更新 Run 而遗漏相关 Token、Activation、Attempt、Command 或 ResourceLease。

---

# 33. 修订后的异常路径裁决

| 故障/竞态 | 唯一裁决 |
|---|---|
| RetryDue 与 Pause/Stop 同时发生 | Run 行锁/CAS 决定顺序；非 RUNNING 时不得产生 READY Token |
| WaitSatisfied 与 WaitExpired 同时发生 | waitId 状态 CAS 只允许一个胜者；败者记迟到事件 |
| WaitSatisfied 重复 | 返回首次处理结果，不重复创建 Token/Attempt |
| 旧 Attempt completion 到达 | 记录 STALE_COMPLETION，不修改当前 Activation/Token |
| Worker Accepted 后、Started 前崩溃 | 纯计算且可证明未开始可 ABANDONED_SAFE；否则按副作用分类 UNKNOWN |
| Pause 与 Dispatch Commit 竞争 | 同一 Run 行线性化；Dispatch 先提交则纳入 Drain，Pause 先提交则 Dispatch 拒绝 |
| Stop 与 Pause 竞争 | Stop 优先；继承未完成收敛对象，旧 Pause drainId 失效 |
| 两个 Branch 同时最后到达 | Split/Join 锁或 CAS 串行；一个事务释放 Join，另一个读取已 RELEASED 幂等返回 |
| Join 释放后重复 Branch Arrival | Branch 已 CLOSED/ARRIVED，拒绝重复效果；不改变 Token |
| SEND_STARTED 后 Dispatcher 崩溃 | 按可能送达；只按 Adapter 幂等/查询能力决定，其他情况 UNKNOWN |
| Command 事件乱序 | 按 commandId + sourceEventId + 设备序号/合法迁移处理；不能以时间戳覆盖 |
| Lease TTL 到期 | EXPIRED_UNVERIFIED；资源仍禁止新 owner 获取 |
| Coordinator epoch 变化后旧 Worker 返回 | 若是旧 Coordinator/Dispatcher 控制命令则拒绝；若是 Worker completion，则按持久化 dispatchEpoch 与当前 attempt/dispatch 身份校验，由新 owner 决定接受或作为迟到结果拒绝 |
| COMMIT 响应丢失 | 使用原 requestId/messageId 查询；存在则返回原结果，不存在才允许原身份重试 |
| 同 Inbox identity 不同 payload | 保留原 Inbox；新增 ProtocolViolation，不覆盖原始消息 |
| Checkpoint 与事件提交并发 | MVCC 同一快照和游标；无法保证则不创建 checkpoint |
| Run 满足图完成但 Lease 未释放 | 不得 SUCCEEDED；等待核验或隔离 |
| 设备仍运行但 Pause 超时 | 继续 PAUSE_DRAINING 或转人工/隔离；不得 PAUSED |
| 设备状态未知但 Stop 超时 | MANUAL_REQUIRED/QUARANTINED；不得 STOPPED 或释放资源 |

---

# 34. 修订后的状态不变量

除 §27 原有不变量外，增加：

1. Token=WAITING 必须且只能关联一个有效 wait owner（WaitRegistration、RetryRegistration 或 Join 等结构 owner）。
2. Activation=RETRY_WAIT 必须关联唯一 ACTIVE RetryRegistration 和一个 RETRYABLE_FAILED sourceAttempt。
3. Activation=SUSPENDED 必须关联唯一 ACTIVE WaitRegistration；其当前 Worker Attempt 不得仍拥有执行权。
4. 每个 Attempt 的 dispatchId 唯一；每个 Dispatch Outbox 只对应一个 attemptId/dispatchId。
5. Attempt=ACCEPTED 不代表 Handler 已开始；Attempt=STARTED 才表示 Handler 已开始。
6. 一个 Join RELEASED 时，其所有参与 Branch Token 必须 CONSUMED，BranchActivation 必须关闭。
7. Split expectedBranchIds 创建后不可变，且必须等于本次实际激活集合。
8. CommandOutbox=SEND_STARTED 后，不得按普通 Outbox at-least-once 规则自动重发非幂等设备调用。
9. ResourceLease=EXPIRED_UNVERIFIED 或 QUARANTINED 时，资源不得授予新 owner。
10. Run=PAUSED 必须满足 PauseSafePredicate；Run=STOPPED/FAILED 必须满足 StopSafePredicate。
11. Run=SUCCEEDED 必须满足 §31.8.1 全部终态条件。
12. 每个 Run 的状态写事务必须校验当前 coordinatorEpoch；旧 epoch 不能写入控制流投影。
13. RunCommitVersion 与同一事务 Runtime Event 的 eventSeq 分配必须原子提交。
14. Checkpoint 的所有对象投影必须来自同一一致性快照。
15. 人工处置必须有证据、授权、对象版本和完整审计，禁止直接修改状态字段。

---

# 35. 修订后的验收矩阵增补

原 §29 的验收矩阵继续有效，并增加以下强制测试。测试必须同时断言数据库状态、Runtime Event、Inbox/Outbox、Command Ledger、ResourceLease 和可调度 Token 集合，不得只检查 Run 最终状态。

## 35.1 状态组合穷举

对以下组合生成状态×事件测试：
- Token × Activation × Attempt；
- Run × Token Reservation/Dispatch；
- Wait × RetryRegistration × Activation；
- Split × Branch × Join × Branch Token；
- Command × CommandOutbox × ResourceLease；
- Run × Drain × DispatchLease；
- CoordinatorEpoch × WorkerCompletion。

所有非法组合必须在事务提交前拒绝；任何拒绝不得产生部分状态写入。

## 35.2 必测不变量用例

1. RETRY_WAIT 时 Token 不可被 Scheduler 派发。
2. RetryDue 发生在 PAUSE_REQUESTED/PAUSE_DRAINING 时不产生 READY Token。
3. Suspend 后旧 Attempt 不可重新 STARTED。
4. WaitSatisfied 后旧 Attempt completion 不可覆盖 continuation。
5. Join RELEASED 后不存在 WAITING Branch Token。
6. 未激活的 OR 分支不创建 BranchActivation。
7. SKIPPED 只有在 JoinPolicy 明确允许时才计入 predicate。
8. Worker ACCEPTED 不等于 STARTED。
9. DispatchCommit 与 PauseRequest 竞态只能有一个线性化结果。
10. Stop 可继承 Pause Drain 的未完成对象且不丢失命令/租约。
11. SEND_STARTED 崩溃恢复不会对非幂等设备自动重发。
12. commandId 相同而 payloadHash 不同必须协议拒绝。
13. ResourceLease TTL 过期不会让资源重新可用。
14. UNKNOWN 核验没有 evidenceRef/evidenceHash 时不能解除。
15. 人工处置不能只改变 Run 状态。
16. Run 有任何未决 Command/Lease 时不能 SUCCEEDED。
17. Run 有未知物理状态时不能 STOPPED/FAILED。
18. 旧 Coordinator/Dispatcher epoch 的控制命令不能改变 Run/Token/Activation；Worker completion 即使携带旧 dispatchEpoch，只要匹配当前有效 attemptId/dispatchId/attemptVersion 且由当前 Coordinator 校验提交，仍可正常完成。
19. Checkpoint 各对象版本必须对应同一 runCommitVersion/eventSeq 游标。
20. Inbox identity conflict 不覆盖原消息内容。

## 35.3 故障注入扩展

原 T01–T17 故障注入扩展为 T01–T23，并对以下外部边界增加崩溃点：
- Worker Dispatch Outbox 提交后、发送前；
- Worker 接收后、Accepted 回执前；
- Accepted 提交后、Handler 开始前；
- Handler 开始后、Completion 提交前；
- Wait 满足与 Timer 过期同时提交；
- Adapter SEND_STARTED 提交后、调用前；
- Adapter 调用后、结果记录前；
- 设备事件 Inbox 提交后、Command Ledger 更新前；
- ResourceLease 获取成功但确认消息丢失；
- Release 请求发送后、物理空闲确认前；
- Coordinator epoch 切换后旧 Worker completion 到达；
- Checkpoint 快照期间并发 Runtime Event 提交。

恢复后必须证明：无重复物理动作、无双重 Join、无孤儿 WAITING Token、无未关联资源、无旧 owner 状态覆盖、无未审计人工跳转。

---

# 36. 实施冻结条件

v2.1 状态机闭合版的冻结不代表数据库、设备 Adapter 和 Worker 的技术选型已完成。进入核心实现前必须完成：

1. 本章全部状态集合和转移表进入自动化状态×事件测试；
2. T01–T23 均有事务读集合、写集合、幂等键、CAS 条件、提交后 Outbox 和失败规则；
3. Pause/Stop 与 Dispatch 的线性化测试通过；
4. Retry/Suspend/Resume 与 Token/Activation/Attempt 组合测试通过；
5. Join release 的 Branch Token 终结测试通过；
6. Command/Outbox/ResourceLease 故障注入通过；
7. UNKNOWN/Manual/Quarantine 核验路径通过；
8. Coordinator epoch 和 Checkpoint 一致性测试通过；
9. §35 的强制测试全部通过；
10. 第二轮独立反向评审中所有阻断项关闭；未关闭项必须明确标记为实现前阻断，不得以“后续完善”放行。

---

# 37. 第二轮反向评审记录（2026-11-19）

本轮基于提交后回读的完整 v2.1 文件进行文档级反向检查。评审不包含源码、DDL、真实数据库隔离行为、设备 Adapter 协议实现和物理设备联调。

### 37.1 评审结果

| 检查项 | 结果 | 证据/判定 |
|---|---|---|
| Token / Activation / Attempt 正常执行状态组合 | PASS | §31.2.1 给出组合表；§32.1 区分 Dispatch、Accepted、Started |
| Retry 状态闭合 | PASS | §31.2.2 明确失败、RetryRegistration、RetryDue、新 Attempt 的事务顺序 |
| Suspend / Wait / continuation 闭合 | PASS | §31.2.3、§32.2 明确旧 Attempt 关闭、新 Attempt 创建；WaitSatisfied/WaitExpired 竞争由 waitId CAS 裁决 |
| Join 释放和分支 Token 终结 | PASS | §31.3.3、§32.3 在同一事务关闭 Branch、消费全部分支 Token 并创建唯一下游 Token |
| Pause/Stop 与 Dispatch 线性化 | PASS | §31.4.1 要求同一 Run 行锁/CAS；§31.4.2–§31.4.4 定义 Drain 和安全谓词 |
| Reservation 回收与在途动作收敛 | PASS | §31.4.3 区分软件配额、物理租约、已提交 Dispatch 和安全点 |
| Command / CommandOutbox 未知派发结果 | PASS | §31.5、§32.4 分离状态机；SEND_STARTED 后不对非幂等设备盲目重发 |
| ResourceLease 状态与隔离 | PASS | §31.6 定义获取、释放、过期未核验、隔离和 fencing 规则 |
| Inbox identity conflict | PASS | §31.7.1 保留原 Inbox，并将不同 payloadHash 作为独立 ProtocolViolation |
| Event sequence / Checkpoint | PASS | §31.7.2–§31.7.3 定义 runCommitVersion、eventSeq 和一致快照要求 |
| Coordinator epoch 与 Worker 迟到结果 | PASS | §31.7.4 区分 owner 控制命令与 Worker/设备/Wait 事实消息；有效旧 dispatchEpoch completion 可由当前 owner 核验 |
| Run 成功/失败/停止终态条件 | PASS | §31.8.1–§31.8.2 要求无未决 Token、Command、Lease 和物理副作用 |
| 人工处置和隔离解除 | PASS | §31.8.3、T21–T23 要求证据、授权、对象版本和审计 |
| 事务编号及故障注入范围 | PASS | §31.9 将 T01–T23 定为唯一规范编号；§35.3 覆盖新增边界 |

### 37.2 第二轮发现并已修正的问题

1. **旧 Attempt 关闭缺口：** WaitSatisfied 原先只恢复 Token/Activation，未在同一事务关闭 SUSPENDED Attempt。已修订为同事务转 ABANDONED_SAFE；WaitExpired 也必须关闭旧 Attempt。
2. **Worker Started 越级转移：** Attempt 转移表原允许 DISPATCHED 直接到 STARTED，与 WorkerAccepted 语义冲突。已收紧为仅 ACCEPTED→STARTED。
3. **Coordinator epoch 误伤合法 Worker completion：** 旧版本规则可能拒绝由旧 epoch 派发、但仍有效的 Worker completion。已区分 owner 控制命令与外部事实消息；当前 Coordinator 根据 dispatchEpoch、attemptId、dispatchId 和版本校验后决定接受。
4. **StopSafePredicate 混入隔离结果：** 原谓词允许隔离资源同时作为 Stop 收敛条件，容易误读为仍可进入 STOPPED。已改为只有命令确定终结、所有租约 RELEASED 才满足；否则进入 MANUAL_REQUIRED/QUARANTINED。
5. **事务编号映射歧义：** 已明确 T01–T14 不变、T15 请求不变、原 Drain Complete 拆为 T16–T19、Checkpoint 顺延为 T20；T01–T23 为唯一规范编号。

### 37.3 仍为 UNKNOWN 的外部验证项

以下不是本规范内部已确认的逻辑缺陷，而是仅凭设计文档无法证明的实现/环境事实，必须在技术选型和集成验收中验证：

- **UNKNOWN-01 数据库隔离能力：** 选定数据库是否能按目标方式提供跨表 MVCC 一致快照、Run 行锁/CAS 和事务内 eventSeq 分配。
- **UNKNOWN-02 设备幂等能力：** 每个真实 Adapter 的 commandId 幂等、按 commandId 查询、事件回显和物理状态回读能力是否达到声明等级。
- **UNKNOWN-03 fencing 落点：** 设备/网关是否实际执行 fencingToken 或等价单 owner 串行化，而不只是 Runtime 数据库保存 token。
- **UNKNOWN-04 Worker 执行事实：** Worker 接收、Handler 开始、执行权释放和副作用分类能否可靠持久化并在崩溃后核验。
- **UNKNOWN-05 事务读写集合：** T01–T23 的完整 SQL 读集合、写集合、索引和唯一约束尚未由物理 DDL 验证。

### 37.4 第二轮判定

**文档内部状态机与事务协议：PASS（本轮覆盖的 11 项问题均已落实，第二轮发现的 5 个文本/协议缺口已在本提交修正）。**

**实现可验证性：UNKNOWN（上列 UNKNOWN-01 至 UNKNOWN-05 必须通过数据库、Adapter、Worker 的技术验证和故障注入关闭）。**

因此，v2.1 可以作为下一阶段技术拆解和测试设计基线；不能据此宣称 Runtime 已实现，也不能在 UNKNOWN 项未关闭前宣称设备执行安全性已获实证。

---

# 38. v2.1 修订版规范结论

**Run Lifecycle 决定调度准入和收敛阶段；Token 表达控制流权利；NodeActivation 表达逻辑节点生命周期；Attempt 表达一次 Worker 执行尝试；Dispatch 表达一次派发；Wait/RetryRegistration 表达持久化等待；Split/Branch/Join/Loop 表达结构化控制流；Command Ledger 表达设备动作事实；CommandOutbox 表达设备命令投递状态；ResourceLease 表达物理资源占用事实。**

**任何跨状态机变化必须在明确事务中原子提交。Worker 接收不等于开始，设备 ACK 不等于物理完成，Outbox 重试不等于设备命令可安全重发，Lease TTL 到期不等于设备空闲。所有未知外部副作用必须核验或隔离；所有终态必须满足显式安全谓词。**


---

# 39. T01–T23 数据库事务与验证工程规格

架构层状态机和事务语义已进一步拆解为独立工程规格，作为数据库实现、自动化测试和故障注入的执行基线：

**[LabOS Workflow Runtime：T01–T23 数据库事务与验证设计 v1.0](./LabOS-Workflow-Runtime-T01-T23-数据库事务与测试设计-v1.0-20261119.md)**

该文件以本架构文档 v2.1 为基线，不改变 T01–T23 编号和状态机语义，具体包含：

1. 逻辑持久化模型、唯一约束、索引、全局锁顺序和通用事务模板；
2. T01–T23 每项事务的输入/幂等键、读写集合、锁/CAS、原子步骤、提交后动作、失败恢复和测试 ID；
3. State × Event 状态转移矩阵、属性测试、数据库不变量巡检；
4. CON-01–CON-14 并发线性化测试；
5. T01–T23 故障注入矩阵、设备外部副作用专项、Coordinator/Worker 恢复测试；
6. 数据库实现交付物、CI Gate 和 UNKNOWN 关闭证据。

**规范关系：** 本架构文档定义状态机和业务事务语义；工程规格定义其数据库与测试落地方式。若发现冲突，以状态机安全不变量为约束，先修订并评审两份文档，不允许实现侧自行选择较宽松语义。

**当前状态：** 工程规格已形成文档基线；尚未形成物理 DDL、真实数据库事务实现或自动化测试通过证据。因此本节不代表 T01–T23 已实现，UNKNOWN 项仍按工程规格 §7.4 逐项关闭。
