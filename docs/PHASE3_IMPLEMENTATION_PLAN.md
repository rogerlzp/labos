# Phase 3 实施方案（PHASE3_IMPLEMENTATION_PLAN）

## 元信息

| 项 | 值 |
| --- | --- |
| 审计基线 | `origin/main` @ `c650e271`（2026-11-21 17:12） |
| 审计分支 | `docs/phase3a-audit-20261121` |
| 性质 | 只读审计的**收口产物**；定义 Phase 3 的分阶段交付，不含生产代码改动 |
| 部署目标形态 | **C 混合式**（中心 Kernel 管流程与命令状态；每工位 Sidecar 管本地连接/驱动/执行） |
| 配套文档 | `REAL_WORKFLOW_INVENTORY.md`、`DEVICE_INFRASTRUCTURE_AUDIT.md`、`DEVICE_EXECUTION_CONTRACT_V1.md` |
| 路径口径 | 相对仓库根目录；引用格式 `path:line` |
| 生成时间 | 2026-11-21 |

> 本文档的目标是把审计结论（[DEVICE_INFRASTRUCTURE_AUDIT.md](file:///Users/zhengpingli/work/中控/.worktrees/phase3a-audit/docs/DEVICE_INFRASTRUCTURE_AUDIT.md)）与契约建议（[DEVICE_EXECUTION_CONTRACT_V1.md](file:///Users/zhengpingli/work/中控/.worktrees/phase3a-audit/docs/DEVICE_EXECUTION_CONTRACT_V1.md)）转成**可执行、可验收、可停止**的分阶段路线。**不追求一次性大爆炸重构**。

---

## 0. TL;DR

1. 六阶段：**3A 审计（本轮，已完成）→ 3B 统一契约冻结 → 3C Device Runtime 核心 → 3D 混合式 Sidecar → 3E Simulator + 真实流程迁移 → 3F 长稳与灰度**。
2. 每阶段**独立分支、独立 PR、独立验收、显式停止条件**；任一阶段未过验收，不得开启下一阶段。
3. 首轮**不重写 TCP Client**：先冻结契约（对象/状态机/ID 语义），再按契约"逐层替换"。
4. **旧链路先兼容运行**：`labos-gateway`、`TcpPersistentDriver`、`.NET Sidecar` 在 3F 灰度前保持可用，不得为重构切断已验收流程。
5. 三条**不可回退红线**（继承现有硬约束）：中心侧 `command_ledger` 是唯一命令真相；`send()` 前必须已落 `SEND_STARTED`；在途命令**绝不自动重发**。
6. 进入 3B 前必须先裁决 **6 个未决问题**（§4）。

---

## 1. 总原则与边界

### 1.1 分层职责（继承契约文档 §1，硬约束）

| 层 | 职责 | 禁止 |
| --- | --- | --- |
| Workflow Runtime | 流程推进与持久化 | 理解 TCP 细节 |
| Device Execution Layer | 契约、Ledger、对账、能力模型 | 直接推进 Workflow 状态 |
| Device Gateway | TCP Client、协议适配、连接管理 | 解释业务动作 |
| Device Sidecar | 本地连接、驱动生命周期、能力注册、隔离、对账上报 | 成为"第二套 Workflow 状态库" |
| Real / Simulator | 执行物理动作 / 协议忠实模拟 | 绕过 Driver 使用独立业务逻辑 |

### 1.2 三条不可回退红线

1. **单一命令真相**：中心侧 `workflow_runtime.command_ledger`（`db/workflow_runtime/001_init.sql:423-450`）是命令状态的唯一权威；Sidecar 只缓存**通信态**。
2. **发送边界不变量**：`send()` 调用之前必须先持久化 `SEND_STARTED`（`workflow-runtime/.../command/DeviceCommandExecutionService.java:146-151`）。
3. **在途不重发**：网关对 `DELIVERY_UNCERTAIN` 命令**绝不自动重发**，只能经对账（`queryStatus`）或人工处置。

### 1.3 继承的既有约束

- 全局锁序 `Run → Token → Activation → Wait → Inbox`；`workflow_run` 行锁优先。
- 单实例由 OS 文件锁 `SingleInstanceHostLock` 保证（`workflow-runtime/.../host/SingleInstanceHostLock.java:22-85`），DB 不引入选举。
- 部署信任模型：单进程、隔离实验室 LAN、无 TLS（`.../host/EmbeddedWorkflowRuntimeHost.java:55-59`）。
- **审计/开发期不得**：改动 `labdb`、启动 `RuntimeHost`、向真实设备下发命令。

---

## 2. 六阶段路线

> 每个阶段统一给出：**目标 / 前置 / 交付物 / 代码边界 / 验收测试 / 停止条件**。

### Phase 3A — 架构与现状审计（本轮 · 已完成）

| 项 | 内容 |
| --- | --- |
| 目标 | 只读盘点真实流程与设备基础设施，冻结审计基线 |
| 交付物 | `REAL_WORKFLOW_INVENTORY.md`、`DEVICE_INFRASTRUCTURE_AUDIT.md`、`DEVICE_EXECUTION_CONTRACT_V1.md`、本文件 |
| 基线 | `origin/main @ c650e271`，只读 worktree `.worktrees/phase3a-audit` |
| 停止条件 | 三份审计文档合入 `main`（仅 `docs/`，零生产代码改动） |

**关键结论**（供后续阶段引用）：

- 设备连接实现 **4 套 Java + 1 套 .NET**（审计 §1）。
- 命令模型**同步请求-响应 vs 事件驱动异步**双轨并存（审计 §8-C3）。
- 设备在线状态**三源分散**：Redis `labos:dev:state` / Redis `labos:link` / 运行时 `HealthSnapshot`（审计 §8-C2）。
- 模拟器**不复用真实 Driver**，仅对齐有线帧（审计 §5）。
- 真实流程以 **code** 标识，非 run.id（清单 §0）。

---

### Phase 3B — 统一执行契约冻结

| 项 | 内容 |
| --- | --- |
| 目标 | 将契约文档从 **PROPOSED** 冻结为 **FROZEN v1**，收敛跨层对象与语义，形成编码基线 |
| 前置 | 完成 §4 六个未决问题的裁决 |
| 交付物 | `DEVICE_EXECUTION_CONTRACT_V1.md` 升版为 FROZEN；新增 `DEVICE_CONTRACT_GLOSSARY.md`（三 ID 与状态机逐条判据）；契约对象 Java 接口草案（`interface` + `record`，**不含实现**） |
| 代码边界 | 仅新增接口/类型定义包（建议 `workflow-runtime/.../device/contract/`）；**不接线、不替换现有实现** |
| 验收测试 | 契约对象与现有 12 态状态机、转移矩阵逐一对照通过（`WorkflowRuntimeRepository.java:4428-4437`）；`commandId/idempotencyKey/sourceMessageId` 三 ID 判据可执行（写单测断言不允许混用） |
| 停止条件 | 评审通过 FROZEN；任何后续阶段不得修改契约字段名/语义，只能走版本升版流程 |

**必须冻结的对象**（契约文档 §2）：`DeviceDescriptor`、`DeviceCapabilities`、`DeviceCommand`、`CommandExecution`、`DeviceEvent`、`DeviceState`、`CommandReceipt`、`ReconciliationResult`。

---

### Phase 3C — Device Runtime 核心重构

| 项 | 内容 |
| --- | --- |
| 目标 | 在**不改 Workflow Runtime 语义**的前提下，落地 Device Execution Layer：命令生命周期、连接管理、协议适配、状态对账 |
| 前置 | 3B 契约 FROZEN |
| 交付物 | `Command Reconciliation Service`（对账服务，新）; 统一 `Protocol Adapter` 抽象（`#!LABSEP!#` + NDJSON 两编解码收口到 `GatewayWireCodec`）; 连接生命周期与状态机统一; 命令状态机实现与契约一致 |
| 代码边界 | 主战场 `workflow-runtime/.../gateway/` 与 `.../command/`、`.../recovery/`；`labos-kernel/.../driver/` 保持可用（不删） |
| 验收测试 | ① 发送边界不变量：崩溃点注入下 `SEND_STARTED` 必先落库；② 对账 5 事实（未发送/已发送未确认/设备已执行/设备失败/无法确认）各一支用例；③ 12 态转移矩阵负例（非法转移被拒）；④ 幂等：同 `sourceMessageId` 重复投递不产生二次副作用 |
| 停止条件 | 上述 4 组测试在 CI 全绿；**旧连接器仍在**，无流程回归 |

**对账服务语义**（契约文档 §5，可直接复用）：内核 `CommandLedger.reconcileWithQuery(...)`（`labos-kernel/.../driver/CommandLedger.java:171-223`）+ `ReconcileSummary`（`:141-146`）；运行时 `WorkerExecutionRecoveryService`（`workflow-runtime/.../recovery/WorkerExecutionRecoveryService.java:20-204`）恢复顺序 **Inbox 完成 → Attempt 恢复 → Outbox 重排**。未解决副作用判定依赖 `worker_execution_fact.side_effect_key = "command:" + commandId`（`WorkflowRuntimeRepository.java:4454`）。

---

### Phase 3D — 混合式 Sidecar

| 项 | 内容 |
| --- | --- |
| 目标 | 落地"中心 Kernel + 每工位 Sidecar"的 C 形态，明确 Sidecar 与 Kernel 的命令/状态边界 |
| 前置 | §4-Q2（C1/C2 形态）与 §4-Q1（DeviceState 权威源）已裁决 |
| 交付物 | Sidecar 职责规范（连接管理、设备发现与注册、健康监控、本地命令管理、崩溃恢复、版本/配置管理）; 中心↔Sidecar 命令下发与回执契约（复用 `DeviceCommandAdapter` / `CommandReceipt`）; 设备状态上报与心跳规范 |
| 代码边界 | Sidecar 侧 `sidecar-dotnet/Labos.Sidecar/`（`TcpServer.cs:22`、`Protocol.cs`、`DeviceRegistry.cs:45`、`DeviceEventForwarder.cs`、`RedisLinkState.cs`、`Labos.Sidecar.Manager/DeviceSupervisor.cs`）；中心侧仅新增接入点，**不迁移业务状态** |
| 验收测试 | ① Sidecar 崩溃：命令状态由中心 `command_ledger` 对账恢复，Sidecar **不持有**业务终态；② Sidecar 断线重连：链路状态上报正确（`labos:link` 语义）；③ 中心不可达时 Sidecar 不擅自推进 Workflow；④ 版本/配置不一致时的拒绝与告警 |
| 停止条件 | 上述 4 组通过；Sidecar 可独立启动/停止，不影响中心流程推进正确性 |

> **边界红线**：Sidecar 可以缓存通信态，但**不得成为第二套 Workflow 状态库**（契约文档 §6）。

---

### Phase 3E — Simulator 重构 + 真实流程迁移

| 项 | 内容 |
| --- | --- |
| 目标 | 模拟器与真实设备**共用 Driver 契约**；迁移首批旧流程 |
| 前置 | 3C 契约落地、§4-Q6（模拟器复用 Driver 策略）已裁决 |
| 交付物 | 模拟器能力清单落地（协议忠实/命令状态机/可配延迟/网络故障/重复响应/乱序上报/命令卡死/崩溃恢复/物理约束）；旧流程迁移映射表（`__awaitEvent` → `receiveTask`+`WaitSpec`）；`1001017`/`1001041` 的**平行新版本**（旧版保留不覆盖） |
| 代码边界 | 模拟器：`labos-kernel/.../sim/`（`DeviceSimulator.java:41`、`DeviceCore.java:56`、`*Behavior.java`）；流程定义：新增 code，**不改旧 code** |
| 验收测试 | ① **同一 Driver** 连模拟器与连真实设备走**同一执行逻辑**（不允许两套分支）；② 故障注入矩阵（断网/丢包/重复响应/乱序/卡死/重启）逐项通过；③ 迁移后的新流程与旧流程**行为等价**（等待对象/生产者/correlation key/超时/重复事件/暂停恢复取消 6 类语义回归）；④ `correlationKey` 生成规则与事件桥接一致 |
| 停止条件 | 首批迁移流程在新引擎**端到端跑通提取工艺**（含 QC/异常分支按 §5 缺口补齐）；旧流程仍可回退运行 |

**迁移映射草案**（清单 §7）：`__awaitEvent(eventType=X,device=D)` → `receiveTask` + `WaitSpec(MESSAGE, correlationKey=f(D,X))`；带超时 → `WaitSpec(MESSAGE_WITH_TIMEOUT)` 走 `RouteKind.TIMEOUT`；`__sleep` → `WaitSpec(TIMER)`；`parallelGateway` → `SPLIT_PARALLEL/JOIN_PARALLEL`。

---

### Phase 3F — 长稳与灰度

| 项 | 内容 |
| --- | --- |
| 目标 | 系统级 Operational Readiness，证明可灰度 |
| 前置 | 3E 首批流程跑通 |
| 交付物 | `Runtime Operational Readiness 报告`；故障演练记录；灰度切换与回退手册 |
| 演练项 | ① 24–72h 持续运行；② 多 Run 并发；③ 设备断网；④ Sidecar 崩溃；⑤ Kernel 崩溃（SIGKILL）；⑥ PostgreSQL 短暂不可用；⑦ 命令对账与人工恢复流程 |
| 验收测试 | 每项演练**恢复后状态一致**（无丢失、无重复副作用、无静默错误）；`MANUAL_REQUIRED` 路径可被人工闭环 |
| 停止条件 | 全部演练通过 + 报告评审通过 → 方可安排生产灰度；期间旧链路**保持可用**直至灰度完成 |

---

## 3. 交付节奏

| 阶段 | 分支命名（建议） | PR 范围 | 生产代码 |
| --- | --- | --- | --- |
| 3A | `docs/phase3a-audit-20261121` | 仅 `docs/` | 零改动 |
| 3B | `feat/phase3b-device-contract` | 契约接口/类型 + 单测 | 新增，不接线 |
| 3C | `feat/phase3c-device-runtime` | `gateway/command/recovery` | 新增，旧实现保留 |
| 3D | `feat/phase3d-sidecar` | `sidecar-dotnet` + 中心接入点 | 新增 |
| 3E | `feat/phase3e-simulator-migration` | `sim/` + 新流程定义 | 新增，旧流程保留 |
| 3F | `feat/phase3f-hardening` | 无功能新增，仅修复/加固 | 修复 |

规约：**每阶段独立 PR、独立验收、验收前须提供远端 CI 成功证据**；任一阶段验收未过，不得进入下一阶段。

---

## 4. 进入 3B 前必须裁决的 6 个未决问题

| # | 问题 | 影响 | 建议倾向 |
| --- | --- | --- | --- |
| Q1 | **DeviceState 权威源**：Redis / `device_state` / 运行时内存，三选一或定优先级 | 3D 状态上报、3C 对账 | 中心侧 `command_ledger` 为命令真相；设备**在线态**建议以运行时统一模型为准，Redis 降级为缓存 |
| Q2 | **Sidecar 跨机形态**：C1（Kernel 内含 Gateway，Sidecar 仅本地接入）还是 C2（每工位常驻 Sidecar 下发命令） | 3D 架构、单实例约束写法定稿 | **C1 先行**（最小改动），验证后再演进 C2 |
| Q3 | **两套 `command_ledger` 收敛**：内核 `BIGINT` 版（`pg_rebuild/schema/99f_command_ledger.sql:9-37`）与运行时 `UUID` 版（`db/workflow_runtime/001_init.sql:423-450`）如何退役/对齐 | 3C 命令真相唯一性 | 运行时版为准，内核版随旧引擎退役 |
| Q4 | **`CommandReceipt` 是否新建统一抽象** | 3B/3D 回执契约 | 建议新建，收口 `InboxIngestionOutcome`/`CommandQueryResult` |
| Q5 | **协议版本机制**：是否引入 `protocol_version` 协商 | 3C 协议适配、3D 版本管理 | 建议引入（为多厂商扩展预留） |
| Q6 | **模拟器复用 Driver**：是否强制"模拟器走同一 `Protocol Adapter`" | 3E 验收核心 | 建议强制（否则协议漂移无法根治） |

> 另需澄清（不阻塞 3B，但影响 3E）：真实流程 `1001017`/`1001041` 对应的 **workflow.code** 尚未反查（清单 §8-1），需查库或用 `scripts/watch-run.py` 复核。

---

## 5. 工艺缺口（3E 前置，来自清单 §5）

3E 迁移前需补齐的真实步骤：**质量确认（QC 读板，`biotek-gen5` 未编排）、管路清洗/填充、样本预处理裂解**；以及**离心机（`aspin-2k`）接入生产流程**。

---

## 6. 风险登记

| 风险 | 来源 | 影响 | 缓解 |
| --- | --- | --- | --- |
| 双网关连接冲突 | 审计 §8-C4 | 同设备竞争连接 | 3D decide 单一连接源；灰度前确认 `labos-gateway` 是否仍启用 |
| 同步/异步命令模型双轨 | 审计 §8-C3 | 适配层复杂度 | 3C 明确适配边界，禁止双写 |
| 设备状态三源不一致 | 审计 §8-C2 | 对账误判 | Q1 裁决权威源 + 收敛 |
| 模拟器与真机分叉 | 审计 §8-C6 | 协议漂移、假绿 | 3E 强制共用 Driver/Protocol Adapter |
| 单实例本地文件锁 vs 跨机 Sidecar | 契约 §6 | 架构张力 | Q2 裁决；C1 先行规避 |
| `.NET Supervisor 依赖 Linux ps` | 审计 §10-4 | Windows 部署受限 | 3D 部署阶段核对 |

---

## 7. 完成定义（DoD）

Phase 3 整体完成（可灰度）需同时满足：

1. 契约 FROZEN 且被 3C/3D/3E 实现一致遵守（含三 ID 不混用、12 态转移矩阵）。
2. 真实设备与模拟器**共用同一 Driver/Protocol Adapter**，无第二套业务分支。
3. 首批真实工艺（含 QC/异常分支）在新引擎端到端跑通，旧流程可回退。
4. 命令**可追踪、可对账、可恢复**：对账 5 事实闭环，`MANUAL_REQUIRED` 路径可人工处理。
5. 3F 全部故障演练通过，产出 `Runtime Operational Readiness 报告`。
6. 旧链路在灰度完成前保持可用，未验收流程零回归。

---

## 8. 本轮（3A）交付清单

| 文件 | 状态 |
| --- | --- |
| `docs/REAL_WORKFLOW_INVENTORY.md` | 已交付 |
| `docs/DEVICE_INFRASTRUCTURE_AUDIT.md` | 已交付 |
| `docs/DEVICE_EXECUTION_CONTRACT_V1.md` | 已交付（PROPOSED） |
| `docs/PHASE3_IMPLEMENTATION_PLAN.md` | 已交付（本文件） |

**本轮无任何生产代码改动**。下一步：评审 §4 的 6 个裁决项，通过后开启 Phase 3B。
