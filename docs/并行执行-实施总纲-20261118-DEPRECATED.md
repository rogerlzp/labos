# 并行执行实施总纲

> ⚠ **已废弃（2026-11-19）**：内容已被《并行执行-设计文档-20261119.md》取代或仅为历史记录，仅作追溯，**不再作为实施依据**。

- 日期：2026-11-18
- 性质：实施总纲 + 交叉评审入口，**未实施**
- 定位：这是**审核入口**。六份详细设计作为附录，本文只回答"改什么、按什么顺序、怎么验"
- 前提：单实例部署；目标态"多实验 + 多批次并行"

---

## 0. 一页纸

| 项 | 内容 |
|---|---|
| 现状 | 批次串行、流程级互斥、准入散在四处、恢复无状态守卫 |
| 目标 | 资源驱动并行：设备/点位/机械臂不冲突即可并行 |
| 六模块 | 准入统一 / 暂停恢复 / 命令身份 / 事件归属 / 锁顺序 / 批次并行 |
| 第一阶段 | 只做准入统一，并行度保持 1，**HTTP 行为零变化**（唯一例外见下行） |
| 行为变化例外 | **门禁 DB 不可用从 fail-open 改 fail-closed，本身就是行为变化**，必须单独立项、单独验收，不得混入"零变化" |
| 最大风险 | 把"统一准入"当纯重构：只把 check 移进事务，却没指定数据库互斥点 → 竞态仍在 |
| 最紧急的一处 | `isCrashRecoverable` 放行 `quarantined` + `resumeToQueued` 加状态条件（**服务层 + SQL 一起改**，否则 SQL 守卫是死代码） |
| 并行度 2 | **不批准**，需前五项全部通过 |

---

## 1. 现状盘点（代码事实）

### 1.1 并行

| 层 | 实现 | 现状 |
|---|---|---|
| 调度器 | `ExperimentScheduler` | 多实验并行，资源级互斥，`MAX_CONCURRENT=4`，有队列/优先级/热加载 |
| 批次 | `BatchOrchestrator.runAll` | **串行**：同步 for 循环，跑完一批才下一批 |
| 门禁 | `WorkflowRunGate.check` | 按 `(tenant, workflow)` 互斥，挡住同流程多实例 |

**能并行**：不同实验、资源不冲突。
**不能并行**：同一实验多批、同一流程多实例。

### 1.2 暂停

| 机制 | 作用域 | 改 `run.status` |
|---|---|---|
| `run.status='paused'` + `pause_kind` | 单 run | 是 |
| `RuntimeHold` | SITE / ISLAND / EXPERIMENT | **否** |

生效点：只在**动作边界**（`GraphRunner` 每节点边界查 `isRunPaused` + `Hold.blocks()`）。

### 1.3 恢复

```
RunService → PauseCoordinator.resumeWorkflowRun（开事务）
  ├ claimTx 幂等
  ├ P-08 位置未确认只允许终止
  ├ PauseManager.resumeTx（执行状态变更）
  └ 提交后 resumeAsync
```

三处对的：幂等、强门禁、事务边界。
两个洞：`resumeToQueued` 无状态守卫、不过准入。

### 1.4 准入

| 入口 | 谁在判断 | 覆盖 |
|---|---|---|
| E-01 同步单流程 | `WorkflowWebService.run` 预检 | 只此一条 |
| E-02 异步单流程 | **无人** | — |
| E-03 同步跑实验 | `execute` 内预检 | 不覆盖批次 |
| E-05 异步建单批 | **无人** | — |
| E-08 批次重跑 | **无人** | — |
| E-09 resume | **无人** | — |

---

## 2. 七个模块与依赖

```
① 准入统一（RunAdmission）            ← 所有其他模块的地基
      ↓
② 暂停恢复（批次级 + 实验级）          ← 依赖准入做恢复
      ↓
③ 命令身份（logical_operation）        ← 提交之后的事
      ↓
④ 事件归属（waiter + command_id）      ← 与 ③ 同批
      ↓
⑤ 锁顺序护栏（LockOrderGuard）         ← 零行为变化，可独立做
      ↓
⑥ 批次并行（activeBatchAttempt 改造）  ← 最后一步

⑦ 物理态（程序态 / 设备态 / 物理态）    ← 横切 ②–⑥，不单独成阶段
```

**顺序不能倒**：⑥ 依赖 ①–⑤ 全部成立。

**⑦ 不是可选**：物理态未知时必须阻断派发，否则并行会把一个设备的未知扩散成多个 run 排队。详见《设计基线》§4.7。

---

## 3. 代码改造计划

### 3.0 阶段 0：恢复路径加固 + 五个决策（2–3 天）

**这一阶段不改任何业务逻辑：只做一处**恢复路径加固**（不在模块①准入内，属模块②的独立前置），并定五个决策。**

#### 3.0.1 立即修（P0，一处服务层 + 两处 SQL）

**先看清链路**：`resumeToQueued` 不是唯一拦截点，它前面还有服务层判定。

```
PauseManager.resumeTx（第 512–520 行）
  ├ pauseKindOfTx       WHERE status='paused'            quarantined → null
  ├ isCrashRecoverable  WHERE status='failed' AND ...    quarantined → false
  └ 两者都为假 → 提前 return「该执行不在暂停状态」
      ⇒ resumeToQueued 根本不会被调用
```

所以**只在 SQL 加 `quarantined` 是死代码**，必须同时改服务层。

| 文件 | 改动 | 验证 |
|---|---|---|
| `PauseMapper.xml` `isCrashRecoverable`（第 19–25 行） | 放行 `quarantined`（语义与 "failed+recoverable" 同类：隔离待确认） | quarantined 的 run 能进入恢复链 |
| `PauseMapper.xml` `resumeToQueued`（第 107–111 行） | 加 `AND status IN ('paused','failed','quarantined')` | 未暂停的 run 调 resume → 影响 0 行 |
| `PauseMapper.xml` `resumeToAborted`（第 113–118 行） | 同上 | 同上 |
| `PauseManager.resumeTx` 调用方 | 检查影响行数，0 行返回冲突 | 并发 resume → 一个成功一个冲突 |

**定性修正**：这不是"已完成的 run 能被重新跑"（`completed` 在服务层就被拦住），而是 **TOCTOU**——读 `paused` 与写 `queued` 之间状态被并发修改，或两个 resume 都读到 `paused`。修复目标是把"先读后写"变成"带状态条件的原子更新"。

**为什么先做**：不需要设计决策，独立于并行改造，且是后续所有恢复逻辑的地基。

#### 3.0.2 四个决策

| ID | 决策 | 建议 |
|---|---|---|
| A | DB 准入互斥点 | 行锁 `SELECT ... FOR UPDATE` + 唯一索引兜底。**必须同时定义全局锁序**：`workflow 行 → 实验行 → run 表`。批次路径已持实验行锁（`lockExperimentRow`），准入路径持 workflow 行锁，两条路径反序取锁会死锁 |
| B | 提交与派发衔接 | 事务内写 `status='queued'` **并写持久化待派发记录**，提交后唤醒。**不能只靠 `StartupRecovery` 兜底**——它对 `queued` 是超 1 小时才收，那不叫兜底，叫挂一小时；必须新增运行期僵尸扫描（阈值远小于 1 小时） |
| C | 事件表最终 DDL | 合并 `command_id` / `device_event_seq` / `device_boot_id` / `attribution_state`，形成唯一迁移规格 |
| D | active 语义 | **拆成四套规则**，不用单一"active 集合"覆盖：workflow admission / batch active attempt / resume eligibility / resource quarantine。`quarantined` 即使不占 workflow 名额，也可能因设备/位置不可信而必须阻止相关资源 |
| D2 | H-09：batch active 是否含 `paused` | 若未定，先只做行锁，索引推迟 |

#### 3.0.3 骨架

新增 `RunAdmission`（即之前叫 `GuardedStart` 的类，名字可换）：

```java
public final class RunAdmission {
    public record Request(long tenantId, long workflowId, Long excludeRunId,
                          boolean isResume, StartContext ctx) {}
    public record Result(boolean accepted, long runId, String errorCode, String message) {}

    public Result start(DbConfig db, Request req) throws SQLException {
        return Tx.inTransaction(db, c -> {
            // ① 行锁：锁 workflow 行（或 guard row）
            admissionLock(c, req.tenantId(), req.workflowId());
            // ② 三态判定（必须在事务内）
            Decision d = WorkflowRunGate.checkInTx(c, req.tenantId(), req.workflowId(), req.excludeRunId());
            if (!d.allowed()) { Tx.rollbackOnly(); return Result.rejected(d); }
            // ③ 写入：新建 run 或 UPDATE resume 状态（带状态守卫）
            long runId = req.isResume() ? resumeInTx(c, req) : createRunInTx(c, req);
            // ④ 写待派发标记（status='queued'），不在此处唤醒 worker
            return Result.accepted(runId);
        });
    }
}
```

**关键：唤醒在事务外。** 提交后由调用方唤醒 scheduler，避免"worker 读到未提交的 run"或"回滚后 worker 已启动"。

---

### 3.1 阶段 1：准入统一（W1–W5）

#### 改动清单

| 入口 | 文件 | 改法 | 行为变化 |
|---|---|---|---|
| E-01 | `WorkflowWebService.run` | 改调 `RunAdmission.start`，去掉自己的预检 | 无 |
| E-02 | `WorkflowService.startAsync` | 改调 `RunAdmission.start`；顺带修 `qs.contains("async=1")` 宽匹配 | **沿用 200 + 现有 JSON 形状**（`error`/`errorCode` 在首位），前端零改动；不得改 4xx |
| E-09 | `PauseCoordinator.resumeWorkflowRun` | 提交后改调 `RunAdmission.start(isResume=true)`，替代裸 `resumeAsync` | 无 |
| E-03 | `BatchOrchestrator.runAll` | 每批开跑前调 `RunAdmission.start` | 无 |
| E-08 | `BatchOrchestrator.runBatch` | 同上，`excludeRunId` 传该批上次的 run | 无 |
| E-05 | `createNextBatchAttempt` | 建 queued run 时过准入 | 无 |

**另有两条不经 `createRun` 的 run 插入路径，盘点时必须确认归属**：

| 路径 | 用途 | 处理 |
|---|---|---|
| `RunMapper.insertDemoRun`（xml:629） | `PersistenceDemo:142` 演示用 | 若只在演示 profile 生效则明确排除；生产可达则必须过准入 |
| `RunMapper.insertExperimentFlowRun`（xml:1012） | `ExperimentFlowRunner:280` | 生产路径 ⇒ **必须过准入** |

#### 验证（并行度 1，行为等价）

| ID | 项 | 方法 |
|---|---|---|
| V-01 | 同步单流程响应字段 | 对比 JSON：`experimentRunId/status/totalSteps/doneSteps/error` |
| V-02 | 异步单流程 | runId + status |
| V-03 | 4 批串行顺序 | 按 `batch_no` 升序 |
| V-04 | 批 1 失败批 2 仍启动 | 现有 D3 语义 |
| V-05 | 扣留停在批边界 | 后续批 paused 不启动 |
| V-06 | resume 幂等 | 同 run 重复 resume 返回同一结果 |
| V-07 | 既有回归集 | `BatchDbSelfTest` / `WorkflowRunGateDbSelfTest` 全绿 |
| V-08 | 错误码兼容 | `EC-WORKFLOW-RUNNING` 语义不变 |

#### 回滚

每接入一个入口就是一次独立提交，出问题单独回退该入口。

---

### 3.2 阶段 2：暂停恢复（W6）

#### 改动清单

| 文件 | 改动 |
|---|---|
| `RuntimeHold.Scope` | 加 `BATCH("batch")` |
| 新增 `BatchPauseCoordinator` | 实验级暂停的逐批协调：写 Hold → 等各批确认 → 改批次状态 → 改实验状态 |
| `PauseCoordinator` | 新增 `resumeBatch(experimentId, batchNo)` |
| `PauseManager.resumeTx` | 加 tenant + workflow 归属校验 |
| `StartupRecovery` | 加僵尸 `queued` 扫描（超 N 分钟无 worker → `paused_fault`） |
| `ExperimentScheduler` | 批次恢复时检查"同实验是否有 `paused_unknown` 批" |

#### 核心语义

```
实验暂停 = 请求，不是命令
  写 experiment Hold
  → 各 run 在节点边界自行转 paused
  → 所有 run 确认后，批次标 paused
  → 所有批次确认后，实验标 paused

实验恢复 = 逐批
  检查所有批已 paused
  → 解除 experiment Hold
  → 按 batch_no 顺序，逐批过准入
  → 每批等前一批准入通过后才做下一批
```

#### 验证

见《暂停与恢复设计》§6 的 PR-01~PR-12。

#### 3.2.1 物理态落地（横切阶段 2 与 3）

| 项 | 落点 | 说明 |
|---|---|---|
| 三层状态分开 | `device_state` 增加 `physical_state` 列（`in_motion / idle / unknown`） | 不与 `current_run_id` 混用；未知时该设备不进可派发池 |
| 可靠性分级 | 复用 ③ 的 `result_trust` | `INFERRED`（P3）只展示，不推进流程 |
| 未知阻断派发 | `ExperimentScheduler.tryHoldResources` 之前增加物理态检查 | 物理态未知 → 不派发，不"先试试" |
| 恢复三项核对 | `PauseManager.resumeTx` 增加"设备是否真停" + "机械臂分区是否物理空出" | 缺一项即停在 `paused_unknown` |

详见《设计基线》§4.7。

---

### 3.3 阶段 3：命令与事件身份（W7–W8）

#### 表（先建表，不动现有）

```sql
CREATE TABLE logical_operation (...);        -- 见《表设计》§1.3
CREATE TABLE device_event_waiter (...);      -- 见《表设计》§2.2

ALTER TABLE device_event ADD COLUMN command_id VARCHAR(160);
ALTER TABLE device_event ADD COLUMN device_event_seq BIGINT;
ALTER TABLE device_event ADD COLUMN device_boot_id VARCHAR(64);
ALTER TABLE device_event ADD COLUMN attribution_state VARCHAR(16) NOT NULL DEFAULT 'PENDING';
```

#### 代码

| 文件 | 改动 |
|---|---|
| `DriverStepExecutor.buildCommand` | `commandId` **按设备能力分档**：支持幂等去重的设备用稳定 id（去掉 `exec`）；不支持去重的设备保留 `exec`，否则 resume 后重发会被设备忽略而卡死 |
| `DriverStepExecutor.executeCommand` | 先建 `logical_operation`（PENDING），再下发给 `CommandLedger` |
| `CommandLedger.begin` | 记录 `(operation_id, attempt_no)`，不再继承 `run_node.attempt` |
| `GraphRunner` 的 `__awaitEvent` | 先建 waiter（继承 run 级 `base_event_id`），再等 |
| `DeviceEventMapper.claimEvent` | **先判唯一、再加锁更新**；匹配域按 `(tenant, device_code, event_type)` 建互斥点；候选集加 `process_id` / `interaction_step` 精匹配 |
| `DeviceEventController` | 写 `attribution_state`；`run_id` **保留并由侧车补填 + 认领过滤**，不标 deprecated（它是对外契约字段） |

#### 验证

见《表设计》§5 的 L-01~L-12 / W-01~W-09 / C-12~C-13。

---

### 3.4 阶段 4：锁顺序护栏（可与阶段 1 并行）

#### 改动清单

| 文件 | 改动 |
|---|---|
| 新增 `LockOrderGuard` | 记录 `(type, id, token, thread)`；按 `(type.rank, id)` 全局全序约束 |
| `DeviceLock` / `PositionLock` / `RailZoneGuard` | 获取前调 `LockOrderGuard.check(type, id)`；**锁类型由实现类声明**（`DeviceLock`→`DEVICE_STATE`、`PositionLock`→`POSITION_STATE`），**不由 `resource_node.kind` 推导**——同一节点可能既参与执行互斥又参与位置占用，kind 表达不了调用者申请的是哪种语义 |
| 测试 | 反向申请、同类型逆序、异常退出、重入、批量释放、**跨 step 持有、跨线程释放** |

**零行为变化**（合法路径不受影响），可最早落地。

#### 验证

见《跨类锁核查》§8 的 K-01~K-07。

---

### 3.5 阶段 5：批次并行（最后）

#### 改动清单

| 文件 | 改动 |
|---|---|
| `BatchOrchestrator.activeBatchAttempt` | 粒度从"同实验"降到"同批次" |
| `BatchOrchestrator.lockExperimentRow` | 保留（用于批次内串行化），但不再阻止不同批 |
| `ExperimentScheduler.continueNextBatch` | 支持并行续队：一个槽位释放即补下一批 |
| 唯一索引 | `experiment_batch_run (tenant_id, batch_id, active_slot)` |
| 批次级失败隔离 | 一批失败不 `break`，其他批继续 |

#### 前置

阶段 1–4 全部通过。

---

## 4. 验收矩阵（汇总）

| 阶段 | 验收 ID | 组数 |
|---|---|---|
| 阶段 1 准入统一 | V-01~V-11 | 11 |
| 阶段 2 暂停恢复 | PR-01~PR-12（原 `R-*`） | 12 |
| 阶段 3 命令身份 | L-01~L-14 | 14 |
| 阶段 3 事件归属 | W-01~W-13 | 13 |
| 阶段 3 命令幂等 + 物理态 | C-01~C-17 | 17 |
| 阶段 4 锁顺序 | K-01~K-11 | 11 |
| 阶段 5 并行场景 | P-01~P-34（24 项） | 24 |
| **合计** | | **102** |

**三条口径说明**：

1. 暂停恢复的验收编号原为 `R-01~R-15`，与《跨类锁核查》的风险编号 `R-17~R-20` 同前缀不同义，**统一改为 `PR-*`**，避免评审时混读。
2. `C-12`/`C-13` 在《设计基线》和《表设计》里被重复使用，合并后 15 个唯一项，**下一版必须重排编号**。
3. `P-*` 属阶段 5，**阶段 1 不可执行**，不得计入阶段 1 的完成度。

每个 ID 的证据必须能追溯到：commit SHA、环境、并发线程数、事件顺序、命令 ID、run/batch/物料 ID、资源申请释放日志、状态时间线、可复现脚本。

---

## 5. 待决策汇总

| ID | 问题 | 影响 | 建议 |
|---|---|---|---|
| A | DB 准入互斥点与全局锁序 | 决定 `RunAdmission` 代码形态 | 行锁 + 索引；锁序 `workflow 行 → 实验行 → run 表` |
| B | 提交与派发衔接 | 决定是否新增待派发记录 | 事务内写 queued + 待派发记录；新增僵尸扫描，不能只靠启动恢复 |
| C | 事件表 DDL | 决定 ③ 能否落地 | 合并成一份唯一迁移规格 |
| D | active 语义拆分 | 决定四类规则的实现 | 拆成 workflow admission / batch attempt / resume eligibility / resource quarantine |
| H-09 | batch active 是否含 paused | 决定唯一索引 DDL | 先不做索引，绕开 |
| H-12 | 实验暂停时 run 是否也标 paused | 决定 UI 语义 | 是 |
| H-13 | 暂停期间位置锁是否保留 | 决定恢复前是否强制校验 | TTL 到期释放 + 恢复前校验 |
| H-14 | 恢复顺序 | 决定恢复耗时 | 严格按 batch_no |
| H-15 | `paused_unknown` 是否阻塞同实验其他批 | 决定安全边界 | 阻塞 |

---

## 6. 风险

| ID | 风险 | 说明 |
|---|---|---|
| R-28 | 把"统一准入"当纯重构 | ④ 只把 check 移进事务，没指定 DB 互斥点 → 竞态仍在 |
| R-29 | 同步入口改异步会破契约 | `totalSteps/doneSteps/batches` 会变空，明确不做 |
| R-30 | 先加唯一索引会打掉 paused 重跑 | 步骤 6/7 已对调 |
| R-31 | 逐批恢复让恢复时间变长 | 4 批 × 检查耗时，可能几十秒 |
| R-32 | Hold 与 run.status 双写不一致 | 必须定义权威（建议 run.status） |
| R-33 | 位置锁 TTL 到期但板还在设备里 | 恢复前强制校验，不能只看状态 |

---

## 7. 审核指引

### 7.1 请按这个顺序读

| 顺序 | 文档 | 审什么 |
|---|---|---|
| 1 | 本文 | 阶段划分是否合理、代码计划是否可执行 |
| 2 | 《源码核查清单》 | 现状判定（已有/部分/没有）是否准确 |
| 3 | 《设计基线》 | 并行模型、三层时间、**§4.7 物理态**、风险是否成立 |
| 4 | 《表设计》 | 命令身份与事件归属能否覆盖全部重放场景 |
| 5 | 《入口统一》 | 准入收口与行为等价 |
| 6 | 《跨类锁核查》 | 锁顺序护栏是否够 |
| 7 | 《暂停与恢复设计》 | 多并行下的暂停恢复语义 |

### 7.2 请重点回答

1. §3.0 的五个决策（A/B/C/D/D2）建议是否成立？尤其全局锁序 `workflow 行 → 实验行 → run 表` 有没有反例？
2. §3.1 的"每批开跑前准入"会不会因为批间终结延迟导致误拒？
3. §3.2 的"实验暂停 = 请求，不是命令"这个语义是否可行？
4. §3.3 的 `commandId` 按设备能力分档后，不支持去重的设备保留 `exec`，会不会在 resume 时让同一物理动作拿到新 id 而被重复执行？
5. §3.3 的 waiter 匹配"先判唯一、再加锁"是否仍有并发窗口？歧义事件的 `UNATTRIBUTED` 记录能否独立于归属事务持久化（否则回滚即丢失）？
6. §3.4 的锁护栏能否在阶段 1 期间并行落地？锁类型由实现类声明，能否覆盖批量申请 / 批量释放 / 跨 step 持有？
7. §4 的 102 组验收是否遗漏"两实验共用设备 + 共用点位"的组合？阶段 1 是否应只计 V-*？
8. 把门禁的 DB 不可用从 fail-open 改为 fail-closed，**本身就是行为变化**——本总纲是否必须在 §0 明确承认，而不是笼统写"HTTP 行为零变化"？
9. 《设计基线》§4.7 的三层状态（程序 / 设备 / 物理）划分是否够？现场的物理事实还有哪些来源没纳入（称重、在位传感、视觉、人工点检记录）？
10. §4.7.4 的"恢复三项核对"是否漏了耗材（针是否堵、液面是否够）？漏了会不会出现"位置对、设备停了、针却堵着"继续跑？

### 7.3 明确不在本轮范围

- 多实例 fencing（单实例部署）
- 同步入口改异步
- 设备协议改造的具体实施
- 跨实验室调度

### 7.4 边界声明

**代码 0 行、表 0 张、测试 0 次。** 本文是实施计划，不是完成记录。任何"设计通过"都不等于"可并行"。

---

## 8. 附录：六份详细文档

| 编号 | 文档 | 行数 |
|---|---|---|
| ① | 并行调度-源码核查清单-20261118.md | 265 |
| ② | 并行执行与事件归属-设计基线-20261118.md | 640 |
| ③ | 并行执行-逻辑操作与等待者登记-表设计-20261118.md | 698 |
| ④ | 并行执行-执行入口统一改造清单-20261118.md | 324 |
| ⑤ | 并行执行-跨类锁顺序核查-20261118.md | 342 |
| ⑥ | 并行执行-暂停与恢复设计-20261118.md | 372 |
| 本文 | 并行执行-实施总纲-20261118.md | 422 |
