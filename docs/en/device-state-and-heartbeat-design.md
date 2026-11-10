# Device State and Heartbeat — Design

> **English edition.** The Chinese original is [`docs/设备状态与心跳-设计.md`](../设备状态与心跳-设计.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Three things: **is the device online** (heartbeat), **what is the device doing**
> (three states), **what did the execution return** (the result).

---

## 1. Two dimensions of state

A device has **two independent** states, and they must not be mixed up:

| Dimension | Field | Values | Meaning |
|---|---|---|---|
| **Reachable or not** | `device_state.online` | `true` / `false` | Whether the heartbeat can reach it |
| **What it is doing** | `device_state.status` | `idle` / `running` / `error` | The state of the current task |

**All four combinations are meaningful**:

| online | status | Meaning | What to do |
|---|---|---|---|
| ✅ | idle | Online and idle | Normal, a task can be assigned |
| ✅ | running | Online and executing | Normal, wait for it to finish |
| ❌ | idle | Offline and idle | Check the network / the power / the driver |
| ❌ | **running** | **Lost the connection while executing** | **A person has to look** — nobody knows where the plate got to |

**The last row is the most critical**: if a device goes offline while it is `running`, it
means **the connection was lost halfway through the action**, which is a dangerous state
that needs human intervention.

---

## 2. Heartbeat: who can start it

**In this protocol the control console is the client and the driver is the server**.

A server cannot actively "push" to a client that is not listening on a port, so:

> **The heartbeat can only be started by the control console (polling).**

### The liveness probe has two levels

| Level | Method | What it can find | Cost |
|---|---|---|---|
| **① TCP reachability** | Connect to the port once | The process died, the network broke, the port is unreachable | Extremely low |
| **② Method probe** | Send one method and look at the reply | The port is open but the device is frozen | One round trip |

**① is the default** — every driver supports it and it costs nothing. If a particular
device often has an "open port but does no work", give it ② as well.

### Why the probing is per driver and not per device

The devices under the same endpoint share one TCP path. **If the endpoint is reachable,
they all are**.

Connecting to each one amplifies the number of connections to N times — 7 devices
connecting 7 times every 10 seconds, 20 devices means 20 connections, which is a burden
on the driver service on site.

**The price**: if one driver manages 20 devices and 1 of them really goes offline, the
liveness probe cannot find it (the endpoint is still reachable). **That case has to be
covered by the "method probe".**

---

## 3. Implementation

### 3.1 The heartbeat service

`driver/HeartbeatService`:

```java
start()                    // 启动后台线程，默认 10 秒一轮
probeOnce()                // 探测一轮
markRunning(instId, method, runId)   // 标记运行中
markIdle(instId, lastError)          // 标记空闲
```

**Each round does three things**:

1. Reads all the active endpoints from the `driver` table
2. Probes them one by one over TCP
3. Writes `driver_health` (the health record) + `device_state.online` (the device state)

### 3.2 The key point: UPSERT, not UPDATE

```sql
INSERT INTO device_state (tenant_id, resource_id, status, online, last_heartbeat, updated_at)
SELECT ?, di.resource_id, 'idle', ?, to_timestamp(? / 1000.0), now()
FROM device_instance di
WHERE di.driver_id = ? AND di.deleted_at IS NULL
ON CONFLICT (tenant_id, resource_id) DO UPDATE
SET online = EXCLUDED.online, ...
```

**Why it has to be an UPSERT**: a device may **never have** a `device_state` record (it has never
executed anything). A pure `UPDATE` silently affects 0 rows, which shows up as "the
simulator is clearly running, but the interface says offline".

**This trap has actually been stepped on**: all 7 simulators were online, but only 2
were shown as online — because only those 2 had ever executed and therefore had a
record.

### 3.3 Where the state is reported

| Moment | Action | Where |
|---|---|---|
| The device has been allocated, just before dispatching | `status='running'`, `current_method=the method name` (`current_method=方法名`) | `onStepStart` |
| The command returns (success / failure) | `status='idle'` or `'error'` | `onStepDone` |

**Mind the ID conversion**: `onStepStart` passes a `device_instance.id` (e.g. 171), whereas `device_state.resource_id` is a `resource_node.id` (e.g.
200171).

```sql
UPDATE device_state SET status = 'running', current_method = ?
WHERE tenant_id = ? AND resource_id =
      (SELECT resource_id FROM device_instance WHERE id = ?)
```

**Without the conversion it updates 0 rows and raises no error** — this project has
stepped on that trap nine times.

---

## 4. The three-state transitions

```
        ┌──────────────────────────────────┐
        │                                  │
        ▼                                  │
     [idle] ──allocate the device──> [running] ──success──┘
        │                    │
        │                    └──failure──> [error] ──retry succeeds──> [idle]
        │
        └──heartbeat fails──> online=false（status 不变）
```

**Mind this**: a failed heartbeat **does not change `status`**. A device can be "offline and
idle", and it can also be "offline and running" (the latter is dangerous).

---

## 5. How the result comes back

### 5.1 The three-part reply

```json
{
  "message_id": "cmd-123",        // 幂等键，必须原样回来
  "message_type": 5,               // 5 = 完成消息
  "method": "Deposit1",
  "equipment_id": "171",
  "method_status": 2,              // 1=运行中  2=成功  其他=失败
  "error": {},                     // 失败时填
  "data": {                        // ★ 结果在这里
    "position": "A1",
    "plate": "PL-20261029"
  }
}
```

### 5.2 How the control console stores it

| Stored where | Field | Content |
|---|---|---|
| `run_command.result` | jsonb | `{"ok":true,"durationMs":4053}` |
| `run_command.device_raw_response` | text | **The complete raw text** (including `data`) |
| `run_command.payload` | jsonb | The parameters that were actually dispatched |

**`device_raw_response` is the only source for tracing a result** — `data` is stored verbatim.

### 5.3 How the result is fetched

**Way one: query it with SQL directly**

```sql
SELECT method,
       device_raw_response::jsonb -> 'data' AS 结果
FROM run_command
WHERE run_id = ? ORDER BY command_seq;
```

**Way two: configure an output variable (passed on automatically inside the workflow)**

Configure it in the node parameters:

```json
{ "output": "plateId" }
```

During execution `ExperimentFlowRunner` takes the field of the same name out of `data`, stores it in a
variable, and the next step can refer to it with `${plateId}`.

**Way three: the API**

```
GET /api/runs/{runId}      # 节点树 + 每步结果
```

---

## 6. How it looks in the interface

### The device card in the island view

A **small dot + a colour**:

| Colour | Condition | Hover tooltip |
|---|---|---|
| 🟢 Green (with a glow) | `online=true` and `status=idle` | Online and idle |
| 🟡 Yellow (with a glow) | `online=true` and `status=running` | Running: Deposit1 |
| 🔴 Red | `online=true` and `status=error` | — |
| ⚫ Grey (no glow) | `online=false` | Offline (the heartbeat got no response) |

**Why a dot and not text**: the device card is small to begin with and text would crowd
it; a dot plus a hover tooltip is enough, and you can scan "which one went offline" at a
glance.

### The heartbeat endpoint

```
GET /api/heartbeat
```

```json
{
  "lastRunAt": "2026-11-02T00:56:05Z",
  "drivers": [
    {"driver": "DRV-SIM-ID1183", "online": true},
    {"driver": "DRV-19200200196-8501", "online": false}
  ]
}
```

**Purpose**: to see at a glance "which endpoint cannot be reached" — much faster than
checking device by device.

---

## 7. How the simulator cooperates

`DeviceSimulator` does not have to send a heartbeat actively (it is impossible in the protocol), but
it has to:

| Behaviour | Parameter | Effect |
|---|---|---|
| Normal response | Default | The heartbeat probe connects → online |
| Does not listen on the port | `--offline` | Cannot connect → **offline** |
| Running | Default | Replies immediately on receiving a command |

**Verifying online/offline**:

```bash
# Online
java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.sim.DeviceSimulator --port 19501 --device 171 --code ID1183

# Offline (does not listen)
java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.sim.DeviceSimulator --port 19501 --offline

# Kill it while it is running
pkill -f "code ID1183"
```

**Within 10 seconds the dot in the interface turns from green to grey** (waiting for the
next heartbeat round).

---

## 8. Known limitations

| Limitation | Impact | Mitigation |
|---|---|---|
| **Probing per endpoint, not per device** | When one driver manages several devices, one going offline cannot be found | Give it a method probe |
| **A 10-second interval** | An outage is only shown after at most 10 seconds | `intervalMs` can be configured smaller |
| **No protocol-level handshake** | "The port is open but the device is frozen" cannot be found | Same as above |
| **No automatic reconnection** | Somebody has to handle it after an outage | The driver's reconnection is a different layer |

---

## 9. The troubleshooting order for operations

When a device shows as offline:

```
① journalctl / 进程在不在
② lsof -i :<端口>     端口有没有监听
③ nc -z <IP> <端口>   网络通不通
④ 看 /api/heartbeat   是哪个端点不通
⑤ 看 /tmp/sim-*.log   模拟器有没有报错
```

**Do not restart the control console straight away** — first establish whether it is on
the device side or on the control-console side.

---

## 10. The offline alert

### 10.1 The rule

| Condition | Action |
|---|---|
| `online=false` and `last_heartbeat` is more than **30 seconds** old | Open one `alert_state` |
| `online=true` again and there is an unclosed alert | Close it automatically |

**Why `last_heartbeat` is used to judge the duration**: this field is only updated when a probe
succeeds, so after an outage it stops at the time of the last success, and `now() - last_heartbeat` is
naturally "how long it has been offline".

### 10.2 Idempotency: no flooding

`fingerprint = 'DEVICE-OFFLINE|<resourceId>'` is a unique key, so the same device **has only one unclosed alert**, and it is not
pushed every 10 seconds.

```sql
-- 开告警：加 NOT EXISTS 判断
AND NOT EXISTS (
      SELECT 1 FROM alert_state a
      WHERE a.fingerprint = 'DEVICE-OFFLINE|' || r.id AND a.status = 'open')
```

### 10.3 The trap stepped on during the rollout: 169 false positives

At the first go-live, **169 offline alerts were opened in one go**.

**Cause**: the `driver_id` of 169 devices in the database points at 5 real-IP drivers (`192.0.2.x`),
and those drivers **were never reachable in the first place**. And the heartbeat's
UPSERT **wrote `last_heartbeat` whether it was online or not**, so they were all judged to be
"offline for more than 30 seconds".

**The two corrections**:

| Problem | The fix |
|---|---|
| `last_heartbeat` was written when offline as well | `CASE WHEN ? THEN now() ELSE NULL END` — **it is only updated when online** |
| Devices that had never connected also alerted | Added the `last_heartbeat IS NOT NULL` condition — **only devices that "have been online before" alert** |

**Cleanup**: those 169 false positives were closed in bulk and annotated with the
reason.

### 10.4 The measured closed loop

```
① 杀掉 ID1183 模拟器
② 等 40 秒
   ID1183 | online=f | 离线59秒 | 告警=open     ← 只有这一台
   ID1731 | online=t | 离线20秒 | 无告警        ← 正常设备不报
③ 重启模拟器
④ 等 25 秒
   [心跳] 关闭 1 条离线告警（设备已恢复）
   ID1183 | online=t | 告警状态=closed
```

### 10.5 The endpoint

```
GET /api/device-alerts?limit=60
```

```json
{
  "total": 5,
  "items": [{
    "fingerprint": "DEVICE-OFFLINE|200171",
    "status": "open",
    "deviceCode": "ID1183",
    "deviceName": "Inheco_Shaking_Incubation-CMS",
    "online": false,
    "offlineSec": 59,
    "lastHeartbeat": "2026-11-02T01:09:50Z"
  }]
}
```

**The difference from `/api/alerts`**:

| Endpoint | Content | How to handle it |
|---|---|---|
| `/api/alerts` | **Execution errors** (a command failed / timed out) | Look at `run_command` |
| `/api/device-alerts` | **Device state** (cannot be reached) | Check the network and the power first |

**Keeping them apart is deliberate** — the troubleshooting paths are completely
different, and mixing them would mislead operations.


