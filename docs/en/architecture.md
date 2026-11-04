# Technical Architecture and Module Map

> **English edition.** The Chinese original is [`docs/技术架构与模块地图.md`](../技术架构与模块地图.md); this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they ever disagree, the Chinese original wins.

> Companion: `../评审指引.md`. This document is about **code structure**: what each
> package/class is responsible for, who calls whom, and where the data flows.

---

## 1. Overall architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Browser (React 18 + TS + Antd + React Flow)                                 │
│  webapp/                                                                    │
└──────────────────┬──────────────────────────────────────────────────────────┘
                   │ HTTP/JSON  :18080
┌──────────────────▼──────────────────────────────────────────────────────────┐
│ Kernel labos-kernel/ (pure Java 17, no framework)                           │
│                                                                             │
│  api/      routing and parameter parsing                                    │
│  workflow/ parsing · execution · experiment orchestration · scheduling      │
│  engine/   state machine (retry / hand-over / idempotency)                  │
│  driver/   device driver abstraction + implementations                      │
│  recovery/ crash recovery                                                   │
│  alert/    alerting                                                         │
└──────────────────┬──────────────────────────────────────────────────────────┘
                   │ ① JDBC
        ┌──────────┴────────┐
        ▼                   ▼
┌──────────────┐    ┌──────────────────────┐
│ PostgreSQL   │    │ TCP + JSON           │
│ labos        │    │ #!LABSEP!# delimiter │
└──────────────┘    └───────┬──────────────┘
                            ▼
                   ┌────────────────────────┐
                   │ Vendor driver service  │
                   │ (.NET Sidecar)         │
                   └────────┬───────────────┘
                            │ Vendor DLL
                            ▼
                   ┌────────────────────────┐
                   │ Devices / robot arm    │
                   │                        │
                   └────────┬───────────────┘
```

**The gateway is an optional path** (`labos-gateway/`): control console → Redis → gateway → device.
The default is still a direct connection; the gateway works but has not been switched over.

---

## 2. Kernel package structure

### `api/` — HTTP layer

| Class | Lines | Responsibility |
|---|---|---|
| `ApiServer` | ~1500 | Routing, parameter parsing, JSON assembly, authentication |
| `QueryService` | ~900 | The various queries (topology, positions, experiments, execution history, points) |
| `AuthService` | ~100 | Login, token |

**Known issue**: `ApiServer` is a god class; the routes are strung together with
`if (path.equals(...))`.
Worth raising at review: after this many endpoints have been added, should it be split
into handlers?

### `workflow/` — workflows and experiments (the core)

| Class | Responsibility | Key methods |
|---|---|---|
| `WorkflowService` | Main entry point for workflow execution | `execute()`、`withIsland()`、`withVars()` |
| `WorkflowGraphParser` | Parses BPMN XML | `parse()` |
| `GraphRunner` | Executes along the graph (parallel / exclusive gateway) | `run()`、`toRecoverable()` |
| `ExperimentExecutor` | Experiment execution (variables + loops) | `run(expId, vars, existingRunId)` |
| `ExperimentScheduler` | Multi-experiment parallelism (island lock + queueing) | `submit()`、`status()` |
| `ExperimentFlowRunner` | Canvas step-by-step execution | `run(name, steps, vars)` |
| `RealWorkflowRunner` | Process regression test | `--all` runs the full workflow set |
| `WorkflowBuilder` | Generates a workflow from a transport sequence | `build()` |
| `PositionLock` | Position lock (lives in the driver package, but is business logic) | `tryReserve()`、`notePlateMove()` |

**Two execution paths** (the review focus):

```
Path A: WorkflowService.execute(workflowId)
        → GraphRunner.run() or RunStateMachine.executeAll()
        → DriverStepExecutor → device

Path B: ExperimentFlowRunner.run(steps)
        → ShadowProbe → device (step by step)
```

**Both write run/run_node/run_command, but device allocation and variable substitution
are two separate code paths.**

### `engine/` — state machine

| Class | Responsibility |
|---|---|
| `RunStateMachine` | Node-level state machine: pending→ready→running→completed/failed, retry, hand-over to an operator |
| `NodeProgress` / `RunProgress` | Progress snapshot |

**Key semantics**: a failed retry reuses the same `commandId` (idempotency key), so the
robot arm does not move the plate twice.

### `driver/` — device drivers

| Class | Responsibility |
|---|---|
| `DeviceDriver` | Interface: `describe()` / `execute()` / `abort()` / `healthy()` |
| `DriverRegistry` | Finds devices by capability |
| `DriverStepExecutor` | Executes one step (30 s timeout, calls the sink to persist) |
| `tcp/TcpDriverAdapter` | **Short connection**: one connection per command, reads one line |
| `tcp/TcpPersistentDriver` | **Persistent connection**: pairs by `messageId`, `pending` Map |
| `PositionLock` | Position lock |
| `ShadowProbe` | Single-command probe (shadow mode, does not write a run) |
| `ShadowRunner` | Whole-workflow shadow (sends no commands, only logs) |

**The two-ID problem lives in this package**: in `DeviceDriver.execute(DriverCommand)`
`deviceInstanceId` is `device_instance.id`, while the graph stores `resource_node.id`.

### `recovery/` — crash-recovery decisions

| Class | Responsibility |
|---|---|
| `RecoveryDecider` | Decides whether a node should be retried / skipped / handed to an operator after a crash |
| `workflow/StartupRecovery` | Startup crash cleanup (scans unfinished runs) |

**There is no "lease takeover"** — it was in the early design, but was never implemented.
The idempotency key still holds: `run_command.command_id` is a unique key, so a resend does
not execute twice.

**Safety net**: a crashed workflow recovers automatically (**note**: this once caused a
workflow to run twice — see the development log, entry B6).

### `alert/` — alerting

Missing plate, timeout, failure, contract mismatch. De-duplication relies on
`fingerprint = errorCode|resourceId`.

---

## 3. Data model (core tables)

```
resource_node          resource tree: island → device → position (kind: island/device/incubator/storage)
  ├ parent_id          self-reference
  └ attr jsonb         holds pos_x/pos_y (canvas coordinates)

device_instance        device instance (one physical device)
  ├ resource_id        → resource_node.id  ★ the mapping point between the two ID sets
  └ driver_id          → driver endpoint

device_state           device-level state (has_plate ← derived from position_state)
position_state         position-level state (plate_id, reserved_until) ★ single source of truth

workflow / workflow_node / workflow_edge   the BPMN graph
run / run_node / run_command               execution records (commands carry device_id)

experiment            experiment (island_id / priority / deadline)
experiment_process    the steps of an experiment (workflow_id / seq / loop_count)
experiment_variable   experiment variables
experiment_run / experiment_run_step       experiment execution records

island_flow           island routing (device connections on the canvas)
experiment_graph      experiment canvas (devices + logic nodes, jsonb)
robot_point           robot points (code/x/y/z/device_resource_id)
```

---

## 4. Key data flows

### Workflow execution

```
POST /api/workflows/{id}/run
  ↓
WorkflowService.execute()
  ├ parse BPMN → ordered nodes
  ├ build the candidate pool capPool (by capability → list of device instance ids)
  │    └ preferEmptyDevices: empty devices first
  ├ GraphRunner.run()
  │    └ toRecoverable: rotate the devices by capability (usageCursor)
  └ DriverStepExecutor.execute() for each step
       ├ DriverCommand (commandId=idempotency key, timeoutMs=30s)
       ├ TcpDriverAdapter.execute() → TCP → device
       └ PersistingStepSink.writeStep()
            ├ INSERT run_command (includes device_id)
            └ PositionLock.notePlateMove() (Deposit puts / Withdraw takes)
```

### Experiment execution

```
POST /api/experiments/{id}/run?async=1
  ↓
ExperimentScheduler.submit()      creates experiment_run (queued)
  ↓ background polling every 2 seconds
  ├ take the island lock (putIfAbsent)
  └ ExperimentExecutor.run(expId, vars, existingRunId)
       ├ loadVariables (default value ← override value)
       ├ expand (expand by loop_count, cap 20/500)
       ├ substitute ${var} (once, before start)
       └ call WorkflowService.execute in sequence (stop on failure)
```

### Canvas step-by-step execution

```
POST /api/experiments/flow-run?steps=id:method:output:params&vars=
  ↓
ExperimentFlowRunner.run()
  for each step:
    ├ instanceIdOf(resourceId)       ★ ID conversion
    ├ enrichWithCoords (fill in the point coordinates when withCoords=1)
    ├ precheckTransfer (before Transfer, check whether the source device holds a plate)
    ├ ShadowProbe.probe()            → TCP → device
    ├ write run_node / run_command
    └ parse the output variables (take the fields from the driver's raw response)
```

---

## 5. Frontend structure

```
webapp/src/
├── api/client.ts       apiFetch (unified 401 handling)
├── pages/
│   ├── Island.tsx            island view: place devices by drag, transport sequences, position visualisation
│   ├── IslandFlow.tsx        island orchestration: device links = plate moves
│   ├── ExperimentCanvas.tsx  experiment canvas: devices + variables + branching + loops + merges (React Flow)
│   ├── Experiments.tsx       experiment orchestration: variable table, loop column, execution history
│   ├── Workflows.tsx         workflow library
│   ├── Designer.tsx          BPMN designer (bpmn-js)
│   ├── Monitor / Alerts / ... monitoring, alerting, logs
│   └── Login.tsx
└── App.tsx            routing + navigation
```

**The difference between the two canvases** (easy to mix up):

| | `ExperimentCanvas` | `IslandFlow` |
|---|---|---|
| Node | Device + variable / branch / loop / merge | Device only |
| Arrow | Logical flow | The plate is moved there |
| Purpose | How the experiment runs | How the logistics move around the island |

---

## 6. Deployment topologies

### Now (single machine)

```
one machine: kernel + frontend + PostgreSQL
direct device connection (TcpDriverAdapter)
```

### Target (multi-instance + gateway)

```
console × N  ──┐
               ├── Redis (state centre)
gateway × M  ──┘
               │
           devices
```

**The gateway is complete but has not been switched over**. The switch: `LABOS_DRIVER_MODE=redis`
(not yet implemented) / `persistent` (implemented) / empty (short connection).

---

## 7. Module dependency direction

```
api  →  workflow  →  engine  →  driver
         ↓            ↓          ↓
      recovery      alert     persistence
```

**Reverse dependencies are forbidden** (`driver` does not depend on `workflow`).

`PositionLock` sits in `driver/` but is strongly business-oriented — **this is one flaw in
the current structure**; it can be raised at review: should the position lock belong to
`workflow`, or become its own `plate/` package?
