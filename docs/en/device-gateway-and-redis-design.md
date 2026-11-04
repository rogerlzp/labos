# Device Gateway and Redis State Centre — Design

> **English edition.** The Chinese original is [`docs/设备网关与Redis状态中心-设计.md`](../设备网关与Redis状态中心-设计.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> The problem it faces: **the device connection should become a service of its own; the
> state goes into Redis; the control console only reads the state and only sends
> commands.**

---

## 1. Why split it up

### The shape today

```
Control-console process
  ├ Workflow orchestration
  ├ Execution scheduling
  └ TCP long connection ── connects straight to the device
```

**The problems**:

| Problem | Consequence |
|---|---|
| The control console restarts | The connection drops, the incubation result that has been waiting for 30 minutes is lost |
| Several control-console instances | Each instance opens its own connection, the device side sees several clients |
| The state is invisible | To know "where the robot arm is right now" you can only query the execution records and infer it |
| Debugging requires production access | To verify a single device you have to start the whole control console |

### The shape after the split

```
┌────────────────────────────┐        ┌────────────────────────────┐        ┌────────────────────────────┐
│ Control console (Java)     │ ◄──────► │ Redis                      │ ◄──────► │ Device gateway             │
│ orchestration / scheduling │        │ state centre               │        │ permanent long connections │
└────────────────────────────┘        └────────────────────────────┘        └─────────────┬──────────────┘
                                                                                   │ TCP
                                                                                   ▼
                                                                           The field device
```

**The individual responsibilities**:

| Component | Responsibility | What it does not do |
|---|---|---|
| **Control console** | Orchestration, scheduling, alerting, auditing | It never touches a socket |
| **Redis** | Command queue, results, device state | It does not store business data |
| **Gateway** | Long connections, protocol parsing, reconnection, state reporting | It does not judge the business semantics |

---

## 2. The Redis data structures

### 2.1 The device state (more reads than writes)

```
Key    labos:dev:state:{deviceInstanceId}
Type   Hash
TTL    none (the gateway maintains it, it is deleted when the device goes offline)

Fields:
  online        1/0           whether it is connected
  status        idle/running/error
  method        the method currently being executed (empty when idle)
  commandId     the current command id
  startedAt     the start time (epoch ms)
  lastResult    the jsre fragment of the previous result
  lastError     the previous error code
  updatedAt     the last update time
```

**Why a Hash and not a String**: a single field has to be updatable on its own (for
example changing only `status`), the `HSET` of a Hash is O(1), whereas a String has to be read
out as a whole JSON and written back.

### 2.2 The command queue (one per driver)

```
Key    labos:cmd:queue:{driverCode}
Type   List
Direction   control console LPUSH → gateway BRPOP

Element: {"commandId":"...","deviceInstanceId":214,"method":"Home",
       "params":{...},"timeoutMs":15000,"enqueuedAt":1699...}
```

**Why one queue per driver**: one driver has one long connection, so the commands have
to be dispatched serially. Between different drivers they can run in parallel — the
different queues do not affect each other.

**Why `BRPOP`**: it blocks, so it does not spin when there is no command.

### 2.3 The command result (a short TTL)

```
Key    labos:cmd:result:{commandId}
Type   String (JSON)
TTL    300 seconds

Content: {"commandId":"...","ok":true,"errorCode":null,
       "raw":"{...}","elapsedMs":3200,"finishedAt":1699...}
```

**A TTL of 300 seconds**: the result is only used for "waiting for the receipt after the
command was sent"; after 5 minutes nobody has taken it, nobody wanted it. The reason
Pub/Sub is not used is: **Pub/Sub is "fire and forget", and a subscriber that is not
online misses it forever**; with a key + TTL a late subscriber can still fetch it.

### 2.4 The commands in flight (preventing a timeout from hanging)

```
Key    labos:cmd:pending:{driverCode}
Type   Hash  field=commandId  value=enqueuedAt
```

**Purpose**: after the gateway crashes and restarts, it scans this Hash to learn which
commands were sent but have no result, so it can mark them as failed or resend them (by
the idempotency key).

### 2.5 The online devices (a fast count)

```
Key    labos:dev:online
Type   Set   member=deviceInstanceId
```

**Purpose**: `SCARD` gives the number of online devices in one line, without scanning all
the state keys.

### 2.6 The state-change notification (optional)

```
Channel  labos:events
Message     {"type":"device","id":214,"status":"running","method":"Incubate"}
```

**Pub/Sub is used because a state change is an "event"** — missing it is acceptable (the
frontend polls as a fallback). It is different from a command result, which must not be
lost.

---

## 3. The timing of the command flow

```
① The control console sends the command
   LPUSH labos:cmd:queue:{driver}  {commandId, device, method, params, timeout}
   HSET  labos:cmd:pending:{driver} {commandId} {now}

② The gateway takes the command
   BRPOP labos:cmd:queue:{driver}  → it gets the command
   Sends it over the TCP long connection

③ The device replies
   The gateway receives it → parses it
   SET   labos:cmd:result:{commandId} {ok, raw, elapsedMs} EX 300
   HDEL  labos:cmd:pending:{driver} {commandId}
   HSET  labos:dev:state:{device} status=idle updatedAt=...

④ The control console waits for the result
   Polls GET labos:cmd:result:{commandId} (every 50 ms, at most until the timeout)
   Deletes the key as soon as it has it, returns it
```

**Why polling and not subscribing**:

| Option | Advantage | Disadvantage |
|---|---|---|
| Polling with `GET` | Simple, the result is never lost, a late arrival can still fetch it | Once every 50 ms; there is a cost with a few dozen commands |
| A `BLPOP` result queue | No polling | One key per command, the order of the results is not guaranteed |
| Pub/Sub | Real time | **It is lost when it is offline** |

**Polling is the choice**: a command is "send one, wait for one", usually only a few are
in flight at the same time, and the cost of polling every 50 ms is negligible. **"The
result is never lost"** matters far more than saving a few `GET`s.

---

## 4. The responsibility boundary of the gateway

### What it does

| Responsibility | Explanation |
|---|---|
| **Maintain the long connections** | One TCP connection per driver, automatic reconnection on a drop (exponential backoff) |
| **Protocol framing** | Split on `#!LABSEP!#`, handle half packets |
| **Serialise the commands** | Only one command runs at a time on a connection (a device is physical) |
| **Timeout control** | Per the command's `timeoutMs`; on a timeout it writes a failed result |
| **State reporting** | `status=running` before execution, `status=idle` when it is done |
| **Heartbeat** | A periodic `PING`; after consecutive failures it marks `online=0` |

### What it does not do

| What it does not do | Why |
|---|---|
| **It does not judge the business semantics** | "Should this plate be moved" is the control console's business; the gateway only cares whether "this action can be executed" |
| **It does not retry** | A retry has to use the same `commandId`, and only the control console knows "whether this one can be retried" |
| **It does not keep history** | After a gateway restart the history should be gone; the history lives in the `run_command` table |
| **It does not do permissions** | Who may send a command is the control console's business |

**This boundary matters**: the smarter the gateway is, the harder it is to locate a
problem. **The gateway only does "turn bytes into commands, and replies into results"**.

---

## 5. The changes on the control-console side

### 5.1 The driver abstraction does not change

```java
public interface DeviceDriver {
    DriverResult execute(DriverCommand cmd);
}
```

**One more implementation is added**:

```java
public final class RedisQueueDriver implements DeviceDriver {
    public DriverResult execute(DriverCommand cmd) {
        // 1. LPUSH 命令到队列
        // 2. 轮询 labos:cmd:result:{cmd.commandId()}
        // 3. 拿到就返回
    }
}
```

**Why this interface still has to be kept**: `DriverStepExecutor` and `ShadowProbe` are both programmed against
`DeviceDriver`, so swapping the implementation does not change anything above it.

### 5.2 The configuration switch

```bash
LABOS_DRIVER_MODE=redis     # 走网关
LABOS_DRIVER_MODE=persistent # 走 Java 内长连接（保留，单机调试用）
LABOS_DRIVER_MODE=（空）     # 走短连接（默认，兼容现状）
```

### 5.3 What the frontend can see

**A new endpoint** `GET /api/devices/live` reads the `labos:dev:state:*` keys in Redis directly:

```json
{
  "items": [
    {"deviceInstanceId": 214, "online": true, "status": "running",
     "method": "Transfer", "since": 1699000000000}
  ]
}
```

**This is better than today**: today, to see "what the device is doing" you can only
query `run_node` and infer it, and once the workflow has finished it cannot be found at all.
The state in Redis is **real time**.

---

## 6. The deployment shapes

### The minimum deployment (a single machine)

```
Control console + gateway + Redis on the same machine
```

### The production deployment (recommended)

```
┌────────────────────────┐   ┌────────────────────────┐
│ Control console 1      │   │ Control console 2      │  ← several instances
└──────┬─────────────────┘   └──────┬─────────────────┘
      │                            │
      └─────────────┬──────────────┘
                    ▼
       ┌────────────────────────┐
       │ Redis (master/slave)   │
       └──────────┬─────────────┘
                 │
    ┌────────────────────────┐
    │ Device gateway         │  ← it can be several instances, each managing a few drivers
    └──────────┬─────────────┘
              │ TCP
 ┌────────────────────────┐
 │ The field device       │
 └────────────────────────┘
```

**How the gateway instances are divided**: by modulo on the `driverCode`, or configured
explicitly. **Only one gateway instance may connect to a given driver** — otherwise two
clients fight over the same TCP connection.

---

## 7. The key risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Redis is a single point** | When it is down no command can be sent | Master/slave + sentinel; the control console reconnects |
| **The gateway crashes** | The commands in flight are lost | `labos:cmd:pending` records the commands in flight; after a restart they are resent by the idempotency key or marked as failed |
| **Message order** | The queue is FIFO, but several consumers scramble it | **Only one consumer is allowed on the queue of a driver** |
| **Duplicate commands** | A retry by the control console moves the plate twice | `commandId` as the idempotency key, the gateway deduplicates on it (the most recent N) |
| **Redis memory** | The result keys pile up | Every key has a TTL, `pending` is cleaned up regularly |

**The most dangerous one is "duplicate commands"**: the robot arm moving the same plate
twice = a collision. The idempotency key has to run all the way through: a retry by the
control console reuses the `commandId` → the gateway deduplicates on the `commandId`.

---

## 8. The implementation order

| Phase | What is done | Output |
|---|---|---|
| **P0** | The Redis client + the data structures in place | The keys above can be read and written |
| **P1** | The gateway skeleton (connects to Redis + one long connection) | A single device runs `Home` through |
| **P2** | The control-console `RedisQueueDriver` | The control console sends commands through the gateway |
| **P3** | The state endpoint `/api/devices/live` | The frontend sees the real-time state |
| **P4** | Several drivers, several gateway instances | The whole island is covered |
| **P5** | The gateway crash recovery | The commands in flight do not hang |

**P0–P2 are enough to replace the existing direct connection**; only after P3 is there a
"visible" benefit.

---

## 9. The relationship with the existing capabilities

| Existing | After the split |
|---|---|
| `TcpDriverAdapter` (short connection) | Kept, for single-machine debugging |
| `TcpPersistentDriver` (long connection) | The logic moves into the gateway |
| `ShadowProbe` | Unchanged, the layer underneath becomes `RedisQueueDriver` |
| `ExperimentFlowRunner` | Unchanged |
| The `device_state` table | Kept (persisted); Redis is the real-time state, the two complement each other |

**`device_state` and Redis are not either/or**:

- **Redis**: real time, may be lost, shared between several instances
- **`device_state`**: persistent, still there after a restart, used for cross-day judgements such
  as "where is the plate"

**Dual write**: the gateway writes Redis after it finishes a command (real time), the
control console writes `device_state` after it consumes the result (persistent).

