# PR-S04-B 实施计划：暂停状态语义与派发并发保护

日期：2026-11-07
状态：**计划（PLAN，未实施）**
关联工作项：S-04（第 2 个 PR）、L-02、P-03、P-06/S-02、P-07/L-03、P-09、S-03
关联契约：`LABOS_STATE_MACHINE_ACCEPTANCE_CONTRACT_20261107.md`、`LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md`、`LABOS_RUN_MODEL_MAPPING_S01_20261107.md`
状态真源：`REMEDIATION_STATUS.md`（本文不复制工作项状态）
执行基线：`main` @ `3debf78`；目标分支 `review/labos-architecture-20261107`

---

## 0. 结论摘要

PR-S04-B 解决的是**一个语义错误**：当前 API 在收到暂停请求时就立刻把实验标成 `paused`，而设备动作此时可能仍在执行。用户看到「已暂停」，设备还在动。

- **PR-S04-A（已完成验证）**：保留 legacy API/status，只补齐可核验事实证据（Hold、设备在途、节点边界、时间线）。已证明 legacy 三层 `paused` 契约未退化。
- **PR-S04-B（本计划）**：把「请求已被接受」与「暂停已完成」分开投影；在此之前，任何中间态实现都会让既有 4.17 变红。

**核心约束**：`PAUSED` 表示软件执行层已到达安全暂停点（无未决命令），**不表示设备断电或物理绝对静止**。物理静止需设备反馈或传感器确认。

**前置依赖未满足前不得开工**：`REMEDIATION_STATUS.md` Step 7 规定 PR-S04-B 依赖 L-02、P-03、P-07/L-03、S-02/P-06。本计划给出依赖满足后的执行顺序与验收口径。

---

## 1. 现状（源码事实，含位置）

| 位置 | 当前行为 | 缺口 |
|---|---|---|
| `labos-kernel/.../api/ExperimentApi.java:186` | `ctl.pause(...)` 之后立即返回 `"status", "paused"` | 「请求已接受」被当成「暂停完成」 |
| `labos-kernel/.../workflow/WorkflowService.java:2435-2447` | `isRunPaused()` 只查 `run.status = 'paused'`；`SQLException` → `return false` | 查不到就不阻断执行 = **fail-open** |
| `labos-kernel/.../workflow/WorkflowService.java:2488-2532` | `settleRun()` 发现已 `paused` 就直接 `return` | 无法区分「暂停请求」与「暂停完成」 |
| `labos-kernel/.../workflow/RuntimeHold.java:274-296` | `active()` 读库 `SQLException` → 返回空列表 | 读失败 = 「现场无扣留」 = **fail-open**，派发继续 |
| `labos-kernel/.../workflow/ExperimentFlowRunner.java:111-113` | `finishNode(...)` 先执行，`writeCommand(...)` 后执行 | 节点写成功、命令写失败 ⇒ 状态不一致（暂停/崩溃恢复/审计都受影响） |
| `run_command` 写入点 3 处 | `ExperimentFlowRunner.java:383`、`WorkflowService.java:2085`、`WorkflowService.java:2319` | 命令没有独立执行状态，复用 `node_status`，且是步结束后才落库 |
| `run.status` / `experiment_run.status` | 两套独立字段、独立写入点、指向两张表 | 无统一事务边界（见 S-01 映射文档） |

---

## 2. 目标状态模型：三层分离

三层**不可互相替代**。

### 2.1 Run（实验运行）

| 字段 | 负责 | 取值 |
|---|---|---|
| `status` | 生命周期 | `RUNNING` / `PAUSED` / `COMPLETED` / `FAILED` / `ABORTED` … |
| `control_state`（新增） | 暂停控制过程 | `NONE` / `PAUSE_REQUESTED` / `PAUSING` / `RESUMING` |
| `pause_kind` | 为何暂停 | `USER_REQUEST` / `WAITING_HUMAN` / `SAFETY_INTERLOCK` … |
| `pause_reason` | 具体原因 | 自由文本 |

### 2.2 Workflow Node

`run_node.status`：`PENDING → RUNNING → COMPLETED`；仅有可靠断点的复合节点才允许 `PAUSED`，否则进入人工确认或对账。

### 2.3 Device Command

`run_command.execution_state`（新增）：`CREATED → DISPATCHED → ACKNOWLEDGED → RUNNING → SUCCEEDED`；异常：`UNKNOWN → RECONCILING → SUCCEEDED / FAILED / MANUAL_INTERVENTION`。

---

## 3. 待裁决设计点（需 ADR）

### 3.1 中间态放哪里：`status` 还是新列 `control_state`

- **方案 A（L-02 原描述）**：把 `PAUSE_REQUESTED` / `PAUSING` 塞进 `experiment_run.status`，`varchar(16)` 需扩容（`WAITING_INTERVENTION` 20 字符已放不下）。
- **方案 B（本计划推荐）**：`status` 只表达生命周期，中间态放新列 `control_state`。理由：`paused` 现已被复用为「等待人工」，若再把暂停中间态塞进同一列，语义会随时间被压成一个多义字段，后续极难维护；且 `status` 的读方（页面、聚合器、门禁）数量远多于新的 `control_state`。

无论 A/B，L-02 的**列长扩容仍要做**（`WAITING_INTERVENTION`）。建议落为一条 ADR（拟 `ADR-PAUSE-008：控制状态与生命周期分离`），并在 `ADR_INDEX.md` 登记。

### 3.2 派发并发保护机制

暂停请求与新命令派发必须互斥。仅靠 worker 周期查 `run.status` 存在 TOCTOU 窗口（worker 查到 `RUNNING` → 用户请求暂停 → 写入 `PAUSE_REQUESTED` → worker 仍派发下一条命令）。候选：

- `run.execution_phase`（P-09：`PREPARED / DISPATCH_STARTED / SETTLED`）+ 行锁；
- 或 `SELECT … FOR UPDATE` 把「检查是否允许派发」与「登记命令已派发」合成一个受保护操作。

---

## 4. 暂停协议（时序）

```text
RUNNING
  │ 用户点击暂停：单事务写 control_state=PAUSE_REQUESTED, pause_kind, pause_reason
  │ 禁止新执行任务进入派发（Node 状态不立即改）
PAUSE_REQUESTED
  │ 执行引擎确认停止新派发
PAUSING：等待已派发命令完成；Node 保持真实状态（RUNNING/COMPLETED）；在途命令保持原状态
  │ 设备回包成功 → 命令 SUCCEEDED，节点完成或进入安全断点
  │ 设备回包丢失 → 命令 UNKNOWN，节点保持待核实，**不得**判定暂停完成
PAUSED：单事务内确认「无未决命令 + 节点已持久化 + 资源/板位事实满足」后落 status=paused, control_state=NONE, paused_at=now()
  │ 用户点击继续
RESUMING → 核验设备/板位/资源/断点后 → NONE / RUNNING
```

**关键规则**：

1. 暂停请求时**先改实验控制状态**，节点保持真实状态。否则存在竞态窗口：节点被认为已暂停，调度器仍认为可执行。
2. 节点和命令状态写入、以及实验最终 `PAUSED` 投影，必须在**同一 PostgreSQL 事务**内；审计事件走同事务 Outbox。
3. `PAUSING` 期间设备命令超时/断网/响应丢失，**不得**因等待超时改判 `PAUSED`；保持 `PAUSING` 并增加 `pause_block_reason`（界面显示「暂停等待中：设备响应未确认」）。
4. 暂停完成的依据是**命令账本 + 节点状态**，不是只看 `run.status`。

---

## 5. 分批实施

### 批次 1：状态模型与暂停协议

范围：`control_state` 字段与状态机、暂停请求事件、暂停完成条件、API 返回 `request accepted`、UI 状态语义、派发并发保护（§3.2）。
对应工作项：L-02 + P-06/S-02 + P-09 + S-04 第 2 个 PR 的 API/UI 部分。
**不在本批**：命令账本完整化。

### 批次 2：节点与命令一致性

范围：Command Ledger（`execution_state`）+ 原子化「节点 + 命令」写入；修 `ExperimentFlowRunner.java:111-113` 的顺序问题；`UNKNOWN → RECONCILING`。
对应工作项：P-07/L-03。**不另起一套账本**，并入 `run_command`。

### 批次 3：恢复与故障注入

范围：UNKNOWN fail-closed（Hold 读失败、设备状态查询失败 ⇒ 阻断恢复）、owner/fencing（旧 worker 未退出禁止新 worker 接管）、故障注入用例（暂停时响应丢失、动作超时、worker 崩溃、重复点击暂停、暂停后立即恢复、多实例恢复）。
对应工作项：P-12（HIL 收尾）、B-03、N-03 的相应实现范围。

---

## 6. 每批的交付物与门禁

| 批次 | 代码/迁移 | 测试 | 门禁 |
|---|---|---|---|
| 1 | 新增列 SQL（同步 `pg_rebuild/schema`，保证 `90_all_in_one` 与分片同源）；`RunControl` / `RuntimeHold` / `ExperimentApi` / 前端状态词表 | `LABOS_STATE_MACHINE_ACCEPTANCE_CONTRACT_20261107.md` 的 ST-01~ST-14 中批次 1 覆盖项 | 4.17/4.18 不回退；新增 4.19 段与 `MIN_SAFEBOUND`（见 G-02）；只升不降 |
| 2 | `run_command.execution_state` + 原子写 | 崩溃点不重复物理动作（P-07 四个崩溃点） | 4.13 崩溃恢复阈值不回退 |
| 3 | fail-closed 分支 + fencing | recon / 多实例恢复用例 | 新增段，独立阈值 |

**硬约束**：不改验收判据的 PR 不得同时改实现（`REMEDIATION_STATUS.md` S-04 行）。批次 1/2/3 各自独立 PR。

---

## 7. 验收口径（与 PR-S04-A 的边界）

| 断言 | A 阶段 | B 阶段 |
|---|---|---|
| 三层 legacy 最终 `paused` | ✅ 已验（13/0） | 必须保持 |
| Hold / 设备在途 / 节点边界 / 时间线事实 | ✅ 已验 | 必须保持 |
| 页面显示 `PAUSING` 时提示「设备可能仍在执行」 | ⬜ | 新增 |
| 仅在全部在途命令确认结束后才显示 `PAUSED` | ⬜ | 新增 |
| 存在 UNKNOWN 命令时不得自动宣告暂停完成 | ⬜ | 新增 |
| 暂停完成后恢复不重复执行已成功的设备动作 | ⬜ | 新增 |

---

## 8. 风险与回滚

| 风险 | 处置 |
|---|---|
| 一改状态语义就大面积红 | 分 3 个 PR；批次 1 先只加列与投影，不删旧字段；接口返回先加 `requestId` 再切文案 |
| `control_state` 与 `pause_kind` 语义重叠 | §3.1 落 ADR；词表单一权威来源（后端 `StatusVocabulary.java` / 前端 `utils/status.ts`） |
| 并发保护引入死锁 | 复用现有 `FOR UPDATE` / `pg_advisory_xact_lock` 模式（见 N-03、`QueryService.java` advisory lock）；不得新增第二套锁层级 |
| 回滚 | 每批 PR 带备份路径与回滚命令；新增列可空、默认值兼容旧读方 |

---

## 9. 未决问题（需在对应 PR 内定）

1. `control_state` vs `status` 中间态（§3.1）—— 先出 ADR。
2. 派发并发保护选 `execution_phase` 行锁还是 `FOR UPDATE`（§3.2）。
3. `PAUSING` 的 `pause_block_reason` 词表与页面文案。
4. 命令账本与 `run_command` 现有列（复用 `node_status`）的迁移路径（P-07/L-03）。

---

**裁决摘要**：PR-S04-B 不是「加一个 `PAUSING` 枚举」就结束，而是把「暂停请求」「设备执行状态」「实验运行状态」三个概念分开，并用同一事实口径驱动 API、UI、数据库与调度门禁。先落 ADR 与批次 1，再动节点/命令事务，最后做恢复与故障注入。
