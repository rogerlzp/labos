# LabOS 下一阶段整改实施计划（基于 2026-11-07 独立审计）

> 本文是评审建议，不代表已经实施。业务代码未改。对应详细证据见 `docs/评审记录/20261107-01-gpt6.md`。

## 总原则

- 先封住会导致重复物理动作、错误停止或错误释放资源的 P0。
- 不大规模重写现有执行链；保留 `WorkflowService → GraphRunner → RunStateMachine → DriverStepExecutor`，按边界渐进修复。
- 一次一个小 PR；每个 PR 包含源码、回归测试、运行证据和中英文文档同步。
- 没有真机证据的能力不得标为生产可用。

## Batch 0：执行路径安全边界（P0）

### 0.1 GraphRunner 执行后禁止 fallback 重跑

- 将 BPMN parse/static validation 移到独立 try。
- 构造 GraphRunner 完成后，执行异常只能走异常收敛，不得调用 `executeAll`。
- 添加 run execution phase 标记，至少区分 PREPARED / DISPATCH_STARTED / SETTLED。
- 验收：在设备命令派发后注入 RuntimeException，确保没有顺序路径执行、没有新 commandId 重做前序动作。

### 0.2 abort 语义分离

- 将 DeviceDriver.abort 返回值从 void 改为可审计 AbortResult（需要兼容适配器）。
- 结果至少有 UNSUPPORTED / REQUESTED / DEVICE_ACKNOWLEDGED / PHYSICALLY_STOPPED / UNKNOWN。
- TCP 协议无 Abort 消息时必须返回 UNSUPPORTED，不得以本地 future 完成冒充设备停止。
- 设备驱动无法确认物理停止时，中断节点不得作为可自动重跑的“安全中断”。
- 全部 `interruptible=true` 配置先保持 false，除非逐型号/固件/方法/负载 HIL 验证通过。
- 验收：设备继续运行但响应丢失时，run/command 必须进入 UNKNOWN/RECONCILING，恢复不自动重跑。

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
