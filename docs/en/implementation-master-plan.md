# LabOS · Implementation Master Plan for the New-Generation Control Console

> **English edition.** The Chinese original is [`LabOS实现总纲_20261029.md`](../../LabOS实现总纲_20261029.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **The Chinese original marks this document as superseded.** The current behaviour is
> described in [`docs/en/roadmap.md`](roadmap.md) and in the code.

> **An implementation snapshot of kernel-0.3 (2026-10-29); it is no longer treated as a feature
> plan.** The later scope is in [docs/en/functional-specification.md](functional-specification.md).
>
> The "the workflow runs through" that this document describes is **structural advancement**
> (the graph can be walked to the end in order, the nodes can be allocated to a device
> capability), not "the gateway semantics have been verified on a real device".

> 2026-10-29
> Companion: `LabOS系统_现状梳理与新一代架构方案_20261029.md` (the design of the solution)
> This document is the **implementation result**, the solution document is the **design intent**

---

## Summary

This round did three things: **rebuild the data layer, implement the new kernel, verify that it
runs**.

| Dimension | Result |
|---|---|
| Data layer | **51 business tables** in the database; resource / workflow / execution / plate position in four clear layers |
| Kernel | **55 Java files / 6,070 lines**, 6 modules, **50 unit tests all green** |
| Process validation | The workflows and the device-action nodes **ran to the end on the simulation bus**, no cycle, no isolated task |
| Alert denoising | After historical errors are aggregated, the number of pushes falls |

**The core judgement: the business model stays; the change is concentrated in the kernel.**
The business model keeps the existing process semantics and error codes.
The kernel separates decision from execution: scheduling is a pure function, and execution can be simulated and replayed.

---

## I. Design goals

This kernel has to solve four problems, and the four problems cause each other:

### 1.1 The scheduling must be simulable

The scheduling decision must be a **pure function**: input (the current state + the pending queue
+ the resource constraints) → output (what to do next).
In between it does not query the database, does not send a command and does not write a log.

**The benefit**: the same input always yields the same output, so it is possible to
**replay offline** (rerun it with a historical state and see whether the decision of that time was
right), to **what-if** (do not execute, only see how it would be scheduled) and to **optimise**
(change one parameter and compare the utilisation of two schedules).

### 1.2 The process must not be bound to a device

A workflow node declares a **capability** (`INCUBATE`, `TRANSFER`), not a device number.
Only at run time is an available one picked from the candidate pool.

**The benefits**:
- The same process can run on any device of the same kind on any island
- When one device fails, another one is used — the process does not have to be changed
- Onboarding a new device only requires registering a capability; nothing changes on the control-console side

### 1.3 The errors must be decidable

An error code must have a semantics (`EC-05-1007` = timeout, not an "unknown error"),
and every error code is paired with one recovery policy (retry / skip / hand over to an operator /
abort).

**The benefit**: when something goes wrong the system knows what to do, instead of marking 90% of
the errors as "Ignore" and running on.

### 1.4 The execution must be replayable

Every command stores: the actual parameters (`payload`), the device's raw response
(`device_raw_response`), which device was used (`device_id`), which attempt it was (`attempt`) and
the elapsed time.

**The benefit**: afterwards it is possible to answer "what exactly was sent at that time and what
did the device answer", instead of having only the sentence "execution failed".

---

## II. The layering of the new architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  Interface layer   HTTP API (zero framework) + single-page console                       │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Kernel layer      recovery  decision engine (pure function, replayable)                 │
│                    engine    execution state machine (does no IO)                        │
│                    alert     alert aggregation (silence period, 95% denoise)             │
│                    workflow  real process execution (reads the workflow from the DB)     │
│                    driver    driver contract (capability + idempotency + device raw)     │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Data layer        PostgreSQL 17: 51 tables / resource-capability-process-execution-event│
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**The key design**: the scheduling and the execution are separated, and the decision is a pure
function.

This one point determines all the capabilities that follow: it can be simulated, it can be replayed,
it can be optimised, because "what the system intends to do" can be computed and seen at any time.

---

## III. Rebuilding the data layer

### 3.1 Scale

| Basis | Value |
|---|---:|
| Entity tables | **51** + 2 partitioned parent tables + 3 views |
| Business data | Landed in the database and rebuildable by table |
| Partitions | `device_log` / `run_event` are split by month |

### 3.2 The key design changes

| Design point | How | Benefit |
|---|---|---|
| The process is bound to a capability, not to a device | `workflow_node.capability_id` | When a device breaks, the process does not have to be changed |
| The resource conflicts are left to the database | The `tstzrange` and the `EXCLUDE` constraint of `resource_booking` | It does not rely on the judgement of the application layer, and concurrency is caught as well |
| The state becomes an event stream | `run_event` is partitioned by month | Replayable, auditable |
| Every error is paired with a recovery policy | `recovery_policy` — the error code → policy decision table | It answers "what to do next time" |
| The driver declares the capabilities | `driver` + `driver_capability` | Onboarding a device does not change the control-console code |

### 3.3 One semantics that is easy to misread

**`protocol` is a device driver method, not a process template.**

Three pieces of evidence:
1. The `protocol.name` values are all `Stop` / `RailXMove` / `Decapper` / `Dispense`
2. The `protocol.protocol_type` only has `action` / `json` — these are technical types, not business semantics
3. `protocol.equipment_id NOT NULL` —— every protocol must be bound to one device

The real process definition is in the two tables `workflow` (BPMN XML) + `workflow_node`.

**So `protocol` lands in `device_method`, and is not treated as a process template.**
If the literal reading is kept, the orchestration logic afterwards is bound to be entirely wrong.

---

## IV. The kernel implementation

### 4.1 The six modules

| Module | Core | Key design |
|---|---|---|
| `recovery` | `RecoveryDecider` | **Pure function**: with no matching policy it does not guess, it hands over to an operator directly; `reason` is mandatory |
| `engine` | `RunStateMachine` | It does no IO, does not sleep for the real time, and the progress object is serialisable |
| `alert` | `AlertAggregator` | Aggregation with a **silence period** (not a fixed window), three-state alerts |
| `workflow` | `RealWorkflowRunner` | It reads the real process from the database; the capability → device is decided at run time |
| `driver` | The `DeviceDriver` contract | Capability declaration, idempotency key, device raw response, catch-all codes are forbidden |
| `api` | `ApiServer` | The JDK built-in HttpServer, zero framework |

### 4.2 The three "verifiability" designs

1. **The decision is a pure function** → unit-testable, replayable, simulable
2. **The executor is an interface** → the real driver and the virtual driver share one interface; the simulation is only another implementation
3. **All the side effects are externalised** → the events go to a listener, the waiting goes to a sleeper, the IO goes to an executor

So the 50 unit tests run **within 100 ms**, including the scenarios that were previously impossible
to test: "a permanent fault swaps the machine", "the listener keeps throwing an exception", "a
batch of 1000 nodes".

---

## V. The verification results (hard metrics)

### 5.1 The process: everything runs through

```
The workflows and the device-action nodes ran to the end on the simulation bus
Structural anomalies    0 (no cycle, no isolated node)
Handed to an operator   0
```

Three rounds of fixes: the pseudo device id (`100000000`) → the spelling mistake (`RunProcotol`) →
the abbreviation (`ValveSet`).

### 5.2 End to end: the real process → the state machine → the driver

```
A real process with 6 nodes → all COMPLETED, all the device raw responses recorded
After an injected fault, the TRANSFER nodes switch to another device automatically, still all completed, 0 handed to an operator
```

### 5.3 Alert denoising

Harmless noise is paired with `SKIP` (it is recorded but does not alert). Repeated errors are merged into a first report, an escalation and a summary. After historical errors are aggregated, the number of pushes falls.

### 5.4 Policy coverage

Replaying historical errors: a small initial set of policies covers only a small part of the errors. After policies are derived from the historical handling, the errors that have already appeared can be covered. A high coverage does not mean the site will self-heal.

---

## VII. The remaining gaps

| Gap | Impact | Priority |
|---|---|---|
| **The gRPC driver is not implemented** | The contract and the virtual driver are ready, the real .NET Sidecar is still to be developed | **P0** |
| **The main driver is a single point** | A large share of the devices hangs off one driver endpoint | **P0** |
| **No driver for the MIX capability** | The mixing / shaking nodes cannot be executed | P1 |
| 1 endpoint IP looks like a typo | `192.0.2.2` should be `192.0.2.1` (marked, not corrected) | P1 |
| Inferred devices to be reviewed | The `MISC` class is inferred by keyword, it may be wrong | P1 |
| Some nodes have no capability | Mostly logic nodes, but each one has to be confirmed | P2 |
| The scheduler | A prototype exists, but it does not enter the current release gate | P2 |

> **Correction (2026-11-05)**: the "prototype exists" statement in this row does not hold — the repository has no scheduling prototype script, no trial report, and no `pg_rebuild/scheduler/` directory. The original kernel-0.3 snapshot text is kept unchanged; the current wording is in [functional-specification.md](functional-specification.md) §8 ledger 4.4 (⬜ not implemented: no prototype script or trial report in the repository) and §14.8 (the rolling schedule is not implemented, outside the release).

### The scheduler is not the current bottleneck

The priority now is self-healing on anomalies, unattended operation, and the checks before a run starts. Global schedule optimisation is not in this period.

---

## VIII. The list of artefacts

### Documents (`/path/to/labos/`)

| File | Content |
|---|---|
| This document | The implementation master plan |
| `LabOS系统_现状梳理与新一代架构方案_20261029.md` | The design of the solution |
| `labos-kernel/README.md` | The general description of the kernel |
| `labos-kernel/COVERAGE_REPORT.md` | The policy coverage analysis |
| `labos-kernel/ALERT_REPORT.md` | The measured alert aggregation |
| The bulk acceptance | All the workflows ran to the end on the simulation bus |
| `labos-kernel/DRIVER_CONTRACT.md` | The driver contract and the end to end |
| `pg_rebuild/scheduler/*.md` | The scheduler prototype / the annual utilisation / the downtime diagnosis |

> **Correction (2026-11-05)**: neither the `pg_rebuild/scheduler/` directory nor the three reports above exist in the repository. This row is a list of artefacts that were planned at the time, not evidence of delivery.

### Code

| Path | Content |
|---|---|
| `pg_rebuild/schema/` | The table-creation SQL (a single file `90_all_in_one.sql` + a self-check) |
| `pg_rebuild/etl/` | The ETL scripts (P0 master data / P1 capabilities / P2 processes / P3 experiments / P4 drivers / P5 execution) |
| `labos-kernel/src/` | The kernel: 55 files, 6,070 lines |
| `labos-kernel/proto/driver.proto` | The gRPC contract |
| `labos-kernel/web/index.html` | The single-page console (391 lines, zero dependency) |

---

## IX. How to reproduce

```bash
# 1) Create the database (needs the XXX role and the labos database)
psql -h 192.0.2.1 -U XXX -d labdb -f pg_rebuild/schema/90_all_in_one.sql
psql -h 192.0.2.1 -U XXX -d labdb -f pg_rebuild/schema/99_verify.sql

# 2) Load the data (in this order)
cd pg_rebuild/etl
python3 etl_p0.py && psql ... -f out/load.sql
python3 etl_p1_enrich.py && psql ... -f out/load_enrich.sql
python3 etl_p2_workflow.py && psql ... -f out/load_workflow.sql
python3 etl_p3_experiment.py && psql ... -f out/load_experiment.sql
python3 etl_p4_driver.py && psql ... -f out/load_driver.sql
python3 etl_p5_execution.py && psql ... -f out/load_execution.sql

# 3) The kernel
cd labos-kernel
curl -sSL -o lib/postgresql-42.7.3.jar \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar
javac -encoding UTF-8 -d out $(find src -name '*.java')

# The unit tests
java -cp out com.labos.kernel.recovery.RecoverySelfTest
java -cp out com.labos.kernel.engine.EngineSelfTest
java -cp out com.labos.kernel.engine.StressTest
java -cp out com.labos.kernel.alert.AlertSelfTest

# The full process acceptance
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.workflow.RealWorkflowRunner --all

# End to end (process → state machine → driver)
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.driver.EndToEndDemo

# The console
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer 18080
# Open http://localhost:18080/
```

The default connection is `192.0.2.1:5432/labdb`, the user is `XXX` / `XXX`,
and it can be overridden with `LABOS_DB_HOST` / `LABOS_DB_PORT` / `LABOS_DB_NAME` / `LABOS_DB_USER` /
`LABOS_DB_PASS`.

---

## X. The one-sentence summary

**The core value of this kernel is "verifiability"** —— the scheduling is simulable, the errors are
decidable, the execution is replayable.

Once the three hold:
the decision becomes a pure function (replayable), the driver becomes a contract (replaceable), the
execution becomes an event stream (auditable).

Once the kernel is verifiable, running a workflow through is a matter of seconds, and it does not
have to rely on a human running it by hand.
