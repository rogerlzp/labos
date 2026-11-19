# LabOS Workflow Runtime 重构设计 v1.0

- 日期：2026-11-19
- 设计基线：5dd22dca27290e4c1ee34962302bcf428863e22e
- 前置代码提交：5d3260641fdb1591729edf284c9f516f4b7b5140（GraphRunner 6 个字段容器线程安全化）
- 关联方案：docs/并行网关真并发-改造方案-20261119.md
- 文档性质：目标架构与分阶段迁移设计
- 状态：设计稿 v1.0；**未实施，不代表源码已具备本文所述能力**
- 部署边界：沿用当前单实例部署；本版不设计多实例调度与分布式 fencing

---

## 0. 设计结论

本次不把 GraphRunner 从单线程改成“多个分支线程各自跑一段图”，也不把现有千行执行逻辑机械拆成若干个大类。

**目标是把当前过程式 GraphRunner 重构为：单写者图协调器（RunCoordinator）+ 独立节点执行器（NodeExecutor）+ 有界工作池（WorkerPool）+ Run 级执行上下文（RunExecutionContext）的 Workflow Runtime。**

核心约束：

1. **一个 Run 只有一个图协调者。** ready、边激活、节点完成归并、JOIN、循环推进等图级状态只由该协调者修改。
2. **节点执行可以并发。** worker 执行一个节点激活，完成后提交结果，不自行推进下游。
3. **分支不是 GraphRunner 实例。** 分支由执行 Token 表示；Token 是流程控制位置的一次激活，不是线程。
4. **不做 done/visits 的分支快照合并。** 图状态由单写者维护，不存在“分支结束后并集 + max”。
5. **节点执行与节点等待分离。** 短期版本允许有界 worker 执行现有同步设备链；后续版本再把长时间设备等待改为可持久化 SUSPENDED continuation。不得把 Future 被取消当作设备已停止。
6. **流程变量仍是 Run 级共享语义。** 并行写冲突必须有显式规则；分支局部输出与流程共享变量分开。
7. **事件序号分配与事件投递分离。** 序号唯一不等于 listener 调用顺序；事件写入由单一串行分发器负责。
8. **先串行等价重构，再启用并发。** 不能把结构重构、并发、崩溃恢复三个目标塞进一个改动。
9. **保持已确定的设备与事件边界。** 事件归属只认 commandId；设备锁、物理占用、失败收尾、暂停/恢复等既有安全约束不因并发而弱化。

### 0.1 最终目标形态

    Workflow Definition
           |
           v
    WorkflowCompiler ----> Immutable GraphPlan
                                  |
                                  v
                           RunCoordinator
                             |    |    |
                             |    |    +---- RunLifecycle
                             |    |
                             |    +--------- Token / SplitActivation
                             |
                             +-------------- NodeDispatcher
                                                |
                                         Bounded WorkerPool
                                                |
                                            NodeExecutor
                                                |
                                            NodeHandler
                                                |
                              Device / Driver / StepExecutor
                                                |
                                         NodeCompletion
                                                |
                                                v
                                         Coordinator Mailbox

    RunExecutionContext:
      FlowVariableStore / RunProgress / control signals / IDs

    EventDispatcher:
      sequence allocation -> ordered listener delivery -> persistence / monitoring

这是一套进程内模块化运行时，不是微服务拆分。第一阶段不要求新建数据库表，也不要求一次性实现所有节点异步化。

---

## 1. 现状与设计依据

本节只记录设计基线中已核对的事实；目标架构是后续设计，不应反向描述为当前实现。

### 1.1 当前 GraphRunner 的职责与执行方式

基线 5dd22dc 的 GraphRunner.run():

- 初始化 pending、ready、done、ConditionalBranchTracker、visits；
- 使用单线程 while (!ready.isEmpty() && !progress.aborted()) 循环；
- 每次 ready.poll() 取一个节点；
- 在同一循环中完成阶段裁剪、断点续跑、干跑、内置节点、任务节点执行、暂停/物料闸门判断；
- 节点处理结束后，在同一方法内决定出边激活和条件分支状态；
- 当前 gateway_parallel 的多条出边进入同一 ready 队列，逻辑上并行、墙钟时间上串行。

原改造方案记录的基准流程中，两条分支开始时间相差约 14 秒，未形成实际重叠。该数据作为性能动机，不作为本设计的性能验收保证。

### 1.2 当前图语义不能在重构中被悄悄改变

当前 GraphRunner 注释及执行逻辑区分：

- 并行分叉：激活全部出边；
- 并行汇合：等待所有有效入边到达；
- XOR 分叉：只激活选中的一条出边；
- 条件/包容分叉：激活所有满足条件的出边，并通过 ConditionalBranchTracker 识别被跳过的分支；
- 普通汇合：当前按 OR 语义处理，任一有效入边到达即可执行，另有 done 去重；
- 干跑：条件值未知时按可达性预检处理，不等同于真实运行的条件选择。

**重构必须逐项保留这些既有语义，不能把所有汇合统一改成 AND JOIN，也不能把所有汇合统一改成 OR JOIN。**

### 1.3 当前代码中的关键风险点

以下是基线源码可见的风险，不代表所有问题都已完成全面审计：

| 位置/对象 | 基线事实 | 重构要求 |
|---|---|---|
| GraphRunner.run() | 图调度与节点执行混在一个长方法 | 拆开控制流与执行副作用 |
| RunProgress.nodes | LinkedHashMap，node() 使用 computeIfAbsent | 并发写入和快照语义必须明确 |
| RunProgress.aborted/materialHold | 普通 boolean | 明确原子性和状态优先级 |
| RunStateMachine.seq | 实例级非原子 long | 不再让多个 worker 竞争修改 |
| RunStateMachine.loopIndexSupplier | 实例字段保存当前 loop index supplier | 改为每次节点执行的显式上下文 |
| JdbcEventStore.pending | ArrayList 缓冲 | 单消费者或完整串行化 |
| ExecutionListener | 接口本身不承诺线程安全 | 统一事件分发边界 |
| WorkflowService step 回调 | 包含设备锁、DB 写入、步骤镜像、物料/板位副作用 | 必须逐项确认并发键、事务和线程归属 |
| RunMonitor | 单节点视角不能直接代表多个活跃节点 | 改为 Run 汇总 + 活跃节点集合 |

此前 6 个 GraphRunner 字段容器的线程安全化，仅解决特定容器的并发访问问题；不等于图调度器、状态机、listener、台账和外部副作用已经具备并发安全性。

---

## 2. 目标架构与职责边界

### 2.1 WorkflowCompiler：定义编译与静态校验

输入：解析后的流程定义/Graph。

输出：不可变 GraphPlan。

职责：

- 节点、边引用完整性检查；
- START/END 与不可达节点检查；
- 网关类型与出入边规则检查；
- SPLIT/JOIN 配对及结构化并行检查；
- 循环回边与循环区域识别；
- 并行分支共享节点归属检查；
- 可声明变量读写集合的冲突检查；
- 节点类型与 NodeHandler 能力检查；
- 生成稳定的节点/边索引，避免运行时反复解析。

不负责：

- 运行时选择条件分支；
- 设备资源分配；
- commandId 生成；
- 执行节点；
- 修改 Run 状态。

**实施策略：** 第一阶段不推翻现有 WorkflowGraphParser。先在现有 Graph 之上增加编译/校验层，形成 GraphPlan；只有当解析模型本身确实妨碍表达时，才单独评估 parser 重构。

### 2.2 GraphPlan：不可变图结构

建议概念结构：

    record GraphPlan(
        Map<String, NodeDefinition> nodes,
        Map<String, List<GraphEdge>> outgoing,
        Map<String, List<GraphEdge>> incoming,
        Map<String, GatewayPlan> gateways,
        Map<String, LoopPlan> loops,
        List<String> entryNodeKeys,
        String definitionVersion
    ) {}

要求：

- 构造后不可变；
- 不保存 run 状态、Token、设备状态或变量值；
- 同一流程版本可供多个 Run 只读共享；
- 所有集合防御性复制；
- 不允许把可变 GraphRunner 字段塞进 GraphPlan。

### 2.3 RunCoordinator：单 Run 的唯一图协调者

职责：

- 接收启动、节点完成、设备事件、暂停、停止、恢复等 RuntimeMessage；
- 管理 ready Token；
- 决定何时分派节点；
- 接收 NodeCompletion；
- 依据结果激活后继边；
- 管理 SPLIT/JOIN 激活；
- 管理循环激活和最大步数；
- 维护 Run 级图完成条件；
- 决定是否还能派发新节点。

**唯一写入约束：** 以下图调度状态只能由 Coordinator 所属的串行执行上下文修改：

- ready；
- 节点/边激活状态；
- pending / join arrivals；
- ConditionalBranchTracker 等条件分支存活信息；
- done/完成激活记录；
- 循环激活计数；
- split/join activation 状态；
- Run 图级终止判定。

Worker 不得直接调用图推进方法，不得直接写 ready/pending/done/visits，不得选择下一个节点。

Coordinator 可以由每 Run 的单线程 mailbox/serial executor 驱动。它不能占用 Node WorkerPool 线程等待节点完成；worker 完成后投递消息即可。

### 2.4 NodeDispatcher：分派策略与容量控制

职责：

- 从 Coordinator 提交的可运行节点请求中选择任务；
- 检查 Run 级 in-flight 上限；
- 检查应用级 worker 容量；
- 提交到有界 WorkerPool；
- 处理拒绝提交/backpressure；
- 维护已提交任务的运行计数；
- 不做图推进、不做 JOIN。

初始建议：

- 应用级共享有界线程池；
- 每 Run 设置最大 in-flight 节点数；
- 每设备互斥继续由现有 DeviceLock/资源租约实现；
- 不为每个 Run 创建独立线程池；
- 不使用 ForkJoinPool.commonPool()；
- 不在 worker 中递归提交子任务后 join 等待。

具体线程数、队列容量和每 Run 并发额度必须通过设备执行时长、DB 连接池容量和实际并发 Run 数测量后配置，不在设计阶段拍脑袋固定为 4/16。

### 2.5 NodeExecutor 与 NodeHandlerRegistry

NodeExecutor 是一次节点执行的统一入口；NodeHandler 负责某类节点的具体行为。

建议接口：

    interface NodeHandler {
        NodeType type();

        NodeExecutionResult execute(
            NodeDefinition node,
            NodeExecutionContext context
        );
    }

    interface NodeExecutor {
        NodeCompletion execute(NodeExecutionRequest request);
    }

Handler 按节点类型注册，例如：

- TaskNodeHandler；
- BuiltinNodeHandler；
- WaitNodeHandler；
- GatewayNodeHandler（仅处理需要执行语义的控制节点；纯图路由优先由 Coordinator 处理）；
- ManualNodeHandler；
- 后续扩展的计算/脚本节点。

不要把原 if/else 链简单搬进 Registry 后仍由一个 Handler 承担所有分支。Handler 应按可独立测试的业务行为拆分。

**重要：** Handler 不是 Spring Controller，也不应直接持有某个 Run 的可变状态。每次调用的状态必须经 NodeExecutionContext 显式传入。

### 2.6 RunExecutionContext：Run 级共享上下文

建议聚合：

- RunIdentity（tenantId、runId、traceId 等）；
- FlowVariableStore；
- RunProgress；
- RunControlState；
- RunEventSequence；
- Run-level policy/config snapshot；
- 运行时持久化/审计接口。

不应聚合：

- 当前线程；
- 当前节点的 loopIndex 可变字段；
- 当前设备的 ThreadLocal 锁持有状态；
- 可变的 GraphPlan；
- 每个 worker 独占的临时参数。

RunExecutionContext 是显式依赖容器，不是另一个万能对象。若某项服务只属于 NodeExecutionContext，就不要为了方便放进 RunExecutionContext。

---

## 3. 图执行模型：Token、激活与 JOIN

### 3.1 为什么需要 ExecutionToken

只用 nodeKey 表示执行位置，在循环、并行、重试和恢复叠加后不够表达一次具体激活。

建议引入运行时 Token：

    record ExecutionToken(
        String tokenId,
        String runId,
        String currentNodeKey,
        String incomingEdgeId,
        String parentTokenId,
        String splitActivationId,
        LoopContext loopContext,
        long activationOrdinal
    ) {}

Token 是控制流状态，不是设备命令身份。**Token ID 不得代替 commandId，不得用于设备事件归属。**

### 3.2 节点执行与节点激活必须区分

- NodeDefinition：流程定义中的静态节点。
- NodeActivation：某个 Run 中该节点的一次执行激活。
- NodeAttempt：该激活内部由恢复策略驱动的一次执行尝试。
- commandId：一次逻辑设备操作身份，沿用既有命令身份规范。

建议 NodeActivation 至少包含：

    record NodeActivation(
        String activationId,
        String nodeKey,
        String tokenId,
        String splitActivationId,
        LoopContext loopContext,
        int activationNumber
    ) {}

这些身份不可混用。

数据库现有 run_node 汇总行与循环明细行的唯一键语义必须保持兼容。引入 NodeActivation 不代表立即把 run_node 改成每个 Token 一行；任何 schema 变更需单独评估与迁移。

### 3.3 SPLIT：创建 SplitActivation

每次实际经过并行分叉时创建一个 SplitActivation：

    record SplitActivation(
        String activationId,
        String splitNodeKey,
        String joinNodeKey,
        String parentActivationId,
        Set<String> expectedBranchIds,
        Set<String> arrivedBranchIds,
        SplitStatus status
    ) {}

核心语义：

- 同一个静态 SPLIT 在循环中每次经过，必须创建不同 activationId；
- 嵌套 SPLIT 通过 parentActivationId 建立层级；
- expectedBranchIds 是本次实际激活的分支集合，不是简单复制 JOIN 所有静态入边；
- 重复到达同一 branchId 必须幂等，不能让 JOIN 计数重复增加；
- 失败/停止/暂停不会伪造分支到达；
- SplitActivation 的完成只能由 Coordinator 判定。

### 3.4 JOIN：按网关语义判定，不做 done/visits 合并

JOIN 的判定必须结合网关类型：

| 网关类型/场景 | 到达条件 |
|---|---|
| 并行 JOIN | 本次 SplitActivation 中所有 expected branches 到达 |
| 条件/包容 JOIN | 所有实际激活且仍存活的分支到达；被明确跳过的分支不等待 |
| XOR 后普通节点 | 按现有选中路径激活 |
| 普通汇合节点 | 保持现有 OR 语义，除非流程定义显式声明其他语义 |

并行 JOIN 完成后，Coordinator 生成下游 Token，关闭该 SplitActivation。

**禁止采用“每个分支独立 done/visits，JOIN 后 done 并集、visits 取 max”的通用方案。** 该方案无法普遍表达同一静态节点的多次激活、循环圈次、嵌套并行和共享汇合节点。

### 3.5 同一节点出现在两个分支中的规则

本版采用保守、可验证的结构化并行约束：

1. 两个并行分支在对应 JOIN 之前，不允许共同拥有同一个可执行静态节点；
2. 分支汇合后的共享下游节点归 JOIN 后的主 Token 执行，只执行一次；
3. 如果两个分支都指向同一个普通节点，编译器必须判定它是合法的汇合点还是不受支持的交叉共享；不能由运行时靠 done 去重猜测；
4. v1.0 第一阶段对无法证明安全的分支交叉图编译期拒绝；
5. 以后若业务确实需要同一个静态节点由不同分支分别执行，必须以不同 NodeActivation 表达，并单独解决现有 run_node 唯一键、变量写入、设备命令和恢复语义，不能默认支持。

这条约束不是宣称现有所有合法流程都满足要求。实施前必须扫描现有流程定义，统计会被拒绝的图形并逐个确认。

### 3.6 循环语义

不再用一个全局 visits[nodeKey] 作为所有并行/循环场景的唯一圈次来源。

建议 LoopContext：

    record LoopContext(
        String loopActivationId,
        String loopNodeKey,
        int iteration,
        String parentLoopActivationId
    ) {}

规则：

- 循环迭代属于一次 LoopActivation；
- Token 在循环内传递 LoopContext；
- 节点的物料/命令圈次仍按现有业务口径映射，不能把无环节点的默认 visit=1 写成 loop_index=1；
- 嵌套循环需要父子 LoopContext；
- 最大循环次数和最大总激活数仍是兜底保护；
- loopIndex 必须通过 NodeExecutionContext 显式传递，不再使用共享 RunStateMachine.withLoopIndexSupplier() 这种实例级可变 supplier。

v1.0 先定义数据模型和兼容映射；复杂嵌套循环与并行交叉的支持范围，须由图编译测试确定，不在未验证前宣称全部支持。

---

## 4. 变量模型与并行读写规则

### 4.1 保持流程级共享语义

既定业务语义是变量流程级共享。本设计不把流程变量整体改为分支私有副本，也不通过 join 合并 Map 来模拟共享。

建议：

- FlowVariableStore：Run 级唯一变量存储；
- BranchOutput：每个分支/Token 的局部输出命名空间；
- JOIN Aggregation：在 JOIN 之后显式聚合分支输出到流程变量。

### 4.2 并行写冲突

V1 采用保守规则：

- 同一并行激活内，两个分支不得无协调地写同一个流程变量 key；
- 同一分支写入、其他分支同时读取该 key，也存在时序依赖，默认禁止；
- 不同分支读同一只读 key，允许；
- 不同分支写不同 key，允许；
- 写冲突通过编译期声明检查 + 运行时写入保护共同实现。

仅靠静态扫描表达式文本不一定能识别动态变量名、脚本隐藏读写或设备输出副作用。每种 NodeHandler 必须提供变量读写声明；无法声明的旧节点在并行组内按保守策略处理（拒绝并行或要求人工明确配置），不能假设“没扫描到就没有写入”。

### 4.3 两分支累加

不在 V1 提供共享变量原子自增 API。

推荐：

    Branch A -> BranchOutput[A].amount = 3
    Branch B -> BranchOutput[B].amount = 5

    JOIN
      -> total = BranchOutput[A].amount + BranchOutput[B].amount

这样重试单一分支不会重复累加，也能审计每个分支的贡献。

如果未来确实需要原子计数器，应作为明确的业务操作设计，必须定义：
- 哪些激活可写；
- 写入幂等键；
- 重试去重；
- 中间读取可见性；
- 崩溃恢复后的 exactly-once/at-least-once 语义。

不能把 ConcurrentHashMap.compute 当作完整业务原子性方案。

---

## 5. 节点执行模型

### 5.1 NodeExecutionRequest

每次执行都传入独立上下文，不通过共享实例字段传递当前节点信息。

    record NodeExecutionRequest(
        RunIdentity run,
        NodeActivation activation,
        NodeDefinition node,
        NodeExecutionContext context
    ) {}

    record NodeExecutionContext(
        String activationId,
        String tokenId,
        String branchId,
        LoopContext loopContext,
        int attempt,
        FlowVariableStore variables,
        RunControlView control
    ) {}

实际类型应避免把可变 RunExecutionContext 全量暴露给 Handler；Handler 只拿完成工作所需的最小接口。

### 5.2 NodeExecutionResult

统一结果类型：

    sealed interface NodeExecutionResult {
        record Completed(NodeOutput output) implements NodeExecutionResult {}
        record Suspended(WaitRegistration wait) implements NodeExecutionResult {}
        record Failed(NodeFailure failure) implements NodeExecutionResult {}
        record Paused(PauseCheckpoint checkpoint) implements NodeExecutionResult {}
    }

解释：

- Completed：该节点业务动作已经完成，可以由 Coordinator 推进；
- Suspended：节点尚未完成，等待设备事件、人工确认或其他外部条件；
- Failed：节点执行失败，交由 RunLifecycleManager 按既定失败策略处理；
- Paused：节点在安全边界停下，尚未完成，恢复后从定义好的检查点继续。

**设备命令已派发、收到 ACK，不等于节点 Completed。** 只有收到按 commandId 正确归属的最终完成事件，并完成必要的设备/物料状态闭环后，节点才可完成。

### 5.3 同步与异步分阶段实现

当前 StepExecutor / RunStateMachine 路径包含同步等待和恢复逻辑。不能只在外面套一层 CompletableFuture 就声称设备等待已异步化。

分阶段策略：

- V1：NodeExecutor 仍可同步调用现有 StepExecutor，重点完成职责抽取和串行等价；
- V2：在有界 WorkerPool 中并发执行独立节点；长时间阻塞任务必须有独立容量预算，不能耗尽协调线程；
- V3：将设备等待改为真正的 Suspended continuation：派发后登记等待，释放 worker；最终事件进入 Mailbox 后恢复 Token。

在 V2 过渡期，如果设备调用仍然阻塞 worker：
- worker 数量必须有界；
- 每 Run in-flight 必须有界；
- 不得让 Coordinator/事件分发器占用同一阻塞池；
- 队列满必须 backpressure，不能无限堆积；
- 不能使用无界 cached thread pool；
- 监控线程池活跃数、队列长度、拒绝次数和节点等待时长。

---

## 6. Run 生命周期：暂停、停止、失败与恢复

### 6.1 生命周期控制由 RunLifecycleManager 统一协调

生命周期状态与图节点状态分开。建议内部控制状态：

    RUNNING
    PAUSE_REQUESTED
    PAUSE_DRAINING
    PAUSED

    STOP_REQUESTED
    STOP_DRAINING
    FAILED
    MANUAL
    QUARANTINED

这些是 Runtime 内部协调状态建议，不表示现有数据库枚举已经包含全部值。落库映射必须复用现有 Run 状态模型或经独立迁移评审。

### 6.2 暂停

语义：

1. 收到暂停请求后，Coordinator 不再分派新的节点激活；
2. 已经开始的节点运行到安全边界；
3. 已派发设备命令不能因暂停请求而被当作已停止；
4. 等待设备事件的节点保留 commandId、等待游标和恢复信息；
5. 所有在途节点都到达安全边界，且状态已持久化后，Run 才能进入 PAUSED；
6. 部分分支仍在运行时，状态保持 PAUSE_REQUESTED/PAUSE_DRAINING，不得提前宣称暂停完成。

暂停协调等待阈值只用于告警/升级处置，不是节点或分支执行超时。超过阈值不能强制释放设备租约或伪造完成。

### 6.3 一个分支失败后的停止

沿用已确定业务语义：

1. 任一分支失败，Run 进入 STOP_REQUESTED；
2. Coordinator 停止分派所有新节点；
3. 已经派发的节点继续等待真实设备结果/安全收尾；
4. 所有在途节点收敛后进入最终失败/人工状态；
5. 无法确认物理状态、命令结果或设备安全边界时进入隔离/人工处置；
6. 不通过 Future.cancel、线程中断或超时标记来假装物理设备已停止。

停止协调阈值用于告警和转人工，不构成分支执行超时。业务“不设置分支超时”的决定保持不变。

### 6.4 恢复

恢复不能只把 Run 状态改回 RUNNING。

恢复所需信息至少包括：

- Run 控制状态；
- 已完成 NodeActivation；
- 在途/等待 NodeActivation；
- Token 与当前节点位置；
- SplitActivation 的 expected/arrived 状态；
- LoopContext；
- FlowVariableStore 的持久化版本；
- commandId 与等待事件游标；
- 设备租约/物理状态核验结果；
- 暂停/失败收尾检查点。

**V1/V2 不自动宣称支持崩溃后完整恢复新 Token 模型。** 在 Token/SplitActivation 的持久化、原子提交和恢复算法通过独立设计及测试前，继续沿用已验证的旧恢复路径，并限制启用新模型的场景。V3 才把可恢复 Token Runtime 作为正式交付目标。

---

## 7. 事件、序号与持久化边界

### 7.1 RunStateMachine 的职责调整

当前 RunStateMachine 将恢复决策、节点尝试、事件产生和 loopIndex supplier 等状态放在同一实例中。并发改造后不得让多个 worker 通过共享可变字段传递当前节点上下文。

目标：

- 每次节点激活/尝试拥有独立 NodeExecutionContext；
- 恢复决策器尽量作为无状态/线程安全依赖；
- 事件序号由 Run 级 EventSequence 统一分配；
- listener 不在多个 worker 中被无保护地直接调用；
- 节点完成由 NodeCompletion 回传 Coordinator。

是否保留 RunStateMachine 类名，属于实现重构选择；不要求为了名字保留错误的实例级共享状态。

### 7.2 序号语义

定义：

- seq：Run 范围内分配的唯一递增事件序号；
- 不承诺数据库中所有事件行永远无缺口（持久化失败、进程崩溃等都可能造成缺口）；
- 如果业务要求按 seq 顺序观察事件，必须通过有序分发保证；
- AtomicLong 只能解决并发分配竞争，不能单独保证 listener 调用顺序和 DB 落库顺序。

推荐：

    Worker
      -> immutable ExecutionEvent (without final seq)
      -> Run EventMailbox
      -> single EventDispatcher
           -> allocate seq
           -> ordered listener delivery
           -> JdbcEventStore / RunMonitor / audit sinks

若事件必须在创建时立即获得 seq，则由线程安全 sequencer 分配，并将事件放入按 seq 排序的串行队列；不能让 worker 各自直接调用 listener。

### 7.3 JdbcEventStore

JdbcEventStore.pending、written、failed 等缓冲状态必须由单一消费者拥有，或由同一把锁完整保护 add/flush/close/count 的全部复合操作。

推荐单消费者 EventDispatcher：

- worker 不直接写 pending；
- EventDispatcher 单线程调用 listener；
- flush/close 只由 EventDispatcher 生命周期管理；
- 队列容量有界；
- 队列满时的策略必须明确（阻塞生产者、持久化降级或 Run 安全失败），不能静默丢弃执行关键事件；
- 关闭时先停止接收，再 drain，最后 flush；
- 写入失败计数和告警必须保留。

当前事件 store 对持久化异常的容错策略，需要与“执行事实不可丢失”的要求重新分级：展示/诊断事件可以容忍有限降级，命令身份、物理动作完成、物料状态和恢复检查点等关键事实不能只存在内存队列。

### 7.4 数据库事务与连接

Spring 的线程绑定事务上下文不跨线程自动传播。worker 的 DB 操作应使用自己明确的事务边界和连接获取路径。

规则：

- 不把主线程事务对象/Connection 传给 worker；
- 不假设多个 worker 的多条 SQL 自动组成一个 Run 原子事务；
- 节点业务事务、设备命令账本、事件记录、物料副作用分别定义一致性边界；
- 一个 worker 的事务提交不代表另一个 worker 的事务提交；
- 对需要“先占用、后派发”的设备资源，继续遵守既有锁顺序和设备租约规则；
- listener 不应在持有设备锁的长事务中执行无关的慢操作；
- 所有新增并发写入都要审查唯一键冲突、更新覆盖和幂等键。

在具体 mapper/Tx 调用链完成源码审计之前，不得写“DB 写入天然线程安全”作为放行理由。

---

## 8. 设备执行与外部副作用

### 8.1 保留既有设备互斥

并发 worker 不等于设备可并发。

- 同一设备的互斥仍由 DeviceLock/设备租约实现；
- 同一 Run 的两个分支也不能绕过设备锁；
- 设备锁获取与释放必须遵循当前持有者/线程上下文约束；
- 不能把 ThreadLocal 锁持有状态跨线程传递；
- 若节点执行未来拆成派发线程和事件回调线程，锁生命周期必须改成显式 lease/owner 标识，而不是依赖线程持有。

### 8.2 设备事件归属

- 事件归属只认 commandId；
- 不回显 commandId 的设备不能因为当前设备只有一个“看起来正在运行”的命令，就把迟到事件归给当前命令；
- 无法归属的事件进入 UNATTRIBUTED/隔离处理；
- worker、Token、branchId、nodeKey、runId 均不能替代 commandId 作为事件归属依据。

### 8.3 节点完成的定义

设备节点完成至少要经过：

1. 资源准入成功；
2. 派发前建立所需设备占用/租约；
3. 生成/复用正确的 commandId；
4. 设备命令派发；
5. 收到按 commandId 归属的最终事件；
6. 完成设备状态、物料状态及必要台账闭环；
7. NodeExecutor 返回 Completed；
8. Coordinator 激活下游。

ACK 不是物理完成。设备锁释放、租约释放和节点完成必须按既有设备生命周期规范执行，不能由线程池任务结束事件代替。

### 8.4 WorkflowService 回调拆分

不要把整个 WorkflowService 注入每个 Handler 作为万能依赖。

按副作用拆成窄接口，例如：

- NodeStateRepository；
- StepExecutionRecorder；
- DeviceLeaseService；
- CommandDispatchService；
- MaterialEffectService；
- EventSink；
- RunProgressPublisher。

这些接口是否都要成为独立类，按实际依赖决定；关键是明确线程安全、事务、幂等和失败策略。

---

## 9. 监控与进度模型

当前单个 nodeKey/nodeStatus 不能表达多个并发节点。

目标分为两层：

### 9.1 Run 汇总视图

- runControlState；
- totalActivations；
- completedActivations；
- activeActivations；
- failedActivations；
- suspendedActivations；
- queuedActivations；
- 当前停止/暂停原因；
- 最后事件时间。

### 9.2 活跃节点视图

每个激活至少包含：

- activationId；
- nodeKey / nodeName；
- branchId / splitActivationId；
- node status；
- resourceId；
- startedAt；
- waitingReason；
- commandId（仅按权限用于诊断展示，不用于替代事件关联）；
- attempt / loopContext。

RunProgress 的快照必须定义一致性：只能在 Coordinator 消息处理边界生成，或通过不可变快照导出；不能把正在变化的底层 Map.values() 直接暴露给调用者。

---

## 10. 线程池、背压与公平性

### 10.1 线程池结构

建议一个应用级共享有界 WorkerPool，而不是每 Run 一个池。

每 Run 由 NodeDispatcher 维护 in-flight 上限；应用级池控制总线程数和总队列容量。

    Application
      Bounded WorkerPool (global cap)
           ^
           |
      NodeDispatcher
           |
           +-- Run A: maxInFlight = a
           +-- Run B: maxInFlight = b
           +-- Run C: maxInFlight = c

### 10.2 必须有背压

- worker 池队列有界；
- 单 Run in-flight 有界；
- 提交被拒绝时，Token 留在 ready，不丢失；
- Coordinator 不得因提交拒绝而标节点失败；
- 调度器稍后重试提交；
- Run 间应避免单个大 Run 长期占满队列；
- 需要记录排队时间与实际执行时间，区分设备慢和调度拥塞。

### 10.3 嵌套并行

不允许外层 worker 执行分支时，再提交内层分支并阻塞 allOf().join()。

所有层级的并行都由同一个 RunCoordinator 展开为 ready Token，再由统一 NodeDispatcher 分派。这样避免 worker 被外层任务占满、内层任务排队导致线程池饥饿。

### 10.4 线程数配置

初始参数通过压测和资源预算确定：

- worker 数量；
- queue capacity；
- 每 Run maxInFlight；
- DB pool 与 worker 的比例；
- 单设备并发约束；
- 事件 mailbox 容量。

不得把“最大分支数”直接等同于线程池大小。多个 Run 会共享同一应用池；节点可能阻塞等待设备；DB 连接池也有独立上限。

---

## 11. GraphRunner 千行逻辑的拆解方法

本设计不接受“把 1000 行搬到一个 NodeExecutor”或“按每 100 行切一个类”。拆解依据是**控制流、节点行为、外部副作用、生命周期**四种责任。

### 11.1 目标模块

| 模块 | 接收现有逻辑 | 不接收 |
|---|---|---|
| GraphCoordinator | ready/pending、边激活、循环推进、网关路由、完成归并 | 设备 IO、SQL、sleep |
| NodeExecutor | 一次节点激活的统一执行入口、异常归一化 | 下游图推进 |
| BuiltinNodeHandler | 变量/表达式/内置等待等具体语义 | 图调度状态 |
| TaskNodeHandler | 任务节点执行编排 | ready/pending/JOIN |
| NodePreconditions | trim/skip/dry-run/material gate/pause boundary 的显式前置决策 | 节点设备动作本身 |
| DeviceExecutionService | 设备资源选择、锁、命令派发、步骤回调 | 图推进 |
| NodeResultRecorder | node 状态与循环明细落库 | 执行决策 |
| RunLifecycleManager | 暂停/停止/人工/隔离协调 | 设备命令事件归属 |
| EventDispatcher | 事件序号、监听器顺序、批量写入 | 节点重试决策 |
| FlowVariableStore | 变量读取写入、版本/冲突保护 | 条件网关调度 |

### 11.2 现有逻辑迁移矩阵

| 现有逻辑 | 目标位置 | 迁移注意事项 |
|---|---|---|
| 初始化 pending/ready/done/visits | GraphCoordinator 初始化 | 先保持旧算法与顺序 |
| START/入度为零入口选择 | WorkflowCompiler/Coordinator | 保持排序与兜底行为，明确空图/全环行为 |
| trimKeys 阶段裁剪 | NodePreconditions | 保留 BYPASSED 语义和下游推进 |
| skipKeys 断点续跑 | NodePreconditions + RecoveryCheckpoint | 不改变已完成节点跳过规则 |
| dryRun | ExecutionModePolicy | 不执行设备、不误判条件分支不可达 |
| BUILTIN 执行 | BuiltinNodeHandler | 保留等待中暂停、失败转人工和变量输出语义 |
| TASK 物料闸门 | NodePreconditions / MaterialGate | 闸门前不派发设备命令；被阻塞节点保留待执行位置 |
| pauseChecker | RunLifecycleManager + NodePreconditions | 只在安全动作边界生效 |
| toRecoverable / executeNode | TaskNodeHandler + NodeExecutionEngine | 不改变重试、换机、人工策略 |
| withLoopIndexSupplier | NodeExecutionContext.loopContext | 删除实例级当前圈次 supplier |
| nodeStateRecorder | NodeStateRepository | 明确实时态与最终态的写入顺序 |
| roundRecorder | NodeResultRecorder | 保留 loop_index=0 汇总行及循环明细兼容口径 |
| outputReader.outputsOf | NodeOutput + FlowVariableStore | 输出提交必须幂等并检查并行写冲突 |
| MANUAL/ABORTED/SKIPPED 处理 | NodeCompletionPolicy + Lifecycle | 保留现有终止语义，SKIPPED 不得误当成功 |
| SPLIT_XOR choose | GatewayRouter | 保留条件/default/稳定排序规则 |
| SPLIT_CONDITIONAL chooseAll | GatewayRouter + ConditionalBranchTracker | 保留跳过分支存活性语义 |
| activate/recheckConditionalJoins | GraphCoordinator/TokenEngine | 由单写者统一调用 |
| maxVisits/maxSteps/hasCycle/cyclicNodes | WorkflowCompiler + Coordinator | 先保持旧限制，再单独评估优化 |
| variablesRefresher | VariableRefreshService | 定义刷新时点和并发版本策略 |
| builtinListener / log | NodeEventSink / logger | listener 不得被 worker 无保护并发调用 |

### 11.3 NodeExecutor 不得返回含糊的 STOP

原主循环中的 break/continue 不能机械改成一个 STOP。必须区分：

- NodeCompleted：节点完成，允许推进；
- NodeBypassed：预期跳过，按旧语义推进；
- NodePaused：当前节点未完成，保留为恢复入口；
- RunStopRequested：整个 Run 停止派发；
- NodeFailedManual：节点转人工，整 Run 按策略停止；
- NodeSuspended：等待外部事件，不推进；
- NodeRetryScheduled：当前 attempt 结束，但同一逻辑节点后续还需重试。

否则抽取后虽然代码短了，控制语义却会被压扁。

---

## 12. 分阶段迁移计划

### Phase 0：源码审计与基线固化

交付：

- GraphRunner 方法/代码块逐段职责映射；
- GraphRunner 所有字段及写入者清单；
- RunStateMachine 全部可变字段和调用点清单；
- WorkflowService 回调完整调用链、DB 事务和锁持有分析；
- 所有 listener、monitor、progress、collector 并发安全审计；
- 现有流程定义的 SPLIT/JOIN/循环/共享节点扫描报告；
- 关键基准 trace 与 DB 台账快照。

通过标准：未核实项清零或明确列为阻断项；不以“接口看起来无状态”替代源码证据。

### Phase 1：串行等价重构

工作：

1. 引入 NodeExecutionContext；
2. 将 loopIndexSupplier 改为调用参数；
3. 抽出 NodeExecutor/NodeHandler；
4. 抽出 NodePreconditions；
5. 把图推进逻辑抽成 RunCoordinator，但仍在同一线程执行；
6. 保持原有顺序、网关、暂停、物料闸门、重试和恢复行为；
7. 对旧/新实现生成标准化 execution trace 并对比。

通过标准：

- 单分支流程行为等价；
- XOR/条件/并行网关逻辑行为等价；
- 循环圈次与 run_node/run_step 台账等价；
- 暂停、续跑、物料闸门和人工处理等价；
- 没有并发线程池参与节点执行。

### Phase 2：单写者协调器 + 有界并发节点

工作：

1. 引入 RuntimeMailbox/Coordinator serial executor；
2. 引入 NodeDispatcher 与全局有界 WorkerPool；
3. worker 只执行 NodeActivation，返回 NodeCompletion；
4. Coordinator 单线程处理完成消息、更新图状态和激活下游；
5. 加入 Run in-flight 上限和 backpressure；
6. 同设备仍经 DeviceLock/租约串行；
7. 引入 EventDispatcher 串行事件投递；
8. 先支持结构化 SPLIT/JOIN 和无歧义的图形。

通过标准：

- 两个独立分支真实时间重叠；
- JOIN 不提前放行；
- worker 不直接改图状态；
- 无线程池嵌套等待死锁；
- 事件/节点/步骤台账无丢失或重复；
- 失败/暂停/停止遵守既定语义。

### Phase 3：异步等待与持久化 Runtime

工作：

1. 设备节点支持派发后返回 Suspended；
2. commandId 等待登记与事件游标持久化；
3. 事件到达后通过 Mailbox 恢复对应 activation；
4. Token、SplitActivation、LoopContext 持久化；
5. 崩溃后恢复算法与幂等提交；
6. 关键 Runtime 状态与事件/命令账本的一致性事务；
7. QUARANTINED/UNKNOWN 物理安全检查。

通过标准：进程崩溃、ACK 丢失、迟到事件、重复事件、恢复重入等故障注入测试通过。未通过前，不宣称“可恢复并发运行时”完成。

---

## 13. 验收与测试策略

### 13.1 串行等价测试

对同一流程定义、输入、模拟设备响应，比较旧实现和 Phase 1 新实现的标准化 trace：

- 节点激活顺序；
- 节点执行次数；
- 网关选择；
- BYPASSED/SKIPPED/MANUAL/ABORTED 语义；
- loop_index；
- 变量最终值；
- 设备选择；
- commandId 生成/复用；
- run_node/run_step/run_event 记录；
- 暂停位置与恢复位置。

时间戳、线程名等非语义字段可归一化；业务状态与身份字段不得忽略。

### 13.2 并发正确性测试

使用可控 Fake NodeHandler / latch，不依赖真实设备耗时：

- 两个分支同时进入 handler；
- 一个分支完成时 JOIN 不提前释放；
- 两个分支完成后 JOIN 恰好释放一次；
- 同一 branch 重复 completion 不重复推进；
- 同一设备两个节点不能同时获得设备执行权；
- 不同设备节点可重叠；
- 线程池队列满时 Token 不丢失；
- Run in-flight 上限生效；
- 多 Run 不出现单个 Run 饿死其他 Run；
- 内层并行不递归阻塞 worker。

### 13.3 图形结构测试

至少覆盖：

1. SPLIT_PARALLEL → 两条独立分支 → JOIN_PARALLEL；
2. SPLIT_XOR 两选一；
3. SPLIT_CONDITIONAL 多分支与跳过分支；
4. 条件 JOIN 的分支存活性；
5. JOIN 后共享下游节点只执行一次；
6. 两分支在 JOIN 前共享同一可执行节点（按本版规则编译拒绝）；
7. 嵌套 SPLIT/JOIN；
8. 循环位于单分支内；
9. 并行分支内循环；
10. 嵌套循环与并行组合（未支持形状必须明确拒绝）；
11. 只有入度为零节点、无 START、空图、全环等历史边界；
12. 超过 maxVisits/maxSteps 的保护行为。

### 13.4 生命周期与设备安全测试

- 分支 A 失败后不再分派新节点；
- 已派发节点仍等待真实完成；
- 停止等待超告警阈值不释放设备租约；
- 暂停请求后不再启动新节点；
- 所有在途节点到安全边界前不能标 PAUSED；
- __sleep / __wait 暂停后不误记 completed；
- __awaitEvent 暂停/恢复不丢游标；
- 设备 ACK 不被当成最终完成；
- commandId 不匹配/缺失的事件不推进节点；
- QUARANTINED 未通过物理检查不能恢复；
- 迟到事件不能清除新租约或推进新命令。

### 13.5 性能验收

基准流程至少重复多次，记录：

- 两个分支的 actual_start 差值；
- 实际重叠时长；
- Run 总耗时；
- worker 排队时间；
- 设备执行时间；
- DB 连接池等待时间；
- 事件队列长度；
- 线程池拒绝次数。

不能只用单次总耗时判断。性能收益必须与台账完整性、设备互斥和错误率一起验收。

---

## 14. 明确不做与禁止事项

本版明确不做：

- 多实例 RunCoordinator；
- 分布式 worker/fencing；
- 每分支一个 GraphRunner；
- 每个 Run 一个独立线程池；
- ForkJoinPool.commonPool；
- worker 内递归提交并阻塞 join；
- done/visits 分支复制后并集/max 合并；
- 无协调的共享变量原子累加；
- 用 Future.cancel 代表设备停止；
- 用 ACK 代表设备物理动作完成；
- 用 nodeKey/runId/branchId 替代 commandId 归属事件；
- 在没有持久化和故障注入证明前宣称 Token Runtime 可崩溃恢复；
- 为了代码整洁而一次性引入大量无实际职责的抽象类。

---

## 15. 待决事项与实施闸门

下列事项必须在相应阶段前关闭：

| ID | 待决事项 | 关闭阶段 | 关闭证据 |
|---|---|---|---|
| WRT-01 | 现有流程定义中共享分支节点/非结构化并行图的数量 | Phase 0 | 全量扫描报告与业务确认 |
| WRT-02 | 当前所有 NodeHandler/设备方法的变量读写声明能力 | Phase 0/1 | 读写集合或保守拒绝策略 |
| WRT-03 | WorkflowService 完整回调链线程安全、DB 事务和锁边界 | Phase 0 | 源码调用链审计与并发测试 |
| WRT-04 | EventDispatcher 队列满、落库失败、关闭 drain 的策略 | Phase 1/2 | 故障注入测试 |
| WRT-05 | WorkerPool、Run in-flight、DB pool 的容量参数 | Phase 2 | 压测数据 |
| WRT-06 | V2 过渡期阻塞式设备等待的容量隔离 | Phase 2 | 长等待压力测试 |
| WRT-07 | Token/SplitActivation 持久化 schema 与原子恢复算法 | Phase 3 | 独立数据模型设计与崩溃恢复测试 |
| WRT-08 | 复杂循环与并行交叉图的支持矩阵 | Phase 0/2 | 编译器测试矩阵 |

**Phase 2 开工闸门：** WRT-01、WRT-03、WRT-04、WRT-05、WRT-06 必须关闭。未关闭时只能继续串行重构，不得开启真实并发。

**Phase 3 开工闸门：** WRT-07、WRT-08 及设备事件/租约恢复检查必须完成设计评审。

---

## 16. 版本边界与完成定义

### v1.0 设计定义

本文件定义目标架构、职责边界、Token/JOIN 模型、变量规则、线程与事件边界、分阶段迁移和验收要求。

它不代表：
- 代码已按此拆分；
- 当前图模型已支持所有结构化并行；
- 所有 listener 已线程安全；
- DB 事务已完成并发验证；
- Token 已持久化；
- 真机并发已验证。

### v1.0 实施完成的最低定义

只有 Phase 0 和 Phase 1 完成，且串行等价测试通过，才可称“Workflow Runtime 串行重构完成”。

只有 Phase 2 并发测试、设备互斥、事件台账、暂停/停止测试全部通过，才可称“真并发执行完成”。

只有 Phase 3 崩溃恢复和设备物理安全测试通过，才可称“可恢复并发 Runtime 完成”。

---

## 附录 A：建议的核心接口草案

以下接口用于明确责任，不要求照抄类名或一次性全部创建。

    interface RunCoordinator {
        void start();
        void post(RuntimeMessage message);
    }

    interface NodeDispatcher {
        DispatchResult submit(NodeExecutionRequest request);
    }

    interface NodeExecutor {
        NodeCompletion execute(NodeExecutionRequest request);
    }

    interface NodeHandler {
        NodeType type();
        NodeExecutionResult execute(
            NodeDefinition node,
            NodeExecutionContext context
        );
    }

    interface FlowVariableStore {
        VariableValue read(String key);
        void write(String key, VariableValue value, WriteContext context);
        VariableSnapshot snapshot();
    }

    interface EventDispatcher {
        void publish(ExecutionEvent event);
        void drainAndClose();
    }

    interface RunLifecycleManager {
        void requestPause(String reason);
        void requestStop(String reason);
        LifecycleSnapshot snapshot();
    }

建议 RuntimeMessage 至少包含：

    sealed interface RuntimeMessage {
        record NodeCompleted(NodeCompletion completion) implements RuntimeMessage {}
        record DeviceEvent(DeviceEventEnvelope event) implements RuntimeMessage {}
        record PauseRequested(String reason) implements RuntimeMessage {}
        record StopRequested(String reason) implements RuntimeMessage {}
        record ResumeRequested(String reason) implements RuntimeMessage {}
        record DispatchRejected(String activationId) implements RuntimeMessage {}
    }

所有消息应携带可去重的身份。重复 NodeCompleted、重复设备事件和重复控制请求必须有明确幂等语义。

## 附录 B：关键术语

| 术语 | 定义 |
|---|---|
| GraphPlan | 编译后的不可变流程图 |
| RunCoordinator | 单 Run 图状态的唯一协调者 |
| ExecutionToken | 流程控制位置的一次激活 |
| NodeActivation | 静态节点在 Run 中的一次执行激活 |
| NodeAttempt | NodeActivation 内的一次尝试 |
| SplitActivation | 某次并行分叉的运行时实例 |
| LoopContext | 某次循环激活及迭代上下文 |
| NodeExecutor | 执行一次节点激活的统一入口 |
| NodeHandler | 某种节点类型的具体执行器 |
| NodeCompletion | worker 返回给 Coordinator 的不可变执行结果 |
| RunExecutionContext | Run 级共享上下文 |
| EventDispatcher | Run 级有序事件分发与持久化入口 |
| SUSPENDED | 节点等待外部条件，尚未完成 |
| QUARANTINED | 物理/执行状态不确定，禁止自动继续或释放资源 |

---

**设计原则总结：图状态单写者、节点执行并发、变量共享有规则、事件有序、设备安全优先、先串行等价再启用并发。**
