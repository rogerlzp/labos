# 人工介入记录 + 人工值守窗口页（L-01 / N-08 / L-04，2026-11-09）

> 目标：人工确认不只留一个窗口状态，还要回答「谁、何时、对哪块板/样本、从哪到哪、
> 结果如何」；同时给值班员一个真正能处置的页面（此前 webapp 对 manual-windows 0 命中）。

## 一、L-01 人工介入记录

新表 intervention_record（99k）：

| 列 | 说明 |
|---|---|
| manual_window_id / run_id / experiment_run_id | 关联窗口/执行 |
| operator | 确认人（当前 API 写 api，页面可扩展带用户） |
| decided_action | CONTINUE/RETRY/SKIP/ABORT/MANUAL |
| plate_barcode / sample_id | 板/样本标识 |
| from_location / to_location | 源/目标交接点 |
| result | waiting / resolved |
| note / created_at | 备注与时间 |

写入点：POST /api/manual-windows/{id}/decide 成功后自动落一条；
查询：GET /api/intervention-records?limit=N（RunApi）。

## 二、N-08 / L-04 人工值守窗口页

新增 /manual-windows 页面：

- 待决窗口表：执行链接、错误码/节点、allowedActions、决策原因、超时时间；
- 「确认/处置」弹窗：板号/样本/源交接点/目标交接点/备注 + 动作按钮；
- 提交即调用 decide 并刷新；下方展示 L-01 人工介入记录（时间/人/动作/执行/板/样本/交接点/结果）；
- viewer 只读（useReadOnly 禁用处置）；
- 20s 自动刷新。

导航：左侧「执行」组新增「人工窗口」；中英文文案齐。

## 三、验证

- `api.InterventionRecordDbSelfTest` 3/0（落库/列表/字段）；
- test-all 1195/0（纳入 DB 组）；
- 手工：插入 pending 窗口 → POST /decide（MANUAL）→ interventionRecordId 返回、
  GET /api/intervention-records 可见板/样本/交接点/结果；
- 页面 Playwright 冒烟：/manual-windows 渲染「人工值守窗口」「人工介入记录（L-01）」，无加载失败；
- `npx tsc --noEmit` 通过。

## 四、边界

- 运行时确认与 Handoff 的强关联（跨 Workcell Handoff 必须走人工窗口确认）尚未打通；
- operator 目前固定 api，尚未接入登录用户；
- N-08 的 pause_kind=waiting_human 与 manual_window 的自动建窗关系仍需在恢复策略里对齐。
