# Plate-Position-Level Occupancy and Position Mutual Exclusion · Design

> **English edition.** The Chinese original is [`docs/板位级占用与位置互斥-设计.md`](../板位级占用与位置互斥-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 |
| Date | 2026-11-01 |
| Status | Awaiting review |
| Prerequisite | `多实验并行调度-动态规划与滚动排程.md` |
| What this document answers | A plate has been put into a position and another experiment must never put one there as well — how is that guaranteed? |

---

## 1. The problem: a real accident scenario

```
Experiment A: puts plate PL-001 into layer 3 of Incubator_2C-1
Experiment B: at the same time wants to put plate PL-002 into layer 3 of Incubator_2C-1
        ↓
     the robot arm pushes PL-002 in
        ↓
    two plates squeezed into the same layer → stuck / crushed / contaminated
```

**This is not a logic error, it is a physical accident.** The plate breaks, the sample is ruined, the device jams and a person has to take it apart.

### 1.1 Why island-level mutual exclusion is not enough

The previous approach was "only one experiment runs on an island at a time":

```
Island = Incubator_2C-1 + XRobot + Thermo ATC
Island-level mutual exclusion → two experiments on the same island never run at the same time ✅
```

**But it misses two situations**:

| Situation | Can island-level mutual exclusion prevent it? |
|---|---|
| Two experiments are on **different islands**, but both want to put a plate into **the same shared position** | ❌ Cannot prevent it |
| The plate of the previous experiment **is still in** the position and the next experiment does not know | ❌ Cannot prevent it |

The second one is more common: experiment A finishes and leaves the plate in the incubator (that is normal, incubation takes hours), and experiment B thinks the position is empty.

### 1.2 The root cause

**Island-level mutual exclusion governs "who is using the robot arm", not "which position holds a plate".**

These are resources on two different axes:

| Resource | Busy/idle characteristic | Mutual exclusion granularity |
|---|---|---|
| **The robot arm** (the executor) | Transient (a few seconds per move) | Action level |
| **The position** (storage) | **Persistent** (the plate occupies it for hours) | Occupancy level |
| **The device** (a single machine) | Medium (tens of seconds per method) | Action level |

**Island-level mutual exclusion mixes the three together and uses the coarsest granularity** — it is safe, but the parallelism is low.

---

## 2. The four-level resource model

```
Island (island)               capacity 1  → recommended to release it in 2.0
 └ Device (device)             capacity 1  → action-level mutual exclusion
     └ Position (nest)         capacity 1  → **occupancy-level mutual exclusion (the focus of this document)**
         └ Plate (plate)       → a plate can only be in one position
```

### 2.1 The types of position

The database already has `resource_node.kind`:

| kind | Count | Purpose |
|---|---|---|
| `incubator` | 1,021 | Incubator layer positions |
| `storage` | 1,122 | Storage tower slots |
| `transfer_point` | 420 | Transfer points (in transit, put down temporarily) |
| **Total** | **2,563** | Each one is a position that "can hold only one plate" |

### 2.2 The states of a position

```sql
CREATE TABLE position_state (
  tenant_id      bigint      NOT NULL,
  resource_id    bigint      NOT NULL,   -- resource_node.id (the position)
  plate_id       varchar(64),            -- the plate that is actually in it (null = empty)
  occupied_since timestamptz,
  occupied_by_run bigint,                -- which run put it there
  reserved_until timestamptz,            -- reserved until when (in transport)
  reserved_by_run bigint,                -- who made the reservation
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, resource_id)
);
```

**Two states**:

| State | Meaning | Who clears it |
|---|---|---|
| `plate_id` is not null | The plate is **actually in** it | The take-plate action |
| `reserved_until` has not expired | Somebody has **reserved** it, it is in transport | Automatically on expiry / when the transport finishes |

### 2.3 Why "actual occupancy" and "reservation" are distinguished

```
t=0s   Experiment A decides to put a plate into position X
       → reserve X immediately (reserved_until = now+60s)
       → X cannot be used by anybody else at this moment

t=5s   The robot arm is transporting
       → X is still in the reserved state

t=8s   The plate has been put in
       → plate_id = 'PL-001', the reservation is cleared

t=8s+  The plate keeps occupying X until it is taken away
```

**Without the reservation**, two experiments could both see "X is empty" and then both put a plate in.

**A reservation = a distributed lock implemented in the database**.

---

## 3. The atomicity of a transport (the key point)

### 3.1 One transport involves two positions

```
Take the plate: position A (source) → the plate is on the robot arm
Put the plate: position B (target) ← the plate comes off the robot arm
```

**The intermediate state**: the plate is on the robot arm, A is empty, B is not occupied yet.

### 3.2 Both positions must be locked in one go

```java
/**
 * Reserve the source and the target in one go before the transport, all or nothing.
 *
 * Why you cannot "take the source first, then take the target":
 *   Experiment A holds X as its source and waits for Y as its target (held by B)
 *   Experiment B holds Y as its source and waits for X as its target (held by A)
 *   → deadlock
 */
boolean reserveTransfer(long sourceId, long targetId, long runId, Duration ttl) {
    // Request in ascending resource_id order (a global order, it eliminates circular wait)
    long first  = Math.min(sourceId, targetId);
    long second = Math.max(sourceId, targetId);

    return tx(() -> {
        if (!tryReserve(first,  runId, ttl)) return false;
        if (!tryReserve(second, runId, ttl)) {
            releaseReserve(first, runId);   // the second one failed, release the first one immediately
            return false;
        }
        return true;
    });
}
```

**Two rules**:

1. **All or nothing**: if the target cannot be taken, give the source back
2. **Ascending id order**: eliminates circular wait

These two correspond exactly to the "hold and wait" and "circular wait" conditions of the four deadlock conditions.

### 3.3 The reservation timeout

```java
// the ttl defaults to 120 seconds (a transport normally takes a few seconds, this leaves ample margin)
// not completed before the timeout → the reservation expires automatically and the position can be requested again
```

**Why it is needed**: when the robot arm fails or the process crashes, the reservation does not stay stuck forever.

**Cost**: after the timeout two experiments may both think the position is available — but that is "a degradation in a very rare abnormal case", and it is **better than being stuck forever**.

---

## 4. How the scheduler uses position occupancy

### 4.1 The static pre-check (during the schedule dry run)

```
The steps of experiment EXP0812:
  1. Plate-in workflow → target position: Incubator_2C-1/L3
  2. Incubation workflow → the position does not change
  3. Plate-out workflow → take it out of Incubator_2C-1/L3
```

**The result of the pre-check**:

```
┌─ Schedule dry run · EXP0812 ─────────────────────────┐
│ Positions required:                                  │
│   Incubator_2C-1/L3   current: empty ✅              │
│                       expected occupancy 00:00-06:00 │
│                                                      │
│   XRobot gripper       current: idle ✅              │
│   Thermo ATC/S1        current: held by EXP0810 ⚠️   │
│                       expected release 04:00         │
│                                                      │
│ Suggested start time: 04:00 (wait for Thermo ATC/S1) │
│ [Adjust] [Run anyway (it will wait)]                 │
└──────────────────────────────────────────────────────┘
```

### 4.2 The runtime check (before execution)

```java
// Check before every step (not only the pre-check)
void beforeStep(StepContext ctx) {
    for (long posId : ctx.targetPositions()) {
        if (!positionLock.tryReserve(posId, ctx.runId(), TTL)) {
            throw new NodeFailure("EC-12-0001",
                "目标位置已被占用：" + posName(posId));
        }
    }
}
```

**Why it has to be checked at runtime as well**:

There can be several hours between the pre-check and the execution, and in that time the position may have been taken by somebody else.

**The pre-check is for scheduling, the runtime check is for safety** — two different things.

### 4.3 Position occupancy does not block scheduling, it blocks one step

```
The target position is occupied → raise EC-12-0001
  ↓
Go through the recovery policy (already there):
  - Use another position (if the workflow allows it)
  - Wait (when wait is configured)
  - Hand over to an operator
```

**It does not fail the whole experiment** — a failed step can be retried or can use another position.

---

## 5. Plate-level tracking (where the plate is)

### 5.1 The minimal implementation

```sql
CREATE TABLE plate (
  tenant_id    bigint      NOT NULL,
  plate_id     varchar(64) NOT NULL,     -- the barcode
  plate_type   varchar(32),              -- 96-well / 384-well
  current_pos  bigint,                   -- which position it is in (null = on the robot arm / already out)
  current_run  bigint,
  status       varchar(16),              -- in_storage / in_transfer / consumed
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, plate_id)
);
```

**`plate.current_pos` and `position_state.plate_id` are the reverse lookup of each other**, and both have to be maintained (or a view has to be used).

### 5.2 The consistency problem

The two tables may disagree (one says X holds a plate, the other says the plate is in Y).

**The solution**: `position_state` is the authority (it is the physical fact), the `plate` table is a redundancy used for queries.

```sql
-- Daily reconciliation
SELECT count(*) FROM position_state ps
LEFT JOIN plate p ON p.plate_id = ps.plate_id
WHERE ps.plate_id IS NOT NULL AND p.plate_id IS NULL;
-- should = 0
```

---

## 6. The relation to island-level mutual exclusion (important)

### 6.1 A comparison of the two approaches

| Option | Parallelism | Safety | Complexity |
|---|---|---|---|
| **A. Island-level mutual exclusion** (the previous approach) | The number of islands (26) | ✅ They never collide | Low |
| **B. Position level + robot arm level** (the approach in this document) | Much higher | ✅ The positions are safe | High |
| **C. Both** (recommended for 1.0) | 26 | ✅ Double insurance | Medium |

### 6.2 Why 1.0 recommends C

**Only A**: a conflict on a shared position across islands cannot be prevented.

**Only B**:
- It needs durations accurate to the second (today only to 10 seconds)
- It needs a complete initialisation of the plate positions (today there are 2,563 positions and it is not known which of them hold a plate)
- It needs time-sliced scheduling of the robot arm (never done)

**A + B**:
- A covers "not running at the same time on the same island" (conservative but absolutely safe)
- B covers "no position is occupied twice" (prevents cross-island conflicts + plates left over from the past)

> **A is the safety net, B is correctness.** First correctness, then loosen the safety net step by step.

### 6.3 The preconditions for releasing island-level mutual exclusion in 2.0

| Precondition | Today |
|---|---|
| A duration model accurate to the second | ❌ Only to 10 seconds |
| The initial state of the positions is known | ❌ 2,563 positions, not initialised |
| Time-sliced scheduling of the robot arm | ❌ Not done |
| The position lock has run stably for 3 months | ❌ Just built |

**It is only released when all four are satisfied**.

---

## 7. Initialisation: where the position state comes from

### 7.1 Cold start (the first go-live)

```
All positions → empty
```

**The risk**: there may be plates in the real devices (left over from earlier runs).

**How it is handled**:
1. Empty all the plates by hand before going live
2. Or: do one inventory at go-live (scanning by hand)
3. Or: run an "inventory scan" workflow (if the devices support it)

### 7.2 The transition period

**Operating the devices from both sides at the same time is absolutely forbidden**.

**The transition plan**:
```
Stop the old system → empty by hand → bring the new system online → inventory → start running
```

It is not possible to "let the old system run island A and the new system run island B" — the position lock does not know that the old system put a plate down.

---

## 8. Summary of the data model

```sql
-- Position occupancy (the core)
position_state (tenant_id, resource_id, plate_id,
                occupied_since, occupied_by_run,
                reserved_until, reserved_by_run, updated_at)

-- Plate
plate (tenant_id, plate_id, plate_type, current_pos, current_run, status)

-- The positions an experiment declares it will use (optional, used by the pre-check)
experiment_position (experiment_id, resource_id, action)
```

---

## 9. The interfaces

| Method | Path | Description |
|---|---|---|
| GET | `/api/positions?islandId=` | All positions under an island and their occupancy state |
| GET | `/api/positions/{id}` | The details of a single position |
| POST | `/api/positions/{id}/release` | Release by hand (for the inventory) |
| GET | `/api/plates?status=` | The list of plates |
| GET | `/api/plates/{id}` | The position history of a plate |
| POST | `/api/workflows/{id}/check-positions` | Pre-check: which positions does this workflow occupy, and are they available |

---

## 10. The error codes

| Code | Meaning | Handling |
|---|---|---|
| `EC-12-0001` | The target position is already occupied | Use another position / wait / hand over to an operator |
| `EC-12-0002` | The source position has no plate | The data is inconsistent, hand over to an operator |
| `EC-12-0003` | The reservation timed out without completing | Check the state of the robot arm |
| `EC-12-0004` | The position does not exist | Configuration error |

---

## 11. The acceptance criteria

| Question | How it is verified |
|---|---|
| Two experiments put a plate into the same position | The first one occupies it, the second one raises `EC-12-0001` |
| After the plate is put in, the position is occupied | `position_state.plate_id` is not null, the other experiments fail to reserve it |
| After the plate is taken, the position is released | `plate_id = null`, the other experiments can reserve it |
| A crash in the middle of the transport | The reservation TTL expires and releases it automatically, it does not stay stuck forever |
| Two executions wait for each other's target position | They are requested in ascending id order, there is no deadlock |
| Where exactly the plate is | `GET /api/plates/{id}` returns the current position |

---

## 12. The phases

| Phase | Content |
|---|---|
| **1.0** | Position table + reservation lock + transport atomicity + runtime check + island-level mutual exclusion (double insurance) |
| 1.1 | Plate-level tracking + position visualisation (which positions on the island hold a plate) |
| 1.2 | Pre-check interface (which positions the workflow will occupy) |
| 2.0 | Release the island-level mutual exclusion (position level + robot arm level) |
| 2.1 | Automatic position optimisation (which position becomes free the soonest) |
