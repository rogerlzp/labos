# LabOS 源码调查 A–F（阶段性审计）

- 日期：2026-11-07
- 分支：`review/labos-industrial-architecture`
- 审计范围：当前分支源码静态检查；未连接现场设备、未查询用户生产数据库、未运行 HIL。
- 结论口径：CONFIRMED = 源码直接证明；PARTIAL = 有实现但边界不完整；NOT_FOUND = 仅在已检查范围未发现；UNKNOWN = 当前证据不足。
- 本报告只记录调查和整改建议，不授权修改设备控制代码。

## 0. 用户已确认的业务边界

当前 `island` 是物理协同工作单元：一个房间/相对封闭区域内放置多台实验设备，由一台或多台机械臂在设备间转运实验板。当前只考虑机械臂，不考虑 AGV；未来有需求再扩展。

一个 island 可以有多台机械臂，不代表可以无条件并行。并发必须由机械臂可达范围、共享工作空间/交叉区、设备端口、板位和路径冲突共同决定。设备故障默认隔离整个 island；其他 island 是否继续，按故障等级、安全域和依赖策略决定。

## 1. 总结

| 维度 | 状态 | 当前判断 |
|---|---|---|
| A. API / Worker / Scheduler 调用链 | PARTIAL | 已确认调度器到 ExperimentExecutor 的主链；流程执行没有把 experiment.island_id 绑定到 WorkflowService 的设备候选池 |
| B. run / experiment_run / workflow / island / robot 关系 | PARTIAL | island→device 资源树、robot_point 的 robot_resource_id/device_resource_id/island_id 已存在；执行路径没有一致使用这些关系 |
| C. 资源释放副作用 | PARTIAL | 板位双预约和板状态记录存在；机械臂工作空间锁、命令与物理位置确认的原子闭环未得到证明 |
| D. DeviceCapability / RobotCapability | PARTIAL | 驱动支持设备级 capability 与设备隔离；未见机械臂可达性、工作空间、安全区域能力模型 |
| E. 事务 / Outbox | PARTIAL | JdbcEventStore 使用独立连接 autoCommit=true；当前检查范围未找到业务状态与事件原子提交的统一 Outbox |
| F. 测试覆盖与缺口 | UNKNOWN / PARTIAL | README 声明若干 SelfTest 和 Maven JUnit；本轮未枚举测试目录、未执行测试、未运行 HIL |

**最高优先级结论：**
1. 当前 island 互斥不能证明机械臂/设备动作一定限制在该 island 内。
2. `WorkflowService.withIsland()` 的空候选回退会退回全局候选池；并且 ExperimentExecutor.runStep 当前没有调用 withIsland。
3. `ExperimentScheduler.dispatch()` 的现场扣留查询是全局 anyActive；查询异常被 catch 后继续派发，形成 fail-open。
4. `robot_point` 已经有按机械臂区分的记录字段，但点位坐标注入和更新接口没有始终使用机械臂 ID，存在多机械臂同码点位歧义。
5. `PositionLock.tryReserveTransfer()` 的双位置预约按 ID 顺序执行，但两次预约并非同一数据库事务；也未在本轮确认它与真实机械臂命令、传感器确认构成原子闭环。

---

## 2. A — API / Worker / Scheduler 调用链

### A-01：实验调度与执行主链 — CONFIRMED

已确认调用关系：

```
ExperimentScheduler.submit(experimentId)
  -> insert experiment_run / queue
  -> dispatch()
  -> executeOne(Queued)
  -> ExperimentExecutor.run(experimentId, vars, experimentRunId)
  -> expand(experimentId)
  -> runStep(runId, Step, vars)
  -> WorkflowService.execute(workflowId)
```

证据：
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java`
  - `submit()`：约 170–240 行
  - `dispatch()`：约 380–430 行
  - `executeOne()`：约 430–510 行
- `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java`
  - `run()`：约 100–190 行
  - `runStep()`：约 748–810 行

### A-02：Island 绑定没有贯穿执行路径 — CONFIRMED

`ExperimentExecutor.runStep()` 调用：

`workflows.withVars(runVars).execute(st.workflowId())`

没有调用 `withIsland(experiment.island_id)`。随后 `clearVars()` 清理 ThreadLocal。由此不能证明实验的 Workflow 节点只会选择所属 island 的设备。

`WorkflowService.withIsland()` 本身存在，但只是可选 ThreadLocal 过滤器。它不自动从 experiment / experiment_run 继承 island。

### A-03：Island 候选过滤存在全局回退 — CONFIRMED / P0

`WorkflowService.execute()` 生成 capability 候选池后执行 island 过滤。当前逻辑：

`filtered.put(e.getKey(), keep.isEmpty() ? e.getValue() : keep)`

即 island 内没有该 capability 设备时，保留未过滤的全局候选池。该行为与“实验只能在所属 island 内运行”相矛盾。

建议：先加回归测试，再把空候选解释为配置错误/等待资源，不允许回退全局设备池。对未指定 island 的实验也应有显式策略，不得隐式扩大范围。

### A-04：全局扣留闸门既过宽又 fail-open — CONFIRMED / P0

`ExperimentScheduler.dispatch()` 在派发前调用 `RuntimeHold.anyActive()`。任何 site / island / experiment 级 hold 都会停止所有新实验派发，而不是只阻止受影响 island 或依赖闭包中的工作。

更严重的是查询异常被捕获后忽略，继续派发：

`catch (Exception ignore) { /* 读不到扣留状态不阻断调度 */ }`

`RuntimeHold.active()` 查询异常时记录日志并返回空列表；`anyActive()` 以空列表推断“无扣留”。因此数据库读取失败会被解释成无 Hold。

整改方向：
- 将 Hold 查询改成 ACTIVE / INACTIVE / UNKNOWN 三态；
- UNKNOWN 时禁止新设备命令/新实验派发；
- 派发判断使用受影响 island / resource / SafetyDomain，而不是全局 anyActive；
- 允许其他 island 继续之前，必须通过依赖闭包与 SafetyDomain 检查。

---

## 3. B — run / experiment_run / workflow / island / robot 关系

### B-01：Island 到设备的资源树 — PARTIAL

`QueryService.topologyJson()` 以 `resource_node.kind='island'` 为根，查询直接子节点 `kind='device'`，再关联 `device_instance`、`device`、`capability`、`driver` 和 `device_state`。

`WorkflowService.loadIslandDeviceIds()` 同样只查询：

`resource_node.parent_id = islandId`

这证明当前核心归属关系主要是一层直接父子资源树。是否允许设备下再挂位置，由 PositionLock 的查询部分支持；但尚未看到一个统一、递归且带类型约束的资源拓扑解析器。

### B-02：RobotPoint 已有多机械臂字段 — CONFIRMED

`QueryService.robotPointsJson(islandId)` 查询字段：
- `island_id`
- `robot_resource_id`
- `code/name/x/y/z/action`
- `device_resource_id`

CSV 导入也接收 `robotResourceId`，并将点位写入指定机械臂；`syncRobotPoints(islandId, robotResourceId)` 会把该 island 的直接子设备批量生成默认点位。

因此，**不能说 LabOS 完全没有多机械臂点位模型**。它已经有按机械臂资源 ID 区分的点位数据结构。

### B-03：多机械臂点位关联未贯穿执行 — CONFIRMED / P1

存在三处不一致：

1. `ExperimentFlowRunner.enrichWithCoords()` 查询坐标只按 `tenant_id + code`，没有 `island_id` 或 `robot_resource_id` 条件。
2. `QueryService.updateRobotPoint()` 更新条件是 `tenant_id + island_id + code`，没有 `robot_resource_id`。若同一 island 多台机械臂存在相同 code，可能更新多条记录。
3. `syncRobotPoints(islandId, robotResourceId)` 会把 island 下所有直接子设备生成该机械臂的默认点位；源码没有根据机械臂实际可达范围筛选设备。

因此当前数据结构“能记录多台机械臂”，但尚不能证明执行时总能选中正确机械臂对应的点位，也不能证明每台机械臂只会访问其可达设备。

建议：
- 点位唯一标识和所有读取/更新 API 都带 `robot_resource_id`；
- 设备可达关系独立建模，默认点位同步不得隐含“所有机械臂都能到所有设备”；
- 点位坐标注入必须使用实际执行机械臂 ID，禁止按 code 全局查询；
- 对点位所属 island、robot、目标 device 做一致性校验。

### B-04：Island Flow 图与执行计划的关系 — PARTIAL

`island_flow` 以 JSON `graph` 保存 island 动线；`saveIslandFlow()` 整体替换该 JSON。当前读取/保存代码没有展示其节点/边与 `robot_point`、设备可达关系、执行命令账本之间的强约束校验。

因此它目前可以作为画布/动线配置证据，但不能仅凭图已保存就证明运行时调度器按图执行或做了碰撞检查。

### B-05：ExperimentFlowRunner 是另一条设备执行路径 — CONFIRMED

`ExperimentFlowRunner` 直接从 Step.resourceId 找 `device_instance.id`，然后用 `ShadowProbe.probe()` 下发设备方法。该路径与 `ExperimentExecutor -> WorkflowService` 并存。README 的已知限制也明确指出双执行路径尚存。

`ExperimentFlowRunner.precheckTransfer()` 只尝试查询源 code 对应的 `device_state.has_plate`；source 未提供时跳过预检，SQL 异常时返回 null 放行。它不等价于板位/物理位置确认。

---

## 4. C — 资源释放副作用矩阵

| 资源 | 当前实现 | 已知边界/风险 |
|---|---|---|
| Island | Scheduler JVM 内存锁，2h TTL | 不是数据库/跨实例锁；TTL 到期可能在原执行仍运行时释放 |
| 机械臂本体 | Robot 作为 resource_node / device_resource_id 使用 | 未看到统一机械臂运行租约、所有权与 fencing token |
| 机械臂工作空间 | 未在已检查执行路径中看到区域互斥 | 多机械臂共享/交叉空间无法由 island 锁或点位表证明 |
| 设备 | DriverRegistry 有设备级 capability 与 quarantine | quarantine 在 Registry 内存态；跨 Kernel 实例/重启一致性未证明 |
| 板位 | PositionLock 原子预约单个位置 | 位置锁不等于机械臂运动完成或板真实到位 |
| 源/目标板位 | tryReserveTransfer 按 ID 顺序申请 | 两次调用各自开连接；非同一事务。第二次失败才释放第一次，进程崩溃可能留下预约直到 TTL |
| 板实体 | position_state 记录 plate_id；plate 表作冗余位置/状态 | 两表更新非总是同一事务；部分冗余更新失败只记日志 |
| 在途板 | takePlate 时将 plate.position_status 设为 IN_TRANSFER（savepoint 保护） | 尚未证明机械臂命令成功、传感器确认、目标 putPlate 与板状态构成单一 TransferTransaction |

### C-01：Island 锁 TTL 是明确的安全权衡 — CONFIRMED / P1

`ExperimentScheduler` 使用进程内 `ConcurrentHashMap`，锁 TTL 为 2 小时。源码注释明确承认 TTL 到期的代价是“可能两个实验同跑一个岛”。

这不能作为生产物理互斥保证。尤其不能把这个锁当成机械臂运动期间的安全租约。

### C-02：双板位预约并非真正原子事务 — CONFIRMED / P1

`PositionLock.tryReserveTransfer()` 按 ID 顺序分别调用 `tryReserve()`。每次 `tryReserve()` 自己开连接执行 UPSERT；第二次失败再调用 `release(first)`。这降低死锁概率，但不是一个数据库事务内的全有或全无操作。

建议后续在单个 Connection/事务内按稳定顺序锁住两行并一起提交；但即便数据库预约原子，也仍需单独处理机械臂物理动作与未知结果。

### C-03：板状态不能仅由命令返回推断 — 设计缺口待验证

`takePlate()` 清空源位置并标记板 IN_TRANSFER；`putPlate()` 写目标位置。当前检查未证明这两步与设备命令 ID、设备 ACK、机械臂到位、扫码/光电/重量等物理确认一一关联。

必须把逻辑状态与物理观测分开：
- logical: RESERVED / OCCUPIED / IN_TRANSIT
- physical: PRESENT / ABSENT / UNKNOWN
- safety: NORMAL / QUARANTINED / MAINTENANCE

命令超时、进程崩溃或响应丢失时，不得自动把板判定为源空/目标已到位。

---

## 5. D — DeviceCapability / RobotCapability

### D-01：驱动能力已有设备级粒度 — CONFIRMED

`DriverDescriptor` 提供：
- 驱动级 `capabilities`
- `deviceCapabilities: Map<deviceInstanceId, Set<capability>>`
- `deviceInstanceIds`
- metadata

`DriverRegistry` 按设备级 capability 建索引，并提供设备级 quarantine/recover。

这对工艺节点按能力找设备有价值，但 capability 不等于空间可达性。

### D-02：机械臂能力/可达性模型未在已检查路径中发现 — NOT_FOUND（范围受限）

当前已检查的 DriverDescriptor / DriverRegistry / RobotPoint / QueryService 路径没有展示：
- robot 可达设备集合及版本；
- 关节/末端执行器/载荷/板型能力；
- 共享工作空间与互斥区；
- 安全区域/禁入区；
- 运动轨迹/碰撞检查；
- 点位校准版本与设备固件兼容矩阵。

这不是证明仓库所有文件都没有这些字段；需要继续检查驱动侧配置、数据库迁移和前端岛屿画布。

### D-03：物理 Abort 默认关闭 — CONFIRMED

`DeviceDriver.supportsPhysicalAbort()` 默认 false；接口文档明确要求只有设备协议停止确认且型号/固件/方法经过 HIL 才能返回 true。该安全默认是正确方向。

但上层仍需区分命令本地取消、设备接受停止、设备报告停止和物理安全确认；不能将线程 Future 取消当作机械臂停止。

---

## 6. E — 事务 / Outbox

### E-01：事件写入是独立连接、autoCommit=true — CONFIRMED

`JdbcEventStore` 构造函数调用 `db.open()` 后设置 `autoCommit(true)`；事件批量写入独立连接。当前代码没有证明业务状态变更与对应事件在同一事务中提交。

### E-02：统一 Outbox — NOT_FOUND（当前检查范围）

在本轮检查的 `RunControl`、`RuntimeHold`、`JdbcEventStore`、`ExperimentScheduler` 路径中，未发现将业务状态与 Outbox 事件原子提交的统一机制。

这不等于仓库全局不存在 Outbox；需要继续检索消息发布、Redis Streams、Gateway 命令持久化和数据库迁移。

### E-03：Pause/Hold 多步写入 — PARTIAL

`RunControl.pause()` 先调用 `RuntimeHold.raise()`，再调用 `markCoveredPaused()` 更新运行记录；两者使用不同数据库连接/事务边界。失败时可能出现 Hold 已持久化、run 状态尚未同步的中间态。

持久化 Hold 仍可作为执行闸门，但 API 应明确返回部分成功/待对账，而不是假设整个动作原子完成。

---

## 7. F — 测试覆盖与缺口

### F-01：项目有测试基础 — CONFIRMED

`labos-kernel/pom.xml` 配置 JUnit 5.10.2 与 Maven Surefire。README 声明可运行：
- `RecoverySelfTest`
- `EngineSelfTest`
- `GraphRunnerTest`

### F-02：本轮未完成测试目录枚举与执行 — UNKNOWN

本轮没有成功取得完整 Git tree，也没有运行 Maven/JUnit/自测命令，因此不能声称没有测试，也不能声称现有测试通过。

### F-03：必须补充的专项验收

1. ExperimentExecutor 每个 workflow 必须携带 experiment.island_id；未绑定 island 的行为必须显式。
2. island 内缺少某 capability 时不得 fallback 到全局设备池。
3. 两个 island 的独立实验可以并行；一个 island 内默认互斥；Hold 只阻断覆盖范围及依赖闭包。
4. RuntimeHold 查询异常必须 UNKNOWN + fail-closed。
5. 两台机械臂同岛、点位 code 相同，坐标读取/更新必须只命中正确 robot_resource_id。
6. syncRobotPoints 不得把不可达设备自动视为可达设备。
7. 两机械臂共享/交叉工作空间时应互斥；完全独立工作区在经过配置证明后可并行。
8. PositionLock 双位置竞争、第二次预约失败、进程崩溃、TTL 到期均要有数据库并发测试。
9. 机械臂完成抓取但 ACK 丢失、板在半空、源已空但目标未到、扫码不符时，板必须进入 UNKNOWN/QUARANTINED，不能自动释放资源。
10. Island 锁过期、Kernel 双实例、Worker crash、旧 fencing token 必须做并发/HIL 验收。

---

## 8. 建议整改顺序（不直接改代码）

| 顺序 | 工作项 | 原因 |
|---|---|---|
| 1 | 修正岛内设备绑定与全局候选回退 | 避免执行越过物理 island 边界 |
| 2 | RuntimeHold 三态查询 + 调度 fail-closed | 避免数据库故障时错误派发 |
| 3 | RobotPoint 全链路增加 robot_resource_id / island_id 约束 | 支持多机械臂，不混淆点位 |
| 4 | 盘点并统一两条实验执行路径 | 避免一条路径有资源约束、另一条绕过 |
| 5 | 机械臂工作空间/设备可达模型 | 为安全并发和故障隔离提供事实基础 |
| 6 | TransferTransaction + 物理确认 + UNKNOWN 对账 | 保护样本与板位真相 |
| 7 | 持久化资源租约 / fencing token / Outbox | 支持多实例和崩溃恢复 |
| 8 | HIL 与故障注入 | 验证物理安全行为，不以单测替代 |

## 9. 本轮明确未做

- 未修改业务代码；
- 未执行数据库迁移；
- 未连接或控制任何真机；
- 未运行 CI、JUnit 或 HIL；
- 未证明当前点位坐标、机械臂可达范围与现场实际布局一致；
- 未合并到 main。

后续需要补充：数据库 schema/migration、Robot driver / sidecar 协议、前端 island canvas、所有 PositionLock 调用点、CI 测试目录和现场机械臂配置。


## 10. 关键源码锚点（便于复核）

| 结论 | 文件与方法 | 关键位置（当前分支行号，后续提交可能变化） |
|---|---|---|
| Scheduler → Executor | `ExperimentScheduler.submit/dispatch/executeOne` | `workflow/ExperimentScheduler.java` 170–240、380–430、430–510 |
| workflow 未绑定 island | `ExperimentExecutor.runStep` | `workflow/ExperimentExecutor.java` 748–810 |
| island filter 存在 | `WorkflowService.withIsland/loadIslandDeviceIds` | `workflow/WorkflowService.java` 198–215、245–260 |
| island filter 空集回退全局 | `WorkflowService.execute` | `workflow/WorkflowService.java` 740–765 |
| RobotPoint 绑定机械臂和设备 | `QueryService.importRobotPointsCsv/robotPointsJson/syncRobotPoints` | `api/QueryService.java` 2092–2180、2203–2270 |
| RobotPoint 更新未限定 robot_resource_id | `QueryService.updateRobotPoint` | `api/QueryService.java` 2018–2040 |
| 点位坐标只按 code 查询 | `ExperimentFlowRunner.enrichWithCoords` | `workflow/ExperimentFlowRunner.java` 155–200 |
| Island Flow JSON | `QueryService.islandFlowJson/saveIslandFlow` | `api/QueryService.java` 2275–2320 |
| Island topology 只查直接子设备 | `QueryService.topologyJson` | `api/QueryService.java` 320–385 |
| 全局 Hold 闸门及 fail-open | `ExperimentScheduler.dispatch` | `workflow/ExperimentScheduler.java` 380–410 |
| Hold 查询异常返回空集合 | `RuntimeHold.active/anyActive` | `workflow/RuntimeHold.java` 270–310 |
| Island 锁 TTL | `ExperimentScheduler.LOCK_TTL/sweepExpiredLocks` | `workflow/ExperimentScheduler.java` 35–45、625–640 |
| 双位置预约不是单事务 | `PositionLock.tryReserveTransfer/tryReserve` | `driver/PositionLock.java` 130–220 |
| 板位置/在途更新 | `PositionLock.putPlate/takePlate/markPlateInTransfer` | `driver/PositionLock.java` 245–420、510–555 |
| 设备级 capability 与 quarantine | `DriverDescriptor/DriverRegistry` | `driver/DriverDescriptor.java` 10–60；`driver/DriverRegistry.java` 15–130 |
| Physical Abort 默认 false | `DeviceDriver.supportsPhysicalAbort` | `driver/DeviceDriver.java` 20–55 |
| 事件独立 autoCommit | `JdbcEventStore` constructor | `persistence/JdbcEventStore.java` 20–55 |
| Pause/Hold 分步写入 | `RunControl.pause/markCoveredPaused` | `workflow/RunControl.java` 80–160 |

