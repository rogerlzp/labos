# Design Review Package

> **English edition.** The Chinese original is [`docs/设计审核包.md`](../设计审核包.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **The entry point for an external review.** Current state: **design 90%, code 10%**.
> It is recommended to **review the design before writing the code** — the 6 weeks of implementation are all based on these judgements, and if the design is wrong the cost of rework is enormous.

---

## The review has taken place (20261102-01, conclusion: conditionally passed)

The three problems that had to be resolved before writing the code, **fixed**:

| Id | Issue | Handling |
|---|---|---|
| **R1** | "The banker's algorithm assumes resources are preemptible" is a **misstatement** | Changed to "the maximum demand cannot be known in advance", and it now explains that this has nothing to do with preemption |
| **R2** | D105 requires the return position to be reserved, D111 requires a pause to release "occupied but unused" — a **hard contradiction** | Added an exemption rule: the semantics of the return position is a "commitment", not a "reservation"; `source_type='position-return'` is not released |
| **R3** | The **cross-resource acquisition order of a Transfer is undefined**; "rejection" cannot be carried out in the physical world | Added the invariant: **the plate does not leave the source position until all resource requests have completed**; fixed total order source port → source position → target port → target position → return position → robot arm |

**The conclusions on the 7 points to be reviewed**:

| # | Original claim | Review conclusion | Handling |
|---|---|---|---|
| 1 | Three resource layers are enough | Agree (the collision of two arms is written as an explicit premise) | Added |
| 2 | Replace the banker's algorithm with a wait-for graph | Agree with the direction, **the argument was rewritten** | R1 was changed |
| 3 | A pause is only allowed at an action boundary | Agree, **but 30 minutes is a modelling error** | Incubation should be split into "place → hold → take out"; the hold is a pausable state |
| 4 | UNKNOWN is never guessed | Agree, add a "set of possible positions" | To be added |
| 5 | `returnTo` defaults to source | **Disagree** (it fails silently) | Changed to **mandatory** |
| 6 | The `running > 1` switch | **Disagree** (race + missed detection) | **Deleted, always on** |
| 7 | The cuttable list contains D105 | **Disagree** (a return deadlock is a 2-experiment scenario) | **D105 cannot be cut**; what really can be cut is **D109 the wait-for graph** (2 in parallel cannot form a cycle) |

---

## 1. The current state

### Design (written, not reviewed)

| Document | Lines (measured with `wc -l`) | Content |
|---|---|---|
| `docs/资源模型与死锁避免-设计.md` | **282** | Three resource layers + wait-for graph detection |
| `docs/暂停恢复与故障处理-设计.md` | **370** | Action atomicity + fault scope + three plate-position states |
| `docs/并发调度决策记录.md` | **624** | The D101-D119 decision record |
| `docs/并发死锁分析与对策.md` | **241** | The three forms of deadlock |
| `docs/变量传递与点位初始化-设计.md` | **340** | Three levels of variables + two levels of positions |
| **Total** | **1857** | |

> **Correction note**: the first version of this table wrote ~430/~400/~500/~280/~250,
> and **not a single row matched** (the total 1860 was only coincidentally close to 1857).
> A document that opens with "first check the actual state" did not check its own table —
> this is the problem that review 20261102-01 pointed out; it has been corrected against the measured values.

### Code (implemented vs not implemented)

| Decision | Design | Code |
|---|---|---|
| D101 Device lock (replaces the island lock) | ✅ | **✅ implemented** (`DeviceLock.java`, 212 lines) |
| D102 Step-level lock for the robot arm | ✅ | **✅ implemented** |
| D103 Orphan lock cleanup | ✅ | **✅ implemented** (heartbeat every 10 s) |
| D104 Variable ThreadLocal | ✅ | **✅ implemented** (+ cleanup in finally) |
| **D105 `returnTo` + double lock** | ✅ | **❌ not implemented at all** |
| **D109 Three resource layers** | ✅ | **❌ not implemented at all** |
| **D110 Safe pause point** | ✅ | **❌ not implemented at all** |
| **D112 Three plate-position states** | ✅ | **❌ not implemented at all** |
| **D113 Three pause kinds** | ✅ | **❌ not implemented at all** |

**The ones that are not implemented take an estimated 6 weeks.**

---

## 2. The core judgements that need a review (ordered by risk)

### 🔴 Risk 1: Is the three-layer resource model enough

**Claim**: three resource layers are enough

| Layer | Resource | Sharing | Occupancy duration |
|---|---|---|---|
| L1 | Each layer position | Independent | The whole task |
| L2 | The entrance/exit of each device | Shared | Action level (a few seconds) |
| L3 | The robot arm | Shared | Action level (2-5 s) |

**Reason**: with these three layers you can explain "two plates entering different positions of B at the same time" and the "A→B→C→A cycle".

**Challenges wanted**:
- Is there a fourth layer? (e.g. "the pose space of the robot arm"? could two arms fight in mid-air?)
- How do you draw the boundary of "action level"? Is a `Transfer` one action, or three ("grasp - move - place")?
- If a device has two entrances, a "pre-processing position" and a "post-processing position", is L2 one lock or two?

### 🔴 Risk 2: Wait-for graph vs the banker's algorithm

**Claim**: use the wait-for graph (cycle detection only), not the complete banker's algorithm

**Reason**: the banker's algorithm requires "knowing the maximum demand in advance", but workflows have branches and the durations are inaccurate, so that premise does not hold.

**Challenges wanted**:
- How serious is the "false rejection" of the wait-for graph? Could it mean that some workflows never get the resource?
- Is it possible to have "no cycle in the wait-for graph but an actual deadlock"? (e.g. a resource is taken in the middle of an action)
- The user explicitly asked for "the complete banker's algorithm" — does replacing it with the wait-for graph deviate from the requirement?

### 🔴 Risk 3: The "non-preemptible" constraint of the banker's algorithm

**Claim**: the safety check only covers "new requests"; it cannot interrupt what is "already held and in use"

**Reason**: stopping an incubation half-way ruins the sample; stopping the robot arm half-way drops the plate.

**Challenges wanted**:
- Does this constraint take the meaning out of "the banker's algorithm"? (because it assumes preemption)
- If this constraint is kept, is it still worth calling it "banker's"?
- Is there a "half-preemption" scheme? (e.g. preempting only at safe points)

### 🟠 Risk 4: A pause is only allowed at an action boundary

**Claim**: a pause is not answered while an action is executing

**Cost**: the pause delay = the remaining time of the current action, at most 30 minutes (incubation)

**Challenges wanted**:
- Is a 30-minute pause delay acceptable in real operations?
- Is there a way to shorten it? (e.g. split incubation into the three actions "place - wait - take out", so that it can be paused while waiting?)

### 🟠 Risk 5: A plate position in UNKNOWN must be inventoried by hand

**Claim**: after a transport anomaly the plate position is marked UNKNOWN and must be confirmed by a person; the system does not guess

**Reason**: the plate may have fallen on the floor or been taken away by a person; any assumption may be wrong.

**Challenges wanted**:
- Does this policy make the operations burden too heavy? (an inventory on every anomaly)
- Is there a "low-cost confirmation" method? (e.g. let the robot arm return to its origin and take a picture?)

### 🟡 Risk 6: `returnTo` defaults to source

**Claim**: if `returnTo` is not configured, the default is "where it came from, back there"

**Challenges wanted**:
- If the workflow is "take the plate out → execute → put it into another position", the default source is wrong
- Should the configuration be mandatory (no default)?

### 🟡 Risk 7: The switch that disables detection for a single experiment

**Claim**: deadlock detection is only enabled when `running > 1`

**Challenges wanted**:
- Do the parallel nodes inside one experiment also need detection? (in the original design `hasParallelNode` was only an option)
- Is using COUNT free of races? (right at the boundary both of them think they are "the only one")

---

## 3. The review focus (where the mistakes are most likely)

Ordered by "the cost of rework if it is wrong":

| Rank | Design point | The consequence if it is wrong |
|---|---|---|
| 1 | **The three-layer resource model** | The whole code changes (the granularity of every lock) |
| 2 | **The non-preemptible constraint** | The entire deadlock scheme has to be redone |
| 3 | **Wait-for graph vs the banker's algorithm** | The algorithm is rewritten |
| 4 | **The action-atomicity boundary** | The state machine is refactored |
| 5 | The three plate-position states | The data model changes |
| 6 | The `returnTo` default | A small change |

**If the first 4 are wrong, the 6 weeks of work have to be redone.**

---

## 4. How the review is suggested to be done

### Round 1: review the design (do it now)

**Goal**: before writing the code, validate the architectural judgements.

**Input**:
- the "risks 1-7" of this document
- 5 design documents

**Output**:
- for every claim: **agree / disagree / have questions**
- when you disagree, give an alternative
- when you have questions, state clearly "which sentence does not hold"

### Round 2: review the code (in 6 weeks)

**Goal**: verify that the implementation matches the design.

**Input**: the implementation + the regression data

---

## 5. How to quickly judge whether the design has obvious holes

### Test it yourself with three scenarios

**Scenario A: two plates enter a multi-position device at the same time**

```
B has 3 internal positions, 1 entrance/exit
Workflow 1: plate 1 enters B position 1
Workflow 2: plate 2 enters B position 2
```

**The design says**: they can run in parallel (L1 is independent, L2 queues for a few seconds).

**What to verify**: how long is L2's "action level" really? If one plate entry takes 30 s, workflow 2 has to wait 30 s — does that still count as "parallel"?

**Scenario B: 3 workflows run a cycle**

```
Workflow 1: A→B→C→A
Workflow 2: B→C→A→B
Workflow 3: C→A→B→C
```

**The design says**: the wait-for graph detects the cycle, the third is rejected, and the first two can complete.

**What to verify**: the rejected one will retry — will it be **rejected over and over** on the retry? (livelock)

**Scenario C: the robot arm is emergency-stopped half-way through a transfer**

```
The robot arm is holding the plate and is physically emergency-stopped
```

**The design says**: mark it UNKNOWN and inventory it by hand.

**What to verify**: if operations are not on site (unattended at night), will the system just stay stuck waiting?

---

## 6. An honest note: the risk that the design is over-engineered

**This design has 1860 lines and covers deadlock, pause, recovery, faults and the three plate-position states.**

**Signals that it may be over-designed**:

| Signal | Explanation |
|---|---|
| **No real-device verification at all** | All the design is based on the simulator; the constraints of the real device may be different |
| **7 devices on a single island** | The design is built for "multi-workflow concurrency", but will more than 3 really run in parallel in practice? |
| **The operations capability is unknown** | "Manual inventory" assumes there is someone who can do the inventory — what if nobody is there at night? |

**If an island in practice only ever runs 1-2 experiments**, this whole deadlock detection may never be needed.

**What the review should ask first**: **"how many experiments does this laboratory run in parallel in a day?"**
If the answer is 1-2, **D109-D113 can be cut and only D101-D104 kept**.

---

## 7. One sentence

**The design is written; the code has only done the 4 lock-related items.**

**It is recommended to review the design now** — because the next 6 weeks of work are entirely betting on these judgements,
especially the two rules "three layers" and "non-preemptible"; if they are wrong everything has to be redone.

**But one thing has to be confirmed first**: **does this laboratory really need multi-workflow concurrency?**
If it only needs 1-2, the current D101-D104 is already enough.

---

## What is to be reviewed now: the parallel-scheduling implementation spec

**The decision is made: option C is adopted (3+ in parallel, hybrid scheduling, 4 weeks).**

**The main document under review**: `docs/并行调度-实施规格.md`

**Why this one**: it **integrates the conclusions scattered over several design documents into an executable spec** —
with the table structures, pseudo-code, 5 milestones and the acceptance criteria of each milestone.

### The 5 points that must be reviewed (section 10 of the spec)

| # | Question | Why it matters |
|---|---|---|
| 1 | Is the boundary of **"action level"** clear enough? Is a `Transfer` one action or three (grasp / move / place)? | It decides how long the resources are held, which affects concurrency |
| 2 | Is the **fixed total order** (source port → source position → target port → target position → return position → robot arm) physically reasonable? | If there is a device of the kind "the robot arm has to be acquired before the cabinet door can be opened", this order is wrong |
| 3 | Will the main path's **"heaviest on resources" selection** always pick the exception branch? | The exception branch often needs more devices; computing against it makes the coarse screening overly conservative |
| 4 | Will **"workflow 2 continues on a fault"** in practice be blocked because the robot arm is shared? | The design says "only the related ones stop", but the robot arm is shared |
| 5 | Is the **M1 acceptance** (two plates entering B at the same time) feasible on real equipment? Is there a physical limit of "only one plate can be accepted at a time"? | This decides whether the three-layer resource model holds |

### The premises that still have to be confirmed (section 9 of the spec)

| # | Premise | The consequence if it does not hold |
|---|---|---|
| 1 | **The laboratory will be configured with `max_parallel >= 3`** | **4 weeks wasted** |
| 2 | Devices per island ≤ 20 | A small effect |
| 3 | Workflow branches ≤ 10 | The main-path selection has to be optimised |
| 4 | **Someone can get on site when a fault occurs** | UNKNOWN stays blocked for a long time; the night mode has to be enabled |

**The first one is the most critical** — if in practice only 1-2 are configured, the whole scheme should not be built.

### The three physical facts that have been confirmed (2026-11-02)

Confirmed by the user and written into the design:

| # | Fact | The effect on the design |
|---|---|---|
| 1 | **The state of the gate is inferred from the action, not queried from the device** | closed by default; a successful `OpenDoor` means `open`; on recovery, force it closed first and then open it again |
| 2 | **Channels with capacity > 1 are physically common** | The channel lock is a **semaphore** (`device_port_usage.in_use < max_capacity`), not a mutex |
| 3 | **The position attributes can be brought out automatically + filled in by hand + verified** | A new `device_point_template` matched by device.model; `fill_status` marks what still has to be filled in |

**These three changed how M1 is implemented** (1 week → 1.5 weeks).
