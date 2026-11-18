# AI Workbench 05/06 实施修订附录

- 日期：2026-11-18
- 适用文档：[05 0–30 天实施拆解](05-AI-WORKBENCH-0-30D.md)、[06 第 1–7 天任务卡](06-AI-WORKBENCH-DAY1-7.md)
- 规范依据：[独立评审](09-AI-WORKBENCH-INDEPENDENT-REVIEW-20261118.md)、[安全与数据治理](07-SECURITY-DATA-GOVERNANCE.md)、[能力验证标准](08-CAPABILITY-VERIFICATION-STANDARD.md)
- 性质：对 05/06 具有约束力的补充条款；不是业务代码实现记录

> 原 05/06 保留作为方案主体。本附录用于避免在不完整重写长任务文档时丢失原有任务卡、接口和验收细节。发生冲突时，以本附录的安全准入和验收要求为准。

## A. 05 文档必须增加的实施约束

### A1. 真实数据准入分层

| 阶段 | 允许范围 | 必须满足 |
|---|---|---|
| 第 1–7 天 | 合成数据、隔离测试租户、假 handler | 无真实客户项目数据；worker 不依赖执行服务 |
| 第 8–30 天开发 | 合成/脱敏数据、内部验证 | 项目 ACL、审计、模型出域策略分别完成测试 |
| 真实项目元数据只读 | 项目、实验、模板与历史记录的受限查询 | 对象 ACL、租户边界、数据出域、审计通过；复核实际使用的 G0 读路径 |
| 真实 readiness / run review | 解释指定快照、复盘并回链证据 | 满足真实只读准入；设备事件身份、租户、事实聚合和证据关联按使用范围复核，不足时输出 UNKNOWN |
| 真实草稿生成 | 受限 Draft，不实例化、不启动 | R1/R4/R5/R6 门禁关闭；项目授权、字段白名单和模型路由可证明；不被无数据流关系的设备启动门阻断 |
| 确认并实例化 | Experiment/ExperimentRun/Placement 写入，不启动 | 草稿准入门仍有效；原子事务、唯一约束、可靠审计、模板版本和参数白名单通过 |
| 设备执行 | AI 不提供此能力 | 未来独立立项；关闭对应执行链风险并完成现场安全验证 |

“第 8–30 天”不是按日期自动开放真实数据的承诺。任何门禁未通过，继续使用合成数据。

### A2. Project ACL 贯穿全链路

05 §1、§2、§3、§4 中所有项目上下文和对象读取，统一补充：
- tenant_id 与 project_id 双重边界；
- 服务端从资源关系解析所属 Project，不信任客户端/模型给出的 projectId；
- Experiment、ExperimentRun、Run、AITask、AIArtifact、EvidenceRef、TwinDiff、Memory、附件、导出、缓存和搜索索引继承 Project ACL；
- Agent 工具调用按发起用户、项目成员、Capability、资源状态权限交集授权；
- 无 ACL 时只能在一个隔离合成项目/白名单中运行，不能宣称真实项目隔离已完成。

### A3. 草稿字段白名单

首期 Draft 可编辑字段限于：
- 实验名称/描述；
- 成功判据文本；
- 模板显式开放且具有类型、单位、上下限/枚举、版本和可修改性元数据的参数。

首期禁止：
- 设备绑定、设备方法和驱动参数；
- Workflow/Node 结构；
- 物料身份、物理位置和 placement；
- 联锁、安全策略、审批规则、权限；
- 任意脚本、SQL、Shell、HTML 或 URL 形式的执行指令。

服务端按模板版本验证每个 parameterPatch；未知 path、单位不匹配、超范围、模板变更均阻断并要求重新审阅。模型不得定义自己的参数边界。

### A4. 人工确认与实例化原子性

将原 05 §4.5 的服务端步骤补充为：
1. 校验调用者 Project ACL、角色、artifact 状态、模板版本、schema 版本和 expectedContentHash。
2. 数据库锁定 Artifact，检查消费映射。
3. 在一个事务中执行模板/参数复核、Experiment、ExperimentRun、Placement 创建、Artifact 消费映射和审计 outbox 写入。
4. 由唯一约束防止同一 Artifact 多次消费；事务提交后返回 ID。
5. 同一 Artifact 重复请求返回原 Experiment/Run ID；同 idempotency key 不同 input hash 返回 `409 IDEMPOTENCY_KEY_REUSED`。
6. 任一事务步骤失败全部回滚；客户端超时后重试必须能查回原 ID。
7. 不调用 start，不创建设备命令，不改变 readiness。

如果现有 AuditRecorder 无法与业务事务同库原子提交，应采用 transactional outbox 或等价可恢复机制，并测试审计投递失败、重复投递和恢复。

### A5. 估工修正

05 §0.4 四阶段估工分别为 7–9、12–15、6–8、10–12 人日，合计为 **35–44 人日**。正文已同步修正，并注明估算不含安全治理、Project ACL 和审计补强可能产生的额外工作；需由研发重新估工，不视为承诺。

## B. 06 第 1–7 天任务卡必须增加的验收

### B1. T1 Schema / Migration

- Task 幂等唯一约束绑定 `tenant + project + requested_by + idempotency_key`（按最终数据模型调整，但必须明确作用域）。
- 保存规范化 `input_hash`；同 key 同 hash 返回原 Task；同 key 不同 hash 返回 409。
- Artifact 的 consumption 字段成组约束；同一 Artifact 只能消费一次。
- 外键/唯一约束防止 artifact 消费映射指向不匹配的 Project/Tenant。
- drift 检查覆盖新增约束、索引和外键，不只检查表/列存在。

### B2. T2 Worker fencing

- 可以使用独立、单调且不可伪造的 fencing token；在满足“attempt 单调递增、同一 attempt 不更换执行体、所有写路径都在数据库条件更新/事务锁中校验 attempt + leaseOwner + RUNNING”时，attempt 本身就是 fencing 代数，不强制增加重复字段。
- Worker 写 artifact、完成/失败任务和续租时，必须校验 taskId + tenantId + projectId + leaseOwner + fencing 代数 + RUNNING。
- cancel、lease 过期或 attempt 增加后，旧 Worker 的迟到结果必须被拒绝。
- 不能只在应用内先读状态再写；最终更新必须在数据库条件更新/事务锁中完成。

### B3. T3 权限与审计

T3 已明确：首周“角色 + 租户/项目条件”只用于合成数据测试，禁止作为真实项目数据隔离完成的安全声明；真实数据准入前仍须完成 Project Member ACL。

新增负向测试：
- 同一 tenant 下 Project A 成员读取 Project B task/artifact/run/evidence/memory 返回拒绝；
- 列表、搜索、证据深链、附件、导出和缓存同样拒绝；
- 撤销成员后，旧会话和运行中 Agent 下一次工具调用拒绝；
- 项目未批准外部模型或输入含 D2/D3 字段时，任务创建前被拒绝；
- 审计存储不可用时，confirm-instantiate 回滚或进入可恢复待处理状态，不允许无审计成功。

### B4. 第 7 天出口证据

进入第 8–15 天页面开发前，必须留存：
- migration fresh DB / upgrade DB / drift 测试原始结果；
- 同 key 同 hash、同 key 不同 hash 的并发测试；
- worker cancel/lease-expired/旧 attempt 迟到写入测试；
- 跨租户和同租户跨 Project 权限矩阵；
- 审计失败注入和恢复记录；
- 模型供应商/数据等级路由拒绝测试；
- worker 无执行/设备服务依赖的静态检查；
- 明确标注合成数据范围与尚未准入的真实数据范围。

未执行或未保存证据的项目状态为 UNKNOWN，不得写 PASS。

## C. 与 G0 的依赖关系

G0 源码核查报告的基线是固定历史 commit。真实数据/写能力启用前，须：
1. 记录目标分支当前 HEAD；
2. 按准入矩阵确定当前能力实际使用的数据和执行路径，对 G0 基线之后涉及 WorkflowRunGate、ExperimentStateProjector、设备事件租户/身份、AI Plan、审计和 RBAC 的相关改动做增量复核；
3. 对历史 P1 风险逐项给出当前状态 PASS/PARTIAL/FAIL/UNKNOWN，并标明它与当前能力的关系；
4. fail-open 阻断其对应的真实启动/执行链；固定 tenant/共享设备身份、事实聚合缺陷阻断依赖这些事实的真实 readiness/run review；关键审计和对象授权风险阻断对应真实数据或写链路。不能用某个执行风险全局阻断无数据流关系的草稿生成，也不能用草稿评审通过替代执行整改；
5. 把复核 commit SHA、源码路径、测试原始结果写入新的 G0 增量报告。

不能把“文档写了整改计划”视为源码已整改。
