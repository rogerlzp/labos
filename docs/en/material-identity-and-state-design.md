# Material Identity and State · Design (v3.2 · S0 freeze candidate)

> **Status**: **pending re-review confirmation** (v1 2026-11-11 → v2 merged three **reviews** →
> v3 merged three independent **designs** → v3.1 merged the fourth-round review O-1…O-9 →
> **v3.2 disposes of the fifth independent review**, see §18 for the disposition summary)
> **Decision number segment**: this document claims **D401-D4xx** (the register is in §18.2; to be
> merged into the segment table in §7 of `docs/评审规范.md` once S0 is frozen)
> **Related**: [Process Orchestration Capability Gaps and Landing Route](process-orchestration-gaps-and-roadmap.md)
> (the gap analysis, G1/G2/G11); the fourth-round review `docs/评审记录/20261112-04-openai.md` plus
> the attachment "Unified Architecture V1.0";
> **the fifth independent review has two independent records**:
> `docs/评审记录/20261112-05-mavis.md` (the concurrent session · MiniMax subagent) and
> `docs/评审记录/20261112-06-mavis.md` (this session · DeepSeek subagents ×3 + the summary);
> v1's three reviews `20261112-01-grok.md`, `-02-kimi.md`, `-03-openai.md`;
> the concurrent session's independent design `docs/物料身份与状态-设计-v2.md` (for comparison)
> **Code baseline**: `main` @ `05a91d7` (documentation only); the code has not changed since `0eb2a99`
> **⚠ This is still a design; no code and no schema change has been made.**
>
> **How this document came to be**: the same question produced **three mutually independent
> designs** (see §0.1). This document is their **convergence**: it keeps only the **points all
> three agree on** and the **disagreements that have been adjudicated**. The superseded drafts
> are kept for comparison and are no longer candidate baselines.
>
> **The v3.2 revision**: the fifth round was reviewed by 3 independent reviewers (source facts /
> real DDL runs / design consistency) who found **2 P0s + a batch of P1s**, all disposed of one by
> one (§18.1). The substantive additions/rewrites this round: the carrier of the execution fact
> (§5.3), the T1 failure recovery channel and quarantine (§5.2b/§5.8), the downstream dispatch
> gate (§5.2b/§5.7), the execution-path inventory and the known gaps (§16.1), the six-state effect
> state machine and the remaining O-2 cross-tenant constraints (§7), the old-data semantics (§10),
> and the ten mandatory questions re-answered (§17).

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
| D4 | The relation between the node state and the effect state | decoupled (not detailed) | decoupled (not detailed) | explicitly "the node only counts as fully complete once the effect reaches the required confirmation level" | **adopt C, and give it a landing** | Do not change the `node_status` enum; a **dispatch gate** (§5.2b, a predicate injected into GraphRunner/executeAll that reads `run_node.material_sync_status`) decides whether the downstream is dispatched; ⚠ do not use `runtime_hold` to hold nodes: its scope is actually `site | island | experiment` (no run/node granularity) |
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
| M3 | the idempotence key omits `loop_index` ⇒ **the third iteration of a loop hits the unique index** and breaks the process semantics outright | §5.5 |
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
| R6 | **The O-2 cross-tenant constraints are completed to 6 kinds / 8 constraints** (`run_node↔run`, effect→run, event→run, effect→plate, event→plate, the three-column FK event→same-tenant-same-run + a NULL CHECK) | the earlier draft only gave 3 groups, so O-2 was not closed; after adding `uq_run_tenant_id` in a real run they can all be created (§7⑧) |

**The adjudications of the disagreements with the concurrent independent design v2.2 (v3.2 decides each)**:

| # | v2.2's claim | v3.2 adjudication |
|---|---|---|
| R7 | v3 §5.8's "crash before T1 ⇒ just re-run" should be rejected | **adopt v2.2**: reconcile first, forbid re-running, isolate (§5.8) |
| R8 | do not persist a string `execution_id`; use a structured unique key | **adopt v2.2** (§5.5 / §7⑤) |
| R9 | the recovery audit uses an append-only effect action, not a `RECONCILIATION` event | **adopt v2.2** (§5.8 / §7⑥⑦) |
| R10 | do not add `current_run_id` / `origin_*` | **adopt v2.2** (§4.5 / §7②) |
| R11 | acyclic graphs use `loopIndex=0`, cyclic ones use `visit`; the gate only lets `not_required/applied` through | **adopt v2.2's executable rule** (§5.2b / §5.5 / §5.7) |
| R12 | the empty reservation for multiple inputs/outputs is an empty promise | **partly adopted**: contract v1 this round only opens 1..N effects on the `outputs` side; inputs stay one-to-one; `operations` is a placeholder only and preflight rejects unknown values |
---

## 1. The problem: questions the site asks that the system cannot answer

| Question | Today's answer |
|---|---|
| Which step of the process is this plate at? | ❌ cannot be answered. `plate` has no plate-level process stage |
| Who produced this plate, and who processed it last? | ❌ cannot be answered. `plate` has no production-source column and `plate_material_event` does not exist yet |
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
> fifth review; the "v3.2 add" rows are facts added / corrected this round.
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
| `TENANT = 1L` is **hard-coded**; not only in `WorkflowWebService` but in **20+ places** in the boot layer (multi-tenant isolation is even less implemented) | `WorkflowWebService.java:48`; `ApiServer.java:93`, `RunService.java:68` etc. |
| the execution side only reads `workflow_node.params->'method_params'` and parses it with a **flat JSON parser** (nested structures get misparsed / passed through) | `WorkflowMapper.xml:70-75`, `WorkflowService.java:1418-1491` |
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

**The definition of "how many empty plates are left"**: decided by `plate_well` (no well rows,
or all wells with `status='empty'`); **no separate plate-level `empty` state** (otherwise there
are two sources of truth).

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
⇒ **The `consumable` table is not created in this round** (v1 created it) — the reviews agree
it needs its own position and attachment-history model, and **freezing an incomplete structure
is the wrong move** ⇒ the whole table is deferred to S5 (§14).

### 4.5 Attribution: three concepts, never mixed

| Concept | Where it lands | Meaning | Overwritten? |
|---|---|---|---|
| **Current attribution** | `position_state.occupied_by_run` | who manages it now | **yes** |
| **Production source** | `plate.origin_run_id` / `origin_node_key` | who produced it in the first place | **no** |
| **Processing history** | `plate_material_event` | one row per change | append only |

**Why they must be split**: after a plate has been processed by A (amplification), B (purification)
and C (detection) in that order, the system must be able to answer "who produced it (A)", "who
processed it last (C)" and "who owns it now". With one set of `owner_*` columns every migration
overwrites the previous value ⇒ **the production relation is lost for good**.

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

### 5.2b The two-level failure semantics (v3.2 rewrite · landing the "blocking" on existing mechanisms)

**The failure consequences of T1 and T2 are completely different**; v3.2 rewrote the definitions
and the landing channels per the source evidence of the fifth review:

| Stage | Failing means | Consequence (v3.2 correction) | Landing channel (existing mechanism / precedent) |
|---|---|---|---|
| **T1** (the material effect intent did not land as `PENDING`) | the **command fact** of the device is usually already in `command_ledger` / `run_command` (§5.3); what is missing is **the material ledger and the basis for compensation**. Only in the extreme case where the database as a whole is unavailable does the physical action really have "no record" | **isolate the run (quarantined) + raise an experiment-level `runtime_hold`** and wait for a human reconciliation; **not "run failed"**; **resuming and re-dispatching device commands is forbidden while isolated** | reuse the existing precedent of `StartupRecovery.quarantineUncertainRuns` (`StartupRecovery.java:130-166`, human confirmation releases at `:171+`) |
| **T2** (applying the state projection) | the physical action happened and is recorded, only the **projection** did not catch up | **the node still counts as completed** (the device really did finish), but **the material is marked as awaiting reconciliation** and **the downstream that depends on it is not dispatched** | `run_node.material_sync_status` + the **dispatch gate** (see below); the effect state is `NEEDS_RECONCILIATION` |

**Why "just set run=failed" is not enough (the fifth review's source conclusion)**: `markRunFailed`
does not write `pause_kind` / `recoverable` ⇒ `resume` is rejected and there is no window to
escalate to manual; and when the database as a whole is unavailable `markFailed` cannot be
written either, so after a restart `StartupRecovery` settles the run as `failed + recoverable`,
and a human CONTINUE goes through `resumeAsync` which **deletes the incomplete nodes and reruns**
⇒ it would **do the already-completed physical action once more**.
⇒ A T1 failure must **isolate**; the recovery entry point must first reconcile against
`command_ledger` / `run_command` (§5.3/§5.8).

**The mechanism for the downstream "not dispatched" (v3.2 correction: not a "scheduler dependency check")**:
1. the node-level fact is recorded in `run_node.material_sync_status` (`not_required / pending /
   applied / failed / needs_reconciliation / cancelled`);
2. **the dispatch gate = a predicate injected into `GraphRunner` / `RunStateMachine.executeAll`**:
   before a node is activated and about to execute, check the `material_sync_status` of **all its
   direct predecessor nodes** — only `not_required` / `applied` pass; `pending` / `failed` /
   `needs_reconciliation` / `cancelled` all block; **branches with no dependency relation carry on
   as usual**; a loop node must aggregate the actual status of its own rounds (`loop_index=1..N`),
   and if any round is not closed it is blocked (§5.5);
3. when the gate trips ⇒ that node **is not started**, the run lands in `paused` +
   `pause_kind=material_sync` (**a new enum value**, see §12), and an experiment-level
   `runtime_hold` alarm is recorded; **after reconciliation / human confirmation, `POST
   /api/runs/{id}/resume` (continue) wakes it up** — the existing continuation entry point is
   reused, no separate wake-up mechanism is invented;
4. **why not put it in `ExperimentScheduler`**: it only reaches workflow-step granularity
   (`ExperimentExecutor.expand`) and cannot govern a workflow_node; node dispatch today happens in
   `GraphRunner`'s in-memory ready queue;
5. `GraphRunner` still **does not access the database directly**: the gate predicate is injected by
   `MaterialEffectService` in the boot layer (the same pattern as the existing `withPauseChecker`).

⇒ **No new `node_status` enum value is introduced**: "the device finished but the material is not
synced" is expressed by `material_sync_status`, and "the successor must not run" is expressed by
the dispatch gate — the two are kept apart.
⇒ The site sees "the device finished, but this batch of material has not been reconciled and the
work behind it was not dispatched" — **not** a fake device failure.

### 5.3 T1: execution fact confirmation (v3.2 rewrite: the command fact = `command_ledger` + `run_command`)

**First, make clear where "the execution fact" actually lands (the fifth review's P0 correction)**:

| Fact | Carrier | Timing | Explanation |
|---|---|---|---|
| command **dispatched, result unknown** | **`command_ledger`** | **before** dispatch | `command_id varchar(160)`, `idempotency_key`, `state` (`DISPATCH_STARTED / ACKED / FAILED / UNKNOWN`), `resolved_at/by`; a Java `CommandLedger` implementation already exists |
| command **response result** | **`run_command`** | **after** the response | `device_raw_response` / `status` / `attempt` / `sent_at` / `finished_at`; **it has no `command_id` column** (the earlier draft was wrong); `ack_at` has the column but no writer |
| node physical completion | the new `run_node.physical_completed_at` | when the node completes | it only means the physical action finished |
| material effect intent | the new `plate_material_effect` (`PENDING`) | after the response, before the node is marked completed | the "material ledger" part of T1; every action appends a `plate_material_effect_action` |

⇒ **T1 = persisting the material effect intent** (not creating the execution fact from scratch):
the command fact is usually already in the command ledger; on a T1 failure **isolate the run**
(§5.2b), and on recovery **reconcile first**: `ACKED` ⇒ the physical action happened ⇒ the effect
may be rebuilt from the declaration snapshot; `UNKNOWN / DISPATCH_STARTED` ⇒ human check;
**re-running the device action directly is forbidden**.

**The declaration snapshot (v3.2 gives it a carrier, no new table)**:
- **at run start, snapshot every node's `materialEffect` into the run context
  `run_node.input.material_effect_snapshot`** (including `mode / target / set /
  acceptedEvidenceLevels` and a hash), and **actually write `run.workflow_ver`** (an existing
  column that has never been written);
- the effect record keeps another copy of the `declaration` snapshot; continuation / compensation
  **read only the snapshot**, never the newest design (otherwise an old physical action would be
  interpreted by a new declaration);
- this round only guarantees **the material declaration snapshot**; it does not claim to also
  solve the versioning of the whole BPMN graph (the v2.2 wording).

**Evidence levels (v3.2 addition · absorbs v2.2 §2.6)**: the contract in §6.1 uses `acceptedEvidenceLevels`,
but until now **only the field name appeared and the level semantics were never defined** ⇒ filled in here,
four levels:

| Level | Meaning | Boundary |
|---|---|---|
| `device_ack` | the device or driver reports success | only proves that the execution chain got a success response; **it does not pretend to be an independent physical confirmation** |
| `physical_confirmed` | a final device-state query, a sensor or other trustworthy physical feedback confirms it | real-machine evidence |
| `manual_confirmed` | an authorised person verified it on site | **must** record the operator, the time and the reason |
| `legacy` | only for compatibility with the old `status=completed` data | **does not represent a new evidence capability** |

- an ordinary method accepts `device_ack` by default; a high-risk method may, in its metadata, accept only
  `physical_confirmed` / `manual_confirmed`; **the evidence policy is snapshotted together with the declaration**
  (as in the previous paragraph);
- **the declaration snapshot answers what we intend to do and is not physical evidence** —— the snapshot
  must not be treated as confirmation;
- the UI, the events and the acceptance reports must keep the **actual level**; they must not uniformly say
  physically verified, nor treat a manual confirmation as an automatic device confirmation.

**What the effect record must hold** (not just an `applied` flag on the audit table):

| Field | Why it must be there |
|---|---|
| declaration snapshot (`declaration` JSONB) | if the declaration changes while the run is going, the continuation / compensation must interpret the old physical action by the **declaration of that time** |
| **the actually resolved `plate_id`** | the compensation replays by it and **does not re-run `target.by=position`** (the plate may have moved) |
| idempotence key + `payload_hash` | same key + same payload may be skipped safely; **same key + different payload must raise an error** |
| `source_run_id` / `source_node_key` / `source_loop_index` / `source_attempt` / `effect_index` | attribution and round; `attempt` is only recorded, it does not go into the idempotence key |
| **every action appends a `plate_material_effect_action`** | declare / apply / reconcile / retry / cancel all need an immutable audit (v2.2 §4.6, adopted by v3.2) |

### 5.4 T2: the material state projection

**In one transaction**: `lock the plate → verify the expected version → apply the transition →
write the audit event → append the effect action → mark APPLIED`.

| Failure case | Behaviour |
|---|---|
| the migration fails (a business rule does not hold) | the effect record goes `REJECTED` + an error code, **an alarm and an escalation to manual**; **never silently swallowed**, no automatic retry |
| the plate is not found / the attribution does not match / the identity evidence conflicts | **isolate and escalate to manual, do not guess** (§6.3) ⇒ `NEEDS_RECONCILIATION` |
| database / concurrency failure | the effect record goes `FAILED` + an error code, **a controlled retry is allowed** (§5.8) |
| a manual inventory and an automatic migration collide | arbitrated per §5.6; **a silent "last writer wins" is forbidden** |
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

**Why the anchor changed** (adjudication D1): v2 used `run+node+loop+plate`, which has two holes —
① a node that **produces several effects** (aliquoting, mixing) has no distinguishing slot;
② "the plate" is not the natural unit of an effect (one effect may involve several materials).

⇒ `effect_index` is the **effect ordinal within one execution** (a node declaring three effects
has 0/1/2), and how many plates an effect touches is written inside its own declaration ⇒ **the
idempotence key is decoupled from the plate**.

| Design point | Reason |
|---|---|
| **`source_loop_index` goes into the idempotence key** | the same node running three times in a loop is **three legitimate effects** and must not be eaten by the key |
| **`attempt` does not go into the idempotence key** | a device / node **retry** is the Nth attempt of the same logical execution ⇒ it must not produce a new material transition (`attempt` only goes into the action audit) |
| **same key + same payload may be skipped; same key + different payload must raise an error** | silently swallowing "the design changed" is the most dangerous failure mode |
| **`payload_hash` must be stored** | it is the basis for deciding "same key, same payload?" |

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

⇒ **reading the round back from the database cannot work — this is a TIMING problem, not a
"semantic inconsistency"** (v3.2 addition · the 05 review's O-11 corrected): at the T1 point the
database only holds the summary row (`loop_index=0`) ⇒ if T1 took the round from
`run_node.loop_index`, rounds **2/3 would both read `0` ⇒ collide with round 1 on the same
idempotence key ⇒ the effect would be silently swallowed** ⇒ the round of the idempotence key
**can only come from the execution context** (graph path = the in-memory `visit`; sequential /
acyclic = 0, propagated per §5.7).

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

⚠ **Cross-continuation (v3.2 addition · the concurrent 05 review's O-2)**: on a continuation
`GraphRunner`'s in-memory `visits` starts from zero; if a node has already run N-1 rounds, its Nth
round gets `visit=1` again ⇒ **it collides with an already-applied effect on the idempotence key**.
⇒ S2 implementation requirement: before a continuation starts, **restore each node's `visit`
starting point from its `run_node` round rows (or the effect records)** (`MAX(source_loop_index) +
1`) and initialise `visits` from that point; **do not restart counting from 1**.

⚠ **v3.2 addition (the fifth review's S12)**: the existing per-node update / query SQL
(`updateNodeStateLive` / `updateNodeResult` / `markBypassed` / `selectTopNodeKeyIds` / the
resume-path `deleteIncompleteTopNodes`) **only filters `level=0` and does not filter
`loop_index`** ⇒ it will hit the round rows. ⇒ the new writes in S1/S2 must explicitly carry
`AND loop_index = 0` (or an exact round condition) and **must not copy the existing style**.

⚠ **v1's `(owner_run_id, owner_node_key, plate_id)` makes the third loop iteration hit the
unique index** — that is a hard defect (fixed in v2).

### 5.6 Concurrency: per-plate serialisation and conflict arbitration (v3.2 adds the rules)

A unique index only prevents "the same idempotence key twice"; it does **not** prevent two
different nodes (or a human) from changing the same plate at the same time.

| Scenario | Rule (v3.2) |
|---|---|
| two automatic migrations modify the same plate | inside T2 take `SELECT ... FOR UPDATE` on the plate + a **`state_version` CAS** (read, verify, write in one transaction; a version mismatch ⇒ `FAILED` + a controlled retry, no overwrite) |
| the manual state-change API | must carry `expectedVersion`; a version mismatch ⇒ **HTTP 409** + the current value / version, no silent overwrite |
| a manual inventory vs an automatic migration at the same time | **manual wins**: the automatic migration sees the new version ⇒ goes back to `PENDING` awaiting replay (it writes no value); the manual operation appends a `MANUAL_INVENTORY / MANUAL_CORRECTION` event and sets that plate's pending effects to `CANCELLED` (reason recorded) |
| audit consistency | `from_*` must equal **what this transaction read** (otherwise "both audit rows look successful while the later commit overwrote the earlier one") |
| conflict visibility | a conflict always produces a `plate_material_effect_action` (`REBASE / CANCEL`) + an alarm; **a silent "last writer wins" is forbidden** |

⚠ The existing `PositionLock` solves **position mutual exclusion**; that is **not** the same as
concurrent updates of the material state being solved.

### 5.7 The completion handler: the single injection point and loop-round propagation (v3.2 rewrite)

**The conclusion first**: material effect processing has **only one injection point** — the shared
`RunStateMachine` instance (`WorkflowService` constructs one `sm` used by both graph execution and
sequential execution), **not** one hook in GraphRunner and one in WorkflowService (that would make
the graph path fire twice). The real mechanism is a **completion handler** `NodeCompletionHandler`
(not a listener), and its return value decides what happens next:

| Return | Meaning | What the caller does |
|---|---|---|
| `CONTINUE` | the current node has no effect, or the effect is already `APPLIED` | the node may complete; successors enter the dispatch-gate check |
| `HOLD` | the effect is `PENDING / FAILED / NEEDS_RECONCILIATION` (physical work done but the material not closed) | the node is still marked completed (no fake device failure), **successors are blocked by the gate**, the run enters `paused(pause_kind=material_sync)` |
| throws a persistence exception | T1: even the effect intent / the physical completion fact cannot be written | abort the current run immediately ⇒ **isolate** (§5.2b), **no continuation and no replay of the device action** |

| Decision point | v3.2 conclusion |
|---|---|
| injection point | `RunStateMachine.executeNode`: called after the device execution succeeds and **before** `np.setStatus(COMPLETED)` / `emit(NODE_COMPLETED)` (constructor injection, replaceable in tests) |
| completion context | explicitly carries `runId / runNodeId / nodeKey / loopIndex / effectIndex / command evidence`; **the handler must not query back "the latest round"** |
| where the round comes from | **the graph path passes the in-memory `visit`** (1..N); **sequential execution and acyclic graphs pass 0**; the signature extension points: `GraphRunner.java:649`, `WorkflowService.java:1057/1065`, `RealWorkflowRunner.java:183` plus tests / demo (changed once in S2, see §12) |
| coverage | only **device TASK nodes**; BUILTIN nodes do not go through `executeNode` ⇒ **a BUILTIN node declaring `materialEffect` is an ERROR at preflight** (§6.4); `POST /api/experiments/flow-run` and shadow runs are known gaps (§16.1) |
| failure semantics | the handler handles T2-class failures internally (swallow the exception but leave `PENDING / FAILED` + an error code); **only T1 is re-thrown**, caught by `WorkflowService`'s catch which isolates the run |
| `effect_index` allocation | assigned `0..n-1` in `outputs` array order within one notification; **a replay / retry reuses the same `effect_index` and must not renumber** |
| difference from ExecutionListener | a listener is a side-effect outlet whose failure must not affect execution; the completion handler **must be able to reflect a failure** ⇒ opposite semantics, **never merged**; `ExecutionListener` is left as it is |
| event persistence | `ExecutionListener` events are buffered and may be lost on the failure path ⇒ they **cannot carry** "an intent that must be left for compensation"; the completion handler writes the action table itself |

⇒ **What is left as it is**: `ExecutionListener` untouched (the side-effect outlet); `GraphRunner`
still never touches the database; both production paths (graph / sequential) go through the same
`sm`.

### 5.8 Recovery: the state machine, the action audit and the human loop (v3.2 rewrite)

**The effect state machine (6 states; v3.2 adds `CANCELLED`)**:

| Current | Trigger | Next | Who | What is recorded |
|---|---|---|---|---|
| `PENDING` | T2 succeeds | `APPLIED` | AUTO | action `APPLY` + `plate_material_event` (a real plate change) |
| `PENDING` | a business rule does not hold | `REJECTED` | AUTO | action `APPLY` (the failure is recorded) |
| `PENDING` | database / concurrency failure | `FAILED` | AUTO | action `APPLY` + `error_code` |
| `PENDING` | the run terminates / is cancelled | `CANCELLED` | AUTO | action `CANCEL` |
| `FAILED` | controlled retry | `PENDING` | **needs the recovery permission** | action `RETRY` + reason |
| `NEEDS_RECONCILIATION` | human reconciliation: the physical action happened and matches the declaration | `APPLIED` | **needs the reconcile permission** | action `RECONCILE` + evidence snapshot |
| `NEEDS_RECONCILIATION` | human reconciliation: the physical action did not happen or does not match the declaration | `REJECTED` | same as above | action `RECONCILE` + evidence snapshot |
| `REJECTED / APPLIED / CANCELLED` | terminal | — | — | no automatic retry; if a correction really is needed ⇒ append a `MANUAL_CORRECTION` event + a new effect |

**The action audit table `plate_material_effect_action` is the only history** (v2.2 §4.6 adopted):
every declare / apply / reconcile / retry / cancel **appends one row** (`from_status → to_status`,
`actor_type / actor_id`, reason, evidence snapshot); `plate_material_event` **only records real
plate state changes** and never fakes a transition with a `RECONCILIATION` event.

| Crash / failure scenario | Behaviour (v3.2 correction) |
|---|---|
| **crash before T1** (the command was dispatched / responded, but the effect intent was not written) | **reconcile first**: `command_ledger` `ACKED` ⇒ the physical action happened ⇒ **rebuild the `PENDING` effect** from the run-level declaration snapshot; `UNKNOWN / DISPATCH_STARTED` ⇒ isolate + manual. **"Just re-run" is forbidden** |
| crash after T1 / before T2 | the effect is `PENDING` ⇒ the compensation replays T2 by the **already resolved `plate_id`** (no device action re-run, no position addressing re-run) |
| T2 rolled back | the effect goes back to `PENDING` ⇒ same replay (the idempotence key blocks a duplicate action) |
| repeated compensation | the idempotence key + the appended action ⇒ same key + same payload is skipped |
| the flow has already continued / ended when the compensation runs | the effect is decoupled from the run ⇒ the compensation does not need the run to still be alive; if the run has terminated, the unapplied effects go `CANCELLED` |
| the run is isolated (T1 failure) | the run lands in `quarantined` + an experiment-level `runtime_hold`; **resuming and re-dispatching device commands is forbidden before human confirmation + reconciliation** |
| database exception | **must not carry on silently** ⇒ record an error code + isolate / escalate to manual |

**The permission, reason and audit of human recovery** (review O-1 + the fifth review's G6):

| Item | Rule |
|---|---|
| who may operate | an independent permission is required (`material:reconcile` / `material:retry`; the automatic compensation job must not change it blindly) |
| is a reason mandatory | **yes** (`note` keeps the failure reason of that time and the reconciliation conclusion) |
| every attempt | **append one action** (never overwrite the failure record); write `operator_user_id` as well |
| concurrent recovery | only one recovery job may run per effect (a row lock / `state_version`) |
| what recovery does | **it only re-processes the material state projection**, and **never re-executes the device action** |
| interfaces | `POST /api/material-effects/{id}/retry`, `POST /api/material-effects/{id}/reconcile` (§8) |

### 5.9 The relation between the node state, the effect state and the run state (v3.2 update)

**The three are not equivalent** and must be looked at separately:

| Device execution | Material effect | Node state | Effect state | Result |
|---|---|---|---|---|
| success | achieved | `completed` | `APPLIED` | carry on normally |
| success | **awaiting reconciliation / failed** | `completed` | `NEEDS_RECONCILIATION` / `FAILED` | the node counts as complete, **the downstream is blocked by the dispatch gate**, the run enters `paused(material_sync)` |
| success | misconfiguration | `completed` | `REJECTED` | the node counts as complete, **the downstream is blocked**, escalate to manual |
| failure / manual | no effect produced | `failed` / `manual` | — | the existing recovery policy applies |
| **timeout** | **result unknown** | `manual` | `NEEDS_RECONCILIATION` | **it must not simply be treated as "the action did not happen"**; check the command ledger / reconcile manually first |
| the device succeeded but T1 could not even write the intent | — | not marked completed | — | **isolate the run (quarantined)**; no fake completion, no continuation replay |

⇒ **What "the node is fully complete" means**: a node explicitly declaring `NONE` does not wait
for an effect; a node that declares an effect **only counts as fully complete once the effect
reaches the required confirmation level** (the completion handler returns `CONTINUE`).
⇒ **No new `node_status` enum value is introduced**: `run_node.material_sync_status` expresses
it, and the **dispatch gate** expresses "the downstream is not dispatched".
⇒ **Whether the run may continue** is decided by the flow dependency relation, the node execution
result and the material consistency together — neither turning every material problem into a
failure of the whole run, nor letting the successors of an anomalous material keep running.

## 6. The material effect contract of a node

### 6.1 A dedicated column, not inside `params`

**Why it cannot go into `params`**: the execution side only reads
`workflow_node.params->'method_params'` and parses it with a **flat JSON parser** before passing
it to the driver ⇒ a nested structure placed there would be misparsed and **could even be passed
on to the device**. And `produces` is **the control system's own business contract**, not a device
execution parameter.

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

### 6.3 Resolving the target: the material identity comes first, the position is only a helper

| Rule | Explanation |
|---|---|
| **The material identity comes first** | `plateId` / `barcode` is the identity; **a position is a spatial attribute, not an identity** |
| Position addressing must check the attribution | inside the transaction verify `tenant_id + resource_id + occupied_by_run` (**fail-closed**, the same standard as the existing plate-taking path) |
| Barcode addressing must check too | verify the expected position / attribution, unless there is an explicit cross-run hand-over operation later |
| The `AUTO-*` placeholder identity | generated when a position is registered without a barcode (and persisted into the `plate` table as its barcode) ⇒ it **must not be treated as a verified barcode** |
| Device re-assignment | the `resourceId` written at design time is not necessarily the actual target ⇒ **the command parameters, the device response and the final position are the runtime evidence** |
| Resolution fails | **isolate and escalate to manual, do not guess** |
| Multi-step nodes | the target must define **which step's end position** it means; the position at the end of the node cannot be used to infer what the whole node worked on |

⇒ **T1 must persist the resolved `plate_id` into the effect record**, and everything after that
follows it.

⚠ **The barcode fact (v3.2 addition)**: `plate.barcode` is **NOT NULL** and unique within a tenant
(`uk_plate_barcode`), and the current code has no update path ⇒ §17 Q1 is corrected accordingly.

**Evidence priority** (the review's O-3 requirement: a physical identity must not be declared
confirmed on `occupied_by_run` alone):

| # | Evidence | Explanation |
|---|---|---|
| 1 | an **explicit** `plateId` / `barcode` in the declaration | the plate a person / the designer named — but **it is only "who we think it is", not "who it physically is"** |
| 2 | the material identifier in the **device command response** | what the device itself says (fresher than the position projection) — this is **physical evidence** |
| 3 | the target position in the command parameters + the position state projection | the weakest — **only a helper** |
| 4 | `occupied_by_run` | **it only proves "this run occupied it", not "this is physically the plate"** |

⇒ **Three kinds of conflict, three dispositions** (v3.2 addition · the fifth review's O-8):

| Conflict | Disposition | Reason |
|---|---|---|
| **① declaration vs ② device response** (pointing at **different plates**) | the effect goes to **`REJECTED`** + a human check of the **declaration** | the declaration is "who we think it is", ② is "who it physically is" ⇒ **do not write blindly per the declaration** (the same rule as T12 in §11.2) ⇒ the persisted `plate_id` takes **②**, not ① |
| ① and ② **agree** | take that plate | the two evidence paths corroborate each other |
| **② device response vs ③ position state projection** | ② wins | the position projection may lag (the plate may have physically moved while the device was executing) |
| ③ and ④ conflict | go to `NEEDS_RECONCILIATION` | both are weak evidence; **neither is automatically trusted** |

⇒ **when a physical move happens during the device execution** (the position projection update and
the material state processing happen at different times), evidence 2 above takes precedence over
evidence 3 — otherwise the wrong plate would be resolved.

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

## 7. Data model summary (v3.2 · corrected per the fifth review)

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

-- ④ Node level: physical completion and material sync are apart
ALTER TABLE run_node
  ADD COLUMN physical_completed_at timestamptz,      -- when the device/node physical action finished
  ADD COLUMN material_sync_status  varchar(24) NOT NULL DEFAULT 'not_required',   -- needs_reconciliation is 20 chars ⇒ ≥24
  ADD COLUMN material_error_code   varchar(64);
ALTER TABLE run_node ADD CONSTRAINT ck_run_node_material_sync
  CHECK (material_sync_status IN ('not_required','pending','applied','failed','needs_reconciliation','cancelled'));
CREATE INDEX ix_run_node_material_sync
  ON run_node (tenant_id, material_sync_status)
  WHERE material_sync_status IN ('pending','failed','needs_reconciliation');
-- Semantics: physical_completed_at only means the physical action finished, not that the material
-- projection is complete; for old data status='completed' + material_sync_status='not_required'
-- means an old flow without a material declaration (not "unknown").

-- ⑤ Material effect (the migration intent): T1 writes PENDING → T2 applies
CREATE TABLE plate_material_effect (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL REFERENCES tenant(id),
  source_run_id     bigint      NOT NULL,
  source_node_key   varchar(64) NOT NULL,
  source_loop_index int         NOT NULL DEFAULT 0,   -- graph path = in-memory visit (1..N); acyclic/sequential = 0
  effect_index      int         NOT NULL,             -- the effect ordinal within one execution
  source_attempt    smallint    NOT NULL DEFAULT 0,   -- recorded only, not in the idempotence key
  plate_id          bigint,                           -- the plate actually resolved in T1; required once APPLIED
  declaration       jsonb       NOT NULL,             -- declaration snapshot (the run version is frozen)
  payload_hash      varchar(64) NOT NULL,             -- same key + different payload → error
  status            varchar(24) NOT NULL,             -- six states (see below)
  applied_at        timestamptz,
  error_code        varchar(64),
  error_message     varchar(255),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_effect_status CHECK (status IN
    ('PENDING','APPLIED','REJECTED','NEEDS_RECONCILIATION','FAILED','CANCELLED')),
  CONSTRAINT ck_effect_applied_has_plate
    CHECK (status <> 'APPLIED' OR plate_id IS NOT NULL)
);
-- Idempotence key: structured (v3.2 persists no execution_id string column, see §5.5)
CREATE UNIQUE INDEX ux_plate_material_effect_key
  ON plate_material_effect (tenant_id, source_run_id, source_node_key, source_loop_index, effect_index);
CREATE INDEX ix_plate_material_effect_pending
  ON plate_material_effect (tenant_id, status, created_at) WHERE status <> 'APPLIED';

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
  CONSTRAINT fk_action_effect FOREIGN KEY (effect_id) REFERENCES plate_material_effect (id),
  CONSTRAINT ck_action_type CHECK (action_type IN ('DECLARE','APPLY','RETRY','RECONCILE','CANCEL','REBASE')),
  CONSTRAINT ck_action_actor CHECK (actor_type IN ('AUTO','MANUAL'))
);
CREATE INDEX ix_material_action_effect
  ON plate_material_effect_action (tenant_id, effect_id, created_at, id);

-- ⑦ Material events (immutable): only **real plate state changes**, one row each, append only
CREATE TABLE plate_material_event (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL REFERENCES tenant(id),
  plate_id          bigint      NOT NULL,
  event_type        varchar(32) NOT NULL,   -- produced / process_state_changed / seal_changed / retired ...
  from_process_state varchar(32),
  to_process_state   varchar(32),
  from_seal_state    varchar(16),
  to_seal_state      varchar(16),
  source_run_id      bigint,
  source_node_key    varchar(64),
  source_loop_index  int         NOT NULL DEFAULT 0,
  command_id         varchar(160),       -- references command_ledger.command_id (the dispatch-level command fact)
  effect_id          bigint,             -- the matching plate_material_effect
  evidence           varchar(24) NOT NULL,
  operator           varchar(16) NOT NULL,  -- AUTO / MANUAL
  operator_user_id   bigint,                -- the human operator (v3.2 addition)
  note               varchar(255),
  occurred_at        timestamptz,           -- the physical time
  recorded_at        timestamptz NOT NULL DEFAULT now(),  -- the system recording time
  CONSTRAINT ck_event_evidence CHECK (evidence IN
    ('DEVICE_CONFIRMED','EXECUTION_DECLARED','MANUAL_INVENTORY','MANUAL_CORRECTION')),
  CONSTRAINT ck_event_operator CHECK (operator IN ('AUTO','MANUAL'))
);
CREATE INDEX ix_plate_material_event_plate  ON plate_material_event (tenant_id, plate_id, occurred_at DESC);
CREATE INDEX ix_plate_material_event_source ON plate_material_event (tenant_id, source_run_id);
-- ⚠ v3.2: the from/to_owner_run_id columns are removed (attribution adds no new column, §4.5);
--   RECONCILIATION is no longer an event evidence (reconciliation is recorded in the action table).

-- ⑧ Cross-tenant / referential-integrity constraints (v3.2 completes the O-2 requirement; run the data precheck before S1)
-- Precheck (mandatory on the production database; fix dirty data first, otherwise the ALTER fails):
--   SELECT count(*) FROM run_node n JOIN run r ON r.id=n.run_id WHERE n.tenant_id<>r.tenant_id;
--   SELECT count(*) FROM plate_material_effect e JOIN run r ON r.id=e.source_run_id WHERE e.tenant_id<>r.tenant_id;
-- The order must not be reversed: add the referenced-side unique keys first, then the composite foreign keys.
ALTER TABLE run ADD CONSTRAINT uq_run_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate ADD CONSTRAINT uq_plate_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT uq_effect_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT uq_effect_tenant_run_id
  UNIQUE (tenant_id, source_run_id, id);

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
| command dispatched, result unknown | **`command_ledger`** | `command_id varchar(160)`, `idempotency_key`, `state`, `resolved_at/by` |
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
| v3.1 §7 executed as written (a temporary PG 17.11) | ✅ reproduced twice independently; the dictionary FK / seal CHECK / MATCH SIMPLE / the idempotence unique key / the five-state CHECK / the run_node sync CHECK / the three composite foreign keys all verified |
| building a composite FK directly without `uq_run_tenant_id` | ❌ fails with a missing unique constraint (v3.2 adds that prerequisite constraint) |
| after adding `uq_run_tenant_id`, the 8 cross-tenant / reference constraints | ✅ all can be created; cross-tenant writes are rejected (measured by review-ddl in the fifth round) |
| the MATCH SIMPLE NULL hole of the three-column FK | ⚠ measured to exist ⇒ v3.2 adds `ck_event_effect_needs_run` to catch it |
| `varchar(24)` holding `NEEDS_RECONCILIATION` (20 chars) / `needs_reconciliation` | ✅ the character count checks out; the S1 regression must **insert every value** (six states + six sync statuses) |
| **the whole of the v3.2 DDL run for real** | ⏳ **to be run** (not re-run within this round's time box; before S1 it must be run in a temporary database the same way: every value of the six states including CANCELLED, effect_action appends, seal_state NULL, the 8 constraints, the data precheck) |

⚠ **Paper DDL that is never executed will never reveal these three kinds of problem** (real defects
caught by v3.1):
1. in the v2/v3 first drafts the dictionary composite foreign key and the seal state CHECK were
   written as comments;
2. the column was too narrow: `needs_reconciliation` is 20 characters and could not be written at
   all when the column was `varchar(16)`;
3. the cross-tenant foreign keys could not be created: the referenced columns need a
   `(tenant_id, id)` unique constraint first.

## 8. Interfaces (v3.2 adds reconciliation / recovery and version control)

| Method | Path | Explanation |
|---|---|---|
| GET | `/api/plates?processState=&resourceId=&plateType=&positionStatus=` | query plates by stage / position / plate format / position trustworthiness (v3.2 removes the currentRunId / originRunId parameters) |
| GET | `/api/plates/{barcode}` | one plate: current stage, seal state, position (current attribution read from `position_state`), production source (the first event), processing history (the event stream) |
| GET | `/api/plates/{barcode}/state-history` | the material event history (paginated, including the evidence source, the operator and the physical / recording timestamps) |
| PUT | `/api/plates/{barcode}/state` | change the state by hand; the body must carry `expectedVersion` (a mismatch ⇒ **409** + the current value); permission `material:write`; append an event with `evidence=MANUAL_INVENTORY / MANUAL_CORRECTION` + `operator_user_id` |
| GET | `/api/runs/{id}/material-effects` | **the material results / state changes this run declared** (queried by the `source_run_id` of the effect records, not by "current attribution") |
| GET | `/api/runs/{id}/material-gate` | the nodes of this run blocked by the dispatch gate, the reason and the suggested disposition (operations view) |
| GET | `/api/material-effects?status=&runId=&plateId=` | the effect list (the reconciliation console) |
| GET | `/api/material-effects/{id}/actions` | the effect action history (append-only audit: DECLARE/APPLY/RETRY/RECONCILE/CANCEL/REBASE) |
| POST | `/api/material-effects/{id}/retry` | `FAILED → PENDING`; permission `material:retry`; **`note` is mandatory**; appends the action `RETRY`; **only reprocesses the projection, never re-runs the device** |
| POST | `/api/material-effects/{id}/reconcile` | `NEEDS_RECONCILIATION → APPLIED / REJECTED`; permission `material:reconcile`; carries `decision + evidenceSnapshot + note`; appends the action `RECONCILE` |
| GET | `/api/plate-process-states` | the stage dictionary (tenant level) |
| POST | `/api/plate-process-states` | add a stage (permission `material:admin`) |
| PATCH | `/api/plate-process-states/{code}` | rename / reorder / **deactivate** (the `code` may not change; permission `material:admin`) |

⚠ **The precondition of the recovery entry point**: a run that is `quarantined` or
`paused(material_sync)` may only be `POST /api/runs/{id}/resume` (continue) once
`GET /api/material-effects` shows **no** `PENDING / FAILED / NEEDS_RECONCILIATION` effect;
otherwise resume returns 409 plus the list of unclosed effects, and **re-dispatching device
commands is forbidden**.

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
| **The old-data semantics, column by column** | `process_state` = NULL (unknown); `seal_state` = NULL (unknown, **not `none`**); `state_version` = 0 (a CAS counter, harmless); `run_node.material_sync_status` = `not_required` (an old flow without a material declaration, not "unknown") |
| **No historical back-fill** | the historical state is not known, and back-filling would be inventing data (**in particular, do not use default values to pretend it is known**) |
| **What the UI shows** | a NULL `process_state` / `seal_state` shows as "unknown"; `none` alone shows as "no seal" |
| **The execution behaviour of old flows** | without a `material_effect` declaration ⇒ **the execution behaviour is completely unchanged** (`legacy_unspecified` ⇒ preflight WARN, runs as `none`) |
| **S1 migration-script requirements** | ① wrap in `DO $$ ... EXCEPTION WHEN duplicate_object / duplicate_column` to make it repeatable; ② run a **data precheck** before the composite foreign keys (cross-tenant dirty data); ③ register the new tables with the `attach_updated_at` trigger; ④ the same source as `90_all_in_one.sql` (`check-schema-sync.sh`); ⑤ **an enum insert-every-value regression** (six states + six sync statuses, to catch column-width / CHECK omissions) |
| **Execution-side compatibility** | `run.workflow_ver` starts to be really written (an existing column); when `workflow_node.material_effect` changes, `workflow.version` is incremented; a continuation reads the snapshot |
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
| Old flows change behaviour not at all | run an existing flow from the database and compare with the behaviour before the change | real environment |

### 11.2 Failure scenarios (27 · the merged list of the three reviews, the three designs, the unified architecture V1.0 and the fifth review)

| # | Scenario | The result that must be guaranteed |
|---|---|---|
| T01 | the device succeeded, the database write failed | **it must not silently show the material state as correct** ⇒ T1 fails ⇒ **the run is blocked and escalated to manual** (running it again would perform the physical action a second time) |
| T02 | the device timed out, but the action may have happened | **it must not simply be treated as "the action did not happen"** ⇒ the effect goes to `NEEDS_RECONCILIATION` |
| T03 | the same device command callback arrives twice | the effect is applied only once (the idempotence key blocks it) |
| T04 | the same node loops three times in one run | **all three real effects are traceable** (the structured idempotence key carries the in-memory `visit`) |
| T05 | one logical execution retries three times | **only one material effect is produced** (`attempt` is not in the idempotence key, it only goes into the action audit) |
| T06 | the run crashes and recovers | the confirmed effects are not repeated; the unconfirmed ones **can be recovered** (replayed by the already resolved `plate_id`) |
| T07 | two runs modify the same plate at the same time | **no state is overwritten** (`FOR UPDATE` / `state_version` CAS) |
| T08 | two materials occupy the same position at the same time | blocked by the existing `PositionLock` (**reused, not rebuilt**) |
| T09 | the target position is already occupied | the migration fails visibly and escalates to manual, no guessing |
| T10 | the material cannot be found / the identity cannot be confirmed | **isolate and escalate to manual, cannot guess** (the position is only a helper, never the identity) |
| T11 | a manual inventory and an automatic migration collide | there is an explicit conflict handling, **no silent overwrite** |
| T12 | the declaration disagrees with the device execution result | the effect goes to `REJECTED` and escalates to manual, **the declaration is not written blindly** |
| T13 | the node succeeded but the effect processing failed | the node still counts as completed (no fake device failure), **the downstream is not dispatched** (the dispatch gate checks the material sync status of the direct predecessor nodes) |
| T14 | the state update succeeded but the event record failed | the two are **in one transaction**, either both or neither (a "state changed but no history" is not allowed) |
| T15 | a cross-tenant material or dictionary reference | **must be rejected** (composite foreign key / tenant context check) |
| T16 | recovery of `FAILED → PENDING` / `NEEDS_RECONCILIATION` | **permission, reason and audit are complete**; every attempt appends one action (RETRY / RECONCILE) and never overwrites the failure record |
| T17 | the sequential path and the graph path | they enter the **same** state handling mechanism (not two) |
| T18 | a multi-step node execution | the intermediate execution facts and material impacts are **not missed** |
| T19 | an old callback arrives after a manual correction | it **does not overwrite** the manually confirmed state |
| T20 | the run terminates abnormally and the position is still held | it can be safely reconciled and released |
| T21 | the node declaration is changed while the run is going | T1/T2 only use the run-start snapshot (`run_node.input.material_effect_snapshot`); **the new declaration does not affect a run already started**; it only takes effect in the next run |
| T22 | a dictionary state is disabled while plates still reference it | deactivation does not delete (`enabled=false`) ⇒ the historical display is unaffected; a new migration declaring a disabled state ⇒ preflight ERROR |
| T23 | several effects of the same execution partially succeed | each `effect_index` has its own state; **a partial success must not mark the whole as APPLIED**; the run releases the gate only after all effects are closed |
| T24 | `NEEDS_RECONCILIATION` is confirmed / rejected by a human | the `reconcile` interface → `APPLIED` / `REJECTED`, appending an action + reason + evidence snapshot; no plate state is written before confirmation |
| T25 | an old callback arrives again after a manual correction | the old callback hits the idempotence key (already processed) ⇒ ignored; the manual correction has appended an event / incremented the version and is not overwritten |
| T26 | a node fails / needs reconciliation in the Nth loop round | a loop node must aggregate the actual state of rounds 1..N; if any round is not closed ⇒ the downstream gate blocks — do not look at the summary row only |
| T27 | a quarantined / gate-held run is restarted | it stays `quarantined` / `paused(material_sync)`; resume deleting nodes and re-running is **forbidden**; after the reconciliation loop closes it is restored per the §8 precondition |

⚠ Any one of them without an explicit handling mechanism means the material state management
**cannot be claimed production-ready**.

---

## 12. Implementation slices (v3.2 update)

| Slice | Content | Depends on | Produces |
|---|---|---|---|
| **S0** | **The domain model is finalised** (no tables): the Material layering and the boundaries; the three attribution concepts (no new columns); the tenant model of the stage dictionary; the structured idempotence key; the two-level failure semantics + the dispatch gate + the completion handler's return contract (CONTINUE/HOLD/throw); the six-state machine; the execution-path inventory and the gap dispositions (§16.1); **the S0 freeze decision** (§16.3) | — | the model is settled; **no code until the re-review passes** |
| **S1** | Basic data structures: the stage dictionary, the new `plate` columns (process_state / seal_state / state_version), `workflow_node.material_effect`, the three new `run_node` columns, the effect table, the action audit table, the event table, the 8 cross-tenant / reference constraints, writing `run.workflow_ver`, the migration script (idempotent + data precheck + an insert-every-value enum regression). **Not wired into the execution engine** | S0 | the tables are in place, zero behaviour change |
| **S2** | The execution fact and material effect engine: `NodeCompletionHandler` (the single injection point + loop-round propagation), T1/T2, the six states, idempotence, loops, retries, **the dispatch gate** (the GraphRunner / executeAll predicate), `pause_kind=material_sync`, isolation (quarantined + experiment hold), compensation, the reconciliation / recovery API and its permissions | S0, S1 | **the behaviour starts to change** |
| **S3** | Material query and traceability: the interfaces + the inventory columns + the state transition history + the run detail material results + the read-only reconciliation console | S2 | the site can look it up |
| **S4** | The process designer: the material effect section (mode) + preflight + the node impact classification | S2, S3 | the site can configure it |
| **S5** | The independent consumable domain: **refactor the existing** `consumable` / `consumable_lot` (position + attachment history + state machine), the lid stock and handling | S0 | lids become traceable |

**The boundary between S0 and S1**: S0 only settles the model / contract (no execution-code change,
no tables); S1 only creates tables and migrates; any execution-behaviour change is S2+.
**S1 must not be touched before S0 is frozen.**

## 13. Known boundaries and items still to close (v3.2 honest list)

### 13.1 Adjudicated boundaries (no longer "open", they are explicitly not done this round)

| # | Boundary | Disposition (v3.2) |
|---|---|---|
| 1 | **Tenant isolation is not actually implemented** (`TENANT = 1L` hard-coded in 20+ places in the boot layer) | this round only guarantees the **database-level** tenant constraints of the new entities (§7's eight constraints); the tenant-context refactor is **listed as an S1 prerequisite risk** and is not pretended to be implemented |
| 2 | the completion handler has to change the `RunStateMachine` signature (to pass the round) | **adjudicated**: a single injection point + an explicit execution context (§5.7); the list of change points is in §5.7 / §12 S2 |
| 3 | the state machine of the stages (legal transitions) is not done this round | only the dictionary and the reference constraint; **transition legality is deferred until a real dictionary exists** (re-evaluated after S4) |
| 4 | the consistency check between the well-level sample and the plate-level stage is not done this round | the boundary is nailed down (§4.2's three boundaries); the consistency check is listed as a post-S3 task |
| 5 | the classification metadata (at the capability / method level) behind "declared vs not declared" is not done this round | in the transition period old flows are a WARN (no behaviour change); new flows may declare `none` explicitly |
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
| 13 | **the material status of each round of a loop has no carrier** (found by this session, **blocks the S0 freeze**) | §5.2b requires the gate to "aggregate the actual status of every round of the loop node (`loop_index=1..N`) and block when any round is not closed", but ① the round row is written **after** the node executes (`GraphRunner.java:657`), so at the T1 point the row of the current round does not exist yet; ② `insertNodeRound` (`RunMapper.xml:584-595`) **does not write** `material_sync_status`, so the new column takes the default `not_required` ⇒ the gate would read "some round needs reconciliation" as `not_required` and **let it through**. **Recommended S0 decision: (a)** — the gate aggregates the per-round effect status from `plate_material_effect` by `source_loop_index` (the effect row is persisted at T1 and naturally carries the round), without depending on the `run_node` round rows; **(b)** — move the round-row persistence earlier, add the column to `insertNodeRound`, and write it back after execution; a bigger change that also touches existing SQL semantics. **§5.2b's "aggregate every round" cannot be implemented until this is decided** |
| 14 | **effect `REJECTED` has no corresponding value at the node level, and no mapping rule** (the 05 review's O-5 **partially landed**, found by this session) | §5.9 already lists a `REJECTED` row ("device succeeded / configuration error ⇒ node `completed` / downstream blocked / hand over to a human"), but the node-level carrier `run_node.material_sync_status` takes `not_required / pending / applied / failed / needs_reconciliation / cancelled` (§5.2b and the §7② CHECK agree) — **there is no `rejected`**, and the document nowhere writes the mapping `REJECTED → ?`. ⇒ Without a mapping, the "downstream blocked" promised by §5.9 has **no value to read at the gate**; mapping it to `failed` makes "terminal `REJECTED` (no automatic retry)" and "retryable `FAILED`" indistinguishable at the node level again (the original concern of O-5). **Recommended S0 decision: (a)** — add a `rejected` value to `material_sync_status` (`varchar(24)` fits it; add the enum value to the CHECK; §7② must be re-verified by running the DDL), and add `rejected` to the gate's blocking list; **(b)** — write down `REJECTED → failed` explicitly and note in §5.9 that "the distinction is deliberately kept in `plate_material_effect.status` + the action audit, and merged at the node level" |

**Conflicts with existing documents** (per §7 of `docs/评审规范.md`: a ②③ conflict ⇒ change the
specification first):

| Conflict | Disposition |
|---|---|
| `LabOS功能规格说明书.md` §7.2 "2.0 delivers sequence only" | this round only adds container dimensions, it **does not touch process orchestration** ⇒ no conflict; a "known boundary" note pointing at the gap document was added |
| `docs/板位级占用与位置互斥-设计.md` §5 "plate-level tracking (where the plate is)" | this round adds "which step of the process the plate is at"; the two complement each other; a pointer to this document should be added there |
| `docs/工艺编排能力缺口与落地路线.md` §4.2 (the G2 criterion) | **corrected per the reviews**: the criterion is widened to "independent identity or lifecycle traceability"; §4.4 here is the corrected version |

## 14. Explicitly not in this round (v3.2)

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

## 15. List of decision points (v3.2: 13, each with a conclusion)

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
| 10 | The effect state machine | **six states** (adding `CANCELLED`); reconcile / retry / cancel are all recorded in `plate_material_effect_action` | the fifth review's G6 + v2.2 §4.6 |
| 11 | The recovery entry point | a T1 failure ⇒ isolate the run (quarantined + experiment hold); `NEEDS_RECONCILIATION` / `FAILED` go through the controlled API; **resume re-dispatching physical actions is forbidden** | the fifth review's S4 + the `StartupRecovery` precedent |
| 12 | Where attribution lands | **do not add** `plate.current_run_id` / `origin_*`: current attribution reads `position_state.occupied_by_run`, the source / history read the event stream | v2.2 §0.6's objection (adopted by v3.2) + preventing double sources of truth |
| 13 | The execution-fact carrier | **`command_ledger` (pre-dispatch) + `run_command` (post-response)**, no second command ledger | the fifth review's P0 (`run_command` has no `command_id`) |

**Wording convergence (v3.2 update)**:
① the effect state machine has six states; the event table is named `plate_material_event` (with
physical / recording dual timestamps); the effect action gets its own table;
② `plate_material_event` only records real plate changes, and reconciliation actions **do not fake
events** (they are recorded as actions);
③ the Execution Fact reuses `command_ledger` + `run_command`, and the material event keeps its own
ledger;
④ the declaration column `workflow_node.material_effect` + a run-start snapshot
(`run_node.input.material_effect_snapshot`).

## 16. The five hard gates of S0: where they stand after the fifth review (v3.2)

> From the unified architecture V1.0 (record 04, O-6). **Only when all five are met plus the gap
> dispositions of §16.3 are done may S0 be frozen and S1 entered.**

| # | Gate | Where it stands (after the v3.2 check) | Basis |
|---|---|---|---|
| 1 | The recovery state machine is explicit (human reconciliation, permission, reason, audit) | ✅ **met in design**: six states + a transition table + the action audit + retry / reconcile APIs + permission codes (§5.8 / §8) | the fifth review's G6 / S4 |
| 2 | The tenant constraints are explicit | ✅ **met in design**: 8 composite / three-column FKs + the NULL CHECK + the data precheck (§7⑧); ⚠ a live-data precheck is needed before implementation | the fifth review's D2, measured |
| 3 | The identity resolution is explicit | ✅ the four-level evidence priority + the three conflict dispositions (§6.3) + the barcode fact correction (§17 Q1) | the fifth review's G9 |
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
| 6 | manual recovery continue / skipNode | `PauseCoordinator → resumeAsync → execute` | ✅ | — |
| 7 | manual recovery retry / skip (window) | `PauseManager.resumeTx` only sets `queued` and **does not continue immediately**; it relies on a restart rehydrate | ⚠ | fix in S2: continue immediately, or make the UI wording explicit |
| 8 | canvas step-by-step execution | `POST /api/experiments/flow-run → ExperimentFlowRunner → ShadowProbe → driver.execute` | ❌ | **gap**: take it offline or refactor it inside S0 (§13.2-7) |
| 9 | shadow runs / single-command probes | `ShadowRunner` / `ShadowProbe` really dispatch, and persist no run / run_node / run_command | ❌ | **not covered**: operational-rules restriction + an explicit statement (§13.2-8) |
| 10 | CLI / demo (verification only) | `RealWorkflowRunner` etc. | — | a non-production path, not counted |

### 16.2 The things that must stay UNKNOWN (no "we will refine it later" in place of a decision)

| Item | Why it is UNKNOWN right now |
|---|---|
| whether the device can supply strong "physically confirmed" evidence | it depends on the driver capability of each device; S0 checks it device by device, no guessing |
| what the `AUTO-<runId>` placeholder barcode means in the material system | it is the fallback for a position registered without a barcode and **must not be treated as a verified identity** ⇒ how the material side handles it awaits a product decision |
| whether the live `node_status` enum contains `manual` / `bypassed` | the schema script and the code appear to have drifted; check the live database before S1 |

(v3.1's "may a barcode change / be duplicated" is no longer UNKNOWN — it is answered in §17 Q1.)

### 16.3 The S0 freeze decision (v3.2 conclusion)

**Conclusion: this round does not meet the conditions to "freeze S0 unconditionally".** The
freezable path = complete the following 3 prerequisites and then sign once more:

| # | Prerequisite | Owner | Acceptance criterion |
|---|---|---|---|
| 1 | **take the flow-run endpoint offline** (or refactor it into the unified executor) | S0 | the endpoint returns 404 / goes through `WorkflowService`; §16.1 no longer has an ❌ production path |
| 2 | **confirm the disposition of every fifth-review finding** (record 06 + record 05) | review | no P0 open; all P1 accepted or explicitly downgraded; the 10 mandatory questions have no UNKNOWN fallback |
| 3 | **run the v3.2 DDL for real in a temporary database** (every value of the six states / the 8 constraints / the action table / `seal_state NULL`) | S0 | all green, and the result is written back into the §7 verification table |

⇒ once the three are done ⇒ submit the S0 freeze (and register D401-D413, §18.2); before that
**S1 does not move**.

### 16.4 Delivery verification levels (v3.2 addition · absorbs v2.2 §11.5)

| Slice | Delivery verification |
|---|---|
| **S1** | migration script + schema sync + database constraints and all-enum tests |
| **S2** | real PostgreSQL fault injection + integration tests of the §16.1 execution-path inventory and the dispatch gate |
| **S3 / S4** | APIs, the complete action history, hands-on page operation |

⚠ **Passing with a simulated driver proves only the logic chain, not real-device verification** ⇒ the real-machine
conclusion must be re-established by HIL (hardware in the loop). No acceptance record of this document may
**use simulator results to impersonate a real-machine conclusion**, nor infer real-machine usability from the
S1 / S2 pass rate.

## 17. The ten questions the review has to answer (v3.2 re-answer)

> From appendix B of the unified architecture V1.0. **Every question comes with a source
> reference, a design decision, or an explicit UNKNOWN.**

| # | Question | This document's answer (v3.2) |
|---|---|---|
| 1 | What field guarantees the stable identity of a plate? May a barcode change or be duplicated? | the stable identity is **`plate.id`** (the primary key). `barcode` is **NOT NULL** and unique within a tenant (`uk_plate_barcode`); the DB layer has no immutability constraint, but the application layer **has no update path** (effectively immutable); `AUTO-<runId>` is persisted as a barcode and **must not be treated as a verified identity**. Whether a change is allowed ⇒ a product decision; the default this round is **forbidden** (opening it up requires a review) |
| 2 | Does the current state of a plate already have several sources? How is a single authoritative state determined? | there are already 5 groups: `plate.status` / `plate.position_status` + `possible_locations` / `position_state` / `plate.location_id` (a redundant projection) / `device_state` derivation. v3.2 settles the rule: lifecycle = `plate.status`; position = `position_state`; position trustworthiness = `position_status`; `location_id` is read-only redundancy; the process stage = the new `process_state` (§4.2's inventory table) |
| 3 | What is the device's evidence of the material identity? | the command fact is in **`command_ledger`** (pre-dispatch, the `command_id` is generated by the kernel) + the response evidence `run_command.device_raw_response`; identity resolution follows §6.3's four-level evidence + the three conflict dispositions; insufficient evidence ⇒ `NEEDS_RECONCILIATION`, no guessing |
| 4 | How does an execution fact tell a loop, a retry and a duplicate callback apart? | **three layers**: a loop = the execution-context `visit` (graph) or 0 (acyclic / sequential); a retry = `attempt` (**not in the idempotence key**, only in the action audit); a duplicate callback = blocked by the structured idempotence key; at the command level there is additionally `command_ledger.idempotency_key` |
| 5 | After the device finishes, the database commit fails — how is that recovered? | the **two-level failure semantics** (§5.2b): T1 (the effect intent was not written) ⇒ isolate the run (quarantined + experiment hold), reconcile against the command ledger first and **forbid re-running**; T2 ⇒ the node completes, the effect is `PENDING/FAILED`, the downstream gate blocks, and the compensation replays by the already resolved `plate_id` |
| 6 | How does an old asynchronous callback avoid overwriting a manual correction? | the old callback hits the idempotence key / an already-processed action ⇒ **ignored**; the manual correction appends an event + increments `state_version`; on the effect side a manual operation appends an action and never overwrites the history (T19 / T25) |
| 7 | Do the sequential path and the graph path really share the same completion handler? | **by design: yes** (the single injection point = `RunStateMachine.executeNode`, and the two production paths share the same `sm`); **source: ✅ the two production paths are proven; ❌ the flow-run endpoint / shadow runs do not go through it** ⇒ gate 4 is judged **PARTIAL** (§16.1/§16.3) |
| 8 | How do PositionLock and the material state update coordinate? | the position belongs to `PositionLock` (`position_state` + `occupied_by_run` = the single source of truth for position), the material belongs to `MaterialEffectService`; the two never rewrite each other; "current attribution" is always read from `occupied_by_run` and **no `current_run_id` is added** |
| 9 | Can the multi-tenant constraints be guaranteed by the database? | among the new entities ✅ (8 constraints including the three-column FK + the NULL CHECK); the existing `run_node↔run` ✅; but **the 20+ hard-coded `TENANT=1L` places**, the `plate.location_id` redundancy and other historical problems remain ⇒ overall **PARTIAL**, listed as an S1 prerequisite risk |
| 10 | How does a one-to-one Effect leave room for a future SPLIT / MERGE? | `outputs` is an array and `effect_index` distinguishes effects; this round restricts one-to-one in the validation layer; `operations` is a placeholder only and preflight rejects an unknown operation; a future SPLIT/MERGE is evaluated separately with **contract v2 + effect grouping**, without changing the existing table structure |

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

### 18.2 Decision-number register (D401-D413; merged into §7 of `docs/评审规范.md` after S0 is frozen)

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
| D412 | The 8 cross-tenant / referential-integrity constraints (including the three-column FK + the NULL CHECK + the data precheck) | §7⑧ |
| D413 | The execution-path inventory and the S0 disposition of the flow-run / shadow-run gaps | §16.1 / §13.2 |

### 18.3 The validation / regression items added this round (mandatory for S1 / S2)

1. insert **every value** of the six states + the six `material_sync_status` values (to catch a
   column-width / CHECK omission);
2. **run the v3.2 DDL for real in a temporary database** (including the action table,
   `seal_state NULL`, the 8 constraints, the data precheck);
3. **regress after the flow-run endpoint is disposed of** (§16.1 has no remaining uncovered
   production path);
4. dispatch-gate regression: in parallel branches only the dependent successors are held
   (T13 / T26);
5. reconciliation-loop regression: `FAILED` / `NEEDS_RECONCILIATION` → retry / reconcile → the
   downstream is released (T24).

---

*This is **v3.2 · an S0 freeze candidate**; no code and no schema change has been made yet. The
fifth independent review has two records: `docs/评审记录/20261112-05-mavis.md` (the concurrent
session · MiniMax subagent) and `20261112-06-mavis.md` (this session · DeepSeek subagents ×3 +
the summary). Review records are named per §8 of `docs/评审规范.md`; the reviewed party's response
is written into the disposition-update section of the corresponding review record.*
