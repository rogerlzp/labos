# G0-01 / G0-07 修复实施与交叉评审统一基线

- 文档版本：2026-11-18 统一评审稿
- 对应 PR：[#4](https://github.com/rogerlzp/labos/pull/4)
- 历史 G0 核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 本轮相关源码复核树：`cf772fcc87f65210e66b2f720c6e65c424611cdb`
- 当前文档状态：待按本文件进行统一交叉评审
- 当前代码状态：G0-01、G0-07 均未实施修复
- 当前证据状态：构建、业务测试、CI、真实设备验证均为 UNKNOWN
- 实施边界：两个独立 P0 修复单元，独立分支、独立提交、独立 PR、独立验收；不占 AI Workbench 工期

> 本文件是后续交叉评审的统一入口，汇总源码证据、已采纳评审意见、实施契约和完整验收矩阵。04/05 任务卡用于实施与证据回填，06 用于保留独立评审记录。发现本文件与任务卡不一致时，必须先登记并修订，不得自行选择较宽松的解释。

> 评审回复必须注明实际读取的 commit。设计评审通过只表示可以进入对应修复实施，不表示代码已修复、测试已通过或 G0 已关闭。

## 1. 背景与统一结论

G0 历史核查发现两类明确代码缺陷：

1. **G0-01：同流程并发门禁 fail-open，且预检没有覆盖所有生产创建路径。** 仅修改三个异常 `allow()` 不足以解决问题；必须在真正流程 Run 插入边界完成全入口原子准入。
2. **G0-07：RecipeCompiler 批量预览与执行前展开丢失设备能力映射。** 两处必须同步修复，并以完整 `RecipeCompiler.NodePlan` 做同快照一致性验证，不能只比较外围执行节点的能力字段。

统一实施顺序为：先完成 G0-01 的数据库级原子准入与并发证据，再实施 G0-07；两者仍分别提交和验收。G0-07 不依赖 G0-01 的代码实现细节，但不能描述为“已经在安全门禁保护下”，直至 G0-01 实际验收通过。

本文件不改变历史 G0 报告的历史结论，也不扩大为执行系统重写。

## 2. 已静态核实的源码事实

以下均为现状事实，不是目标设计：

| 位置 | 当前事实 | 修复含义 |
|---|---|---|
| `WorkflowRunGate.check(DbConfig,long,long,Long)` | 参数非法、active 状态词表为空、`SQLException` 三处返回 allow；并发 allow 开关位于这些判定之前 | 未知态必须 fail-closed；allow 只能覆盖 active 冲突 |
| `WorkflowWebService.run(long,String)` | 同步 Web 在调用执行前消费 `Decision.allowed()` | 可保留提前反馈，但不是最终原子边界 |
| `WorkflowController.run` 的 `async=1` 分支 | 直接调用 `WorkflowService.startAsync`，不经过同步 Web 门禁 | 异步入口必须纳入统一准入 |
| `WorkflowService.startAsync(...)` | 先 `createRun`，再提交后台 worker | 准入败者不得留下 Run、worker 或设备命令 |
| `ExperimentScheduler → ExperimentExecutor` | 调度器先创建 experiment_run；`existingRunId>0` 时跳过实验级门禁预检 | experiment_run ID 不等于 workflow 续跑 ID，首次派发不能绕过 |
| `ExperimentExecutor.runStep` | 每一步调用 `workflows.execute(workflowId)`，最终创建流程 Run | 真正流程 Run 创建点必须再次准入 |
| `WorkflowService.createRun → RunMapper.insertRun` | 本轮复核到的流程 Run 共同最终插入路径 | 原子安全边界应落在该路径；实施前仍须重新全仓普查 |
| `RecipeCompiler.compileAll(...,capabilityByResource)` | 循环内仍调用两参数 `compile(n,protocolCap)`，后者使用空映射 | 必须改为三参数透传 |
| `WorkflowService` 执行前展开 | 已持有 `resourceCap`，仍调用两参数 `compiler.compile(n,protocolCap)` | 必须与预览一起修复 |
| `RealWorkflowRunner.resolveCapability` | 逻辑／伪资源 → explicit → bound → protocol → protocol-short → heuristic／unknown | 不得借修复重排既有优先级 |

本轮没有修改上述业务源码，没有运行构建、测试或真实设备流程。

## 3. G0-01 统一实施契约

### 3.1 必须覆盖的生产路径

| 路径 | 当前调用链与风险 |
|---|---|
| 同步 Web | `WorkflowController.run` → `WorkflowWebService.run` 预检 → `WorkflowService.execute` → `createRun`；预检与插入之间存在竞争窗口 |
| 异步 Web | `POST /api/workflows/{id}/run?async=1` → `WorkflowService.startAsync` → `createRun`；当前绕过同步预检 |
| 调度器首次派发 | 先建 experiment_run → `ExperimentExecutor(existingRunId>0)` 跳过预检 → `runStep` → `workflows.execute` → `createRun` |
| 直接实验执行 | `existingRunId<=0` 时可做实验级预检，但流程 Run 到后续每一步才真正创建；预检不能封住竞争窗口 |

实施交付必须附全量入口与插入点清单。若发现本表之外的新建流程 Run 路径，必须纳入同一协调边界或给出可验证的排除理由。

### 3.2 三态决策与错误语义

| 场景 | 决策 | 稳定错误语义 |
|---|---|---|
| 输入有效、查询成功、无 active Run | `ALLOW` | 可继续原子准入 |
| 已知同 tenant／workflow 存在 active Run | `DENY_ACTIVE` | 保持 `EC-WORKFLOW-RUNNING` 与 active Run 信息兼容 |
| 参数非法或缺失 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-INVALID` 或项目既有等价稳定映射 |
| active 状态词表为空或不可判定 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-STATUS-UNKNOWN` |
| DB 断开、超时、查询或协调失败 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-UNAVAILABLE` |

要求：

- `allowed()` 只能在 `ALLOW` 时为 true。
- active 冲突与未知故障必须使用不同 reason code。
- `DENY_UNKNOWN` 是拒绝，不是“异常放行”。
- 同步与异步拒绝均返回非 2xx 和独立 `errorCode`；JSON 字段或形状变化不得使错误响应落回 200。

### 3.3 数据库级原子准入

1. “检查—准入—流程 Run 插入—初始状态写入”必须形成明确事务边界，覆盖所有创建入口。
2. 可选择数据库唯一约束、advisory lock、guard row、可串行化事务或等价数据库级机制；实施者须记录选择依据。
3. 默认互斥粒度为 `(tenant_id, workflow_id)`；若领域模型需要其他维度，必须由源码与业务约束证明。
4. 单 JVM 的 `synchronized`、内存锁或单元测试模拟锁不能作为跨进程／跨实例正确性的最终证据。
5. 必须说明两个请求同时查询不到 active Run 时，为什么最终仍最多一个创建成功。
6. 必须记录锁获取、active 检查、Run 创建、初始状态写入、事务提交的顺序。
7. 锁等待超时、死锁、连接中断、事务回滚、提交结果未知均不得放行；准入后回滚必须释放协调资源，允许后续请求重新竞争。
8. 不同 workflow 应按设计并行，不得被无谓全局串行。
9. 外部分布式锁如被选用，TTL／续租本身不构成充分正确性证明；必须有所有权、 fencing 与数据库最终协调等防 stale owner 机制。

### 3.4 并发开关、续跑身份与副作用

- 默认策略：同一 tenant/workflow 同时最多一个 active Run。
- `LABOS_WORKFLOW_CONCURRENT_RUN=allow` 只能覆盖 `DENY_ACTIVE`，不能覆盖非法参数、空词表、DB 故障或事务协调未知。
- 若数据库约束与显式 allow 存在交互，必须说明并实现一致语义，不能让配置失效或绕过未知态。
- `ExperimentExecutor.existingRunId` 是 experiment_run ID；调度器复用实验 Run 不等于流程续跑。
- 合法流程续跑必须校验 Run 存在、tenant／workflow 归属和当前续跑上下文，只排除自身。
- 其他 tenant、其他 workflow、不存在或无关 Run ID 均不得被排除。
- 拒绝路径不得留下新 running 流程 Run、worker、设备命令或物料／位置副作用。
- 调度器预先创建的 experiment_run 应按既有失败契约明确收尾，不得永久滞留 queued；不要求倒删既有审计记录。

### 3.5 G0-01 完整验收矩阵

所有“实际证据”当前均为待执行。通过必须有真实测试输出；SKIP、环境缺失和测试文件存在均不计 PASS。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G01-T01 | 查询成功，无同流程 active Run | `ALLOW`，可进入正常新建流程 | 待执行 |
| G01-T02 | 同 tenant／workflow 存在 running/paused/quarantined Run | `DENY_ACTIVE`，返回正确 active Run ID／状态 | 待执行 |
| G01-T03 | 仅有非 active／终态 Run，或另一 workflow 有 active Run | 不误拦截，不无谓全局串行 | 待执行 |
| G01-T04 | 合法流程续跑排除自身，同时存在另一 active Run | 只排除自身，不排除其他冲突；experiment_run ID 不可替代 | 待执行 |
| G01-T05 | `db=null`、tenant/workflow ID 非法 | `DENY_UNKNOWN + INVALID`；所有新建入口均无 Run／worker／命令 | 待执行 |
| G01-T06 | active 状态词表为空 | `DENY_UNKNOWN + STATUS-UNKNOWN`，不得 ALLOW | 待执行 |
| G01-T07 | DB 断开、连接超时、查询 SQLException | `DENY_UNKNOWN + UNAVAILABLE`；无副作用；安全日志可追溯 | 待执行 |
| G01-T08 | 同步 Web active 冲突 | 保持 `EC-WORKFLOW-RUNNING` 和 active Run 字段兼容，非 2xx | 待执行 |
| G01-T09 | 同步 Web 未知／DB 故障 | 独立未知错误和非 2xx，不误报 active 或成功 | 待执行 |
| G01-T10 | 调度器已建 experiment_run，以 existingRunId>0 首次派发 | 不跳过流程准入；消费同一决策；无违规流程 Run | 待执行 |
| G01-T11 | 两个同步 Web 竞争同一 tenant/workflow | 恰好一个准入；败者 DENY_ACTIVE、无设备命令 | 待执行 |
| G01-T12 | 调度首次派发与同步 Web 竞争 | 恰好一个流程 Run；另一方明确拒绝 | 待执行 |
| G01-T13 | 两个不同 workflow 并发新建 | 均可按各自规则准入 | 待执行 |
| G01-T14 | 默认与显式 allow，叠加非法参数、空词表、DB 故障 | allow 只覆盖 active；所有未知态 fail-closed | 待执行 |
| G01-T15 | 既有 WorkflowRunGate 与 workflow 回归集 | 无回归；新增测试进入可信测试入口 | 待执行 |
| G01-T16 | 两个 `async=1` 同时启动同一 tenant/workflow | 恰好一个 running；败者无新 Run、worker、设备命令 | 待执行 |
| G01-T17 | 实验预检通过后，真正 createRun 前另一请求占用流程 | 插入边界拒绝；不派发命令；实验 Run 按契约收尾 | 待执行 |
| G01-T18 | 同步／异步分别发生 active、invalid、status-unknown、DB unavailable | 可区分 errorCode、非 2xx；JSON 变化不产生 200 | 待执行 |
| G01-T19 | 调度首次派发在准入处失败 | experiment_run 不永久 queued；无流程 Run、worker、命令 | 待执行 |
| G01-T20 | 同步、异步、直接实验、调度首次派发两两竞争 | 每组合恰好一个流程 Run；不同 workflow 对照不受误伤 | 待执行 |
| G01-T21 | 两个独立进程／实例、独立 DB 连接争抢同一 workflow | 数据库级协调下最多一个准入；内存锁不构成证据 | 待执行 |
| G01-T22 | excludeRunId 指向跨 tenant、跨 workflow、不存在或无关 Run | 全部拒绝且不排除任何 Run；合法自身对照按设计通过 | 待执行 |
| G01-T23 | 准入成功后事务回滚，随后另一请求竞争 | 回滚不留 active Run；锁释放后另一请求可准入 | 待执行 |
| G01-T24 | 锁等待超时、死锁、连接中断、提交结果未知 | 均不放行、无副作用；原因可诊断、可审计 | 待执行 |

## 4. G0-07 统一实施契约

### 4.1 最小修复范围

1. 三参数 `compileAll` 对每个 task 节点调用三参数 `compile(n,protocolCap,capabilityByResource)`；非 task 继续跳过。
2. `WorkflowService` 执行前展开调用 `compile(n,protocolCap,resourceCap)`。
3. 两参数 `compile` / `compileAll` 保持空映射和既有回退语义，不全局改写。
4. 不重写 `resolveCapability`，不调整逻辑／伪资源、explicit、bound、protocol、protocol-short、heuristic 的顺序。
5. 实施前必须全仓清查所有 `RecipeCompiler.compile` / `compileAll` 调用点，记录文件、方法、场景、两／三参数语义、对应测试或豁免理由。

### 4.2 判别性夹具

关键夹具必须满足：

- `protocolName` 为空或空白，避免“已指定具体方法”的提前单步返回。
- 节点显式 capability 为空。
- `equipmentInstanceId` 使用合法 ID，例如 `101`，并以同一个 ID 作为 capabilityByResource 的 key；不得使用伪资源 ID。
- bound capability 与 protocol capability 不同，例如 `BOUND_CAP` 与 `PROTOCOL_CAP`。
- 两种能力配置不同 recipe 方法序列，使 capability、source、steps、sampleCount、fromRecipe 能共同识别丢参。
- 节点名称不得命中逻辑节点关键词。
- 另设显式 `EXPLICIT_CAP` 和具体 protocolName 的兼容用例，但具体 protocolName 单步用例不能替代 bound recipe 判别证明。
- G07-T02 与 G07-T10 必须证明能在旧实现上失败；旧实现仍为绿时先修测试，不得直接判 PASS。

### 4.3 同快照 NodePlan 一致性

同一不可变输入快照至少固定或记录：

- Workflow 版本；
- NodeDef／节点定义；
- 设备绑定版本；
- Recipe 模板版本；
- Capability 映射版本。

服务级测试必须直接截获并比较预览与执行前展开的完整 `RecipeCompiler.NodePlan`，至少包括 nodeId、capability、capabilitySource、method 序列、sampleCount 和 fromRecipe。

不得使用 RecoverableNode、GraphRunner 能力字段或最终设备事件替代。外围能力字段原本就可能一致，会产生假阴性。测试必须在任何设备动作前完成，不依赖真实设备回包。

输入版本变化必须重新计算，不得复用旧缓存或旧预览。该契约只证明 Java 编译预览与执行前步骤计划一致，**不外推为真机物理动作完全一致**。

### 4.4 G0-07 完整验收矩阵

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G07-T01 | 空 protocolName、空 explicit、合法 ID=101；bound/protocol 能力与 recipe 均不同 | 三参数 compile 得到 `BOUND_CAP / bound` 和 BOUND recipe 方法序列 | 待执行 |
| G07-T02 | 将 T01 节点放入三参数 compileAll | 与直接三参数 compile 的完整 NodePlan 一致；旧实现必须失败 | 待执行 |
| G07-T03 | explicit、bound、protocol 三者同时存在且不同 | 得到 `EXPLICIT_CAP / explicit`，优先级未重排 | 待执行 |
| G07-T04 | 无 explicit、无有效 bound、protocol 可反查 | 保持 `PROTOCOL_CAP / protocol` | 待执行 |
| G07-T05 | 映射 null／空，或合法 ID 不在映射中 | 无新增异常；按 protocol／后续规则回退 | 待执行 |
| G07-T06 | 两参数 compile 与 compileAll | 维持空映射语义，不读取绑定能力 | 待执行 |
| G07-T07 | 批量输入同时含 task 与非 task | 非 task 跳过；每个 task 收到同一映射 | 待执行 |
| G07-T08 | 已指定具体 protocolName | 能力解析正确，仍单步不展开；不替代 T02 | 待执行 |
| G07-T09 | 无模板、逻辑节点、伪资源 ID、既有 recipe 展开 | 步数、方法、sampleCount/fromRecipe 无回归 | 待执行 |
| G07-T10 | 同一不可变快照下预览与执行前展开 | 两侧完整 NodePlan 一致；旧实现必须失败；不使用外围字段替代 | 待执行 |
| G07-T11 | 设备绑定、Recipe 模板或 Capability 映射版本变化 | 新版本重新计算；不混用旧缓存／旧计划 | 待执行 |
| G07-T12 | 既有 RecipeCompiler 与 workflow 回归集 | 无回归；测试入口与退出码可信 | 待执行 |
| G07-T13 | 全仓 compile／compileAll 调用点清单复核 | 每个调用点均分类并登记测试或豁免理由，无未分类项 | 待执行 |
| G07-T14 | 同一不可变快照下直接 compile、compileAll 预览、执行前展开 | 三方完整 NodePlan 一致；任一旧快照不得判 PASS | 待执行 |

## 5. 前序评审意见处理结果

本节只保留技术结论；详细评论以 PR 记录为准。

| 评审意见 | 处理 | 当前落点 |
|---|---|---|
| 三个异常 allow 必须 fail-closed | 采纳 | G0-01 三态与 T05–T07 |
| 异步与调度首次派发绕过门禁 | 采纳 | 四类生产路径与 T10、T16、T19、T20 |
| 原子边界必须落在 createRun／insertRun | 采纳 | §3.3 |
| experiment_run ID 不等于流程续跑 ID | 采纳 | §3.4 与 T04、T10、T22 |
| allow 不得放行未知故障 | 采纳 | T14、T24 |
| 同步错误 JSON 形状不得导致 200 | 采纳 | T08、T09、T18 |
| 数据库级跨进程协调、事务顺序和失败语义 | 采纳 | §3.3 与 T21、T23、T24 |
| 续跑排除自身必须做归属和上下文校验 | 采纳 | §3.4 与 T22 |
| 预览／执行断言必须落在 NodePlan | 采纳 | §4.3 与 T10、T14 |
| 空 protocolName、合法 ID、不同 recipe 的判别夹具 | 采纳 | §4.2 与 T01–T03 |
| 全仓调用点清查 | 采纳 | §4.1 与 T13 |
| 同一不可变输入快照及版本变化 | 采纳 | §4.3 与 T10、T11、T14 |
| 自动差分测试 | 已吸收到现有验收，不重复新增 | T02、T10、T14 |
| 外部 TTL 锁提示 | 仅作为风险提示；不作为充分方案 | §3.3 第 9 项 |
| 将 UNKNOWN 描述为异常放行 | 不采纳该表述 | UNKNOWN 必须拒绝 |
| 将计划一致外推为真机物理一致 | 不采纳 | 真实设备证据继续独立保持 UNKNOWN |
| 旧评论中的 15／12 项口径 | 已被后续版本更新 | 当前为 G0-01 24 项、G0-07 14 项 |

## 6. 实施、提交与证据要求

### 6.1 G0-01

- 建议分支：`fix/g0-01-workflow-run-gate-fail-closed`。
- 先补能击中旧实现的失败测试，再实现三态、全入口原子准入、续跑归属和 API 映射。
- 交付记录包含实际 HEAD、入口／插入点清单、数据库协调方案、事务顺序、错误映射、迁移与回滚。
- 若增加数据库迁移，必须验证 fresh DB、upgrade DB、drift、active 状态集合、显式 allow、冲突映射和回滚。
- 回滚不得恢复异常 allow；必要时关闭受影响的新 Run 入口，同时保持 fail-closed。

### 6.2 G0-07

- 建议分支：`fix/g0-07-recipe-compiler-capability`。
- 先完成调用点清单与判别性测试，再最小修改两处参数透传。
- 交付记录包含调用点分类、输入快照、NodePlan 截获点、修复前失败证据和修复后结果。
- 回滚必须同时考虑预览与执行两处，不能只回滚一侧制造新分歧。

### 6.3 共同证据门槛

1. 每项验收记录实际命令、环境、退出码和原始日志位置。
2. 修复前失败与修复后通过要形成对照，防止永远为绿的测试。
3. 未执行、环境缺失或 SKIP 均保持 UNKNOWN；不得写成 PASS。
4. 执行当前可用的 `./scripts/ci-gate.sh --fast` 和 workflow 测试映射；脚本或段号变化时以当前仓库为准。
5. Core/Control 实施，QA/Integration 独立验收；两项分别回填证据。
6. 数据库／模拟器证据不能替代真实设备现场证据；历史串扰和物理闭环没有现场材料时继续保持 UNKNOWN。
7. 文档评审、任务卡完成、PR 可合并或 CI 状态本身均不等于风险关闭。

## 7. 请求本轮评审回答的问题

请评审者基于本文件和其实际注明的 commit，至少回答：

1. 是否仍有未覆盖的新建流程 Run 路径，或把实验 Run 与流程 Run 混淆的表述？
2. 数据库级原子准入、显式 allow、续跑身份和提交未知语义是否可实施、可重复验收？
3. 24 项 G0-01 验收是否存在重复、遗漏或无法执行的条目？给出具体 ID 与修改建议。
4. G0-07 全仓调用点清单、判别夹具和同快照三方 NodePlan 比较是否足以检出旧缺陷？
5. 是否有测试只比较外围能力字段、依赖真实设备或无法在旧代码上失败的风险？
6. 是否有把设计通过误写为代码通过、把模拟证据外推为真机证据的表述？

每条意见按以下格式返回：

```text
结论：支持 / 反对 / 需要补充证据
位置：本文件章节或验收 ID
依据：源码、接口、数据对象、实验约束或可复现推理
风险：安全、并发、兼容性、事实边界、测试有效性或交付范围
建议：具体删改内容或替代方案
验收：如何证明建议有效；哪些情况必须保持 UNKNOWN
```

评审开头必须写明：`实际评审 commit：<完整 SHA>`。未注明 commit、仍引用旧版 15／12 项或只评价 Web／ExperimentExecutor 两个预检点的回复，不作为本统一基线的最终评审结论。


## 9. 本轮冻结前补充：数据库事务、并发模式与完整 NodePlan

本节是本统一基线在 2026-11-18 冻结前的补充约束；与前述 24＋14 项验收合并解释，不替换既有验收 ID。

### 9.1 G0-01：原子准入必须绑定同一数据库事务与物理连接

“检查—准入—流程 Run 插入—初始状态写入”的数据库协调必须在同一个数据库事务、同一个物理数据库连接上成立，除非实施者提供可证明等价的事务级协调机制。

实施者必须明确：
1. 锁获取、active 检查、Run 插入、初始状态写入和事务提交的完整顺序。
2. 若使用事务级 advisory lock、guard row、可串行化事务等机制，锁的生命周期必须覆盖实际 Run 插入；不能获取锁后关闭连接，再在另一个连接上插入 Run。
3. MyBatis/Mapper 的 Run 插入必须加入同一事务；不得通过另开连接绕开协调边界。
4. 事务回滚、连接断开、提交结果未知时均不得放行；协调资源必须在失败后正确释放。
5. 测试必须使用独立数据库连接/进程验证跨入口竞争，不能以单 JVM 内存锁或模拟锁作为最终证据。

### 9.2 G0-01：默认互斥与显式 allow 的测试边界

统一解释如下：
- 默认配置：同一 tenant/workflow 最多一个 active Run；G01-T11、T12、T16、T20、T21 的“恰好一个”竞争验收均以默认互斥模式为基准。
- LABOS_WORKFLOW_CONCURRENT_RUN=allow：只能覆盖已知 DENY_ACTIVE，不得覆盖非法参数、状态词表未知、数据库故障或事务协调未知。
- 若数据库约束与显式 allow 存在交互，实施者必须证明两者语义一致；不得出现配置声称允许并发而数据库无条件阻止第二个 Run，或配置仅改变表现却绕过未知态保护。
- T14 单独证明默认与显式 allow 的差异；T24 证明基础设施失败无论配置如何均不放行。

### 9.3 G0-07：完整 NodePlan 与输入快照

“预览与执行一致”必须比较完整 RecipeCompiler.NodePlan，至少包括：
- nodeId、nodeKey、name
- capability、capabilitySource
- 每个 Step 的 seq、method
- sampleCount、fromRecipe

同一不可变输入快照至少固定或记录 Workflow、NodeDef、设备绑定、Recipe 模板、Capability 映射及 protocolCap 的版本或规范化内容 hash。若 Recipe 数据本身没有版本号，可使用规范化输入 hash；不要求为本卡额外建设一套版本管理系统。

G07-T10/T14 必须在任何设备动作前直接比较预览、直接 compile 和执行前展开的 NodePlan。输入版本变化后必须重新计算，不得复用旧缓存/旧计划。

### 9.4 统一源码基线说明

cf772fcc87f65210e66b2f720c6e65c424611cdb 为本轮统一源码复核树；此前任务卡使用的 f134aa66717a02385d65c2f6e6b058d2478409bc 保留为历史文档提交参考。经本轮核对，G0-01/G0-07 关键源码文件在两者间的 blob 未发生变化；后续实施证据仍以实际修复 HEAD 为准。

因此，其他评审不得因为旧任务卡引用 f134aa6 而认为源码事实发生了分歧；若发现其他相关文件发生变化，应以实际 commit 重新核查并在增量记录中注明。

### 9.5 冻结意见

上述四项补充完成后，G0-01/G0-07 的统一设计与验收基线可冻结，后续原则上不再进行泛化架构讨论；除非源码实施暴露新的事实冲突，否则直接进入 G0-01 代码修复、数据库级并发验证，再进入 G0-07 编译器修复。
