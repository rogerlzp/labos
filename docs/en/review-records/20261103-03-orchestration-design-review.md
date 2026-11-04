# Review Record 20261103-03 (review of the unified experiment-orchestration design draft)

> **English edition.** The Chinese original is [`docs/评审记录/20261103-03-编排设计评审.md`](../../评审记录/20261103-03-编排设计评审.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Field | Value |
|---|---|
| **Review id** | `20261103-03` |
| **Review time** | 2026-11-03 13:33 (UTC+8) |
| **Reviewing model** | GPT-5.4 |
| **Review target** | `docs/设计-实验编排统一-v0.1-草稿.md`, plus the existing implementation it references (`ExperimentExecutor` / `QueryService` / `WorkflowService` / `DeviceLock` / `PositionLock`) |
| **Review method** | Design review + code-reading verification; **no database / real device run** |
| **Conclusion** | **Fail** |

> **Yardstick**: what this record reviews is "whether the design can safely enter the schedule / the implementation", not "whether the draft is clearly written".
>
> **Statement of independence**: the reviewing model of this round is **GPT-5.4**, a **different model family** from the `DeepSeek-V4.1-Flash` of the previous two rounds, which satisfies the requirement of "cross-family re-checking".

---

## 1. TL;DR

The most valuable part of this draft is that it spells out a hard fact that had been hidden by the UI before:

1. `experiment_graph` really does **not take part in the execution** today;
2. `experiment_process.run_after` really is **"present in the table / the API, but not read by the executor"**;
3. "First make the misleading state of the canvas visible" is the right move.

But the draft **cannot enter the M9.5 schedule as it stands**, because there are 2 blocking-level problems + 1 high-severity problem:

- **B1**: §4.4 builds "DAG concurrency is safe" on top of `DeviceLock`, and that premise does not hold; the project's existing concurrency design document already states that device locks / dual locks **are not enough to cover a multi-resource deadlock**, so the three-layer resource model + the wait graph are required.
- **B2**: the batch model of §5 / §6 cannot hold the history and the retry semantics of "only re-run that batch on failure"; the one-to-one `experiment_batch.run_id` overwrites the history.
- **H1**: §7 writes S1 as "can be released independently", but unless the saving of `graph` and `process` is **converged into a single transaction with a single entry point**, S1 still carries the risk of a split write.

In other words: **the problem identification is right, the migration direction is broadly right, but the implementation cut has not been fully converged.**

---

## 2. Blocking-level findings

### B1. The judgement of §4.4 "DAG concurrency + `DeviceLock` is already safe enough" does not hold

**Location:**
- the draft [§4.4](../../设计-实验编排统一-v0.1-草稿.md#L186-L192)
- the existing device-lock implementation [DeviceLock.java:L58-L103](../../../labos-kernel/src/main/java/com/labos/kernel/driver/DeviceLock.java#L58-L103)
- taking / releasing the device lock at execution time [WorkflowService.java:L1847-L1885](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1847-L1885)
- the existing position dual lock only covers a two-position transport [PositionLock.java:L205-L215](../../../labos-kernel/src/main/java/com/labos/kernel/driver/PositionLock.java#L205-L215)
- the project's existing deadlock design conclusion [资源模型与死锁避免-设计.md:L273-L307](../../资源模型与死锁避免-设计.md#L273-L307)

**The problem:**

The draft writes:

> The device lock (`DeviceLock`) already guarantees "the same device is never used by two workflows at the same time", so the concurrency of the DAG is safe.

The first half of that sentence is right, the second half **cannot be derived**.

`DeviceLock` only guarantees "a single device is occupied by only one run at a time"; in implementation it is a single-resource reservation/wait on `device_state.current_run_id`; `PositionLock.tryReserveTransfer()` only guarantees "the two positions apply in `resource_id` order, so that X and Y do not wait for each other". Neither of them **establishes a "multi-resource wait graph"**, and neither covers the **cross-layer circular wait** between:

- the position (L1)
- the entry/exit / port (L2)
- the robot arm (L3)

And the project's own concurrency design document has already given the conclusion: `returnTo` / the dual lock can only solve a single chain, **an A→B→C→A ring must be refused by the wait graph**, otherwise it deadlocks.

**The concrete deadlock construction:**

Suppose the 3 parallel DAG branches inside one experiment respectively hold the inner positions of A / B / C, and the next step of each needs "the robot arm + the entry/exit / target position of the other device":

1. Branch P1: the plate is in A, waiting to enter B
2. Branch P2: the plate is in B, waiting to enter C
3. Branch P3: the plate is in C, waiting to enter A

This is not "the same device used by two workflows at the same time", it is **three workflows each holding part of the resources and waiting for the next part**.
`DeviceLock` cannot see such a ring, it only lets all 3 workflows keep waiting; the wait graph is what detects the ring at the moment "P3 waits for A while A is held by P1" and refuses it.

**Why this is blocking level:**

Once S2 makes "concurrent dispatch inside an experiment" a real wall-clock concurrency, this wrong premise is not a documentation blemish but an **execution-level risk that freezes the whole run after it goes live**.

**Suggested correction:**

Change the wording of §4.4 / §8.4 to:

1. `DeviceLock` only solves **single-device mutual exclusion**;
2. if S2 does real concurrency, it **must reuse/merge in** the existing three-layer resource + wait-graph scheme;
3. if S2 does not intend to bring in the wait graph at the same time, it can only do the conservative version of "logically parallel, physically serial", and must not claim "parallel execution inside an experiment".

---

### B2. The batch model of §5 / §6 cannot carry the history and the retries of "only re-run that batch on failure"

**Location:**
- the draft [§5.2](../../设计-实验编排统一-v0.1-草稿.md#L212-L216)
- the draft [§6 batch table draft](../../设计-实验编排统一-v0.1-草稿.md#L235-L244)

**The problem:**

The draft proposes all three of the following at the same time:

1. "one run per batch, the run records `batch_no`"
2. "on failure, only re-run that batch"
3. `experiment_batch` has only one `run_id`

These three things currently **do not fit into the same model**.

If a batch fails and is re-run:

- either `experiment_batch.run_id` is overwritten, and the history of the previous failed run is lost;
- or it is not overwritten, and "which run does this batch currently correspond to" cannot be said clearly;
- and the draft does not actually add a `batch_no` or `batch_id` field to `run` / `experiment_run`, which is inconsistent with "the run records `batch_no`".

**Why this is blocking level:**

"Only re-run that batch on failure" is not a UI presentation problem, it is an **audit / accountability / statistics yardstick** problem.
Without a stable data model, the later resume, the work reporting, the success rate and the batch detail page all fork.

**Suggested correction:**

Pick one of the two:

1. **A stable batch + multiple attempts**
   `experiment_batch` only represents the "logical batch", and a new `experiment_batch_run(batch_id, run_id, attempt_no, status, started_at, finished_at)` records each execution;
2. **The run carries the batch membership itself**
   Add `batch_no` / `batch_id` directly to `experiment_run` (or `run`, pick the real yardstick), and `experiment_batch` only aggregates, it does not hold a single `run_id`.

Of these two, I recommend **#1** more strongly, because it naturally supports the history of "several attempts at the same batch".

---

## 3. High-severity finding

### H1. S1 cannot be only "compile the canvas into the database", it must simultaneously converge the save entry point; otherwise the "dual paths" remain

**Location:**
- the draft [§2.2 rules 1-5](../../设计-实验编排统一-v0.1-草稿.md#L78-L90)
- the draft [§7 S1](../../设计-实验编排统一-v0.1-草稿.md#L252-L259)
- the existing `experiment_process` saving [QueryService.java:L1393-L1425](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L1393-L1425)
- the existing `experiment_graph` saving [QueryService.java:L2156-L2172](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L2156-L2172)

**The problem:**

The draft's judgement about "who is the single source of truth" is right, but S1 is written as "can be released independently", and one key condition is still missing:

> **The graph saving + the process compilation result saving must be completed at the same backend entry point and in the same transaction; and the old direct-edit entry point of the process must be closed at the same time.**

In the actual code today:

- `experiment_process` has its own whole-replacement transaction;
- `experiment_graph` also has its own independent saving;
- the two are not bound atomically.

If S1 is only "the frontend triggers one compilation from the canvas", but the server still keeps two independent write paths, the following will still happen:

1. the graph is written successfully, the process write fails;
2. the process is changed by the old entry point, the graph does not follow;
3. the transaction succeeds but the version / audit yardstick only updated one of the two sides.

This is exactly the kind of problem you just caught in §1 of the draft, except that it has changed from "historically occasional" to "kept by design".

**Suggested correction:**

Make the Definition of Done of S1 explicitly:

1. add a single save entry point: `saveExperimentGraphAndCompile(...)`
2. that entry point writes `experiment_graph` + `experiment_process` + `experiment.version` in **one transaction**
3. the old direct-edit entry point of the workflow table is **closed for writing in the same version, or kept read-only**
4. the regression test has to cover "the whole thing rolls back when either the graph or the process write fails"

Otherwise S1 cannot count as truly "eliminating that the canvas has no effect", only as "lowering the probability of the split".

---

## 4. Items verified as true

The following judgements were confirmed by reading the code, and the draft holds up:

### 1. `experiment_graph` really is only read/written by the canvas today, the executor does not read it

- The executor only expands by [`experiment_process ORDER BY seq`](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java#L108-L139)
- `experiment_graph` today only has [`SELECT graph`](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L2137-L2153) / [`saveExperimentGraph`](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L2156-L2172)

Conclusion: **"the canvas has no effect" is a fact, not a speculation.**

### 2. `run_after` really is exposed by the API today, but the executor does not use it

- The API returns [`p.run_after`](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L1236-L1260)
- The fields queried by `ExperimentExecutor.expand()` are only `seq / workflow_id / loop_count / params_override`, **there is no `run_after`**

Conclusion: the draft uses it as a counter-example of "the model comes first, the execution does not keep up", and that judgement holds.

### 3. "B before A" as a route judgement is directionally reasonable

I did not find a hard constraint that forces an immediate all-in on BPMN.
At the current size of the system, **solving "the canvas has no effect" first and only then deciding whether to unify the engine** is a sound ordering.

### 4. The overall judgement of §4.3 "no sub-experiment nesting for now" is reasonable

The 4 extra design surfaces the draft lists:

- lock ownership
- variable scope
- cancellation propagation
- report aggregation

are all real problems, and the current code has no existing boundary to reuse.
So "use DAG + phase + batch to carry the real requirements, and do not introduce nesting" —— **I agree** with that judgement.

---

## 5. The suggested order of the revision

Do not rewrite the whole text, just fix the three most important wordings:

1. **First change §4.4 / §8.4**
   Change "DAG concurrency is safe" from `DeviceLock` to "the three-layer resource + the wait graph must be merged in, otherwise it is only logically parallel"
2. **Then change §5 / §6**
   Make explicit that the relation between a batch and a run is one-to-many, and add "how the re-run history is stored"
3. **Finally change §7 S1**
   Change the premise of "released independently" to "a single entry point + a single transaction + the old entry point closed for writing"

Only after these three are changed is it worth deciding whether to put it on the ROADMAP timeline.

---

## 6. Review conclusion

**Review conclusion: fail.**

Not because the direction is wrong, but because **the implementation cut has not been converged**.
Going into development with the current draft, the most likely outcome is not "it cannot be built", but:

1. S2 first builds the concurrent dispatch, and the deadlock governance is added afterwards, which introduces a new freezing surface in between;
2. S3 first builds the batch list, and only afterwards is it discovered that the re-run history has nowhere to go;
3. S1 believes it has "eliminated the double write", but in reality it has only gone from "the frontend can change it in two places" to "the backend can write it in two ways".

---

**Review sign-off**: GPT-5.4 · 2026-11-03 13:33 CST

---

## 7. The re-check conclusion (after the convergence)

### 2026-11-03 15:36 CST · the reviewer's re-check

This round converged the draft and `ROADMAP.md` according to the review comments; the status of the original blocking items is as follows:

| Item | Original problem | Current status | Evidence |
|---|---|---|---|
| `B1` | Building the safety of real DAG concurrency on `DeviceLock` | ✅ **Closed** | The draft has been changed to "`DeviceLock` only guarantees single-device mutual exclusion; if real concurrency is done, the three-layer resource + the wait graph must be brought in at the same time" ([draft:L186-L201](../../设计-实验编排统一-v0.1-草稿.md#L186-L201)); `ROADMAP` has been converged accordingly to "only a partial order is promised today" ([ROADMAP.md:L299-L305](../../../ROADMAP.md#L299-L305)) |
| `B2` | The batch model cannot hold the history of "only re-run that batch on failure" | ✅ **Closed** | The draft has been changed to the two-layer model `experiment_batch + experiment_batch_run`, and it makes explicit that "a re-run after a failure of the same batch must keep the history" ([draft:L221-L267](../../设计-实验编排统一-v0.1-草稿.md#L221-L267)); `ROADMAP` has been written in as well ([ROADMAP.md:L297-L305](../../../ROADMAP.md#L297-L305)) |
| `H1` | If S1 does not have a single entry point / a single transaction, the dual paths remain | ✅ **Closed** | The draft has converged S1 into "a single save entry point + a single transaction + a rollback case" ([draft:L276-L297](../../设计-实验编排统一-v0.1-草稿.md#L276-L297)); `ROADMAP` has added the "second-review gate", and it does not enter the timeline before that is met ([ROADMAP.md:L313-L325](../../../ROADMAP.md#L313-L325)) |

### The residual problems (not blocking)

1. The title and the status line of the draft still say "awaiting review" ([draft:L1-L3](../../设计-实验编排统一-v0.1-草稿.md#L1-L3)), while the content has actually entered the "converged after the first review, awaiting the second review" state.
   This is a **copy label that has not kept up**, it does not affect the design judgement.
2. The 4th line of the summary in §0 of the draft still says "adds `experiment_batch`" ([draft:L20-L23](../../设计-实验编排统一-v0.1-草稿.md#L20-L23)), and does not explicitly mention `experiment_batch_run`.
   This is **the summary not being fully in sync with the body**, it does not affect the scheduling judgement, but it would be good to align it the next time the draft is revised.

### The re-check ruling

**Review conclusion: pass.**

But this "pass" means:

- ✅ **It is allowed to enter the scheduling discussion / the refinement of the plan**
- ❌ **It does not enter the timeline commitment yet**

In other words, `M9.5` can now be raised from "a directional draft" to "**a design plan awaiting scheduling**",
but only after all the second-review gates written in `ROADMAP` are met is a concrete date allowed to be scheduled.

### The suggested next steps

1. First align the two non-blocking copy differences of the draft (the title status, the batch summary in §0);
2. Then enter the **pre-implementation design breakdown**: split `S1 / S2 / S3` each into three sub-lists — the interfaces, the table structures, the regression tests;
3. On `S2`, make the decision first: exactly "partial order only", or the complete version with "three-layer resource + the wait graph".

**Re-check sign-off**: GPT-5.4 · 2026-11-03 15:36 CST

---

## 8. The disposition of the residual items and the implementation follow-up (the implementer · the evening of 2026-11-03)

| Item raised by the re-check | Disposition |
|---|---|
| Residual 1: the title / status line of the draft still says "awaiting review" | ✅ Fixed: the title and the status line have been changed to "**second review passed (2026-11-03 15:36), entered the implementation**", the wording "awaiting review / awaiting the second review" is no longer used |
| Residual 2: the batch summary in §0 does not explicitly mention `experiment_batch_run` | ✅ Fixed: the 4th line of §0 is now `experiment_batch + experiment_batch_run`, and it makes explicit that "each batch can have several attempts, the history is kept" |
| Suggestion 2: the pre-implementation design breakdown | ✅ Split: §2/§3/§4 of the implementation checklist give the backend / frontend / test / risk / exit conditions of S1/S2/S3; the D1-D7 decisions and the interface contract of S3 are in §8 |
| Suggestion 3: the S2 wording is one of the two | ✅ Written down as **S2-A** (only a partial order is promised, physically serial); **S2-B** (real concurrency + three-layer resource + wait graph) is listed as an independent upgrade, not part of the 1.0 delivery |
| The second-review gate | ✅ All 4 have been checked and met item by item (the evidence is in the M9.5 section of `ROADMAP.md`), and `M9.5` can be scheduled from 2026-11-03 |

**Implementation progress**: S1 (`86ef1c1`), S2-A (`ae06025`) are live and have pure-logic / live-database regressions;
S3 (batched execution) is under implementation. See §9 of `docs/设计-实验编排统一-v0.1-实施清单.md`.
