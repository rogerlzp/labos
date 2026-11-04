# Review Record 20261103-02 (review package re-check · second round · code-reading level + command measurement)

> **English edition.** The Chinese original is [`docs/评审记录/20261103-02-评审包复核.md`](../../评审记录/20261103-02-评审包复核.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Field | Value |
|---|---|
| **Review id** | `20261103-02` |
| **Review time** | 2026-11-03 12:46 (UTC+8) |
| **Reviewing model** | Trae AI (underlying model family: DeepSeek-V4.1-Flash) |
| **Review target** | `docs/评审包-20261103/` (`00` / `01` / `02` / `03` / `04`), `scripts/` (test-all / ci-gate / verify-device-chain / e2e-smoke), `pg_rebuild/schema/`; the code `main @ 100a47a` |
| **Review scope** | **All the numeric claims** of the four documents, the 9 review points of `00` §4, the 3 focus points the user named (D4 / D7 / D15), the regression of the first round's blocking items (B1 / H1 / H2) |
| **Review method** | **Code-reading level + reproducible command measurement**; **no database connection** (`LABOS_DB_*` not set, 5432 unreachable) |
| **Conclusion** | **Fail** |

> **Yardstick**: this record only verifies "whether what the document says equals what the code / the measurement looks like"; it does not judge the functional design direction.
> Per the review guidelines §8, this file is **append-only, never modified**; the reviewed party's response is written in the "remediation update" section at the end.
>
> **Statement of independence (important)**: the underlying model of the reviewing party `Trae AI` of this round is still **DeepSeek-V4.1-Flash**, so it **belongs to the same DeepSeek family** as the reviewed party (the document author), and their blind spots may overlap.
> Per the user's rule "multiple review rounds must switch model family", **the conclusion of this round cannot be the final verdict**; it has to be re-verified and taken on trust by a reviewer from a **non-DeepSeek family** (e.g. Grok / Kimi / Claude).
> In addition: all the "numeric" conclusions of this round are **code-reading + command-level** evidence, **which is not the same as "run-level" verification** against a live database / a real device.

---

## 1. Conclusion summary

**Fail.** 1 blocking + 5 self-consistency issues (P2). (The first round's B1 / H1 / H2 have passed the regression.)

| Category | Claim | Measured | Verdict |
|---|---|---|---|
| Backend Java | 29,671 lines / 107 files | **29,671 / 107** | ✅ |
| Frontend | 8,393 lines / 19 pages | **8,393 / 19** | ✅ |
| Route patterns | 50 | **50** route constants (`Pattern.compile` 52 in total, 2 of them are inlined in a method, not routes) | ✅ |
| Pure-logic cases | 379 / 0 | actual run of `test-all.sh --pure-only` **379 / 0** | ✅ |
| Cases with the database | 397 | **not re-verified** (no password, no database connection) | Unverified |
| Table count / error-code count | 112 / 25 | **not re-verified** (no password, no database connection) | Unverified |
| Device-chain assertions | 37 | **37** assertions in the script | ✅ |
| e2e assertions | 14 | **14** assertions in the script | ✅ |
| Gate thresholds | ≥379 / ≥37 / ≥14 | `ci-gate.sh:34-36` **379 / 37 / 14** | ✅ |
| **The install artifact** | `90_all_in_one.sql` already contains the M6 fix | **a stale artifact**: missing `run_node.loop_index`, missing the foreign key, still a 3-column unique key | ✗ **Blocking** |
| Document self-consistency | — | 5 places that contradict each other / are untrue (see §5) | ✗ P2 |

---

## 2. Items verified as true (evidence)

| Item | Claim | Verification evidence | Verdict |
|---|---|---|---|
| Backend scale | 29,671 lines / 107 files | `find src/main/java -name '*.java' \| xargs wc -l` = **29671**; file count = **107** | ✅ |
| Frontend scale | 8,393 lines / 19 pages | `find webapp/src -name '*.ts*' \| xargs wc -l` = **8393**; `pages/*.tsx` = **19** | ✅ |
| Routes | 50 patterns | [ApiServer.java](../../../labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java#L80-L148) has **50** route constants; `Pattern.compile` 52 in the whole file, the difference of 2 are inlined matches inside a method at [:597](../../../labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java#L597) / [:1140](../../../labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java#L1140) | ✅ |
| Pure-logic cases | 379 / 0 | actual run of `./scripts/test-all.sh --pure-only`: 10 categories total 378 + `SidecarChainTest` counted as 1 = **379 / 0** | ✅ |
| Gate thresholds | 379 / 37 / 14 | [ci-gate.sh:34-36](../../../scripts/ci-gate.sh#L34-L36) `MIN_PURE=379` `MIN_CHAIN=37` `MIN_E2E=14` | ✅ |
| Device-chain assertions | 37 | the assertion count of [verify-device-chain.sh](../../../scripts/verify-device-chain.sh) = 37, including the new "resume steps=3 / cmds=3 / no uk conflict" added for H1 | ✅ |
| e2e assertions | 14 | [e2e-smoke.py](../../../scripts/e2e-smoke.py) = 14 | ✅ |
| Number of historical documents | 52 | `ls docs/*.md \| wc -l` = 52 | ✅ |
| ADR numbering | rearranged into D1–D15 | [03-设计文档.md](../../评审包-20261103/03-设计文档.md) D1…D14 in order without duplicates; **the position of D15 is the exception, see §5.5** | ⚠ Partial |

### Regression of the first round's blocking items (all passed)

| Last round's id | Last round's problem | This round's measurement | Verdict |
|---|---|---|---|
| B1 | The document wrote 373, the measurement was 368 | The document now says 379 throughout, the actual run is 379 | ✅ Fixed |
| H1 | The resume only deleted `level=0`, the step rows / commands were left behind | [WorkflowService.java](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L551-L571) deletes `level=1` first (located via `parent_node_id` for the unfinished parent nodes) and then deletes `level=0` | ✅ Fixed |
| H2 | `markBypassedNodes` did not exclude `SKIPPED`, a paused run was wrongly marked bypassed | `count(SKIPPED)>0 → return early` has been added | ✅ Fixed |

---

## 3. Blocking level

### B1. The install artifact `90_all_in_one.sql` is a **stale generated product** missing the M6 fix; and `deploy.sh` is exactly what deploys it

**Symptom**: what line 27 of `deploy.sh` deploys is the **merged artifact** [90_all_in_one.sql](../../../pg_rebuild/schema/90_all_in_one.sql), but that file **is stuck before the M6 fix**.

**Chain of evidence (git timestamps + content comparison)**:

| File | Latest commit | Content |
|---|---|---|
| [merge.sh](../../../pg_rebuild/schema/merge.sh#L37-L42) | `6fb38d3` 11-03 11:18 | concatenates `10+20+30+40+92+93+94+`**`96_schema_fix.sql`** → `90_all_in_one.sql` |
| [96_schema_fix.sql](../../../pg_rebuild/schema/96_schema_fix.sql#L103-L130) | `6fb38d3` 11-03 11:18 | has: the `parent_node_id` foreign key (112-115), `loop_index` (120), the 4-column `uk_run_node` (127), `step_seq` (130) |
| [20_kernel.sql](../../../pg_rebuild/schema/20_kernel.sql#L309-L310) | `6fb38d3` 11-03 11:18 | has: `loop_index smallint NOT NULL DEFAULT 0` (309), `UNIQUE(run_id,node_key,attempt,loop_index)` (310) |
| **[90_all_in_one.sql](../../../pg_rebuild/schema/90_all_in_one.sql#L557)** | **`43317da` 11-02 21:33 (earlier than M6)** | **has not**: [:557](../../../pg_rebuild/schema/90_all_in_one.sql#L557) is still the 3-column `uk_run_node (run_id, node_key, attempt)`; [:2488](../../../pg_rebuild/schema/90_all_in_one.sql#L2488) `ADD COLUMN parent_node_id` has no foreign key; `run_node` has **no `loop_index` column** |

**Root cause**: the M6 commit (`6fb38d3`) changed the shards and `merge.sh`, but **did not re-run `merge.sh`**, so the merged artifact was never regenerated.

**Impact (why this is blocking level, not a documentation problem)**: `loop_index` is referenced extensively in the code —— [QueryService.java](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java) at [:82](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L82), [:152](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L152), [:245-250](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L245-L250), [:289](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L289), [:416-421](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L416-L421), [:721](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L721) and [WorkflowService.java:1695](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1695). After starting it up with a **new database**:

1. all the **run list / detail / flow chart / timeline** queries that reference `run_node.loop_index` fail with a SQL error directly (the column does not exist);
2. `parent_node_id` has no foreign key → the cascade delete of the step rows does not hold (the structural precondition for the H1-class orphan rows is missing again);
3. `uk_run_node` goes back to 3 columns → **the same node hitting the unique key when it is inserted across iterations** in a loop scenario (the M6 fix is cancelled out).

This is exactly the **"dual paths"** defect the project keeps stressing: the code path has been changed, but the path that gets deployed (`90_all_in_one.sql`) has not kept up.

**Fix suggestion (to be done by the implementer, the reviewer does not do it)**:
- re-run `pg_rebuild/schema/merge.sh` to regenerate `90_all_in_one.sql`;
- add a "**the merge artifact is consistent with the shards**" check to the regression baseline / CI (assert on the columns and the unique key of `run_node`), to prevent the drift from recurring.

---

## 4. High severity / medium severity

This round **found** no new code-level high/medium severity defects. The three uncertain points the reviewed party listed are adjudicated one by one in §7, and **all of them are correct implementations**.

---

## 5. Self-consistency issues (P2, a necessary condition for releasing the block)

> They all belong to "the document contradicts itself / the document does not match the code". The scale is small, but they violate the hard constraint "the documents must be self-consistent".

| # | Location | Problem | Evidence |
|---|---|---|---|
| 1 | [04-测试文档.md](../../评审包-20261103/04-测试文档.md#L165) | §8 writes `verify-device-chain.sh # **34** / 0`, which conflicts with the **37** at [:17](../../评审包-20261103/04-测试文档.md#L17) / [:42](../../评审包-20261103/04-测试文档.md#L42) / [:83](../../评审包-20261103/04-测试文档.md#L83) / [:89](../../评审包-20261103/04-测试文档.md#L89) of the same file | 34≠37 inside the same file |
| 2 | [02-技术文档.md](../../评审包-20261103/02-技术文档.md#L64-L76) §2.1 | The package file counts are untrue: it writes driver **25** / workflow **21** / sim **8** / device **7** / expr **6**; the measurement gives driver **23** / workflow **20** / sim **7** / device **6** / expr **5**. The itemised total is **113** ≠ the header total **107** | `find … \| wc -l` per package |
| 3 | [02-技术文档.md](../../评审包-20261103/02-技术文档.md#L78) | It cites "**design document §6**", but `03-设计文档.md` has no §6 (the sections are 0 / D1-D15 / 3 / 4 / 5) | A dangling reference |
| 4 | [02-技术文档.md:128](../../评审包-20261103/02-技术文档.md#L128) and [00-索引与评审须知.md:118](../../评审包-20261103/00-索引与评审须知.md#L118) | It still writes the `run_node` unique key as `(run_id, node_key, attempt)` (**`loop_index` is missing**); while [:192](../../评审包-20261103/00-索引与评审须知.md#L192) of the same 00 document writes "`run_node.loop_index` has been completed" | Does not match `20_kernel.sql:310` / M6, and the document contradicts itself |
| 5 | [03-设计文档.md:318](../../评审包-20261103/03-设计文档.md#L318) | **D15** sits **after** §3([:284](../../评审包-20261103/03-设计文档.md#L284)) / §4([:297](../../评审包-20261103/03-设计文档.md#L297)) / §5([:310](../../评审包-20261103/03-设计文档.md#L310)) —— the ADR section and the conclusion section are out of order | The document structure |

> In addition: the user said "the link check has no failures", but **no link-checking script could be found in the repository** (no `*link*` / `*consistency*`). That claim **has no reproducible means**, this record does not accept it, and records it as "unverified".

---

## 6. The conclusions on the user's three "most worrying points"

| User's point | Claim | Code-reading conclusion | Verdict |
|---|---|---|---|
| **D4 resume from breakpoint** | Delete the rows of the unfinished nodes + `skipKeys`; add "delete `level=1` first, then `level=0`" | [WorkflowService.java:551-571](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L551-L571) first deletes the `level=1` step rows via `parent_node_id` (the parent node is unfinished), then deletes the unfinished `level=0` rows; [GraphRunner.java:308-311](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L308-L311) treats a `skipKeys` hit as "already done, carry on". **The direction of "skip the completed nodes" is correct, nothing is transported twice** | ✅ |
| **D7 `hasCycle`** | Re-entry is only allowed when the graph really has a cycle; an acyclic graph is deduplicated | [GraphRunner.java:403-431](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L403-L431) uses three-colour DFS; [:285](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L285) `if (done.contains(key) && !hasCycle) continue;` —— **only an acyclic graph is deduplicated, a cyclic graph allows re-entry**; double insurance `maxVisits=10` (clamp 1–100) + `maxSteps=500` | ✅ Semantically self-consistent |
| **D15 the built-in node `__` prefix** | A `protocolName` starting with `__` = a built-in node, it does not go to a device | [WorkflowGraphParser.java:174](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowGraphParser.java#L174) `proto.startsWith("__") ? Kind.BUILTIN : Kind.TASK`; [GraphRunner.java:312-327](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L312-L327) the BUILTIN branch does not touch a device, a failure turns into `MANUAL` + `EC-BUILTIN-001` and stops. **The judgement is correct** | ✅ |

> Note: D15 is new code, so it has had the least time-based verification; its "run-level" correctness still has to be verified **with a live database + a real device** (this round did not connect to a database).

---

## 7. Remediation update (filled in by the reviewed party, append-only, never modified)

> This section is for the reviewed party to answer item by item; please do not modify the text above.

### 2026-11-03 12:50 CST · recorded by the reviewer (carried out per the user's instruction)

The user instructed "do all of the above": ① write the P2 of §5 straight into the reviewed documents; ② create a BUG ledger; ③ confirm this record. Accordingly:

- **The 5 P2 items of §5 have been written into the body** (`docs/评审包-20261103/` 00/02/03/04):
  - 04 §8: the device-chain assertion `34` → `37`;
  - 02 §2.1: driver 25→23, workflow 21→20, sim 8→7, device 7→6, expr 6→5 (the itemised total = 107);
  - 02 §2: `design document §6` → `design document §5`;
  - 02 §3 and 00 §5.1: the `run_node` unique key gains `loop_index` → `(run_id, node_key, attempt, loop_index)`;
  - 03: D15 moved from after §5 to between D14 and §3.
- **Incidental self-consistency corrections (because they contradict M6, handled together)**: 00 §5.1 #2 and 03 §5 priority 2 —— marked **completed (M6)** (`run_node.loop_index` gets one row per iteration).
- **New ledger**: [BUG台账.md](../../评审记录/BUG台账.md), registering B1 (`BUG-20261103-01`, 🟥 to fix) + the 6 self-consistency items (already fixed).

> ⚠️ Boundary statement: the above is a **document self-consistency correction**, a record-keeping action carried out by the reviewer under the user's authorisation; **no code / SQL was changed**.
> The fix of the §3 blocking item `BUG-20261103-01` (re-running `merge.sh`) is to be done by the **implementer**, and it **must be re-checked against a live database**.
> Because the reviewer modified the reviewed documents, **the next round has to be re-verified independently by a reviewer from a non-DeepSeek family**.

---

**Sign-off**: Trae AI (DeepSeek-V4.1-Flash) · 2026-11-03 12:46 CST · review target `main @ 100a47a`

---

### 2026-11-03 13:2x CST · the reviewed party (the implementer) remediation

For the §3 blocking item `BUG-20261103-01`, as required **re-ran the merge and re-checked against a live database**. The item-by-item response follows.

#### 1. The blocking item: the install artifact is stale —— fixed, pending re-check

| Action | Result |
|---|---|
| `bash pg_rebuild/schema/merge.sh` | the artifact was regenerated: 2531 → 2536 lines |
| Artifact diff (against the repository's prior version) | only 30 related changes: the `loop_index` definition, the 4-column `uk_run_node`, the parent foreign-key DO block, **plus 5 restored `ALTER TABLE ONLY plate_well`** (those 5 lines had been lost in the stale artifact, which caused 4 `syntax error at or near "ADD"`, fixed together) |
| Live-database verification (the artifact fully executed in a new schema) | `run_node.loop_index` ✓ / `uk_run_node UNIQUE (run_id,node_key,attempt,loop_index)` ✓ / `run_node_parent_node_id_fkey ... ON DELETE CASCADE` ✓ |

The re-check command (anyone can re-run it):

```bash
psql -c "create schema schema_check"
PGOPTIONS='-c search_path=schema_check' psql -v ON_ERROR_STOP=0 -f pg_rebuild/schema/90_all_in_one.sql
psql -tc "select pg_get_constraintdef(oid) from pg_constraint
           where conrelid='schema_check.run_node'::regclass and conname in ('uk_run_node','run_node_parent_node_id_fkey')"
```

#### 2. The drift assertion —— added to the gate

New `scripts/check-schema-sync.sh`: it re-runs `merge.sh` and compares the result with the repository's artifact (a mismatch fails, and the working tree is restored so that nothing is polluted),
and at the same time asserts that the artifact contains the three elements `loop_index` / the 4-column unique key / the parent foreign key. It has been merged into the 2.5th gate of `scripts/ci-gate.sh`.

**Negative test**: put the stale artifact back → the script reports 「产物过期」 ("the artifact is stale") and lists the diff, exit code 1; put the new artifact back → it passes.
```bash
./scripts/ci-gate.sh
  ✓ passed 379 / failed 0 (threshold 379)
  ✓ tsc 0 errors
  ✓ the artifact and the shards have the same source (with loop_index / the 4-column unique key / the parent foreign key)
  Gate passed
```

#### 3. A new problem found during the re-check (not in the scope of this remediation, registered)

At the step "execute the artifact in a brand-new database", apart from the blocking item above, it also exposed an **existing defect**:
the artifact contains 22 `already exists` (4 identity sequences created twice, 5 `multiple primary keys`, several duplicated constraints).
The root cause is that `30_business.sql` contains **hand-written DDL** and a **pg_dump-style tail** at the same time, and both define the same set of tables.
`deploy.sh:112` uses `ON_ERROR_STOP=1` and aborts at the first one —— in other words, **a brand-new deployment does not run cleanly**.

It has been registered as `BUG-20261103-08` (blocking, 🟥 to fix). The fix plan: drop the duplicate definitions (keep the hand-written DDL,
delete the dump tail), or wrap the tail in a `DO $$ ... EXCEPTION WHEN duplicate_object ...` guard.
**It was not touched this time**: it is a different thing from the artifact drift, and mixing the two changes into one would make "which change caused what" impossible to say.

#### 4. On the "environment-level concurrent editing" the reviewer mentioned

We have not seen any other editor holding the files under `docs/评审包-20261103/`: the reviewer's 4 P2 modifications and the 2 new files are all in `git status`, and their content matches the report I read, with no trace of a rollback. If "the write does not take effect" happens again, it is recommended to judge it with the double evidence of `git diff --stat` and the file mtime, and not only by what the tool returns.

#### 5. Conclusion

- `BUG-20261103-01`: **please close it after re-checking** (the evidence is above, including the live-database output).
- `BUG-20261103-08`: a new blocking item, to be handled in the next round.
- On the document side: the reviewer's 4 P2 modifications have been committed together with this round.

---

### 2026-11-03 13:4x CST · the implementer's addition: BUG-20261103-08 is also fixed (not left for the next round)

The previous section wrote `-08` as "to be handled in the next round", the user asked for it to be fixed directly, so it is completed here.

#### The approach: do not delete the duplicate definitions, add a guard instead

The hand-written DDL and the pg_dump tail are **interleaved** in the shards (the tail also has 23 tables of its own), so deleting a block easily deletes the wrong thing;
instead, a step `guard_dup_decl.py` was added to `merge.sh`: it wraps the **duplicate-declaration** statements ——

```sql
DO $$ BEGIN
  ALTER TABLE capability_recipe ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (…);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;
```

It covers the two kinds `ALTER … ADD GENERATED` and `ALTER TABLE [ONLY] … ADD CONSTRAINT`, 59 statements in total.
The semantics are unchanged: an object that does not exist is still created, one that already exists is skipped with a NOTICE.

#### Verification (empty schema, `ON_ERROR_STOP=1`)

| Item | Result |
|---|---|
| Exit code | **0** (it aborted at the first one before) |
| Number of ERRORs | **0** (was 22) |
| Objects created | 105 tables + 5 views (the live 107 = 105 + 2 historical monthly partitions from 2026-09) |
| The three elements of `run_node` | `loop_index` ✓ / `uk_run_node(…,loop_index)` ✓ / the `parent` foreign key ✓ |

> Conclusion: please re-check and close both `BUG-20261103-01` and `-08`. Both come with a re-runnable command,
> so there is no need to take my description on trust.

---

### 2026-11-03 13:21 CST · the reviewer's re-check: `BUG-20261103-01` / `-08` closure ruling

| Field | Value |
|---|---|
| **Reviewing model** | Trae AI (underlying model family: DeepSeek-V4.1-Flash) |
| **Re-check target** | `main @ 39d352c` (`git status` clean, `HEAD=39d352c`) |
| **Re-check method** | Static structural verification + a re-run of the gate; **no live database** (no PostgreSQL server on this machine: the `postgres` binary is missing, 5432 is unreachable, no docker/podman/colima) |

#### Ruling: **pass —— both can be closed**

| BUG | Ruling | The reviewer's independent evidence | Not covered |
|---|---|---|---|
| `-01` the install artifact is stale | ✅ **Closed** | ① an independent re-run of `scripts/check-schema-sync.sh` → **PASS** (re-running `merge.sh` yields zero difference against the repository); ② the three elements are in place in the artifact: `loop_index smallint NOT NULL DEFAULT 0` (:558), `uk_run_node UNIQUE (run_id, node_key, attempt, loop_index)` (:559), `run_node_parent_node_id_fkey … REFERENCES run_node(id) ON DELETE CASCADE` (:2799-2802); ③ the 5 restored `ALTER TABLE ONLY plate_well` have flowed back; ④ the 2.5th gate (artifact consistency) prevents a recurrence | The closure condition originally contains "needs verification against a live database" —— this machine has no database, so **the existence of the columns/constraints is structural-level evidence** (consistent with the implementer's live-database output) |
| `-08` a brand-new database does not install cleanly | ✅ **Closed (with a live-database note)** | ① the artifact contains **59** guard DO blocks and the structure is legal: `DO $$ BEGIN <stmt> EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN RAISE NOTICE … END $$;`; ② **completeness check by exclusion**: the artifact no longer has any top-level `ALTER TABLE … ADD GENERATED` / `ALTER TABLE ONLY … ADD CONSTRAINT`; all 80 `CREATE TABLE` are `IF NOT EXISTS`; all `CREATE INDEX` use `IF NOT EXISTS`; all `CREATE TRIGGER` are preceded by `DROP TRIGGER IF EXISTS`; functions/views use `CREATE OR REPLACE` → **statically there is no statement left that would still abort a brand-new installation**; ③ `./scripts/ci-gate.sh` is all green | "an empty schema with `ON_ERROR_STOP=1` → exit=0 / ERROR=0" is the **implementer's own measurement**; this machine has no PG and it **cannot be reproduced**. The conclusion depends on the runtime error code falling into the catch set (see below) |

#### Two points confirmed during the re-check (not blocking, recorded)

**① 5 tables are declared twice in the artifact** (hand-written DDL + the dump tail); on a brand-new database the `IF NOT EXISTS` **silently skips the second copy**:

| Table | Position | Are the two equivalent |
|---|---|---|
| `plate_well` | :856 / :1464 | the column sets match ✅ |
| `run_variable` | :661 / :1629 | the column sets match ✅ |
| `run_artifact` | :677 / :1595 | the column sets match ✅ |
| `experiment_sample` | :1031 / :1393 | the column sets match (the PK is added by a trailing constraint) ✅ |
| `resource_booking` | :313 / :1517 | the column sets match (including `ck_booking_period`) ✅ |

→ **This does not constitute a defect** (the columns are equivalent, skipping has no side effect); but it is a **maintenance hazard**: editing the wrong copy does not raise an error. It is recommended to put it on the cleanup list (merge into a single definition), it **does not block the closure**.

**② The correctness of the guard depends on the SQLSTATE being hit**: when `ADD GENERATED` runs into an identity that already exists, the real error is **"a sequence with the same name already exists"** (`relation <t>_id_seq already exists` = `42P07 duplicate_table`), which falls into the catch set, so it can be skipped —— consistent with the implementer's `exit=0`. The catch set deliberately **does not contain `WHEN OTHERS`** (so that real errors stay visible), which is the **right trade-off**; the cost: if a **fourth** kind of duplicate-declaration error code ever appears, the guard will miss it —— it is recommended to state this premise in the comment of the guard script.

#### The self-consistency of the "105 tables" count

The reviewer's static count: the artifact declares **78** table objects + **28** monthly partitions created dynamically by a `DO` loop at install time (`run_event`/`device_log`, each `FOR i IN -1..12`), **≈106** in total; **5** views. This is **consistent with the implementer's "105 tables + 5 views"** (**±1 counting convention: whether the partition parent tables / `_default` are included**). **No distortion of the count was found**.

#### Conclusion

- `BUG-20261103-01` → ✅ **Closed**.
- `BUG-20261103-08` → ✅ **Closed**; note: its "a brand-new database exit=0 / ERROR=0" is the implementer's own measurement, the reviewer had no PostgreSQL locally and could not reproduce it, and this has been recorded faithfully.
- **Statement of independence**: this re-check is still **DeepSeek-V4.1-Flash**, the same family as the reviewed party. Per the "multiple review rounds switch model family" rule, **the final verdict is still recommended to be taken on trust by a reviewer from a non-DeepSeek family**.
