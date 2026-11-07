# Review Package · 2026-11-06 (for reviewing AIs)

> Purpose: a **self-contained** delivery summary + how to re-run + a list of review questions.
> The previous review package is `docs/评审包-20261105/` (gateway pairing + page exercise; not
> repeated here); before that `docs/评审包-20261104/`. The review records live in `docs/评审记录/`.
>
> **This round in one sentence**: "pause during a device command" moved from *plan only* to
> *a working interrupt mechanism*, and that work uncovered **4 real bugs** (three of them the
> classic "the value is stored but nobody reads it / the lock leaks / the criterion is ambiguous"
> family).

## 1. Snapshot

| Item | Value |
|---|---|
| Repository | `github.com/rogerlzp/labos` (main) |
| Delivery snapshot | this round's code snapshot = `cf53bd8`; the review **is against that**; this review package itself is a **doc-only commit** on top of `cf53bd8` and contains no code or test changes |
| This round's range | `bb97b62..cf53bd8`, **12 commits**; earlier commits are the repository rebuild and narrative clean-up and are out of scope |
| Environment | macOS, Java 17 (no Maven, full `javac` build); PostgreSQL 17.10 (database `labdb`); API `127.0.0.1:18080`; front-end dev `localhost:5173`; device simulators `19500–19506` |
| Test baseline | pure logic **644/0**; database-connected suite **1031/0**; gate **22 segments, all green, zero skipped** (`ci-gate.sh --all`); EN/CN docs `verify-en-docs.py` hard errors **0** (registry 100 entries / 443 relative links all resolve) |
| Gate thresholds | `MIN_*` at the top of `scripts/ci-gate.sh`: `MIN_PURE=644` `MIN_CHAIN=41` `MIN_PFLOW=4` `MIN_PEXP=3` `MIN_MATRIX=45` `MIN_EXPCTL=22` `MIN_ALERTS=8` `MIN_POLICIES=12` `MIN_PAUSED=7` `MIN_CANVAS=12` `MIN_CRASH=34` `MIN_MULTIPAR=21` `MIN_GWPAIR=34` `MIN_PAUSERES=25` `MIN_PAUSEEXP=5` `MIN_INTR=3`; "only ever up" is enforced by segment 0 of `ci-gate.sh` (any `MIN_*` below its git-history maximum is a red light) |

### 1.1 Gate segments added this round

| Segment | Script | What it asserts | Threshold |
|---|---|---|---|
| 4.16 | `scripts/e2e-pause-resume-nodes.py` | the pause point lands **only on a device task node** (neither gateways nor logic nodes consult the pause checker); a resume continues from the node that had not finished; the device command is not issued twice | `MIN_PAUSERES=25` |
| 4.17 | `scripts/e2e-pause-during-experiment.py` | pausing **while the device method is mid-flight** ⇒ **all three layers stop at paused**: both experiment-level layers (`experiment_run` / `experiment`) become `paused` within 5 s, and the underlying workflow-level run also stops as `paused` at the node boundary with the following nodes left `pending` (the old reading "the workflow-level run finishes on its own" was a bug, now fixed) | `MIN_PAUSEEXP=5` |
| 4.18 | `scripts/e2e-interrupt-during-device.py` | when the method declares `interruptible=true`, pausing mid-flight makes the command reach a terminal state **within 10 s** (`aborted｜EC-99-0005`) instead of waiting for the device to finish | `MIN_INTR=3` |

## 2. How to re-run (a reviewing AI can execute it step by step)

~~~bash
cd <repo>
# 1) pure logic (no DB needed)
./scripts/test-all.sh --pure-only              # 644/0
# 2) database-connected suite (needs a reachable DB; the password is not in this doc,
#    use your own environment variable)
LABOS_DB_HOST=<host> LABOS_DB_NAME=labdb LABOS_DB_USER=labos \
LABOS_DB_PASS='<password>' ./scripts/test-all.sh          # 1031/0
# 3) full gate (needs DB + simulators + front end + API running) — 22 segments
export LABOS_TRANSFER_PLAN=1 LABOS_EXEC_GRAPH=1 LABOS_DRIVER_MODE=persistent LABOS_AUTH=1
./scripts/ci-gate.sh --all
# 4) this round's key scripts (real DB + real API)
python3 scripts/e2e-pause-during-experiment.py --api http://127.0.0.1:18080  # experiment level
python3 scripts/e2e-pause-during-device.py    --api http://127.0.0.1:18080  # workflow level
python3 scripts/e2e-interrupt-during-device.py --api http://127.0.0.1:18080 # A1: interruptible → really aborted
# 5) EN/CN docs, pair by pair (structure / links / Chinese residue)
python3 scripts/verify-en-docs.py                                       # hard errors 0
~~~

> Note: the DB password environment variable is `LABOS_DB_PASS` (not `LABOS_DB_PASSWORD`).
> **Slow devices change the gate's runtime**: with the robot-arm simulator at
> `--rand-delay 30000:300000`, the full gate goes from ~17 minutes to **~32 minutes**
> (segment 2.7 alone is ~14 minutes). For daily runs prefer a narrower range or a fixed seed
> (e.g. `--rand-delay 30000:45000:42`). Starting the simulators:
> `labos-kernel/scripts/sim-up.sh LAB-DEMO-02 0 30000:300000` (third argument = random delay range).

## 3. The four deliveries of this round

| Theme | One sentence | Details |
|---|---|---|
| A · Designer gateway readability + page exercise | the two gateway kinds (exclusive ✕ / parallel ＋) are now told apart by **name + role label + highlighted end marker**; a page exercise drags multi-parallel + multi-gateway + a variable node and really runs it | `01` §1 |
| B · Simulator random delays + measured reality of "pause during a device command" + the "really stop it" plan | the simulator gained `--rand-delay MIN:MAX[:SEED]`; whether a pause stops a running device was **measured**, not assumed, and a plan follows | `01` §2 |
| C · Criterion correction + gate hardening | "the device is executing" is now judged by `device_state`, not `run_command`; the gate distinguishes "the script died" from "an assertion went red" | `01` §3 |
| D · Timeout per method + the A1 interrupt mechanism + 4 real bugs | the three hard-coded fixed timeouts (30 s / 60 s / 90 s) all become config-driven; an `interruptible` column plus an interruptible wait; 4 real bugs fixed along the way | `01` §4 |

## 4. Where to focus the review (by risk)

1. **The timeout resolution chain** (`MethodTimeoutPolicy`): device method → device-library
   ceiling → workflow node → capability default → fallback 30 s. Look hard at **why the device
   library's explicit small values (3 s / 5 s / 20 s) are not raised to the capability default**,
   and whether using `MAX(timeout_sec)` for the "device-library ceiling" spreads one slow
   method's ceiling onto every method of that device.
2. **Is A1's default really safe?** `device_method.interruptible` defaults to `false` ⇒ without
   an on-site list **every method takes the old path** (blocking wait, behaviour unchanged).
   Check whether there is a bypass (is `driver.execute` called anywhere besides
   `executeCommand`?).
3. **The data semantics after an interrupt**: the command/step row is recorded as `aborted`
   (told apart from a real failure by `EC-99-0005`), the business node row stays `running`, the
   run lands on `paused`; on resume `resumeAsync` deletes the non-completed rows ⇒ **that step
   is re-run**. Is `aborted` vs `manual` the right choice, given the existing UI/statistics?
4. **The three device-lock semantics**: normal release / ghost lock (the run died, the lock was
   never released) / orphan lock (the run was deleted, the lock survived). The latter two are
   fixed this round, but the **root cause is that "deleting a run" does not release the lock** —
   should the deletion entry points (API / background cleanup) handle it instead?
5. **Capability resolution with the bound device first**: `loadProtocolCapability` had no
   `DISTINCT`/`ORDER BY`, so for a method name shared across capabilities the result depended on
   the physical row order. This round makes the bound device's capability win and makes the
   protocol map deterministic. Is `bound` placed correctly (after `explicit`, before
   `protocol`)? It would now **override** a wrong explicit capability annotation on a node.
6. **Is there a fourth hard-coded timeout?** The known ones (driver wait 30 s, device-lock wait
   60 s, test-script wait 90 s) are fixed. Please sweep for other places that assume "the
   device answers instantly".
7. **Gate mechanics**: the new `parse_summary` (tells "summary line missing" apart from "there
   are failing assertions"); 22 segments; `pre-push` still runs the full gate.
8. **Test ledger self-consistency**: do the constants / script list / honest list in
   `docs/测试用例总表.md` match the actual thresholds in `scripts/ci-gate.sh` 100%?
9. **EN/CN 1:1**: `scripts/verify-en-docs.py` hard errors 0; are the 8 new/changed documents
   aligned paragraph by paragraph (especially the `99a`/`99b` shard descriptions and the
   `aborted` status paragraph)?

## 5. Questions for the reviewing AI (please answer these)

1. **Timeout chain**: is the order and the fallback of
   `device_method → device-library ceiling → workflow_node → capability default → 30 s`
   reasonable? Specifically: does using `MAX` for the device-library ceiling spread one slow
   method's ceiling onto every method of that device? Does putting `workflow_node.timeout_sec`
   *after* the two device levels let a tighter workflow-side constraint be ignored?
2. **The capability defaults** added this round (incubation 1800 s / thermocycler 3600 s /
   centrifuge 900 s / washer 900 s / everything else 600 s): are those values sensible? Should
   the timeout be derived from `estimate_sec` instead of hard-coded?
3. **A1's safe default**: is `interruptible` defaulting to `false` enough? Is a second guard
   needed (e.g. "a method may only be interruptible if it also has a certain capability")?
4. **The interrupt status**: why `aborted` rather than a new vocabulary entry / reusing
   `manual`? Does "the node row stays `running`" make the page show "still running"? Should it be
   an explicit `interrupted`?
5. **How the experiment-level pause reaches the workflow level**: the execution side has **two**
   ways to stop — ① **node boundary**: `WorkflowService.withPauseChecker` checks once per task
   node boundary with the criterion "`run.status='paused'` **or** this workflow is covered by an
   active site hold" (an experiment-level pause records a hold and does **not** change the
   workflow-level run status, so it can only be resolved indirectly through
   `RuntimeHold.coveringForWorkflow`); on a hit `pauseRunAtBoundary` lands the underlying run on
   `paused` and the following nodes stay `pending` — this path is independent of
   `interruptible` and on by default; ② **mid-action**: `DriverStepExecutor.pauseRequested`
   calls `driver.abort` on an **interruptible** method while it is in flight (requires
   `device_method.interruptible=true`, see question 3). Is that reverse mapping
   (workflow run → the running experiment step → experiment → island/site) reliable? In
   particular, `RuntimeHold.coveringForWorkflow` resolves ownership only by
   `experiment_run_step.status='running'` + `workflow_id` — with several concurrent running
   steps on the same `workflow_id`, is coverage still decided reliably?
6. **Device locks**: ghost locks and orphan locks are both cleaned by the startup fallback, but
   the root cause is "deleting a run does not release the lock". Should the deletion entry point
   release it? Is a startup-time cleanup enough (the device can stay unusable for hours if
   nothing restarts the API)?
7. **Capability resolution**: with `bound` inserted right after `explicit`, a wrong explicit
   capability annotation on a node is now **overridden** by the device's capability. Is that the
   semantics you want?
8. **A fourth hard-coded timeout**: where else does the code assume "the device answers
   instantly"? (Especially alerts, scheduler polling, heartbeats, batch orchestration.)
9. **Gate runtime**: slow devices took the gate from 17 to 32 minutes. What balance do you
   recommend — "narrow range for tests, full range for demos", or "per-capability timeout tiers"?
10. **The honest list**: does §5 of
    `docs/设备执行中暂停-现状实测与实现方案-20261106.md` **faithfully** reflect the gaps?
    Anything glossed over (especially "not validated on a real device" and "the on-site list has
    not arrived")?
11. What other hidden assumptions would be "green in simulation, broken on a real device"
    (especially: can a real `abort` actually interrupt; would interrupting a robot arm drop the
    plate; do real timeout configs differ from the simulated ones)?

## 6. Contents

- `01-deliverables-and-verification.md` — the four deliveries grouped by commit theme, the
  verification matrix, the known gaps, the key decisions and the pitfalls
- `02-remainder-and-next-steps.md` — open items, pending decisions, follow-up batches, inputs needed
