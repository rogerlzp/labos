# Concurrency Deadlock Analysis and Countermeasures

> **English edition.** The Chinese original is [`docs/并发死锁分析与对策.md`](../并发死锁分析与对策.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> The scenario you pointed out is a **real deadlock**, not a theoretical problem.
> This article analyses every possible form of deadlock and sets out the countermeasures.

---

## 1. The scenario you pointed out: the plate cannot go home

### Sequence

```
Experiment 1: A takes out a plate → robot arm carries it to B → B executes → preparing to put it back into A
Experiment 2: A takes out another plate → occupies that position of A

Result: Experiment 1 wants to put the plate back into A, but the position is occupied → stuck
```

### Why it happens

**Root cause: when Experiment 1 took the plate from A, it only reserved "A's current layer position", not "the position where it has to be put back".**

```
① Experiment 1 takes the plate: A's L1 position → now empty
② Experiment 2 sees that L1 is empty → puts a plate into L1     ← the conflict is here
③ Experiment 1 comes back: L1 is occupied, nowhere to put it
```

**This is not "the lock did not take effect", it is "the semantics of the lock are wrong"**:
the position lock protects "whether this position holds a plate right now", not "where this plate will go back to".

---

## 2. Three forms of deadlock

### Form 1: the return position is occupied (the one you pointed out)

```
A ──take plate──> robot arm ──> B        A's position is occupied by another execution
                          ↓
                   must go back to A     ← stuck
```

**Characteristic**: a one-way resource dependency, stuck on the "return leg".

### Form 2: circular wait (the classic deadlock)

```
Experiment 1: reserves A first, then wants B
Experiment 2: reserves B first, then wants A

Experiment 1 holds A and waits for B, Experiment 2 holds B and waits for A → waiting for each other
```

**Characteristic**: each of the two executions holds half of the resources.

**Current state**: `PositionLock.tryReserveTransfer` requests in **ascending id** order,
which already eliminates this one (the "circular wait" of the four deadlock conditions).

**But**: that only covers the atomic operation of "moving two positions in one go".
If the workflow is "move X first, then move Y", **a circular wait across steps is still possible**.

### Form 3: the robot arm itself is occupied

```
Experiment 1: the robot arm is halfway through the move, waiting for B to finish
Experiment 2: wants the robot arm to move its own plate → waits
```

**Characteristic**: the robot arm is a serial resource; a long task holding it affects the other experiments.

**This is not a deadlock** (the robot arm will eventually release it), but it does **slow things down badly**.

---

## 3. Countermeasures

### Countermeasure 1: reserve the return position when the plate is taken (solves form 1) ★

**Core idea**: when the plate is taken, **lock "the position this plate will go back to" at the same time**.

```java
// The current logic
tryReserve(sourceId, runId, ttl);          // reserves the source position only
// Take the plate, transport it, execute...
putPlate(targetId, plateId, runId);        // put it into the target

// It should become
tryReserveTransfer(sourceId, returnSlotId, runId, ttl);
// ↑ reserves the source position and the return position at the same time
//   (the existing dual-lock API, ordered by id to prevent deadlock)
```

**How `returnSlotId` is determined**:

| Case | Return position |
|---|---|
| Take out a plate → execute → **return to the original position** | The original position itself |
| Take out a plate → execute → change position | The target position |
| Take out a plate → execute → anywhere | **Reserve an empty position** (the loosest option) |

**Key point**: **decide "where this plate finally goes" when the workflow is designed**, not when it runs.

**Implementation**: add `returnTo` to the node parameters:

```json
{
  "method": "Transfer",
  "params": {
    "source": "ID1775",
    "target": "ID1183",
    "returnTo": "ID1775"        ← explicitly declares the return position
  }
}
```

**If `returnTo` is not configured**: it defaults to `source` (where it came from is where it goes back to).

### Countermeasure 2: a reservation timeout must be extendable

**Problem**: Experiment 1 is holding the return position, but B takes 30 minutes, so the position is released when the TTL expires.

**Current state**: the TTL of `PositionLock` is 30 minutes (`DEFAULT_TTL`).

**Risk**: for a workflow that incubates for 40 minutes, the return position gets taken half-way.

**Countermeasure**:

```java
// Renew on every heartbeat / progress report
lock.extendTtl(positionId, runId, Duration.ofMinutes(30));
```

**Or**: compute the TTL dynamically from the estimated workflow duration (`duration_model` holds the `base` number of seconds).

### Countermeasure 3: when the reservation fails, "apply" instead of "fail"

**Today**: `putPlate` cannot write → reports `NO_EMPTY_POSITION` → the execution fails.

**Better**: **queue and wait** (paired with countermeasure 1, under normal circumstances it never has to wait).

```java
if (putPlate(...) == NO_EMPTY_POSITION) {
    // The reserved position was taken (the TTL expired or the data is wrong)
    // Find an empty position on a device of the same kind, and "put it down somewhere else"
    Find a substitute position → update the run record → continue
}
```

**At least it does not hang**, at the cost of the plate possibly not returning to the expected position (this has to raise an alert).

### Countermeasure 4: the same applies across islands

**Note**: form 1 is not limited to the same device.

```
Experiment 1: takes a plate on island A → carries it to island B to execute → has to go back to island A
Experiment 2: that position on island A is occupied
```

**The countermeasure is the same**: reserve the return position when the plate is taken.

### Countermeasure 5: deadlock detection (safety net)

**All of the above is prevention**. If it still gets stuck, we need **detection + breaking**:

```sql
-- Find the executions that "hold a position but have not moved for a long time"
SELECT ps.reserved_by_run, r.status,
       EXTRACT(EPOCH FROM (now() - ps.reserved_until)) AS 超时秒
FROM position_state ps JOIN run r ON r.id = ps.reserved_by_run
WHERE ps.reserved_by_run IS NOT NULL
  AND ps.reserved_until < now()
  AND r.status = 'running';
```

**Alert**: when one of these is found, push a "suspected deadlock" so that a human can deal with it.

**Do not release it automatically** — the plate may really be on the robot arm, and an automatic release would only make things worse.

---

## 4. The actual risks in the current code

### Already protected

| Scenario | Protection |
|---|---|
| Two plates in the same position | Position lock (atomic reservation) |
| Moving two positions in one go | `tryReserveTransfer` ordered by id (prevents circular wait) |
| Two executions commanding the same device | Device lock |
| Variable cross-talk | ThreadLocal (already fixed) |

### Not protected (real risks)

| Scenario | Consequence | Severity |
|---|---|---|
| **The return position is occupied** | The execution gets stuck on the last step | 🔴 High |
| **Circular wait across steps** | Two executions wait for each other | 🟠 Medium |
| **Premature release when the TTL expires** | The plate's position is taken, the data becomes inconsistent | 🟠 Medium |

---

## 5. Implementation proposal

| Order | Task | Estimate | Effect |
|---|---|---|---|
| 1 | **Add `returnTo` to the node parameters** | 1 day | Lets the workflow declare the return position |
| 2 | **Dual-lock when taking the plate (source + returnTo)** | 1 day | Cures form 1 at the root |
| 3 | **Compute the TTL dynamically from the workflow duration** | 0.5 day | Prevents long workflows from expiring half-way |
| 4 | **Deadlock detection + alerting** | 1 day | Safety net, no automatic handling |
| 5 | Find a substitute position when the reservation fails | 2 days | Degraded fallback (optional) |

**Items 1 and 2 are the core**; once they are done, form 1 is gone.

---

## 6. One trade-off that has to be stated

**Reserving the return position lowers concurrency**:

```
Experiment 1 holds A's take-out position + the return position = it occupies two positions
  → one position fewer is available to Experiment 2
```

**This is a necessary cost** — without the reservation there would be a deadlock, and a deadlock is more expensive (it needs a human).

**If extreme concurrency is really wanted**: the workflow can explicitly declare "do not reserve" (`reserveReturn: false`),
and **the person doing the orchestration carries the risk themselves**. On by default.

---

## 7. In one sentence

**The deadlock you pointed out is real**, and the root cause is not "the lock did not take effect" but **"the semantics of the lock are not enough"**:
the position lock only cares about "is there a plate right now", not "where does this plate go back to".

**The solution**: **reserve the return position at the same time** as taking the plate (one dual-lock),
compute the TTL dynamically from the workflow duration, and add deadlock detection as the safety net.

**This cannot be solved by adding a lock; the workflow semantics need one more field** (`returnTo`).
