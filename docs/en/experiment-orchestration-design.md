# Experiment Orchestration · Functional Design

> **English edition.** The Chinese original is [`docs/实验编排-功能设计.md`](../实验编排-功能设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 |
| Date | 2026-11-01 |
| Status | Awaiting review |
| Scope | The experiment layer (above the workflow), not including the design of the nodes inside a workflow |

---

## 0. What the experiment layer has to solve

The experiment layer composes several workflows into one experiment in sequence, and draws "what is going to run" before it starts, instead of discovering the conflict only after it has started.

Parallel runs of several experiments have to be designed here. The inside of a workflow already has BPMN; the experiment layer only guarantees sequence, and that the schedule is visible.

---

## 1. The three-layer model

```
Experiment (Experiment)           ← the scope of this document
 └ Workflow (Workflow)           ← BPMN, already there
     └ Node (Node)               ← the device action, already there
```

### 1.1 The boundary of responsibility (the key point)

| Capability | Which layer | Why |
|---|---|---|
| The order of the device actions | Workflow | Already there, native to BPMN |
| The conditional branch | Workflow (gateway) | Already there; an exclusive gateway takes one outgoing edge |
| The loop inside a single workflow | Workflow (loop back) | Natively supported by BPMN |
| **The experiment variables** | **Experiment** | The inputs shared across workflows (temperature, number of plates) |
| **The loop across workflows** | **Experiment** | "N workflows run M rounds", BPMN cannot express it |
| **The dependency between workflows** | **Experiment** | B only runs after A has finished |
| **The declaration of the resource usage** | **Experiment** | The scheduler has to know in advance "which islands this experiment will occupy" |

**In one sentence: the actions and the conditions belong to the workflow, the data and the multiple rounds belong to the experiment, the resource conflicts belong to the scheduler.**

---

## 2. The definition of an experiment

### 2.1 The data structure

```json
{
  "experimentNo": "EXP0812",
  "name": "Incubator plate-out",
  "priority": 50,                    // 0-100, the larger the more important
  "deadline": "2026-11-01T18:00",    // optional, used for the EDD scheduling
  "variables": [
    {"name": "temp",       "type": "number", "defaultValue": "37"},
    {"name": "plateCount", "type": "number", "defaultValue": "3"}
  ],
  "steps": [
    {"seq": 1, "workflowId": 239, "params": {"temp": "${temp}"}},
    {"seq": 2, "workflowId": 648, "loopCount": "${plateCount}",
     "params": {"plateCount": "${plateCount}"}},
    {"seq": 3, "workflowId": 278}
  ]
}
```

### 2.2 The variables

| Type | Supported | Explanation |
|---|---|---|
| `number` | ✅ | Temperature, volume, number of plates |
| `string` | ✅ | Batch number, operator |
| `boolean` | ✅ | Switches |
| Array/object | ❌ | A complex structure belongs in the parameters of a workflow node |

**The reference syntax**: `${variableName}`, it is only substituted in **string values**.

```json
{"params": {"temp": "${temp}", "note": "batch-${batch}"}}
```

**When it is substituted**: before the workflow starts (once, not per node). The reason: the variables do not change during one experiment, substituting them per node is a waste.

### 2.3 The loops

```json
{"seq": 2, "workflowId": 648, "loopCount": "${plateCount}"}
```

| Form | Meaning |
|---|---|
| `null` / omitted | It runs once |
| `"3"` | It runs 3 times |
| `"${plateCount}"` | It runs as many times as the value of the variable |

**2.0 only does single-level loops.** Nested loops (a loop inside a loop) lead to:
- the number of steps explodes after the expansion (3×3=9, 3×3×3=27)
- they are hard to express and to debug in the UI

When nesting really is needed, use the BPMN loop back at the workflow level.

### 2.4 The dependencies (S2-A is already in effect)

The dependencies are no longer expressed with the `runAfter` field, they land in the `experiment_process_edge` edge table (from → to):

```sql
-- experiment_process_edge(tenant_id, experiment_id, from_id, to_id)
```

**The execution order = the Kahn topological sort** (on the same level it is ordered stably by `seq`), a cycle is rejected outright.
Data in the database without edges degrades to ascending `seq` order — exactly the same as before the change.

**The current position is S2-A: only a partial order is promised, the advance is physically serial, wall-clock concurrency is not promised**;
for real concurrency the three resource layers + the wait-for graph have to be connected first. See §4.4.

---

## 3. The interface design

### 3.1 The authoring page (`Authoring` → Experiment Orchestration)

```
┌─ Experiment EXP0812 Incubator plate-out ────────────────────┐
│ Priority [50▾]  Deadline [2026-11-01 18:00]                │
├─ Variables ───────────────────────────────────────────────┤
│ Name         Type      Default   Description               │
│ temp         number    37        incubation temperature    │
│ plateCount   number    3         number of plates          │
│ [+ Add variable]                                           │
├─ Workflow sequence ───────────────────────────────────────┤
│ ≡ 1. Plate-in workflow (2 nodes)   params: temp=${temp} [×]│
│ ≡ 2. ⟳ Repeat ${plateCount} times                          │
│      2.1 Plate-reading workflow (7 nodes)            [×]   │
│      2.2 Plate-transfer workflow (3 nodes)           [×]   │
│ ≡ 3. Plate-out workflow (2 nodes)                    [×]   │
│ [+ Add workflow]  [+ Add loop block]                       │
├───────────────────────────────────────────────────────────┤
│ [Save orchestration]  [Schedule dry run]  [Run]            │
└───────────────────────────────────────────────────────────┘
```

### 3.2 The schedule dry run (a key feature)

Click "Schedule dry run" → nothing is executed, it only computes:

```
┌─ Schedule dry run · EXP0812 ───────────────────────────────────────────────┐
│ Number of steps after expansion: 2 + 7×3 + 3×3 + 2 = 36 steps             │
│ Estimated duration: 18.5 minutes (based on duration_model)                │
│ Devices occupied:                                                         │
│   Incubator_2C-1   00:00-06:00 ─────────────────                          │
│   XRobot A10LR     02:00-08:00    ─────────────────                       │
│   Thermo ATC       06:00-12:00       ─────────────                        │
│ ⚠ Conflict: it contends with EXP0813 for the XRobot (02:00-04:00 overlaps)│
│ [Adjust] [Run anyway]                                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

**The conflict is visible before it runs**, instead of getting stuck half-way through.

### 3.3 The experiment list (with the status)

```
Experiment list

Number    Name                 Status     Progress  Priority  Est.   Actual
EXP0812  Incubator plate-out   running    12/36     50        18.5m  4.2m
EXP0813  CMS review            queued     —         50        9.0m   —
EXP0814  Quality control       completed  8/8       30        3.0m   3.1m
EXP0815  Night shift batch     failed     5/20      10        —      —
```

**The state machine**:

```
draft ──┬─→ queued ──→ running ──┬─→ completed
        │      ↑         │  │     ├─→ failed ──(recovered after manual handling)──┐
        │      │         │  │     └─→ aborted (terminal, cannot be recovered)       │
        │      │         │  └─→ paused ──(resume)                                   ┤
        │      │         │          ↑                                               │
        │      └─────────┴──────────┘ (re-queued, continues from the breakpoint)    │
        └─→ cancelled                                                               │
                                                                                    │
        queued ◀────────────────────────────────────────────────────────────────────┘
```

- `paused`: an **on-site hold** (not the behaviour of a single experiment), see §4.3.
- `failed`: **recoverable** — after a person has dealt with it, it continues from the step that failed.
- `aborted`: **terminal** — a person stopped it, it cannot be recovered (to run it again, submit it again).

---

## 4. The execution semantics

### 4.1 The execution of one experiment (node-driven + the action boundary checkpoint)

```
① Resolve the variables: the default value ← the override value passed in at execution time
② Compile: read experiment_process_edge → the Kahn topological sort gives the steps; the loop blocks are expanded into N copies according to loopCount
③ The baseline for resuming from a breakpoint: read the seq:loop_index with status='completed' in experiment_run_step, and skip them when resuming
④ Advance node by node (physically serial):
     Before every step, first pass the "action boundary gate":
        - A stop request hits  → the experiment becomes aborted (terminal)
        - An on-site hold hits  → the experiment becomes paused (the resources are not released)
     Substitute ${var} → call WorkflowService.execute(workflowId, params)
     The three states of the step result → see §4.2
⑤ Wrap-up: settle() writes the terminal state into experiment_run in one place, and syncs experiment.status
```

**The key point: the check only happens at the "action boundary"** — it does not interrupt an action that has already been sent to the device.
The robot arm is holding a plate, a hard stop in the middle only creates an unknown state on site; so a stop / pause waits at most for the current action to finish.

### 4.2 The failure handling (the three states of the step result)

| Step result | Verdict | Experiment status | Can it be recovered |
|---|---|---|---|
| All the nodes are `COMPLETED` | OK | Continue with the next step | — |
| Some nodes are `MANUAL` / `SKIPPED` | INTERRUPTED | **paused** (waiting for a person) | ✅ After the handling it continues from this step |
| The remaining unfinished nodes | FAILED | **failed** | ✅ After a person has dealt with it, it continues from the step that failed |

**`MANUAL`/`SKIPPED` is a "pause" and not a "failure"**: waiting for human intervention in attended mode is a normal path,
and it must not be judged as failed and then be unable to be picked up again (that was the defect before the change).

**There is no "skip the failure and carry on"**: in a laboratory, skipping a step means the position of the plate is unknown and all the later steps are wrong.

### 4.3 The state changes: pause / stop / resume

#### 4.3.1 The scoped hold (RuntimeHold)

A pause is not "the behaviour of a single experiment", it is a **site-level safety event**: as soon as one experiment is paused on a site (station / island),
the other experiments running in parallel have to stop as well — otherwise the robot arm is still carrying a plate for another experiment and nobody can get in to deal with it.

| Scope | scope_id | Meaning |
|---|---|---|
| `site` | None | The hold of the whole site |
| `island` | island_id | The hold of a whole island (the robot arms inside the island are shared, the whole island has to stop) |
| `experiment` | experiment_id | The hold of a single experiment |

**The priority of the coverage: `experiment` > `island` > `site`**. A hit stops it, the innermost scope gives the reason first.

#### 4.3.2 The three semantics that cannot be given up

1. **A pause is a site-level event**: one experiment is paused → the other running / queued experiments on the same island also fall to `paused`, it does not only stop itself.
2. **It only takes effect at the action boundary**: it does not kill a running action, it waits for the action in progress to finish before stopping (a device action cannot be interrupted).
3. **A hold does not release the resources**: in contrast to the workflow-level `PauseManager` "release what is occupied but unused" — an experiment-level pause requires the site to stay as it is
   and to wait for the human intervention, releasing it on its own would only create a new unknown state.

#### 4.3.3 The three actions

| Action | Target | Behaviour |
|---|---|---|
| Pause | Running / queued | Raise the hold according to the scope → the affected runs fall to `paused` (an action that has already been sent waits for it to finish) |
| Stop | Running | Set `stop_requested`, fall to `aborted` at the next action boundary (terminal, cannot be recovered) |
| Stop | Not running | Fall to `aborted` directly + sync `experiment.status` |
| Resume | paused / failed | Release the innermost hold (**`site` is not released**) → the run goes back to `queued` → it continues from the breakpoint |

#### 4.3.4 Resuming from a breakpoint

- The step identifier: `seq:loop_index` (every round of the loop with the same `seq` can be resumed independently).
- When it resumes, the steps with `experiment_run_step.status='completed'` are skipped.
- The interrupted step falls to `status='paused'` (**not completed**, so it is not skipped the next time and that step is run again).
- `failed` is retried from the step that failed; `aborted` / `cancelled` are terminal, they can only be submitted again.

#### 4.3.5 The interfaces

| Method | Path | Description |
|---|---|---|
| GET | `/api/runtime/holds` | The list of holds that are currently in effect |
| POST | `/api/experiments/runs/{id}/pause` | Pause (query: `scope` / `kind` / `reason`, the default is island + manual) |
| POST | `/api/experiments/runs/{id}/stop` | Stop (returns `stopping` / `aborted`) |
| POST | `/api/experiments/runs/{id}/resume` | Resume (release the hold + continue from the breakpoint) |

#### 4.3.6 The entry point in the UI (added 2026-11-03)

The entry point is in the "Execution history" table on the "Experiment list → click a row → Experiment details" page:

| Location | Content |
|---|---|
| The row action column | Queued / running: **Pause**, **Stop**; paused / failed: **Resume**, **Stop**; nothing is shown for a terminal state |
| The pause dialog | Choose the scope + the reason; the default is `island` (the same as the default of the interface, see §4.3.5), it explains "it only stops at the action boundary, the plate position is not released"; when no island has been assigned the backend degrades automatically to `experiment`, and **the interface returns the scope that actually took effect** (not the requested value) |
| Resume | One click; when it is covered by a `site` level hold it says "still covered by an on-site hold: …, release the hold first" — it does not pretend it has resumed |
| Stop | A second confirmation (running is not interrupted, the action boundary does the wrap-up, a terminal state cannot be recovered) |
| The "On-site hold" card | It calls `GET /api/runtime/holds` and refreshes every 5s; it lists the scope / type / reason / start time, the hold of the current experiment is tagged "Current experiment"; the empty state explicitly says "no hold is in effect" |
| The "Pause reason" column | `pause_kind` (manual pause / device fault / plate position unknown / waiting for manual confirmation) + the reason; when `stop_requested` is set the status column gets a "Stopping" tag |

**Why this card has to exist**: a pause is a "site gate", not the status of one run. Showing only the `paused` of a run
makes a person think "one single job has stopped", while in fact all the experiments that are covered have stopped at the action boundary and the plate positions are not released.

**The trap of the time display**: the hold interface gives an ISO timestamp (`…T10:50:40Z`), most of the other interfaces give a JDBC local-time string;
taking `slice(0,19)` as it is shows the UTC as if it were local time (8 hours off), and the operator misjudges "how long has it been stopped already".
The frontend `fmtTime` normalises both to the local time zone.

**The re-run**: `scripts/verify-exp-control.py` (borrows an empty experiment → inserts 2 queued runs → the UI goes through
pause → resume → stop → 22 assertions → deletes the data and restores the state, it can be run again and again); the read-only smoke test is covered by
`scripts/e2e-smoke.py` T4 (it asserts that the card / the control column exist, the threshold is 16).

### 4.4 The execution order: a partial order (S2-A)

```
① Read experiment_process (seq is only used for the stable ordering of "who runs first on the same level")
② Read experiment_process_edge to get the predecessors -> successors
③ Kahn topological sort:
     - The ones with an in-degree of 0 go into the ready set, on the same level they are taken in ascending seq order
     - If there is a cycle → raise an error and reject it ("the experiment orchestration contains a cycle, the execution order cannot be determined")
④ The loop blocks are copied according to loopCount, the ${var} in the parameters are substituted once before the workflow starts
```

**S2-A only promises a partial order, it does not promise wall-clock concurrency**: one item is taken from the ready set at a time, the advance is physically serial.
So "the same level" currently only shows up as the **dispatch order**, it is not a parallel execution.

**The step identifier of the resume from a breakpoint is `seq:loop_index`**: every round of the loop with the same `seq` can be resumed independently.

---

## 5. Several experiments in parallel (this document only gives the constraints, the algorithm is in another document)

### 5.1 The conflicts that have to be solved

```
Experiment A: uses Incubator_2C-1 (0-6 minutes)
Experiment B: also uses Incubator_2C-1 (2-8 minutes)   ← it collides
```

### 5.2 The resource hierarchy

```
Island (island)        ← coarse: the robot arms inside an island are shared
 └ Device (device)     ← fine: a single device can only have one action at a time
```

**Why there has to be an island-level lock**: the robot arms inside an island carry the plates, and when two experiments run on the same island at the same time the robot arms fight each other — even if they are using different incubators.

### 5.3 The parallel strategy of 2.0 (conservative)

| Strategy | 2.0 | Explanation |
|---|---|---|
| Island-level mutual exclusion | ✅ | **Only one experiment runs on an island at a time** |
| Device-level mutual exclusion | ✅ | A lock is added when a device is shared across islands (rare) |
| Priority preemption | ❌ | No preemption, the high priority one queues |
| Time slice rotation | ❌ | Not done |
| Deadlock detection | ✅ | Timeout + a global order |

**The cost of the island-level mutual exclusion**: the parallelism = the number of islands (26). But this is the **safe lower bound** — the robot arms never fight each other.

**Why there is no parallelism of several experiments inside an island**: it needs a precise model of the "time window in which the robot arm is occupied", and the time data only has 10-second accuracy (a known issue), it cannot be done accurately.

> Wait until the new system has collected 3 months of real duration data, then release the parallelism inside the island.

---

## 6. The data model (the new part)

```sql
-- The experiment variables
experiment_variable (id, experiment_id, name, type, default_value, description)

-- The main experiment table gets new columns
ALTER TABLE experiment ADD COLUMN priority int DEFAULT 50;
ALTER TABLE experiment ADD COLUMN deadline timestamptz;
ALTER TABLE experiment ADD COLUMN started_at timestamptz;
ALTER TABLE experiment ADD COLUMN finished_at timestamptz;

-- The orchestration table gets new columns (already created)
experiment_process (..., loop_count varchar(64), params_override jsonb)

-- The execution records of the experiment (a new table)
experiment_run (
  id, tenant_id, experiment_id,
  status,              -- queued/running/paused/completed/failed/cancelled
  variables jsonb,     -- the actual variable values of this execution
  expanded_steps int,  -- the number of steps after the expansion
  started_at, finished_at,
  current_seq int,     -- which step is currently running
  error text
);

-- The execution record of every step
experiment_run_step (
  id, experiment_run_id, seq, loop_index,
  workflow_id, run_id,     -- the link to workflow_run
  status, started_at, finished_at, error
);
```

---

## 7. The interfaces

| Method | Path | Description |
|---|---|---|
| GET | `/api/experiments/{id}/variables` | The list of variables |
| PUT | `/api/experiments/{id}/variables` | Save the variables |
| GET | `/api/experiments/{id}/processes` | The orchestration list (including loopCount) |
| PUT | `/api/experiments/{id}/processes` | Save the orchestration |
| **POST** | **`/api/experiments/{id}/plan`** | **The schedule dry run (it does not execute)** |
| **POST** | **`/api/experiments/{id}/run`** | **Execute (it enters the queue)** |
| GET | `/api/experiments/{id}/runs` | The execution history |
| POST | `/api/experiment-runs/{id}/cancel` | Cancel |
| GET | `/api/runtime/holds` | The list of on-site holds (site / island / experiment) |
| POST | `/api/experiments/runs/{id}/pause` | Pause (the hold is scoped, see §4.3) |
| POST | `/api/experiments/runs/{id}/stop` | Stop (the terminal state `aborted`, it cannot be recovered) |
| POST | `/api/experiments/runs/{id}/resume` | Resume (release the hold + continue from the breakpoint) |

---

## 8. The phases

| Phase | Content | Depends on |
|---|---|---|
| **2.0** | Variables, single-level loops, sequential execution, island-level mutual exclusion, the schedule dry run | Nothing |
| 2.1 | Changing the variables during the execution, retrying after a failure | 2.0 |
| 2.2 | Priority preemption, deadline scheduling | 2.1 |
| 2.3 | Several experiments in parallel inside an island (it needs real duration data) | 2.2 + 3 months of data |
| 2.4 | Nested loops, conditional blocks | 2.3 |

---

## 9. The connection to the current system

| Existing capability | How it is reused |
|---|---|
| `WorkflowService.execute()` | The experiment executor calls it to run every workflow |
| `run` / `run_node` / `run_command` | One run per workflow, the experiment layer only stores the run_id |
| `device_state` | The scheduler uses it to decide "is the device occupied" |
| Attended mode | The experiment inherits it (the experiment_run stores the attendance) |
| The recovery policy | It is used inside the workflow, the experiment layer does not implement it a second time |
| The alert | A workflow fails → an alert → the experiment is paused |

---

## 10. The known risks

| Risk | Impact | Mitigation |
|---|---|---|
| The duration accuracy is 10 seconds | The schedule dry run is inaccurate | Use p90 instead of p50 |
| The island-level mutual exclusion lowers the parallelism | At most 26 experiments in parallel with 26 islands | Acceptable in 2.0 |
| The variables are substituted before the start | Changing a variable during the execution has no effect | It is stated explicitly on the interface |
| The number of steps explodes after the expansion | 100 steps × 10 rounds = 1000 steps | Limit `loopCount ≤ 20`, the total number of steps ≤ 500 |

---

## 11. Canvas orchestration (added in v0.2)

### 11.1 The complete set of node types

| Type | Colour | Purpose | Execution semantics |
|---|---|---|---|
| **Start** | Green | The entry into the workflow | None |
| **End** | Grey | The exit from the workflow | None |
| **Device** | Cyan | Executes one method on a device | It really calls the driver |
| **Workflow** | Blue | Runs a workflow | A sub-workflow |
| **Variable** | Purple | Defines / assigns | It writes the variable table |
| **Fork** | Orange | Takes one of two paths according to a condition | The condition is evaluated |
| **Parallel** | Green | Runs several paths at the same time | It is constrained by the island-level mutual exclusion |
| **Loop** | Pink | Repeats N times | It is expanded |

### 11.2 Why the start/end nodes have to be brought in automatically

**If they are not brought in automatically, the entry into the execution has to be guessed**:

```java
// This way of writing it picks the wrong node when the graph has a cycle or several starting points
const start = nodes.find(n => !incoming.has(n.id));
```

**With them brought in**:

```
● Start (no incoming edge connection point, physically nothing can be connected to it)
  ↓
... the business nodes ...
  ↓
● End (no outgoing edge connection point, physically nothing can be connected out of it)
```

**The UI rules out the illegal operations right away**, which is better than validating at execution time.

### 11.3 Dragging a device in inserts it automatically

**The problem**: dragging in 5 devices means drawing 6 lines, and a missing one shows up as "the later steps are not executed", without a visible reason.

**The approach**:

```
Drag the device N in
  ↓ Find the edge that points to the "End"
  ↓ Change it to point to N
  ↓ Add an edge N → End
  ↓ Move the End node down by 130px
```

The visual effect is "it is plugged in", and the user does not have to understand the reconnection of the edges.

### 11.4 The three levels of variable passing (the core)

This is what distinguishes the experiment orchestration from "a list of actions".

#### Level ①: static variables

```
Variable node  plateCount = 2
                    ↓
Device node parameters {"cycles": "${plateCount}"}
                    ↓ substituted at execution time
              {"cycles": 2}
```

**When it is determined**: when the graph is built.

**What it is for**: the parameters the user knows in advance (temperature, number of repetitions, number of plates).

#### Level ②: the output of a node

```
Device node A
  Method: BarcodeScan
  Output: plateId=plate      ← configure "take the plateId out of the returned JSON and store it in ${plate}"
       ↓ execution
  The driver returns {"plateId": "PL-2026-001", "code": 0}
       ↓ parsing
  ${plate} = "PL-2026-001"
       ↓
Device node B parameters {"plate_id": "${plate}"}
       ↓
  {"plate_id": "PL-2026-001"}
```

**When it is determined**: after the execution has reached that node.

**What it is for**: the result of the barcode scan, the state of the device, the product of the previous step.

#### Level ③: the evaluation of an expression

```
Variable node  total = ${plateCount} * 2
```

**When it is determined**: it is evaluated at execution time.

**State**: not implemented (it needs an expression engine, and it must not be an eval).

### 11.5 Why level ② needs a "step-by-step execution"

**The current way of executing**:

```
The whole chain → generate the workflow in one go → execute it as a whole
```

**The problem**: when the workflow is generated the variables do not have a value yet — **because nothing has been executed, where would the return value come from**.

**It has to become**:

```
Resolve the 1st step
  ↓ Substitute the parameters (with the current variables)
  ↓ Execute
  ↓ Take the raw response
  ↓ Parse the variables out according to the output configuration
  ↓ Accumulate them into the variable table
Resolve the 2nd step
  ↓ Substitute the parameters with the updated variable table
  ...
```

**The cost**:

| Item | Generate in one go | Step by step |
|---|---|---|
| The execution speed | Fast (one dispatch) | Slow (one dispatch per step) |
| Writing the variables back | ❌ Impossible | ✅ |
| The granularity of the failure retry | The whole workflow | A single step |
| The execution records | One run | N runs (or one run with several nodes) |

**Conclusion**: to have a data flow, the execution has to be step by step. 2.0 accepts the loss of speed.

### 11.6 The format of the output variable configuration

```
plateId=plate
```

- On the left: the name of the field in the JSON the driver returns (a dotted `a.b` is supported to reach a nested field)
- On the right: the name of the variable

**Why not a JSON configuration**:

```json
{"outputs": [{"from": "plateId", "to": "plate"}]}
```

For a single output `k=v` is shorter and one input field on the interface is enough. Several outputs are separated by commas.

### 11.7 The shape at execution time

```
Start
  ↓
Variable plateCount=2           ← write the variable table
  ↓
Device ID0000 / LoadFile      ← execute; if an output is configured it is written back
  ↓
Fork ${plateCount} > 1      ← evaluate it and choose the branch
  ├─ true  → Loop ${plateCount}
  │            ↓
  │          Device ID1731        ← repeated 2 times
  │            ↓
  └─ false → Device ID1775
               ↓
End
```

**Note**: the two paths of the fork eventually have to merge into the "End" (or into a merge node), otherwise one of the paths never finishes.

---

## 12. The technical implementation (the execution model)

### 12.1 The storage of the graph

```sql
experiment_graph (
  experiment_id UNIQUE,
  graph jsonb
)
```

```json
{
  "nodes": [
    {"id":"dev-1","type":"kindNode","position":{"x":240,"y":80},
     "data":{"kind":"device","label":"ID0000","resourceId":200206,
             "capability":"ALIQUOT","ip":"192.0.2.10",
             "method":"LoadFile","output":"plateId=plate"}}
  ],
  "edges": [{"id":"e1","source":"start-1","target":"dev-1"}]
}
```

**Why jsonb and not separate tables**: the shape of the graph changes with the requirements (later there may be "merge", "wait", "sub-workflow"),
and separate tables mean the schema has to be changed frequently; the graph is only read and written as a whole, it is not queried per node, so jsonb is enough.

### 12.2 The execution pipeline

```
① Start from the start node, traverse along source→target (the seen set prevents cycles)
② Handle it according to the type
   ├ start/end   → skip
   ├ variable    → write the variable table (the ${x} in the value is substituted first)
   ├ device      → collect (resourceId, method, output)
   ├ loop        → record loopFrom + the number of repetitions
   ├ fork        → evaluate the condition, choose one outgoing edge
   └ parallel    → skipped in 2.0
③ Expand the loops: after the main chain has been walked, copy steps[loopFrom..] (n-1) times
④ Execute step by step, passing the variables between the steps
```

**Why ③ is done outside the main loop**: a jump back has to change `cur`, and a repeated `cur` is blocked by `seen`,
so the main loop would exit immediately. So the single chain is walked first and the copy is made at the end.

### 12.3 The variable substitution

```java
"${plate}"  →  vars.get("plate")
```

**A variable that cannot be found is kept as it is** (`${plate}`), it is not replaced with an empty string —
at execution time seeing `${plate}` shows at a glance that "the variable is not defined", while an empty string only makes you guess "the parameter was not filled in".

**After the substitution a purely numeric value is turned into a Long**: the driver is sensitive to the type, `"7"` and `7` are not the same thing in some protocols.

### 12.4 The state management of the executor

```java
workflows.withIsland(isl).withVars(vars).execute(id);
finally {
    workflows.withIsland(null);      // forgetting to clear it makes the next request inherit it for no reason
    workflows.withVars(Map.of());
}
```

**Why a field of the Service and not passing the parameters through every layer**: the parameters are already turned into a Map in `loadMethodParams`,
and changing the signature backwards would mean touching 4 methods; one execution is the lifetime of one Service instance.

### 12.5 Step-by-step execution vs generating in one go

| Dimension | Generate in one go | Step by step |
|---|---|---|
| Writing the variables back | ❌ | ✅ |
| The execution speed | Fast | Slow (one dispatch per step) |
| The granularity of the failure | The whole workflow | A single step |

**Conclusion**: to have a data flow between the nodes, the execution has to be step by step, 2.0 accepts the loss of speed.

### 12.6 The safety boundary

**`eval` / `Function()` is not used to evaluate the conditions and expressions**:

The content of the canvas comes from the user input (it is stored in the database, it may be copied and pasted), `eval` is handing the browser over to somebody else to write code.

Only the fixed form is supported:

```
left operand  operator  right operand                (a condition)
"${a} > 37"  → parse → a > 37
```

**The limit of 20 on the number of repetitions**: `Math.min(20, ...)`, it prevents a `${n}` of 10000 from blowing up the workflow.

### 12.7 What is still to be implemented

| Item | Explanation |
|---|---|
| Writing the output variables back | After each step, the fields are parsed out of the raw driver response and written into the variable table |
| The evaluation of expressions | The arithmetic of `${a} + ${b}`, it needs an expression engine |
| The merge point of a fork | Right now the two paths of the fork both point to the end, there is no "carry on after the merge" |
| Parallel | It is constrained by the island-level mutual exclusion |

---

## 13. The implementation details of the frontend and the backend (v0.3)

### 13.1 The overview of the data flow

```
┌─ Browser ──────────────────────────────────────────────────────────┐
│ ExperimentCanvas.tsx                                                │
│   ├ Node state nodes[] (with position/data)                         │
│   ├ Edge state edges[] (with source/target/marker)                  │
│   └ Property panel (select a node → edit data)                      │
└───────────────┬────────────────────────────────────────────────────┘
                │ GET/PUT /api/experiments/{id}/graph
                ↓
┌─ Backend ──────────────────────────────────────────────────────────┐
│ experiment_graph table (jsonb: {nodes, edges})                      │
└───────────────┬────────────────────────────────────────────────────┘
                │ POST /api/experiments/flow-run
                ↓
┌─ Execution ────────────────────────────────────────────────────────┐
│ ExperimentFlowRunner                                                │
│   ├ Step by step: substitute ${var} → ShadowProbe → parse the output│
│   └ Persist: run + run_node + run_command                           │
└────────────────────────────────────────────────────────────────────┘
```

### 13.2 The state design of the frontend

| State | Type | Description |
|---|---|---|
| `nodes` | `Node<NodeData>[]` | The React Flow nodes, with `position` and `data` |
| `edges` | `Edge[]` | The edges, with `markerEnd` (the arrow) and `label` (the delete hint) |
| `selected` | `Node \| null` | The node that is currently selected, it decides the property panel on the right |
| `expId` | `number` | The current experiment, stored in localStorage to remember the last one |
| `dirty` | `boolean` | There are unsaved changes |

**The fields of `NodeData`**:

```ts
interface NodeData {
  kind: 'start' | 'end' | 'device' | 'workflow' | 'variable'
      | 'fork' | 'parallel' | 'loop';
  label: string;
  // A device node
  resourceId?: number;       // resource_node.id
  capability?: string;       // used to pick the photo
  ip?: string | null;
  method?: string;           // which method is executed
  params?: Record<string, string>;  // the method parameters, the values support ${var}
  output?: string;           // "plateId=plate": take the returned field and store it in a variable
  // A variable node
  value?: string;            // "plateCount=2"
  // A fork node
  condition?: string;        // "${eq} == 214"
}
```

### 13.3 Dragging and the automatic connection

**Dragging a logic node in** (`onDrop`):

```ts
const kind = e.dataTransfer.getData('application/x-node-kind');
// the drop point = the mouse position - the container offset - half the width and height of the node
```

**Dragging a device in** (the other branch of `onDrop` + the automatic insertion):

```ts
const rid = e.dataTransfer.getData('application/x-device');
// autoInsert:
//   1. Find the edge that points to end
//   2. Change it to point to the new node
//   3. Add an edge new node → end
//   4. Move end down by 130px
```

**Why the HTML5 drag and not the drag API of React Flow**: the panel on the left is not part of the canvas,
the HTML5 drag is more direct and it does not depend on the internal state of React Flow.

**A device can be dragged in repeatedly**: the node id uses `dev-{rid}-{Date.now()}`.
The robot arm can be used 5 times in one workflow, so the resource id must not be used to deduplicate.

### 13.4 The design of the edges

```ts
const EDGE_STYLE = {
  animated: true,
  style: { stroke: '#3b82f6', strokeWidth: 2 },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#3b82f6',
               width: 18, height: 18 },   // ← the arrow
  label: 'Double-click to delete',
};
```

**The three ways to delete**:

| Way | Implementation |
|---|---|
| Double-clicking the connection | `onEdgeDoubleClick` → it is filtered out |
| Clicking to select + Delete | `deleteKeyCode={['Backspace', 'Delete']}` |
| Deleting a node | All of its edges are deleted with it |

### 13.5 The shape of the nodes

| Type | Implementation |
|---|---|
| Fork | `clipPath: polygon(12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%, 0 12px)` |
| Parallel | `borderStyle: 'double', borderWidth: 4` |
| Loop | `borderLeftWidth: 6` |
| Device | A thumbnail + a colour bar for the capability |

**A symbol is also added in front of the title** (`◇`/`∥`/`↻`) — the pure shapes cannot be told apart when they are small, the symbol is more reliable.

### 13.6 The device thumbnail

```tsx
{data.capability && PHOTO_CAPS.has(data.capability) && (
  <img src={`/devices/${data.capability}.jpg`}
       onError={(e) => { e.currentTarget.style.display = 'none'; }} />
)}
```

**Where the photos come from**: the repository contains no images. `public/devices/` is named after the capability, you can put the photos in yourself; when a photo is missing the card hides the image area automatically
(`INCUBATE.jpg` / `TRANSFER.jpg` / ...).

**Hide it when it fails**: the capabilities without a photo (`SENSE`/`WASH`) or a failed load are not shown, they do not take up space.

### 13.7 The execution: the frontend computes the path, the backend runs the steps

**Why this division of labour**:

| Logic | Where it goes | The reason |
|---|---|---|
| The evaluation of the fork, the expansion of the loops, the substitution of the variables | Frontend | It has to give the user immediate feedback ("the parallel node is skipped") |
| The real execution, the persistence to the database | Backend | It has to write run/run_node/run_command, the frontend cannot do that |

**The transport format of the steps**:

```
id:method:output:k1=v1;k2=v2
200214:Home:equipment_id=eq
200210:Deposit1::plate_id=${plate};cycles=5
```

**Why a compact string and not JSON**: passing JSON in a URL query means double encoding,
and what you see while debugging is all `%7B%22`, so every change means decoding it once. The compact format is much more readable.

### 13.8 The backend: ExperimentFlowRunner

```java
public Result run(String name, List<Step> steps, Map<String, String> vars)
```

**Every step does five things**:

```java
1. Insert run_node (status=running)     // even after a crash you can see "where it got stuck"
2. Parse the parameters: k=v;k2=v2, the ${var} in the values is substituted
3. Run the ShadowProbe (it wants the instanceId, not the resourceId!)
4. Update run_node + insert run_command (including the raw driver response)
5. Parse the output variables: take the field out of the raw response → write vars
```

**The trap of the two sets of ids**:

```java
// The graph / the execution records use resource_node.id (200214)
// The driver / the probe want device_instance.id (214)
Long instId = instanceIdOf(st.resourceId());
```

**This trap has been stepped on four times in the project** (the candidate pool, the driver registry, the plate animation, here),
so `instanceIdOf()` is a separate method with a comment on it.

### 13.9 The mapping of the enum values (the traps that have been stepped on)

| Table.column | Enum name | The possible values |
|---|---|---|
| `run.mode` | `run_mode` | `normal` / `simulation` / `attended` / `unattended` |
| `run.status` | `run_status` | `draft`/`pending`/`planned`/`queued`/`running`/`paused`/`completed`/`failed`/`aborted`/`skipped` |
| `run_node.status` | `node_status` | `pending`/`ready`/`running`/`completed`/`failed`/`skipped`/`aborted`/`manual` |

**`run.status` and `run_node.status` are two different enums** — writing the wrong one reports
`column status is of type run_status but expression is of type node_status`.

### 13.10 The regular expression of the output variable parsing

```java
// For:
.compile("\"" + field + "\"\\s*:\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?)")
```

**After the expansion**: `"equipment_id"\s*:\s*("..."|number)`

**The symptom of the wrong version**: in the log `output` and `raw` are both normal, but `extractField` returns null —
because after one extra level of escaping `\\s` becomes "a literal backslash + s", and it never matches.

**The compiler does not report this kind of error**, it can only be found by the tests.

### 13.11 What is still to be implemented

| Item | The impact on the frontend and the backend |
|---|---|
| The merge point of a fork | The frontend gets a "merge" node type; the path algorithm of the backend has to support merging several paths |
| The evaluation of expressions | The frontend already has a condition evaluator, it is extended to arithmetic |
| The refinement of the parameter types | The frontend already renders by control_type, the source of the options of `select` still has to be confirmed |
| Real concurrency for the parallel nodes | The scheduler of the backend has to release the island-level mutual exclusion |

---

## 14. The transport of the plates: how the robot arm is called automatically

### 14.1 The problem

The user connects `Device A → Device B` and expects "the plate is carried from A to B".

But one arrow **only stands for one execution**, while a real transport is:

```
A puts the plate  →  the robot arm grabs, moves and places it  →  B takes the plate in
```

**If every arrow only executes the method of A or of B, the plate never gets across.**

### 14.2 The key discovery: the robot arm has a Transfer method

```
ID1731 (XRobot A10LR)
  ├ Transfer            source(select, required) target(select, required) consumable(number)
  ├ Pick / Place        grab / put down
  ├ Move                move
  ├ GripAction          the gripper action
  └ Home / ReturnToSafe go home
```

**`Transfer(source, target)` is exactly the atomic operation "carry it from A to B".**

### 14.3 The rule of the automatic expansion

When it walks over the device nodes on the path, it checks the **two adjacent stations**:

```
The previous station is not a robot arm && this station is not a robot arm
  ↓
Insert in between: robotArm.Transfer(source=the code of the previous station, target=the code of this station)
```

**Why it is only inserted when neither end is a robot arm**:
- If the previous station is a robot arm itself (e.g. it has just finished carrying), there is no need to insert another one
- If this station is a robot arm, it moves by itself, it does not need to be "carried over"

### 14.4 The measurement

Graph: `Start → ID1731(robot arm) → Fork → ID1775(incubator) → Merge → ID1183(incubator) → End`

The execution records:

| # | Method | Parameters | Explanation |
|---|---|---|---|
| 1 | `Home` | resourceId=200214 | The robot arm goes home (configured explicitly by the user) |
| 2 | `Deposit1` | resourceId=200210 | ID1775 puts the plate in |
| 3 | **`Transfer`** | **source=ID1775, target=ID1183** | **The transport inserted automatically** |
| 4 | `Deposit1` | resourceId=200171 | ID1183 takes the plate in |

**The 3rd step is filled in by the system automatically**, and the parameters use the codes of the devices at both ends.

### 14.5 How the robot arm is found

```ts
const arm = islandDevices.find((x) => x.capability === 'TRANSFER');
```

**It takes the first device with the TRANSFER capability on the current island**.

**Limitation**: when an island has several robot arms, the first one is always used. To control it precisely you need:
- a configuration "which robot arm carries it" on the device node
- or to insert a robot arm node explicitly on the canvas (`A → robot arm → B`)

### 14.6 Which value is used for source/target

What is passed is the **device code** (`ID1775` / `ID1183`), not the position code.

**Why**: the parameters of `Transfer` are of the `select` type, and the source of the options has not been confirmed yet.
Passing the device code is the most intuitive — the robot arm knows "where the machine ID1775 is".

**If the driver wants the position code** (e.g. `I4495`), you need:
1. The frontend takes the first free position out of the layers of the device
2. Or the mapping is done on the driver side

### 14.7 What has not been done (honestly)

| Item | Explanation |
|---|---|
| Automatically adding "A puts the plate in / B takes the plate in" | Right now only `Transfer` is inserted, the actions of A/B themselves are configured by the user |
| The choice of the robot arm | The first TRANSFER on the island is always used |
| The format of the source/target values | The device code is passed, whether the driver accepts it still has to be verified on site |
| The protection against a wrong grab | The plate should not be carried when it is on an empty device — a `has_plate` pre-check is needed |
| Automatic transport insertion in the backend run path | The designer inserts `Transfer` per §14.2/§14.3 when a graph is built, but `WorkflowBuilder` / `GraphRunner` / `POST /api/experiments/flow-run` do not add it at run time; a separate batch is needed |

**Added on 2026-11-05 (the designer's graph-building path)**: `webapp/src/pages/Designer.tsx`, when methods from two different devices are dropped in sequence, takes the island's TRANSFER device from the same device pool, inserts a `Transfer` node and connects "device A → robot arm → device B" with `modeling.connect`, with `source/target` set to the two device codes (still the format caveat in the table above). `scripts/e2e-method-drop.py` now asserts "cross-device → Transfer inserted + connected + persisted" as a resident check. Note this is **not** the same as automatic transport insertion during execution: the new backend row above remains open, and there is still no combined assertion of "source position → target position + `run_command` containing Transfer" for `position_state` / `plate_location_audit`. Demo: workflow 1885 (island `LAB-DEMO-02`, `ID0000` → `ID1731` → `ID1183`).

---

## 15. The robot arm points and the plate position pre-check

### 15.1 Why the points are needed

The parameters of the robot arm's `Transfer(source, target)` are `select`, and the value is **not the device code but the point code**.

The procedure on site:

```
Teach the robot arm → drag it in front of every device → note the coordinates → save them as a point (e.g. PT-001)
     ↓
Export the point table (Excel / XML)
     ↓
Import it into the robot_point table of this system
     ↓
In the workflow: Transfer(source="PT-001", target="PT-002")
```

**Why not the device code**: the robot arm only knows the "coordinates", it does not know "which device this is".
A point is the name of "the coordinates of a device".

### 15.2 The table structure

```sql
robot_point (
  island_id, robot_resource_id, code, name,
  x, y, z,                    -- the coordinates (obtained by teaching)
  device_resource_id,         -- which device it is linked to
  action,                     -- Pick / Place / PickPlace
  synced_at                   -- the time of the last sync
)
UNIQUE (tenant_id, robot_resource_id, code)
```

**The purpose of `device_resource_id`**: it lets the interface show "PT-001 = the incubator ID1775"
instead of a string of coordinates. It is understandable when the user picks a point.

### 15.3 What to do when there is no point table at the beginning

`POST /api/islands/{id}/robot-points/sync?robotResourceId=` is provided:

```sql
INSERT INTO robot_point (island_id, robot_resource_id, code, name, device_resource_id, action)
SELECT ?, ?, d.code, d.name, d.id, 'PickPlace'
FROM resource_node d
WHERE d.parent_id = ? AND d.kind = 'device' AND d.id <> ?
```

**The point code is the device code directly** (`ID1775`) — so that `Transfer` works when the source/target is given the device code,
without having to wait for a point table from the site.

**Later**: after the teaching on site, the real coordinates are written back into `x/y/z`, and the `code` can be changed to something like `PT-001`.

### 15.4 The plate position pre-check

**The problem**: the robot arm raises an error when it grabs nothing, but the error message is "the grab failed" — on site you have to guess whether the point is wrong or the plate is not there.

**The approach**: before the `Transfer` is executed, look up the `device_state.has_plate` of the source device.

```java
if (method.startsWith("Transfer")) {
    Object src = params.get("source");
    // look up has_plate, block it if it is false
    return src + " 上没有板，不能搬";
}
```

**The measurement**:

| Scenario | The result |
|---|---|
| The source device has no plate | `failed`: `ID1775 上没有板，不能搬` |
| The source device has a plate | `completed` |

**Why it is only looked up before a Transfer**: `Home`/`Deposit` do not involve "grabbing it from somewhere",
and looking it up there would block it wrongly (a Deposit puts a plate into the device, and the source device naturally has no plate).

**A failed pre-check does not block the persistence**: the `run_node` is still inserted (status=failed, error_code=EC-PLATE-PRECHECK),
so the operations can find out that "it was the pre-check that stopped it, not an error of the device".

### 15.5 The trap of the parameter format

The format of `steps` is `id:method:output:params`, **four segments**.

```
200214:Transfer::source=ID1775;target=ID1183
              ↑↑
              two colons: the output is empty
```

**Writing it with three segments** (`200214:Transfer:source=...`) takes the parameters for the `output`,
`params` is empty, and the pre-check cannot get the source and silently lets it pass — **it shows up as "the pre-check did not take effect"**.

**This trap is very well hidden**: it does not raise an error, the pre-check simply is not triggered.

### 15.6 The teaching of the points (the interface)

At the bottom of the property panel of a device node:

```
Robot arm point (teaching)
Point code = device code, fill in the coordinates where the robot arm is currently standing in front of that device
  [X: ____] [Y: ____] [Z: ____]  [Record point]
```

Click "Record point" →

```bash
PUT /api/islands/{id}/robot-points/{code}
    ?action=PickPlace&deviceResourceId=200210
    body: {"x": 420}
→ {"updated": 1, "code": "ID1775"}
```

**How it is used on site**:

```
① On the teaching device of the robot arm, drag the robot arm in front of ID1775
② Read the coordinates on the teaching device (or click "read the current position")
③ Select the ID1775 node on the canvas, fill in the coordinates, click "Record point"
④ The x/y/z of that entry in the point table are updated
```

**Why the coordinates are not drawn on the canvas**: the robot arm is three-dimensional (x/y/z + the pose),
the canvas is two-dimensional and drawing it would mislead. **Filling in the values is more accurate**.

**The meaning of `updated=0`**: this point does not exist yet. You first have to click "Sync points" in the island view,
or call `POST /robot-points/sync` directly.

### 15.7 What is still to be implemented

| Item | Explanation |
|---|---|
| The import and export of the points | Excel / XML (the teaching device on site can usually export a table) |
| Reading the coordinates from the teaching device | Add a `GetPosition` method call and write the result back directly |
| The point coordinates take part in the dispatch | Right now only the code is used, x/y/z is only a record |
| Several robot arms | The table has `robot_resource_id`, but the interface only handles the first one |
| The pose parameters | The robot arm usually also needs rx/ry/rz, they are not in the table |
