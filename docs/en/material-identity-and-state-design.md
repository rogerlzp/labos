# Material Identity and State · Design (v3 · three-way convergence)

> **Status**: **up for re-review** (v1 raised 2026-11-11; v2 rewritten on 2026-11-12 after merging
> the findings of three independent **reviews**; **v3 converged on 2026-11-12 after merging three
> independent *designs***)
> **Decision number segment**: this document claims **D401-D4xx** (to be merged into the segment
> table in `docs/评审规范.md` §7 once the review passes)
> **Related**: [Process Orchestration Capability Gaps and Landing Route](process-orchestration-gaps-and-roadmap.md)
> (the gap analysis, G1/G2/G11); review records `docs/评审记录/20261112-01-grok.md`,
> `20261112-02-kimi.md`, `20261112-03-openai.md`
> **Code baseline**: `main` @ `0eb2a99` (the v2 commit, documentation only)
> **⚠ This is still a design; no code and no schema change has been made.**
>
> **How this document came to be**: the same question produced **three mutually independent
> designs** (see §0.1). This document is their **convergence**: it keeps only the **points all
> three agree on** and the **disagreements that have been adjudicated**. The superseded drafts
> are kept for comparison and are no longer candidate baselines.

---

## 0. Review entry point

| Item | Content |
|---|---|
| **What is being reviewed** | The domain model for material identity and state, the mechanism for execution facts and material effects, the data model, the interfaces and the pages |
| **Why now** | Checking against a real process flowchart showed that LabOS orchestrates "actions" while the real process orchestrates "material"; a container has an identity and a position but **no process stage** ⇒ the site cannot answer "which step of the process is this plate at", and material hand-over across processes cannot be expressed either |
| **Read these three first** | §3 (the facts) → §4 (the domain model) → §5 (execution facts and material effects) |
| **How v2 differs from v1** | v1 had **6 factual errors** and **4 model defects**, each found independently by at least one of the three reviews ⇒ v2 fixed all of them; the list is in §0.2 |
| **Review focus** | ① the two-level failure semantics in §5.2b; ② the extension boundary of the contract in §6; ③ the boundary between plate stage and well-level sample in §4.2; ④ the idempotence key in §5.5; ⑤ the completion-point scheme in §5.7 |
| **Known problems** | See §13, **six open questions**, two of which would change the implementation |

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
| D1 | The anchor of the idempotence key | `run+node+loop+plate` | effect_key | `execution_id + effect_index` | **`execution_id + effect_index`** | C's anchor is the most stable, and it also solves "one node produces several effects" |
| D2 | Where the execution fact lives | the effect table doubles as T1 | two columns on `run_node` | a separate Execution Fact object | **a three-way split**: the command fact reuses `run_command`; the node-level sync status goes on `run_node`; the material effect gets its own table | No second command ledger, and the effect table does not double as the execution fact |
| D3 | The effect state machine | 3 states | 3 states | **5 states** | **5 states** (adding `REJECTED` / `NEEDS_RECONCILIATION`) | "the effect violates the rules" and "the physical fact is unclear" are two completely different dispositions; merging them loses information |
| D4 | The relation between the node and the effect | decoupled (not detailed) | decoupled (not detailed) | explicitly "the node only counts as fully complete once the effect reaches the required confirmation level" | **adopt C, and give it a landing** | Do not change the `node_status` enum; the **scheduler's dependency check** decides whether the downstream is dispatched (⚠ not `runtime_hold`: its scope only has experiment/island, far too coarse) |
| D5 | The name and fields of the event table | `plate_state_audit` | `plate_material_event` | `Material Event` (with `occurred_at` / `recorded_at`) | **`plate_material_event` + the two timestamps** | "event" expresses an immutable event stream better than "audit"; the physical time and the recording time must be apart |
| D6 | How much of the domain model lands | the lightest (extra columns on `plate`) | in between | the heaviest (three relation tables) | **a compromise**: Material = the existing `plate` plus columns; Location = **reuse** `position_state`; Content = **reuse** `plate_well`/`sample`/`sample_aliquot` | The three agree on the future model; they differ only on how many tables the first phase builds, and reusing what exists avoids a second source of truth |
| D7 | The criterion for consumables | independent identity / lifecycle traceability | an independent entity plus position and attachment history | independent identity / lifecycle / stock / batch / expiry / reuse / traceability | **the union of the three** | The three word it differently but mean the same thing |

### 0.2 The errors in v1 (publicly acknowledged, per `docs/评审规范.md` §4)

The **factual errors** the reviews pointed out (my draft was simply wrong — not a difference of
opinion):

| # | What v1 said | The truth | Why I got it wrong |
|---|---|---|---|
| E1 | "`GraphRunner.java:604` is the node-completion hook" | that line is in the **`Kind.BUILTIN` branch**; the device TASK completion callback is at **`:650`** | I saw the line number and assumed it was right — **I never checked which branch it was in** |
| E2 | "`WorkflowService` is in the boot layer" | it is in `src/main/java/com/labos/kernel/workflow/` (the **domain layer**); the boot layer one is `WorkflowWebService` | I mixed up two similarly named classes |
| E3 | "`plate` only has `status(active)`" | the default is `available`; the vocabulary is **`available / in_use / consumed / discarded`** | I only checked the **actual data values**, not the **column definition and its comment** |
| E4 | "`plate_type` only ever holds `unknown`, change it to six process roles" | the default is `'96'`; the vocabulary is **`96 / 384 / 24 / tube_rack`** = **plate format specification** | same mistake: I took "what the six rows happen to hold" for "what the column means" |
| E5 | "only the first of the four dimensions is implemented" | the **`plate_well` / `sample` / `sample_aliquot` tables have existed all along** (0 rows) ⇒ the **well-level sample model is already there** | I grepped one table (`plate`) and never looked at the neighbouring schema |
| E6 | "`NodeEffectListener` extends the existing listener pattern" | `BuiltinListener` **only covers builtin nodes** and **swallows exceptions**; device tasks go through `stateMachine.executeNode` | I wrote "the pattern is similar" as if "the mechanism exists" |

**The model defects** (found by the three reviews, consistently or in complementary ways):

| # | Defect | Fixed in |
|---|---|---|
| M1 | "must be in the same transaction as node completion" **cannot be satisfied** in the current architecture and contradicts "a failure must not roll the node back" | §5 becomes a two-phase T1/T2 |
| M2 | one set of `owner_*` columns carrying three different concepts, so the production relation is lost on every overwrite | §4.5 splits the three concepts |
| M3 | the idempotence key omits `loop_index` ⇒ **the third iteration of a loop hits the unique index** and breaks the process semantics outright | §5.5 |
| M4 | `target.by=position` is ambiguous (source position or target position?), and a position is a spatial attribute, not a material identity | §6.3 |

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

| Fact | Where |
|---|---|
| `plate.status` defaults to `available`; vocabulary `available / in_use / consumed / discarded` | `30_business.sql:68` |
| `plate.plate_type` defaults to `'96'`; vocabulary `96 / 384 / 24 / tube_rack` = **plate format specification** (with `rows`/`cols` next to it) | `30_business.sql:65` |
| The `plate_well` / `sample` / `sample_aliquot` **tables already exist**, all with 0 rows | `30_business.sql:17-58,78-93` |
| `run_node` unique key `UNIQUE (run_id, node_key, attempt, loop_index)` ⇒ **the loop round is a first-class dimension** | `20_kernel.sql:328` |
| The device TASK completion callback is at `GraphRunner.java:650`; `:604` is in the `Kind.BUILTIN` branch | `GraphRunner.java` |
| `BuiltinListener` is only called in the builtin branch, and `emit()` **catches the exception and ignores it** | `GraphRunner.java:577-626` / `RunStateMachine.java:250-265` |
| **The common completion point of the graph path and the sequential path** is `RunStateMachine.executeNode` returning `COMPLETED` (both paths go through it) | `RunStateMachine.java:87-112`; `WorkflowService.java:1056-1066` |
| The sequential path (`sm.executeAll`) **does not go through** `nodeStateRecorder` ⇒ there is no node-level callback there | `WorkflowService.java:1056` |
| The `run_node` completion update and the `run_command` insert are **separate commits**, and the command commits first | `WorkflowService.java:2213-2244`, `RunMapper.xml:555-563` |
| `PositionLock.auditPlateMove` writes its audit with an **independent connection**, logs and moves on, and **deliberately stays out of the position-lock transaction** | `PositionLock.java:301-316` |
| The plate-taking path already has a **fail-closed ownership check** (it verifies `occupied_by_run`) | `PositionLock.java:328-343`, `DeviceStateMapper.xml:251-256` |
| A position registered without a barcode gets an **`AUTO-<runId>` placeholder identity** | `PositionLock.java:450-485` |
| `WorkflowWebService.TENANT = 1L` is **hard-coded** ⇒ multi-tenant isolation is **not actually implemented yet** | `WorkflowWebService.java:48` |
| The execution side only reads `workflow_node.params->'method_params'` and parses it with a **flat JSON parser** | `WorkflowMapper.xml:70-75`, `WorkflowService.java:1418-1458` |

---

## 4. The core model

### 4.1 The Material domain layering (new: do not lock it into the `plate` table)

```
Material (material, abstract base)
├── Container (a container: it holds things)
│   ├── PCR Plate
│   ├── Deep Well Plate
│   └── Assay Plate
├── Consumable (a consumable: a lid, a cap — needs an independent identity or lifecycle)
├── Sample (sample)
├── Reagent (reagent)
└── Waste (waste)
```

**Only this round**: the plate-level process stage, the attribution and the audit of the
`Container` branch.
The other branches **only reserve the namespace**; no tables are created for them.

⚠ **`plate` is neither renamed nor repositioned**. It is one instance of `Container`, not the
whole of Material — stating that boundary now is what saves the first phase from having to be
torn down when samples, reagents and waste arrive later.

### 4.2 The four dimensions of a container + three boundaries

| Dimension | Where it lives | This round |
|---|---|---|
| **Identity** (barcode / plate format) | `plate.barcode` / `plate_type` / `rows` / `cols` | exists. **`plate_type` is not touched** (it is a specification, not a process role) |
| **Plate-level process stage** | `plate.process_state` | **new** (v1 called it `content_state`, v2 renames it) |
| **Consumable state** | `plate.seal_state` (the seal) | **new** (the independent consumable entity is deferred to S5) |
| **Attribution** | `plate.current_run_id` / `origin_run_id` / `origin_node_key` | **new** (the three concepts are in §4.5) |

**Why the rename `content_state` → `process_state`**:
"content" suggests it describes what the plate holds, and that is exactly `plate_well`'s job.
"process stage" accurately says it describes **where this plate sits in the process**.

**Three boundaries that must be nailed down in this round (otherwise it fights `plate_well`)**:

| # | Boundary |
|---|---|
| ① | This round describes the **process stage of the whole container** (one stage per plate, homogeneous) |
| ② | It **does not represent well-level sample content** — that is `plate_well`'s source of truth; the two **must not contradict each other** |
| ③ | It **does not represent volume / concentration or other physical-chemical attributes** — that is `sample`'s source of truth |

**The definition of "how many empty plates are left"**: decided by `plate_well`
(no well rows, or all wells with `status='empty'`); **no separate plate-level `empty` state**
(otherwise there are two sources of truth).

### 4.3 The plate-level process stage dictionary: tenant level + a template set

| Decision | Choice | Reason |
|---|---|---|
| Ownership of the dictionary | **tenant level** (carries `tenant_id`) | the stage names are the customer's process vocabulary; the system has no right to prescribe them |
| Built in? | a **global template set** (`is_template=true`), copied into tenant copies when a tenant is initialised | building 18 stages from scratch is a terrible experience; but the template is only a "suggestion" |
| Unique identity | `code` (machine identifier) + `display_name` (what the customer sees) | renaming must not break the references |
| Deletion | **deactivate only** (`enabled=false`), never a physical delete | deleting a stage that is referenced breaks the historical display |
| Reference constraint | a **composite foreign key** (or equivalent check) from `plate.process_state` to `(tenant_id, code)` | a state that is not in the dictionary must not be possible |

⚠ **The state of tenant isolation**: `WorkflowWebService.TENANT = 1L` is hard-coded ⇒
**the fact that a new table carries `tenant_id` does not mean isolation is in place**. The
dictionary and state reads and writes of the new code must go through one tenant context, and
**this has to be recorded as a known problem of its own (§13)**.

### 4.4 Consumables: a wider criterion

| | Criterion | Conclusion |
|---|---|---|
| v1 criterion | does it need its **position** tracked independently | too narrow |
| **v2 criterion** | does it need an **independent identity or lifecycle trace** (position, batch, stock, attachment history, reusability, quality traceability …) | the reviewers' wording is adopted |

⇒ **Seal**: peeled off and discarded, not tracked separately ⇒ one `plate.seal_state` column.
⇒ **Lid**: has its own barcode, stock and attachment history ⇒ needs an entity of its own.
⇒ **The `consumable` table is not created in this round** (v1 created it) — the reviews agree
it needs its own position and attachment-history model, and **freezing an incomplete structure
is the wrong move** ⇒ the whole table is deferred to S5 (§14).

### 4.5 Attribution: three concepts, never mixed

| Concept | Where it lives | Meaning | Overwritten? |
|---|---|---|---|
| **Current attribution** | `plate.current_run_id` | who manages it now | **yes** |
| **Production source** | `plate.origin_run_id` / `origin_node_key` | who produced it in the first place | **no** |
| **Processing history** | `plate_material_event` | one row per change | append only |

**Why they must be split**: after a plate has been processed by A (amplification), B (purification)
and C (detection), the system must be able to answer "who produced it (A)", "who processed it
last (C)" and "who owns it now". With one set of `owner_*` columns every migration overwrites
the previous value ⇒ **the production relation is lost for good**.

⇒ **`/api/runs/{id}/material-effects` queries by the `source_run_id` of the event / effect
records**, **not** by `plate.current_run_id`; the wording changes to "**the material results /
state changes this run declared**" and no longer says "newly produced plates".

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
⇒ **The goal becomes**: **the execution fact must first become a recoverable record; the
material state transition must be idempotent, replayable and isolated on failure.**
Whatever the database can commit atomically should go in one transaction.

### 5.2 Three-layer division of labour (no new execution engine)

| Layer | Responsibility | Change this round |
|---|---|---|
| `GraphRunner` | graph progression, loops, branches, pause, resume | **never touches the database** (as today) |
| `RunStateMachine` / `StepExecutor` | executing nodes, retries, device commands | add a **material effect notification** (§5.7) |
| `PositionLock` / `CommandLedger` | position locks, command records, physical execution trace | **untouched** (position ≠ content) |
| **MaterialEffectService** (new) | material inputs and outputs, state transitions, material audit, idempotence | **a new domain service** that consumes execution facts |

⚠ **Explicitly forbidden**: do not push the material state update into `PositionLock` — that
would couple position management and process-content management all over again.

### 5.2b The two-level failure semantics (v3 new · the key point of the convergence)

**The failure consequences of T1 and T2 are completely different.** v2 did not say this, and
it is what the re-review chased down as a P0:

| Stage | Failing means | Consequence | Basis |
|---|---|---|---|
| **T1** (persisting the execution fact + the migration intent) | **the physical action happened but the system kept no record at all** | **the whole run must be blocked and escalated to manual** — without a record there is no basis for compensation, and "run it again" would perform the physical action a second time | C6 / C9 |
| **T2** (applying the state projection) | the physical action happened and is recorded, only the **projection** did not catch up | **the node still counts as completed** (the device really did finish), but **the material is marked as awaiting reconciliation** and **the downstream that depends on it must be blocked** | D4 / D3's `NEEDS_RECONCILIATION` |

⇒ **What mechanism blocks the downstream**: ⚠ **`runtime_hold` cannot be used** — the existing
`runtime_hold.scope` only has `experiment | island` (**checked against the real database**,
2026-11-12), **with no run / node granularity** ⇒ using it can only hold **the whole experiment
or the whole island**, which is far too coarse and stops unrelated work as well.

⇒ **The correct landing (without widening `runtime_hold`'s scope)**:
① the node-level fact is recorded in **`run_node.material_sync_status`**
(`pending` / `failed` / `needs_reconciliation`);
② **before dispatching a successor node, the scheduler checks the material sync status of the
predecessor nodes it depends on** — the predecessor's device action is complete but its material
is not synced ⇒ **the successor is not dispatched**, and waits for compensation / reconciliation;
③ when **the whole experiment** has to be stopped (a human judges that a material anomaly has a
wide blast radius), only then use `runtime_hold`'s `experiment` granularity — that is its
correct use.

⇒ **No new `node_status` enum value is introduced** (that is a contract inside the release gate,
changing it is a big surface): "the device finished but the material is not synced" is expressed
by `material_sync_status`, and "the successor must not run" is expressed by the **dependency
check** — the two are kept apart.
⇒ The site sees "the device finished, but this batch of material has not been reconciled and the
work behind it was not dispatched" — **not** a fake device failure.

### 5.3 T1: execution fact confirmation

The device command result, the node completion and a **pending material effect record**
(`PENDING`) are persisted.

**What the effect record must hold** (not just an `applied` flag on the audit table):

| Field | Why it must be there |
|---|---|
| declaration snapshot (`declaration` JSONB) | if the declaration changes while the run is going, the continuation and the compensation must interpret the old physical action by the **declaration of that time** |
| **the actually resolved `plate_id`** | the compensation replays by it and **does not re-run `target.by=position`** (the plate may have moved) |
| idempotence key + `payload_hash` | same key + same payload may be skipped safely; **same key + different payload must raise an error** |
| `source_run_id` / `source_node_key` / `source_loop_index` | attribution and round |

**Run version snapshot**: when the run starts, the **effective declaration and the workflow
version** are frozen; a continuation reads only the snapshot and **never the newest design**
(otherwise an old physical action would be interpreted by a new declaration).

### 5.4 T2: the material state projection

**In one transaction**: `lock the plate → apply the transition → write the event → mark APPLIED`.

| Failure case | Behaviour |
|---|---|
| the migration fails | the effect record stays `FAILED` with an error code, **an alarm and an escalation to manual**; **never silently swallowed** |
| the plate is not found / the attribution does not match | **isolate and escalate to manual, do not guess** (§6.3) |
| a manual inventory and an automatic migration collide | there must be an explicit conflict rule (§5.6); **a silent "last writer wins" is forbidden** |

### 5.5 The idempotence key (v3 adopts `execution_id + effect_index`)

```
execution_id = (tenant_id, source_run_id, source_node_key, source_loop_index)
idempotence key = execution_id + effect_index
```

**Why the anchor changed** (adjudication D1): v2 used `run+node+loop+plate`, which has two
holes — ① a node that **produces several effects** has no distinguishing slot;
② "the plate" is not the natural unit of an effect (one effect may involve several materials).

⇒ `effect_index` is the **effect ordinal within one execution** (a node declaring three
effects has 0/1/2), and how many plates an effect touches is written inside its own
declaration ⇒ **the idempotence key is decoupled from the plate**.

| Design point | Reason |
|---|---|
| **`source_loop_index` goes into `execution_id`** | the `run_node` unique key already includes `loop_index` (`20_kernel.sql:328`) ⇒ the same node running three times in a row is **three legitimate effects** and must not be eaten by the idempotence key |
| **`attempt` does not go into `execution_id`** | a device / node **retry** is the Nth attempt of the same logical execution ⇒ it must not produce a new material transition |
| **same key + same payload may be skipped; same key + different payload must raise an error** | silently swallowing "the design changed" is the most dangerous failure mode |
| **`payload_hash` must be stored** | it is the basis for deciding "same key, same payload?" |

⚠ **`source_loop_index` takes GraphRunner's in-memory `visit`, not `run_node.loop_index`** —
worked out by reading the source on 2026-11-12 (`docs/评审记录/20261112-04-openai.md` O-4):

| Fact | Evidence |
|---|---|
| `visit` starts at **1** | `GraphRunner.java:536` `visits.merge(key, 1, Integer::sum)` |
| the round row is written with that very `visit` | `GraphRunner.java:657` `roundRecorder.record(key, visit, …)` |
| the row lands as `loop_index = round` ⇒ **1..N**; the **summary row is 0** | `WorkflowService.java:1020`, `1904-1921` |
| ⇒ `visit` and the **round row's** `loop_index` agree | the two lines above |
| ⚠ **but `roundRecorder` is only called when `graphHasCycle && visit >= 1`** | `GraphRunner.java:654` |
| ⇒ **in an acyclic graph the node only has the summary row (`loop_index=0`) and never gets a 1..N round row** | same |

⇒ **Reading `run_node.loop_index` out of the database yields an inconsistent value** (the same
node_key has a summary row 0 and round rows 1..N; an acyclic graph has only the summary row)
⇒ the idempotence key must use the **in-memory `visit`**.
⇒ The benefit of keeping `attempt` out of the key also becomes explicit: **a device retry does
not change `visit`** ⇒ retrying one logical execution N times keeps the same `execution_id`
⇒ the effect is applied only once ✓

⚠ **v1's `(owner_run_id, owner_node_key, plate_id)` makes the third loop iteration hit the
unique index** — that is a hard defect.

### 5.6 Concurrency: serialise per plate

A unique index only prevents "the same idempotence key twice"; it does **not** prevent two
different nodes (or a human) from changing the same plate at the same time.

⇒ Inside T2, take **`SELECT ... FOR UPDATE`** on the `plate` row, or use a **`state_version`
CAS**.
⇒ The `from_*` values in the event row must match what was actually updated (otherwise "both
event rows look successful while the later commit overwrote the earlier one").
⇒ When an automatic migration collides with a manual change: **who fails and how it alarms must
be explicit**.

⚠ The existing `PositionLock` solves **position mutual exclusion**; that is **not** the same as
solving concurrent updates of the material state.

### 5.7 The completion point: both paths must be covered, and exceptions must not be swallowed

**This is the point v1 got wrong and v2 corrects.**

| Candidate | Coverage | Problem |
|---|---|---|
| `GraphRunner.java:650` (device TASK completion) | the **graph path** only | the sequential path (`sm.executeAll`) **does not go through it** |
| `GraphRunner.java:604` (builtin node completion) | builtin nodes only | device tasks are not there (v1's error E1) |
| the `NODE_COMPLETED` event of `ExecutionListener` | **both paths are covered** ✓ | ⚠ `emit()` **catches `RuntimeException` and ignores it** ("a listener failure must not affect the execution") ⇒ it cannot carry an action that "must leave a pending intent behind" |

⇒ **v3's scheme (correcting v2's wording)**: before `RunStateMachine.executeNode` returns
successfully, fire a **dedicated material effect notification**, `materialEffectListener`;
both `GraphRunner` and `WorkflowService` inject it on their path.

| Decision point | Explanation |
|---|---|
| **How it differs from `ExecutionListener`** | `ExecutionListener` is "the side-effect outlet" and **its failure must not affect the execution** (`emit()` catches the exception and ignores it); the material effect notification **must be able to reflect a failure** ⇒ the two semantics are opposite and **must not be merged** |
| **How exceptions are handled (v3 correction)** | v2 wrote "exceptions are not swallowed, they propagate to the caller" — **that is wrong**: if a failed material effect propagates up to `executeNode`'s caller it **aborts the whole run**, while the device action has already happened. ⇒ **The correct approach: the material effect handler swallows its own exception, but it must leave a PENDING record + an error code + an escalation to manual**; **only T1 (where even the PENDING cannot be written) may propagate and block the run** (see §5.2b) |
| **Where it fires** | after "the device action has completed" and **before** the node is marked completed ⇒ when PENDING cannot be written the node does not falsely count as complete |
| The round on the sequential path | the sequential path has no loop ⇒ `source_loop_index = 0` |
| What happens to `ExecutionListener` | **left exactly as it is** (it is "the side-effect outlet" semantics; do not change it) |

### 5.8 Compensation and recovery

| Scenario | Behaviour |
|---|---|
| crash before T1 | nothing happened ⇒ the device action did not happen either ⇒ just re-run |
| crash after T1 / before T2 | the effect record is `PENDING` ⇒ the compensation job replays T2 by the **already resolved `plate_id`** (no device action re-run, no position addressing re-run) |
| T2 rolled back | the effect record goes back to `PENDING` ⇒ same replay |
| repeated compensation | the idempotence key blocks it ⇒ same key + same payload is skipped |
| the flow has already continued when the compensation runs | the effect record is decoupled from the run ⇒ the compensation does not need the run to still be alive |
| database exception | **must not carry on silently** ⇒ an error code is recorded and it escalates to manual |

**`FAILED → PENDING` is a permission-controlled recovery operation, not an ordinary field
update** (the review's O-1 requirement):

| Item | Rule |
|---|---|
| who may recover | a role with recovery permission (an automatic compensation job must not just change it) |
| is a reason required | **yes** (`note` / `error_message` keeps the failure reason of that moment) |
| the audit of each attempt | every recovery **appends** a `RECONCILIATION` event and **never overwrites** the failure record |
| concurrent recovery | only one recovery job may run per `execution_id` (the row lock / `state_version` blocks it) |
| what a recovery does | **only reprocess the material state projection**, and **never re-executes the device action** |

### 5.9 The relation between the node state, the effect state and the run state (v3 adopts D4)

**The three are not equivalent** and must be looked at separately:

| Device execution | Material effect | Node state | Effect state | Result |
|---|---|---|---|---|
| success | achieved | `completed` | `APPLIED` | carry on normally |
| success | **awaiting reconciliation** | `completed` | `NEEDS_RECONCILIATION` | the node counts as complete, **the downstream is not dispatched** (the scheduler checks the material sync status of the predecessor nodes) |
| success | failed | `completed` | `FAILED` | the node counts as complete, **the downstream is held**, waiting for compensation / reconciliation |
| failure | no effect produced | `failed` / `manual` | — | the node fails, the existing recovery policy applies |
| **timeout** | **result unknown** | `manual` | `NEEDS_RECONCILIATION` | **it must not simply be treated as "the action did not happen"** (the device may have finished) |

⇒ **What "the node is fully complete" means**: a node that explicitly declares `NONE` does not
wait for an effect; a node that declares an effect **only counts as fully complete once the
effect reaches the required confirmation level**.
⇒ **No new `node_status` enum value is introduced for this**: `run_node.material_sync_status`
expresses it, and the **scheduler's dependency check** expresses "the downstream is not dispatched".
⇒ **Whether the run may continue** is decided by the flow dependency relation, the node
execution result and the material consistency **together** — **neither turning every material
problem into a failure of the whole run, nor letting the successors of an anomalous material
keep running.**

---

## 6. The material effect contract of a node

### 6.1 A dedicated column, not inside `params`

**Why it cannot go into `params`**: the execution side only reads
`workflow_node.params->'method_params'` and parses it with a **flat JSON parser** before
handing it to the driver ⇒ a nested structure placed there would be misparsed and **could even
be passed on to the device**. And `produces` is **the control system's own business contract**,
not a device execution parameter.

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
| `inputs` / `outputs` | the material identity plus the result. **This round only supports a one-to-one state change** |
| `operations` | reserved: aliquoting (1→N), mixing (N→1), plate transfer (1→1 new plate), sampling for detection (1→1 + data) |

⚠ **Aliquoting and mixing are not implemented this round**, but the **structure does not seal
off the extension space** (the review's requirement).

⚠ **"only one-to-one is supported this round" is a restriction of the _validation rules_, not of
the table structure** (the review's O-5 requirement): `inputs` / `outputs` and `operations` are
**arrays** ⇒ the structure natively supports multiple inputs and outputs; this round merely
**rejects, in the validation layer**, a declaration carrying more than one input or output.
⇒ Opening up SPLIT / MERGE later **requires no table change** — only relaxing the validation and
implementing the operation semantics.

### 6.3 Resolving the target: the material identity comes first, the position is only a helper

| Rule | Explanation |
|---|---|
| **The material identity comes first** | `plateId` / `barcode` is the identity; **a position is a spatial attribute, not an identity** |
| Position addressing must check the attribution | inside the transaction verify `tenant_id + resource_id + occupied_by_run` (**fail-closed**, the same standard as the existing plate-taking path) |
| Barcode addressing must check too | verify the expected position / attribution, unless there is an explicit cross-run hand-over operation later |
| The `AUTO-*` placeholder identity | a position registered without a barcode gets one ⇒ it **must not be treated as a verified barcode** |
| Device re-assignment | the `resourceId` written at design time is not necessarily the actual target ⇒ **the command parameters, the device response and the final position are the runtime evidence** |
| Resolution fails | **isolate and escalate to manual, do not guess** |
| Multi-step nodes | the target must define **which step's end position** it means; the position at the end of the node cannot be used to infer what the whole node worked on |

⇒ **T1 must persist the resolved `plate_id` into the effect record**, and everything after that
follows it.

**The priority of the evidence (the review's O-3 requirement: `occupied_by_run` alone does not
prove the physical identity)**:

| # | Evidence | Explanation |
|---|---|---|
| 1 | an **explicit** `plateId` / `barcode` in the declaration | the strongest — the plate a person or the designer named |
| 2 | the material identifier **in the device command response** | the device says so (fresher than the position projection) |
| 3 | the target position in the command parameters + the position projection | the weakest — **a helper only** |
| 4 | `occupied_by_run` | **proves only "this run holds it", not "physically it is it"** |

⇒ **When the position state and the device command evidence disagree**: **neither is
automatically trusted** ⇒ the effect goes to `NEEDS_RECONCILIATION` and a human reconciles it.
⇒ **When the plate physically moved while the device was executing** (the position projection
is updated at a different moment than the material state handling), the evidence at #2 takes
priority over #3 — otherwise the wrong plate would be resolved.

### 6.4 Explicitly declared vs not declared

| Case | Behaviour |
|---|---|
| explicitly no material side effect | the node declares `materialEffect: "none"` (or the equivalent) ⇒ **no alarm** |
| an old flow declares nothing | **WARN** (no behaviour change); in the new version this stays a WARN |
| a node known to change the content / the seal declares nothing | **ERROR** (blocked at publish / start) |
| a node that cannot be classified | a human has to confirm `none` or add a declaration |
| the declared state is not in the dictionary | **ERROR** (blocked at publish / start); **if it is deleted at run time ⇒ the migration fails and escalates to manual — it must not write "unknown state" and pass as success** |

⚠ **Why it cannot simply be a WARN everywhere**: v1 said "the system cannot tell a transfer
from a forgotten declaration" — that is not accurate. **The capability / method metadata can
mark nodes at design time** as "position-only / may change the content or the seal / unknown",
used **only for validation, never for guessing the state at run time** ⇒ with the classification
there is no need to treat every node the same way.

---

## 7. Data model summary

```sql
-- ① Plate-level process stage dictionary (tenant level; templates and tenant copies apart)
CREATE TABLE plate_process_state (
  id           bigserial PRIMARY KEY,
  tenant_id    bigint      NOT NULL,
  code         varchar(32) NOT NULL,
  display_name varchar(64) NOT NULL,
  sort_order   int         NOT NULL DEFAULT 0,
  is_template  boolean     NOT NULL DEFAULT false,  -- global template (belongs to no tenant)
  enabled      boolean     NOT NULL DEFAULT true,   -- deactivate, never delete
  UNIQUE (tenant_id, code)
);
-- Note: the template set is stored with tenant_id = 0; a new tenant gets copies.

-- ② plate gains columns (★ plate_type is NOT touched — it is the plate format 96/384/24/tube_rack)
ALTER TABLE plate
  ADD COLUMN process_state   varchar(32),            -- → plate_process_state.code
  ADD COLUMN seal_state      varchar(16) NOT NULL DEFAULT 'none',  -- none / sealed
  ADD COLUMN current_run_id  bigint,                 -- current attribution (overwritten)
  ADD COLUMN origin_run_id   bigint,                 -- production source (never overwritten)
  ADD COLUMN origin_node_key varchar(64),
  ADD COLUMN state_version   bigint NOT NULL DEFAULT 0;  -- for the CAS
-- Reference constraint: process_state must exist in the same tenant's plate_process_state.
-- ⚠ With MATCH SIMPLE (the PG default): when process_state is NULL the foreign key is **not**
--   enforced ⇒ old data (unknown) can still be written, whose meaning is "unknown" rather than
--   "illegal". That is exactly what we want.
ALTER TABLE plate ADD CONSTRAINT fk_plate_process_state
  FOREIGN KEY (tenant_id, process_state)
  REFERENCES plate_process_state (tenant_id, code);
ALTER TABLE plate ADD CONSTRAINT ck_plate_seal_state
  CHECK (seal_state IN ('none','sealed'));

-- ③ Node level: physical completion and material sync are apart (v3 adopts the independent v2)
ALTER TABLE run_node
  ADD COLUMN physical_completed_at timestamptz,      -- when the device/node physical action finished
  ADD COLUMN material_sync_status  varchar(32) NOT NULL DEFAULT 'not_required',   -- ⚠ needs_reconciliation is 20 characters ⇒ 16 is too narrow
  ADD COLUMN material_error_code   varchar(64);
ALTER TABLE run_node ADD CONSTRAINT ck_run_node_material_sync
  CHECK (material_sync_status IN ('not_required','pending','applied','failed','needs_reconciliation'));
CREATE INDEX ix_run_node_material_sync
  ON run_node (tenant_id, material_sync_status)
  WHERE material_sync_status IN ('pending','failed','needs_reconciliation');
-- Semantics: physical_completed_at only means the physical action finished, **not** that the
--   material projection is complete; for old data status='completed' serves as the compatible
--   evidence of physical completion; material_error_code **does not overwrite** the device's
--   own error_code.

-- ④ Material effect (the migration intent): T1 writes PENDING → T2 applies
CREATE TABLE plate_material_effect (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL,
  execution_id      varchar(128) NOT NULL,   -- = tenant|run|node|loop (the logical execution instance)
  effect_index      int         NOT NULL,   -- the effect ordinal within one execution
  source_run_id     bigint      NOT NULL,
  source_node_key   varchar(64) NOT NULL,
  source_loop_index int         NOT NULL DEFAULT 0,
  source_attempt    int         NOT NULL DEFAULT 0,   -- recorded, not part of execution_id
  plate_id          bigint,                    -- the plate actually resolved in T1 (the compensation replays by it)
  declaration       jsonb      NOT NULL,      -- declaration snapshot (the run version is frozen)
  payload_hash      varchar(64) NOT NULL,      -- same key + different payload → error
  status            varchar(32) NOT NULL,      -- five states, see below (needs_reconciliation is 20 characters ⇒ 16 is too narrow)
  applied_at        timestamptz,
  error_code        varchar(64),
  error_message     varchar(255),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_effect_status CHECK (status IN
    ('PENDING','APPLIED','REJECTED','NEEDS_RECONCILIATION','FAILED'))
);
CREATE UNIQUE INDEX ux_plate_material_effect_key
  ON plate_material_effect (tenant_id, execution_id, effect_index);
CREATE INDEX ix_plate_material_effect_pending
  ON plate_material_effect (tenant_id, status, created_at) WHERE status <> 'APPLIED';

-- ⑤ Material events (immutable): one row per state change, append only
CREATE TABLE plate_material_event (
  id                 bigserial PRIMARY KEY,
  tenant_id          bigint      NOT NULL,
  plate_id           bigint      NOT NULL,
  event_type         varchar(32) NOT NULL,   -- process_state_changed / seal_changed / moved / retired ...
  from_process_state varchar(32),
  to_process_state   varchar(32),
  from_seal_state    varchar(16),
  to_seal_state      varchar(16),
  from_owner_run_id  bigint,
  to_owner_run_id    bigint,
  source_run_id      bigint,
  source_node_key    varchar(64),
  source_loop_index  int         NOT NULL DEFAULT 0,
  command_id         varchar(64),        -- the physical command id (the Execution Fact)
  effect_id          bigint,             -- the matching plate_material_effect
  evidence           varchar(24) NOT NULL,  -- evidence source (see below)
  operator           varchar(16) NOT NULL,  -- AUTO / MANUAL
  note               varchar(255),
  occurred_at        timestamptz,         -- **the physical time**
  recorded_at        timestamptz NOT NULL DEFAULT now(),  -- **the system recording time**
  CONSTRAINT ck_event_evidence CHECK (evidence IN
    ('DEVICE_CONFIRMED','EXECUTION_DECLARED','MANUAL_INVENTORY','MANUAL_CORRECTION','RECONCILIATION')),
  CONSTRAINT ck_event_operator CHECK (operator IN ('AUTO','MANUAL'))
);
CREATE INDEX ix_plate_material_event_plate  ON plate_material_event (tenant_id, plate_id, occurred_at DESC);
CREATE INDEX ix_plate_material_event_source ON plate_material_event (tenant_id, source_run_id);

-- Cross-tenant constraints (the review's O-2 requirement): **the Service layer check alone is
-- not enough**.
-- ⚠ A composite foreign key needs a unique index on **exactly** those referenced columns: the
--   primary keys of plate / plate_material_effect are the single column id, so the
--   (tenant_id, id) unique constraints have to be added first, otherwise these foreign keys
--   cannot be created at all.
--   (Found on 2026-11-12: without uq_effect_tenant_id, fk_event_effect_tenant fails to be
--   created.)
ALTER TABLE plate ADD CONSTRAINT uq_plate_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT uq_effect_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE plate_material_effect ADD CONSTRAINT fk_effect_plate_tenant
  FOREIGN KEY (tenant_id, plate_id) REFERENCES plate (tenant_id, id);
ALTER TABLE plate_material_event ADD CONSTRAINT fk_event_plate_tenant
  FOREIGN KEY (tenant_id, plate_id) REFERENCES plate (tenant_id, id);
ALTER TABLE plate_material_event ADD CONSTRAINT fk_event_effect_tenant
  FOREIGN KEY (tenant_id, effect_id) REFERENCES plate_material_effect (tenant_id, id);
```

**The effect state machine (five states, v3 adopts the third design's D3)**:

| State | Meaning | What follows |
|---|---|---|
| `PENDING` | waiting to be processed | the compensation job / the next retry |
| `APPLIED` | the effect has been applied | terminal |
| `REJECTED` | **the effect violates the rules** (the target plate's attribution does not match, the declaration contradicts itself) | terminal, **escalate to manual**, no automatic retry |
| `NEEDS_RECONCILIATION` | **the physical fact is unclear** (the device timed out but the action may have happened) | wait for the reconciliation, **it must not simply be treated as "the action did not happen"** |
| `FAILED` | the processing failed (database / concurrency) | keep + retryable |

**The evidence-source enum** (`evidence`):

| Value | Meaning |
|---|---|
| `DEVICE_CONFIRMED` | confirmed by a device result (the state was actually read) |
| `EXECUTION_DECLARED` | migrated after the node finished, per the declaration (**the system said it, not the device**) |
| `MANUAL_INVENTORY` | manual on-site inventory |
| `MANUAL_CORRECTION` | a human correcting what the system recorded (**a new event is appended, the history is not overwritten**) |
| `RECONCILIATION` | repaired by a reconciliation / recovery job |

**Where the Execution Fact lives (v3 adjudication D2)**: **no new table** —
`run_command` already has `command_id` / `device_raw_response` / `ack_at` / `status` / `attempt`
and is the ready-made carrier of the execution fact; it only needs two semantic slots added
where necessary, `evidence_source` and `occurred_at`.
**`run_command` is the command ledger and the material event is the material ledger — the two
are not mixed.**

⚠ **The `consumable` table is not created in this round** (§4.4) ⇒ the independent consumable
is deferred to S5 as a whole.

⚠ **`plate_material_event` and `plate_location_audit` are two separate lines**: the former
records **content / attribution** changes, the latter records **position** changes; a single
transfer may produce only the latter.

**✅ This DDL has actually been executed (2026-11-12)**: it was run in the isolated schema
`labos_v2_ddl_check` and four constraints were verified; that schema was then dropped
(**no table of the production database was touched**).

⚠ **Honest note (corrected)**: what was executed the first time is the **v2 DDL**, and
**v3 changed four things** (two columns on `run_node`, the event table renamed to
`plate_material_event` with the two timestamps, and the effect table gaining
`execution_id`/`effect_index` plus the five-state CHECK).
⇒ **the v3 DDL has been re-run** (2026-11-12): the SQL was **extracted from this document
as written** and executed in the isolated schema `labos_v3_ddl_check`, which was then
dropped (**no table of the production database was touched**):

| Verified | Result |
|---|---|
| the whole DDL executes (**extracted from the document**) | ✅ tables / indexes / composite foreign key / CHECK all pass |
| the dictionary reference constraint | ✅ writing `process_state='no_such_state'` is rejected by `fk_plate_process_state` |
| the seal state CHECK | ✅ writing `seal_state='weird'` is rejected by `ck_plate_seal_state` |
| **the old-data semantics (`process_state` is NULL)** | ✅ **the write is allowed** — under MATCH SIMPLE a NULL does not enforce the foreign key, so "unknown" ≠ "illegal" |
| the effect idempotence unique key | ✅ a second insert with the same `execution_id` + `effect_index` is rejected by `ux_plate_material_effect_key` |
| the five-state CHECK | ✅ writing `status='BOGUS'` is rejected by `ck_effect_status` |
| the `run_node` sync status CHECK | ✅ writing `material_sync_status='bogus'` is rejected by `ck_run_node_material_sync` |

⚠ **The real defects this round caught (now fixed)**:

1. **In the v2/v3 first drafts the dictionary composite foreign key and the seal state CHECK
   were written as comments** — present on paper, **not in effect** in the DDL.
2. **The column was too narrow**: `needs_reconciliation` is **20 characters** while
   `material_sync_status` and `plate_material_effect.status` were both `varchar(16)`
   ⇒ **that state could not be written at all** (the measured error was
   `value too long for type character varying(16)`).
   ⇒ now `varchar(32)`. **This is a P0 that only running the DDL exposes** — the CHECK
   listed it all along.
3. **The cross-tenant foreign keys could not be created**: a composite foreign key needs a
   unique index on **exactly** those referenced columns, while the primary keys of `plate` /
   `plate_material_effect` are the single column `id`
   ⇒ `uq_plate_tenant_id` and `uq_effect_tenant_id` were added.

⇒ **Paper DDL that is never executed will never reveal these three kinds of problem.**

⇒ **this SQL is no longer prose**; but what was verified are **the constraints themselves**,
not the migration script of S1 (the back-fill of old data, index concurrency and the tenant
initialisation each need another round).

---

## 8. Interfaces

| Method | Path | Explanation |
|---|---|---|
| GET | `/api/plates?processState=&currentRunId=&originRunId=&resourceId=&plateType=` | query plates by stage / current attribution / production source / position / plate format |
| GET | `/api/plates/{barcode}` | one plate: current stage, seal state, current attribution, production source, position |
| GET | `/api/plates/{barcode}/state-history` | the material event history (paginated, including the evidence source and the physical / recording timestamps) |
| PUT | `/api/plates/{barcode}/state` | change the state by hand ⇒ writes the event with `evidence='MANUAL_INVENTORY'` / `'MANUAL_CORRECTION'` |
| GET | `/api/runs/{id}/material-effects` | **the material results / state changes this run declared** (queried by the `source_run_id` of the effect records, not by the current attribution) |
| GET | `/api/plate-process-states` | the stage dictionary (tenant level) |
| POST | `/api/plate-process-states` | add a stage |
| PATCH | `/api/plate-process-states/{code}` | rename / reorder / **deactivate** (the `code` may not change) |

---

## 9. Pages

| Page | Change |
|---|---|
| **Plate inventory** (`Plates.tsx`, exists) | add the "process stage / seal state / current attribution / production source" columns; the stage can be edited inline |
| **Plate traces** (`PlateTraces.tsx`, exists) | a new "state transitions" tab next to the existing "position transitions"; **the evidence source must be shown distinctly** |
| **Run detail** (`RunDetail.tsx`, exists) | a "material results of this run" block (with an evidence source column) |
| **Process designer** (`Designer.tsx`, exists) | a "material effect" section in the node settings panel (input / output material plus the resulting stage); preflight gets three WARN / ERROR classes |

---

## 10. Migration and compatibility

| Item | Approach |
|---|---|
| **Old data** | the new columns are null ⇒ the meaning is "**unknown**", not "empty" |
| **No historical back-fill** | the historical state is not known, and back-filling would be inventing data |
| **What the UI shows** | a null value shows as "unknown" |
| **The execution behaviour of old flows** | without a `material_effect` declaration the **execution behaviour is completely unchanged** |
| ⚠ **Four things still to be verified independently** | ① consistency between the old and the new queries; ② tenant isolation (**`TENANT=1L` is hard-coded today**, see §13); ③ the migration script and the schema artefacts come from the same source (`check-schema-sync.sh`); ④ the failure handling and the escalation-to-manual path |
| **Schema scripts** | through the `pg_rebuild/` shards plus `90_all_in_one.sql` |

---

## 11. Acceptance criteria

### 11.1 Function

| Question | How it is verified | Tier |
|---|---|---|
| After a node produces a plate, its stage changed | run a flow with a `materialEffect` ⇒ `plate.process_state` = the declared value | real environment |
| The migration is traced and the evidence source is visible | one row in `plate_material_event` with the right `evidence` | real environment |
| "Who produced it / who processed it last / who owns it now" can be answered | the three columns and the event history answer them separately | real environment |
| "Which material results did this run declare" can be answered | `GET /api/runs/{id}/material-effects` | real environment |
| Renaming a dictionary entry does not break the reference | change `display_name` ⇒ the display follows, the `code` does not change | pure logic |
| Old flows change behaviour not at all | run an existing flow from the database and compare with the behaviour before the change | real environment |

### 11.2 Failure scenarios (20 · the merged list of the three reviews, the three designs and the unified architecture V1.0)

| # | Scenario | The result that must be guaranteed |
|---|---|---|
| T01 | the device succeeded, the database write failed | **it must not silently show the material state as correct** ⇒ T1 fails ⇒ **the run is blocked and escalated to manual** (running it again would perform the physical action a second time) |
| T02 | the device timed out, but the action may have happened | **it must not simply be treated as "the action did not happen"** ⇒ the effect goes to `NEEDS_RECONCILIATION` |
| T03 | the same device command callback arrives twice | the effect is applied only once (the idempotence key blocks it) |
| T04 | the same node loops three times in one run | **all three real effects are traceable** (`execution_id` carries the in-memory `visit`) |
| T05 | one logical execution retries three times | **only one material effect is produced** (`attempt` does not go into `execution_id`) |
| T06 | the run crashes and recovers | the confirmed effects are not repeated; the unconfirmed ones **can be recovered** (replayed by the already resolved `plate_id`) |
| T07 | two runs modify the same plate at the same time | **no state is overwritten** (`FOR UPDATE` / `state_version` CAS) |
| T08 | two materials occupy the same position at the same time | blocked by the existing `PositionLock` (**reused, not rebuilt**) |
| T09 | the target position is already occupied | the migration fails visibly and escalates to manual, no guessing |
| T10 | the material cannot be found / the identity cannot be confirmed | **isolate and escalate to manual, cannot guess** (the position is only a helper, never the identity) |
| T11 | a manual inventory and an automatic migration collide | there is an explicit conflict handling, **no silent overwrite** |
| T12 | the declaration disagrees with the device execution result | the effect goes to `REJECTED` and escalates to manual, **the declaration is not written blindly** |
| T13 | the node succeeded but the effect processing failed | the node still counts as completed (no fake device failure), **the downstream is not dispatched** (the scheduler checks the predecessor's material sync status) |
| T14 | the state update succeeded but the event record failed | the two are **in one transaction**, either both or neither (a "state changed but no history" is not allowed) |
| T15 | a cross-tenant material or dictionary reference | **must be rejected** (composite foreign key / tenant context check) |
| T16 | recovering `FAILED → PENDING` | **permission, reason and audit are complete**; every attempt appends a `RECONCILIATION` event and never overwrites the failure record |
| T17 | the sequential path and the graph path | they enter the **same** state handling mechanism (not two) |
| T18 | a multi-step node execution | the intermediate execution facts and material impacts are **not missed** |
| T19 | an old callback arrives after a manual correction | it **does not overwrite** the manually confirmed state |
| T20 | the run terminates abnormally and the position is still held | it can be safely reconciled and released |

⚠ Any one of them without an explicit handling mechanism means the material state management
**cannot be claimed production-ready**.

---

## 12. Implementation slices

| Slice | Content | Depends on | Produces |
|---|---|---|---|
| **S0** | **The domain model is finalised** (no tables): the Material layering and the three boundaries; the three attribution concepts; the tenant model of the stage dictionary and the template set; the `execution_id` / `effect_index` idempotence definition; the model for execution facts and material effects; the **two-level failure semantics**; the completion-point scheme | — | the model is settled; **no code until the review passes** |
| **S1** | Basic data structures: the stage dictionary, the new `plate` columns, the two new `run_node` columns, the effect table, the event table, the constraints and indexes, the migration script. **Not wired into the execution engine** | S0 | the tables are in place, zero behaviour change |
| **S2** | The execution fact and material effect engine: the material effect notification (both paths covered, **a failure does not abort the execution but a PENDING must be left**), T1/T2, the five-state effect state machine, idempotence, loops, retries, isolation, compensation, the scheduler's material dependency check | S0, S1 | **the behaviour starts to change** |
| **S3** | Material query and traceability: the interfaces, the columns on the inventory page, the state transition history, the material results on the run detail page | S2 | the site can look it up |
| **S4** | The process designer: the material effect section, preflight, the node impact classification | S2, S3 | the site can configure it |
| **S5** | The independent consumable domain: the `consumable` model (position + attachment history + state machine), the lid stock and handling | S0 | lids become traceable |

---

## 13. Known problems and open questions (the honest list)

| # | Question | Impact | State |
|---|---|---|---|
| 1 | **Tenant isolation is not actually implemented** (`WorkflowWebService.TENANT = 1L` is hard-coded) | a new table carrying `tenant_id` does not mean isolation is in place; the dictionary and the state reads and writes have to guarantee the tenant context themselves | **a known fact**; the review has to confirm how this round covers for it |
| 2 | The completion notification means **changing `RunStateMachine` on both paths** | the change is bigger than v1 assumed (v1 thought there was a ready-made hook) | the review has to confirm the scheme |
| 3 | The **state machine of the stages** (which state may follow which) is **not done this round** | only the dictionary and the reference constraint, no validation of a transition's legality | a known trade-off; the review has to confirm it is acceptable |
| 4 | The **consistency check between the well-level sample and the plate-level stage** is not done this round | the two sources of truth may disagree | the review has to confirm the boundary is tight enough |
| 5 | The **classification metadata** (at the capability / method level) behind "declared vs not declared" is not done this round | the site may misconfigure it broadly | the review has to confirm the transition period is acceptable |
| 6 | The **cross-process attribution hand-over** (§4.5) is **not implemented** this round | a plate crossing a process boundary still has no traceable production source | stage 3's content; the columns are reserved |

**Conflicts with existing documents** (per `docs/评审规范.md` §7: a ②③ conflict ⇒ change the
specification first):

| Conflict | Disposition |
|---|---|
| `LabOS功能规格说明书.md` §7.2 "2.0 delivers sequence only" | this round only adds container dimensions, it **does not touch process orchestration** ⇒ no conflict; a "known boundary" note pointing at the gap document was added |
| `docs/板位级占用与位置互斥-设计.md` §5 "plate-level tracking (where the plate is)" | this round adds "which step of the process the plate is at"; the two complement each other; a pointer to this document should be added there |
| `docs/工艺编排能力缺口与落地路线.md` §4.2 (the G2 criterion) | **corrected per the reviews**: the criterion is widened to "independent identity or lifecycle traceability"; §4.4 here is the corrected version |

---

## 14. Explicitly not in this round

| Not done | Reason |
|---|---|
| The `consumable` table | it needs its own position and attachment-history model, and **freezing an incomplete structure is the wrong move** ⇒ the whole thing is deferred to S5 |
| The station layer (`station_id`) | stage 2; addressing by `resource_id` plus an attribution check covers most cases this round |
| Material hand-over across processes | stage 3; this round only reserves the attribution columns |
| Events / triggers / setpoints | stage 4; a paradigm change with the highest risk |
| Aliquoting, mixing, dynamic expansion | the contract reserves `operations`, but they are not implemented |
| The state machine of the stages (legality of a transition) | it can only be defined once there is a real dictionary |
| The consistency check between well-level samples and the plate-level stage | it needs a more complete well-level data model |
| A separate "material" overview page | re-evaluate once the dictionary and the stages are in use |

---

## 15. List of decision points (v2 states a conclusion for each)

| # | Decision point | v1's suggestion | **v2 conclusion** | Basis |
|---|---|---|---|---|
| 1 | The transaction boundary between node completion and the state transition | must be the same transaction | **changed**: execution facts + a two-phase material effect (T1/T2); no reliance on a single transaction | all three reviews agree, and the current architecture cannot do a single transaction |
| 2 | Ownership of the dictionary | tenant level + a recommended set | **accepted**, and **extended** with a composite foreign key, deactivate-instead-of-delete, and templates/copies apart | Grok / Kimi / OpenAI agree |
| 3 | The criterion for splitting consumables | does it need its position tracked independently | **changed**: does it need an independent identity or lifecycle traceability | OpenAI + Kimi |
| 4 | `produces` inside `params` or a dedicated column | inside `params` | **changed**: a dedicated column `material_effect` | OpenAI + Kimi (`params` is handed to the device) |
| 5 | Should a missing declaration block | do not block | **basically accepted, but the reasoning changes**: classify by node impact (position-only / may change the content / unknown); a node known to change the content that declares nothing ⇒ ERROR | all three reviews point out that "the system cannot tell" does not hold |
| 6 | The six `plate_type` values | change it to six process roles | **rejected**: `plate_type` keeps meaning the **plate format**; a process role would be another column, and this round does not add one | Grok + OpenAI + Kimi |
| 7 | Are the consumables (S5) in this round | a separate slice | **accepted**, and the **whole table is deferred** (not just the execution) | Kimi + OpenAI |

**The two new adjudications of the v3 three-way convergence**:

| # | Decision point | **v3 conclusion** | Basis |
|---|---|---|---|
| 8 | **The anchor of the idempotence key** | **`execution_id + effect_index`** (`execution_id = tenant\|run\|node\|loop`; `attempt` does not go into it) | the third design's anchor is the most stable: it is decoupled from the plate and natively supports several effects per node; keeping `attempt` out guarantees a retry produces no new effect |
| 9 | **The relation between the node completion and the effect** | a node explicitly declaring `NONE` does not wait for an effect; a node that declares an effect **only counts as fully complete once the effect reaches the required confirmation level**. **No new `node_status` enum value**: `run_node.material_sync_status` expresses it and the **scheduler's dependency check** decides whether the downstream is dispatched | the third design's D4; ⚠ **not `runtime_hold`** (its scope only has experiment/island, too coarse) |

**Three more convergences (not decision points, they are wording)**:
① the effect state machine takes **five states** (adding `REJECTED` / `NEEDS_RECONCILIATION`);
② the event table is named **`plate_material_event`** and separates `occurred_at` (the physical
time) from `recorded_at` (the system recording time);
③ **the Execution Fact gets no new table** — `run_command` is reused (it already has
`command_id` / `device_raw_response` / `ack_at`), the material event gets its own ledger, and
the two ledgers are not mixed.

---

## 16. The five hard gates of S0 (must be done before coding starts)

> From the unified architecture V1.0 (`docs/评审记录/20261112-04-openai.md` O-6).
> **All five must be complete before S0 is frozen and S1 creates any table.**

| # | Gate | Where it stands today |
|---|---|---|
| 1 | **The recovery state machine is explicit**: including the human reconciliation of `FAILED → PENDING`, the permission, the reason and the audit | ✅ §5.8 has it (a controlled recovery operation + one audit row per attempt); **the permission model has to be defined during implementation** |
| 2 | **The tenant constraints are explicit**: the cross-tenant composite constraints of the key entities | ✅ §7 adds three composite foreign keys (effect↔plate, event↔plate, event↔effect); ⚠ the prerequisite is `uq_plate_tenant_id` |
| 3 | **The identity resolution is explicit**: the priority among the device command evidence, the position state and the Run occupancy conflict | ✅ §6.3 has the four-level evidence priority + a conflict going to `NEEDS_RECONCILIATION` |
| 4 | **The execution paths are explicit**: every execution way enters the unified handler, and the loop / retry semantics are verified against the source | ⚠ **partly met**: §5.5 pins where the round comes from (the in-memory `visit`); **the six execution paths still have to be proven against the source in S0** (see §16.1) |
| 5 | **The extension boundary is explicit**: one-to-one is allowed in the first phase, but multi-input / multi-output must not be permanently blocked | ✅ §6.2 states it plainly: "the restriction is in the validation layer, not in the table structure" |

### 16.1 The questions that still have to be verified against the source (four groups)

> **Until they are verified, the new fields / interfaces / states proposed by the design must not
> be described as "capabilities the system already has".**

| Group | To be verified |
|---|---|
| **Execution paths** | normal graph execution / sequential execution / multi-step nodes / loop nodes / device retries / manual recovery — do all six enter the unified completion handler? |
| **Device evidence** | does the device provide a stable `command_id`; can the final command state be queried; after a timeout can it be told whether the physical action happened; is the device response enough to support the material state transition? |
| **Data consistency** | when the node execution state is written; the transaction relation between the Effect and the Event; can the Run finalisation miss an unprocessed Effect; does the execution record survive a failed DB commit? |
| **The existing domain model** | the real relation between `plate` / `position_state` / `PositionLock`; the actual structure of `plate_well`; the position occupancy and release logic; whether there are other implicit material state fields; how the tenant context is actually implemented |

### 16.2 The things that must stay UNKNOWN (no "we will refine it later" in place of a decision)

| Item | Why it is UNKNOWN right now |
|---|---|
| whether the device can supply strong "physically confirmed" evidence | it depends on the driver capability of each device; this round did not go through them one by one |
| what the `AUTO-<runId>` placeholder identity means in the material model | it is the fallback for a position registered without a barcode and **must not be treated as a verified barcode** ⇒ how the material side handles it is still open |
| whether a barcode may change or be duplicated | not verified against the current state |

---

## 17. The ten questions the review has to answer (answered, or explicitly marked UNKNOWN)

> From appendix B of the unified architecture V1.0. **Every question must come with a source
> reference, a design decision, or an explicit UNKNOWN.**

| # | Question | This document's answer |
|---|---|---|
| 1 | What field guarantees the stable identity of a plate? May a barcode change or be duplicated? | the stable identity is **`plate.id`** (the primary key); `barcode` is an **external identifier**, nullable and changeable ⇒ identity resolution prefers the id, and the barcode is only one of the resolution entries |
| 2 | Does the current state of a plate already have several sources? How is a single authoritative state determined? | there are already two sources: **`plate.status` (lifecycle)** and **`position_state` (position occupancy)** ⇒ this design **does not merge them** but **splits them into different dimensions** (§4.2); each dimension has its own single source of truth |
| 3 | What is the device's evidence of the material identity? | the **four-level evidence priority** of §6.3; the material identifier in the device response is #2; **when it is not strong enough the effect goes to `NEEDS_RECONCILIATION`**, nothing is guessed |
| 4 | How does an execution fact tell a loop, a retry and a duplicate callback apart? | **three dimensions**: a loop = the in-memory `visit`; a retry = `attempt` (**not in the idempotence key**); a duplicate callback = a replay of the same `execution_id` ⇒ the key `execution_id + effect_index` blocks it (§5.5) |
| 5 | After the device finishes, the database commit fails — how is that recovered? | the **two-level failure semantics** (§5.2b): a T1 failure blocks the run and escalates to manual; a T2 failure leaves the node completed, the material awaiting reconciliation and **the downstream not dispatched**; the compensation replays by the **already resolved `plate_id`** and **never re-runs the device action** |
| 6 | How does an old asynchronous callback avoid overwriting a manual correction? | the manual reconciliation **appends** a `MANUAL_CORRECTION` event (it does not overwrite the history); a replay of the old callback is recognised by the idempotence key as **already processed** ⇒ no overwrite (T19) |
| 7 | Do the sequential path and the graph path really share the same completion handler? | **by design they do** (the common completion point is `RunStateMachine.executeNode`); ⚠ **at the source level it still has to be proven that all six paths get there** (§16.1) ⇒ currently marked **PARTIAL** |
| 8 | How do PositionLock and the material state update coordinate? | **each manages its own**: the position / occupancy belongs to `PositionLock` (with the mutex and the transfer audit), the content / attribution belongs to the material projection; **they never rewrite each other** (§5.2 explicitly forbids pushing the material state into PositionLock) |
| 9 | Can the multi-tenant constraints be guaranteed by the database? | **yes, but only among the new entities**: the three composite foreign keys of §7 plus the dictionary composite foreign key ⇒ **a cross-tenant reference between the new material entities cannot exist**; ⚠ **across the existing entities** (`run_node` ↔ `run` etc.) it still rests on the Service layer ⇒ marked **PARTIAL** |
| 10 | How does a one-to-one Effect leave room for a future SPLIT / MERGE? | `inputs` / `outputs` / `operations` are all **arrays** ⇒ the table structure does not seal it; this round only restricts one-to-one in the **validation layer** (§6.2) |

---

*This is an **up-for-review** design; no code and no schema change has been made yet. Please
file the review record per §8 of `docs/评审规范.md`
(`docs/评审记录/YYYYMMDD-NN-<reviewer>.md`) and answer each point in that file.*
