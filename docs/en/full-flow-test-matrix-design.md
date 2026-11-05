# Full Workflow Test Matrix (Design)

> Applies to: LabOS 2.0
> Related: [workflow-control-test-report.md](workflow-control-test-report.md) · [workflow-control-design.md](workflow-control-design.md) · [parallel-capability-verification.md](parallel-capability-verification.md)

---

## 1. Purpose and scope

Bring the whole workflow / experiment layer — from nodes to port topology, from a single
workflow to concurrent experiments, from normal running to pause and resume — into one
**re-runnable test matrix**, and state clearly what is covered, what is a gap, and what is
a capability boundary.

Covered: execution-side nodes and gateways, variables and conditions, loops, parallelism,
device inlet/outlet topology, experiment orchestration, concurrency, pause and resume.
Not covered: the device driver and protocol implementation (see
`scripts/verify-device-chain.sh`). Frontend interaction is covered by the **page-side
matrix** and the main-path e2e (see §10, `scripts/e2e-smoke.py`).

## 2. Capability inventory

| Dimension | Capability | Authoritative implementation |
|---|---|---|
| Node types | `TASK` / `START` / `END` / `SPLIT_PARALLEL` / `JOIN_PARALLEL` / `SPLIT_XOR` / `JOIN_XOR` / `BUILTIN` | Orchestration compiler |
| Built-in nodes | `__setVar` (`var`+`value`) / `__expr` (`var`+`expr`) | Executor |
| Gateway semantics | Exclusive: evaluated in declaration order, the first true branch wins, otherwise `default` | Compiler / executor |
| Variables | `run?vars=k=v` seeds them; built-in nodes write them; device response fields write them; conditions read `${var}` | Runtime context |
| Condition evaluation | Comparison / logic / parentheses / add-subtract arithmetic (`${a} + 1 > 3`) | `ConditionEvaluator` |
| Loop | Back edge + `maxIterations` (**workflow level**) + `loop_index` per round | Graph executor |
| Parallel | Split / join; same device serialised (`DeviceLock`); cross-device parallel | Executor |
| Port topology | `both` / `in`+`out` split / multiple inlets; channel capacity | `TransferPlan` |
| Experiment orchestration | An experiment = several workflows expanded by `seq` + edge topology | `ExperimentExecutor` |
| Concurrency | Same-island mutual exclusion (peak parallelism = 1) | Scheduler |
| Pause | `paused_fault` / `waiting_human` / `paused_unknown` + resume actions | `PauseManager` |
| Heartbeat | 5 s period / 20 s offline threshold / auto-resume | `HeartbeatService` |

## 3. Coverage matrix

| Requirement | Entry point | Section | Status |
|---|---|---|---|
| All node types (START / END / TASK) | `verify-flow-matrix.py` | serial | ✅ 4 assertions |
| Built-in nodes `__setVar` / `__expr` | `verify-flow-matrix.py` | builtin | ✅ 4 assertions |
| Variables: seeding to gateway-driven | `verify-flow-matrix.py` | builtin | ✅ |
| Device response field to variable to condition | `verify-flow-matrix.py` | outvar | ✅ 2 assertions |
| Exclusive gateway (true / default) | `verify-flow-matrix.py` | xor | ✅ 6 assertions |
| Three-way exclusive (declaration order) | `verify-flow-matrix.py` | xor3 | ✅ 3 assertions |
| Back-edge loop + iteration cap + per-round | `verify-flow-matrix.py` | loop | ✅ 3 assertions |
| Back edge spanning a parallel split (loop body contains a parallel region) | `verify-flow-matrix.py` | loopback | ✅ 2 assertions |
| Parallel split / join | `verify-flow-matrix.py` | parallel | ✅ 3 assertions |
| Multiple devices: same-device serialised / cross-device parallel | `verify-flow-matrix.py` | parallel | ✅ |
| Pause (three kinds) + resume | `verify-flow-matrix.py` | pause | ✅ 10 assertions |
| Fault pause + heartbeat auto-resume | `verify-flow-matrix.py` | pause ④ | ✅ |
| Device inlet/outlet: both / split / multiple inlets | `verify-flow-matrix.py` | ports | ✅ 4 assertions |
| Multiple workflows (several tasks in one workflow) | `verify-flow-matrix.py` | serial | ✅ |
| Concurrent workflows (several runs of the same workflow) | `verify-flow-matrix.py` | concurrent | ✅ 1 assertion |
| Experiment (single workflow) | `verify-parallel-experiments.py` | — | ✅ 3 assertions |
| Several workflows inside one experiment (topology expansion) | `verify-flow-matrix.py` | experiment | ✅ 3 assertions |
| Concurrent experiments (same-island exclusion) | `verify-parallel-experiments.py` | — | ✅ 3 assertions |
| Experiment-level pause / resume / stop (UI) | `verify-exp-control.py` | — | ✅ 22 assertions |
| Device chain (driver / protocol) | `verify-device-chain.sh` | — | ✅ 41 assertions |
| Frontend main paths | `e2e-smoke.py` | — | ✅ 24 assertions |
| Page-side: designer assembly / variable node / logic nodes and sequence-flow conditions / run detail / page pause-resume | `e2e-flow-matrix.py` | — | ✅ 30 assertions |
| Simulator integration: build workflow on the page → bind a real device → build experiment → run → real response | `e2e-sim-flow.py` | — | ✅ 24 assertions |

## 4. Entry points and how to run

```bash
export PGPASSWORD='...' \
       LABOS_DB_HOST=... LABOS_DB_PORT=5432 LABOS_DB_NAME=... \
       LABOS_DB_USER=... LABOS_DB_PASS='...'
python3 scripts/verify-flow-matrix.py --force-online
# Expected: 45 passed / 0 failed
```

The `ports` section needs the kernel started with `LABOS_TRANSFER_PLAN=1`, otherwise the
whole section is skipped:

```bash
LABOS_TRANSFER_PLAN=1 ... java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.api.ApiServer 18080
```

## 5. Section detail

| Section | Assertions | Key assertions |
|---|---|---|
| serial | 4 | Run `completed`; both tasks done; two device commands |
| builtin | 4 | The variable really changed (branch A taken); the unselected branch is `bypassed` |
| outvar | 2 | A device response field reaches the variable table and drives the condition |
| xor | 6 | True goes to A; false goes to default; both paths marked `bypassed` |
| xor3 | 3 | Declaration-order evaluation; three inputs each take one branch |
| loop | 3 | The cap is reached; each round has its own `loop_index`; a false condition skips the back edge |
| loopback | 2 | A loop body containing a parallel region does not deadlock; every round has both branches completed |
| parallel | 3 | Same-device command windows do not overlap; cross-device parallelism |
| pause | 10 | Three pause kinds + resume semantics + corrections + heartbeat auto-resume |
| ports | 4 | All three topologies release their channel on completion; `in` is consumed |
| experiment | 3 | Both workflows of the experiment are registered, the run completes, both produce a run |
| concurrent | 1 | Two workflows submitted concurrently, each completes |

## 6. Gaps and attribution

| # | Gap | Attribution | Plan |
|---|---|---|---|
| 1 | A back edge pointing straight at a parallel split gateway | Unsupported (the whole parallel region is bypassed) | Class C: capability boundary, see §7 |
| 2 | Iteration cap configured per gateway | Unsupported (workflow level) | Class C: capability boundary, see §7 |
| 3 | OR / inclusive gateway | Unsupported | Class C: capability boundary, see §7 |

## 7. Class C capability boundaries

| # | Boundary | Fact | Impact |
|---|---|---|---|
| 1 | No OR / inclusive gateway | On the execution side there is only exclusive (`SPLIT_XOR`) and parallel (`SPLIT_PARALLEL`) | An orchestration that needs "every satisfied branch runs" must use a parallel gateway |
| 2 | The iteration cap is workflow-level | `maxIterations` comes from `workflow.attr`, not per gateway | Several loops in one workflow share the same cap |
| 3 | `TransferPlan` always takes `in` | Source / target channels always resolve to `in`; the `out` direction is not consumed by the current execution chain | `out` only declares capacity; it creates no separate occupancy |
| 4 | Heartbeat auto-resume requires no offline device in the tenant | `autoResumeFaulted` tests `NOT EXISTS(... online=false)` | A single offline device stops a fault pause from auto-resuming |
| 5 | A back edge cannot point straight at a parallel split gateway | Pointing a back edge at a parallel gateway bypasses the whole parallel region (the run still reports `completed`, with 0 commands) | When a loop body contains a parallel region, the back edge must point at the task **before** the region (the `loopback` section verifies this shape) |

## 8. Relation to the existing test assets

| Asset | Coverage | Relation to the matrix |
|---|---|---|
| `verify-flow-matrix.py` | Nodes / gateways / variables / loops / back edge / parallelism / ports / pause / experiments / concurrency | The matrix itself |
| `verify-parallel-flow.py` | 4 in-workflow parallel assertions | Complements the parallel section |
| `verify-parallel-experiments.py` | 3 same-island concurrency assertions | Experiment-level concurrency |
| `verify-exp-control.py` | 22 experiment-level pause / resume / stop assertions | Experiment-level pause |
| `verify-device-chain.sh` | 41 device-chain assertions | Driver / protocol layer |
| `e2e-smoke.py` | 24 frontend main-path assertions | End to end |
| `e2e-sim-flow.py` | 24 simulator-integration assertions | Build workflow on the page → bind a real device → build experiment → run → real response |
| `e2e-flow-matrix.py` | 30 page-side matrix assertions | Designer / logic nodes and sequence-flow conditions / run detail / page pause-resume |

## 9. Corrections

| Earlier conclusion | Current state | Evidence |
|---|---|---|
| Arithmetic in conditions is unsupported (`${a} + 1 > 3`) | **Supported** (`+` / `-`, left-associative) | `arith()` in `ConditionEvaluator`; the `builtin` section exercises `${counter} + 10 == 10` |
| On loop re-entry `run_node` keeps only one row | **Resolved**: rows are split by `loop_index` | The `loop` section observes `T:loop=0..3` |

## 10. Page-side matrix (`e2e-flow-matrix.py`)

The backend matrix (§3) locks the capabilities down from the **API / XML** side, but it
bypasses the UI: a palette that cannot create a node kind, a save that never reaches the
database, a run detail that draws no graph, a pause button that does nothing — none of
these is visible to it. `scripts/e2e-flow-matrix.py` adds the **page-side** slice.

| Section | Assertions | Coverage | Key assertions |
|---|---|---|---|
| Login | 1 | Precondition | Login lands on the console |
| A Designer assembly | 6 | Start / task / exclusive gateway / end | Palette placement takes effect; save shows a toast; the preflight verdict renders; the stored XML holds all four node kinds; they survive a reload |
| B Variable node | 4 | `__setVar` | Selecting a task opens “Node settings”; switching to “Variable node”; filling `var`/`value`; the API reads back `method=__setVar`, `params.var=go` |
| C Run detail | 5 | Graph / legend / node table / round | The run detail opens; the graph card is there; the legend renders; the node table renders; a looping run shows the round column |
| D Page pause / resume | 5 | Run control | A “Pause” button appears; clicking it sets the database to `paused` and the page shows paused; a “Resume” button appears; clicking it leaves `paused` |
| E Logic node / sequence-flow condition / workflow options | 9 | `__setVar` tab / `conditionExpression` / `maxIterations` | The “Logic nodes” tab is always visible and within the first screen; a dropped logic node is persisted; selecting a flow and writing a condition is persisted; the workflow-level `maxIterations` is persisted |

```bash
python3 scripts/e2e-flow-matrix.py --base http://127.0.0.1:8080
# Expected: 30 passed / 0 failed
```

**Boundary (Class C)**: the designer palette has **no parallel-gateway entry** (only
`create.exclusive-gateway`; the script prints this as an observation and never fails on
it). Parallel structures can only be built from XML / the API side — the page only
“looks at” them. This matches the parallel boundary in §7.

## 11. Simulator integration (`e2e-sim-flow.py`)

The page-side matrix in §10 uses **placeholder devices**, so it only reaches "the page can
build it and click it"; `verify-device-chain.sh` starts from the driver side and bypasses the
page. Between the two there is an untested gap: **does the real-device segment actually run end
to end from the page**. `scripts/e2e-sim-flow.py` adds that slice — it only **reuses**
already-running `DeviceSimulator` instances (ports 19500..19506) and never starts them; with
none listening the whole section is skipped and points at `labos-kernel/scripts/sim-up.sh`.

| Section | Assertions | Coverage | Key assertions |
|---|---|---|---|
| 0 Simulator present | 2 | Precondition | 19500..19506 are listening; demo-island devices with `device_state.online=true` > 0 |
| 0b Pre-reset | 1 | Test hygiene | Release a leftover island-level runtime hold (`runtime_hold`) on the demo island |
| A Build workflow → bind real device | 8 | Designer + node settings | Create a workflow from the UI and enter the designer; palette drop lands; connect nodes; save persists 3 `sequenceFlow`; bind `ID1731`/`Home`; after re-saving the compile plan carries the device method; `workflow_node.params.protocol_name='Home'` |
| B Build experiment | 4 | Experiment orchestration | Pick island `LAB-DEMO-02`; attach the workflow; create the experiment; orchestrate 1 workflow |
| C Run → assert | 6 | End-to-end real interaction | One `run` row appears; `run.status=completed`; `experiment.status=finished`; `run_command` carries the device-raw response; the persisted command method = `Home`; the run shows up in the page's run history |

```bash
python3 scripts/e2e-sim-flow.py --base http://127.0.0.1:8080
# Expected: 24 passed / 0 failed
```

**Test trap (leftover runtime hold)**: as soon as one workflow in a previous round ends up
"handover to human / skipped", the executor treats "a site pause is a safety event" and holds
the **whole island** (`raise ISLAND` in `ExperimentExecutor`); that `runtime_hold` never clears
by itself. Every later experiment on the same island then lands straight in `paused` at the
action boundary and **no workflow-level `run` is created at all** — it looks like the execution
path is broken, when in fact it is leftover site state from the previous round. The script
therefore **resets before it runs and resets once more at the end** (`reset_island_holds()`), so
no residue is left for the next round. This is test residue, not the behaviour under test.

**Product-side consistency fix (surfaced by this matrix)**: `WorkflowService.saveGraph` writes
`workflow_node.params.protocol_name` for **new** nodes (INSERT branch) but originally wrote only
`method_params` for **existing** nodes (UPDATE branch). In the page flow a node is saved once,
then the device is bound and it is saved again → the UPDATE branch runs → `protocol_name` never
lands in the DB (present in the graph XML, missing in the table). The UPDATE branch now writes
`protocol_name` too, and section A pins this with an assertion.

## 12. Open items

None open: the Class A item (a back edge spanning a parallel region) is the `loopback`
section; the backend matrix is wired into `ci-gate.sh` (`MIN_MATRIX=45`), the page-side matrix
too (`MIN_E2EFM=30`), and so is the simulator integration (`MIN_E2ESIM=24`).
