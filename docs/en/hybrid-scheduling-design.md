# Hybrid Scheduling Solution — Design

> **English edition.** The Chinese original is [`docs/混合调度方案-设计.md`](../混合调度方案-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **Before start: main-path coarse screening (a simplified banker's algorithm)**
> **During execution: wait-graph fine control (cycle detection)**
>
> The coarse screening is responsible for "telling the user in advance whether it can
> run"; the fine control is responsible for "guaranteeing that there is no deadlock".
> A wrong coarse screening does not affect correctness, it only makes the experience
> slightly worse.

---

## 1. Why there are two mechanisms

### The problem with a pure wait graph

**It can only "discover halfway through the run that it cannot get the resource"**:

```
The user presses "Start" → the system accepts it → it runs for 5 minutes → it gets stuck
trying to take a device → it waits forever
```

**What the user feels**: **"nothing happened when I pressed the button"**.

### The problem with a pure banker's algorithm

**It requires knowing the maximum demand in advance**, which is impossible for workflows
with branches (see `为什么不用银行家算法.md`).

### The positioning of the hybrid

| Mechanism | What it solves | What happens if it computes wrongly |
|---|---|---|
| **Main-path coarse screening** | "Whether there are enough resources is known at the moment of starting" | **Only a poor experience** (queue next time), correctness is unaffected |
| **Wait-graph fine control** | "Guaranteeing that there is no deadlock" | **It must be correct** (this is the safety floor) |

**The key point**: **the coarse screen may be wrong; the fine control may not.**

---

## 2. The definition of the main path

### Three ways to define it (in priority order)

| Way | Who decides | Applicable when |
|---|---|---|
| **① Marked by hand at orchestration time** | The user | The most accurate, recommended |
| **② The branch that uses the most resources** | Automatic in the system | When there is no manual marker |
| **③ The branch with the most nodes** | The system fallback | When the branch complexity is close |

### ① Marking it by hand (recommended)

At orchestration time, mark on the gateway node which branch is the main path:

```json
{
  "nodeType": "exclusiveGateway",
  "mainPath": "branch-sample",     // designates the main path
  "branches": [
    {"id": "branch-sample", "label": "with a sample"},
    {"id": "branch-empty", "label": "empty plate"}
  ]
}
```

**Why marking it by hand is recommended**:

- **The system does not know which path is the normal case in business terms**
- For example "with a sample" happens 95% of the time, and "empty plate" is the exception
  branch
- Only the person doing the orchestration knows

### ② Automatically picking "the one that uses the most resources"

**When there is no marker, it is chosen by the algorithm**:

```
For each branch, compute the size of the set of devices it needs
Choose the largest one as the main path
```

**Rationale**: computing with the heaviest one makes the coarse screening
**conservative** — there will never be a "it passed the screening but in reality it is not
enough".

**Cost**: conservative → a slightly lower degree of concurrency.

### ③ The most nodes (the fallback)

**When the branch resource requirements are the same**, pick the longest path.

**It is only there to give the algorithm a deterministic result**; it does not mean it is
more accurate.

### How the "resource requirement" on the main path is computed

**The main path = one definite path**, so the resource requirement is **determinate**:

```
Main path: start → incubator → centrifuge → plate reader → end
Resource requirement:
  positions: {some free slot of the incubator, some free slot of the centrifuge, some free slot of the plate reader}
  devices: {incubator, centrifuge, plate reader}
  robot arm: {the robot arm inside the island}
```

**Note**: a position is "a free slot of some class of device", not a specific one (it is
not known at the moment of starting).

---

## 3. The coarse-screening algorithm (a simplified banker's algorithm)

### The input

```
The set of running executions: for each one
  - the current node
  - the resource requirement of the remaining main-path nodes
  - the resources already held

The execution that is about to start:
  - the resource requirement of all main-path nodes
  - the resources already held (usually empty)
```

### The algorithm

```
① Treat the "device" as the resource unit (do not go down to position granularity)
   —— there are too many positions, it is complex to compute, and positions are usually
      sufficient

② Compute the "remaining requirement" R_i of each execution

③ Compute the available resources A = total resources - allocated

④ The banker's safety check:
   the work vector W = A
   the completion flag done[] = false
   loop:
     find i such that done[i]=false and R_i ≤ W
     if one is found:
       W += the resources already held by that execution
       done[i] = true
     if none is found and some are still unfinished → **unsafe**, reject
     all done → **safe**, allow
```

**This is the standard banker's algorithm**, except that the resource unit is the
"device" rather than the "position".

### Why the resource unit is the "device" and not the "position"

| By device | By position |
|---|---|
| Few (10 devices) | Many (80 positions) |
| The algorithm is fast | The algorithm is slow |
| Sufficient (the device is the main bottleneck) | Over-refined |

**Position conflicts are handled by the fine control (the position lock)**; the coarse
screening does not deal with them.

### The output of the coarse screening

```json
{
  "canStart": true,
  "reason": null,
  "estimatedWait": null
}
```

or

```json
{
  "canStart": false,
  "reason": "Not enough resources: the incubator is held by 2 runs",
  "estimatedWait": "about 30 minutes (waiting for one incubation to finish)",
  "blocking": [{"runId": 123, "device": "ID1183", "until": "..."}]
}
```

**How `estimatedWait` is computed**: it can be computed along with the coarse screening —
find "the execution that can finish soonest", and its remaining time is the optimistic
estimate.

**This value is inaccurate** (the duration estimate was never accurate to begin with),
but **having it is better than not having it**.

---

## 4. The fine control (the wait graph)

### Following the existing design

Section 3 of `docs/资源模型与死锁避免-设计.md`.

**The key points**:

```
Cannot take it → add the edge P → Q
            → DFS detects whether it can get back to P from P
            → a cycle: reject, withdraw the edge
            → no cycle: wait
```

### The three layers of resources of the fine control

| Layer | Purpose |
|---|---|
| **Position** (L1) | Position conflicts |
| **Port** (L2) | Action-level exclusivity |
| **Robot arm** (L3) | Action-level exclusivity |

**The coarse screening only looks at the "device" (≈ the union of L2+L3); the fine control
looks at all three layers.**

---

## 5. How the two work together

### At the moment of starting

```
The user presses Start
  → coarse screening: compute the safe sequence
     - safe → allow the start, and enter the queue
     - unsafe → **refuse the start**, and return "not enough resources + the estimated wait"
```

### During execution

```
Requesting a resource at each step
  → fine control: wait-graph detection
     - no cycle → wait, or get it
     - a cycle → reject, back off and retry
```

### What happens when the coarse screening computes wrongly

**This is the core safeguard of the design**.

**Scenario**: the coarse screening computes "2 devices are needed" along the main path,
but at runtime it takes a branch that needs 3.

```
Coarse screening: pass (it was computed as 2 devices along the main path)
Execution: it takes a branch and needs the 3rd
  → fine control: wait-graph detection
     - no cycle → wait (possibly a long time, but no deadlock)
     - a cycle → reject, back off
  → if it never gets it → timeout (for example 10 minutes) → alert "resource starvation"
```

**Conclusion**: **the worst the coarse screening can do is "wait a long time" or "report
resource starvation"; it will never deadlock.**

**This is what "the coarse screen may be wrong, the fine control may not" means.**

---

## 6. Handling resource starvation

**The fine control guarantees no deadlock, but it does not guarantee "fairness"** — some
execution may never get the resource.

### Detection

```sql
-- an execution waits beyond the threshold
SELECT run_id, resource_id, now() - wait_started_at AS 等待时长
FROM resource_wait
WHERE now() - wait_started_at > interval '10 minutes';
```

### Handling

| Strategy | Explanation |
|---|---|
| **Alert** | "Execution X has been waiting for device Y for more than 10 minutes" |
| **Raise the priority** | The longer it waits, the higher the priority (prevents starvation) |
| **Human intervention** | If it really comes to that, a human decides who goes first |

**Forced preemption is not recommended** — the plate may really be inside the device.

### Priority ageing (recommended)

```java
// +1 to the effective priority for every minute waited
int effectivePriority = basePriority + (int) waitMinutes;
```

**This way the ones that have waited longest eventually win**, and nothing starves
forever.

---

## 7. The implementation order

| Batch | Task | Estimate | Depends on |
|---|---|---|---|
| **1** | The three-layer resource model (position / port / robot arm) | 1 week | None |
| **2** | Wait-graph fine control + cycle detection | 1 week | 1 |
| **3** | **The definition of the main path** (manual marker + automatic selection) | 3 days | None (can run in parallel) |
| **4** | **The coarse-screening algorithm + the start interface** | 1 week | 1, 3 |
| **5** | The resource starvation alert + priority ageing | 3 days | 2 |

**About 4 weeks in total** (1.5 weeks more than a pure wait graph).

**The order cannot be changed**: 1 is the foundation, 2 is the safety floor, and 3-4 are
experience optimisations.

**If only 1+2 are done**: no deadlock is guaranteed, but the user may have to wait a long
time after pressing Start to find out that the resource cannot be taken.
**With 3+4 added**: clear feedback is given at the moment of starting.

---

## 8. The risks and the trade-offs

### The risks we explicitly accept

| Risk | Impact | Mitigation |
|---|---|---|
| **The coarse screening's `estimatedWait` is inaccurate** | The user sees "about 30 minutes" and it is actually 2 hours | Mark it as "an estimate, for reference only" |
| **The main path is marked wrongly** | The coarse screening is based on a wrong assumption | The fine control catches it (it only affects the experience) |
| **Resource starvation** | Some execution waits forever | Priority ageing + alerts |
| **Complexity** | Two mechanisms | The coarse screening is a pure computation (stateless), the fine control is stateful |

### What we explicitly do not do

| Not done | Why |
|---|---|
| Coarse screening down to position granularity | There are too many positions, and positions are usually sufficient |
| Forced preemption | The plate may be inside the device |
| Automatically terminating a starved execution | It may be doing something else (for example, incubating) |

---

## 9. The relationship to the earlier designs

```
docs/为什么不用银行家算法.md
  ├─ It explains: why pure banker's does not work (the premise does not hold)
  └─ Section 9: it proposes the hybrid solution ← **this file is its detailed design**

docs/资源模型与死锁避免-设计.md
  ├─ The three-layer resource model ← the fine-control part of this solution
  └─ Wait-graph detection ← the fine-control part of this solution

This file
  ├─ New: the definition of the main path
  ├─ New: the coarse-screening algorithm (a simplified banker's, at device granularity only)
  └─ New: the coordination of the two and the fallback
```

---

## 10. In one sentence

**The coarse screening is responsible for "knowing at the moment Start is pressed whether
there is enough"; the fine control is responsible for "not deadlocking halfway through
the run".**

**The coarse screening may be wrong (it only affects the experience); the fine control
may not (it is the safety floor).**

**The cost: 1.5 weeks more than a pure wait graph, in exchange for "clear feedback at the
moment of starting".**
