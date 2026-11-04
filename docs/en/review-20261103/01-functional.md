# Functional Document · LabOS Laboratory Control Console

> **English edition.** The Chinese original is
> [`docs/评审包-20261103/01-功能文档.md`](../../评审包-20261103/01-功能文档.md);
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

| Menu | Route | One-line description | Source file |
|---|---|---|---|
| **Authoring** — **Experiments** | `/experiments` | The experiment list: code / name / **island** / **version** (+1 on every save) / number of workflows / status; click a row to open the orchestration page | `ExperimentList.tsx` |
| **Authoring** — **Experiment detail** | `/experiments/:id` | The workflow orchestration, the variables and the execution history of this experiment; "← Back to the list"; the "**＋ New workflow**" button (or the item of the same name in the "Add workflow" dropdown) **first creates the workflow and adds it to this experiment, then jumps to the designer** (the designer header shows which experiment / island it belongs to, with a one-click return); "Canvas" draws the same experiment as a graph (workflow / variables / splits / parallelism / loops) — **⚠️ today the canvas only stores the drawing, execution still follows the workflow table** (see `docs/设计-实验编排统一-v0.1-草稿.md` for the unified plan) | `Experiments.tsx` |
| **Authoring** — Workflows | `/workflows` | All the process flowcharts; **"New workflow" directly creates a blank graph and enters the canvas**; click a row to enter the canvas (the BPMN editor); "Run history" shows every run of this workflow; the designer top bar shows **device idle / estimated wait N minutes** (estimated from the remaining time of the holder; hover to see who holds it); the "Timeline" draws which device each step occupies and when it starts, proportionally to time, and marks **the source of each step's duration** (manual / historical P50 / model library / fallback) | `Workflows.tsx` / `Designer.tsx` |
| Authoring — Experiments | `/experiments` | Arranges several workflows by seq into one experiment; execute, look at the execution history (expandable down to every step) | `Experiments.tsx` |
| Authoring — Experiment Canvas | `/canvas` | The **graphical version** of the same experiment: device nodes / variables / splits / parallelism / loops / merges | `ExperimentCanvas.tsx` |
| **Run** — Live Board | `/runs` | Which card the running jobs are stuck on; after a crash, "Resume" from the breakpoint | `Runs.tsx` |
| Run — **Schedule Gantt** | `/schedule` | How long the experiments that have **not run yet** will take when queued in order: **"By experiment"** — one row per experiment (answers "how long in total", **the rows can be dragged to reorder**), **"By device"** — one row per device (answers "which one is the bottleneck") | `ScheduleGantt.tsx` |
| Analysis — Gantt | `/gantt` | The node timeline of **a run that has already finished** (relative to the start of the run) | `Gantt.tsx` |
| Run — Paused Queue | `/paused` | The jobs waiting for a human; shows which node they stopped on and which error code; supports continue / dismiss / dismiss selected | `PausedRuns.tsx` |
| Run — Plate Inventory | `/plates` | Which plate is on which position (the basis of plate-position-level mutual exclusion) | `Plates.tsx` |
| Run — Run Detail | `/runs/:id` | **Flowchart (coloured by status) + node table (with error codes) + event stream** | `RunDetail.tsx` |
| **Analysis** — Gantt | `/gantt` | The node timeline of a single run; switchable between historical runs | `Gantt.tsx` |
| Analysis — Duration Distribution | `/duration` | The p50/p90 duration model per method / device | `Durations.tsx` |
| **Operations** — Alerts | `/alerts` | Device / workflow alerts aggregated by fingerprint; ack / close / silence | `Alerts.tsx` |
| Operations — Devices & Islands | `/topology` | The resource tree: island → device instance; the ledger view | `Topology.tsx` |
| Operations — Island View | `/island` | **Drag the device pool (searchable, with a diagram) onto an island to deploy**; the device cards in the island carry a diagram | `Island.tsx` |
| Operations — Island Flow | `/island-flow` | The transport sequence inside the island (the robot arm's point-to-point moves) | `IslandFlow.tsx` |
| Operations — Policies | `/policies` | Overview of the recovery policies (including the wildcard `*`) | `Policies.tsx` |
| Operations — Error Codes | `/error-codes` | The scope / severity of every code + **managing the policies by scope** | `ErrorCodes.tsx` |
| **System** — User Management | `/users` | Accounts | `Users.tsx` |
| System — Audit Log | `/audit` | Who changed what, and when | `Audit.tsx` |

---

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
| The method list comes from the `device_method` table (not read straight from the model library) | Devices can add or remove methods according to the real site; the historical workflows reference the records in this table |
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
| Experiment orchestration (list) | Sequential execution | The list is more intuitive and less easy to wire up wrongly (in the stored workflows `run_mode` is `sequential` for 99.7%, see the code comment) |
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

## 5. Three end-to-end scenarios (you can follow them directly)

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

## 6. What is explicitly "not done"

| Not done | Reason |
|---|---|
| No real-time video / visual review | Outside the scope of the control console, it belongs to the upper-layer application |
| No recipe / reagent management | The existing tables are placeholders, not wired up |
| No scheduling optimisation inside the control console (cross-experiment resource pre-emption) | See `docs/多实验并行调度-设计.md`, it belongs to the next phase |
| Not all BPMN elements are supported (timer / message / subprocess) | Only the subset that can "get the job done" is implemented; the rest is ignored while parsing and reported in the preflight |
| No strong guarantee of multi-tenant isolation | `tenant_id` is everywhere, but only a single tenant has ever been run |

---

## 7. Glossary (for alignment during the review)

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
