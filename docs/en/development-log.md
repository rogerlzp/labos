# LabOS v0.1 · Development Log

> **English edition.** The Chinese original is [`docs/开发日志.md`](../开发日志.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **The rule**: every change is written down in the round it happens, never added afterwards.
> Three things have to be recorded: **features** (what was added), **bugs** (what broke, why, and how it was fixed), **design** (the trade-offs and the reasons).
> What is recorded is the "why", not the "what was changed" — for the "what was changed" see `git log`.

---

## 2026-11-01 (afternoon)

### Features

| Item | Explanation |
|---|---|
| The model pool | On the island view the left side changed from "concrete devices" to "device models" (74 of them), dragging one = deploying one real device |
| Deploy by model | `POST /api/device-models/{id}/deploy?islandId=` the number increases (`Incubator_2C-1/-2`), the IP is left empty |
| The transport sequence | Every station can pick a method (recommended by capability), "walk it through" sends it down station by station for real, the plate moves along |
| Save as a workflow | `POST /api/choreography` turns the sequence into a workflow (the nodes are bound to `bound_resource_id`) |
| Jump to the designer after saving | 400ms after the generation it goes into the designer automatically, there is no need to look for it in the workflow library |
| The shadow check | The "shadow check" of the workflow library: it sends every node down for real, a failure does not interrupt it, it shows the missing contract fields |
| The shadow run of a whole workflow | `POST /api/workflows/{id}/shadow-run` + `ShadowRunner` |
| The history of the workflow graph | A backup is made before every save into `workflow_graph_history`, it can be rolled back (a backup is made again before the rollback) |
| The island layout | The devices are placed by dragging them with absolute positioning, the coordinates are stored in `resource_node.attr.pos_x/pos_y` |
| The plate movement animation | During the execution it polls `process-status`, the plate moves to the device of the current node |
| Choosing a device inside the island | `?islandId=` limits the candidate pool, it avoids an allocation across islands |

### Bugs (in the order they were found)

**B1. The compilation illusion (the most serious one, it wasted 4 rounds)**

```bash
javac ... 2>&1 | grep error    # the Chinese "错误" does not match, so it always looked like it compiled
```

In reality there was a type error on `NodeProgress`, `DriverRegistry` was not imported, none of the newly added routes made it into the class file, and it showed up as `no route`.

**The lesson**: when you filter the build output you have to match the error keyword of the real language. The Chinese JDK output is 「错误」, not `error`.

**B2. The page "silently went empty" after the token expired**

The island view was blank without an error. The truth: 401, but nobody handled the JSON that the bare `fetch` returned.

Fix: `client.ts` exports `apiFetch`, on a 401 it clears the token + dispatches `labos:unauthorized` + throws the error. **The bare fetch was replaced in 7 pages in one go**.

**B3. The foreign key of `device_state` failed**

`markPlatePosition` writes `device_instance.id` (171), while the foreign key points at `resource_node.id` (200171).

Fix: map it first and then write it, and skip it when it cannot be mapped (**you must not write 0**, 0 hits the foreign key as well).

**B4. Dragging swallowed the click**

`onMouseDown` goes straight into the dragging state, so the `click` does not fire. Fix: a movement of less than 4px counts as a click.

**B5. The detail Card brought the whole layout down**

The `Card` was nested in a `Space`, and when it threw the whole right-hand side disappeared. Fix: use a `Modal` instead (the portal renders its own tree).

**B6. The safety net made the workflow run twice (historic)**

After a failure "go back to the order and run it again", but by the time the decision was made the action had already been executed → **on a real device that means opening the lid twice**.

Fix: only go back when the parsing stage failed. This has been written into the specification.

**B7. The shadow check allocated the device wrongly**

`ShadowRunner` only picks from the pool by capability, it does not read the `bound_resource_id` the node is bound to. Fix: when there is a binding, the binding is used first.

### Design decisions

**D201. The pool is made of "models", not of "device instances"**

The semantics of a laboratory is "this island needs 3 incubators", not "move one specific incubator over there".

So the pool = `device` (the model), and dragging = a `POST deploy` that creates a new instance.

**The model pool is for creating, the devices and islands page is for the operations** — the former is the design, the latter is the site.

**D202. The transport sequence does not land a run, but it can be saved as a workflow**

"Walk it through" is an on-site debugging tool: it checks whether the logistics work, it does not produce production data.

Only "save as a workflow" writes to the database. The difference between the two is written into the wording of the buttons ("walk it through" vs "save as a workflow and open the designer").

**D203. The nodes of a transport workflow bind the devices hard**

The nodes of an ordinary workflow only mark the capability, and at execution time one is picked at random from the pool.

A transport workflow additionally stores `bound_resource_id` — the semantics are "the plate has to pass through these concrete devices".

**D204. The shadow run has two levels**

| Level | What it does | Why |
|---|---|---|
| The single command probe | Whether one method can be called through | Safe, it can be stopped at any time |
| The shadow run of a whole workflow | The contract coverage of the whole workflow | A failure does not interrupt it, it covers all the nodes |

There is no "a real run of the whole workflow + the decision is discarded" — because a physical action cannot be undone, and stopping half-way is worse than not running at all.

**D205. The Gantt uses SVG and not frappe-gantt**

frappe-gantt needs absolute dates, our data is relative milliseconds, converting back and forth introduces time zone problems; and the dark theme would mean overriding a pile of CSS.

The bar chart is only a few lines of coordinate calculation, SVG is more direct. The chart library is left to the statistics scenarios (the utilisation trend, the duration distribution) with ECharts.

**D206. The history of the workflow graph must also back up "before the rollback"**

Otherwise after the rollback you can no longer find the state "before the rollback", and the rollback becomes a one-way operation.

---

## 2026-11-01 (evening)

### Features

| Item | Explanation |
|---|---|
| The experiment variables | The `experiment_variable` table + the read/write interface (replaced as a whole, in a single transaction) |
| The loop field | `experiment_process` gets `loop_count` (it supports `${var}`) and `params_override` |
| Jumping to the designer after saving as a workflow | 400ms after the generation it goes into `/designer/{id}` automatically |

### Design decisions

**D207. The experiment layer handles the data and the multiple rounds, the workflow layer handles the actions and the conditions**

| Capability | Layer | The reason |
|---|---|---|
| The device actions, the conditional branches, the loop inside a single workflow | Workflow (BPMN) | Already there, natively supported |
| The variables, the loop across workflows, the dependency between workflows | Experiment | BPMN cannot express it |

If both layers can express a condition, they are bound to conflict during the maintenance.

**D208. The variables are replaced as a whole, they are not diffed one by one**

The variables are usually <10. Deleting all of them and inserting them again inside one transaction means there is never a "half deleted" intermediate state.

**D209. The variable types are only scalars (number/string/boolean)**

The parameters of an experiment are essentially scalars (temperature, volume, number of plates). A complex structure belongs in the parameters of a workflow node.

**D210. The loops are only single-level**

Nested loops make the number of steps explode after the expansion (3×3×3=27), and the interface is hard to use and hard to debug. When they really are needed, use the BPMN loop back at the workflow level.

**D211. Several experiments in parallel: island-level mutual exclusion, no parallelism inside an island**

The physical fact: an island usually has only 1 robot arm, two experiments running at the same time fight each other and the ownership of the plates cannot be told apart.

The island-level capacity=1 is the **safe lower bound**. The cost is that the parallelism = the number of islands (26), but that is better than colliding with the robot arm.

**D212. The experiment has to declare the island_id explicitly**

Deriving it backwards from the workflow nodes is unreliable — an ordinary node only binds the capability, the device is only chosen at execution time, so the island is not certain.

With an explicit declaration the scheduler can work out the resource usage statically.

**D213. The scheduling uses a heuristic (EDD + priority + FIFO), not an optimal-solution algorithm**

Three reasons:
1. The result is explainable ("it has the earliest deadline")
2. An error can be corrected by hand
3. The accuracy of the working time is only 10 seconds and the maximum fluctuation ratio is 13.5×, so the gap between the optimal solution and the heuristic is smaller than the error in the working time

> Pursuing an algorithmically optimal solution in a system whose data is inaccurate is deceiving yourself. First get the resource conflicts under control, then talk about optimisation.

**D214. "Hold and wait" is eliminated to prevent deadlocks**

The experiment requests all the resources at once, all or nothing. In 2.0 every experiment only needs 1 island, so this holds naturally.

In the future, when it goes across islands, the requests are made in ascending `island_id` order (a global order), which eliminates the circular wait.

**D215. The island lock has a TTL (2 hours)**

It prevents the lock from leaking forever when the execution thread exits abnormally (OOM/kill -9).

The cost is that "two experiments may run on the same island at the same time", **but that is better than being stuck forever**.

**D216. No pure dynamic programming (DP), instead a rolling-horizon dynamic scheduling**

The number of states of a pure DP is `2^N × time-slot^M` (N=50 experiments, M=26 islands), an astronomical number.

And the precondition for the DP to find the optimum is that the working time is accurate, while our maximum fluctuation ratio is 13.5×.

**The key clarification**: "dynamic programming" (the Bellman optimum) ≠ "dynamic scheduling" (the adjustment at run time). The former cannot be used, the latter should be done.

The approach: the rolling horizon (Rolling Horizon) —
- the commitment window: the experiments that have already started are never changed
- the frozen zone: the schedule of the next 15 minutes does not move (otherwise it is the same as not scheduling at all)
- the event-driven re-scheduling: a new experiment / a completion / a failure / a device going offline is what triggers a re-scheduling
- the local repair: only the affected part is re-scheduled, to avoid the schedule jittering

**The reason why the greedy approach (EDD + the earliest available island) is better than the global optimum**: in a rolling framework the local optimum of every step is more practical than a one-off global optimum — because the precondition of a one-off optimum (that the future is known) does not hold in reality.

**D217. The experiment has to declare the island_id explicitly** (decided)

Deriving the island backwards from the workflow nodes is unreliable (an ordinary node only binds the capability). With an explicit declaration the scheduler can work out the resource usage statically.

The cost: the user has to pick the island. The mitigation: the island with the most devices is selected by default.

**D218. 2.0 uses island-level mutual exclusion, the parallelism = the number of islands** (decided)

The physical fact: an island usually has 1 robot arm, two experiments running at the same time fight each other and the ownership of the plates cannot be told apart.

The island-level capacity=1 is the safe lower bound. The cost is that the parallelism is limited by the number of islands (26), the benefit is that the robot arms never collide.

**D219. 2.0 does not do cross-workflow dependencies** (decided)

The workflows in the database run sequentially. Supporting a DAG needs a topological sort + cycle detection + partial failure handling, none of which 2.0 uses.

When a parallel requirement really appears, use "split it into two experiments + island-level parallelism" instead.

**D220. The position-level mutual exclusion is more fundamental than the island-level one (the physical constraint the user pointed out)**

The user's scenario: experiment A puts a plate into position X, experiment B must never put a plate into X as well, otherwise it is crushed / jams.

**The island-level mutual exclusion cannot prevent this** — it governs "who is using the robot arm", not "which position holds a plate":

| Situation | Can the island-level mutual exclusion prevent it? |
|---|---|
| The same position is shared across islands | ❌ |
| The plate of the previous experiment is still in the position | ❌ |

**The root cause**: the robot arm is a **transient** resource (a few seconds per move), a position is a **persistent** resource (the plate occupies it for hours). Managing two kinds of resources with one granularity necessarily distorts them.

**D221. The position state has to distinguish "actual occupancy" from "reservation"**

- `plate_id` is not null = the plate is actually in it (persistent)
- `reserved_until` has not expired = somebody has reserved it, it is in transport (short-lived)

Without the reservation → two experiments both see "the position is empty" and both go to put a plate in.

**A reservation = a distributed lock implemented in the database**.

**D222. A transport has to lock the source and the target in one go**

```
Experiment A holds the source X, waits for the target Y (held by B)
Experiment B holds the source Y, waits for the target X (held by A)
→ deadlock
```

Two rules:
1. **All or nothing** (if the target cannot be taken, give the source back)
2. **Request in ascending resource_id order** (it eliminates the circular wait)

**D223. 2.0 uses island-level mutual exclusion + the position lock as double insurance**

| Option | Parallelism | Safety |
|---|---|---|
| Island level only | 26 | A position conflict across islands cannot be prevented |
| Position level only | High | It needs a second-level duration + the position initialisation + the robot arm time slices, none of which exist today |
| **Both** | 26 | ✅ Double insurance |

**The island level is the safety net, the position level is the correctness.** First correctness, then loosen the safety net step by step.

The precondition for loosening the island level in 2.3: a second-level duration + the position initialisation + the robot arm time slices + the position lock running stably for 3 months.

**D224. The cold start of the positions has to be emptied by hand**

At go-live all the positions are treated as "empty". But plates already in the lab may still be in them.

**Operating the devices from two control instances at the same time is absolutely forbidden** — the position lock does not know that the other one put a plate down.

The transition: stop the running instance → empty by hand → bring the new instance online → inventory → start running.

### New documents

| Document | Content |
|---|---|
| `docs/板位级占用与位置互斥-设计.md` | The four-level resource model, the reservation lock, the atomicity of the transport, the relation to the island-level mutual exclusion |
| `docs/实验编排-功能设计.md` | The three-layer model, the variables / loops / dependencies, the interface, the execution semantics, the phases |
| `docs/多实验并行调度-设计.md` | The resource model, the scheduling algorithm, the deadlock avoidance, the failure isolation, the observability |
| `docs/多实验并行调度-动态规划与滚动排程.md` | Why not DP, the rolling-horizon algorithm, the comparison of the three, the three decisions that were taken |

### Features (the position lock)

| Item | Explanation |
|---|---|
| The `PositionLock` service | tryReserve / tryReserveTransfer / release / releaseAll / putPlate / takePlate / sweepExpired / byIsland / stats |
| The position occupancy interface | `GET /api/positions?islandId=` returns the positions and their occupancy state |
| The position statistics interface | `GET /api/positions/stats` |

**The three contention scenarios were measured** (the atomicity was verified at the SQL level):

| Scenario | The result |
|---|---|
| run1 occupies the position | `INSERT 0 1` succeeds |
| run2 contends for the same position | `INSERT 0 0` fails, the holder is still run1 |
| run1 puts a plate in | `UPDATE 1`, the plate_id is written to the database |
| run3 contends while there is a plate | `INSERT 0 0` fails, the plate is still there |

**The implementation point**: a single `INSERT ... ON CONFLICT DO UPDATE ... WHERE` does the "check + occupy",
you must not do a SELECT followed by an UPDATE (there is a window in between).

### Features (the position visualisation)

| Item | Explanation |
|---|---|
| The layer positions are shown in the device details | The layer position occupancy is shown grouped by device in the details Modal |
| Three colours | Green=empty / pink=there is a plate / orange=reserved |
| `/api/positions` carries the ownership | It returns `parentDeviceId/parentDeviceCode`, the frontend groups by device |

**Measured**: ID1183 (the Inheco incubator) has 8 layer positions, 2 of them hold a plate, 1 is reserved, 5 are empty.

### Features (the position lock is connected into the executor) — partly completed

| Item | Explanation |
|---|---|
| `PositionLock.notePlateMove` | It decides put/take from the prefix of the method name (Deposit/Input/Put/Load vs Withdraw/Output/Get/Unload), and it automatically finds the first free position / the position with a plate on the same device |
| Connected to `PersistingStepSink` | It is called after every successful step, a failure is silent (it does not affect the main chain of the execution) |

**✅ Verified** (the troubleshooting is worth recording):

The log after the execution of 239:
```
[PLATE] Deposit1 dev=171 -> pos=504497    ← put into I4497
[PLATE] Withdraw1 dev=171 -> pos=504495   ← taken away from I4495
```

The statistics went from `withPlate:2` to `withPlate:2` (I4495 was emptied, I4497 was filled, the net change is 0, but the positions changed).

**The three traps stepped on during the troubleshooting**:

1. **`run_command` had no `device_id` column** → the device id that `writeStep` got could not be confirmed. The column has been added.
2. **`ps.setLong(13, deviceId)` had the wrong index** → `device_id` is the 12th parameter (9=result, 10=raw, 11=error_code, 12=device_id). The wrong index does not raise an error, it just writes into the NULL.
3. **The replacement anchor did not match and it was not checked** → the `replace` that added the `plateLock` call failed silently the first time (`assert` was only added the second time), so that piece of code was not in the file at all. It showed up as "it was changed but it did not take effect".

**The lesson**: after a `replace` you have to `grep` to confirm that the new code is in the file. This is the same kind of error as the "compilation illusion" — **thinking it was changed, while it was not**.

### Features (the real plate ID)

The priority of the plate ID resolution (`PositionLock.resolvePlateId`):

| Priority | Source | Explanation |
|---|---|---|
| 1 | The node parameters `plate_id` / `plateID` / `plateId` / `barcode` | The most controllable, it is configured explicitly in the workflow |
| 2 | The barcode in the raw text the device returned | Extracted with the regular expressions `plate_id` / `plateBarcode` / `barcode` |
| 3 | `AUTO-<runId>` | The fallback |

**Why the fallback is not a UUID**: the `AUTO-` prefix shows at a glance that "this plate was not scanned", while a UUID would be mistaken for a real barcode.

**Measured**:
```
[PLATE] Deposit1 dev=171 plate=PL-REAL-0001 -> pos=504495   ← the node parameter
[PLATE] Withdraw1 dev=171 plate=AUTO-204931 -> pos=null      ← the fallback
```

### Features (the experiment executor)

| Item | Explanation |
|---|---|
| `ExperimentExecutor` | Resolve the variables → expand the loops → substitute ${var} → execute in order |
| `experiment_run` / `experiment_run_step` | The main execution record + the record of every step (including `loop_index`) |
| `POST /api/experiments/{id}/run` | The body `{"vars":{"plateCount":"2"}}` overrides the variables |

**Measured** (experiment 814, the 2nd step `loopCount=${plateCount}`, with `plateCount=2` passed in):

```
seq  loop  workflow  run_id  status
1    0     239       204932  completed
2    0     648       204933  completed
2    1     648       204934  completed   ← the loop was expanded
3    0     278       204935  completed
```

**The design points**:

1. **A failure stops it immediately**: in a laboratory, skipping a step means the position of the plate is unknown and all the later steps are wrong
2. **The variables are substituted once before the workflow starts**: the variables do not change during one experiment, substituting them per node is a waste
3. **The limit of 20 on the loops and the limit of 500 on the total number of steps**: it prevents the expansion from exploding to something that cannot be debugged
4. **A variable that cannot be found keeps its `${name}` as it is**: it makes the error at execution time more direct, and it is easier to troubleshoot than replacing it with an empty string

### Features (the experiment scheduler)

| Item | Explanation |
|---|---|
| `ExperimentScheduler` | Island-level mutual exclusion + the EDD / priority ordering + the background polling (2 seconds) + the thread pool (4) |
| The island lock | `ConcurrentHashMap` + `putIfAbsent` (not "look it up first and then put it in", the latter has a window) |
| The TTL fallback | Not released after 2 hours means a leak (when the execution thread was killed with -9) |
| `POST /api/experiments/{id}/run?async=1` | It is submitted to the queue and the experimentRunId is returned immediately |
| `GET /api/scheduler/status` | running / queued / islandLocks / stalled |

**Measured**: 2 experiments submitted in a row →
```
{"experimentRunId":4,"status":"queued"}
{"experimentRunId":5,"status":"queued"}

Status: running=1 queued=1 stalled=false
      Island 0 is held by run#4
```

**The island-level mutual exclusion takes effect**: the second experiment on the same island enters the queue, it does not run in parallel.

**✅ Fixed**: `ExperimentExecutor.run` got the parameter `existingRunId`, and the scheduler passes the runId it created itself.
Two records are no longer produced.

The point of the fix: when it is reused the variables and expanded_steps have to be **filled in** (they are empty when the scheduler creates it).

### Features (the frontend of the experiment orchestration)

| Item | Explanation |
|---|---|
| The variable table | Name / default value / description, it can be added to and deleted from, it is saved as a whole |
| The loop column | One input field per step, empty=1, it supports `${var}` |
| The run button | It runs with the current variable values and displays `status · done/total` |
| `PUT /api/experiments/{id}/processes/{seq}/loop?count=` | It sets the number of repetitions on its own |

**The interface as measured**: EXP0812 has three workflows (2/65/66 nodes), the variables are temp=37, plateCount=3.

### Features (the execution history)

`GET /api/experiments/{id}/runs` + the card in the frontend:

```
run    status      progress  duration  start                error
#9     queued      0/0       —         —                    —
#8     running     1/5       —         2026-11-01 18:45:12  —
#7     completed   3/5       60s       2026-11-01 18:40:02  —
```

**Note**: `current_seq` is "which step is currently running" (1-3), not "how many steps have been completed".
The progress is displayed as `current_seq/expanded_steps`, which is slightly off semantically, and 2.0 accepts it.

### To do (orchestration related)

| Item | Status |
|---|---|
| The frontend table of the experiment variables | ✅ |
| The loop block UI | ✅ |
| The `${var}` substitution engine | ⬜ |
| The experiment executor (the expansion + the substitution + the order) | ✅ |
| The scheduler (the island lock + the queue) | ✅ |
| The scheduler creating a run twice | ✅ Fixed |
| The position pre-check (checking the target position before the scheduling) | ⬜ |
| The schedule dry run interface | ⬜ |
| The position lock connected into the executor | ✅ Verified |
| The visualisation of the position occupancy (the island view) | ✅ |
| The scheduler + the island lock | ⬜ |

## 2026-11-01 (late night)

### Features

| Item | Explanation |
|---|---|
| The default island strategy | It picks the island with "the most devices that have already been laid out" (`devices.filter(d => d.x != null).length`), which is closer to the intent than "the most / the newest devices" |
| The empty devices come first | The ordering of the candidate pool: the ones with `has_plate=false` come first, so with several devices of the same capability the plate moves to another place |
| The plate position is updated at every step | `PositionLock.markDevicePlate` synchronises `device_state.has_plate` on a Deposit / Withdraw |
| The transport workflows come first | The "run a workflow" of the island view automatically picks the newest workflow with `category='choreography'` (it binds the devices hard) |

### Bugs

**B8. `run_node.resource_id` was always empty (not completely fixed)**

The symptom: a cross-device workflow ran 3/3 successfully, but `run_node.resource_id` was NULL all the way down,
so the timeline could not get the device and the plate animation could not be replayed.

The attempts that have been made (none of them solved it):
1. `markPlatePosition` maps it with a database lookup → it works (`device_state` has a record)
2. `resolveResource` got a database lookup as a fallback → it still returns null

**The conclusion**: `np.currentResourceId()` is null in `updateNodeStatus`,
but it is not null in `markPlatePosition` — the same `RunProgress`, a different behaviour,
which means that the timing of the two calls or their data source differs (not located yet).

**The temporary workaround**: the nodes of a transport workflow have `bound_resource_id`, so the device order can be read from
`workflow_node.params->>'bound_resource_id'` to replay the animation.
But the tasks of `GET /api/workflows/{id}` do not return that field.

### Features (the plate replay chain)

| Item | Explanation |
|---|---|
| `GET /api/choreographies` | The list of transport workflows (descending by id), it bypasses the limit truncation |
| The workflow detail returns `resourceId` | It is read from `params->>'bound_resource_id'`, the frontend replays on that basis |
| The plate rendering is moved after the device cards | It used to be before them, and the absolute cards covered it |
| The default island is the one with "the most devices that have already been laid out" | As soon as a device has been placed on a newly created island, that island is preferred |

**The three traps of the plate animation** (all of them are fixed):

1. **Covered by the cards**: the plate div is rendered before `devs.map`, and the absolute cards that are rendered later cover it.
   Fix: move the plate block after the cards + zIndex 999 + a stronger look (44×30, a white border, a glow, the letters "PL").
2. **You cannot see whether it is rendered**: first verify with fixed initial coordinates that "it can be drawn", and only then look at the timing —
   this step is crucial, otherwise you cannot tell "it was not rendered" apart from "it was rendered but covered".
3. **The async closure gets the stale coordinates**: `runFlow` is async, `pos` is a rendering snapshot.
   Fix: `posRef` is updated every time `pos` changes, and the async part reads the ref.

**The measurement as it is now**:
```
Click "run the workflow and watch the plate flow"
→ the execution finishes 3/3 (#1250)
→ the plate goes to ID1183 → the plate goes to ID1731 → the plate goes to ID1775
→ the orange PL square moves along the three devices in turn and stops at the last one
```

**The two limit-related problems that were fixed**:

1. `/api/workflows?limit=3000` actually only returns 500 entries, and it sorts them by the number of tasks in descending order →
   the 991 experiment workflows push the newly generated transport workflows behind the 500, so the frontend never finds them.
   **Fix**: a dedicated interface `/api/choreographies` was added.
2. `?::text IS NULL` cannot be typed in JDBC, so the condition is always false.
   **Fix**: the SQL is assembled dynamically (the WHERE is only added when there is a category).

### Design decisions

**D225. A transport workflow is the right carrier for "the plate flows"**

The nodes of an ordinary workflow only bind the capability, one is picked from the pool at execution time, and two nodes with the same capability end up on the same device — so the plate does not move.

A transport workflow (`/api/choreography`) binds the devices hard, and only that is the real expression of "the plate goes from A to B".

So the "run a workflow" button of the island view **runs a transport workflow first**, instead of just any one of them.

## 2026-11-02 (early morning)

### Features (the experiment orchestration canvas)

A React Flow canvas + 5 kinds of draggable nodes + the connections + the property panel.

| Node type | Colour | Purpose | Property |
|---|---|---|---|
| `workflow` | Blue | Runs one workflow | Choose the workflow |
| `variable` | Purple | Defines a variable | `name=value` |
| `fork` | Orange | A conditional branch | The condition expression |
| `parallel` | Green | Runs several at the same time | — |
| `loop` | Pink | Repeats N times | `3` or `${var}` |

**The interaction**:
- drag it from the panel on the left onto the canvas (HTML5 drag)
- drag from the dot at the bottom of a node to the top of another node to connect them
- click a node → edit it in the property panel on the right
- a fork / parallel has extra connection points on the left and the right (the two branches)
- save → `PUT /api/experiments/{id}/graph`

**Measured**:
```
Variable(plateCount=3) → Workflow(Test-Incubator进板) → Fork(${plateCount}>1)
                                              ├→ Loop(${plateCount})
                                              └→ Parallel
```
The 5 nodes and 4 connections are all rendered correctly, both branches of the fork are there.

### The data model

```sql
experiment_graph (
  experiment_id UNIQUE,
  graph jsonb   -- {"nodes":[{id,type,position,data:{kind,label,workflowId,value,condition}}],
                  --  "edges":[{id,source,target,sourceHandle}]}
)
```

**Why the graph is stored in jsonb and not in separate tables**: the shape of the orchestration graph changes with the requirements (later there may be "merge", "wait" and similar nodes),
and separate tables would mean changing the schema frequently; and the graph is only read and written as a whole, it is not queried per node, so jsonb is enough.

### Bugs

**B9. "no route" again — the service was running the stale classes**

`javac` compiled successfully and the route was grepped, but the interface returned a 404. The reason is that **the service process was not restarted** (changing a .java means restarting).

The troubleshooting was fast this time (because the experience was already there: first `grep -c` to confirm the code is there, then restart).

### To do

| Item | Explanation |
|---|---|
| The interpreter from the canvas to the execution | The graph is built, but the execution still goes through `experiment_process` (a sequential list) |
| The evaluation of the fork condition | `${plateCount} > 1` is still only a string, it needs an expression engine |
| The concurrency control of the parallel nodes | How the parallelism and the resource conflicts are handled (the island-level mutual exclusion) |
| The mapping of the loop node to the process table | Right now the loop only exists in `experiment_process.loop_count` |

## 2026-11-02 (island orchestration)

### Features (the device-level orchestration of an island)

**This is the form the user asked for**: pick an island → spread the devices over the canvas → drag the arrows to string the devices together → execute in the order of the arrows.

| Step | Implementation |
|---|---|
| Picking the island | A drop-down, the island with "the most devices that have already been laid out" is selected by default |
| Spreading the devices | It uses the `attr.pos_x/pos_y` stored in the island view, so the positions are exactly the same |
| The connections | Drag from the dot at the bottom of a device to the top of the next one, the arrow is marked as "carry the plate" (orange) |
| The automatic chaining | It connects them automatically by sorting on x and then on y, so nothing has to be dragged by hand during a demo |
| The path preview | `ID0000 → Incubator_2C-2 → ...` is shown on the right |
| The execution | The method of every device is taken in the order of the arrows, a choreography workflow is generated and run |

**Measured with 7 stations**:
```
N1 Starlet            LoadFile      200206
N2 Incubator_2C #2    Deposit1Ex    504826
N3 XRobot (2)         BarcodeScan   504824
N4 Incubator_2C #1    Deposit1Ex    504825
N5 XRobot             BarcodeScan   200214
N6 Incubator_4C       Deposit1Ex    200210
N7 Inheco_Shaking     ActionDrawer  200171

→ workflow 1251 is generated
→ run 204971 completed (14 run_node)
```

### Design decisions

**D226. The island orchestration ≠ the experiment orchestration, they are two levels**

| | The island orchestration (this round) | The experiment orchestration (the canvas) |
|---|---|---|
| The nodes | **Devices** | Workflow / variable / fork / parallel / loop |
| The arrows | The plate is carried over | The logical flow |
| The granularity | The device actions | The composition of the workflows |
| The purpose | Designing the logistics on site | The production scheduling |

**The island orchestration is "how the plates move on this island", the experiment orchestration is "how this experiment runs".**
Mixing them makes both of them hard to use.

**D227. The path is resolved by "starting from the node without an incoming edge" and not by a fixed order**

```java
const start = nodes.find(n => !incoming.has(n.id));
// walk along source→target to the end
```

So however the user drew the graph, the execution follows it, and it does not depend on the order in which the nodes were created.

**Limitation**: only one chain is supported (when there is a fork only the first one is walked). Several branches need a topological sort + the evaluation of the conditions, which belongs to the experiment orchestration.

### To do

| Item | Explanation |
|---|---|
| The fork paths | Right now only the first one is walked, several branches need the evaluation of the conditions |
| The carry-the-plate method of every station | The first Deposit-like method is picked automatically, it cannot be chosen by hand |
| Saving the path | ✅ It is stored in the `island_flow` table |

### Features (the persistence of the island routes)

| Item | Explanation |
|---|---|
| The `island_flow` table | `island_id` is unique, `graph jsonb` stores the nodes (the coordinates) + the edges (the arrows) |
| `GET/PUT /api/islands/{id}/flow` | Reads and writes the route |
| The automatic save | It is written with a 600ms debounce after a change, the top shows "saved HH:mm:ss" |
| Restoring after an island change | The stored positions and arrows are used when loading; the arrows of the devices that have been moved off the island are dropped automatically |

**Why the coordinates are stored and not only the arrows**: after the user has moved a device on the canvas, a refresh should restore it the way they moved it, instead of going back to the coordinates of the island view.

**Measured**: 2 stations are chained → refresh → the arrows and the positions are all there, the top shows "path 2 stations / saved 19:32:26".

**A debounce of 600ms instead of saving on every change**: dragging a node triggers `onNodesChange` dozens of times in a row,
and saving every time would fill up the database for nothing (the user has not let go yet).

## 2026-11-02 (the experiment canvas gets the devices)

### Features (the devices and the logic nodes on the same canvas)

What the user wants is: **pick an island and the devices come with it, and the devices are strung together with the variables / loops on the same canvas**.

| Item | Implementation |
|---|---|
| Picking the island | A drop-down at the top left, the island with "the most devices that have already been laid out" by default |
| The list of devices | It shows `Number / Capability / IP`, a single one can be dragged in, and there is also "add them all to the canvas" |
| The device nodes | Cyan, they show the code + the capability + the IP, the property panel can fill in the execution method |
| The logic nodes | Workflow / variable / fork / parallel / loop (as before) |
| The automatic avoidance | When there are already nodes the devices are moved down as a whole, they do not overlap the logic nodes |
| Executing in the order of the arrows | They are collected in order: the variables are noted down, the devices are collected (device, method), the loops / forks / parallels are skipped first |

### The execution semantics (2.0)

```
Variable node → note down k=v (right now it is only recorded, it is not substituted into the method parameters)
Device node → collect (resourceId, method), if the method is empty the first Deposit-like one is picked automatically
Loop / fork / parallel → skip them and tell the user
At the end → generate one choreography workflow and execute it
```

**Why the loops / forks / parallels are skipped first**: the executor does not support this control flow yet,
and skipping them with a clear message is better than "silently ignoring them" — the user knows which nodes did not take effect.

### To do

| Item | Explanation |
|---|---|
| The real expansion of the loop node | ✅ Implemented (this round) |
| The evaluation of the fork condition | ✅ Implemented (this round) |
| The concurrency of the parallel nodes | It is constrained by the island-level mutual exclusion, what can really run in parallel is very limited |
| The variables substituted into the method parameters | ✅ Implemented (this round) |

### Features (the variables substituted into the method parameters)

Write `${var}` in the node parameters, at execution time it is replaced with the actual value.

```
workflow_node.params.method_params
  {"plate_id": "${plate}", "cycles": "${cycles}"}
        ↓ at execution time withVars({"plate":"PL-VAR-003","cycles":"11"})
  {"plate_id": "PL-VAR-003", "cycles": 11}
        ↓ it lands in run_command.payload
  {"cycles": 11, "nodeId": 118906, "stepSeq": 1, "plate_id": "PL-VAR-003"}
```

**The implementation**:
- `WorkflowService.withVars(Map)` —— the same pattern as `withIsland`, it is cleared in the finally when it has been used
- `substVars()` substitutes the whole parameter Map in `loadMethodParams`
- after the substitution, if the value is purely numeric it is turned into a `Long` (**the driver is sensitive to the type, "7" and 7 are not the same thing**)
- `ExperimentExecutor` carries the experiment variables along when it executes every step
- the API: `POST /api/workflows/{id}/run?vars=plate=PL001,cycles=7`

**Why a field of the Service and not passing the parameters through every layer**: the parameters are already turned into a Map in `loadMethodParams`,
and changing the signature backwards would mean touching 4 methods; and one execution is the lifetime of one Service instance, it is enough to clear it when it is done.

**The trap**: the name of the local variable collided with the existing `out` (there is already an `out` in `loadMethodParams`),
and the compilation reported "the variable is already defined". Renaming it to `rv` solved it.

### Features (the evaluation of the fork + the expansion of the loops)

**The evaluation of the fork condition** (in the frontend, without `eval`):

```
${plateCount} > 1
      ↓ parse "the left operand the operator the right operand"
      ↓ if both sides can be turned into a number compare the numbers, otherwise compare the strings
   true → take the main exit (the sourceHandle is empty)
   false → take the right exit
```

**Why not eval**: the content of the canvas comes from the user input, `eval` is handing the browser over to somebody else to write code.
Only the one form "left operand operator right operand" is supported, which is enough to express "is the temperature high enough", "is the number of plates complete".

**The expansion of the loops**:

```
Variable plateCount=2
Device ID0000
Loop ${plateCount}     ← note down loopFrom (the current length of steps) and the number of repetitions
Device ID1731
```

After the main chain has been walked, `steps[loopFrom..]` is copied `(the number of repetitions - 1)` more times.

**Why it is done outside the main loop**: a jump back has to change `cur`, and as soon as `cur` repeats it is blocked by `seen`,
so the main loop would exit immediately. So the single chain is walked first and the copy is made at the end.

**The limit of 20 repetitions**: `Math.min(20, ...)` — it prevents a `${n}` of 10000 from blowing up the workflow.

**Measured**:
```
The canvas: Variable(plateCount=2) → Device ID0000 → Loop${plateCount} → Device ID1731
Execution → workflow 1253
Nodes: N1 Starlet LoadFile
      N2 XRobot BarcodeScan
      N3 XRobot BarcodeScan   ← the second pass of the loop expansion
```

### To do

| Item | Explanation |
|---|---|
| The concurrency of the parallel nodes | It is constrained by the island-level mutual exclusion, on the same island there is nothing that can really run in parallel |
| The boundary of the loop body | Right now "everything after the loop node is repeated", there is no concept of a merge point |
| The variables substituted into the device method | It is supported (the `${var}` is substituted in `workflow_node.params`) |

### Features (the start/end nodes + the automatic connection)

**Question 1: should the start/end be brought in automatically? — Yes**

The reasons:
- the entry into the execution is clear (there is no need to guess it with "the node without an incoming edge")
- the end node is the place for the output (later the reports, the summary of the results)
- BPMN and every flowchart has these two

The implementation: when a new experiment opens the canvas, the "Start" and the "End" are placed automatically and connected; the start node has no incoming edge connection point, the end node has no outgoing edge connection point.

**Question 2: connect it automatically after a device is dragged in — Yes**

The reason: dragging in 5 devices means drawing 6 lines, and it is easy to miss one; a missing one shows up as "the later steps are not executed", without a visible reason.

The implementation `autoInsert`: it finds the edge that points at the end, changes it to point at the new node, and then adds a new node→end edge;
the end itself moves down by 130px, so visually it is "plugged in".

**Measured**: an empty canvas → drag in ID1731 → it automatically becomes `Start → ID1731 → End`, and both lines are generated automatically.

**Question 3: how are the variables passed between the nodes — three levels, two of them are implemented**

| Level | Explanation | Status |
|---|---|---|
| ① The static variables | The variable node `plateCount=2`, the following `${plateCount}` is substituted | ✅ |
| ② The configured output | The device node is configured with `plateId=plate`, it declares "store the return value in ${plate}" | ✅ Only recorded, not written back |
| ③ The write-back at execution time | After the execution reaches that node, the field is taken out of the JSON the driver returned and stored in a variable | ❌ It needs a step-by-step execution |

**Why ③ was not done**: the current execution is "first generate the whole chain into one workflow, then execute it as a whole",
and when it is generated the variables do not have a value yet. To get "the output of the previous step feeds the next step", it has to be **generated step by step and executed step by step**:
```
Execute the 1st step → take the return value → parse out the plate → substitute the parameters of the 2nd step → generate and execute the 2nd step
```
That means giving up the one-off generation of `choreography` and calling `WorkflowService` step by step like `ExperimentExecutor` does.

### Features (the step-by-step execution + the write-back of the output variables)

**The way of executing changed**: from "generate the workflow in one go and execute it as a whole" to "execute it probe by probe".

```
for (const st of steps) {
  Substitute the ${var} in the parameters
    ↓ POST /api/shadow/probe?resourceId=&method=
    ↓ take back the rawResponse
    ↓ take the field out according to the output configuration: equipment_id=eq → vars.eq = "214"
    ↓ the ${eq} of the next step can use it
}
```

**Why it had to be changed**: when the workflow is generated the variables do not have a value yet (because nothing has been executed),
and a one-off generation can never produce "the output of the previous step feeds the next step".

**The cost** (it is recorded in the document):
- no `run` is landed (a probe is a probe, it does not go into the execution records)
- one HTTP round trip per step, it is slower than the one-off generation

**The fields confirmed by measurement**: the mock driver returns `{message_id, message_type, method, equipment_id, equipment_status, method_status, error, data}`,
`equipment_id = "214"`, so `output: "equipment_id=eq"` can fetch it.

**The test graph**:
```
Start → Variable(plateCount=2) → Device(ID1731 Home, output=equipment_id=eq)
     → Fork(${eq} == 214) → End
```

### To do

| Item | Explanation |
|---|---|
| The step-by-step execution lands a run | ✅ Implemented (`ExperimentFlowRunner`) |
| The evaluation of expressions | Arithmetic like `${a} * 2` |
| The merge point of a fork | ✅ The "merge" node has been added |

### Features (the step-by-step execution lands a run)

The new interface `POST /api/experiments/flow-run` executes step by step and lands everything in the database.

```
steps=200214:Home:equipment_id=eq,200210:Deposit1
vars=plateCount=2
name=Canvas 20:14:32
```

**What is landed**:
- a `run` (`run_type='experiment'`, `workflow_id` is NULL)
- one `run_node` per step (level=0, resource_id = the resource id)
- one `run_command` per step (including the raw driver response)
- `run.output` holds the final variable table

**Measured**:
```
run 204983  completed  progress 100%  2 nodes  2 commands
vars: {plateCount: "2", eq: "214"}
```

### The four traps stepped on along the way

| # | The symptom | The root cause | The fix |
|---|---|---|---|
| 1 | `invalid input value for enum run_mode: "AUTO"` | The enum values are `normal/simulation/attended/unattended` | Change it to `normal` |
| 2 | `column status is of type run_status but expression is of type node_status` | `run.status` and `run_node.status` are **two enums** | `run` uses `run_status`, `run_node` uses `node_status` |
| 3 | The 1st step `EC-06-0001` | `ShadowProbe` wants `device_instance.id`, but the graph stores `resource_node.id` | Add the `instanceIdOf()` conversion |
| 4 | The variable `eq` is not written back | ① The raw text was stored as a JSON string (one extra layer of quotes on the outside) ② The regular expression was escaped one level too many, so `\s` became a literal backslash + s | Store `?::jsonb`, rewrite `extractField`, strip the outer quotes first |

**The 4th trap is the most hidden**: the log shows `output=equipment_id=eq` and `raw={"message_id":...}` as normal,
but `extractField` simply returns null — **the regular expression itself is wrong**, it is not a data problem.

A comparison of the two ways of writing it:
```java
// Wrong: one extra level of escaping, in the regular expression \s matches "a literal backslash + s"
.compile("\"" + field + "\"\\s*:\\s*(...)")
// Right: only \s is the whitespace match of a regular expression
.compile("\"" + field + "\"\\s*:\\s*(...)")
```

### Features (the method parameter configuration)

When a method is selected on a device node, the property panel on the right automatically fetches the parameter definition of that method and renders the input fields:

```
Method parameters (3)
  plateID       [________]
  plateProtocol [________]
  timeout       [________]
  the value supports ${variableName}, it is substituted at execution time
```

**Where the parameters are stored**: `node.data.params` (in the graph, saved together with `experiment_graph`).

**How they are passed at execution time**: the steps expression gets a 4th segment.

```
id:method:output:k1=v1;k2=v2
200214:BarcodeScan::plate_id=${plate};cycles=5
```

**Measured**:
```
vars=plate=PL-777
steps=200214:BarcodeScan::plate_id=${plate};cycles=5
        ↓
run_command.payload = {"cycles": 5, "plate_id": "PL-777",
                       "resourceId": 200214, "stepSeq": 1}
```

`${plate}` is replaced with `PL-777`, and `cycles` is turned into a number (not a string).

### To do

| Item | Explanation |
|---|---|
| The parameter types | ✅ Rendered according to control_type (number/select/input) |
| The default values of the parameters | ✅ The empty values are filled in when the method is switched (it does not overwrite what the user has changed) |
| The required-field validation | ✅ It is validated when it is saved (the method definition is fetched asynchronously) + the panel is marked in red |

### Features (the parameter types and the default values)

| control_type | Rendering |
|---|---|
| `number` | InputNumber (with a hint about the unit) |
| `select` + there is a `range` | Select (the options are split on `,，;；` and on the newlines) |
| `select` without a range / `input` | Input |

**The write-back of the default values**: after the parameter definition has been loaded, only the **empty values** are filled in and only when the `default` is a simple value (less than 60 characters, no `{`).

**Why the simple value has to be checked**: the `default` of some methods is a fragment of JSON (the sequence of `RunSequence`, for example),
and putting it straight into a single-line input field makes it show up as garbage.

**The trade-off of the required-field validation**: it is marked in red on the panel plus "not filled in", and before the execution only the fields that "the user has explicitly cleared" are blocked.
**The ones that "were never filled in" are not blocked** — because the required information only exists in `device_method.param_schema`,
and fetching the method definition of every device again before the execution is too expensive (one graph may have a dozen devices).

A strict validation belongs in the **saving of the graph**, where it can be fetched asynchronously.

### Features (the four improvements)

**① The required fields are validated when it is saved**

Saving is a low-frequency operation and the user is waiting anyway, so the cost of fetching the method definition asynchronously for the validation is acceptable:

```ts
const uniq = [...new Set(deviceNodes.map(n => n.data.method))];
await Promise.all(uniq.map(m => fetch(`/api/methods/{m}/params`)));
// check the ones that are required but were not filled in
```

**A failure of the validation itself does not block the save** — better to store it than to make the user click for nothing.

**② The same device can be dragged in repeatedly**

Before, `nodes.some(n => n.data.resourceId === rid)` refused it.

**But that restriction is wrong**: the robot arm has to carry the plates again and again, it can be used 5 times in one workflow.

The node id now carries a timestamp `dev-{rid}-{Date.now()}`, and the same device is allowed to appear several times.

**③ The shapes of the nodes tell them apart**

| Type | The shape |
|---|---|
| Fork | A cut corner (`clip-path: polygon`, 12px cut off the top right and the bottom left) |
| Parallel | A double border (`border-style: double`) |
| Loop | A 6px thick border on the left |

A symbol is also added in front of the title: `◇ Fork` / `∥ Parallel` / `↻ Loop`.

**④ The start/end nodes are always there**

Before they were only placed automatically on an **empty graph**. An existing graph (or a graph that was stored directly through the API) did not have them and never did.

It is changed to a check when it is loaded: if the start is missing it is added at the top, if the end is missing it is added 140px below the bottom-most node.

**Why they have to be always there**: the entry into the execution depends on it, without it the user cannot find "where it starts running".

### Features (the device thumbnail + the merge node)

**The device thumbnail**: a device node embeds `public/devices/{capability}.jpg` (44px high),
it is hidden when the loading fails or when the capability has no photo, it does not take up space.

**The merge node**:

| Item | Explanation |
|---|---|
| The shape | The top corner is cut off (symmetric to the cut corner at the bottom of the fork) |
| The connection points | One **incoming** edge on the left and one on the right, one outgoing edge at the bottom |
| The execution | It is skipped (it is only a visual marker, the branch has already been chosen upstream) |

**The measured graph**:
```
Start → Device ID1731(photo) → ◇Fork(${eq}==214) → Device ID1775(photo)
                        → ⌄Merge → Device ID1183(photo) → End
```

**Why the merge node is skipped at execution time**: the fork has already "chosen one path", and when the execution reaches the merge point
the branch that had to be taken is already finished, the merge is only a marker that says "from here on the two paths join".

### Documents

`实验编排-功能设计.md` was extended to 818 lines, with a new chapter 13 "The implementation details of the frontend and the backend":

| Section | Content |
|---|---|
| 13.1 | The overview of the data flow (browser → database → executor) |
| 13.2 | The state design of the frontend + the complete fields of NodeData |
| 13.3 | The dragging and the automatic connection (why the HTML5 drag) |
| 13.4 | The design of the edges (the arrow, the three ways to delete) |
| 13.5 | The shapes of the nodes (clip-path / double border) |
| 13.6 | The device thumbnail |
| 13.7 | The division of labour in the execution: the frontend computes the path, the backend runs the steps |
| 13.8 | The five things ExperimentFlowRunner does at every step |
| 13.9 | The mapping of the enum values (the trap of the two status enums) |
| 13.10 | The regular expression of the output variable parsing (one extra level of escaping) |
| 13.11 | What is still to be implemented |

### Features (the transport of the plates: the robot arm is inserted automatically)

**The problem**: the connection `A → B` only executes the method of A or of B, so the plate never gets across.

**The key discovery**: the robot arm has `Transfer(source, target)` —— which is exactly the atomic operation "carry it from A to B".

**The implementation**: while it walks over the device nodes it checks the two adjacent stations, and **when neither end has the TRANSFER capability** it inserts one in the middle:

```ts
steps.push({
  resourceId: arm.resourceId,
  method: 'Transfer',
  params: { source: prevDev.code, target: d.label },
});
```

**Measured** (Start→robot arm→fork→ID1775→merge→ID1183→End):

```
1. Home        resourceId=200214
2. Deposit1    resourceId=200210      ← ID1775 puts the plate in
3. Transfer    source=ID1775 target=ID1183   ← inserted automatically
4. Deposit1    resourceId=200171      ← ID1183 takes the plate in
```

**The limitations**:
- the robot arm always takes the first TRANSFER on the island
- the source/target pass the device code, whether the driver accepts it still has to be verified on site
- there is no pre-check of "whether the plate is there"

### Features (the teaching of the points)

The "Robot arm point (teaching)" is added at the bottom of the property panel of a device node:

```
Point code = device code, fill in the coordinates where the robot arm is currently standing in front of that device
  [X: ____] [Y: ____] [Z: ____]  [Record point]
```

- `PUT /api/islands/{id}/robot-points/{code}?action=PickPlace&deviceResourceId=`
- only the fields that carry a value are updated (when teaching, maybe only the coordinates change and not the linked device)
- `updated=0` says "the point does not exist, sync the points first"

**Why the numerical input and not drawing it on the canvas**: the robot arm is three-dimensional (x/y/z + the pose),
the canvas is two-dimensional and drawing it would mislead.

**The procedure on site**: drag the teaching device in front of the device → read the coordinates → fill them in on the canvas → record the point.

### Features (reading the coordinates from the robot arm)

```bash
POST /api/islands/{id}/robot-points/{code}/capture?robotResourceId=&method=Home
```

It calls the method of the robot arm → finds `x`/`y`/`z` in the returned raw text with a regular expression (`pos_x`/`X` are recognised as well) → updates the point.

**The reason for matching several field names**: the drivers of the different vendors return different formats, and hardcoding one of them means one vendor can never be read.

**Measured in the mock environment**:
```json
{"captured": 0, "reason": "驱动返回里没有 x/y/z 字段",
 "raw": "{...data:{position:"A1",plate:"PL20261029"}...}"}
```
The mock only returns the position code and not the coordinates, **that is the expected behaviour**; the raw text is returned along with it so that the site can troubleshoot.

**The trap of the two sets of ids was stepped on again (the fifth time)**: `probe(robot, ...)` is passed the resourceId(200214),
it should first be converted to the instanceId(214). `instanceIdOf()` has been added to `QueryService` so that it can be reused.

### Features (the coordinates take part in the dispatch)

By default only the point code is passed. When `withCoords=1` is added in the graph the coordinates are filled in automatically:

```
200214:Transfer::source=ID1775;target=ID1183;withCoords=1
        ↓
run_command.payload = {
  "source": "ID1775", "target": "ID1183",
  "source_x": 420.5, "source_y": 120.25, "source_z": 0.0,
  "target_x": 420.5, "target_y": 120.25, "target_z": 0.0
}
```

**`withCoords` is consumed and does not go into the payload**: it is an instruction and not a device parameter.

**A point without coordinates is silently skipped**: it does not block the execution.

**Why it is not added by default**: some drivers strictly validate the parameter names and reject an unknown parameter.
Turning it on explicitly when the coordinates are wanted is safer than "send everything by default and turn it off when there is a problem".

### Features (the CSV import and export of the points)

```bash
GET  /api/islands/{id}/robot-points.csv          export
POST /api/islands/{id}/robot-points.csv?robotResourceId=  import (upsert)
```

**Why CSV and not Excel**: the teaching device can usually export a CSV; bringing in a `.xlsx` parsing dependency for a low-frequency feature is not worth it.

**The import uses COALESCE**: the rows of the exported table without coordinates (x is empty) must not clear the coordinates that are already there.

**The `device_code` is resolved automatically** into a resource_node.id, the id does not have to be typed in by hand.

**Measured**:
```
Export 6 points → change the coordinates of 2 rows → import {"imported":2}
ID1183 goes from 420.5/120.25/0 to 444.4/555.5/66.6
```

### Documents (the new testing strategy)

`docs/测试与上线策略.md` —— the five-layer verification model:

| Layer | What it verifies | The pass criterion |
|---|---|---|
| L1 unit + integration | The logic of the code, the state machine, the database | All green + the processes the full set |
| L2 the single command probe | **Whether the contract is right** | All 4 contract items are true |
| L3 the shadow run of a whole workflow | **Whether the command sequence is right** | The device allocation / method name / parameters / order are all right |
| L4 the segmented integration | **Whether the cooperation is right** | Single device→single workflow→several devices→the whole island |
| L5 the production canary | **Whether it is stable** | The rollout is increased week by week, 0 incidents per level |

**The core points**:
- if a layer fails, it does not go further down
- if the shadow run does not work, the real run certainly does not work (it reuses the same allocation and substitution logic)
- two control instances must never operate the same batch of devices at the same time

### Features (the thumbnail in the device list)

Every row of the "Devices on the island" list on the left of the experiment canvas gets a 36×28 thumbnail, and the capabilities without a photo show the abbreviation of the capability.

### Documents (the timing of the commands)

`测试与上线策略.md` got a chapter 10: send one, wait for one.

**The core fact**: `ShadowProbe.probe()` is a synchronous method, when it returns the result is already there;
a `for` loop is serial by nature, there is no "send it off and forget about it".

**The three timeouts**:

| Layer | The timeout | Where |
|---|---|---|
| The command level | 15 seconds | `DriverCommand.timeoutMs()` |
| The read timeout | 30 seconds | `socket.setSoTimeout(30_000)` |
| The connection level | A deadline | `t0 + cmd.timeoutMs()` |

**The read timeout is longer than the command timeout**: it leaves a margin, so that a command that is still on its way is not judged dead.

**After the timeout the following steps are interrupted**: doing the next step before this one is finished can physically crash the machine.

**The asynchronous reporting mode is not supported**: right now it is one question one answer (a short TCP connection).
The judgement is based on `method_status` — a return of 2 is synchronous, a return of 1 (running) means it is asynchronous and it would have to be reworked.

**How to verify that the timing is right**: look at `run_command.result->>'durationMs'`.
If it is all 0 or a single digit, it is going through the mock and it is not really waiting for the device.

### Features (the persistent-connection driver)

`TcpPersistentDriver`: one resident connection, the requests and the responses are paired by `message_id`.

```
execute(cmd)          → the messageId goes into pending → write to the socket → future.get(timeout)
readerLoop()          → read, framing by the delimiter → parse the messageId → pending.remove(id).complete()
```

**The switch**: `LABOS_DRIVER_MODE=persistent`

**The three points that have to be handled**:
- **the reconnection**: it reconnects automatically, otherwise a single hiccup means a permanent loss of contact
- **the serialised write**: `synchronized (writeLock)`, several threads must not write at the same time
- **the cleanup of the dangling ones**: after the timeout `pending.remove(id)`, otherwise when the device really answers later it stuffs it into a future that nobody is waiting for

**A key problem was found as well**: `buildDriverRegistry()` creates a new driver on every call,
so the persistent connection is the same as no persistent connection. It is only really reused after adding a `cachedRegistry` + a 60 second TTL.

**Measured**:
```
Without the cache: [Driver] persistent connection mode  it is printed every time
With the cache: it is printed only once, the 4 commands took 69/64/61/62ms
```

**What is still not done**: `method_status=1` (running) now returns a failure straight away.
On a real machine the future should be kept and waited on, and only a 2 should complete it.

### Architecture (the device gateway + the Redis state centre, P0 done)

The new `docs/设备网关与Redis状态中心-设计.md`:

| Component | The responsibility | What it does not do |
|---|---|---|
| The control console | The orchestration, the scheduling, the alerting, the audit | It does not touch the socket |
| Redis | The command queue, the results, the device state | It does not store the business data |
| The gateway | The persistent connection, the framing, the reconnection, the state reporting | It does not judge the business semantics |

**The five structures in Redis**:

| Key | Type | The purpose |
|---|---|---|
| `labos:dev:state:{id}` | Hash | online/status/method/startedAt |
| `labos:cmd:queue:{driver}` | List | the control console LPUSHes, the gateway RPOPs |
| `labos:cmd:result:{commandId}` | String + TTL 300s | The result of the command |
| `labos:cmd:pending:{driver}` | Hash | The commands that are on their way (it prevents the dangling ones) |
| `labos:dev:online` | Set | The statistics of the online devices |

**Why the result uses a key+TTL and not Pub/Sub**: a Pub/Sub subscriber that is not online misses it forever;
with a key+TTL even a late subscriber can fetch it. **A command result must not be lost**.

**P0 is done**: `RedisClient` (the RESP2 protocol written by hand, 200 lines).

**Why Lettuce/Jedis are not brought in**: the classpath only has the PG driver,
and bringing in the whole Netty dependency set for a few commands is not worth it.

**Measured**:
```
PING: true
GET: world
HGET: running
RPOP: {"m":1}
```

**P1 is done**: the separate Maven project `labos-gateway/` (see its README).

| Class | The responsibility |
|---|---|
| `GatewayMain` | The parsing of the configuration, one consumer thread per driver, the state printed every 30 seconds |
| `DriverConnection` | The persistent connection, the framing, the pairing by messageId, the writing to Redis |
| `RedisClient` | The RESP2 protocol written by hand (copied from the kernel) |

**The end-to-end measurement** (without the control console):
```
redis-cli LPUSH labos:cmd:queue:DRV-LOCAL-MOCK '{"commandId":"gw-test-001",...}'
  ↓ the gateway consumes it → TCP → the mock → the response comes back
GET labos:cmd:result:gw-test-001
→ {"ok":true,...,"raw":"{\"method_status\":2,...}"}
HGETALL labos:dev:state:214
→ status=idle, method=Home, commandId=gw-test-001
```

**The technology choices**:

| Point | The reference | Here | The reason |
|---|---|---|---|
| The framework | Spring Boot | The bare JDK | The gateway is a pipe, it does not need DI; the start-up is <1 second |
| The network | Netty | The native socket | One connection per thread is enough |
| Redis | Redisson | 256 lines written by hand | Netty+Reactor is not brought in for a few commands |
| The serialisation | The RLock distributed lock | One consumer thread per driver | Only one instance of the gateway connects to a driver |

**The next step**: P2 `RedisQueueDriver` → P3 `/api/devices/live`

## To do and the known problems

| Item | Type | Explanation |
|---|---|---|
| The duration accuracy is 10 seconds | Data | The source data only has a 10-second resolution, the prediction upper limit is bound by it |
| The link between the experiment and the workflow is empty | Data | All 650 experiments have no workflow link |
| 63 devices have no IP | Data | It has to be filled in on site |
| The 26 islands include the test islands | Data | `测试岛屿1`, `12345` and the like, the interface has to tell them apart |
| The comparison of the Gantt prediction with the actual | Feature | The interface exists, the frontend only draws the actual |
| The duration distribution as a weekly trend | Feature | Right now there are only the static p50/p90 |
| The `run_after` of the experiment orchestration | Feature | The field exists, only the order was implemented |

---

## The record template (copy it when you add an entry)

```markdown
## YYYY-MM-DD

### Features
| Item | Explanation |
|---|---|

### Bugs
**BN. The title in one line**
The symptom / the root cause / the fix / the lesson

### Design decisions
**DN. The title of the decision**
Why it is done this way and not the other way, and what that costs
```

### Features (the plate flows: the devices rotate)

**The problem**: both INCUBATE nodes take `candidates.get(0)`, they pick the same device, and the plate never moves.

**The fix**: `GraphRunner` gets a `usageCursor` (it records the number of allocations per capability):

```java
int used = usageCursor.merge(cap, 1, Integer::sum) - 1;
initial = candidates.get(Math.floorMod(used, candidates.size()));
```

Together with `WorkflowService.preferEmptyDevices` (the empty devices come first in the candidate pool),
the rotation = the empty devices are used one after the other.

**Measured** (239, two INCUBATE nodes):
```
Deposit1   → ID1183
Withdraw1  → ID1775     ← finally not the same one
```

### Bugs (several processes contending for the mock driver caused a 30 second timeout)

**The symptom**: the workflow execution timed out stably at 30007ms, but `/api/shadow/probe` with the same device and the same method succeeded in 62ms.

**The root cause**: `lsof -i :19301` showed **two Java processes** connected to the mock at the same time (the leftover process 48441 held 3 connections that it did not release).
The mock handles them one at a time, so the commands of a new connection wait for a response that never comes.

**Why the probe succeeds**: the probe uses its own short connection, and it may happen to be the one that gets served.

**The lesson**: when `pkill -f` has not killed everything you have to use `pkill -9`, and **you have to confirm with `lsof -i` that the connections on the port are cleaned up**.
Looking only at "the number of processes = 0" is not enough.

### The problem (the two sets of plate position states disagree) — to be fixed

```
device_state.has_plate     device level: there is a plate / there is no plate
position_state.plate_id    layer level: which layer holds which plate
```

When a `Deposit1` goes to a device **without a layer position record**:
- `device_state.has_plate` becomes true (it is written during the workflow execution)
- `position_state` has no record (`notePlateMove` skips it when it cannot find a free layer position)

The result: when a `Withdraw1` takes the plate from the same device, `pos=null` (there is no plate on the layer position).

**The suggested fix**: `device_state.has_plate` should be derived from `position_state`
(any layer position of that device holds a plate → has_plate=true), and not maintained independently.

### The fix (the two sets of plate position states are unified into a single source) — partly completed

**What was changed**:

| Item | The change |
|---|---|
| The data source of `has_plate` | It is derived from `position_state` now (`refreshHasPlate`) |
| `PositionLock.markDevicePlate` | All the calls are removed (it wrote has_plate directly) |
| `WorkflowService.markPlatePosition` | **Deprecated** (at the end of the workflow it marked "the device that was executed last" as holding a plate, which conflicts with the layer level) |
| `putPlate` | A pure UPDATE → an **UPSERT** (position_state may not have that row yet) |
| The failure signal | The new `NO_EMPTY_POSITION(-1)` / `NO_PLATE_TO_TAKE(-2)`, distinguished from null (nothing has to be registered) |

**What has been confirmed to work**:

```
After the execution of 239:
  ID1183  has_plate=f
  ID1775  has_plate=f      ← there is no longer the dirty data of "no layer position but it shows a plate"
```

The full correction SQL (it clears the historic dirty data in one go):

```sql
UPDATE device_state ds
SET has_plate = EXISTS (
        SELECT 1 FROM resource_node r
        JOIN position_state ps ON ps.resource_id = r.id AND ps.tenant_id = r.tenant_id
        WHERE r.parent_id = ds.resource_id AND r.tenant_id = ds.tenant_id
          AND ps.plate_id IS NOT NULL AND ps.plate_id <> ''
    ), updated_at = now()
WHERE ds.tenant_id = 1;
```

**⚠ Still not solved**: `putPlate` does not land in the database on the Java call path.

```
The log: [PLATE] Deposit1 dev=171 plate=${plate} -> pos=504495
      it means that notePlateMove returned a position
The layer position: there is no 504495 row in position_state
```

**What has been ruled out**:
- The UPSERT statement itself is fine (running it by hand succeeds with `INSERT 0 1`)
- The parameter indexes are right (1=tenantId, 2=resourceId, 3=plateId, 4=runId)
- There is no `[PLATE-ERR]` log (which means that no exception was thrown)

**The direction of the next investigation**:
- whether the `c.commit()` of `putPlateInternal` really runs (it may be outside the `if (n > 0)` branch)
- or whether the transaction was rolled back by the outer layer

### The lesson (the third error of the same kind)

This time the "the replace anchor did not match and it failed silently" was stepped on again:
- I wanted to change `notePlateMove(deviceId, method, null, runId)`, but the code was already the `plateId` version → the replacement had no effect
- I wanted to add a constant, but the anchor `/** 默认预约 TTL（秒）。 */` no longer existed → the replacement had no effect

**They all showed up as "it was changed but it did not take effect", the same kind as the wrong parameter index and the compilation illusion before.**

**The hard rule from now on**: after a `replace` it has to be confirmed with `python3 -c "print(s.count('新代码'))"` that the count is not 0 (the string '新代码' is the new code that is searched for).

### Documents (the review material put in order)

Two new ones, for the external review:

| Document | Content |
|---|---|
| `docs/评审指引.md` | What the project is, which documents to read first, how to run it, the code map, **the list of the known problems**, the review suggestions |
| `docs/技术架构与模块地图.md` | The package structure, the responsibilities of the key classes, the data model, the four data flows, the direction of the module dependencies |

**Why a separate review guide has to be written**: the existing documents are 5191 lines, and the external reviewer does not know where to enter.
The value of the review guide lies in **listing the known problems explicitly** — including the unresolved `putPlate` bug that does not land in the database.
Review material without the problems listed has no review value.

**The problem that was exposed at the same time**: `docs/评审指引.md` refers to the "technical architecture and module map",
but that document did not exist (there were only scattered architecture descriptions in the various design documents).
The gap was only found while the guide was being written — **it shows that the documentation system is missing an "overview layer"**.

### The fix (putPlate does not land in the database — the review gave the root cause)

**The external review (Grok, 2026-11-01 21:40) pointed at the root cause against the source code, my earlier judgement was wrong.**

**The real chain**:

```
tryReserve inserts/updates position_state (succeeds)
  ↓ the same transaction
putPlate → UPDATE position_state SET plate_id (n=1)
  ↓ the same transaction, inside the if (n > 0)
PositionLock.markPlatePosition(c, ...)  ← it writes the plate table
  ↓ the column names are wrong: the code writes plate_id/current_pos/current_run, the table only has barcode/location_id
the SQL fails → the whole transaction is voided
  ↓
c.commit() throws → rollback
  ↓
the update of position_state is gone with it
  ↓ the exception is swallowed by the catch (SQLException ignore)
the log only shows [PLATE] ... -> pos=504495 (the pos that notePlateMove got is the value in memory)
```

**Where my earlier judgement was wrong**: I only looked at that one UPDATE of `putPlate`,
**and did not realise that there was another statement in the same transaction that writes the plate table** — a failure of any one statement in a transaction takes the whole thing down with it.

**The fix**:

1. The write to the `plate` table is **moved out of the main transaction** (after the commit of `putPlateInternal`, with an independent connection)
2. The column names are changed to the actual structure: `barcode` / `location_id`
3. A failure of the `plate` write only records a `[PLATE-WARN]`, it does not affect the position lock

**The two places where a "failure was taken for a success" were fixed at the same time**:

| Location | The problem | The fix |
|---|---|---|
| `notePlateMove` | It does not look at the number of rows returned by `putPlate`, with 0 rows it still does `return pos` | When 0 rows are returned it returns `NO_EMPTY_POSITION` |
| `tryReserve` | When 0 rows are updated and no record is found it still returns `OK` | It returns `NOT_FOUND` |

**The verification**:

```
After the execution of 239:
  the layer position ID1183 / I4495 / ${plate}     ← the plate ID finally goes in
  has_plate: ID1183=t, ID1775=f     ← consistent
```

### The lesson (the fifth "I thought it was changed, but it was not")

Review C5 pointed out: "the development log says there is no `[PLATE-ERR]`, but according to the source code a failed commit should print that line."

**The verification**: the log was cleared on every restart by `nohup > /tmp/apiserver.log`, so the historical error lines could not be seen.

**The fix**: use `>>` to append instead.

**This is the fifth error of the same kind**:
1. The compilation illusion (`grep error` cannot match the Chinese "错误")
2. The wrong parameter index (`setLong(13)` is actually the 12th, a silent NULL)
3. The `replace` anchor did not match (3 times)
4. Several processes contending for the driver (`pkill -f` did not kill everything)
5. The log was cleared by the restart (`>` vs `>>`)

**What they have in common: the means of verification itself was defective, which made it "look like there was no problem".**

### The verification (the end-to-end confirmation of the putPlate fix)

| Layer | The verification | The result |
|---|---|---|
| The data layer | `position_state` has I4495/${plate} | ✅ |
| The interface layer | `/api/positions?islandId=` returns 1 layer position with a plate, available=false | ✅ |
| The interface layer (the screen) | The details of ID1183 show "occupied 1 / free 7", I4495 is marked in pink with the plate ID | ✅ |

**A screenshot of the interface**: I4495 in pink (there is a plate), the other 7 in green (empty),
with the hint "green = empty, pink = there is a plate, orange = reserved".

**This chain really works now**:
```
The device action succeeds
  ↓
notePlateMove looks for a free layer position
  ↓
tryReserve occupies it (INSERT ON CONFLICT)
  ↓
putPlate writes position_state (UPSERT, the main transaction)
  ↓ the commit succeeds
  ↓
markPlateOutsideTx writes the redundant plate table (an independent connection, a failure does not affect it)
  ↓
refreshHasPlate derives the device-level has_plate
  ↓
/api/positions → the pink square in the interface
```

### New documents (the specifications)

| Document | Content |
|---|---|
| `docs/评审规范.md` | The format of the review records (number/time/model/scope/conclusion), the severity grading (P0-P3), the rules of the response, the rules of the documentation layers |
| `docs/提交规范.md` | When to commit, the format of the commit message (type/scope/description/body), the known problems that have to be written down, the set of counter-examples |

### Naming

The package names, the Redis prefix, the frontend storage and the environment variables use `labos`.

| Item | Name |
|---|---|
| The kernel package | `com.labos.kernel` |
| The gateway package | `com.labos.gateway` |
| The project directory | `labos-kernel` / `labos-gateway` |
| The Redis prefix | `labos:dev:state:` and the like |
| The frontend storage | `labos_token` / `labos_canvas_exp` |
| Environment variable prefix | `LABOS_*` |
| The default database name | `labdb` (on site it is overridden with `LABOS_DB_NAME`) |

**The trap that was stepped on**: `pkill -f` does not support the `\|` syntax of grep, and neither of the two processes was killed,
so the new service reported `Address already in use`. It was changed to `pkill -f ApiServer` + `lsof -i :port` to confirm.

### The fix (the schema script was incomplete — hidden but serious)

**The problem**: the table creation script of `pg_rebuild/schema/` was **missing 17 tables**:

```
device_state  position_state  robot_point
experiment_run  experiment_run_step  experiment_process
experiment_variable  experiment_graph  experiment_sample
island_flow  alert_state  plate_well  resource_booking
capability_recipe  workflow_graph_history  run_variable  run_artifact
```

**The reason**: these tables were **created directly with SQL on the test database** during the development, and they were never written back into the schema script.

**The consequence**: after a newcomer clones the repository and creates the database following the README, **a complete database cannot be created**, and the kernel reports that the table does not exist as soon as it starts.

**This is far more serious than "the documentation is out of date"** — a wrong document can be checked against the code, but a schema that is missing tables means the project does not run.

**The fix**:

```bash
pg_dump --schema-only --no-owner --no-privileges \
  -t public.device_state -t public.position_state ... > missing_ddl.sql
```

The DDL of the 17 tables (1087 lines) is appended to `30_business.sql`,
and `90_all_in_one.sql` is regenerated (1326 → 2070 lines, 74 table creation statements).

**The verification**: it was tried out in a temporary schema, and all 17 tables were created successfully.

**The lesson**:

> **When you change the database directly during the development, you have to write it back into the schema script.**
> Nothing will remind you of this — the test database runs, the code runs,
> and only the single scenario of "creating the database from scratch" exposes it.

### The lesson (the seventh "I thought it was fine, but it was not")

The first six were "it was changed but it did not take effect", this time it was **"I thought the schema was complete, but 17 tables were missing"**.

The common point has not changed: **the way of verifying does not cover the real scenario**.
`99_verify.sql` only checked the tables of the platform layer and of the kernel layer, it did not check the business layer.

### The fix (a long task was judged to be a timeout — a P0 prerequisite)

**The problem**: when the device answers `method_status=1` (running), the driver judges it as a failure straight away.

On a real machine the incubation takes 30 minutes, and the driver first reports "running" and then "complete".
The current code **returns a failure as soon as it receives the first "running"**, which means that no long task can run at all.

**The fix**:

| The driver | Before | After |
|---|---|---|
| The persistent connection `TcpPersistentDriver.dispatch` | After `pending.remove(id)` it completes unconditionally | **It looks at the status first**: when it is =1 it neither removes nor completes, it waits for the next frame |
| The short connection `TcpDriverAdapter`, the frame reading loop | It returns as soon as it receives the FINISH frame | When the status=1 it does `continue` and keeps reading |

**The key point**: in the persistent connection `pending.remove(id)` has to happen **after it has been confirmed that it is not running**.
Removing it first and judging afterwards loses the future, and when the device really reports the completion later nobody picks it up.

**The way to verify**: `LABOS_MOCK_LONG_TASK=1` was added to `MockDriverServer`,
it makes it send two frames in a row (`status=1` → `status=2`), and an independent main was added so that it can be started on its own.

```
Testing the mock by hand: frame 1 status=1, frame 2 status=2  ✅
The driver probe:         ok=true, method_status=2, 13ms  ✅
```

**The trap**: at first it was assumed that 19301 was the Java mock, but in fact it is the **.NET sidecar** process.
The code of the Java mock was changed for a long time without any effect — because it was not the one that was connected.

**The lesson**: before changing anything, use `lsof -i :port` to see **who is listening**,
do not assume that "the class I am changing is the one that is running".
This is the eighth time that "the means of verification itself was the problem".

### Features (one simulator per device + the registration of several drivers)

**The problem**: before, one `MockDriverServer` simulated all the devices, and the real scenarios could not be reproduced:

| One mock for everything | One simulator per device |
|---|---|
| All the devices respond at the same speed | The slowness / the failure can be set individually |
| The concurrency cannot be reproduced | Real concurrency across several of them |
| Killing one stops the whole line | One of them can be killed on its own to verify the offline |
| **A wrong routing does not report an error** | **The endpoints are isolated, sending it to the wrong one simply cannot connect** |

**The implementation**:

1. `sim/DeviceSimulator.java`: the simulator of a single device, with a parameterised behaviour
   - `--delay` simulates the duration of every step
   - `--long` answers "running" first and then "complete" (it verifies the long tasks)
   - `--fail N` an intermittent failure (it verifies the retry)
   - `--offline` does not listen on the port (it verifies that the device is offline)
   - **It validates the equipment_id**: a command for another device raises a clear error, it does not pretend to succeed

2. `scripts/sim-up.sh` / `sim-down.sh`: they start and stop the simulators of the whole island with one command
   - look up the devices → assign the ports (starting at 19500) → create the driver records → update device_instance.driver_id → start the processes

3. `WorkflowService.buildDriverRegistry`: it reads **all** the active drivers from the database and registers them one by one
   - every driver only recognises the devices under its own name (`device_instance.driver_id`)
   - the drivers without a capability are skipped (it logs them, it does not interrupt)
   - when there is not a single one it falls back to the single endpoint mode

**Measured**:

```
7 simulators started:
  ID0000  port 19500  ALIQUOT
  ID1183  port 19501  INCUBATE
  ID1731  port 19502  TRANSFER
  ...

The execution of 239 (two INCUBATE nodes):
  finished 2/2, viaDriver=true
  sim-ID1183.log received 1 (Deposit1)
  sim-ID1775.log received 1 (Withdraw1)    ← each of the two devices got its own
```

**Cross-platform**: it is pure Java, one jar runs on mac/win/linux, there is no need to package it separately.

**The traps that were stepped on**:
1. In the script `$VAR：` the full-width colon was taken by bash as part of the variable name → everything was changed to `${VAR}`
2. The `driver` table has no `kind` column (it is the `protocol` enum), and `status` is a smallint and not a string
3. `driver.code` has no unique constraint, so `ON CONFLICT` cannot be used → it was changed to delete first and then insert
4. Some devices do not declare a capability, so the construction of the `DriverDescriptor` fails → it is skipped and not interrupted

### Features (the device heartbeat + the three states + the result return)

**1. The heartbeat (online/offline)**

The new `driver/HeartbeatService`: every 10 seconds it does a TCP liveness probe per driver endpoint,
and the result is written to `driver_health` + `device_state.online`.

**Why only the control console can start it**: in this protocol the control console is the client and the driver is the server,
and the server cannot push to a client that is not listening, so it can only be polled.

**Why per endpoint and not per device**: the path is shared under the same endpoint, and connecting to each of them individually would multiply the number of connections by N.

**The trap**: at first `device_state.online` was written with a pure UPDATE,
but the device may never have had a `device_state` record (it has never been executed) → 0 rows are updated →
it shows up as "all 7 simulators are running, but the interface only shows 2 as online". It works normally after it was changed to an UPSERT.

**2. The three states (idle/running/error)**

`onStepStart` marks running + `current_method`, `onStepDone` marks it back to idle/error.

**The trap (the ninth time with the two sets of IDs)**: `onStepStart` is passed the `device_instance.id` (171),
while `device_state.resource_id` is the `resource_node.id` (200171).
A direct comparison updates 0 rows **without raising an error**, and it shows up as "the running state cannot be found".
It works normally after a subquery conversion was added (the log confirms "1 row updated").

**3. The result return**

The `data` field of the response is stored as it is in `run_command.device_raw_response`.
There are three ways to get the result: a SQL query on the jsonb, the automatic passing through the output variables, `/api/runs/{id}`.

**4. The frontend**

The device cards get a status dot: green=online and idle, yellow=running (the method name on hover),
red=error, grey=offline (no glow).

**5. The interfaces**

`GET /api/heartbeat` returns the online state of every endpoint;
the device object of `/api/topology` got `online`/`status`/`currentMethod`/`hasPlate`.

**Measured**:
```
[Heartbeat] 7 drivers online
ID1183  online=True  status=idle
...
During the execution of 239: Deposit1 took 4053ms (4 seconds slower)
```

**The new document**: `docs/设备状态与心跳-设计.md`
(the two dimensions, the four combinations, why only the control console can start it, the trap of the ID conversion, how the result is fetched)

### The fix (the frontend does not refresh the state of the devices automatically)

**The problem**: the online / running state of the devices is written into the database, but **the frontend does not refresh** —
you can only see "a device has gone offline" or "it is executing" after a manual reload of the page.

**The process of the verification** (three screenshots):

| Stage | The interface | The database |
|---|---|---|
| Before the execution | All green dots | All idle |
| During the execution (ID1183, the slow device, 15s) | **ID1183 has a yellow dot** | `ID1183 \| running \| Deposit1` |
| After the execution | All green dots | `ID1183 idle` |

**The first "during the execution" screenshot came out empty** — the interface was still green, because it did not refresh.
That exposed the problem: **the state data was there, but nobody fetched it**.

**The fix**: `Island.tsx` gets a 5 second polling:

```tsx
useEffect(() => {
  const timer = setInterval(() => setTopoReload((n) => n + 1), 5000);
  return () => clearInterval(timer);
}, []);
```

**At the same time it was changed to a silent refresh**: the first load shows the loading state, the later polls do not touch the loading state,
otherwise it flashes once every 5 seconds and it looks bad.

```tsx
const firstLoad = useRef(true);
if (firstLoad.current) setTopoLoading(true);
```

**The lesson**: a working data path does not mean the user can see it.
Between "it is written into the database" and "it is shown in the interface" there is still a refresh mechanism —
this gap does not show up during the testing (the test always reloads first).

### Features (the alert for a device that is offline)

**The rule**: `online=false` and `last_heartbeat` is more than 30 seconds old → the alert is opened; the recovery → it is closed automatically.
The idempotency key is `DEVICE-OFFLINE|<resourceId>`, so there is only ever one open alert per device.

**The trap that was stepped on at the first go-live: 169 false alarms were opened at once.**

The reason: the drivers of the 169 devices in the database point at 5 real IPs that have never worked,
and the UPSERT of the heartbeat **writes `last_heartbeat` whether it is online or not**,
so all of them were judged as "offline for more than 30 seconds".

Two corrections:
1. `last_heartbeat` is only written when it is online (`CASE WHEN ? THEN now() ELSE NULL`)
2. Only the devices that "have been online before" raise an alert (`last_heartbeat IS NOT NULL` is added)

**The measured closed loop**:
```
Kill ID1183 → after 40 seconds: ID1183 alert=open (offline for 59 seconds)
                    ID1731/ID1775 are normal, no alert
Restart ID1183 → after 25 seconds: [Heartbeat] 1 offline alert closed
                      ID1183 alert=closed
```

**The new interface** `GET /api/device-alerts`, kept apart from the execution error alerts of `/api/alerts`:
the former is "it cannot be connected" and you have to look at the network and the power, the latter is "the execution failed" and you have to look at the run_command.

### Features (the expression engine, M1.1 done)

**The problem**: `substVars` only does a plain substitution (`${i}` → the value), so a workflow cannot write `${usedSlots + 1}`.

**The implementation**: the package `com.labos.kernel.expr`, four classes:

| Class | The responsibility |
|---|---|
| `Expression` | The tokenizer + the recursive descent parser + the AST (~400 lines) |
| `Evaluator` | The evaluation (with the short circuit, the division by zero check, the type check) |
| `Template` | It handles the "an expression embedded in a string" like `ID1183-L${i}` |
| `Values` | The type conversion (string↔number), the path lookup, the equality check |

**Why no script engine is brought in**: Nashorn has been removed in JDK 15+, GraalJS is dozens of MB,
and both of them can execute arbitrary code. Writing 400 lines yourself means zero dependencies, it can only do arithmetic, and the behaviour is controllable.

**The two traps that were stepped on**:

1. **`ID1183-L${i}` is not an expression, it is a template**
   At first it was parsed directly with `Expression.parse`, which reported "the character '$' cannot be recognised".
   The `Template` class was split out: it looks for `${}` in the string and calls `Expression` for every part.

2. **The variables are all Strings, so `i + 1` becomes "31"**
   The workflow variables are stored in a `Map<String,String>`, and with the `+` operator
   the rule "if one side is a String it concatenates" makes `"3" + 1` → `"31"`.
   It was changed to "**if both sides can be treated as numbers it does an addition**" (`Values.isNumeric`).
   For the same reason `requireNum` changed from "check whether it is a String" to "check whether it can be parsed".

**Measured**:

```
ExpressionSelfTest   30/30
  ID1183-L${cycles + 1}     → ID1183-L5
  ${floor(cycles / 2)}      → 2
  ${plate}-RAW              → PL-777-RAW
  ${i * 2} (i="5")          → 10
  1 / 0                     → the exception "除数为 0（表达式 1 / 0）"
  undefinedVar              → the exception "变量未定义：undefinedVar"

End to end (running 239, passing vars=cycles=4,plate=PL-777):
  run_command.payload = {cycles: 4, plate_id: PL-777}   ← the substitution took effect
  the variables that were not passed keep their ${xxx} as they are  ← as designed
```

**The boundary of the expression capability (deliberately not done)**: the string functions, the function definitions, the loops, the object arrays, the regular expressions.
**The expression only "computes a value", it does not "write logic".**

### Features (M1.2 the tables are created)

The new `pg_rebuild/schema/93_m1_parallel.sql`, and it is merged into `90_all_in_one.sql`.

**The 5 new tables**:

| Table | The purpose | The key constraint |
|---|---|---|
| `run_result` | The asynchronous results | `UNIQUE(tenant, run, result_key)` |
| `device_port` | 1~N entrances/exits, with a direction | `direction IN (in,out,both)`, `capacity >= 1` |
| `device_port_usage` | The channel semaphore | `CHECK (in_use >= 0 AND in_use <= max_capacity)` |
| `device_access` | The gate / barrier | `state IN (closed,open,opening,closing,unknown)` |
| `device_point_template` | The template of the point attributes | The primary key is `device_model` |

**`robot_point` gets 10 more fields**:
point_type / port_id / position_id / access_id / approach_axis /
safe_z_offset / max_stack / height_mm / order_in_device / fill_status

**`plate` / `position_state` get the state fields**:
plate.position_status (KNOWN/UNKNOWN/LOST) + possible_locations (bigint[])
position_state.port_id + block_reason

**2 views for the troubleshooting**: `v_device_access_status`, `v_port_usage`

**The verification**:

```
On the real database: the 5 new tables are created, the 10 fields of robot_point are in place
The semaphore test (capacity=2):
  the first UPDATE → in_use=1
  the second UPDATE → in_use=2
  the third UPDATE → 0 rows (it is full) ✅
The verification on an empty database (with a schema and not a database, because the labos user has no permission to create a database):
  all 101 tables are created, the 5 of M1 are among them
```

**The traps that were stepped on**:
- the syntax `CREATE DATABASE xxx M TEMPLATE` was wrong (an M too many)
- the `labos` user has no `CREATEDB` permission → it was changed to `CREATE SCHEMA verify_test` + `search_path`

### Features (M1.3 the result injection is done)

**The new `workflow/RunResultService`**:
- `save()` writes the result (the same key twice raises an error, it does not overwrite silently)
- `loadAsVars()` loads it and expands it into flat variables
- `exists()` checks the existence (it excludes the expired ones automatically)

**The form of the injection**:

```
results.readPlate.od        = 0.72
results.readPlate.plate     = PL-888
results.readPlate.__exists  = true          ← the existence marker
results.xxx.__expired       = true          ← the expiry marker (the field is not injected)
```

**The expiry semantics**: after `valid_until` has passed, **the field is not injected**, only `__expired` is left.
A positive / negative judgement that is based on a plate reading from 2 hours ago may already be invalid without anybody knowing.

**The connection to `WorkflowService`**:
- the two new ThreadLocals `currentRunId` / `resultsCache`
- in `substVars` **the results are put in first, the workflow variables after** (with the same name the workflow variable wins)
- `withRunId()` initialises, `refreshResults()` updates, `clearVars()` clears

**Measured**:

```
The injection:
  results.readPlate.od        = 0.72
  results.readPlate.plate     = PL-888
  results.readPlate.__exists  = true

The expressions:
  ${results.readPlate.od}                    → 0.72
  ${results.readPlate.od > 0.5 ? 'positive' : 'negative'} → positive
  ${results.readPlate.plate}-L${cycles + 1}  → PL-888-L4
  ${has('readPlate') ? 'there is a result' : 'there is no result'}   → there is a result
  ${has('qc') ? 'there is a QC' : 'there is no QC'}              → there is no QC
```

**The two traps that were stepped on**:

1. `has('readPlate')` always returned false
   The injected key is `results.readPlate.__exists`, while `has()` only looks at `readPlate`.
   Fix: `has()` looks at `varName + ".__exists"` and at `results.` + `varName + ".__exists"` as well.

2. After the code was changed the unit tests passed, but the test class had not been recompiled
   `T3.class` was the stale one, so the stale logic was still running. Deleting the class and running javac again was what made it work.
   **This is the Nth time of "I thought it was changed, but it was not".**

### Features (M1.4 the resource acquisition protocol is done)

**The three new components**:

| Class | The responsibility |
|---|---|
| `DevicePortLock` | The channel semaphore (capacity 1/2/N) |
| `DeviceAccessManager` | The opening and closing of the gate + the inference of the state + the recognition of the manual gates |
| `TransferPlan` | **The fixed total order of the requests + the rollback on a failure** |

**The fixed total order** (`TransferPlan.acquireAll`):

```
① The gate of the source device (open)
② The source channel
③ The source position
④ The gate of the target device (open)
⑤ The target channel
⑥ The target position
⑦ The return position (position-return)
⑧ The robot arm
   ━━━ everything is ready, the plate is still on the source position ━━━
⑨ Pick up the plate (irreversible)
```

**Why the gate comes first**: if the gate is open but the robot arm cannot be taken, the gate can be closed and the attempt abandoned;
the other way round, the plate is already in the gripper and there is no way to put it back.

**The rollback logic**:

```java
catch (RuntimeException e) {
    rollback();      // releases everything that was requested, in the reverse order
    throw e;         // the plate is still on the source position, it can be retried safely
}
```

**The exemption of the return position** (`close()` keeps it, `abortBeforeGrab()` releases everything):
the return position is a "promise" and not a "reservation", once it is released somebody else can put a plate into it.

**Measured**:

```
TransferPlanSelfTest  passed 3, failed 0

  ✓ The request failed and reported the error as expected: 申请 源门 失败：开门动作失败
  ✓ After the failure to open, the channel occupancy has been rolled back (0 → 0)    ← the key point
  ✓ The position is occupied (as expected)
```

**The traps that were stepped on**:
- `position_state` has no `id` column, the primary key is `(tenant_id, resource_id)` —— `resource_id` itself is the identifier of the position
- `PositionLock.tryReserve` returns the `ReserveResult` enum and not a boolean
- the reference of `TransferPlan.AccessOpener` was written as `DeviceAccessManager.AccessOpener`
- `acquireAll` handles the SQLException internally, so the outer layer must not declare it again

### Features (M1.5 the integration is done, the switch controls it)

**`TransferPlan` is connected into `DriverStepExecutor`**, the switch is `LABOS_TRANSFER_PLAN=1`.

**It is off by default** —— changing the core logic of the transport is risky, first let the site verify that the workflows are not affected.

**The integration point**:

```java
// Before
result = driver.execute(buildCommand(ctx, deviceId, method));

// Now
result = executeWithPlan(driver, ctx, deviceId, method);
// When the switch is on and the method starts with Transfer:
//   take the gate → take the channel → execute → close the gate → release
```

**The mapping of the parameters**: the `params.source` / `params.target` of a `Transfer` are device codes,
so `resource_node.code` has to be looked up to get the `resource_node.id` before the resources can be requested.

**Measured**:

```
The switch off: the regression 30/17/14/12/5 is all green, the processes 100%
The switch on: the processes 100% (the SimulatedBus path is not affected)
The real execution (v1 end to end):
  finished 2/2
  the state of the gate unknown (the simulator has no OpenDoor method, as expected)
  the channel occupancy 0/2 (the release is normal)
```

**The finding**: the simulator does not support `OpenDoor`/`CloseDoor`, so a Transfer that opens a gate fails.
**The simulator has to be given these two methods**, otherwise the devices with a gate cannot run a workflow.

**The traps that were stepped on**:
- `Driver` is the wrong type name, it is actually `DeviceDriver`
- the last constructor did not delegate to the private constructor, so `db` was not initialised
- `acquireAll` still declares the SQLException, the outer layer has to catch it
- `StepFailure` is a checked exception, it has to be declared

### Features (M2 the deadlock detection of the wait-for graph)

**The new `driver/WaitForGraph`**:

```java
boolean waitFor(long waiter, long holder)  // records "who is waiting for whom", returns whether a cycle is formed
void release(long runId)                   // clears the edges (the resource was taken or it finished)
```

**The core scenario (the one the user raised)**:

```
Workflow 1 holds A, wants B (B is held by workflow 2) → add the edge 1→2 → the DFS finds no cycle → the wait is allowed
Workflow 2 holds B, wants C (C is held by workflow 3) → add the edge 2→3 → no cycle → the wait is allowed
Workflow 3 holds C, wants A (A is held by workflow 1) → add the edge 3→1
  DFS: 3→1→2→3 ← a cycle → **workflow 3 is rejected**
```

**The connection to `TransferPlan`**:
- taking the robot arm fails → look up the holder → add the edge and detect → a cycle is rejected immediately (it does not wait stupidly for 60 seconds)
- taking a position fails → if it is "reserved by somebody else" → the same detection
- on the release `GRAPH.release(runId)` clears the edges

**A global singleton**: `TransferPlan.graph()`.
If every instance held one of its own, it would lose its meaning — a cycle is formed across the executions.

**Measured**:

```
WaitForGraphSelfTest  13/13
  ✓ The chain 1→2 has no cycle
  ✓ Workflow 3 waiting for workflow 1 is rejected (a cycle)
  ✓ After the rejection there is no 3→1 edge on the graph
  ✓ After workflow 1 releases it, 3→1 is possible again
  ✓ Waiting for itself is rejected
  ✓ A cycle of five: the first 4 are allowed, the fifth is rejected
```

**The key design points**:
- **the edge is added before the detection**: if there is a cycle after it has been added, this wait would deadlock
- **when there is a cycle the edge is withdrawn**, no dirty data is left behind
- **the release has to clear two kinds of edges**: the ones it waits for and the ones that wait for it
  forgetting it leaves zombie edges behind, and a situation that is not a deadlock is judged as one

**A false rejection is an accepted cost**: some requests that could have passed are rejected conservatively.
But a false rejection is only a retry, while a deadlock needs a human.

### Features (the M5 acceptance of several workflows in concurrency)

**The new `scripts/verify-m5.sh`**, it verifies how M1+M2 behave under real concurrency.

**Measured, all 10 items pass**:

```
① 3 workflows really in concurrency           → all finished, the total duration 8.28s
② The same device occupied in the same millisecond → 0 places (the step-level lock works)
   There are 3 pairs of runs whose times overlap    → they really run in parallel
③ The state of the gate                  → none stopped in open
④ The release of the channel            → back to the baseline (0 → 0)
⑤ No run stuck in running / no lock leak
```

**An important clarification of the criterion**:

> **The device lock protects "every single step", not "the whole run".**

Three runs using the same device at different steps is **correct** (A finishes with it, then B uses it).
At first I checked it as "the devices of the whole run do not overlap", and that reported 6 false alarms.

What really has to be prevented is "**two runs using the same device at the same moment**",
so the criterion was changed to "the same device, finished in the same millisecond".

**The traps that were stepped on**:
- `sim-up.sh` did not clear the `device_instance.driver_id` foreign key before deleting the driver → an error
- `run_command` has no `started_at` column, only `finished_at`
- the full-width colon in `$VAR：` in the script (the Nth time)

### Features (the device model library + the chain of the error codes: the centrifuge / the sealer are adapted to the real machine)

**The origin**: there are only two real manuals, for the S100 and the ASpin 2K, but the onboarding of the devices is "one at a time".
When it is modelled by type, connecting the second centrifuge means changing the code; when it is modelled by model, **adding one = adding one definition**.

**The layering** (only now it is clear):

```
DeviceProfile (the type)      the centrifuge "roughly which methods it has, rpm 200–14000" —— it only governs the parameter boundaries
DeviceModel (the model)        the real command names, parameters, error codes and action timeouts of the ASpin 2K / S100
DeviceBehavior (the behaviour) the timing constraints: the tray origin, the two doors, the modules are created first
recovery_policy (the database) what to do when something goes wrong (it can be changed on site)
```

**Why the "model" was moved out of the sim package** (the new package `com.labos.kernel.device`):
the same definition now has **three consumers** —— the simulator (it answers according to the manual), the adaptation layer
(`DeviceErrorMapper` translates `0x8000000C` into `EC-08-300C`), and the SQL generator
(`DeviceErrorSql` produces the `error_code` + `recovery_policy`).
Leaving it in the sim package would make "the control console depend on the simulator", and later the .NET sidecar could not reuse it.

**The places where the S100 was made more detailed following the manual**:

- the error codes are **14 and not 15** (`0x0D`/`0x0F` are not in the manual, the earlier document was wrong);
- one command code of `0x04` hangs off three usages (sealing / changing the plate type / querying the plate type), they are told apart by the index `0x00`/`0xA5`/`0x5A`;
- **it cannot seal when the tray is not at the origin** → `0x8000001A`. Now that this is implemented,
  a workflow that misses `MoveTray(0)` shows up in the simulation phase, there is no need to wait for the site;
- **the faults are latched**: after "out of paper" has been reported, changing the paper without clearing the fault means that sealing reports "out of paper" again.

**The three errors of the ASpin 2K that were corrected following the manual**:

- a `ControlCoverClose` command had been made up —— **it is not in the manual**, it is `ControlCover` + `nControType=0`;
- `nControType` is not the two values "open=1 / closed=0", it is **0=closed / 1=open the A door / 2=open the B door**, this machine has two doors;
- the parameters of `Start` are **the time / the speed / the acceleration**, there is no nominal value in rpm; the example of the manual waits for
  `120000 + the seconds of centrifugation*1000` —— the timeout of 30 seconds of the control console would **judge a timeout every single time**.

**How the error codes are put to use** (this is what the round really solved):

```
The device 0x8000000C
  → DeviceErrorMapper (the lookup in the model library) → EC-08-300C / MANUAL / "replace the consumables"
  → DriverResult.errorCode = EC-08-300C (the original frame still goes into device_raw_response)
  → RecoveryDecider (recovery_policy, scope=node) → MANUAL
```

- the numbering rule is **`EC-08-30` + the low byte of the manual code**: `0x8000001A` ↔ `EC-08-301A`, the operations can work it out backwards;
- a code that is not registered → `EC-08-9001` + hand over to an operator, **it is not silent and it does not guess**;
- the severity is not typed in by hand, it is derived from the disposition (RETRY→warn / MANUAL→error / ABORT→fatal),
  otherwise the ones that "can be retried automatically" would be marked as fatal and would wake somebody up in the middle of the night;
- **the persistent connection mode has to translate as well**: `TcpPersistentDriver` used to squash all the failures into `EC-05-0001`,
  and the device code was completely lost on the persistent connection path.

**The traps that were stepped on**:

1. **`case X -> expr;` in a `switch` statement does not return a value**.
   In `S100Behavior` there was `case "GetPlateType" -> Reply.ok(...)`,
   it compiled, it did not raise an error at runtime, and **the return value was silently dropped**, so it fell through to the `default` and returned the generic frame.
   The self-test caught it (the "query the plate type returns 3" case failed), otherwise it would have been believed that "the simulator supports querying the plate type".
   It has been changed to `case X -> { return ...; }`. **The arrow syntax only yields in a switch expression.**
2. **When there is a model, the method set must not be validated against the profile any more**: the `MoveTray` of the S100 and the
   `CreateModules` of the ASpin are not in the generic profile, and validating against the profile **wrongly rejects the legitimate commands**.
   The profile is the boundary of the parameters, the model is the authority of the method set.
3. **The real driver serialises the parameters as strings** (`"value":"10000"`), while the simulator only accepted numbers,
   and it showed up as soon as the e2e self-test ran. The parameter parsing now accepts both.
4. **`Map.ofEntries` does not keep the order**, and the list of the models has to be printed in the order of the registration, so a `LinkedHashMap` is used.

**The self-tests** (none of them touch the database, they go into `scripts/test-all.sh`):

| The self-test | The number of assertions | What it covers |
|---|---|---|
| `device.DeviceModelSelfTest` | 192 | The typos of the model library, the 14 codes one by one, the mapping, the decisions, whether the SQL is in sync |
| `sim.SimulatorBehaviorSelfTest` | 41 | It starts a real socket and runs the S100 / ASpin with the timing of the manual |
| `driver.DeviceErrorChainSelfTest` | 14 | The whole chain device code → the adapter → the control console code → the recovery action |

`./scripts/test-all.sh --pure-only` → **passed 340 / failed 0** (it was 93 before).

**The cost of adding one device** (it is written into `docs/设备/型号库与错误码映射.md`):
add one entry to `DeviceModels` → add a `DeviceBehavior` if there are timing constraints →
paste the output of `DeviceErrorSql --emit-sql` into `95_seed_data.sql` → run the three self-tests → update the md. About half a day.
**The precondition is the manual**; without a manual it can only go into the `PLANNED` list (the resource can be created and the skeleton of a workflow can run, but it cannot talk to a real machine).

### Features (connecting to the real database + the on-site gap list + the .NET sidecar: the device chain is closed)

**This round finished the three things of the previous round that were "only a definition, nothing landed".**

#### 1. The seed went into the real database (`labdb` @ 192.0.2.10)

The previous round wrote the INSERT of `error_code`/`recovery_policy` into `95_seed_data.sql`, but it **was never executed**.
This round the current state online was checked first, and three things turned up that had to be dealt with first:

- **`EC-08-3010` already exists** (it was created automatically from the raw error frames, and the `title` is the whole JSON).
  My initial `EC-08-30xx` numbering would have collided with this range —— and the more fundamental problem is:
  **two different models may both report `0x8000000C` with a completely different meaning**, and numbering only by the byte makes the two models fight over the same control console code.
- So it was changed to **`EC-08-<the model block><the manual byte>`**: `40`=S100, `41`=ASpin 2K, the new models from `42` onwards.
  `0x8000000C` → `EC-08-400C`. The block is allocated in `DeviceModel.errorBlock`,
  and the control console code is computed automatically by `DeviceModel.error()` —— **typing it in by hand is bound to get it wrong**.
- The result of the execution: `error_code` 60 → **77**, `recovery_policy` 62 → **79**, all of them with `scope='node'`.
  Before the execution the two tables were dumped into `/tmp/labos_backup/`, and the SQL itself is idempotent (`ON CONFLICT DO NOTHING`).

**The verification on the real database** (`DeviceErrorChainSelfTest` got a 2nd section, it only runs when `LABOS_DB_*` is set):
`EC-08-400C → MANUAL`, `EC-08-4001 → RETRY`, `EC-08-4008 → ABORT` are all looked up in the **real database policies**,
and not generated on the fly from the model library. This is the only evidence that "the seed really went in and the scope really is right".

#### 2. There is no S100 in the database, and no ASpin 2K either —— this is an important finding

```sql
select count(*) filter (where attr ? 'device_model'), count(*) from device;  -- 0 | 74
```

Of the 74 devices, the ones that are really running are:

| The devices of the production line | The model in the database | Do we have the manual? |
|---|---|---|
| The centrifuge | `360R` (Hettich Rotanta 460R) | ❌ |
| The plate sealer | `PlateLoc` (Agilent) ×2 | ❌ (semi-automatic heat sealing, a different source from the S100) |

**So not a single `device_model` was bound this round, and that is deliberate**: binding the 360R as `aspin-2k`
would use the semantics of the ASpin to interpret the error codes of a Hettich —— which is worse than not binding it (it degrades into "a code that is not registered → hand over to an operator").

Two measures:
- `360R` / `PlateLoc` are added to `PLANNED` (visible with `--list-models`), which says "this one is in the database but there is no manual for it";
- the document states the binding SQL and **what happens when it is bound wrongly**, so that it is executed when the real machine arrives or when the manual turns up.

#### 3. The items the manual does not cover → a separate list, no guessing

The new `docs/设备/现场确认清单.md`, 13 items, and each one states **how it is tested / the pass criterion / what happens if it was guessed wrong**:

- S100: how long one sealing takes, the table of the parameter indexes, whether a power cycle needs a reset, where the tray stops when the power goes off, the valid range of the tray, the bytes 6–8 of the error frame
- ASpin: the unit of the acceleration, whether the lid can be opened while it is centrifuging (**a safety interlock, and if it is allowed the control console has to forbid it itself**),
  the unit of `CurrSpeed/CurrTime`, the state when the power goes off, the node number of several of them, what the XML looks like when `SendData` fails
- the three that the control console decides itself (the timeout = 120s + the seconds of centrifugation, the actions are not retried, the allocation of the error code blocks)

**One criterion**: the manual states it → it goes into the code; the vendor confirms it verbally → it goes in, but it is written into the `note`;
it was guessed by ourselves → it only goes into a parameter like `delayMs` where "a wrong guess is not fatal", and it has to be marked as "guessed".

#### 4. The .NET sidecar (`sidecar-dotnet/`)

At the level of the real machine there was only the simulator before, **on site there was nothing that could speak the language of the devices**. This round it was added:

```
labos-kernel ──TCP(JSON+SEP)──> sidecar ──┬── the serial port, 12 byte frames      (S100)
                                          ├── Cluster.dll + XML   (ASpin 2K)
                                          └── forwarded to the Java simulator   (the workstation)
```

A few key decisions:

- **the model definitions are not hardcoded**: `DeviceModelExport` (Java) exports the model library as `models.json`,
  and the sidecar reads it when it starts. Copying them twice is bound to drift, and the symptom is "the simulator runs, but on site the commands cannot be sent".
  `test-sidecar.sh` compares the export with the file in the repository and fails outright when they differ.
- **the error codes are not translated**: if the device says `0x8000000C`, then `0x8000000C` comes back.
  "Should it be handed over to a person or retried" is a business policy and it has to be defined in exactly one place (`recovery_policy`).
- **the sim mode is not there out of laziness**: in the chain control console→sidecar→device, the middle hop is the one that breaks the most easily
  (how the frames are assembled, how the parameters are converted, how the error codes come back). The sim mode verifies it separately from "whether the device itself is right",
  and on site only the transport is swapped, not a single line of the logic.

#### The traps that were stepped on

1. **the read timeout of the serial port must not be hardcoded**: `ReadTimeout=3000` while the sealing takes 12 seconds → every sealing times out →
   the control console judges it as a failure → retries → **the plate is sealed twice**. It was changed so that every command dynamically widens the timeout according to the `delayMs`/`actionTimeoutMs` of the model.
2. **`Integer.parseInt("80000001", 16)` overflows**: `0x8000000C` is beyond the positive range of an int,
   so the low byte has to be fetched with `Long.parseLong(...) & 0xFF`.
3. **`byteOf` has to blow up at the registration and not at the runtime**: when the format of the device code is wrong,
   `DeviceModel.error()` throws the exception directly —— finding it during the self-test is better than finding out on site that "this code was not translated".
4. **the order of the parameter validation**: `SealWithType(9)` used to check the tray first → it reported "the tray is not at the origin".
   It was changed to validate the parameters first: a wrong parameter is "you sent it wrong", it has nothing to do with where the tray is; otherwise the site goes and looks at the mechanics.
5. **the position of the parameters of the `DeviceModel` constructor**: `errorBlock` comes after `note`,
   and the first time it was inserted before `note`, so the compilation reported "int cannot be converted to String".

#### The verification

| The check | The result |
|---|---|
| `./scripts/test-all.sh --pure-only` | **345 / 0** (it was 340: the new planned assertions + 1 item of the sidecar chain) |
| `DeviceErrorChainSelfTest` + the real database | **19 / 0** (including the 5 assertions on the real database policies) |
| `./scripts/test-sidecar.sh` | **7 / 0** (the 10 chain assertions inside all pass) |
| The execution of the seed | 60→77 codes, 62→79 policies, a repeated run changes nothing |

**There is still no real machine**: the serial frames and the `Cluster.dll` only have the manual as their basis, and they have never been run against a real device.
The 5th section of `sidecar-dotnet/README.md` lists "what has not been verified" item by item.

### The data governance (52 existing error codes are deleted) + the features (the registration in the device pool: one command with an image)

#### 1. What those 60 "existing codes" in the database really are

Looking at the `attr` revealed the origin:

```json
{"origin":"error_log","devices":28,"occurrences":12220,"top_shooting":"Ignore"}
```

They are **the error log that was loaded in**, and the `code` was dug out of the **raw error frames**
(the `title` field stores the whole JSON directly: `{"checksum":"...","equipment_id":"ID1131"...}`),
and `top_shooting` records how they were disposed of —— a lot of them are `Ignore` (which corresponds to the
sentence "91.2% of them were ignored directly" in the comment of `JdbcErrorOccurrenceStore`).

The problem is not that it is ugly, it is that it **cannot be used**:

- the `title` is not human language, and the site cannot understand it when the alert is pushed out;
- the `code` (`EC-00-xxxx` / `EC-23-xxxx` / `Transfer-001` / `Script Error`) runs in parallel with the `EC-08-40xx` of the model library,
  and the lookup of the recovery policy hits the existing rows;
- of the 52 codes, **0 of them have an `error_occurrence` record** (that history was not loaded), so deleting them does not lose the history of the control console.

**The handling**: the 52 dirty codes are deleted (`title LIKE '{%'`), the `recovery_policy` takes 52 of them with it through the CASCADE of the foreign key;
`error_code` 77 → 25, `recovery_policy` 79 → 27.
The rows as they were (including the id, the severity and the `attr` statistics) are kept locally and are not shipped with the repository,
and the kept copy contains both the DELETE (which can be run again) and the INSERT for the recovery (commented out by default) —— when they are needed in the future, they are in that copy.

The 25 that are kept = the 8 existing codes with a proper Chinese title (`设备未就绪`/`设备通讯超时`/`驱动未加载`…)
+ the 17 new codes (14 for the S100 + 3 for the adaptation layer).

**The policies that were not changed**: no mapping is added for these 52. The error codes of the machines without a manual (`Rotanta 460R`/`PlateLoc`)
cannot be translated in the first place, and hardcoding a table only disguises "I do not know" as "I know".

#### 2. The registration in the device pool: `DeviceRegistrar`

Before, adding a device meant writing an `INSERT INTO device` by hand, and it was easy to leave out `capability_id` / `icon` / `attr`.
The new `com.labos.kernel.device.DeviceRegistrar`:

```bash
# Already in the model library: the name / the vendor / the type / the capability / the model binding are all automatic, the image is handled as well
java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.device.DeviceRegistrar \
     --model s100 --image docs/设备/images/s100.jpg
# An external user: give it a JSON description file
java ... DeviceRegistrar --from my-device.json
# Look at the current state
java ... DeviceRegistrar --list
```

- **The image**: it is copied into `webapp/public/devices/<code>.<ext>`, `device.icon=/devices/...`;
  without an image a placeholder SVG is generated (which says "a schematic") —— it is not left empty, and it does not pretend to be a photo of the real thing.
- **Idempotent**: it upserts by `(tenant_id, code)`, a repeated run only updates.
- **The model binding**: `attr.device_model`, it is the key of the error code translation;
  an id that is not in the model library is rejected outright (it is better not to bind than to bind wrongly).
- **No deployment**: the `device_instance` is still created by the frontend with "island → deploy" —— a deployment has to pick the island and fill in the IP,
  and that is a decision of a person.

While at it, the icons of the pool were connected: `/api/device-models` returns the `icon`,
and the frontend `PoolIcon` shows it first, **and it falls back to the capability icon automatically when the loading fails**
(the `icon` of the existing devices points at an earlier server `/image/device/...`, which is a 404 in the new environment, so a plain img would be a broken image).

#### 3. The two that were added

| id | code | Name | Type | Capability | The model library |
|---|---|---|---|---|---|
| 220 | `S100` | S100 sealer | Sealing | SEAL | `s100` |
| 221 | `ASPIN_2K` | Shanghai Aiao Biotechnology ASpin 2K | Centrifugation | CENTRIFUGE | `aspin-2k` |

**Note**: being in the pool ≠ being able to run. These two have no `device_instance`, no IP and no driver endpoint yet,
and once the real machine is connected they still have to go through "deploy → fill in the IP → start the sidecar → bind the driver".

#### The traps that were stepped on

1. **`git check-ignore` says that bin is not ignored**: in the `.gitignore` there is `sidecar-dotnet/bin/`,
   and it only matches the **direct subdirectories**, while the actual artefacts are in `sidecar-dotnet/Labos.Sidecar/bin/`.
   It was changed to `sidecar-dotnet/**/bin/`, otherwise `git add .` sweeps in 200+ binaries.
2. **The name of the device pool got mixed up with the wording of the documents**: the vendor of the S100 in the model library is "（手册未标注厂商）",
   and splicing it straight into the `name` produced "（手册未标注厂商） S100". A check was added to the registrar,
   and without a vendor it uses "the model + the Chinese name of the type".
3. **When the API changes a field it has to be restarted**: `ApiServer` is a bare process, it does not get the new fields without a restart (the `icon` was stuck on this).

### The fix (the deployed device cannot be driven) + the acceptance (the device chain is verified up to the "island" level)

#### 1. The key step the user pointed out: a device has to be dragged into an island before it can be verified

Before, only "sidecar ↔ simulator" was verified, **it was not verified whether the control console can find this device through the configuration in the database**.
`scripts/verify-device-chain.sh` was added:

```
the model goes into the pool → it is deployed to an island → the capability rows are added → the driver is bound → the simulator + sidecar are started → a real command is sent through /api/shadow/probe → the policy is looked up again
```

The assertions on the real commands: the last one of the S100 `MoveTray(0)→Seal→MoveTray(10000)→Seal` has to be `EC-08-401A`;
the ASpin `CreateModules→Home→RunProgram→CurrSpeed` all succeed. After the run **26 / 0**.

#### 2. This step caught a real bug: `deployModel` does not write `resource_capability`

On the first run, all 10 probes returned **`EC-06-0001` (the driver is not loaded)**. The root cause is in the product code:

```java
// WorkflowService.registerAllDriversFromDb
// WorkflowService.registerAllDriversFromDb
if (allCaps.isEmpty()) {
    System.err.println("[驱动] " + m[0] + " 跳过：名下设备都没有声明能力");
    continue;      // ← the whole driver is skipped, all the devices under it fail together
}
```

And `QueryService.deployModel()` only creates the `resource_node` + `device_instance`,
**it has never written a `resource_capability`**. A `grep` over the whole repository confirms it: no code writes this table,
it was brought over during initialization.

⇒ **For a device that is dragged into an island from the frontend, the capability rows are empty and the whole driver cannot be registered**.
This bug cannot be found by the unit tests (they construct the `DriverDescriptor` directly), only the path "deploy → send a command" exposes it.

**The fix**: `deployModel()` writes the capability rows at the deployment as well
(`INSERT ... SELECT capability_id FROM device WHERE id = ?`, `ON CONFLICT DO NOTHING`).
**The data is topped up**: the instances that were missing it have been filled in.

#### 3. The traps that were stepped on

1. **The unique key of the `driver` table is `(tenant_id, code, version)`**, not `(tenant_id, code)`.
   I wrote `ON CONFLICT (tenant_id, code)` → it reported "there is no matching unique constraint", the whole INSERT failed,
   and `q()` swallowed the stderr, so the symptom was only "the driver binding failed". It was changed to the two steps "insert it if it is not there, update it if it is".
2. **The bash 3.2 of macOS swallows the Chinese character that follows `$var` into the name of the variable**:
   `echo "$v），复用"` → `v?: unbound variable`. It has to be written as `${v}`.
   The whole repository was scanned, `verify-device-chain.sh` (6 places) and `verify-m4.sh` (1 place) are fixed.
   **This is a trap that has been lurking in the scripts for a long time**, it simply never blew up before because the thing after `$var` happened to be ASCII.
3. **The 60s cache of the driver registry** (`REGISTRY_TTL_MS`): probing right after the driver is bound always gives `EC-06-0001`,
   so you either wait 62 seconds or restart the ApiServer. The script sleeps explicitly and prints the reason.

#### 3.1 Fixed along the way: the instance number was escaped into underscores by the Chinese name

At the deployment the code is taken from `device.name` ("Shanghai Aiao Biotechnology ASpin 2K"), and then
`replaceAll("[^A-Za-z0-9_]", "_")` ⇒ it becomes `_________ASpin_2K-1` (every character of the Chinese part becomes an underscore), which the operations cannot read.
It was changed to take the **code of the device pool** (`S100` / `ASPIN_2K`) ⇒ `S100-1` / `ASPIN_2K-1`.
The two deployed instances were renamed as well (resource_node.code + device_instance.code together).

#### 4. The state in the database now

| Item | The value |
|---|---|
| The device pool | `S100`(220) / `ASPIN_2K`(221) |
| The instances | Both are on the island `Device_Test`(100030): resourceId 504830 / 504831 |
| The drivers | `DRV-SIDECAR-S100` / `DRV-SIDECAR-ASPIN` (the endpoint is the random port of the sidecar) |
| The capabilities | The `resource_capability` has been filled in (SEAL / CENTRIFUGE) |
| The error codes | 25 (including the 14 `EC-08-40xx` of the S100) |

> Note: the driver endpoint points at **the temporary port of this run**. The next run of the script changes it to a new port;
> when the real machine goes live it has to be changed to the fixed port of the sidecar (8501), and the `ip` has to be filled in.

### The fix ("the workflow ran green but the device did not move" —— the three real bugs that were caught on the page)

The user asked "can I see the execution of the workflow on the page". It was actually clicked through in the browser, **and it was seen, but what was seen was fake**.

#### 1. 🔴 The most serious one: a node with a device bound to it is treated as a "logic node", so the device call is skipped entirely

Click execute in the workflow library → the run details show **1/1 nodes completed, 0 failed**, all green.
But looking at the simulator log: **not a single command was received**; `run_command.result = {"ok": true, "durationMs": 0}`.

The root cause is in `RealWorkflowRunner`:

```java
/** The upper bound of a legitimate device instance id. The real ids only have three digits (162~405), everything bigger is a fake value. */
public static final long MAX_PLAUSIBLE_RESOURCE_ID = 10_000L;
...
if (equipmentInstanceId != null && (... || equipmentInstanceId > MAX_PLAUSIBLE_RESOURCE_ID))
    return new CapabilitySource(LOGIC, "logic");   // ← it is judged to be a logic node
```

And `GraphRunner` passes the `bound_resource_id` of the node (**the resource id**) as this parameter ——
and the `resource_node.id` of the devices that are deployed now is **5xxxxx** (e.g. 504830), **all of them > 10000**.
⇒ Every workflow in which "a device is dragged onto a node" is judged to be a logic node ⇒
the first line of `DriverStepExecutor` is

```java
if (deviceId == 0L || "LOGIC".equalsIgnoreCase(ctx.capabilityCode())) {
    // it does not call the device, it simply marks every step as successful
    return;
}
```

**That is where the false green comes from.** This bug cannot be found by the unit tests (they do not go through the path "deploy → the resource id grows").
The fix: the upper bound becomes 99_999_999 (it only stops the fake 9 digit ids of the data in the database); and the real reason is written into the comment.

#### 2. `bound_resource_id` was not used at all to pin the device

Even with the capability resolution correct, the node only "picks the device by rotating through the capabilities" —— with two devices of the same capability it ends up on another machine (the machine 2 that was picked in the orchestration actually runs as machine 1).
The fix: `GraphRunner.withBoundInstances(resource_node.id → device_instance.id)`,
a node with a bound resource is pinned to that instance directly and does not take part in the rotation.

#### 3. The list of the workflow library is sorted by "the number of tasks", so a newly created workflow is not visible at all

`ORDER BY task_count DESC` ⇒ the single step workflow that was just created (task_count=1) falls outside the limit of 60,
it cannot be found in the interface, and the symptom is "the workflow I just created is gone". It was changed to `ORDER BY updated_at DESC`.

#### 4. The sim forwarding of the sidecar does not reconnect

After the simulator is restarted, the TCP connection that the sidecar cached is half open (`TcpClient.Connected` is still true),
and every following command is written into a black hole, **until the sidecar is restarted**. The fix: the connection is dropped when the response times out;
a failure is retried once; the error code is changed from the ASpin specific `EC-05-2001` to `EC-05-1006` (the communication failed).

#### The verification (the real machine = the simulator, clicked through on the page)

| Scenario | The result |
|---|---|
| Normal | The run details show **node 1/1, 0 failed**, the device column shows `S100-1`, the simulator log has `收到 Seal id=…-d413` |
| Out of paper injected | The node is `manual`, the reason is **`策略[EC-08-400C@NODE] 指定直接转人工`**, the run becomes `paused` and it goes into the "paused queue" |
| Running right after the device is restarted | It may fail once (`EC-05-1006`), it heals itself within 60 seconds (after the cache of the driver registry has expired and it is rebuilt) |

#### The ones that are not fixed yet (noted down)

- The "node 0/1, 0 failed" of the run details: a manual node is neither counted as completed nor as failed, so the count reads as "it did not run".
- The paused queue does not show **why it stopped** (which error code), you have to go into the run details to see `EC-08-400C`.
- The offline alert of the heartbeat has a concurrency bug: `duplicate key ... alert_state_pkey（DEVICE-OFFLINE|200171）`,
  which means the upsert was written as an insert and it reports an ERROR on every heartbeat.

### Features (resuming from a breakpoint: clicking "continue" really carries on) + the fix (the upsert of the heartbeat alert / the pause reason)

The previous round made "the execution of the workflow is visible on the page" work, and what this round adds is **the last step of the closed loop**.

#### 1. 🔴 Clicking "continue" actually does nothing

`PauseManager.resume` only puts the run back to `queued` (the comment in the source says: the execution body returned long ago,
continuing from the middle "is a matter for the next round"). But **nothing picks up the queued run** ——
after the paper is changed on site and "continue" is clicked, the interface shows it queued, and then it queues forever.

The fix:
- `GraphRunner.withSkipKeys(Set)`: the nodes that were already completed in the previous round **are skipped and not executed**
  (without the skip a re-run would carry the plate over a second time).
- `WorkflowService.resumeAsync(runId)`: it reads the completed nodes → it deletes the run_node of the unfinished nodes
  (the run_command goes with them through the cascade of the foreign key) → it puts the run to running → it continues in the background with **the same runId**.
- The resume interface of `ApiServer` hooks into this continuation after `action=continue` has succeeded.

The verification (all of it clicked through on the page): the fault is injected → it is paused → the paper is changed (the simulator is restarted) → "continue" is clicked
→ **the run is completed, the node is completed, and the simulator received the Seal once more**.

#### 2. The paused queue does not show "why it stopped"

The row only had "stopped at the node that needs a manual confirmation" + the node it stopped at `—` (`run.pause_node_key` is null).
The backend was changed to take **the first unfinished business node** (the name + the status + `input.reason`),
and the frontend pulls the error code out of it and marks it in red separately.

Now a row shows: `EC-08-400C | 策略[EC-08-400C@NODE] 指定直接转人工 | 停在节点 S100 封膜机 #1 / Seal [manual]`.

#### 3. The "node 0/1, 0 failed" of the run details reads as if it did not run

A manual is neither counted as completed nor as failed. A "waiting for a person" column was added (it is not shown when it is 0).

#### 4. The offline alert of the heartbeat reports an ERROR every time (duplicate key)

`INSERT ... WHERE NOT EXISTS(status='open')` looks right, but it hits the primary key:
the unique key of `alert_state` is `(tenant_id, fingerprint)`, one row per device;
after the last round closed it the row is still there (closed), so the next time it goes offline the NOT EXISTS does not hold ⇒ it goes to the INSERT ⇒ duplicate key.
It was changed to `ON CONFLICT (tenant_id, fingerprint) DO UPDATE SET status='open', closed_at=NULL`.
After the fix the `duplicate key` in the log is zero and the offline alert can be "reopened" normally.

#### The traps that were stepped on

- **`str.replace` silently does not match**: while changing the frontend TSX neither of the two anchors matched (a difference in the line ending / the indentation),
  the script did not report an error, the file did not change, and a full green tsc was even run for nothing —— it was believed that it was fixed.
  It was changed later to "match by line + print how many places were changed", and only because tsc really reported an error was the extra `{` found.
- **`psql -tAc ... | xargs -I{}`**: the `{}` is taken by psql as the SQL braces, the syntax is wrong.
  It is queried with one SQL statement that is put together properly.

### Features (P1/P2: the error codes land in the database, the closed loop of the alert is verified, the experiment chain)

#### P1-1 somebody finally writes `run_node.error_code`

The column has been there all along (varchar(64)), but **nobody ever wrote it**: the error code was only stuffed into the string of `input.reason`,
and the frontend had to dig it out of "策略[EC-08-400C@NODE] 指定直接转人工" with a regular expression, and the SQL could not count it either.

- `WorkflowService.updateNodeResults`: `error_code = ?` (it is truncated at 64 characters)
- `ExperimentFlowRunner.finishNode`: it used to write `duration_ms` —— **run_node does not have that column at all**,
  so every time it threw a SQLException and degraded to "only the status is updated", and the error code and the duration were both lost.
  It was changed to write the real `error_code`, and the duration goes into `input.durationMs`
- `QueryService` returns the `errorCode`; the run details get an "error code" column

The verification on the page: `S100 封膜机 #1 / Seal | manual | S100-1 | EC-08-400C | 策略[...]`.

#### P1-2 the device comes back online → the alert is closed automatically (it was really run once)

The sidecar of the S100 is disconnected: after 42 seconds `device_state.online=f`, `alert_state` gets an open one.
The sidecar is started again: after 25 seconds `online=t`, the same alert is `closed` (with a closed_at).
—— Before only the "open" had been fixed, the path of the "close" had never been verified.

#### P2-1 the experiment chain (not a direct run of a workflow)

An experiment is created (815) → a workflow is attached (1254) → "Authoring → run the experiment" on the page:
`#10 completed 1/1`, the simulator received the Seal, and there is a record in the execution history.

Fixed along the way: **the status of the experiment itself was never updated**. `ExperimentExecutor` only writes `experiment_run`,
and `experiment.status` stays at `created` forever —— so the list cannot tell "it has run" from "it has not run",
and after clicking "run the experiment" the list still shows "pending execution".
Now `markRunning`/`finish` also update the status / run_id / started_at / finished_at of the `experiment`.
Measured after the run: `815 | finished | run_id=11 | started ✓ | finished ✓`.

#### P2-2 the device pool of the island view

`Devices & Islands → Island View`: both the S100 and the ASpin 2K show the schematic
(`img naturalWidth=320`, which shows that the SVG really loaded and it is not a broken image).

#### The traps that were stepped on

- **`grep -o 'e[0-9]*'` matches the e inside the word "menuitem"**:
  the extracted refs all became `e`, and clicking them did nothing, so it was thought to be a problem with the page. It was changed to `e[0-9]\+`.
- **Restarting the ApiServer invalidates the login in the browser** (the token is in memory),
  so every time the backend is changed you have to log in again, otherwise the page is all empty.

### The fix (the retry back-off never took effect) + the features (the sub-steps get the error code, the attempts leave a trace)

#### 1. 🔴 "retry 3 times" actually retries three times within 50ms

What is passed to the state machine in `WorkflowService` is:

```java
new RunStateMachine(decider, …, Sleeper.recording(new ArrayList<>()), …)
```

`Sleeper.recording` only **records the back-off time in an ArrayList**, **it does not really sleep** —— that is a substitute for the unit tests,
and it was left on the production path. So the `retry 3 times / exponential 500ms` of the policy
behaves in reality like this: it fails, it retries immediately, it fails again immediately, and **the three chances are used up within 50ms**.
A transient fault like the device being "busy for two seconds" is inevitably judged as a failure and handed over to a person. It does not help how accurately the back-off is calculated.

The change: the production uses `Sleeper.REAL`; only when the CI / the batch verification finds it too slow and `LABOS_FAST_RECOVERY=1` is set does it record instead.
`RealWorkflowRunner` (the batch acceptance `--all`) stays in the recording mode, but a comment was added saying **do not copy it**.

Measured (`EC-08-4001` the semaphore overflow → retry ×3):

| The attempt | The moment | The interval |
|---|---|---|
| S1 | 20:50:33.883 | — |
| S2 | 20:50:34.552 | +669ms (≈500) |
| S3 | 20:50:35.694 | +1142ms (≈1000) |
| S4 | 20:50:37.885 | +2191ms (≈2000) |

#### 2. The sub-steps (level=1) also get the error_code

When `writeStep` inserts the `run_node(level=1)` it did not write the `error_code`, only the `run_command` had it.
The business node is marked as `manual` (and not as failed), so "which step failed and because of which code"
could only be looked up in the `run_command` table.

Now the step nodes carry the `error_code`, and the run details page shows it directly.

#### 3. The multiple attempts left a trace all along, but nobody had ever verified it

Measured, a `retry ×3` produces 4 step nodes: `S1(尝试0) / S2(尝试1) / S3(尝试2) / S4(尝试3)`,
each row independently carries the `durationMs` and the `error_code`; the parent node has `attempt=3` and the reason is
`策略[EC-08-4001@NODE]已耗尽：attempt=3 >= maxAttempts=3，原意图=RETRY`.
—— This chain worked all along, it was simply never run; now there are measured data.

#### The traps that were stepped on

- I slipped on the `--fail-code` of the simulator and wrote `0x8000001` (one digit short), it is not found in the model library →
  it becomes `EC-08-9001`, a code that is not registered → it goes straight to a person, **and it does not enter the retry**. It took a while to find out that the parameter was wrong.
  This also shows that the design of "a code that is not registered is not guessed and goes straight to a person" is right: a typo does not silently try at random.

#### 4. The continuation with several nodes: another one was caught (the node shells are inserted again)

The continuation of a single node was verified, and as soon as there are several nodes it shows:
```
Click "continue" → the run goes straight to failed, not a single command was sent
run.input.error = 续跑失败：duplicate key ... uk_run_node
                  Key (run_id, node_key, attempt)=(205351, N1, 0) already exists
```

`createNodeShells` creates a running shell for **all** the nodes at the beginning of every `execute()`.
When it continues, N1 already has a row (completed), and inserting it again hits `uk_run_node`.

The fix: before creating the shells the nodes that this run already has are looked up, **and if there is one its id is reused instead of inserting it again**.
At the same time this makes `execute()` re-entrant for the same run (the replay of StartupRecovery is safe as well).

The verification (a 3 step workflow, out of paper injected in the 2nd step):

| The stage | The result |
|---|---|
| The first run | N1 completed, N2 **manual** (`EC-08-400C`), N3 running (it was not its turn yet) |
| Change the paper → click continue | The run is **completed**, N1/N2/N3 are all completed |
| The simulator received the Seal during the continuation | **2 times** (N2, N3) —— N1 was not executed again |

> It was verified by hand, it is not in `scripts/verify-device-chain.sh` yet (that script only runs a single node for now).
> The next time it is touched this has to be added, otherwise the next time this logic breaks nobody is bound to notice it.

#### 5. The loops and the several workflows of an experiment: they were already implemented, this round adds the verification

Before there was the worry that `ExperimentExecutor` only runs the first process. Measured (experiment 815):

| Scenario | The result |
|---|---|
| `loop_count=2` | `totalSteps=2, doneSteps=2`, the simulator received the Seal 2 times, `expanded_steps=2` |
| Adding one more process (seq=2) | `totalSteps=2`, both `experiment_run_step` are completed |

`expand()` is "take all the processes by seq → copy each one according to loop_count → expand it into the linear steps",
the limit is 20 times / 500 steps. **This does not have to be changed, it was simply never verified before.**

### Features (the crash recovery works) + the fix (the scope of a transport fault was wrong, the command level retry was a dead configuration)

#### 1. "The continuation with several nodes goes into the acceptance script"

`scripts/verify-device-chain.sh` got a 6th step: a 3 step workflow, out of paper injected in the 2nd step → it asserts
N1 completed / N2 manual / N3 not executed / the whole order paused → change the paper → click continue →
it asserts that the whole order is completed, that all three nodes are completed, and that **the simulator only received 2 commands** (N1 was not executed again).
The script goes from 26 to **34 assertions, all of them pass**.

#### 2. 🔴 The crash recovery: the interface lists it, but clicking "continue" is refused

After a crash `StartupRecovery.settle` marks the orders that are `running` as `failed` and sets
`input.recoverable=true`, and `/api/runs/recoverable` lists them as well (measured: it listed 42 historical records).
But `PauseManager.resume` only accepts `status='paused'`:

```
POST /api/runs/205352/resume?action=continue
→ {"ok":false,"error":"该执行不在暂停状态"}
```

**These "recoverable" records can only be looked at, not used.** The fix: `resume` gets
`isCrashRecoverable()` (`failed` + `recoverable=true`) and lets them through.

Measured (the rows of the N2/N3 nodes are deleted to create a "crashed after N1"):

| The step | The result |
|---|---|
| Restart the ApiServer | the run → `failed`, `input={interrupted:true, completedNodes:1, recoverable:true}` |
| Click continue | `ok:true`, N1 is skipped, N2/N3 are executed |
| The end | the run is `completed`, all three nodes are completed, the simulator received **2** Seals |

#### 3. 🔴 The recovery scope of a transport fault is wrong: the command level retry strategy is a dead configuration

`DriverStepExecutor` throws `RecoveryScope.NODE` for **all** the failures, while in the database
`EC-05-1006` (the communication failed) / `EC-05-1007` (the timeout) are configured with a **command** level retry:

| The code | The policy in the database | What actually took effect before |
|---|---|---|
| EC-05-1006 | command / retry ×5 / 500ms exponential | The wildcard node/manual (**it goes to a person after one attempt**) |
| EC-05-1007 | command / retry ×3 / 800ms | The same |

A single network hiccup, a device that is restarted for two seconds, and it goes straight into the queue of the people.

The fix: `EC-05-*` (the communication / the timeout) and `EC-99-*` (it cannot be sent) are thrown as **COMMAND**,
the rest (the device really refuses) stays NODE.

Measured (the driver endpoint is pointed at a port on which nobody is listening):

```
N1 manual attempt=5 EC-05-1006
reason: 策略[EC-05-1006@COMMAND]已耗尽：attempt=5 >= maxAttempts=5，原意图=RETRY
```

5 attempts and an exponential back-off of about 7.5 seconds before it goes to a person —— that is what "retry 5 times" is supposed to look like.

#### The problems of the same kind that are not fixed yet (noted down)

For `EC-06-0001` (the driver is not loaded) the database has a **run** level abort configured, while the code throws a **DEVICE** level one,
so it does not match either and it falls through to the wildcard node/manual. The result (hand over to a person) is not wrong, but the configuration is dead.
Whether the scope should be decided uniformly by the error code needs a design, it must not be changed on the fly.

#### 4. The acceptance script restores the driver endpoint at the end

The script runs with a random port (to avoid colliding with the simulator that was left running by hand), and after the run the sidecar is stopped as well.
Before, the endpoint stayed on the random port —— and from then on the two devices in the interface were "unreachable",
so **the next person who troubleshoots it will suspect the code first**. Now the endpoint is restored to 18501/18502 during the cleanup,
and a line is printed that says "the sidecar has been stopped, start it before you use it".

### The refactoring (the recovery scope is brought to one place: from "written off the cuff at the throw site" to an attribute of the error code)

**The problem**: `RecoveryScope` used to be hardcoded at the throw site —— all the device failures threw `NODE`.
While in the database the different levels are configured according to the nature of the error:

| The code | The policy in the database | What it actually was before |
|---|---|---|
| `EC-05-1006` the communication failed | **command** / retry×5 | The wildcard node/manual (it goes to a person after one attempt) |
| `EC-05-1007` the response timed out | **command** / retry×3 | The same |
| `EC-06-0001` the driver is not loaded | **run** / abort | `无匹配策略（层级=DEVICE）` → hand over to a person |

The previous round patched "the communication ones go to COMMAND" temporarily by prefix, but that was a **second set of rules**, and sooner or later it forks from the configuration in the database again.

**The approach**:

1. `error_code` gets a `scope` column (`20_kernel.sql` / `90_all_in_one.sql` are synchronised);
2. the backfill **does not write a separate table of intentions**, it "prefers the scope of the recovery policy of that code" ——
   the level that was chosen when the policy was written is the real intention;
3. `DriverStepExecutor.scopeOf(code)` becomes the **only exit**: it lazily loads
   `error_code.scope` → when it is not found it falls back to the prefix (`EC-05-10*`/`EC-99-*`→command, `EC-06-*`→device);
4. the five throw sites (the driver is not loaded / the sending failed / the device answered / the resource is occupied / the step threw) all use it.

**Measured**:

| Scenario | Before the fix | After the fix |
|---|---|---|
| The driver is not loaded (the driver_id of the ASPIN instance is set to empty) | `无匹配策略（层级=DEVICE）` → **paused / manual** | `策略[EC-06-0001@RUN] 终止执行` → **failed / aborted** |
| The communication fails (the endpoint is pointed at a dead port) | It goes to a person after one attempt | `EC-05-1006@COMMAND` retries 5 times, with a back-off of about 7.5s before it goes to a person |

**Along the way**: an idempotent backfill section was added to the seed SQL (`ALTER ... IF NOT EXISTS` + it only fills in the empty values),
it was executed twice to confirm that there is no side effect; `test-all` is 350/0 and the normal flow runs through (205364 completed).

**What is still not done**: there is no UI entry point for `scope` yet, so changing the level still means writing SQL.
Until the error code management page exists, this is a known gap.

### The wrap-up (the recoverable list is no longer noise + the heartbeat threshold is configurable)

#### 1. `/api/runs/recoverable` had 41 entries on it

Once the crash recovery worked, this interface became a "to do list". But measured it had **41** entries on it, and the vast majority of them were
left behind by restarting the ApiServer again and again while debugging today (every restart collects the orders that were running at that moment into the recoverable ones).
There was a pile of "recoverable" in the interface and a person could not tell which one is a real accident.

Two things:

- **Only the ones interrupted in the last 7 days are listed**. Clicking "continue" on an older order really does re-run it, while the state of the device, the plate position
  and the recipe have long since changed —— that is not a recovery, that is creating an accident.
- `POST /api/runs/{id}/dismiss` was added (when a person judges that "this one should not be continued" it is explicitly ignored).
  The 7 day window is only a fallback, and even inside the window there can be an order of a device that has already been taken apart.

Measured: a single dismiss works; after the historical test leftovers were cleaned up in bulk the list is back to 0; the normal flow runs as usual (205365 completed).

> The cleanup is a **data** operation (`input.recoverable=false`), it is not a deletion of the records ——
> the run itself and its nodes and its commands all stay, and they can be looked up at any time.

#### 2. The heartbeat threshold changed from hardcoded to configurable

`HeartbeatService` used to have `10_000L` (the probing interval) and a hardcoded `30_000L` (the offline threshold).
The problem is not only that "it cannot be configured": **when the interval is changed the threshold does not follow** —— with a probing interval of 60 seconds and an offline judgement of 30 seconds,
every device is judged offline once before every probe.

Now:
- `LABOS_HEARTBEAT_INTERVAL_MS` (10s by default)
- `LABOS_OFFLINE_ALERT_MS` (`max(3×the interval, 30s)` by default, it follows the interval)

An illegal value / a value that is not set falls back to the default, a wrong configuration must not be able to crash the service on start-up.

#### 3. The entry point of the crash recovery in the interface (it did not exist at all before)

The backend `/api/runs/recoverable` and `dismiss` are both done, but **the frontend does not reference them in a single place** ——
`grep recoverable webapp/src` is empty. In other words the crash recovery can only be seen in the log, and there is no way to click it in the interface.

A card was added at the top of "Run → Live Board": `Interrupted by the last crash (N)`,
and every entry shows `#runId / the order number / the completed x/y / the number of failures`, with the two buttons **continue from the breakpoint** and **ignore**.

Measured (a crash order with a single node was created): the page shows `Interrupted by the last crash (1)` → "continue from the breakpoint" is clicked
→ it goes to the run details `node 1/1, 0 failed`, the run in the database becomes `completed`, and the card disappears automatically.

### Features (the error code management page + the bulk ignore of the paused queue)

#### 1. The error code management page: changing one code no longer means writing three SQL statements

After the scope was brought to one place in `error_code.scope`, this table is the only truth about "what to do after an error".
But changing it means writing `error_code` + `recovery_policy` + `95_seed_data.sql` in three places,
and nobody on site dares to touch it —— configuring it is the same as not configuring it.

- **The backend**: `GET /api/error-codes?scope=&severity=&q=` (the code + the main policy are fetched in one go),
  `PATCH /api/error-codes/{id}` (the attributes of the code and the policy are changed **in the same transaction**, otherwise there can be
  a half-finished state with "the code was changed, the policy was not")
- **The frontend**: "Operations → Error Codes", the table has the severity / the scope / the policy / the back-off parameters,
  the dialog changes the scope, the strategy, the maximum number of attempts and the params; the scope drop-down has a line of explanation
  (command = resend the single one / node = redo the node / run = stop the whole order / device = the device dimension)

Measured: the maximum number of attempts of `EC-08-4001` is changed from 3 to 4 → in the database `max_attempts=4`, and then it is changed back to 3.

#### 2. The paused queue: 89 entries, and they can finally be cleared with one click

Before every row only had "continue / terminate". The vast majority of the 89 entries are historic leftovers and the leftovers of the integration,
and **nobody is going to click terminate on them one by one**, so the queue stays dirty forever and the orders that really have an incident are not visible instead.

- The backend `POST /api/runs/dismiss`: `{"runIds":[...]}` ignores the specified ones,
  `{}` = ignore all of the current ones; the state that lands is `aborted` + `error_code=EC-HUMAN-DISMISS`
  (it is distinguished from "terminate", so a report can tell "the person ignored it" from "the person terminated it")
- The frontend: an inline "ignore" in the row + a "bulk ignore (n)" and an "ignore all" in the toolbar, all of them with a second confirmation

Measured: the inline ignore of #205362 → the queue 89 → 88, the run becomes `aborted` / `EC-HUMAN-DISMISS`.

> The bulk ignore only changes the database, **it does not send any device command** —— an order that is stuck should not be driving a device anyway.

### Features (several policies for one code: one per scope)

#### Why "several policies" is not a "fallback chain"

First the runtime: `RecoveryPolicyResolver` sorts by `specificity` → `priority` and then does a **`findFirst`**.
So of the two policies with the same code and the same scope, only the one with the smaller priority is ever used, and the other one is a **dead configuration**.

The scenario that really needs several is **the same code being handled differently in different scopes**:

```
EC-05-1006 (the communication failed)
  the command scope → retry ×5 (resend the single one)
  the node scope    → manual ×1 (at the node level do not try hard, call a person)
```

At runtime the one that "matches the current scope" is chosen. So the model is **one code × one scope = one policy**.

#### The implementation

- `GET /api/error-codes` carries a `policies` array for every code (it no longer only gives the "main policy")
- `POST /api/error-codes/{id}/policies` is new (by scope, it updates it when it already exists)
- `PATCH .../policies/{pid}`, `DELETE .../policies/{pid}`
- The frontend: all the policy tags are listed by scope in the table, **the one of the current scope gets a blue frame**
  (at a glance you see which one is really in effect); in the dialog the scope / strategy / the number of attempts / the params are changed row by row,
  they can be added and removed; when it is added and that scope already exists, it says directly "just change that one"

Measured (EC-05-1006): a `node/manual ×1` is added → it is changed to ×2 → it is deleted, all of it clicked on the page,
and the database is synchronised to `command|retry|5`.

#### Fixed along the way: `jsonInt` does not accept a number in quotes (it returns 200 but the value does not change)

The value of the frontend `InputNumber` is often sent as a string (`{"maxAttempts":"2"}`),
and the regular expression of `jsonInt` only accepts a bare number → it parses out null → `COALESCE(?::smallint, ...)` keeps the existing value
→ **the interface returns 200 and the database does not change**. This trap is the hardest to find when it comes to "the save succeeded but it did not take effect".

It was changed to `"?(-?\d+)"?`, so it accepts it with and without the quotes. It affects every interface that uses `jsonInt`.

### The data cleanup (the paused queue is emptied)

Before the cleanup it is looked at what is in there:

| pause_kind | The number | The time range | The origin |
|---|---|---|---|
| waiting_human | — | (all of them from today) | `PD0001 Test-Incubator进板` (the M4 debugging)<br>`AUTO-7659 S100 封膜验证` (the integration of this round) |

The way it is handled: through the **product interface** `POST /api/runs/dismiss` (`{}` = all of them), and not by writing the SQL directly
—— and on the way it also verified the path of the "ignore all". It returns `{"dismissed":88}`, the paused queue is back to 0, and the interface shows
"there are no paused executions".

**All the records are kept**: only `status=aborted` + `error_code=EC-HUMAN-DISMISS` is changed,
not a single row of `run_node` / `run_command` is touched (a spot check of 205358 still shows 7 nodes and 6 commands).
The list is kept locally and is not shipped with the repository (a CSV).

If you change your mind: `where id in (...)` changes those 88 back to `status='paused'` (the CSV has all the ids).

### Features (four things on the page: the device can be found, the picture can be seen, the experiment can be created)

The user asked "how do I see the devices / the pictures on the island on the page" and "how is an experiment created", and after looking into it four real defects were found, and they were fixed one by one:

#### 1. The device pool gets a search (76 models cannot be looked for)

The pool is sorted by `deployed` (the number of deployed ones), so `S100` (only 1 was ever deployed) is sorted way back and you have to scroll all the way.
An `Input` search was added: the model name / the code / the capability all match, and typing `s100` leaves exactly one card.

#### 2. The device cards on the island canvas show the real picture

`/api/topology` did not return the `icon` before, so the cards on the island could only draw a colour block for the capability.
Now the SQL brings out `dev.icon` and the frontend extracted a `DevIcon` (`PoolIcon` reuses it),
**and it falls back to the colour block automatically when the loading fails** (the icon of the existing devices points at an earlier server, and it is a 404 in the new environment).
Measured: in `Device_Test · 8 units` both `S100-1` and `ASPIN_2K-1` now render an `img`.

#### 3. The device nodes of the experiment canvas no longer 404

It used to be hardcoded to `/devices/<capability name>.jpg` (e.g. `/devices/CENTRIFUGE.jpg`),
and **those files do not exist at all** —— every device node did a 404 and then left a blank space.
It was changed to use the `icon` that was registered in the device pool; when there is none, no picture is drawn (the capability name is displayed separately anyway).
The list "the devices on the island" in the sidebar is the same.

#### 4. Creating an experiment (before it could only be done by writing the SQL by hand)

`/api/experiments` only had a GET and there was no entry point in the interface —— the experiment 815 that was used for the testing before was created by writing the SQL by hand,
and inserting only one table meant that "the experiment was created but clicking execute does not run anything".

- The new `POST /api/experiments`: `{name, code?, islandId?, workflowIds?[]}`,
  **the `experiment` + `experiment_process` are written in one transaction** (seq 1..N),
  and when the number is not filled in it is generated automatically as `EXP-<the timestamp>-<4 random digits>`
- The frontend: a "new experiment" dialog on the orchestration page (the name / the number / which island / which workflows to attach first),

- The frontend: a "new experiment" dialog on the orchestration page (the name / the number / which island / which workflows to attach first),
  and after the creation it is **selected automatically**, there is no need to look for it in the list

Measured (all of it clicked through on the page): the experiment 817 "the page creation is verified" is created → the island Device_Test is chosen, the workflow 1255 is attached
→ it is selected automatically → "run the experiment" is clicked → `completed · 1/1 steps`,
the underlying run 205367 has all three nodes completed and the simulator received the Seal.

#### The traps that were stepped on

- `experiment.created_by` is a **bigint**, and I first wrote `'api'` → `invalid input syntax for type bigint`.
  It was changed to not write it (NULL). For every field of the id type do not use `jsonInt`, a `jsonLong` was added (it overflows).
- After the frontend was changed the `Modal` was not added to the import, and `tsc` reported a syntax error (`')' expected`),
  and at first it was thought that a JSX bracket was missing —— in fact the end of the file had been truncated by my string replacement (`);\n}`).

### The fix (the method is empty in the "details" of a device) + the experience (no hint when you click execute on an empty experiment)

#### 1. The method is empty in the details of a device: nobody ever writes `device_method`

The user clicks `ASPIN_2K-1 → details` and the list of the methods is empty. Looking into it: the "details" of the interface reads the
**`device_method` table** (`/api/devices/{id}/methods`), while when `DeviceRegistrar` puts a device into the pool it
**only writes the `device` row**, it does not write the methods —— the S100 / ASPIN_2K both have 0 entries.

The methods only existed in the **model library** (`DeviceModels`, 8 entries for aspin-2k / 12 for s100).
So there was a split: "the model library has the methods, the simulator can run them, but the interface does not show them".

The fix: when `DeviceRegistrar` puts a device into the pool it synchronises the methods of the model library into `device_method`
(`ON CONFLICT (device_id, method) DO UPDATE`, idempotent):

- the `name` uses the alias, and the commands that the manual does not document (e.g. `GetStatus`) are marked as "（not provided by the manual, it has to be confirmed on site）"
- the `param_schema.params[0].desc` holds the parameter description of the manual
- the `estimate_sec` / `timeout_sec` are derived from the `delayMs` / `actionTimeoutMs` of the model

**The methods that are no longer in the model library are not deleted**: the methods in the database may be referenced by the workflows, and deleting them would make those workflows report "the method does not exist".

Measured: `S100 12 entries / ASPIN_2K 8 entries`, and the "methods (8)" drop-down of the page has CloseDoor / CreateModules / Home / RunProgram …

#### 2. Clicking "run the experiment" on a newly created empty experiment gives no hint at all

The backend returns 400 `实验没有编排任何流程`, but the user only sees the button flash (it is only popped up in the catch of the frontend).
Worse, a new experiment has 0 workflows by default, so **the first click is bound to fail**.

The change: when there is no workflow, "run the experiment" is **disabled** + a tooltip explains it, and above the workflow table there is an explicit notice
→ "this experiment does not have any workflow orchestrated yet —— pick one with the drop-down above, click save orchestration, and then it can be executed".

### The fix (the "add workflow" drop-down shows `undefined node`)

What the user saw when creating an experiment: `S100 封膜验证（页面执行） (AUTO-7659, undefined node)`.

`/api/workflows` only returns `id/code/name/category`, **it does not return `nodeCount` at all**,
so when the frontend assembles the string it is `undefined`.

The fix: the list interface gets `nodeCount` (a subquery that counts the rows of `workflow_node` with `node_type='task'`).
Only the tasks are counted —— the start / end / gateway are not "a step of work", counting them would inflate the number.

The trap that was stepped on: the first version of the SQL copied the `AND deleted_at IS NULL` of `run_node`,
but `workflow_node` **does not have that column** → the query threw an exception → it was caught as "everything is 0",
and the symptom was "the field was added but it is still 0". It was only found because there is a `[WARN] 统计流程节点数失败` in the log.

Measured: AUTO-6773 = 1 node, AUTO-98266 = 3, AUTO-7659 = 1.

### Features (the execution of an experiment becomes visible step by step: which ones ran, which ones did not, which ones were skipped)

The user asked: "for an experiment that has finished or stopped half-way, how do I see the state of the whole flow on the page, which ones were executed, which ones were not executed, which ones were skipped?"
Before, only "progress 0/2 + a sentence about the error" was visible, and you could not see that **the 2nd step simply did not run**.

#### 1. The key trap: a step that was not executed has **no row** in the database

`experiment_run_step` is only INSERTed **when the step starts** (`ExperimentExecutor.startStep`).
So in the database "the 1st step failed → the 2nd step did not run" only has 1 row —— querying the table directly makes you think "the experiment only has 1 step".

The new `GET /api/experiments/runs/{runId}/steps`:
it expands the **planned steps** according to `experiment_process × loop_count` and then left joins the rows of the actual execution,
and the ones without a row are all marked `pending` (not executed). It returns the three counts `completed/failed/pending`.

#### 2. The execution history can be expanded

`Authoring → Execution history` —— every row can now be expanded, and when it is expanded it is a step table:

```
2 steps in total: 0 executed, 1 failed, 1 not executed
Step  Loop  Workflow                    Status    Run      Error
 1   #1  S100 封膜验证（页面执行）   Failed    #205377   1 node is not completed
 2   #1  S100 三步续跑验证          Not executed   —         —
```

Clicking the `#205377` of the "run" jumps straight to the run details —— that layer shows the **node level** status.

#### 3. The node level status is changed to Chinese

The run details used to show `pending` / `skipped` / `manual` directly, which nobody understands. It was changed to:

| In the database | On the page |
|---|---|
| `pending` | **Not executed** (it was not its turn yet) |
| `skipped` | **Skipped** |
| `manual` | **Waiting for a person** |
| `completed` / `failed` / `running` / `aborted` | Completed / Failed / Running / Terminated |

The confusion that is avoided: `pending` (it was not its turn yet) and `skipped` (it was skipped on purpose) are two different things,
and skipped also means that **the plate position is not trustworthy**, so it has to be stopped and confirmed (there is that logic in GraphRunner).

#### Measured

An experiment that "fails half-way" was created, `Demo-D 中途失败` (2 steps, out of paper injected on the S100):

```
Execution → {"status":"failed","totalSteps":2,"doneSteps":0,"error":"第 1 步（workflow 1254）失败"}
Expanded → 2 steps in total: 0 executed, 1 failed, 1 not executed
  seq1 → failed (run 205377, the node N1 = waiting for a person EC-08-400C)
  seq2 → not executed
```

After the fault is cleared, the re-run is back to normal (the S100 simulator has been restored to no fault).

### The experience (the workflow library gets a "see the canvas" shortcut into the designer)

The user asked: "how do I click this workflow to get into the canvas?" —— before it took **two steps**:
click the row (the detail card only appears on the right) → then click "open the designer" at the top right of the card.
If you only click the row, the card on the right is below, and it is easy to think "nothing happened when I clicked".

The table of the workflow library gets a "see the canvas" column, and clicking it goes straight into `/designer/{id}` (`stopPropagation`,
so it does not also trigger the selection of the row). Clicking the row is still "select it and see the details", both paths are kept.

### Features (clicking a workflow goes straight into the canvas + the run details draw the flowchart so that the node status can be seen)

The user pointed out that two things are unreasonable:
① the workflow library takes two clicks to get into the canvas; ② the orders that have finished or are half-way can only be looked at as a table, **the graph does not show which step is stuck and which step did not run**.

#### 1. The workflow library: clicking the row = into the canvas

Before, clicking the row only "selected" it, the detail card was on the right and below, and it was easy to think that nothing happened.
Now **clicking the row goes straight into `/designer/{id}`**; the pre-check / the shadow check / the execution are moved into the "details" button of the action column (the row click has
`stopPropagation`). The "see the canvas" column that the last commit added was therefore removed —— the entry point was duplicated.

#### 2. The run details: the flowchart (coloured by the status)

The new `GET /api/runs/{runId}/graph`: it returns `workflow.graph->>'xml'` + the status of every `run_node`.

- the `node_key` is the id of the BPMN element, so it matches up directly
- before the execution a `running` shell is created for **all** the nodes ⇒ the ones that still hang on `running` after the order has stopped are really **not executed**,
  and they are all folded into `pending`
- the nodes that are not on the graph (they were deleted later) are skipped, it does not affect the rendering

The frontend `components/RunGraph.tsx`: the **Viewer** of bpmn-js (read-only, it does not let a person change the graph of an execution record),
`canvas.addMarker(nodeKey, 'rg-<status>')` + the CSS colouring, and the legend at the top has the counts.

The actual effect (run 205378, a 3 step workflow stuck in the 2nd step):

```
The legend: completed 1    waiting for a person 1    not executed 1
  ○Start → [Seal green] → [Seal orange] → [Seal grey dashed] → ○End
```

The colours: green=completed, red=failed/terminated, orange=waiting for a person, yellow=skipped, blue=running, grey dashed=not executed.

A workflow without a BPMN graph shows "this workflow was not built from a graph (the data in the database has no BPMN graph), you can only see the node table below",
it is not a blank screen.

### The fix (the flowchart does not refresh: after it has finished it still shows "not executed")

It was only found by running it on the page: click execute → the run details show `Nodes 3/3, Failed 0`, all green,
**but the flowchart below says 「not executed 3」** —— the graph is lying.

Two reasons stack on top of each other:

1. `RunGraph` only fetches once on mount, and after that it **never refreshes**.
   When you come in right after clicking execute the nodes are still the `running` shells (which are folded into pending), and they are not updated after it has finished.
2. After the first version was changed to "polling", I also added "stop the polling when the order stops" ——
   and so the polling of a `paused` order stopped immediately, and when the user clicks "continue" and the run becomes completed, **the graph still sits at "waiting for a person 1"**.

The fix: it polls every 2 seconds the whole time it is mounted (the page itself refreshes every 2 seconds as well, it is only a small query),
and "putting the marker" and "building the canvas" are split into two effects ——
otherwise every poll does an `importXML` again and the picture flickers.

Measured (a 3 step workflow):
- it finishes normally: the legend `completed 3`, the three Seals are all green
- out of paper injected in the 2nd step: `completed 1 / waiting for a person 1 / not executed 1`, the grey dashed line marks the step that was not its turn yet
- change the paper and click "continue": it automatically becomes `completed 3` (no manual refresh is needed), and the simulator only received 2 Seals (N1 did not re-run)

### Features (workflow → execution record → click into it to see the node status of that run)

The user asked: "for a workflow that has already been executed, can I click into it and see the status of the individual nodes?"
The answer was **no before**: the run details page has the flowchart, but **there is no entry point that leads to the historic orders** ——
the live board only lists the running ones, the paused queue only lists the paused ones, and the Gantt has to be searched manually in the drop-down for the run id.
Clicking a workflow in the library goes to the designer (that is "what the graph looks like", not "how this run went").

This chain was added:

```
The workflow library → click the workflow → the "execution records" at the top right of the designer
   ↓ it lists the last 20 executions of this workflow (the status / the nodes 3/3 · waiting for a person 1 / the duration / the start time)
   ↓ click any row
The run details → the flowchart (coloured by the status) + the node table (the error codes)
```

- The backend `GET /api/workflows/{id}/runs?limit=20`:
  it queries the run by workflow_id, with the counts of the total nodes / completed / waiting for a person / failed
- The frontend of the designer gets an "execution records" dialog, clicking a row goes to `/runs/{id}`

Measured: `S100 三步续跑验证` lists 6 entries (3 of them 3/3 completed, 1 `paused 1/3 · awaiting an operator 1`,
and 2 older ones); clicking that paused one → the run details → the legend `completed 1 / awaiting an operator 1 / not executed 1`,
the graph shows the three segments green / orange / grey dashed, and the node table has `N2 manual EC-08-400C`.

### The fix (the flowchart has no connections + the start/end nodes cannot be told from the tasks)

The user pointed out two things on the page:
① the start node, the task node and the status cannot be told apart; ② there are **no connections** between the nodes, so the order cannot be seen.

#### 1. 🔴 No connections: only the sequenceFlow was written, not the BPMNEdge

When `WorkflowBuilder` generates the BPMN it writes a `<bpmn:sequenceFlow>`, but in the DI section there is **only a BPMNShape,
and no BPMNEdge (the waypoints)**. bpmn-js only draws the lines according to the DI —— so the graph is "a row of unconnected boxes".

This is the mistake a hand-written renderer is most likely to make: the "logic" and the "drawing" of BPMN are two different things,
a semantic node (sequenceFlow) is not the same as the visualisation metadata (BPMNEdge).

- The new workflows: `WorkflowBuilder.build` now computes the coordinates from the layout and adds the `BPMNEdge`
  (the middle of the right edge of the source box → the middle of the left edge of the target box)
- The workflows: the new `GraphDiRepair` parses the Bounds of every shape and adds the edges where they are needed,
  idempotent (the ones that already have an edge are skipped). Measured, of the 1001 workflows 9 were "a graph without connections", and all of them were repaired;
  running it a second time repairs 0.

#### 2. The start/end nodes are mixed up with "not executed"

Before only the task nodes had a colour: **the start/end were white circles, a task that was not executed was a grey dashed box**,
and on a light background it was hard to tell them apart at a glance in the legend.

- The start node: a light blue fill + a blue border (`rg-start`)
- The end node: a light grey fill + a dark grey border (`rg-end`)
- The text of the legend explicitly says "the circle = the start / the end, the box = a task"

Measured (run 205378): `Start (blue circle) → Seal (green) → Seal (orange) → Seal (grey dashed) → End (grey circle)`,
and the arrows connect the order together.

### Features (a branch that is not selected is marked `bypassed`) + the measurement: how far do the parallel / branch / loop / expression / variables actually go

The user asked "besides the ordinary task nodes, what about the parallel, the branch, the loop, the expressions, the variables? And when one of the parallel ones goes, are the others skipped?"
Two real workflows were run on the simulator (`T-PAR` 1257 parallel, `T-XOR` 1258 branch).

#### 1. The parallel gateway (AND): **it is supported**

```
Start → ◇parallel fork → [Seal-A] ↘
                  → [Seal-B] ↗ → ◇parallel join → End
```

Measured: both of them are executed (the simulator received 2 Seals), the `parallelJoin` only advances when both have arrived, and the whole order is completed.

#### 2. One of the parallel ones fails: **it is not skipped, the whole order stops**

The same workflow with out of paper injected into the 2nd Seal:

| The node | The result |
|---|---|
| Seal-A | completed |
| Seal-B | **manual / EC-08-400C** |
| The whole order | **paused** |

The join point does not let it through (they do not all arrive), and the other one that has already finished stays completed. That is right:
a failing parallel branch does not mean "skip", and whether it is retried is decided by the policy.

#### 3. The branch gateway (XOR): it can fork, but **the condition is not evaluated**

```
Start → ◇branch(default=f3) → [take branch A] (condition ${ok}==true) ↘
                        → [take branch B] (default)          ↗ End
```

Measured: it took **B (the default)**, and `A` was not taken.
The handling of the conditionExpression in `GraphRunner.choose()` is **to write a log line and then take the default** ——
the condition expression **has never been evaluated** (the number of uses of conditionExpression in the graphs is 0, so it was never exposed).

#### 4. 🔴 The branch that was not taken used to stay `running`

Before the change: the node of the branch that was not taken stayed at `running` (in the interface it was covered up by "the whole order is not running ⇒ it is shown as not executed"),
so you could not tell "this path is simply not taken" from "it is not its turn yet", and it could not be counted either.

The new state **`bypassed`** (and it is not a reuse of `skipped`!):

| The state | The meaning | The behaviour at runtime |
|---|---|---|
| `skipped` | It should have been done and it was not, **the plate position is not trustworthy** | GraphRunner **stops and hands over to a person** |
| `bypassed` | The XOR gateway chose the other path, this step does not have to be done | It carries on normally |

- the `node_status` enum gets `bypassed`
- after the whole order has **succeeded**, the business nodes that still hang on `running` are marked `bypassed` + `input.reason`
  (on a failure / a pause they are not marked: those `running` are "waiting to be continued", and marking them would make the continuation logic misjudge them)
- The interface: a light grey dotted line on the graph, the legend 「branch not selected N」, the status 「not selected」 + the reason in the node table

Measured (T-XOR): `走B分支 completed`, `走A分支 bypassed / the branch was not selected (the XOR gateway took the other path)`.

#### 5. The loop: **it is not supported**

The back edge is ignored directly by the `if (!done.contains(target))` of `activate()` —— on the second round it finds that the target has already been executed,
and it is not enqueued again. And because the condition is not evaluated, the "exit condition of the loop" cannot be judged either. To do a loop in the graph, the evaluation of the expressions has to be supported first.

#### 6. The expressions / the variables

| | The current state |
|---|---|
| `conditionExpression` | **It is parsed, it is stored, it is not evaluated** (`unevaluatedConditionCount` only counts it, it is not used) |
| The variables inside a workflow | None. Only the **experiment layer** substitutes the `${var}` into the parameters before it starts (the vars of `ExperimentExecutor`) |
| The default of a gateway | It is supported (when the XOR has no match it takes the default, and if there is none it takes the first one of the stable ordering) |

To really do the conditional branches, you need: an expression evaluator (a safe one, without eval) + a variable table at runtime (the output of the nodes is written into the context).
That is the work of the next round, it is not in the scope of this one.

### Features (the evaluation of the conditional branches / the loop over the back edge / the variables at runtime) + the three documents

The user asked for the "expressions, the variables, the loop" to be done, and that **every new feature comes with a test document**.

#### 1. The evaluator of the conditional expressions (`engine.ConditionEvaluator`)

Why it is written by hand: the expressions come from the user input of the flowchart, and a `ScriptEngine`/`eval` is handing the industrial computer over to somebody else.
A recursive descent parser written by hand only accepts `comparison/logic/parentheses/numbers/strings/variables`, and one character more is an error.
There is a dedicated injection case in the self-test: `... ; Runtime.getRuntime().exec('ls')` → it is rejected.

The trap of the type rules: `'10' > '9'` is false if it is compared as strings, and only true if it is compared as numbers.
So "if both sides can be treated as numbers it compares the values", and for the magnitude comparison **both sides have to be numbers**, otherwise it is an error and it does not guess.

#### 2. GraphRunner: the condition is really evaluated + the back edge can be entered again

- in `choose()` the conditions of the outgoing edges are evaluated one by one and the one that holds is chosen; if the evaluation fails → `unevaluatedConditionCount` is recorded and the default is taken (**it does not block the production**)
- `done` changed from "an absolute deduplication" to "**a re-entry is only allowed when the graph really has a cycle**": the DFS with the three colours computes a `hasCycle` once,
  and a graph without a cycle keeps the deduplication semantics (it prevents a parallel join from activating twice and running the device action twice)
- The protection: 10 rounds for a single node, 500 steps in total, and when it is exceeded it records the log and stops

#### 3. The variables: two sources

| The source | The implementation |
|---|---|
| The ones passed in at the start | `?vars=k=v` (the asynchronous path used to ignore the variables, this time they are added) → written into `run.input.vars` |
| The output of a node | After every successful task the `run_command.result` is queried, and the top level scalars are extracted and merged into the variable table |

The reason why the loop could not really loop before is exactly that there was no second source —— the variables in a condition never changed.

#### 4. `bypassed`: the branch that was not selected

`skipped` cannot be reused: the semantics of `skipped` are "it should have been done and it was not, the plate position is not trustworthy", and `GraphRunner` **stops and hands over to a person** when it meets it.
A branch that was not selected is a normal flow. A new enum value + it is only marked **after the whole order has succeeded** (on a failure / a pause the running ones are "waiting to be continued").

#### 5. The three documents (filled in as the user requested)

| Document | Content |
|---|---|
| `docs/流程控制-功能说明.md` | How to draw a conditional branch / a loop, where the variables come from, how to see it on the page, the 6 known limitations |
| `docs/流程控制-技术设计.md` | The syntax and the safety boundary of the evaluator, the trade-off of `done`→`visits`, the data flow diagram, the changes of the interfaces, the 5 technical debts |
| `docs/流程控制-测试报告.md` | The 23 cases of the evaluator listed one by one, the 6 end-to-end scenarios with the run ids, **the 5 gaps that are not covered** |

#### 6. A repair of the test infrastructure

`scripts/test-all.sh` did not compile `src/test` before, and the newly added test classes only reported "the main class cannot be found",
which looked like a failure but was actually "it was not compiled". Now the script compiles `javac src/test → out` at the beginning, and it reports the error and exits when the compilation fails.

In addition the format of the test output has to be the same as what the script parses (`passed N, failed M`, with a comma),
and my first version wrote `passed N / failed M`, so the script judged the output as "it cannot be parsed".

### The completion (the "5 that were not verified" of the last round are filled in, and on the way 2 real bugs are dug out)

The 5 gaps that were listed at the end of the last round are all verified now:

| # | The gap | The result |
|---|---|---|
| 1 | A loop driven by the output of a node | ✅ But first 2 bugs were fixed (see below) |
| 2 | A loop combined with a parallel | ✅ A 1 time + B 2 times = 3 Seals, no infinite loop and no deadlock |
| 3 | The limit of the number of rounds is configurable | ✅ `workflow.attr.maxIterations`, measured 3 rounds / 2 rounds both take effect |
| 4 | The pre-check of the condition syntax | ✅ The new `PF-09`, in the pre-check you can see "the expression ends unexpectedly" |
| 5 | Several branches (more than 2 outgoing edges) | ✅ The three inputs v=1/2/9 take A/B/default respectively, the rest are marked bypassed |

#### 🔴 Two real bugs: the output of a node simply cannot be read

The first one: the `run_command` hangs on the **step node** (`level=1`, `node_key=S1`), and the business node is its
`parent_node_id`. I queried it with `node_key=? AND level=0`, **and not a single row was found** ——
the variable table was always empty, `${plateType} >= 1` was always false, and the loop looked like "it did not take effect".

The second one: the `run_command.result` only has `{ok,durationMs}`. The business field that the device really returned
(`plateType`) is in the `device_raw_response`, and it is an **escaped JSON string**,
so it has to be `replace("\\\"", "\"")` first and only then extracted.

Without these two fixes, "the output of a node drives the condition" is a feature on paper —— and it is precisely the precondition for the loop being able to stop by itself.

#### The design trade-off: the limit of the number of rounds goes at the workflow level and not at the gateway level

`workflow.attr.maxIterations` (10 by default). The reason: a workflow usually has more than one back edge,
and configuring it per gateway makes it impossible for the operations to remember "who exactly is in charge of this one". The cost is that the limit is shared by the whole workflow, and it has been recorded as a technical debt.

### The fix (the method is empty in the "details" of a device)

The user clicks `ASPIN_2K-1 → Details` on the page and the list of the methods is empty.

Looking into it: the "details" of the interface reads the **`device_method` table of the database** (`/api/devices/{resourceId}/methods`),
while when `DeviceRegistrar` puts a device into the pool it **only writes the `device` row**, it does not write the methods —— the S100 / ASPIN_2K both have 0 entries.

The methods only existed in the **model library** (`DeviceModels`, 8 entries for aspin-2k / 12 for s100).
So there was a split: "the model library has the methods, the simulator can run them, but the interface does not show them".

The fix: when `DeviceRegistrar` puts a device into the pool it synchronises the methods of the model library into `device_method`
(`ON CONFLICT (device_id, method) DO UPDATE`, idempotent):

- the `name` uses the alias, and the commands that the manual does not document (e.g. `GetStatus`) are marked as "（not provided by the manual, it has to be confirmed on site）"
- the `param_schema.params[0].desc` holds the parameter description of the manual
- the `estimate_sec` / `timeout_sec` are derived from the `delayMs` / `actionTimeoutMs` of the model

**The methods that are no longer in the model library are not deleted**: the methods in the database may be referenced by the workflows, and deleting them would make those workflows report "the method does not exist".

Measured: `S100 12 entries / ASPIN_2K 8 entries`; the "methods (8)" drop-down of the page has CloseDoor / CreateModules / Home / RunProgram / CurrSpeed / Stop / GetStatus / CurrTime.

### The experience (the warning of an empty experiment gets an "add a workflow right now")

When an experiment is created the workflow can be left empty, so it is easy to end up with an empty experiment: "run the experiment" is greyed out,
and the warning says "pick a workflow with the drop-down above" —— but that drop-down looks like an input field, and a lot of people will not click it.

A **「add a workflow right now」** button was added on the right of the warning bar: clicking it does two things at the same time
—— it opens the drop-down + it `focus()`es the input field (the Select of antd only does `open` and does not focus,
so the search box is not activated and the typing does not go into the filter).

Measured (an empty experiment 824 was created): it is selected → the warning and the button appear → the button is clicked →
the drop-down opens directly → typing 「封膜」(sealing) filters out `S100 封膜验证` → it is selected →
"save orchestration" becomes clickable and the warning disappears. The 824 was deleted after the verification.

### Documents (the four documents of the review)

Before the external review the material is gathered into one set, it is put in `docs/评审包-20261103/`:

| The file | The content | The characteristic |
|---|---|---|
| `00-索引与评审须知.md` | The scale of the system, how to run it, **the 8 suggested review focuses**, the known problems and the risks, the places you should not believe | Read this one first |
| `01-功能文档.md` | The map of the pages (the menu→the route→the function), the six blocks of the features, the three end-to-end scenarios, the explicit "what is not done", the glossary | For the product / the user |
| `02-技术文档.md` | The layering of the architecture, the table of the package responsibilities, the data model, the complete chain of one execution, the key mechanisms, the overview of the APIs, the deployment and the operations | For the person who takes over the code |
| `03-设计文档.md` | **The 14 ADRs** (the background / the choice / the options that were dropped / the cost) + the boundary + the table of the risks + where to start if it were refactored | The focus of the review |
| `04-测试文档.md` | The four layer testing strategy, the list of the 373 cases, the L3 scripts, **the coverage matrix**, the 9 that are explicitly not covered, the regression baseline | To judge how credible it is |

Three things were deliberately done while writing it:

1. **every number is looked up fresh** (the lines of code, the number of tables, the amount of data, the number of test cases), and the reproduction command is written into the document —— the reviewer can check it themselves.
2. **"the places you should not believe" is a separate section**: the simulator is not a real machine; a green page does not mean the device moved (the `run_command` is the authority); the stress test data cannot be extrapolated.
3. **no password goes into the repository**: the review package only has placeholders. The device manuals were once cleared with a filter-branch because of their classification, and this rule has to be consistent.

Two untruths that were found on the way:
- The frontend is really **React 18.3** (not 19) and the sidecar is really **.NET 10** (not 8) —— they were corrected according to the `package.json` / `csproj`.
- "the experiments are sequential" was taken from a statistic in a comment in the code, not from the current environment (where `experiment_process` has only 13 rows); the document was changed to give the source.

### The review remediation (20261103-01): 1 blocking + 2 high severity + 5 untrue statements in the documents

The external review (reading the code + measuring with the commands) judged it as **not passing**. Every point was checked one by one, all of them hold, and they have been changed.

#### B1. The number of the test cases is untrue: the document says 373, the measurement says 368

It is not a typo, it is **environment dependent**: `DeviceErrorChainSelfTest` has a section of real database assertions (5 of them) that "only runs when `LABOS_DB_*` is set".
My shell has the database variables → 19 cases; the reviewer does not → 14 cases. The same code produces two numbers, and whichever one the document writes it is misleading.

The fix is not to change the number, it is to **make `--pure-only` really pure**: the script does `unset LABOS_DB_*`.
Now it is constantly **368**; the full run with the database is **386** (368 + 5 + 3 + 10), and both numbers are written into the document.

#### H1. The step rows / the commands are not deleted at all when it is continued (the audit records are silently lost)

The review said it was "derived statically", and I **ran it out with the database**:

```
After the pause: level1=2  cmd=2
Change the paper → continue: the simulator received 2 Seals, level1 is still =2  cmd is still =2
The log: 2 entries of [WARN] 步骤落库失败: duplicate key ... uk_run_node
And the S2 from before the change (failed) was UPDATEd to completed —— the failure history is covered up
```

There are two root causes:
1. `resumeAsync` only deletes `level=0`; the step rows (`level=1`) only point at the business node through the bare field `parent_node_id`, **they have no foreign key**, so they cannot be deleted; the `run_command` hangs on the step rows, so it cannot be deleted either.
2. `PersistingStepSink.seq` starts from 0 every time, and `node_key="S1"` collides with the leftover S1 on `(run_id,node_key,attempt)`, the exception is caught as a WARN, and **the execution carries on and the record is gone**.

The fix: ① first the `level=1` rows are deleted (located by the parent) and then the `level=0` ones, the commands go with them through the cascade of the foreign key; ② the step number continues from the `max("S"+n)` of the database.
The acceptance script gets 3 more assertions (the number of step rows / the number of command rows / no duplicate key), 34 → **37**.

#### H2. `bypassed` did not exclude SKIPPED

The exclusion condition of `markBypassedNodes` missed the `SKIPPED`. For an order that is `paused/waiting_human` because of a skip, the downstream nodes were marked as "the branch was not selected", and the page drew "it is not its turn yet" as "this path does not have to be taken". It has been added.
(It was measured by temporarily changing the policy of `EC-08-4001` to `skip`: N2=skipped, N3=running, the whole order paused, and N3 is not marked bypassed any more.)

#### The 5 untrue statements in the documents

| # | The problem | The handling |
|---|---|---|
| 1 | A `.NET 8` was left over in 02, it contradicts the `.NET 10` in the same document | It was changed to `net10.0` |
| 2 | The risk list of 00 says "this package contains the passwords", while the body has placeholders | The risk entry was changed to "only placeholders are used" |
| 3 | 00 writes `main @ 4570113` (the parent commit) | It was changed to `30cfc51` + it points at the handling update |
| 4 | 04 says "it can be verified right after `git clone`", while in fact the main code has to be compiled first | The script was changed so that it compiles `src/main` first when `out/` is empty; in addition the 7 self-test classes were moved from `src/main` to `src/test` |
| 5 | The versions of react-router / query are marked as "verified", while they are actually the lower bounds of the `package.json` | The versions that are actually installed were added (6.30.6 / 5.105.0) |

### Documents (the ROADMAP: it makes "the next step" the single authority)

After the review remediation the scattered "to do" items were in three places: §8.8 of the review record, §8 of the 00 of the four documents, and my own verbal plan in the conversation.
Writing the same thing in three places is bound to fall out of sync (the review B1 fell over exactly like that: one place wrote 373 and the whole document followed it).

The approach: a new `ROADMAP.md` at the root of the repository as the **single authority**, and the other documents only refer to it and do not repeat it.

The structure:
- §1 The current position (the finished M1/M3/M4/M5 + this round; the baseline numbers can be re-run)
- §2 The overview (the six phases M6–M11 + the dependencies + the estimates)
- §3 The details of the phases (for every phase: what has to be solved / the evidence of the current state / the feature list / **the executable acceptance** / the dependencies / the risks / the estimate)
- §4 **The priority → the phase mapping** (every P0/P1/P2 falls into one of the M, the "list of the features by phase" that the user asked for)
- §5 The definition of done DoD (5 of them, of which "the numbers must not go down" is a hard threshold)
- §6 The list of what is not done (the current state, with the reasons)
- §7 The relation to the review

The reason for the order is written into the document: **first the hole that loses data silently is closed (M6) → the configuration loop (M7) → the CI locks it in (M8) → only then the scheduling and the real machine (M9/M10)**.
M6 happens to cover the first two points of §8.8 of the review (loop_index, the foreign key of the parent), and both of them are of the nature of "the data is not kept completely", so they have to be fixed first.

The synchronised updates:
- the document table of the `README.zh-CN.md`: the ROADMAP is put on top (the single authority), the four documents are demoted to "the entry point of the review"
- §8 of `docs/评审包-20261103/00-索引与评审须知.md`: it was changed to refer to the ROADMAP + a summary of 6 lines
- §8.8 / §8.9 of the review record: they are marked as "merged into the ROADMAP", so that there is not a second copy in two places

### M6 is done (the loop lands a row per round with loop_index + the creation script gets the foreign key)

The M6 of the ROADMAP, which are the two P0 of the list after the review.

#### 1. One row per round of the loop: `run_node.loop_index`

Before, a business node only had one row (the unique key `(run_id,node_key,attempt)`), and a loop that ran 5 rounds also only had the final state ——
"why did the 3rd round fail" can never be looked up.

The approach (**purely additive, the existing semantics are not touched**):

| The row | loop_index | The meaning |
|---|---|---|
| The summary row | **0** | The **final result** of this node (the existing queries only look at it, it is equivalent to before the change) |
| The round rows | 1..N | The actual execution of the n-th round (the status / the error code / the start and end time) |

The unique key becomes `(run_id,node_key,attempt,loop_index)`; `GraphRunner` calls back the `RoundRecorder` on every round,
and `WorkflowService.recordNodeRound` does the INSERT per round (it copies the name / seq / node_type from the summary row and does not assemble the fields again).

**It is only recorded when "the graph really has a cycle"**: in a graph without a cycle every node only runs once, and the summary row already represents that,
and recording another row would double the "number of nodes" —— the first version is exactly what made the 3 step workflow of `verify-device-chain` run red
(`第 1 步状态不对（completed\ncompleted）`: two rows were found).

The query side is brought to one place (`loop_index = 0`): the number of the nodes, the current node, the colouring of the flowchart, the count of the workflow execution records.
The run details on the other hand **return all the rows**, and the frontend hangs the round rows under the summary row to expand them:

```
The nodes (1)
  One sealing round  Completed  —      ← the summary
    One sealing round  Completed  Round 1
    One sealing round  Completed  Round 2
    One sealing round  Completed  Round 3
```

#### 2. The `parent_node_id` foreign key: it is in the live database and not in the creation script

The review said "there is no foreign key", and when I looked at the live database it **has been there for a long time** (`run_node_parent_node_id_fkey ... ON DELETE CASCADE`).
The real problem is that `pg_rebuild/schema/96_schema_fix.sql` only does an `ADD COLUMN`, **it does not add the constraint** ——
and a database that is installed from the script in a new environment has no cascade, so the "deleting the business node leaves the orphan step rows" of the H1 kind would happen there too.

It has been added: the script gets the foreign key (idempotent, `DO $$ ... IF NOT EXISTS`); and the `loop_index` with the new unique key were written into the script as well
(`20_kernel.sql` defines it directly, `96_schema_fix.sql` goes the incremental way).

Verified on the side as well: of the 120,000 rows of `run_node` there are **0** dangling `parent_node_id`, so no cleanup script is needed.

#### 3. The tests

- `GraphRunnerTest` gets the new "loop: every round reports its round", it asserts that the callback is called 3 times and that the rounds are `[1,2,3]` (6 → 7 cases)

- `GraphRunnerTest` gets the new "loop: every round reports its round", it asserts that the callback is called 3 times and that the rounds are `[1,2,3]` (6 → 7 cases)
- Measured run 205411 (maxIterations=3): the four rows `loop 0/1/2/3`; the page "nodes (1)" is expanded and the three rounds are visible
- A 3 step workflow without a cycle: it is still 3 rows, `loop=0`

### M7 is done (the operability: the error codes can be added and deleted / the scope validation / the arithmetic of the expressions / the hot configurable thresholds)

#### 1. The error codes can be added and deleted (before they could only be changed)

`POST /api/error-codes` + `DELETE /api/error-codes/{id}` (a soft delete of `deleted_at`,
the history of the `error_occurrence` is kept). A duplicate code returns a 400 and not a 500 —— a unique key conflict has to be explained properly.
The page: a "new error code" in the toolbar + an inline "delete" (a Popconfirm).

#### 2. A scope that does not match is now marked in red

A code has a `scope` and a policy also has a `scope`; **when the scope of the policy and the scope of the code do not match, the runtime cannot match it**,
and it only falls through to the wildcard policy —— the configuration fails silently and this trap is hard to find.

- every code of the list carries a `scopeCovered` (whether there is an enabled policy with `scope = the scope of the code` or a wildcard)
- when it is not covered → the policy column shows the red 「Scope not covered」
- when a policy is saved the interface returns `{scopeCovered, warning}`, and the frontend hints accordingly

#### 3. The expressions support arithmetic

`+ - * /`, the precedence of the brackets, the unary minus. It is still a **hand-written parser**, there is no eval;
a string taking part in the arithmetic and a division by zero always raise an error (it does not guess the semantics). The cases go from 23 → 32.

#### 4. The heartbeat threshold can be changed on the page and takes effect immediately

Before, only the environment variable could be changed and then a restart was needed —— tuning "how long without an answer counts as being offline" is a daily action on site, and a restart interrupts the experiments.

- `sys_setting` for the persistence + `RuntimeSettings` as a static field, the heartbeat thread reads the memory on every cycle
- `GET/PUT /api/settings/heartbeat`; the page is at the top of **Operations → Alerts**
- **the offline threshold is forced to be ≥ 2× the interval** (the backend clamps it), to avoid the "the probe is 60s, 30s means offline" kind where every round judges it offline once first

The trap that was stepped on: `sys_setting` has **no** `(tenant_id, scope, setting_key)` unique constraint in the live database,
and `ON CONFLICT` reports directly "no unique or exclusion constraint matching". It was changed to the two step form of "first UPDATE,
and if it did not hit, INSERT" —— so a deployed database can run without a migration.

#### Along the way: the experiment variables were moved

At the top of the `/experiments` page there used to be an "experiment variables" card, which looked like "the global variables that all the experiments share".
It is now moved **inside the card of the selected experiment** (under the workflow orchestration table and above the execution history), together with "the workflows / variables of this experiment".

#### The verification

```
test-all --pure-only  378 / 0 (ConditionEvaluator 32)
test-all (with the database)       396 / 0
The interfaces: create / duplicate 400 / the soft delete of the delete / scopeCovered / warning / the reading and writing of the heartbeat and that it survives a restart
The pages: the "new error code" "delete" "Scope not covered" of the error codes; the "heartbeat parameters" card of the alert page
```

### M8 is done (the CI gate + the frontend e2e + the long stability soak test)

#### 1. `scripts/ci-gate.sh`: one command, four gates

| The gate | The threshold | The explanation |
|---|---|---|
| The pure logic unit tests | **≥378** and 0 failures | A drop in the number is a red light as well —— it forces a person to explain "where did the missing ones go" |
| The frontend tsc | 0 errors | |
| The device chain | **≥37** and 0 failures | It needs the database + the simulator (`--with-db`) |
| The frontend e2e | **≥14** and 0 failures | It needs 5173/18080 (`--e2e`) |

With the `.github/workflows/ci.yml`: a push / a PR runs the first two gates (they do not need the database), and the result is there in 3 minutes.

#### 2. `scripts/e2e-smoke.py`: 5 main chains, 14 assertions

It uses **Python Playwright** (it is already in the miniconda of this machine, and the Chromium is in the cache as well),
and it does not add an npm dependency to the webapp:

| The chain | The assertions |
|---|---|
| The login + the workflow library | The menu appears, the table has rows |
| Click a workflow row → the designer | The URL changes, `.djs-element` ≥ 2 (the canvas really rendered) |
| The run details | The flowchart card, the legend has the status counts, the node table |
| The experiment orchestration | The workflow table has rows, the execution history can be expanded into "N steps in total" |
| The error codes | The list has `EC-`, the policy tags / the not covered hint |

The first run gave 13/14 —— the "legend" assertion failed: the legend is rendered asynchronously, and `.count()` finds 0 at that moment.
It was changed to wait 2.5 seconds and then take the text of the whole page.

#### 3. `scripts/soak-test.sh`: the long stability soak test

It runs N executions and asserts three things: the failure rate is 0, the number of `run`/`run_command` adds up, and the memory of the JVM does not grow.

The trap that was stepped on: the first version took the baseline directly → 47MB grew to 74MB (+58%) and it reported a false alarm.
The reason is that the class loading / the connection pool / the JIT from idle to running are all counted in it —— **take the baseline after 3 warm-up runs**,
and after that it is 73856 → 74192KB (0%).

#### The explanation

The "the bulk ignore supports the filters" that was moved over from M7 was not done this time: it changes the deletion type operations, while the e2e currently locks down
"it can be seen / it can be clicked", and it does not cover the deletion semantics. Doing it together with the scheduling in M9 is safer.

### Features (the built-in nodes: the variables / the expressions) —— the engine side is done

The user asked "can a variable node, an expression node or a program code node be added to the flowchart".

#### The three boundaries that were decided

| The node | The decision | The reason |
|---|---|---|
| The variable `__setVar` | ✅ Do it | The loop / the condition need it to increment itself, it is safe |
| The expression `__expr` | ✅ Do it | It reuses the arithmetic of the condition evaluator, it is safe |
| The program code | ❌ Do not do it | An `eval` / a script engine is handing the industrial computer over to somebody else; it goes through a "whitelist of built-in functions" instead |

#### The way it is implemented (no custom BPMN element is added)

The node is still a `bpmn:task`, and it is marked as built-in by **`protocolName` starting with `__`**:

- parsing: `WorkflowGraphParser` sees the `__` prefix → `Kind.BUILTIN` (it does not look for a capability and does not look for a device)
- execution: `GraphRunner` executes it inline, **it does not go through the state machine and it produces no device command**
  - `__setVar`: `{var, value}`, the `${x}` in the value is substituted first
  - `__expr`: `{var, expr}`, it is computed with `ConditionEvaluator.value()` (it supports `+ - * /`)
  - the parameters reuse `workflow_node.params->'method_params'`, no new field is added
- on a failure: it writes `MANUAL` + `EC-BUILTIN-001` and **stops** (it must not go on to the conditional branch with a wrong value)
- the bookkeeping: `RunProgress.complete/manual` (a built-in node has no device / retry semantics, going through the state machine would only be roundabout)

#### Measured (run 205442, the workflow T-BUILTIN)

```
SET  completed  [built-in] __setVar → counter=0
A    completed  (the device task: Seal)
INC  completed  [built-in] __expr  → counter=1
run  completed
```

The unit test: `GraphRunnerTest` gets "a built-in node does not touch the device" (7 → 8), it asserts that the two nodes are completed and that **the number of the device commands is 0**.

#### The traps that were stepped on

The first run went straight to `SET manual 无可执行资源：节点未给出初始设备` ——
**the API was not restarted after the code was changed**, so it was still running the stale class. This kind of "it was changed but it did not take effect" is the easiest to misjudge as a design problem.

#### What is still not done (the next step)

The entry point in the designer panel for "turning a node into a variable / expression node + filling in the parameters".
The blocker: the designer currently **has no interface at all for writing `method_params`** (the parameters of the device tasks cannot be changed either),
and the modeling of bpmn-js does not support the undeclared custom attributes (`protocolName`) reliably.
The approach: add `PUT /api/workflows/{id}/nodes/{key}` (it changes the protocol_name + the method_params
and it patches that **one** attribute in the XML), and the panel only calls the interface and reloads —— it does not touch the internals of bpmn-js.

#### The built-in nodes: the entry point in the interface (finished)

The last commit only did the engine, and this time "how do you configure it on the canvas" was added —— and the key blocker is that **the designer has no interface for writing the parameters**.

##### 1. `PUT /api/workflows/{id}/nodes/{key}` + `GET` on the same path

- PUT: it changes `workflow_node.params->'method_params'` (and it records `builtin=true`) + it patches that **one**
  `protocolName` attribute in the XML (not a single character of the coordinates / the layout is touched)
- GET: it reads it back to fill the panel in (the method name comes from the XML, the parameters from the database)

Three traps were stepped on:

1. `workflow_node` **has no `protocol_name` column** (the method comes from the protocolName of the XML) → the first version of the SQL reported directly
   `column "protocol_name" does not exist`, and it was changed to only write the params.
2. `jsonField` only accepts **quoted string** values, `{"params":{...}}` is fetched as null → the interface returns a success but the database does not change.
   A `jsonRawOrString` was added: for an object it takes the part between the brackets, for a string it unescapes it.
3. After the helper was added **the call sites were forgotten**, and so it was "a success but it did not take effect" once more —— this time it was found by comparing the database by hand.

##### 2. The "node settings" card on the right of the designer

Select any task → a drop-down switches between the device task / the variable node / the expression node → fill in the `variable name` + the `value | expression` → save
→ the XML is imported again (the canvas is synchronised).

Verified entirely through the page:

```
Select INC (the expression node) → the panel is filled in with counter / ${counter} + 5
Change it to ${counter} + 10 → save
The database: {"var":"counter","expr":"${counter} + 10"}
The execution: SET __setVar → counter=0; INC __expr → counter=10; the run is completed
```

##### 3. What is still not done

The editing of the **method parameters** of the device tasks (the same interface can already store them, the panel is only missing the input fields); and the "whitelist of built-in functions".

### Documents (the consistency review of the review package: the numbers that would have gone stale were measured again)

The root cause of the last review falling over was exactly that "the numbers in the document do not match the measurement", so everything was measured again before the delivery:

| Item | The value in the document | The measurement | The handling |
|---|---|---|---|
| The lines of the backend Java | 30,241 | **29,671** (107 files) | Changed. The reason for the difference: the 7 self-test classes moved to `src/test` |
| The lines of the frontend | 8,048 | **8,393** | Changed |
| The route patterns | 49 | **50** (the node binding / setting were added) | Changed |
| The pure logic cases | 379 | 379 | Consistent |
| The full run with the database | 397 | 397 | Consistent |
| The number of the tables | 112 | 112 | Consistent |
| The code version | hardcoded `30cfc51` | it has not been the HEAD for a long time | Changed to "do not believe the hardcoded commit, check it with git log" |

In addition there were two structural problems:

1. **The ADR numbering is a mess**: D13 and D15 are about the same thing (the heartbeat threshold), and D16 comes before D15.
   They were merged into one D13 (first the environment variable, later it can be changed on the page and it takes effect immediately), and renumbered as D1–D15.
2. **The threshold of the gate is too low**: `ci-gate.sh` still writes 378, while the cases are already 379. The threshold was raised to 379 ——
   this number may only go up, and "one case fewer" has to be explained by somebody.

### The fix of BUG-20261103-01 (the creation artefacts are stale) + the gate that prevents the drift

The blocking item of the review 20261103-02: `deploy.sh` deploys the merged artefact `90_all_in_one.sql`,
while M6 only changed the fragments and **did not re-run merge.sh** —— so a database installed in a new environment is missing `run_node.loop_index`,
it is missing the foreign key of `parent_node_id`, and `uk_run_node` still has 3 columns.

The fix:

1. `merge.sh` is run again and the artefact is produced (2531 → 2536 lines).
2. In addition **5 lost `ALTER TABLE ONLY plate_well`** are put back —— the stale artefact had 4 entries of
   `syntax error at or near "ADD"`, because that line was swallowed in the fragment (it is not the fault of the merge, it is a hole in the fragment itself).
3. The new `scripts/check-schema-sync.sh`: it runs the merge again and compares it with the artefact of the repository (it fails and restores the working tree when they differ),
   and at the same time it asserts that the artefact contains `loop_index` / the 4 column unique key / the foreign key of the parent. It has been merged into the 2.5th gate of `ci-gate.sh`.
   A negative test was done: putting the stale artefact back makes it report "the artefact is stale" and it lists the diff, and putting it back again makes it pass.

The verification with the database (the new schema executes the artefact in full):

```
run_node.loop_index                                  ✓ it exists
uk_run_node      UNIQUE (run_id,node_key,attempt,loop_index)   ✓
run_node_parent_node_id_fkey  ... ON DELETE CASCADE            ✓
```

**An existing defect was found as well** (it is registered as `BUG-20261103-08`):
executing the artefact on a completely new database still gives 22 entries of `already exists` (4 identity sequences, 5 `multiple primary keys`).
The root cause is that `30_business.sql` has a hand-written DDL and a pg_dump style tail next to each other, and the two definitions define the same batch of tables;
`deploy.sh:112` uses `ON_ERROR_STOP=1` and it interrupts at the first one → **a completely new deployment does not run cleanly**.
It was not touched this time: it and the drift of the artefact are two different things, and mixing them makes it impossible to say "which change caused it".

### The fix of BUG-20261103-08 (a new database does not install cleanly: 22 entries of already exists)

The last round only fixed "the artefact is stale", and the re-check found a more fundamental problem: **executing the artefact on an empty database gives 22 errors**,
and `deploy.sh:112` uses `ON_ERROR_STOP=1` —— a completely new deployment interrupts at the first one.

The root cause: in `30_business.sql` the **hand-written DDL** and the **pg_dump style tail** are interleaved,
and both of them define the same batch of tables (the tail also has 23 tables of its own, so it cannot be deleted as a whole).

The approach: `guard_dup_decl.py` was added to `merge.sh`, and it wraps the **duplicate declaration type** statements
(`ALTER … ADD GENERATED`, `ALTER TABLE [ONLY] … ADD CONSTRAINT`) into
`DO $$ … EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition`.
59 statements were wrapped —— if the object does not exist it is created, if it exists it is skipped and a NOTICE is left, and the semantics are unchanged.

The trap that was stepped on: the first version reported `syntax error at or near "EXCEPTION"` after the wrapping —— I had stripped the `;` from the end of the statement,
and every statement inside a PL/pgSQL block has to carry its own semicolon.

The verification (an empty schema, `ON_ERROR_STOP=1`): **exit=0, ERROR=0**;
105 tables + 5 views are created; `run_node.loop_index` / the 4 column unique key / the foreign key of the parent are all there.

### The cleanup of BUG-20261103-09 (5 tables are declared twice)

The re-check of the review pointed out: in the artefact 5 tables have two definitions (the hand-written DDL + the pg_dump style tail),
and `IF NOT EXISTS` makes the second one skip silently, **and since the column sets are equivalent there is no side effect**, but "editing the wrong one does not report an error" is a latent hazard.

The approach: the duplicated section is deleted from `30_business.sql` (the `CREATE TABLE` + the `ADD GENERATED` right after it + the `pkey` of that table),
and the canonical copy is kept (`plate_well`/`experiment_sample` are in the 30, `resource_booking`/`run_variable`/`run_artifact` are in the 20).
**The foreign keys are kept** —— they only appear in the dump section, and deleting them would lose the constraints.

The way of verifying is worth recording: before and after the change a schema is created from scratch each time and `pg_dump --schema-only` compares them,
and **apart from the random `\restrict` marker of pg_dump they are identical line by line** —— that is more reliable than "it looks fine".

### Features (the paused queue: the bulk ignore by type + by time range)

Before there were only two levels: "ignore all" and "ignore the selected ones": the queue had dozens of historic leftovers from the integration,
so either you click them one by one, or you "ignore all" —— and the latter may take away an order that is really waiting for a person.

- The backend `POST /api/runs/dismiss` supports `{kind, beforeHours}` (when `runIds` is not given it narrows down by the condition)
  - `kind`: it only clears one kind of pause (the `pause_kind` is a **varchar and not an enum**, the first version wrote `?::pause_kind`
    and it reported directly `type "pause_kind" does not exist`)
  - `beforeHours`: it only clears the ones whose "last update is more than N hours ago" (it uses `updated_at` as an approximation of the moment of the pause)
- The frontend: "ignore all" becomes "ignore the ones in the filter (N)" + a drop-down of the time range (no limit / 1 day ago / 7 days ago),
  and the confirmation box states the current range

Measured (3 paused ones were created: A=waiting_human just paused, B=paused_fault, C=waiting_human 3 days ago):

```
① {kind:waiting_human, beforeHours:24} → dismissed=1, only C is cleared, A/B are unchanged
② {kind:paused_fault}                  → dismissed=1, only B is cleared
③ The 5 real waiting_human ones in the queue were not touched
```

### Features (the top bar of the designer: the estimated wait / the device is idle)

After clicking execute there is no feedback at all, and you do not know whether it "runs right away" or "queues up behind somebody else".

- The new `GET /api/workflows/{id}/eta`: it takes the resources that all the task nodes of the workflow use,
  looks at whether one of the `run_node` of somebody else is currently running on each of them, and takes the maximum of "the estimated duration − the elapsed time".
- The top bar of the designer shows `the device is idle` / `the estimated wait is N minutes`, it refreshes every 30 seconds; on hover it shows "which resource, who holds it, how much longer".

The trap: `workflow_node` **has no resource_id column** —— the binding of the device only exists in the attribute `resource="504830"` of the XML of the graph,
so the first version, which looked it up in the table, reported directly `column "resource_id" does not exist`; it was changed to dig it out of the XML with a regular expression.

Measured:
```
Idle       → {"waitSec":0,"known":true,"resources":1,"busy":[]}         The page: the device is idle
Occupied 300s/60s → waitSec=240, busy[{resourceCode:S100-1, holderRunNo:..., remainSec:240}]
Occupied 600s/30s → waitSec=570                                          The page: the estimated wait is 10 minutes
```

The limitation (it is written into the functional document): it does not include the changeover / the cleaning time; and if the holder is "paused, waiting for a person", what is estimated is the time it needs to finish running.

### Features (the time bar: the estimated start / end of every step) —— the precondition of the Gantt

The Gantt has to draw the bars, so first there has to be a "start + a duration". The new `GET /api/workflows/{id}/timeline`:
it simulates serially in the order of the nodes, the start of a node = max(the end of the previous step, the moment the device becomes free), and the duration comes from `estimate_sec`
(when it is 0 there is a fallback of 60 seconds and it is marked `estimated=true`).

The top bar of the designer gets a "time bar": no chart library is brought in, it is drawn with div + percentages, orange=an estimated value, blue=a configured value.

Measured (the 3 step workflow 1255): three bars of 0→1, 1→2 and 2→3 minutes, 3 minutes in total.

The trap: `workflow_node` **has neither resource_id nor seq** —— the binding of the device is in the `resource="…"` of the XML of the graph,
and the order can only be approximated with the id; both traps were stepped on one after the other (first seq, then resource_id).

The limitation (it is already written into the hint of the dialog): it does not include the changeover / the cleaning, and the parallel branches are added up serially (conservative).

### Features (the "new workflow" entry point is added, the experiment ↔ workflow loop is closed)

The feedback of the user: after creating an experiment you want to create a workflow, and **there is no entry point in the interface**; and once the workflow is created there is no path back to the canvas.
It turns out to be true: the backend only has `GET /api/workflows`, **there is no POST**; the workflow library only has a list; and the
"add workflow" of the experiment orchestration can only pick an existing one.

Three openings were added:

1. **The backend `POST /api/workflows`**: it creates the workflow row + it immediately lands a minimal graph "Start → End"
   (with the DI, otherwise bpmn-js cannot draw it); when the `code` is not passed it becomes `AUTO-<the timestamp>`.
   A duplicate name / a duplicate code returns a 400 with the hint "choose another one or leave it empty and it is generated automatically".
2. **The "new workflow" of the workflow library**: a dialog asks for the name → it is created → **it goes straight to the designer** (there is no need to look for it in the list any more).
3. **The "add workflow" drop-down of the experiment orchestration**: the first entry is 「＋ new workflow…」 → once it is created it is **added to the current orchestration automatically**
   (the orchestration still has to be saved); every row of the workflow table gets an "edit the workflow" → it goes straight to `/designer/{workflowId}`.

Measured (everything clicked through on the page): the workflow 1266 is created → it goes to the designer (the title and the canvas are right); 1267 is created in the experiment → it goes into the 4th row automatically;
the "edit the workflow" on the row works. The test data has been cleaned up.

### Features (the node settings: a device task can bind a device + a method + the parameters)

Before, the "node settings" on the right could only turn a node into a variable / expression node, **the column of the device task was read-only** ——
a task that was dragged out of a new blank workflow has no `protocolName`/`resource`, and the execution reports "there is no executable resource",
which is the same as "it can be drawn but it cannot be run".

Three controls were added (the device drop-down → the method drop-down → the parameters JSON), and the saving goes through the existing
`PUT /api/workflows/{id}/nodes/{key}`, and this time the interface was extended in two places:

1. The new `resourceId` parameter: at the same time it changes the `resource="…"` attribute in the XML (0 = unbind it)
2. The rewriting of the attributes changed from "a string replacement" to "parse it into a table of attributes and rebuild it" —
   the first version left a double space after the replacement (`name="Seal"  protocolName=…`), which is valid XML but very ugly,
   and when the replacement value contains a space it is easy to cut it in the wrong place

Measured: the N1 of 1255 is changed from `Seal` to `GetStatus` and then back to `Seal`, the XML attributes are tidy,
the parameters land in `params->'method_params'`; the panel is filled in correctly (the device S100-1 / the method Seal / the parameters `{}`);
a smoke run once gives run 205456 completed (N1/N2/N3 are all green).

### Documents (the "resource_id / seq did not make it into the table" is recorded as a prerequisite debt of M9)

While I was doing the ETA / the time bar / the panel of the node binding, three times I first had to take the `graph->>'xml'` and dig the resources out of it with a regular expression.
This is not "ugly code", it is that **every new feature has to parse it again**, and the four sets of rules are bound to drift apart.

It has been written into `ROADMAP.md` §3 M9 as a "prerequisite debt" + one P1 in §4:
`workflow_node` gets `resource_id` / `seq`, `saveGraph` writes both, the read side switches to the table, the existing rows are backfilled + a drift assertion.
The data model diagram of the 02 technical document also carries this sentence, so that the next person does not step on it again.

### The experience (when a method is selected, the name of the node changes with it)

The task that was dragged out is called 「Task」 on the canvas, and after the device is bound it is still 「Task」 —— at a glance you cannot see what this workflow is doing.

The approach (it is done on the side in `patchNodeAttrs`, no extra interface): **only the "placeholder name" is changed**, and the criterion is threefold
—— the name is empty / the name equals the name from before / it is still the placeholder name of bpmn-js (`Task`, `Activity`, `任务`, `活动`).
If it does not hold, the name that the user gave it is kept (for example "我的封膜").

The built-in nodes get a Chinese name (`__setVar` → variable, `__expr` → expression), otherwise strings like `__setVar` appear on the graph.

The three cases were measured (the N1/N2 of 1255):

| The scenario | The result |
|---|---|
| `Seal` → `GetStatus` (the name equals the method name) | `name="GetStatus"` ✅ |
| Then it is changed back to `Seal` | `name="Seal"` ✅ |
| The N2 is named 「我的封膜」 by hand and then it is changed to `GetStatus` | `name="我的封膜"` **it stays unchanged** ✅ |

### The prerequisite debt of M9 is done (`workflow_node` gets resource_id / seq)

This is the same thing that I kept stepping on while doing the ETA, the time bar and the panel of the node binding: **the binding of the device and the order only exist in the XML of the graph**,
and the three features each wrote their own regular expression, and the Gantt would have been the fourth.

#### What was done

| Item | The result |
|---|---|
| The columns | `workflow_node.resource_id bigint` / `seq int` (the live database + `20_kernel.sql` + `96_schema_fix.sql`) |
| The dual write | `saveGraph` writes the columns when it saves; the XML stays the authority (bpmn-js has to be able to open it) |
| The backfill | The new `BindingBackfill`: 1009 workflows / 13,990 rows; with `--check` it only compares |
| The switch of the read side | The ETA, the time bar and the node binding all read the columns back; when the table is empty it falls back to parsing the XML (the fallback for the existing rows) |
| The drift guard | `verify-device-chain.sh` gets a 9th group of assertions, 37 → **38** |

#### The two traps

1. **`seq` cannot be the traversal order of the parser.** The first version took the order of `graph.nodes()` as the seq,
   and after the backfill the `--check` reported 13,925 inconsistencies —— the same XML has a different order on two runs.
   It was changed to `BpmnGraphParser.documentOrder(xml, keys)`: the numbering follows the order in which they appear in the XML.
2. **The document order has to skip the definition level ids.** `Def_x` / `Proc_x` / `Diag_x` / `Plane_x` also carry `id=`,
   and counting all of them makes the seq of the nodes start at 3 (it is ordered, but it reads very strangely). Now only the real node keys are counted.

#### What was found on the data side

The XML of the workflow 1248 is broken itself (the prefix of `di:waypoint` is not bound), and it is skipped with a message during the backfill.
This kind of dirty data has to be fixed separately, but it does not affect the other workflows.

#### The verification

```
BindingBackfill           → 1009 workflows / 13990 rows
BindingBackfill --check   → 0 inconsistencies
verify-device-chain       → 38 / 0 (the new "the table and the XML agree")
The ETA / the timeline / the node binding → they all query the columns now, and the result is the same as before the change
```

### Features (the chain of the estimation of the duration of every step) + the fix of the dirty data of 1248

The user asked "what do we do when there is no data for the estimated duration" —— in fact **there is data**: in `run_command` there are 116,699 commands with
`sent_at` / `finished_at`, and the P50 per method gives a basis.

#### The estimation chain (`workflowTimelineJson`)

| The priority | The source | The criterion |
|---|---|---|
| ① | `manual` | The `workflow_node.estimate_sec` that a person filled in by hand in the workflow |
| ② | `history` | The **historical P50** of that method, and the sample has to be ≥ 20 (one or two samples are worse than the default of the model library) |
| ③ | `device` | The `device_method.estimate_sec` of the model library (278 of the 353 entries have a value) |
| ④ | `default` | The fallback of 60 seconds, `estimated=true` |

Every row of the time bar carries a source label (by hand / the historical P50 / the model library / the fallback), and the hover gives the explanation —— so that the person who looks at the graph knows which ones are guesses.

Measured: 1255 (Seal) → the model library 12s ×3 = 36s; a workflow with a Transfer goes by manual;
the historical P50 of `Transfer` is 10s (43,157 samples) and the P90 is 39s (4 times as much, the scheduling has to leave a margin).

**A data quality problem**: all the 177 historical samples of `Seal` have `finished_at = sent_at` (a duration of 0),
so it falls to the model library instead of the history —— which shows that **some drivers do not record the end time accurately**, and the P50 is only credible for these methods.

#### The dirty data of 1248

The XML of `TEST-COMPILE 编译测试流程` (category=test, a test fixture created on 10-29) uses
`di:waypoint`, but the `<bpmn:definitions>` **does not declare `xmlns:di`** —— and any strict XML parser reports
"the prefix di is not bound".

The impact is limited (it is a test fixture, no experiment refers to it), but it makes the bulk operations like the backfill / the saving skip one workflow.
The fix: the `xmlns:di` declaration was added to it (one UPDATE), and then the backfill was run again —— now all the 1009 workflows can be parsed,
and `--check` has 0 inconsistencies.

### The refactoring (the experiment: the list page → the detail page)

Before, `/experiments` was the orchestration page directly and it relied on a drop-down to pick the experiment —— and that drop-down **cannot tell the island, the content and the version apart**,
and there is no way to see at a glance how many experiments there are and how many workflows each of them has.

It was changed into two levels:

| The route | The page | The content |
|---|---|---|
| `/experiments` | The list | The number / the name / **the island** / **the version** / the number of workflows / the status / the planned time / the remark; the search, click a row to go into the details, the new experiment |
| `/experiments/:id` | The details (the former orchestration page) | The workflow orchestration + the variables + the execution history; the "← back to the list" at the top, the drop-down switches via the route |

The accompanying changes:
- `experiment` gets `version int default 1`, and it is **incremented by 1 on every save of the orchestration** (the list shows v2/v3, and at a glance you see how many versions there have been)
- `GET /api/experiments` gets `islandCode/islandName/version/flowCount/remark`
- The `expId` of the detail page is now driven by the route parameter; "select the first one by default" only takes effect when there is no route id, otherwise it would overwrite the experiment that the URL specifies

The trap that was stepped on: the patch of `experimentsJson` and the "save the orchestration bumps the version" were written in one script,
and the assertion of the latter failed with a `SystemExit` → **the content that had already been changed was not written to disk** (the write is at the end of the script).
As a result the interface still had the fields from before the change and a whole round of troubleshooting was wasted. The lesson: when one script changes several things, it has to write in stages.

### Features (the scheduling Gantt · the read-only first version)

The new page `/schedule` (Run → Schedule Gantt): it draws "how long it takes to run the next few experiments in order".

- One row per experiment, the colour block = one workflow, the same colour = the same device, the hover shows every step
- The data comes from: `workflow_node.seq/resource_id` + the chain of the estimation of the duration of every step (it does not parse the XML)
- **It explicitly only does the serial lower bound**: "only one experiment runs at a time", it does not do the interleaving of the device level parallelism —
  that needs the scheduling algorithm to be decided first, and drawing it now would turn an unverified assumption into a picture

Measured: the 8 experiments add up to about 3 minutes, and the duration of each segment agrees with the interface of the time bar.

#### ⚠️ It almost overwrote an existing feature

At first I wrote the new page directly as `pages/Gantt.tsx` —— **that file already exists** (180 lines of
"the timeline of the nodes of one execution", it goes through `/api/runs/{id}/timeline` and it draws a dark bar chart in SVG).
It was only noticed because `git status` showed `M Gantt.tsx`.

The handling: the file from before the change was restored from HEAD, the new page was renamed `ScheduleGantt.tsx` with the route `/schedule`,
and the two Gantts coexist in the menu and are clearly distinguished:

| The menu | The route | What it shows |
|---|---|---|
| Analysis → Gantt | `/gantt` | **One execution that has already run**, the timeline of the nodes |
| Run → Schedule Gantt | `/schedule` | **The experiments that have not run yet**, how long they take in order |

The lesson: before you start, run `git log -- <the target file>`, and do not assume that "this name should not have been used yet".

### Features (the scheduling Gantt gets a "by device" view)

Before there was only the "by experiment" view with one row per experiment, and you could not see **which device is busy and when it is free**.

A Segmented switch was added:

| The view | One row | The question it answers |
|---|---|---|
| By experiment | One experiment | How long it takes in total |
| By device | One device | Which one is the bottleneck and when it is free |

The scheduling is still the serial lower bound (only one experiment runs at a time), but the `start` of every step is `max(the end of the previous step, the moment the device becomes free)`
—— two nodes of the same workflow that use the same device are correctly strung together, and they are not drawn as overlapping.

#### Two device identifiers were fixed on the way

While looking at the device view it turned out that all 63 steps were piled up in "the unbound device", and looking into it **the resource ids in the data are no longer in `resource_node`**
(example: the workflow 1239 is bound to 383/387/398…, and these ids cannot be looked up for a code any more).

- `resource_id = 0` is the "no device" of the data → it is treated as `null`, and it is not treated as a device with the name 0
- When the code cannot be found it shows **`设备#387 (expired)`** instead of "the unbound device" ——
  it really is bound, only the thing it was bound to is gone, and the two cases are completely different when you are troubleshooting

Measured (the Gantt of several devices Demo-G, the experiment 825 / the workflow 1239): 15 device rows, `设备#387` with 25 steps is clearly the longest,
and the bottleneck is visible at a glance.

### The changes (the menu "experiment orchestration" → "experiment list"; the new workflow of the detail page goes to the designer)

- The menu on the left: `experiment orchestration` → **`experiment list`** (`/experiments` is the list now)
- The title of the detail page: `experiment orchestration` → **`experiment details`**
- The "＋ new workflow…" of the detail page: it creates the workflow → **it lands in the orchestration of this experiment first** → it goes to the designer,
  and the URL carries `expId/expName/island`, and the top of the designer shows 「this workflow belongs to the experiment X · the island Y」+ "back to the experiment"

In this way "adding a workflow to an experiment" is done in one step, and it does not happen that "you draw it and come back and find that it was not added to the experiment".

#### 🔴 Two traps were stepped on along the way, both of them are fixed

1. **The interface of saving the orchestration reads the parameters from the query, and reading them from the body is the same as clearing them**
   The interface is `POST /api/experiments/{id}/processes?workflowIds=a,b`, and my first version put the ids in the body →
   the backend could not parse them → it treated them as an "empty array" → **it cleared the two orchestrations of Demo-D**.
   Demo-D was restored after the combination of Demo-B (1255 + 1254), and the version number was set back to 1.
   At the same time a defence was added to the interface: **a missing `workflowIds` parameter is a 400 directly**, and to clear it you have to explicitly pass `?workflowIds=`.

2. **The sentinel item -1 of the drop-down was taken for a workflow id**
   The value of the option 「＋ new workflow…」 is `-1`, and the `onChange` of the Select fires anyway → `-1` gets into the `order` →
   it hits the foreign key when it is saved (`Key (workflow_id)=(-1) is not present`).
   It is now filtered with `<= 0` in both the `onChange` and the `add()`.

### The hardening (three lines of defence of saving the orchestration + the regression assertions)

In the last round I cleared the two orchestrations of Demo-D because of the "the parameters in the body, the backend only reads the query". Fixing the frontend is not enough ——
**the characteristic of this kind of error is: the interface returns 200 and the data is gone**. Clicking it by hand on the page basically does not find it, it has to be nailed down with assertions.

#### The three lines of defence of the backend (`replaceExperimentProcesses`)

| The situation | The behaviour now |
|---|---|
| The `workflowIds` parameter is missing | **400** 「to clear it, explicitly pass ?workflowIds=」 |
| An id ≤ 0 (the frontend mixes in the sentinel value -1 of the "＋ new workflow…") | **400** 「there is an illegal workflow id in the orchestration: [-1]」 |
| The id does not exist / it has been deleted | **400** 「the workflow does not exist or it has been deleted: [999999]」 |

**The order is crucial**: all the validation is put **before** the `DELETE`. The first version had no validation, and only after the deletion did it find out that it could not insert anything →
the orchestration was already gone and not even a rollback could save it (it could be rolled back in the transaction, but the error message is
`Batch entry 1 ... violates foreign key constraint`, and it does not show which id it was).

`Batch entry 1 ... violates foreign key constraint`, and it does not show which id it was).

#### The regression assertions (the 10th group of `verify-device-chain.sh`, 38 → 41 cases)

```
✓ A missing workflowIds → 400, the orchestration was not cleared (N entries)
✓ An illegal workflow id (-1, the sentinel value) → 400, the orchestration was not cleared
✓ A reference to a workflow that does not exist → 400 (the validation is before the deletion), the orchestration was not cleared
```

All three assert the **HTTP code** and **that the number of entries is unchanged** at the same time —— looking at the HTTP code alone is not enough: what if the interface returned a 400
→ but deleted first and reported the error afterwards, the data would be gone just the same.

The threshold of the device chain of `ci-gate.sh` is synchronised from 38 → **41**.

### Features (dragging the Gantt to reorder it = the manual queue-jumping)

The scheduling Gantt could only be looked at before. Now the rows of the "by experiment" view **can be dragged**, and it is saved when they are let go.

#### The backend `POST /api/experiments/reorder`

- the body `{"ids":[3,1,2]}`: the order of the array is the execution order
- it becomes `priority = 100 - index` —— the sorting of the scheduler (`ExperimentScheduler`) is
  **priority from high to low**, so the first one of the list gets the highest score; the page sorts by the same rule as well,
  otherwise "the order after the drag" and "which one really runs first" are two different things
- the validation: the id has to exist and it must not be repeated; **only `created`/`planned` can be reordered**, the finished / the running ones are a 400 in any case

#### The frontend

- The row has a `⠿` handle; the rows that cannot be dragged (the ones that have run / that are running) get `cursor: not-allowed` and they are made semi-transparent
- The drag source is put into a **ref** and not into the state: the `dragstart` and the `drop` may arrive in the same round of events
  (an automated test, a synthetic event), and with the state you would read a stale value that has not been committed yet
- Before the submission **only "the part that can be dragged" is taken**: the page mixes in the experiments that have already run, and a full submission is rejected by the backend

#### Measured

```
Before the drag: 培养箱出板(p100) → Demo-G(p99)
After the drag:  Demo-G(p100)      → 培养箱出板(p99)      ← the order of the page and the database both changed
POST /api/experiments/reorder {"ids":[820]}  → 400「只有还没跑的实验能调序，这些不是：[820(finished)]」
```

Two things about the look were fixed on the way: the `Alert` did not render the markdown (the `**` showed up as it is), and the track was too bright in the dark theme.

### The verification ("several experiments / several workflows in parallel" — was it ever tested?)

The user asked two things: does the Gantt have to do the parallelism, and has the parallelism actually been tested. I did it as a verification task,
and the conclusion and the evidence were written into `docs/并行能力验证-20261103.md`.

#### The conclusion

| The scenario | The conclusion | The evidence |
|---|---|---|
| The parallel branch inside a workflow | ✅ Both of them run | `verify-parallel-flow.py` 4/0 |
| The two branches contend for the same device | ✅ It is serialised automatically (DeviceLock) | The overlap of the command time windows = 0 |
| Several experiments on the same island | ✅ They queue up and are serial | The peak parallelism = 1, `verify-parallel-experiments.py` 3/0 |
| Several experiments across islands | ⚠️ The code supports it, **it was not measured** | Only Device_Test has a simulator attached in this environment |
| The parallelism modelled in the Gantt | ❌ It is not done (deliberately) | See §5 of the document |

#### An observation that is worth recording

The parallelism **inside a workflow shows no speed-up**, and the reason is not that "it did not run in parallel", it is that **the device is the bottleneck**:
the interval between the commands of the two branches is 201ms and the time windows do not overlap —— `DeviceLock` queued up the "contention for the same device".
In other words: in the single machine scenario the parallelism is limited by the number of devices, not by the number of threads.

#### The two new scripts that can be run again

- `scripts/verify-parallel-flow.py`: it builds the graph automatically (a fork / a join) → it executes it → it asserts that both branches are completed + that the device is serial → it cleans up
- `scripts/verify-parallel-experiments.py`: it creates N experiments automatically → it submits them asynchronously at the same time → it samples the number of `running` → it asserts that the peak = 1 → it cleans up

The passwords in the scripts always go through the `LABOS_DB_*` environment variables, they are never written into the files.

#### The trap that was stepped on

The first time the flow script was run, the Seal failed 4 times in a row and went to a person —— the default device picked "the first one in the database",
and that one has no simulator attached. It was changed to take **the device that really ran a command successfully in the last 2 hours**.

#### The wording was unified on the way

The Gantt page used to write "the interleaving of the parallelism waits until the scheduling algorithm is decided", and that is easily taken for "it will be done later".
It was changed to write explicitly "this picture does not model the parallelism, the real completion can only be earlier"; a section 「the real boundary of the parallelism」 was added to the 01 functional document;
"the interleaving of the device level parallelism" in the ROADMAP was changed from "not done" to "**explicitly not done**".

### The gate (the parallel capability is connected into the ci-gate: the 2.6th gate)

The two parallel verification scripts were connected into the gate, so that from now on the "parallelism" has an automated safety net and it does not rely on a single manual verification any more.

```
./scripts/ci-gate.sh --with-db
  1. The pure logic unit tests   379 / 0
  2. The frontend types         tsc 0 errors
  2.5 The creation artefacts     the same source as the fragments
  2.6 The parallel capability    inside the workflow 4/0, the concurrency on the same island 3/0
  3. The whole device chain     41 / 0
  The gate passed
```

#### The three design details

1. **It is placed before the device chain** (2.6 < 3): the chain script stops its own sidecar when it has run,
   and the parallel script relies on the sidecar + the simulator that are already running in the environment —— the other way round it fails falsely.
2. **The thresholds may only go up as well**: `MIN_PFLOW=4`, `MIN_PEXP=3`, so that the deletion of an assertion gives a red light.
3. **It skips gracefully when the environment is not suitable**: when there is no "device that executed successfully in the last 2 hours", or when the API is not available,
   the script prints `SKIP:` and exits with 0, and the gate shows "skipped (there is no usable device in the environment)" instead of red.
   —— A clean CI without a simulator is the normal case, and the gate must not fail because of it.

The default device of the flow script was fixed on the way: before it took "the first one in the database", and it once caught the one that has no simulator attached
(the Seal failed 4 times in a row and went to a person); now it takes **the device that really ran a command successfully in the last 2 hours**.

### Features (the editing of the parameters of a device task + a "＋ new workflow" button in the details of an experiment)

#### 1. The parameters of a device task are now rendered as input fields according to the definition of the method

In the "node settings" on the right of the designer, after a device task was selected there used to be only one JSON text box ——
which is the same as requiring the user to know the parameter names and the format. Now:

- the input fields are rendered **one by one** according to the `param_schema` of `/api/methods/{m}/params`
  (the label is the parameter name, a `*` marks it as required, the placeholder is the explanation of the method definition)
- the values support `${variable}`, with the same substitution rules as the condition expressions
- the "raw JSON" text box is still kept below: it is used when the method does not define the parameters, or when extra fields have to be passed
- changing an input field **synchronises that JSON** (a single source of truth, so it does not have to be assembled again when it is saved)

Measured: the Seal node of `S100 封膜验证` is selected → the input field of the parameter `args` (the placeholder `0x04: aa 04 00 … 00 ff`)
→ `0x04 AA 04` is filled in → it is saved → in the database `{"args": "0x04 AA 04"}` ✅

**The trap that was fixed**: the `method_params` of this node is the number `0` in the database (the data in the database is not an object),
and the first version put `JSON.stringify(0)` into the input field → the structured input was all empty. Now it is normalised to `{}` when it is loaded.

#### 2. An explicit "＋ new workflow" button is added to the details of an experiment

Before there was only the "＋ new workflow…" entry of the "add workflow" drop-down —— it is a drop-down item, and **the people who use it for the first time do not find it**
(the feedback was "there is no button"). Now the toolbar gives a button directly, and it opens the same dialog.

#### 3. "Canvas orchestration" got an explanation

This button has never been explained, and it is easily confused with the "workflow designer". A Tooltip was added:
a node can be 「a workflow / a variable / a fork / a parallel / a loop」, and it is **two expressions of the same experiment** as the workflow table above, so pick one.

### Documents (the unification of the experiment orchestration: the design draft v0.1 + a "it does not take effect" warning on the canvas)

The user raised four architectural questions (the unification of the graph / the process, a shared canvas framework, the priority / the nesting between the workflows, the batches).
This time no code was written, first the draft: `docs/设计-实验编排统一-v0.1-草稿.md`.

#### The current state that was found out (all of it was checked against the code / the tables)

1. **`experiment_graph` is not executed by anybody** —— only the canvas API reads and writes it;
   `ExperimentExecutor` only reads `experiment_process ORDER BY seq`.
   In other words **the canvas is currently a dead end**: you change the order and run it, and what runs is still the order of the table.
2. **The semantics the canvas can draw > the semantics the executor can run**: the canvas supports the fork / the parallel / the loop,
   and the executor only runs the seq linearly.
3. **There is already a precedent of "it was drawn and never executed"**: the field `experiment_process.run_after` exists and the API returns it,
   but the executor never reads it —— the typical "the model came first, the execution did not follow", and it makes the next person assume that it takes effect.

#### The proposals for the four questions (the options and the costs are all in the draft)

| # | The proposal |
|---|---|
| 1 | **The process is the single source of truth**, the graph is demoted to the layout; the canvas compiles it into the database when it is saved; the table becomes a read-only mirror |
| 2 | In the short term the two editors are kept (the canvas → the process), in the medium term the experiments also move to BPMN (callActivity) and it is done together with the DAG |
| 3 | A **partial order DAG + a priority** is used, **no nesting of the sub-experiments**; the real need for the nesting is satisfied with the batches / the templates |
| 4 | `experiment_batch` + it is expanded into several runs (reusing the existing runs / the continuation), and a failure only re-runs that batch |

The key argument: the device lock (`DeviceLock`) already guarantees that "the same device is not used by two workflows at the same time",
so the concurrency of the DAG is safe —— and that is a lot less work than redesigning the lock.

#### One thing that was done on the way

A **warning bar** was added at the top of the canvas page: 「the canvas currently only stores the picture, the execution still follows the workflow orchestration table」.
If this state is not said out loud it is worse than not having a canvas at all —— the user will think that changing the order takes effect.

#### What was explicitly not done

S1 (the compilation into the database) waits until the draft has been reviewed; in the README / the ROADMAP it is marked as **M9.5 (a draft, waiting for the review)**.

### Features (the frontend entry point of the experiment level pause / stop / resume + the on-site hold card)

The backend was finished back in `4458c88` (the scoped hold + the action boundary checkpoint + the continuation from a breakpoint), and the interfaces are there,
but **there is not a single button on the page** —— according to the DoD "the feature can be demonstrated: it can be clicked out on the page, it is not only an interface",
this item does not count as done. This round adds the frontend entry point.

#### What was done (`webapp/src/api/client.ts` + `pages/Experiments.tsx`, `85a404e`)

| The location | The content |
|---|---|
| The execution history · the row actions | Queued / running: pause, stop; paused / failed: resume, stop; nothing is shown for a terminal state |
| The pause dialog | Choose the scope + the reason; the default is **the whole island** (the same as the default of the interface —— the experiments of the same island share the robot arm, and stopping only yourself means the other experiments carry on contending for the device); it explains "it only stops at the action boundary, the plate position is not released" |
| The resume | It resumes with one click; when it is covered by a `site` level hold it tells the truth with "still covered by an on-site hold: …, release the hold first", it does not pretend it has resumed |
| The stop | A second confirmation (it states: it does not interrupt the running one, the action boundary does the wrap-up, a terminal state cannot be recovered); when `stop_requested` is set the status column shows 「stopping」 |
| The "on-site hold" card | It calls `GET /api/runtime/holds` and refreshes every 5s; the scope / the type / the reason / the start time, the hold of the current experiment is tagged; with no hold it shows the empty state |
| The "pause reason" column | The `pause_kind` (a manual pause / a device fault / the plate position is unknown / waiting for a manual confirmation) + the reason |

Two details:

1. **There are two formats of the time**: the hold interface gives an ISO (`…T10:50:40Z`), while most of the other interfaces give a JDBC local time string.
   Taking `slice(0,19)` as it is shows the UTC as local (8 hours off), and the operator misjudges "how long has it been stopped"; it is unified with `fmtTime`.
2. **The "pause reason" has to show the `pause_kind` and not only `paused`**: a device fault and waiting for a manual confirmation
   are two different handling flows, and if it is displayed wrongly a person follows the wrong flow.

#### The two defects that were caught and fixed during the integration

- **BUG-20261103-11** (medium, `f335a10`): the e2e main chain 4 has been **failing since `051f4d1`** (the menu rename + splitting the list / the details into two levels),
  and the script was not changed with it. The gate does not have `--e2e` by default, so the "5 main chains" was only green in the document.
  It was changed to experiment list → click a row to go into the details → assert the "on-site hold" / the control column → expand the execution history; the number of assertions 14 → 16.
- **BUG-20261103-12** (medium, `046836d`): when an experiment without an island asks for an `island` pause the backend degrades to
  `experiment`, but the interface returned the **requested value** to the frontend —— the interface says "the scope: the island" while the database only has an experiment level hold.
  `RunControl.pause` was changed to return the scope that actually took effect, and the interface returns that value.

The overview table of the BUG ledger was fixed on the way as well: `-08`/`-09` were separated by a blank line and the "handling" section, and `-09`/`-10` had no table header,
so it did not render as a table; only the position of the rows was adjusted, the verdict / the status text was not touched.

#### The verification (it can all be run again)

| Item | The result |
|---|---|
| `test-all.sh` with the database, in full | **passed 500 / failed 0** |
| `ci-gate.sh --e2e` | The gate passed (the pure logic 423/0, tsc 0 errors, the creation artefacts the same source, the e2e **16/0**) |
| The new script `scripts/verify-exp-control.py` | **22/22** (it borrows an empty experiment → it inserts 2 queued runs → the UI goes through pause → resume → stop → it deletes the data and restores the state, `3ab54a3`) |
| The leftovers of the self-tests | `experiment_run` 39 (the baseline value), `runtime_hold` 0, the experiment #813 is restored to `created` |

#### A note about the environment (it is easy to step on)

The API 18080 of this machine was originally **a process from 14:46**, and it does not contain the routes of M9.5 —— `GET /api/runtime/holds`
returned a 404 straight away. It was restarted with the current `out/` (the DB connection string was taken from the environment variables of the leftover process).
From now on, either restart it after changing the backend, or do not blame "the interface is not there".

#### What was explicitly not done

- The control buttons are not put directly on the list page of the experiments (you go into the details and operate there: the list page has too many actions and it is easy to click the wrong one).
- `RuntimeHold.Kind.of` still hardcodes the three strings of the workflow level `paused_fault`/`paused_unknown`/`waiting_human`
  (it was not changed to refer to `PauseManager.Kind`), it does not block anything, it is left as a P2.
- The context warnings of the static `message`/`Modal.confirm` of antd are an **existing pattern of the whole site** (the paused queue / the run details use them as well),
  and the scope of this round is not widened to change them to `App.useApp()`.

### M9.5-S1 (the canvas is compiled into the database when it is saved: the process becomes the single source of truth, `86ef1c1`)

**The background**: the canvas `experiment_graph` used to be read and written only by the canvas itself, and the executor never read it ——
"the canvas was lying" (you change the order and run it, and what runs is still the order of the workflow table).

**The approach**:
- the saving of the canvas is brought to a **single entry point + a single transaction**: in the same transaction it writes `experiment_graph`, the compiled
  `experiment_process` and `experiment.version+1`; an illegal graph (a non linear main chain / several entry points / a dangling workflow)
  **explicitly refuses to save**, neither side is written to the database any more, and it does not "silently land something wrong".
- The compilation rules: start → the chain of the workflows → end; the non workflow nodes (the variables / the forks) are rejected or ignored according to the S1 rule,
  and the error message says clearly "only a linear main chain is supported at the moment".
- The workflow table of the details page of the experiment is demoted to a **read-only mirror** (it says "change it in the canvas orchestration", and it is recompiled automatically afterwards);
  the table that was there before could still click "add a workflow / move it up / move it down", that was a second write entry point, and it was deleted.

**The verification**: `ExperimentGraphCompileSelfTest` (pure logic: a linear main chain / an empty experiment / an illegal graph, case by case),
`ExperimentGraphPersistenceSelfTest` (with the database: the save succeeds and the three tables are updated together; a constructed save failure → the three tables stay as they were,
which proves the rollback of the transaction); the threshold of the gate was raised accordingly.

### M9.5-S2-A (the partial order between the workflows: the edge table + the Kahn ordering, only a partial order is promised, `ae06025`)

**The background**: `experiment_process.seq` can only express a linear order, and the canvas can already draw a fork / a parallel;
after S1 the `experiment_process` became the single source of truth, but "what the structure between the workflows actually is" could still only be guessed from the seq.
The counterexample is right next to it: the field `experiment_process.run_after` exists and the API also returns it, but the executor never read it.

**The approach**:
- The new table `experiment_process_edge` (`tenant_id, experiment_id, from_id, to_id`, PK `(from_id,to_id)`),
  it is replaced as a whole when the canvas is saved (deleting a process cascades to the edges), and the dependencies of the workflows on the canvas are compiled into the edges.
- The executor changes to the Kahn topological ordering: an in-degree of 0 goes into the ready set, on the same level it is ordered stably by `seq`; **a cycle is rejected outright**
  ("the experiment orchestration contains a cycle, the execution order cannot be determined"), it does not guess. Data without edges is ordered by ascending `seq`, exactly as before the change.
- **The wording fixes S2-A**: only a partial order is promised, physically serial (one is taken from the ready set at a time), wall-clock concurrency is not promised;
  real concurrency (S2-B) has to connect the three resource layers + the wait-for graph first, otherwise the premise of "the device lock is already enough" does not hold (review B1).

**The verification**: `ExperimentOrderSelfTest` (pure logic: no edges = the seq order, a linear chain, a diamond on the same level by seq, a cycle is rejected);
`ExperimentGraphPersistenceSelfTest` gets the assertion "after the canvas is saved the edges agree with the graph".
**The side effect**: if the workflow graph of an existing experiment had already drawn a fork, it is now refused when it is saved —— that is deliberate (if the executor cannot run it, do not store it).

### The prerequisite of M9.5-S3 (`runtime_hold` the scoped hold + the experiment level pause / stop / resume, `ce3eaf0`/`4458c88`)

**Why it is done first**: after the execution semantics (S2-A) are stable, the next step is the batch; but the batch requires that "every batch can be run / stopped on its own",
so the **experiment level** pause / stop / resume is built first, otherwise the batch can only rely on killing the process.

**The approach**:
- The table `runtime_hold`: `scope(site/island/experiment) + scope_id + kind + reason + released_at`;
  a pause = raising a hold gate (**not** just changing the status of some run), and the `queued/running` runs inside the coverage fall to paused;
  a hold **does not release the plate position** (in contrast to the workflow level `PauseManager` "release what is occupied but unused").
- The executor gets an **action boundary checkpoint**: before every step it looks at stop_requested → aborted; it looks at a covering hold → paused.
  An action that is being sent is not killed (the robot arm is holding a plate and it cannot let go).
- `RunControl`: the stop (while it is running it only sets `stop_requested`, and when it is not running it goes straight to aborted), the resume (it releases the innermost hold,
  the run goes back to queued and it continues from the `seq:loop_index` breakpoint; **a site level hold is not released by a single experiment**).
- The interfaces: `GET /api/runtime/holds`, `POST /api/experiments/runs/{id}/(pause|stop|resume)`.
- The self-test found and fixed **BUG-20261103-10** (`9c1f65d`): `ExperimentExecutor` writes the `experiment_run.id`
  into `experiment.run_id`, while the foreign key of the latter points at `run(id)` —— two id spaces, and they are bound to collide with 23503 sooner or later.

**The verification**: `RuntimeHoldSelfTest` / `RuntimeHoldDbSelfTest` / `RunControlSelfTest` /
`RunControlDbSelfTest` (the threshold of the pure logic of the gate 413→423); the frontend entry point and the integration on the site page are in the record of the last round.

### M9.5-S3 (the batched execution: two layers of batch tables + serial per batch + the re-run of a failed batch, `bbcc5bc` / the frontend `fd59d90`)

**What it has to solve**: one experiment has 96 samples, the device only handles 24 at a time → it has to be split into 4 batches; every batch can be run on its own,
a failure only re-runs that batch, and **the re-run must not overwrite the history** (the explicit requirement of the review 20261103-03 · B2).

**The decisions when the work started (the implementation checklist §8, proposed by the implementer)**: D1 the switch `experiment.batch_split` (NULL = the behaviour is completely unchanged); D2 at the first execution the batches are cut by `experiment_sample.sample_id` in ascending order and the `[{sampleId,barcode}]` is snapshotted into
`experiment_batch.samples`, and the re-run only uses the snapshot; D3 one execution = all the batches that have not succeeded yet are run serially by the batch number, one
`experiment_run` + one attempt per batch, and a failed batch **continues** with the later batches; D4 the attempt of the re-run of a single batch is incremented, and the successful batches are rejected; D5 `batchNo`/`batchSamples` are injected and they do not overwrite the variables of the user; D6 the status of the experiment is aggregated over the batches
(running/paused/all of them succeeded=finished/any one failed=failed); D7 the batches are serial and the batch boundary looks up the `RuntimeHold`.

**The implementation**:
- `BatchPlanner` (pure logic): it cuts the batches / injects the batch variables / aggregates the status, and it can be unit tested without the database.
- `BatchOrchestrator` (new): it creates the batches idempotently + the snapshot of the samples, `runAll` (serially by batch), `runBatch` (the re-run of a single batch),
  `batchesJson` (the view of the batches), and the self-healing of the read side (the status of a batch is based on the **most recent run**, so changing the status of a run when it is continued is also reflected).
- The new overload `syncExperimentStatus=false` of `ExperimentExecutor`: when it is batched it **does not** write the terminal state of a single run into the table of the experiment,
  otherwise "the 1st batch is finished = the whole order is finished". The existing signature delegates true, and not a single character of the behaviour changes.
- The APIs: `GET /api/experiments/{id}/batches`, `POST /api/experiments/{id}/batches/{no}/run`,
  and the existing `POST /run` appends `batchSplit` + `batches` when it is batched (the existing fields are kept); a batched `async=1` is an explicit 400
  (the scheduler only knows one run for the whole order, it must not silently run the wrong thing).
- The frontend: a "batches" card on the details page of the experiment (the whole block is not rendered when `batch_split` is null), a "re-run that batch" on the failed / paused / terminated batches,
  and the expansion of a row shows every attempt and the run that belongs to it.

**The verification** (everything was really run on this machine):

| Item | The result |
|---|---|
| The pure logic | **458 / 0** (the new `BatchPlannerSelfTest` with 35 cases) |
| The full run with the database | **631 / 0** (the new `BatchDbSelfTest` with 96 cases; a fake execution body is injected, **it does not really touch the device**, negative synthetic ids + a finally cleanup) |
| The integration script | `scripts/verify-exp-batch.py` **24/24**: 3 samples / `batch_split=2` really created on the database → the real API runs 2 batches (a failure continues) → the snapshot / the attempt are checked → the re-run of the failed batch has attempt=2 and the existing history is kept → a successful batch is a 400 → Playwright asserts the batch card / the number of attempts / the expanded history; the data is cleaned up in the finally, 0 leftovers |
| The gate | `ci-gate.sh --e2e` passed: the pure logic 458/0, tsc 0, the creation artefacts the same source, the e2e 16/0 |
| curl | The three interfaces with 24 assertions (the batched execution / the view of the batches / 0 attempts during a hold / a successful batch is rejected / the NULL regression) |

**The problem of the wording that was caught during the integration**: the top level `status` of a batched `POST /run` is the **aggregated status of the experiment** (all of them succeeded = `finished`),
and the frontend `runExp` used to only accept `completed`, so a complete success popped up a warning —— it was fixed (**BUG-20261103-13**, `fd59d90`).

**What is left over (written into the implementation checklist §9, not done this round)**:
1. The continuation from a breakpoint inside a batch does not write back to `experiment_batch_run` / the table of the batches (the self-healing of the read side is the fallback; after a successful continuation, the re-run of the whole batch carries the risk of a physical double processing) → S3.1 is recommended;
2. A concurrent re-run of the same batch can hit `UNIQUE(batch_id, attempt_no)`;
3. `batch_split` has no configuration entry point (for now it can only be set through the DB / the seed, and changing it does not re-cut the batches that already exist);
4. A batched experiment with `async=1` is explicitly rejected, running the batches asynchronously requires the scheduler to be changed;
5. A failure continues with the later batches, which is the wording of D3, so the reporting / the statistics have to look at the batches.

### M9.5-S3.1 (the write-back of the continuation + the configuration entry point of the batching + the concurrency of the re-run, `c915948` / the frontend `5f12696`)

**What it has to solve (the leftovers 1/2/3 of S3)**: after the run inside a batch goes through the recovery of the scheduler or a manual recovery / stop, the batch layer is not updated,
and it **overwrites the aggregated status of the experiment with the status of the single run** (for example: a batch that was paused is resumed and it finishes, the experiment is written as finished directly,
while the other batches are still pending); a concurrent re-run of the same batch can hit the unique key; and `batch_split` can only be changed through the database, it cannot be configured on the page.

**The approach (the decisions D8-D13 of the implementation checklist §10)**:
- **D8 the unified write-back point** `BatchOrchestrator.onRunStatusChanged(runId)`: a run that is not a batch run returns false directly and
  **writes nothing** (the behaviour of the path before the change does not change); a batch run writes the attempt row back in one transaction (the status / the error / the finished_at,
  the terminal time uses COALESCE so it stays idempotent) and recomputes the status of the batch according to the wording of the self-healing of the read side, and then aggregates the status of the experiment according to D6.
- **D9 the scheduler**: `executeOne` first probes whether the run is a batch run → a batch run is run with `syncExperimentStatus=false`,
  and after it has finished D8 is called; after the fallback of the exception `markStatus(failed)` D8 is called again. A failure of D8 itself is only logged —— it must not bubble up to the outer catch
  and falsely mark a run that has just finished as failed.
- **D10 the run control**: `RunControl.stop/resume` probes the batch affiliation after it has changed the run, and a batch run skips the single run
  `syncExperimentStatus` and goes to D8 instead (the resume → the attempt / the batch become queued and the experiment shows running according to the aggregation;
  the stop when it is not running → the attempt / the batch become aborted and the experiment aggregates to failed).
- **D11 the configuration entry point**: `PUT /api/experiments/{id}/batch-split` (>0 or null, 0 counts as off; changing it when there are already batches
  is rejected —— the snapshot of D2 is fixed; an illegal value is a 400) + a "batch settings" dialog on the action row of the details page of the experiment (it fills in the current value and it explains the rule of the fixing).
- **D12 the concurrency**: the re-run of the same batch locks the row of the batch with `SELECT … FOR UPDATE` in the same transaction before it computes the attempt_no and does the INSERT,
  so the row lock serialises it and it no longer hits `UNIQUE(batch_id, attempt_no)`.
- **D13** `BatchPlanner.normalizeSplit`: null/<=0 -> null, >0 keeps the value, so that the configuration entry point and the read switch have the same wording.

**The verification (everything was really run)**:

| Item | The result |
|---|---|
| The pure logic | **462 / 0** (`BatchPlannerSelfTest` 39, +4) |
| The full run with the database | **694 / 0** (`BatchDbSelfTest` 155, +59: the write-back of D8 / the idempotency / no change for a non batch run, the write-back of resume / stop, the configuration API, the row lock of the concurrent run of the same batch; negative synthetic ids + a finally cleanup) |
| The integration script | `scripts/verify-exp-batch.py` **35/35**: the configuration API of batch-split (an illegal value / setting it / the rejection when a batch already exists), the "batch settings" entry point of the page and the filling in, the write-back of the attempt state after the run inside a batch was paused and resumed and it **did not add an attempt**, the update of the batch / the experiment according to the aggregation; the data is cleaned up in the finally, 0 leftovers |
| The gate | `ci-gate.sh --e2e` passed: the pure logic **462/0** (the threshold is synchronised), tsc 0, the creation artefacts the same source, the e2e 16/0 |
| The real API + the scheduler | the resume of a batch run → the attempt / the batch become queued immediately and the experiment is running; after the fallback of the scheduler → the attempt / the batch are failed and the experiment is failed; the stop when it is not running → aborted / failed (a synthetic experiment, it does not touch the device, it has been cleaned up) |

**What is left over (written into the implementation checklist §9/§10.5)**:
1. The transaction of the attempt / the batch of D8 and the aggregation of the experiment are two writes (they are not in the same transaction), and an extreme failure is corrected by the self-healing of the read side;
2. A batched `async=1` is still an explicit 400 —— running the batches asynchronously requires the scheduler to be changed to dispatch per batch (a bigger change);
3. The success path of D9 (a batch run really runs the workflow up to completed) depends on a real device, and this round only verified the exception path with an "experiment without a workflow",
   and at the review it is recommended to demonstrate the success path by hand following §10.4-1.

### The observability of M9 (the scheduling status gets the detail of the queue + the estimated wait in the list of the experiments + the page of the scheduler, `f7120a2` / the frontend `f0de15c`)

**What it has to solve**: two items are still left in the ROADMAP M9 —— "the estimated wait while contending for a device is written back into the list of the experiments" and "the `scheduler/status` gets a page".
Before, `/api/scheduler/status` only had a few counters, and the operator could not see "who holds the island, which position am I in the queue";
and the list of the experiments could not show "how long do I have to wait after I click execute", there was only the tail of the log.

**The approach**:
- **The backend**: the new `Snapshot` of `ExperimentScheduler` (one snapshot serves both the status and the eta, so that the two do not get out of step);
  the `queue` carries the `position` in the **actual dispatch order** (EDD: deadline ASC → priority DESC → submittedAt ASC);
  the `islandLocks` get the code of the island / the number of the experiment, and the `queue` gets the number of the experiment, all of them are looked up in a batch with `= ANY(array)` (no N+1);
  the `status()` gets `workers`, `started`=the cumulative number of dispatches (a number) and `loopStarted` (the boolean semantics from before the change is kept for compatibility).
- **SchedulerEta** (pure logic + the estimation): `estimateWait` (running→0, not in the queue→null, the remaining time of the holder + the estimation of the experiments in front of it on the same island,
  a negative number is clamped to 0), `estimateExperimentSec` (the sum of the task nodes `COALESCE(NULLIF(estimate_sec,0),60)`),
  `estimateRunRemainSec`; the new interface `GET /api/experiments/eta?limit=` (100 by default / 500 as the upper limit).
- **The frontend**: an "estimated wait" after the status column of the list of the experiments (running / about N min (the k-th position) + a reason tooltip / —, it refreshes every 5s);
  the new page `/scheduler` (the healthy or blocked alert, the statistic cards, the table of the island locks, the table of the queue, the explanation of the estimation, it refreshes every 3s);
  a "scheduler" is added to the "Run" group of the left hand side; `formatWait/formatTime` are extracted into a common utils (the list and the scheduler page have the same wording).

**The three wordings that were fixed during the integration** (the document and the implementation are synchronised):
1. **The position in the queue has to be in the same order as the real dispatch**: §7.1 of the design gave the priority the precedence, while the `dispatch()` is actually EDD
   (the deadline has the precedence) —— it was unified to **EDD first**, and `QUEUE_ORDER` + the design document were changed. The position is only used to answer "how many more until it is my turn",
   and if the sorting rule is inconsistent the position is a false number.
2. **The semantics of `started`**: the contract of §7.1 has `started`=the cumulative number of dispatches (a number); the boolean from before the change "has the scheduling loop started"
   was moved to `loopStarted` and kept for compatibility; the `dispatched` alias that the backend had added temporarily was deleted (two names for one set of fields are bound to drift).
3. **The judgement of "running"**: it is now based on **the holder of the island lock of the scheduler** + `experiment.status` as the fallback, and it **does not look at**
   `experiment_run.status` —— during the integration it turned out that EXP0812 has two leftover running runs from 11-01, and that would make the ETA judge an experiment that ended long ago
   as "running" (BUG-20261103-14). The stale data itself has not been cleaned up, it is handled after it has been confirmed.

**The verification (everything was really run)**:

| Item | The result |
|---|---|
| The pure logic | **479 / 0** (`SchedulerEtaSelfTest` with 17 cases) |
| The full run with the database | **711 / 0** |
| The gate | `ci-gate.sh --e2e` passed: the pure logic 479/0 (the threshold is synchronised), tsc 0, the creation artefacts the same source, the e2e **20/0** (the threshold 16→20) |
| The e2e | The 4 new ones: the "estimated wait" column of the list of the experiments + the statistic cards / the island locks and the queue table of the scheduler page / the healthy or blocked conclusion |
| curl | An empty queue of `/api/scheduler/status` is legal (started:0 / loopStarted:true / workers 4/0/0); `/api/experiments/eta` 100 entries ~110ms, 500 entries ~122ms, the upper limit of limit is 500; EXP0812 is no longer misjudged as running |
| The pages | `/scheduler` and the list of the experiments were actually opened and confirmed with a screenshot |

**What is left over / the explanations**:
- `estimateSec` is only returned for running / queued (it is null for the rest); the remaining time of the holder only counts the **run_node that has already been expanded**
  (right at the start it can be too small, it does not contain the later workflows) —— it belongs to the formula of §7.2, and the page says "it is an estimate, do not take it as a promise";
- for an experiment without an island (the fake 0 inside the island) the "same island" of the ETA is grouped by 0, and the reason says "the preceding experiment has not finished yet";
- a **synchronously** executed experiment (one that does not go through the scheduling queue) has no island lock, so the ETA may show that it does not apply;
- the stale running run data of BUG-14 has not been cleaned up (the production data was not touched without being sure, it is waiting for the confirmation of the user).

### The wrap-up of M9 + BUG-14/15 + the batched asynchronous (`3aa4837` / `e378b91`, 2026-11-03 in the evening)

Four to do items are closed together.

#### 1. The acceptance and the wrap-up of M9 (the Gantt gets its tick)

The new `scripts/verify-scheduler-acceptance.py`: it goes through the async scheduling chain **with an empty experiment that does not orchestrate any workflow**,
so the scheduler can be verified (the queueing / the island occupation / the dispatch / the mutual exclusion / the concurrency) without touching a device:
- 3 experiments on the same island: strictly mutually exclusive (a high frequency sampling of the simultaneous occupation is <=1), the dispatch order == the submission order (the completion order is taken from the log of the API process
  `[调度] 实验 <id> 结束/失败`, cross-checked with the position in the queue);
- 3 rounds across the islands: a 20ms sampling, **maxRunning=3 was observed in all 3 rounds** (MAX_CONCURRENT=4), and no island was ever occupied concurrently;
- the Gantt page opens `/schedule` with Playwright to confirm the rendering.

Measured **8/8**. The "the scheduling Gantt" of the ROADMAP M9 gets its tick and the acceptance conclusion is written —— **all 4 items of the M9 scope are done**
(the Gantt / EDD + the queue-jumping / the write-back of the estimated wait / the page of the scheduler).
(The explanation: an empty experiment finishes too fast and a pure polling misses the frames; so the dispatch order is judged by the completion order of the log and the concurrency by several rounds of high frequency sampling, and the result is recorded as it is.)

#### 2. BUG-14: the cleanup of the stale running data + the settle of the start-up fallback

- The new settle of the orphan `experiment_run` of `StartupRecovery`: at the start-up the leftover `status='running'` falls to failed
  (the finished_at / the error say "the API restarted: the leftover running was settled by the start-up fallback"), a batch run goes through D8
  (the attempt / the batch / the D6 aggregation), a non batch run goes through `syncExperimentStatus`; the wording of the single instance deployment is written into the comment
  (in the future a multi instance deployment has to change the owner / the heartbeat, it must not settle everything).
- Measured at the first restart: the log `[启动收尾] 孤儿 running settle：2 条` (the run#2/#3 of EXP0812 → failed),
  the experiment 814=failed, running=0 in the whole database, and the ETA does not misjudge any more; **a second restart gives 0** (it is idempotent).
- The self-test `StartupSettleSelfTest` with 26 cases (negative synthetic ids + a finally cleanup, it does not consume the real leftovers early).

#### 3. BUG-15: the processes of the test scripts leak (150 orphans)

While cleaning the environment, **150** orphans of the simulator / the sidecar were found (PPID=1, the oldest at 19:02). The root cause:
- `test-sidecar.sh` starts with `( cd … && java … ) &` —— `$!` is the sub shell, and killing the sub shell does not kill the java;
- `dotnet run` starts another sub process (the real sidecar), and after dotnet is killed the sub process stays alive;
- the sidecar of `verify-device-chain.sh` has the same problem.

The fix (`e378b91`): the real process is always started with `exec`; the sidecar becomes `exec dotnet <Labos.Sidecar.dll>`
(the host executes it directly, it does not depend on the apphost / DOTNET_ROOT and there is no sub process); the cleanup gets a `pkill -P` as a fallback.
**Measured: the number of processes before and after running test-all (including SidecarChainTest 7/0) does not change (150→150)** —— the fix works;
after that they were cleaned up with TERM→KILL from 150 to 0, and in 12s of observation they did not come back (the list is archived in `/tmp/leaked-device-procs-20261103.txt`).

**The cleanup of the worktree**: `feat-auto-20261103-acb9a80e` had 17 tracked modifications + 108 untracked files (the WIP of another
auto task). First it was **archived into a commit on that branch** (`90f0170`, with a format-patch backup), and then the worktree was removed
(the `.worktrees` directory was emptied); the branch and the archive commit are kept, and the main working area is not affected.

#### 4. S3.2 the batched asynchronous (the scheduler continues the queue per batch, `3aa4837`)

- `BatchOrchestrator.createNextBatchAttempt` (the row lock of the experiment serialises it, EXISTING is idempotent, the batch boundary is HELD),
  `batchContextFor` (the reverse lookup of the batch variables of D5), D8 gets attempt.started_at;
- `ExperimentScheduler.executeOne` injects the batch variables into a batch run, and after D8 it has run it continues with the next batch according to D3
  (a failure continues / an aborted stops / a hold stops at the boundary), and `planNextBatch` is extracted so that it can be tested in the package;
- `ApiServer`: `async=1` with a non empty batch_split **is no longer a 400** (the four kinds of 200 response CREATED / EXISTING / HELD / NO_MORE;
  the non batched path is unchanged).

Measured with curl (an empty experiment): 2 batches in series with attempt=1 each, the experiment aggregates to failed and there is no 400 all the way through; after an aborted the batch2 stays
pending and there is no new run / attempt; during a hold the POST returns held:true and no attempt is created; the shape of the response of a non batched async does not change.
What is left over (written into the implementation checklist §11.3): after the hold is released the queue is not continued automatically, a restart does not rehydrate the queue, the contract of `experimentRunId=null` in HELD / NO_MORE, the async overrides are ignored, and the wording of the status derived from a paused batch.

#### The summary of the verification (everything was really run)

| Item | The result |
|---|---|
| The pure logic | **479 / 0** |
| The full run with the database | **784 / 0** (711 + 73: StartupSettle 26 + BatchDb 155→202 with the 47) |
| The gate | `ci-gate.sh --e2e` passed: the pure logic 479/0, tsc 0, the creation artefacts the same source, the e2e 20/0 |
| The acceptance of M9 | `scripts/verify-scheduler-acceptance.py` **8/8** |
| The environment | The orphans of the simulator / the sidecar **150 → 0** (no resurrection in 12s); the worktree is removed, the branch + the archive commit are kept |
| The leftovers in the DB | running=0, the two batch tables 0, the experiments 678, experiment_run 39 |

### The wrap-up of the leftovers of S3.2 (the rehydrate at the restart / the automatic continuation of the queue after the hold is released / the async vars / the derivation of a paused batch, `9bd83e5` / the integration script `ed612f3`)

**The background**: when the batched asynchronous of S3.2 went live it left 5 semantic leftovers (the implementation checklist §11.3). This round they are closed one by one, and a re-runnable integration script is added.

#### 1. The rehydrate of the queue of the pending dispatches at the restart (§11.3-2)

The queue of the scheduler is an in-memory state: after the process restarts the runs that still have `status='queued'` in the database (an async whole order / the next batch that the asynchronous batching has already created)
are not enqueued by anybody and **are never executed**, and the page keeps saying "queued".
- `ExperimentScheduler.rehydrateQueued()`: at the start-up it looks up the queued runs (it joins the experiment to get the island / the priority / the deadline),
  it skips the ones that are already in the in-memory queue, it puts the rest back into the queue and returns the number; it is **idempotent** (it is only called at the start-up, not while it is running).
- The start-up wrap-up of the ApiServer prints `[启动收尾] 待派发 rehydrate：N 条`; a failure is only logged and it does not block the start-up.
- Measured: 1 queued run is created and the machine is restarted → `rehydrate：1 条` and that run is dispatched to a terminal state; a second restart → `0 条` (it is idempotent).

#### 2. The automatic continuation of the queue after the hold is released (§11.3-1)

When the batch boundary hits a hold the candidate batch is marked paused and no attempt is created (D7), and the wording from before the change is that a person has to release the hold and then manually POST async for it to continue.
- The scheduling loop calls `autoContinueHeldBatches()` every 5 ticks (about 10s): as long as any hold is in effect nothing is touched; it scans
  the batches that are "paused and have **no attempt at all**" and whose experiment is not in a terminal state, and then it judges the coveringFor one by one (the three scopes),
  it calls `createNextBatchAttempt` → only when it is CREATED does it call `resumeRun` to enqueue it (at most 5 per tick, a failure of a single one is only logged).
- The log: `[调度] 扣留解除，自动续跑实验 X 第 N 批（run#M）`.
- Measured: an experiment level hold is created → the async returns `held:true` and 0 attempts → the hold is deleted → after about 10s the queue continues automatically and the 2 batches run through.

#### 3. The persistence of the variable overrides of the async (§11.3-4)

- `ExperimentScheduler.submit(exp, vars)` / `BatchOrchestrator.createNextBatchAttempt(..., vars)` write the vars into
  `experiment_run.variables`; when the scheduler executes a queued run it reads them out as the overrides of the user for the executor
  (a non batched one keeps sync=true, a batched one merges the batch variables of D5); the continuation of the queue uses the user variables of the previous run.
- **The batchNo/batchSamples are stripped out**: after the executor has run it writes the merged variables back into run.variables, and if they were passed on to the next batch as they are,
  the batch context of the previous batch would override the next one with the identity of "the variables that the user set explicitly" (the user has the precedence in D5) —— so the continuation of the queue only inherits the variables that the user set explicitly.
- Measured: the async submits `{"k":"v","dose":"12"}` → `experiment_run.variables` lands `{"dose":"12","k":"v"}`.

#### 4. The wording of the status derived from a paused batch (§11.3-5)

The derivation rule of `loadBatches` was changed to: **any completed → completed**; otherwise, if the status of the run of the most recent attempt is
running / queued → that status; otherwise `storedStatus='paused'` → paused; otherwise the status of the most recent attempt.
The default semantics: when a batch is stuck at the boundary / waiting for a person, it must not be covered up as a "failure" by the **failed attempt of the previous time**; the D6 aggregation shows paused accordingly.

#### The verification (everything was really run)

| Item | The result |
|---|---|
| The full run with the database | **840 / 0** (784 + 56: BatchDb 202→244, the new SchedulerRehydrateSelfTest 14) |
| The gate | `ci-gate.sh --e2e` passed: the pure logic 479/0, tsc 0, the creation artefacts the same source, the e2e 20/0 |
| The integration script | `scripts/verify-s3-2-residuals.py` **12/12** (the async vars land in the database; the automatic continuation of the queue runs the 2 batches; the rehydrate at the restart is ≥1 and it is dispatched to a terminal state; a second restart gives 0, it is idempotent) |
| The environment | The data that the script created is cleaned up; the running / queued / holds / batches of the DB are all 0; the API is healthy after the restart |

#### The semantic boundaries (recorded in the implementation checklist §11.3, nothing new is opened)
1. The variables that the continuation of the queue inherits **have the batch context stripped** (batchNo / batchSamples), for the reason above;
2. When the scheduler executes a queued run it reads the run.variables as the overrides (**the semantics of a snapshot**): a restart / a recovery uses the variables of the submission,
   and it does not fetch the current default values of the experiment again (if the review asks for "the latest default values are fetched again when it is recovered", it has to be discussed);
3. `loadBatches` only changes the derivation on the read side; the write side `derivedBatchStatus` (D8) still lands the status of the most recent run in the table,
   and a paused batch that has a failed attempt is changed by D8, which changes the existing value in the table —— the candidates of the automatic continuation of the queue are limited to the ones with "no attempt", so it is not normally triggered, and it is recorded as a known boundary.

#### What is still left (§11.3-3)

The contract of `experimentRunId=null` in the `HELD` / `NO_MORE` responses has been fixed (§11.1), but the frontend currently only uses the synchronous /run,
so **there is no UI entry point for the async batching** —— whether to add a page entry point has to be decided by the product / the review.

### The frontend entry point of the "asynchronous execution" (the wrap-up of S3.2 §11.3-3)

**What it has to solve**: the backend of the batched asynchronous already supports it (`POST /run?async=1`), but the page only has the **synchronous** "run the experiment" (it blocks until all the batches have run),
so the page hangs during a long experiment / while it is queued; and the three kinds of 200 response of the backend (already queued / on-site hold `held` / no batch is waiting) have no counterpart in the interface.

**The approach**:
- `client.ts` gets the new union type `ExperimentAsyncRunResult` (the three shapes are distinguished by `experimentRunId/held`) and
  `api.runExperimentAsync()`.
- The details page of the experiment: the action row of a batched experiment gets an "execute asynchronously" button (it is only visible when `batchSplit != null`; it is disabled when there is no orchestrated workflow, the same wording as the "run the experiment").
  The three responses correspond to three hints, and **they must not be mixed up**:
  - already queued → "already queued: the N-th batch (run#), the scheduler will continue automatically batch by batch";
  - `held` → "there is an on-site hold, the N-th batch was not started: <the reason>" (**no attempt was created, so it must not say it is queued**, otherwise the user thinks the system will run it by itself);
  - `NO_MORE` → "there is no batch waiting to be executed (the status of the experiment: …)" (it must not be treated as a failure).
  After the click the batches / the execution history are refreshed at the same time, so that the queued state becomes visible as fast as possible (the scheduler dispatches every 2s).

**The verification**:
- `npx tsc --noEmit` 0 errors; `npm run build` passes;
- `scripts/verify-s3-2-residuals.py` gets a 4th UI case (a blank workflow makes the button available, it does not touch the device):
  the entry point exists and is available → clicking it during a hold pops up the "on-site hold" and 0 attempts → the hold is deleted and the 2 batches continue automatically → clicking it again gives a clear conclusion;
  measured **18/18** (including the three sections of the async vars / the automatic continuation of the queue / the rehydrate at the restart), the data is cleaned up.

**The boundary**: the asynchronous execution and the synchronous execution coexist: the synchronous one is good for seeing the result on the spot (it blocks), the asynchronous one is good for a long experiment / for the queueing;
both of them go through the same set of batch semantics (a failure continues / an aborted stops / a hold stops at the boundary).

### P2 the mapping of the status is brought to one place (the authoritative vocabulary of the backend + the de-duplication in the 8 frontend pages, `81f14c3` / `cb305be`)

**What it has to solve**: the status → the wording / the colour was written once in each of the 8 frontend pages, and there is another one in the backend `syncExperimentStatus`
for the run → experiment mapping; the same status can have a different wording / colour on different pages, and the drift between the vocabulary of the run and of the experiment is exactly
the root of BUG-13/14 (problems like "the list shows it is finished, the details show it failed" keep popping up).

**The approach (the backend is the authority)**:
- The new `StatusVocabulary` (pure data + pure functions, it does not touch the database): the code / label / colour / terminal / active of the five scopes
  run 11 / node 9 / step 6 / experiment 12 / batch 7; pauseKinds 3, holdKinds 4, holdScopes 3;
  and the 8 mappings of runToExperiment (completed→finished …).
- `GET /api/meta/status-vocabulary` (Bearer authentication) outputs the contract JSON, and the frontend consumes the same one.
- The hardcoded CASE of `ExperimentExecutor.syncExperimentStatus` is replaced by the generated `runToExperimentCaseSql()`:
  **the mapping that writes and the mapping that displays have the same source**, and from now on the wording is only changed in this one place.
- The frontend `utils/status.ts`: a built-in FALLBACK (field by field identical to the contract, so the first screen / an unavailable interface does not flash a code) +
  a merge of what react-query fetches; it provides statusLabel/statusColor/statusHex/pauseKindMeta/holdKindMeta/
  holdScopeLabel/runToExperimentStatus; the 10 local mappings of the 8 pages are deleted.

**The display differences that were converged according to the contract after the unification (the places where the pages disagreed with each other)**:

| The location | Before | The new |
|---|---|---|
| RunDetail, the node bypassed | not selected | the branch was not selected |
| ExperimentList, paused | paused | already paused |
| Gantt / Runs, skipped | grey | gold |
| Designer, paused / aborted | gold / blue | orange / red |
| The "pause reason" Tag of the details of the experiment | a fixed colour | by the type (a device fault red / waiting for a person orange / the plate position unknown volcano) |

**The verification (everything was really run)**:

| Item | The result |
|---|---|
| The full run with the database | 840 → **860 / 0** (+20: StatusVocabularySelfTest) |
| The pure logic | 479 → **499 / 0** (the threshold of the ci-gate is synchronised 479→499) |
| The gate | `ci-gate.sh --e2e` passed: 499/0, tsc 0, the creation artefacts the same source, the e2e 20/0 |
| The interfaces | 401 without a token / 200 with a token (5042B); the 45 states + 3 pauseKind + 4 holdKind + 3 holdScope + 8 transitions are deep-equal to the contract |
| The pages | The list of the experiments / the details of the execution / the live board / the Gantt / the details of the experiment were opened and confirmed with a screenshot; the vocabulary interface was measured with a 200 in the network of the page (the data source has been switched to the backend) |

**The boundaries**:
- the vocabulary interface has `staleTime: Infinity`: it is not fetched again during the session, and a change of the `version` only takes effect after the page is refreshed (this avoids every page polling the metadata);
- the tables of the execution history / the steps / the batches still display the raw status code (the same as before the change); this round only converged the "wording / the colour mapping",
  and it did not change the shape of the display of these tables —— if they are to be unified into the Chinese labels it can be done in the next round;
- the FALLBACK is field by field identical to the contract of the backend: it can still be rendered when the interface is down / when nobody is logged in; an unknown code falls back to label=code and the default colour, and it does not throw.

### The three wrap-up items are done together (the Chinese labels of the status column / the ApiServer is split into controllers / the M10 checklist for the onboarding of the manuals, `731c908` / `deb6aed`)

#### ① The status column of the execution history / the attempts / the batches / the board is unified into the Chinese labels (`731c908`)

The last step of the convergence of the P2 status vocabulary: the tables still had the raw status code (failed / running …).
- The status column of the batch attempt history of Experiments.tsx (the run scope), of the table of the batches (the batch scope) and of the execution history (the run scope):
  `{a.status} / {r.status} / {v}` → `statusLabel(scope, code, vocab)`, and the colour still uses `statusColor(scope, …)`;
- the status column of the current node of the live board of Runs.tsx is changed to Chinese as well (it stays — when there is no current node);
- the two UI assertions of `scripts/verify-exp-control.py` were changed to "a Chinese label **or** the raw code" (compatible with both kinds of rendering).

#### ② The ApiServer is split into controllers (`deb6aed`)

The last item of the ROADMAP P2. The ApiServer used to be 3331 lines with one gigantic route() if/else.
- It was split into 8 domain controllers in the same package: AuthApi 127 / DeviceApi 478 / SchedulerApi 164 / RunApi 509 /
  WorkflowApi 909 / OpsApi 553 / ExperimentApi 638 / MetaApi 109 lines; the ApiServer went from **3331 → 679 lines**
  (only the bootstrap / the CORS / the authentication / the exceptions / the dispatch + the shared helpers are left, and the helper changed from private to visible for the package);
- the order of the dispatch = the order in which the routes of the domains first appear in the pre-split route() (Auth→Device→Scheduler→Run→Workflow→Ops→Experiment→Meta),
  and inside the controller the pre-split if/else and the relative order of the Matchers are strictly kept (it is written into the javadoc of every controller);
- **the evidence**: the grep snapshot before the split shows that all 58 compilation points of the Pattern have an owner (the business route blocks 156/156, the ones that were not moved =0;
  the Pattern literals 58→58, the equals/startsWith 54→54 are completely identical); the version before the split was compiled separately and started on 18081,
  and it was compared route by route with the one after the split on 18080 with the same database and the same data: **34/34 GET responses have an identical status code + JSON**,
  and the 401×4 / 405 / OPTIONS 204 / the structure of the login are identical as well;
- the regression: javac 0 errors, the full run with the database **860/0**, the e2e **20/0**, verify-exp-batch **35/0**,
  verify-exp-control **22/0** (after the script became compatible with the Chinese labels), the acceptance of the M9 scheduling **8/8**, the leftovers of S3.2 **18/18**;
  after the restart of the API (PID 37673) /health is normal.

#### ③ The precondition of M10: the checklist for the onboarding of the manual → the model library (a document)

The new `docs/设备/M10-手册到型号库接入准备清单-v0.1.md`:
- an inventory of the current state: the four layer structure of the model library, the MANUAL that already exist (aspin-2k / s100), the three PLANNED ones
  (**360R = Hettich Rotanta 460R; PlateLoc = Agilent PlateLoc**; plus the Eppendorf 5810R);
  **no device of the current production database is bound to a model** (without a manual it is not bound blindly, to avoid the error codes getting the wrong meaning); the error code blocks 0x40/0x41 are taken, 360R=0x42 and PlateLoc=0x43 are recommended;
- a list of 18 documents (10 general + 4 for the 360R + 4 for the PlateLoc, all of them requested as a "communication interface specification / an error code table / the units of the parameters", not as an operation manual);
- a template for the parameter extraction with 7 tables (A the master of the model / B the mapping of the methods / C the error codes / D the type and the parameter boundaries / E the behaviour of the model / F the on-site measurement / G the binding and the acceptance);
- the onboarding process with 10 steps of the DoD (including a first draft of the "busy / dead" judgement of the timeout of a long task and the flow of the error code coming back);
- the gaps G1–G6 of the model library (the hardcoding / the state of the documents / the choice of the code block / the field of the timeout of a long task / the device→model binding / the regression samples of the real machine codes);
- the risks and what is not done + the 8 questions to be confirmed by the user / the vendor + the follow-up table of the documents of the 360R / the PlateLoc.
At the same time two stale states of the ROADMAP were corrected: the P1 "the bulk ignore supports the filters" is actually done (`835aa43` / `73dc4f4`, the paused queue page can bulk ignore by type + time range);
and the first item of the M10 scope got the link to the checklist.

**The summary of the verification**

| Item | The result |
|---|---|
| The full run with the database | **860 / 0** |
| The pure logic | **499 / 0** (the threshold stays at 499) |
| The gate | `ci-gate.sh --e2e` passed (499/0, tsc 0, the creation artefacts the same source, the e2e 20/0) |
| The script regression | the acceptance of the scheduling 8/8, the leftovers of S3.2 18/18, exp-batch 35/0, exp-control 22/0, the e2e 20/0 |
| The API | 34/34 identical route by route before and after the split; the new PID 37673 /health 200 |
| The pages | The execution history of the details of the experiment shows the Chinese labels and no bare completed; the board page is normal (there was no running row at the time, so the status column had nothing to show) |

### M10 goes first (during the wait for the manual): the model library page + the model library interface + the check script before the go-live (`f2d7e8e` / `a1b21ef` / `4196f1c`)

**The background**: the real blocker of the M10 is the manual of the 360R / the PlateLoc; but "which models can be connected, which ones are only a placeholder and how many units of them are running on the production line" can be made visible and testable first.

**The approach**
- The backend `GET /api/meta/device-models` (`f2d7e8e`): `DeviceModels.Planned` gets `dbModelAlias` (360R / PlateLoc),
  the detail of the manual (the evidence / the manual / the number of the commands including the unproven ones / the number of the error codes / the timeouts) + the detail of the planned ones (what is missing / the identification name / the number of the units running on the production line, one GROUP BY statistic on the device table);
  the pure logic self-test `DeviceModelLibrarySelfTest` with 22 cases.
- The frontend page "model library" `/models` (`a1b21ef`, the operations menu): the statistic cards (2 can be connected / 6 waiting for a manual / 3 running on the production line and waiting to be connected, in red),
  the MANUAL table, the PLANNED table (360R and PlateLoc already have instances but no manual, highlighted in red + waiting for a manual), the explanation of the M10 blocker at the top;
  the e2e gets a 7th step of assertions (20→23).
- The script `scripts/device-go-live-check.py` (`4196f1c`): a PASS/WARN/FAIL per item for the on-site integration / before the go-live
  (the API / the DB, the freshness of the heartbeat and the number of the online drivers, the driver endpoints, the coverage of the model manuals, the rate of the device→model binding, the coverage of the error codes EC-08, the list of the capabilities, the topology),
  with `--strict` a WARN is a failure as well; measured in the simulation environment of this machine **PASS 9 / WARN 2 / FAIL 0** (the WARNs = 0 drivers online and the missing manual of the 360R + the PlateLoc, which is expected before the M10).

**The web development / testing went along**: two of the three blocks of this round are web (the page + the interface) and they went into the e2e; the backend interface, the data of the page and the e2e assertions were integrated in the same round (the measured data of the page agrees with the interface).
The verification: the full run with the database **882/0**, the pure logic **521/0**, the gate passed (the e2e **23/0**, the thresholds synchronised to 521/23).
The documents: the M10 document gets §1.4 of the tools that can already go first, the correction of the PLANNED to 6 entries; the M10 of the ROADMAP gets the link.

### The convergence of the permissions (the whitelist of the roles + the protection of the last administrator + the role awareness of the frontend, `afddf5b` / `3339606` / `19bb67b`)

**The background (the first step of the M11 "the role permissions")**: the system already has a minimal authentication (`sys_user.role` ∈ admin/operator/viewer, the login issues an in-memory token,
the backend middleware says "a write operation needs operator or above, /api/users needs admin"), but there are three gaps:
① the role strings have no whitelist (an account with role=boss can be created); ② there is no "the last administrator" protection (the only admin can be deactivated / downgraded → the system locks itself);
③ the frontend has no role awareness at all (a viewer who clicks a write button only gets a 403 toast and does not know why).

**The approach**
- **The backend** (`afddf5b`): the new pure logic `RbacRules` (the whitelist of the roles / the write permission / the right of the user management / the wording of the roles / `lastAdminGuard`);
  `QueryService.createUser/updateUser` get the role validation (an illegal one → a 400 「the role is not valid: xxx (the options are admin/operator/viewer)」);
  updateUser reads the state of the target on the same connection + `countEnabledAdmins()`, and **the protection of the last administrator is raised before the UPDATE** (it is refused and nothing is written).
  The decision: the currently-unused tables `app_role`/`role_permission` are **not** enabled (that avoids two tracks, `sys_user.role` is used).
- **The frontend** (`3339606`): `AuthProvider/useAuth/useReadOnly` (the session is restored with `/api/auth/me` when the token exists, it listens to the 401 event, logout);
  the Header shows the role Tag + the displayName (the username); the user management menu is only visible for admin, and `/users` shows 「only an administrator can manage the accounts」 for a non admin;
  the read-only experience for a viewer: the write operations of the Experiments / PausedRuns / ExperimentList / ErrorCodes / Policies / Workflows /
  ExperimentCanvas / Designer / RunDetail are all disabled + the native title 「read-only account, the operation cannot be performed」.
- **The tests** (`19bb67b`): `scripts/verify-rbac.py` (20 assertions, the HTTP role matrix + the read-only UI of a viewer, it cleans the database up itself);
  the 1st step of the e2e gets the assertions of the account + the role in the Header (23→24).

**The verification**

| Item | The result |
|---|---|
| The full run with the database | 882 → **907 / 0** (+25: RbacRulesSelfTest 15 + UserRoleDbSelfTest 10) |
| The pure logic | 521 → **536 / 0** (the threshold of the ci-gate is synchronised; MIN_E2E 24) |
| The gate | `ci-gate.sh --e2e` passed (536/0, tsc 0, the creation artefacts the same source, the e2e **24/0**) |
| The RBAC integration | **20/20**: the admin creates an operator / a viewer, an illegal role is a 400; a viewer gets a 200 on a business GET / a 403 on a write / a 403 on the user management; an operator can create an experiment but the user management is a 403 (it cannot escalate the privileges); the Header of a viewer is read-only, the execution of the experiment is disabled and the title contains the read-only, there is no user management menu, the guard of /users; the admin is normal |
| The protection of the last administrator | A real admin that is downgraded / deactivated returns a 400 in both cases 「the last administrator cannot be downgraded / deactivated」, and the DB does not change (the protection works) |
| The pages | Screenshots of both roles confirmed (the role Tag of the Header, the disabled buttons, the difference of the menus) |

**The boundaries / what is still to be decided**
- The strategy of a failed session: `api.me()` **fails in any way** (including a restart of the kernel / a network hiccup) and it clears the token and goes back to the login page; if "a restart of the kernel does not kick anybody out" is wanted, it can be changed so that only a 401 clears the token + a retry hint (it is undecided);
- The write entries that are not covered (a viewer still gets a 403, the backend is safe; the experience of the frontend can be filled in in the next round): the ack / the close / the silence of an alert, the plate inventory (lost / continue the inventory),
  the write operations of the devices and the islands / the island view, the points of the island orchestration, the reordering of the scheduling Gantt, the "record the point / grab the point / delete the node" of the canvas;
- `Users.tsx` still keeps its own wording of the roles (the same value as auth.tsx, it was not forcibly merged);
- The judgement of the protection is the two steps "read the number of the administrators → write the role", and no `FOR UPDATE` row lock was added; the window of operation of a single instance is extremely small and the strong consistency can be added later.

#### The wrap-up of the permissions (`4e3baec` / `ec28d02`): the transaction lock + the retry of a failed session + the completion of the read-only coverage

The last round of the convergence of the permissions left three tails, and they are filled in today:
- **The transaction lock of the protection of the last administrator** (`4e3baec`): before, the two steps "read the number of the administrators → write the role" had no lock, and under extreme concurrency two admins could be downgraded at the same time and both would pass.
  `QueryService.updateUser` was changed to a single transaction: `pg_advisory_xact_lock(hashtext('labos:user-role'))` globally serialises the writes of the user roles
  → the target row is read with `FOR UPDATE` → when the target is an enabled admin, `countEnabledAdmins()` + the `lastAdminGuard` judgement happen on the same connection
  → a refusal rolls back and there is no UPDATE → after a normal UPDATE there is a commit (an exception rolls back explicitly). The lock is at the xact level, it is released automatically on the commit / the rollback, and the fixed key covers the scenario of the crossing of the users.
  Measured: while an external transaction holds the lock the worker blocks and then finishes after 1536ms; a real admin that is downgraded / deactivated still returns a 400 and the DB does not change.
- **The strategy of a failed session** (`ec28d02`): before, any failure of `/api/auth/me` (the network / a 500 / a restart of the kernel) cleared the token and went back to the login page;
  now **only a 401 clears the token**, the other failures keep the token and show a full screen 「the session could not be restored + retry / log in again », so there is neither a white screen nor a kick.
  Measured with a Playwright route mock (me returns a 500): the retry page is stopped → the token is still in localStorage → clicking the retry restores the main interface.
- **The completion of the read-only coverage + the wording of the roles from the same source** (`ec28d02`): the Alerts (save / claim / close), the Plates (report the position / lost), the Topology / the Island
  (add an island / a device, edit and delete, the dragging of the deployment, run a workflow / save a workflow / change the IP / the simulated transport / the dispatch), the IslandFlow (the automatic chaining / the execution / the automatic save / the dragging of the connections),
  the ScheduleGantt (the dragging to reorder is disabled + the hint of the row), the ExperimentCanvas (record / grab the point, delete the node), the Runs (continue from the breakpoint / ignore / switch the attendance),
  the confirmation of the inventory of the PausedRuns; the wording of the roles of `Users.tsx` now uses the same source as auth.tsx (the same as the Header).

**The verification**: the database **907/0**, the pure logic **536/0**, the gate passed (the e2e **24/0**); `verify-rbac.py` **20/20**;
the frontend Playwright self-check **22/22** (the read-only of a viewer on every page, the admin normal, the recovery of the retry of me() 500, the number of the draggable rows of the Gantt = 0).

**What can still be postponed**: the alert page has a silence API in the backend but the UI does not use it (this round only disables the existing actions); changing sys_user directly with psql bypasses the protection of the interface (an operations path, it is outside the expectation).




---

### 2026-11-04 (the A2 document consistency + the handling of the review 20261104-01)

**The handling of the review (`20261104-01`)**: 1 blocking item (the "24/0" of the e2e cannot be reproduced, the root cause = a leftover experiment pushes itself to the first row of the list and there is no execution history to expand) + 1 high severity item (the leftovers of the RBAC integration pollute the database) + 3 P2 (the wording is self-consistent)
All of them were fixed and re-verified. After the cleanup `sys_user`=3, `experiment`=678; `e2e-smoke.py` 24/0, `ci-gate.sh --e2e` passed.
The details are in `docs/评审记录/20261104-01-评审包复核.md` §七 and in `BUG台账.md` (`BUG-20261104-01`~`-05` have been closed).

**The A2 document and ROADMAP consistency closure** (the plan is in `docs/评审包-20261104/02-后续规划.md` A2):
- The historical ticks of the M6 / M7 / M8 of `ROADMAP.md` are checked one by one: the 6 items of the M6 and the 5 items of the M7 are ticked after they were verified against the source / the scripts;
  the 3 items of the M8 are ticked, and **2 items are confirmed as not done** (the coverage baseline, the device chain in the CI) keep the `[ ]`, and the status of the M8 is changed to 「the main part is done」.
- The baseline of §1.1 / §1.2 of `ROADMAP.md` is refreshed: the pure logic 536 / the database 907 / the device chain 41 / the e2e 24;
  the code 36,595 lines / 11 packages, the webapp 12,830 lines / 23 pages; the database 116 tables. §1.3 the boundary of the capability, §4 the thresholds and §5 the DoD are synchronised.
- `02-后续规划.md` marks A2 as done; `评审指引.md` refreshes the number of lines of the development log;
  the stale numbers in the header comment of `ci-gate.sh` (423→536, e2e 14→24) are aligned with the constants in the file.

**The way it was checked**: the self-description of the documents is not taken on trust, instead the source / the scripts are looked at (`the constants of ci-gate.sh`, `ci.yml`, `verify-device-chain.sh`, `Expression.java`,
`RunDetail.tsx`, `pg_rebuild/schema/*`) + the counts of the database with `psql` (`run_node` 120,268, 0 dangling parents).

### 2026-11-05 (Designer closure: delete / device images / robot-arm transport / documented trade-offs)

**Background**: after accepting "island → device → drag a method → set parameters", the user asked five things: whether dropped nodes can be deleted and where the entry point is; whether devices and nodes show real device images instead of one uniform shape; how many devices and robot points an island has, how a plate moves between positions, and whether a robot-arm method is actually called; a demo flow built from a device with an image; and whether the functional / design / test documents were updated with the discussion, the evolution of the approach and its trade-offs. The findings are below, without embellishment.

**What was done**: ① Delete: bpmn-js's native contextPad trash icon and Delete key always worked, but "only after selection + no hint + save again afterwards" made it look impossible; an always-visible "Delete selected node" button was added on the right, calling `modeling.removeElements` and persisting immediately just like a drop, with a guard against deleting every node (`saveGraph` rejects an empty diagram). ② Images: 62 of 76 device models have `device.icon`, but almost all point at the source server `/image/device/<uuid>.png` (404 in a new environment), and the repository had only 2 placeholder SVGs; `scripts/gen-device-icons.py` now generates 15 local drawings for 14 capabilities plus a generic fallback, `/api/devices/pool` returns `icon`, and `iconFor()` maps dead paths back to `/devices/<CAP>.svg`. Nodes use the custom renderer `webapp/src/bpmn/DeviceImageRenderer.ts` (`additionalModules`, priority 2000) to draw "image card + method name"; images scale and export with the SVG. `icon` is stored in `$attrs.icon` and was added to the whitelists in `collapse()`, `BpmnExpander` and the static designer. ③ Robot arm: per §14.2/§14.3 of the orchestration design, dragging methods from two different devices inserts a `Transfer` node for the island's TRANSFER device and connects A → arm → B with `modeling.connect`, with `source/target` set to the two device codes. ④ A demo flow was kept with `--demo` (workflow 1885, island `LAB-DEMO-02`, `ID0000` → `ID1731` → `ID1183`, all three nodes with images). ⑤ Documentation: decision log, development log, D16 ADR, UI / functional / design / test / roadmap / onboarding documents were updated 1:1 in Chinese and English, including "option 2 was rejected", the bpmn palette provider vs React Flow trade-off, and the three implementation pitfalls.

**Verification**: `scripts/e2e-method-drop.py` grew from 10 to **20 / 0** assertions (17 resident without a database), covering device-row thumbnails, local `data-icon` paths, the `<image>` in a node's SVG, `icon` in the XML, automatic Transfer insertion and connections across devices, the Transfer row in `workflow_node`, and canvas/database deletion; `MIN_METHOD_DROP` 9→17. `tsc` reports 0 errors, the English mirror has 0 hard errors, and `ci-gate.sh --all` passes all 18 gates with zero SKIPs.

**Boundary / open items**: ① automatic transport insertion currently exists only in the **designer's graph-building path**; the backend `WorkflowBuilder` / `GraphRunner` / `POST /api/experiments/flow-run` still do not add a transfer at run time (`ExperimentCanvas.runFlow()` is a separate frontend-only path); this is now in the test master table's honest list and needs its own batch. ② `source/target` still use device codes while §15.1 requires `robot_point.code`; taught coordinates are not filled in. ③ Always the island's first TRANSFER device (known §14.7 limitation); A unload / B load actions are still user-configured. ④ The 15 drawings are schematic SVGs, not photographs; real ones can replace them under the same names. ⑤ Multi-step nodes expanded into subProcess fall back to native rendering, so images are temporarily hidden. ⑥ `position_state` / `plate_location_audit` still cover only the UNKNOWN block; there is no combined assertion of "source position → target position + `run_command` containing Transfer". **Addendum (same day): the literal `\uXXXX` mojibake in experiment names is fixed**. Symptom: names such as `verify-\u5e76\u53d1-1` appeared in the `/experiments` list. The root cause was **dirty historical data**: the early kernel's `jsonField` did not decode JSON Unicode escapes, so Chinese text sent by a standard client (Python `json.dumps`, `ensure_ascii=True` by default) was stored as a plain string. Since 2026-11-04 `ApiServer.unescapeJson` decodes it and `JsonFieldSelfTest` covers `\uXXXX`, surrogate-pair emoji and malformed escapes, so **new writes are correct** (sending `\u9a8c\u8bc1` stores the real characters, with no literal escapes). **19 experiment rows and 2 workflow rows** were restored with `scripts/fix-unicode-escapes.sql`: it only matches `\u[0-9a-fA-F]{4}`, skips the surrogate range and control characters, self-checks before and after, and is idempotent; zero rows remained and samples read `并发验证-1`, `probe-中文-A`, `verify-并行分支`. `resource_node` had no such rows. This is a data fix and does not change the API contract.

⑦ A pre-existing e2e weakness surfaced in pre-push: `e2e-smoke.py` assumed the **first row** of the workflow list always has run history, while a newly created workflow (this round's demo flow, or the gate's own `test` workflow) takes that row → the run-history dialog is empty, and because it was left open its mask blocked the next menu click with a 15s timeout. The script now walks the first 12 rows to find one with history and closes an empty dialog explicitly. This is test data-order dependence, not a regression of this round's feature, but new workflows triggered it, so it was fixed in the same batch.

### 2026-11-05 (review-bundle re-review + gate hardening + live page exercise with multiple parallel gateways)

**Background**: after the review bundle `docs/评审包-20261105/` was handed over, it went through an
independent re-review (`docs/评审记录/20261105-01-评审包复核.md`; reviewer MiniMax-M3.1-Flash, a
different model family from the previous round's DeepSeek). The verdict was "conditional pass":
no regression in the product code, with the problems concentrated in **document precision** and
**the strength of the gate mechanism itself**. The user then added: follow the review guidelines,
fix the remaining items, and then really **drag a graph with multiple parallel branches, multiple
gateways and variables on the page**, run it, and put the results into the test documentation.

**What was done**: ① **Traceability per the guidelines**: added
`docs/评审记录/20261105-01-评审包复核.md` with every field the guidelines require (review id / time
/ model / scope / method / conclusion / comment list / per-item response / remediation status); all
six comments get a "conclusion · reason · remediation · verification" entry; the file is
append-only. ② **P0 number fix**: the bundle claimed "14 gates", but this round added sections
4.12 and 4.13, so the real count was 16; the four places in the bundle (Chinese and English),
`开发日志` and `评审包-20261103/04` were all synchronised. ③ **"Thresholds may only rise" turned
into a mechanism**: it used to be only a comment at the top of `ci-gate.sh` plus commit
discipline — lowering `MIN_PURE` went unchallenged and CI stayed green. A new gate section
"0. thresholds-may-only-rise check" now scans every historical version of `ci-gate.sh` in git and
takes the **highest historical value** of each `MIN_*` as the baseline (not the "previous version",
which may itself be the one that was lowered); any value below the baseline turns the gate red and
names the offending key. ④ **SKIP made visible**: the five SKIP branches used to only `echo`
without counting towards `FAIL`, so "did not run" was scored as "green", while the comment in
`.githooks/pre-push` claimed "not silently counted as a pass" — **the comment contradicted the
code**. They now use a `skip()` counter, and the summary prints "gate passed (with N sections
skipped — those did not run, not a pass)"; the comment was rewritten to match reality. ⑤ **Crash
recovery boundaries**: `StartupRecovery.settle` scans for zombies that are `queued`/`pending` with
`created_at` older than 1 hour, and the `paused` site-hold state **must not** be killed by
mistake — both had **zero coverage**. Three assertions were added (paused stays paused / the
zombie is collected as failed / a freshly queued run is not killed), taking the count 31→34.
⑥ **Live page exercise**: added `scripts/e2e-multi-parallel.py`, which really drags 12 nodes /
13 edges on the page (2 parallel gateways P1+P2 nested + 2 exclusive gateways X/X2 + 5
variable/expression nodes), saves them, and then triggers a real run, asserting that all three
parallel branches end `completed` and the whole run ends `completed`; the gate gains section 4.14
with threshold `MIN_MULTIPAR=21`.

**Verification**: `ci-gate.sh --all` passes all **18 gates with zero SKIPs** (including the new
section 0 threshold check and the section 4.14 live page exercise); `verify-en-docs.py` reports
**98** registry entries / **0** hard errors / 437 links all resolving; `e2e-multi-parallel.py`
gives **21/0** across 3 consecutive runs; the `e2e-flow-matrix.py` regression is **45/0**;
`verify-s3-2-residuals.py` is **34/0**; the threshold mechanism was verified in reverse
(lowering `MIN_PURE` to 500 makes the gate report exactly `MIN_PURE(500 < 624)`).

**Boundary / open items**: ① **Four live-page pitfalls** (see
`docs/页面实操验证报告-多并行多网关-20261105.md`): the canvas container is only ~690px wide, so an
out-of-range coordinate **silently** drops the node; clicking the source inside a shape's own box
makes that edge fail to connect (needs save + verify-against-the-database, then retry); a plain
`task` is a device task and turns `manual` when no device is found (verifying orchestration
semantics requires built-in nodes); and `endEvent` is `Kind.END`, which **never enters
`run_node`**, so a join can only be asserted as "all branches completed + whole run completed".
② **A real bug fixed along the way**: the `q()` helper in `e2e-flow-matrix.py` queries the
database through a `psql` subprocess; without `PGPASSWORD`, psql asks for the password
interactively → the query fails → it returns an **empty string**, which the caller reads as
"status is empty" ⇒ a whole block of assertions goes red and looks like a broken product; it now
raises an exception carrying stderr whenever psql exits non-zero (this misled one full round of
debugging — worth recording). ③ **Not covered**: real devices, device tasks + parallelism, the
**elapsed-time overlap of parallel branches** (built-in nodes are too fast to show real
parallelism), loop + parallelism combinations, and `subProcess` + parallelism. ④ "Thresholds may
only rise" currently covers `MIN_*` only; the "case count holds but coverage shrinks" loophole
still needs human review, and "numbers match the code" is not yet a machine check — **suggested
for the next round**. ⑤ The L2 with-database layer (1005) is **not covered by the gate**
(`ci-gate.sh` only runs `--pure-only`); this is now noted in the master table.

### 2026-11-06 (gateway readability: naming + role labels + a visible end marker)

**Background**: while working through the review bundle the user asked what the difference
between "Create Gateway" and "Create parallel gateway" is, and after looking at the
screenshots raised a real problem: **"these two are easy to confuse just sitting there like
that. Especially at the end — without a matching end marker, it is hard to see clearly."**
Checking the code showed this was not a badly drawn picture but the product missing three
things:

1. **Both gateways are diamonds** (✕ / ＋), nearly indistinguishable when zoomed out;
2. **The designer had no naming UI at all** — the node-settings card hangs off the compile
   result (compile only knows tasks), so selecting a gateway shows only "not saved yet, save
   the canvas first before you can bind a device and method"; **you could not even give a
   gateway a name**;
3. **The end event's fill was `#f8fafc` (near-white)**, nearly identical to `rg-bypassed` —
   on a light canvas it was impossible to see where the flow ended.

**What was done**: ① **A dedicated gateway card** (not a reuse of the task node-settings
card): the right panel gains "gateway settings" showing the gateway type (parallel/exclusive
plus a one-line explanation), **the role it plays in this graph**, and a display-name input.
② **A default name derived from the number of incoming/outgoing edges**: parallel split /
parallel join / exclusive split / exclusive join, with a one-click "use the suggested name".
The derivation rule **must match the backend** (`WorkflowGraphParser`: more than one
incoming edge → join, more than one outgoing → split, both → join (wait first, then split),
one in / one out → straight through) — a misleading name is worse than no name. The name is
stored in the BPMN `name` attribute (the parser already read it, and the run detail shows it
as the node-name column). ③ **Run view**: the end event becomes a dark filled circle
(`#334155` with a 3px dark border), gateways get a different stroke width per type, and a
gateway with more than one incoming edge gets an **orange marker** (BPMN's convention is a
double border for joins). ④ **Label styling**: `.djs-label` is forced not to wrap and
gateway labels are bolded and enlarged, otherwise Chinese names get squeezed into a
vertical strip.

**Verification**: really dragged "exclusive split → parallel split → parallel join" on the page
and named them — **3/3 succeeded and all three names landed in the XML**; the screenshots
confirm the three labels are legible and do not overlap, and the panel correctly shows
"parallel join" for the role. `ci-gate.sh --all` passes all 18 gates with zero SKIPs;
`verify-en-docs.py` reports 0 hard errors.

**Pitfalls (all three worth recording)**:
① **`incoming` / `outgoing` live on the `businessObject`, not on the element** — reading the
wrong place yields undefined, the edge count is always 0, and **every gateway is then judged
"straight through (one in, one out)"**. When the first version showed "straight through" I
briefly suspected the backend rule was wrong; it was the frontend reading the wrong object.
② **bpmn-js's context-pad covers the palette** — the floating toolbar that pops up on
selection blocks the next palette click, and Playwright retries until timeout ("text-annotation
entry intercepts pointer events"). Clicking empty canvas does **not** help (it is mounted on
the canvas); the reliable approach is `Esc` plus removing `.djs-context-pad-parent` from the DOM.
③ **A gateway had no place to say "join or split" at all** — this is not a matter of adding
an input field; it first needs a card **of its own** (a gateway binds no device or method,
so it can never reach the task card).

**Boundary**: naming was implemented for **gateways only** (no name field was added to the
task node-settings card — that is a different card, and a task's name is currently derived
from its device/method); the "double border" of `rg-join` is simulated with a drop-shadow
rather than a true double stroke; cross-workflow parallelism is still not implemented
(P2 batch 5, see the review bundle 02).

### 2026-11-06 (gateway pairs + N-value coverage: exclusive/parallel at N=2 and N=3)

**Background**: after the gateway-readability work the user pointed at a substantive problem
with the exclusive gateway: **"the exclusive one is wrong — after it there should be more than
two running together, and it should be tested with two or three-plus scenarios; and the
exclusive one is the same: it needs an exclusive start and an exclusive end as a pair to be
readable."** Checking the previous graph (`e2e-multi-parallel.py`) against the backend rule showed
the **names did not match the structure**:

| Gateway | Actual in/out | Real role | Name given back then |
|---|---|---|---|
| P1 | in 1 / out 2 | split | parallel split ✓ |
| P2 | in 1 / out 2 | split | parallel split ✓ |
| **X** | **in 1 / out 1** | **straight through** | ~~exclusive split~~ ✗ |
| **X2** | **in 1 / out 1** | **straight through** | ~~exclusive split~~ ✗ |

**The whole graph had no join at all**, and the two "exclusive" gateways were straight-through
rather than splits; the names were hard-coded by the script and never checked against anything —
so the user was right, and it was worse than legibility: **the graph was lying.**

**What was done**: added `scripts/e2e-gateway-pairs.py` (gate 4.15, threshold `MIN_GWPAIR=34`):
two graphs — N=2 and N=3 — **each containing both an "exclusive split + exclusive join" pair
and a "parallel split + parallel join" pair**, i.e. eight role checks in total. Three key
design decisions: ① **naming is not hard-coded — it goes through the page's "use the suggested
name" button**, so the assertion is that "the role the engine derives from the edge counts"
matches "the name shown on the graph" (hard-coding was the root cause of the mismatch);
② the role rule is the same source as the backend `WorkflowGraphParser` (more than one incoming
→ join, more than one outgoing → split, both → join, one in / one out → straight through);
③ execution semantics asserted separately: **all N parallel branches run**, the **exclusive split
takes exactly one** and the rest are `bypassed`.

**Verification**: all eight role checks correct and every one derived from the page button; all
four names landed in the graph XML; edge counts exactly as expected; two real runs both
`completed` (all parallel branches ran, the exclusive split took one); **34 passed / 0 failed**.

**Pitfalls**: ① **which branch the exclusive split takes is not deterministic** — with no
condition and no default, `choose()` takes the first edge by **target node id lexicographic
order**, and node ids are generated randomly by bpmn-js (`Activity_0ajn4aq` and the like). The
first version asserted "branch B ran"; two runs took A and C respectively ⇒ the assertion can
only be "exactly one branch ran". ② **every branch node must be turned into a built-in node** —
the first version only converted the *expected* branches, so the node on the branch the exclusive
split actually took was still a plain task (a device task) → it looked for a device → none found
→ turned `manual` and stopped → the run sat in `paused` (both N=2 and N=3 failed this way).
③ The gate section count goes **18 → 19** (adding 4.15), and the review bundle and master table
were synchronised (Chinese/English).

**Boundary**: only gateways **within one workflow** are covered, with their N values; **cross-
workflow parallelism is still not implemented** (P2 batch 5); an exclusive join is an OR join
(any arriving branch releases it) while a parallel join is an AND join (every incoming branch
must arrive first) — and that difference is exactly what this section asserts.

### 2026-11-06 (pause point and resume origin across exclusive / parallel / device / variable nodes)

**Background**: the user asked a very practical question: **"if there are exclusive, parallel,
device nodes and variable nodes, when a pause happens will it stop at those nodes? And when it
resumes, does it continue from the next node or run from the current one? And is there any
difference in how device nodes and logic nodes are handled?"**

**The source-level answers** (`GraphRunner` / `WorkflowService`):

| Question | Answer | Basis |
|---|---|---|
| Which nodes can a pause stop at | **Only device task nodes.** Exclusive gateways, parallel gateways **and logic nodes** never can | `pauseChecker` is consulted only in the `Kind.TASK` branch; `Kind.BUILTIN` takes a **separate branch** and that branch has **no** `pauseChecker` |
| Where does the resume continue | **From the node that had not finished yet** — neither the next one nor from the beginning | `resumeAsync` takes the previous round's `level=0 AND status='completed'` node keys as `skipKeys` → completed ones are skipped, unfinished ones are re-run |
| Device vs logic difference | **No difference** in how the pause point is chosen; the difference is the **side effect of re-running** | re-running a logic node is pure computation with zero side effects, while re-running a device node **issues the command again** (the robot arm moves the plate again) |

Key source locations:
- `GraphRunner.java:333` — the only `pauseChecker` call, inside the `Kind.TASK` branch and
  **before** `executeNode` (action boundary — "the robot arm would drop the plate")
- `GraphRunner.java:333-338` — `ready.offer(key); done.remove(key);` → the stopped node goes
  back on the queue and leaves `done` ⇒ "this node is not done; resume continues here"
- `WorkflowService.java:824` — `.withPauseChecker(() -> isRunPaused(runId))` → one DB check per node boundary
- `WorkflowService.resumeAsync` — collect completed node keys → delete the non-completed rows → `execute(..., doneKeys)`
- `WorkflowService.java:825-827` — "**do not** decide to fall back based on the execution result.
  Falling back means re-running, and re-running on a real device means opening the lid twice
  (physical side effects are irreversible)"

**What was done**: added `scripts/e2e-pause-resume-nodes.py` (gate 4.16, threshold
`MIN_PAUSERES=25`): drag "S → variable node → **real device node** → exclusive → parallel → end"
on the page, pause while the device node runs, and assert ① the pause point is not a gateway,
② **nor a logic node**, ③ it is a device task node; then go through the **real resumeAsync**
(the same path as the page's "continue" button) and assert ④ the completed logic node was **not
re-run** (still one `run_node` row), ⑤ the unfinished device node **was re-run**, ⑥ the **device
command was issued exactly once** and really got a response back.

**Verification**: **25 passed / 0 failed**.

**Two points clarified by measurement (more accurate than reading the source alone; both are now
asserted)**:
① **A pause takes effect *after* the previous node has already finished** — `pauseChecker` is
called *before* `executeNode`, so a node that was mid-flight when the signal arrived still runs
to completion; the stop happens at the **next boundary**. The code that runs on stopping is
`ready.offer(key); done.remove(key);` (put back on the queue but not executed this round) —
**"put back on the queue" does not mean "will be re-run"**: on resume, if it is `completed` it
lands in `skipKeys` → skipped. Measured: the device node was **already completed** when the
pause took effect → the resume skipped it → **the device command was issued exactly once**
(with a real response back).
② **`run_node.status` cannot be used as an "in progress" signal during execution** —
`WorkflowService.java:1523` creates **all** business nodes with status 'running' **before
execution starts**, and the final states are written in one go by `persistNodes` at the end.
The first version used "the node turned running" as its timing signal and thus caught a **fake
signal** (every node starts out running). The correct signal is **run_command has a row**
(the device command really went out).

**Pitfalls (all three worth recording)**:
① **The device dropdown holds 247 devices but renders only the first 10** — you must
**type to search** to reach `ID1731`; scrolling the options never finds it (the first version's
device binding silently failed and it took a while to spot).
② **Selecting a node needs a raw `page.mouse.click`** — `locator.click()` is blocked by
bpmn-js's **direct-editing box** (`.djs-direct-editing`) and retries until timeout.
③ **`run_command` hangs off the level=1 step rows** — you must join through `parent_node_id`
to reach the level=0 business node; the first version joined on level=0 directly, counted 0 rows,
and **nearly concluded "the device never ran"**. Also `device_raw_response` is a json type and
cannot be compared with `<> ''` (that raises invalid input syntax).

**Boundary**: not covered — pausing in the middle of a device command (the source guarantees it
cannot stop there), pausing under a `runtime_hold` site hold, pausing inside a loop body,
pausing on an exclusive branch **with conditions**, real-device timing, and pausing on a
`subProcess` node.

### 2026-11-06 (pausing during a device command: measured reality + random simulator delays + how to really stop)

**Background**: the user described a very specific phenomenon: **"most of the time the pause
command goes out, but the method the device is executing keeps going; only the robot arm
receives the pause command and stops right there — see how that should be implemented."**

**The measured reality (more thorough than the impression)**:
**Today a pause does nothing to a device command that is already executing — and the robot arm
stops neither.**

Measured (simulator delay set to 8–15 s, pause issued while the command was mid-flight):

```
run 最终状态      : completed        ← the pause never took effect
设备指令最终状态  : completed        ← the device finished; it was not interrupted
节点状态          : ARM=completed
暂停生效延迟      : 未观测到 run 变 paused（它直接跑到了终态）
```

**Root cause (located by reading the code)**:
① `GraphRunner.java:333` consults `pauseChecker` **only at an action boundary** (before
`executeNode`); while a device command is in flight the thread is **blocked waiting for the
reply** and never reaches that check. ⇒ a pause is structurally ineffective against a
**running** command (by design: "the robot arm would drop the plate").
② "the robot arm stops" is a **misreading**: `PauseManager.releaseResources` releases
resources that are *reserved but not in use*, while the **robot arm holding a plate is
exempt** (letting go would drop it) — the semantics is precisely "the robot arm keeps
carrying". The robot arm and ordinary devices behave **identically** here.
③ **The abort capability exists but is not wired up**: `DeviceDriver.abort(commandId)`
("abort the command in flight, best effort") genuinely interrupts the wait in
`TcpPersistentDriver` (it removes the pending future); `TcpDriverAdapter` is a no-op (the
protocol has no Abort message). **There is no caller anywhere** — measured:
`run_command.error_code='EC-99-0005'` (aborted) occurs **zero times in the whole database**.
④ **A real bug found along the way**: the driver's timeout is a **fixed 30 seconds at the
executor level**, while `device_method.timeout_sec` / `workflow_node.timeout_sec` **store the
value but the driver never reads it** (`grep timeout_sec` under `driver/` and `workflow/`
returns nothing). ⇒ **any real-device method taking longer than 30 s (a 5-minute centrifuge,
a 30-minute incubation) is judged as a timeout by the control plane** — unrelated to pausing.
Measured: with the simulator delay set to 20–40 s, the runs that exceeded 30 s came back
`failed`.

**Companion change · random simulator delays** (needed to test "pause during execution" — with
the fixed few-hundred-millisecond delays the pause signal never gets a chance to arrive while
a command is in flight):
- `DeviceCore`: new `randDelayMinMs / randDelayMaxMs / randDelaySeed`; each call takes a
  value from the range; precedence `--rand-delay` > `--delay` > the profile/model's fixed
  delay. **Off by default** (`randDelayMaxMs=0`) ⇒ existing behaviour is completely unchanged.
- `DeviceSimulator`: new `--rand-delay MIN:MAX[:SEED]` (ms); omit SEED for a different value
  every call, pass SEED for a reproducible sequence.
- `scripts/sim-up.sh`: third argument — `./scripts/sim-up.sh LAB-DEMO-02 0 30000:300000`.
- Measured (10–20 s, seed 42): four commands took **17.3 / 16.8 / 13.1 / 12.8 s** — inside
  the range and different every time.

**New tests**:
- `scripts/e2e-pause-resume-nodes.py` (gate 4.16, threshold `MIN_PAUSERES=25`) — the pause
  point and the resume origin across exclusive / parallel / device / variable nodes, **25/0**.
- `scripts/e2e-pause-during-device.py` — measures the current behaviour of "pause while a
  device command is in flight" (not in the gate: it asserts today's behaviour, which will
  change once a real interrupt is implemented).

**Solution document**: `docs/设备执行中暂停-现状实测与实现方案-20261106.md`. The
recommendations, ordered by risk:
1. **Take the timeout from the method** (low risk, immediate): read `device_method.timeout_sec`
   (already stored), fall back to 30 s; long tasks stop being killed spuriously.
2. **Record the current commandId on pause** (low risk): paving the way for a real interrupt.
3. **Make the execution side interruptible** (medium-high risk, involves real-device safety):
   replace the blocking `driver.execute` with "own the future + poll for pause + call
   `driver.abort(cmdId)` on a hit"; **let each method declare whether it can be interrupted**
   (`device_method.interruptible`: robot-arm transport = false, long processes = true) — the
   robot arm must not stop (the plate would drop), but a centrifuge or incubator can.
4. State it in the UI ("the current action will finish" / "interrupted, retryable") to remove
   the misunderstanding.

**A gate defect fixed along the way (2026-11-06)**:
the assertion "no uk_run_node conflict" in `scripts/verify-device-chain.sh` was
`grep -c "步骤落库失败" /tmp/labos-api.log` — it counts the **entire accumulated log**.
`/tmp/labos-api.log` grows forever, so **once a duplicate key had ever occurred, that
assertion would stay red forever** (measured: 7 historical records kept the gate failing
while the actual execution was fine). It now only looks at the log lines **added by this
run** (the script records `LOG_BASE_LINE` at startup as the baseline). After the fix,
`verify-device-chain.sh` runs **41 / 0** (it was 39 / 2 before).

⚠ The first attempt also added "clean the reused workflow's previous run data before
triggering" — and **that broke the script** (the cleanup ran before the trigger, so
`RID=$(SELECT max(id) FROM run)` found nothing → section 6 failed wholesale, plus
`steps: unbound variable`). That change was reverted — **only the assertion's scope was
fixed, the script's data flow was left alone**. Lesson: when changing a test script, the
insertion point matters more than the change itself.

**Addendum (same evening): experiment-level measurement + two real bugs fixed**
The user followed up: "when we issue a pause while the device method is executing, does the
experiment end up in the paused state?" Measured (experiment level, device delay random
10–18 s, pause issued mid-execution):

```
暂停前 experiment_run 状态: running
暂停接口返回：HTTP 200 {"status":"paused","scope":"experiment","kind":"manual"}
暂停后 experiment_run 状态: paused
run（workflow 级）      : paused
experiment_run（实验级） : paused
experiment（实验本身）   : paused
设备指令最终状态        : completed      ← the device action still ran to the end
暂停生效延迟            : 0.2 秒
```

**⇒ The answer: it does go into the paused state** (all three layers show "paused", effective
within 0.2 s), **but the method the device is executing is not interrupted**.

**A pitfall when measuring this**: the first version triggered with `?async=1`, and **the
`experiment_run` was already `completed` before the pause** — the scheduler dispatches and
immediately runs the experiment to the end (11:23:21→11:23:33), so the pause landed on an
**already-completed run** (the endpoint still answers 200 + `status:"paused"`, which is very
misleading). **You must use synchronous execution** (without `async=1`): synchronous execution
blocks the HTTP response, which conveniently leaves the "device is executing" window open
(run the execution on a background thread, issue the pause from the main thread).

**Two real bugs exposed and fixed** (they only surface together in the "synchronous execution +
pause during a device command" scenario; with async execution the experiment finished early
and both bugs stayed hidden):
① **the `paused` state of `experiment_run` gets overwritten on finalisation**:
`ExperimentExecutor.settle()` unconditionally writes `status='completed'` and **lacks the
`if (isRunPaused(runId)) return;` guard the workflow level has** (`WorkflowService.settleRun`
has it) ⇒ the moment the user clicks pause it is overwritten with `completed`.
**Fixed**: the experiment level now has the same guard, plus a new `isRunPaused(long)` (same
criterion).
② **the `experiment` table is not synchronised**: `RunControl.markCoveredPaused()` marks
`experiment_run` but leaves `experiment.status` at 'running' ⇒ the list shows "running" while
the detail view shows "paused" — **the two contradict each other** (on site, people would think
it is still running and never press continue).
**Fixed**: after marking, the `experiment` row is synchronised through the vocabulary mapping
(`StatusVocabulary.runToExperimentCaseSql`, the same criterion as
`ExperimentExecutor.syncExperimentStatus` — no second hand-written mapping).

New gate section 4.17 (`MIN_PAUSEEXP=2` at the time, raised to 5 on 2026-11-07, see the end of this file): asserts the **semantic requirement** (all three layers
paused + effective within 5 s) and deliberately does **not** assert "the device command finally
completed" — that one changes once a real interrupt is implemented and would make the gate
falsely red.

**Boundary**: this round only **measured the current behaviour and produced a plan**; the
production pause semantics were **not** changed (it involves real-device plate drops, so the
list of interruptible methods needs on-site confirmation); taking the timeout from the method
is also **not yet implemented** (located and written into the plan).


**Added later (2026-11-06, evening): gate section 4.17 went red → the problem was the
*criterion* itself; after the fix the re-measurement**

Gate section 4.17 failed, yet **running that script on its own was green** (both assertions ✓),
and the gate log could **not find any `✗` detail**. Those two symptoms together have exactly one
explanation: **the script never printed its "通过 N / 失败 M" summary line**
⇒ the gate's `grep -oE '失败 [0-9]+'` found nothing ⇒ it fell back to `EF2=1` ⇒ it reported
"实验级暂停有失败（1 条）" while `grep '✗'` naturally printed nothing — **red with no visible
reason**.

Fix (in the gate itself): a `parse_summary` helper that treats **"summary line missing" as its
own failure class** and prints the tail of the output, instead of mixing it with "there are
failing assertions" (the two need completely different handling: the first means the script
died, the second means an assertion went red). Section 4.16 now uses the same helper, and
`e2e-pause-resume-nodes.py` now prints the summary on **every** exit path.

🔴 **But following that trail uncovered a more important problem: the first version's criterion
for "the device is executing" was wrong in the first place.**

1. **A `run_command` row ≠ the device is executing.**
   `run_command` is INSERTed **after the step has finished** (`status` is written directly as
   `completed`/`failed`, `sent_at`/`finished_at` are both `now()`) — it proves "this step ran",
   **not** "the command just went out". The first version used it as the "device is executing"
   signal, so **the pause went out after the device had already finished** — it was not
   measuring "pause during execution" at all.
2. **`max(id) FROM run` can pick up somebody else's run.** The table still holds runs from other
   tests (with commands and terminal states); it did pick up the stale run with
   `workflow_id=1255` (finished at 11:58) — so "the device command's final status" and "the node
   states" were **another run's data**.

**The correct criterion**: `device_state.status='running'` + non-null `current_method`
(`markDeviceBusy` on `onStepStart`, `markDeviceIdle` on `onStepEnd`), plus `current_run_id` to
confirm the execution is ours; locating your own run is always done via
`run.workflow_id = <the workflow this test just created>`.

**Re-measured with the corrected criterion**:

```
  ✓ 观测到设备方法执行中（device_state status=running current_method=Home，归属 run #207774）
  暂停前 experiment_run 状态: running
  暂停接口返回：HTTP 200 {"runId":1643,...,"status":"paused","scope":"experiment","kind":"manual"}
  暂停后 experiment_run 状态: paused
  ✓ 暂停在 5 秒内生效（0.2 秒）
  ✓ workflow 级 run 独立跑完（不受实验级暂停影响）：completed

  结果：
    run（workflow 级）      : completed
    experiment_run（实验级） : paused
    experiment（实验本身）   : paused
    设备指令最终状态        : completed      ← the device method still ran to the end
    暂停生效延迟            : 0.2 秒
```

**⇒ The conclusion (corrected on 2026-11-07)**:
① The pause **does take effect** (`run` → `paused` within 0.1–0.2 s). The line above —
"the workflow-level run finishes on its own" — is **what was observed at the time, but it is a
bug**: an experiment-level pause records a **hold** (`runtime_hold`) and does **not** change the
workflow-level `run` status, and the underlying `GraphRunner` only checked `run.status` ⇒ it
never saw the hold ⇒ it ran every remaining node. **Fixed on 2026-11-07**:
`WorkflowService.withPauseChecker` now checks, in addition to `run.status`,
`RuntimeHold.coveringForWorkflow` (resolving ownership via `experiment_run_step.status='running'`)
⇒ on a hit the underlying `run` lands on `paused` at the **node boundary** and the following
nodes stay `pending` (see the entry at the end of this file).
② What is still not solved is the same thing as before: **the one device method already executing
is not interrupted** (`completed`, it runs to the end). ① and ② are **two different things** — do
not conflate them: ① is "stop at the node boundary", on by default and independent of
`interruptible`; ② is "really stop mid-action" and needs the on-site list
(`device_method.interruptible=true`).

(The workflow-level variant was re-measured too: `run` → `paused` within 0.1 s, device command
`completed`, node `ARM=completed` — consistent with the experiment level.)

**Knock-on correction**: the criterion wording in `e2e-pause-resume-nodes.py` (gate section 4.16)
— a `run_command` row only proves "the DEV step was done", it cannot be described as "the
command went out"; its conclusions (DEV was completed when the pause took effect; the resume
skips it via `skipKeys`) are **unaffected**, because the pause check point is always **before**
`executeNode`, whether the device is fast or slow.

**Lesson (cross-project)**: in a test script, "some state appeared" does not mean "something
happened" — *when* a row is written (persisted after the step vs. written at dispatch time)
decides what that signal can actually prove. **Find out who writes a signal and when, before
using it as a criterion.** And when a gate parses a sub-script's output, "could not parse" must
be handled separately from "parsed and not zero" — otherwise you get "red with no visible
reason".

**Added later (2026-11-06, afternoon): the user decided three things — timeout per method /
A1 "really stop it" / switch the simulator to 30–300 s**

The user's answers to the three open questions:
① Timeout per method → **"implement the mechanism **and** fill in the default timeout for
   every capability"**
② Really stopping a device command → **"A1 — I will provide the list of interruptible
   methods"**
③ Simulator delay range → **"fix the timeout first, then switch to 30–300 s"**

### ① Timeout per method (implemented)

Before the change `DriverStepExecutor` used an **executor-level fixed 30 s**, while
`device_method.timeout_sec` / `workflow_node.timeout_sec` were **stored and never read**
⇒ **every real-device method taking longer than 30 s was killed by the controller**
(a 5-minute centrifuge, a 40-minute PCR) — unrelated to pausing, a plain bug.

Landed:
- New pure-logic class `MethodTimeoutPolicy` (the priority order) + 20 self-test
  assertions (`MethodTimeoutPolicySelfTest`).
- The driver layer (`DriverStepExecutor.resolveTimeoutMs`) resolves
  **device method → device method-library ceiling → workflow node → capability default →
  fallback 30 s**, and logs one line `[超时] <node> <method> → N s (source …)`.
- New shard `99a_method_timeout.sql` (idempotent): fills the **260 rows** whose
  `device_method.timeout_sec = 0` per capability (incubation 1800 s / thermocycler 3600 s /
  centrifuge 900 s / washer 900 s / everything else 600 s).
  The 3 s / 5 s / 20 s / 302 s values already in the device library are **left untouched** —
  they are real contracts (estimated duration × 2), and raising them would turn "the
  barcode reader died" from a 3-second discovery into a 10-minute one.
- `90_all_in_one.sql` regenerated with merge.sh; the gate's "product matches the shards"
  check passes.

**Measured** (robot arm + random 30–300 s delays):
```
[超时] ARM Home → 600 秒（来源 device_library：设备方法=0s 设备库上限=600s 节点=0s 能力=TRANSFER）
⇒ the device method ran for 48 s and answered normally — it was no longer killed at 30 s
```

**Four traps hit while implementing it** (none of them is small — every one of them
silently falls back to 30 s):

1. **`ctx.resourceId()` is the device_instance.id**, not the resource_node.id — the proof is
   `DriverRegistry.driverFor(long deviceInstanceId)` and `candidates()`, which both use
   instance ids. The wrong id space ⇒ every lookup misses ⇒ **silently** back to 30 s
   (that is what the first version did; the log was full of 0s).
2. **The capability default must use the device's real capability** — the capability on the
   node is **guessed from its name** (`resolveCapability` level 6: a node named
   "机械臂搬运" gets guessed as WASH), and querying the device's tables with a guessed
   capability misses on both ends ⇒ query `resource_capability` instead.
3. **`resource_capability` has no tenant_id column** — including it raises a SQLException,
   the exception is swallowed ⇒ null is returned silently ⇒ the capability falls back to the
   guessed one again.
4. **The resolved timeout must be logged** — without the `[超时] …` line, all three traps
   above can only be guessed at.

### ② Two real bugs this work exposed

**bug A · Capability resolution hijacked by same-named methods (a latent defect)**
`loadProtocolCapability` was `SELECT dm.method, c.code FROM device_method ...` with
**no DISTINCT and no ORDER BY**, and `Home` exists on the centrifuge (221), the transport
robot (145) and the plate washer (183) ⇒ the result **depended on the physical row order**.
My bulk UPDATE (260 timeout rows) reshuffled the heap ⇒ `Home` flipped from TRANSFER to
CENTRIFUGE ⇒ **a node bound to the robot arm got bound to a centrifuge and the bound
resource was ignored** (the candidate pool is per capability).
Fixed: **the bound device's capability wins** (a new "bound" priority in
`resolveCapability`, ahead of the protocol lookup) + the protocol map is now
`DISTINCT + ORDER BY` (reproducible).

**bug B · Leaked device locks (ghost occupancy)**
When the process crashes or the API restarts, the device method in flight never reaches
`onStepDone` ⇒ `markDeviceIdle` never runs ⇒ `device_state` stays at
`status='running' + current_run_id=<a dead run>`; and `DeviceLock.tryAcquire` requires
`current_run_id IS NULL OR current_run_id = me`
⇒ **that device can never be acquired by any run again** (on site: one device is
mysteriously "always busy"). Hit in practice: the robot arm ID1731 was stuck at
`current_run_id=207893`, a run that died with the API restart.
Fixed: the startup fallback `settle` now **releases the device locks held by interrupted
runs** (same semantics as `DeviceLock.release`), covered by 3 new assertions in
`StartupSettleSelfTest`.

### ③ A1 "really stop it": mechanism implemented, waiting for the on-site list

- `device_method` gains an `interruptible` column (default **false**); every method
  declares for itself whether it can be interrupted mid-flight.
- Execution side (`DriverStepExecutor.executeCommand`):
  **not declared interruptible ⇒ the old path (the calling thread blocks for the reply,
  behaviour unchanged)**; declared interruptible ⇒ the call runs on a worker thread and the
  main thread polls every 200 ms for "should I stop" (workflow-level `run.status='paused'`
  / experiment-level `runtime_hold` coverage) and on a hit calls
  `driver.abort(commandId)` (`TcpPersistentDriver.abort` removes the waiting future).
- After the interrupt: the command fails → `EC-99-0005` (that code is **not registered**
  in the error-code table) ⇒ no recovery policy matches ⇒ the fallback is **MANUAL** ⇒ the
  node lands as `manual`, the run as `paused`; on resume `resumeAsync` deletes every
  non-completed row ⇒ **that step is re-run**.
- **Everything defaults to false ⇒ without the on-site list the behaviour is byte-for-byte
  the same as before**; this switch can never stop a robot arm that is carrying a plate.
  Once the list arrives, flipping the flag is enough.
- New script `scripts/e2e-interrupt-during-device.py`: temporarily marks the robot arm's
  Home as interruptible, pauses while it executes, and asserts that the command reaches a
  terminal state **within 10 seconds** (i.e. it did not wait for the device to finish).

### ④ Simulator switched to a random 30–300 s

`/tmp/sim-19502.log`: the robot-arm instance now runs `--rand-delay 30000:300000`
(truly random, no seed). Measured one call at 275 s — **before the timeout fix that call
was guaranteed to be killed at 30 s**; now it completes normally.

**Lesson (cross-project)**: before treating "some column in some table" as configuration,
confirm two things: **who writes it** (the write timing decides what it can prove) and
**whether anything actually reads it** (`timeout_sec` sat there for three years unread, and
those "looks configured" fields are the most dangerous ones).

**Addendum (2026-11-06, evening): the orphan-lock cleanup was skipped by an early return**

Above I wrote that "the startup fallback settle releases orphan device locks (those
pointing at deleted runs)" — **the first version put that UPDATE in the wrong place**:
it sat **after** `if (found.isEmpty()) return orphans;`, and "there is no orphan run to
settle this time" is by far the **common** case
⇒ **the early return ⇒ the orphan lock is never released**.

Hit in practice: after the gate run the robot arm's lock pointed at
`current_run_id=208017`, and that run **no longer exists** in the `run` table (the test
deleted it) ⇒ an orphan lock. After an API restart it was **still there** ⇒ the device
could not be acquired ⇒ exactly the symptom we are fixing.

Fixed: the orphan-lock cleanup now runs **before the scan**, so it happens whether or not
there are orphan runs. And the self-test now covers **the early-return path**
(restricting the settle to a run id that does not exist ⇒ `found` is empty) — the old
self-test only covered the "there is an orphan run" path, which is precisely why it
missed this bug.

**Lesson (cross-project)**: when adding an early return to a block of logic, go back and
check whether **every cleanup action after it** also gets skipped; and write self-tests
that cover **each exit path**, not just the main line.

**Added later (2026-11-07): an experiment-level pause could not stop the underlying run — fixed, and the interrupted old run is voided on resume**

The user's instruction: "fix the experiment-level pause".

**Root cause**: an experiment-level pause records a **hold** (`runtime_hold`) and does **not**
change the workflow-level `run` status; the underlying `GraphRunner` only checked `run.status` at
the node boundary ⇒ it never saw the hold ⇒ it ran every remaining node.

**The fix (two parts)**:
① **Stop at the node boundary**: `WorkflowService.withPauseChecker`'s criterion was widened from
   "`run.status='paused'`" to "`run.status='paused'` **or** this workflow is covered by an active
   site hold" (`RuntimeHold.coveringForWorkflow(workflowId)`) ⇒ on a hit the underlying `run` lands
   on `paused` (`pauseRunAtBoundary`) and the following nodes stay `pending`. **Independent of
   `interruptible`, on by default.** The key point of the criterion: `coveringForWorkflow` resolves
   ownership by **`experiment_run_step.status='running'` + `workflow_id`**, and must **not** filter
   on `experiment_run.status='running'` — an experiment-level pause first sets `experiment_run` to
   `paused` (`RunControl.markCoveredPaused`), so filtering on `running` can never find it, which is
   the direct cause of the original bug.
② **Void the interrupted old run on resume** (a regression found while fixing): an
   experiment-level pause also lands the current step's workflow-level run on `paused`; on resume
   that step is re-run **whole** and a new run is started (`ExperimentExecutor.runStep` creates a
   new run for every step) ⇒ the old `paused` run becomes an orphan that sits in the **paused
   queue** (`PauseManager.pausedRuns` queries `run.status='paused'` with **no filter**) forever,
   and an operator's mistaken "resume" would make the robot arm **repeat the same action**
   (a physical side effect that cannot be undone). Fixed: `RunControl.resume` now voids
   "this experiment's steps that are still `paused`" workflow runs as `aborted` (the runs of
   completed steps are `completed` and untouched).

**Verification** (both actually run):
- The database-backed self-test `RunControlDbSelfTest`: **43 passed / 0 failed** (3 new assertions:
  the interrupted step's workflow run is voided as `aborted`, a completed step's run is still
  `completed`, and the voided run gets an end time).
- Gate 4.17 (`e2e-pause-during-experiment.py`): **5 passed / 0 failed**
  (run #208222 / experiment_run #1868; `ARM=completed` / `ARM2=pending`); the threshold
  `MIN_PAUSEEXP` went 2 → 5.

**Lesson (cross-project)**: when implementing "pause at layer A", never assume "layer B will see
it on its own" — if layer A records a **bypass state** (here a hold, not the run status), layer B's
check point will never see it; either add a reverse lookup in layer B or record the state where
layer B can see it. Also, **when voiding an intermediate state, clean up the downstream visible
artefacts too**: a re-run starts a new run, and an unhandled old run becomes a "ghost pause entry"
that, once someone mis-clicks it, **replays a physical action**.

**Added later (2026-11-10): legacy Chinese gateway names still showed up in the English UI — solved with a display-layer reverse lookup (P-13)**

Symptom: workflows created before 2026-11-10 still showed Chinese gateway names
(`异或汇合`, `并行分叉`) on the canvas in the English UI.

Root cause: when the automatic gateway name was changed to the neutral code
(`XOR_MERGE`), only a **one-directional** map ("code → text") was added. The legacy rows
store the Chinese name, which is not in the table ⇒ the lookup misses ⇒ the raw Chinese
is displayed. Two more holes of the same kind turned up while chasing this:
① the bpmn-js viewer of the **run graph never registered the renderer at all**
   (36edb54 only touched the designer canvas) ⇒ even new codes stayed raw there;
② a reverse lookup without a node-type guard would treat "a custom name that happens to
use the same wording" as a role name — the database really does contain gateways named
`分支` and `B还要再来吗`.

Fix: add a **frozen** "legacy Chinese name → code" reverse table, normalize the name to
a code *before* rendering and then look up the text, so new and legacy data share one set
of i18n strings. **Not a single byte of the stored data is touched** (no data migration:
you cannot tell "an auto-generated role name" from "a custom name that happens to use the
same wording", and a migration would corrupt the user's own naming). The run graph now
registers the renderer as well and redraws on a locale switch (the canvas is an SVG,
unaffected by React re-renders).

Verification: the live database has 4 legacy Chinese names in `workflow_node` (workflows
1257 / 3203, whose BPMN XML carries the same Chinese names); a runtime mapping check
(2 locales × 8 role names all correct, 10 other names never misjudged); and a real
bpmn-js rendering check (canvas texts `["并行分叉","异或汇合","分支"]` in Chinese and
`["Parallel split","XOR merge","分支"]` in English, while `businessObject.name` still
holds the original Chinese value afterwards ⇒ the model was not modified).

Lessons (cross-project):
① **"A new mechanism was added" is not "every entry point was wired up"** — the same
renderer is registered once in the designer canvas and once in the run graph; missing one
leaves that one place on the old behaviour, and the place that misses still *looks* fine
(only the text is not localized). ⇒ When adding a mechanism, write the **list of entry
points** down and wire each of them; do not stop at the main one.
② **A reverse table must freeze the literal strings; it must not be derived from the
current language pack** — the pack's wording changes, while the literals stored in the old
rows are **historical facts**.
③ **A display-layer reverse lookup without a type guard will misjudge**: rather miss the
translation (the old name stays as it is) than misjudge (rewrite a user's custom name into
a role name). That trade-off has to be written into the item explicitly — do not assume
"an exact match is enough".
④ **Under concurrent sessions, `git commit` commits the entire index** — my changes were
swept into another session's commit, whose title had nothing to do with the content.
⇒ Check `git diff --cached --stat` before committing and `git show --stat HEAD` afterwards;
for a commit that was already **pushed**, do not rewrite history — just record the real
commit id in the status table (see chapter 9 of the contribution guidelines).

**Added later (2026-11-10): the names of built-in logic nodes and robot-arm transfer nodes also fall back to neutral codes (P-14)**

Symptom: a workflow created in the Chinese UI stores node names like `表达式节点` and
`机械臂搬运`; the same workflow created in the English UI stores "Expression node" and
"Robot-arm transfer". Open someone else's graph after a locale switch and the node names
are simply in the other language.

Root cause: P-13 only converted the **gateway role names** to neutral codes — the
built-in node line was left untouched. It uses the very same mechanism (an
auto-generated name written into the BPMN `name`), but it wrote the **UI wording**
there. And `name` is persisted data (it lands in `workflow_node.name`).

Fix:
① The code of a built-in node **is its method** (`__setVar`/`__expr`/`__sleep`/`__wait` —
   the backend identifies built-in nodes by `protocolName` anyway), so no separate set of
   codes is needed;
② The robot-arm transfer node uses `ARM_TRANSFER` — its method is `Transfer`, and the
   database has more than 2000 ordinary transfer tasks with that same name; using it as
   the display identity would label every one of them `机械臂搬运`;
③ Legacy Chinese names are converted by a reverse table at the display layer;
   **not a single byte of the stored data is touched**;
④ The legacy **English** names are deliberately **not** reverse-mapped: they collide with
   **real device method names** — the database has 480 rows named `Delay` whose
   `protocol_name` is `Delay` — so the two are indistinguishable in the data and a
   reverse lookup would necessarily misjudge 480 real device nodes. Rather miss the
   translation (graphs created in the English UI have to be renamed by hand once) than
   misjudge.

**The most valuable thing found this time**: bpmn-js draws names in **two completely
different places** — the external label (gateways, drawn outside the shape) and the
**inside of the task shape** (built-in nodes, drawn in the box). The first version only
took over the label, and in the rendering-level check `__expr` **showed up verbatim in
the box on the canvas** ⇒ the shape itself has to be taken over as well (two renderers:
label 2000 / shape 1900, and the shape one deliberately below the device-card renderer
at 2000 so that device nodes with an icon still go through the card renderer).

**Verification**: `tsc --noEmit` on an exported snapshot of the index (0 errors — the
working tree mixes in another session's WIP and cannot stand for this commit); a runtime
mapping check (2 locales × 9 codes + 10 legacy Chinese names, zero misjudgements among
14 other names); and a real bpmn-js rendering check (both the import-render path and the
locale-switch redraw path — `Delay` stayed untouched and the model name was not
modified).

Lessons (cross-project):
① **"the same mechanism" is not "the same code path"** — registering the same renderer
   in **both canvases** (designer / run graph) is not enough: bpmn-js has **two**
   rendering entry points for a name (external label vs inside the shape), and missing
   one leaves half of the nodes showing their raw code.
   ⇒ When adding a rendering-layer mechanism, write down **everywhere the name can be
   drawn** and wire each of them.
② **A reverse lookup by name requires proving the literal is unique in the real data** —
   this time we nearly turned 480 real `Delay` device nodes into built-in delay nodes.
   ⇒ Before building a reverse table, query the database: does this literal mean
   anything else there? (Not "it looks unique" — **looked it up**.)
③ **Without the field, there is no type guard** — the frontend `RunNode`/Gantt nodes
   carry no `nodeType` and the backend DTO does not expose one either, so a reverse
   lookup can only match names exactly ⇒ the misjudgement risk has to be written into
   the item explicitly (rather miss than misjudge) instead of assuming "an exact match
   is enough".

**Added later (2026-11-10): the start/end node labels of the experiment canvas also fall back to neutral codes (P-15)**

Symptom: the node labels of the experiment orchestration canvas are **persisted data**
(the whole canvas JSON lands in `experiment_graph.graph`), but the auto-generated labels
of the start/end nodes are written with the **UI wording of the current locale**.
⇒ An experiment created in the Chinese UI stores `开始` / `结束`; the same experiment
created in the English UI stores "Start" / "End". Open someone else's experiment after a
locale switch and the labels are simply in the other language.

Measured: all 5 rows of `experiment_graph` carry the Chinese `开始` / `结束` (none in
English), next to names the user typed themselves (`板数`, `重复`, `判断`, `汇合`,
`按条件走两条路`).

Root cause: the same as P-13/P-14 — a value that is "auto-generated" must not be UI
wording. P-13 (gateway roles) and P-14 (built-in logic nodes) were both converted; the
experiment canvas line was missed.

Fix: store neutral codes (`START` / `END`), localize at display time, **leave the stored
data alone`; nodes dropped from the palette no longer store wording at all (empty label ⇒
the localized hint of that node type is shown); legacy data is reverse-mapped at the
display layer and the lookup is **guarded by `kind`** — only start/end nodes can hit it,
so a workflow node the user named `开始` is not misjudged; the property panel input shows
the **display text**, and as soon as the user edits it, their own text is stored.

**The difference from the previous two (worth recording)**: this one **can** be guarded
by type, P-13/P-14 cannot — canvas nodes **carry a `kind` field** (start/end/workflow), so
one can tell "this label is the auto-generated one"; on the BPMN side the frontend
interface has no nodeType and the backend DTO does not expose one either, so only an
exact name match is possible.
⇒ For the same reverse-lookup task, **whether a type guard is possible depends on
whether the data carries that field**; if it does, do not fall back to guessing by name.

Verification: `tsc --noEmit` on an exported snapshot of the index (0 errors); a runtime
display-text check (2 locales × 10 cases, all passing — including "a workflow node the
user named `开始` is not misjudged"); `scan-i18n.py` reports 0 residual Chinese characters
in 0 files.

Lessons (cross-project):
① **"A was converted" does not mean "the whole class was converted"** — the very same
mistake ("an auto-generated name stored as UI wording") surfaced three times in a row
(gateway roles / built-in logic nodes / experiment canvas start & end), and each time it
had to be hunted down again. ⇒ For this class, **find them all at once**: search every
write site of an auto-generated name (`name = t(...)`, `label: t(...)`), not just the one
in front of you.
   ⚠ But note: grepping `label: t(` produces a lot of false positives (menus, table column
   titles, dropdown options are not persisted fields) ⇒ the criterion is "does this value
   **end up in the database**", not "is it a t(...) call".
② **Whether the data carries the field decides whether the reverse lookup can be guarded
by type** — guard by `kind` when there is one; otherwise only an exact name match is
possible (and the misjudgement risk has to be written down).

**Added later (2026-11-10): the two rulers of this environment — a verification criterion has to match the environment**

Trigger: the user reported that "the user name and password no longer work". The login
itself was fine (the audit log holds **not a single failed attempt** ⇒ the request never
reached the backend): **the backend was not running** — a devsplit test run at 17:50
stopped the kernel and nobody started it again; when I tried to restart it at 18:05 I
hit a compile error in another session's uncommitted file (they fixed it themselves within
minutes). After a rebuild and a restart the login worked again (both directly on port
18080 and through the frontend proxy on 5173 returned a token).

While fixing the login, **three real bugs / three broken criteria** turned up:

① **The PG enum case did not match** (real bug, fixed): `recovery_strategy` is an enum
   that only accepts lower case (retry/skip/reassign/abort/manual/compensate), while the
   UI and the Java vocabulary are upper case; the "error code policy" page sent the
   upper-case value straight through ⇒ PG answered
   `invalid input value for enum recovery_strategy: "RETRY"` ⇒ **saving always failed
   with a 500**. Its sibling page `ErrorCodes.tsx` already used lower case — only this
   one entry point was wrong. Fix: `.toLowerCase()` before sending; the UI is unchanged.

② **The probe criterion of the acceptance scripts did not match this environment** (the
   same trap, once in each of two scripts): the local simulators are **deliberately**
   given a 30–45 second random delay (`--rand-delay 30000:45000`; G-05 in
   `verify-flow-matrix.py` explicitly requires "the budget is derived from it, not a
   constant"), while the **shadow probe's command timeout is only 3 seconds** ⇒ even a
   good device times out with EC-05-1007 ⇒ judging by `ok` marks **every** device as
   "cannot run a command" ⇒ the script always SKIPs, which looks like "the environment
   did not start the simulators". Worse: the remote devices that cannot be reached
   return **the same** EC-05-1007 ⇒ the error code alone cannot tell "slow" from "dead".
   ⇒ The criterion became three things that do not depend on `ok`: ① the script opens the
   TCP connection itself (whether the port is open is unambiguous); ② the method is taken
   from the methods the **simulator profile really implements** (read `--profile` from the
   process arguments) — picking a method the model does not support guarantees a failing
   run; ③ the probe only judges "does the method match the device capability"
   (EC-06-0004 ⇒ try another method); every other error (including a timeout) means the
   command really went out. Also: the remote benches are **TCP-reachable from this
   machine** (port open, device silent) ⇒ only probe the local loopback endpoints;
   device selection went from 4 minutes to ~30 seconds.

③ **A momentary event like "running at the same time" cannot be judged by sampling**: an
   empty experiment runs for ~10 milliseconds, which sampling (interval ~25 ms) misses
   entirely; and computing the overlap of [started_at, finished_at] afterwards gives 0 as
   well (the three experiments were indeed released together, but each finishes within
   10 ms, so the intervals do not intersect). ⇒ Switched to the **dispatch log**, an
   evidence source the script already had: the 3 cross-island experiments were dispatched
   **within the same scheduler tick** (measured dispatch span 0.050–0.090 seconds while
   the tick interval is ~2 seconds) ⇒ the scheduler really released them together ⇒ the
   islands do not block each other.
   **Division of labour**: "the scheduler released them together" is proven by the
   dispatch log; "the execution windows really overlap" is proven with **real device
   actions** (same island, different devices, peak running = 2, two experiments dispatched
   31 milliseconds apart).

Lessons (cross-project):
① **A criterion has to match the environment, and the environment changes** — the local
   simulator delay is **by design** (the scripts derive their budget from it), not a
   misconfiguration; a criterion written as "a good device should answer ok" marks every
   good device as dead in this environment. ⇒ Before writing an acceptance script, ask
   one question: **what does "normal" look like in this environment** (delay magnitude,
   timeout values, the real response time of a device)? Write the criterion against
   that; if it cannot be read reliably, **read it from the environment** (process
   arguments, configuration, measured durations).
② **Find every instance of the same class of trap at once** — trap ② was hit once in
   `verify-parallel-experiments.py` and once in `verify-parallel-flow.py` (the latter spun
   for 15 minutes with 110 probes). ⇒ After fixing one criterion, search the other
   scripts for the same kind of criterion.
③ **Judge momentary events from evidence collected afterwards, not by sampling** — sampling
   misses the whole window; dispatch logs and timestamp intervals are **post-hoc**
   evidence and do not depend on the sampling rate.
④ **"Looks unreachable" and "reachable but silent" must be separated** — a TCP port that
   accepts a connection does not mean the device answers; a port check only proves that
   *somebody is listening*, not that *it will reply*.

**Added later (2026-11-10): the kernel would not start — a mapper statement was defined twice**

Symptom: the user reported that "the user name and password no longer work". The first
investigation showed the **backend was not running** (a devsplit test at 17:50 stopped the
kernel and nobody started it again) ⇒ starting it fixed the login. But a few hours later it
**would not start at all**:

```
Caused by: java.lang.IllegalArgumentException: Mapped Statements collection
already contains key com.labos.kernel.persistence.WorkflowWebMapper.selectWorkflowXml
```

Root cause: the statement `selectWorkflowXml` was **defined twice** in
`WorkflowWebMapper.xml`, the two copies being **character-for-character identical** (a
copy-paste leftover). MyBatis throws "already contains key" while parsing the second one ⇒
the `SqlSessionFactory` cannot be built ⇒ **the whole application fails to start**.

Lessons (cross-project):
① **A duplicate MyBatis statement does not warn, it kills the startup** — copying one SQL
   twice is not caught at compile time (the XML is not part of javac) and not by the unit
   tests either (they never reach the parsing stage when there is no database); it only
   explodes when the **kernel is actually started**. ⇒ After touching a mapper XML, **start
   the kernel once and read the log** — "it compiled" is not evidence.
② **"Copy-paste a piece of SQL" is the number one source of duplicate definitions** — the same
   `id` twice in one file; deleting one copy is enough (they are identical).
   ⇒ Self-check after adding or moving a mapper statement: scan the file for a repeated `id`:
   `grep -o 'id="[^"]*"' X.xml | sort | uniq -d`.

**Added later (2026-11-10): a `--` inside an XML comment kept the kernel from starting; and P-04 "the designer locks the island to the server-authoritative one"**

**1. The real reason the kernel would not start: two hyphens in a comment**

Symptom: the kernel failed at start-up (MyBatis threw `Error parsing Mapper XML`)
⇒ the container never came up, port 18080 never listened ⇒ what the site sees is
"cannot log in / the service is not running".

Root cause: three mappers used section comments written as `<!-- ---- xxx ---- -->`
— **`--` is not allowed inside an XML comment** (it may only start or end it) ⇒
those three files were **not well-formed XML**. Mapper XML does not take part in
javac ⇒ the build still passed and the unit tests never reached the parsing stage
(without a database they do not get that far) ⇒ **it only blew up when the kernel
was actually started**, and the symptom pointed nowhere near the cause.

Fix: the decoration becomes `==== xxx ====`; and `scripts/build-kernel.sh` now
runs an **XML well-formedness self-check** after compiling (parses every XML under
`src/`, fails with the file and line).

**2. P-04 frontend: the designer locks the island to the server-authoritative one**

The island used to be just a filter in the user's hands (localStorage, switchable
at will), while the authoritative boundary is `workflow.island_id` ⇒ a device
from another island could be bound to this island's workflow. Now the API carries
the authoritative island ⇒ the designer locks the island selector (cannot be
cleared, shows "fixed by the server") ⇒ the device pool is filtered strictly by it
⇒ dropping a foreign device is refused ⇒ the robot arm is taken from **the island
of the previous node** (moving a plate needs both devices on the same island).
Workflows without an island (1036 of them in the database) can still pick one
manually — the fallback path is intact.

Lessons (cross-project):
① **"It compiled" is not "it runs"** — for every artefact that does **not** take
   part in the compiler (XML / YAML / config), there must be a *parsing* self-check
   in the build; otherwise the error is deferred to "the service does not start",
   and the symptom is far away from the cause (a comment → the container dies →
   nobody can log in).
② **"A filter in the user's hands" is not "the system's authority"** — when the
   backend has an authoritative value and the frontend also lets the user pick one,
   the backend must win and the frontend must be locked; otherwise the two
   versions talk past each other while the UI shows nothing wrong.
③ **When a type field exists, the reverse lookup should be guarded by it** — canvas
   nodes carry `kind`, so one can tell "this label is the auto-generated one"; on
   the BPMN side there is no nodeType, so only an exact name match is possible.
   Same kind of task: guard it if you can, don't guess.

**Addendum (2026-11-10): flattening PG array columns (every execution returned 500); and P-05 "requested island vs authoritative island"**

**One: PG array columns — every workflow execution returned 500**

Symptom: every workflow execution returned 500 with
`ClassCastException: class [Ljava.lang.Long; cannot be cast to class java.sql.Array`.

Cause: `RealWorkflowRunner.loadCapabilityPool` cast the `array_agg(...)` column
of the capability-pool SQL to `java.sql.Array`. But with `resultType=java.util.Map`
the PG array column comes back through `ResultSet.getObject(...)` ⇒ you get the
**corresponding Java array** (`Long[]`); `java.sql.Array` only appears when
`getArray(...)` is called explicitly ⇒ the cast always blew up.

The same wrong assumption existed in `PauseManager.toList`, but there it was
**silent**: only `java.sql.Array` was recognised ⇒ the plate's "possible locations"
list was always empty (no exception, so nobody noticed).

Fix: a new `persistence/DbArrays` flattens all three shapes (`java.sql.Array` /
Java array / null); both call sites switched to it; `DbArraysSelfTest` (14 cases)
pins the three shapes down.

**Two: P-05 — requested island vs authoritative island**

Workflow 1000319's authoritative island is 100027, but the island used at runtime
comes **only from the request parameter**, and the frontend does not send an island
at all ⇒ `workflow.island_id` **never takes part in device selection** (it is now
only used to reject a mismatching request island). This
batch lands only the part that can be judged right or wrong: a requested island
that differs from the authoritative one ⇒ **400, explicitly refused**.

The rest is not done, and the cause is not "the filter was forgotten" but "the
filter never took effect" (all four at once; **corrected on re-check 2026-11-10**:
the first draft named "the node picks from the unfiltered `pool`" as the main
cause, which does not match the code):
① **the filtered content is always empty** — `islandFilter` is only set by
`withIsland(islandId)` (a **request parameter**), and the frontend does not send an
island ⇒ the island device set is empty ⇒ every capability's kept set is empty ⇒
everything falls through to the "fall back to the global pool" branch ⇒ the
candidate pool stays as it was;
② **the fallback makes the filter *look* effective** — when the island has no
device with that capability the global candidates are kept ⇒ even with an island
passed, global devices are still admitted;
③ **the id space differs per path** — on the main (driver) path `capPool` comes
from `registryPool(...)` (device_instance.id), which is the same space as the
island set ⇒ it lines up; on the **simulation path** `capPool` comes from
`loadCapabilityPool(db)` (resource_node.id) ⇒ it does not line up ⇒ the filter
necessarily misses there;
④ **pinned devices bypass the filter** — a node with an explicitly bound device
goes through GraphRunner's `pinned` branch ⇒ it neither takes part in the
capability rotation nor in the island filter ⇒ a workflow of this island can be
bound to another island's device and really use it at run time.
⇒ all four must change together (take the island from the authoritative value at
execution time / drop the global-candidate fallback / take the island set in the
current path's id space / check that pinned devices are on the same island),
otherwise the candidate pool gets emptied ⇒ regression.

Lessons (cross-project):
① **"What comes back" depends on how you fetch it, not on the type** — the same
PG array column gives `java.sql.Array` via `getArray` and `Long[]` via
`getObject`; **casting an array type inside a Map result always blows up** ⇒
everywhere the database hands back an array, go through one flattening helper
instead of casting per call site.
② **A silent empty value costs more than an exception** — the plate "possible
locations" list was always empty without throwing, so it stayed unnoticed until
the same wrong assumption exploded somewhere else ⇒ **when the same wrong
assumption shows up a second time, go back and find the first one.**
③ **"Never took effect" and "was never written" are different** — the island
filter code was there all along, and it really did act on the pool GraphRunner
uses; but **the content it filtered on was always empty** (no island ever reached
it), and when it matched nothing it **fell back to the global candidates** ⇒ seeing
"there is a filter" in the code absolutely does not imply the filter works ⇒ follow
the **actual data flow** and then ask whether the thing being filtered on is empty
at all.

**Addendum (2026-11-11): the island constraint actually takes effect (authoritative island at execution time / global fallback removed / id space aligned / pinned same-island check)**

The previous entry recorded the root cause of "the island constraint never took
effect"; these four steps fix it, with a paired set of measurements.

**One: what changed**

① **The authoritative island is used at execution time** — `WorkflowService.execute`
reads `workflow.island_id` directly; only workflows without an authoritative island
fall back to the island passed in the request (the old manual selection survives).
② **The global-candidate fallback is gone** — when the island has no device with
that capability the candidate set is empty ⇒ the node gets **no device** ⇒ it no
longer borrows a device from another island.
③ **The island set is taken in the current path's id space** — the driver path uses
device_instance.id, the simulation path uses resource_node.id ⇒ `resourceIdsOf`
converts between them (this is the fourth "two id spaces" trap).
④ **Pinned devices are checked against the island** — a device bound in the
orchestration that is not on the island ⇒ **explicitly refused** (400), never
silently swapped for another island's device.
In addition, the reason for "no device" now carries the island context (the state
machine used to say only "the candidate set is empty").

**Two: paired measurements (real kernel + real database)**

| Sample | Island | Result |
|---|---|---|
| 1255 (bound device 413, on island 100030) | 100030 | 200, runs (device is on the island) |
| 1255 | 100027 | **400 refused**, 0.25s, **no device action** |
| 1063 (device chosen by capability) | none | picks an off-island device 200167 (baseline) |
| 1063 | 100030 (no TRANSFER) | node gets no device, 0.49s fast fail, reason names the island |
| 1063 | 100027 (has TRANSFER) | picks the island's device 200167 |

**Three: lessons (cross-project)**

① **"there is a filter" needs three follow-up questions**: is the **content** being
filtered empty? is the **object** being filtered the one that is actually used? does
the **fallback** when nothing matches swallow the effect? — I tripped over all three,
and each of them looks fine on its own.
② **"explicit refusal" must happen before the action and carry a reason**: refusing
early (no command dispatched) with a readable reason ("island 100030 has no device
with capability TRANSFER") is a refusal; discovering it halfway through and leaving
only "the candidate set is empty" just makes the field think the device is broken.
③ **Verification must be designed in pairs**: testing only the "allow" case proves
nothing about the boundary (the old code allowed it too); you need a "should be
blocked" sample as well, and it has to prove that **no device was touched**
(0.25s elapsed + zero device actions), not merely that the status code changed.
④ **Before changing filter logic, measure the id space of the filtered object** —
this time I nearly wrote the "two id spaces" trap again; only after checking the
candidate id space of the driver path and the simulation path separately did it
become clear which side needed converting.
⑤ **Before claiming "this field is empty", confirm which column you queried** — I
queried `run_node.output->>'reason'`, found it empty, and nearly wrote a
**non-existent problem** into the status table; the reason actually lives in
`input->>'reason'` (`updateNodeResult` writes `input`) ⇒ before asserting "this
field has no value", confirm the table and the column; writing "we queried the
wrong column" into a governance document sends the next person to fix a bug that
does not exist.

**Addendum (2026-11-11, evening): re-validating the configuration snapshot before dispatch (preflight PF-10~PF-14); and "the device lock is not returned after quarantine is resolved"**

**One: preflight — making hidden problems explicit**

The spec §6 requires "compile/preflight/publish: Revalidate graph, capabilities, transfer
robot and reachability" ⇒ preflight gains four checks (**no execution behaviour
change**, reporting only):
- **PF-10** (ERROR) the bound id cannot be found in the resource tree ⇒ the binding
  does not take effect; at run time the node actually picks a different device by capability
- **PF-11** (WARN) the bound id is really a **device instance id** ⇒ the space does not
  match what the executor uses ⇒ the binding does not take effect
- **PF-12** (ERROR) the workflow has an authoritative island and the bound device is not
  on it ⇒ execution will be refused
- **PF-13** (ERROR) the workflow has an authoritative island but the island has no
  device with that capability ⇒ the node will go to manual at run time
- **PF-14** (WARN) the device/resource relation could not be read ⇒ this run skips the
  binding and island checks (fail-closed: report one more rather than pass silently)

**Measured on the real database**: of 10651 task bindings, **8295 store a device
instance id** (all still alive ⇒ **every one of those bindings currently does not take
effect**, touching 988 of 1038 workflows), 79 are in resource-id space (those bindings
do take effect), and 2277 exist in neither space (genuinely dangling, 490 workflows,
44 runs in the last 30 days).
⚠ What I had written earlier — that workflow 1063 "binds no device and selects by
capability" — **was wrong**: every one of its nodes does bind a device, but the bound id
is an instance id ⇒ the binding does not take effect and it degrades to capability
selection.

**Two: a real defect found on the way: the device lock is not returned after quarantine
is resolved**

The concurrency acceptance B segment reported "0/2 completed" — digging in, it was
**not** my change: the **kernel was SIGTERM'd while the device action was running** ⇒
two runs were quarantined at startup ⇒ each held a device lock ⇒ and **resolving the
quarantine did not release the device lock** ⇒ those two devices became **permanently
unavailable** (`DeviceLock.tryAcquire` requires `current_run_id IS NULL` or equal to
itself) ⇒ every later experiment waits for them forever.
The ordinary settle path does release them (`releaseRunDeviceLocks`); the **quarantine
path was missing it**.
Fix: `resolveQuarantine` releases the run's device locks when the confirmation
succeeds. (Not releasing them **during** quarantine is correct: the physical result is
unknown ⇒ the device may still be executing something.)

**Three: lessons (cross-project)**

① **"What must be said before execution" belongs in preflight** — a dead binding, a
   foreign-island binding and "no device with that capability on the island" look like
   "it ran on another device", "400 refused" and "the node went to manual" at run time,
   and **none of them tells an operator anything**; preflight is the only place where
   it can be said before touching the equipment.
② **"Silently swapping the device" is harder to diagnose than "failing outright"** —
   with a dead binding the node still runs to completion and every status is green, so
   nobody notices that the device was not the one that was bound ⇒ such problems can
   only be found by **static comparison** (preflight), never by watching the logs.
③ **Separate "code regression" from "environment residue" before believing a test
   failure** — this time the red B segment was caused by the kernel being killed, but
   the **lock not being released** found by following it is a genuine defect ⇒
   **follow a failure all the way down**; do not stop at "environmental, just re-run".
④ **After quarantine or termination, give the resources back** — releasing locks cannot
   hang only on the "normal wrap-up" path; abnormal wrap-up (quarantine) is a terminal
   state too, and a terminal state owes the resource back.

**Addendum (2026-11-11, night): two real problems found by the acceptance runs (ghost lock / acceptance budget false failure); and choreography deriving its island server-side**

**Zero, an earlier one: the full gate `ci-gate.sh --all` could not actually run as a
single command** (fixed 2026-11-11 08:16, `9b6c340`)

Section 1 (pure logic) requires the kernel to be **stopped** (the mutual-exclusion
guard in `test-all.sh`: a running kernel competes with the database self-tests for
the same rows ⇒ assertions go red at random), while 2.6/2.7 and 4.x all need the
API **alive** ⇒ each way of running it got half wrong: with the kernel up, section 1
goes red; with the kernel down, everything afterwards reports "cannot reach the API".
⇒ The cause sits in `test-all.sh`: that guard protects "the **database self-tests**
from competing with the kernel", while `--pure-only` **never touches the database**
⇒ it should not have been blocked at all. Changed to **block only the database
profile** (`[[ "${1:-}" != "--pure-only" ]]`); the pure profile now runs even with
the kernel up ⇒ `--all` can be run as one command (sections 0/1/2/2.5 measured
green in a row).

**Environmental fact when two sessions share the machine**: the other session stops
the kernel with `pkill -f "com.labos.boot.LabosApplication"` — **by class name** ⇒
a kernel I start on **another port** (18090) is killed as well ⇒ **port isolation
does not help**. ⇒ While the other session is still running tests/gates, **no
verification that needs a long-lived kernel can finish** here (the flow matrix ran
for 12 minutes with 30+ assertions green, then was cut off by the kill). ⇒ Such long
runs must wait until the other session is done and then run exclusively.

**One: the parallel-flow acceptance kept failing ⇒ two independent problems underneath**

① **Kernel side: the runtime acquire path could not reclaim a ghost lock** (fixed)
   Device 200171's lock pointed at run 1000891, and that run had **already been
   deleted** (the acceptance script deletes its runs on cleanup) ⇒ `tryAcquire`
   only accepts "empty lock or my own lock" ⇒ **nobody could ever get that device
   again**; and the only path that clears ghost locks runs **at kernel startup** ⇒
   in the runtime, nothing returns the lock when a run is deleted or finished.
   Worse, it **self-infects**: every cleanup leaves one behind ⇒ the next round
   on the same device always hangs ⇒ the same case failed three times in a row,
   looking like "the device is broken".
   **Fix**: the acquire condition gains "the lock's run no longer exists or is
   terminal ⇒ reclaimable" (a lock held by a paused/running/queued run must
   **not** be reclaimed — those runs still resume and the device is theirs).

② **Script side: the wait budget was hardcoded to 60 seconds ⇒ false failure** (fixed)
   The simulator deliberately takes 30–45 seconds per action, and the two branches
   share one device ⇒ serialized ⇒ at least 90 seconds; the script gave up after
   60 ⇒ **"still running" was judged as "did not complete"**.
   Fix: derive the budget the same way the other two parallel scripts do — read
   the `--rand-delay` upper bound from the simulator process arguments.

③ The two problems sat on top of each other, which is why the early rounds looked
   like "nothing adds up": the command was sent, the device did answer (the
   simulator log shows "finished OpenDoor" after 42 seconds), but **the script
   stopped waiting** and **the next round could not get the device**.

**Two: choreography — derive the island server-side and refuse cross-island steps**
(P-05 spec §6)

The spec requires "choreography build ⇒ Derive or require Island; validate every
resource server-side", and in reality **none of it existed**: only "steps
non-empty, ≤50 steps" was checked, the resulting transfer workflow had **no
island**, and whether the step's resource is on the roster or on the same island
as the others was **never checked**.
**Why it must be server-side**: the frontend already picks devices within one
island (Island.tsx / IslandFlow.tsx), but the endpoint is public — calling it
directly can splice devices from two islands into one "transfer workflow", and a
plate simply cannot move between islands. ⇒ the server derives the island itself,
refuses cross-island steps, and writes the derived island into `workflow.island_id`
(that workflow now has an authoritative island ⇒ the execution-time island
constraints apply to it).

**Three: lessons (cross-project)**

① **"The device is not responding" is often "the lock was not returned", not a
   broken device** — look at what `device_state.current_run_id` points at, whether
   that run still exists and whether it is terminal. What the field sees is a
   device not responding; the cause is a lock.
② **An acceptance script's wait budget must be derived from the designed duration
   of what it tests** — treating "timeout" as "failure" leads you to break
   perfectly correct kernel code (I almost did).
③ **For the same red, separate "code regression" from "environment/assertion"** —
   this time two independent problems were stacked; acting on the surface symptom
   fixes the wrong thing ⇒ **follow it down to the root**.
④ **The endpoint is public ⇒ the frontend's rule must be re-enforced server-side** —
   the frontend doing it right does not mean the backend does; calling the API
   directly bypasses every frontend constraint.

**Addendum (2026-11-11): the end-to-end test of the P-05 experiment-dispatch gate — and the real shape of "dispatch"**

Context: the last piece of P-05 is "re-validate the configuration snapshot before
dispatch" (`ExperimentExecutor.run` checks the island boundary through
`IslandBindingGate` before creating the run; an ERROR-level violation ⇒ fail-closed
exception). The self-tests (pure logic 8/0) and the preflight DB self-test passed, but
**the full dispatch chain was never exercised** — this round closes that gap.

**One: how the test was done (real kernel + real DB + real scheduler)**

Built a **single-workflow experiment**: `experiment` + `experiment_process`
(workflow 1255) + `experiment_run` (`status='queued'`) ⇒ wait for the scheduler tick to
dispatch it. Workflow 1255's 3 task nodes all bind resource 504830 (device instance 413,
**on island 100030**), so setting its authoritative island `workflow.island_id` to
**100027** produces the "out-of-island" case.

| Group | Authoritative island | Result |
|---|---|---|
| Out-of-island | 100027 | run **1003084 = `failed`**, error: "the experiment's workflow configuration no longer satisfies the island boundary: workflow 1255 PF-12, node "Seal" binds a device (resource 504830 / instance 413) that is not inside the workflow's island 100027 — execution will be refused (device ownership is decided server-side, the device cannot be swapped at execution time)" |
| Control (same island) | 100030 | run **1003085 really entered the execution path** (running → paused "1 node needs manual confirmation (the plate position cannot be trusted and must not be treated as completed)"), **the error contains no island-boundary wording at all** |

⇒ The control group is the point: **it proves the gate refuses "because of the island
boundary" rather than "refusing everything"**. A green out-of-island group alone cannot
show that the "let it through" path works.

**Two: what the test dug up on the side: the dispatch queue lives in memory**

The first attempt wrote the rows straight into the DB → the run stayed `queued` forever
and the scheduler log never even said "dispatching experiment". Following that down:

- The scheduler's dispatch queue is **in-memory** (`ExperimentScheduler.queue`); only
  `submit()` can enqueue;
- once enqueued, a run is eligible for dispatch by the tick; **writing the row directly,
  bypassing `submit()`, means it is never dispatched**;
- the other way into the queue is the **kernel start-up tail** `rehydrateQueued()`: it
  picks `experiment_run.status='queued'` up from the DB and loads it into the queue ⇒
  hence this test went "build the rows → **restart the kernel** → start-up rehydrate
  loads them → the tick dispatches".

⚠ **The first draft got this wrong; corrected (2026-11-11, evening)**: it said
"`submit()` **has no caller anywhere in the repo** ⇒ there is no endpoint that
creates/dispatches an experiment" — **that is wrong**: the grep only searched
`labos-kernel/src/main/java/` and **missed `labos-kernel/src/boot/java/`, where the
controllers live**; in fact `POST /api/experiments/{id}/run?async=1` calls
`scheduler.submit(id, overrides)` (`src/boot/.../controller/ExperimentActionController.java:383`)
⇒ **the dispatch entry point does exist**.

⇒ The correct conclusion is: **"writing the row" and "enqueuing" are two steps**, and the
normal path does both (the endpoint enqueues); **bypassing the endpoint means the run is
never dispatched** unless the kernel restarts and rehydrates it. ⇒ That is **not a defect**
but an **implicit contract of the API** (a `queued` row in the DB does not mean
"submitted") — but it is worth writing down, or it will be tripped over again.

**Three: lessons (cross-project)**

① **"The data is in the DB" ≠ "something will process it"** — when the queue lives in
   memory, inserting a row just puts it in a drawer nobody opens. The judgement has to
   land on **"who reads it"**: `submit()` has no caller, only `rehydrate` loads ⇒ the
   test method must follow the real path too (build the rows → **restart the kernel** →
   rehydrate → the tick dispatches), not "build the rows and wait".
② **When your own assertion fails, suspect the assertion first** — this time
   `grep -q "not inside the workflow's island"` did not match, and I almost went to
   change the implementation; the actual wording carries the island number ("…not
   inside the workflow's island **100027**"), so the implementation was right. **A
   phrase containing a variable value must be copied from the real wording, not written
   from memory.**
③ **`INSERT ... RETURNING` in psql prints one extra status line** — capturing the id
   with `-tAc` also swallows `INSERT 0 1` (`tr -d ' '` squashes it into `INSERT01`) ⇒
   the next SQL fails with `syntax error at or near "INSERT01"`, and **it fails as a
   syntax error, not as an empty value**, which is very hard to connect back to "a status
   line polluted the variable". ⇒ always `head -1` + strip whitespace + validate that the
   result is a number.
④ **The control group is the only evidence that the "let it through" path works** —
   testing only "the ones that must be refused are refused" cannot distinguish "the gate
   works" from "that path was never open".

**Addendum (2026-11-11, morning): the 2.7 matrix ran all 14 sections for the first time — and how those 2 reds were proven *not* to be defects**

First full run: all **14 sections** were executed, 24 minutes, **53 passed / 2 failed**,
both failures in `loopback` (back-edge across a parallel fork): "back-edge parallel did
not complete (st=running)" and "some round's parallel branches are not paired".

**One: three reversals during the diagnosis (each time "I thought I had the root cause")**

**First reversal: I thought the kernel's pinned binding was not taking effect.**
Re-running with the scene preserved, `run_node` showed `level=0` for T0/T1/T2 all with
`resource_id = 200165` — a device with `status='error'` and `online=false` — while the XML
binds 200171/200171/200206 ⇒ "the binding is ignored and it even picked a broken device".
**That conclusion was wrong**: I had looked at a **mid-run** state; once that run finished,
all three `level=0` rows were `completed` with resources 200171/200171/200206 ⇒ the pinned
binding **worked throughout**. ⇒ The `level=0` summary row is not updated until the whole
run ends (measured: every `level=0` row shares the same millisecond `actual_end`) ⇒ a
mid-run look at a summary row necessarily says `running` and cannot tell you whether the
execution was right.

**Second reversal: I thought an isolated device had been selected.**
Following that thread, `selectCapabilityPools` (the capability pool) indeed **never looks at
`device_state`** and `tryAcquireDevice` (lock acquisition) indeed **ignores the device's own
state** — both true (recorded as **P-18**) — but **that is not why loopback went red**: every
node in that section has an explicit binding and takes the pinned path, which never goes
through the candidate pool.

**Third reversal (the real cause): the wait budget was too small — the assertion was wrong,
not the flow.**
The preserved run **ended as `completed`**, all 5 `run_command` rows were `completed`, and
every device lock was returned; the script read `running` about 27 seconds before it finished.
How long should this section take? `GraphRunner`'s parallelism is **logical parallelism**
(the branches are advanced in ready-queue order, not concurrently on the wall clock) ⇒ this
section is "3 action nodes × 2 rounds" = **6 serial device actions**, 33~40 seconds each ⇒
**about 225 seconds**; but `device_budget_sec(actions=3)` grants only 60 + 3×45 = **195
seconds** ⇒ **30 seconds short**. Changed to `actions=6` (330 s); re-run: the whole order
completed and all 3 rounds had both parallel branches paired (**2/0**).

**Two: an independent real defect found on the side**

An isolated device (`status='error'`) is still in the capability pool and can still take the
lock ⇒ a node **without an explicit binding** will reliably pick it ⇒ the command cannot be
sent ⇒ the node **neither fails nor times out**, it stays `running` forever ⇒ the whole run
hangs. Recorded in the status table as **P-18** (with both fix directions and their blast
radius).

**Three: lessons (cross-project)**

① **Never draw a conclusion from a mid-run state** — the summary row (`level=0`) is not
   updated until the run ends, so a mid-run look necessarily says `running`; either wait for
   the terminal state, or look at **command-level** records (`run_command`'s `device_id` /
   `sent_at`) — "which device was it sent to, and did it go out at all" is the hard evidence.
   This time I looked at the summary row first ⇒ I produced two conclusions that were
   **both** wrong.
② **Derive an acceptance budget from the number of ACTIONS of the thing under test, not from
   the number of nodes** — this section is 3 nodes × 2 rounds = 6 actions, and parallel
   branches are dispatched one by one in the current implementation (not wall-clock parallel)
   ⇒ 6 serial actions; the budget granted 3 ⇒ it was failed for being 30 seconds short.
   **An insufficient budget and a broken implementation look identical on site** — those 30
   seconds are the entire difference.
③ **When editing scripts, locate same-named call sites by function boundary — don't use
   `str.replace(..., 1)`** — I meant to change the budget in `sec_loopback`, but
   `replace(...,1)` changed the **first** same-named call in the file (`sec_loop`) ⇒ two
   wasted rounds before I noticed. ⇒ When editing scripts in bulk, first `grep -n` every
   call site with its line number and owning function, then edit them one by one, and after
   the edit `awk` the function names against the line numbers to confirm.
