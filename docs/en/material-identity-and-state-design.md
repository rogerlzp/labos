# Material Identity and State · Design

> **Status**: **up for review** (raised 2026-11-11, nothing implemented yet)
> **Decision number segment**: this document claims **D401-D4xx** (to be merged into the segment
> table in `docs/评审规范.md` §7 once the review passes; existing segments: D1-D11 specification /
> D101-D119 concurrency scheduling / D201-D227 development log / D301-D311 review guide)
> **Related**: [Process Orchestration Capability Gaps and Landing Route](process-orchestration-gaps-and-roadmap.md)
> (the gap analysis, G1/G2/G11)
> **Code baseline**: `main` @ `46bbcff`
> **Review material**: see §0 (30 minutes to read, including the honest list of known problems)

---

## 0. Review entry point

| Item | Content |
|---|---|
| **What is being reviewed** | The complete design of stage 1 "material identity and state": data model, state-transition semantics, how a node declares what it produces, the interfaces and the pages |
| **Why now** | Checking against a real process flowchart showed that LabOS orchestrates "actions" while the real process orchestrates "material"; a container in the system has an identity and a position but **no content state** ⇒ the site cannot answer "what does this plate hold right now", and material hand-over across processes cannot be expressed either |
| **Read these three first** | §2 (root cause) → §4 (model) → §5.5 (**the transaction boundary**, the place where this design is most likely to be wrong) |
| **Review focus** | ① Is the criterion for splitting consumables in §4.3 sound; ② the transaction boundary in §5.5; ③ does the handling of "no declared output" in §6.3 lead to widespread misconfiguration on site |
| **Known problems** | See §13 — **nothing is hidden**; six open questions are listed there, two of which would change the implementation, please look at those |

---

## 1. The problem: questions the site asks that the system cannot answer

The following four questions come from a real process on site (desensitised):

| Question | Today's answer |
|---|---|
| What does this plate hold right now? | ❌ cannot be answered. `plate` has no content field |
| Who put this plate in the incubator, and where was it before? | ⚠️ **half answerable**: `plate_location_audit` has the position trail (one row each for putting and taking), but **not what it was holding at the time** |
| Which plates did this run produce? | ❌ cannot be answered. `plate` has no field for "which run / which step owns this" |
| How many of this batch of plates are still empty? | ❌ cannot be answered. Without a state there is no way to count by state |

**Why these questions matter**: they are not nice-to-have feature requests, they are the
**foundation of traceability and hand-over**. The input of one process is often the artefact
of the previous one (the same plate with different content). If the system cannot say "what
does this plate hold now", then there is no semantics for material hand-over between processes.

---

## 2. Root cause: a container has "where", not "what"

LabOS's existing container model answers a **spatial** question:

```
plate (barcode, plate_type, rows, cols, status, location_id, position_status)
  └─ position_state (resource_id, plate_id, occupied_by_run, ...)   ← which position the plate is in
```

It **does not answer the content question**. On a real process the label of a plate is:

```
PCR板-3  [扩增产物]  (PCR扩增)  -膜
   │         │          │       └── consumable state (sealed)
   │         │          └────────── ownership (which step it belongs to)
   │         └───────────────────── content state (what it holds now)
   └─────────────────────────────── identity (barcode)
```

⇒ **Four dimensions; LabOS implements only the first.** This round adds the other three
(the identity already exists).

---

## 3. Current state (the real state of the code and the tables)

> This section is for the reviewer to check against; everything comes from the database and
> the source on 2026-11-11.

| Fact | Where |
|---|---|
| `plate` has only `status(active)` and `position_status(IN_TRANSFER\|KNOWN)`; the `attr` column is **empty for all 6 rows** | `plate` table |
| The only actual value of `plate_type` is `unknown` | `plate` table |
| Where the plate is: `position_state.plate_id` / `resource_id` / `occupied_by_run` | `position_state` table |
| How it moved: putting and taking each write one `plate_location_audit` row (`operator='AUTO'` distinguishes automatic movement from manual inventory) | `PositionLock.auditPlateMove(...)` (`PositionLock.java:301`) |
| **The hook for "node completed" already exists**: `nodeStateRecorder.accept(key, "completed")` | `GraphRunner.java:604` |
| **`GraphRunner` is a pure executor and does not touch the database** — the DB side comes in through an injected listener (the `BuiltinListener` pattern) | `GraphRunner.java:189` / `withBuiltinListener(...)` |
| A plate-inventory page and a plate-trace page already exist | `webapp/src/pages/Plates.tsx`, `PlateTraces.tsx` |

⇒ **Good news**: the position lock, the movement trail, the node-completion hook and the
listener decoupling **all exist already**. This round does not have to touch the execution
semantics — it only hangs a side effect off "node completed".

---

## 4. The core model

### 4.1 The four dimensions of a container

| Dimension | Where it lives | This round |
|---|---|---|
| **Identity** (barcode / type) | `plate.barcode` / `plate_type` | exists; `plate_type` has to become a real dictionary |
| **Content state** | `plate.content_state` | **new** |
| **Consumable state** | `plate.seal_state` (a seal) + a `consumable` table (lids and other independent consumables) | **new** (§4.3) |
| **Ownership** (which run / which step) | `plate.owner_run_id` / `owner_node_key` | **new** |

### 4.2 Content state: a tenant-level dictionary, not a system enum

**The problem**: a content state is essentially **the customer's process vocabulary**
(`amplified product` / `purified sample` / `colour` / `stop` …). Eighteen of them appeared in
the real process. The system **has no business prescribing** what they are called.

**The solution**: `plate_content_state` is a **tenant-level dictionary table**; the product's
only job is to ship a **recommended set**.

| Decision | Choice | Reason |
|---|---|---|
| Ownership of the dictionary | **tenant level** (carries `tenant_id`) | the names are the customer's vocabulary; the system has no right to dictate them |
| Built in? | a **recommended set** is built in (`is_builtin`), the tenant may copy / rename / add | building 18 states from scratch is a terrible experience; but the recommended set is a "suggestion", not a "regulation" |
| Unique identity | `code` (machine identifier, language-independent) + `display_name` (what the customer sees) | renaming must not break the references |
| Where the value lives | `plate.content_state` stores the `code` | display resolves through the current tenant's dictionary |

⚠ **Do not** make the content state a free-text `varchar` or a `jsonb`:
the former lets "扩增产物" and "扩增产物 " coexist (then you cannot query by state),
the latter cannot be indexed (so "how many plates are in the post-amplification state right
now" cannot be answered).

### 4.3 Consumables: **one criterion, two modellings**

On a real process there are two kinds of "consumable", and their modelling cost differs by an
order of magnitude:

| | What it is on the drawing | Modelling |
|---|---|---|
| **Seal** | sealed on → peeled off → the peeled-off one goes **straight to waste and is not tracked separately** | `plate.seal_state` (`none` / `sealed`) — one column |
| **Lid** | **an entity in its own right**: it has its own position trail (taken from an ambient carrier → put on the plate → peeled off again) | a `consumable` table — a new class of entity |

**Criterion (new in this document, proposed for the review conclusion)**:

> **Does the consumable need its own position tracked?** Yes ⇒ an entity of its own;
> no ⇒ a state field on the plate.

A seal does not (peeled off and discarded), a lid does (it has to know where it came from and
which plate it is on). ⇒ There is no point in putting the seal into the `consumable` table
merely for the sake of uniformity — that would only produce noise.

**Upgrade path for the future**: if "the seal has to be recovered and reused" ever becomes a
requirement, turn the seal into a `consumable` as well and demote `plate.seal_state` to a
derived value ("which consumable is currently on it"). **The table structure already leaves
room for that.**

### 4.4 Ownership: why two dedicated columns

`owner_run_id` + `owner_node_key` (not a JSONB `attr`) — because ownership **takes part in
queries**: "which plates did this run produce" and "which plates did this node touch" are
frequent queries, and things inside JSONB cannot be indexed.

**How ownership is handed over at a process boundary**: when process A ends and process B
starts, the plates that are A's outputs (declared per §6) get their `owner_run_id` handed to
B's run. ⇒ **That is stage 3's content; this round only reserves the columns and does not
implement the hand-over.**

---

## 5. State transitions

### 5.1 What a transition is

| Concept | Content |
|---|---|
| **Transition** | one change of a plate's (content state / consumable state / ownership) |
| **Trail** | one row in `plate_state_audit`: who, when, changed what from what into what |
| **Current value** | the redundant copy on the `plate` table (for queries), **updated in step with the transition** |

### 5.2 There are exactly two triggers

| Trigger | Source | operator |
|---|---|---|
| **A node finished** | the node declared an output (§6) | `AUTO` |
| **A human changes the state during inventory** | the inventory page | `MANUAL` |

⚠ **Implicit derivation is not allowed**: "the plate is in the incubator ⇒ its state must be
post-incubation" — **not done**. A state is **declared** ("this step of mine produces X"), not
**inferred** from a position. Reason: changing position and changing content are **two
different things** (moving a plate does not change its content, adding liquid changes the
content without changing the position); conflating them produces the wrong behaviour of "the
state changed just because the plate was moved".

### 5.3 Idempotence: one node transitions one plate once

- A unique index `(run_id, node_id, plate_id)` on `plate_state_audit`
- Node retries, device retries, run continuation ⇒ no second row
- Idempotence rests on the **unique index** (not on "read then write") ⇒ safe under concurrency

### 5.4 Relation to pause / resume / breakpoint continuation

| Scenario | Behaviour | Correct? |
|---|---|---|
| Pause | the node did not finish ⇒ no transition | ✓ |
| Re-run after resume | the node finishes ⇒ one transition | ✓ |
| Breakpoint continuation (`skipKeys` skips a node that already finished) | **no transition** | ⚠ see §5.5 |

### 5.5 ⚠ The transaction boundary (the point of this document most in need of review)

**Requirement**: **the state transition must be committed in the same transaction as "the node
finished".**

Otherwise two kinds of seam appear:

| Seam | Consequence |
|---|---|
| "node finished" committed, transition not committed (crash in between) | the plate "ran" but its state did not change ⇒ the trace is broken, and **it cannot be repaired automatically** (nobody knows what it was supposed to become) |
| transition committed, "node finished" not committed | the state changed while the node is still running ⇒ the state runs ahead, the trace is broken again |

**The fact in the current code**: the node state goes to the `run_node` table and the commands
go to `run_command` / `command_ledger`; **whether they are in the same transaction has to be
confirmed by the review** (this design does not assume it already is).

⇒ **Question 1 for the reviewer**: if "node finished" and the transition cannot share a
transaction (different database / different service / asynchronous), what is the fallback?
The fallback proposed here is: **an `applied` flag on the transition table plus an idempotent
compensation job** — but that turns "one simple slice" into "a slice with a compensation
mechanism", which is **the biggest uncertainty in this design**.

### 5.6 How it is hooked into the execution side

**Reuse the existing listener pattern** (do not invent a new mechanism):

```
GraphRunner (pure executor, never touches the DB)
   └── node finished → fires NodeEffectListener.onNodeDone(nodeKey, method, params, ok)
          └── WorkflowService (boot layer, has the DB) implements it
                 ├── read the produces declaration of that node
                 ├── find the target plate (by position / by barcode)
                 ├── write the new state on plate
                 └── write one row into plate_state_audit
```

⚠ **Failure handling**: a failed transition **must not roll the node back** (the plate has
already moved; the physical fact cannot be undone) ⇒ the failure is **recorded with an error
code and escalated to manual**, never swallowed. The reason matches the existing convention of
`run_node`: the physical action has already happened ⇒ a state mismatch must be visible to a
human, and execution must not continue silently.

---

## 6. How a node declares the material it produces

### 6.1 How it is expressed: a `produces` entry inside `workflow_node.params`

**Why inside `params` (JSONB) rather than a new column**: the method parameters already live
there, and `produces` is "the material side effect of this method" — same source. **This
round adds no column** (one migration less).

> Alternative: a dedicated `workflow_node.produces jsonb` column — cleaner and easier to query
> ("which nodes declare an output" becomes a plain SQL query).
> **Trade-off**: this round uses `params.produces` (one column and one migration less); if the
> review values queryability more, switch to a dedicated column.

### 6.2 Shape

```json
{
  "produces": {
    "contentState": "amplified_product",
    "sealState": "sealed",
    "target": { "by": "position", "resourceId": 1000005 },
    "consumes": { "kind": "lid", "from": "stock" }
  }
}
```

| Field | Meaning |
|---|---|
| `contentState` | the content state `code` after the step (§4.2 dictionary) |
| `sealState` | the seal state after the step (`none` / `sealed`) |
| `target.by` | `position` (address by position) / `barcode` (address by barcode) |
| `consumes` | the independent consumable that is used up (registered only in this round, see §14) |

**How the two addressing modes divide the work**:
- `position` — covers the large majority of the actions on a real process (the arm carries it
  somewhere, that somewhere is the target)
- `barcode` — aliquoting and plate transfers, where "this specific plate" has to be named

### 6.3 What happens when no output is declared

| Case | Behaviour |
|---|---|
| The node **touches a plate** but declares no `produces` | the plate state **does not change**; the designer preflight gives a **WARN** ("this node moves a plate but declares no output") |
| The node declares an output but no plate matches `target` | **not silent**: an error code is recorded and it escalates to manual (the plate was taken by another experiment / the position is wrong) |
| The declared `contentState` is not in the dictionary | **preflight blocks it** (WARN) and at run time it is treated as an "unknown state" and traced |

⚠ **Why "no declaration" is only a WARN and not a blocking error**: a lot of nodes on site are
pure transfers (they do not change the content), and the system cannot tell "this node does
not change the content" from "the user forgot to declare it" ⇒ blocking outright would get in
the way of perfectly normal flows. ⇒ **The designer warns; the run time does not guess.**

### 6.4 Relation to the transfer nodes

A transfer (`Transfer`) **only changes the position, not the content state** ⇒ a transfer node
declares no `produces`. Position changes are the job of `PositionLock` (which already exists)
⇒ **the two lines do not overlap**: a position change writes `plate_location_audit`, a content
change writes `plate_state_audit`.

---

## 7. Data model summary

```sql
-- ① Content state dictionary (tenant level)
CREATE TABLE plate_content_state (
  id           bigserial PRIMARY KEY,
  tenant_id    bigint      NOT NULL,
  code         varchar(32) NOT NULL,
  display_name varchar(64) NOT NULL,
  sort_order   int         NOT NULL DEFAULT 0,
  is_builtin   boolean     NOT NULL DEFAULT false,
  UNIQUE (tenant_id, code)
);

-- ② Container: three new columns + plate_type becomes a real dictionary
ALTER TABLE plate
  ADD COLUMN content_state  varchar(32),   -- → plate_content_state.code
  ADD COLUMN seal_state     varchar(16),   -- none / sealed (the seal)
  ADD COLUMN owner_run_id   bigint,        -- owning run
  ADD COLUMN owner_node_key varchar(64);   -- owning step
-- plate_type values: pcr_plate / deep_well / assay_plate / reagent_reservoir /
--                    sample_deep_well / consumable / unknown

-- ③ Independent consumables (lids and other consumables whose position is tracked)
CREATE TABLE consumable (
  id          bigserial PRIMARY KEY,
  tenant_id   bigint      NOT NULL,
  kind        varchar(24) NOT NULL,        -- lid / cap / foil
  barcode     varchar(64),
  status      varchar(16) NOT NULL,        -- available / attached / discarded
  plate_id    bigint,                      -- the plate it is on
  resource_id bigint,                      -- the position it is at now
  run_id      bigint,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz
);
CREATE INDEX ix_consumable_tenant_status ON consumable (tenant_id, status);
CREATE INDEX ix_consumable_plate        ON consumable (plate_id) WHERE deleted_at IS NULL;

-- ④ State transition trail
CREATE TABLE plate_state_audit (
  id                bigserial PRIMARY KEY,
  tenant_id         bigint      NOT NULL,
  plate_id          bigint      NOT NULL,
  from_content_state varchar(32),
  to_content_state   varchar(32),
  from_seal_state    varchar(16),
  to_seal_state      varchar(16),
  owner_run_id      bigint,
  owner_node_key    varchar(64),
  operator          varchar(16) NOT NULL,  -- AUTO / MANUAL
  note              varchar(255),
  created_at        timestamptz NOT NULL DEFAULT now()
);
-- Idempotence: one node transitions one plate once
CREATE UNIQUE INDEX ux_plate_state_audit_once
  ON plate_state_audit (owner_run_id, owner_node_key, plate_id);
```

> ⚠ **The index semantics need review**: `ux_plate_state_audit_once` contains
> `owner_run_id`, and a manual inventory row (`operator='MANUAL'`) has a null
> `owner_run_id` ⇒ a PostgreSQL unique index **allows multiple NULLs** ⇒ manual rows are not
> constrained ✓ (deliberate). But if `owner_run_id` ever becomes nullable for automatic rows
> too, a partial index will be needed.

---

## 8. Interfaces

| Method | Path | Explanation |
|---|---|---|
| GET | `/api/plates?contentState=&ownerRunId=&resourceId=&plateType=` | query plates by state / ownership / position / type (**new filtering**) |
| GET | `/api/plates/{barcode}` | one plate: current state, consumables, ownership, position, transition summary |
| GET | `/api/plates/{barcode}/state-history` | the state transition history (paginated) |
| PUT | `/api/plates/{barcode}/state` | change the state by hand (inventory) ⇒ writes `plate_state_audit` with `operator='MANUAL'` |
| GET | `/api/runs/{id}/materials` | which plates this run produced (by `owner_run_id`) |
| GET | `/api/plate-content-states` | the content state dictionary (tenant level) |
| POST | `/api/plate-content-states` | add a state |
| PATCH | `/api/plate-content-states/{code}` | rename / reorder (**the code may not change** ⇒ references stay intact) |

---

## 9. Pages

| Page | Change |
|---|---|
| **Plate inventory** (`Plates.tsx`, exists) | the list gets three columns: content state / consumable / ownership; the state can be changed inline (manual inventory) |
| **Plate traces** (`PlateTraces.tsx`, exists) | a new "state transitions" tab next to the existing "position transitions" |
| **Run detail** (`RunDetail.tsx`, exists) | a "material produced by this run" block next to the node table (`GET /api/runs/{id}/materials`) |
| **Process designer** (`Designer.tsx`, exists) | a "produced material" section in the node settings panel (pick a content state / pick the target plate); preflight gets a WARN "moves a plate but declares no output" |

⚠ **Not in this round**: a separate "material" overview page — re-evaluate once the dictionary
and the states are actually in use.

---

## 10. Migration and compatibility

| Item | Approach |
|---|---|
| **Old data** | `content_state` / `seal_state` / `owner_*` all null ⇒ the meaning is "**unknown**", not "empty" |
| **No historical back-fill** | the historical state is not known, and back-filling would be inventing data ⇒ better to stay unknown |
| **What the UI shows** | a null value shows as "unknown", not as "empty" |
| **Old flows** | without a `produces` declaration the behaviour is exactly as today (the state does not change) ⇒ **zero-risk rollout** |
| **Old APIs** | the response body of `GET /api/plates` **only gains fields, loses none**; the new filter parameters are optional |
| **Schema scripts** | go through the `pg_rebuild/` shards plus `90_all_in_one.sql` (consistency checked by `check-schema-sync.sh`) |

---

## 11. Acceptance criteria

| Question | How it is verified | Tier |
|---|---|---|
| After a node produces a plate, its state changed | run a flow with a `produces` ⇒ `plate.content_state` holds the declared value | real environment |
| The transition is traced | `plate_state_audit` has one row, `operator='AUTO'`, from/to correct | real environment |
| "What does this plate hold now" can be answered | `GET /api/plates/{barcode}` returns `content_state` plus the dictionary text | real environment |
| "Which plates did this run produce" can be answered | `GET /api/runs/{id}/materials` returns the plate list | real environment |
| A node retry does not write two rows | retry the node ⇒ the audit table still has one row (unique index) | pure logic |
| Breakpoint continuation does not transition twice | `skipKeys` skips the node ⇒ no new row in the audit table | pure logic |
| No declared output ⇒ the state does not change and there is a hint | preflight reports a WARN, the plate state keeps its value | pure logic |
| Old data is unaffected | the 6 plates that exist in the database still have a null `content_state`; the API returns "unknown" | real environment |
| Renaming a dictionary entry does not break the reference | change `display_name` ⇒ the state shown on the plate follows, the `code` does not change | pure logic |

---

## 12. Implementation slices

| Slice | Content | Depends on | Produces |
|---|---|---|---|
| **S1** | tables + migration script + the recommended dictionary set (read only, not wired into execution) | — | the tables are in place, zero behaviour change |
| **S2** | `NodeEffectListener` + the `WorkflowService` implementation + writing the transition + idempotence | S1 | **the behaviour starts to change**: flows that declare an output transition the state |
| **S3** | the interfaces + the three columns on the inventory page + the state transition history | S2 | the site can look it up |
| **S4** | the "produced material" section in the designer + the preflight WARN | S2, S3 | the site can configure it |
| **S5** | the `consumable` table and putting / taking a lid (**an independent slice, a bigger order of magnitude**) | S1 | lids become traceable |

> ⚠ **S5 is listed on its own**: it is the independent-entity half, and it has nothing to do
> with the acceptance criteria of the other four ⇒ mixing it in would make "the first slice"
> heavier.

---

## 13. Known problems and open questions (the honest list)

| # | Question | Impact | State |
|---|---|---|---|
| 1 | **Can "node finished" and the state transition share a transaction** (§5.5) | if not ⇒ a compensation mechanism is needed and this design gets heavier | **please confirm in the review** |
| 2 | "No declared output" can only be a WARN, so the site may misconfigure it broadly | the state stays "unknown" forever, which is the same as not having done it | **please give an opinion in the review**: should the designer *require* a declaration for nodes that move a plate? |
| 3 | The dictionary is tenant-level ⇒ there is no shared vocabulary across tenants | reports and statistics have a per-tenant definition | a known trade-off |
| 4 | The content state is **declared**, and a human can change it to anything | it may disagree with reality (a human types it wrong) | mitigated by the fact that manual changes are audited too |
| 5 | The consumable half (lids) is clearly bigger than the rest | S5 drags the overall timeline | already split into its own slice |
| 6 | The cross-process ownership hand-over (§4.4) is **not implemented** in this round | a plate crossing a process boundary still cannot be traced to an owner | stage 3's content; the columns are reserved |

**Two conflicts with existing documents that need to be converged** (per `docs/评审规范.md` §7:
②③ conflict ⇒ change the specification first):

| Conflict | Current state | Disposition |
|---|---|---|
| `LabOS功能规格说明书.md` §7.2 "2.0 delivers sequence only" | sequence ≠ material hand-over | this round only adds container dimensions, it **does not touch process orchestration** ⇒ no conflict; a "known boundary" note pointing at the gap document was added |
| `docs/板位级占用与位置互斥-设计.md` §5 "plate-level tracking (where the plate is)" | plate-level tracking only covers the position | this round adds "what the plate is", the two complement each other and do not conflict; the review may confirm whether a pointer should be added to that document |

---

## 14. Explicitly not in this round

| Not done | Reason |
|---|---|
| The station layer (`station_id`) | stage 2; addressing by `resource_id` already covers most cases in this round |
| Material hand-over across processes | stage 3; this round only reserves the `owner_run_id` / `owner_node_key` columns |
| Events / triggers / setpoints | stage 4; a paradigm change with the highest risk, done last |
| Aliquoting, dynamic expansion | stage 5 |
| Actually putting / taking a lid (the consumable) | S5; this round only creates the table and registers the consumable |
| A separate "material" overview page | re-evaluate once the dictionary and the states are in use |

---

## 15. List of decision points (please state a position on each)

| # | Decision point | This document's suggestion |
|---|---|---|
| 1 | The transaction boundary between "node finished" and the state transition | must be the same transaction; if it cannot be, go through compensation (the current state needs to be confirmed by the review) |
| 2 | Who owns the content state dictionary | tenant level + a recommended set from the product |
| 3 | The criterion for splitting consumables | "does it need its position tracked independently" |
| 4 | `produces` inside `params` or in a dedicated column | `params` for this round (one column less); a dedicated column if queryability matters more |
| 5 | Should "no declared output" block | **no**, the designer warns and the run time does not guess |
| 6 | The set of `plate_type` values | six kinds (see §7) — is that enough? |
| 7 | Are the consumables (S5) in this round | listed separately, not mixed into the first slice |

---

*This is an **up-for-review** design; no code and no schema change has been made yet. Please
file the review record per §8 of `docs/评审规范.md`
(`docs/评审记录/YYYYMMDD-NN-<reviewer>.md`) and answer each point in that file.*
