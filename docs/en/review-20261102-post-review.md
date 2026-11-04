# Re-check Package · 2026-11-02 (post-review)

> **English edition.** The Chinese original is [`docs/复核包-20261102-审后.md`](../复核包-20261102-审后.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **For the external re-checker.** The previous round of code review (`20261102-03`) raised 18 P0 and 33 P1 items,
> and this round disposes of all of them: **P0 18/18 fixed, P1 26/33 fixed**.
>
> **The conclusion in one sentence**: it can be re-checked, but **please focus on the 6 items in section 4** —
> those are the fixes that "changed the semantics", and they are the most likely to introduce new problems.
>
> **⚠ The boundary of this re-check (pointed out by review 20261102-04 R6)**:
> the numbers in section 3 come from **an actual command run**, but **they are not an independent re-run in this round** —
> the unit tests / acceptance depend on the simulator and a real PostgreSQL. This document is a "**code-reading-level re-check**", not a "run-level verification".
> Real-device verification is still a blank.

---

## 1. What this round changed

| Category | Count | Explanation |
|---|---|---|
| **P0** | **18/18 fixed** (among them P0-11 was misjudged by me as "does not exist"; after the Trae re-check it was confirmed to hold and fixed) |
| **P1** | **26/33** | 2 not accepted (with reasons), 1 could not be located, **4 not touched** (18/19, 33, island lock) |
| Commits | **21** | `git rev-list --count af8d5f3..HEAD` (20 at the time this section was written, +1 since) |
| Changes | **1052 lines added / 115 deleted in the source, 34 files** | Plus 148 deleted `.class` files (chore commit) |

> **Correction (pointed out by review 20261102-04 Trae)**: this section originally wrote "12 commits, 527 lines, 172 files",
> which does not match reality. The numbers were written from memory and have been corrected against `git` measurements.
> Once again "the statement does not match the entity" — the same class of bug I find when I review other people's work.

**The item-by-item disposition is written in**: `docs/评审记录/20261102-03-code-self.md`
- §11.4 — 18 P0 items, each with "conclusion / reason / disposition / verification"
- §十二 — 29 P1 items (the same format)

---

## 2. A root cause that keeps recurring (worth looking at on its own)

**6 of the 26 P1 items are the same disease**:

| Id | Symptom | Essence |
|---|---|---|
| P0-4 | Auto-recovery has no caller | It was written but never connected |
| P1-1 | The wiring has `db=null` | It was connected to the wrong constructor |
| P1-8 | No pause checkpoint | The state was changed but the executor was not notified |
| P1-10 | `markPlateUnknown` has no caller | It was written but never connected |
| P1-15 | The catch-up wait is cut off by a 60 s ceiling | It was connected but got truncated |
| P1-23 | It reports success without validating the response | It was connected but the result was not checked |

> **Written ≠ connected; connected ≠ connected correctly; connected correctly ≠ in effect.**

**Before these 6 were fixed, the corresponding mechanisms were all "present but never in effect"**:
M4 never actually paused, auto-recovery never actually recovered, the three plate-position states were never actually marked, and the four-layer resource protection never actually reserved anything.

---

## 3. Verification baseline (actually run this round, not self-reported)

```
Compile       0 errors (kernel + gateway + webapp build)
Unit tests    106/106 (§11.4 wrongly wrote 104, now corrected)
              Expression 30 / WaitForGraph 13 / TransferPlan 3 / PauseManager 10
              Recovery 17 / Engine 14 / Alert 13 / GraphRunner 6
Acceptance    45/45 (m1 12 / m3 9 / m4 14 / m5 10)
Process sim   node auto-completion rate 100%  workflow fully-run-through rate 100%
Empty DB      105 tables / 0 ERROR (the real database has 107; the 2 missing are historical monthly partitions)
Idempotency   96_schema_fix and 95_seed each run twice in a row, 0 ERROR
git history   searching the whole history for a pg_dump token: 0 hits
```

---

## 4. ⚠ The 6 items to focus the re-check on (the semantics changed, most likely to introduce new problems)

### 4.1 P1-1 Production wiring `db=null` (the wiring was changed)

**What was changed**: `DriverStepExecutor` gained the constructor `(registry, sink, db, tenantId, runId)`;
the wiring in `WorkflowService` was changed to use it.

**Why it is dangerous**: this is **the master switch of the four-layer resource protection**. Before the change `protect` was constantly false,
and every Transfer called `driver.execute` bare; after the change **every Transfer goes through the resource-acquisition flow**.

**What to re-check**:
```bash
# ① Confirm that the wiring point really passes db
grep -n "new DriverStepExecutor(registry, persistSink, db" \
  labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java

# ② Run one real Transfer and see whether the resource-acquisition log appears
LABOS_TRANSFER_PLAN=1 ... run a workflow containing a Transfer
grep "通道\|门\|位置" /tmp/xxx.log
```

**Pass criterion**: the log should show the records for acquiring the gate / the channel / the position;
if it is still a bare execution, the wiring did not take effect.

### 4.2 P1-4 Three-state semantics of the position reservation (the decision condition was changed)

**What was changed**:

```sql
-- Before: reserved_until IS NULL → considered idle
-- After:  reserved_by_run IS NULL → only then it is considered idle
AND (position_state.reserved_by_run IS NULL
     OR (position_state.reserved_until IS NOT NULL AND position_state.reserved_until < now())
     OR position_state.reserved_by_run = EXCLUDED.reserved_by_run)
```

**Why it is dangerous**: this WHERE clause decides whether **every position reservation** can succeed.

**What to re-check**:
1. Is a permanent commitment (`reserved_by_run` not null + `reserved_until` NULL) **really impossible to take**?
2. Can an expired reservation (`until < now()`) **really be taken**?
3. Is there any **historical dirty data** in the data where `reserved_by_run` is not null but `reserved_until` is NULL?
   If there is, those positions become **permanently locked**.

```sql
-- Point 3 must be checked
SELECT count(*) FROM position_state
WHERE reserved_by_run IS NOT NULL AND reserved_until IS NULL;
```

**Self-checked** (2026-11-02):

```
Permanently locked count: 0
State distribution: 9 idle records (no historical dirty data)
```

The current database is clean. **But this still has to be checked when the production database is imported.**

### 4.3 P1-15 Catch-up wait for the shortest interval (the behaviour changed)

**What was changed**: originally `sleep(min(needMs, 60_000))`, now it **waits the full `needMs`** (ceiling 2 hours).

**Why it is dangerous**: **on a real device it will look "stuck"**.
The process requires an interval of 30 minutes; now it really waits 30 minutes, while the screen only says "executing".

**Self-checked** (2026-11-02):

```
min_sec of the workflows: max=3 s / avg=3.0 s / only 1 record has a constraint
```

**The impact is negligible** — there are almost no time constraints in the database, so the change will not slow the workflows down.
But **if a new workflow is configured with a large interval, the behaviour differs from before** (before it was released after 60 s, now it waits the full amount).

**What to re-check**:
- For a future workflow configured with `min_sec > 60`, does the UI need a hint "this will really wait N minutes"?
```sql
SELECT max(min_sec), avg(min_sec), count(*) FROM (
  SELECT (params->'time_constraint'->>'min_sec')::int AS min_sec
  FROM workflow_node WHERE params->'time_constraint' IS NOT NULL
) t;
```

### 4.4 P1-8 Pause checkpoint (a new mechanism)

**What was changed**: `GraphRunner` checks `run.status` at the **task-node boundary**; on a pause it does `break` and puts the node back into the queue.

**Why it is dangerous**: every task node now costs **one more database query**; and the wrap-up logic after the `break` has to guarantee that it does not overwrite `paused`.

**What to re-check**:
1. The performance impact on workflows made of many short nodes (one `SELECT status` per node)
2. Whether the `isRunPaused` guard in `settleRun` covers all exit paths

**Existing unit test**: `GraphRunnerTest` gained a case (a 3-node chain, pause at the second boundary → only 1 node runs).

### 4.5 P1-23 `apiFetch` throws on a non-2xx (frontend, global)

**What was changed**: `apiFetch` now throws an `ApiError` for every non-2xx.

**Why it is dangerous**: **all 71 call sites are affected**, and 27 of them have their own `if (!r.ok)` — which is now dead code.

**What to re-check**:
- Is there any place that **depends on "getting a non-2xx and handling it myself"** (e.g. treating a 404 as "does not exist" rather than as an error)?

```bash
# Find every place that depends on the non-2xx semantics
grep -rn "r.status ===\|res.status ===" webapp/src/pages/ | head -20
```

**Known**: `Island.tsx:165` originally wrote `r.ok ? r.json() : r.text()`;
after the change `r.ok` is always true and the logic is equivalent (the failure is already thrown in apiFetch). **The other 26 places need the reviewer's confirmation.**

### 4.6 P0-1 Authentication moved forward (the security boundary)

**What was changed**: the authentication block was moved from the middle of the route chain to **before all routes**; `isPublic` gained `/api/auth/login`.

**Why it is dangerous**: missing one entry in the whitelist **blocks a legitimate interface**; one entry too many **leaves a back door**.

**What to re-check**:
```bash
# Check the whitelist entry by entry
sed -n '/private static boolean isPublic/,/^    }/p' \
  labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java

# Actual test: every route that is not on the whitelist should return 401
for p in /api/workflows /api/runs /api/topology /api/process-status \
         /api/devices /api/alerts /api/users /api/heartbeat; do
  printf "%-24s " "$p"
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:18080$p"
done
```

**Self-checked** (2026-11-02):

```
/api/workflows     401    /api/alerts          401
/api/runs          401    /api/users           401
/api/topology      401    /api/heartbeat       401
/api/process-status 401   /health              200
/api/devices       401    /api/auth/login      405 (only POST is allowed, correct)
```

**Pass criterion**: apart from `/health`, `/api/auth/login` and the static resources, **everything is 401**.

---

## 5. Known items that are unverified / unfixed (do not assume they were all done)

| Item | Status | Explanation |
|---|---|---|
| **Real-device verification** | ⬜ **the biggest risk** | All 45 acceptance checks and all 106 unit tests still run on the simulator. The gate opening/closing time, the position coordinates, whether a `Transfer` is one action — **all unverified** |
| P1-18/19 read-timeout semantics | ⬜ not touched | The current `setSoTimeout(0)` means that a read timeout never happens; **whether the defect really exists needs the reviewer to confirm the scenario** |
| P1-33 the tests really connect to the database | ⬜ not touched | Changing them to closed tests means extracting an interface + a mock for `DbConfig`, which involves refactoring 8 test classes |
| P1-9 channel placeholder | ⬜ could not be located | `acquirePort` stores `portId`, and `release(portId)` is consistent with the SQL; **please point at the exact line number** |
| P1-29/30 the scripts | ❌ not accepted | `set -e` would break "run all the scenarios and then summarise"; the report under `/tmp` is a diagnostic artefact |
| The island lock is taken before submit | ⬜ not touched | P1-6 only fixed the counting semantics; **the structural problem is that a queued task holds the island without running** |
| Resume from a breakpoint | ⬜ not done | P0-5 changed `resume` to `queued` (it no longer pretends to be running), but **continuing from a middle step** requires support from the executor |

---

## 6. How to verify it (10 minutes / full)

### 6.1 Quick (no database needed)

```bash
cd labos-kernel
java -cp out com.labos.kernel.expr.ExpressionSelfTest          # 30
java -cp out com.labos.kernel.driver.WaitForGraphSelfTest      # 13
java -cp out com.labos.kernel.alert.AlertSelfTest              # 13
java -cp out com.labos.kernel.engine.EngineSelfTest            # 14
java -cp out com.labos.kernel.recovery.RecoverySelfTest        # 17
java -cp out com.labos.kernel.workflow.GraphRunnerTest         # 6
```

### 6.2 Full (needs a database + the simulator)

```bash
export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
cd labos-kernel && ./scripts/sim-up.sh LAB-DEMO-02 100

# In another window
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer 18080

# Run the acceptance
cd .. && ./scripts/verify-m1.sh && ./scripts/verify-m3.sh \
        && ./scripts/verify-m4.sh && ./scripts/verify-m5.sh
```

### 6.3 Empty-database table creation (verifying that the schema is self-consistent)

```bash
cd pg_rebuild/schema && bash merge.sh
psql -h $HOST -U $USER -d $DB -c "CREATE SCHEMA cmp_test;"
psql -h $HOST -U $USER -d $DB -c "SET search_path TO cmp_test;" -f 90_all_in_one.sql
# Expected: 0 ERROR, 105 tables
```

---

## 7. Three suggestions for the re-checker

1. **Run the third SQL of §4.2 first** (the historical dirty-data check) —
   if the database holds records where `reserved_by_run` is not null and `reserved_until` is NULL,
   those positions are now **permanently locked**, which is the easiest trap to fall into in this change.

2. **Do not only look at the diff**. Most of these fixes are "structural problems" (call chains, wiring, semantics),
   and the diff looks very clean; **the problem lies in "whether this path is actually reachable at all"**.
   It is recommended to **actually run** P1-1, P1-8 and P1-10 once and look at the log.

3. **Switch to a model family that is not DeepSeek**. All the fixes in this round were **self-reviewed and self-fixed**,
   so the independence is insufficient — the reviewer and I are the same model and the blind spots most likely overlap.

---

## 8. Document index

| Document | Content |
|---|---|
| `docs/评审记录/20261102-03-code-self.md` | **The main record**: §三-§六 the original comments, §11.4 the P0 responses, §十二 the P1 responses |
| `docs/评审包-代码-20261102.md` | The entry-point map before the review |
| `docs/评审规范.md` | The review process and the record format |
| `docs/资源模型与死锁避免-设计.md` | Four resource layers + the wait-for graph |
| `docs/暂停恢复与故障处理-设计.md` | Three pause kinds + three plate-position states |
| `docs/并发调度决策记录.md` | The complete decision record D101-D119 |
