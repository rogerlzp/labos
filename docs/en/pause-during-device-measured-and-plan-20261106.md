# Pausing During a Device Command · Measured Reality and How to Really Stop (2026-11-06)

> Origin (the user's words):
> **"most of the time the pause command goes out, but the method the device is executing keeps
> going; only the robot arm receives the pause command and stops right there — see how that
> should be implemented"**
>
> Script: `scripts/e2e-pause-during-device.py`
> Re-run: `python3 scripts/e2e-pause-during-device.py --api http://127.0.0.1:18080`
> Companion change: the simulator gained `--rand-delay MIN:MAX[:SEED]` (random method delays)
> Environment: macOS, API `18080` + PostgreSQL `192.168.124.2:5432/labdb` + simulators 19500-19506

---

## 1. Conclusion (one sentence)

**Your observation is right — and in fact more thorough than the impression:
today a pause does nothing to a device command that is already executing — and the robot arm
stops neither.**

Measured (simulator method delay set to 8–15 s, pause issued while the command was mid-flight):

```
══ 长耗时设备执行中发暂停 ══
  ✓ 设备指令已发出（设备正在执行）
  ✓ 已在设备执行中发出暂停

  结果：
    run 最终状态      : completed        ← the pause never took effect
    设备指令最终状态  : completed        ← the device finished; it was not interrupted
    节点状态          : ARM=completed
    暂停生效延迟      : 未观测到 run 变 paused（它直接跑到了终态）

  ⇒ 设备指令**跑完了**（completed）：暂停没有中断正在执行的设备动作
```

The source explains why: **the pause check only happens at an "action boundary"**
(before `executeNode`), while the device command is in flight the thread is **blocked waiting
for the reply** and never reaches that check.

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
| Difference between the robot arm and an ordinary device on pause | ✅ clarified | **there is no difference today** — both "let the current action finish". "The robot arm stops" is a misreading |
| The `abort` chain | ✅ clarified | the driver layer implements it for real (`TcpPersistentDriver` really does remove the future) but **nobody calls it**; `EC-99-0005` occurs 0 times |
| The fixed 30 s timeout | ⚠ **located, not fixed** | `device_method.timeout_sec` is stored but never read; long methods are always killed |
| Real long tasks (a 5-minute centrifuge, …) | ❌ not covered | this round is all simulators |
| "Retry this step after an interrupt" | ⚠ **the mechanism exists** | `resumeAsync` deletes the not-completed rows and re-runs them; but there is no "interrupted" state source yet |
| Pause + site hold (`runtime_hold`) combined | ❌ not covered | `releaseResources`' exemptions and the interrupt design have to be designed together |
| Pausing while several devices run in parallel | ❌ not covered | the pause is run-scoped; with parallel devices it is unclear which one should stop |

---

## 6. How to re-run

```bash
export LABOS_DB_HOST=192.168.124.2 LABOS_DB_NAME=labdb \
       LABOS_DB_USER=labos LABOS_DB_PASS='***' LABOS_DB_PORT=5432 PGPASSWORD='***'

# ① make one device on the demo island take a long time (30–300 s at random)
cd labos-kernel
pkill -f "DeviceSimulator.*--port 19502"
nohup java -cp out com.labos.kernel.sim.DeviceSimulator \
  --port 19502 --device 214 --code ID1731 --cap TRANSFER --profile generic \
  --rand-delay 30000:300000 > /tmp/sim-19502.log 2>&1 &

# ② run the "pause during a device command" measurement
cd ..
python3 scripts/e2e-pause-during-device.py --api http://127.0.0.1:18080
```

The script creates its own workflow and run, and cleans up after itself.
