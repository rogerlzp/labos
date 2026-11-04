# M3: Start Admission (Main-Chain Coarse Screening)

> **English edition.** The Chinese original is [`docs/M3-启动准入-主链粗筛.md`](../../M3-启动准入-主链粗筛.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> One-command acceptance: `./scripts/verify-m3.sh`
> **Result: 9 items passed / 0 failed**

---

## 1. What problem is solved

A pure wait graph can only "**find out halfway through that it cannot be acquired**":

```
The user clicks start → the system accepts it → it runs for 5 minutes → it gets stuck on acquiring a device → it waits forever
```

The user experience is "**I clicked and nothing happened**".

**The coarse screening computes it once, at start time**:

```json
GET /api/workflows/239/can-start

{
  "canStart": false,
  "strategy": "heaviest-devices",
  "reason": "资源不足：INCUBATE（空闲 0/48）",
  "estimatedWaitSeconds": 59,
  "blocking": [
    { "runId": 205172, "runNo": "M3-VERIFY-OCC",
      "deviceCode": "ID1183", "etaSeconds": 59 }
  ],
  "needDeviceCount": 1,
  "totalEstimateSec": 20
}
```

The `reason` value is the string the backend really returns — 资源不足：INCUBATE（空闲 0/48）
("insufficient resources: INCUBATE, 0/48 idle").

---

## 2. The main chain: why "one path has to be picked"

**Banker's algorithm requires "declaring up front how many resources are needed at most"**, but a workflow has branches, so it cannot be declared
(see `docs/为什么不用银行家算法.md`).

**The compromise**: **compute on the main chain only**. The main chain is one deterministic path, the requirement is certain.

If the run actually took a non-main path and the resources are not enough → the **fine control** (the wait graph) catches it → at worst it "waits a long time", **it never deadlocks**.

### The three ways of choosing it (in priority order)

| # | Strategy | Who decides | When it applies |
|---|---|---|---|
| 1 | **Marked by hand** | Configure `params.mainPath` on the gateway while orchestrating | Only the person who orchestrates knows which path is the normal case in business terms |
| 2 | **Heaviest in resources** | Automatic: it picks the branch that uses the most devices | On the conservative side, it will not "pass the screening but actually be short" |
| 3 | **Most nodes** | The fallback | It only exists so that the algorithm has a deterministic result |

Measured, 239 took `heaviest-devices` (no manual marking).

---

## 3. ⚠ The resource unit is a "capability", not a "device instance"

**This is the single most important correction.**

**At first it was computed by `params.equipment_instance_id`**, and the result was:

```
ID1183 is occupied → canStart is still true
```

**The reason**:

| Fact | Explanation |
|---|---|
| `equipment_instance_id=155` | **Historical dirty data**, it does not exist in the `device_instance` table at all |
| How the device is actually chosen during execution | **By capability, out of the pool** (`capability_id`) |
| How many `INCUBATE` units there are | **48 units** |

**So computing by "instance" was completely wrong.**

**Changed to compute by capability**:

```sql
SELECT c.code, count(di.id) AS total,
       count(*) FILTER (WHERE ds.current_run_id IS NULL) AS free_cnt
FROM capability c
LEFT JOIN resource_capability rc ON rc.capability_id = c.id
LEFT JOIN device_instance di ON di.resource_id = rc.resource_id ...
LEFT JOIN device_state ds ON ds.resource_id = di.resource_id ...
WHERE c.tenant_id = ? AND c.id = ?
GROUP BY c.code
```

**The criterion**: `free >= needCount` is required to start.

### The same capability appearing several times counts as 1 unit

**A sequential execution can reuse the same device.**

Only a **parallel gateway** really needs several units at the same time — and that case is caught by the fine control.

---

## 4. The acceptance result

```
── ① Starting is allowed when idle ──
  ✅ canStart=true
  ✅ The main-chain strategy is explainable: heaviest-devices
  ✅ Number of devices needed: 1
  ✅ Total estimated duration: 20s

── ② Occupying one unit does not matter (there are still many in the pool) ──
  ✅ INCUBATE has 48 units in total, occupying 1 unit is still allowed

── ③ All of them occupied → rejected ──
  ✅ canStart=false
  ✅ The rejection information is complete (reason + blocking + the wait estimate)
    reason: 资源不足：INCUBATE（空闲 0/48）
    blocking: 20 items
    first blocker: ID1183 runNo=M3-VERIFY-OCC eta=59s

── ⑤ Restored after the cleanup ──
  ✅ Back to "starting is allowed" after the cleanup
  ✅ No leftover occupancy

passed 9 / failed 0
```

**The contrast between ② and ③ explains the core**:
- 48 units, 1 occupied → **allowed** (47 are still free)
- 48 units, all occupied → **rejected** (0 units free)

**That is the real semantics** — not "this device is occupied, so nothing can run".

---

## 5. The code structure

| Class | Responsibility |
|---|---|
| `workflow/MainPathSelector` | Picks the main chain out of the graph, outputs the **capability requirement** |
| `workflow/AdmissionController` | Looks up the pool by capability, decides whether there are enough, gives the reason for the rejection |
| `ApiServer.canStartJson` | `GET /api/workflows/{id}/can-start` |

### The implementation of the main-chain selection

```
DFS from the start node to the end, collect all the paths (up to 200, to prevent an exponential blow-up)
  ↓
① There is a mainPath marking and the path matches → use it
② Otherwise pick "the one that uses the most devices"
③ On a tie pick "the one with the most nodes"
```

---

## 6. estimatedWaitSeconds is inaccurate

**It is computed from `run_node.estimate_sec - 已跑秒数` (the seconds already run).**

**A duration estimate cannot be accurate in the first place** — the interface has to mark it as "**for reference only**".

**Why it is given anyway**: better than nothing. The user seeing "about 59 seconds" is still better than "not knowing how long the wait will be".

---

## 7. What happens when the coarse screening is wrong

| Case | Consequence |
|---|---|
| The coarse screening says "it can start", but the run took a non-main path and the resources are not enough | **A long wait** (the fine control catches it, no deadlock) |
| The coarse screening says "it cannot start", but the resources are actually enough | **A false rejection**, the user retries once |

**Neither of them leads to a deadlock** — this is the division of labour "the coarse screening may be wrong, the fine control may not".

---

## 8. The pitfalls hit along the way

| Pitfall | Root cause | The fix |
|---|---|---|
| Computing by device instance was completely ineffective | `equipment_instance_id=155` is dirty data, the actual selection is by capability | Query the pool by capability |
| The `device_instance_state` table does not exist | A table name I made up | Use `device_state.current_method` |
| `run_node.node_id` does not exist | It is actually `node_key` | Switch to `run_node.estimate_sec` |
| `resource_capability` has no `tenant_id` | The table is structured that way | Drop that condition |
| The script's `OCC_RID` is not a valid number | `psql -tAc` also printed `INSERT 0 1` | `head -1 \| tr -d '[:space:]'` |

**The first one is the most important** — it shows that this **implicit contract**, "reading the code makes you think it is per instance, in reality it is per capability",
cannot be seen by reading the code alone, it has to be verified against the data.

---

## 9. The relationship to M1/M2

```
M3 coarse screening (before the start) → decides whether it can start, gives the reason
M1 resource protocol (during execution) → a fixed total order of acquisition, rollback on failure
M2 wait graph (during execution)       → rejects a cycle, guarantees no deadlock
```

**The three layers work together**:
- The coarse screening is responsible for the "**experience**" (there is feedback after the click)
- The fine control is responsible for the "**correctness**" (no deadlock)
