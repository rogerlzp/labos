# DEVICE_GATEWAY_PROTOCOL — 设备网关、TLS 1.3 mTLS 认证与 Sidecar 协议契约（AI-2 主责 · Draft v1.6）

> **所属总纲**：[PHASE3B_MASTER_CONTRACT.md](./PHASE3B_MASTER_CONTRACT.md)  
> **核心执行对侧契约**：[DEVICE_EXECUTION_CORE_CONTRACT.md](./DEVICE_EXECUTION_CORE_CONTRACT.md)

---

## 1. 连接方向 A 演进路线与 TLS 1.3 mTLS 双向认证体系（P0-1 · P1-A）

### 1.1 方向 A 与 `TcpDeviceConnection` 演进种子定位
按照实验室现场运维确认的组网模式（**先启动工控机设备驱动程序监听约定 `IP:Port` 与唯一 ID，中控后启动作为 TCP Client 主动拨号**），本契约锁定 **方向 A**（当前状态：`CONFIRMED_BY_OPERATIONS / PENDING_NETWORK_TEST`，须在 Phase 3E 切入实景网络前完成《网络可达与 Lab-CA 运维核验单》实测签字）：
- 现有 [TcpDeviceConnection.java](../workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/TcpDeviceConnection.java) 与 [DeviceGateway.java](../workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/DeviceGateway.java)（已具备 `ndjson://` 拨号、心跳线程、连接代次防重入与 `EVENT_ACK`/`NACK` 骨架）作为 **方向 A 的正式演进种子**，而非推翻重做；
- 剥离 [WorkflowRuntimeHostProperties.java](../labos-kernel/src/boot/java/com/labos/boot/WorkflowRuntimeHostProperties.java#L164-L204) 中的 `labsep://` 协议分支，将单端点 1:1 映射拓展支持 **C2 工位级多设备端点**（`workstationId=tls://host:port` 下可代理多台 `deviceId`；同时兼容 C1 临时单设备测试端点）；
- 严禁从 [TcpDriverAdapter.java](../labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpDriverAdapter.java) 或 [TcpPersistentDriver.java](../labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpPersistentDriver.java) 引入任何类或代码路径。

### 1.2 `TLS 1.3 mTLS` 双向认证与轻量私有 CA 治理（彻底替代无加密过渡层）

**彻底删除此前未带机密性与每帧 AEAD 完整性保护的过渡挑战方案**；所有通信模式收敛为**两档**（无任何非回环明文模式）：

| 模式 (`security_mode`) | 适用范围 | 密码学安全保障（全由 TLS 1.3 协议层原生提供） |
| :--- | :--- | :--- |
| **`DEV_LOOPBACK_PLAINTEXT`** | **仅限本机 `127.0.0.1` / `::1`** 单测与本地进程沙箱 | 代码入口强制校验：若绑定或拨号地址不是 loopback，**直接拒绝启动 (`IllegalArgumentException`)** |
| **`TLS13_MTLS`** (**唯一合法非回环模式 · 首发强制默认**) | Phase 3B/3C/3D/3E/3F 全部局域网工位与生产环境 | 1. **完整传输加密、逐帧 AEAD 完整性与单连接内 TLS 记录序列抗重放（不接受 `0-RTT / Early Data`）**，业务命令/参数/回执不再裸奔；<br/>2. **双向证书 + SAN 白名单密码学绑定**：<br/>　- Sidecar 校验 Kernel 证书必须由 Lab Root/Sub CA 签发、在有效期内且其 `SAN (URI: urn:labos:kernel:<kernelId>)` 命中 Sidecar 配置的 `allowedKernelIds[]` 白名单（直接阻止未授权测试 Kernel 接入工控机）；<br/>　- Kernel 校验 Sidecar 证书 `SAN (URI: urn:labos:workstation:<workstationId>)` **必须精确等于**拨号目标的 `workstationId`（防止假工位或错端口串台）；<br/>3. **应用层 `HELLO` 角色交叉锁死**：`HELLO.kernelId` 必须等于 TLS Peer SAN 提取出的 `<kernelId>`，`HELLO_ACK.workstationId` 必须等于服务端 Peer SAN 提取出的 `<workstationId>`，任何不匹配立刻断连并记 `ERR_SESSION_IDENTITY_MISMATCH`。 |

**`TLS 1.3 0-RTT / Early Data` 能力边界与可测试握手栅栏红线**：
1. **运行库能力边界确认（不虚构不存在的 API 开关）**：采用的 `.NET 10`（`SslStream`）与 Java 17（JSSE `SSLContext` / `SSLEngine`）TLS 1.3 实现必须确认**不支持且不接受 TLS 1.3 `0-RTT / Early Data`**；若所选运行库未暴露 Early Data 配置开关，则通过运行库能力边界与版本/配置基线审计保证不处理 Early Data（严禁在契约或代码中虚构不存在的配置开关，亦严禁引入开启 `0-RTT` 的第三方 TLS 代理或引擎）。
2. **完整握手与身份校验栅栏**：只有在完整 TLS 1.3 1-RTT 握手完成、双向证书链/有效期/吊销名单校验及 `SAN (URI)` 身份核验全部通过**之后**，应用层协议解析器才允许接管数据流并接收帧（包括 `HELLO`、`COMMAND_SEND`、`COMMAND_CANCEL`、`COMMAND_QUERY` 等）。
3. **可测试的握手栅栏验证**：阶段 1–2 自动化测试必须证明，握手及双向证书/SAN 认证完成前的任何应用数据或未认证字节流均会被连接层直接拒绝/断开，绝不进入命令解析或执行路径。
4. **传输层与业务层防重严格分离**：TLS 1.3 记录层仅提供**单连接内（per-connection）**记录抗重放与逐帧 AEAD 完整性校验，**绝不等同于跨连接/跨重连的应用层命令幂等**；跨连接防重与物理副作用幂等保护严格由 `commandId` + Sidecar 本地 SQLite WAL Journal + Kernel PG `command_ledger` 共同承担。

**轻量实验室内部 CA 生命周期规范（不引入重型外部 KMS，但可审计、可轮换、可吊销）**：
1. **签发模型**：离线 Root CA（仅保管于管理员安全介质）签发工控机与 Kernel 的短期叶证书（推荐有效期 90 天）；工控机私钥以操作系统最小权限存储（Linux `0600` 属主只读 / Windows `LocalSystem`/服务账户密钥库）。
2. **零停机平滑轮换**：支持配置双 CA TrustStore 过渡窗口（旧 CA + 新 CA 并存），工控机轮换叶证书后下一次重连无缝生效。
3. **快速撤销（Denylist）**：Sidecar 与 Kernel 支持热加载本地 `revoked_cert_fingerprints.txt` / CRL（匹配 SHA-256 证书指纹 `cert_fingerprint_sha256`）；命中吊销指纹立刻拒绝新握手并**当场踢断已存在的同指纹长连接**。

### 1.3 会话失效与重连纪律（Kernel 重启 / Sidecar 重启 / 网络分区）
1. **单实例 `connectionGeneration`**：每次 Kernel 为某个 `workstationId` 新建拨号会话，必须先在 PG `workflow_runtime.workstation_sidecar` **原子递增 `connection_generation`** 后再写入 `HELLO`；任何携带旧代次号到达或滞留在套接字缓冲区的滞后心跳/回执帧一概丢弃。
2. **网络分区与陈旧会话踢除**：Sidecar Server 同一时刻对同一 `workstationId` **仅保持 1 个当前生效的高 `connectionGeneration` 控制连接**；若同一授权 Kernel（已通过 mTLS 认证）带更高 `connectionGeneration` 拨入（如中控崩溃重启重连），Sidecar **立即替换并关闭旧套接字**，避免双连接并存错乱。
3. **Allowlist 与设备归属冲突拦截**：Kernel 在 `HELLO_ACK` 收到 `devices[]` 后，校验所有 `deviceId` 是否均在配置的工位允许集合内且未同时被其他 `ONLINE` 工位持有（数据库 `device_state_projection` 主键 `(tenant_id, device_id)` + 严格 `workstation_id` 约束保障）；出现越权或跨工位抢注立即触发 `ERR_SESSION_DEVICE_CONFLICT` 断连停摆，绝不污染路由表。

---

## 2. 能力模型拆解：彻底区分 Sidecar Journal 与硬件原生查询能力

为彻底消除"仅查 Sidecar Journal 就误判硬件已完成"的致命漏洞，每个操作的能力声明（`DeviceCapabilities.operations[op]`）必须携带正交声明与对账策略：

```json
{
  "Bind": {
    "idempotencyClass": "NON_IDEMPOTENT",
    "defaultTimeoutMs": 600000,
    "interruptible": false,
    "supportsCancel": false,
    "supportsJournalQuery": true,
    "supportsNativeHardwareQuery": true,
    "reconciliationPolicy": {
      "autoQueryAllowed": true,
      "maxQueryAttempts": 3,
      "queryIntervalMs": 2000,
      "maxReconcileWindowMs": 15000,
      "requiredSuccessStrength": "STRONG_HARDWARE_FACT",
      "onExhaustedAction": "ESCALATE_UNKNOWN_AND_QUARANTINE"
    },
    "emitsWaitingEvents": ["waiting_interaction"]
  },
  "Seal": {
    "idempotencyClass": "NON_IDEMPOTENT",
    "defaultTimeoutMs": 30000,
    "interruptible": false,
    "supportsCancel": false,
    "supportsJournalQuery": true,
    "supportsNativeHardwareQuery": false,
    "reconciliationPolicy": {
      "autoQueryAllowed": false,
      "maxQueryAttempts": 0,
      "queryIntervalMs": 0,
      "maxReconcileWindowMs": 0,
      "requiredSuccessStrength": "STRONG_HARDWARE_FACT",
      "onExhaustedAction": "ESCALATE_UNKNOWN_AND_QUARANTINE"
    },
    "emitsWaitingEvents": []
  }
}
```

- **`supportsJournalQuery`**（Sidecar 自身能力）：可查本地 `sidecar_command_journal` 记录的精确状态；
- **`supportsNativeHardwareQuery`**（设备本体物理能力，如 `P_AUTO` 有任务寄存器、`S100` 老式串口无任务流水表）：仅当为 `true` 时，`IProtocolAdapter.QueryHardwareAsync(commandId, deviceSequence)` 才允许在崩溃窗口后向真机发起只读状态读取；
- **`COMMAND_QUERY_RESULT` 与终态 `DEVICE_EVENT` 强制附带完整 `ExecutionEvidence`（与 CORE §1.1 共享跨语言完整枚举）**：
  - `deviceSequence`（仅作关联标识，不构成独立成功凭证）；
  - `evidenceSource`（5 值全集，对齐 CORE & `contract-fixtures/v1_1/`）：`HARDWARE_NATIVE_QUERY` \| `PRIOR_HARDWARE_COMPLETION_IN_JOURNAL` \| `PRE_SEND_ABORT_IN_JOURNAL` \| `OPERATOR_PHYSICAL_INSPECTION` \| `AMBIGUOUS`；
  - `evidenceStrength`（4 档全集，对齐 CORE & `contract-fixtures/v1_1/`）：`STRONG_HARDWARE_FACT` \| `STRONG_PRE_SEND_ABORT` \| `STRONG_OPERATOR_ATTESTED` \| `WEAK_UNVERIFIED`；
  - `evidencePayload`（JSON：保存原始硬件应答报文 / 状态寄存器快照）。
  - **线协议防伪红线**：`OPERATOR_PHYSICAL_INSPECTION` 与 `STRONG_OPERATOR_ATTESTED` 属于 **Kernel T21 授权人工入口专用值，严禁出现在任何 TCP 线协议帧内**；Gateway/Inbox 入站一旦在 `COMMAND_RECEIPT` / `COMMAND_QUERY_RESULT` / `DEVICE_EVENT` 中检测到这两个值，必须当场拒绝并返 **`ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE`**（`NACK_PERMANENT`），杜绝设备/Sidecar 伪造人工现场证明。
  - 对于 Journal 只存在 `ACCEPTED_PENDING_SEND` / `DISPATCHED_TO_DEVICE` 且硬件无法原生自证的操作，**必须返回 `evidenceSource="AMBIGUOUS"`, `evidenceStrength="WEAK_UNVERIFIED"`, `reconciliationStatus="INDETERMINATE"`**；Core 结构性拒收任何无 `STRONG_HARDWARE_FACT` 的自动 `CONFIRMED_SUCCEEDED`。

---

## 3. 版本化 NDJSON 线协议 v1.1（完整 13 帧 + 不对称可靠性与顺序声明）

### 3.1 协议全局铁律与可靠性/顺序边界（N5 · P1-5）
1. 编码：UTF-8 单行 JSON（`\n` 结尾）；所有帧必含 `"protocolVersion": "1.1"`、`"frameType"`、`"sentAtEpochMs"`；**绝不允许任何 `#!LABSEP!#` 帧或裸文本帧混入**。
2. **明确可靠性不对称设计（N5）**：
   - **唯一可靠 Outbox 重传通道**：只有 **`DEVICE_EVENT`**（携带不变的 `sourceMessageId`）受 Sidecar 持久化 Outbox 保护，直到收到 Kernel 在 PG `COMMIT` 后返回的 `EVENT_ACK` 方可删除；
   - **尽最大努力回执通道**：`COMMAND_RECEIPT` 与 `COMMAND_QUERY_RESULT` **不建独立 Outbox 队列**；一旦在传输中丢失，由 Kernel 的 `receiptTimeoutMs` / `DELIVERY_UNKNOWN` + 重连后按 `ReconciliationPolicy` 发起 **`COMMAND_QUERY`**（使用新 `dispatchId`）兜底。
3. **Outbox 顺序与 `EVENT_ACK` 故障恢复边界（P1-5）**：
   - **顺序保证粒度**：Sidecar Outbox **按单个 `deviceId` 严格保持 FIFO 投递顺序**（同台设备上命令进展与紧随其后的自发事件按生成序号依次投递；不同设备之间互不阻塞，避免单机阻塞全工位）；
   - **阶段明确区分**：`EVENT_ACK` 仅证明 **Kernel 数据库已提交不可抵赖的权威收据**，绝不等于“业务 `WAIT` 节点必然已经被立刻消费完成”；
   - **双侧崩溃恢复**：① 若 Kernel 已 `COMMIT` 但在回写 `EVENT_ACK` 前断网 → 重连后 Sidecar 按原 `sourceMessageId` 重发，`inbox_receipt` 幂等返回 `EVENT_ACK(status="DUPLICATE")`，Sidecar 据此清理 Outbox；② 若 Sidecar 收到 `EVENT_ACK` 后、删除本地 `sidecar_event_outbox` 前崩溃 → 重启后重发原 `sourceMessageId`，同样命中 `EVENT_ACK(status="DUPLICATE")` 并安全核销，**零重复消费、零丢事件**。

### 3.2 全部 13 种标准帧规范（方向 A）

| `frameType` | 方向 | 关键载荷字段 | 语义与确认约束 |
| :--- | :--- | :--- | :--- |
| `HELLO` | Kernel → Sidecar | `workstationId`, `kernelId`, `connectionGeneration`, `heartbeatIntervalMs`, `heartbeatTimeoutMs`, `supportedProtocolVersions` | TLS 1.3 mTLS 建连后首帧，`kernelId` 与 `workstationId` 须与双方证书 Peer SAN 严格匹配 |
| `HELLO_ACK` | Sidecar → Kernel | `workstationId`, `acceptedProtocolVersion`, `sidecarInstanceId`, `storageState`, `devices[]` (`descriptor`, `capabilities`, `observation`) | 声明所辖设备能力树（含双层 Query 与 `reconciliationPolicy`）、本地存储真实态与实时观察快照 |
| `HEARTBEAT_PING` | Kernel → Sidecar | `workstationId`, `connectionGeneration`, `pingId` | 传输层保活；超时切换 `DEGRADED`/`OFFLINE` 阻断派发 |
| `HEARTBEAT_PONG` | Sidecar → Kernel | `workstationId`, `connectionGeneration`, `pingId`, `sidecarEpochMs` | 必须匹配当前连接 `connectionGeneration` 与活跃 `pingId` |
| `COMMAND_SEND` | Kernel → Sidecar | `commandId`, `dispatchId`, `runId`, `attemptId`, `deviceId`, `operation`, `idempotencyClass`, `timeoutMs`, `interruptible`, `params` | 单 `commandId` 派发（`params` 已完成 `${var}` 替换）；重连禁止盲发 |
| `COMMAND_RECEIPT` | Sidecar → Kernel | `commandId`, `dispatchId`, `deviceId`, `receiptStatus` (`ACCEPTED`\|`DUPLICATE_IN_FLIGHT`\|`COMPLETED_IMMEDIATELY`\|`FAILED_IMMEDIATELY`), `failureBoundary?`, `deviceSequence?`, `evidenceSource?`, `evidenceStrength?`, `evidencePayload?`, `acceptedAtEpochMs`, `errorCode?`, `outcome?` | Sidecar `fsync` 落盘 Journal 后的立即回执；`FAILED_IMMEDIATELY` 必带 `failureBoundary`；`COMPLETED_IMMEDIATELY` 必带 `STRONG_HARDWARE_FACT` |
| `COMMAND_QUERY` | Kernel → Sidecar | `commandId`, `dispatchId`, `deviceId`, `deviceSequence?` | **只读状态查询**，绝不产生物理动作 |
| `COMMAND_QUERY_RESULT` | Sidecar → Kernel | `commandId`, `dispatchId`, `deviceId`, `reconciliationStatus`, `deviceSequence?`, `evidenceSource`, `evidenceStrength`, `evidencePayload`, `errorCode?`, `outcome?` | 依据 `ExecutionEvidence` 返回确定结果或 `INDETERMINATE` |
| `COMMAND_CANCEL` | Kernel → Sidecar | `commandId`, `dispatchId`, `deviceId`, `reason` | 仅当操作 `interruptible=true` 才允许下发；终态以 `DEVICE_EVENT` 为准 |
| `DEVICE_EVENT` | Sidecar → Kernel | `sourceMessageId`, `eventScope` (`COMMAND`\|`SPONTANEOUS`), `deviceId`, `eventName`, `commandId?`, `targetState?`, `deviceSequence?`, `evidenceSource?`, `evidenceStrength?`, `evidencePayload?`, `errorCode?`, `payload` | 由 Sidecar Outbox 按 `deviceId` FIFO 投递；`deviceId` 受会话白名单硬校验 |
| `EVENT_ACK` | Kernel → Sidecar | `sourceMessageId`, `status` (`APPLIED`\|`DUPLICATE`\|`BUFFERED`) | **仅在 PG `inbox_receipt` COMMIT 后发送**，通知 Sidecar 核销 Outbox |
| `EVENT_NACK` | Kernel → Sidecar | `sourceMessageId`, `retryable`, `errorCode`, `reason` | `retryable=true` 时退避重发同 `sourceMessageId`；`false` 落本地死信告警 |
| `DEVICE_STATE` | Sidecar → Kernel | `deviceId`, `deviceState`, `activeCommandId?`, `deviceSequence?`, `faultCode?`, `observedAtEpochMs`, `detail` | 更新 Kernel `device_state_projection` 与观察新鲜度 |

### 3.3 统一错误码表（`ContractV1ErrorCodes`）

| 错误码 | 层级 | `retryable` | 触发隔离 | 说明 |
| :--- | :--- | :---: | :---: | :--- |
| `ERR_WIRE_UNSUPPORTED_VERSION` / `ERR_WIRE_MALFORMED_FRAME` | Gateway / Sidecar | 否 | 否 | 缺版本/非 JSON/混入旧分隔协议帧 → NACK 并断开 |
| `ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE` | Gateway / Core | 否 | 否 | 线协议帧违规出现 `OPERATOR_PHYSICAL_INSPECTION` / `STRONG_OPERATOR_ATTESTED`（仅允许 T21 授权人工入口使用） → 永久拒收 |
| `ERR_WIRE_SPOOFED_DEVICE` / `ERR_SESSION_IDENTITY_MISMATCH` / `ERR_SESSION_DEVICE_CONFLICT` | Gateway / Sidecar | 否 | 否 | TLS 1.3 mTLS 证书/SAN/吊销校验失败、工位 ID 不符、跨工位争抢同 `deviceId` |
| `ERR_SESSION_STALE` | Core / Gateway | 是（等上线） | 否 | 工位心跳失效或连接非 `ONLINE` 拦截派发 |
| `ERR_CAPABILITY_MISSING_OP` / `ERR_CAPABILITY_CANCEL_UNSUPPORTED` | Core | 否 | 否 | 未声明的操作拒绝派发；不支持取消的操作拒绝取消 |
| `ERR_SAFETY_IMPACT_UNDECLARED` | Core / SafetyGate | 否 | 否 | Sidecar 上报了操作，但 Core 尚未登记 `CommandResourceImpactSpec` → Fail-Closed 拒绝派发 |
| `ERR_SAFETY_LEASE_BUSY` | SafetyGate | 是（排队） | 否 | 目标资源（设备/板位/轨道/载具）被占用 |
| `ERR_SAFETY_QUARANTINED` / `ERR_SAFETY_PRECONDITION_FAILED` | SafetyGate | 否 | 已隔离/按影响 | 存在 `ACTIVE` 隔离单，或欠缺抓前扫码/门控/搅拌器条件 |
| `ERR_EVENT_UNROUTABLE` / `ERR_EVENT_RUN_TERMINAL` | Core / EventBridge | 否 | 否 | 自发事件按三级规则命中 0 条或多条歧义 Run，或目标 Run 已终结（不再猜单活跃 Run） |
| `ERR_DEVICE_REJECTED_PRE_EXECUTION` | Sidecar / Adapter | 按策略 | **否** | 参数/前置非法在发往硬件前拒单（`PRE_EXECUTION`） |
| `ERR_DEVICE_EXECUTION_FAILED` | Sidecar / Adapter | 按策略 | 视影响范围 | 执行中设备报故障终态 |
| `ERR_RECONCILE_DB_TRANSIENT` | Gateway / Inbox | **是** | 否 | PG 暂时超时 → 返回 `EVENT_NACK(retryable=true)` |
| `ERR_RECONCILE_INDETERMINATE` | Core / Sidecar | 否 | **是** | 无法提供 `STRONG_HARDWARE_FACT`（含 `FRESH_STORAGE` 或无原生查询断电） → `UNKNOWN` + 隔离 |

---

## 4. `.NET 10` Sidecar 持久化与崩溃恢复契约

1. **权威定位**：Kernel PG `command_ledger` 为**唯一命令账本**；Sidecar 本地 SQLite WAL 数据库仅存两张防重与防丢表：
   - `sidecar_command_journal(command_id PK, idempotency_class, device_id, operation, journal_state, device_sequence, outcome_json, failure_boundary, accepted_at_ms)`；
   - `sidecar_event_outbox(source_message_id PK, event_scope, device_id, seq_no, command_id, payload_json, created_at_ms, delivered_ack_at_ms)`（按 `device_id, seq_no` 严格保序出队）。
2. **强制 WAL 时序**：
   - `COMMAND_SEND` 到达 → 先查 `sidecar_command_journal`：存在同 `command_id` 即直接依据既落盘状态回传 `DUPLICATE_IN_FLIGHT`（携带原 `deviceSequence` 与 `acceptedAtEpochMs`）或重放既有立即终态回执 / 重新排入尚未 ACK 的终态事件；
   - 不存在 → **先以 `ACCEPTED_PENDING_SEND` 事务提交 + 磁盘 `fsync` 落盘，随后才能触发 `IProtocolAdapter.SendAsync(...)`**。
   - 物理执行完成/失败 → 先更新 `journal_state`（保存原始硬件响应快照 `evidencePayload`）并同事务写入 `sidecar_event_outbox` 后再发出 `DEVICE_EVENT`；直到收妥 `EVENT_ACK` 方可清理 Outbox。
3. **诚实 Fail-Closed 边界**：
   - **崩溃发生在"发硬件中、未写回终态"**且 `supportsNativeHardwareQuery=false`：重连后只能返回 `evidenceSource = "AMBIGUOUS"`, `evidenceStrength = "WEAK_UNVERIFIED"`, `reconciliationStatus = "INDETERMINATE"`；
   - **工控机磁盘损坏 / 重装清空数据目录**：重启携带新 `sidecarInstanceId` 与 `storageState = "FRESH_STORAGE"` 或 `"CORRUPTED"`；Kernel 对该工位下所有处于在途态的命令（除能走 `HARDWARE_NATIVE_QUERY` 取回 `STRONG_HARDWARE_FACT` 者外）**一律强制升格 `UNKNOWN` + 打开 `quarantine_record`**。

---

## 5. 双层模拟器边界与跨语言黄金样例对齐（AI-3 接入协议）

| 替身类型 | 部署边界 | 测试范围与要求 |
| :--- | :--- | :--- |
| **A. Wire-Level Chaos Harness** | **替代 Sidecar Server**，直接监听端口承接 `DeviceGateway` 拨号 | 按本契约实现方向 A `TLS13_MTLS`（含坏证书/SAN 不匹配/吊销测试）+ `HELLO`/`HELLO_ACK`，并可编程注入：断连 RST、写黑洞、`COMMAND_RECEIPT` 丢失后由 `COMMAND_QUERY` 恢复、伪造无硬件背书的 `WEAK_UNVERIFIED` 验证 Core 拒收、篡改伪造 `deviceId`、`FRESH_STORAGE` 重启、丢失 `EVENT_ACK` 验证重传幂等 |
| **B. Device-Level Virtual Simulator** | **挂载在 `.NET 10` Sidecar `IProtocolAdapter` 之下**（模拟真机串口/TCP/DLL 接口） | 实现 `P_AUTO-1..4`、`DISP1..6`、`ARM1`、`STACK1`、`S100`、`ASpin` 真实状态机与分段延时，支持自发吐出 `waiting_interaction`、执行故障与板位校验，验证完整 4 层链路：`Kernel → DeviceGateway → .NET Sidecar → VirtualSimulator` |

**跨语言 Golden Fixtures 约束（R3）**：黄金样例集统一存放于**仓库根中立目录 `contract-fixtures/v1_1/*.json`**（不在 Java 测试资源内部），Java (`workflow-runtime` Maven 测试) 与 `.NET 10` (`sidecar-dotnet` `dotnet test`) 各自直接从仓库根相对路径读取同一组文件进行序列化/反序列化等价断言，彻底杜绝双份副本漂移。
