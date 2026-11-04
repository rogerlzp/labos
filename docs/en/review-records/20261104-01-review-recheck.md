# Review Record 20261104-01 (review package re-check · live-database measurement level)

> **English edition.** The Chinese original is [`docs/评审记录/20261104-01-评审包复核.md`](../../评审记录/20261104-01-评审包复核.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Field | Value |
|---|---|
| **Review id** | `20261104-01` |
| **Review time** | 2026-11-04 00:14 (UTC+8) |
| **Reviewing model** | Trae AI (underlying model family: DeepSeek-V4.1-Flash) |
| **Review target** | `docs/评审包-20261104/` (`00` / `01` / `02`), `scripts/` (test-all / ci-gate / e2e-smoke / verify-rbac), `labos-kernel/src` (ApiServer + 8 controllers, QueryService, RbacRules, StatusVocabulary, ExperimentScheduler), the real database `192.0.2.10:5432/labos_demo`; the code `main @ 21329cf` |
| **Review scope** | **All the numeric claims** of the three documents, the 8 review focus points of `00` §3, the 10 review questions of `00` §4 (answered by sampling) |
| **Review method** | **Code-reading level + reproducible command measurement against a live database** (this time PostgreSQL `192.0.2.10` was reachable; the API `18080` and the frontend `5173` were both running) |
| **Conclusion** | **Fail** |

> **Yardstick**: this record only verifies "whether what the document says equals what the code / the measurement looks like"; it does not judge the functional design direction.
> Per the review guidelines §8, this file is **append-only, never modified**; the reviewed party's response / remediation is written in the "remediation update" section at the end.
>
> **Statement of independence (important)**: the underlying model of the reviewing party `Trae AI` of this round is
> **DeepSeek-V4.1-Flash**, so it **belongs to the same DeepSeek family** as the reviewed party (the document author),
> and their blind spots may overlap.
> Per the user's rule "multiple review rounds must switch model family", **the conclusion of this round cannot be the final verdict**,
> and it has to be re-verified and taken on trust by a reviewer from a **non-DeepSeek family** (e.g. Grok / Kimi / Claude).

---

## 1. Conclusion summary

**Fail.** 1 blocking + 1 high + 3 self-consistency issues (P2).
**No regression was found in the product code**; the blocking and high-severity items are all **test-data hygiene + a fragile gate + documentation precision**, and they can converge quickly.

| Category | Document claim | Measured | Verdict |
|---|---|---|---|
| git push | `21329cf`, consistent with `origin/main` | `HEAD=21329cf`, `left-right=0/0`, the working tree is clean | ✅ |
| Commit range | `3aa4837..ec28d02`, **25** commits | `git rev-list --count` = **24** | ✗ P2 |
| Full run with the database | 907 / 0 | actual run of `test-all.sh` **907 / 0** | ✅ |
| Pure logic | 536 / 0 | actual run of `test-all.sh --pure-only` **536 / 0** | ✅ |
| RBAC joint debugging | 20 / 20 | actual run of `verify-rbac.py` **20 / 0** | ✅ |
| **e2e** | **24 / 0** | actual run of `e2e-smoke.py` **23 / 1** (the same result twice) | ✗ **Blocking** |
| ApiServer | 679 lines | **679** | ✅ |
| The line counts of the 8 controllers | MetaApi **109** (total 3487) | MetaApi **132** (total 3510) | ✗ P2 |
| Gate thresholds | ≥536 / ≥24 | [ci-gate.sh:35](../../../scripts/ci-gate.sh#L35) `MIN_PURE=536`, [:39](../../../scripts/ci-gate.sh#L39) `MIN_E2E=24` | ✅ |
| DB snapshot | 678 / 39 / **sys_user 3** / running=queued=0 | 679 / 39 / **sys_user 5** / 0 | ✗ High |
| go-live | PASS 9 / WARN 2 | with the DB env = **9 / 2 / 0**; without = **8 / 3** | ⚠ P2 |

---

## 2. Items verified as true (evidence)

| Item | Claim | Verification evidence | Verdict |
|---|---|---|---|
| Full run with the database | 907 / 0 | actual run of `LABOS_DB_*=192.0.2.10 ./scripts/test-all.sh`: pure logic 536 + with the database 371 (`BatchDbSelfTest` 244, `RunControlDbSelfTest` 36, `StartupSettleSelfTest` 26, `SchedulerRehydrateSelfTest` 14, the rest) = **907 / 0** | ✅ |
| Pure logic | 536 / 0 | actual run of `./scripts/test-all.sh --pure-only` **536 / 0** (`RbacRulesSelfTest` 15, `StatusVocabularySelfTest` 20, etc.) | ✅ |
| RBAC joint debugging | 20 / 20 | actual run of [verify-rbac.py](../../../scripts/verify-rbac.py) **20 / 0**: it covers a viewer being refused with 403, an operator not being able to escalate the privilege, an admin being demoted with 400 | ✅ |
| The ApiServer split | 679 lines | `wc -l` of [ApiServer.java](../../../labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java) = **679** | ✅ |
| The dispatch order | chained `\|\|`, the first hit returns | [ApiServer.java:184-191](../../../labos-kernel/src/main/java/com/labos/kernel/api/ApiServer.java#L184-L191) in order `authApi→deviceApi→schedulerApi→runApi→workflowApi→opsApi→experimentApi→metaApi` | ✅ (the equivalence is discussed separately in §6) |
| The status vocabulary's single source | the backend is the only authority + the frontend consumes it | [StatusVocabulary.java](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/StatusVocabulary.java) is there; [MetaApi.java:90-94](../../../labos-kernel/src/main/java/com/labos/kernel/api/MetaApi.java#L90-L94) provides `GET /api/meta/status-vocabulary`; the frontend uniformly does `import … from '../utils/status'`, and **no local status-mapping constants** are seen inside the pages | ✅ |
| The last-administrator protection | advisory lock + a single transaction | [QueryService.java:1574-1624](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L1574-L1624): `pg_advisory_xact_lock(hashtext('labos:user-role'))` + `SELECT … FOR UPDATE` + a single transaction commit/rollback; the rule is in [RbacRules.lastAdminGuard](../../../labos-kernel/src/main/java/com/labos/kernel/api/RbacRules.java#L83-L106) | ✅ |
| The scheduler parameters | EDD / island mutual exclusion / MAX_CONCURRENT=4 / tick 2s | [ExperimentScheduler.java:47](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java#L47); `rehydrateQueued()` [:217](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java#L217); the automatic re-queue every 5 ticks [:294](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/ExperimentScheduler.java#L294) | ✅ |
| go-live (with the DB env) | PASS 9 / WARN 2 / FAIL 0 | `LABOS_DB_* python3 scripts/device-go-live-check.py` = **PASS 9 / WARN 2 / FAIL 0** | ✅ |

---

## 3. Blocking level

### B1. The e2e "24 / 0" is not reproducible —— the measurement is **23 / 1**, and `ci-gate.sh --e2e` is currently guaranteed to be red

**Symptom**: `python3 scripts/e2e-smoke.py` run twice in a row gives **23 passed / 1 failed** both times, and the failing assertion is
[e2e-smoke.py:190](../../../scripts/e2e-smoke.py#L190) `✗ 执行历史没有可展开行` ("the execution history has no expandable row").

**The root-cause chain (verified in a closed loop)**:

| Step | Fact | Evidence |
|---|---|---|
| 1 | The experiment list takes the first row by `ORDER BY e.id DESC` | [QueryService.java:721](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L721) |
| 2 | The current largest id is **962**, `experiment_no=EXP-20261104001045-2428`, `name=rbac-operator-允许建实验-22244` | `psql … ORDER BY id DESC LIMIT 3` = 962 / 844 / 843 |
| 3 | The number of `experiment_run` rows of 962 = **0** | `psql … count(*) WHERE experiment_id=962` = 0 |
| 4 | The execution-history table has **no `rowExpandable`** set ⇒ "it can only be expanded if there is a run"; with 0 runs there is no expand icon | [Experiments.tsx:848-854](../../../webapp/src/pages/Experiments.tsx#L848-L854) |
| 5 | The assertion requires "there is an expandable row" ⇒ it fails | [e2e-smoke.py:180-190](../../../scripts/e2e-smoke.py#L180-L190) |

**Counter-evidence**: the next experiment **844** has 1 run (run_id 37, completed) ⇒ after 962 is cleaned up the first row becomes 844 and the e2e should return to 24 / 0.

**Nature**: **there is no functional regression**, this is a **fragility of the gate** — "the e2e assertion depends strongly on the data distribution of the first row of the list";
it only triggers together with the **test residue** of §4. Both have to be fixed together.

**Fix suggestion (to be done by the implementer)**:
1. clear the residue (§4);
2. the e2e must not take the first row when it picks an experiment, it should **filter for "experiments that have runs"** (removing the data-distribution dependency).

---

## 4. High severity

### H1. The RBAC joint-debugging residue pollutes the database, directly violating `01` §9 "no rbac test residue"

| Item | Document claim (`01` §9) | Measured (`psql`) | Verdict |
|---|---|---|---|
| experiment | 678 | **679** | ✗ |
| experiment_run | 39 | **39** | ✅ |
| sys_user | **3** | **5** | ✗ |
| running / queued | 0 | **0** | ✅ |

**The details of the residue**:

| Object | Value | Source |
|---|---|---|
| sys_user id 43 / 44 | `rbac_op_22244` / `rbac_viewer_22244` | created by `verify-rbac.py`, not cleaned up |
| experiment 962 | `rbac-operator-允许建实验-22244` | the same (the run with PID 22244) |

**Root cause**: an **interrupted** `verify-rbac.py` (the name embeds PID=22244; this round the reviewer re-ran it as 24643, which cleaned up after itself)
did not reach the `finally` of the [cleanup section](../../../scripts/verify-rbac.py#L216-L230).
The cleanup only deletes the experiments from the `created_exp` list and the accounts by `username LIKE 'rbac_%'`; **if the process is killed outright the deletion is missed**,
and the **experiments have no "fallback by name"** (asymmetric with the accounts).

**Impact**: it pollutes the e2e (see §3), and it also makes the `01` §9 data snapshot untrue.

**Fix suggestion**: add a `name LIKE 'rbac-%'` fallback to the experiment cleanup (symmetric with `username LIKE 'rbac_%'`) so that it survives an interruption; and clear the existing residue.

---

## 5. Self-consistency issues (P2)

| # | Location | Problem | Evidence |
|---|---|---|---|
| 1 | [00-索引与评审指引.md:11](../../评审包-20261104/00-索引与评审指引.md#L11)、[01:3](../../评审包-20261104/01-本轮交付清单与验证.md#L3) | It writes "the range `3aa4837..ec28d02`, **25** commits"; the git semantics of `A..B` **do not include A**, the real number is **24** | `git rev-list --count 3aa4837..ec28d02` = 24 (it is 25 only when the base `3aa4837` is included) |
| 2 | [01:44](../../评审包-20261104/01-本轮交付清单与验证.md#L44) | It writes `MetaApi 109` (total **3487**); on HEAD MetaApi has grown to **132** (total **3510**) with `f2d7e8e` (device-models) | `wc -l MetaApi.java` = 132; at the time of the split `deb6aed` it was 109 |
| 3 | [00:33](../../评审包-20261104/00-索引与评审指引.md#L33) | The re-run command `python3 scripts/device-go-live-check.py` **carries no DB env**, but `01` §7 reports the 9/2 **with** the DB; without the env it is really 8/3 (the "binding coverage rate" is skipped) | The two runs compared in §2 |

---

## 6. The sampled conclusions on the review focus points of `00` §3

| Focus point | Code-reading conclusion | Verdict |
|---|---|---|
| ⑤ The status vocabulary has a single source | The backend `StatusVocabulary` is the only authority + `GET /api/meta/status-vocabulary`; the frontend `utils/status.ts`; no local mapping in the 8 pages (a `grep` of the page directory finds no `STATUS_LABEL` / no local map) | ✅ |
| ③ The last-administrator lock | advisory lock + `FOR UPDATE` + a single transaction, the cross-row constraint check is correct; the advisory lock is a **database-level primitive**, so it still holds in a future multi-instance setup (given in §2) | ✅ |
| ④ The equivalence of the ApiServer split | The dispatch is a chained `\|\|`, the if/else order inside a controller is preserved; **the equivalence evidence (34/34 route pairs compared) was not independently re-run this round**, the delivery evidence is taken on trust | Not independently verified |
| ① The scheduling / recovery semantics | `rehydrateQueued`, the automatic re-queue after the hold is released (every 5 ticks, a single failure does not bubble up), `MAX_CONCURRENT=4` are all there; **the concurrency correctness was not reproduced under a load test** | Partial |
| ⑥ The sufficiency of the tests | See §3/§4: **the e2e is sensitive to the data distribution** + **the joint-debugging script leaves residue** — that is the real shortcoming of the current test system (not a lack of test cases) | ✗ |
| ⑦ The data / startup recovery | BUG-14 `settleOrphanExperimentRuns` is present; this round did not independently reproduce "the first start clears 2 / the second start clears 0" | Not independently verified |

---

## 7. Remediation update (filled in by the reviewed party / the implementer, append-only, never modified)

### 7.1 Summary of the remediation (the implementer · 2026-11-04)

> This section is the **implementer's** item-by-item response (append only, the text above is not changed). The reviewer is `Trae AI`, the implementer belongs to the same DeepSeek family, so the limitation of the independence is in the statement in §0.
> **Conclusion: all 5 items (1 blocking + 1 high + 3 P2) have been fixed and re-verified.**

| Id | Conclusion | Remediation | Re-verification |
|---|---|---|---|
| B1 (blocking) | True | Clear the residue + change step 4 of `e2e-smoke.py` (do not take the first row) | `python3 scripts/e2e-smoke.py` → **24 / 0** |
| H1 (high) | True | Clear the residue + add a fallback by name to the `finally` of `verify-rbac.py` | `sys_user` 5→**3**, `experiment` 679→**678** (matches `01` §9) |
| P2-1 | True | `00` §1/§5 and the `01` header 25→**24** | `git rev-list --count 3aa4837..ec28d02` = **24** |
| P2-2 | True | `01` §4 `MetaApi 109/3487` → **132/3510** | the 8 controllers `wc -l` total **3510** |
| P2-3 | True | Add the DB env precondition to the go-live command of `00` §2 | without the env **8/3**, with the env **9/2** — both reproduced |

### 7.2 Item-by-item response (the review guidelines §4: conclusion / reason / remediation / verification)

**B1 (blocking) the e2e "24 / 0" is not reproducible → fixed**
- **Conclusion**: true. The root cause holds —— the first row of the list ordered by `ORDER BY id DESC` is the leftover experiment 962 (**0 runs**), the execution history has no expandable row, so the assertion fails for sure (deterministic, not flaky).
- **Reason**: the script has an **implicit strong dependency** on "the first row of the list always has an execution history"; the leftover data (H1) happens to push the run-less experiment to the top, and the two together expose it.
- **Remediation**: (1) clear the residue (see H1); (2) change [e2e-smoke.py](../../../scripts/e2e-smoke.py): step 4 changed from "click the first row" to **walking the rows and taking the first experiment that has an expandable execution history (at most 8 rows tried)**, which removes the data-distribution dependency.
- **Verification**: `python3 scripts/e2e-smoke.py` → **24 passed / 0 failed**.

**H1 (high) the RBAC joint-debugging residue pollutes the database → fixed**
- **Conclusion**: true. The violation conflicts directly with `01` §9 "no rbac test residue".
- **Reason**: the cleanup of `verify-rbac.py` only deletes the experiments from the `created_exp` list; if the process is killed outright (the batch with PID=22244) the `finally` is skipped and the experiments are never deleted. On the user-table side there is already a `username LIKE 'rbac_%'` fallback, but on the experiment side a **symmetric fallback is missing**.
- **Remediation**: change the `finally` of [verify-rbac.py](../../../scripts/verify-rbac.py), and in addition to deleting the users, symmetrically add `DELETE FROM experiment WHERE name LIKE 'rbac-%'` (deleting `experiment_run` first to satisfy the foreign key), an **interruption-proof** fallback; and the historical residue has been cleared by name.
- **Verification**: measured against a live database → `sys_user`=**3**, `experiment`=**678**, consistent with the `01` §9 snapshot.

**P2-1 the commit count 25 → 24 → fixed**
- Conclusion / reason: true; the git semantics of `A..B` do not include A.
- Remediation: [00:11](../../评审包-20261104/00-索引与评审指引.md#L11)、[00:64](../../评审包-20261104/00-索引与评审指引.md#L64)、[01:3](../../评审包-20261104/01-本轮交付清单与验证.md#L3) all changed to **24 commits**.
- Verification: `git rev-list --count 3aa4837..ec28d02` = **24**.

**P2-2 MetaApi 109 / total 3487 → 132 / 3510 → fixed**
- Conclusion / reason: true; at the time of the split `deb6aed` it was 109, and afterwards `f2d7e8e` (device-models) grew it to 132.
- Remediation: [01:44](../../评审包-20261104/01-本轮交付清单与验证.md#L44) changed to `MetaApi 132（合计 3510 行；… 由 109 增至 132）` ("MetaApi 132 (3510 lines in total; … grew from 109 to 132)").
- Verification: the 8 controllers `wc -l` total **3510**.

**P2-3 the go-live command yardstick is inconsistent → fixed**
- Conclusion / reason: true; the `00` §2 command lacks the DB env, while `01` §7 reports the 9/2 with the DB.
- Remediation: the re-run command of [00:33](../../评审包-20261104/00-索引与评审指引.md#L33) gained the DB env precondition, and both yardsticks are annotated.
- Verification: without the env **PASS 8 / WARN 3**, with the env **PASS 9 / WARN 2 / FAIL 0** — both reproduced by measurement.

> All three P2 items **rewrite the body directly**, they do not use "keep the old text and add an erratum" (which conforms to the review guidelines §8 "document self-consistency").

### 7.3 The items not handled / kept

- The three "not independently verified" items of §6 (④ the 34/34 route comparison, ① the concurrency load test, ⑦ the first start clears 2 / the second start clears 0): these are **items the reviewer did not independently re-run**, not defects; the implementer's evidence is kept, and they can be re-run as needed in the next round of review.
- Per the user's rule "multiple review rounds must switch model family", the implementer of this round is also from the DeepSeek family, so **the conclusion still needs to be re-verified by a reviewer from a non-DeepSeek family**.

**Remediation sign-off**: Trae AI (DeepSeek-V4.1-Flash) · 2026-11-04 · the implementer (took over the remediation of this round of review)

---

**Sign-off**: Trae AI (DeepSeek-V4.1-Flash) · 2026-11-04 00:14 CST · review target `main @ 21329cf`
