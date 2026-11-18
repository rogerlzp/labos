# G0-07 独立修复任务卡：RecipeCompiler 能力映射透传与预览／执行一致

- 任务 ID：G0-07
- 优先级：P0；独立代码缺陷修复，不占 AI Workbench 工期
- 建议分支：`fix/g0-07-recipe-compiler-capability`
- 责任角色：Core/Control 实施；QA/Integration 独立验收
- 历史核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 本次静态复核代码树：`f134aa66717a02385d65c2f6e6b058d2478409bc`（相关源码与 `28a4b36` 一致）
- 当前状态：待实施；构建、测试、CI、真实设备验证均为 UNKNOWN
- 交付原则：独立分支、独立提交、独立 PR；不得与 G0-01 或 AI 功能捆绑

> 本卡是实施与验收设计，不是修复完成记录。目标是最小代码改动加判别性回归，不重写能力解析器。2026-11-18 交叉评审指出的步骤计划断言与夹具要求已纳入本版。

## 1. 问题与证据

### 1.1 批量预览丢失参数

`RecipeCompiler.compileAll(nodes, protocolCap, capabilityByResource)` 明确接收设备能力映射，但循环内调用的是两参数 `compile(n, protocolCap)`；该重载继续以 `Map.of()` 调用三参数 `compile`，导致 `capabilityByResource` 被丢弃。

`WorkflowService.compile(workflowId)` 已把 `RealWorkflowRunner.loadResourceCapability(db)` 传给 `compileAll`，因此问题在 `compileAll` 内部透传，不是预览调用方未提供数据。

### 1.2 执行编译也必须同步修复

本次静态复核定位到 `WorkflowService` 执行路径在逐节点展开时仍调用两参数 `compiler.compile(n, protocolCap)`，尽管同一方法已经持有 `resourceCap`。

因此，**只修 `compileAll` 不合格**：那会让预览开始使用绑定设备能力，而执行展开仍忽略它，形成新的预览／执行分歧。本卡要求两处使用同一 `resourceCap`，但保持两参数重载供无绑定上下文调用，不全局改变其语义。

执行路径里的候选设备和 `GraphRunner.withResourceCapability(resourceCap)` 已使用绑定能力。丢参直接影响喂给 `withStepPlanner` 的方法序列。因此，**只比较 RecoverableNode 或 GraphRunner 的能力字段可能得到假阴性**：这些字段原本就可能一致，旧丢参不会被检出。必须直接截获并比较执行前的 `RecipeCompiler.NodePlan`。

### 1.3 现有能力优先级不得借机重排

`RealWorkflowRunner.resolveCapability(...)` 当前完整相关顺序为：逻辑／伪资源判定 → 节点显式能力 → 有效绑定设备能力 → 协议反查 → `protocol-short` → `heuristic` / unknown。修复只恢复既有参数传递，不改变该优先级。

能力映射的查找 key 是 `NodeDef.equipmentInstanceId`。该 ID 必须位于当前合法资源 ID 范围 `1..99_999_999`；非正数或九位数历史伪 ID 会在绑定查找之前被归为逻辑节点，不能用于 bound 判别夹具。

### 增量状态记录

| 项 | 结论 |
|---|---|
| 旧结论 | 历史基线 FAIL：compileAll 未透传 capabilityByResource |
| 本次静态结论 | compileAll 丢参路径仍可见；执行展开的两参数调用也需纳入同一修复；服务级断言必须落在 NodePlan |
| 实现就绪度 | FAIL（仅针对本卡列出的编译路径） |
| 证据就绪度 | UNKNOWN（本轮未执行任何测试） |
| 本轮是否执行 | 否；只读源码与文档 |

## 2. 修复目标与不变量

1. 三参数 `compileAll` 对每个 task 节点调用三参数 `compile(n, protocolCap, capabilityByResource)`；非 task 节点继续跳过。
2. `WorkflowService` 执行展开调用 `compile(n, protocolCap, resourceCap)`，与预览使用同一份绑定能力映射。
3. 对相同 NodeDef、protocolCap、capabilityByResource 和 recipe 数据，三参数 `compileAll` 中某节点结果必须与直接三参数 `compile` 一致。
4. 对相同工作流版本、节点绑定和模板版本，编译预览与实际执行前截获的 `RecipeCompiler.NodePlan` 具有一致的 nodeId、capability、capabilitySource、method 序列、sampleCount 和 fromRecipe。
5. 服务级一致性断言必须直接针对预览 NodePlan 与执行展开 NodePlan；不得用 RecoverableNode、GraphRunner 能力或最终设备事件代替。
6. 显式节点能力仍优先于绑定设备能力；绑定设备能力优先于协议反查。不得为解决丢参而调整 `resolveCapability` 的优先级。
7. 两参数 `compile` / `compileAll` 继续使用空映射并保持既有回退；null／空映射、资源未登记能力时不得抛新增异常或错误选中绑定能力。
8. 已指定具体方法的节点仍不展开；无模板能力仍单步回退；逻辑节点和既有 recipe 展开行为不变。
9. 本卡只证明 Java 编译预览与执行前步骤计划一致，不宣称预览与真实设备物理行为一致。

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

1. 节点的 `protocolName` 必须为空或空白，避免命中“已指定具体方法”的提前单步返回。
2. 节点显式 capability 为空，`equipmentInstanceId` 使用合法 ID，例如 `101`，并以同一个 `101` 作为 `capabilityByResource` 的 key。
3. 绑定能力与协议反查能力必须不同，例如 `BOUND_CAP` 与 `PROTOCOL_CAP`。
4. `BOUND_CAP` 与 `PROTOCOL_CAP` 必须配置不同的 recipe 方法序列，使 capability、capabilitySource、steps、sampleCount、fromRecipe 能共同识别丢参。
5. 节点名不得命中逻辑节点关键词；ID 不得为伪资源 ID。
6. 另设独立用例覆盖显式具体 `protocolName` 的单步兼容；该用例不得被当作 bound recipe 展开缺陷的判别性证明。
7. 修复前运行关键回归，至少确认 G07-T02 与 G07-T10 能检出旧实现；若旧实现仍为绿，先修测试夹具或截获点。

## 5. 实施步骤

1. **固定修复基线**：记录实际 HEAD、预览与执行展开的调用链；先运行现有 RecipeCompiler 测试并保存结果。
2. **先写判别性回归**：按第 4 节构造空 protocolName、合法 equipmentInstanceId、不同 bound／protocol recipe 的节点，证明旧 `compileAll` 丢失 bound 结果；另构造显式能力用例钉住既有优先级。
3. **最小修复两处透传**：批量预览与执行展开共享 `resourceCap`；不复制一份新的能力解析逻辑。
4. **保持重载兼容**：两参数入口、空／缺映射和 protocol fallback 行为均有回归；非 task 节点仍被批量编译跳过。
5. **做 NodePlan 级一致性测试**：在同一 fixture 上比较预览与执行前展开的完整 NodePlan，测试在任何设备动作前截获计划；不得只比较 GraphRunner／RecoverableNode，也不依赖真实设备运行。
6. **运行回归**：执行本卡测试、`./scripts/ci-gate.sh --fast`，以及 `docs/测试总纲` §4 对 workflow 改动要求的当前可用测试段；记录命令、退出码、日志和 SKIP。
7. **独立评审与提交**：提交信息写现象、根因、两处修法、实际验证和遗留 UNKNOWN；QA/Integration 对照测试 ID 验收。

## 6. 验收清单

“通过”必须有实际测试输出。测试文件存在、只读源码或环境缺失均不计 PASS。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G07-T01 | protocolName 为空、显式能力为空、合法 equipmentInstanceId=101；protocolCap 指向 `PROTOCOL_CAP`，映射 key 101 指向 `BOUND_CAP`；两者 recipe 不同 | 三参数 compile 得到 `BOUND_CAP / bound`，方法序列来自 BOUND_CAP recipe | 待执行 |
| G07-T02 | 将 G07-T01 节点放入三参数 compileAll | 与直接三参数 compile 的完整 NodePlan 一致；旧实现必须失败 | 待执行 |
| G07-T03 | 节点显式 `EXPLICIT_CAP`，同时存在不同绑定能力和协议能力 | 仍为 `EXPLICIT_CAP / explicit`，证明未重排优先级 | 待执行 |
| G07-T04 | 无显式能力、无有效绑定映射，但协议可反查 | 保持 `PROTOCOL_CAP / protocol` | 待执行 |
| G07-T05 | capabilityByResource 为 null／空，或合法 equipmentInstanceId 不在映射中 | 不抛新增异常；按既有 protocol／后续规则回退 | 待执行 |
| G07-T06 | 两参数 compile 与 compileAll | 维持空映射语义，不意外读取绑定能力 | 待执行 |
| G07-T07 | 批量输入同时含 task 与非 task 节点 | 非 task 跳过；每个 task 都收到同一 capabilityByResource | 待执行 |
| G07-T08 | 已指定具体 protocolName 的节点 | 能力解析正确，仍单步且不展开 recipe；不把它当作 G07-T02 的替代证明 | 待执行 |
| G07-T09 | 无模板能力、逻辑节点、伪资源 ID、既有 INCUBATE/READ_PLATE 展开 | 步数、方法序列、sampleCount/fromRecipe 无回归；使用确定性 fixture | 待执行 |
| G07-T10 | 相同 fixture 下服务级预览与执行前展开，直接截获两侧 RecipeCompiler.NodePlan | nodeId、capability、source、method 序列、sampleCount、fromRecipe 全部一致；旧实现必须失败；不得用 RecoverableNode／GraphRunner 代替 | 待执行 |
| G07-T11 | 绑定关系或模板版本变化 | 新版本重新计算并得到各自明确结果；不混用旧缓存／旧计划 | 待执行 |
| G07-T12 | 既有 RecipeCompiler 与 workflow 回归集 | 无回归；新增测试已接入当前测试入口且退出码可信 | 待执行 |

### 发布前检查

- [ ] `compileAll` 与执行展开两处都传递同一能力映射；不是只修一行预览代码。
- [ ] 判别性夹具满足空 protocolName、合法 equipmentInstanceId、不同 bound／protocol recipe；旧代码至少被 G07-T02 与 G07-T10 检出。
- [ ] 服务级一致性直接比较执行前 NodePlan，不用 RecoverableNode／GraphRunner 的既有能力字段制造假阴性。
- [ ] 服务级一致性断言在任何设备动作前完成，不依赖真实设备结果。
- [ ] 未修改 `resolveCapability` 优先级；显式能力仍高于绑定能力，`protocol-short` 与 heuristic 顺序未变。
- [ ] 两参数重载、空映射、无模板、伪资源 ID 和既有 recipe 展开均有兼容回归。
- [ ] 测试使用确定性 fixture；无环境数据时才执行关键断言的反模式已排除。
- [ ] 测试输出包含稳定用例 ID、实际命令和退出码；SKIP 未写成 PASS。
- [ ] `./scripts/ci-gate.sh --fast` 及当前 workflow 映射测试已按实际环境执行并留存结果。
- [ ] 本卡已回填修复 commit、文件／方法签名、测试结果和证据位置。
- [ ] 实现就绪度与证据就绪度分别更新；未宣称真实设备一致性。

## 7. 实施证据回填

| 字段 | 待填写 |
|---|---|
| 修复基线 HEAD | 待填 |
| 修复 commit / PR | 待填 |
| 涉及文件与方法签名 | 待填 |
| 判别性夹具与修复前失败证据 | 待填 |
| 预览／执行 NodePlan 截获点与一致性测试位置 | 待填 |
| 测试命令、退出码和原始日志位置 | 待填 |
| Core/Control 实施人 | 待填 |
| QA/Integration 独立验收人 | 待填 |
| 遗留问题与必须保持 UNKNOWN 的项 | 待填 |

## 8. 回滚与边界

本修复预期为小范围参数透传，可按独立 commit 回滚；但回滚必须同时考虑预览与执行两处，不能只回滚一处而制造新的差异。若服务级测试发现除丢参外还有缓存、版本或计划快照分歧，应停止发布并另开问题，不在本卡扩大重写。

完成本卡不等于真实设备能力登记正确，也不等于设备动作与预览物理一致。真机能力比对、设备原始证据和现场验收仍需独立记录；未执行前保持 UNKNOWN。
