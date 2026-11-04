# ROADMAP · LabOS Laboratory Control Console

> **English edition.** The Chinese original is [`ROADMAP.md`](../../ROADMAP.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Updated: 2026-11-04 (review 20261104-01 remediation + A2 documentation-consistency pass)
> Baseline: `main @ b91e4d8`
> Related: [Review bundle 20261103](../评审包-20261103/00-索引与评审须知.md) · [Review package 20261104](../评审包-20261104/00-索引与评审指引.md) · [Production roadmap (gap analysis)](production-roadmap.md) · [Development log](../开发日志.md)

---

## 0. How to read this document

| If you want to know | Read |
|---|---|
| Where the project stands now | §1 Current position |
| How many phases are left and roughly how long they take | §2 Overview |
| What each phase does and how it is judged complete | §3 Phase details |
| Which phase each P0/P1/P2 item falls into | §4 Priority → phase mapping |
| What counts as "passing" for a phase | §5 Definition of done (DoD) |
| What is explicitly out of scope | §6 Not doing |

**Division of labour with `docs/生产化路线图.md`**: that document answers "how far are we
from a commercial product" (gap analysis); this one answers "in what order to close the
gaps and how each step is accepted" (execution plan). Where they conflict, this document
wins (it is updated more recently).

---

## 1. Current position

### 1.1 Completed milestones (with acceptance scripts or documents)

| Milestone | Content | Acceptance |
|---|---|---|
| **M1** | Parallel scheduling infrastructure: method boundaries, gate protection, channel semaphores, position generation, failure rollback | `verify-m1.sh` **12 items** |
| **M3** | Start admission (coarse main-chain screening): decide before running whether resources suffice | `verify-m3.sh` **9 items** |
| **M4** | Pause / resume / fault handling: three pause kinds, plate-position UNKNOWN lock-out, return-leg promise | `verify-m4.sh` **14 items** + `PauseManagerSelfTest` **10 items** |
| **M5** | Multi-workflow concurrency: same-device queueing, gate state, channel release, no residue in the wait graph | `verify-m5.sh` |
| **This round (M6–M9.5)** | Device-onboarding loop, workflow/experiment orchestration, conditional branches/loops/variables, error-code policy management, crash recovery, data integrity / operability / gating / scheduling / unified orchestration | `test-all` **907/0** (pure logic 536/0), `verify-device-chain` **41/41** |

### 1.2 Current baseline (reproducible, not an estimate)

```
./scripts/test-all.sh --pure-only     → 536 / 0        (deterministic, no database)
./scripts/test-all.sh                 → 907 / 0        (full run with database)
./scripts/verify-device-chain.sh      → 41 / 0         (console→sidecar→simulator→database)
cd webapp && npx tsc -b --force       → 0 errors
./scripts/ci-gate.sh --e2e            → pass (pure logic 536 + tsc + e2e 24)

Code: labos-kernel 36,595 lines / 11 packages; webapp 12,830 lines / 23 pages
Data: 116 tables; workflows 1009 / nodes 14033 / experiments 678 / runs 10710 / commands 116724; `run_node` 120,268 (0 dangling parents)
(Measured snapshot as of 2026-11-04; the numbers inside each milestone's "evidence/acceptance" section below are **from delivery time** and are not updated with this table)
```

### 1.3 The real boundaries of the capability (do not overestimate)

| Can do | Cannot do |
|---|---|
| Drive devices from a BPMN graph; parallel / branch / loop / variables; expressions include arithmetic `+ - * / %` with precedence | Expressions do not support string functions, function definitions, loops, object arrays or regular expressions |
| Each loop iteration is recorded separately (`loop_index`); run detail and the graph expand by iteration | Multi-instance deployment (locks live in-process) |
| On error, retry / switch device / hand over to an operator per policy; error codes can be added, edited and deleted (duplicate → 400) | No coverage baseline yet; the device chain is not in CI |
| Resume after pause, resume after crash; heartbeat / offline thresholds editable in the UI and effective immediately | **Never connected to a real device** (that is M10, and it depends on vendor manuals) |
| Full set of pages (authoring / execution / monitoring / operations) + 24 e2e main-chain assertions | Reporting / BI, multi-tenancy (M11, pending the shape and deployment model) |

---

## 2. Overview

```
2026-11          2026-12          2027-01          2027-02+
   │                │                │                │
  M6 ───────────▶  M7 ───────────▶  M8 ───────────▶  M9 ────▶ M10 ────▶ M11
 Data integrity   Operability       Verifiability    Scheduling      Real device     Platform
 (P0 cleared)    (config loop)     (CI gate)        upgrade         integration     (multi-tenant)
```

| Phase | One-line goal | Depends on | Estimate |
|---|---|---|---|
| **M6 Data integrity** | Make "what happened during a run" **lose nothing**, and make it replayable per iteration | — | ✅ **Done** (2026-11-03) |
| **M7 Operability** | Field staff can change behaviour without touching code or writing SQL | M6 | ✅ **Done** (2026-11-03) |
| **M8 Verifiability** | Turn "someone remembered to click through it" into "CI blocks it" | M6/M7 | ✅ **Mainly done** (2026-11-03; remaining: coverage baseline / device chain in CI) |
| **M9 Scheduling upgrade** | Parallel experiments can be scheduled, inspected and adjusted | M8 | 1–2 weeks |
| **M9.5 Unified orchestration** (draft, pending second review) | Unify canvas and workflow table, allow **partial order** inside an experiment, support batching | M9 | 6–10 days (S1/S2/S3) |
| **M10 Real-device integration** | From "passes in simulation" to "runs on site" | M6/M7 + manuals | 2–4 weeks |
| **M11 Platformisation** | Multi-tenancy, permissions, reporting, multi-instance | M9/M10 | 4 weeks+ |

> **The order is not arbitrary**: M6 is the foundation of "data you can trust" (review
> finding H1 came from exactly this layer); without M8's CI gate, the regression check of
> every later phase degrades into "remember to test it".

---

## 3. Phase details

### M6 · Data integrity and the audit loop (P0) — ✅ done 2026-11-03

**Outcome**: one row per loop iteration (`loop_index` 0 = summary / 1..N = per iteration);
the run detail can be expanded per iteration; the schema scripts gained the
`parent_node_id` foreign key, so new environments no longer miss the cascade.

**Evidence**:

```
run 205411 (T-LOOP, maxIterations=3)
  A loop=0 completed   ← summary row (all existing queries look only at this one)
  A loop=1 completed
  A loop=2 completed
  A loop=3 completed
Page: nodes (1), expanding to "iteration 1/2/3"
Regression: test-all --pure-only 369/0 (GraphRunnerTest gains a "every iteration reports its index" case)
```

**The original problems** (kept for the record):

| # | Problem | Evidence in the current state |
|---|---|---|
| 1 | On loop re-entry `run_node` kept a single row (unique key `(run_id,node_key,attempt)`) | A 10-iteration loop left 1 node record in the database, with `attempt` overwritten cumulatively |
| 2 | `run_node.parent_node_id` had **no foreign key** | Review finding H1: deleting only `level=0` rows leaves the step rows behind and the commands with them; it was fixed by explicit code deletion, but **the next omission repeats it** |

**Scope (feature list)**

- [x] `run_node` gains `loop_index` (starting at 0); the unique key becomes `(run_id, node_key, attempt, loop_index)` — ✅ `20_kernel.sql:312-313` (declaration) + `96_schema_fix.sql:129-136` (migration)
- [x] `run_command` keeps its relation to `(run_node_id)`; `GraphRunner` writes an independent row per iteration — ✅ `GraphRunnerTest` "loop: every iteration reports its index" (3 iterations = 3 callbacks)
- [x] `run_node.parent_node_id` gains foreign key `REFERENCES run_node(id) ON DELETE CASCADE` — ✅ `96_schema_fix.sql:104-116` (idempotent constraint backfill)
- [x] Historical dirty-data cleanup script (of the 120k node rows, the ones with a dangling parent must be zeroed or completed) — ✅ measured: 120,268 `run_node` rows, 0 dangling parents, no cleanup needed
- [x] Run detail / graph display per iteration (frontend aggregates and collapses, newest iteration shown by default) — ✅ `RunDetail.tsx`: renders a "iteration N" Tag when `loopIndex>0`; the top level only counts `loopIndex=0`
- [x] `verify-device-chain.sh` gains a "loop 3 iterations → node row count = 3×" assertion — ✅ the assertion landed on the pure-logic side: `GraphRunnerTest` "loop: every iteration reports its index" (3 iterations = 3 callbacks); the device-chain script itself did not gain this assertion

**Acceptance (executable)**

```bash
# ① A 3-iteration loop: 3 S rows should appear (loop_index 0/1/2), no longer overwriting each other
SELECT node_key, loop_index, status FROM run_node WHERE run_id=:RID AND level=1 ORDER BY id;
# ② After a resume: both the step rows and the command rows must grow linearly with the number of business nodes
# ③ The foreign key works: after deleting a level=0 row, its level=1 children and run_command are cascade-deleted
SELECT count(*) FROM run_node WHERE parent_node_id = :deleted_id;   -- expected 0
```

**Dependencies**: none | **Risks**: schema migration (120k rows in the production database) + frontend display changes | **Estimate**: 2 days

---

### M7 · Operability (configuration loop) — ✅ done 2026-11-03 (5/5)

**Outcome**: error codes can be added and deleted, a scope mismatch is flagged in red,
expressions support arithmetic, heartbeat parameters are editable in the UI and take
effect immediately. The remaining item, "bulk ignore supports filters", was completed in
the M8 phase (`835aa43` bulk ignore + `73dc4f4` filter by pause kind / time range).

**Evidence**:

```
POST /api/error-codes           {"id":191,"code":"EC-TEST-M7"}   duplicate → 400 "error code already exists"
DELETE /api/error-codes/191     {"deleted":true}（soft delete: gone from the list, history kept）
POST .../policies               returns scopeCovered / warning（a missing scope raises a warning）
PUT /api/settings/heartbeat     {"intervalMs":5000,"offlineMs":20000} → persisted, effective next cycle, survives restart
Expressions: 1+1==2, ${count}+1>3, (a+b)/2>=5, -${a}>-5; string arithmetic and division by zero throw
```

**The original problems** (kept for the record):

| # | Current state | Target |
|---|---|---|
| 1 | Error codes can only be edited, not added or deleted | Support add/delete (including a set-level "code × scope" validation) |
| 2 | `error_code.scope` and `recovery_policy.scope` may disagree, and the policy then fails silently | Validate on save + flag in red in the list |
| 3 | Conditional expressions do not support arithmetic | Support `${a}+1 > 3`, `${count}+1` |
| 4 | Alert thresholds live in environment variables, so a change needs a restart | Editable in the UI (device scope overrides the global value) |
| 5 | The paused-queue / recoverable list can only be ignored one by one or all at once | Support bulk ignore by time range / workflow |

**Scope (feature list)**

- [x] Error-code CRUD (`POST /api/error-codes`, `DELETE`), checking references before deleting — ✅ `OpsApi` routes + soft delete (duplicate → 400)
- [x] Scope consistency validation (saving a policy whose scope conflicts with the code → 400 + message) — ✅ saving a policy returns `scopeCovered`/`warning`; the list flags "scope not covered"
- [x] Expression evaluator gains arithmetic (`+ - * /`) with precedence, still a hand-written parser, still no `eval` — ✅ `expr/Expression.java` (`+ - * / %` + precedence + unary minus)
- [x] Heartbeat / offline thresholds moved to a `sys_setting` table with UI editing, effective immediately — ✅ `RuntimeSettings` + `PUT /api/settings/heartbeat` (effective on the next probe cycle, survives restart)
- [x] Bulk ignore supports filter conditions (time range, workflow, pause reason) — ✅ `835aa43` bulk ignore + `73dc4f4` filter by pause kind / time range (`PausedRuns.tsx`)

**Acceptance**

```bash
./scripts/test-all.sh --pure-only     # should be > 368 and all green after the new expression cases
# Page: create a custom code → configure a command-scoped policy → run once to trigger it → the action matches the expectation
# Consistency: deliberately set the policy scope to something different from the code → the API should reject it and say why
```

**Dependencies**: M6 (only touch the operations surface once the data structures are stable) | **Estimate**: 3 days

---

### M8 · Verifiability (CI gate) — ✅ mainly done 2026-11-03 (2 items left: coverage baseline, device chain in CI)

**Outcome**: one command, `./scripts/ci-gate.sh --all`, blocks at four gates; GitHub
Actions runs the two that do not need a database.

**Evidence**:

```
./scripts/ci-gate.sh --e2e
  1. Pure logic unit tests   pass 378 / 0（threshold 378, may only rise）
  2. Frontend types          tsc 0 errors
  4. Frontend e2e            pass 14 / 0（5 main chains）
  Gate passed

./scripts/soak-test.sh --count 10
  Executed  success 10 / failure 0; run +10, run_command +10
  Memory 73856KB → 74192KB（0%，baseline taken after 3 warm-up runs）
  ✓ soak passed
```

**Why memory must be warmed up first**: the JVM goes from idle to running and grows from
47MB → 74MB (class loading / connection pool / JIT), so the first measurement is always
"up 58%" and reports nothing but false alarms. Only after three warm-up runs is it steady state.

**The original problems** (kept for the record):

**Scope (feature list)**

- [x] GitHub Actions (or a local pre-push hook) runs `test-all --pure-only`, **failing outright below 536** — ✅ `ci.yml` runs `ci-gate.sh` (pure logic, threshold now 536)
- [ ] `verify-device-chain.sh` in CI (start simulator + sidecar), **failing outright below 41** — ⏳ not done: GitHub Actions only runs pure logic + tsc; the device chain only runs locally via `ci-gate.sh --with-db` (see `02-后续规划.md` item A3)
- [x] Playwright covers 5 main chains: — ✅ covered by `scripts/e2e-smoke.py` (Python httpx assertions, not Playwright; 24 assertions today)
      execute→graph colouring / pause→resume / experiment run-history expansion / error-code editing / empty-experiment guidance
- [ ] Coverage baseline (Jacoco or a simple count), may only go up — ⏳ not done (see `docs/评审包-20261104/02-后续规划.md` item A3)
- [x] Long-stability soak script: run continuously for 2 hours and watch memory / connection count / `run_event` growth — ✅ `scripts/soak-test.sh` (defaults to a reproducible 20-iteration short soak; not a continuous 2 hours — the reason is in the script header)

**Acceptance**

```
PR check: test-all 536+ / tsc 0 errors (CI runs only the two gates that need no database); local closure: verify-device-chain 41 / e2e 24 — a red light in any of them blocks the merge
```

**Dependencies**: M6 (data structures must be stable, otherwise the e2e assertions change over and over) | **Estimate**: 4 days

---

### M9 · Scheduling upgrade (parallel experiments)

**Prerequisite debt 1: `workflow_node` does not express "how the workflow runs" — ✅ done 2026-11-03**

**Prerequisite debt 2: per-step duration estimation chain — ✅ done 2026-11-03**
(manual `estimate_sec` > historical P50 (≥20 samples) > model library
`device_method.estimate_sec` > 60 s fallback; the database already holds 116k measured
`run_command` durations, so this is not "no data")

Today a node's **device binding** and **execution order** live nowhere in the database:

| Information | Where it lives now | Consequence |
|---|---|---|
| Which device a node binds to | The `resource="504830"` attribute of the graph XML | Every query that needs it must fetch the XML and regex it; ETA / timeline / Gantt each wrote their own copy |
| Node order | The insertion order of `workflow_node.id` (there is no `seq`) | The order is only "accidentally right" and becomes unreliable after a reorder; the Gantt timeline draws it wrong |

This is not "ugly code", it means **every new feature has to parse the XML again**: ETA
(`workflowEtaJson`), the timeline (`workflowTimelineJson`) and the node-binding panel
each wrote their own regex. The next feature (Gantt) would have to write a fourth, and
the four parsing rules would eventually drift apart.

**Proposed approach** (0.5–1 day):

1. Add `resource_id bigint` and `seq int` (nullable, backfilled later) to `workflow_node`
2. On `saveGraph`, **write both**: the XML stays authoritative (bpmn-js still has to open it), and the parsed result is persisted
3. Switch the read side to the table: ETA / timeline / binding panel / Gantt all query `workflow_node`
4. Backfill script: parse the XML of the existing 1008 workflows and write it back (idempotent, re-runnable)
5. Add an assertion: after `saveGraph`, `workflow_node.resource_id/seq` must match the XML parse result (drift guard, same idea as `check-schema-sync`)

**Landed result (2026-11-03)**:

| Item | Result |
|---|---|
| Columns added | `workflow_node.resource_id bigint` / `seq int` (live + `20_kernel.sql` + `96_schema_fix.sql`) |
| Dual write | `saveGraph` writes the columns on save (the XML stays authoritative, bpmn-js must be able to open it) |
| Backfill | `BindingBackfill`: 1009 workflows / 13,990 rows; `--check` mode reports **0 mismatches** |
| Read-side switch | ETA, timeline and node binding all read the columns back (no more XML parsing) |
| Drift guard | `verify-device-chain.sh` gains a 9th assertion group (37 → **38**) |

> An incidental finding: `seq` must be computed in **XML document order**. The first version
> used the parser's internal Map iteration order, so the backfill and the check disagreed
> (the same XML produced a different order on two runs).
> `BpmnGraphParser.documentOrder()` counts only real node ids (skipping
> `Def_`/`Proc_`/`Diag_`/`Plane_`), otherwise seq would start at 3.
>
> The XML of workflow 1248 is itself broken (unbound `di` prefix) and is skipped with a
> message during the backfill — dirty data like this needs a separate fix.

**Why not do it now**: it does not affect correctness, only "every new feature repeats it".
Gantt is the first feature that really pays this price (it has to compute a timeline in
order), so it was placed before M9 rather than later.

**What has to be solved**: today it is "island-level mutual exclusion + first come first
served"; with a dozen experiments queueing you can neither see nor adjust anything.

**Scope (feature list)**

- [x] Schedule Gantt (visualisation: which experiment, on which device, when) — `ffa497c` read-only version / `95fa8b3` by-device view / `052ee59` drag-to-reorder; design in `docs/多实验并行调度-设计.md`
- [x] EDD + priority + **manual queue-jumping (drag to reorder, writes priority)**
- [x] The "estimated wait" when a device is contended is written back to the experiment list (`f7120a2` backend / `f0de15c` frontend)
- [x] `scheduler/status` given a page (`/scheduler`: island locks / queue detail / worker pool; `f0de15c`)

**Acceptance (measured 2026-11-03, `scripts/verify-scheduler-acceptance.py`, 8/8)**:
3 experiments on one island are strictly mutually exclusive, dispatch order == submission
order (log completion order + queue position); across 3 islands maxRunning=3 was observed
in every round (concurrency holds, MAX_CONCURRENT=4), no island was ever concurrently
occupied; the Gantt page renders correctly.
The original acceptance condition — "3 experiments run concurrently, order is consistent,
mutual exclusion is not broken" — **is met**.

**M9 conclusion (2026-11-03)**: all 4 scope items are done (Gantt / EDD + manual
queue-jumping / estimated-wait write-back / scheduler page), and the acceptance script is
re-runnable.

**Dependencies**: M8 (you only dare touch scheduling once e2e exists) | **Estimate**: 1–2 weeks

---

### M9.5 · Unified orchestration (second review passed 2026-11-03; S1/S2-A/S3/S3.1 implemented)

**What has to be solved (state at the 2026-11-03 second review)**: the two orchestration
models have been consolidated — `experiment_process` is the only structure that is
executed, compiled into the database in a single transaction when the canvas is saved (S1
is live); dependencies between workflows are stored as `experiment_process_edge` edges and
ordered with Kahn's topological sort (S2-A is live).
S3 batching is live: `experiment_batch` + `experiment_batch_run`, one run per batch, a
failure re-runs only that batch and keeps the history; S3.1 completed the in-batch run
resume/stop write-back, the `batch_split` configuration entry point and the
same-batch re-run concurrency guard (remainder in the implementation checklist §9).

**The four questions** (details in the design draft):

| Question | Proposal |
|---|---|
| Unify graph and process | **process wins**, the graph only stores layout; the canvas compiles into the database on save; the table becomes a read-only mirror |
| Share one canvas framework? | Keep two editors for now (the canvas compiles into process); in the medium term turn the experiment into BPMN as well (`callActivity`), together with the DAG |
| Priority / nesting between workflows | Use **partial order (DAG) + priority**, and **do not implement nested sub-experiments** (the real need behind nesting is reuse, which batches/templates cover) |
| Batching | `experiment_batch` + `experiment_batch_run`; one run per batch, a failure re-runs only that batch, and the re-run history is kept |

**Implementation state (2026-11-03 evening)**:

- `S1` ✅ (`86ef1c1`): saving the canvas became a **single entry point in a single
  transaction**, writing `experiment_graph`, the compiled `experiment_process` and
  `experiment.version` in one transaction; an illegal graph is rejected and neither side
  is written.
  Regression: `ExperimentGraphCompileSelfTest` (pure logic) + `ExperimentGraphPersistenceSelfTest` (with database).
- `S2-A` ✅ (`ae06025`): dependencies become an `experiment_process_edge` edge table, the
  executor orders them with Kahn's topological sort and rejects cycles; legacy data
  without edges is ordered by ascending `seq` (same as before the change). **Only partial
  order and physical serialisation are promised, not wall-clock concurrency.**
  Regression: `ExperimentOrderSelfTest` (pure logic).
- `S3` ✅ (backend `bbcc5bc` + frontend `fd59d90`): a `batch_split` switch plus the two
  tables `experiment_batch` / `experiment_batch_run`; batches run serially, a failed batch
  does not stop the rest, a single batch can be re-run with an incrementing attempt and
  the old history kept (the persistence shape agreed in review item B2); frontend batch
  cards, "re-run this batch" and the per-attempt history.
- `S3.1` ✅ (backend `c915948` + frontend `5f12696`): in-batch run resume/stop write back
  to the batch layer (D8–D10), `PUT /api/experiments/{id}/batch-split` plus the frontend
  "batch settings" entry point (D11), a row lock for same-batch re-runs (D12).
  Regression: `BatchPlannerSelfTest` 39 + `BatchDbSelfTest` 155 + `verify-exp-batch.py` 35/35;
  remainder (D8: the batch transaction and the experiment aggregation are two writes) in
  the implementation checklist §9/§10.5.
- `S3.2` ✅ (`3aa4837`): **batched asynchronous execution** — `POST /run?async=1` for a
  batched experiment creates the next batch run and lets the scheduler continue
  batch by batch; all of §11.3's remainder is closed (restart rehydrate / automatic
  re-queue after a hold is released / async vars / deriving a paused batch / **the frontend
  "run asynchronously" entry point**)
  (a failure continues / aborted stops / a hold stops at the batch boundary; the semantics
  of the synchronous `/run` are unchanged). Remainder (after releasing a hold you must
  start again; a restart does not rehydrate the queue) in the implementation checklist §11.3.
- Delivered alongside: scoped holds via `runtime_hold` (`ce3eaf0`) + experiment-level
  pause/stop/resume (`4458c88`) + pure-logic/with-database self-tests
  (`54c1318`/`9aa32ac`, gate pure-logic threshold 413 → 423).

**Why it is a separate phase**: it is a "data model + executor" change, which is a
different kind of work from M9's "dashboard / drag-and-drop". After S1 **the canvas no
longer lies** (saving compiles into the database); but batching (S3) is still a layer that
has to touch the data model and the executor.

**Design document**: [`docs/设计-实验编排统一-v0.1-草稿.md`](../设计-实验编排统一-v0.1-草稿.md)
(option comparison, costs, open questions, prompts for the reviewing AI)

**Implementation checklist**: [`docs/设计-实验编排统一-v0.1-实施清单.md`](../设计-实验编排统一-v0.1-实施清单.md)
(splits `S1/S2/S3` into backend / frontend / tests / risks / exit conditions, ready to schedule)

**Second-review gate (2026-11-03 15:36, passed; verified item by item)**:

1. ✅ **B1 / B2 / H1** are closed (review record in [`20261103-03-编排设计评审.md`](../评审记录/20261103-03-编排设计评审.md) §7);
2. ✅ the `S2` scope is fixed as **partial order only (S2-A)**; true concurrency + three-tier resources + the wait graph are listed as an **independent upgrade** (S2-B), not in 1.0;
3. ✅ the `S3` data model is defined (`experiment_batch` + `experiment_batch_run`, implementation checklist §4.2 and §8.2);
4. ✅ the `S1` regression cases are defined and passing: a linear graph saves → `graph`/`process` stay in sync; an illegal graph → neither side is written;
   a simulated `process` write failure → the `graph` is rolled back (`ExperimentGraphPersistenceSelfTest` covers it with a database).

**Conclusion**: the gate is met, so `M9.5` can be **scheduled** from 2026-11-03; S1/S2-A are live, S3 is being implemented.

---

### M10 · Real-device integration

**What has to be solved**: every "pass" today is a simulation result.

**Scope (feature list)**

- [ ] Onboard the 360R / PlateLoc manuals into the model library (documents missing) — **the preparation checklist is out**: `docs/设备/M10-手册到型号库接入准备清单-v0.1.md` (document list / parameter extraction template / model-library gap / 10-step DoD / open questions, 2026-11-03); **already done ahead of time**: the model-library page `/models` (`a1b21ef`) + `GET /api/meta/device-models` (`f2d7e8e`) + `scripts/device-go-live-check.py` (`4196f1c`)
- [ ] Long-task (30-minute incubation) timeout policy: distinguish "device busy" from "device dead"
- [ ] On-site integration checklist (heartbeat, driver endpoint, error-code mapping, shadow probe)
- [ ] Feed real-device anomalies back: add the codes seen on site to `error_code` + policies

> **Follow-up tasks (noted 2026-11-03, deferred)**: all M10 items wait on manuals/on-site
> material; the remaining device-related UI polish (model-library menu icon consistency,
> the device-type vocabulary, production-line ledger detail links) is deferred as well and
> is out of scope for the current round.

**Acceptance**: at least one complete on-site process (≥3 devices, ≥5 nodes) runs end to
end, with a recording of failure → recovery → continue.

**Dependencies**: M6/M7 + device manuals | **Estimate**: 2–4 weeks (including on-site time)

---

### M11 · Platformisation

**Scope**: multi-tenant isolation, role permissions (`app_role` is not used today),
reporting / BI, multi-instance deployment (locks currently live in-process).

**Permissions (basic) were closed on 2026-11-03**: the existing `sys_user.role`
(admin/operator/viewer) is reused + backend role whitelist / last-administrator protection +
frontend role awareness / read-only experience; the legacy `app_role`/`role_permission`
tables are **not enabled** (to avoid two tracks — see the development log for 2026-11-03).
Remaining: multi-tenant isolation, reporting / BI, multi-instance deployment.

**Not listed here**: reporting only becomes meaningful once enough real-device data has
accumulated.

---

## 4. Priority → phase mapping (the phased feature list)

> This table is the **single authoritative version** of review §8.8 and 00-index §8;
> other documents only reference it and do not repeat it.

| Priority | Item | Lands in | Status |
|---|---|---|---|
| **P0** | Loops write one `run_node` row per iteration (`loop_index`) | M6 | ✅ Done |
| **P0** | `run_node.parent_node_id` gains a cascading foreign key | M6 | ✅ Done: the live database already had the FK, **the schema script missed it**, and it has been added to `96_schema_fix.sql` |
| **P0** | Historical dirty-data cleanup (dangling parents) | M6 | ✅ Nothing to clean: measured 0 dangling rows out of 120,268 |
| **P1** | Add / delete error codes | M7 | ✅ Done (POST/DELETE + page) |
| **P1** | `scope` consistency validation | M7 | ✅ Done (the list flags "scope not covered"; saving a policy returns a warning) |
| **P1** | Expression arithmetic | M7 | ✅ Done (`+ - * /`, including precedence and unary minus) |
| **P1** | Alert thresholds configurable in the UI, effective immediately | M7 | ✅ Done (`sys_setting` + a card on the alert page; effective on the next cycle) |
| **P1** | Bulk ignore supports filters | M7 | ✅ Done (`835aa43` bulk ignore + `73dc4f4` filter by pause kind + time range; the paused-queue page supports selection and filtered ignore) |
| **P1** | CI gate (hard thresholds; currently 536 / 41 / 24) | M8 | ✅ Done (`scripts/ci-gate.sh` + GitHub Actions; the thresholds rise with the case count — see the constants at the top of `ci-gate.sh`) |
| **P1** | Frontend e2e, 5 main chains | M8 | ✅ Done (`scripts/e2e-smoke.py`, 24 assertions) |
| **P1** | Long-stability soak | M8 | ✅ Done (`scripts/soak-test.sh`, memory baseline taken after warm-up) |
| **P1** | `workflow_node` gains `resource_id` / `seq` (removing the XML parsing dependency) | M9 (prerequisite) | ✅ Done (dual write + backfill of 1009 workflows + a consistency assertion in the device chain) |
| **P2** | Schedule Gantt + manual queue-jumping | M9 | ✅ Gantt (by experiment / by device) + drag-to-reorder are done; **explicitly parallel interleaving is not done** (see `docs/并行能力验证-20261103.md`) |
| **P2** | Split `ApiServer` into controllers (2,725 lines / 49 routes) | M9 (incidental) | ✅ Done (`deb6aed`: ApiServer 3331 → 679 lines + 8 domain controllers; 156/156 routes owned, HTTP behaviour identical before/after 34/34, with database 860/0, e2e 20/0) |
| **P2** | Consolidate the status mapping (one copy front and back → keep one) | M7 (incidental) | ✅ Done (`81f14c3` the backend is authoritative in `StatusVocabulary` + `GET /api/meta/status-vocabulary`; `cb305be` the frontend `utils/status.ts` de-duplicates 8 pages; pure logic 479 → 499, with database 860/0) |
| **P2** | 360R / PlateLoc manuals | M10 | Waiting for material |
| **P3** | Multi-tenancy / permissions / reporting / multi-instance | M11 | Partial: **the basics of role permissions are closed** (2026-11-03: `sys_user.role` admin/operator/viewer + whitelist / last-administrator protection / frontend read-only experience, RBAC integration test 20/20); multi-tenancy, reporting and multi-instance remain |

**Already done (before this round / in this round)**: separation of the `skipped` vs
`bypassed` semantics, resume from a breakpoint (including step-row cleanup and renumbering),
crash recovery with a 7-day window + ignore, recovery scope persisted, multiple policies
per error code, device methods persisted, workflow execution records, run-graph colouring,
experiment-level pause/stop/resume (backend `4458c88` + frontend entry point + the
"on-site hold" card `85a404e`), unified experiment orchestration S1/S2-A/S3 (canvas compiled
into the database `86ef1c1` / partial order `ae06025` / batching `bbcc5bc` + frontend
`fd59d90`).
Details in `docs/评审包-20261103/03-设计文档.md` and `docs/开发日志.md`.

---

## 5. Definition of done (DoD)

When a phase closes, all of the following must hold at the same time:

| # | Condition | How it is judged |
|---|---|---|
| 1 | The feature can be demonstrated | You can click it in the UI (not just an API) |
| 2 | There are automated assertions | Unit tests or a script; **the numbers must be re-runnable** |
| 3 | The documentation is in sync | At least one of the four (functional / technical / design / test) documents is corrected, with the numbers in sync |
| 4 | The baseline is not broken | `test-all --pure-only` ≥ 536 with 0 failures; `verify-device-chain` ≥ 41 with 0 failures; `tsc` 0 errors |
| 5 | Known gaps are written down | Listed in the "not covered" section of the corresponding document; not to be waved through verbally |

> Item 4 is a hard gate. **A falling number means the phase is not done** (that is exactly
> where the blocking item in review 20261103-01 came from: the document said 373, the
> measurement said 368, and the third party's first reaction was "it regressed").
>
> **Addition: M9.5 is a design-driven phase and must additionally satisfy its own entry
> gate** before it enters the timeline (see the "second-review gate" in `M9.5 · Unified
> orchestration` above); otherwise it stays a draft and no date may be scheduled.

---

## 6. Explicitly not doing (keeping the status quo)

| Not doing | Why |
|---|---|
| Thread-level parallel execution of nodes | Devices are shared physical resources; lock contention → deadlock → collision; the current "logical parallelism" is enough (design document D3) |
| The full BPMN element set (timers / messages / subprocesses) | Only the subset that gets the work done; the rest is ignored at parse time + reported by the preflight |
| Cross-experiment scheduling optimisation inside the console | Belongs to M9, and needs the Gantt and real data first |
| Recipe / reagent / sample management | The tables are placeholders, nothing is connected |
| Strong multi-tenant isolation | Only single-tenant has been exercised; revisit in M11 |
| Using `eval` / a script engine for expressions | Security red line (design document D6) |

---

## 7. Relationship to the reviews

The remediation from review [20261103-01](../评审记录/20261103-01-评审包复核.md) is
complete (B1/H1/H2/P2 fully closed); its §8.8 "not handled in this round" and §8.9
"recommendations for the next review" have been merged into **M6** and **§5 DoD** above
respectively.

The follow-up review [20261104-01](../评审记录/20261104-01-评审包复核.md) is fully
remediated (1 blocking + 1 high + 3 P2 all closed; `BUG-20261104-01`~`-05` are closed);
this round also included a documentation-consistency pass (A2, see
`docs/评审包-20261104/02-后续规划.md`).

The next review should run the three commands from §8.9; after `M6` is complete, one
assertion each should also be added for `loop_index` and the `parent` foreign key.
