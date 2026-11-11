# Process Orchestration Capability Gaps and Landing Route

> **Source**: a real laboratory-automation process flowchart (material view), a single
> portrait vector page with 633 graph elements and 172 connectors.
>
> **⚠ This document is desensitised**: the specific plate numbers, reagent names,
> device models and station naming are all removed. Only **structural information**
> survives — the notation, the abstract model, the control semantics and the capability
> gaps. The original and the full analysis stay under `private/`, **out of version
> control and never pushed**.
>
> **Why a desensitised version goes into the repository**: the source drawing is client
> material, but **"what our system is missing" is a product requirement** — and it is
> worth more long term than the original drawing, because it decides where the next few
> rounds of development go.
>
> **Basis**: the description of LabOS's current state comes from the real table
> structures in the database on 2026-11-11, not from design intent. See §9.

---

## 1. What this document is

A **capability gap analysis plus a landing route**.

The analysed object is a real laboratory-automation process flowchart (material view).
It is not a drawing that "looks nice" — it is the orchestration **actually in use on
site**: 14 sub-processes, hundreds of graph elements, 11 scan hand-overs and three
event-driven control chains.

The conclusion in one sentence:

> **LabOS orchestrates "actions", while this drawing orchestrates "material".**
> A plate moves from one position to another, carries something, and at some point the
> cooling has to stop for a while. These are two different abstraction layers — this is
> not something you close by adding a few nodes to the existing flowchart.

---

## 2. The abstract process model

### 2.1 Six kinds of notation

Even after desensitisation this notation can be reconstructed completely — it is
information in its own right:

| Notation | Meaning | Volume | The counterpart in LabOS |
|---|---|---|---|
| Solid blue rectangle | Device / station | multi-station platform stations ×47, cold positions ×19, ambient positions ×15, automated carriers ×8, stacks ×7, sealers ×6, de-sealers ×7, transfer positions ×3, plate readers ×3, incubators ×3, washers ×3 | `resource_node` (`kind = device / incubator / storage / transfer_point`) |
| Light grey rounded shape | Material instance (plate / reservoir) | assay plates ×19, PCR plates (several), deep-well plates (several), reagent reservoirs ×4, lids | `plate` |
| White rounded shape | Action | robot arm ×109, gripper ×28, scan ×11 | `workflow_node` (a device method) |
| Yellow circle | Control action (stop cooling) | ×5 | ❌ none |
| Green circle / green block | Data artefact / restore action | scan ×11, restore cooling ×5, concentration data, plate-read result | partly (`run_node.output`) |
| Red dashed frame | Sub-process boundary | 14 | `workflow` |
| Red rounded block | Waste | in-station ×9, out-of-station ×2 | ❌ none |
| Grey dashed arrow | Material input across a sub-process boundary | — | ❌ none |

### 2.2 Three dimensions of a container

The node labels have two slots, and that is the key to reading the whole drawing:

- A container is written as `PCR plate [amplified product] (owning step)`
  - `[amplified product]` = **what this plate currently holds** (content state)
  - `(owning step)` = **which step this plate belongs to** (material provenance)
- Plus a `-膜` / `-盖` / `-样品` suffix = **consumable state** (sealed / lidded / dosed).
  ⚠ **A seal and a lid are not the same thing**: a seal is a state of the plate itself
  (it is peeled off and goes straight to waste, **it is not tracked separately**), while
  **a lid is an entity in its own right** — it has its own position and its own
  hand-over (taken from an ambient carrier, put on the plate, peeled off again)

⇒ **A plate has three dimensions on this drawing: identity, content, consumable state.
LabOS currently has only the first one.**

---

## 3. The material main line

Process categories (generic molecular-biology terminology, not client-confidential)
form one material main line running across the whole drawing:

```
amplification plate (stock) → dilution plate → amplification plate (amplified → purified)
   → digestion plate → in-vitro-transcription plate → post-transcription purification
   → deep-well plate → purified plate → detection plate → [concentration data]
   → detection plate / aliquot plates ×N → assay plate sample-antibody-enzyme-colour-stop → [plate-read result]
```

**One important fact about the shape**: this drawing contains **no branch or merge
symbol at all**. The material main line is **linear**. The "several plates side by
side on the same row" are **positions of different materials**, not process branches.

⇒ **In real material-view process orchestration, gateways are not used.**
Parallel / exclusive / conditional gateways are all present in LabOS, but they are
**not used** in this kind of process; the real difficulty lies elsewhere (§5).

---

## 4. Control and event semantics

The drawing has **three side control chains that are not on the material main line**,
and all of them are **event-driven**. This is the most informative part of the drawing.

### 4.1 Position linkage: stop cooling / restore cooling

```
cold position / detection plate ──→ stop cooling ──→ …… ──→ restore cooling ──→ continue main line
        ↑ trigger: when the transfer starts
```

Meaning: **as soon as a plate enters a cold position the cooling stops (so the reagent
does not spoil in the cold), and when the plate is carried away the cooling resumes.**
This is a **state linkage based on position**, not a timer.

### 4.2 Timer linkage: incubation timing

```
incubator ──(dashed: incubation time elapsed)──→ stop cooling ──→ …… ──→ restore cooling
```

Appears **3 times**, matching the three incubator entries of the assay section —
sample incubation, antibody incubation and colour incubation.

### 4.3 Position event: on arrival at the incubation position

```
incubation position ←──(dashed: on arrival at the incubation position)── stop cooling
```

Meaning: **a plate arriving at the incubation position triggers an action** (not "after
some time").

### 4.4 A loop driven by a device reading

```
plate reader 「incubate  read」 → 「until the reading reaches the set point」 → (not reached) back to read
                                                                               (reached) → stop colour → plate-read result
```

### 4.5 Data artefacts

| Artefact | Where | What it drives |
|---|---|---|
| **Concentration data** | end of concentration detection | the concentration calculation in "concentration adjustment and aliquoting" |
| **Plate-read result** | end of colour termination | the end product |
| **Scan record** ×11 | every material hand-over | every step must scan the matching plate code |

---

## 5. LabOS against the drawing: 13 gaps

> The current state comes from the real table structures (`resource_node` / `plate` /
> `position_state` / `workflow_variable` / `experiment_variable` / `run_node` /
> `command_ledger`).

### P0 · The four things that block "orchestrating the drawing as it is"

| # | Gap | Evidence in the drawing | LabOS today | Consequence |
|---|---|---|---|---|
| G1 | The **plate-level process stage** is not modelled | every plate carries its content (amplified product / purified sample / detection mix / colour / stop …) — 18 states in total | `plate` has only `status(available/in_use/consumed/discarded)` and `position_status(IN_TRANSFER\|KNOWN)`, **no process stage column**; `attr` is empty for every row. ⚠ The **well-level sample model (`plate_well`/`sample`/`sample_aliquot`) has existed all along** (empty tables) ⇒ what is missing is the plate-level stage, not the well-level content | "which step of the process is this plate at" cannot be answered; the trace breaks |
| G2 | Consumables are not modelled | `-膜` (sealed), `-盖` (lidded), `-样品` (dosed); the de-sealer removes it again. ⚠ **a seal and a lid must be looked at separately**: a seal is a state of the plate (peeled off and discarded, not tracked on its own), while **a lid is an entity in its own right** (its own barcode, stock and attachment history) | nowhere to store it | a seal only needs a column; a lid needs **a new class of entity** — a completely different order of magnitude. **Criterion (corrected after the review)**: *does it need an independent identity or lifecycle traceability* (position / batch / stock / attachment history / reusability / quality traceability)? |
| G3 | There is no semantics for material hand-over across processes | the container name runs all the way through; the output of one sub-process is the input of the next (grey dashed arrows) | specification §7.2: "2.0 delivers sequence only"; `experiment_run` has a single-valued `workflow_id` + a `variables` JSONB; `plate` has **no field for "which run / which step owns this"** | Orchestrating the drawing as it is ⇒ **the plate identity breaks between sub-processes**: where the plate went at the end of one process and where the next one picks it up cannot be answered |
| G4 | There is no "station" layer under a device | multi-station platform stations ×47, inside the platform ×19, magnetic separation station, transfer positions, plate positions inside instruments | `resource_node.kind ∈ {device, incubator, island, storage, transfer_point}` — **there is no station layer**; `position_state.port_id` is the closest thing to a "channel", but it cannot express "one device with dozens of stations" | "the arm picks the plate at station 3 and puts it at station 7" has **nowhere to land** |

### P1 · The five things you cannot work around while orchestrating

| # | Gap | Evidence in the drawing | LabOS today | Consequence |
|---|---|---|---|---|
| G5 | There is no event / trigger node type at all | three kinds of trigger: incubation time elapsed / on arrival at the incubation position / when the transfer starts | `__sleep` is a **blocking wait** (sleep N seconds, then continue) — not "fire something when the time comes"; there is no timer, no event bus, no position event | **This is a paradigm difference, not a missing node**: LabOS is "walk along the graph step by step", while the drawing has 3 side control chains that are off the main line and driven by events ⇒ they cannot be drawn at all |
| G6 | No read/write of device parameters (setpoints) | stop cooling / restore cooling = **flipping a switch on the device**, not "running an action" | a device method is always "`method` + `payload`, run one action"; there is no generic read/write-parameter interface | it can only be expressed as "call a method called StopCooling", which requires the device to really expose it |
| G7 | The loop driven by a device reading is only half there | plate reader reading → decide → back to reading | `__wait` waits for a **variable** to reach a value (fed in by an external refresher), **not** for a **device reading** to reach a threshold | the trio "device output → condition → loop" has to be connected end to end |
| G8 | Aliquoting (one to many) is not a first-class operation | one sample is split across several plates, each taking a different volume | the plate model is "one plate with N positions", but there is no "distribute the content of one container by volume / concentration into several containers" | it can only be split into N hand-written nodes, or computed by hand with `__expr` |
| G9 | No dynamic expansion of N identical plates | several PCR plates and deep-well plates — the same action repeats N times, each time on a fresh plate | parallel gateways ✓ exist, but **there is no "expand into N instances according to N"** (N is decided by the size of the experiment, it cannot be drawn into the graph) | N plates have to be drawn as N parallel branches by hand |

### P2 · Relevant to running the site

| # | Gap | Evidence in the drawing | LabOS today | Consequence |
|---|---|---|---|---|
| G10 | Scanning is not a first-class check | scan ×11, at every material hand-over | `plate.barcode` ✓ and `plate_location_audit` ✓ exist, but **the scan itself is not a node / event in the flow** | there is no "this step must scan code X, otherwise it does not proceed" |
| G11 | Waste handling has no semantics | in-station ×9 / out-of-station ×2; peeling, discarding plates and disposing of liquid are part of the flow | `plate.status` only has `active`; **there is no "discarded"** | a discarded plate still occupies its position |
| G12 | Reagent containers have no type and no consumption | solvent reservoir / reagent reservoir / buffer reservoir — the reagent reservoirs are **consumed** | `plate_type` means the **plate format** (`96/384/24/tube_rack`); there is no separate dictionary for the role a container plays in the process | there is no distinction between plate / reagent reservoir / deep-well plate / assay plate, and no notion of remaining volume |
| G13 | No contract for data artefacts across processes | the concentration data drives "concentration adjustment and aliquoting"; the plate-read result is the end product | `run_node.output` is JSONB ✓ and can hold artefacts; `experiment_run.variables` ✓ exists (but only 4 of 72 rows use it); **but there is no contract for "how the artefact of this run becomes the input of the next process"** | same root as G3 |

---

## 6. What LabOS already does well

This drawing confirms the following are right:

| LabOS capability | What it corresponds to on this drawing |
|---|---|
| Position occupancy + position lock + plate movement trace | which plate belongs to whom on a carrier / stack / transfer position, and how it got there |
| Device online state (Redis) + heartbeat | whether the automation platform, amplification instrument, plate reader and incubator are online |
| Pause / resume / fault, manual window | what the site cares about most: "the device is still moving while we paused" |
| Command ledger + per-node trace (`run_node.loop_index`) | every step's "who touched which plate, and when" |
| Three-scope variables + condition expressions | the arithmetic in the concentration adjustment |
| Gateways (parallel / exclusive / conditional) | ⚠️ **not a single one is used on this drawing** (the material main line is linear) |

---

## 7. Landing route: where to start

> **Ordering rationale**: first make "where a plate came from, where it went, and what it
> holds" true inside the system; only then talk about events and triggers —
> **without a material identity there is nowhere for an event model to land**.

### Stage 0 · Swap the abstraction layer first (the foundation)

The core entity of LabOS today is an **action** (`workflow_node` = one device method).
The core entity of this drawing is **material**.

- ⇒ Make "material" a first-class citizen: a container has an identity, a content state,
  a consumable state and an owner
- ⇒ Then make "station" a first-class citizen: a position inside a device where a plate
  can sit
- ⇒ Only then can a flow upgrade from "a sequence of actions" to "**a sequence of
  actions that carries material with it**"

**This stage changes no code, but it decides the table structure of every stage after it.**

### Stage 1 · Material identity and state (G1 / G2 / G11 / G12)

> **Detailed design**: see [Material Identity and State · Design](material-identity-and-state-design.md)
> (up for review; contains the data model, the state transition semantics, the transaction
> boundary, the implementation slices and the list of decision points).

| Action | Landed on |
|---|---|
| Add the **plate-level process stage** (`process_state`), the **seal** state and the attribution (current attribution / production source / processing history, three concepts) to `plate` | G1, the seal half of G2 |
| **Lids and other independent consumables** become a class of entity of their own (their own stock and handling), not an attribute of the plate — **the whole table is deferred to S5**, so that no incomplete structure gets frozen | the lid half of G2 |
| The plate format keeps using `plate_type` (`96/384/24/tube_rack`), **unchanged**; a process role would be another column | G12 (corrected by the review) |
| Add a state-transition trace (modelled on `plate_location_audit`) | the "traceable" half of G1 |
| **Nodes declare the material they produce**: a device method turns the container into a given state when it finishes | the other half of G1 — without this the state never changes by itself |
| A "discarded" state and a waste action | G11 |

### Stage 2 · The station layer (G4)

| Action | Landed on |
|---|---|
| Add a station layer to `resource_node` (hanging under the device; `parent_id` already exists) | G4 |
| Turn `position_state.port_id` into a real station reference | G4 |
| The arm's from / to point at stations, not at devices | G4 |

### Stage 3 · Material hand-over across processes (G3 / G13)

| Action | Landed on |
|---|---|
| A flow declares its **input materials** and **output materials** | G3 |
| A chain of flows declares the ordering constraint and the material hand-over relation | G3 |
| Ownership transfer of a plate at the process boundary (previous outputs → next inputs) | G3, G13 |
| An explicit contract for an artefact as the input of the next process (with type and validation) | G13 |

### Stage 4 · Events and triggers (G5 / G6 / G7)

| Action | Landed on |
|---|---|
| A `__trigger` node: timed / position change / action precondition | G5 |
| A device setpoint interface (read parameter / write parameter) | G6 |
| The trio "device reading → condition → loop" | G7 |

### Stage 5 · Site convenience items (G8 / G9 / G10)

| Action | Landed on |
|---|---|
| An aliquoting operation (one to many, by volume / concentration) | G8 |
| Dynamic expansion into N instances | G9 |
| A scan-check node (only proceeds when the expected code was scanned) | G10 |

---

## 8. The first thing to cut

**Start with one minimal closed loop of stage 1**, rather than spreading across all five
stages at once:

> A plate has an identity (barcode) and a content state; it is carried by the arm from
> position A to position B; and **after the move the content state of the plate changes
> automatically from state A to state B** (because the node declared the output). The
> whole thing is traced, so you can answer "what does this plate hold now, and who
> changed it last".

Once that loop works, the foundation of G1 / G2 / G3 / G11 is in place; every later item
(stations, cross-process hand-over, events) adds something **on top of a material
identity that already exists**.

**If only one thing gets done**: build "**nodes declare the material they produce +
the plate state flows automatically + the transitions are traced**". It closes G1, G2 and
G11 at once and is the common premise of the other twelve.

---

## 9. Conclusions

1. **This drawing orchestrates material, not actions.** LabOS orchestrates actions.
   That is a difference of abstraction layer, not of feature count.
2. **Gateways are not the point.** Not one gateway is used on this drawing — the
   material main line is linear, and the plates side by side are positions of different
   materials. We have already made the gateways complete (parallel / exclusive /
   conditional), but they are not used in this kind of real process.
3. **The difficulty concentrates in three places**: material identity and state
   (G1/G2), the station layer (G4) and material hand-over across processes (G3) —
   all three are **modelling** problems.
4. **The event model (G5) is a paradigm difference**, not an extra node: LabOS is
   "walk along the graph step by step", while the real process has 3 control chains
   that are **off the main line and driven by events**.
5. **Establish the material identity first.** Without it, events, stations and
   cross-process hand-over all have nowhere to land.

---

*This is the desensitised version. The original drawing and the full analysis (with the
concrete plate numbers / reagents / device naming) stay under `private/` and are out of
version control.*
