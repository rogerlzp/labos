# 设备命令与事件关联规范：commandId 回显

- 日期：2026-11-19
- 性质：**协议与实现规范**，未实施
- 适用范围：中控内核、.NET 侧车、设备驱动、模拟器
- 目的：用**一个字段**解决"这条事件属于哪条命令、哪个 run"的归属问题

---

## 0. 一句话

**中控下发命令时带 `commandId`；设备/侧车返回时原样带回来；中控据此判断事件归属。归属只认 `commandId`，不引入 `funcName` / `processId` / 弱匹配。**

---

## 1. 原则

| # | 原则 | 理由 |
|---|---|---|
| 1 | `commandId` 由**中控生成**，不由设备/侧车生成 | 设备不知道 run/node 概念，无法生成有业务含义的 id |
| 2 | **`commandId` = 一次逻辑操作的唯一标识**：同一逻辑操作的所有重试（网络重发 / 断线重连 / resume）**复用同一个 id**；**人工重跑 / 循环下一轮 / 换批次**属于新逻辑操作，生成**新 id** | "重试"与"重新执行"是两件事，混在一起会让设备重复动作或拒绝正常重放 |
| 2b | **不支持设备去重的设备，不允许自动重试**（重试必然重复执行）；但 `commandId` 仍然**保持稳定**，不以"每次换新 id"来规避 | 换 id 只会让设备当成新命令，问题更严重 |
| 3 | 设备/侧车**原样回显**，不改写、不加前缀 | 任何改写都会破坏匹配 |
| 4 | 事件若由某条命令触发，必须回填该命令的 `commandId` | 这是唯一的精匹配依据 |
| 5 | **无法判断归属时留空，不得猜测** | 瞎填比留空更危险：会把事件归给错误的 run |
| 6 | **没有 `commandId` 的事件不归属任何 run**（进 `UNATTRIBUTED`）；不回显 `commandId` 的设备降级为**独占串行**，且不得进入并行清单 | **不设弱匹配**——它既在"同设备多 waiter"时不可靠，又是迟到事件的接盘者 |

---

## 2. 字段定义

### 2.0 术语：只有 `method` 和 `commandId`

**中控、模拟器、侧车三边都是 `method`，没有第二个名字。**

| 概念 | 字段 | 实例 | 谁产生 |
|---|---|---|---|
| 设备方法名 | **`method`** | `Centrifuge`、`Seal`、`SetProductName` | 中控（从 `device_method.method` 取） |
| 命令实例 id | **`commandId`** | `r100-n10-s1-d5` | 中控 |
| 设备侧事件原始 id | `sourceEventId` | 设备帧里的序号 | 设备（可选） |

**明确不引入的字段**（v3 修正）：

| 字段 | 为什么不要 |
|---|---|
| `funcName` | 就是 `method` 的别名，重复 |
| `processId`（事件表） | 事件归属不需要它。原列是 `VARCHAR(128)`，语义从未定义 |

### 2.1 事件只有两类

| 类型 | 例子 | 带 commandId | 归属 |
|---|---|---|---|
| **命令关联型** | `waiting_interaction`（到交换点）、`extract_done`、进度上报 | **必须** | 归到该命令所属的 run / node / branch |
| **系统型** | 心跳、设备自检告警、人工开门 | 没有 | **不归属任何 run**，只做设备状态更新 |

**关键**：原先我把"设备自发事件"单独列为第三类（E3），认为它没有命令关联——这是错的。"到交换点"本身就是某条 `Transfer` 命令执行到一半的结果，设备应当把该命令的 `commandId` 带回来。

**所以不需要**：`process_id` 精匹配、设备占用者归属、弱匹配。归属链只有一条。

### 2.2 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `commandId` | string，≤160 | 命令关联型必填；系统型不填 | 唯一归属键 |
| `method` | string，≤64 | **否（可选交叉校验）** | 触发该事件的设备方法名。**归属不依赖它**，只用于发现"设备把方法 A 的事件挂到了方法 B 的命令上" |
| `sourceEventId` | string，≤128 | 否 | 设备侧事件原始 id，用于去重（已有） |
| `device` / `deviceCode` | string，≤64 | 是 | 设备码（已有） |
| `eventType` | string，≤48 | 是 | 事件类型（已有） |
| `payload` | object | 否 | 原始载荷（已有） |

**归属只认 `commandId`。** `method` 用于交叉校验（确认这条事件确实来自预期的方法），不作归属依据。

**`commandId` 的生成规则**（中控侧，已有实现）：

```
r{runId}-n{nodeId}-s{stepSeq}-d{deviceInstanceId}[|l{loopIndex}]
```

- **不含时间戳、不含随机数、不含递增计数器**（`exec` 不进 `commandId`，只保留在日志与追踪里，见《表设计》§1.7）
- 首次生成后持久化，重放时读取，不重新拼接

---

## 3. 报文规范

### 3.1 中控 → 侧车（下发命令）

```json
{
  "commandId": "r100-n10-s1-d5",
  "deviceInstanceId": 214,
  "deviceCode": "P_AUTO-3",
  "method": "Transfer",
  "params": { "from": "A", "to": "B" },
  "timeoutMs": 15000,
  "enqueuedAt": "2026-11-19T10:00:00Z"
}
```

**已实现**：`DriverCommand` 的 `commandId` 必填（构造时校验非空）。

### 3.2 侧车 → 中控（ACK，收到即回）

```json
{
  "commandId": "r100-n10-s1-d5",
  "type": "ack",
  "acceptedAt": "2026-11-19T10:00:01Z"
}
```

**要求**：ACK 与"执行完成"必须是两条独立消息。ACK 只表示"我收到了"，不表示"做完了"。

### 3.3 侧车 → 中控（执行结果）

```json
{
  "commandId": "r100-n10-s1-d5",
  "type": "result",
  "ok": true,
  "finishedAt": "2026-11-19T10:00:15Z",
  "raw": { "deviceResponse": "..." }
}
```

**已实现**：`DriverResult.commandId` 必填。

### 3.4 侧车 → 中控（自发事件，由某条命令触发）

```json
{
  "commandId": "r100-n10-s1-d5",
  "device": "P_AUTO-3",
  "eventType": "waiting_interaction",
  "sourceEventId": "dev-7f3a-001",
  "payload": { "station": "exchange" }
}
```

**判定标准**：该事件是某条命令的**直接后果**（命令让设备启动内部流程，流程完成后上报）⇒ 必须回填那条命令的 `commandId`。

### 3.5 侧车 → 中控（自发事件，无对应命令）

```json
{
  "device": "P_AUTO-3",
  "eventType": "door_opened",
  "sourceEventId": "dev-7f3a-002",
  "payload": {}
}
```

**无 `commandId`** ⇒ 中控按"无归属信息"处理（见 §7.3）。

### 3.6 侧车 → 中控（状态查询响应）

```json
{
  "commandId": "r100-n10-s1-d5",
  "state": "EXECUTING",
  "detail": "..."
}
```

查询响应必须带 `commandId`，否则对账时无法确定查的是哪条命令。

---

## 4. 中控侧要求

### 4.1 数据表

```sql
-- ① 删除语义未定义的列（生产路径从不填值）
ALTER TABLE device_event DROP COLUMN IF EXISTS process_id;

-- ② 命令归属（唯一归属键）
ALTER TABLE device_event ADD COLUMN command_id VARCHAR(160);
COMMENT ON COLUMN device_event.command_id IS
    '触发该事件的命令 id；由设备/侧车原样回显。NULL 表示系统型事件（心跳/告警/人工）。';

-- ③ 方法名（交叉校验用，与下发时的 method 比对）
ALTER TABLE device_event ADD COLUMN method VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_device_event_cmd
    ON device_event (tenant_id, command_id) WHERE command_id IS NOT NULL;
```

**注意**：`DROP COLUMN process_id` 会让现有代码读不到该字段，必须同步改：

| 文件 | 改动 |
|---|---|
| `DeviceEventRow` | 删 `processId` 字段；新增 `commandId`、`method` |
| `DeviceEventMapper.xml` | `insertEvent` / `selectEventsSince` 的列清单 |
| `DeviceEventController` | 不再读 `processId`；改读 `commandId` 与 `method` |
| `设备事件协议契约.md` | 删除 `processId` 条目（第 45、57 行） |
| 侧车 `Protocol.cs` | 事件帧带 `commandId`（见 §3.4），不填 `processId` |
| 模拟器 `DeviceCore` | 自发事件回填触发它的 `commandId` |

### 4.2 接收入口

`DeviceEventController` 增读 `commandId` 与 `method`，不再读 `processId`：

```java
String commandId = trimmed(ApiServer.jsonField(body, "commandId"));
String method    = trimmed(ApiServer.jsonField(body, "method"));

mapper().insertEvent(TENANT, deviceId, device, eventType, payload,
        runId, sourceEventId, commandId, method);
```

### 4.3 认领匹配

`claimEvent` 的候选集增加：

```sql
AND (e.command_id IS NULL
     OR e.command_id = #{expectedCommandId})
```

**语义**：
- 事件带 `commandId` 且与等待者期望一致 → 强匹配
- 事件带 `commandId` 但与等待者期望不一致 → **不匹配**
- 事件不带 `commandId` → **不匹配**，进 `UNATTRIBUTED`（见 §7.2，无弱匹配）

### 4.4 waiter 增加期望字段

```sql
ALTER TABLE device_event_waiter
    ADD COLUMN IF NOT EXISTS expect_command_id VARCHAR(160);
```

由等待节点声明：如果该事件应该由某条命令触发，就写入该命令的 `commandId`。

---

## 5. 侧车（.NET）要求

| # | 要求 | 说明 |
|---|---|---|
| 5.1 | 收到下发时**缓存** `commandId` ↔ 设备/会话 | 用于后续回填 |
| 5.2 | ACK、结果、查询响应**必须回显** `commandId` | 从缓存读取，不重新生成 |
| 5.3 | 自发事件若能确定由某条命令触发，**回填** `commandId` | 判定依据：设备协议回包里带该命令的标识，或侧车能按设备会话唯一确定 |
| 5.4 | 无法确定时**留空**，不得填最近一条命令的 id | 猜测会造成串板 |
| 5.5 | 设备协议不支持回显时，在**能力清单**里登记 | 见 §8 |
| 5.6 | 不修改 `commandId` 的大小写与前缀 | 原样透传 |

**侧车缓存的生命周期**：

```
下发命令 → 缓存 (commandId, deviceCode, 发起时间)
收到结果/超时 → 保留 5 分钟（供迟到事件回填）→ 清理
```

---

## 6. 模拟器要求

模拟器是**规范的一部分**，不是测试辅助。

| # | 要求 |
|---|---|
| 6.1 | `VirtualDriver` / `DeviceCore` 接收命令时必须保存 `commandId` |
| 6.2 | ACK / 结果 / 查询响应必须回显同一个 `commandId` |
| 6.3 | 自发事件如果是对某条命令的响应（如 `waiting_interaction`），必须回填该命令的 `commandId` |
| 6.4 | 提供"不回显"的故障注入开关，用于验证中控能识别并降级 |
| 6.5 | 测试用例必须包含：模拟器不回显 `commandId` 时，归属判定失败并进 `UNATTRIBUTED` |

**为什么必须写进规范**：如果模拟器"发出即算收到"、"回包不带 id"，测试会全绿，现场会全错。

---

## 7. 归属规则

### 7.1 强匹配（有 commandId）

```
事件.commandId == waiter.expect_command_id
  → 强匹配，唯一确定
```

多个 waiter 声明了同一个 `expect_command_id`（不应发生）⇒ 全部不匹配，告警。

### 7.2 无 commandId 的事件（**不存在弱匹配**）

```
事件无 commandId
  → 不匹配任何 waiter，进 UNATTRIBUTED + 告警
  → 即使该设备下只有一个 WAITING waiter，也不认领
```

**为什么"唯一 waiter"不能作为判据**：

| 风险 | 说明 |
|---|---|
| 迟到事件接盘 | A 释放设备后 B 开始等，一个属于 A 的迟到事件到达 ⇒ 唯一 waiter 是 B ⇒ 推进错误流程 |
| 唯一 waiter ≠ 唯一来源 | 只有一个 waiter 只说明"当前只有一个人在等"，不能说明"这条事件属于他" |

**没有开关，不允许开启。** 需要事件推进流程的设备，必须回显 `commandId`；做不到的设备降级为独占串行（见 §8）。

### 7.3 无匹配

```
事件有 commandId 但无 waiter 声明该 id
  → 进 UNATTRIBUTED，告警
  → 可能是迟到事件（对应 waiter 已过期）或旧命令的残留事件
```

---

## 8. 降级规则

| 设备能力 | 事件是否带 commandId | 允许的调度模式 |
|---|---|---|
| A：完整回显 | 是 | 可并行 |
| B：只能回 ACK/结果，自发事件不带 | 部分 | **独占串行**（无弱匹配） |
| C：完全不回显 | 否 | **独占串行**，且不得进入并行清单 |

**降级不能静默**：设备能力必须在设备注册信息里标注，调度器据此决定是否允许同设备多 run。

---

## 9. 验收

| ID | 场景 | 期望 |
|---|---|---|
| C-18 | 中控下发命令 | `DriverCommand.commandId` 非空且稳定 |
| C-19 | 侧车回 ACK | 带同一个 `commandId` |
| C-20 | 侧车回结果 | 带同一个 `commandId` |
| C-21 | 设备自发事件（命令触发） | 带该命令的 `commandId` |
| C-22 | 两批等同一设备同类事件 | 按 `commandId` 精确区分，不串 |
| C-23 | 事件带 commandId 但无 waiter 匹配 | 进 `UNATTRIBUTED`，告警，不推进任何流程 |
| C-24 | 事件无 `commandId`（无论几个 waiter） | **一律不匹配**，进 `UNATTRIBUTED`；不存在弱匹配 |
| C-25 | 模拟器不回显 commandId | 归属判定失败，用例必须红（防止测试假绿） |
| C-26 | 设备不支持回显 | 该设备被标注为独占串行，调度器不放行同设备多 run |

---

## 10. 与已废弃字段的关系

| 字段 | 处置 | 说明 |
|---|---|---|
| `process_id` | **删除** | 语义从未定义，生产路径不填值；归属不需要它 |
| `interaction_step` | 保留 | 供 L5 守卫与工位匹配，与归属无关 |
| `interactionId` | 保留现状 | 服务于"不等事件直接搬"的前置检查 |
| `run_id` | 保留，侧车可选补填 | 不作为归属判据，见另一份设计 |

**明确不做**：不引入"过程标识"作为第二套归属键。一套 `commandId` 足够，两套会打架。

---

## 11. 迁移顺序

| 步 | 做什么 | 影响 |
|---|---|---|
| 1 | `device_event` 加 `command_id` 列 + 索引 | 只新增，不影响现有 |
| 2 | `DeviceEventController` 接受并存储 `commandId` | 向后兼容（不传则为 null） |
| 3 | 侧车实现 ACK/结果/事件回显 | 需侧车发版 |
| 4 | 模拟器实现同样行为 | 可与 3 并行 |
| 5 | `claimEvent` 加精匹配 | 需 3、4 完成后开启，否则全部匹配失败 |
| 6 | 观察期：统计事件带 `commandId` 的比例 | 比例低说明侧车没落实，不得开并行 |
| 7 | 按设备能力标注，更新并行清单 | 最后一步 |

**第 5 步必须在 3、4 之后**：先加过滤会把所有事件挡掉，等于瘫痪。

---

## 12. 一句话边界

这份规范**不改**命令的语义、不改事件表结构的主体、不引入新的状态机。它只做一件事：**让每条事件都能回答"我是哪条命令的结果"**。

没有这一条，并行的事件归属就只能靠猜。
