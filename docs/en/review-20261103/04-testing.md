# Testing Document · LabOS Laboratory Control Console

> **English edition.** The Chinese original is
> [`docs/评审包-20261103/04-测试文档.md`](../../评审包-20261103/04-测试文档.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Audience: review (to judge how credible it is), maintainers (how to verify after a change)
> Companions: [Functional document](01-functional.md) · [Technical document](02-technical.md) · [Design document](03-design.md)
>
> The conclusion in one sentence: **all 379 pure-logic tests are green (`--pure-only`, deterministic); the full run with the database is 397 cases; end to end it rests on 6 shell scripts + really clicking through the pages**.
> There is no automation at the page level (this is the biggest gap, see §7).

---

## 1. Testing strategy: four layers, each covering one part

| Layer | What it covers | How | How long | Dependency |
|---|---|---|---|---|
| **L1 pure-logic unit tests** | State machine, expressions, recovery policies, model library, simulator behaviour | `scripts/test-all.sh --pure-only` | ~40s | **None** (no database, no network) |
| **L2 tests with the database** | Position lock, pause / resume, recipe compilation | `scripts/test-all.sh` (detects the database automatically) | ~1min | PostgreSQL |
| **L3 chain scripts** | Control console → sidecar → simulator → database, end to end | `verify-device-chain.sh` (41 assertions) etc. | ~3min | Simulator + sidecar |
| **L4 manual page verification** | Interaction, display, state consistency | Clicking for real in a browser, checking the screenshots | Depends on the scenario | The whole service stack |

**Why L1 has to be "without a database"**: once 106 tests all connected to a real database, which meant there were effectively no tests on CI. Now the pure logic makes up the vast majority; `test-all.sh` **compiles `src/main` first** when `out/` is empty, so any machine that has done a `git clone` can verify directly.

> Also: 7 self-test classes used to sit in `src/main/java` (they were packaged into the artefact) and have been moved to `src/test/java` (the package names and the way they are run are unchanged).

---

## 2. How to run it

```bash
# L1: needs no environment at all (the recommended first step of a review)
cd /path/to/labos
./scripts/test-all.sh --pure-only
# Expected: 379 passed / 0 failed (2 tests that need the database are skipped)
# Note: --pure-only clears LABOS_DB_*, so the assertions of the branch that needs the database never run — that is why this number is deterministic

# L1+L2: needs the database
export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
./scripts/test-all.sh

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

## 3. The L1/L2 test-case list (`--pure-only`, **379** cases in total)

| Test class | Cases | What it covers | Example of a key assertion |
|---|---|---|---|
| `device.DeviceModelSelfTest` | 196 | The model library: method names, aliases, manual evidence, error-code mapping | A command that is not in the manual must be marked `undocumented`; every error code can be translated |
| `sim.SimulatorBehaviorSelfTest` | 41 | Simulator behaviour (real protocol): Seal/Home/RunProgram, fault injection | Injecting `0x8000000C` must return "no paper"; a normal call must return ok |
| `expr.ExpressionSelfTest` | 30 | The old expression component (historical) | — |
| `engine.ConditionEvaluatorSelfTest` | **32** | **The condition evaluator**: comparison / logic / variables / **arithmetic** / rejecting injection | `(a+b)/2>=5` holds; string arithmetic `${s}+1` throws; `1/0` throws; `Runtime.exec` is rejected |
| `driver.DeviceErrorChainSelfTest` | **14** (+5 real-database assertions, only run with the database) | The error-code chain: device code → console code → policy | `0x8000000C → EC-08-400C → manual` |
| `recovery.RecoverySelfTest` | 17 | Policy matching, back-off computation, scope | Exact > code-level > wildcard; the exponential back-off does not exceed the cap |
| `engine.EngineSelfTest` | 14 | The state machine: retry / hand-over to an operator / abort / skip | When the retries are exhausted it must be `MANUAL` |
| `driver.WaitForGraphSelfTest` | 13 | Deadlock detection | A circular wait must be refused |
| `alert.AlertSelfTest` | 13 | Alert aggregation and handling | The same fingerprint is merged, after the ack it is no longer escalated |
| `workflow.GraphRunnerTest` | **8** | Graph execution: split / join / deduplication / one report per loop iteration / **built-in nodes** | Both parallel branches execute; a loop of 3 iterations calls back with `[1,2,3]`; `__setVar`/`__expr` run through and produce **no device command** |
| `driver.SidecarChainTest` | 7 | Control console ↔ sidecar protocol | The frame format, the error-code pass-through (skipped when there is no dotnet) |
| **L2** `driver.TransferPlanSelfTest` | (with the database) | The transport plan and the position lock | A target position that is occupied must be refused |
| **L2** `workflow.PauseManagerSelfTest` | (with the database) | The database state of pause / resume | A run that is not paused cannot be resumed |

> The pure logic adds up to **379** (the 7 cases of SidecarChainTest count as 1 item).
> The full run with the database (`./scripts/test-all.sh`) = 379 + 5 (the real-database section of DeviceErrorChainSelfTest) + 3 (TransferPlanSelfTest) + 10 (PauseManagerSelfTest) = **397**.
> Only DeviceErrorChainSelfTest produces two different numbers depending on the environment, so **`LABOS_DB_*` is forcibly cleared** in `--pure-only` to keep it at 14.
> The two L2 items show "2 tests that need the database are skipped" under `--pure-only`.

---

## 4. The L3 chain scripts

| Script | Assertions | What it covers |
|---|---|---|
| `verify-device-chain.sh` | **41** | ①login ②the model enters the pool (idempotent) ③deploy an instance ④bind the driver ⑤start the simulator + sidecar ⑥shadow probe ⑦**resume over several nodes** (a 3-step workflow, no paper injected at step 2 → pause → replace the paper → continue → assert that only 2 commands are sent) ⑧look the recovery policy up again ⑨the node binding table agrees with the graph ⑩**the guards when saving the orchestration** (a missing parameter / an illegal id / a non-existent id are all rejected with 400, and the orchestration is not cleared) |
| `verify-m4.sh` | — | Pause / resume and fault handling: the three pause kinds, the plate-position UNKNOWN lock-out, the return-leg promise, the visibility of the list |
| `verify-m3.sh` | — | Start admission / the coarse main-chain screening |
| `verify-m1.sh` / `verify-m5.sh` | — | The milestone acceptance |
| `test-sidecar.sh` | 7 | The standalone protocol test of the sidecar |
| `load-test.sh` | — | The load test (multiple experiments in parallel) |
| **`check-schema-sync.sh`** | 4 | The schema artefacts vs the shards: re-run the merge and compare + assert `loop_index` / the 4-column uk / the parent foreign key (regression of BUG-20261103-01) |
| **`ci-gate.sh`** | 6 gates | The unified gate: pure logic ≥379 / tsc / **the schema artefacts agree** / **the parallel capability** / device chain ≥41 / e2e ≥14, any failure exits with a non-0 code |
| **`verify-parallel-flow.py`** | **4** | Parallelism inside a workflow: build a fork/join graph automatically → execute → assert that both branches complete + the commands on the same device do not overlap (the device lock serialises them) → clean up |
| **`verify-parallel-experiments.py`** | **3** | Concurrency on the same island: build N experiments and submit them asynchronously at the same time → sample the number of `running` → assert that the **peak parallelism = 1**, that there is queueing, and that everything completes → clean up |
| **`e2e-smoke.py`** | **14** | The 5 main chains of the frontend: login + workflow library, click a row into the canvas, the legend of the run detail, the expansion of the experiment run history, the error-code page |
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

| Feature | L1 | L2 | L3 | L4 manual |
|---|---|---|---|---|
| Device model library / error-code mapping | ✅ 196+14 | — | ✅ | ✅ |
| Device onboarding / deployment / method list | — | — | ✅ | ✅ |
| Workflow preflight (reachability / deadlock / condition syntax) | — | — | — | ✅ |
| Sequential execution | ✅ 6 | — | ✅ | ✅ |
| Parallel split / join | ✅ 6 | — | ✅ | ✅ |
| Conditional branch | ✅ 23 | — | — | ✅ |
| Loop (back edge) | ✅ 7 (one report per iteration) | — | — | ✅ |
| Variables (passed in at start / node output) | ✅ 23 (the evaluation side) | — | — | ✅ |
| Error code → policy → action | ✅ 17+14 | — | ✅ | ✅ |
| The paused queue's "dismiss in filter" (by kind / time) | — | — | ✅ manual | — |
| The designer's "estimated wait" (by device occupancy) | — | — | ✅ manual | — |
| The designer's "Timeline" (the start / end of each step) | — | — | ✅ manual | — |
| Drag-and-drop reordering in the Gantt (only for experiments that have not started) | — | — | ✅ manual | — |
| The closed loop of creating a workflow (two entry points: workflow library / experiment orchestration) | — | — | ✅ manual | — |
| Retry and back-off | ✅ 14+17 | — | — | ✅ (the time interval measured) |
| Pause / resume / crash recovery | — | ✅ | ✅ | ✅ |
| Opening / closing an alert | ✅ 13 | — | — | ✅ (verified with the network down for 42s) |
| Experiment orchestration / experiment execution | — | — | — | ✅ |
| Page display (graph / table / status) | — | — | — | ✅ (checked against the screenshots) |

**How to read it**: a blank means there is no automation and it relies on manual work; it does not mean "not tested".

---

## 7. What is explicitly not covered (the focus of the review)

| # | Gap | Risk | Why it has not been done yet |
|---|---|---|---|
| 1 | **Zero frontend e2e** | A wrong page state (the graph shows not executed, the button does not refresh) can only be found by clicking by hand — historically 5 real bugs were caught exactly this way | No Playwright / Cypress infrastructure |
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
./scripts/test-all.sh --pure-only        # 379 / 0

# 2. The frontend types must be clean
cd webapp && npx tsc -b --force          # 0 errors

# 3. The device chain must all pass
./scripts/verify-device-chain.sh         # 37 / 0

# 4. Frontend e2e (needs 5173 + 18080)
python3 scripts/e2e-smoke.py             # 14 / 0

# 4.5 The parallel capability (needs the devices to be reachable)
python3 scripts/verify-parallel-flow.py            # 4 / 0
python3 scripts/verify-parallel-experiments.py --count 4   # 3 / 0

# 5. Really run one job (the fastest smoke test)
curl -X POST "$API/api/workflows/1254/run?async=1" -H "Authorization: Bearer $T"
# Expected: the run is completed, run_node N1 is completed, run_command has 1 row and its error_code is empty
```

**The criterion**: all 5 of the above pass = the main line is not broken (or just run `./scripts/ci-gate.sh --all` once for all of it). If item 4 is "it ran green but the device received no command", **take the `run_command` table as the authority**, do not trust the page (this project has already paid for that).

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
