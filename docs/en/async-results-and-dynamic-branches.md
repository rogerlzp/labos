# Asynchronous Result Queries and Dynamic Branches — Design

> **English edition.** The Chinese original is [`docs/异步结果查询与动态分支-设计.md`](../异步结果查询与动态分支-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **Scenario**: a result is produced asynchronously by some device further down the chain
> (reading a plate takes 10 minutes); when it is needed, it is queried, processed, and
> then it decides which branch to take.
>
> **The key judgement**: **this does not need a script sandbox**. What is needed is
> "result staging + variable injection + a query node".

---

## 1. First, distinguish three kinds of "query"

| What is queried | Example | What is needed |
|---|---|---|
| **① Query the asynchronous results of this system** | "Has the plate-reading result come out?" | **A result table + variable injection** (this design) |
| **② Query the real-time state of this system** | "Which shelf level is free?" | A restricted query API (`ctx.freeSlots` already exists) |
| **③ Query an external system** | "The information about this sample in LIMS" | **A script + a whitelist** (only this needs a sandbox) |

**The scenario the user raised is ①** — the result has **already been produced by this
system**, it is just **asynchronous**.

**No code upload, no sandbox.**

---

## 2. Breaking down the scenario

### 2.1 A typical workflow

```
Node 1: send the plate to the plate reader
Node 2: read the plate (asynchronous, the result comes out in 5-10 minutes)
Node 3: do something else at the same time (for example, centrifuge another batch)
Node 4: ← here we have to "decide which branch to take based on the plate-reading result"
        plate-reading result OD > 0.5 → take the "positive" branch (continue with sequencing)
        OD <= 0.5 → take the "negative" branch (archive)
```

**The key point**: when node 4 executes, the result of node 2 **may have just arrived, or
may not have arrived yet**.

### 2.2 Three problems that have to be solved

| # | Problem | Solution |
|---|---|---|
| 1 | **Where the result is stored** | The `run_result` table |
| 2 | **How it is referenced** | Injecting the variable `${results.xxx}` |
| 3 | **What if it has not arrived yet** | Waiting + timeout + degradation strategy |

---

## 3. Storing the result

### 3.1 The table structure

```sql
CREATE TABLE run_result (
    id          bigserial PRIMARY KEY,
    tenant_id   bigint NOT NULL,
    run_id      bigint NOT NULL REFERENCES run(id),
    node_id     bigint,                    -- which node produced it
    result_key  varchar(64) NOT NULL,      -- 'readPlate'
    payload     jsonb NOT NULL,            -- {"od": 0.65, "plate": "PL-001"}
    source      varchar(16) NOT NULL,      -- 'device' | 'manual' | 'external'
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, run_id, result_key)
);

CREATE INDEX idx_run_result_lookup ON run_result (tenant_id, run_id, result_key);
```

**`result_key` is unique** — within one run there is only one copy of the same key (a
later write overwrites it, or it is an error; see below).

### 3.2 Where the result comes from

| Source | How it is produced | Example |
|---|---|---|
| **`device`** | The device command's response contains `data` | `{"data": {"od": 0.65}}` |
| **`manual`** | A human fills it in on the interface | The result of a microscope observation |
| **`external`** | An external system pushes it (webhook) | The LIMS callback |

**`device` is the main path**:

```json
// The node is configured with {"output": "readPlate"}
// The device returns {"data": {"od": 0.65, "plate": "PL-001"}}
// → automatically write run_result(result_key='readPlate', payload={...})
```

**There is already a prototype of this in the current implementation** (the `output`
variable); it is just not stored in a table of its own.

### 3.3 What happens when it is produced twice

**The same key appears twice**:

| Strategy | Applicable when |
|---|---|
| **Error** (the default) | A normal workflow should not produce it twice |
| **Overwrite** | The retry scenario (the second result is more trustworthy) |
| **Keep the first** | A one-off result (such as "the first plate reading") |

**It is configured on the node**:

```json
{ "output": "readPlate", "onDuplicate": "error" | "overwrite" | "keep_first" }
```

**`error` is the default** — silently overwriting lets the problem hide.

---

## 4. Variable injection: how to reference it in an expression

### 4.1 The injection rule

**Before every node executes**, inject all the **completed results of this run** into the
variables:

```java
Map<String, Object> vars = new HashMap<>(runVars);

for (RunResult r : resultsOf(runId)) {
    // expand into results.<key>.<field>
    Map<String, Object> flat = new HashMap<>();
    flatten(r.resultKey(), r.payload(), flat);   // recursively expand the nesting
    flat.forEach((k, v) -> vars.put("results." + k, v));
}
```

**After expansion the variables look like this**:

```
results.readPlate.od       = 0.65
results.readPlate.plate    = "PL-001"
results.qc.concentration   = 12.5
```

### 4.2 How to use it in an expression

```json
{
  "nodeType": "exclusiveGateway",
  "condition": "${results.readPlate.od > 0.5}",
  "then": [...],   // the positive branch
  "else": [...]    // the negative branch
}
```

**Or a more complex condition**:

```json
{
  "condition": "${results.readPlate.od > 0.5 && results.readPlate.plate != null}"
}
```

### 4.3 What the variables look like when the result has not arrived yet

**A result that has not arrived has no variable** → the expression evaluation reports
"variable not defined".

**That is not good enough** — "has not arrived" has to be distinguished from "the value
is null".

**The solution: inject an "existence flag"**:

```
results.readPlate.od          = 0.65
results.readPlate.__exists    = true      ← the result exists
results.qc.__exists           = false     ← the result has not arrived yet
```

**The expression can test it**:

```json
{ "condition": "${results.readPlate.__exists && results.readPlate.od > 0.5}" }
```

**Better still, use a function**:

```json
{ "condition": "${has('readPlate') && results.readPlate.od > 0.5}" }
```

**`has()` is a built-in function of the expression engine** (it only reads the variable
table, so it is not an excess of privilege).

---

## 5. The result has not arrived yet: the waiting strategy

### 5.1 Three strategies

| Strategy | Behaviour | Applicable when |
|---|---|---|
| **`wait`** | Poll until it appears or the timeout expires | The default; the result will definitely come |
| **`skip`** | Do not wait, continue as if there were no result | The result is optional |
| **`pause`** | Pause and wait for a human to confirm | The result matters and must not be guessed |

### 5.2 The configuration

**It is configured on the gateway node**:

```json
{
  "nodeType": "exclusiveGateway",
  "waitFor": {
    "results": ["readPlate"],
    "timeoutMs": 600000,          // wait at most 10 minutes
    "pollMs": 2000,               // query every 2 seconds
    "onTimeout": "pause"          // fail | skip | pause
  },
  "condition": "${results.readPlate.od > 0.5}",
  "then": [...],
  "else": [...]
}
```

### 5.3 The resources during the wait

**The key point**: **all the occupied resources have to be released while waiting**.

```
Node 4 has to wait for the plate-reading result
  → release: the robot arm, the channels, the positions (if any)
  → keep only: the device lock (if the plate is still inside the device)
  → state: run.status = 'waiting_result'
```

**Why**: during the 10-minute wait, the other workflows have to be able to use the robot
arm.

### 5.4 The observability of the wait

**The interface shows**:

```
Run #205123  waiting for a result
  waiting for: readPlate (already waited 3 min 20 s, timeout 10 minutes)
  expected: reading a plate usually takes 8 minutes
```

**The estimate comes from `duration_model`** — it lets the person on duty know "how much
longer do I have to wait".

### 5.5 What happens when it times out

| `onTimeout` | Behaviour | Reminder |
|---|---|---|
| **`fail`** | The execution fails | Simple, but it needs a manual re-run |
| **`skip`** | Continue as if there were "no result" | It may take the wrong branch |
| **`pause`** ⭐ | Pause and wait for a human | **The recommended default** (when the result matters) |

**The implementation of `pause`**: `status='waiting_human'`, and an alert "timed out
waiting for the result".

---

## 6. Dynamic branches: the typical uses of a result

### 6.1 Scenario: the plate-reading result decides what follows

```json
{
  "nodeType": "exclusiveGateway",
  "name": "OD decision",
  "waitFor": { "results": ["readPlate"], "timeoutMs": 600000, "onTimeout": "pause" },
  "condition": "${results.readPlate.od > 0.5}",
  "then": [
    { "name": "Positive: continue with library construction" },
    { "nodeType": "script", "scriptId": "assign-barcode" }
  ],
  "else": [
    { "name": "Negative: archive for storage" },
    { "method": "Transfer", "params": { "target": "ARCHIVE-S${plateCount + 1}" } }
  ]
}
```

### 6.2 Scenario: a combined judgement over three results

```json
{
  "condition": "${results.qc.concentration > 10 && results.qc.purity > 1.8}"
}
```

**Take the "qualified" branch; otherwise take the "re-extract" branch**.

### 6.3 Scenario: a result-driven position selection

**This is the "dynamically update the workflow" the user raised**:

```json
{
  "nodeType": "compute",
  "waitFor": { "results": ["readPlate"] },
  "set": {
    // choose a different storage temperature zone depending on the OD value
    "storageSlot": "${results.readPlate.od > 0.5 ? 'COLD-' : 'ROOM-'}${plateId}"
  }
}
```

**Result → variable → position**, the whole chain is connected.

---

## 7. The relationship to script nodes (important)

### 7.1 Most scenarios do not need a script

| Requirement | What to use |
|---|---|
| Waiting for a result and branching on the value | **`waitFor` + an expression** |
| A combined judgement over several results | **An expression** |
| A result that drives the position | **`compute` + an expression** |
| **The result is in an external system** | **A script** (a whitelisted lookup on its behalf) |
| **The result needs a complex transformation** (for example several levels of nested JSON) | An expression is not enough → a script |

### 7.2 The decision criterion

```java
// Can it be written as an expression?
if (the requirement can be expressed as "arithmetic over existing variables") {
    use an expression;   // safe, fast
} else if (the requirement is "query this system's results/status") {
    use waitFor + variable injection;   // safe, moderately fast
} else {
    use a script;   // risky, slow
}
```

**The user's scenario falls into the second category**, so **no script is needed**.

---

## 8. The implementation

### 8.1 Done in M1 (3 days)

| # | Task | Estimate |
|---|---|---|
| 1 | The `run_result` table + writing (from the `output` configuration) | 1 day |
| 2 | Variable injection (`results.<key>.<field>`) | 1 day |
| 3 | `waitFor` + polling + timeout | 1 day |
| 4 | The `has()` function (one more built-in function in the expression engine) | 0.5 days |

**These 3 days have to be added to M1** (1.75 → **2.1 weeks**).

### 8.2 Acceptance

| Scenario | Assertion |
|---|---|
| **The result arrives first** | The gateway node reads it directly, without waiting |
| **The result arrives later** | It is read after polling for 2 seconds |
| **The result never arrives** | It is handled according to `onTimeout` after the timeout |
| **During the wait** | The robot arm has been released (the other workflows can use it) |
| **A duplicate result** | It is handled according to the `onDuplicate` strategy |
| **The variable does not exist** | `has()` returns false and the expression does not raise an error |

---

## 9. An easily overlooked point: the validity period of a result

**A result is not valid forever**:

```
The plate-reading result came out 2 hours ago
  → Can it still be used for the decision now? (the sample may have deteriorated)
```

**Add a "validity period"**:

```json
{ "output": "readPlate", "validForMs": 7200000 }   // 2 hours
```

**After it expires**:
- `has('readPlate')` returns **false**
- `results.readPlate.__expired` is marked true

**Why**: **making a judgement on an expired result may lead to a wrong conclusion without
anyone knowing.**

---

## 10. Open questions

| # | Question | Impact |
|---|---|---|
| 1 | Will the result **definitely arrive**, or might it **never arrive**? | It decides the default `onTimeout` |
| 2 | Is it normal for the same key to **appear twice**? | It decides the default value of `onDuplicate` |
| 3 | Does the result have a **validity period**? (the sample may deteriorate) | It decides whether `validForMs` is needed |
| 4 | **Who writes** the result? (the device automatically / a human fills it in / an external system pushes it) | It decides the default value of `source` |

**The third point is easy to overlook** — if the plate-reading result is from 2 hours
ago, using it now to decide "positive/negative" means **the basis may have expired**.
