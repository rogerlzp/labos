# 设备基础设施现状审计（DEVICE_INFRASTRUCTURE_AUDIT）

## 元信息

| 项 | 值 |
| --- | --- |
| 审计基线 | `origin/main` @ `c650e271`（2026-11-21 17:12） |
| 审计分支 | `docs/phase3a-audit-20261121` |
| 审计类型 | 只读（不改动生产代码、不触碰 `labdb`、不下发设备命令） |
| 路径口径 | 相对仓库根目录；引用格式 `path:line` |
| 审计执行 | Trae AI（DeepSeek-V4.1-Flash） |
| 生成时间 | 2026-11-21 |

---

## 0. TL;DR

1. **连接管理器至少 4 套并存**：`TcpPersistentDriver`、`TcpDriverAdapter`(内核)、`DriverConnection`(labos-gateway)、`TcpDeviceConnection`(workflow-runtime)，外加 .NET `TcpServer`。它们重复实现了分帧、写锁、pending 表。
2. **两套命令处理模型冲突**：内核/网关是**同步请求-响应**（pending 表配对）；新运行时是**事件驱动异步**（命令发出即返回，终态由 `DEVICE_EVENT` 异步送达）。二者之间需适配层转换。
3. **设备在线状态有 3 个互不相同的上报源**：Redis `labos:dev:state`（旧网关）、Redis `labos:link`（Sidecar）、运行时内部 `HealthSnapshot`（不落 Redis）。尚无统一设备状态模型。
4. **模拟器不复用真实 Driver**：`DeviceSimulator` 独立实现了 TCP 服务端、协议解析、命令处理，与真实驱动是两份代码（仅帧格式对齐）。
5. **无统一协议版本管理**：无 `protocol_version` 字段/协商机制。
6. **单实例由本地文件锁保证**，意味着 V1 的 Sidecar 必须与 Runtime 同机（与"混合式 C"目标存在张力，见契约文档 §6）。

---

## 1. TCP 连接实现枚举

| # | 实现 | 位置 | 重连 | 心跳 | 分帧 | 请求-响应配对 | 连接状态归属 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `TcpPersistentDriver` | `labos-kernel/src/main/java/com/labos/kernel/driver/tcp/TcpPersistentDriver.java:60` | 懒加载 `ensureConnected()`（`:209-244`） | TCP keepalive（`:217`） | `#!LABSEP!#`（`:65`、`:261-273`） | `pending` Map 按 `message_id`（`:78`） | 自身 `volatile Socket`（`:92`） |
| 2 | `TcpDriverAdapter` | `.../driver/tcp/TcpDriverAdapter.java:44` | 每次执行前检查（`:230-241`） | 依赖 TCP | `#!LABSEP!#`（`:47`） | execute 内轮询匹配（`:103-124`） | 自身 `healthy` 标志（`:70`） |
| 3 | `DriverConnection`(gateway) | `labos-gateway/src/main/java/com/labos/gateway/driver/DriverConnection.java:35` | 懒加载 `ensureConnected()`（`:79-106`） | TCP keepalive（`:87`） | `#!LABSEP!#`（`:37`） | `pending` 按 `commandId`（`:53`） | 自身 `volatile Socket`（`:59`） |
| 4 | `TcpDeviceConnection`(runtime) | `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/TcpDeviceConnection.java:56` | 指数退避 200ms→5s + epoch 隔离（`:130-132`） | **应用层** PING(5s)/超时(15s)（`:128-130`、`:348-352`） | 多编码 `GatewayWireCodec`（`:496-593`） | **不配对**，事件驱动 | 自身 `volatile status`（`:176`） |
| 5 | `TcpServer`(.NET) | `sidecar-dotnet/Labos.Sidecar/TcpServer.cs:22` | 监听 accept，每连接独立线程（`:58-72`） | 无（仅链路状态上报） | `Protocol.Sep`（`:127`） | 同步请求-响应（`:151-154`） | 每连接作用域内 `TcpClient`（`:121`） |
| 6 | `SimBridgeTransport`(.NET) | `sidecar-dotnet/Labos.Sidecar/SimBridgeTransport.cs:31` | 按需 | 无 | 透明转发 | 透明转发 | — |

发送串行化：`TcpPersistentDriver`(`writeLock :96`)、`DriverConnection`(`writeLock :54`)、`TcpDeviceConnection`(`ReentrantLock :164`)、`TcpServer`(单线程 `Serve`)。

---

## 2. 驱动层抽象与注册

- 契约接口：`labos-kernel/src/main/java/com/labos/kernel/driver/DeviceDriver.java:16`
  `describe() / execute(DriverCommand) / abort(commandId) / supportsPhysicalAbort() / queryStatus(commandId) / healthy()`。
- 声明：`DriverDescriptor.java`（`code/endpoint/protocol/driverKind/capabilities/deviceCapabilities/deviceInstanceIds/metadata`）。
- 注册表：`DriverRegistry.java:18` — 维护 `drivers / deviceToDriver / devicesByCapability / quarantined`。
- 能力口径：字符串集合（`Home/Move/Transfer/Mix/Centrifuge/Bind/Wash/GetStatus/...`）。
- 新运行时侧：`workflow-runtime/.../command/DeviceCommandAdapter.java:13`（`@FunctionalInterface send(DeviceCommandEnvelope)`，`SendStatus ∈ {ACCEPTED_ASYNC, COMPLETED_IMMEDIATELY, COMPLETED_IMMEDIATELY_FAILED, DELIVERY_UNCERTAIN}`）；`DeviceCommandExecutionService.java:29` 负责从 Outbox 到网关的生命周期。
- **协议适配分离度**：旧层把协议逻辑封装在各驱动内部（调用方只见 `DeviceDriver`），但没有独立的"Protocol Adapter"抽象；新层有 `GatewayWireCodec`（`LabSepWireCodec`/`NdjsonWireCodec`）。

---

## 3. 网关角色

| 网关 | 位置 | 角色 | 通信 |
| --- | --- | --- | --- |
| `labos-gateway` | `labos-gateway/src/main/java/com/labos/gateway/GatewayMain.java:46` | 独立进程；建长连接、从 Redis 队列消费命令、结果写回 Redis | Redis |
| `DeviceGateway`(runtime) | `workflow-runtime/.../gateway/DeviceGateway.java:30` | 单 JVM 内嵌；持久 TCP 连接池、设备级心跳、自动重连（**不重发命令**） | 同进程 SPI |
| `.NET Sidecar` | `sidecar-dotnet/Labos.Sidecar/TcpServer.cs:22` | 物理设备适配（串口/DLL/COM）；协议转换；自发帧转发；链路状态上报 | TCP + Redis |

> **风险**：若 `labos-gateway` 与 `DeviceGateway` 同时运行，会对同一批设备发起连接，产生**连接冲突**。

---

## 4. Sidecar（.NET）

- 入口：`TcpServer.cs`（`:22`）；协议：`Protocol.cs`；型号库：`ModelLibrary.cs:14` + `models.json`。
- 传输实现：`ITransport.cs` 及 `AspinClusterTransport / Gen5OleTransport / LhcRunnerTransport / S100SerialTransport / SimBridgeTransport`。
- 设备注册：`DeviceRegistry.cs:45`；事件转发 `DeviceEventForwarder.cs` + `DeviceEventOutbox.cs`；链路状态 `RedisLinkState.cs`。
- 进程管理：`Labos.Sidecar.Manager/DeviceSupervisor.cs`。
- 职责边界（现状）：Sidecar 负责到物理设备的连接与协议转换，**不决定 Workflow 是否推进**（业务持久化在 Kernel/Repository）。

---

## 5. 模拟器

- Java：`labos-kernel/src/main/java/com/labos/kernel/sim/DeviceSimulator.java:41`、`DeviceCore.java:56`、`DeviceRegistry.java`、`DeviceProfile.java`、`*Behavior.java`（Aspin/Gen5/Lhc/S100）。
- .NET：`sidecar-dotnet/Labos.Sidecar/SimBridgeTransport.cs:31`（连到 Java 模拟器，帧透明转发）。
- Python：`pylabrobot-sim/`。
- **关键发现**：模拟器**不与真实 Driver 共享代码**，只对齐有线帧格式（`#!LABSEP!#`、`message_id/message_type/method_status`）。同一 Driver 在真机/模拟器间**不存在"两套业务逻辑"**是目标，但当前模拟器是独立实现，协议忠实度靠约定维持。

---

## 6. 连接/命令状态职责矩阵

| 责任 | TcpPersistentDriver | DriverConnection | TcpDeviceConnection | Sidecar |
| --- | --- | --- | --- | --- |
| 连接管理 | 是 | 是 | 是 | 是 |
| 心跳 | TCP keepalive | TCP keepalive | 应用层 PING/PONG | 无（链路状态上报） |
| 重连 | 懒加载 | 懒加载 | 指数退避 + epoch | 发送失败重建 |
| 请求配对 | pending(message_id) | pending(commandId) | 不配对（事件驱动） | 同步 |
| 命令状态 | 本地 | Redis pending | PostgreSQL `command_ledger` | 本地当前请求 |
| 设备状态 | `healthy()` | Redis `labos:dev:state` | 内部 `HealthSnapshot` | Redis `labos:link` TTL |

---

## 7. 协议与版本管理

**结论：当前无统一的设备协议版本机制，也无统一设备状态模型。** 证据：

1. 有线协议中无 `protocol_version` 字段；`GatewayWireProtocol.java` 仅定义编解码，无协商。
2. 设备状态分散在 Redis（`labos:link`、`labos:dev:state`）与运行时内部枚举，PostgreSQL 侧 `device_state` 表承载的是 Kernel 旧路径状态（`pg_rebuild/schema/30_business.sql:459-469` + `96_schema_fix.sql:92-94` 补列 `online/last_heartbeat/current_method`），与运行时侧未打通。

---

## 8. 重复/冲突发现（供重构切分）

- **C1 连接管理四处重复**：4 套 TCP 连接器各自实现分帧/写锁/pending，重连与心跳策略不一致。
- **C2 设备在线状态三源**：Redis `labos:dev:state`（网关）/ Redis `labos:link`（Sidecar）/ `HealthSnapshot`（运行时）。同一设备可能被三处给出不同判断。
- **C3 命令模型冲突**：同步请求-响应 vs 事件驱动异步，需适配层。
- **C4 双网关连接冲突**：`labos-gateway` 与 `DeviceGateway` 同设备竞争。
- **C5 自发帧路径未端到端统一**：Sidecar 自发帧 → 内核 vs → DeviceGateway 的路径未统一测试。
- **C6 模拟器与真机实现分叉**：模拟器独立实现，协议漂移风险。

---

## 9. 逐条回答审计前提出的 10 个问题

| # | 问题 | 结论 |
| --- | --- | --- |
| 1 | 现在 TCP Client 有几套？在哪里？ | **4 套 Java + 1 套 .NET**（见 §1） |
| 2 | Driver 按厂商还是型号划分？ | 目前按"设备/型号"以能力字符串划分（`DriverRegistry` + `DeviceModels`），非严格厂商分层；协议适配未独立成层 |
| 3 | DeviceCore/Gateway/Sidecar 真实调用关系？ | Sidecar(.NET) 连物理设备；`DeviceGateway`(runtime) 连 TCP 设备；`labos-gateway` 走 Redis；`DeviceCore` 仅模拟器内部 |
| 4 | 设备命令由谁持久化？ | 内核旧路径 `command_ledger`(`pg_rebuild/schema/99f_command_ledger.sql:9-37`) + `command_outbox`(`99n_command_outbox.sql:7-28`)；运行时路径 `workflow_runtime.command_ledger`(`db/workflow_runtime/001_init.sql:423-450`) |
| 5 | 断线后谁负责重连？ | 各连接器各自重连（§1）；运行时还有 `StartupRecovery`/`WorkerExecutionRecoveryService` 做进程级恢复 |
| 6 | Sidecar 崩溃后命令状态如何恢复？ | 靠 Kernel 侧 `command_ledger` 对账；Sidecar 自身无常驻命令状态 |
| 7 | Simulator 是否复用真实 Driver？ | **否**，独立实现（§5） |
| 8 | 设备协议是否统一版本管理？ | **无**（§7） |
| 9 | 设备状态是否有统一模型？ | **无**，三源分散（§6、§8-C2） |
| 10 | 是否存在三处重复管理连接/命令？ | **是**（§8-C1/C2/C3） |

---

## 10. 差距与不确定性

1. 未逐一核实每个连接器的**超时/取消**语义在并发下的等价性（仅静态阅读）。
2. `device_state` 与运行时 `HealthSnapshot` 的**同步关系**未找到桥接代码，需在契约阶段确认权威源。
3. `labos-gateway` 是否仍在生产启用、与 `DeviceGateway` 的**切换策略**未知。
4. Sidecar `.NET` 侧 `DeviceSupervisor` 依赖 Linux `ps`，Windows 支持受限（需在部署阶段核对）。
5. 未执行任何运行时探测（本轮为只读静态审计）。
