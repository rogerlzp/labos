# Logic nodes by layer, and the split between the two editors (2026-11-05)

> Answers one question: **the experiment canvas has forks, parallelism, loops, joins and variables — should the workflow designer have them too?**
> Answer: yes, and the **main battlefield is inside a workflow**. The experiment canvas keeps only the minimum needed for "cross-workflow orchestration".
> This document is the implementation basis for whoever takes over; every conclusion cites a code/SQL line, and the documents are not taken on trust.

## 0. One-line conclusion

- Logic nodes (exclusive, parallel, conditions, loops, variables) **belong inside a workflow** and belong to the bpmn-js designer; the experiment canvas only does "cross-workflow orchestration".
- The capability mismatch is the **starting point**, now converged by Batch 1–4 (Batch 6 was added on 2026-11-06: the two built-in logic nodes `__sleep` delay / `__wait` wait): the designer's **entry points are in place** (parallel gateway = Batch 2, condition expression = Batch 3, loop cap = Batch 4), and the experiment canvas **has been shrunk** to cross-workflow orchestration only (`fork/parallel/loop/variable/device/join` are gone from the palette — on the canvas they "save 400 / are skipped at run time"; see §5 Batch 1). **Remaining**: `subProcess` silently skipped (registered only) and Batch 5 cross-workflow parallelism (parked).
- So the task is not "move the canvas model into the designer" but: **give the designer the editing entry points, and shrink the canvas to what it can actually run**.

## 1. The user's wording (the constraint of this document)

1. The overwhelming majority of logic happens **inside one workflow** → it must be drawable, saveable and runnable in the workflow designer.
2. Only a few cases need **cross-workflow** (experiment-level) orchestration → the experiment canvas stays, but does not carry workflow-internal semantics.
3. "Drawable but not executed" or "drawable but not saveable" is not allowed — that is worse than a missing feature.

## 2. Evidence today (capability comparison)

| Logic capability | Experiment canvas `/canvas` | Workflow designer `/designer/:id` | Backend executor | Existing assertions |
|---|---|---|---|---|
| Exclusive branch | 🟡 a `fork` node plus one condition box; `runFlow` picks an edge; **save returns 400** | ✅ can create an `exclusiveGateway`; condition/default have UI (right column "Sequence flow · condition") | ✅ `SPLIT_XOR` + condition → default → first by order | ✅ xor 6 / xor3 3 |
| Parallel fork/join | ⬜ `parallel` is skipped, `join` is visual only; save returns 400 | ✅ can drag out a `parallelGateway` (entry added in Batch 2; the palette is aligned with the parser whitelist) | ✅ `SPLIT_PARALLEL` activates every outgoing edge + `JOIN_PARALLEL` waits for all incoming edges | ✅ parallel 3 / loopback 2 |
| Condition expression | 🟡 a home-grown `evalCondition`: spaces required, no arithmetic/logic/parentheses, parse failure means true | ✅ sequence-flow condition UI (writes `bpmn:conditionExpression` + gateway `default`) | ✅ `conditionExpression` + `default`, `ConditionEvaluator` supports comparisons/logic/parentheses/plus-minus | ✅ builtin/outvar/xor |
| Loop | 🟡 a `loop` node that duplicates steps in the frontend (≤20); no back edge; save returns 400 | ✅ a back edge can be drawn by hand; workflow-level `maxIterations` UI (right column "Workflow options") | ✅ back edge + DFS cycle detection + `maxVisits` (default 10) + `loop_index` | ✅ loop 3 / loopback 2 |
| Variables | 🟡 a `variable` node with `k=v`, request-only; save returns 400 | ✅ `__setVar` / `__expr` panel | ✅ `Kind.BUILTIN` + device output write-back | ✅ builtin 4 / outvar 2 |
| Delay / wait | ⬜ not available on the canvas | ✅ `__sleep` (sec) / `__wait` (var+value+timeout) panel | ✅ `Kind.BUILTIN`; capability resolution honours the `__` prefix (it does not rely on the node display name); the wait loop responds to a pause in 500 ms slices | ✅ waitsleep 10 |
| Sub-workflow | 🟡 a `workflow` node can pick a workflow and save; **`runFlow` ignores it** | ✅ multi-step collapsible subProcess | 🟡 `BpmnExpander` expands it; `callActivity` not done | 🟡 experiment 3 |
| Manual pause | ⬜ runs synchronously, no pause | ⬜ the designer palette no longer has intermediate events (the parser rejects them); manual pause goes through the paused queue / run-control API | ✅ node-boundary pauseChecker + MANUAL stops the graph | ✅ pause 10 |

Legend: ✅ supported / 🟡 partial / ⬜ not supported. Evidence is in `ExperimentCanvas.tsx:184-191,750-753`, `PaletteProvider.js:161-211`, `WorkflowGraphParser.java:215-220`, `GraphRunner.java:385-394,489-500`, `ExperimentActionService.java:349-384`, `verify-flow-matrix.py:295-472`.

## 3. Six concrete contradictions (ordered by harm)

| # | Contradiction | Evidence |
|---|---|---|
| M1 | The canvas palette offers six logic nodes, but save/compile accepts only three (start/end/workflow); the rest always return 400 | `ExperimentCanvas.tsx:184-191`; `ExperimentActionService.java:380-384`; `ExperimentGraphCompileSelfTest.java:55-80` |
| M2 | What can be drawn cannot be saved, and what can be saved is not executed: `runFlow` only understands device nodes, which are exactly the kind rejected on save; workflow nodes save and compile, but `runFlow` silently ignores them | `ExperimentCanvas.tsx:703-757` |
| M3 | A parallel gateway can only be hand-written in XML, even though the executor fully supports it | `PaletteProvider.js:161-211`; `e2e-flow-matrix.py:182-185`; `GraphRunner.java:390-394,489-500` |
| M4 | Two condition-expression syntaxes; the canvas one treats a parse failure as true (silently taking the wrong branch) | `ExperimentCanvas.tsx:229-259`; `ConditionEvaluator`; `WorkflowGraphParser.java:96-121` |
| M5 | Three loop semantics: canvas duplicates steps, the backend uses a back edge plus a workflow-level cap, and the designer has no entry point | `ExperimentCanvas.tsx:734-741,761-766`; `GraphRunner.java:145-161`; `WorkflowService.java:815` |
| M6 | Two variable models that do not recognise each other: the canvas `k=v` string vs the backend `__setVar` `params{var,value}` | `ExperimentCanvas.tsx:1228-1235`; `WorkflowGraphParser.java:174`; `GraphRunner.java:449-453` |

> Status (2026-11-05): M3 (no parallel-gateway entry) was resolved in Batch 2; the designer-side entries for M4 (two condition syntaxes) and M5 (three loop semantics) landed in Batch 3 / Batch 4, with the syntax unified on the backend `ConditionEvaluator`; the canvas-side "drawable but not executed" was converged in Batch 1 by removing the entries. M2 / M6 (executor-side unification) are handled together with Batch 5.

## 4. Target split (what this document argues for)

| Layer | Carrier | Carries | Does not carry |
|---|---|---|---|
| Inside a workflow | bpmn-js designer | Device/method nodes, exclusive, parallel, condition expressions, loop back edges, variables | Cross-workflow dependencies, plate logistics |
| Experiment level | Experiment canvas | Workflow nodes, start/end, (optionally) cross-workflow parallelism | Workflow-internal gateways, device methods, variable assignment |
| Island logistics | Island flow `/island-flow` | Transport arrows between devices | Logic control |

Three boundaries follow:

1. The experiment canvas **no longer offers** node types that cannot be saved or run; either make them fully work or remove them from the palette.
2. The workflow designer gains three editing entry points: parallel gateway, condition expression and loop limit (parallel gateway, condition expression and loop limit are all done as of 2026-11-05); it does not pull in `bpmn-js-properties-panel` and reuses the lightweight `NodeSettings` panel.
3. Both condition syntaxes converge on the backend `ConditionEvaluator`; the canvas's home-grown `evalCondition` is retired so it cannot "look fine and take the wrong branch".

## 5. Implementation route (onboarding guide)

### Batch 1 · Stop the bleeding: the canvas no longer "lies" (0.5 day)

- Change `PALETTE` in `ExperimentCanvas.tsx`: keep only workflow nodes (start/end are auto-placed); remove fork/parallel/loop/join/variable and add a line saying "workflow-internal logic belongs in the workflow designer".
- Either keep device nodes but label them "this canvas only, not saved", or remove them too (see decision Q1).
- Update `scripts/e2e-canvas.py`: the assertion changes from "six kinds" to "workflow + start/end", lower `MIN_CANVAS` accordingly and state the reason in the commit message (required by the test specification).
- Documents: section 11 of `docs/实验编排-功能设计.md` and `docs/技术架构与模块地图.md:231-237` change "the canvas supports fork/parallel" to "the canvas only does cross-workflow orchestration".

### Batch 2 · Add the parallel gateway to the designer (1 day) ✅ done (2026-11-05)

- Add `webapp/src/bpmn/WorkflowPalette.ts` (a custom PaletteProvider extension at priority 900; `getPaletteEntries()` returns an updater that adds/removes on top of the default entries) and register it in the `BpmnModeler` `additionalModules`. It is not named `Parallel*` because it both adds the parallel gateway and removes the parser-unsupported entries.
- Also trim the palette: remove entries the parser does not accept (intermediate/boundary events, data objects, data stores, lanes/participants, groups). Such a node dropped on the chain is silently skipped by `GraphRunner` (`node == null → continue`, and it does not enqueue its successor), so the whole stretch after it never runs and no error is raised.
- Acceptance: it can be dragged out, connected with two outgoing and two incoming edges, saved, and `PreflightChecker` PF-05 (missing join edge) can report it; the three parallel assertions of `verify-flow-matrix.py` do not regress.
- UI test: section A of `e2e-flow-matrix.py` gains 3 assertions - "the parallel gateway can be dragged out", "the saved XML contains bpmn:parallelGateway" and "the parser-unsupported palette entries are gone" (a missing entry fails on its own line).
- **Known risk (decided 2026-11-05: record only, change nothing for now)**: the `subProcess` element is not in the parser whitelist (`WorkflowGraphParser.java:189-197`); the palette keeps `create.subprocess-expanded` so the "sub-process grouping" ability is not lost. Measured (device 200171 / method Home): `S → empty sub-process → T1 → E` reports the run `completed` while `T1=bypassed` (successors silently skipped); the `T1=completed` of `S → sub-process(containing S1→T1→E1) → E` is only an accident of "the inner `startEvent` being seeded as a top-level start by `addEvents`", **not real sub-graph semantics** (the box's own in/out edges lead nowhere). The same root cause is recorded in `docs/en/test-case-master-table.md`, honesty list item 12.

### Batch 3 · Condition-expression UI (1–1.5 days) ✅ done (2026-11-05)

- When a `bpmn:SequenceFlow` is selected, a condition-expression input and a "default edge" checkbox appear; they write `bpmn:conditionExpression` and the `default` attribute.
- Where it landed: the panel is a **separate "Sequence flow · condition" card in the right column** (a sequence flow is not a task, so it does not enter the `NodeSettings` kind tri-state); saving goes through `modeling.updateProperties` + `moddle.create('bpmn:FormalExpression')`, and when the source is a gateway it writes the gateway's `default`.
- Syntax hints reuse the backend `ConditionEvaluator` set; the frontend only lightly checks parentheses and spaces and does not build a second evaluator.
- Acceptance: the true and false branches of an exclusive gateway each run, the default edge catches the rest; a UI assertion covers "write a condition → save → execute takes the right branch".

### Batch 4 · Loop-limit UI (0.5 day) ✅ done (2026-11-05)

- Offers `maxIterations` (written to `workflow.attr.maxIterations`, **camelCase**; the backend uses the same key in `loadMaxIterations` / `setMaxIterations`, validated 1..100); the page states "workflow-level cap, not per gateway".
- Where it landed: a "Workflow options" card in the right column (workflow-level, independent of back-edge/node selection) plus `GET/PUT /api/workflows/{id}/options`.
- Acceptance: the number of `loop_index` rounds matches the setting.

### Batch 2.5 · Seam assertion: a graph drawn in the designer → a real run (2026-11-05) ✅ done

- The gap: sections A / E only proved "it can be dragged out and saved"; the `xor` / `parallel` cases in `verify-flow-matrix.py` POST `build_xml()` directly (bypassing the UI); section C borrows a historical run — so **"does a graph drawn on the page get recognised by the execution side" had no assertion at all**.
- Where it landed: `scripts/e2e-flow-matrix.py` gains a **section F** (12 assertions) — draw `S → variable node → exclusive gateway → two branches → end` on the page, set `__setVar` params (`counter=1` / `hit`) in node settings, write conditions `${counter} == 1` / `${counter} == 2` on the two outgoing flows; **click the page-level "Save" once more to persist the settings back into `workflow_node`** (same trap as `e2e-sim-flow.py`: node/flow settings only patch the graph XML, while the execution side reads the XML), then run for real.
- Assertions: the run is `completed`; `SET` actually executed (the `__setVar` set on the page is seen by the execution side); the matching branch `A=completed` and the non-matching `B=bypassed` (`markBypassedNodes` semantics).
- Trap: a newly created workflow ships with a seed graph `S → E` (`WorkflowService.blankGraphXml`); without deleting it first the flow count is off by one and both `S`/`E` ids are taken → section F calls `delete_node(S/E)` before drawing.
- Numbers: the page flow matrix goes **33 → 45**, gate `MIN_E2EFM=45` (may only go up).

### Batch 6 · Delay / wait nodes (2026-11-06) ✅ done

- Two new **built-in logic nodes** (`protocolName` starting with `__` means `Kind.BUILTIN`: no device lookup, no driver, no parser-whitelist change):
  - `__sleep` · delay: parameter `sec` (positive, seconds) - it really waits N seconds here.
  - `__wait` · wait: parameters `var` + `value` (required) and `timeout` (seconds, defaults to 300 when blank) - it polls the workflow variable until `${var} == value`, the only way it passes.
- Pause response: the wait loop polls `pauseChecker` in **500 ms slices**, so a pause takes effect within 500 ms (unlike a device node, which checks once at its action boundary; see `pause-resume-semantics-four-node-types-20261106.md`).
- Timeout policy: if `timeout` expires without the condition, it throws `EC-BUILTIN-001` → `RunProgress.manual` marks the node `MANUAL` → `WorkflowService.settleRun` settles the run as **`paused`** (`pause_kind='waiting_human'`; going to manual is a pause, so fixing the variable and pressing continue resumes it, and since the step was never recorded `completed` it re-runs), so it never **carries an unsatisfied condition forward**. `failed` is reserved for `aborted`; the `anyManual ? "failed"` line in `OpsApi` is a separate demo insert path, not the production settle.
- Wiring: `WorkflowService` injects `Sleeper.REAL` and `withVariableRefresher(() -> loadRunVars(runId))` (re-reads `run.input.vars` each round, otherwise a variable written from outside would never be seen). The refresh fills only the keys **this graph has not written**: `__setVar` / `__expr` change memory only and never write back, so a blanket `putAll` would clobber the value just written (guaranteed to bite when the run starts with a variable of the same name). Known residue: a variable this graph wrote is no longer refreshed from outside, and those values are never persisted - on resume a skipped (already `completed`) assignment node loses them.
- Acceptance: five `GraphRunnerTest` cases (sleep really waits / wait satisfied / wait timeout to manual / external refresh does not clobber this graph's own writes / the `__` prefix resolves to LOGIC), pure logic `644 → 649`; `verify-flow-matrix.py` gains a `waitsleep` section of 10 assertions, flow matrix `45 → 55` (`MIN_MATRIX=55`).

### Batch 5 · Cross-workflow parallelism (optional, needs decision Q2 first)

- To keep canvas parallel/join: first allow those two kinds in `ExperimentActionService.compileExperimentGraph` and make the experiment executor really run sub-workflows in parallel; otherwise keep them hidden.
- Risk: logical parallelism is not wall-clock parallelism; the island lock serialises devices on the same island.

### Backlog · subProcess silently skipped (decided 2026-11-05: park it, do it later)

- Problem: `subProcess` is not in the parser whitelist (`WorkflowGraphParser.java:189-197`), so an empty sub-process wired into the chain makes its successors silently **bypassed** (the run still reports `completed`). Measured evidence in §5 Batch 2 "known risk" above and honesty list item 12 of `docs/en/test-case-master-table.md`.
- Three candidate fixes (pick one when scheduled):
  1. **Preflight block**: add a generic rule to `PreflightChecker` - a node the parser does not recognise on the chain is `Level.ERROR` (keeps sub-process grouping, turns the silent skip into an explicit block);
  2. **Remove from the palette**: add `create.subprocess-expanded` to the trim list in `WorkflowPalette.ts` (most thorough, at the cost of no longer being able to group with a sub-process);
  3. **Make the parser transparent**: treat `subProcess` as a transparent container (in edges to the inner start, out edges from the inner end) for real sub-graph semantics (highest cost, changes runtime behaviour).
- Dependency: if option 3 is chosen, `GraphRunner` and the `verify-flow-matrix.py` (55) cases must be updated in step, and two contrasting assertions ("empty sub-process" / "sub-process with content") must be added.
- Why unscheduled: it does not affect the current main path (the designer normally wires start/task/gateway directly), and each of the three fixes has its own trade-off, so the approach must be agreed first.

## 6. Decisions needed

| # | Question | Recommended default |
|---|---|---|
| Q1 | Keep device nodes on the experiment canvas (direct single-device runs, but not saveable)? | Remove them; device methods are dragged in the workflow designer |
| Q2 | Keep parallel/join on the experiment canvas (cross-workflow parallelism)? | Not for now, until DAG and callActivity land together |
| Q3 | Unify conditions on the backend `ConditionEvaluator`? | Yes; retire the canvas evaluator |
| Q4 | May a parallel gateway span devices? | Yes, but state clearly "logical parallelism, not wall-clock" |

## 7. Where to start

- Key files: `webapp/src/pages/ExperimentCanvas.tsx`, `webapp/src/pages/Designer.tsx`, `webapp/src/bpmn/`, `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowGraphParser.java`, `GraphRunner.java` in the same package, `boot/web/service/ExperimentActionService.java:296-430`, `workflow/PreflightChecker.java:202-269`.
- Re-run: `./scripts/ci-gate.sh` (no database), `--all` (needs DB + sidecar + frontend); `python3 scripts/verify-flow-matrix.py` (55); `python3 scripts/e2e-flow-matrix.py` (45); `python3 scripts/e2e-canvas.py` (12).
- Number discipline: changing cases requires updating the `MIN_*` constants in `scripts/ci-gate.sh`, `docs/测试用例总表.md`, `ROADMAP.md` and review package 04 in both languages; thresholds may only go up, and a real reduction must be explained in the commit message.
- Do not touch the body of historical snapshots: `docs/评审包-20261103/`, `docs/评审包-20261104/`, `LabOS实现总纲_20261029.md`.

---

## The designer's "run view": node states right on the flow chart (2026-11-11)

### Requirement

While an experiment is running, the user wants to **open the flow editor** and see every
node's state (which one is still running, which one went manual, which one failed),
instead of jumping to a separate node table.

### Why not only `/runs/:id`

The run detail page does have a node table (with the current node highlighted), but it is a
**table**: you cannot see where the nodes sit **on the graph**, nor compare one workflow
against the next ⇒ on site you have to reconstruct "where did this workflow actually get
to" in your head.

⇒ So the **designer itself** gains a view: a URL with `?run=<runId>` enters the run view.

### Shape

| Aspect | Design | Why |
|---|---|---|
| Entry | "**Open flow chart**" per step in the experiment's "execution history" ⇒ `/designer/{workflowId}?run={runId}` | one jump lands on that workflow, that time |
| State source | `nodes` of `/api/runs/{id}`, taking the **level=0 summary row** (`loopIndex=0`) | level=1 are sub-steps, `loopIndex>0` are the individual rounds |
| Correspondence | graph element id == `run_node.node_key` | verified identical (workflow 1000546: `Activity_16n7hh7` ↔ `node_key`) |
| Colouring | bpmn-js `canvas.addMarker(id, 'run-state-<status>')` + CSS `filter: drop-shadow(...)` | device nodes are drawn by a **custom renderer** as image cards (not native rects) ⇒ changing `fill` may not hit them; `filter` works on any SVG structure |
| Colours | running = blue / manual = orange / failed = red / completed = green / skipped or not reached = faded | same family as the status vocabulary |
| Legend | a row of tags in the top banner | no need to remember |

### Boundaries

- States are fetched and colours are applied **only when `?run=` is present** — a normal
  designer session is unaffected;
- nodes that are **not on the graph** are skipped (the workflow changed, the node was
  deleted) ⇒ never colour something that is not there;
- this is an **observation view, not an editing view** — a workflow that is running should
  not be edited anyway; the banner keeps reminding "this is run #xxx";
- **no backend change at all** — the node states were already available from
  `/api/runs/{id}`.

### Measured

playwright on `/designer/1000546?run=1001035` ⇒ marker counts **running 1 / pending 2**,
the running node is exactly `Activity_16n7hh7` (same as `run_node.node_key` in the DB),
the other two pending; the banner shows `run #1001035 (failed)` plus the legend.
