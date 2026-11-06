# Pausing During a Device Command · Measured Reality and How to Really Stop (2026-11-06)

> Origin (the user's words):
> **"most of the time the pause command goes out, but the method the device is executing keeps
> going; only the robot arm receives the pause command and stops right there — see how that
> should be implemented"**
>
> Script: `scripts/e2e-pause-during-device.py` (workflow level)
>       `scripts/e2e-pause-during-experiment.py` (experiment level, used by gate segment 4.17)
> Re-run: `python3 scripts/e2e-pause-during-device.py --api http://127.0.0.1:18080`
> Companion change: the simulator gained `--rand-delay MIN:MAX[:SEED]` (random method delays)
> Environment: macOS, API `18080` + PostgreSQL `192.168.124.2:5432/labdb` + simulators 19500-19506

---

## 1. Conclusion (one sentence)

**Your observation is right — and in fact more thorough than the impression:
today a pause does nothing to a device command that is already executing — and the robot arm
stops neither.**

But two things have to be separated (the first version mixed them up — see §1.5):

| Dimension | Conclusion |
|---|---|
| **Does the pause itself take effect?** | ✅ **Yes** — `run` turns `paused` within 0.1 s of the pause going out |
| **Is the executing device method interrupted?** | ❌ **No** — the method runs to completion (`completed`); the action is not interrupted |

Measured (simulator method delay random in 10–18 s, pause issued **while the method was
mid-flight**; the "is it really executing?" criterion is `device_state`, see §1.5):

```
══ 长耗时设备执行中发暂停 ══
  ✓ 观测到设备方法执行中（device_state status=running current_method=Home，归属 run #207775）
  ✓ 已在设备执行中发出暂停（UPDATE 影响 1 行）

  结果：
    run 最终状态      : paused           ← the pause did take effect
    设备指令最终状态  : completed        ← but the device finished; it was not interrupted
    节点状态          : ARM=completed
    暂停生效延迟      : 0.1 秒（从发暂停到 run 变 paused）

  ⇒ 设备指令**跑完了**（completed）：暂停没有中断正在执行的设备动作
```

The source explains why: **the pause check only happens at an "action boundary"**
(before `executeNode`), while the device command is in flight the thread is **blocked waiting
for the reply** and never reaches that check.

---

## 1.5 ⚠ Criterion fix: how do you actually tell that "the device is executing"?

> This section was added on 2026-11-06. **The first version of the script used a wrong
> criterion**, so it was not measuring "pause during execution" at all, and the conclusion
> it printed was off.

### Trap 1: a `run_command` row ≠ the device is executing

The first version decided "the device is executing" like this:

```python
n = q1(f"SELECT count(*) FROM run_command WHERE run_id={rid}")
if n and int(n) >= 1:      # ← believed this means "the command went out"
```

**That is wrong.** `run_command` is INSERTed **after the step has finished**
(the command-level persistence in `WorkflowService.java` writes `status` directly as
`completed`/`failed`, with `sent_at`/`finished_at` both `now()`) —
it proves "**this step already ran and was persisted**", not "the command just went out".

⇒ The first version therefore issued the pause **after the device had already finished**:
`run` was already wrapping up, the UPDATE set `paused` and the wrap-up overwrote it with
`completed` ⇒ the first output line "run 最终状态: completed ← the pause never took effect"
was a **misdiagnosis**; the truth is "the pause went out too late, the device was already done".

### Trap 2: `max(id) FROM run` can pick up **someone else's run**

The first version located its own run with `SELECT max(id) FROM run WHERE tenant_id=1`.
The table still holds runs from **other tests** (with commands and terminal states);
as soon as `max(id)` hit one of those stale runs, the whole script asserted against
**another run's evidence** (it did hit a stale run with `workflow_id=1255`, finished at 11:58).

⇒ Always locate the run by `run.workflow_id = <the workflow this test just created>`
(unique by construction).

### The correct criterion: `device_state`

| Signal | Meaning | Written by |
|---|---|---|
| `device_state.status='running'` + non-null `current_method` | **the device method is executing** | `markDeviceBusy` (`onStepStart`) / `markDeviceIdle` (`onStepEnd`) |
| `device_state.current_run_id` | the run holding that device (proves it is not somebody else's execution) | `DeviceLock.tryAcquire` |
| a `run_command` row exists | that step **already ran and was persisted** | command-level persistence (after the step) |

```sql
SELECT status, current_method, current_run_id
FROM device_state WHERE tenant_id=1 AND resource_id=200214;
-- running | Home | 207775   ← this is "the device is executing"
```

After fixing the criterion the re-measurement **kept the same conclusion but changed the
evidence chain**: the pause **does take effect** (`run` → `paused` within 0.1 s) while the
**device method still runs to completion** (`completed`) — that is the actual problem to solve.

---

## 2. The full chain today (read from the code + measured)

### 2.1 How the pause is implemented

`WorkflowService.java:824` injects a "pause checker" into the executor:

```java
// P1-8：每到一个节点边界查一次 DB —— 状态是 paused 就停。
// 只查单行主键，开销可忽略；不查的话暂停按钮等于没用。
.withPauseChecker(() -> isRunPaused(runId));
```

`isRunPaused` is just "read one row: `run.status == 'paused'`".

### 2.2 There is exactly one check, and it is at an action boundary

`GraphRunner.java:328-340`:

```java
} else if (node.kind() == Kind.TASK) {
    // P1-8：在**任务节点的动作边界**检查暂停。
    // 不停在动作中间 —— 机械臂抓着板会掉。
    // 也不停 start/gateway —— 它们不是"一步活"，停在那里没意义。
    // 把 key 放回队列，恢复时从这里继续。
    if (pauseChecker.getAsBoolean()) {
        ready.offer(key);
        done.remove(key);
        paused = true;
        log.accept("[暂停] 收到暂停请求，在节点边界停下，待执行节点：" + key);
        break;
    }
    RecoverableNode rn = toRecoverable(node);
    ...
    NodeStatus st = stateMachine.executeNode(rn, progress, executor);   // ← blocks waiting for the reply
```

**The execution order is**:

```
action boundary ──check pause──▶ not paused ──▶ executeNode ──▶ driver.execute ──▶ blocked waiting (seconds~minutes)
   ▲                                                                                      │
   └──────────────── the device replied, only then back here ─────────────────────────────┘
```

**The pause signal is only consulted at the first arrow.** While the device is busy
(the second arrow) no code reads `run.status`.

### 2.3 Where "the robot arm stops" comes from — it is **resource protection**, not a pause

The two "the robot arm must not stop" comments are about **not releasing resources there**,
not "the pause stops it":

- `PauseManager.java:29`: the robot arm grabbing a plate and moving it half-way —
  **must not stop here, the plate would drop**
- `PauseManager.releaseResources`: it releases resources that are **"reserved but not in
  use"**, with three exemptions:
  - the return promise (`position-return`) — occupying it after putting someone else's plate
    in would deadlock on the way back
  - active device locks — the plate may still be inside
  - **the robot arm holding a plate — it must not let go**

In other words: on pause, resources that are *reserved but idle* get released, but the
**robot arm's reservation is exempt**, so the robot arm **keeps carrying to the end**.
That is the **opposite** of "the robot arm stops on a pause command".

> ⚠ In other words: **in the current implementation the robot arm and ordinary devices
> behave identically for a pause — both "let the current action finish, stop at the next
> boundary".** The impression that "the robot arm stops" is most likely a mix-up of
> "the robot arm's reservation is exempt from release".

### 2.4 The interrupt capability **exists but is not wired up**

`DeviceDriver.java:29`:

```java
/** 中止正在执行的指令（尽力而为）。 */
void abort(String commandId);
```

Three implementations:

| Implementation | Behaviour |
|---|---|
| `TcpPersistentDriver.abort` | **removes the pending future**, so the caller returns a failure immediately (`EC-99-0005 被中止`) — **it really does interrupt the wait** |
| `TcpDriverAdapter.abort` | **no-op** (the protocol has no Abort message type; only the upper-layer timeout can help) |
| `VirtualDriver.abort` | implemented |

**But there is no caller anywhere in the codebase** — the only `.abort(` hit is
`RecoveryDecision.abort` (same name, different thing).

Measured proof: `run_command.error_code='EC-99-0005'` (aborted) occurs **zero times in the
whole database** — that path has never been taken.

### 2.5 A real bug found along the way: long tasks are killed by a fixed 30 s timeout

The driver's timeout is a **fixed 30 seconds at the executor level**:

```java
public DriverStepExecutor(DriverRegistry registry, StepSink sink, DbConfig db, long tenantId, long runId) {
    this(registry, 30_000L, "trace", sink, db, tenantId, runId);
}
```

while `device_method.timeout_sec` / `workflow_node.timeout_sec` **store the value but the
driver never reads it** (`grep timeout_sec` under `driver/` and `workflow/` returns nothing).

Consequence: **any real-device method taking longer than 30 s (a 5-minute centrifuge, a
30-minute incubation) is judged as a timeout by the control plane** — unrelated to pausing.
Measured: with the simulator delay set to 20–40 s, the runs that exceeded 30 s came back
`failed`.

---

## 2.6 Experiment-level measurement: all three layers go to "paused", but the device action still runs to the end

The above measured the workflow-level run. At the **experiment level**
(`/api/experiments/{id}/run`) there are three layers of status to look at: `run` (workflow level,
the device command hangs off it), `experiment_run` (experiment level) and `experiment` (the
experiment itself, derived from `experiment_run`).

Measured (device delay random 10–18 s, pause issued **while the method was mid-execution**
through the real experiment-level pause endpoint `POST /api/experiments/runs/{id}/pause`;
the criterion for "really executing" is `device_state`, see §1.5 — the pause is only issued
after the device is confirmed to be executing):

```
  ✓ 观测到设备方法执行中（device_state status=running current_method=Home，归属 run #207774）
  暂停前 experiment_run 状态: running
  暂停接口返回：HTTP 200 {"runId":1643,"experimentId":1458,"status":"paused","scope":"experiment","kind":"manual"}
  暂停后 experiment_run 状态: paused
  ✓ 实验在设备执行中暂停后，实验级两层都变 paused（experiment_run / experiment）
  ✓ 暂停在 5 秒内生效（0.2 秒）
  ✓ workflow 级 run 独立跑完（不受实验级暂停影响）：completed

  结果：
    run（workflow 级）      : completed
    experiment_run（实验级） : paused
    experiment（实验本身）   : paused
    设备指令最终状态        : completed      ← the device action still ran to the end
    节点状态                : ARM=completed
    暂停生效延迟            : 0.2 秒
```

**⇒ The answer: it does go into the paused state** (both experiment-level layers show "paused",
effective within 0.2 s), **but the method the device is executing is not interrupted** — it
finishes first.

> This is now gate segment 4.17 (`scripts/e2e-pause-during-experiment.py`). It asserts only the
> **semantic requirements** (both experiment-level layers paused + effective within 5 s + the
> workflow-level run finishes on its own) and deliberately does **not** assert "the device
> command ends as `completed`" — that one changes once "really stopping" is implemented, and
> baking it into the gate would produce a false red.

> ⚠ The workflow-level `run` is a **separate execution** and is unaffected by an
> experiment-level pause: it ends up `completed` when it finishes (by design — "experiment
> paused" ≠ "underlying workflow run paused"). The first version asserted all three layers
> together and went falsely red as soon as the workflow run finished first. The gate asserts
> only the two experiment-level layers.

### ⚠ A pitfall when testing this: async dispatch makes the test miss it

The first version triggered the experiment with `?async=1`, and the result was that **the
`experiment_run` status was already `completed` before the pause** — the scheduler dispatches
the experiment and **immediately** runs it to the end (11:23:21 → 11:23:33), so by the time I
waited for the device command to appear, the experiment-level run had long finished and **the
pause landed on an already-completed run** (the endpoint still answers 200 + `status:"paused"`,
which is extremely misleading).

**You must use synchronous execution** (without `async=1`): synchronous execution blocks the
HTTP response, which conveniently leaves the "device is executing" window open to issue the
pause (run the execution on a background thread, issue the pause from the main thread).

### ⚠ Two real bugs exposed along the way (both fixed)

**Bug 1 · the `paused` state of `experiment_run` gets overwritten by the finalisation**
`ExperimentExecutor.settle()` unconditionally writes `status='completed'` on finalisation and
**lacks the `if (isRunPaused(runId)) return;` guard that the workflow level has**
(`WorkflowService.settleRun` has it) ⇒ the moment the user clicks pause, the executing thread
overwrites it with `completed` when the action finishes.
**Fixed**: the experiment level now has the same guard, plus a new `isRunPaused(long)` (same
criterion as the workflow level).

**Bug 2 · the `experiment` table is not synchronised**
`RunControl.markCoveredPaused()` marks `experiment_run` as paused but **does not sync the
`experiment` table** ⇒ the experiment list shows "running" while the detail view shows
"paused" — **the two contradict each other** (on site, people would think it is still running
and never press continue).
**Fixed**: after marking, the `experiment` row is synchronised through the vocabulary mapping
(`StatusVocabulary.runToExperimentCaseSql`, the same criterion as
`ExperimentExecutor.syncExperimentStatus` — no second hand-written mapping).

> Both bugs surfaced **at the same time** in the synchronous-execution + pause-during-a-device-
> command scenario. With async execution the experiment finished early, the pause landed on an
> already-completed run, and both bugs stayed hidden.

---

## 3. How to make a device command really stop

### 3.1 First separate two different problems

| Problem | Today | What to change |
|---|---|---|
| **A. On pause, should the running action stop?** | No (by design: "the robot arm would drop the plate") | Decide **per device type**: **interruptible** devices stop, **non-interruptible** ones (the robot arm mid-transport) keep going |
| **B. Long tasks being killed by the 30 s timeout** | Yes (every real long method trips it) | Take the timeout **per method / per node** (`device_method.timeout_sec` is already stored) |

**A is a semantics decision; B is a plain bug.** B must be fixed; A needs your call.

### 3.2 Approach A1 (recommended): **grade actions by interruptibility**

On a real device, methods fall into two classes:

- **Safely interruptible** (asynchronous devices): scanning, reading, incubation (can pause and
  resume), centrifuges (most support a stop command)
- **Not safely interruptible** (synchronous mechanical actions): the robot arm grabbing /
  carrying / placing a plate — interrupting it drops the plate on the floor

So the right answer is neither "stop everything" nor "stop nothing" but **each method declares
whether it can be interrupted**:

```
device_method gains a column interruptible (boolean, default false)
  · robot-arm transport (Transfer/Grip/Release/Load/Unload) → false
  · long processes (RunProgram/Incubate/Centrifuge/Scan)     → true
```

then:

```
① PauseManager.pause records, along with the pause, the **commandId + deviceId of the
   command currently in flight** (e.g. run.pause_command_id / run.pause_device_id)

② Execution side: replace the blocking `driver.execute` with an **interruptible wait** —
   every 200 ms check (a) whether the run was paused and this method is interruptible,
                                   (b) whether a stop was requested
   on a hit, call driver.abort(commandId) and mark the method as
   "interrupted, this step can be retried"

③ Interrupted and paused must differ in the data:
   · paused (nobody pressed continue) → stay put, wait for the resume
   · interrupted (the device stopped by itself) → node state aborted/interrupted,
     and on resume the run **re-runs this step**
   —— and that is exactly what resumeAsync already supports today
   (rows that are not completed get deleted and re-run)
```

**Constraint ② (established by measurement — it has to shape the implementation)**:
`resumeAsync` deletes **every row whose `status <> 'completed'`** — so **`paused` and
`aborted` get exactly the same treatment on resume** (both deleted and re-run).
⇒ **the "interrupted" state cannot be distinguished via `run.status`; it has to live on the
node status.** If an interrupt still marks the run `paused`, the user pressing "continue"
thinks they are waiting for a human while the step is actually re-run automatically; if it
marks the run `aborted`, it gets conflated with the "stop" semantics. Recommendation: record
the node status as `interrupted` (a new vocabulary entry), keep the run `paused`, and make
the UI distinguish "waiting for a human to continue" from "interrupted — this step will be
retried automatically".

**The key point**: today's `executeNode` is **one blocking call**; it has to become an
interruptible wait. The good news: `TcpPersistentDriver.abort` already implements "remove the
future so the waiter returns immediately", so **the driver layer needs no change** — only
`DriverStepExecutor` has to take over the future, poll for a pause, and call `abort`.

### 3.3 Approach A2 (conservative): **only stop at the next node, but say so clearly**

If you consider "stopping mid-action" too risky on real hardware (the plate drops, a
centrifuge that stops needs re-balancing), then **keep the current behaviour** but make the
UI honest:

- the pause button's label becomes "Pause (the current action will finish)"
- the run detail page shows "pause requested — waiting for the current action to end"
- while the robot arm is carrying a plate, add "the plate is on the arm, this move will
  complete"

This is the **zero-code-risk** option, but "I pressed pause and nothing happened" would still
be true from the user's point of view.

### 3.4 Approach B: take the timeout from the method (**do this one**)

```
① DriverStepExecutor stops using a fixed 30 s; instead:
   timeout = device_method.timeout_sec (for that device + method)
              ?? workflow_node.timeout_sec
              ?? fallback 30 s
② device_method.timeout_sec already has values, but many rows are probably NULL/0 — they
   need sensible defaults per capability (centrifuge 5 minutes, incubation 30 minutes,
   scan 30 seconds, …)
③ Long tasks (--long) already have the "reply running first, then completed" two-frame
   mechanism; combined with a per-method timeout it can really support
   "incubate for 30 minutes"
```

### 3.5 Suggested implementation order

| Step | Content | Risk | Benefit |
|---|---|---|---|
| 1 | **Timeout taken from the method** (B) | low (just stops false failures) | immediate; real long methods stop failing spuriously |
| 2 | **Record the current commandId on pause** (no change to execution semantics yet) | low | paving the way for a real interrupt |
| 3 | **Interruptible wait on the execution side + `interruptible` decides whether to abort** (A1) | **medium-high** (real-device safety) | a pause can actually stop an interruptible device |
| 4 | State it in the UI ("the current action will finish" / "interrupted, retryable") | low | removing the misunderstanding |

---

## 4. The companion change: random simulator delays

To make "pause during execution" testable at all (the fixed delays are only a few hundred
milliseconds, so the pause signal never gets a chance to arrive while a command is in flight),
the simulator gained `--rand-delay`:

```bash
# syntax: --rand-delay MIN:MAX[:SEED]   milliseconds
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19502 --device 214 --code ID1731 --cap TRANSFER --profile generic \
     --rand-delay 30000:300000          # 30–300 s at random (real-device timing)
```

- **omit SEED** → a different value on every call (like a real device)
- **give SEED** → the sequence is reproducible (tests need this)
- precedence: `--rand-delay` > `--delay` > the profile/model's fixed delay

`scripts/sim-up.sh` supports it too (third argument):

```bash
./scripts/sim-up.sh LAB-DEMO-02 0 30000:300000   # every device on the island: 30–300 s
```

**Measured** (10–20 s range, seed 42): four commands took **17.3 / 16.8 / 13.1 / 12.8 s** —
inside the range, and different every time.

---

## 5. Boundaries and what is still uncovered (honest list)

| Item | Status | Note |
|---|---|---|
| "Really stop a device command in flight" | ❌ **not implemented** | this round only **measured the current behaviour** and produced a plan; A1 involves real-device safety, so no production code was touched |
| How to tell "the device is executing" | ✅ corrected | `device_state.status='running'` + `current_method`; **not** `run_command` (that is persisted after the step finishes) — see §1.5 |
| Difference between the robot arm and an ordinary device on pause | ✅ clarified | **there is no difference today** — both "let the current action finish". "The robot arm stops" is a misreading |
| The `abort` chain | ✅ clarified | the driver layer implements it for real (`TcpPersistentDriver` really does remove the future) but **nobody calls it**; `EC-99-0005` occurs 0 times |
| The fixed 30 s timeout | ⚠ **located, not fixed** | `device_method.timeout_sec` is stored but never read; long methods are always killed |
| Real long tasks (a 5-minute centrifuge, …) | ❌ not covered | this round is all simulators |
| "Retry this step after an interrupt" | ⚠ **the mechanism exists** | `resumeAsync` deletes the not-completed rows and re-runs them; but there is no "interrupted" state source yet |
| Pause + site hold (`runtime_hold`) combined | ❌ not covered | `releaseResources`' exemptions and the interrupt design have to be designed together |
| Pausing while several devices run in parallel | ❌ not covered | the pause is run-scoped; with parallel devices it is unclear which one should stop |
| Pausing during a multi-device parallel execution | ❌ not covered | this round only measured a **single** device executing; "which one stops" is a design question |

---

## 6. How to re-run

```bash
export LABOS_DB_HOST=192.168.124.2 LABOS_DB_NAME=labdb \
       LABOS_DB_USER=labos LABOS_DB_PASS='***' LABOS_DB_PORT=5432 PGPASSWORD='***'

# ① make one device on the demo island take a long time (random; a SEED makes it reproducible)
cd labos-kernel
pkill -f "DeviceSimulator.*--port 19502"
nohup java -cp out com.labos.kernel.sim.DeviceSimulator \
  --port 19502 --device 214 --code ID1731 --cap TRANSFER --profile generic \
  --rand-delay 10000:18000:7 > /tmp/sim-19502.log 2>&1 &
#   ⚠ the simulator's stdout has to go to its own file (sim-up.sh writes
#     /tmp/sim-<code>.log; a hand-started one needs an explicit path) — that is
#     where you see lines like "完成 Home（13579ms）", i.e. how long the device
#     really executed, which is what you need to cross-check the timing.

# ② run the "pause during a device command" measurement (workflow level)
cd ..
python3 scripts/e2e-pause-during-device.py --api http://127.0.0.1:18080

# ③ run the experiment-level variant (the one gate segment 4.17 uses)
python3 scripts/e2e-pause-during-experiment.py --api http://127.0.0.1:18080
```

Both scripts create their own workflow and run, clean up after themselves, and print a
"通过 N / 失败 M" summary line.

> ⚠ **Only run one long-delay simulator**: concurrent gate instances step on each other
> (they fight over the same simulators/DB), which shows up as random false failures.
> Always debug with a **single** instance.
