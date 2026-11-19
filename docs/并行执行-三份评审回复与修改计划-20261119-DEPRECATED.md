# 并行执行设计：三份评审的逐条回复与修改计划

> ⚠ **已废弃（2026-11-19）**：内容已被《并行执行-设计文档-20261119.md》取代或仅为历史记录，仅作追溯，**不再作为实施依据**。

- 日期：2026-11-19
- 基线：`d067104`（设计文档），源码 `bd6fa6f` 起未变
- 输入：三份评审（ChatGPT follow-up / Trae AI / MiniMax 08）
- 性质：**回复 + 计划**，未修改设计文档，未改代码
- 目的：把三份评审的去重意见逐条给出结论，并给出可评审的修改计划

---

## 0. 一页纸

| 项 | 内容 |
|---|---|
| 三份评审共提出 | 去重后 **24 条** |
| 完全接受 | 15 条 |
| 部分接受 / 修正表述 | 6 条 |
| 不接受的 | 2 条（含一条我之前判断错误，已修正） |
| 转化为"待决策" | 3 条（不是设计能定的） |
| 修改计划 | **4 个包**，包 1 不依赖任何外部决策，可立即开工 |
| 并行度 2 | 仍不批准；放行条件从"设计通过"改为"物理态数据源 + 事件归属协议 + 入口闭合" |

---

## 1. 三份评审的分工

| 评审 | 最强的地方 | 独有发现 |
|---|---|---|
| ChatGPT follow-up | 概念拆解：隔离级别、迟到 waiter、身份持久化 | F-03a（隔离级别）、F-03b（迟到 waiter） |
| Trae AI | 源码行号 + 具体反例 | Q1（E-05 锁序反例）、Q5（`control_state` 双写）、Q8（`physical_state` 停线） |
| MiniMax 08 | 认领自己的错 + 给更彻底的方案 + 判断"不可实现" | N-A（旁路）、P0-1 第四方案、N-G-2（无命令标识） |

三份**没有互相矛盾**，重合度约 60%。

---

## 2. 逐条回复（24 条）

### 2.1 锁顺序（3 条）

| # | 来源 | 结论 | 处置 |
|---|---|---|---|
| 1 | F-01 / Q1 / P0-1 | **接受，且采纳第四方案** | 见 2.1.1 |
| 2 | Q1（E-05 事务边界） | **接受，是我骨架设计的硬伤** | 见 2.1.2 |
| 3 | Q 生命周期三类资源 | 接受 | 包 4 出"锁的申请/释放时机矩阵" |

#### 2.1.1 P0-1：采纳"前缀判定"，不采纳三值 rank

三份都指出 rank 与真实顺序矛盾（设备 1 → 位置 2 → 轨道 1）。MiniMax 说透了根因：

> rank 应编码**申请槽位**，不是**存储表**。

**采纳方案**：断言从"rank 单调"改为"槽位集合必须是全局序列的前缀"：

```
{} ⊂ {DEVICE} ⊂ {DEVICE, POSITION} ⊂ {DEVICE, POSITION, RAIL}
```

- 允许跳槽、允许同槽多次、兼容批量申请与重入
- 只禁止"跳回前面"——那才是真正会成环的

**配套 A1（必须同时做）**：断言只能由语义拥有者发起一次。`RailZoneGuard` 先 `enter(RAIL)`，`DeviceLock` 内部**不再按自己的 TYPE 断言**，否则 RAIL(3) 后断言 DEVICE(1)，仍然倒退。

**不采纳**：
- B（改真实申请顺序）：先占最稀缺的轨道再排队等位置，机械臂吞吐进一步下降（R-03 已是瓶颈）
- C（只比跨组）：倒退恰恰发生在跨组，解决不了

#### 2.1.2 Q1：E-05 的锁序反例成立

Trae 给的证据：

```
决策 A 声明：workflow 行 → 实验行 → run 表
E-05 createNextBatchAttempt：已在事务内 lockExperimentRow（BatchOrchestrator:585-586）
若准入内联 ⇒ 实际顺序 = 实验行 → workflow 行 ⇒ 与 E-01 构成 ABBA
```

而且 `RunAdmission.start` 自带 `Tx.inTransaction`，**无法内联**。

**处置**：禁止 E-05 同事务内联，改为"事务外先准入，成功后再开事务建 run（只持实验锁）"。窗口由唯一索引兜底。

**代价（必须写进文档）**：唯一索引必须先于 E-05 上线，这与 ④ 原本"先事务边界、后唯一索引"的顺序冲突。**方案：唯一索引与 E-05 同一批次上线。**

### 2.2 命令与事件身份（6 条）

| # | 来源 | 结论 | 处置 |
|---|---|---|---|
| 4 | F-02 / Q2 / P0-4b | 接受 | 拆三实体 + 补持久化字段 |
| 5 | P0-4a | 接受"粒度错配"表述 | `command_id` 移出 `logical_operation` |
| 6 | F-03a / Q3（隔离级别） | **完全接受，这是我的漏项** | §2.4 补三条约束 |
| 7 | F-03b / Q3（迟到 waiter） | **接受，且是我文档内矛盾** | grace window + 反向回扫 |
| 8 | Q3（32 位 hash 碰撞） | 接受 | 改 64 位 |
| 9 | C（事件表唯一 DDL） | 接受 | 包 4 出迁移规格 |

#### 2.2.1 三实体拆分（F-02 / Q2 / P0-4b）

```
LogicalOperation   一次业务意图（operation_key / generation / result_trust）
  └ LogicalCommand   逻辑命令身份（仅去重设备需要）
      └ DeviceCommandAttempt  每次传输（device_command_id / attempt_no / 设备档位）
```

**`command_id` 从 `logical_operation` 移走**，改为 attempt 表上的 `UNIQUE(tenant_id, device_command_id)`。

**必须补的持久化字段**（三份都指出的同一个洞）：

| 维度 | 现在 | 要改成 |
|---|---|---|
| `loop` | `GraphRunner:738` 内存 `visits`，resume 从 1 重算 | 持久化到 `run_node.loop_round`，恢复时先读回 |
| `invocation` | 内存计数 | 持久化到 `run_node.visit_count` |
| `branch` | 未定义 | 定义嵌套网关下的唯一性规则 |

**前置能力**：恢复进循环体时，先从 `run_node` 恢复圈次游标。依据：`RunMapper.xml:695-706` 的 `insertNodeRound` 是 `INSERT...SELECT ... WHERE loop_index = 0`，无 `ON CONFLICT` ⇒ 恢复重跑同圈要么撞键只打 log，要么写出重复圈次行。

#### 2.2.2 §2.4 的三条落地约束（F-03a）

1. 明确 `READ COMMITTED`；用 RR/SERIALIZABLE 必须给并发测试与重试策略
2. 拿锁后的候选集查询**必须是后续独立 SQL 语句**（不能与取锁合并）
3. 锁持有到事务提交/回滚

**依据**：READ COMMITTED 下每条语句新快照，拿锁后能看到对方已提交的 waiter；REPEATABLE READ 下用事务快照，拿锁后仍可能看不到 ⇒ **advisory lock 只保证互斥，不保证可见性**。

#### 2.2.3 迟到 waiter（F-03b）

**这是文档内矛盾，不是待补充项**：

| 验收 | 内容 |
|---|---|
| W-04 | 事件先于 waiter 到达 → 不丢 |
| W-08 | 候选集 0 行 → 标 `UNATTRIBUTED` |

按现流程，事件先到会被标 UNATTRIBUTED，waiter 后到无法再匹配 ⇒ W-04 不可能成立。

**修正**：

```
① 0 候选时不立即终结，保持 PENDING（带 grace window）
② waiter 创建提交后，主动回扫该匹配域下未归属、未过期的事件
③ 两条路径共用同一个幂等匹配函数
```

### 2.3 物理态与恢复（5 条）

| # | 来源 | 结论 | 处置 |
|---|---|---|---|
| 10 | Q8 / N-B | **接受，这是上线即停线的风险** | 拆三值 + 默认值规则 |
| 11 | Q6（P0 空集） | 接受 | P0 标为理论级 + 给可用来源清单 |
| 12 | F-04 / Q7（耗材） | 接受 | 节点级四组检查 |
| 13 | N-C（机械臂空出无数据源） | 接受 | 人工点检落台账 + TTL |
| 14 | N-D（有效期） | 接受 | `confirmed_at` + `expires_at` |

#### 2.3.1 `physical_state` 三值（Q8 / N-B）

```sql
physical_state VARCHAR(16) NOT NULL DEFAULT 'NOT_COLLECTED'
-- NOT_COLLECTED：设备不支持/从未采集 → 降级 P1 + 告警 + 列入不可并行清单，不阻断
-- UNKNOWN      ：采集到但不确定     → 阻断
-- 具体值（in_motion / idle / ...）  → 正常判定
```

**关键区分**：**"设备不支持该能力" ≠ "设备明确回 unknown"**。混成一件事，上线当天全场不可派发。

#### 2.3.2 节点级恢复条件（F-04 / Q7）

| 节点类型 | 恢复前必须确认 |
|---|---|
| 加样 / 吸液 | 针状态、液面、耗材、是否可能部分出液 |
| 转运 | 板位、夹爪、目的位置、轨道状态 |
| 离心 | 转子/载具、盖锁、设备运行状态 |
| 孵育 | 温度、计时状态、样品位置、剩余工艺时间 |

每条物理观察记录：`subject / fact_type / value / source / observed_at / confidence / expires_at`。**过期必须重新确认。**

#### 2.3.3 P0 是空集（Q6）

§4.7.2 要求 P0 = "设备确认 + 独立传感器交叉验证"，但 §4.7.6 明确"不做视觉/称重"，实际数据只有扫码 ⇒ **P0 当前为空集，最高可达 P1**。

**修正**：把 P0 标为"理论级"；给出"可用第二证据"清单（在位传感 / 限位开关 / 扫码 / 人工点检）；无传感器的行一律降到 P1/P2。

### 2.4 入口与调度（4 条）

| # | 来源 | 结论 | 处置 |
|---|---|---|---|
| 15 | N-A（`insertExperimentFlowRun` 旁路） | **接受，且已查实** | 降级为受控调试端点 |
| 16 | A（DB 互斥点） | 接受，必须先定 | 包 1 |
| 17 | F-05 / B（outbox） | 接受 | 包 4 给表结构 |
| 18 | H-09（batch active 含 paused） | 接受，且有源码证据 | `('queued','running')` |

#### 2.4.1 N-A：旁路已查实

```
ExperimentActionController（HTTP 端点，steps=id:method:output）
  → ExperimentFlowRunner.run
    → insertExperimentFlowRun：workflow_id = NULL, status = 'running'
```

| 保护 | 为什么绕过 |
|---|---|
| `WorkflowRunGate` | `workflowId <= 0` ⇒ 直接 `allow` |
| `ExperimentScheduler` | 直接 `running`，无 queued，不进队列 |
| 资源粗筛 | 无 `tryHoldResources` |

**处置**：加环境开关（生产默认关闭）+ 强制审计 + 文档明确"此端点不占资源池、不参与并行调度"。要删则先查审计表 `action='experiment.flowrun'` 有无调用。

#### 2.4.2 H-09：源码证据

```
selectActiveAttempt：r.status IN ('queued','running','paused')   ← 含 paused
selectPausedBatchCandidates：该批必须无任何 attempt 行才可重跑
⇒ 已建过 attempt 的 paused 批"既被判活跃、又不可重跑"
```

**处置**：`activeBatchAttempt` 收敛为 `('queued','running')`，paused 走独立重跑入口。

### 2.5 暂停与恢复（3 条）

| # | 来源 | 结论 | 处置 |
|---|---|---|---|
| 19 | Q5（`control_state` 双写） | **接受，我之前不知道它已存在** | 改为聚合视图 |
| 20 | 批次暂停协调表 | 接受 | 包 4 给最小表结构 |
| 21 | 暂停中间态进度信息不足 | 接受 | 补 `blocking_run_id` / `blocking_reason` |

#### 2.5.1 `control_state` 已存在（Q5）

```java
StatusVocabulary.SCOPE_CONTROL = "control"
// 承载 experiment_run.control_state: NONE / PAUSE_REQUESTED / PAUSING / RESUMING
```

而我在 §4.1.1 建议落 `experiment.pause_state` ⇒ 双写，会出现"实验 pausing、run NONE"。

**修正**：experiment 级中间态 = **各 run `control_state` 的聚合视图**，不独立存储；`pause_blocked` 必须能定位到 `blocking_run_id`。

### 2.6 表述与边界（3 条）

| # | 来源 | 结论 | 处置 |
|---|---|---|---|
| 22 | N-F（`current_run_id` 归层） | 接受 | 从"设备态"移到"程序态" |
| 23 | 总纲 §0 "HTTP 零变化" | 接受 | 反向同步，补"并发后到的请求从能跑变成被拒" |
| 24 | 邀请稿补 N-A~N-G | 接受 | 已在本文件体现 |

### 2.7 我修正的两条判断

| 原判断 | 修正 | 原因 |
|---|---|---|
| **N-G-1：L5 守卫一直 SKIP 是缺陷** | **不是缺陷** | 提取线结构是"先等事件再搬"，事件本身即到位信号；L5 只面向"不等事件直接搬"的场景。让搬运节点补 `interactionId` 是冗余的 |
| **P0-1：把 rank 改成 1/2/3 即可** | **改为前缀判定** | rank 应编码申请槽位，不是存储表；三值仍会在 RAIL 之后断言 DEVICE 时倒退 |

**N-G-2 保留为真缺口**：`__awaitEvent` 无命令标识，两批等同一设备同类事件时会互相抢。

**解法已定（2026-11-19）**：不是 `processId`，而是 **`commandId` 回显**——

- 中控下发带 `commandId`（`DriverCommand` 已必填）
- 侧车 ACK / 结果 / 由命令触发的事件，全部原样回显
- 事件入库带 `command_id`，认领时精匹配
- **侧车与模拟器都必须实现**

完整规范见《设备命令与事件关联规范-commandId回显-20261119.md》。

**附带的术语纠正**：设备程序号不叫 `processId`，叫 `funcName`；`processId` 专指实验编排节点（`experiment_process.id`，BIGINT）。详见规范 §2.0。

### 2.8 术语纠正：`processId` 与 `funcName`（2026-11-19 新增）

评审过程中发现 `processId` 被用错了地方，需全局纠正：

| 概念 | 现名 | 应为 | 类型 | 生成方 |
|---|---|---|---|---|
| 设备程序/功能名（`P1` / `Centrifuge3000`） | `processId` ❌ | **`funcName`** | VARCHAR | 设备 |
| 实验编排节点（`experiment_process.id`） | 未记录 | **`processId`** | **BIGINT** | 中控 |

**依据**：`experiment_process.id` 是 identity BIGINT，而 `device_event.process_id` 是 VARCHAR(128)——**类型对不上**，说明它装的不是编排节点。`docs/设备事件协议契约.md:57` 的原话"后续精确工位/交互步骤匹配使用"也印证了这一点。

**处置**：

| 项 | 改动 | 落点 |
|---|---|---|
| DDL | `RENAME COLUMN process_id TO func_name`；新增 `process_id BIGINT` | 规范 §4.1 |
| 代码 | `DeviceEventRow` / `DeviceEventMapper.xml` / `DeviceEventController` 同步 | 规范 §4.1 清单 |
| 协议文档 | `设备事件协议契约.md` 第 45、57 行 | 待改 |
| 归属规则 | **只认 `commandId`**；`funcName` 与 `processId` 均为辅助信息 | 规范 §2.1 |

---

## 3. 修改计划（4 个包）

### 包 1：不依赖外部决策，可立即开工

| 任务 | 文件 | 改动 | 验证 |
|---|---|---|---|
| 1.1 TOCTOU 修复 | `PauseMapper.xml` / `PauseManager` | `isCrashRecoverable` 放行 `quarantined`；`resumeToQueued`/`resumeToAborted` 加状态条件；调用方检查影响行数 | 对 completed 调 resume → 0 行；quarantined 能进恢复链；并发 resume 一成功一冲突 |
| 1.2 准入统一 | `WorkflowWebService` / `WorkflowService.startAsync` / `PauseCoordinator` | 接入 `RunAdmission`；E-01/E-02/E-09 三条 | V-01/V-02/V-06 |
| 1.3 DB 互斥点 | `RunAdmission` | `workflow 行 SELECT FOR UPDATE`；E-05 改事务外准入 | 双连接并发只成功一个 |
| 1.4 唯一索引 | 迁移 | `run (tenant, workflow, active_slot)` | 并发插入冲突被拒 |
| 1.5 锁断言 | `DeviceLock` / `PositionLock` / `RailZoneGuard` | 前缀判定 + A1 | K-01 不误报；反向申请被拒 |
| 1.6 N-A 降级 | `ExperimentActionController` | 开关 + 审计 + 文档标注 | 生产关闭时 404/403 |

**包 1 里 1.3 与 1.4 必须同批上线**（E-05 的窗口需要索引兜底）。

### 包 2：设计改动（不依赖协议）

| 任务 | 文件 | 改动 |
|---|---|---|
| 2.1 §2.4 三约束 | 表设计 | 隔离级别 + 独立 SQL + 锁持有到提交 |
| 2.2 迟到 waiter | 表设计 | grace window + 反向回扫 |
| 2.3 三实体 | 表设计 | 拆表 + `command_id` 移出 |
| 2.4 圈次游标 | 表设计 + `GraphRunner` | `run_node.loop_round` / `visit_count` 持久化 |
| 2.5 32→64 位锁 | 表设计 | `hashtextextended` 或双参数 |
| 2.6 字段改名与迁移 | 迁移 + `DeviceEventRow`/Mapper/Controller | `process_id` → `func_name`；新增 `process_id BIGINT`（§2.8） |
| 2.7 `command_id` 入库与精匹配 | 迁移 + `claimEvent` | 见规范 §4；**必须在侧车与模拟器实现之后开启** |

### 包 3：物理态（依赖"谁写"的决策）

| 任务 | 文件 | 改动 |
|---|---|---|
| 3.1 三值 + 默认 | 迁移 + 设计基线 | `NOT_COLLECTED` / `UNKNOWN` |
| 3.2 归层 | 设计基线 | `current_run_id` 移到程序态 |
| 3.3 节点级恢复 | 设计基线 | 四组检查 + 节点类型表 |
| 3.4 有效期 | 设计基线 | `confirmed_at` + `expires_at` |
| 3.5 P0 空集 | 设计基线 | 标为理论级 + 来源清单 |

### 包 4：可靠性（P1）

| 任务 | 文件 | 改动 |
|---|---|---|
| 4.1 outbox | 总纲 + 迁移 | `run_dispatch_intent` + 60s 僵尸扫描 |
| 4.2 暂停协调表 | 暂停恢复 | `batch_pause_coord` + `blocking_run_id` |
| 4.3 事件表 DDL | 表设计 | 唯一迁移规格 |
| 4.4 生命周期矩阵 | 跨类锁 | 三类资源的申请/释放时机 |

---

## 4. 待决策（3 项，设计不能定）

| ID | 问题 | 需要谁 | 答案决定什么 |
|---|---|---|---|
| D1 | 设备/侧车能否回显 `commandId` | 设备供应商 + 侧车负责人 | 事件归属走精匹配，还是只能独占串行 |
| D2 | `physical_state` 由谁写、写什么 | 现场 + 设备 | §4.7 能否落地；并行度 2 的放行条件 |
| D3 | `insertExperimentFlowRun` 删还是留 | 运维（查审计） | 入口闭合范围 |

**D2 是最关键的**：如果设备只回 `done`，`physical_state` 永远是 `NOT_COLLECTED` ⇒ §4.7 无法落地 ⇒ "物理态未知必须阻断"这个放行条件**永远达不到** ⇒ 并行度 2 永远开不了。

所以放行条件应改为：**"物理态数据源与分级规则已确定"**，而不是"未知必须阻断"。

---

## 5. 请评审者确认的 8 个问题

1. **前缀判定**（`{} ⊂ {DEVICE} ⊂ {DEVICE,POSITION} ⊂ {…,RAIL}`）是否覆盖了所有真实调用路径？有没有"跳槽后又回到前面"的合法场景？
2. **A1**（RailZoneGuard 先 enter，DeviceLock 不断言）是否会让 DeviceLock 单独使用时失去保护？
3. **E-05 改事务外准入 + 唯一索引同批上线**，这个组合是否成立？窗口期还有别的风险吗？
4. **迟到 waiter 的 grace window 取多长**？太长会积压，太短等于没有。是否有比时间窗更好的判据？
5. **三实体拆分**后，`command_ledger` 现有数据怎么迁移？是否会出现一个 operation 对应多条 attempt 但无法回溯？
6. **`physical_state` 三值**中，`NOT_COLLECTED` 不阻断是否会让"设备不支持"变成默认豁免？有没有办法区分"暂时没采集"和"永远不会采集"？
7. **节点级恢复条件**是否应该由流程定义声明（而不是代码硬编码）？如果是，流程 DSL 加什么字段？
8. 本计划是否把某处"待决策"写成了"已定"？

---

## 6. 边界

- 三份评审的原文分别在 `CROSS-REVIEW-20261119-d067104-followup.md`（含第 10–12 节回复）与 `评审记录/20261118-08`。
- 本文件只做回复与计划，**未修改任何设计文档，未改代码**。
- 代码 0 行、表 0 张、测试 0 次。
- 并行度 2 不批准；包 1 完成后也只到"准入统一、并行度 1"。
