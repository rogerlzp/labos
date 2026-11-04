# Resource Model and Deadlock Avoidance — Design

> **English edition.** The Chinese original is [`docs/资源模型与死锁避免-设计.md`](../资源模型与死锁避免-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> Goal: **no deadlock in theory when several workflows run in parallel**.
> Cost: each allocation has to run a safety check (microsecond level, **always on, no switch** — see the R6 correction).

---

## 1. Why the current design is not enough

### Flaw 1: the granularity of the lock is wrong — it is at the "device" level

**Scenario**: B has only one entrance/exit, but internally it has several positions.

```
B (3 internal positions)
Workflow 1: plate 1 goes into position 1 of B
Workflow 2: plate 2 goes into position 2 of B      ← these two could run in parallel!
```

**If you lock "the device B", workflow 2 cannot get in** — that is wrong.

**Correct**: the **entrance/exit** of B is shared, the **internal positions** are independent.

### Flaw 2: `returnTo` only solves a single chain

**Scenario**: the ring A→B→C→A, 3 workflows.

```
Workflow 1 holds A, waits for B
Workflow 2 holds B, waits for C
Workflow 3 holds C, waits for A
→ circular wait, deadlock guaranteed
```

**2 workflows can interleave, 3 cannot.** The `returnTo` dual-lock cannot solve a ring.

---

## 2. The resource model: three layers

| Layer | Resource | Count | Sharing | Example |
|---|---|---|---|---|
| **L1 position** | Each layer position | N per device | **Independent** | `ID1183-L1`, `ID1183-L2` |
| **L2 entrance/exit** | The in/out channel of a device | 1 per device | **Shared** | `ID1183-PORT` |
| **L3 robot arm** | The robot used for transport | 1 per island | **Shared** | `ID1731` |

### Your scenario 1 explained with the three layers

```
Workflow 1 wants to deliver a plate into B:
  Takes L2 (B's entrance/exit)  ← exclusive, one plate in at a time
  Takes L1 (one of B's positions) ← picks a free position
  Releases L2                  ← lets go as soon as it has delivered, so others can deliver

Workflow 2 wants to deliver at the same time:
  L2 is taken → it waits (a few seconds)
  L2 is released → it takes it → it takes another position of B (L1) → success

Result: both plates are in B, each in its own position ✅
```

### ⚠ The invariant that has to be added (pointed out in review R3)

**One Transfer has to hold 5 kinds of resource at the same time** (robot arm + source port + source position + target port + target position + return position),
and the original text does not specify the **order in which resources of different types are acquired**.

**If it is implemented as "grab the plate first, then request the target position, and get rejected by the wait-for graph"** —
the plate is already on the robot arm, and there is **no way to "return a failure and retry"**.

#### The enforced order: the plate does not leave the source position until every request has succeeded

```
① Request the source port (L2)
② Request the source position (L1)     ← confirms the plate is there
③ Request the target port (L2)
④ Request the target position (L1)
⑤ Request the return position (L1)   ← required by D105
⑥ Request the robot arm (L3)
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   **Up to here all resources are in hand; the plate is still on the source position and can be safely abandoned at any time**
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ Pick up the plate (the plate leaves the source position)  ← this is the point of no return
⑧ Move to the target and put it down
⑨ Release the robot arm, the source port and the target port
⑩ The return position is held (until the plate comes back, see D105)
```

**If any single request fails**:

```
→ Release every resource already requested
→ The plate is still on the source position, the state is unchanged
→ Retry safely
```

**This invariant is the precondition for the "rejection" semantics to hold.**
Without it, the "rejection" of the wait-for graph cannot be carried out in the physical world.

#### Why the order is "port → position → arm"

| Constraint | Reason |
|---|---|
| **The source port comes before the source position** | A port is a channel, a position is the destination; the channel comes before the destination, which matches physics |
| **The target port comes before the target position** | Same as above |
| **The robot arm comes last** | The arm is the most expensive resource; requesting it last reduces the holding time |

**Total order**: `source port → source position → target port → target position → return position → robot arm`

**A fixed total order eliminates circular wait** — this is a finer-grained rule than "ascending id"
(ascending id is only valid within a class of resources; across types it has to be stated explicitly).

### Key point: the entrance is "action-level", not "task-level"

**The entrance is only held for the few seconds in which "the plate passes through"**, not for the whole task.

That is the same as for the robot arm — both are **transient resources**.

---

## 3. Deadlock avoidance: wait-for graph + cycle detection

### Why not the full Banker's algorithm

**The Banker's algorithm requires knowing the "maximum demand" in advance**, but:

- a workflow has **branches**, so the future is uncertain
- the durations are **not accurate**, so a safe sequence cannot be computed precisely
- each allocation costs O(n²), which is expensive

### The actual approach: the wait-for graph

**It only records "who is waiting for whom" and detects cycles.**

```
Workflow 1 wants B, B is held by workflow 2  →  edge: workflow 1 → workflow 2
Workflow 2 wants C, C is held by workflow 3  →  edge: workflow 2 → workflow 3
Workflow 3 wants A, A is held by workflow 1  →  edge: workflow 3 → workflow 1
                            ↑ a cycle has been detected!
```

**A detected cycle → this request is rejected** (the last requester waits instead of grabbing blindly).

### Why this approach works

| Comparison | Banker's algorithm | Wait-for graph |
|---|---|---|
| Has to know the maximum demand in advance | ✅ Required | ❌ Not required |
| When a branch changes | It has to be recomputed | It evolves dynamically |
| Computation | O(n²) each time | O(E) to detect the cycle |
| Can it avoid deadlocks 100% of the time | In theory yes | **Yes** (it only rejects requests that would close a cycle) |

**The wait-for graph guarantees that there is no deadlock** — because every request that could close a cycle is rejected.

**Cost**: some requests that "could have got through" are rejected wrongly (conservative).

### The algorithm

```
tryAcquire(resource R, execution P):
  ① Can it be taken directly? → take it, return success
  ② Cannot take it, find the holder Q
  ③ Add the edge P → Q to the wait-for graph
  ④ DFS to check whether starting from P one can get back to P
     - there is a cycle → remove the edge, return "would deadlock, rejected"
     - there is no cycle → return "waiting"
  ⑤ When it is released, wake the waiter
```

---

### ⚠ R5 correction: `returnTo` must be configured explicitly, there is no default

**Review comment**: defaulting it to `source` would **fail silently**.

**Scenario**:

```
Workflow: take a plate out of A → go to B to execute → put it into C (not back into A)
returnTo not configured → default = A → A's position is wrongly reserved
  → one position is wasted, and C may not be able to get one
```

**The rule**: **`returnTo` is mandatory**, an error is raised when it is missing (validated at orchestration time).

**Where the validation lives**: when the workflow is saved, every `Transfer` node is checked;
a node without `returnTo` makes the save fail, with the message "the final position of the plate has to be declared".

**Cost**: one more field to fill in at orchestration time.
**Benefit**: no silent failures caused by a default value — this kind of bug is the hardest to find (the system runs "normally", only the positions are wrong).

---

## 4. Walkthrough of your scenario 2: A→B→C→A, 3 workflows

### The initial state

```
Workflow 1 holds A
Workflow 2 holds B
Workflow 3 holds C
```

### Workflow 1 wants B

```
B is held by workflow 2
Add an edge to the wait-for graph: workflow 1 → workflow 2
DFS from workflow 1: workflow 1→workflow 2, workflow 2 is not waiting for anyone → no cycle
Conclusion: waiting is allowed
```

### Workflow 2 wants C

```
C is held by workflow 3
Add an edge: workflow 2 → workflow 3
DFS: workflow 2→workflow 3, workflow 3 is not waiting → no cycle
Conclusion: waiting is allowed
```

### Workflow 3 wants A

```
A is held by workflow 1
Add an edge: workflow 3 → workflow 1
DFS: workflow 3→workflow 1→workflow 2→workflow 3  ← **a cycle!**
Conclusion: **rejected**, workflow 3 has to wait (it is not added to the wait-for graph, it fails and retries)
```

**Result**: workflows 1 and 2 keep waiting, workflow 3 leaves the waiting state.
Only after workflow 1 has taken B, finished and released A can workflow 3 ask for A.

**No deadlock ✅**

### The case of 2 workflows

```
Workflow 1 holds A, wants B (B is held by workflow 2) → add the edge 1→2, no cycle, wait
Workflow 2 holds B, wants ... (does not need A) → finishes, releases B
Workflow 1 takes B → finishes
```

**2 get through, 3 are rejected — this matches your judgement.**

---

## 5. Detection is always on, there is no switch (R6 correction)

**The original design wanted a `LABOS_DEADLOCK_GUARD=auto/on/off` switch; it was cancelled after the review.**

### The reason it was cancelled

| Problem | Explanation |
|---|---|
| **The overhead is small anyway** | Wait-for graph detection is an in-memory DFS, microsecond level |
| **COUNT has a race** | Two executions start at the same time, both find that they are "the only one" → neither detects anything → a miss |
| **Parallelism inside a single run is missed** | An experiment contains a `parallelGateway` internally; `running = 1` although there really is concurrent contention |

### Working the overhead out

```
Number of resources n ≤ 10, number of edges E ≤ 20
One DFS < 10 microseconds
One Transfer involves 6 resource requests → 60 microseconds
```

**Compared with a single device action (150 ms to 30 minutes)**, 60 microseconds can be ignored.

**Introducing a race and misses in order to save 60 microseconds is not a good trade.**

### If a downgrade really is needed in future

**No environment-variable switch**; instead **keep a `DeadlockGuard` interface in the code**,
whose default implementation is "full detection", and into which a "no-op implementation" can be injected during tests.

**That gives flexibility in testing without introducing a runtime race.**

## 6. Implementation in batches

### Batch 1: the resource model (1 week)

| Task | Output |
|---|---|
| Model the three layers L1/L2/L3 | `source_type` of `resource_booking` distinguishes `position`/`port`/`robot` |
| An "action-level" lock for the entrance/exit | Take it before the move and release it after, not held for the whole task |
| Position selection (choosing a free position automatically) | Reuse `position_state` |
| Keep `returnTo` | Reservation of the return position |

**Acceptance**: your scenario 1 — two plates enter B at the same time (different positions).

### Batch 2: deadlock detection (1 week)

| Task | Output |
|---|---|
| Wait-for graph data structure | In-memory `Map<Long, Set<Long>>` |
| Cycle detection (DFS) | `wouldDeadlock(P, Q)` |
| Rejection + retry | After a rejection, wait 200ms and retry, at most N times |
| Switch | `LABOS_DEADLOCK_GUARD` |

**Acceptance**: your scenario 2 — 3 workflows go A→B→C→A and **do not deadlock** (the third is rejected, the first two can finish).

### Batch 3: the safety net and observability (3 days)

| Task | Output |
|---|---|
| Wait-for graph snapshot interface | `GET /api/deadlock/graph` |
| Forced release on timeout | Locks are cleared after 2 hours (already there) |
| Alert | "N cycles detected, requests rejected" |

---

## 7. The costs and the trade-offs

### The costs that are explicitly accepted

| Cost | Explanation | Why it is accepted |
|---|---|---|
| **False rejection** | Some requests that could have passed are rejected (conservative) | A deadlock costs more |
| **Complexity** | Three resource layers + the wait-for graph | Production-grade parallelism has to pay it |
| **Hard to debug** | When something goes wrong you have to inspect the wait-for graph | A snapshot interface is provided |

### What is explicitly not done

| Not done | Why |
|---|---|
| The full Banker's algorithm | It requires knowing the maximum demand in advance, and a workflow with branches makes that impossible |
| Preemption (forcibly taking a resource away) | The plate may really be on the robot arm, and preempting it would smash the plate.<br>**Note**: this is not "the Banker's algorithm demands preemption" — the Banker's algorithm itself is designed for non-preemptible resources;<br>the reason it is not used is "the maximum demand cannot be known in advance" (see the R1 correction) |
| Automatic release of a deadlock | Same as above: only an alert, no automatic handling |

---

## 8. In one sentence

**Resources have to be split into three layers**: position (independent), entrance/exit (shared at action level), robot arm (shared at action level).

**Deadlocks are handled by detecting cycles in the wait-for graph**: it only records "who is waiting for whom", and requests that would close a cycle are rejected.

**For a single experiment the switch is off**: without concurrency there is no deadlock, so this overhead is not paid.

**Build the resource model first, then the detection** — if the resource granularity is wrong, no detection algorithm can rescue it.
