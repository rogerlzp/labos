# Multi-Experiment Parallel Scheduling · Dynamic Programming and Rolling Horizon

> **English edition.** The Chinese original is [`docs/多实验并行调度-动态规划与滚动排程.md`](../多实验并行调度-动态规划与滚动排程.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.2 |
| Date | 2026-11-01 |
| Status | Pending review |
| Prerequisite | `多实验并行调度-设计.md`, `实验编排-功能设计.md` |
| What this document answers | Can we use dynamic programming? How do we let several experiments run safely in parallel? |

---

## 1. First, clarify the concept: three kinds of "dynamic programming"

The term means completely different things in different contexts, so they have to be
separated first:

| Term | Academic meaning | Do we use it |
|---|---|---|
| **Dynamic programming (DP)** | Solving for the optimum with a state-transition equation (Bellman) | ❌ The state explodes, it is unusable |
| **Dynamic scheduling** | Adjusting the schedule at runtime according to the actual state | ✅ **This is what should be done** |
| **Rolling horizon** | Each time schedule only one window into the future, and re-schedule in a loop | ✅ **The concrete implementation** |

**Conclusion: do not do DP; do rolling-horizon dynamic scheduling.**

The reasons are in §2, the approach in §3.

---

## 2. Why pure DP is unusable

### 2.1 The size of the problem

The DP state of a standard Job Shop:

```
f(S, t₁, t₂, ..., t_M) = the minimum makespan under the scheduled experiment set S and the per-island availability times t_i

number of states ≈ 2^N × (number of discretised time slots)^M
```

| Parameter | Our value |
|---|---|
| N (number of experiments) | 20~50 on the night shift |
| M (number of islands) | 26 |
| Time slots | Even with minute granularity, one shift of 8 hours = 480 slots |

`2^50 × 480^26` — an astronomical number.

### 2.2 Even if it could be computed, the input is inaccurate

The premise of DP is that **the processing times are accurate**. Our reality:

```
Method             p50      p90      Fluctuation ratio
Transfer          10.0s    40.0s     4.0×
RunProtocol       25.0s   250.0s    10.0×
Run               18.5s   250.0s    13.5×
```

**A fluctuation ratio of 13.5× means**: the real gap between the optimal solution and the
second-best solution is smaller than the error in the processing times themselves.

> Computing an optimal solution with inaccurate data is like weighing a breathing thing on
> an exact scale.

### 2.3 The only special case where DP works (and why we still do not use it)

If all of the following hold:

- Each experiment occupies only 1 island (✅ our 1.0 constraint)
- There is no ordering dependency between experiments (✅ fully independent in 1.0)
- The only objective is to minimise the makespan

then it degenerates into **P||Cmax (parallel machine scheduling)**, which has a DP
solution and a PTAS approximation.

**But we still do not use it**, because:

| Reason | Explanation |
|---|---|
| The objective is not only Cmax | There is also the deadline satisfaction rate and the balance of device utilisation |
| The site changes | A device breaks or an experiment fails, and the DP solution is void immediately |
| Not explainable | The person on duty wants "why is it 5th", not "Pareto-optimal" |

---

## 3. What should be done: rolling-horizon dynamic scheduling

### 3.1 The core idea

```
Do not schedule all the experiments at once.
Each time schedule only one window into the future (for example 2 hours); once it has run,
schedule the next segment.
```

This is the standard approach in the MES / APS field; in essence it is **planning
dynamically** — every round is the optimal decision for the current state, not a one-off
optimum for the whole cycle.

### 3.2 The three key mechanisms

#### ① The commitment window

```
An experiment that has already started → never change it
```

**Why**: the experiment is already driving the devices; changing it is useless, and it
may even scramble the plate positions.

#### ② The frozen zone

```
The schedule within the next 15 minutes → do not touch it unless there is an urgent insertion
```

**Why**: it gives the floor preparation time. A schedule that keeps changing is the same
as no schedule.

#### ③ Event-triggered rescheduling

| Event | Action |
|---|---|
| A new experiment is submitted | Insert it into the queue; it is considered at the next rescheduling |
| An experiment completes | Release the island, and immediately try to dispatch the next one |
| An experiment fails | Release the island + alert + reschedule |
| A device goes offline | Mark that island as unavailable + reschedule the affected experiments |
| A person changes a priority | Reschedule immediately |

**Only polling on a timer is not what makes it dynamic** — the event-driven response is
faster.

### 3.3 The complete algorithm

```java
/**
 * The main rolling-scheduling loop.
 * Runs once every 2 seconds, or when an event triggers it.
 */
void rollingSchedule() {

    // ── ① collect the current state ──────────────────────────
    List<Island> islands = loadIslands();               // 26 of them
    Map<Long, Instant> islandFree = loadIslandFreeAt(); // when each island becomes available
    List<ExpRun> committed = loadCommitted();           // started, must not be touched
    List<ExpRun> queue = loadQueued();                  // waiting to be scheduled

    // ── ② sort: EDD → priority → FIFO ──────────────────────
    queue.sort(EDD_THEN_PRIORITY_THEN_FIFO);

    // ── ③ try to assign one by one (greedy + earliest free island) ──
    for (ExpRun r : queue) {
        Duration dur = estimate(r);                     // use p90, not p50

        Island best = null;
        Instant bestStart = null;

        for (Island isl : islands) {
            if (!isl.available()) continue;             // the device is offline

            Instant start = islandFree.get(isl.id());
            Instant end   = start.plus(dur);

            // the deadline constraint: if it would be exceeded, do not choose this island
            if (r.deadline != null && end.isAfter(r.deadline)) continue;

            if (bestStart == null || start.isBefore(bestStart)) {
                best = isl;
                bestStart = start;
            }
        }

        if (best == null) {
            // no island can finish on time → mark it at risk, but it still queues
            markAtRisk(r);
            continue;
        }

        r.assign(best, bestStart, bestStart.plus(dur));
        islandFree.put(best.id(), bestStart.plus(dur));  // push this island back
    }

    // ── ④ dispatch the ones that "should start now" ──────────
    Instant now = Instant.now();
    for (ExpRun r : queue) {
        if (r.assignedStart().isBefore(now.plus(DISPATCH_LOOKAHEAD))) {  // dispatch 10 s early
            if (islandLock.tryAcquire(r.islandId(), r.id())) {
                dispatch(r);
            }
        }
    }
}
```

### 3.4 Why this is "greedy" and not "optimal"

**The cost of being greedy**: it does not guarantee a global optimum.

**The benefit of being greedy**:

| Benefit | Explanation |
|---|---|
| **Explainable** | "Its deadline is the earliest, so it gets the earliest island" |
| **Incremental** | A new experiment only affects what comes after it; what came before does not move |
| **Interruptible** | Stopping at any moment leaves the existing schedule valid |
| **O(N·M)** | 50 experiments × 26 islands = 1300 computations, in milliseconds |

**The key insight**: under a **rolling** framework, "a locally optimal step" is more
useful than "a one-off global optimum" — because the premise of a one-off optimum (that
the future is known) does not hold in reality.

### 3.5 Improvement: local repair instead of a full re-schedule

The problem with a full re-schedule: **schedule jitter** (the same experiment keeps
changing position between two schedules).

The improvement:

```java
// only re-order the part affected by the event
Set<Long> affected = findAffected(event);
// a new experiment → only affects what comes after it
// an experiment failure → only affects the one on the same island
// a device going offline → only affects the ones that use that island

// everything else keeps its original schedule (it is already in committed)
```

**Benefit**: the schedule stability improves a great deal, and the person on duty no
longer sees "the schedule keeps changing".

---

## 4. Standing on the shoulders of others: what each of them contributed

### 4.1 A line-by-line comparison

| Capability | Peer product A | Peer product B | LabOS | Us |
|---|---|---|---|---|
| The order of workflows within one experiment | ✅ Process list | ✅ | 🟡 Manual | ✅ |
| Experiment variables | ❌ | 🟡 Parameter table | ❌ | ✅ The design is settled |
| Loops across workflows | ❌ | ❌ | ❌ | ✅ The design is settled |
| Visualisation of the schedule | ❌ | ✅ **The core selling point** | ❌ | ✅ Dry-run scheduling |
| Multiple experiments in parallel | ❌ | 🟡 Serial on a single device | ❌ | ✅ Island-level mutual exclusion |
| Dynamic re-ordering | ❌ | ❌ | ❌ | ✅ Rolling horizon |
| Resource conflict early warning | ❌ | 🟡 Visible while scheduling | ❌ | ✅ Reported by the dry run |
| Deadlock protection | — | — | — | ✅ All-or-nothing + TTL |

### 4.2 What we take from each of them

**What we take from peer product A**:

- **The simplicity of composing Processes**: one experiment is just a few workflows run in
  sequence, no complicated DAG
- This "simplicity" is deliberate — a laboratory operator does not need to understand
  graph theory

**What we take from peer product B**:

- **The product form of the schedule view (Schedule View)**: visualise the future instead
  of waiting for it to happen
- Our "dry-run scheduling" is a simplified version of it

**What we take from LabOS**:

- **The BPMN canvas expresses the inside of a workflow**: nodes, edges, gateways
- Our workflow designer is built on bpmn-js

**What we add (none of the three has it)**:

| What we add | Why it has to be added |
|---|---|
| **Experiment variables** | Running the same workflow at different temperatures should not mean duplicating the workflow |
| **Loops across workflows** | "3 workflows × 5 rounds" is the norm on the night shift |
| **Island-level resource mutual exclusion** | Two robot arms fighting is a physical accident, not a logic error |
| **Rolling re-ordering** | The site always changes; a schedule that never moves after it is made is a fake one |
| **Resource conflict early warning** | Tell you at the dry-run stage that there will be a collision, instead of halfway through the run |

### 4.3 What we do not do (equally important)

| Not done | Reason |
|---|---|
| Dragging the Gantt chart to re-order the schedule | Peer product B has it, but our processing times are inaccurate, so dragging it would be fake anyway |
| An automatic optimisation algorithm (CPLEX/GA) | The data is inaccurate + not explainable |
| Multiple experiments in parallel inside one island | It needs second-accurate duration data; the original implementation only has 10-second precision |
| Nested loops | The number of steps explodes after expansion |
| Conditional blocks (if/else) | Use BPMN gateways at the workflow layer, to avoid expressing conditions in two places |

> **Restraint is also design**: every item we do not do is there because it would bring the
> risk of "it looks like it can be done, but it cannot be done accurately".

---

## 5. Three decisions that have to be made

### Decision 1: an experiment has to declare `island_id` explicitly

| Option | Problem |
|---|---|
| Derive the island from the workflow nodes | Ordinary nodes only bind a capability; the device is chosen at execution time → **the island is undetermined** |
| Take the first of the capability pool | It computes "this experiment uses 5 islands", while only 1 is actually used |
| **Declare it explicitly** | ✅ The scheduler can statically compute the resource occupancy |

**Cost**: the user has to choose the island. **Benefit**: the schedule is predictable.

> **Recommendation: adopt the explicit declaration.** In the interface, default to "the
> island with the most devices", to reduce the operating cost.

### Decision 2: 1.0 uses island-level mutual exclusion (parallelism = number of islands)

| Option | Parallelism | Risk |
|---|---|---|
| **Island-level mutual exclusion** | = the number of islands (26) | No crashing robot arms |
| Parallelism inside an island | Higher | It needs second-accurate durations + plate-level tracking, and we can do neither today |

**Cost**: at most 26 experiments run at the same time with 26 islands.

**Benefit**: **crashing robot arms never happen**.

> **Recommendation: adopt island-level mutual exclusion.** This is the safety floor. After
> accumulating 3 months of real duration data, evaluate parallelism inside an island.

### Decision 3: 1.0 does not support dependencies across workflows (the `runAfter` field
is kept but unused)

| Option | Complexity | Benefit |
|---|---|---|
| Total order | Low | It already covers 99.7% of the scenarios (from the `run_mode` statistics of the original implementation) |
| Supporting a `runAfter` DAG | High (it needs topological sorting + cycle detection + partial failure handling) | 1.0 does not need it |

**Cost**: for now it is not possible to express "workflows A and B run in parallel, and C
runs once both are done".

**Benefit**: the executor stays simple, and errors are easy to investigate.

> **Recommendation: a total order for 1.0.** If a real parallel requirement appears, use
> "split it into two experiments + island-level parallelism" instead.

---

## 6. The implementation route (1.0)

| No. | Content | Depends on | Estimate |
|---|---|---|---|
| 1 | The variable table in the frontend | None | 0.5 days |
| 2 | The loop block UI | None | 0.5 days |
| 3 | The `${var}` substitution engine | 1, 2 | 0.5 days |
| 4 | The experiment executor (expansion + substitution + sequential execution) | 3 | 1 day |
| 5 | The island lock + the scheduling loop | 4 | 1 day |
| 6 | The dry-run scheduling interface | 5 | 0.5 days |
| 7 | The schedule page | 6 | 0.5 days |
| 8 | The observability interface `/api/scheduler/status` | 5 | 0.5 days |

**About 5 days in total**.

---

## 7. The validation criteria

When 1.0 is done, it has to be able to answer:

| Question | How it is validated |
|---|---|
| Will two experiments fight over the same island? | Submit 2 experiments on the same island → the second one goes to queued, and it starts automatically after the first one completes |
| Will the robot arms fight? | The same island never runs at the same time (the island lock) |
| Will an experiment failure leave the island stuck? | Kill the process → the TTL expires and releases it → the next one can come in |
| Can the schedule be explained? | `/api/scheduler/status` shows the holder and the expiry time of every lock |
| Is the variable substitution correct? | Substitute `temp=37` into the workflow, and see it in the resulting `run_command.payload` |
| Is the loop expansion correct? | `loopCount=3` → 3 runs are generated, and `experiment_run_step.loop_index` is 0/1/2 |
