# 01 · Deliverables and verification (2026-11-06)

> Grouped by commit theme. Each block gives: **what was done → the evidence → the gap**.

## A. Designer gateway readability + page exercise

| Commit | Content |
|---|---|
| `6522066` | Designer gateway readability: **name + role label + highlighted end marker** (the exclusive ✕ and the parallel ＋ gateway no longer look alike) |
| `555bde1` | real screenshots of both gateway kinds side by side |
| `6a2a346` | gateway pairing + N-value coverage (exclusive/parallel with N=2 and N=3) → gate 18 segments |
| `62a1c57` | review-package fixes + page exercise with multi-parallel and multi-gateways (gate 16→18) |

**Verification**: gate 4.14 "multi-parallel multi-gateway" **21/0** (2 parallel gateways + 2
exclusive gateways + a variable node, dragged on the page → really run); gate 4.15 "gateway
pairing" **34/0**.

**Gap**: the name and the role label are **presentation layer only** — nothing pins
"the node's name in the diagram ↔ its runtime role"; `subProcess` nodes are still **silently
skipped** by the parser (carried over from the previous round's ruling: registered, not fixed).

---

## B. Simulator random delays + measured reality of "pause during a device command" + the "really stop it" plan

### B1. Simulator random delays (`1faa087`)

```
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19502 --device 214 --code ID1731 --cap TRANSFER --profile generic \
     --rand-delay 30000:300000          # 30–300 s at random (real-machine timing)
```

- Syntax `--rand-delay MIN:MAX[:SEED]`; **without SEED** every call differs (like a real
  machine), **with SEED** the sequence is reproducible (which tests want).
- Priority: `--rand-delay` > `--delay` > the profile/model's fixed delay.
- `scripts/sim-up.sh` third argument: `./labos-kernel/scripts/sim-up.sh LAB-DEMO-02 0 30000:300000`.
- Landed in `DeviceCore` (the random value overrides `methodDelayMs`), `DeviceSimulator`
  (argument parsing) and `sim-up.sh`.

### B2. What was measured (the user's words: "the method the device is executing keeps going,
only the robot arm stops right there when it gets the pause command")

| Question | Answer | Evidence |
|---|---|---|
| While a device method is executing, does a pause put the **experiment** into the paused state? | **Yes** (both `experiment_run` and `experiment` turn `paused` within 0.2 s) | `e2e-pause-during-experiment.py` |
| Is the **executing device method** interrupted? | **No** — it runs to completion (`completed`) | same script, `设备指令最终状态: completed` |
| Does the **robot arm** stop? | **No** — it behaves exactly like an ordinary device | source: `PauseManager.releaseResources` exempts the arm's slot while it is holding a plate; the pause check sits **before** `executeNode` (`GraphRunner.java:333`, and only in the `Kind.TASK` branch) |
| Does an `abort` path exist? | It exists but **nobody calls it**: `TcpPersistentDriver.abort` really does remove the waiting future (error code `EC-99-0005`); `TcpDriverAdapter.abort` is an empty implementation | `EC-99-0005` occurs 0 times in the whole database |

### B3. The "really stop it" plan (three steps; the landed state is in §D)

| Step | Content | Risk | State |
|---|---|---|---|
| 1 | **take the timeout from the method** | low | ✅ done this round (§D1) |
| 2 | record the current commandId when pausing | low | ⚠ changed to **the execution side asking itself** (it already knows its own runId and commandId — one less state field to keep consistent) |
| 3 | interruptible wait on the execution side + abort depending on `interruptible` | medium-high (real-device safety) | ✅ mechanism done, **everything defaults to false** (§D2) |
| 4 | state it in the UI ("the current action will finish" / "interrupted, retryable") | low | ❌ not done (depends on the on-site list) |

**Gaps**: no real-device validation; the on-site list of interruptible methods has not arrived;
the UI does not yet tell "waiting for a human" apart from "interrupted, will retry".

---

## C. Criterion correction + gate hardening (`a0ec575`)

### C1. How do you actually tell that "the device is executing"? Two traps

| Trap | Fact | Consequence |
|---|---|---|
| A `run_command` row ≠ the device is executing | `run_command` is INSERTed **after the step has finished** (`status` is written directly as `completed`/`failed`, `sent_at`/`finished_at` are both `now()`) | the first version of the script read "this step ran" as "the command just went out" ⇒ the pause went out after the device was long done ⇒ **it was not measuring "pause during execution" at all** |
| `max(id) FROM run` can pick up **someone else's** run | the table still holds runs from other tests (with commands and terminal states) | it really did pick up the stale run with `workflow_id=1255` (finished at 11:58) ⇒ **the whole segment was asserting against another run's evidence** |

**The correct criterion**: `device_state.status='running'` + non-null `current_method`
(`markDeviceBusy` on `onStepStart`, `markDeviceIdle` on `onStepEnd`) + `current_run_id` to
confirm ownership; your own run is always located via `run.workflow_id = <the workflow this
test just created>`.

**The conclusion was corrected too**: the first version's "the pause never took effect" was a
**misdiagnosis** (the pause went out too late); the truth is "the pause takes effect, the device
action still runs to the end".

### C2. The gate now tells "the script died" apart from "an assertion went red"

New `parse_summary` in `scripts/ci-gate.sh`: a missing summary line (the script crashed / never
reached its last line) and "there are failing assertions" are **two different causes**; the
first used to be folded into "1 failure" while not a single `✗` line could be printed — which
looks like "the gate went red and nobody can tell why". Now a missing summary is its own class
and the tail of the output is printed; `e2e-pause-resume-nodes.py` prints the summary on **every**
exit path.

**Verification**: gate 4.17 went from red to green (4/0).

---

## D. Timeout per method + the A1 interrupt mechanism + 4 real bugs

### D1. Timeout taken from the method (a plain bug fix)

**Before**: the driver used an **executor-level fixed 30 s**, while `device_method.timeout_sec`
and `workflow_node.timeout_sec` were **stored and never read** (`grep timeout_sec` in
driver/ hits nothing) ⇒ **every real-device method taking longer than 30 s was killed by the
controller** (a 5-minute centrifuge, a 40-minute PCR, a 30-minute incubation) — unrelated to
pausing, a plain bug.

**The chain** (`labos-kernel/src/main/java/com/labos/kernel/driver/MethodTimeoutPolicy.java`,
pure logic, unit-testable):

```
device_method.timeout_sec (device + exact method)
  → the device's method-library ceiling (MAX(timeout_sec) over its device_method rows)
    → workflow_node.timeout_sec (workflow-node level)
      → capability default (looked up by the device's real capability)
        → fallback 30 s
```

**Filling in the per-capability defaults** (shard `pg_rebuild/schema/99a_method_timeout.sql`,
idempotent, UPDATE of 260 rows): incubation 1800 s / thermocycler-PCR 3600 s / centrifuge 900 s
/ washer 900 s / everything else 600 s (generic floor). The 3 s / 5 s / 20 s / 302 s values
already in the device library are **left untouched** — they are real contracts (estimated
duration × 2), and raising them would turn "the barcode reader died" from a 3-second discovery
into a 10-minute one.

**Measured**: `[超时] ARM Home → 600 秒（来源 device_library）` ⇒ the device method ran for
**48 seconds** and answered normally — **no longer killed at 30 s** (before the change that
call was guaranteed to fail).

**Four pitfalls hit while implementing it** (each of them **silently** falls back to 30 s, so
the value must be logged):

1. `ctx.resourceId()` is actually the **device_instance.id** (proof: `DriverRegistry.driverFor(long deviceInstanceId)` and `candidates()` both use instance ids) — querying with the resource_node.id misses everything.
2. The capability default must use the device's **real** capability (`resource_capability`) — the capability on the node is **guessed from its name** (`resolveCapability` level 6: a node named "机械臂搬运" gets guessed as WASH).
3. The `resource_capability` table has **no `tenant_id` column** — including it raises a SQLException which gets swallowed ⇒ silent fallback.
4. The resolved timeout must be logged (`[超时] …`), otherwise the three traps above can only be guessed at.

### D2. The A1 interrupt mechanism (everything defaults to false)

- **New column**: `device_method.interruptible` (shard
  `pg_rebuild/schema/99b_interruptible.sql`, default `false`), already exposed by the API
  (`QueryService.deviceMethodsJson`).
- **Execution side** (`DriverStepExecutor.executeCommand`): **not declared interruptible ⇒ the
  old path** (the calling thread blocks for the reply, **behaviour unchanged**); declared
  interruptible ⇒ the call runs on a worker thread and the main thread polls every 200 ms for
  "should I stop" (workflow-level `run.status='paused'` / experiment-level `runtime_hold`
  coverage) and on a hit calls `driver.abort(commandId)`.
- **Data after the interrupt**: the command/step row is recorded as **`aborted`** (told apart
  from a real failure by `EC-99-0005`), the run lands on `paused`; on resume `resumeAsync`
  deletes the non-completed rows ⇒ **that step is re-run**.
- **Measured**: the command reaches a terminal state in **0.3 seconds** (the device itself
  needs 30–300 s) — `aborted|EC-99-0005`.
- **Why "everything defaults to false" is safe**: without the on-site list every method takes
  the old path, so **this switch can never stop a robot arm that is carrying a plate**; once the
  list arrives, flipping the flag is enough — no code change.

### D3. The four real bugs fixed along the way

| # | Bug | Symptom | Fix |
|---|---|---|---|
| 1 | **Capability resolution hijacked by same-named methods** | `loadProtocolCapability` was `SELECT dm.method, c.code FROM device_method ...` (**no DISTINCT, no ORDER BY**), and `Home` exists on the centrifuge (221), the transport robot (145) and the plate washer (183) ⇒ the result **depended on the physical row order** ⇒ a single bulk UPDATE of `device_method` flipped it ⇒ **a node bound to the robot arm got bound to a centrifuge and the bound resource was ignored** | ① a new **bound priority** in `resolveCapability` (the bound device's capability wins, ahead of the protocol lookup); ② the protocol map is now `DISTINCT + ORDER BY` (reproducible) |
| 2 | **Leaked device lock (ghost occupancy)** | when the process crashes / the API restarts, the device method in flight never reaches `onStepDone` ⇒ `markDeviceIdle` never runs ⇒ `device_state` stays at `current_run_id=<a dead run>`; and `DeviceLock.tryAcquire` requires `current_run_id IS NULL OR = me` ⇒ **that device can never be acquired by any run again** (on site: one device mysteriously "always busy") | the startup fallback `settle` now releases the device locks held by interrupted runs |
| 3 | **Orphan lock** (the run was deleted) | the run is gone, the lock is not ⇒ nothing can find it via settle ⇒ same symptom | the startup fallback additionally releases locks **pointing at deleted runs**; and that cleanup was once **skipped** by `if (found.isEmpty()) return orphans;` (the self-test now covers that exit path) |
| 4 | **Device-lock wait hard-coded to 60 s** | with a slow device (30–300 s per call), the next workflow waits 60 s and then gives up ⇒ "device busy for more than 60 seconds" ⇒ the node goes to manual | now taken from **the device's own registered method-timeout ceiling** (floor 60 s, cap 15 minutes) |

### D4. Three hard-coded fixed timeouts (the same root disease)

| Where | Before | After |
|---|---|---|
| waiting for the device's reply | fixed 30 s | per method (§D1) |
| acquiring the device lock | fixed 60 s | per the device's method-timeout ceiling (§D3-4) |
| test scripts waiting for an execution | fixed 90 s | 420 s (with a slow device, "the device is not done" ≠ "the workflow is not done") |

**Common root cause**: they all assume "the device answers instantly", while the product's
whole premise is "a device may take minutes".

### D5. The test-side corrections the slow devices exposed

- **Wait budgets**: flow matrix `run(wait=90→420)`, page matrix `run_workflow(wait=90→420)`,
  the pause/resume scripts' three `30/40/90 → 420`, simulator-chain `120 → 420`.
- **Cleanup timing**: the test scripts **deleted a run while the device was still executing** ⇒
  the in-flight step's persistence found its parent gone ⇒
  `run_node_parent_node_id_fkey` violation ⇒ the gate's "step persistence failed" assertion
  went red. Fix: **wait for the devices to go idle before cleaning up (420 s) + release the
  device locks of the runs being deleted**.

---

## Verification matrix (final state of this round)

| Item | Result |
|---|---|
| Pure logic (`--pure-only`) | **644/0** (new `MethodTimeoutPolicySelfTest`, 20 assertions) |
| Database-connected suite | **1031/0** (`StartupSettleSelfTest` 26 → 32 assertions) |
| Gate `--all` | **22 segments, all green, zero skipped** (4.16 25/0, 4.17 4/0, **4.18 5/0** new) |
| EN/CN docs `verify-en-docs.py` | registry 100 entries / hard errors **0** / 443 relative links resolve |
| Schema product consistency | re-running `merge.sh` changes nothing (`90_all_in_one.sql` contains `99a`/`99b`) |
| Database residue | E2E workflows/experiments 0, unreleased device locks 0, unreleased holds 0, `interruptible=true` rows 0 |

## Key decisions and why

| Decision | Reason |
|---|---|
| The capability default does **not** raise the device library's explicit small values | those are real contracts; raising them turns "the device died" from a 3-second discovery into a 10-minute one |
| `interruptible` defaults to `false` | without the on-site list no method can be stopped — a dropped plate is irreversible |
| An interrupt is recorded as `aborted` rather than a new vocabulary entry | `node_status` already has `aborted`; not touching the vocabulary means not touching the front end |
| The device-lock wait is taken from the device's method-timeout ceiling | the device can legitimately be busy exactly as long as its slowest method |
| Orphan locks are cleaned by the **startup fallback** rather than at deletion time | deleting a run has many entry points (scripts, background cleanup, manual SQL); one fallback is the hardest to miss |
| The gate uses `parse_summary` to tell "died" from "red" | "could not parse" and "parsed and non-zero" need completely different handling |

## Known gaps (NOT done this round)

1. **Real-device validation**: everything ran on simulators; whether a real `abort` really
   interrupts, and whether interrupting a robot arm drops the plate, is unverified.
2. **The on-site list of interruptible methods has not arrived**: the mechanism is complete but
   **no method has been switched on** (`interruptible=true` → 0 rows).
3. **The UI does not distinguish** "waiting for a human" from "interrupted, will retry".
4. **The root cause of the device-lock leak is not treated**: this round cleans up at startup,
   but "deleting a run does not release the lock" still stands (see `02` §2).
5. **Pausing during a multi-device parallel execution** is not covered (the pause is run-scoped;
   "which one stops" is a design question).
6. **`subProcess` being silently skipped** follows the previous round's ruling (registered, not fixed).
