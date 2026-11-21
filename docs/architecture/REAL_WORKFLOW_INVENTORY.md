# REAL_WORKFLOW_INVENTORY — 真实工艺流程与旧流程迁移审计

- 基线：`origin/main` @ `c650e271`（2026-11-21 17:12:49 +0800），导出只读快照审计；本地 `main` @ `4eb32095` 落后 135 文件 / +39,759 行，不作为审计依据。
- 方法：只读静态审计，未连接数据库、未启动 RuntimeHost、未向设备下发命令、未修改生产代码。
- 配套文档：`DEVICE_INFRASTRUCTURE_AUDIT.md`、`DEVICE_EXECUTION_CONTRACT_V1.md`、`PHASE3_IMPLEMENTATION_PLAN.md`。

> 本文所有路径均为仓库相对路径，行号对应上述基线。标注【推测】的条目不是已确认事实。

---

## 1. 结论摘要

1. **流程定义的权威存储是数据库，不在仓库。** `pg_rebuild/schema/90_all_in_one.sql:444-461` 定义 `workflow.graph jsonb`（原始 BPMN 2.0 XML）；仓库内 `*.bpmn` 文件数 = **0**。Runtime 另有一套 `workflow_runtime.definition_snapshot`（GraphPlan JSON）。
2. **用户给出的 6 个流程 ID 在代码库中几乎不存在。** `1001040`、`1001041` 各命中 2 个文件，且只是文档中的 UI 只读流程图测试 URL `/runs/{id}/flow`；`1001017`、`1001042`、`1001044`、`1001045` **全库 0 命中**。字面量 `Phase 2B` 全库 0 命中。
3. **不能把这 6 个 ID 直接当作"真实生产工艺"或"Phase 2B 验收流程"。** 它们至少是 run 号而非 workflow 定义 ID；其定义体、租户、设备岛归属均未随仓库导出。
4. **`__awaitEvent` 与 G5 WAIT 是两套并存的等待机制。** 旧机制只按 `(device, eventType)` 匹配，无 correlation key、无命令回显；新机制有静态 `correlationKey`、deadline、CANCELLED、Inbox 幂等。二者不是简单替换关系。
5. **真实提取工艺只在脚本生成的"BPMN 写入数据库"层面存在**（板链、方法名、等待点）；"样本接收 / 耗材检查 / 混匀 / QC / 异常处理"没有可执行流程节点定义，只出现在设计文档的能力缺口中。
6. **G5 是独立验收集，不是生产切换。** 13/13 PASS（`docs/G5-独立验收测试设计与报告-20261121.md:7`），但 `docs/Workflow-Runtime-G1-GraphCoordinator-实施记录-v1.0-20261120.md:133-137` 明确"不接管旧 GraphRunner，不迁移真实设备流量"，RuntimeHost 默认禁用未变（G5 报告 :68）。

**因此：Phase 3A 的第一个真实动作不是改流程，而是从现场库导出这 6 个 run 的定义与执行轨迹。** 在此之前，任何"迁移 1001017 / 1001041"的具体改图方案都缺乏事实依据。

---

## 2. 流程定义存储与写入路径

| 层 | 存储 | 证据 |
|---|---|---|
| Kernel 权威定义 | `public.workflow.graph->>'xml'` | `pg_rebuild/schema/90_all_in_one.sql:444-461`；`docs/LabOS-Workflow-Runtime-BPMN-GraphPlan契约与实施记录-v1.0-20261119.md:9-20` |
| 节点投影 | `workflow_node` / `workflow_variable` / `workflow_edge`（**不能用 edge 重建控制流**） | `90_all_in_one.sql:463-512`；契约文档 :9-20 |
| 绑定 / 历史 | `node_binding`、`workflow_graph_history`、`workflow_node_step` | `90_all_in_one.sql:1655, 2749-2766, 4017-4051` |
| Runtime 定义快照 | `workflow_runtime.definition_snapshot`（`definition_hash` + `compiler_version` + `graph_plan jsonb`） | `db/workflow_runtime/001_init.sql:18-30` |
| 读取端口 | `WorkflowDefinitionSource` / `LegacyKernelWorkflowDefinitionSource`（`kernel-tenant:<bigint>` 映射） | `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/definition/{WorkflowDefinitionSource,LegacyKernelWorkflowDefinitionSource}.java:7-17, 41-57` |

写入方：
- Java：`labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowBuilder.java:45-94, 169-235`。
- Python 建流脚本：41 个文件命中 `/api/workflows`，关键为 `scripts/setup-extraction-flow.py:139-248, 384-608`、`scripts/setup-extraction-single-plate-flow.py:208-342, 357-361`。
- 前端设计器：`webapp/src/pages/Designer.tsx`、`webapp/src/bpmn/flowSpec.ts`。
- 测试 fixture：内嵌 BPMN 字符串，非生产定义。

否定证据：`*.bpmn` 文件 0 个；`pg_rebuild/schema/95_seed_data.sql` 无流程 INSERT。

### 2.1 当前真实流程清单（以 workflow.code 为准）

真实流程以 `workflow.code` 标识，而非 run id：

| 流程 code | 规模 | 用途 | 证据 |
|---|---|---|---|
| `EXT-WORKFLOW-01` | 8 阶段 + 8 个 `__awaitEvent` | 高通量核酸提取·单批标准流程 | `scripts/setup-extraction-flow.py:62, 340` |
| `EXT-WORKFLOW-02` | 单批 57 节点 | 客户交付在用（保持原名） | `scripts/export-extraction-flow-diagram.py:90, 456-457` |
| `EXT-WORKFLOW-SINGLE` | 单样品板 80 节点 | 单样品板板链 | `scripts/export-extraction-flow-diagram.py:91`；`scripts/setup-extraction-single-plate-flow.py:48` |
| `BASELINE-SERPAR-01` | 基准 9 节点 | 基准 / 冒烟 | `scripts/export-extraction-flow-diagram.py:91` |

`scripts/export-extraction-flow-diagram.py:88-91` 注释明确：提取岛现有 3 条流程（02 / SINGLE / BASELINE），"原来「取第一条」会把单样品板流程导成旧的 57 节点那条"。岛号 `LAB-EXT-02` 与流程号 `EXT-WORKFLOW-02` 是平台内部编号，与设备编号无关（`scripts/rename-extraction-device-codes.py:27`）。

### 2.2 用户提供的 Phase 2B 验收映射（代码库无证据）

用户在本轮需求中给出：`1001040` = 新引擎串行/并行基准、`1001042` = G5 Signal、`1001044` = Timer 超时、`1001045` = SIGKILL 恢复；`1001017`、`1001041` 仍在使用 `__awaitEvent`。

**这些映射来自用户描述，快照中 4 个 ID 全库 0 命中，必须在现场库中核实后才能作为迁移依据。**

---

## 3. 六个流程 ID 逐个定位

| ID | 命中 | 定位 | 判定 |
|---|---|---|---|
| `1001017` | 0 | — | 无法从仓库判定 |
| `1001040` | 2 | `docs/设计-逻辑节点分层与编辑器分工.md:175-179`、`docs/en/design/logic-nodes-by-layer.md:180-184` | UI 只读流程图测试的 **run 号** |
| `1001041` | 2 | 同上（"实验页「打开流程图」跳 `/runs/1001041/flow`"） | UI 只读流程图测试的 **run 号** |
| `1001042` | 0 | — | 无法从仓库判定 |
| `1001044` | 0 | — | 无法从仓库判定 |
| `1001045` | 0 | — | 无法从仓库判定 |

原文证据（`docs/设计-逻辑节点分层与编辑器分工.md:175-179`）：
> playwright 实测 /runs/1001040/flow ⇒ rg-* 计数 manual 1 / pending 2 / start 1 / end 1、只读提示条在、画布 11 元素；实验页「打开流程图」⇒ 跳 /runs/1001041/flow。

**结论**：这些数字是 `workflow_run` 的实例号，不是 BPMN `process id`，也不是 workflow 定义 ID。仓库里没有它们的定义内容、设备绑定、租户、状态、definition_id/version 或 BPMN SHA。

### 3.1 导出现场库时至少要取的数据

对 `1001017` / `1001041`（以及另 4 个 ID）：

1. `workflow_run`：状态、租户、workflow_id、definition_id/version、BPMN SHA、创建时间与实验/批次关联。
2. `run_node` / `workflow_node_step`：执行轨迹、实际走过哪些节点、哪些节点停在 WAIT。
3. `workflow.graph->>'xml'`：真实 BPMN 原文（含 `node_binding`）。
4. 等待事件事实：`device_event`、`device_event_consumption`、`device_event_waiter`（若存在）、等待游标。
5. 设备命令事实：legacy `command_ledger`、`run_command`。
6. 现场设备清单与 `driver` / `device_instance` / `node_binding` 映射。

没有这六项，无法回答"等什么事件、谁产生、是否带 correlation key、是否允许重复事件、有无暂停/恢复/取消"。

---

## 4. `__awaitEvent` 语义与清单

### 4.1 实现事实

- 定义：`labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java:1061-1067, 1209-1230`。
- 等待条件：`device`（设备码，留空=任意）+ `eventType`（必填）+ `timeout` + `interval` + `into` + `deviceInto`。**无 correlation key，无命令回显匹配**。
- 认领方式：`src.claim(eventRunId, node.key(), loopIndex, device, eventType, eventCursorFor(...), 1)`（`GraphRunner.java:1268-1269`）；`device_event_consumption.event_id` 全局单次消费。
- 游标基准：run 起手（`WorkflowService.java:1178-1180, 1285-1288`），节点启动前的事件也算（`:1227-1229`）。
- 超时：抛"等待设备事件超时"→转人工（`GraphRunner.java:1293-1296, 1275-1278`）。
- 暂停：分片 500ms 轮询，每片查 paused（`:1065-1067, 1309-1311`）。
- 取消：**参数中没有 cancel**。已知缺陷：分支停在 `__awaitEvent` 时 stopFlag 不立即生效，"等待没有上限"（`docs/并行网关真并发-改造方案-20261119.md:215-217`）。

### 4.2 全库分布（58 个文件命中）

- 实现/校验：`GraphRunner.java`(8)、`WorkflowService.java`(2)、`PreflightChecker.java`、`DeviceCore.java`、`DeviceMapper.java`、`DeviceService.java`、`BindingController.java`、`DeviceEventController.java`、`DeviceController.java`。
- 测试：`GraphRunnerTest.java`(11)、`DeviceEventDbSelfTest.java`(6)、`DeviceCodeSyncDbSelfTest.java`。
- 建流/运维脚本：`setup-extraction-flow.py`(7)、`setup-extraction-single-plate-flow.py`(2)、`bring-extraction-station-online.py`(2)、`replay-run.py`(2)、`setup-extraction-station.py`、`verify-builtin-nodes.py`(4) 等。
- 前端：`Designer.tsx`(18)、`flowSpec.ts`、`builtinLabels.ts`、`nodeRoleLabel.ts`、i18n 文件。
- SQL / 文档 / 大量 DEPRECATED 评审归档。

真实流程中的等待点：
- `scripts/setup-extraction-flow.py:452-454`：`__awaitEvent(device=code, eventType=EV_WAIT_INTERACTION, timeout=600)`；`:546-548` 收尾 `timeout=900`。脚本注释 `:384` 称 8 个等待点，`setup-extraction-station.py:528` 称 7 个——**已确认的文档冲突**。
- `scripts/setup-extraction-single-plate-flow.py:252-259, 268, 295, 316`：`await_event(dev, into, timeout=900)`。

事件生产者：
- `scripts/bring-extraction-station-online.py:265-286` 用 `--spontaneous "PickTipComb=waiting_interaction@300*; ..."` 让模拟器自发上报，`*` 表示可重复；按每方法 FIFO 队列消费，写 N 次才发 N 次（`:271-273`）。
- `:315-320` 事件必须经 sidecar 转 `POST /api/device-events`；kernel TCP 驱动会丢弃无 `message_id` 的自发帧。
- `labos-kernel/.../sim/DeviceCore.java:937-941, 959-961` 自发帧形状与设备码口径。

### 4.3 与 G5 WAIT 的能力对照

| 维度 | `__awaitEvent`（旧 Kernel） | G5 WAIT（新 Runtime） |
|---|---|---|
| 匹配键 | 仅 `device + eventType`，**无 correlation key** | 静态 `correlationKey` 必填（`BpmnGraphCompiler.java:346-366`） |
| 消费 | `DeviceEventSource.claim` 原子认领，全局单次 | `wait_registration` 行锁 + 唯一索引 `wait_active_correlation_uq`（`db/workflow_runtime/003_g5_wait_correlation.sql:30-32`） |
| 超时 | `timeout`，流程里写 600/900 秒 | ISO-8601 deadline，最长 365 天（`GraphPlan.java:26, 151-179`） |
| 重复/幂等 | 事件一次性认领；事件源可重复产生 | `inbox_receipt` 唯一 `(source, source_message_id)`（`001_init.sql:369-388`） |
| 暂停/恢复 | 分片轮询查 paused；游标基准为 run 起手 | PAUSE/STOP/FAILURE drain（`001_init.sql:487-515`） |
| 取消 | 无参数 | `wait_state IN ('ACTIVE','SATISFIED','EXPIRED','CANCELLED')`（`001_init.sql:283`） |
| 无上限风险 | stopFlag 不立即生效 | `deadline_at` + `findDueWaits` 收敛（`001_init.sql:286, 295`） |

**关键缺口**（`docs/并行执行-设计文档-20261119.md:165-171`）：设备不回显 `commandId` ⇒ 事件永远进 UNATTRIBUTED ⇒ 依赖 `__awaitEvent` 的流程在该设备上跑不起来；sidecar 必须为自发事件回填触发它的 `commandId`。`docs/设备命令与事件关联规范-commandId回显-20261119.md:4` 自标"未实施"，所需 DDL（`device_event.command_id`、`device_event_waiter.expect_command_id`）全库 0 命中。

---

## 5. Runtime 原语支持矩阵

| 用户建议原语 | 当前实现 | 证据 |
|---|---|---|
| `DeviceTask` | 有：`GatewayDeviceTaskBridge` → durable command → outbox | `workflow-runtime/.../bridge/GatewayDeviceTaskBridge.java:109-147, 187-221` |
| `Wait` | 有：G5 `receiveTask + labos:wait` → `GraphPlan.Kind.WAIT`；另有旧 `__awaitEvent` | `BpmnGraphCompiler.java:117-136, 346-366` |
| `Timer` | 有：`WaitKind.TIMER / MESSAGE_WITH_TIMEOUT`；boundary timer 仅 `bpmn:timeDuration`，只能挂显式 Wait | `GraphPlan.java:93-137, 151-179`；`BpmnGraphCompiler.java:447-477, 510-515` |
| `Gateway` | 有：并行/XOR/Inclusive 的 split/join | `GraphPlan.java:144-149`；`001_init.sql:217-264` |
| `HumanTask` | **不是 BPMN 原语**：`userTask` 按 TASK 编译，`manualTask` 拒绝；真实人工处理是运行态 `manual_intervention` + UNKNOWN/MANUAL_REQUIRED | `BpmnGraphCompiler.java:627-636`；`001_init.sql:533-571` |
| `SubWorkflow` | **不支持**：`subProcess`、`callActivity` 在拒绝清单 | `BpmnGraphCompiler.java:633-636` |
| `Compensation` | **未实现**：G1 明确未实现 BPMN Error / Boundary Event / Retry / Compensation | `Workflow-Runtime-G1-GraphCoordinator-实施记录-v1.0-20261120.md:133-137` |

注：`WorkerExecutionService.java:274-275` 的 "wakeup compensation" 是进程内唤醒，与 BPMN Compensation 无关。

---

## 6. 真实提取工艺现状

### 6.1 已有板链定义（脚本生成 BPMN 写库）

- `scripts/setup-extraction-flow.py:423-439`：DISP 分液器方法链——裂解液+蛋白酶K → `Incubate`；磁珠分装 → `Bind`；W1/W2/W3 → `Wash`；洗脱液 → `Elute`。
- 同文件 `:449-504, 531-533, 569-571, 597-604`：`PickTipComb` → `__awaitEvent` → `BarcodeScan` → `Transfer` → `BarcodeScan` → `DropTipComb`；分液器方法名 `Run`；收尾 `GetStatus`。
- `scripts/setup-extraction-single-plate-flow.py:65-80`：6 块板 `plateSample / plateMagnet / plateW1 / plateW2 / plateW3 / plateElute`，`WASH_STAGES = Wash/Wash/Wash/Elute`。
- 同文件 `:132-137` 授权方法清单：`PickTipComb, DropTipComb, Bind, Wash, Elute, GetStatus, Transfer, BarcodeScan, Run`。
- `:399-406` 明确：方法名用 PAuto 真实指令，不是客户文档里的抽象名；PAuto 协议里没有"启动提取流程"/"继续信号"指令。

### 6.2 命名证据的边界

- `DISP6` 只出现在演示剧本 `docs/media/提取线演示剧本-run1001706.md:74-76, 139-142`，且 `:78` 注明"这一段还没建模完整"。
- `Centrifuge` 出现在 README 演示、动画 `docs/media/labos-flow-source.html:27-30`、`EngineDemo.java:44-46, 73-75`、超时策略 `CENTRIFUGE=900s`——**均不是可执行流程定义**。

### 6.3 未发现可执行定义的工艺环节

全库搜索"样本接收 / 耗材检查 / 混匀 / QC / 异常处理"：**没有出现在任何建流脚本的 BPMN 节点里**。唯一脚本命中是 UI 文案映射 `scripts/verify-workflows-page.py:183-185`。

这些环节只以能力缺口形式出现：
- `docs/多流程工艺编排能力缺口与落地路线.md:163-167`（B5 事件/触发器、B6 扫码校验、B7 设备驱动）、`:247-253`（`__trigger`、等质控结果的循环）、`:299-301`（不做 BPMN 子流程/定时器/消息事件全量）。
- `docs/工艺编排能力缺口与落地路线.md:268-276`：真实工艺里网关用不上；难点是物料身份、工位层、跨流程传递；**G5 是范式差异，不是加节点**。
- 同文档 `:3-9`：四条客户真实流程原始资料脱敏，**不在版本库**。
- `:314`：物料身份增量迁移未并入 `90_all_in_one.sql`。

---

## 7. 迁移 `1001017` / `1001041` 必须先回答的问题

1. **ID 与数据来源**：从哪个库导出？run 的状态、租户、workflow_id、definition_id/version、BPMN SHA 是什么？
2. **分类**：是真实生产工艺还是验收 run？判定依据（实验模板/批次/物料清单/设备岛）是什么？
3. **对应定义**：`EXT-WORKFLOW-01`（8 阶段 57 节点）、`EXT-WORKFLOW-SINGLE`（单板 40 节点），还是另一条未入库流程？
4. **执行引擎**：继续 Kernel `GraphRunner` 还是切 G1/G5 Runtime？切换授权与回滚边界？RuntimeHost 默认禁用何时解除？
5. **事件映射**：旧 `__awaitEvent` 无 correlation key，迁到 `receiveTask + labos:wait` 必须补静态 key；设备能否回显 `commandId`？回显不了怎么办？
6. **生产者就位**：PAuto 不支持主动上报（`setup-extraction-flow.py:404-406`）；当前靠模拟器 `--spontaneous` + sidecar HTTP。真机是否具备同等能力？
7. **语义对齐**：超时、暂停、恢复、取消的 SLA 与人工确认链如何定？
8. **历史事实**：旧 run 的事件消费记录、等待游标、设备命令事实是回填 Runtime 表还是只读归档？
9. **板链与物料身份**：迁移前是否重跑 `verify-extraction-plate-chain.py --strict` 与 RailZone 登记？（脚本记录了多次板位/扫码/缓存失败：`:140-162, 279-284, 411-420, 487-498`）
10. **缺失原语**：目标流程是否依赖 Compensation / Error / Boundary / Retry / SubProcess？依赖则改图还是补 Runtime？
11. **DDL 漂移**：物料身份增量未并入 `90_all_in_one.sql`，迁移前是否重新核对？
12. **等待点数量**：7 个还是 8 个？哪些 run 实际包含哪个版本？

---

## 8. 停止条件

在以下问题闭环前，Phase 3E 不得开工：

- 上述 12 个问题都有来自现场库/真机手册的可核验答案；
- `设备命令与事件关联规范` 的"未实施"状态被关闭，或明确本次迁移不依赖它；
- 目标流程不依赖当前不支持的 BPMN 原语，或 Runtime 已补齐并有验收；
- 新引擎与旧 `GraphRunner` 的双轨边界、回退路径、生产流量授权获得明确确认。
