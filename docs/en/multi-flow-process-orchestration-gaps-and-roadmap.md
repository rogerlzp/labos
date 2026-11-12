# Multi-Flow Process Orchestration Capability Gaps and Landing Route

> **Source**: four real client process flows — (1) a material-view process flowchart
> (molecular biology), (2) a cell culture / harvesting island, (3) a compound management
> island, (4) a library preparation island (pre- and post-). The raw process documents
> are client material; the full analysis stays under `private/`, **out of version control
> and never pushed**.
>
> **⚠ This document is desensitised**: specific plate numbers, reagent names, device
> models, consumable vendors, IP addresses, method names and station naming are all
> removed. Only **structural information** survives — the abstract model, the control
> semantics and the capability gaps. The raw documents and the full analysis stay under
> `private/`, **out of version control and never pushed**.
>
> **Why a desensitised version goes into the repository**: the raw flows are client
> material; but **"what our system is missing" is a product requirement** — and it is
> worth more long term than the raw flows, because it decides where the next few rounds
> of development go.
>
> **Basis**: the description of LabOS's current state comes from the real table structures
> and code in the repository on **2026-11-12**, not from design intent. See §9.
>
> **Related**: the single-flow write-up of the material-view line is
> [Process Orchestration Capability Gaps and Landing Route](process-orchestration-gaps-and-roadmap.md)
> (gaps G1–G13). This document is the **merged write-up** of that line and the other
> three flow lines. Gap IDs here are **B1–B8** (shared foundations) and **X1–X6**
> (new findings), with a mapping table back to the old IDs in §7.

---

## 0. Summary (one sentence)

> **The three new flow lines share one execution skeleton, and LabOS already has that
> skeleton. What is actually missing is eight shared foundations — and the two most
> expensive of them are not device drivers, they are the "station layer" and the "glue
> layer".**
>
> 1. **The orchestration layer is usable as is**: loop / parallel / conditional branch /
>    manual intervention / idempotency / timeout / audit trail — the skeleton of all four
>    flow lines falls inside this range.
> 2. **Eight foundations are missing**: external-system calls and the file channel, the
>    station layer, the transport relay chain, material identity and state, device
>    drivers, events and triggers, scan verification, and the consumable/reagent catalogue.
> 3. **One investment, many lines reused**: the same patterns recur across the three
>    flows ⇒ this is **not three custom projects, it is one platform capability gap**.
> 4. **Cross-validation**: this analysis independently confirms the material-view line's
>    conclusion — "LabOS orchestrates actions, real processes orchestrate material +
>    position + events" — and it provides harder evidence for it (deck station numbers,
>    multi-arm relay chains).

---

## 1. The four flow lines at a glance

| | Material-view line (molecular biology) | Cell culture island | Compound management island | Library preparation island |
|---|---|---|---|---|
| **What it does** | amplification → purification → digestion → in-vitro transcription → detection → development | cell counting → seeding → media exchange → harvesting → plate transfer | compound library: dissolution, dilution, dispensing, gradient dilution, cherry-picking | sample pre-treatment + library construction + pre-run quality control |
| **Islands** | single island | cell island + high-throughput island | compound management island | pre island + post island |
| **Sub-processes** | 14 | 3 main flows + 2 test groups | 8 main flows + tube verification + gradient dilution | pre 9 stages + post 10 stages |
| **Liquid-handler methods** | few (mostly device actions) | 10 | 8 | about 25 |
| **Devices** | multi-station platform dominated | about 25 types (two robot arms, two rails, several processing units) | 10 types + out-of-island logistics | about 15 types (magnetic-bead extractor, thermostatted position, nitrogen evaporator, real-time fluorescence quantifier) |
| **Consumable catalogue** | not tabulated | 23 kinds (incl. lids, reagent troughs, tube racks) | tube racks / plates / troughs | about 23 kinds (tip carriers, reagent troughs, extraction plates, culture plates) |
| **Volume / concentration maths** | concentration adjustment | cell count → volume mapping | **well dead volume + current volume** | dilution gradient quantitation |
| **External systems** | none | **laboratory information system ordering + imaging counts** | **the compound management system's own 6 interfaces** | quality-control data → generating workstation files |

> **Common ground**: all three new flows put the **liquid handler** at the centre, and all
> three treat "workstation method + input/output files" as first-class.
> **Differences**: the cell culture island is hard on **transport topology and
> data-driven branching**; the compound management island is hard on **external systems
> and inventory**; the library preparation island is hard on **stations and batching**.

---

## 2. Eight patterns that recur across the flows (this is the real requirement)

> The eight below appear in **all three new flows**. ⇒ They are product requirements,
> not one client's customisation.

### P1 · A liquid-handler method = method name + input file + output file

| Flow | Evidence |
|---|---|
| Cell culture island | method table: the cell-counting method ← barcode input sheet / sample count sheet → output sheet; the "file source" column reads "orchestrator" / "orchestrator/imaging" |
| Compound management island | script node calls the compound management system → generates the input sheet → uploads to object storage → assigns it to a file variable → the run-method command consumes it → output sheet comes back |
| Library preparation island | about 25 method names; "**the orchestrator generates the file the workstation needs from the quality-control data it received**" |

⇒ **All three flows share one abstraction.** LabOS's device-command model today is
"method + payload, run one action" — it has **zero support for external methods that take
file parameters**.

### P2 · Deck stations (P-series / T-series)

| Flow | Evidence |
|---|---|
| Library preparation island | reagent troughs on the P series, shallow-well plates on the P series, tip carriers on the T series; **the deck also has T8 / T10** |
| Cell culture island | a "deck position table" with 17 rows: index / consumable identifier / barcode / note (which plate goes on which trough) |
| Compound management island | deck naming: reagent troughs / compound tube racks / consumable plates / picked plates, each numbered |

⇒ **The station layer is shared vocabulary across the three flows**, and LabOS's
`resource_node.kind` has **no such layer**.

### P3 · Batch expansion + station allocation

| Flow | Evidence |
|---|---|
| Library preparation island | "repeat the above until **10** culture plates are all placed on the deck" — **the positions are non-contiguous, the occupied ones are skipped**; "until 4 sample plates are all loaded" |
| Cell culture island | "repeat the imaging procedure until all 4 plates are done"; "the remaining culture plates of this batch repeat the above actions" |
| Compound management island | "1–4 96-well plates", "with 3 plates per batch, 8 plates split 3, 3, 2" |

⚠ **The library preparation document exposes a conflict by itself**: "transfer the plate
from the nitrogen evaporator to the liquid handler (**P8 — conflict?!**)".
⇒ What is needed is not "a human-specified position" but "**allocate a free station**".

### P4 · Data-driven branching and quantity decisions

| Flow | Evidence |
|---|---|
| Cell culture island | `case1` cell count above the threshold → take 4 culture plates and top up the medium; `case2` → 3 plates; `case3` → 2 plates; `case4` below the threshold → **"prompt: sample quality control failed"**. The media-exchange flow triggers harvesting in **three ways**: (1) the QC result, (2) **the orchestrator specifies a barcode for media exchange (or the laboratory information system places the order for the orchestrator)**, (3) the media-exchange cycle ends |
| Compound management island | pass → continue, fail → terminate; "required volume <= system volume − well dead volume" |
| Library preparation island | "the orchestrator verifies the plate barcode" (several times), "the orchestrator generates the file the workstation needs from the quality-control data it received" |

⚠ **The cell culture island explicitly asks for a timer**: "(1) after seeding ends,
**start the clock, change the medium when the time is up**" ⇒ this is not "sleep N
seconds and then move on", it is **triggering something else when the time comes**.

### P5 · Lids and consumables are entities in their own right

| Flow | Evidence |
|---|---|
| Library preparation island | "the lid **is thrown into the waste bin**", "the workstation puts the **lid** on the deck", "the consumable stack dispenses a plate lid" |
| Cell culture island | "decap the sample plates, **put the lids on the temporary rack**", "**the lid's own record**", "where do the lids go?" (a dozen-plus occurrences) |
| Compound management island | the decapper "keeps the lid in the decapper", one temporary position is dedicated to "the solvent's lid" |

⇒ All three flows require **lids to have their own position, stock and record** — which
is exactly the "lid half" of G2 in the material-view line.

### P6 · Waste and consumption

Library preparation island: waste bin (tip carriers, reagent troughs, plates, lids) ｜
cell culture island: solid-waste disposal + flushing unit cleaning the fluid path ｜
compound management island: waste-liquid level alarm (logged by the client as a known issue)

### P7 · External systems

Compound management island (6 interfaces) ｜ cell culture island (**laboratory information
system ordering for the orchestrator** + imaging counts) ｜ library preparation island
(quality-control data → generating files)

### P8 · Transport is a "relay chain", not a single from/to pair

The typical chain on the cell culture island (one move spans **four transport actors**):

```
robot arm B ──► transfer position 1 ──► robot arm A ──► plate holder position ──► rail B ──► liquid-handler deck
```

⇒ LabOS's `resource_node` is a "device" and `position_state` is a "position", but
**what a transfer position is** and **that one move is a relay** are two things neither
of them expresses.

---

## 3. The orchestration layer: what LabOS has today

| Requirement | Current state | Evidence |
|---|---|---|
| Batch loop + per-round audit trail | BPMN back edge + workflow-level `maxIterations`, one `run_node.loop_index` row per round | `Designer.tsx:866`; `GraphRunner.java:270,518,679` |
| Parallel segments | library preparation "during the 10-minute incubation the workstation moves three sample plates out"; cell culture "run the DNA and RNA plate transfers at the same time as the seeding" | `GraphRunner.java:1017-1036,742-753,1068-1110` |
| Conditional branches | cell culture's four cases, compound management's pass/fail | `BpmnGraphParser.java:84,90`; `WorkflowPalette.ts:82-131` |
| Manual intervention | cell culture "the operator loads the plate", "the operator checks the volume", compound management's scan mismatch | `PauseManager.java:71-96`; `98b_manual_window.sql:22+`; `ManualWindows.tsx:69-70` |
| Idempotency + restart reconciliation | a 60-min shake or a 15-h incubation is never dispatched twice | `DriverStepExecutor.java:875-889`; `99f_command_ledger.sql`; `CommandLedger.java:30` |
| Layered long-task timeouts | `device_method.timeout_sec` → device library → node → capability → 30 s | `MethodTimeoutPolicy`; `DriverStepExecutor.java:511-556` |
| Deck occupancy + position locks + movement trail | atomic position reservation + both locks taken in ascending id order | `PositionLock.java`; `30_business.sql:664` |
| Named command parameters | deck position / method name / barcode / consumable identifier are all expressible | `DriverCommand.java:17-39`; `WorkflowService.java:1500-1548` |
| 16 capability codes | already cover aliquot / transfer / store / centrifuge / decap / peel / seal / mix / incubate / read plate / scan | `95_seed_data.sql:21-38` |

---

## 4. What is missing: eight shared foundations

| # | Foundation | Evidence across the three flows | Old IDs | Difficulty |
|---|---|---|---|---|
| **B1** | **External-system calls + file/CSV channel + response backfill** | the compound management script node itself; the cell culture laboratory-information-system order + imaging counts; the library preparation "QC data → generate workstation file" | C1–C4, C7 | medium (glue, but the chain is broken today) |
| **B2** | **Station layer + free-station allocation** | the P / T series deck of library preparation; the 17-row deck table of cell culture; the position conflict library preparation wrote down itself | G4 + X3 | medium-high |
| **B3** | **Transport relay chain (transfer positions + multi-step moves)** | cell culture's robot arm B → transfer position → robot arm A → holder → rail → workstation | X1 | medium |
| **B4** | **Material identity and state** (plate-level stage + lid as an entity + waste) | all three flows need it; P5, P6 | G1, G2, G11 | high |
| **B5** | **Events / triggers** (timed, QC result, external order, position change) | cell culture "start the clock, change the medium when the time is up"; three trigger sources; the library preparation parallel segment | G5, G7 | high (a paradigm difference) |
| **B6** | **Scan-verification node** (do not release until the expected code is scanned) | all three flows need it | G10 | low |
| **B7** | **Device drivers** (about 20 types) | about 50 devices across the three flows | C5, C6 | high (depends on manuals) |
| **B8** | **Consumable / reagent catalogue and remaining quantity** | cell culture's 23 consumables + 8 reagents; the compound management dead volume; library preparation's tip carriers and troughs | G12 | medium |

---

## 5. New findings (the six the earlier report did not have)

| # | Finding | Evidence | Verdict |
|---|---|---|---|
| **X1** | **Transport is a relay chain**; one move spans four actors | throughout the cell culture flow | needs a new "move segment" model, not just from/to |
| **X2** | **One action has a parameterised template plus variants** | the cell culture island's fixed 19-step template, where the four cases change only the count and the volume | agrees with the material-view line: **provide "station action templates", not "hand-write a dozen nodes per flow"** |
| **X3** | **Stations must be allocated automatically, and the client has already hit a conflict** | library preparation "(**P8 — conflict?!**)" | human-specified positions do not scale; there must be "allocate a free station" |
| **X4** | **The liquid handler has two hand-off locations: on-deck and off-deck** | cell culture "(**not onto the deck**, it stays on rail B)", "the tube rack is delivered into the workstation (**not onto the deck**)" | the current "plate-entry position" model is not enough |
| **X5** | **Methods need versions and a structured parameter contract** | cell culture "(ignore the well index — it is a 96-head index)", "(volume)"; library preparation runs the same method twice | flat CSV is not enough; a method needs a schema |
| **X6** | **A "parallel segment" is resource yielding, not a flow branch** | library preparation "these 10 minutes, the workstation moves the three sample plates out" | agrees with the material-view line's "no gateway needed": **parallelism is a resource-occupancy problem** |

---

## 6. One unified landing route

> **Ordering rationale**:
> 1. first close the foundations that **need no real hardware and can be accepted in shadow
>    mode** → 2. then the device layer, which depends on manuals → 3. last the
>    paradigm-level material and event work.
> **Schedule by foundation, not by flow** — because the three flows share them.

### Stage 0 · The glue layer (first cut, no real hardware needed)

| Action | Covers |
|---|---|
| **0.1 Re-resolve parameters at execution time** — device command parameters resolve `${x}` against the **runtime** variable table at execution time, not only at load time | B1 |
| **0.2 `__http` allow-listed node** — method/URL/headers/body (form or flat JSON)/timeout/retry/**response body stored into a variable**; domain allow-list + audit | B1 |
| **0.3 File channel** — object-storage client + upload/download endpoints + `run_artifact` hook-up (the `file_blob` table exists, with zero implementation) | B1 |
| **0.4 `__csv` node** — read CSV → variable table (header mapping) / write CSV → file | B1, X5 |
| **0.5 Structured backfill of command return values** — actually populate `DriverResult.outputs`; replace the current regex value-scraping | B1 |

⚠ **0.1 is a precondition for 0.2**: the compound management island's "file variable"
pattern (a script node writes a path → the next node consumes it) is **broken by
construction today**, because device parameters are resolved **once at graph-load time**
(`WorkflowService.java:919,1500-1548`) and `__setVar` only mutates memory without
writing back to the database (`GraphRunner.java:121`).

⚠ **Do not jump straight to a "general script node"**: [Custom Script Node — Design](custom-script-node-design.md)
already picked option C (separate process + restricted API). The direction is right but
the effort is large and that document calls it "the highest-risk feature in the system".
The actual need of the three flows is only "call a few fixed HTTP interfaces + pass files
+ parse CSV" ⇒ **a white-listed `__http` node is enough and far safer**.

**Acceptance (shadow run, no real hardware)**: mock HTTP + mock object storage ⇒
`__http` fetches a sheet path → `__csv` parses it → `__http` uploads it → the variable
reaches the next device command ⇒ **the compound management island's first main flow
runs end-to-end in shadow mode**.

### Stage 1 · Stations and relay chains (B2 + B3 + X3 + X4)

| Action | Covers |
|---|---|
| 1.1 Add a **station** layer under `resource_node` (`parent_id` already exists) | B2 / G4 |
| 1.2 Turn `position_state.port_id` into a real station reference | B2 |
| 1.3 **Free-station allocation** ("give me the next free position on this device") instead of human-specified positions | X3 |
| 1.4 Model **transfer positions** and a **move segment** (one move = one relay) | B3 / X1 |
| 1.5 The liquid handler's on-deck / off-deck dual hand-off | X4 |

**Acceptance**: one segment of the library preparation flow — the consumable stack
dispenses 10 culture plates → the robot scans them → 10 free deck positions are
allocated automatically → the plates are placed — **no real hardware needed, the
simulation bus suffices**.

### Stage 2 · Material identity and state (B4)

Reuse the material-view line's G1/G2/G11: plate-level process stage (`process_state`),
**lids as entities in their own right**, waste state, consumable type classification.
**All three flows need it**, and the earlier report already produced the design.

> ⚠ Scope note: **the per-well compound ledger does not go into LabOS** (delegate it to
> the compound management system — building it would create two sources of truth);
> but **lids, waste and the plate-level stage do**.

### Stage 3 · Device drivers (B7)

- Add about 20 device models: liquid handler / robot arm / consumable stack / centrifuge /
  decapper / de-sealer / sealer / heated shaker / code reader / imaging camera /
  magnetic-bead extractor / thermostatted incubation position / nitrogen evaporator /
  cell-count imager / plate washer / acoustic dispenser / non-contact dispenser /
  incubator / out-of-island logistics
- Add the command semantics specific to the compound management island: the
  run-method command (method name + file), dispense-by-type (with barcode),
  load-labware / prepare-to-load (with deck station identifier),
  transfer (source/target station)
- **Depends on vendor manuals** ⇒ the schedule is decided by what the client sends, not
  by us. The `planned` list in `sidecar-dotnet/models.json` already contains **two
  centrifuges/sealers** ⇒ **ask for those two manuals first**.
- Along the way fix two known holes: a device returning `method_status=1` (running) is
  **judged a failure outright today** (long tasks get misjudged); the `DevicePortLock`
  semaphore **only covers Transfer** (`LABOS_TRANSFER_PLAN` is off by default), so
  "3 shaker positions, 4 plates queueing" has no corresponding model.

### Stage 4 · Events and dynamic expansion (B5 + P3)

| Action | Covers |
|---|---|
| 4.1 A `__trigger` node: timed / state change / pre-action / external order | G5 (G5 is a paradigm difference, not a missing node) |
| 4.2 The "device reading → condition → loop" trio (waiting for a cell count, waiting for a QC result) | G7 |
| 4.3 Expand **N instances dynamically** (N comes from the experiment size and cannot be hard-coded on the canvas) | G9 / P3 |
| 4.4 Allocate stations automatically during batch expansion | paired with 1.3 |

### Stage 5 · On-site convenience items (B6 + B8)

A scan-verification node ("do not release until the expected code is scanned" + a
verification-result page) ｜ a consumable/reagent catalogue with remaining quantity ｜
a station-management front end (the back-end API is ready, only the UI is missing) ｜ a
consumable-stack debug page + **de-quoting of parameters** (`Island.tsx` stores the raw
string, so the same class of bug is still unfixed).

---

## 7. ID mapping table

| Old ID | Origin | Maps to here | Disposition |
|---|---|---|---|
| G1 plate-level process stage | material-view line | **B4** | Stage 2, reuse the existing design |
| G2 consumables (seal / lid) | material-view line | **B4** (the lid half) | Stage 2, independently validated by three flows |
| G3/G13 material hand-off across flows | material-view line | **B4** | Stage 2 |
| G4 station layer | material-view line | **B2** | Stage 1, **now backed by hard evidence** (deck station numbers) |
| G5 events / triggers | material-view line | **B5** | Stage 4, cell culture provides the "clocked media exchange" instance |
| G6 device setpoint | material-view line | inside B7 | Stage 3 |
| G7 loop driven by a device reading | material-view line | **B5** | Stage 4 |
| G8 dispensing (one to many) | material-view line | near Stage 5 | the compound management island's "add compound to 3 primary + 4 daughter plates" is an instance |
| G9 dynamic expansion of N instances | material-view line | **P3** / 4.3 | Stage 4 |
| G10 scan as first-class verification | material-view line | **B6** | Stage 5 |
| G11 waste | material-view line | **B4** | Stage 2 |
| G12 reagent types and consumption | material-view line | **B8** | Stage 5 |
| C1–C4 HTTP / file / CSV / file variable | compound management line | **B1** | Stage 0 |
| C5–C7 device / command semantics / return values | compound management line | **B7** / **B1-0.5** | Stage 3 / Stage 0 |
| C8–C9 compound inventory | compound management line | **not built (delegated to the compound management system)** | — |
| C10 Excel worklist | compound management line | near Stage 5 | — |
| C11 tube verification | compound management line | **B6** | Stage 5 |
| C12 general capability slots | compound management line | inside B7 | Stage 3 |
| C13 consumable-stack debug page | compound management line | Stage 5 | — |
| C14 station-management front end | compound management line | Stage 5 | — |
| **X1–X6** | new in this document | B3 / X3 / X4 / X5 | Stage 1 / Stage 0 |

---

## 8. Explicitly not doing (the shared position of the three flows)

| Not doing | Why |
|---|---|
| Rebuilding **compound inventory / dead volume / virtual containers** inside LabOS | the compound management system's interfaces are already in place — delegate; building our own creates **two sources of truth**, and when the remaining quantities disagree there is nobody on site who can adjudicate |
| **Cell counting / image analysis** | that belongs to the imaging island; the orchestrator only consumes the result |
| A **general script engine** (Groovy / Nashorn / arbitrary code) | the security red line in `ConditionEvaluator.java:8-11`; a white-listed `__http` node replaces it |
| **Laboratory information system master data** (sample identity, reagent formulae) | master data belongs to the master-data system; the orchestrator only needs "able to query, able to receive an order" |
| Full **out-of-island logistics** integration | the client writes "**no out-of-island logistics at FAT, can be ignored**"; model it as **optional out-of-island logistics**, not as a core component |
| The full set of BPMN **sub-processes / timers / message events** | `ROADMAP.md:449` already fixed "only the subset that gets the job done" |

---

## 9. Risks and unverified items

| Item | Description |
|---|---|
| **The three flow documents are full of "?"** | library preparation "**(position?)**" "**(method?)**" "**(quantity??)**" "**(conflict!)**"; cell culture "**(position?)**" "**(where do the lids go?)**" "**(the formula?)**" ⇒ **the client has not settled stations, methods or parameters itself.** That is both a risk and an opportunity: **LabOS is exactly the place to freeze those "?" into system answers** |
| **The library preparation document has a long run of empty paragraphs** | trailing blank lines ⇒ there may be unfinished content ⇒ confirm with the client whether a complete version exists |
| **The compound management side's interface contract has not been obtained** | no script source code and no interface documentation ⇒ the `__http` methods and fields are **reverse-engineered from the flow description** |
| **The cell culture island's three trigger sources** | "timed / external order / cycle end" map directly onto the B5 event model, but **priority and race conditions are undefined** (what if the time is up and the QC result has not arrived yet?) |
| **No database connection, no e2e run** | this was a static code and SQL investigation; the test database was not connected and neither the kernel nor the front end was started |
| **Migration drift** | the material-identity migration is **not merged into** `schema/90_all_in_one.sql` ⇒ a freshly provisioned database lacks those tables |
| **README limitations verified** | "no real hardware verified" holds; "the scheduler uses island-level mutual exclusion" is **outdated** (changed to resource-level on 2026-11-10); "long tasks are misjudged" holds (`TcpDriverAdapter.java:215-227`) |
| **The three flows' station lists are not reconciled** | the library preparation P / T series and the cell culture 17 positions do not agree across the two documents ⇒ a **plant-wide station master list** is needed as a modelling input |

---

## 10. One-page cheat sheet

```
cell culture island      material: cells / culture plates / tubes    hard: multi-arm multi-rail transport + data-driven branching + timed triggers
compound management      material: compounds / plates / troughs       hard: external system as the body + per-well inventory
library preparation      material: sample plates / library plates     hard: dense deck stations + batch expansion + cross-island transfer
                              ↓ shared by all three
skeleton (LabOS ✅): loop parallel branch manual idempotency timeout audit deck locks
                              ↓ shared by all three — eight foundations (LabOS ❌)
B1 glue   B2 stations   B3 relay chain   B4 material identity
B5 events B6 scan check  B7 device drivers B8 consumable catalogue
                              ↓ ordering rationale
stage 0 glue (shadow-acceptable, no hardware) → stage 1 stations & relay → stage 2 material identity
→ stage 3 devices (waiting on manuals) → stage 4 events & dynamic expansion → stage 5 convenience
```

---

*This is a desensitised analysis of client processes, keeping only structural
information; the raw documents and the full analysis stay under `private/`, out of
version control and never pushed.*
