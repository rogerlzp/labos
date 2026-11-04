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
| L1 pure logic | `./scripts/test-all.sh --pure-only` | 547 | ✅ every push |
| L1+L2 with DB | `./scripts/test-all.sh` | 917 | ⬜ local `--with-db` |
| L3 chain | `./scripts/verify-device-chain.sh` | 41 | ⬜ local `--all` |
| L3 flow matrix | `./scripts/verify-flow-matrix.py --force-online` | 45 | ⬜ local `--all` |
| L3 parallel | `verify-parallel-flow.py` / `verify-parallel-experiments.py` | 4 / 3 | ⬜ local `--all` |
| L4 page e2e | `scripts/e2e-*.py` | see section 2 | ⬜ local `--all` + nightly |

---

## 2. CI gate thresholds (constants at the top of `scripts/ci-gate.sh`)

| Constant | Value | Coverage |
|---|---|---|
| `MIN_PURE` | 547 | Pure-logic unit tests (fewer = red) |
| `MIN_CHAIN` | 41 | Full device chain |
| `MIN_PFLOW` / `MIN_PEXP` | 4 / 3 | In-flow parallelism / same-island concurrency |
| `MIN_MATRIX` | 45 | Full flow matrix (`verify-flow-matrix.py`) |
| `MIN_E2E` | 24 | Page main paths (`e2e-smoke.py`) |
| `MIN_E2EFM` | 21 | Page flow matrix (`e2e-flow-matrix.py`) |
| `MIN_EXPCTL` | 22 | Experiment run control (`verify-exp-control.py`) |
| `MIN_E2ESIM` | 24 | Simulator integration (`e2e-sim-flow.py`) |
| `MIN_ALERTS` | see script | Page alert ack/close (`e2e-alerts.py`) |
| `MIN_POLICIES` | see script | Page policy edit + validation (`e2e-policies.py`) |
| `MIN_PAUSED` | see script | Page paused queue filter and actions (`e2e-paused.py`) |
| `MIN_CANVAS` | see script | Experiment orchestration canvas (`e2e-canvas.py`) |

---

## 3. Feature ledger × cases

### L1 Orchestration

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 1.1 | Workflow designer (drag nodes, connect, open stored BPMN) | `e2e-flow-matrix.py` section A; `e2e-smoke.py` §2 | L4 | ✅ | ✅ |
| 1.2 | Multi-step node collapsible in the UI | `e2e-flow-matrix.py` section A (reopen after save) | L4 | ✅ | ✅ |
| 1.3 | Auto-compile (runtime expansion) | `api.ExperimentGraphCompileSelfTest`; `workflow.RecipeCompilerTest` | L1 | ✅ | ✅ |
| 1.4 | Decision (XOR, with default edge) | `verify-flow-matrix.py` xor / xor3; `workflow.GraphRunnerTest` | L1+L3 | ✅ | ✅ |
| 1.5 | Parallel (fork and join) | `verify-flow-matrix.py` parallel; `verify-parallel-flow.py`; `workflow.GraphRunnerTest` | L1+L3 | ✅ | ✅ |
| 1.6 | Time Constraint (min / max interval) | Only `engine.TimeConstraintExecutor` exists, no case | — | ⬜ | ⬜ |
| 1.7 | Sequential execution of experiment blocks | `workflow.ExperimentOrderSelfTest`; `verify-flow-matrix.py` experiment | L1+L3 | ✅ | ✅ |
| 1.8 | Sub-process executes as a sub-graph | `e2e-sim-flow.py` section A (page assembly) | L4 | ✅ | ✅ |
| 1.9 | Loop, Wait Until, variables, conditional / loop | `verify-flow-matrix.py` loop / loopback; `workflow.GraphRunnerTest` | L1+L3 | ✅ | ✅ |
| 1.10 | Plate type and gripper offset | Out of scope, no case | — | ⬜ | ⬜ |

### L2 Validation

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 2.1 | Pre-save preflight (capability without device / XOR without condition / parallel without join) | `verify-device-chain.sh` §10 save guard; `e2e-flow-matrix.py` section A preflight tag | L3+L4 | ✅ | ✅ |
| 2.2 | Cycle, isolated node, missing start / end | `workflow.ExperimentOrderSelfTest` (cycle rejected); `GraphDiRepair` | L1 | ✅ | ✅ |
| 2.3 | Compile error panel, double-click to locate | No case | — | ⬜ | ⬜ |
| 2.4 | Reference to a retired device | No case | — | ⬜ | ⬜ |
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
| 4.3 | Static scheduling, dependencies before Run After | `workflow.SchedulerEtaSelfTest`; `verify-scheduler-acceptance.py` | L1+L3 | ✅ | ⬜ |
| 4.4 | Global makespan optimisation | Out of scope, no case | — | ⬜ | ⬜ |
| — | Scheduler queue rehydrate after restart | `workflow.SchedulerRehydrateSelfTest`; `verify-s3-2-residuals.py` | L2+L3 | ✅ | ⬜ |

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
| 5.8 | Switch one device between simulation and real | No case | — | ⬜ | ⬜ |

### L6 Errors and recovery

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 6.1 | Pure function from error code to action | `recovery.RecoverySelfTest`; `driver.DeviceErrorChainSelfTest` | L1 | ✅ | ✅ |
| 6.2 | Tiered trace and alert aggregation | `alert.AlertSelfTest`; `workflow.WorkflowAlertTest` | L1 | ✅ | ✅ |
| 6.3 | Contract layer rejects catch-all codes | `driver.DeviceErrorChainSelfTest` | L1 | ✅ | ✅ |
| 6.4 | Policy replay hit distribution (no SKIP-padding coverage) | `e2e-policies.py` effective validation; `api.validatePolicy` | L4 | ✅ | ✅ |
| 6.5 | Attended window | No case | — | ⬜ | ⬜ |
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
| 9.2 | Run recovery after a crash | `workflow.StartupSettleSelfTest`; `verify-s3-2-residuals.py` | L2+L3 | ✅ | ⬜ |
| 9.3 | Roles, permissions, audit log | `api.RbacRulesSelfTest`; `api.UserRoleDbSelfTest`; `verify-rbac.py` | L1+L2+L3 | ✅ | ⬜ |
| — | On-site hold (pause / resume / stop) | `verify-exp-control.py`; `e2e-flow-matrix.py` section D | L4 | ✅ | ✅ |

### L10 Containers and inventory

| Item | Feature | Case / script | Layer | Automated | In CI |
|---|---|---|---|---|---|
| 10.1 | Container identity: barcode and plate type referenced by a run | Out of scope; only `plate` / `plate_well` migrated | — | ⬜ | ⬜ |
| 10.2 | Nest-level position, replenishment while running | Out of scope, no case | — | ⬜ | ⬜ |
| 10.3 | Plate-type propagation and gripper offset | Out of scope, no case | — | ⬜ | ⬜ |

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

---

## 5. Known gaps (honest list)

| # | Gap | Risk | Why it is not done |
|---|---|---|---|
| 1 | `/gantt`, `/duration`, `/plates`, `/island`, `/island-flow`, `/topology`, `/audit`, the home dashboard | A wrong rendering is only found by hand | Not P0; the four P0 pages (`/alerts` `/policies` `/paused` `/canvas`) are now covered (4.8-4.11) |
| 2 | Loop crossing a parallel fork | Possible deadlock | Combinatorial explosion; single scenarios first |
| 3 | Arithmetic inside a condition | `${a}+1 > 3` fails on site | The evaluator does not support it |
| 4 | Multi-tenant isolation | Only one tenant has been run | No second data set |
| 5 | Real hardware (not simulators) | Timing / concurrency / exception differences | No real device environment |
| 6 | Soak (running for days) | Memory / connection leaks | No soak environment |
| 7 | Correctness under load (not just throughput) | State corruption under lock contention | The load test only measured throughput |
| 8 | Resume after renaming a node | skipKeys match on `node_key`; a rename breaks it | No case constructed |

---

## 6. How to maintain this table

- Added a case: add the script name and layer to the matching ledger row, and sync the `MIN_*` threshold at the top of `ci-gate.sh`.
- Removed a case: mark the row ⬜ with the reason; a lowered threshold must be explained in the commit message.
- New page: read the four mandatory surfaces in [test-specification.md](test-specification.md) §4.3 first, then decide whether it goes into CI.
- This table and the thresholds in `ci-gate.sh` must agree one for one; if they disagree, `ci-gate.sh` wins and this table is corrected.
