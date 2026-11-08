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
| 调度器逐候选评估（不同实验 Workcell 隔离） | ❌ P-03 完整项（当前 V1 全局闸门） |
| 设备级故障 Hold（device scope） | ❌ 目前故障须落 island/experiment Hold |
| 展示层「哪些节点受影响」标注 | ❌ 执行层先一律阻断，展示后续补 |

## 五、下一步

1. 落 `experiment_workcell` 绑定表 + Handoff 门禁（ADR-ISLAND-002/004）；
2. P-03 完整项：`ExperimentScheduler` 按实验涉及 Workcell 集合逐候选评估 + 锁；
3. 故障注入 e2e：让 A Workcell 故障、验证 B 段 0 命令、整实验停。
