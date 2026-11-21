# DEVICE_EXECUTION_CONTRACT_V1 — 统一设备执行合同（建议稿，待冻结）

- 基线：`origin/main` @ `c650e271`；事实依据见 `DEVICE_INFRASTRUCTURE_AUDIT.md`、`REAL_WORKFLOW_INVENTORY.md`。
- **状态：建议稿（DRAFT）。尚未冻结。** 冻结前必须关闭本文 §9 的开放问题。
- 部署模式：采用用户确认的 **C 混合式**——中心 Kernel 管理流程与命令状态，每个设备工位 Sidecar 管理本地连接、协议与执行。

---

## 1. 目标与非目标

### 1.1 目标

1. 让工艺定义与设备协议解耦：Workflow 只说 `Transfer`，不说 TCP 帧。
2. 让真实设备与模拟器共用同一套 Driver 接口与状态机。
3. 让每条设备命令**可追踪、可对账、可恢复**，且物理副作用绝不因重启/重连被盲目重复。
4. 建立唯一的命令账本与唯一的 ID 语义，消除现有两套 `command_ledger` 的并存。

### 1.2 非目标

- 不改变 `workflow-runtime` 的图推进语义（GraphCoordinator / WorkerPool / Wait）。
- 不接管旧 `GraphRunner` 的生产流量（G1 边界）。
- 不在本合同中定义具体厂商协议帧（由 Protocol Adapter 承担）。
- 不把 Sidecar 变成第二套 Workflow 状态数据库。

---

## 2. 分层与责任边界

| 层 | 核心职责 | 禁止事项 |
|---|---|---|
| Workflow Designer / API | BPMN、定义、Signal、监控 | 生成 TCP 帧 |
| Workflow Runtime | GraphPlan、Dispatch、Wait/Timer、Recovery、流程状态持久化 | 理解 TCP、协议、设备连接 |
| **Device Execution Layer** | Device Task Contract、`command_ledger`、State Reconciliation、Capability Model | 决定 Workflow 是否推进 |
| **Device Gateway** | TCP Client、Protocol Adapter、Connection Manager | 解释业务动作、写 Workflow DB |
| **Device Sidecar** | 本地连接/驱动生命周期、能力注册、健康、重连、事件上报、本地缓存 | 作为命令最终持久化与业务状态真相 |
| Real Devices / Simulator | 执行物理/仿真动作 | 使用独立业务逻辑绕过 Driver |

**三条不可逾越的红线（来自审计事实）：**

1. **TCP 发送成功 ≠ 设备执行成功。** 越过 `SEND_STARTED` 的命令绝不盲目重发（`DeviceCommandExecutionService.java:20-27, 317-354`）。
2. **TCP 连接恢复 ≠ 设备状态恢复。** 重连只恢复传输活力，无命令重发缓冲（`TcpDeviceConnection.java:49-50`）。
3. **Sidecar 可以缓存通信状态，但不能成为另一套 Workflow 状态数据库。** 命令真相在中心账本。

---

## 3. 合同对象总表（现状 → 建议）

用户列出的 8 个对象中，**当前代码只存在 1 个同名类型**（`DeviceEvent`），其余 7 个缺失但有语义相近替代品。V1 应在既有类型上做命名对齐/适配，而不是新建一套平行类型（否则会出现第三套命令模型）。

| 对象 | 现状 | 语义相近的现有实现 |
|---|---|---|
| `DeviceDescriptor` | 缺失 | `DriverDescriptor.java:15-23`、`DeviceModel.java:38`、`sim/DeviceProfile.java:24` |
| `DeviceCapabilities` | 缺失（无独立类型） | `DriverDescriptor.supports()/capabilitiesOf():49-55`；`Set<String> capabilities` |
| `DeviceCommand` | 缺失（无该类名） | `DriverCommand.java:14-20`；`DeviceCommandAdapter.DeviceCommandEnvelope:16-25` |
| `CommandExecution` | 缺失 | `DeviceCommandExecutionService.java:29`；`CommandLedger.Entry:36`；`WorkflowRuntimeRepository.CommandRecord` |
| `DeviceEvent` | **存在** | `labos-kernel/.../event/DeviceEvent.java:19-22`（`record(id, deviceCode, eventType, payload)`）；表 `device_event` |
| `DeviceState` | 缺失（无 Java 类型） | DB `device_state`（`90_all_in_one.sql:1277-1288`）、`DeviceStateMapper`、`DeviceBusinessStateResolver` |
| `CommandReceipt` | 缺失 | `CommandQueryResult.java:10-18`；`DeviceCommandAdapter.SendResult:53-58`；表 `inbox_receipt` |
| `ReconciliationResult` | 缺失 | `CommandLedger.ReconcileSummary:141-146`；`StartupRecovery.Quarantined:125`；`WorkerExecutionRecoveryService.StartupResult:43-53` |

---

## 4. 对象定义（建议）

以下为**合同语义**，不是最终 Java/C# 签名。实际签名需在审查 Kernel、DeviceCore、现有 driver 合同后确定。

### 4.1 `DeviceDescriptor`

设备身份与静态属性；**不含运行时状态**。

```
deviceId         : string        // 全局唯一，工位内稳定
deviceCode       : string        // 兼容旧系统的设备码
modelId          : string        // 关联型号画像（models.json / DeviceModels）
location         : { lab, station, slot }
protocolId       : string        // 指向 Protocol Adapter 注册项
protocolVersion  : string        // 必填；当前系统完全缺失
capabilitiesRef  : string
endpoint         : { host, port, transport }   // TCP / serial / dll / lhc / gen5
metadata         : map<string,string>
```

来源：`DriverDescriptor:15-23` + `models.json:5-12, 37-45, 80-86`。

### 4.2 `DeviceCapabilities`

能力清单 + 每能力的参数约束与幂等策略。**这是 Workflow 与设备之间唯一的词汇表。**

```
capability   : string    // Home | Move | Transfer | Mix | Centrifuge | Bind | Wash | GetStatus | ...
version      : string
idempotent   : boolean   // 映射到现有 idempotency_class: IDEMPOTENT/QUERYABLE/NON_IDEMPOTENT
queryable    : boolean   // 是否支持 QueryStatus 用于 UNKNOWN 对账
parameters   : schema
timeoutHint  : duration
```

关键约束：
- `queryable=false` 且 `idempotent=false` 的能力，其 UNKNOWN 命令**只能进入 MANUAL_REQUIRED**，不能自动决策。
- 现有 `idempotency_class` 分类（`001_init.sql:434`）应升级为能力属性，而不是命令上的临时标记。

### 4.3 `DeviceCommand`

**字段必须分开，不能把 `commandId`、`idempotencyKey`、`sourceMessageId` 混成一个概念。** 这是当前系统的真实缺陷：`DriverCommand.java:10-12` 说 `commandId` 是幂等键，`docs/设备命令与事件关联规范-commandId回显-20261119.md:21-26` 说它是事件归属唯一键，runtime 中它又是 UUID 主键。

```
commandId        : uuid       // 账本主键；一次物理意图的唯一标识
idempotencyKey   : string     // 调用方去重键；与 commandId 正交
sourceMessageId  : string     // 回包/事件的 inbox 去重键（与 sourceEventId 统一术语）
runId            : uuid       // 中心流程归属；Device Runtime 独立部署时需替代语义
activationId     : uuid
attemptId        : uuid
dispatchId       : uuid       // 每次发送新建，不等于 commandId
deviceId         : string
operation        : string     // 来自 DeviceCapabilities，不是 TCP 指令
parameters       : json
deadline         : instant
idempotencyClass : enum(IDEMPOTENT | QUERYABLE | NON_IDEMPOTENT)
payloadHash      : bytes[32]  // 冲突检测，沿用 001_init.sql:442
```

持久化归属：**中心 `command_ledger`**（建议统一到 `workflow_runtime` 版 schema）。

### 4.4 `CommandExecution`

命令的执行生命周期，即账本行本身；由中心服务推进。

```
commandId        : uuid
state            : enum(§5.1)
dispatchAttempts : int
firstSentAt      : instant
lastSentAt       : instant
lastReceipt      : CommandReceipt?
reconciliation   : ReconciliationResult?
evidence         : json       // 死信/UNKNOWN 的证据，不是失败结论
version          : bigint     // 乐观并发，沿用 001_init.sql:439
```

不可变规则（来自现有实现）：
- `markCommandSendStarted` **必须先提交**，再调用 adapter.send（`DeviceCommandExecutionService.java:22`）。
- 越过 `SEND_STARTED` 后**永不自动重发**；不确定 → UNKNOWN + outbox 死信（`:332-354`）。
- `markCommandUnknown` 只转非终态，不覆盖并发的 SUCCEEDED/FAILED/CANCELLED（`:317-330`）。
- outbox 重放只用于外部投递重试，绝不重发物理命令（`CommandMapper.xml:149-153`）。

### 4.5 `DeviceEvent`

设备侧事件（自发或命令回包），统一设备→中心的入口。

```
eventId          : uuid
sourceMessageId  : string     // 与 inbox_receipt 去重键统一
deviceId         : string
commandId        : string?    // 必须回显；缺失 → UNATTRIBUTED，流程不可依赖自发事件
eventType        : string
correlationKey   : string?    // G5 WAIT 需要的静态键
payload          : json
occurredAt       : instant
receivedAt       : instant
```

落地：统一到 `inbox_receipt` + `device_event`，并补齐 `device_event.command_id` / `expect_command_id`——现有规范自标"未实施"，相关 DDL 全库 0 命中。

### 4.6 `DeviceState`

**统一模型，终结当前 7 套口径并存的局面。** 必须区分三种正交状态，不能混为一谈：

```
connection  : enum(DISCONNECTED | CONNECTING | ONLINE | RECONNECTING | CLOSED)
operational : enum(IDLE | BUSY | FAULT | MAINTENANCE | UNKNOWN)
physical    : { doorOpen, holding, activeMethod, latchedFault, ... }  // 型号相关
online      : boolean         // sidecar Redis 心跳派生，仅作调度参考，不作执行依据
```

来源：`TcpDeviceConnection.java:58-64`、`DeviceCore.java:157-183`、`device_state`、`DeviceBusinessStateResolver`、`RedisLinkState`、`pylabrobot-sim._state`。

**判定原则**：`connection=ONLINE` 只说明链路可用；只有 `operational` 与物理状态查询才能支撑命令对账。

### 4.7 `CommandReceipt`

设备对命令的接收/执行凭证，与 `CommandExecution` 分离。

```
receiptId        : string
commandId        : uuid
deviceSequence   : string     // 设备侧流水号
kind             : enum(ACCEPTED | RUNNING | SUCCEEDED | FAILED | REJECTED)
payloadHash      : bytes[32]   // 冲突 → inbox_protocol_violation
receivedAt       : instant
```

映射：`SendResult`（`DeviceCommandAdapter.java:53-58`）、`CommandQueryResult`（`:10-18`）、`inbox_receipt`（`001_init.sql:369-401`）。

### 4.8 `ReconciliationResult`

对账结论，**必须是对事实的判定，而不是"猜成功"**。

```
commandId        : uuid
verdict          : enum(CONFIRMED_COMPLETED | CONFIRMED_FAILED | STILL_RUNNING
                       | NOT_FOUND | UNKNOWN | MANUAL_REQUIRED)
evidence         : { queriedAt, deviceState, deviceSequence, operator?, note }
actor            : string      // 人工裁决必填
resolvedAt       : instant
```

映射：`CommandLedger.ReconcileSummary`（四值）、`StartupRecovery.Quarantined`、`WorkerExecutionRecoveryService.StartupResult`。

关键语义（kernel 已确立，须继承）：`NOT_FOUND` **不等于设备没动过**（`CommandLedger.java:168-169`）。UNKNOWN 只能经设备状态查询 + 人工确认后解除（`StartupRecovery.java:177-204`）。

---

## 5. 状态机

### 5.1 命令状态（建议统一为 runtime 12 态）

以 `db/workflow_runtime/001_init.sql:431-433` 为准：

```
CREATED → DISPATCH_PENDING → SEND_STARTED → ACKNOWLEDGED → RUNNING → SUCCEEDED
                                   │              │            │
                                   │              └────────────┴→ FAILED
                                   ├→ DELIVERY_UNKNOWN → UNKNOWN → MANUAL_REQUIRED
                                   └→ CANCEL_REQUESTED → CANCELLED
```

- legacy 的 `DISPATCH_STARTED / ACKED / FAILED / UNKNOWN` + `phase(PENDING→SENT→RECEIVED→EXECUTING→DONE)` 需映射到上表。
- `RECEIVED / EXECUTING` 在 legacy 中是**预留不可达态**（`CommandLedger.java:113-118`）；V1 应明确它们是保留态还是要求设备实现 ACK/执行中回包。

### 5.2 连接状态

`DISCONNECTED / CONNECTING / ONLINE / RECONNECTING / CLOSED`（`TcpDeviceConnection.java:58-64`）。连接状态转换**不得**驱动命令状态转换。

### 5.3 对账结果状态

见 §4.8 `verdict`。`MANUAL_REQUIRED` 是终态之一，需显式人工动作；`STILL_RUNNING` 允许在 deadline 前再次查询。

---

## 6. 必守不变量（验收必须逐条断言）

1. **先落盘后发送**：`SEND_STARTED` 提交先于任何 adapter.send。
2. **绝不盲目重发**：同一 `commandId` 越过 `SEND_STARTED` 后无自动重发路径。
3. **幂等入站**：重复 receipt 返回 `DUPLICATE_REPLAY`，不产生第二次状态推进（`DeviceCommandExecutionService.java:267-302`）。
4. **冲突留痕**：同 `sourceMessageId` 不同 payload → `inbox_protocol_violation`，不得静默覆盖。
5. **fail-closed UNKNOWN**：不确定结果不判成功也不判失败；迟到 SUCCEEDED 被拒，走显式对账（`:322-330`）。
6. **NOT_FOUND ≠ 未执行**：对账必须保留这一语义。
7. **隔离物理副作用未知的运行**：参照 `StartupRecovery` 的 `quarantined` + `RuntimeHold(HUMAN)`（`StartupRecovery.java:128-204`）。
8. **重连不重放命令**：TCP 重连只恢复传输。
9. **Sidecar 无业务真相**：Sidecar 本地缓存丢失不得改变中心账本状态。
10. **模拟器与真实设备同一 Driver 接口**：禁止模拟器专用业务逻辑分支绕过 Driver。

---

## 7. 兼容与迁移策略

1. **双轨并存期**：legacy `public.command_ledger` 冻结为只读历史；新命令一律写统一账本。现有 kernel 直连路径先兼容运行，不改默认值。
2. **适配层**：`GatewayDeviceTaskBridge`、`DeviceCommandAdapter`、`LabSepWireCodec` 保留为薄适配；协议版本号与能力协商在适配层新增。
3. **Sidecar 双目录收敛**：`sidecar-dotnet/` 为目标；`labos-kernel/sidecar-dotnet/` 的 `IdempotentCache` 内存幂等必须移除或持久化。
4. **模拟器**：`DeviceCore` 与 `pylabrobot-sim` 逐步实现同一 Driver 接口；过渡期保留帧兼容。
5. **部署**：混合式——工位 Sidecar 注册到中心，中心持有权威账本；单实例 `SingleInstanceHostLock` 在多实例前替换为分布式租约。

---

## 8. 与 Phase 3 各阶段的对应

| 合同部分 | 落地阶段 |
|---|---|
| §2 分层红线、§4 对象语义、§6 不变量 | Phase 3B 冻结并写测试 |
| §4.1/4.2 协议版本、能力模型、§5.2 连接状态 | Phase 3C（Device Runtime 核心） |
| §4.6 设备状态、Sidecar 注册/心跳边界 | Phase 3D（混合式 Sidecar） |
| §4.5/4.7 事件统一、模拟器同接口 | Phase 3E（Simulator 与流程迁移） |
| §6 全部不变量的故障注入 | Phase 3F（系统级验收） |

---

## 9. 冻结前必须关闭的开放问题

1. **以哪套 `command_ledger` 为准？** 同名表在 `public`（BIGINT + `idempotency_key`）与 `workflow_runtime`（UUID + `command_id` PK）并存。建议统一到后者，但需确认历史数据处理。
2. **ID 空间如何统一？** `commandId` 当前同时是幂等键、事件归属键、主键；`sourceMessageId` 与 `sourceEventId` 是否统一术语？
3. **状态机以哪份为准？** 设计文档五态、kernel 实际三态、runtime 12 态。`RECEIVED/EXECUTING` 是保留态还是要求设备实现？
4. **`MANUAL_REQUIRED` 进入路径是否唯一？** runtime 的 `resolveUnknownCommand` 与 kernel 的隔离 + `RuntimeHold(HUMAN)` 如何映射？
5. **`设备命令与事件关联规范` 的"未实施"是否属实？** `device_event.command_id` / `expect_command_id` DDL 缺失、`DeviceEventRow` 仍读 `processId`；是否列为 Phase 3B 前置改造？
6. **Drill 6 / Drill 7 来源？** 快照中 0 命中；无法把"崩溃恢复验收证据"挂到合同条款。需用户提供原始材料或确认由 G4/G5 测试替代。
7. **故障注入覆盖是否足够？** 现有 G4-B/G4-C/G5 覆盖 DB 提交边界与命令发送边界；是否覆盖"`SEND_STARTED` 提交后、adapter.send 之前"的掉电窗口需核实。
8. **Device Runtime 是否复用 Workflow UUID 外键？** 若独立部署，`run_id/activation_id/attempt_id` 必须有替代语义。
9. **工位粒度**：一台设备一个 Sidecar，还是一个工位（多设备）一个 Sidecar？影响注册、心跳与故障域划分。
