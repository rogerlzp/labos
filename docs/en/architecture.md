# Technical Architecture and Module Map

> **English edition.** The Chinese original is [`docs/技术架构与模块地图.md`](../技术架构与模块地图.md); this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they ever disagree, the Chinese original wins.
>
> Companion: [`onboarding.md`](onboarding.md). This document is about **code structure**: what each
> package/class is responsible for, who calls whom, and where the data flows.
>
> **Technology baseline (2026-11-10)**: the kernel runs inside a **Spring Boot 3.2.5 / Java 17**
> container. The HTTP layer is Spring MVC (Tomcat owns port 18080), persistence is MyBatis XML,
> the connection pool and transactions are managed by Spring, and logging goes through SLF4J.
> The per-batch migration history and its measured evidence live in
> [`../architecture/SPRING_BOOT_MIGRATION_20261109.md`](../architecture/SPRING_BOOT_MIGRATION_20261109.md).

---

## 1. Overall architecture

```
┌──────────────────────────────────────────────────────────┐
│ Browser (React 18 + TS + Antd + React Flow)  webapp/     │
└───────────────────┬──────────────────────────────────────┘
                    │ HTTP/JSON  :18080
┌───────────────────▼──────────────────────────────────────┐
│ labos-kernel/   Spring Boot 3.2.5 · Java 17 · Tomcat     │
│                                                          │
│  src/boot/   container layer (lifecycle, threads, config)  │
│    web/controller/  12 @RestController (thin HTTP layer)   │
│    web/service/     RunService · WorkflowWebService        │
│    web/             auth interceptor · CORS · MVC · audit  │
│                                                          │
│  src/main/   domain layer (no Spring annotations)          │
│    workflow/ engine/ driver/ recovery/ alert/ expr/ sim/  │
│    persistence/  MyBatis Mapper · DbConfig · Tx           │
│    api/        AuthService · QueryService (queries + auth) │
└────────┬───────────────────────────────┬─────────────────┘
         │ HikariCP → JDBC                │ TCP + JSON
  ┌──────▼─────────┐              ┌───────▼────────────────┐
  │ PostgreSQL      │              │ Vendor driver service  │
  │ MyBatis XML     │              │ (.NET Sidecar)         │
  └────────────────┘              └───────┬────────────────┘
                                          │ Vendor DLL
                                    ┌─────▼────────────────┐
                                    │ Devices / robot arm   │
                                    └──────────────────────┘
```

**The gateway is an optional path** (`labos-gateway/`): control console → Redis → gateway → device.
The default is still a direct connection; the gateway works but has not been switched over.

**Framework boundary**: only the kernel is containerised. The gateway is a **separate process**
that deliberately stays on bare JDK + Lettuce (rationale in the `labos-gateway/pom.xml` comment:
the gateway is a "pipe", and pulling Spring in would add roughly 30MB of dependencies and 2–3
seconds of start-up time, which is inconvenient for on-site restarts).

---

## 2. Directory structure (boot / main, two layers)

```
labos-kernel/src/
├── boot/          Spring Boot container layer — every Spring dependency lives here
│   ├── java/com/labos/boot/
│   │   ├── LabosApplication.java        start-up entry point (the only one)
│   │   ├── BeanConfiguration.java       Service / Mapper wiring
│   │   ├── DataSourceConfiguration.java HikariCP + transaction manager
│   │   ├── KernelRunner.java            starts the kernel once the container is ready
│   │   ├── LogContext.java              MDC scope (traceId/runId/…)
│   │   └── web/                         Spring MVC wiring
│   │       ├── ApiAuthInterceptor.java  authentication
│   │       ├── CorsConfig.java          CORS allow-list
│   │       ├── WebMvcConfig.java        MVC registration
│   │       ├── AuditRecorder.java       operation audit
│   │       ├── controller/              12 controllers
│   │       └── service/                 2 web-facing business services
│   └── resources/
│       ├── application.properties       port / MyBatis / logging
│       ├── logback-spring.xml           log format and rotation
│       └── mapper/*.xml                 8 mapper XML files (all SQL lives here)
└── main/java/com/labos/kernel/           domain layer
    ├── workflow/ workflow · experiment · scheduling
    ├── engine/   state machine (retry / hand-over / idempotency)
    ├── driver/   device drivers · position lock
    ├── recovery/ crash recovery
    ├── alert/    alerting
    ├── expr/     expression evaluation
    ├── sim/      device simulator
    ├── redis/    Redis client
    ├── persistence/ MyBatis Mapper · DbConfig · Tx
    ├── device/   device type definitions
    └── api/      AuthService · QueryService · RbacRules · ApiServer
```

**Why two layers**: `src/main/` carries no Spring annotations ⇒ the domain logic can be
unit-tested without a container and can be driven directly by pure-JVM tools such as
`RealWorkflowRunner`; `src/boot/` only does wiring and HTTP adaptation, it holds no business logic.

---

## 3. Container layer (`src/boot/`)

| Class | Responsibility |
|---|---|
| `LabosApplication` | Start-up entry point. `@SpringBootApplication` + `@MapperScan` + `@EnableTransactionManagement`; excludes the DataSource auto-configuration (so Spring does not build a second Hikari pool) |
| `BeanConfiguration` | `QueryService` / `AuthService` / `WorkflowService` / `ExperimentScheduler` / `HeartbeatService` / `ApiServer` are all created by the container |
| `DataSourceConfiguration` | HikariCP pool (max 16 / min idle 2 / 60s leak detection) + `DataSourceTransactionManager` + `TransactionTemplate` |
| `KernelRunner` | Starts the kernel once the container is ready (crash-orphan settle, command-ledger reconciliation, queued rehydrate) |
| `LogContext` | MDC scope: `traceId` / `runId` / `commandId` / `workflowId`, try-with-resources, nestable |
| `web/ApiAuthInterceptor` | Authentication; applies only to HandlerMethods under `com.labos.boot.web.controller` |
| `web/CorsConfig` | CORS allow-list, any local port by default, overridable via `LABOS_CORS_ORIGINS` |
| `web/WebMvcConfig` | MVC registration |
| `web/AuditRecorder` | Operation audit (a controller cannot reach `HttpExchange`, so this is a separate layer) |

**Wiring precondition**: `final` was removed from the Service classes (CGLIB cannot proxy a final
class) so that `@Transactional` can take effect. **Same-class `this.xxx()` self-invocation does not
go through the proxy** — the write paths are concentrated in 4 entry points, the transaction opens at
the entry point, and the internal calls share the same connection.

---

## 4. HTTP layer (`src/boot/web/`)

### Layering contract

```
HTTP request
  ↓
ApiAuthInterceptor        authentication (token → Session into a request attribute)
  ↓
@RestController           parse parameters / assemble the response / audit
  ↓
Service (RunService / WorkflowWebService)   business orchestration
  ↓
Mapper (MyBatis XML)      SQL
  ↓
Domain layer (workflow / engine / driver …)
```

**Controllers are a thin HTTP layer**: no business branches, only parameter parsing, response shape
and auditing. Orchestration for the execution domain moved into `RunService`, for the workflow
domain into `WorkflowWebService`.

### Controller inventory

| Controller | Responsibility |
|---|---|
| `AuthController` | login / me / logout |
| `WorkflowController` | workflow CRUD, execution, graph persistence, preflight |
| `RunQueryController` | execution record queries (list / detail / progress) |
| `RunActionController` | execution control (start / pause / resume / stop) |
| `ExperimentQueryController` | experiment queries (list / detail / history) |
| `ExperimentActionController` | experiment control (create / run / stop / orchestrate) |
| `DeviceController` | device pool, heartbeat, state queries |
| `SchedulerController` | scheduler status |
| `OpsUserController` | user and role management |
| `OpsErrorController` | error events and manual windows |
| `MetaController` | status vocabulary, capability catalogue, model library |
| `FallbackController` | `/health`, static UI, 404 fallback |

### Before and after the migration

| | Before | After |
|---|---|---|
| Routing | `ApiServer.route()` with hand-written `if (path.equals(...))` chains | Annotated mappings; each endpoint carries its own path |
| Transport | JDK `com.sun.net.httpserver` + a 16-thread pool | Spring MVC / Tomcat |
| Business code | 9 `*Api` classes (4457 lines) mixing HTTP and business logic | split into Controllers (thin) + Services (business) |
| Authentication | checked inside every branch | handled uniformly by an interceptor |

**Leftovers**: `ApiServer` dropped from roughly 1500 lines to 614; only `runKernel()` (start-up
cleanup) and a few static helpers (`jsonField` and friends) remain. The methods inside it that are
still typed against `HttpExchange` **have no callers** (dead code); the **serving** responsibility
of the JDK HttpServer is fully retired.

---

## 5. Domain layer package structure

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

## 6. Persistence: MyBatis XML and transaction boundaries

### Why MyBatis XML

On-site decision: **SQL separated from Java, readable at a glance**. JPA clashes with this
project's `jsonb`, PG enums, `ON CONFLICT` and `FOR UPDATE`.

| Configuration | Value |
|---|---|
| Scanning | `@MapperScan("com.labos.kernel.persistence")` |
| XML location | `classpath:mapper/**/*.xml` (`src/boot/resources/mapper/`) |
| Field mapping | `map-underscore-to-camel-case=true` |
| Statement timeout | 30 seconds |

Eight mapper XML files exist today: `WorkflowMapper` / `RunMapper` / `GraphMapper` /
`QueryMapper` / `ExperimentMapper` / `MetaMapper` / `PauseMapper` /
`WorkflowWebMapper`.

**Not yet consolidated**: roughly 100 SQL statements still sit in the large write-path methods of
`QueryService`, in `DriverStepExecutor`, in `ExperimentExecutor` and elsewhere; moving them into
mappers is work in progress.

### Transaction boundaries

`setAutoCommit` / `commit()` / `rollback()` are **now zero** across the whole repository. There
are three transaction entry points:

| Entry point | Used where |
|---|---|
| `@Transactional(rollbackFor = Exception.class)` | write methods on Spring beans (**checked exceptions do not roll back by default, the declaration is mandatory**) |
| `Tx.inTransaction(db, c -> …)` | static utility classes (`PauseCoordinator` / `BatchOrchestrator` / `PositionLock` and friends), backed by `TransactionTemplate` |
| `Tx.rollbackOnly()` | an explicit rollback inside normal control flow (e.g. "validation failed on a duplicate request → roll back and return") |

**The bridge that makes this work**: `DbConfig.bind(ds)` injects Spring's `DataSource` into a
static field, so every `db.open()` in the repository goes through `DataSourceUtils.getConnection` —
legacy raw-JDBC code enters a Spring transaction without any change.

**A missing mapper throws immediately** (fail-fast); there is no silent "fall back to raw JDBC" —
such a fallback would let an environment with a missing binding quietly run the old path, and that
is impossible to diagnose.

---

## 7. Logging and diagnosability

| Item | Approach |
|---|---|
| Facade | SLF4J; all 539 `System.out` / `System.err` call sites replaced (48 files) |
| Implementation | `logback-spring.xml`: console + a full rotating file (daily / 100MB) + a separate execution-safety archive |
| Context | `LogContext` injects MDC: `traceId` / `runId` / `commandId` / `workflowId` |
| Format | `time level [thread] trace=… run=… cmd=… class - message` |

The log file lands in `labos-kernel/logs/labos-kernel.log`.

---

## 8. Data model (core tables)

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

## 9. Key data flows

### Workflow execution

```
POST /api/workflows/{id}/run
  ↓
WorkflowController → WorkflowWebService
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
ExperimentActionController → ExperimentScheduler.submit()
  ↓ creates experiment_run (queued)
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
ExperimentActionController → ExperimentFlowRunner.run()
  for each step:
    ├ instanceIdOf(resourceId)       ★ ID conversion
    ├ enrichWithCoords (fill in the point coordinates when withCoords=1)
    ├ precheckTransfer (before Transfer, check whether the source device holds a plate)
    ├ ShadowProbe.probe()            → TCP → device
    ├ write run_node / run_command
    └ parse the output variables (take the fields from the driver's raw response)
```

---

## 10. Frontend structure

```
webapp/src/
├── api/client.ts       apiFetch (unified 401 handling)
├── pages/
│   ├── Island.tsx            island view: place devices by drag, transport sequences, position visualisation
│   ├── IslandFlow.tsx        island orchestration: device links = plate moves
│   ├── ExperimentCanvas.tsx  experiment canvas: cross-workflow orchestration (start / end / workflow, React Flow)
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
| Node | Start / end / workflow (cross-workflow orchestration) | Device only |
| Arrow | Workflow order | The plate is moved there |
| Purpose | In what workflow order the experiment runs | How the logistics move around the island |

---

## 11. Deployment topologies

### Now (single machine)

```
one machine: kernel (Spring Boot container) + frontend + PostgreSQL
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

### Build and start

```bash
./scripts/build-kernel.sh              # fetch Maven dependencies + compile main/boot + copy resources
./scripts/build-kernel.sh --verify     # additionally run the transaction and mapper self-tests
./scripts/start-kernel.sh              # start: Spring Boot container + kernel on 18080
```

**Why not `mvn package` in one go**: the existing gates and e2e scripts all work against the
`out/` directory and bare `javac`; switching to the Maven lifecycle would at the same time change
the start-up script, the restart command in the crash-recovery section and the classpath of every
self-test. **Maven only fetches dependencies**, compilation still uses `javac`, and the artefacts
still land in `out/`.

Dependencies land in `labos-kernel/lib/spring/` (gitignored, no binaries committed).

---

## 12. Module dependency direction

```
src/boot/   ──→  src/main/          (the container layer depends on the domain layer, never the reverse)

Inside the domain layer:
  api  →  workflow  →  engine  →  driver
         ↓            ↓          ↓
      recovery      alert     persistence
```

**Reverse dependencies are forbidden** (`driver` does not depend on `workflow`).

`PositionLock` sits in `driver/` but is strongly business-oriented — **this is one flaw in
the current structure**; it can be raised at review: should the position lock belong to
`workflow`, or become its own `plate/` package?
