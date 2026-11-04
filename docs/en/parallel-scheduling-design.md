# Multi-Experiment Parallel Scheduling · Design

> **English edition.** The Chinese original is [`docs/多实验并行调度-设计.md`](../多实验并行调度-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 |
| Date | 2026-11-01 |
| Status | Pending review |
| Prerequisite | `实验编排-功能设计.md` |

---

## 0. Definition of the problem

**Scenario**: the night shift has to run a batch of experiments, and the lab has several islands and many devices. How
do you schedule them?

This is not a new problem, it is the classic **Job Shop Scheduling**:

| Element | Our counterpart |
|---|---|
| Job | An experiment (EXP0812) |
| Operation | A workflow |
| Machine | An island |
| Processing time | The expected duration of a workflow (`duration_model`) |
| Precedence | The order of the workflows |

**How it differs from the textbook Job Shop**:

1. Our processing times are **inaccurate** (the precision is only 10 seconds, and the fluctuation is up to 13.5×)
2. Our resources have **physical constraints** (two robot arms fighting = a real crash)
3. Our failures **cannot be undone** (once the lid is open, it is open)

So we **cannot copy an optimal-solution algorithm** (the IBM CPLEX family); we need a **conservative
feasible heuristic**.

---

## 1. Resource model

### 1.1 Two levels of resources

```
Island (island)  capacity = 1     ← the robot arm inside the island is shared, two experiments running at once would fight
 └ Device (device) capacity = 1  ← a single device can only execute one action at a time
```

### 1.2 Why the island-level capacity is 1

**Physical fact**: an island usually has only one robot arm (the `TRANSFER` capability), and it is
responsible for carrying every plate inside the island.

Two experiments running on the same island at the same time:

```
Experiment A: Incubator_2C-1 → XRobot takes the plate → puts it into Thermo ATC
Experiment B: Incubator_2C-2 → XRobot takes the plate → puts it into Rotanta
```

XRobot is called by both experiments, and **it does not know whose plate it is** — in the end it may
deliver A's plate to B's destination.

**Conclusion: the island-level capacity must be 1**, unless we can do "robot arm time slicing" (which
needs second-accurate durations, and we cannot do that today).

### 1.3 Experiment → island mapping

**The key constraint**: an experiment **has to declare explicitly which island it runs on**.

```sql
ALTER TABLE experiment ADD COLUMN island_id bigint REFERENCES resource_node(id);
```

**Why it has to be explicit**:

| Approach | Problem |
|---|---|
| Derive it back from the workflow nodes | Ordinary workflow nodes only bind a capability; the device is chosen at execution time → **the island is undetermined** |
| Take the first of the capability pool | It computes "this experiment uses 5 islands at once", while in the actual execution only 1 is used |

Once it is declared explicitly:

```java
Set<Long> required = Set.of(experiment.islandId());
```

**One experiment occupies only one island** (2.0). If a cross-island experiment really does occur, split
it into several experiments + dependencies.

---

## 2. Scheduling algorithm (2.0)

### 2.1 The overall structure

```
                        ┌─────────────────────┐
  Submit experiment ──→ │ pending queue       │
                        └──────────┬──────────┘
                                   │  sort by priority + deadline
                                   ↓
                        ┌─────────────────────┐
                        │ Scheduling loop     │  every 2 seconds
                        │ (single thread)     │
                        └──────────┬──────────┘
                                   │  find the highest-priority experiment whose island is idle
                                   ↓
                        ┌─────────────────────┐
                        │ Island lock table   │  island_id → experiment_run_id
                        └──────────┬──────────┘
                                   │  occupied successfully
                                   ↓
                        ┌─────────────────────┐
                        │ Execution thread pool│  size = min(islands, 8)
                        └─────────────────────┘
```

### 2.2 The ordering rule

```java
Comparator<ExperimentRun> order =
    // ① those with a deadline come first (EDD: Earliest Due Date)
    comparing(r -> r.deadline == null ? LocalDateTime.MAX : r.deadline)
    // ② same deadline: by descending priority
    .thenComparing(Comparator.comparingInt(ExperimentRun::priority).reversed())
    // ③ same priority: first submitted, first run (FIFO)
    .thenComparing(ExperimentRun::submittedAt);
```

**Why EDD comes before priority**:

Experiments with a deadline usually have an external constraint (for example, "this batch of samples has
to produce results today"), and missing it wastes them. Priority can be negotiated; a deadline cannot.

**Why we do not use SPT (shortest processing time)**:

SPT lowers the average waiting time, but it makes long experiments **starve** — a night batch may never
get scheduled.

### 2.3 Pseudocode of the scheduling loop

```java
while (running) {
    // ① take the executable experiments from the queue (by the ordering rule)
    List<ExperimentRun> candidates = pending.sorted(order);

    for (ExperimentRun r : candidates) {
        long island = r.islandId();

        // ② try to take the island (CAS, atomic)
        if (!islandLock.tryAcquire(island, r.id())) {
            continue;   // this island is taken, try the next experiment
        }

        // ③ check whether the devices in the island really are idle (in case the previous experiment did not release cleanly)
        if (!allDevicesIdle(island)) {
            islandLock.release(island, r.id());
            continue;
        }

        // ④ dispatch
        pending.remove(r);
        r.status(RUNNING);
        executor.submit(() -> executeExperiment(r));

        // ⑤ dispatch one at a time, or fill a whole round?
        // 2.0 fills a whole round (different islands can start at the same time)
    }

    sleep(2_000);   // one round every 2 seconds
}
```

**Filling a whole round vs one at a time**:

Filling a whole round (different islands starting in parallel) raises the throughput. But it also means
"26 experiments start at the same moment", which puts pressure on the logs and the monitoring.

**2.0 takes the middle ground**: at most `min(idle islands, 4)` are dispatched per round, to avoid an
instantaneous spike.

### 2.4 Release

```java
finally {
    islandLock.release(island, r.id());
    r.status(COMPLETED / FAILED);
}
```

**It has to be released in the finally block** — otherwise an experiment that crashes holds the island
forever.

---

## 3. Deadlock avoidance

### 3.1 The four conditions of a deadlock

| Condition | Do we satisfy it | Handling |
|---|---|---|
| Mutual exclusion | ✅ The island lock is mutually exclusive | Unavoidable (a physical resource) |
| Hold and wait | ⚠️ **Possible** | **Eliminate it** |
| No preemption | ✅ No preemption | Acceptable |
| Circular wait | ⚠️ **Possible** | Eliminate it |

### 3.2 Eliminating "hold and wait"

**The rule: an experiment requests all the resources it needs at once, all or nothing.**

```java
// 2.0 each experiment only needs 1 island → satisfied naturally
if (!islandLock.tryAcquire(island, r.id())) {
    continue;   // if it cannot be taken, nothing is held at all, no partial hold
}
```

**If cross-island experiments appear in future** (needing 2 islands):

```java
// request in ascending island_id order (a global order, eliminates circular wait)
long[] islands = required.stream().mapToLong(Long::longValue).sorted().toArray();
if (!lockAllOrNone(islands, r.id())) {
    continue;   // if not one can be taken, all are released
}
```

### 3.3 The timeout fallback

```java
// the island lock has a TTL: holding the lock longer than maxDuration (2 hours by default) releases it automatically
islandLock = new IslandLock(ttl = Duration.ofHours(2));
```

**Why there has to be a TTL**: if the execution thread exits abnormally and the `finally` never runs
(OOM, kill -9), the lock leaks permanently.

Forcing a release when the TTL expires costs "two experiments may run on one island at the same time" —
**which is better than being stuck forever**.

### 3.4 Deadlock detection (optional)

```java
// check every 30 seconds: all pending experiments are blocked by a held lock and the lock holders make no progress → alert
if (allPendingBlocked() && noActiveProgress(Duration.ofMinutes(10))) {
    alert("调度停滞：所有实验都在等锁，但持锁实验无进展");
}
```

---

## 4. Failure isolation

### 4.1 One experiment failing must not affect the others

```
Experiment A fails → the island is released → experiment C (queued) can come in
```

**Implementation**: `executeExperiment` is wrapped in try-catch-finally as a whole; on any exception it:
→1. Marks `experiment_run.status = FAILED`
→2. Releases the island lock
→3. Writes an `audit_log` entry
→4. **Does not rethrow** (it must not pollute the scheduling loop)

### 4.2 The execution thread pool

```java
ExecutorService executor = Executors.newFixedThreadPool(4);
```

**Size 4 rather than 26**:

- Running 26 experiments at the same time, each experiment also has a workflow thread pool inside
(`RUN_POOL` is 4 as well) - That would become 26 × 4 = 104 threads competing for the CPU - 4 is enough
(20 experiments on the night shift, done in 5 rounds)

**Use a bounded queue + CallerRuns**: when the queue is full the scheduling thread runs the task itself,
which forms back-pressure.

### 4.3 The relationship to the workflow threads

```
Scheduling thread pool (4)  each runs one experiment
   └ the workflow's RUN_POOL (4)  each runs one workflow
       └ node execution (sequential)
```

**Total concurrency = 4 experiments × 4 workflows = 16 runs in parallel**.

This number has to be monitored — if the CPU is saturated, drop to 2×2.

---

## 5. The state machine

### 5.1 The experiment run states

```
                   ┌──────────── cancelled ←── the user cancels
                   │
draft → queued ────┼──→ running ──┬──→ completed
        │          │      │       └──→ failed
        │          │      └──→ paused ──→ running (after a person has handled it)
        │          │
        └──────────┴──→ (the lock is held, stay in queued)
```

### 5.2 The atomicity of a state transition

```sql
-- a conditional update guarantees that only one scheduling thread can win the race
UPDATE experiment_run
SET status = 'running', started_at = now()
WHERE id = ? AND status = 'queued'
RETURNING id;
-- 0 rows returned = somebody else won the race, skip it
```

**Do not use `SELECT ... FOR UPDATE`**: under high concurrency it waits a lot; a conditional update is
lighter.

---

## 6. Integration with the existing modules

| Module | Relationship |
|---|---|
| `WorkflowService.execute()` | Each call runs one workflow and returns a `runId` |
| `device_state` | Before scheduling, check whether the devices in the island really are idle |
| Attendance mode | The experiment-level attendance is passed through to every run |
| Recovery policy | Handled inside the workflow; the experiment layer does not repeat it |
| Alerts | A workflow fails → an alert → the experiment is paused |
| Audit | One record per state transition |

---

## 7. Observability

The scheduler has to be **able to make sense of what it is thinking**:

```bash
GET /api/scheduler/status

{
  "running": 3,
  "queued": 12,
  "islandLocks": [
    {"islandId": 100033, "islandCode": "Pre-NGS", "runId": 204930, "since": "...", "expiresAt": "..."}
  ],
  "workers": {"pool": 4, "active": 3, "queue": 0},
  "lastDispatchAt": "...",
  "stalled": false
}
```

**`stalled` is the most important field**: all workers idle, the queue is not empty, but nothing was
dispatched → either a lock was not released or all the resources are taken.

### 7.1 The scheduling status interface (queue detail added 2026-11-03)

The existing interface only has counts, so the person on duty cannot see "who holds the island, and
where am I in the queue". It is completed to:

~~~json
GET /api/scheduler/status
{
  "running": 1, "queued": 3, "maxConcurrent": 4, "stalled": false,
  "started": 41, "loopStarted": true,
  "lastDispatchAt": "2026-11-03T19:50:00",
  "islandLocks": [
    {"islandId": 100033, "islandCode": "Pre-NGS", "runId": 91, "experimentRunId": 91,
     "experimentId": 812, "experimentNo": "EXP0812", "since": "...", "expiresAt": "..."}
  ],
  "queue": [
    {"position": 1, "experimentId": 813, "experimentNo": "EXP0813",
     "experimentRunId": 92, "islandId": 100033, "islandCode": "Pre-NGS",
     "priority": 50, "deadline": null, "submittedAt": "..."}
  ],
  "workers": {"pool": 4, "active": 1, "queue": 0}
}
~~~

**Field semantics**: `started` = **the cumulative number of dispatches** (number; the implementation before the change
used a boolean field of the same name meaning "whether the scheduling loop has started"; from 2026-11-03
this contract makes it a count, and that boolean semantics is kept for compatibility in
`loopStarted`).

**Ordering rule**: `queue` is ordered by **the actual dispatch order** (`deadline` ASC (no deadline
last, EDD first) → `priority` DESC → `submittedAt` ASC), the same rule as
`dispatch()` — the position has to answer "how many more do I have to wait before it is
my turn"; if the ordering rule is inconsistent the position is a lie.
`position` starts at 1; `running` = the number of experiments currently occupying an
island; `workers.queue` = the number of tasks in the thread pool that have not been picked up by a
worker yet (the pool is submitted to directly today, so it is always 0; the field is kept for future
changes).

### 7.2 "Estimated wait" on the experiment list (2026-11-03)

~~~json
GET /api/experiments/eta?limit=100
{"items": [
  {"experimentId": 813, "experimentNo": "EXP0813", "status": "queued",
   "islandId": 100033, "islandCode": "Pre-NGS",
   "queued": true, "queuePosition": 2, "estimateSec": 900, "estimatedWaitSec": 1830,
   "reason": "The island is held by EXP0812, and there is 1 experiment ahead of it"}
]}
~~~

**The algorithm** (a pure function `SchedulerEta.estimateWait` that can be unit-tested):

1. The experiment is in the **set of scheduler island-lock holders** (the in-memory state
   of "who really is running"), or the experiment table has `status=running` →
   `estimatedWaitSec=0`, `queued=false`; **it does not look at `experiment_run.status`** —
   the stale running rows would misjudge a finished experiment as "running";
2. Not in the scheduling queue and not running → `estimatedWaitSec=null` (not applicable,
   the frontend shows " —");
3. In the queue: `estimatedWaitSec = max(0, the estimated remainder of the island's current
holder) + Σ(the estimateSec of the queued experiments on the same island ahead of it)`; - the estimated
remainder of the holder = the sum of `COALESCE(estimate_sec, 60)` over the unfinished nodes in that
run's `run_node`; - `estimateSec` = the sum of `COALESCE(estimate_sec, 60)` over the workflow task nodes
of that experiment (the same estimation chain as the Gantt / time bars);
4. `reason` is written in plain language ("running" / "the island is held by EXP0812, and
   there is 1 experiment ahead of it"), and the frontend shows it directly in a tooltip.

**The limitations (put them in the page description; do not treat them as a promise)**: it does not
include changeover / cleaning / manual handling time; islands do not exclude each other, but the
`MAX_CONCURRENT` pool ceiling applies — this estimate only looks at the island locks; a holder that is
paused is estimated as "it will run to completion".


---

## 8. Boundaries and limitations

| Limitation | Value | Reason |
|---|---|---|
| Maximum steps per experiment | 500 | Beyond that, after expansion, it cannot be debugged |
| `loopCount` of a single workflow | ≤ 20 | The same |
| Island lock TTL | 2 hours | A fallback for an abnormal exit |
| Scheduling loop interval | 2 seconds | Too frequent wastes CPU, too slow gives a poor response |
| Experiment thread pool | 4 | Avoids a thread explosion |
| Experiments running at the same time | ≤ 8 | Beyond that the CPU is saturated |

---

## 9. Phases

| Phase | Content |
|---|---|
| **2.0** | Island-level mutual exclusion, EDD + priority ordering, TTL fallback, the state machine, the observability interface |
| 2.1 | Cross-island experiments (several locks requested in id order) |
| 2.2 | Automatic alert on a stall + automatic release |
| 2.3 | Multiple experiments in parallel inside one island (needs second-accurate duration data) |
| 2.4 | Priority preemption, dynamic re-ordering |

---

## 10. Why not an "optimal-solution" algorithm

| Algorithm | Why not |
|---|---|
| CPLEX / Gurobi | It needs a commercial licence; and the input data (the processing times) is itself inaccurate, so however optimal the solution is it means nothing |
| Genetic algorithms | The results are good but **not explainable** — the person on duty asks "why is this experiment 5th in the queue", and there is no answer |
| Reinforcement learning | It needs a lot of training data, and we do not have it |

**The reasons for using a heuristic (EDD + priority + FIFO)**:

1. The result is **explainable**: "its deadline is the earliest, so it runs first"
2. When something goes wrong it **can be corrected by hand** (change the priority, change
   the deadline)
3. When the processing times are inaccurate, the real gap between the optimal solution and
   the heuristic is smaller than the error in the processing times

> **In a system whose data is inaccurate, chasing algorithmic optimality is self-deception.**
> First get the resource conflicts under control (no crashing robot arms), then talk about
> optimisation.
