# P-08 第一切片：物理结果未知的 run 启动隔离（QUARANTINED，2026-11-09）

> P-08 原范围：资源与物理位置三维分离、启动恢复隔离 QUARANTINED、TransferTransaction + 物理确认。
> 本切片先做「启动恢复隔离」：崩溃时设备命令物理结果未知的 run，不再按普通 failed/recoverable
> 放回执行，而是隔离等人工确认。

## 一、规则

- 启动时命令账本已对账（P-07）：陈旧 DISPATCH_STARTED → UNKNOWN；
- run 状态 running/failed 且存在 UNKNOWN/DISPATCH_STARTED 命令 ⇒ 标 `quarantined`：
  - `run_status` 枚举新增 quarantined；
  - `input.quarantine=true` + `quarantineReason`，`recoverable=false`；
  - 若能映射到实验，抬 experiment 级 Hold（kind=human），整实验停；
- 人工确认物理状态后 `POST /api/runs/{id}/quarantine/release`：
  - run → failed，error_code=EC-QUARANTINE-CONFIRMED；
  - 解除隔离时抬的实验级 Hold；
  - 落 intervention_record（action=QUARANTINE_RELEASE，L-01）；
  - 二次解除返回 409。

## 二、实现位置

| 文件 | 改动 |
|---|---|
| pg_rebuild/schema/99l_run_quarantine.sql | run_status 增加 quarantined |
| StartupRecovery | quarantineUncertainRuns（可限指定 run，自测用）/ resolveQuarantine |
| ApiServer 启动收尾 | 命令账本对账后调用隔离，打印隔离清单 |
| RunApi | POST /api/runs/{id}/quarantine/release + intervention_record |
| StatusVocabulary + webapp status fallback | run scope 增加「隔离待确认」 |

## 三、测试

- `workflow.QuarantineDbSelfTest` 10/0：隔离 / input 标记 / 实验 Hold / 解除 / 错误码 /
  Hold 释放 / 二次解除 false；
- test-all 1205/0；T0 门禁；tsc 通过；
- API 手工验证：quarantined → release 200（interventionRecordId）→ 二次 409。

## 四、边界（P-08 剩余）

1. 资源与物理位置三维分离（resource / booking / physical position 独立事实）未做；
2. TransferTransaction + 物理到位确认未做；
3. 隔离 run 的页面入口（目前 Runs 列表状态可见，无单独处置页）；
4. QUARANTINED 与人工窗口/Handoff 的联动仍待做。
