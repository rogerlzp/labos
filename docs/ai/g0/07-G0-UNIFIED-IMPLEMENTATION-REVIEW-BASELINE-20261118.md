# G0-01 / G0-07 修复实施与交叉评审统一基线

- 文档版本：2026-11-18 综合收敛稿
- 对应 PR：[#4](https://github.com/rogerlzp/labos/pull/4)
- 历史 G0 核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 本轮相关源码复核树：`cf772fcc87f65210e66b2f720c6e65c424611cdb`
- 当前文档状态：已纳入本轮新增源码事实，等待基于最新 commit 的最终回执
- 当前代码状态：G0-01、G0-07 均未实施修复
- 当前证据状态：构建、业务测试、真实设备验证均为 UNKNOWN；文档提交触发 CI gate，但脚本在进入测试前因 `LABOS_DB_PASS` 未设置而退出，不得记为 CI 通过
- 实施边界：两个独立 P0 修复单元，独立分支、独立提交、独立 PR、独立验收；不占 AI Workbench 工期

> 本文件是 **G0-01/G0-07 实施与验收的统一入口**，不是整个 AI 路线或全部前序评审意见的唯一入口。实施任务卡为 [04-G0-01](04-G0-01-WORKFLOW-RUNGATE-HOTFIX-TASK-CARD.md) 与 [05-G0-07](05-G0-07-RECIPE-COMPILER-FIX-TASK-CARD.md)，[06 独立评审记录](04-G0-REVIEW-OF-TASK-CARDS-20261118.md)保留历史意见。发现本文件与任务卡不一致时，必须先登记并修订，不得自行选择较宽松的解释。

> 评审回复必须注明实际读取的完整 commit。设计评审通过只表示可以进入对应修复实施，不表示代码已修复、测试已通过或 G0 已关闭。

## 1. 背景与统一结论

G0 历史核查与本轮交叉评审确认两类代码缺陷：

1. **G0-01：同流程并发门禁 fail-open，预检未覆盖全部入口，且准入未与状态写入原子化。** 修复范围不能只限新建流程 Run；任何使同一 tenant/workflow 出现第二个 active Run 的写入路径，包括新建、resume 再激活及普查发现的状态回退，都必须进入同一数据库级协调边界。
2. **G0-07：RecipeCompiler 批量预览与执行前展开丢失设备能力映射。** 两处必须同步修复，并以完整 `RecipeCompiler.NodePlan` 做同快照一致性验证，不能只比较外围执行节点的能力字段。

统一实施顺序为：先完成 G0-01 的数据库级原子准入与并发证据，再实施 G0-07；两者仍分别提交和验收。G0-07 不依赖 G0-01 的代码实现细节，但在 G0-01 实际验收前不得描述为“已经在安全门禁保护下”。

本文件不改变历史 G0 报告的历史结论，也不扩大为执行系统重写。三项跨文档路线冲突仍未裁决，见 §5.2；它们不阻塞两个独立代码热修，但不得被本文件隐式关闭。

## 2. 已静态核实的源码事实

以下均为现状事实，不是目标设计：

| 位置 | 当前事实 | 修复含义 |
|---|---|---|
| `WorkflowRunGate.check(DbConfig,long,long,Long)` | 参数非法、active 状态词表为空、`SQLException` 三处返回 allow；并发 allow 开关位于这些判定之前 | 未知态必须 fail-closed；allow 只能覆盖 active 冲突 |
| `WorkflowWebService.run(long,String)` | 同步 Web 在调用执行前消费 `Decision.allowed()` | 可保留提前反馈，但不是最终原子边界 |
| `WorkflowController.run` 的异步分支 | 使用查询字符串包含匹配 `async=1` 后直接调用 `WorkflowService.startAsync`，不经过同步 Web 门禁 | 异步入口必须纳入统一准入，并按参数名和值精确解析 |
| `WorkflowService.startAsync(...)` | 先 `createRun`，再提交后台 worker；外层只捕获 `IllegalStateException` | 准入败者不得留下 Run、worker 或设备命令；其他 `RuntimeException` 也必须有收尾契约 |
| `WorkflowService.submitRunWorker(...)` | `RUN_POOL.submit` 抛出 `RuntimeException` 时仅释放内存 worker slot，随后原样重抛 | 线程池拒绝等异常可绕过 `startAsync` 的 `IllegalStateException` 收尾，留下 running Run |
| `ExperimentScheduler → ExperimentExecutor` | 调度器先创建 experiment_run；`existingRunId>0` 时跳过实验级门禁预检 | experiment_run ID 不等于 workflow 续跑 ID，首次派发不能绕过 |
| `ExperimentExecutor.runStep` | 每一步调用 `workflows.execute(workflowId)`，最终创建流程 Run | 真正流程 Run 创建点必须再次准入 |
| `RunActionController → RunService → PauseCoordinator` | `POST /api/runs/{id}/resume` 进入 `resumeWorkflowRun`，提交后调用 `workflows.resumeAsync` | resume 是真实生产再激活路径，不能只测 `excludeRunId` 接口契约 |
| `PauseManager.resumeTx → PauseMapper.resumeToQueued` | paused 或可崩溃恢复的 failed Run 可直接更新为 queued；当前链路未调用 WorkflowRunGate，UPDATE 未带原状态条件 | failed/recoverable Run 可与另一 active Run 形成双 active；状态 UPDATE 必须纳入同一原子准入并校验状态守卫 |
| `StartupRecovery.settle` 与 `StatusVocabulary` | 启动收尾可将中断 Run 写为 failed/recoverable；run 词表中 failed 为非 active 终态，queued/running/paused/quarantined 为 active | 先新建 B、再 resume 旧 A 是可达竞争顺序，不是假设性接口调用 |
| `WorkflowService.createRun → RunMapper.insertRun` | 本轮复核到的流程 Run 共同最终插入路径 | 原子安全边界应落在该路径；实施前仍须重新全仓普查 |
| `RecipeCompiler.compileAll(...,capabilityByResource)` | 循环内仍调用两参数 `compile(n,protocolCap)`，后者使用空映射 | 必须改为三参数透传 |
| `WorkflowService` 执行前展开 | 已持有 `resourceCap`，仍调用两参数 `compiler.compile(n,protocolCap)` | 必须与预览一起修复 |
| `RecipeCompiler.compile` | `resolveCapability` 在具体 protocolName 的单步返回之前执行 | 具体 protocolName 用例也能判别 capability/source 丢参，G07-T08 应要求旧实现失败 |
| `RealWorkflowRunner.resolveCapability` | 逻辑／伪资源 → explicit → bound → protocol → protocol-short → heuristic／unknown | 不得借修复重排既有优先级 |

本轮没有修改上述业务源码，没有运行构建、业务测试或真实设备流程。上述结论均来自静态源码复核；动态可达性和修复结果仍须由验收测试证明。

## 3. G0-01 统一实施契约

### 3.1 必须覆盖的生产路径

| 类别 | 路径 | 当前调用链与风险 |
|---|---|---|
| 新建 | 同步 Web | `WorkflowController.run` → `WorkflowWebService.run` 预检 → `WorkflowService.execute` → `createRun`；预检与插入之间存在竞争窗口 |
| 新建 | 异步 Web | `POST /api/workflows/{id}/run?async=1` → `WorkflowService.startAsync` → `createRun`；当前绕过同步预检，且异步判定使用字符串包含匹配 |
| 新建 | 调度器首次派发 | 先建 experiment_run → `ExperimentExecutor(existingRunId>0)` 跳过预检 → `runStep` → `workflows.execute` → `createRun` |
| 新建 | 直接实验执行 | `existingRunId<=0` 时可做实验级预检，但流程 Run 到后续每一步才真正创建；预检不能封住竞争窗口 |
| 再激活 | 暂停／崩溃恢复 resume | `POST /api/runs/{id}/resume` → `RunService` → `PauseCoordinator.resumeWorkflowRun` → `PauseManager.resumeTx` → `resumeToQueued`；当前没有统一门禁，可把旧 Run 再变为 active |

实施交付必须附全量入口、Run 插入点和 active 状态写入点清单。若发现本表之外的新建、再激活或状态回退路径，必须纳入同一协调边界或给出可验证的排除理由。不得以“没有新建 Run”为由豁免 resume。

### 3.2 三态决策与错误语义

| 场景 | 决策 | 稳定错误语义 |
|---|---|---|
| 输入有效、查询成功、无 active Run | `ALLOW` | 可继续原子准入 |
| 已知同 tenant／workflow 存在 active Run | `DENY_ACTIVE` | 保持 `EC-WORKFLOW-RUNNING` 与 active Run 信息兼容 |
| 参数非法或缺失 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-INVALID` 或项目既有等价稳定映射 |
| active 状态词表为空或不可判定 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-STATUS-UNKNOWN` |
| DB 断开、超时、查询或协调失败 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-UNAVAILABLE` |
| 准入后 worker 无法启动 | 启动失败并完成非 active 收尾 | `EC-WORKFLOW-WORKER-UNAVAILABLE` 或项目既有等价稳定映射 |

要求：

- `allowed()` 只能在 `ALLOW` 时为 true。
- active 冲突与未知故障必须使用不同 reason code。
- `DENY_UNKNOWN` 是拒绝，不是“异常放行”。
- 同步、异步和 resume 拒绝均返回非 2xx 和独立 `errorCode`；JSON 字段或形状变化不得使错误响应落回 200。
- `IllegalStateException`、`SQLException`、线程池拒绝及其他 worker 启动 `RuntimeException` 均须进入明确的异常映射，不得依赖框架默认错误页。

### 3.3 数据库级原子准入

1. 新建路径的“检查—准入—流程 Run 插入—初始状态写入”，以及 resume 路径的“检查—准入—原 Run 状态 UPDATE—恢复上下文写入”，必须形成明确事务边界。
2. 数据库协调必须在同一个数据库事务、同一个物理连接上成立，除非实施者提供可证明等价的事务级协调机制。MyBatis/Mapper 的插入或 UPDATE 不得另开连接绕开协调边界。
3. 可选择数据库唯一约束、advisory lock、guard row、可串行化事务或等价数据库级机制；实施者须记录选择依据。事务级锁的生命周期必须覆盖实际插入或状态 UPDATE。
4. 默认互斥粒度为 `(tenant_id, workflow_id)`；若领域模型需要其他维度，必须由源码与业务约束证明。
5. 单 JVM 的 `synchronized`、内存锁或单元测试模拟锁不能作为跨进程／跨实例正确性的最终证据。
6. 必须说明两个请求同时查询不到 active Run 时，为什么最终仍最多一个进入 active；resume 与 create 竞争同样适用。
7. 必须记录锁获取、active 检查、Run 创建或状态 UPDATE、初始／恢复状态写入、事务提交的顺序。
8. 锁等待超时、死锁、连接中断、事务回滚、提交结果未知均不得放行；准入后回滚必须释放协调资源，允许后续请求重新竞争。
9. 不同 workflow 应按设计并行，不得被无谓全局串行。
10. 外部分布式锁如被选用，TTL／续租本身不构成充分正确性证明；必须有所有权、fencing 与数据库最终协调等防 stale owner 机制。
11. 若采用唯一约束，必须同时设计默认互斥与显式 allow 的兼容语义，并证明 resume UPDATE 也受约束或等价事务协调保护；不能让唯一约束把正常 resume 变成未映射的数据库异常。

### 3.4 并发开关、Run 身份与状态守卫

- 默认策略：同一 tenant/workflow 同时最多一个 active Run。G01-T11、T12、T16、T20、T21、T26 的“恰好一个”均以默认互斥模式为基准。
- `LABOS_WORKFLOW_CONCURRENT_RUN=allow` 只能覆盖 `DENY_ACTIVE`，不能覆盖非法参数、空词表、DB 故障或事务协调未知。
- 若数据库约束与显式 allow 存在交互，必须说明并实现一致语义，不能让配置失效或绕过未知态。
- `ExperimentExecutor.existingRunId` 是 experiment_run ID；调度器复用实验 Run 不等于流程续跑。
- 门禁参数 `excludeRunId`、experiment_run ID 与真实 resume 的 workflow Run ID 是三个不同概念。当前两个生产门禁调用均传 null，T04/T22 只能证明接口契约，不能替代真实 resume 验收。
- 合法流程续跑／resume 必须校验 Run 存在、tenant／workflow 归属、pause 或 recoverable 状态和当前请求上下文。其他 tenant、其他 workflow、不存在或无关 Run ID 均不得被排除或再激活。
- resume 的状态 UPDATE 必须带可证明的原状态／recoverable 守卫并核对影响行数，防止检查后状态已被另一请求改变；不能只依赖前置 SELECT。
- 同一 Run 的合法 resume 只排除自身旧状态，不得排除同流程另一个 active Run。

### 3.5 异步启动、参数解析与副作用

- 只有原子准入成功后才可提交 worker；竞争败者不得留下新 Run、worker 或设备命令。
- 准入成功后若 worker 提交失败，必须捕获所有相关启动异常、释放 worker slot，并把已创建 Run 按明确契约收尾为非 active；收尾失败须记录为独立基础设施故障并进入可恢复机制，不得静默留下永久 running active Run。
- worker 启动失败必须返回稳定 `errorCode`，且后续同流程可再次启动；T27 对此独立验收。
- 异步标志必须通过框架／现有参数解析器按参数名和值精确读取；`notasync=1`、`x=async=1` 等不得进入异步分支，精确 `async=1` 仍进入统一门禁。
- 拒绝路径不得留下新 running／queued 流程 Run、worker、设备命令或物料／位置副作用。真实 resume 被拒绝时，原 Run 必须保持原状态。
- 调度器预先创建的 experiment_run 应按既有失败契约明确收尾，不得永久滞留 queued；不要求倒删既有审计记录。

### 3.6 G0-01 完整验收矩阵

共 **28 组验收，G01-T24 含 4 个独立子项**。所有“实际证据”当前均为待执行。通过必须有可追溯的真实测试输出；SKIP、环境缺失和测试文件存在均不计 PASS。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G01-T01 | 查询成功，无同流程 active Run | `ALLOW`，可进入正常新建流程 | 待执行 |
| G01-T02 | 同 tenant／workflow 存在 running/paused/quarantined Run | `DENY_ACTIVE`，返回正确 active Run ID／状态 | 待执行 |
| G01-T03 | 仅有非 active／终态 Run，或另一 workflow 有 active Run | 不误拦截，不无谓全局串行 | 待执行 |
| G01-T04 | 门禁接口的合法 excludeRunId 自身排除，同时存在另一 active Run | 只排除自身，不排除其他冲突；experiment_run ID 不可替代；本项不替代真实 resume | 待执行 |
| G01-T05 | `db=null`、tenant/workflow ID 非法 | `DENY_UNKNOWN + INVALID`；所有新建入口均无 Run／worker／命令 | 待执行 |
| G01-T06 | active 状态词表为空 | `DENY_UNKNOWN + STATUS-UNKNOWN`，不得 ALLOW | 待执行 |
| G01-T07 | DB 断开、连接超时、查询 SQLException | `DENY_UNKNOWN + UNAVAILABLE`；无副作用；安全日志可追溯 | 待执行 |
| G01-T08 | 同步 Web active 冲突 | 保持 `EC-WORKFLOW-RUNNING` 和 active Run 字段兼容，非 2xx | 待执行 |
| G01-T09 | 同步 Web 未知／DB 故障 | 独立未知错误和非 2xx，不误报 active 或成功 | 待执行 |
| G01-T10 | 调度器已建 experiment_run，以 existingRunId>0 首次派发 | 不跳过流程准入；消费同一决策；无违规流程 Run | 待执行 |
| G01-T11 | 两个同步 Web 竞争同一 tenant/workflow | 恰好一个准入；败者 DENY_ACTIVE、无设备命令；本项是 T20 的定向分解 | 待执行 |
| G01-T12 | 调度首次派发与同步 Web 竞争 | 恰好一个流程 Run；另一方明确拒绝；本项是 T20 的定向分解 | 待执行 |
| G01-T13 | 两个不同 workflow 并发新建 | 均可按各自规则准入 | 待执行 |
| G01-T14 | 默认与显式 allow，叠加非法参数、空词表、DB 故障 | allow 只覆盖 active；所有未知态 fail-closed | 待执行 |
| G01-T15 | 既有 WorkflowRunGate 与 workflow 回归集 | 无回归；新增测试进入可信测试入口；本项是回归证据，不单独证明新缺陷已检出 | 待执行 |
| G01-T16 | 两个 `async=1` 同时启动同一 tenant/workflow | 恰好一个 running；败者无新 Run、worker、设备命令 | 待执行 |
| G01-T17 | 实验预检通过后，真正 createRun 前另一请求占用流程 | 插入边界拒绝；不派发命令；实验 Run 按契约收尾 | 待执行 |
| G01-T18 | 同步／异步分别发生 active、invalid、status-unknown、DB unavailable、IllegalStateException、SQLException、worker 启动失败 | 可区分 errorCode、非 2xx；JSON 变化不产生 200；worker 失败有专门稳定错误 | 待执行 |
| G01-T19 | 调度首次派发在准入处失败 | experiment_run 不永久 queued；无流程 Run、worker、命令 | 待执行 |
| G01-T20 | 同步、异步、直接实验、调度首次派发两两竞争 | 每组合恰好一个流程 Run；不同 workflow 对照不受误伤 | 待执行 |
| G01-T21 | 两个独立进程／实例，各自使用独立连接池和物理 DB 连接争抢同一 workflow | 数据库级协调下最多一个准入；内存锁、单连接池或模拟锁不构成证据 | 待执行 |
| G01-T22 | excludeRunId 指向跨 tenant、跨 workflow、不存在或无关 Run | 全部拒绝且不排除任何 Run；合法自身对照按设计通过；本项不替代真实 resume | 待执行 |
| G01-T23 | 准入成功后事务回滚，随后另一请求竞争 | 回滚不留 active Run；锁释放后另一请求可准入 | 待执行 |
| G01-T24a | 锁等待超时 | 默认与 allow 均不放行、无副作用；原因可诊断、可审计 | 待执行 |
| G01-T24b | 死锁 | 默认与 allow 均不放行、无副作用；原因可诊断、可审计 | 待执行 |
| G01-T24c | 连接中断 | 默认与 allow 均不放行、无副作用；原因可诊断、可审计 | 待执行 |
| G01-T24d | 提交结果未知 | 默认与 allow 均不声称成功；不重复派发；可通过对账确定最终状态 | 待执行 |
| G01-T25 | failed/recoverable 或 paused 的旧 Run A 存在，先新建同流程 Run B，再调用真实 `/resume` 恢复 A | A 被 DENY_ACTIVE；B 保持唯一 active；A 保持原状态；无第二 worker 或设备命令 | 待执行 |
| G01-T26 | 先 resume A 再新建 B；以及真实 resume 与 create 同时竞争同一 tenant/workflow | 两种顺序均最多一个 active；并发竞争恰好一个准入，败者稳定拒绝且无副作用 | 待执行 |
| G01-T27 | 原子准入成功后 `RUN_POOL.submit` 拒绝或抛出其他 RuntimeException | 释放 slot；无永久 running active 僵尸；返回稳定 errorCode；后续同流程可重新启动 | 待执行 |
| G01-T28 | 查询串分别为 `notasync=1`、`x=async=1`、精确 `async=1` | 前两者不进入异步分支；精确 async=1 进入异步且消费统一门禁 | 待执行 |

## 4. G0-07 统一实施契约

### 4.1 最小修复范围

1. 三参数 `compileAll` 对每个 task 节点调用三参数 `compile(n,protocolCap,capabilityByResource)`；非 task 继续跳过。
2. `WorkflowService` 执行前展开调用 `compile(n,protocolCap,resourceCap)`。
3. 两参数 `compile` / `compileAll` 保持空映射和既有回退语义，不全局改写。
4. 不重写 `resolveCapability`，不调整逻辑／伪资源、explicit、bound、protocol、protocol-short、heuristic 的顺序。
5. 实施前必须全仓清查所有 `RecipeCompiler.compile` / `compileAll` 调用点，记录文件、方法、场景、两／三参数语义、对应测试或豁免理由。

### 4.2 判别性夹具

关键夹具必须满足：

- 主 recipe 展开用例的 `protocolName` 为空或空白，避免“已指定具体方法”的提前单步返回。
- 节点显式 capability 为空。
- `equipmentInstanceId` 使用合法 ID，例如 `101`，并以同一个 ID 作为 capabilityByResource 的 key；不得使用伪资源 ID。
- bound capability 与 protocol capability 不同，例如 `BOUND_CAP` 与 `PROTOCOL_CAP`。
- 两种能力配置不同 recipe 方法序列，使 capability、source、steps、sampleCount、fromRecipe 能共同识别丢参。
- 节点名称不得命中逻辑节点关键词。
- 具体 protocolName 的 G07-T08 也使用空 explicit、合法资源 ID 和不同的 bound/protocol 能力；由于能力解析先于单步返回，旧实现必须在 capability/source 上失败，同时仍保持单步。
- 另设显式 `EXPLICIT_CAP` 优先级用例。G07-T02、G07-T08 与 G07-T10 必须提供旧实现失败日志；旧实现仍为绿时先修测试，不得直接判 PASS。

### 4.3 同快照完整 NodePlan 一致性

“预览与执行一致”必须比较完整 `RecipeCompiler.NodePlan`，至少包括：

- nodeId、nodeKey、name；
- capability、capabilitySource；
- 每个 Step 的 seq、method；
- sampleCount、fromRecipe。

同一不可变输入快照至少固定或记录 Workflow、NodeDef、设备绑定、Recipe 模板、Capability 映射及 protocolCap 的版本或规范化内容 hash。若 Recipe 数据本身没有版本号，可使用规范化输入 hash；不要求为本卡额外建设版本管理系统。

服务级测试必须在任何设备动作前直接截获并比较直接 compile、compileAll 预览与执行前展开的 NodePlan。不得使用 RecoverableNode、GraphRunner 能力字段或最终设备事件替代，外围字段原本就可能一致而产生假阴性。

输入版本变化必须重新计算，不得复用旧缓存或旧预览。该契约只证明 Java 编译预览与执行前步骤计划一致，**不外推为真机物理动作完全一致**。

### 4.4 G0-07 完整验收矩阵

共 **14 项验收**。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G07-T01 | 空 protocolName、空 explicit、合法 ID=101；bound/protocol 能力与 recipe 均不同 | 三参数 compile 得到 `BOUND_CAP / bound` 和 BOUND recipe 方法序列 | 待执行 |
| G07-T02 | 将 T01 节点放入三参数 compileAll | 与直接三参数 compile 的完整 NodePlan 一致；旧实现必须失败并留存日志 | 待执行 |
| G07-T03 | explicit、bound、protocol 三者同时存在且不同 | 得到 `EXPLICIT_CAP / explicit`，优先级未重排 | 待执行 |
| G07-T04 | 无 explicit、无有效 bound、protocol 可反查 | 保持 `PROTOCOL_CAP / protocol` | 待执行 |
| G07-T05 | 映射 null／空，或合法 ID 不在映射中 | 无新增异常；按 protocol／后续规则回退 | 待执行 |
| G07-T06 | 两参数 compile 与 compileAll | 维持空映射语义，不读取绑定能力 | 待执行 |
| G07-T07 | 批量输入同时含 task 与非 task | 非 task 跳过；每个 task 收到同一映射 | 待执行 |
| G07-T08 | 具体 protocolName、空 explicit、合法 ID=101，bound/protocol 能力不同 | 得到 `BOUND_CAP / bound`，仍单步不展开；旧两参数实现必须因 capability/source 失败并留存日志 | 待执行 |
| G07-T09 | 无模板、逻辑节点、伪资源 ID、既有 recipe 展开 | 步数、方法、sampleCount/fromRecipe 无回归 | 待执行 |
| G07-T10 | 同一不可变快照下预览与执行前展开 | 两侧完整 NodePlan 一致；旧实现必须失败并留存日志；不使用外围字段替代 | 待执行 |
| G07-T11 | 设备绑定、Recipe 模板、Capability 映射或 protocolCap 版本／hash 变化 | 新版本重新计算；不混用旧缓存／旧计划 | 待执行 |
| G07-T12 | 既有 RecipeCompiler 与 workflow 回归集 | 无回归；测试入口与退出码可信 | 待执行 |
| G07-T13 | 全仓 compile／compileAll 调用点清单复核 | 每个调用点均分类并登记测试或豁免理由，无未分类项 | 待执行 |
| G07-T14 | 同一不可变快照下直接 compile、compileAll 预览、执行前展开 | 三方完整 NodePlan 一致；任一旧快照不得判 PASS | 待执行 |

## 5. 评审意见处理与范围边界

### 5.1 本轮及前序意见处理

本节只保留技术结论；详细评论以 PR 记录为准。

| 评审意见 | 处理 | 当前落点 |
|---|---|---|
| 三个异常 allow 必须 fail-closed | 采纳 | §3.2 与 T05–T07 |
| 异步与调度首次派发绕过门禁 | 采纳 | §3.1 与 T10、T16、T19、T20 |
| 原子边界必须落在 createRun／insertRun | 采纳并扩展到 active 状态 UPDATE | §3.3 |
| experiment_run ID 不等于流程续跑 ID | 采纳 | §3.4 与 T04、T10、T22 |
| 真实 resume 再激活未过门禁，failed/recoverable 可形成双 active | 采纳；为本轮新增 P0 事实 | §2、§3.1、§3.3、§3.4 与 T25、T26 |
| excludeRunId 接口测试不能替代真实 resume | 采纳 | §3.4、T04、T22、T25、T26 |
| worker 提交 RuntimeException 可留下 running 僵尸 | 采纳 | §2、§3.2、§3.5 与 T18、T27 |
| 异步异常缺稳定 errorCode | 采纳 | §3.2 与 T18、T27 |
| 字符串包含匹配 async 参数不精确 | 采纳 | §2、§3.5 与 T28 |
| allow 不得放行未知故障 | 采纳 | T14、T24a–T24d |
| 数据库级跨进程协调、同一事务与同一物理连接 | 采纳 | §3.3 与 T21、T23、T24a–T24d |
| T21 必须明确双实例与独立连接池 | 采纳 | T21 |
| T24 四类故障需独立验收；T11/T12、T15 需说明证据角色 | 采纳；保留原 ID 以避免历史引用漂移 | §3.6 |
| 预览／执行断言必须落在完整 NodePlan | 采纳 | §4.3 与 G07-T10、T14 |
| 空 protocolName、合法 ID、不同 recipe 的判别夹具 | 采纳 | §4.2 与 G07-T01–T03 |
| 具体 protocolName 用例也可判别 capability/source 丢参 | 采纳；升级为旧实现必须失败 | §4.2 与 G07-T08 |
| 全仓调用点清查 | 采纳 | §4.1 与 G07-T13 |
| 同一不可变输入快照及 protocolCap 版本／hash | 采纳 | §4.3 与 G07-T10、T11、T14 |
| 自动差分测试 | 已吸收到现有验收，不重复新增 | G07-T02、T10、T14 |
| 外部 TTL 锁提示 | 仅作为风险提示；不作为充分方案 | §3.3 第 10 项 |
| 每项证据必须可追溯命令、环境、退出码和原始日志 | 采纳并强化 | §6.3 |
| 将 UNKNOWN 描述为异常放行 | 不采纳该表述 | UNKNOWN 必须拒绝 |
| 将计划一致外推为真机物理一致 | 不采纳 | 真实设备证据继续独立保持 UNKNOWN |
| 因单次抓取截断而断言 04/05/06 文档不存在 | 不采纳该事实前提 | 本文件开头已给出三个实际相对链接；评审须注明实际 commit |
| 因抓取截断而把 `createRun → RunMapper.insertRun` 降级为未核实 | 不采纳 | 已读取完整方法体，保留为静态事实；动态路径仍须测试 |
| 旧评论中的 15／12 项或 24＋14 项口径 | 已被本版更新 | 当前为 G0-01 28 组（T24 四子项）、G0-07 14 项 |

### 5.2 本文件不覆盖、也不关闭的事项

以下三项跨文档 P0 仍保持未裁决，不因本基线收敛而视为解决：

1. **第一阶段范围有三套定义。** `03-AGENT-AND-CAPABILITY-PLATFORM.md` 的 MVP-A/B/C、`g0/03-G0-REMEDIATION-AND-NEXT-STEPS-20261118.md` 的 G1 六工具、`05-AI-WORKBENCH-0-30D.md` 的三类任务需要在 G1／30 天方案评审前统一。
2. **G1 对象级 ACL 门槛与 30 天实现范围冲突。** G1 门槛要求与 `05/06` 及其修订附录中“本期不实现项目级 ACL”的边界必须统一裁决，不能同时作为验收承诺。
3. **路线图的并行阶段与串行依赖、三条时间线尚未对齐。** `04-ROADMAP.md`、G0 整改计划和 30 天实施文档需要统一依赖关系与日期口径。

这些事项不属于 G0-01/G0-07 两个独立代码热修的关闭条件，不阻塞其实施；但在 G1 准入或 30 天方案最终评审前必须另行裁决并记录。

## 6. 实施、提交与证据要求

### 6.1 G0-01

- 建议分支：`fix/g0-01-workflow-run-gate-fail-closed`。
- 先补能击中旧实现的失败测试，再实现三态、全生命周期原子准入、Run 身份与状态守卫、异步启动收尾和 API 映射。
- 交付记录包含实际 HEAD、入口／插入点／active 状态写入点清单、数据库协调方案、事务与物理连接边界、错误映射、迁移与回滚。
- 若增加数据库迁移，必须验证 fresh DB、upgrade DB、drift、active 状态集合、显式 allow、冲突映射和回滚。
- 回滚不得恢复异常 allow 或 resume 绕门禁；必要时关闭受影响的新建／再激活入口，同时保持 fail-closed。

### 6.2 G0-07

- 建议分支：`fix/g0-07-recipe-compiler-capability`。
- 先完成调用点清单与判别性测试，再最小修改两处参数透传。
- 交付记录包含调用点分类、输入快照、完整 NodePlan 截获点、修复前失败证据和修复后结果。
- 回滚必须同时考虑预览与执行两处，不能只回滚一侧制造新分歧。

### 6.3 共同证据门槛

1. 每个验收 ID 的证据单元格必须能追溯到实际命令、代码 HEAD、运行环境、数据库／配置、退出码和原始日志位置；不得只写“PASS”。
2. 修复前失败与修复后通过要形成对照，防止永远为绿的测试；G07-T02、T08、T10 必须保留旧实现失败日志。
3. 未执行、环境缺失或 SKIP 均保持 UNKNOWN；不得写成 PASS。
4. 执行当前可用的 `./scripts/ci-gate.sh --fast` 和 workflow 测试映射；脚本或段号变化时以当前仓库为准。
5. Core/Control 实施，QA/Integration 独立验收；两项分别回填证据。
6. 数据库／模拟器证据不能替代真实设备现场证据；历史串扰和物理闭环没有现场材料时继续保持 UNKNOWN。
7. 文档评审、任务卡完成、PR 可合并或 CI 状态本身均不等于风险关闭。

## 7. 请求最终评审回答的问题

请评审者基于本文件和其实际注明的完整 commit，至少回答：

1. 是否仍有未覆盖的新建、resume 再激活或状态回退路径，或混淆 experiment_run ID、excludeRunId 和 workflow Run ID 的表述？
2. 数据库级原子准入是否同时覆盖 Run 插入和 resume 状态 UPDATE，并能处理默认互斥、显式 allow、状态守卫和提交未知？
3. worker 启动失败收尾、稳定 errorCode 与精确 async 参数解析是否可实施、可重复验收？
4. 28 组 G0-01 验收（T24 四子项）是否存在重复、遗漏或无法执行的条目？给出具体 ID 与修改建议。
5. G0-07 全仓调用点清单、判别夹具和同快照三方完整 NodePlan 比较是否足以检出旧缺陷？G07-T08 是否能在旧代码上失败？
6. 是否有把设计通过误写为代码通过、把模拟证据外推为真机证据，或把 §5.2 三项跨文档冲突误写为已关闭的表述？

每条意见按以下格式返回：

```text
结论：支持 / 反对 / 需要补充证据
位置：本文件章节或验收 ID
依据：源码、接口、数据对象、实验约束或可复现推理
风险：安全、并发、兼容性、事实边界、测试有效性或交付范围
建议：具体删改内容或替代方案
验收：如何证明建议有效；哪些情况必须保持 UNKNOWN
```

评审开头必须写明：`实际评审 commit：<完整 SHA>`。未注明 commit、仍引用旧版数量口径、只评价 Web／ExperimentExecutor 两个预检点或忽略真实 resume 路径的回复，不作为本统一基线的最终评审结论。

## 8. 收敛状态与实施门槛

本版已将新增源码事实纳入统一设计，不再保留“所有意见均已闭环”或“后续不再修订”的表述。出现新的源码事实、调用路径或可复现的反例时，仍必须更新本文件与对应任务卡。

对没有新证据的泛化架构讨论不再重复展开。最终回执确认本文件与任务卡一致后，可进入：

**G0-01 → 先写能打穿旧实现的失败测试 → 数据库级原子准入（新建＋再激活）→ 异步失败收尾 → 独立进程／连接与真实 resume 验证 → QA/Integration 验收 → 再进入 G0-07。**

当前仅是文档级收敛；业务代码未修改，业务测试未执行，两项风险均未关闭。
