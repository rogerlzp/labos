# LabOS 整改状态表（唯一状态真源）

日期：2026-11-07
基线：`main` @ `3debf78`（ADR-PROCESS-001）
关联：`ADR_INDEX.md`（唯一决策真源）、`docs/architecture/` 下 6 份归档审计与设计文档。

**本表是唯一状态真源。** 其它文档（整改计划、实施规格、专项审计、A–F 审计、ADR §8）只保留思考与源码证据，其状态描述一律引用本表；不得再各自维护 checkbox 或优先级清单。

状态枚举：`NOT_STARTED` → `IN_PR` → `DONE`（代码已合入 main）→ `VERIFIED`（有 CI/e2e/HIL 证据）。
`SUPERSEDED` = 被 `ADR_INDEX.md` 的新裁决作废，不得实施。

证据规则：标 `DONE` 必须给 PR；标 `VERIFIED` 必须给 CI / e2e / HIL 证据路径或 run id；真机安全项必须有型号、固件、方法、负载、停止后状态。

---

## 1. M 组：`main` 已完成（保持，不得回退）

| ID | 工作项 | 状态 | 证据 |
|---|---|---|---|
| M-01 | 实验级暂停三层真停（`experiment_run` / `experiment` / 底层 workflow `run` 都 `paused`，后续节点 `pending`）+ `RunControl.resume` 作废旧 paused run + `RuntimeHold.coveringForWorkflow` 反查 | VERIFIED | `3debf78`；ci-gate 2.7 矩阵 55/0、4.17 实验级暂停 5/0、4.18 A1 真停 5/0；`MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5` |

> 合并裁决：**任何分支代码合入 `main` 时，M-01 的所有符号必须仍然存在**。冲突文件：`WorkflowService.java`、`RunControl.java`、`RuntimeHold.java`、`GraphRunner.java`、`DriverStepExecutor.java`、`ci-gate.sh`、`e2e-pause-during-experiment.py`、`e2e-pause-resume-nodes.py`、`GraphRunnerTest.java`、`RunControlDbSelfTest.java`。

## 2. B 组：评审分支已有、需搬到 `main`（取并集，见 ADR-PROCESS-001）

| ID | 工作项 | 分支证据 | 状态 | 目标 PR |
|---|---|---|---|---|
| B-01 | GraphRunner 执行期 RuntimeException 不再 fallback 到 `executeAll` 顺序重跑 | `WorkflowService.executeGraphWithParseFallback` | NOT_STARTED(on main) | PR-B1 |
| B-02 | TCP 无物理 Abort 时不以本地 Future 取消冒充设备已停止 | `DeviceDriver.supportsPhysicalAbort()` 默认 false、`DeviceDriverAbortContractSelfTest.java` | NOT_STARTED(on main) | PR-B2 |
| B-03 | 同一 JVM 内同一 run 的后台 worker 禁止并发，旧 worker 未退出时拒绝 resume | `WorkflowExecutionSafetySelfTest.java` | NOT_STARTED(on main) | PR-B3 |
| B-04 | 结构化 `AbortResult`（UNSUPPORTED / REQUESTED / DEVICE_ACKNOWLEDGED / PHYSICALLY_STOPPED / UNKNOWN） | 分支未实现，整改计划 Batch 0.2 `[ ]` | NOT_STARTED | PR-B4 |

## 3. G 组：基线对齐与门禁（先于一切实施）

| ID | 工作项 | 状态 | 验收 |
|---|---|---|---|
| G-01 | 把 `main` 的 `3debf78` 同步到评审分支（分支仅归档，不再实施） | NOT_STARTED（需 push 授权） | 分支 `git grep coveringForWorkflow` 非空、`MIN_PAUSEEXP=5` |
| G-02 | 门禁拆段（ADR-PROCESS-002）：恢复 `MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5`；**4.18 原样保留 `MIN_INTR=3`（A1 真停）**；新增 **4.19「安全边界暂停 · TCP 无物理 Abort」+ `MIN_SAFEBOUND`**，脚本另起 `e2e-safebound-pause.py`（避免与分支改过的 `e2e-interrupt-during-device.py` 互相覆盖）；skip 文案 `4.5-4.18` → `4.5-4.19` | NOT_STARTED | 两套 e2e 并存、段 0「只升不降」通过 |
| G-03 | 补 e2e 锁死「三层都停 + resume 作废旧 run」，作为 B 组合入的回归条件 | NOT_STARTED | 全量 `ci-gate.sh --all` 无 SKIP 通过 |
| G-04 | 评审分支打 tag 冻结为设计归档 | NOT_STARTED | tag + `ADR_INDEX.md` 标注 |

## 4. N 组：本次裁决新增（ADR-ISLAND-001~005）

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| N-01 | 跨 Workcell 实验数据模型：Experiment↔Workcell 关联 / ExecutionPlan 绑定 | Q-02 **已裁决** | NOT_STARTED | 按 ADR-ISLAND-005：`experiment.island_id` 仅内部分区键 + 岛名展示，**不承担「主 Workcell」语义、不参与执行判定**；先出只读盘点，不自动推断、不破坏性迁移 |
| N-02 | 执行上下文按**当前 Workflow 的权威 Workcell** 构造；`ExperimentExecutor.runStep` 不再依赖 `experiment.island_id` | N-01、P-01 | NOT_STARTED | 取代原 P0-B 第 1 条与 A-02 修复方向；须同步改 `RunControl` / `RuntimeHold` / `ExperimentScheduler` / `BatchOrchestrator` 的 7+ 处 `e.island_id` 执行判定 |
| N-03 | **已改写（ADR-LOCK-001）**：分布式资源锁 = **Redis + PG 联合、按资源粒度**（设备 / 机械臂 / 板位 / 端口 / 共享工作空间 / 转运路径）；PG 为权威事实层（行锁、唯一约束、`pg_advisory_xact_lock`、fencing token 同事务），Redis 为加速与跨实例协调层且**不作为唯一真相源**；**废弃 `islandId` 锁键 / JVM `ConcurrentHashMap` / 2h TTL** | Q-03 **已裁决**、Q-12、N-01 | NOT_STARTED | 实施前查清自研 `RedisClient` 是否支持 `SET NX PX` / Lua 原子释放 / 续约（Q-12）；租约到期只释放软件租约、不释放物理隔离；崩溃/分区 ⇒ `UNKNOWN/QUARANTINED`，不自动重派发；可复用 `QueryService.java:1585` 的 advisory lock 与 `BatchOrchestrator` 的 `FOR UPDATE` |
| N-10 | **新增（ADR-FAULT-SCOPE-002）**：故障影响闭包以 **ExperimentRun 为最小单位**——任一所属 Workcell 故障/扣留 ⇒ 该实验全部节点、全部 Workflow 段、全部 Handoff 边界停止派发；**不允许 B 段继续** | Q-04 **已裁决**、P-03、N-03 | NOT_STARTED | 其他**独立实验**仍按 `ADR-FAULT-SCOPE-001`（Workcell 隔离 + Continue Predicate + SafetyDomain）；`RuntimeHold` 覆盖判定、`ExperimentScheduler` 逐候选评估、`autoContinueHeldBatches` 都不得因「节点在另一个 Workcell」放行同一实验 |
| N-04 | **已改写**：跨 Workcell Handoff = **配置期静态定义**（源/目标 Workcell、交接点、板与样本身份、执行方式=人工、所需确认项）+ **运行时人工确认门禁**；到位证据为人工确认记录 | Q-01 **已裁决** | NOT_STARTED | ADR-ISLAND-004：**不做自动可达性 / AGV / 路径规划**。配置只证明「允许这么走」，不证明「这次板真的到了」；无确认记录 ⇒ UNKNOWN 阻断 |
| N-05 | `BoundaryResult` / `BoundaryViolation` 扩展 `boundaryKind` / `scopeType` / `evidence`，区分 INVALID / UNKNOWN | ADR-ISLAND-003 | NOT_STARTED | 取代实施规格 §3 的两 `Long` 接口；P-02 的前置 |
| N-06 | 期望反转的测试：Experiment A 引用 Workflow B，有合法 Handoff 且人工确认后允许；Handoff 缺失 / 确认缺失 / UNKNOWN 时阻断 | N-04 | NOT_STARTED | 取代实施规格 §7 测试 8/9/10 的旧期望 |
| N-07 | 配置期 Handoff 持久化结构：扩展 `experiment_process_edge` 或新建 `experiment_handoff`（Q-10），在岛内设备/IP 配置入口同处录入 | Q-10、N-04 | NOT_STARTED | `island_flow.graph` 边只落 `{id,source,target}` 且属**岛内动线**，不可直接复用；`experiment_process_edge` 现注释「只表达偏序，不表达资源」 |
| N-08 | 运行时人工确认门禁：复用 `manual_window` + `pause_kind='waiting_human'` + `GET/POST /api/manual-windows`，补前端消费页（现 `webapp` 0 命中） | Q-09、N-04、ADR-PAUSE-007 | NOT_STARTED | **不新建第三套 Handoff 状态机**；确认人/时间/板样本标识/交接点/结果落 L-01 |
| N-09 | 界面口径：不显示岛 ID，只显示岛名 `resource_node.name`；跨 Workcell 实验展示**涉及岛名列表**；改 `ExperimentList.tsx:169,171`、`Scheduler.tsx:171,232` 等 | ADR-ISLAND-005 | NOT_STARTED | 岛名字段已存在（`QueryService.java:714` `isl.name AS island_name`），改动小 |

## 4a. L 组：分层最小骨架（ADR-LAYER-001）

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| L-01 | `InterventionRecord` 落证：确认人、时间、板/样本标识、源/目标交接点、确认结果 | Q-09、N-08 | NOT_STARTED | **与 N-08 同批**，借人工 Handoff 一起做，避免两套人工记录（ADR-PAUSE-003 / 007） |
| L-02 | `experiment_run.status varchar(16)` 扩容或改短 code + display label；落 `PAUSE_REQUESTED` / `PAUSING` 最小中间态 | ADR-PAUSE-001/003 | NOT_STARTED | `WAITING_INTERVENTION` 20 字符现有列放不下；四个中间态在 main 全仓 0 命中 |
| L-03 | CommandExecutionState 最小账本 + 最小 Outbox | ADR-PAUSE-006 | NOT_STARTED | `run_command` 现复用 `node_status` 且步结束才落库；**并入 P-07，不另起一套** |
| L-04 | 人工窗口前端消费页 | N-08 | NOT_STARTED | 可与 N-08 同 PR |
| L-05 | SafetyDomain 传播算法 / RobotArm 工作空间互斥 / Dependency Edge 完整图 / 完整聚合器投影 | — | **DEFERRED** | 暂不建全套；当前只需「依赖闭包 UNKNOWN ⇒ fail-closed」最小实现 |

## 4b. S 组：状态机多层维护治理（ADR-STATE-001）

专门文档：`LABOS_STATE_MACHINE_MULTILAYER_20261107.md`（仓库原有 203 份文档无此主题专篇）。

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| S-01 | 两套 run 模型映射表：字段 / 调用链 / `pause_kind` 词表差异 / 创建·关闭·重启恢复行为 / 关联基数 | — | NOT_STARTED | `PauseManager`→`run.status`、`RunControl`→`experiment_run.status` 并存且独立写库；`3debf78` 只是约定式补丁，未合并 |
| S-02 | `PauseCoordinator` 适配层：**与 P-06 是同一项**，本行只补「两套模型映射与适配边界」，不另建第二个 Coordinator | S-01、P-06 | NOT_STARTED | 统一入口 + 单事务写 Hold/意图/事件/Outbox；保留 `M-01` 作废旧 run 契约，不直接删 `PauseManager` |
| S-03 | 事实 vs 投影职责切换：`experiment.status` 只允许聚合器写；`StatusVocabulary` CASE 回写收口 | S-02、P-06 | NOT_STARTED | 现状 3 处直接 UPDATE；`PauseCoordinator` / `ExperimentStateProjector` 在 Java 源码 0 命中 |
| S-04 | ⚠️ **验收口径切换（分两个 PR）**：① 升级 4.17 判据为「三层 status **且** Hold 有效 **且** 在途命令已收敛」，新增「未收敛不得显示 `PAUSED`」断言，旧断言并行通过；② 再切 API 语义 | G-03、L-02 | NOT_STARTED | `docs/测试用例总表.md:51,216` 与 e2e 4.17 现以「三层都 `paused`」为唯一判据，与新设计「status 只是投影」冲突；**禁止同一 PR 同时改实现与判据** |
| S-05 | 原有 26 份状态相关文档索引 + 过时标注（哪些仍是权威、哪些已被 ADR 取代） | — | NOT_STARTED | 避免下一个人把「三层都 paused」当成新设计的验收判据 |

## 5. P 组：保留的整改工作项

| ID | 工作项 | 原编号 | ADR 依据 | 状态 |
|---|---|---|---|---|
| P-01 | `workflow.island_id` 权威列 + FK + `(tenant_id, island_id)` 索引；attr 兼容镜像同事务双写；只读 inventory 报告；改掉 `createWorkflow`「回落全部岛」语义 | PR-1 | ADR-ISLAND-001 | NOT_STARTED（schema 事实已确认：无独立列，`20_kernel.sql:189-205`） |
| P-02 | 统一 `IslandBoundaryValidator`（用 N-05 扩展后的接口）+ 事务化写入路径：`createWorkflow` 单事务、`backupGraph` 入 `saveGraph` 事务、按 Experiment→Workflows→resource 顺序 FOR UPDATE 加锁 | PR-2、P0-6、P0-A | ADR-ISLAND-001/003 | NOT_STARTED |
| P-03 | RuntimeHold `CLEAR/HELD/UNKNOWN` 三态 + 逐 Workcell 候选调度 + `autoContinueHeldBatches` 逐候选 + `uk_runtime_hold_active` 唯一索引改造（支持多 Hold 并存） | P0-8、P0-C、P0-0 | ADR-PAUSE-005、ADR-FAULT-SCOPE-001 | NOT_STARTED（**独立 PR，不与 P-01/P-02 混合**） |
| P-04 | 编辑器 UX：Workflow 加载服务端权威 Workcell 并锁定不可清空；严格设备池；drop 期 Workcell 校验；`LastDropped` 保存归属；无可达机械臂/UNKNOWN 时禁止直连 | PR-3、P0-6 | ADR-ISLAND-001 | NOT_STARTED |
| P-05 | 编译/发布/运行严格边界：移除 `WorkflowService.withIsland()` 的全局候选回退；direct run / choreography / Experiment 执行前重校验配置快照 | PR-4、P0-B、A-03、N-02 | ADR-ISLAND-001/002 | NOT_STARTED |
| P-06 | PauseCoordinator：Hold + 实验级 PAUSE_REQUESTED + 节点暂停请求 + Event/Outbox 单事务；PAUSING 异步收敛；API 返回 request accepted | P0-1(事务)、P0-2、P0-3、Batch 0.4 | ADR-PAUSE-001/002/007 | NOT_STARTED |
| P-07 | 命令账本 + Outbox；`CREATED→…→UNKNOWN→RECONCILING`；四个崩溃点不重复物理动作 | Batch 1、ADR §8 Batch 1 | ADR-PAUSE-006 | NOT_STARTED |
| P-08 | 资源与物理位置三维分离、启动恢复隔离 QUARANTINED、TransferTransaction + 物理确认 | Batch 2 | ADR-FAULT-SCOPE-001 | NOT_STARTED |
| P-09 | run execution phase 标记 `PREPARED / DISPATCH_STARTED / SETTLED`（整改计划 0.1 有要求但 checkbox 清单漏列） | Batch 0.1 | ADR-PAUSE-001 | NOT_STARTED |
| P-10 | `updateNodeBinding` 的 null 语义定案（Javadoc「不改绑定」 vs 代码写 SQL NULL） | 实施规格 §2.3 | — | NOT_STARTED（P-02 前置） |
| P-11 | 前端/文档状态枚举与 `varchar` 长度核实（`WAITING_INTERVENTION` 等长状态） | 状态模型 §12.4 | ADR-PAUSE-003 | NOT_STARTED |
| P-12 | HIL：停止响应丢失、设备拒绝停止、响应丢失、报警风暴/乱序、联锁未复位、人工移板、恢复时旧命令仍运行 | Batch 3、FS-01~12、P-01~16 | 全部 | NOT_STARTED |

## 6. X 组：作废（不得实施）

| ID | 原工作项 | 出处 | 作废依据 |
|---|---|---|---|
| X-01 | Experiment / Workflow Island 一致性校验（要求 `experiment.island_id` 与每个 Workflow 的 island_id 相等） | 整改计划 P0-7、Island 审计 §8、实施规格 §1 inv4 / §2.5 / §3 / §6 / §7 测试 8-10 | **ADR-ISLAND-002**：实验允许跨 Workcell |

> 注意：Island 审计 §8 的**源码事实仍有效**（`validateExperimentWorkflowIds` 只查存在性/租户、`ExperimentExecutor.runStep` 不传 Workcell），已转作 N-02 / N-04 的证据，不是缺陷结论。

---

## 7. 旧编号 → 唯一 ID 映射（解决「P0-1 有多种含义」）

| 旧编号 | 出处 | 唯一 ID |
|---|---|---|
| P0-0（安全闸门 fail-closed） | 整改计划 §Batch 0.4 实施前核对 | P-03 |
| P0-1（暂停事务原子化） | 同上 | P-06 |
| P0-1（修所有安全闸门查询 fail-closed） | 整改计划 §下一步代码调查 D 段 | P-03 |
| P0-2（异步收敛 PAUSING / 统一事务 API） | 两处各一义 | P-06 |
| P0-3（Hold 原因与解除粒度 / API 返回 request accepted） | 两处各一义 | P-03、P-06 |
| P0-4 / P0-5（人工干预门禁 / 兼容迁移） | 整改计划 §Batch 0.4 | P-06、P-11 |
| P0-6（Workflow 固定 Workcell + 编辑期阻断） | 整改计划 §Batch 0.6 | P-01、P-02、P-04 |
| P0-7（Experiment/Workflow 一致性） | 整改计划 §Batch 0.6 | **X-01（作废）** → 由 N-01/N-04/N-06 取代 |
| P0-8（Hold 逐 Island 判定） | 整改计划 §Batch 0.6 | P-03、N-03 |
| P0-A / P0-B / P0-C | Island 边界专项审计 §6 | P-02/P-04、P-05+N-02、P-03+N-03 |
| PR-1 / PR-2 / PR-3 / PR-4 | Island 边界实施规格 §8 | P-01、P-02、P-04、P-05 |
| Batch 0 / 0.4 / 0.5 / 0.6 / 1 / 2 / 3 | 整改计划 | B-01~B-04 / P-06 / P-05 / X-01+N-* / P-07 / P-08 / P-12 |
| Batch 0~3 | 外部实践与 ADR §8 | P-03、P-06、P-07、P-12 |
| 顺序 1~8 | A–F 源码审计 §8 | P-05、P-03、N-02、P-08、P-04、P-07、P-12 |
| P-01~P-16 / FS-01~FS-12 | 状态模型 §9 / ADR §14.6 | 验收用例，非工作项；归入 P-12 |

**规则：后续讨论、PR 标题、提交信息一律只用本表的唯一 ID 或 ADR 编号，不再使用 `P0-x` / `P0-A~C` / `PR-x` / `Batch x` 作为工作项标识。**

---

## 8. 执行顺序（`main` 基线）

| Step | 内容 | 前置 |  gates |
|---|---|---|---|
| 0 | G-01~G-04 基线对齐、门禁拆段、补回归 e2e、分支归档 | push 授权 | 全量 `ci-gate.sh --all` 无 SKIP 通过；M-01 符号仍在 |
| 1 | B-01~B-03 分 3 个小 PR 搬到 main（每项含源码+回归测试+证据+文档） | Step 0 | G-03 回归绿 |
| 2 | 文档 PR：`ADR_INDEX.md`、`REMEDIATION_STATUS.md`、6 份脱敏归档文档合入 main；修 ADR §14.2 重复编号、A–F 审计行号改符号锚点 | — | review checklist 含「无商业公司/产品名」 |
| 3 | 查清 **Q-12**（自研 `RedisClient` 是否支持 `SET NX PX` / Lua 原子释放 / 续约）；N-05 接口定案；Q-05 / Q-09 / Q-10 在对应 PR 内定（Q-03 / Q-04 已裁决） | Step 2 | ADR 新增/更新 |
| 3b | **S-01 + S-04 第 1 个 PR**：两套 run 模型映射表 + 4.17 判据升级（三层 status **且** Hold 有效 **且** 在途命令已收敛）+ 新增「未收敛不得显示 `PAUSED`」断言，**旧断言并行通过** | Step 3 | 全量门禁绿；`M-01` 不回退 |
| 4 | P-01（schema + 只读盘点，无 enforcement）→ P-10 → P-02（validator + 事务 + 12 条集成测试） | Step 3、N-05 | 测试 1~7/11/12 |
| 5 | **跨 Workcell 组（独立 PR 组，ADR-LAYER-001 第 1 批）**：**N-03（Redis + PG 分布式资源锁，先做）** → N-01 → N-07（配置期 Handoff 结构）→ N-04 → **N-08 + L-01 + L-04 同批**（人工确认门禁 + 到位记录 + 前端页）→ N-06（反转用例）→ N-02 | Step 3b、Step 4 | 人工确认缺失 / UNKNOWN 必须阻断；配置过但无本次确认不得放行；锁失败 / 分区 ⇒ 资源 QUARANTINED |
| 6 | **P-03（Hold 三态，独立 PR）+ N-10（整实验停的影响闭包，同批）** → P-04 + N-09（岛名展示口径）→ P-05 | Step 0、Step 4 | 同一实验内任一 Workcell 故障 ⇒ 全部段阻断；其他独立实验仍按 ADR-FAULT-SCOPE-001 |
| 7 | **分层第 2 批 + 状态机治理**：L-02（状态列扩容 + 中间态）→ **S-02 / P-06（同一个 PauseCoordinator）** → S-03（事实/投影切换）→ **S-04 第 2 个 PR（切 API 语义）** → S-05（文档索引）→ L-03 并入 P-07 → P-08 → P-09 → P-11 → P-12（HIL 收尾） | 前置各组 | 每个 PR 带证据；S-04 两个 PR 不得合并 |

> P-01/P-02 与 P-03 不得混合（实施规格 §8 原有纪律，保留）。跨 Workcell 组（N-）不得与 Hold 重构（P-03）混合，但 **N-10 与 P-03 是同一批**（都改 Hold 覆盖判定）。
> **S-04 是硬前置**：不先升级 4.17 判据，任何中间态 / `PAUSE_REQUESTED` 的实现都会让既有 e2e 变红；禁止在同一 PR 里同时改状态实现和验收判据。
> **L-05（SafetyDomain 传播算法 / RobotArm 工作空间互斥 / Dependency Edge 完整图 / 完整聚合器投影）标记 DEFERRED**，不在本轮实施；当前只需「依赖闭包 UNKNOWN ⇒ fail-closed」的最小实现。
> 分层落地三批的依据与 main 源码盘点见 `ADR_INDEX.md` §2.9（ADR-LAYER-001）。

---

## 9. PR 模板必填项

```
ADR 依据：ADR-XXXX-XXX（或「无，纯重构」）
状态表更新：REMEDIATION_STATUS.md 行 <ID> → <状态>
变更工作项：<唯一 ID 列表>
证据：CI run / e2e 段号与断言数 / HIL（型号·固件·方法·负载·停止后状态）
回滚：备份路径或回滚命令
合规：□ 无商业公司/产品名  □ 未修改门禁阈值下限  □ 未 SKIP
```
