# LabOS 2.0 · UI Design Specification

> **English edition.** The Chinese original is [`docs/UI-界面设计说明书.md`](../UI-界面设计说明书.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 |
| Date | 2026-10-31 |
| Status | Pending review |
| Companions | `LabOS功能规格说明书.md` (features), `webapp/` (implementation) |

---

## 0. What this document settles

The current interface is **split by data table** (execution / process / status / alerts /
policies) — an operator who wants to know "why did it stop" has to jump across 3 pages and
piece the information together.

This document reorganises the navigation **by role and task**.

---

## 1. How the navigation is split

Split by what a person is doing, not by data table. When the same person changes task, the
information should stay in one group of pages, instead of being scattered across execution,
alerts and policies.

### 1.1 Five top-level entries

| Navigation | Main role | One-line responsibility |
|---|---|---|
| **Authoring** | Process engineer | Draw the method up, compile it, preflight it |
| **Run** | Operator | Watch what is running now, and where it is stuck |
| **Analysis** | Process / device engineer | Whether it runs fast, and where the time goes |
| **Operations** | Operations / supervisor | How alerts are handled, how policies are changed |
| **System** | Administrator | Users, permissions, audit |

> **The difference from today's state**: there is no "Analysis" entry point yet (the Gantt
> has nowhere to go), and "Alerts" and "Policies" are split into two tabs (they are really
> two sides of the same thing).

---

## 2. Information architecture

```
LabOS 2.0
├── Authoring                  (process engineer)
│   ├── Workflows              list / search / filter of the processes in the database
│   ├── Workflow designer      bpmn-js canvas + compilation preview + preflight result
│   └── Experiment Canvas      React Flow block canvas (drag workflows to assemble an experiment)
├── Run                        (operator)
│   ├── Live Board             the runs in progress + current node + device + whether it is stuck
│   └── Run Detail             node tree (including sub-steps) + event stream + attendance switch
├── Analysis                   (process / device engineer)
│   ├── Gantt                  predicted vs actual, expandable down to command level
│   ├── Duration Distribution  p50/p90 statistics by method / device
│   └── Utilization            device occupancy, downtime
├── Operations                 (operations / supervisor)
│   ├── Alerts                 open / acknowledged / closed + acknowledge & close actions
│   ├── Policies               error code → action, editable + applicability check
│   └── Devices & Islands      online state, capability, isolation records
└── System                     (administrator)
    ├── User Management
    └── Audit Log
```

**Unified colour and status semantics** (across every page):

| Status | Colour | Purpose |
|---|---|---|
| `completed` | Green `#22c55e` | Success |
| `skipped` | Grey `#8b93a7` | Skipped by policy (recorded, not alerted) |
| `running` | Blue `#3b82f6` | Executing |
| `manual` | Orange `#f59e0b` | Needs a person (escalates to red when unattended) |
| `failed` | Red `#ef4444` | Failure |
| Unreachable node | Red dashed border | Preflight PF-03 |

---

## 3. Page inventory

Each page specifies 6 items: **who uses it / what it solves / what you see / what you can do
/ empty state / error state**.

### 3.1 Live Board (Run · home page)

| Item | Content |
|---|---|
| **Who uses it** | Operator |
| **What it solves** | "Is anything running now? Where is it stuck?" |
| **What you see** | One run per row: progress bar (`12/65`), current node name, device code, attendance mode, latest event, elapsed time |
| **What you can do** | Switch the attendance mode, open the run detail, abort |
| **Empty state** | "No workflows are running" + a link into **Authoring** |
| **Error state** | Backend unreachable → a red bar at the top + an automatic retry countdown |

> **Design point**: the data comes from the in-memory state (`/api/process-status`),
> because `run_node` rows are only committed once execution finishes.
> The page has to say whether it is showing the "live in-memory state" or the "database",
> so the operator knows how fresh the data is.

### 3.2 Run Detail

| Item | Content |
|---|---|
| **Who uses it** | Operator / process engineer |
| **What it solves** | "Why did it stop? Which step? Which device?" |
| **What you see** | ① Header: run no., status, attendance, progress<br>② Node tree: parent node + sub-steps (`└1 OpenDoor completed 200200`), failed steps marked in red<br>③ Event stream: `#2 STEP_FAILED 第 3 步 CloseDoor device=200` (the detail is printed by the backend verbatim; "第 3 步" = "step 3") |
| **What you can do** | Switch attendance, re-run, view the raw device text |
| **Empty state** | — |
| **Error state** | Event stream is empty → the hint "No event records" (this run has no event records — possibly loaded data) |

**Key**: a sub-step has to show the **device code** and the **attempt count**
(`Attempt 2`), otherwise you cannot tell "it moved to another device and still failed".

### 3.3 Workflows

| Item | Content |
|---|---|
| **Who uses it** | Process engineer |
| **What it solves** | "Which workflow? Can it run?" |
| **What you see** | A list: name, code, node count, category; searchable by name / code |
| **What you can do** | Open the designer, execute in one click, see the preflight result |
| **Empty state** | No workflows → "create one first" |
| **Error state** | Preflight has an ERROR → a red dot on the left of the list item + a hover tooltip |

### 3.4 Workflow Designer

| Item | Content |
|---|---|
| **Who uses it** | Process engineer |
| **What it solves** | "Draw the workflow, see how many steps it expands into, know before saving whether it can run" |
| **What you see** | ① The canvas (bpmn-js)<br>② On the right: node properties (capability dropdown + method dropdown) + compilation preview ("will execute 7 steps") + preflight issues<br>③ At the bottom: node count / edge count / total steps / preflight status |
| **What you can do** | Drag nodes, draw edges, collapse sub-processes, change the capability, save |
| **Empty state** | No node selected → "Select a node to see its compilation result" |
| **Error state** | Preflight ERROR → red text at the bottom + the node marked with a red dashed line; a second confirmation on save |

**Key interaction**: a multi-step node is displayed as a **collapsible subProcess**
(already implemented); opening it shows the 7 steps inside.

**Finding devices by island (2026-11-05)**: when a workflow is created, an island can be selected (stored in `workflow.attr.island_id`; empty = all islands, so existing workflows are unchanged). The "Island devices · methods" panel on the left has the island selector, and the device dropdown in Node settings on the right is filtered by that island; each option carries `@islandCode` to prevent picking a device from another island. `GET /api/devices/pool?islandId=` is an optional filter; without it the full pool is returned, so the island view and existing tests do not regress. A newly dropped node that has not been saved used to show only "not in the compilation result", with no device or method dropdown at all; it now offers a "Save and continue" button that saves the canvas and refreshes the compilation result.

**Drag a method to create a node (2026-11-05, the recommended path)**: the left panel is a three-level "island → device → method" tree, and a device's methods are loaded lazily only when it is expanded (`GET /api/devices/{id}/methods`). Dragging a method onto the canvas immediately creates a `bpmn:Task` whose XML attributes carry `protocolName` (the method), `resource` (the device) and `methodParams`; it is saved once automatically and the compilation result is refreshed. The inefficient "drop a task → save → pick a device → pick a method → save again" chain is therefore no longer needed: select the new node and set its parameters on the right. The attribute names and casing match `BpmnGraphParser`, and `modeling.createShape` generates the DI automatically; the `methodParams` attribute was added to the whitelists in `collapse()`, `BpmnExpander` and the static designer so parameters survive saving an expanded view. Read-only accounts cannot drag; empty islands and devices without methods show an empty state instead of an error.

**Deleting nodes (2026-11-05)**: bpmn-js's native contextPad trash icon and Delete/Backspace keys always worked, but "only after selection, no hint, save again afterwards" left users unable to find the entry point. An **always-visible "Delete selected node" button** sits at the top of the right properties column; it calls `modeling.removeElements` and persists immediately just like a drop, refreshing the compilation result. `saveGraph` removes `workflow_node` rows no longer in the diagram and rebuilds `workflow_edge`. Deleting every node is blocked with a hint to keep at least one; viewer accounts are read-only.

**Device images (2026-11-05)**: device rows on the left show `<img>` thumbnails, and dropped nodes are drawn by `DeviceImageRenderer` as a "device image card + method name" instead of the uniform rectangle; images scale with the SVG and survive export. Most `device.icon` values point at dead source-server `/image/device/<uuid>.png` paths, so `scripts/gen-device-icons.py` generates 15 local drawings for 14 capabilities plus a generic fallback, and `iconFor()` maps dead paths back to `/devices/<CAP>.svg` (real photographs can replace them under the same names). The path is stored in `$attrs.icon` and was added to the whitelists in `collapse()`, `BpmnExpander` and the static designer; multi-step nodes expanded into subProcess fall back to native rendering, where images are temporarily hidden.

**Automatic robot-arm insertion when a plate changes device (2026-11-05)**: per §14.2/§14.3 of the orchestration design, dragging methods from two different devices inserts a `Transfer` node for the island's TRANSFER device and connects "device A → robot arm → device B" with `modeling.connect`, with `source/target` set to the two device codes. This covers only the **designer's graph-building path**; the backend `WorkflowBuilder` / `GraphRunner` / `flow-run` still do not add a transfer at run time, an acknowledged gap listed in the test master table's honest list.

**Implementation constraints and pitfalls (2026-11-05)**: `$attrs` on a newly created bpmn-moddle shape is a read-only getter, so assigning the whole object throws and properties must be written one by one; this version has no `canvas.getAbsoluteOrigin()`, so coordinates use `viewbox()` plus the container's `getBoundingClientRect()`; after the left panel narrowed the canvas, the fixed x=700/950 drop points of `e2e-flow-matrix.py` / `e2e-sim-flow.py` fell outside the container, so both scripts now scale their drop points to the container width. The full trade-offs are in the 2026-11-05 row of `decision-log.md` and D16 of `review-20261103/03-design.md`.

### 3.5 Alerts

| Item | Content |
|---|---|
| **Who uses it** | Operations / operator |
| **What it solves** | "What needs handling? Who is handling it?" |
| **What you see** | Aggregated by `error code \| device`: hit count, severity, latest message, **disposition** (open / acknowledged / closed), assignee |
| **What you can do** | Acknowledge (fill in the assignee + a note), close, silence, reopen |
| **Empty state** | "No unhandled alerts" |
| **Error state** | The disposition fails → a toast; the local state is not changed |

> **Design point**: by default only "Open" is shown; acknowledged / closed are collapsed
> below — when the operator opens the page they should immediately see "what still needs
> me".

### 3.6 Policies

| Item | Content |
|---|---|
| **Who uses it** | Operations / supervisor |
| **What it solves** | "How should this error code be handled? Will my change affect anything else?" |
| **What you see** | A list: error code, scope, action, attempts, backoff, priority, enabled state, source (manual / derived) |
| **What you can do** | Edit, enable / disable, **applicability check** (replays historical errors and tells you "this one matches no historical error") |
| **Empty state** | — |
| **Error state** | The check fails → a red "never applies" hint; saving is still allowed (but requires a confirmation) |

**Key**: editing a policy is a **dangerous operation** and must have a second confirmation
+ show the scope of impact ("this policy applies to existing historical errors").

### 3.7 Gantt (Analysis)

| Item | Content |
|---|---|
| **Who uses it** | Process / device engineer |
| **What it solves** | "How long does this take? Which device is the bottleneck?" |
| **What you see** | Swimlane = device, block = node / command; two views: **predicted** (simulated duration) / **actual** (`run_node` timings) |
| **What you can do** | Expand down to command level, switch the device allocation, zoom the time axis |
| **Empty state** | "Run it once or do a simulation first" |
| **Error state** | No duration model → that command gets a dashed block + a "no historical duration" marker |

### 3.8 Devices & Islands (Operations)

| Item | Content |
|---|---|
| **Who uses it** | Device engineer |
| **What it solves** | "Which one is offline? Which one is isolated? What can it do?" |
| **What you see** | Device cards: code, name, capability tags, online state, number of isolations, latest error |
| **What you can do** | Lift the isolation, view historical errors |
| **Empty state** | — |
| **Error state** | The device state is unknown → grey + "state unknown" |

---

## 4. Design rules

### 4.1 Layout

```
┌────────────────────────────────────────────────────────────┐
│ Top bar: brand | environment badge | global search | user  │
├────────────┬───────────────────────────────────────────────┤
│ Side nav   │ Content area                                  │
│ Authoring  │  ┌─ Page header (title + primary action)      │
│ Run        │  ├─ Filters / toolbar                         │
│ Analysis   │  ├─ Body (list | table | canvas)              │
│ Operations │  └─ Pagination / status bar                   │
│ System     │                                               │
└────────────┴───────────────────────────────────────────────┘
```

- The side nav can be collapsed and is expanded by default (5 top-level entries, at most
  4 second-level entries)
- Minimum content-area width 1024; below that the side nav collapses automatically

### 4.2 Tables (the most frequent component)

| Rule | Description |
|---|---|
| Row height | Compact 36px / standard 44px; the run detail uses compact |
| First column | Identifier (code / name), monospace font |
| Status column | A badge, coloured by the status colours of section 2 |
| Numeric column | Right-aligned, monospace font |
| Empty state | Centred copy + a primary action button, no empty table header |
| Long text | Truncated to one line + the full text on hover |

### 4.3 Dangerous operations

Three kinds must have a **second confirmation + an explanation of the consequences**:

| Operation | Example confirmation copy |
|---|---|
| Saving a workflow (when there are ERRORs) | "The preflight found 2 blocking problems; after saving, the workflow may not be able to run. Save anyway?" |
| Changing a policy | "This policy applies to existing historical errors and takes effect immediately. Confirm the change?" |
| Aborting a run | "After the abort, the device actions already executed are not undone. Confirm aborting #204888?" |

### 4.4 Feedback for long-running tasks

| Scenario | Feedback |
|---|---|
| Executing a process (synchronous, <5s) | Button loading + disabled |
| Executing a process (asynchronous) | Returns the runId immediately + jumps to the detail + polls the progress |
| Full preflight (~90s) | A progress bar + the number already checked + cancellable |
| Saving a workflow | Button loading + a toast on success |

### 4.5 Time display

- Relative time first ("3 minutes ago"), the absolute time on hover
- Across days, show the date (`10-30 23:16`)
- The timezone is uniformly +08:00, with no timezone suffix

---

## 5. Feature cross-reference table

> Purpose: verify that every interface element can be traced back to the feature ledger,
> so that neither "it is on the screen but not in the backend" nor the reverse can happen.

### 5.1 Page ↔ feature ledger (spec chapter 8, level 9)

| Page | Ledger items covered | Status |
|---|---|---|
| Workflows | 1.1 workflow list, 2.1 preflight entry point | ✅ in the backend |
| Workflow designer | 1.1 drag editing, **1.2 multi-step collapse**, 1.3 compilation preview, 2.1 preflight, 2.2 double-click to locate | 🟡 double-click to locate is missing |
| Experiment Canvas | **1.4 drag workflows to assemble an experiment** | ⬜ **not in the backend** (D1 decision 2.0 only does the sequence) |
| Live Board | 7.3 Process Status, 3.4 attendance switch | ✅ in the backend |
| Run Detail | 3.1 execution, 3.3 locating the failed step, 7.1 event stream | ✅ in the backend |
| Alerts | 7.2 acknowledging / closing alerts | ✅ in the backend |
| Policies | 9.1 policy management + applicability check | 🟡 **read-only**, editing and the check are not done |
| Gantt | 5.4 Gantt predicted + actual | ⬜ **not in the backend** (no duration model) |
| Duration Distribution | 5.2 / 5.3 duration model | ⬜ not in the backend |
| Devices & Islands | 8.x device state, isolation | 🟡 the isolation logic exists, there is no endpoint to lift it |
| System | 9.3 permissions, 9.4 audit | ⬜ none |

### 5.2 The six gates → where they land in the interface

| Gate | Where it lands in the interface | Current state |
|---|---|---|
| 1 Execute according to the graph | Designer (collapsible subProcess) + run detail (both branches execute) | ✅ fixed in the backend, not yet wired into the interface |
| 2 Attendance switch | Live Board + run detail (the switch button) | 🟡 the backend has the endpoint, the page does not use it |
| 3 The operations loop | Preflight (designer) + alerts (acknowledge and close) + policies (editable) + the status board | 🟡 policies are editable, the preflight timing still needs to be adjusted |
| 4 Duration forecast + interval | Gantt + duration distribution | ⬜ not in the backend |
| 5 Shadow mode | System → the driver commissioning page | ⬜ none |
| 6 Offline reassignment | Run detail (showing the device switch) + the devices page (isolation records) | ✅ in the backend |

### 5.3 The 5 gaps found by the cross-reference

| # | Gap | Explanation |
|---|---|---|
| 1 | **The experiment canvas has no backend** | The interface designed an entry point, but D1 decision 2.0 only does the sequence; whether to open it up has to be decided first |
| 2 | **Policies are read-only** | The interface designed editing + an applicability check, but the backend only has GET |
| 3 | **The Gantt has no duration model** | The interface designed the predicted / actual dual view; the backend `duration_model` table has data but it is not wired up |
| 4 | **The preflight timing is misaligned** | The interface designed it as "before saving"; the implementation does it "after saving" |
| 5 | **No authentication** | The interface has to show the "current user", but the backend has no authentication anywhere (a mandatory review item) |

---

## 6. List of differences from the current state

| Current state | Changed to | Reason |
|---|---|---|
| 5 tabs: execution / process / status / alerts / policies | 5 top-level navigation entries: Authoring / Run / Analysis / Operations / System | Split by role and task, not by data |
| No "Analysis" entry point | Added (Gantt / duration / utilization) | Gate 4 needs a landing place |
| The status page is separate | Folded into the "Run" home page | The operator only cares about "now" |
| Alerts and policies are separate | Both under "Operations" | They are two sides of the same thing |
| A single-file HTML | A React + AntD project | See `webapp/` |

---

## 7. Implementation order

| Phase | Pages | Depends on |
|---|---|---|
| 1 | Live Board, run detail, alerts | Already in the backend |
| 2 | Workflows, designer (connecting the existing endpoints) | Already in the backend |
| 3 | Policy editing + applicability check | **the backend needs PUT + a check** |
| 4 | Gantt, duration distribution | **the backend needs the duration model** |
| 5 | Devices & Islands, system | Endpoints need to be added |

---

## Appendix: what this document does not cover

- Mobile (this round is desktop only, minimum 1024px)
- Dark / light theme switching (dark only, to match the control-room environment)
- Multiple languages (Chinese only)
- Accessibility (listed for later)
