# Parallel Scheduling — Implementation Specification

> **English edition.** The Chinese original is [`docs/并行调度-实施规格.md`](../并行调度-实施规格.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **This is the specification you "write the code against"**, not a design discussion.
> The design discussion lives in the topic documents; this file only states "what has to
> be implemented and how it is accepted".

**Scope**: support **3 or more experiments in parallel** (`lab_profile.max_parallel >= 3`).
**Total duration**: **4.75 weeks** (5 milestones).

> ⚠ **The resource model in this file has been corrected**; see
> `docs/资源模型修正-出入口门与点位属性.md`.
> The main changes:
> - **L2 port is not "1 per device"**, it is the `device_port` table, 1~N of them, with a
>   direction (in/out/both)
> - **A new L3 "door" resource** (`device_access`) — if the door is not open, the robot arm
>   reaching in will crash
> - **`robot_point` gains 9 attributes** (which port / which door / approach direction /
>   safe height and so on)
>
> The "fixed total order" in section 2 of this file has to follow the corrected order:
> `door → channel → position → return position → robot arm`, with the **door first** (if
> the door is open but the robot arm was not acquired, the door can be closed and the
> attempt abandoned).

---

## 1. Resource model

### 1.1 The three layers

| Layer | Table/field | Granularity | Sharing | Occupancy duration |
|---|---|---|---|---|
| **L1 position** | `position_state` | One shelf level | Exclusive | The whole task |
| **L2 port** | `resource_booking` (`source_type='port'`) | One per device | Shared | **Action-level** |
| **L3 robot arm** | `resource_booking` (`source_type='robot'`) | One per island | Shared | **Action-level** |

### 1.2 Extending `resource_booking`

**An existing table** (no need to create one); reuse the `period` + `ex_booking_overlap`
constraint:

```sql
-- the constraint that already exists
EXCLUDE USING gist (resource_id WITH =, period WITH &&)
WHERE (status IN ('planned','active') AND deleted_at IS NULL)
```

**The permitted values of `source_type`** (new semantics, the structure is unchanged):

| Value | Meaning | Can it be released on pause? |
|---|---|---|
| `port` | A device entrance/exit | ✅ |
| `robot` | The robot arm | ✅ (when it is not holding a plate) |
| `position-reserve` | An ordinary position reservation | ✅ |
| **`position-return`** | **A return commitment** | ❌ **Must not be released** (review R2) |

### 1.3 Extending the plate position state

```sql
ALTER TABLE position_state ADD COLUMN IF NOT EXISTS
    block_reason varchar(32);
-- PLATE_UNKNOWN = the system locks the position when the plate position is unknown, and waits for a manual inventory

ALTER TABLE plate ADD COLUMN IF NOT EXISTS
    position_status varchar(16) NOT NULL DEFAULT 'KNOWN';
-- KNOWN / UNKNOWN / LOST

ALTER TABLE plate ADD COLUMN IF NOT EXISTS
    possible_locations bigint[];
-- the set of possible locations while UNKNOWN: {source device, target device, robot arm}
```

---

## 2. Resource acquisition protocol (the **most critical invariant**)

### 2.1 The fixed total order

**A single Transfer has to request the resources in this order**:

```
① Source port     (L2, the port of the source device)
② Source position (L1, one of the source device's positions)
③ Target port     (L2, the port of the target device)
④ Target position (L1, one of the target device's positions)
⑤ Return position (L1, source_type='position-return')
⑥ Robot arm       (L3, the robot of the island)
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   **Up to here all resources are in hand; the plate is still at the source position, so it can be abandoned safely**
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑦ Grab the plate    ← the point of no return
⑧ Move, put down
⑨ Release: the robot arm, the source port, the target port
⑩ Keep the return position (until the plate goes back)
```

### 2.2 The invariant

> **The plate does not leave the source position until all resource requests have
> completed.**

**If any step fails**:

```java
// pseudocode
List<Long> acquired = new ArrayList<>();
try {
    for (Resource r : orderedRequests) {
        long id = acquire(r);   // throws on failure
        acquired.add(id);
    }
    // only grab the plate once everything is in hand
    grabPlate();
} catch (AcquireFailed e) {
    releaseAll(acquired);       // release in reverse order
    // the plate is still at the source position, the state is unchanged, it is safe to retry
    return RETRY;
}
```

### 2.3 Why the order has to be fixed

| Reason | Explanation |
|---|---|
| **Eliminates circular wait** | A fixed total order → "A holds 1 and waits for 2, B holds 2 and waits for 1" cannot happen |
| **"Rejection" can actually be carried out** | Only while the plate has not been grabbed can it be safely abandoned |
| **Reduces the holding time** | The robot arm is requested last, so it is held for the shortest time |

**Note**: this does not conflict with "ascending id" — ascending id only applies within
one class of resource; across types there has to be an explicit total order.

---

## 3. Coarse screening (before start)

### 3.1 Defining the main path

**Three ways, in priority order**:

```java
String mainPath = node.attr("mainPath");           // ① marked by hand
if (mainPath == null) {
    mainPath = selectHeaviestBranch(node);          // ② the heaviest in resources
}
if (mainPath == null) {
    mainPath = selectLongestBranch(node);           // ③ the most nodes
}
```

**`selectHeaviestBranch`**:

```java
// compute the size of the main-path device set of each branch, take the largest
Branch best = null;
int maxSize = -1;
for (Branch b : node.branches()) {
    Set<Long> devices = collectDevices(b);   // the devices this branch uses
    if (devices.size() > maxSize) {
        maxSize = devices.size();
        best = b;
    }
}
```

### 3.2 The coarse-screening algorithm

**The resource unit = the device** (not the position).

```java
boolean canStart(long workflowId, long tenantId) {
    // ① the requirement of the run that is about to start: all devices on the main path
    Set<Long> need = mainPathDevices(workflowId);

    // ② the remaining requirement of each running run
    Map<Long, Set<Long>> runningNeed = new HashMap<>();
    for (Run r : runningRuns()) {
        runningNeed.put(r.id(), remainingDevices(r));   // from the current node to the end of the main path
    }

    // ③ allocated = the devices currently held by all running runs
    Set<Long> allocated = allHeldDevices(runningRuns());

    // ④ available = all devices - allocated
    Set<Long> available = allDevices();
    available.removeAll(allocated);

    // ⑤ the banker's safety check
    Map<Long, Set<Long>> work = new HashMap<>(runningNeed);
    work.put(-1L, need);                    // -1 = the one about to start
    Set<Long> W = new HashSet<>(available);
    Set<Long> done = new HashSet<>();

    boolean progress = true;
    while (progress) {
        progress = false;
        for (var e : work.entrySet()) {
            if (done.contains(e.getKey())) continue;
            if (W.containsAll(e.getValue())) {
                W.addAll(heldDevices(e.getKey()));   // completing it releases
                done.add(e.getKey());
                progress = true;
            }
        }
    }
    return done.size() == work.size();      // all can complete = safe
}
```

### 3.3 The output when a request is rejected

```json
{
  "canStart": false,
  "reason": "Not enough resources: the incubator is held by 2 runs",
  "estimatedWaitSeconds": 1800,
  "blocking": [
    {"runId": 123, "deviceCode": "ID1183", "nodeName": "Incubate", "etaSeconds": 1800}
  ]
}
```

**The `estimatedWaitSeconds` algorithm**: find "the blocking run that can finish
soonest", and compute the remaining time with `duration_model.base` (**inaccurate; mark
it as for reference only**).

---

## 4. Fine-grained control (during execution)

### 4.1 The wait graph

**In-memory structure**:

```java
// runId → the set of runs it is waiting for
Map<Long, Set<Long>> waitFor = new ConcurrentHashMap<>();
```

### 4.2 Taking a resource

```java
AcquireResult acquire(Resource r, long runId) {
    // ① try to take it directly
    if (tryAcquireLock(r, runId)) {
        return OK;
    }

    // ② find the holder
    Long holder = holderOf(r);
    if (holder == null) return RETRY;      // a transient race

    // ③ add an edge
    waitFor.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(holder);

    // ④ cycle detection
    if (hasCycle(runId)) {
        waitFor.get(runId).remove(holder);   // withdraw the edge
        return WOULD_DEADLOCK;
    }

    return WAIT;                            // wait, and retry when woken up
}

boolean hasCycle(long start) {
    Set<Long> visited = new HashSet<>();
    Deque<Long> stack = new ArrayDeque<>();
    stack.push(start);
    while (!stack.isEmpty()) {
        long cur = stack.pop();
        if (!visited.add(cur)) continue;
        for (long next : waitFor.getOrDefault(cur, Set.of())) {
            if (next == start) return true;     // back to the start = a cycle
            stack.push(next);
        }
    }
    return false;
}
```

### 4.3 Handling a rejection

```java
switch (result) {
    case WOULD_DEADLOCK:
        // back off and retry, do not let it spin forever
        sleep(200 + random(300));
        retryCount++;
        if (retryCount > MAX_RETRY) {
            transitionTo(WAITING_HUMAN, "资源环检测连续拒绝");
        }
        break;
    case WAIT:
        parkUntilNotified();           // woken up when it is released
        break;
}
```

### 4.4 Starvation detection

```java
// scan once every 30 seconds
for (var e : waitStartTime.entrySet()) {
    long waitedSec = now() - e.getValue();
    if (waitedSec > 600) {                     // 10 minutes
        alert("执行 " + e.getKey() + " 等待超过 10 分钟");
        // priority ageing
        bumpPriority(e.getKey(), (int) (waitedSec / 60));
    }
}
```

---

## 5. Pause and resume

### 5.1 The state machine

```
pending → running → completed
            │
            ├─→ paused_fault    (device fault, the heartbeat recovery continues automatically)
            ├─→ waiting_human   (retries exhausted / plate position mismatch, human resume)
            └─→ paused_unknown  (plate position unknown, manual inventory + correction)
```

### 5.2 Safe pause points

```java
for (Step step : node.steps()) {
    if (shouldPause()) {
        transitionTo(pauseState());
        return;
    }
    executeStep(step);      // once it has started, it must run to completion
}
```

### 5.3 Releasing resources on pause

```java
List<Long> toRelease = bookings.stream()
    .filter(b -> b.runId() == runId)
    .filter(b -> !"position-return".equals(b.sourceType()))   // ★ exemption (R2)
    .toList();
release(toRelease);
```

### 5.4 The resume interface

```
POST /api/runs/{runId}/resume
  ?action=continue|retry|skip|abort
  body: { "corrections": { "plate_position": {"plate": "...", "now_at": "..."} } }
```

**`corrections` is mandatory** (when `paused_unknown`) — without a correction of the
position, every later plate-position judgement is wrong.

---

## 6. Configuration

**`lab_profile` is read-only**, the table structure is not changed:

```java
LabProfile p = LabProfile.load(tenantId);   // 1 hour TTL
switch (p.maxParallel()) {
    case 1 -> new SimpleScheduler();        // device locks only
    case 2 -> new ReturnToScheduler();      // + return reservation
    default -> new HybridScheduler();       // three layers + coarse screening + fine control
}
```

---

## 7. Implementation milestones

### M1: four-layer resources + expression engine (1.75 weeks)

| Task | Deliverable |
|---|---|
| Extend `resource_booking.source_type` | `port`/`robot`/`position-reserve`/`position-return` |
| **`device_port` table** + configuration of the three forms | Separate (in+out) / many in and many out / single (both) |
| **`device_port_usage` semaphore** | capacity=1 is mutually exclusive, capacity>1 is a counter (the user confirmed this is physically common) |
| **`device_access` door table** | The state is inferred from the **action result**, not queried from the device |
| **`robot_point` gains 9 fields** | point_type/port_id/position_id/access_id/approach_axis/safe_z_offset/max_stack/height_mm/order_in_device |
| **`device_point_template` template table** | The attributes are carried over automatically by device.model |
| Position `fill_status` | ok / pending / verified; the pending ones do not take part in dynamic planning |
| Action-level lock on the robot arm | Reuse `DeviceLock` |
| **Variable substitution in the position code** | `substVars(target)` before looking up `robot_point` (`ID1183-L${i}`) |
| **Expression engine** | `${usedSlots + 1}`, `${i} % 8`, `${a > b ? 1 : 2}` |
| **`order_in_device` mandatory validation** | If it is missing, mark `fill_status='pending'` |
| **Loop expansion** | Every step of a loop node uses a different `i`, expanded into several Transfers |
| Automatic position selection (**optional**) | `firstAvailablePosition()` is **off by default**, it is only used when `autoPickSlot=true` |

**Acceptance** (5 scenarios):
```
① Separate entrances/exits: flow 1 goes in, flow 2 comes out, neither blocks the other
② Many in and many out: 4 plates move in and out at the same time (4 ports, each with capacity 1)
③ A single port: in and out are mutually exclusive (the same lock)
④ Door: entry is rejected while the door is not open; after the open action succeeds, state='open'
⑤ Template: when a new device syncs its positions, approach_axis/safe_z_offset are
        carried over automatically; if position_id is missing then fill_status='pending',
        and it does not take part in scheduling
⑥ **Loop + variable**: loop i=1..8, target='ID1183-L${i}'
        assertion: it expands into 8 steps, and the position code looked up differs per step
⑦ **Doors in pairs**: every path (success/failure/timeout/pause) performs the door close;
        if the close fails it is marked unknown and an alert is raised
```

### M2: fine-grained control (1 week)

| Task | Deliverable |
|---|---|
| Wait graph + cycle detection | The `WaitForGraph` class |
| Requesting in a fixed total order | `TransferResourcePlan` |
| Backoff and retry after a rejection | `WOULD_DEADLOCK` + backoff |

**Acceptance**:
```
Scenario: 3 workflows follow the A→B→C→A cycle
Assertions:
  - the 3rd workflow is rejected and does not enter the wait graph
  - the first 2 eventually complete
  - there is no deadlock throughout (all finish within 2 minutes, or starvation is reported explicitly)
```

### M3: main-path definition (3 days, can run in parallel with M1)

| Task | Deliverable |
|---|---|
| Marking `mainPath` by hand | A field on the workflow orchestration page |
| Automatically picking "the heaviest in resources" | `selectHeaviestBranch()` |
| Automatically picking "the most nodes" | `selectLongestBranch()` |

**Acceptance**:
```
The workflow has 2 branches (with a sample / an empty plate)
Mark mainPath=with a sample
Assertion: the coarse screening computes with the device set of "with a sample"
```

### M4: coarse screening (1 week)

| Task | Deliverable |
|---|---|
| The banker's safety check | `canStart()` |
| The start interface returns explicit information | `canStart` + `estimatedWait` + `blocking` |
| The queue (the ones that cannot start queue up) | `startQueue` |

**Acceptance**:
```
Scenario: there is only 1 incubator, and 2 workflows both need it
Assertions:
  - the 1st starts successfully
  - the 2nd returns canStart=false, and blocking points at the 1st
  - estimatedWait is not empty
```

### M5: starvation handling (3 days)

| Task | Deliverable |
|---|---|
| Statistics on the waiting duration | `waitStartTime` |
| Priority ageing | +1 per minute |
| Starvation alert | Push an alert after more than 10 minutes |

**Acceptance**:
```
Scenario: a long task holds the device, a short task waits
Assertion: the short task triggers an alert after waiting 10 minutes, and its priority has been raised
```

---

## 8. Overall acceptance (after M1-M5 are done)

### Scenario A: two plates enter a multi-position device

```
B has 3 internal positions
2 workflows each deliver a plate into B
```
**Expected**: they complete in parallel, each occupying one position.

### Scenario B: a cycle of three workflows

```
3 workflows follow A→B→C→A
```
**Expected**: no deadlock; after the 3rd is rejected it retries, and in the end all of
them complete.

### Scenario C: the scope of a device fault

```
Flow 1 uses B, flow 2 does not use B
B faults
```
**Expected**: flow 1 pauses, **flow 2 continues**.

### Scenario D: the robot arm emergency stop

```
An emergency stop in the middle of a Transfer
```
**Expected**: the plate is marked UNKNOWN, all positions of the 2 devices are locked, an
alert is pushed, and it waits for a human.

### Scenario E: degradation to a single experiment

```
max_parallel = 1
```
**Expected**: only the device locks are used; the coarse screening and the wait graph are
not started (no performance loss).

---

## 9. Risks and premises

### Premises (**if they do not hold, it has to be re-assessed**)

| # | Premise | If it does not hold |
|---|---|---|
| 1 | **The lab will configure `max_parallel >= 3`** | 4 weeks of work are wasted |
| 2 | The number of devices in an island ≤ 20 | The wait-graph DFS may get slower (small impact) |
| 3 | The number of branches in a workflow ≤ 10 | The main-path selection algorithm has to be optimised |
| 4 | **Somebody can get to the site when a fault happens** | UNKNOWN stays stuck for a long time; night mode has to be on |

### Known risks

| Risk | Mitigation |
|---|---|
| **The main path is marked wrongly** | Fine-grained control catches it (it only affects the experience) |
| **False rejection** (conservative) | Record the cases that "were rejected but would have passed", and review them regularly |
| **The wait graph misjudges** | Provide a snapshot interface `GET /api/deadlock/graph` to make troubleshooting easier |
| **The coarse screening's estimatedWait is inaccurate** | Mark it in the interface as "an estimate, for reference only" |

---

## 10. Review points

**The parts of this specification that most deserve to be challenged**:

| # | Question |
|---|---|
| 1 | Is the "action-level" boundary of the three layers of resources clear enough? Is a `Transfer` one action or three (grab / move / put)? |
| 2 | Is the **fixed total order** (source port → source position → target port → target position → return position → robot arm) physically reasonable? Are there devices that "have to take the robot arm before opening the cabinet door"? |
| 3 | Will the coarse screening's "heaviest in resources" choice of the main path always pick the exception branch (because exception branches usually need more devices)? |
| 4 | In **scenario C (the scope of a device fault)**, will "flow 2 continues" in practice be blocked by the shared robot arm? |
| 5 | Is **M1's acceptance** (two plates entering B at the same time) feasible on real devices? Is there a physical restriction that "a device can only accept one plate at a time"? |
