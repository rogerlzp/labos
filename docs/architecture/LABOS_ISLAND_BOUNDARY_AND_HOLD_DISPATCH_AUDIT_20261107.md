# LabOS Island 边界与 Hold 调度闸门专项审计

> **执行基线说明（2026-11-07）**
> 本文档由评审分支 `review/labos-industrial-architecture` 归档到 `main` 执行基线，已按用户裁决处理：
> ① **流程（Workflow）不允许跨 Workcell；实验（Experiment）允许跨 Workcell**；
> ② `main` 的暂停修复 `3debf78` 为代码基线；③ 所有工程实施在 `main` 上进行，评审分支仅作设计归档；
> ④ 按 `ADR-DOC-001` 去除商业公司/产品名称，外部实践改以「产品形态 A/B/C」表述。
>
> 本文**保留原始思考与源码证据**，但结论以 `ADR_INDEX.md` 与 `REMEDIATION_STATUS.md` 为唯一准绳；
> 被新裁决取代的条目见文末「裁决影响（2026-11-07）」，不得按被取代条目实施。

- 日期：2026-11-07
- 分支：`review/labos-industrial-architecture`
- 审计类型：源码静态审计；只修改本审计文档，不修改业务代码
- 范围：Workflow Designer、设备池 API、Workflow 保存/节点绑定、IslandFlow 执行入口、ExperimentCanvas、RuntimeHold、ExperimentScheduler
- 证据口径：CONFIRMED = 当前源码直接证明；PARTIAL = 部分实现；UNKNOWN = 证据不足
- 本轮未连接生产数据库、未运行前端测试/Maven/HIL、未控制现场设备

## 1. 结论摘要

| 项目 | 状态 | 结论 |
|---|---|---|
| Designer 设备面板按 Island 过滤 | PARTIAL | 选择 islandId 时设备池按岛过滤；允许清空选择，null 会返回全量设备池 |
| Workflow 固定 Island 归属 | PARTIAL / P0 | createWorkflow 将 island_id 写入 workflow.attr，但设计器没有把它作为不可变边界；页面岛屿选择只是 URL/UI 状态 |
| 拖拽跨 Island 阻断 | NOT_FOUND / P0 | onDropMethod 没有比较当前 workflow Island、上一个节点 Island 和新节点 Island；可在切换设备池后将不同 Island 设备拖入同一张 BPMN |
| 服务端保存校验 | NOT_FOUND / P0 | saveGraph、updateNodeBinding 当前可见实现保存 resource_id，但没有校验设备 resource_node.parent_id 是否等于 workflow 的 island_id |
| 设备池 API | CONFIRMED | /api/devices/pool 的 islandId 参数可选；不传时返回所有 Island 的设备 |
| 无机械臂时的转运约束 | P1 | Designer 自动插 Transfer 仅在找到 arm 时触发；找不到 arm 时仍直接连接前后设备，不阻止保存 |
| ExperimentCanvas 工作流组合边界 | PARTIAL | 画布节点是 workflow 引用，不直接选择设备；本轮未完成实验 island 与 workflow island 的服务端一致性检查 |
| Hold 查询语义 | CONFIRMED / P0 | RuntimeHold.active() SQL 异常返回空列表，anyActive() 将 UNKNOWN 当 INACTIVE |
| Hold 调度范围 | CONFIRMED / P0 | dispatch() 使用全局 anyActive；一个 Island Hold 会挡住所有 Island 的新派发 |
| Hold 异常处理 | CONFIRMED / P0 | dispatch() catch 后继续派发，明确 fail-open |
| 自动续批 | CONFIRMED / P1 | autoContinueHeldBatches() 同样先用全局 anyActive，某岛 Hold 会阻止其他岛续批 |

**修正后的总原则：Island 边界应在编辑器交互时就限制，并在服务端保存/编译时强制校验；运行时再做独立的最后一道验证。Hold 应按受影响 Island/Experiment 精确阻断，只有明确的 Site 级或跨 Island SafetyDomain 事件才扩大范围。**

---

## 2. Workflow Designer：设备岛屿边界目前只是 UI 过滤，不是强约束

### 2.1 设备池选择是可选过滤 — CONFIRMED

文件：`webapp/src/pages/Designer.tsx`

- 747–751：`islandId` 从 URL query 初始化；缺少/无效时为 null。
- 758–763：设备池请求为 `/api/devices/pool?islandId=...`；islandId 为空时不带参数。
- 1342–1355：左侧下拉框允许 `allowClear`，可清除 Island 选择。
- 379–388：NodeSettings 复用同一设备池查询；islandId 为空时同样请求全量设备。

文件：`labos-kernel/src/main/java/com/labos/kernel/api/DeviceApi.java`

- 211–216：`/api/devices/pool` 将可选 islandId 传给 QueryService；不传即传 null。

文件：`https://github.com/rogerlzp/labos/blob/7524a94d853076a601bb87ad6a0e8bc45c31d8d2/labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java`

- 3184–3219：`devicePoolJson(null)` 明确返回全量设备；只有 islandId 非空才加 `d.parent_id = islandId` 过滤。
- 3198–3201：返回的 island_id/island_code 是通过设备直接 parent 为 island 查询得到。

因此，“只显示一个岛的设备”目前依赖用户保持下拉框选择；没有绑定 Island 的旧流程或清空下拉框时，设备池会扩展到全场。

### 2.2 同一张 Workflow 可先后拖入不同 Island 的设备 — CONFIRMED / P0

文件：`webapp/src/pages/Designer.tsx`

- 74–95：拖拽 payload `DroppedMethod` 携带 resourceId 和 islandId。
- 164–175：拖拽时把当前设备的 islandId 放入 payload。
- 1127–1150：`onDropMethod` 读取 payload 后只检查 method/resourceId 是否存在，没有验证 Island。
- 1167–1174：自动寻找机械臂时只按当前新拖入节点的 p.islandId 过滤；没有比较前一个节点的 Island。
- 97–106：`LastDropped` 不保存 islandId，因此无法在下一次拖入时验证前后设备是否同岛。
- 1171–1174：当 prev 和新节点都是普通设备且 resourceId 不同，只在找到 arm 时才 needTransfer。

可复现操作（无需现场设备）：
1. 在 Designer 选择 Island A，拖入设备 A1。
2. 把左侧 Island 下拉切换为 Island B。
3. 拖入设备 B1。
4. 当前代码没有在 drop 时拒绝，前后节点可落在同一 BPMN。
5. 由于 LastDropped 未记录前节点 Island，自动插入的 Transfer 机械臂可能来自 B，而 source/target 分别属于 A/B。

另一个边界：
- 清空 Island 下拉框后，设备池包含全场设备；
- `!p.islandId` 会使机械臂筛选条件放宽为任意 Island 的 TRANSFER 设备；
- 同一工作流可混入任意 Island 设备。

### 2.3 没有可用机械臂时，流程仍会直接连接设备 — CONFIRMED / P1

Designer 注释写明“板子换设备必须由机械臂搬运”，但代码行为不是强制：

- `needTransfer` 的表达式以 `&& arm` 结束；
- 若没有找到机械臂，`needTransfer=false`；
- 随后进入 `else if (prev && prevEl)`，直接执行 `modeling.connect(prevEl, shape)`。

这会产生“图上有顺序边、没有真实 Transfer 节点”的流程。编辑器应在不能证明有可达机械臂时阻止连接/保存，而不是退化为直接相连。

### 2.4 Workflow 的 Island 归属没有被编辑器强制 — PARTIAL / P0

文件：`labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java`

- 2647–2690：`createWorkflow(..., islandId)` 创建流程后，把 island_id 写入 `workflow.attr`。
- 注释明确称该字段用于“设计器据此默认只显示这个岛的设备”，旧流程没有该键时回落全部 Island。

文件：`webapp/src/pages/Designer.tsx`

- 742–751：页面从 URL query 读取 islandId；没有在此处读取 workflow.attr 中的 island_id。
- 1342–1351：用户可以改变或清空 Island 选择。
- 现有证据更符合“设备面板过滤偏好”，而不是服务端不可变的 workflow 归属。

需要补查 workflow detail/compile API 是否在其他组件中返回并使用 attr.island_id；本轮没有找到它被用作编辑器强制边界的证据。

### 2.5 服务端保存与节点绑定没有岛屿一致性校验 — NOT_FOUND（已检查路径）

文件：`labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java`

- 2724–2743：saveGraph 解析 BPMN、检查空图/展开节点。
- 2748 起加载方法能力。
- 2787–2868：按 BPMN 节点写入/更新 workflow_node.resource_id。
- 当前检查到的保存逻辑没有把 workflow 的 Island 与每个节点 resource_id 对照，也没有校验资源是否为该 Island 的直接子设备。

文件：`labos-kernel/src/main/java/com/labos/kernel/api/WorkflowApi.java`

- 284–306：节点绑定 PUT/POST 将 resourceId 交给 QueryService.updateNodeBinding。

文件：`https://github.com/rogerlzp/labos/blob/7524a94d853076a601bb87ad6a0e8bc45c31d8d2/labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java`

- 2636–2653：updateNodeBinding 接受 workflowId/nodeKey/resourceId。
- 2668–2682：直接更新 workflow_node.resource_id。
- 当前可见代码没有先校验资源归属。

结论：只修前端拖拽不够。任何直接 API、旧前端、脚本、恢复历史图或其他执行入口都可能绕过 UI。服务端 saveGraph 和 updateNodeBinding 必须调用同一套 IslandBoundaryValidator。

---

## 3. Island 边界建议模型

### 3.1 固定的 Workflow Island

建议把 `workflow.island_id` 变成明确领域字段或等价的强约束元数据，不再只是可选 attr 展示偏好。

- 一个可执行物理 Workflow 只能归属一个 Island。
- 物理设备任务必须引用该 Island 内的设备。
- 逻辑节点（变量/表达式/网关）不占用 Island 设备，但不能改变 Workflow 的 Island。
- 旧 Workflow 的 island_id 为 null 时，标记为 `UNASSIGNED`；必须由用户明确分配后才能发布/执行，不得把 null 解释为“全场设备池”。
- 不允许在编辑器中把已绑定 Island 的 Workflow 改成 null 或切换到另一个 Island；如确需迁移，必须走显式迁移流程，重新校验全部设备节点、机械臂可达性和引用该 Workflow 的实验。

### 3.2 编辑器交互规则

1. 打开已归属 Island 的 Workflow 时，从服务端读取并锁定该 Island；隐藏“全部 Island”选项。
2. 左侧设备池只加载该 Island 的设备；逻辑节点独立显示。
3. 拖拽 payload 中的 islandId 只作 UI 提示，不能作为可信事实；drop 时重新与当前 workflow islandId 比较。
4. LastDropped 必须保存 islandId；前后两个物理设备节点 islandId 不一致，立即拒绝 drop/连接并提示两个 Island。
5. 切换 Island 不能只刷新左侧池：已存在图中的设备绑定必须整体校验；不一致时阻止保存。
6. 若需要机械臂 Transfer，必须找到属于该 Island、且配置证明能访问 source/target 的机械臂；找不到或可达性 UNKNOWN，拒绝连接/保存。
7. 画布应显示 Island 固定标签，防止用户误认为可以跨岛组合。

### 3.3 服务端统一校验（必须）

新增一个纯领域服务，例如 `IslandBoundaryValidator`，供以下入口共同使用：

- Workflow create / update / saveGraph
- NodeBinding PUT/POST
- Workflow restore history
- Experiment create / process replacement / experiment graph save
- Choreography build
- ExperimentFlowRunner / 其他直接执行入口
- Preflight / compile / runtime dispatch

校验事实：
- Workflow 的 Island 存在且状态有效；
- 每个物理节点的 resourceId 存在、属于当前 tenant、kind=device、parent Island 与 Workflow Island 一致；
- 方法与设备 capability 匹配；
- Transfer robot 属于同一 Island；
- robot 对 source/target device 的可达关系已配置且有效；
- 设备/机械臂/点位配置版本在编译快照中固定。

任一事实 UNKNOWN 或不一致：拒绝保存/发布/执行；不得降级到全局设备池。

### 3.4 Experiment 与 Workflow 组合

ExperimentCanvas 的节点类型是 start/end/workflow，工作流组合不是设备拖拽本身。仍须校验：
- experiment.island_id 与每个 workflow.island_id 一致；
- experiment 内不能引用不同 Island 的物理 Workflow；
- Workflow Island 未分配时，不能发布为可执行 Experiment；
- 旧 workflow 的归属迁移需要审计和影响分析。

本轮已看到 createExperiment 接受 islandId 和 workflowIds，并通过 replaceExperimentProcesses 写入流程关联；尚未完整审计 replaceExperimentProcesses 的全部调用方及其校验，因此“跨岛 workflow 是否能通过所有实验 API 入口写入”标为 PARTIAL，必须在实施前补查。

---

## 4. IslandFlow 画布不能替代 Workflow 的 Island 边界

文件：`webapp/src/pages/IslandFlow.tsx`

- 设备节点来自当前选中 Island 的 topology.devices；这是一个 Island 视图。
- 画布保存 nodes 的位置和 edges 的 source/target；不能由此证明边是机械臂可达路径、碰撞检查结果或 Transfer 命令计划。
- run() 按图路径生成 steps，并调用 `POST /api/choreography`，随后调用 workflow run。

文件：`labos-kernel/src/main/java/com/labos/kernel/api/WorkflowApi.java`

- 106–129：`/api/choreography` 接收 steps 中的 resourceId/method，调用 WorkflowBuilder.build。
- 请求体当前没有 IslandId；入口没有展示资源归属验证。

这条 IslandFlow → choreography → workflow run 路径也必须纳入服务端 IslandBoundaryValidator；不能因为正常 UI 从一个 Island 的 topology 生成 steps，就假设 API 输入永远可信。

---

## 5. Hold：全局闸门造成跨 Island 阻断，查询错误又会 fail-open

### 5.1 当前 Hold 模型已有 scope 粒度 — CONFIRMED

文件：`labos-kernel/src/main/java/com/labos/kernel/workflow/RuntimeHold.java`

- Scope 已有 SITE / ISLAND / EXPERIMENT。
- 纯函数 `covering(active, experimentId, islandId)` 能判断某个实验是否被对应 scope 覆盖。
- 但 `active()` 查询 SQL 异常会 catch 后返回部分/空 List（274–295）。
- `coveringFor()` 通过 active() 计算，异常时无法区分“没有 Hold”和“查询失败”。
- `anyActive()` 只检查 list 是否为空（303–305）。

因此目前模型有作用域枚举，但读 API 不是三态，不能安全用于派发。

### 5.2 dispatch 全局闸门 — CONFIRMED / P0

文件：`labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java`

- Queued 记录已经带有 experimentId 和 islandId（67–69）。
- 派发前 390–398：调用全局 `RuntimeHold.anyActive()`。
- 任意 Island/Experiment Hold 都使整个 dispatch() return，队列中其他 Island 无法启动。
- SQL 读取失败后，RuntimeHold.active() 返回空列表，anyActive() 为 false；外层 catch 也忽略异常继续派发。

因此当前同时存在：
- **过度阻断**：Island A Hold 阻止 Island B；
- **安全漏放**：Hold 状态查不清时仍可能派发。

### 5.3 自动续批也使用全局闸门 — CONFIRMED / P1

文件：`ExperimentScheduler.java`

- 339–377：autoContinueHeldBatches() 一开始调用全局 anyActive()；只要任意 Hold 生效，就不扫描/续跑任何 Island 的批次。
- 后续才逐条调用 coveringFor(experimentId,islandId)，但前面的全局闸门已经把范围扩大到全场。
- 该路径同样需要改为逐个候选的三态覆盖判定。

### 5.4 正确的 Hold 决策口径

对每个 Queued `q` 独立计算：

`HoldDecision = evaluate(q.experimentId, q.islandId, affectedResources, safetyDomains)`

结果必须是三态：
- `CLEAR`：确认没有覆盖该实验/Island 的 Hold，且依赖与安全域检查通过；
- `HELD`：被 SITE、该 Island、该 Experiment 或明确覆盖它的 SafetyDomain Hold 命中；
- `UNKNOWN`：数据库查询失败、scope 数据不完整、依赖闭包/安全域状态未知。

调度行为：
- CLEAR：可以继续竞争该 Island/设备资源；
- HELD：只跳过该候选，不影响不相关 Island；
- UNKNOWN：不派发该候选，记录可观测原因；不能把 UNKNOWN 当 CLEAR。
- SITE Hold：明确公共基础设施/全场安全事件才使用，阻断所有 Island。
- ISLAND Hold：默认设备故障、板位未知、机械臂/岛内工艺故障，只阻断该 Island。
- EXPERIMENT Hold：只阻断对应 Experiment。
- 跨 Island 公共设施故障：使用显式 SafetyDomain/affected-resource 范围，不把普通 Island Hold 偷偷升级成 SITE Hold。

**默认隔离原则：一个 Island 的普通故障不应影响其他 Island。只有经过明确配置/证据证明存在共享资源或跨岛安全影响时，才传播到受影响的其他 Island。** 如果影响关系本身查询失败，UNKNOWN 的阻断范围应落在被调查的依赖闭包/安全域；不得借此恢复成“全场一律停”，也不得让可能受影响的目标继续。

---

## 6. 整改优先级与验收

### P0-A：Workflow Island 固定归属 + 编辑期阻断

1. 将 workflow island_id 作为强约束；旧流程 null 明确迁移/人工分配。
2. 打开 Workflow 时从后端加载归属，不依赖 URL query。
3. 设备池不能清空成全量；已绑定流程隐藏全岛选项。
4. drop 时比较当前 workflow Island、拖拽设备 Island、前一设备 Island。
5. 跨 Island drop 必须不创建 BPMN shape、不自动连线、不保存。
6. 无可达机械臂时，不允许把两台物理设备直接连成一条板流。
7. 后端 saveGraph、updateNodeBinding、restore、choreography build 必须使用同一 validator。
8. preflight 报告具体节点、resourceId、实际 Island、预期 Island；错误为 blocking。

### P0-B：运行时 Island 防线

1. ExperimentExecutor 从 experiment 读取 islandId，并将不可变执行上下文传给每个 workflow。
2. WorkflowService 候选池严格按该 Island 过滤；空池返回 WAITING_RESOURCE/CONFIGURATION_ERROR，不得 fallback 全局。
3. 每个绑定 resourceId 在运行前再次校验 tenant/kind/parent island。
4. ExperimentFlowRunner、IslandFlow choreography、workflow direct-run 都不得绕过边界。
5. 配置版本/Island 归属发生变化时，已编译 ExecutionPlan 失效或进入人工重新确认。

### P0-C：Hold 三态与逐候选调度

1. RuntimeHold 查询返回 CLEAR/HELD/UNKNOWN，不能以空 List 表示 SQL 错误。
2. dispatch 不再在全队列前调用 anyActive；改为逐个 Queued 按 experimentId/islandId/依赖闭包判定。
3. HELD 只跳过被覆盖候选；其他 Island 继续调度。
4. UNKNOWN 阻断对应候选并显示“安全状态不可确认”；不得 fail-open。
5. SITE Hold 只由明确的全场安全事件创建；普通 Island Hold 不升级成 SITE。
6. autoContinueHeldBatches 改为逐候选评估，不能被其他 Island 的 Hold 全局挡住。
7. 在锁竞争/执行提交前再次验证 Hold 版本或快照，避免检查后新 Hold 插入造成竞态。

### 最小回归测试矩阵

| 场景 | 期望 |
|---|---|
| 同一 Workflow 拖入 A1 后切换 B 再拖入 B1 | drop 被拒绝；图不变 |
| 已绑定 A 的 Workflow 清空 Island 过滤 | UI 不允许；API 仍拒绝 B 设备 |
| 直接 PUT node binding 指向 B 设备 | 400/409，数据库不变 |
| saveGraph XML 中混入 B 设备 resourceId | 事务回滚，旧图保持 |
| workflow island_id=null 且包含物理设备节点 | 发布/执行阻断，要求显式分配 |
| 同 Island 两设备无可达机械臂 | 不允许自动连线/保存 |
| 两个 Island A/B 都有队列，A 有 ISLAND Hold | A 不派发，B 正常派发 |
| A 有 Experiment Hold，B 有队列 | 只阻断 A 对应 Experiment |
| SITE Hold 生效 | A/B 都不派发 |
| Hold SQL 查询抛异常 | 受影响候选 UNKNOWN，不派发；B 不因 A 的普通 Island Hold 被误阻断 |
| A/B 都有批次候选，A 有 Hold | B 的无 Hold 批次可以续队 |
| SafetyDomain 明确跨 A/B | 两岛都按该安全域策略阻断 |
| 依赖关系查询 UNKNOWN | 不能对潜在受影响的任务给出 CLEAR |

---

## 7. 本轮未做与后续调查

- 未修改任何业务代码。
- 未修改数据库 schema/migration。
- 未运行 TypeScript、Maven/JUnit 或 HIL。
- 未查询生产库中 workflow.attr.island_id 的实际分布及旧流程数量。
- 未完整审计 ExperimentCanvas 的 workflow 选择/保存 API 是否在所有入口验证 experiment Island 与 workflow Island。
- 未完整审计 Workflow restore、compile、direct-run、ExperimentFlowRunner 的全部 Island 校验。
- 未取得完整 DB migration/tree 清单；robot_point、workflow.attr、island_flow 的真实索引/外键/唯一约束仍需专门调查。

下一步应先做 P0 的测试设计和边界服务接口设计，再由用户确认后进入小 PR 实施。不能先只改前端下拉框，因为这无法约束 API、历史图、脚本和旁路执行路径。


## 8. 补充核查：Experiment 组合阶段也没有 Island 一致性校验 — CONFIRMED / P0

这不是只发生在 Designer 的设备拖拽阶段。实验编排还存在第二个边界：

文件：`https://github.com/rogerlzp/labos/blob/7524a94d853076a601bb87ad6a0e8bc45c31d8d2/labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java`

- 1358–1373：`replaceExperimentProcesses(experimentId, workflowIds)` 先调用 `validateExperimentWorkflowIds`，再替换流程。
- 1375–1413：`validateExperimentWorkflowIds` 只检查 workflowIds 数量、ID 合法性、tenant 下是否存在；查询只取 `workflow.id`，没有读取 `workflow.attr.island_id`，也没有读取 experiment.island_id。
- 1415–1458：真正写入 `experiment_process` 时只写 experiment_id/workflow_id/seq/run_mode，没有 Island 一致性比较。
- 2334–2362：`saveExperimentGraph` 编译 ExperimentCanvas 后调用同一存在性校验和流程替换；没有 Island 比较。
- 2408–2450：`createExperiment(name, code, islandId, ..., workflowIds)` 同时接收实验 islandId 和 workflowIds，并在同一事务中写 experiment 与流程关联；本段路径没有看到先检查 workflow Island 是否一致。

文件：`labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java`

- 689–704：`islandOf(experimentId)` 能读取 experiment.island_id。
- 144–156：运行时拿到 islandId 后用于 RuntimeHold 覆盖判断。
- 774–779：真正调用 WorkflowService 时只使用 `withVars(runVars).execute(workflowId)`，没有把 islandId 传给 WorkflowService。

因此需把“Workflow 内设备属于同一个 Island”和“Experiment 只引用所属 Island 的 Workflow”作为两个不同校验点：
1. Workflow 内每个物理设备节点必须属于 workflow.island_id；
2. Experiment 内每个 Workflow 必须属于 experiment.island_id；
3. 两层任意一层未分配/不一致，都必须在保存/发布时阻断，运行时再次验证。

本发现把原报告中“Experiment 与 Workflow 组合边界 PARTIAL”细化为：**当前已检查的流程关联写入路径只有存在性/租户检查，没有 Island 一致性检查；可见实现为 CONFIRMED 缺口。**

---

## 裁决影响（2026-11-07，以 main 执行基线为准）

### SUPERSEDED（整节结论撤销）

- **§8「Experiment 组合阶段也没有 Island 一致性校验 — CONFIRMED / P0」整节作废**。它把「缺少 Experiment/Workflow Island 相等校验」判为 P0 缺口，与新裁决相反。§8 的**源码事实本身仍成立**（`validateExperimentWorkflowIds` 只查存在性/租户、`ExperimentExecutor.runStep` 不传 islandId），但结论不再是「应补相等校验」，而是「应补跨 Workcell 编排的显式绑定与 Handoff 校验」。
- §3.4「Experiment 与 Workflow 组合」中要求 Island 一致的部分作废。

### 保留

- §2 Designer 跨岛拖拽问题、§3.1~3.3 Workflow 单 Workcell 边界、§4 IslandFlow、§5/P0-C Hold 三态与逐候选调度、§6 P0-A / P0-C 与回归矩阵中 Workflow 侧和 Hold 侧用例。
- P0-B 第 1 条「ExperimentExecutor 从 experiment 读取 islandId」**需改写**：执行上下文的 Workcell 应取自当前 Workflow 的权威归属。
