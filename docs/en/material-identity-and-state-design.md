# Material Identity and State · Design (v2)

> **Status**: **up for re-review** (v1 raised 2026-11-11; v2 rewritten on 2026-11-12 after
> merging **all** the findings of three independent reviews)
> **Decision number segment**: this document claims **D401-D4xx** (to be merged into the segment
> table in `docs/评审规范.md` §7 once the review passes)
> **Related**: [Process Orchestration Capability Gaps and Landing Route](process-orchestration-gaps-and-roadmap.md)
> (the gap analysis, G1/G2/G11); review records `docs/评审记录/20261112-01-grok.md`,
> `20261112-02-kimi.md`, `20261112-03-openai.md`
> **Code baseline**: `main` @ `9b36868` (the v1 commit, which changed no code)
> **⚠ This is still a design; no code and no schema change has been made.**

---

## 0. Review entry point

| Item | Content |
|---|---|
| **What is being reviewed** | The domain model for material identity and state, the mechanism for execution facts and material effects, the data model, the interfaces and the pages |
| **Why now** | Checking against a real process flowchart showed that LabOS orchestrates "actions" while the real process orchestrates "material"; a container has an identity and a position but **no process stage** ⇒ the site cannot answer "which step of the process is this plate at", and material hand-over across processes cannot be expressed either |
| **Read these three first** | §3 (the facts) → §4 (the domain model) → §5 (execution facts and material effects) |
| **How v2 differs from v1** | v1 had **6 factual errors** and **4 model defects**, each found independently by at least one of the three reviews ⇒ v2 fixes all of them; the list is in §0.1 |
| **Review focus** | ① the failure semantics of the two-phase model in §5; ② the extension boundary of the contract in §6; ③ the boundary between plate stage and well-level sample in §4.2; ④ the idempotence key in §5.5; ⑤ the completion-point scheme in §5.7 (this round gives a more precise answer than the three reviews did) |
| **Known problems** | See §13 — **six open questions**, two of which would change the implementation |

### 0.1 The errors in v1 (publicly acknowledged, per `docs/评审规范.md` §4)

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
| Who produced this plate, and who processed it last? | ❌ cannot be answered. `plate` has no production-source column and `plate_state_audit` does not exist yet |
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
| The **`plate_well` / `sample` / `sample_aliquot` tables already exist**, all with 0 rows | `30_business.sql:17-58,78-93` |
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
| **Processing history** | `plate_state_audit` | one row per change | append only |

**Why they must be split**: after a plate has been processed by A (amplification), B (purification)
and C (detection), the system must be able to answer "who produced it (A)", "who processed it
last (C)" and "who owns it now". With one set of `owner_*` columns every migration overwrites
the previous value ⇒ **the production relation is lost for good**.

⇒ **`/api/runs/{id}/material-effects` queries by the `source_run_id` of the audit / effect
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
| `RunStateMachine` / `StepExecutor` | executing nodes, retries, device commands | add a **completion notification that does not swallow exceptions** (§5.7) |
| `PositionLock` / `CommandLedger` | position locks, command records, physical execution trace | **untouched** (position ≠ content) |
| **MaterialEffectService** (new) | material inputs and outputs, state transitions, material audit, idempotence | **a new domain service** that consumes execution facts |

⚠ **Explicitly forbidden**: do not push the material state update into `PositionLock` — that
would couple position management and process-content management all over again.

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

**In one transaction**: `lock the plate → apply the transition → write the audit → mark APPLIED`.

| Failure case | Behaviour |
|---|---|
| the migration fails | the effect record stays `FAILED` with an error code, **an alarm and an escalation to manual**; **never silently swallowed** |
| the plate is not found / the attribution does not match | **isolate and escalate to manual, do not guess** (§6.3) |
| a manual inventory and an automatic migration collide | there must be an explicit conflict rule (§5.6); **a silent "last writer wins" is forbidden** |

### 5.5 The idempotence key

```
idempotence key = tenant_id + source_run_id + source_node_key + source_loop_index + plate_id
```

| Design point | Reason |
|---|---|
| **includes `source_loop_index`** | the `run_node` unique key already includes `loop_index` (`20_kernel.sql:328`) ⇒ the same node running three times in a row is **three legitimate effects** and must not be eaten by the idempotence key |
| **`attempt` is not part of the key** | a device / node **retry** is the Nth attempt of the same logical execution ⇒ it must not produce a new material transition |
| **aligned with the `run_node` unique key** | `(run_id, node_key, attempt, loop_index)` ⇒ we take its `(run_id, node_key, loop_index)` plus `plate_id` |
| **same key + same payload may be skipped; same key + different payload must raise an error** | silently swallowing "the design changed" is the most dangerous failure mode |

⚠ **v1's `(owner_run_id, owner_node_key, plate_id)` makes the third loop iteration hit the
unique index** — that is a hard defect.

### 5.6 Concurrency: serialise per plate

A unique index only prevents "the same idempotence key twice"; it does **not** prevent two
different nodes (or a human) from changing the same plate at the same time.

⇒ Inside T2, take **`SELECT ... FOR UPDATE`** on the `plate` row, or use a **`state_version`
CAS**.
⇒ The `from_*` values in the audit row must match what was actually updated (otherwise "both
audit rows look successful while the later commit overwrote the earlier one").
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

⇒ **v2's scheme**: before `RunStateMachine.executeNode` returns successfully, fire a
**dedicated completion notification that does not swallow exceptions**, `nodeEffectListener`;
both `GraphRunner` and `WorkflowService` inject it on their path.

| Decision point | Explanation |
|---|---|
| How are exceptions handled | **not swallowed**: they propagate to the caller, which decides "escalate to manual" or "record PENDING and carry on". ⇒ **`ExecutionListener` must not be reused** |
| The round on the sequential path | the sequential path has no loop ⇒ `source_loop_index = 0` |
| What happens to `ExecutionListener` | **left exactly as it is** (it is "the side-effect outlet" semantics; do not change it) ⇒ this is **another** callback |

### 5.8 Compensation and recovery

| Scenario | Behaviour |
|---|---|
| crash before T1 | nothing happened ⇒ the device action did not happen either ⇒ just re-run |
| crash after T1 / before T2 | the effect record is `PENDING` ⇒ the compensation job replays T2 by the **already resolved `plate_id`** (no device action re-run, no position addressing re-run) |
| T2 rolled back | the effect record goes back to `PENDING` ⇒ same replay |
| repeated compensation | the idempotence key blocks it ⇒ same key + same payload is skipped |
| the flow has already continued when the compensation runs | the effect record is decoupled from the run ⇒ the compensation does not need the run to still be alive |
| database exception | **must not carry on silently** ⇒ an error code is recorded and it escalates to manual |

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
-- Reference constraint: process_state must exist in the same tenant's plate_process_state
--   (composite foreign key or an equivalent check)
-- CHECK (seal_state IN ('none','sealed'))

-- ③ Material effect (T1 writes PENDING → T2 applies)
CREATE TABLE plate_material_effect (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL,
  effect_key        varchar(128) NOT NULL,   -- idempotence key (hash computed by the application)
  source_run_id     bigint      NOT NULL,
  source_node_key   varchar(64) NOT NULL,
  source_loop_index int         NOT NULL DEFAULT 0,
  source_attempt    int         NOT NULL DEFAULT 0,   -- recorded, not part of the idempotence key
  plate_id          bigint,                    -- the plate actually resolved in T1 (the compensation replays by it)
  declaration       jsonb      NOT NULL,      -- declaration snapshot (the run version is frozen)
  payload_hash      varchar(64) NOT NULL,      -- same key + different payload → error
  status            varchar(16) NOT NULL,      -- PENDING / APPLIED / FAILED
  applied_at        timestamptz,
  error_code        varchar(64),
  error_message     varchar(255),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_plate_material_effect_key
  ON plate_material_effect (tenant_id, effect_key);
CREATE INDEX ix_plate_material_effect_pending
  ON plate_material_effect (tenant_id, status, created_at) WHERE status <> 'APPLIED';

-- ④ State transition audit (written by T2)
CREATE TABLE plate_state_audit (
  id                 bigserial PRIMARY KEY,
  tenant_id          bigint      NOT NULL,
  plate_id           bigint      NOT NULL,
  from_process_state varchar(32),
  to_process_state   varchar(32),
  from_seal_state    varchar(16),
  to_seal_state      varchar(16),
  from_owner_run_id  bigint,
  to_owner_run_id    bigint,
  source_run_id      bigint,
  source_node_key    varchar(64),
  source_loop_index  int         NOT NULL DEFAULT 0,
  command_id         varchar(64),        -- the physical command id
  effect_id          bigint,             -- the matching plate_material_effect
  evidence           varchar(24) NOT NULL,  -- evidence source (see below)
  operator           varchar(16) NOT NULL,  -- AUTO / MANUAL
  note               varchar(255),
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_plate_state_audit_plate  ON plate_state_audit (tenant_id, plate_id, created_at DESC);
CREATE INDEX ix_plate_state_audit_source ON plate_state_audit (tenant_id, source_run_id);
```

**The evidence-source enum** (`evidence`):

| Value | Meaning |
|---|---|
| `DEVICE_CONFIRMED` | confirmed by a device result (the state was actually read) |
| `EXECUTION_DECLARED` | migrated after the node finished, per the declaration (**the system said it, not the device**) |
| `MANUAL_INVENTORY` | manual on-site inventory |
| `MANUAL_CORRECTION` | a human correcting what the system recorded |
| `RECONCILIATION` | repaired by a reconciliation / recovery job |

⚠ **The principle**: the system writing a state from a node declaration **is not** the device
having detected the material state. **These two kinds of evidence must not both be shown as
"confirmed by the device".**

⚠ **The `consumable` table is not created in this round** (§4.4) ⇒ the independent consumable
is deferred to S5 as a whole.

---

## 8. Interfaces

| Method | Path | Explanation |
|---|---|---|
| GET | `/api/plates?processState=&currentRunId=&originRunId=&resourceId=&plateType=` | query plates by stage / current attribution / production source / position / plate format |
| GET | `/api/plates/{barcode}` | one plate: current stage, seal state, current attribution, production source, position |
| GET | `/api/plates/{barcode}/state-history` | the state transition history (paginated, including the evidence source) |
| PUT | `/api/plates/{barcode}/state` | change the state by hand ⇒ writes the audit with `evidence='MANUAL_INVENTORY'` / `'MANUAL_CORRECTION'` |
| GET | `/api/runs/{id}/material-effects` | **the material results / state changes this run declared** (queried by `source_run_id` of the effect records, not by the current attribution) |
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
| The migration is traced and the evidence source is visible | one row in `plate_state_audit` with the right `evidence` | real environment |
| "Who produced it / who processed it last / who owns it now" can be answered | the three columns and the audit history answer them separately | real environment |
| "Which material results did this run declare" can be answered | `GET /api/runs/{id}/material-effects` | real environment |
| Renaming a dictionary entry does not break the reference | change `display_name` ⇒ the display follows, the `code` does not change | pure logic |
| Old flows change behaviour not at all | run an existing flow from the database and compare with the behaviour before the change | real environment |

### 11.2 Failure scenarios (required by all three reviews)

| Scenario | The result that must be guaranteed |
|---|---|
| the device succeeded, the database write failed | **it must not silently show the material state as correct** ⇒ the effect record goes `FAILED` and it escalates to manual |
| the device timed out, but the action may have happened | **it must not simply be treated as "the action did not happen"** |
| the same node retries | **the same material effect is not applied twice** (`attempt` is not part of the idempotence key) |
| the same node loops three times | **all three real effects are traceable** (the idempotence key includes `loop_index`) |
| the run crashes and recovers | the confirmed effects are not repeated; the unconfirmed ones **can be recovered** (replayed by the already resolved `plate_id`) |
| two runs modify the same plate at the same time | **no state is overwritten** (`FOR UPDATE` / CAS) |
| a manual inventory and an automatic migration collide | there is an explicit conflict rule; **no silent overwrite** |
| the material cannot be found | **isolate and escalate to manual, do not guess** |

---

## 12. Implementation slices

| Slice | Content | Depends on | Produces |
|---|---|---|---|
| **S0** | **The domain model is finalised** (no tables): the Material layering and the three boundaries; the three attribution concepts; the tenant model of the stage dictionary and the template set; the idempotence key and the round; the model for execution facts and material effects; the completion-point scheme | — | the model is settled; **no code until the review passes** |
| **S1** | Basic data structures: the stage dictionary, the new `plate` columns, the effect table, the audit table, the constraints and indexes, the migration script. **Not wired into the execution engine** | S0 | the tables are in place, zero behaviour change |
| **S2** | The execution fact and material effect engine: the completion notification (both paths covered, exceptions not swallowed), T1/T2, idempotence, loops, retries, isolation, compensation | S0, S1 | **the behaviour starts to change** |
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

---

*This is an **up-for-review** design; no code and no schema change has been made yet. Please
file the review record per §8 of `docs/评审规范.md`
(`docs/评审记录/YYYYMMDD-NN-<reviewer>.md`) and answer each point in that file.*
