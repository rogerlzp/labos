# Review Package · 2026-11-05 (for AI review)

> **English edition.** The Chinese original is `docs/评审包-20261105/00-索引与评审须知.md`;
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical
> to the Chinese source — if they ever disagree, the Chinese original wins.

> Purpose: give an external reviewing AI a **self-contained** delivery summary + how to
> reproduce the verification + a list of review questions.
> The previous round is in `docs/en/review-20261104/` (M9 closure + BUG-14/BUG-15 already
> reviewed; not repeated here); the round before that is `docs/en/review-20261103/`.
> Recheck records of past rounds are in `docs/en/review-records/`.

## 1. Snapshot

| Item | Value |
|---|---|
| Repository | `github.com/rogerlzp/labos` (main) |
| Delivery snapshot | Code/test delivery snapshot of this round = `bb97b62`; review against **this**; this review package itself is a **docs-only commit** appended after `bb97b62`, with no code or test changes |
| Range of this round | `2c62ac3..bb97b62`, **33 commits** (including `2c62ac3` itself); earlier commits are the repository rebuild, de-narration and version-alignment work and are out of scope |
| Runtime environment | macOS, Java 17 (no Maven, full `javac` build); PostgreSQL 17.10 (database `labdb`); API `127.0.0.1:18080`; frontend dev `localhost:5173`; device simulator `19500+` |
| Test baseline | Pure logic **624/0**; full run with database **1005/0**; device chain **41/0**; flow matrix **45/0**; page flow matrix **45/0**; frontend e2e **24/0**; crash-recovery end to end **31/0** |
| Gate | `./scripts/ci-gate.sh --all` **14 gates green** (thresholds may only rise; see `MIN_*` at the top of `scripts/ci-gate.sh`) |

## 2. How to reproduce (the reviewing AI can execute each item)

~~~bash
cd <repo>
# 1) Pure logic (no DB needed)
./scripts/test-all.sh --pure-only              # 624/0
# 2) Full run with database (a reachable DB is needed; the password is not written
#    down here — use your own environment variables)
LABOS_DB_HOST=<host> LABOS_DB_NAME=labdb LABOS_DB_USER=labos \
LABOS_DB_PASS='<password>' ./scripts/test-all.sh          # 1005/0
# 3) Full gate (needs DB + simulator + frontend + API running) — 14 gates
./scripts/ci-gate.sh --all
# 4) Key scripts of this round (real DB + real API + real pages)
python3 scripts/verify-s3-2-residuals.py --base http://localhost:5173   # 31: crash recovery end to end (kill -9 → restart → startup fallback settle)
python3 scripts/verify-flow-matrix.py --force-online                    # 45: flow matrix (nodes / gateways / variables / loops / parallel / ports / pause)
python3 scripts/e2e-flow-matrix.py --base http://localhost:5173         # 45: page flow matrix (incl. section F, the "designer-drawn graph → real run" seam)
python3 scripts/e2e-canvas.py --base http://localhost:5173              # 12: experiment canvas (cross-workflow orchestration)
# 5) Chinese/English documentation cross-check (structure alignment / links / CJK residue)
python3 scripts/verify-en-docs.py                                       # hard errors 0
~~~

> Note: `verify-s3-2-residuals.py` **really kills the running kernel process and restarts
> it** — run it only in a test environment. It inherits the current process environment, so
> first `export LABOS_TRANSFER_PLAN=1 LABOS_EXEC_GRAPH=1 LABOS_DRIVER_MODE=persistent LABOS_AUTH=1`.
> The environment variable for the DB password is `LABOS_DB_PASS` (not `LABOS_DB_PASSWORD`).

## 3. Three deliverables of this round

| Topic | One line | Details |
|---|---|---|
| A · Test system and gate hardening | Established the test specification and the test master table; the "thresholds may only rise" gate; `pre-push` forces the full gate; P0 four-page assertions; scheduler dispatch shares the page-snapshot ordering; the spec was rewritten to match the code | `01` §1 |
| B · Designer main path + logic-node layering (batches 1–4 + 2.5) | The experiment canvas is shrunk to "cross-workflow orchestration only"; the designer gains a parallel gateway / condition expression / loop limit; the "designer-drawn graph → real run" seam assertions are added | `01` §2 |
| C · Crash recovery end to end | Really `kill -9` → restart → assert queued rehydrate + orphan running settled by the startup fallback; wired into the gate as `MIN_CRASH=31` | `01` §3 |

## 4. Suggested review focus (ordered by risk)

1. **Does the crash-recovery end-to-end close the gap?** The existing L2 self-tests (`workflow.StartupSettleSelfTest` / `workflow.SchedulerRehydrateSelfTest`) `new` an instance in-process and reload via package-private hooks, so they only prove the method is right, not that the `ApiServer` startup entry really calls settle/rehydrate. This round uses a real `kill -9` and a real restart; the assertion points are in `01` §3.
2. **Logic-node layering**: is there a **capability hole** between shrinking the canvas (removing nodes that can be neither saved nor run) and completing the designer (parallel / condition / loop limit); is the two-syntax condition really converged on the backend `ConditionEvaluator` (is the canvas-local `evalCondition` gone); is the camelCase `maxIterations` key consistent between frontend and backend?
3. **Scheduler dispatch ordering**: does `ExperimentScheduler.dispatch()` really reuse the `QUEUE_ORDER` comparator, sharing one ordering with the page snapshot (there was a latent "tests pass, the real order differs" risk)?
4. **Gate reliability**: do "thresholds may only rise" plus the `pre-push` full gate ever produce false reds/greens because of missing environment variables; can the SKIP branches hide real failures?
5. **Test master table self-consistency**: do the constant table / script list / honest list in `docs/en/test-case-master-table.md` match the actual thresholds in `scripts/ci-gate.sh` 100%?
6. **Chinese/English 1:1**: `scripts/verify-en-docs.py` reports 0 hard errors (structure alignment / all links resolve / classified CJK residue); is there still any "keep the old text + append an erratum" style non-self-consistent patch?
7. **The `subProcess` silent-skip exposure**: the designer palette still offers `create.subprocess-expanded`; `subProcess` is not in the parser whitelist, so wiring it into a chain silently skips its successors (the whole run still reports `completed`). This round registered it only, per the ruling, and did not touch the code.
8. **Robustness of the crash-recovery script itself**: the log path is resolved via `pgrep` + `lsof` (avoiding a hard-coded path turning red); the second-restart idempotency assertions avoid brittle "global count" definitions.

## 5. Review question list (please answer these first)

1. Are the crash-recovery end-to-end cases **enough to prove** that the production startup entry really calls settle/rehydrate, or is there still a "testing the script, not the production path" gap?
2. Do the three assertions — "orphan `run` / `experiment_run` settled", "a legitimate `completed` run untouched", "second restart idempotent" — **cover the real crash semantics**? Which boundaries are missing (paused / held / half-written batches)?
3. Is moving logic nodes off the experiment canvas and converging them onto the workflow designer **consistent with the user's position** (the vast majority of logic lives inside one workflow, very little crosses workflows)? Was any legitimate use removed by mistake?
4. After converging the condition syntax on the backend evaluator, **is there still a second evaluation path left in the frontend** (which would cause "the page looks fine but the run takes the wrong branch")?
5. Does the `QUEUE_ORDER` change apply on **all dequeue paths** (normal dispatch / rehydrate / re-queue after a hold is released)?
6. Is the "thresholds may only rise" gate strategy **enough**? Is there a loophole where the assertion count does not drop but the coverage does?
7. Is it acceptable to register the `subProcess` silent skip without fixing it? Of the three candidate fixes (preflight blocking / palette removal / transparent parser), which do you favour?
8. Does the honest list (12 items) in the test master table **faithfully** reflect the gaps? Is any item glossed over?
9. Can the machine cross-check of the Chinese/English 1:1 (structure alignment) replace human review? **What can the machine not see**?
10. What other implicit assumptions remain of "all green in simulation, guaranteed failure on a real device" (especially crash/restart, timing, long tasks)?

## 6. Contents

- `docs/en/review-20261105/01-deliverables-and-verification.md` — the three deliverables grouped by topic, the verification matrix, known gaps, key decisions and pitfalls
- `docs/en/review-20261105/02-remainder-and-next-steps.md` — items on hold / to be decided, follow-up batches and priorities, required inputs
