# Dynamic Workflow Capability — Overview

> **English edition.** The Chinese original is [`docs/动态流程能力-总纲.md`](../动态流程能力-总纲.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> This ties together all the capabilities that have been discussed recently: **how a
> workflow gets "moving"**.
> The details of each topic are in the corresponding document; this file only covers **the
> overall picture and the relationships**.

---

## 1. The picture in one sentence

```
A workflow is not just "issuing commands in order", it has to be able to:
  ① compute (an expression)      —— derive a new value from the variables
  ② query (result injection)     —— read the result an earlier node produced asynchronously
  ③ wait (waitFor)               —— wait if the result has not arrived yet
  ④ branch (a dynamic branch)    —— take a different branch depending on the result
  ⑤ script (optional)            —— only for complex logic, the highest risk
```

**The first four cover 90% of the "dynamic" requirements; the fifth is the fallback.**

---

## 2. Three levels of capability (in increasing order of risk)

| Level | Capability | Risk | Performance | When to use it |
|---|---|---|---|---|
| **L1 expression** | Arithmetic, conditions, rounding, concatenation | ✅ None | Microseconds | Computing one value |
| **L2 result query** | `waitFor` + `results.*` injection | ✅ None | Milliseconds | Waiting for an async result, branching on a value |
| **L3 script** | Upload Java code, executed in a separate process | 🟠 **Medium** | 200-500ms | Querying an external system, complex transformation |

### The decision criterion (ask it this way)

```
① Can it be computed from the variables that already exist?
   Yes → an expression
② Is it querying a result / a state that this system itself produced?
   Yes → waitFor + variable injection
③ Is it querying an external system, or doing a transformation an expression cannot do?
   Yes → a script (it has to go through approval)
```

**In the vast majority of cases it lands on ① and ②.**

---

## 3. Four layers of resources (the basis of parallel scheduling)

| Layer | Resource | Granularity | How long it is held | Concurrency |
|---|---|---|---|---|
| **L1 position** | `position_state` | One level position | For the whole task | Several positions are independent |
| **L2 channel** | `device_port` | One in/out port | Action level | **capacity 1~N, counted** |
| **L3 gate** | `device_access` | One openable part | Action level | The same gate is mutually exclusive |
| **L4 robot arm** | `device_state` | One robot | Action level | One at a time |

### Why four layers and not three

**"The gate" was missing originally**: if the centrifuge door is not open and the robot arm
reaches in, it crashes.

**And the entrance/exit is not "one per device"** either:

| Form | Configuration | Lock |
|---|---|---|
| Separate | `IN-1` + `OUT-1` | Two independent ones |
| Multiple in and out | `IN-1` `IN-2` `OUT-1` `OUT-2` | Four |
| Single | `BOTH` | One, in and out are mutually exclusive |
| Wide channel | `capacity=2` | **A semaphore** (two at the same time) |

### The fixed total order (deadlock avoidance)

```
gate → channel → position → return position → robot arm
             ━━━━━━━━━━━━━━━
             all ready before the plate is picked up
```

**The gate comes first**: if the gate is open but the robot arm was not obtained, the gate
can be closed and the attempt abandoned;
the other way round the plate is already in the gripper and cannot be put back.

**"Picking up the plate" is the point of no return** — all the resource requests have to be
completed before it.

---

## 4. The position system

### 4.1 The numbering is an agreement between the control console and the robot arm

```
{device code}-{type}{number}
ID1183-L3      multi plate position
ID1775-IN2     multiple inlets
STACKER-OUT1   multiple outlets
STACKER-S5     stacking
```

### 4.2 The numbering and the access order are two concepts

| Concept | Field | Who decides it |
|---|---|---|
| **The numbering** | `code` | Generated during authoring / synchronisation |
| **The access order** | `order_in_device` | **Decided by on-site teaching** |

**The user was explicit**: "L1 cannot be decided now, the different positions have to be
decided on site".

**So the system assumes nothing** about whether `L1` is the bottom or the outer level,
**everything is configured on site**.

### 4.3 The position attributes (15 fields)

The key ones:

| Attribute | What it does |
|---|---|
| `port_id` | Which channel it goes through |
| `position_id` | Which level position it corresponds to |
| `access_id` | **Which gate to open before picking up the plate** |
| `approach_axis` | From which direction the arm reaches in |
| `safe_z_offset` | How high it is lifted before traversing |
| `order_in_device` | The order in which the robot arm visits it |
| `fill_status` | Only `ok` can be used for scheduling |

### 4.4 How positions are generated in a workflow

```json
{ "nodeType": "loop", "varName": "i", "from": 1, "to": 8,
  "body": [{ "method": "Transfer",
             "params": { "target": "ID1183-L${i}" } }] }
```

**A loop + a variable → a dynamic position**. This is the core capability the user wants.

---

## 5. Querying asynchronous results

### 5.1 Storing

```sql
run_result (run_id, result_key, payload jsonb, source, created_at)
```

### 5.2 Injecting

```
results.readPlate.od        = 0.65
results.readPlate.__exists  = true
```

### 5.3 Waiting

```json
{ "waitFor": { "results": ["readPlate"], "timeoutMs": 600000,
               "pollMs": 2000, "onTimeout": "pause" } }
```

**While waiting, the robot arm / channel / position have to be released**, otherwise the
whole island deadlocks.

### 5.4 Using

```json
{ "condition": "${results.readPlate.od > 0.5}" }
```

---

## 6. The laboratory profile (the configuration decides the architecture)

| Configuration | What it decides |
|---|---|
| **`max_parallel`** | **1 → device locks are enough; 2 → +returnTo; 3+ → four layers of resources + deadlock detection** |
| `objective` | Throughput / timeliness / fairness |
| `unknown_policy` | Stop everything when the plate position is unknown / stop only the related ones / carry on |
| `night_mode` | UNKNOWN is downgraded automatically when nobody is on site at night |
| `retry` | The retry ceiling (GxP does not retry automatically) |
| `audit` | Command level / only errors are stored / nothing is stored |

**The templates cover 80% of the scenarios**: high throughput / medical testing /
education / GxP.

---

## 7. The relationship with the existing code

| Current state | What has to change |
|---|---|
| `substVars` is a pure substitution | **Changed to expression evaluation** |
| The `output` configuration (extracting variables) | **Changed to writing `run_result` + injecting** |
| `device_state.current_run_id` | Extended into the four layers of resources |
| `robot_point` has 6 fields | **9 more are added + `fill_status`** |
| `device_instance.driver_id` | Unchanged |
| The island lock (already downgraded to optional) | Kept |

**What already exists and can be reused**:
- The atomic reservation of `PositionLock` (`INSERT ON CONFLICT`)
- The `EXCLUDE` constraint of `resource_booking`
- `DeviceLock` (at the device level)
- The loop column of the canvas

---

## 8. The implementation route (M1 broken down)

| Stage | Content | Estimate | Independently verifiable |
|---|---|---|---|
| **M1.1** | **The expression engine** | 2.5 days | ✅ Unit tests |
| **M1.2** | Create the tables: `run_result` / `device_port` / `device_port_usage` / `device_access` / the extra fields of `robot_point` / `device_point_template` | 1.5 days | ✅ The table-creation script |
| **M1.3** | **Variable injection + waitFor** | 3 days | ✅ Create two runs to verify |
| **M1.4** | **The resource acquisition protocol** (gate→channel→position→return→robot arm) | 3 days | ✅ It is refused when the gate is not open |
| **M1.5** | **Position variable substitution + loop expansion** | 2 days | ✅ `ID1183-L${i}` is expanded |
| **M1.6** | End-to-end acceptance (5 scenarios) | 2 days | ✅ |
| **Total** | | **14 days (2.1 weeks)** | |

### Why this order

```
M1.1 is the most independent (pure computation, no dependencies) → do it first, it can be verified immediately
M1.2 is the data foundation → do it second
M1.3 depends on 1 and 2 → do it third
M1.4 depends on 2      → do it fourth
M1.5 depends on 1 and 2 → do it fifth
M1.6 depends on everything → last
```

---

## 9. What is explicitly not done (to stop the scope from spreading)

| Not done | Why |
|---|---|
| **The full banker's algorithm** | It requires knowing the maximum demand in advance, which a workflow with branches cannot do |
| **Script nodes (L3)** | The highest risk and 3 weeks of engineering; do it when there is a concrete requirement |
| **Automatically picking an empty position** | It depends on the device cooperating in real time and is not necessarily reliable; a loop + variables in the workflow is steadier |
| **Preempting a resource** | The plate may be on the robot arm, and preempting would smash it |
| **The physical meaning of `L1`** | The system assumes nothing; `order_in_device` is configured on site |

---

## 10. Open questions (they do not block the start)

| # | Question | Impact |
|---|---|---|
| 1 | Is **inferring** the gate state from the action enough? (or is a query interface needed) | Affects M1.4 |
| 2 | How many channels with **`capacity > 1`** are there on your site? | Affects the acceptance scenarios |
| 3 | Does a result **always** arrive, or **might it not**? | Affects the default value of `onTimeout` |
| 4 | Does a result have a **validity period**? | Affects `validForMs` |
| 5 | **Is there anyone on site who can review Java code**? | Decides whether L3 is feasible |

**None of them blocks M1.1-M1.3** (the expressions, the tables, the variable injection).

---

## 11. In one sentence

**For a workflow to get "moving", four levels of means are used: compute (expressions),
query (result injection), wait (waitFor), branch (a branch).**

**These four cover 90% of the requirements, and all of them are "zero risk".**

**A script is the fallback, it carries the highest risk, and it comes later when there is a
concrete requirement.**

**The resource model has four layers (the "gate" was added), and the position numbering is
an agreement (the order is configured on site).**
