# LabOS 架构决策记录（ADR）统一注册表

日期：2026-11-07
状态：**唯一真源**。所有架构文档的结论、编号与状态以本表为准；其它文档只保留思考过程与源码证据，不得各自维护一套结论或状态。

维护规则：

1. 新增决策必须先在本表取号并登记，再写文档；禁止在正文里新增未注册的 ADR 编号。
2. 状态取值：`PROPOSED` / `ACCEPT` / `SUPERSEDED` / `REJECTED`。已 `ACCEPT` 的决策不直接改写，用新 ADR 标记 `SUPERSEDES` 并列入 §3。
3. 每个实施 PR 的模板必填：`ADR 编号`、`REMEDIATION_STATUS.md 中对应行更新`、`CI/e2e/HIL 证据`。
4. 命名前缀：`ADR-PAUSE-*`（暂停/干预状态）、`ADR-FAULT-SCOPE-*`（故障隔离）、`ADR-ISLAND-*`（Workcell 边界）、`ADR-PROCESS-*`（工程与基线纪律）、`ADR-DOC-*`（文档规范）。

---

## 1. 已接受决策

| 编号 | 标题 | 状态 | 决策摘要 | 来源 | 主要影响面 |
|---|---|---|---|---|---|
| ADR-PAUSE-001 | 请求态与完成态必须分离 | ACCEPT | Pause/Stop/Abort 先记请求状态，设备/节点事实收敛后才记最终状态 | 外部实践与 ADR §10 | RunControl、ExperimentApi、UI |
| ADR-PAUSE-002 | 采用四个正交事实模型 | ACCEPT | Run Control State / Hold Ledger / Command Execution State / Alarm-Intervention State 分开存储，UI 由聚合器投影 | 同上 | 状态模型、DB schema |
| ADR-PAUSE-003 | WAITING_INTERVENTION 是执行门禁/聚合态 | ACCEPT | 人工待办用 InterventionRecord，阻断原因用 Hold Ledger，不作为唯一 Hold 记录 | 同上 | 状态枚举、UI |
| ADR-PAUSE-004 | 暂停不等于设备取消或急停 | ACCEPT | 设备 Pause / Controlled Stop / Abort / Emergency Stop 能力分别声明，硬件安全回路独立 | 同上 | DeviceCapability、Driver |
| ADR-PAUSE-005 | Hold 多原因并存，按 hold_id 精确清除 | ACCEPT | 不使用 scope-wide clear；需幂等键去重 | 同上 | `uk_runtime_hold_active` 唯一索引、RuntimeHold |
| ADR-PAUSE-006 | 设备命令必须可查询或有人工对账路径 | ACCEPT WITH DEVICE-SPECIFIC LIMITS | 优先稳定 execution ID + status query；不支持查询者采用序列化、保守隔离、人工 reconciliation | 同上 | Command Ledger、Outbox |
| ADR-PAUSE-007 | 先收敛已有暂停实现，不新建第三套子系统 | ACCEPT | 以 RuntimeHold / RunControl / PauseManager / ManualWindow 为基础，建 PauseCoordinator 与适配层逐步迁移 | 同上 | PauseCoordinator |
| ADR-FAULT-SCOPE-001 | 设备故障默认隔离整个 Workcell | ACCEPT AS DEFAULT POLICY | Workcell 进入 FAULT_HOLD / ISOLATED；Workcell 外任务只有满足 Continue Predicate 才继续；Safety Domain 可覆盖多个 Workcell | 外部实践与 ADR §14 | Hold 作用域、调度、传播快照 |
| **ADR-ISLAND-001** | **流程（Workflow）不允许跨 Workcell** | **ACCEPT** | 见 §2.1 | 用户裁决 2026-11-07 | `workflow.island_id`、validator、Designer、编译/运行 |
| **ADR-ISLAND-002** | **实验（Experiment）允许跨 Workcell** | **ACCEPT** | 见 §2.2 | 用户裁决 2026-11-07 | Experiment 数据模型、Scheduler 锁、Hold 覆盖、Handoff |
| **ADR-ISLAND-003** | **禁止用单一 `island_id` 相等判断代替领域边界** | **ACCEPT** | 见 §2.3 | 用户原则 2026-11-07 | BoundaryValidator 接口、Hold/SafetyDomain |
| **ADR-PROCESS-001** | **`main` 为唯一执行基线** | **ACCEPT** | 见 §2.4 | 用户裁决 2026-11-07 | 分支策略、git 流程、CI 门禁 |
| **ADR-DOC-001** | **架构文档不得出现商业公司/产品名称** | **ACCEPT** | 见 §2.5 | 用户裁决 2026-11-07 | 全部架构文档、PR review checklist |
| **ADR-ISLAND-004** | **跨 Workcell Handoff 由人工完成，配置期定义 + 运行时人工确认** | **ACCEPT** | 见 §2.6 | 用户裁决 2026-11-07（Q-01） | Handoff 配置结构、ManualWindow、执行门禁 |
| **ADR-ISLAND-005** | **`island_id` 仅为内部区分键，界面只显示岛名不显示 ID** | **ACCEPT** | 见 §2.7 | 用户裁决 2026-11-07（Q-02） | `experiment.island_id`、全部前端列表/看板 |
| **ADR-PROCESS-002** | **两套暂停 e2e 拆段：4.18 保留，新增 4.19** | **ACCEPT** | 见 §2.8 | 用户裁决 2026-11-07（Q-07） | `ci-gate.sh`、两个 e2e 脚本 |
| **ADR-LAYER-001** | **分层落地采用「最小骨架 + 收敛已有实现」，分三批** | **ACCEPT** | 见 §2.9 | 基于 main 源码盘点 2026-11-07 | 四正交事实、六类领域对象、状态列 |
| **ADR-LOCK-001** | **分布式资源锁用 Redis + PG 联合，按资源粒度，不按 islandId** | **ACCEPT** | 见 §2.10 | 用户裁决 2026-11-07（Q-03） | ExperimentScheduler、设备/板位/机械臂锁、RedisClient |
| **ADR-FAULT-SCOPE-002** | **同一实验内任一 Workcell 故障 ⇒ 整个实验停** | **ACCEPT** | 见 §2.11 | 用户裁决 2026-11-07（Q-04） | 故障传播、Hold 覆盖、逐候选调度 |
| **ADR-STATE-001** | **状态机多层维护治理：事实/投影分离 + PauseCoordinator + 验收口径切换** | **ACCEPT** | 见 §2.12 | 源码与文档盘点 2026-11-07 | 两套 run 模型、e2e 4.17 判据 |
| **ADR-PAUSE-008** | **控制状态（`control_state`）与生命周期（`status`）分离** | **ACCEPT** | 见 §2.13 | 用户裁决 2026-11-07（L-02，取更完善方案） | `experiment_run` schema、RunControl、StatusVocabulary、前端词表 |
| **ADR-PAUSE-009** | **V1 幂等键采用独立 `pause_request` 表** | **ACCEPT** | 见 §2.14 | 用户裁决 2026-11-07（Q-1，取更完善方案） | PauseCoordinator、schema 新增表、P-06 |
| **ADR-PAUSE-010** | **Hold 读取失败的 `UNKNOWN` 必须运维可见** | **ACCEPT** | 见 §2.15 | 用户裁决 2026-11-07（Q-2，取更完善方案） | `GET /api/holds`、RuntimeHold |
| **ADR-PROCESS-003** | **L-02 先于 S-02/P-06（PauseCoordinator）合并** | **ACCEPT** | 见 §2.16 | 用户裁决 2026-11-07（Q-3） | 合并顺序、REMEDIATION Step 7 |

---

## 2. 本次新增决策全文

### 2.1 ADR-ISLAND-001：流程（Workflow）不允许跨 Workcell

**Decision：ACCEPT。** 用户裁决 2026-11-07。

- 一个物理 Workflow 必须有且仅有一个**权威 Workcell**（当前 `island` 的物理含义：一个房间/相对封闭区域内的设备 + 一台或多台机械臂）。
- `UNASSIGNED` 不是通配；含物理任务的 Workflow 未分配 Workcell 时不得发布、编译或执行。
- 每个物理任务的资源必须在同租户、为设备类资源，且属于该 Workflow 的 Workcell；归属不能由 URL 参数、编辑器状态、graph XML 或客户端 payload 静默改变。
- 所有写入路径（`createWorkflow` / `saveGraph` / `updateNodeBinding` / `restore` / choreography build / direct run / compile）使用同一服务端校验器；前端校验只是易用性，不是权威。
- **BPMN 顺序边不隐含跨 Workcell 板流**；同一 Workcell 内两台设备间也必须有可达机械臂与可达性证明，否则阻断而不是直连。
- 支撑 `ADR-ISLAND-003`：Workcell 是**物理与调度边界**，不是可以和功能分组、安全域互换的标签。

**SUPERSEDES：** 实施规格 §1 invariant 4 中关于 Workflow 的部分保持不变（本条升级其为强制决策）。

### 2.2 ADR-ISLAND-002：实验（Experiment）允许跨 Workcell

**Decision：ACCEPT。** 用户裁决 2026-11-07，取代此前「Experiment 必须与其引用的 Workflow 同岛」的全部结论。

- Experiment 可以引用多个**不同 Workcell** 的 Workflow；每个 Workflow 仍须满足 ADR-ISLAND-001（单 Workcell）。
- 跨 Workcell 的衔接必须发生在**实验编排层**，并显式声明 Handoff：源 Workcell 出口、目标 Workcell 入口、转运执行者、板/样本身份、物理到位证据。
- Handoff 可达性或到位确认 **UNKNOWN ⇒ 阻断**，不得自动放行；AGV 当前不纳入范围，转运方式见 §4 待决 Q-01。
- 跨 Workcell 实验不意味着放宽物理边界：**每个节点仍只在其 Workflow 所属 Workcell 内执行**，`WorkflowService.withIsland()` 空候选回退全局设备池仍必须移除。

**关键推论（防止开发按旧逻辑实现）：**

1. `experiment.island_id` 是单值列（`96_schema_fix.sql:142`），**不能表达跨 Workcell 实验**。需新增 Experiment↔Workcell 关联（或等价 ExecutionPlan 绑定）；原列只保留为「主/默认 Workcell」的展示与兼容语义，**不得作为执行边界**。
2. 执行上下文的 Workcell **取自当前 Workflow 的权威归属**，不是 `experiment.island_id`。原「ExperimentExecutor 从 experiment 读 islandId 再传给 WorkflowService」的修复方向作废。
3. `ExperimentScheduler` 以 `experiment.island_id` 作队列与 JVM 内存锁键 ⇒ 跨 Workcell 实验须按**涉及的 Workcell 集合**加锁，并按稳定顺序获取以避免死锁；2h TTL 内存锁仍不能作为物理互斥保证。
4. `RuntimeHold` 覆盖判定按**节点实际 Workcell + SafetyDomain + 依赖闭包**，不能按实验单值 island_id；`coveringForWorkflow` 的反查链路需相应扩展。
5. 测试期望反转：原「Experiment A 引用 Workflow B 必须拒绝」的用例改为「有合法 Handoff 时允许；Handoff 缺失/UNKNOWN 时阻断」。
6. `ADR-FAULT-SCOPE-001` 与 §14.5「同一 ExperimentRun 可部分受影响」在新裁决下更成立：跨 Workcell 实验可只有部分节点受某 Workcell 故障影响。

**SUPERSEDES：** 见 §3 全部作废/修正条目。

### 2.3 ADR-ISLAND-003：禁止用单一 `island_id` 相等判断代替领域边界

**Decision：ACCEPT。** 用户原则 2026-11-07：后续实现不能为方便把 Island、Workcell、Functional Island、SafetyDomain 合并成一个简单的 `island_id` 判断。

领域对象保持分离（沿用外部实践与 ADR §14.1，并补充「Flow 单岛 / Experiment 跨岛」）：

| 对象 | 职责 | 与 `island_id` 的关系 |
|---|---|---|
| Island / Workcell | 当前物理自动化单元（设备、机械臂、板位、交接点、内部空间、健康状态） | 是物理归属，可用于 Workflow 绑定 |
| RobotArm | 一等共享资源；所属 Workcell、能力、可达设备/位置、工作空间/互斥区 | 不等于 Workcell，不得按 Workcell 数量推断可并行 |
| Functional Island | 未来可能的跨物理 Workcell 纯逻辑工艺分组 | **当前不建实体**，且不得与 Workcell 共用字段 |
| SafetyDomain | 独立的风险影响边界，可覆盖一个或多个 Workcell | 独立建模，故障传播按 SafetyDomain，不靠 `island_id` 相等 |
| Resource / Position | 设备、机械臂、板位、储位、端口、交接点 | 归属 Workcell，但互斥按资源粒度 |
| Dependency Edge | 工艺先后、样本/板位、搬运、设备共享、工作空间冲突、环境与安全依赖 | 跨 Workcell 实验的影响闭包依据 |

**接口要求：** `BoundaryResult` / `BoundaryViolation` 必须携带 `boundaryKind`（WORKCELL / SAFETY_DOMAIN / RESOURCE / ROBOT_REACHABILITY / DEPENDENCY / HANDOFF）、`scopeType` 与 `evidence`，并区分 `INVALID` / `UNKNOWN`；**不得只返回 `expectedIslandId` / `actualIslandId` 两个 Long 做相等比较**。SQL 失败必须传播为 UNKNOWN，绝不转为空资源列表。

**SUPERSEDES：** 实施规格 §3 的领域 API 提案（需按本条扩展后再实现）。

### 2.4 ADR-PROCESS-001：`main` 为唯一执行基线

**Decision：ACCEPT。** 用户裁决 2026-11-07。

- `main` 是**唯一工程执行基线**：所有整改 PR 从 `main` 切出、往 `main` 合并；评审分支 `review/labos-industrial-architecture` 是**设计归档**，不再承载代码实施。
- `main` 的 `3debf78`（实验级暂停三层真停 + `RunControl.resume` 作废旧 paused run + 门禁 `MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5`）是代码基线，**必须同步到评审分支**，避免归档分支基于落后代码被引用。
- 评审分支的 P0 三项保守修复（GraphRunner 不 fallback 重跑、TCP abort 不冒充物理停止、同 run worker 并发护栏 + 两个 SelfTest）以**独立 PR 搬到 `main`**，与 main 修复取并集，不得整表覆盖任何一边。
- **门禁纪律**：断言语义变更必须新增段号 + 新增 `MIN_*` 变量，不得复用既有段号/变量名（原分支曾用 `MIN_INTR=3` 段 4.18 承载「安全边界暂停」替换「A1 真停」，阈值未变使「MIN_* 只升不降」机制失效）。
- 禁止 `git checkout <某一边>` 式整表覆盖；合并冲突按 `REMEDIATION_STATUS.md` 的裁决表逐文件处理。
- 评审分支归档后打 tag 冻结；后续讨论与代码改动一律回写 `main` 上的这套文档。

**SUPERSEDES：** 上一轮评审报告中「先把 main 合入评审分支、在评审分支上继续开 PR」的方案。

### 2.5 ADR-DOC-001：架构文档不得出现商业公司/产品名称

**Decision：ACCEPT。** 用户裁决 2026-11-07。

- 架构与评审文档**不得出现商业公司名称、商业产品名称、厂商链接或厂商域名**。
- 外部产品实践统一以「产品形态 A / B / C」表述，只保留可迁移的架构原则与证据等级，不保留对标来源标识。
- 允许保留公开标准与开源技术名（ISA-88 / PackML、ROS 2 Actions、SiLA 2、OPC UA A&C、Apache Airflow、Temporal 类编排引擎），这些不是商业对标标识。
- 参考资料节只写证据边界（公开资料只能证明用户可见行为、模拟不等于 HIL、物理安全需现场验收），不列厂商条目。
- 适用于 `docs/architecture/` 全部文档、PR 描述、评审报告与后续 ADR；review checklist 增加一条「无商业公司/产品名」。

**首轮执行：** 已从 6 份归档文档中移除全部商业公司/产品名称与厂商链接，按禁用关键词清单全量校验为 0 命中。禁用清单只在本地校验脚本中维护，不写入仓库文档。

### 2.6 ADR-ISLAND-004：跨 Workcell Handoff 由人工完成（Q-01 裁决）

**Decision：ACCEPT。** 用户裁决：**衔接由人工完成，在人工配置岛内设备、IP 等时就把 Handoff 定义好**。

- Handoff = **配置期静态定义 + 运行时人工执行**，不是运行时自动调度；不需要 AGV、机械臂跨岛可达性、路径/碰撞规划（与 ADR §14.1「AGV 不纳入范围」一致）。ADR-ISLAND-002 中「转运执行者 / 可达性证明」的表述据此改为人工执行。
- **配置期**：在岛配置入口（设备/IP 配置、挂岛/移出）同处定义 Handoff：源 Workcell、目标 Workcell、交接点/出入口、板与样本身份、执行方式=人工、所需确认项，并持久化。现状不足：`island_flow.graph` 的边只落 `{id,source,target}`，不落 `type/label`（`IslandFlow.tsx:200-203`），且它是**岛内动线**；跨 Workcell Handoff 应挂在**实验编排层的边**上——候选 `experiment_process_edge`（`97_m95_process_edge.sql:24-44`，其注释明确「只表达偏序，不表达资源」，`run_after` 执行器从未读过），需扩展或新建 `experiment_handoff`。
- **运行时**：跨 Workcell 实验在两个 Workflow 之间必须停在**人工门禁**，不得自动推进。复用已有 `manual_window` + `pause_kind='waiting_human'` + `GET/POST /api/manual-windows`（`RunApi.java:89,113`），遵守 ADR-PAUSE-007「不新建第三套子系统」，**不另建 Handoff 状态机**。
- **物理到位证据 = 人工确认记录**，不是设备 ACK。每次运行必须记录确认人、时间、板/样本标识、源/目标交接点、确认结果；无记录 ⇒ `UNKNOWN` ⇒ 阻断后续节点。**配置里定义过只证明「允许这么走」，不证明「这一次板真的到了」**，绝不可因已配置就自动放行。
- main 现状缺口：`ManualWindow` 后端齐全但 `webapp` 全目录 **0 处消费**；唯一人工确认是 `Plates.tsx` 手填 barcode 的板位盘点，无扫码/传感器、无「样本到位」独立入口，`position_state` 无确认字段。⇒ 必须补前端人工确认页 + `InterventionRecord` 落证（正好是 ADR-PAUSE-003 要求的实体）。
- 影响：`REMEDIATION_STATUS.md` 的 N-04 定义改写，新增 N-07（配置期结构）、N-08（运行时门禁）、L-01（`InterventionRecord`）。

### 2.7 ADR-ISLAND-005：`island_id` 仅区分、界面只显示岛名（Q-02 裁决）

**Decision：ACCEPT。** 用户裁决：`island_id` 仅用于区分，**展示岛的名字，不需要展示岛的 ID**。

- `island_id` 是**内部区分键**，不向用户展示数字 ID；界面只显示岛名（字段已存在：`resource_node.name`，`20_kernel.sql:30-31`；`QueryService.java:714` 已有 `isl.name AS island_name`）。
- `experiment.island_id` **不参与执行判定**，也不承担「主 Workcell」语义；跨 Workcell 实验涉及的 Workcell 集合由 Experiment↔Workflow 关联推导，界面展示**涉及的岛名列表**而非单个名字。
- ⚠️ **与 main 现状的差距就是 N-01/N-02/N-03 的真实工作量**：`e.island_id` 目前在 7+ 处是执行判定核心——`RunControl.java:60,136`（暂停作用域）、`RuntimeHold.java:329`（扣留覆盖）、`ExperimentScheduler.java:226,845`（队列与岛锁键）、`ExperimentExecutor.java:704`（故障隔离）、`BatchOrchestrator.java:1475`。这些必须逐一改为按**节点实际 Workcell** 判定，**不能只改 UI**。
- `workflow.island_id`（P-01 新增）是**权威执行边界**，与 `experiment.island_id` 的「区分/展示」定位不同，不得混用同一语义或同一套读写路径（ADR-ISLAND-001/003）。
- 前端待改：`ExperimentList.tsx:169,171` 现用 `islandCode` + Tooltip `islandName`，`Scheduler.tsx:171,232` 的 `IslandCell code/id` 会把 ID 暴露出来，需只留岛名。
- **不做**：不删除 `experiment.island_id` 列（破坏性、影响展示与既有 e2e），不把岛名塞进 ID 字段。

### 2.8 ADR-PROCESS-002：两套暂停 e2e 拆段（Q-07 裁决）

**Decision：ACCEPT。** 用户裁决：两套都留、拆两段。

- main 当前最大段号 **4.18**（`ci-gate.sh:521`），`MIN_INTR=3` 对应「A1 真停·可中断方法执行中发暂停 → 命令 `aborted`、run `paused`」（`e2e-interrupt-during-device.py` 3 条断言）。**4.18 原样保留**，不改名、不动 `MIN_INTR`。
- 新增 **4.19「安全边界暂停 · TCP 无物理 Abort」**，新变量 `MIN_SAFEBOUND`（不复用 `MIN_INTR`）；脚本另起 `e2e-safebound-pause.py`——评审分支改的是 `e2e-interrupt-during-device.py` 同一个文件，直接合并会互相覆盖。
- 理由：`ci-gate.sh:100-118` 的段 0 按 git 历史最大值校验 `MIN_*` 下调；**复用同一变量名做语义替换不会触发红灯**，这正是分叉时发生的事。新增变量无历史最大值，不冲突。
- 两套断言并存：4.18 证明「声明可中断时能真停」，4.19 证明「无物理 Abort 能力时不谎报停止、当前动作完成、下一 task 暂停、不重复派发」。
- 同步：`ci-gate.sh:540` 的 skip 文案 `4.5-4.18` 改为 `4.5-4.19`。

### 2.9 ADR-LAYER-001：分层落地采用「最小骨架 + 收敛已有实现」，分三批

**Decision：ACCEPT。** 基于 2026-11-07 对 `main` 的源码盘点，回答「main 上设计完善吗 / 要不要把分层加进来」：**不完善，需要加，但不是一次性全加**。

| 分层 | main 现状 |
|---|---|
| 状态三层 | 只有 `run` / `run_node` / `experiment_run` / `experiment_run_step` 的单 `status` 字段；无 `ExperimentRun` / `NodeExecution` / `DeviceCommand` 领域类；`PAUSE_REQUESTED` / `PAUSING` / `WAITING_INTERVENTION` / `RESUMING` 全仓 **0 命中**，只存在于设计文档；`experiment_run.status varchar(16)` 放不下 20 字符的 `WAITING_INTERVENTION` |
| 四正交事实 | 只有 **Hold Ledger**（`runtime_hold`）是独立实体；Run Control 挤在 status + 5 个控制列；Command 靠复用 `node_status` 的 `run_command`、步结束才落库、无 Outbox；Alarm/Intervention 散在 `alert_state` / `manual_window` / `error_occurrence` / `run_event`，无 `InterventionRecord` |
| 六类领域对象 | **0 实体**。`island` 只是 `resource_node.kind` 的枚举值；`SafetyDomain` / `DependencyEdge` / `ExecutionPlan` / `Workcell` / `Handoff` 在 Java/SQL/TSX 全 0 命中；最接近的 `experiment_process_edge` 注释明说「只表达偏序，不表达资源」 |
| 人工能力 | `manual_window` 后端齐全、前端 **0 消费**；无扫码/传感器/样本到位确认 |

**三批实施边界：**

1. **必须先加（阻塞跨 Workcell）**：Workflow 权威 Workcell 列、Experiment↔Workcell 关联、配置期 Handoff 结构、运行时人工确认门禁、`InterventionRecord`。对应 N-01 / N-04 / N-07 / N-08 / L-01。
2. **同期最小骨架（否则状态语义落不了地）**：`experiment_run.status` 扩容或短 code + display label；`PAUSE_REQUESTED` / `PAUSING` 最小中间态；CommandExecutionState 最小账本 + 最小 Outbox；人工窗口前端消费页。一律复用 `ManualWindow` / `waiting_human`，**不新建第三套状态机**（ADR-PAUSE-007）。对应 L-02 / L-03。
3. **暂不建全套（避免过度设计）**：SafetyDomain 传播算法、RobotArm 工作空间互斥、Dependency Edge 完整图、完整聚合器投影。当前只需「依赖闭包 UNKNOWN ⇒ fail-closed」的最小实现。依据是整改计划总原则「不大规模重写执行链，一次一个小 PR」；且 **ADR-ISLAND-003 要求概念分离，不等于一次性建 6 张表**。

**为什么现在就是加分层的最佳时机**：Q-01 把 Handoff 定为人工，恰好需要「人工确认记录 + 执行门禁」，这与 ADR-PAUSE-003 的 `InterventionRecord` 是同一个实体；若这次不一起做，后续必然出现两套人工记录，正是 ADR-PAUSE-007 要避免的。

### 2.10 ADR-LOCK-001：Redis + PG 联合锁，按资源粒度（Q-03 裁决）

**Decision：ACCEPT。** 用户裁决：锁要**通过 Redis、PG 一起来做，包括设备等**；不用 `islandId` 当锁键，不用 JVM 内存锁和 2h TTL（「这种容易有问题」）。

- **废弃**：`ExperimentScheduler` 以单一 `islandId` 为键的 JVM `ConcurrentHashMap` + 2h TTL（`tryLockIsland`、`sweepExpiredLocks:633`）。三条理由：多 Kernel 实例下不互斥；TTL 到期可能在原执行仍在跑时释放；键粒度是**整岛**，无法表达设备、机械臂、板位、端口、共享工作空间/交叉区、转运路径之间的真实互斥关系。
- **目标粒度**：按**资源**加锁，而不是按岛。一个跨 A/B 的实验占用的是「A 的设备 X + A 的机械臂 + 交接点 + B 的设备 Y + 相关板位」这组资源，不是「岛 A」「岛 B」两个粗粒度标签。
- **职责划分（防止双主）**：
  - **PG 是权威事实层**——资源占用、租约记录、fencing token、状态变更在同一事务提交；用行锁 / 唯一约束 / `pg_advisory_xact_lock` 保证跨实例互斥。
  - **Redis 是加速与跨实例协调层**——租约抢占、事件通知、对热资源快速拒绝重复派发；**不作为唯一真相源**。Redis 不可用或数据丢失，不得让系统把物理资源误判为空闲。
- **可复用基础**：`labos-kernel/.../redis/RedisClient.java`（自研 RESP2 最小客户端，env `LABOS_REDIS_HOST/PORT/PASSWORD/DB`）、`labos-gateway` 同名客户端、`docs/设备网关与Redis状态中心-设计.md`；PG 侧已有 `pg_advisory_xact_lock`（`QueryService.java:1585`）与 `SELECT ... FOR UPDATE`（`BatchOrchestrator` 多处）。⚠️ **实施前必须核实**自研 `RedisClient` 是否支持 `SET NX PX`、Lua 原子释放与续约；若不支持，先补客户端能力，不要用「先查后写」冒充原子锁。
- **硬约束**：租约到期只释放**软件租约**，不释放物理安全隔离；每个资源锁带 fencing token，旧持有者的写必须被拒；进程崩溃 / 网络分区 / Redis 主从延迟 ⇒ 资源状态 `UNKNOWN/QUARANTINED`，不得自动重派发、不得自动释放设备。
- **SUPERSEDES**：Q-03 原默认建议「按 Workcell 集合 + ID 升序加锁、2h TTL 暂留」，以及所有以 `e.island_id` 为锁键/队列键的路径（`N-03` 据此改写）。

### 2.11 ADR-FAULT-SCOPE-002：同一实验内任一 Workcell 故障 ⇒ 整个实验停（Q-04 裁决）

**Decision：ACCEPT。** 用户裁决：「A 岛故障时，这个实验都要停止，否则没有意义」——**推翻上一轮「按节点判定、B 段可继续」的建议**。

- 跨 Workcell 实验是**一个业务整体**：任一所属 Workcell 故障或扣留 ⇒ 该 **ExperimentRun 的所有节点、所有 Workflow 段、所有 Handoff 边界**都停止派发新工作，在途动作按各自设备能力收敛，**不允许 B 段继续**。
- **范围边界（与 ADR-FAULT-SCOPE-001 分工，不能混淆）**：
  - 同一实验内：**整实验停**（本 ADR）。
  - 其他独立实验：仍按 `ADR-FAULT-SCOPE-001`，Workcell 隔离 + Continue Predicate + SafetyDomain 传播。
- 因此 **Continue Predicate 不适用于同一跨 Workcell 实验内部的其他段**；外部实践与 ADR §14.5「同一 ExperimentRun 可部分受影响」、状态模型 §14.5 同类表述**作废/限定**：展示层可以标出哪些节点受影响，但执行层一律阻断。
- 实现：Hold 仍可按 Workcell 发起，但**影响闭包的最小单位是 ExperimentRun**。`RuntimeHold` 覆盖判定、`ExperimentScheduler` 逐候选评估、`autoContinueHeldBatches` 都不得因「这个节点在另一个 Workcell」而放行同一实验；Handoff 边界同样阻断。
- **SUPERSEDES**：Q-04 原建议「按节点实际 Workcell 判定、UNKNOWN 扩大阻断」（放大到实验级）、ADR §14.5 与状态模型 §14.5 的「可部分受影响」表述。

### 2.12 ADR-STATE-001：状态机多层维护治理

**Decision：ACCEPT。** 基于 2026-11-07 对 `main` 源码与 203 份原有文档的盘点；专门文档见 `LABOS_STATE_MACHINE_MULTILAYER_20261107.md`。

- **现状**：两套主状态机并存且独立写库——`PauseManager` → `run.status`、`RunControl` → `experiment_run.status`；另加 `runtime_hold` / `manual_window` / `run_node` / `run_command` 各自直写（`run.status` 约 10 处、`experiment_run.status` 约 9 处写入点）。**无 `PauseCoordinator`、无聚合投影器**（Java 源码 0 命中，只在文档里）。`3debf78` 是约定式补丁（三层真停 + 作废旧 run + `coveringForWorkflow` 反查），**未合并两套模型**。仓库原有 203 份文档**没有一篇**以「多层维护」为主题的专篇，内容散在实测记录、`BUG台账.md`、测试用例总表、开发日志与一份单套状态机设计。
- **决策**：① 入口只写**事实**，状态由聚合器投影（`ADR-PAUSE-002/003`）；② 建 `PauseCoordinator` **适配层**收敛两套模型，不直接删 `PauseManager`；③ 保留 `M-01` 作废旧 paused run 契约（防重复物理动作），任何重构必须有对应回归；④ 建原有 26 份状态相关文档的索引并标注过时项。
- ⚠️ **必须先解决的验收口径冲突**：`docs/测试用例总表.md:51,216` 与 e2e 4.17 以「**三层都 `paused`**」为通过判据，而新设计说 `status` 只是投影、判据应看 Hold + 节点 + 命令事实。若直接把 API 改成返回 `PAUSE_REQUESTED`，现有断言会变红；若为满足断言继续立即写 `paused`，中间态就永远落不了地。
  - 处置：`M-01` 行为保留为回归基线，分两个 PR：**PR-S04-A** 保留 legacy API/status，先增加本次 Hold ID、device_state 在途/收敛、Workflow 节点边界、后续 pending 等事实证据；由于当前 `RunControl.pause()` 立即写 ExperimentRun/Experiment paused，且 `ExperimentApi` 立即返回 `status=paused`，A 阶段不得声称未收敛时 UI/API 已不显示 PAUSED，也不得加入无法通过的断言。**PR-S04-B** 在 Hold 三态（P-03）、命令账本与 reconciliation（P-07/L-03）、状态表达（L-02）、PauseCoordinator（S-02/P-06）具备后，再切 API/UI 语义，并新增「请求已接受但未收敛时不得显示 `PAUSED`」断言。禁止同一 PR 同时改实现与验收判据。
  - 该两阶段边界由 `LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md` 细化；它是 ADR-STATE-001 的实施验收澄清，不改变「事实为准、status 为投影」的已接受原则。
- 新增工作项 `S-01`~`S-05`（见 `REMEDIATION_STATUS.md`）。

### 2.13 ADR-PAUSE-008：控制状态（`control_state`）与生命周期（`status`）分离

**Decision：ACCEPT。** 用户裁决 2026-11-07（L-02；明确「尽量从更完善的方案出发，而不是从改动少的角度出发」）。

- **不把暂停中间态塞进 `experiment_run.status`**（否决 `LABOS_S04B_IMPLEMENTATION_PLAN_20261107.md` §3.1 的方案 A）。`status` 只表达**生命周期**（`queued` / `running` / `paused` / `completed` / `failed` / `aborted` / `cancelled`），取值与 legacy 契约**保持不变**。
- 新增列 `experiment_run.control_state` 表达**暂停控制过程**：`NONE` / `PAUSE_REQUESTED` / `PAUSING` / `RESUMING`，默认 `NONE`。与 `pause_kind`（为何暂停）语义不重叠：`pause_kind` 是原因，`control_state` 是控制阶段。
- 同时 **`experiment_run.status` 列长扩容**（`varchar(16)` → `varchar(32)`）：`WAITING_INTERVENTION`（20 字符）等新生命周期码当前列放不下（`REMEDIATION_STATUS.md` L-02 行、`LABOS_STATE_MACHINE_ACCEPTANCE_CONTRACT_20261107.md` §101）。扩容是 L-02 的硬要求，与方案 A/B 无关。
- 依据：`paused` 现已被复用为「等待人工」（`pause_kind=waiting_human`）；若再把中间态压进同一列，`status` 会变成随时间累积的多义字段，而它的读方（页面、聚合器、门禁）远多于新的 `control_state`，语义漂移风险高。
- **本 ADR 只授予「状态表达能力」，不改行为**：L-02 不改变 `status` 何时变为 `paused`（legacy 立即投影保持），因此 PR-S04-A / e2e 4.17 / 4.18 判据不退化。「请求已接受但未收敛时不得显示 `PAUSED`」的行为切换属 **PR-S04-B**（`LABOS_S04B_IMPLEMENTATION_PLAN_20261107.md` §5 批次 1）。
- **2026-11-08 S-04-B 最小闭环落地**：实验级 Pause API 返回 status=accepted + controlState=PAUSE_REQUESTED，受理时不写 paused；收敛由执行器在动作边界 settle（running）或调度器 RunControl.convergeQueuedPauses（queued）完成，之后才写 paused 且 control_state 回 NONE；页面显示 control 词表（已请求暂停/暂停中）。4.17=17/0、4.20=25/0。
- 词表单一权威：后端 `StatusVocabulary` 增 `control` scope，前端 `webapp/src/utils/status.ts` 与 `webapp/src/i18n/codes.ts` 同步；不自建第二套映射。
- **SUPERSEDES**：`LABOS_S04B_IMPLEMENTATION_PLAN_20261107.md` §3.1 方案 A（把 `PAUSE_REQUESTED` / `PAUSING` 塞进 `status`）。

### 2.14 ADR-PAUSE-009：V1 幂等键采用独立 `pause_request` 表（Q-1 裁决）

**Decision：ACCEPT。** 用户裁决 2026-11-07：**选项 B（更完善）**——`PauseCoordinator` 的幂等键用独立持久化表，而非「复用 `runtime_hold` + 审计」（选项 A）。

- 新建 `pause_request` 表承载：`request_id`（幂等键，唯一）、`tenant_id`、`target_type` / `target_id`、`action`、`source` / `kind` / `reason`、`requested_by`、`requested_at`、`result_state`（`ACCEPTED` / `COMPLETED` / `REJECTED` / `UNKNOWN`）、`created_at`。
- 语义：同 `request_id` 重复请求 ⇒ 不重复建 Hold / 不重复下发，直接返回首次结果（幂等可查）。
- 与 `ADR-PAUSE-005`（按 `hold_id` 精确清除、需幂等键去重）同源：`pause_request` 是幂等键的落库载体。
- 影响：`PauseCoordinator`（V1-5）、schema 新增表、`P-06`。

### 2.15 ADR-PAUSE-010：Hold 读取失败的 `UNKNOWN` 必须运维可见（Q-2 裁决）

**Decision：ACCEPT。** 用户裁决 2026-11-07：**选项 B（更完善）**——不在 V1 只落日志。

- `RuntimeHold` 的 `CLEAR / HELD / UNKNOWN` 三态（`ADR-PAUSE-005`、`P-03`）中，`UNKNOWN` 由 `GET /api/holds` 显式暴露（如 `unknown=true` 及原因），使「读不到 Hold ⇒ 派发被阻断」这一 fail-closed 行为对运维**可见**，而不是仅落日志。
- 依据：`UNKNOWN` 会导致调度器整体阻断新派发，若只落日志，运维看到的「实验不派发」将没有任何界面线索。

### 2.16 ADR-PROCESS-003：L-02 先于 S-02/P-06 合并（Q-3 裁决）

**Decision：ACCEPT。** 用户裁决 2026-11-07：**先合 L-02，再合 S-02/P-06（PauseCoordinator）**。

- 合并顺序：`L-02`（状态表达能力：`control_state` + `status` 扩容 + 词表） → `S-02 / P-06`（PauseCoordinator 适配层，含 `ADR-PAUSE-009` 的 `pause_request` 幂等表与 `ADR-PAUSE-010` 的 UNKNOWN 暴露） → `S-03`（事实/投影切换） → `S-04` 第 2 个 PR。
- 与 `REMEDIATION_STATUS.md` Step 7 依赖序一致（`L-02 → S-02/P-06 → S-03 → S-04B`）。
- `V1-1`~`V1-4`（安全闸门 fail-closed、资源释放修复、PauseCoordinator 适配层）是 `P-03` / `P-06` 的**安全子集**，须在提交信息中说明，不与 `P-03` 的全部范围（逐 Workcell 候选调度、`uk_runtime_hold_active` 唯一索引改造）混淆。

---

## 3. 被取代条目（SUPERSEDED）

| 原条目 | 出处 | 由谁取代 | 处置 |
|---|---|---|---|
| §1 invariant 4「Experiment belongs to exactly one Island…」 | Island 边界实施规格 | ADR-ISLAND-002 | **作废** |
| §2.5 / §3 `validateExperimentWorkflows` 的 Island 相等校验 | 同上 | ADR-ISLAND-002 / 003 | 作废，改跨 Workcell 组合 + Handoff 校验 |
| §6 矩阵中 Experiment create/replace/save/execution 四行的「同 Island」 | 同上 | ADR-ISLAND-002 | 作废 |
| §7 集成测试 8 / 9 / 10「Experiment A 引用 Workflow B 必须拒绝」 | 同上 | ADR-ISLAND-002 | **期望反转** |
| §3 领域 API 仅两个 `Long` 的 `BoundaryViolation` | 同上 | ADR-ISLAND-003 | 接口须扩展 |
| P0-7「Experiment / Workflow Island 一致性」 | 整改计划 Batch 0.6 | ADR-ISLAND-002 | **整项作废** |
| 「P0-7 不只是建议补查：确认存在缺口」 | 整改计划 Batch 0.6 补充结论 | ADR-ISLAND-002 | 作废 |
| §8「Experiment 组合阶段也没有 Island 一致性校验 — CONFIRMED / P0」 | Island 边界与 Hold 调度专项审计 | ADR-ISLAND-002 | **整节结论撤销**；源码事实保留，改作跨 Workcell 绑定缺口证据 |
| §3.4 中要求 Experiment 与 Workflow Island 一致 | 同上 | ADR-ISLAND-002 | 作废 |
| P0-B 第 1 条「ExperimentExecutor 从 experiment 读取 islandId」 | 同上 | ADR-ISLAND-002 | 改为按 Workflow 权威 Workcell 取上下文 |
| §13.5 第 3 条「实验/流程可跨 Workcell」 | 外部实践与 ADR | ADR-ISLAND-001 / 002 | 修正为「实验可跨、流程不可跨」 |
| §12「基于 main 源码核对」的基线描述 | 暂停状态模型 | ADR-PROCESS-001 | 基线补记 `3debf78` |
| A-02 的修复方向（把 experiment.island_id 传给 withIsland） | A–F 源码审计 | ADR-ISLAND-002 | 改为按 Workflow 取 Workcell |
| 「先把 main 合入评审分支继续开发」 | 上一轮评审报告 §5 Step 0 | ADR-PROCESS-001 | 改为 main 为执行基线 |
| §2.2 中 Handoff「转运执行者 / 可达性证明」的自动调度表述 | ADR-ISLAND-002 | ADR-ISLAND-004 | 改为人工执行、配置期定义、运行时人工确认 |
| §2.2 中「Experiment 主/默认 Workcell」的兼容语义表述 | ADR-ISLAND-002 | ADR-ISLAND-005 | 改为纯内部分区键 + 岛名展示，不承担主 Workcell 语义 |
| Q-03 原默认建议「按 Workcell 集合 + ID 升序加锁、2h TTL 暂留」 | 上一轮评审报告 §9.4 | ADR-LOCK-001 | 改为 Redis + PG 联合、按资源粒度锁，废弃 islandId 键 / JVM 内存锁 / TTL |
| Q-04 原建议「按节点实际 Workcell 判定、B 段可继续」 | 上一轮评审报告 §9.4 | ADR-FAULT-SCOPE-002 | 改为**同一实验整体停**，不允许其他 Workcell 段继续 |
| ADR §14.5「同一 ExperimentRun 可部分受影响」、状态模型 §14.5 同类表述 | 外部实践与 ADR / 状态模型 | ADR-FAULT-SCOPE-002 | 限定为「展示层可标注，执行层一律阻断」 |
| `REMEDIATION_STATUS.md` 的 N-03 原定义（集合加锁 + 稳定顺序） | 整改状态表 | ADR-LOCK-001 | 改写为分布式资源锁（见状态表 N-03） |
| `docs/测试用例总表.md:51,216` / e2e 4.17「三层都 `paused`」作为唯一通过判据 | 原有验收文档 | ADR-STATE-001 | 判据升级为「三层 status + Hold 有效 + 在途命令已收敛」，分两个 PR 切换 |
| S-04B §3.1 方案 A（把 `PAUSE_REQUESTED` / `PAUSING` 塞进 `experiment_run.status`） | `LABOS_S04B_IMPLEMENTATION_PLAN_20261107.md` | ADR-PAUSE-008 | 改为新增 `control_state` 列；`status` 只表达生命周期 |

---

## 4. 待决问题

### 4.1 本轮已裁决（2026-11-07）

| ID | 裁决 | 决策编号 |
|---|---|---|
| Q-01 | 跨 Workcell Handoff 由**人工**完成，配置期随岛内设备/IP 配置一并定义；到位证据为人工确认记录 | ADR-ISLAND-004 |
| Q-02 | `island_id` 仅内部区分；界面只显示**岛名**、不显示 ID；`experiment.island_id` 不作执行边界 | ADR-ISLAND-005 |
| Q-07 | 4.18 保留，新增 4.19 + `MIN_SAFEBOUND`，两套 e2e 并存 | ADR-PROCESS-002 |
| Q-08（本轮用户提问） | 「分层是否加进 main」→ **要加，按最小骨架分三批**，不一次性建全套 | ADR-LAYER-001 |
| Q-03 | 锁改为 **Redis + PG 联合、按资源粒度（含设备）**；废弃 `islandId` 键 / JVM 内存锁 / 2h TTL | ADR-LOCK-001 |
| Q-04 | 同一实验内任一 Workcell 故障 ⇒ **整个实验停**，不允许其他 Workcell 段继续 | ADR-FAULT-SCOPE-002 |
| Q-11（本轮用户提问） | 「状态机多层维护」→ 补专门文档 + 治理工作项 `S-01`~`S-05`，含验收口径切换 | ADR-STATE-001 |
| L-02 设计（S-04B §3.1） | 暂停中间态放哪 → **新列 `control_state`（方案 B）** + `status` 扩至 `varchar(32)` | ADR-PAUSE-008 |
| Q-1（PauseCoordinator V1 设计 §9） | 幂等键持久化 → **新建 `pause_request` 表**（取更完善方案） | ADR-PAUSE-009 |
| Q-2（PauseCoordinator V1 设计 §9） | `UNKNOWN` 运维可见 → **`GET /api/holds` 暴露 `unknown=true`** | ADR-PAUSE-010 |
| Q-3（PauseCoordinator V1 设计 §9） | 合并顺序 → **先合 L-02，再合 S-02/P-06** | ADR-PROCESS-003 |

### 4.2 仍 OPEN

| ID | 问题 | 阻塞对象 | 备注 |
|---|---|---|---|
| Q-05 | Functional Island 何时引入，以及引入后与 Workcell 的字段隔离方案 | ADR-ISLAND-003 | 可延后 |
| Q-12（ADR-LOCK-001 带出） | 自研 `RedisClient` 是否支持 `SET NX PX` / Lua 原子释放 / 续约；若不支持先补客户端能力 | N-03 | 实施前查清 |
| Q-06 | 分支 P0 三项修复搬 `main` 的 PR 拆分顺序（段号已由 ADR-PROCESS-002 定） | ADR-PROCESS-001 | 可在 PR 内定 |
| Q-09（Q-01 带出） | 人工到位确认落在新建 `InterventionRecord` 还是扩展 `manual_window`？现有 `manual_window` 只对应 `waiting_human` 暂停态，`decided_action` 白名单 `CONTINUE/RETRY/SKIP/ABORT/MANUAL` 无「样本到位确认」语义 | N-08、L-01 | 可在 PR 内定 |
| Q-10（Q-01 带出） | 跨 Workcell Handoff 配置挂扩展 `experiment_process_edge` 还是新建 `experiment_handoff` | N-07 | 可在 PR 内定 |

---

## 5. 证据规则

- 每条 `ACCEPT` 决策必须能追溯到本表编号；实施 PR 必须引用编号。
- 状态变更只能改 `REMEDIATION_STATUS.md` 对应行，并附 CI / e2e / HIL 证据；只有 checkbox 没有证据不得标 `DONE` 或 `VERIFIED`。
- 真机安全相关结论必须有设备型号、固件、方法、负载和停止后状态的 HIL 证据；任何门禁阈值下降、SKIP、仅跑模拟器却宣称真机通过，均不接受。
