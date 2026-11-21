# G0 实现核查矩阵

- 核查基线：`bef848e47131356494c3b20460fc77ce33232020`
- 分支：`docs/ai-native-labos-strategy-clean-20261118`
- 结论词义：PASS=源码和对应测试支持当前声明；PARTIAL=有实现但范围/证据不完整；FAIL=发现实现与契约不一致或明确缺陷；UNKNOWN=缺少可验证证据。
- 注意：本表的 PASS 也只针对表述的窄范围，不代表整模块生产验收。

| ID | 目标能力 | 当前源码/数据对象 | 测试/迁移证据 | 结论 | 主要差距 |
|---|---|---|---|---|---|
| M01 | 命令幂等与派发账本 | `CommandLedger.begin/finish`；`command_ledger`；`DriverStepExecutor` | CommandLedgerDbSelfTest；CommandMapper；command phase migration | PARTIAL | 单命令账本已具备；跨命令尝试、驱动 ACK/执行事件、审计强一致仍不足 |
| M02 | 命令重启对账 | `reconcileWithQuery` 逐条 QueryStatus；UNKNOWN 不重发 | CommandQueryReconcileDbSelfTest | PARTIAL | 测试使用 QueryStub/驱动抽象，不等于每类真实设备均支持 QueryStatus |
| M03 | 命令阶段观测 | PENDING/SENT/DONE/UNKNOWN；同步驱动 | 20261115_command_phase.sql；CommandLedgerDbSelfTest | PARTIAL | RECEIVED/EXECUTING 对实时同步调用不可达 |
| M04 | 设备事件入站 | DeviceEventController；device_event / run_cursor / consumption | 20261115_device_event.sql；DeviceEventApiSelfTest 等 | PARTIAL | 固定 tenant=1；全局共享 token；sourceEventId 可缺省 |
| M05 | Run/Node 状态机 | RunStateMachine、GraphRunner、StatusVocabulary、RunExecPhase | EngineSelfTest、StatusVocabularySelfTest、RunExecPhaseDbSelfTest 等 | PARTIAL | 需进一步把所有异常/暂停/恢复/未知态与真实设备证据逐路径对齐 |
| M06 | Experiment 状态投影 | ExperimentStateProjector 唯一写入口；BatchPlanner 聚合 | ExperimentStateProjectorSelfTest | PARTIAL | L-05 多事实聚合明确 DEFERRED |
| M07 | 物料执行确认与效果投影 | MaterialEffectService T1/T2、声明快照、effect/action | MaterialEffectServiceSelfTest、Material lifecycle tests | PARTIAL | Worker/隔离/对账范围需继续核对；不等同于所有物料类型完整 |
| M08 | 搬运与位置确认 | TransferTransaction、PositionLock、position_state、position_evidence | TransferConfirmWithPositionDbSelfTest、TransferCrashRecoveryDbSelfTest | PARTIAL | 有人工/扫码路径；设备自动确认受条件与开关约束，需真实设备证据 |
| M09 | 扫码证据防复用 | ScanEvidence、position_scan_evidence consumed 唯一性 | ScanEvidenceDbSelfTest、ScanEvidenceAuthorizeDbSelfTest | PARTIAL | 需要核对现场扫描设备身份与数据可信链 |
| M10 | 资源互锁与并发 | DeviceLock、PositionLock、RailZoneGuard、WorkflowRunGate | RailZoneConcurrencyDbSelfTest、WorkflowRunGateDbSelfTest | FAIL | WorkflowRunGate 参数异常/词表空/SQL 异常均 allow，与 fail-closed 承诺不符 |
| M11 | 调度恢复/重启 | ExperimentScheduler、StartupRecovery、ReconcilingGate | ReconcilingGateDbSelfTest、SchedulerRehydrateSelfTest | PARTIAL | 本轮未执行重启/并发/断线的完整组合验证 |
| M12 | RBAC | RbacRules、AuthService.Session、ApiAuthInterceptor | RbacRulesSelfTest、RbacRouteGuardSelfTest | PARTIAL | 全局角色级；缺少 project/experiment/dataset 级对象授权与 Agent 委托策略 |
| M13 | 审计 | AuthService.audit、AuditRecorder、audit_log | Audit-related API and RBAC tests | PARTIAL | 写入 best-effort；高风险动作与审计没有被证明为原子/可靠 Outbox |
| M14 | AI 流程规划 | AiPlanService、WorkflowController /ai/plan、前端 flowPlan/flowOps | verify-designer-ai-plan.py 等 | PARTIAL | AI 修改草案由前端应用；没有服务端 DSL Compiler + frozen ExecutionPlan |
| M15 | Capability 模型 | DeviceModel、DriverDescriptor、method params、RecipeCompiler | DeviceModel/ParamAndModelRegistration tests | PARTIAL | 设备/方法数据存在；尚无统一可版本化、可校验的 LabCapability API 契约 |
| M16 | 编译预览 | RecipeCompiler.compileAll；WorkflowService.compile | RecipeCompilerTest | FAIL | compileAll 的 capabilityByResource 重载参数未透传；测试没有覆盖该分支 |
| M17 | 数字孪生 | ShadowProbe/ShadowRunner、twin_diff_report | 20261116_twin_diff_report.sql | PARTIAL | 离线差分/局部影子执行不等于校准物理孪生 |
| M18 | 实验数据谱系 | Run events、material declaration/effect、position snapshot、result | 各 DB SelfTest / migration | PARTIAL | 尚未证明原始数据、样本、试剂批次、校准、设备命令、质控和分析版本全链关联 |
| M19 | Agent Runtime | 本轮目标路径中未发现完整 AgentRun/ToolCall/Approval Runtime | 当前 AI Plan 仅覆盖流程图规划 | UNKNOWN / GAP | 需独立建设；不应由 LLM 直接控制设备 |
| M20 | 真实设备闭环 | 真实设备驱动与事件接口存在 | 本轮无现场原始证据 | UNKNOWN | 必须现场验收，模拟器和 DB SelfTest 不能替代 |

## 关键源码定位

| 文件 | 核查定位 |
|---|---|
| `labos-kernel/src/main/java/com/labos/kernel/driver/CommandLedger.java` | begin 幂等账本约 36–80；finish/phase 约 105–145；QueryStatus 重启对账约 160–230 |
| `labos-kernel/src/main/java/com/labos/kernel/driver/DriverStepExecutor.java` | 命令派发前账本、幂等拒绝、SENT、物理执行和 finish 约 1145–1230 |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowRunGate.java` | allow fallback 与 SQL 异常 fail-open 约 90–150 |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentStateProjector.java` | 唯一写入者与 L-05 DEFERRED 约 10–31；投影方法约 110–176 |
| `labos-kernel/src/main/java/com/labos/kernel/material/MaterialEffectService.java` | T1/T2 和 fail-closed gate 约 25–125 |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/TransferTransaction.java` | REPORTED/CONFIRMED/UNKNOWN 及 confirmWithPosition 约 20–235 |
| `labos-kernel/src/boot/java/com/labos/boot/web/controller/DeviceEventController.java` | 固定租户、令牌与 sourceEventId 约 40–110；eventToken/匿名开关约 175–190 |
| `pg_rebuild/migration/20261115_device_event.sql` | sourceEventId 部分唯一索引、run cursor、消费约束 |
| `labos-kernel/src/main/java/com/labos/kernel/api/RbacRules.java` | 全局角色权限约 20–65 |
| `labos-kernel/src/boot/java/com/labos/boot/web/ApiAuthInterceptor.java` | 设备事件绕过 Session、用户角色和路由权限约 45–120 |
| `labos-kernel/src/main/java/com/labos/kernel/api/AuthService.java` | audit 写失败捕获并仅记录日志约 170–205 |
| `labos-kernel/src/boot/java/com/labos/boot/web/service/AiPlanService.java` | AI 职责边界约 15–40；LLM 请求与返回处理约 160–240 |
| `labos-kernel/src/boot/java/com/labos/boot/web/controller/WorkflowController.java` | AI Plan API 与前端业务校验边界约 330–385；TENANT 常量约 68–75 |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/RecipeCompiler.java` | compileAll 未透传 capabilityByResource 约 118–126 |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java` | compile preview 调用 compileAll 并传入 resource capability 约 520–524 |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java` | WorkflowRunGate 被调用并声明 fail-closed 约 168–184 |

## 核查基线与限制

- 源码核查基线：`bef848e47131356494c3b20460fc77ce33232020`。
- 全量 Git tree 文件清单：1,216 个 blob；关键路径抽样阅读，不代表全仓逐行审计。
- 本轮未执行测试；GitHub combined status 未返回 status checks。
- 本轮未做现场设备验收；M20 必须保持 UNKNOWN。
