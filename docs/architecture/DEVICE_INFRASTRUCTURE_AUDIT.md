# DEVICE_INFRASTRUCTURE_AUDIT — 设备基础设施现状审计

- 基线：`origin/main` @ `c650e271`（2026-11-21 17:12:49 +0800），只读快照审计。
- 约束：未修改生产代码，未连接 labdb，未启动 RuntimeHost，未向真实设备下发命令。
- 配套：`REAL_WORKFLOW_INVENTORY.md`、`DEVICE_EXECUTION_CONTRACT_V1.md`、`PHASE3_IMPLEMENTATION_PLAN.md`。

> 路径为仓库相对路径，行号对应上述基线。标注【推测】的条目未取得源码/DDL 级直接证据。

---

## 0. 总结论

**仓库里同时存在三套彼此独立的设备执行栈，且各自管理连接与命令状态：**

| 栈 | 连接管理 | 命令持久化 | 当前状态 |
|---|---|---|---|
| ① legacy kernel 直连驱动 | `TcpDriverAdapter` / `TcpPersistentDriver` | `public.command_ledger`（BIGINT）+ `command_outbox` + `run_command` | **源码中仍是默认下发路径**（`WorkflowService.buildAdapter:1879-1891`） |
| ② `labos-gateway` 独立进程 + Redis | `DriverConnection` | `labos:cmd:pending/result` | 文档称"可选、未切换"（`docs/技术架构与模块地图.md:44-45`）；【推测】源码中无中控侧 LPUSH 生产者 |
| ③ workflow-runtime 内嵌网关 | `DeviceGateway` + `TcpDeviceConnection` | `workflow_runtime.command_ledger`（UUID）+ `runtime_outbox` + `inbox_receipt` | 最完整的可靠模型，但需 `labos.workflow.runtime.host.enabled=true` |

**已确认的四个结构性事实：**
1. 连接管理重复：至少 6 处独立持有 socket 的客户端实现。
2. 命令状态重复：两张同名 `command_ledger` 表，schema、ID 空间、状态枚举均不兼容；另有 Redis、内存 pending、sidecar 内存幂等缓存。
3. 协议无统一版本管理：分隔符 `#!LABSEP!#` 在 6 处硬编码，无版本字段/协商。
4. 设备状态无统一模型：至少 7 套口径并存；模拟器与真实 Driver **只共享线上帧格式，不共享代码接口**。

因此用户的判断成立：**不应把 TCP Client / Driver / Sidecar / Simulator 当四个孤立项目同时推倒重来**；应先统一合同，再逐层替换。但替换顺序必须优先处理"命令状态唯一化"，否则重构会制造第三套账本。

---

## 1. TCP Client 有几套？分别在哪里？

按实现文件计：**8 类客户端（生产相关 5 类）+ 4 类服务端**。

### 1.1 Java 客户端

| # | 类 | 位置 | 职责 |
|---|---|---|---|
| J1 | `TcpDeviceConnection` | `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/TcpDeviceConnection.java:56` | 每设备一条持久连接；NDJSON / `#!LABSEP!#` 双 codec；心跳、指数退避重连、EVENT_ACK/NACK。socket `:455-494`，重连 `:424-453`，心跳 `:342-384`，发命令 `:300-327` |
| J2 | `DriverConnection` | `labos-gateway/src/main/java/com/labos/gateway/driver/DriverConnection.java:35` | 每驱动一条长连接，Redis 取命令，按 `message_id` 配对，写 Redis 结果/状态。`:79-106, 108-159, 166-199, 261-272`。**无心跳帧实现**（类注释 `:29` 声称有，全类 `heartbeat` 0 命中） |
| J3 | `TcpPersistentDriver` | `labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpPersistentDriver.java:60` | legacy 长连接；`pending` Map 配对；`:209-240` 连接，`:282-287` 读线程退出后懒重连 |
| J4 | `TcpDriverAdapter` | `labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpDriverAdapter.java:44` | legacy **短连接**，一命令一连接；`:235-236` `new Socket(...)` |
| J5 | `WorkflowService.probe` | `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java:1894-1900` | 400ms 短连接探活（在线状态权威已改 sidecar→Redis，见 `:1882-1891`） |

### 1.2 .NET 客户端

| # | 类 | 位置 |
|---|---|---|
| N1 | `SimBridgeTransport` | `sidecar-dotnet/Labos.Sidecar/SimBridgeTransport.cs:31`（sidecar → Java 模拟器长连接） |
| N2 | `TcpDriverConnection` | `labos-kernel/sidecar-dotnet/Drivers/TcpDriverConnection.cs:32-33`（老 gRPC sidecar → 设备） |
| N3 | `RedisLinkState` | `sidecar-dotnet/Labos.Sidecar/RedisLinkState.cs:23`（手写 RESP 的 Redis TCP 客户端，三次 `new TcpClient` :46/:76/:124） |

**注意有两个不同的 sidecar 目录并存**：`sidecar-dotnet/`（新 TCP sidecar + Manager）与 `labos-kernel/sidecar-dotnet/`（老 gRPC sidecar + IdempotentCache）。

### 1.3 Python / JS / 服务端

- P1：`pylabrobot-sim/send_job.py:12, 54`——测试/演示客户端。
- JS：无设备 TCP 客户端。
- 服务端：`DeviceCore.java:406-418`、`pylabrobot-sim/run_simulator.py:947-953`、`TcpServer.cs:42-58`、`MockDriverServer.cs:19-27`。

### 1.4 重复证据

`TcpDeviceConnection` / `DriverConnection` / `TcpPersistentDriver` / `TcpDriverAdapter` / `SimBridgeTransport` / `TcpDriverConnection.cs` 都在做同一件事：**`#!LABSEP!#` 分帧 + `message_id`/`commandId` 配对 + 断线重连**。分隔符硬编码于：

- `labos-kernel/.../sim/DeviceCore.java:61`
- `labos-kernel/.../driver/tcp/TcpDriverAdapter.java:47`
- `labos-kernel/.../driver/tcp/TcpPersistentDriver.java:65`
- `labos-gateway/.../driver/DriverConnection.java:37`
- `sidecar-dotnet/Labos.Sidecar/Protocol.cs:29`
- `pylabrobot-sim/run_simulator.py:39`

无自定义 Netty；仅 Redis 客户端用 Lettuce/Netty（`labos-gateway/.../redis/RedisClient.java:17-19`）。

---

## 2. Driver 按什么划分？`DeviceCore` 是什么？

**已确认：调度按能力、实现按传输、设备知识按型号画像——三者分离，不是单一维度。**

```
DeviceDriver (interface)  labos-kernel/.../driver/DeviceDriver.java:16-73
├─ VirtualDriver                 .../driver/VirtualDriver.java:16
├─ TcpDriverAdapter              .../driver/tcp/TcpDriverAdapter.java:44
├─ TcpPersistentDriver           .../driver/tcp/TcpPersistentDriver.java:60
└─ (跨语言) DriverService : DeviceDriverBase
                                 labos-kernel/sidecar-dotnet/Services/DriverService.cs:16
```

- 契约方法：`describe / execute / abort / queryStatus / healthy`；`abort` 默认 UNSUPPORTED（`:41-43`），`queryStatus` 默认 UNKNOWN（`:63-65`）。
- **没有按厂商或型号命名的 Java Driver 子类**。厂商/型号知识在数据里：
  - `DriverDescriptor.java:15-23`：`code, endpoint, protocol, driverKind, capabilities, deviceCapabilities, deviceInstanceIds, metadata`。
  - `DriverRegistry.java:12-16, 26-44, 51-64`：注释明确"把按设备 id 找驱动变成按能力找设备"。
  - DB `driver` 表 `pg_rebuild/schema/20_kernel.sql:104-120`（`vendor/protocol/version/manifest`）+ `driver_capability` `:125-130`。
  - `DeviceModels.java`、`sidecar-dotnet/models.json:5-12, 37-45, 80-86`。
  - sidecar 按传输分五档：`sidecar-dotnet/Labos.Sidecar/Program.cs:332-357`（sim / serial / dll / lhc / gen5）。

**`DeviceCore` 不是 driver，是模拟器进程内核**：`labos-kernel/.../sim/DeviceCore.java:56`（`public final class`，不 implements `DeviceDriver`），TCP 服务端 `:237-249, 406-418`，请求处理 `:666-697`；消费 `DeviceProfile`/`DeviceModel`/`DeviceBehavior`（`:138-155`，行为接口 `DeviceBehavior.java:17-31`）。与真实 driver **只共享线上协议，不共享接口/状态机代码**。

---

## 3. 真实调用关系

### 3.1 主链（workflow-runtime）

```
BPMN GraphPlan.Node
 → GatewayDeviceTaskBridge.resolveDispatchSpec/resolveHandler   bridge/GatewayDeviceTaskBridge.java:109-147
 → WorkerExecutionContext.createDeviceCommand                   worker/WorkerExecutionContext.java:45-50
 → WorkerExecutionService                                       worker/WorkerExecutionService.java:542-546
 → WorkflowRuntimeRepository.createCommandForAttempt            :3572
     → command_ledger(CREATED) + runtime_outbox(DEVICE_COMMAND)
 → RuntimeDispatchLoop                                          host/RuntimeDispatchLoop.java:313-327
 → DeviceCommandExecutionService.deliverPendingCommands         command/DeviceCommandExecutionService.java:80-145
 → DeviceGateway.asCommandAdapter / sendCommand                 gateway/DeviceGateway.java:118-132
 → TcpDeviceConnection.sendCommand                              gateway/TcpDeviceConnection.java:300-327
 → TCP → sidecar:8501 或 Java/pylabrobot 模拟器

回程：
 TCP 入站帧 → TcpDeviceConnection.ingress/eventQueue
 → RuntimeDeviceEventBridge.onDeviceEvent                       gateway/RuntimeDeviceEventBridge.java:95-133
 → DeviceCommandExecutionService.ingestDeviceEvent              command/DeviceCommandExecutionService.java:224-303
 → inbox_receipt + command_ledger 状态推进

Bridge 只轮询 DB：loadCommand + SUCCEEDED/FAILED/UNKNOWN 判断  bridge/GatewayDeviceTaskBridge.java:187-221
```

**关键事实**：`GatewayDeviceTaskBridge` **不直接调用** `TcpDeviceConnection` 发命令；它 import `TcpDeviceConnection.Endpoint` 仅用于取 deviceId 集合（`:6, 46-52`）。两者通过 DB outbox/ledger 解耦。装配点：`host/EmbeddedWorkflowRuntimeHost.java:195-212`。

### 3.2 其他路径

- `labos-gateway`：`GatewayMain.java:18-30, 123-169`，`RPOP labos:cmd:queue:{code}` → `labos:cmd:pending:{code}` → `DriverConnection.execute` → Redis 结果。**【推测】中控侧无 LPUSH 生产者**（全仓仅 `GatewayMain.java:125`、`RedisClient.lpush:98-99` 与文档/redis-cli 示例）。
- 新 sidecar：`Program.cs:47-175` 启动 → `TcpServer.Run` 监听 8501（`:40-72`）→ `ITransport`（`:332-357`）→ 设备/模拟器；自发事件经 `DeviceEventForwarder.cs:11, 114-133` → `POST /api/device-events`。
- `pylabrobot-sim`：独立 Python TCP 服务端（`run_simulator.py:921-953`），与 Java runtime 无代码级调用。

---

## 4. 命令由谁持久化？

### 4.1 两套同名表（已复核 DDL）

| | legacy（kernel） | runtime |
|---|---|---|
| DDL | `pg_rebuild/schema/99f_command_ledger.sql:9-27` | `db/workflow_runtime/001_init.sql:423-450` |
| 主键 | `id BIGINT IDENTITY` | `(tenant_id uuid, command_id uuid)` |
| 幂等 | `idempotency_key VARCHAR(160)`，`UNIQUE(tenant_id, idempotency_key)` | `idempotency_class` 分类 + `payload_hash bytea`（32 字节） |
| 关联 | `run_id BIGINT, node_id, attempt, step_seq, device_id BIGINT, method, command_id VARCHAR(160)` | `run_id/activation_id/attempt_id` 均为 UUID 外键 |
| 状态 | `DISPATCH_STARTED/ACKED/FAILED/UNKNOWN` + 独立 `phase` 列 | 12 态 `CREATED…MANUAL_REQUIRED` |
| 恢复 | `CommandLedger.reconcileWithQuery` + `StartupRecovery` 隔离 | `DeviceCommandExecutionService` + `WorkerExecutionRecoveryService` |

legacy 写入：`CommandMapper.xml:18-39`（同事务 `insertDispatchBegin` + `insertOutboxEvent`）、`:48-54`、`:58-80`、`:84-93`；`CommandLedger.java:48-75, 103-111, 135-138, 171-223`。调用方 `DriverStepExecutor.java:1215, 1230, 1284-1294, 1341`。另有历史表 `run_command`（`90_all_in_one.sql:602-620`）。

runtime 写入：`WorkflowRuntimeRepository.createCommandForAttempt:3572`、`beginOutboxSendForKind:4127`、`markCommandSendStarted:4199`、`receiveInbox:4272`、`applyCommandEventOrReject:4321`、`markCommandUnknown:5141`、`resolveUnknownCommand:1700`、`recoverOutboxAfterRestart:5197`。

**结论**：写入方 = repository/service；状态推进方 = `DeviceCommandExecutionService`（runtime）与 `CommandLedger.reconcileWithQuery`（legacy）；`GatewayDeviceTaskBridge` 只读取并在超时时写 UNKNOWN（`:218-221`）。

---

## 5. 断线后谁重连？

| 层 | 类与位置 | 机制 |
|---|---|---|
| runtime 网关 | `TcpDeviceConnection.java:424-453, 455-494` | 指数退避 200ms→5s（`:126-134`）；每设备独立 scheduler 心跳（`:246-253, 342-384`），epoch-bound pingId 防伪造 PONG |
| legacy 长连接 | `TcpPersistentDriver.java:209-240, 282-287` | 读线程退出 → 下次 `ensureConnected()` 懒重连 |
| labos-gateway | `DriverConnection.java:79-106, 108-142` | `ensureConnected()` + 读线程退出 `failAllPending`；**无心跳帧** |
| sidecar → 设备 | `SimBridgeTransport.cs:64-73, 103-109, 180-201` | 半开连接丢弃 + 重连；写超时 `CloseQuietly()` |
| sidecar → Redis | `Program.cs:226-229, 262-300` | 每 10s 刷新 TTL 30s 的 `labos:link:*`，退出 Clear |
| kernel 在线/告警 | `HeartbeatService.java:42, 90-133, 267-294` | 基于 `last_heartbeat` 的 DB 心跳，**与 TCP 传输心跳是两套** |

**重要边界**：`TcpDeviceConnection.java:49-50` 明确"Reconnect ONLY restores transport liveness；NO command retry buffer，NEVER auto-resend"——这正是用户强调的"TCP 连接恢复 ≠ 设备状态恢复"。

---

## 6. Sidecar 崩溃后命令状态如何恢复？

**已确认：恢复责任在中控数据库，不在 sidecar。**

新 sidecar 启动步骤：解析参数/取型号（`Program.cs:85-108`）→ 构建 ITransport（`:110-111, 332-357`）→ 认领设备（`:113-128, 313-330`，失败不致命）→ `StartLinkKeeper` 每 10s 刷在线态（`:223-305`）→ `DeviceEventForwarder` 加载磁盘 outbox 未确认事件（`DeviceEventForwarder.cs:25-37, 63-112`）→ `TcpServer.Run` 监听 8501。Manager 侧 `AdoptExisting` 收编现存子进程（`DeviceSupervisor.cs:88-90, 149-185`），上线时按原命令行 `RelaunchOriginal`（`:357-413`）或退化 `--link-only`（`:458-502`）。

命令恢复链：
- 越过 `SEND_STARTED` 后崩溃 → `handlePostSendUncertainty` → `markCommandUnknown` + outbox 死信，**不重发**（`DeviceCommandExecutionService.java:317-354`）。
- `GatewayDeviceTaskBridge` 轮询超时也只写 UNKNOWN（`:209-221`）。
- 显式人工核对 `reconcileUnknownCommand`（`:308-315`）。
- Host 重启：`WorkerExecutionRecoveryService.recoverAtStartup` 先 inbox → 再 attempt → 最后 outbox（`:56-101`）。
- **sidecar 内没有命令 outbox**：sidecar 目录只有 `device-event-outbox`（`DeviceEventOutbox.cs:5-9`）；老 gRPC sidecar 的 `IdempotentCache` 是**内存态**（`IdempotentCache.cs:13-38`），崩溃即丢失。

【推测】Manager 只有手动/按原命令行重拉，未见崩溃自动 watchdog/Timer 循环；不排除外部 systemd/容器编排承担。

---

## 7. Simulator 是否复用真实 Driver？

**已确认：不复用代码接口，只复用线上协议。**

- `DeviceCore` 不 implements `DeviceDriver`；它是 TCP 服务端。真实 driver 实现 `DeviceDriver`。
- 共享的是帧分隔符与 `method_status=1/2/3` 语义：`DeviceCore.java:61`、`TcpDriverAdapter.java:47`、`Protocol.cs:29`、`LabSepWireCodec.java:15-27, 34, 140-163`。
- sidecar `--transport sim` 只是原样转发给 Java 模拟器（`SimBridgeTransport.cs:8-15, 60-73`）。
- `pylabrobot-sim` 完全独立：自有 `SEP`（`run_simulator.py:39`）、硬编码 `PROFILE_METHODS`（`:42-47`）、自有 `_state`/`_ledger`（`:87-93`）、自有 TCP handler。**不 import 任何 Java driver**。
- 唯一"共用接口"的 `VirtualDriver`（`:13-16`）是内存桩，不驱动 `DeviceCore`。
- 状态机不等价：Java `DeviceCore` 有 doorOpen/running/holding/latchedFault + 型号行为（`:138-155, 157-183`）；Python 只有 `_state` 字符串。

---

## 8. 协议版本管理？

**已确认：没有。**

- `GatewayWireCodec.java:19-48` 只有 `protocolName()` / `frameDelimiter()` / `supportsAppHeartbeat()`，**无版本字段/协商**。
- `GatewayWireProtocol.java:42-48, 54-78` 只有 frameType 常量；NDJSON codec 无版本号。
- 分隔符与回包形状在 6 处硬编码（见 §1.4）。
- DB `driver.version`（`20_kernel.sql:109`）与 `models.json` 的 `protocol` 是描述性字符串，不参与帧解析。
- `schemaVersion` 属于 GraphPlan，不是设备协议。
- "协议版本不匹配"只作为设备错误码语义出现（`models.json:64, 70`；`DeviceModels.java:185, 197`）。

---

## 9. 设备状态模型？

**已确认：没有统一模型，至少 7 套口径并存。**

| 口径 | 证据 |
|---|---|
| DB 运行态 | `90_all_in_one.sql:459-469`（`device_state.status='idle'`, `has_plate`, `ready_give/take`） |
| DB 资源态 | 同文件 `:306-318`（`device_instance.status resource_status`） |
| 业务态字典 + 读时派生 | `90_all_in_one.sql:5100-5112`；`DeviceBusinessStateResolver.java:14-45, 50-65`（明确"与 device_state.status 不是一回事，不能混用"） |
| Redis 在线态 | `DriverConnection.java:261-272`（`labos:dev:state`）；`RedisLinkState.cs:14-18`（`labos:link`） |
| 传输连接态 | `TcpDeviceConnection.java:58-64`（DISCONNECTED/CONNECTING/ONLINE/RECONNECTING/CLOSED） |
| 模拟器物理态 | `DeviceCore.java:157-183`；`DeviceBehavior.state():29-32` |
| Python 仿真态 | `run_simulator.py:87-93` |

`command_ledger.command_state` 是命令状态，不是设备状态，但在界面/文档中经常混用。

---

## 10. 三处重复管理连接/命令状态？

**已确认：存在。**

- 连接：runtime `DeviceGateway`（`EmbeddedWorkflowRuntimeHost.java:195-197`；`DeviceGateway.java:52-69`）、独立 `labos-gateway`（`GatewayMain.java:59-60, 83-99`）、kernel 直连（`WorkflowService.java:1879-1891`）、两个 sidecar 各自持有 `TcpClient`。
- 命令状态：两张同名 `command_ledger`；Redis `labos:cmd:pending:*`（`GatewayMain.java:164-168`）；内存 pending（`DriverConnection.java:53`、`TcpPersistentDriver.java:78`、`SimBridgeTransport.cs:41`）；sidecar 内存幂等缓存（`IdempotentCache.cs:15`）。
- Gateway 三套：内嵌 `DeviceGateway`、独立 `labos-gateway`、sidecar `TcpServer:8501`；**彼此不互相调用**。

---

## 11. 指定组件速查表

| 组件 | 位置 |
|---|---|
| `GatewayDeviceTaskBridge` | `workflow-runtime/.../bridge/GatewayDeviceTaskBridge.java:34`（`:187-221` 只经 DB，不直连 TCP） |
| `TcpDeviceConnection` | `workflow-runtime/.../gateway/TcpDeviceConnection.java:56`（`:49-50` 无命令重发） |
| `DeviceCommandExecutionService` | `workflow-runtime/.../command/DeviceCommandExecutionService.java:29` |
| `command_ledger` | `pg_rebuild/schema/99f_command_ledger.sql:9` 与 `db/workflow_runtime/001_init.sql:423` |
| `SingleInstanceHostLock` | `workflow-runtime/.../host/SingleInstanceHostLock.java:22`（`:32-55` FileLock；V1 单主机） |
| `DeviceCore` | `labos-kernel/.../sim/DeviceCore.java:56`（`:666-697` 请求处理） |
| Sidecar 启动/恢复 | `sidecar-dotnet/Labos.Sidecar/Program.cs:47-175, 223-305`；`.../Manager/DeviceSupervisor.cs:149-185, 357-413` |
| Simulator 复用关系 | 共享帧格式，不共享接口（§7） |

---

## 12. 重写 / 保留 / 适配建议

### 12.1 保留为目标架构主干

1. **runtime 的 command_ledger + Outbox + Inbox + 启动恢复**：全仓唯一具备"先落 SEND_STARTED 再发、绝不盲目重发、inbox 幂等、fail-closed UNKNOWN"完整闭环。
2. **`DeviceGateway` + `TcpDeviceConnection`**：每设备隔离 IO/心跳/有界事件队列、epoch 绑定 PONG、EVENT_ACK/NACK、只恢复传输不重发命令——可直接作为唯一 TCP 主干。
3. **`DeviceDriver` 契约 + `DriverRegistry` 能力索引**：抽象方向正确。
4. **型号知识库**（`DeviceModels` / `DeviceProfile` / `DeviceBehavior` / `models.json`）与 sidecar 五种 `ITransport`：真实设备差异的承载处。
5. **`DeviceEventOutbox` / `DeviceEventForwarder`**：至少一次投递 + 幂等入站，设计正确。

### 12.2 重写或收编

1. legacy `command_ledger` + `CommandLedger` + `CommandOutbox` + `DriverStepExecutor` 直连下发段：与 runtime 版同名不同义，建议迁移或冻结为只读历史。
2. `labos-gateway` 独立进程：【推测】无生产生产者、无心跳、Redis 状态口径重复；建议删除或改为复用 runtime 传输/账本的薄适配层。
3. `TcpDriverAdapter` / `TcpPersistentDriver`：统一为一个到 canonical gateway 的适配器。
4. 老 gRPC sidecar 的 `IdempotentCache`：内存幂等崩溃失效，应移到持久层或删除该进程形态。
5. `DeviceSupervisor` 的 ps 扫进程 + 手动重拉：改为真正的 supervisor（自动重启 + 健康探针 + 状态对账）。

### 12.3 只是适配层（保留边界，不承载业务）

`GatewayDeviceTaskBridge`、`DeviceCommandAdapter`、`GatewayWireCodec` / `LabSepWireCodec` / `NdjsonWireCodec`、`RuntimeDeviceEventBridge`、`SimBridgeTransport`、各 `ITransport`、`DeviceEventForwarder`、`pylabrobot-sim`（需声明协议版本并复用型号画像）、`SingleInstanceHostLock`（若多实例需改分布式租约）。

### 12.4 明确标为推测的判断

- `labos-gateway` 未在生产链路使用；Sidecar Manager 无自动重启；三套栈是否同时在线未经运行时验证。
