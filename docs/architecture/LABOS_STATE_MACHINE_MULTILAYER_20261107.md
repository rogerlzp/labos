# LabOS 状态机多层维护：现状与治理

日期：2026-11-07
基线：`main` @ `3debf78`（只读核对，未改代码）
触发：用户提问「之前审核的状态机的多层维护，现在是什么情况，有专门的文档来讨论了吗？」

---

## 0. 直接回答

1. **有专门文档吗？** 仓库原有 **203 份**文档里**没有一篇**专门讨论「状态机多层维护」。相关内容散落在实测记录、`BUG台账.md`、测试用例总表、开发日志，以及一份**单套**执行状态机设计（`docs/暂停恢复与故障处理-设计.md`）。本轮新建的 `docs/architecture/` 里，`LABOS_PAUSE_AND_INTERVENTION_STATE_MODEL_20261107.md` §2/§5/§12 是第一次系统讨论，但它来自评审分支的设计归档。**本文是第一份以「多层维护」为主题的专门文档。**
2. **现在什么情况？** 代码里仍是**两套主状态机并存 + 四张附属状态各自直写，无统一协调器，无聚合投影器**。`3debf78` 只是把三层状态对齐的**约定式补丁**，没有合并两套模型。
3. **要不要补？** 要。而且发现一个必须先解决的**验收口径冲突**（见 §6）：原有测试仍以「三层都 `paused`」为通过判据，而新设计说 `status` 只是投影。不改判据就动状态机，会让既有 e2e 与新模型互相打架。

---

## 1. 代码现状：谁在写什么

| 入口 | 写哪张表 / 哪个字段 | 备注 |
|---|---|---|
| `PauseManager` | **`run.status`** + `pause_kind/pause_reason/paused_at/pause_node_key`；兼写 `plate.position_status`、`position_state`、`resource_booking`、`device_state` | **旧 run 模型** |
| `RunControl` | **`experiment_run.status`** + 控制列、**`experiment.status`**、以及 `run.status`（恢复时作废旧 run） | **新 experiment_run 模型** |
| `RuntimeHold` | `runtime_hold`（INSERT / `released_at`） | **不写任何 run 状态**，是真正的阻断闸门 |
| `WorkflowService` | `run.status`、`run_node.status`、`run_command` INSERT；`pauseRunAtBoundary` 落 `run.paused` | 节点边界检查的实际落库点 |
| `ExperimentExecutor` | `experiment_run.status`、`experiment_run_step.status`、`experiment.status` | |
| `ExperimentScheduler` / `BatchOrchestrator` | `experiment_run.status`、`experiment.status` | 批次按 D6 聚合后回写 |
| `JdbcManualWindowStore` | `manual_window.status` | 人工窗口 |
| `GraphRunner` / `RunStateMachine` / `StatusVocabulary` / `JdbcEventStore` | **不落状态**（内存状态机 / SQL 片段生成 / `run_event` append-only） | |

**写入点统计（`labos-kernel/src/main/java`）**：`run.status` 约 10 处、`experiment_run.status` 约 9 处、`run_node.status` 4 处、`experiment.status` 3 处、`manual_window.status` 3 处、`experiment_run_step.status` 1 处、`runtime_hold.released_at` 1 处、 **`run_command.status` 0 处 UPDATE**（只有 INSERT，步结束才落库）。

### 两套主状态机并存

- `PauseManager.pause()` → `UPDATE run SET status='paused'...`（`PauseManager.java:112`）
- `RunControl.pause()` → `UPDATE experiment_run r SET status='paused'...`（`RunControl.java:141`）

两张表、两套 `pause_kind`，**互不引用**。两者靠约定衔接：`RunControl.resume()` 里有一段注释说明要去作废 `PauseManager.pausedRuns` 会查到的孤儿 run（`RunControl.java:272-284`）；`RuntimeHold.Kind.of()` 特地兼容 `PauseManager.Kind` 的 code（`RuntimeHold.java:93`），注释还写明两者「释放已占未用」的口径**相反**（`RuntimeHold.java:33`）。

**没有统一协调器**：`PauseCoordinator` / `ExperimentStateProjector` / `StateProjector` 在 Java 源码里 **0 命中**，只出现在 `docs/architecture/` 的设计文档中；`REMEDIATION_STATUS.md` 里 `P-06`（PauseCoordinator）= `NOT_STARTED`，`L-05`（完整聚合器投影）= `DEFERRED`。

**所谓「投影」目前只是 SQL 片段 + 直接 UPDATE**：`StatusVocabulary.runToExperimentCaseSql()` 生成 CASE 片段，`ExperimentExecutor.syncExperimentStatus` 和 `RunControl` 各自拿它 `UPDATE experiment SET status=...`；批次侧 `BatchPlanner.aggregateExperimentStatus`（D6 纯函数）也仍是直接回写。没有「由事实算状态」的聚合器。

---

## 2. `3debf78` 修了什么、没修什么

| 修了 | 没修 |
|---|---|
| 实验级暂停时底层 workflow `run` 也停（三层都 `paused`） | 两套模型仍是两套，未合并 |
| `RuntimeHold.coveringForWorkflow` 按 `experiment_run_step.status='running'` 反查 | 没有统一协调器 / 聚合器 |
| `resume()` 作废旧 paused run，防重复物理动作 | `run.status` / `experiment_run.status` 仍各自直写 |
| 门禁 `MIN_PURE=651` / `MIN_MATRIX=55` / `MIN_PAUSEEXP=5` | 中间态、`InterventionRecord`、命令账本、单事务仍缺 |

结论：它是一次**高质量的约定式补丁**，但「谁维护状态、谁投影状态」这个结构问题没动。文档里把它记为 `M-01`（VERIFIED）；`STATE_MODEL §12.2` 第 5 条「两套暂停模型并存」至今仍是待办。

---

## 3. 事实 vs 投影：目标职责划分（`ADR-PAUSE-002/003`）

| 层 | 职责 | 允许谁写 |
|---|---|---|
| **Hold Ledger**（`runtime_hold`） | 持续生效的阻断原因；按 `hold_id` 精确清除 | Hold 服务 |
| **Command Execution State**（命令账本） | 一次设备命令的事实，含 `UNKNOWN → RECONCILING` | 命令服务 / 驱动回调 |
| **Node / Step 事实**（`run_node`、`experiment_run_step`、`run_command`） | 节点当前在做什么、命令是否仍在途 | 执行器 |
| **Alarm / Intervention**（`InterventionRecord`） | 报警与人工处置证据 | 人工处置服务 |
| **Run Control State**（`experiment_run.status` 等） | **投影**：由上面四组事实聚合出的展示状态 | **只有聚合器**，业务入口不得直接 UPDATE |

现状是「业务入口直接 UPDATE status，且多层同时写」；目标是「入口只写事实，聚合器算状态」。

**为什么必须这样**：暂停请求发出时设备动作可能还在跑，API 若立即把 `experiment_run.status` 写成 `paused`，就等于**用展示状态谎报物理事实**。恢复时如果只看 `status`，也会在命令仍 UNKNOWN 的情况下放行。

---

## 4. 两套 run 模型的迁移边界（`ADR-PAUSE-007`）

不能直接删 `PauseManager`：旧 `run` 模型仍被暂停队列（`pausedRuns` 查 `run.status='paused'`）、板位盘点、资源释放、`RunDetail`/`PausedRuns` 前端消费。

迁移三步：

1. **建映射表**：逐个列出旧 `run` 与 `experiment_run` 的创建/关闭时机、关联基数、API 入口、`pause_kind` 词表差异、重启恢复行为。
2. **引入 `PauseCoordinator`（适配层，不是新表）**：所有暂停/恢复/停止入口统一走它；内部按 run 类型适配到 `PauseManager` 或 `RunControl`，并**在同一事务**写 Hold + 控制意图 + 节点暂停请求 + Event/Outbox。
3. **逐入口迁移 + 反查收口**：`coveringForWorkflow` 这类「按 workflow 反查实验」的桥接逻辑收进 Coordinator；旧入口保留只读兼容，直到前端与自测全部迁移完，才移除 `PauseManager` 的写路径。

**必须保留的契约**：`RunControl.resume()` 作废旧 paused `run` 的行为（`M-01`）——它防的是「误点继续 → 机械臂重复动作」，任何重构都要有对应回归测试（G-03）。

---

## 5. 暂停 / 恢复的一致性契约

一次暂停的正确顺序：

```
请求 → 单事务写 Hold + 实验级 PAUSE_REQUESTED + 活动节点 PAUSE_REQUESTED + Event/Outbox
     → 节点边界停止派发新工作（在途命令继续收敛）
     → 设备/节点事实全部收敛 → 聚合器算出 PAUSED
     → 需人工 → ExecutionGate = WAITING_INTERVENTION（不是 status 值）
```

恢复门禁（**全部满足**才允许 `RESUMING`）：

- 所有覆盖该实验的 Hold 已按 `hold_id` 清除（SITE 级不得由单实验恢复顺手解掉）；
- 在途命令已收敛，或已进入 `RECONCILING` 且人工对账完成；
- 必要的人工处置证据（`InterventionRecord`）到位；
- 设备自检、联锁复位、物理位置核验通过；
- 旧 paused `run` 已作废，不会重放物理动作。

**硬约束**：任何 Hold / 依赖 / 设备状态查询失败 ⇒ `UNKNOWN` ⇒ 阻断（`RuntimeHold.active()` 现在失败返回空列表，是 fail-open，必须改）；TTL 到期只释放软件租约，不释放物理隔离。

---

## 6. ⚠️ 必须先解决的验收口径冲突

| 来源 | 判据 |
|---|---|
| `docs/测试用例总表.md:51,216`、e2e 4.17（M-01） | 「**三层都停在暂停**：`experiment_run` / `experiment` / 底层 workflow `run` 都变 `paused`，后续节点 `pending`」 |
| 新设计（`LABOS_PAUSE_RESUME_WALKTHROUGH_20261107.md`、`ADR-PAUSE-002/003`） | 「`status` 只是给界面看的即时投影，判据应看 Hold + 节点 + 命令事实」 |

**这不是措辞差异，是通过条件冲突**：如果按新模型把 API 改成返回 `PAUSE_REQUESTED`、由聚合器稍后才算出 `PAUSED`，现有 4.17 的 5 条断言会立刻变红；反过来若为了满足断言继续让 API 立即写 `paused`，中间态与 fail-closed 就永远落不了地。

处置建议（**实施前必须定**）：

- `M-01` 的三层真停行为**保留为回归基线**，但判据从「三个 status 值」升级为「三个 status 值 **且** Hold 有效 **且** 在途命令已收敛」；
- 新增断言验证「请求已接受但尚未收敛时不得显示 `PAUSED`」；
- 分两个 PR：先加新判据与中间态（保持旧断言并行通过），再切换 API 语义；不要在一个 PR 里同时改实现和判据。

---

## 7. 治理工作项

对应 `REMEDIATION_STATUS.md` 新增 **S 组**：

| ID | 工作项 |
|---|---|
| S-01 | 两套 run 模型映射表（字段、调用链、词表差异、创建/关闭/重启行为） |
| S-02 | `PauseCoordinator` 适配层：统一入口 + 单事务写 Hold/意图/事件/Outbox，`M-01` 契约不回退 |
| S-03 | 事实 vs 投影职责切换：`experiment.status` 只允许聚合器写；`StatusVocabulary` CASE 回写收口 |
| S-04 | 验收口径切换：4.17 判据升级（§6 的两个 PR 方案），`测试用例总表.md` 与中英文档同步 |
| S-05 | 本文档与原有 26 份状态相关文档建立索引，标注哪些已过时、哪些仍是权威 |

---

## 8. 原有相关文档索引（哪些不是专门讨论）

| 文档 | 性质 |
|---|---|
| `docs/暂停恢复与故障处理-设计.md`（496 行） | **单套**执行状态机设计（§三状态机、§六完整执行状态机），不讨论两套并存 |
| `docs/设备执行中暂停-现状实测与实现方案-20261106.md` | 实测记录（§2.6 三层都停），不是模型讨论 |
| `docs/暂停与恢复语义验证-四类节点-20261106.md` | 四类节点暂停点 / 断点续跑语义 |
| `docs/M4-暂停恢复与故障处理.md`、`docs/暂停队列界面.md`、`docs/流程控制-技术设计.md` | 验收 / UI / 流程控制单侧 |
| `docs/实验编排-功能设计.md`、`docs/设计-实验编排统一-v0.1-草稿.md` | 编排模型与状态词表；后者 §1.1「两套模型」指 **编排模型**，不是暂停状态机 |
| `docs/评审记录/BUG台账.md:34,177,185-188` | `BUG-20261107-01` 三层真停的处置记录 |
| `docs/测试用例总表.md:51,176-178,216` | 通过判据（见 §6 冲突） |
| `docs/开发日志.md:4600` | P2 状态映射收口（`run→experiment` 同口径 CASE） |
| `docs/设备网关与Redis状态中心-设计.md` | Redis 状态中心（供锁方案参考，非状态机讨论） |
| `docs/评审记录/20261102-03-code-self.md:261` | 「双套路径」指 driver/workflow 两套执行路径，非状态机分层 |

⇒ 结论：原有材料**足以拼出事实**，但散且口径不一；`S-05` 的索引工作不能省，否则下一个人还会把「三层都 paused」当成新设计的验收判据。
