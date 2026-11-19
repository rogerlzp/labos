# Workflow Runtime v2.1 评审

- 日期：2026-11-19
- Reviewer：Trae AI（GPT-5.4）
- 评审对象：`LabOS Workflow Runtime 架构设计 v2.0`
- 评审基线：`e4f6efd1c9f37c168549f8946f0c73952e4ab6c6`
- 评审范围：v2.1 修订后的架构文档原文；不含源码、DDL、真实数据库隔离行为、设备 Adapter 协议实现和物理设备联调

---

## 1. 结论

这次 `v2.1` 是明显进步。

相较上一轮，以下关键问题已经实质收敛：

1. `JoinReleased` 时消费全部 branch token 已补上；
2. `WorkerAccepted -> WorkerStarted` 的越级转移已收紧；
3. `StopSafePredicate` 已从“可混入隔离结果”收紧为“必须满足显式安全谓词”；
4. 第二轮反向评审记录已写入正文，且明确区分了 `PASS` 与 `UNKNOWN`。

因此，我的判断是：

> **v2.1 已经可以作为下一阶段详细设计拆分的主架构基线。**

但它仍有 3 个应在详细设计前收口的问题。

---

## 2. Findings

| No. | Issue Title | Suggestion |
|---|---|---|
| 1 | V1 支持范围仍然写得过宽，与待关闭问题表冲突 | 新增 `V1 Implementation Profile`，把“目标架构支持面”与“首批实现承诺”拆开 |
| 2 | Branch / Join 在修订章补了终结语义，但还没补成完整合法转移规范 | 在 v2.1 规范章补一张完整状态转移表，覆盖 `BranchActivation` 与 `JoinActivation` |
| 3 | 仍允许一个 attempt 涉及多个 `commandId`，但等待/完成协议仍按单个 `commandId` 建模 | V1 先收窄成“一个设备型 attempt 只对应一个逻辑 `commandId`” |

---

## 3. 详细问题

### 3.1 Issue 1：V1 支持范围仍然过宽

#### 现象

文档 `3.1` 仍把以下能力直接写进 V1 支持：

- `INCLUSIVE_SPLIT`
- `INCLUSIVE_JOIN`
- `LOOP_ENTER / LOOP_BACK / LOOP_EXIT`

但待关闭问题表里又把：

- `WRT2-02 Inclusive Join 是否需要支持复杂非结构化分支`
- `WRT2-03 Loop 与并行嵌套的完整支持矩阵`

列为 `Stage A` 前必须关闭的问题。

#### 风险

这会让评审者和实现者产生两种不同理解：

1. “这些能力已经属于第一版必须实现”
2. “这些能力只是目标架构支持面，第一版还未冻结”

如果不拆开，详细设计阶段会重复争论“到底算不算 V1 范围”。

#### 建议

新增一章：

`V1 Implementation Profile`

明确分成两层：

- **目标架构支持面**：长期要支持什么
- **首批实现承诺**：第一版真正承诺落什么

我建议第一版默认只承诺：

- 结构化 `PARALLEL_SPLIT -> branches -> PARALLEL_JOIN`
- 结构化 loop 的最小子集
- 不承诺复杂 `INCLUSIVE_JOIN`
- 不承诺复杂 `loop + parallel` 组合

#### 证据

- [3.1 V1 支持](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L186-L200)
- [待关闭问题 WRT2-02/03](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L1729-L1730)

---

### 3.2 Issue 2：Branch / Join 仍缺完整合法转移规范

#### 现象

旧章里的 `BranchActivation` 状态机是：

- `ACTIVE`
- `WAITING_JOIN`
- `ARRIVED`
- `SKIPPED`
- `FAILED`
- `CANCEL_PENDING`
- `CANCELLED`
- `UNKNOWN`
- `QUARANTINED`

没有 `CLOSED`。

但 v2.1 修订章里又新增了：

- `所有 BranchActivation ARRIVED/SKIPPED -> 终结态 CLOSED`

与此同时，`JoinActivation` 在修订章里虽然补了状态集合：

- `CREATED`
- `WAITING`
- `RELEASED`
- `CLOSED_FAILED`
- `QUARANTINED`

但没有像 `Attempt` 那样给出一张完整的“合法转移”表。

#### 风险

这会让实现者在以下地方各自脑补：

- Repository 层状态枚举
- DDL CHECK/enum 约束
- 状态迁移测试
- 失败和恢复时 `JoinActivation` 如何关闭

也就是：语义上已经接近闭合，但规范表达还没完全闭合。

#### 建议

在 v2.1 的规范修订章里，显式补两张表：

1. `BranchActivation` 修订后完整状态集合 + 合法转移表
2. `JoinActivation` 完整状态集合 + 合法转移表

至少明确：

- `ARRIVED/SKIPPED -> CLOSED`
- `FAILED -> CLOSED_FAILED` 或等价收口语义
- `WAITING -> RELEASED`
- `WAITING -> CLOSED_FAILED`
- `UNKNOWN -> QUARANTINED`

#### 证据

- [旧 Branch 状态机](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L2114-L2140)
- [v2.1 Join/Branch 修订](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L3033-L3052)

---

### 3.3 Issue 3：multi-command attempt 与 single-command wait 模型冲突

#### 现象

文档仍保留：

> 一个 `attempt` 可以涉及多个 `commandId`

但设备节点完成条件、等待恢复条件仍然按单个 `commandId` 建模。

例如：

- 设备节点完成条件要求“最终结果按 `commandId` 可靠关联”
- `WaitSatisfied` 仍要求“匹配 `commandId` 且命令事件满足定义的等待条件时才有效”

#### 风险

如果一个设备型 attempt 真发出了多个命令：

- 节点什么时候算完成？
- 哪个 `commandId` 驱动 `WaitSatisfied`？
- 多个命令部分成功 / 部分未知时如何裁决？
- 恢复时 continuation 绑定哪条命令？

这些问题在当前文本中仍然没有闭环。

#### 建议

V1 直接收窄成：

> 一个设备型 `NodeAttempt` 只允许对应一个逻辑 `commandId`。

如果后续确实要支持一个 attempt 多条命令，再单独引入：

- `attempt_command`
- `command_group`
- group completion predicate

不要让第一版在这里留口子。

#### 证据

- [attempt 可含多个 commandId](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L404-L404)
- [命令完成条件](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L973-L984)
- [WaitSatisfied 仍按 commandId 匹配](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L3011-L3011)

---

## 4. 这版已经收掉的问题

以下是我确认相较上一轮已经实质收掉的点：

1. **Join 释放时 waiting branch token 已明确消费**
   - [Join 释放事务](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L3040-L3052)

2. **Attempt 状态机已收紧为 `ACCEPTED -> STARTED`**
   - [Attempt 合法转移](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L2952-L2967)

3. **WaitSatisfied / WaitExpired 已补旧 Attempt 关闭语义**
   - [Suspend / Wait / Resume 协议](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L3007-L3011)

4. **StopSafePredicate 已从“混隔离也能停止”收紧为“必须满足显式安全谓词”**
   - [修订后的 StopSafePredicate](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L3105-L3110)

5. **第二轮反向评审记录已明确区分 PASS 和 UNKNOWN**
   - [第二轮反向评审记录](file:///Users/zhengpingli/work/中控/.review-worktree-e4f6efd/docs/LabOS-Workflow-Runtime-架构设计-v2.0-20261119.md#L3414-L3461)

---

## 5. 我的综合判断

### 5.1 当前定位

这版文档现在可以被视为：

> **下一阶段 Runtime 详细设计拆分的主架构基线**

但还不宜直接被视为：

> **无需再收口即可开编码的最终实现规格**

### 5.2 和我方方案如何合并

我建议继续沿用这个收口方式：

1. **保留 v2.1 作为唯一主架构文档**
2. **把我方方案并入成 `V1 Implementation Profile`**
3. **在进入详细设计前，优先修正本评审列出的 3 个问题**

### 5.3 下一步建议

建议顺序：

1. 先补：
   - `V1 Implementation Profile`
   - `BranchActivation / JoinActivation` 完整转移表
   - V1 中设备型 attempt 与 commandId 的一对一约束
2. 再拆详细设计：
   - `Runtime State Machine`
   - `Persistence & Device Protocol`
   - `Workflow Definition & Compiler`

---

## 6. 最终结论

我的结论是：

> **v2.1 已经足够作为“主架构基线”继续推进；但在进入详细设计前，仍建议先修完这 3 个问题。**

如果只问一句话：

> 这版已经“能作为主线继续走”，但还不是“可以无歧义直接编码”的最终规格。

