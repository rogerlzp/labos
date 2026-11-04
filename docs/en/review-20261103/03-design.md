# Design Document · Key Decisions and Trade-offs

> **English edition.** The Chinese original is
> [docs/en/review-20261103/03-design.md](03-design.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> Audience: review (this one is the focus), future maintainers
> Companions: [Functional document](01-functional.md) · [Technical document](02-technical.md) · [Testing document](04-testing.md)
>
> Every decision is written as **background → choice → the alternatives that were given up → the cost**. Everything that has a cost is stated openly.

---

## 0. Three overarching principles

This system **drives physical devices**, so its priorities differ from those of an ordinary back-office system:

| Priority | Principle | How it shows up |
|---|---|---|
| 1 | **Do no harm** | It would rather stop and hand over to an operator than guess its way forward (`skipped` always stops, an unregistered error code always goes to an operator, a broken expression takes the default branch instead of picking something arbitrary) |
| 2 | **Traceable** | Every action, every command and every raw response is written to the database; every configuration change is audited |
| 3 | **Recoverable** | A pause can be resumed, a crash can be resumed, and a resume does not repeat an action that already completed |

Principle 1 often conflicts with principle 3 (e.g. "a resume has to skip the completed nodes" against "skipping means the plate position is not trustworthy"); the arbitration in the case of a conflict is written down in D2/D4.

---

## D1. The recovery scope is a **property of the error code**, not something written at the throw site

**Background**: originally `DriverStepExecutor` threw `RecoveryScope.NODE` for every failure. But the database was configured with a different layer per error kind:

| Code | Configured in the database | Thrown by the code | Result |
|---|---|---|---|
| `EC-05-1006` communication failure | `command` / retry ×5 | `node` | **No match**, it falls through to the wildcard → one network hiccup and it goes to an operator |
| `EC-06-0001` driver not loaded | `run` / abort | `device` | Same, no match |

**Choice**: add a `scope` column to `error_code`; `DriverStepExecutor.scopeOf(code)` becomes the single exit point (lazy load + prefix fallback), and all five throw sites are changed to use it.

**The backfill does not get a separate "table of intentions"**: take the `recovery_policy.scope` of that code directly — the layer that was chosen when the policy was written is the real intention.

**The alternatives that were given up**

- Deciding by prefix in the code (`EC-05-*` → command): good enough in the short term, but over time it is bound to diverge from the configuration in the database (this has already happened twice).
- Adding a parameter to every throw site: the caller easily passes the wrong one, and it is easily forgotten when a new error code is added.

**Cost**: one extra lazy-load query (cached inside the process); `error_code.scope` and `recovery_policy.scope` may disagree (the backfill takes the policy as the authority, so changing the scope of the code afterwards does not affect the existing policies).

---

## D2. `skipped` and `bypassed` must be kept apart

**Background**: an XOR gateway has selected one branch. What should the nodes on the other branch be?

**Choice**: add `bypassed` (the branch was not selected), **do not reuse** `skipped`.

**Why**: the two have opposite meanings.

| Status | Meaning | What the system should do |
|---|---|---|
| `skipped` | This step **should have been done but was not**, the position of the plate is no longer trustworthy | **Stop immediately and hand over to an operator** (carrying on would let the later "fetch the plate" reach into an empty position) |
| `bypassed` | This branch **was never to be taken** | Carry on normally, the plate position is unaffected |

If, to save effort, the branch that was not taken were marked as `skipped`, the whole job would be judged "the plate position is not trustworthy" and would stop and wait for a human — completely the wrong behaviour.

**Implementation detail**: the business nodes that are still `running` are marked `bypassed` **only after the whole job has finished successfully**.
The exclusion condition has to cover `MANUAL / ABORTED / SKIPPED / aborted()` ——
`SKIPPED` makes the whole job end up as `paused/waiting_human`, and the downstream nodes of such a job are "not their turn yet",
not "the branch was not selected" (the first version missed `SKIPPED`; the 2026-11-03 review H2 pointed it out, and it has been added).

**Cost**: the `node_status` enumeration has one more value and every switch / mapping has to follow (already done: two colour mappings on the frontend, two status mappings on the backend).

---

## D3. Graph execution is **logical parallelism**, not thread parallelism

**Background**: the semantics of a BPMN parallel gateway are "both branches go at the same time".

**Choice**: all the ready nodes queue up and are **executed one after another**; parallelism only means "all of them can be advanced", it does not require wall-clock overlap.

**Why**:

1. The devices are shared resources. True parallelism means competing for locks, and competing for locks means deadlock, and the mutual exclusion of the robot arm is physical (only one plate can be gripped at a time).
2. "One at a time" is reproducible while debugging; the problems of true parallelism (A stuck waiting for B, B stuck waiting for A) are collisions on the real machine.
3. The bottleneck on site is the mechanical travel (seconds), not the scheduling of the control console (milliseconds).

**Given up**: executing the nodes on a thread pool — small gain, big risk.

**Cost**: two branches that should have overlapped (e.g. two centrifuges spinning at the same time) actually run in series, so the total duration is the sum. This cost is compensated for by "cross-experiment parallelism" in the design document `docs/多实验并行调度-设计.md`, not here.

---

## D4. Resume-from-breakpoint = delete the rows of the unfinished nodes + skipKeys

**Background**: clicking "Continue" used to set the run back to `queued`, and then nobody picked it up → it queued forever (on site this showed up as "I replaced the paper, clicked continue, nothing happened").

**Choice**:

1. Read the `node_key`s of this run that are already `completed` as the `skipKeys`
2. **Delete the rows of the "unfinished business nodes" and their step rows**: first delete the `level=1` ones (those whose `parent_node_id` points at those business nodes),
   then the `level=0` ones. The `run_command`s hang off the step rows (FK `ON DELETE CASCADE`), so they disappear together with the step rows
3. The step numbers are **continued** from `max(S<number>)` in the database (they must not be numbered from the start, otherwise `uk_run_node` is hit)
4. The run is set to `running` and executed again in the background with **the same runId**; `GraphRunner.withSkipKeys` skips the completed ones

**Why the rows are deleted**: if they are not deleted, the node is executed again and `INSERT`ed again, hitting the unique key `(run_id, node_key, attempt)` → the whole job fails. This trap really happened (it is unavoidable with a resume over several nodes).

**Fixed along the way**: `createNodeShells` became idempotent (it reuses the id if it already exists), so that `execute()` can be re-entered for the same run — the replay of `StartupRecovery` also goes this way.

**Given up**: creating a new run to "carry on" — that loses the completeness of "this execution", and in the reports one experiment would become two unrelated records.

**Cost**: deleting the rows means losing the **unfinished nodes** of "the failed attempt" together with their command records (the records of the completed nodes are kept). If the scene has to be kept in the future, it has to be archived before the deletion.

> The 2026-11-03 review H1 pointed out: the first version only deleted the `level=0` rows, and the step rows pointed at the business nodes only through the bare field `parent_node_id`,
> **without a foreign key**, so they could not be deleted; and on resume the step numbers started again at `S1`, hitting the unique key and being caught as a WARN ——
> the result was "the resume sent the commands, the database has no record", while every assertion was still green. It has been fixed as in 2/3 above,
> and 3 assertions were added to `verify-device-chain.sh` to pin it down (step row count / command row count / no conflict).

---

## D5. Crash recovery only lists the last 7 days, and it must be possible to "dismiss" them

**Background**: `/api/runs/recoverable` hangs 41 entries, most of them left behind by restarting again and again while debugging. What is more dangerous: **clicking "Continue" on a job from ten-odd days ago really re-runs it**, while the device state, the plate positions and the recipes have all changed — that is not a recovery, it is the creation of an incident.

**Choice**:

- List only the ones whose `interruptedAt` (or `actual_end`) is **within the last 7 days**
- Add `POST /api/runs/{id}/dismiss`: when a person judges that "this one should not be resumed", it is explicitly dismissed
- The cleanup **changes a field** (`input.recoverable=false`), it does not delete the record

**Given up**: forbidding every resume (loses the capability); or allowing every one of them (dangerous). 7 days is an empirical value that is "good enough and not enough to cause an incident", and it is configurable.

**Cost**: a "the device has been removed" case inside the 7-day window still needs a manual dismiss; this action cannot be undone (but it can be changed back with SQL).

---

## D6. The condition expression parser is written by hand, **no eval**

**Background**: the `conditionExpression` of a BPMN is user input.

**Choice**: a hand-written recursive-descent parser that only accepts `comparison / logic / parentheses / number / string / ${variable}`. One character more and it throws.

**Why**:

1. `ScriptEngine` / `eval` is the same as handing over the industrial PC — a single `Runtime.getRuntime().exec()` breaks through the whole machine.
2. The expressive power needed is very low (has the temperature been reached, are there enough plates), a full language is not necessary.

**Behaviour on failure**: **it does not block production** — it records `unevaluatedConditionCount` + a log entry and then takes the default edge. The reason: a broken expression is a configuration error, it should not stop the whole production line; but it must leave a trace (the preflight `PF-09` also reports it in advance).

**Given up**: an off-the-shelf expression library (GraalJS/SpEL) — a heavy dependency, and it comes with file / network access by default, so narrowing it is harder than writing it yourself.

**Cost**: no functions and no regular expressions; the type rules have to be defined by hand (e.g. `'10' > '9'` is compared numerically, to avoid the lexicographic trap).

> On 2026-11-03 (M7) arithmetic was added: `+ - * /`, the precedence of parentheses, the unary minus.
> It is still a purely hand-written parser, **eval has not been introduced**; a string taking part in arithmetic and a division by 0 always throw (the semantics are not guessed).

---

## D7. Loops: re-entry is only allowed when **the graph really has a cycle**, and there are two safeguards

**Background**: `GraphRunner` deduplicates with a `done` set, so a back edge never executes a second time — a loop cannot be written.

**Choice**:

1. At startup, a DFS decides whether the graph has a cycle (`hasCycle`)
2. **Only a graph with a cycle** allows a node to be entered again; an acyclic graph keeps the deduplication semantics
3. Two safeguards: at most 10 iterations per node (`workflow.attr.maxIterations` is configurable), 500 total steps

**Why re-entry is not simply allowed**: a parallel join point is hit by several edges, and the same node can be "activated again". Unguarded re-entry makes a device action run twice — that is a physical side effect (sealing twice, moving the plate twice).

**Given up**: the BPMN standard `standardLoopCharacteristics` — the graphs do not have it, and it cannot express "go back to any predecessor node on a condition".

**Cost**: the total step cap is a global guess.

> On 2026-11-03 (M6) the "one row per iteration" was added: `run_node.loop_index` 0 = the summary row (the final status), 1..N = the actual execution of each iteration,
> and the unique key became `(run_id, node_key, attempt, loop_index)`. The existing queries all add `loop_index = 0`,
> which is equivalent to before the change; the run detail expands the iteration rows under the summary row.
> **The summary row is not "iteration 0"**, it is "the final result of this node" — this is stated both in the documentation and in the code comments.

---

## D8. Node output → where the value comes from

**Background**: for a loop to "run enough times and then exit", it needs a variable that changes; a static variable cannot do that.

**Choice**: after every successful task, merge the fields of the **raw device response** into the variable table.

Two traps (both found the hard way):

1. The `run_command` hangs off the **step node** (`level=1`, `node_key=S1`), the business node is its `parent_node_id`. A query that filters on `node_key=? AND level=0` alone **never finds anything**.
2. `run_command.result` only holds `{ok,durationMs}`; the real business fields of the device are in `device_raw_response`, and that is an escaped JSON string.

**Given up**: having `DriverStepExecutor` pass the result back to the GraphRunner (architecturally cleaner, but it changes the executor signature and carries more risk). The current approach is "one more small query", measured at <1 ms / node.

**Cost**: a regex pulls out the top-level scalars, nested structures / arrays cannot be read; high-frequency nodes carry the extra query overhead.

---

## D9. The loop cap sits at the **workflow level**, not at the gateway level

**Choice**: `workflow.attr.maxIterations` (default 10).

**Why**: a workflow usually has more than one back edge, and configuring it per gateway leaves operations unable to remember "which one governs this". Put on the workflow, it can be said in one sentence.

**Cost**: several back edges of the same workflow share one cap. It is already recorded in the technical debt (to configure it per gateway, `GNode` first needs an attribute field).

---

## D10. Experiment orchestration (list) and the experiment canvas (graph) are **two views of the same data**

**Background**: in the workflows in the database `run_mode` is `sequential` for 99.7% (recorded in the comment of `ExperimentCanvas.tsx`). Note that in the current environment `experiment_process` has only 13 rows — the 659 historical experiments only filled in `experiment.workflow_id`, they did not go through the new table.

**Choice**: two editing forms, both reading and writing `experiment_process` (the list) and `experiment_graph` (the canvas).

**Why**: for a sequential scenario the list is faster and less easy to wire up wrongly; the graph is only needed when there are branches / parallelism. Forcing everybody to use the canvas would reduce efficiency instead.

**Given up**: only the canvas (heavy); only the list (cannot express parallelism).

**Cost**: the consistency of the two views has to be maintained (currently the list is the "authority", and when the canvas is saved it writes the node graph into `experiment_graph`; the two sides do not synchronise automatically).

---

## D11. The flowchart of the run record uses the **Viewer** (read-only), not the Modeler

**Choice**: `bpmn-js/lib/Viewer`.

**Why**: the run detail is a "historical record". Allowing edits on it causes the misunderstanding "I changed the graph, why did this execution not change"; worse, someone will think that changing the graph can repair a running job.

**Cost**: wanting to "change the workflow on the basis of this failure" in the run detail costs one extra click (jump to the designer).

---

## D12. The device methods are persisted (`device_method`), and the **existing methods are not deleted**

**Background**: the "Details" of the UI reads `device_method`, while `DeviceRegistrar` used to write only the `device` row → the method list was always empty.

**Choice**: when a device enters the pool, upsert the methods of the model library into `device_method`; **do not delete** the methods that are no longer in the model library.

**Why**: a method may be referenced by a workflow, and deleting it would make those workflows report "method does not exist" as soon as they are opened. Adding and updating only is safer.

**Cost**: after a method is deleted from the model library, "orphan methods" are left in the database (they need a periodic manual comparison, or a "mark as deprecated" field).

---

## D13. The heartbeat thresholds are configurable, and the offline threshold follows the interval; they can now also be **changed on the page and take effect immediately**

**Background**: originally a `10_000L` constant + a hard-coded `30_000L`. The problem is not only that they could not be configured — **when the interval changed, the threshold did not follow**:
probe every 60 seconds, declare offline after 30 seconds, and every device is declared offline once before every probe.
A second problem was found later: you could only change the environment variables and restart the API. Tuning "how long without a response counts as offline" is a daily job on site,
and a restart interrupts the experiments that are running.

**Choice (in two steps)**:

1. First make it configurable: `LABOS_HEARTBEAT_INTERVAL_MS` (default 10s), `LABOS_OFFLINE_ALERT_MS` (default `max(3×interval, 30s)`), an illegal value falls back to the default.
2. Then upgrade it to **runtime configuration**: `sys_setting` persistence + an in-process static field (`RuntimeSettings`),
   the heartbeat thread reads the memory in every cycle, and saving on the page takes effect immediately; the offline threshold is forced to `≥ 2×interval` (clamped by the backend, not only by a frontend hint).

**Given up**:

- Querying the database on every probe — one more round trip, and the jitter of the database would drag the heartbeat down;
- Keeping only the environment variables — a restart is needed before it takes effect.

**Cost**:

- The configuration exists in two places (database + memory), and when the in-process value disagrees with the database **the memory wins**; after a restart it falls back to the database value (already stated in the code comments);
- The environment variables are still the fallback, and the two sources have to be described together in §7 of the technical document.

---

## D14. One error code × one scope = one policy

**Background**: at first "multiple policies" was to be made into a **fall-back chain** (try retry first, then reassign…).

**Why it was rejected**: at runtime `RecoveryPolicyResolver` sorts by `specificity → priority` and then takes the **`findFirst`**. Of two policies with the same code and scope, only the one with the lower priority is ever used; the other is **dead configuration**. Making it a fall-back chain would mean changing the runtime semantics, which is risky.

**The real need for multiple policies** is "the same code handled differently at different scopes":

```
EC-05-1006 (communication failure)
  command → retry ×5     resend the single command
  node    → manual ×1    do not push it at the node layer, call a human
```

**Choice**: unique by `(error_code_id, scope)`; the UI lists them by scope, **the entry of the current scope gets a blue border**.

**Cost**: "several levels of policy for the same scope" (retry 3 times and then switch device if it still fails) cannot be done for now — that needs the scope matching of a `reassign` policy to be written correctly, and it belongs to the next phase.

---

## D15. Built-in nodes use the `__` prefix of `protocolName`, no new BPMN element is added

**Background**: a workflow has to be able to "compute something" — a loop that increments itself, the upstream result turned into the value a condition needs. BPMN has no ready-made
"variable node", and bpmn-js does not recognise custom element types either (a custom palette would have to be maintained on top).

**Choice**: the node stays a `bpmn:task`, marked as built-in with `protocolName="__setVar" / "__expr"`;
the parameters reuse `workflow_node.params->'method_params'`.

**Why this is the cheapest**:

- The parser only needs one more condition (`__` prefix → `Kind.BUILTIN`), the BPMN element model does not change;
- The designer opens / saves it normally (the attributes are ordinary XML attributes), no custom palette is needed;
- The executor computes it inline in the `GraphRunner`, **it does not enter the state machine and produces no device command** — a built-in node has no retry semantics.

**Failure semantics**: the expression cannot be evaluated (written wrong, variable missing) → record `MANUAL` + `EC-BUILTIN-001` and **stop**.
It must not carry on with a wrong value, otherwise the conditional branches afterwards would go on the wrong value.

**UI**: the "Node settings" card on the right of the designer (device task / variable node / expression node + parameters),
saving goes through `PUT /api/workflows/{id}/nodes/{key}`, and the server changes **both** `workflow_node.params` and
the one `protocolName` attribute in the XML, then the frontend re-imports the XML — the modeling of bpmn-js is not touched.

**Cost**: `workflow_node` has no `protocol_name` column (the method used to come from the XML), so "what is this method called"
can only be seen in the XML; `builtin=true` is written into params to make the list and the troubleshooting easier.

---

## 3. The boundary: what is explicitly not done

| Not done | Reason |
|---|---|
| No thread-level parallel execution | See D3 |
| Not all BPMN elements (timer / message / subprocess) | Only the subset that can "get the job done"; the rest is ignored while parsing and reported in the preflight |
| No cross-experiment scheduling optimisation inside the control console | See `docs/多实验并行调度-设计.md` |
| No recipe / reagent / sample management | The tables are placeholders, not wired up |
| No strong multi-tenant isolation | `tenant_id` exists in every table, but only a single tenant has ever been run; isolation security cannot be promised |
| No in-house frontend framework / state library | antd + react-query is enough |

---

## 4. Summary of the risks and the trade-offs

| Risk | Trigger | How it is covered today | Residual |
|---|---|---|---|
| A scope configured wrongly makes the policy ineffective | `scope` is forgotten when a new error code is added | Default backfill + preflight | No automatic validation of "the policy scope disagrees with the code scope" |
| The resume repeats the execution | skipKeys is computed wrongly, or a node was renamed | Delete the unfinished rows + the unique key | No unit test covers "resume after a node was renamed" |
| An infinite loop | The back edge is wrong, the condition is always true | Iteration cap + total step cap | Triggering the cap is a "silent skip", it is only in the log |
| The graph and the database disagree | SQL changed by hand, or the save failed | Preflight + version history | No "database vs graph" consistency check task |
| Single-process in-memory state | Multi-instance deployment | The documentation states that only a single instance is supported | No distributed lock |
| The manual is missing (360R/PlateLoc) | When onboarding the real device | Nothing can be done but wait for the manual | — |

---

## 5. If I had to refactor, this is where I would start

| Priority | What to change | Why | Risk |
|---|---|---|---|
| 1 | Split `ApiServer` (2,700 lines) into controllers + a routing table | Adding an endpoint today means changing three places (pattern / handler / help text) | Medium (pure moving, but a wide surface) |
| 2 | ~~Add `loop_index` to `run_node`, so that every loop iteration has its own row~~ **Done (M6)** | The intermediate states were invisible → the N-th iteration can now be replayed (the summary row has `loop_index=0`) | Landed (`run_node.loop_index` + a 4-column unique key) |
| 3 | Unify the state: the mapping from the database state to the display state is kept in one place only | Today the frontend and the backend each have a mapping, and they drift apart easily | Low |
| 4 | The expressions support arithmetic | On site somebody will want to write `${a}+1 > 3` | Low (one more layer in the parser) |

---
