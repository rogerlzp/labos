# 设备网关与 Redis 状态中心 — 设计

> 面向问题：**设备连接应该独立成一个服务；状态放 Redis；中控只读状态、只下命令。**

---

## 一、为什么拆

### 现在的形态

```
中控进程
  ├ 流程编排
  ├ 执行调度
  └ TCP 长连接 ── 直连设备
```

**问题**：

| 问题 | 后果 |
|---|---|
| 中控重启 | 连接断，正在等 30 分钟的孵育结果丢失 |
| 多中控实例 | 每个实例各建一条连接，设备侧看到多个客户端 |
| 状态不可见 | 想知道"机械臂现在在哪"，只能查执行记录推断 |
| 调试要连生产 | 想单点验证设备，得启动整套中控 |

### 拆之后的形态

```
┌─────────────┐        ┌──────────────┐        ┌─────────────┐
│ 中控（Java）  │        │  Redis       │        │ 设备网关      │
│  编排/调度    │◄──────►│  状态中心     │◄──────►│  常驻长连接   │
└─────────────┘        └──────────────┘        └──────┬──────┘
                                                       │ TCP
                                                       ▼
                                                    下位机
```

**各自职责**：

| 组件 | 职责 | 不做 |
|---|---|---|
| **中控** | 编排、调度、告警、审计 | 不碰 socket |
| **Redis** | 命令队列、结果、设备状态 | 不存业务数据 |
| **网关** | 长连接、协议解析、重连、状态上报 | 不判断业务语义 |

---

## 二、Redis 数据结构

### 2.1 设备状态（读多写多）

```
Key    labos:dev:state:{deviceInstanceId}
Type   Hash
TTL    无（网关负责维护，设备下线时删）

字段：
  online        1/0           是否连着
  status        idle/running/error
  method        当前执行的方法（idle 时为空）
  commandId     当前命令 id
  startedAt     开始时间（epoch ms）
  lastResult    上次结果的 jsre 片段
  lastError     上次错误码
  updatedAt     最后更新时间
```

**为什么用 Hash 不用 String**：要单独更新某个字段（比如只改 `status`），
Hash 的 `HSET` 是 O(1)，String 要读出整个 JSON 再写回。

### 2.2 命令队列（每台驱动一条）

```
Key    labos:cmd:queue:{driverCode}
Type   List
方向   中控 LPUSH → 网关 BRPOP

元素：{"commandId":"...","deviceInstanceId":214,"method":"Home",
       "params":{...},"timeoutMs":15000,"enqueuedAt":1699...}
```

**为什么每台驱动一条队列**：一台驱动一条长连接，命令必须串行下发。
多台驱动之间可以并行——不同队列互不影响。

**为什么用 `BRPOP`**：阻塞式取，没命令时不空转。

### 2.3 命令结果（短 TTL）

```
Key    labos:cmd:result:{commandId}
Type   String（JSON）
TTL    300 秒

内容：{"commandId":"...","ok":true,"errorCode":null,
       "raw":"{...}","elapsedMs":3200,"finishedAt":1699...}
```

**TTL 300 秒**：结果只用于"命令发出后等回执"，过了 5 分钟没人取就是没人要了。
不用 Pub/Sub 的原因是：**Pub/Sub 是"发完就丢"，订阅方不在线就永久错过**；
用 key + TTL 可以让订阅方晚到也能取到。

### 2.4 在途命令（防超时悬挂）

```
Key    labos:cmd:pending:{driverCode}
Type   Hash  field=commandId  value=enqueuedAt
```

**作用**：网关崩溃重启后，扫这个 Hash 知道哪些命令发过但没结果，
可以标记失败或重发（按幂等键）。

### 2.5 在线设备（快速统计）

```
Key    labos:dev:online
Type   Set   member=deviceInstanceId
```

**作用**：`SCARD` 一行拿到在线数，不用扫所有 state key。

### 2.6 状态变更通知（可选）

```
Channel  labos:events
消息     {"type":"device","id":214,"status":"running","method":"Incubate"}
```

**用 Pub/Sub 是因为状态变化是"事件"**——错过就错过，可接受（前端会轮询兜底）。
和命令结果不同，命令结果不能丢。

---

## 三、命令流转时序

```
① 中控发命令
   LPUSH labos:cmd:queue:{driver}  {commandId, device, method, params, timeout}
   HSET  labos:cmd:pending:{driver} {commandId} {now}

② 网关取命令
   BRPOP labos:cmd:queue:{driver}  → 拿到命令
   发到 TCP 长连接

③ 设备回包
   网关收到 → 解析
   SET   labos:cmd:result:{commandId} {ok, raw, elapsedMs} EX 300
   HDEL  labos:cmd:pending:{driver} {commandId}
   HSET  labos:dev:state:{device} status=idle updatedAt=...

④ 中控等结果
   轮询 GET labos:cmd:result:{commandId}（间隔 50ms，最多 timeout）
   拿到就删 key，返回
```

**为什么是轮询而不是订阅**：

| 方案 | 优点 | 缺点 |
|---|---|---|
| 轮询 `GET` | 简单、结果不丢、晚到也能取 | 50ms 一次，几十条命令时有开销 |
| `BLPOP` 结果队列 | 无轮询 | 每命令一条 key，结果顺序不保证 |
| Pub/Sub | 实时 | **离线就丢** |

**选轮询**：命令是"发一条等一条"，同一时刻在途的通常只有几条，50ms 轮询开销可忽略。
**结果不丢**这点比省几次 GET 重要得多。

---

## 四、网关的职责边界

### 做什么

| 职责 | 说明 |
|---|---|
| **维护长连接** | 每台驱动一条 TCP，断线自动重连（指数退避） |
| **协议分帧** | 按 `#!LABSEP!#` 切，处理半包 |
| **命令串行** | 一条连接同时只跑一条命令（设备是物理的） |
| **超时控制** | 按命令的 `timeoutMs`，超时写失败结果 |
| **状态上报** | 执行前 `status=running`，完成后 `status=idle` |
| **心跳** | 定期 `PING`，连续失败标记 `online=0` |

### 不做什么

| 不做的 | 为什么 |
|---|---|
| **不判断业务语义** | "这块板该不该搬"是中控的事，网关只管"搬这个动作能不能执行" |
| **不重试** | 重试要用同一个 `commandId`，只有中控知道"这条能不能重试" |
| **不存历史** | 网关重启后历史就该没了，历史在 `run_command` 表里 |
| **不做权限** | 谁能发命令是中控的事 |

**这条边界很重要**：网关越聪明，出问题越难定位。
**网关只做"把字节变成命令、把回包变成结果"**。

---

## 五、中控侧改造

### 5.1 驱动抽象不变

```java
public interface DeviceDriver {
    DriverResult execute(DriverCommand cmd);
}
```

**新增一个实现**：

```java
public final class RedisQueueDriver implements DeviceDriver {
    public DriverResult execute(DriverCommand cmd) {
        // 1. LPUSH 命令到队列
        // 2. 轮询 labos:cmd:result:{cmd.commandId()}
        // 3. 拿到就返回
    }
}
```

**为什么还要保持这个接口**：`DriverStepExecutor`、`ShadowProbe` 全都面向
`DeviceDriver` 编程，换实现不用改上层。

### 5.2 配置切换

```bash
LABOS_DRIVER_MODE=redis     # 走网关
LABOS_DRIVER_MODE=persistent # 走 Java 内长连接（保留，单机调试用）
LABOS_DRIVER_MODE=（空）     # 走短连接（默认，兼容现状）
```

### 5.3 前端能看到什么

**新增接口** `GET /api/devices/live`：直接读 Redis 的 `labos:dev:state:*`

```json
{
  "items": [
    {"deviceInstanceId": 214, "online": true, "status": "running",
     "method": "Transfer", "since": 1699000000000}
  ]
}
```

**这比现在好**：现在要看"设备在干什么"只能查 `run_node` 推断，
而且流程跑完就查不到了。Redis 里的状态是**实时的**。

---

## 六、部署形态

### 最小部署（单机）

```
中控 + 网关 + Redis 在同一台机器
```

### 生产部署（推荐）

```
┌──────────────┐   ┌──────────────┐
│ 中控 1        │   │ 中控 2        │  ← 多实例
└──────┬───────┘   └──────┬───────┘
       │                  │
       └────────┬─────────┘
                ▼
         ┌──────────────┐
         │ Redis（主从）  │
         └──────┬───────┘
                │
         ┌──────▼───────┐
         │ 设备网关       │  ← 可以多实例，每实例管几个驱动
         └──────┬───────┘
                │ TCP
         ┌──────▼───────┐
         │ 下位机         │
         └──────────────┘
```

**网关多实例怎么分**：按 `driverCode` 取模或配置指定。
**同一台驱动只能有一个网关实例连**——否则两个客户端抢同一个 TCP 连接。

---

## 七、关键风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Redis 单点** | 挂了就发不出命令 | 主从 + 哨兵；中控侧有重连 |
| **网关崩溃** | 在途命令丢失 | `labos:cmd:pending` 记录在途；重启后按幂等键重发或标记失败 |
| **消息顺序** | 队列是 FIFO 但多消费者会乱 | **同一驱动的队列只允许一个消费者** |
| **命令重复** | 中控重试导致搬两次 | `commandId` 幂等键，网关侧去重（最近 N 条） |
| **Redis 内存** | 结果 key 堆积 | 全部设 TTL，`pending` 定期清理 |

**最危险的是"命令重复"**：机械臂把同一块板搬两次 = 撞机。
幂等键必须贯穿：中控重试复用 `commandId` → 网关按 `commandId` 去重。

---

## 八、实施顺序

| 期 | 做什么 | 产出 |
|---|---|---|
| **P0** | Redis 客户端 + 数据结构落地 | 能读写上述 key |
| **P1** | 网关骨架（连 Redis + 一条长连接） | 单台设备跑通 Home |
| **P2** | 中控 `RedisQueueDriver` | 中控通过网关发命令 |
| **P3** | 状态接口 `/api/devices/live` | 前端看到实时状态 |
| **P4** | 多驱动、多网关实例 | 全岛覆盖 |
| **P5** | 网关崩溃恢复 | 在途命令不悬空 |

**P0-P2 就可以替换掉现有的直连**，P3 之后才有"看得见"的收益。

---

## 九、和现有能力的关系

| 现有 | 拆之后 |
|---|---|
| `TcpDriverAdapter`（短连接） | 保留，单机调试用 |
| `TcpPersistentDriver`（长连接） | 逻辑挪进网关 |
| `ShadowProbe` | 不变，底层换成 `RedisQueueDriver` |
| `ExperimentFlowRunner` | 不变 |
| `device_state` 表 | 保留（持久化），Redis 是实时态，两者互补 |

**`device_state` 和 Redis 不是二选一**：

- **Redis**：实时、可丢、多实例共享
- **`device_state`**：持久、重启后还在、给"板子在哪"这种跨天判断用

**双写**：网关完成命令后写 Redis（实时），中控消费结果后写 `device_state`（持久）。
