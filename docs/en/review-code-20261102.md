# Code Review Package · 2026-11-02

> **English edition.** The Chinese original is [`docs/评审包-代码-20261102.md`](../评审包-代码-20261102.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **The entry point for the code reviewer.** The previous review package (`评审包-20261102.md`) was aimed at **design / process**;
> this package is aimed at **the code itself**: entry point, module map, the mapping from design decisions to code, a checklist, reproduction commands, disclosed risks.
>
> **The conclusion first**: the backend runs, 104 unit tests + 45 acceptance checks are all green, **but all of it is on the simulator**;
> the code has never had an independent **logic-level** code review — the previous round `20261101-01-grok.md` was "verifying the document claims item by item against the source code",
> and after that M1-M5 added about 2000 lines of new code; **that new code has had zero review**.

---

## 1. Review scope and size

| Component | Path | Files | Lines | Priority |
|---|---|---:|---:|---|
| Kernel main code | `labos-kernel/src/main/java` | 96 | 22580 | 🔴 high |
| Kernel tests | `labos-kernel/src/test/java` | 7 | 1485 | medium |
| Gateway | `labos-gateway/src` | 3 | 794 | medium |
| .NET Sidecar | `labos-kernel/sidecar-dotnet` | 8 | 1018 | medium |
| Frontend | `webapp/src` | 21 | 6598 | medium |
| Database scripts | `pg_rebuild/schema` | 9 SQL | 4803 | high |
| Verification scripts | `scripts/` | 6 | 1065 | medium |

**Version**: commit `d42778d` (after this round's renumbering + the review-record append).

---

## 2. Module map (kernel)

```
com.labos.kernel
├── driver/      device drivers and concurrency resources   ← 🔴 highest risk: device lock / position lock / wait-for graph / transport
│   ├── DeviceLock / DevicePortLock / DeviceAccessManager
│   ├── PositionLock                  position lock (atomic reservation + three plate-position states)
│   ├── WaitForGraph                  wait-for graph deadlock detection (D109)
│   ├── TransferPlan                  transport plan + return position (D105)
│   ├── DriverStepExecutor            action execution + lock request/release
│   ├── HeartbeatService              heartbeat + orphan lock cleanup (D103)
│   ├── DriverRegistry / DeviceDriver / VirtualDriver / Shadow*
│   └── tcp/                          TcpDriverAdapter / TcpPersistentDriver / MockDriverServer
├── workflow/    workflow · experiment · scheduling         ← 🔴 high risk
│   ├── ExperimentScheduler           experiment scheduling (EDD / priority / FIFO + island lock)
│   ├── AdmissionController           start admission (main-chain coarse screening, D114)
│   ├── MainPathSelector              main-path selection (the branch with the heaviest resource use)
│   ├── PauseManager                  pause / resume / fault handling (D110-D113)
│   ├── StartupRecovery               startup wrap-up
│   ├── PreflightChecker              pre-flight before execution
│   ├── GraphRunner / BpmnGraphParser / BpmnExpander / WorkflowGraphParser
│   ├── ExperimentFlowRunner / ExperimentExecutor / WorkflowService (two execution paths)
│   └── RunResultService              async result injection (D119)
├── engine/      state machine · node execution · main-line routing
├── expr/        expression engine (self-written parser, D117)
├── recovery/    crash-recovery decisions
├── alert/       alert aggregation
├── persistence/ JDBC storage
├── api/         ApiServer / AuthService / QueryService
├── redis/       RedisClient
└── sim/         DeviceSimulator
```

---

## 3. Design decision → code mapping (to check "does the code do what the document says")

| Id | Decision | Implementing class |
|---|---|---|
| D101 | Device lock (replaces the island lock) | `DeviceLock`, `DeviceAccessManager` |
| D102 | The robot arm as a transient resource (step-level lock) | `DeviceLock`, `DriverStepExecutor` |
| D103 | Orphan locks: heartbeat cleanup | `HeartbeatService` |
| D104 | Variable isolation ThreadLocal + finally cleanup | `WorkflowService`/`ExperimentFlowRunner` variable substitution |
| D105 | Return position `returnTo` + double lock | `TransferPlan` |
| D106 | Duration estimate inaccurate → rolling recalculation + conservative TTL | `TransferPlan`, TTL parameter |
| D107 | Look-ahead (pre-staging the following plates) | **not implemented** |
| D109 | Three-layer resource model + wait-for graph deadlock detection | `WaitForGraph`, `DeviceAccessManager` |
| D110 | A pause is only allowed at an action boundary | `PauseManager` |
| D111 | A fault does not stop everything; look at the dependencies | `PauseManager`, `HeartbeatService` |
| D112 | Three states for the plate position KNOWN/UNKNOWN/LOST | `PositionLock` |
| D113 | The manual-intervention state machine | `PauseManager` |
| D114 | Main-chain coarse screening + wait-for graph fine pipe (hybrid) | `MainPathSelector`, `AdmissionController` |
| D115 | Position selection strategy | `RecipeCompiler`, `AdmissionController` |
| D117 | Separating numbering from access order; a self-written expression parser | `expr/` |
| D118 | Script node sandbox | **not implemented** |
| D119 | Async result query → result table + variable injection | `RunResultService`, `expr/` |

---

## 4. Reproduction commands

```bash
# Compile (JDK 17, no framework dependencies)
cd labos-kernel && javac -encoding UTF-8 -d out $(find src -name '*.java')

# Unit tests (the ones that need no database)
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.expr.ExpressionSelfTest
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.driver.WaitForGraphSelfTest
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.driver.TransferPlanSelfTest
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.workflow.PauseManagerSelfTest

# End-to-end acceptance (needs a database + the simulator)
./scripts/sim-up.sh LAB-DEMO-02 100
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer 18080
./scripts/verify-m1.sh && ./scripts/verify-m3.sh && ./scripts/verify-m4.sh && ./scripts/verify-m5.sh
```

---

## 5. Review checklist (suggested to tick it category by category)

| # | Dimension | What to check specifically |
|---|---|---|
| C1 | **Concurrency correctness** | Whether the lock request order is a fixed total order; whether there is any "hold and wait"; the race between TTL expiry and release; whether the wait-for graph's cycle decision is complete (self-loop / multiple cycles / false cycle) |
| C2 | **Transaction boundary** | Whether `commit()` is in the right place; whether swallowing exceptions (`catch (...) {}`) causes silent failures; whether, after a write failure, the following statements in the same transaction are rolled back as well |
| C3 | **Return-value checks** | Whether `executeUpdate()` returning 0 rows is treated as success; the return-value semantics of `putPlate`/`notePlateMove`/`tryReserve` |
| C4 | **Resource leaks** | Whether the JDBC Connection/Statement use try-with-resources; whether the ThreadLocal is cleaned up in a finally; whether the thread pool is shut down |
| C5 | **Idempotency and recovery** | The implementation of the idempotency key; whether the restart wrap-up `StartupRecovery` covers every intermediate state; whether the state machine has unreachable / dangling states |
| C6 | **Security** | Whether the SQL is fully parameterised (string concatenation); the auth switch `LABOS_AUTH`; whether there is any `eval`; the boundary of the script sandbox |
| C7 | **Driver contract** | Validation of the response fields (the four contract fields); the semantics of `method_status`; whether `=1` on a persistent connection is misjudged as a failure; timeout and retry |
| C8 | **Documentation consistency** | The D-number declarations in the table above vs what the code actually does; whether the README's "Known limitations" still hold |
| C9 | **Performance** | The source of the 6.5 s overhead per command; whether there are full-table scans / N+1 / missing indexes; queries over large fact tables |
| C10 | **Maintainability** | The god class (`ApiServer`); the two execution paths (`WorkflowService` vs `ExperimentFlowRunner`); the two `long` ID conversions |

---

## 6. Known risks disclosed by me (please verify whether they hold / whether they are worse)

> Source: the "Known limitations" section of `README.md`, `评审包-20261102.md`, `开发日志.md`, `20261101-01-grok.md`.
> **Listing these is not a disclaimer; it is asking the reviewer to hit these points first.**

| # | Disclosed item | To be verified |
|---|---|---|
| R1 | **Two execution paths**: `WorkflowService` and `ExperimentFlowRunner` each have their own device allocation and variable substitution | Whether the behaviour differs, and whether one of them is already dead code |
| R2 | **Two `long` ID sets**: `device_instance.id` and `resource_node.id` are converted by the caller | Whether every conversion point is correct, and whether there is any silent mismatch |
| R3 | **A persistent connection with `method_status=1` is judged a failure straight away** | Whether long tasks such as incubation are inevitably misjudged |
| R4 | **The 6.5 s overhead per command has not been located** (the command itself only takes 312 ms) | Where the remaining overhead is (lock contention / polling / connection) |
| R5 | **`putPlate` writes the wrong column** (grok 20261101-01 pointed out that the column name in the `plate` table does not match; the exception was swallowed and caused a rollback) | Whether it has been fixed; whether the "swallow the exception + the rollback drags the rest along" pattern is still there |
| R6 | `tryReserve` updates 0 rows and still returns `OK` when it cannot find the record (around `PositionLock.java:132`) | Whether it has been fixed |
| R7 | `notePlateMove` does not look at the row count returned by `putPlate` and logs success even for 0 rows | Whether it has been fixed |
| R8 | The island lock has a TTL of 2 hours; after it expires two experiments may run on the same island at the same time | Whether there is a fallback / an alert |
| R9 | The coarse screening's "heaviest on resources" strategy may be overly conservative (the exception branch uses more devices) | The false-rejection rate is not measured |
| R10 | The wait-for graph's "false rejection" is not measured (`rejectedCount` does not distinguish a real deadlock from a misjudgement) | — |
| R11 | `estimatedWaitSeconds` often shows 0 | — |
| R12 | Unblocking a plate position depends on `plate.possible_locations`; if the field is lost the position is locked forever | Whether there is a fallback |
| R13 | **Zero real-device verification**: the gate opening/closing time, the position coordinates, the number of actions in a `Transfer`, the meaning of a channel with `capacity>1` | Whether the design assumptions hold |

---

## 7. Review record requirements

Per [docs/en/review-guidelines.md](review-guidelines.md): all fields present (id / time / model / scope / method / conclusion / list of comments / response / disposition),
comments graded **P0 blocking / P1 serious / P2 normal / P3 suggestion**, **every comment must give a `file:line`**,
every disposition answered item by item, and both the documents and the code **have to be covered**.

**Cross-agent audit rule**: this round has to use a **different model family** from grok / kimi / GLM-self.
