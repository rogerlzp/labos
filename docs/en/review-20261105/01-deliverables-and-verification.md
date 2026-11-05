# Review Package · 2026-11-05 · Deliverables and verification

> Range: `2c62ac3..bb97b62` (33 commits), in three blocks: A test system and gate hardening,
> B designer main path + logic-node layering, C crash recovery end to end.
> Every conclusion points to a **re-runnable script / file location** rather than trusting
> the documentation's own account.

## 1. Topic A · Test system and gate hardening

| Commit | Content | Where it lands |
|---|---|---|
| `2c62ac3` | Adds the P0 four-page gates (4.8–4.11: alerts / policies / paused queue / canvas) + a `pre-push` hook + a nightly e2e workflow | `scripts/ci-gate.sh`, `.githooks/pre-push`, `.github/workflows/e2e-nightly.yml` |
| `33daede` | Adds the test specification and the test master table (Chinese + English) and registers the English entry | `docs/en/test-specification.md`, `docs/en/test-case-master-table.md` |
| `e1796b3` | `dispatch()` in the scheduler reuses `QUEUE_ORDER`; self-tests for queue ordering / time constraints; thresholds 553→589 and 923→959 | `workflow/ExperimentScheduler.java`, `workflow/SchedulerOrderSelfTest.java` |
| `dae7238` | The specification is rewritten to match the code (§13.8 attended mode = alert level, §14.8 scheduling marked unimplemented, ledger 4.4) | `docs/en/functional-specification.md` |
| `3d2b80c` / `d2d99f1` / `fda400a` | Ledger 6.5 "attended window": 35 pure-logic assertions + persistence / API wiring; thresholds 589/959 → 624/994 | `recovery/ManualWindowSelfTest.java` |
| `8399d5b` / `e76e2bf` | Baseline numbers aligned with the real run (Chinese + English) | `docs/en/roadmap.md` |

Key points:

- **Tests first + two tracks** (script track / page track) are written into the test
  specification and enforced by the gate: `pre-push` forces `ci-gate.sh --all`, and thresholds
  "may only rise".
- **Dispatch and page ordering share one source**: `ExperimentScheduler.dispatch()` reuses
  `QUEUE_ORDER`, avoiding "tests pass but the real execution order differs".
- **Documentation self-consistency**: the functional specification was rewritten to match the
  code (no "keep the old text + append an erratum" style), and unimplemented items are marked
  as unimplemented.

## 2. Topic B · Designer main path + logic-node layering

| Batch | Content | Evidence |
|---|---|---|
| Batch 1 · canvas stop-the-bleeding | The `/canvas` palette keeps only "workflow"; start / end are laid out automatically; `flow-run` is no longer called; `MIN_CANVAS=12` is a constant count | `webapp/src/pages/ExperimentCanvas.tsx` |
| Batch 2 · parallel gateway | `WorkflowPalette.ts` gains a parallel-gateway entry and converges the palette items the parser rejects | `webapp/src/bpmn/WorkflowPalette.ts` |
| Batch 3 · condition expression | A right-panel "link · condition" card writes `bpmn:conditionExpression` and the gateway `default` | `webapp/src/pages/Designer.tsx` |
| Batch 4 · loop limit | A right-panel "workflow options" card + `GET/PUT /api/workflows/{id}/options` (1..100, camelCase `maxIterations`) | `workflow/WorkflowService.java` |
| Batch 2.5 · seam assertions | `e2e-flow-matrix.py` section F, 12 assertions: the page draws a XOR branch graph → a real run → the taken branch is `completed`, the dead branch is `bypassed` | `scripts/e2e-flow-matrix.py` |
| Designer main path | Find devices by island + island → device → drag a method into a node with its image + automatic robot-arm insertion across devices + explicit delete | `scripts/e2e-method-drop.py` |

The rationale is in `docs/en/design/logic-nodes-by-layer.md`: logic nodes **belong inside one
workflow**, and the main battleground is the workflow designer; the experiment canvas only does
"cross-workflow orchestration".

## 3. Topic C · Crash recovery end to end

| Item | Content |
|---|---|
| Main script | `scripts/verify-s3-2-residuals.py`, extended this round from 18 to **31** assertions |
| Mechanism | `pgrep` finds `ApiServer` → `kill -9` → wait for the port to be released → `nohup java ... ApiServer 18080` restart (inheriting this process's environment) |
| Assertions | A queued run is rehydrated into the queue on startup and dispatched to a terminal state; the `running` orphans of `run` / `experiment_run` at crash time are settled to `failed` by the startup fallback (`interrupted` / `recoverable` / `completedNodes` written back, `experiment` status synced); a legitimate `completed` run is untouched; a second restart is idempotent |
| Gate | `MIN_CRASH=31` + gate 4.13 (see `scripts/ci-gate.sh`) |
| Incidental fix | `kernel_env()` in `verify-flow-matrix.py` now aggregates `ps eww` of all pids (fixing "picking up the `bash -c` wrapper, not reading `LABOS_TRANSFER_PLAN` → four ports assertions falsely SKIP") |

Why it had to be done: the existing L2 self-tests (`workflow.StartupSettleSelfTest` /
`workflow.SchedulerRehydrateSelfTest`) `new` an instance in-process and reload data via
package-private hooks, so they only prove the method itself is right — they **cannot prove that
the `ApiServer` startup entry really calls settle / rehydrate**. The startup order is in
`api/ApiServer.java`: `StartupRecovery.settle` → `settleOrphanExperimentRuns` →
`scheduler.rehydrateQueued()`.

## 4. Verification matrix

| Track | Entry point | Number | In CI |
|---|---|---|---|
| Pure logic | `./scripts/test-all.sh --pure-only` | 624 / 0 | ✅ every push |
| Full run with database | `./scripts/test-all.sh` | 1005 / 0 | local `--with-db` |
| Device chain | `./scripts/verify-device-chain.sh` | 41 / 0 | local `--all` |
| Flow matrix | `python3 scripts/verify-flow-matrix.py --force-online` | 45 / 0 | local `--all` |
| Parallel capability | `verify-parallel-flow.py` / `verify-parallel-experiments.py` | 4 / 3 | local `--all` |
| Page flow matrix | `python3 scripts/e2e-flow-matrix.py` | 45 / 0 | local `--all` |
| Simulator integration | `python3 scripts/e2e-sim-flow.py` | 24 / 0 | local `--all` |
| Crash recovery | `python3 scripts/verify-s3-2-residuals.py` | 31 / 0 | local `--all` |
| Chinese / English docs | `python3 scripts/verify-en-docs.py` | hard errors 0 | ⬜ |

## 5. Known gaps (honest-list summary)

The full list of 12 items is in `docs/en/test-case-master-table.md` §5. Those relevant to this round:

- Item 10: **the backend run path does not insert the robot-arm transfer automatically** — the
  designer inserts a `Transfer` when building the graph, but `WorkflowBuilder` / `GraphRunner` /
  `flow-run` do not at run time; there is no combined assertion for plate migration.
- Item 11: **canvas logic nodes cannot be saved / parallel is not executed** — batch 1 already
  stopped the bleeding (entries removed), and the designer side is complete.
- Item 12: **`subProcess` silently skips its successors** — `subProcess` is not in the parser
  whitelist, so wiring it into a chain silently marks successors `bypassed` (the whole run still
  reports `completed`); this round ruled "register only, do not touch the code".
- Specification §14.8 **rolling scheduling** (commitment window / 15-minute freeze zone / greedy)
  is not implemented enough to be testable.

## 6. Key decisions and pitfalls (self-exposed)

1. **The `subProcess` trap**: when it is not in the `WorkflowGraphParser` whitelist, the palette
   can drag it and saving passes, but at run time it silently skips its successors without any
   error.
2. **Editor capability mismatch**: the experiment canvas can draw a gateway but cannot save it
   (400); the workflow designer can run it, but the palette once lacked a parallel entry and the
   condition expression and loop limit had no UI.
3. **Scheduling consistency**: if `ExperimentScheduler.dispatch()` and the page-snapshot ordering
   do not share `QUEUE_ORDER`, you get "tests pass, the real execution order differs".
4. **`verify-flow-matrix.py` false SKIP**: `kernel_env()` took only the first `pgrep` pid, which
   could be the `bash -c` wrapper (no environment), so `LABOS_TRANSFER_PLAN` was unreadable and
   the four ports assertions falsely SKIPped (45 → 41). Fix: aggregate the `ps eww` of all pids.
5. **Crash-recovery script pitfalls**: the `run` table has **no `error` column** (only
   `error_code`), while `experiment_run` has `error text`; a "global rehydrate = 0" assertion is
   brittle (any other legitimate queued run interferes), so it was changed to a per-entity
   idempotency assertion; a hard-coded log path turns red, so it is resolved via `pgrep` + `lsof`.
6. **JDK 17 scripting limit**: Nashorn is removed, so running user scripts in the same JVM needs
   JSR-223 or GraalVM isolation (relevant to the script-node design).
