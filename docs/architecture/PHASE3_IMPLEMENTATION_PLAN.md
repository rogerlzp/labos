# PHASE3_IMPLEMENTATION_PLAN — Device Runtime 分阶段重构计划

- 基线：`origin/main` @ `c650e271`。
- 总原则：**围绕统一的 Device Execution Contract 做整体架构重构，采用混合式部署（中心 Kernel 管流程与命令状态，工位 Sidecar 管本地连接/驱动/执行）**；每阶段独立分支、独立 PR、独立验收；不做一次性大爆改。
- 当前状态：**Phase 3A 审计已完成，合同为 DRAFT，尚未冻结。** 未创建分支、未提交、未改生产代码、未连 labdb、未启动 RuntimeHost、未向设备下发命令。

---

## 0. 阶段总览

| 阶段 | 目标 | 交付 | 停止条件 |
|---|---|---|---|
| 3A | 架构与现状审计 | 4 份审计/合同文档（本次） | 流程 ID 来源与 Drill 材料缺失，需用户补证 |
| 3B | 统一执行合同冻结 | 冻结版合同 + 对象/状态机/不变量测试 | §9 开放问题全部关闭 |
| 3C | Device Runtime 核心 | 唯一命令账本、连接管理、协议适配、状态对账 | G4-C 类故障注入全通过 |
| 3D | 混合式 Sidecar | 注册/心跳/重连/配置版本、无业务真相 | Sidecar 崩溃不产生重复物理副作用 |
| 3E | Simulator 与真实流程迁移 | 同一 Driver 接真机与模拟器；首批流程迁移 | 现场库导出 + 双轨授权到位 |
| 3F | 系统级验收 | Operational Readiness 报告 | 长稳、并发、故障、对账全通过 |

---

## 1. Phase 3A — 架构与现状审计（✅ 本次完成）

### 1.1 已交付

| 文档 | 内容 |
|---|---|
| `REAL_WORKFLOW_INVENTORY.md` | 流程定义存储、6 个 ID 定位、`__awaitEvent` 全量语义、Runtime 原语矩阵、真实提取工艺现状、迁移问题清单 |
| `DEVICE_INFRASTRUCTURE_AUDIT.md` | 8 类 TCP 客户端、Driver 划分、真实调用链、两套 `command_ledger`、重连/恢复、Simulator 复用关系、协议版本与状态模型缺口、重写/保留建议 |
| `DEVICE_EXECUTION_CONTRACT_V1.md` | 8 个合同对象的现状与建议定义、状态机、10 条不变量、兼容策略、9 个开放问题 |
| `PHASE3_IMPLEMENTATION_PLAN.md` | 本计划 |

### 1.2 关键审计结论

1. 三套独立设备执行栈并存，连接与命令状态三处重复。
2. 两张同名 `command_ledger` 表 schema 不兼容（`99f_command_ledger.sql:9` vs `001_init.sql:423`）。
3. 6 个流程 ID 中 4 个全库 0 命中，另 2 个只是 UI 测试的 run 号；不能当作真实工艺流程直接迁移。
4. `Drill 6 / Drill 7` 在快照中 0 命中。
5. runtime 的"先落 SEND_STARTED 再发、绝不重发、inbox 幂等、fail-closed UNKNOWN"闭环是全仓最完整的物理副作用安全边界，应作为主干保留。
6. Simulator 与真实 Driver 只共享帧格式，不共享代码接口。
7. 协议无版本管理，设备状态有 7 套口径。

### 1.3 本阶段遗留（阻断 3B 冻结）

- 需要从现场库导出 6 个 run 的定义与执行轨迹（清单见 `REAL_WORKFLOW_INVENTORY.md` §3.1）。
- 需要提供 `Drill 6 / Drill 7` 原始材料，或确认由 G4/G5 测试替代。
- 需要确认 `设备命令与事件关联规范` 的"未实施"状态及是否列为前置改造。
- 需要确认工位粒度（一台设备 vs 一个工位多设备）一个 Sidecar。

---

## 2. Phase 3B — 统一执行合同冻结

### 2.1 目标

把 `DEVICE_EXECUTION_CONTRACT_V1.md` 从 DRAFT 变为 FROZEN，并让合同成为可测试资产。

### 2.2 工作包

1. 关闭 §9 的 9 个开放问题，逐条记录裁决依据。
2. 确定唯一 `command_ledger` schema 及 legacy 表处置（迁移 / 冻结只读）。
3. 确定 ID 空间：`commandId / idempotencyKey / sourceMessageId / dispatchId / activationId / runId` 的职责与生成规则。
4. 确定 `DeviceCapabilities` 的初始能力集与幂等/可查询分类。
5. 确定统一 `DeviceState` 三种正交状态（connection / operational / physical）。
6. 把 10 条不变量写成契约测试（先红后绿，不依赖设备）。

### 2.3 代码边界

- 允许：新增合同定义、接口、契约测试、文档。
- 禁止：修改现有 driver 实现、改 DB schema 生产数据、改 Workflow Runtime 调度语义。

### 2.4 验收

- 合同文档有冻结版本号与哈希。
- 10 条不变量各有至少一个可执行测试或明确标注"由 Phase 3C/3D 测试覆盖"。
- 审计中三条红线（发送≠执行、连接恢复≠状态恢复、Sidecar≠业务真相）在文档中被明确为验收判据。

### 2.5 停止条件

任一开放问题无裁决 → 不进入 3C；不允许"先写代码再补合同"。

---

## 3. Phase 3C — Device Runtime 核心

### 3.1 目标

建立**独立于 Workflow Runtime** 的设备执行层：唯一命令账本、连接管理、协议适配、状态对账。

### 3.2 工作包

1. 以 `DeviceCommandExecutionService` + `WorkflowRuntimeRepository` 命令段为起点，抽出 Device Execution Layer（先适配，后收编）。
2. 统一 `command_ledger` 写入口；legacy 直连路径改为写统一账本或冻结只读。
3. 以 `TcpDeviceConnection` 为唯一 TCP 主干，移除/降级 `TcpDriverAdapter`、`TcpPersistentDriver`、`labos-gateway.DriverConnection` 的重复连接逻辑。
4. Protocol Adapter 增加 `protocolVersion` 与能力协商；统一分隔符/帧解析，消除 6 处硬编码。
5. 实现 `Command Reconciliation Service`：`CONFIRMED_COMPLETED / CONFIRMED_FAILED / STILL_RUNNING / NOT_FOUND / UNKNOWN / MANUAL_REQUIRED`，继承 `NOT_FOUND ≠ 未执行` 语义。
6. 把 `DeviceCommandAdapter` / `LabSepWireCodec` / `NdjsonWireCodec` 收为薄适配层。

### 3.3 代码边界

- 主干：`workflow-runtime/src/main/java/.../command/`、`.../gateway/`、`.../persistence/` 命令相关段。
- 收编：`labos-kernel/.../driver/tcp/`、`labos-gateway/`（先禁用生产生产者再删除）。
- 禁止：改 `GraphCoordinator` / `WorkerPool` / Wait 语义；改 `90_all_in_one.sql` 历史数据。

### 3.4 验收

复用并扩展现有测试资产：

| 测试 | 覆盖 |
|---|---|
| `command/DeviceCommandFaultInjectionIntegrationTest.java:32-40` | SEND_STARTED 前后断连、丢失/重复/迟到 receipt、UNKNOWN 对账 |
| `persistence/DatabaseTransactionCrashConsistencyIntegrationTest.java:37-62` | ABORT_BEFORE_COMMIT / LOST_RESULT_AFTER_COMMIT |
| `host/RuntimeProcessRestartRecoveryIntegrationTest.java:42-45` | 跨进程重启恢复、无重复物理副作用 |
| `gateway/DeviceGatewayTcpIntegrationTest.java`、`LabSepGatewayIntegrationTest.java` | 设备通信协议 |
| `acceptance/G5AcceptanceContractTest.java` | 13/13 PASS 不回归 |

新增断言：`commandId` 越过 SEND_STARTED 后重发次数 = 0；`NOT_FOUND` 不产生自动成功/失败判定。

### 3.5 停止条件

任一"物理命令被重复发送"或"不确定结果被判成功"的测试失败 → 立即停止，不进入 3D。

---

## 4. Phase 3D — 混合式 Sidecar

### 4.1 目标

工位 Sidecar 只管理本地设备：连接、驱动生命周期、能力注册、健康、重连、事件上报、配置版本；**中心持有命令与流程真相**。

### 4.2 工作包

1. 合并两个 sidecar 目录：`sidecar-dotnet/` 为目标；`labos-kernel/sidecar-dotnet/` 的 `IdempotentCache` 移除或持久化。
2. 实现真正的 supervisor：崩溃自动重启 + 健康探针 + 状态对账（替代 `DeviceSupervisor.cs:278-322, 357-413` 的 ps 扫进程 + 手动重拉）。
3. 设备发现与能力注册：上报 `DeviceDescriptor` / `DeviceCapabilities`，中心维护注册表。
4. 心跳与在线状态：`labos:link:*` 保留为调度参考，不作为执行依据。
5. 配置与版本管理：型号画像、协议版本、驱动版本下发与校验。
6. 设备事件路径统一：自发事件必须回填 `commandId`，否则进 UNATTRIBUTED；`DeviceEventOutbox` 保持至少一次 + 幂等入站。

### 4.3 代码边界

- 允许：`sidecar-dotnet/Labos.Sidecar/**`、`Labos.Sidecar.Manager/**`、中心侧注册/心跳接口。
- 禁止：Sidecar 内新建命令账本或 Workflow 状态表；Sidecar 决定流程推进。

### 4.4 验收

- Sidecar 进程被 SIGKILL 后自动重启并重新注册；中心账本状态不变。
- Sidecar 本地 outbox/缓存全部丢失后，中心账本仍能正确对账。
- 重复/乱序设备事件不产生重复状态推进。
- 无 `commandId` 的自发事件不会导致流程错误推进。

### 4.5 停止条件

Sidecar 出现任何"本地状态覆盖中心账本"的路径 → 停止。

---

## 5. Phase 3E — Simulator 与真实流程迁移

### 5.1 目标

模拟器和真实设备共用同一 Driver 接口；逐条迁移真实流程（首批候选 `1001017`、`1001041`）。

### 5.2 前置条件（缺一不可）

1. 现场库导出 6 个 run 的定义与执行轨迹（`REAL_WORKFLOW_INVENTORY.md` §3.1 六项数据）。
2. 判定它们是真实生产工艺还是验收 run，并取得切换授权。
3. `设备命令与事件关联规范` 的"未实施"状态关闭，或证明目标流程不依赖它。
4. 目标流程不依赖当前不支持的 BPMN 原语（Compensation / Error / Boundary / Retry / SubProcess），或 Runtime 已补齐并验收。
5. `verify-extraction-plate-chain.py --strict` 与 RailZone 登记通过。

### 5.3 工作包

1. `DeviceCore` 与 `pylabrobot-sim` 实现统一 Driver 接口（先协议一致，后状态机一致）。
2. 模拟器支持故障注入：Protocol Fidelity、Command State Machine、Latency、Network Fault、Duplicate Response、Out-of-order、Stuck Command、Crash Recovery、Physical Constraints。
3. 迁移第一批流程；旧版本保留，不覆盖；双轨运行与结果对比。
4. 补齐 `__awaitEvent` → `receiveTask + labos:wait` 的映射（含静态 correlationKey 与命令回显）。
5. 完整跑通提取工艺并验证异常与恢复。

### 5.4 验收

- 同一 Driver 分别连接模拟器与真机，命令语义与状态推进一致。
- 迁移流程在双轨下运行，结果与旧引擎可对比且无新增物理副作用。
- 异常路径（断网、超时、人工干预、暂停恢复）均有可观测证据。

### 5.5 停止条件

授权未到位或现场数据未导出 → 不得迁移；旧流程不得被覆盖。

---

## 6. Phase 3F — 长稳与灰度

### 6.1 验收矩阵

| 场景 | 判据 |
|---|---|
| 24–72 小时持续运行 | 无卡死 inbox/outbox/drain，无内存/连接泄漏 |
| 多 Run 并发 | 无串扰；`wait_active_correlation_uq`、split/join 约束不回归 |
| 设备断网 | 只影响传输，不产生命令重发或误判 |
| Sidecar 崩溃 | 自动重启 + 重新注册；中心账本不变 |
| Kernel 崩溃 | 复用 `RuntimeProcessRestartRecoveryIntegrationTest`；无重复物理副作用 |
| PostgreSQL 短暂不可用 | fail-closed；恢复后由 outbox/inbox 收敛 |
| 命令对账与人工恢复 | `NOT_FOUND / UNKNOWN / MANUAL_REQUIRED` 全路径可观测、可审计 |
| 重复/乱序/迟到事件 | Inbox 幂等 + protocol_violation 留痕 |
| 性能与容量 | 设备数、并发 Run 数、心跳频率的上限有实测数据 |

### 6.2 交付

`Runtime Operational Readiness` 报告：场景、命令、证据、通过/不通过、已知限制、回滚方案。

### 6.3 停止条件

任一"重复物理副作用"或"未授权切生产流量"发生 → 全面回滚到旧链路。

---

## 7. 分支 / PR / 提交纪律

1. 每阶段一个分支：`phase3b-execution-contract`、`phase3c-device-runtime`、`phase3d-sidecar`、`phase3e-migration`、`phase3f-readiness`。
2. 每阶段一个 PR，PR 描述必须引用对应合同条款与验收证据。
3. **当前工作区有大量未提交/删除文件（他人并行改动）**。本次审计只新增 4 份文档，**不创建分支、不提交、不 stash、不 checkout**，避免卷入他人改动。分支从 `origin/main` 新开，不带工作区脏状态。
4. 提交前必须重新 `git fetch` 并确认 `origin/main` 新基线；审计结论若因主线更新失效，先更新审计再动代码。
5. 不擅自修改 `labdb`、crontab、启动配置或生产默认值。

---

## 8. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| 现场库数据缺失 | 无法判定真实流程，迁移无依据 | 3E 前置条件，缺则停工 |
| 两套 `command_ledger` 合并 | 历史数据/ID 冲突 | 3B 裁决 + legacy 只读冻结 |
| 三套设备栈同时在线 | 重复下发、状态打架 | 3C 先禁用非主干生产者，再删代码 |
| 协议版本缺失 | 帧变更影响多语言实现 | 3C 引入版本字段与能力协商 |
| 模拟器不同接口 | 测试结论无法迁移到真机 | 3E 强制同一 Driver 接口 |
| Sidecar 成为第二真相 | 恢复时覆盖中心状态 | 3D 禁止 Sidecar 写命令账本 |
| 多工位扩展 | 单实例锁限制 | 3D 注册模型预留；多实例前换分布式租约 |
| 工作区并发改动 | 提交归属混乱 | 从 `origin/main` 新分支，显式列文件提交 |
