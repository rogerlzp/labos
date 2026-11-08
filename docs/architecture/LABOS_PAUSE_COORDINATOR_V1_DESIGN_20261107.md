# PauseCoordinator V1 最小设计（S-02 / P-06）

日期：2026-11-07
目标分支：`review/labos-architecture-20261107`
基线：`main` @ `3debf7858cc3479a1cfae941837081015a41c137`
关联：`ADR_INDEX.md`（ADR-PAUSE-001/002/003/006/007、ADR-STATE-001）、`REMEDIATION_STATUS.md`（S-01~S-05、P-06/P-07、L-02/L-03）、`LABOS_RUN_MODEL_MAPPING_S01_20261107.md`、`LABOS_STATE_MACHINE_ACCEPTANCE_CONTRACT_20261107.md`。

> 本文只定义 **V1 最小范围**。它不切换 API/UI 的 `PAUSED` 语义（那是 S-04-B），不引入完整聚合器（L-05 = DEFERRED），不改状态枚举值域（L-02 独立 PR）。

---

## 0. 结论摘要

1. **分两批落地**：V1 先做「安全闸门 fail-closed + 统一入口适配层 + 精确 Hold + 资源释放缺陷修复」；V2 再做「Hold/Run Control/Event/Outbox 单事务」与「命令账本」。
2. **不新建第三套子系统**（ADR-PAUSE-007）：`PauseCoordinator` 是**适配层**，内部按 target 类型路由到既有 `PauseManager`（Workflow Run）或 `RunControl`（Experiment Run）。
3. **外部语义不变**：V1 不改变 HTTP 响应与 `run.status` / `experiment_run.status` 的既有取值，`M-01` 三层真停契约与 `RunControl.resume()` 作废旧 paused run 的行为**必须保留**。
4. **最优先的不是新状态，而是 fail-closed**：`RuntimeHold` 现有 5 处安全闸门查询在 `SQLException` 时静默返回「无扣留」，等于「读不到 Hold ⇒ 继续派发」，必须改为三态 `CLEAR / HELD / UNKNOWN`，UNKNOWN 一律阻断。

---

## 1. 入口矩阵（V1 必须收敛的写入入口）

| # | 入口 | 路由 | 目标模型 | 现有控制器 | 动作 |
|---|---|---|---|---|---|
| E-1 | `RunApi` | `POST /api/runs/{id}/pause` | Workflow Run | `PauseManager.pause()` | PAUSE |
| E-2 | `RunApi` | `POST /api/runs/{id}/resume` | Workflow Run | `PauseManager.resume()` + `WorkflowService.resumeAsync()` | RESUME |
| E-3 | `RunApi` | `POST /api/runs/{id}/dismiss`（批量/单条） | Workflow Run | `PauseManager.dismiss*()` | DISMISS |
| E-4 | `ExperimentApi` | `POST /api/experiments/runs/{id}/pause` | Experiment Run | `RunControl.pause()` | PAUSE |
| E-5 | `ExperimentApi` | `POST /api/experiments/runs/{id}/stop` | Experiment Run | `RunControl.stop()` | STOP |
| E-6 | `ExperimentApi` | `POST /api/experiments/runs/{id}/resume` | Experiment Run | `RunControl.resume()` + `scheduler.resumeRun()` | RESUME |

读取入口（V1 不改 `GET /api/runs/paused`、`GET /api/manual-windows`）：`GET /api/runtime/holds`
按 `ADR-PAUSE-010`（Q-2=B）**新增 `unknown` / `unknownReason` 字段** —— 读不到扣留（`UNKNOWN`）
会让调度器 fail-closed 阻断新派发，必须让运维在界面可见；既有 `anyActive` / `holds` 逐字段不变。

## 2. 安全闸门调用点（V1 必须 fail-closed）

| # | 调用点 | 现语义 | 触发后果 |
|---|---|---|---|
| G-1 | `RuntimeHold.anyActive()` → `ExperimentScheduler.dispatch()` | SQL 失败 ⇒ `false`（放行） | **扣留读不到仍派发新实验** |
| G-2 | `RuntimeHold.coveringFor(exp,island)` → scheduler 逐候选 + `RunControl.resume` | SQL 失败 ⇒ `null`（不覆盖） | 候选被误派发 / 误放行 Resume |
| G-3 | `RuntimeHold.coveringForWorkflow(wf)` → `DriverStepExecutor`（节点边界 / abort 轮询） | SQL 失败 ⇒ `null`（继续跑） | **扣留读不到仍继续执行物理动作** |
| G-4 | `RuntimeHold.active()`（内部） | SQL 失败 ⇒ 空列表 | 上述三项的共同根因 |

铁律：**查询失败、超时、异常 ⇒ UNKNOWN ⇒ 阻断派发与 Resume**（验收契约 §3.4 / §5.1）。

## 3. 三层状态模型（V1 只**读**，不引入新列）

| 层 | 载体（现状） | V1 角色 |
|---|---|---|
| Run Control State | `run.status` / `experiment_run.status` + `pause_kind` / `pause_reason` | 兼容投影，取值不变 |
| Hold Ledger | `runtime_hold`（`scope` / `scope_id` / `kind` / `reason` / `released_at`） | **权威阻断事实**，V1 精确按 `hold_id` 管理 |
| Command Execution State | `run_command`（步结束才 INSERT） | V1 不实现账本；`UNKNOWN/RECONCILING` 归 P-07 |

`L-02`（`experiment_run.status` 扩容 + `PAUSE_REQUESTED`/`PAUSING` 中间态）**不在 V1**：V1 不写中间态，因此不会与既有 e2e 判据冲突。

## 4. 适配边界（S-01 §7 的最小落地）

```
业务入口（RunApi / ExperimentApi）
        │  PauseCommand（统一请求对象）
        ▼
   PauseCoordinator ──── 鉴权 / 幂等键 / 路由 / 审计
        ├── WorkflowRunAdapter  ──▶ PauseManager            （run.status/pause_kind）
        └── ExperimentRunAdapter ──▶ RunControl + RuntimeHold（experiment_run + runtime_hold）
```

**统一请求对象最低字段**（S-01 §7.1）：

- `requestId`（幂等键）
- `targetType` / `targetId`（`WORKFLOW_RUN` / `EXPERIMENT_RUN`）
- `action`（`PAUSE` / `RESUME` / `STOP` / `DISMISS`）
- `source` / `kind` / `reasonText`
- `requestedBy` / `requestedAt`
- `resultState`（`ACCEPTED` / `COMPLETED` / `REJECTED` / `UNKNOWN`）

**职责红线**：Coordinator **不直接控制设备协议、不伪造物理停止、不写最终 `PAUSED` 事实**。

## 5. 事务策略（V1 vs V2）

- **V1（本次）**：先保证**幂等 + 精确 Hold + fail-closed**；`RuntimeHold` / `RunControl` 各步仍用各自连接，但**任何一步失败必须让整体返回失败**，不得把「部分成功」当成功（现状 `RunControl.pause()` 的 Hold 与 `markCoveredPaused` 分属不同连接，失败静默）。
- **V2（后续 PR，P-06 完整项）**：Hold + RunControl intent + Event/Outbox 单事务提交；需要给 `RuntimeHold` / `RunControl` / `EventStore` 增加「外部 `Connection` 入参」重载，并定义恢复扫描。
- V1 **不**引入 Outbox（`L-03` 并入 `P-07`）。

## 6. 首批实施清单（V1，可独立验收）

| 序号 | 工作 | 对应闸门 | 验收 |
|---|---|---|---|
| V1-1 | `RuntimeHold` 三态：`activeChecked()` 抛/标 UNKNOWN；`coverageFor` / `coverageForWorkflow` / `siteCoverage` 返回三态 | G-1~G-4 | 单测：注入查询失败 ⇒ UNKNOWN ⇒ 阻断 |
| V1-2 | 5 处调用点改为「UNKNOWN 视为阻断」 | G-1~G-3 | 既有 `MIN_PURE` / `MIN_MATRIX` / `MIN_PAUSEEXP` 不降 |
| V1-3 | 修 `PauseManager.releaseResources()` 通道释放 SQL 参数错位（`runId` 被绑到 `device_port.tenant_id`） | — | DB 单测：释放后 `device_port_usage.in_use = 0` |
| V1-4 | 新增 `PauseCoordinator` 适配层，E-1~E-6 全部经过它；外部响应不变 | — | e2e 4.17 = 13/0、4.18 = 5/0、2.7 = 55/0 |
| V1-5 | Coordinator 级幂等：同 `requestId` 重复请求不重复建 Hold | — | DB 单测：重复 pause ⇒ Hold 数不增 |
| V1-6 | `GET /api/runtime/holds` 暴露 `unknown` / `unknownReason`（`ADR-PAUSE-010`，Q-2=B）；前端「现场扣留」卡显式提示 | — | 既有 `anyActive` / `holds` 不变；读库失败 ⇒ `unknown=true` |

## 7. 验收与证据要求

- `./scripts/ci-gate.sh --all`：**0 SKIP**，段 0 阈值「只升不降」通过。
- M-01 回归：2.7 矩阵 55/0、4.17 实验级暂停 13/0、4.18 A1 真停 5/0。
- 新增单测计入 `MIN_PURE`（阈值需同步上调，不得下调）。
- 状态表 `REMEDIATION_STATUS.md` 只在有 CI/e2e 证据后更新为 `IN_PR` / `DONE`。

### 7.1 证据回填（2026-11-08，V1-1~V1-6）

| 项 | 证据 |
|---|---|
| V1-1 | `RuntimeHoldSelfTest` 三态 11 条（`CLEAR` 放行 / `HELD` 阻断 / `UNKNOWN` 阻断 / 覆盖判定纯函数 4 条 / 读库失败 fail-closed 3 条） |
| V1-2 | 5 处调用点改「`UNKNOWN` 视为阻断」；`MIN_PURE` 未降 |
| V1-3 | `PauseManagerSelfTest.testReleasePortUsage`：`releaseResources()` 后 `device_port_usage.in_use = 0` |
| V1-4 | `ci-gate.sh --all`：2.7 = 55/0、4.17 = 13/0、4.18 = 5/0、e2e 24/0 全绿，外部响应未变 |
| V1-5 | 新建 `PauseCoordinatorDbSelfTest`（已注册进 `scripts/test-all.sh` 的 `DB` 列表）：**16/0** —— 同 `requestId` 重复 pause ⇒ `pause_request` 仅 1 行且 `runtime_hold` 生效数不增；无 `requestId` 不写 `pause_request`（旧行为）；目标不存在时 `abandon` 撤回登记 ⇒ 同 `requestId` 可真正重跑。分片 `99d_pause_request.sql` 已拼入 `90_all_in_one.sql` 且 `check-schema-sync.sh` 同源；已应用到测试库 `192.168.124.2/labdb` |
| V1-6 | `RuntimeHold.activeView()` 三态 + `RunApi` 的 `GET /api/runtime/holds` 回 `unknown` / `unknownReason`（实测：`{"anyActive":false,"unknown":false,"unknownReason":null,"holds":[]}`，既有字段不变）；前端「现场扣留」卡改用 `unknown` 分支显式提示（`client.ts` / `Experiments.tsx` / `zh-CN.ts` / `en-US.ts`，`tsc` 0、`scan-i18n.py` 硬错误 0） |
| 总 | `./scripts/ci-gate.sh --all` = **门禁通过（无跳过）/`GATE_EXIT=0`**；段 0 阈值「只升不降」通过；纯逻辑 664/0。**注**：V1-5 是 DB 单测，不计入 `MIN_PURE`（阈值维持 664 不下调） |

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| fail-closed 后 DB 抖动导致调度器频繁阻断 | UNKNOWN 阻断 + 明确日志与指标；恢复由人确认，不自动放行 |
| Coordinator 改变既有响应体 | V1 只做路由与幂等，**响应体逐字段保持不变**；对照 `ExperimentApi` 现有 JSON |
| 误改 M-01 作废旧 run 逻辑 | `RunControl.resume()` 内的作废语句原样保留，新增回归 |

## 9. 原未决问题（已裁决，2026-11-07）

| # | 问题 | 选项 | 裁决 |
|---|---|---|---|
| Q-1 | V1 是否需要新建 `pause_request` 持久化表承载幂等键？ | A：复用 `runtime_hold` + 审计；B：新建 `pause_request` 表（更重，但幂等可查） | **B**（`ADR-PAUSE-009`）：新建 `pause_request` 表，已由 V1-5 落地 |
| Q-2 | UNKNOWN 的呈现：`GET /api/holds` 是否返回 `unknown=true` 让运维可见？ | A：V1 只落日志；B：V1 即在 API 暴露 | **B**（`ADR-PAUSE-010`）：V1 即在 API 暴露，已由 V1-6 落地 |
| Q-3 | `L-02`（状态列扩容 + 中间态）与 V1 的先后：V1 不做中间态，是否先合 V1 再合 L-02？ | 见 `REMEDIATION_STATUS.md` Step 7 依赖序 | **`L-02` 先于 V1**（`ADR-PROCESS-003`）：V1 依赖 L-02 的状态表达能力 |

---

**签署**：执行方 LabOS 内核开发（Trae AI）。本设计为实施前登记稿，代码落地后回填 §7 证据。
