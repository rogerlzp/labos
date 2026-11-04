# Position Numbering Standard

> **English edition.** The Chinese original is [`docs/点位编号规范.md`](../点位编号规范.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **This is the interface agreement between the control console and the robot arm.**
> The control console computes a position code with variables / formulas → passes it to the
> robot arm → the robot arm looks up the coordinates by code and executes.
> **The code has to be ordered, computable and unique.**

---

## 1. The numbering format

### 1.1 Five forms

| Form | Format | Example | Used for |
|---|---|---|---|
| **Multi plate position** | `{device}-L{number}` | `ID1183-L3` | Incubators, centrifuges, plate readers |
| **Multiple inlets** | `{device}-IN{number}` | `ID1775-IN2` | Multi-channel feeding devices |
| **Multiple outlets** | `{device}-OUT{number}` | `STACKER-OUT1` | Plate stackers, outfeed towers |
| **Single port** | `{device}-BOTH` | `ID1731-BOTH` | Single-port devices |
| **Stack position** | `{device}-S{number}` | `STACKER-S5` | Stackable towers / magazines |
| **Docking point** | `{device}-DOCK` | `ID1731-DOCK` | The robot arm's standby position |
| **Safe height** | `{device}-SAFEZ` | `ID1183-SAFEZ` | The lift point for that device |

### 1.2 The composition rule

```
{device code}-{type}{number}
    ↑             ↑     ↑
  resource_node.code   L/IN/OUT/BOTH/S   1..N
```

**All three parts must be**:
- **Device code**: `resource_node.code` (e.g. `ID1183`), globally unique
- **Type**: a single upper-case letter or a short word (`L`/`IN`/`OUT`/`S`/`BOTH`/`DOCK`/`SAFEZ`)
- **Number**: **a positive integer starting at 1** (not 0, to avoid the ambiguity of "the 0th one")

**Not allowed**:
- Spaces, underscores, lower case (apart from the device code itself)
- The number starting at 0
- Skipping numbers (`L1, L3` but no `L2`) — unless a level is physically missing

---

## 2. Numbering vs access order: **two concepts, do not mix them up**

### 2.1 First separate these two things

| Concept | Field | Meaning | Who decides it |
|---|---|---|---|
| **Position code** | `robot_point.code` | **Identifies a physical position** (`ID1183-L3`) | Generated during authoring / synchronisation |
| **Access order** | `robot_point.order_in_device` | **Which one the robot arm takes first** (1 = first) | **Decided by on-site teaching** |

**The user was explicit**:

> "L1 cannot be decided now, the positions differ, it has to be decided on site;
> when we write this system, it has to support many on-site situations."

**So**: **the system does not assume that `L1` is the bottom or the outer level**.
The actual order of each device is **configured in `order_in_device` at teaching time**.

### 2.2 What happens when the two disagree

**No collision, but the plate-picking efficiency differs**:

```
Numbering: L1 L2 L3 L4 (numbered from the bottom up)
The robot arm in reality: takes the topmost one first (L4)

If order_in_device is configured as L1=1, L2=2, L3=3, L4=4
  → the robot arm tries to "take the bottom one first"
  → but the bottom one is blocked by the ones above, so it cannot be taken out, or the travel is very long

If order_in_device is configured as L4=1, L3=2, L2=3, L1=4
  → the robot arm takes the topmost one first, correct
```

**Key**:
- **As long as the numbering is unique and has coordinates, it cannot "collide"**
- **The order only affects "whether it can be taken" and "the efficiency"**

### 2.3 When the order affects correctness

| Scenario | Does the order matter |
|---|---|
| **The workflow names a specific code** (`ID1183-L3`) | ❌ No (it goes straight to L3) |
| **The system picks an empty position by itself** (`autoPickSlot`) | ✅ **It must follow the order** (otherwise it picks a blocked level) |
| **Picking plates in bulk** (looping over them) | ✅ **It must follow the order** (otherwise they cannot be taken out) |

**So**:
- **`order_in_device` is optional when the workflow names the code**
- **it is mandatory when a position is picked automatically / in bulk**

**But for uniformity**: **fill it in for every position**, and mark the missing ones with
`fill_status='pending'`.

### 2.4 Why the order cannot be inferred from the "format"

**A scheme that was considered**: stipulating that `L1` must be the bottom level.

**Why it was dropped**:

| Problem | Explanation |
|---|---|
| **The devices differ a lot** | An incubator may be a vertical rack (from below), a plate reader is a horizontal tray (from the outside) |
| **The same model may differ too** | Some plants installed it the other way round, some changed the structure |
| **Only the site knows** | Only during teaching do you find out how the robot arm actually picks |

**The user's attitude**: "it has to support many on-site situations" — **no assumptions,
everything configurable**.

### 2.5 A configuration example

```sql
-- Centrifuge: 4 levels, from the bottom up
UPDATE robot_point SET order_in_device = 1 WHERE code = 'CENT-01-L1';
UPDATE robot_point SET order_in_device = 2 WHERE code = 'CENT-01-L2';
UPDATE robot_point SET order_in_device = 3 WHERE code = 'CENT-01-L3';
UPDATE robot_point SET order_in_device = 4 WHERE code = 'CENT-01-L4';

-- A device installed the other way round: numbered from the bottom up, but the robot arm picks from the top down
UPDATE robot_point SET order_in_device = 4 WHERE code = 'ODD-01-L1';
-- ... L2=3, L3=2, L4=1
```

**The order has nothing to do with the numbering, only with "how the robot arm actually
moves".**

---

## 3. How the workflow computes positions## 3. How the workflow computes positions

### 3.1 A loop: filling 8 levels in bulk

```json
{
  "nodeType": "loop",
  "varName": "i",
  "from": 1, "to": 8,
  "body": [{
    "method": "Transfer",
    "params": {
      "source": "STACKER-OUT1",
      "target": "ID1183-L${i}"
    }
  }]
}
```

**Expanded**:

```
Transfer(source=STACKER-OUT1, target=ID1183-L1)
Transfer(source=STACKER-OUT1, target=ID1183-L2)
...
Transfer(source=STACKER-OUT1, target=ID1183-L8)
```

### 3.2 Computation: working out the next empty position

```json
{
  "nodeType": "compute",
  "set": {
    "nextSlot": "ID1183-L${usedSlots + 1}"
  }
}
```

**Note**: `${usedSlots + 1}` is an **expression**, and the result is spliced into the code.

### 3.3 Conditions: choosing a different device depending on the case

```json
{
  "nodeType": "exclusiveGateway",
  "condition": "${plateCount} > 4",
  "then": [{ "method": "Transfer", "params": { "target": "STACKER-S${plateCount}" } }],
  "else": [{ "method": "Transfer", "params": { "target": "STACKER-S1" } }]
}
```

### 3.4 What the expression engine has to support

**What can be computed in a workflow**:

| Operation | Example |
|---|---|
| Arithmetic | `${a} + 1`, `${b} * 2` |
| Rounding | `floor(${x} / 8) + 1` |
| Conditions | `${a} > ${b} ? 1 : 2` |
| String concatenation | `ID1183-L${i}` |
| Modulo | `${i} % 8` (for loops) |

**What is not supported** (deliberately):
- Complex function calls (`substring`, `split`)
- Modifying variables inside a loop (read-only)

**The reason**: the more powerful the expression, the easier it is to write something
wrong, and the harder it is to debug. **Good enough is enough**.

---

## 4. Validation: a wrong code has to be reported early

### 4.1 At authoring time (saving the workflow)

```
Check the target/source of every Transfer:
  - if it is a literal code (contains no ${}) → check whether robot_point exists
  - if it contains ${} → only check the format (whether a valid code can be spliced out of it)
```

**A form without variables can be checked; a form with variables cannot** (the value is only
known at execution time).

### 4.2 At execution time (before looking up the position)

```java
String code = substVars(target);          // ID1183-L3
RobotPoint p = robotPointByCode(code);
if (p == null) {
    throw new IllegalStateException(
        "点位不存在：" + code
        + "（可能：变量算错、编号没同步、或设备编号拼错）");
}
if (!"ok".equals(p.fillStatus())) {
    throw new IllegalStateException(
        "点位 " + code + " 的属性不完整（" + p.fillStatus() + "），需人工补填");
}
```

### 4.3 Periodic verification

**Run a check every week / after every synchronisation**:

```sql
-- A device that has no positions
SELECT d.code FROM resource_node d
WHERE d.parent_id = <islandId> AND d.kind = 'device'
  AND NOT EXISTS (
    SELECT 1 FROM robot_point p
    WHERE p.island_id = <islandId> AND p.device_resource_id = d.id);
```

```sql
-- Gaps in the position numbers (e.g. L1 and L3 but no L2)
SELECT device_resource_id, string_agg(code, ',' ORDER BY code)
FROM robot_point
WHERE code ~ '-L[0-9]+$'
GROUP BY device_resource_id
HAVING count(*) <> max(
    regexp_replace(code, '.*-L', '')::int
);
```

---

## 5. The "why" behind the numbering rules

### Why "device code + type + number" and not an independent serial number

| Option | Advantage | Disadvantage |
|---|---|---|
| **An independent serial number** (P001, P002) | Simple | **You cannot tell which device it belongs to**, which makes manual debugging hard |
| **Device code + type + number** | You can see at a glance which device it is and which level | The code is a bit longer |

**The second one is chosen**: **readability > brevity**, because when you are debugging a
problem a human is reading it.

### Why the number starts at 1

```
L0 or L1 — which is the first?
  → some people count from 0, some from 1
  → **Starting at 1 matches the natural language of "which level"**
```

### Why gaps are not supported

**Physically a gap may really exist** (e.g. a level is broken).

**But gaps make "computing the number with a formula" more complicated**:

```java
// With a gap, i=3 may correspond to L4
// A mapping table has to be maintained
```

**The recommendation**: a level with a gap **keeps its code anyway**, it is only marked
`fill_status='disabled'`,
so the formula does not change and the scheduler skips it.

---

## 6. A complete example: a centrifuge (4 levels + 1 door)

### 6.1 Configuration

```sql
-- Device: CENT-01, 4 levels, 1 door
-- The door
INSERT INTO device_access (resource_id, code, access_type, open_method, close_method)
VALUES (<CENT-01>, 'DOOR', 'door', 'OpenDoor', 'CloseDoor');

-- 4 levels
INSERT INTO position_state (resource_id, code, ...) VALUES
  (<CENT-01>, 'L1', ...), (<CENT-01>, 'L2', ...),
  (<CENT-01>, 'L3', ...), (<CENT-01>, 'L4', ...);

-- 4 positions (L1 is the outermost, next to the door)
INSERT INTO robot_point
  (island_id, robot_resource_id, code, x, y, z,
   device_resource_id, point_type, access_id, position_id,
   approach_axis, safe_z_offset, order_in_device, fill_status)
VALUES
  (<island>, <robot arm>, 'CENT-01-L1', 320, 140, 45, <CENT-01>, 'place', <door>, <L1>, 'front', 80, 1, 'ok'),
  (<island>, <robot arm>, 'CENT-01-L2', 320, 180, 45, <CENT-01>, 'place', <door>, <L2>, 'front', 80, 2, 'ok'),
  (<island>, <robot arm>, 'CENT-01-L3', 320, 220, 45, <CENT-01>, 'place', <door>, <L3>, 'front', 80, 3, 'ok'),
  (<island>, <robot arm>, 'CENT-01-L4', 320, 260, 45, <CENT-01>, 'place', <door>, <L4>, 'front', 80, 4, 'ok');
```

### 6.2 The workflow

```json
{
  "nodeType": "loop",
  "varName": "i", "from": 1, "to": 4,
  "body": [
    { "method": "Transfer", "params": {
        "source": "ID1775-L${i}",
        "target": "CENT-01-L${i}" } }
  ]
}
```

### 6.3 At execution time (i=2)

```
① substVars → source='ID1775-L2', target='CENT-01-L2'
② Look up the positions:
   ID1775-L2: access=ID1775 door, safe_z=80, order=2
   CENT-01-L2: access=CENT-01 door, safe_z=80, order=2
③ Request the resources (a fixed total order):
   door(ID1775) → channel(ID1775) → position(ID1775-L2)
   → door(CENT-01) → channel(CENT-01) → position(CENT-01-L2)
   → return position → robot arm
④ Open the doors on both sides
⑤ Pick up the plate → lift to safe_z → traverse → descend → put down
⑥ Close the doors on both sides
⑦ Release the channel and the robot arm (the return position is kept)
```

**The whole thing is driven by the code; not one coordinate is hard-coded.**

---

## 7. Open questions

| # | Question | Impact | Conclusion |
|---|---|---|---|
| 1 | Is `L1` the bottom level or the outermost one | The plate-picking order | **The site decides `order_in_device`, the system assumes nothing** (decided) |
| 2 | For a stacking device, is `S1` the bottom or the top | Same as above | Same |
| 3 | Special characters in the device code (`ID1731-2` has a dash) | Parsing ambiguity | Look for `-L`/`-IN`/`-OUT`/`-S` from right to left |
| 4 | The upper limit on the number (8 levels? 32 levels?) | The base for the expression `%` | Decided on site, the system does not limit it |

**Watch out for point 3**: `ID1731-2-L1` parses correctly when split on `-L`,
but if `-L` appears inside the device code (e.g. `DEV-L1`), it becomes ambiguous.

**The recommendation**: when parsing, **look from right to left** for the first
`-L`/`-IN`/`-OUT`/`-S`, not from the start.
