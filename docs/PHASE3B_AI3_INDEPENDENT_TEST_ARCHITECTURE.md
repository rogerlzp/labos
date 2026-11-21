# PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE — AI-3 独立模拟与混沌验收架构（Phase 0 交付 · Draft v0.1 · 2026-11-21）

> **责任人**：AI-3（辅助 1 · Simulator & Chaos · 独立模拟与对抗验收）
> **独立分支**：`feat/phase3-device-simulator-chaos`
> **所属总纲**：[PHASE3B_MASTER_CONTRACT.md](./PHASE3B_MASTER_CONTRACT.md)（§2.2 四 AI 权限、§3 五阶段门禁）
> **对侧被测契约**：[DEVICE_EXECUTION_CORE_CONTRACT.md](./DEVICE_EXECUTION_CORE_CONTRACT.md)（AI-1）· [DEVICE_GATEWAY_PROTOCOL.md](./DEVICE_GATEWAY_PROTOCOL.md)（AI-2）
> **目标 DDL**：[006_device_execution_contract_v1.draft.sql](./ddl/006_device_execution_contract_v1.draft.sql)
>
> **阶段定位**：本文件即 [PHASE3B_MASTER_CONTRACT.md §3](./PHASE3B_MASTER_CONTRACT.md) 中 AI-3 的**阶段 0 交付物「准备独立测试架构」**。
> **重要边界**：阶段 0 的 PG17 实际验证仍为 `UNKNOWN`（契约文档已 PASS，实跑未发生）。故**本文件只冻结 AI-3 的测试架构、独立性红线、混沌矩阵与验收规范，不交付任何生产代码**；代码骨架从阶段 1 起在 `feat/phase3-device-simulator-chaos` 分支落地。
> **后续归并**：本文件为拟议的 `PHASE3_AI_WORK_ASSIGNMENTS.md` 的 AI-3 章节，待该分工文档建立后原样并入。

---

## 0. 定位、独立性铁律与红线

### 0.1 AI-3 的核心交付
AI-3 不生产被验收对象的代码，只交付**可信的对抗证据**：证明系统在正常流程之外——断连、崩溃、重放、伪造、并发竞争——仍然**遵守物理安全契约**。核心交付物是：双层模拟器 + Wire Chaos Harness + 故障注入矩阵 + 跨模块黑盒验收（四态裁决 + 原始证据）。

### 0.2 三条不可让渡的铁律
1. **独立性**：模拟器与 Chaos Harness **严禁 import / 复用 AI-2 的生产协议实现**（`workflow-runtime/.../gateway/**`、`sidecar-dotnet/**`）与 **严禁复用生产 Driver 内部逻辑**（`TcpDriverAdapter` / `TcpPersistentDriver` / 任何 `IProtocolAdapter` 生产实现）。二者只允许共享**冻结的** Wire 契约与 [contract-fixtures/v1_1/](#7-跨语言黄金样例对齐只读消费) 黄金报文。理由：若协议两端共用同一份实现，双方同一个错误会互相抵消，测试形同虚设。
2. **不改生产代码**：发现缺陷时，AI-3 只提交**最小复现 + 原始日志 + 失败用例 + 期望值**，由 AI-1（Core）或 AI-2（Gateway/Sidecar）修复；AI-3 随后**独立回归**，绝不代改被测代码换取绿灯。
3. **四态裁决**：AI-3 的结论只能是 `PASS` / `FAIL` / `BLOCKED` / `UNKNOWN`（定义见 [§8](#8-四态裁决与证据交付规范)）。**严禁**把 `skipped`、未执行、模拟器自测通过、文档核对、单模块自测算作端到端 `PASS`。

### 0.3 排他目录（引自 [MASTER §2.2](./PHASE3B_MASTER_CONTRACT.md)）
| 目录 | 用途 |
| :--- | :--- |
| `workflow-runtime/src/test/java/com/labos/kernel/workflow/runtime/chaos/**` | 跨模块黑盒/混沌集成测试 |
| `simulators/device-virtual-sim/**` | 设备物理行为虚拟模拟器（独立工程） |
| `scripts/test-phase3d-*` | 阶段 2（3D 混沌联调）可复现驱动脚本 |
| `scripts/verify-phase3-*` | 独立验收与证据采集脚本 |

### 0.4 AI-3 禁止事项
- 禁止修改 `WorkflowRuntimeRepository.java`（该文件的三处切片为 **AI-1 定点独占**）。
- 禁止修改公共契约与 Schema：`device/contract/**`、`contract-fixtures/**`、`002_schema_contract.sql`、`006` 正式 DDL、`boot/**`（**只读消费**，如需变更须提 RFC）。
- 禁止修改 `workflow-runtime/.../gateway/**`、`sidecar-dotnet/**`（AI-2 主责）。
- 禁止自行签发工艺验收 `PASS`（工艺验收需 AI-4 定义场景 + AI-3 黑盒执行 + 集成负责人裁决）。

---

## 1. 双层模拟器架构（独立性设计）

用**两个物理上分离的替身**覆盖 [DEVICE_GATEWAY_PROTOCOL.md §5](./DEVICE_GATEWAY_PROTOCOL.md) 定义的两个边界，二者均不得引用被测实现：

| 层 | 替身 | 部署边界 | 独立实现要求 |
| :--- | :--- | :--- | :--- |
| **A** | **Wire-Level Chaos Harness** | **替代 Sidecar Server**：直接监听 `workstationId` 约定端口，承接 Kernel `DeviceGateway` 的方向 A 拨号 | 自写 NDJSON v1.1**独立解析/编码器**（13 帧，见 §2.2）；自管 TLS 1.3 mTLS 客户端证书与 Lab CA 测试链；**不链接** `TcpDeviceConnection` / `DeviceGateway` 任何类 |
| **B** | **Device-Level Virtual Simulator** | **挂载于 `.NET 10` Sidecar `IProtocolAdapter` 之下**，模拟真机串口/TCP/DLL 接口 | 自写设备状态机（§3）；**不引用**生产 Adapter 实现；由 AI-3 维护 |

### 1.1 为什么必须两层（反模式警示）
- A 层只验**协议与 Core 的对账逻辑**（Wire 语义、证据强度、去重、Outbox 重传）；B 层只验**物理执行语义**（真执行/中断/回执丢失/状态不可确认）。
- 若只用 A 层（协议模拟器）验物理完成，就会把「协议说成功」错当成「硬件真做过」——这正是契约 P0-3 要根除的漏洞。
- 若 B 层复用 AI-2 的 Adapter 生产实现，则 Sidecar 侧真值由被测方提供，物理行为真值不可信。
- 因此**协议真值（A）与物理真值（B）必须各自独立来源**，在 [§5](#5-6-条不变式的黑盒验证映射) 的同一场景里交叉断言。

### 1.2 三层「真值」分离原则
1. 帧字节真值：来自 A 层独立编解码器；
2. 物理行为真值：来自 B 层独立设备状态机；
3. 契约期望真值：来自冻结契约条文 + `contract-fixtures/v1_1/*.json`。
任一不一致即视为**被测方缺陷或契约漂移**，按 §8 判 `FAIL` 或 `BLOCKED`，不得自行“以测试代码迁就实现”。

---

## 2. Wire Chaos Harness 能力清单（A 层）

### 2.1 TLS 1.3 mTLS 与会话边界（对齐 [GATEWAY §1](./DEVICE_GATEWAY_PROTOCOL.md)）
| 编号 | 注入 | 期望收敛 |
| :--- | :--- | :--- |
| CH-TLS-01 | 非 loopback 使用 `DEV_LOOPBACK_PLAINTEXT` | 启动/拨号被拒（`IllegalArgumentException`），绝不建连 |
| CH-TLS-02 | Sidecar 证书由非 Lab CA 签发 | Kernel 拒绝握手 |
| CH-TLS-03 | Sidecar 证书 SAN `workstationId` 与拨号目标不一致 | 拒绝 + `ERR_SESSION_IDENTITY_MISMATCH` |
| CH-TLS-04 | Kernel 证书 `kernelId` 不在 `allowedKernelIds[]` | Sidecar 拒绝握手 |
| CH-TLS-05 | 吊销指纹（`revoked_cert_fingerprints.txt`） | 新握手拒绝 + **当场踢断既有同指纹长连接** |
| CH-TLS-06 | 握手/认证完成前灌入应用帧字节 | 连接层直接断开，帧**绝不进入**命令解析路径（[GATEWAY §1.2 栅栏 3]） |
| CH-TLS-07 | 尝试 0-RTT / Early Data 语义 | 运行库能力边界内不处理 Early Data（版本/配置基线审计） |
| CH-SESS-01 | 携带旧 `connectionGeneration` 的滞后心跳/回执 | 一概丢弃（[GATEWAY §1.3]） |
| CH-SESS-02 | 同工位第二连接以更高代次拨入 | 旧套接字被替换关闭，无双连接并存 |
| CH-SESS-03 | `HELLO_ACK.devices[]` 含越权/跨工位 `deviceId` | `ERR_SESSION_DEVICE_CONFLICT` 断连停摆，不污染路由表 |

### 2.2 帧级注入（对齐 [GATEWAY §3](./DEVICE_GATEWAY_PROTOCOL.md) 13 帧）
`HELLO` / `HELLO_ACK` / `HEARTBEAT_PING` / `HEARTBEAT_PONG` / `COMMAND_SEND` / `COMMAND_RECEIPT` / `COMMAND_QUERY` / `COMMAND_QUERY_RESULT` / `COMMAND_CANCEL` / `DEVICE_EVENT` / `EVENT_ACK` / `EVENT_NACK` / `DEVICE_STATE`。

| 编号 | 注入 | 期望收敛 |
| :--- | :--- | :--- |
| CH-WIRE-01 | 丢弃 `COMMAND_RECEIPT` 后重连 | Kernel 按 `ReconciliationPolicy` 用新 `dispatchId` 发起 `COMMAND_QUERY` 兜底，不盲重发 |
| CH-WIRE-02 | 缺失 `protocolVersion` / 非 JSON / 混入 `#!LABSEP!#` 分隔协议帧 | `ERR_WIRE_UNSUPPORTED_VERSION` / `ERR_WIRE_MALFORMED_FRAME` → NACK + 断开 |
| CH-WIRE-03 | 截断半包 / 超大帧 / 换行缺失 | 解析为 malformed，拒绝且不残留帧状态 |
| CH-WIRE-04 | 篡改/重放 `COMMAND_RECEIPT`（改 `dispatchId`） | 不触发二次派发 |
| CH-WIRE-05 | `FAILED_IMMEDIATELY` 缺 `failureBoundary` 或传 `POST_EXECUTION_UNKNOWN` | 拒绝判普通失败 → `UNKNOWN` + 隔离（[CORE §2.3-1]） |
| CH-WIRE-06 | `COMMAND_RECEIPT(COMPLETED_IMMEDIATELY)` 无 `STRONG_HARDWARE_FACT` | 结构性拒收 → `UNKNOWN` |
| CH-WIRE-07 | `EVENT_ACK` 丢失后 Sidecar 重启重发同 `sourceMessageId` | 命中 `inbox_receipt` 唯一键 → `EVENT_ACK(DUPLICATE)`，**零重复消费** |

### 2.3 证据伪造与跨 ID 越权（P0-3 防伪红线核心）
| 编号 | 注入 | 期望收敛 |
| :--- | :--- | :--- |
| CH-FAKE-01 | `COMMAND_QUERY_RESULT` 以 `WEAK_UNVERIFIED` 报 `CONFIRMED_SUCCEEDED` | Core 拒收 → `UNKNOWN` + `quarantine_record` |
| CH-FAKE-02 | `storageState='FRESH_STORAGE'` 下报 `CONFIRMED_NOT_EXECUTED` | 拒收 → `UNKNOWN`（[CORE §2.3-5 硬边界③]） |
| CH-FAKE-03 | 线协议帧携带 `OPERATOR_PHYSICAL_INSPECTION` 或 `STRONG_OPERATOR_ATTESTED` | 当场 `ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE`（`NACK_PERMANENT`）（[GATEWAY §2 防伪红线]） |
| CH-FAKE-04 | `DEVICE_EVENT` 篡改 `deviceId` 冒充他设备 | `ERR_WIRE_SPOOFED_DEVICE`，会话白名单硬校验拦截 |
| CH-FAKE-05 | 伪造 `deviceSequence` 单独充当成成功凭证 | `deviceSequence` 仅作关联句柄；缺强证据即拒收 |
| CH-FAKE-06 | 崩溃窗口后 `supportsNativeHardwareQuery=false` 仍强行自证成功 | 必须 `AMBIGUOUS` / `WEAK_UNVERIFIED` / `INDETERMINATE`（[GATEWAY §4.3]） |

### 2.4 可靠性不对称与顺序（N5 · P1-5）
| 编号 | 注入 | 期望收敛 |
| :--- | :--- | :--- |
| CH-ORD-01 | 单 `deviceId` 连续注入乱序 `DEVICE_EVENT` | Kernel 侧按 FIFO 与 `sourceMessageId` 去重，语义不被乱序破坏 |
| CH-ORD-02 | 不同 `deviceId` 事件交错 | 互不阻塞（无单机阻塞全工位） |
| CH-ORD-03 | `EVENT_NACK(retryable=true)` 后退避重发 | 同 `sourceMessageId` 重发被幂等吸收 |
| CH-ORD-04 | `EVENT_ACK` 前 PG 回滚 | 网关回 `EVENT_NACK(retryable=true)`，**绝不发 `EVENT_ACK`**（[CORE §7 Invariant-6]） |

---

## 3. Device Virtual Simulator 物理行为模型（B 层）

### 3.1 四层链路完整性
被测链路：`Kernel → DeviceGateway → .NET Sidecar → VirtualSimulator`。B 层模拟真机接口，支持两类能力声明**正交**（对齐 [GATEWAY §2]）：
- `supportsJournalQuery`（Sidecar 自身能力）；
- `supportsNativeHardwareQuery`（设备本体物理能力，如 `P_AUTO` 有任务寄存器 / 老式串口无任务流水表）。

### 3.2 必须建模的四类物理行为
| 行为 | 语义 | 关键验收 |
| :--- | :--- | :--- |
| (a) 真执行并回执 | 完整执行 + 原生状态可查 | `STRONG_HARDWARE_FACT` 正当成立 |
| (b) 执行中断 | 中途停机/断电/异常 | 不误判为成功；进 `UNKNOWN` + 隔离 |
| (c) 执行成功但回执丢失 | 硬件已动作、回执丢 | 靠 `COMMAND_QUERY` 查证，**绝不允许**非幂等动作被重发 |
| (d) 状态无法确认 | 无原生查询接口 + 崩溃窗口 | 诚实 `INDETERMINATE` → `UNKNOWN` → T21 |

### 3.3 建模范围（Stage 3E-2 扩展）
- 阶段 1–2：`P_AUTO-1..4`、`DISP1..6`、`ARM1`、`STACK1`、`S100`、`ASpin`（满足 `EXT-STD-SINGLE-V1`）。
- 阶段 3E-2：`LHC`、`Gen5` 及 4 机并行资源（`rail:` / `turntable:`）。
- 通用能力：真实状态机 + 分段延时 + 自发吐出 `waiting_interaction` + 执行故障 + 板位/门控/`DISP2` 搅拌器前置校验。

---

## 4. 故障注入矩阵（Chaos Matrix）

覆盖 [MASTER §3 阶段 2](./PHASE3B_MASTER_CONTRACT.md) 要求的 8 类注入，逐项给出观察面与对账依据：

| 维度 | 注入点 | 触发手段 | 期望系统收敛 | 对账不变式 |
| :--- | :--- | :--- | :--- | :--- |
| 网络断连 | TCP RST / 静默断链 | A 层强制关闭套接字 | 连接 `DEGRADED`/`OFFLINE`，阻断派发，不产生孤儿命令 | Inv-4 / Inv-6 |
| 写黑洞 | 只收不回 | A 层吞掉回包 | `receiptTimeoutMs` → `DELIVERY_UNKNOWN` → 有限 QUERY | Inv-5 |
| 丢回执 | 丢 `COMMAND_RECEIPT` | A 层丢弃指定帧 | 重连后 QUERY 兜底（新 `dispatchId`） | Inv-5 |
| 重包/重放 | 重复帧 / 重放事件 | A 层复制帧 | `inbox_receipt` 唯一键幂等吸收，零重复消费 | Inv-6 |
| Sidecar WAL 崩溃 | 收单后杀进程 | 杀 `.NET` Sidecar | 重启后按 Journal 恢复，不重复非幂等物理动作 | Inv-2 / Inv-5 |
| 磁盘清空 | `FRESH_STORAGE` / `CORRUPTED` | 清数据目录重启 | 在途命令强制 `UNKNOWN` + 隔离，不自动清除隔离 | Inv-3 / Inv-5 |
| 错证/跨 ID | 伪造证据 / 篡改 `deviceId` | A 层注入 | 结构性拒收 / 会话拦截 | Inv-2 |
| 早到事件并发 | 同 `sourceMessageId` 并发 N 线程 | 并发注入脚本 | 全局仅 1 条权威收据且仅消费 1 次；无归属 Run → `ERR_EVENT_UNROUTABLE` | Inv-6 |
| 资源隔离竞争 | 并发派发 vs T21 解冻 | 并发驱动 + `pg_locks` 观察 | 零死锁（统一先锁 `workflow_run`），整组锁解冻 | Inv-4 |

---

## 5. 6 条不变式的黑盒验证映射

对齐 [CORE §7](./DEVICE_EXECUTION_CORE_CONTRACT.md#7-阶段-0-骨架-pr-必附6-条核心契约不变式测试清单n7)。AI-3 只做**黑盒观察**，不读被测私有实现：

| 不变式 | AI-3 黑盒场景 | 黑盒观察面 | 四态 |
| :--- | :--- | :--- | :--- |
| **Inv-1** `ACKNOWLEDGED != SUCCEEDED` | 注入 `COMMAND_RECEIPT(ACCEPTED)` | 观察 `command_ledger.command_state` 停于 `ACKNOWLEDGED`；`node_attempt` 未结算；Token 未前移 | PASS/FAIL |
| **Inv-2** 硬件背书防伪 & 线协议禁人工伪证 | CH-FAKE-01/02/03/06 | `command_ledger` 未出现无强证据的 `SUCCEEDED`；违禁证据帧被 `NACK_PERMANENT` | PASS/FAIL |
| **Inv-3** `FAILED_IMMEDIATELY` 双边界隔离 | CH-WIRE-05 | `PRE_EXECUTION` → `FAILED` 且无隔离单；`POST_EXECUTION_UNKNOWN` → `UNKNOWN` + `ACTIVE` 隔离全影响资源 | PASS/FAIL |
| **Inv-4** `PhysicalSafetyGate` 全局锁序 | 并发派发 + T21 解冻压测 | 零死锁（`pg_locks`/死锁检测）；`Transfer` `UNKNOWN` 整组锁 `device:ARM1`+`slot:src`+`slot:dst`+`carrier:id` | PASS/FAIL |
| **Inv-5** `recoverInterruptedAttempt` 终态不误杀 + T21 授权解冻 | 重启恢复 + T21 提交 | Step 0 已落 `SUCCEEDED` 的命令重启后直接推进成功，**不**进 `MANUAL_REQUIRED`；真实 `UNKNOWN` 经 T21 合法转 `SUCCEEDED` 并释放 `scheduler_capacity` | PASS/FAIL |
| **Inv-6** `inbox_receipt` 并发全局唯一去重 + 禁猜 Run + 提交栅栏 | 并发注入同一 `sourceMessageId`；无归属工位事件 | 全局仅 1 条权威收据；`ERR_EVENT_UNROUTABLE`；PG 回滚时回 `EVENT_NACK(retryable=true)` 且无 `EVENT_ACK` | PASS/FAIL |

> 每条不变式的判定必须同时给出：**注入序列**、**原始日志**、**黑盒 SQL 观察结果**、**契约条文引用**。缺任一项即降级为 `UNKNOWN`。

---

## 6. 数据库与状态观察点（黑盒可观测面）

AI-3 只读观察，不修改 Schema、不构造生产数据：

| 观察对象 | 用途 |
| :--- | :--- |
| `workflow_runtime.command_ledger` | 12 态迁移、`reconciliation_evidence` 落库、`station` 绑定 |
| `workflow_runtime.command_dispatch_attempt` | `dispatch_kind`(SEND/QUERY/CANCEL)、`dispatch_no` 唯一性 |
| `workflow_runtime.inbox_receipt` | 去重唯一键、`message_type`（含 `DEVICE_SPONTANEOUS_EARLY`）、`receipt_state` |
| `workflow_runtime.wait_early_signal_buffer` | 1:1 引用与 `run_id` 强一致、`buffer_state` |
| `workflow_runtime.quarantine_record` | 隔离开启/整组解锁、`quarantine_origin` |
| `workflow_runtime.resource_lease` | 租约 `HELD/RELEASED`、字典序加锁 |
| `workflow_runtime.device_state_projection` | `device_state`、`active_command_id` |
| `pg_locks` / 死锁日志 | 锁序与零死锁证据 |

**并发证据**：以可复现并发脚本（`scripts/test-phase3d-*`）驱动，采集 `pg_stat_activity` + `pg_locks` 快照 + PostgreSQL 死锁检测日志作为 `PASS` 佐证。

---

## 7. 跨语言黄金样例对齐（只读消费）

- 唯一真值源：仓库根中立目录 `contract-fixtures/v1_1/*.json`（Java Maven 与 `.NET 10` 双方直读同一组文件）。
- AI-3 角色：**只读对齐校验方**——用 A 层独立编解码器对同一组 Fixtures 做序列化/反序列化等价断言；**漂移即 Fail-Closed**（判 `FAIL` 或 `BLOCKED` 并上报集成负责人）。
- 枚举一致性硬校验：`evidenceSource` 5 值（`HARDWARE_NATIVE_QUERY` \| `PRIOR_HARDWARE_COMPLETION_IN_JOURNAL` \| `PRE_SEND_ABORT_IN_JOURNAL` \| `OPERATOR_PHYSICAL_INSPECTION` \| `AMBIGUOUS`）与 `evidenceStrength` 4 档（`STRONG_HARDWARE_FACT` \| `STRONG_PRE_SEND_ABORT` \| `STRONG_OPERATOR_ATTESTED` \| `WEAK_UNVERIFIED`）必须与 [CORE §1.1](./DEVICE_EXECUTION_CORE_CONTRACT.md#11-结构化执行证据模型executionevidence解决-p0-3) / [GATEWAY §2](./DEVICE_GATEWAY_PROTOCOL.md) 完全一致。

---

## 8. 四态裁决与证据交付规范

### 8.1 四态定义（强制）
| 态 | 判据 |
| :--- | :--- |
| `PASS` | 有可复现证据（注入脚本 + 原始日志 + 黑盒观察）且与契约期望完全一致 |
| `FAIL` | 存在已复现缺陷（含契约期望与实测冲突） |
| `BLOCKED` | 依赖条件不具备（如某帧未实现、环境缺失、上游未交付） |
| `UNKNOWN` | 证据不足，不能判断（含 skipped、未执行、仅模拟器自测、仅文档核对） |

### 8.2 严禁计入 `PASS` 的情形
`skipped` / 未执行 / 模拟器自测通过 / 文档核对 / 单模块自测 / 无原始日志的“跑通” / 依赖被测实现自证。

### 8.3 统一交付清单（AI-3 每次交付必附）
- 分支名、基线 SHA、最终 HEAD SHA；
- 模拟器/Harness/脚本完整文件清单；
- 每个混沌场景的注入序列与期望值；
- 原始日志（未裁剪）与黑盒 SQL 观察结果；
- PostgreSQL 17 证据（涉及 DB 时）；
- 异常/负向场景清单；
- **对 AI-1 / AI-2 的缺陷交接单**（最小复现 + 复现命令 + 期望 vs 实测）；
- 已知限制、未完成项与 `UNKNOWN` 明示；
- 四态总表。

### 8.4 缺陷交接闭环
`AI-3 发现 → 提交最小复现 + 日志 + 失败用例给 AI-1/AI-2 → 对方修复并注明提交 SHA → AI-3 在独立分支回归 → 更新四态`。本闭环中 AI-3 **不得**自行修改被测代码。

---

## 9. 阶段门禁与排期（AI-3 视角）

| 阶段 | AI-3 交付 | AI-3 可执行退出门禁 |
| :--- | :--- | :--- |
| **阶段 0（当前）** | 本架构方案 + 排他目录规划 + 探针/驱动脚本清单 + 缺陷交接模板 | 集成负责人认可本架构；**不产出生产代码** |
| **阶段 1** | 建双层模拟器骨架；TLS 握手栅栏测试（CH-TLS-*） | A 层独立编解码器通过 `contract-fixtures/v1_1` 对齐；CH-TLS-01..07 有原始证据 |
| **阶段 2** | 混沌矩阵全量（CH-WIRE / CH-FAKE / CH-ORD + 崩溃/并发） | 6 条不变式 + 混沌压测 100% 收敛，零误判 `CONFIRMED_SUCCEEDED`（[MASTER §3 阶段 2]） |
| **阶段 3** | `EXT-STD-SINGLE-V1` / `EXT-STD-BATCH4-V1` 黑盒执行验收 | 正常 + 注入故障恢复场景连续跑通，产出原始日志 |
| **阶段 4** | 跨模块独立黑盒验收 + 四态总报告 | 全项 `PASS` 后交集成负责人独立审计覆盖率/日志/SHA 并裁决 |

**阶段 0 前置约束**：AI-3 的阶段 1 代码工作**必须**等阶段 0 骨架 PR 合入并拿到统一 `origin/main` SHA 后才启动（当前契约文档 PASS、PG17 实跑仍 `UNKNOWN`）。

---

## 10. 目录与交付物清单（阶段 1 起）

```
simulators/device-virtual-sim/**                                  # B 层：设备物理行为模拟器（独立工程）
workflow-runtime/src/test/java/com/labos/kernel/workflow/runtime/chaos/**   # 跨模块黑盒/混沌测试
scripts/test-phase3d-*                                            # 阶段 2 混沌联调可复现驱动
scripts/verify-phase3-*                                           # 独立验收与证据采集
```

交付物：模拟器工程、Chaos Harness、故障注入脚本、黑盒验收剧本（AI-4 定义 + AI-3 执行）、四态报告、缺陷交接单。

---

## 11. 风险与开放问题（待集成负责人裁决）

| 编号 | 问题 | 影响 |
| :--- | :--- | :--- |
| OQ-1 | A 层 Chaos Harness 是否需与 B 层共用设备能力注册数据源？若共用，须确保该数据源来自**冻结契约**而非 AI-2 运行时实现 | 影响独立性成立性 |
| OQ-2 | `.NET 10` 侧模拟真机接口需 AI-2 提供 `IProtocolAdapter` **接口签名冻结快照**（只读，不含实现） | 阻塞阶段 1 B 层 |
| OQ-3 | 阶段 2 并发压测的「零死锁」判定阈值与观测窗口需集成负责人定义 | 影响 Inv-4 判定口径 |
| OQ-4 | `contract-fixtures/v1_1/*.json` 的冻结版本号与漂移检测方式（哈希清单） | 影响漂移 Fail-Closed 实现 |
| OQ-5 | AI-3 独立回归是否需在**独立 PG17 隔离库**执行（避免污染其他 AI 的库） | 影响环境隔离要求 |
