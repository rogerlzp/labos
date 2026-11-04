# Testing Document · LabOS Laboratory Control Console

> **English edition.** The Chinese original is
> [docs/en/review-20261103/04-testing.md](04-testing.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Audience: review (to judge how credible it is), maintainers (how to verify after a change)
> Companions: [Functional document](01-functional.md) · [Technical document](02-technical.md) · [Design document](03-design.md)
>
> The conclusion in one sentence: **all 547 pure-logic tests are green (`--pure-only`, deterministic); the full run with the database is 917 cases;
> end to end it rests on shell / Python scripts + 8 Playwright page-track scripts**.
> Page-level automation has gone from "zero" to 8 scripts (the four P0 pages are covered, see §4 and §7).

---

## 1. Testing strategy: four layers, each covering one part

| Layer | What it covers | How | How long | Dependency |
|---|---|---|---|---|
| **L1 pure-logic unit tests** | State machine, expressions, recovery policies, model library, simulator behaviour | `scripts/test-all.sh --pure-only` | ~40s | **None** (no database, no network) |
| **L2 tests with the database** | Position lock, pause / resume, recipe compilation, batch persistence, RBAC persistence | `scripts/test-all.sh` (detects the database automatically) | ~1min | PostgreSQL |
| **L3 chain scripts** | Control console → sidecar → simulator → database, end to end | `verify-device-chain.sh` (41 assertions) etc. | ~3min | Simulator + sidecar |
| **L4 page e2e** | Interaction, display, state consistency | Real Playwright clicks (`scripts/e2e-*.py`); key conclusions checked against screenshots | Depends on the scenario | The whole service stack (+ simulator) |

**Why L1 has to be "without a database"**: once 106 tests all connected to a real database, which meant there were effectively no tests on CI. Now the pure logic makes up the vast majority; `test-all.sh` **compiles `src/main` first** when `out/` is empty, so any machine that has done a `git clone` can verify directly.

> Also: 7 self-test classes used to sit in `src/main/java` (they were packaged into the artefact) and have been moved to `src/test/java` (the package names and the way they are run are unchanged).

---

## 2. How to run it

```bash
# L1: needs no environment at all (the recommended first step of a review)
cd /path/to/labos
./scripts/test-all.sh --pure-only
# Expected: 547 passed / 0 failed (9 tests that need the database are skipped)
# Note: --pure-only clears LABOS_DB_*, so the assertions of the branch that needs the database never run — that is why this number is deterministic

# L1+L2: needs the database
export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
./scripts/test-all.sh
# Expected: 917 passed / 0 failed (measured against a reachable database)

# L3: the full device chain (needs the simulator + sidecar, the script starts them itself)
export LABOS_DB_HOST=... (the same as above)
./scripts/verify-device-chain.sh
# Expected: all 41 assertions pass, the failing ones are printed at the end

# A single test-case class
cd labos-kernel
javac -encoding UTF-8 -d out -cp "out:lib/postgresql-42.7.3.jar" $(find src/test -name '*.java')
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.engine.ConditionEvaluatorSelfTest
```

> `test-all.sh` **compiles `src/test` first** and then runs. It used to compile nothing, so a newly added test class only reported "main class not found", which looks like a failure but is actually a missing compilation.

---

## 3. The L1/L2 test-case list (`--pure-only`, **547** cases in total)

| Test class | Cases | What it covers | Example of a key assertion |
|---|---|---|---|
| `device.DeviceModelSelfTest` | 196 | The model library: method names, aliases, manual evidence, error-code mapping | A command that is not in the manual must be marked `undocumented`; every error code can be translated |
| `sim.SimulatorBehaviorSelfTest` | 41 | Simulator behaviour (real protocol): Seal/Home/RunProgram, fault injection | Injecting `0x8000000C` must return "no paper"; a normal call must return ok |
| `workflow.BatchPlannerSelfTest` | 39 | S3 batching: batch size / remainder batched / empty set / D5 batch variables / D6 status aggregation | 96/24=4 batches; the remainder forms its own batch |
| `engine.ConditionEvaluatorSelfTest` | **32** | **The condition evaluator**: comparison / logic / variables / **arithmetic** / rejecting injection | `(a+b)/2>=5` holds; string arithmetic `${s}+1` throws; `1/0` throws; `Runtime.exec` is rejected |
| `expr.ExpressionSelfTest` | 30 | The existing expression component (historical) | — |
| `device.DeviceModelLibrarySelfTest` | 22 | M10 model-library metadata: manual/planned semantics, production-database aliases, the libraryJson contract | — |
| `workflow.StatusVocabularySelfTest` | 20 | The single source of the status vocabulary (backend `StatusVocabulary` ↔ page wording) | — |
| `workflow.RuntimeHoldSelfTest` | 18 | Scope hold precedence + boundary gates | `experiment > island > site` |
| `recovery.RecoverySelfTest` | 17 | Policy matching, back-off computation, scope | Exact > code-level > wildcard; the exponential back-off does not exceed the cap |
| `workflow.SchedulerEtaSelfTest` | 17 | The duration model / total-duration prediction / importing durations from the database | — |
| `api.RbacRulesSelfTest` | 15 | RBAC closure: role allow-list / write-permission matrix / last-admin protection (pure logic) | — |
| `api.JsonFieldSelfTest` | 11 | Minimal JSON field extraction: Unicode-escape decoding (Chinese names no longer stored as literal escapes), malformed input does not crash | — |
| `driver.DeviceErrorChainSelfTest` | **14** (+5 real-database assertions, only run with the database) | The error-code chain: device code → console code → policy | `0x8000000C → EC-08-400C → manual` |
| `engine.EngineSelfTest` | 14 | The state machine: retry / hand-over to an operator / abort / skip | When the retries are exhausted it must be `MANUAL` |
| `driver.WaitForGraphSelfTest` | 13 | Deadlock detection | A circular wait must be refused |
| `alert.AlertSelfTest` | 13 | Alert aggregation and handling | The same fingerprint is merged, after the ack it is no longer escalated |
| `api.ExperimentGraphCompileSelfTest` | 10 | Experiment-orchestration ordering: no edges = sequence order / with edges = topological order / a cycle must be rejected | — |
| `workflow.RunControlSelfTest` | 10 | Experiment-level run control: scope degradation + a `site` hold cannot be lifted incidentally | — |
| `workflow.GraphRunnerTest` | **8** | Graph execution: split / join / deduplication / one report per loop iteration / **built-in nodes** | Both parallel branches execute; a loop of 3 iterations calls back with `[1,2,3]`; `__setVar`/`__expr` run through and produce **no device command** |
| `driver.SidecarChainTest` | 7 | Control console ↔ sidecar protocol | The frame format, the error-code pass-through (skipped when there is no dotnet) |
| `workflow.ExperimentOrderSelfTest` | 6 | Partial ordering of experiment blocks (the S2-A migration boundary) | — |
| **L2** `workflow.BatchDbSelfTest` | 244 | S3 batch persistence: idempotent batch creation / snapshot, a failed batch is retried with `attempt_no=2`, a successful batch refuses a re-run, batch-boundary holds (a fake executor, no real device is touched) | — |
| **L2** `workflow.RunControlDbSelfTest` | 36 | Experiment-level pause / stop / resume persistence (coverage scope / the two stop branches / the resume baseline) | — |
| **L2** `workflow.StartupSettleSelfTest` | 26 | The BUG-14 startup fallback settle: an orphan `running` becomes `failed` + the batch/experiment is written back | Legitimate data is not touched |
| **L2** `workflow.SchedulerRehydrateSelfTest` | 14 | S3.2-A restart rehydrate: `queued` runs are put back into the scheduler queue / idempotent | `running` + `paused` are not enumerated |
| **L2** `api.ExperimentGraphPersistenceSelfTest` | 13 | Experiment-orchestration persistence | — |
| **L2** `workflow.RuntimeHoldDbSelfTest` | 10 | Scope-hold persistence (idempotent / unique per scope / release) | — |
| **L2** `workflow.PauseManagerSelfTest` | 10 | The database state of pause / resume | A run that is not paused cannot be resumed |
| **L2** `api.UserRoleDbSelfTest` | 10 | RBAC closure persistence: the role allow-list + last-admin protection (created and cleaned up by itself) | — |
| **L2** `driver.TransferPlanSelfTest` | 2 | The transport plan and the position lock | A target position that is occupied must be refused |

> The pure logic adds up to **547** (the 7 cases of SidecarChainTest count as 1 item).
> The full run with the database (`./scripts/test-all.sh`) = 547 + 5 (the real-database section of DeviceErrorChainSelfTest, 14→19) + 365 (the 9 L2 classes: 244+36+26+14+13+10+10+10+2) = **917**.
> Only DeviceErrorChainSelfTest produces two different numbers depending on the environment, so **`LABOS_DB_*` is forcibly cleared** in `--pure-only` to keep it at 14.
> The 9 L2 classes show "9 tests that need the database are skipped" under `--pure-only`.

---

## 4. The L3 chain scripts / L4 page track

| Script | Assertions | What it covers |
|---|---|---|
| `verify-device-chain.sh` | **41** | ①login ②the model enters the pool (idempotent) ③deploy an instance ④bind the driver ⑤start the simulator + sidecar ⑥shadow probe ⑦**resume over several nodes** (a 3-step workflow, no paper injected at step 2 → pause → replace the paper → continue → assert that only 2 commands are sent) ⑧look the recovery policy up again ⑨the node binding table agrees with the graph ⑩**the guards when saving the orchestration** (a missing parameter / an illegal id / a non-existent id are all rejected with 409, and the orchestration is not cleared) |
| `verify-m4.sh` | — | Pause / resume and fault handling: the three pause kinds, the plate-position UNKNOWN lock-out, the return-leg promise, the visibility of the list |
| `verify-m3.sh` | — | Start admission / the coarse main-chain screening |
| `verify-m1.sh` / `verify-m5.sh` | — | The milestone acceptance |
| `test-sidecar.sh` | 7 | The standalone protocol test of the sidecar |
| `load-test.sh` | — | The load test (multiple experiments in parallel) |
| **`check-schema-sync.sh`** | 4 | The schema artefacts vs the shards: re-run the merge and compare + assert `loop_index` / the 4-column uk / the parent foreign key (regression of BUG-20261103-01) |
| **`ci-gate.sh`** | 13 gates | The unified gate: pure logic ≥547 / tsc / **the schema artefacts agree** / **the parallel capability ≥4+3** / **the full flow matrix ≥45** / device chain ≥41 / e2e ≥24 / **the page flow matrix ≥21** / **experiment run control ≥22** / **the simulator integration ≥24** / **the four P0 pages** (alerts ≥8 / policies ≥12 / paused queue ≥7 / canvas ≥12), any failure exits with a non-0 code |
| **`verify-parallel-flow.py`** | **4** | Parallelism inside a workflow: build a fork/join graph automatically → execute → assert that both branches complete + the commands on the same device do not overlap (the device lock serialises them) → clean up |
| **`verify-parallel-experiments.py`** | **3** | Concurrency on the same island: build N experiments and submit them asynchronously at the same time → sample the number of `running` → assert that the **peak parallelism = 1**, that there is queueing, and that everything completes → clean up |
| **`verify-flow-matrix.py`** | **45** | The full flow matrix: nodes / gateways / variables / loops / back edges / parallelism / port topology / pause-resume / experiments / concurrency |
| **`e2e-smoke.py`** | **24** | The 5 main chains of the frontend: login + workflow library, click a row into the canvas, the legend of the run detail, the expansion of the experiment run history, the error-code page (including the RBAC closure + the Header role assertion) |
| **`e2e-flow-matrix.py`** | **21** | The page flow matrix: designer assembly / variable nodes / run-detail colouring / page pause-resume |
| **`verify-exp-control.py`** | **22** | Experiment run control: page pause (a site hold) / resume / stop |
| **`e2e-sim-flow.py`** | **24** | Simulator integration: create a workflow on the page → bind a real device in the designer → create an experiment on the page → execute on the page → assert the device really responds (needs simulators 19500-19506) |
| **`e2e-alerts.py`** | **16** | The alerts page: the heartbeat-settings card + the alert table / empty state + the claim / close dialogs |
| **`e2e-policies.py`** | **13** | The policies page: the recovery-policy table + the edit dialog + the effect validation |
| **`e2e-paused.py`** | **7** | The paused queue: the four statistic cells + the empty state / queue shape is self-consistent |
| **`e2e-canvas.py`** | **12** | The experiment canvas: island selection / the six logical node kinds / reactflow / Run-by-arrows · Save · Back / the properties panel |
| **`soak-test.sh`** | — | Long stability: N consecutive executions, assert a failure rate of 0, that no data is lost and that the memory does not grow (the baseline is taken after 3 warm-up runs) |

**Note**: the L3 scripts start the sidecar on a random port and **restore the driver endpoints** to 18501/18502 at the end (otherwise the devices on the UI are always "unreachable", and the next troubleshooting session suspects the code first).

---

## 5. Test record of the features added in this round

The full record is in `docs/流程控制-测试报告.md`, the summary:

| Feature | How it was tested | Result |
|---|---|---|
| Condition expression evaluation | 23 unit tests + end to end | All pass; `vars=ok=true` takes A, `ok=false` takes default |
| The branch that is not selected, `bypassed` | End to end (3 branches) | `v=1/2/9` take A/B/default respectively, the other two are `bypassed` |
| Back-edge loop | End to end | `go=yes` runs to the cap (default 10 / configured 3 / configured 2 were all verified) |
| Loop + parallelism | End to end (T-PLOOP) | A 1 time + B 2 iterations = 3 Seal commands, the join behaves normally, no deadlock |
| The node output drives the condition | End to end (T-OUT) | `plateType` comes from the device response → the condition holds → 2 iterations per the cap |
| The preflight of the condition syntax | API | `PF-09` reports "表达式意外结束：${ok} >" (the expression ended unexpectedly) |
| Resume-from-breakpoint (several nodes) | L3 script + manual | The resume sends only 2 commands (N1 is not executed again); **new assertions: 3 step rows / 3 command rows / no uk_run_node conflict** (added after the review H1 fix) |
| Crash recovery | Manual (a `recoverable` run was created) | Click "Resume" → completed, the card disappears |
| Multiple policies per error code | Clicking on the page | Add / change / delete a `node`-scope policy, the database is updated in step |
| The device methods are persisted | Page + API | S100 12 methods / ASPIN 2K 8 methods |

---

## 6. Coverage matrix (feature × how it is tested)

| Feature | L1 | L2 | L3 | L4 page |
|---|---|---|---|---|
| Device model library / error-code mapping | ✅ 196+14 | — | ✅ | ✅ |
| Device onboarding / deployment / method list | — | — | ✅ | ✅ |
| Workflow preflight (reachability / deadlock / condition syntax) | — | — | — | ✅ |
| Sequential execution | ✅ 8 | — | ✅ | ✅ |
| Parallel split / join | ✅ 8 | — | ✅ | ✅ |
| Conditional branch | ✅ 32 | — | — | ✅ |
| Loop (back edge) | ✅ 8 (one report per iteration) | — | — | ✅ |
| Variables (passed in at start / node output) | ✅ 32 (the evaluation side) | — | — | ✅ |
| Error code → policy → action | ✅ 17+14 | — | ✅ | ✅ |
| The paused queue's "dismiss in filter" (by kind / time) | — | — | ✅ | ✅ page |
| The designer's "estimated wait" (by device occupancy) | — | — | ✅ manual | — |
| The designer's "Timeline" (the start / end of each step) | — | — | ✅ manual | — |
| Drag-and-drop reordering in the Gantt (only for experiments that have not started) | — | — | ✅ manual | — |
| The closed loop of creating a workflow (two entry points: workflow library / experiment orchestration) | — | — | ✅ | ✅ |
| Retry and back-off | ✅ 14+17 | — | — | ✅ (the time interval measured) |
| Pause / resume / crash recovery | — | ✅ | ✅ | ✅ |
| Opening / closing / claiming an alert | ✅ 13 | — | — | ✅ page |
| Experiment orchestration / experiment execution | — | ✅ | ✅ | ✅ |
| Page display (graph / table / status) | — | — | — | ✅ (checked against the screenshots) |

**How to read it**: a blank means there is no automation and it relies on manual work; it does not mean "not tested". A ✅ in the `L4 page` column means it is covered by a Playwright script (`scripts/e2e-*.py`); rows marked "manual" still rely on a human clicking.

---

## 7. What is explicitly not covered (the focus of the review)

| # | Gap | Risk | Why it has not been done yet |
|---|---|---|---|
| 1 | **The page track is still not exhaustive** | A wrong page state on `/gantt`, `/duration`, `/plates`, `/island`, `/island-flow`, `/topology`, `/audit` or the home dashboard can only be found by clicking by hand | Not P0; the four P0 pages (`/alerts` `/policies` `/paused` `/canvas`) are covered by `e2e-alerts/policies/paused/canvas.py`; the rest come later |
| 2 | A loop **across a parallel split** | The combined scenario may deadlock | The combinations explode; a single scenario is guaranteed first |
| 3 | The **trace of the iteration number** of a loop re-entry | The intermediate state of the N-th iteration is not visible | It needs a change to the `run_node` table structure |
| 4 | Arithmetic inside a condition | Writing `${a}+1 > 3` on site fails | The evaluator does not support it |
| 5 | Multi-tenant isolation | Only a single tenant has ever been run | There is no second set of data |
| 6 | The real device (not the simulator) | The timing, the concurrency and the abnormal behaviour differ | There is no real-device environment |
| 7 | Long stability (running for days) | Memory / connection leaks | There is no long-stability environment |
| 8 | The **correctness** under a concurrent load test (not just the throughput) | The state gets confused under lock contention | The load test only looked at the throughput |
| 9 | The resume after a node was renamed | skipKeys matches on `node_key`, a rename makes it miss | No test case has been constructed |

---

## 8. Regression baseline (how to decide after a change that "nothing is broken")

```bash
# 1. The pure logic must be all green, the number of cases must not drop
./scripts/test-all.sh --pure-only        # 547 / 0

# 2. The frontend types must be clean
cd webapp && npx tsc -b --force          # 0 errors

# 3. The device chain must all pass
./scripts/verify-device-chain.sh         # 41 / 0

# 4. The page main chains + the page flow matrix (needs the frontend + API)
python3 scripts/e2e-smoke.py             # 24 / 0
python3 scripts/e2e-flow-matrix.py       # 21 / 0

# 4.5 The four P0 pages (alerts / policies / paused queue / experiment canvas)
python3 scripts/e2e-alerts.py            # 16 / 0
python3 scripts/e2e-policies.py          # 13 / 0
python3 scripts/e2e-paused.py            # 7 / 0
python3 scripts/e2e-canvas.py            # 12 / 0

# 4.6 The parallel capability (needs the devices to be reachable)
python3 scripts/verify-parallel-flow.py            # 4 / 0
python3 scripts/verify-parallel-experiments.py --count 4   # 3 / 0

# 5. Really run one job (the fastest smoke test)
curl -X POST "$API/api/workflows/1254/run?async=1" -H "Authorization: Bearer $T"
# Expected: the run is completed, run_node N1 is completed, run_command has 1 row and its error_code is empty
```

**The criterion**: all of the above pass = the main line is not broken (or just run `./scripts/ci-gate.sh --all` once for all of it; the supplementary page track is described in `docs/en/test-specification.md`). If item 4 is "it ran green but the device received no command", **take the `run_command` table as the authority**, do not trust the page (this project has already paid for that).

---

## 9. How to record a manual verification (recommended)

This project has historically found a lot of problems by "really clicking through it once"; it is worth keeping that habit and leaving a trace:

| What to record | Example |
|---|---|
| The path of the operation | Workflows → T-X3 → Details → Execute (vars=v=2) |
| Expected vs actual | Expected B green, A/C not selected; actual the same |
| The evidence in the database | `run_node` A:bypassed B:completed C:bypassed |
| The screenshot | Save it as `/tmp/xxx.png`, paste the important conclusions into the development log |

> Every fix in the development log (`docs/开发日志.md`, 20,000+ lines) records "symptom → root cause → fix → measured", so it can be used directly as a reproduction manual.
