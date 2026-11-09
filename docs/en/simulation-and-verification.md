# Simulation and Verification Guide

> **English edition.** The Chinese original is [`docs/仿真与验证指南.md`](../仿真与验证指南.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **The simulation is not another run mode, it is only a different `StepExecutor` / `DeviceDriver`
> implementation.** This design lets "verification" and "production" share the same
> execution logic; the difference is only at the far end.

---

## 1. Three layers of simulation

| Layer | Class | What it replaces | What it verifies |
|---|---|---|---|
| **① Virtual bus** | `engine/SimulatedBus` | `StepExecutor` | Workflow logic, state machine, retry, hand-over to an operator |
| **② Virtual driver** | `driver/VirtualDriver` | `DeviceDriver` | Device allocation, capability matching, command structure |
| **③ Mock service** | `driver/tcp/MockDriverServer` | The real vendor driver | **The protocol**: framing, field names, timeouts, the raw text |

**The further down, the closer to reality**:

```
SimulatedBus      returns the result in memory, zero network
VirtualDriver     goes through the driver interface, has the DriverResult structure
MockDriverServer  really opens a TCP port, sends and receives JSON, verifies the #!LABSEP!# framing
The real driver   .NET Sidecar → device
```

---

## 2. How to run it

### 2.1 Run all the processes (the fastest way to see results)

```bash
cd labos-kernel

# Walk through every workflow on the virtual bus
LABOS_DB_NAME=<database name> ./scripts/start-kernel.sh \
     com.labos.kernel.workflow.RealWorkflowRunner --all
```

**Output**:

```
流程总数 xxx，耗时 xxxx ms
全部完成     xxx 个  100.0%
结构异常       0 个（无环、无孤立节点）
节点完成   xxxxx 个  100.0%
转人工         0 个
```

**What to look at**:

| Metric | Meaning | What it means when it is not normal |
|---|---|---|
| **Structural anomalies** | The graph has cycles or isolated nodes | There is a problem with the graph parsing |
| **Handed over to an operator** | A node cannot find a capability / device | The device allocation or the capability dictionary is missing something |
| **Completion rate** | The share that ran through | Some nodes failed |

### 2.2 A single workflow executed along its graph

```bash
LABOS_DB_NAME=<database name> ./scripts/start-kernel.sh \
     com.labos.kernel.workflow.RealWorkflowRunner --all --verbose
```

**Adding `--verbose`** prints the allocation result of every node.

### 2.3 The single-command probe (verifies the protocol)

**Nothing is written to the database, no workflow is run**; it sends exactly one
command:

```bash
POST /api/shadow/probe?resourceId=200214&method=Home
```

It returns:

```json
{
  "ok": true,
  "elapsedMs": 62,
  "contract": {
    "messageId": true,
    "messageType": true,
    "method": true,
    "methodStatus": true
  },
  "rawResponse": "{\"message_id\":\"...\",\"method_status\":2,...}"
}
```

**The protocol only counts as working when all four `contract` items are true**:

| Field | What goes wrong without it |
|---|---|
| `messageId` | You cannot tell whether this is the response to this call |
| `messageType` | It might be a heartbeat rather than an execution result |
| `method` | The response could be attributed to the wrong command |
| `methodStatus` | You do not know whether it succeeded or failed |

**Use this as the first step on site** — after changing `appsettings.json`, probe first; do not rush
to run a workflow.

### 2.4 A whole-workflow shadow run (to see "what would be sent")

```bash
POST /api/workflows/{id}/shadow-run
```

**No command is sent**; it only records what each step "would send".

**What to look at**:
- Which device each node lands on
- Whether the method name is right (`Deposit1` or `Deposit1Ex`)
- The values after the variable substitution
- Whether the order is right (which branch the parallel / exclusive gateway took)

### 2.5 Step-by-step execution on the canvas (written to the database, visible in the UI)

```bash
POST /api/experiments/flow-run
  ?name=试跑
  &steps=200214:Transfer::source=ID1775;target=ID1183
  &vars=plate=PL-001
```

**This really sends the commands** (through the driver) and writes every step into `run`
/ `run_node` / `run_command`.

---

## 3. Injecting faults (verifying the retry and the recovery)

`SimulatedBus` supports injecting faults per device:

```java
SimulatedBus bus = new SimulatedBus()
    .fail(200214, "TRANSFER", "EC-05-1007", 2)       // 前 2 次超时
    .fail(200171, "INCUBATE", "EC-05-0001", Integer.MAX_VALUE);  // 永久故障
```

**Parameters**:

| Parameter | Explanation |
|---|---|
| `resourceId` | Which device |
| `capability` | Which capability |
| `errorCode` | What error code is returned |
| `times` | How many times it fails (`MAX_VALUE` = broken forever) |
| `scope` | The recovery scope (`COMMAND` / `PLATE` / `RUN`) |

**What it verifies**:

```
故障注入 → 状态机按 recovery_policy 决策 →
  重试成功     → 记录 attempt=2
  重试耗尽     → 转人工
  不可恢复     → 中止并告警
```

**This is how "the error is decidable" is verified** — not by reading the log, but by
watching how the state machine walks through it.

---

## 4. How to see the results

### 4.1 Command line

| Command | Output |
|---|---|
| `RealWorkflowRunner --all` | Completion rate, structural anomalies, number of hand-overs |
| `RecoverySelfTest` | The 17 assertions of the crash recovery |
| `EngineSelfTest` | The 14 assertions of the state machine |
| `AlertSelfTest` | The 12 assertions of the alerting |
| `GraphRunnerTest` | The 5 assertions of the graph execution |

### 4.2 API

| Endpoint | What to look at |
|---|---|
| `GET /api/runs` | The list of executions |
| `GET /api/runs/{id}` | The node tree of a single execution |
| `GET /api/experiments/{id}/runs` | The execution history of an experiment |
| `GET /api/scheduler/status` | Island locks, queues, stalled |
| `GET /api/positions?islandId=` | Which positions hold a plate |

### 4.3 Database (the most reliable)

```sql
-- Which commands one execution sent
SELECT command_seq, method, device_id, status, error_code,
       payload, device_raw_response
FROM run_command WHERE run_id = ? ORDER BY command_seq;

-- The status of every node
SELECT name, status, resource_id FROM run_node WHERE run_id = ? ORDER BY id;

-- The overall conclusion
SELECT status, error_code, output FROM run WHERE id = ?;
```

**If `durationMs` is always 0 or a single digit** → it went through the virtual bus, it was not
really waiting for a device.

### 4.4 User interface

| Page | What to look at |
|---|---|
| **Execution monitoring** | The node tree, the status of every step |
| **Experiment orchestration → execution history** | The status / progress / duration of each experiment |
| **Island view → device details** | The position occupancy (green/pink/orange) |
| **Alerts** | Missing plates, timeouts, failures |

---

## 5. The capability boundary of the simulation

**What it can verify**:

| Item | Can |
|---|---|
| Graph parsing (parallel, exclusive, loop) | ✅ |
| Node ordering and dependencies | ✅ |
| Device allocation (by capability, round-robin, empty device first) | ✅ |
| Variable substitution | ✅ |
| Retry and hand-over to an operator | ✅ |
| Plate position occupancy and release | ✅ |
| Writing to the database | ✅ |
| Protocol fields (using the mock service) | ✅ |

**What it cannot verify**:

| Item | Why |
|---|---|
| **The real duration** | The virtual bus returns immediately, the observed duration is fake |
| **Physical constraints** | Will the robot arm collide, will the gripper hold |
| **The real behaviour of the device** | The edge cases of the driver DLL |
| **Concurrency contention** | The interference when several devices act at the same time |

**So**: passing the simulation = **the logic is correct**; it does not mean it is
**ready for production**. Going live also has to pass the L2–L5 of `docs/测试与上线策略.md`.

