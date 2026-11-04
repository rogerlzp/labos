# Pause, Resume and Fault Handling — Design

> **English edition.** The Chinese original is [`docs/暂停恢复与故障处理-设计.md`](../暂停恢复与故障处理-设计.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Three questions: ① A device fails — do the other parallel experiments have to stop? ②
> Human intervention is needed — how do you pause, how do you notify, how do you resume?
> ③ While the robot arm is holding a plate — can it stop?

---

## 1. The core principle: an action cannot be interrupted

### Why

**A device action is a physical process; it cannot stop halfway**:

```
The robot arm grabs the plate at A and moves it into mid-air → stop it here?
  The plate falls off. The structure of the arm does not allow hovering under load.
```

**So: a pause can only happen at an "action boundary", never in the middle of an
action.**

```
Node 1 [action 1] [action 2] [action 3] → node 2 [action 1] ...
            ↑          ↑          ↑
        pausable   pausable   pausable            ← only these points are safe
```

**This is called a "safe pause point" (safe point)**.

### The implementation: every action is atomic

```
Action starts → reserve the resources → dispatch the command → wait for completion → record the result → release the resources
                                         ↑
                                    Only here can it check "should we pause"
```

**While an action is running, a pause request is only flagged, it does not take
effect.**

---

## 2. Question 1: a device fails — do the other experiments have to stop

### The answer: **not everything stops, it depends on the dependencies**

```
Device B fails
  ↓
Workflow 1 (is using B)   → pause, wait for B to be repaired
Workflow 2 (does not use B) → keep running, unaffected
Workflow 3 (is going to use B) → pause, wait for B to be repaired or for a reroute
```

**The criterion**: whether the workflow will use B further on.

### But a robot-arm failure is the exception

**The robot arm is the "transport channel"; if it breaks, the logistics of the whole
island are cut**:

```
The robot arm fails
  ↓
Every workflow that needs a plate moved → pause
Nodes that are purely computation / pure waiting → they may continue (for example something already incubating, let it finish incubating)
```

**Mind this**: **what is already running inside a device cannot be stopped** (stopping
an incubation halfway ruins the sample).

**So**:

| What failed | The scope of the impact |
|---|---|
| **An ordinary device** (incubator / centrifuge) | Only the workflows that use it |
| **The robot arm** | The workflows that need transport, but **an action that is already running has to finish** |

### The action on a fault: pause + release + re-plan

```
① A device fault is detected (the heartbeat fails / the command returns a hardware error)
② The device is marked status='fault'
③ Every running execution that depends on it is found
④ Those executions: finish the current action → pause (status='paused_fault')
⑤ Release the other resources they hold (positions, other device locks) ← the key point, so that other workflows can run
⑥ Wait for the device to recover → re-schedule
```

**Step ⑤ is the most important**: a paused execution that still holds resources will
drag the whole island to a standstill.

---

## 3. Question 2: human intervention is needed

### What triggers it

| Trigger | Example |
|---|---|
| The command returns `method_status=3` and the error code is marked "needs a human" | The gripper is jammed, the sensor is abnormal |
| The retries are exhausted | It still fails after 3 timeout retries |
| A plate position mismatch | The system thinks there is a plate, the device says there is none |
| A human pauses it deliberately | The operator clicks "Pause" |

### The state machine

```
        ┌──────────────────────────────────┐
        │                                  │
        ▼                                  │
    [running] ──trigger──> [waiting_human] ──a human confirms──┘
        │                    │
        │                    └──a human cancels──> [aborted]
        │
        └──completed──> [completed]
```

### When the pause happens

**It must be after the current action has finished**:

```java
// 检查每一步之前
if (shouldPause()) {
    transitionTo("waiting_human");
    return;   // 不再往下走
}
executeStep();   // 这一步一旦开始，必须跑完
```

### How the person is notified

| Channel | Content |
|---|---|
| **`run.status = 'waiting_human'`** | The frontend list turns red |
| **`alert_state`** | An alert is pushed (with the error code, the device, which step) |
| **`run_node.last_error`** | The concrete reason |

### How a human resumes it

```
POST /api/runs/{runId}/resume
  ?action=continue     继续（人工已处理）
  &action=retry        重试当前动作
  &action=skip         跳过这一步（危险，要二次确认）
  &action=abort        终止
```

**The key point: the resume has to be triggered by a person**; the system must not guess
"whether it has been handled".

### The state correction after the resume

**After a human has handled it, the state on site may no longer match the record in the
system**:

```
The system records: the plate is at B's L1
In reality: the human took the plate out
```

**So the `resume` has to carry a "state correction"**:

```json
{
  "action": "continue",
  "corrections": {
    "plate_position": {"plate": "PL-001", "now_at": "ID1183-L1"}
  }
}
```

**Without the correction every later plate-position judgement is wrong.**

---

## 4. Question 3: while the robot arm is holding a plate, can it stop?

### Three cases

#### ① The robot arm is transporting (it has grabbed the plate, has not put it down)

**It cannot stop.**

```
It has to finish this transport (put the plate at the target position)
  → only then check the pause request
```

**Why**: stopping in mid-air makes the plate fall.

**The implementation**: the `Transfer` action is atomic, it does not respond to a pause in the
middle.

#### ② The robot arm is idle, the plate is inside a device

**It can stop immediately.**

A plate inside a device is a safe state; stop and wait for a human to handle it.

#### ③ A human force-stops the robot arm

**This is the most dangerous state**:

```
The robot arm is holding the plate and a human pressed the emergency stop
  → Where is the plate? On the arm? In mid-air?
  → The system has no idea at all
```

**The handling procedure**:

```
① The system detects "the command timed out / the connection dropped" + "the previous action was a Transfer"
② The plate's position is marked as **UNKNOWN** (not "on the robot arm", but "unknown")
③ That experiment → status='paused_unknown'
④ A highest-priority alert is pushed: "the position of plate PL-001 is unknown, a human inventory is needed"
⑤ **Every workflow that involves this plate is paused**
⑥ After the human inventory, resume + corrections is called to fix the position
```

**The key point**: **you cannot assume the plate is still on the robot arm** — it may
have fallen on the floor, and it may have been taken away by someone.

### The state design: the three states of a position

| State | Meaning | How it is produced |
|---|---|---|
| **KNOWN** | Definitely at some position | A normal action completed |
| **UNKNOWN** | It is not known where it is | An exception during the transport |
| **LOST** | Confirmed lost | Confirmed by a human inventory |

**`UNKNOWN` requires human intervention**; the system does not guess at all.

### 4.1 The "set of possible positions": how many resources does one UNKNOWN lock down (added in review 202604)

**Knowing only that you "do not know" is not enough** — it also has to answer: **before
the human inventory, which resources must not be used by anybody?**

#### How the set of possible positions is computed

The **physical path** of one `Transfer(source=S, target=T)`:

```
    Timeline ──────────────────────────────────→
Inside S's device ──→ grabbed out ──→ on the robot arm ──→ put down ──→ inside T's device
          ↑ known ↑ from here on it cannot be said     ↑ only from here on it is known again
```

**The moment the exception happens** determines the possible landing places:

| When the exception happens | The set of possible positions |
|---|---|
| The command was sent, the transport has not started | `{S 的全部位置}` (all the positions of S) — it is basically certain it is still at S |
| It has been grabbed out, it is moving | `{S 的出口区, 机械臂, T 的入口区}` (the exit area of S, the robot arm, the entry area of T) |
| It has arrived at T, it has not been put down | `{机械臂, T 的入口区}` (the robot arm, the entry area of T) |
| During the put-down | `{T 的入口区, T 的第一个空位}` (the entry area of T, the first free position of T) |

**The conservative approach (the default)**: **take the union of the whole path**

```
Possible positions = { all the positions of S, all the positions of T, the robot arm }
```

**Why the union and not something more precise**:

- The reply of the device may be inaccurate (it says "it is moving", but it has already
  put the plate down)
- **Better to lock down too much than to miss one** — a missed lock-down leads to a real
  collision

#### The lock-down rule

```sql
-- 板标 UNKNOWN，同时记录可能位置集合
UPDATE plate
SET position_status = 'UNKNOWN',
    possible_locations = ARRAY[src_resource_id, dst_resource_id, robot_resource_id]
WHERE barcode = ?;

-- 把这些位置全部标为"疑似占用"（别人不能往这放板）
UPDATE position_state
SET reserved_by_run = -1,          -- -1 = 系统封锁，不是某个执行
    reserved_until = now() + interval '24 hours',
    block_reason = 'PLATE_UNKNOWN'
WHERE resource_id = ANY(?) AND current_plate_id IS NULL;
```

**Three things to note**:

| Point | Explanation |
|---|---|
| **What is locked down is the position, not the device** | The device itself can still do other things (e.g. a plate reader can read other plates); only **these positions cannot take a new plate** |
| **`reserved_by_run = -1`** | A special value that means "blocked by the system" rather than occupied by an execution; it can only be released after a human inventory |
| **The TTL is 24 hours** | Not 30 minutes — a person has to come, so give them enough time; and when the TTL expires it is **still not released automatically** (see below) |

#### The release condition: only a human inventory

**It must not be released automatically on a timeout** — the plate may really have
fallen into the device, and a timeout release would let another plate be put in there,
which **physically stacks plates**.

```
① Operations go to the site and find the plate (at S? at T? on the arm? on the floor?)
② Call the endpoint to report the actual position:
   POST /api/plates/{barcode}/locate
   { "actual_location": "ID1183-L2", "operator": "张三", "note": "卡在入口滑轨" }
③ The system updates position_status = 'KNOWN' and releases the other positions
④ The related executions call resume to continue
```

#### If the plate cannot be found

```
Mark position_status = 'LOST'
  → every execution involving this plate → aborted
  → the loss / re-sampling procedure is started
  → the related positions are released (the plate is definitely not there)
```

#### The impact on concurrency (this has to be spelled out)

**One UNKNOWN locks down all the positions of 2 devices**:

```
Before the lock-down: ID1183 has 8 free positions, ID1775 has 8 free positions
After the lock-down: all 16 positions are unusable (until the human inventory)
```

**This is a very heavy price**, but compared with "stacked plates shattering the plates
inside the device" it is worth paying.

**So the design has to produce as few UNKNOWNs as possible**:

| Measure | Effect |
|---|---|
| **Action atomicity + a generous timeout** | Fewer misjudgements of "the command timed out but it is actually still running" |
| **Commands carry an idempotency key** | When it is resent the device can recognise "this is the same transport" and does not move it twice |
| **Check the state before the emergency stop** | If the driver supports `GetStatus`, ask where the plate is before stopping |

---

## 5. Question 1 revisited: do the other experiments have to pause as well

### Case by case

| Situation | The other experiments | The reason |
|---|---|---|
| **An ordinary device fails** | **They do not stop** | They do not use that device |
| **The robot arm fails** | **Those that need transport pause**, an action that is already running finishes | The logistics are cut |
| **The plate position is UNKNOWN** | **Everything involving this plate pauses** | Nobody knows where the plate is, and any transport could collide |
| **A human pauses it deliberately** | **They do not stop** | Only this one experiment has to stop |

### Releasing the resources is mandatory

**A paused execution that still holds resources will drag the whole island to a
standstill**:

```
Workflow 1 fails while it is using B, it is paused
  - it still holds the position of A (the plate-out position)
  - workflow 2 wants to use A → it cannot reserve it → it is stuck as well
```

**So when it pauses, the resources that are "no longer needed" have to be released**:

| Resource | When it pauses |
|---|---|
| The device lock that is in use | **Keep it** (the plate may still be inside) |
| Positions that are occupied but not in use | **Release them** |
| The robot arm | **Release it** (if it is not holding a plate) |
| The island lock | Release it |

**But**: if the plate is still inside a device, **the device lock must not be released**
(another workflow would put a plate into it).

---

## 6. The complete execution state machine

```
                    ┌─────────────┐
                    │   pending   │
                    └──────┬──────┘
                           │ 调度
                    ┌──────▼──────┐
              ┌─────│   running   │─────┐
              │     └──────┬──────┘     │
        故障/人工            │ 全部完成   │ 不可恢复
              │              ▼           │
    ┌─────────▼────────┐  ┌──────────┐   │
    │  paused_fault    │  │completed │   │
    │  waiting_human   │  └──────────┘   │
    │  paused_unknown  │                 │
    └─────────┬────────┘                 ▼
              │ 人工恢复            ┌─────────┐
              └──────> running      │ aborted │
                                    └─────────┘
```

**The difference between the three pause states**:

| State | Trigger | The condition for resuming |
|---|---|---|
| `paused_fault` | A device fault | The device becomes healthy again (the heartbeat is normal) |
| `waiting_human` | Human handling is needed | A human calls resume |
| `paused_unknown` | The plate position is unknown | A human inventory + correction |

---

## 7. The relationship with the banker's algorithm (an extension of question 1)

**The question "since the banker's algorithm is used, does the plan of the other
experiments have to be recalculated when a fault occurs?"** — **yes**.

### A fault changes the available resources

```
Originally: the resources { A, B, C } are all available, a safe sequence exists
After the fault: B is unavailable → the resources { A, C }
  → the original safe sequence may no longer hold
  → **it has to be recalculated**
```

### When the recalculation happens

| Moment | The action |
|---|---|
| A device fails | Recalculate the safety of every running execution |
| A device recovers | Recalculate (more can be let through) |
| A human resumes an execution | Recalculate |

**An unsafe execution → pause** (not terminate — it waits for the resources to come
back).

### But there is a practical constraint

**A plate that is already inside a device cannot be taken out just because "the
recalculation says it is unsafe"**:

```
Halfway through an incubation, the recalculation finds "unsafe" → can it stop?
  → No, forcing it to stop ruins the sample
```

**So**:

| Resource | Can it be interrupted because of the recalculation? |
|---|---|
| A position | ✅ Yes (the plate can be moved elsewhere) |
| A device (executing) | ❌ **No** (the sample is inside it) |
| The robot arm (transporting) | ❌ **No** (the plate is in mid-air) |

**Conclusion: the "safety check" of the banker's algorithm can only be used for the
"newly requested resources"; it cannot be used for the "resources that are already held
and in use".**

**This is the practical constraint of "non-preemptible resources"** — a resource that is
already held and in use cannot be interrupted because "the recalculation says it is
unsafe"; that is a physical fact and **has nothing to do with the premises of the
banker's algorithm** (the banker's algorithm was designed for non-preemptible resources
in the first place; its premise is "knowing the maximum demand in advance", see the
conceptual correction at the end of this chapter).

---

## 8. The implementation priority

| # | Task | Depends on | Estimate |
|---|---|---|---|
| 1 | **Action atomicity** (it does not respond to a pause in the middle) | None | Already there (synchronous execution) |
| 2 | **Safe pause points** (a check before every step) | 1 | 2 days |
| 3 | **The state-machine extension** (3 pause states) | 2 | 3 days |
| 4 | **The human-resume endpoint** + the state correction | 3 | 2 days |
| 5 | **The UNKNOWN plate position** | 3 | 2 days |
| 6 | **The fault impact analysis** (which devices are depended on) | The forward scan | 3 days |
| 7 | **Releasing the non-essential resources when it pauses** | 3 | 2 days |
| 8 | **Recalculating the safe sequence** | The banker's algorithm + 6 | 3 days |

**About 3 weeks in total**, and items 6 and 8 depend on the forward scan and on the
banker's algorithm.

---

## 9. One-sentence summary

**A pause can only happen at an action boundary**, it cannot stop in the middle of an
action (the plate would fall).

**A fault does not stop the other experiments**, unless it is a robot-arm fault or the
plate position is unknown.

**Human intervention requires an explicit resume + a state correction**; the system does
not guess.

**The safety check of the banker's algorithm only covers the "newly requested"
resources; it cannot interrupt the "resources that are already held and in use".**

---

### ⚠ Conceptual correction (pointed out by review R1)

**What was written here before was "the physical world is non-preemptible, so the
banker's algorithm does not apply" — that is a misstatement.**

**The banker's algorithm (Dijkstra 1965) was designed for non-preemptible resources in
the first place** (printers, tape drives); it does not require the resources to be
preemptible.

**Its real premise is "knowing the maximum demand in advance" (max claim)**:

> Every process has to declare how many resources it will request at most, and only then
> can the system work out "after the allocation, does a safe sequence still exist".

**This project cannot satisfy that premise**:

- A workflow has `exclusiveGateway` / `loop`; which branch it takes is only known at execution time
- The maximum demand changes with the branch, so **it cannot be declared before the
  execution**

**So there is only one reason for not adopting the banker's algorithm: the maximum
demand cannot be predicted.** It has nothing to do with "whether it can be preempted".

**That difference matters**: if an implementer understands it for the wrong reason, they
may think "as soon as a preemption mechanism is added, the banker's algorithm can be
used" — that is wasted effort, the real obstacle is that the demand cannot be predicted.



