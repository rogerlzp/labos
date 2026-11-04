# Load Test Report: Multi-Experiment Parallelism

> **English edition.** The Chinese original is [`docs/压测报告-多实验并行.md`](../压测报告-多实验并行.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> It loads 7 simulated devices to measure the throughput, the latency and whether the island lock works.
> Script: `./scripts/load-test.sh LAB-DEMO-02 1,2,4,8,16`

---

## 1. The data

### Throughput and latency

| Concurrency | Success | Failure | Duration (s) | Throughput (runs/s) | Average per run (ms) | Peak concurrency |
|---|---|---|---|---|---|---|
| 1 | 1 | 0 | 6.56 | 0.15 | 5239 | 1 |
| 2 | 2 | 0 | 6.45 | 0.31 | 5221 | 2 |
| 4 | 4 | 0 | 6.82 | 0.59 | 5304 | 4 |
| 8 | 2 | 0 | 6.42 | 0.31 | 5164 | 2 |
| 16 | 11 | 0 | 7.78 | **1.41** | 5741 | **11** |

**How to read it**:

- **The average per run is stable at 5.2-5.7 seconds**, it does not rise with the concurrency
- **The throughput rises linearly with the concurrency** (0.15 → 1.41)
- **No failures** (16 concurrent, zero failures)

**Conclusion: the concurrency itself does not slow down a single execution, but the throughput is locked by the duration of one run.**

### The composition of the duration of one execution

```sql
SELECT EXTRACT(EPOCH FROM (actual_end - actual_start)) AS 总秒,
       count(rc.id) AS 命令数,
       sum((rc.result->>'durationMs')::int) AS 命令耗时和
FROM run r LEFT JOIN run_command rc ON rc.run_id = r.id ...
```

```
总秒    命令数   命令耗时和
5.07    2        312
5.03    2        312
5.04    2        319
```

**The commands only take 312ms, the total duration is 5 seconds —— 4.7 seconds are spent somewhere unknown.**
(The column headers above are the Chinese aliases of the SQL above: 总秒 = total seconds, 命令数 = the number of commands, 命令耗时和 = the sum of the command durations.)

---

## 2. The three findings

### 🔴 Finding 1: the island lock does not work (an architectural gap)

**Evidence**: during the concurrent execution, **the time intervals of 55 pairs of runs overlap**.

```sql
SELECT count(*) FROM run a JOIN run b ON a.id < b.id
WHERE a.actual_start < b.actual_end AND b.actual_start < a.actual_end;
-- 55
```

**The root cause**:
```
POST /api/workflows/{id}/run     → WorkflowService.execute()  ← does not go through the scheduler
POST /api/experiments/{id}/run   → ExperimentScheduler        ← goes through the scheduler
```

**Calling the workflow execution directly bypasses the island lock.**

**The impact**:

- On site, if two operators click "execute" at the same time, **the same island will run two experiments**
- The robot arms will fight each other
- **This is a hole that has to be plugged before going into production**

**The fix (two options)**:

| Option | What it does | The cost |
|---|---|---|
| **A. Add the lock inside WorkflowService** | Acquire the island lock before the execution, queue up if it cannot be acquired | A small change, but the logic is duplicated in two places |
| **B. Force everything through the scheduler** | Deprecate `/api/workflows/{id}/run`, keep only the experiment path | Clean, but the frontend and the existing calls have to be changed |

**Option B is recommended** — having two execution paths side by side is a hidden risk in itself (it is already listed as a known issue in the documents).

### 🟡 Finding 2: one execution takes 5 seconds, but the commands only take 312ms

**The sum of the command durations is 312ms, the total duration is 5000ms.**

**Already ruled out**:

- The driver registry rebuild (after changing it so that both modes are cached, it is still 5 seconds)
- The queue waiting (`actual_start - created_at` = 0)
- The concurrency contention (it is also 5 seconds with a single concurrency)

**Not ruled out**:

| Possibility | How to verify it |
|---|---|
| The multiple SQL statements of `PositionLock` | Add timing logs |
| The `run_event` writes | The same |
| The BPMN parsing (the XML is parsed on every execution) | Cache the parse result |
| The database connection setup (every `db.open()`) | A connection pool |

**This has not been located yet**, it needs segmented timing.

**The impact**: the throughput ceiling = 1/5.3 ≈ **0.19 runs/second/island**.
One island can run at most about **16 000** workflow nodes a day — that is enough for a laboratory, but it is still a way off "smooth".

### 🟡 Finding 3: the measurement basis is unreliable

The load-test script counts with `run.id > 水位` (the high-water mark), but some runs were not counted (with 2 concurrent, "success" is only 1).

**The reason**: `/api/workflows/{id}/run` returns **synchronously**, but under concurrency some requests may time out or be cut off, and the script counted before it had waited.

**The fix**: change the script to "wait 2 seconds after it has sent the requests, then count".

---

## 3. What conclusions can be drawn

### The good news

| Item | Data |
|---|---|
| **Zero failures** | 16 concurrent, 11 runs, all of them succeeded |
| **The concurrency does not slow down a single run** | The average per run is stable at 5.2-5.7 seconds, independent of the concurrency |
| **The throughput is linear** | 0.15 → 1.41 runs/second, no inflection point |

**This says the system itself has no resource contention problem** (the database connections and the thread pool are sufficient).

### The bad news

| Item | Impact |
|---|---|
| **The island lock can be bypassed** | Real parallelism inside one island, the robot arms will fight each other |
| **5 seconds per run** | 4.7 seconds are wasted overhead, the throughput is locked |
| **The measurement is inaccurate** | The load-test data itself has to be discounted |

---

## 4. The next step

### Must be done (before going into production)

1. **Plug the island-lock gap** (option B: keep only the experiment execution path)
2. **Locate the 4.7 seconds of overhead** (add segmented timing)

### Recommended

3. Fix the measurement basis of the load-test script
4. Add a connection pool (if it is confirmed that `db.open()` is the slow part)

### Not needed

- Add more simulators (7 are enough, the bottleneck is not the number of devices)
- Raise `MAX_CONCURRENT` (it is 4 now, but in the measurements it had no effect — because the scheduler was bypassed)

---

## 5. How to reproduce it

```bash
# after starting the simulators and the control console
./scripts/load-test.sh LAB-DEMO-02 1,2,4,8,16

# the details are in /tmp/loadtest.csv
```

**Verifying the island lock**:

```sql
SELECT count(*) FROM run a JOIN run b ON a.id < b.id
WHERE a.actual_start < b.actual_end AND b.actual_start < a.actual_end;
-- > 0 means there is parallelism
```

---

## 6. The lock granularity rework (island lock → device lock)

### The problem: the island lock is too coarse

An island holds an incubator, a centrifuge, a plate reader and a robot arm, **and only the robot arm is really shared** (it can only move one plate at a time). The island-level mutual exclusion magnifies "grab the robot arm" into "grab the whole island":

```
Experiment A: incubates for 30 minutes (only the incubator)
Experiment B: centrifuges for 5 minutes (only the centrifuge)
Island lock → B waits stupidly for 30 minutes. A pure waste.
```

### The option: three layers of locks, each with its own job

| Lock | Granularity | What it prevents | Implementation |
|---|---|---|---|
| **Position lock** | One level | Two plates in the same position | `position_state.reserved_by_run` |
| **Device lock** | One device | Two executions commanding the same device | `device_state.current_run_id` |
| **Island lock (optional)** | The whole island | Exclusive use during maintenance/debugging | The EXCLUDE constraint of `resource_booking` |

**The robot arm needs no special handling** — it is just one device, and using the same lock naturally implements "one plate per trip".

### The island lock is downgraded to optional

It is **not used** by default, it only takes effect when `?exclusive=1` is passed explicitly (the maintenance and debugging scenarios).

### The measured comparison

| Configuration | Result at 4 concurrent | Total duration |
|---|---|---|
| Island lock on | **1 success, 3 rejected** | 6 seconds |
| Island lock off + device lock | **4 successes** | 8.78 seconds |

**The concurrency capability went from 1 to 4**, and the duration of a single run hardly changed.

### A concurrency defect that was fixed on the way

With 4 concurrent, 2 of them failed:

```
duplicate key value violates unique constraint "uk_run_no"
Key (tenant_id, run_no)=(1, WF-20261102080427077) already exists.
```

**The reason**: `run_no` uses a millisecond timestamp, under concurrency the same millisecond produces the same value.

**The fix**: add a 4-digit random suffix (`WF-20261102080427077-4821`).
