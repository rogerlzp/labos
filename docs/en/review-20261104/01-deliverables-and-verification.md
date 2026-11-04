# Delivery List and Verification for This Round (2026-11-03 evening → 11-04 early morning)

> **English edition.** The Chinese original is
> [`docs/评审包-20261104/01-本轮交付清单与验证.md`](../../评审包-20261104/01-本轮交付清单与验证.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are
> identical to the Chinese source — if they ever disagree, the Chinese original wins.

> Range: `3aa4837..ec28d02`, 24 commits (this review package adds 1 docs commit).
> Each topic gives "what was done / key decisions / how to verify"; every number was
> actually measured.

## 1. Topic A · M9 closure + BUG-14 / BUG-15 (3 commits)

**What was done**
- M9 ("schedule Gantt / EDD + manual queue-jumping / estimated wait / scheduler page")
  checked off and closed; added `scripts/verify-scheduler-acceptance.py`:
  3 experiments on one island mutually exclusive + dispatch order == submission order
  (log completion order), 3 rounds across islands sampling maxRunning=3, Gantt page
  rendering — measured 8/8.
- **BUG-14** (historical running runs skewing the ETA): `StartupRecovery.settleOrphanExperimentRuns`
  marks leftover running runs as failed at startup (batches go through D8, non-batches
  through syncExperimentStatus; a single-instance definition in the comment); measured:
  the first restart clears 2 rows, the second restart clears 0 (idempotent), remaining
  running = 0.
- **BUG-15** (test scripts leaking 150 orphan processes): `test-sidecar.sh` /
  `verify-device-chain.sh` now start the real process with `exec` + the sidecar uses
  `exec dotnet <dll>` + cleanup adds `pkill -P`; measured: the process count is
  unchanged before and after test-all (150 → 150), and after cleanup 0 with no
  resurrection.

**Verification**: with database 784 → 860/0; the gate passes; the debugging acceptance
8/8; environment cleanup 150 → 0; BUG ledger -14 closed / -15 registered as closed.

## 2. Topic B · S3.2 batched asynchronous execution + remainder (8 commits)

**The contract** (details in `docs/设计-实验编排统一-v0.1-实施清单.md` §11)
- `POST /experiments/{id}/run?async=1`: a non-batched experiment runs as one whole run
  as before; a batched one → CREATED / EXISTING (can be queued), HELD (held at the batch
  boundary, `experimentRunId=null`), NO_MORE (no further batches).
- Re-queue semantics: a failure continues / aborted stops / a hold stops at the
  boundary; the D5 batch variables are injected by the scheduler.
- Remainder closed out: startup `rehydrateQueued()` restores queued runs from the
  database (idempotent); automatic re-queue when a hold is released (every 5 ticks it
  scans paused batches without an attempt, so no manual re-POST is needed); async vars
  are written to `experiment_run.variables` and used as execution overrides (a re-queue
  inherits them and strips batchNo/batchSamples); the read-side derivation for paused
  batches (completed > active > storedStatus=paused > latest attempt).
- The frontend gets a "run asynchronously" entry point for batched experiments, with a
  distinct message for each of the three 200 responses (queued / held on site / no
  pending batch).

**Verification**: BatchDbSelfTest 202 → 244, StartupSettle 26, Rehydrate 14;
`verify-s3-2-residuals.py` 18/18 (including killing and restarting the API to verify
rehydrate ≥ 1 and a second restart = 0); frontend UI cases cover hold / release
auto-re-queue / the queued message.

## 3. Topic C · P2 status-mapping consolidation (4 commits)

**What was done**
- Backend `StatusVocabulary` (the single authority): code/label/color/terminal/active
  for run11 / node9 / step6 / experiment12 / batch7, plus pauseKinds 3 / holdKinds 4 /
  holdScopes 3 and 8 runToExperiment entries; `GET /api/meta/status-vocabulary`; the SQL
  CASE of `syncExperimentStatus` is generated from that mapping (the write side and
  the display side share one source — the root of BUG-13/14).
- Frontend `utils/status.ts`: FALLBACK (field-by-field identical to the contract) +
  merge of the react-query fetch; 10 local mappings deleted across 8 pages; the status
  columns of the run history / attempts / batches / board now show Chinese labels
  (colours unchanged).

**Verification**: StatusVocabularySelfTest 20; with database 860/0; the endpoint is
deep-equal to the contract; tsc/build; the vocabulary endpoint returns 200 when
exercised on the page.

## 4. Topic D · Splitting ApiServer into controllers (1 commit + documentation)

- 3,331 lines → **679 lines**; 8 controllers in the same package: AuthApi 127 /
  DeviceApi 478 / SchedulerApi 164 / RunApi 509 / WorkflowApi 909 / OpsApi 553 /
  ExperimentApi 638 / MetaApi 132 (3,510 lines in total; after the split MetaApi grew
  from 109 to 132 with `f2d7e8e`).
- The dispatch order equals the original first-occurrence order of `route()`; inside a
  controller the original if/else and Matcher order is preserved.
- **Equivalence evidence**: a pre-split grep snapshot shows all 58 Pattern compile
  points and 156/156 business route blocks have an owner (0 unmoved; Pattern 58 → 58,
  equals/startsWith 54 → 54 identical); the pre-split original was started separately
  on 18081 against the new one on 18080, on the same database, and compared route by
  route: **34/34** GET response status codes + JSON identical
  (401×4 / 405 / OPTIONS / login structure identical).

**Verification**: javac 0; with database 860/0; e2e 20/0; exp-batch 35/0; exp-control
22/0.

## 5. Topic E · M10 groundwork (4 commits, the real-device part deferred)

- `GET /api/meta/device-models`: `DeviceModels.Planned` gains `dbModelAlias`
  (360R / PlateLoc); manual details (evidence / manual / command count including the
  unverified ones / error-code count / timeout) + planned details (what is missing /
  the name used in the database / how many units run on the line, one GROUP BY).
- Frontend "model library" page `/models` (operations menu): 2 integrable / 6 awaiting
  a manual / **3 units running on the line awaiting integration** (360R 1 + PlateLoc 2,
  marked in red); the e2e gains a 7th step (assertions 20 → 23).
- `scripts/device-go-live-check.py`: PASS/WARN/FAIL checks before on-site integration
  and go-live (heartbeat freshness, number of drivers online, driver endpoints, model
  manual coverage, device→model binding rate, EC-08 error-code coverage, capability
  list, topology); on this machine with the simulation: PASS 9 / WARN 2 / FAIL 0 (the
  WARNs are expected before M10).
- `docs/设备/M10-手册到型号库接入准备清单-v0.1.md`: an 18-item material list, 7
  parameter-extraction templates, a 10-step onboarding DoD, a first draft of the
  long-task "busy vs dead" rule, the error-code feedback loop, gaps G1–G6, risks and 8
  open questions, and a 360R / PlateLoc follow-up table.

## 6. Topic F · RBAC permission closure (6 commits, the M11 permission basics first)

- Backend: `RbacRules` (role whitelist / write permission / user-management right /
  role labels / `lastAdminGuard`); role validation in createUser/updateUser; protection
  of the last administrator (the only enabled admin cannot be disabled or demoted);
  **transaction lock added**: `pg_advisory_xact_lock(hashtext('labos:user-role'))` +
  read-modify-write in a single transaction (concurrent demotions: at most one
  succeeds). Decision: do not enable the legacy `app_role` / `role_permission` tables
  (avoid two tracks).
- Frontend: AuthProvider/useAuth (session restored via `/api/auth/me`); a role Tag and
  displayName in the header; the user-management menu is admin-only; the viewer's
  read-only disable covers the write operations of 13 pages (experiment / paused queue /
  error codes / policies / workflows / canvas / designer / run detail / alerts / plate
  positions / devices and islands / island orchestration / Gantt drag / live board and
  more); session-failure policy: **only a 401 clears the token**, other failures show
  "session restore failed + retry".
- Tests: `verify-rbac.py` 20/20 (role matrix + viewer read-only UI); RbacRulesSelfTest
  15 + UserRoleDbSelfTest 10; an e2e header role assertion (23 → 24); a real admin
  demotion/disable measured 400 with the database unchanged.

## 7. Verification matrix (the most recent run)

| Command | Result |
|---|---|
| `./scripts/test-all.sh` (full run with database) | **907 / 0** |
| `./scripts/test-all.sh --pure-only` | **536 / 0** |
| `./scripts/ci-gate.sh --e2e` | **Gate passed**: 536/0, tsc 0, schema artifacts same-source, e2e **24/0** |
| verify-rbac.py | 20/20 |
| verify-exp-batch.py | 35/0 |
| verify-exp-control.py | 22/0 |
| verify-scheduler-acceptance.py | 8/8 |
| verify-s3-2-residuals.py | 18/18 |
| device-go-live-check.py | PASS 9 / WARN 2 / FAIL 0 (expected before M10) |

## 8. Known gaps / risks (worth the reviewer's attention)

| # | Item | Current state | Impact |
|---|---|---|---|
| 1 | M10 real-device integration (360R / PlateLoc manuals) | **Deferred, waiting for material**; the preparation checklist, the model-library page/API and the go-live check are already in place | The real-device chain cannot be verified |
| 2 | Long-task "busy vs dead" rule (30-minute incubation) | A first draft in the documentation, not yet in code (waiting for the manual's busy/idle query command) | Long real-device tasks may time out or be resent wrongly |
| 3 | Tooling for error-code feedback | The process is written down, but there is no automated intake / simulator injection | On-site codes still have to be entered by hand |
| 4 | Uncovered write entry points (frontend read-only experience) | The alert silence API exists in the backend but has no UI; a few pages can still be covered | Viewers still hit 403s (safe, but the experience is incomplete) |
| 5 | Stale documentation items | ROADMAP M6/M7/M8 still contain completed items that are not ticked | A reviewer can misread the progress |
| 6 | The legacy `app_role` / `role_permission` tables | Decision: not enabled (`sys_user.role` is in effect) | The legacy tables can confuse a reviewer; the documentation has to be explicit |
| 7 | Session-restore failure policy | A non-401 failure stops on the retry page (the token is kept) | Not operable while the kernel is restarting (acceptable; open question whether it should become read-only) |
| 8 | Multi-tenancy / reporting / multi-instance | Not done (the remaining M11 items) | A platformisation gap |

## 9. Environment and data snapshot

- API `127.0.0.1:18080` /health ok; frontend dev `localhost:5173` 200.
- DB: experiment 678 / experiment_run 39 / sys_user 3 / running=queued=0 in both tables
  / no rbac test residue.
- git: main is clean; the remote origin is `github.com/rogerlzp/labos.git`.

## 10. New or updated key documents

- `docs/设计-实验编排统一-v0.1-实施清单.md` §11 (the S3.2 contract and the remainder)
- `docs/多实验并行调度-设计.md` §7 (the scheduling observability contract)
- `docs/设备/M10-手册到型号库接入准备清单-v0.1.md` (M10 groundwork)
- `docs/开发日志.md` (an item-by-item record of this phase), `ROADMAP.md` (the
  authority on progress), `docs/评审记录/BUG台账.md` (-14 / -15 / -16)
