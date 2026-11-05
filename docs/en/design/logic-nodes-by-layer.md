# Logic nodes by layer, and the split between the two editors (2026-11-05)

> Answers one question: **the experiment canvas has forks, parallelism, loops, joins and variables — should the workflow designer have them too?**
> Answer: yes, and the **main battlefield is inside a workflow**. The experiment canvas keeps only the minimum needed for "cross-workflow orchestration".
> This document is the implementation basis for whoever takes over; every conclusion cites a code/SQL line, and the documents are not taken on trust.

## 0. One-line conclusion

- Logic nodes (exclusive, parallel, conditions, loops, variables) **belong inside a workflow** and belong to the bpmn-js designer; the experiment canvas only does "cross-workflow orchestration".
- The real state today is a **capability mismatch**: the designer **still has entry-point gaps** (still no parallel-gateway entry in the palette; the condition-expression and loop-limit entry points were closed in Batch 3 / Batch 4), while the experiment canvas **has entry points but can neither save nor execute** (`fork/parallel/loop/variable/device/join` all return 400 on save, and `parallel` is skipped at run time).
- So the task is not "move the canvas model into the designer" but: **give the designer the editing entry points, and shrink the canvas to what it can actually run**.

## 1. The user's wording (the constraint of this document)

1. The overwhelming majority of logic happens **inside one workflow** → it must be drawable, saveable and runnable in the workflow designer.
2. Only a few cases need **cross-workflow** (experiment-level) orchestration → the experiment canvas stays, but does not carry workflow-internal semantics.
3. "Drawable but not executed" or "drawable but not saveable" is not allowed — that is worse than a missing feature.

## 2. Evidence today (capability comparison)

| Logic capability | Experiment canvas `/canvas` | Workflow designer `/designer/:id` | Backend executor | Existing assertions |
|---|---|---|---|---|
| Exclusive branch | 🟡 a `fork` node plus one condition box; `runFlow` picks an edge; **save returns 400** | ✅ can create an `exclusiveGateway`; condition/default have UI (right column "Sequence flow · condition") | ✅ `SPLIT_XOR` + condition → default → first by order | ✅ xor 6 / xor3 3 |
| Parallel fork/join | ⬜ `parallel` is skipped, `join` is visual only; save returns 400 | ⬜ **no palette entry**, XML must be hand-edited | ✅ `SPLIT_PARALLEL` activates every outgoing edge + `JOIN_PARALLEL` waits for all incoming edges | ✅ parallel 3 / loopback 2 |
| Condition expression | 🟡 a home-grown `evalCondition`: spaces required, no arithmetic/logic/parentheses, parse failure means true | ✅ sequence-flow condition UI (writes `bpmn:conditionExpression` + gateway `default`) | ✅ `conditionExpression` + `default`, `ConditionEvaluator` supports comparisons/logic/parentheses/plus-minus | ✅ builtin/outvar/xor |
| Loop | 🟡 a `loop` node that duplicates steps in the frontend (≤20); no back edge; save returns 400 | ✅ a back edge can be drawn by hand; workflow-level `maxIterations` UI (right column "Workflow options") | ✅ back edge + DFS cycle detection + `maxVisits` (default 10) + `loop_index` | ✅ loop 3 / loopback 2 |
| Variables | 🟡 a `variable` node with `k=v`, request-only; save returns 400 | ✅ `__setVar` / `__expr` panel | ✅ `Kind.BUILTIN` + device output write-back | ✅ builtin 4 / outvar 2 |
| Sub-workflow | 🟡 a `workflow` node can pick a workflow and save; **`runFlow` ignores it** | ✅ multi-step collapsible subProcess | 🟡 `BpmnExpander` expands it; `callActivity` not done | 🟡 experiment 3 |
| Manual pause | ⬜ runs synchronously, no pause | ⬜ palette has an intermediate event, the executor does not accept it | ✅ node-boundary pauseChecker + MANUAL stops the graph | ✅ pause 10 |

Legend: ✅ supported / 🟡 partial / ⬜ not supported. Evidence is in `ExperimentCanvas.tsx:184-191,750-753`, `PaletteProvider.js:161-211`, `WorkflowGraphParser.java:215-220`, `GraphRunner.java:385-394,489-500`, `QueryService.java:4069-4094`, `verify-flow-matrix.py:295-472`.

## 3. Six concrete contradictions (ordered by harm)

| # | Contradiction | Evidence |
|---|---|---|
| M1 | The canvas palette offers six logic nodes, but save/compile accepts only three (start/end/workflow); the rest always return 400 | `ExperimentCanvas.tsx:184-191`; `QueryService.java:4091-4094`; `ExperimentGraphCompileSelfTest.java:55-80` |
| M2 | What can be drawn cannot be saved, and what can be saved is not executed: `runFlow` only understands device nodes, which are exactly the kind rejected on save; workflow nodes save and compile, but `runFlow` silently ignores them | `ExperimentCanvas.tsx:703-757` |
| M3 | A parallel gateway can only be hand-written in XML, even though the executor fully supports it | `PaletteProvider.js:161-211`; `e2e-flow-matrix.py:182-185`; `GraphRunner.java:390-394,489-500` |
| M4 | Two condition-expression syntaxes; the canvas one treats a parse failure as true (silently taking the wrong branch) | `ExperimentCanvas.tsx:229-259`; `ConditionEvaluator`; `WorkflowGraphParser.java:96-121` |
| M5 | Three loop semantics: canvas duplicates steps, the backend uses a back edge plus a workflow-level cap, and the designer has no entry point | `ExperimentCanvas.tsx:734-741,761-766`; `GraphRunner.java:145-161`; `WorkflowService.java:815` |
| M6 | Two variable models that do not recognise each other: the canvas `k=v` string vs the backend `__setVar` `params{var,value}` | `ExperimentCanvas.tsx:1228-1235`; `WorkflowGraphParser.java:174`; `GraphRunner.java:449-453` |

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

### Batch 5 · Cross-workflow parallelism (optional, needs decision Q2 first)

- To keep canvas parallel/join: first allow those two kinds in `QueryService.compileExperimentGraph` and make the experiment executor really run sub-workflows in parallel; otherwise keep them hidden.
- Risk: logical parallelism is not wall-clock parallelism; the island lock serialises devices on the same island.

## 6. Decisions needed

| # | Question | Recommended default |
|---|---|---|
| Q1 | Keep device nodes on the experiment canvas (direct single-device runs, but not saveable)? | Remove them; device methods are dragged in the workflow designer |
| Q2 | Keep parallel/join on the experiment canvas (cross-workflow parallelism)? | Not for now, until DAG and callActivity land together |
| Q3 | Unify conditions on the backend `ConditionEvaluator`? | Yes; retire the canvas evaluator |
| Q4 | May a parallel gateway span devices? | Yes, but state clearly "logical parallelism, not wall-clock" |

## 7. Where to start

- Key files: `webapp/src/pages/ExperimentCanvas.tsx`, `webapp/src/pages/Designer.tsx`, `webapp/src/bpmn/`, `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowGraphParser.java`, `GraphRunner.java` in the same package, `api/QueryService.java:4003-4130`, `workflow/PreflightChecker.java:202-269`.
- Re-run: `./scripts/ci-gate.sh` (no database), `--all` (needs DB + sidecar + frontend); `python3 scripts/verify-flow-matrix.py` (45); `python3 scripts/e2e-flow-matrix.py` (33); `python3 scripts/e2e-canvas.py` (12).
- Number discipline: changing cases requires updating the `MIN_*` constants in `scripts/ci-gate.sh`, `docs/测试用例总表.md`, `ROADMAP.md` and review package 04 in both languages; thresholds may only go up, and a real reduction must be explained in the commit message.
- Do not touch the body of historical snapshots: `docs/评审包-20261103/`, `docs/评审包-20261104/`, `LabOS实现总纲_20261029.md`.
