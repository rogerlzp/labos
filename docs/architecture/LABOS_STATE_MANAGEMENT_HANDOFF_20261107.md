# LabOS 状态管理功能：交接与继续实施说明

日期：2026-11-07  
目标分支：`review/labos-architecture-20261107`  
基线：`main` @ `3debf7858cc3479a1cfae941837081015a41c137`  
用途：交给另一位 AI / 工程实施者继续完成状态管理功能。本文是实施交接说明，不取代 ADR 和唯一整改状态表。

---

## 0. 给接手者的第一句话

**不要直接重写状态机，也不要先改 Pause API。先读本文件列出的 ADR、状态表和验收契约，确认工作树与目标分支，再从 S-04-A 的实际运行验证开始。**

目前已经完成的是：领域语义讨论、两套 Run 模型的源码映射、状态机验收契约，以及 S-04-A 测试脚本的部分增强。

目前没有证据证明的是：S-04-A 在目标环境运行通过、CI 全量通过、S-04-B API/UI 语义迁移完成、UNKNOWN fail-closed 已实现、状态聚合器已实现、真机安全行为通过 HIL。

**不得把“文档已写”“代码已推送”“脚本静态检查通过”写成“功能已完成”。** 状态只能按 `REMEDIATION_STATUS.md` 的证据规则更新。

## 1. 唯一事实来源与必读顺序

请按以下顺序阅读，不要从旧归档文档自行恢复已作废结论：

1. [ADR_INDEX.md](ADR_INDEX.md)：唯一架构决策真源。当前 ACCEPT 决策及 SUPERSEDED 清单均以它为准。
2. [REMEDIATION_STATUS.md](REMEDIATION_STATUS.md)：唯一工程状态真源。工作项状态、依赖、PR 归属以它为准。
3. [LABOS_STATE_MACHINE_ACCEPTANCE_CONTRACT_20261107.md](LABOS_STATE_MACHINE_ACCEPTANCE_CONTRACT_20261107.md)：暂停/恢复的事实、投影、门禁及完成条件。
4. [LABOS_RUN_MODEL_MAPPING_S01_20261107.md](LABOS_RUN_MODEL_MAPPING_S01_20261107.md)：Workflow Run 与 Experiment Run 双模型映射和源码调查边界。
5. [LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md](LABOS_S04A_E2E_ACCEPTANCE_SPEC_20261107.md)：PR-S04-A 的测试范围与验收口径。
6. [LABOS_SCENARIO_COVERAGE_GAP_20261107.md](LABOS_SCENARIO_COVERAGE_GAP_20261107.md)：场景讨论底稿，**不是规格**，不能将其内容当成已裁决实现要求。

重要：其它归档审计/设计文档保留历史证据与讨论过程，正文可能包含已被后续 ADR 反转的旧结论。出现冲突时，以 ADR_INDEX 和 REMEDIATION_STATUS 为准。

## 2. 已接受的状态管理核心语义

### 2.1 不把所有事实塞进一个 status

状态管理应分离四类正交事实：

1. **Run Control State**：执行控制意图/阶段，例如 RUNNING、PAUSE_REQUESTED、PAUSING、PAUSED、RESUMING、STOPPING、STOPPED、ABORTING、ABORTED、COMPLETED、FAILED、CANCELED。
2. **Hold Ledger**：多个可并存的阻断原因；按 hold_id 精确创建、去重、清除。不得用 scope-wide clear 清掉别的原因。
3. **Command Execution State**：命令派发、ACK、运行、取消请求、取消中、终态、UNKNOWN、RECONCILING、MANUAL_INTERVENTION 等。
4. **Alarm / Intervention State**：报警确认/解决和人工处置生命周期，不能与 Hold 或 Run status 混为一谈。

`WAITING_INTERVENTION`、`RECONCILIATION_REQUIRED` 更适合作为执行门禁/聚合关注状态，不应伪装成设备已经 PAUSED。

### 2.2 核心语义

- Pause API 成功只表示请求被可靠接受，不代表设备已经停止。
- PAUSING 表示正在收敛；设备可以按能力执行到安全边界。
- PAUSED 只能由事实聚合得出，不可在请求入口提前写成最终事实。
- Future.cancel、线程中断、本地 pending map 删除，不等于物理设备停止。
- 命令状态 UNKNOWN、设备查询失败、Worker 所有权不明、资源状态未知，均不得按 CLEAR/空闲/已停止处理。
- Resume 前必须重新核验 Hold、设备、命令、节点、资源、Worker ownership/fencing token 和执行快照；不能简单把 status 改回 running。
- 用户 Hold、设备故障 Hold、安全 Hold 等多个原因可并存，只能按 hold_id 精确清除。
- 报警消失不自动清除人工 Hold；需要相应的自检、联锁复位、物理确认和人工处置证据。

### 2.3 M-01 兼容性

现有 M-01 三层最终暂停回归仍需保留：
- `experiment_run` 最终 paused；
- `experiment` 最终 paused；
- 底层 Workflow `run` 在安全节点边界 paused；
- 后续节点保持 pending；
- Resume 不得重放旧 paused run 的物理副作用。

但三层 status 一致**不是**暂停完成的充分证据。还必须检查 Hold、设备动作、命令、节点边界、Worker 和资源事实。

## 3. 两套 Run 模型：接手者必须理解的现状

当前不是一个 Run 的两种 UI 视图：

- `experiment`：实验定义/列表聚合对象，不等于一次执行。
- `experiment_run`：一次 Experiment 执行记录；batch attempt 可能各有记录。
- `experiment_run_step`：Experiment 编排步骤，是 ExperimentRun 与 Workflow Run 之间的桥。
- `workflow`：流程定义。
- `run`：一次 Workflow 执行实例，节点在 `run_node`，设备命令关联到节点。

现有关键缺口（来自 S-01 对 main 指定基线的调查，实施前需按当前代码复核）：

1. `experiment_run_step.run_id` 在 Workflow 执行返回后才回写；Workflow 已创建并执行、父子关联尚未写入时崩溃，可能产生孤儿/难追溯 Run。
2. Workflow PauseManager 与 Experiment RunControl 的暂停类型词表、恢复路径和重启收尾语义不同；不能把两个 status 字段简单合并。
3. Experiment API 当前会立即返回 legacy `status=paused`；ExperimentRun/Experiment 的状态也可能先于设备动作收敛写成 paused。
4. Workflow Run 在节点边界检查 Hold，当前动作通常完成后才暂停。
5. Workflow Run Resume 与 Experiment Resume 的恢复方式不同；Experiment Resume 可能作废旧 paused Workflow Run，再由 ExperimentExecutor 创建新 Workflow Run。必须避免重复物理副作用。
6. 当前没有证据证明所有状态写入入口都经过一个统一协调器。

详细字段、源码位置和证据等级见 S-01 映射文档。该文档明确仍未完成：DDL 逐字段核验、全仓状态写入点穷举、全部 API handler 精确定位、父子关联崩溃注入。

## 4. S-04-A 当前进度和验收边界

### 4.1 PR-S04-A 的目标

PR-S04-A 只升级 4.17 的事实证据与兼容回归，不切换 API/UI 的 PAUSED 语义，也不改生产状态机。

脚本：`scripts/e2e-pause-during-experiment.py`

已增加/要求的事实观测包括：

- 本次专用 Experiment / ExperimentRun / Workflow Run 的准确关联；
- Pause API 的 legacy HTTP 200 / `status=paused` 兼容断言；
- 本次请求创建的 Experiment Hold（scope、scope_id、kind、reason、held_at、released_at）；
- API 返回时设备仍归属本次 Workflow Run，且 `status=running`、`current_method` 非空；
- 当前设备动作后续收敛；
- Workflow Run 在节点边界进入 paused；
- 后续设备节点 pending，且没有对应设备命令；
- 当前动作完成的命令/节点证据；
- Pause 请求、设备动作收敛、Workflow 暂停的时间线顺序；
- 清理前确认本次 Run 已终态且设备租约已释放；不安全时保留现场和 Hold。

### 4.2 关键时序断言

测试必须真正命中“设备执行中发出 Pause”：

```text
device_state.status == running
current_method != empty
current_run_id == this_workflow_run_id
        |
        v
POST Pause API
        |
        v
API returns; device must STILL be running for this run
        |
        v
device action settles
        |
        v
Workflow Run pauses at a safe node boundary
```

时间线断言：

```text
t_pause_request < t_device_action_settled <= t_workflow_paused
```

如果 API 返回时设备已自然完成，该次测试没有命中目标场景，应失败并调整模拟器方法时长后重跑。不能把未命中场景解释为通过。

`run_command` 是步骤结束后才 INSERT 的完成证据，不能拿它证明设备命令仍在执行；在途证据应从 `device_state` 等实时设备事实读取。

### 4.3 PR-S04-A 不做什么

- 不改变 API/UI 仍立即返回 legacy paused 的行为；
- 不实现 PR-S04-B 的“未收敛不得展示 PAUSED”；
- 不宣称设备物理 Abort；
- 不验证 UNKNOWN/ACK 丢失后一定可对账；
- 不实现统一状态聚合器；
- 不降低 CI 门禁阈值，不允许 SKIP。

### 4.4 尚未完成的运行验证

当前没有可核验的证据证明以下命令已经在目标环境执行成功：

```bash
python3 -m py_compile scripts/e2e-pause-during-experiment.py

python3 scripts/e2e-pause-during-experiment.py \
  --api http://127.0.0.1:18080

./scripts/ci-gate.sh --all
```

请在具备 LabOS API、PostgreSQL、模拟设备/模拟器的环境执行。必须保存完整日志、`S04A_TIMELINE_JSON`、断言计数、CI run id 和失败现场。不得只回报“跑过了”。

另外，检查脚本头部注释与当前断言是否一致；目前文件头部仍包含历史 M-01 叙述，避免旧注释让接手者误以为三层 status 本身就证明物理暂停完成。

## 5. 当前分支与 Git 工作纪律

目标分支：`review/labos-architecture-20261107`

最近一次 GitHub compare 读取结果：
- base：`main` @ `3debf7858cc3479a1cfae941837081015a41c137`
- 分支相对 main：ahead 30、behind 0
- 文件变化不仅是文档，也包含 `scripts/e2e-pause-during-experiment.py` 和 `scripts/ci-gate.sh` 的修改。
- 当前未发现该分支对应的 PR。
- 最近查询到的提交 combined status 为 `statuses: []`，这表示没有可见 status 记录，不代表 CI 通过。

注意：ahead 30 是最近一次 compare 时读取的快照；后续若有新提交，以 GitHub 实时结果为准。不要根据本文数字推断当前 HEAD SHA。

接手者开始前执行：

```bash
git fetch origin
git status --short --branch
git switch review/labos-architecture-20261107
git pull --ff-only
git log --oneline --decorate -12
git diff --check origin/main...HEAD
```

不要：
- 使用 `git add -A`；
- 覆盖用户未提交的工作；
- 擅自改写 main 或强推；
- 修改 CI 阈值、跳过门禁或用 SKIP 规避测试；
- 将文档分支当成已合并 main；
- 在没有真实测试证据时更新状态表为 DONE/VERIFIED。

每个 PR 必须遵守 ADR_INDEX / REMEDIATION_STATUS 的模板：ADR 编号、工作项 ID、状态表更新、CI/e2e/HIL 证据、回滚方式、门禁合规。

## 6. 推荐实施顺序

以下顺序用于指导接手者；若与最新 REMEDIATION_STATUS 的依赖关系冲突，先修正状态表，不要自行跳过依赖。

### Phase 0：基线与现状复核

1. 拉取指定分支并检查工作树干净程度。
2. 阅读本交接文件 §1 的全部文档。
3. 对比当前 main 与评审分支，确认 M-01 的符号、测试阈值和旧行为没有被误删。
4. 复核 S-01 源码调查，特别是所有 Pause/Resume/Stop/Abort 写入点和父子 Run 关联时序。
5. 核对测试脚本、测试总表、ci-gate 的口径一致。

### Phase 1：完成 PR-S04-A（先验收，后改实现）

1. 修正脚本中的过时注释和实际断言不一致。
2. Python 编译检查。
3. 在隔离环境执行 4.17，确保 API 返回瞬间设备仍执行本次 Run 的方法。
4. 检查 Hold 精确归属、设备收敛、节点暂停、后续节点无命令、时间线和安全清理。
5. 执行 `ci-gate.sh --all`，确认门禁下限未降低、无 SKIP、M-01 其余矩阵无回归。
6. 记录可复现证据；只有证据充分才更新状态表。
7. PR-S04-A 合并后，再开始 PR-S04-B。

### Phase 2：S-02 / 适配边界与父子 Run 关联

在改状态 API 前先解决双 Run 的持久化协调：
- 设计 ExperimentRunStep 与 Workflow Run 的预关联/创建事务；
- 处理创建成功但执行失败、执行启动后进程崩溃、重复投递、重试和孤儿 Run；
- 定义关联唯一性、幂等键、状态迁移和恢复扫描；
- 避免把物理动作重放当成普通任务重试；
- 明确由谁拥有 RunControl 写权限、谁拥有 Workflow Run 状态写权限。

不能只在执行结束后补写 `run_id`。

### Phase 3：PR-S04-B 状态语义迁移

在 S-04-A 已通过且 S-02 设计可实施后：
- Pause API 改为返回“请求已接受/正在暂停”，而非谎报最终 paused；
- UI 区分 PAUSE_REQUESTED、PAUSING、PAUSED、WAITING_INTERVENTION、RECONCILIATION_REQUIRED；
- 只有事实聚合满足暂停完成条件才显示 PAUSED；
- 对旧客户端提供有期限的兼容策略，不允许旧 status 继续误导安全判断；
- 增加 API contract、UI、并发、崩溃和恢复测试。

### Phase 4：UNKNOWN fail-closed 与命令对账

- 所有门禁查询使用明确三态/多态结果，不用 null、空列表或异常 catch 代表 CLEAR。
- 查询失败、超时、事件丢失、ACK 丢失、设备不可查询时进入 UNKNOWN / RECONCILING / MANUAL_INTERVENTION。
- UNKNOWN 阻断新物理命令和 Resume。
- 设备不支持可靠查询时，采用串行化、保守隔离和人工对账；不虚构自动恢复能力。
- 确认 Outbox、事务、事件幂等和命令执行账本的一致性。

### Phase 5：恢复、故障与场景规格

场景讨论底稿指出仍缺统一的逐步处理规格。后续应建立唯一《运行场景与处理规格》，每个场景至少包含：
1. 场景 ID / 触发条件；
2. 初始事实（Hold、节点、命令、资源、Run 关联）；
3. CLEAR / HELD / UNKNOWN 门禁判定；
4. 系统动作、事务和事件写入；
5. 超时、断连、崩溃、乱序、重复分支；
6. 恢复条件及人工/设备证据；
7. 对应单测、集成测试、e2e、HIL。

优先覆盖暂停与 Handoff 相遇、设备故障传播、资源锁租约、ACK 丢失、进程崩溃、Resume 防重放等。

## 7. 不可越过的安全边界

1. 不把软件线程/Future 取消说成物理设备停止。
2. 不在命令 UNKNOWN 时释放设备/板位/机械臂资源。
3. 不在 Hold 查询失败时继续派发。
4. 不因报警 inactive 自动清除人工 Hold。
5. 不在未确认旧 Worker 已失去执行权时启动新 Worker。
6. 不在未验证 checkpoint/幂等性时重放物理副作用。
7. 不因 Experiment 的一个 Workflow 完成，就推断 ExperimentRun 全部完成。
8. 不因三个 status 字段相同，就推断设备已经安全暂停。
9. 不把人工 Handoff 配置记录当作本次样本/板实际到位确认。
10. 不把模拟器通过等同于真机 HIL 通过。

## 8. 交接时当前应标注的状态

| 项目 | 当前可确认状态 | 不可声称 |
|---|---|---|
| ADR / 工程状态真源 | 已建立 | 所有 ADR 均已实现 |
| S-01 双 Run 模型 | 有源码映射文档，部分调查未闭合 | 全仓状态写入点已穷举 |
| 状态机验收契约 | 已形成文档 | 状态机功能已实现 |
| S-04-A 测试脚本 | 已提交评审分支，需在环境运行 | 4.17 已通过 |
| S-04-A CI | 尚无本轮可见通过证据 | CI 通过 |
| S-04-B API/UI | 未实施 | 未收敛时已禁止显示 PAUSED |
| UNKNOWN fail-closed | 设计已明确，实施未证明 | 所有门禁都 fail-closed |
| 命令对账 / Outbox | 有缺口调查，需继续实施与验证 | 命令状态可完全恢复 |
| 真机 HIL | 未提供本轮证据 | 真机安全已验证 |

---

## 9. 接手者首轮任务（明确可执行）

请不要同时开多个方向。首轮只做：

1. 确认分支 HEAD、工作树状态、与 main 的差异；
2. 阅读 ADR_INDEX、REMEDIATION_STATUS、状态机契约、S-01、S-04-A；
3. 审查 `scripts/e2e-pause-during-experiment.py` 的当前完整内容，修正旧注释/断言不一致；
4. 在有运行环境时执行 py_compile、4.17 和完整 CI，收集日志；
5. 根据真实结果更新 REMEDIATION_STATUS；未运行则保持 NOT_STARTED/IN_PR，不得标 VERIFIED；
6. 将审查结果和下一项实施建议写入 PR 描述或单独报告。

**完成首轮后再决定是否进入 S-02 或 PR-S04-B。不要跳过 S-04-A 的实际验证。**
