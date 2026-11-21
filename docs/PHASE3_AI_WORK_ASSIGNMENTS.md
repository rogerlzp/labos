# PHASE3_AI_WORK_ASSIGNMENTS — 四 AI 并行交付分工与治理规范（Stage 0 Freeze）

> **所属总纲**：[PHASE3B_MASTER_CONTRACT.md](./PHASE3B_MASTER_CONTRACT.md)  
> **对侧契约**：[DEVICE_EXECUTION_CORE_CONTRACT.md](./DEVICE_EXECUTION_CORE_CONTRACT.md)（AI-1）· [DEVICE_GATEWAY_PROTOCOL.md](./DEVICE_GATEWAY_PROTOCOL.md)（AI-2）· [PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md](./PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md)（AI-3）  
> **目标数据库迁移**：[006_device_execution_contract_v1.sql](../db/workflow_runtime/006_device_execution_contract_v1.sql)  
> **跨语言黄金夹具清单**：[MANIFEST.sha256](../contract-fixtures/v1_1/MANIFEST.sha256)

---

## 1. 基线与分支纪律（强约束）

1. **阶段 0 合入前置**：4 条实现分支的正式开发**必须以阶段 0 骨架 PR 合入后的最新 `origin/main` SHA 作为统一起点**（具体 SHA 待阶段 0 骨架 PR 合入后由集成负责人公布）。
2. **分支命名与既有本地分支重基要求**：
   - AI-1（主力 1 · 执行核心）：`feat/phase3-device-execution-core`
   - AI-2（主力 2 · 网关与 Sidecar）：`feat/phase3-device-gateway-sidecar`
   - AI-3（辅助 1 · 独立模拟器与混沌验收）：`feat/phase3-device-simulator-chaos`
   - AI-4（辅助 2 · 标准工艺与运维剧本）：`feat/phase3-standard-workflows`  
     > **已存在本地工作区提示**：`.worktrees/phase3-standard-workflows` 当前本地基于 PR #17 的 Draft v1.8 提交（`48a2ad5c`）；在阶段 0 骨架 PR 合入 `main` 后，AI-4 必须先 `git rebase origin/main` 对齐最新 `006`、`V3 WaitSpec` 与 `device/contract/**`，再继续或提交 PR。
3. **四条公共安全红线**：
   - 严禁修改脏主工作区 `/Users/zhengpingli/work/中控`。
   - 严禁对 `192.168.124.2:5432/labdb` 或任何共享演练库执行未审批写入/迁移。
   - `labos.workflow.runtime.host.enabled` 保持默认值 `false` 至 Phase 3F 单独审批。
   - 严禁未经审批向真实实验室硬件发送设备控制命令。

---

## 2. 阶段 0 只读冻结资产清单（四分支一律只读消费）

阶段 0 骨架 PR 合入 `main` 后，以下资产成为四条实现分支的**只读共享基线**。任何 AI 不得在个人分支私自修改；确需调整时必须先提交 RFC 给集成负责人，待公共基线更新后四方统一 `rebase`：

- 总纲与契约文档：`docs/PHASE3B_MASTER_CONTRACT.md`、`docs/DEVICE_EXECUTION_CORE_CONTRACT.md`、`docs/DEVICE_GATEWAY_PROTOCOL.md`、`docs/PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md`、`docs/PHASE3_AI_WORK_ASSIGNMENTS.md`
- 数据库正式迁移与契约门禁：`db/workflow_runtime/006_device_execution_contract_v1.sql`、`db/workflow_runtime/002_schema_contract.sql`、`db/workflow_runtime/tests/test_pg_migrations.py`、`db/workflow_runtime/tests/test_pg_con_matrix.py`、`scripts/apply-workflow-runtime-migrations.sh`、`.github/workflows/workflow-runtime-db.yml`
- 共享 Java 契约包：`workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/device/contract/**`（含已从 `DeviceCommandAdapter` 迁出且无并存副本的 `DeviceCommandEnvelope`、`ExecutionEvidence`、`ContractV1ErrorCodes`、`SecurityMode` 等）
- 编排契约 v3 与宿主配置边界：`GraphPlan.java`、`GraphPlanCodec.java`、`BpmnGraphCompiler.java`、`labos-kernel/src/boot/java/com/labos/boot/WorkflowRuntimeHostProperties.java`、`labos-kernel/src/boot/java/com/labos/boot/WorkflowRuntimeKernelConfiguration.java`
- 跨语言黄金报文与契约不变式骨架：`contract-fixtures/v1_1/*.json`、`contract-fixtures/v1_1/MANIFEST.sha256`、`workflow-runtime/src/test/java/com/labos/kernel/workflow/runtime/device/contract/**`

---

## 3. 四 AI 职责、排他目录与禁止越界边界

### 3.1 AI-1：主力一 — Device Execution Core（执行内核与物理安全）
- **分支**：`feat/phase3-device-execution-core`
- **排他可写目录**：
  - `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/command/**`
  - `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/bridge/**`
  - `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/safety/**`
  - `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/recovery/**`
  - `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/persistence/DeviceExecution*Repository.java`
  - **定点独占权限（仅限 AI-1）**：仅允许修改 [WorkflowRuntimeRepository.java](../workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/persistence/WorkflowRuntimeRepository.java) 的 3 处精确切片：
    1. `resolveUnknownCommand` → 升级为 `reconcileAndSettleUnknownCommand` 原子解冻事务（`L1700` 附近）；
    2. `applyCommandEventOrReject` 12 态转移矩阵（补齐 `DELIVERY_UNKNOWN → CANCELLED`，`L4428` 附近）；
    3. `recoverInterruptedAttempt` 未决命令过滤条件（修正误伤 Step-0 已落终态的 `SUCCEEDED`/`FAILED`，`L5355` 附近）。
- **核心交付**：12 态完整状态机与证据双通道硬校验（通道 A 硬件事实 vs 通道 B T21 人工核验）、`PhysicalSafetyGate`（`CommandResourceImpactSpec` + 全局锁序 + `UNKNOWN` 整组隔离）、G5.1 早到事件 `wait_early_signal_buffer` 路由及 V3 `WaitSpec.deadlinePolicy` 执行语义、将 Stage 0 的 Inv-1..6 `@Disabled` 骨架落地为可实跑模块测试。
- **严禁**：修改 `gateway/**`、`sidecar-dotnet/**`、`simulators/**`、标准工艺定义或冻结的 `device/contract/**`（如需变更提 RFC）。

### 3.2 AI-2：主力二 — Device Gateway & Workstation Sidecar（通信与工位代理）
- **分支**：`feat/phase3-device-gateway-sidecar`
- **排他可写目录**：
  - `workflow-runtime/src/main/java/com/labos/kernel/workflow/runtime/gateway/**`
  - `sidecar-dotnet/**`
  - `docs/Sidecar*`
- **分步优先交付顺序**：
  1. **阶段 1A (`3C-1` 关键路径，最高优先)**：演进 `TcpDeviceConnection` / `DeviceGateway` 为方向 A `TLS13_MTLS`（与回环限定 `DEV_LOOPBACK_PLAINTEXT`）+ C2 工位级多设备复用（替换 Stage 0 保留的 `@Deprecated fromDeviceRegistryRows`）；实现 `.NET 10` Workstation Sidecar SQLite WAL `sidecar_command_journal` + 按 `deviceId` FIFO `sidecar_event_outbox` + NDJSON v1.1 全 13 帧与 `ExecutionEvidence` 上报；**彻底移除新链路中 `LabSepWireCodec` 与 `#!LABSEP!#` 逻辑**；于阶段 1 起点发布只读 `IProtocolAdapter` 接口签名快照（解决 `OQ-2`）；
  2. **阶段 1B (`3C-2`)**：迁移 `S100`、`ASpin`、`LHC`、`Gen5` 等物理适配器挂载至新 Sidecar，严禁 import `labos-kernel/.../driver/tcp/TcpDriverAdapter` / `TcpPersistentDriver`。
- **退出可执行 grep 门禁**：`rg -n "LABSEP|LabSepWireCodec|labos:link" workflow-runtime/ sidecar-dotnet/` 在 AI-2 退出阶段 1 时必须返回 **0 命中**。
- **严禁**：修改 `WorkflowRuntimeRepository.java`、`safety/**`、`command/**` 或 AI-3 独立模拟器。

### 3.3 AI-3：辅助一 — Independent Simulator & Chaos Verification（独立模拟与对抗验收）
- **分支**：`feat/phase3-device-simulator-chaos`
- **完整架构规范**：见独立架构文档 [PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md](./PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md)（已归并受控于本分工总纲）。
- **排他可写目录**：
  - `workflow-runtime/src/test/java/com/labos/kernel/workflow/runtime/chaos/**`
  - `simulators/device-virtual-sim/**`
  - `scripts/test-phase3d-*`
  - `scripts/verify-phase3-*`
- **三条铁律**：
  1. **严格独立**：Layer A Wire Chaos Harness 与 Layer B Device Virtual Simulator **严禁** import 或复用 AI-2 的 `gateway/**`、`sidecar-dotnet/**` 或任何生产 Driver；仅只读消费冻结契约与校验过 SHA-256 的 `contract-fixtures/v1_1/*.json`。
  2. **零生产代码改动**：发现缺陷只提交最小复现 + 未裁剪原始日志 + 失败用例给 AI-1/AI-2 修复，随后在独立分支回归验证。
  3. **四态裁决**：只允许签发 `PASS` / `FAIL` / `BLOCKED` / `UNKNOWN`；任何 `skipped`、未执行、`@Disabled`、模拟器自证或无原始日志的结果一律记为 `UNKNOWN`。

### 3.4 AI-4：辅助二 — Standard Workflows & Runbooks（标准工艺与运维闭环）
- **分支**：`feat/phase3-standard-workflows`（须先 `rebase` 至阶段 0 合入后的 `origin/main`）
- **排他可写目录**：
  - `workflow-runtime/src/main/resources/workflows/standard/**`
  - `workflow-runtime/src/test/java/com/labos/kernel/workflow/runtime/standard/**`
  - `scripts/build-standard-workflows.py`、`scripts/test-phase3e-*`
  - `docs/runbooks/**`、验收数据剧本
- **核心交付**：基于冻结 `V3`（`WaitSpec.deadlinePolicy`）与 `DeviceOperationCapability` / `CommandResourceImpactSpec` 构建全新标准工艺 `EXT-STD-SINGLE-V1`（单板首发）与 `EXT-STD-BATCH4-V1`（四机并行），提供验收场景剧本与运维 Runbook（含 `CORE §6.1` 迁移核验与《网络可达与 Lab-CA 运维核验单》模板）。
- **严禁**：修改 `WorkflowRuntimeRepository.java`、`gateway/**`、`sidecar-dotnet/**` 或自行签发工艺验收 `PASS`（必须经 AI-3 黑盒执行 + 集成负责人审计后裁决）。

---

## 4. 集成负责人对 AI-3 `OQ-1..OQ-5` 的正式裁决

以下裁决于阶段 0 随本文档一并冻结，关闭 [PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md §11](./PHASE3B_AI3_INDEPENDENT_TEST_ARCHITECTURE.md#11-风险与开放问题待集成负责人裁决) 全部 5 项开放问题：

| 编号 | 裁决结论（Stage 0 Closed） | 执行约束与可验证标准 |
| :--- | :--- | :--- |
| **OQ-1** | **仅共享冻结契约源，禁止共享 AI-2 运行时代码** | Layer A（Chaos Harness）与 Layer B（Virtual Simulator）只允许从 `contract-fixtures/v1_1/*.json` 与 [DEVICE_GATEWAY_PROTOCOL.md §2](./DEVICE_GATEWAY_PROTOCOL.md) 读取能力 schema，**严禁**依赖或复用 AI-2 的任何连接/协议/适配器实现类 |
| **OQ-2** | **阶段 1 起点由 AI-2 冻结发布只读 `IProtocolAdapter` 接口签名** | AI-2 在 `3C-1` 启动时于 `docs/Sidecar_ProtocolAdapter_Contract.md`（或 `.NET` 纯接口程序集契约文件）冻结 `SendAsync` / `QueryHardwareAsync` / `CancelAsync` 方法签名（不含实现），AI-3 据此独立构建 Layer B 虚拟设备实现 |
| **OQ-3** | **Inv-4 并发“零死锁”定量门禁固化** | Stage 2 `Inv-4` 压测判定 `PASS` 必须满足：持续运行 **≥ 60 秒** 且完成 **≥ 100 轮**并发派发 + T21 解冻竞争周期，期间目标数据库 `pg_stat_database.deadlocks` 增量 **== 0**、PG17 服务端日志零 `40P01` 死锁记录，并附带采样 `pg_locks` / `pg_stat_activity` 快照与完整 PG 日志 |
| **OQ-4** | **黄金夹具版本与漂移检测机制已在 Stage 0 落地** | 固定协议版本 `"1.1"` 并由 `contract-fixtures/v1_1/MANIFEST.sha256` 锁定全量 10 组 JSON 文件校验和；Java（`ContractFixturesV1_1ValidationTest`）、`.NET 10` Sidecar 与 AI-3 Layer A 均强制 Fail-Closed 校验哈希，任何未更新 Manifest 的篡改或漂移立即阻断 |
| **OQ-5** | **强制独立临时 PostgreSQL 17 隔离库运行** | AI-3 每次混沌/回归测试**必须**自启独立临时 PG17 实例（`initdb` / CI 独立数据库容器），运行结束后销毁临时目录；严禁复用或污染共享库 `192.168.124.2:5432/labdb` 及其他 AI 的测试库 |

---

## 5. 五阶段门禁与跨 AI 缺陷交接闭环

1. **阶段推进顺序**：`Stage 0 (契约冻结)` → `Stage 1 (4 AI 并行起步 + AI-2 3C-1 优先)` → `Stage 2 (3D 混沌联调，6 不变式 100% PASS)` → `Stage 3 (EXT-STD-SINGLE-V1 → EXT-STD-BATCH4-V1 + 网络核验单签字)` → `Stage 4 (独立黑盒总验收 + 四态报告 + 旧链路退役 PR)`。
2. **缺陷交接闭环**：
   `AI-3 复现 → 提交最小复现 + 未裁剪原始日志 + 黑盒 SQL 证据 → 集成负责人分诊归属（Core → AI-1 / Wire·Sidecar → AI-2 / 工艺 → AI-4）→ 责任 AI 在己方分支修复并注明 Commit SHA → AI-3 独立回归重跑更新四态表`。
