# M1 Acceptance Report: Parallel Scheduling Infrastructure

> **English edition.** The Chinese original is [`docs/M1验收报告.md`](../../M1验收报告.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> One-command acceptance: `./scripts/verify-m1.sh`
> **Result: 12 items passed / 0 failed**

---

## 1. What is being accepted

What M1 has to solve is "**when several experiments run in parallel, the devices must not collide**".

Five things:

| # | Capability | Why it is needed |
|---|---|---|
| 1 | **Method boundary** | A robot arm should not have an "open the gate" method, an incubator should not have a "move a plate" method |
| 2 | **Gate protection** | With the centrifuge door shut, a robot arm reaching in would hit the machine |
| 3 | **Channel semaphore** | A wide channel can take two plates at once, it cannot use a mutual-exclusion lock |
| 4 | **Position generation** | The robot arm reports the levels it knows about |
| 5 | **Failure rollback** | If opening the gate fails, the run must be able to retreat intact and must not keep holding the channel |

---

## 2. The result of every item

```
── 0. Starting the per-type simulators ──
  ✅ The simulator is at 19701 (robot)
  ✅ The simulator is at 19702 (incubator)
  ✅ The simulator is at 19703 (centrifuge)

── ① Method boundary (different types have different method sets) ──
  ✅ robot.Home succeeded
  ✅ robot.OpenDoor was rejected (EC-06-0004)
  ✅ incubator.OpenDoor succeeded
  ✅ incubator.Transfer was rejected (EC-06-0004)

── ④ Position generation (the robot arm reports) ──
  ✅ robot.GetPoints succeeded
  ✅ 4 positions returned

── ②③⑤ Gate / channel semaphore / failure rollback ──
  ✅ The gate configuration exists (1 entry)
  ✅ The channel count is readable: BOTH|0/2
  ✅ TransferPlan passed 3, failed 0
```

---

## 3. The details of the five scenarios

### ① Method boundary

**What is verified**: when the device is wired up wrongly, it **must fail immediately**.

```
Send OpenDoor to the robot arm  → EC-06-0004
Send Transfer to the incubator   → EC-06-0004
```

**Why it does not "pretend to succeed"**: if the simulator accepted everything,
a mis-wired device on site would mean **the demo works and the real machine collides**.

### ② Gate protection

**What is verified**: while the gate is shut, `OpenDoor` is called before the Transfer.

**Implementation**: the **first step** of `TransferPlan.acquireAll` is to open the gate.

```
The gate was opened but the robot arm could not be acquired → close the gate and give up, the cost is small
The robot arm was acquired and only then the gate turns out not to open → the plate is already in the gripper, it cannot be put back
```

**This order is not arbitrary.**

### ③ Channel semaphore

**What is verified**: with `capacity=2` two can be held at the same time, the third one fails.

```sql
UPDATE device_port_usage SET in_use = in_use + 1
WHERE port_id = ? AND in_use < max_capacity
RETURNING in_use;
```

```
1st time → in_use=1  ✅
2nd time → in_use=2  ✅
3rd time → 0 rows      ✅
```

**`capacity=1` is only a special case of `max_capacity=1`**, there is no need for two sets of code.

### ④ Position generation

**What is verified**: the robot arm's `GetPoints` returns the positions it believes in.

```
ID1731-L1  (320, 140, 45)
ID1731-L2  (320, 180, 45)
ID1731-L3  (320, 220, 45)
ID1731-L4  (320, 260, 45)
```

**Note**: the simulator returns a **fixed 4**.
On the real machine these coordinates come from teaching, `order_in_device` is configured on site.

### ⑤ Failure rollback

**What is verified**: after opening the gate fails, the channels already applied for must be released.

```
Opening the gate fails → throws AcquireException
         → rollback() releases them in reverse order
         → the channel occupancy returns to zero (0 → 0) ✅
         → the plate is still at the source position, a safe retry is possible
```

---

## 4. The two real problems fixed along the way

### Problem 1: the open-gate command was sent to the robot arm's driver

**The original code**:
```java
AccessOpener opener = m -> driver.execute(buildCommand(ctx, deviceId, m));
//                            ↑ the robot arm's driver
```

**One Transfer involves two devices**, each of them has its own driver.
Opening the centrifuge's gate with the robot arm's driver:

| Case | Consequence |
|---|---|
| The robot arm has no OpenDoor | EC-06-0004 is reported, the workflow cannot go on |
| **The robot arm happens to have a method with the same name** | **The wrong gate was opened and it still looks like it succeeded** |

**The second case is the most dangerous.**

**The fix**: look up the driver of the corresponding device by `resourceId`.

### Problem 2: the simulator had no gate method

**The symptom**: the gate state stayed `unknown`.

**The cause**: the simulator does not know `OpenDoor` / `CloseDoor`.

**The fix**: give the method set per device type (`--profile`).

---

## 5. What is not verified yet (M1 does it)

| Item | Why M1 does not do it |
|---|---|
| **Wait-graph deadlock detection** | It needs 3+ workflows really running concurrently, the construction is complicated |
| **Main-chain coarse screening** | It depends on the workflow branch analysis, M3 does it |
| **Pause/resume state machine** | An independent feature, M5 does it |
| **Real concurrency of multiple workflows** | M1 only guarantees "a single Transfer does not collide" |

**The boundary of M1 is very clear**: **the resource protection of a single transfer**.
"What happens when several workflows run at the same time" is M2's business.

---

## 6. The switch

**The resource protection is off by default**:

```bash
LABOS_TRANSFER_PLAN=1   # turn it on
```

**Why it is off by default**: changing the core transfer logic is risky.
First let the site verify that "the existing workflows are unaffected while the switch is off", then turn it on step by step.

**The regression while the switch is off**:

```
ExpressionSelfTest   30/30
RecoverySelfTest     17/17
EngineSelfTest       14/14
AlertSelfTest        12/12
GraphRunnerTest       5/5
processes             100%
```

---

## 7. The M1 delivery list

| Phase | Content | Status |
|---|---|---|
| M1.1 | Expression engine (30 unit tests) | ✅ |
| M1.2 | Table creation (5 new tables + 10 fields) | ✅ |
| M1.3 | Async result injection | ✅ |
| M1.4 | Resource acquisition protocol (a fixed total order + rollback) | ✅ |
| M1.5 | Integration (controlled by the switch) | ✅ |
| M1.6 | Acceptance (all 12 items passed) | ✅ |

**New code**:
- `expr/` (5 classes, the expression engine)
- `RunResultService`
- `DevicePortLock` / `DeviceAccessManager` / `TransferPlan`
- The `--profile` of `DeviceSimulator`

**New documents**: 9 (design + decision record + acceptance report)

---

## 8. The next step

| Phase | Content | Estimate |
|---|---|---|
| **M2** | Wait-graph deadlock detection | 1 week |
| M3 | Main-chain definition + coarse screening | 1.5 weeks |
| M4 | Pause/resume state machine | 1 week |
| M5 | End-to-end multi-workflow acceptance | 3 days |

**M2 is the next focus** — it solves "whether 3 workflows going A→B→C→A in a cycle will deadlock".
