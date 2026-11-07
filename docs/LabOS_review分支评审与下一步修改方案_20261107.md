# LabOS `review/labos-industrial-architecture` 分支评审与下一步修改方案

日期：2026-11-07
核对方式：本地只读（`git fetch` + `git show 分支:路径`），未切换分支、未改工作区、未动远端。
核对范围：分支 6 份架构文档全文、分支与 `main` 的 55 个差异文件、`pg_rebuild/schema/` 全量 DDL、关键 Java/TSX/CI 脚本。

> **v2 修订（2026-11-07，用户裁决后）**
> 用户已拍板四条决策，见本文 **§8**。其中第 3 条**取代了 §0 与 §5 的合并策略**：不再是「把 main 合回评审分支、在分支上继续开发」，而是 **`main` 为唯一执行基线、评审分支仅作设计归档**（`ADR-PROCESS-001`）。
> 第 1 条（流程不允许跨 Workcell、实验允许跨 Workcell）**反转了多条 P0 结论**，见 §8.1 与 `ADR_INDEX.md` §3。
> 结论与状态请以 `docs/architecture/ADR_INDEX.md` 与 `docs/architecture/REMEDIATION_STATUS.md` 为准；本文 §0~§7 保留为评审当时的证据记录。

---

## 0. 一句话结论（评审当时；合并策略已被 §8 取代）

**先别开 PR-1。第一件事是处理基线分叉并做一次语义裁决**：分支的基线早于 `main` 上一个已通过 CI 的 P0 暂停安全修复，按现状继续开发，会把"实验级暂停停不住底层 run"和"恢复后重复执行设备动作"两个已修好的 bug 重新带回主干；同时文档体系的编号已经冲突到「说做 P0-1 无法确定指哪件事」，需要先建立单一真源（ADR 注册表 + 整改状态表），并把"Island 不能压平成 `island_id` 相等判断"写成 ADR 再动代码。

你对三层（讨论结论已整理 / 源码证据已整理 / 工程实现未完成）的判断方向是对的，但需要精确化：**Batch 0 的三项保守缓解在分支上已经落地为代码**（55 文件 / +3492 / −2321），未实现的是 Batch 0.4/0.5/0.6 与实施规格的 PR-1~4。说"一行代码没动"会让后续 PR 误判基线。

---

## 1. 分支性质

| 项 | 值 |
|---|---|
| `main` HEAD | `3debf78`（11-07 11:03，实验级暂停真停修复） |
| 分叉点 | `ba556d0`（11-06 16:50） |
| 分支 HEAD | `4774af0`（11-07 16:21，`docs: specify transactional Island boundary enforcement`） |
| 分支独有提交 | 约 40 个，前段是 P0 代码修复，后段全是文档 |
| `main` 独有提交 | **1 个：`3debf78`** |
| 分支相对 `main` | 55 文件 / +3492 / −2321（含 6 份新架构文档） |

---

## 2. 🔴 阻塞项：分支与 `main` 已发生语义分叉

`3debf78` 是在分叉之后提到 `main` 的，**从未合入评审分支**。两边又都改了同一批核心文件（`WorkflowService` / `RunControl` / `RuntimeHold` / `GraphRunner` / `Designer.tsx` / `ci-gate.sh` / e2e 脚本 / 测试）。

### 2.1 逐项对比（均已用 `git show` / `git grep` 核实）

| 能力 | `main` (3debf78) | review 分支 | 后果 |
|---|---|---|---|
| 实验级暂停 → 底层 workflow run | 三层都 `paused`、后续节点 `pending` | `withPauseChecker(() -> isRunPaused(runId))`，只查 `run.status` | **分支上 bug 仍在**：暂停发出后节点照跑完 |
| `RuntimeHold.coveringForWorkflow()` | 有（按 `experiment_run_step.status='running'` 反查） | `git grep` 0 命中 | 底层流程无法感知实验级扣留 |
| `WorkflowService.pauseRunAtBoundary` | 有 | 0 命中 | 同上 |
| `RunControl.resume()` 作废旧 paused run | 有（26 行） | 无 | **恢复后值班员误点「继续」会重做同一设备动作**，物理副作用不可撤销 |
| `MIN_PAUSEEXP` | `5`，注释「三层都停 paused，旧口径是 bug，已修」 | `2`，注释「workflow 级 run 独立跑完（语义要求，实现真停后也不该变）」 | **同一断言两边判定完全相反**，无法自动合并 |
| `MIN_INTR=3` 段 4.18 | 「A1 真停·可中断方法命令真被中止」 | 「安全边界暂停·TCP 无物理 Abort 时当前动作完成、下一 task 暂停」 | **同编号换了测试内容**；`ci-gate.sh` 段 0 的「MIN_* 只升不降」因阈值仍是 3 而发现不了 |
| `MIN_PURE` / `MIN_MATRIX` | 651 / 55 | 648 / 45 | 合回 main 时门禁直接判红 |
| GraphRunner 异常 fallback 重跑 | **仍有**（`WorkflowService.java:852-855` catch 后 `sm.executeAll`） | **已修**（`executeGraphWithParseFallback`，仅解析失败可退） | 分支的修复不能被丢掉 |
| 同 run worker 并发护栏 | 无 | 有 + `WorkflowExecutionSafetySelfTest.java` | 同上 |
| TCP abort 契约 | `void abort()` 尽力而为 | `supportsPhysicalAbort()` 默认 false + `DeviceDriverAbortContractSelfTest.java` | 同上 |

**结论：两边各有对方没有的修复，必须取并集，不能整表覆盖，也不能简单 `git checkout` 某一边。** 这也是"讨论决定 vs 开发按旧逻辑实现"的现实版本——只不过这次是反方向：主干已修，评审分支停在过去。

### 2.2 冲突裁决原则（建议）

1. 暂停语义以 `main` 的 `3debf78` 为准（已过门禁：2.7 矩阵 55/0、4.17 实验级暂停 5/0、4.18 A1 真停 5/0），补回 `coveringForWorkflow` / `pauseRunAtBoundary` / `resume` 作废旧 run。
2. 分支的 P0 三项保守修复全部保留。
3. `ci-gate.sh`：恢复 `MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5`；分支新增的「安全边界暂停」断言**另起新段号 + 新 `MIN_*` 变量**，不要占用 4.18 / `MIN_INTR`。两套 e2e 都要留。
4. 补一条 e2e 锁死「三层都停 + resume 作废旧 run」，作为合并验收条件。
5. 合并后跑全量 `ci-gate.sh --all`（无 SKIP）+ 4.17 / 4.18 / 新段，留证后再开后续 PR。
6. 在整改计划里登记这次分叉，作为「基线不对齐导致修复回退」的案例。

---

## 3. 文档体系：你点的两件事，具体缺口如下

### 3.1 编号空间已经冲突（最影响协作）

同一个 `P0-1` 至少有三种含义：

- 整改计划 §「Batch 0.4 实施前的现有代码核对」：`P0-0`~`P0-5` = 安全闸门 fail-closed / 暂停事务原子化 / 异步收敛 / Hold 粒度 / 恢复门禁 / 兼容迁移
- 整改计划 §「下一步代码调查工作包」D 段：`P0-1`/`P0-2`/`P0-3` = 修闸门查询 / 统一事务 API / API 返回 request accepted
- 整改计划 §「Batch 0.6」：`P0-6`/`P0-7`/`P0-8` = 编辑期边界 / Experiment 一致性 / 逐 Island Hold
- 另有：Island 审计 `P0-A`/`P0-B`/`P0-C`、实施规格 `PR-1`~`PR-4`、ADR §8 `Batch 0`~`3`、A–F 审计 §8 顺序 `1`~`8`、测试 `P-01`~`16` / `FS-01`~`12`

⇒ 现在说「先做 P0-1」无法唯一确定工作项。这不是排版问题，是会导致 PR 做错事的工程问题。

### 3.2 状态口径分散在至少 4 处

- 整改计划 §「Batch 0 当前状态」有 8 个 checkbox（**唯一真正的状态表**）
- Batch 0.4 / 0.5 / 0.6 的状态只写在标题括号里（"设计已补充，代码未实施"）
- 实施规格开头写 `Status: design/specification only`
- A–F 审计 §8、ADR §8 又各有一套独立优先级清单

且这些状态都没有「证据列」（CI run / e2e / HIL），容易再次变成"讨论了但没做"。

### 3.3 🔴 你强调的原则，在实施规格里已经有被压平的风险

ADR §14.1 已经把领域对象拆成 6 类：`Island/Workcell`、`RobotArm`、`SafetyDomain`、`Resource/Position`、`ExecutionPlan Binding`、`Dependency Edge`。但：

- 实施规格 §3 的 `BoundaryViolation` 只有 `expectedIslandId` / `actualIslandId` 两个 `Long`，4 个 validator 方法全部按 island 相等判断；
- §1 invariant 4 写「A physical Experiment belongs to **exactly one** Island」，而 ADR §13.5.3 写「实验/流程**可跨** Workcell」、§14.5 写「同一 ExperimentRun 可部分受影响」，整改计划 P0-7 又要求 Experiment 与 Workflow 单岛一致 —— **文档内部对"实验能否跨岛"是矛盾的**；
- 代码级证据：`WorkflowService.createWorkflow` 注释写「写 attr 而不是加列，避免为展示偏好改 DDL；旧流程 attr 里没有这个键，设计器回落「全部岛」」。也就是说**当前 island 在代码里就是"展示偏好"**。PR-1 要把它变成权威字段，必须一并改掉这段语义和依赖它的 `e2e-sim-flow`。

⇒ 如果直接按实施规格 PR-2 落地，会产出一个纯 `island_id == island_id` 的校验器，把 Workcell / Functional Island / SafetyDomain 全糊成一个字段判断——正是你警告的情况。**这个矛盾必须在 ADR 层先裁决，再写 validator 接口。**

### 3.4 文档与代码基线不同步（已经发生）

| 现象 | 证据 |
|---|---|
| 状态模型 §12 标题写「基于 **main** 源码核对」，但文档在 review 分支且两边已分叉 | 文档 `:223`；分支缺 `coveringForWorkflow` |
| A–F 审计 §10 用行号做锚点，分支 P0 改动后已漂移 | 如 `WorkflowService.java 740–765`；分支该文件改动 252 行 |
| 实施规格 §2 的行号反而是准的 | 我核对 `QueryService.java` 1358 / 1375 / 1415 / 2335 / 2450 与分支一致 |
| 分支 `RuntimeHold.java:306` 旧注释「实验级暂停**不改** workflow 级 run 状态」与 main 修复矛盾 | 子代理核对 |
| `QueryService.java:2650` javadoc「workflow_node 没有 resource_id 列」与 DDL 矛盾 | `96_schema_fix.sql:125` 已加该列 |
| ADR §14 有两个 `### 14.2` | 556 行「当前源码差距」、571 行「故障影响传播算法」 |

---

## 4. 已经可以钉死的 UNKNOWN（不必再调查，PR-1 可直接写 DDL）

实施规格 §4/§9 把「完整迁移历史 / 是否已有权威 Island 列」标为 UNKNOWN。我核对 `pg_rebuild/schema/` 全量 SQL 后可以关闭：

- **`workflow` 表没有独立 `island_id` 列**，只有 `attr jsonb` 里的 `island_id` 键（`20_kernel.sql:189-205`、`90_all_in_one.sql:441-457`）；全仓**没有任何** `ALTER TABLE workflow ... ADD COLUMN island_id`。
- **`experiment` 表有 `island_id` 列**（`96_schema_fix.sql:142`、`90_all_in_one.sql:2780`），`RuntimeHold.coveringForWorkflow` 的 SQL 也用 `COALESCE(e.island_id,0)` 佐证。
- **`uk_runtime_hold_active` 唯一索引存在**（`98_m95_run_hold.sql:65`），限制同 tenant/scope/scope_id 只有一条活动 Hold ⇒ ADR-PAUSE-005「多 Hold 并存、按 hold_id 清除」必须连带改这个索引，不能只改 Java。
- 实施规格 §2 引用的方法在分支当前代码中位置一致，审计结论仍成立。

⇒ PR-1 的「Inspect complete migration history」可以改成「已确认需新增列」，省掉一轮调查。

---

## 5. 建议的修改顺序

### Step 0 — 基线对齐（阻塞项，不写新业务代码）

1. 在分支上执行 `git merge main`（分支已 push 且多人评审，**不要 rebase**；也不要拿任何一边整表覆盖）。
2. 按 §2.2 逐文件裁决，取两边修复的并集。
3. `ci-gate.sh` 门禁按 §2.2 第 3 条处理，两套 e2e 并存。
4. 补 e2e 锁死 main 的暂停修复；全量门禁跑绿留证。
5. 整改计划登记本次分叉与裁决。

### Step 1 — 文档 PR：建立单一真源（你建议的落地形态）

- 新增 **`docs/architecture/ADR_INDEX.md`**：统一注册表，字段建议 = `ADR 编号 / 标题 / 状态(PROPOSED|ACCEPT|SUPERSEDED) / 决策摘要 / 来源文档 / 受影响代码与 PR / 复核日期`。现有 `ADR-PAUSE-001~007`、`ADR-FAULT-SCOPE-001` 迁入，新增 `ADR-FAULT-SCOPE-002`（下条）。
- 新增 **`docs/architecture/REMEDIATION_STATUS.md`**：唯一状态表，字段建议 = `工作项ID / 优先级 / 所属批次 / ADR依据 / 代码状态(NOT_STARTED|IN_PR|DONE|VERIFIED) / 测试证据 / HIL证据 / PR链接 / 负责人 / 更新日期`。把整改计划 8 个 checkbox、Batch 0.4/0.5/0.6、实施规格 PR-1~4、Island 审计 P0-A~C、A–F 审计 §8 全部映射为**唯一 ID**；其余文档只写设计，状态一律引用此表。
- **裁决「实验/流程能否跨 Workcell」**，写 `ADR-FAULT-SCOPE-002：禁止用单一 `island_id` 相等判断代替领域边界`。据此修改实施规格 §3：`BoundaryViolation` 增加 `boundaryKind`（WORKCELL / SAFETY_DOMAIN / RESOURCE / ROBOT_REACHABILITY / DEPENDENCY）、`scopeType`、`evidence`；validator 不再只返回两个 `Long`。同时明确 Functional Island 何时才引入（对齐 ADR §14.1）。
- 修 §14.2 重复编号、状态模型 §12 基线标注、A–F 审计 §10 行号改符号锚点（方法名而非行号）。
- PR 模板增加三必填：`ADR 编号`、`状态表行更新`、`证据（CI/e2e/HIL）`。

### Step 2 — PR-1：schema + 只读盘点（无 enforcement）

- 新增 `workflow.island_id` + FK + `(tenant_id, island_id)` 索引；从 `attr` 安全回填（仅值可解析且同租户 Island 存在时）；`attr` 暂作兼容镜像，读写同事务，读优先列。
- 只读 inventory 报告：流程总数 / 有效 attr / 缺失 / 非法 / 异岛物理节点 / 引用这些流程的 Experiment。
- 一并改掉 `createWorkflow`「回落全部岛」语义；注意 `e2e-sim-flow` 依赖旧行为，需同步迁移。
- 不做自动推断、不做破坏性迁移、不启用 enforcement。

### Step 3 — PR-2：validator + 事务化写入路径

- 用 Step 1 修正后的接口（不是原 §3 的两 `Long` 版本）。
- `createWorkflow` 单事务（行 + Island + 初始图一起提交）；`backupGraph` 移入 `saveGraph` 事务；`SELECT ... FOR UPDATE` 按 Experiment → Workflows(按 ID 排序) → resource 一致顺序加锁。
- 覆盖 `saveGraph` / `updateNodeBinding` / `restore` / Experiment create / `replaceExperimentProcesses` / `saveExperimentGraph` / choreography build。
- **先定案 `updateNodeBinding` 的 null 语义**（实施规格 §2.3 已指出：Javadoc 说"不改绑定"，代码写 SQL NULL），否则测试 6 无法写。
- 落实施规格 §7 的 12 条 DB/API 集成测试。
- 补整改计划 0.1 漏列的一项：run execution phase 标记 `PREPARED / DISPATCH_STARTED / SETTLED`（`[ ]` 清单里没有，容易漏）。

### Step 4 — Hold 三态 + 逐 Island 调度（独立 PR，**不与 Step 2/3 混合**，实施规格 §8 明确要求）

- `RuntimeHold` 返回 `CLEAR / HELD / UNKNOWN`，SQL 异常一律 UNKNOWN 且 fail-closed；去掉 `dispatch` 的全队列 `anyActive`，改逐 `Queued` 按 `experimentId/islandId/SafetyDomain` 判定；`autoContinueHeldBatches` 同样逐候选。
- 依赖 Step 0 合并结果（两边都改过 `RuntimeHold`）。
- 连带处理 `uk_runtime_hold_active` 唯一索引，才能落地 ADR-PAUSE-005 的多 Hold 并存。

### Step 5 → Step 7

编辑器 UX（Island 锁定 / 严格设备池 / 拒绝无机械臂直连）→ compile & runtime 严格边界（移除 `WorkflowService.withIsland` 的全局候选回退）→ HIL 与故障注入。

---

## 6. 需要你先拍板的两个问题

1. **实验/流程是否允许跨 Workcell？** ADR §13.5.3 说可跨，整改计划 P0-7 与实施规格 invariant 4 说单岛。这个不裁决，`workflow.island_id` 是单值还是关联表、validator 怎么设计、PR-1/PR-2 的数据模型都无法定。
2. **`main` 的 4.18「A1 真停」与分支「TCP 无 Abort 时安全边界暂停」是否都保留？** 建议都保留，拆成两个门禁段、两个 `MIN_*` 变量（一个证明可中断方法能真停，一个证明无物理 Abort 能力时不谎报停止、不重派发）。

---

## 7. 对你那条建议的补充

完全同意「维护统一的 ADR 和整改状态表」，另加两点：

- 状态表必须带**证据列**。只有 checkbox 没有 CI/e2e/HIL 证据，仍会出现"标了已完成、实际按旧逻辑跑"。
- 建议把「**基线对齐与合并纪律**」本身也写成一条 ADR（例如 `ADR-PROCESS-001`）：评审分支不得长期游离于 `main`，每个 PR 开工前先对齐；禁止整表覆盖；门禁阈值与断言语义变更必须新增段号而非复用。这次分叉已经证明，讨论再完整，基线不对齐一样会把修复弄丢。

---

## 8. 用户裁决与方案修订（v2，2026-11-07）

用户四条决策：

1. **流程（Workflow）不允许跨 WorkCell；实验（Experiment）允许跨 WorkCell。**
2. **`main` 的 bug 修复要同步过去。**
3. **分支里的思考和解决办法，要放到 `main` 上来执行。**
4. **分支里提到的外部对标产品，不能出现商业公司或产品名称。**

### 8.1 第 1 条会反转多条已定 P0 结论（影响最大）

原先「Experiment 必须与其引用的每个 Workflow 同岛」被当成 P0 缺口，现在整条作废：

| 作废/反转 | 出处 |
|---|---|
| P0-7「Experiment / Workflow Island 一致性」整项作废 | 整改计划 Batch 0.6 |
| §8「Experiment 组合阶段也没有 Island 一致性校验 — CONFIRMED / P0」整节结论撤销 | Island 边界与 Hold 调度专项审计 |
| 实施规格 §1 invariant 4、§2.5、§3 `validateExperimentWorkflows`、§6 四行、§7 测试 8/9/10 期望反转 | Island 边界实施规格 |
| ADR §13.5.3「实验/流程可跨 Workcell」修正为「实验可跨、流程不可跨」 | 外部实践与 ADR |
| P0-B 第 1 条 / A-02 的修复方向改为「按 Workflow 权威 Workcell 取上下文」 | 专项审计、A–F 审计 |

**并且带出四个新工程缺口**（`REMEDIATION_STATUS.md` N-01~N-04）：

- `experiment.island_id` 是单值列（`96_schema_fix.sql:142`），**无法表达跨 Workcell 实验**；需 Experiment↔Workcell 关联，原列只能保留为「主/默认 Workcell」的展示与兼容语义。
- `ExperimentScheduler.tryLockIsland` 以单 `islandId` 为队列与 JVM 内存锁键 ⇒ 跨 Workcell 实验须按 **Workcell 集合**加锁、稳定排序；2h TTL 内存锁仍不是物理互斥。
- `RuntimeHold.coveringForWorkflow` 用 `COALESCE(e.island_id,0)` 判覆盖 ⇒ 跨 Workcell 实验会漏掉非主 Workcell；须按节点实际 Workcell + SafetyDomain + 依赖闭包判定。
- 跨 Workcell 板流必须在实验编排层显式 Handoff（源/目标、转运执行者、板/样本身份、物理到位证据）；AGV 当前不纳入范围 ⇒ **转运方式成为新的待拍板项 Q-01**。

§0 提出的「Island 不能压平成一个 `island_id` 判断」在新裁决下更关键，已固化为 `ADR-ISLAND-003`：`BoundaryViolation` 必须带 `boundaryKind` / `scopeType` / `evidence`，不得只比较两个 `Long`。

### 8.2 第 2、3 条：合并策略反转

由「把 main 合回分支、在分支上继续开 PR」改为 **`main` 唯一执行基线，评审分支设计归档**（`ADR-PROCESS-001`）：

- `3debf78`（M-01）同步到评审分支（G-01），使归档分支不再基于落后代码被引用；
- 分支 P0 三项修复（B-01~B-03）以独立 PR 搬到 `main`，与 M-01 **取并集**；
- 门禁拆段（G-02）：恢复 `651/55/5`，4.18「A1 真停」保留，分支「安全边界暂停」另起新段号 + 新 `MIN_*`；补 G-03 回归 e2e；
- 分支打 tag 冻结（G-04），后续讨论与改动回写 `main` 上的文档。

> **本节只给方案，未执行 git 合并/cherry-pick/push**。这些操作会改远端分支，需要你授权后按 `REMEDIATION_STATUS.md` Step 0 执行。

### 8.3 第 4 条：已执行的脱敏

- 6 份文档已从评审分支归档到 `main` 工作区 `docs/architecture/`，**原文思考与源码证据保留**，每份头部加「执行基线说明」、文末加「裁决影响（作废/保留/新增）」，不悄悄改写历史结论。
- 商业公司/产品名与厂商链接已全部移除：标题、§13.1/13.2/13.3、§13.4 表头、§15 参考资料整节。外部实践改称「产品形态 A / B / C」；ISA-88、ROS 2、SiLA 2、OPC UA、Airflow、Temporal 等标准/开源技术名保留。
- 校验：按禁用关键词清单对 `docs/architecture/` 全量检索 → **0 命中**（清单只在本地校验脚本维护，不写入仓库）。
- 已固化 `ADR-DOC-001`，并把「无商业公司/产品名」加进 PR review checklist 与模板。

### 8.4 本轮落地的文件（均在 `main` 工作区，未提交）

| 文件 | 用途 |
|---|---|
| `docs/architecture/ADR_INDEX.md` | 统一 ADR 注册表（含 5 条新决策、SUPERSEDED 清单、OPEN 问题 Q-01~Q-07） |
| `docs/architecture/REMEDIATION_STATUS.md` | 唯一整改状态表（M/B/G/N/P/X 六组 + 旧编号映射 + 执行顺序 + PR 模板） |
| `docs/architecture/LABOS_*_20261107.md`（6 份） | 脱敏 + 裁决声明后的归档设计/审计文档 |
| `docs/LabOS_review分支评审与下一步修改方案_20261107.md` | 本文（v2） |

### 8.5 本轮已裁决

上列 Q-01 / Q-02 / Q-07 已由用户 2026-11-07 答复，固化为 `ADR-ISLAND-004` / `ADR-ISLAND-005` / `ADR-PROCESS-002`；由此带出的「分层是否加进 main」固化为 `ADR-LAYER-001`。详见 **§9**。

---

## 9. 分层落地评估（v3）：main 上完善吗？要把分层加进来吗？

**直接回答：不完善，需要加，但不能一次性全加。**

### 9.1 main 源码盘点（本轮只读核对，HEAD=`3debf78`）

| 分层 | main 现状 |
|---|---|
| 状态三层（ExperimentRun / NodeExecution / DeviceCommand） | 只有 `run` / `run_node` / `experiment_run` / `experiment_run_step` 的**单 `status` 字段**，无对应领域类；`PAUSE_REQUESTED` / `PAUSING` / `WAITING_INTERVENTION` / `RESUMING` 在 Java/SQL/TSX/Python **全仓 0 命中**，只存在于设计文档；`experiment_run.status varchar(16)` 放不下 20 字符的 `WAITING_INTERVENTION` |
| 四正交事实 | 只有 **Hold Ledger**（`runtime_hold`）是独立实体；Run Control 挤在 status + 5 控制列；Command 靠复用 `node_status` 的 `run_command`、步结束才落库、无 Outbox；Alarm/Intervention 散在 `alert_state` / `manual_window` / `error_occurrence` / `run_event`，无 `InterventionRecord` |
| 六类领域对象 | **0 实体**。`island` 只是 `resource_node.kind` 的枚举值；`SafetyDomain` / `DependencyEdge` / `ExecutionPlan` / `Workcell` / `Handoff` 全 0 命中；`e.island_id` 在 7+ 处是执行判定核心（暂停作用域、扣留覆盖、队列与岛锁、故障隔离） |
| 人工 Handoff 能力 | `manual_window` 表 + `ManualWindow` 类 + `GET/POST /api/manual-windows` 后端齐全，但 `webapp` **0 处消费**；唯一人工确认是手填 barcode 的板位盘点，无扫码/传感器/样本到位入口；配置期设备/IP 入口齐全但**无任何 Handoff 结构** |
| 门禁 | 最大段号 **4.18**，只验「可中断方法执行中发暂停 → 命令 aborted、run paused」，不覆盖任何分层状态或跨 Workcell 衔接 |

⇒ 设计文档里的分层目前**完全是纸面的**，代码是「单 status 字段 + 单 `island_id` 列」的扁平模型。所以：要加，而且这就是当前最大的实现缺口。

### 9.2 但分三批（`ADR-LAYER-001`）

| 批次 | 内容 | 状态表 ID |
|---|---|---|
| 第 1 批（阻塞跨 Workcell，必须先加） | Workflow 权威 Workcell 列、Experiment↔Workcell 关联、配置期 Handoff 结构、运行时人工确认门禁、`InterventionRecord` | N-01 / N-04 / N-07 / N-08 / L-01 / L-04 |
| 第 2 批（同期最小骨架，否则状态语义落不了地） | `experiment_run.status` 扩容或短 code + display label、`PAUSE_REQUESTED`/`PAUSING` 最小中间态、CommandExecutionState 最小账本 + 最小 Outbox、人工窗口前端页 | L-02 / L-03 / P-06 / P-07 |
| 第 3 批（**暂不建全套**） | SafetyDomain 传播算法、RobotArm 工作空间互斥、Dependency Edge 完整图、完整聚合器投影 | L-05 = DEFERRED |

两条理由：

1. 整改计划总原则是「不大规模重写执行链，一次一个小 PR」；**ADR-ISLAND-003 要求概念分离，不等于一次性建 6 张表**。当前只需「依赖闭包 UNKNOWN ⇒ fail-closed」的最小实现。
2. Q-01 把 Handoff 定为人工后，「人工确认记录 + 执行门禁」正好就是 `ADR-PAUSE-003` 的 `InterventionRecord`。**这次不一起做，后面必然出现两套人工记录**，违反 `ADR-PAUSE-007`（不新建第三套子系统）。

### 9.3 三条答复带来的具体变化

- **Q-01（人工 Handoff）**：N-04 改写为「配置期静态定义 + 运行时人工确认」，不做 AGV / 自动可达性 / 路径规划。**配置过只证明「允许这么走」，不证明「这次板真的到了」**；无本次确认记录 ⇒ `UNKNOWN` ⇒ 阻断后续节点。复用 `manual_window` + `waiting_human`，不新建 Handoff 状态机。新增 N-07（配置结构，`experiment_process_edge` 或新表）、N-08（门禁）、L-01（到位记录）、L-04（前端页）。
- **Q-02（岛名展示）**：岛名字段已存在（`resource_node.name`，`QueryService.java:714` 已有 `isl.name AS island_name`），**UI 改动很小**；但 `e.island_id` 在 `RunControl.java:60,136`、`RuntimeHold.java:329`、`ExperimentScheduler.java:226,845`、`ExperimentExecutor.java:704`、`BatchOrchestrator.java:1475` 是执行判定核心 ⇒ **N-01/N-02/N-03 的真正工作量在这些读取点，不能只改 UI**。`workflow.island_id`（权威执行边界）与 `experiment.island_id`（区分/展示）语义必须分开。
- **Q-07（段号）**：4.18 原样保留 `MIN_INTR=3`；新增 **4.19「安全边界暂停 · TCP 无物理 Abort」+ `MIN_SAFEBOUND`**，脚本另起 `e2e-safebound-pause.py`（分支改的是 `e2e-interrupt-during-device.py`，直接合会互相覆盖）；skip 文案改 `4.5-4.19`。理由是 `ci-gate.sh:100-118` 段 0 只校验 `MIN_*` 是否被下调，**复用同一变量名做语义替换不会亮红灯**——分叉时就是这样静默发生的。

### 9.4 仍需拍板 / 可在 PR 内定

- **需你拍板**：Q-03（多 Workcell 锁集合与获取顺序、2h TTL）、Q-04（Hold/SafetyDomain 覆盖粒度：按节点 / Workflow 段 / Handoff 边界）、Q-05（Functional Island 何时引入，可延后）。
- **可在 PR 内定**：Q-09（人工确认落新建 `InterventionRecord` 还是扩 `manual_window`——后者只对应 `waiting_human`，`decided_action` 白名单无「样本到位确认」）、Q-10（Handoff 配置挂扩展 `experiment_process_edge` 还是新建 `experiment_handoff`）。
- **未执行的 git 操作**：G-01~G-04（同步 `3debf78` 到分支、门禁拆段、补回归 e2e、分支打 tag）会改远端，仍需你授权。
