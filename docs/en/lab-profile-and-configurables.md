# Lab Profile and Configurables — Design

> **English edition.** The Chinese original is [`docs/实验室画像与可配置项-设计.md`](../实验室画像与可配置项-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> The system has to be used by **many laboratories**, and every laboratory is different.
> **A single hard-coded policy is not acceptable**, but **neither is letting the user
> configure everything** (they would never finish, and nobody would anyway).

---

## 1. The core problem: how fine-grained the configuration should be

### The consequence of too little

```
One policy for everyone:
  - a high-throughput screening lab (chases throughput) → finds it too conservative
  - a medical testing lab (chases turnaround) → finds the queueing too casual
  - a teaching laboratory (1-2 experiments) → is slowed down by the complex deadlock detection
```

### The consequence of too much

```
50 configuration items → the implementation consultant cannot finish configuring them →
the user does not dare to change them → in the end everything uses the defaults
```

### The principle

> **Only make the items that "decide the architecture" configurable; give the "tuning"
> items sensible defaults and let advanced users change them.**

---

## 2. The 6 items that decide the architecture (mandatory)

These 6 items **change the way the system behaves**; they are not parameter tweaking.

### ① The willingness to parallelise (the most critical one)

```json
"maxParallelExperiments": 1 | 2 | 4
```

| Value | The shape of the system |
|---|---|
| **1** | **No deadlock detection is needed** — device locks only |
| **2** | Device locks + a simple return reservation (`returnTo`); no wait graph |
| **3+** | **The full hybrid scheduling is needed** (three-layer resources + the wait graph + the coarse screening) |

**This item directly decides how much has to be implemented**:

| Configuration | Implementation workload |
|---|---|
| 1 | Already done (D101-D104) |
| 2 | +2 days (`returnTo`) |
| 3+ | **+4 weeks (the hybrid solution)** |

**Why it is mandatory**: it decides whether the 4 weeks of work have to be done at all.

### ② The scheduling objective

```json
"objective": "throughput" | "latency" | "fairness"
```

| Value | The ordering basis | Applicable when |
|---|---|---|
| **throughput** | Keep the devices from idling (batching, filling up with big jobs first) | A high-throughput screening lab |
| **latency** | First come, first served (FCFS + short job first) | Medical testing (fast report turnaround) |
| **fairness** | Priority + ageing (prevents starvation) | Several customers sharing it |

**Impact**: the queue ordering and how conservative the coarse screening is.

### ③ The policy when the plate position is unknown

```json
"unknownPlatePolicy": "stop_all" | "stop_related" | "continue_with_alert"
```

| Value | Behaviour | Applicable when |
|---|---|---|
| **stop_all** | Pause the whole island | High-value samples (they must not collide) |
| **stop_related** | Only stop the ones involving this plate | **The default, recommended** |
| **continue_with_alert** | Keep running, only raise an alert | Low-value, disposable samples |

**Why it is mandatory**: it decides "how many resources one UNKNOWN has to lock up", and
the impact is large.

### ④ Whether anybody is on site at night

```json
"nightMode": {
  "enabled": true,
  "startHour": 20,
  "endHour": 8,
  "autoResume": true         // an UNKNOWN at night is degraded automatically
}
```

**Why it is mandatory**: an `UNKNOWN` needs a manual inventory. **At night nobody is
there, so the system would stay stuck.**

**The degradation in night mode**:

| Daytime | At night |
|---|---|
| Wait for a manual inventory | Automatically mark it LOST and terminate the related executions (conservative, but it lets the others keep running) |

**Cost**: it may be a misjudgement (the plate may actually still be inside the device),
but **it is still better than being stuck all night**.

### ⑤ The retry limit on failure

```json
"retry": {
  "maxAttempts": 3,
  "backoffMs": 1000,
  "onExhausted": "waiting_human" | "abort"
}
```

**Impact**: how `recovery_policy` is carried out.

### ⑥ Whether audit traceability is needed

```json
"audit": {
  "enabled": true,
  "level": "command" | "error" | "none"
}
```

| Value | What is stored | Storage volume |
|---|---|---|
| **command** | The parameters + the raw text of every command | Large |
| **error** | Only the failures are stored | Medium |
| **none** | Nothing is stored | Small |

**Impact**: pharma/clinical must use `command`; teaching can use `none`.

---

## 3. The items that can have defaults (optional)

These **do not change the architecture**, they are only tuning.

| Item | Default | Explanation |
|---|---|---|
| `scheduling.agingMinutes` | 5 | The rate of the priority ageing |
| `scheduling.preferEmptyDevices` | true | Prefer the devices that hold no plate |
| `deadlock.guardAlwaysOn` | true | The deadlock detection is always on (decided by review R6) |
| `position.lockTtlMinutes` | 30 | The TTL of the position lock |
| `heartbeat.intervalSeconds` | 10 | The heartbeat interval |
| `heartbeat.offlineAlertSeconds` | 30 | How long offline before an alert |
| `experiment.maxDurationHours` | 8 | Beyond this it is treated as abnormal |

---

## 4. The preset templates (to lower the implementation cost)

**The user does not have to configure from scratch** — pick a template and then fine-tune
it:

### Template A: a high-throughput screening lab

```json
{
  "maxParallelExperiments": 4,
  "objective": "throughput",
  "unknownPlatePolicy": "stop_related",
  "nightMode": {"enabled": true, "autoResume": true},
  "retry": {"maxAttempts": 3, "onExhausted": "abort"},
  "audit": {"enabled": false}
}
```

**Characteristics**: it can run in parallel, it chases throughput, it degrades
automatically at night, and it does not store a detailed audit.

### Template B: a medical testing lab

```json
{
  "maxParallelExperiments": 2,
  "objective": "latency",
  "unknownPlatePolicy": "stop_all",
  "nightMode": {"enabled": true, "autoResume": false},
  "retry": {"maxAttempts": 2, "onExhausted": "waiting_human"},
  "audit": {"enabled": true, "level": "command"}
}
```

**Characteristics**: conservative parallelism, it chases turnaround, it stops everything
when the plate position is unknown, and it stores a detailed audit.

### Template C: a teaching laboratory

```json
{
  "maxParallelExperiments": 1,
  "objective": "fairness",
  "unknownPlatePolicy": "stop_related",
  "nightMode": {"enabled": false},
  "retry": {"maxAttempts": 5, "onExhausted": "waiting_human"},
  "audit": {"enabled": true, "level": "error"}
}
```

**Characteristics**: **a single experiment (no deadlock detection needed)**, many retries
(the students are not consistent), and it stores the error audit.

### Template D: pharmaceutical GxP

```json
{
  "maxParallelExperiments": 2,
  "objective": "fairness",
  "unknownPlatePolicy": "stop_all",
  "nightMode": {"enabled": false},
  "retry": {"maxAttempts": 1, "onExhausted": "waiting_human"},
  "audit": {"enabled": true, "level": "command"}
}
```

**Characteristics**: **no automatic retry** (a human has to confirm), stop everything, and
a full audit.

---

## 5. Where the configuration lives

### The table structure

```sql
CREATE TABLE lab_profile (
  tenant_id     bigint PRIMARY KEY,
  template      varchar(32),        -- which template it comes from
  max_parallel  smallint NOT NULL,
  objective     varchar(16) NOT NULL,
  unknown_policy varchar(24) NOT NULL,
  night_mode    jsonb NOT NULL DEFAULT '{}',
  retry         jsonb NOT NULL DEFAULT '{}',
  audit         jsonb NOT NULL DEFAULT '{}',
  -- the tuning items
  tuning        jsonb NOT NULL DEFAULT '{}',
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    bigint
);
```

### How it is read at runtime

**Read once at startup and cached in memory** (the configuration does not change
frequently):

```java
LabProfile profile = LabProfile.load(tenantId);
// a 1 hour TTL, or an active refresh when the configuration changes
```

### The impact of a configuration change

| Configuration | After the change |
|---|---|
| `maxParallel` | It takes effect immediately (the scheduler reads the new value) |
| `unknownPlatePolicy` | It takes effect on the next UNKNOWN |
| `audit.level` | It takes effect on the next execution |
| `nightMode` | The time window is checked every minute |

**The ones already running are not affected** — to avoid a mid-run change causing state
confusion.

---

## 6. How this configuration reduces the implementation workload

**The key point**: `maxParallel` decides how much has to be implemented.

| Lab configuration | What has to be implemented | Workload |
|---|---|---|
| **1** | Device locks only (D101-D104) | **Already done** |
| **2** | +`returnTo` to protect the return path | +2 days |
| **3+** | +three-layer resources + the wait graph + the coarse screening | **+4 weeks** |

**This is what "gradual capability" means**:

```
Week 1: deliver a version that can run a single experiment (any lab can use it)
Week 2: add returnTo (supports 2 in parallel)
Week 5-6: add the hybrid scheduling (supports 3+ in parallel)
```

**Every stage can be delivered**; there is no need to wait for all 4 weeks to be done.

---

## 7. The implementation suggestions

### The first batch (do it now)

| Task | Estimate |
|---|---|
| The `lab_profile` table + the templates | 1 day |
| Reading + caching the configuration | 1 day |
| Wiring `maxParallel` into the scheduler | 1 day |

**What it enables**: a lab can choose "I only run 1", and the system takes the simple
path; or it can choose "I want to run 4", and **it knows explicitly that more has to be
implemented**.

### The second batch (decided by the configuration)

**If a lab wants 2 in parallel** → build `returnTo` (2 days)
**If a lab wants 3+** → build the hybrid scheduling (4 weeks)

---

## 8. The answer to the user's question

> "Should some conditions be entered by hand, so that the optimisation can be done
> better?"

**The answer: yes, but limited to 6 items, and with templates provided.**

**Why only 6 items**:

| Item | Why it has to be entered by hand |
|---|---|
| **The willingness to parallelise** | It decides the architecture (the workload of 1 / 2 / 4 differs by 4 weeks) |
| **The scheduling objective** | The business model (throughput vs turnaround); the system cannot guess it |
| **The unknown-plate policy** | The value of the sample (a high-value one must not collide, a low-value one can be dropped) |
| **Whether anybody is on site at night** | It decides whether it is possible to wait for an UNKNOWN (at night nobody is there to wait) |
| **The retry limit** | GxP does not retry automatically; teaching can retry 5 times |
| **The audit level** | The compliance requirement (pharma must store everything; teaching may store only the errors) |

**Everything else gets a default**, and advanced users can change it.

**The templates lower the implementation cost**: the 4 templates cover 80% of the
scenarios.

---

## 9. In one sentence

**The configuration is not for letting the user "tune the system", it is for letting the
user "tell the system what their laboratory looks like".**

**The 6 mandatory items decide the architecture; everything else gets a default.**
**The templates turn the implementation from "configure 50 items" into "pick one and
change 2 items".**

**The most important one is the first**: **the willingness to parallelise decides how
much has to be implemented** (1 → already done; 4 → 4 more weeks).
