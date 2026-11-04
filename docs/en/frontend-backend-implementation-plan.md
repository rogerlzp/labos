# Frontend / Backend Implementation Plan: Automatic Compilation / Gantt Forecast / Ready Scheduling

> **English edition.** The Chinese original is [`前后端实施方案_编译甘特就绪调度.md`](../../前后端实施方案_编译甘特就绪调度.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **The Chinese original marks this document as superseded.** The current behaviour is
> described in [`docs/en/roadmap.md`](roadmap.md) and in the code.

> **The weekly plan is superseded (2026-10-29).** The scheduling is governed by chapter 11 of
> [`LabOS功能规格说明书.md`](../../LabOS功能规格说明书.md). The automatic compilation's "expanded at run
> time, not landed as BPMN nodes" is still valid and has been written into section 7.4 of the
> specification. The Gantt and the nest-level ready scheduling are not in the 2.0 release gate.

> Date: 2026-10-29
> The decision is made: all three are done (the automatic compilation depends on the capability templates, the Gantt forecast, the ready pull scheduling)

---

## Zero. The difference in difficulty between the three things, stated up front

| Feature | The core difficulty | Dependency | Estimate |
|---|---|---|---|
| **Automatic compilation** | The quality of the templates (whether they can cover the real nodes) | 6 templates already mined from the history | 1 week |
| **Gantt forecast** | The duration model (historical time fields contain dirty data) | Use only cleaned completed samples | 1.5 weeks |
| **Ready pull** | **The plate-level model is missing** (we have no concept of "where the plate is") | The plate tracking has to be built anew | 2-3 weeks |

**It has to be said up front**: the full form of the ready pull depends
on "the plate as a first-class citizen" —— which nest holds each plate and where it goes next all
have to be queryable.

We currently have **node-level execution** (node → capability → device) and no plate model.
So the complete ready scheduling requires the plate tracking to be filled in first. That is 2-3
weeks, not 3 days.

**The suggested compromise**: first do the **device readiness decision** (which does not involve
the plate), as a guard before the execution; the plate-level tracking goes into the second phase.

---

## I. Automatic compilation

### 1.1 What is already done

```sql
-- The templates mined from the 110,000 historical commands
capability_recipe (tenant_id, capability_id, seq, method, param_expr, criteria, origin, sample_count)
```

The 6 templates (origin=mined, with the number of historical samples):

| Capability | Template | Samples |
|---|---|---:|
| WASH | `CarrierOutDestin → Transfer → CarrierInDestin` | 327 |
| STORE | `OutputByType → LoadLabware → Transfer → Transfer` | 318 |
| INCUBATE | `StartShaking → Incubate → StopShaking → Transfer×3` | 255 |
| READ_PLATE | `OpenDoor → Transfer → CloseDoor → ReadPlate → OpenDoor → Transfer → CloseDoor` | 198 |
| CENTRIFUGE | `RunProgram → CloseLid` | 119 |
| ALIQUOT | `RunMethod → RunMethod` | 105 |

### 1.2 The key design decision: **expanded at run time, not landed as nodes**

| Option | Advantage | Disadvantage |
|---|---|---|
| Expanded into sub-nodes at compile time | Visible and editable in the Advanced tab | **It changes the data structure of the workflows**, which is risky |
| **Expanded at run time** | The data is untouched and the templates can evolve independently | The expansion result is only visible at execution time |

**Choose the run-time expansion**, but provide a **compilation preview** (the frontend can see
"this node will expand into 7 steps"), so that both are covered.

### 1.3 The data flow

```
workflow_node (capability=READ_PLATE)
   ↓ compile (preview / at execution time)
capability_recipe (7 steps)
   ↓ generated per step
DriverCommand × 7
   ↓ persisted
run_command × 7 (command_seq=1..7)
```

### 1.4 The backend interfaces

```java
// The compilation preview (does not execute)
GET /api/workflows/{id}/compile
→ {
    "nodes": [
      { "nodeKey": "Activity_x", "capability": "READ_PLATE",
        "steps": ["OpenDoor","Transfer","CloseDoor","ReadPlate","OpenDoor","Transfer","CloseDoor"],
        "source": "recipe", "sampleCount": 198 },
      { "nodeKey": "Activity_y", "capability": "TRANSFER",
        "steps": ["Transfer"], "source": "direct" }   // no template → a single step
    ],
    "totalSteps": 8
  }

// The compilation of a single node (for the side panel of the designer)
GET /api/capabilities/{code}/recipe
→ { "capability": "READ_PLATE", "steps": [...], "sampleCount": 198 }
```

### 1.5 The frontend

Add a **properties panel** on the right of the designer: select a node → it shows

```
Node: Activity_0b8t6do
Capability: READ_PLATE        [source: explicit]
Will expand into 7 steps (based on 198 historical samples):
  1. OpenDoor
  2. Transfer
  3. CloseDoor
  4. ReadPlate
  5. OpenDoor
  6. Transfer
  7. CloseDoor
```

### 1.6 The change to the execution

In the `RunStateMachine`, when a node executes:

```java
List<String> steps = compiler.compile(node);   // the recipe or a single step
for (int i = 0; i < steps.size(); i++) {
    DriverCommand cmd = new DriverCommand(..., steps.get(i), ...);
    result = driver.execute(cmd);
    if (!result.ok()) throw new StepFailure(...);   // the whole node fails
}
```

**The failure semantics**: if any step fails → the whole node fails (it is handled by the
existing recovery policy; with REASSIGN the device is changed and all the steps are re-run).

> There is a real problem here: **after the 3rd step fails and the device is changed, do the first
> 2 steps have to be re-run?**
> One approach is to re-run the whole procedure (the idempotency is guaranteed by the driver).
> We do the same: changing the device = the state of the new device may differ, so it has to run
> from the beginning.

---

## II. The Gantt forecast

### 2.1 Where the durations come from

**The historical measurement is preferred**; if there is none, `estimate_sec` is used; if there is
none either, the capability default is used.

```sql
-- The duration distribution of each method (cleaned: 1 second ~ 12 hours)
SELECT method,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY duration) AS p50,
       percentile_cont(0.9) WITHIN GROUP (ORDER BY duration) AS p90,
       count(*) AS samples
FROM (
  SELECT method,
         EXTRACT(EPOCH FROM (finished_at - sent_at)) AS duration
  FROM run_command
  WHERE status = 'completed' AND finished_at > sent_at
    AND EXTRACT(EPOCH FROM (finished_at - sent_at)) BETWEEN 1 AND 43200
) t
GROUP BY method
HAVING count(*) >= 5;
```

### 2.2 The forecast algorithm (the key: serial + parallel)

```
Input: the node list + the dependency edges + the estimated duration of each node + the device allocation
Output: the [forecast start, forecast end] of each node

The algorithm (list scheduling):
  t = 0
  while there are still unscheduled nodes:
      take all the nodes whose "predecessors are complete"
      for each node:
          find the earliest available device by capability
          start = max(predecessor completion time, device idle time)
          end = start + estimated duration
          device idle time = end
```

### 2.3 The backend interface

```java
POST /api/workflows/{id}/simulate
→ {
    "makespanSec": 412,
    "nodes": [
      { "nodeKey": "...", "resourceId": 171, "startSec": 0, "endSec": 12, "method": "Deposit1" },
      ...
    ],
    "devices": [ { "resourceId": 171, "busySec": 320 }, ... ],
    "confidence": "historical"   // historical / estimate / default
  }
```

### 2.4 The Gantt on the frontend

**No charting library is introduced**; it is drawn with native SVG:

```
Time axis 0 ────────────────────────────── 412s
Device 171  ████      ████        ██
Device 183      ██████      ████
Device 191            ████          ████████
```

The structure of the component:

```html
<div class="gantt">
  <div class="gantt-row" data-device="171">
    <span class="gantt-label">Incubator_2C-1</span>
    <div class="gantt-track">
      <div class="gantt-bar" style="left:0%;width:8%" title="Deposit1 12s"></div>
    </div>
  </div>
</div>
```

Two tabs: **Forecast** (the result of simulate) / **Actual** (the actual_start/end of run_node).

---

## III. The ready pull scheduling

### 3.1 What has to be filled in first: the device state

```sql
CREATE TABLE device_state (
  tenant_id      bigint NOT NULL,
  resource_id    bigint NOT NULL,
  status         varchar(16) NOT NULL DEFAULT 'idle',   -- idle / busy / error / offline
  has_plate      boolean NOT NULL DEFAULT false,        -- whether the nest holds a plate
  current_run_id bigint,
  ready_give     boolean NOT NULL DEFAULT false,
  ready_take     boolean NOT NULL DEFAULT true,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, resource_id)
);
```

**The three-state decision**:

```
if (status='busy')          → busy
else if (has_plate)         → a plate is present and can be taken
else                        → the nest is empty and can receive a plate
```

### 3.2 The readiness decision (as a guard, not involving the plate flow)

Before a node is executed:

```java
// The target device must be able to receive a plate
if (!deviceState.readyToTake(resourceId)) {
    throw new StepFailure("EC-07-0002", RecoveryScope.NODE,
        "The device " + resourceId + " is not ready (busy/has_plate)");
}
```

This step comes first; it blocks "stuffing a task into a busy device".

### 3.3 The pull scheduling loop (the second phase)

```java
while (running) {
    for (each node waiting to be executed) {
        if (the node's predecessors are complete && the target device can receive a plate) {
            allocate the device → submit the execution
        }
    }
    sleep(200ms)   // the polling interval
}
```

**The difference from the existing state machine**:

| | Now (push) | Pull (pull) |
|---|---|---|
| The driver side | The state machine traverses the nodes in sequence | It claims actively when the device is ready |
| Suitable for | Serial workflows | Multi-plate parallelism, device pipelining |
| Deadlock | Possible | Averted by nature by the readiness decision |

### 3.4 The plate tracking (the third phase)

New tables are needed:

```sql
CREATE TABLE plate_location (
  plate_id    bigint PRIMARY KEY,
  resource_id bigint,          -- which device holds it now
  nest        varchar(16),
  status      varchar(16),     -- on_deck / in_process / stored / done
  updated_at  timestamptz
);
```

**This is 2-3 weeks of work**, because it has to be hooked into every device action (taking the
plate / putting the plate down / storing / taking it out of the store).

---

## IV. The implementation order (suggested)

### Week 1: the automatic compilation

| Task | Deliverable |
|---|---|
| The backend `RecipeCompiler` | Node → step sequence |
| `GET /api/workflows/{id}/compile` | The preview interface |
| The properties panel of the designer | It shows "will expand into N steps" |
| `RunStateMachine` supports the multi-step execution | One node runs several commands |
| It writes `run_command` (command_seq=1..N) | Traceable |

**Acceptance**: drag a READ_PLATE node, the side panel shows 7 steps; at execution time 7
run_command rows are produced.

### Week 2: the Gantt forecast

| Task | Deliverable |
|---|---|
| The duration model (`MethodDuration`) | The p50 query + the cache |
| `WorkflowSimulator` | The list scheduling computes the forecast timeline |
| `POST /api/workflows/{id}/simulate` | It returns the node intervals |
| The SVG Gantt on the frontend | The forecast/actual dual tab |

**Acceptance**: click "Forecast" on any workflow, the Gantt chart comes out, and the deviation of
the total duration from the actual history is < 30%.

### Week 3: the device readiness + the pull scheduling (the first version)

| Task | Deliverable |
|---|---|
| The `device_state` table + the decision logic | The three states |
| The readiness guard before the execution | It blocks the busy devices |
| The pull loop (the single-plate scenario) | The device claims when it is ready |
| The device status panel on the frontend | Green / yellow / red |

**Acceptance**: inject "the device is busy", the task is blocked instead of being stuffed in.

### Week 4 (the second phase): the plate tracking

A business confirmation is needed on where the "plate" lands in the database (we already have
`plate` + `plate_well`, but not "which device holds the plate").

---

## V. The three technical judgements that must be confirmed first

**1. After the compilation, what is the relation between `run_node` and `run_command`?**

Suggestion: `run_node` remains "one business node" (such as READ_PLATE), and `run_command` is the
7 commands under it. This way the historical data (113,570 run_node) and the new multi-step
execution can be aligned.

**2. When the device is changed and it is re-run, from which step does it start?**

Suggestion: **run it from the beginning**. Because the physical state of the new device is unknown
(for example the door may already have been opened). The cost is that actions may be repeated, but
it is safe.

**3. Should the "device allocation" of the Gantt forecast be adjustable?**

First phase: use the candidate devices of the current workflow node (take the first one). Second
phase: let the user drag the device manually and recompute the Gantt.

---

## VI. The connection to the existing code

| Existing | Change |
|---|---|
| `RunStateMachine` | The node execution becomes a "multi-step loop", the rest is unchanged |
| `DriverStepExecutor` | It gains `executeStep(node, method, seq)` |
| `WorkflowService.execute` | It writes the run_command (today it only writes the run_node) |
| `web/designer.html` | It gains the right-hand properties panel (showing the compilation preview) |
| `web/index.html` | It gains the Gantt tab |

**All the changes are incremental; they do not overturn the existing 55 tests.**
