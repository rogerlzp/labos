# 设备命令与事件关联规范：commandId 回显

- 日期：2026-11-19
- 性质：**协议与实现规范**，未实施
- 适用范围：中控内核、.NET 侧车、设备驱动、模拟器
- 目的：用**一个字段**解决"这条事件属于哪条命令、哪个 run"的归属问题

---

## 0. 一句话

**中控下发命令时带 `commandId`；设备/侧车返回时原样带回来；中控据此判断事件归属。`funcName`（设备程序名）与 `processId`（实验编排节点）都不是归属依据，只作辅助信息。**

---

## 1. 原则

| # | 原则 | 理由 |
|---|---|---|
| 1 | `commandId` 由**中控生成**，不由设备/侧车生成 | 设备不知道 run/node 概念，无法生成有业务含义的 id |
| 2 | **同一逻辑命令重试时复用同一 `commandId`** | 设备据此去重；换 id 等于新命令 |
| 3 | 设备/侧车**原样回显**，不改写、不加前缀 | 任何改写都会破坏匹配 |
| 4 | 事件若由某条命令触发，必须回填该命令的 `commandId` | 这是唯一的精匹配依据 |
| 5 | **无法判断归属时留空，不得猜测** | 瞎填比留空更危险：会把事件归给错误的 run |
| 6 | 没有 `commandId` 的事件只能弱匹配，且该设备**不得进入并行清单** | 弱匹配在同设备多 waiter 时不可靠 |

---

## 2. 字段定义

### 2.0 术语纠正（v2 新增，必须先读）

现有系统里 `processId` 这个词被用错了地方，导致两个完全不同的概念撞名：

| 概念 | 现在的名字 | 应该叫 | 类型 | 谁生成 |
|---|---|---|---|---|
| **设备程序/功能名**（如离心机的 `P1`、`Centrifuge3000`） | `processId` ❌ | **`funcName`** | VARCHAR(64) | 设备 |
| **实验编排节点 id**（`experiment_process.id`） | 未记录 | **`processId`** | **BIGINT** | 中控（建实验时） |
| 命令实例 id | `commandId` | `commandId` | VARCHAR(160) | 中控 |
| 交互步骤标识 | `interactionStep` | `interactionStep` | VARCHAR(128) | 设备（可选） |

**为什么必须纠正**：

1. `experiment_process.id` 是 `BIGINT`（identity 列），而 `device_event.process_id` 是 `VARCHAR(128)`——**类型都对不上**，说明现有列装的根本不是实验编排节点。
2. `docs/设备事件协议契约.md:57` 原话是"后续精确工位/交互步骤匹配使用"——这描述的是设备侧的过程标识，不是实验编排节点。
3. 两个概念混用一个名字，后面做归属和资源分配时一定会解释错。

### 2.1 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `commandId` | string，≤160 | 下发时必填；回包/事件有则必填 | 命令幂等键与**归属键** |
| `funcName` | string，≤64 | 否 | 设备程序/功能名，用于交叉校验（不是归属依据） |
| `processId` | **number（BIGINT）** | 否 | 实验编排节点 id（`experiment_process.id`），用于把事件关联回编排步骤 |
| `interactionStep` | string，≤128 | 否 | 交互步骤标识（L5 守卫用，与归属无关） |
| `sourceEventId` | string，≤128 | 否 | 设备侧事件原始 id，用于去重（已有） |
| `device` / `deviceCode` | string，≤64 | 是 | 设备码（已有） |
| `eventType` | string，≤48 | 是 | 事件类型（已有） |
| `payload` | object | 否 | 原始载荷（已有） |

**归属只认 `commandId`。** `funcName` 与 `processId` 都是辅助信息：前者用于发现"设备把 A 程序的事件挂到 B 命令上"，后者用于把事件归到实验编排的哪一步。

**`commandId` 的生成规则**（中控侧，已有实现）：

```
r{runId}-n{nodeId}-s{stepSeq}-d{deviceInstanceId}[|l{loopIndex}]
```

- 不含时间戳、不含随机数、不含递增计数器（`exec` 按设备能力分档处理，见《表设计》§1.7）
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
-- ① 设备程序名：现有 process_id 装的就是这个，改名保留数据
ALTER TABLE device_event RENAME COLUMN process_id TO func_name;
COMMENT ON COLUMN device_event.func_name IS
    '设备程序/功能名（如 P1 / Centrifuge3000）。设备侧产生，仅作交叉校验，不作归属依据。';

-- ② 真正的 processId：实验编排节点（experiment_process.id，BIGINT）
ALTER TABLE device_event ADD COLUMN process_id BIGINT;
COMMENT ON COLUMN device_event.process_id IS
    'experiment_process.id，实验编排节点。由中控按 run 的编排关系回填，设备/侧车不填。';

-- ③ 命令归属（唯一归属键）
ALTER TABLE device_event ADD COLUMN command_id VARCHAR(160);
COMMENT ON COLUMN device_event.command_id IS
    '触发该事件的命令 id；由设备/侧车原样回显。NULL 表示无命令关联（自发事件）。';

CREATE INDEX IF NOT EXISTS ix_device_event_cmd
    ON device_event (tenant_id, command_id) WHERE command_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_device_event_process
    ON device_event (tenant_id, process_id) WHERE process_id IS NOT NULL;
```

**注意**：`RENAME COLUMN` 会让现有代码读不到 `process_id`，必须同步改：

| 文件 | 改动 |
|---|---|
| `DeviceEventRow` | 字段 `processId` → `funcName`（String），新增 `processId`（Long） |
| `DeviceEventMapper.xml` | `insertEvent` / `selectEventsSince` 的列名 |
| `DeviceEventController` | 请求体读 `funcName`；`processId` 改为可选的 Long |
| `设备事件协议契约.md` | 字段说明同步（第 45、57 行） |
| 侧车 `Protocol.cs` | 若能把设备程序名提取出来，填 `funcName`；填不了就留空 |

### 4.2 接收入口

`DeviceEventController` 增读 `commandId` 与 `funcName`，`processId` 改为 Long：

```java
String commandId = trimmed(ApiServer.jsonField(body, "commandId"));
String funcName  = trimmed(ApiServer.jsonField(body, "funcName"));   // 原 processId
Long   processId = ApiServer.jsonLong(body, "processId");            // 实验编排节点 id

mapper().insertEvent(TENANT, deviceId, device, eventType, payload,
        runId, sourceEventId, funcName, interactionStep, commandId, processId);
```

### 4.3 认领匹配

`claimEvent` 的候选集增加：

```sql
AND (e.command_id IS NULL
     OR e.command_id = #{expectedCommandId})
```

**语义**：
- 事件带 `commandId` 且与等待者期望一致 → 强匹配
- 事件带 `commandId` 但与等待者期望不一致 → **不匹配**（不得降级为弱匹配，否则会串）
- 事件不带 `commandId` → 只能走弱匹配（见 §7.2）

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

### 7.2 弱匹配（无 commandId）

```
事件无 commandId
  → 仅当该设备 + 该事件类型下**只有一个** WAITING waiter 时允许
  → 多个 waiter 时不匹配，进 UNATTRIBUTED
```

**弱匹配默认关闭**（配置项），因为并行下它是串板的主要来源。

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
| B：只能回 ACK/结果，自发事件不带 | 部分 | 弱匹配（若开启）或独占串行 |
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
| C-24 | 事件无 commandId 且多 waiter | 不匹配，进 `UNATTRIBUTED`（弱匹配关闭时） |
| C-25 | 模拟器不回显 commandId | 归属判定失败，用例必须红（防止测试假绿） |
| C-26 | 设备不支持回显 | 该设备被标注为独占串行，调度器不放行同设备多 run |

---

## 10. 与已废弃字段的关系

| 字段 | 处置 | 说明 |
|---|---|---|
| `process_id`（旧语义 = 设备程序号） | **改名为 `func_name`** | 见 §2.0 / §4.1 |
| `process_id`（新语义 = 实验编排节点） | **重新定义为 BIGINT** | 对应 `experiment_process.id` |
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
