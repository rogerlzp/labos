# Workflow Runtime T24：Runtime Host 集成盘点与实施方案

- 日期：2026-11-19
- 基线分支：`docs/workflow-runtime-single-instance-20261119`
- 基线提交：`e508e6e80f3ad28df1ecb3a7d0fb31723bcf1437`
- 实施分支：`feat/workflow-runtime-t24-integration-20261119`
- 部署模型：一个 Runtime Host 进程 + PostgreSQL 17
- 状态：源码盘点完成；执行层尚未实现；本文件定义 T24 实施边界，不宣称集成已完成

## 1. 结论

当前 `workflow-runtime` 已有持久化与恢复基础，但还不是可运行的 Workflow Runtime。

当前模块实际提供：
- `WorkflowRuntimeRepository`：T01–T23 的事务入口、Attempt/Token/Run 状态提交、Command Ledger、Inbox/Outbox、Drain、Checkpoint 和恢复操作；
- `WorkerExecutionRegistry`：单进程内 Attempt 到 Thread 的执行登记与尽力取消；
- `WorkerExecutionRecoveryService`：Outbox 重启恢复、Interrupted Attempt 扫描和本地超时恢复；
- PostgreSQL 17 DDL、Schema Contract、并发/原子性测试和 Repository 集成测试。

当前缺少可运行的执行/编排层：
- Runtime Host 生命周期与启动门闩；
- RunCoordinatorRegistry、GraphCoordinator、串行 Mailbox；
- 全局有界 WorkerPool、每 Run 并发额度的进程内执行调度；
- Worker Handler/ExecutionContext/Result 合约；
- Outbox Dispatcher；
- Device Adapter 合约及 Mock Adapter；
- Event Ingress、Inbox 消费与 EventDispatcher；
- 将上述组件装配起来的端到端测试。

因此，不能将当前 Repository/Registry/RecoveryService 描述为“完整 Runtime 已集成”。T24 的工作是补齐执行层，并以 Mock Adapter 建立可重复的最小闭环。

## 2. 源码盘点证据

| 检查项 | 当前事实 | 证据 |
|---|---|---|
| Runtime 模块 | 独立 Maven 项目，Java 17，Spring JDBC；不是 Spring Boot 应用 | `workflow-runtime/pom.xml` |
| 持久化层 | Repository 暴露 T01–T23 的事务操作 | `WorkflowRuntimeRepository.java` |
| Worker 登记 | 仅保存本地 Thread、workerId、取消回调 | `WorkerExecutionRegistry.java` |
| 恢复入口 | 可执行数据库恢复扫描，但不调用设备 Adapter 查询或回读 | `WorkerExecutionRecoveryService.java` |
| 模块自述 | README 明确声明完整 Host、WorkerPool/Coordinator/Adapter 集成尚未由当前模块提供 | `workflow-runtime/README.md` |
| 测试覆盖 | Repository PG 集成测试 7 项；Registry 测试 2 项；没有 Host/Coordinator/WorkerPool/Adapter 闭环测试 | `workflow-runtime/src/test/java/` |
| 旧 Kernel | 独立 Spring Boot 启动入口，旧 GraphRunner 属于旧执行链 | `labos-kernel/pom.xml`、`labos-kernel/src/boot/java/com/labos/boot/LabosApplication.java`、`labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java` |
| 新旧依赖 | `labos-kernel/pom.xml` 未声明 `workflow-runtime` 依赖；仓库没有根 Maven reactor 聚合两者 | 两个 Maven POM |

### 2.1 关键边界

1. `workflow-runtime` 是新执行内核，不读写旧 GraphRunner 的运行状态表。
2. 不把旧 `GraphRunner` 改造成新 `GraphCoordinator`；两者的状态模型、持久化模型和副作用协议不同。
3. 不把旧 Kernel POM 中的 Redis 客户端或旧跨实例锁叙述带入 V1 新 Runtime。
4. 本阶段先在 `workflow-runtime` 独立模块内完成 Host + Mock Adapter 闭环；是否由现有 Spring Boot 容器托管，留到 API/部署集成阶段单独决策，不提前引入双重执行入口。
5. 不新增分布式协调、数据库 Worker Lease、Leader Election、fencing 或跨进程接管。

## 3. T24 目标拓扑

```
                    RuntimeHost
          ┌──────────────┼────────────────┐
          │              │                │
     RecoveryService  EventIngress   LifecycleGate
          │              │                │
          │           Inbox写入            │
          │              ▼                │
          │        EventDispatcher        │
          │              │                │
          ▼              ▼                ▼
     Repository   RunCoordinatorRegistry  Scheduler
                         │                  │
                   GraphCoordinator         │
                    SerialMailbox           │
                         │                  │
                         └──────┬───────────┘
                                ▼
                       Bounded WorkerPool
                                │
                         Worker Handler
                                │
                     Repository副作用事实
                                │
                                ▼
                         OutboxDispatcher
                                │
                           DeviceAdapter
                         （Mock / Real）
                                │
                       设备事件 / 状态回读
                                │
                                ▼
                          EventIngress

                   PostgreSQL 17
       Run / Token / Attempt / Event / Inbox / Outbox
              Command Ledger / Worker Facts
```

图中是逻辑职责，不要求每个方框都成为独立线程或独立服务。V1 是一个进程，内部通过明确的接口和线程边界组织。

## 4. Host 生命周期与启动顺序

Host 状态建议固定为：

`NEW → STARTING → RECOVERING → RUNNING → STOPPING → STOPPED`

启动失败进入 `FAILED`。非法重复启动必须拒绝；关闭操作幂等。

启动顺序：
1. 校验配置并初始化 DataSource、Repository、Adapter 和进程内组件；调度门闩保持 CLOSED。
2. 启动 EventIngress 的接收能力。收到的设备事件只允许先写入 Inbox；在恢复完成前不得推进图状态。
3. 执行 `WorkerExecutionRecoveryService.recoverAtStartup(...)`。恢复扫描失败时 Host 不得进入 RUNNING。
4. 创建/恢复需要管理的 RunCoordinator；每个 Run 只允许一个本地 Coordinator。
5. 启动 WorkerPool 和 Outbox Dispatcher，但 Dispatcher 必须服从 Host 生命周期门闩；Recovery 完成前不能发送新命令。
6. 打开 EventProcessor、Scheduler 和调度门闩，Host 才进入 RUNNING。

注意：
- Inbox 的持久化接收与 Inbox 的业务处理是两个阶段；启动恢复期间允许前者，不允许后者。
- `recoverAtStartup` 当前只依据数据库事实做保守恢复，不代表已完成设备 commandId 查询或物理状态回读。T24 先确保未知状态不被重发；真实 Adapter 的查询/回读属于后续适配器能力。
- 若恢复结果包含 MANUAL_REQUIRED，相关 Run 必须继续保持 control gate CLOSED；不能因为 Host 整体启动成功而解锁该 Run。
- 若 Outbox 恢复或 Attempt 扫描未完整完成，禁止以“部分成功”开放调度。

关闭顺序：
1. 关闭调度门闩，不再接纳新 Attempt。
2. 停止 Outbox 新发送。
3. 对本地 Worker 发出尽力取消；不能把线程中断视为设备停止。
4. 让已到达的 Adapter 事件继续写 Inbox，或按 Adapter 合约明确关闭事件通道。
5. 关闭 WorkerPool、Coordinator Mailbox、Adapter 和 DataSource。
6. 对所有未确认的副作用保留持久化事实，交由下次启动恢复；不得伪造成功或自动重发。

## 5. 组件职责与接口边界

### 5.1 RuntimeHost

唯一生命周期入口。负责装配、启动顺序、关闭顺序、健康状态和调度门闩。不直接实现图推进或设备协议。

### 5.2 GraphCoordinator / SerialMailbox

- 每个 Run 一个 GraphCoordinator。
- 所有影响同一 Run 图状态的事件进入该 Run 的串行 Mailbox。
- Coordinator 是唯一的图状态推进者；Worker 不得直接修改 Token/Activation/Run。
- Mailbox 必须有界；过载时拒绝/背压，不允许无界堆积。
- Coordinator 通过 Repository 完成状态事务；事务提交后再触发后续本地动作。
- T24 第一版只实现当前已支持的最小节点执行路径；Split/Join/Loop/Wait 的全图语义不得用假实现宣称完成。

### 5.3 WorkerPool

- 一个 Host 只有一个全局有界线程池。
- 每 Run 并发额度在本地调度层执行；Repository 的容量预留仍是持久化兜底。
- Worker 只接收不可变 ExecutionContext，返回 WorkerResult。
- Worker 通过 Registry 登记和注销；取消是尽力行为。
- 线程池拒绝执行时，必须走明确的 Attempt 回滚/安全失败路径，不得遗留 RESERVED/ACCEPTED 状态。

### 5.4 OutboxDispatcher

- 只发送 Repository 返回的 OutboxMessage。
- 调用 Adapter 前必须完成 `markCommandSendStarted` 所要求的事务提交。
- Adapter 调用在数据库事务之外执行。
- 发送成功只表示 Adapter 已接受请求；不能直接完成 Attempt。
- 发送结果不确定时保持 UNKNOWN/查询路径，禁止盲目重发。
- 单实例本地调度，不实现 claim owner/epoch/lease。

### 5.5 DeviceAdapter / Mock Adapter

Adapter 至少提供：
- 发送命令（携带稳定 commandId）；
- 查询命令状态（按 commandId）；
- 读取设备状态/安全点；
- 接收设备事件，事件必须关联 commandId 或有明确可验证的关联键；
- 关闭与取消能力（取消不等于物理停止）。

Mock Adapter 必须模拟 ACK 与物理完成分离，支持延迟、重复事件、事件丢失、发送后断连、命令查询结果未知等测试行为。Mock 不能把 ACK 直接当成 SUCCEEDED。

### 5.6 EventIngress / EventDispatcher

- EventIngress 负责校验来源、接收事件、调用 `receiveInbox` 持久化去重。
- EventDispatcher 只处理已持久化且未处理的 Inbox。
- 同一 Run 的事件必须路由到同一 GraphCoordinator Mailbox。
- 同 messageId 同 payload 幂等；同 messageId 不同 payload 触发既有冲突审计。
- 事件处理失败时保留可恢复状态，不能先标记 Inbox 已处理再推进业务状态。

## 6. 分阶段实施与验收

### T24-A：Host 生命周期骨架

交付：
- RuntimeHost、HostState、LifecycleGate；
- 启动/关闭状态机；
- Recovery 完成前调度门闩不可打开；
- 启动失败关闭资源并进入 FAILED；
- Host 生命周期单测。

验收：
- Recovery 抛错时 Scheduler/Outbox 不启动；
- Recovery 未返回前调度门闩保持 CLOSED；
- 重复 start/close 行为确定；
- MANUAL_REQUIRED 不会被 Host 启动流程清除。

### T24-B：WorkerPool 与 Coordinator

交付：
- 有界 WorkerPool；
- 每 Run 并发额度；
- RunCoordinatorRegistry；
- 有界 SerialMailbox；
- WorkerExecutionRegistry 接入真实执行生命周期。

验收：
- 同 Run 事件串行；
- 不同 Run 可以并行；
- 全局 Worker 数不超过配置；
- 每 Run 并发不超限；
- 拒绝执行不遗留半激活 Attempt；
- Worker 无权直接推进 Run 图状态。

### T24-C：Outbox / Adapter / Inbox

交付：
- OutboxDispatcher；
- DeviceAdapter 接口；
- MockDeviceAdapter；
- EventIngress 和 Inbox 处理器。

验收：
- `SIDE_EFFECT_STARTED` / `SEND_STARTED` 先提交再调用 Adapter；
- ACK 不会完成 Attempt；
- 完成事件才触发完成路径；
- 重复事件幂等；
- 发送后未知不自动重发。

### T24-D：最小端到端闭环

交付一条 Mock Adapter 端到端用例：

`createRun → startRun → reserveCapacity → dispatchAttempt → acceptAttempt → startAttempt → createCommand → Outbox send → Adapter ACK → Inbox event → physical completion → completeAttempt`

验收：
- PostgreSQL 17 集成测试可重复运行；
- 每个状态变化均可在数据库事实中追溯；
- 设备 ACK 与物理完成分别测试；
- 故障注入后恢复行为符合单实例恢复协议。

### T24-E：故障注入

至少覆盖：
- Host 在 Worker 开始前退出；
- Worker 开始后、SIDE_EFFECT_STARTED 前退出；
- SIDE_EFFECT_STARTED 提交后、Adapter 调用前退出；
- Adapter 接收命令后、ACK 前断连；
- ACK 后、完成事件前退出；
- 完成事件重复、丢失、乱序；
- Host 重启后 Outbox/Attempt 恢复；
- 设备状态无法查询时进入 UNKNOWN/MANUAL_REQUIRED。

## 7. 重要设计问题（实现前必须逐项解决）

1. **Run 图定义的读取与解析**：Repository 的 `createRun` 保存 graphPlan JSON，但当前执行层尚无新的 GraphPlan 类型/解析器。T24-D 先定义最小不可变 GraphPlan 契约，不复用旧 GraphRunner 内部对象。
2. **Attempt 启动顺序**：调度事务完成后，Coordinator 必须按 `dispatchAttempt → acceptAttempt → startAttempt` 语义推进，并保证线程池拒绝时有明确恢复路径。
3. **Outbox 类型区分**：Dispatcher 必须区分内部 Worker 派发消息与设备 Command 消息，不能把所有 Outbox payload 都发给设备 Adapter。
4. **设备命令与 Worker 生命周期**：Worker 可以创建命令并结束等待，也可能等待设备完成；必须在实现前确定 Attempt 是由 Worker 持续等待还是由设备事件驱动完成，不能同时由两条路径重复 complete。
5. **启动恢复与设备查询**：当前 RecoveryService 不依赖 Adapter。真实设备查询/回读需定义单独的 ReconciliationPort；未接入时保守进入 UNKNOWN/MANUAL_REQUIRED，不得推测完成。
6. **RunCoordinator 装载策略**：启动时需从数据库恢复活动 Run 和待处理 Event/Inbox；当前 Repository 尚未提供完整 Run 列表/GraphPlan 读取 API，需先补只读查询契约。
7. **多租户边界**：所有 Host 入口必须明确 tenantId；不能从不可信事件 payload 推断 tenantId。

以上问题是 T24 的实现输入，不允许通过默认值、空实现或假数据绕过。

## 8. 不在 T24 范围内

- 多 Runtime Host、分布式锁、Leader Election、数据库 Worker Lease、fencing；
- 直接迁移或重写旧 GraphRunner；
- 接真实厂商 SDK/真机协议；
- 在没有设备回读能力时声称已完成物理恢复；
- 全量实现所有 BPMN 语义后再开始验证；
- 把 Mock Adapter 的通过等同于真机验收；
- 合并到 main。

## 9. 当前状态声明

截至基线 `e508e6e`：
- Repository/DDL/Schema Contract/Worker Registry/RecoveryService 已实现；
- PostgreSQL 17 CI 对其覆盖范围通过；
- Runtime Host、Coordinator、WorkerPool、Outbox Dispatcher、Adapter、Event Ingress 和端到端 Mock 闭环尚未实现；
- T24 当前完成的是源码盘点和实施方案，后续代码必须逐阶段提交并通过对应测试。


## 10. T24 首轮实施进度（2026-11-19）

源码盘点后先修复了两个与 Host 启动/执行生命周期直接相关的缺口：

1. **启动恢复分页**：原 `recoverAtStartup(limit, ...)` 只读取一页 STARTED Attempt。若待恢复数量超过 limit，剩余 Attempt 会留在 STARTED 状态而 Host 可能误以为恢复扫描已完成。现改为重复读取并处理，直到没有 STARTED 候选；新增 PG 集成测试，使用 limit=1 验证两条 Attempt 都被扫描。
2. **Worker 注销与取消竞态**：原 `unregister` 先从 Map 删除再标记 Entry finished，和并发 `requestCancellation` 的锁顺序不一致。现将 finished 标记与 Map 删除放在同一 Entry 锁内，和 `cancel()` 串行化。
3. **PR CI 触发范围**：原 workflow 的 `push.paths` 包含 `workflow-runtime/**`，但 `pull_request.paths` 漏掉该目录，导致仅修改 Java Runtime 模块的 PR 不会触发本工作流。现补入该路径。

以上代码已提交到本 T24 分支，等待 GitHub Actions 对最新提交执行验证。未通过 CI 前，不标记为 PASS。

本轮尚未实现 RuntimeHost、GraphCoordinator、WorkerPool、OutboxDispatcher、Adapter 或端到端 Mock 闭环；这些仍按 §6 的顺序推进。

### T24-A 实际代码进度

已新增：
- `host/SchedulingGate`：默认 CLOSED，只有 Host 生命周期包内可打开/关闭；提供 `requireOpen()` 供后续调度入口强制检查。
- `host/RuntimeHost`：NEW/STARTING/RECOVERING/RUNNING/STOPPING/STOPPED/FAILED 生命周期；EventIngress 在恢复前启动，其他组件在恢复完成后启动，最后打开调度门闩；启动失败按逆序清理已启动或部分启动的组件。
- `RuntimeHostTest`：覆盖恢复先于 Dispatcher/Scheduler、恢复失败不开放门闩、组件启动失败逆序清理。

这只是 Host 生命周期控制器和组件端口，不是完整可执行 Host：当前还没有具体 EventIngress、Scheduler、Outbox Dispatcher、WorkerPool 或 Adapter 实现。T24-A 需待 CI 通过并补齐实际装配后才能验收完成。

### T24-B 首个交付：有界 WorkerPool

已新增 `worker/WorkerPool`：
- 一个 Host 共享一个固定大小 ThreadPoolExecutor；
- 使用有界 ArrayBlockingQueue，拒绝超出全局线程/队列容量的任务；
- 每 Run in-flight 额度同时统计运行中与排队中任务，避免单个 Run 占满全局队列；
- Attempt 运行时接入 WorkerExecutionRegistry；排队取消和运行中取消都可释放本地容量；
- 所有提交都要求 SchedulingGate 已打开；
- close 时停止接收新任务、尽力取消现有任务并中断线程。

新增 `WorkerPoolTest` 覆盖每 Run 限额、全局队列上限、取消/注销和调度门闩。此交付尚未接入 GraphCoordinator；CI 未确认前不标记 PASS。

### T24-B 第二个交付：每 Run 串行 Mailbox

已新增：
- `coordinator/SerialMailbox<T>`：每个 Run 使用有界队列；共享 Executor 执行 drain；同一 Mailbox 同时最多处理一个事件；队列满时显式拒绝；单条 handler RuntimeException 通过对应 Future 暴露，不静默吞掉。
- `coordinator/RunCoordinatorRegistry<T>`：每个 Run 唯一 Mailbox，多个 Run 共享 Executor；只允许在 Mailbox 空闲时移除，防止旧 Mailbox 尚在处理事件时创建同 Run 的新 Mailbox。
- 对应顺序、容量、异常和 Registry 测试。

边界：这两个类提供串行事件投递基础设施，不等于 GraphCoordinator。GraphPlan 解释、Repository 状态推进、Inbox 事务处理和 Run 生命周期恢复仍未实现。
