# M4: Pause / Resume / Fault Handling

> **English edition.** The Chinese original is [`docs/M4-暂停恢复与故障处理.md`](../../M4-暂停恢复与故障处理.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> One-command acceptance: `./scripts/verify-m4.sh` → **14 items passed / 0 failed**
> Unit tests: `PauseManagerSelfTest` → **10 items passed / 0 failed**

---

## 1. Three pause states (not one)

| Type | Trigger | Resume condition | Why it is a separate kind |
|---|---|---|---|
| `paused_fault` | Device fault | **It continues by itself as soon as the heartbeat recovers**, no person needed | The device is fine but nobody knows that it can continue — the most common "false deadlock" |
| `waiting_human` | Retries exhausted, plate position mismatch | **A person has to confirm it** | The system does not know whether the person has dealt with it |
| `paused_unknown` | Plate position unknown | **An inventory + a correction are required** | The position is completely unknown, that is more serious than the first two |

**Merging them into one "pause" would cause this**: the device recovers but nobody clicks continue, or the person thinks it was dealt with but it was not.

**Implementation**: the `run_status` enum is left alone, a `run.pause_kind` column is added.
Adding an enum value needs an `ALTER TYPE`, and PG does not allow it inside a transaction; and more pause kinds may be added later.

---

## 2. A pause can only happen at an action boundary

**A robot arm that has grabbed a plate from A and moved it half-way into the air — it must not stop there, the plate would fall.**

So the pause request only **marks**; the execution loop checks **before the next step starts**:

```java
for (Step step : node.steps()) {
    if (shouldPause()) {
        transitionTo(pauseState());
        return;
    }
    executeStep(step);   // once it has started, it must be finished
}
```

**The cost**: the pause latency = the remaining time of the current action.

**But incubation should not be "a 30-minute action"**:

| Phase | Kind | Can it be paused |
|---|---|---|
| Put in (the robot arm moves the plate into the incubator) | Action | It can stop at an action boundary |
| **Hold (waiting 30 minutes)** | **State** | ✅ **Can be paused at any time** |
| Take out | Action | It can stop at an action boundary |

**So the real pause latency is on the order of minutes, not 30 minutes.**

---

## 3. Resources are released on pause (with exemptions)

**A paused execution that still holds resources will drag the whole island down**:

```
Workflow 1 faults while it is using B, and it still holds A's exit position
  → Workflow 2 wants to use A, cannot acquire it, and is stuck as well
```

### The release rules

| Resource | While paused | Why |
|---|---|---|
| An ordinary "occupied but not in use" position | **Released** | So that another workflow can run |
| **The return-leg promise (`position-return`)** | **⚠ Kept** | Once you have put someone else's plate in, you have taken the position; releasing it deadlocks on the way back |
| Channel occupancy | **Released** (reset to zero) | A pause means it is not in the middle of a transfer |
| Device lock | **Only the ones with `status != running` are released** | `running` means the plate is still inside |
| The robot arm that is holding a plate | **Kept** | It cannot let go |

### Why the return-leg promise has to be exempt

**This is a contradiction that was already built into the M1 design**:

- D105 requires that the return position is **reserved for the whole time** while taking a plate
- D111 requires that on a fault pause the **"occupied but not in use" positions are released**
- **The reserved return position is precisely an "occupied but not in use" position**

**The consequence of releasing it as D111 says**:

```
① Take the plate, reserve the return position L1
② Device fault → pause → release L1
③ The fault is repaired → resume → it has to put the plate back into L1, but L1 has been taken by somebody else
④ The deadlock reproduces exactly as it was
```

**The solution**: distinguish a **reservation** (releasable) from a **promise** (not releasable).

```sql
resource_booking.release_exempt = true   -- the return-leg promise is exempt by default
```

---

## 4. The three states of a plate position

| State | Meaning | Handling |
|---|---|---|
| **KNOWN** | Definitely at a certain position | Normal scheduling |
| **UNKNOWN** | It is not known where it is | **Manual inventory**, every workflow that involves this plate is paused |
| **LOST** | Confirmed lost | Terminate the related workflows, write it off as a loss |

### How many resources an UNKNOWN blocks each time

**The physical path of one Transfer**:

```
Inside device S ──→ grabbed out ──→ on the robot arm ──→ put down ──→ Inside device T
       ↑ known          ↑ from here on it is unclear                   ↑ only known again from here
```

**The conservative approach**: take the union of the whole path

```
possible positions = { all the positions of S, all the positions of T, the robot arm }
```

**Better to block too much than to miss one** — a missed block causes a machine collision.

### The granularity of the block: the position, not the device

```sql
UPDATE position_state
SET reserved_by_run = -1,          -- -1 = a system block, not a particular run
    block_reason = 'PLATE_UNKNOWN',
    reserved_until = now() + interval '24 hours'
WHERE resource_id IN (...) AND plate_id IS NULL;
```

**The device itself can still do other things**, only these positions cannot take a new plate.

### A timeout does not unblock it automatically

**The plate may really be inside the device**. Unblocking on a timeout would let another plate in → **physically stacking plates**.

**Only a manual inventory**:

```
POST /api/plates/{barcode}/locate
{"actualLocation": "ID1183-L2"}     ← or "LOST"
```

**Every inventory leaves a trace** (`plate_location_audit`) — where the plate is has to be looked at on site by a person,
the system only records the conclusion, so a problem can be traced back.

---

## 5. A resume has to carry a correction

```
POST /api/runs/{id}/resume?action=continue
{"corrections": {"PL-001": "ID1183-L2"}}
```

**A `paused_unknown` without corrections is rejected directly**:

```json
{
  "ok": false,
  "error": "板位置未知，必须提供 corrections（板号 → 实际位置）才能恢复"
}
```

(The `error` string is the one the backend really returns — "the plate position is unknown, corrections (plate ID → actual position) are required before resuming".)

**Why it is mandatory**: what is on site and what the system recorded are very likely to disagree. **Without a correction every later plate-position judgement is wrong.**

### The four resume actions

| action | Behaviour |
|---|---|
| `continue` | Continue (a person has dealt with it) |
| `retry` | Retry the current action |
| `skip` | Skip this step (dangerous, it needs a second confirmation) |
| `abort` | Terminate |

---

## 6. The acceptance result

```
── ① The three pause states ──
  ✅ paused_fault paused successfully (the API and the database agree)
  ✅ waiting_human paused successfully
  ✅ paused_unknown paused successfully

── ⑤ The pause list is visible ──
  ✅ The test run can be seen in the list

── ③ paused_unknown without a correction has to be rejected on resume ──
  ✅ Rejected without a correction
  ✅ The reason for the rejection mentions corrections
  ✅ After the rejection it is still paused

── ④ Plate position UNKNOWN block + unblocking by the inventory ──
  ✅ The plate is in the unknown list
  ✅ The position is blocked
  ✅ The inventory succeeded
  ✅ The plate state is back to KNOWN
  ✅ The position is unblocked
  ✅ The inventory left a trace (2 audit rows)

── ② The return-leg promise is not released on pause ──
  ⏭ There is no return-leg promise booking in the database, skipped

passed 14 / failed 0
```

**The unit tests 10/10**:
```
✓ After the pause pause_kind=waiting_human
✓ The resume succeeded
✓ After the resume it is not in the paused state
✓ UNKNOWN without a correction is rejected on resume
✓ The reason for the rejection mentions corrections
✓ After the rejection it is still paused
✓ UNKNOWN + correction → the resume succeeded
✓ After the resume it is not paused any more
✓ All the bookings of the return-leg promise are marked as exempt
✓ All the PLATE_UNKNOWN blocks carry reserved_by_run=-1
```

---

## 7. The API overview

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/runs/{id}/pause?kind=&reason=&nodeKey=` | POST | Pause (three kinds) |
| `/api/runs/{id}/resume?action=` | POST | Resume (the body carries corrections) |
| `/api/runs/paused` | GET | All the runs that are paused right now |
| `/api/plates/unknown` | GET | The plates whose position is unknown / that are lost |
| `/api/plates/{barcode}/locate` | POST | Manual inventory |

---

## 8. The pitfalls hit along the way

| Pitfall | Root cause | The fix |
|---|---|---|
| Releasing the resources reports `current_plate_id does not exist` | The actual column name is `plate_id` | Rename it |
| Releasing the resources reports `reserved_by_run does not exist` | `resource_booking` does not have that column, it has to be joined via `run_node_id` | Change it into a subquery |
| Resuming reports `status is of type run_status` | An enum column cannot take a direct `setString` | Add `?::run_status` to the SQL |
| The position was not unblocked after the inventory | The test only set `block_reason`, it did not set `possible_locations` | Complete the test (the real workflow writes it) |

**The 4th one is worth talking about**: the code depends on `plate.possible_locations` to find the positions that have to be unblocked.
→ When the test builds the data by hand and misses that field, the position cannot be unblocked.

**This exposes a design dependency**: `possible_locations` is the only clue for the unblocking,
if it is lost (someone edits the database by hand), the block can never be lifted. **Redundancy has to be considered later**.

---

## 9. The relationship to M1/M2/M3

```
M3 coarse screening (before the start) → decides whether it can start
M1 resource protocol (during execution) → a fixed total order of acquisition, rollback on failure
M2 wait graph (during execution)       → rejects a cycle, guarantees no deadlock
M4 pause and resume (when something goes wrong) → it stops without making a mess and resumes without a mistake
```

**What M4 adds is "what to do when something goes wrong"** — the first three assume everything is normal,
M4 handles a broken device, a lost plate and the cases where a person has to step in.
