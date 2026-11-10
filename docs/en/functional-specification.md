# LabOS 2.0 · Functional Specification

> **English edition.** The Chinese original is [`LabOS功能规格说明书.md`](../../LabOS功能规格说明书.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | 2.0-draft |
| Date | 2026-10-29 |
| Status | Draft. The conditions for dropping "draft" are in chapter 1 |
| Implementation baseline | `kernel-0.3` (2026-10-29, the kernel and the migrations that already run) |
| Purpose | For external review. This document is self-contained and does not rely on conversational context |
| Supersedes | `archive/LabOS2.0_功能规格说明书_v0.3.md`; the feature list and the phasing of `目标功能文档_三方合并版.md`; the weekly plan of `前后端实施方案_编译甘特就绪调度.md`; the roadmap in chapters 5–7 of `LabOS系统_现状梳理与新一代架构方案_20261029.md` |

**Status markers**: ✅ implemented in the baseline and measured　🟡 partly implemented　⬜ not started

---

## 0. Reading guide

- Chapter 1 is the **delivery boundary of 2.0**
- Chapter 4 is **what this version includes, and what it explicitly does not**
- Chapter 5 is the **technical architecture**: the layers, the kernel package structure, the two execution paths, the data model, the deployment and the ports
- Chapters 6–7 are the **operating modes and the control semantics** — this is the body of this version relative to v0.3
- Chapter 8 is the **feature ledger**. P0 equals the 2.0 release gate; P1 are the items that follow immediately and do not block the release
- Chapter 12 is the **default decisions already written into the specification**. Later changes are recorded in `决策记录.md`, append-only
- Chapter 13 is the **run state and lifecycle**: the five-level status vocabulary, the pause kinds, the holds, the crash recovery, the batching and the resuming
- Chapter 14 is **concurrency, resources and scheduling**: the four-level resource model, the fixed acquisition order, the wait-for-graph deadlock, the scheduler, the duration model and the rolling schedule

---

## 1. What 2.0 delivers

The product name is LabOS 2.0. The current code and database are the implementation baseline `kernel-0.3`, not yet a 2.0 release artefact.

### 1.1 Release gate

When all six of the following hold at the same time, this file changes from `2.0-draft` to `2.0`:

1. **The workflows execute by the graph.** A workflow containing `parallelGateway` waits at the join until all branches are finished; a workflow containing `exclusiveGateway` takes only one outgoing edge. Regression uses the workflows in the library that contain either gateway.
2. **Attended / unattended can be switched while running.** Unattended follows only the recovery policy; attended raises a dialog whose defaults come from the policy.
3. **The operations loop closes.** Preflight before saving, policies that can be changed and can be replayed against historical errors, alerts that can be claimed and closed, and one status view that answers "why is it stopped".
4. **Time is predictable.** Command-level durations come from historical samples, a total-duration forecast can be given, and the longest interval between two steps can be checked.
5. **One real driver endpoint has been exercised in shadow mode.** The definition of shadow mode is in section 6.3. Commissioning every device at the same time is not required.
6. **A run is not aborted when a device goes offline.** If there is a substitute in the same capability pool, reassign; if not, escalate to a human and record the reason.

### 1.2 What already holds in the baseline

These capabilities stay in appendix B and are struck off the P0 list: data migration, pure-function recovery decisions, event-based execution, alert aggregation, capability-template command expansion, the bpmn-js workflow designer, virtual drivers and the local Sidecar mock, and the structural advancement of all workflows.

### 1.3 What this version explicitly does not do

Orchestration between experiment blocks other than "sequence", global makespan optimisation, nest-level plate-position scheduling, a React rewrite, a Spring Boot wrapper, the teaching pendant, the permission matrix, treating any one device-interoperability standard as this period's interface, and regulatory certification. The reasons are in section 4.3.

---

## 2. Background and goals

### 2.1 Current state

LabOS is the scheduling software of a laboratory automation line. The current baseline can load workflow definitions, advance them structurally on the simulation bus, and pick a device by capability. A capability with a template expands into several steps; otherwise it degrades to a single step.

### 2.2 Problems

The core problem is that **the kernel cannot be verified**.

| Problem | Evidence |
|---|---|
| Scheduling cannot be simulated | There is no simulation run. A workflow change can only be tried on the machine |
| Errors cannot be decided on | The error codes are too broad — one code covers many causes, so the handling policy can only be written as "retry" or "ignore" |
| Execution cannot be replayed | Command records generally lack the device's raw response, so the scene cannot be reconstructed when something goes wrong |
| The code is not maintainable | Scheduling / execution / communication / retry are concentrated in one giant class |
| The data is not trustworthy | `run_node` time fields contain dirty data (negative durations or bulk-written to the same instant) |
| The diagram and the execution are disconnected | The diagrams contain parallel gateways and exclusive gateways, but the executor only advances along `sequenceFlow`. The new kernel `kernel-0.3` likewise does not execute the gateway semantics — see section 7.6 |

### 2.3 Goals

1. **The kernel is verifiable**: recovery decisions are pure functions, execution is an event stream, drivers are a replaceable contract, and simulation and real execution share one executor.
2. **The assets carry over**: the workflows can be loaded on the new kernel. 2.0 further requires that the gateway semantics match the graph. What `kernel-0.3` has already proven is the structural advancement — see appendix B.
3. **Work continues when nobody is there**: attended and unattended are two operating modes. A device failure is handled first by reassigning or retrying; only when the policy is exhausted is a human called.
4. **Onboarding a new device does not change the control console's business code**: drivers declare capabilities. 2.0 accepts this path through the shadow mode of one real endpoint.

---

## 3. Priority

This version does not put global schedule optimisation into the release gate. Executing parallel gateways and exclusive gateways by the graph does enter the release gate. Duration forecasts use only cleaned completed samples.

---

## 4. Scope

This version chooses its scope from the needs of laboratory scheduling. It does not compare with, or excerpt, another product's manual, brochure or demo material.

### 4.1 Mechanisms included in this version

| Mechanism | LabOS 2.0 |
|---|---|
| Automatic compilation | The canvas keeps one business node. The expansion result is a preview projection. At execution time it is expanded according to `capability_recipe`. The steps a user changes are recorded as overrides on that node and are not written back as extra BPMN nodes |
| Simulation | The same executor; simulation only swaps the clock and the driver. The total-duration forecast uses the duration model. An accelerated run does not rewrite the predicted Gantt |
| Handling while a person is present | The attended window. The default item comes from `recovery_policy`. After a pause, execution can continue from the failed command |
| Diagnosing "why is it not moving" | The status vocabulary is in section 7.7. 2.0 uses the running states |
| Blocking it before save | Preflight before saving. Double-click to locate is P1 |
| A device going offline | Reassign by capability; the run is not aborted. If there is no substitute, escalate to a human |
| Design-time checks | Preflight before saving (does the capability have a device, is the gateway missing a join or a condition) |

Two further product rules:

- **Containers are inventory, not hidden scheduler state.** A container can be identified across workflows by barcode or by attribute. The live position is a later capability.
- **Simulation is another clock on the same scheduling and execution path**, plus a set of virtual drivers.

### 4.2 What was trimmed

| Topic | Trim |
|---|---|
| Global optimisation, static scheduling, multi-path scheduling | Stays outside 2.0 |
| Parallel and choose-one | Semantics already drawn on the graph but not yet executed by the graph enter the release gate |
| Conditions, loops, timers, semaphores between experiment blocks | 2.0 guarantees sequence only |
| The shortest / longest interval between two steps | It enters the release gate and is checked with the duration model |
| Three-state readiness on a nest | 2.0 first does device busy/idle and offline reassignment |
| Arbitrary scripts overriding the readiness decision | This version forbids executing arbitrary code in the readiness or in the conditions |

### 4.3 Not included in this version

Not entering the P0/P1 of the feature ledger:

- Orchestration between experiment blocks other than sequence, global schedule optimisation, and nest-level scheduling.
- Arbitrary scripts in readiness or conditions, and the teaching pendant.
- Treating any one device-interoperability standard, or a regulatory certification, as a required delivery of this period.
- Turning a workflow into an object model of "one column per plate type". A plate is a container; a workflow is still a BPMN workflow.

---

## 5. Technical architecture

This chapter is in two parts: **5.1–5.7 is the current state** (the actual shape of the implementation baseline, with the code as the authority), and **5.8 is the 2.0 target architecture** (the shape once the release gate is met). Where the current state and the target disagree, the release gate of section 1.1 is the basis for trimming.

### 5.1 Layered architecture (current state)

```
┌──────────────────────────────────────────────────────────────┐
│ Browser  React 18 + TypeScript + Ant Design + React Flow       │
│ webapp/                                                       │
└───────────────────────────┬──────────────────────────────────┘
                            │ HTTP/JSON  ApiServer :18080
                            │ (dev frontend Vite :5173; deployed Nginx :8080 reverse-proxies /api)
┌───────────────────────────▼──────────────────────────────────┐
│ Kernel labos-kernel/ (Java 17, zero web framework dependency)  │
│  api/      routing, parameter parsing, JSON assembly, auth     │
│  workflow/ workflow parsing · graph execution · experiment orchestration · scheduling · status vocabulary │
│  engine/   node-level state machine (retry / hand over to a human / idempotency) │
│  driver/   device-driver abstraction + implementations + position lock + shadow probe │
│  recovery/ crash-recovery decisions (pure functions)           │
│  alert/    alert aggregation and handling                      │
└───────────────────────────┬──────────────────────────────────┘
                            │ ① JDBC
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
┌────────────────────┐              ┌────────────────────────────────┐
│ PostgreSQL :5432   │              │ TCP + JSON, frames delimited by #!LABSEP!# │
│ database labdb     │              └───────────────┬────────────────┘
└────────────────────┘                              ▼
                                        ┌────────────────────────────────┐
                                        │ Vendor driver service .NET Sidecar :18501 │
                                        └───────────────┬────────────────┘
                                                        │ vendor DLL
                                                        ▼
                                        ┌────────────────────────────────┐
                                        │ Devices / robot arms           │
                                        └────────────────────────────────┘
```

**The gateway is an optional path** (`labos-gateway/`): console → Redis :6379 → gateway → devices. It has been exercised but **the default is still the direct connection**, not switched over.

### 5.2 Kernel package structure (who is responsible for what)

| Package | Key classes | Responsibility |
|---|---|---|
| `api/` | `ApiServer`, `QueryService`, `AuthService` | HTTP routing and queries. `ApiServer` is a giant class (the routes are strung together with `if (path.equals(...))`) and is a known refactoring point |
| `workflow/` | `WorkflowService`, `WorkflowGraphParser`, `GraphRunner`, `ExperimentExecutor`, `ExperimentScheduler`, `ExperimentFlowRunner`, `RunControl`, `RuntimeHold`, `StatusVocabulary`, `BatchOrchestrator`, `BatchPlanner`, `SchedulerEta`, `PreflightChecker` | Workflow parsing, execution by the graph, experiment orchestration and scheduling, pause/hold, status vocabulary, batching, duration forecast, preflight before saving |
| `engine/` | `RunStateMachine`, `NodeProgress`, `RunProgress` | The node-level state machine: pending→ready→running→completed/failed, with retry and hand-over-to-human. A failed retry uses the same `commandId` (the idempotency key), to avoid the robot arm moving twice |
| `driver/` | `DeviceDriver`, `DriverRegistry`, `DriverStepExecutor`, `tcp/TcpDriverAdapter`, `tcp/TcpPersistentDriver`, `PositionLock`, `ShadowProbe`, `ShadowRunner` | Find a device by capability, execute one step (timeout 30s), the two transports short-lived/long-lived, the position lock, the shadow probe |
| `recovery/` | `RecoveryDecider`, `RecoveryPolicyResolver`, `RecoveryPolicy`, `StartupRecovery` | After a crash whether a node should retry / skip / hand over to a human (a pure function); settle orphaned runs at start-up |
| `alert/` | — | Missing plate, timeout, failure, contract mismatch; de-duplication by `fingerprint = errorCode\|resourceId` |

**Dependency direction** (reverse dependencies are forbidden):

```
api → workflow → engine → driver
        ↓           ↓         ↓
     recovery     alert   persistence
```

`driver` does not depend on `workflow`. `PositionLock` currently sits in `driver/` but is very much business logic — a known structural flaw (it should belong to `workflow/` or become its own `plate/` package).

### 5.3 The two execution paths (the review focus)

There are two execution paths in the kernel that both write `run` / `run_node` / `run_command`, and device allocation and variable substitution are two separate pieces of code:

| Path | Entry point | Route |
|---|---|---|
| **A: execution by the graph** | `WorkflowService.execute(workflowId)` | `GraphRunner.run()` (or `RunStateMachine.executeAll()`) → `DriverStepExecutor` → device |
| **B: step-by-step canvas execution** | `ExperimentFlowRunner.run(steps)` | step by step `ShadowProbe.probe()` → device |

The two paths share the same capability-resolution rule set (six levels), but the executor branch still has to check the logic nodes on its own: when `resourceId == 0` or `capability == LOGIC` the kernel completes them directly, without going into the driver registry.

### 5.4 Data model (core tables)

```
resource_node          resource tree: island → device → tier (kind: island/device/incubator/storage)
  ├ parent_id          self-reference
  └ attr jsonb         contains pos_x/pos_y (canvas coordinates)

device_instance        device instance (one real device)
  ├ resource_id        → resource_node.id   ★ the mapping point of the two ID sets
  └ driver_id          → driver endpoint

device_state           device-level state (online + status; has_plate ← derived from position_state)
position_state         tier-level state (plate_id, reserved_until) ★ the single source of truth for position
device_port            channel (the device's inlet/outlet/combined; capacity > 1 is a counting semaphore)
device_access          door (hatch/lid/drawer; exclusive while a open/close is in progress)

workflow / workflow_node / workflow_edge   BPMN graph
run / run_node / run_command               execution records (command level carries device_id)
run_event                                  event stream

experiment             experiment (island_id / priority / deadline)
experiment_process     the steps of an experiment (workflow_id / seq / loop_count)
experiment_variable    experiment variables (definition layer)
experiment_run / experiment_run_step       experiment execution records
run_variable           run-time variable snapshot (isolated by run_id)

island_flow            island flow lines (the device connections on the canvas)
experiment_graph       experiment canvas (devices + logic nodes, jsonb)
robot_point            robot-arm point (code/x/y/z/device_resource_id + door/channel/tier attributes)
```

### 5.5 Key data flows

**Workflow execution**

```
POST /api/workflows/{id}/run
  ↓
WorkflowService.execute()
  ├ parse BPMN → ordered nodes
  ├ build the candidate pool capPool (capability → list of device-instance ids; preferEmptyDevices puts empty devices first)
  ├ GraphRunner.run()
  │    └ toRecoverable: pick a device by capability in turn (usageCursor)
  └ DriverStepExecutor.execute() for each step
       ├ DriverCommand (commandId = idempotency key, timeoutMs = 30s)
       ├ TcpDriverAdapter.execute() → TCP → device
       └ PersistingStepSink.writeStep()
            ├ INSERT run_command (including device_id)
            └ PositionLock.notePlateMove() (Deposit put / Withdraw take)
```

**Experiment execution**

```
POST /api/experiments/{id}/run?async=1
  ↓
ExperimentScheduler.submit()      creates experiment_run (queued)
  ↓ background 2-second polling
  ├ take the island lock (putIfAbsent)
  └ ExperimentExecutor.run(expId, vars, existingRunId)
       ├ loadVariables (defaults ← overrides)
       ├ expand (expanded by loop_count, upper bound 20/500)
       ├ substitute ${var} (once, before the start)
       └ call WorkflowService.execute in sequence (stop on failure)
```

**Step-by-step canvas execution**

```
POST /api/experiments/flow-run?steps=id:method:output:params&vars=
  ↓
ExperimentFlowRunner.run()
  each step:
    ├ instanceIdOf(resourceId)          ★ ID conversion
    ├ enrichWithCoords (fill in the point coordinates when withCoords=1)
    ├ precheckTransfer (check whether the source device holds a plate before a Transfer)
    ├ ShadowProbe.probe()               → TCP → device
    ├ write run_node / run_command
    └ parse the output variables (take the fields from the driver raw response)
```

### 5.6 Frontend structure

```
webapp/src/
├── api/client.ts       apiFetch (unified 401 handling)
├── pages/
│   ├── Island.tsx            Island view: drag to place devices, transfer sequence, tier visualisation
│   ├── IslandFlow.tsx        Island orchestration: device connections = plate transfers
│   ├── ExperimentCanvas.tsx  Experiment canvas: cross-workflow orchestration (start / end / workflow, React Flow)
│   ├── Experiments.tsx       Experiment orchestration: variable table, loop column, execution history
│   ├── Workflows.tsx         Workflow library
│   ├── Designer.tsx          BPMN designer (bpmn-js)
│   └── …                     monitoring, alerts, logs
└── App.tsx            routing + navigation
```

**The difference between the two canvases** (easy to confuse):

| | `ExperimentCanvas` (`/canvas`, `/canvas/:id`, `/experiments/:id/canvas`) | `IslandFlow` (`/island-flow`) |
|---|---|---|
| Nodes | start / end / workflow (cross-workflow orchestration) | devices only |
| Arrows | workflow order | the plate moves there |
| Purpose | in what workflow order the experiment runs | how the logistics on the island go |

### 5.7 Deployment shape and ports

**Now (single machine)**: one machine runs the kernel + frontend + PostgreSQL, devices are connected directly (short-lived connections).

**Target (multiple instances + gateway)**: console × N ─ Redis (state centre) ─ gateway × M ─ devices. The gateway is done but not switched over (the switch `LABOS_DRIVER_MODE`: `persistent` is implemented, `redis` is still to do, empty means the short-lived connection).

**Ports and addresses**:

| Component | Address / port | Explanation |
|---|---|---|
| Kernel ApiServer | `:18080` | the external HTTP/JSON interface |
| Frontend (dev) | `:5173` | Vite dev server |
| Frontend (deployed) | `:8080` | Nginx reverse-proxies `/api` to 18080 |
| .NET Sidecar | `:18501` | the vendor driver service |
| Device simulator | `:19500–19506` | 7 DeviceSimulators (`labos-kernel/scripts/sim-up.sh`) |
| PostgreSQL | `:5432`, database `labdb`, user `labos` | the connection is given by `LABOS_DB_HOST/PORT/NAME/USER/PASS`. **It falls back to `127.0.0.1` by default; a test environment must give the real database explicitly** |
| Redis | `:6379` | used by the gateway path only (`LABOS_REDIS_*`) |

### 5.8 The 2.0 target architecture (once the release gate is met)

```
┌──────────────────────────────────────────────────────┐
│  Interaction                                             │
│  Workflow designer (BPMN, node level)                    │
│  Execution monitoring (event stream, status, total-duration forecast) │
│  Handling (policy, alerts, attended window)              │
│  2.0 keeps the existing web console; no new frontend framework │
└────────────────────┬─────────────────────────────────┘
                     │ REST + SSE
┌────────────────────┴─────────────────────────────────┐
│  Kernel (Java 17, zero web framework dependency)         │
│  compiler    node → command sequence (template + node-level overrides) │
│  engine      state machine. Executes Decision / Parallel by the graph │
│  recovery    pure-function decisions. The mode decides whether the result goes to the policy or to the window │
│  simulator   the same executor, with a swapped clock and virtual drivers │
│  persistence event stream │
└────────────────────┬─────────────────────────────────┘
                     │ gRPC
┌────────────────────┴─────────────────────────────────┐
│  Driver layer                                            │
│  .NET Sidecar (the existing endpoints)                   │
│  Contract: capability declaration / error codes / device raw response / idempotency │
└──────────────────────────────────────────────────────┘
```

The experiment-block layer keeps only "several workflows executed in sequence" in 2.0. A block-level canvas is not in the release gate.

The kernel now runs on Spring Boot 3.2.5 (on 2026-11-10 this replaced the original "the kernel stays free of a Spring dependency" wording — see D6 in chapter 12 and `决策记录.md`). The HTTP layer is Spring MVC, persistence is MyBatis XML, and the pool and transactions are managed by Spring; the device gateway is a separate process that stays on bare JDK and pulls in no framework.

---

## 6. Operating modes

The mode is an attribute of the execution instance, and it can be switched between "attended" and "unattended" while it runs. Simulation can be combined with either attendance mode: the attendance decides who is in charge when something goes wrong, the simulation decides the clock and the driver.

| Mode | When something goes wrong | Clock | Devices |
|---|---|---|---|
| Attended | A dialog pops up. The default option equals the action computed by the current policy. The person can choose retry, skip, stop, or pause, modify and continue from the failed command | Real | Real |
| Unattended | Only the policy is executed. When the result is `MANUAL` an alert is recorded and no blocking dialog is raised | Real | Real |
| Simulation | The decision rules are the same as above. An error can be injected into a single command | Can be accelerated. The forecast duration reported externally is still the un-accelerated model time | Virtual drivers. A single device may be switched back to its real driver for selective simulation; that item is P1 |

The mapping from the options of the window to the actions of the kernel:

| What the person sees | Kernel action |
|---|---|
| Retry | `RETRY` |
| Switch to another device | `REASSIGN` |
| Skip | `SKIP` |
| Stop | `ABORT` |
| Pause and modify, then continue from the failed command | Attended only. The kernel records one manual-intervention event |
| Hand over to a person | `MANUAL` |

What `recovery_policy` holds is the intent. The decision function turns the intent into the actions above according to the attempt count and whether a substitute device is still available. When no policy matches, the attempt count is exhausted, or no substitute device exists, the result is `MANUAL` and the reason is written into the event. `COMPENSATE` in the policy enumeration is not yet executed in the baseline; the decision degrades to `MANUAL`.

### 6.1 The alerts under unattended mode

When unattended escalates to `MANUAL`, one alert is produced, carrying the error code, the device and the policy computation. An alert can be claimed and closed. Closing does not change the run that is already stopped; to continue, a person must choose a window action under attended mode, or switch back to unattended with a new policy result.

### 6.2 The simulation clock

The duration is taken from the model of L5 in chapter 8. The acceleration only compresses the waiting; it does not change the "model time" recorded in the events. A command that references a barcode, reads the data table, or would rewrite the condition of a later Decision cannot be simulated with a pure delay: either it is really executed, or the test case explicitly supplies the return value. This rule is written into the preflight: if a workflow contains such a command, a hint is raised before the simulation starts.

### 6.3 Shadow mode

Shadow mode is used for the commissioning of the real drivers; it does not replace the simulation.

Definition: the new kernel computes the commands that are about to be issued according to the workflow and records them into the event; the calls that go to the on-site endpoints travel over a channel that only observes and does not drive real actions. The acceptance looks at three things: the contract fields are complete (capability, error code, device raw response, idempotency key), the command sequence is consistent with the compilation result, and the kernel has not written any action to a production device.

2.0 only requires one driver endpoint to be exercised first.

---

## 7. Object model and control semantics

### 7.1 The three object layers

| LabOS | What the person edits |
|---|---|
| Experiment | The order of several workflows |
| Node | One business step (read plate, incubate, transfer) |
| Command | The device call after the node is expanded |
| Container | A physical item such as a plate, with a barcode and a plate type |

The editing altitude of 2.0: the designer maintains the nodes and the connections. The command sequence is a compilation projection, generated at run time. The container identity is in section 7.5 and does not occupy a column of the workflow.

### 7.2 Layer A: between experiment blocks

2.0 only delivers the sequence: the next workflow starts after the previous one has ended. Parallel, conditional, loop, dependency, timer, semaphore and sub-experiment keep their names but do not enter the release gate.

### 7.3 Layer B: inside the workflow

The storage continues to use BPMN XML, so that the workflow definitions can be opened. The execution semantics use the table below, not "every `sequenceFlow` is walked once".

| Semantics | Existing BPMN | 2.0 |
|---|---|---|
| Sequence | `sequenceFlow` | Delivered |
| Parallel, fork and join | `parallelGateway` | Delivered |
| Decision, one branch only | `exclusiveGateway` | Delivered |
| Time Constraint, the shortest or longest interval between two steps | No equivalent element, a new annotation is needed | Delivered. Used for incubation and plate-reading windows |
| Sub-process | `subProcess` | P1, executed as a subgraph |
| Loop, Wait Until | `loopCharacteristics` etc. | Outside the release, the model keeps the field |
| Exclusively holding a device until it is released | None | Outside the release |
| Executing a set of containers one by one and assigning variables | None | Together with the containers and the variables, outside the release |
| Inclusive gateway, event gateway, boundary events | Corresponding elements | Outside the release |

The acceptance criterion of the parallel: both branches are executed; the join does not continue while any branch is unfinished. 2.0 does not require the two branches to overlap on the wall clock. The wall-clock overlap is device scheduling and does not block the gateway semantics.

The acceptance criterion of the exclusive: only one outgoing edge is executed. An outgoing edge in a graph in the database may have no `conditionExpression`, so the rule is split into two:

- When an outgoing edge carries a condition expression, the one that matches after the evaluation is taken. The evaluation only allows the whitelist limited in chapter 12; arbitrary code is not allowed.
- When an outgoing edge has no condition, the preflight before saving flags it as "missing condition". The execution takes one default edge (the BPMN default outgoing edge; when there is no default, the first one after a stable sort), and the event records "no condition, the default edge was taken". Executing both outgoing edges counts as a failure.

The acceptance criterion of the time constraint: the constraint is written on a pair of commands (or a pair of nodes) and contains the shortest interval, the longest interval, or both. Both the forecast and the real execution check it. When the longest interval is exceeded, the recovery policy handles it (the default escalates to `MANUAL`; the policy can change it to `ABORT`). For a command without a duration model, the preflight refuses to attach a time constraint.

### 7.4 Layer C: inside a node

A node is expanded into a command sequence according to its capability template. A capability without a template degrades to one step, and the method name uses the protocol on the node.

| Property | Baseline |
|---|---|
| Execute the expanded commands in sequence | ✅ |
| If any step fails, the node fails | ✅ |
| The failure is located at step N | ✅ |
| When switching devices, re-run from the first step of that node | ✅ implemented this way. The physical state of the new device is unknown, so the execution does not resume from a middle step |
| Retry from the failed step on the same device | 🟡 a P1 after the release |
| The expansion result is shown in the designer as a projection | 🟡 the execution path exists; the preview and the "an override is not written back to the BPMN" are completed per section 4.1 |
| Branching again inside a step | Not done |

The expansion happens at run time. `run_node` is still one business node and `run_command` is the command under it. The historical `run_node` and the new multi-step execution are aligned by this relation.

### 7.5 Containers

The container answers "which plate", the position answers "where the plate is". 2.0 separates the two.

2.0 requires that a container can at least be referenced across runs by barcode and by plate type. The data lands on the existing `plate` / `plate_well` capability, and the product semantics add "a run can point at these containers". The gripper offsets, the replenishment during a run and the live nest position are not in the release gate.

The three-state readiness that depends on a nest position goes after 2.0. Until then the device side only uses busy, idle, offline and error.

### 7.6 The gap between the current baseline and the semantics above

The `WorkflowService.execute` of `kernel-0.3` only takes out the nodes with `type = task` and hands them to `RealWorkflowRunner.topoSort`. An edge whose other end is not a task is dropped. The gateways are marked as `gateway` or `gateway_parallel` at parse time and do not take part in the execution.

Therefore:

- The "all the device-action nodes advance" of the bulk acceptance proves that the structure can be walked to the end; it does not prove the branch selection and the joining.
- The "conditional branches are already supported by the backend" written in v0.3 is void.
- The first implementation task of 2.0 is to make the executor recognise the Decision, the Parallel and the default edge, and not to build the experiment-block canvas first.

### 7.7 Status vocabulary

The Process Status and the device map share one vocabulary; each status carries a reason and an entry time.

| Status | Meaning |
|---|---|
| Running | A command is executing |
| WaitingForDevice | The target device is busy or offline, and the reassignment has not been completed yet |
| WaitingForOperator | The decision result is `MANUAL`, or the attended window has not been closed |
| WaitingForJoin | The join of a Parallel is still waiting for the branches |
| Recovering | A retry or a reassignment is in progress |
| Blocked | The preflight or a time constraint decided that it cannot continue; the reason is in the event |
| Completed / Aborted | Terminal states |

2.0 does not use nest-position readiness states. They are added once the nest position exists.

> This section is the **simplified** vocabulary of the running states, used to answer "why is it not moving". The complete five-level status vocabulary
> (run / node / step / experiment / batch), together with the full basis for the pause kinds, the on-site holds, the crash recovery, the batching and the resuming,
> is in **chapter 13**; the resource locks and the deadlock detection under concurrency are in **chapter 14**.

---

## 8. Feature ledger

The P0 is folded into the release gate of section 1.1. The P1 is done and does not block the 2.0 version bump. The items marked "outside the release" only keep the interface or the data and are not scheduled into the 2.0 implementation.

> **The basis of the status column**: ✅ implemented and measured　🟡 partly implemented　⬜ not started. This table is updated as the implementation progresses and reflects the **current** delivery status;
> the sole authority for whether an item "has a test case and is in CI" is [`test-case-master-table.md`](test-case-master-table.md) (1:1 with the ledger items of this table).
> Where the two conflict, the test ledger wins and this table is corrected. Chapter 11 records which period each item landed in.

### L1 Orchestration

| # | Feature | Status | Priority |
|---|---|---|---|
| 1.1 | Workflow designer (bpmn-js): an island-device panel "island → device → method" drags a method into a `bpmn:Task` with the device image and persists it automatically; changing device inserts a robot-arm `Transfer` node and connects it; devices and nodes show drawings; an always-visible delete button persists immediately; dragging connections, opening stored BPMN and the node settings panel are still supported | ✅ bpmn-js | `scripts/e2e-method-drop.py` (20 assertions) |
| 1.2 | A multi-step node can be collapsed for viewing in the interface | ✅ | — |
| 1.3 | Automatic compilation: expansion at run time; the preview is a projection; an override is not written back to the BPMN | 🟡 the expansion exists | P1. The run-time expansion is already counted in gate 1; the projection and the overrides do not block the release on their own |
| 1.4 | Decision (exclusive, including the default edge when there is no condition) | ✅ execution by the graph (§11 B.1, `GraphRunner`) | P0 |
| 1.5 | Parallel (fork and join) | ✅ execution by the graph (§11 B.1, the join waits for all incoming edges) | P0 |
| 1.6 | Time Constraint (the shortest / longest interval) | ✅ `engine.TimeConstraintExecutor` + `engine.TimeConstraintSelfTest` (25 cases, pure logic) | P0 |
| 1.7 | Experiment blocks executed in sequence | ✅ | P1 |
| 1.8 | Sub-processes executed as a subgraph | ✅ | P1 |
| 1.9 | Loop, Wait Until, variables (inside a workflow); the conditions / loops / timers / semaphores between experiment blocks | 🟡 inside a workflow it is implemented and has cases; between experiment blocks it is still outside the release | Outside the release |
| 1.10 | Plate types and gripper offsets | ⬜ | Outside the release |

### L2 Validation

| # | Feature | Status | Priority |
|---|---|---|---|
| 2.1 | Preflight before saving: does the capability have a device, does the exclusive lack a condition, does the parallel lack a join, does the time constraint lack a duration | ✅ `PreflightChecker` + a dry run with an empty executor (§11 B.2) | P0 |
| 2.2 | Cycles, isolated nodes, a missing start / end | 🟡 the backend rejects them, the interface preflight already labels them | P1 |
| 2.3 | An error panel for the compilation result, double-click to locate | ⬜ | P1 |
| 2.4 | Referring to a device that has been taken offline | ⬜ | P1 |
| 2.5 | A hint before the simulation that "this command cannot be simulated with a pure delay" | ⬜ | P1 |
| 2.6 | Bulk capacity (whether there are enough plate positions) | ⬜ | Outside the release |

### L3 Execution

| # | Feature | Status | Priority |
|---|---|---|---|
| 3.1 | State-machine execution + event stream | ✅ | — |
| 3.2 | The runtime allocation from capability to device | ✅ | — |
| 3.3 | The multi-step execution and the locating of the failed step | ✅ | — |
| 3.4 | Switching between attended / unattended | ✅ switchable while running (§11 B.3, `POST /api/runs/{id}/attendance`) | P0 |
| 3.5 | Reassign when a device is offline or fails, the run is not aborted; with no substitute it is `MANUAL` | ✅ | P0 |
| 3.6 | Resuming from a breakpoint | ✅ `seq:loop_index` + `skipKeys`, see §13.7 | P1 |
| 3.7 | Command-level single-step forward / backward | ⬜ not implemented | P1 |
| 3.8 | Modify after a pause and continue from the failed command | 🟡 it can be handed over to a person and continue from the failed command, but a command cannot be modified in place | P1 |

### L4 Scheduling

| # | Feature | Status | Priority |
|---|---|---|---|
| 4.1 | When the device is busy, wait or reassign (not depending on which nest holds the plate) | ✅ inside a workflow, two branches contending for the same device are serialised automatically, see §14.1 | P1 |
| 4.2 | The three-state readiness and the pull loop | ✅ the three states of the scheduler are in §14.6 | Outside the release |
| 4.3 | Static scheduling, dependencies other than Run After | ⬜ | Outside the release |
| 4.4 | Global makespan optimisation | ⬜ not implemented (no prototype script or trial report in the repository) | Outside the release |

### L5 Simulation

| # | Feature | Status | Priority |
|---|---|---|---|
| 5.1 | Virtual driver | ✅ | — |
| 5.2 | Command-level duration model | ✅ p50 / p90, see §14.7 | P0 |
| 5.3 | Import the durations from the history (cleaned completed samples only) | ✅ | P0 |
| 5.4 | Total-duration forecast | ✅ | P0 |
| 5.5 | Gantt chart (forecast and actual) | ⬜ the page `/gantt` is manual only | P1 |
| 5.6 | Throttle: accelerate the waiting, the forecast duration does not change | ⬜ | P1 |
| 5.7 | The interface for the simulated errors | ✅ the driver can inject | P1 |
| 5.8 | Switching a single device between simulation and reality | ⬜ | P1 |

The basis of the duration: only the `completed` records whose cleaned duration is positive, giving p50 and p90 per capability and per method. The forecast uses p50 by default. The "can it be finished within the longest interval" of the time constraint uses p90 as a conservative judgement. At acceptance, report a baseline on the cleaned samples first, then freeze the threshold; this draft does not pre-write a percentage that has not been recomputed.

### L6 Errors and recovery

| # | Feature | Status | Priority |
|---|---|---|---|
| 6.1 | A pure function from the error code to the action. Actions: `RETRY` `REASSIGN` `SKIP` `ABORT` `MANUAL` | ✅ | — |
| 6.2 | The graded evidence and the alert aggregation | ✅ history replay reduces the alert count, see `ALERT_REPORT.md` | — |
| 6.3 | The contract layer rejects the catch-all codes | ✅ | — |
| 6.4 | When a policy is replayed, show the distribution of the hits; it is forbidden to make the coverage 100% by using a large number of `SKIP` | ✅ | P0 |
| 6.5 | The attended window | 🟡 Manual pause + paused-queue disposition pages (`PausedRuns` / `RunDetail`) available; the auto dialog whose default comes from `recovery_policy` is not done | P0 |
| 6.6 | The call stack view | ⬜ | Outside the release |
| 6.7 | `COMPENSATE` | ⬜ the policy enumeration has it, the execution degrades to `MANUAL` | Outside the release |

### L7 Monitoring

| # | Feature | Status | Priority |
|---|---|---|---|
| 7.1 | The execution detail (the node tree + the event stream) | ✅ | — |
| 7.2 | Claiming and closing the alerts | ✅ | P0 |
| 7.3 | Process Status (the vocabulary of section 7.7) | ✅ the full basis is in §13 | P0 |
| 7.4 | The device status colouring (busy / idle / offline / error) | ✅ | P1 |
| 7.5 | The plate-level Gantt, the scheduling decision trace | ⬜ | Outside the release |

### L8 Device onboarding

| # | Feature | Status | Priority |
|---|---|---|---|
| 8.1 | The driver contract (capability, error code, raw response, idempotency) | ✅ | — |
| 8.2 | .NET Sidecar local mock | ✅ | — |
| 8.3 | The shadow mode for one real endpoint | ✅ `driver.ShadowProbe` (§11 period 3) | P0 |
| 8.4 | The remaining driver endpoints | ⬜ | Outside the release |
| 8.5 | The teaching pendant | ⬜ | Outside the release |

### L9 Operations

| # | Feature | Status | Priority |
|---|---|---|---|
| 9.1 | Adding, editing and deleting the policies; replay them against the historical errors before saving and show the hits | ✅ | P0 |
| 9.2 | Recovering a run after a crash | 🟡 close-out at start-up (orphan `running`→`failed` + `recoverable`), a 7-day window, no automatic resume, see §13.5 | P1 |
| 9.3 | The role permissions, the audit log | 🟡 the tables exist, the interface/endpoints partly | Outside the release |

### L10 Containers and inventory

| # | Feature | Status | Priority |
|---|---|---|---|
| 10.1 | The container identity: the barcode and the plate type can be referenced by a run | 🟡 `plate` / `plate_well` have been migrated, the product semantics are not connected | P1 |
| 10.2 | The nest-level position, the replenishment during a run, picking the containers by attribute | ⬜ | Outside the release |
| 10.3 | The plate-type propagation and the gripper offsets | ⬜ | Outside the release |

---

## 9. Data


The objects that are directly related to this specification:

| Object | Role | What 2.0 needs to change |
|---|---|---|
| `workflow` / `workflow_node` / `workflow_edge` | The existing BPMN | The executor reads the gateways and the default outgoing edges |
| `capability` / `capability_recipe` | 16 capabilities; only those with a template expand into several steps | The node-level overrides are stored separately, the BPMN is not changed |
| `run` / `run_node` / `run_command` / `run_event` | The execution and the events | The events gain the mode, the default edge and the time-constraint decision |
| `recovery_policy` / `error_code` / `error_occurrence` | The policies and the evidence | The policies can be written; the replay only reads the history |
| `plate` / `plate_well` | The containers | P1 connected to the run |
| The duration statistics | Derived from the cleaned completed samples | The derived result can be rebuilt, it is not written into the specification by hand |

The `protocol` is a device method, not a process template. Chapter 1 of the data dictionary is the authority for this semantics.

---

## 10. Technology choices

2.0 converges the choices on the part that the release needs.

| Layer | The 2.0 choice | Explanation |
|---|---|---|
| Kernel language | Java 17 | Already in use |
| Kernel dependencies | Spring Boot 3.2.5 (Spring MVC / MyBatis XML / Spring transactions) | The domain layer carries no Spring annotations, so the decisions, the execution and the compilation stay unit-testable |
| Demo service | Spring MVC (Tomcat owns port 18080) | The JDK HttpServer is retired; the device gateway is a separate bare-JDK process |
| Database | PostgreSQL 17 | Already in use |
| Driver | gRPC + .NET Sidecar | The contract is fixed, the real endpoint is in 8.3 |
| Real-time push | SSE | P1, the status view can poll first |
| Workflow designer | The existing bpmn-js page | Opens the existing XML |
| The rest of the interface | The existing console | Add the preflight, the policies, the alert handling and the status vocabulary |
| Conditional expressions | Whitelisted comparisons and logic operations | See chapter 12. No SpEL or Groovy is introduced |

React, Ant Design, React Flow and Spring Boot 3 are not in the implementation scope of 2.0. If the experiment-block canvas appears in a later version, the canvas library is chosen then. The workflow designer still uses bpmn-js at that point, and the two are related by the workflow identifier.

---

## 11. Roadmap

The completion criterion of each period is the acceptance of the corresponding row in chapter 8; no separate weekly plan is started.

### Completed: kernel-0.3

### B.1 Execution by the graph (added 2026-10-29, kernel-0.4)

Gate 1 is met: the full workflow set is walked on the simulation bus by the graph. Execution by the graph follows only the activated path; a branch that was not selected is not executed.

All three semantics hold:

| Semantics | How it was verified |
|---|---|
| A parallel fork executes both branches | `GraphRunnerTest` case 2 |
| A parallel join waits for all the incoming edges | Case 3: the exclusive takes only A, the in-degree of the JOIN goes from 2 to 1 and the following node is not executed |
| An exclusive takes only one branch | Cases 4/5: the default wins, otherwise the first one after a stable sort |

Executing every node in topological order (including branches that were not selected) and executing only the activated path produce different node counts. The nodes that drop out are drawn on the graph but not reachable. They are not lost.

The implementation points:

- `WorkflowGraphParser`: recognises `customDevice`/`custom*`/standard tasks, the two kinds of gateway, `conditionExpression` and the default outgoing edge
- `GraphRunner`: in-degree counting + a ready queue; an AND join for the parallel, an OR join for the ordinary nodes (de-duplicated by done)
- The capability resolution is extracted into a shared method; the two execution paths (NodeDef / GNode) reuse the same six-level rule set

### B.2 Preflight before saving (2026-10-29, the same round)

A full dry run of the workflows can finish, with zero blocked. Preflight reports: an exclusive missing both a condition and a default edge, a node that will never be executed, and a parallel join whose incoming edges do not all arrive.

How it checks: static rules (capability / device, exclusive exits, start events) + a **dry run with an empty executor**.
The dry run reuses the advancement logic of `GraphRunner`, so it can find the **structural deadlocks** that are hard to find by static analysis.

A single-workflow preflight takes < 1 s and can be used as a pre-save validation.

#### One business problem the preflight exposed

Some workflows contain nodes whose connections never reach them. The reason is not a program defect, it is that **the connections on the graph cannot reach those nodes**.

If the executor walks every node in topological order, those unreachable nodes still run, and the structural defect of the graph is hidden.
After the switch to execution by the graph they no longer execute, and the behaviour changes.

> Business confirmation needed: is the semantics of these workflows when they were drawn "run only the connected path", or "everything that is drawn should run"?
> If it is the latter, these nodes should be connected into the main path when saving, rather than letting the executor ignore the graph.

### B.3 Attendance mode (2026-10-29, the same round)

The `run.attendance` field; the difference between the two modes shows in the **alert level**:

| Mode | The severity when it becomes MANUAL | Meaning |
|---|---|---|
| `attended` | `warn` | Attended; an alert is recorded but it does not block (handled on the paused-queue page) |
| `unattended` | **`error`** | Nobody is next to it, it must be visible at a glance during the patrol |

Measured (the same workflow, the same failure):

```
Mode          Alert level   Count
attended      warn          1
unattended    error         1
```

The interfaces:

- `POST /api/workflows/{id}/run?mode=unattended` —— start in the unattended mode
- `POST /api/runs/{id}/attendance?mode=...` —— switch (only takes effect for a run with `status='running'`)

#### Asynchronous execution (filled in on the same day)

`POST /api/workflows/{id}/run?async=1` returns the `runId` immediately, a background thread keeps executing,
and `run.status` stays `running` in the meantime.

Measured (one multi-node workflow):

```
runId=204871
Queried immediately after the start   status=running   attendance=attended   nodes=0
Switched while running     {"updated":1,"note":"已切换"}
After completion         status=completed attendance=unattended nodes=130
```

**This fills in the concept of a "run in progress"**, on which switching the attendance mode while running, the Process Status and the claiming of the alerts all depend.

#### One defect that was fixed along the way

The logic nodes such as `Delay` are looked up in the driver registry by `DriverStepExecutor` as if they were devices,
and after `EC-06-0001` (the driver is not loaded) is raised they are judged as a fault.

The fix: when `resourceId == 0` or `capability == LOGIC` the kernel completes them directly, without going into the driver registry.

> This is a recurrence of the "logic node" problem on the **second execution path** —— it had already been fixed once (with the pseudo resource 0), in `RealWorkflowRunner`, but `DriverStepExecutor` did not make the same judgement.
> Once the two paths share one capability resolution, this kind of inconsistency decreases, but the branch of the executor still has to be checked on its own.

### B.4 Process Status View (2026-10-29, the same round)

It answers "where is this run now, where is it stuck, on which device".

The `run_node` is only committed in bulk when the execution **ends**, so querying the database during the execution returns nothing; therefore an in-memory state is used:

```
GET /api/process-status
→ [memory] 20/65  node=Activity_1c6trsm  device=183  status=running
```

The "Status" tab of the page: the runs in progress on the left (with a progress bar), the current node / device / latest event on the right.

The status vocabulary follows specification 7.7 (the running states), not the three nest states.

#### A historical problem that was cleaned up along the way

The first run of `process-status` produced **51 zombie `running`** entries —— the early executions did not close out properly when they failed.

They have been cleaned up (`status='failed'`, marked with `input.cleanup`), and a `markRunFailed` fallback has been added to the asynchronous execution.

### B.5 The scope of kernel-0.3

The migration, the decisions, the event execution, the alert aggregation, the recipe expansion, the designer, the virtual drivers and the Sidecar mock, and the structural advancement of all workflows. The basis is in appendix B.

### Period 1: execution by the graph, and telling who is on duty

| Deliverable | Ledger |
|---|---|
| The Decision and the Parallel | 1.4, 1.5 |
| The preflight before saving | 2.1 |
| The attendance mode | 3.4 |
| The reassignment when offline, the run is not aborted | 3.5 |
| The writable policies + the replay distribution | 6.4, 9.1 |
| The claiming/closing of the alerts, the Process Status | 7.2, 7.3 |
| The compilation projection and the node overrides | 1.3, done in this period, does not block the release on its own |

### Period 2: time

| Deliverable | Ledger |
|---|---|
| The duration model and the historical import | 5.2, 5.3 |
| The total-duration forecast | 5.4 |
| The Time Constraint | 1.6 |

### Period 3: one real endpoint

| Deliverable | Ledger |
|---|---|
| Shadow mode running through one endpoint | 8.3 |

Shadow mode does not depend on the Gantt chart. The reassignment of period 1 is accepted first on the virtual drivers; period 3 only confirms the contract of the real endpoint and the "does not drive production actions".

### After the release

The Gantt and the throttle, the single-step and the in-place modification, the resuming from a breakpoint, the sub-processes, the experiment blocks executed in sequence, the device colouring, the containers connected to the run, the crash recovery, the nest-level readiness decisions, the conditions and the loops of the experiment blocks, the global optimisation, the frontend framework and Spring Boot.

---

## 12. The decisions written into this version

The following rulings are part of the specification. When one of them is overturned, append a row to `决策记录.md` and change the corresponding chapter of this document.

| # | Topic | Ruling |
|---|---|---|
| D1 | How many kinds of relation the experiment blocks need to support | 2.0 only does the sequence. The conditions, the loops, the timers and the semaphores do not enter the release gate |
| D2 | The existing exclusive gateways have no condition expression | A preflight warning. The execution takes the default, otherwise the first outgoing edge after a stable sort. The event marks that no condition was used. Executing both is forbidden |
| D3 | Whether the parallel needs a wall-clock overlap | 2.0 only requires the join semantics. The wall-clock parallelism does not block the release |
| D4 | How the conditional expressions are implemented | The whitelist is used once the conditions appear (comparisons, and / or, variables and upstream state). No SpEL, Groovy or arbitrary script |
| D5 | The frontend framework | 2.0 keeps the existing console and bpmn-js. React is decided when the experiment-block canvas is started as a project |
| D6 | Spring Boot | **Overturned (2026-11-10)**: the 2.0 kernel now uses Spring Boot 3.2.5 (Spring MVC HTTP layer + MyBatis XML + Spring transactions + HikariCP + SLF4J). The JDK HttpServer is retired; the device gateway stays on bare JDK and pulls in no framework. See `决策记录.md` and ADR-TECH-001 |
| D7 | Where the plate is | 2.0 does not implement the nest tracking. The container identity is P1. The three-state readiness waits until there is a position |
| D8 | How the real devices are connected | Shadow mode with one endpoint first. It is not required to take every device down at the same time |
| D9 | From which step a re-run after a device switch starts | From the first step of that node |
| D10 | The conservative basis of the time constraint | Whether the forecast will time out uses the duration p90; the total duration that is displayed uses p50 |
| D11 | The policy coverage | The replay must show the distribution of the actions. Deriving a historical Ignore into a `SKIP` does not count as the recovery capability being finished |

---

---

## 13. Run state and lifecycle

This chapter consolidates the **run-state semantics** that were scattered across the code and the design documents into one basis. The authoritative implementation is
`labos-kernel/src/main/java/com/labos/kernel/workflow/StatusVocabulary.java`
(the contract version `2026-11-03`); the backend exposes it at `GET /api/meta/status-vocabulary`, and the frontend
`webapp/src/utils/status.ts` only provides a fallback. Changing the vocabulary means changing this one class and bumping the version number.

### 13.1 The five-level status vocabulary

Each of the five scopes has its own set of status codes, and the counts differ: **run 11 / node 9 / step 6 / experiment 12 / batch 7**.
`terminal` = a terminal state (it never changes again), `active` = an occupying state (it appears in the "currently busy / in the queue" statistics).

**run (the execution record)**

| Code | Label | terminal | active |
|---|---|---|---|
| `draft` | Draft | no | no |
| `pending` | Pending | no | no |
| `planned` | Planned | no | no |
| `queued` | Queued | no | yes |
| `running` | Running | no | yes |
| `paused` | Paused | no | yes |
| `completed` | Completed | yes | no |
| `failed` | Failed | yes | no |
| `aborted` | Aborted | yes | no |
| `cancelled` | Cancelled | yes | no |
| `skipped` | Skipped | yes | no |

**node (the node)**

| Code | Label | terminal | active |
|---|---|---|---|
| `pending` | Not executed | no | no |
| `ready` | Ready | no | no |
| `running` | Running | no | yes |
| `completed` | Completed | yes | no |
| `failed` | Failed | yes | no |
| `manual` | Awaiting a human | no | yes |
| `skipped` | Skipped | yes | no |
| `aborted` | Aborted | yes | no |
| `bypassed` | Branch not taken | yes | no |

> **`bypassed` and `skipped` must be kept apart**: `bypassed` is a branch not taken by an exclusive gateway and is a **normal close-out**;
> `skipped` is a policy skip, which means **the plate position is untrustworthy and the system stops for a human**. Both reach a terminal state, but the handling implication is the opposite.

**step (the step)**

| Code | Label | terminal | active |
|---|---|---|---|
| `pending` | Not executed | no | no |
| `running` | Running | no | yes |
| `completed` | Executed | yes | no |
| `failed` | Failed | yes | no |
| `skipped` | Skipped | yes | no |
| `paused` | Paused | no | yes |

**experiment (the experiment)**

| Code | Label | terminal | active |
|---|---|---|---|
| `created` | Created | no | no |
| `planned` | Planned | no | no |
| `queued` | Queued | no | yes |
| `running` | Running | no | yes |
| `paused` | Paused | no | yes |
| `completed` | Completed | yes | no |
| `finished` | Finished | yes | no |
| `failed` | Failed | yes | no |
| `aborted` | Aborted | yes | no |
| `cancelled` | Cancelled | yes | no |
| `skipped` | Skipped | yes | no |
| `deleted` | Deleted | yes | no |

> Both `completed` and `finished` mean completed; the two codes are kept for compatibility with historical writes, and new writes use `finished` uniformly (see 13.2).

**batch (the batch)**

| Code | Label | terminal | active |
|---|---|---|---|
| `pending` | Pending | no | no |
| `queued` | Queued | no | yes |
| `running` | Running | no | yes |
| `paused` | Paused | no | yes |
| `completed` | Completed | yes | no |
| `failed` | Failed | yes | no |
| `aborted` | Aborted | yes | no |

### 13.2 run → experiment status mapping

The experiment list / detail shows the experiment status, while the persisted subject is the run. The two must have the same source, otherwise drift such as
"the list shows completed while the detail shows failed" appears. The mapping is defined by `StatusVocabulary.RUN_TO_EXPERIMENT`,
and the persistence SQL fragment is generated by `runToExperimentCaseSql()` (`CASE WHEN r.status='...' THEN '...' ELSE r.status END`):

| run.status | experiment.status |
|---|---|
| `completed` | `finished` |
| `failed` | `failed` |
| `running` | `running` |
| `paused` | `paused` |
| `aborted` | `aborted` |
| `cancelled` | `cancelled` |
| `skipped` | `skipped` |
| `queued` | `queued` |

### 13.3 The three pause kinds

"Paused" is not one state; it is one state (`run.status = paused`) plus a reason (`run.pause_kind`). The three reasons have different semantics:

| pause kind | Label | Trigger | How it resumes |
|---|---|---|---|
| `paused_fault` | Device fault | A device heartbeat timeout / fault | **Automatic**: it resumes automatically once the heartbeat recovers, no human needed |
| `waiting_human` | Waiting for a human | The retry / reassign policy is exhausted, or a human judgement is needed | **Manual**: click "Continue" |
| `paused_unknown` | Plate position unknown | An exception during a transfer, the plate position is `UNKNOWN` | **Manual**: take stock and correct the plate position first, then continue |

While `pause_kind` is set a `run` does not reach a terminal state; a `waiting_human` under unattended mode is escalated to an alert at the same time (see 13.8).

### 13.4 On-site holds (hold)

An "on-site hold" is used to **freeze a whole scope with a single event** (for example, when a device on an island faults, other experiments on the same island should also stop sending work to that device).
It is described by two dimensions, `hold kind` (why it is held) and `hold scope` (how large a scope is held).

**Hold kind**

| code | Label |
|---|---|
| `fault` | Device fault |
| `plate_unknown` | Plate position unknown |
| `human` | Awaiting human confirmation |
| `manual` | Manual pause |

**Hold scope**

| code | Scope |
|---|---|
| `site` | The whole site |
| `island` | The island |
| `experiment` | This experiment |

**Priority: the more inner, the higher, `experiment > island > site`.**
A hold **does not release resources** (otherwise a parallel body would grab the held position); but the "return reserved slot"
(`position-return`) is a promise and must be kept at all times, even under a hold.

> The island ID of the scope must be a positive number: a negative number is treated by the logic as "unassigned" and makes the hold ineffective.

### 13.5 Crash recovery

A process crash (not the graph execution stopping at a node boundary) follows another path:

1. At start-up, `StartupRecovery.settleOrphanExperimentRuns` marks the orphaned records left in `running` as **`failed`**;
2. On a crash it sets `recoverable=true`; the page `/api/runs/recoverable` lists the recoverable records;
3. **No automatic resume**: it is only recovered when a human clicks "Continue"; the system does not re-run automatically;
4. **Only the records interrupted in the last 7 days are listed** — clicking "Continue" on an older one would re-run with a stale state, which counts as an incident.

The difference between a "pause" (13.3) and a "crash" (this section): a pause is **the graph execution waiting at a node boundary for a human**, with the process still alive; a crash is **the process being gone**, and the recovery relies on the persisted progress.

### 13.6 Batched execution (S3.2)

When the number of samples of an experiment exceeds a threshold it is split into batches submitted one batch at a time, lowering the resource peak of a single submission:

- **Splitting**: when `batchSplit` is NULL or ≤ 0 no split happens; otherwise a batch is cut every `batchSplit` samples in the order of the input;
- **Re-run**: when one batch is re-run, `attempt_no` increments from 1, and the historical attempts are kept (so "which attempt" is traceable);
- **An already completed batch refuses a re-run**: error code `COMPLETED_BATCH_NOT_RERUNNABLE`;
- **Batch states**: see 13.1, 7 states.

### 13.7 Resuming from a breakpoint

The key of the resume is **`seq:loop_index`**: each lap of the loop of the same `seq` can be resumed independently. On recovery it reads the
`node_key` of the `completed` nodes as the `skipKeys`, and `GraphRunner.withSkipKeys` skips them and only fills in the ones after.

> Known boundary: `skipKeys` matches by `node_key`; after a node is **renamed** the old `node_key` no longer matches and it re-runs — this boundary is pinned by the `workflow.GraphRunnerTest` case "resume: a rename mismatch re-runs".

### 13.8 Attendance mode and alert level

Two operating modes, switchable while running (`POST /api/runs/{id}/attendance`). **The only difference that is implemented is the alert level**:

| Mode | The alert level of "hand over to a human (MANUAL)" |
|---|---|
| Attended (attended) | `warn` (non-blocking) |
| Unattended (unattended) | `error` (an alert is recorded) |

In both modes the fault handling path is the same: a fault is handed over to a human (`MANUAL`), and the human disposes of it on the paused-queue page (`PausedRuns` / `RunDetail`).

> **Not implemented**: of release criterion 1.1 item 2, "an attended dialog whose default comes from the policy", the **auto dialog is not done** — there is no code path that "raises a blocking dialog with the default item from `recovery_policy` and waits for a decision". See ledger 6.5 in §8 and gap 2 of the "honest list" in `docs/en/test-case-master-table.md`.

An attendance switch only affects a run that is **not finished**.

---

## 14. Concurrency, resources and scheduling

### 14.1 The three levels of concurrency (do not conflate them)

"Can it run in parallel" has to be answered at three levels, and the conclusions differ:

| Level | Current state | Evidence |
|---|---|---|
| ① Multiple workflows / parallel branches inside one experiment | ✅ supported and measured | `scripts/verify-parallel-flow.py` 4/0 |
| ② Several experiments concurrent on the same island | ✅ but **queued serially**, peak concurrency = 1 (island-level mutual exclusion) | `scripts/verify-parallel-experiments.py` 3/0 |
| ③ Concurrency across different islands | ⚠️ the code supports it (the thread pool is at most 4), this environment has only one island, **not measured** | code reading only, see §4 of `docs/并行能力验证-20261103.md` |

The scheduling Gantt does not model parallelism: it accumulates serially as "only one experiment runs at a time", so what it gives is a **lower bound** — the real completion can only be earlier. This is a deliberate trade-off.

### 14.2 The four-level resource model

The resources that a single transfer occupies fall into four levels, with different granularity and mutual-exclusion meaning:

| Level | Resource | Table | Granularity | Mutual-exclusion semantics | Occupancy |
|---|---|---|---|---|---|
| L1 | Position | `position_state` | One tier | Occupancy-level mutual exclusion | The whole task |
| L2 | Channel | `device_port` | One inlet / outlet | `capacity=1` mutual exclusion; `>1` is a semaphore | **Action level** |
| L3 | Door | `device_access` | One openable/closable part | Action-level exclusivity | **Action level** |
| L4 | Robot arm | `device_state` | One robot | Action-level exclusivity | **Action level** |

The `capacity` of L2 means "how many plates can pass at once": 1 = serial, >1 = parallelisable (counted with `device_port_usage`; `capacity=1` is just the special case of `max_capacity=1`).

### 14.3 Fixed acquisition order

The resource requests of one Transfer follow a **fixed total order**, and out-of-order requests are not allowed, which rules out the circular wait at its source:

```
① source device door (L3) → ② source channel (L2) → ③ source position (L1)
→ ④ target device door (L3) → ⑤ target channel (L2) → ⑥ target position (L1)
→ ⑦ return position (L1) → ⑧ robot arm (L4)
──────────────────────────────────────────────
everything ready; the plate is still at the source position and can be abandoned at zero cost
──────────────────────────────────────────────
⑨ grab the plate  ← the point of no return
```

**The door comes first**: the door is the "main switch"; without opening it you cannot get in; if the door is open but the robot arm did not get it → the door can be closed and the attempt abandoned, at little cost.

### 14.4 Wait-for-graph deadlock detection

Even with the fixed order, one layer of safety net is kept: the **wait-for graph + DFS cycle detection**.

| Property | Value |
|---|---|
| Algorithm | DFS cycle detection |
| Scale | number of resources n ≤ 10, number of edges E ≤ 20 |
| Single-run time | < 10 microseconds |
| Switch | **always on, no switch** |

Why always on: one Transfer involves about 6 resource requests ≈ 60 microseconds, which is negligible next to one device action (150 ms to 30 minutes); introducing a switch to save that little overhead would instead bring races and missed detections.

### 14.5 Why the banker's algorithm is not used

| # | Reason |
|---|---|
| 1 | A workflow has branches, and before the execution reaches a decision node it is **impossible to declare a "maximum demand"** (taking branch A needs 3 devices, taking branch B needs 0, unknowable in advance) |
| 2 | To declare it anyway you would have to use the worst case (the union of all branches), which amounts to every workflow declaring "I want the whole island", **reducing concurrency to zero** |
| 3 | Recomputing after the branch is determined is expensive, and the already-held resources must be included; the later it gets, the more meaningless it is |
| 4 | The banker's algorithm assumes "the declaration does not change", but a human changing a parameter overturns the declaration and **the assumption does not hold** |
| 5 | It is almost never used in engineering practice: PostgreSQL / MySQL / Linux all use a wait-for graph or a lock order |

### 14.6 Scheduler

| Property | Value |
|---|---|
| Ready decision | Three states: `pending` → `running` → `completed` (plus `paused` / `cancelled`) |
| Polling interval | 2 seconds |
| Island lock acquisition | `putIfAbsent` (a CAS atomic, guaranteeing no concurrency on the same island) |
| Ordering | **EDD → priority → FIFO** (earliest due date first; on a tie by priority; on a further tie first submitted first run) |
| Restart recovery | rehydrate: the queue is restored after a restart (`SchedulerRehydrateSelfTest`) |

### 14.7 Duration model

- **Basis**: p50 (the displayed total duration) / p90 (judging whether the longest interval is exceeded, conservative).
- **Samples**: only the **cleaned** `completed` records with a positive duration, aggregated by "capability + method".
- **Variance**: the duration of the same action varies widely — for `Run`, p50 = 18.5 s and p90 = 250 s, a **variance ratio of 13.5×**. This is the root reason why the scheduling must be conservative and must not be over-optimistic about a single-point estimate.

### 14.8 Rolling schedule

**The 2.0 implementation is "re-sort the whole queue every tick and dispatch in place"**, not the rolling window of the design:

- The scheduling loop re-reads the queue from the database every **2 seconds** (`TICK_MS`), sorts it by `QUEUE_ORDER` (EDD → priority → FIFO, see §14.6), and tries to dispatch it entry by entry;
- Each time it counts from "the number currently running", up to `MAX_CONCURRENT = 4` in parallel (different islands); the same island is serialised by the island lock (`putIfAbsent`, CAS), only different islands run in parallel;
- An experiment that has started holds the island lock until it finishes, and the scheduler never changes it — that is the source of "an experiment that has started is not interrupted" in the current implementation, but it is **not** the commitment window of the design.

**Not implemented (out of scope)**: the three mechanisms of the design — the **commitment window** (explicitly declaring that an experiment that has started is never changed), the **frozen zone** (no change in the next 15 minutes), and the **greedy rolling** (scheduling only one window at a time, and the next segment after it finishes) — are all absent from the scheduler. The rationale of that design still holds (the duration is an estimate, so computing a full-DP optimum from inaccurate data is meaningless), but it has not been landed.

---

## Appendix A: Glossary

| Term | Meaning |
|---|---|
| Workflow | One process workflow. The diagram elements are in `workflow_node`, among which the device-action nodes are counted separately |
| Node | A business step in the workflow, declaring the capabilities it needs |
| Step / Command | One device command after the node is expanded |
| Capability | A capability, such as `TRANSFER`, `INCUBATE`, `READ_PLATE`. 16 in the dictionary |
| Recipe | The command-sequence template belonging to a capability |
| Decision | Exclusive: only one outgoing edge is taken |
| Parallel | Parallel: all the branches are executed, the join waits |
| Time Constraint | The shortest or longest interval between two steps |
| Container | A physical item such as a plate, identified by a barcode and a plate type |
| Run mode | Attended or unattended |
| Shadow | Shadow mode: the commands are computed and recorded, no production device is driven |
## Appendix B: The proven scope

| Statement | Evidence | Must not be extrapolated into |
|---|---|---|
| The workflows and all the device-action nodes can be walked to the end on the simulation bus, with no cycle and no isolated task | `BATCH_VALIDATION_REPORT.md` | The gateway semantics are executed; the durations are correct; it is concurrency-safe; the bulk path covers the fault recovery |
| A recovery decision yields the same action for the same input, and it carries the reason | The decision engine of `labos-kernel` and `COVERAGE_REPORT.md` | A historical coverage of 100% means that the site will self-heal. When the action is derived from historical handling, a large number of results become `SKIP` |
| Historical errors are aggregated and the alert count falls | `ALERT_REPORT.md` | The on-site alerts can already be claimed and closed |
| A node can be expanded according to `capability_recipe` and a failure can be located at step N | `RecipeCompiler` and the multi-step execution | The projection in the designer and the node overrides are already product behaviour |
| The driver contract has been walked through on the virtual driver and on the local Sidecar mock | `DRIVER_CONTRACT.md` | The real endpoint has been commissioned |

The reproduction steps are in chapter 9 of `LabOS_新一代中控实现总纲_20261029.md`. The master plan is the implementation snapshot of kernel-0.3; where its use of "executable" conflicts with this appendix, this appendix wins.

## Appendix C: Document map

| File | Status |
|---|---|
| This document | The functional specification, a living document |
| `决策记录.md` | The changes after chapter 12, append-only |
| `README.zh-CN.md` | The directory entry point |
| `LabOS_新一代中控实现总纲_20261029.md` | The kernel-0.3 snapshot |
| The reports `labos-kernel/BATCH_VALIDATION_REPORT.md` etc. | Evidence; the specification cites them and does not copy the tables |
| `archive/LabOS2.0_功能规格说明书_v0.3.md` | The draft that this document supersedes |
| `目标功能文档_三方合并版.md` | The feature list and the phasing are superseded. The scope choices are written in chapter 4 |
| `前后端实施方案_编译甘特就绪调度.md` | The weekly plan is superseded. The compilation "expanded at run time, not landed as a node" has been absorbed into section 7.4 |
| `LabOS系统_现状梳理与新一代架构方案_20261029.md` | The review is kept. The roadmap of chapters 5–7 is superseded |
