# Review Guide

> **English edition.** The Chinese original is [`docs/评审指引.md`](../评审指引.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> This document is written for external reviewers (a person or an AI).
> The purpose: **to let you know within 30 minutes what this project does, where it
> might be wrong, and what to look at.**

---

## 1. What this is

**LabOS — the laboratory control system**: an automation control kernel for
bio-pharma laboratories.
What it controls: incubators, robot arms, centrifuges, plate readers, plate movers —
communicating with vendor drivers over TCP/JSON.

**Core capability**:

```
Experiment definition (multiple workflows + variables + loops)
    ↓
Scheduling (parallel experiments, island-level mutual exclusion)
    ↓
Workflow execution (BPMN graph → nodes → device actions)
    ↓
Plate tracking (which position holds which plate + reservation to prevent conflicts)
```

**Scale**:

| Part | Files | Lines |
|---|---|---|
| Kernel `labos-kernel/` | 83 | 18,366 |
| Device gateway `labos-gateway/` | 3 | 794 |
| Frontend `webapp/` | — | 5,747 |
| Documentation `docs/` | 10 | 5,191 |
| git commits | 53 | — |

---

## 2. Which documents to read first

### Must read (in order)

| # | Document | Why read it |
|---|---|---|
| 1 | **This document** | The map + the known problems |
| 2 | `LabOS功能规格说明书.md` | **The functional spec is authoritative** (2.0-draft) |
| 3 | [`docs/en/architecture.md`](architecture.md) | Code directories → responsibility mapping |
| 4 | [docs/en/development-log.md](development-log.md) | Where every decision and every pitfall came from (4,788 lines — read it when debugging) |

### Read by topic

| Topic | Document |
|---|---|
| Experiment orchestration (variables / loops / canvas) | `docs/实验编排-功能设计.md` (1,049 lines, the most detailed) |
| Parallel experiment scheduling | `docs/多实验并行调度-设计.md`<br>`docs/多实验并行调度-动态规划与滚动排程.md` |
| Plate positions and mutual exclusion | `docs/板位级占用与位置互斥-设计.md` |
| Device gateway + Redis | `docs/设备网关与Redis状态中心-设计.md` |
| Testing and release | `docs/测试与上线策略.md` |
| UI design | `docs/UI-界面设计说明书.md` |
| On-site operation | `docs/操作手册-岛屿与搬运.md` |
| Device onboarding | `docs/初始化与设备接入指南.md` |

### Obsolete (marked as superseded at the top)

The root-level `前后端实施方案_编译甘特就绪调度.md`,
`目标功能文档_三方合并版.md`, `LabOS_新一代中控实现总纲_20261029.md` and similar —
**the schedule follows the specification; read these only for historical background**.

---

## 3. How to run it

### Dependencies

- PostgreSQL `192.0.2.1:5432` (database `labos`, user `labos`)
- Redis `192.0.2.1:6379` (the gateway state centre)
- JDK 17 (no third-party dependencies, only `postgresql-42.7.3.jar`)
- Node 18+ (frontend)

### Start

```bash
# 1. Kernel (port 18080)
cd labos-kernel
javac -encoding UTF-8 -d out $(find src -name '*.java')
java -Dfile.encoding=UTF-8 -cp "out:lib/postgresql-42.7.3.jar" \

# 2. Frontend (port 5173)
cd webapp
npm install && npm run dev

# 3. Device gateway (optional, persistent-connection mode)
cd labos-gateway
LABOS_REDIS_HOST=192.0.2.1 \
LABOS_GATEWAY_DRIVERS="DRV-A=tcp://192.0.2.10:8501" \
java -cp out com.labos.gateway.GatewayMain
```

### Verify

```bash
# Regression (should be all green)
cd labos-kernel
for t in recovery.RecoverySelfTest engine.EngineSelfTest alert.AlertSelfTest \
         workflow.WorkflowAlertTest workflow.GraphRunnerTest; do
  java -cp out com.labos.kernel.$t
done

# Full process set (the workflows walked on the simulation bus)
# Note: this is SimulatedBus walking the graph, not real-device verification
./scripts/start-kernel.sh \
     com.labos.kernel.workflow.RealWorkflowRunner --all
```

### Sign in

`admin` / `admin123`

---

## 4. Code map

```
labos-kernel/src/main/java/com/labos/kernel/
├── api/             HTTP endpoints (ApiServer is a ~1500-line monolith, QueryService does queries)
├── workflow/        workflows: BPMN parsing, compilation, execution, experiment orchestration, scheduling
│   ├── WorkflowService      core (execution, variable substitution, candidate pool)
│   ├── ExperimentExecutor   experiment execution (variables + loop expansion + sequential run)
│   ├── ExperimentScheduler  parallel experiments (island locks + queueing)
│   ├── GraphRunner          executes along a BPMN graph
│   └── ExperimentFlowRunner canvas step-by-step execution (writes run/run_node/run_command)
├── engine/          state machine (RunStateMachine: retry, hand-over, idempotency)
├── driver/          device drivers
│   ├── DeviceDriver         interface
│   ├── tcp/TcpDriverAdapter short connection
│   ├── tcp/TcpPersistentDriver persistent connection (pairs by messageId)
│   ├── PositionLock         position lock (atomic reservation, dual move locks)
│   └── ShadowProbe          single-command probe (shadow mode)
├── recovery/        crash-recovery decisions (RecoveryDecider); the crash cleanup is in workflow/StartupRecovery
│                    Note: there is no "lease takeover" mechanism (it was in the early design, never implemented)
├── alert/           alerts (generation, deduplication, merging)
└── redis/           hand-written RESP2 client

labos-gateway/src/main/java/com/labos/gateway/
├── GatewayMain             entry point (one consumer thread per driver)
├── driver/DriverConnection persistent connection + writes to Redis
└── redis/RedisClient       hand-written RESP2

webapp/src/pages/
├── Island.tsx          island view (drag devices onto the canvas, transfer sequence, plate visualisation)
├── Experiments.tsx     experiment orchestration (variable table, loops, run history)
├── ExperimentCanvas    experiment canvas (React Flow: cross-workflow orchestration - start / end / workflow)
├── IslandFlow.tsx      island orchestration (device links = plate transfers)
└── ...                 workflow library, designer, execution monitoring, alerts, etc.
```

**The two ID schemes are the easiest pitfall** (details below).

---

## 5. Key design decisions (this file has its own numbering block **D301-D311**)

> **The numbering-block rule is in `docs/评审规范.md` §7.** Global blocks: `决策记录.md`
> D1-D11, `并发调度决策记录.md` D101-D119, `开发日志.md` D201-D227, this file D301-D311.
> **A cross-file reference must name the file** (e.g. "concurrent scheduling D305").
>
> Correction: before 2026-11-02 this section's title claimed the numbering was "aligned
> with `决策记录.md`, not a separate scheme", but **it was not actually aligned** (the
> content is completely different from chapter 12 of `决策记录.md`) — that was the
> remediation of review record `20261101-01-grok.md` item A3, "only the title was
> changed, not the content". It has been changed to an independent numbering block.

| # | Decision | Where documented | Why decided this way |
|---|---|---|---|
| D301 | **Two layers of responsibility**: BPMN governs actions/conditions; the experiment layer governs variables and multi-round loops | Experiment orchestration §3 | Mixing them makes both unusable |
| D302 | **Position-level mutual exclusion is more fundamental than island-level** | Plate-position occupancy §2 | Positions are shared across islands, and plates already in the lab may still exist |
| D303 | **No pure DP scheduling** | Dynamic programming §4 | State space 2^50, work-time variance 13.5× |
| D304 | **Rolling time horizon**: commitment window + frozen zone + event-driven reorder | Dynamic programming §6 | The replacement after DP proved infeasible |
| D305 | **Atomic reservation**: `INSERT ON CONFLICT DO UPDATE WHERE` | Plate-position occupancy §4 | SELECT-then-UPDATE leaves a window |
| D306 | **The two move locks are taken in ascending id order** | Plate-position occupancy §5 | Removes circular wait (one of the four deadlock conditions) |
| D307 | **No `eval` for condition evaluation** | Experiment orchestration §12.6 | Canvas content comes from user input |
| D308 | **An unresolved variable keeps `${name}` verbatim** | Experiment orchestration §12.3 | Errors stay obvious and debuggable |
| D309 | **Stop on failure, never skip** | Experiment orchestration §11.5 | Skipping means the plate position becomes unknown |
| D310 | **The gateway is only a pipe; it does not judge business logic** | Device gateway §4 | The cleverer it gets, the harder problems are to locate |
| D311 | **Island lock TTL of 2 hours** | Parallel experiment scheduling §6 | When the execution thread is killed the `finally` never runs; the TTL forces release, at the **cost of two experiments possibly running on the same island** — a deliberate trade-off |

---

## 6. Known problems (**the review focus**)

### 🔴 Severe: `putPlate` does not persist (unresolved)

```
Symptom: the log shows [PLATE] Deposit1 dev=171 -> pos=504495
         but there is no such row in the position_state table
```

**Already ruled out**:
- the UPSERT statement itself is correct (running `INSERT 0 1` by hand succeeds)
- the parameter ordinal is correct
- there is no exception in the log

**Next step**: check whether `c.commit()` in `putPlateInternal` sits outside the
`if (n > 0)` branch.

**Impact**: the entire plate-tracking chain (visualisation, preflight, real plate IDs)
is built on unreliable data.

### 🟡 Two ID schemes mixed up (has caused errors 5 times)

```
device_instance.id (214)   ← the drivers/probes use this one
resource_node.id (200214)  ← the graph / execution records / positions use this one
```

**Where it has bitten**: the candidate pool, the driver registry, the plate
animation, `ExperimentFlowRunner`, reading position coordinates.

**Current state**: each call site converts with `instanceIdOf()`, but there is **no
type-level isolation** (they are all `long`).

**Recommendation**: introduce `DeviceInstanceId` / `ResourceId` value types and
eliminate it at compile time.

### 🟡 There are two execution paths, and their behaviour may differ

| Path | Entry point | Used for |
|---|---|---|
| `WorkflowService.execute` → `GraphRunner` | Workflow execution | BPMN graphs |
| `ExperimentFlowRunner.run` | `/api/experiments/flow-run` | Canvas step-by-step execution |

**Both write `run`/`run_node`/`run_command`, but the device-allocation and
variable-substitution logic are two separate code bases**.

**Risk**: you change one and forget the other.

### 🟡 The scheduler is the 2.0 simplified version

- Island-level mutual exclusion (parallelism = number of islands)
- Greedy ordering (EDD → priority → FIFO)
- **The rolling time horizon from the design document is not implemented**

**The document argues why DP is infeasible, but the code does not implement the
replacement** — this is the largest "documentation versus code" gap.

### 🟡 Persistent connections do not support a "running" state

```java
if (method_status == 1) {
    return failed("设备仍在运行，需要更长超时或异步上报");
}
```

**On a real device, incubation may take 30 minutes** — today it is judged a failure
immediately.

**Addition**: the default is still the short connection, and `TcpDriverAdapter`
treats every `method_status != 2` as a failure,
→ **the problem is not limited to persistent connections**.

### 🟡 The progress semantics have a flaw

`current_seq` is "which step is running" (1-3), not "how many steps finished".
A frontend showing `3/5` actually means "step 3 is running", not "3 steps done".

### 🟢 `markPlatePosition` is deprecated but may still be referenced

It has become an empty method (with a comment explaining why); if something else
still calls it, it fails silently.

### 🟢 `ApiServer` is a ~1500-line monolith

Routing, parameter parsing and JSON assembly all live together. When reviewing, you
can count how many `if (path.equals(...))` it has.

---

## 7. Engineering-practice problems (also review points)

### The recurring "I thought I changed it but I didn't" (4 times)

| Symptom | Root cause |
|---|---|
| A false sense of compilation | `grep error` does not match the Chinese "错误", so several rounds passed falsely |
| Wrong parameter ordinal | `ps.setLong(13, ...)` is actually the 12th, so it silently wrote NULL |
| `replace` anchor did not match | `s.replace(old, new)` silently returns the original string (3 times) |
| Several processes fighting over a driver | `pkill -f` did not kill everything, and the lingering process kept the connection |

**The hard checks in place now**:

```bash
# After a replace, confirm
python3 -c "print(s.count('新代码'))"   # non-zero
# After compiling, search with the Chinese term
javac ... 2>&1 | grep "错误"
# After killing processes, check the connections
lsof -i :19301
```

### The test strategy exists but is not fully executed

`docs/测试与上线策略.md` defines five layers of verification (unit → probe → shadow →
staged → canary).

**In practice only L1 (unit) and L2 (probe) are running**; L3 shadow has an interface
but has not become a routine check.

---

## 8. Review suggestions

### High-value review angles

| Angle | What to look at |
|---|---|
| **Correctness** | The atomicity of `PositionLock`, the deadlock argument for the dual move locks, the device rotation in `GraphRunner` |
| **Data consistency** | The two plate-position states (partly unified), the two ID schemes, dual-path execution |
| **Failure handling** | Whether a timeout interrupts, whether a preflight failure blocks, whether the idempotency key runs through everything |
| **Observability** | Whether `run_command` is enough to debug, whether the logs are enough to locate a problem |
| **Documentation versus code** | Especially the scheduler (the document describes a rolling horizon, the code is greedy) |
| **Security boundary** | No `eval`, parameter validation, permissions |

### Not worth focusing on

- Frontend styling details (demo quality)
- The code style of `ApiServer` (it is known to be a monolith)
- Test coverage numbers (look at whether the critical paths are tested at all)

---

## 9. One-paragraph summary

> **This is a laboratory control kernel that runs**: workflows are walked on the simulation bus,
> multiple experiments can safely run in parallel, and plate positions have
> reservation-based conflict prevention.
>
> **But there is one unresolved bug in plate persistence, the scheduler implements only
> the 2.0 simplified version, and the two ID schemes mixed up have caused repeated
> errors.**
>
> **Please focus the review on these three points, and on the gap between "what the
> documentation promises" and "what the code implements".**
