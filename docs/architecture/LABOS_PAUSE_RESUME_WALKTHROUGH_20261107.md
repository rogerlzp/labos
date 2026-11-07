# 暂停 / 恢复到底按什么走 + 五个待决问题的白话说明

日期：2026-11-07
基线：`main` @ `3debf78`（只读核对，未改代码）
用途：把「暂停、恢复、人工确认」的**真实链路**和几个待决问题讲清楚，供拍板。

> **修订（2026-11-07 用户裁决后）**：§4 的 Q-03 / Q-04 与 §5 中对应默认值**已被取代**，以 `ADR_INDEX.md` 为准：
> - Q-03 → `ADR-LOCK-001`：**Redis + PG 联合、按资源粒度锁（含设备）**，废弃 `islandId` 锁键 / JVM 内存锁 / 2h TTL；
> - Q-04 → `ADR-FAULT-SCOPE-002`：同一实验内任一 Workcell 故障 ⇒ **整个实验停**，不允许其他 Workcell 段继续。
> 本文 §0~§3 的暂停/恢复链路说明仍有效；「状态机多层维护」的专门讨论见 `LABOS_STATE_MACHINE_MULTILAYER_20261107.md`。

---

## 0. 一句话结论

**在 `main` 上，真正决定「停不停、能不能恢复」的不是 `status` 字段，而是 `runtime_hold` 表里那条 `released_at IS NULL` 的扣留记录。**
`status`（`paused` / `running` / `queued`）只是给界面看的即时投影。设计文档要做的事，是把这套「事实」补全（扣留 + 节点事实 + 命令事实 + 人工证据），再由聚合器算出展示状态——这正是 `ADR-PAUSE-002`（四正交事实）和 `ADR-PAUSE-003`（执行门禁）的意思。

---

## 1. 现在（`main`）一次暂停 / 恢复的真实时间线

场景：实验 E 在岛 A 上跑，某个设备方法正在执行，操作员点了「暂停」。

| 时刻 | 发生什么 | 代码位置 |
|---|---|---|
| T0 | `RunControl.pause()` 先算**实际生效作用域**（岛未分配时自动退化成实验级，避免「按了暂停却什么都没停」） | `RunControl.java:88-94`, `effectiveScope:108-115` |
| T1 | `RuntimeHold.raise(...)` 往 `runtime_hold` 插一条 `released_at=NULL` 的扣留 → **真正的闸门立起来了** | `RunControl.java:95`, `98_m95_run_hold.sql:32-49` |
| T2 | `markCoveredPaused()` 把 `experiment_run.status` 和 `experiment.status` 改成 `paused` → **界面立刻显示已暂停** | `RunControl.java:130-172` |
| T3 | 底层 workflow 级 `run` **此时还在跑**。它每到一个节点边界查一次：`isRunPaused(runId) \|\| coveringForWorkflow(workflowId) != null` | `WorkflowService.java:840-843` |
| T4 | 命中的话 `pauseRunAtBoundary()` 把 `run` 落成 `paused`、后续节点留 `pending`。**不硬杀在途的设备动作**，当前动作做完才停（协作式停止） | `WorkflowService.java:2459-2481` |
| T5 | 调度器 `dispatch()` 前查 `anyActive()`，有扣留就不派发新实验 | `ExperimentScheduler.dispatch` |
| T6 | 「恢复」`RunControl.resume()`：① 查 `coveringFor(experimentId, islandId)`，只有 `scope != SITE` 才 `clear()`（**全场闸门不允许单实验恢复顺手解掉**）② `experiment_run.status='queued'`，清 `pause_kind/reason/paused_at`，同步 `experiment.status` ③ **把旧 paused 的 workflow 级 `run` 置为 `aborted`** ④ 再查一次扣留，仍有就如实返回「仍被现场扣留」 | `RunControl.java:235-298` |

**第 ③ 步为什么关键**：恢复会把当前这一步**整段重跑、另起一条新 run**。旧 run 若留在暂停队列里，值班员误点「继续」会让机械臂把同一个动作再做一遍——物理副作用不可撤销。这就是 `3debf78` 修的问题。

三个必须知道的现状缺陷：

1. `runtime_hold` 的唯一索引 `uk_runtime_hold_active` 限制**同一 scope 同时只能有一条生效扣留**，`clear()` 又是按 scope 整批清 ⇒ 用户暂停和设备故障**不能并存**，解一个会把另一个也解掉（`ADR-PAUSE-005` 要改）。
2. `RuntimeHold.active()` 查询抛 `SQLException` 时**打日志后返回空列表** ⇒ 「读不到扣留」被当成「没有扣留」，是 fail-open（`P-03` 要改成 UNKNOWN + 阻断）。
3. T1 / T2 用的是**不同数据库连接、非同一事务**；`markCoveredPaused` 里 `experiment_run` 和 `experiment` 也是两次独立 UPDATE ⇒ 中途失败会留下三层不一致。

---

## 2. 设计文档最终要把它走成什么样

目标链路（`ADR-PAUSE-001/002/003`）：

```
点暂停 → PAUSE_REQUESTED（请求已登记，不等于已停）
       → 在途命令收敛 → PAUSING
       → 节点/设备事实都确认 → PAUSED
       → 若需人工 → WAITING_INTERVENTION（这是「执行门禁」，不是 status 值）
恢复：所有 Hold 已按 hold_id 清除
   + 在途命令已收敛/已对账
   + 必要的人工处置证据（InterventionRecord）到位
   + 设备自检 / 联锁复位 / 物理位置核验通过
   → 才允许 RESUMING
```

**判据不是「某个 status 等于什么」，而是「还有没有阻断原因 + 在途事实收没收敛」。**

现状与目标的差距：

| 目标 | main 现状 |
|---|---|
| `PAUSE_REQUESTED` / `PAUSING` 等中间态 | 全仓 **0 命中**；API 点完暂停立刻回 `paused` |
| `WAITING_INTERVENTION` | 不存在；`experiment_run.status varchar(16)` 连 20 字符都放不下 |
| `InterventionRecord`（人工处置证据） | 无此表/类 |
| 命令级账本、`UNKNOWN → RECONCILING` | `run_command` 复用 `node_status`、步结束才落库、无 Outbox |
| 请求 / 状态 / 事件 / Outbox 单事务 | 多连接分步写 |

⇒ 所以上一轮说「分层要加」不是加概念，而是**把「凭什么判定可以恢复」从「看一个 status 字段」补成「看一组事实」**。

---

## 3. Q-09 / Q-10 到底是什么（用同一个场景）

场景：**实验跨 A、B 两个岛**。A 岛的 Workflow 跑完，实验板要由**人**搬到 B 岛，然后 B 岛的 Workflow 才能开始。你已拍板这一步由人工完成、配置期定义好。

### Q-09：人在界面点「板已放到 B 岛设备 X」，**这条证据存到哪**

- 为什么会有这个问题：系统不能因为「配置里写了 A→B」就自动往下跑，必须等**这一次**的人为确认。这条「谁、什么时候、确认了哪块板、从哪到哪、结果如何」得有地方落。
- 选项 A：新建 `intervention_record`（设计文档一直这么叫，`ADR-PAUSE-003` 要求人工待办由它表达）。
- 选项 B：复用现有 `manual_window` 表。**但它现在是「出错转人工」的弹窗记录**：字段是 `run_id / error_code / node_key / default_action / allowed_actions / decision_reason`，动作白名单只有 `CONTINUE / RETRY / SKIP / ABORT / MANUAL`，语义是「这个错误怎么处理」，**没有「样本到位确认」这个动作，也没有源/目标交接点、板号、确认结果字段**（`98b_manual_window.sql:22-46`、`ManualWindow.java:29-30`）。
- **建议**：用 `manual_window` 做「开门 / 等人 / 决策」的流程骨架，**到位证据单独落 `intervention_record`**（与 N-08 同批，即 `L-01`）。`ADR-PAUSE-007` 说的是不要新建第三套 **Hold/状态机**，不是不要证据表；而且 Hold 表示持续闸门、InterventionRecord 表示一次人工处置事实，职责本就不同。
- 一句话：**Q-09 问的是「人点确认这条证据存哪张表」**，不是问要不要人工确认（那个已定）。

### Q-10：人工配置的「A 岛出口 → B 岛入口」**存到哪**

- 为什么会有这个问题：你说「配置岛内设备 / IP 时就把 Handoff 做好」，那这份配置得有结构可存：源/目标 Workcell、交接点、板与样本标识、需确认项。
- 选项 A：扩展现有 `experiment_process_edge`（`from_id → to_id`，实验内**流程之间的偏序**，主键 `(from_id,to_id)`）。跨岛衔接本质就是「流程 A 完成 → 流程 B 才能开始」的一条边，加几列 `handoff_type / source_point / target_point / require_confirmation` 即可。
- 选项 B：新建 `experiment_handoff` 表，与偏序边分开。
- **为什么不能用 `island_flow.graph`**：它存的是**岛内**动线（设备坐标 + 搬运箭头），而且边只落 `{id,source,target}`，连 `type/label` 都不持久化（`IslandFlow.tsx:200-203`），表达不了跨岛。
- **建议**：**扩展 `experiment_process_edge`**（`N-07`）。跨岛衔接天然是两流程之间的一条边；新建表会多一次 join，且容易出现「边改了、handoff 配置没改」的不一致。该表 DDL 注释写着「加了就必须被读」——正好对应「配置期定义、运行时门禁读取」。
- 一句话：**Q-10 问的是「配置期那份跨岛衔接信息存哪张表」**。

> 这两个都可以在对应 PR 里定，不必单独开决策会。

---

## 4. Q-03 / Q-04 / Q-05 是什么（同样用场景）

### Q-03：跨 A、B 两岛的实验，**怎么加锁**

现在 `ExperimentScheduler` 用**一个** `islandId` 当队列键和 JVM 内 `ConcurrentHashMap` 锁键，TTL 2 小时。跨岛实验要同时占 A 和 B，锁就得改成「锁一个 Workcell 集合」，而且必须**按固定顺序（如 ID 升序）获取**，否则两个实验会互相死锁；TTL 到期还可能让别的实验误以为岛空了。
→ 要你拍的是：**按集合加锁 + 稳定排序是否认可**；2h TTL 是这次顺手改成 DB 锁，还是留到 `P-08`。建议：先按集合 + 排序，TTL 暂留但标记为不可作物理互斥依据。

### Q-04：A 岛设备故障时，**这条跨岛实验的哪些部分要停**

现在 `RuntimeHold` 按 `coveringFor(experimentId, islandId)` 判定，`islandId` 来自 `e.island_id` 单列。跨岛实验的 A 段和 B 段在不同岛，判据得换：按**节点实际所在 Workcell**（最细，A 段停、B 段满足条件可继续）、按 **Workflow 段**、还是按 **Handoff 边界**（整段卡在交接处）。
→ 建议：**按节点实际 Workcell 判定，依赖关系或状态 UNKNOWN 时扩大阻断**（与 `ADR-FAULT-SCOPE-001` 一致），并把判定依据换成 `N-02` 的 Workflow 权威 Workcell。

### Q-05：`Functional Island` 什么时候引入

它是「跨物理 Workcell 的纯逻辑工艺分组」，当前**不建实体**。问的是何时引入、引入后怎么与 Workcell 做字段隔离。
→ 建议：**无限期延后**。等真出现「跨岛但逻辑上属于同一工艺组」的需求再说；现在引入只会和 Workcell 混淆，正是要避免的压平。

---

## 5. 如果不想逐个拍板：一组保守默认值（确认一次即可）

| 问题 | 默认值 |
|---|---|
| Q-09 | `manual_window` 管开门/决策，`intervention_record` 管到位证据（L-01 与 N-08 同批） |
| Q-10 | 扩展 `experiment_process_edge`，不新建表 |
| Q-03 | 按 Workcell 集合加锁 + ID 升序；2h TTL 暂留，明确不得作为物理互斥依据 |
| Q-04 | 按节点实际 Workcell 判定；UNKNOWN ⇒ 扩大阻断 |
| Q-05 | 延后，当前不建 Functional Island 实体 |

这组值全部是**保守方向**（宁可多阻断、不多放行），符合安全边界类系统的默认策略。确认后我把它们写进 `ADR_INDEX.md` 并据此更新 `REMEDIATION_STATUS.md`。
