# Functional Document · LabOS Laboratory Control Console

> **English edition.** The Chinese original is
> [docs/en/review-20261103/01-functional.md](01-functional.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Audience: user / product / review
> Companions: [Technical document](02-technical.md) · [Design document](03-design.md) · [Testing document](04-testing.md)

---

## 1. What this system does

In one sentence: **orchestrate the laboratory's instruments with a "flowchart", drive the devices automatically according to the graph, recover by policy when something fails, and stop and call a human when it cannot be cured.**

Split into three things:

| Layer | What it does | What the user touches |
|---|---|---|
| **Device layer** | Brings in the S100 plate sealer, the ASpin 2K centrifuge, the incubator, the robot arm etc., and unifies them into "capability + method" | the device cards in the island view, the method list behind "Details" |
| **Workflow layer** | Orchestrates the process with a BPMN graph (node = one operation, edge = the order), supports parallelism, conditional branches and loops | workflow library → canvas |
| **Execution layer** | Sends the commands along the graph, collects the results, judges the errors, retries / switches device / hands over to an operator, and keeps a trace the whole way | run detail (flowchart + node table + event stream) |

---

## 2. Who uses it and how

| Role | Main actions | Entry point |
|---|---|---|
| **Process / application engineer** | Builds workflows, orchestrates experiments, runs experiments | Authoring → Workflows / Experiments |
| **On-site operator** | Sees where the execution has got to, clicks continue after replacing consumables, counts plate positions | Run → Live Board / Paused Queue / Plate Inventory |
| **Device / operations engineer** | Onboards devices, deploys them onto islands, watches alerts, adjusts error-code policies | Operations → Devices & Islands / Island View / Alerts / Error Codes |
| **Administrator** | Users, audit | System → User Management / Audit Log |

---

## 3. Page map (menu → route → function)

The frontend has **24 business routes** in total (the table has 23 rows, where the "Experiment Canvas" row carries the two paths `/canvas` and `/canvas/:id`) (there is also the root route `/`, which redirects to `/runs` by default). The menu is split into four groups: **Authoring / Run / Analysis / Operations / System**.

| Menu group | Menu | Route | One-line description | Source file |
|---|---|---|---|---|
| Authoring | Workflow Library | `/workflows` | All the process flowcharts: new / search / preflight / execute / shadow check; click a row to enter the canvas | `Workflows.tsx` |
| Authoring | Workflow Designer | `/designer/:id` | The single BPMN graph editor (bpmn-js): drag nodes, draw edges, bind a device + method + parameters | `Designer.tsx` |
| Authoring | Experiment List | `/experiments` | The experiment list: code / name / **island** / **version** (+1 on every save of the orchestration) / number of workflows / status | `ExperimentList.tsx` |
| Authoring | Experiment Orchestration | `/experiments/:id` | The orchestration of one experiment: workflows ordered by `seq` / variables / execute / pause / continue / stop; "Canvas" jumps to the experiment canvas | `Experiments.tsx` |
| Authoring | Experiment Canvas | `/canvas`, `/canvas/:id` | The React Flow canvas: the graphical version of the same experiment (workflows / variables / splits / parallelism / loops / merges). **⚠️ today the canvas only stores the drawing, execution still follows the workflow table** | `ExperimentCanvas.tsx` |
| Authoring | Experiment Canvas (with experiment context) | `/experiments/:id/canvas` | As above; when entered from the experiment detail it keeps the experiment context | `ExperimentCanvas.tsx` |
| Run | Live Board | `/runs` | Which card the running jobs are stuck on; "resume from the breakpoint" after a crash; watchdog mode toggle | `Runs.tsx` |
| Run | Run Detail | `/runs/:id` | **Flowchart (coloured by status) + node table (with error codes) + event stream** | `RunDetail.tsx` |
| Run | Scheduler Board | `/scheduler` | Island locks / queue / ETA / worker pool: the duty operator judges when to insert a job | `Scheduler.tsx` |
| Run | Schedule Gantt | `/schedule` | How long the experiments that have **not run yet** will take when queued in order: "by experiment" — one row per experiment (draggable to reorder) / "by device" — one row per device (to find the bottleneck) | `ScheduleGantt.tsx` |
| Run | Paused Queue | `/paused` | The jobs waiting for a human: which node they stopped on / which error code / grouped by type; supports continue / dismiss / dismiss selected / plate-position correction | `PausedRuns.tsx` |
| Run | Plate Inventory | `/plates` | Which plate is on which position (the basis of plate-position-level mutual exclusion); three-state plate counting: report position / register lost | `Plates.tsx` |
| Analysis | History Gantt | `/gantt` | The node timeline of **a run that has already finished** (relative to the start of the run); switchable between historical runs | `Gantt.tsx` |
| Analysis | Duration Distribution | `/duration` | The p50 / p90 duration model per method / device | `Durations.tsx` |
| Operations | Alerts | `/alerts` | Device / workflow alerts aggregated by fingerprint; ack / close / silence / reopen | `Alerts.tsx` |
| Operations | Devices & Islands | `/topology` | The resource tree: island → device instance; the ledger view | `Topology.tsx` |
| Operations | Model Library | `/models` | The device model library: methods / parameters / estimated duration; model status management | `ModelLibrary.tsx` |
| Operations | Island View | `/island` | **Drag the device pool (searchable, with a diagram) onto an island to deploy**; the device cards in the island carry a diagram | `Island.tsx` |
| Operations | Island Flow | `/island-flow` | The transport sequence inside the island (the robot arm's point-to-point moves) | `IslandFlow.tsx` |
| Operations | Policies | `/policies` | Overview of the recovery policies (including the wildcard `*`) | `Policies.tsx` |
| Operations | Error Codes | `/error-codes` | The scope / severity of every code + **managing the policies by scope** | `ErrorCodes.tsx` |
| System | User Management | `/users` | Accounts (visible only to `admin`) | `Users.tsx` |
| System | Audit Log | `/audit` | Who changed what, and when | `Audit.tsx` |

> **Two easy mix-ups**: `/schedule` (Schedule Gantt, future perspective, experiments that have not run yet) and `/gantt` (History Gantt, runs that have already been executed);
> `/designer/:id` (BPMN single-workflow canvas, bpmn-js) and `/canvas` (experiment orchestration canvas, React Flow).

## 4. Core feature list

### 4.1 Device onboarding (device pool → island)

**What it can do**

1. Pick a device model in the device pool (**76 models**, searchable by name / code / capability), drag it onto an island → one **device instance** is generated (248 of them)
2. Fill in the IP / driver endpoint → click "Test connection" → it comes online on the heartbeat
3. Every instance has "Details": **supported methods + parameter descriptions + estimated duration** (from the model library, stored together with the device)
4. Devices can be attached to / removed from an island, instances can be cloned, devices can be deleted

**Key constraints**

| Constraint | Why |
|---|---|
| The method list comes from the `device_method` table (not read straight from the model library) | Devices can add or remove methods according to the real site; the workflows reference the records in this table |
| Methods **not** in the manual are marked "(not provided by the manual, to be confirmed on site)" | The model library has undocumented commands such as `GetStatus`, which may not exist on the real device |
| Before deleting a device, confirm that no workflow references it | Otherwise opening the workflow reports "method does not exist" |

**Entry point**: Operations → Island View (deploy / details), Operations → Devices & Islands (ledger)

---

### 4.2 Workflow orchestration (the BPMN canvas)

**What it can do**

| Construct | Supported | Explanation |
|---|---|---|
| Sequential task | ✅ | The node binds a device + a method |
| Parallel gateway (AND) | ✅ | All the branches run, the join waits for all of them |
| Conditional branch (XOR + expression) | ✅ | `${temp} > 37`, `${mode} == 'fast'`, `&& \|\| !` |
| Loop (back edge) | ✅ | At most 10 iterations (configurable per workflow via `attr.maxIterations`) |
| Run-time variables | ✅ | Passed in at start + **the node output automatically enters the variable table** (e.g. the device returns `plateType`) |
| Write → save → preflight | ✅ | The preflight checks: reachability, missing capability, condition syntax (`PF-09`), parallel-join deadlock |

**The three outcomes of a branch** (easy to mix up, so it is called out separately):

| Colour on the graph | Status | In which situation |
|---|---|---|
| Green | `completed` | This step is done |
| **Light grey dotted** | `bypassed` | **The branch was not selected**, this step does not have to be done (a normal ending) |
| Grey dashed | `pending` | Its turn has not come yet (an earlier step failed / paused) |
| Yellow | `skipped` | Skipped by policy — **the plate position is not trustworthy, the system stops and waits for a human** |
| Orange | `manual` | Handed over to an operator (e.g. no paper `EC-08-400C`) |

**Entry point**: Authoring → Workflows → click a row (straight into the canvas) / "Run history"

---

### 4.2.1 Built-in nodes: variables / expressions (they compute without a device)

Select any task on the canvas; the "Node settings" panel on the right can turn the node into one of three kinds:

| Type | What to fill in | Explanation |
|---|---|---|
| **Device task** | Device (dropdown, from the device pool) + method (dropdown, from that device's `device_method`) + **parameters (the input fields are rendered from the method definition)** | This is the only way to make a workflow "really move a device"; without a device bound, the run reports "no executable resource" |
| Variable node | Variable name + value | See below |
| Expression node | Variable name + expression | See below |

> **When you pick a method, the node name on the canvas follows** (`Seal` → `GetStatus`), so the graph is readable at a glance;
> but if you renamed the node yourself (e.g. "My sealing"), it is **no longer overwritten**.
> Built-in nodes are displayed by label (Variable / Expression), and strings such as `__setVar` never appear on the graph.

> **The parameter input fields come from the method definition** (`device_method.param_schema`): whatever parameters exist are rendered,
> the value supports `${variable}`; when the method defines no parameter it falls back to a JSON text box (extra fields can be sent too).
> The device and method dropdowns depend on "synchronising the methods when the device enters the pool" (`DeviceRegistrar` → `device_method`).
> The parameters are JSON, for example `{"plateID":"PL001","cycles":3}`; leaving them empty means no parameters are sent.

Besides device tasks, the flow chart can also hold two kinds of node that **do not drive a device**:

| Node | What it does | Parameters |
|---|---|---|
| **Variable node** `__setVar` | Writes a value into the run-time variables | Variable name + value (the value may contain `${other}`) |
| **Expression node** `__expr` | Evaluates an expression and stores it in a variable | Variable name + expression (supports `+ - * /`) |

Use: loop counting (`counter = counter + 1`), turning the upstream result into the value a conditional branch needs.

**There is no "arbitrary code" node**: `eval` on an industrial PC is the same as handing over the right to execute; if you need computing power, go through the whitelist of built-in functions (extended on demand).

---

### 4.2.2 The real boundary of parallelism (do not overestimate it)

"Can it run in parallel" has to be split into three things, **and the conclusions differ**:

| Scenario | What happens today | Basis |
|---|---|---|
| A parallel branch is drawn inside one workflow | **Both branches run**; when they compete for the same device they queue automatically (device lock) | measured `scripts/verify-parallel-flow.py` 4/0 |
| Several experiments are submitted on the same island | **One runs, the rest queue** (island-level mutual exclusion, no parallelism) | measured peak parallelism = 1, `scripts/verify-parallel-experiments.py` 3/0 |
| Experiments submitted on different islands | The code supports running them at the same time (up to 4), **not measured in this environment** | code reading only, see `docs/并行能力验证-20261103.md` §4 |

**The Schedule Gantt does not model parallelism**: it adds the durations up serially, on the assumption that "only one experiment runs at a time", so it is a **lower bound** —
the real completion can only be earlier. This is a deliberate trade-off, not an unfinished part (see the verification document §5).

---

### 4.3 Experiment orchestration (assembling workflows into one experiment)

**What it can do**

- One experiment = N workflows run in `seq` order, each with a **loop count**
- "Add workflow" → "Save orchestration" → "Execute experiment"
- **The execution history can be expanded**: it shows "executed / failed / not executed" step by step, and every step can be clicked into the run detail

**Two entry points, the same data**

| Form | Suitable for | Explanation |
|---|---|---|
| Experiment orchestration (list) | Sequential execution | The list is more intuitive and less easy to wire up wrongly (in the workflows in the database `run_mode` is `sequential` for 99.7%, see the code comment) |
| Experiment canvas (graph) | When branching / parallelism / variables are needed | From the experiment orchestration, click "Canvas" to jump over with the selected experiment |

**Key constraints**

- When the experiment has no workflow attached, "Execute experiment" is **disabled** (clicking it only gets "the experiment has not orchestrated any workflow" back from the backend); the page offers an "Add a workflow now" button
- Changes to the orchestration only take effect after "Save orchestration"
- When creating an experiment you can pick the island (which decides the device scope); if the code is left empty it is generated automatically as `EXP-<timestamp>-<4 digits>`

**Entry point**: Authoring → Experiments

---

### 4.4 Running and troubleshooting (the most valuable part of the system)

**What to look at**

| What you want to know | Where to look |
|---|---|
| Which step the execution has reached | Run → Live Board (current node + the reason it is stuck) |
| What each step of this run did | Run Detail → Event stream (`NODE_STARTED` / `COMMAND_SENT` / `COMMAND_ACKED`…) |
| Which step failed, with which error code | Run Detail → the "Error code" column of the node table |
| **Which step is stuck on the graph and which step did not run** | Run Detail → **Flowchart (coloured by status)** |
| Why it stopped to wait for a human | Paused Queue → the "Reason" column (the error code is highlighted in red separately) + the node it stopped on |
| How many times this workflow has run in the past | Workflows → "Run history" → click one entry to open the detail |

**What you can do**

| Action | Effect | Note |
|---|---|---|
| Continue | **Resume from the breakpoint**: the completed nodes are skipped, only the rest is done | Not skipping means the execution is repeated → the plate is moved a second time |
| Dismiss / Dismiss selected | Marks `aborted` + `EC-HUMAN-DISMISS`, **sends no device command at all** | All the records are kept, you can change your mind |
| Abort | Also `aborted`, but with a different error code | The report can tell "nobody answered it" apart from "somebody terminated it on purpose" |
| Crash recovery | After the process crashes, the whole job is marked `failed` + `recoverable`, and the board shows an "Interrupted by the last crash" card | Only the **last 7 days** are listed; resuming something older re-runs it with the expired state, which is an incident |

---

### 4.5 Error codes and recovery policies ("what to do after an error")

**Model**: one error code × one scope = one policy.

| Scope | Meaning | Typical configuration |
|---|---|---|
| `command` | Resend the single command | `EC-05-1006` communication failure → retry ×5, exponential back-off |
| `node` | Redo the whole node | `EC-08-4001` semaphore overflow → retry ×3 |
| `run` | Stop the whole job | `EC-06-0001` driver not loaded (retrying is pointless) |
| `device` | Device level | Offline, driver crash |

**Matching rule**: first compare the specificity (`code + scope` > `code only` > wildcard `*`), then compare `priority`, and take the **first** one (`findFirst`, not a fall-back chain).

**Why the scope matters**: a policy with `scope=command` **does not take effect** for an error thrown at the `node` level; it falls through to the wildcard → hand over to an operator. Historically the code threw `node` in every case, which is why the command-level retries configured in the database were all dead configuration.

**What you can do**

| Action | Explanation |
|---|---|
| Edit | The code attributes (scope / severity / title) + managing the policies by scope |
| **Add** | "New error code"; the code has to match the manual / the driver, otherwise it can never fire |
| **Delete** | Soft delete: it disappears from the list, the historical occurrence records are kept |
| **Red flag** | When no policy is enabled on the code's own scope, the policy column shows "Scope not covered" — when it really fires it can only fall through to the wildcard policy |

**Entry point**: Operations → Error Codes (add / delete / edit) / Operations → Policies (overview)

**Heartbeat parameters** (the top card of Operations → Alerts): the probe interval / the offline threshold can be changed, **and the change takes effect in the next cycle**,
without restarting the API; the threshold is forced to be ≥ 2× the interval.

---

### 4.6 Alerts

- Device offline: the heartbeat times out → one `DEVICE-OFFLINE|<id>` is opened; back online → automatically `closed` (verified: opens after 42s offline, closes after 25s back online)
- Workflow node: `manual` / `skipped` leave a trace in the alerts but are **not escalated** (on site, nobody needs to be woken up by a "skip")
- Acknowledge (ack) / close (close) / silence (silence) / reopen (reopen) are all available

**Entry point**: Operations → Alerts

---

## 5. Technical architecture and runtime model

> This chapter answers three questions reviewers ask most often: **how the system is put together**, **which states one execution has**,
> and **how far parallelism goes and how resources are locked**. The authoritative statement is in the functional specification
> chapters 5 / 13 / 14 (Chinese source: `../../LabOS功能规格说明书.md`).

### 5.1 Layered architecture and deployment

```
Browser  React 18 + TypeScript + Ant Design + React Flow (webapp/)
        └─ Nginx (:8080) reverse-proxies /api → backend
ApiServer (:18080, Java; hand-written HTTP dispatch: routing + auth + audit + business glue)
        ├─ PostgreSQL (:5432/labdb)  workflows / runs / events / resource state
        ├─ Redis (:6379)             session / cache
        └─ Device adaptation layer
             ├─ Sidecar (:18501)      local driver process
             └─ Device simulator cluster (:19500–19506)
```

| Layer | Responsibility | Key components |
|---|---|---|
| Frontend | Canvas / lists / boards / run detail | `webapp/` (`App.tsx` defines all the routes) |
| API | Auth + audit + assembling DTOs | `ApiServer` (known technical debt: the single file is on the large side) |
| Kernel | Compiles the workflow, advances along the graph, schedules resources, decides recovery | `kernel`: `QueryService` (compile / preflight), `ExperimentExecutor` (execution), `GraphRunner`, `Scheduler` |
| Storage | Workflows / runs / events / resource state | PostgreSQL; the DAG edges are stored in `experiment_process_edge` |
| Device | Capability contract + driver | Sidecar / simulators; **the driver declares the capabilities, onboarding a new device does not change the business code** |

**Port conventions**: frontend 5173 (dev) / 8080 (Nginx), ApiServer 18080, Sidecar 18501,
simulators 19500–19506, PostgreSQL 5432, Redis 6379.

**One hard rule**: the validation of the logical relationships is **closed at the code layer** (the `QueryService` compiler); the database only does persistence,
and business logic is not carried by database constraints / stored procedures.

### 5.2 Five-level status vocabulary (experiment status)

The system is split into five scopes, each with its own set of status codes: **run 11 / node 9 / step 6 / experiment 12 / batch 7**.
`terminal` = the terminal state (no longer changes); `active` = the occupied state (it appears in the "busy / in the queue" statistics).

**Single source of authority**: the backend `StatusVocabulary.java` (contract version `2026-11-03`),
exposed as `GET /api/meta/status-vocabulary`; the frontend `webapp/src/utils/status.ts` only does a fallback.
Changing the vocabulary means changing only this one class and bumping the version number.

**run (the execution job)**

| Status code | Label | terminal | active |
|---|---|---|---|
| `draft` | Draft | no | no |
| `pending` | Pending | no | no |
| `planned` | Planned | no | no |
| `queued` | Queued | no | yes |
| `running` | Running | no | yes |
| `paused` | Paused | no | yes |
| `completed` | Completed | yes | no |
| `failed` | Failed | yes | no |
| `aborted` | Aborted | yes | no |
| `cancelled` | Cancelled | yes | no |
| `skipped` | Skipped | yes | no |

**node (the node)**

| Status code | Label | terminal | active |
|---|---|---|---|
| `pending` | Not executed | no | no |
| `ready` | Ready | no | no |
| `running` | Running | no | yes |
| `completed` | Completed | yes | no |
| `failed` | Failed | yes | no |
| `manual` | Awaiting human | no | yes |
| `skipped` | Skipped | yes | no |
| `aborted` | Aborted | yes | no |
| `bypassed` | Branch not selected | yes | no |

> **`bypassed` and `skipped` must be told apart**: `bypassed` is a branch the XOR gateway did not select, it is a **normal ending**;
> `skipped` is a skip by policy, meaning **the plate position is not trustworthy and the system stops and waits for a human**. Both enter a terminal state, but their handling meanings are opposite.

**step (the step)**

| Status code | Label | terminal | active |
|---|---|---|---|
| `pending` | Not executed | no | no |
| `running` | Running | no | yes |
| `completed` | Executed | yes | no |
| `failed` | Failed | yes | no |
| `skipped` | Skipped | yes | no |
| `paused` | Paused | no | yes |

**experiment**

| Status code | Label | terminal | active |
|---|---|---|---|
| `created` | Created | no | no |
| `planned` | Planned | no | no |
| `queued` | Queued | no | yes |
| `running` | Running | no | yes |
| `paused` | Paused | no | yes |
| `completed` | Completed | yes | no |
| `finished` | Finished | yes | no |
| `failed` | Failed | yes | no |
| `aborted` | Aborted | yes | no |
| `cancelled` | Cancelled | yes | no |
| `skipped` | Skipped | yes | no |
| `deleted` | Deleted | yes | no |

> Both `completed` and `finished` mean done; keeping two codes is for compatibility with historical writes; **new writes uniformly use `finished`**.

**batch**

| Status code | Label | terminal | active |
|---|---|---|---|
| `pending` | Pending | no | no |
| `queued` | Queued | no | yes |
| `running` | Running | no | yes |
| `paused` | Paused | no | yes |
| `completed` | Completed | yes | no |
| `failed` | Failed | yes | no |
| `aborted` | Aborted | yes | no |

**run → experiment status mapping**: the experiment list / detail displays the experiment status, while the persisted subject is the run;
the two must come from the same source (otherwise there is a drift "the list is completed, the detail is failed"). The mapping is defined by
`StatusVocabulary.RUN_TO_EXPERIMENT` and `runToExperimentCaseSql()` generates the SQL that is persisted:

| run.status | experiment.status |
|---|---|
| `completed` | `finished` |
| `failed` | `failed` |
| `running` | `running` |
| `paused` | `paused` |
| `aborted` | `aborted` |
| `cancelled` | `cancelled` |
| `skipped` | `skipped` |
| `queued` | `queued` |

### 5.3 The three pause kinds and on-site hold

**A "pause" is not one state but "one state plus one reason"**: `run.status = paused` plus `run.pause_kind`.

| pause kind | Label | Trigger | Recovery |
|---|---|---|---|
| `paused_fault` | Device fault | Device heartbeat timeout / fault | **Automatic**: it resumes by itself once the heartbeat recovers, no human needed |
| `waiting_human` | Waiting for a human | The retry / reassign policy is exhausted, or a human judgement is needed | **Manual**: click "Continue" |
| `paused_unknown` | Plate position unknown | An exception during transport, the plate position is `UNKNOWN` | **Manual**: count and correct the plate positions first, then continue |

**On-site hold**: one event freezes a whole range (for example, when a device on an island faults, the other experiments on the same island should also stop sending work to that device).
It is described by two dimensions, "why it is held" (kind) and "how big a range is held" (scope), **the more inner, the higher the priority:
`experiment > island > site`**.

| hold kind | Label | | hold scope | Range |
|---|---|---|---|---|
| `fault` | Device fault | | `site` | Whole site |
| `plate_unknown` | Plate position unknown | | `island` | Island |
| `human` | Waiting for human confirmation | | `experiment` | This experiment |
| `manual` | Manually paused | | | |

- During a hold the resources are **not released** (otherwise a parallel process would snatch the held position);
- but the "return reservation" (`position-return`) is a promise and **must be kept all along**, even a hold does not release it;
- the island ID of a scope must be a **positive number**: a negative one is treated by the logic as "unassigned" and the hold stops working.

### 5.4 Crash recovery, batched execution and resuming from a breakpoint

| Mechanism | Key points |
|---|---|
| **Crash recovery** | A process crash (not the graph execution stopping at a node boundary) goes down another path: at startup `StartupRecovery.settleOrphanExperimentRuns` **marks `failed`** the leftover `running` orphan jobs; on a crash it stamps `recoverable=true`, and `/api/runs/recoverable` lists the recoverable jobs; it does **not resume automatically**, a human must click "Continue"; **only the last 7 days are listed** (resuming something older re-runs it with the expired state, which is an incident) |
| **Batched execution (S3.2)** | When the number of samples exceeds the threshold it is split into batches and submitted batch by batch, lowering the resource peak of a single submission. When `batchSplit` is NULL or ≤ 0 there is no splitting; re-running one batch increments `attempt_no` and keeps the history; **a completed batch refuses to be re-run** (`COMPLETED_BATCH_NOT_RERUNNABLE`) |
| **Resume from a breakpoint** | The resume key is **`seq:loop_index`**: every iteration of a loop with the same `seq` can be resumed independently. On recovery it reads the `node_key` of the `completed` nodes as `skipKeys`, skips them, and only makes up the rest |

> The difference between a "pause" and a "crash": a pause is **the graph execution waiting for a human at a node boundary, with the process still alive**; a crash is **the process being gone**, and recovery relies on the persisted progress.
> Known boundary: `skipKeys` matches by `node_key`; after a node is **renamed** the old `node_key` no longer matches and it is re-run (already pinned down by the "resume: rename mismatch re-runs" case in `workflow.GraphRunnerTest`).

### 5.5 Concurrency, resources and scheduling

**Three levels of concurrency (do not lump them together)**

| Level | Status | Evidence |
|---|---|---|
| ① Several workflows / parallel branches inside one experiment | ✅ supported and measured | `verify-parallel-flow.py` 4/0 |
| ② Several experiments concurrently on the same island | ✅ but **queued serially**, peak parallelism = 1 (island-level mutual exclusion) | `verify-parallel-experiments.py` 3/0 |
| ③ Concurrency across different islands | ⚠️ supported in code (thread pool up to 4), this environment has only one island, **not measured** | code reading only |

The Schedule Gantt does not model parallelism: it adds up serially on the assumption that "only one experiment runs at a time", so what it gives is a **lower bound** (the real completion can only be earlier); this is a deliberate trade-off.

**Four-level resource model**: the resources occupied by one transport are split into four levels, with different granularity and mutual-exclusion meaning.

| Level | Resource | Table | Granularity | Mutual-exclusion semantics | Held for |
|---|---|---|---|---|---|
| L1 | Position | `position_state` | One plate slot | Occupancy-level exclusion | The whole task |
| L2 | Port | `device_port` | One inlet / outlet | `capacity=1` exclusion; `>1` is a semaphore | **Action-level** |
| L3 | Door | `device_access` | One openable / closeable part | Action-level exclusive | **Action-level** |
| L4 | Robot arm | `device_state` | One robot | Action-level exclusive | **Action-level** |

The `capacity` of L2 says "how many plates can pass at the same time": 1 = serial, >1 = parallelisable (counted with `device_port_usage`).

**Fixed acquisition order**: the resource request of one Transfer is a **fixed total order**, out-of-order is not allowed, which removes circular waiting at the source:

```
① source door (L3) → ② source port (L2) → ③ source position (L1)
→ ④ target door (L3) → ⑤ target port (L2) → ⑥ target position (L1)
→ ⑦ return position (L1) → ⑧ robot arm (L4)
──────────────────────────────────────────  all ready; the plate is still at the source, it can be abandoned at zero cost
⑨ grip the plate  ← the point of no return
```

The door is put first: the door is the "main switch", you cannot get in without opening it; the door is open but the robot arm did not win it → you can close the door and give up, at a small cost.

**Wait-for-graph deadlock detection**: even with a fixed order, one safety net is kept — a wait-for graph + DFS cycle detection.

| Attribute | Value |
|---|---|
| Algorithm | DFS cycle detection |
| Scale | number of resources n ≤ 10, number of edges E ≤ 20 |
| Time per run | < 10 microseconds |
| Switch | **always on, no switch** |

Why always on: one Transfer is about 6 resource requests ≈ 60 microseconds, negligible compared with one device action (150 ms to 30 minutes);
introducing a switch to save this tiny cost would instead bring races and missed detection.

**Why not the banker's algorithm**: ① workflows have branches, so the "maximum demand" cannot be declared before execution reaches the decision node;
② a hard declaration would have to assume the worst case (the union of the branches) ≈ "I want the whole island", dropping concurrency to zero; ③ recomputing after the branches are decided costs a lot and means less and less the further it goes;
④ the banker's algorithm assumes "the declaration does not change", but a human changing a parameter overturns the declaration, so the assumption does not hold; ⑤ it is almost never used in engineering practice (PostgreSQL / MySQL / Linux all use wait-for graphs or lock ordering).

**Scheduler**

| Attribute | Value |
|---|---|
| Readiness decision | Three states: `pending` → `running` → `completed` (plus `paused` / `cancelled`) |
| Polling interval | 2 seconds |
| Island lock acquisition | `putIfAbsent` (atomic CAS, guarantees no concurrency on the same island) |
| Ordering | **EDD → priority → FIFO** (earliest due date runs first; on a tie compare priority; on a further tie the earlier submission runs first) |
| Restart recovery | rehydrate: the queue is restored after a restart |

### 5.6 Duration model and rolling scheduling

- **Definition**: p50 (for displaying the total duration) / p90 (for judging whether the longest gap is exceeded, conservative).
- **Sample**: only **cleaned** `completed` records with a positive duration are used, aggregated by "capability + method".
- **Variation**: the duration of the same action varies a lot — the p50 of `Run` = 18.5 s, the p90 = 250 s, a **variation ratio of 13.5×**.
  This is the root reason why scheduling has to be conservative and must not be over-optimistic about a point estimate.
- **Rolling schedule**: it is not scheduled all at once; each time only one future window is scheduled. A **commitment window** (an experiment that has started is never changed) + a **frozen zone of the next 15 minutes**;
  the algorithm is **greedy** (not a full DP) — the state space of a full DP is astronomical (`2^50 × 480^26`),
  and the input itself (the duration) is an estimate, so computing the "optimal solution" from inaccurate data is meaningless.

---

## 6. Three end-to-end scenarios (you can follow them directly)

### Scenario A: run a single workflow (the fastest check)

```
Workflows → click the "S100 plate-sealing check" row → the designer (BPMN graph)
  → "Run history" at the top right → every run so far
Or: Workflows → "Details" at the end of the row → Preflight: pass / task 1 / reachable 1 → Execute
  → jumps to the run detail: node 1/1, 0 failed, device S100-1
  → Flowchart: Start (blue circle) → [Seal green] → End (grey circle)
```

### Scenario B: conditional branch + loop (checks the expressions and the back edge)

```
Workflows → «T-X3 three-way XOR» → Details → Execute (vars=v=2)
  → On the graph: A light grey dotted (not selected) / B green (completed) / C light grey dotted
Workflows → «T-LOOP loop» → Details → Execute (vars=go=yes)
  → On the graph: the same Seal node executes repeatedly and stops after 10 iterations (attr.maxIterations can be changed)
```

### Scenario C: start from an experiment (the real on-site usage)

```
Experiment orchestration → pick an experiment → (if it is an empty experiment) click "Add a workflow now" → pick a workflow → Save orchestration
  → Execute experiment → the execution history shows completed 1/1
  → Expand that row: step / loop / workflow / status / run → click the run to open the run detail
```

---

## 7. What is explicitly "not done"

| Not done | Reason |
|---|---|
| No real-time video / visual review | Outside the scope of the control console, it belongs to the upper-layer application |
| No recipe / reagent management | The existing tables are placeholders, not wired up |
| No scheduling optimisation inside the control console (cross-experiment resource pre-emption) | See `docs/多实验并行调度-设计.md`, it belongs to the next phase |
| Not all BPMN elements are supported (timer / message / subprocess) | Only the subset that can "get the job done" is implemented; the rest is ignored while parsing and reported in the preflight |
| No strong guarantee of multi-tenant isolation | `tenant_id` is everywhere, but only a single tenant has ever been run |

---

## 8. Glossary (for alignment during the review)

| Term | Meaning |
|---|---|
| **Workflow** | A BPMN graph; the nodes are the actions, the edges are the order |
| **Node** | One piece of work in the graph: device + method + parameters (or a gateway) |
| **Experiment** | Several workflows arranged by seq into one task (may loop) |
| **Step** | One execution of one workflow inside an experiment (`experiment_run_step`) |
| **Run** | One real device-driving process; every step of the experiment produces one run |
| **Node execution** (`run_node`) | One node inside a run (level=0 is the business node, level=1 is the step) |
| **Command** (`run_command`) | One dispatch / response, carrying the raw response |
| **Capability** | e.g. SEAL, CENTRIFUGE; a workflow node finds its device by capability |
| **Instance** (`device_instance`) | One concrete device of a model, deployed onto an island (with IP and driver) |
| **Pause** (`paused`) | The graph execution stopped at a node boundary waiting for a human (not a process crash) |
| **Recoverable** (`recoverable`) | A job interrupted by a process crash; the completed part can be skipped on resume |
