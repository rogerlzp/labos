# Resource Model Correction: Entrances/Exits, Gates and Point Attributes

> **English edition.** The Chinese original is [`docs/资源模型修正-出入口门与点位属性.md`](../资源模型修正-出入口门与点位属性.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> The **L2 "one entrance/exit per device" is wrong**.
> In reality there are three forms of entrance/exit + one resource I missed: **the gate**.

---

## 1. The four facts the user pointed out

| # | Fact | Impact on the design |
|---|---|---|
| 1 | An entrance/exit has three forms: separate / multiple in and out / single | L2 cannot be "1 per device" |
| 2 | The robot arm has only one gripper, and pick up → transport → put down is **not interruptible** | ✅ The "action-level" definition of L3 is correct |
| 3 | **If the gate is not open, the robot arm crashes when it reaches in** | **One resource was missing: the gate** |
| 4 | The points need extra attributes before dynamic planning is possible | The fields of `robot_point` are not enough |

---

## 2. The corrected resource model (four layers)

| Layer | Resource | Granularity | Count | Occupancy |
|---|---|---|---|---|
| **L1 position** | `position_state` | one layer position | N per device | the whole task |
| **L2 channel** | `device_port` | one in/out port | **1~N per device** | action level |
| **L3 gate/barrier** | `device_access` | one openable part | 0~N per device | **action level (while it is moving)** |
| **L4 robot arm** | `device_state` | one robot | 1 per island | action level |

### 2.1 L2: channels (three forms)

```sql
CREATE TABLE device_port (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint NOT NULL,
    resource_id   bigint NOT NULL REFERENCES resource_node(id),
    code          varchar(32) NOT NULL,       -- 'IN-1' / 'OUT-1' / 'BOTH'
    direction     varchar(8)  NOT NULL,       -- 'in' | 'out' | 'both'
    port_type     varchar(16) NOT NULL,       -- 'slot' | 'tray' | 'drawer' | 'conveyor'
    capacity      smallint NOT NULL DEFAULT 1,-- how many plates can pass at the same time
    position_id   bigint,                     -- the position_state it is linked to (if any)
    UNIQUE (tenant_id, resource_id, code)
);

COMMENT ON COLUMN device_port.direction IS
    'in=in only out=out only both=shared by in and out (only one plate can pass at a time)';
COMMENT ON COLUMN device_port.capacity IS
    'How many plates can pass at the same time. 1=serial, >1=can run in parallel (e.g. a wide conveyor)';
```

**How the three forms are configured**:

| Form | Configuration |
|---|---|
| **Separate** | `IN-1`(in) + `OUT-1`(out) → **two independent locks** |
| **Multiple in and out** | `IN-1`(in) `IN-2`(in) `OUT-1`(out) `OUT-2`(out) → four locks, they can run in parallel |
| **Single** | `BOTH`(both, capacity=1) → **one lock, in and out exclude each other** |

**Key point**: **the number of locks is decided by the configuration, the code does not have to change**.

### ⚠ When capacity > 1 the lock is a "semaphore", not "mutual exclusion" (confirmed with the user)

**The user was explicit**: channels with capacity > 1 are **physically common** (wide conveyors, multi-level trays),
so **1 / 2 / N must all be supported**.

**So the channel lock cannot be "is it occupied", it has to be "how many more can go in"**:

```sql
-- Not a status='active' mutual exclusion, but a counter
CREATE TABLE device_port_usage (
    tenant_id   bigint NOT NULL,
    port_id     bigint NOT NULL REFERENCES device_port(id),
    in_use      smallint NOT NULL DEFAULT 0,
    max_capacity smallint NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, port_id),
    CONSTRAINT ck_port_capacity CHECK (in_use >= 0 AND in_use <= max_capacity)
);

-- Take the channel: atomically +1 (it only succeeds if it is not full)
UPDATE device_port_usage
SET in_use = in_use + 1, updated_at = now()
WHERE tenant_id = ? AND port_id = ? AND in_use < max_capacity
RETURNING in_use;
-- a returned row = it was taken; no row returned = it is full

-- Release: -1
UPDATE device_port_usage
SET in_use = in_use - 1, updated_at = now()
WHERE tenant_id = ? AND port_id = ? AND in_use > 0;
```

**A comparison of the three cases**:

| capacity | Semantics | Which table |
|---|---|---|
| **1** | Mutual exclusion (one plate at a time) | `device_port_usage` (`in_use < 1` is equivalent to mutual exclusion) |
| **2** | Semaphore (two plates at the same time) | The same one |
| **N** | Semaphore | The same one |

**Use `device_port_usage` for everything** — capacity=1 is just the special case of `max_capacity=1`,
so there is no need for two sets of code.

**Note**: **the position lock (L1) is still a mutual exclusion** — a position can only hold one plate.
Only the **channel** supports counting.

### 2.2 L3: the gate (the resource I missed)

**Why the gate is a resource**:

```
The centrifuge door is closed
  → the robot arm reaches in → **it crashes**
  → OpenDoor has to be called first and confirmed open before anything can go in
```

**Opening and closing the gate is itself an action** (a few seconds), and **it occupies a resource during that time as well**.

```sql
CREATE TABLE device_access (
    id              bigserial PRIMARY KEY,
    tenant_id       bigint NOT NULL,
    resource_id     bigint NOT NULL REFERENCES resource_node(id),
    code            varchar(32) NOT NULL,     -- 'DOOR' / 'LID' / 'DRAWER'
    access_type     varchar(16) NOT NULL,     -- 'door' | 'lid' | 'drawer' | 'gate'
    state           varchar(16) NOT NULL DEFAULT 'unknown',
                                              -- closed|open|opening|closing|unknown
    open_method     varchar(64),              -- 'OpenDoor' (can be null = a manual gate)
    close_method    varchar(64),              -- 'CloseDoor'
    open_timeout_ms integer NOT NULL DEFAULT 30000,
    state_checked_at timestamptz,
    UNIQUE (tenant_id, resource_id, code)
);

COMMENT ON COLUMN device_access.state IS
    'The current open/closed state. unknown means it is not certain, it has to be queried or force-closed first';
COMMENT ON COLUMN device_access.open_method IS
    'The name of the method that opens the gate. NULL means it is a manual gate, a person has to open it and then confirm';
```

**The three cases of a gate**:

| Case | `open_method` | Handling |
|---|---|---|
| **Powered gate** | `'OpenDoor'` | The system calls the method to open it and waits for the state to become open |
| **Manual gate** | `NULL` | **It has to be opened by a person and confirmed** (the system cannot open it) |
| **Always open (no gate)** | No record | Nothing to handle |

**Where the state of the gate comes from: from the action, not from a query** (confirmed with the user)

**The device does not provide a "query the gate state" interface, and it does not need one**:

```
① The system assumes the gate is closed by default (the initial state is closed)
② The workflow contains an "open the gate" action (the method OpenDoor)
③ **A successful action return = the gate is open** → state = 'open'
④ The other way round, a successful "close the gate" action = the gate is closed → state = 'closed'
```

```java
// before entering the device
if (access != null && !"open".equals(access.state())) {
    if (access.openMethod() == null) {
        throw new NeedsHuman("手动门，请人工打开后确认");
    }
    Result r = callMethod(access.openMethod());
    if (!r.ok()) {
        throw new DeviceFault("开门动作失败：" + r.errorCode());
    }
    // **Do not query, trust the result of the action**
    access.setState("open");
    access.setStateCheckedAt(now());
}
```

(The two exception messages are the literals the backend prints: "手动门，请人工打开后确认" = "manual gate, please open it by hand and confirm", and "开门动作失败：" = "the open-gate action failed:".)

**Why this works**:

| Condition | Explanation |
|---|---|
| **Only the system opens and closes the gate** | Nobody touches it by hand (or if they do, they have to tell the system) |
| **The action has a clear success/failure** | The driver returning `method_status=2` means success |
| **The workflow guarantees the pair** | If it was opened it is closed again (the close is added in the `finally`) |

**The risks and how they are mitigated**:

| Risk | Mitigation |
|---|---|
| **A person closed the gate by hand and the system does not know** | A constraint is needed: a person who touches the gate has to tick "closed by hand" in the control console |
| **The action succeeds but the gate is in fact not open** (jammed mechanically) | It is exposed by the failure of a later action (not being able to reach in raises an error) |
| **The execution crashes after the gate was opened and the gate stays open** | On recovery **close first and then open** (idempotent) |

**The gate has to be opened and closed in pairs** (confirmed with the user)

```
If there is an open action → there must be a close action
```

**The implementation constraints**:

| Constraint | How |
|---|---|
| **In pairs** | In the code path that opens the gate, the `finally` must contain the close |
| **A failed close has to raise an error** | It must not be swallowed, it has to set `state='unknown'` and raise an alert (the gate may be half open) |
| **The exceptional path has to close as well** | A failed grab, a timeout, a pause — every path has to go through the close |

```java
boolean opened = false;
try {
    openDoor(access);  opened = true;
    // ... take and put the plate ...
} finally {
    if (opened) {
        Result r = closeDoor(access);
        if (!r.ok()) {
            access.setState("unknown");
            alert("门关闭失败：" + r.errorCode() + "，可能半开，需人工确认");
        }
    }
}
```

**The idempotent handling during recovery**:

```
After a restart/recovery every gate state is treated as unknown
  → the first time a device has to be operated, CloseDoor is called first (whatever the current state is)
  → success → state = 'closed'
  → then OpenDoor is called → state = 'open'
```

**Better to close one extra time than to assume the gate is open.**

### 2.3 L1 positions have to be linked to a channel

**Which channel a position is behind**:

```
The centrifuge has 4 layer positions and 1 gate
  → all 4 positions are behind that gate
  → to take the plate out of layer 2, the gate has to be opened first
```

**There is already `device_port.position_id`**, but the more accurate form is the **reverse link**:

```sql
ALTER TABLE position_state ADD COLUMN IF NOT EXISTS
    port_id bigint REFERENCES device_port(id);
-- the channel through which this position is reachable
```

---

## 3. The corrected order of resource acquisition

### 3.1 The complete sequence (including the gates)

```
[Source device side]
① The gate of the source device (open)      ← new
② The source channel (in/out/both)
③ The source position

[Target device side]
④ The gate of the target device (open)      ← new
⑤ The target channel
⑥ The target position

[Wrap-up]
⑦ The return position (position-return)
⑧ The robot arm
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   Everything is ready; the plate is still on the source position and can be safely abandoned
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⑨ Pick up the plate                 ← the point of no return
⑩ Move (at the safe height safe_z)
⑪ Put it down
⑫ Close the gates on both sides (optional, according to the configuration)
⑬ Release: the robot arm, the two channels
⑭ The return position is held
```

### 3.2 Why the gate comes first

| Order | Reason |
|---|---|
| **The gate comes before the channel** | The gate is the "main breaker", without it open you cannot get in |
| **The gate comes before the position** | Same as above |
| **The gate comes before the robot arm** | The gate is open but the robot arm could not be taken → the gate can be closed and the attempt abandoned, the cost is small |
| **If the gate came after the robot arm** | You only find out that the gate will not open after you have taken the robot arm → **the plate is already in the gripper** and there is no way to put it back |

**This is the extension of "the plate does not leave the source position until every request has succeeded"**: the gate is a "request" as well, so it has to be completed before the plate is picked up.

### 3.3 Opening and closing the gate is also an "action"

**Opening the gate takes 3–30 seconds** (for a powered gate), and during that time:

- the gate resource is held (nobody else can open the same gate at the same time)
- **the robot arm is not held** (while waiting for the gate you do not have to hold the gripper)

**So the gate and the robot arm are two independent locks and they can interleave**:

```
Workflow 1: open gate A (3 seconds) → take the robot arm → pick up the plate → ...
Workflow 2: open gate B (3 seconds, in parallel with workflow 1) → wait for the robot arm
```

---

## 4. Point attributes (question 4, the basis of dynamic planning)

### 4.1 What the existing `robot_point` is missing

```sql
-- only these exist today
(id, tenant_id, island_id, robot_resource_id, code, name,
 x, y, z, device_resource_id, action, synced_at, ...)
```

**What is missing**:

| What is missing | Consequence |
|---|---|
| It is unknown whether this point is an "in" or an "out" | The wrong point is picked during the grab |
| It is unknown which layer position it corresponds to | It is unknown which layer the plate goes into |
| It is unknown whether a gate has to be opened first | **It crashes** |
| The safe height is unknown | It hits the rack while moving sideways |
| The approach direction is unknown | Reaching in from the wrong direction crashes it |

### 4.2 The additional fields

```sql
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    point_type      varchar(16) DEFAULT 'generic',
    -- 'pick' grab point | 'place' put-down point | 'generic' general purpose
    -- 'dock' docking point | 'safe_z' safe height point

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    port_id         bigint REFERENCES device_port(id),
    -- the channel this point corresponds to

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    position_id     bigint REFERENCES position_state(id);
    -- the layer position this point corresponds to (essential for devices with several plate positions)

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    access_id       bigint REFERENCES device_access(id);
    -- which gate has to be opened before visiting this point (NULL = no gate)

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    approach_axis   varchar(8) DEFAULT 'front',
    -- the approach direction: front|left|right|top|bottom
    -- reaching in from the wrong direction crashes it

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    safe_z_offset   numeric(10,3) DEFAULT 50;
    -- the safe height offset: lift to z + this value before moving sideways

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    max_stack       smallint DEFAULT 1;
    -- how many plates can be stacked at this position at most

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    height_mm       numeric(8,2);
    -- the stack height of the plates at this point (used to calculate Z when stacking)

ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    order_in_device smallint;
    -- the order of several points on the same device (take the outer one first, then the inner one)
```

### 4.3 What each attribute is for

| Attribute | How the dynamic planning uses it |
|---|---|
| `point_type` | Only `pick`/`place` are selected, `safe_z`/`dock` are skipped |
| `port_id` | It knows which port to use when the channel lock is requested |
| `position_id` | **When a free position is chosen it only looks at the layer position this point corresponds to** |
| `access_id` | **It knows which gate has to be opened before the plate is picked up** |
| `approach_axis` | The feed direction when the robot arm path is generated |
| `safe_z_offset` | It is lifted to this height before it moves sideways |
| `max_stack` | When stacking, it decides whether another plate can be added |
| `height_mm` | When stacking, it calculates the Z offset |
| `order_in_device` | With several plate positions it decides which one to take first (the outer one first) |

### 4.4 How the attributes come about: the template fills them in automatically + a person fills in the missing ones + verification (confirmed with the user)

**The current state as described by the user**:

```
The robot arm has finished → the control console has a "sync action" → the points are synchronised into the control console system
```

**Three steps are added on top of that**:

| Step | How |
|---|---|
| **① The template is filled in automatically during the sync** | The device type template is matched by `device.model` and the default attributes are filled in |
| **② The missing ones are filled in by a person** | The attributes the template does not have are asked of the person |
| **③ They are verified at the next sync** | The template values are compared and an alert is raised when they differ |

**The device type template table**:

```sql
CREATE TABLE device_point_template (
    tenant_id     bigint NOT NULL,
    device_model  varchar(64) NOT NULL,      -- matched by device model
    point_type    varchar(16) DEFAULT 'generic',
    approach_axis varchar(8)  DEFAULT 'front',
    safe_z_offset numeric(10,3) DEFAULT 50,
    max_stack     smallint DEFAULT 1,
    height_mm     numeric(8,2),
    requires_door boolean DEFAULT false,
    port_direction varchar(8) DEFAULT 'both',
    notes         text,
    PRIMARY KEY (tenant_id, device_model)
);
```

**The flow during the sync**:

```
The robot arm synchronises the points (an existing capability)
  ↓
For every point:
  ① Look up device.model → look up the template
  ② If there is a template → fill in approach_axis / safe_z_offset / max_stack / height_mm
  ③ Look up device_access → if there is a gate, fill in access_id
  ④ Look up device_port → fill in port_id according to the direction
  ⑤ The missing fields (e.g. which layer position_id corresponds to) → **mark them as "to be filled in"**
  ↓
The frontend shows "3 points are still to be filled in" → a person fills them in
  ↓
Only once they are filled in can they be used for dynamic planning
```

**The verification mechanism**:

```java
// at the next sync
if (existing.safeZOffset() != template.safeZOffset()) {
    // somebody may have changed it by hand, or the template has been updated
    alert("点位 " + code + " 的 safe_z_offset 与模板不一致："
        + "现有 " + existing.safeZOffset() + " 模板 " + template.safeZOffset());
    // do not overwrite automatically, let a person confirm
}
```

**Why it is not overwritten automatically**: **a manual change may have a reason** (that device may be out of position), and an automatic overwrite would wipe out the correction a person made.

**The visibility of the fill-in status**:

```sql
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS
    fill_status varchar(16) DEFAULT 'ok';
-- 'ok' complete | 'pending' to be filled in | 'verified' verified
```

**During dynamic planning**: the points with `fill_status != 'ok'` are **not used** (they are skipped and the reason is stated in the log).

### 4.5 How the points are chosen: **hard-coded in the workflow with a loop + variables** (the main path), automatic position selection is an optional enhancement

**The design direction confirmed with the user**:

> **First use the hard-coded approach in the workflow** — for example, obtain a dynamic point through a **loop + variables**
> and then pass it to the robot arm. That way the robot arm can receive a dynamic position and handle it.
>
> **This is also why conditions, computation and the like are needed to modify variables**, so as to achieve better handling of the workflow and the points.

#### Why it is not "the scheduler picks the free position automatically"

| Automatic position selection | Hard-coded in the workflow |
|---|---|
| It needs the device to **provide the occupancy state in real time** | It does not depend on the device cooperating |
| The device may not support it | **It always works** |
| The scheduler becomes complex | It is thought through at orchestration time |

**The user's judgement**: "This requires the other devices to obtain our information as well, and sometimes that is not so easy to handle."

**So the main path is specified at the workflow level, not guessed by the scheduler.**

#### Implementation: the point code supports variables

**The workflow configuration** (loop node + variables):

```json
{
  "nodeType": "loop",
  "varName": "i",
  "from": 1,
  "to": 8,
  "body": [
    {
      "method": "Transfer",
      "params": {
        "source": "STACKER",
        "target": "ID1183-L${i}"      // ← the point code is assembled from a variable
      }
    }
  ]
}
```

**It is expanded into 8 steps at execution time**:

```
Transfer(source=STACKER, target=ID1183-L1)
Transfer(source=STACKER, target=ID1183-L2)
...
Transfer(source=STACKER, target=ID1183-L8)
```

**The `target` of every step is a point code**, and the robot arm looks up `robot_point` by code to get the coordinates.

#### This requires three things

| # | Requirement | Current state |
|---|---|---|
| 1 | **Variable substitution has to work on `target`/`source`** | ✅ Already there (`substVars`) |
| 2 | **A loop node has to be expandable into several steps with a different variable per step** | ✅ The canvas has a loop column |
| 3 | **The point code has to support being assembled from variables** (`ID1183-L${i}`) | ⚠ **To be confirmed**: substitute the variables before looking up `robot_point` |

**The third point is what M1 has to add**:

```java
// Substitute the variables before looking up the point
String targetCode = substVars(params.get("target"));   // ID1183-L3
RobotPoint p = robotPointByCode(targetCode);           // look up the coordinates
if (p == null) {
    throw new IllegalStateException("点位不存在：" + targetCode
        + "（可能是变量拼错了，或者点位没同步）");
}
```

#### How conditions and computation work together

**The user mentioned "conditions, computation and the like to modify variables"**:

```json
// Condition: only go into the incubator when there is a sample on the plate
{
  "nodeType": "exclusiveGateway",
  "condition": "${plateCount} > 0",
  "then": [...],
  "else": [...]
}

// Computation: the number of the next free position
{
  "nodeType": "compute",
  "set": { "nextSlot": "${usedSlots} + 1" }
}
```

**These are done at the workflow level**, not by the scheduler.

**The scheduler is only responsible for this**: give me `target=ID1183-L3` and I take L3, open the gate of L3 and carry it over.

#### Automatic position selection as an "optional enhancement" (not required)

**If the device really does provide the occupancy state**, it can be switched on:

```json
{
  "target": "ID1183",              // only the device, not the layer
  "autoPickSlot": true             // let the system pick the free position
}
```

**Only then does the system look up `position_state` to choose a free position.**

**Off by default** — because it depends on the device cooperating and is not necessarily reliable.

### 4.6 A concrete example

**The centrifuge has 4 layer positions and 1 gate**:

```sql
-- The gate
INSERT INTO device_access (resource_id, code, access_type, open_method, close_method)
VALUES (<centrifuge>, 'DOOR', 'door', 'OpenDoor', 'CloseDoor');

-- The 4 layer positions
INSERT INTO position_state (resource_id, code, ...) VALUES
  (<centrifuge>, 'L1', ...), (<centrifuge>, 'L2', ...),
  (<centrifuge>, 'L3', ...), (<centrifuge>, 'L4', ...);

-- The 4 points, all linked to the same gate
INSERT INTO robot_point
  (island_id, robot_resource_id, code, x, y, z,
   device_resource_id, point_type, access_id, position_id,
   approach_axis, safe_z_offset, order_in_device)
VALUES
  (..., 'CENT-L1', 320, 180, 45, <centrifuge>, 'pick', <gate>, <L1>, 'front', 80, 1),
  (..., 'CENT-L2', 320, 180, 95, <centrifuge>, 'pick', <gate>, <L2>, 'front', 80, 2),
  (..., 'CENT-L3', 320, 180, 145, <centrifuge>, 'pick', <gate>, <L3>, 'front', 80, 3),
  (..., 'CENT-L4', 320, 180, 195, <centrifuge>, 'pick', <gate>, <L4>, 'front', 80, 4);
```

**During dynamic planning**:

```
A plate has to be put into the centrifuge
  → look up the free position of the centrifuge: L2 is free
  → find the point of L2: CENT-L2
  → read access_id = the gate → request the gate (open)
  → read port_id / or use the point directly
  → request the channel
  → read safe_z_offset = 80 → the path first lifts to z=175
  → take the robot arm → move → put it down
```

**Everything is data-driven, no code has to be changed.**

---

## 5. The impact on the implementation specification

### 5.1 What M1 has to change

| Original M1 | Corrected M1 |
|---|---|
| Three resource layers | **Four resource layers** (the gate is added) |
| The entrance/exit is "1 per device" | **The entrance/exit is a table, 1~N of them, with a direction** |
| — | **The state machine of the gate is added** (closed/open/opening/closing/unknown) |
| — | **The point attributes are extended** (9 new fields) |

**M1 goes from 1 week → 1.5 weeks**.

### 5.2 The new acceptance criteria

| Scenario | Expectation |
|---|---|
| **Separate entrance/exit** | Workflow 1 goes in, workflow 2 goes out, **they do not block each other** |
| **Multiple in and out** | 4 plates go in and out at the same time |
| **A single port** | In and out **exclude each other** |
| **The gate is not open** | The operation is refused with the message "the gate is not open" |
| **A manual gate** | The message "it has to be opened by a person" is shown |
| **While the gate is moving** | Another workflow cannot operate the same gate |

---

## 6. The adjustment of the total schedule

| Milestone | Before | Now | Change |
|---|---|---|---|
| M1 three resource layers | 1 week | **1.5 weeks** | The gate + the channel table + the point attributes |
| M2 fine-grained control | 1 week | 1 week | — |
| M3 the main chain | 3 days | 3 days | — |
| M4 coarse screening | 1 week | 1 week | — |
| M5 starvation | 3 days | 3 days | — |
| **Total** | **4 weeks** | **4.5 weeks** | +0.5 weeks |

---

## 7. Three questions still have to be confirmed

| # | Question | Why it matters |
|---|---|---|
| 1 | **Can the state of the gate be queried?** Some devices can only "open", they cannot "query the state" | Then it has to be inferred from "the time of the last operation", which is risky |
| 2 | **Are channels that can pass several plates at the same time** (`capacity > 1`) **physically common?** | If they are common, the design of the lock has to support "counting" instead of "mutual exclusion" |
| 3 | **How is `access_id` known when the points are taught?** Filled in by hand or inferred automatically? | Filling in 9 attributes by hand is too tiring, can they be brought in automatically from the device type template |

---

## 8. In one sentence

**Originally it was assumed that "the entrance/exit = 1 per device", in reality it is "1~N of them, with a direction";**
**the "gate" was missed completely — if it is not open, the robot arm crashes when it reaches in;**
**a point is not only coordinates, it also needs "which port to go in through, which gate to open, from which direction, how high to lift".**

**Without these three, dynamic planning is just guessing.**
