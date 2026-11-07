# S-04-A：4.17 暂停事实证据升级与 e2e 实施规格

日期：2026-11-07  
状态：**测试实施规格（PROPOSED FOR PR-S04-A）**  
执行基线：`main` @ `3debf7858cc3479a1cfae941837081015a41c137`  
目标分支：`review/labos-architecture-20261107`  
关联决策：ADR-STATE-001、ADR-PAUSE-001/002/006/007、ADR-PROCESS-002  
关联工作项：S-01、S-04（第一阶段）、M-01、G-03、P-03、P-07、L-02/L-03  
状态真源：`REMEDIATION_STATUS.md`。本文定义 PR-S04-A 测试范围，不维护工作项状态。

---

## 0. 先给结论

当前 4.17 已经验证了一个重要行为：**Experiment 级暂停请求发出后，当前不可中断设备动作可以继续到完成；Workflow Run 在下一个节点边界暂停，后续节点保持 pending。**

但当前 4.17 的三层 status 不是物理暂停完成的充分证据：

- `RunControl.pause()` 立即创建 RuntimeHold，并把受影响的 `experiment_run` 标 paused；
- 随后 `RunControl.markCoveredPaused()` 另开连接把 `experiment.status` 标 paused；
- `ExperimentApi.handle()` 当前 HTTP 200 直接返回 `status="paused"`；
- 当前设备动作仍可能处于 `device_state.status='running'`；
- 当前动作完成后，Workflow Run 才在下一节点边界落 paused；
- `run_command` 是步骤结束后才写入，不能证明设备命令仍在执行。

因此，本规格修正 S-04-A 的边界：

**PR-S04-A 只增加可观测事实证据和兼容回归，不切换 API/UI 的 PAUSED 语义。** 在 PR-S04-A 中，允许观察到 legacy `status=paused` 与设备动作仍运行并存；必须把它记录为“旧状态写入已发生，但物理动作尚未收敛”，不能解释为安全暂停完成。

「未收敛不得显示 PAUSED」必须放在 PR-S04-B，与 API/UI 语义切换一起验收。否则会同时要求“保持旧 API 行为”和“改变旧 API 展示语义”，两者矛盾。

## 1. 当前 4.17 源码与测试行为

### 1.1 现有测试步骤

文件：`scripts/e2e-pause-during-experiment.py`

当前脚本核心过程：

1. 建立本次测试专用 Experiment、Workflow、ExperimentRun。
2. 启动 Experiment，获取本次 Workflow Run ID。
3. 通过 `device_state.status='running'`、`current_method`、`current_run_id` 确认设备方法确实在执行。
4. 调用 `POST /api/experiments/runs/{experimentRunId}/pause?scope=experiment&kind=manual`。
5. 轮询直到 Workflow Run 到达终态/paused。
6. 断言 ExperimentRun、Experiment、Workflow Run 最终 paused。
7. 断言后续设备节点 pending。
8. 输出命令状态和 Hold 摘要。

当前脚本已明确知道：`run_command` 是步骤完成后才 INSERT 的，不能用它判断“设备正在执行”。这是正确的观测口径，应保留。

### 1.2 当前缺失的断言

| 事实 | 当前 4.17 | PR-S04-A |
|---|---|---|
| 设备方法请求前确实在执行 | 已验证 | 保留 |
| Pause API 返回 | 打印，不严格断言响应语义 | 断言 legacy response 仍为 paused，确保本 PR 不改 API |
| 本次 Pause 创建的 Hold | 只打印全局最新 Hold 摘要 | 断言本次测试专属 Hold ID / scope / scope_id / kind / held_at / released_at |
| ExperimentRun / Experiment status 立即变化 | 最终状态断言 | 额外记录请求接受后的 legacy 状态 |
| Pause 后设备是否仍在执行 | 未形成明确的请求后即时断言 | 明确记录“请求后设备仍 running” |
| 当前动作最终收敛 | 通过最终 command status 打印 | 断言设备离开本次 run 的 running 状态，并有步骤完成证据 |
| Workflow Run 节点边界暂停 | 最终 status 断言 | 保留并记录暂停时间 |
| 后续节点 pending | 已验证 | 保留，并补充无后续设备命令证据 |
| UNKNOWN / ACK 丢失 | 未验证 | 暂不纳入；归 P-03 / P-07 |
| 未收敛时 UI 不显示 PAUSED | 当前实现不支持 | 仅 PR-S04-B 验收 |

## 2. PR-S04-A 的测试场景

### ST-417-01：设备动作中发出 Experiment Pause

**测试目的：** 证明暂停请求、legacy 状态写入和物理动作收敛是三个不同事实。

前置条件：

- 使用独立测试租户/测试数据库或确保本次测试对象完全隔离。
- 当前 Experiment 无任何既有 active Hold；否则测试应先失败退出，不得把历史 Hold 当成本次证据。
- Workflow 至少包含两个真实设备任务节点；第一个设备动作必须有足够长的执行时间，确保发暂停请求时设备仍在执行。
- 测试只验证当前默认不可中断动作的安全边界语义，不把它当成设备物理 Abort 测试。

步骤：

1. 创建本次测试专用 `experiment_id`、`experiment_run_id`、`workflow_id`。
2. 启动执行，获取准确的 Workflow `run_id`。
3. 轮询 `device_state`，直到同时满足：
   - `status='running'`；
   - `current_method` 非空；
   - `current_run_id` 等于本次 Workflow Run ID。
4. 记录 `t_device_running`。
5. 调用 Experiment Pause API，记录请求前后响应和时间 `t_pause_request`。
6. 立即读取 ExperimentRun、Experiment、RuntimeHold、device_state。
7. 等待当前设备方法自然结束。
8. 轮询 Workflow Run，直到节点边界状态为 paused。
9. 读取 Workflow Run 节点、run_command、设备状态和本次 Hold。

必须断言：

- Pause API HTTP 成功，且响应保持当前 legacy `status=paused`。
- ExperimentRun / Experiment 的 legacy 状态写入仍然符合当前行为。
- 本次请求确有一条 active RuntimeHold，scope=experiment，scope_id=本次 experiment_id，kind=manual，released_at IS NULL。
- Pause 请求后设备可能仍为 running；这是当前非可中断动作的预期兼容行为，不可将此时刻标记为暂停完成。
- 当前设备动作最终结束，设备状态不再是本次 run 的 running/current_method。
- Workflow Run 最终 paused。
- 后续设备任务节点仍 pending。
- 后续节点没有对应的 run_command；不能仅凭 pending 节点状态推断没有发过命令，需结合本次 run 的命令关联查询。
- 已完成的当前动作有其步骤完成证据；`run_command` 只能作为动作完成后的证据，不能作为在途证据。
- 记录三个时间戳：`t_pause_request < t_device_action_settled <= t_workflow_paused`。如果执行器的观测粒度导致后二者相同，允许相等；不得倒序。

明确不作的断言：

- 不断言 API/UI 在设备动作运行时不显示 PAUSED；当前 legacy API 明确会返回 paused。
- 不断言当前设备动作被物理中断；默认方法是等待安全边界。
- 不断言 Hold 查询异常时会 fail-closed；当前 RuntimeHold 查询错误路径尚不满足该要求。
- 不断言 ACK 丢失后命令一定可对账；命令账本能力归 P-07/L-03。

### ST-417-02：Pause Hold 的唯一归属和生命周期

**测试目的：** 确保暂停状态可以追溯到本次请求，而不是依赖“最新一条 Hold”。

必须断言：

- 本次测试能通过 scope + scope_id + kind + held_at 确认 Hold 属于本次请求；
- 读取并记录 Hold 主键 ID；
- Hold 在 ExperimentRun paused 期间仍 active；
- 未调用 Resume / 人工解除前，Hold 不应被测试清理或后台逻辑误释放；
- 结束清理必须按本次 Hold ID 精确处理，不能按 scope-wide clear；
- 若当前 API 只支持按 scope 清除，测试清理应在隔离测试库中执行，不能把 scope-wide clear 当成生产推荐语义。

### ST-417-03：后续节点没有被派发

**测试目的：** 除节点 pending 外，证明后续设备任务没有实际产生物理命令。

必须断言：

- Workflow Run 的目标后续节点仍 pending；
- 该业务节点没有对应 level=1 step node；
- 该业务节点没有关联的 run_command；
- 当前执行设备的 `current_run_id/current_method` 已清除或转为设备定义的非执行态；
- 不能用全局 `max(run_command.id)` 或全局最新 Hold 作为证据，必须限定本次 run / node / Hold ID。

## 3. 时间线与数据采集格式

每次测试输出一条结构化摘要（JSON 或等价日志），至少包括：

| 字段 | 含义 |
|---|---|
| `experimentId` | 本次 Experiment ID |
| `experimentRunId` | 本次 ExperimentRun ID |
| `workflowId` | Workflow 定义 ID |
| `workflowRunId` | 本次 Workflow Run ID |
| `holdId` | 本次 Pause 创建的 RuntimeHold ID |
| `pauseRequestedAt` | Pause API 请求开始时间 |
| `pauseAcceptedAt` | API 返回成功时间 |
| `deviceRunningObservedAt` | 确认设备仍在执行的时间 |
| `deviceSettledAt` | 设备不再执行本次方法的时间 |
| `workflowPausedAt` | Workflow Run 到达 paused 的时间 |
| `experimentRunStatusAtAccept` | 请求接受后读到的 legacy status |
| `experimentStatusAtAccept` | 请求接受后读到的 legacy status |
| `workflowRunStatusFinal` | 最终 Workflow Run 状态 |
| `pendingNodeKeys` | 最终仍 pending 的节点 key |
| `completedCommandIds` | 当前动作完成后落库的命令 ID |
| `activeHoldAtEnd` | 结束时本次 Hold 是否仍 active |

时间线要能明确显示：**legacy status 已变 paused 时，设备动作可能还未结束。** 这是本轮审计要揭示的事实，不应隐藏。

## 4. PR-S04-A 的代码变更边界

允许：

- 修改 `scripts/e2e-pause-during-experiment.py`，增加 Hold 查询、设备状态快照、时间戳和节点/命令关联断言；
- 更新 `docs/测试用例总表.md` 和对应英文文档；
- 必要时增加只读测试 helper，不能改生产 Pause API 的 status 语义；
- 增加纯测试层的时间线输出和测试数据隔离检查。

禁止：

- 不修改 `RunControl.pause()` 的即时 legacy 状态写入语义；
- 不把 Experiment API 的 `status=paused` 改成 `PAUSE_REQUESTED`；
- 不将 UI 的 status 展示提前切成新状态机语义；
- 不引入一个只有测试使用、生产没有的假 Hold / 假命令账本；
- 不降低 `MIN_PAUSEEXP`、`MIN_INTR` 或其他门禁阈值；
- 不 SKIP 原 4.17 或把原断言移到非门禁脚本。

## 5. 当前不能纳入 PR-S04-A 的断言及其归属

| 目标断言 | 当前缺口 | 正确工作项 |
|---|---|---|
| Hold 查询异常时 UNKNOWN fail-closed | `RuntimeHold.active()` 当前 catch 后返回空列表 | P-03 |
| 设备 ACK 丢失后命令状态 UNKNOWN / RECONCILING | 当前命令账本不具备完整的独立执行状态 | P-07 / L-03 |
| UNKNOWN 时不得 Resume / 重新分配资源 | 需要 Hold 三态、命令 reconciliation 和资源隔离事实 | P-03 / P-07 / P-08 |
| 旧 Worker 不退出时禁止新 Worker 接管 | owner/fencing token 能力未证明 | B-03 / N-03 / P-07 的相应实现范围 |
| 设备动作未收敛时 API/UI 不显示 PAUSED | 当前 ExperimentApi 返回 paused，实验两层 status 立即写 paused | PR-S04-B |
| 设备物理 Abort 成功 | 需要设备型号/固件/方法/负载的 HIL 证据 | 4.18 / P-12，不属于 4.17 |

不能因为 S-04-A 想增加覆盖率，就把这些未实现能力写成预期通过的测试；否则只能得到一个长期红灯或被迫 SKIP 的测试门禁。

## 6. PR-S04-B 的切换验收承接

PR-S04-B 在 P-03、P-07/L-03、L-02、S-02/P-06 具备前置能力后，新增独立场景：

1. Pause API 只返回请求已接受和 request ID；
2. 设备动作仍执行时，RunControl/UI 显示 PAUSE_REQUESTED / PAUSING，不得显示 PAUSED；
3. Workflow 节点到达安全边界、命令已收敛、Hold 与资源事实满足条件后，才投影 PAUSED；
4. Hold 查询失败、设备状态查询失败、命令 ACK 丢失、旧 Worker 状态 UNKNOWN 时，投影 RECONCILIATION_REQUIRED 或等价阻断态；
5. Resume 在 UNKNOWN 或 Hold 未清时拒绝；
6. 最终三层兼容状态仍对齐 paused，M-01 旧 paused Workflow Run 作废行为保留。

PR-S04-B 不能只改 API 响应文字；必须让 UI、数据库、调度门禁和 Resume 前置条件使用同一事实口径。

## 7. 验收与证据要求

- 原 4.17 既有断言全部通过；
- 新增 Hold / device_state / 时间线 / pending-node / command 关联断言全部通过；
- 4.18 可中断方法真实停止测试保持原样，不与 4.17 混为一个场景；
- `ci-gate.sh --all` 无 SKIP；
- 记录 CI run ID、4.17 实际断言数、测试日志和本次测试 ID；
- 本 PR 不声称 UNKNOWN fail-closed、物理 Abort 或新 PAUSED 投影已经完成。

---

**裁决摘要：** PR-S04-A 是“legacy 三层状态 + 可核验 Hold / 设备 / 节点事实”的兼容验证；PR-S04-B 才是“请求接受与 PAUSED 完成分离”的 API/UI 语义切换。任何把「未收敛不得显示 PAUSED」塞进保持旧 API 行为的 PR-S04-A 的做法，均属于验收契约自相矛盾。


## 8. 当前分支实施状态（2026-11-07）

已在 `review/labos-architecture-20261107` 修改：

- `scripts/e2e-pause-during-experiment.py`：增加本次 Experiment Hold 精确查询、设备 `current_run_id` 与 Workflow Run 精确归属、legacy API/status 兼容断言、Hold 生命周期、ARM/ARM2 节点与命令关联、`S04A_TIMELINE_JSON`。
- 清理路径不再删除全局 active Hold；只释放本次 Hold ID。只有本次 Workflow Run 已进入终态且设备不再运行/持有该 Run，才允许清理；否则保留运行记录与 Hold，并使测试失败。
- `scripts/ci-gate.sh`：只更新 4.17 的说明文字，`MIN_PAUSEEXP=5` 未改变。
- `docs/测试用例总表.md`：同步 S-04-A 的事实证据与 legacy 状态边界。

**尚未验证：** 本轮没有可访问的项目运行环境，尚未执行 Python 编译检查、4.17 e2e、`ci-gate.sh --all` 或真实设备测试。GitHub 当前分支也没有对应 PR，且最新提交没有可见 CI status。因此这些修改只能标记为“已提交到评审分支、待运行验证”，不能标记 S-04-A 完成或 M-01 VERIFIED。

**下一步验收顺序：** 先在具备 LabOS API、PostgreSQL 和模拟器的环境执行 `python3 -m py_compile scripts/e2e-pause-during-experiment.py`；再单独运行 4.17，检查 `S04A_TIMELINE_JSON` 和所有断言；最后执行 `./scripts/ci-gate.sh --all`，确认无 SKIP、`MIN_PAUSEEXP=5` 未降、M-01 其余回归无退化。
