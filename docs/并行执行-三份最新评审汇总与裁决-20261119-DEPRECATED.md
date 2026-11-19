# 并行执行：三份最新评审的汇总与裁决

> ⚠ **已废弃（2026-11-19）**：内容已被《并行执行-设计文档-20261119.md》取代或仅为历史记录，仅作追溯，**不再作为实施依据**。

- 日期：2026-11-19
- 基线：三份评审均针对 `09b4272`（同一提交），因此可逐条对照
- 性质：**评审汇总 + 裁决**，不含设计细节
- 目的：一份文档看清"三份评审说了什么、哪些一致、哪些冲突、怎么裁决、还剩什么"

---

## 0. 一页纸

| 项 | 内容 |
|---|---|
| 评审份数 | 3 份（Trae / MiniMax / ChatGPT），基线相同 |
| 提出意见 | **19 条**（去重后） |
| 三份共识 | 5 条 |
| 两份共识、一份补充 | 4 条 |
| 单份独有 | 10 条 |
| **真实分歧** | **2 处**（拆成两次的方案、15 分钟的性质） |
| 裁决后待办 | P0 6 项 / P1 5 项 / P2 3 项 |
| 我自己推翻的判断 | 3 条 |

---

## 1. 三份评审的画像

| | Trae | MiniMax | ChatGPT |
|---|---|---|---|
| 方法 | 读代码 + 逐条拷问 | 盘点下发路径 + 排除担心 | 跨文档比对 + 代码注释取证 |
| 最强的地方 | 挖出 `GraphRunner` 的三套联动变量 | 证明"hold 假设不会破"（下发路径已收口） | 发现 `withVariables` 会复制 Map |
| 独有发现 | 三套联动字段清单 | 两个 15 分钟要分别命名 | 5 处跨文档口径矛盾 |
| 明显短板 | 未验证旁路是否真存在 | 未检查 `withVariables` 实现 | 把"资源等待"误当"执行上限"（见 §4.2） |

---

## 2. 三份共识（5 条）

### 2.1 `device_physical_hold` 必须补 owner / 原子性

| 评审 | 提法 |
|---|---|
| Trae | 缺 owner/CAS ⇒ 旧完成事件会误清新占用；hold 期间又受理新命令 ⇒ `QUARANTINED` |
| MiniMax | 假设不破，但补三条：检查放 `executeCommand` 收口点、记 `owner_command_id` 并告警、TTL 兜底复用 R-10 |
| ChatGPT | **建立太晚**（下发→ACK 窗口）、不能约束绕过中控的命令 ⇒ 改为**派发前原子租约** |

**裁决：三条合并采纳。** 最终形态：

```
1. 派发前原子申请设备租约（RESERVED）    ← ChatGPT 的时机修正
2. 记录 owner_command_id + hold_epoch     ← Trae 的归属修正
3. ACK 到达：RESERVED → ACCEPTED
4. 完成事件：按 (device, command_id, epoch) 条件清除  ← Trae 的 CAS
5. ACK 超时：→ UNKNOWN，不释放            ← ChatGPT 的保守处置
6. TTL 兜底：复用 R-10 / E-19             ← MiniMax 的复用
7. 检查点放 executeCommand（唯一收口）      ← MiniMax 的落点
```

**三份缺一不可**：Trae 给了归属，MiniMax 给了落点和兜底，ChatGPT 给了时机。

### 2.2 共享变量不是换个容器就行

| 评审 | 提法 |
|---|---|
| Trae | 还有 `locallyWrittenVars` / `consumedValues` / `externallyOwnedVars` 三套联动 |
| MiniMax | 顺序无语义依赖（已核实零遍历），但 `alreadyConsumed` 是读-改-删，要 `compute` |
| ChatGPT | **`withVariables` 会 `new LinkedHashMap` 复制** ⇒ 根本没共享；且读-改-写不原子 |

**裁决：采纳 ChatGPT 的方案为主，另两份作补充。**

```
FlowExecutionContext {
    FlowVariableStore variables;   // 共享同一实例，不复制
    AtomicBoolean stopFlag;
}
```

- `withVariables(map)` 改为 `withContext(ctx)`，注入引用
- `FlowVariableStore` 内部管四个容器，对外暴露 `set/get/consume/refresh` 四个原子方法
- `alreadyConsumed` 用 `compute`，`writeVar/consumeVar` 的三步序列收进 store

**ChatGPT 这一条最致命**：它说明我"每分支一个 `GraphRunner` 实例 + 共享变量"的方案**本身矛盾**——每个实例都 `new` 了一份副本。

### 2.3 事件归属只能靠 `commandId`，无弱匹配

| 评审 | 提法 |
|---|---|
| Trae | 规范里"声明删除但仍在"（原则 6 / §4.3 / §7.2 / §8 降级表四处保留） |
| MiniMax | 未直接提，但要求"表设计只认 command_id" |
| ChatGPT | **表设计 `:450` 和规范 `:217` 仍写着 `IS NULL OR`** —— 后门还在 |

**裁决：全部清理。** 正确写法：

```sql
AND w.expect_command_id IS NOT NULL
AND e.command_id IS NOT NULL
AND w.expect_command_id = e.command_id
```

**并补一条语义**（ChatGPT 8.4）：**独占串行是调度降级，不提供事件归属**。它只保证"同一时刻只有一个 run 用设备"，不能证明"迟到事件属于当前操作"。

### 2.4 `E-16` 的约束方向对，但表述要精确

| 评审 | 提法 |
|---|---|
| MiniMax | 补两条：分支内不得再有 split/join、join 点不得被切进分支 |
| ChatGPT | 区分三种结构：join 后共同下游（合法）、join 前共享静态节点（歧义）、共享尾部（应归 join 后） |
| Trae | 未提 |

**裁决：采纳 ChatGPT 的表述 + MiniMax 的两条边界。**

> 两个分支在 join **之前**共享同一静态执行节点时，若没有显式的共享节点执行语义和独立执行实例身份，则编译期报错；共同下游节点归 join 之后，不计入交集。

**并且承认**：没有做过全量流程扫描，不能宣称"现有流程兼容"。

### 2.5 多实例：**已关闭，不再讨论**

| 评审 | 提法 |
|---|---|
| Trae | R-11 不是"epoch 还是数据库"二选一；设备锁必须下沉 DB，owner 粒度细到 branch |
| MiniMax | DB 已在拦 run 级，缺口是**粒度**；加 branch 成本极低；epoch 是另一件事 |
| ChatGPT | 两者正交：租约解决互斥，epoch 解决过期执行者；要一起做 |

**三份的技术判断都对，但本轮全部关闭。**

**原因**：本项目**单实例**部署。多实例需要重新设计执行体身份与锁的落点，不是"补个字段"能解决的。把它挂在"遗留"里，只会让下一轮评审再问一次。

**唯一要留的一点**：区分两个 `epoch`——

| 名称 | 解决什么 | 单实例是否需要 |
|---|---|---|
| `hold_epoch`（设备占用代次） | 旧完成事件误清新租约 | **需要** |
| `owner_epoch`（执行体 fencing） | 旧实例写回状态 | **不需要** |

---

## 3. 两份共识、一份补充（4 条）

### 3.1 拆分命令：**场景未确认，暂不实现**

- Trae：应新建 ID
- MiniMax：不能用 generation，要引入 `logical_command` 子表
- ChatGPT：两个新 `commandId` + `parent_operation_id`

**但先要问：这个场景真的会出现吗？**

| 能想到的场景 | 实际是什么 | 需要父子关系吗 |
|---|---|---|
| 设备卡住，人工把板挪到中转位再送目标位 | **原操作已失败**，人工新建两个操作 | **不需要**——原操作作废 |
| 流程设计从"直达"改成"经中转" | **改流程定义**，重新发布 | **不需要**——是不同流程 |
| 一个业务意图要设备执行多条命令 | **已被 step 拆分**（`operation_key` 含 `s{step}`） | **不需要**——每条命令本就是独立 operation |

**三者都不需要 `parent_operation_id`。**

**裁决：暂不实现。**

| 项 | 决定 | 理由 |
|---|---|---|
| `logical_command` 子表 | **不加** | commandId 在重试中不变（已定），一个 operation 只有一个 commandId，一对多不存在 |
| `parent_operation_id` | **不加** | "拆分"本质是"原操作作废 + 新建两个"，不需要保留父子关系 |
| 留一句 | "若将来要支持'人工拆分且要追溯'，再加子表" | 真出现时再加，不预先建 |

**为什么我原来写错了**：我把两家的"假设"当成了"必须"。它们之所以回答，是因为**我的邀请里列了"人工拆成两次"这个问题**——**是我先提出了一个不存在的场景，然后评审者认真回答了它。**

这条修正比设计本身更重要：**提问本身也是设计，问错了会引来一堆正确的答案，指向错误的方向。**

### 3.2 `commandId` 生成公式要覆盖新维度

- MiniMax：`operation_key` 已含 `branch` / `invocation`，但 `commandId` 公式没跟上
- ChatGPT：规范 `:79` 的公式缺 `branch` / `invocation` / `generation`；建议直接 `commandId = operation_id`
- Trae：未提

**裁决：`commandId = operation_id`**（独立持久化的全局唯一 ID），不让各模块各自拼字符串。

### 3.3 恢复核对要扩，但要节点级

- Trae：未提
- MiniMax：未直接提（它谈的是物理态来源）
- ChatGPT：**四项检查 + 节点级前置条件**（加样查针/液面、转运查板位/夹爪、离心查转子/盖锁、孵育查温度/计时）

**裁决：采纳节点级四组检查**（空间安全 / 设备安全 / 工艺准备 / 操作连续性），但**不是每个节点都查全部**。

### 3.4 事实源缺失必须 fail-open

- 三份未直接提，但 MiniMax 引了 `StirrerDispenserGuard` 的事故，ChatGPT 8 问题要求"NULL ≠ unknown"

**裁决：**

| 情况 | 处置 |
|---|---|
| `physical_state IS NULL`（设备不支持） | **fail-open**：跳过 + SKIP 审计 + 告警 + 列入不可并行 |
| `physical_state = 'unknown'`（设备明确说不确定） | fail-closed：阻断 |

---

## 4. 真实分歧（2 处）

### 4.1 分歧一：拆分命令用 generation 还是 logical_command —— **已消解**

| | MiniMax | ChatGPT |
|---|---|---|
| 主张 | 引入 `logical_command` 子表 | 两个新 `commandId` + `parent_operation_id` |
| 共同点 | 都认为要新 ID，都不能用 generation | 同 |

**两家说法都正确，但随着 §3.1 的结论（场景未确认、暂不实现），这条分歧不再需要裁决**——不建表、不加字段，两者都不做。

如果将来真要支持，再在"子表"和"父字段"之间选。

### 4.2 分歧二：15 分钟是"资源等待上限"还是"执行上限"

| | ChatGPT | 我 |
|---|---|---|
| 主张 | 15 分钟可能把 20/30 分钟的正常等待判超时，需要现场数据 | 15 分钟是**资源等待上限**，B 等 A 的 30 分钟孵育时超时转人工是**正确**的 |

**裁决：按"资源等待上限"定义，但表述要写清。**

```
B 申请 A 正在使用的设备
  → B 等 15 分钟
  → B 超时转人工 / 换设备
  → A 的租约不受影响，继续执行
```

**这不是 bug，是设计意图。** 但文档要加一句"不影响在途占用的租约"，否则读起来像会把 A 也停掉。

**不过 ChatGPT 的建议仍有价值**：采集 BUSY 时长分布（p50/p95/p99/max）能帮助定这个值，列 P2 待办。

---

## 5. 单份独有（10 条）

### 5.1 只有 ChatGPT 发现的（4 条）

| # | 发现 | 为什么别人没看到 |
|---|---|---|
| 1 | **`withVariables` 会复制 Map** | 只有它读了方法实现 |
| 2 | **5 处跨文档口径矛盾**（8.1~8.5） | 只有它做了跨文档 grep |
| 3 | **E-16 的三种图结构** | 只有它区分了"join 前"与"join 后" |
| 4 | **外部命令入口（G1/G2/G3 分级）** | 只有它想到"现场软件可能绕过中控" |

### 5.2 只有 MiniMax 发现的（3 条）

| # | 发现 | 价值 |
|---|---|---|
| 1 | **下发路径已收口**（`executeCommand` 唯一，`ShadowProbe` 被只读白名单挡） | **推翻了 Trae 的"旁路"担心** |
| 2 | 两个 15 分钟要分别命名（`DEVICE_BUSY_GIVEUP_MS` / `DEVICE_LOCK_WAIT_MAX_MS`） | 避免混用 |
| 3 | `DEVICE_LOCK_WAIT_MAX_MS = 900_000L` 已存在（`WorkflowService:3422`） | 口径可统一 |

### 5.3 只有 Trae 发现的（3 条）

| # | 发现 | 价值 |
|---|---|---|
| 1 | **三套联动变量清单**（`locallyWrittenVars:126` / `consumedValues:142` / `externallyOwnedVars:156`） | 证明"换 CHM"远远不够 |
| 2 | `writeVar` 先查 `externallyOwnedVars` 再改 `locallyWrittenVars`（`:162-163`） | 三步序列必须收进 store |
| 3 | `refreshVariables`（`:1411`）读三个容器 | 刷新也要原子化 |

---

## 6. 合并后的待办

### P0（不改就不能开工）

| # | 项 | 来源 |
|---|---|---|
| 1 | `device_physical_hold` → 派发前原子租约 + owner/epoch + ACK 超时不释放 | 三份 |
| 2 | 引入 `FlowExecutionContext`，`withVariables` 改为注入共享引用 | ChatGPT + Trae |
| 3 | 清理 5 处口径矛盾（`IS NULL OR` ×2、L-14、commandId 公式、process_id、独占串行） | ChatGPT + Trae |
| 4 | ~~拆分命令用 child 表~~ **暂不实现**（场景未确认，见 §3.1） | MiniMax + ChatGPT |
| 5 | `commandId = operation_id`，不再各自拼字符串 | MiniMax + ChatGPT |
| 6 | 独占串行明确为"调度降级，不提供事件归属" | ChatGPT |

### P1（并行度 2 前必须）

| # | 项 | 来源 |
|---|---|---|
| 7 | E-16 表述改为"join 前共享静态执行节点" + 两条边界 | MiniMax + ChatGPT |
| 8 | 全量流程扫描（枚举含 `SPLIT_PARALLEL` 的流程，算分支交集） | ChatGPT |
| 9 | 节点级四组恢复检查（空间/设备/工艺/操作连续性） | ChatGPT |
| 10 | 设备命令入口分级（G1/G2/G3），G3 不进并行清单 | ChatGPT |
| 11 | 改造点 3 改为分类型清单（换容器 / 改原子操作 / 扩检查范围） | MiniMax |

### P2

| # | 项 | 来源 |
|---|---|---|
| 12 | 采集 BUSY 时长分布（p50/p95/p99/max） | ChatGPT |
| 13 | 两个 15 分钟常量分别命名 | MiniMax |
| 14 | 多实例架构约束（DB 租约 + epoch + branch 粒度）写入演进文档 | 三份 |

---

## 7. 我推翻的 3 条判断

| # | 原判断 | 修正 | 被谁推翻 |
|---|---|---|---|
| 1 | "每分支一个 `GraphRunner` 实例 + 共享变量"可行 | **不可行**，`withVariables` 会复制 | ChatGPT |
| 2 | "hold 在 ACK 后建立"够用 | **不够**，下发→ACK 之间有窗口 | ChatGPT |
| 3 | "拆成两次 = generation +1" | **错**，要用 `logical_command` | MiniMax |

**共同点**：三次都是"看了接口没看实现"或"看了单份没看跨份"。

---

## 8. 待决策（需要业务方定）

| ID | 问题 | 选项 |
|---|---|---|
| D1 | 现场是否有绕过中控向设备发命令的软件 | 有 ⇒ 该设备只能 G3，不进并行清单；无 ⇒ G1 成立 |
| D2 | ~~人工拆分~~ **暂不需要**：现场若不做人工拆分动作，此项取消（§3.1） | — |
| D3 | `physical_state` 由谁写、写什么 | 决定 §4.7 能否落地 |

---

## 9. 边界

- 三份评审均针对 `09b4272`，未读更早版本的结论不适用
- 本文档是汇总，不重复设计细节；细节以四份设计文档为准
- 代码 0 行、表 0 张、测试 0 次
- 并行度 2 不批准；阶段 1 只做准入统一
