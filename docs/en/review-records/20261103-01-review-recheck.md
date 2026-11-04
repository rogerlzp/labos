# Review Record 20261103-01 (review package re-check · code-reading level + command measurement)

> **English edition.** The Chinese original is [`docs/评审记录/20261103-01-评审包复核.md`](../../评审记录/20261103-01-评审包复核.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Field | Value |
|---|---|
| **Review id** | `20261103-01` |
| **Review time** | 2026-11-03 09:47 (UTC+8) |
| **Reviewing model** | DeepSeek-V4.1-Flash (executed by TraeCode Agent) |
| **Review target** | `docs/评审包-20261103/` (`00-索引与评审须知.md` / `01-功能文档.md` / `02-技术文档.md` / `03-设计文档.md` / `04-测试文档.md`); the code `main @ 30cfc51` |
| **Review scope** | **All the numeric claims** of the four documents, the 8 review points of `00` §4, the 3 focus points the user named (D2 / D4 / D6) |
| **Review method** | **Code-reading level + reproducible command measurement** (re-running `test-all.sh --pure-only`, re-running `javac`, `git`/`find`/`wc` counts); **no database connection** (no password) |
| **Conclusion** | **Fail** |

> **Yardstick**: this record only verifies "whether what the document says equals what the code / the measurement looks like"; it does not judge the functional design direction.
> Per the review guidelines §8, this file is **append-only, never modified**; the reviewed party's response is written in the "remediation update" section at the end.
>
> **Statement of independence (important)**: the reviewing party and the reviewed party (the document author) of this round **belong to the same DeepSeek family**, so their blind spots may overlap.
> The conclusion should be regarded as **"code-reading + command-level"** evidence, **which is not the same as "run-level" verification** against a live database / a real device;
> the next round has to be re-verified by a reviewer from a **non-DeepSeek family**.

---

## 1. Conclusion summary

**Fail.** 1 blocking + 2 high-severity + 5 self-consistency problems.

| Category | Claim | Measured | Verdict |
|---|---|---|---|
| Pure-logic test count | **373** (8 places in the whole document) | **368** (actual run of `test-all.sh --pure-only`) | ✗ Blocking |
| `DeviceErrorChainSelfTest` | 19 | **14** | ✗ (the source of the 373→368 difference) |
| D4 resume-from-breakpoint row deletion | "delete the `run_node` of the unfinished nodes (`run_command` cascades with the foreign key)" | Only `level=0` is deleted; the step rows and `run_command` are not deleted | ✗ High |
| D2 the moment `bypassed` is marked | "it is only marked after the whole run has succeeded" | `SKIPPED` is not excluded, so a paused run is marked too | ✗ High |
| Java / frontend / docs line counts | 30,241 / 8,048 / 52 files | all match | ✅ |
| Table count, error-code count, device-instance count, etc. | 112 / 25 / 248… | **not re-verified** (no password, no database connection) | Unverified |

---

## 2. Items verified as true (evidence)

The following were confirmed by reading the code / measuring, item by item, to be **consistent with the claim**, and can be used as a trustworthy baseline:

| Item | Claim | Verification evidence | Verdict |
|---|---|---|---|
| Java scale | 30,241 lines / 11 packages | `find src/main/java -name '*.java' \| xargs wc -l` = 30241; 11 sub-packages under `kernel/` (driver 25 including the sub-package `driver/tcp`) | ✅ |
| Frontend scale | 8,048 lines / 19 pages | `find webapp/src -name '*.ts*' \| xargs wc -l` = 8048; `find webapp/src/pages -name '*.tsx'` = 19 | ✅ |
| Historical documents | 52 files | `ls docs/*.md \| wc -l` = 52 | ✅ |
| Version (frontend) | React 18.3 / antd 5.29 / bpmn-js 17.11 / vite 5.4 | consistent with what `package-lock.json` actually installs | ✅ |
| Focus #1 | The policy matches with "two-stage compose, `scope` takes priority" | [RecoveryPolicyResolver.java](../../../labos-kernel/src/main/java/com/labos/kernel/recovery/RecoveryPolicyResolver.java#L21-L37) `specificity DESC → priority ASC → findFirst` | ✅ |
| Focus #1 | A failed tolerance plan must fall back to the driver-domain scope | [DriverStepExecutor.java](../../../labos-kernel/src/main/java/com/labos/kernel/driver/DriverStepExecutor.java#L230-L255) `scopeOf` lazy loading + a sub-domain-prefix fallback; all 5 `throw`s use it | ✅ |
| Focus #2 | The fan-out is "parallel in the interface, serial in reality" | [GraphRunner.java](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L223-L288) a single-threaded ready queue, so it really is **logically parallel** | ✅ |
| Focus #6 | The back-edge cap: 10 rounds per node + 500 steps in total | [GraphRunner.java](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L116-L126) `maxVisits=10` (clamp 1–100); [:223](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L223) `maxSteps=500` | ✅ |
| Focus #7 | The page state matches `run_command` | [QueryService.runGraphJson](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L658-L733) converts `running→pending` for a single child that is not `running` ([:688-733](../../../labos-kernel/src/main/java/com/labos/kernel/api/QueryService.java#L688-L733)); the colour mapping is consistent with the table in [02 §4](../review-20261103/02-technical.md) | ✅ (the only gap is H2) |
| Idempotence / unique key | `createNodeShells` is idempotent | [WorkflowService.java](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1432-L1520) reuses the rows that already exist | ✅ |
| Unique key / cascade | `uk_run_node`, the `run_command` cascade | [20_kernel.sql:308](../../../pg_rebuild/schema/20_kernel.sql#L308) `UNIQUE(run_id,node_key,attempt)`; [:320](../../../pg_rebuild/schema/20_kernel.sql#L320) `REFERENCES run_node(id) ON DELETE CASCADE` | ✅ |

---

## 3. Blocking level

### B1. The test count is untrue: 8 places in the whole document write "373", the measurement is 368

**Reproduction command (the document's original text, not changed by a single character)**:

```bash
./scripts/test-all.sh --pure-only
# Document expectation: 373 passed / 0 failed
# Measured output: 368 passed / 0 failed (2 database tests skipped)
```

**Attribution of the difference (down to the single place)**: after comparing the case lists of the four documents category by category, **only one place is wrong** —
[04 §3](../review-20261103/04-testing.md) marks
`driver.DeviceErrorChainSelfTest` as **19**, the actual run gives **14**. The other 10 categories all match
(196 / 41 / 30 / 23 / 17 / 14 / 13 / 13 / 6, + `SidecarChainTest` 7 counted as 1):

```
196+41+30+23+17+14+13+13+6+1 = 354  ... by the document's yardstick (with 19) it comes to 373
196+41+30+23+17+14+13+13+6+1 = 368  ... by the measured yardstick (with 14) it comes to 368
```

That is, **373 − 368 = 5 = 19 − 14**: a single typo cut through the whole document.

**Impact (not only that the number is off)**: the document writes "expectation: 373 passed / 0 failed" in
[04 §2](../review-20261103/04-testing.md),
[04 §8](../review-20261103/04-testing.md),
[00 §6](../review-20261103/00-index.md).
A third party executing it by the document sees 368, and **the first reaction is "5 are missing, a regression is suspected"**.
This directly conflicts with the project's hard constraint "an inconsistent statistic in the internal documents = a blocking review finding".

**Where it occurs**: [00:36](../review-20261103/00-index.md), [00:146](../review-20261103/00-index.md), [02:96](../review-20261103/02-technical.md), [04:6](../review-20261103/04-testing.md), [04:30](../review-20261103/04-testing.md), [04:51](../review-20261103/04-testing.md), [04:69](../review-20261103/04-testing.md), [04:151](../review-20261103/04-testing.md).

---

## 4. High severity (hits the focus points #2, #4 the user named)

### H1. The scope of D4's "row deletion" is incomplete — the step/command audit records after a resume are **silently lost**

**Chain of facts (code reading + schema)**:

1. The condition of the [deletion in `resumeAsync`](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L547-L554) is
   `LEVEL = 0 AND status <> 'completed'` —— **only the business nodes are deleted**.
2. But according to [03 D8](../review-20261103/03-design.md), `run_command` hangs on the **`level=1` step nodes**;
   and the `run_node.parent_node_id` of [96_schema_fix.sql:103](../../../pg_rebuild/schema/96_schema_fix.sql#L103) is only a bare `bigint`, **with no foreign key**.
3. So: deleting a `level=0` row **does not cascade** to the `level=1` rows, and neither to any `run_command`
   (their FK points at the id of `level=1`, see [20_kernel.sql:320](../../../pg_rebuild/schema/20_kernel.sql#L320)).
   → **The D4 body text's "(`run_command` cascades with the foreign key)" does not match the implementation.**
4. When the run is continued and re-executed, the [`seq` of `PersistingStepSink`](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1651-L1652)
   is a **newly created `AtomicInteger`**, and the step keys start again from `S1`
   ([writeStep:1841](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1841) `"S" + seq.incrementAndGet()`),
   and they collide with the **`S1/S2` that were not deleted** on `uk_run_node(run_id,node_key,attempt)` ([20_kernel.sql:308](../../../pg_rebuild/schema/20_kernel.sql#L308)).
   `attempt` is 0 the first time (the initial value of [NodeProgress](../../../labos-kernel/src/main/java/com/labos/kernel/engine/NodeProgress.java) is 0), so the existing rows have the same value → a collision is certain.
5. That `SQLException` is [caught in `onStepDone` and turned into one `[WARN] 步骤落库失败`](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1789),
   and **the execution is not interrupted** → this stretch of the resumed run — "which step, what was sent, what came back" — is simply missing from the database.

**Why the existing acceptance test cannot find it**: the [resume assertion of `verify-device-chain.sh`](../../../scripts/verify-device-chain.sh#L280-L286)
checks only two things — three `completed` at `level=0`, and exactly 2 `收到 Seal` entries in the simulator log.
**It looks at "was the command sent" and "did the business node complete", and never at whether `run_command`/`level=1` was persisted**,
so the hole is covered by a green light. It lands right on the general principle of "traceability" ([03 §0](../review-20261103/03-design.md)).

**Boundary of the evidence**: this item is **derived statically from the schema + the code**, and **was not run against a live database**.
**The cheapest way to re-check it**: run a three-step resume once and then execute

```sql
SELECT node_key, level, status FROM run_node WHERE run_id = :RID ORDER BY id;
SELECT count(*) FROM run_command WHERE run_node_id IN (SELECT id FROM run_node WHERE run_id = :RID);
```

If after the resume the number of `level=1` rows has not grown, or the number of `run_command` rows ≠ the number of commands actually sent, it is confirmed.

### H2. D2's "only mark `bypassed` after the whole run has succeeded" does not match the implementation

**Chain of facts**:

1. The [exclusion condition of `markBypassedNodes`](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1511-L1517) is
   `MANUAL>0 || ABORTED>0 || aborted()`, and **it does not exclude `SKIPPED` at all**.
2. And `SKIPPED` makes [settleRun](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L2178-L2196)
   settle the whole run as `paused / pause_kind='waiting_human'` (which is right in itself).
3. The two stack up: in a run that "stopped to wait for a human because of a skip", **the nodes that have not executed yet are changed from `running` straight to `bypassed`**
   ([:1522](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1522) `SET status='bypassed'`).
4. And [01 §4.2](../review-20261103/01-functional.md) defines it itself:
   a light grey dotted line (`bypassed`) = the branch was not selected, a grey dashed line (`pending`) = it has not come up yet.
   → The page draws "it has not come up yet, it is waiting for a human" as "this branch does not have to be taken", **the display does not match the truth** (exactly the counter-example of focus #7).

**Trigger condition**: there is a policy with `strategy='skip'` in the database. The seed data has been checked and **has** no skip policy,
so it is currently a **latent defect**; it only shows up once operations configures one in `/error-codes`.
The D2 body text says "in a failed/paused run `running` means 'to be resumed', marking it would make the resume logic misjudge" —— **the code does not implement that sentence**.

**An aside, a design-level question (not a bug)**: a policy **named `skip` can never skip anything** ——
`SKIPPED` always halts ([GraphRunner:308-315](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L308-L315) + `settleRun`).
Whether the option offered to operations in the UI should be renamed, or should carry a hint, has to be confirmed by the reviewed party.

---

## 5. The three points the user named · adjudicated one by one

| # | Focus point | Ruling | Basis |
|---|---|---|---|
| 1 | **D2 (`skipped` vs `bypassed`)** | **The core semantics are correct, but the implementation boundary does not match the document**. `skipped` = stop and hand over to a human, `bypassed` = carry on normally, consistent in three places: [NodeStatus](../../../labos-kernel/src/main/java/com/labos/kernel/engine/NodeStatus.java#L21-L36), [GraphRunner](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L275-L315), [settleRun](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L2178-L2196). The problem is only that the "only marked on success" of [H2] never made it into the code | Code reading |
| 2 | **D4 (row deletion + `skipKeys`)** | **The mechanism is there, but the row deletion is incomplete (see H1)**. The `skipKeys` propagation chain (`resumeAsync → execute → withSkipKeys`, [GraphRunner:132](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java#L132) / [WorkflowService:780](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L780)) and the idempotence of `createNodeShells` are both fine; what is missing is **the cleanup of the step rows** | Code reading + schema |
| 3 | **D6 (the safety boundary of the evaluator)** | **True, "there really is no injection point"**. [ConditionEvaluator.java](../../../labos-kernel/src/main/java/com/labos/kernel/engine/ConditionEvaluator.java) is a pure recursive-descent parser, with no `eval`/`ScriptEngine`/reflection; unknown identifiers, trailing content and `;` concatenation all raise an exception; the injection case ([ConditionEvaluatorSelfTest.java:54](../../../labos-kernel/src/test/java/com/labos/kernel/engine/ConditionEvaluatorSelfTest.java#L54)) really exists. In addition, a repository-wide scan for `ScriptEngine\|Nashorn\|getRuntime\|ProcessBuilder\|Class.forName\|invoke` finds **no evaluation entry point at all** in main (only two shutdownHooks and one JDBC dynamic proxy, unrelated to this item); `expr.Expression` is hand-written too | Code reading + repository-wide scan |

---

## 6. Document self-consistency issues (P2)

| # | Location | Problem |
|---|---|---|
| 1 | [02:94](../review-20261103/02-technical.md) | It still writes `.NET 8`, but [02:47](../review-20261103/02-technical.md) and [00:47](../review-20261103/00-index.md) in the same documents both write `.NET 10`, and the `csproj` really is `net10.0`. **It contradicts itself inside one document**, and it does not match the author's "already changed" claim |
| 2 | [00:127](../review-20261103/00-index.md) | The risk list says "this package is known to contain a password", but [00:56-57](../review-20261103/00-index.md) explicitly writes placeholders and notes that "this repository **does not store** any database password", and [04:33](../review-20261103/04-testing.md) is also `...`. **Two sentences of the same document contradict each other** (either drop the risk entry, or the body really does contain a password) |
| 3 | [00:4](../review-20261103/00-index.md) | It writes `main @ 4570113`, which does not match the `30cfc51` of the opening prompt (`4570113` is the parent commit of `30cfc51`). The two should be unified |
| 4 | [04:20](../review-20261103/04-testing.md) | It claims "any machine can `git clone` and verify immediately". **The measurement does not hold**: [test-all.sh:30](../../../scripts/test-all.sh#L30) compiles only `src/test`, not `src/main`, so on a clean checkout (without `out/`) `javac` reports "cannot find symbol" and exits. [00 §3.3①](../review-20261103/00-index.md) has to be run first. Also: 7 self-test classes sit in `src/main/java` (so they end up in the artifact) — it is worth noting that, or moving them to `src/test` |
| 5 | [02:88](../review-20261103/02-technical.md) | It marks `react-router 6.27` / `react-query 5.59` as "verified versions", but those are only the **lower bounds** of `package.json`; `package-lock.json` actually installs `6.30.6` / `5.105.0` |

---

## 7. The shortest action list for the fixing party (by priority)

1. **[B1]** Change the `19` of [04:59](../review-20261103/04-testing.md) to the measured `14`; change the 8 `373` in the whole document to `368`. **Rewrite the body directly** — "keep the original text and add an erratum" is not allowed.
2. **[H1]** Pick one of the two and do **run-level** verification: (a) make the resume deletion **cover the `level=1` step rows as well** (using `run_id + level>=0 + status<>'completed'`, or add a cascading foreign key to `parent_node_id`); (b) make the `seq` of [PersistingStepSink](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1645-L1652) continue from the `max` in the database. After the change you **must** add **a `run_command` row-count assertion** to the resume case of `verify-device-chain.sh`, otherwise the hole is still there.
3. **[H2]** Add `SKIPPED` to the exclusion condition of [markBypassedNodes](../../../labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java#L1511-L1517); or unify the success criterion of `settleRun` into one function.
4. **[Document P2]** Fix all 5 items of §6, all by **rewriting the body directly**.
5. **[Unverified items]** The `count(*)` yardsticks — table count, error-code count, device-instance count, workflow count — have to be re-checked by a reviewer **with database access** before they are taken on trust.

---

## 8. Remediation update (filled in by the reviewed party)

> The reviewed party answers item by item here: conclusion / reason / remediation (with a commit or file line numbers) / verification.
> §1–§7 of this record **must not be changed** (this section is an appendix).
>
> | Field | Value |
> |---|---|
> | Remediation commits | `ad8fb32` (code + documents), `40f44f4` (the first draft of this response) |
> | Re-check baseline | `main @ ad8fb32` |
> | Position | **§1–§7 are accepted in full, without objection** (including the reviewer's statement of independence) |

### 8.1 Conclusion at a glance

| Review item | Review conclusion | Reviewed party | Remediation | How it was re-verified |
|---|---|---|---|---|
| **B1** test count 373≠368 | Blocking | Accepted (the root cause is environment-dependent, not a typo) | `--pure-only` forcibly clears `LABOS_DB_*` → constantly **368/0**; with the database **386/0**; 8 places in the document changed to 368, 19 changed to 14 | `./scripts/test-all.sh --pure-only` |
| **H1** incomplete row deletion on resume | High | Accepted, **reproduced at run level**, and one more thing was found (S2 was overwritten to completed, hiding the failure history) | Delete `level=1` first, then `level=0` (the commands cascade with the FK); the step sequence continues from the `max` in the database; the script gains 3 assertions | `verify-device-chain.sh` 37/0 |
| **H2** `bypassed` does not exclude SKIPPED | High | Accepted | The exclusion condition gains `SKIPPED`; measured, with a `skip` policy the downstream nodes stay `pending` | Temporarily change the policy and run a three-step flow once |
| **P2-1** `.NET 8` left over | Self-consistency | Accepted | Changed to `net10.0` | — |
| **P2-2** "this package contains a password" contradicts the body | Self-consistency | Accepted | That entry changed to "only placeholders are used" | — |
| **P2-3** the version names the parent commit | Self-consistency | Accepted | Changed to `30cfc51` | — |
| **P2-4** "a `git clone` is enough to run it" does not hold | Self-consistency | Accepted | The script compiles `src/main` first; the 7 self-test classes move to `src/test` | Delete `out/` and run directly → 368/0 |
| **P2-5** the version is marked "verified" | Self-consistency | Accepted | The lock-installed 6.30.6 / 5.105.0 were added | — |
| **Unverified items** (the in-database yardsticks) | Unverified | Now measured | See 8.6 | The SQL can be re-run |

### 8.2 Blocking item B1: accepted, and changed into a "deterministic" number

The review is right; it was inaccurate of me to record the cause as "a typo". **The root cause is environment-dependent**:

`DeviceErrorChainSelfTest` contains a set of real-database assertions that "only run when `LABOS_DB_*` is set" (5 of them).
The reviewer had no database variables → 14 cases; my shell has them → 19 cases. The same code produces two numbers,
so whichever one the document writes, it misleads a third party.

| Item | Remediation |
|---|---|
| Root cause | The test class embeds an environment branch, which conflicts with the definition of "pure logic" |
| Fix | `scripts/test-all.sh` **`unset LABOS_DB_*`** when `--pure-only` is given (test-all.sh:33-41), so that branch never runs |
| Result | `--pure-only` is constantly **368 / 0**; a full run with the database is **386 / 0** (368 + 5 + 3 + 10) |
| Documents | All 8 `373` changed to `368`; `DeviceErrorChainSelfTest` changed from `19` to `14`, with the note "+5 cases run only with the database"; both yardsticks (368/386) are written into 00 §2 and 04 §3 |
| Commitment | From now on the statistic is based on `--pure-only`, and **one document writes only one number** |

Verification:

```
$ ./scripts/test-all.sh --pure-only
passed 368 / failed 0 (2 database tests skipped)
$ ./scripts/test-all.sh          # with the database
passed 386 / failed 0
```

### 8.3 High-severity item H1: accepted, **reproduced with run-level evidence**, more serious than the static inference

The review "derived it statically from the schema + the code"; I ran it against the live database, which confirms it and adds one more finding:

```
After the pause: level1=2  cmd=2
Change the paper → continue: the simulator received 2 Seals
After the resume: level1=2  cmd=2      ← the command was sent, but nothing was recorded in the database
The log: 2 entries of [WARN] 步骤落库失败: duplicate key ... uk_run_node
The additional finding: S2 (failed) was UPDATEd to completed —— the failure history is covered up
```

**The additional finding** is more serious than the original judgement: it is not just "less was recorded", it is **that records were overwritten**.

| Item | Remediation |
|---|---|
| ① The step rows cannot be deleted | `resumeAsync` changed to **delete `level=1` first** (`DELETE ... USING run_node p WHERE p.id=s.parent_node_id AND p.level=0 AND p.status<>'completed'`), then delete `level=0`; `run_command` goes along with the `ON DELETE CASCADE` (WorkflowService.java:543-566) |
| ② The sequence collides with the unique key | The step sequence now continues from `max("S"+n)` in the database: `maxStepSeq(runId)` added, `PersistingStepSink.seq` initialised from it (WorkflowService.java:1646-1700) |
| ③ The assertion gap | `verify-device-chain.sh` gains 3: step rows = 3 / command rows = 3 / no duplicate key; **34 → 37 assertions** |

Verification (`verify-device-chain.sh`, all green 37/0):

```
✓ The resume sent only 2 commands (N1 was not executed again)
✓ 3 step rows (S1 kept + 1 each for N2/N3)
✓ 3 command rows (1 before the pause + 2 in the resume)
✓ No uk_run_node conflict
```

The same one reproduced by hand:

```
After the resume level0=3 level1=3 cmd=3 status=completed
N1/0/completed  N2/0/completed  N3/0/completed
S1/1/completed  S2/1/completed  S3/1/completed
the sim received 2 Seals; the log has duplicate key = 0
```

### 8.4 High-severity item H2: accepted, the `SKIPPED` exclusion has been added (and measured)

| Item | Remediation |
|---|---|
| Code | The exclusion condition of `markBypassedNodes` gained `progress.count(NodeStatus.SKIPPED) > 0` (WorkflowService.java:1511-1522) |
| Verification | Temporarily changed the node policy of `EC-08-4001` to `skip×1`, injected `0x80000001` and ran a three-step flow: `N1=completed / N2=skipped / N3=running`, the whole run `paused(waiting_human)`; **N3 was not marked `bypassed`** (it would have been before the fix) |
| Restoration | After the verification the policy was changed back to `node/retry/3` |

**On the design question the review raised ("a `skip` policy can never skip anything") —— confirmed: this is intentional, the runtime is not changed.**
The semantics of `SKIPPED` are "this step should have been done but was not ⇒ the plate position is not trustworthy"; carrying on would let the next plate pickup reach into an empty position.
So the policy is named `skip`, and the effect is "skip and then stop to wait for a human confirmation".
**The UI text has been changed** to remove the ambiguity: `skip · skip (skipping stops the run and waits for an operator)` (webapp/src/pages/ErrorCodes.tsx:28).

### 8.5 Document self-consistency P2: all 5 items accepted and changed

| # | Problem | Remediation |
|---|---|---|
| 1 | `.NET 8` left over in 02 | Changed to `net10.0` (02-技术文档.md:94) |
| 2 | The 00 risk entry "this package contains a password" contradicts the placeholders in the body | That entry changed to "the password only circulates through internal channels, the body only uses placeholders" (00:127) |
| 3 | The 00 version writes `4570113` (the parent commit) | Changed to `30cfc51`, with the note "see the remediation update of this record for the revision commit" (00:4) |
| 4 | "a `git clone` is enough to run it" does not hold | ① `test-all.sh` compiles `src/main` first when `out/` is empty; ② the 7 self-test classes were `git mv`-ed to `src/test/java` (they no longer end up in the artifact). A clean checkout can now really be run directly |
| 5 | The react-router/react-query versions are marked "verified" | Changed to "`package.json` declares + `package-lock` actually installs 6.30.6 / 5.105.0" (02:88) |

Verification (simulated clean checkout): after deleting `out/`, running `./scripts/test-all.sh --pure-only` directly first prints 「（首次运行：先编译 src/main）」 ("(first run: compiling src/main first)"), and then 368/0.

### 8.6 The unverified items (the reviewer had no password) —— measured by the reviewed party

The reviewer marking the in-database yardsticks as "unverified" is right. The reviewed party has database access and added the measured values (`2026-11-03 10:10`):

| Claim | Measured |
|---|---|
| 112 tables | `information_schema.tables where table_schema='public'` = **112** |
| 25 error codes / 27 policies | `error_code` = **25**, `recovery_policy` = **27** |
| 76 device models / 248 instances | `device` = **76**, `device_instance` = **248** (including soft deletes) |
| workflows / nodes | Take `count(*)` from the database; do not freeze the scale in the document |
| 659 experiments / 10642 runs | `experiment` = **659**, `run` = **10642** |
| 120043 node executions / 116626 commands | `run_node` = **120043**, `run_command` = **116626** |
| 15 partitions each for `device_log`/`run_event` | **15** each (202609–202711 + `_default`) |

### 8.7 Independence and the residual risks

The note "both belong to the DeepSeek family" is very important. The remediation evidence of this round falls into two categories:
- **Code-reading level** (B1 / P2): objectively reproducible;
- **Run level** (H1 / H2): the SQL and the script assertions are given, so any party with database access can re-run them.
It is recommended that the next round be run by a reviewer from a **non-DeepSeek family** who re-runs `test-all.sh --pure-only` (368),
`verify-device-chain.sh` (37), and spot-checks the SQL of H1.

### 8.8 What this round did **not** handle (explicitly out of the remediation scope, already in the ROADMAP)

> The items below have been merged into the repository-root [`ROADMAP.md`](../roadmap.md): M6 (one row per loop iteration, the `parent` foreign key),
> M7 (expression arithmetic, adding/deleting error codes), M10 (the real device). **The ROADMAP is the only authoritative source for the plan**, this table is only a review record.

| Item | Why it is not touched | Plan |
|---|---|---|
| `run_node` has only one row when a loop is re-entered | The table structure has to change (`loop_index`), which affects the resume / the queries / the frontend | A separate item, see 8.7 P1 |
| Add the foreign key to `run_node.parent_node_id` | The historical dirty data has to be cleaned up first (248 instances / 120k nodes), the migration risk is higher than the benefit | Done together with the previous item |
| The condition expression does not support arithmetic (`${a}+1 > 3`) | A capability question of the evaluator, not a defect | P3 |
| The real device / long stability / concurrency correctness | The environment is not available | The on-site joint-debugging phase |
| Error codes can only be edited, not added or deleted | The codes follow the manuals, adding one has to go through the model library | P3 |

### 8.9 Suggestions for the next re-check (for a reviewer from a non-DeepSeek family)

> These three have already been written into §5 "Definition of done (DoD)" of `ROADMAP.md`, as a hard gate for every phase.

Ordered by "cheapest in time + most able to falsify":

```bash
# ① Test baseline (1 minute, no database needed)
./scripts/test-all.sh --pure-only            # expected 368 / 0

# ② Device chain (3 minutes, needs the simulator; the script starts the sidecar itself)
export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
./scripts/verify-device-chain.sh             # expected 37 / 0

# ③ Spot-check of H1 (with database access): after one three-step resume
SELECT node_key, level, status FROM run_node WHERE run_id = :RID ORDER BY level, id;
SELECT count(*) FROM run_command WHERE run_id = :RID;
# Expected: three level=1 rows (S1 kept + S2/S3 new), 3 command rows
```

Watch the "residual risks" of P0/P1 in 8.7 closely — those are the costs the current design explicitly accepts, not omissions.
