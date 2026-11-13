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

## 三·补、隔离处置页（2026-11-09）

- 后端：GET /api/runs/quarantined（runId/runNo/errorCode/reason/createdAt/experimentId）；
- 前端：/quarantined 页（「隔离处置」菜单）列出隔离 run，确认弹窗填板号/说明后
  调 POST /api/runs/{id}/quarantine/release；20s 自动刷新；viewer 只读；
- 页面 Playwright 冒烟：标题/行渲染正常、无加载失败；
- 清理：确认后 run→failed、实验 Hold 释放、intervention_record 落证。

## 三·补二、启动 settle 不清理物理未知 run（2026-11-09）

Batch 2 要求「启动恢复不无条件清理设备锁」。

- StartupRecovery.settle 的扫描 SQL 增加排除条件：命令账本存在
  UNKNOWN/DISPATCH_STARTED 的 run 不进入普通 settle（不标 failed、不释放设备锁）；
- 这些 run 只由 quarantineUncertainRuns 隔离为 quarantined，设备锁保留到人工确认；
- QuarantineDbSelfTest 12/0 增加「settle 跳过 / 仍 running / 隔离」3 条断言。

## 三·补三、物理位置证据（2026-11-09）

- 新表 position_evidence（99o）：plate/resource/evidence_type(MANUAL/SCAN/SENSOR/DEVICE_REPORT)/
  run/operator/note/created_at；
- 人工盘点（PauseManager.locatePlate / locatePlateTx）成功后自动落 MANUAL 证据；
- API：POST /api/plate-position-evidence（记录）、GET（按板号过滤列表）；
- api.PositionEvidenceDbSelfTest 3/0；PauseManagerSelfTest 清理证据夹具；
- test-all 1221/0（停 API）。

仍缺：扫码/传感器证据接入、TransferTransaction 把「运动报告与位置确认」合并成事务。

## 三·补四、TransferTransaction + 物理确认（2026-11-09）

- 新表 transfer_transaction（99p）：run/板/源目标资源/运动报告/状态/确认信息；
- 驱动执行 Transfer 时自动创建事务：设备成功回包 → REPORTED（仅运动报告），
  失败/异常 → UNKNOWN；**不会自动变 CONFIRMED**；
- POST /api/transfers/{id}/confirm（evidenceType=SCAN/MANUAL/...，operator/note）：
  只允许 REPORTED/UNKNOWN → CONFIRMED，并落 position_evidence；GET /api/transfers 列表；
- TransferTransactionDbSelfTest 9/0（创建/报告/确认/证据/重复拒绝/失败 UNKNOWN）；
- test-all 1230/0（停 API）。

仍缺：扫码/传感器自动接入（当前人工调 API）、与调度「目标位置确认后才允许下一步」的强门禁。

## 三·补五、按位置确认的恢复强门禁（2026-11-09）

- PauseManager.resume / PauseCoordinator.resumeWorkflowRun 增加门禁：
  该 run 存在 REPORTED/UNKNOWN 的 transfer_transaction 时，继续/重试一律拒绝，
  提示先扫码/人工确认（POST /api/transfers/{id}/confirm）；ABORT 仍允许；
- 依据：设备成功回包只是运动报告，位置未确认不能继续下一动作；
- PauseManagerSelfTest 19/0（未确认拒绝恢复 / 确认后恢复成功）；
- test-all 1240/0（停 API）。

## 四、边界（P-08 剩余）

1. 资源与物理位置三维分离（resource / booking / physical position 独立事实）未做；
2. TransferTransaction + 物理到位确认未做；
3. 隔离 run 的页面入口（目前 Runs 列表状态可见，无单独处置页）；
4. QUARANTINED 与人工窗口/Handoff 的联动仍待做。

---

## 五、后续变更 · M 方案自动位置确认（2026-11-13）

> 本节记录 2026-11-09 之后的设计变更；**上面各节是当时口径，保留不改以便追溯**。
> 用户 2026-11-13 拍板 M，要求「按严谨方式」实施。完整设计见
> `docs/评审记录/20261113-02-物料追踪-评审检查清单.md`「区 4 定稿方案 · M」。

- **CONFIRMED 的证据来源扩展**：人工盘点/扫码/传感器 **+** `DEVICE_REPORT`。
  `DEVICE_REPORT` 仅在九项条件全部满足、且 `LABOS_TRANSFER_AUTO_CONFIRM` 开关
  （**默认关、按 workflow 生效**：空=关 / `1`=全局 / 逗号分隔的 wf id=仅这些流程）
  启用时由 `TransferTransaction.confirmByDeviceReport` 写入；与人工 `confirm`
  共用状态白名单 CAS 与同一组 mapper，不另开状态机。
  ⚠️ 不设全局：全库 23 个 wf 含 Transfer 节点，只有 1000830 配了板号。
- **恢复强门禁判定不变**：`PauseManager.resume` / `PauseCoordinator.resumeWorkflowRun`
  的 `count(status IN ('REPORTED','UNKNOWN')) > 0` 保持原样。自动确认提供的是一条
  合法的 CONFIRMED 路径，**不是短路门禁**；fail-closed 不丢。
- **位置登记入口**：`DriverStepExecutor.executeWithPlan` 成功分支 →
  `PositionLock.noteTransferMove`（复用 `takePlate/putPlate`，写 audit 两行：
  取板 `KNOWN→IN_TRANSFER`、放板 `IN_TRANSFER→KNOWN`）。
  `writeStep/notePlateMove` 不参与，仍只服务 Deposit/Withdraw。
- **板号单一真源**：`PositionLock.resolvePlateBarcode` 统一
  `barcode/plate/plateBarcode/plateId/plateID/plate_id`，拒绝数值 `plate.id`、
  未替换的 `${}` 与 `AUTO-` 占位（后两者会造出假板或让两块板串账）。
- **补既有缺口**：`putPlate` 后 `plate.position_status` 仍停在 `IN_TRANSFER`，
  自动确认时由 `PauseMapper.updatePlatePositionStatus` 写回 `KNOWN`。
- **回滚**：摘掉 `LABOS_TRANSFER_AUTO_CONFIRM` 环境变量即恢复改造前行为
  （代码保留但默认不生效）。

### 仍未闭合

1. 位置写入沿用既有「独立事务 / 独立连接」语义（audit 也是独立连接），
   `noteTransferMove` 与 `confirmByDeviceReport` **不是同一个数据库事务**；
   部分成功而确认失败会留下不一致，靠开关默认关 + 人工复核兜底。要做强原子需重构
   `PositionLock` 的事务边界，属后续改造。
2. 简化版 20 节点为单板主线（`${mainPlate}`），多板 / 并行分支需按节点写死不同板号。
3. 真实物理确认（扫码 / 传感器）仍未接入；`DEVICE_REPORT` 是设备侧闭环证据，
   不等价于独立物理确认。
