# 设备命令账本 P-07 第一切片（2026-11-08）

> 对应工作项：P-07（命令账本 + Outbox；CREATED→…→UNKNOWN→RECONCILING；四个崩溃点不重复物理动作）。
> 本切片先做「单条指令账本 + 重启对账 + fail-closed 禁止重复下发」，
> 跨指令 Outbox 传输层与 UNKNOWN 人工核对闭环留 P-07.2。

## 一、解决的崩溃窗口

设备指令有物理副作用。原实现只有 `run_command`（执行结果落库）——
进程在「已下发、还没拿到回包」窗口崩溃时，重启后没有任何地方能回答：
机械臂到底动没动？再下发一次会不会重复搬同一块板？

本切片把每次下发拆成两个可查事实：

1. 派发前：`command_ledger` 落 `DISPATCH_STARTED`（`idempotency_key` 唯一）；
2. 回包后：`ACKED` / `FAILED`（带 error_code/error_message）；
3. 启动对账：超过 10 分钟仍是 `DISPATCH_STARTED` ⇒ 标 `UNKNOWN`，
   物理结果未知，必须人工确认后处置；
4. 同一 `commandId` 重复派发 ⇒ 账本拒绝，返回 `EC-99-0006`（fail-closed，不再动设备）。

## 二、表与状态

`pg_rebuild/schema/99f_command_ledger.sql`（已拼入 `90_all_in_one.sql`）：

| 列 | 说明 |
|---|---|
| `idempotency_key` | 当前=commandId（`r{run}-n{node}-e{exec}-s{step}-d{device}`），唯一 |
| `run_id/node_id/attempt/step_seq/device_id/method` | 定位与排障上下文 |
| `command_id/trace_id` | 传输层/链路追溯 |
| `state` | DISPATCH_STARTED / ACKED / FAILED / UNKNOWN |
| `error_code/error_message` | 失败或 UNKNOWN 原因 |
| `created_at/updated_at` | 对账时间依据 |

## 三、接入点

- `DriverStepExecutor.executeCommand(...)`：所有设备下发（含 Transfer 门/通道命令）都经过这里；
  派发前 `CommandLedger.begin`，回包后 `CommandLedger.finish`；账本写失败直接返回
  `EC-99-0002`（没有账本就不下发）；
- `ApiServer` 启动收尾：`CommandLedger.reconcileOnStartup(..., STALE_MINUTES=10)`，
  打印「标记 UNKNOWN N 条」；只标记，不自动重发；
- 测试：`driver.CommandLedgerDbSelfTest` 10/0（同 key 一行 / 终态重放拒绝 / 陈旧标 UNKNOWN /
  非陈旧不误标），已注册进 `scripts/test-all.sh` DB 组。

## 三·补、P-07.2：UNKNOWN 人工核对处置（2026-11-08）

启动对账把「派发中断」标 UNKNOWN 后，原来没有产品出口 —— 值班员只能查库。
P-07.2 补：

- command_ledger 增加 resolved_at / resolved_by（99h 迁移）；
- `GET /api/commands/unknown`：列出当前 UNKNOWN（id/key/run/node/method/command/error/时间）；
- `POST /api/commands/{id}/resolve`：body {action: confirm-done | confirm-failed, note}，
  只允许 UNKNOWN → ACKED/FAILED，带 resolved_at/resolved_by 留痕；二次处置返回 409；
- 审计 action=command.ledger.resolve；
- 自测：CommandLedgerDbSelfTest 16/0（列表可见 / 处置落 FAILED / 留痕 / 二次处置被拒）；
  手工验证：GET 列表 → POST confirm-failed → 列表清空、DB 状态 FAILED；

### 四个崩溃点覆盖情况

| 崩溃点 | 证据/处置 |
|---|---|
| 派发前 | begin 落 DISPATCH_STARTED 后才下发；无账本不下发（EC-99-0002） |
| 派发中 | 重启对账把陈旧 DISPATCH_STARTED 标 UNKNOWN |
| 回包后/结果落库前 | 同上标 UNKNOWN，人工核对后 resolve 成 ACKED/FAILED |
| 重复下发 | 同 commandId begin 拒绝（EC-99-0006）；UNKNOWN 处置幂等 |

尚未做：真实故障注入矩阵（kill -9 时序）、逻辑步骤级稳定 key、命令 Outbox 传输层。

## 四、当前边界（诚实说明）

| 能力 | 状态 |
|---|---|
| 单条指令派发前落账 / 回包终态 | ✅ 本切片 |
| 重启把陈旧 DISPATCH_STARTED 标 UNKNOWN | ✅ 本切片 |
| 同 commandId 重复下发 fail-closed | ✅ 本切片 |
| 逻辑步骤级幂等（跨 exec 计数重放） | ❌ P-07.2（需引入稳定 step key + op 序号） |
| 命令 Outbox 传输层 / 重试重放 | ❌ P-07.2 |
| UNKNOWN 的人工核对/处置入口（页面/API） | ❌ P-07.2 |
| 四个崩溃点完整回归（故障注入矩阵） | ❌ P-07.3 |

## 五、验证

- `./scripts/test-all.sh` = 1120/0（含 `CommandLedgerDbSelfTest` 10/0）；
- 设备链路回归：4.19 页面网关暂停、4.17 实验级暂停（含 ARM Home 真下发）；
- 启动日志出现「命令账本对账：标记 UNKNOWN 0 条（无遗留）」；
- `check-schema-sync` 产物一致。

## 六、下一步

1. P-07.2：稳定逻辑 key（run+node+attempt+step+op）+ 命令 Outbox；
2. UNKNOWN 的人工处置：页面列出 UNKNOWN 账本、确认物理位置后 resolve；
3. P-07.3：四个崩溃点故障注入（派发前/派发中/回包后/结果落库前）回归。
