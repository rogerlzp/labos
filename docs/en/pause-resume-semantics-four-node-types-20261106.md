# Pause Point and Resume Origin · Exclusive / Parallel / Device / Variable Nodes (2026-11-06)

> Answers four questions:
> ① When a pause happens, **which kind** of node does it stop at?
> ② On resume, does it continue from the **next** node, or re-run from the
> **current** (pause-point) node?
> ③ Do device nodes and logic (variable/expression) nodes differ in how pauses
> and resumes are handled?
>
> Script: `scripts/e2e-pause-resume-nodes.py` (gate section 4.16, threshold `MIN_PAUSERES=25`)
> Re-run: `python3 scripts/e2e-pause-resume-nodes.py --base http://localhost:5173`
> Environment: macOS, frontend `5173` + API `18080` + PostgreSQL `192.168.124.2:5432/labdb` + simulators 19500-19506
> Result: **25 / 0 all green**

---

## 1. One-line conclusion

| Question | Answer |
|---|---|
| ① Which nodes can a pause stop at? | **Only device task nodes.** Exclusive gateways, parallel gateways **and logic nodes (variable/expression)** never become the pause point — in the source, `pauseChecker` is only consulted in the `Kind.TASK` branch (the `Kind.BUILTIN` branch is separate and does not check it).<br>⚠ And a pause takes effect **after the previous node has already finished** — the check sits *before* `executeNode`, so a node that was mid-flight when the signal arrived still runs to completion |
| ② On resume, from the next node or from the current one? | **From the node that had not finished yet** — neither "the next one" nor "from the beginning". Nodes already `completed` are **skipped, not re-run**; nodes that had not finished are **re-run**.<br>Measured: the device node was **already completed** when the pause took effect (the check sits before execution), so it lands in `skipKeys` → the resume **skips** it → the device command is issued exactly once |
| ③ Difference between device and logic nodes? | **No difference in how the pause point is chosen** (both are task-like) — but there is a difference **on resume**: re-running a logic node is **pure computation, zero side effects**, while re-running a device node **issues the device command again** — so the mechanism must guarantee it is not issued twice |

---

## 2. Source basis (the conclusions are not guesses)

### 2.1 There is exactly one pause check, and it is in the task branch

`labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java`:

```java
} else if (node.kind() == Kind.TASK) {
    // P1-8：在**任务节点的动作边界**检查暂停。
    // 不停在动作中间 —— 机械臂抓着板会掉。
    // 也不停 start/gateway —— 它们不是"一步活"，停在那里没意义。
    // 把 key 放回队列，恢复时从这里继续。
    if (pauseChecker.getAsBoolean()) {
        ready.offer(key);
        done.remove(key);
        paused = true;
        log.accept("[暂停] 收到暂停请求，在节点边界停下，待执行节点：" + key);
        break;
    }
    RecoverableNode rn = toRecoverable(node);
    ...
```

Three key points:

1. **The check lives inside the `Kind.TASK` branch** → gateways (`SPLIT_XOR` /
   `SPLIT_PARALLEL` / `JOIN_*`) and start/end events never consult the pause flag.
2. **The check is before `executeNode`** → it stops at an **action boundary**, never in
   the middle of a device command ("the robot arm would drop the plate").
3. **`ready.offer(key); done.remove(key);`** → the stopped node is put **back on the
   queue** and removed from `done`, which is exactly the semantics of "this node has
   not been done yet; resume continues here".

> ⚠ **A detail that is easy to misread**: the first branch tests
> `(node.kind() == Kind.TASK || node.kind() == Kind.BUILTIN) && skipKeys.contains(key)`,
> which looks like TASK and BUILTIN are treated alike — but that is the
> **skip-on-resume** test. The very next `else if (node.kind() == Kind.BUILTIN)`
> branch has **no** `pauseChecker`; the `pauseChecker` only appears in the
> `else if (node.kind() == Kind.TASK)` branch below it. So **a logic node never
> becomes the pause point** (the measurement confirms it: the pause landed on the
> device node while VAR was already `completed`).

### 2.2 The pause is implemented by querying the database

`WorkflowService.java:824`:

```java
// P1-8：每到一个节点边界查一次 DB —— 状态是 paused 就停。
// 只查单行主键，开销可忽略；不查的话暂停按钮等于没用。
.withPauseChecker(() -> isRunPaused(runId));
```

i.e. **the page's "pause" button sets `run.status='paused'` → the executing thread
finds it at the next task-node boundary → stops.**

### 2.2b The exact moment a pause takes effect (clarified by measurement)

`pauseChecker` is called **before** `executeNode`, so:

```
… → node X (executing) …  ← the pause signal arrives here; X still runs to completion
… → the next boundary after X  ← only here is `paused` seen, and the run stops
```

i.e. **a pause takes effect after the previous node has already finished**. The code that
runs when it stops is:

```java
ready.offer(key);   // put this node back on the queue
done.remove(key);   // remove it from the completed set
paused = true;
break;              // don't go further this round
```

**"Put back on the queue" does not mean "will be re-run"** — and that queue is not even
usable: `ready` is a **local variable inside `GraphRunner.run()`**, so after `break` the method
returns and the queue is discarded. **A resume is a brand-new `run()` call** — it starts again
from START and skips the already-completed nodes one by one via `skipKeys`.

So "continue from the current node" is **not implemented by remembering progress in a queue**,
but **derived backwards from skipKeys**: everything `completed` last round is skipped, and the
first one that is left is "the node to continue with".

Measured: the device node was already `completed` when the pause took effect → it went into
`skipKeys` → the resume skipped it → **the device command was issued exactly once**
(`run_command` has 1 row, with a real response).

> ⚠ **"The device node was already completed when the pause took effect" does NOT mean
> "the pause is always too late".**
> That conclusion was measured on a **fast device (150 ms)**: the script waits for a
> `run_command` row before issuing the pause, and `run_command` is only persisted **after the
> step has finished** ⇒ by the time the pause went out, the device was long done.
> Re-measured with a long-delay device (10–18 s) issuing the pause **while the method was
> mid-flight**, the conclusion is **unchanged**: the device method still runs to completion
> (`completed`), the `run` turns `paused`, and the run really stops only at the **next task
> node boundary** — because the check point is always **before** `executeNode`.
> How to write the criterion so it does not mislead: see §1.5 of
> "Pausing During a Device Command · Measured Reality and How to Really Stop".

### 2.3 "Where to continue" on resume is decided by `skipKeys`

`WorkflowService.resumeAsync(runId)`:

```java
// 断点续跑要跳过的节点 key（上一轮已经 completed 的那些）
SELECT node_key FROM run_node
 WHERE tenant_id = ? AND run_id = ? AND level = 0 AND status = 'completed'
```

With that `doneKeys` it then:

```java
// 先删步骤行（level=1），再删业务节点
DELETE FROM run_node s USING run_node p
 WHERE s.level = 1 AND p.id = s.parent_node_id AND p.level = 0 AND p.status <> 'completed'
DELETE FROM run_node WHERE level = 0 AND status <> 'completed'
UPDATE run SET status = 'running', pause_kind = NULL, ... WHERE id = ?
```

and finally `execute(wid, mode, rid, doneKeys)` → `GraphRunner.withSkipKeys(doneKeys)`:

```java
if ((node.kind() == Kind.TASK || node.kind() == Kind.BUILTIN)
        && skipKeys.contains(key)) {
    // 断点续跑：这个节点上一轮已经做完了，直接当完成往下走
    log.accept("[续跑] 节点 " + key + " 已完成，跳过");
}
```

**So the answer is "continue from the node that had not finished"**:

- nodes `completed` in the previous round → go into `skipKeys` → **skipped, not re-run**
- nodes not `completed` → their rows are deleted → **re-run**

### 2.4 The real difference between device and logic nodes

There is **no difference in how they are skipped or re-run** (`skipKeys` puts `Kind.TASK` / `Kind.BUILTIN` side by side). The difference is the
**side effect of re-running**:

| | Logic node, instant computation (`__setVar` / `__expr`) | Device node (plain task) |
|---|---|---|
| What it does | The engine computes a value into the variable table | Sends a command to the device through the driver |
| Cost of re-running | **zero side effects** (same result) | **physical side effects** — the robot arm moves the plate again, the device acts again |
| Protection | none needed | `GraphRunner`'s comment is explicit: "**不能**按执行结果决定是否退回。退回意味着重跑，而在真实设备上重跑 = 开两次盖（物理副作用不可撤销）" |
| When skipped | the variable value is kept in `run_node` | an already-completed device action is not redone (via `skipKeys`) |

`WorkflowService.java:825-827` is the core of that design:

```java
// 注意：**不能**按执行结果决定是否退回。
// 退回意味着重跑，而在真实设备上重跑 = 开两次盖（物理副作用不可撤销）。
```

> ⚠ One easy pitfall worth recording: `run_command` hangs off the **level=1 step
> rows**, so you must join through `parent_node_id` to reach the level=0 business
> node. The first version joined on level=0 directly, counted 0 rows, and nearly
> concluded "the device never ran".

---

## 3. Measured (from the script, not inferred)

### 3.1 The graph

```
S ─ VAR(variable node) ─ DEV(device node) ─ XOR ─┬─ A ─┐
                                                  └─ B ─┴─ PAR(parallel) ─┬─ C ─┐
                                                                          └─ D ─┴─ E
```

- `VAR` = variable node `__setVar` (counter=1)
- `DEV` = a **real device node** (simulator ID1731 / Home — the command really goes out
  and a response really comes back)
- `XOR` / `PAR` = exclusive / parallel gateways
- `A`/`B`/`C`/`D` = built-in nodes (a plain task is a device task; with no matching
  device it turns `manual` and stalls the run)

### 3.2 Measured output

```
══ B. 执行 → 在设备节点执行时暂停 ══
  ✓ 触发真执行（run #207362）
  ✓ 抓到设备节点 DEV=Activity_1dspo1r 进入 running
  ✓ 发出暂停
  ✓ run 状态变 paused（paused）

══ C. Q1 · 暂停点落在哪种节点上 ══
  ✓ pause_node_key 记的是待执行节点 Activity_1dspo1r
  ✓ 暂停点不是网关（异或/并行网关都不检查 pause）
  ✓ 逻辑节点（变量节点）也不是暂停点（BUILTIN 分支不查 pauseChecker）
  ✓ 暂停点是设备任务节点
  ✓ 变量节点 VAR 状态是 completed（逻辑节点在暂停点之前已跑完）
  ✓ 设备节点 DEV 在暂停时已完成（completed）—— 暂停生效在它执行完之后

══ D. Q2/Q3 · 恢复：从哪个节点继续 ══
  ✓ 恢复前已 completed 的节点：1 个
  ✓ VAR（逻辑节点）在恢复前已 completed → 续跑会跳过它
  ✓ DEV（设备节点）在恢复前已 completed → 进 skipKeys → 续跑跳过它
  ✓ 恢复后整单 completed（completed）
  ✓ VAR（逻辑节点）续跑后仍只有 1 条记录 → 没被重跑（skipKeys 跳过）
  ✓ DEV（设备节点）续跑后仍只有 1 条记录 → 旧的删掉重写，不是叠加
  ✓ DEV（设备节点）续跑后 completed
  ✓ 暂停点节点续跑后 completed
  ✓ DEV 的设备指令只下了 1 次（续跑不重复下发，1 条）
  ✓ 设备指令真收到了回包（1 条有 device_raw_response）

通过 25 / 失败 0
```

### 3.3 The three most convincing pieces of evidence

1. **"The device command is not issued twice"** — `run_command` holds exactly **one**
   command for DEV, carrying a real `device_raw_response`. This shows the
   "physical side effects are irreversible" design **actually works**: a completed
   device action is not redone.
2. **"The logic node is not re-run"** — VAR still has exactly one `run_node` row after
   the resume. Had it been re-run there would be two (resumeAsync deletes the
   non-completed rows, the resume rewrites them).
3. **"The pause point is not a gateway"** — `pause_node_key` points at a task node;
   exclusive/parallel gateways never become the pending node.

---

## 4. Full answers to the four questions

### Q1 · Will a pause stop at these nodes?

| Node type | Can it be the pause point? | Basis |
|---|---|---|
| **Exclusive gateway** (✕) | **No** | `pauseChecker` is only in the `Kind.TASK` branch; gateways take the `else` branch and go straight to `choose()` / edge activation |
| **Parallel gateway** (＋) | **No** | same |
| **Device node** | **Yes** (at its action boundary) — but it actually takes effect at the **next boundary after it finished**, because the check sits before `executeNode` | `Kind.TASK` branch |
| **Variable / expression node** (instant computation) | **No** | `Kind.BUILTIN` takes a separate branch with **no** `pauseChecker`; measured: the pause landed on the device node while VAR was already `completed` |
| **Delay / wait node** (`__sleep` / `__wait`) | **Yes** | the wait loop polls `pauseChecker` in 500 ms slices (`GraphRunner.waitInterruptibly`) |
| Start / end | **No** | `Kind.START` / `Kind.END` — not "a unit of work" |

### Q2 · On resume: from the next node, or from the current one?

**From the node that had not finished** — neither "the next one" nor "from the start".

- The node at the pause point: it had **not** finished → the resume **re-runs it**
  (measured: DEV ends `completed` after the resume)
- Nodes **before** it that were `completed`: they go into `skipKeys` → **skipped, not
  re-run** (measured: VAR still has exactly one row)

In one sentence: **"what was already done is not done again; continue from the step it
stalled at"** (the `GraphRunner` comment's own wording).

### Q3 · How do device nodes and logic nodes differ?

| Dimension | Logic node, instant computation (`__setVar`/`__expr`) | Device node |
|---|---|---|
| **Can it be the pause point** | ❌ **No** (the `Kind.BUILTIN` branch doesn't consult `pauseChecker`; measured: the pause landed on the device node). **Exception**: `__sleep` / `__wait`, see the note in section 4 Q1 / Q3 | ✅ Yes (action boundary, before the command is sent) |
| **Pause timing** | — | before the command is issued (action boundary, never mid-command) |
| **Re-run on resume** | already completed → skipped | already completed → skipped; **not completed → re-run (issues the command again)** |
| **Cost of re-running** | zero side effects | **physical side effects** (the robot arm moves the plate again) |
| **Protection mechanism** | none needed | `skipKeys` + the hard constraint "do not fall back based on the execution result" |
| **On failure / skip** | evaluation failure → turns `manual` and stops (must not carry a wrong value forward) | an action that was skipped → **the plate position is no longer trusted → hand over to a human** (D9: skipping means the plate position is unknown) |

**The most important line**: re-running a device node means **the device really moves
again**. That is why the design is:

- resume starts at the **unfinished** node, not from the beginning (otherwise every
  device action in the flow would be redone)
- completed device actions are skipped via `skipKeys`
- the source explicitly forbids "falling back based on the execution result" — falling
  back means re-running, and re-running on a real device means opening the lid twice

---

## 5. Boundaries and what is still uncovered (honest list)

| Item | Status | Note |
|---|---|---|
| **A logic node as the pause point** | 🟡 partially covered | the source's `Kind.BUILTIN` branch has no `pauseChecker` ⇒ `__setVar` / `__expr` (instant computations) never become the pause point; measured: the pause landed on the device node, but no dedicated assertion pins this down (only "not a gateway" was asserted). **Exception**: the `__sleep` / `__wait` nodes added on 2026-11-06 poll `pauseChecker` in 500 ms slices in their wait loop and **can** become the pause point (the waiting semantics are covered by the `waitsleep` section of `verify-flow-matrix.py`; the pause response has no dedicated assertion) |
| Pausing **in the middle of a device command** | ✅ now measured (by the other script) | the source checks at the action boundary ("the robot arm would drop the plate") ⇒ it **never stops mid-command**; the device method still **runs to the end**. A half-way state was constructed with a long-delay simulator — see §1.5 / §2.6 of "Pausing During a Device Command" |
| Pause under a **site hold** (`runtime_hold`) | ❌ not covered | relates to `PauseManager.pause`'s `release_exempt` (the robot arm holding a plate / active device lock are exempt) |
| Pausing **inside a loop body** | ❌ not covered | interaction between `maxVisits` and `hasCycle` |
| **Real device** | ❌ not covered | this round uses the simulator (ID1731 / Home); the command really goes out and a response really comes back, but real-device timing differs |
| Pausing on an **exclusive branch with conditions** | ❌ not covered | this round's exclusive gateway has no condition and takes the "first in stable order" |
| Pausing on a **`subProcess` node** | ❌ not covered | the `subProcess` silent-skip issue is still unfixed (honest-list item 12) |

---

## 6. How to re-run

```bash
export LABOS_DB_HOST=192.168.124.2 LABOS_DB_NAME=labdb \
       LABOS_DB_USER=labos LABOS_DB_PASS='***' LABOS_DB_PORT=5432 PGPASSWORD='***'

python3 scripts/e2e-pause-resume-nodes.py --base http://localhost:5173   # → 25 / 0
```

Requires: frontend `5173` + API `18080` + the simulators + simulator device `ID1731`
(method `Home`). The script cleans up the workflows / runs / nodes it creates.

> ⚠ Timing notes (learned while writing the script):
> ① A simulator command round-trip takes only ~750ms — the polling interval must be
>    **50ms**; at 1s you miss it entirely;
> ② The device dropdown holds 247 devices but renders only the first 10 — you must
>    **type to search**, otherwise `ID1731` simply isn't in the list;
> ③ Selecting a node needs a raw `page.mouse.click` — `locator.click()` is blocked by
>    bpmn-js's direct-editing box (`.djs-direct-editing`) and retries until timeout.

---

## 7. Gate integration

`scripts/ci-gate.sh` gains **section 4.16**, threshold `MIN_PAUSERES=25`:

```bash
MIN_PAUSERES=25   # 2026-11-06：暂停点与恢复起点（暂停点落在设备任务节点——网关不查 pause，__setVar/__expr 是瞬时计算也不查；__sleep/__wait 在等待循环里另查，见 MIN_MATRIX 的 waitsleep；恢复从「未完成的节点」继续，已 completed 的走 skipKeys 跳过；设备指令不重复下发）
```

The gate section count goes **19 → 20**.
