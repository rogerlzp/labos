# BUG Ledger · LabOS-Kernel

> **English edition.** The Chinese original is [`docs/评审记录/BUG台账.md`](../../评审记录/BUG台账.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **Purpose**: register the defects found in each round of review and track them one by one
> until they are closed. **Append only — historical verdicts are never edited or deleted**;
> status changes go in the "remediation" column.
> **Maintenance convention**: id `BUG-<date>-<sequence>`; severity `blocking / high / medium / P2 (self-consistency)`; the fix is done by the implementer, the reviewer only registers and re-checks.
> **Related**: the original evidence for each entry is in the corresponding review record (`docs/评审记录/`).

---

## 1. Overview

| Id | Severity | Summary | Location | Status | Found in |
|---|---|---|---|---|---|
| `BUG-20261103-01` | **blocking** | The install artifact `90_all_in_one.sql` was not regenerated with M6: `run_node.loop_index` is missing, the `parent_node_id` foreign key is missing, and `uk_run_node` is still 3 columns | `pg_rebuild/schema/90_all_in_one.sql` | ✅ **Closed** (reviewer re-check 2026-11-03 13:21, see `20261103-02` §7) | `20261103-02` |
| `BUG-20261103-02` | P2 (self-consistency) | The testing document §8 writes the device-chain assertion as **34**, while the other 4 places in the same document write **37** | `docs/评审包-20261103/04-测试文档.md` | ✅ Fixed | `20261103-02` |
| `BUG-20261103-03` | P2 (self-consistency) | The technical document §2.1 misstates the package file counts: driver/workflow/sim/device/expr are all too high, the itemised total is **113 ≠ 107** | `docs/评审包-20261103/02-技术文档.md` | ✅ Fixed | `20261103-02` |
| `BUG-20261103-04` | P2 (self-consistency) | The technical document §2 cites "design document **§6**" — that section does not exist | `docs/评审包-20261103/02-技术文档.md` | ✅ Fixed | `20261103-02` |
| `BUG-20261103-05` | P2 (self-consistency) | The index/technical documents write the `run_node` unique key as `(run_id,node_key,attempt)`, missing `loop_index`, contradicting the "loop_index has been added" in the same document | `docs/评审包-20261103/00-索引与评审须知.md`、`02-技术文档.md` | ✅ Fixed | `20261103-02` |
| `BUG-20261103-06` | P2 (self-consistency) | The design document places **D15** **after** §3/§4/§5, the ADR order is broken | `docs/评审包-20261103/03-设计文档.md` | ✅ Fixed | `20261103-02` |
| `BUG-20261103-07` | P2 (self-consistency) | The index §5.1 #2 and the design document §5 p2 still list the **already completed M6** (one row per loop iteration of `loop_index`) as not done | `docs/评审包-20261103/00-…`、`03-…` | ✅ Fixed | `20261103-02` |
| `BUG-20261103-08` | **blocking** | `90_all_in_one.sql` does not install cleanly on a **brand-new database**: 22 `already exists` (4 identity sequences created twice, 5 `multiple primary keys`, several duplicated constraints). `deploy.sh:112` uses `ON_ERROR_STOP=1` and aborts at the first one | `pg_rebuild/schema/30_business.sql` (the pg_dump-style tail and the hand-written DDL define the same set of tables twice) | ✅ **Closed** (reviewer re-check 2026-11-03 13:21; "clean install with a live database exit=0" is the implementer's own measurement, the reviewer had no PostgreSQL locally and did not reproduce it, see `20261103-02` §7) | `20261103-02`, found during the re-check (implementer's measurement) |
| `BUG-20261103-09` | P2 (maintenance) | 5 tables are declared twice in the artifact (`plate_well` / `run_variable` / `run_artifact` / `experiment_sample` / `resource_booking`). **The column sets are equivalent, no side effect**; the hazard is "editing the wrong copy does not raise an error" | `pg_rebuild/schema/30_business.sql` | ✅ **Fixed** (2026-11-03 14:0x, structural diff is 0) | `20261103-02` re-check |
| `BUG-20261103-10` | **high** | `ExperimentExecutor` writes `experiment_run.id` into `experiment.run_id`, but that column's foreign key points at `run(id)` — the two tables have independent auto-increment sequences, they are not one id space; as soon as `experiment_run.id` lands in a gap of `run`'s ids, `23503` is raised | `labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java` | ✅ Fixed (`9c1f65d`) | Found by the implementer's own testing (2026-11-03) |
| `BUG-20261103-11` | **medium** | The frontend e2e main chain 4 "experiment orchestration" has been **failing ever since** `051f4d1` (menu renamed to "experiment list" + list/detail split into two layers): the script still looks for the "experiment orchestration" menu. And the gate never runs with `--e2e`, so the "5 main chains" were untrustworthy for a long time | `scripts/e2e-smoke.py` | ✅ Fixed (`f335a10`: changed to list → click the row → detail, and it now covers the "on-site hold" / run-control column; all 16 pass) | Found by the implementer's own testing this round (2026-11-03) |
| `BUG-20261103-12` | **medium** | The experiment-level pause interface echoes the **requested** scope instead of the **actually effective** one: when an experiment with no island assigned requests `island`, the backend degrades to `experiment`, but the response still writes `"scope":"island"` — the UI says "Scope: island" while the database only holds an experiment-level hold, so the operator will wrongly believe the whole island has stopped | `RunControl.java` `RunControl.pause`, the `pause` branch of EXP_RUN_ACTION in `ApiServer.java` | ✅ Fixed (`046836d`: `pause` returns the scope that actually took effect, the interface echoes that value) | Found by the implementer during joint debugging (2026-11-03) |
| `BUG-20261103-13` | P2 (self-consistency) | Two status vocabularies are mixed: `experiment_run.status` uses `completed`, `experiment.status` uses `finished`; the batched execution writes the **aggregated experiment status** into the top-level `status` of `POST /experiments/{id}/run`, but the frontend `runExp` only accepts `completed`, so a fully successful batch pops up a **warning** ("the whole batch succeeded but it is reported yellow") | `webapp/src/pages/Experiments.tsx` `runExp` | ✅ Fixed (`fd59d90`: both `completed` / `finished` count as success, the batch hint carries the batch count) | Found by the implementer during joint debugging (2026-11-03) |
| `BUG-20261103-14` | **medium** | The historical leftover `experiment_run.status='running'` (EXP0812's run#2/#3, started 2026-11-01) makes the new "estimated experiment wait" misjudge an experiment that ended long ago as **running**: wait=0, and every experiment queued behind it is estimated with "it is holding the island". The root cause is that the early ETA judged "running" by `experiment_run.status`, while the run status itself can be stale | `labos-kernel/src/main/java/com/labos/kernel/workflow/SchedulerEta.java`、`docs/多实验并行调度-设计.md` §7.2 | ✅ **Closed**: the judgement `f7120a2` (running = the scheduler's island-lock holder + `experiment.status` as fallback, run.status is not consulted) + the startup fallback settle `3aa4837` (on the first restart run#2/#3 are automatically settled to failed, a second restart yields 0 rows, idempotent); the remaining running=0, EXP0812 is no longer misjudged | Found by the implementer during joint debugging (2026-11-03) |
| `BUG-20261103-15` | **medium** | The device-chain test script **leaks processes**: `test-sidecar.sh` starts with `( cd … && java … ) &` (`$!` is the subshell, kill cannot reach the java process), and `dotnet run` spawns yet another child process; `verify-device-chain.sh` does the same → every run of the self-test/acceptance leaks a simulator + sidecar, and this cleanup has accumulated **150 orphan processes** (PPID=1, the oldest at 19:02) | `scripts/test-sidecar.sh`、`scripts/verify-device-chain.sh` | ✅ Fixed (`e378b91`: always `exec` to start the real process, the sidecar changed to `exec dotnet <dll>` and no longer depends on apphost/DOTNET_ROOT, the cleanup adds a `pkill -P` fallback; measured: the process count is unchanged before and after a full test-all run, 0 after the cleanup and none come back) | Found by the implementer during environment cleanup (2026-11-03) |
| `BUG-20261103-16` | **medium** | Three gaps in the permission closure: ① the roles have no whitelist (an account can be created with role=boss); ② there is no last-administrator protection (the only admin can be deactivated/demoted → the system locks itself up); ③ the frontend has no role awareness (a viewer clicking a write action only gets a 403, and the user-management menu is still visible) | `labos-kernel/.../api/RbacRules.java`、`QueryService.java`、`webapp/src/auth.tsx` | ✅ Fixed (`afddf5b` backend RbacRules + whitelist + last-administrator protection; `3339606` frontend AuthProvider / role header / read-only experience; `19bb67b` joint-debug script 20/20; measured: demoting/deactivating a real admin returns 400 and the DB is unchanged; the closing touches `4e3baec` transaction lock + `ec28d02` session-failure retry / read-only fallback) | Found by the implementer in the permission-closure inventory (2026-11-03) |
| `BUG-20261104-01` | **blocking** | The e2e "24/0" is not reproducible (measured **23/1**, same result twice): the first row of the experiment list (`ORDER BY id DESC`) is the leftover experiment 962 (**0 runs**), the run-history table has no expandable row → the assertion fails; the script **depends strongly** on the data distribution of the first row | `scripts/e2e-smoke.py` | ✅ **Closed** (2026-11-04: step 4 of the script now walks the rows and takes the first experiment with "an expandable run history", trying at most 8 rows, which removes the first-row dependency; after the leftovers were cleared a re-run gives **24/0**) | `20261104-01` |
| `BUG-20261104-02` | **high** | The RBAC joint-debug leftovers pollute the database: `sys_user` = 5 (the extra `rbac_op_22244` / `rbac_viewer_22244`) + leftover experiment 962; this violates `01` §9 "no rbac test leftovers"; the root cause is that `verify-rbac.py` only deletes experiments from the `created_exp` list, so a killed process leaves rows behind (the experiments have no fallback by name) | `scripts/verify-rbac.py`、`docs/评审包-20261104/01-本轮交付清单与验证.md` §9 | ✅ **Closed** (2026-11-04: `verify-rbac.py`'s finally block gained a fallback that deletes experiments by name; the leftovers were also cleared → `sys_user` 5→**3**, `experiment` 679→**678**, matching the `01` §9 snapshot) | `20261104-01` |
| `BUG-20261104-03` | P2 (self-consistency) | The review package writes "`3aa4837..ec28d02`, **25 commits**"; the `A..B` git semantics **do not include A**, the real number is **24** (25 only when the base is counted) | `docs/评审包-20261104/00-索引与评审指引.md`、`01-本轮交付清单与验证.md` | ✅ **Closed** (2026-11-04: `00` §1/§5 and the `01` header 25→**24**; measured `git rev-list --count 3aa4837..ec28d02` = 24) | `20261104-01` |
| `BUG-20261104-04` | P2 (self-consistency) | The review package writes `MetaApi 109` (total **3487**); on HEAD MetaApi has grown to **132** (total **3510**) with `f2d7e8e` (device-models) | `docs/评审包-20261104/01-本轮交付清单与验证.md` §4 | ✅ **Closed** (2026-11-04: `01` §4 changed to `MetaApi 132` (total **3510**), noting that 109→132 was introduced by `f2d7e8e`; measured, the 8 controllers total 3510) | `20261104-01` |
| `BUG-20261104-05` | P2 (self-consistency) | The go-live re-run command (`00` §2) **carries no DB env**, but `01` §7 reports the 9/2 **with** the DB; without the env it is really **8/3** ("binding coverage rate" is skipped), so the command and the result use different yardsticks | `docs/评审包-20261104/00-索引与评审指引.md` §2、`01-本轮交付清单与验证.md` §7 | ✅ **Closed** (2026-11-04: the `00` §2 go-live command gained the DB env precondition and is annotated with 8/3 without the env, PASS 9 / WARN 2 / FAIL 0 with it; both yardsticks reproduced by measurement) | `20261104-01` |
| `BUG-20261107-01` | **high** | An experiment-level pause does not stop the underlying workflow-level single-flow `run`: the node boundary only consults `run.status` and cannot see the on-site hold (`runtime_hold`) ⇒ the two experiment-level layers are set to paused while the underlying run finishes all its nodes as before; after the fix, on resume the stale `paused` run would be mistaken for a resumable one and re-execute the device action | `WorkflowService.withPauseChecker` / `RuntimeHold.coveringForWorkflow` / `RunControl.resume` | ✅ **Fixed** (2026-11-07: the node-boundary predicate is widened to "run paused OR a hold exists"; on resume, stale runs of this experiment step that are still paused are voided) | Found by the implementer in the delivery re-check (2026-11-07) |

> **Table structure repair (2026-11-03 evening)**: `-08` / `-09` were separated from the overview table by blank lines and the "remediation" section, and the `-09` / `-10` rows had no table header, so Markdown did not render them as a table. This round only moved the rows; the verdict/status/evidence text was not changed.

#### Remediation: BUG-20261103-08 (implementer · 2026-11-03)

| Item | Result |
|---|---|
| Means | `merge.sh` gained a `guard_dup_decl.py`: it wraps the **duplicate-declaration** statements (`ALTER … ADD GENERATED`, `ALTER TABLE [ONLY] … ADD CONSTRAINT`) in `DO $$ … EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition`, and on a hit it `RAISE NOTICE`s and skips |
| Why not delete the duplicate definitions | The hand-written DDL and the dump tail are **interleaved** in the shards, so deleting a block easily deletes the 23 tables that only exist in the tail; with a guard, "the object does not exist → create it, it exists → do not blow up", the semantics are unchanged |
| Brand-new-database verification | Full execution of the artifact in an empty schema, `ON_ERROR_STOP=1`: **exit=0, ERROR=0** (was 22) |
| Objects created | 105 tables + 5 views (the live 107 = 105 + 2 historical monthly partitions from 2026-09), `run_node.loop_index` / the 4-column uk / the parent foreign key are all present |
| Idempotence | The guard only affects duplicate declarations; neither a fresh install nor a repeated execution raises an error |

#### Remediation: BUG-20261103-11 / -12 (implementer · 2026-11-03)

| Item | Result |
|---|---|
| How -11 broke | `051f4d1` renamed the menu "experiment orchestration" to "experiment list", and folded the experiment into two layers, "list page → detail page"; T4 of `e2e-smoke.py` still clicked the "experiment orchestration" menu, 0 matches → 15s timeout |
| Why nobody noticed -11 for so long | The e2e stage of `ci-gate.sh` does not run by default (it needs `--e2e` and a running frontend/backend); in the rounds after `051f4d1` the gate only ran "pure logic + tsc", so the "5 main chains" were green only on paper |
| The -11 fix | T4 changed to: experiment list → click the row → detail → assert the "on-site hold" card / the run-control column → expand the run history; measured 16 items / 0 failures (threshold 14, the script has 14) |
| The -12 symptom | An experiment with no island assigned requests an `island` pause: the backend's `effectiveScope` degrades to `experiment`, but the interface echoes the requested value `island` straight back to the frontend |
| The -12 fix and its measurement | `pause` returns the scope that actually took effect; the joint debugging of that experiment showed "Scope: this experiment", the hold table's range = "this experiment #813", matching the experiment-level hold in the database |
| How to re-run | `scripts/verify-exp-control.py` (added this round, `3ab54a3`): it automatically borrows an empty experiment, inserts 2 queued runs, walks pause → resume → stop through the interface, deletes the data in the finally block and restores the state; 22 assertions |

> The entries already closed (✅) are **document self-consistency corrections**; the remediation actions are recorded in `docs/评审记录/20261103-02-评审包复核.md` §7.
> `BUG-20261103-01` / `-08` were closed by the reviewer on 2026-11-03 13:21 (see `20261103-02` §7);
> among them, the "brand-new database exit=0/ERROR=0" of `-08` is the implementer's own measurement — the reviewer had no PostgreSQL locally and could not reproduce it, and this has been recorded faithfully.

---

## 2. Details

### 🟥 BUG-20261103-01 (blocking) The install artifact is stale — the path that gets deployed did not follow M6

**Symptom**: `pg_rebuild/deploy.sh:27` deploys the merged artifact `90_all_in_one.sql`, but that file is stuck **before the M6 fix**.

**Evidence (git timestamps + content comparison)**:

| File | Latest commit | `run_node`'s `loop_index` | `uk_run_node` | `parent_node_id` foreign key |
|---|---|---|---|---|
| `schema/20_kernel.sql` (shard, correct) | `6fb38d3` 11-03 11:18 | present (:309) | 4 columns `(…,loop_index)` (:310) | — |
| `schema/96_schema_fix.sql` (patch, correct) | `6fb38d3` 11-03 11:18 | present (:120) | rebuilt as 4 columns (:127) | present (:112-115) |
| **`schema/90_all_in_one.sql` (deployed artifact, stale)** | **`43317da` 11-02 21:33 (earlier than M6)** | **absent** | **3 columns** (:557) | **absent** (:2488 only adds the column) |

**Root cause**: M6 (`6fb38d3`) changed the shards and `merge.sh`, but **did not re-run `merge.sh`** to regenerate the merged artifact.

**Impact**: after starting with a new database —
1. the ~22 queries in [QueryService.java](https://github.com/rogerlzp/labos/blob/7524a94d853076a601bb87ad6a0e8bc45c31d8d2/labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java) that reference `run_node.loop_index` (run list / detail / flow chart / timeline) fail with a SQL error directly (the column does not exist);
2. `parent_node_id` has no foreign key → the structural precondition for the cascade delete of the step rows is missing (the H1-class orphan-row risk reappears);
3. `uk_run_node` goes back to 3 columns → in a loop scenario the same node **hits the unique key** when it is inserted across iterations (M6 is cancelled out).

This is exactly the **"dual paths"** defect the project keeps repeating: the code path has been changed, the path that gets deployed has not.

**Fix (implementer)**:
- Re-run `pg_rebuild/schema/merge.sh` to regenerate `90_all_in_one.sql`, and check that it contains `loop_index` / the foreign key / the 4-column uk;
- add a "**merge artifact vs shard consistency**" assertion to the CI/regression baseline, to prevent the drift from recurring.

**Closure condition**: `90_all_in_one.sql` has the same source as `20_kernel.sql + 96_schema_fix.sql`; the database built by `deploy.sh` contains `run_node.loop_index` and `uk_run_node(run_id,node_key,attempt,loop_index)`. **Needs verification against a live database**.

#### Remediation (implementer · 2026-11-03)

| Item | Result |
|---|---|
| Re-ran `merge.sh` | ✅ artifact 2531→2536 lines; same source as the shards (`git diff` only 30 lines: `loop_index` + the 4-column uk + the foreign-key DO block + 5 restored `ALTER TABLE ONLY plate_well` that had been lost) |
| The artifact contains `loop_index` | ✅ `grep '^\s*loop_index\s+smallint'` hits |
| The artifact contains the 4-column uk | ✅ `CONSTRAINT uk_run_node UNIQUE (run_id, node_key, attempt, loop_index)` |
| The artifact contains the parent foreign key | ✅ `run_node_parent_node_id_fkey ... ON DELETE CASCADE` |
| **Live-database verification** (full execution of the artifact in a new schema) | ✅ `run_node.loop_index` exists; `uk_run_node = UNIQUE (run_id,node_key,attempt,loop_index)`; `run_node_parent_node_id_fkey = FOREIGN KEY (parent_node_id) REFERENCES run_node(id) ON DELETE CASCADE` |
| Drift assertion | ✅ new `scripts/check-schema-sync.sh` (re-run merge + compare + assert the three elements), merged into the 2.5th gate of `ci-gate.sh`; the negative test passes (putting the stale artifact back raises an error and restores the working tree) |

> Incidentally: the 22 `already exists` that a brand-new database produced when executing the stale artifact have been fixed together with `BUG-20261103-08`
> (a guard was added to `merge.sh`), so an empty database now executes with **0 errors** and the deployment path is clean too.

---

### ✅ BUG-20261103-02 ~ 07 (document self-consistency, fixed)

| Id | Original value → new text | Explanation |
|---|---|---|
| `-02` | Device-chain assertion `34` → `37` | Aligned with the other 4 places in the same document (37) and with `ci-gate.sh MIN_CHAIN=37` |
| `-03` | driver 25→23 / workflow 21→20 / sim 8→7 / device 7→6 / expr 6→5 | Consistent with the measured `find … \| wc -l` per package, the itemised total = 107 |
| `-04` | "design document §6" → "design document §5" | §5 "if I had to refactor, I would start here" is the ApiServer split entry |
| `-05` | `uk_run_node (run_id,node_key,attempt)` → add `loop_index` | Consistent with `20_kernel.sql:310` (M6), removes the contradiction inside the same document |
| `-06` | D15 from after §5 → moved between D14 and §3 | The ADR section is contiguous, the conclusion section comes after |
| `-07` | 00 §5.1 #2 / 03 §5 p2 marked **done (M6)** | Removes the contradiction with the M6 milestone (00 §8) and with the code |

---

**Maintenance sign-off**: Trae AI (DeepSeek-V4.1-Flash) · 2026-11-03 12:50 CST

**Re-check sign-off (the `-01` / `-08` closure ruling)**: Trae AI (underlying model family: DeepSeek-V4.1-Flash) · 2026-11-03 13:21 CST · `main @ 39d352c`
> Re-check method: static structural verification + a re-run of `check-schema-sync.sh` / `ci-gate.sh`; **no live database** (no PostgreSQL server on this machine). Ruling: **both are closed**; the `-08` live-database item is marked as the implementer's own measurement, not reproduced by the reviewer.

---

### BUG-20261103-09 remediation (implementer · 2026-11-03 14:0x)

**Approach**: delete the **duplicate definitions** of the 5 tables in `30_business.sql` (each one a `CREATE TABLE` + the immediately following
`ALTER … ADD GENERATED` + that table's `pkey` constraint), keeping the canonical copy:

| Table | Canonical location | Deleted location |
|---|---|---|
| `plate_well` | `30_business.sql:78` | the dump-style copy after it |
| `experiment_sample` | `30_business.sql:253` | the dump-style copy after it |
| `resource_booking` / `run_variable` / `run_artifact` | `20_kernel.sql` | the copies in `30_business.sql` |

The foreign keys (`*_fkey`) are **kept untouched** — they only appear in the dump section, and deleting them would lose constraints.

**Verification**: a schema was built from scratch before and after the change, `pg_dump --schema-only` compared them,
**line for line identical apart from pg_dump's random `\restrict` marker** (zero structural difference); a brand-new install with `ON_ERROR_STOP=1` still exits 0 / ERROR=0.

Number of table definitions in the artifact: 86 → **80** (the 5 duplicates are gone, and there is 1 more difference in how the entity tables are counted).

---

### BUG-20261103-10 remediation (implementer · 2026-11-03)

**Symptom**: both `markRunning` and `syncExperimentStatus` in [ExperimentExecutor.java](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentExecutor.java) write `experiment_run.id` into `experiment.run_id`.

**Evidence (measured against a live database `192.0.2.10:5432/labdb`)**:

| Item | Value |
|---|---|
| Foreign key | `experiment_run_id_fkey = FOREIGN KEY (run_id) REFERENCES run(id)` |
| `experiment_run` row count | 39, and **all** of them find a row with the same id in `run` (`not exists` = 0) |
| `max(experiment_run.id)` | 39 |
| `max(run.id)` / `run_id_seq.last_value` | 205486 / 205494 |
| The contiguous stretch of `run` | id **1..197 contiguous**, **gaps start at 198** (`first_missing_run_id = 198`) |
| Trigger condition | When `experiment_run.id` reaches 198, the start raises `23503` (currently 39 → about **158** more experiment runs before that) |

**Root cause**: `experiment_run` and `run` **auto-increment independently**, they are not one id space; `experiment_run.id` happened to stay
inside the contiguous stretch of `run`, so nothing has blown up in production — it is a **deferred certain failure**. It was introduced by the
existing commit `862f52a` ("feat: error code persistence + experiment status synchronisation"), **not by this round's work**.

**Fix**: `run_id = r.id` removed from both places, the `status / started_at / finished_at / updated_at` synchronisation is kept, and a comment
explains why. A repository-wide search confirms that no consumer reads `experiment.run_id` (`QueryService` / `webapp` / `docs` have no hits), so
the removal is safe.

**Verification**: `RunControlDbSelfTest` gained the assertion "after the synchronisation `experiment.run_id` stays NULL", and the placeholder `run`
row that had been inserted to work around the foreign key was removed; the full live-database `test-all.sh` is **500 / 0**, and the `ci-gate.sh`
gate passes (pure logic 423/0).

**Closure condition**: `experiment.run_id` stays constantly `NULL` on the experiment status synchronisation path; if a backfill is ever genuinely
needed, it must write `run(id)` and not `experiment_run(id)`.

---

### BUG-20261107-01 remediation (implementer · 2026-11-07)

**Symptom**: an experiment-level pause only sets the two layers `experiment_run` / `experiment` to `paused` (and raises an on-site hold `runtime_hold`); the underlying workflow-level `run` is unaffected. At a node boundary the executor `GraphRunner` only consults `run.status`, cannot see the hold, and therefore runs all the remaining nodes as before.

**Root cause**: an experiment-level pause lands a **side-channel state** (a hold), decoupled from "whether a given run has stopped"; the node-boundary gate only read `run.status` and did not take "the site is held" into account.

**Fix (two places, both on the execution side)**:

| Place | File | Approach |
|---|---|---|
| ① Stop at the node boundary | `WorkflowService` | The `withPauseChecker` predicate is widened from "`run.status='paused'`" to "`run.status='paused'` **or** `RuntimeHold.coveringForWorkflow(workflowId) != null`"; on a hit `pauseRunAtBoundary` sets the underlying run to `paused` and the following nodes stay `pending`. **Independent of `interruptible`, effective by default** |
| ② Void the stale run on resume | `RunControl.resume` | An experiment-level pause also sets the current step's workflow-level run to `paused`; on resume that step re-runs from the top and starts a new run, so the old `paused` run becomes an orphan that sits in `PauseManager.pausedRuns` and, if an operator clicks "continue", redoes the device action (an irreversible physical side effect). So on resume the runs of "this experiment step and still `paused`" are voided to `aborted` |

**Why the reverse lookup matters**: `RuntimeHold.coveringForWorkflow` resolves ownership by **`experiment_run_step.status='running'` + `workflow_id`**, and it must **not** filter by `experiment_run.status='running'` — an experiment-level pause sets `experiment_run` to `paused` first, so a `running` filter would never match (the direct cause of the original bug).

**Verification**:

| Item | Result |
|---|---|
| Self-test (live DB) | `RunControlDbSelfTest` etc. **43 / 0** |
| Gate stage 4.17 | `scripts/e2e-pause-during-experiment.py` **5 / 0** (threshold 2 → 5) |
| Measured evidence | run #208222 / experiment_run #1868: after the pause `ARM=completed / ARM2=pending` (the following node stays pending) |
| Pure-logic unit tests | 651 / 0 (threshold 650 → 651) |

**Do not conflate the two things**: this bug fixes "**stopping at the node boundary**" (effective by default); "**really stopping mid-action**" is a separate path (`device_method.interruptible=true` + `DriverStepExecutor.pauseRequested` → `driver.abort`, which needs a per-site checklist).

**Closure condition**: after an experiment-level pause the underlying workflow-level run lands on `paused` at the node boundary and the following nodes stay `pending`; after a resume the old `paused` run is constantly `aborted` and no longer appears in the resumable `pausedRuns` set.
