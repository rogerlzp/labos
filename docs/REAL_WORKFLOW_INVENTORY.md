# REAL_WORKFLOW_INVENTORY — LabOS 真实工艺流程盘点与标准提取工艺规划

> **审计基线**：代码主线 `origin/main` @ `c650e271`（只读源码基线）；生产库 `192.168.124.2:5432/labdb`（以 PostgreSQL `default_transaction_read_only=on` 只读采集，零写入）。

---

## 1. 生产库现存有效流程全集（共 26 条）

按工位（`island_id`）与用途分类如下：

| 分类 | Workflow ID | Code | 名称 | Island ID | XML 大小 | `__awaitEvent` | `receiveTask` | 并行网关 | XOR | 说明 |
|---|---|---|---|---|---:|:---:|:---:|:---:|:---:|---|
| **提取岛·真实工艺** | `1001017` | `EXT-WORKFLOW-02` | 高通量核酸提取工作站 · 单批标准流程 | `1001890` | 51,326 B | **是 (8)** | 否 | **是 (4)** | 否 | 参考工艺样本（不做旧流程迁移，见第 6 节） |
| **提取岛·真实工艺** | `1001041` | `EXT-WORKFLOW-SINGLE` | 高通量核酸提取工作站 · 单样品板标准流程（P_AUTO-1） | `1001890` | 66,805 B | **是 (6)** | 否 | 否 | 否 | 参考工艺样本（不做旧流程迁移，见第 6 节） |
| **提取岛·Phase 2B 验收** | `1001040` | `BASELINE-SERPAR-01` | 基准流程 · 串行+并行 | `1001890` | 7,563 B | 否 | 否 | 是 (2) | 否 | 新引擎已跑通（5 步基准） |
| **提取岛·Phase 2B 验收** | `1001042` | `PHASE2B-EXT-G5-WAIT-01` | Phase2B · 提取工作站 G5 Wait 联调流程 | `1001890` | 2,421 B | 否 | 是 | 否 | 否 | 新引擎 G5 Signal 已跑通 |
| **提取岛·Phase 2B 验收** | `1001044` | `PHASE2B-EXT-G5-TIMEOUT-01` | Phase2B · 提取工作站 G5 Timeout 分支流程 | `1001890` | 2,736 B | 否 | 是 | 否 | 否 | 新引擎 G5 Timer 超时分支已跑通 |
| **提取岛·Phase 2B 验收** | `1001045` | `PHASE2B-EXT-G5-RESTART-WAIT-01` | Phase2B · 提取工作站重启持久 Wait 流程 | `1001890` | 1,825 B | 否 | 是 | 否 | 否 | 新引擎 SIGKILL 跨重启恢复已跑通 |
| **质粒岛·真实工艺** | `1001032` / `1001035` | `PLASMID-WF/CHAIN-01-DILUTE` | 质粒构建 · ①质粒稀释 | `1000836` | ~11 KB | 否 | 否 | 否 | 否 | 12 步纯串行（扫码+搬运+撕膜+稀释），无 `__awaitEvent` |
| **质粒岛·真实工艺** | `1001004` / `1001036` | `PLASMID-WF/CHAIN-02-PCR` | 质粒构建 · ②PCR扩增 | `1000836` | ~12–14 KB | 否 | 否 | 否 | 否 | 12 步纯串行（扫码+搬运+封膜+PCR），无 `__awaitEvent` |
| **质粒岛·真实工艺** | `1001033` / `1001037` | `PLASMID-WF/CHAIN-03-PURIFY` | 质粒构建 · ③扩增后纯化 | `1000836` | ~14 KB | 否 | 否 | 否 | 否 | 纯串行，无 `__awaitEvent` |
| **质粒岛·真实工艺** | `1001034` / `1001038` | `PLASMID-WF/CHAIN-04-DIGEST` | 质粒构建 · ④酶切 | `1000836` | ~14 KB | 否 | 否 | 否 | 否 | 纯串行，无 `__awaitEvent` |
| **测试/演示流程** | `1000661`–`1000930` (11 条) | `AUTO-*` | S2 探针 / 门控 / Demo / 缩放验证 | `1000005` / 空 | 0.6–18 KB | 否 | 否 | 部分 | 部分 | 历史开发测试流程，不纳入生产迁移 |

---

## 2. 提取工作站（Island `1001890`）设备与能力清单

提取岛共部署 **12 台设备实例**（另质粒岛 `1000836` 共 31 台设备实例，含机械臂、9608 移液、S100 封膜、Xpeel 撕膜、PCR、CellInsight CX7 读板、EL406 洗板、Cytomat 2C 培养箱、磁力架、开关盖、相机及料栈）：

| 设备实例 ID | Code | 型号 / 厂商 | Resource ID | 类型 | 已登记方法（预估耗时 / 超时 / 可中断） |
|---|---|---|---|---|---|
| `1000092`–`1000097` | `DISP1`–`DISP6` | EL406 / Biotek | `1001891`–`1001896` | 分液器（裂解/磁珠/W1/W2/W3/洗脱） | `Run` (10s / 900s / int=false) |
| `1000098` | `ARM1` | XRobot A10LR / X-Imaging | `1001897` | 轨道式四轴机械臂 | 18 项：`BarcodeScan`(0s/600s), `BarcodeScanEx`(10s/600s), `Transfer`(15s/20s), `Home`(10s/20s), `Move`(10s/20s), `Pick`/`Place`(10s/20s), `ReturnToSafe`(10s/20s), `GripAction`/`GripperAction`, `Flip`, `Rotation`, `ServoSetPosition`, `ServoZero`, `SwitchEOAT`, `Perform`, `ResetRobot`, `TrySyncAreas` |
| `1000099` | `STACK1` | Hotel12H / X-Imaging | `1001898` | 自动化耗材栈 | 14 项：`CheckStackState`/`CheckStackUsed`/`CheckType`/`GetStackRecord`/`GetStackSetting`(10s/20s), `MoveInLabware`/`MoveOutLabware`(10s/60s), `InputBy*`/`OutputBy*`, `TrySyncRecords` |
| `1000100`–`1000103` | `P_AUTO-1`–`P_AUTO-4` | PAuto / 天隆科技 | `1001899`–`1001902` | 磁珠法核酸提取仪 | 12 项：`PickTipComb`/`DropTipComb`(15s/60s), `Bind`(200s/600s), `Wash`(150s/300s), `Elute`(600s/750s), `Incubate`(300s/400s), `CollectBeads`/`DiscardBeads`(60s/120s), `RotatePlate`(2s/30s), `Home`(3s/30s), `GetStatus`(1s/10s), `Stop`(2s/30s, **int=true**) |

已登记设备事件字典（`public.device_event_type` 共 11 种）：
- **交互类 (`interaction`)**：`waiting_interaction`（主动暂停等待机械臂换板）、`plate_ready`、`plate_removed`
- **终态类 (`terminal`)**：`process_done`、`flow_completed`
- **进度类 (`progress`)**：`idle`、`plate_detected`、`carousel_running`、`stirrer_running`
- **故障类 (`fault`)**：`stirrer_fault`、`fault`

---

## 3. 重点流程深度拆解：`1001017` 与 `1001041`

### 3.1 `1001041`（`EXT-WORKFLOW-SINGLE`，78 任务，0 网关，79 流）
纯串行单样品板全流程，面向 `P_AUTO-1` + `ARM1` + `STACK1` + `DISP1..6`：
- **节点构成**：
  - `60` 个 `ARM1` 节点：严格遵循 **20 组「抓板前扫码 `BarcodeScan` → 搬运 `Transfer` → 到位扫码 `BarcodeScan`」物理取证三连**；全部携带变量模板参数（`${plateMagnet}`、`${plateSample}`、`${plateW1}`、`${plateW2}`、`${plateW3}`、`${plateElute}`）与显式工位坐标（如 `STACK1-S01` → `DISP1-PLATE` → `P_AUTO-1-EXT` → `STACK1-S18`）。
  - `6` 个分液器节点：`DISP2`(磁珠/顶置搅拌) → `DISP1`(裂解液+蛋白酶K) → `DISP3`(W1) → `DISP4`(W2) → `DISP5`(W3) → `DISP6`(洗脱液)，均为 `Run`。
  - `6` 个 `P_AUTO-1` 工艺节点 + `1` 个 `GetStatus` + `2` 个取/卸磁套：`PickTipComb` → `DropTipComb` → `PickTipComb`(再装转盘) → `Bind` → `Wash`(W1) → `Wash`(W2) → `Wash`(W3) → `Elute` → `GetStatus`。
  - **`6` 个 `__awaitEvent` 节点**：紧跟在 `PickTipComb`、`Bind`、`Wash`×3、`Elute` 之后，参数均为 `{"device": "P_AUTO-1", "eventType": "waiting_interaction", "timeout": 900, "into": "P_AUTO-1_wait_<stage>"}`。

### 3.2 `1001017`（`EXT-WORKFLOW-02`，51 任务，4 并行网关，62 流）
单批 4 台提取仪（`P_AUTO-1..4`）并行流程：
1. **阶段 1（4 路并行：`Gateway_0002` → `Gateway_0052`）**：每台 `P_AUTO-i` 并行执行 `PickTipComb` → `__awaitEvent(waiting_interaction, timeout=600)` → `ARM1` 扫码+搬运磁套板(`STACK1-S09..S12` → `P_AUTO-i-EXT`) → 到位扫码 → `DropTipComb`。
2. **阶段 2（串行预分液）**：依次执行 `DISP1`..`DISP6` 的 `Run`（注意：`DISP2` 涉及搅拌器–分液器互锁 `StirrerDispenserGuard`）。
3. **阶段 3（4 路并行：`Gateway_0069` → `Gateway_0111`）**：每路执行 `__awaitEvent(waiting_interaction, timeout=900)` → `ARM1` 扫码取产物板(`P_AUTO-i-EXT` → `STACK1-S09..S12`) → 到位扫码 → `P_AUTO-i Elute`。
4. **阶段 4（收尾）**：`P_AUTO-1 GetStatus`。

---

## 4. 旧 `__awaitEvent` 真实语义与 G5 `receiveTask` 差异对照

代码定位：[GraphRunner.java](../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L1210-L1313)、[WorkflowService.java](../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1174-L1181)、[DeviceEventMapper.xml](../labos-kernel/src/boot/resources/mapper/DeviceEventMapper.xml#L65-L116)。

| 语义维度 | 旧 `__awaitEvent` 真实行为 | 新 G5 `receiveTask` + `wait_registration` | 迁移风险与处理 |
|---|---|---|---|
| **事件来源** | 设备自发事件（`message_type=6`）经 Sidecar `POST /api/device-events` 写入 `public.device_event` | 仅接入 `POST /api/runtime/runs/{runId}/signals` 写入 `workflow_runtime.inbox_receipt` | **缺失桥接**：当前 `LabSepWireCodec.java:103-113` 直接丢弃 `message_type=6`，且 Sidecar 推往旧表 `device_event` |
| **匹配与关联键** | 按 `(tenant_id, device_code?, event_type)` 在全局池 FIFO 抢占，**完全忽略 `run_id`** | 强制 `(tenant_id, run_id, correlation_key)` 精确路由，无跨 Run 串扰 | 需由 Device Event Router 按设备当前归属 Run（`command_ledger` / `resource_lease`）生成 `(runId, correlationKey="device:<deviceId>:<eventType>")` |
| **早到事件（进入 Wait 前已上报）** | **天然缓冲**：`device_event_run_cursor` 在 Run 启动时钉住 `base_event_id`，早到事件进入节点后仍可认领 | `RUNNING/OPEN` 下若尚无 `ACTIVE Wait`，直接 **`409 REJECTED`**（仅 `PAUSED` 态允许延迟缓冲） | **高危竞态**：`PickTipComb`/`Bind` 完成后若设备立即发 `waiting_interaction`，可能早于 Wait 注册到达！必须在设备命令/事件契约中定义**短窗口待认领事件缓冲或命令-交互态耦合** |
| **超时语义** | 阻塞线程累计睡眠满 `timeout` 秒 → 抛异常 → 节点转 `manual` (`EC-BUILTIN-001`)，**无超时分支** | DB 时钟 `deadline_at` 扫描 → `EXPIRED` → 必须路由到 `boundaryEvent` 独立超时分支；且 **G5 禁止并行网关内使用 `MESSAGE_WITH_TIMEOUT`**（`BpmnGraphCompiler.java:564-570`） | **`1001017` 硬阻塞**：8 个 `__awaitEvent` 全部在并行分支内且带 `timeout=600/900`，不能直接 1:1 翻译为 `receiveTask + boundaryEvent` |
| **载荷回写** | 写回流程变量 `into`（JSON 载荷）与 `deviceInto`（设备编码） | 仅存入 `inbox_receipt.message_payload`，不自动导出为 `flow_variable` | 若后续节点或人工审计需要读取事件载荷，需扩展 Wait 满足时的可选变量导出 |
| **重复 / 暂停 / 停止** | `source_event_id` 冲突静默忽略（无 payload 校验）；500ms 切片查 pause；**无活体 STOP 检查** | 严格 `sourceMessageId + payload_hash` 幂等与 `PROTOCOL_VIOLATION`；PAUSE 延迟缓冲 + RESUME 重放；STOP 原子取消 Wait | 新引擎全面优于旧引擎 |

---

## 5. 标准提取工艺模型（业务原语与设备解耦）

为避免工艺定义随底层设备协议/驱动变化，提取工艺抽象为 12 个标准业务工序，每步声明清晰契约：

| 阶段 | 标准工序 (`Step`) | 业务原语 | 设备能力 / 角色 | 关键输入 | 前置条件（物理互锁） | 预期结果 | 建议超时 | 失败策略 | 补偿策略 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `verify_sample_and_consumables` | `DeviceTask` | `STORAGE.CHECK` + `SCANNER` | `batchId`, `plateBarcodes[]` | 料栈在线、板位已登记 | 条码与耗材位校验通过 | 30s | `MANUAL_REQUIRED` | 不可自动重做，人工核对 |
| 2 | `init_station` | `DeviceTask` | `ROBOT.HOME` + `EXTRACTOR.HOME` | — | 无在途板、门禁闭锁 | 工位机械归零就绪 | 60s | `RETRY(1)` → `MANUAL` | 幂等归位 |
| 3 | `load_tip_comb` | `DeviceTask` + `Wait` | `EXTRACTOR.PICK_TIP_COMB` + `ROBOT.TRANSFER` | `magPlate`, `slot` | 抓前扫码 + L1 转盘静止 + L3 轨道互斥 | 磁套加载至转盘 | 命令 60s / 等待 600s | `MANUAL_REQUIRED` | 禁止盲重试，先查转盘状态 |
| 4 | `dispense_beads` | `DeviceTask` | `DISPENSER.RUN` (`DISP2`) | `magPlate`, `reagent=beads` | **L4 搅拌器就绪 (`StirrerDispenserGuard`)** + 到位扫码 | 磁珠分装完成 | 900s | `PAUSE_FAULT` / `MANUAL` | 不可重分液（防溢出） |
| 5 | `dispense_lysis` | `DeviceTask` | `DISPENSER.RUN` (`DISP1`) | `samplePlate`, `reagent=lysis_pk` | 到位扫码 + 板位独占 | 裂解液+蛋白酶K 分装完成 | 900s | `MANUAL_REQUIRED` | 不可重分液 |
| 6 | `transfer_plate`（通用） | `DeviceTask` | `ROBOT.SCAN_TRANSFER_SCAN` | `barcode`, `srcPos`, `dstPos` | `PositionLock` + `TransferPlan` + 门控 + L1/L3/L5 + 抓前扫码 | 板安全到位并完成到位扫码 | 60s | `MANUAL_REQUIRED`（在途板隔离） | 禁止自动重抓，走盘点对账 |
| 7 | `bind_and_lyse` | `DeviceTask` + `Wait` | `EXTRACTOR.BIND` | `samplePlate`, `plateIndex` | 板到位确认、提取仪门关闭 | 加热振荡结合完成并进入 `waiting_interaction` | 命令 600s / 等待 900s | `UNKNOWN` → T21 对账 | 按设备状态查询决定续跑或人工 |
| 8 | `wash_cycle` (W1/W2/W3) | `DeviceTask` + `Wait` | `DISPENSER.RUN` + `EXTRACTOR.WASH` | `washPlate`, `washStage` | 对应分液器与提取仪交互位就绪 | 洗涤完成并等待换板 | 分液 900s / 洗涤 300s / 等 900s | `UNKNOWN` → T21 对账 | 不可重复洗涤已回收磁珠 |
| 9 | `elute_nucleic_acid` | `DeviceTask` + `Wait` | `DISPENSER.RUN` (`DISP6`) + `EXTRACTOR.ELUTE` | `elutePlate` | 洗脱板到位 + 交互位就绪 | 洗脱产物生成并等待取板 | 分液 900s / 洗脱 750s / 等 900s | `UNKNOWN` → T21 对账 | 最高价值产物，严禁盲重试 |
| 10 | `archive_eluate_and_status` | `DeviceTask` | `ROBOT.TRANSFER` + `EXTRACTOR.GET_STATUS` | `elutePlate`, `archiveSlot` | 产物板抓前/到位扫码 | 产物板回库、设备回归 `IDLE` | 60s | `MANUAL_REQUIRED` | 可重试只读 `GetStatus` |
| 11 | `qc_confirmation` | `Wait` (`HumanTask`) | 外部 LIMS / 人工确认 API | `correlationKey="qc.<batchId>"` | 产物归档完成 | 收到 `PASS` 或 `FAIL` 信号 | 3600s（超时升级） | `MANUAL_REVIEW` | 人工复核决定放行/返工 |
| 12 | `abnormal_reconcile` | `Compensation` / T21 | 命令对账 + 人工干预 | `commandId`, `evidence` | 命令/板位处于 `UNKNOWN` | 明确终端状态并释放工位锁 | 人工决定 | `QUARANTINE` | 经审计决策后解冻 Run |

---

## 6. 架构拍板结论：不做旧流程迁移，从头构建标准提取工艺

> **已拍板决策（2026-11-21）**：不再为历史 `1001017` / `1001041` 编写 `__awaitEvent` 兼容翻译器，也不复用旧 78 节点耦合式 XML；保留旧流程原样不触碰，**在新 Workflow Runtime + 统一 Device Execution Contract v1 上从头构建标准提取工艺**。

### 新建首批标准工艺（Greenfield v1）
1. **`EXT-STD-SINGLE-V1`（单板全工序标准提取工艺，首发）**：
   - 基于第 5 节 12 步标准工艺模型全新建模，严格表达业务原语（`DeviceTask` / `Wait` / `Timer` / `ExclusiveGateway` / `HumanTask`），不硬编码底层 TCP 指令或厂商协议。
   - 参数全部以结构化输入变量传入（`${var}` 由 Kernel 在写入 `command_ledger` 前解析）。
   - 物理搬运不再靠手工拼写 60 个零散节点，而是由设备能力操作 + 中央 `PhysicalSafetyGate` SPI 统一保障抓前扫码、站位锁、轨道互斥与门控。
2. **`EXT-STD-BATCH4-V1`（4 台提取仪高通量并行工艺，第二批）**：
   - 在 `EXT-STD-SINGLE-V1` 跑通后，引入 4 路 `ParallelGateway` 驱动 `P_AUTO-1..4` 并发执行，共享 `ARM1` / `DISP1..6` 资源竞争与物理互锁。
   - 并行分支内的 `Wait(waiting_interaction)` 使用 Contract v1.1 定义的 `deadlinePolicy=ESCALATE_MANUAL`（不产生逃逸出并行区域的拓扑分支，与 G5 拓扑校验完全兼容）。

### 首发前必需的底座能力（对应 Phase 3B）
1. **Device Spontaneous Event → G5 Wait Inbox 路由桥** + Run 级短窗口早到事件缓冲（解决 `waiting_interaction` 早于 Wait 注册到达导致的 `409`）。
2. **`${var}` 模板变量解析**（Kernel 层解析后落库 `command_ledger.command_payload`）。
3. **能力驱动的每操作超时、幂等分类与可中断元数据**（来自 Sidecar 注册的 `DeviceCapabilities`，取代硬编码全局 300s 与硬编码 `QUERYABLE`）。
4. **全新 `PhysicalSafetyGate` SPI**（统一接管站位锁、轨道互斥、转盘/搅拌器互锁与扫码取证）。
5. **T21 闭环解冻事务**（`reconcileAndSettleUnknownCommand` 同步释放设备容量、关闭干预并恢复 Run）。
