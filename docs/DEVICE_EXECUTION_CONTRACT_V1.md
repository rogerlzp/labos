# 设备执行契约 V1（建议稿 · DEVICE_EXECUTION_CONTRACT_V1）

## 元信息

| 项 | 值 |
| --- | --- |
| 审计基线 | `origin/main` @ `c650e271`（2026-11-21 17:12） |
| 审计分支 | `docs/phase3a-audit-20261121` |
| 状态 | **建议稿（PROPOSED）**，未冻结；待评审 |
| 部署形态 | **C 混合式**（中心 Kernel 管流程与命令状态；工位 Sidecar 管本地连接/驱动/执行） |
| 路径口径 | 相对仓库根目录；引用格式 `path:line` |
| 审计执行 | Trae AI（DeepSeek-V4.1-Flash） |
| 生成时间 | 2026-11-21 |

> 本文件只定义**边界与语义**，不定义最终 Java 签名。所有字段的持久化归属以现状 `command_ledger / inbox_receipt / wait_registration` 为准，落地时回写对齐。

---

## 0. TL;DR

1. 目标：让"**不同厂商设备**"对 Workflow 暴露**统一能力模型**；真实设备与模拟器**共用同一契约**；命令**可追踪、可对账、可恢复**。
2. 三层**不可逾越**边界：Workflow Runtime 不懂 TCP；TCP/协议层不懂 BPMN；驱动不写 Workflow DB。
3. 命令状态机已有实证实现（12 态 + 转移矩阵），本契约以其为**冻结语义**，仅补齐对象抽象与跨层字段归属。
4. **必须区分三个 ID**：`commandId`（命令身份）、`idempotencyKey`（重发去重，落在 attempt 维度）、`sourceMessageId`（设备消息幂等）。
5. **对账（Reconciliation）是实验室自动化与普通 Web 任务系统的核心差异**：崩溃后命令可能 `UNKNOWN`，禁止裸重发，须经设备查询 + 人工对账。
6. **待裁决**：`SingleInstanceHostLock` 是本地文件锁，与"每工位一个 Sidecar"的混合式部署存在张力（§6）。

---

## 1. 分层职责边界（硬约束）

| 层 | 核心职责 | **禁止** |
| --- | --- | --- |
| Workflow Runtime | 流程推进与持久化（GraphPlan/GraphCoordinator/Dispatch/Wait） | 理解底层 TCP 细节 |
| Device Execution Layer | Device Task Contract、Command Ledger、State Reconciliation、Capability Model | 直接推进 Workflow 状态 |
| Device Gateway | TCP Client、Protocol Adapter、Connection Manager | 业务动作解释 |
| Device Sidecar | 本地连接管理、驱动生命周期、能力注册、隔离、重连、对账上报 | 成为"另一套 Workflow 状态库" |
| Real Devices / Simulator | 执行物理动作 / 协议忠实模拟 | 使用独立业务逻辑绕过 Driver |

---

## 2. 统一执行契约的 8 个对象

| 对象 | 含义 | 建议持久化归属 |
| --- | --- | --- |
| `DeviceDescriptor` | 设备身份、型号、协议、位置 | **配置**（`device_instance`/`models.json`）；不随命令落库 |
| `DeviceCapabilities` | 设备能力列表 | 配置（`capability`/`device_method`），驱动启动时声明 |
| `DeviceCommand` | 统一设备命令 | `command_ledger`（payload/hash） |
| `CommandExecution` | 命令执行生命周期 | `command_ledger`（state/version）+ `command_dispatch_attempt` |
| `DeviceEvent` | 设备事件（入站） | `inbox_receipt` + 事件表 |
| `DeviceState` | 当前设备状态 | `device_state`（权威源待定，见 §6） |
| `CommandReceipt` | 命令接收凭证 | `inbox_receipt`（幂等键） |
| `ReconciliationResult` | 命令对账结果 | 运行时计算，可选落表（待定） |

### 2.1 `DeviceCommand` 最小字段（建议）

```
{
  "commandId":      "uuid",   // 命令身份；= command_ledger.command_id
  "runId":          "uuid",
  "activationId":   "uuid",
  "attemptId":      "uuid",
  "deviceId":       "ARM1",
  "commandKind":    "Move",   // 能力方法名
  "parameters":     {},
  "deadline":       "2026-11-21T18:00:00+08:00",
  "idempotencyKey": "uuid"    // 重发去重，attempt 维度
}
```

**三 ID 不可混用**（用户明确要求）：

| ID | 语义 | 唯一性口径 | 现状落点 |
| --- | --- | --- | --- |
| `commandId` | 命令身份 | `(tenant_id, command_id)` 主键 | `command_ledger.command_id`（`db/workflow_runtime/001_init.sql:425`） |
| `idempotencyKey` / `idempotency_class` | 重发去重策略 | `UNIQUE (tenant_id, attempt_id)`；class `{IDEMPOTENT,QUERYABLE,NON_IDEMPOTENT}` | `001_init.sql:434`、`:443` |
| `sourceMessageId` | 设备消息幂等 | `UNIQUE (tenant_id, source, source_message_id)` | `inbox_receipt` |

---

## 3. 命令状态机（冻结语义）

**12 态**（`db/workflow_runtime/001_init.sql:431-433`）：
`CREATED → DISPATCH_PENDING → SEND_STARTED → ACKNOWLEDGED → RUNNING → SUCCEEDED/FAILED`，旁路 `CANCEL_REQUESTED → CANCELLED`、`DELIVERY_UNKNOWN → UNKNOWN`、终局 `MANUAL_REQUIRED`。

**转移矩阵**（`WorkflowRuntimeRepository.java:4428-4437`，实证）：

| 当前 | 允许到达 |
| --- | --- |
| `SEND_STARTED` | ACKNOWLEDGED, RUNNING, SUCCEEDED, FAILED, DELIVERY_UNKNOWN, UNKNOWN |
| `ACKNOWLEDGED` | RUNNING, SUCCEEDED, FAILED, CANCEL_REQUESTED, DELIVERY_UNKNOWN, UNKNOWN |
| `RUNNING` | RUNNING, SUCCEEDED, FAILED, CANCEL_REQUESTED, DELIVERY_UNKNOWN, UNKNOWN |
| `CANCEL_REQUESTED` | CANCELLED, SUCCEEDED, FAILED, DELIVERY_UNKNOWN, UNKNOWN |
| `DELIVERY_UNKNOWN` | ACKNOWLEDGED, RUNNING, SUCCEEDED, FAILED, UNKNOWN |
| 其它 | 拒绝（default false） |

**消息类型 → 目标状态白名单**（`WorkflowRuntimeRepository.java:4258-4268`）：
`RECEIVED_ACK→ACKNOWLEDGED`；`EXECUTING/PROGRESS→RUNNING`；`COMMAND_RESULT/DEVICE_COMMAND_RESULT→{SUCCEEDED,FAILED,CANCELLED}`；`DELIVERY_UNCERTAIN/COMMAND_UNKNOWN→{DELIVERY_UNKNOWN,UNKNOWN}`。

**发送边界不变量**：`send()` 之前必须先持久化 `SEND_STARTED`（`DeviceCommandExecutionService.java:146-151`）。

---

## 4. 事件与收据契约

- 入站事件对象 `DeviceEventSink.InboundDeviceEvent`（`workflow-runtime/.../gateway/DeviceEventSink.java`）：`authenticatedDeviceId`（连接绑定，**可信**）/ `claimedDeviceId`（载荷声明，**不可信**）/ `runId/commandId/sourceMessageId/messageType/targetState/deviceSequence/outcomeJson`。
- 送达结果 `DeliveryResult ∈ {ACKED, NACK_RETRYABLE, NACK_PERMANENT}`。
- 幂等与冲突：`inbox_receipt` 幂等键 `(tenant_id, source, source_message_id)`；`payload_hash` 冲突 → `PROTOCOL_VIOLATION`。
- 身份绑定：`source` 始终取 `authenticatedDeviceId`（`RuntimeDeviceEventBridge.java`），杜绝网络载荷伪造身份。
- 唤醒等待：`receiveWaitInbox(...)` → `WAIT_SATISFIED`；超时 → `WAIT_EXPIRED`（`WorkflowRuntimeRepository.java:1884-1885`）。

---

## 5. Command Reconciliation Service（对账服务，**新增**）

对账必须区分 5 种事实（用户明确要求）：**未发送 / 已发送未确认 / 设备已执行 / 设备执行失败 / 无法确认**。

| 事实 | 判定依据 | 处置 |
| --- | --- | --- |
| 未发送 | `CREATED/DISPATCH_PENDING` 且无 dispatch attempt | 安全取消 → 可重试 |
| 已发送未确认 | `SEND_STARTED`，超出 stale 窗口 | **禁止裸重发**；发起 `queryStatus()` |
| 设备已执行 | 查询返回 COMPLETED | 回写 `SUCCEEDED` |
| 设备失败 | 查询返回 FAILED | 回写 `FAILED` |
| 无法确认 | 查询 `NOT_FOUND/UNKNOWN` 或传输中断 | → `UNKNOWN` → `MANUAL_REQUIRED` |

现状实证（可直接复用为 V1 语义）：

- 内核：`CommandLedger.reconcileWithQuery(...)`（`labos-kernel/.../driver/CommandLedger.java:171-223`）；`ReconcileSummary(confirmedCompleted, confirmedFailed, stillRunning, unknown)`（`:141-146`）；人工处置 `resolve(...)`（`:320-324`）。
- 运行时：`WorkerExecutionRecoveryService`（`workflow-runtime/.../recovery/WorkerExecutionRecoveryService.java:20-204`）；恢复顺序 **Inbox 完成 → Attempt 恢复 → Outbox 重排**（`:60-101`）；`recoverInterruptedAttempt` 的 `SAFE_TO_RETRY / MANUAL_REQUIRED` 二分（`WorkflowRuntimeRepository.java:5324-5465`）。
- 未解决副作用判定依赖 `worker_execution_fact.side_effect_key`，取值 `"command:" + commandId`（`WorkflowRuntimeRepository.java:4454`）。

---

## 6. 部署形态 C（混合式）与单实例约束（**待裁决**）

现状：`SingleInstanceHostLock` 用**本地 OS 文件锁**（`workflow-runtime/.../host/SingleInstanceHostLock.java:22-85`，"不使用 PostgreSQL advisory lock 或任何分布式协调"）；`EmbeddedWorkflowRuntimeHost` 启动序为 **HostLock → Ingress → Recovery → Logic → Gate Open**（`.../host/EmbeddedWorkflowRuntimeHost.java:39-51`）；V1 部署信任模型为"单进程在隔离实验室 LAN 内，无 TLS"（`:55-59`）。

混合式 C 的目标是"**中心 Kernel + 每工位 Sidecar**"。二者关系必须在契约里明确：

| 选项 | 说明 | 风险 |
| --- | --- | --- |
| C1（推荐先行） | 中心 Kernel 内含 `DeviceGateway`；Sidecar 仅作**本地设备接入进程**，与 Kernel 可同机也可跨机（仅 TCP） | 最小改动；但"跨机 Sidecar"的重连/对账需明确边界 |
| C2 | 每工位 Sidecar 常驻本地，Kernel 通过 Sidecar 下发命令 | 需定义 Sidecar 侧的**命令缓存边界**（不得成为第二套状态库） |

> 必答安全红线（沿用现有硬约束）：中心侧 `command_ledger` 仍是**唯一命令真相**；Sidecar 只缓存通信态；`SEND_STARTED` 必须在中心侧先落库。

---

## 7. 与现有实现的映射与缺口

| 拟定对象 | 现有最接近实现 | 位置 | 缺口 |
| --- | --- | --- | --- |
| DeviceDescriptor | `DriverDescriptor` | `labos-kernel/.../driver/DriverDescriptor.java:15-61` | 不落库、无位置/协议版本 |
| DeviceCapabilities | `DriverDescriptor.capabilities` + `capability` 表 | `pg_rebuild/schema/95_seed_data.sql:21-44` | 缺统一能力→方法映射 |
| DeviceCommand | `DriverCommand`(旧) / `command_ledger`(新) | `.../driver/DriverCommand.java:14` | 两套并存 |
| CommandExecution | `command_ledger` 12 态 + 转移矩阵 | `001_init.sql:423-450`、`WorkflowRuntimeRepository.java:4428-4437` | 已较完整 |
| DeviceEvent | `InboundDeviceEvent` + `device_event` + `inbox_receipt` | `DeviceEventSink.java`、`20261115_device_event.sql` | 已较完整 |
| DeviceState | `device_state` + `HeartbeatService` + `HealthSnapshot` | `30_business.sql:459-469`、`96_schema_fix.sql:92-94` | **无统一权威源**（三源分散） |
| CommandReceipt | `InboxIngestionOutcome` / `CommandQueryResult` | `DeviceCommandExecutionService.java`、`.../driver/CommandQueryResult.java` | 无统一抽象 |
| ReconciliationResult | `ReconcileSummary` / `WorkerRecoveryResult` | `CommandLedger.java:141-146`、`WorkflowRuntimeRepository.java:5324` | 两套口径，需统一 |

**硬约束继承（不得违反）**：全局锁序 `Run → Token → Activation → Wait → Inbox`；`workflow_run` 行锁优先；网关**绝不自动重发**在途命令（`DELIVERY_UNCERTAIN`）；单实例由 OS 文件锁保证，DB 不引入选举。

---

## 8. 未决问题（进入 Phase 3B 前需裁决）

1. **DeviceState 权威源**：Redis / `device_state` / 运行时内存，三选一或定义优先级。
2. **Sidecar 跨机形态**：C1 还是 C2；若 C2，`SingleInstanceHostLock` 与 Sidecar 的部署约束如何写。
3. **两套 command_ledger 收敛**：内核 `BIGINT` 版与运行时 `UUID` 版如何退役/对齐。
4. **`CommandReceipt` 统一抽象**：是否新建统一类型。
5. **协议版本机制**：是否在 `GatewrayWireProtocol` 引入 `protocol_version` 协商。
6. **模拟器复用 Driver**：是否强制"模拟器走同一 `Protocol Adapter`"。
