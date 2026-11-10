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
| B-01 | GraphRunner 执行期 RuntimeException 不再 fallback 到 `executeAll` 顺序重跑 | `WorkflowService.executeGraphWithParseFallback` | **VERIFIED（2026-11-09 合入 main @ `f904474`，PR-B1 = `0b0d6da`）**：`executeGraphWithParseFallback(xml, sequentialFallback, graphRunner, executor)` 只把 `WorkflowGraphParser.parse` 放进 try，`graphRunner.run` 故意放在 try 之外 ⇒ 解析期可退回、执行期异常原样抛出；执行期异常另把 run 落 `failed`（不覆盖 paused、不回滚已完成节点）后抛出。证据：`WorkflowExecutionSafetySelfTest` 3/0（执行期异常不 fallback + 解析失败可 fallback + 解析成功走图执行）；`MIN_PURE` 673 → 684 | PR-B1 |
| B-02 | TCP 无物理 Abort 时不以本地 Future 取消冒充设备已停止 | `DeviceDriver.supportsPhysicalAbort()` 默认 false、`DeviceDriverAbortContractSelfTest.java` | **VERIFIED（2026-11-09 合入 main @ `f904474`，PR-B2 = `bf5aeb9` + `fb6cd6a`）**：①`DeviceDriver.supportsPhysicalAbort()` 安全默认 `false`；②`DriverStepExecutor.allowInterruptibleWait(method, driver)` = 方法声明可中断 **且** 驱动声明物理 Abort，缺一即阻塞等回包（跑完到安全边界）；③`TcpPersistentDriver.abort` 改为空实现（TCP 协议只有 `message_type=1` 命令帧，无停止帧）并显式 `supportsPhysicalAbort()=false`。证据：`DeviceDriverAbortContractSelfTest` 6/0、`TcpAbortContractSelfTest` 2/0（abort 后 Future 必须仍未完成）；门禁 4.18 改写为「暂停安全边界」`MIN_INTR` 6 → 9。**实跑证据（2026-11-09，仿真机 `--rand-delay 30000:45000`）**：`e2e-interrupt-during-device.py` **9/0** —— 暂停后当前动作 **31.2 秒自然 `completed`**（不是 1 秒内 `aborted`）、run `paused`、`ARM=completed`/`AFTER=pending`、`run_command` 仅 1 条；`e2e-pause-during-experiment.py`（4.17）**17/0** 未回退；`ci-gate.sh --fast` = 门禁通过（纯逻辑 704/0 ≥ 697、tsc 0）。**未验证**：「声明可中断 + 驱动有物理 Abort ⇒ 命令数秒内真停」当前环境无此驱动（无 HIL）⇒ 归 P-12 | PR-B2 |
| B-03 | 同一 JVM 内同一 run 的后台 worker 禁止并发，旧 worker 未退出时拒绝 resume | `WorkflowServiceSingleFlightSelfTest.java` | **VERIFIED（2026-11-09 收口，相关段实测）**：`ACTIVE_RUN_WORKERS` + `claimRunWorkerSlot` / `releaseRunWorkerSlot`（按 owner 释放，别人释放不掉）/ `claimRunWorkerSlotOrThrow` / `launchRunWorker`；`startAsync` 走 `submitRunWorker`（占位+线程池），占位失败时 `markRunFailed` 不留 `running` 空单；`resumeAsync` **先占位再动库**（拒绝发生时 DB 一个字段都没改），DB 失败立刻释放占位。原工作区版本把占位放在续跑事务之后，会留下「status=running 却没人跑」的僵尸单，收口时已调整。证据：`WorkflowServiceSingleFlightSelfTest` **13/0**（同 run 单飞 / 只有持有者能释放 / 释放后可重入 / 重复启动被拒且消息带占位者 / 线程正常结束与 body 抛异常都自动释放）；`MIN_PURE` 705 → 718 | PR-B3 |
| B-04 | 结构化 `AbortResult`（UNSUPPORTED / REQUESTED / DEVICE_ACKNOWLEDGED / PHYSICALLY_STOPPED / UNKNOWN） | `AbortResult` record（`driver/AbortResult.java`）+ `DeviceDriver.abort` 改为返回该状态（安全默认 UNSUPPORTED） | **VERIFIED（2026-11-09 合入 main @ `f904474`，PR-B4 = `bba18b5` + `f904474`）**：①`AbortResult` 五态 record，`physicallyStopped()` **只有 PHYSICALLY_STOPPED 为真**；②`DeviceDriver.abort` 返回 `AbortResult`，接口默认实现给 `UNSUPPORTED`（未实现的驱动不许被当成"能停"）；③TCP 长连接/短连接/虚拟驱动均如实回 `UNSUPPORTED`（协议无停止帧）；④`DriverStepExecutor.decideAfterAbort` 只有 `PHYSICALLY_STOPPED` → `TREAT_AS_STOPPED`，其余（含 null）→ `KEEP_WAITING`（不记 aborted、继续等动作跑完，避免恢复时重放）；⑤结果落 `command_ledger.abort_state/abort_detail/abort_at`（新分片 `99s_command_abort.sql`，旁路写失败只告警），并建 `ix_command_ledger_abort_unconfirmed` 作为"发了中止但没确认"的人工核验入口。证据：`DeviceDriverAbortContractSelfTest` **14/0**（新增 8 条）、`TcpAbortContractSelfTest` 2/0、`CommandLedgerDbSelfTest` **36/0**（新增 7 条真库断言：REQUESTED 落账 / 时间戳 / PHYSICALLY_STOPPED / 空号 no-op / UNKNOWN 可核验 / 核验队列含未确认 / 核验队列不含已确认停止）；e2e 4.18 **10/0**（新增「中止结果账本必须为空」）；⑥`GET /api/commands/abort-unconfirmed` 列出「请求了中止但设备没确认」的命令（走部分索引 `ix_command_ledger_abort_unconfirmed`，与 `/api/commands/unknown` 分开：一个说"结果不知道"，一个说"试着停过但没证据"）——实测插入一行 `REQUESTED` 后接口 `count=1` 并返回 `abortState/abortDetail/abortAt`，删行后回 0；`ci-gate --fast` 通过（纯逻辑 712/0 ≥ 705、tsc 0、产物同源） | PR-B4 |

> 2026-11-09 合入记录：B-01 / B-02 / B-04 已随 `fix/audit-remediation-20261109` 快进合入 `main` @ `f904474`（`e2e3d5ab1..f904474`，线性历史，0 merge 提交）。
> **2026-11-09 口径修正**：`VERIFIED` 只认「改动面 + 相关段」的实测证据，**不认全量 `--all`**。
> B 组相关段实测汇总：`test-all.sh` 全量（纯+连库）**1310/0**（含纯逻辑 718/0）、`ci-gate.sh --fast` 通过、2.7 流程矩阵 **55/0**、4.18 **10/0**、4.17 **17/0**；B-01 另有 `WorkflowExecutionSafetySelfTest` 3/0，B-02 另有 14/0 + 2/0，B-03 另有 `WorkflowServiceSingleFlightSelfTest` 13/0，B-04 另有 36/0 + 8/0 DB 断言。真停正向路径（需 HIL）仍属 P-12，不在本组验收范围。
> B-03 已收口（2026-11-09，见下表）：原工作区实现把占位放在续跑事务之后，会留下僵尸单，已调整为「先占位再动库」。

## 3. G 组：基线对齐与门禁（先于一切实施）

| ID | 工作项 | 状态 | 验收 |
|---|---|---|---|
| G-01 | 把 `main` 的 `3debf78` 同步到评审分支（分支仅归档，不再实施） | NOT_STARTED（需 push 授权） | 分支 `git grep coveringForWorkflow` 非空、`MIN_PAUSEEXP=5` |
| G-02 | 门禁拆段（ADR-PROCESS-002）：恢复 `MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5`；**4.18 原样保留 `MIN_INTR=3`（A1 真停）**；新增 **4.19「安全边界暂停 · TCP 无物理 Abort」+ `MIN_SAFEBOUND`**，脚本另起 `e2e-safebound-pause.py`（避免与分支改过的 `e2e-interrupt-during-device.py` 互相覆盖）；skip 文案 `4.5-4.18` → `4.5-4.19` | **SUPERSEDED（ADR_INDEX §2.8 修订，2026-11-09）** | 原口径不可执行：①`4.19` 号已被「页面全链路」占用；②「A1 真停」在无 abort-capable 驱动的环境里不可能成立（B-02 前它靠的是 `abort()` 伪造完成）。改为：4.18 段号/变量名不变、语义原地改写为「暂停安全边界」、`MIN_INTR` 6 → 9 → 10（只升），「真停」整体移交 P-12 HIL；新增段与 `MIN_SAFEBOUND` 待有 HIL 驱动后再开（从 4.22 起）。验收：段 0「只升不降」通过（已通过） |
| G-03 | 补 e2e 锁死「三层都停 + resume 作废旧 run」，作为 B 组合入的回归条件 | **DONE（前面已做，2026-11-09 据实收口）** | **验收条件已改**：只认这两条相关段，不认全量 `--all`。①「三层都停」由 `e2e-pause-during-experiment.py`（4.17）**17/0** 锁死：`experiment_run`/`experiment` 两层 paused + Workflow 级 run 在节点边界 paused + 后续 ARM2 保持 pending + 设备收敛顺序；②「resume 作废旧 run」由 `RunControlDbSelfTest` §5.1（**已在 test-all 1310/0 内**）锁死：被中断步的流程级 run 作废旧 run 为 aborted、已完成 run 不受影响、补 actual_end。原写的「全量 `ci-gate.sh --all` 无 SKIP 通过」**作废** —— 它把两个与改动无关的十几个段（2.6 并行 207s、4.13 kill -9、4.19/4.20 页面全链路等）绑成前置，实测为此白跑 12 分钟。原则见 `docs/测试总纲.md` §2.1【现行铁律】 |
| G-04 | 评审分支打 tag 冻结为设计归档 | NOT_STARTED | tag + `ADR_INDEX.md` 标注 |
| G-05 | 测试环境稳定性：`verify-flow-matrix.py` 的 `outvar` 用例取「在线 TRANSFER 中 `resource_id` 最小」的设备（本环境 = `200214`/ID1731，模拟器 `--rand-delay 30000:300000`），`GetPosition` + `Home` 耗时之和可超过 `Ctx.run(wait=420)` 的 420 秒预算 ⇒ 2.7 间歇判红（**非被测代码回归**，见 `LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md` §8.3） | **DONE（2026-11-09 修复，改用「按设备延迟上限推导预算」）** | `scripts/verify-flow-matrix.py` 新增 `sim_method_delay_sec()` + `device_budget_sec()`：从 `pgrep -fl DeviceSimulator` 的 `--rand-delay min:max` 读单动作上限，预算 = 60 + 3×上限（覆盖串行动作），读不到则退回真机上限 300s；`Ctx.run(wait=None)` 与 `wait_devices_idle(limit=None)` 都改用该预算，不再写死 420。预算是**上限**不是固定等待（run 一进终态立即返回），快速场景不受影响。证据：预算函数 4 组参数实测 15/45/300/读不到 → 105/195/960/960 秒全对；`--section outvar`（原间歇判红用例）在当前 `--rand-delay 30000:45000` 环境 **2/0**。**VERIFIED（2026-11-09 补证据）**：`ci-gate.sh --all` 跑到 2.7 段时该脚本实测 **55/0**（用时 515s），与原间歇判红的 `outvar` 2/0、预算函数 4 组参数实测共同构成充分证据。原「连续 3 次全量 `--all` 无该失败」的验收口径**作废**（同 G-03，按改动面只跑 2.7 即可） |

## 4. N 组：本次裁决新增（ADR-ISLAND-001~005）

| ID | 工作项 | 依赖 | 状态 | 说明 |
|---|---|---|---|---|
| N-01 | 跨 Workcell 实验数据模型：Experiment↔Workcell 关联 / ExecutionPlan 绑定 | Q-02 **已裁决** | NOT_STARTED | 按 ADR-ISLAND-005：`experiment.island_id` 仅内部分区键 + 岛名展示，**不承担「主 Workcell」语义、不参与执行判定**；先出只读盘点，不自动推断、不破坏性迁移 |
| N-02 | 执行上下文按**当前 Workflow 的权威 Workcell** 构造；`ExperimentExecutor.runStep` 不再依赖 `experiment.island_id` | N-01、P-01 | NOT_STARTED | 取代原 P0-B 第 1 条与 A-02 修复方向；须同步改 `RunControl` / `RuntimeHold` / `ExperimentScheduler` / `BatchOrchestrator` 的 7+ 处 `e.island_id` 执行判定 |
| N-03 | **已改写（ADR-LOCK-001）**：分布式资源锁 = **Redis + PG 联合、按资源粒度**（设备 / 机械臂 / 板位 / 端口 / 共享工作空间 / 转运路径）；PG 为权威事实层（行锁、唯一约束、`pg_advisory_xact_lock`、fencing token 同事务），Redis 为加速与跨实例协调层且**不作为唯一真相源**；**废弃 `islandId` 锁键 / JVM `ConcurrentHashMap` / 2h TTL** | Q-03 **已裁决**、Q-12、N-01 | **IN_PR（第一步切片已落地 2026-11-10）**：JVM 内的岛锁已换成**资源级软预约**（`ExperimentScheduler.resourceHolds`，resource_id → run；派发前算 `experiment_process → workflow_node.resource_id` 的资源集合，与别人正在占的不相交才放行 ⇒ 同岛不同设备可以并行）。**同时补上被岛锁隐含保证的那条安全底线**：`PositionLock.takePlate` / `firstOccupiedPosition` 校验 `occupied_by_run`，取板只取自己的、归属读不到按 fail-closed 处理（否则岛内并行后 A 会取走 B 的板）；新增 `PlateOwnershipDbSelfTest` 5 组断言。**仍未做**：跨实例仲裁（Redis 部分）、位置/端口级锁、`estimatedWaitSec` 的完整资源口径（当前只按设备冲突算） | 实施前查清自研 `RedisClient` 是否支持 `SET NX PX` / Lua 原子释放 / 续约（Q-12）；租约到期只释放软件租约、不释放物理隔离；崩溃/分区 ⇒ `UNKNOWN/QUARANTINED`，不自动重派发；可复用 advisory lock 与 `BatchOrchestrator` 的 `FOR UPDATE`（同类用法现在见 `OpsUserMapper.xml:30`；原 `QueryService.java:1585` 随该类于 2026-11-10 按域拆分删除）。**<br>2026-11-10 排查补记（实施前的前置条件）**：① 岛内并行的物理安全**其实已经由资源级锁承担** —— 设备锁 `DeviceLock`（一台设备一把锁，机械臂就是普通设备 ⇒「一次搬一块板」天然成立；抢不到就等，等待预算按该设备方法超时上限动态取，60s~15min）+ 位置锁 `PositionLock`（层位级原子预约 + 搬运双锁按 id 升序、全有或全无）。**岛锁 `ExperimentScheduler.islandLocks`（`tryLockIsland` 用 `putIfAbsent`）是唯一多余的粗粒度闸门** —— 它把「抢机械臂」放大成「抢整个岛」，让本可并行的实验互相排队（`DeviceLock` 类注释原话：「A 孵育 30 分钟 / B 离心 5 分钟 ⇒ 岛锁让 B 等 30 分钟，这是纯浪费」）。② **但删岛锁前必须先补一条安全底线**：`PositionLock.takePlate(resourceId, runId)` 只按 `resource_id` 取板，UPDATE 的 WHERE **没有 `occupied_by_run` 条件** —— `position_state.occupied_by_run` 被记录却**从未用于判定** ⇒「板子归属」目前隐含依赖岛锁保证；放开岛内并行前必须先让取板/放板校验归属，否则 A 会取走 B 的板。③ ETA 口径要跟着改：`estimatedWaitSec` 现按「岛当前持有者剩余估算 + 同岛前面排队实验的 estimateSec」算，岛锁去掉后须改成按设备/位置算。④ `DeviceLock` 是**库内 PG 行锁**，不是 Redis 锁 ⇒ 单实例内已够用，跨实例仲裁才是 ADR-LOCK-001 的 Redis 部分要解决的 **验收证据（2026-11-10，`abc5794` 之后实测）**：`scripts/verify-parallel-experiments.py` A/B 两段**通过 5 / 失败 0** —— A 段（同岛、**同一台设备**、4 个实验）峰值同时 running = **1**、4 个排队、**4/4 完成**（设备级互斥成立）；B 段（同岛、**两台不同设备**）峰值同时 running = **2**、**2/2 完成**（资源级准入会同时放行 ⇒ 岛内并行是真的并行）。内核日志佐证：B 段两个实验相隔 **31 毫秒**先后派发（`[调度] 派发实验 1000139` → `1000140`）。⚠ 本次为跑通验收，先修了脚本的**选设备判据与等待预算**（本机模拟器刻意 30~45 秒延时、影子探针 3 秒超时 ⇒ 原判据会把好设备全判死、脚本永远 SKIP）：见提交 `abc5794`。**仍未做**：跨实例 Redis 仲裁（多实例部署时资源锁要跨进程协调）。 **调度验收（2026-11-10，`db94897` 之后实测）**：`scripts/verify-scheduler-acceptance.py` **通过 8 / 失败 0** —— A 段（同岛 3 实验）：队列位次与提交顺序一致、同一台设备任何时刻最多 1 个实验占用、派发顺序 == 提交顺序；B 段（不同岛 3 实验）：3 轮**派发跨度 0.050~0.090 秒** ⇒ 3 个跨岛实验在**同一个调度 tick 内被放行**（调度 tick 间隔 ~2 秒）⇒ 跨岛互不阻塞成立，且岛级互斥未被破坏；C 段：甘特页渲染正常。⚠ B 段的断言口径这轮改了：空实验一次只跑 ~10 毫秒，"同时 running"这个瞬间**采样抓不到**（连改成"提交前起采样"、后台采样 41~58 次覆盖整个排空窗口仍是 0；事后算 [started_at, finished_at] 区间重叠也是 0，因为三个实验虽被同时放行但各自 10 毫秒就结束、区间并不相交）⇒ 改用**派发日志**这条已有的证据口径。**分工**：「调度器同时放行」由本脚本用派发日志证明；「执行期真的重叠」由 `verify-parallel-experiments.py` 用**真设备动作**证明（同岛不同设备峰值 running = 2）。 |
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
| P-01 | `workflow.island_id` 权威列 + FK + `(tenant_id, island_id)` 索引；attr 兼容镜像同事务双写；只读 inventory 报告；改掉 `createWorkflow`「回落全部岛」语义 | PR-1 | ADR-ISLAND-001 | **DONE（2026-11-09，按现场决策「清空脏标注、不推断不回填」）**：① 清除 994 条 `workflow.attr->'island_id'` 旧编号脏键（27/31/33…，对不上资源树），并把 `experiment.island_id=504821` 的 8 条置空；删前全量导出 `/tmp/labos-cleanup-20261109/`。**流程定义与历史单据一条没删**（1027 张流程、10726 条 run 全保留）。② 新增 `workflow.island_id bigint REFERENCES resource_node(id)` + `(tenant_id,island_id)` 索引（分片 `99v`），存量一律 NULL（未分配，交 P-02 校验器判 UNKNOWN），不推断不回填；③ `createWorkflow` 改为同 INSERT 写权威列：`isRealIsland()` 校验必须是 `resource_node(kind='island')`，非法 id 直接 400 拒绝、不传则 NULL；**删掉 attr 镜像写入这个造脏源头**，「回落全部岛」语义取消；④ 按现场指示删掉只服务于历史脏数据的只读盘点脚本与报告（`workflow_island_inventory.py` / `P01_...INVENTORY_20261109.md`），不再维护旧口径兼容。证据：端到端 POST /api/workflows —— 合法 100027 → 200 且 `island_id=100027`、attr 无旧键；旧编号 27 → 400「不是 resource_node 里的岛节点」；不传 → 200 且 `island_id=NULL`、attr 无旧键 |
| P-02 | 统一 `IslandBoundaryValidator`（用 N-05 扩展后的接口）+ 事务化写入路径：`createWorkflow` 单事务、`backupGraph` 入 `saveGraph` 事务、按 Experiment→Workflows→resource 顺序 FOR UPDATE 加锁 | PR-2、P0-6、P0-A | ADR-ISLAND-001/003 | NOT_STARTED |
| P-03 | RuntimeHold `CLEAR/HELD/UNKNOWN` 三态 + 逐 Workcell 候选调度 + `autoContinueHeldBatches` 逐候选 + `uk_runtime_hold_active` 唯一索引改造（支持多 Hold 并存） | P0-8、P0-C、P0-0 | ADR-PAUSE-005、ADR-FAULT-SCOPE-001 | **DONE**：`RuntimeHold` 三态 fail-closed（`activeChecked()` 抛 `SQLException`；`coveringForChecked()` 供写路径；`coverageFor` / `coverageForWorkflow` / `siteState` 失败即 `UNKNOWN` 阻断）+ 5 处调用点改为「`UNKNOWN` 视为阻断」。证据：`RuntimeHoldSelfTest` 三态 11 条；`./scripts/ci-gate.sh --all` 门禁通过（无跳过）。2026-11-08 进展：ExperimentScheduler.dispatch 与 autoContinueHeldBatches 已改为逐候选评估（按实验涉及 Workcell 覆盖判定；读不到 Hold 仍全局 UNKNOWN 阻断），不再因无关实验 Hold 挡住独立实验；BatchDbSelfTest 244/0 按新语义调整。2026-11-09 完成 **uk_runtime_hold_active 多 Hold 并存**：唯一索引改为 (tenant, scope, scope_id, kind)，同 scope 不同 kind 可并存；RuntimeHold.raise 按 kind 幂等、新增 clearKind 精确解除；RuntimeHoldDbSelfTest 14/0 |
| P-04 | 编辑器 UX：Workflow 加载服务端权威 Workcell 并锁定不可清空；严格设备池；drop 期 Workcell 校验；`LastDropped` 保存归属；无可达机械臂/UNKNOWN 时禁止直连 | PR-3、P0-6 | ADR-ISLAND-001 | NOT_STARTED |
| P-05 | 编译/发布/运行严格边界：移除 `WorkflowService.withIsland()` 的全局候选回退；direct run / choreography / Experiment 执行前重校验配置快照 | PR-4、P0-B、A-03、N-02 | ADR-ISLAND-001/002 | NOT_STARTED |
| P-06 | PauseCoordinator：Hold + 实验级 PAUSE_REQUESTED + 节点暂停请求 + Event/Outbox 单事务；PAUSING 异步收敛；API 返回 request accepted | P0-1(事务)、P0-2、P0-3、Batch 0.4 | ADR-PAUSE-001/002/007、**ADR-PAUSE-009/010** | **IN_PR（仅 V1 适配层子集）**：`PauseCoordinator` 统一入口（E-1~E-6）+ `PauseCommand` 统一请求对象 + Coordinator 级幂等（`pause_request`，ADR-PAUSE-009）+ `UNKNOWN` 运维可见（ADR-PAUSE-010）。证据：`./scripts/ci-gate.sh --all` = 门禁通过（无跳过）/`GATE_EXIT=0`；DB 单测 `PauseCoordinatorDbSelfTest` 16/0。**V2 第一切片已落地（2026-11-08）**：实验级 Pause 的 Hold + control_state + pause_request + pause_event 同事务；PauseEventOutbox 每 tick 幂等派发；API 已返回 status=accepted + controlState=PAUSE_REQUESTED（S-04-B 最小闭环，43358f2）。**已补第二/第三切片（2026-11-08）**：实验级 Resume（E-6）单事务；实验级 Stop（E-5）单事务（STOP_REQUESTED 事件）；**未做**：Workflow 级 Pause（E-1~E-3）单事务化、PAUSING 最小异步 worker 已落地（2026-11-08 第四切片：受理超 5s 标 PAUSING，settle 回 NONE）、真实事件总线消费者、P-07 完整命令账本 |
| P-07 | 命令账本 + Outbox；`CREATED→…→UNKNOWN→RECONCILING`；四个崩溃点不重复物理动作 | Batch 1、ADR §8 Batch 1 | ADR-PAUSE-006 | IN_PR（P-07 第一切片） |
| P-08 | 资源与物理位置三维分离、启动恢复隔离 QUARANTINED、TransferTransaction + 物理确认 | Batch 2 | ADR-FAULT-SCOPE-001 | IN_PR（启动隔离切片） |
| P-09 | run execution phase 标记 `PREPARED / DISPATCH_STARTED / SETTLED`（整改计划 0.1 有要求但 checkbox 清单漏列） | Batch 0.1 | ADR-PAUSE-001 | **DONE（2026-11-09）**：新列 `run.exec_phase varchar(24) NOT NULL DEFAULT 'PREPARED'`（分片 `99u_run_exec_phase.sql`，含历史行回填：终态→SETTLED、有 run_command→DISPATCH_STARTED）。写入点：`DriverStepExecutor` **派发前**升 DISPATCH_STARTED（只从 PREPARED 升，幂等，失败只告警不挡派发）；`settleRun`（非 paused 分支）与 `markRunFailed` 收尾落 SETTLED。**paused 不进 SETTLED**——那一单还没走完，恢复时要接着跑。`RunExecPhase.maybeDispatched()` 是崩溃恢复用的判据：只有明确 PREPARED 才算"没动过设备"，读不到一律按动过（fail-closed）。为什么需要它：`run_command` 是步结束才落库，崩在「已下发、未回包」窗口时它数出来是 0，会把最危险的情况误判成安全。证据：`RunExecPhaseDbSelfTest` **10/0**（默认 PREPARED / 派发前升级 / 重复 markDispatched 幂等 / 收尾 SETTLED / **SETTLED 不许被降级** / 读不到返回 null 且 maybeDispatched=true）；实测回填 10,729 行（10,726 SETTLED + 3 DISPATCH_STARTED）。**2026-11-09 现场清理**：那 3 条 DISPATCH_STARTED 经查都是 11-05/06 的验证流程（`内置节点验证(变量+表达式)`、`S100 三步续跑验证`）、状态 paused、各 5 节点/2 命令，按用户指示删除；删除前已把 run/run_node/run_command/run_variable/error_occurrence 导出到 `/tmp/labos-zombie-runs-20261109/`，删除用「必须仍为 paused+DISPATCH_STARTED 且恰好 3 条」的事务守卫，级联无孤儿，复核实跑 `run` 只剩 10,726 条 SETTLED |
| P-10 | `updateNodeBinding` 的 null 语义定案（Javadoc「不改绑定」 vs 代码写 SQL NULL） | 实施规格 §2.3 | — | **DONE（2026-11-09 定案）**：`null` = **不改动**、`0`（或负数）= **显式解绑**、`>0` = 绑定。改动前 null 写 SQL NULL，而 XML 侧 `patchNodeAttrs` 把 null 当"不改" ⇒ 表说没绑、图里还绑着（正是该方法注释里最怕的漂移）。同时前端 `Designer.tsx` 对非设备节点由 `undefined` 改为显式 `0`，避免"省字段"与"解绑"混为一谈。证据（端到端，wid=3782）：② 绑定 200214 → 表/图都 200214；③ **不带 resourceId → 表/图都保持 200214**（改前表会被清空）；④ 显式 0 → 表/图都清空 |
| P-11 | 前端/文档状态枚举与 `varchar` 长度核实（`WAITING_INTERVENTION` 等长状态） | 状态模型 §12.4 | ADR-PAUSE-003 | **DONE（2026-11-09 核实 + 修两处真不一致）**：① `run` scope 有 `cancelled` 而 PG 枚举 `run_status` **没有** ⇒ 补 DDL（`99t_run_status_cancelled.sql`，`ALTER TYPE ADD VALUE IF NOT EXISTS`）；② `step` scope 有 `paused`（**没有任何写入路径**）且**缺** `aborted`（`WorkflowService.stepStatus()` 对 EC-99-0005 就落 aborted）⇒ 改词表，`VERSION` 2026-11-07 → 2026-11-09。③ `WAITING_INTERVENTION` 只出现在 schema 注释里、代码从未使用；`experiment_run.status` 已按 L-02 扩到 varchar(32)，实际最长码是 `PAUSE_REQUESTED`(15) 存 control_state varchar(24)，够用。新增常驻守卫 `StatusVocabularyDbSelfTest` **8/0**：枚举类 scope 双向一致（词表码全部可写 + 枚举成员都有文案，反向按枚举聚合——`node_status` 由 node/step 共享，step 是子集属设计），varchar 类 scope 断言最长码放得进列宽 |
| P-12 | HIL：停止响应丢失、设备拒绝停止、响应丢失、报警风暴/乱序、联锁未复位、人工移板、恢复时旧命令仍运行 | Batch 3、FS-01~12、P-01~16 | 全部 | NOT_STARTED |
| P-13 | **老数据的中文节点名在英文界面仍显示中文** —— 网关自动名改中性 code 后（见 ADR-TECH-001 的延伸口径与 `docs/architecture/SPRING_BOOT_MIGRATION_20261109.md` 同批次的 `36edb54`），新存的是 `XOR_MERGE` 这类与语种无关的 code、界面渲染时映射成本地化文案；**库里已有的中文名（「异或汇合」等）不在映射表里，英文界面下仍原样显示中文**。两个选项：**①（推荐）加一张 code↔中文名 反向映射表**（一次性、不动库里的数据）；② 跑一次数据迁移把老名字转成 code。**已定 ①**（2026-11-10 落地，**不动库里的数据**）：老名字可能是用户手起的自定义名，反向映射表只认「精确等于那 4 个角色名」的名字才转换，不会把任意用户命名误判成角色；② 则要动生产数据、且无法区分「自动生成的角色名」与「用户恰好写了同样字的自定义名」 | 36edb54、ADR-TECH-001 | — | **DONE（2026-11-10）**：新增 `webapp/src/i18n/legacyRoleNames.ts` 冻结 4 条历史中文名→code 对照（异或分叉/异或汇合/并行分叉/并行汇合）；`nodeRoleLabel.ts` 增 `roleCodeOf`/`isLocalizableRoleName`，`localizedNeutralLabel` 改为**先归一成 code 再查表** ⇒ 新数据（新 code）与老数据（老中文名）走同一套 i18n 文案；`NodeAutoNameRenderer`（2026-11-10 由 `NodeRoleLabelRenderer` 改名，因为渲染器扩成"外置标签 + task 图形内部"两个）的 `canRender` 走同一判定 ⇒ 老中文名也被接管渲染。调用点接入：`Designer.tsx` 切语种重画判定由 `isNeutralCode` 换成 `isLocalizableRoleName`（**hunk 级分离暂存**，该文件同时有并发会话的 WIP）、`Workflows.tsx` 节点表接本地化显示、`RunDetail`/`ScheduleGantt`/`Designer` 面板原有 `localizedNeutralLabel(...) ?? name` 自动生效。**顺带修 36edb54 的遗漏**：`RunGraph.tsx` 的 bpmn-js viewer **一直没注册**渲染器（当时的 `NodeRoleLabelRenderer`，现已改名 `NodeAutoNameRenderer`） ⇒ 运行图上的角色名一直是原文（英文界面下是中文），本次补注册并加切语种重画。证据：① 真库实测 `workflow_node` 有 4 行老中文名（workflow 1257「并行网关验证」/ 3203「示例2-并行流程」，两者 BPMN XML 同样含这些中文名）⇒ 画布与运行图确实会露中文；② 运行时映射实测：zh/en 两语种下 8 个角色名（4 中文 + 4 code）全部映射正确，10 个其它名字一律不误判（含前后空格、繁体「異或分叉」、后缀「异或分叉X」、库里的用户自定义名「分支」「B还要再来吗」）；③ `tsc --noEmit` 0 错误；④ `scan-i18n.py` 残留中文 0 字 0 文件、硬错误 0（`legacyRoleNames.ts` 按「历史数据键值表」单列豁免理由，不复用语言包的措辞）；⑤ `docs/en/i18n-ledger.md` 已重生成；⑥ **真实 bpmn-js 渲染级验证**（临时 harness 页，走 `RunGraph` 同款 `new BpmnViewer({ additionalModules:[nodeRoleLabelRenderer] })` 路径，喂含 `name="并行分叉"`/`name="异或汇合"`/`name="分支"` 的 BPMN）：zh 画布文本 `["并行分叉","异或汇合","分支"]`、en 画布文本 `["Parallel split","XOR merge","分支"]` ⇒ **老中文名在英文界面下正确本地化**；渲染后 `businessObject.name` 仍是 `并行分叉`/`异或汇合`（临时换名→渲染→还原 生效，**模型没被改写**）；用户自定义名「分支」两语种都原样保留，**未被误判成角色**；⑦ **切语种重画路径**也单独验证过（这是 `Designer.tsx` 与 `RunGraph.tsx` 切语言时跑的 `graphicsFactory.update('shape', label, gfx)` 分支，与上面的导入渲染是**两条不同代码路径**）：初始 zh 画布 `["并行分叉","异或汇合","分支"]` → 切 en + 重画后 `["Parallel split","XOR merge","分支"]`，且 `businessObject.name` 仍是 `并行分叉`/`XOR_MERGE`/`分支` ⇒ **老中文名与新 code 两条来源在重画后都变成本地化文案，模型同样没被改写**。**已知局限**：反查按**名字精确匹配**、**没有节点类型守门**（前端 `RunNode`/甘特图节点没有 nodeType，后端 DTO 也没暴露，改后端会碰并发会话的 WIP）⇒ 用户若把普通任务**恰好命名为**「异或分叉」，英文界面下会被显示成角色文案。宁可漏翻、不误判 —— 这类误判只在名字恰好撞上时发生。**落地提交号 `a077f19`**（该提交由并发会话执行 `git commit` 时把本条工作区已暂存的改动一并带入 ⇒ 前端 i18n 改动与 SQL 收口同批；内容已逐项核验完整，`Designer.tsx` 只带入了本条的两个 hunk、并发会话的 WIP 未被提交） |
| P-14 | **内置节点与机械臂搬运节点的显示名随界面语种落库** —— 与 P-13 同源（持久化的 `name` 不该跟界面语种变），但落在**另一类节点**上：`Designer.tsx` 写 BPMN `name` 属性时，内置逻辑节点用 `builtinNodeLabel(t, method)`（取 `designer.node.sleep/wait/expr/setVar`）、机械臂搬运节点用 `t('designer.node.armTransfer')` —— **都是当前语种的文案**，于是中文界面建出来的图存「延时」「表达式」「机械臂搬运」，英文界面建出来的存 "Delay"/"Expression"/"Robot-arm transfer"。换语种打开对方建的图，节点名就是另一种语言。**实测**：`workflow_node` 有 4 行这种名字（`机械臂搬运`×2、`表达式`×2，workflow 1885/1999/2414），其余内置节点因为 `name` 为空、后端回退到 `params.protocol_name`（`Transfer`/`__expr`，与语种无关）而侥幸没这个问题 | — | ADR-TECH-001 | **DONE（2026-11-10，落地提交 `2d7f70a`）**：沿用 P-13 的口径 —— 落中性 code、显示时本地化、**库数据一个字不动**。① **内置节点的 code 就是它的 method**（`__setVar`/`__expr`/`__sleep`/`__wait` —— 后端本就按 `protocolName` 识别内置节点），不必另起一套 code；② 机械臂搬运节点用 `ARM_TRANSFER`（它的 method 是 `Transfer`，而库里 `Transfer` 有 2000 多行普通搬运任务同名，拿它当显示标识会把所有搬运节点都显示成「机械臂搬运」）；③ 老数据中文名（机械臂搬运/变量节点/表达式节点/表达式/延时/等待变量）走**反向映射表**在显示层转换；④ **英文老名不反查** —— 内置节点的旧英文名与**真实设备方法名撞名**（实测库里 `Delay` **480 行**、`protocol_name` 就是 `Delay`），数据上无法区分，反查必然误判 ⇒ 宁可漏翻、不误判。**实现要点**：bpmn-js 把名字画在**两个位置** —— 外置标签（网关，画在图形外面）与 **task 图形内部**（内置逻辑节点，画在方框里面）；只接管标签的话内置节点的 code 会**直接露在画布上**（渲染级验证实测到）⇒ 拆成两个渲染器（`bpmn/NodeAutoNameRenderer.ts`，取代 `NodeRoleLabelRenderer.ts`），设备卡片渲染器里那行字也接本地化（机械臂搬运节点走的是卡片渲染器），切语种重画同时覆盖标签与图形两处。**证据**：① 暂存区快照单独跑 `tsc --noEmit` **0 错误**（工作区混着并发会话的 WIP，不能用它代表本次提交）；② 运行时映射实测：两语种下 9 个自动名 code + 10 条老中文名全部映射正确，14 个其它名字**零误判**（含 `Delay`/`Transfer` 等真实设备方法名、前后空格、后缀变体、库里的用户自定义名）；③ 真实 bpmn-js 渲染级验证（临时 harness 页）：导入渲染 zh → `["表达式节点","机械臂搬运","表达式节点","Delay","机械臂搬运","并行分叉","异或汇合"]`，切语种重画 en → `["Expression node","Robot-arm transfer","Expression node","Delay","Robot-arm transfer","Parallel split","XOR merge"]` ⇒ 全部正确、`Delay` 未被本地化、模型 name 未被改写（harness 已删，未进仓库）；④ `scan-i18n.py` 残留中文 0 字 0 文件、硬错误 0（台账已重生成）；⑤ e2e 脚本核对：断言的是 `params.protocol_name` 与界面选项文案、不是 `workflow_node.name` ⇒ 不受影响；脚本自带的 BPMN 夹具名（如「机械臂搬运1」）按精确匹配不转换。**已知局限**：英文界面下建的老图（内置节点名存的是 "Delay"/"Expression node"）**无法自动修复** —— 那些字面量与真实设备方法名不可区分，只能手工改一次名；反查仍**没有节点类型守门**（前端 RunNode/甘特图无 nodeType，后端 DTO 也没暴露）⇒ 用户若把普通任务恰好命名为「表达式节点」，英文界面下会被显示成内置节点文案。**对执行零影响**：后端按 `protocolName` 识别节点，已核实全仓库**没有任何按节点 name 匹配的逻辑**（只有 `resource_node.name` 的更新与 `sys_user.username`）
| P-15 | **实验编排画布的起止节点标签随界面语种落库** —— 与 P-13/P-14 同源（自动生成的名字不该是界面文案），落在**实验编排画布**上：画布 JSON 落 `experiment_graph.graph`，自动生成的起止节点标签写的是 `t('experimentCanvas.node.start')` / `t('experimentCanvas.node.end')`（当前语种文案），左侧面板拖入的节点写 `t(KIND_META[kind].hintKey)` ⇒ 中文界面建出来的实验存「开始」「结束」，英文界面建出来的存 "Start"/"End"，换语种打开对方建的实验，标签就是另一种语言。**实测**：库里 5 条 `experiment_graph` 全带中文的「开始」「结束」（无英文的），另有用户自起的名（「板数」「重复」「判断」「汇合」「按条件走两条路」等） | — | ADR-TECH-001 | **DONE（2026-11-10，落地提交 `2aa2cb6`）**：沿用 P-13/P-14 的口径 —— 落中性 code、显示时本地化、**库数据不动**。① 自动生成的起止节点标签落中性 code `START`/`END`；② 面板拖入的节点不再落文案（标签留空 ⇒ 显示该类型的本地化说明）；③ 老数据（中文「开始」「结束」/ 英文 "Start"/"End"）在**显示层**反查，且**按 `kind` 守门** —— 只有 start/end 节点命中 ⇒ 用户把**流程**节点起名叫「开始」不会被误改；④ 属性面板输入框显示**显示文案**（不是 code/老数据文案），用户一改就落成他自己的名字。**为什么这条能按类型守门、而 P-13/P-14 不能**：画布节点**带 `kind` 字段**（start/end/workflow）⇒ 能确定"这个标签就是自动生成的那个"；BPMN 那边前端接口没有 nodeType、后端 DTO 也没暴露，只能按名字精确匹配。**证据**：① 暂存区快照单独跑 `tsc --noEmit` **0 错误**；② 运行时显示文案实测：zh/en 两语种 × 10 个用例**全过**（中性 code、老数据中文、老数据英文都正确本地化；用户自起的名与"用户把流程节点起名为开始"都原样保留 ⇒ kind 守门生效；空标签显示本地化说明）；③ `scan-i18n.py` 残留中文 0 字 0 文件、硬错误 0（新表按"历史数据键值表"放在 `i18n/` 下，沿用该目录的整体豁免）。**同批还扫出并修掉一处同类**：画布**边**上的 `label`（"点这条线可删"的提示）也跟着 `edges` 一起落库 ⇒ 库里带着界面语种的文案；后端 `compileExperimentGraph` 只读边的 `source`/`target`、不读 label ⇒ 改成保存前只提交 `{id, source, target}`。**对执行零影响**：后端 `compileExperimentGraph` 只取 `kind` 与 `workflowId`，**完全不读 label** ⇒ 改它不影响编译与执行 |
| P-16 | **后端错误消息是中文自由文本，英文界面下直接露中文** —— 前端有 32 处把后端返回的 `error` 字段原样弹给用户（`message.error(j.error)` / `throw new Error(j.error)`），而后端抛的是**中文自由文本**（实测 `labos-kernel` 里去重后 **152 条**`IllegalArgumentException`/`IllegalStateException`/`SQLException` 文案，如「流程不能为空」「节点 X 缺少 kind」）⇒ 英文界面下操作失败时弹出来的是中文。**规模**：152 条消息 / 32 个显示点。**为什么不能直接翻译**：① 后端抛的是自由文本、不是 code ⇒ 前端无法按 key 反查（要先把它们改成「code + 参数」，后端返回 `{code, params}`、文案归前端）；② 部分消息含动态数据（节点 id、字段名、资源名）；③ 部分是给运维看的内部日志（不该翻译、也不该弹给用户）。**与已完成项的衔接**：**状态词表已经是这个模式**了（P-11 那次做的：50 个状态 + 3 个暂停类型 + 4 个扣留类型 + 3 个扣留作用域，后端只出 code、文案在前端语言包）⇒ 错误消息照同一口径做即可 | — | ADR-TECH-001 | **NOT_STARTED（建议分批）**：① 先只做**用户最常看到的那批**（保存/执行/绑定失败的提示，约 10~15 条），改成 `code + params`；② 前端加一张错误码 → i18n 键的映射，弹窗按 code 查文案；③ 后端**自己的日志文案保持中文不动**（那是给运维看的，不是界面文案）。**为什么本轮不做**：① 改动主体在**后端**（`labos-kernel` 的异常抛出点），而同一分支上另一个会话正在重构`QueryService`/Mapper ⇒ 大量改同一批文件会互相覆盖；② 前端 32 个显示点分散在十来个页面，**没有前端测试设施**（webapp 无测试运行器），只能靠人工点验，批量改动回归风险高；③ 152 条消息的**归类**需要产品口径（哪些给用户看、哪些只进日志），不是纯技术判断 ⇒ 建议先定这一批的范围再动手 |## 6. X 组：作废（不得实施）
| P-17 | **恢复策略写入没有在库边界归一大小写** —— PG 的 `recovery_strategy` 是枚举，只收小写（retry/skip/reassign/abort/manual/compensate），而界面与 Java 的 `RecoveryStrategy` 词表是大写。前端「错误码策略」页（`Policies.tsx`）原来直接发大写 ⇒ 保存必 500（`invalid input value for enum recovery_strategy: "RETRY"`）；**已修前端**（提交 `dcbab6c`，保存前 `.toLowerCase()`，与姊妹页 `ErrorCodes.tsx` 本来就用小写的既有口径对齐）。**剩余缺口**：后端写路径**不归一**，直接传大写会被 PG 拒 ⇒ 任何非前端调用方（脚本、直接调 API）传大写都会 500 | — | ADR-TECH-001 | **DONE（2026-11-10，落地提交 `dcbab6c` + `ff04e67`）**：**前后端两侧都归一** —— 前端 `Policies.tsx` 保存前 `.toLowerCase()`（`dcbab6c`）；后端 `OpsErrorService.upsertPolicy` 在**写库边界**归一 `strategy.trim().toLowerCase(Locale.ROOT)`（`ff04e67`，照既有约定那一对写法：`JdbcErrorOccurrenceStore` 写时 `.name().toLowerCase()`、`JdbcRecoveryPolicyRepository` 读时 `.toUpperCase()`）⇒ 任何调用方（前端 / 脚本 / 直接调 API）传大写都安全。**验证**：用**大写** `RETRY` 调 `POST /api/error-codes/{id}/policies` ⇒ **HTTP 200**（改前必 500），库里落的是小写 `retry` ✓（测试数据已删） |
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
| 0 | G-01~G-04 基线对齐、门禁拆段、补回归 e2e、分支归档 | push 授权 | 各相关段分别通过（G-03=4.17 17/0 + RunControlDbSelfTest §5.1；G-05=2.7 55/0）；M-01 符号仍在。**不以 `--all` 为前置** |
| 1 | B-01~B-04 分小 PR 搬到 main（每项含源码+回归测试+证据+文档） | Step 0 | 各 PR 自身的相关段实测通过（见 B 组各行），**不要求全量门禁** |
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

---

## 7. 基础设施：ID 号段（2026-11-09）

| 项 | 内容 |
|---|---|
| 背景 | 库里混着多套号段（`resource_node` 既有 `100027/100033` 也有 `504821/504831`；`workflow.id`~3784、`run.id`~209170；旧 `attr.island_id` 甚至用 `27/31/33` 两位数）。本次整改已踩过一次「两套编号对不上」（991 条悬空岛标签）。 |
| 动作 | `pg_rebuild/schema/99w_sequence_fresh_start.sql`：把 **69 个 identity 序列**统一抬到 **1,000,000** 起。条件式（仅 `last_value < 1000000` 才抬），且保证大于该表 `max(id)`，可重复执行、不退回。不改动任何已有行的 id。 |
| 效果 | 此后新建的任何行 id ≥ 1,000,000，与历史所有号段不重叠，一眼可分新旧；杜绝「手工插入大 id 导致 sequence 落后 → duplicate key」。 |
| 证据 | 抬升前 69 个序列**无一落后于 max(id)**（不是修 bug，是防患）；抬升后复核「仍低于 1,000,000 的序列 = 0」；插入验证行拿到 `id=1000000`；重跑分片输出「共抬升 0 个序列」且序列未退回；验证行已删。分片已并入 `90_all_in_one.sql`，`check-schema-sync.sh` 产物一致。 |

---

## 8. 架构：Spring Boot 容器与声明式事务（2026-11-09）

| 项 | 内容 |
|---|---|
| 起因 | 现场指出全仓 396 处裸 `db.open()` / 46 处手写事务是重复造轮子，要求直接用 Spring Boot 处理。 |
| 阶段一（**已完成**） | `pom.xml` 引入 `spring-boot-starter-jdbc` 3.2.5；`LabosApplication`（WebApplicationType.NONE）+ `DataSourceConfiguration`（HikariCP 替代手写 8 连接池、DataSourceTransactionManager、TransactionTemplate）+ `KernelRunner`（容器就绪后拉起原 ApiServer）；`DbConfig.open()` 桥接到 `DataSourceUtils`，396 处调用点零改动即进入 Spring 事务；反射实现使旧 classpath 零编译依赖。文档 `SPRING_BOOT_MIGRATION_20261109.md`。 |
| 证据 | `./scripts/build-kernel.sh --verify` **8/0**（事务内同一物理连接 / 抛异常全回滚 / 正常全提交 / 遗留 commit、rollback 不越界 / 解绑后旧池可用）；Spring Boot 实启 18080，登录与 topology/workflows/devices/workflows POST 全 200；`test-all.sh` **1310/0**、`ci-gate.sh --fast` 通过。 |
| 阶段二（未做） | Service 注册为 Spring Bean 后用 `@Transactional` 改写 P-02 三条写路径并收编 46 处显式事务；4.13 崩溃恢复段改用 Spring 启动（需同步改 `api_pids()` 的进程匹配）。 |
| 阶段三（未做） | HTTP 层由 JDK `HttpServer` 迁到 Spring MVC Controller。 |

**2026-11-09 后续批次（按现场决策：MyBatis XML、不留旧启动方式）**：A 批已完成——`ApiServer.main` 删除、Service 全部 Bean 化、4 个写入口 `@Transactional(rollbackFor=Exception.class)`、手写池/反射桥接/无容器回落全删、`verify-s3-2-residuals.py` 改按端口反查 + Spring 启动、唯一入口 `scripts/start-kernel.sh`；4.13 崩溃恢复 **34/0** 验证通过。B 批——Logback + MDC（`LogContext`）落地，关键路径 （B-01/B-02/中止结果/启动）已换 SLF4J，其余约 500 处 println 按模块迁移。C 批——`WorkflowMapper.xml` 首个垂直切片落地，`createWorkflow` 改走 MyBatis；剩余约 130 条 SQL、46 处显式事务、gateway Lettuce、HTTP 迁 Spring MVC 待做。验收 `build-kernel.sh --verify` **13/0**、`test-all.sh` **1310/0**、`ci-gate.sh --fast` 通过。权威文档 `SPRING_BOOT_MIGRATION_20261109.md`。**续（同日）**：539 处 `System.out/err` 全量换 SLF4J；新增 `RunMapper.xml`（5 条 SQL，`markRunFailed`/`settleRun` 改走 Mapper）；`QueryService` 21 个写入口加 `@Transactional`；`labos-gateway` 手写 RESP2 换 Lettuce（`DriverConnection` 零改动）。HTTP 层已迁 Spring MVC（Tomcat 接管 18080 + `ServletHttpExchange` 适配 + `LegacyRouteController`，40+ 端点逻辑不变；4.13 崩溃恢复 34/0、端点冒烟全 200）；拆成注解式 `@RestController` 仍待做。**不得以「已引入 Spring Boot」宣称 P-02 事务化或全部 SQL 迁移完成。**
