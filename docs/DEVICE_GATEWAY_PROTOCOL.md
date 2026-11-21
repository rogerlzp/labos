# DEVICE_GATEWAY_PROTOCOL — 设备网关、认证与工位 Sidecar 协议契约（AI-2 主责 · Draft v1.1）

> **所属总纲**：[PHASE3B_MASTER_CONTRACT.md](./PHASE3B_MASTER_CONTRACT.md)  
> **核心执行对侧契约**：[DEVICE_EXECUTION_CORE_CONTRACT.md](./DEVICE_EXECUTION_CORE_CONTRACT.md)

---

## 1. 连接方向 A 演进路线与三级认证体系

### 1.1 方向 A 与 `TcpDeviceConnection` 演进种子定位
按照实验室现场正式运维约束（**先启动工控机设备驱动程序监听约定 `IP:Port` 与唯一 ID，中控后启动作为 TCP Client 主动拨号**），本契约正式锁定 **方向 A**：
- 现有 [TcpDeviceConnection.java](../workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/TcpDeviceConnection.java) 与 [DeviceGateway.java](../workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/DeviceGateway.java)（已具备 `ndjson://` 拨号、心跳线程、连接代次防重入与 `EVENT_ACK`/`NACK` 骨架）作为 **方向 A 的正式演进种子**，而非推翻重做；
- 剥离 [WorkflowRuntimeHostProperties.java](../labos-kernel/src/boot/java/com/labos/boot/WorkflowRuntimeHostProperties.java#L164-L204) 中的 `labsep://` 协议分支，将单端点 1:1 映射拓展支持 **C2 工位级多设备端点**（`workstationId=tcp://host:port` 下可代理多台 `deviceId`；同时兼容 C1 临时单设备测试端点）；
- 严禁从 [TcpDriverAdapter.java](../labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpDriverAdapter.java) 或 [TcpPersistentDriver.java](../labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpPersistentDriver.java) 引入任何类或代码路径。

### 1.2 三级可插拔身份认证与防跨环境接入（不阻断首发，又防明文伪造）

单纯应用层 `workstationId` 无法密码学防伪造。契约定义底层/握手统一接口支持三级 `TransportSecurityMode`，上层 NDJSON 业务帧完全复用：

| 安全等级 | 适用阶段 | 实现机制 | 防护目标 |
| :--- | :--- | :--- | :--- |
| **`DEV_LOOPBACK`** | 仅 `127.0.0.1` 本机单测 | 仅限环回网卡地址，禁止非 `127.0.0.1` 绑定启用 | 本地快速自测，避免配置密钥拖累 CI 单元测试 |
| **`PSK_HMAC_CHALLENGE`** (**首发强制默认**) | Phase 3B / 3C / 3D / 3E 实验室局域网 | 1. TCP 建连后 Kernel 发 `HELLO(workstationId, connectionGeneration, kernelNonce, kernelHmac)`<br/>2. Sidecar 验证 `kernelHmac = HMAC-SHA256(psk[workstationId], kernelNonce \|\| workstationId \|\| connectionGeneration)` 及授权 `kernelId` 白名单；校验失败**直接静默断开**<br/>3. Sidecar 回 `HELLO_ACK(..., sidecarNonce, sidecarHmac)`，证明持有同一预共享工位密钥 `HMAC-SHA256(psk[workstationId], kernelNonce \|\| sidecarNonce \|\| workstationId \|\| connectionGeneration)` | **无需自建企业 CA/CRL**，即刻解决"如何阻止未授权测试 Kernel 接入工控机"以及"防止假设备/错环境串台" |
| **`MTLS`** | Phase 3F 生产加固平滑升级 | 底层 `Socket` / `.NET SslStream` 启用 TLS 1.3 双向证书校验 + SAN 白名单，同时叠加上述 `HELLO`/`HELLO_ACK` 会话代次绑定 | 满足未来跨网段/强合规环境的传输加密与证书吊销轮换要求 |

### 1.3 会话失效与重连纪律（Kernel 重启 / Sidecar 重启 / 网络分区）
1. **单实例 `connectionGeneration`**：每次 Kernel 为某个 `workstationId` 新建 TCP 拨号会话，必须先在 PG `workflow_runtime.workstation_sidecar` **原子递增 `connection_generation`** 后再写入 `HELLO`；任何携带旧代次号到达或滞留在 TCP 缓冲的滞后心跳/回执帧一概丢弃。
2. **网络分区与陈旧会话踢除**：Sidecar Server 同一时刻对同一 `workstationId` **仅保持 1 个当前生效的高 `connectionGeneration` 控制连接**；若新授权 Kernel 带更高 `connectionGeneration` 拨入（如中控崩溃重启重连），Sidecar **立即替换并关闭旧套接字**，避免双连接并存错乱。
3. **Allowlist 与设备归属冲突拦截**：Kernel 在 `HELLO_ACK` 收到 `devices[]` 后，校验所有 `deviceId` 是否均在配置的工位允许集合内且未同时被其他 `ONLINE` 工位持有；出现越权或跨工位抢注立即触发 `ERR_SESSION_DEVICE_CONFLICT` 断连停摆，绝不污染路由表。

---

## 2. 能力模型拆解：彻底区分 Sidecar Journal 与硬件原生查询能力

为彻底消除"仅查 Sidecar Journal 就误判硬件已完成"的致命漏洞，每个操作的能力声明（`DeviceCapabilities.operations[op]`）必须携带正交声明：

```json
{
  "Bind": {
    "idempotencyClass": "NON_IDEMPOTENT",
    "defaultTimeoutMs": 600000,
    "interruptible": false,
    "supportsCancel": false,
    "supportsJournalQuery": true,
    "supportsNativeHardwareQuery": true,
    "emitsWaitingEvents": ["waiting_interaction"]
  },
  "Seal": {
    "idempotencyClass": "NON_IDEMPOTENT",
    "defaultTimeoutMs": 30000,
    "interruptible": false,
    "supportsCancel": false,
    "supportsJournalQuery": true,
    "supportsNativeHardwareQuery": false,
    "emitsWaitingEvents": []
  }
}
```

- **`supportsJournalQuery`**（Sidecar 自身能力）：可查本地 `sidecar_command_journal` 记录的精确状态；
- **`supportsNativeHardwareQuery`**（设备本体物理能力，如 `P_AUTO` 有任务寄存器、`S100` 老式串口无任务流水表）：仅当为 `true` 时，`IProtocolAdapter.QueryHardwareAsync(commandId, deviceSequence)` 才允许在崩溃窗口后向真机发起只读状态读取；
- **`COMMAND_QUERY_RESULT` 强制附带 `evidenceSource` 字段**：
  1. `HARDWARE_NATIVE_QUERY`：直接从设备硬件接口读取到的权威结果（可合法转 `CONFIRMED_SUCCEEDED` / `CONFIRMED_FAILED` / `CONFIRMED_CANCELLED` / `STILL_RUNNING` / `CONFIRMED_NOT_EXECUTED`）；
  2. `PRIOR_HARDWARE_COMPLETION_IN_JOURNAL`：Sidecar 在断连前**已经真实收完硬件终态回包并落盘 WAL**、仅因尚未写回或断网未发送给 Kernel（`command_ledger` 还没更新）——允许回 `CONFIRMED_SUCCEEDED`/`FAILED`；
  3. `PRE_SEND_ABORT_IN_JOURNAL`：Sidecar 在调用 `IProtocolAdapter.SendAsync` **之前**就中止/拒绝，且 `fsync` 落盘未发出物理报文——允许回 `CONFIRMED_NOT_EXECUTED`（或当场直接用 `FAILED_IMMEDIATELY(PRE_EXECUTION)`）；
  4. **`AMBIGUOUS`**：Journal 只存在 `ACCEPTED_PENDING_SEND` / `DISPATCHED_TO_DEVICE`，且设备硬件无法原生自证——**强制只能返回 `reconciliationStatus = "INDETERMINATE"`**！Core 层收到后触发 `UNKNOWN + MANUAL_REQUIRED + Quarantine`。

---

## 3. 版本化 NDJSON 线协议 v1.1（完整 13 帧 + 不对称可靠性声明）

### 3.1 协议全局铁律
1. 编码：UTF-8 单行 JSON（`\n` 结尾）；所有帧必含 `"protocolVersion": "1.1"`、`"frameType"`、`"sentAtEpochMs"`；**绝不允许任何 `#!LABSEP!#` 帧或裸文本帧混入**。
2. **明确可靠性不对称设计（N5 显式声明）**：
   - **唯一可靠 Outbox 重传通道**：只有 **`DEVICE_EVENT`**（携带不变的 `sourceMessageId`）受 Sidecar 持久化 Outbox 保护，直到收到 Kernel 在 PG `COMMIT` 后返回的 `EVENT_ACK` 方可删除，绝不丢事件；
   - **尽最大努力回执通道**：`COMMAND_RECEIPT` 与 `COMMAND_QUERY_RESULT` **不建独立 Outbox 队列**；一旦在传输中丢失，由 Kernel 的 `receiptTimeoutMs` / `DELIVERY_UNKNOWN` + 重连后主动发起 **`COMMAND_QUERY`**（必要时用新的 `dispatchId`）完成幂等拉取兜底。

### 3.2 全部 13 种标准帧规范（方向 A）

| `frameType` | 方向 | 关键载荷字段 | 语义与确认约束 |
| :--- | :--- | :--- | :--- |
| `HELLO` | Kernel → Sidecar | `workstationId`, `connectionGeneration`, `heartbeatIntervalMs`, `heartbeatTimeoutMs`, `supportedProtocolVersions`, `kernelId`, `kernelNonce`, `kernelHmac` | 拨号后首帧（含 `PSK_HMAC` 挑战或 mTLS 上下文验证） |
| `HELLO_ACK` | Sidecar → Kernel | `workstationId`, `acceptedProtocolVersion`, `sidecarInstanceId`, `storageState`, `sidecarNonce`, `sidecarHmac`, `devices[]` (`descriptor`, `capabilities`, `observation`) | 声明所辖设备能力树、本地存储真实态与实时三维观察快照 |
| `HEARTBEAT_PING` | Kernel → Sidecar | `workstationId`, `connectionGeneration`, `pingId` | 传输层保活；超时切换 `DEGRADED`/`OFFLINE` 阻断派发 |
| `HEARTBEAT_PONG` | Sidecar → Kernel | `workstationId`, `connectionGeneration`, `pingId`, `sidecarEpochMs` | 必须匹配当前连接 `connectionGeneration` 与活跃 `pingId` |
| `COMMAND_SEND` | Kernel → Sidecar | `commandId`, `dispatchId`, `runId`, `attemptId`, `deviceId`, `operation`, `idempotencyClass`, `timeoutMs`, `interruptible`, `params` | 单 `commandId` 派发（`params` 已完成 `${var}` 替换）；重连禁止盲发 |
| `COMMAND_RECEIPT` | Sidecar → Kernel | `commandId`, `dispatchId`, `deviceId`, `receiptStatus` (`ACCEPTED`\|`DUPLICATE_IN_FLIGHT`\|`COMPLETED_IMMEDIATELY`\|`FAILED_IMMEDIATELY`), `failureBoundary?`, `deviceSequence?`, `acceptedAtEpochMs`, `errorCode?`, `outcome?` | Sidecar `fsync` 落盘 Journal 后的立即回执；`FAILED_IMMEDIATELY` 必带 `failureBoundary` |
| `COMMAND_QUERY` | Kernel → Sidecar | `commandId`, `dispatchId`, `deviceId`, `deviceSequence?` | **只读状态查询**，绝不产生物理动作 |
| `COMMAND_QUERY_RESULT` | Sidecar → Kernel | `commandId`, `dispatchId`, `deviceId`, `reconciliationStatus`, `evidenceSource`, `deviceSequence?`, `errorCode?`, `outcome?` | 依据 `evidenceSource` 返回确定结果或 `INDETERMINATE` |
| `COMMAND_CANCEL` | Kernel → Sidecar | `commandId`, `dispatchId`, `deviceId`, `reason` | 仅当操作 `interruptible=true` 才允许下发；终态以 `DEVICE_EVENT` 为准 |
| `DEVICE_EVENT` | Sidecar → Kernel | `sourceMessageId`, `eventScope` (`COMMAND`\|`SPONTANEOUS`), `deviceId`, `eventName`, `commandId?`, `targetState?`, `deviceSequence?`, `errorCode?`, `payload` | 由 Sidecar Outbox 持久保序投递；`deviceId` 受会话白名单硬校验 |
| `EVENT_ACK` | Kernel → Sidecar | `sourceMessageId`, `status` (`APPLIED`\|`DUPLICATE`\|`BUFFERED`) | **仅在 PG COMMIT 后发送**，通知 Sidecar 核销 Outbox |
| `EVENT_NACK` | Kernel → Sidecar | `sourceMessageId`, `retryable`, `errorCode`, `reason` | `retryable=true` 时退避重发同 `sourceMessageId`；`false` 落本地死信告警 |
| `DEVICE_STATE` | Sidecar → Kernel | `deviceId`, `deviceState`, `activeCommandId?`, `deviceSequence?`, `faultCode?`, `observedAtEpochMs`, `detail` | 更新 Kernel `device_state_projection` 与观察新鲜度 |

### 3.3 统一错误码表（`ContractV1ErrorCodes`）

| 错误码 | 层级 | `retryable` | 触发隔离 | 说明 |
| :--- | :--- | :---: | :---: | :--- |
| `ERR_WIRE_UNSUPPORTED_VERSION` / `ERR_WIRE_MALFORMED_FRAME` | Gateway / Sidecar | 否 | 否 | 缺版本/非 JSON/混入旧分隔协议帧 → NACK 并断开 |
| `ERR_WIRE_SPOOFED_DEVICE` / `ERR_SESSION_IDENTITY_MISMATCH` / `ERR_SESSION_DEVICE_CONFLICT` | Gateway / Sidecar | 否 | 否 | `PSK_HMAC`/mTLS 校验失败、工位 ID 不符、跨工位争抢同 `deviceId` |
| `ERR_SESSION_STALE` | Core / Gateway | 是（等上线） | 否 | 工位心跳失效或连接非 `ONLINE` 拦截派发 |
| `ERR_CAPABILITY_MISSING_OP` / `ERR_CAPABILITY_CANCEL_UNSUPPORTED` | Core | 否 | 否 | 未声明的操作拒绝派发；不支持取消的操作拒绝取消 |
| `ERR_SAFETY_LEASE_BUSY` | SafetyGate | 是（排队） | 否 | 目标资源（设备/板位/轨道/载具）被占用 |
| `ERR_SAFETY_QUARANTINED` / `ERR_SAFETY_PRECONDITION_FAILED` | SafetyGate | 否 | 已隔离/按影响 | 存在 `ACTIVE` 隔离单，或欠缺抓前扫码/门控/搅拌器条件 |
| `ERR_DEVICE_REJECTED_PRE_EXECUTION` | Sidecar / Adapter | 按策略 | **否** | 参数/前置非法在发往硬件前拒单（`PRE_EXECUTION`） |
| `ERR_DEVICE_EXECUTION_FAILED` | Sidecar / Adapter | 按策略 | 视影响范围 | 执行中设备报故障终态 |
| `ERR_RECONCILE_DB_TRANSIENT` | Gateway / Inbox | **是** | 否 | PG 暂时超时 → 返回 `EVENT_NACK(retryable=true)` |
| `ERR_RECONCILE_INDETERMINATE` | Core / Sidecar | 否 | **是** | 无法提供硬件自证（含 `FRESH_STORAGE` 或无原生查询断电） → `UNKNOWN` + 隔离 |

---

## 4. `.NET 10` Sidecar 持久化与崩溃恢复契约

1. **权威定位**：Kernel PG `command_ledger` 为**唯一命令账本**；Sidecar 本地 SQLite WAL 数据库仅存两张防重与防丢表：
   - `sidecar_command_journal(command_id PK, idempotency_class, device_id, operation, journal_state, device_sequence, outcome_json, failure_boundary, accepted_at_ms)`；
   - `sidecar_event_outbox(source_message_id PK, event_scope, device_id, command_id, payload_json, created_at_ms, delivered_ack_at_ms)`。
2. **强制 WAL 时序**：
   - `COMMAND_SEND` 到达 → 先查 `sidecar_command_journal`：存在同 `command_id` 即直接依据既落盘状态回传 `DUPLICATE_IN_FLIGHT`（携带原 `deviceSequence` 与 `acceptedAtEpochMs`）或重放既有立即终态回执 / 重新排入尚未 ACK 的终态事件；
   - 不存在 → **先以 `ACCEPTED_PENDING_SEND` 事务提交 + 磁盘 `fsync` 落盘，随后才能触发 `IProtocolAdapter.SendAsync(...)`**。
   - 物理执行完成/失败 → 先更新 `journal_state` 并同事务写入 `sidecar_event_outbox(source_message_id)` 后再通过长连接发出 `DEVICE_EVENT`；直到收妥 `EVENT_ACK` 方可清理/归档 Outbox。
3. **诚实 Fail-Closed 边界**：
   - **崩溃发生在"发硬件中、未写回终态"**且 `supportsNativeHardwareQuery=false`：重连后只能返回 `evidenceSource = "AMBIGUOUS"` + `reconciliationStatus = "INDETERMINATE"`；
   - **工控机磁盘损坏 / 重装清空数据目录**：重启携带新 `sidecarInstanceId` 与 `storageState = "FRESH_STORAGE"` 或 `"CORRUPTED"`；Kernel 对该工位下所有处于在途态的命令（除能走 `HARDWARE_NATIVE_QUERY` 核实者外）**一律强制升格 `UNKNOWN` + 打开 `quarantine_record`**。

---

## 5. 双层模拟器边界与跨语言黄金样例对齐（AI-3 接入协议）

| 替身类型 | 部署边界 | 测试范围与要求 |
| :--- | :--- | :--- |
| **A. Wire-Level Chaos Harness** | **替代 Sidecar Server**，直接监听端口承接 `DeviceGateway` 拨号 | 按本契约完整实现方向 A `HELLO`/`HELLO_ACK` 与 `PSK_HMAC` 握手，并可编程注入：断连 RST、写黑洞、`COMMAND_RECEIPT` 丢失后由 `COMMAND_QUERY` 恢复、返回 `AMBIGUOUS` vs `HARDWARE_NATIVE_QUERY`、篡改伪造 `deviceId`、`FRESH_STORAGE` 重启、丢失 `EVENT_ACK` 验证重传幂等 |
| **B. Device-Level Virtual Simulator** | **挂载在 `.NET 10` Sidecar `IProtocolAdapter` 之下**（模拟真机串口/TCP/DLL 接口） | 实现 `P_AUTO-1..4`、`DISP1..6`、`ARM1`、`STACK1`、`S100`、`ASpin` 真实状态机与分段延时，支持自发吐出 `waiting_interaction`、执行故障与板位校验，验证完整 4 层链路：`Kernel → DeviceGateway → .NET Sidecar → VirtualSimulator` |

**跨语言 Golden Fixtures 约束**：`workflow-runtime/src/test/resources/contract-v1_1-fixtures/*.json` 为阶段 0 冻结共享样例集，Java (`DeviceGateway` 编解码测试) 与 `.NET 10` (`Sidecar` 编解码测试) 在各自 CI 内必须用完全同一组 JSON 向量断言序列化/反序列化 100% 等价。
