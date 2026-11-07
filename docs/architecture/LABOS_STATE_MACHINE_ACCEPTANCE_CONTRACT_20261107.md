# LabOS 状态机验收契约：事实、投影与暂停完成条件

日期：2026-11-07  
状态：实施前验收契约（PROPOSED FOR IMPLEMENTATION）  
执行基线：main（ADR-PROCESS-001）  
关联决策：ADR-PAUSE-001/002/003/004/005/006/007、ADR-STATE-001、ADR-LAYER-001  
关联工作项：S-01、S-02、S-03、S-04、L-02、L-03、P-06、P-07、M-01、G-03  
状态真源：REMEDIATION_STATUS.md。本文件定义语义和验收，不复制工作项状态。

---

## 0. 本文要解决什么

本文统一回答：

1. 用户发出暂停请求后，系统首先必须持久化什么事实？
2. 哪些组件可以修改事实，哪些组件只能计算展示状态？
3. 什么时候可以对外声称一个 ExperimentRun 已经 PAUSED？
4. 命令仍在执行、响应丢失或设备状态未知时，系统必须如何表现？
5. 如何保留现有 M-01 三层暂停回归，同时迁移到「事实为准、状态为投影」的模型？

**核心裁决：请求已接受 ≠ 暂停已完成。** PAUSED 是底层事实收敛后的投影，不是用户点击按钮时可以直接写入的意图值。任何尚未收敛的物理命令、未知命令、未退出的旧 Worker 或未解决的安全阻断，都不能被状态字段掩盖。

本文不要求一次性重写所有状态表，也不要求立即建设完整聚合器。按 ADR-LAYER-001，先建立可验证的最小契约，再逐步收敛现有实现。

## 1. 规范用语与判定原则

### 1.1 事实、意图、投影和门禁

- **事实（Fact）**：系统已持久化或从权威设备接口核验得到的事件/状态，例如 Hold 已创建、节点已到达安全边界、命令已收到设备终态确认、人工处置已验证。
- **意图（Intent）**：请求系统未来执行的动作，例如 PauseRequested、StopRequested、ResumeRequested。意图持久化不代表动作已发生。
- **投影（Projection）**：根据事实计算得到的运行展示状态，例如 RUNNING、PAUSING、PAUSED、WAITING_INTERVENTION。投影可以重算，不得反过来充当物理事实。
- **门禁（Execution Gate）**：决定是否允许领取新节点、派发设备命令或恢复执行的判定。门禁必须基于事实，不能只看展示状态。
- **UNKNOWN**：无法证明 CLEAR，也无法证明 HELD 的状态。UNKNOWN 必须阻断可能产生物理副作用的动作；不得按空集合、无 Hold、空闲资源或已停止处理。

### 1.2 强制原则

1. **事实先于投影**：先持久化请求、Hold、节点/命令事实，再计算展示状态。
2. **请求先于执行**：Pause API 成功表示请求已被可靠接受，不表示设备已停止。
3. **完成必须有证据**：没有命令终态或安全边界证据，不得宣布暂停完成。
4. **UNKNOWN fail-closed**：查询失败、事件缺失、状态冲突、租约/Worker 所有权不明，都不能当成 CLEAR。
5. **单一写入职责**：业务入口写事实和意图；状态投影由统一状态计算组件维护。迁移期可由适配层协调旧表，但不能继续增加互相竞争的状态写入者。
6. **不把软件取消等同物理停止**：Future cancel、线程中断、本地 pending map 删除，不证明设备已停止。
7. **保持旧契约**：M-01 的三层最终状态对齐及 Resume 作废旧 paused run 的防重复执行行为必须保留为兼容回归。
8. **不误伤其他运行**：本契约定义单个 ExperimentRun 的暂停；其他 ExperimentRun 的故障影响范围仍按 ADR-FAULT-SCOPE-001/002 和对应 Hold 规则判定。

## 2. 状态事实模型与职责

本节定义验收职责，不意味着本轮必须新建四张表。现有表可通过适配层承载事实，后续逐步迁移。

| 事实域 | 事实内容 | 允许写入者 | 禁止事项 |
|---|---|---|---|
| Run Control / Intent | Pause、Resume、Stop、Abort 请求；请求人、原因、时间、幂等键、目标 Run | PauseCoordinator（统一入口） | API/页面直接把最终状态写成 paused |
| Hold Ledger | 每个独立阻断原因、scope、source、severity、active/cleared、清除证据 | Hold 服务 / 经授权的安全事件处理器 | scope-wide 清除；清一个 Hold 顺带清掉其他 Hold |
| Node / Step Execution | 节点是否待执行、执行中、到达安全边界、完成、失败、待人工 | 对应执行器 | 聚合器伪造节点完成或安全停止 |
| Command Execution | 命令创建、派发、设备 ACK、执行中、终态、UNKNOWN、对账状态 | 命令服务、驱动回调、对账服务 | 把本地 Future 状态当设备事实；未知命令自动重派 |
| Alarm / Intervention | 报警事件、人工确认、处置过程、验证证据 | 报警/人工处置服务 | 报警 inactive 自动清除人工 Hold |
| Resource / Ownership | 资源占用、租约、fencing token、Worker owner、隔离状态 | 资源锁服务 / 执行协调器 | 仅凭 TTL 到期释放物理隔离 |

### 2.1 投影层职责

投影器读取上述事实，计算 RunControl 状态、Experiment/ExperimentRun 列表状态、UI attention state、Execution Gate 和可执行/不可执行原因。

投影器不得创建或删除 Hold，不得将命令 UNKNOWN 改成终态，不得自行确认设备安全，不得替人工完成 Intervention。

### 2.2 迁移期兼容写入

迁移期可以暂时保留 run.status、experiment_run.status、experiment.status 作为兼容读模型，但必须满足：

- 它们由 PauseCoordinator/状态适配层协调更新，而不是各业务入口各写一份。
- 更新失败时记录投影不同步，不能回滚或伪造底层事实。
- 对外 API 不得只凭某个 status 字段判断可恢复或物理设备已停止。
- 任何状态不一致均可通过事实重新计算和修复。
- 新增写路径不得再绕过统一入口直接修改最终状态。

## 3. Run Control 状态契约

### 3.1 规范状态

| 状态 | 含义 | 是否允许新节点派发 | 是否代表暂停完成 |
|---|---|---:|---:|
| CREATED | Run 已创建，尚未开始 | 否，须经过启动门禁 | 否 |
| RUNNING | 正常执行 | 是，受其他门禁约束 | 否 |
| PAUSE_REQUESTED | 暂停意图已可靠持久化，尚未完成派发冻结/收敛 | 否 | 否 |
| PAUSING | 已停止领取新工作，正在等待在途动作和节点到达安全边界 | 否 | 否 |
| WAITING_INTERVENTION | 需要人工处置；属于 attention/gate 投影，不强制成为唯一持久化 Run 状态 | 否 | 否 |
| RECONCILIATION_REQUIRED | 命令、设备、Worker 或资源事实 UNKNOWN，需要对账 | 否 | 否 |
| PAUSED | 暂停完成条件全部满足 | 否 | **是** |
| RESUMING | 恢复门禁通过，正在重新建立执行所有权/逐节点恢复 | 否，除非对应节点已获准 | 否 |
| STOPPING | 有序停止正在收敛 | 否 | 否 |
| STOPPED | 有序停止完成，不承诺可从原处继续 | 否 | 否 |
| ABORTING | 异常终止正在处理 | 否 | 否 |
| ABORTED | 异常终止完成；不等同硬件 E-stop | 否 | 否 |
| COMPLETED | 业务执行完成 | 否 | 否 |
| FAILED | 执行失败且未进入可恢复暂停 | 否 | 否 |
| CANCELED | 尚未执行或按业务取消语义取消 | 否 | 否 |

说明：

- WAITING_INTERVENTION、RECONCILIATION_REQUIRED 可以是投影层 attention/gate 状态，不要求与 RunControl 主状态互斥地写进同一个 status 字段。
- 实际枚举编码、数据库长度和 API 字段由 L-02 / P-11 核实；不得因为现有 varchar(16) 而悄悄缩短语义或静默映射为 paused。
- PAUSED 不表示所有设备断电或绝对静止；它表示所有受控动作已按设备能力到达定义的安全暂停边界，且不存在未知的继续执行风险。

### 3.2 Pause 请求接受契约

Pause API 成功的语义：

> 系统已可靠记录本次暂停请求，并建立阻止该 Run 继续领取新工作的控制事实。设备/节点是否已收敛，需通过后续状态查询确认。

成功响应应包含或可查询到：run/experiment_run 标识、稳定的 pause request ID、请求已接受时间、当前 RunControl 投影、当前 execution gate/attention state，以及已知的未收敛原因或待处理节点。

不得在设备仍执行、命令 UNKNOWN 或 Worker 所有权不明时返回「暂停完成」。HTTP 状态码和响应 DTO 由 API 设计确定；HTTP 200 本身不代表物理暂停完成。

### 3.3 Pause 完成契约

只有以下条件全部满足，才允许将 RunControl 投影为 PAUSED：

1. 本次 Pause 意图及对应 Hold 已可靠持久化且仍有效；若采用独立 PauseRequest 表达，必须能追溯到唯一请求。
2. 新节点/新命令派发已被阻断；所有派发入口都检查同一 Run 的暂停门禁。
3. 所有活动节点均已到达允许的暂停边界，或进入有明确证据的终态/人工接管状态。
4. 所有已派发物理命令均已收敛：设备确认完成、设备确认停止/取消，或达到设备能力定义的安全边界；不能存在未解决的 UNKNOWN。
5. 不存在仍可能继续对设备产生副作用的旧 Worker；Worker ownership/fencing token 已确认，或旧 Worker 已退出并被隔离。
6. 所有资源占用与物理位置状态满足设备/工艺暂停策略；未知资源不得释放并重新派发。
7. 必要的报警、人工处置和安全联锁条件已明确；若需要人工，投影应为 WAITING_INTERVENTION / RECONCILIATION_REQUIRED，而不是 PAUSED。
8. 三层兼容状态（如仍存在）已同步为最终 paused；兼容状态同步失败必须显示投影不一致，不能把它当作事实成功。

**非可中断设备动作可以继续到其安全边界。** 如果设备能力明确规定当前方法不可中断，系统可以保持 PAUSING，等待当前动作正常完成，再阻止下一节点派发。该动作完成且有可信终态证据后，可以满足命令收敛条件；这不代表设备被中途 Abort。若动作是否完成无法确认，则保持 UNKNOWN / RECONCILIATION_REQUIRED。

### 3.4 不满足条件时的状态

| 观察到的事实 | 最低允许投影 | 必须阻断 |
|---|---|---|
| Pause 已接受，但尚未停止领取新任务 | PAUSE_REQUESTED | 新节点/命令派发 |
| 新派发已冻结，在途命令仍运行 | PAUSING | 新节点/命令派发、Resume |
| 命令响应丢失或设备查询失败 | RECONCILIATION_REQUIRED | Resume、资源重新分配、重放命令 |
| 需要人工确认/物理盘点 | WAITING_INTERVENTION | 后续依赖节点、Resume |
| 旧 Worker 是否仍执行不明 | RECONCILIATION_REQUIRED | 新 Worker 接管、重复派发 |
| 所有安全条件满足且事实一致 | PAUSED | 新节点派发；等待显式 Resume |

## 4. Pause 请求的事务与执行顺序

### 4.1 接受请求的事务边界

PauseCoordinator 必须将以下控制事实作为一个逻辑原子操作：

1. 校验 Run 当前状态和操作者权限；
2. 按幂等键创建 PauseRequest / 控制意图；
3. 创建本次用户 Hold（若该暂停语义使用 Hold）；
4. 将 Run Control 意图推进到 PAUSE_REQUESTED；
5. 记录受影响活动节点/执行上下文的暂停意图；
6. 写入可投递的事件/Outbox；
7. 提交事务。

事务提交前，不得对调用方声称请求已接受；事务提交后，事件投递失败不能撤销已提交的暂停事实，应由 Outbox 重试或恢复扫描补偿。

若现有表无法在同一数据库事务内承载全部事实，必须先明确事务边界和恢复扫描策略；不得以多个独立连接先后写库并将部分成功伪装成完整成功。

### 4.2 异步收敛顺序

事务提交后：

1. 所有 Worker 在领取新节点、发出新设备命令前检查 Pause/Hold gate。
2. 已派发命令按设备 capability 和当前工艺阶段处理：
   - 支持安全 Pause：发出设备 Pause 请求并等待设备确认；
   - 支持受控停止：按设备契约请求停止并等待确认；
   - 支持物理 Abort：仅在设备能力和当前动作允许时请求 Abort，并等待设备侧证据；
   - 不支持中断：等待当前动作完成或达到设备定义的安全边界；
   - 查询失败、响应丢失、结果不明：记录 UNKNOWN，进入对账，不重发物理命令。
3. 节点执行器记录安全边界、完成或人工接管事实。
4. 资源锁服务保留仍被物理占用/状态未知的资源；不因暂停请求或租约 TTL 到期而释放物理隔离。
5. 聚合器根据最新事实重新计算 RunControl 和 UI attention/gate。
6. 满足 §3.3 全部条件时，才将状态投影为 PAUSED。

### 4.3 重复请求与并发请求

- 相同幂等键的重复 Pause 请求返回同一请求结果，不创建重复 Hold 或重复设备命令。
- 不同来源的 Hold 可以并存；一个 Hold 的清除不得清除另一个 Hold。
- Pause 与设备故障并发时，两种事实都必须保留；暂停完成/恢复门禁按所有有效 Hold 与命令事实计算。
- Pause 与 Stop/Abort 并发时，必须按已接受的控制意图和状态转换规则串行化；不允许两个控制器各自覆盖最终状态。
- 状态版本或乐观锁用于防止迟到事件覆盖更新事实；旧版本事件只能进入审计/对账路径，不能倒退状态。

## 5. Resume 契约

Resume 是一次新的受控请求，不是把 status 从 paused 改回 running。

### 5.1 恢复前置条件

必须全部满足：

1. 本 Run 的用户 Pause Hold 已按 hold_id 清除，或明确采用可审计的豁免策略。
2. 其他设备故障、安全、质量或人工 Hold 均已分别解除；不能由 Resume 顺手清除。
3. 所有命令均已查询并收敛；UNKNOWN 必须完成对账。
4. 所有必要 InterventionRecord 已完成并通过验证。
5. 设备自检、联锁复位、校准/配置有效性和物理位置确认通过。
6. 板/样本身份、板位和交接状态已确认；不确定时阻断。
7. Protocol / Workflow / ExecutionPlan 快照仍有效；如果版本改变，必须重新验证或要求显式重新规划，不能静默执行新图。
8. 旧 Worker 已退出或被 fencing；新 Worker 获得新的所有权凭证。
9. M-01 规定的旧 paused run 作废/防重放行为仍然有效，不能因恢复重构而重复执行已经产生物理副作用的步骤。

### 5.2 Resume 执行顺序

1. 在事务中记录 ResumeRequest 和审计事件；确认 Hold/命令/设备/快照检查的版本。
2. 重新验证所有门禁；任何检查结果 UNKNOWN 即拒绝恢复。
3. RunControl 投影进入 RESUMING。
4. 为可恢复节点生成明确 continuation/checkpoint；区分已完成、可安全重试、必须人工处置的节点。
5. 新 Worker 获得 ownership/fencing token 后，逐节点进入可恢复状态。
6. 首个后续物理命令派发前再次检查 gate 和资源锁。
7. 确认执行器已接管且没有重复命令风险后，投影进入 RUNNING。

## 6. 三层兼容状态与 M-01 的关系

M-01 当前回归基线要求：

- experiment_run.status 最终为 paused；
- experiment.status 最终为 paused；
- 底层 workflow run.status 最终为 paused；
- 后续节点保持 pending；
- Resume 作废旧 paused run，避免重复物理动作。

该行为继续保留，但必须区分「最终状态一致」与「暂停完成的充分证据」。

### 6.1 兼容三层状态的正确解释

三层 paused 是兼容投影对齐要求，不是独立事实来源。它们只能在 §3.3 的暂停完成条件成立后统一写成 paused。

如果三层中某一层先更新成功、另一层更新失败：

- 记录 projection_sync_pending 或等价可恢复事实；
- 不得宣称三层对齐；
- 不得开放 Resume；
- 由协调器重试或重算投影；
- 不得为了让测试通过而把尚未收敛的 Run 提前写成 paused。

### 6.2 M-01 不可回退项

任何状态机重构都必须继续证明：

1. 实验级 Pause 能阻断后续节点派发；
2. 三层最终兼容状态一致；
3. 设备命令在途时不误报暂停完成；
4. Resume 不会重新执行已完成的物理副作用；
5. 旧 paused run 被正确作废/隔离；
6. 重复 Pause / Resume 不会产生重复命令或重复 Hold。

## 7. S-04 的两阶段 PR 验收方案

**禁止把判据升级和 API 语义切换放在同一个 PR。**

### PR-S04-A：先补事实证据与兼容回归，保持现有 API 行为

目标：不改变现有 API 的 legacy status 语义，先把 4.17 从「只看三层 status」升级为「三层兼容状态 + 可核验的暂停事实证据」。本 PR **不宣称已完成新 PAUSED 投影语义**。

**已从 main @ 3debf785 源码确认的约束：**

- `RunControl.pause()` 立即创建 RuntimeHold，并立即把覆盖范围内的 `experiment_run` 标为 paused；`markCoveredPaused()` 随后另开连接更新 `experiment.status`。
- `ExperimentApi.handle()` 当前 HTTP 200 直接返回 `status: "paused"`。
- Workflow Run 要等当前设备动作完成，再到下一个节点边界才会落 paused。
- 4.17 通过 `device_state.status='running' + current_method + current_run_id` 判断设备动作正在执行；`run_command` 是步骤结束后才落库，不能证明命令仍在执行。
- 因此当前 legacy API/UI 在设备动作仍执行时可能已经显示 paused。这是 PR-S04-B 要解决的语义缺口，不能在保持现有 API 行为的 PR-S04-A 里假装它不存在。

必须完成：

1. 保留现有 e2e 4.17 的 M-01 三层最终 paused、后续节点 pending 断言。
2. 增加 RuntimeHold 持久化证据：本次测试创建的 Hold ID、scope、scope_id、kind、held_at、released_at；必须绑定当前实验/测试 run，不用全局最新 Hold 代替。
3. 明确验证请求时设备确实在执行：使用 `device_state.status='running'`、`current_method`、`current_run_id`，并记录请求前后的状态。
4. 明确验证收敛：等待当前动作结束；确认对应设备不再处于本次 run 的 running 状态；Workflow Run 在节点边界进入 paused；后续节点保持 pending；步骤结束后出现的 `run_command` 只作为动作已结束的补充证据，不作为“命令在途”证据。
5. 在测试输出中分别记录三个时刻：Pause 请求接受、设备动作结束、Workflow 节点边界暂停。不得把实验级 status 提前变 paused 的时刻写成物理暂停完成时刻。
6. legacy `experiment_run.status` / `experiment.status` 在请求后提前变 paused 的行为，本 PR 作为已知兼容行为记录，不把它解释成安全暂停完成；本 PR 不增加“未收敛时 API/UI 不得显示 PAUSED”的断言。
7. 更新测试用例总表和对应英文文档，明确「legacy 三层状态」与「事实收敛证据」是两类断言。
8. 不在本 PR 中加入当前 main 无法满足的 UNKNOWN fail-closed 断言；Hold 三态归 P-03，命令 ACK 丢失与 reconciliation 归 P-07/L-03。对应故障注入测试必须随这些实现工作项落地，并在 S-04-B 前成为硬门禁。
9. 不改变 Pause API 当前的外部 status 语义；不引入未完成的 PauseCoordinator 大规模替换。

PR-S04-A 通过条件：

- 原 4.17 全部既有断言继续通过；
- 新增 Hold 记录与设备执行事实断言通过；
- 新增动作结束、节点边界暂停、后续 pending 的证据链通过；
- 测试输出能区分请求接受时间与物理动作收敛时间；
- 不把 legacy `status=paused` 单独当成安全暂停证据；
- M-01 其他回归无退化；
- 无 SKIP；记录 CI/e2e run id 和断言数。

**边界：** PR-S04-A 是“事实证据与旧契约并行”的兼容阶段，不是“新状态语义已经切换”的阶段。它不能同时承诺旧 API 行为不变、又承诺未收敛时 API/UI 不显示 PAUSED。

### PR-S04-B：再切换 API / 投影语义

前置：PR-S04-A 已合入 main 并通过；L-02 的状态表达能力已具备；S-01 映射已完成；S-02 / P-06 的 PauseCoordinator 适配路径已可用。

必须完成：

1. Pause API 只返回「请求已接受」及 request ID，不同步伪造最终 paused。
2. PAUSE_REQUESTED / PAUSING / RECONCILIATION_REQUIRED / WAITING_INTERVENTION 等状态能正确表达；数据库长度、API schema、前端词表一致。
3. 只有事实聚合满足 §3.3 时才投影为 PAUSED。
4. Resume API 使用 §5 的恢复门禁。
5. 三层兼容状态仍在最终收敛后对齐；不再要求它们在 Pause 请求提交瞬间同步 paused。
6. 保留 M-01 的防重复物理动作行为与回归。
7. 更新测试总表、e2e、前端状态展示与中英文文档。

PR-S04-B 通过条件：

- 请求接受与暂停完成可以在 API、数据库、UI 中明确区分；
- 在途命令、UNKNOWN、旧 Worker、未解除 Hold 均不能进入 PAUSED 或 RESUMING；
- 所有完成条件满足后可最终收敛到 PAUSED；
- Resume 从正确 checkpoint 继续且无重复物理副作用；
- 全量门禁无 SKIP；必要的设备 HIL 证据另行登记。

## 8. 最小测试矩阵

| ID | 场景 | 关键断言 | 类型 |
|---|---|---|---|
| ST-01 | 空闲边界请求 Pause | 请求和 Hold 可靠持久化；新节点不再领取；满足完成条件后 PAUSED | DB/API |
| ST-02 | 设备动作执行中请求 Pause（不可中断） | 先 PAUSING；当前动作按设备契约完成；下一节点不派发；确认收敛后 PAUSED | e2e/HIL |
| ST-03 | 设备动作执行中请求 Pause（可中断） | 仅按设备声明能力请求中断；收到设备侧证据后才收敛；本地 Future cancel 不算停止 | e2e/HIL |
| ST-04 | 命令已发出但响应丢失 | 命令 UNKNOWN；不得重发；不得 PAUSED / Resume / 释放资源 | 故障注入 |
| ST-05 | Pause 接受事务提交后事件投递失败 | Hold/意图仍存在；Outbox 可重试；不能丢失请求或重复设备命令 | DB/Outbox |
| ST-06 | Pause 接受事务中途失败 | 全部事务事实回滚，或按明确恢复协议进入可对账状态；API 不得声称接受 | DB |
| ST-07 | 三层状态写入部分失败 | 投影标记不同步；事实不被覆盖；Resume 阻断；可重算修复 | DB |
| ST-08 | 用户 Pause + 设备故障 Hold 并存 | 清除一个 Hold 后另一个仍阻断；不得 scope-wide clear | DB/API |
| ST-09 | Pause 与 Resume 并发/重复 | 幂等；控制意图串行化；不产生双 Worker 或重复命令 | 并发 |
| ST-10 | 旧 Worker 未退出时 Resume | 新 Worker 不接管；或 fencing 旧 Worker 后才允许；旧写被拒 | 多实例 |
| ST-11 | Hold 查询或设备状态查询异常 | UNKNOWN fail-closed；不能按无 Hold/设备空闲处理 | 故障注入 |
| ST-12 | M-01 最终三层对齐 | 三层最终 paused、后续节点 pending、旧 paused run Resume 时作废 | 回归 |
| ST-13 | 完成命令的安全边界暂停 | 非可中断动作完成并有可信终态后可暂停；不能声称曾被 Abort | e2e/HIL |
| ST-14 | 暂停期间资源租约到期 | 仅软件租约状态可更新；物理占用/UNKNOWN 资源仍隔离 | 多实例/HIL |

测试数据必须绑定本次测试创建的 run/request/command ID；不得用全局 max(id)、历史日志或其他测试遗留状态作为证据。

## 9. 实施前必须核实的源码与 Schema

以下是实施前核实项，不在本文中假设已经存在：

1. run、experiment_run、experiment 的真实关联基数与创建/结束/重启恢复语义（S-01）。
2. pause_kind、状态枚举、前端词表、API DTO 和数据库字段长度；尤其 WAITING_INTERVENTION 不可直接写入长度不足的列（L-02/P-11）。
3. run_command 的创建时机、状态更新路径、commandId 与设备 execution ID 的映射；当前「步结束才落库」是否已改变，必须以目标 main commit 的源码复核（L-03/P-07）。
4. Hold、PauseRequest、节点意图和 Outbox 是否能在同一 PG 事务提交；若不能，明确事务边界、幂等键和恢复扫描（P-06/P-07）。
5. 所有写 run.status / experiment_run.status / experiment.status 的代码位置和调用方；建立允许写入者清单。
6. Worker ownership、fencing token、设备状态查询和命令 reconciliation 的实际实现能力。
7. 旧 paused run 作废逻辑与重复物理动作保护的完整测试覆盖（M-01/G-03）。

本文件描述目标契约，不把历史审计中的源码观察自动当作当前 main 的事实。每个 PR 开始前必须对目标 commit 复核。

## 10. 与其他架构范围的边界

- 跨 Workcell Experiment 的故障影响单位按 ADR-FAULT-SCOPE-002 为 ExperimentRun；本文件的 Pause 完成条件不改变该裁决。
- 人工 Handoff 的人工确认属于 Intervention/Hold 事实；配置期 Handoff 存在不等于本次交接已完成。
- Redis + PG 资源锁按 ADR-LOCK-001；锁租约到期不等于物理资源安全释放。
- SafetyDomain、完整 DependencyEdge 图、完整聚合器平台按 ADR-LAYER-001 延后建设；但任何依赖闭包 UNKNOWN 必须 fail-closed。
- 硬件 Emergency Stop 由独立安全回路负责；软件 PauseCoordinator 不得宣称替代 E-stop。

## 11. 实施顺序与文档治理

按 REMEDIATION_STATUS.md 的唯一工作项状态执行，本文件不复制状态表。建议的逻辑依赖：

1. S-01：完成两套 run 模型映射与写入点清单。
2. 核对 L-02/L-03/P-06/P-07 的 schema、事务、命令账本和 Outbox 能力。
3. PR-S04-A：先升级 4.17 验收判据，保留 M-01 旧断言。
4. L-02：具备最小中间态表达能力。
5. S-02/P-06：通过 PauseCoordinator 统一入口与事务边界。
6. S-03：逐步收敛事实写入和状态投影。
7. PR-S04-B：最后切换 API 和 UI 的请求/完成语义。
8. S-05：更新旧状态相关文档索引，旧文档只作为历史证据或按 ADR 标注的兼容说明。

**状态表现有顺序提示：** REMEDIATION_STATUS.md Step 5 把 N-03 排在 N-01 之前，但 N-03 依赖列又写 N-01。执行前必须由状态表维护者消除该依赖矛盾；本文件不擅自覆盖唯一状态真源。

## 12. Definition of Done

状态机验收契约只有在以下全部满足时，才算落地：

- [ ] S-01 映射表有源码路径、调用链、字段、关联基数和重启行为证据。
- [ ] 所有 Pause/Resume 写入口已纳入统一协调路径，或有明确、限期的兼容例外。
- [ ] Pause 请求提交与设备/节点收敛在 API/UI 中可区分。
- [ ] PAUSED 只能由满足 §3.3 的事实聚合产生。
- [ ] UNKNOWN、Hold 并存、旧 Worker、资源隔离都能阻断 Resume。
- [ ] M-01 三层最终状态对齐和旧 run 作废契约保留。
- [ ] PR-S04-A 与 PR-S04-B 分开合并，不能合并成一个同时改变判据和实现的 PR。
- [ ] CI/e2e 无 SKIP；物理停止相关能力有设备型号、固件、方法、负载和停止后状态的 HIL 证据。
- [ ] REMEDIATION_STATUS.md 的 S-01~S-04、L-02/L-03、P-06/P-07 状态按证据更新。

---

**裁决摘要：** PAUSE_REQUESTED 表示请求已可靠接受；PAUSING 表示系统正在安全收敛；WAITING_INTERVENTION / RECONCILIATION_REQUIRED 表示需要人工或对账，均不等于暂停完成；只有 Hold、节点、命令、Worker 和资源事实全部满足安全暂停条件，才允许投影为 PAUSED。三层 paused 继续作为 M-01 的最终兼容回归，但不再是唯一验收证据。
