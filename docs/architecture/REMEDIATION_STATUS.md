# LabOS 整改状态表（唯一状态真源）

> **⚠ 测试口径部分 DEPRECATED（2026-11-08）**：本文中的门禁段数 / 阈值 / 用例数 /
> 分层口径均为**当时时点快照**，不再作为现行依据。现行唯一入口为
> [测试总纲](../测试总纲.md)，数值只认 `scripts/ci-gate.sh` 的 `MIN_*` 常量。
> 本文其余内容（开发 / 评审 / 验收记录）仍有效，按「只追加不删改」保留。

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
| B-01 | GraphRunner 执行期 RuntimeException 不再 fallback 到 `executeAll` 顺序重跑 | `WorkflowService.executeGraphWithParseFallback` | **DONE（2026-11-09 合入 main @ `f904474`，PR-B1 = `0b0d6da`）**：`executeGraphWithParseFallback(xml, sequentialFallback, graphRunner, executor)` 只把 `WorkflowGraphParser.parse` 放进 try，`graphRunner.run` 故意放在 try 之外 ⇒ 解析期可退回、执行期异常原样抛出；执行期异常另把 run 落 `failed`（不覆盖 paused、不回滚已完成节点）后抛出。证据：`WorkflowExecutionSafetySelfTest` 3/0（执行期异常不 fallback + 解析失败可 fallback + 解析成功走图执行）；`MIN_PURE` 673 → 684 | PR-B1 |
| B-02 | TCP 无物理 Abort 时不以本地 Future 取消冒充设备已停止 | `DeviceDriver.supportsPhysicalAbort()` 默认 false、`DeviceDriverAbortContractSelfTest.java` | **DONE（2026-11-09 合入 main @ `f904474`，PR-B2 = `bf5aeb9` + `fb6cd6a`）**：①`DeviceDriver.supportsPhysicalAbort()` 安全默认 `false`；②`DriverStepExecutor.allowInterruptibleWait(method, driver)` = 方法声明可中断 **且** 驱动声明物理 Abort，缺一即阻塞等回包（跑完到安全边界）；③`TcpPersistentDriver.abort` 改为空实现（TCP 协议只有 `message_type=1` 命令帧，无停止帧）并显式 `supportsPhysicalAbort()=false`。证据：`DeviceDriverAbortContractSelfTest` 6/0、`TcpAbortContractSelfTest` 2/0（abort 后 Future 必须仍未完成）；门禁 4.18 改写为「暂停安全边界」`MIN_INTR` 6 → 9。**实跑证据（2026-11-09，仿真机 `--rand-delay 30000:45000`）**：`e2e-interrupt-during-device.py` **9/0** —— 暂停后当前动作 **31.2 秒自然 `completed`**（不是 1 秒内 `aborted`）、run `paused`、`ARM=completed`/`AFTER=pending`、`run_command` 仅 1 条；`e2e-pause-during-experiment.py`（4.17）**17/0** 未回退；`ci-gate.sh --fast` = 门禁通过（纯逻辑 704/0 ≥ 697、tsc 0）。**未验证**：「声明可中断 + 驱动有物理 Abort ⇒ 命令数秒内真停」当前环境无此驱动（无 HIL）⇒ 归 P-12 | PR-B2 |
| B-03 | 同一 JVM 内同一 run 的后台 worker 禁止并发，旧 worker 未退出时拒绝 resume | `WorkflowServiceSingleFlightSelfTest.java`（2026-11-09 工作区已实装：`claimRunWorkerSlot` / `submitRunWorker` / `startRunWorker`，**未提交、未合入 main**） | NOT_STARTED(on main) | PR-B3 |
| B-04 | 结构化 `AbortResult`（UNSUPPORTED / REQUESTED / DEVICE_ACKNOWLEDGED / PHYSICALLY_STOPPED / UNKNOWN） | `AbortResult` record（`driver/AbortResult.java`）+ `DeviceDriver.abort` 改为返回该状态（安全默认 UNSUPPORTED） | **DONE（2026-11-09 合入 main @ `f904474`，PR-B4 = `bba18b5` + `f904474`）**：①`AbortResult` 五态 record，`physicallyStopped()` **只有 PHYSICALLY_STOPPED 为真**；②`DeviceDriver.abort` 返回 `AbortResult`，接口默认实现给 `UNSUPPORTED`（未实现的驱动不许被当成"能停"）；③TCP 长连接/短连接/虚拟驱动均如实回 `UNSUPPORTED`（协议无停止帧）；④`DriverStepExecutor.decideAfterAbort` 只有 `PHYSICALLY_STOPPED` → `TREAT_AS_STOPPED`，其余（含 null）→ `KEEP_WAITING`（不记 aborted、继续等动作跑完，避免恢复时重放）；⑤结果落 `command_ledger.abort_state/abort_detail/abort_at`（新分片 `99s_command_abort.sql`，旁路写失败只告警），并建 `ix_command_ledger_abort_unconfirmed` 作为"发了中止但没确认"的人工核验入口。证据：`DeviceDriverAbortContractSelfTest` **14/0**（新增 8 条）、`TcpAbortContractSelfTest` 2/0、`CommandLedgerDbSelfTest` **36/0**（新增 7 条真库断言：REQUESTED 落账 / 时间戳 / PHYSICALLY_STOPPED / 空号 no-op / UNKNOWN 可核验 / 核验队列含未确认 / 核验队列不含已确认停止）；e2e 4.18 **10/0**（新增「中止结果账本必须为空」）；⑥`GET /api/commands/abort-unconfirmed` 列出「请求了中止但设备没确认」的命令（走部分索引 `ix_command_ledger_abort_unconfirmed`，与 `/api/commands/unknown` 分开：一个说"结果不知道"，一个说"试着停过但没证据"）——实测插入一行 `REQUESTED` 后接口 `count=1` 并返回 `abortState/abortDetail/abortAt`，删行后回 0；`ci-gate --fast` 通过（纯逻辑 712/0 ≥ 705、tsc 0、产物同源） | PR-B4 |

> 2026-11-09 合入记录：B-01 / B-02 / B-04 已随 `fix/audit-remediation-20261109` 快进合入 `main` @ `f904474`（`e2e3d5ab1..f904474`，线性历史，0 merge 提交）。
> 转 `VERIFIED` 的前置是全量 `./scripts/ci-gate.sh --all` 无 SKIP 通过（见 G-03）；本轮只跑了 `--fast` + 4.18/4.17 两个 e2e 段。
> B-03 未包含在内：工作区有实现与自测，但属另一会话未提交改动。

## 3. G 组：基线对齐与门禁（先于一切实施）

| ID | 工作项 | 状态 | 验收 |
|---|---|---|---|
| G-01 | 把 `main` 的 `3debf78` 同步到评审分支（分支仅归档，不再实施） | NOT_STARTED（需 push 授权） | 分支 `git grep coveringForWorkflow` 非空、`MIN_PAUSEEXP=5` |
| G-02 | 门禁拆段（ADR-PROCESS-002）：恢复 `MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5`；**4.18 原样保留 `MIN_INTR=3`（A1 真停）**；新增 **4.19「安全边界暂停 · TCP 无物理 Abort」+ `MIN_SAFEBOUND`**，脚本另起 `e2e-safebound-pause.py`（避免与分支改过的 `e2e-interrupt-during-device.py` 互相覆盖）；skip 文案 `4.5-4.18` → `4.5-4.19` | **SUPERSEDED（ADR_INDEX §2.8 修订，2026-11-09）** | 原口径不可执行：①`4.19` 号已被「页面全链路」占用；②「A1 真停」在无 abort-capable 驱动的环境里不可能成立（B-02 前它靠的是 `abort()` 伪造完成）。改为：4.18 段号/变量名不变、语义原地改写为「暂停安全边界」、`MIN_INTR` 6 → 9 → 10（只升），「真停」整体移交 P-12 HIL；新增段与 `MIN_SAFEBOUND` 待有 HIL 驱动后再开（从 4.22 起）。验收：段 0「只升不降」通过（已通过） |
| G-03 | 补 e2e 锁死「三层都停 + resume 作废旧 run」，作为 B 组合入的回归条件 | NOT_STARTED | 全量 `ci-gate.sh --all` 无 SKIP 通过 |
| G-04 | 评审分支打 tag 冻结为设计归档 | NOT_STARTED | tag + `ADR_INDEX.md` 标注 |
| G-05 | 测试环境稳定性：`verify-flow-matrix.py` 的 `outvar` 用例取「在线 TRANSFER 中 `resource_id` 最小」的设备（本环境 = `200214`/ID1731，模拟器 `--rand-delay 30000:300000`），`GetPosition` + `Home` 耗时之和可超过 `Ctx.run(wait=420)` 的 420 秒预算 ⇒ 2.7 间歇判红（**非被测代码回归**，见 `LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md` §8.3） | NOT_STARTED | 固定到低延迟臂或按设备延迟上限调预算；连续 3 次 `ci-gate.sh --all` 无该失败 |

## 4. N 组：本次裁决新增（ADR-ISLAND-001~005）

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| N-01 | 跨 Workcell 实验数据模型：Experiment↔Workcell 关联 / ExecutionPlan 绑定 | Q-02 **已裁决** | NOT_STARTED | 按 ADR-ISLAND-005：`experiment.island_id` 仅内部分区键 + 岛名展示，**不承担「主 Workcell」语义、不参与执行判定**；先出只读盘点，不自动推断、不破坏性迁移 |
| N-02 | 执行上下文按**当前 Workflow 的权威 Workcell** 构造；`ExperimentExecutor.runStep` 不再依赖 `experiment.island_id` | N-01、P-01 | NOT_STARTED | 取代原 P0-B 第 1 条与 A-02 修复方向；须同步改 `RunControl` / `RuntimeHold` / `ExperimentScheduler` / `BatchOrchestrator` 的 7+ 处 `e.island_id` 执行判定 |
| N-03 | **已改写（ADR-LOCK-001）**：分布式资源锁 = **Redis + PG 联合、按资源粒度**（设备 / 机械臂 / 板位 / 端口 / 共享工作空间 / 转运路径）；PG 为权威事实层（行锁、唯一约束、`pg_advisory_xact_lock`、fencing token 同事务），Redis 为加速与跨实例协调层且**不作为唯一真相源**；**废弃 `islandId` 锁键 / JVM `ConcurrentHashMap` / 2h TTL** | Q-03 **已裁决**、Q-12、N-01 | NOT_STARTED | 实施前查清自研 `RedisClient` 是否支持 `SET NX PX` / Lua 原子释放 / 续约（Q-12）；租约到期只释放软件租约、不释放物理隔离；崩溃/分区 ⇒ `UNKNOWN/QUARANTINED`，不自动重派发；可复用 `QueryService.java:1585` 的 advisory lock 与 `BatchOrchestrator` 的 `FOR UPDATE` |
| N-10 | **新增（ADR-FAULT-SCOPE-002）**：故障影响闭包以 **ExperimentRun 为最小单位**——任一所属 Workcell 故障/扣留 ⇒ 该实验全部节点、全部 Workflow 段、全部 Handoff 边界停止派发；**不允许 B 段继续** | Q-04 **已裁决**、P-03、N-03 | IN_PR（N-10 第一切片） | 其他**独立实验**仍按 `ADR-FAULT-SCOPE-001`（Workcell 隔离 + Continue Predicate + SafetyDomain）；`RuntimeHold` 覆盖判定、`ExperimentScheduler` 逐候选评估、`autoContinueHeldBatches` 都不得因「节点在另一个 Workcell」放行同一实验；2026-11-08 第一切片：ExperimentWorkcellScope（资源树推导 Workcell）+ RuntimeHold.coveringExperiment + ExperimentExecutor/RunControl 接入，纯单测 7/0；Workcell 显式绑定（experiment_workcell）与跨 Workcell Handoff 配置/门禁（experiment_handoff + HandoffGate + GET/PUT /api/experiments/{id}/handoffs）已落地（2026-11-08/09）；P-03 逐候选调度已落地；运行时人工确认（POST /api/experiments/{id}/handoffs/confirm 落 L-01 + intervention_record_id + manual_window_id 联动）已补；故障注入 e2e verify-dispatch-isolation.py 3/0（A 岛 Hold → A queued、B 正常派发）+ verify-experiment-fault-closure.py 3/0（同实验跨 Workcell 任一扣留 ⇒ 整实验 queued）（N10_EXPERIMENT_FAULT_CLOSURE_20261108.md） |
| N-04 | **已改写**：跨 Workcell Handoff = **配置期静态定义**（源/目标 Workcell、交接点、板与样本身份、执行方式=人工、所需确认项）+ **运行时人工确认门禁**；到位证据为人工确认记录 | Q-01 **已裁决** | NOT_STARTED | ADR-ISLAND-004：**不做自动可达性 / AGV / 路径规划**。配置只证明「允许这么走」，不证明「这次板真的到了」；无确认记录 ⇒ UNKNOWN 阻断 |
| N-05 | `BoundaryResult` / `BoundaryViolation` 扩展 `boundaryKind` / `scopeType` / `evidence`，区分 INVALID / UNKNOWN | ADR-ISLAND-003 | NOT_STARTED | 取代实施规格 §3 的两 `Long` 接口；P-02 的前置 |
| N-06 | 期望反转的测试：Experiment A 引用 Workflow B，有合法 Handoff 且人工确认后允许；Handoff 缺失 / 确认缺失 / UNKNOWN 时阻断 | N-04 | NOT_STARTED | 取代实施规格 §7 测试 8/9/10 的旧期望 |
| N-07 | 配置期 Handoff 持久化结构：扩展 `experiment_process_edge` 或新建 `experiment_handoff`（Q-10），在岛内设备/IP 配置入口同处录入 | Q-10、N-04 | NOT_STARTED | `island_flow.graph` 边只落 `{id,source,target}` 且属**岛内动线**，不可直接复用；`experiment_process_edge` 现注释「只表达偏序，不表达资源」 |
| N-08 | 运行时人工确认门禁：复用 `manual_window` + `pause_kind='waiting_human'` + `GET/POST /api/manual-windows`，补前端消费页 | Q-09、N-04、ADR-PAUSE-007 | IN_PR | **不新建第三套 Handoff 状态机**；确认人/时间/板样本标识/交接点/结果落 L-01；2026-11-09 已补 /manual-windows 页 + 决策落 intervention_record + waiting_human 自动建窗（L01_N08_MANUAL_INTERVENTION_20261109.md） |
| N-09 | 界面口径：不显示岛 ID，只显示岛名 `resource_node.name`；跨 Workcell 实验展示**涉及岛名列表**；改 `ExperimentList.tsx:169,171`、`Scheduler.tsx:171,232` 等 | ADR-ISLAND-005 | IN_PR | 岛名字段已存在；2026-11-08 已改：Scheduler 锁/队列增加 islandName 并只显示岛名，ExperimentList 显示 workcellNames/islandName（跨 Workcell 列表），不再显示 island_id |

## 4a. L 组：分层最小骨架（ADR-LAYER-001）

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| L-01 | `InterventionRecord` 落证：确认人、时间、板/样本标识、源/目标交接点、确认结果 | Q-09、N-08 | IN_PR | **与 N-08 同批**，借人工 Handoff 一起做，避免两套人工记录（ADR-PAUSE-003 / 007）；2026-11-09 intervention_record 已落 + GET /api/intervention-records + operator 取登录用户名 |
| L-02 | `experiment_run.status varchar(16)` 扩容或改短 code + display label；落 `PAUSE_REQUESTED` / `PAUSING` 最小中间态 | ADR-PAUSE-001/003、**ADR-PAUSE-008** | **IN_PR** | 按 ADR-PAUSE-008 **方案 B**：`status` 扩容 `varchar(32)`；新增 `control_state varchar(24) NOT NULL DEFAULT 'NONE'`（`NONE`/`PAUSE_REQUESTED`/`PAUSING`/`RESUMING`）；词表单一权威同步（后端 `StatusVocabulary` 新增 `control` scope + 前端 `status.ts` / `i18n/codes.ts` / 扫描器 `scan-i18n.py`）；**只授予状态表达能力、不改行为**（legacy 立即投影保持，4.17/4.18 判据不退化）。证据：分片 `99c_pause_control.sql` 已拼入 `90_all_in_one.sql` 且 `check-schema-sync.sh` 产物同源；已应用到测试库 `192.168.124.2/labdb`（`control_state varchar(24) default NONE` + `status varchar(32)`）；`./scripts/ci-gate.sh --all` = 门禁通过（无跳过）/`GATE_EXIT=0`，4.17=13/0、4.18=5/0 未回退；纯逻辑 664/0（`MIN_PURE=664`）；`tsc` 0 错误；`scan-i18n.py` 硬错误 0。行为切换（`control_state` 接线）属 PR-S04-B |
| L-03 | CommandExecutionState 最小账本 + 最小 Outbox | ADR-PAUSE-006 | NOT_STARTED | `run_command` 现复用 `node_status` 且步结束才落库；**并入 P-07，不另起一套** |
| L-04 | 人工窗口前端消费页 | N-08 | IN_PR | 可与 N-08 同 PR；2026-11-09 /manual-windows 已落地 |
| L-05 | SafetyDomain 传播算法 / RobotArm 工作空间互斥 / Dependency Edge 完整图 / 完整聚合器投影 | — | **DEFERRED** | 暂不建全套；当前只需「依赖闭包 UNKNOWN ⇒ fail-closed」最小实现 |

## 4b. S 组：状态机多层维护治理（ADR-STATE-001）

专门文档：`LABOS_STATE_MACHINE_MULTILAYER_20261107.md`（仓库原有 203 份文档无此主题专篇）。

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| S-01 | 两套 run 模型映射表：字段 / 调用链 / `pause_kind` 词表差异 / 创建·关闭·重启恢复行为 / 关联基数 | — | IN_PR | `PauseManager`→`run.status`、`RunControl`→`experiment_run.status` 并存且独立写库；`3debf78` 只是约定式补丁，未合并；2026-11-08 新增 docs/architecture/S01_RUN_MODEL_MAPPING_20261108.md（字段/调用链/词表/恢复/基数六节） |
| S-02 | `PauseCoordinator` 适配层：**与 P-06 是同一项**，本行只补「两套模型映射与适配边界」，不另建第二个 Coordinator | S-01、P-06、**ADR-PAUSE-009/010**、**ADR-PROCESS-003** | **IN_PR** | **V1 安全子集已落地**（`LABOS_PAUSE_COORDINATOR_V1_DESIGN_20261107.md` §6 V1-1~V1-6；ADR-PROCESS-003 §2.16）：`PauseCoordinator` 适配层（E-1~E-6 全部经它路由，外部响应逐字段不变，`M-01` 作废旧 run 契约保留）+ Coordinator 级幂等（`pause_request` 表，§6 V1-5 / ADR-PAUSE-009）+ `GET /api/runtime/holds` 暴露 `unknown`/`unknownReason`（§6 V1-6 / ADR-PAUSE-010）。证据：`./scripts/ci-gate.sh --all` = 门禁通过（无跳过）/`GATE_EXIT=0`，2.7=55/0、4.13=34/0、4.17=13/0、4.18=5/0 未回退；纯逻辑 664/0；DB 单测 `PauseCoordinatorDbSelfTest` 16/0。**V2 第一切片已落地（2026-11-08）**：pause_event Outbox + 实验级 Pause（E-4）的 Hold/control_state/幂等键/事件**单事务**写入，PauseEventOutbox 幂等派发（PAUSE_V2_SINGLE_TX_OUTBOX_20261108.md）；剩余：Resume/Stop 与 Workflow 级 Pause 单事务化、PAUSING worker、P-07 命令账本 |
| S-03 | 事实 vs 投影职责切换：`experiment.status` 只允许聚合器写；`StatusVocabulary` CASE 回写收口 | S-02、P-06 | NOT_STARTED | 现状 3 处直接 UPDATE；`PauseCoordinator` / `ExperimentStateProjector` 在 Java 源码 0 命中 |
| S-04 | ⚠️ **验收口径切换（分两个 PR）**：① PR-S04-A 保留 legacy API/status，4.17 增加本次 Hold ID、device_state 在途/收敛、节点边界和后续 pending 证据；明确 legacy `paused` 可早于物理收敛，**不在 A 阶段断言 UI/API 不显示 PAUSED**；② PR-S04-B 在 P-03、P-07/L-03、L-02、S-02/P-06 完成后切 API/UI 语义，并新增「未收敛不得显示 `PAUSED`」断言 | G-03、L-02、P-03、P-07、S-02/P-06 | IN_PR（PR-S04-B 最小闭环） | 现有 4.17 以三层 `paused` 为主断言；当前 `ExperimentApi` 立即返回 paused、设备动作可继续运行到安全边界。两阶段边界见 `LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md`；**禁止同一 PR 同时改实现与验收判据**。2026-11-07 进展：PR-S04-A 已在评审分支跑通（4.17 = 13/0，`ci-gate.sh --all` 中 0 SKIP、4.17/4.18/4.13 全绿，唯一失败为与本项无关的 G-05），评审分支测试已随 530e4eb/b716d07 合入 main（2026-11-08）；PR-S04-B 最小闭环已落地：Pause API 返回 accepted + control_state，收敛后才写 paused（执行器 settle / 调度器 convergeQueuedPauses），页面显示 control 词表；4.17=17/0、4.20=25/0；剩余：P-07 命令账本/Outbox 与 P-06 V2 单事务未做 |
| S-05 | 原有 26 份状态相关文档索引 + 过时标注（哪些仍是权威、哪些已被 ADR 取代） | — | IN_PR | 避免下一个人把「三层都 paused」当成新设计的验收判据；2026-11-08 新增 docs/状态机文档索引.md（权威/现行/历史快照/被取代四级，含 S-04-B 新口径提醒） |

## 5. P 组：保留的整改工作项

| ID | 工作项 | 原编号 | ADR 依据 | 状态 |
|---|---|---|---|---|
| P-01 | `workflow.island_id` 权威列 + FK + `(tenant_id, island_id)` 索引；attr 兼容镜像同事务双写；只读 inventory 报告；改掉 `createWorkflow`「回落全部岛」语义 | PR-1 | ADR-ISLAND-001 | NOT_STARTED（schema 事实已确认：无独立列，`20_kernel.sql:189-205`） |
| P-02 | 统一 `IslandBoundaryValidator`（用 N-05 扩展后的接口）+ 事务化写入路径：`createWorkflow` 单事务、`backupGraph` 入 `saveGraph` 事务、按 Experiment→Workflows→resource 顺序 FOR UPDATE 加锁 | PR-2、P0-6、P0-A | ADR-ISLAND-001/003 | NOT_STARTED |
| P-03 | RuntimeHold `CLEAR/HELD/UNKNOWN` 三态 + 逐 Workcell 候选调度 + `autoContinueHeldBatches` 逐候选 + `uk_runtime_hold_active` 唯一索引改造（支持多 Hold 并存） | P0-8、P0-C、P0-0 | ADR-PAUSE-005、ADR-FAULT-SCOPE-001 | **DONE**：`RuntimeHold` 三态 fail-closed（`activeChecked()` 抛 `SQLException`；`coveringForChecked()` 供写路径；`coverageFor` / `coverageForWorkflow` / `siteState` 失败即 `UNKNOWN` 阻断）+ 5 处调用点改为「`UNKNOWN` 视为阻断」。证据：`RuntimeHoldSelfTest` 三态 11 条；`./scripts/ci-gate.sh --all` 门禁通过（无跳过）。2026-11-08 进展：ExperimentScheduler.dispatch 与 autoContinueHeldBatches 已改为逐候选评估（按实验涉及 Workcell 覆盖判定；读不到 Hold 仍全局 UNKNOWN 阻断），不再因无关实验 Hold 挡住独立实验；BatchDbSelfTest 244/0 按新语义调整。2026-11-09 完成 **uk_runtime_hold_active 多 Hold 并存**：唯一索引改为 (tenant, scope, scope_id, kind)，同 scope 不同 kind 可并存；RuntimeHold.raise 按 kind 幂等、新增 clearKind 精确解除；RuntimeHoldDbSelfTest 14/0 |
| P-04 | 编辑器 UX：Workflow 加载服务端权威 Workcell 并锁定不可清空；严格设备池；drop 期 Workcell 校验；`LastDropped` 保存归属；无可达机械臂/UNKNOWN 时禁止直连 | PR-3、P0-6 | ADR-ISLAND-001 | NOT_STARTED |
| P-05 | 编译/发布/运行严格边界：移除 `WorkflowService.withIsland()` 的全局候选回退；direct run / choreography / Experiment 执行前重校验配置快照 | PR-4、P0-B、A-03、N-02 | ADR-ISLAND-001/002 | NOT_STARTED |
| P-06 | PauseCoordinator：Hold + 实验级 PAUSE_REQUESTED + 节点暂停请求 + Event/Outbox 单事务；PAUSING 异步收敛；API 返回 request accepted | P0-1(事务)、P0-2、P0-3、Batch 0.4 | ADR-PAUSE-001/002/007、**ADR-PAUSE-009/010** | **IN_PR（仅 V1 适配层子集）**：`PauseCoordinator` 统一入口（E-1~E-6）+ `PauseCommand` 统一请求对象 + Coordinator 级幂等（`pause_request`，ADR-PAUSE-009）+ `UNKNOWN` 运维可见（ADR-PAUSE-010）。证据：`./scripts/ci-gate.sh --all` = 门禁通过（无跳过）/`GATE_EXIT=0`；DB 单测 `PauseCoordinatorDbSelfTest` 16/0。**V2 第一切片已落地（2026-11-08）**：实验级 Pause 的 Hold + control_state + pause_request + pause_event 同事务；PauseEventOutbox 每 tick 幂等派发；API 已返回 status=accepted + controlState=PAUSE_REQUESTED（S-04-B 最小闭环，43358f2）。**已补第二/第三切片（2026-11-08）**：实验级 Resume（E-6）单事务；实验级 Stop（E-5）单事务（STOP_REQUESTED 事件）；**未做**：Workflow 级 Pause（E-1~E-3）单事务化、PAUSING 最小异步 worker 已落地（2026-11-08 第四切片：受理超 5s 标 PAUSING，settle 回 NONE）、真实事件总线消费者、P-07 完整命令账本 |
| P-07 | 命令账本 + Outbox；`CREATED→…→UNKNOWN→RECONCILING`；四个崩溃点不重复物理动作 | Batch 1、ADR §8 Batch 1 | ADR-PAUSE-006 | IN_PR（P-07 第一切片） |
| P-08 | 资源与物理位置三维分离、启动恢复隔离 QUARANTINED、TransferTransaction + 物理确认 | Batch 2 | ADR-FAULT-SCOPE-001 | IN_PR（启动隔离切片） |
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
| 3b | **S-01 + PR-S04-A**：两套 Run 模型映射表 + 4.17 增加 Hold / device_state / 动作收敛 / 节点 pending 事实证据，保持 legacy API/status；未收敛不得显示 `PAUSED` 的 UI/API 断言留到 PR-S04-B | Step 3 | 全量门禁绿；`M-01` 不回退；PR-S04-A 不得假称新 PAUSED 语义已切换 |
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
