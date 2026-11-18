# G0-01 独立修复任务卡：WorkflowRunGate fail-closed 与原子准入

- 任务 ID：G0-01
- 优先级：P0；现有执行系统独立热修，不占 AI Workbench 工期
- 建议分支：`fix/g0-01-workflow-run-gate-fail-closed`
- 责任角色：Core/Control 实施；QA/Integration 独立验收
- 历史核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 本次静态复核代码树：`28a4b366e60bdd9b2861463a0d3ef20681558c75`
- 当前状态：待实施；构建、测试、CI、现场验证均为 UNKNOWN
- 交付原则：独立分支、独立提交、独立 PR；不得与 G0-07 或 AI 功能捆绑

> 本卡是实施与验收设计，不是修复完成记录。勾选任何项目都必须附实际提交和原始证据。

## 1. 问题与证据

历史基线和本次静态复核均可见：`WorkflowRunGate.check(DbConfig,long,long,Long)` 在以下路径返回 `Decision.allow()`：

1. `db == null`、`tenantId <= 0` 或 `workflowId <= 0`；
2. `activeStatuses()` 为空；
3. 查询抛出 `SQLException`。

这与 `ExperimentExecutor.run(...)` 调用处的 fail-closed 安全承诺冲突。门禁无法判断时放行，可能绕过同流程运行互斥；但历史事件日志未在本轮复核，是否曾发生串扰仍为 UNKNOWN。

本次只读复核还确认两个入口都消费 `Decision.allowed()`：

- `WorkflowWebService.run(long,String)`：Web 新运行入口；
- `ExperimentExecutor.run(...)`：实验/批次/定时入口，`existingRunId <= 0` 时检查新建运行。

### 增量状态记录

| 项 | 结论 |
|---|---|
| 旧结论 | 历史基线 FAIL：异常/未知路径 fail-open |
| 本次静态结论 | 上述代码路径仍可见；未做全仓或运行时审计 |
| 实现就绪度 | FAIL（仅针对本卡列出的门禁路径） |
| 证据就绪度 | UNKNOWN（本轮未执行任何测试） |
| 本轮是否执行 | 否；只读源码与文档 |

## 2. 修复目标与不变量

1. `ALLOW` 只能表示：查询成功且未发现应阻断的在途 Run，或显式并发开关按批准策略生效。
2. 已知存在同租户、同 workflow 的在途 Run 时，返回 `DENY_ACTIVE`，保留现有 `EC-WORKFLOW-RUNNING` 和 active Run 信息。
3. 参数非法、活动状态词表为空、数据库不可用/查询失败时，返回与 `DENY_ACTIVE` 可区分的 `DENY_UNKNOWN`；两个入口都不得创建或启动新 Run。
4. 建议决策结果至少包含 `outcome + reasonCode + runId/runNo/status + safeReason`；`allowed()` 只能在 `ALLOW` 时为 true。不能把“存在冲突”和“门禁无法判断”合并成同一原因。
5. 新 Run 的“检查—准入—创建”必须原子化。只把三个 `allow()` 改成拒绝，不能解决两个并发请求同时通过检查后再各自创建 Run 的竞态。
6. `excludeRunId` 只用于合法续跑/重跑同一 Run；不能成为绕过新 Run 准入的通用参数。
7. 默认配置继续禁止同流程并发。生产环境如启用 `LABOS_WORKFLOW_CONCURRENT_RUN=allow`，必须有独立变更批准和可审计记录；该开关不得把异常/未知路径静默变成允许。
8. 被拒绝的请求不得创建 `run`、推进 `markRunning`、产生设备命令或改变物料/位置事实。

### 建议错误语义

以下为设计建议；若项目已有统一错误码，应做明确映射并固定 API 契约：

| 场景 | 决策 | 建议错误码 |
|---|---|---|
| 已知同流程在途 Run | `DENY_ACTIVE` | `EC-WORKFLOW-RUNNING`（保持兼容） |
| 必填参数非法/缺失 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-INVALID` |
| active 状态词表为空或不可判定 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-STATUS-UNKNOWN` |
| DB 断开、超时或查询失败 | `DENY_UNKNOWN` | `EC-WORKFLOW-GATE-UNAVAILABLE` |

## 3. 范围

### 预计涉及

- `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowRunGate.java`
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java`（仅在统一决策映射/原子准入需要时）
- `labos-kernel/src/boot/java/com/labos/boot/web/service/WorkflowWebService.java`
- `labos-kernel/src/test/java/com/labos/kernel/workflow/WorkflowRunGateDbSelfTest.java`
- 新增入口契约/并发自测，以及必要的共享准入服务或数据库迁移
- `scripts/test-all.sh` 或当前测试映射：登记新增自测，不以“文件存在”代替执行
- 本任务卡末尾的实施证据与增量结论

### 明确不做

- 不重写调度器、设备事件系统、DeviceLock、PositionLock 或 RailZoneGuard。
- 不在本卡处理 G0-03 设备事件身份、G0-11 租户上下文或任何 AI Agent 功能。
- 不通过放宽门禁、吞异常、恢复异常 `allow()` 来“降低失败率”。
- 不用模拟器或数据库自测宣称真实设备现场安全已验证。

## 4. 实施步骤

1. **固定修复基线**：记录实际 HEAD、两个入口和 Run 创建路径；先运行现有门禁测试，保存修复前结果。
2. **先补失败用例**：覆盖非法参数、空词表、SQL 异常和两个入口的一致性；确认旧实现会失败，避免写出永远为绿的测试。
3. **建立三态决策**：区分 `ALLOW / DENY_ACTIVE / DENY_UNKNOWN`，让调用方按稳定 `reasonCode` 映射响应；记录安全日志，不泄露 SQL、连接信息或敏感数据。
4. **收口原子准入**：由 Core/Control 选择共享事务锁、数据库约束或等价机制，但必须覆盖两个入口的 check-and-create 全路径。若引入锁/唯一约束，说明锁粒度、事务边界、死锁/冲突映射、迁移与回滚。
5. **保持续跑语义**：新建和续跑分别测试；续跑只排除自身，不得排除其他 active Run。
6. **验证无副作用**：所有拒绝和并发失败路径都核对 Run 数、状态和设备命令数；入口测试通过真实 API/服务动作驱动，不用 SQL `UPDATE` 冒充被测业务动作。
7. **回归与登记**：执行本卡测试、`./scripts/ci-gate.sh --fast`，以及 `docs/测试总纲` §4 对 workflow 改动要求的当前可用测试段；记录命令、退出码、日志和 SKIP。历史段号或已删除脚本不得直接照抄。
8. **独立评审与提交**：Core/Control 自评后由 QA/Integration 复核；提交信息写现象、根因、修法、实际验证和遗留 UNKNOWN。

## 5. 验收清单

“通过”必须同时满足预期结果和实际证据；未执行、环境缺失或 SKIP 均保持 UNKNOWN。

| ID | 场景 | 预期结果 | 实际证据 |
|---|---|---|---|
| G01-T01 | 查询成功，无同流程 active Run | `ALLOW`；可进入正常新建流程 | 待执行 |
| G01-T02 | 同租户同 workflow 存在 running/paused/quarantined Run | `DENY_ACTIVE`；返回正确 active Run ID/状态 | 待执行 |
| G01-T03 | 仅有 draft/pending/终态 Run，或另一 workflow 有 active Run | 不误拦截；锁/约束不误伤其他 workflow | 待执行 |
| G01-T04 | 合法续跑并排除自身；另有一个 active Run 的负向用例 | 只排除自身，不能排除其他冲突 Run | 待执行 |
| G01-T05 | `db=null`、tenant/workflow ID 非法 | `DENY_UNKNOWN + INVALID`；两个入口均无新建/启动副作用 | 待执行 |
| G01-T06 | active 状态词表为空 | `DENY_UNKNOWN + STATUS-UNKNOWN`；不得返回 `ALLOW` | 待执行 |
| G01-T07 | DB 断开、连接超时、查询抛 `SQLException` | `DENY_UNKNOWN + UNAVAILABLE`；无 Run/命令副作用；安全日志可追溯 | 待执行 |
| G01-T08 | Web 入口发生 active 冲突 | 保持 `EC-WORKFLOW-RUNNING` 和 active Run 字段兼容 | 待执行 |
| G01-T09 | Web 入口发生未知/DB 故障 | 返回稳定的门禁未知错误；不得误报为 active 冲突或成功 | 待执行 |
| G01-T10 | ExperimentExecutor 对同一事实快照做判定 | 与 Web 消费同一 outcome/reasonCode；新 Run 不被创建或标记 running | 待执行 |
| G01-T11 | 两个并发 Web 新运行请求竞争同一 tenant/workflow | 恰好一个准入；另一个 `DENY_ACTIVE`；无重复 active Run，无败者设备命令 | 待执行 |
| G01-T12 | Web 与 ExperimentExecutor 并发竞争同一 tenant/workflow | 同样恰好一个准入，证明跨入口原子性 | 待执行 |
| G01-T13 | 两个不同 workflow 并发新建 | 均可按各自规则准入，不被全局锁误阻断 | 待执行 |
| G01-T14 | 默认配置与显式 allow 开关分别运行 | 默认 fail-closed；生产配置无未批准 allow；异常路径不被开关静默放行 | 待执行 |
| G01-T15 | 既有 WorkflowRunGate 与 workflow 回归集 | 无回归；新增测试已接入当前测试入口且退出码可信 | 待执行 |

### 发布前检查

- [ ] 不是只修改三个 `return Decision.allow()`；原子准入竞态有实现和并发证据。
- [ ] 两个入口共享同一决策语义，active 冲突与未知故障可在 API/日志中区分。
- [ ] 所有拒绝路径均无 Run、运行状态和设备命令副作用。
- [ ] 新测试通过真实入口验证关键动作；SQL 仅用于明确夹具/清理，不代替被测状态转换。
- [ ] 测试输出包含稳定用例 ID、实际命令和退出码；SKIP 未写成 PASS。
- [ ] `./scripts/ci-gate.sh --fast` 及当前 workflow 映射测试已按实际环境执行并留存结果。
- [ ] 若增加数据库迁移，fresh DB、upgrade DB、drift、冲突映射和回滚均已验证。
- [ ] 本卡已回填修复 commit、文件/方法签名、测试结果和证据位置。
- [ ] 实现就绪度与证据就绪度分别更新；真实设备证据仍单独标记，未外推。

## 6. 实施证据回填

| 字段 | 待填写 |
|---|---|
| 修复基线 HEAD | 待填 |
| 修复 commit / PR | 待填 |
| 原子准入方案与理由 | 待填 |
| 涉及文件与方法签名 | 待填 |
| 测试命令、退出码和原始日志位置 | 待填 |
| 数据库迁移与回滚证据（如有） | 待填 |
| Core/Control 实施人 | 待填 |
| QA/Integration 独立验收人 | 待填 |
| 遗留问题与必须保持 UNKNOWN 的项 | 待填 |

## 7. 回滚与边界

回滚不得以恢复异常 `allow()` 作为止血手段。若修复导致正常运行不可用，应优先关闭受影响的新 Run 入口或回退完整原子准入变更，同时保留 fail-closed 行为和可审计错误。若含数据库约束/迁移，回滚顺序必须与数据兼容方案一起评审。

本卡完成只能证明应用与数据库层的同流程准入契约；没有真实设备、现场事件日志和物理资源观测时，不得宣称历史串扰已排除或物理闭环已验收。
