# G0-01 独立修复任务卡：WorkflowRunGate fail-closed 与全入口原子准入

- 任务 ID：G0-01
- 优先级：P0；现有执行系统独立热修，不占 AI Workbench 工期
- 建议分支：`fix/g0-01-workflow-run-gate-fail-closed`
- 责任角色：Core/Control 实施；QA/Integration 独立验收
- 历史核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 本次静态复核代码树：`f134aa66717a02385d65c2f6e6b058d2478409bc`（相关源码与 `28a4b36` 一致）
- 当前状态：待实施；构建、测试、CI、现场验证均为 UNKNOWN
- 交付原则：独立分支、独立提交、独立 PR；不得与 G0-07 或 AI 功能捆绑

> 本卡是实施与验收设计，不是修复完成记录。勾选任何项目都必须附实际提交和原始证据。2026-11-18 交叉评审指出的异步、调度首次派发和真正插入点缺口已纳入本版。

## 1. 问题与证据

### 1.1 未知状态仍然 fail-open

历史基线和本次静态复核均可见：`WorkflowRunGate.check(DbConfig,long,long,Long)` 在以下路径返回 `Decision.allow()`：

1. `db == null`、`tenantId <= 0` 或 `workflowId <= 0`；
2. `activeStatuses()` 为空；
3. 查询抛出 `SQLException`。

此外，`concurrentAllowed()` 目前排在上述判定之前，显式 allow 会把参数非法、状态词表为空和数据库故障一并变成 `ALLOW`。

### 1.2 两个 Decision 消费点不等于生产入口闭合

本次复核到的两个 `Decision.allowed()` 消费点是 `WorkflowWebService.run(long,String)` 和 `ExperimentExecutor.run(...)` 的 `existingRunId <= 0` 预检；但真正新建流程 Run 的路径不止这两条：

| 生产路径 | 当前调用链与缺口 |
|---|---|
| 同步 Web | `WorkflowController.run` → `WorkflowWebService.run` 预检 → `WorkflowService.execute` → `createRun`；预检与插入之间隔着准备和编译代码，不具原子性 |
| 异步 Web | `POST /api/workflows/{id}/run?async=1` → `WorkflowService.startAsync` → `createRun`；不经过 `WorkflowWebService.run`，当前没有门禁 |
| 调度器首次派发 | `ExperimentScheduler.insertRun` 先建 `experiment_run` → `ExperimentExecutor.run(..., existingRunId>0)` 跳过整段预检 → 每一步 `runStep` 调 `workflows.execute(workflowId)` → `createRun`；首次派发仍可新建流程 Run |
| 直接实验执行 | `existingRunId <= 0` 时先做实验级预检，但每个流程 Run 仍在后续 `runStep → workflows.execute → createRun` 才创建；预检不能封住竞争窗口 |

当前复核到的流程 Run 最终插入集中在 `WorkflowService.createRun → RunMapper.insertRun`。实施前仍须重新做全仓调用点普查；安全边界必须落在这一真正创建边界，而不是只锁预检。

### 1.3 `existingRunId` 与流程续跑不是一回事

`ExperimentExecutor.existingRunId` 是调度器预先创建的 **experiment_run ID**，不是 workflow run ID；`existingRunId > 0` 既可能是调度器首次派发，也可能是后续恢复，不能据此跳过流程门禁。

当前两个 `WorkflowRunGate.check` 生产调用都传 `excludeRunId=null`。实验的已完成步骤恢复由 `completedStepKeys` 等逻辑处理。实现时必须分别定义“调度器复用实验 Run”和“合法流程续跑排除自身”，不得混用。

## 2. 修复目标与不变量

1. `ALLOW` 只能表示：门禁所需输入与状态词表有效、查询成功，且未发现 active Run；或者查询成功后，显式并发开关仅覆盖 `DENY_ACTIVE`。
2. 已知存在同租户、同 workflow 的 active Run 时，返回 `DENY_ACTIVE`，保留现有 `EC-WORKFLOW-RUNNING` 和 active Run 信息。
3. 参数非法、active 状态词表为空、数据库不可用或查询失败时，返回可区分的 `DENY_UNKNOWN`；所有新建流程 Run 的入口均不得准入。
4. 建议决策结果至少包含 `outcome + reasonCode + runId/runNo/status + safeReason`；`allowed()` 只能在 `ALLOW` 时为 true，不能把 active 冲突与未知故障合并。
5. `LABOS_WORKFLOW_CONCURRENT_RUN=allow` 只能跳过“已有 active Run”这一种拒绝，不能跳过非法参数、空词表或 DB 故障。实现顺序必须保证这一点。
6. 新流程 Run 的“检查—准入—插入”必须在 `createRun` / `insertRun` 边界原子化，覆盖同步、异步、实验直接执行和调度首次派发。预检可保留用于提前反馈，但不是安全边界；单 JVM 的 `synchronized` 或内存锁不能作为跨线程、跨进程、跨实例的最终协调机制。
7. 采用 `(tenant, workflow)` 粒度的事务锁、部分唯一约束、guard row、可串行化事务或等价机制时，必须覆盖全部流程 Run 插入点，并明确 active 状态集合、显式 allow 语义、迁移和回滚。实施记录必须写明锁获取、active 检查、Run 创建、初始状态写入和事务提交的顺序及原子范围。
8. 锁等待超时、死锁、连接中断、事务回滚和提交结果未知均不得放行。准入成功后若事务回滚，锁释放后另一请求应能正常竞争；不同 workflow 不得被无谓全局串行。
9. `excludeRunId` 只用于经过身份与归属校验的合法流程续跑。其他 tenant、其他 workflow、不存在或不属于当前续跑上下文的 Run ID 均不得被排除；实验 Run ID 不能代替流程 Run ID。
10. 调度器预先创建的 `experiment_run` 已存在时，准入失败必须按现有失败收尾机制处理，不得永远滞留 `queued`；本卡不要求倒删这条既有审计记录。

### 建议错误语义

以下为设计建议；若项目已有统一错误码，应做明确映射并固定 API 契约：

| 场景 | 决策 | 建议错误码 |
|---|---|---|
| 已知同流程 active Run | `DENY_ACTIVE` | `EC-WORKFLOW-RUNNING`（保持兼容） |
| 必填参数非法/缺失 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-INVALID` |
| active 状态词表为空或不可判定 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-STATUS-UNKNOWN` |
| DB 断开、超时或查询失败 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-UNAVAILABLE` |

## 3. 范围

### 预计涉及

- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowRunGate.java`
- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java`：`execute`、`startAsync`、`createRun` 的统一原子准入
- `labos-kernel/src/main/java/com/labos/kernel/persistence/RunMapper.java` 及必要的共享事务／数据库迁移
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java`：区分首次派发与合法续跑，保留必要的提前反馈
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java`：仅在实际失败收尾或入口接线需要时
- `labos-kernel/src/boot/java/com/labos/boot/web/service/WorkflowWebService.java`
- `labos-kernel/src/boot/java/com/labos/boot/web/controller/WorkflowController.java`：同步与异步错误契约
- `labos-kernel/src/test/java/com/labos/kernel/workflow/WorkflowRunGateDbSelfTest.java`
- 新增同步／异步／调度／实验入口契约与并发自测，以及必要的共享准入服务或数据库迁移
- `scripts/test-all.sh` 或当前测试映射：登记新增自测，不以“文件存在”代替执行
- 本任务卡末尾的实施证据与增量结论

### 明确不做

- 不重写调度器、设备事件系统、DeviceLock、PositionLock 或 RailZoneGuard。
- 不在本卡处理 G0-03 设备事件身份、G0-11 租户上下文或任何 AI Agent 功能。
- 不通过放宽门禁、吞异常、恢复异常 `allow()` 来“降低失败率”。
- 不用模拟器或数据库自测宣称真实设备现场安全已验证。

## 4. 实施步骤

1. **固定修复基线并普查入口**：记录实际 HEAD；枚举 `WorkflowService.execute/startAsync/createRun`、`RunMapper.insertRun`、同步／异步 Controller、ExperimentExecutor 和 ExperimentScheduler 的全部相关调用，形成入口清单。
2. **先补失败用例**：覆盖非法参数、空词表、SQL 异常、异步绕过、调度首次派发和 check→create 竞态；确认旧实现会失败，避免写出永远为绿的测试。
3. **建立三态决策**：区分 `ALLOW / DENY_ACTIVE / DENY_UNKNOWN`，按稳定 `reasonCode` 映射响应；调整 allow 开关位置，使其只覆盖 active 冲突；记录安全日志，不泄露 SQL、连接信息或敏感数据。
4. **在真正插入点收口原子准入**：由 Core/Control 选择数据库唯一约束、advisory lock、guard row、可串行化事务或等价数据库级机制，覆盖所有 `createRun` 调用。记录锁／唯一键粒度、active 检查、Run 创建、初始状态写入、提交的事务顺序，以及锁等待超时、死锁、连接中断、提交结果未知和回滚行为。不得只给现有 `check` 加锁，也不得只依赖单 JVM 内存锁。
5. **闭合异步路径**：只有原子准入成功后才能提交 worker；竞争败者不得留下新 Run、worker 或设备命令。不得把“先插入 running、事后标 failed”当作并发败者的合格实现。
6. **修正实验与续跑语义**：调度器复用 `experiment_run` 不等于流程续跑；每个真正的新流程 Run 都经过统一原子准入。合法续跑单独识别，校验 Run 存在、tenant／workflow 归属和当前续跑上下文，并且只排除自身；任意构造的 ID 必须拒绝。
7. **固定 HTTP 契约**：同步和异步拒绝均返回非 2xx 与独立 `errorCode`。同步现有 `{"error"...}` 路径当前统一映射 400；新增字段或 JSON 形状不得使其落回 200。active 冲突与未知故障必须可区分。
8. **验证无副作用**：所有拒绝和并发失败路径都核对流程 Run 数、实验 Run 收尾、worker 数、状态和设备命令数；入口测试通过真实 API／服务动作驱动，不用 SQL `UPDATE` 冒充被测业务动作。
9. **回归与登记**：执行本卡测试、`./scripts/ci-gate.sh --fast`，以及 `docs/测试总纲` §4 对 workflow 改动要求的当前可用测试段；记录命令、退出码、日志和 SKIP。历史段号或已删除脚本不得直接照抄。
10. **独立评审与提交**：Core/Control 自评后由 QA/Integration 复核；提交信息写现象、根因、修法、实际验证和遗留 UNKNOWN。

## 5. 验收清单

“通过”必须同时满足预期结果和实际证据；未执行、环境缺失或 SKIP 均保持 UNKNOWN。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G01-T01 | 查询成功，无同流程 active Run | `ALLOW`；可进入正常新建流程 | 待执行 |
| G01-T02 | 同租户同 workflow 存在 running/paused/quarantined Run | `DENY_ACTIVE`；返回正确 active Run ID/状态 | 待执行 |
| G01-T03 | 仅有 draft/pending/终态 Run，或另一 workflow 有 active Run | 不误拦截；锁／约束不误伤其他 workflow | 待执行 |
| G01-T04 | 经归属校验的合法流程续跑排除自身；另有一个 active Run 的负向用例 | 只排除自身，不能排除其他冲突 Run；experiment_run ID 不可代替 | 待执行 |
| G01-T05 | `db=null`、tenant/workflow ID 非法 | `DENY_UNKNOWN + INVALID`；所有新建入口均无新流程 Run、worker 或命令 | 待执行 |
| G01-T06 | active 状态词表为空 | `DENY_UNKNOWN + STATUS-UNKNOWN`；不得返回 `ALLOW` | 待执行 |
| G01-T07 | DB 断开、连接超时、查询抛 `SQLException` | `DENY_UNKNOWN + UNAVAILABLE`；无 Run／worker／命令副作用；安全日志可追溯 | 待执行 |
| G01-T08 | 同步 Web 入口发生 active 冲突 | 保持 `EC-WORKFLOW-RUNNING` 和 active Run 字段兼容，响应为非 2xx | 待执行 |
| G01-T09 | 同步 Web 入口发生未知／DB 故障 | 返回稳定的门禁未知错误和非 2xx；不得误报 active 冲突或成功 | 待执行 |
| G01-T10 | 调度器已创建 experiment_run，以 `existingRunId>0` 首次派发 | 不因该 ID 跳过流程准入；与同步 Web 消费同一 outcome/reasonCode；无违规流程 Run | 待执行 |
| G01-T11 | 两个并发同步 Web 新运行请求竞争同一 tenant/workflow | 恰好一个准入；另一个 `DENY_ACTIVE`；无重复 active Run，无败者设备命令 | 待执行 |
| G01-T12 | 调度器首次派发与同步 Web 竞争同一 tenant/workflow | 恰好一个流程 Run 准入；另一方明确拒绝，无败者设备命令 | 待执行 |
| G01-T13 | 两个不同 workflow 并发新建 | 均可按各自规则准入，不被全局锁误阻断 | 待执行 |
| G01-T14 | 默认配置与显式 allow 开关分别运行，并叠加非法参数、空词表、DB 故障 | 默认禁止 active 并发；allow 只覆盖 `DENY_ACTIVE`；所有未知态仍 fail-closed | 待执行 |
| G01-T15 | 既有 WorkflowRunGate 与 workflow 回归集 | 无回归；新增测试已接入当前测试入口且退出码可信 | 待执行 |
| G01-T16 | 两个 `async=1` 同时启动同一 tenant/workflow | 恰好一个 `running`；败者无新 Run、无 worker、无设备命令 | 待执行 |
| G01-T17 | 实验预检已通过，真正 `createRun` 前另一请求使同流程变为 active | 当前步骤在插入边界被拒绝；不派发设备命令；按既有机制收尾实验 Run | 待执行 |
| G01-T18 | 同步与异步入口分别发生 active、invalid、status-unknown、DB unavailable | 均返回稳定且可区分的 `errorCode` 与非 2xx；JSON 形状变化不产生 200 | 待执行 |
| G01-T19 | 调度首次派发在准入处失败 | 既有 experiment_run 按当前契约明确失败收尾，不滞留 queued；无流程 Run、worker 或设备命令 | 待执行 |
| G01-T20 | 同步、异步、直接实验、调度首次派发的两两竞争矩阵 | 每一组合均恰好一个流程 Run 准入；不同 workflow 对照组不受误伤 | 待执行 |
| G01-T21 | 两个独立进程／实例、两个独立数据库连接同时争抢同一 workflow | 数据库级协调下最多一个准入；单 JVM 内存锁不能作为证据 | 待执行 |
| G01-T22 | excludeRunId 指向其他 tenant、其他 workflow、不存在或不属于当前续跑上下文的 Run | 全部拒绝且不排除任何 Run；合法自身归属对照用例按设计通过 | 待执行 |
| G01-T23 | 准入成功后事务回滚，随后另一请求竞争同一 workflow | 回滚请求不留 active Run；锁释放后另一请求可正常取得准入 | 待执行 |
| G01-T24 | 锁等待超时、死锁、连接中断、提交结果未知 | 均不得放行或产生新 Run／worker／设备命令；错误原因可诊断、可审计 | 待执行 |

### 发布前检查

- [ ] 不是只修改三个 `return Decision.allow()`；真正插入点的全入口原子准入有实现和并发证据。
- [ ] 数据库级协调机制、事务顺序和异常语义已记录；两个独立进程／连接的并发测试实际通过，未只依赖内存锁或单元模拟锁。
- [ ] 锁等待超时、死锁、连接中断、提交结果未知均 fail-closed；准入后回滚与锁释放已有实测。
- [ ] 续跑 Run 的存在性、tenant／workflow 归属和请求上下文均校验；跨 tenant、跨 workflow、不存在及无关 ID 负向用例通过。
- [ ] 同步、异步、直接实验、调度首次派发均纳入入口清单；没有只保护两个预检调用点。
- [ ] `existingRunId` 的实验 Run 语义与流程续跑语义已分开；调度首次派发不能绕过门禁。
- [ ] allow 开关只覆盖 active 冲突，非法参数、空词表和 DB 故障仍 fail-closed。
- [ ] active 冲突与未知故障可在同步／异步 API 和日志中区分，拒绝响应均为非 2xx。
- [ ] 异步竞争败者无新 Run、worker 和设备命令；调度失败无永久 queued 孤儿。
- [ ] 所有拒绝路径均无违规流程 Run、运行状态和设备命令副作用；既有实验审计记录按契约收尾。
- [ ] 新测试通过真实入口验证关键动作；SQL 仅用于明确夹具／清理，不代替被测状态转换。
- [ ] 测试输出包含稳定用例 ID、实际命令和退出码；SKIP 未写成 PASS。
- [ ] `./scripts/ci-gate.sh --fast` 及当前 workflow 映射测试已按实际环境执行并留存结果。
- [ ] 若增加数据库迁移，fresh DB、upgrade DB、drift、active 状态集合、显式 allow、冲突映射和回滚均已验证。
- [ ] 本卡已回填修复 commit、文件／方法签名、入口清单、测试结果和证据位置。
- [ ] 实现就绪度与证据就绪度分别更新；真实设备证据仍单独标记，未外推。

## 6. 实施证据回填

| 字段 | 待填写 |
|---|---|
| 修复基线 HEAD | 待填 |
| 修复 commit / PR | 待填 |
| 全量入口与插入点清单 | 待填 |
| 原子准入方案与理由 | 待填 |
| 涉及文件与方法签名 | 待填 |
| 测试命令、退出码和原始日志位置 | 待填 |
| 数据库迁移与回滚证据（如有） | 待填 |
| Core/Control 实施人 | 待填 |
| QA/Integration 独立验收人 | 待填 |
| 遗留问题与必须保持 UNKNOWN 的项 | 待填 |

## 7. 回滚与边界

回滚不得以恢复异常 `allow()` 作为止血手段。若修复导致正常运行不可用，应优先关闭受影响的新 Run 入口或回退完整原子准入变更，同时保留 fail-closed 行为和可审计错误。若含数据库约束／迁移，回滚顺序必须与数据兼容方案一起评审。

本卡完成只能证明应用与数据库层的同流程准入契约；没有真实设备、现场事件日志和物理资源观测时，不得宣称历史串扰已排除或物理闭环已验收。
