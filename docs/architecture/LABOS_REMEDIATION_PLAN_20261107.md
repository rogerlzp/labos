# LabOS 下一阶段整改实施计划（基于 2026-11-07 独立审计）

> **执行基线说明（2026-11-07）**
> 本文档由评审分支 `review/labos-industrial-architecture` 归档到 `main` 执行基线，已按用户裁决处理：
> ① **流程（Workflow）不允许跨 Workcell；实验（Experiment）允许跨 Workcell**；
> ② `main` 的暂停修复 `3debf78` 为代码基线；③ 所有工程实施在 `main` 上进行，评审分支仅作设计归档；
> ④ 按 `ADR-DOC-001` 去除商业公司/产品名称，外部实践改以「产品形态 A/B/C」表述。
>
> 本文**保留原始思考与源码证据**，但结论以 `ADR_INDEX.md` 与 `REMEDIATION_STATUS.md` 为唯一准绳；
> 被新裁决取代的条目见文末「裁决影响（2026-11-07）」，不得按被取代条目实施。

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



## Batch 0.4：暂停/人工干预方案的外部实践校准（2026-11-07）

新增研究与 ADR：
- `docs/architecture/LABOS_PAUSE_EXTERNAL_PRACTICES_AND_ADR_20261107.md`
- `docs/architecture/LABOS_PAUSE_AND_INTERVENTION_STATE_MODEL_20261107.md` §13

借鉴结论：
- ISA-88/PackML：命令、过渡态、稳定态分离；Hold/Pause/Stop/Abort 语义区分。
- ROS 2 Actions：CANCELING 与 CANCELED 分离，取消请求可被拒绝。
- SiLA 2：稳定 execution UUID + 状态查询/订阅；但不假设其核心协议普遍支持 Pause/Cancel。
- OPC UA A&C：Active、Acknowledge、Confirm、Resolved 分离；报警已知悉不等于故障已修复。
- Temporal/Airflow：暂停编排层阻止新工作，不自动中止在途任务；必须另建 LabOS quiescence barrier。
- 工业安全：软件 Pause/Stop/Abort 不替代硬件安全回路和 Emergency Stop。

**重要模型修订：** WAITING_INTERVENTION / RECONCILIATION_REQUIRED 建议作为 ExecutionGate / AttentionState 聚合投影，而不是全部塞进 ExperimentRun.status。底层分为 RunControlState、HoldLedger、CommandExecutionState、Alarm/InterventionState 四类正交事实。

## 下一步代码调查工作包（先调查，暂不直接改状态机）

### A. 先完成现有调用链与副作用清单
1. 旧 run 路径：RunApi → PauseManager.pause/resume → WorkflowService/WorkflowExecutor → run / run_node / resource_booking / position_state / device_state。
2. 新 experiment_run 路径：ExperimentApi → RunControl.pause/resume/stop → RuntimeHold → ExperimentScheduler / ExperimentExecutor → experiment_run / experiment_run_step / experiment。
3. 设备/报警路径：Gateway/Driver → alarm/event → RuntimeHold / ManualWindow / PauseManager。
4. 前端：暂停队列、实验详情、运行详情、Hold 列表、人工窗口、恢复按钮对状态值的假设。
5. 逐一确认旧 run 与 experiment_run 的关联基数、创建/关闭时机和重启恢复行为。

### B. 本轮新增待核查点
- PauseManager.releaseResources() 的 device_port_usage 更新 SQL 目前有两个 tenant_id 参数位，但通用 exec(sql, runId) 将第二个参数绑定为 runId；该 SQL 的第二个参数实际是 device_port.tenant_id。需确认这是否导致通道释放失效，或在 runId 恰好等于 tenantId 时错误释放该租户全部通道。先加测试复现，再决定修复。
- PauseManager.releaseResources() 各资源释放操作使用独立连接；部分失败被吞掉并返回 0。需要明确哪些释放必须原子、哪些失败必须阻止恢复/保持隔离。
- RuntimeHold.active() SQL 失败返回空列表；coveringForWorkflow() 反查 SQL 失败也返回 null。两处都可能 fail-open，不能只修 active()。
- RunControl.pause() 和 resume() 的 Hold / run / experiment / workflow run 多步更新需纳入同一事务边界或显式 Saga + Outbox；不能仅修 pause 的一半。
- 现有 DB self-test 覆盖 PauseManager、RunControl、RuntimeHold，但需检查其是否覆盖并发 Hold、SQL 失败、设备在途暂停、重复/乱序事件和跨表事务中断。

### C. 本阶段交付物
1. Pause/Resume/Stop/Abort 入口矩阵（API、调用方、权限、DB 写入、消息、设备动作）。
2. 旧 run / experiment_run / workflow_id / experiment_run_step 关联图。
3. 资源释放副作用矩阵（板位、预约、端口、设备锁、机械臂抓板状态）。
4. DeviceCapability 当前实现盘点（TCP、模拟驱动、潜在 SiLA/OPC UA）。
5. 当前测试覆盖表及缺口。
6. PauseCoordinator V1 最小事务设计和迁移计划。

### D. 预计实施顺序
P0-1：修复所有安全闸门查询 fail-closed（包含 workflow scope 反查）。
P0-2：定义统一事务 API/服务，原子写入 Hold、RunControl intent、Audit/Event、Outbox。
P0-3：API 返回 request accepted / PAUSE_REQUESTED；执行器在边界收敛；聚合器决定 PAUSED。
P1-1：多 Hold 并存、按 hold_id 清除。
P1-2：报警/人工处置/设备复位证据与恢复门禁。
P1-3：旧新执行路径适配与前端迁移。
P2：设备协议适配、跨实验安全域、HIL 和长稳测试。

未经 A-D 的调查和测试复现，不直接重写 PauseManager 或 RunControl，不先扩充 UI 状态枚举。


### Batch 0.4 进一步源码核对：驱动取消和执行器 fail-open

独立评审记录新增 F-23 至 F-25：
- 主分支 DeviceDriver.abort() 是 void 尽力而为；TCP 实现目前只移除本地 pending Future 并返回本地失败，没有证据表明向设备发送真实停止协议帧。
- DriverStepExecutor 的 q1s/q1 查询异常返回 null，RuntimeHold.coveringForWorkflow() 反查 SQL 异常也返回 null；暂停判定可能在数据库故障时 fail-open。
- 当前源码树未找到统一 Outbox 实现；JdbcEventStore 独立连接且 autoCommit=true，需核实事件与业务事务一致性。

因此 PauseCoordinator V1 的真正第一批调整为：
1. **三态安全判定：** ACTIVE / INACTIVE / UNKNOWN；任何 DB/设备查询错误进入 UNKNOWN 并阻断派发。
2. **设备 Abort 语义纠正：** 本地 Future 取消不等于设备停止；TCP 驱动需明确不支持/未知真实 cancel 能力，不能将本地取消结果记为物理停止。
3. **RuntimeHold fail-closed：** active、coveringForWorkflow、anyActive 等所有安全闸门查询一致处理错误。
4. **事务一致性：** 再设计 Hold + Run Control + Event + Outbox 原子提交。
5. **资源释放 SQL 复现：** PauseManager 端口释放 SQL 的第二个参数疑似将 runId 绑定到 tenant_id 位置，先加回归测试。
6. **现有 API / UI 迁移：** 上述内核行为确认后再变更 UI 状态和值域。

不要先增加更多状态枚举。第一批目标是确保数据库或设备状态未知时，系统不会继续派发或谎报设备停止。


## Batch 0.5：Workcell 故障隔离与影响传播（设计补充，代码未实施）

用户已确认的业务原则：**设备故障默认暂停/隔离整个 Workcell / Functional Island；其他独立节点是否继续，由故障等级、安全域和依赖策略决定。**

设计依据与详细规则：
- `docs/architecture/LABOS_SOURCE_AUDIT_A-F_20261107.md`：A–F 阶段性源码审计，含调用链、robot_point 多机械臂字段、island 绑定缺口、资源互斥、Outbox 与测试缺口。
- `docs/architecture/LABOS_PAUSE_EXTERNAL_PRACTICES_AND_ADR_20261107.md` §13–14
- `docs/architecture/LABOS_PAUSE_AND_INTERVENTION_STATE_MODEL_20261107.md` §14

实施前置调查（用户已明确 island 业务定义后修订）：
1. 将当前 `island` 视为物理 Workcell：一个房间/相对封闭区域内的多台实验设备，由一台或多台机械臂连接并转运实验板；当前不另建重复的 Functional Island 实体。
2. 当前仅考虑机械臂，不考虑 AGV；未来按需扩展物流执行器，不提前实现 AGV 调度。
3. 核实 `resource_node` 资源树能否准确表达 island→device→position，以及 robot_point、机械臂、设备和板位之间的实际归属/可达关系。
4. 审计实验执行是否把 `experiment.island_id` 绑定到每个 workflow 的设备候选池；当前初查发现 ExperimentExecutor.runStep 未调用 WorkflowService.withIsland，且 withIsland 的空候选回退会退回全局候选池，需先复现。
5. 核实一台/多台机械臂的并发互斥边界：机械臂本体、共享工作空间/交叉区、设备端口、板位和转运路径；不得默认同岛只能有一台机械臂，也不得仅因多臂就放开并发。
6. 确认调度锁是否跨 Kernel 实例持久化；当前 ExperimentScheduler 使用 JVM 内存锁和 2 小时 TTL，不能证明跨实例互斥或物理资源安全。
7. 建立 island 内外共享资源、样本/板位和公共设施依赖图；确认故障影响闭包和跨实验传播。
8. 盘点设备报警等级、设备能力、工艺安全边界和现场 E-stop/PLC 信号来源；不得由软件团队猜测安全等级。
9. 定义 Continue Predicate 和影响传播快照，缺少依赖/状态信息时 fail-closed。

验收新增 FS-01 至 FS-12，至少覆盖跨实验共享 Workcell、跨岛共享资源、公共设施安全域、样本位置未知、传播计算缺失、报警恢复但人工 Hold 未清、其他实验仍有 User Hold、策略版本更新和 E-stop 覆盖多 Workcell。

注意：本 Batch 目前是领域设计和调查要求，不授权直接改设备控制代码。完成 A–F 源码调查、现场拓扑核实和独立 AI 反向审查后，再拆分实施 PR；真机安全行为必须做 HIL/现场验收。


## Batch 0.6：Island 编辑期边界与逐 Island Hold 调度（2026-11-07，源码审计完成，代码未实施）

专项证据报告：`docs/architecture/LABOS_ISLAND_BOUNDARY_AND_HOLD_DISPATCH_AUDIT_20261107.md`。

**产品语义修订：Island 边界首先由流程编辑和发布保证，运行时负责不可绕过的二次校验。不能把它仅仅当成 WorkflowService 的设备候选池过滤。**

### P0-6：Workflow 固定 Island，编辑器禁止跨岛拼接

- 当前 Designer 的 islandId 是可清空的 URL/UI 状态；null 会请求全量设备池。
- 当前 onDropMethod 未验证当前 Workflow Island、前一节点 Island 和新节点 Island；LastDropped 未保存 islandId。
- createWorkflow 写入 workflow.attr.island_id，但现有证据显示它主要用于设备面板默认过滤，不是不可变归属。
- saveGraph / updateNodeBinding 当前可见实现没有校验 resource_id 所属 Island。
- 先将 Workflow Island 归属作为权威字段/强约束；旧流程 null 必须显式分配，不得等同“全场设备池”。
- 前端 drop、NodeSettings、切换 Island、页面保存均校验；服务端 saveGraph、updateNodeBinding、restore、choreography build、experiment process/graph 保存使用同一 IslandBoundaryValidator。
- 无机械臂或机械臂不可达关系 UNKNOWN 时，不能把两台设备直接连成一条板流；当前 Designer 找不到 arm 时会退化为直接 connect，必须改为阻断。

### P0-7：Experiment / Workflow Island 一致性

- ExperimentCanvas 组合的是 Workflow 引用；Experiment 的 island_id 必须与其引用的每个物理 Workflow 的 island_id 一致。
- 需要继续核查 replaceExperimentProcesses、experiment graph save 的所有入口；未完成前不声称该约束不存在。
- ExperimentExecutor 运行时仍需从 experiment 取得 islandId，并对每个 Workflow/设备节点做强校验。

### P0-8：RuntimeHold 按候选逐 Island 判定，三态 fail-closed

- Queued 已携带 experimentId/islandId，可按候选评估，无需全队列 anyActive。
- 当前 dispatch 全局 anyActive 导致任意 Island Hold 阻止所有 Island；RuntimeHold.active SQL 异常返回空列表，dispatch catch 后继续，构成 fail-open。
- CLEAR 才允许派发；HELD 只跳过被覆盖的 Island/Experiment；UNKNOWN 阻断该候选并明确告警。
- 普通设备/板位/机械臂故障默认 ISLAND Hold；SITE Hold 只用于明确的全场公共设施/安全事件；跨 Island 影响通过显式 SafetyDomain/affected-resource 传播。
- autoContinueHeldBatches 也要按候选逐个评估，不能被其他 Island 的 Hold 全局挡住。

### 验收新增

- 同一 Workflow 先拖入 Island A 设备，再切换 Island B 拖入设备：drop/保存必须失败，图保持不变。
- 直接 API PUT 绑定其他 Island 的 resourceId、或 saveGraph 混入异岛设备：服务端拒绝并回滚。
- 旧 Workflow Island 未分配：不能以全量设备池发布/执行。
- A Island 有 ISLAND Hold、B Island 有独立队列：A 不派发，B 正常派发。
- Hold 查询 SQL 异常：相关候选 UNKNOWN，不派发；不能把查询失败当无 Hold。
- 只有 SITE Hold 或明确跨岛 SafetyDomain Hold 才能同时阻断 A/B。
- A 有 Hold、B 有无 Hold 的批次候选：B 可自动续批。


### Batch 0.6 补充审计结论（2026-11-07）

已进一步确认 Experiment → Workflow 组合也没有 Island 一致性校验：
- `validateExperimentWorkflowIds` 只检查 ID 合法、租户内存在，不比较 experiment.island_id 与 workflow.attr.island_id。
- `saveExperimentGraph` 复用该校验；`replaceExperimentProcesses` 写入 experiment_process 时不校验 Island。
- `ExperimentExecutor` 虽读取 experiment.island_id 用于 Hold，但调用 WorkflowService 时仍只传 workflowId 和 vars。

因此 P0-7 不只是“建议补查”：当前已检查的关联写入路径确认存在缺口。详见专项审计报告 §8。实施时必须同时验证 Workflow 内设备 Island 和 Experiment 所引用 Workflow Island，不能只修 Designer 的拖拽逻辑。

---

## 裁决影响（2026-11-07，以 main 执行基线为准）

### SUPERSEDED（作废）

- **P0-7「Experiment / Workflow Island 一致性」整项作废**（含「Experiment 的 island_id 必须与其引用的每个物理 Workflow 的 island_id 一致」）。裁决：Experiment 允许跨 Workcell。
- §「Batch 0.6 补充审计结论」中「因此 P0-7 不只是建议补查：当前已检查的关联写入路径确认存在缺口」一并作废——按新裁决，缺少「相等校验」不是缺陷。

### 保留

- Batch 0 / 0.4 / 0.5、P0-6（Workflow 固定单 Workcell + 编辑期阻断）、P0-8（Hold 三态逐 Workcell 调度）、全部事务/命令账本/资源/HIL 要求。
- Batch 0.5 第 1 条「不另建重复的 Functional Island 实体」保留，并由 `ADR-ISLAND-003` 强化。

### 新增工作项（见 `REMEDIATION_STATUS.md`）

- N-01 跨 Workcell 实验数据模型；N-02 执行上下文按 Workflow 取 Workcell；N-03 多 Workcell 加锁与 Hold 覆盖判定；N-04 跨 Workcell Handoff 定义。
