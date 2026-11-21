# 真实工艺流程清单与迁移分析（REAL_WORKFLOW_INVENTORY）

## 元信息

| 项 | 值 |
| --- | --- |
| 审计基线 | `origin/main` @ `c650e271`（2026-11-21 17:12） |
| 审计分支 | `docs/phase3a-audit-20261121` |
| 审计类型 | 只读（不改动生产代码、不触碰 `labdb`、不下发设备命令） |
| 路径口径 | 本文所有路径均相对仓库根目录；引用格式 `path:line` |
| 审计执行 | Trae AI（DeepSeek-V4.1-Flash） |
| 生成时间 | 2026-11-21 |

---

## 0. TL;DR（先看这段）

1. **关键纠正**：`1001017 / 1001040 / 1001041 / 1001042 / 1001044 / 1001045` 是**运行实例 ID（run.id）**，不是工作流定义 ID。证据：`docs/设计-逻辑节点分层与编辑器分工.md:177-179` 中它们只作为 URL `/runs/1001040/flow` 出现。真实工作流以**流程号 code** 标识。
2. **当前真实流程只有 4 个 code**（提取岛）：`EXT-WORKFLOW-01`、`EXT-WORKFLOW-02`(单批 57 节点)、`EXT-WORKFLOW-SINGLE`(单样品板 80 节点)、`BASELINE-SERPAR-01`(基准 9 节点)。证据：`scripts/export-extraction-flow-diagram.py:90-91`。
3. **旧引擎在用、新引擎未接管生产流量**。旧 `GraphRunner` 的内置节点 `__awaitEvent` 是"等设备主动上报的一次事件"（边沿触发），新 G5 用 `receiveTask` + `wait_registration` 表达等待。
4. **新旧定义存储是两套**：旧内核 `workflow / workflow_node / workflow_edge`（`pg_rebuild/schema/20_kernel.sql:189-258`）；新运行时 `workflow_runtime.definition_snapshot`（`db/workflow_runtime/001_init.sql:18-30`）。
5. **真实提取工艺只是骨架建模**：管路清洗/填充、样本预处理裂解、产物 QC 读板、异常补偿分支尚未建模。

---

## 1. 工作流定义存储模型

### 1.1 旧内核存储（生产在用）

`pg_rebuild/schema/20_kernel.sql`：

- `workflow`（`:189-206`）：`code`、`name`、`version`、`graph`(jsonb，BPMN 快照)；唯一键 `uk_workflow (tenant_id, code, version)`。
- `workflow_node`（`:208-229`）：`node_key`、`node_type`(task/subprocess/wait/condition/parallel/join/script)、`protocol_id`、`capability_id`、`params`(jsonb，内置节点参数如 `__awaitEvent`)、`condition`、`resource_id`、`seq`。
- `workflow_edge`（`:246-258`）：`from_key`、`to_key`、`edge_type`(sequence/condition/parallel)、`condition`、`default_flow`。
- `workflow_variable`（`:231-243`）：流程级变量定义。

### 1.2 新运行时存储

`db/workflow_runtime/001_init.sql`：

- `definition_snapshot`（`:18-30`）：`definition_key`、`definition_version`、`bpmn_sha256`、`graph_plan_jsonb`（编译后的 GraphPlan）。
- 运行态表：`workflow_run`(`:32-54`)、`execution_token`(`:69-90`)、`node_activation`(`:92-114`)、`node_attempt`(`:116-146`)、`wait_registration`(`:278-297`)、`retry_registration`(`:299-...`)、`runtime_outbox`(`:403-421`)、`command_ledger`(`:423-450`)、`command_dispatch_attempt`(`:452-...`)。

> 结论：定义层"编译产物可复现"由 `bpmn_sha256` + `graph_plan_jsonb` 保证；这是迁移时做**新旧定义对照**的抓手。

---

## 2. 现有流程清单（以 code 为准）

| 流程 code | 规模 | 用途 | 证据 |
| --- | --- | --- | --- |
| `EXT-WORKFLOW-01` | 8 阶段 + 8 个 `__awaitEvent` | 高通量核酸提取·单批标准流程 | `scripts/setup-extraction-flow.py:62`、`:340` |
| `EXT-WORKFLOW-02` | 单批 57 节点 | 客户交付在用（保持原名） | `scripts/export-extraction-flow-diagram.py:90`、`:456-457` |
| `EXT-WORKFLOW-SINGLE` | 单样品板 80 节点 | 单样品板板链 | `scripts/export-extraction-flow-diagram.py:91`、`scripts/setup-extraction-single-plate-flow.py:48` |
| `BASELINE-SERPAR-01` | 基准 9 节点 | 基准/冒烟 | `scripts/export-extraction-flow-diagram.py:91` |

- 岛号 `LAB-EXT-02` 与流程号 `EXT-WORKFLOW-02` 是**平台内部编号，与设备编号无关**（`scripts/rename-extraction-device-codes.py:27`）。
- 验收流程号（Phase 2B）对应关系：`1001040`(串行/并行)、`1001042`(G5 Signal)、`1001044`(Timer 超时)、`1001045`(SIGKILL 恢复)——均系**运行实例**，不能等同真实生产工艺。
- 旧流程 `1001017`、`1001041` 仍在使用 `__awaitEvent`（作为 run 实例）。

> 差距：仓库未提供"run.id → workflow.code"的反查脚本，`1001017/1001041` 到底跑的哪条 code 需查库或用 `scripts/watch-run.py` 复核。见 §8。

---

## 3. 遗留 `__awaitEvent` 语义（旧引擎）

实现位置：`labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java`

- 分派：`:331` `case "__awaitEvent"`；实现：`:1084`、`:1210-1313`。
- 语义（据 `:1210-1265` 注释）：
  - 等**设备主动上报的一次事件**（边沿触发），区别于 `__wait/__waitAny` 的"变量等于某值"（电平触发）。
  - 参数：`device`(设备码，空=任意)、`eventType`(必填，如 `waiting_interaction`)、`timeout`/`interval`、`into`(载荷写入变量)、`deviceInto`(设备码写入变量)。
  - **游标基准取 run 起手**，不是节点起手——设备可能在本节点开始前就报了，按节点起手会漏事件。
- 认领：`:1268-1291` 调用 `src.claim(runId, nodeKey, loopIndex, device, eventType, cursor, 1)`；命中后 `advanceEventCursor(...)` 推进游标、把载荷/设备码写入变量；事件源缺失时报错（`:1235` "没有设备主动上报通道"）。
- 事件落库：`device_event` 表（`pg_rebuild/migration/20261115_device_event.sql:4-19`），幂等键 `source_event_id`；唯一约束 `uk_device_event_source (tenant_id, device_code, source_event_id)`（`:33-35`）。
- 消费去重：`device_event_run_cursor`(`:42-48`) + `device_event_consumption`（`uk_device_event_waiter (tenant_id, run_id, node_key, loop_index)`，`:51-60`）。
- 其它内置节点：`__sleep / __wait / __waitAny / __setVar / __expr`（`GraphRunner.java:291-354`）。

**原语义六问的答案（迁移前必答）**：

| 问题 | 结论 |
| --- | --- |
| 等什么事件 | `eventType` 指定的一次设备上报（典型 `waiting_interaction`） |
| 谁产生 | 设备侧（经 Sidecar/网关落 `device_event`） |
| 相关键 | `(device, eventType)` 二元组分键；无独立业务 correlation key |
| 超时 | 参数 `timeout`，超时抛 `IllegalStateException` → 上层转人工 |
| 重复事件 | 通过游标 + `device_event_consumption` 保证不重复消费 |
| 暂停/恢复/取消 | 等待切成 500ms 片段，每片检查暂停；取消随 run 生命周期 |

---

## 4. 新 G5 图元（运行时）

编译与图模型：`workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/graph/`

- `GraphPlan.Kind`：`TASK, BUILTIN, START, END, WAIT, SPLIT_PARALLEL, JOIN_PARALLEL, SPLIT_XOR, JOIN_XOR, SPLIT_INCLUSIVE, JOIN_INCLUSIVE`（`GraphPlan.java`）。
- `WaitKind`：`MESSAGE / TIMER / MESSAGE_WITH_TIMEOUT`；`RouteKind`：`NORMAL / TIMEOUT`（`GraphPlan.java`）。
- `WaitSpec(waitKind, correlationKey, durationIso, durationMillis)`（校验三型等待）。
- BPMN 映射（`BpmnGraphCompiler.java:125-265`）：纯消息=`receiveTask`(`labos:wait`)；纯定时器=`intermediateCatchEvent`+`timerEventDefinition`；消息+超时=`receiveTask`+`timer boundaryEvent`。
- 可执行元素白名单（`BpmnGraphCompiler.java:627-637`）：含 `receiveTask`；**不支持** `subProcess/callActivity/manualTask/eventBasedGateway/complexGateway/transaction/adHocSubProcess`（`:...`）。
- 物理落点 `wait_registration`（`db/workflow_runtime/001_init.sql:278-297`）：`wait_state ∈ {ACTIVE,SATISFIED,EXPIRED,CANCELLED}`；`wait_due_idx`(仅 ACTIVE) + `wait_one_active_per_activation_uq`。
- G5 关联唯一约束：`db/workflow_runtime/003_g5_wait_correlation.sql:30-32` `wait_active_correlation_uq (tenant_id, run_id, correlation_key) WHERE wait_state='ACTIVE'`。
- 统一事件名：满足/过期写 `WAIT_SATISFIED` / `WAIT_EXPIRED`（`WorkflowRuntimeRepository.java:1884-1885`）。
- 外部信号入口：`WorkflowRuntimeRepository.receiveWaitInbox(tenantId, runId, source, sourceMessageId, correlationKey, payloadJson)`。

> 迁移含义：`__awaitEvent` → WAIT 节点的 `correlationKey` 必须由 `(device, eventType)` 合成，并在 Sidecar/网关事件桥接时按同一规则生成，否则唤醒失败。

---

## 5. 真实提取工艺步骤：已建模 vs 缺失

设备文档：`docs/设备/PAuto-磁珠法核酸提取仪.md:20-33`（提取仪 9 个逻辑动作：结合/洗脱/丢磁珠/取磁珠/孵育/漂洗/取磁套/卸磁套/换板位）。
脚本骨架：`scripts/setup-extraction-flow.py:62-716`、`scripts/setup-extraction-single-plate-flow.py`。
真实工艺资料：`private/实际流程/高通量核酸提取工作站-*.md`、`private/实际流程/三条流程-LabOS综合适配分析与扩展路线.md`、`private/实际流程/高通量核酸提取工作站-板流还原与差距分析-20261119.md`。

| 真实步骤 | 建模状态 | 对应图元/设备 |
| --- | --- | --- |
| 样本接收与身份校验 | 部分 | `BarcodeScan`(ARM1) / 扫码确认 |
| 耗材与试剂检查 | 部分 | 板位预摆 + 扫码 |
| 设备准备与初始化 | 部分 | `PickTipComb`(PAuto) 等 |
| 移液/分液 | 已建模 | `Run`(DISP1–DISP6) |
| 混匀 | 已建模 | 顶置搅拌(磁珠分装阶段) |
| 离心 | 未接入生产流程 | `aspin-2k`(离心机) 方法存在但未编排 |
| 提取（Bind/Elute/Wash） | 已建模骨架 | PAuto 内部动作 |
| 洗涤 | 已建模骨架 | W1/W2/W3 分液 + 洗板 |
| 洗脱 | 已建模骨架 | `Elute`(PAuto) |
| 质量确认（QC 读板） | **缺失** | `biotek-gen5`(酶标仪) 未编排 |
| 结果记录 | 部分 | run 结果/错误码映射 |
| 异常处理与人工干预 | 部分 | 恢复策略/人工窗口（见 DEVICE 契约文档） |
| 管路清洗/填充 | **缺失** | 分液器必须前置步骤 |
| 样本预处理裂解 | **缺失** | 非提取仪自身动作 |

---

## 6. 设备与方法目录

- 能力字典：`pg_rebuild/schema/95_seed_data.sql:21-44`（`ALIQUOT/CENTRIFUGE/INCUBATE/READ_PLATE/SCAN/TRANSFER/DECAP/WASH/SEAL/PEEL/MIX/EXTRACT`）。
- 型号库：`labos-kernel/src/main/java/com/labos/kernel/device/DeviceModels.java` — `aspin-2k`(离心机)、`s100`(封膜机)、`biotek-lhc`(洗板机)、`biotek-gen5`(酶标仪)、`pauto`(核酸提取仪)，另有 `*-generic` PLANNED 占位型号。
- 设备表：`device`、`device_method`、`device_instance`、`device_state`、`device_log`（`pg_rebuild/schema/30_business.sql` 等）。
- Sidecar 型号库：`sidecar-dotnet/models.json` + `sidecar-dotnet/Labos.Sidecar/ModelLibrary.cs:14`。

---

## 7. 迁移分析（`1001017` / `1001041` → 新版）

**原则**：不做 `__awaitEvent` 的全局文本替换；先语义映射，再逐一迁移，旧版本保留不覆盖。

映射规则草案：

| 旧 | 新 | 说明 |
| --- | --- | --- |
| `__awaitEvent(eventType=X, device=D)` | `receiveTask` + `WaitSpec(MESSAGE, correlationKey=f(D,X))` | correlationKey 生成规则须与事件桥接一致 |
| `__awaitEvent(timeout=T)` | `WaitSpec(MESSAGE_WITH_TIMEOUT, durationMillis=T)` | 超时路由走 `RouteKind.TIMEOUT` |
| `__sleep(N)` | `WaitSpec(TIMER, durationIso=PTnS)` | |
| `__wait/__waitAny`（电平） | 条件网关（XOR）+ 变量快照 | 注意旧电平语义到新条件网关的差异 |
| `__setVar/__expr` | `BUILTIN` 节点 | |
| `parallelGateway` | `SPLIT_PARALLEL/JOIN_PARALLEL` | V1 禁止并行区域内 Timeout Boundary（见契约文档 §7） |

**必须回归的 6 类语义**：等待对象、生产者、correlation key、超时、重复事件、暂停/恢复/取消。

---

## 8. 差距与不确定性

1. **run.id → workflow.code 反查缺失**：无法在静态代码中确认 `1001017/1001041` 对应的 code，需查库或用 `scripts/watch-run.py` 复核。
2. **生产工作流全量未统计**：已知提取岛 4 条 code，是否还有 QC/封膜/样本前处理等其它流程未盘点。
3. **`waiting_interaction` 上报帧格式**：`docs/设备/PAuto-磁珠法核酸提取仪.md` 标注"无应答帧格式"，设备主动上报帧属推断，需真机确认。
4. **分液器 96 孔加液帧格式存疑**：`docs/设备/9608-加液分液设备.md:83` 指出 `0x40/0x41` 报文长度与 12 字节帧总述矛盾（变长帧 or 多帧续传待现场确认）。
5. **新旧引擎切换时间表未定**：新运行时已嵌入 Kernel 集成但**未接管生产执行流量**（新旧并存）。
6. **并行区域内 Timeout 的编译器支持**：旧图含并行分叉，迁移时需确认新编译器对 Timeout Boundary 的拒绝规则不会阻断合法图。
