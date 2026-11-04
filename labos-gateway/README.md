# labos-gateway · 设备网关

实验室中控的**独立设备连接服务**：维持长连接、收发命令、把状态同步到 Redis。

---

## 为什么独立成服务

| 问题 | 直连（中控内置长连接） | 独立网关 |
|---|---|---|
| 中控重启 | 连接断，正在等的孵育结果丢失 | 连接不断，中控重连即可 |
| 多中控实例 | 每实例一条连接，设备侧看到多个客户端 | 共享网关的连接 |
| 状态可见 | 想知道"机械臂在哪"只能查执行记录推断 | Redis 里实时可读 |
| 调试 | 要单点验证设备得启动整套中控 | `redis-cli` 塞一条命令就能试 |

---

## 职责边界（很重要）

**做什么**：

- 为每条驱动维持一条 TCP 长连接
- 从 Redis 队列取命令，发到设备
- 按 `message_id` 配对回包
- 把结果和设备状态写回 Redis
- 断线重连

**不做什么**：

| 不做 | 为什么 |
|---|---|
| 不判断业务语义 | "这块板该不该搬"是中控的事 |
| 不重试 | 重试要用同一个 `commandId`，只有中控知道能不能重试 |
| 不存历史 | 历史在 `run_command` 表里 |
| 不做权限 | 谁能发命令是中控的事 |

**网关越聪明，出问题越难定位**。它只做"把字节变成命令、把回包变成结果"。

---

## Redis 结构

### 命令队列（中控 → 网关）

```
Key    labos:cmd:queue:{driverCode}
Type   List
方向   中控 LPUSH → 网关 RPOP

元素：
{
  "commandId": "gw-test-001",
  "deviceInstanceId": 214,
  "method": "Home",
  "params": {"plate_id": "PL-001"},
  "timeoutMs": 15000
}
```

**每台驱动一条队列**：一台驱动一条连接，命令必须串行。不同驱动之间互不影响。

### 命令结果（网关 → 中控）

```
Key    labos:cmd:result:{commandId}
Type   String + TTL 300s

{
  "commandId": "gw-test-001",
  "ok": true,
  "errorCode": null,
  "error": null,
  "elapsedMs": 0,
  "raw": "{\"message_id\":\"...\",\"method_status\":2,...}"
}
```

**为什么用 key+TTL 不用 Pub/Sub**：Pub/Sub 订阅方不在线就**永久错过**，
key+TTL 让晚到的消费者也能取到。**命令结果不能丢**。

### 设备状态

```
Key    labos:dev:state:{deviceInstanceId}
Type   Hash

status      idle / running / error
method      当前执行的方法
commandId   当前命令 id
updatedAt   最后更新时间（epoch ms）
```

### 在途命令（防悬挂）

```
Key    labos:cmd:pending:{driverCode}
Type   Hash   field=commandId  value=入队时间
```

网关崩溃重启后扫这个 Hash，知道哪些命令发过但没结果。

---

## 运行

### 编译

```bash
cd labos-gateway
javac -encoding UTF-8 -d out $(find src -name '*.java')
```

或者用 Maven：

```bash
mvn -q package
```

### 启动

```bash
LABOS_REDIS_HOST=192.0.2.1 \
LABOS_GATEWAY_DRIVERS="DRV-A=tcp://192.0.2.10:8501,DRV-B=tcp://192.0.2.11:8501" \
java -cp out com.labos.gateway.GatewayMain
```

**环境变量**：

| 变量 | 默认 | 说明 |
|---|---|---|
| `LABOS_REDIS_HOST` | 127.0.0.1 | |
| `LABOS_REDIS_PORT` | 6379 | |
| `LABOS_REDIS_PASSWORD` | 无 | |
| `LABOS_REDIS_DB` | 0 | |
| `LABOS_GATEWAY_DRIVERS` | 必填 | `code=tcp://host:port` 逗号分隔 |
| `LABOS_RESULT_TTL_SEC` | 300 | 命令结果保留时长 |

### 启动日志

```
=== 实验室中控 · 设备网关 v0.1 ===
Redis 已连接
已启动消费者：DRV-LOCAL-MOCK → tcp://127.0.0.1:19301
[GW:DRV-LOCAL-MOCK] 已连接 127.0.0.1:19301
— 12:34:56 —
  DRV-LOCAL-MOCK connected=true pending=0 sent=3 failed=0 reconnects=0
```

---

## 端到端测试

**不需要中控**，直接用 `redis-cli` 验证：

```bash
# 1. 塞一条命令
redis-cli -h 192.0.2.1 LPUSH labos:cmd:queue:DRV-LOCAL-MOCK \
  '{"commandId":"t1","deviceInstanceId":214,"method":"Home","params":{},"timeoutMs":15000}'

# 2. 等 2 秒，取结果
redis-cli -h 192.0.2.1 GET labos:cmd:result:t1
→ {"commandId":"t1","ok":true,...,"raw":"{\"method_status\":2,...}"}

# 3. 看设备状态
redis-cli -h 192.0.2.1 HGETALL labos:dev:state:214
→ status=idle, method=Home, commandId=t1
```

**这是网关最有价值的特性**：调试设备不需要启动中控。

---

## 与参考实现的关系


**相同的地方**：

- 长连接 + 按 `message_id` 配对
- Redis 存状态（`RMap` vs 这里的 Hash）
- 队列串行消费

**不同的地方**：

| 点 | labos-gateway | 为什么 |
|---|---|---|
| 框架 | 裸 JDK | 网关是管道，不需要 DI/AOP；启动 <1 秒 |
| 网络 | 原生 Socket | 一条连接一个线程够用；Netty 的价值在高并发短连接 |
| Redis | 手写 RESP2（256 行） | 只为几个命令引 Redisson 会带 Netty + Reactor |
| 串行 | 一驱动一消费线程 | 一台驱动只允许一个网关实例连，不需要分布式锁 |

**能更好在哪**：依赖少（一个 jar 丢过去就能跑）、启动快（现场重启不心疼）、
代码短（三个类看完就懂网关在干什么）。

---

## 实现要点

### 三个必须处理的点

| 点 | 不处理会怎样 | 做法 |
|---|---|---|
| **重连** | 一次网络抖动就永久失联 | 读线程退出 → 清空 pending → 下次 execute 重连 |
| **写串行** | 多线程同时写 socket 会串包 | `synchronized (writeLock)` |
| **清理悬空** | 超时后设备才回，往没人等的 future 里塞 | `pending.remove(id)` |

### 分帧

```
下行/上行都按 "#!LABSEP!#" 分隔
TCP 是流，不能假设一次 read 一条
```

### 状态机

```
收到命令  → status=running, method=X
执行成功  → status=idle
执行失败  → status=error
超时      → status=error
连接断开  → 所有 pending 标失败
```

---

## 下一步

| 期 | 内容 | 状态 |
|---|---|---|
| P0 | Redis 结构 + 客户端 | ✅ |
| **P1** | **网关骨架（本文档描述的）** | ✅ |
| P2 | 中控侧 `RedisQueueDriver` | ⬜ |
| P3 | `/api/devices/live` 实时状态接口 | ⬜ |
| P4 | 多驱动、多网关实例 | ⬜ |
| P5 | 网关崩溃恢复（扫 pending） | ⬜ |
