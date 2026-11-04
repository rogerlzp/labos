# Technical Document · LabOS Laboratory Control Console

> **English edition.** The Chinese original is
> [`docs/评审包-20261103/02-技术文档.md`](../../评审包-20261103/02-技术文档.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Audience: the people who read / review the code
> Companions: [Functional document](01-functional.md) · [Design document](03-design.md) · [Testing document](04-testing.md)
> For the finer package structure and class responsibilities see the historical document `docs/技术架构与模块地图.md` (this document covers the main line and does not enumerate every class)

---

## 1. Overall architecture

```
┌────────────────────────────────────────────────────────────────┐
│ Frontend  webapp (React 18 + antd 5.29 + react-query + bpmn-js)│
│  19 pages: workflow library / designer / experiment / canvas / │
│  run detail / paused queue / …   ·   8,393 lines TS/TSX        │
└───────────────┬────────────────────────────────────────────────┘
                │ HTTP + Bearer token (18080)
┌───────────────▼────────────────────────────────────────────────┐
│ API layer  api.ApiServer (routing/auth/audit) · QueryService   │
│          AuthService (login/token)                             │
└───────────────┬────────────────────────────────────────────────┘
                │ direct call (same process)
┌───────────────▼────────────────────────────────────────────────┐
│ Business layer  workflow.*                                     │
│  WorkflowService (orchestration and wrap-up of one execution)  │
│  GraphRunner (advance the BPMN graph: parallel/branch/loop)    │
│  PreflightChecker (check first) · PauseManager (pause/resume)  │
│  ExperimentExecutor / ExperimentScheduler (experiment queueing)│
│  StartupRecovery (orphans after a crash) · RunMonitor (live)   │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ Execution layer  engine.*                                      │
│  RunStateMachine (state machine for retry/back-off/decisions)  │
│  ConditionEvaluator (condition expressions, own parser)        │
│  NodeStatus / RunProgress / event stream                       │
└───────────────┬────────────────────────────────────────────────┘
                │ DriverStepExecutor
┌───────────────▼────────────────────────────────────────────────┐
│ Driver layer  driver.*                                         │
│  DriverRegistry (who has a driver) · DeviceLock/PositionLock   │
│  WaitForGraph (wait graph decides deadlock) · HeartbeatService │
│  TcpDriverAdapter (TCP protocol) · ShadowProbe/ShadowRunner    │
└───────────────┬────────────────────────────────────────────────┘
                │ TCP 18501/18502 (custom frame protocol)
┌───────────────▼────────────────────────────────────────────────┐
│ sidecar  sidecar-dotnet/Labos.Sidecar (.NET 10) — sim transport│
│  translates the console's JSON frames into the vendor protocol │
└───────────────┬────────────────────────────────────────────────┘
                │ TCP 19601/19602
┌───────────────▼────────────────────────────────────────────────┐
│ Simulator  sim.DeviceSimulator (S100 / ASpin 2K behaviour)     │
└────────────────────────────────────────────────────────────────┘
```

**Why there is a sidecar layer in the middle**: the device vendors' protocols come as C/C++/C# SDKs, and pushing them straight into the JVM means either JNI or a rewrite. The sidecar reuses the vendor SDK in .NET, and the control console only ever speaks one stable JSON frame protocol. The price is one extra hop (about 1–3 ms of latency, acceptable).

---

## 2. How the code is organised

### 2.1 Backend packages (`labos-kernel/src/main/java/com/labos/kernel/`, 29,671 lines)

| Package | Files | Responsibility | Key classes |
|---|---|---|---|
| `driver` | 23 | Device drivers, concurrency locks, the wait graph, heartbeat, shadow probe | `DriverRegistry`、`DeviceLock`、`PositionLock`、`WaitForGraph`、`DriverStepExecutor`、`HeartbeatService`、`TcpDriverAdapter` |
| `workflow` | 20 | Orchestration and execution of workflows / experiments, preflight, pause & resume, crash recovery | `WorkflowService`、`GraphRunner`、`PreflightChecker`、`PauseManager`、`ExperimentExecutor`、`StartupRecovery` |
| `engine` | 17 | Execution state machine, condition evaluation, events and progress | `RunStateMachine`、`ConditionEvaluator`、`NodeStatus`、`RunProgress` |
| `recovery` | 11 | Error recovery policy: matching, decision, back-off | `RecoveryPolicyResolver`、`RecoveryDecider`、`BackoffCalculator` |
| `persistence` | 9 | JDBC wrapper, event storage, policy repository | `DbConfig`、`JdbcEventStore`、`JdbcRecoveryPolicyRepository` |
| `sim` | 7 | Device simulator (real protocol, fake device) | `DeviceSimulator`、`S100Behavior`、`AspinBehavior` |
| `device` | 6 | Device model library, error-code mapping, onboarding | `DeviceModels`、`DeviceRegistrar`、`DeviceErrorMapper` |
| `expr` | 5 | **Parameter template substitution**: evaluating `${var}` inside the experiment parameters | `Evaluator`、`Expression`、`Template` |
| `alert` | 5 | Alert aggregation and handling | `AlertService` |
| `api` | 3 | HTTP entry point | `ApiServer` (about 2,700 lines, all the routes are here)、`QueryService`、`AuthService` |
| `redis` | 1 | Reserved | — |

> The largest single file is `ApiServer` (routing + auth + audit + some business glue). **This is known technical debt**: it should be split into a controller layer, but there is no framework here, the HTTP dispatch is hand-written, and splitting it does not pay off yet (see the design document §5).

### 2.2 Frontend (`webapp/src`, 8,393 lines)

| Directory | Contents |
|---|---|
| `pages/` | 19 pages, one file per page (`Experiments.tsx` is the largest; it holds create / orchestrate / execute / history) |
| `components/` | `RunGraph.tsx` (bpmn-js Viewer + status colouring), the timeline, etc. |
| `api/client.ts` | All the API wrappers + `apiFetch` (carries the token automatically, raises an event on 401) |

Technology choices: `package.json` declares React 18.3 / Vite 5.4 / antd 5.29 / react-router 6.27 / `@tanstack/react-query` 5.59 / `bpmn-js` 17.11; `package-lock.json` actually installs react-router **6.30.6** and react-query **5.105.0** (the declarations are lower bounds, the installed versions win).

### 2.3 Everything else

| Directory | Contents |
|---|---|
| `sidecar-dotnet/Labos.Sidecar` | The .NET 10 sidecar (`net10.0`), `SimBridgeTransport` (the sim forwarder, with reconnection) |
| `pg_rebuild/schema` | Schema scripts (`20_kernel.sql`、`90_all_in_one.sql`、`95_seed_data.sql`) |
| `scripts/` | `check-schema-sync.sh` (consistency of the schema artefacts)、`test-all.sh` (379 pure-logic cases / 397 with the database)、`verify-device-chain.sh` (41)、`verify-parallel-flow.py` (4)、`verify-parallel-experiments.py` (3)、`verify-m1/m3/m4/m5.sh`、`load-test.sh` |

---

## 3. Data model (the core tables)

```
workflow (the flowchart)                device (model library / device pool)
  ├─ workflow_node (node)                     ├─ device_method (method, the UI reads this table)
  │    resource_id / seq: device binding and in-graph order (persisted from the XML, the read side
  │       does not parse the graph; filled in by the M9 prerequisite, the existing rows are backfilled with BindingBackfill)
  │    └─ workflow_node_step                  └─ device_instance (instance, attached to an island)
  └─ workflow_graph_history (version)

experiment (experiment)
  ├─ experiment_process (seq → workflow, loop_count)
  └─ experiment_run (one execution of the experiment)
       └─ experiment_run_step (seq step × loop iteration → run_id)

run (one execution, run_type = workflow|experiment)
  ├─ run_node (level=0 business node / level=1 step)
  ├─ run_command (one command + the raw response device_raw_response)
  └─ run_event (event stream, partitioned monthly)

error_code (error code) ── recovery_policy (recovery policy, one per scope)
device_state (online / heartbeat) ── alert_state (alert, unique by fingerprint)
plate / plate_location_audit / position_state  (plate-position-level mutual exclusion)
audit_log (who changed what)
```

**Key constraints (the ones to look at hard during the review)**

| Table | Constraint | Why it matters |
|---|---|---|
| `run_node` | Unique key `(run_id, node_key, attempt, loop_index)` | A repeated insert on resume hits the key → the whole job fails (fixed, see design document D4); `loop_index` gives every loop iteration its own row (M6) |
| `error_code` | Unique key `(tenant_id, code)`; `scope` decides the recovery layer | A scope configured wrongly = the policy does not take effect |
| `recovery_policy` | One row per `(error_code_id, scope)` | Of two policies with the same code and scope only one is used (`findFirst`) |
| `alert_state` | Unique key `(tenant_id, fingerprint)` | If the upsert is written as an insert, every heartbeat reports a duplicate key (fixed) |
| `device_method` | Unique key `(device_id, method)` | The onboarding upserts on this key (fixed: it did not write at all before) |

**Enumerations**: `node_status = {pending, ready, running, completed, failed, skipped, aborted, manual, bypassed}`; `run_status`, `recovery_strategy = {retry, reassign, skip, abort, manual}`, `recovery_scope = {command, node, run, device}`.

---

## 4. What happens during one execution (the main chain)

Taking "workflow library → Execute" as the example (the experiment path adds one layer, `ExperimentExecutor`; everything after step 5 is the same):

```
1. POST /api/workflows/{id}/run?async=1[&vars=k=v]
   ApiServer → WorkflowService.startAsync()
   → createRun()  writes the run row (status=running), writes input.vars

2. Background thread execute(workflowId, mode, runId)
   → loads workflow + workflow_node
   → createNodeShells()  creates a run_node for **every** business node (status=running)
        ⚠️ this must be idempotent: a resume comes in here a second time (D4)

3. If workflow.graph->>'xml' has content → GraphRunner.run(graph, executor)
   → otherwise it degrades to sequential execution (the data in the database)

4. GraphRunner main loop:
   - take a node from the ready queue → decide the type
   - task node: RunStateMachine.executeNode(...)
        → DriverStepExecutor: pick the device (the bound instance first, otherwise rotate by capability)
        → DriverRegistry finds the driver → TCP to the sidecar → the device replies
        → on failure, look up recovery_policy by scope and decide retry / reassign / manual / abort
   - gateway: SPLIT_PARALLEL activates all the outgoing edges; SPLIT_XOR uses ConditionEvaluator to pick one
   - after each node succeeds: merge the fields of the device response into the variable table (the loop relies on it to exit)
   - boundary checks: pause request, iteration cap, total step cap

5. Wrap-up (WorkflowService)
   - updateNodeResults()  writes back the final status + error_code
   - markBypassedNodes()  when the whole job succeeded, marks the nodes that are still running as bypassed
   - settleRun()  finalises the run status
   - markPlatePosition()  records which device the plate finally came to rest on
```

**How the written-back state maps onto the display** (review focus #7):

| In the database | Colour on the graph | Explanation |
|---|---|---|
| `completed` | Green | |
| `failed` / `aborted` | Red | |
| `manual` | Orange | Handed over to an operator |
| `skipped` | Yellow | Skipped by policy, the plate position is not trustworthy |
| `bypassed` | Light grey dotted | The branch was not selected |
| `running` (the job has stopped) | Grey dashed (counted as pending) | **It must not be shown as "running"** |

---

## 5. Key mechanisms

### 5.1 Retry and back-off

`RunStateMachine` calls `RecoveryDecider` when something fails:

1. Look up the policy by `errorCode + scope + context` (`RecoveryPolicyResolver`, ordered by `specificity → priority`, `findFirst`)
2. Decide `retry / reassign / skip / abort / manual` according to the policy
3. `retry` computes the delay with `BackoffCalculator` (linear / exponential + jitter), and `Sleeper` really sleeps

> ⚠️ A historical pitfall: the production path once used `Sleeper.recording()` (records but does not sleep) → "retry 3 times" was actually used up in 50 ms. Production now uses `Sleeper.REAL`; only `LABOS_FAST_RECOVERY=1` switches to the recording mode.

### 5.2 Pause and resume-from-breakpoint

- **Pause**: `pauseChecker` checks at the **node boundary** (it does not stop in the middle of an action — the robot arm would drop the plate)
- **Resume**: `resumeAsync()` reads the node_keys that are already `completed` → deletes the `run_node`s of the unfinished nodes (the commands cascade) → sets the run to running → runs again with the same runId, and `GraphRunner.withSkipKeys` skips the completed ones
- **Crash recovery**: `StartupRecovery` collects the leftover `running` rows at process start into `failed` + `input.recoverable=true` + `interruptedAt`; the UI only lists the last 7 days, and they can be `dismiss`ed

### 5.3 Concurrency and deadlock

| Lock | Granularity | What it solves |
|---|---|---|
| `DeviceLock` | A single device | Two workflows competing for the same device |
| `PositionLock` | A single plate position | The robot arm puts the plate back into a position that is being occupied |
| `IslandLock` | A whole island (optionally `exclusive=1`) | Exclusive access during maintenance |
| `WaitForGraph` | Global | Detection of circular resource waits; the request is refused as soon as a cycle is found |

### 5.4 Heartbeat and the online state

`HeartbeatService` probes once every `LABOS_HEARTBEAT_INTERVAL_MS` (default 10s); beyond `max(3×interval, 30s)` the device counts as offline, which writes `device_state.online` + opens/closes `alert_state` (`ON CONFLICT DO UPDATE`, idempotent).

### 5.5 Audit

Write operations (`run.start`、`error_code.update`、`run.dismiss`…) all go through `audited(...)` and land in `audit_log`. **Read endpoints are not audited**.

---

## 6. API overview (grouped by domain)

> Everything is prefixed with `/api`; everything except the login needs `Authorization: Bearer <token>`. `ApiServer` holds **53 route patterns**; the main ones are below.

| Domain | Endpoints |
|---|---|
| Authentication | `POST /auth/login` |
| Workflows | `GET /workflows`、`POST /workflows` (create a blank workflow)、`GET /workflows/{id}` (nodes + capabilities)、`GET|PUT /workflows/{id}/graph` (BPMN XML)、`GET .../compile`、`GET .../preflight`、`GET .../predict`、`GET .../history`、`POST .../history/{h}/restore`、`POST .../run`、`POST .../shadow-run`、`GET .../runs` (run history)、`GET .../eta` (estimated wait)、`GET .../timeline` (the time bar of every step) |
| Experiments | `GET|POST /experiments`、`POST /experiments/reorder` (manual queue-jumping, writes priority)、`PUT /experiments/{id}/processes`、`GET|PUT /experiments/{id}/variables`、`POST /experiments/{id}/run`、`GET /experiments/{id}/runs`、`GET /experiments/runs/{runId}/steps`、`GET|PUT /experiments/{id}/graph` |
| Execution | `GET /runs?limit=`、`GET /runs/{id}`、`GET /runs/{id}/timeline`、`GET /runs/{id}/graph` (BPMN + status)、`POST /runs/{id}/pause|resume|dismiss`、`POST /runs/dismiss` (batch)、`GET /runs/paused`、`GET /runs/recoverable`、`POST /runs/{id}/attendance` |
| Devices | `GET /device-models`、`POST /device-models/{id}/deploy`、`GET|POST /devices`、`GET|PUT|DELETE /devices/{id}`、`GET /devices/{id}/methods`、`PUT /devices/{id}/island|position`、`GET /devices/pool|unassigned`、`POST /devices/{id}/clone` |
| Islands | `GET|POST /islands`、`DELETE /islands/{id}`、`GET|PUT /islands/{id}/robot-points[/{code}]`、`POST .../sync`、`POST .../capture`、`/robot-points.csv` |
| Errors and policies | `GET /error-codes`、`PATCH /error-codes/{id}`、`POST|PATCH|DELETE /error-codes/{id}/policies[/{pid}]`、`GET|PUT /policies`、`POST /policies/validate`、`GET /errors` (error details, including the trace of the skipped ones) |
| Observability | `GET /process-status`、`GET /alerts`、`POST /alerts/{fp}/ack|close|silence|reopen`、`GET /audit`、`GET /durations`、`GET /heartbeat`、`GET /topology`、`GET /scheduler/status`、`GET /positions`、`POST /plates/{barcode}/locate` |
| Other | `GET /choreographies`、`POST /choreography` (transport sequence → workflow)、`POST /shadow/probe`、`POST /demo/run`、`GET|POST /users` |

**Uniform response**: success `200` + JSON; business error `4xx` + `{"error": "..."}`; not logged in `401` (the frontend redirects to the login on that basis).

---

## 7. Deployment and operations

| Item | Value / practice |
|---|---|
| Ports | API 18080, frontend 5173 (dev), sidecar 18501/18502, simulator 19601/19602 |
| Configuration | Everything goes through environment variables (`LABOS_DB_*`、`LABOS_HEARTBEAT_INTERVAL_MS`、`LABOS_OFFLINE_ALERT_MS`、`LABOS_FAST_RECOVERY`) |
| Logs | stdout (redirected to `/tmp/labos-api.log`); the driver / heartbeat logs are voluminous, filter them by level |
| Backup | The usual PostgreSQL backups; `run_event` is partitioned monthly, a whole partition can be archived |
| Health check | `GET /api/process-status` (if it answers, the process is alive); database connectivity is judged from the startup log |

**Known operational traps**

1. Restarting the API invalidates every token (the tokens are held in memory), so the pages need a new login.
2. The acceptance script `verify-device-chain.sh` uses random ports and **restores the driver endpoints** to 18501/18502 at the end; if you changed an endpoint by hand, the script overwrites it when it finishes.
3. `run_event` and `run_command` grow fast (already 110,000+), a long-term archiving policy is needed.

---

## 8. Performance and capacity (measured, not extrapolated)

| Scenario | Data | Source |
|---|---|---|
| A single workflow with 1 node | about 1s (including 1 device command) | run detail |
| A single workflow with 3 nodes | 1–2s | same |
| 2 parallel branches | comparable to the serial case (logical parallelism) | same |
| A loop of 10 iterations | about 10× a single run | same |
| Multiple experiments in parallel | see `docs/压测报告-多实验并行.md` | single-machine simulator, not a real device |

**Do not extrapolate**: the simulator has neither the mechanical travel time of a real device nor network jitter.
