# G0-01 独立修复任务卡：WorkflowRunGate fail-closed 与全生命周期原子准入

- 任务 ID：G0-01
- 优先级：P0；现有执行系统独立热修，不占 AI Workbench 工期
- 建议分支：`fix/g0-01-workflow-run-gate-fail-closed`
- 责任角色：Core/Control 实施；QA/Integration 独立验收
- 历史核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 统一源码复核树：`cf772fcc87f65210e66b2f720c6e65c424611cdb`
- 历史文档参考：`f134aa66717a02385d65c2f6e6b058d2478409bc`
- 当前状态：待实施；构建、业务测试、现场验证均为 UNKNOWN；文档提交 CI gate 因 `LABOS_DB_PASS` 未设置在进入测试前退出
- 交付原则：独立分支、独立提交、独立 PR；不得与 G0-07 或 AI 功能捆绑
- 统一实施与验收基线：[07-G0-01/G0-07 统一基线](07-G0-UNIFIED-IMPLEMENTATION-REVIEW-BASELINE-20261118.md)

> 本卡是实施与验收设计，不是修复完成记录。勾选任何项目都必须附实际提交和原始证据。2026-11-18 综合评审新增的真实 resume 再激活、worker 启动失败和精确 async 参数解析要求已纳入本版。

## 1. 问题与证据

### 1.1 未知状态仍然 fail-open

`WorkflowRunGate.check(DbConfig,long,long,Long)` 在以下路径返回 `Decision.allow()`：

1. `db == null`、`tenantId <= 0` 或 `workflowId <= 0`；
2. `activeStatuses()` 为空；
3. 查询抛出 `SQLException`。

此外，`concurrentAllowed()` 目前排在上述判定之前，显式 allow 会把参数非法、状态词表为空和数据库故障一并变成 `ALLOW`。

### 1.2 两个 Decision 消费点不等于生产入口闭合

当前两个 `Decision.allowed()` 消费点是 `WorkflowWebService.run(long,String)` 和 `ExperimentExecutor.run(...)` 的 `existingRunId <= 0` 预检；真正使流程 Run 成为 active 的生产路径不止这两条：

| 类别 | 生产路径 | 当前调用链与缺口 |
|---|---|---|
| 新建 | 同步 Web | `WorkflowController.run` → `WorkflowWebService.run` 预检 → `WorkflowService.execute` → `createRun`；预检与插入之间不具原子性 |
| 新建 | 异步 Web | `POST /api/workflows/{id}/run?async=1` → `WorkflowService.startAsync` → `createRun`；不经过 `WorkflowWebService.run` |
| 新建 | 调度器首次派发 | 先建 experiment_run → `ExperimentExecutor(existingRunId>0)` 跳过预检 → `runStep` → `workflows.execute` → `createRun` |
| 新建 | 直接实验执行 | 实验级预检不能封住后续 `runStep → workflows.execute → createRun` 的竞争窗口 |
| 再激活 | 暂停／崩溃恢复 resume | `RunActionController` → `RunService` → `PauseCoordinator.resumeWorkflowRun` → `PauseManager.resumeTx` → `resumeToQueued`；当前未经过 WorkflowRunGate |

当前复核到的流程 Run 最终插入集中在 `WorkflowService.createRun → RunMapper.insertRun`，但 resume 不经过该插入点，而是直接 UPDATE 原 Run 状态。实施前必须同时普查 Run 插入点和 active 状态写入点；安全边界不能只锁预检或只锁 createRun。

### 1.3 真实 resume 可绕过“新建 Run”修复

`PauseManager.resumeTx` 接受 paused Run，也接受 `isCrashRecoverableTx` 判定为 true 的 failed Run；非 ABORT 路径直接 `resumeToQueued`。`PauseMapper.resumeToQueued` 当前只按 tenant/run ID 更新，不带原状态条件。

`StartupRecovery.settle` 会把有已完成节点的中断 Run 收尾为 failed/recoverable；状态词表中 failed 非 active，而 queued 为 active。因此以下顺序可达：

1. 旧 Run A 为 failed/recoverable；
2. 新建同 tenant/workflow 的 Run B，B 成为 active；
3. 调用真实 `/api/runs/{A}/resume`，A 被置为 queued；
4. 若无统一准入，A、B 同时 active；若只有新建唯一约束，也可能把正常 resume 变成未映射的数据库异常。

`excludeRunId` 是门禁接口参数，真实 resume 使用原 Run 身份再激活；当前两个生产门禁调用都传 null。接口级自身排除测试不能替代真实 resume 测试。

### 1.4 异步 worker 启动异常可能留下 running 僵尸

`WorkflowService.startAsync` 先 `createRun(status=running)`，再调用 `submitRunWorker`。后者在 `RUN_POOL.submit` 抛出 `RuntimeException` 时只释放内存 worker slot 并重抛；外层只捕获 `IllegalStateException` 并 `markRunFailed`。

因此线程池拒绝等其他 `RuntimeException` 可绕过现有收尾，留下 running Run。门禁修复后，该僵尸还可能长期占用 active 名额。必须覆盖启动失败收尾、稳定错误码和后续可再启动，而不能只扩大 catch 后忽略收尾失败。

### 1.5 异步参数使用字符串包含匹配

`WorkflowController.run` 当前通过查询字符串包含 `async=1` 判定异步。`notasync=1` 或 `x=async=1` 也可能误入异步分支。必须按参数名和值精确解析，且精确异步入口仍消费统一门禁。

## 2. 修复目标与不变量

1. `ALLOW` 只能表示：门禁所需输入与状态词表有效、查询成功，且未发现 active Run；或者查询成功后，显式并发开关仅覆盖 `DENY_ACTIVE`。
2. 已知存在同租户、同 workflow 的 active Run 时，返回 `DENY_ACTIVE`，保留现有 `EC-WORKFLOW-RUNNING` 和 active Run 信息。
3. 参数非法、active 状态词表为空、数据库不可用或查询失败时，返回可区分的 `DENY_UNKNOWN`；所有新建与再激活入口均不得准入。
4. 决策结果至少包含 `outcome + reasonCode + runId/runNo/status + safeReason`；`allowed()` 只能在 `ALLOW` 时为 true。
5. `LABOS_WORKFLOW_CONCURRENT_RUN=allow` 只能跳过“已有 active Run”这一种拒绝，不能跳过非法参数、空词表、DB 故障或事务协调未知。
6. 新建 Run 的“检查—准入—插入—初始状态写入”和 resume 的“检查—准入—原 Run 状态 UPDATE—恢复上下文写入”必须分别原子化，并共享同一数据库级协调语义。
7. 原子协调必须在同一事务、同一物理连接上成立，或提供可证明等价的事务级协调机制。MyBatis/Mapper 的插入和 UPDATE 均不得换连接绕开协调。
8. 采用 `(tenant, workflow)` 粒度的事务锁、部分唯一约束、guard row、可串行化事务或等价机制时，必须覆盖全部流程 Run 插入点和 active 状态 UPDATE，明确 active 状态集合、显式 allow、迁移和回滚。
9. 锁等待超时、死锁、连接中断、事务回滚和提交结果未知均不得放行。准入后回滚须释放协调资源；不同 workflow 不得被无谓全局串行。
10. `excludeRunId`、experiment_run ID 和真实 resume Run ID 必须分别定义。合法续跑／resume 校验 Run 存在、tenant／workflow 归属、pause 或 recoverable 状态和当前请求上下文，只处理自身，不排除其他 active Run。
11. resume 的状态 UPDATE 必须带原状态／recoverable 守卫并核对影响行数，防止检查后状态被另一请求改变；拒绝时原 Run 保持原状态。
12. 原子准入成功后 worker 提交失败时，必须释放 slot、将新 Run 收尾为非 active、返回稳定错误并允许后续启动；收尾自身失败须记录为基础设施故障并进入可恢复机制，不得静默留下永久 running active Run。
13. 同步、异步和 resume 拒绝均返回非 2xx 与独立 `errorCode`；`IllegalStateException`、`SQLException`、线程池拒绝及其他启动 `RuntimeException` 均有明确映射。
14. 异步标志按参数名和值精确解析；`notasync=1`、`x=async=1` 不进入异步分支。
15. 调度器预先创建的 `experiment_run` 在准入失败时必须按现有失败契约收尾，不得永久滞留 queued；本卡不要求倒删既有审计记录。

### 建议错误语义

以下为设计建议；若项目已有统一错误码，应做明确映射并固定 API 契约：

| 场景 | 决策 | 建议错误码 |
|---|---|---|
| 已知同流程 active Run | `DENY_ACTIVE` | `EC-WORKFLOW-RUNNING`（保持兼容） |
| 必填参数非法/缺失 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-INVALID` |
| active 状态词表为空或不可判定 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-STATUS-UNKNOWN` |
| DB 断开、超时或查询失败 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-UNAVAILABLE` |
| 准入后 worker 无法启动 | 启动失败并完成非 active 收尾 | `EC-WORKFLOW-WORKER-UNAVAILABLE` |

## 3. 范围

### 预计涉及

- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowRunGate.java`
- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java`：`execute`、`startAsync`、`createRun`、worker 启动失败收尾的统一原子准入
- `labos-kernel/src/main/java/com/labos/kernel/persistence/RunMapper.java` 及必要的共享事务／数据库迁移
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java`：区分首次派发与合法续跑，保留必要的提前反馈
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java`：仅在实际失败收尾或入口接线需要时
- `labos-kernel/src/main/java/com/labos/kernel/workflow/PauseCoordinator.java`、`PauseManager.java` 与 `PauseMapper`：真实 resume 准入、事务连接与状态守卫
- `labos-kernel/src/boot/java/com/labos/boot/web/service/RunService.java`
- `labos-kernel/src/boot/java/com/labos/boot/web/controller/RunActionController.java`：resume 错误契约
- `labos-kernel/src/boot/java/com/labos/boot/web/service/WorkflowWebService.java`
- `labos-kernel/src/boot/java/com/labos/boot/web/controller/WorkflowController.java`：精确 async 参数及同步／异步错误契约
- `WorkflowRunGateDbSelfTest`、真实 resume／异步入口契约测试、独立进程／连接并发测试及必要的数据库迁移测试
- `scripts/test-all.sh` 或当前测试映射：登记新增自测，不以“文件存在”代替执行
- 本任务卡末尾的实施证据与增量结论

实际文件以全仓普查为准；发现其他 active 状态写入路径时必须更新统一基线和本卡，不得静默豁免。

### 明确不做

- 不重写调度器、设备事件系统、DeviceLock、PositionLock 或 RailZoneGuard。
- 不在本卡处理 G0-03 设备事件身份、G0-11 租户上下文或任何 AI Agent 功能。
- 不通过放宽门禁、吞异常、恢复异常 `allow()` 或豁免 resume 来“降低失败率”。
- 不用模拟器或数据库自测宣称真实设备现场安全已验证。
- 不在本卡裁决 AI 第一阶段范围、G1 ACL 或整体路线图时间线；这些仍由统一基线 §5.2 单列为未关闭事项。

## 4. 实施步骤

1. **固定修复基线并普查入口**：记录实际 HEAD；枚举所有 `createRun` / `RunMapper.insertRun`、resume 和其他 active 状态 UPDATE，同步／异步 Controller、ExperimentExecutor、ExperimentScheduler 与 Pause 路径，形成完整清单。
2. **先补失败用例**：覆盖非法参数、空词表、SQL 异常、异步绕过、调度首次派发、check→create 竞态、真实 resume 双 active、worker 提交 RuntimeException 和 async 字符串误匹配；确认旧实现会失败。
3. **建立三态决策**：区分 `ALLOW / DENY_ACTIVE / DENY_UNKNOWN`，按稳定 `reasonCode` 映射响应；allow 只覆盖 active 冲突；记录安全日志，不泄露 SQL、连接信息或敏感数据。
4. **在真正写入点收口原子准入**：选择数据库唯一约束、advisory lock、guard row、可串行化事务或等价机制，覆盖所有 Run 插入和 resume 状态 UPDATE。记录锁／唯一键粒度、事务顺序、同一物理连接证明及失败行为；不得只给现有 `check` 加锁或只依赖 JVM 内存锁。
5. **闭合真实 resume**：校验 Run 身份、tenant／workflow、pause/recoverable 和请求上下文；在同一协调边界内检查其他 active Run，并以带原状态条件的 UPDATE 再激活。竞争败者不得改变原 Run 状态。
6. **闭合异步路径**：精确解析 async 参数；只有原子准入成功后才能提交 worker。捕获所有相关启动异常并收尾非 active；不得把“先插入 running、事后标 failed”当作并发败者的合格实现。
7. **固定 HTTP 契约**：同步、异步和 resume 拒绝均返回非 2xx 与独立 `errorCode`。active、未知故障与 worker 启动失败必须可区分；JSON 字段或形状变化不得使响应落回 200。
8. **验证无副作用**：所有拒绝、并发失败和启动失败路径都核对流程 Run 数与状态、实验 Run 收尾、worker 数、设备命令数；入口测试通过真实 API／服务动作驱动，不用 SQL `UPDATE` 冒充被测业务动作。
9. **回归与登记**：执行本卡测试、`./scripts/ci-gate.sh --fast` 及当前 workflow 测试映射；记录实际 HEAD、命令、环境、退出码、日志和 SKIP。
10. **独立评审与提交**：Core/Control 自评后由 QA/Integration 复核；提交信息写现象、根因、修法、实际验证和遗留 UNKNOWN。

## 5. 验收清单

共 **28 组验收，G01-T24 含 4 个独立子项**。“通过”必须同时满足预期结果和可追溯的实际证据；未执行、环境缺失或 SKIP 均保持 UNKNOWN。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G01-T01 | 查询成功，无同流程 active Run | `ALLOW`，可进入正常新建流程 | 待执行 |
| G01-T02 | 同 tenant／workflow 存在 running/paused/quarantined Run | `DENY_ACTIVE`，返回正确 active Run ID／状态 | 待执行 |
| G01-T03 | 仅有非 active／终态 Run，或另一 workflow 有 active Run | 不误拦截，不无谓全局串行 | 待执行 |
| G01-T04 | 门禁接口的合法 excludeRunId 自身排除，同时存在另一 active Run | 只排除自身，不排除其他冲突；experiment_run ID 不可替代；本项不替代真实 resume | 待执行 |
| G01-T05 | `db=null`、tenant/workflow ID 非法 | `DENY_UNKNOWN + INVALID`；所有新建入口均无 Run／worker／命令 | 待执行 |
| G01-T06 | active 状态词表为空 | `DENY_UNKNOWN + STATUS-UNKNOWN`，不得 ALLOW | 待执行 |
| G01-T07 | DB 断开、连接超时、查询 SQLException | `DENY_UNKNOWN + UNAVAILABLE`；无副作用；安全日志可追溯 | 待执行 |
| G01-T08 | 同步 Web active 冲突 | 保持 `EC-WORKFLOW-RUNNING` 和 active Run 字段兼容，非 2xx | 待执行 |
| G01-T09 | 同步 Web 未知／DB 故障 | 独立未知错误和非 2xx，不误报 active 或成功 | 待执行 |
| G01-T10 | 调度器已建 experiment_run，以 existingRunId>0 首次派发 | 不跳过流程准入；消费同一决策；无违规流程 Run | 待执行 |
| G01-T11 | 两个同步 Web 竞争同一 tenant/workflow | 恰好一个准入；败者 DENY_ACTIVE、无设备命令；本项是 T20 的定向分解 | 待执行 |
| G01-T12 | 调度首次派发与同步 Web 竞争 | 恰好一个流程 Run；另一方明确拒绝；本项是 T20 的定向分解 | 待执行 |
| G01-T13 | 两个不同 workflow 并发新建 | 均可按各自规则准入 | 待执行 |
| G01-T14 | 默认与显式 allow，叠加非法参数、空词表、DB 故障 | allow 只覆盖 active；所有未知态 fail-closed | 待执行 |
| G01-T15 | 既有 WorkflowRunGate 与 workflow 回归集 | 无回归；新增测试进入可信测试入口；本项是回归证据，不单独证明新缺陷已检出 | 待执行 |
| G01-T16 | 两个 `async=1` 同时启动同一 tenant/workflow | 恰好一个 running；败者无新 Run、worker、设备命令 | 待执行 |
| G01-T17 | 实验预检通过后，真正 createRun 前另一请求占用流程 | 插入边界拒绝；不派发命令；实验 Run 按契约收尾 | 待执行 |
| G01-T18 | 同步／异步分别发生 active、invalid、status-unknown、DB unavailable、IllegalStateException、SQLException、worker 启动失败 | 可区分 errorCode、非 2xx；JSON 变化不产生 200；worker 失败有专门稳定错误 | 待执行 |
| G01-T19 | 调度首次派发在准入处失败 | experiment_run 不永久 queued；无流程 Run、worker、命令 | 待执行 |
| G01-T20 | 同步、异步、直接实验、调度首次派发两两竞争 | 每组合恰好一个流程 Run；不同 workflow 对照不受误伤 | 待执行 |
| G01-T21 | 两个独立进程／实例，各自使用独立连接池和物理 DB 连接争抢同一 workflow | 数据库级协调下最多一个准入；内存锁、单连接池或模拟锁不构成证据 | 待执行 |
| G01-T22 | excludeRunId 指向跨 tenant、跨 workflow、不存在或无关 Run | 全部拒绝且不排除任何 Run；合法自身对照按设计通过；本项不替代真实 resume | 待执行 |
| G01-T23 | 准入成功后事务回滚，随后另一请求竞争 | 回滚不留 active Run；锁释放后另一请求可准入 | 待执行 |
| G01-T24a | 锁等待超时 | 默认与 allow 均不放行、无副作用；原因可诊断、可审计 | 待执行 |
| G01-T24b | 死锁 | 默认与 allow 均不放行、无副作用；原因可诊断、可审计 | 待执行 |
| G01-T24c | 连接中断 | 默认与 allow 均不放行、无副作用；原因可诊断、可审计 | 待执行 |
| G01-T24d | 提交结果未知 | 默认与 allow 均不声称成功；不重复派发；可通过对账确定最终状态 | 待执行 |
| G01-T25 | failed/recoverable 或 paused 的旧 Run A 存在，先新建同流程 Run B，再调用真实 `/resume` 恢复 A | A 被 DENY_ACTIVE；B 保持唯一 active；A 保持原状态；无第二 worker 或设备命令 | 待执行 |
| G01-T26 | 先 resume A 再新建 B；以及真实 resume 与 create 同时竞争同一 tenant/workflow | 两种顺序均最多一个 active；并发竞争恰好一个准入，败者稳定拒绝且无副作用 | 待执行 |
| G01-T27 | 原子准入成功后 `RUN_POOL.submit` 拒绝或抛出其他 RuntimeException | 释放 slot；无永久 running active 僵尸；返回稳定 errorCode；后续同流程可重新启动 | 待执行 |
| G01-T28 | 查询串分别为 `notasync=1`、`x=async=1`、精确 `async=1` | 前两者不进入异步分支；精确 async=1 进入异步且消费统一门禁 | 待执行 |

### 发布前检查

- [ ] 不是只修改三个 `return Decision.allow()`；Run 插入与 resume 状态 UPDATE 的全入口原子准入均有实现和并发证据。
- [ ] 数据库级协调在同一事务、同一物理连接成立；两个独立进程／实例、独立连接池和物理连接的测试实际通过。
- [ ] 锁等待超时、死锁、连接中断、提交结果未知分别实测并 fail-closed；准入后回滚与锁释放已有实测。
- [ ] 真实 resume 校验 Run 存在性、tenant／workflow、pause/recoverable 和请求上下文；状态 UPDATE 有原状态守卫并核对影响行数。
- [ ] T25/T26 通过真实 `/resume` 驱动，未用 excludeRunId 接口测试或手工 SQL 冒充。
- [ ] 同步、异步、直接实验、调度首次派发、resume 均纳入入口清单；其他 active 状态写入路径已普查。
- [ ] `existingRunId`、excludeRunId 和真实 resume Run 身份已分开；调度首次派发不能绕过门禁。
- [ ] allow 开关只覆盖 active 冲突，非法参数、空词表、DB 故障和事务未知仍 fail-closed。
- [ ] active、未知故障和 worker 启动失败可在同步／异步／resume API 和日志中区分，拒绝响应均为非 2xx。
- [ ] worker 提交 RuntimeException 有非 active 收尾、稳定错误码和后续可启动实测；异步竞争败者无新 Run、worker 和设备命令。
- [ ] `notasync=1`、`x=async=1` 不进入异步分支，精确 `async=1` 消费统一门禁。
- [ ] 所有拒绝路径均无违规 active Run、运行状态和设备命令副作用；既有实验审计记录按契约收尾。
- [ ] 新测试通过真实入口验证关键动作；SQL 仅用于明确夹具／清理，不代替被测状态转换。
- [ ] 每个验收 ID 的证据均可追溯实际 HEAD、命令、环境、退出码和原始日志；SKIP 未写成 PASS。
- [ ] `./scripts/ci-gate.sh --fast` 及当前 workflow 映射测试已按实际环境执行并留存结果。
- [ ] 若增加数据库迁移，fresh DB、upgrade DB、drift、active 状态集合、显式 allow、resume、冲突映射和回滚均已验证。
- [ ] 本卡已回填修复 commit、文件／方法签名、入口清单、测试结果和证据位置。
- [ ] 实现就绪度与证据就绪度分别更新；真实设备证据仍单独标记，未外推。

## 6. 实施证据回填

| 字段 | 待填写 |
|---|---|
| 修复基线 HEAD | 待填 |
| 修复 commit / PR | 待填 |
| 全量入口、Run 插入点与 active 状态写入点清单 | 待填 |
| 原子准入方案与理由 | 待填 |
| 同一事务／物理连接证明 | 待填 |
| 真实 resume 身份与状态守卫设计 | 待填 |
| worker 启动失败收尾与错误映射 | 待填 |
| 涉及文件与方法签名 | 待填 |
| 每个验收 ID 的命令、环境、退出码和原始日志位置 | 待填 |
| 数据库迁移与回滚证据（如有） | 待填 |
| Core/Control 实施人 | 待填 |
| QA/Integration 独立验收人 | 待填 |
| 遗留问题与必须保持 UNKNOWN 的项 | 待填 |

## 7. 回滚与边界

回滚不得以恢复异常 `allow()` 或 resume 绕门禁作为止血手段。若修复导致正常运行不可用，应优先关闭受影响的新建／再激活入口或回退完整原子准入变更，同时保留 fail-closed 行为和可审计错误。若含数据库约束／迁移，回滚顺序必须与数据兼容方案一起评审。

本卡完成只能证明应用与数据库层的同流程准入契约；没有真实设备、现场事件日志和物理资源观测时，不得宣称历史串扰已排除或物理闭环已验收。

## 8. 当前收敛状态

本卡已与统一基线同步纳入同一事务／物理连接、默认互斥与显式 allow、真实 resume、worker 启动失败和精确 async 参数解析。后续如出现新的源码事实或反例，必须同步修订统一基线与本卡；不得沿用旧“24 项”口径或将文档完成写成代码修复完成。
