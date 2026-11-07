# LabOS 下一阶段整改实施计划（基于 2026-11-07 独立审计）

> 本文是分阶段整改计划。2026-11-07 已在评审分支实施 Batch 0 的部分保守修复；未修改 `main`。对应详细证据见 `docs/评审记录/20261107-01-gpt6.md`。

## 总原则

- 先封住会导致重复物理动作、错误停止或错误释放资源的 P0。
- 不大规模重写现有执行链；保留 `WorkflowService → GraphRunner → RunStateMachine → DriverStepExecutor`，按边界渐进修复。
- 一次一个小 PR；每个 PR 包含源码、回归测试、运行证据和中英文文档同步。
- 没有真机证据的能力不得标为生产可用。

## Batch 0：执行路径安全边界（P0）

### 0.1 GraphRunner 执行后禁止 fallback 重跑 <已实现，待后续增强>

- 将 BPMN parse/static validation 移到独立 try。
- 构造 GraphRunner 完成后，执行异常只能走异常收敛，不得调用 `executeAll`。
- 添加 run execution phase 标记，至少区分 PREPARED / DISPATCH_STARTED / SETTLED。
- 验收：在设备命令派发后注入 RuntimeException，确保没有顺序路径执行、没有新 commandId 重做前序动作。

### 0.2 abort 语义分离 <保守缓解已实现，物理中止待实现>

- 将 DeviceDriver.abort 返回值从 void 改为可审计 AbortResult（需要兼容适配器）。
- 结果至少有 UNSUPPORTED / REQUESTED / DEVICE_ACKNOWLEDGED / PHYSICALLY_STOPPED / UNKNOWN。
- TCP 协议无 Abort 消息时必须返回 UNSUPPORTED，不得以本地 future 完成冒充设备停止。
- 设备驱动无法确认物理停止时，中断节点不得作为可自动重跑的“安全中断”。
- 全部 `interruptible=true` 配置先保持 false，除非逐型号/固件/方法/负载 HIL 验证通过。
- 待验收：设备继续运行但响应丢失时，run/command 必须进入 UNKNOWN/RECONCILING，恢复不自动重跑；当前本次提交没有实现完整 UNKNOWN/RECONCILING 命令账本。

## Batch 1：配置与命令账本（P1）

### 1.1 device_method 查询修正

- 查询路径必须通过 `device_instance.device_id = device_method.device_id` JOIN。
- 方法精确值、设备方法库范围、interruptible 读取都复用同一 DAO。
- 删除/迁移测试数据必须确保型号 ID 与实例 ID 不相等。
- 验收：不同 ID 空间下 timeout 与 interruptible 查询正确，tenant 之间不串读。

### 1.2 Command Ledger + Outbox

建议状态：

`CREATED → DISPATCHED → ACKNOWLEDGED → RUNNING → SUCCEEDED/FAILED`

补充不确定状态：

`UNKNOWN → RECONCILING → (SUCCEEDED | FAILED | MANUAL_INTERVENTION)`

- 设备派发前先在 PostgreSQL 写入 command 与 Outbox。
- 网络 attempt 独立记录，不以重试 attempt 覆盖原始 command 事实。
- 超时/断线后不自动生成新的物理命令；先 query/reattach/reconcile。
- Redis 队列作为传输层，不作为唯一命令账本。
- 验收：在“提交 Outbox 后未发出”“已发送但响应丢失”“设备完成但 API 崩溃”“Gateway 重启”四个时间点杀进程，恢复后不重复执行物理动作。

## Batch 2：资源与物理位置（P1）

- 启动恢复不无条件清理设备锁；先将相关资源隔离为 UNKNOWN/QUARANTINED。
- query/reattach 设备当前命令；无法查询的驱动进入人工 reconciliation。
- 逻辑预约、物理观测、安全状态三维分离。
- 板位更新必须有独立扫码/传感器/人工证据；设备成功回包只能作为运动报告，不能当作目标位置确认。
- TTL 只释放软件 lease，不释放物理安全隔离。
- 验收：搬运中断电、响应丢失、人工移板、扫码不一致、传感器失效均不能使系统误判空位。

## Batch 3：流程语义与并发（P1/P2）

- 未支持的 BPMN 节点类型编译期 fail-fast；subProcess 明确支持或拒绝。
- pause request 绑定具体 run/experiment_run，不从 workflowId 反查最近一条实验。
- RecipeCompiler 批量编译必须传递 capabilityByResource。
- 超时拆分设备方法超时、设备响应超时和 workflow deadline；不以设备所有方法 MAX 作为任意未知方法的唯一回退。
- 验收：同一 workflow 被两个实验并发执行时，只暂停 A 不影响 B；不支持节点在命令派发前报清晰编译错误。

## 合并与审查策略

1. 每个 Batch 拆成独立 PR，先合并 P0。
2. 每个 PR 至少包含：问题复现测试、修复测试、失败路径测试、日志/审计字段、文档。
3. 其他 AI 只做独立反向审查，重点寻找反例；不与主实现者并行修改同一核心文件。
4. 真机安全相关 PR 必须有设备型号、固件、方法、负载和停止后状态的 HIL 证据。
5. 任何门禁阈值下降、SKIP、仅跑模拟器却宣称真机通过，均不接受。


## Batch 0 当前状态（2026-11-07）

- [x] GraphRunner 运行期异常不会再触发顺序 fallback 重跑。
- [x] TCP 当前无物理 Abort 能力时，不会通过本地 Future 取消冒充设备已停止。
- [x] 同一 JVM 内同一 run 的后台 worker 不允许并发；旧 worker 未退出时拒绝 resume。
- [ ] 结构化 AbortResult 和设备侧停止确认。
- [ ] 将 pause_requested / pausing / paused 分开建模；当前设备动作收尾期间不能把 run.status='paused' 解读为设备已停止。
- [ ] 数据库持久化 worker lease / fencing token（覆盖多 Kernel 实例）。
- [ ] 命令级 UNKNOWN/RECONCILING 与 crash recovery。
- [ ] 真机 HIL：暂停时设备仍运行、响应丢失、设备拒绝停止、停止确认丢失等故障。


## Batch 0.4：暂停状态与人工干预闭环（设计已补充，代码待实施）

完整领域设计见 `docs/architecture/LABOS_PAUSE_AND_INTERVENTION_STATE_MODEL_20261107.md`。

核心决策：
- ExperimentRun 新增/明确 PAUSE_REQUESTED、PAUSING、PAUSED、WAITING_INTERVENTION、RESUMING、RECONCILIATION_REQUIRED。
- NodeExecution 区分 PAUSE_REQUESTED、FINISHING_CURRENT_COMMAND、STOP_REQUESTED、WAITING_DEVICE_CONFIRMATION、PAUSED、BLOCKED_BY_FAULT、WAITING_OPERATOR、UNKNOWN/RECONCILING、READY_TO_RESUME。
- 用户暂停与设备报警/安全联锁统一进入 Hold 机制，按 source、reason_code、scope、severity、resume_policy 区分。
- 实验级 Hold 意图与受影响节点的 PAUSE_REQUESTED 在同一 PostgreSQL 事务中提交，并同事务写事件和 Outbox；随后异步收敛节点事实，最后由聚合器更新实验状态。
- 设备报警需要人工处理时，安全收敛后进入 WAITING_INTERVENTION；人工处理、设备自检和联锁复位通过后才进入 RESUMING。
- UNKNOWN 命令/物理状态不能被伪装成 PAUSED；不得因 TTL 过期解除物理隔离。
- 默认不能证明并行分支独立性时，扩大暂停范围。

实施顺序：
1. 先落状态转换表、聚合规则和迁移兼容方案，不立即改数据库 schema。
2. 修复暂停入口的精确 run 关联，避免按 workflowId 反查最近实验。
3. 原子登记 Hold + 实验暂停意图 + 活动节点暂停请求 + Event/Outbox。
4. 修复 UI 语义，明确展示“暂停处理中”与“已安全暂停”。
5. 增加设备报警 Hold、人工 InterventionRecord、复位证据与恢复门禁。
6. 最后做事件乱序、重复、worker crash、多实例和真机 HIL。


### Batch 0.4 实施前的现有代码核对（重要修订）

暂停设计文档已补充当前代码映射：`docs/architecture/LABOS_PAUSE_AND_INTERVENTION_STATE_MODEL_20261107.md` §12。

仓库已经存在 PauseManager（旧 run 模型）、RuntimeHold（site/island/experiment）、RunControl（experiment_run）和 ManualWindow。**下一步不是新建第三套 Hold，而是收敛已有实现。**

实施顺序调整为：

1. **P0-0：安全闸门 fail-closed。** RuntimeHold.active() 当前 SQL 失败会返回空列表，可能把“读不到 Hold”误判成“没有 Hold”。改为明确错误/UNKNOWN，派发和恢复必须拒绝。
2. **P0-1：暂停事务原子化。** RunControl.pause() 当前 raise Hold、更新 experiment_run、更新 experiment 使用独立连接；改为单一事务写 Hold + 实验级 PAUSE_REQUESTED + 受影响 run/node 的 PAUSE_REQUESTED + Event/Outbox。
3. **P0-2：异步收敛状态。** 删除“暂停请求 API 立即把实验标为 paused”的语义；引入 PAUSING，节点在设备当前命令结束或设备侧停止确认后才标 PAUSED。
4. **P0-3：Hold 原因与解除粒度。** 当前唯一索引限制同一 scope 只有一条活动 Hold，clear 按 scope 清全部。改为事件幂等键去重、hold_id 精确解除，允许用户暂停和设备故障 Hold 并存。
5. **P0-4：人工干预恢复门禁。** 设备故障按策略区分可自动恢复和必须人工恢复；后者要求 InterventionRecord、设备自检、联锁复位和物理状态核验后才能 RESUMING。
6. **P0-5：旧新模型兼容迁移。** 先梳理 PauseManager 的旧 run 与 RuntimeHold/RunControl 的 experiment_run 调用入口，建立统一 PauseCoordinator/适配器，再逐步迁移，不能直接删旧类。

注意：WAITING_INTERVENTION 和 RECONCILIATION_REQUIRED 长于当前 schema 注释中 experiment_run.status 的 varchar(16) 设计；实施前核实实际库列类型，迁移扩容或使用短状态 code。当前不应直接写入这些长字符串。

