# Testing and Release Strategy

> **English edition.** The Chinese original is [`docs/测试与上线策略.md`](../测试与上线策略.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> The question it faces: **how do we verify that this system can really send commands to
> the devices?** The answer: layered verification, from "is the code right" to "does the
> device move", with an explicit pass criterion at every layer.

---

## 1. The five-layer verification model

```
L5  Production canary  real experiments, real samples, ramping up gradually   ← on site
L4  Staged commissioning  single device → single workflow → multiple devices → whole island ← on site
L3  Whole-workflow shadow  the workflow runs once, the commands are not sent to the device, only logged ← on site / intranet
L2  Single-command probe  one method on one device, check whether the contract is right        ← on site / intranet
L1  Unit + integration  the code logic, the database reads and writes, the state machine   ← local / CI
```

**If a layer fails, do not go further down** — running L2 after L1 has failed is a waste
of time.

---

## 2. L1: unit and integration (already there)

### The self-tests that exist

| Test | Coverage | Command |
|---|---|---|
| `RecoverySelfTest` | Crash recovery, idempotency, leases | `java -cp out com.labos.kernel.recovery.RecoverySelfTest` |
| `EngineSelfTest` | The state machine, retries, hand-over to an operator | `... engine.EngineSelfTest` |
| `AlertSelfTest` | Alert generation, deduplication, merging | `... alert.AlertSelfTest` |
| `WorkflowAlertTest` | Missing plate, timeout and failure alerts | `... workflow.WorkflowAlertTest` |
| `GraphRunnerTest` | Parallel, exclusive gateway, compilation | `... workflow.GraphRunnerTest` |
| `RecipeCompilerTest` | Compiling workflows on the simulation bus | `... workflow.RecipeCompilerTest` |
| `RealWorkflowRunner --all` | the workflows running end to end | `... workflow.RealWorkflowRunner --all` |

**Pass criterion**: everything green + the processes at the full set = 100%.

### Self-tests for the new capabilities (recommended additions)

| Test | What it verifies |
|---|---|
| `PositionLockTest` | A failed reservation, a refusal when a plate is present, a TTL expiry |
| `ExperimentFlowRunnerTest` | Step-by-step execution, the variable write-back, the preflight interception |
| `ConditionEvalTest` | The various operators of `${a} > 37`, a variable that is not found |

**Why these deserve a separate test**: they have a clear input and output, they can be
verified without a device, and blocking a regression with them in CI is the cheapest
option.

---

## 3. L2: the single-command probe (the most important verification means)

### What problem it solves

**"Can the kernel really talk to the device?"**

It does not run a workflow, it **sends exactly one command**:

```bash
POST /api/shadow/probe?resourceId=200214&method=Home
```

It returns:

```json
{
  "ok": true,
  "elapsedMs": 87,
  "contract": {
    "messageId": true,
    "messageType": true,
    "method": true,
    "methodStatus": true
  },
  "rawResponse": "{\"message_id\":\"...\",\"equipment_id\":\"214\",...}"
}
```

### The four items of the contract check

| Field | Meaning | What goes wrong without it |
|---|---|---|
| `messageId` | The reply has a unique id | You cannot tell whether this is the response to this call |
| `messageType` | The type of the reply is right | It might be a heartbeat rather than an execution result |
| `method` | The reply answers the method you sent | The response could be attributed to the wrong command |
| `methodStatus` | There is an execution status | You do not know whether it succeeded or failed |

**The contract only counts as working when all four are true**.

### The execution order on site

```
① Change appsettings.json → one endpoint (only change one first)
② Start the Sidecar
③ Pick one device under that endpoint and run probe(Home)
   ├ If it works → try the next method
   └ If it does not → look at rawResponse, it is most likely the field name / the delimiter that is wrong
④ The 3-5 key methods of this device all work → move to the next device
⑤ Pick 1-2 devices under each of the 13 endpoints, only enter L3 when they all work
```

**Why `Home` first**: `Home` is the safest action (returning to the home position); it does
not touch a plate and does not collide with anything.

---

## 4. L3: the whole-workflow shadow

### What problem it solves

**"When a workflow runs through, is the command sequence right?"**

It does not touch the device, it only logs:

```bash
POST /api/workflows/239/shadow-run
```

It returns:

```json
{
  "runId": 204900,
  "ok": true,
  "steps": [
    {"node": "N1", "method": "Deposit1", "device": "200171", "wouldSend": true},
    {"node": "N2", "method": "Withdraw1", "device": "200171", "wouldSend": true}
  ]
}
```

### What the shadow mode is for

| Check | How to look at it |
|---|---|
| **Is the device allocation right** | Which device each node lands on, is it the one you expected |
| **Is the method name right** | `Deposit1` or `Deposit1Ex`, not a single character may differ |
| **Are the parameters right** | Are the values after the `${var}` substitution reasonable |
| **Is the order right** | Are the parallel branches really parallel, which branch did the exclusive gateway take |
| **Does the plate-position preflight pass** | Is there an interception of "the source device has no plate" |

**If the shadow run does not work, the real run definitely will not** — because the
shadow reuses the same device-allocation and parameter-substitution logic.

---

## 5. L4: staged commissioning

### Why it is staged

Running a complex workflow in one go means that when something goes wrong **you do not
know which step it was**.

### The four stages

| Stage | Content | Pass criterion |
|---|---|---|
| **① One device, several methods** | One device, running its 3-5 methods | Each one is ok=true, the contract works throughout |
| **② One workflow** | The simplest 2-node workflow (like 239) | 2/2 completed, `run_command` holds the raw text |
| **③ Several devices working together** | 3-4 stations including a robot-arm transport | The source/target of the `Transfer` are correct |
| **④ The whole island** | Every device on the island takes part | It runs through without human intervention |

### What to do between the stages

```sql
-- 看这一段发了什么命令
SELECT command_seq, method, payload, result
FROM run_command WHERE run_id = ? ORDER BY command_seq;

-- 看设备有没有报错
SELECT method, device_raw_response->>'error'
FROM run_command WHERE run_id = ? AND status = 'failed';
```

**These two SQL statements have to be looked at after every stage** — not only "did it
finish", but "what was sent, what came back".

---

## 6. L5: the production canary

### The ramping-up schedule

```
Week 1   1 island, 1 workflow, 1-2 times a day, watched by a human
Week 2   3-5 workflows on the same island
Week 3   all the workflows on the same island
Week 4   the 2nd island
```

**What has to be met before every step of the ramp-up**:

| Condition | Why |
|---|---|
| 0 incidents in the previous step | |
| All the alerts have been handled | Ramping up while alerts are pending hides the new problems |
| The duty operator can handle the common alerts independently | Otherwise nobody picks it up when something goes wrong |

### The things that must never be done

| The wrong thing | The consequence |
|---|---|
| **Two systems operating on the same set of devices at the same time** | Both think the plate is in their hands, a collision |
| Skipping the shadow and going straight to the real run | A wrong parameter is only discovered afterwards |
| Changing several endpoints and then going live | When something goes wrong you do not know which endpoint it was |

**The transition must be**: stop any other software that is controlling these devices → a human empties the devices → bring up
this system.

---

## 7. How to create test data

### The scenarios that do not touch a real device

| Requirement | How |
|---|---|
| Verify the command format | `sidecar-dotnet --mock`, returns a fixed JSON |
| Verify the workflow logic | `RealWorkflowRunner --all` (workflows on the simulation bus) |
| Verify the plate-position logic | Change `device_state.has_plate` directly with SQL |
| Verify the robot-arm positions | `POST /robot-points/sync` generates them per device |

### Creating a "has a plate" state

```sql
INSERT INTO device_state (tenant_id, resource_id, status, has_plate, updated_at)
SELECT 1, id, 'idle', true, now()
FROM resource_node WHERE tenant_id = 1 AND code = 'ID1775'
ON CONFLICT (tenant_id, resource_id)
DO UPDATE SET has_plate = true, updated_at = now();
```

### Creating a "transport" scenario

```bash
# 1. The positions exist (code = the device number)
POST /api/islands/504821/robot-points/sync?robotResourceId=200214

# 2. The source device has a plate
UPDATE device_state SET has_plate = true WHERE ... 'ID1775'

# 3. Execute
POST /api/experiments/flow-run
  ?steps=200214:Transfer::source=ID1775;target=ID1183
```

---

## 8. How to localise a problem

### Three steps

```
① Look at run_command
   SELECT * FROM run_command WHERE run_id = ? ORDER BY command_seq;
   → Which step failed, what was passed at the time, what the device replied

② Look at run_node
   SELECT * FROM run_node WHERE run_id = ?;
   → Which device this step used (resource_id)

③ Look at the raw text of the device
   SELECT device_raw_response FROM run_command WHERE id = ?;
   → What the driver actually replied
```

### A table of the common symptoms

| Symptom | The most likely cause | How to confirm it |
|---|---|---|
| `EC-06-0001` | The device was not registered with a driver endpoint | Look at `device_instance.driver_id` |
| `EC-99-0002` | The driver threw an exception | Look at the Sidecar log |
| One contract item is false | The name of a returned field is wrong | Look at the actual fields in `rawResponse` |
| The preflight fails with `EC-PLATE-PRECHECK` | The source device has no plate | Look at `device_state.has_plate` |
| 0 steps were executed | The graph is not connected to a device | Look at whether `steps` is empty |
| The variable was not written back | The name of a returned field does not match the `output` configuration | Look at whether `rawResponse` has that field |

---

## 9. One-sentence summary

> **The unit tests test the logic, the probe tests the contract, the shadow tests the
> sequence, the staged commissioning tests the collaboration, the canary tests the
> stability.** **Every layer has an explicit "pass criterion"; if it does not pass, you
> do not go further down.**

---

## 10. The command timing: send one, wait for one

### 10.1 The current model

```
The frontend POSTs /api/experiments/flow-run
    ↓
ExperimentFlowRunner loops over the steps
    ↓ Step 1
ShadowProbe.probe → TcpDriverAdapter.execute
    ↓ establishes the TCP connection
    ↓ socket.setSoTimeout(30_000)      ← 读超时 30 秒
    ↓ sends one line of JSON (the delimiter #!LABSEP!#)
    ↓ blocks waiting for the reply (reads in a loop until the deadline)
    ↓ parses the DriverResult
    ↓ closes the connection
    ↓ only then does it enter step 2
```

**The key point**: `ShadowProbe.probe()` is a **synchronous method**, the result is already in hand when
it returns. The caller `for (const st of steps)` is naturally serial — **there is no "send it and forget
it"**.

### 10.2 Three layers of timeout

| Layer | Timeout | Where it is set |
|---|---|---|
| Command level | 15 seconds (the default, configurable) | When `ShadowProbe` constructs the `DriverCommand` |
| Read timeout | 30 seconds | `socket.setSoTimeout(30_000)` |
| Connection level | Controlled by the deadline | `long deadline = t0 + cmd.timeoutMs()` |

**Why the read timeout (30s) is longer than the command timeout (15s)**: the read
timeout is "give up after no byte has arrived for this long", the command timeout is
"how long the whole command is allowed to take". The former leaves a margin, so that a
command that is still in flight is not judged dead.

### 10.3 What to do after a timeout

```java
// TcpDriverAdapter
if (System.currentTimeMillis() > deadline) {
    return DriverResult.timeout(cmd.commandId(), ...);
}
```

`DriverResult.timeout` → `probe.ok() == false` → `ExperimentFlowRunner` records failed and **aborts the following steps**.

**Why it aborts instead of continuing**: doing the next step before this one is finished
can physically cause a collision (for example the robot arm goes to fetch a plate before
it is in position).

### 10.4 The persistent-connection mode (`LABOS_DRIVER_MODE=persistent`)

**Why it is needed**: a short connection suits the commands that come back in a few
seconds, but an incubation / a centrifugation often takes tens of minutes:

| Problem | Short connection | Long connection |
|---|---|---|
| The socket during the wait | It is held the whole time | The same one is reused |
| An intermediary device drops the connection | The command simply fails | There is a reconnection |
| Progress / heartbeat reporting | All lost | It can be received (dispatched by messageId) |
| The connection overhead | Once per command | Only once |

**How it is switched on**:

```bash
LABOS_DRIVER_MODE=persistent java -cp ... ApiServer 18080
```

The startup log prints `[驱动] 长连接模式：tcp://...`.

**The core structure**:

```
execute(cmd)
  ├ messageId enters the pending table
  ├ one row is written to the socket (with a write lock, several threads must not write at the same time)
  └ future.get(timeout)          ← blocks

readerLoop() (a separate thread)
  ├ read() frames by #!LABSEP!#
  ├ parses out the message_id
  └ pending.remove(id).complete(result)
```

**Three things that have to be handled**:

| Point | What happens if it is not handled |
|---|---|
| **Reconnection** | A single network jitter loses the connection for good |
| **Serialised writes** | Several threads writing at the same time garbles the packets |
| **Cleaning up the dangling future** | The device replies after the timeout, it pushes into a future nobody is waiting for, a memory leak |

**The registry has to be cached**:

```java
if (persistent && cachedRegistry != null && 60秒内) return cachedRegistry;
```

**Without the cache a new driver is created on every request** — a long connection is
then no long connection at all. Measured without the cache, `[驱动] 长连接模式` is printed every time;
with the cache it is printed only once.

**Caching for 60 seconds**: so that a change in the device capabilities takes effect,
without reconnecting on every request.

**Measured**: 4 consecutive commands took 69/64/61/62 ms, the driver was created only
once.

### 10.5 If the device is in the "asynchronous reporting" mode

The current system is **request-response** (a TCP short connection, one question and one
answer). Some vendors' devices are:

```
Send the command → return accepted(userId) immediately
        ↓
The device executes (a few seconds to a few minutes)
        ↓
It reports completed(userId) proactively
```

**This mode is not supported today**, it would need to be changed:

| What has to change | How |
|---|---|
| The long connection | Do not open a connection per command; use a permanent connection + message dispatch |
| Pairing the request with the response | Use the `messageId` as the key and maintain a `Map<messageId, CompletableFuture>` |
| The timeout | Configure it per `method` (an incubation may take 30 minutes) |
| The progress | When it reports `running(50%)`, update `run_node.progress` |

**How to decide whether the change is needed**: look at the `method_status` the driver returns.
- If it returns `2` (success) it is the synchronous mode, the current system is enough
- If it returns `1` (running) it is asynchronous, **the long connection can already
  receive it, but "waiting for it to become 2" is still missing**

**What the long connection already implements**: it can receive several messages at the
same time, and it can pair them by messageId. **What is still missing**: when `method_status=1` it
should **keep waiting** instead of failing straight away.

In the current code `status == 1` returns `EC-99-0004` ("the device is still running"), which is a
**conservative placeholder** — on a real machine it should be:
1. On receiving 1, complete a "progress" event, the future keeps waiting
2. On receiving 2, complete the final result
3. Only a timeout is a failure

### 10.6 How to verify that the timing is right

```sql
-- 看每步的耗时：如果都是 0ms，说明是 mock 立即返回
SELECT command_seq, method,
       (result->>'durationMs')::int AS 耗时ms,
       status::text
FROM run_command WHERE run_id = ?
ORDER BY command_seq;
```

**A real device**:
```
序  方法        耗时ms   状态
1   Deposit1    3200     completed     ← 开门+放板，几秒
2   Transfer    5400     completed     ← 机械臂搬运
3   Deposit1    2800     completed
```

**If the duration is 0 or a single digit** → it went through the mock or the virtual
driver, it was not really waiting for a device.

### 10.7 The concurrency scenarios

**Within the same workflow**: strictly serial (a `for` loop + a synchronous call).

**Between different workflows**: it relies on the island-level mutual exclusion (the
island lock of `ExperimentScheduler`), so two workflows on the same island never send commands at the
same time.

**The exception**: calling `/api/shadow/probe` manually is not bound by the island lock — **this is
deliberate** (on-site debugging has to be able to verify a single point at any time),
but it also means **do not debug on a device at the same time as a production workflow
is operating on it**.

