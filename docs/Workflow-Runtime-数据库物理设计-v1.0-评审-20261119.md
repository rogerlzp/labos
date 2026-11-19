# Workflow Runtime 数据库物理设计 v1.0 评审

- 日期：2026-11-19
- Reviewer：Trae AI（GPT-5.4）
- 评审范围：
  - `docs/LabOS-Workflow-Runtime-数据库物理设计-v1.0-20261119.md`
  - `db/workflow_runtime/001_init.sql`
  - `docs/LabOS-Workflow-Runtime-T01-T23-数据库事务与测试设计-v1.0-20261119.md`
- 评审基线：`92e089622bf58f58d8b0c236eefa48dfbba77e01`
- 评审目标：**只检查一致性与可实现性**，不评价总体架构方向

---

## 1. 结论

这轮数据库交付已经把 Runtime 的持久化结构从零定义出来了，方向上是完整的：

- 物理设计文档给出了 ERD、表职责、锁顺序、事务映射
- `001_init.sql` 已经能表达大部分核心实体与关键唯一约束
- 文档也明确承认“DDL 已编写 != 已通过真实 PostgreSQL 17 验证”

但就**一致性和可实现性**来看，仍有 4 个问题建议先修。

其中前 2 条优先级最高；如果不先修，后续写 `T03 / T07 / T08 / T10` 的 SQL 事务时会直接撞墙。

---

## 2. Findings

| No. | Issue Title | Suggestion |
|---|---|---|
| 1 | DDL 的状态集合无法表达 v2.1 规范状态机 | 补齐 DDL 枚举，或在物理设计文档里明确“规范态 -> 物理投影态”的唯一映射表 |
| 2 | `dispatch_reservation` 依赖 `attempt_id`，但 `T03-R` 明确先于 Attempt 创建 | 让 reservation 绑定 `token_id/activation_id`，或修改事务基线，明确 Attempt 在 `T03-R` 即预创建 |
| 3 | 泛化的 `runtime_outbox` 无法直接承接 `T03-D` 的 Worker 派发身份要求 | 增加 `worker_dispatch_outbox` / `worker_dispatch_lease`，或把 `attempt_id + dispatch_id + claim/fencing` 提升为显式列 |
| 4 | `dispatch_reservation.capacity_key` 没有外键到 `scheduler_capacity` | 增加 `(tenant_id, capacity_key)` 复合外键，避免容量账本和 reservation 账本脱钩 |

---

## 3. 详细问题

### 3.1 Issue 1：DDL 状态集合无法表达 v2.1 规范状态机

#### 现象

v2.1 主架构文档中，规范态已经定义得很细：

- `Token`：`CREATED / READY / RESERVED / IN_FLIGHT / WAITING / CONSUMED / TERMINAL / INVALIDATED`
- `NodeActivation`：`CREATED / READY / ACTIVE / SUSPENDED / RETRY_WAIT / COMPLETED / FAILED / MANUAL_REQUIRED / UNKNOWN / CANCELLED / QUARANTINED`
- `Attempt`：`CREATED / DISPATCH_PENDING / DISPATCHED / ACCEPTED / STARTED / SUSPENDED / SUCCEEDED / RETRYABLE_FAILED / PERMANENT_FAILED / UNKNOWN / ABANDONED_SAFE`
- `JoinActivation`：`CREATED / WAITING / RELEASED / CLOSED_FAILED / QUARANTINED`

但当前 `001_init.sql` 里的状态列被明显压扁了：

- `execution_token.token_state`：只有 `READY / RESERVED / RUNNING / WAITING / CONSUMED / INVALIDATED / FAILED`
- `node_activation.activation_state`：只有 `READY / RESERVED / RUNNING / WAITING / SUCCEEDED / FAILED / CANCELLED / ABANDONED_SAFE`
- `node_attempt.attempt_state`：只有 `CREATED / DISPATCHED / ACCEPTED / STARTED / SUCCEEDED / FAILED / CANCELLED / ABANDONED_SAFE / UNKNOWN`
- `branch_activation.branch_state`：只有 `ACTIVE / WAITING / ARRIVED / CANCELLED`
- `join_activation.join_state`：只有 `WAITING / RELEASED / CANCELLED`

#### 风险

如果这是**有意的物理压缩**，文档现在没有把映射规则写出来。  
如果这不是有意压缩，那当前 DDL 就无法无损承载主架构语义。

这会直接带来三类问题：

1. SQL 实现时必须私自脑补哪些规范态映射到哪个物理态；
2. 自动化测试无法判断“DDL 是否真的覆盖规范状态机”；
3. 某些事务在物理层根本没有合法终点，例如：
   - `DISPATCH_PENDING`
   - `SUSPENDED`
   - `RETRYABLE_FAILED / PERMANENT_FAILED`
   - `SKIPPED`
   - `QUARANTINED`
   - `CLOSED_*`

#### 建议

二选一，必须明确：

**方案 A：状态同构**

- 直接把 DDL 状态集合补齐到与 v2.1 规范态同构

**方案 B：状态投影**

- 在物理设计文档中新增一张“规范态 -> 物理态”映射表
- 明确每个被压缩掉的状态如何唯一落库
- 明确哪些语义靠附加字段表达，而不是靠状态列表达

如果没有这张映射表，后续 Repository 和 SQL 事务实现会不可避免地产生分叉。

#### 证据

- [v2.1 规范状态集](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L2933-L2968)
- [当前 DDL 状态列](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/db/workflow_runtime/001_init.sql#L71-L147)

---

### 3.2 Issue 2：`dispatch_reservation` 依赖 `attempt_id`，但 `T03-R` 明确先于 Attempt 创建

#### 现象

物理设计文档把 `T03` 拆成：

- `T03-R Reservation`
- `T03-D Dispatch Commit`

事务基线里也明确写了：

- `T03-R` 负责预留容量、把 Token 置 `RESERVED`
- `T03-D` 才进入 `Attempt / Activation / Outbox` 的派发提交

但 DDL 中：

- `dispatch_reservation.attempt_id uuid NOT NULL`
- 并且有外键 `REFERENCES node_attempt`

这意味着没有 `node_attempt` 就插不进 `dispatch_reservation`。

#### 风险

这和事务基线的“两个短事务”模型直接冲突：

- 如果 `T03-R` 时还没有 Attempt，那 reservation 无法落库
- 如果为了满足 DDL，在 `T03-R` 就先造 Attempt，那文档基线就变了

这不是实现细节问题，而是**物理模型和事务协议的根本冲突**。

#### 建议

二选一：

**方案 A：保留当前事务基线**

- `dispatch_reservation` 改为绑定 `token_id` 或 `activation_id`
- `T03-D` 时再把 reservation 与 `attempt_id` 关联起来

**方案 B：修改事务基线**

- 明确 Attempt 在 `T03-R` 预创建
- 那么 `T03-R` 不再只是 Reservation，而是 Reservation + Attempt bootstrap

我的倾向是 **方案 A**，因为它更符合当前文档中“Reservation 与 Dispatch Commit 两个短事务”的原意。

#### 证据

- [物理设计文档 T03-R/T03-D 映射](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/docs/LabOS-Workflow-Runtime-数据库物理设计-v1.0-20261119.md#L127-L128)
- [事务基线 T03 说明](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/docs/LabOS-Workflow-Runtime-T01-T23-数据库事务与测试设计-v1.0-20261119.md#L237-L277)
- [dispatch_reservation DDL](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/db/workflow_runtime/001_init.sql#L160-L175)

---

### 3.3 Issue 3：泛化的 `runtime_outbox` 无法直接承接 `T03-D` 的 Worker 派发身份要求

#### 现象

事务基线文档中，`T03-D Dispatch Commit` 依赖的是：

- `worker_dispatch_outbox`
- `(attempt_id, dispatch_id) UNIQUE`
- 以及与 worker 派发相关的身份和恢复语义

但当前 DDL 没有单独的 `worker_dispatch_outbox`，只有泛化的：

- `runtime_outbox`
- 唯一键为 `(tenant_id, dedup_key)`

同时也没有看到：

- `attempt_id`
- `dispatch_id`
- 结构化的 worker dispatch lease/fencing 列

#### 风险

如果后续全靠 `dedup_key` 这类字符串来拼 Worker 派发身份，那么：

1. DDL 不能直接证明 `(attempt_id, dispatch_id)` 的唯一性；
2. 事务基线里的“重复请求返回原 outbox”只能靠应用层协议维持；
3. code review / schema review 无法判断“这是不是同一个 dispatch”。

这不一定会让系统做不出来，但它会让“物理设计已经承载事务基线”这个说法站不稳。

#### 建议

两个可选方向：

**方案 A：单独建 Worker 派发表**

- `worker_dispatch_outbox`
- `worker_dispatch_lease`

这样最贴近事务基线原文。

**方案 B：继续使用泛化 `runtime_outbox`**

但至少要把下列列提升为显式结构化列：

- `attempt_id`
- `dispatch_id`
- `claim_owner`
- `claim_epoch`
- `claim_until`

否则这块仍然只是“逻辑可行”，不是“物理模型已承载”。

#### 证据

- [事务基线实体定义](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/docs/LabOS-Workflow-Runtime-T01-T23-数据库事务与测试设计-v1.0-20261119.md#L48-L56)
- [T03-D Dispatch Commit 细节](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/docs/LabOS-Workflow-Runtime-T01-T23-数据库事务与测试设计-v1.0-20261119.md#L261-L277)
- [runtime_outbox DDL](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/db/workflow_runtime/001_init.sql#L353-L372)

---

### 3.4 Issue 4：`dispatch_reservation.capacity_key` 没有外键到 `scheduler_capacity`

#### 现象

物理设计文档明确把这两个表放在同一 Async 子域：

- `scheduler_capacity`
- `dispatch_reservation`

事务基线也明确要求：

- `T03-R` 需要锁定容量行
- 容量扣减必须原子化

但当前 DDL 中：

- `dispatch_reservation.capacity_key text NOT NULL`
- 却没有 `(tenant_id, capacity_key)` 到 `scheduler_capacity` 的外键

#### 风险

这意味着数据库层允许出现：

- 指向不存在容量桶的 reservation
- capacity 表和 reservation 表键空间不一致
- reconcile 阶段只能靠业务代码兜底

对于一套主打“从零定义物理结构”的新 Runtime，这个缺口不应该留到实现阶段才发现。

#### 建议

直接补上复合外键：

- `FOREIGN KEY (tenant_id, capacity_key) REFERENCES scheduler_capacity(tenant_id, capacity_key)`

除非文档明确要求 reservation 可以引用“尚未持久化的临时 capacity key”，但当前文本并没有这种语义。

#### 证据

- [Async 子域职责](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/docs/LabOS-Workflow-Runtime-数据库物理设计-v1.0-20261119.md#L63-L69)
- [T03-R 容量语义](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/docs/LabOS-Workflow-Runtime-T01-T23-数据库事务与测试设计-v1.0-20261119.md#L247-L249)
- [capacity + reservation DDL](file:///Users/zhengpingli/work/中控/.review-worktree-92e0896/db/workflow_runtime/001_init.sql#L149-L176)

---

## 4. 我认为这版已经做对的地方

这轮我没有只看问题，也确认了几处重要优点：

1. **文档明确承认“DDL 已写 != PG17 已验证”**
   - 这点很重要，避免把设计状态误写成验证通过

2. **同一 Attempt 最多一个逻辑 Command**
   - `command_ledger UNIQUE (tenant_id, attempt_id)` 这点是清楚的

3. **Run event_seq 的唯一性是结构化的**
   - `runtime_event PRIMARY KEY (tenant_id, run_id, event_seq)`

4. **Inbox 冲突消息不覆盖首次 Receipt**
   - 通过 `inbox_protocol_violation` 单独存证，这条设计是对的

5. **ResourceLease 对 `QUARANTINED / EXPIRED_UNVERIFIED` 继续占位**
   - 这一点和主架构语义是一致的

---

## 5. 综合判断

我的判断是：

> **这版数据库物理设计已经是一个可评审、可继续推进的第一版基线，但还不能宣称“物理模型已经与 Runtime 主架构和 T01–T23 事务基线完全闭合”。**

优先级建议：

1. **先修 Issue 1 和 Issue 2**
   - 它们会直接卡住后面的 SQL 实现
2. 再收 Issue 3 和 Issue 4
   - 它们更偏“物理承载能力是否足够明确”

如果一句话总结：

> 这版已经足够开始做“DDL 修订 + T03/T07/T08/T10 SQL 设计”，但还不够直接进入 Repository 编码。

