# Review Package · 2026-11-03 · Index and Review Notes

> **English edition.** The Chinese original is
> [docs/en/review-20261103/00-index.md](00-index.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Recipient: external review (AI or human)
> Code version: `main`, 2026-11-03. **Do not trust the commit hard-coded in the documents** (it goes
> stale) — use `git log -1 -- docs/评审包-20261103/` to see the last revision of this package, and
> `git rev-parse HEAD` to compare it with the code in front of you.
> Generated: 2026-11-03

---

## 1. What this is

This is the complete review material for a **laboratory automation control console** (LabOS). The system is responsible for: connecting the laboratory's instruments (plate sealer, centrifuge, incubator, robot arm …), orchestrating the process as a "flowchart", driving the devices from that graph, retrying / switching device / handing over to an operator according to policy when a device fails, and — when something goes wrong — stopping for a human to deal with it and then carrying on.

**What it can do today, how it is implemented, why it is designed this way and how well it is tested** correspond to four documents:

| # | Document | What it answers | Suggested reading order |
|---|---|---|---|
| 01 | [Functional document](01-functional.md) | What can the system give the user? How is it used on the pages? | Read first, to get the overall picture |
| 02 | [Technical document](02-technical.md) | How is the code organised? Which path does one execution take? | Read next, against the code |
| 03 | [Design document](03-design.md) | Why is it designed this way? What was given up? | Focus here — this is where opinions differ most |
| 04 | [Testing document](04-testing.md) | What was tested? How do I reproduce it? What was not tested? | Read last, to judge how credible it is |

The historical designs and topic documents (52 of them) are still under `docs/`; this package does not repeat them, it only links to them where needed.

---

## 2. Current system scale (verified, not estimated)

| Dimension | Value | How it was verified |
|---|---|---|
| Backend code | `labos-kernel` **29,671 lines** of Java (107 files), 11 packages | `find src/main/java -name '*.java' \| xargs wc -l` (7 self-test classes have moved to `src/test`, so it is fewer than in the last version) |
| Frontend code | **8,393 lines** of TypeScript/TSX, 19 pages | `find webapp/src -name '*.ts*' \| xargs wc -l` |
| Database | 112 tables (including the two monthly-partitioned groups `device_log` / `run_event`, 15 tables each) | `information_schema.tables` |
| Data in the database | the workflows and run records | direct `count(*)` |
| Error codes | 25 codes / 27 recovery policies | same |
| Devices | 76 device models / 248 deployed instances | same |
| Automated tests | **379 pure-logic cases** (`--pure-only`, deterministic) / 397 with the database | `scripts/test-all.sh --pure-only` |
| End-to-end scripts | device chain 41 + **parallel capability 7** (4 inside one workflow / 3 on the same island) | the `ok/bad` counters inside the scripts |

> Numbers go out of date. Re-run the commands when you check; do not quote this document directly.

---

## 3. How to run the system

### 3.1 Dependencies

- JDK 17+, Node 20+, .NET 10 (sidecar), PostgreSQL 14+ (remote database, see the environment variables)
- Optional: Redis (this repository does not depend strongly on it, the `redis` package has only 1 class)

### 3.2 Environment variables (backend database connection)

```bash
export LABOS_DB_HOST=<测试库地址>
export LABOS_DB_NAME=<库名>
export LABOS_DB_USER=<账号>
export LABOS_DB_PASS='<口令，内部渠道单独提供>'
# Note: this repository stores **no** database password at all (the historical manual text was kept out of the repository because of its classification).
# Ask the project owner for the test database; do not write the password into code or documents.
```

Other tunables: `LABOS_HEARTBEAT_INTERVAL_MS` (default 10000), `LABOS_OFFLINE_ALERT_MS` (default max(3×interval, 30s)), `LABOS_FAST_RECOVERY=1` (turns off the real back-off, CI only).

### 3.3 Startup order (the four parts)

```bash
# ① Build the kernel
cd labos-kernel && javac -encoding UTF-8 -d out -cp "out:lib/postgresql-42.7.3.jar" $(find src -name '*.java')

# ② Device simulator (emulates the real device TCP protocol, ports 19601/19602)
java -cp out com.labos.kernel.sim.DeviceSimulator --model s100 --device 413 --code S100UI --port 19601 --delay 1
java -cp out com.labos.kernel.sim.DeviceSimulator --model aspin-2k --device 414 --code ASPINUI --port 19602 --delay 1

# ③ sidecar (.NET, translates HTTP into the device vendor protocol; 18501/18502)
cd sidecar-dotnet/Labos.Sidecar
dotnet run -- --model s100 --transport sim --sim-port 19601 --port 18501 --device 413

# ④ Backend API (18080)
cd labos-kernel && java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer 18080

# ⑤ Frontend (5173)
cd webapp && npm run dev
```

First login: `admin / admin123` (the test account in the database, **must be changed in production**).

> Without the sidecar the device-chain tests are skipped; without the simulator the execution fails, but reading the code is unaffected.

---

## 4. Suggested review focus (9 items)

Ordered by "most likely to go wrong + biggest impact". Every item comes with an entry point, so you do not have to search the whole repository.

| # | Focus | Why it is worth looking at | Entry point |
|---|---|---|---|
| 1 | **Recovery scope matching** | A `command`-level retry is configured in the database but the code throws at `node` level → the configuration never takes effect. This kind of "configured but as if it were not" is the hardest to find | `DriverStepExecutor.scopeOf()`, `RecoveryPolicyResolver` |
| 2 | **The parallel semantics of graph execution** | It is "logical parallelism" (one after another, all of them able to advance), not thread parallelism. Device actions have physical side effects — one mistake here is a collision | `GraphRunner.run()` |
| 3 | **The correctness of resume-from-breakpoint** | On resume, "already completed nodes must be skipped", otherwise **the robot arm moves the plate a second time** | `WorkflowService.resumeAsync()` + `GraphRunner.withSkipKeys` |
| 4 | **`skipped` vs `bypassed`** | The two words look alike but behave completely differently: the former stops and hands over to a human (the plate position is not trustworthy), the latter continues normally (the branch was not selected) | `NodeStatus`, `WorkflowService.markBypassedNodes` |
| 5 | **The safety boundary of the condition expression evaluator** | A hand-written parser, no `eval`. Confirm that injection really is impossible | the injection cases of `ConditionEvaluator` + `ConditionEvaluatorSelfTest` |
| 6 | **Loop protection against infinite loops** | A wrong back edge means an infinite loop; the protection is "10 iterations per node by default (configurable per workflow via `attr.maxIterations`) + 500 total steps" | `visits` / `maxSteps` inside `GraphRunner.run()` |
| 7 | **Whether state and truth agree** | Do the green / orange / grey shown on the page really correspond to `run_node.status` in the database (there was a case where "the graph showed not executed, but it had actually completed")? | `RunGraph.tsx`, `QueryService.runGraphJson` |
| 8 | **Database write amplification and idempotency** | `createNodeShells` inserting repeatedly hits the unique key (already fixed); are there more of the same kind | search the whole repository for `INSERT INTO run_node` / `ON CONFLICT` |
| 9 | **The boundary for deciding what a built-in node is** | A `protocolName` starting with `__` = variable / expression node, **it must not enter the device path**. Getting it wrong means "no executable resource" and a direct hand-over to a human (that is exactly how we failed the first time) | `WorkflowGraphParser` (`Kind.BUILTIN`), `GraphRunner.executeBuiltin` |

---

## 5. Known issues and risks (an honest list)

> When you review, treat the items below as "known" — there is no need to report them again; but **if you think the severity of one of them is underestimated, please point it out directly**.

### 5.1 Functional gaps

| # | Problem | Impact | Location |
|---|---|---|---|
| 1 | Error codes can only be edited, not added / deleted | A custom code that does not come from a manual has to be changed in the code | the `/error-codes` page |
| 2 | ~~On loop re-entry `run_node` only had one row~~ **Fixed (M6)**: one row per iteration via `loop_index` | The N-th iteration can be replayed by iteration number (the summary row still has `loop_index=0`) | `run_node` unique key `(run_id, node_key, attempt, loop_index)` |
| 3 | Variables only support top-level scalars | The inner level cannot be read when the device returns a nested structure | `WorkflowService.loadNodeOutputs` |
| 4 | Condition expressions do not support arithmetic | `${a}+1 > 3` cannot be written | `ConditionEvaluator` |
| 5 | The iteration cap is workflow-level, not gateway-level | Several back edges in one workflow share one cap | `workflow.attr.maxIterations` |
| 6 | A back edge crossing a parallel split is unverified | The combined scenario may deadlock | testing document §6 |
| 7 | Experiment state / progress is not pushed in real time | The page polls every 2 seconds | `Experiments.tsx` |

### 5.2 Engineering risks

| # | Risk | Explanation |
|---|---|---|
| 1 | The test database password only travels through internal channels | The body of this package **uses placeholders only**, the repository holds no password; the scripts inject it through the `LABOS_DB_*` environment variables |
| 2 | The backend has no authorisation tiers | Every endpoint can be called by anyone who is logged in, with no per-role split (the `app_role` table exists but is not used) |
| 3 | Single-process in-memory state | Pause / resume, island locks and device locks all live inside the process; a multi-instance deployment breaks them |
| 4 | The event table is partitioned monthly, but there is no archiving / cleanup policy | `run_event` already has 100,000+ rows |
| 5 | The 19 frontend pages have no e2e tests | Everything is clicked by hand (this project has historically found 5 real bugs by "really clicking through it once") |

### 5.3 Do not trust these

- **The simulator is not a real device**. The protocol frame format matches, but the timing, concurrency and abnormal behaviour are all gentler than on a real device.
- **"It ran green" does not mean the device moved.** There was a case where "the page was all green and the device had not received a single command" (root cause: the device id was judged to be a pseudo id). When reviewing, take the `run_command` table as the authority, not the page.
- **The load-test data comes from a single-machine simulator** and cannot be extrapolated to the throughput of a real site.

---

## 6. The three cheapest actions for a re-review

```bash
# ① Run the pure-logic tests once (no database needed, within 1 minute)
cd /path/to/labos && ./scripts/test-all.sh --pure-only
# Expected: 379 passed / 0 failed

# ② Run the full device chain once (simulator + sidecar needed, about 3 minutes)
export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
./scripts/verify-device-chain.sh
# Expected: all 41 assertions pass (the script prints ✓/✗)

# ③ Spot-check a real execution
curl -s "$API/api/runs/<id>" -H "Authorization: Bearer $TOKEN" | jq '.run.status, [.nodes[]|{nodeKey,status,errorCode}]'
# Compare against the flowchart colours on the page /runs/<id>
```

---

## 7. How the documents relate to each other

```
README.md (project overview)
  └─ docs/评审包-20261103/
       ├─ 00-索引与评审须知.md   ← you are reading this
       ├─ 01-功能文档.md
       ├─ 02-技术文档.md
       ├─ 03-设计文档.md
       └─ 04-测试文档.md
  └─ docs/ (52 historical topic documents, look them up as needed)
       ├─ 技术架构与模块地图.md   (finer package structure)
       ├─ 数据库设计说明.md
       ├─ 暂停恢复与故障处理-设计.md
       ├─ 实验编排-功能设计.md
       ├─ 流程控制-{功能说明,技术设计,测试报告}.md
       └─ 开发日志.md            (the whole story of every decision and every pitfall, 20,000+ lines)
```

**When they conflict, the code wins**, then this document, then the historical topic documents.

---

## 8. Next-phase plan

> **The authoritative version is [`ROADMAP.md`](../roadmap.md) at the repository root** (phase breakdown, feature lists, acceptance criteria, P0/P1/P2 placement).
> This section only gives the reviewer a summary; the details and the changes are governed by ROADMAP — so that two places do not each keep a copy that drifts apart.

| Phase | Goal | Key deliverable |
|---|---|---|
| **M6 Data integrity** ✅ | The execution record loses nothing and can be replayed per iteration | Done: `run_node.loop_index`, foreign keys added to the schema scripts |
| **M7 Operability** ✅ | On site, behaviour is changed without touching code or writing SQL | Done: error-code add / delete, scope flagged in red, expression arithmetic, hot-configurable thresholds |
| **M8 Verifiability** ✅ | From "a human clicks it" to "CI blocks it" | Done: `ci-gate.sh` (379/41/14 hard thresholds) + 5 e2e + a long-stability script |
| **M9 Scheduling upgrade** | Multiple experiments can be scheduled, inspected and adjusted | Scheduling Gantt, manual queue-jumping, estimated wait written back |
| **M10 Real-device integration** | From "passes in simulation" to "runs on site" | Manual onboarding, long-task timeout policy, on-site checklist |
| **M11 Platformisation** | Multi-tenancy / permissions / reporting / multi-instance | — |

The ordering rationale: **first plug the hole that "silently loses data" (M6) → then close the configuration loop (M7) → then lock it down with CI (M8) → only then dare to touch scheduling and real devices**.
The 5 unhandled items of review record §8.8 all fall into M6; the re-check commands of §8.9 have been written into the DoD of ROADMAP §5.
