# Test Case Master Table

> The question this answers: **is every line of the functional documentation actually
> covered by a case, in which script, and does it run in CI?**
> This table is the single ledger: rows are the feature ledger (L1–L10) from
> [functional-specification.md](functional-specification.md) §8, columns are
> case / script / layer / automated / in CI.

Companions: [test-specification.md](test-specification.md) (how to write a case),
[testing-and-release-strategy.md](testing-and-release-strategy.md) (the L1–L5 model),
[full-flow-test-matrix-design.md](full-flow-test-matrix-design.md) (the 45-case matrix in detail).

Layer definitions: **L1** pure-logic unit test (no DB), **L2** DB unit test,
**L3** chain script (needs a device / simulator), **L4** page e2e (needs frontend + API).

---

## 1. Overview

| Track | Entry point | Assertions today | In CI |
|---|---|---|---|
| L1 pure logic | `./scripts/test-all.sh --pure-only` | 624 | ✅ every push |
| L1+L2 with DB | `./scripts/test-all.sh` | 1005 | ⬜ **not covered by the gate** (`ci-gate.sh` only runs `--pure-only`; this number moving up or down is invisible to the gate) |
| L3 chain | `./scripts/verify-device-chain.sh` | 41 | ⬜ local `--all` |
| L3 flow matrix | `./scripts/verify-flow-matrix.py --force-online` | 45 | ⬜ local `--all` |
| L3 parallel | `verify-parallel-flow.py` / `verify-parallel-experiments.py` | 4 / 3 | ⬜ local `--all` |
| L4 page e2e | `scripts/e2e-*.py` | see section 2 | ⬜ local `--all` + nightly |

---

## 2. CI gate thresholds (constants at the top of `scripts/ci-gate.sh`)

| Constant | Value | Coverage |
|---|---|---|
| `MIN_PURE` | 624 | Pure-logic unit tests (fewer = red) |
| `MIN_CHAIN` | 41 | Full device chain |
| `MIN_PFLOW` / `MIN_PEXP` | 4 / 3 | In-flow parallelism / same-island concurrency |
| `MIN_MATRIX` | 45 | Full flow matrix (`verify-flow-matrix.py`) |
| `MIN_E2E` | 24 | Page main paths (`e2e-smoke.py`) |
| `MIN_E2EFM` | 45 | Page flow matrix (`e2e-flow-matrix.py`) |
| `MIN_EXPCTL` | 22 | Experiment run control (`verify-exp-control.py`) |
| `MIN_E2ESIM` | 24 | Simulator integration (`e2e-sim-flow.py`) |
| `MIN_ALERTS` | 8 | Page alert ack/close (`e2e-alerts.py`); gate lower bound, measured value in review package 04 |
| `MIN_POLICIES` | 12 | Page policy edit + validation (`e2e-policies.py`); gate lower bound, measured value in review package 04 |
| `MIN_PAUSED` | 7 | Page paused queue filter and actions (`e2e-paused.py`); gate lower bound, measured value in review package 04 |
| `MIN_CANVAS` | 12 | Experiment orchestration canvas · cross-workflow orchestration (`e2e-canvas.py`); gate lower bound, measured value in review package 04 |
| `MIN_METHOD_DROP` | 17 | Device images / a method node with its image / automatic robot-arm insertion across devices / deletion (`e2e-method-drop.py`); 17 resident assertions without a DB, 20 with one |
| `MIN_CRASH` | 34 | Crash recovery end to end (`verify-s3-2-residuals.py`); kill -9 → restart → queued rehydrate + orphan running settled by the startup fallback + `paused` not killed by mistake + queued zombie (queued for over 1 hour) collected |
| `MIN_MULTIPAR` | 21 | Live page exercise · multiple parallel gateways + multiple exclusive gateways + variables (`e2e-multi-parallel.py`); drag 12 nodes / 13 edges on the page → save → really run → all three parallel branches completed |

---

## 3. Feature ledger × cases

### L1 Orchestration

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 1.1 | Workflow designer (drag nodes, connect, open stored BPMN) | `e2e-flow-matrix.py` section A; `e2e-smoke.py` §2 | L4 | ✅ | ✅ |
| 1.2 | Multi-step node collapsible in the UI | `e2e-flow-matrix.py` section A (reopen after save) | L4 | ✅ | ✅ |
| 1.3 | Auto-compile (runtime expansion) | `api.ExperimentGraphCompileSelfTest`; `workflow.RecipeCompilerTest` | L1 | ✅ | ✅ |
| 1.4 | Decision (XOR, with default edge) | `verify-flow-matrix.py` xor / xor3; `workflow.GraphRunnerTest`; `e2e-flow-matrix.py` section E (sequence-flow condition + gateway default); section F (designer-drawn XOR branch graph → real run → matching branch completed / non-matching branch bypassed) | L1+L3+L4 | ✅ | ✅ |
| 1.5 | Parallel (fork and join) | `verify-flow-matrix.py` parallel; `verify-parallel-flow.py`; `workflow.GraphRunnerTest`; `e2e-flow-matrix.py` section A (palette parallel gateway entry + persistence + palette trim) | L1+L3+L4 | ✅ | ✅ |
| 1.6 | Time Constraint (min / max interval) | `engine.TimeConstraintSelfTest` (25 cases, pure logic); `engine.TimeConstraintExecutor` | L1 | ✅ | ✅ |
| 1.7 | Sequential execution of experiment blocks | `workflow.ExperimentOrderSelfTest`; `verify-flow-matrix.py` experiment | L1+L3 | ✅ | ✅ |
| 1.8 | Sub-process executes as a sub-graph | `e2e-sim-flow.py` section A (page assembly) | L4 | ✅ | ✅ |
| 1.9 | Loop, Wait Until, variables, conditional / loop | `verify-flow-matrix.py` loop / loopback; `workflow.GraphRunnerTest`; `e2e-flow-matrix.py` section E (logic-node tab / workflow-level maxIterations) | L1+L3+L4 | ✅ | ✅ |
| 1.10 | Plate type and gripper offset | Out of scope, no case | — | ⬜ | ⬜ |

### L2 Validation

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 2.1 | Pre-save preflight (capability without device / XOR without condition / parallel without join) | `verify-device-chain.sh` §10 save guard; `e2e-flow-matrix.py` section A preflight tag | L3+L4 | ✅ | ✅ |
| 2.2 | Cycle, isolated node, missing start / end | `workflow.ExperimentOrderSelfTest` (cycle rejected); `GraphDiRepair` | L1 | ✅ | ✅ |
| 2.3 | Compile error panel, double-click to locate | No case | — | ⬜ | ⬜ |
| 2.4 | Reference to a retired device | Partially implemented: the heartbeat can detect offline and opens a `DEVICE-OFFLINE` alert, but preflight does not block; no case | — | ⬜ | ⬜ |
| 2.5 | "This command cannot be simulated by pure delay" hint | No case | — | ⬜ | ⬜ |
| 2.6 | Batch capacity (enough plate positions) | Out of scope, no case | — | ⬜ | ⬜ |

### L3 Execution

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 3.1 | State-machine execution + event stream | `engine.EngineSelfTest`; `verify-flow-matrix.py` serial | L1+L3 | ✅ | ✅ |
| 3.2 | Runtime capability-to-device assignment | `workflow.RecipeCompilerTest`; `verify-device-chain.sh` §9 binding table | L1+L3 | ✅ | ✅ |
| 3.3 | Multi-step execution and failed-step location | `verify-device-chain.sh` §6 multi-node resume | L3 | ✅ | ✅ |
| 3.4 | Attended / unattended switching | `workflow.RunControlSelfTest`; `e2e-smoke.py` §1 attendance | L1+L4 | ✅ | ✅ |
| 3.5 | Reassign when a device is offline or fails; `MANUAL` with no alternative | `recovery.RecoverySelfTest`; `verify-flow-matrix.py` pause | L1+L3 | ✅ | ✅ |
| 3.6 | Resume from a breakpoint | `workflow.StartupSettleSelfTest`; `verify-device-chain.sh` §6 | L2+L3 | ✅ | ✅ |
| 3.7 | Command-level step forward / back | Not implemented, no case | — | ⬜ | ⬜ |
| 3.8 | Edit after pause, continue from the failed command | `verify-exp-control.py`; `e2e-flow-matrix.py` section D | L4 | ✅ | ✅ |

### L4 Scheduling

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 4.1 | Wait or reassign when a device is busy | `verify-parallel-flow.py` (same device serialised) | L3 | ✅ | ✅ |
| 4.2 | Ready three-state and pull loop | `verify-parallel-experiments.py` | L3 | ✅ | ✅ |
| 4.3 | Static scheduling, dependencies other than Run After | Static scheduling not implemented (outside the release); the `Run After` order is covered by `verify-scheduler-acceptance.py` | — | ⬜ | ⬜ |
| 4.4 | Global makespan optimisation | Out of scope, no case | — | ⬜ | ⬜ |
| — | Scheduler queue rehydrate after restart | `workflow.SchedulerRehydrateSelfTest`; `verify-s3-2-residuals.py` | L2+L3 | ✅ | ✅ |

### L5 Simulation

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 5.1 | Virtual driver | `driver.VirtualDriver`; `sim.SimulatorBehaviorSelfTest` | L1 | ✅ | ✅ |
| 5.2 | Command-level duration model | `workflow.SchedulerEtaSelfTest` | L1 | ✅ | ✅ |
| 5.3 | Import durations from the DB (cleaned samples) | `workflow.SchedulerEtaSelfTest` | L1 | ✅ | ✅ |
| 5.4 | Total duration prediction | `workflow.SchedulerEtaSelfTest`; page `/duration` | L1 | ✅ | ✅ |
| 5.5 | Gantt (predicted vs actual) | Page `/gantt`, manual only | L4 | ⬜ | ⬜ |
| 5.6 | Throttle: speed up waiting | No case | — | ⬜ | ⬜ |
| 5.7 | Simulated-error UI | `sim.SimulatorBehaviorSelfTest` (injectable in the driver) | L1 | ✅ | ✅ |
| 5.8 | Switch one device between simulation and real | Partially implemented: `VirtualDriver` exists (with `simulated` metadata), but there is no per-device toggle; no case | — | ⬜ | ⬜ |

### L6 Errors and recovery

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 6.1 | Pure function from error code to action | `recovery.RecoverySelfTest`; `driver.DeviceErrorChainSelfTest` | L1 | ✅ | ✅ |
| 6.2 | Tiered trace and alert aggregation | `alert.AlertSelfTest`; `workflow.WorkflowAlertTest` | L1 | ✅ | ✅ |
| 6.3 | Contract layer rejects catch-all codes | `driver.DeviceErrorChainSelfTest` | L1 | ✅ | ✅ |
| 6.4 | Policy replay hit distribution (no SKIP-padding coverage) | `e2e-policies.py` effective validation; `api.validatePolicy` | L4 | ✅ | ✅ |
| 6.5 | Attended window (the auto dialog from `recovery_policy` is not done; manual pause / disposition pages available) | `verify-exp-control.py`; `e2e-paused.py`; `e2e-flow-matrix.py` §Run detail | L4 | 🟡 | ✅ |
| 6.6 | Call-stack view | Out of scope, no case | — | ⬜ | ⬜ |
| 6.7 | `COMPENSATE` | Out of scope, no case | — | ⬜ | ⬜ |

### L7 Monitoring

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 7.1 | Run detail (node tree + event stream) | `e2e-smoke.py` §3; `e2e-flow-matrix.py` section C | L4 | ✅ | ✅ |
| 7.2 | Alert acknowledgement and close | `e2e-alerts.py` | L4 | ✅ | ✅ |
| 7.3 | Process Status / paused queue | `e2e-paused.py`; `verify-exp-control.py` | L4 | ✅ | ✅ |
| 7.4 | Device status colouring (busy / idle / offline / error) | `e2e-smoke.py` §6 (scheduler page) | L4 | ✅ | ✅ |
| 7.5 | Plate-level Gantt, scheduling decision trace | Out of scope, no case | — | ⬜ | ⬜ |

### L8 Device onboarding

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 8.1 | Driver contract (capability, error codes, raw response, idempotency) | `verify-device-chain.sh` §5 probe; `driver.DriverContractDemo` | L3 | ✅ | ✅ |
| 8.2 | .NET Sidecar local mock | `scripts/test-sidecar.sh` | L3 | ✅ | ⬜ |
| 8.3 | Shadow mode for one real endpoint | `verify-device-chain.sh` §5; `driver.ShadowProbe` | L3 | ✅ | ✅ |
| 8.4 | The remaining driver endpoints | Out of scope, no case | — | ⬜ | ⬜ |
| 8.5 | Teach pendant | Out of scope, no case | — | ⬜ | ⬜ |

### L9 Operations

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 9.1 | Policy create/edit/delete + replay hits before save | `e2e-policies.py` | L4 | ✅ | ✅ |
| 9.2 | Run recovery after a crash | `workflow.StartupSettleSelfTest`; `verify-s3-2-residuals.py` (kill -9 → restart, end to end) | L2+L3 | ✅ | ✅ |
| 9.3 | Roles, permissions, audit log | `api.RbacRulesSelfTest`; `api.UserRoleDbSelfTest`; `verify-rbac.py` | L1+L2+L3 | ✅ | ⬜ |
| — | On-site hold (pause / resume / stop) | `verify-exp-control.py`; `e2e-flow-matrix.py` section D | L4 | ✅ | ✅ |

### L10 Containers and inventory

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 10.1 | Container identity: barcode and plate type referenced by a run | Out of scope; only `plate` / `plate_well` migrated | — | ⬜ | ⬜ |
| 10.2 | Nest-level position, replenishment while running | Out of scope, no case | — | ⬜ | ⬜ |
| 10.3 | Plate-type propagation and gripper offset | Out of scope, no case | — | ⬜ | ⬜ |

### L11 Run state and lifecycle (spec §13)

| Spec section | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 13.1 | Five-level status vocabulary (run/node/step/experiment/batch contract, terminal/active sets) | `workflow.StatusVocabularySelfTest` | L1 | ✅ | ✅ |
| 13.2 | run→experiment status mapping + the persisted SQL CASE pinned word for word | `workflow.StatusVocabularySelfTest` | L1 | ✅ | ✅ |
| 13.3 | Three pause kinds: grammar and semantics | `workflow.StatusVocabularySelfTest` (contract); `workflow.PauseManagerSelfTest` (an unknown plate position must be reconciled before resume) | L1+L2 | ✅ | L1 in CI |
| 13.4 | On-site hold (kind × scope, priority experiment>island>site) + return position kept | `workflow.RuntimeHoldSelfTest`; `workflow.RuntimeHoldDbSelfTest`; `workflow.PauseManagerSelfTest` | L1+L2 | ✅ | L1 in CI |
| 13.5 | Crash recovery (orphan running→failed + `recoverable` + 7-day window) | `workflow.StartupSettleSelfTest` (L2, the method); `verify-s3-2-residuals.py` (L3, end to end: kill -9 → restart → startup fallback settle) | L2+L3 | ✅ | ✅ |
| 13.6 | Batched execution S3.2 (split / `attempt_no` rerun / a completed batch refuses a rerun) | `workflow.BatchPlannerSelfTest`; `workflow.BatchDbSelfTest`; `verify-exp-batch.py` | L1+L2+L3 | ✅ | L1 in CI |
| 13.7 | Resume from a breakpoint (`seq:loop_index` + `skipKeys`) | `verify-device-chain.sh` §6; `workflow.RunControlDbSelfTest`; `workflow.GraphRunnerTest` (a hit is skipped / a rename mismatch re-runs) | L3+L2+L1 | ✅ | L1 in CI |
| 13.8 | Attendance mode (attended/unattended) switching and alert level | `workflow.RunControlSelfTest`; `e2e-smoke.py` §1 | L1+L4 | ✅ | L1 in CI |

### L12 Concurrency, resources and scheduling (spec §14)

| Spec section | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 14.1 | Three levels of concurrency (parallel within one experiment / serial on the same island, peak = 1 / across islands) | `verify-parallel-flow.py`; `verify-parallel-experiments.py` | L3 | ✅ | ⬜ local |
| 14.2 | Four-level resource model (L1 position / L2 channel / L3 door / L4 robot arm) | `driver.TransferPlanSelfTest` (acquire / release / rollback on failure) | L2 | ✅ | ⬜ local |
| 14.3 | Fixed acquisition order (source door→source channel→source position→target door→…→robot arm) + return-position exemption | `driver.TransferPlanSelfTest` | L2 | ✅ | ⬜ local |
| 14.4 | Wait-for graph deadlock detection (DFS cycle check, always on, no switch) | `driver.WaitForGraphSelfTest` | L1 | ✅ | ✅ |
| 14.5 | Why not Banker's algorithm | Design note, no case needed | — | — | — |
| 14.6 | Scheduler (ready three-state / island-lock CAS / EDD→priority→FIFO / rehydrate) | `workflow.SchedulerOrderSelfTest` (the three ordering tiers, one by one); `workflow.SchedulerRehydrateSelfTest`; `workflow.SchedulerEtaSelfTest`; `verify-scheduler-acceptance.py` | L1+L2+L3 | ✅ | L1 in CI |
| 14.7 | Duration model (p50 / p90, cleaned samples only) | `workflow.SchedulerEtaSelfTest` | L1 | ✅ | ✅ |
| 14.8 | Rolling schedule (commit window / 15-minute freeze zone / greedy) | No case (out of scope) | — | ⬜ | ⬜ |

---

## 4. Cross-cutting and infrastructure

| Case / script | Coverage | Layer | In CI |
|---|---|---|---|
| `scripts/check-schema-sync.sh` | Schema artifact vs shards (`loop_index` / 4-column unique key / parent FK) | L1 | ✅ |
| `api.JsonFieldSelfTest` | Minimal JSON value extraction: Unicode escape decoding (Chinese names are no longer stored as literal escapes), malformed data does not crash | L1 | ✅ |
| `scripts/verify-en-docs.py` | Chinese/English documents, pair by pair (structure / links / residual Chinese) | — | ⬜ |
| `scripts/scan-i18n.py` | Frontend i18n: key parity / code coverage / residual Chinese | — | ⬜ |
| `scripts/test-sidecar.sh` | Standalone Sidecar protocol | L3 | ⬜ |
| `scripts/load-test.sh` / `soak-test.sh` | Load test / soak test | L3 | ⬜ |
| `scripts/device-go-live-check.py` | Device go-live pre-checks | L3 | ⬜ |
| `scripts/verify-all.sh` / `verify-m1..m5.sh` | Milestone acceptance (historical) | L3 | ⬜ |
| `scripts/e2e-method-drop.py` | Island devices → drag a method to create a node: XML binding / compile recognition / persistence (skipped when the island is empty or the device has no methods) | L4 | ✅ |
| `scripts/e2e-multi-parallel.py` | Live page exercise · multiple parallel gateways + multiple exclusive gateways + variables: drag 12 nodes / 13 edges on the page → the graph holds 2 parallelGateway + 2 exclusiveGateway → really run → all three parallel branches completed | L4 | ✅ |

---

## 5. Known gaps (honest list)

| # | Gap | Risk | Why it is not done |
|---|---|---|---|
| 1 | `/gantt`, `/duration`, `/plates`, `/island`, `/island-flow`, `/topology`, `/audit`, the home dashboard | A wrong rendering can only be found by hand | Not P0; the four P0 pages (`/alerts` `/policies` `/paused` `/canvas`) are covered by `e2e-*`; the rest come later |
| 2 | The attended auto dialog (item 6.5, P0: a dialog whose default comes from `recovery_policy`) | Unattended only raises an alert and shows no dialog; attended needs a person to go to the paused-queue page, the default action is never offered automatically | Not implemented; the manual pause + disposition pages (`PausedRuns` / `RunDetail`) exist and are covered by `e2e-paused.py` |
| 3 | A loop crossing a parallel fork | The combined scenario may deadlock | Combinatorial explosion; single scenarios first |
| 4 | The trace of the iteration number of a loop re-entry | The intermediate state of the N-th iteration is not visible | It needs a change to the `run_node` table structure |
| 5 | Multi-tenant isolation | Only one tenant has been run | No second data set |
| 6 | Real hardware (not simulators) | Timing / concurrency / exception differences | No real device environment |
| 7 | Soak (running for days) | Memory / connection leaks | No soak environment |
| 8 | Correctness under load (not just throughput) | State corruption under lock contention | The load test only measured throughput |
| 9 | Rolling schedule (spec §14.8: commit window / 15-minute freeze zone / greedy) | A broken commit window or freeze zone scrambles the experiments already committed | Out of scope, not yet implemented enough to be testable |
| 10 | Backend run-path automatic robot-arm insertion + a combined plate-move assertion | The designer inserts `Transfer` while a graph is built, but `WorkflowBuilder` / `GraphRunner` / `flow-run` do not add it at run time; whether a plate really moves from position A to B via the arm is not asserted (`position_state` only covers the UNKNOWN lock-out) | The §14.2/§14.3 expansion rule has to move from the frontend into the executor and depends on taught `robot_point.code`; recorded in the decision log and D16, a separate batch |
| 11 | Logic nodes on the experiment canvas cannot be saved, and parallel does not execute | Dragging fork/parallel/loop/join/variable/device nodes on `/canvas` makes saving fail in `compileExperimentGraph` (only start/end/workflow are accepted), while `runFlow` skips parallel, treats join as a visual marker and ignores workflow nodes; "drawn but not executed" misleads more than a missing feature | Settled as "the canvas only does cross-workflow orchestration"; batch 1 has stopped the bleeding - the `/canvas` palette now offers only "workflow", start/end are laid out automatically, and the canvas no longer calls `flow-run`; adding parallel/condition/loop limits to the designer is in `docs/en/design/logic-nodes-by-layer.md` §5 - batch 2 (parallel gateway, including palette trimming), batch 3 (condition expression) and batch 4 (loop limit) are all done |
| 12 | An "expanded sub-process" from the designer palette silently skips its successors once wired into the chain | `S → empty sub-process → T → E` really reports the whole run `completed` with `T=bypassed` and no error; `subProcess` is not in the parser whitelist (`WorkflowGraphParser.java:189-197`), so the box is not in `nodes` → `GraphRunner.java:298-301` `node == null → continue`, and its successor is never enqueued | Decided on 2026-11-05 to "record only, change nothing for now" (keeps the sub-process grouping ability); fix and evidence in `docs/en/design/logic-nodes-by-layer.md` §5, Batch 2 known risk |

---

## 6. How to maintain this table

- Added a case: add the script name and layer to the matching ledger row, and sync the `MIN_*` threshold at the top of `ci-gate.sh`.
- Removed a case: mark the row ⬜ with the reason; a lowered threshold must be explained in the commit message.
- New page: read the four mandatory surfaces in [test-specification.md](test-specification.md) §4.3 first, then decide whether it goes into CI.
- This table and the thresholds in `ci-gate.sh` must agree one for one; if they disagree, `ci-gate.sh` wins and this table is corrected.
