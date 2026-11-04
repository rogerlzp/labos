# Parallel Capability Verification (2026-11-03)

> **English edition.** The Chinese original is [`docs/并行能力验证-20261103.md`](../并行能力验证-20261103.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> It answers two questions:
> ① **Should the Gantt draw a "multi-experiment parallelism" chart?** — No, the reason is in §5.
> ② **"Several experiments and several workflows running in parallel" — how far is it supported today, and what has been measured?** — See §1–§4.

## 1. The one-sentence conclusion

| Capability | The current state | Evidence |
|---|---|---|
| **Parallel branches inside a workflow** (fork/join) | ✅ Supported, measured | `verify-parallel-flow.py` 4/0 |
| **The same device is wanted by two branches at the same time** | ✅ Automatically serialised (the device lock) | The command time intervals do not overlap = 0 |
| **Several experiments on the same island** | ✅ Queued up and serialised (the island-level mutual exclusion) | `verify-parallel-experiments.py` 3/0, peak concurrency = 1 |
| **Several experiments across islands** | ⚠️ The code supports it, **it has not been measured in this environment** | Reading the code only, see §4 |
| **Parallel scheduling in the Gantt** | ❌ Not done (on purpose) | See §5 |

## 2. Parallel branches inside a workflow (measured)

The graph: `开始 → 分叉 → (封膜 / 读状态) → 汇合 → 结束` ("Start → Fork → (Seal / Read status) → Join → End"), both branches are bound to the same S100.

```
Devices: T1=504830 (Seal) T2=504830 (GetStatus)  [the same one]
Nodes:
T1 completed
T2 completed
Commands:
Seal      | 15:00:54.833 → 15:00:54.833 | completed
GetStatus | 15:00:55.034 → 15:00:55.034 | completed
  ✓ The whole order is completed (it ends normally after the join)
  ✓ Both branches have a node record
  ✓ Both branches are completed
  ✓ The same device: the command time intervals do not overlap (DeviceLock serialised it)
passed 4 / failed 0
```

The arrival interval of the two branches is 201ms and the **time intervals do not overlap**: it is not that there was "no parallelism", it is that `DeviceLock` put "two branches fighting for the same device" into a queue — that is the physical constraint (a device can only take one instruction at a time).

> This also explains why "parallelism" shows no speed-up in the single-machine scenario: **the device is the bottleneck, not the thread**.

## 3. Several experiments on the same island (measured, serialised)

4 experiments (the same island `Device_Test`, 1 workflow each) **submitted asynchronously at the same time**:

```
4 submissions took 0.07s
sampling: 0R/4Q 0R/4Q 0R/4Q 0R/4Q 1R/3Q 0R/3Q 1R/2Q 0R/2Q 1R/1Q 0R/1Q 1R/0Q 0R/0Q
  ✓ Queuing appears when several experiments share an island (peak queue 4)
  ✓ Peak simultaneous running = 1 (the island mutual exclusion should be 1)
  ✓ 4/4 experiments completed
passed 3 / failed 0
```

All 5 sampling rounds are `1R/NQ` — one is running and the rest are queued, **there were never two running**. All 4 experiments completed, none was lost.

## 4. Several experiments across islands (not measured, reading the code only)

The code does allow it:

- `ExperimentScheduler.islandLocks` is an **island → lock** mapping, different islands do not exclude each other
- The thread pool `newFixedThreadPool(MAX_CONCURRENT = 4)`
- The start-up log: `[调度] 已启动（岛级互斥，最多 4 个实验并行）`

**But this environment cannot verify it**: only the one island `Device_Test` has a reachable simulator attached (S100 / ASPIN_2K), the devices of the other islands (Pre-NGS, CC, SIQC…) have no sidecar they can connect to, submitting there would only fail and would not show any "parallelism".

To complete the test you need: one reachable device on each of two islands, submit two experiments at the same time, and observe whether two `running` rows appear at the same time in `experiment_run`.

> By the way, an environment pitfall: when the script by default took "the first device", it once picked one that had no simulator attached (Seal failed to connect 4 times and went to a person).
> Now it by default takes **the device that has really run a command successfully in the last 2 hours**.

## 5. The Gantt: no parallelism modelling (an explicit trade-off)

The scheduling Gantt draws the time axis by accumulating serially under **"only one experiment runs at a time"**. This is on purpose, it is not an unfinished job:

1. The actual scheduling is "island-level mutual exclusion + at most 4 in parallel", **whether it can run in parallel depends on which island the experiment lands on**;
2. To draw the parallelism you first need a complete model of "which island the experiment goes to, and when which device in the island becomes free";
3. And the benefit of cross-island parallelism **cannot be verified** in this environment (§4).

So: **the Gantt only does the serial lower bound, the parallel interleaving is not done**. Both the page and the documents say so, so that it is not taken for "it has already been calculated".

## 6. How to reproduce it

```bash
export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...

# Parallelism inside a workflow (the same device → it should be serialised)
python3 scripts/verify-parallel-flow.py

# Different devices (optional; the ASPIN side is incomplete in this environment, so it may not yield a valid conclusion)
python3 scripts/verify-parallel-flow.py --device 504830 --device2 504831 --method2 CurrSpeed

# Several experiments on the same island (the peak concurrency should be 1)
python3 scripts/verify-parallel-experiments.py --count 4 --island 100030 --workflow 1254
```

The two scripts delete the experiments/workflows they created themselves; the `run` records are kept (the real execution history).
