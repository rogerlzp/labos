# Variable Passing and Point Initialisation — Design

> **English edition.** The Chinese original is [`docs/变量传递与点位初始化-设计.md`](../变量传递与点位初始化-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> It answers two questions:
> ① When a node changes a variable, how does the downstream use it? Could another experiment change it wrongly?
> ② How are plate positions and robot points initialised? How is a device with several plate positions specified?

---

## 1. Variables: three-layer isolation

### 1.1 The three-layer structure

```
Experiment definition layer   experiment_variable     variable name + default value + type
                 ↓
Execution instance layer   run_variable            the variable snapshot of this execution
                 ↓
Node parameter layer   workflow_node.params    of the form {"plate": "${plateId}"}
```

| Layer | Table | Purpose | Who writes it |
|---|---|---|---|
| **Definition** | `experiment_variable` | Which variables this experiment has, and their default values | Configured by hand at orchestration time |
| **Instance** | `run_variable` | The actual values of this execution | Written during execution |
| **Reference** | `workflow_node.params` | The node writes `${name}` | Configured at orchestration time |

### 1.2 Can a variable be changed wrongly by another experiment

**No — as long as the right path is used.**

**Key point: `run_variable` carries `run_id`.**

```sql
CREATE TABLE run_variable (
  tenant_id  bigint,
  run_id     bigint,      -- ★ isolated per execution
  name       varchar,
  value      text,
  updated_at timestamptz
);
```

**Every `run` has its own independent copy of the variables**, so the execution of experiment A cannot touch the execution of experiment B.

**But there is one real trap**:

| Scenario | Does it cross over? |
|---|---|
| The same experiment runs twice | ❌ No (two runs, two sets of variables) |
| Two different experiments run in parallel | ❌ No (different runs) |
| **The default value in `experiment_variable` is changed while an execution is running** | ✅ **Yes** |

**Why**: `ExperimentExecutor` reads the default values **once at start-up** and does not read them again.
If someone changes the default value half-way through an execution, **this execution is not affected** (so it is actually safe).

**What really needs attention**: `WorkflowService.withVars()` is a **process-wide shared Map**.

```java
public WorkflowService withVars(Map<String, String> vars) {
    this.varOverrides = vars;   // a field, not a ThreadLocal
    return this;
}
```

**Under concurrent execution**, thread A calls `withVars` and thread B calls it too — **the latter overwrites the former**.

**This is the real risk in the current code**.

### 1.3 The fix: variable scope

**Option A: ThreadLocal** (a small change)

```java
private final ThreadLocal<Map<String, String>> varOverrides = new ThreadLocal<>();
```

**Advantage**: thread isolation, so it cannot cross over naturally.
**Disadvantage**: asynchronous tasks (the scheduler's background thread) have to pass it manually.

**Option B: explicit parameters** (the cleanest)

```java
Execution execute(long workflowId, Map<String, String> vars);
```

**Advantage**: no implicit state, it is visible.
**Disadvantage**: every call site has to be changed.

**Option B is recommended** — this project has already been bitten by "implicit shared state" (two sets of IDs, two sets of plate positions).

### 1.3.1 The fix that was adopted: ThreadLocal (2026-11-02)

`WorkflowService` was changed:

```java
private final ThreadLocal<Map<String, String>> runtimeVars =
        ThreadLocal.withInitial(Map::of);
private final ThreadLocal<Long> islandFilter = new ThreadLocal<>();
```

**Why ThreadLocal instead of explicit parameters**:

- `substVars` is called deep inside `loadMethodParams`, so changing the signature would touch 4–5 methods
- `withIsland` has the same shared-field problem, so changing both at once is less work

**Cost**: when a thread pool reuses a thread, `clearVars()` must be called, otherwise the variables linger.

**A finally block was added in two places**:

| Location | Explanation |
|---|---|
| `ApiServer.workflowRunJson` | It used to call `withVars(Map.of())` in the finally block; it now calls `clearVars()` |
| `ExperimentExecutor.run` | It had no finally block; one has been added |

**Verification**: 3 concurrent runs, each with a different `vars=plate=PLATE-n`, all succeeded.

**Note**: `withIsland` was also changed to a ThreadLocal — it used to be an ordinary field,
so under concurrency A could set island 1 and B could set island 2, and A might pick devices on B's island.

### 1.4 How a node "changes" a variable

**The current mechanism**: a node can only **produce** variables (extracted from the device response); it cannot modify an existing variable.

```
The device returns {"data": {"plate": "PL-001"}}
       ↓ the node is configured with {"output": "plateId"}
run_variable gains a new row name=plateId, value=PL-001
       ↓ the next node references ${plateId}
```

**The extraction rule** (in `ExperimentFlowRunner`):

```java
String field = ...;  // the output configured on the node
Object v = data.get(field);   // take the field of the same name out of data
runVariable.put(field, String.valueOf(v));
```

**If a node has to "change" an existing variable** (a counter +1, say):

**Not supported today** — expressions like `${count}+1` were **deliberately not built**.

**Why not**:

| Approach | Cost |
|---|---|
| Support expression evaluation | You have to write a parser, or bring in a script engine (a security risk) |
| Support only `${var}` | Good enough, and it cannot compute the wrong thing |

**If a counter really is needed**: use the `loop_index` of the loop node (it carries the ordinal automatically at execution time), do not increment a variable.

### 1.5 When a variable cannot be found

**Rule: keep `${name}` as it is, do not replace it with an empty string.**

```java
// substVars
return matcher.replaceAll(m -> vars.getOrDefault(m.group(1), "${" + m.group(1) + "}"));
```

**Advantages**:

- In the log you see `plate=${plate}` and know at a glance that "this variable was not passed"
- If it were replaced with an empty string, the log would read `plate=`, and you could not tell a missed value from a genuinely empty one

**Cost**: the driver raises an error when it receives a parameter containing `${}` — but **that is exactly what is wanted**:
**better to fail than to silently pass an empty value**.

---

## 2. Points: initialisation + single and multiple plate positions

### 2.1 Why there is a problem with points

The robot arm's plate-moving action is `Transfer(source, target)`.

- `source` / `target` are **point codes**, not device numbers
- point code → coordinates (x/y/z) → only then does the robot arm know where to go

**If the points are not initialised**, the robot arm does not know where to move.

### 2.2 The three plate-position situations

| Case | Example | Does the workflow have to specify it? |
|---|---|---|
| **The device has only one plate position** | Single-station incubator | **No** — that point is used automatically |
| **The device has several plate positions** | 8-layer layer rack | **Yes** — otherwise it is unknown which layer to put it in |
| **The plate position is outside the device** | Plate stacker | Yes |

### 2.3 The point model

```sql
robot_point (
  island_id,            -- which island the point belongs to
  robot_resource_id,    -- which robot arm
  code,                 -- the point code; this is what Transfer uses
  x, y, z,              -- coordinates
  device_resource_id,   -- which device this point belongs to ★
  action                -- action type (pick/place/home...)
)
```

**The key field `device_resource_id`**: it fixes the correspondence between a point and a device.

**This is what lets the robot arm "fetch it automatically"**:

```
The workflow says Transfer(source=ID1775, target=ID1183)
        ↓ look up robot_point
source point → the coordinates of the device's only layer position
target point → the coordinates of the device's only layer position
        ↓
Issue the Transfer with the coordinates
```

### 2.4 The initialisation flow (mandatory after an island is created)

```
① Create the island, drag the devices in
        ↓
② Synchronise the points (POST /api/islands/{id}/robot-points/sync?robotResourceId=)
   —— generate the points automatically from the devices on the island, code = device code
        ↓
③ Teach / read the coordinates
   —— real device: POST .../robot-points/{code}/capture reads from the robot arm
   —— simulation: fill in by hand or import from CSV
        ↓
④ Validate: does every device have points
        ↓
⑤ Only then can the workflow run
```

**The rule used by step ②**:

```sql
-- one point per device, code = device code
INSERT INTO robot_point (island_id, robot_resource_id, code, device_resource_id)
SELECT i.id, <robotId>, d.code, d.id
FROM resource_node d
WHERE d.parent_id = <islandId> AND d.kind = 'device'
ON CONFLICT (island_id, robot_resource_id, code) DO UPDATE
SET device_resource_id = EXCLUDED.device_resource_id;
```

**Note**: `sync` only creates "device-level" points (one per device).

**What about several plate positions**:

**The approach: two levels, device-level points + layer-level points**

```
ID1183          ← device level (the first free layer position is used by default)
ID1183-L1       ← layer 1
ID1183-L2       ← layer 2
...
```

**In the workflow**:

- do not write `target` → use the device-level point (**the first free layer position is chosen automatically**)
- write `target=ID1183-L2` → use the specified layer

**This is the implementation of "a single plate position needs no specification, several plate positions do".**

### 2.5 The logic for choosing a layer position automatically

```java
// When the Transfer target only carries a device code
if (!targetHasLayer) {
    // Look up the first free layer position of that device
    Long posId = firstAvailablePosition(deviceResourceId);
    target = "deviceCode-L" + layerNoOf(posId);
}
```

**It depends on `position_state`** (which layer position is free).

**This is why plate tracking has to be accurate** — if the wrong point is picked, the robot arm will put the plate into a layer position that already holds one.

### 2.6 Synchronising with the real device

**`POST .../robot-points/{code}/capture`** already reads the coordinates from the robot arm:

```json
// returned by the robot arm
{"data": {"x": 320.5, "y": 180.2, "z": 45.0}}
```

**`QueryService.extractXYZ`** matches `x`/`pos_x`/`X` loosely (the field names differ between vendors).

**The synchronisation strategy**:

| Scenario | What to do |
|---|---|
| First deployment | Teach point by point + capture |
| A device is nudged slightly | Re-capture that single point |
| Large batches | CSV import (`POST .../robot-points.csv`) |

**The CSV import uses `COALESCE`**: only the fields that carry a value are updated, existing coordinates are not cleared.

### 2.7 Validation: it has to pass before a workflow runs

**Add a "point completeness check"**:

```sql
-- devices that have no point
SELECT d.code FROM resource_node d
WHERE d.parent_id = <islandId> AND d.kind = 'device'
  AND NOT EXISTS (
    SELECT 1 FROM robot_point p
    WHERE p.island_id = <islandId> AND p.device_resource_id = d.id);
```

**If anything is missing**, `Transfer` will fail — **it is best to stop it before the workflow runs**.

---

## 3. Implementation order

| # | Task | Depends on |
|---|---|---|
| 1 | ✅ Island lock (done) | — |
| 2 | **Change the variables to explicit parameters** | Nothing |
| 3 | **Points: two levels, device level + layer level** | Nothing |
| 4 | **Automatic layer selection** | Item 3 + `position_state` |
| 5 | **Point completeness check** | Item 3 |

**Item 2 is the most urgent** — the shared Map of `withVars` crosses over under concurrency,
and concurrency has already been verified as workable (16 concurrent runs, zero failures), so **this trap can be stepped on at any time**.

---

## 4. In one sentence

**Variables**: three layers (definition / instance / reference), `run_variable` is isolated by `run_id`;
**the only risk is the process-wide shared Map of `withVars`**, which has to become explicit parameters or a ThreadLocal.

**Points**: `robot_point.device_resource_id` binds a point to a device;
**a single plate position needs no specification (the only layer position is used automatically), several plate positions are specified with `deviceCode-L<n>`**;
after an island is created, `sync` first and then `capture`, before a workflow can run.
