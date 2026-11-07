# S-01：Workflow Run 与 Experiment Run 双模型映射调查

日期：2026-11-07  
状态：**源码调查结果（MAIN_BASELINE_AUDIT）**  
执行基线：`main` @ `3debf7858cc3479a1cfae941837081015a41c137`（ADR-PROCESS-001）  
目标分支：`review/labos-architecture-20261107`  
关联决策：ADR-STATE-001、ADR-PAUSE-001/002/003/007、ADR-FAULT-SCOPE-002、ADR-LAYER-001  
关联工作项：S-01（本文件）；S-02 / P-06（后续适配层）；S-03（事实与投影收敛）；S-04（验收口径分两 PR）  
状态真源：`REMEDIATION_STATUS.md`。本文不复制工作项状态。

---

## 0. 结论摘要

**LabOS 当前存在两套独立持久化的执行记录和暂停控制路径，不是同一 Run 的两种 UI 视图。**

- `run`：一次 Workflow 执行实例。由 `WorkflowService` 创建和执行，节点记录在 `run_node`，设备命令记录关联到节点；Workflow 级暂停由 `PauseManager` 管理。
- `experiment_run`：一次 Experiment 执行实例；分批模式下每个 batch attempt 也有独立的 `experiment_run`。由 `ExperimentScheduler` / `ExperimentExecutor` 创建和执行，步骤记录在 `experiment_run_step`；Experiment 级暂停由 `RunControl` 与 `RuntimeHold` 管理。
- `experiment`：定义及列表聚合状态，不是一次执行实例。它可以关联多个 `experiment_run`，且一个实验可编排多个 Workflow。
- `experiment_run_step` 是两套执行模型之间的桥，但目前 Workflow 执行完成后才回写 `run_id`；在 Workflow 正在执行期间，父子 Run 关联可能尚未持久化。

**核心风险：两套 Run 的状态、暂停类型、恢复策略和崩溃收尾语义不同；它们之间没有统一事务边界或统一协调入口。**

本轮没有修改代码、没有执行测试。以下结论以 main 指定 commit 的源码为准；schema 细节中未能从本轮直接取得 migration 原文的项目会明确标注证据等级。

## 1. 领域对象和关系图

```text
Experiment（定义 / 列表聚合）
  1
  |
  | 1:N
  v
ExperimentRun（一次实验执行；分批时通常每次 attempt 一条）
  1
  |
  | 1:N
  v
ExperimentRunStep（实验编排中的一个 Workflow 步骤）
  |                         \
  | workflow_id              \ run_id（执行结束后回写）
  v                           v
Workflow（流程定义）        Run（一次 Workflow 执行）
                              1
                              |
                              | 1:N
                              v
                            RunNode（业务节点 / 子步骤）
                              |
                              | 1:N（命令挂在步骤节点）
                              v
                            RunCommand

其他旁路记录：
- RuntimeHold：跨 ExperimentRun / Workcell 影响范围的现场阻断事实
- ManualWindow：Workflow Run 的人工处置窗口
- RunEvent / Alert / ErrorOccurrence：事件、告警和错误记录
```

### 1.1 关联基数（按当前代码可确认范围）

| 关系 | 当前口径 | 证据 / 限制 |
|---|---|---|
| Experiment → ExperimentRun | 1:N | `experiment_run.experiment_id`；同一 Experiment 可多次执行，分批还有多次 attempt |
| ExperimentRun → ExperimentRunStep | 1:N | 每执行一个展开后的 Workflow step，插入一条 step 记录 |
| ExperimentRunStep → Workflow | N:1 | `workflow_id`；一个 Workflow 可被多个实验/多个步骤引用 |
| ExperimentRunStep → Run | 0..1（当前代码） | `run_id` 可空，Workflow 执行返回后才更新 |
| Run → RunNode | 1:N | Workflow 执行时先建业务节点外壳，步骤命令挂到节点 |
| RunNode → RunCommand | 1:N（设计/查询路径） | 命令通过 `run_node_id` 关联；步骤节点删除时命令 FK 级联 |
| Experiment → Run | 0..1 旧兼容引用 | `experiment.run_id` 外键指向 `run.id`，不是 `experiment_run.id`；执行器明确不把 ExperimentRun ID 写入此列 |

**特别注意 ID 空间：** `run.id` 与 `experiment_run.id` 是两张独立表的自增 ID。两者即使数值相同也不是同一条记录。源码注释明确记录过把 `experiment_run.id` 写进 `experiment.run_id` 会触发外键错误（23503）。

### 1.2 跨模型桥接缺口

`ExperimentExecutor.runStep()` 当前顺序：

1. 先 INSERT `experiment_run_step`，写入 `experiment_run_id / seq / loop_index / workflow_id / status='running'`；
2. 调用 `workflows.withVars(runVars).execute(workflowId)`；
3. WorkflowService 创建/执行 Workflow Run；
4. Workflow 执行返回后，才将返回的 `e.runId()` 更新到该 step 的 `run_id`；
5. 同时更新 step 的 completed / paused / failed 状态。

因此在步骤执行期间，`experiment_run_step.run_id` 可能仍为 NULL。若执行器在 Workflow 创建后、返回前崩溃，父 ExperimentRun 与子 Workflow Run 之间可能缺少直接关联。当前恢复和反查逻辑部分依赖 `workflow_id + step.status` 等间接关系，不能把这个字段当作实时、完整的父子执行链路。

**建议：** 后续 S-02 / P-06 设计时，把“创建 Workflow Run 并绑定 ExperimentRunStep”作为同一执行上下文中的早期操作；至少要保证 Workflow Run ID 一旦创建就立即写入桥接记录，且创建失败/关联失败能被对账发现。不要等 Workflow 整体执行完成后才补关联。

## 2. 两套 Run 模型字段映射

### 2.1 Workflow Run：`run`

| 字段 / 事实 | 用途 | 当前状态维护者 |
|---|---|---|
| `id` | Workflow Run 主键 | WorkflowService |
| `workflow_id` | 所属 Workflow 定义 | WorkflowService |
| `status` | Workflow 执行状态（queued/running/paused/completed/failed/aborted 等） | WorkflowService / PauseManager / StartupRecovery / 其他收尾路径 |
| `pause_kind` | Workflow 级暂停原因码 | PauseManager、WorkflowService 暂停边界逻辑 |
| `pause_reason` | 暂停原因文本 | 同上 |
| `pause_node_key` | 暂停节点定位 | PauseManager |
| `input` | 执行变量、崩溃恢复标记、错误上下文等 JSON | WorkflowService / StartupRecovery |
| `attendance` | attended / unattended 执行模式（当前从 input/字段兼容读取） | WorkflowService |
| `actual_end` | Workflow Run 结束时间 | WorkflowService / StartupRecovery |
| `resumed_at` | 最近恢复时间 | PauseManager / WorkflowService |
| 节点事实 | `run_node` 中的业务节点和步骤节点状态 | GraphRunner / WorkflowService / Step sink |
| 命令事实 | `run_command`，关联到 `run_node` | PersistingStepSink / DriverStepExecutor |

证据：
- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java`
  - `startAsync()`：先创建 Workflow Run，再启动后台线程；
  - `execute()`：未传 presetRunId 时创建新 Run，预建 run_node 外壳；
  - `resumeAsync()`：同一 run_id 恢复，保留 completed 节点、删除未完成节点及其命令，再置 running。
- `PauseManager.java`：直接更新 `run.status`。
- `StartupRecovery.java`：扫描 Workflow Run 的 running / 长时间 queued / pending 并执行崩溃收尾。

### 2.2 Experiment Run：`experiment_run`

| 字段 / 事实 | 用途 | 当前状态维护者 |
|---|---|---|
| `id` | ExperimentRun 主键 | ExperimentScheduler / ExperimentExecutor |
| `experiment_id` | 所属 Experiment | 创建时写入 |
| `status` | queued/running/paused/completed/failed/aborted/cancelled 等 | ExperimentExecutor / RunControl / Scheduler / BatchOrchestrator / StartupRecovery |
| `variables` | 本次执行变量快照 | Scheduler / ExperimentExecutor |
| `expanded_steps` | 展开后的步骤数量 | ExperimentExecutor |
| `current_seq` | 当前执行序号 | ExperimentExecutor |
| `stop_requested` | 协作式停止请求 | RunControl；ExperimentExecutor 在步骤边界读取 |
| `pause_kind` | Experiment 级 Hold 类型码 | RunControl / ExperimentExecutor |
| `pause_reason` | 暂停原因文本 | RunControl / ExperimentExecutor |
| `started_at / paused_at / resumed_at / finished_at` | 生命周期时间 | ExperimentExecutor / RunControl / BatchOrchestrator |
| `error` | 错误或恢复说明 | ExperimentExecutor / StartupRecovery |
| 子步骤 | `experiment_run_step` | ExperimentExecutor |
| 批次归属 | `experiment_batch_run` 等批次关联 | BatchOrchestrator |

证据：
- `ExperimentScheduler.insertRun()`：创建 queued ExperimentRun。
- `ExperimentExecutor.createRun()/updateRun()/markRunning()/settle()`：执行生命周期。
- `RunControl.pause()/resume()/stop()`：实验级控制。
- `StartupRecovery.settleOrphanExperimentRuns()`：启动时处理遗留 running ExperimentRun。
- `REMEDIATION_STATUS.md` / ADR-LAYER-001 记录 `experiment_run.status` 当前为 `varchar(16)`；完整 schema migration 原文未在本轮源码读取中取得，实施 L-02 前仍应对目标 main commit 的 DDL 再做一次直接核验。

### 2.3 Experiment：`experiment`

`experiment.status` 是列表/业务对象层状态，不是独立执行事实。

当前同步方式有两种：

- **非分批路径**：`ExperimentExecutor.settle()` 在同一连接中更新 ExperimentRun，再按 `StatusVocabulary.runToExperimentCaseSql()` 回写 Experiment.status。
- **分批路径**：不允许某一个 batch run 直接覆盖整个 Experiment 状态；由 `BatchOrchestrator.onRunStatusChanged()` 按所有批次 attempt 聚合。
- **人工暂停路径**：`RunControl.markCoveredPaused()` 先把受影响 ExperimentRun 标 paused，再另开连接更新 Experiment.status；与 ExperimentRun 状态更新不是一个原子事务。

`experiment.run_id` 是旧兼容引用，外键指向 Workflow 的 `run.id`，不是 ExperimentRun 的 `id`。不能用它代替 Experiment → ExperimentRun 的执行关系。

## 3. 暂停类型词表差异

当前存在三套容易混淆的“暂停原因”词汇：

### 3.1 Workflow PauseManager.Kind

源码：`workflow/PauseManager.java`

| code | 含义 | 当前恢复提示 |
|---|---|---|
| `paused_fault` | 设备故障 | 代码注释定义为心跳恢复后自动继续 |
| `waiting_human` | 需要人工确认 | 必须人工处置 |
| `paused_unknown` | 板位未知 | 必须盘点/校正 |

`PauseManager.Kind.of(unknown)` 当前默认回退 `WAITING_HUMAN`。这会把未知 code 映射成人工等待，属于兼容兜底，不应被未来新模型当成严格验证。

### 3.2 Experiment Hold RuntimeHold.Kind

源码：`workflow/RuntimeHold.java`

| code | 含义 |
|---|---|
| `fault` | 设备故障 |
| `plate_unknown` | 板位未知 |
| `human` | 等待人工确认 |
| `manual` | 人工主动暂停 |

### 3.3 StatusVocabulary 对外词表

源码：`workflow/StatusVocabulary.java`

- Pause kinds：`paused_fault`、`waiting_human`、`paused_unknown`
- Hold kinds：`fault`、`plate_unknown`、`human`、`manual`
- Hold scopes：`site`、`island`、`experiment`

RuntimeHold.Kind.of() 将 Workflow code 映射为 Hold code：

| Workflow PauseManager code | RuntimeHold code |
|---|---|
| `paused_fault` | `fault` |
| `paused_unknown` | `plate_unknown` |
| `waiting_human` | `human` |

反向不是完整一一映射：`manual` 没有 PauseManager.Kind 的同名项；ExperimentRun.pause_kind 可能写入 RuntimeHold code，而 Workflow Run.pause_kind 写入 PauseManager code。因此不能把两列视为同一枚举，也不能直接复用同一个前端 code->label 字典而不保留 scope/type 信息。

### 3.4 需要定案的兼容策略

建议在 S-02 采用显式归一化类型，而不是继续用字符串互相塞值：

- `PauseReasonCategory`：FAULT / HUMAN_INTERVENTION / PLATE_LOCATION_UNKNOWN / USER_REQUEST；
- `PauseSource`：DEVICE / USER / QUALITY / SYSTEM / OPERATOR；
- `LegacyCodeMapping`：按原表和原字段分别映射，保留原始 code；
- 未知 code → UNKNOWN，不再静默回退为 HUMAN；
- API 展示同时返回 canonical category 与 legacy raw code，迁移期不破坏旧客户端。

以上是建议，不是已实施代码。

## 4. 两套暂停调用链

### 4.1 Workflow 级暂停链

典型链路：

```text
Workflow API / error handling
  -> PauseManager.pause(runId, Kind, reason, nodeKey)
  -> UPDATE run SET status='paused', pause_kind, pause_reason, pause_node_key
  -> releaseResources(runId)
  -> WorkflowService / GraphRunner pause boundary
  -> Workflow Run settles as paused
```

关键事实：

1. `PauseManager.pause()` 只在 `run.status='running'` 时更新；否则返回 0。
2. 更新 run 状态后调用 `releaseResources()`，释放“已预约但未使用”的位置/资源；豁免项不释放。
3. `releaseResources()` 目前分多个独立连接执行，部分 SQL 异常被吞并返回 0；这不是一个原子暂停事务。
4. `WorkflowService` 通过 `withPauseChecker` 在 GraphRunner 节点边界检查 run 是否 paused，以及是否有覆盖该 Workflow 的 RuntimeHold。
5. 节点边界暂停后调用 `pauseRunAtBoundary()`，再执行节点状态回写和 run 收尾，防止后续节点被误标 completed。
6. Workflow 级人工恢复由 `PauseManager.resume()` 把 run 置 queued；`WorkflowService.resumeAsync()` 重新启动同一个 run_id。

注意：PauseManager 的“释放已占未用资源”与 RuntimeHold 的“现场扣留不释放资源”是不同语义，不能合并成一个通用 releaseResources 操作。

### 4.2 Experiment 级暂停链

典型链路：

```text
POST /api/experiments/runs/{id}/pause
  -> RunApi / ApiServer pause handler
  -> RunControl.info(runId)
  -> RunControl.pause(run, scope, kind, reason, actor)
       -> RuntimeHold.raise(scope, scopeId, kind, reason, actor)
       -> RunControl.markCoveredPaused(scope, scopeId, ...)
            -> UPDATE experiment_run status='paused'
            -> separate connection UPDATE experiment.status='paused'
  -> ExperimentExecutor 在两个 Workflow step 之间调用 RuntimeHold.coveringFor(...)
  -> 命中 Hold 后 settle ExperimentRun as paused
```

关键事实：

1. `RunControl.pause()` 先持久化 RuntimeHold，再调用 `markCoveredPaused()`；两者不同连接/事务。
2. `markCoveredPaused()` 将 scope 覆盖范围内 queued/running 的 ExperimentRun 直接标 paused，属于即时展示/控制状态写入。
3. `ExperimentExecutor` 只在 Workflow step 边界检查 Hold；当前 Workflow 动作由 WorkflowService 自己执行到边界。
4. 实验级暂停最终会向下传播：`RuntimeHold.coveringForWorkflow()` 根据 `experiment_run_step.status='running'` 和 workflow_id 反查所属 Experiment，供 Workflow 节点边界停止。
5. 当前 Hold 查询异常会被降级成空列表/null（见既有审计），所以这条链还没有满足 UNKNOWN fail-closed。
6. `RunControl.resume()` 清除覆盖该 Experiment 的 Hold，再把 ExperimentRun 置 queued；随后将该 ExperimentRun 关联、且仍 paused 的旧 Workflow Run 标为 aborted，防止用户误点旧 Workflow Run 的继续而重复执行物理动作。

### 4.3 同一个暂停事件的多层写入顺序

当前不是一个原子协调事务，而是分散写入：

| 顺序 | 写入 | 表 | 当前连接/事务 |
|---|---|---|---|
| 1 | 创建 RuntimeHold | runtime_hold | RuntimeHold.raise 自己开连接 |
| 2 | 标记 ExperimentRun paused | experiment_run | RunControl.markCoveredPaused 自己开连接 |
| 3 | 标记 Experiment paused | experiment | 另开连接 |
| 4 | Workflow 在节点边界感知 Hold | run / run_node | WorkflowService 执行链 |
| 5 | ExperimentRunStep 记 paused 并关联 Workflow Run | experiment_run_step | runStep 返回后另开连接 |
| 6 | 后续恢复时清 Hold、ExperimentRun queued、旧 Workflow Run aborted | 多张表 | 多个连接/事务 |

这解释了为何系统可能出现“Hold 已创建，但某一层 status 尚未同步”的中间状态，也解释了为何状态字段不能单独作为事实真源。

## 5. 创建、关闭、恢复和重启行为

### 5.1 创建

| 场景 | Workflow Run `run` | ExperimentRun `experiment_run` |
|---|---|---|
| 直接运行 Workflow | `WorkflowService.startAsync/execute` 创建 Workflow Run | 不必创建 ExperimentRun |
| 运行 Experiment（同步） | 每个展开步骤调用 WorkflowService.execute，产生 Workflow Run | ExperimentExecutor 创建一条 ExperimentRun |
| 运行 Experiment（调度异步） | ExperimentExecutor 每个 Workflow step 创建 Workflow Run | ExperimentScheduler.submit 先建 queued ExperimentRun，执行时复用该 ID |
| 分批 Experiment | 每个 batch attempt 的每个 Workflow step 仍会产生 Workflow Run | 每个 batch attempt 独立 ExperimentRun |

Workflow Run 的 `startAsync()` 是先创建 run，再启动后台线程。Experiment Scheduler 的 `submit()` 先 INSERT ExperimentRun queued，再放入内存队列；重启后由 `rehydrateQueued()` 把库中 queued 记录重新入队。

### 5.2 正常关闭

- Workflow Run：WorkflowService 收尾节点状态、bypassed 状态、run 状态和事件；完成时写 `actual_end`。
- ExperimentRun：ExperimentExecutor.settle 写 status/error/pause fields；completed/failed/aborted/cancelled 写 `finished_at`，paused 只写 `paused_at`，不写 finished_at。
- Experiment：非批次按 Run→Experiment 映射回写；批次按所有 attempt 聚合。
- Workflow Run 与 ExperimentRun 的关闭不是同一个事务；ExperimentRunStep 的更新也在独立连接执行。

### 5.3 恢复

| 维度 | Workflow Run 恢复 | ExperimentRun 恢复 |
|---|---|---|
| 入口 | PauseManager.resume → WorkflowService.resumeAsync | RunControl.resume → ExperimentScheduler.resumeRun / ExperimentExecutor |
| 主记录 ID | 复用原 `run.id` | 复用原 `experiment_run.id` |
| 已完成节点/步骤 | 保留已完成 run_node；删除未完成节点和其命令，再从未完成节点继续 | 保留 completed experiment_run_step；只跳过 completed 的 seq:loop_index |
| 被暂停 Workflow Run | 本身继续 | Experiment Resume 会把该 ExperimentRunStep 对应的旧 paused Workflow Run 标为 aborted |
| 重新创建子 Run | 否，原 run_id 继续 | 是；暂停的 Workflow step 重跑时产生新的 Workflow Run |
| 物理副作用保护 | 保留已完成节点，清理未完成节点和命令后续跑；仍需设备幂等/状态证据 | 旧 paused 子 Run 作废，避免旧记录被单独继续；completed ExperimentRunStep 不重跑 |

**两种恢复语义不能混为一谈。** 未来 PauseCoordinator 应负责统一控制请求与 Hold/意图，但应通过不同的 Resume Adapter 执行各自正确的恢复策略；不能强行让两类 Run 都复用或都重建。

### 5.4 重启恢复

`StartupRecovery` 明确维护两套不同的孤儿收尾：

**Workflow Run：**
- 扫描 `run.status='running'`，以及创建超过 1 小时的 queued/pending；
- 标为 failed，写 `input.interrupted / interruptedAt / completedNodes / totalNodes / failedNodes / recoverable`；
- 释放 `device_state.current_run_id` 对应的设备锁；
- 有 completed 节点时可列为人工可恢复记录，默认不自动续跑；
- recoverable 列表只展示最近 7 天记录。

**ExperimentRun：**
- 单实例假设下，扫描 `experiment_run.status='running'`；
- 统一标为 failed，写 `finished_at` 和固定错误说明；
- batch run 交给 BatchOrchestrator 回写 attempt / batch / experiment 聚合状态；
- 非 batch run 调 ExperimentExecutor.syncExperimentStatus；
- queued 与 paused 不自动改判。

**重要限制：** StartupRecovery 注释明确说 ExperimentRun “启动时所有 running 都是上次进程遗留”只适用于当前单实例部署。未来多实例必须改为 owner/heartbeat/lease 识别，不能继续全量 settle running。

### 5.5 重启行为的未闭合问题

1. Workflow Run 的 recoverable 与 ExperimentRun 的失败收尾没有统一父子恢复协调。
2. Workflow Run 被 StartupRecovery 标 failed 时，关联的 ExperimentRunStep / ExperimentRun 是否同步变更，需要针对崩溃点验证。
3. ExperimentRun queued 会 rehydrate 入队；Workflow Run queued/pending 超过 1 小时才由 StartupRecovery settle，两个恢复扫描的时间边界不同。
4. 当前没有持久化 Worker owner/fencing token；单实例假设不支持安全扩展多实例。
5. ExperimentRunStep.run_id 延迟写入，使“Workflow 已启动但桥接尚未写入”的崩溃窗口难以可靠对账。

## 6. 状态写入职责现状

| 表 / 状态 | 已确认写入者 | 当前问题 |
|---|---|---|
| `run.status` | WorkflowService、PauseManager、StartupRecovery、恢复/异常收尾路径 | 多入口直接写；PauseManager 和 WorkflowService 恢复策略并存 |
| `experiment_run.status` | ExperimentScheduler、ExperimentExecutor、RunControl、BatchOrchestrator、StartupRecovery | 多入口直接写；Hold/Run 状态更新非统一事务 |
| `experiment.status` | ExperimentExecutor.syncExperimentStatus、RunControl.markCoveredPaused、BatchOrchestrator 聚合、其他业务写路径 | 不是纯投影；至少有多处直接 UPDATE |
| `experiment_run_step.status` | ExperimentExecutor.runStep | 运行中先建，结束后回写；桥接 run_id 延迟 |
| `run_node.status` | WorkflowService / GraphRunner / PersistingStepSink | 节点事实较细，但与 ExperimentRun 的映射需保持一致 |
| `run_command` | PersistingStepSink / DriverStepExecutor | 现有审计指出账本状态复用 node_status、命令落库时机仍需按目标 commit 核实；不能直接当作完整设备命令状态机 |

现状不是“所有表都由同一个状态机写”。ADR-STATE-001 所述 3debf78 的 M-01 是约定式补丁，增强了跨层暂停和防重复动作，但没有消除两套状态机。

## 7. 对 PauseCoordinator 的适配边界建议

PauseCoordinator 应统一**控制意图与事实入口**，不是把所有执行细节塞进一个巨型类，也不是把 Workflow Run 与 ExperimentRun 合并为一张表。

建议最小接口边界：

| 组件 | 责任 | 不负责 |
|---|---|---|
| PauseCoordinator | 鉴权、幂等请求、Pause/Resume/Stop 意图、创建/清除精确 Hold、事务/outbox、协调任务 | 不直接控制设备协议；不伪造物理停止 |
| WorkflowRunAdapter | 将统一控制请求映射到现有 `PauseManager` / Workflow Run；保留节点恢复语义 | 不修改 Experiment 级影响闭包 |
| ExperimentRunAdapter | 将控制请求映射到 `RunControl` / ExperimentRun；保留 M-01 旧子 Run 作废契约 | 不将 ExperimentRun ID 当 Workflow Run ID |
| HoldService | 维护多个独立 Hold、精确 hold_id、scope、source、clear evidence | 不通过 scope-wide clear 清理全部原因 |
| RunStatusProjector（后续 S-03） | 根据事实计算兼容状态与 UI 状态 | 不创建/清除 Hold，不更改命令/设备事实 |
| ReconciliationWorker（与 P-07 协作） | 处理事件投递失败、父子关联缺失、命令 UNKNOWN、投影不同步 | 不盲目重放物理命令 |

### 7.1 统一请求对象最低字段

- `request_id`（幂等键）
- `target_type` / `target_id`（WORKFLOW_RUN / EXPERIMENT_RUN）
- `action`（PAUSE / RESUME / STOP / ABORT）
- `source`、`reason_code`、`reason_text`
- `requested_by`、`requested_at`
- `expected_version`（防迟到命令覆盖）
- `result_state`（ACCEPTED / PROCESSING / COMPLETED / REJECTED / UNKNOWN）

这只是后续接口设计建议；当前仓库未发现 PauseCoordinator 实体/类。

## 8. 必须保留的 M-01 契约

以 `main @ 3debf785` 为基线，Experiment 级暂停必须保留：

1. `experiment_run.status` 最终 paused；
2. `experiment.status` 最终 paused；
3. 对应底层 Workflow `run.status` 最终 paused；
4. 后续未执行节点保持 pending；
5. Resume 时将本 ExperimentRun 对应的旧 paused Workflow Run 标 aborted，防止旧 run 被误点继续而重复物理动作。

这些是**最终兼容回归**，但未来 S-04 必须增加 Hold 有效、在途命令收敛和未收敛不得 PAUSED 的断言。不能只凭三层 status 判断物理动作已经安全收敛。

## 9. S-01 已确认 / 部分确认 / 未知清单

| 项目 | 结论 | 证据状态 |
|---|---|---|
| 两套主 Run 表与控制器 | `run/PauseManager`、`experiment_run/RunControl` | CONFIRMED |
| ExperimentRun 与 Workflow Run 的桥接表 | `experiment_run_step` | CONFIRMED |
| ExperimentRun → Workflow Run 关联写入时机 | Workflow execute 返回后才回写 | CONFIRMED |
| Workflow Run 恢复复用原 ID | 是 | CONFIRMED |
| ExperimentRun 恢复复用原 ID | 是；其 paused 子 Workflow Run 作废后重跑该 step | CONFIRMED |
| 分批 ExperimentRun 基数 | 每个 batch attempt 独立 ExperimentRun | CONFIRMED |
| 两套 pause_kind 词表相同 | 否，三类 workflow code vs 四类 hold code | CONFIRMED |
| `experiment.run_id` 指向 ExperimentRun | 否，指向 Workflow `run.id` | CONFIRMED |
| Workflow 与 Experiment 状态更新原子 | 否 | CONFIRMED |
| Pause Hold + ExperimentRun + Experiment 三者原子 | 否，独立连接/事务 | CONFIRMED |
| 两套 Run 是否都有 owner/fencing token | 当前检查路径未见 | NOT_FOUND（检查范围受限） |
| 所有写 status 的仓库全量调用点 | 本轮未完成全仓 AST/SQL 枚举 | PARTIAL |
| 所有表字段的精确 DDL、FK、索引、ON DELETE 规则 | 本轮未取得 migration 原文逐条核验 | UNKNOWN |
| Workflow 崩溃时父 ExperimentRunStep 是否总能找到子 Run | 关联延迟写入，存在风险；需故障注入确认 | UNKNOWN |
| Workflow Run 与 ExperimentRun 启动扫描的完整竞态 | 两套扫描存在且口径不同；未做运行时验证 | PARTIAL |

## 10. 下一步：S-02 / S-04-A 前置动作

不应直接开始写 PauseCoordinator。建议顺序：

1. **补齐 DDL 与写入点清单**：找到实际 migration 文件，核实 `run_status` enum、`experiment_run.status varchar(16)`、`pause_kind` 类型、外键、唯一约束、ON DELETE 规则。
2. **补齐所有 API 调用点**：定位 Workflow Run pause/resume 与 ExperimentRun pause/resume 路由，列出鉴权、参数、响应和审计行为。
3. **补齐状态写入点**：对 `run.status`、`experiment_run.status`、`experiment.status`、`experiment_run_step.status` 做全仓 SQL 写入枚举，区分生产写入、测试夹具、迁移脚本。
4. **补父子关联崩溃测试**：在 Workflow Run 创建后、`experiment_run_step.run_id` 回写前模拟进程退出，确认 StartupRecovery 能否识别并对账。
5. **补双层重启矩阵**：分别模拟 Workflow Run running、ExperimentRun running、两者同时 running、ExperimentRun queued、paused、父子状态不一致。
6. **再定义 PauseCoordinator 的最小适配接口**，避免把未知 schema/生命周期假设写进实现。
7. **按 REMEDIATION_STATUS.md 执行 S-04-A**：先增加验收判据，保留 M-01 原断言；不得与 API 语义切换同 PR。

---

## 11. 源码证据索引（main @ 3debf785）

以下路径均相对于仓库根目录；行号以该基线源码为锚，后续提交可能漂移，实施时优先按方法名复核。

| 结论 | 文件 | 方法 / 证据点 |
|---|---|---|
| Workflow Run 创建、后台执行 | `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java` | `startAsync`、`execute`、`createRun` |
| Workflow Run 恢复同 ID | 同上 | `resumeAsync` |
| Workflow Run 节点边界暂停 | 同上 | `withPauseChecker`、`pauseRunAtBoundary`、`settleRun` |
| Workflow Run Pause/Resume | `.../workflow/PauseManager.java` | `pause`、`resume`、`releaseResources` |
| ExperimentRun 创建/状态 | `.../workflow/ExperimentScheduler.java` | `submit`、`insertRun`、`executeOne`、`resumeRun`、`rehydrateQueued` |
| ExperimentRun 执行/步骤桥接 | `.../workflow/ExperimentExecutor.java` | `run`、`createRun`、`markRunning`、`runStep`、`settle`、`completedStepKeys` |
| Experiment Pause/Resume/Stop | `.../workflow/RunControl.java` | `info`、`pause`、`markCoveredPaused`、`resume`、`stop` |
| Hold 词表、scope、查询、落库 | `.../workflow/RuntimeHold.java` | `Kind`、`Scope`、`raise`、`clear`、`active`、`coveringForWorkflow` |
| 状态词表与映射 | `.../workflow/StatusVocabulary.java` | `PAUSE_KINDS`、`HOLD_KINDS`、`SCOPES`、`RUN_TO_EXPERIMENT` |
| 双模型崩溃收尾 | `.../workflow/StartupRecovery.java` | `settle`、`settleOrphanExperimentRuns` |
| API 路由 | `labos-kernel/src/main/java/com/labos/kernel/api/RunApi.java` | `/api/runs/{id}/pause|resume`、`/api/manual-windows` |
| Experiment API | `labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java` / 对应 controller | `/api/experiments/runs/{id}/pause|resume|stop`；需补齐 handler 精确定位 |

---

**最终判断：** S-01 的领域映射、两套暂停类型差异、创建/恢复/重启主要语义已完成源码级调查；DDL 逐字段核验、全仓状态写入点穷举、API 全部 handler 精确定位、父子关联崩溃注入仍未完成。当前结论足以明确 S-02 的适配边界，但不足以直接批准大规模状态机实现。
