# Material Identity and State · Design (v3.3 · S0 freeze candidate)

> **Status**: **pending re-review confirmation** (v1 2026-11-11 → v2 merged three **reviews** →
> v3 merged three independent **designs** → v3.1 merged the fourth-round review O-1…O-9 →
> v3.2 disposed of the fifth independent review → **v3.3 merged the sixth-round supplementary
> architecture review + the 19 findings of this session's sixth independent review**; the
> disposition summary is in **§19**)
> **Decision number segment**: this document claims **D401-D4xx** (the register is in §18.2; to be
> merged into the segment table in §7 of `docs/评审规范.md` once S0 is frozen)
> **Related**: [Process Orchestration Capability Gaps and Landing Route](process-orchestration-gaps-and-roadmap.md)
> (the gap analysis, G1/G2/G11); the fourth-round review `docs/评审记录/20261112-04-openai.md` plus
> the attachment "Unified Architecture V1.0";
> **the fifth independent review has two independent records**:
> `docs/评审记录/20261112-05-mavis.md` (the concurrent session · MiniMax subagent) and
> `docs/评审记录/20261112-06-mavis.md` (this session · DeepSeek subagents ×3 + the summary);
> **the sixth round likewise has two independent materials**:
> `docs/评审记录/20261112-07-mavis.md` (this session · MiniMax subagents ×3: source facts / a real
> DDL run and the cross-tenant checks / design consistency and disposition closure) and the
> user-supplied sixth-round supplementary architecture review (the external attachment "我认真看完了这份 v2.1。我的总体判断",
> not filed in the repository, a **documentation-only review**, whose adjudications are §0.4's N1–N8);
> v1's three reviews `20261112-01-grok.md`, `-02-kimi.md`, `-03-openai.md`;
> the concurrent session's independent design `docs/物料身份与状态-设计-v2.md` (for comparison)
> **Code baseline**: `main` @ `65fe5ef` (documentation only); the code has not changed since `0eb2a99`
> **⚠ This is still a design; no code and no schema change has been made.**
>
> **How this document came to be**: the same question produced **three mutually independent
> designs** (see §0.1). This document is their **convergence**: it keeps only the **points all
> three agree on** and the **disagreements that have been adjudicated**. The superseded drafts
> are kept for comparison and are no longer candidate baselines.
>
> **The v3.2 revision**: the fifth round was reviewed by 3 independent reviewers (source facts /
> real DDL runs / design consistency) who found **2 P0s + a batch of P1s**, all disposed of one by
> one (§18.1). The substantive additions/rewrites in that round: the carrier of the execution fact
> (§5.3), the T1 failure recovery channel and quarantine (§5.2b/§5.8), the downstream dispatch
> gate (§5.2b/§5.7), the execution-path inventory and the known gaps (§16.1), the six-state effect
> state machine and the remaining O-2 cross-tenant constraints (§7), the old-data semantics (§10),
> and the ten mandatory questions re-answered (§17).
>
> **The v3.3 revision**: it takes v3.2 as the baseline and absorbs the sixth-round attachment,
> with no rollback of the independent declaration column, the `command_ledger` division of labour
> or the run isolation. It adds the standalone `execution_resolution_status` (§5.2c), the complete
> before/after of an event + `changed_fields` and the replay constraints (§7⑦), the Worker row-lock
> claiming / backoff / cap (§5.8), and the target-resolution recovery evidence (§6.3/§8); at the
> same time it adjudicates the loop-gate carrier and the `REJECTED` node mapping left over from
> v3.2 (§5.2b/§13.2), and fixes the remaining contradictions around attribution, empty plates and
> identity conflicts.
>
> **The sixth-round review increment of v3.3 (this session, 19 findings)**: it caught and fixed
> **4 P1s** — ① the round row is **not an immutable fact** (a later write overwrites an earlier
> one) ⇒ the "use the round row as the carrier" approach is rejected outright (§5.5 / §13.2-15);
> ② the identity resolution of §6.3 **missed the 5th source** `position_status`, which conflicts
> with the DDL hard rule "the system never guesses" (§6.3 / §13.2-16);
> ③ the audit table → effect table is a **single-column foreign key** ⇒ cross-tenant audit rows can
> be written (§7⑥/§7⑧ / §13.2-17);
> ④ the partial index used by the gate **misses `cancelled`** ⇒ the query degrades into a full
> table scan (§7④ / §13.2-18).
> It also closes the two holes where the sixth-round attachment **said something the DDL did not
> have**: `execution_resolution_status` (§7④) and `changed_fields` / `from_state_version /
> to_state_version` (§7⑦). An earlier v3.3 **intermediate snapshot** recorded 30/30 successful
> statements, but that result does not cover the fields and constraints added afterwards; **the
> current final merged DDL has not been run in this round** (§7 / §16.3).
> The item-by-item disposition is in **§19**.

---

## 0. Review entry point

| Item | Content |
|---|---|
| **What is being reviewed** | The domain model for material identity and state, the mechanism for execution facts and material effects, the data model, the interfaces and the pages |
| **Why now** | Checking against a real process flowchart showed that LabOS orchestrates "actions" while the real process orchestrates "material"; a container has an identity and a position but **no plate-level process stage** ⇒ the site cannot answer "what is this plate holding now", and material hand-over across processes has no semantics either |
| **Read these three first** | §3 (the facts) → §4 (the domain model) → §5 (execution facts and material effects) |
| **How v1 differs** | v1 had **6 factual errors** and **4 model defects**, each found independently by at least one of the three reviews ⇒ v2 fixed all of them; the list is in §0.1 |
| **The focus of this review round (the fifth round is closed)** | ① which table actually carries the execution fact (§5.3/§7); ② how a T1 failure blocks and how it recovers (§5.2b/§5.8); ③ what mechanism makes the downstream "not dispatch" (§5.2b/§5.7); ④ which physical execution paths still do not enter the unified handler (§16.1); ⑤ whether the O-2 cross-tenant constraints are really complete (§7) |
| **Known boundaries** | See §13 (**adjudicated boundaries + items still to close**) and §16.3 (**the S0 freeze decision**) |

### 0.1 How this document came to be: the convergence of three independent designs

The same question produced **three mutually independent designs** (not one design reviewed
three times):

| # | Source | File | Shape |
|---|---|---|---|
| A | v2 of this session (merging the findings of the Grok / Kimi / OpenAI **reviews**) | `docs/物料身份与状态-设计.md` (the previous version of this file) | the smallest landing: extra columns on `plate` + an effect table + an audit table |
| B | the **independent v2** of the concurrent session | `docs/物料身份与状态-设计-v2.md` (kept for comparison) | four kinds of facts apart + 8 invariants + two new columns on `run_node` |
| C | a third independent design draft (given by an external reviewer) | not filed (its conclusions are absorbed here) | the full Material / Location / Content / Event / Effect five-object model |

**What all three agree on (12 points, all adopted here)**:

| # | Consensus |
|---|---|
| C1 | **Identity ≠ position**: a position is an attribute or relation of the material, not its identity |
| C2 | **An execution declaration ≠ a physical fact**: the flow declaration, the device report and the system confirmation are three different things |
| C3 | **Current state and historical events are apart**: the current value may be projected, the history is never overwritten |
| C4 | **Source / current attribution / processing history are three things**, not one `owner_*` |
| C5 | **Loop round, retry count and execution instance must be modelled apart** (`loop_round` / `attempt` / `execution_id`) |
| C6 | **Device execution and the database transaction are not one thing**: a physical action cannot be rolled back |
| C7 | **The one-to-many structure must be reserved**: the first phase restricts it with validation rules rather than designing the tables to be one-to-one forever |
| C8 | **Not declared ≠ no effect**: an explicit classification is required (NONE / DECLARED / UNKNOWN) |
| C9 | **The position lock reuses the existing `PositionLock`**, no second occupancy system |
| C10 | **Tenant isolation is not actually implemented yet** (`TENANT = 1L` is hard-coded); a new table carrying `tenant_id` does not mean isolation is in place |
| C11 | **S0 settles the model first, S1 creates the tables**: do not freeze the DDL and then patch the model afterwards |
| C12 | **Consumables are a later phase**: the first phase does not build a half-finished inventory / consumption feature |

**The disagreements and how they were adjudicated (7 points)**:

| # | Disagreement | A (this session's v2) | B (independent v2) | C (third design) | **Adjudication** | Reason |
|---|---|---|---|---|---|---|
| D1 | The anchor of the idempotence key | `run+node+loop+plate` | effect_key | the logical execution instance + effect_index | **the structured unique key `(tenant, run, node, loop, effect_index)`** (v3.2 correction: no string `execution_id`, see §5.5) | C's anchor is the most stable, and it also solves "one node produces several effects" |
| D2 | Where the execution fact lives | the effect table doubles as T1 | two columns on `run_node` | a separate Execution Fact object | **a three-way split (v3.2 correction)**: **the device command fact = `command_ledger` (booked before dispatch / idempotency key / UNKNOWN) + `run_command` (the result ledger after the response)**; the node-level sync status goes on `run_node`; the material effect gets its own table | No second command ledger, and the effect table does not double as the execution fact; the earlier "reuse `run_command`" was wrong (it has no `command_id` column) |
| D3 | The effect state machine | 3 states | 3 states | **5 states** | **6 states (v3.2 adds `CANCELLED`)** | "the effect violates the rules" and "the physical fact is unclear" are two completely different dispositions; effects not applied after the run ends need a terminal state (CANCELLED), otherwise compensation would replay a dead intent |
| D4 | The relation between the node state and the effect state | decoupled (not detailed) | decoupled (not detailed) | explicitly "the node only counts as fully complete once the effect reaches the required confirmation level" | **adopt C, and give it a landing** | Do not change the `node_status` enum; a **dispatch gate** (§5.2b, a predicate injected into GraphRunner/executeAll that reads `run_node.material_sync_status`) decides whether the downstream is dispatched; ⚠ do not use `runtime_hold` to hold nodes: its scope is actually `site\|island\|experiment` (no run/node granularity) |
| D5 | The name and fields of the event table | `plate_state_audit` | `plate_material_event` | `Material Event` (with `occurred_at`/`recorded_at`) | **`plate_material_event` + the two timestamps** | "event" expresses an immutable event stream better than "audit"; the physical time and the recording time must be apart |
| D6 | How much of the domain model lands | the lightest (extra columns on `plate`) | in between | the heaviest (three relation tables) | **a compromise**: Material = the existing `plate` plus columns; Location = **reuse** `position_state`; Content = **reuse** `plate_well`/`sample`/`sample_aliquot` | The three agree on the future model; they differ only on how many tables the first phase builds, and reusing what exists avoids a second source of truth |
| D7 | The criterion for consumables | independent identity / lifecycle traceability | an independent entity plus position and attachment history | independent identity / lifecycle / stock / batch / expiry / reuse / traceability | **the union of the three** | The three word it differently but mean the same thing |

### 0.2 The list of v1's errors (publicly acknowledged, per §4 of `docs/评审规范.md`)

The **factual errors** the reviews pointed out (my draft was simply wrong — not a difference of
opinion):

| # | What v1 said | The truth | Why I got it wrong |
|---|---|---|---|
| E1 | "`GraphRunner.java:604` is the node-completion hook" | that line is in the **`Kind.BUILTIN` branch**; the in-process completion point of a device TASK is **`:649`** (`sm.executeNode`), and `:650` is the status write-back | I saw the line number and assumed it was right — **I never checked which branch it was in** |
| E2 | "`WorkflowService` is in the boot layer" | it is in `src/main/java/com/labos/kernel/workflow/` (the **domain layer**); the boot layer one is `WorkflowWebService` | I mixed up two similarly named classes |
| E3 | "`plate` only has `status(active)`" | the default is `available`; the **values suggested in the column comment** are `available / in_use / consumed / discarded` (**there is no CHECK**; the code actually writes `active`) | I only checked the **actual data values**, not the **column definition and its comment**; and I took a comment for a constraint |
| E4 | "`plate_type` only ever holds `unknown`, change it to six process roles" | the default is `'96'`; the values suggested in the comment `96 / 384 / 24 / tube_rack` = **plate format specification** (no CHECK; automatic plate creation writes `unknown`) | same mistake: I took "what the six plates happen to hold" for "what the column means" |
| E5 | "only the first of the four dimensions is implemented" | **the `plate_well` / `sample` / `sample_aliquot` tables have existed all along** (0 rows) ⇒ the **well-level sample model is already there** | I grepped one table (`plate`) and never looked at the neighbouring schema |
| E6 | "`NodeEffectListener` extends the existing listener pattern" | `BuiltinListener` **only covers builtin nodes** and **swallows exceptions**; device tasks go through `stateMachine.executeNode` | I wrote "the pattern is similar" as if "the mechanism exists" |

**The model defects** (found by the three reviews, consistently or in complementary ways):

| # | Defect | Fixed in |
|---|---|---|
| M1 | "must be in the same transaction as node completion" **cannot be satisfied** in the current architecture and contradicts "a failure must not roll the node back" | §5 becomes a two-phase T1/T2 |
| M2 | one set of `owner_*` columns carrying three different concepts, so the production relation is lost on every overwrite | §4.5 splits the three concepts |
| M3 | the idempotence key omits `loop_index` ⇒ multiple legitimate effects from the same execution node in a subprocess loop can hit the unique index; the common shape is **a multi-action subprocess changing plates and source/destination positions per round**, not an isolated node spinning on its own | §5.5 / §6.2a |
| M4 | `target.by=position` is ambiguous (source position or target position?), and a position is a spatial attribute, not a material identity | §6.3 |

### 0.3 The key adjudications after the fifth independent review (20261112-05 / 06) (v3.2)

> Basis: `docs/评审记录/20261112-05-mavis.md` (the concurrent session) and `20261112-06-mavis.md`
> (this session's 3 independent subagent reviews + the summary) plus the three findings files.
> The complete disposition table is in §18.1.

| # | Adjudication | Why (details) |
|---|---|---|
| R1 | **The execution-fact carrier = `command_ledger` + `run_command`, split between the two tables**; `run_command` **does not have** a `command_id` column (the earlier draft was wrong), `command_id` lives in `command_ledger` | The command ledger is booked **before dispatch**, which is what expresses the "already dispatched, no response yet" window; `run_command` is the result ledger written **after** the response (§5.3/§7) |
| R2 | **A T1 failure = isolation at run level (quarantined + an experiment-level `runtime_hold`), not "run failed"**; **resuming and re-dispatching device commands is forbidden** before isolation / human confirmation | the existing `markRunFailed` has no channel to escalate to manual; after a restart a manual continuation would execute the already-completed physical action a second time (§5.2b/§5.8) |
| R3 | **The downstream "not dispatched" = a dispatch-gate predicate** (GraphRunner / executeAll checks the `material_sync_status` of the predecessor nodes before dispatching), **not** a "scheduler dependency check" | node dispatch happens inside GraphRunner's in-memory queue; `ExperimentScheduler` only reaches workflow-step granularity, and no node-level dependency check exists (§5.2b/§5.7) |
| R4 | **The inventory of physical execution paths must be complete**: 2 production paths already enter the unified completion point; `/api/experiments/flow-run` and ShadowRunner / single-command probes are **known gaps** that S0 has to dispose of first | the former bypasses `RunStateMachine` and drives the device directly, the latter performs real actions but persists no execution fact (§16.1) |
| R5 | **The effect state machine has 6 states** (adding `CANCELLED`); `NEEDS_RECONCILIATION` / `REJECTED` / `FAILED` all need a human loop (permission + reason + event + API) | only "FAILED→PENDING" is not enough; effects not applied after the run ends would dangle forever (§5.8/§8) |
| R6 | **The O-2 cross-tenant/reference constraints remain in force; §7⑧ adds two identity-binding foreign keys to the sixth round's 12, for 14 `ADD CONSTRAINT` statements now** (4 unique keys + 9 foreign keys + 1 NULL CHECK) | the earlier draft only gave 3 groups, so O-2 was not closed; a previous version was measured to be able to create the corresponding constraints once `uq_run_tenant_id` was added; the current merged version including the scanning-binding table still has to be re-run (§7⑧) |

**The adjudications of the disagreements with the concurrent independent design v2.2 (v3.2 decides each)**:

| # | v2.2's claim | v3.2 adjudication |
|---|---|---|
| R7 | v3 §5.8's "crash before T1 ⇒ just re-run" should be rejected | **adopt v2.2**: reconcile first, forbid re-running, isolate (§5.8) |
| R8 | do not persist a string `execution_id`; use a structured unique key | **adopt v2.2** (§5.5 / §7⑤) |
| R9 | the recovery audit uses an append-only effect action, not a `RECONCILIATION` event | **adopt v2.2** (§5.8 / §7⑥⑦) |
| R10 | do not add `current_run_id` / `origin_*` | **adopt v2.2** (§4.5 / §7②) |
| R11 | acyclic graphs use `loopIndex=0`, cyclic ones use `visit`; the gate only lets `not_required/applied` through | **adopt v2.2's executable rule** (§5.2b / §5.5 / §5.7) |
| R12 | the empty reservation for multiple inputs/outputs is an empty promise | **partly adopted**: contract v1 this round only opens 1..N effects on the `outputs` side; inputs stay one-to-one; `operations` is a placeholder only and preflight rejects unknown values |

### 0.4 The new adjudications after the sixth-round supplementary review (v3.3)

| # | Adjudication | Landing |
|---|---|---|
| N1 | **Execution confirmation and material sync are completely separated**: a new `execution_resolution_status`; `none/legacy/transition` all forbid an ordinary continuation as soon as the physical result is unknown | §5.2c / §7④ / §8 |
| N2 | **Events are replayable**: the from/to columns always keep the complete before/after of both state dimensions, plus separate `from_state_version / to_state_version + changed_fields`; NULL, "not modified" and "explicitly changed to unknown" are no longer conflated | §5.4 / §7⑦ / §11 |
| N3 | **The Worker only handles PENDING**: a short transaction per effect with `FOR UPDATE SKIP LOCKED`, a bounded exponential backoff, a maximum count, and over the cap a transfer to FAILED for manual handling; no persistent PROCESSING state is introduced | §5.8 / §7⑤ |
| N4 | **The loop gate does not depend on the round `run_node` that does not exist yet**: it aggregates over the declaration snapshot and the actual effects' `source_loop_index/effect_index`; a missing required effect also blocks | §5.2b / §13.2-13 |
| N5 | **The node material status gains `rejected`**: it maps one-to-one onto the effect REJECTED, blocks the gate, and is not merged with the retryable FAILED | §5.2b / §7④ / §13.2-14 |
| N6 | **A reconciliation does not go directly to APPLIED**: completed → PENDING → T2; not_completed → CANCELLED; still_unknown stays in reconciliation; the supplementary target evidence must be auditable | §5.8 / §8 |
| N7 | **AUTO is a reserved coding namespace, not a heuristic judgement that "looks like a fake barcode"**; a system-generated identity must not drive a material migration | §6.3 / §17 Q1 |
| N8 | The older draft's "put the declaration back into params" and "trust the device ID and persist it when the targets conflict" are not rolled back; the column stays independent, and an identity conflict is reconciled first with no event written | §6.1 / §6.3 |
| N9 | **The subprocess is the process unit of a loop**: each round explicitly binds the source position, destination position, plate reference and other parameters; the same binding result drives both device parameters and the material target and is frozen with the execution evidence; `loop_index` remains only an idempotence safeguard | §5.5 / §6.2a |
| N10 | **Late binding by a mid-run scan is supported**: the scan first writes a run-scoped identity context and downstream nodes use `scanRef`; no plate is guessed before scanning. An already registered plate resolves to `plate.id`; an AUTO placeholder may be bound once under controlled evidence checks, while any conflict goes to reconciliation | §6.3a / §7⑦a / §8 |

---

## 1. The problem: questions the site asks that the system cannot answer

| Question | Today's answer |
|---|---|
| Which step of the process is this plate at? | ❌ cannot be answered. `plate` has no plate-level process stage column |
| Who produced this plate, and who processed it last? | ❌ cannot be answered. `plate` has no production-source column; the `plate_material_event` table does not exist yet |
| Which material results did this run declare? | ❌ cannot be answered |
| How many of this batch of plates are still empty? | ⚠️ **half answerable**: the `plate_well.status` model (`empty/filled/consumed/invalid`) exists, but there is no data in it ⇒ the definition has to be pinned down (§4.2) |

**Why these matter**: the input of one process is often the artefact of the previous one (the
same plate with a different content). If the system cannot say "what is this plate now", then
there is no semantics for material hand-over between processes.

---

## 2. Root cause: what is missing is the plate-level process stage, not the well-level content

**Correction to v1** (E5): the well-level sample model **has been there all along**:

```
sample            (barcode, sample_type, batch_id, volume, concentration, ...)
sample_aliquot    (parent_id, barcode, volume, ...)
plate_well        (plate_id, well_row, well_col, sample_id, aliquot_id, volume, status)
```

⇒ What is missing is not "what each well holds" but the **plate-level process stage** and the
**processing attribution**.

⇒ **v1's "four dimensions" framing has to change** (see §4.1): this is not "add a few columns
to `plate`", it is establishing a **Material domain** of which `plate` is one kind.

---

## 3. The current state (every line checked against the source and the DDL)

> **The reading rule (v3.2 correction)**: every row below was checked against the source in the
> fifth review; the "v3.2 add" rows are facts added / corrected in that round.
> ⚠ "word list" = **the values suggested in the column comment, not a database constraint**; at
> run time values outside the word list are really written (see rows 1/2).

| Fact | Where |
|---|---|
| `plate.status` defaults to `available`; the comment's suggested values `available / in_use / consumed / discarded` (**no CHECK**) | `30_business.sql:68` |
| `plate.plate_type` defaults to `'96'`; the comment's suggested values `96 / 384 / 24 / tube_rack` = **plate format specification** (no CHECK) | `30_business.sql:65` |
| **v3.2 add**: the automatic plate-creation path writes values outside the word list `plate_type='unknown'` / `status='active'` (`insertPlateIfAbsent`, triggered by position registration) | `DeviceStateMapper.xml:351-356` |
| the `plate_well` / `sample` / `sample_aliquot` **tables already exist** | `30_business.sql:17-59,78-93` |
| **v3.2 add**: `plate` also carries `position_status` (KNOWN/IN_TRANSFER/UNKNOWN/LOST), `possible_locations`, `location_id` (a **redundant projection** of `position_state`) | `93_m1_parallel.sql:194-201`; `30_business.sql:69`; `PositionLock.java:221-232` |
| the `run_node` unique key `UNIQUE (run_id, node_key, attempt, loop_index)` ⇒ **the loop round is a first-class dimension** | `20_kernel.sql:326-328` |
| the in-process completion point of a device TASK is `GraphRunner.java:649` (`stateMachine.executeNode`); `:650` is the status write-back; `:604` is in the `Kind.BUILTIN` branch | `GraphRunner.java:604,646-650` |
| `BuiltinListener` is only called in the builtin branch, and `emit()` **catches the exception and ignores it** | `GraphRunner.java:577-632` / `RunStateMachine.java:257-267` |
| the common completion point of the graph path and the sequential path for **device TASK nodes** = `RunStateMachine.executeNode` returning `COMPLETED` | `RunStateMachine.java:87-112`; `WorkflowService.java:1057,1065`; `GraphRunner.java:649` |
| **v3.2 add**: builtin logic nodes (BUILTIN) **do not go through** `executeNode` (GraphRunner completes them itself); the sequential fallback path does not contain logic nodes either | `GraphRunner.java:570-632`; `WorkflowService.java:779-787` |
| the sequential path (`sm.executeAll`) **has no node-level completion callback**; but the step-level `running` write-back exists on both paths, and the final completion state is back-filled in a batch after the whole order finishes | `WorkflowService.java:1039-1041,1828-1852,2143-2165` |
| the `run_node` completion update and the `run_command` insert are **separate commits**, and the command commits first (graph path; on the sequential path the completion is written when the whole order ends; **the simulation mode has no `run_command`**) | `WorkflowService.java:2212-2306,1828-1852`; `Mappers.java:12-22` |
| **v3.2 add**: `run_command` is a ledger written **after the response**; **the fact carrier for the "already dispatched, no response yet" window is `command_ledger`** (`command_id varchar(160)` / `idempotency_key` / `state`); `run_command` **has no `command_id` column** | `20_kernel.sql:336-357`; `99f_command_ledger.sql:19`; `DriverStepExecutor.java:675-701` |
| `PositionLock.auditPlateMove` writes its audit with an **independent connection**, logs and moves on, and **deliberately stays out of the position-lock transaction** | `PositionLock.java:206-237,301-317` |
| the plate-taking path already has a **fail-closed ownership check** (it verifies `occupied_by_run`) | `PositionLock.java:328-344`、`DeviceStateMapper.xml:250-257` |
| a position registered without a barcode gets an **`AUTO-<runId>`** placeholder identity, persisted into the `plate` table as its `barcode` | `PositionLock.java:456-486`; `DeviceStateMapper.xml:354` |
| `TENANT = 1L` is **hard-coded**; not only in `WorkflowWebService` but in **40 places in the kernel module** (of which **15 are in the boot layer**) (multi-tenant isolation is even less implemented). ⚠ the sixth-round N07 measured and corrected the earlier draft's "20+ places in the boot layer" scale | `WorkflowWebService.java:48`; `ApiServer.java:93`, `RunService.java:68` etc. |
| the execution side's **device parameter channel** only reads `workflow_node.params->'method_params'` and parses it with a **flat JSON parser** (nested structures get misparsed / passed through). ⚠ **this does not mean "the execution side only reads this one"** (the sixth round's N14): the pre-execution graph assembly also reads `params->'time_constraint'` (`WorkflowMapper.xml:60-67`), and there are `params->>'protocol_name'` / `bound_resource_id` / `equipment_instance_id` (`GraphMapper.xml:127-141,285-296`, `RunMapper.xml:162`, `WorkflowWebMapper.xml:45-48`) — but those **are not the device parameter channel** and do not reach the driver | `WorkflowMapper.xml:60-75`、`WorkflowService.java:1418-1491` |
| **v3.2 add**: the `consumable` / `consumable_lot` tables **already exist** (S5 is a "refactor", not a "create") | `30_business.sql:134,150` |
| **v3.2 add**: the `run.workflow_ver` column exists but `insertRun` **never writes it** (the run-version snapshot has no carrier, see §5.3) | `20_kernel.sql:271`; `RunMapper.xml:508-521` |

---

## 4. The core model

### 4.1 The Material domain layering (new: do not lock it into the `plate` table)

```
Material (the material, an abstract base)
├── Container (a container: it holds things)
│   ├── PCR Plate
│   ├── Deep Well Plate
│   └── Assay Plate
├── Consumable (a consumable: a lid, a cap — anything needing an independent identity or lifecycle trace)
├── Sample (a sample)
├── Reagent (a reagent)
└── Waste (waste)
```

**Only this round**: the plate-level process stage + the attribution + the audit of the
`Container` branch. The other branches **only reserve the namespace**; no tables are created.

⚠ **`plate` is neither renamed nor repositioned**. It is one instance of `Container`, not the
whole of Material — stating that boundary now is what saves the first phase from having to be
torn down when samples, reagents and waste arrive later.

### 4.2 The four dimensions of a container + three boundaries

| Dimension | Where it lands (v3.2 finalised) | This round |
|---|---|---|
| **Identity** (barcode / plate format specification) | `plate.barcode` / `plate_type` / `rows` / `cols` | exists. **`plate_type` is not touched** (it is a specification, not a process role) |
| **Plate-level process stage** | `plate.process_state` | **new** (v1 called it `content_state`, v2 renames it) |
| **Consumable state** | `plate.seal_state` (the seal; **NULL = unknown**, `none` / `sealed`) | **new** (the independent consumable entity is deferred to S5) |
| **Position** (existing, not changed) | `position_state` (the single source of truth) + `plate.location_id` (a redundant projection) / `plate.position_status` (position trustworthiness) | **not changed**; the new code only reads, never double-writes (§17 Q2) |
| **Attribution** (three concepts) | current attribution = `position_state.occupied_by_run`; production source / processing history = `plate_material_event` (§4.5) | **no `plate.current_run_id` / `origin_*` added** (v3.2 adopts v2.2's objection: without a CREATE / handoff loop these columns only create a second source of truth for attribution) |

**Why the rename `content_state` → `process_state`**:
"content" suggests it describes what the plate holds, and that is exactly `plate_well`'s job;
"process stage" accurately says it describes **where this plate sits in the process**.

**Three boundaries that must be nailed down in this round (otherwise it fights `plate_well`)**:

| # | Boundary |
|---|---|
| ① | This round describes the **process stage of the whole container** (one stage per plate, homogeneous) |
| ② | It **does not represent well-level sample content** — that is `plate_well`'s source of truth; the two **must not contradict each other** |
| ③ | It **does not represent volume / concentration or other physical-chemical attributes** — that is `sample`'s source of truth |

**The definition of "how many empty plates are left"**: it is only judged empty when the whole
plate's well data has been registered completely **and** every well has `status='empty'`;
**no well rows = the well-level data is unknown, which is not the same as an empty plate**. No
separate plate-level `empty` state is introduced (otherwise there would be two sources of truth).

### 4.3 The plate-level process stage dictionary: tenant level + a template set

| Decision | Value | Reason |
|---|---|---|
| Ownership of the dictionary | **tenant level** (carries `tenant_id`) | the stage names are the customer's process vocabulary; the system has no right to prescribe them |
| Built in? | a **global template set** (`is_template=true`), copied into tenant copies when a new tenant is initialised | building 18 stages from scratch is a terrible experience; but the template is only a "suggestion" |
| Unique identity | `code` (machine identifier) + `display_name` (what the customer sees) | renaming must not break the references |
| Deletion | **deactivate only** (`enabled=false`), never a physical delete | deleting a stage that is referenced breaks the historical display |
| Reference constraint | a **composite foreign key** from `plate.process_state` to `(tenant_id, code)` (or an equivalent check) | a state that is not in the dictionary must not be possible |

⚠ **The state of tenant isolation**: `WorkflowWebService.TENANT = 1L` is hard-coded ⇒
**the fact that a new table carries `tenant_id` does not mean tenant isolation is in place**. The
dictionary and state reads and writes of the new code must go through one tenant context, and
**this has to be recorded as a known problem of its own (§13)**.

### 4.4 Consumables: a wider criterion

| | Criterion | Conclusion |
|---|---|---|
| v1 criterion | does it need its **position** tracked independently | too narrow |
| **v2 criterion** | does it need an **independent identity or lifecycle trace** (position, batch, stock, attachment history, reusability, quality traceability …) | the reviewers' wording is adopted |

⇒ **Seal**: peeled off and discarded, not tracked separately ⇒ one `plate.seal_state` column.
⇒ **Lid**: has its own barcode / stock / attachment history ⇒ needs an entity of its own.
⇒ **The existing `consumable` / `consumable_lot` are not refactored in this round** — they need
their own position and attachment-history model, and **freezing an incomplete structure
is the wrong move** ⇒ the whole refactor is deferred to S5 (§14).

### 4.5 Attribution: three concepts, never mixed

| Concept | Where it lands | Meaning | Overwritten? |
|---|---|---|---|
| **Current attribution** | `position_state.occupied_by_run` | who manages it now | **yes** |
| **Production source** | the first source record in `plate_material_event` | who produced it in the first place | **no** |
| **Processing history** | `plate_material_event` | one row per state result | append only |

**Why they must be split**: after a plate has been processed by A (amplification), B (purification)
and C (detection) in that order, the system must be able to answer "who produced it (A)", "who
processed it last (C)" and "who owns it now". With one set of `owner_*` columns every migration
overwrites the previous value ⇒ **the production relation is lost for good**. This round has no
CREATE / handoff loop, so **no `plate.origin_*` / `current_run_id` is added**; the first event only
expresses the earliest source among the existing records and does not fabricate a history from
before the system went live.

⇒ **`/api/runs/{id}/materials` queries by the `source_run_id` of the audit / effect records**,
**not by any "current attribution" field** (attribution already has a single source of truth in
`position_state`, §4.5); the wording changes to "**the material results / state changes this run
declared**" and no longer says "newly produced plates".

---

## 5. Execution facts and material effects (v1 rewritten)

### 5.1 Why a single transaction cannot be relied on

| Fact | Consequence |
|---|---|
| The device command **commits first** (`insertRunCommand`), the node completion **commits afterwards** (`updateNodeStateLive`) | they were never in one transaction to begin with |
| A device action is **physically irreversible** | if a failed migration rolls the node completion back ⇒ on continuation the device action **runs a second time** (a duplicated physical action is an accident) |
| `PositionLock`'s audit is **deliberately** written outside the position transaction, and a failure is only logged | copying that pattern produces exactly the seam this design is trying to avoid |

⇒ **"Must be in the same transaction as node completion" cannot be satisfied in the current
architecture**, and v1 wrote it as if it were an existing capability. That was wrong.
⇒ **The goal becomes**: **the execution fact must first become a recoverable record; the material
state transition must be idempotent, replayable and isolated on failure.** Whatever the database
can commit atomically should go in one transaction.

### 5.2 Three-layer division of labour (no new execution engine)

| Layer | Responsibility | Change this round |
|---|---|---|
| `GraphRunner` | graph progression, loops, branches, pause, resume | **never touches the database** (as today) |
| `RunStateMachine` / `StepExecutor` | executing nodes, retries, device commands | add a completion notification that **does not swallow exceptions** (§5.7) |
| `PositionLock` / `CommandLedger` | position locks, command records, physical execution trace | **untouched** (position ≠ content) |
| **MaterialEffectService** (new) | material inputs and outputs, state transitions, material audit, idempotence | **a new domain service** that consumes execution facts |

⚠ **Explicitly forbidden**: do not push the material state update into `PositionLock` — that
would couple position management and process-content management all over again.

### 5.2b The two-level failure semantics and the dispatch gate (v3.3 converged)

**The failure consequences of T1 and T2 are completely different**; v3.2 already fixed the
isolation channel on source evidence, and v3.3 keeps it:

| Stage | Failing means | Consequence | Landing channel |
|---|---|---|---|
| **T1** (the material effect intent did not land as the expected state) | the device command fact is usually already in `command_ledger` / `run_command`; what is missing is the material ledger and the basis for compensation | **isolate the run (quarantined) + an experiment-level `runtime_hold`**; not run failed; resuming and re-dispatching device commands is forbidden before a human reconciliation | reuse the precedent of `StartupRecovery.quarantineUncertainRuns` |
| **T2** (applying the state projection) | the physical action happened and is recorded, only the projection did not catch up | the node stays physically complete; the material sync records the concrete error; the dependent successors are not dispatched and the unrelated branches continue | `run_node.material_sync_status` + the dispatch gate; the effect goes to PENDING/FAILED/REJECTED/NEEDS_RECONCILIATION according to the reason |

**Why "just set run=failed" is not enough**: `markRunFailed` writes neither `pause_kind` nor
`recoverable`, and it cannot write anything at all when the database as a whole is unavailable;
an ordinary CONTINUE after a restart may delete the incomplete nodes and replay the already
completed physical action. A T1 failure must therefore isolate the run, and the recovery entry
point reconciles against `command_ledger` / `run_command` first (§5.3/§5.8).

**The mechanism for the downstream "not dispatched"**:
1. the node-level material status is `not_required / pending / applied / failed / rejected /
   needs_reconciliation / cancelled`; the effect `REJECTED` maps **one-to-one** onto the node
   `rejected` and is not merged with the retryable `failed`;
2. **the dispatch gate = a predicate injected into `GraphRunner` / `RunStateMachine.executeAll`**:
   before a node is activated and about to execute, check all its direct predecessors — only
   `not_required / applied` pass; `pending / failed / rejected / needs_reconciliation / cancelled`
   all block;
3. **the adjudication for a loop node (v3.3 closes §13.2-13)**: it does not read the round
   `run_node` that does not exist yet at T1, and it does not trust its default `not_required`.
   The gate determines the expected effect set of each round from the run-level declaration
   snapshot, then aggregates the actual effects by `source_loop_index=1..N + effect_index`:
   if any expected effect is missing, or any effect is not APPLIED, it blocks; `none/legacy` is
   judged by the execution resolution status and by the summary row's `not_required`.
   Multiple effects must all be APPLIED — the summary row or the last round cannot stand in;
4. when the gate trips ⇒ that node **is not started**, the run lands in `paused` +
   `pause_kind=material_sync`, and an experiment-level `runtime_hold` alarm is recorded; after the
   reconciliation is closed and a human has confirmed, the existing resume entry point wakes it up;
5. the gate is **not** placed in `ExperimentScheduler`, which only handles workflow-steps.
   `GraphRunner` still does not access the database directly: the predicate is injected by
   `MaterialEffectService` in the boot layer, the same dependency direction as the existing
   `withPauseChecker`.

⇒ **No new `node_status` enum value is introduced**: the physical completion, the material sync
and the dispatch of the successors are three different dimensions, and a fake device failure is
not fabricated.

### 5.2c The execution resolution status: the replay guard shared by every node (new in v3.3)

`physical_completed_at` can only prove "the completion has been confirmed"; on its own it cannot
override "it was dispatched but the result is unknown". A new `run_node.execution_resolution_status`
is added, kept apart from the existing `node_status` and `material_sync_status`:

| Status | Meaning | The meaning for an ordinary continuation |
|---|---|---|
| `unresolved` | no final execution conclusion has been reached; it covers not-started / running and the unknown legacy data | it may only be treated as an ordinary not-started node **only when there is no dispatch evidence** |
| `completed` | the target physical action is confirmed complete | only the execution / material records are filled in; re-executing the device action is forbidden |
| `failed_confirmed` | the target physical action is confirmed not to have happened | only now may the existing safe retry policy be entered |
| `reconciliation_required` | there is a dispatch or execution trace but the final physical result is unknown | **deleting and rebuilding, an ordinary resume or an automatic retry are forbidden**; first a device query / a human reconciliation |

- UNKNOWN is no longer a separate value: UNKNOWN is an evidence situation and falls uniformly
  into `reconciliation_required`; the existing `node_status` keeps expressing the process
  lifecycle (not started, running, completed, …);
- on an explicit success `completed + physical_completed_at + physical_evidence_level +
  physical_evidence` are written together; an old `status=completed` can only be covered by the
  `legacy` evidence and must not fabricate a precise occurrence time;
- on a timeout, a broken connection or a `command_ledger=UNKNOWN/DISPATCH_STARTED`,
  `reconciliation_required` is written, **whatever the declaration says — `none`,
  `legacy_unspecified` or `transition`**; the `material_sync_status` of a node without effects may
  still stay `not_required`;
- the continuation guard must jointly check the execution resolution status, the physical
  completion time, the original `run_node` status, `command_ledger/run_command` and the
  Effect/Event references. Any node that is confirmed complete, has an unknown result or is
  already referenced by a material record must not be deleted and rebuilt;
- `failed_confirmed` must be supported by explicit evidence — a timeout must not be mapped
  directly onto a failure. After a human confirmation only the evidence and the original logical
  effect are filled in; only a human confirmation that it did not happen allows the existing retry
  policy.

### 5.3 T1: execution fact confirmation (the command fact = `command_ledger` + `run_command`)

**First, make clear where "the execution fact" actually lands (the fifth review's P0 correction,
kept by v3.3)**:

| Fact | Carrier | Timing | Explanation |
|---|---|---|---|
| command **dispatched, result unknown** | **`command_ledger`** | **before** dispatch | `command_id varchar(160)`, `idempotency_key`, `state` (`DISPATCH_STARTED / ACKED / FAILED / UNKNOWN`), `resolved_at/by`; a Java `CommandLedger` implementation already exists. ⚠ the sixth round's N15: the production instance has **21 columns**, and there are also `abort_state varchar(24)` / `abort_detail varchar(200)` / `abort_at timestamptz` (`99s_command_abort.sql:26-28`) — that is a **partial column list**, not the complete set |
| command **response result** | **`run_command`** | **after** the response | `device_raw_response` / `status` / `attempt` / `sent_at` / `finished_at`; **it has no `command_id` column**; `ack_at` has the column but no writer and must not be treated as evidence |
| the node execution conclusion | `run_node.execution_resolution_status` + the physical evidence columns | when the execution conclusion is reached | the judgement of `completed / failed_confirmed / reconciliation_required` is in §5.2c; only a success writes `physical_completed_at` |
| material effect intent | `plate_material_effect` (normally `PENDING`) | after the response, before the node is marked completed | the material ledger of T1; a `plate_material_effect_action(DECLARE)` is appended at the same time |

⇒ **T1 = the node execution conclusion + the material effect intent being persisted**, not
creating the command fact from scratch. On a T1 failure the run is isolated (§5.2b) and the
recovery reconciles first: only a successful response that satisfies the snapshot's
`acceptedEvidenceLevels` allows the effect to be rebuilt from the declaration snapshot;
`UNKNOWN / DISPATCH_STARTED` or insufficient evidence always goes to a human check.
**`ACKED` is only a `device_ack`; it is not automatically an independent physical confirmation,
and no path may directly re-run the device action.**

**The declaration snapshot (no new table)**:
- **at run start, snapshot every node's `materialEffect` into
  `run_node.input.material_effect_snapshot`** (including `mode / inputs / outputs / operations /
  acceptedEvidenceLevels` and a hash), and **actually write `run.workflow_ver`**;
- the effect record keeps another copy of the `declaration` snapshot; the continuation /
  compensation **read only the snapshot**, never the newest design;
- before each subprocess round executes, freeze both the original expressions and resolved values
  of `iterationBindings` into `target_snapshot` (call path, round, source/destination positions,
  `scanRef`, actual device parameters and `command_id/stepSeq`); the device command and the
  material effect must consume the same binding result;
- an identity fact produced by late scanning lives in the run-scoped context, while the raw scan
  response remains in the command facts; downstream effect snapshots record the `scanKey`, scan
  evidence and controlled identity-binding record ID, not merely the finally resolved barcode;
- this round guarantees the material declaration and the runtime binding snapshots above; it does
  not claim to also solve the versioning of the whole BPMN graph.

**The evidence levels**: the declaration snapshot answers "what we intend to do" and is **not**
physical evidence.

| Level | Meaning | Boundary |
|---|---|---|
| `device_ack` | the device or driver reports success | only proves that the execution chain got a success response; it does not pretend to be an independent physical confirmation |
| `physical_confirmed` | a final device-state query, a sensor or other trustworthy physical feedback confirms it | real-machine evidence |
| `manual_confirmed` | an authorised person verified it on site | **must** record the operator, the time and the reason |
| `legacy` | only for compatibility with the old `status=completed` data | **it does not represent a new evidence capability and cannot fabricate a precise completion time** |

An ordinary method accepts `device_ack` by default; a high-risk method may accept only
`physical_confirmed / manual_confirmed`. The evidence policy is frozen together with the
declaration snapshot; the UI, the events and the acceptance reports must keep the **actual level**
and must not uniformly say "physically verified".

**What the effect record must hold**:

| Field | Why it must be there |
|---|---|
| `declaration` + `payload_hash` | the continuation interprets the old action by the declaration of that time; the same key with the same payload may be skipped, the same key with a different payload must raise an error |
| **the original target and the resolution evidence** | keep the original `target`, `plateId`, `barcode`, the target position, `occupied_by_run`, `command_id/stepSeq`, the resolution time and the basis; the original declaration and the original evidence must never be overwritten |
| **the actually resolved `plate_id`** | saved whenever the identity can be determined; the compensation replays by it and does not re-run `target.by=position`; NULL is allowed when it cannot be determined, but it must be filled in before `APPLIED` |
| `expected_state_version` + the expected state snapshot | T1 records the baseline before applying; T2 does a CAS so that an old effect cannot overwrite a manual correction |
| the structured idempotence fields | `source_run_id / source_node_key / source_loop_index / source_attempt / effect_index`; `attempt` is only recorded and does not go into the idempotence key |
| **every action appends a `plate_material_effect_action`** | the declaration, the application, the reconciliation, the supplementary target evidence, the retry and the cancellation all have an immutable audit |

When the target cannot be resolved at first but there are already signs of a physical execution,
the effect goes to `NEEDS_RECONCILIATION` with an empty `plate_id` and the complete resolution
evidence; an authorised person may add `confirmedTarget + resolutionEvidence` to recover, but
that may only append evidence and an action — it must not rewrite the original declaration or the
original resolution record.

### 5.4 T2: the material state projection and the replayable events

**One effect, one transaction**: `lock the effect → lock the plate → verify the expected
version / state → apply the transition → write exactly one automatic event → append the effect
action → write the node-level `material_sync_status` back (per the §5.9 mapping table; the same
transaction, landed together with the effect state) → mark APPLIED`.
⚠ **The writer of the node-level column and the timing** (flagged as previously missing by the
sixth-round reviewer C, the sixth round's P1-03):
**the only writer is the T2 transaction itself** (`MaterialEffectService`), **in the same
transaction as the effect row, the event and the action**;
the value corresponds state by state to the "effect state → node material status" mapping table of
§5.9 (`APPLIED→applied`, `PENDING→pending`, `FAILED→failed`, `REJECTED→rejected`,
`NEEDS_RECONCILIATION→needs_reconciliation`, `CANCELLED→cancelled`).
⇒ **the column is not written when T1 fails** (the effect intent has not even landed ⇒ there is
nothing from which a node-level value could be derived); **a successful T1 immediately writes
`pending`**.
⇒ **both the gate and the recovery entry read only this column** and no longer query the effect
table back (the query-back only happens on the manual reconciliation path of §5.8).
⚠ **Nodes with several effects**: one node may have several effects within one round ⇒ the
node-level value is a **single value** ⇒ it is folded by "**`applied` only when all of them are
APPLIED**, otherwise the most severe one among the non-APPLIED ones (`rejected` >
`needs_reconciliation` > `failed` > `pending` > `cancelled`)";
this folding rule is consistent with T23 of §11.2, "a partial success must not mark the whole
APPLIED".
`APPLIED` must correspond to exactly one automatic `plate_material_event` produced by that
effect; a missing event, a duplicated event, or an automatic event for an effect that was not
applied are all treated as a broken transaction invariant and block delivery via an integration
test.

**The event is not a diff patch but a complete state snapshot**:
- `from_process_state / from_seal_state` and `to_process_state / to_seal_state` always keep the
  complete before/after of both dimensions;
- `changed_fields` lists explicitly the dimensions that actually changed this time; `from = to`
  may express a real "state confirmation", in which case `changed_fields=[]` and the event
  version may still be advanced;
- the event also keeps `from_state_version / to_state_version`. NULL means that dimension is
  explicitly unknown; a dimension that was not modified is expressed by `changed_fields` — it is
  no longer guessed from "are from and to the same";
- a replay must start from a known baseline event and its `to_state_version`, and apply the
  complete snapshots in the order `(plate_id, to_state_version, id)`. A missing baseline or a gap
  in the versions ⇒ stop the replay and escalate to a human, **it is forbidden to treat NULL as
  "unchanged"**;
- an automatic event must have an associated `effect_id`, the source node and the evidence; a
  manual event must have the operator, the reason and the evidence. A reconciliation / recovery
  action itself does not produce a plate event — only a confirmed real plate state does.

| Failure case | Behaviour |
|---|---|
| the declaration contradicts itself or a business rule such as the dictionary / the contract does not hold | the effect goes `REJECTED` + an error code, the node goes `rejected`, an alarm escalates to manual; no Event, no Plate change, no automatic retry |
| the plate is not found, the attribution does not match, or the device / declaration / position identities conflict | `NEEDS_RECONCILIATION`; no Event, no Plate change, no guessing — wait for supplementary evidence |
| a database failure, a recoverable concurrency conflict | `FAILED` + an error code; a bounded backoff and a maximum count give a controlled retry per §5.8 |
| a manual inventory and an automatic migration happen concurrently | an explicit rebase / cancel per §5.6; a silent "last writer wins" is forbidden |
| the run has terminated / been cancelled | the unapplied effects go `CANCELLED` (an action is appended) and are no longer replayed |

### 5.5 The idempotence key (v3.2: a structured unique key, no string execution_id)

```
idempotence key = UNIQUE (tenant_id, source_run_id, source_node_key, source_loop_index, effect_index)
```

- `execution_id` is only the **logical name** of this group of fields and is **not persisted as a
  string column** (v3.2 adopts v2.2's objection: a concatenated key has to deal with separators /
  length / case, and `node_key` may itself contain the separator; a structured unique key can also
  be used directly by foreign keys and queries);
- the command ledger's `command_ledger.idempotency_key` is a **dispatch-level** idempotence key
  encoded from `(run, node, loop, step_seq)`; it and the effect idempotence key are **two layers**
  (one node may have several steps and several effects) aligned through `run_id / node_key / loop`,
  and they do not replace each other.

**Why the anchor changed** (the three-way adjudication D1): v2 used `run+node+loop+plate`, which
has two holes —
① a node that **produces several effects** (aliquoting, mixing) has no distinguishing slot;
② "the plate" is not the natural unit of an effect (one effect may involve several materials).

⇒ `effect_index` is the **effect ordinal within one execution** (a node declaring three effects
has 0/1/2), and how many plates an effect touches is written inside its own declaration ⇒ **the
idempotence key is decoupled from the plate**.

| Design point | Reason |
|---|---|
| **The loop's process unit is a subprocess, not an isolated node spinning on its own** | the common case is a subprocess such as "pick plate → process → place plate", changing the plate, source position or destination position each round; `loop_index` prevents legitimate effects from being de-duplicated and does not prescribe single-node loop modelling |
| **`source_loop_index` goes into the idempotence key** | effects from different rounds of the same execution node are all legitimate and must not be eaten by the key; ordinary acyclic execution is fixed at 0 |
| **Subprocess call paths must be unique** | `source_node_key` holds the **execution call-path key** (including the subprocess call key and ancestor rounds where needed), not the template-local `node_key`; the full call stack and round vector also go into `target_snapshot.loopPath`, preventing collisions when the same subprocess is called in several places or rounds |
| **Bind each round before dispatch** | resolve `iterationBindings` explicitly for every round; source/destination positions, plate references and device parameters come from the same result, and any missing or conflicting value blocks before physical dispatch |
| **`attempt` does not go into the idempotence key** | a device / node **retry** is the Nth attempt of the same logical execution ⇒ it must not produce a new material transition (`attempt` only goes into the action audit) |
| **same key + same payload may be skipped; same key + different payload must raise an error** | silently swallowing "the design changed" is the most dangerous failure mode |
| **`payload_hash` must be stored** | the hash covers the declaration and the resolved bindings of this round, providing the basis for deciding "same key, same payload?" |

⚠ **`source_loop_index` takes the round from the execution context, not `run_node.loop_index`** —
this was checked back against the source on 2026-11-12 (`docs/评审记录/20261112-04-openai.md` O-4),
and v3.2 tightened it again per the fifth review:

| Fact | Evidence |
|---|---|
| `visit` starts at **1** | `GraphRunner.java:536` `visits.merge(key, 1, Integer::sum)` |
| the loop round is persisted from exactly `visit` | `GraphRunner.java:657` `roundRecorder.record(key, visit, …)` |
| persisted `loop_index = round` ⇒ **1..N**; **the summary row is 0** | `WorkflowService.java:1910-1929` |
| ⇒ `visit` matches the `loop_index` of the **round rows** | the two points above taken together |
| ⚠ **but `roundRecorder` is only called when `graphHasCycle && visit >= 1`** | `GraphRunner.java:654` |
| ⇒ **a node in an acyclic graph only has the summary row (`loop_index=0`) and never persists the 1..N round rows** | same as above |
| ⚠ **but the round row is written only AFTER the node has executed** | `GraphRunner.java:649` `stateMachine.executeNode(...)` → `:650` writes the node state back → `:657` `roundRecorder.record(key, visit, …)` |
| ⇒ **T1 happens inside `executeNode` ⇒ this round's row is not in the database yet** | same as above (the completion handler is hooked per §5.7) |

⇒ **the round of "this round" cannot be read back from the database — this is a **TIMING**
problem, not a "semantic inconsistency"** (v3.2 addition · the 05 review's O-11 corrected;
**v3.3 tightened again · the sixth-round reviewer C pointed out that two phrasings were imprecise,
and they have been rewritten**):
the row of **this round** has not landed yet ⇒ if T1 took the round from `run_node.loop_index`, it
would read the value of the **previous round**
(in round 2 the database already holds the row of round 1, and in round 3 it already holds the rows
of rounds 1–2 — because the round row is written only **after** the previous round's `executeNode`
**returns**, `GraphRunner.java:649` → `:657`)
⇒ **this round would collide with the previous round on the same idempotence key ⇒ the effect
would be silently swallowed**
⇒ the round of the idempotence key **can only come from the execution context** (graph path = the
in-memory `visit`; sequential / acyclic = 0, propagated per §5.7).

⚠ **The boundary between the two cases (v3.3 addition, to avoid applying the timing argument in
the wrong place)**:
| Case | What the database holds | Whether the timing argument applies |
|---|---|---|
| **a cyclic graph, round N (N≥2)** | the summary row + the rows of rounds 1…N-1 (**not this round**) | **it applies** ⇒ what is read is the previous round ⇒ a key collision |
| **an acyclic graph / sequential execution (only one round runs)** | only the summary row `loop_index=0` | **it does not apply**: at this point `loop_index=0` **is exactly** the node's one and only correct round, so reading it back would never collide on the key. ⇒ But the conclusion does not change: take the value uniformly from the execution context, the same code as the cyclic path, and there is no need to write two sets of logic for the two cases |

⚠ **Correcting an erroneous argument in the earlier draft** (O-11): the draft said "reading
`run_node.loop_index` back from the database gives values with inconsistent semantics" and offered
"an acyclic graph only has the summary row" as an example — **that example does not hold**: in an
acyclic graph every node runs exactly once, so `loop_index=0` is its **one and only, unambiguous**
value. The inconsistency exists only in **cyclic graphs** (the same node_key has 1 summary row + N
round rows). And **what actually makes "read it from the database" impossible is the timing above,
not that inconsistency** — an implementer who reads it as "inconsistency" would likely change it to
"read it once the round row exists", and at the T1 point that **never happens**.

⇒ the benefit of keeping `attempt` out of the key is now also clear: **a device retry does not
change `visit`** ⇒ the same logical execution retried N times keeps the same idempotence key ⇒
the effect is applied only once ✓

⚠ **The round row is not an immutable fact (the sixth round's N01 · P1)**: the above only said
"the SQL does not filter `loop_index` ⇒ it will hit the round rows", but **the real consequence is
heavier than "hitting"** — it is **a later write overwriting an earlier one**:

| Fact | Evidence |
|---|---|
| the WHERE of `updateNodeStateLive` is `… node_key AND level = 0`, with **no `loop_index`** | `RunMapper.xml:555-563` |
| `updateNodeResult` has the same WHERE, **without `loop_index`** | `RunMapper.xml:575-582` |
| the `:648`/`:650` of the 2nd round also rewrite **the row of the 1st round** (`loop_index=1`) into `running`/`manual`/`completed` | `GraphRunner.java:648-650` |
| the `updateNodeResult` at the end of the whole order then flushes the **last round's** `status`/`attempt`/`error_code`/`input` onto **all the `level=0` rows** of that `node_key` | `WorkflowService.java:1100` → `:1846-1850` |

⇒ **the content of a round row is overwritten by the later rounds and by the end-of-order
flush ⇒ it is not "the fact of each round", only "the shape of the last write".**
The DDL comment at `20_kernel.sql:326` itself states that the purpose of a round row is to
"review 'why did the 3rd round fail'" — under the semantics above, **that purpose no longer
holds**.

⇒ **The direct consequence**: the option (b) of §13.2-13, "write `material_sync_status` back into
the round row after the node executes", is **unsafe under the existing SQL semantics** (the
write-back of the 2nd round overwrites the sync status of the 1st round) ⇒ **(b) is rejected**,
and O-16 can only take (a) (aggregate from `plate_material_effect`, §5.2b).
⇒ **A new known gap is added at the same time**: the historical round-row data is untrustworthy
(the production database currently has **0** rows with `loop_index>0`, so there is no existing
data harmed, but **the existing `loop_index>0` semantics must no longer be treated as a per-round
fact**).
⇒ The database **already contains** a correct style that can be copied: `insertBuiltinCommand` /
`updateBuiltinCommandNote` explicitly carry `AND loop_index = 0` (`RunMapper.xml:658` / `:680`)
⇒ the new writes of S1/S2 **must** explicitly carry `AND loop_index = 0` or an exact round
condition.

⚠ **Across a continuation (v3.2 addition · the concurrent 05 review's O-2)**: on a continuation
`GraphRunner`'s in-memory `visits` starts from zero; if a node has already run N-1 rounds, its Nth
round gets `visit=1` again ⇒ **it collides with an already-applied effect on the idempotence key**.
⇒ S2 implementation requirement: before a continuation starts, **restore each node's `visit`
starting point from its `run_node` round rows (or the effect records)** (`MAX(source_loop_index) +
1`) and initialise `visits` from that point; **do not restart counting from 1**.

⚠ **v3.2 addition (the fifth review's S12; the sixth round's N06 tightened the wording and closed
a gap)**: the following **5** per-node update / query SQL statements (`updateNodeStateLive` /
`updateNodeResult` / `markBypassed` / `selectTopNodeKeyIds` / the resume-path
`deleteIncompleteTopNodes`) **only filter `level=0` and do not filter `loop_index`** ⇒ they hit
the round rows.
⚠ **It is not "the whole database does this"**: `insertBuiltinCommand` / `updateBuiltinCommandNote`
(`RunMapper.xml:658` / `:680`) **explicitly carry `AND loop_index = 0`** ⇒ the database
**already has** a correct style that can be copied, and S1/S2 can simply copy it.
⚠ **The original list missed one** (the sixth round's N06): `selectCompletedNodeKeys`
(`RunMapper.xml:466-472`, `level=0` + `status='completed'`, **without `loop_index`**) is **the
source of the skipKeys on the continuation chain** (`WorkflowService.java:710`) and belongs to the
same chain as `deleteIncompleteTopNodes` ⇒ it is listed as well.
⇒ The new writes of S1/S2 must explicitly carry `AND loop_index = 0` (or an exact round
condition) and **must not copy the existing style**; combined with the above "a round row is not an
immutable fact" ⇒ more strictly, **any write keyed on `node_key` may hit a round row**.

⚠ **v1's `(owner_run_id, owner_node_key, plate_id)` makes the third loop iteration hit the
unique index** — that is a hard defect (fixed in v2).

### 5.6 Concurrency: per-plate serialisation and conflict arbitration

A unique index only prevents "the same idempotence key twice"; it does **not** prevent two
different nodes (or a human) from changing the same plate at the same time. The effect row lock
does not replace the plate row lock either.

| Scenario | Rule |
|---|---|
| two automatic migrations modify the same plate | inside T2 take `SELECT ... FOR UPDATE` on the plate + a `state_version` CAS; a version mismatch ⇒ `FAILED(MAT_STATE_CONFLICT)`, no Event, no overwrite |
| the manual state-change API | must carry `expectedVersion`; a version mismatch ⇒ HTTP 409 + the current value / version; a manual event likewise uses the complete before/after and `changed_fields` |
| a manual inventory vs an automatic migration at the same time | the automatic effect enters the conflict handling and does not change the baseline by itself; only an authorised `REBASE` may update the expected version and then retry, and only when the declaration hash, the target plate and the effect semantics have all not changed — otherwise a `CANCEL` and a new effect are required |
| audit consistency | `from_*` must equal the complete value this transaction read; the automatic-event unique key and the same-plate `to_state_version` unique key together prevent duplicate / interleaved commits |
| conflict visibility | a conflict always appends a `plate_material_effect_action(REBASE/CANCEL)` + an alarm; a silent "last writer wins" is forbidden |

⚠ The existing `PositionLock` solves **position mutual exclusion**; that is **not** the same as
concurrent updates of the material state being solved. A declaration-hash or target-identity
conflict **forbids a rebase** — a human reconciliation is mandatory.

### 5.7 The completion handler: the single injection point and loop-round propagation

Material effect processing has only one injection point: the shared `RunStateMachine` instance,
not one hook in GraphRunner and one in WorkflowService.
`NodeCompletionHandler` is a port defined in kernel/domain; `MaterialEffectService` implements it
in boot/infrastructure and injects it.
`GraphRunner` only passes the execution context; it does not depend on the database, on a Mapper
or on the material tables.

| Return | Meaning | What the caller does |
|---|---|
| `CONTINUE` | the current node has no effect, or the effect is already `APPLIED` | the node may complete; the successors enter the dispatch-gate check |
| `HOLD` | the effect is `PENDING / FAILED / REJECTED / NEEDS_RECONCILIATION / CANCELLED` | the node stays physically complete, the successors are blocked by the gate, the run enters `paused(material_sync)`; REJECTED is not merged with FAILED |
| throws a persistence exception | T1: the execution conclusion / effect intent cannot be persisted | abort the current run immediately and isolate it; no continuation and no replay of the device action |

| Decision point | Conclusion |
|---|---|
| injection point | `RunStateMachine.executeNode`: called after the device execution succeeds and **before** `np.setStatus(COMPLETED)` / `emit(NODE_COMPLETED)`, constructed once by injection |
| completion context | explicitly carries `runId / runNodeId / nodeKey / loopIndex / effectIndex / commandId / stepSeq / the execution evidence`; looking up "the latest round" is not allowed |
| where the round comes from | the graph path passes the in-memory `visit` (1..N); sequential execution and acyclic graphs pass 0; the signature extension points are still `GraphRunner.java:649`, `WorkflowService.java:1057/1065`, `RealWorkflowRunner.java:183`, `RunStateMachine.java:76` (**the place inside the sequential path `executeAll` that calls `executeNode`**) plus the tests / demos. ⚠ **the sixth round's N13 completes the list**: under `src/main/java` there are also **5 call sites in 4 demos** that must be changed in the same batch, otherwise **the main source does not compile** (not just the tests): `driver/EndToEndDemo.java:156`, `driver/tcp/SidecarDemo.java:131`, `persistence/PersistenceDemo.java:86`, `engine/EngineDemo.java:61` and `:88` |
| coverage | only **device TASK nodes**; a BUILTIN node declaring `materialEffect` is a preflight ERROR; flow-run and the shadow runs are known gaps |
| failure semantics | the T2-class failures stay in the effect status and the action; only a T1 persistence failure is thrown up and `WorkflowService` isolates the run |
| `effect_index` allocation | assigned `0..n-1` in the `outputs` array order within one notification; a replay / retry reuses the same ordinal |
| difference from ExecutionListener | a listener is a notification mechanism outside the failable side-effect outlet, and its exceptions must not affect the execution; the completion handler must be able to reflect a failure ⇒ the two are not merged |
| event persistence | `ExecutionListener` events are buffered and may be lost on the failure path ⇒ they do not carry an intent that must be compensated; the completion handler writes the effect / action, and T2 writes the material Event |
| the writer of the node-level sync status | **`MaterialEffectService` inside the T2 transaction** (the only writer), in the **same transaction** as the effect row / event / action; the value corresponds state by state to the §5.9 mapping table; several effects are folded by "all APPLIED ⇒ `applied`, otherwise the most severe one"; the column is not written when T1 fails |

⇒ no generic Execution Fact platform is built, and this design is not expanded into a global
generic material state machine; only the above port, implementation and continuation guard are
added.

### 5.8 The Worker, the recovery state machine and the human loop

**The effect state machine (6 states)**: a reconciliation does not write APPLIED directly; it
restores the original T2 projection path.

| Current | Trigger | Next | Who | What is recorded |
|---|---|---|---|---|
| `PENDING` | T2 succeeds | `APPLIED` | AUTO | action `APPLY` + exactly one automatic Event |
| `PENDING` | a deterministic declaration / configuration / business-rule error | `REJECTED` | AUTO | action `APPLY` + error_code; no Event |
| `PENDING` | the physical identity or the result evidence conflicts | `NEEDS_RECONCILIATION` | AUTO | action `APPLY` + evidence; no Event |
| `PENDING` | a recoverable database / concurrency failure, or the Worker exceeds the retry limit | `FAILED` | AUTO | action `APPLY/RETRY` + error_code |
| `PENDING / FAILED / NEEDS_RECONCILIATION` | the run terminates or an authorised cancellation | `CANCELLED` | AUTO / MANUAL | action `CANCEL` + the reason |
| `FAILED` | a manual / due retry of a recoverable error | `PENDING` | **needs the recovery permission** | action `RETRY` + the reason |
| `NEEDS_RECONCILIATION` | `completed`: the physical work is done, the target and the evidence suffice | `PENDING` | **needs the reconciliation permission** | action `RECONCILE` + `resolutionEvidence`; then T2 runs |
| `NEEDS_RECONCILIATION` | `not_completed`: it is confirmed that it did not execute / did not produce the effect | `CANCELLED` | same as above | action `RECONCILE` + the evidence; no Event |
| `NEEDS_RECONCILIATION` | `still_unknown`: the evidence is still insufficient | stays in the original state | same as above | an action and the evidence are appended; the state advance is not faked |
| `REJECTED / APPLIED / CANCELLED` | terminal | — | — | no automatic retry; correcting the real plate state takes a separate manual Event or a new effect |

**The Worker's claiming and retrying**:
- it only scans `PENDING AND (next_retry_at IS NULL OR next_retry_at <= now())`; a FAILED effect
  is not unconditionally picked up again by the automatic job — only a recoverable error that has
  not exceeded the limit returns to PENDING through an explicit scheduled / authorised retry;
- each effect gets one short transaction, claimed with `SELECT ... FOR UPDATE SKIP LOCKED`; T2 is
  completed inside the transaction and **no persistent `PROCESSING` state is added**. If the Worker
  crashes the transaction rolls back, the row lock is released naturally and the other Workers can
  continue;
- `apply_attempt_count` increases monotonically and records `last_error_code / last_error_message /
  last_attempt_at`; a recoverable error uses a bounded exponential backoff with `next_retry_at`, and
  on reaching the configured cap it becomes `FAILED` and an alarm escalates it to manual;
- a repeated callback with the same key and the same payload may be skipped; the same key with a
  different payload, a changed declaration hash or a changed target identity must raise an error,
  and it may never be masked by a retry / rebase;
- `REBASE` only updates the version baseline, it must be done by an authorised operation and it
  leaves an action; `CANCEL` only cancels an unapplied effect; neither of them directly produces a
  plate Event.

**The action audit table `plate_material_effect_action` is the effect disposition history**: the
declaration, the application, the reconciliation, the supplementary evidence, the retry, the
rebase and the cancellation all append one row;
`plate_material_event` only records the real plate state snapshots and never fakes a transition
with a RECONCILIATION event.

| Crash / failure scenario | Behaviour |
|---|---|
| **crash before T1** (the command was dispatched / responded, but the execution conclusion or the effect intent was not written) | first reconcile against the command ledger and the response; only if the evidence satisfies the snapshot is `completed` + a PENDING effect filled in, and UNKNOWN/DISPATCH_STARTED gives `reconciliation_required` + an isolated run; "just re-run" is forbidden |
| crash after T1 / before T2 | the PENDING effect is replayed by the Worker as T2 on the already resolved `plate_id`, with no device action and no position addressing re-run |
| T2 rolled back | the Plate / Event / action / APPLIED all roll back in the same transaction; the effect stays PENDING and is replayed after the backoff |
| repeated compensation / two Workers | the effect row lock, the structured idempotence key and the Effect→Event unique constraint together guarantee that it is applied only once |
| the flow has already continued / ended when the compensation runs | the effect can be reconciled independently; if the run has terminated the unapplied effects are cancelled, and the compensation may not re-drive the physical execution |
| the run is isolated | `quarantined` + an experiment-level `runtime_hold`; resume is forbidden until the execution confirmation, the command ledger and the Effect/Event references are all closed |
| a database exception | it must not carry on silently; record the error and isolate / escalate to manual |

**The uniform requirements of a human recovery**: `material:reconcile / material:retry /
material:cancel / material:rebase` are separated by permission; `note` is mandatory;
every operation appends an action and an `actor_id`, and the supplementary target evidence is
saved as `confirmedTarget + resolutionEvidence`; the recovery only touches the records and the
projection and **never re-executes the device action**.

### 5.9 The relation between the execution confirmation, the material effect and the run state

`node_status`, `execution_resolution_status` and `material_sync_status` are three different
dimensions and cannot replace each other.

| Execution confirmation | Material effect | The existing node status | The node material status | Result |
|---|---|---|---|---|
| `completed` | all achieved | `completed` | `applied` | carry on normally |
| `completed` | awaiting projection / a recoverable failure | `completed` | `pending / failed` | the downstream is blocked, the run is `paused(material_sync)` |
| `completed` | the physical fact awaits reconciliation | `completed` | `needs_reconciliation` | the downstream is blocked, a human reconciliation is needed |
| `completed` | a deterministic declaration / rule error | `completed` | `rejected` | the downstream is blocked, no automatic retry |
| `completed` | no longer applied | `completed` | `cancelled` | the downstream is blocked, only an explicit disposition is possible |
| `failed_confirmed` | no effect produced | `failed / manual` | `not_required` or `cancelled` | only when the evidence of "it did not execute" is sufficient does the existing safe retry apply |
| `reconciliation_required` | whatever it is — `none / legacy_unspecified / transition` | neither a fake failed nor a fake completed | there may be an effect, and it may also be `not_required` | an ordinary resume, a delete-and-rebuild and an automatic retry are forbidden — reconcile first |
| a success but a T1 persistence failure | the intent is unknown | not marked completed | no fake state | isolate the run, do not write a fake completion |

⇒ an explicit `none` only means "no material effect"; it does not mean "the physical result is
certainly known", and `legacy_unspecified` is bound by the execution-confirmation guard in the
same way.
⇒ no new `node_status` enum value is introduced; `REJECTED → rejected` is a one-to-one mapping,
the other effect states map as in the table above and are not merged into FAILED.
⇒ The run is decided by the flow dependency, the execution confirmation and the material
consistency together; a material problem does not always become a run failure, but the dependent
successors of an anomalous material may not continue.

---

## 6. The material effect contract of a node

### 6.1 A dedicated column, not inside `params`

**Why it cannot go into `params`**: the execution side's **device parameter channel** only reads
`workflow_node.params->'method_params'` (the other fields of `params` each have their own
channel, see the corrected wording in §3),
and parses it with a **flat JSON parser** before passing it to the driver ⇒ a nested structure
placed there would be misparsed and **could even be passed on to the device**. And `produces` is
**the control system's own business contract**, not a device execution parameter.

⇒ **A dedicated column** `workflow_node.material_effect jsonb`:

```
workflow_node
├── params → method_params   ← the parameters sent to the device (untouched)
└── material_effect          ← the control system's material contract (new)
    ├── inputs
    ├── outputs
    └── operations
```

**Benefits**: it does not get mixed into the device parameters; the designer can edit it on its
own; it can be validated with a JSON Schema independently; "which nodes declare a material
effect" becomes searchable; and later upgrades of the contract do not touch the device driver.

### 6.2 The shape of the contract

```json
{
  "materialEffect": {
    "mode": "transition",
    "acceptedEvidenceLevels": ["device_ack", "physical_confirmed", "manual_confirmed"],
    "inputs":  [ { "by": "plateId", "plateId": 1001 } ],
    "outputs": [ { "by": "plateId", "plateId": 1001,
                   "processState": "amplified_product",
                   "sealState": "sealed" } ],
    "operations": []
  }
}
```

| Field | Meaning |
|---|---|
| `mode` | `none` (explicitly no material side effect) / `transition` (declares a state transition) / `legacy_unspecified` (old flow, runs as `none`, preflight WARN) |
| `inputs` / `outputs` | the material identity plus the result. **This round only supports a one-to-one state change**; `outputs` is an array (reserved for a future 1→N) |
| `operations` | reserved: aliquoting (1→N), mixing (N→1), plate transfer (1→1 new plate), sampling for detection (1→1 + data) |

⚠ **Aliquoting and mixing are not implemented this round**, but the **structure does not seal off
the extension space** (the review's O-3 requirement); `operations` is only a placeholder this
round and **preflight rejects an unknown operation** (v3.2 narrows the empty promise).

⚠ **"only one-to-one is supported this round" is a restriction of the _validation rules_, not of
the table structure** (the review's O-5 requirement): `inputs` / `outputs` are **arrays** and
`operations` is **an array** ⇒ the structure natively supports multiple inputs and outputs; this
round merely **rejects, in the validation layer**, a declaration carrying more than one input or
output. ⇒ opening up SPLIT / MERGE later **does not require a table change**; it only requires
relaxing the validation and implementing the operation semantics.

### 6.2a Subprocess loops: parameter binding per round (new in v3.3)

Plate-output loops and plate-by-plate processing should be modelled as **subprocesses with explicit
inputs and outputs**, not as an isolated node looping without context. A subprocess may contain
multiple actions such as pick, scan, process and place; every round changes parameters through
explicit `iterationBindings`:

```json
{
  "subprocess": "plate_batch_process",
  "iterationBindings": {
    "sourcePosition": "${loop.item.source}",
    "destinationPosition": "${loop.item.destination}",
    "plateTarget": { "by": "scanRef", "scanKey": "currentPlate" }
  }
}
```

| Rule | Required behaviour |
|---|---|
| Bind each round before physical dispatch | if any required source position, destination position, plate reference or other value is missing or conflicting, do not dispatch the device command; never act first and patch parameters afterwards |
| Device and material share one binding result | `method_params` and `material_effect` must not independently resolve different positions or identities; one resolver output feeds the command, execution evidence and effect snapshot |
| Binding results are immutable | freeze the original expressions, resolved values, call path, round vector and `command_id/stepSeq` into `target_snapshot`; later rounds must not overwrite earlier rounds |
| Retries reuse the original round's bindings | device retries and compensation must not re-read shared variables changed by later rounds; the same key with different binding values is a conflict |
| Call paths are distinguishable | when the same subprocess template is called by different nodes or parent rounds, distinguish it by the execution call-path key; the complete `loopPath` goes into the evidence snapshot |

⇒ This document **does not assume** that the current graph engine already provides subprocess
definitions, per-round parameter resolution and call-path propagation. S2 must treat these as
standalone integration points and pass §11.2 T37/T38 first. These are the material contract's
requirements on loop execution, not a claim that the engine capability already exists.

### 6.3 Resolving the target: the material identity comes first, the position is only a helper

| Rule | Explanation |
|---|---|
| **The material identity comes first** | `plateId` / `barcode` is the identity; **a position is a spatial attribute, not an identity** |
| Position addressing must check the attribution | inside the transaction verify `tenant_id + resource_id + occupied_by_run` (**fail-closed**) |
| Barcode addressing must check too | verify the expected position / attribution, unless there is an explicit cross-run hand-over operation later |
| **`AUTO-` is a reserved coding namespace** | it is explicitly registered and resolved by the system generator, not guessed from "the prefix looks similar"; the identities inside that namespace must not drive a material migration as a verified barcode |
| Device re-assignment | the `resourceId` written at design time is not necessarily the actual target; the command parameters, the device response and the final position are the runtime evidence at different levels |
| Resolution fails / conflicts | keep the original evidence and escalate to manual; the target may not be guessed from a similar prefix, the current occupant or the device ID |
| Multi-step nodes | the target must define **which step's end position** it means, and it must keep `command_id + stepSeq`; the position at the end of the node cannot be used to infer what the whole node worked on |

⇒ T1 saves `plate_id` when the identity can be determined; when it cannot, it saves NULL + the
original target and the resolution evidence and goes to `NEEDS_RECONCILIATION`.
The supplementary evidence may only add `confirmedTarget / resolutionEvidence` and never overwrites
the original declaration and the original evidence; before APPLIED a definite same-tenant
`plate_id` is required.

⚠ **The barcode fact (v3.2 addition)**: `plate.barcode` is **NOT NULL** and unique within a tenant
(`uk_plate_barcode`), and the current code has no update path ⇒ §17 Q1 is corrected accordingly.

**The evidence priority** (the review's O-3 requirement: a physical identity must not be declared
confirmed on `occupied_by_run` alone):

| # | Evidence | Explanation |
|---|---|---|
| 1 | an **explicit** `plateId` / `barcode` in the declaration | the plate a person / the designer named — but **it is only "who we think it is", not "who it physically is"** |
| 2 | the material identifier in the **device command response** | what the device itself says (fresher than the position projection) — this is **physical evidence** |
| 3 | the target position in the command parameters + the position state projection | the weakest — **only a helper** |
| 4 | `occupied_by_run` | **it only proves "this run occupied it", not "this is physically the plate"** |
| 5 | **`plate.position_status` / `possible_locations`** (the sixth round's N02 addition) | the plate-level **position trustworthiness** (KNOWN/IN_TRANSFER/UNKNOWN/LOST) — **it is not an identity, but it is the admission condition for using the levels 3/4 to resolve the identity** |

⇒ **The level 5 is not a 6th identity source; it is the admission gate for the levels 3 and 4**
(v3.3 addition · the sixth round's N02):

| Hard rule | Explanation | Basis |
|---|---|---|
| **the target plate's `position_status ∈ {UNKNOWN, LOST}` ⇒ the identity may never be resolved from the level 3/4 evidence** | go straight to **`NEEDS_RECONCILIATION`** + escalate to manual; **the `plate_id` must not be persisted** | the column comment verbatim at `93_m1_parallel.sql:188-190`: "`UNKNOWN` must be inventoried by hand, **the system never guesses**" |
| `position_status = 'IN_TRANSFER'` | the plate is being transferred ⇒ the level 3 (the position projection) is **unavailable**, only the levels 1/2 can be used | `PositionLock.java:212/271`, `DeviceStateMapper.xml:291` write that value |
| `position_status = 'KNOWN'` | the levels 3/4 are **available** | same as above |
| the column is a **live** source of truth, not a historical leftover | a manual inventory writes it (`PauseMapper.xml:27-30`, driven by `PauseManager.java:422/501`) ⇒ it cannot be treated as a read-only field | the production reality: `public.plate` has 6 rows = `KNOWN`×4 / `IN_TRANSFER`×2 |

⇒ **The alignment with the wording of §3 / §4.2 / §17 Q2**: those three places have long listed
`plate.position_status` as an independent source of truth, **and only the implementation rule of
this section missed it** ⇒ once it is added, the sentence "the position trustworthiness =
`position_status`" really reaches the code.
⚠ **The division of labour with §11.2 T10**: T10 covers "**it cannot be found**" (the identity
cannot be resolved) ⇒ isolate and escalate to manual;
this rule covers "**it was found, but the position is officially marked unknown / lost**" ⇒ it is
equally not trusted. The two are **parallel**, not overlapping.

⇒ **The evidence conflicts and the rule errors are kept apart**:

| Case | Disposition | Reason |
|---|---|---|
| ① the declaration and ② the device response point at different plates | `NEEDS_RECONCILIATION`; neither side's `plate_id` is trusted, no Event and no Plate change | the physical identity is uncertain, not a deterministic configuration error; only after a human supplies the evidence may it be recovered |
| ② the device response and ③ the position projection point at different plates | the same | the position may lag, and it may also be a response / mapping error; "the device comes first" is not a reason to write blindly |
| ③ conflicts with ④, or the position trustworthiness refuses admission | the same | weak evidence may not guess at each other; UNKNOWN / LOST must be inventoried by hand |
| ① and ② agree, and the position admission and the attribution check pass | resolved as that plate, with the complete evidence saved | several evidence paths corroborate each other |
| the declaration contradicts itself, the dictionary is illegal or the contract does not support it | `REJECTED` | a deterministic rule error, kept apart from the uncertainty of the physical facts |

⇒ after the reconciliation confirms the target, it is still applied as `completed → PENDING → T2`;
T2 verifies the version, the tenant, the attribution and the declaration again, and the
supplementary-evidence interface itself never writes to the plate directly.

### 6.3a Mid-run scanning and late identity binding (new in v3.3)

A real flow may start without scanning and only obtain the plate barcode at a later subprocess
node. It must not fabricate a fixed `plateId` at run start. Instead, model scanning as **an action
that produces identity evidence**, and model later use as **a reference to that scan fact**:

```json
{ "target": { "by": "scanRef", "scanKey": "currentPlate" } }
```

| Point in time / case | Rule |
|---|---|
| The scan node completes | write the raw barcode, raw response, `command_id/stepSeq`, call path and round into the run-scoped `materialIdentities[scanKey]`; repeating the same value for the same `scanKey` and round is idempotent, while a different value raises a conflict |
| A downstream node has not reached the scan step | preflight must prove the dependency order; referencing a `scanRef` that does not exist yet blocks before dispatch and marks a flow/data error — **never execute first and supply identity later** |
| The scan matches an existing plate in the same tenant | bind to the stable `plate.id`, then verify position trustworthiness, attribution and declaration; `plate_id` is the internal target of later effects |
| The scan matches an AUTO placeholder at the current physical position | only when scan evidence, position and run occupancy agree and the new barcode is unique in the tenant may a controlled one-time `AUTO → scanned` binding occur; the `plate.barcode` update and the `plate_identity_binding` append must be atomic |
| No plate matches, or several plates / positions / declarations conflict | do not auto-create a plate, overwrite a real barcode or pick the "closest" plate; enter `NEEDS_RECONCILIATION` and wait for authorised supplementary evidence |
| After scanning | resolve once and freeze `plate_id + identityBindingId + original evidence`; compensation must neither scan again nor switch to another plate because the current position changed |

- Scanning itself **does not change the process stage or seal state**, so it writes no
  `plate_material_event`; identity binding uses the separate immutable `plate_identity_binding` audit.
- Ordinary barcode editing remains forbidden; the only exception is the controlled AUTO binding
  above. A plate already bound to a real barcode cannot be re-coded by another scan.
- Identity binding and material effects are separate: the binding API / scan processor only confirms
  "who it is", while T2 still confirms "what state it becomes" from the original declaration; a
  successful binding never goes directly to APPLIED.
- Whether the current code already has a reusable scan-result context and an atomic AUTO recoding
  transaction is **unverified**; S2 must check the source first. This section is not a description
  of an existing capability.

### 6.4 Explicitly declared vs not declared

| Case | Behaviour |
|---|---|
| explicitly no material side effect | the node declares `materialEffect.mode = "none"` ⇒ **no alarm** |
| **a builtin logic node (BUILTIN) declaring a material effect** | **ERROR** (blocked at preflight): builtin nodes do not go through the unified completion point and do not carry material effects this round |
| **canvas step-by-step execution / shadow runs / single-command probes** | **not covered**: these paths neither produce nor go through material effect processing (§13.2-7/8) and may only be used for debugging under the operational rules |
| an old flow declares nothing | **WARN** (no behaviour change); in the new version this stays a WARN |
| a node known to change the content / the seal declares nothing | **ERROR** (blocked at publish / start) |
| a node that cannot be classified | a human has to confirm `none` or add a declaration |
| the declared state is not in the dictionary, or is disabled | **ERROR** (blocked at publish / start); **if it is disabled / deleted at run time ⇒ the migration fails and escalates to manual — it must not write "unknown state" and pass as success** |

⚠ **Why it cannot simply be a WARN everywhere**: v1 said "the system cannot tell a transfer from a
forgotten declaration" — that is not accurate. **The capability / method metadata can mark nodes at
design time** as "position-only / may change the content or the seal / unknown", used **only for
validation, never for guessing the state at run time** ⇒ with the classification there is no need
to treat every node the same way.

---

## 7. Data model summary (v3.3 · sixth-round and field subprocess/scan additions; final version pending a real run)

> The SQL below is the **structural baseline of the S1 migration script**, not an idempotent
> script that can simply be re-run: in PG17 `CREATE TABLE / ADD COLUMN / ADD CONSTRAINT` fails
> on a second execution ⇒ S1 must wrap them in
> `DO $$ ... EXCEPTION WHEN duplicate_object / duplicate_column ...` (v2.2 §14 / the fifth
> review's D14).
> **The data semantics of existing tables are not changed**: only columns / tables / constraints
> are added.

```sql
-- ① Plate-level process stage dictionary (tenant level; templates and tenant copies apart)
CREATE TABLE plate_process_state (
  id           bigserial PRIMARY KEY,
  tenant_id    bigint      NOT NULL,          -- 0 for templates; no tenant FK (template rows would block it)
  code         varchar(32) NOT NULL,
  display_name varchar(64) NOT NULL,
  sort_order   int         NOT NULL DEFAULT 0,
  is_template  boolean     NOT NULL DEFAULT false,
  enabled      boolean     NOT NULL DEFAULT true,   -- deactivate, never delete
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);
-- Note: the template set is stored with tenant_id = 0; a new tenant gets copies.

-- ② plate gains columns (v3.2: no current_run_id / origin_*, see §4.5 for attribution)
ALTER TABLE plate
  ADD COLUMN process_state   varchar(32),            -- → plate_process_state.code; NULL = unknown
  ADD COLUMN seal_state      varchar(16),            -- NULL = unknown; none / sealed
  ADD COLUMN state_version   bigint NOT NULL DEFAULT 0;  -- for the CAS
-- Reference constraint: process_state must exist in the same tenant's plate_process_state.
-- ⚠ MATCH SIMPLE (the PG default): when process_state is NULL the foreign key is not enforced
--   ⇒ old data "unknown" ≠ "illegal".
ALTER TABLE plate ADD CONSTRAINT fk_plate_process_state
  FOREIGN KEY (tenant_id, process_state)
  REFERENCES plate_process_state (tenant_id, code);
ALTER TABLE plate ADD CONSTRAINT ck_plate_seal_state
  CHECK (seal_state IN ('none','sealed'));   -- NULL passes automatically ⇒ means unknown
-- Old data: process_state / seal_state are both NULL = unknown; state_version=0 is harmless.
-- (v3.1's seal_state NOT NULL DEFAULT 'none' was a silent back-fill that asserted old plates as
--  "no seal"; v3.2 has changed it.)

-- ③ Flow node: the material effect declaration gets its own column (v2.2 wording; not in params)
ALTER TABLE workflow_node
  ADD COLUMN material_effect jsonb;   -- declaration (mode + inputs/outputs/operations); the run-time snapshot lives in run_node.input

-- ④ Node level: the process lifecycle, the execution confirmation and the material sync are three
--    separate things (six columns in total)
ALTER TABLE run_node
  ADD COLUMN physical_completed_at timestamptz,
  ADD COLUMN physical_evidence_level varchar(24),
  ADD COLUMN physical_evidence jsonb,
  ADD COLUMN execution_resolution_status varchar(32) NOT NULL DEFAULT 'unresolved',
  ADD COLUMN material_sync_status varchar(24) NOT NULL DEFAULT 'not_required',
  ADD COLUMN material_error_code varchar(64);
ALTER TABLE run_node ADD CONSTRAINT ck_run_node_exec_resolution
  CHECK (execution_resolution_status IN
    ('unresolved','completed','failed_confirmed','reconciliation_required'));
ALTER TABLE run_node ADD CONSTRAINT ck_run_node_physical_evidence_level
  CHECK (physical_evidence_level IN ('device_ack','physical_confirmed','manual_confirmed','legacy'));
ALTER TABLE run_node ADD CONSTRAINT ck_run_node_physical_completion
  CHECK (
    (execution_resolution_status = 'completed'
       AND physical_completed_at IS NOT NULL
       AND physical_evidence_level IS NOT NULL
       AND physical_evidence IS NOT NULL)
    OR (execution_resolution_status <> 'completed' AND physical_completed_at IS NULL)
  );
ALTER TABLE run_node ADD CONSTRAINT ck_run_node_material_sync
  CHECK (material_sync_status IN
    ('not_required','pending','applied','failed','needs_reconciliation','cancelled','rejected'));
-- The old data defaults to unresolved + not_required: the physical completion is not faked; legacy
-- is only marked explicitly when there is human migration evidence.
CREATE INDEX ix_run_node_exec_resolution
  ON run_node (tenant_id, execution_resolution_status)
  WHERE execution_resolution_status IN ('unresolved','reconciliation_required');
CREATE INDEX ix_run_node_material_sync
  ON run_node (tenant_id, material_sync_status)
  WHERE material_sync_status IN ('pending','failed','needs_reconciliation','cancelled','rejected');
-- The index WHERE must cover the complete blocking list of §5.2b and the guard query of §5.2c;
-- rolling back an enum value migrates the data first, then NOT VALID→VALIDATE.

-- ⑤ Material effect (the migration intent): the target evidence, the version baseline and the
--    Worker's retry all have a carrier
CREATE TABLE plate_material_effect (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL REFERENCES tenant(id),
  source_run_id     bigint      NOT NULL,
  source_node_key   varchar(160) NOT NULL, -- execution call-path key, not a subprocess template-local node_key
  source_loop_index int         NOT NULL DEFAULT 0,
  effect_index      int         NOT NULL,
  source_attempt    smallint    NOT NULL DEFAULT 0,
  plate_id          bigint,
  declaration       jsonb       NOT NULL,
  target_snapshot   jsonb       NOT NULL,             -- the original target/position/occupancy/commandId/stepSeq/basis
  resolution_evidence jsonb     NOT NULL DEFAULT '{}'::jsonb, -- the original resolution evidence, never overwritten
  reconciliation_evidence jsonb,                      -- the supplementary evidence, appended into the action history
  payload_hash      varchar(64) NOT NULL,
  expected_state_version bigint,
  expected_process_state varchar(32),
  expected_seal_state varchar(16),
  status            varchar(24) NOT NULL,
  apply_attempt_count int NOT NULL DEFAULT 0,
  next_retry_at     timestamptz,
  last_attempt_at   timestamptz,
  applied_at        timestamptz,
  error_code        varchar(64),                      -- the most recent error
  error_message     varchar(255),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_effect_status CHECK (status IN
    ('PENDING','APPLIED','REJECTED','NEEDS_RECONCILIATION','FAILED','CANCELLED')),
  CONSTRAINT ck_effect_dimensions CHECK
    (source_loop_index >= 0 AND effect_index >= 0 AND source_attempt >= 0 AND apply_attempt_count >= 0),
  CONSTRAINT ck_effect_applicable_target CHECK
    (status NOT IN ('PENDING','APPLIED','FAILED')
      OR (plate_id IS NOT NULL AND expected_state_version IS NOT NULL)),
  CONSTRAINT ck_effect_applied CHECK
    (status <> 'APPLIED' OR applied_at IS NOT NULL)
);
CREATE UNIQUE INDEX uk_plate_material_effect_key
  ON plate_material_effect (tenant_id, source_run_id, source_node_key, source_loop_index, effect_index);
CREATE INDEX ix_plate_material_effect_worker
  ON plate_material_effect (tenant_id, next_retry_at, created_at)
  WHERE status = 'PENDING';
CREATE INDEX ix_plate_material_effect_attention
  ON plate_material_effect (tenant_id, status, updated_at)
  WHERE status IN ('FAILED','NEEDS_RECONCILIATION','REJECTED');
-- The Worker only scans PENDING; FAILED/NEEDS_RECONCILIATION are the manual / controlled-recovery
-- queues and are not unconditionally picked up by an ordinary Worker.

-- ⑥ Effect action audit (immutable, append only; v2.2 §4.6 / the fifth review's G6)
CREATE TABLE plate_material_effect_action (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL REFERENCES tenant(id),
  effect_id         bigint      NOT NULL,
  action_type       varchar(16) NOT NULL,     -- DECLARE/APPLY/RETRY/RECONCILE/CANCEL/REBASE
  from_status       varchar(24),
  to_status         varchar(24) NOT NULL,
  actor_type        varchar(16) NOT NULL,     -- AUTO / MANUAL
  actor_id          varchar(64),              -- the operator's user identifier (mandatory for manual actions)
  reason            varchar(500),
  evidence_snapshot jsonb       NOT NULL DEFAULT '{}'::jsonb,
  error_code        varchar(64),
  created_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_action_type CHECK (action_type IN ('DECLARE','APPLY','RETRY','RECONCILE','CANCEL','REBASE')),
  CONSTRAINT ck_action_actor CHECK (actor_type IN ('AUTO','MANUAL')),
  CONSTRAINT ck_action_manual_identity CHECK
    (actor_type <> 'MANUAL' OR (actor_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> ''))
);
-- ⚠ v3.3 (the sixth round's N03): fk_action_effect is **not** inlined here.
--   The original form was a single-column FK (effect_id → effect.id) with **no tenant consistency**
--   ⇒ the measurement showed that `action(tenant=1) → effect(tenant=2)` could be **written
--   successfully** (persisting action_tenant=1 / effect_tenant=2), contradicting §7⑧'s "the
--   cross-tenant constraints have been completed".
--   It becomes the composite FK (tenant_id, effect_id) → plate_material_effect (tenant_id, id),
--   but the referenced side's unique key uq_effect_tenant_id is only created in §7⑧ ⇒ **the order
--   cannot be reversed**, so the FK is added with ALTER in §7⑧.
CREATE INDEX ix_material_action_effect
  ON plate_material_effect_action (tenant_id, effect_id, created_at, id);

-- ⑦ Material events (immutable): the complete before/after + the version interval +
--    changed_fields, replayable from a known baseline
CREATE TABLE plate_material_event (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL REFERENCES tenant(id),
  plate_id          bigint      NOT NULL,
  event_type        varchar(32) NOT NULL,   -- produced/state_changed/state_confirmed/retired etc.
  from_process_state varchar(32),
  to_process_state   varchar(32),
  from_seal_state    varchar(16),
  to_seal_state      varchar(16),
  changed_fields    jsonb       NOT NULL,
  from_state_version bigint     NOT NULL,
  to_state_version   bigint     NOT NULL,
  source_run_id      bigint,
  source_node_key    varchar(160),
  source_loop_index  int         NOT NULL DEFAULT 0,
  command_id         varchar(160),
  effect_id          bigint,
  evidence           varchar(24) NOT NULL,
  evidence_level     varchar(24) NOT NULL,
  evidence_snapshot  jsonb       NOT NULL DEFAULT '{}'::jsonb,
  operator           varchar(16) NOT NULL,
  operator_user_id   bigint,
  note               varchar(255),
  occurred_at        timestamptz,
  recorded_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_event_evidence CHECK (evidence IN
    ('DEVICE_CONFIRMED','EXECUTION_DECLARED','MANUAL_INVENTORY','MANUAL_CORRECTION')),
  CONSTRAINT ck_event_evidence_level CHECK (evidence_level IN
    ('device_ack','physical_confirmed','manual_confirmed','legacy')),
  CONSTRAINT ck_event_operator CHECK (operator IN ('AUTO','MANUAL')),
  CONSTRAINT ck_event_changed_fields CHECK
    (jsonb_typeof(changed_fields) = 'array'
      AND changed_fields <@ '["process_state","seal_state"]'::jsonb),
  CONSTRAINT ck_event_changed_process CHECK
    ((changed_fields ? 'process_state') = (from_process_state IS DISTINCT FROM to_process_state)),
  CONSTRAINT ck_event_changed_seal CHECK
    ((changed_fields ? 'seal_state') = (from_seal_state IS DISTINCT FROM to_seal_state)),
  CONSTRAINT ck_event_version_step CHECK (to_state_version = from_state_version + 1),
  CONSTRAINT ck_event_auto_source CHECK
    (operator <> 'AUTO' OR
      (effect_id IS NOT NULL AND source_run_id IS NOT NULL AND source_node_key IS NOT NULL)),
  CONSTRAINT ck_event_manual_actor CHECK
    (operator <> 'MANUAL' OR
      (operator_user_id IS NOT NULL AND note IS NOT NULL AND btrim(note) <> ''))
);
CREATE UNIQUE INDEX uk_plate_material_event_effect
  ON plate_material_event (tenant_id, effect_id) WHERE effect_id IS NOT NULL;
CREATE UNIQUE INDEX uk_plate_material_event_version
  ON plate_material_event (tenant_id, plate_id, to_state_version);
CREATE INDEX ix_plate_material_event_replay
  ON plate_material_event (tenant_id, plate_id, to_state_version, id);
CREATE INDEX ix_plate_material_event_source ON plate_material_event (tenant_id, source_run_id);
-- from=to with changed_fields=[] is a legitimate state-confirmation event; a recovery action
-- itself writes no Event. An automatic Effect has at most one Event; the same plate has at most one
-- Event per to_state_version.

-- ⑦a Identity-binding audit for mid-run scans (immutable; changes neither process_state nor
-- seal_state and writes no material Event)
CREATE TABLE plate_identity_binding (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint       NOT NULL REFERENCES tenant(id),
  plate_id          bigint       NOT NULL,
  previous_barcode  varchar(160) NOT NULL,
  bound_barcode     varchar(160) NOT NULL,
  source_run_id     bigint       NOT NULL,
  source_node_key   varchar(160) NOT NULL,       -- execution call-path key
  source_loop_index int          NOT NULL DEFAULT 0,
  command_id        varchar(160),
  step_seq          int,
  evidence_snapshot jsonb        NOT NULL,        -- raw scan response, position, occupancy and binding basis
  actor_type        varchar(16)  NOT NULL,
  actor_id          varchar(64),
  reason            varchar(500),
  created_at        timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT ck_identity_binding_changed CHECK (previous_barcode <> bound_barcode),
  CONSTRAINT ck_identity_binding_dimensions CHECK
    (source_loop_index >= 0 AND (step_seq IS NULL OR step_seq >= 0)),
  CONSTRAINT ck_identity_binding_actor CHECK (actor_type IN ('AUTO','MANUAL')),
  CONSTRAINT ck_identity_binding_manual_actor CHECK
    (actor_type <> 'MANUAL' OR (actor_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> ''))
);
CREATE INDEX ix_plate_identity_binding_plate
  ON plate_identity_binding (tenant_id, plate_id, created_at, id);
CREATE INDEX ix_plate_identity_binding_run
  ON plate_identity_binding (tenant_id, source_run_id, source_node_key, source_loop_index);
-- AUTO namespace membership comes from generator metadata and application-transaction checks,
-- never a LIKE-prefix guess; the existing same-tenant plate.barcode unique constraint rejects duplicates.

-- ⑧ Cross-tenant / referential-integrity constraints (v3.2 completes the O-2 requirement; run the data precheck before S1)
-- ⚠ v3.3 final merged counting rule: this section has **14 ADD CONSTRAINT** in total
--   = 4 unique keys (uq_run_tenant_id / uq_plate_tenant_id / uq_effect_tenant_id / uq_effect_tenant_run_id)
--   + 9 foreign keys (fk_run_node_run_tenant / fk_effect_plate_tenant / fk_effect_run_tenant
--                / fk_event_plate_tenant / fk_event_run_tenant / fk_event_effect_same_run
--                / fk_action_effect_tenant / fk_identity_binding_plate_tenant
--                / fk_identity_binding_run_tenant)
--   + 1 NULL CHECK (ck_event_effect_needs_run)
--   ⇒ of which there are **9 cross-tenant foreign keys + 1 NULL CHECK**.
--   (Besides: §7②④ has 6 more ADD CONSTRAINT: fk_plate_process_state / ck_plate_seal_state /
--     ck_run_node_exec_resolution / ck_run_node_physical_evidence_level /
--     ck_run_node_physical_completion / ck_run_node_material_sync; none of them is counted in
--     this section's 14.)
-- Precheck (mandatory on the production database; fix dirty data first, otherwise the ALTER fails):
--   SELECT count(*) FROM run_node n JOIN run r ON r.id=n.run_id WHERE n.tenant_id<>r.tenant_id;
--   SELECT count(*) FROM plate_material_effect e JOIN run r ON r.id=e.source_run_id WHERE e.tenant_id<>r.tenant_id;
-- The order must not be reversed: add the referenced-side unique keys first, then the composite foreign keys.
ALTER TABLE run ADD CONSTRAINT uq_run_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate ADD CONSTRAINT uq_plate_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT uq_effect_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT uq_effect_tenant_run_id
  UNIQUE (tenant_id, source_run_id, id);

-- ⚠ v3.3 (the sixth round's N03): the **composite** foreign key from the audit table to the effect
--   table. The original §7⑥ inlined a single-column FK (effect_id → effect.id) ⇒ no tenant
--   consistency ⇒ the measurement showed that a cross-tenant audit row could be written
--   successfully; the referenced side's uq_effect_tenant_id is already created in this section ⇒ it
--   can be created directly, and the measurement showed that the cross-tenant write is rejected.
ALTER TABLE plate_material_effect_action ADD CONSTRAINT fk_action_effect_tenant
  FOREIGN KEY (tenant_id, effect_id) REFERENCES plate_material_effect (tenant_id, id);

-- Tenant consistency between existing entities (O-2 requires run_node↔run)
ALTER TABLE run_node ADD CONSTRAINT fk_run_node_run_tenant
  FOREIGN KEY (tenant_id, run_id) REFERENCES run (tenant_id, id);

-- Effect / event → plate, → run (same tenant)
ALTER TABLE plate_material_effect ADD CONSTRAINT fk_effect_plate_tenant
  FOREIGN KEY (tenant_id, plate_id) REFERENCES plate (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT fk_effect_run_tenant
  FOREIGN KEY (tenant_id, source_run_id) REFERENCES run (tenant_id, id);
ALTER TABLE plate_material_event ADD CONSTRAINT fk_event_plate_tenant
  FOREIGN KEY (tenant_id, plate_id) REFERENCES plate (tenant_id, id);
ALTER TABLE plate_material_event ADD CONSTRAINT fk_event_run_tenant
  FOREIGN KEY (tenant_id, source_run_id) REFERENCES run (tenant_id, id);

-- Identity binding → plate, → run (same tenant; the binding audit must not cross tenants)
ALTER TABLE plate_identity_binding ADD CONSTRAINT fk_identity_binding_plate_tenant
  FOREIGN KEY (tenant_id, plate_id) REFERENCES plate (tenant_id, id);
ALTER TABLE plate_identity_binding ADD CONSTRAINT fk_identity_binding_run_tenant
  FOREIGN KEY (tenant_id, source_run_id) REFERENCES run (tenant_id, id);

-- Event → effect: same tenant + same run (a three-column FK + a NULL CHECK, O-2's fourth group)
ALTER TABLE plate_material_event ADD CONSTRAINT fk_event_effect_same_run
  FOREIGN KEY (tenant_id, source_run_id, effect_id)
  REFERENCES plate_material_effect (tenant_id, source_run_id, id);
ALTER TABLE plate_material_event ADD CONSTRAINT ck_event_effect_needs_run
  CHECK (effect_id IS NULL OR source_run_id IS NOT NULL);
-- ⚠ Deletion semantics: the foreign keys above default to NO ACTION ⇒ deleting a run is blocked
--   (needed by the history-retention policy; S1 states it explicitly); if a cascade is ever
--   wanted, the retention policy must be reviewed first — never silently changed to CASCADE.

-- ⑨ The updated_at trigger of the new tables (follow the existing attach_updated_at convention; S1 registers them)
--    The table names plate_process_state / plate_material_effect must be added to the explicit
--    attach_updated_at list in pg_rebuild.
```

**The effect state machine (six states, v3.2 adds `CANCELLED`)**:

| State | Meaning | What follows |
|---|---|---|
| `PENDING` | waiting to be processed | the compensation job / the next retry |
| `APPLIED` | the effect has been applied | terminal |
| `REJECTED` | **the effect violates the rules** (the target plate's attribution does not match, the declaration contradicts itself) | terminal, **escalate to manual**, no automatic retry |
| `NEEDS_RECONCILIATION` | **the physical fact is unclear** (the device timed out but may have acted / the identity evidence conflicts) | wait for the reconciliation, **it must not simply be treated as "the action did not happen"** |
| `FAILED` | the processing failed (database / concurrency) | keep + a controlled retry |
| `CANCELLED` | the run terminated / was cancelled, the effect is no longer applied | terminal (`plate_material_effect_action` records CANCEL) |

**The evidence-source enum (`evidence`; v3.2 removes RECONCILIATION)**:

| Value | Meaning |
|---|---|
| `DEVICE_CONFIRMED` | confirmed by a device result (the actual state was read) |
| `EXECUTION_DECLARED` | migrated after the node finished, per the declaration (**the system said it, not the device**) |
| `MANUAL_INVENTORY` | manual on-site inventory |
| `MANUAL_CORRECTION` | a human correcting what the system recorded (**a new event is appended, the history is not overwritten**) |

**Where the Execution Fact lands (v3.2 adjudication R1)**: **do not build a second command ledger** —

| Fact | Carrier | Key columns |
|---|---|---|
| command dispatched, result unknown | **`command_ledger`** | `command_id varchar(160)`, `idempotency_key`, `state`, `resolved_at/by` (and also `abort_state` / `abort_detail` / `abort_at`, the sixth round's N15 addition) |
| command response result | **`run_command`** | `device_raw_response`, `status`, `attempt`, `sent_at`, `finished_at` (**no `command_id` column**) |

⚠ The earlier draft's "`run_command` already has a `command_id`" is a **factual error** (the fifth
review's P0): none of its 17 columns is that one, and `ack_at` has the column but no writer; the
real pre-dispatch fact is in `command_ledger` (a `CommandLedger` implementation already exists,
including UNKNOWN human checks). **`command_ledger` is the command ledger and
`plate_material_event` is the material ledger; the two are not mixed.**

⚠ **The `consumable` / `consumable_lot` tables already exist** (`30_business.sql:134,150`); S5 is
a **refactor**, not a create (§4.4/§14).

⚠ **`plate_material_event` and `plate_location_audit` are two separate lines**: the former records
**content / state** changes, the latter records **position** changes; a single transfer may produce
only the latter.

⚠ **The principle**: the system writing a state per the node declaration **does not mean** the
device really detected the material state. **The two kinds of evidence must not both be displayed
as "confirmed by the device".**

**DDL verification record**:

| Item | Status |
|---|---|
| v3.1 §7 executed as written (a temporary PG 17.11) | ✅ reproduced twice independently; the dictionary FK / seal CHECK / MATCH SIMPLE / the idempotence unique key / the five-state CHECK / the run_node sync CHECK / the three composite foreign keys all verified. ⚠ **what was run is v3.1** (the column width `varchar(32)`, five states, five node-level values), **it does not cover v3.2** |
| building a composite FK directly without `uq_run_tenant_id` | ❌ fails with a missing unique constraint (v3.2 adds that prerequisite constraint) |
| after adding `uq_run_tenant_id`, the cross-tenant / reference constraints | ✅ all can be created; cross-tenant writes are rejected (measured by review-ddl in the fifth round) |
| the MATCH SIMPLE NULL hole of the three-column FK | ⚠ measured to exist ⇒ v3.2 adds `ck_event_effect_needs_run` to catch it |
| `varchar(24)` holding `NEEDS_RECONCILIATION` (20 chars) / `needs_reconciliation` | ✅ the character count checks out; the S1 regression must **insert every value** (six states + **seven** sync statuses) |
| **the whole of the v3.2 DDL run for real** (the sixth round's `20261112-07-mavis.md` §3) | ✅ **already run**: the working copy vs `git show 65fe5ef:` **byte-for-byte identical by sha256** (`d8602173…`, 162 lines / 27 statements) → the isolated schema `labos_v6_review` (PG 17.10, isomorphic prerequisite tables, **zero column-level difference**) → **27/27 statements succeeded** → **all 11 constraints took effect in their reverse tests** (the cross-tenant writes were each rejected, with the error text pasted) → **the enum regression over every value passed** (the six effect states / the six sync statuses / `seal_state` / the `process_state` dictionary FK including the cross-tenant and template-code rejections) → **the production data precheck was clean** (cross-tenant dirty data 0, orphans 0, `(tenant_id,id)` duplicate groups 0) → **the isolated schema was DROPPED** and the production `public` was not written (`plate=6 / run_node=566 / run=99`, the new tables 0, the new columns 0) |
| ⚠ the **two gaps newly found** in that real run by the sixth round | ❌ fixed and to be re-run in S1: `fk_action_effect` was originally a **single-column FK** ⇒ a cross-tenant audit row could be written (N03, now a composite FK); the WHERE of `ix_run_node_material_sync` **missed `cancelled`** ⇒ the gate query degraded into a full table scan (N04, now completed) |
| **the real run of the earlier v3.3 intermediate snapshot** (a later record of the sixth round's `20261112-07-mavis.md`) | ✅ the intermediate snapshot described in that record was once 30/30 statements successful; it proves that the execution-confirmation four states, the composite action FK and the index fixes were effective at that time, **it does not cover the Worker fields, the complete event version interval, the Event unique keys and the target-evidence fields added since then** |
| **the design-internal verification record of the earlier, stronger-constraint v3.3 snapshot** | the design draft once recorded **249 lines / 35 statements, 35/35 successful** and 15 reverse tests; that result points only at the snapshot of that time and does not cover the `plate_identity_binding`, the two identity-binding foreign keys, the execution-call-path column width and the sub-loop / scanning contract added afterwards. It was not re-run in this round and is not taken as the verification conclusion of the current version |
| **the whole of the current final merged v3.3 DDL run for real** | ⏳ **to be run, not claimed as passed**. The whole section must be re-run in a temporary PostgreSQL, adding: the six-column run_node consistency CHECK, the Worker query and the double-Worker lock, the event versions and the unique keys, the forward / reverse constraints of the identity-binding table, the atomicity of the AUTO binding, and the **14** constraints of §7⑧; only after the result is filled back into this table may that prerequisite be closed |

⚠ **Paper DDL that is never executed will never reveal these three kinds of problem** (real defects
caught by v3.1):
1. in the v2/v3 first drafts the dictionary composite foreign key and the seal state CHECK were
   written as comments;
2. the column was too narrow: `needs_reconciliation` is 20 characters and could not be written at
   all when the column was `varchar(16)`;
3. the cross-tenant foreign keys could not be created: the referenced columns need a
   `(tenant_id, id)` unique constraint first.

## 8. Interfaces (reconciliation, supplementary evidence, recovery and version control)

| Method | Path | Explanation |
|---|---|---|
| GET | `/api/plates?processState=&resourceId=&plateType=&positionStatus=` | query plates by stage / position / plate format / position trustworthiness |
| GET | `/api/plates/{barcode}` | one plate's detail; the stable identity is the internal `plate.id`, and the barcode is only the current immutable addressing means |
| GET | `/api/plates/{barcode}/state-history` | the complete event snapshots, changed_fields, the version interval, the evidence and the dual timestamps |
| PUT | `/api/plates/{barcode}/state` | permission `material:write`; the body must carry `expectedVersion + note + evidence`; an omitted field = unchanged, an explicit NULL = changed to unknown; a version mismatch ⇒ 409; a manual Event is appended |
| GET | `/api/plates/{id}/identity-bindings` | the immutable binding history from an AUTO placeholder barcode to the real scan, plus the original evidence |
| POST | `/api/runs/{id}/material-identities:bind` | permission `material:reconcile`; only for the supplementary-evidence / confirmation binding of an AUTO placeholder plate; it verifies the scan, the position, the occupancy and the uniqueness; the Plate update and the binding audit are in the same transaction; it writes no material Event and does not apply an Effect directly |
| GET | `/api/runs/{id}/material-effects` | the material results this run declared, queried by `source_run_id`, not by the current attribution |
| GET | `/api/runs/{id}/material-gate` | the nodes blocked by the gate, the unclosed effects, the execution resolution status and the suggested disposition |
| GET | `/api/material-effects?status=&runId=&plateId=` | the effect list and the target-resolution evidence |
| GET | `/api/material-effects/{id}/actions` | the complete history of DECLARE/APPLY/RETRY/RECONCILE/CANCEL/REBASE |
| POST | `/api/material-effects/{id}/retry` | only a recoverable `FAILED → PENDING`; `material:retry`; `note` is mandatory; when the limit is reached or the error is deterministic it returns 409, and the device is never re-run |
| POST | `/api/material-effects/{id}/reconcile` | `material:reconcile`; the body = `decision + resolutionEvidence + note`, and `confirmedTarget` may be attached; `completed→PENDING`, `not_completed→CANCELLED`, `still_unknown→keep` |
| POST | `/api/material-effects/{id}/rebase` | only for a state-version conflict; `material:rebase`; the body = `expectedVersion + note`; a changed declaration hash / target forbids a rebase; an action is appended and no Event is written |
| POST | `/api/material-effects/{id}/cancel` | an unapplied effect → CANCELLED; `material:cancel`; `note` is mandatory; an action is appended and no Event is written |
| GET | `/api/plate-process-states` | the stage dictionary (tenant level) |
| POST | `/api/plate-process-states` | add a stage (`material:admin`) |
| PATCH | `/api/plate-process-states/{code}` | rename, reorder, deactivate; the code may not change |

**The ordinary resume / end-of-run guard**: it checks node by node the `execution_resolution_status`,
the physical evidence, the original `run_node` status, `command_ledger/run_command` and the
Effect/Event references;
whenever there is a `reconciliation_required`, a command in UNKNOWN/DISPATCH_STARTED, an effect in
`PENDING/FAILED/REJECTED/NEEDS_RECONCILIATION/CANCELLED`, or a node that already has a material
reference and is about to be deleted and rebuilt, it returns 409 with the list.
Only a not-started node without dispatch evidence and a confirmed node with a complete record may
be continued safely; `failed_confirmed` can only go through the explicit safe retry.
The recovery interface does not open the gate directly: only after the effect is actually APPLIED
and the execution evidence is closed and consistent may the resume / the completion of the run be
allowed.

## 9. Pages

| Page | Change |
|---|---|
| **Plate inventory** (`Plates.tsx`, exists) | add the "process stage / seal state / position (and whether it is trustworthy) / production source / last processed" columns; the stage can be edited inline (with `expectedVersion`); a null value shows as "unknown" rather than "none" |
| **Plate traces** (`PlateTraces.tsx`, exists) | a new "state transitions" tab next to the existing "position transitions"; **the evidence source must be shown distinctly** (device-confirmed / per declaration / manual) |
| **Run detail** (`RunDetail.tsx`, exists) | a "material results of this run" block (effect state, evidence source, nodes blocked by the gate) |
| **Material reconciliation console** (new, `MaterialReconciliation.tsx`) | lists `PENDING / FAILED / NEEDS_RECONCILIATION / REJECTED` effects; allows retry / reconcile (reason mandatory); shows the action history; read-only without permission |
| **Process designer** (`Designer.tsx`, exists) | a "material effect" section in the node settings panel (mode + input / output material + resulting stage); preflight adds WARN / ERROR (including ERROR for a BUILTIN node declaring a material effect) |

## 10. Migration and compatibility (v3.2: old-data semantics written out column by column)

| Item | Approach |
|---|---|
| **The old-data semantics, column by column** | `process_state/seal_state=NULL` (unknown); `state_version=0`; `material_sync_status=not_required` (it only means "no material declaration"); `execution_resolution_status=unresolved`, the physical-evidence columns NULL (the old `status=completed` is not faked into having precise physical evidence); the new Event table has no old data, so `changed_fields/from_state_version/to_state_version` are not back-filled |
| **No historical back-fill** | the historical state that is not known is not invented into data; only the rows checked by a human migration may be explicitly marked `legacy`, and they still must not fabricate a precise completion time |
| **What the UI shows** | a NULL shows as "unknown"; `none` alone shows as "no seal"; an unknown execution and a node without a material declaration are shown apart |
| **The execution behaviour of old flows** | no material state is written, `legacy_unspecified` is a preflight WARN and runs as `none`; **the exception**: the new physical-uncertainty replay guard applies to every node, and once there is dispatch evidence but the result is unknown, the old-style delete-and-rebuild / ordinary resume may no longer be used |
| **S1 migration-script requirements** | the idempotent wrapper; the data precheck before the composite foreign keys; register `attach_updated_at`; the same source as `90_all_in_one.sql`; insert every value of the six effect states, the seven sync statuses, the four execution-confirmation statuses and the four evidence levels; add the reverse tests of the event version and source CHECKs |
| **Execution-side compatibility** | `run.workflow_ver` is really written; a declaration change increments `workflow.version`; a continuation reads only the snapshot, and at the same time the all-node replay guard of §8 runs |
| ⚠ **Four things still to be verified independently** | ① consistency between the old and the new queries; ② tenant isolation (`TENANT=1L` is hard-coded, §13); ③ the migration script and the schema artefacts come from the same source; ④ the failure handling / escalation-to-manual path |
| **Schema scripts** | through the `pg_rebuild/` shards + `90_all_in_one.sql` (S1 decides the new shard numbers) |

## 11. Acceptance criteria

### 11.1 Function

| Question | How it is verified | Tier |
|---|---|---|
| After a node produces a plate, its stage changed | run a flow with a `materialEffect` ⇒ `plate.process_state` = the declared value, and `plate_material_event` has an `EXECUTION_DECLARED` event | real environment |
| The migration is traced and the evidence source is visible | one row each in `plate_material_event` and `plate_material_effect_action`, with the right `evidence` / `action_type` | real environment |
| "Who produced it / who processed it last / who owns it now" can be answered | the first / last event's `source_run_id` and `position_state.occupied_by_run` answer them separately | real environment |
| "Which material results did this run declare" can be answered | `GET /api/runs/{id}/material-effects` | real environment |
| The reconciliation loop works | force a `FAILED` / `NEEDS_RECONCILIATION` ⇒ retry / reconcile from the console succeeds, the action history is complete and the downstream gate is released | real environment |
| The dispatch gate only holds the relevant branches | in parallel branches, if one material is not closed ⇒ only its dependent successors are held, unrelated branches continue | real environment |
| Renaming a dictionary entry does not break the reference | change `display_name` ⇒ the display follows, the `code` does not change | pure logic |
| Old flows write no material state, and an uncertain continuation is stopped by the guard | an ordinary old flow writes no Effect/Event; once a command is made unknown, the old-style delete-and-rebuild must be forbidden | real environment |
| The events can rebuild the current projection | after deleting the test projection, replay it from a known baseline; both state dimensions and the version match exactly | database integration |

### 11.2 Failure scenarios (36 · merged up to the sixth-round supplementary review)

| # | Scenario | The result that must be guaranteed |
|---|---|---|
| T01 | the device succeeded, the database write failed | T1 isolates the run and escalates to manual; it must not silently show a success and must not replay the physical action |
| T02 | a `none / legacy_unspecified / transition` node times out | all of them write `execution_resolution_status=reconciliation_required`; with an effect it is NEEDS_RECONCILIATION; an ordinary resume / an automatic retry is forbidden |
| T03 | the same device command callback arrives twice | the effect is applied only once, and there is at most one automatic Event |
| T04 | the same node loops three times | the three effects are traceable by loop / effect_index and none swallows another |
| T05 | one logical execution retries three times | attempt does not go into the idempotence key, and only one material effect is produced |
| T06 | the run crashes and recovers | the confirmed effects are not repeated; a PENDING one is replayed by the already resolved plate_id; an unknown physical result is reconciled first |
| T07 | two runs modify the same plate at the same time | the Plate row lock + the state_version CAS, with no silent overwrite |
| T08 | two materials occupy one position at the same time | blocked by the reused PositionLock |
| T09 | the target position is already occupied | the failure is visible and escalates to manual, no guessing |
| T10 | the material cannot be found, or the position is UNKNOWN/LOST/IN_TRANSFER | the identity is not guessed from the position / the occupancy; the evidence is kept and it is NEEDS_RECONCILIATION |
| T11 | a manual inventory and an automatic migration happen at the same time | only an explicit rebase/cancel; a changed declaration hash or target forbids a rebase |
| T12 | the declaration and the device / position evidence point at different plates | NEEDS_RECONCILIATION; the device ID is not trusted and no Event is written; only a deterministic declaration error gives REJECTED |
| T13 | the node succeeded but the effect processing failed | the node stays physically complete, the relevant successors are not dispatched and the unrelated branches continue |
| T14 | the state update succeeded but the Event/action write failed | the single transaction rolls back; an APPLIED without an Event or a state change without history is not allowed |
| T15 | a cross-tenant material, dictionary, Effect, Event or action reference | the composite constraints and the tenant context must reject it |
| T16 | the recovery of FAILED / NEEDS_RECONCILIATION | the permission, the reason, the evidence and the appended action are complete |
| T17 | the sequential path and the graph path | the same NodeCompletionHandler port and implementation, with no double injection |
| T18 | a multi-step node execution | the commandId/stepSeq and the target-resolution evidence are saved, and the intermediate facts are not missed |
| T19 | an old callback arrives after a manual correction | the version / CAS and the unique keys prevent it from overwriting the manual state |
| T20 | the run terminates abnormally and the position is still held | it can be safely reconciled and released, and the material compensation may not change the position on its own |
| T21 | the node declaration is changed while the run is going | the old run only uses the start-up snapshot; the same key with a different payload must raise an error |
| T22 | a dictionary state is disabled while plates still reference it | the history is still displayable; a new declaration is a preflight ERROR; the effects in flight escalate to manual |
| T23 | several effects of the same execution partially succeed | each effect has its own state, the whole must not be marked APPLIED, and the gate is released only after all of them are closed |
| T24 | the three conclusions of a reconciliation | completed→PENDING→T2; not_completed→CANCELLED; still_unknown stays; all of them append the evidence / an action |
| T25 | an old callback arrives late after a manual correction | the idempotence / version check ignores the old write, and the manual Event and the version are not overwritten |
| T26 | the Nth loop round fails, or an expected effect is missing | the effects of 1..N are aggregated per the declaration snapshot; a missing one or any one that is not APPLIED blocks |
| T27 | an isolated / paused run is restarted | it stays isolated / paused; while the execution, the command or the Effect/Event references are not closed the nodes must not be deleted and re-run |
| T28 | a `none/legacy` node crashes after it already has dispatch evidence | even without a material effect, the delete-and-rebuild and the ordinary resume are forbidden |
| T29 | the Plate projection is deleted and replayed from the events | replay from a known baseline in version order, and at the end both dimensions and the version match exactly; a gap stops the replay |
| T30 | two Workers scan the same PENDING at the same time | `FOR UPDATE SKIP LOCKED` lets only one apply it; the Effect/Event unique constraints are the backstop |
| T31 | the Worker keeps failing on a recoverable error | a bounded exponential backoff, a count and the error are recorded; over the limit it becomes FAILED for manual handling, with no infinite loop |
| T32 | the effect is REJECTED | the node is `rejected`, the gate blocks, there is no automatic retry and no Event |
| T33 | the original target is unknown and authorised supplementary evidence arrives | the original evidence cannot be changed; after `completed` it goes back to PENDING and runs T2; the supplementary-evidence interface itself does not change the plate |
| T34 | explicitly changed to unknown vs a dimension that was not modified | `changed_fields` and the complete from/to tell them apart; a state-confirmation event with from=to may exist and the version advances by 1 |
| T35 | many attempts × a repeated / out-of-order / late callback | the command layer and the effect layer are idempotent separately; a late old callback produces no new effect and does not overwrite the new state |
| T36 | the run ends with an unclosed effect or an unknown execution | `completed` is forbidden; the list of unclosed items is returned, and CANCELLED must not be used to pretend everything succeeded |
| T37 | a sub-process loop × a different source / destination position each round | the device parameters and the material target use the same round binding; the evidence of each round is not overwritten; the same sub-process called from several places / rounds does not collide on the effect key; a missing parameter blocks before the dispatch |
| T38 | no barcode at the start, using `scanRef` after a mid-run scan | a reference before the scan is blocked; a hit on an existing plate resolves to `plate.id`; the controlled AUTO binding and the audit are committed atomically; a duplicate / a conflict / no match never guesses a plate; the compensation does not re-scan |

⚠ Any one of them without an explicit handling mechanism means the material state management
**cannot be claimed production-ready**.

---

## 12. Implementation slices (v3.3 update)

| Slice | Content | Depends on | Produces |
|---|---|---|---|
| **S0** | The domain model and the contract are finalised: the three status lines, the structured idempotence key, T1/T2, the complete event snapshots and the replay, the six-state effect, the Worker lock / backoff / cap, the loop gate, the REJECTED mapping, the target supplementary evidence, the recovery guard, the execution-path gaps; all the prerequisites of §16.3 are completed | — | no code until the re-review passes |
| **S1** | The data structures: the stage dictionary, the three `plate` columns, `workflow_node.material_effect`, the **six** `run_node` columns; **the new writes / new queries must explicitly carry `AND loop_index = 0` or an exact round condition** (carried over from §5.5 / the sixth round's O-9 and N01: the 5 existing per-node update SQL statements do not filter `loop_index`, so they hit the round rows); the Effect carries the target / resolution / reconciliation evidence, the expected version and the Worker fields; the Event carries the complete before/after, `changed_fields`, `from/to_state_version`, the evidence snapshot and the unique keys; the action table; the **`plate_identity_binding` identity-binding audit**; the **14** constraints of §7⑧; the `workflow_ver` write and the migration regression. **Not wired into the execution engine** | S0 | the tables are in place, zero behaviour change |
| **S2** | The `NodeCompletionHandler` port and its implementation, T1/T2, the execution-confirmation guard, the Effect Worker (SKIP LOCKED / backoff / cap), the per-round parameter binding of a sub-process and the call path, the mid-run scan / the controlled AUTO binding, the loop gate, the run isolation, reconcile / supplementary evidence / retry / rebase / cancel, the event projection replay and the fault-injection integration tests | S0, S1 | the behaviour starts to change, the 38 failure scenarios pass |
| **S3** | Material query and traceability: the interfaces + the inventory columns + the state transition history + the run detail material results + the read-only reconciliation console | S2 | the site can look it up |
| **S4** | The process designer: the material effect section (mode) + preflight + the node impact classification | S2, S3 | the site can configure it |
| **S5** | The independent consumable domain: **refactor the existing** `consumable` / `consumable_lot` (position + attachment history + state machine), the lid stock and handling | S0 | lids become traceable |

**The boundary between S0 and S1**: S0 only settles the model / contract (no execution-code change,
no tables); S1 only creates tables and migrates; any execution-behaviour change is S2+.
**S1 must not be touched before S0 is frozen.**

---

## 13. Known boundaries and items still to close (v3.3 honest list)

### 13.1 Adjudicated boundaries (no longer "open", they are explicitly not done this round)

| # | Boundary | Disposition (v3.3) |
|---|---|---|
| 1 | **Tenant isolation is not actually implemented** (`TENANT = 1L` hard-coded in **40 places in the kernel module, 15 of them in the boot layer**) | this round only guarantees the **database-level** tenant constraints of the new entities (the **14** of §7⑧); the tenant-context refactor is **listed as an S1 prerequisite risk** and is not pretended to be implemented |
| 2 | the completion handler has to change the `RunStateMachine` signature (to pass the round) | **adjudicated**: a single injection point + an explicit execution context (§5.7); the list of change points is in §5.7 / §12 S2 |
| 3 | the state machine of the stages (legal transitions) is not done this round | only the dictionary and the reference constraint; **transition legality is deferred until a real dictionary exists** (re-evaluated after S4) |
| 4 | the consistency check between the well-level sample and the plate-level stage is not done this round | the boundary is nailed down (§4.2's three boundaries); the consistency check is listed as a post-S3 task |
| 5 | the timing of the implementation of the "not declared" classification metadata | S1/S2 does not guess the node impact at run time; the old flows stay a WARN. Only after S4 adds the minimal capability / method classification is "known to change the content but declares nothing ⇒ ERROR" enabled; a node that cannot be classified still needs a human to confirm `none` or add a declaration, consistent with §6.4 |
| 6 | the cross-process attribution hand-over is not done this round | attribution adds no column (§4.5); the cross-process CREATE / handoff loop is stage 3 |

### 13.2 Known gaps found by the fifth review (to be handled inside S0)

| # | Gap | Disposition (v3.2) |
|---|---|---|
| 7 | `POST /api/experiments/flow-run` → `ExperimentFlowRunner` **bypasses the unified completion point**, drives the device directly and writes run / run_node / run_command itself | **S0 chooses one of two**: ① take the endpoint offline; ② refactor it to go through `WorkflowService.execute` and declare `mode=NONE`. **Until it is disposed of, "all execution paths enter the unified handler" must not be claimed** |
| 8 | `ShadowRunner` / single-command probes **really dispatch device actions but persist no execution fact** | marked as "physical actions that produce no execution fact" ⇒ written into the operational rules as a restriction + a second confirmation on the page; the material system **does not cover them** (stated explicitly, not pretended) |
| 9 | the schema script's `node_status` enum lacks `manual` / `bypassed` while the code writes them | before S1, use `check-schema-sync.sh` + the live database to check the enum definition; on drift, write a migration script |
| 10 | `plate.location_id` and `position_state` are double-written | S1 declares `position_state` the single source of truth for position; `plate.location_id` is marked a read-only redundancy (stop the double write or mirror it only inside the transaction); **no third copy of the position may be added** |
| 11 | `run_command.ack_at` has the column but no writer; `command_id` is in `command_ledger` | this document takes `command_ledger` as the reference; if `ack_at` is to be evidence, a write point must be added (S2) |
| 12 | the device's ability to give "physically confirmed" evidence | stays UNKNOWN (§16.2); S0 checks it device by device, no guessing |
| 13 | **the material status of each round of a loop has no trustworthy carrier** | ✅ **the design is closed, the integration verification is in S2**: it does not read the `run_node` round rows, which are overwritten by the later rounds and do not exist yet at T1; the run-start snapshot gives the expected `outputs.length` and the effect_index set of each round, and the actual Effects are aligned by `source_loop_index + effect_index`. **A missing expected effect or any one that is not APPLIED blocks**, so no extra `expected_effect_count` column is needed; the structured unique-key prefix supports the query. T04/T23/T26 verify multiple rounds, multiple effects, a missing row and a partial success |
| 14 | **effect `REJECTED` has no corresponding value at the node level, and no mapping rule** (the 05 review's O-5 **partially landed**) | ✅ **adjudicated and closed (v3.3, §0.4 N5)**: take **(a) add a `rejected` value** ⇒ the node-level `material_sync_status` becomes **seven values** = the six effect states + `not_required`, the effect `REJECTED` maps **one-to-one** onto the node `rejected` and is **not merged with the retryable `FAILED`**; the gate's blocking list gains `rejected` at the same time (§5.2b items 1/2). ⚠ **The index must be synced as well** (the sixth round's N09): the WHERE of `ix_run_node_material_sync` must ⊇ the complete blocking list (including `cancelled` and `rejected`), and **PG does not follow a CHECK change automatically** (measured: after only changing the CHECK, `pg_get_indexdef` is identical before and after) ⇒ the index must be **rebuilt**; and rolling back an enum value requires migrating the data first (the recommended path is `ADD CONSTRAINT … NOT VALID` → `VALIDATE`, two steps) |
| 15 | **a round row is not an immutable fact** (the sixth round's N01 · P1) | ✅ **fixed (v3.3)**: §5.5 now has the full derivation chain — `updateNodeStateLive` / `updateNodeResult` do not filter `loop_index` ⇒ **a later write overwrites an earlier one** ⇒ a round row is only "the shape of the last write", not a per-round fact. **Consequence**: the purpose the DDL comment states for itself — "review why the 3rd round failed" — **no longer holds**; the option (b) of §13.2-13 is rejected. **Residual**: a new known gap — **the historical round-row data is untrustworthy** (the production database currently has 0 rows with `loop_index>0`, so there is no existing data harmed); the new writes of S1/S2 must explicitly carry `AND loop_index = 0` or an exact round condition (the correct style inside the database is `RunMapper.xml:658` `insertBuiltinCommand`) |
| 16 | **the evidence priority of §6.3 misses the 5th source** (the sixth round's N02 · P1) | ✅ **fixed (v3.3)**: §6.3 adds the 5th level `plate.position_status` / `possible_locations` and nails down the hard rule — **when the target plate's `position_status ∈ {UNKNOWN, LOST}`, the identity may never be resolved from the level 3/4 evidence** ⇒ straight to `NEEDS_RECONCILIATION` + escalate to manual; when `IN_TRANSFER` the level 3 is unavailable. The basis is the column comment at `93_m1_parallel.sql:188-190` — "UNKNOWN must be inventoried by hand, the system never guesses". It is a **parallel** relation to §11.2 T10 (it cannot be found) |
| 17 | **the audit table → the effect table is a single-column FK, so a cross-tenant audit row can be written** (the sixth round's N03 · P1) | ✅ **fixed and verified by a real run (v3.3)**: `fk_action_effect` was inlined as `FOREIGN KEY (effect_id) REFERENCES plate_material_effect(id)` ⇒ **no tenant consistency**, and the measurement showed that `action(tenant=1) → effect(tenant=2)` **writes successfully**. It is now a composite FK `FOREIGN KEY (tenant_id, effect_id) REFERENCES plate_material_effect (tenant_id, id)` (since the referenced side's `uq_effect_tenant_id` is only created in §7⑧ ⇒ the order cannot be reversed, and the FK is added with ALTER in §7⑧). **Measured**: the cross-tenant write is rejected (`violates foreign key constraint "fk_action_effect_tenant"`), and a same-tenant write succeeds. The counting rule of §7⑧ is changed to **12** at the same time (4 unique keys + 7 foreign keys + 1 NULL CHECK) |
| 18 | **the partial index misses `cancelled` ⇒ the gate query degrades into a full table scan** (the sixth round's N04 · P1) | ✅ **fixed and verified by a real run (v3.3)**: the WHERE of `ix_run_node_material_sync` was `('pending','failed','needs_reconciliation')`, **missing the blocking value `cancelled` explicitly listed in §5.2b** ⇒ the EXPLAIN measured the gate query as `Parallel Seq Scan` (200k rows in run_node). It is now completed to `('pending','failed','needs_reconciliation','cancelled','rejected')`. **Measured**: the gate query → `Index Scan using ix_run_node_material_sync`. A new invariant is added as well — "**every value set a guard queries must have a partial index that covers it**" — and `ix_run_node_exec_resolution` is added for §5.2c's continuation guard |
| 19 | **a sub-process loop is not an existing parameter-binding capability** | §6.2a has defined the per-round binding, the shared resolution result, the call path and the immutable snapshot; the existing engine carrier is not verified — S0 verifies the current state and S2 verifies it with T37. Keeping only `loop_index` and then claiming that a loop taking a plate out is supported is not allowed |
| 20 | **no barcode at the start and a mid-run scan need late binding and a controlled AUTO re-coding** | §6.3a / §7⑦a / §8 have defined scanRef, the identity-binding table and the conflict disposition; the existing scan context and transaction implementation are not verified — S2 verifies it with T38. Guessing a plate before the scan, creating a plate automatically and overwriting a real barcode are all forbidden |
| 21 | **the release of the position and the waste (G11) have no landing point** (pointed out by the sixth-round reviewer C) | §11.2 **T20 "the position is still held after the run terminates abnormally ⇒ it can be safely reconciled and released" is the only failure scenario in the whole document that has no handling mechanism at all** — §13.2 had no corresponding entry before, and the word "release" appears in the whole document only inside T20 itself. **Disposition**: the position belongs to `PositionLock` (§4.5) and the material system **does not change positions**; the landing point of T20 is "run isolation / termination ⇒ the manual reconciliation and release entry of `PauseCoordinator` + `PositionLock`", **not** a material compensation that changes the position. ⇒ Within S0 the mechanism attribution of that entry must be completed (who writes the reconciliation, which API, which table the audit goes to), otherwise T20 of §11.2 is still a scenario without an owner. **The waste G11**: it is **not in the delivery of this round** (§13.1 already has the boundary), but §13.2 must explicitly register it as "not covered" rather than as a missing item, so that the next person does not think it was overlooked |

**Conflicts with existing documents** (per §7 of `docs/评审规范.md`: a ②③ conflict ⇒ change the
specification first):

| Conflict | Disposition |
|---|---|
| `LabOS功能规格说明书.md` §7.2 "2.0 delivers sequence only" | this round only adds container dimensions, it **does not touch process orchestration** ⇒ no conflict; a "known boundary" note pointing at the gap document was added |
| `docs/板位级占用与位置互斥-设计.md` §5 "plate-level tracking (where the plate is)" | this round adds "which step of the process the plate is at"; the two complement each other; a pointer to this document should be added there |
| `docs/工艺编排能力缺口与落地路线.md` §4.2 (the G2 criterion) | **corrected per the reviews**: the criterion is widened to "independent identity or lifecycle traceability"; §4.4 here is the corrected version |

## 14. Explicitly not in this round (v3.3)

| Not done | Reason |
|---|---|
| **refactoring** `consumable` / `consumable_lot` (the tables exist, see `30_business.sql:134,150`) | it needs an independent position and attachment-history model ⇒ deferred to S5 as a whole; this round **neither creates nor changes them** |
| the station layer (`station_id`) | stage 2; this round addresses by `resource_id` + an ownership check |
| material hand-over across processes | stage 3; attribution adds no column this round (§4.5) |
| events / triggers / setpoints | stage 4; a paradigm change with the highest risk |
| aliquoting, mixing, batch expansion | the contract reserves `operations`, not implemented this round (the validation layer restricts to one-to-one) |
| the stage state machine (transition-legality check) | it can only be defined once there is a real dictionary |
| the consistency check between well-level samples and the plate-level stage | it needs a more complete well-level data model |
| a separate "material" overview page | re-evaluate once the dictionary and the stages are in use |
| versioning of the whole BPMN graph | this round only snapshots the material declaration (§5.3) |

## 15. List of decision points (v3.3: 20, each with a conclusion)

| # | Decision point | Conclusion | Basis |
|---|---|---|---|
| 1 | The transaction boundary between node completion and the state transition | execution facts + a two-phase material effect (T1/T2); no reliance on a single transaction | all three reviews agree; the current architecture cannot do a single transaction |
| 2 | Ownership of the dictionary | tenant level + composite foreign key + deactivate-instead-of-delete + templates / copies apart (templates use `tenant_id=0` with no FK) | Grok / Kimi / OpenAI agree |
| 3 | The criterion for splitting consumables | does it need an independent identity or lifecycle traceability | OpenAI + Kimi |
| 4 | `produces` inside `params` or a dedicated column | a dedicated column `workflow_node.material_effect` | OpenAI + Kimi (`params` is passed through to the device) |
| 5 | Should a missing declaration block | classify by node impact; a node known to change the content that declares nothing ⇒ ERROR; old flows are a WARN with no behaviour change | all three reviews agree |
| 6 | The six `plate_type` values | rejected: `plate_type` is the plate format specification and is not changed | Grok + OpenAI + Kimi |
| 7 | Are the consumables (S5) in this round | accepted as deferred; and it is a **refactor of existing tables** (`consumable` already exists), not a create | Kimi + OpenAI |
| 8 | The anchor of the idempotence key | **a structured unique key** (tenant, run, node, loop, effect_index); no string `execution_id`; `attempt` not in the key | the v3 three-way adjudication + v2.2's objection (adopted by v3.2) |
| 9 | The relation between node completion and the effect | the completion handler returns `CONTINUE / HOLD / throw`; no new `node_status`; the downstream relies on the **dispatch gate** (not a scheduler dependency check, and not `runtime_hold` node holds) | the third design's D4 + the fifth review's source check |
| 10 | The effect state machine | six states; a reconciliation `completed→PENDING`, `not_completed→CANCELLED`, `still_unknown→keep`; every disposition is recorded as an action | the fifth review's G6 + the sixth-round attachment |
| 11 | The recovery entry point | T1 isolation; an ordinary resume is forbidden until the execution confirmation, the command ledger and the Effect/Event references are all closed; only `failed_confirmed` goes through the explicit safe retry | the fifth review's S4 + the sixth-round attachment |
| 12 | Where attribution lands | do not add `plate.current_run_id / origin_*`; the current attribution reads `position_state`, the source / history read the event stream | v2.2's objection, kept |
| 13 | The execution-fact carrier | `command_ledger` + `run_command`, no second command ledger | the fifth review's P0 |
| 14 | The execution resolution status | the new four states `execution_resolution_status`; the physical-uncertainty replay guard is shared by all declaration modes | the sixth-round attachment, D414 |
| 15 | The events and the replay | the complete before/after + changed_fields + the from/to versions; APPLIED corresponds to exactly one automatic Event; replayable from a known baseline | the sixth-round attachment, D415 |
| 16 | The Worker's concurrency and retry | it only handles PENDING; one short transaction per effect with SKIP LOCKED; no persistent PROCESSING; a bounded backoff and a count cap | the sixth-round attachment, D416 |
| 17 | The loop gate and REJECTED | the expected / actual Effects of each round are aligned per the declaration snapshot; a missing one blocks; REJECTED maps one-to-one onto the node `rejected` | the v3.2 leftover + the sixth-round re-check, D417 |
| 18 | The target supplementary evidence and AUTO | the original evidence cannot be overwritten; after the supplementary evidence it still goes through T2; AUTO is a reserved namespace and does not drive a material migration; an identity conflict is reconciled first | the sixth-round attachment, D418 |
| 19 | The sub-process loop and the per-round parameters | the multi-action sub-process is the process unit of a loop; each round first binds the source / destination position and the identity reference, and the device and the material share the result; the execution call path + loop_index prevent duplicates | the field supplement, D419 |
| 20 | The mid-run scan late binding | a run-scoped scanRef; guessing a plate before the scan is forbidden; an existing plate resolves to plate.id; AUTO is only bound once under controlled conditions and is audited separately; a conflict is reconciled first | the field supplement, D420 |

**Wording convergence (v3.2 update)**:
① the effect state machine has six states; the event table is named `plate_material_event` (with
physical / recording dual timestamps); the effect action gets its own table;
② `plate_material_event` only records real plate changes, and reconciliation actions **do not fake
events** (they are recorded as actions);
③ the Execution Fact reuses `command_ledger` + `run_command`, and the material event keeps its own
ledger;
④ the declaration column `workflow_node.material_effect` + a run-start snapshot
(`run_node.input.material_effect_snapshot`).

## 16. The five hard gates of S0: where they stand after the sixth round and the field supplement (v3.3)

> From the unified architecture V1.0 (record 04, O-6). **Only when all five are met plus the gap
> dispositions of §16.3 are done may S0 be frozen and S1 entered.**

| # | Gate | Where it stands (after the v3.3 merge) | Basis |
|---|---|---|---|
| 1 | The recovery state machine is explicit (human reconciliation, permission, reason, audit) | ✅ **met in design**: six states + a transition table + the action audit + retry / reconcile APIs + permission codes (§5.8 / §8) | the fifth review's G6 / S4 disposition |
| 2 | The tenant constraints are explicit | ✅ **met in design**: the **14** of §7⑧ (4 unique keys + 9 foreign keys including the three-column FK + 1 NULL CHECK) + the data precheck (§7⑧); ⚠ the final version including the identity-binding table has not been run for real yet | the fifth review's D2; the sixth round's N03/N07; the field supplement's N10 |
| 3 | The identity resolution is explicit | ✅ **met in design**: the identity evidence + the position admission + reconciling a conflict first; the mid-run `scanRef` late binding and the controlled AUTO binding are supported, and an ordinary barcode still cannot change (§6.3/§6.3a/§17 Q1) | the fifth review's G9 + the field supplement |
| 4 | The execution paths are explicit | ⚠ **covered on the design side, not closed on the code side**: the 2 production paths are confirmed to enter the unified completion point; **the flow-run endpoint is a gap** (§13.2-7), shadow runs / probes are not covered (§13.2-8) ⇒ judged **PARTIAL (blocks the S0 freeze)**; disposition: take the flow-run endpoint offline first inside S0 (recommended) or refactor it in | the fifth review's S2 |
| 5 | The extension boundary is explicit | ✅ restricted to the validation layer; `operations` is a placeholder only and preflight rejects an unknown operation (§6.2) | the fifth review's O-5 re-check |

### 16.1 The execution-path inventory (v3.2, checked line by line against the source, replacing the earlier guess of "six paths")

| # | Execution path | Entry → completion point | Enters the unified completion point | Disposition |
|---|---|---|---|---|
| 1 | normal graph execution (production) | `WorkflowService.execute → GraphRunner.run → RunStateMachine.executeNode` | ✅ | the completion handler is mounted here (§5.7) |
| 2 | sequential execution (production fallback / non-graph) | `WorkflowService:1057/1065 → sm.executeAll → executeNode` | ✅ | same as above (the round is passed as 0) |
| 3 | multi-step node | the recipe expands into steps, several steps inside one `executeNode` | ✅ | the step-level fact already exists (`run_command`) |
| 4 | loop node | each re-entry is one `executeNode`; the round is passed by the execution context | ✅ | the loop aggregates the status of every round (§5.2b gate) |
| 5 | device retry | a loop inside `executeNode` with `attempt` incrementing | ✅ | `attempt` only goes into the action audit |
| 6 | manual recovery **`ResumeAction.CONTINUE`** / the `POST …/skip-node` API | `PauseCoordinator:297-300 → resumeAsync → execute`; skipNode is `RunService.skipNode:369-386` (it marks `skipped` manually + `skip_confirmed`, then `resumeAsync`) | ✅ | — |
| 7 | manual recovery **`ResumeAction.RETRY` / `ResumeAction.SKIP`** (window) | `PauseManager.resumeTx:450 → :477-481` only sets `queued` (`PauseMapper.xml:104-108`) and **does not continue immediately** — it can only be picked up by `WorkflowResumeRecovery.rehydrateQueued` (`ApiServer.java:529-533`) **at process startup** | ⚠ | fix in S2: continue immediately, or make the UI wording explicit |
| 8 | canvas step-by-step execution | `POST /api/experiments/flow-run → ExperimentFlowRunner → ShadowProbe → driver.execute` | ❌ | **gap**: take it offline or refactor it inside S0 (§13.2-7) |
| 9 | shadow runs / single-command probes | `ShadowRunner` / `ShadowProbe` really dispatch, and persist no run / run_node / run_command | ❌ | **not covered**: operational-rules restriction + an explicit statement (§13.2-8) |
| 10 | CLI / demo (verification only) | `RealWorkflowRunner` etc. | — | a non-production path, not counted |

### 16.2 The things that must stay UNKNOWN (no "we will refine it later" in place of a decision)

| Item | Why it is UNKNOWN right now |
|---|---|
| whether the device can supply strong "physically confirmed" evidence | it depends on the driver capability of each device; S0 checks it device by device, no guessing |
| how the existing `AUTO-<runId>` history rows are to be inventoried / upgraded into verified identities | the new flow only allows a controlled binding whose scan, position and occupancy agree (§6.3a); the historical rows still have to be inventoried plate by plate and must not be bulk-guessed into real barcodes |
| whether the per-round parameter binding of a sub-process, the call path and the scan context already have an engine carrier | §6.2a/§6.3a is the target contract; it is not assumed that the existing GraphRunner already supports it — S0 verifies the current state, S2 verifies it with T37/T38 |
| whether the live `node_status` enum contains `manual` / `bypassed` | the schema script and the code appear to have drifted; check the live database before S1 |

(v3.1's "may a barcode change / be duplicated" has moved out of UNKNOWN — an ordinary re-coding is forbidden and the controlled AUTO binding is the exception, see §17 Q1.)

### 16.3 The S0 freeze decision (v3.3 update)

**Conclusion: the current merged v3.3 still cannot freeze S0.** The following **5** prerequisites
must be completed before signing once more; the earlier intermediate-snapshot verification cannot
replace the final-version verification.

| # | Prerequisite | Owner | Acceptance criterion |
|---|---|---|---|
| 1 | **take the flow-run endpoint offline or refactor it into the unified executor** | S0 | the endpoint returns 404 or goes through WorkflowService; §16.1 no longer has an uncovered production path |
| 2 | **confirm the disposition of the fifth round** (records 05 + 06) | review | M1–M24 and O-1–O-15 have no P0/P1 open; this document has not rolled back command_ledger, the run isolation, the independent declaration column or the composite constraints |
| 3 | **confirm the disposition of the sixth round and the field supplement** (the supplementary attachment + record 07 + the sub-loop / scanning) | review | the execution confirmation, the event replay, the Worker, the loop / REJECTED and the target supplementary evidence all have a design landing; the sub-process call path, the per-round binding and the scanRef / AUTO binding pass the review; no P0/P1 is open |
| 4 | **run the current final merged v3.3 DDL for real in a temporary database** | S0 | the strategy for running the whole section twice is clear; the six run_node columns, the effect target / retry fields, the event version / source / unique keys, the identity-binding table, the **14** constraints of §7⑧ and the reverse tests over every enum are all green, and the result is written back into §7 |
| 5 | **confirm the plan for the continuation guard and the key concurrency integration verification** | S0/S2 | a `none/legacy` unknown result forbids a re-run and has a source mapping; the sub-loop parameters, the late binding by a scan, the missing loop expectation, the REJECTED gate, the two Workers, the retry over the limit, the target supplementary evidence and the event replay all have an executable test and a fault-injection point |

⇒ only when the five are done may D401–D420 be registered and the S0 freeze be submitted; before
that **S1 does not move**.
⚠ The runtime fault injection and the device's strong physical-evidence capability remain
independent verification items, and cannot be replaced by a static code derivation, a simulator or
a successful DDL.

⚠ **The runtime behaviour and the device evidence still have to be verified later**: the P0
argument of §5.2b is currently only a **static code derivation**, and S2 measures it with fault
injection; the device's ability to give "physically confirmed" evidence stays UNKNOWN (§16.2).
Neither of these two can be replaced by a successful DDL or by a documentation review.

### 16.4 Delivery verification levels (v3.2 addition · absorbs v2.2 §11.5)

| Slice | Delivery verification |
|---|---|
| **S0** | the domain model is finalised + the review is closed: all 5 prerequisite actions of §16.3 are achieved and the freeze is signed; it **contains** no table-creation / code change at all |
| **S1** | migration script + schema sync + database constraints and all-enum tests (including a **reverse test** of every constraint of §7⑧, not only a check that it "can be created") |
| **S2** | real PostgreSQL fault injection + integration tests of the §16.1 execution-path inventory and the dispatch gate; **the regression item numbers of §18.3 must be named** (T13 / T24 / T26 / the round coverage, etc.) |
| **S3 / S4** | APIs, the complete action history, hands-on page operation |
| **S5** | the separate consumables-domain refactoring (the existing `consumable` / `consumable_lot` tables) — the regression of the positions, the attachment history and the state machine |

⚠ **Passing with a simulated driver proves only the logic chain, not real-device verification** ⇒ the real-machine
conclusion must be re-established by HIL (hardware in the loop). No acceptance record of this document may
**use simulator results to impersonate a real-machine conclusion**, nor infer real-machine usability from the
S1 / S2 pass rate.

## 17. The ten questions the review has to answer (v3.3 re-answer)

> From appendix B of the unified architecture V1.0. **Every question comes with a source
> reference, a design decision, or an explicit UNKNOWN.**

| # | Question | This document's answer (v3.3) |
|---|---|---|
| 1 | What field guarantees the stable identity of a plate? May a barcode change or be duplicated? | the stable identity = `plate.id`; the barcode is NOT NULL and unique within a tenant, and an ordinary re-coding is forbidden. `AUTO-` is the system's reserved coding namespace and is explicitly recognised by the generator; only when the mid-run scan evidence, the position and the occupancy all agree may an AUTO placeholder plate be bound once under control to a real barcode, and a `plate_identity_binding` is appended. An existing real barcode must not be overwritten by a scan, and the historical rows have to be inventoried by hand |
| 2 | Does the current state of a plate already have several sources? How is a single authoritative state determined? | there are already 5 groups: `plate.status` / `plate.position_status` + `possible_locations` / `position_state` / `plate.location_id` (a redundant projection) / `device_state` derivation. the unified rule is: lifecycle = `plate.status`; position = `position_state`; position trustworthiness = `position_status`; `location_id` is read-only redundancy; the process stage = the new `process_state` (§4.2's inventory table) |
| 3 | What is the device's evidence of the material identity? | the command fact is in `command_ledger` and the response is in `run_command`; a mid-run scan first forms a run-scoped `scanRef`; the declaration, the device response, the position projection and the Run occupancy are different kinds of evidence, and `position_status` is the admission of the position rather than an identity. Any identity conflict is `NEEDS_RECONCILIATION` and the device ID is not trusted outright; only after the supplementary evidence / the controlled binding does it go through T2 |
| 4 | How does an execution fact tell a loop, a retry and a duplicate callback apart? | a loop = the sub-process execution call path + the current round (0 for an ordinary acyclic execution), and the per-round binding result is frozen; a retry = `attempt`, and it does not go into the effect key; a duplicate callback = the structured effect key and the Effect/Event unique keys; the command layer additionally uses `command_ledger.idempotency_key` |
| 5 | After the device finishes, the database commit fails — how is that recovered? | T1 isolates and reconciles first, and re-running is forbidden; T2 keeps the node physically complete, the effect `PENDING/FAILED` and the relevant successors blocked. `none`/`legacy` are also bound by the `execution_resolution_status` guard; only `failed_confirmed` may take an explicit safe retry |
| 6 | How does an old asynchronous callback avoid overwriting a manual correction? | the manual Event advances `state_version`; T2 verifies the expected version, a conflict does not overwrite, and only an explicit rebase/cancel is possible; an old callback is ignored once it hits the effect key and the Event unique key; every disposition appends an action |
| 7 | Do the sequential path and the graph path really share the same completion handler? | **by design: yes** (the single injection point = `RunStateMachine.executeNode`, and the two production paths share the same `sm`); **source: ✅ the two production paths are proven; ❌ the flow-run endpoint / shadow runs do not go through it** ⇒ gate 4 is judged **PARTIAL** (§16.1/§16.3) |
| 8 | How do PositionLock and the material state update coordinate? | the position belongs to `PositionLock` (`position_state` + `occupied_by_run` = the single source of truth for position), the material belongs to `MaterialEffectService`; the two never rewrite each other; "current attribution" is always read from `occupied_by_run` and **no `current_run_id` is added** |
| 9 | Can the multi-tenant constraints be guaranteed by the database? | among the new entities ✅ (the **14** of §7⑧: 4 unique keys + 9 foreign keys (including the three-column FK) + 1 NULL CHECK, including the two identity-binding foreign keys); the existing `run_node↔run` ✅; but **the 40 hard-coded `TENANT=1L` places in the kernel module (15 in the boot layer)**, the `plate.location_id` redundancy and other historical problems remain ⇒ overall **PARTIAL**, listed as an S1 prerequisite risk; the final DDL is still to be run for real |
| 10 | How does a one-to-one Effect leave room for a future SPLIT / MERGE? | `outputs` is an array and `effect_index` distinguishes effects; this round restricts one-to-one in the validation layer; `operations` is a placeholder only and preflight rejects an unknown operation; a future SPLIT/MERGE is evaluated separately with **contract v2 + effect grouping**, without changing the existing table structure |

---

## 18. The disposition summary of the fifth independent review (v3.2) and the decision-number register

### 18.1 Disposition of the findings (24 after merge and de-duplication in record 06 → this table merges them into 20 topic groups)

> Sources: `docs/评审记录/20261112-06-mavis.md` (merging the three reviews' findings, 24 items:
> P0×2 / P1×13 / P2×8 / P3×1) + the concurrent session's `20261112-05-mavis.md` (O-1~O-15).
> Adopted: **all accepted**, no "not accepted" items; P3 wording / line-number items were updated
> where convenient.
> **Record 06's conclusion is "not passed (this round does not meet the conditions to freeze
> S0)"**; after the dispositions in this document, the freeze is signed again per §16.3's three
> prerequisites.

| # | Finding (after merge) | Source | Severity | Disposition (v3.2) | Where it landed |
|---|---|---|---|---|---|
| M01 | `run_command` has no `command_id`; the execution-fact carrier was chosen wrongly | S1 / D1 / G16 | **P0** | **accepted**: the command fact = `command_ledger` + `run_command`, split between the two tables | §5.3 / §7 / §15-13 |
| M02 | §5.8's "crash before T1 ⇒ just re-run" would redo the physical action | G1 | **P0** | **accepted**: reconcile first, forbid re-running, isolate the run | §5.8 |
| M03 | the flow-run / shadow-run execution paths were missed | S2 / G13 | P1 | **accepted**: §16.1 inventory + §13.2-7/8, take flow-run offline inside S0 | §16.1 / §13.2 |
| M04 | the completion handler cannot get the round `visit`; two injection points would fire twice | S3 | P1 | **accepted**: a single injection point + an explicit execution context + round propagation | §5.7 |
| M05 | a T1 failure has no channel to escalate to manual; a restart continuation would redo the device action | S4 | P1 | **accepted**: quarantined + experiment hold + the resume precondition | §5.2b / §5.8 / §8 |
| M06 | the "scheduler dependency check" does not exist | S5 | P1 | **accepted**: changed to the dispatch-gate predicate (GraphRunner / executeAll) | §5.2b / §5.7 |
| M07 | the recovery state machine only had `FAILED→PENDING`; no human loop / no CANCELLED / events had no operator | G6 | P1 | **accepted**: six states + the action table + retry/reconcile APIs + `operator_user_id` | §5.8 / §7 / §8 |
| M08 | concurrency conflicts had no rules and `state_version` was added but unused | G7 | P1 | **accepted**: `FOR UPDATE` + CAS + `expectedVersion` / 409 + conflict arbitration | §5.6 / §8 |
| M09 | the run-level snapshot had no carrier and `run.workflow_ver` was never written | S11 / G2 | P1 | **accepted**: `run_node.input.material_effect_snapshot` + S1 writes `workflow_ver` | §5.3 / §10 / §12 |
| M10 | O-2 was missing the `run_node↔run` and the same-tenant-same-run effect constraints | D2 / G4 | P1 | **accepted**: completed to 8 constraints (including the three-column FK + the NULL CHECK) | §7⑧ |
| M11 | §10's old-data "all NULL = unknown" contradicted the `NOT NULL DEFAULT` (`seal_state`) | D3 / G10 | P1 | **accepted**: `seal_state` becomes nullable; the old-data semantics are written out column by column | §7② / §10 |
| M12 | §17 Q1's "barcode is nullable / changeable" is the opposite of the DDL | D4 / G9 | P1 | **accepted**: corrected to NOT NULL + unique + `AUTO-` is not an identity | §17 |
| M13 | §17 Q2's state sources were incomplete (`position_status` / `location_id` etc.) | S8 / D5 | P1 | **accepted**: the §4.2 state inventory table + Q2 re-answered | §4.2 / §17 |
| M14 | the string `execution_id` projection vs the structured key | G19 / v2.2 | P2 | **accepted v2.2**: use a structured unique key, no string column | §5.5 / §7⑤ |
| M15 | `RECONCILIATION` events faked plate state transitions | G6 / v2.2 | P2 | **accepted v2.2**: the action table; the event records only real changes | §5.8 / §7⑥⑦ |
| M16 | attribution had two sources of truth (`current_run_id` vs `occupied_by_run`) | G5 / v2.2 | P2 | **accepted v2.2**: do not add `current_run_id` / `origin_*` | §4.5 / §7② |
| M17 | the `consumable` table was "not created this round" but already exists | D7 / G24 | P2 | **accepted**: changed to "not refactored, already exists" | §4.4 / §14 |
| M18 | `workflow_node.material_effect` was absent from §7/S1 | G12 | P2 | **accepted**: added to §7③ and S1 | §7 / §12 |
| M19 | the round-row SQL does not filter `loop_index` (new writes must not copy it) | S12 | P2 | **accepted**: writes must carry `loop_index=0` / an exact round | §5.5 |
| M20 | historical findings 04 O-1/O-2/O-6 and 02 R4/R6/R9/R13 were "partly closed but marked fixed" | design | P1 | **accepted**: this round completes the landing of O-1 / O-2 / O-6 / R6 / R9; R4 / R13 are stated as gaps / boundaries in §13.2 | §5.8 / §7 / §13.2 |

**Disposition of the concurrent 05 review's (MiniMax) O-1 ~ O-5**: O-1 (the anchor is unreachable
at the completion point) ⇒ §5.7 single injection point + explicit execution context; O-2 (unstable
across a continuation) ⇒ §5.5 adds the "restore the visit starting point on continuation"
requirement; O-3/O-4/O-5 (whether T1 includes node completion, the §5.8 most-dangerous window
judged re-runnable, the §3/Q1 wording) ⇒ they land in §5.2b / §5.8 / §3 / §17 Q1 respectively.
**These are the same kind of issue as record 06's M01 / M02; they are disposed of together and not
listed as separate items.**

**Residual absorption from v2.2 (added by this session)**: after comparing item by item against
`docs/物料身份与状态-设计-v2.md` (the v2.2 synthesis draft), **2 blocks not yet absorbed by v3.2** were found and
inserted in place, marked 「v3.2 addition · absorbs v2.2」:
① **the four evidence levels** (v2.2 §2.6) ⇒ added to **§5.3** —— the draft only used `acceptedEvidenceLevels`
in the §6.1 contract but never defined the level semantics (`device_ack` / `physical_confirmed` /
`manual_confirmed` / `legacy`), which is a **specification gap**;
② **the delivery verification levels** (v2.2 §11.5) ⇒ added to **§16.4** —— including the boundary that
「passing with a simulated driver ≠ real-machine verification; the real machine needs a separate HIL run」.
The rest of v2.2 (structured idempotence key, action table, attribution without `current_run_id`) has already
been disposed of in M14 / M15 / M16, so nothing is missing this round.

### 18.2 Decision-number register (D401-D420; merged into §7 of `docs/评审规范.md` after S0 is frozen)

| Number | Decision | Where in this document |
|---|---|---|
| D401 | The domain layering and the first-phase landing boundary (Material / Container; `plate` is not renamed) | §4.1 / §4.2 |
| D402 | The three attribution concepts: no new columns; current attribution reads `position_state`, source / history read the event stream | §4.5 |
| D403 | The plate-level process stage dictionary: tenant level + template set + deactivate-not-delete + composite foreign key | §4.3 |
| D404 | The two-level failure semantics: T1 isolates, T2 uses the dispatch gate (no run=failed) | §5.2b |
| D405 | The execution fact = `command_ledger` (pre-dispatch) + `run_command` (post-response) | §5.3 |
| D406 | The idempotence key = five structured columns; `attempt` not in the key; the round comes from the execution context | §5.5 |
| D407 | The completion handler `NodeCompletionHandler`: the single injection point and CONTINUE / HOLD / throw | §5.7 |
| D408 | The six-state effect state machine + the action-table audit + the human-loop APIs and permissions | §5.8 / §7 / §8 |
| D409 | The dispatch gate: only `not_required` / `applied` pass; a loop aggregates every round; GraphRunner never touches the DB | §5.2b |
| D410 | The declaration column `workflow_node.material_effect` + the run-start snapshot | §5.3 / §7 |
| D411 | The old-data semantics column by column (NULL = unknown; no default value pretending to be known) | §10 |
| D412 | The cross-tenant / referential-integrity constraints (the **14** of §7⑧ = 4 unique keys + 9 foreign keys including the three-column FK + 1 NULL CHECK; 8 in the fifth round → 12 in the sixth round → 2 more added by the identity binding) + the data precheck | §7⑧ |
| D413 | The execution-path inventory and the S0 disposition of the flow-run / shadow-run gaps | §16.1 / §13.2 |
| D414 | The four-state execution confirmation and the all-node physical-uncertainty replay guard | §5.2c / §7④ / §8 |
| D415 | The complete event snapshots, the version interval, the Effect/Event uniqueness and the baseline replay | §5.4 / §7⑦ / T29/T34 |
| D416 | The PENDING Worker, SKIP LOCKED, the bounded backoff and the retry cap | §5.8 / §7⑤ / T30/T31 |
| D417 | The loop aligns the Effects per the declaration snapshot; REJECTED maps one-to-one onto rejected | §5.2b / §5.9 / §13.2 |
| D418 | The target supplementary evidence, the AUTO reserved namespace and reconciling an identity conflict first | §6.3 / §8 / §17 Q1 |
| D419 | The per-round parameter binding of a sub-process loop, the execution call path and the effect idempotence | §5.5 / §6.2a / T37 |
| D420 | The mid-run scanRef late binding, the controlled one-off AUTO binding and the separate identity audit | §6.3a / §7⑦a / §8 / T38 |

### 18.3 The validation / regression items added this round (mandatory for S1 / S2)

1. run the current merged DDL for real: the six effect states, the seven sync statuses, the four
   execution confirmations and the four evidence levels — the reverse tests of every value and of
   the illegal values;
2. the full reverse test of the run_node physical-completion consistency, the Effect target /
   version baseline, and the Event changed_fields / version-step / source CHECKs;
3. the Effect→Event uniqueness, the same-plate version uniqueness, the **14** constraints of §7⑧,
   and the rejection of a cross-tenant action and of an identity binding;
4. the double Worker SKIP LOCKED, the lock release on a crash, the backoff and the FAILED over the
   cap;
5. the per-round parameter consistency of a sub-process, the call-path anti-collision, a missing
   expected effect in a loop, the REJECTED gate and the unrelated branches continuing;
6. the three conclusions of a reconciliation, the target supplementary evidence, the explicit
   rebase/cancel and the ordinary-resume guard;
7. the baseline replay after the projection is deleted, and the NULL / not-modified / explicitly
   changed-to-unknown;
8. the dependency order of the mid-run scanRef, the atomicity of the AUTO binding, the
   duplicate / conflict / no-match cases and the compensation not re-scanning;
9. the flow-run disposition and the real PostgreSQL fault injection of the **38** failure
   scenarios.

---

## 19. The disposition summary of the sixth independent review (v3.3)

### 19.1 The sixth round has two **mutually independent** review materials

| # | Source | Shape | Coverage | Conclusion |
|---|---|---|---|---|
| **R-A** | this session's `docs/评审记录/20261112-07-mavis.md` (MiniMax subagents ×3: source facts / a real DDL run and the cross-tenant checks / design consistency and disposition closure) | **a line-by-line source comparison + read-only production queries + a real run in an isolated schema** (§7's DDL extracted as written, sha256 identical across two extractions, 27 statements, 11 constraints tested one by one in reverse, an enum regression over every value) | v3.2 + this session's increment | 0 P0 / **2 P1** / 4 P2 / 3 P3 (reviewer A) + 0 P0 / **2 P1** / 3 P2 / 5 P3 (reviewer B) |
| **R-B** | the user-supplied sixth-round supplementary architecture review attachment "我认真看完了这份 v2.1。我的总体判断" (an external attachment, not filed in the repository) | ⚠ **a documentation-only review**: the reviewer states that it is "**not** a source re-check completed after a fresh checkout — the content about existing method calls, field types, DDL constraints and execution paths still has to be verified against the source in S0" | v2.1 → the adjudications are applied on top of v3.2 | it raised **N1–N8**, eight adjudications → v3.3 |

⇒ **The two materials complement each other**: R-B fills the **architectural completeness** (the
execution confirmation, the event replay, the Worker), and R-A fills the **"a promise on paper vs
a carrier in the database"**.
**But merging is not the same as the verification being complete**: the DDL that the current draft
adds / changes still has to be re-run per §16.3.

### 19.2 The 8 adjudications of R-B cross-checked against the 06 record

`N1` the execution confirmation is separated from the material sync → §5.2c / §7④ / §8;
`N2` the complete before/after + `changed_fields` + `from/to_state_version`, replayable from a known
baseline → §5.4 / §7⑦ / T29/T34;
`N3` the Worker only handles PENDING, with the row lock, the backoff and the cap → §5.8 / §7⑤ / T30/T31;
`N4` the loop aligns the declaration snapshot with the actual Effects, and a missing one blocks as
well → §5.2b / §13.2-13;
`N5` REJECTED maps one-to-one onto the node `rejected` → §5.9 / §7④ / §13.2-14;
`N6` a reconciliation: completed→PENDING, not_completed→CANCELLED, still_unknown stays → §5.8 / §8;
`N7` AUTO is a reserved coding namespace and does not drive a material migration → §6.3 / §17 Q1;
`N8` a dedicated declaration column, and an identity conflict is reconciled first → §6.1 / §6.3.

**Explicitly not rolled back**: the declaration is not put back into params; the device ID is not
trusted outright on an identity conflict; a reconciliation does not go directly to APPLIED; no
persistent PROCESSING is added; no generic Execution Fact platform and no global generic material
state machine is built.

**The cross-check conclusion of the 06 record's M1–M24**: every acceptance of v3.2 is kept. v3.3
further corrects only two closure points: the reconciliation of M7/M8 no longer goes directly to
APPLIED but returns to PENDING and goes through T2; and the "the declaration conflicts with the
device identity" of M18 is changed from REJECTED to NEEDS_RECONCILIATION — only a deterministic
declaration / rule error is REJECTED. The remaining P0/P1 (the command ledger, the T1 isolation, the
dispatch gate, the snapshot, the composite constraints, the old data, the execution paths) are not
weakened.

⚠ **The two holes that R-B's landing left behind — "said but the DDL does not have"** (caught by
R-A and now fixed):
① §0.4's N1 says "add a new `execution_resolution_status` (§5.2c / **§7④**)", but §7④'s DDL has
**no such column at all** ⇒ §5.2c's four states and the continuation guard have **no carrier in
the database**;
② §0.4's N2 says "the event keeps the complete before/after + `changed_fields` + `state_version`
(**§7⑦**)", but §7⑦'s event table **only has the four from/to columns and not the latter two**
⇒ "not modified" and "explicitly changed to unknown" **cannot be told apart** in the database
(both are NULL).
⇒ **N1a / N1b** have been filled in (§7④ / §7⑦), together with the two CHECKs
`ck_run_node_exec_resolution` and `ck_event_changed_fields` and the `ix_run_node_exec_resolution`
partial index.

### 19.3 The disposition of R-A's 19 findings (after merge and de-duplication)

| # | Finding | Severity | Disposition (v3.3) | Where it landed |
|---|---|---|---|---|
| **N01** | **a round row is not an immutable fact** (a later write overwrites an earlier one) | **P1** | **accepted and the argument rewritten**: §5.5 now has the complete derivation chain (4 pieces of evidence) ⇒ **the option (b) of §13.2-13 is rejected outright**; and the new known gap "the historical round rows are untrustworthy" is added | §5.5 / §13.2-15 |
| **N02** | **the evidence priority of §6.3 misses the 5th source** `position_status` | **P1** | **accepted**: the 5th level + the hard rules are added (`UNKNOWN`/`LOST` ⇒ the identity may not be resolved from the levels 3/4 ⇒ `NEEDS_RECONCILIATION`; `IN_TRANSFER` ⇒ the level 3 is unavailable), and the "parallel" relation with §11.2 T10 is drawn | §6.3 / §13.2-16 |
| **N03** | **the audit table → the effect table is a single-column FK, so a cross-tenant audit row can be written** | **P1** | **accepted and verified by a real run**: the composite FK `(tenant_id, effect_id)` (since the referenced side's unique key is only created in §7⑧ ⇒ the order cannot be reversed, and the FK is added with ALTER in §7⑧); **the measured cross-tenant write is rejected** | §7⑥ / §7⑧ / §13.2-17 |
| **N04** | **the partial index misses `cancelled` ⇒ the gate query degrades into a full table scan** | **P1** | **accepted and verified by a real run**: the index's WHERE is completed to the 5 blocking values; **the measured gate query is back to an `Index Scan`**; and the invariant "every value set a guard queries must have a partial index that covers it" is established | §7④ / §13.2-18 |
| **N05** | §16.3 does not name the gaps that block the freeze | P2 | **accepted**: it is now expanded to **5** prerequisites, naming the sixth-round disposition, the final DDL, the continuation guard and the key concurrency verification; §13.2-13/14 are marked as design-closed and S2-verified | §16.3 |
| **N06** | §5.5's wording "the existing per-node update / query SQL" is too broad, and it misses `selectCompletedNodeKeys` | P2 | **accepted**: changed to "the following **5**" + `selectCompletedNodeKeys` added + the point made that the database already has a correct style that can be copied | §5.5 |
| **N07** | the constraint-counting rule was wrong (it said "8", the real number was 11 → now 12) | P3 | **accepted**: the whole document is unified on "§7⑧ **12** = 4 unique keys + 7 foreign keys + 1 NULL CHECK", and a counting-rule note is added | §7⑧ / §0.3 / §16 / §17 Q9 / §18.2 D412 / §12 S1 |
| **N08** | the pending index sweeps the terminal states in, and an ordinary Worker must not scan the manual states | P3 | **accepted and tightened**: `ix_plate_material_effect_worker` is PENDING only; FAILED / NEEDS_RECONCILIATION / REJECTED go into a separate attention index and are not unconditionally picked up by an ordinary Worker | §7⑤ |
| **N09** | O-17(a) missed the **index linkage**; rolling back a CHECK enum value requires migrating the data first | P2 | **accepted**: §13.2-14 gains "the index's WHERE is synced + **rebuilt**" (PG does not follow a CHECK change automatically) + the rollback path + the `NOT VALID` / `VALIDATE` two-step recommendation | §13.2-14 / §7④ |
| **N10** | `material_sync_status` is `NOT NULL DEFAULT` ⇒ "never written" and "not needed" are indistinguishable | P2 | **accepted** (after taking (a) it is downgraded to "§5.2b must state that the node-level carrier is the effect table", which is written) | §5.2b |
| **N11** | `uk_run_node` contains `attempt` ⇒ the same round can have two rows | P2 | **accepted** (after taking (a) nothing has to be done; the hard defect of (b)'s item ③ is already explained in §13.2-13) | §13.2-13 |
| **N12** | the round columns of the two carriers have different types (`smallint` vs `int`) | P3 | **recorded**: both places in §7 say "the round"; converting across the carriers needs a cast (the type is not changed this round — the change surface outweighs the benefit) | §7⑤ / §7⑦ |
| **N13** | the 5 call sites in the 4 demos under `src/main/java` were not named | P3 | **accepted**: §5.7 gains the line numbers and the note "otherwise **the main source does not compile**" | §5.7 |
| **N14** | §3's "the execution side **only reads** `method_params`" is inaccurate | P2 | **accepted** (a leftover of the fifth round's O-13): §3 / §6.1 changed to "the execution side's **device parameter channel** only reads…" and the other params fields that are read are listed | §3 / §6.1 |
| **N15** | the `command_ledger` "key columns" list is incomplete | P3 | **accepted**: `abort_state` / `abort_detail` / `abort_at` are added, with the note that it is a **partial** list | §5.3 / §7 |
| **N16** | the scale of `TENANT=1L` was overestimated (it said boot 20+, the real numbers are boot 15 / kernel 40) | P2 | **accepted**: the three places are unified on "40 places in the kernel module, of which 15 are in the boot layer" | §3 / §13.1 / §17 Q9 |
| **N17** | the "skip" of §16.1 has two meanings that were not disambiguated | P2 | **accepted**: rows 6/7 changed to `ResumeAction.CONTINUE` / `POST …/skip-node` and `ResumeAction.RETRY` / `ResumeAction.SKIP` | §16.1 |
| **N18** | 3 line-number drifts (`93_m1_parallel.sql` / `DeviceStateMapper.xml` / `StartupRecovery.java`) | P3 | **recorded**: every content pointer lands on the right statement, only the line numbers are offset; they are uniformly corrected in this round | §3 / §5.2b |
| **N19** | the DDL verification record must be consistent with the actual version | P3 | **accepted and the version boundary kept**: the 30/30 result of the earlier intermediate snapshot is kept as historical evidence; the current merged version, after the new fields / constraints were added, is marked as to-be-run again, and the old result is not passed off as its own | §7 / §16.3 |

### 19.4 The verification conclusions of the sixth round

| Item | Result |
|---|---|
| The 24 assertions of §3's inventory | **21 match word for word**, 2 have wording that is too broad (N14 fixed), 1 has a wrong number (N16 fixed) |
| The 10 rows of §16.1's execution-path inventory | **walked one by one, all of them hold** |
| The whole causal chain of §5.2b's two-level failure semantics | **the source + the production SQL all line up** (`markRunFailed` → the resume is rejected → the incomplete nodes are deleted and re-run → the physical action is redone) |
| §5.5's timing argument (the correction of `3ce7741`) | **independently re-checked as correct** |
| §7's DDL (v3.2) | 27/27 succeeded; the 11 constraints all took effect in their reverse tests; every enum value passed; the production precheck was clean |
| §7's DDL (**the earlier, stronger-constraint v3.3 snapshot**) | the design draft previously recorded **35/35 statements succeeded** and 15 reverse tests passing; that historical result does not cover the identity-binding table, the 14 constraints of §7⑧, the call-path column width and the sub-loop / scanning contract added since then, and it cannot serve as the proof of the current version |
| §7's DDL (**the current final merged v3.3**) | ⏳ **not run for real in this round, still to be verified**; the final whole section and the atomicity of the identity binding must be re-tested in a temporary PostgreSQL and the result filled back afterwards |
| **Still unverified (must be labelled honestly)** | ① **the runtime behaviour has not been reproduced by a real run** — the P0 argument of §5.2b is currently only a **static code derivation** ⇒ S2 measures it with fault injection; ② the device's ability to give "physically confirmed" evidence stays UNKNOWN (§16.2); ③ whether the schema script's `node_status` enum really lacks `manual`/`bypassed` (§13.2-9, not compared line by line); ④ running the **idempotent** S1 migration script twice (the idempotent wrapper itself is not written yet); ⑤ the existing engine carrier of the sub-process parameter binding, the call path and the scan context is not verified |

### 19.5 The validation / regression items added this round (mandatory for S1 / S2)

1. insert **every value** of the **seven** `material_sync_status` values + the **four**
   `execution_resolution_status` values + the six effect states;
2. a `changed_fields` that is not an array is rejected by `ck_event_changed_fields` / an illegal
   `execution_resolution_status` value is rejected by `ck_run_node_exec_resolution`;
3. **a cross-tenant action write and an identity-binding write must be rejected** (the
   corresponding composite FKs) — this is a **new** regression item of this round and must be
   tested after the S1 migration;
4. the **EXPLAIN of the gate query must show an `Index Scan`** (not a `Parallel Seq Scan`) — a
   regression item on the index predicate;
5. **run the current final merged v3.3 DDL for real in a temporary database** (the whole section /
   the **14** constraints of §7⑧ / the data precheck / the forward and reverse tests of the
   identity binding);
6. the multi-round writes of a sub-process must not overwrite the evidence of the previous rounds;
   the existing SQL of the same `node_key` must carry an exact `loop_index`, and T37 verifies the
   per-round parameters and the call path at the same time;
7. the regression after §16.1's flow-run disposition (§16.1 has no remaining uncovered production
   path);
8. T38 covers the no-barcode start, the mid-run scan, the atomicity of the AUTO binding, the
   conflict / no-match cases and the compensation not re-scanning.

### 19.6 The two adjudications added by the field supplement (v3.3)

| # | The field problem | The disposition in this draft |
|---|---|---|
| F1 | a loop is usually a multi-action sub-process that changes the plate, the source position or the destination position each round — not an isolated node spinning on its own | `loop_index` is kept as the idempotence defence line; the sub-process `iterationBindings`, the unified device / material resolution, the execution call path and the immutable round snapshot are added (D419, §6.2a / T37) |
| F2 | no scan happens at the start; the plate code is obtained mid-execution and is then used downstream | a run-scoped `scanRef` is added; guessing a plate before the scan is forbidden; an existing plate resolves to the stable `plate.id`, and an AUTO placeholder plate is only bound once under controlled conditions with a separate audit; the compensation does not re-scan (D420, §6.3a / T38) |

### 19.6 The 17 findings of reviewer C (the evidence pinned to the v3.2 baseline → adjudicated one by one against v3.3)

Reviewer C delivered at 08:03 and **strictly pinned its evidence to `65fe5ef` (v3.2)**
(`git show 65fe5ef:…`, avoiding the drift of the working copy); its conclusion is **FAIL** and its
findings are **P0×0 / P1×6 / P2×7 / P3×4**. ⚠ It states itself that "v3.3 has substantially fixed
the O-13 and the O-17 of this report".
⇒ The table below adjudicates its 17 findings **one by one against the current v3.3** (the full
version is in `docs/评审记录/20261112-07-mavis.md` §5):

| C's number | Severity | Summary of the assertion | Adjudication against v3.3 | Disposition |
|---|---|---|---|---|
| P1-01 | P1 | O-13 "the execution side reads only `method_params`" is still false | ✅ fixed | closed |
| P1-02 | P1 | `REJECTED` has no value to read at the gate | ✅ fixed (seven values + the §5.9 mapping + the HOLD set) | closed |
| **P1-03** | **P1** | **`material_sync_status` has no writer / no mapping** | ⚠ **the mapping has been completed by v3.3; the writer is still missing** | ✅ **completed in this session** (the §5.4 T2 transaction chain + the new writer row in the §5.7 decision table + the multi-effect folding rule) |
| P1-04 | P1 | §5.8 lacks the two incoming edges such as `PENDING→NEEDS_RECONCILIATION` | ✅ fixed | closed |
| P1-05 | P1 | the two sets of "four-level evidence" have the same name but different meanings, and there is no persisted column | ✅ mostly fixed (v3.3 adds `physical_evidence_level` + a CHECK; §17 Q3 already separates the source from the strength) | closed |
| P1-06 | P1 | §5.7's HOLD cannot cover `REJECTED`/`CANCELLED`; §16.3 does not name O-16/O-17 | ✅ fixed | closed |
| P2-07 | P2 | §5.8 puts `state_version` side by side (that column exists only on `plate`) | ✅ fixed | closed |
| **P2-08** | P2 | **§12 S1 does not carry over "the new writes must carry `AND loop_index = 0`"** | ⚠ **it still holds** | ✅ **completed in this session** (§12 S1) |
| P2-09 | P2 | §8's resume precondition misses `REJECTED` | ✅ fixed (changed to the "APPLIED + the evidence is closed" criterion) | closed |
| P2-10 | P2 | §16.3's acceptance criterion can never be satisfied literally | ✅ fixed (the wording changed to "no uncovered production path") | closed |
| P2-11 | P2 | "8 constraints" does not match the actual 11 | ✅ fixed (unified to 12) | closed |
| P2-12 | P2 | the two interface paths of §4.5 and §8 | ✅ fixed | closed |
| **P2-13** | P2 | **§18.1 has no numbered cross-reference; G11 / the waste / the position release are not in §13.2; T20 has no mechanism** | ⚠ **T20 partially holds** | ✅ **§13.2 row 21 has been added in this session**; the numbered cross-reference is **recorded as still to be added** (P3) |
| **P3-14** | P3 | **§16.4 lacks the S0 / S5 rows** | ⚠ **it still holds** | ✅ **added in this session** |
| P3-15 | P3 | the scenario names of T19/T25 are duplicated | ✅ fixed (§11.2 has been rewritten as 36 scenarios) | closed |
| **P3-16** | P3 | **§15 and §18.2 are not a one-to-one mapping, and §15 has no D-number column** | ⚠ **it still holds** | **recorded as still to be added** (P3, it does not block S0) |
| **P3-17** | P3 | **§5.7's signature extension points miss `RunStateMachine.java:76`** | ⚠ **it still holds** | ✅ **added in this session** |

⚠ **C also caught two imprecisions in the modification made by this session itself (they hold, and
they have been changed)**: the timing argument of §5.5
① "at the T1 point the database holds **only the summary row**" is imprecise — in round 2 the round
row of round 1 **has already landed**
(`GraphRunner.java:649` → `:657`, the round row is written only **after** the previous round has
executed) ⇒ the correct wording is
⇒ "the row of **this round** has not landed yet ⇒ what is read is the value of the **previous
round** ⇒ **this round collides with the previous round on the same idempotence key**";
② an acyclic graph / sequential execution **runs only one round**, and in that case the timing
argument **does not apply** (the `loop_index=0` in the database is exactly the one correct round)
⇒ §5.5 has added the "the boundary between the two cases" table.
⇒ **This is a self-proof of this session's discipline**: the fifth round used "the ledger says it
is fixed ≠ it really is fixed" to catch the concurrent session's O-11; the sixth round applied the
same yardstick to **its own** modification ⇒ **it was changed, and not defended**.

---

*This is **v3.3 · an S0 freeze candidate**; no code and no schema change has been made yet. The
fifth independent review has two records: `docs/评审记录/20261112-05-mavis.md` (the concurrent
session · MiniMax subagent) and `20261112-06-mavis.md` (this session · DeepSeek subagents ×3 +
the summary); the sixth round likewise has two independent materials: `20261112-07-mavis.md`
(source facts / the DDL / a consistency review) and the user-supplied sixth-round supplementary
architecture review attachment "我认真看完了这份 v2.1。我的总体判断" (an external attachment, not
filed in the repository, a documentation-only review) ⇒ the disposition summary is in §19.
Review records are named per §8 of `docs/评审规范.md`; the reviewed party's response
is written into the disposition-update section of the corresponding review record.*
