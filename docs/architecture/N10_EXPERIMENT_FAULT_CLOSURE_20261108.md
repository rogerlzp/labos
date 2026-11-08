# N-10 实验故障影响闭包（第一切片，2026-11-08）

> 决策：ADR-FAULT-SCOPE-002 —— 同一实验内任一所属 Workcell 故障/扣留 ⇒
> 整个 ExperimentRun 全部节点、全部 Workflow 段停止；不允许 B 段继续。
> 本切片实现「运行中覆盖判定」的实验级闭包；逐候选调度仍属 P-03。

## 一、当前数据模型的限制（先说清楚）

ADR-ISLAND-002 要求的「Experiment↔Workcell 显式绑定 + Handoff」**尚未落库**：

- `experiment.island_id` 是单值；`workflow` 表没有 `island_id`；没有 workcell 关联表；
- 所以本切片只能从**已有资源树**推导实验涉及的 Workcell：
  1. 实验引用的 workflow（`experiment_process`）；
  2. 这些 workflow 的节点资源（`workflow_node.resource_id`）；
  3. 沿 `resource_node.parent_id` 上溯到 `kind='island'` 的根；
  4. 兜底 `experiment.island_id`（单值兼容语义）。

## 二、实现

| 位置 | 改动 |
|---|---|
| `ExperimentWorkcellScope`（新增） | 递归 CTE 推导实验的 Workcell 集合；兜底 experiment.island_id |
| `RuntimeHold.coveringExperiment`（纯函数） | 优先级 experiment > island(∈集合) > site；**任一所属 Workcell 扣留 = 整实验 HELD**；范围外 Workcell 不误伤 |
| `RuntimeHold.coveringForExperiment / coveringForExperimentChecked` | 查库版（读失败按 UNKNOWN 阻断，写路径抛异常 fail-closed） |
| `ExperimentExecutor`（3 处节点边界检查） | 从「只看 experiment.island_id」改为「按实验全部 Workcell 判覆盖」 |
| `RunControl.resume/stop`（2 处） | 恢复/停止前同样按实验全部 Workcell 判覆盖 |

## 三、不变量与测试

- 不变量：实验涉及的任一 Workcell 有 island 级扣留 ⇒ 实验判 HELD；
  Workcell 集合之外的扣留不阻断该实验；experiment/site 级语义不变；
- 纯单测：`workflow.ExperimentFaultScopeSelfTest` 7/0（范围内阻断 / 范围外不误伤 /
  experiment/site 优先级 / 无扣留 CLEAR）；
- `./scripts/test-all.sh` = 1127/0；
- 回归：4.17 实验级暂停、4.20 页面暂停/恢复（覆盖判定路径）。

## 四、边界（未完成项）

| 能力 | 状态 |
|---|---|
| 运行中实验按全部可推导 Workcell 判 Hold 覆盖 | ✅ 本切片 |
| Workcell 显式绑定 / Handoff 模型（ADR-ISLAND-002） | ❌ 仍缺，当前靠资源树推导 |
| 调度器逐候选评估（不同实验 Workcell 隔离） | 🟡 2026-11-08：dispatch / autoContinueHeldBatches 已逐候选按 coveringForExperiment 判定；UNKNOWN 仍全局阻断；uk_runtime_hold_active 多 Hold 索引改造未做 |
| 设备级故障 Hold（device scope） | ❌ 目前故障须落 island/experiment Hold |
| 展示层「哪些节点受影响」标注 | ❌ 执行层先一律阻断，展示后续补 |

## 四·补、显式 Workcell 绑定（2026-11-08）

此前只能从资源树推导 Workcell。本补丁增加显式绑定（ADR-ISLAND-002 的落地第一步）：

- 新表 experiment_workcell（99i，已拼入 90_all_in_one）：union(tenant, experiment, workcell)，
  外键 experiment ON DELETE CASCADE；
- ExperimentWorkcellScope.islandsOf 优先级：**显式绑定存在 ⇒ 只信显式绑定**；
  为空才回退资源树推导 + experiment.island_id；
- 产品写入口：GET /api/experiments/{id}/workcells、
  PUT /api/experiments/{id}/workcells body {workcellIds:[...]}（空数组=清空绑定）；
  PUT 带审计 experiment.workcells.set；
- 自测：ExperimentWorkcellScopeDbSelfTest 6/0（显式权威 / 清空回退 / 写入口 / 不存在 404）；
  test-all 1160/0；API 手工验证 GET→PUT→GET→清空。

仍缺：Handoff 配置/可达性门禁（ADR-ISLAND-002/004）、P-03 逐候选调度、
故障注入 e2e（A Workcell 故障 → B 段 0 命令）。

## 四·补二、跨 Workcell Handoff 配置/门禁（2026-11-09）

- 新表 experiment_handoff（99j）：experiment + from/to workcell + carrier/status/remark，
  唯一约束 (tenant, experiment, from, to)，实验删除级联；
- 产品写入口：GET/PUT /api/experiments/{id}/handoffs（body {handoffs:[{fromWorkcellId,toWorkcellId}]}）；
  - 起点/终点必须 >0、不能相同、且在实验显式 Workcell 绑定内；
- 执行门禁 HandoffGate：ExperimentExecutor.run 开跑前按 Workflow 链推导 Workcell 序列，
  相邻且不同的 Workcell 必须配置 Handoff 且 status != unknown，否则 fail-closed 抛错；
- 纯单测 HandoffGateSelfTest 6/0（完整/缺失点名/运行时 UNKNOWN/单 Workcell/无关配置/去重）；
  test-all 1192/0；API 手工验证 PUT/GET/非法 pair 400。

仍缺：运行时人工确认（manual_window 与 Handoff 关联）、故障注入 e2e（A Workcell 故障 → B 段 0 命令）。

## 四·补三、故障注入 e2e（P-03 隔离验证，2026-11-09）

脚本 verify-dispatch-isolation.py：

1. 两个只含 start→end 的实验 A/B，分别绑两个岛；
2. 给 A 岛抬 island Hold（DB 夹具，模拟 Workcell 故障/扣留）；
3. 异步提交两个实验；
4. 断言：A 保持 queued（不派发）、B 被正常派发（离开 queued）、扣留期间 A 不完成；
5. 清理 Hold/实验/流程。

实测结果：通过 3 / 失败 0（B 因空流程执行失败，但已证明被派发；A 全程 queued）。

这验证了 P-03 逐候选派发：无关实验不再被 A 的 Hold 全局挡住；A 自身被正确阻断。

## 五、下一步

1. 落 `experiment_workcell` 绑定表 + Handoff 门禁（ADR-ISLAND-002/004）；
2. P-03 完整项：`ExperimentScheduler` 按实验涉及 Workcell 集合逐候选评估 + 锁；
3. 故障注入 e2e：让 A Workcell 故障、验证 B 段 0 命令、整实验停。
