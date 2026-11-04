# Review Package · 2026-11-04 (for AI review)

> **English edition.** The Chinese original is
> [`docs/评审包-20261104/00-索引与评审指引.md`](../../评审包-20261104/00-索引与评审指引.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are
> identical to the Chinese source — if they ever disagree, the Chinese original wins.

> Purpose: give an external reviewing AI a **self-contained** delivery summary +
> how to reproduce the verification + a list of review questions.
> The historical baseline is in `docs/评审包-20261103/` (parts that already passed the
> previous round are not repeated).

## 1. Snapshot

| Item | Value |
|---|---|
| Repository | `github.com/rogerlzp/labos` (main) |
| Range of the round | `3aa4837..ec28d02`, **24 commits** (M9 closure / S3.2 batched asynchronous execution / P2 status vocabulary / ApiServer split / M10 groundwork / RBAC permission closure) |
| Runtime environment | macOS, Java 17 (no Maven, compiled with `javac` in full), PostgreSQL `"192.0.2.10:5432/labos_demo"` (tenant 1), API `127.0.0.1:18080`, frontend dev `localhost:5173` |
| Data snapshot | experiments 678 / experiment_run 39 / sys_user 3 / running=queued=0 |
| Test baseline | Full run with database **907/0**; pure logic **536/0**; frontend e2e **24/0** |

## 2. How to reproduce (the reviewing AI can execute each item)

~~~bash
cd <repo>
# 1) Pure logic (no DB needed)
./scripts/test-all.sh --pure-only
# 2) Full run with database (needs a reachable DB)
#    Note: the password is not written down here — use your own environment
#    variables (LABOS_DB_HOST/PORT/NAME/USER/PASS)
LABOS_DB_HOST=192.0.2.10 LABOS_DB_NAME=labos_demo LABOS_DB_USER=labos \
LABOS_DB_PASS='<DB_PASSWORD>' ./scripts/test-all.sh
# 3) Gate (pure logic + tsc + schema-artifact consistency + 24 e2e assertions)
./scripts/ci-gate.sh --e2e
# 4) Key integration scripts (real DB + real API + real pages)
python3 scripts/verify-rbac.py                 # 20: role matrix + viewer read-only UI
python3 scripts/verify-exp-batch.py            # 35: batched execution / re-run / batch write-back
python3 scripts/verify-exp-control.py          # 22: experiment-level pause / stop / resume
python3 scripts/verify-scheduler-acceptance.py # 8: M9 scheduling acceptance (mutual exclusion / ordering / concurrency / Gantt page)
python3 scripts/verify-s3-2-residuals.py       # 18: async vars / automatic re-queue / restart rehydrate / UI
# Pre-go-live check on site: the "binding coverage" item needs the DB env (without it: PASS 8 / WARN 3)
LABOS_DB_HOST=192.0.2.10 LABOS_DB_NAME=labos_demo LABOS_DB_USER=labos \
LABOS_DB_PASS='<DB_PASSWORD>' python3 scripts/device-go-live-check.py   # PASS 9 / WARN 2 / FAIL 0
~~~

## 3. Suggested review focus (ordered by risk)

1. **Semantic consistency of the scheduler**: `ExperimentScheduler` (EDD, island-level
   mutual exclusion, MAX_CONCURRENT=4, tick 2s) against the `scheduler/status` /
   `experiments/eta` definitions; whether the new "rehydrate the queue on restart" and
   "automatically re-queue when a hold is released" introduce races or duplicate
   execution.
2. **The S3.2 batched-asynchronous contract**: the CREATED / EXISTING / HELD / NO_MORE
   semantics of `POST /experiments/{id}/run?async=1`, batch-variable inheritance (the
   reason batchNo/batchSamples are stripped), the variable-snapshot semantics; the
   contract and the remainder are in
   `docs/设计-实验编排统一-v0.1-实施清单.md` §11.
3. **Authorisation and last-administrator protection**: the single-transaction
   `pg_advisory_xact_lock` scheme in `RbacRules` + `QueryService.updateUser`; the
   session policy (only a 401 clears the token); the frontend read-only coverage.
4. **ABI / interface compatibility**: after splitting the 3,331-line `ApiServer` into
   679 lines across 8 controllers, are route ownership / dispatch order / HTTP
   behaviour equivalent to before (route-by-route comparison 34/34 before and after; the
   reviewer can re-run the e2e and spot-check the ordering inside a controller)?
5. **A single authority for the status vocabulary**: the backend `StatusVocabulary`
   (including the generated run→experiment migration CASE) against the frontend
   `utils/status.ts` (fallback + API merge); are there still pages that bypass the
   vocabulary and show raw codes?
6. **Test adequacy**: the coverage distribution of the 907 with-database tests; do the
   24 e2e assertions cover the critical chains; is the "thresholds may only rise" gate
   mechanism enough?
7. **Data / startup recovery**: the BUG-14 startup settle (only collects running, the
   single-instance definition), the idempotency of rehydrate, the D8 write-back of
   batch runs.
8. **Correctness of the M10 groundwork**: the model-library metadata API and page
   (MANUAL 2 / PLANNED 6 / 3 running on the line awaiting integration), whether the
   checks in `device-go-live-check.py` are enough for a real site; whether the
   preparation checklist covers the information a manual onboarding needs.

## 4. Review question list (please answer these first)

1. Are there holes in the **concurrency and recovery semantics** of scheduling /
   batched asynchronous execution (restart, hold, aborted, paused boundaries)?
2. Does the **lock scheme** of the last-administrator protection (advisory lock +
   single transaction) work for a single instance and for future multi-instance
   deployments?
3. Is the argument for **dispatch-order equivalence** after splitting `ApiServer`
   into controllers sufficient (prefix anchoring + 34/34 comparison)?
4. Is the **single source** for the status vocabulary still leaking anywhere
   (backend / frontend / documentation / DB enums)?
5. Testing: 907 with-database tests + 24 e2e assertions + 6 integration scripts —
   is the **coverage balanced against the maintenance cost**? Is the gate-threshold
   strategy reasonable?
6. Are the M10 preparation checklist and the model-library groundwork enough to
   support "the manual arrives and we can onboard immediately"? Which material or
   fields are missing?
7. Is the **priority and dependency structure** of the follow-up plan
   (`02-后续规划.md`) reasonable? Is there a risk item that should be done earlier?
8. Security: are there points that need hardening in the RBAC write protection, user
   management, and the session policy?
9. Data consistency: is the boundary between the startup settle and the D8
   write-back / derived states / read-side derivation clear?
10. Are there still implicit assumptions of "all green in simulation, guaranteed
    failure on a real device" (especially error codes / timing / long tasks)?

## 5. Contents

- `01-本轮交付清单与验证.md` — the 24 commits grouped by topic, key decisions,
  verification matrix, known gaps
- `02-后续规划.md` — inventory of the remaining work, phased plan (A start now /
  B waiting for material / C platformisation / D continuous), dependencies and
  required inputs
