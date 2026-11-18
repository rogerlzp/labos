# G0-07 独立修复任务卡：RecipeCompiler 能力映射透传与预览／执行一致

- 任务 ID：G0-07
- 优先级：P0；独立代码缺陷修复，不占 AI Workbench 工期
- 建议分支：`fix/g0-07-recipe-compiler-capability`
- 责任角色：Core/Control 实施；QA/Integration 独立验收
- 历史核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 统一源码复核树：`cf772fcc87f65210e66b2f720c6e65c424611cdb`
- 历史文档参考：`f134aa66717a02385d65c2f6e6b058d2478409bc`
- 当前状态：待实施；构建、业务测试、真实设备验证均为 UNKNOWN；文档提交 CI gate 因 `LABOS_DB_PASS` 未设置在进入测试前退出
- 交付原则：独立分支、独立提交、独立 PR；不得与 G0-01 或 AI 功能捆绑
- 统一实施与验收基线：[07-G0-01/G0-07 统一基线](07-G0-UNIFIED-IMPLEMENTATION-REVIEW-BASELINE-20261118.md)

> 本卡是实施与验收设计，不是修复完成记录。目标是最小代码改动加判别性回归，不重写能力解析器。2026-11-18 综合评审新增的具体 protocolName 判别用例和旧实现失败日志要求已纳入本版。

## 1. 问题与证据

### 1.1 批量预览丢失参数

`RecipeCompiler.compileAll(nodes, protocolCap, capabilityByResource)` 明确接收设备能力映射，但循环内调用的是两参数 `compile(n, protocolCap)`；该重载继续以 `Map.of()` 调用三参数 `compile`，导致 `capabilityByResource` 被丢弃。

`WorkflowService.compile(workflowId)` 已把 `RealWorkflowRunner.loadResourceCapability(db)` 传给 `compileAll`，因此问题在 `compileAll` 内部透传，不是预览调用方未提供数据。

### 1.2 执行编译也必须同步修复

`WorkflowService` 执行路径在逐节点展开时仍调用两参数 `compiler.compile(n, protocolCap)`，尽管同一方法已经持有 `resourceCap`。

因此，**只修 `compileAll` 不合格**：那会让预览开始使用绑定设备能力，而执行展开仍忽略它，形成新的预览／执行分歧。本卡要求两处使用同一 `resourceCap`，但保持两参数重载供无绑定上下文调用，不全局改变其语义。

执行路径里的候选设备和 `GraphRunner.withResourceCapability(resourceCap)` 已使用绑定能力。丢参直接影响喂给 `withStepPlanner` 的方法序列。因此，**只比较 RecoverableNode 或 GraphRunner 的能力字段可能得到假阴性**：这些字段原本就可能一致，旧丢参不会被检出。必须直接截获并比较执行前的 `RecipeCompiler.NodePlan`。

### 1.3 具体 protocolName 用例也具有判别性

`RecipeCompiler.compile` 先调用 `RealWorkflowRunner.resolveCapability`，再进入具体 protocolName 的单步返回。因此，只要节点 explicit 为空、资源 ID 合法且 bound/protocol 能力不同，旧两参数实现就会在 capability 和 capabilitySource 上与三参数结果不同，即使两侧都保持单步。

G07-T08 据此升级为判别用例，必须断言 `BOUND_CAP / bound + 单步`，并保留旧实现失败日志；它不能替代 G07-T02 的 recipe 展开证明，但也不是无关的兼容性重复项。

### 1.4 现有能力优先级不得借机重排

`RealWorkflowRunner.resolveCapability(...)` 当前完整相关顺序为：逻辑／伪资源判定 → 节点显式能力 → 有效绑定设备能力 → 协议反查 → `protocol-short` → `heuristic` / unknown。修复只恢复既有参数传递，不改变该优先级。

能力映射的查找 key 是 `NodeDef.equipmentInstanceId`。该 ID 必须位于当前合法资源 ID 范围 `1..99_999_999`；非正数或九位数历史伪 ID 会在绑定查找之前被归为逻辑节点，不能用于 bound 判别夹具。

### 增量状态记录

| 项 | 结论 |
|---|---|
| 旧结论 | 历史基线 FAIL：compileAll 未透传 capabilityByResource |
| 本次静态结论 | compileAll 丢参路径仍可见；执行展开的两参数调用也需纳入同一修复；服务级断言必须落在完整 NodePlan |
| 实现就绪度 | FAIL（仅针对本卡列出的编译路径） |
| 证据就绪度 | UNKNOWN（本轮未执行任何业务测试） |
| 本轮是否执行 | 否；只读源码与文档 |

## 2. 修复目标与不变量

1. 三参数 `compileAll` 对每个 task 节点调用三参数 `compile(n, protocolCap, capabilityByResource)`；非 task 节点继续跳过。
2. `WorkflowService` 执行展开调用 `compile(n, protocolCap, resourceCap)`，与预览使用同一份绑定能力映射。
3. 对相同 NodeDef、protocolCap、capabilityByResource 和 recipe 数据，三参数 `compileAll` 中某节点结果必须与直接三参数 `compile` 一致。
4. 同一不可变输入快照至少固定或记录 Workflow、NodeDef、设备绑定、Recipe 模板、Capability 映射及 protocolCap 的版本或规范化内容 hash。若 Recipe 没有版本号，可使用规范化输入 hash，不要求额外建设版本管理系统。
5. 预览与执行前展开的完整 `RecipeCompiler.NodePlan` 至少比较 nodeId、nodeKey、name、capability、capabilitySource、每个 Step 的 seq/method、sampleCount 和 fromRecipe。
6. 服务级一致性断言必须直接针对预览 NodePlan 与执行展开 NodePlan；不得用 RecoverableNode、GraphRunner 能力或最终设备事件代替。
7. 显式节点能力仍优先于绑定设备能力；绑定设备能力优先于协议反查。不得为解决丢参而调整 `resolveCapability` 的优先级。
8. 两参数 `compile` / `compileAll` 继续使用空映射并保持既有回退；null／空映射、资源未登记能力时不得抛新增异常或错误选中绑定能力。
9. 已指定具体方法的节点仍不展开；无模板能力仍单步回退；逻辑节点和既有 recipe 展开行为不变。
10. 输入版本或 hash 变化必须重新计算，不得复用旧缓存或旧计划。
11. 本卡只证明 Java 编译预览与执行前步骤计划一致，不宣称预览与真实设备物理行为一致。

## 3. 范围

### 预计涉及

- `labos-kernel/src/main/java/com/labos/kernel/workflow/RecipeCompiler.java`
- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java`：执行展开的两参数调用与可测试的 NodePlan 截获点
- `labos-kernel/src/test/java/com/labos/kernel/workflow/RecipeCompilerTest.java`，或新增独立的确定性回归测试
- 必要的服务级预览／执行 NodePlan 契约测试与测试登记
- 本任务卡末尾的实施证据与增量结论

如为确定性测试增加 `RecipeCompiler` 夹具构造入口，应限制可见性且不得改变生产 `load(...)` 语义。服务级测试可以在设备动作前截获现有 NodePlan，或做不改变执行语义的小范围计划留存；不得引入另一套编译逻辑。不要依赖开发者本地模板数据、随意 SQL 修改业务状态或真实设备回包来让测试通过。

### 明确不做

- 不重写 `RealWorkflowRunner.resolveCapability`，不调整能力优先级或 ID 空间规则。
- 不改 Workflow DSL、BPMN、设备驱动、命令派发、物料位置或执行状态机。
- 不处理 G0-01、G0-03、G0-08 或任何 AI Agent 功能。
- 不把“编译结果相同”写成“真机动作完全相同”。

## 4. 判别性夹具要求

1. 主 recipe 展开用例的 `protocolName` 必须为空或空白，避免命中“已指定具体方法”的提前单步返回。
2. 节点显式 capability 为空，`equipmentInstanceId` 使用合法 ID，例如 `101`，并以同一个 `101` 作为 `capabilityByResource` 的 key。
3. 绑定能力与协议反查能力必须不同，例如 `BOUND_CAP` 与 `PROTOCOL_CAP`。
4. `BOUND_CAP` 与 `PROTOCOL_CAP` 必须配置不同的 recipe 方法序列，使 capability、capabilitySource、steps、sampleCount、fromRecipe 能共同识别丢参。
5. 节点名不得命中逻辑节点关键词；ID 不得为伪资源 ID。
6. 具体 protocolName 的 G07-T08 同样使用空 explicit、合法资源 ID 和不同 bound/protocol 能力；必须断言 `BOUND_CAP / bound`，同时保持单步不展开。
7. 另设显式 `EXPLICIT_CAP` 用例钉住既有优先级。
8. 修复前运行关键回归，确认 G07-T02、G07-T08 与 G07-T10 都能检出旧实现并保留失败日志；若旧实现仍为绿，先修测试夹具或截获点。

## 5. 实施步骤

1. **固定基线并全仓清查调用点**：记录实际 HEAD；列出所有 `RecipeCompiler.compile` / `compileAll` 调用文件、方法、场景，逐项分类为应传 capability mapping 或保持两参数回退，并登记对应测试或无需额外测试的理由；先运行现有 RecipeCompiler 测试并保存结果。
2. **先写判别性回归**：构造空 protocolName 的 recipe 展开用例和具体 protocolName 的单步用例，两者均证明旧实现丢失 bound 能力；另构造显式能力用例钉住优先级。
3. **最小修复两处透传**：批量预览与执行展开共享 `resourceCap`；不复制一份新的能力解析逻辑。
4. **保持重载兼容**：两参数入口、空／缺映射和 protocol fallback 行为均有回归；非 task 节点仍被批量编译跳过。
5. **做同快照完整 NodePlan 一致性测试**：固定 Workflow、NodeDef、设备绑定、Recipe 模板、Capability 映射和 protocolCap 的版本或规范化 hash，在同一 fixture 上比较直接编译、预览与执行前展开的完整 NodePlan；测试在任何设备动作前截获计划。
6. **运行回归**：执行本卡测试、`./scripts/ci-gate.sh --fast` 及当前 workflow 测试映射；逐项记录实际 HEAD、命令、环境、退出码、原始日志和 SKIP。
7. **独立评审与提交**：提交信息写现象、根因、两处修法、实际验证和遗留 UNKNOWN；QA/Integration 对照测试 ID 验收。

## 6. 验收清单

共 **14 项验收**。“通过”必须有可追溯的实际测试输出。测试文件存在、只读源码或环境缺失均不计 PASS。

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

### 发布前检查

- [ ] 全仓 `compile` / `compileAll` 调用点已列出并分类；不是只检查当前已知两处。
- [ ] 同一不可变快照明确记录 Workflow、NodeDef、绑定、Recipe、Capability 映射和 protocolCap 的版本或规范化 hash；变化后的重新计算有实测。
- [ ] `compileAll` 与执行展开两处都传递同一能力映射；不是只修一行预览代码。
- [ ] 判别性夹具满足合法 equipmentInstanceId 和不同 bound／protocol 能力；旧代码被 G07-T02、T08、T10 检出并保留失败日志。
- [ ] G07-T08 明确断言 `BOUND_CAP / bound + 单步`，未被写成只验证“仍单步”的假阴性用例。
- [ ] 服务级一致性直接比较执行前完整 NodePlan，不用 RecoverableNode／GraphRunner 的既有能力字段制造假阴性。
- [ ] 服务级一致性断言在任何设备动作前完成，不依赖真实设备结果。
- [ ] 未修改 `resolveCapability` 优先级；显式能力仍高于绑定能力，`protocol-short` 与 heuristic 顺序未变。
- [ ] 两参数重载、空映射、无模板、伪资源 ID 和既有 recipe 展开均有兼容回归。
- [ ] 测试使用确定性 fixture；无环境数据时才执行关键断言的反模式已排除。
- [ ] 每个验收 ID 的证据均可追溯实际 HEAD、命令、环境、退出码和原始日志；SKIP 未写成 PASS。
- [ ] `./scripts/ci-gate.sh --fast` 及当前 workflow 映射测试已按实际环境执行并留存结果。
- [ ] 本卡已回填修复 commit、文件／方法签名、测试结果和证据位置。
- [ ] 实现就绪度与证据就绪度分别更新；未宣称真实设备一致性。

## 7. 实施证据回填

| 字段 | 待填写 |
|---|---|
| 修复基线 HEAD | 待填 |
| 修复 commit / PR | 待填 |
| 涉及文件与方法签名 | 待填 |
| 全仓 compile／compileAll 调用点清单与分类 | 待填 |
| 同一不可变输入快照标识、版本与规范化 hash | 待填 |
| 判别性夹具与修复前失败日志 | 待填 |
| 预览／执行完整 NodePlan 截获点与一致性测试位置 | 待填 |
| 每个验收 ID 的命令、环境、退出码和原始日志位置 | 待填 |
| Core/Control 实施人 | 待填 |
| QA/Integration 独立验收人 | 待填 |
| 遗留问题与必须保持 UNKNOWN 的项 | 待填 |

## 8. 回滚与边界

本修复预期为小范围参数透传，可按独立 commit 回滚；但回滚必须同时考虑预览与执行两处，不能只回滚一处而制造新的差异。若服务级测试发现除丢参外还有缓存、版本或计划快照分歧，应停止发布并另开问题，不在本卡扩大重写。

完成本卡不等于真实设备能力登记正确，也不等于设备动作与预览物理一致。真机能力比对、设备原始证据和现场验收仍需独立记录；未执行前保持 UNKNOWN。

## 9. 当前收敛状态

本卡已与统一基线同步纳入完整 NodePlan、protocolCap 输入快照、具体 protocolName 判别用例和证据可追溯要求。后续如出现新的源码事实或反例，必须同步修订统一基线与本卡；不得将任务卡完成写成代码修复或测试通过。
