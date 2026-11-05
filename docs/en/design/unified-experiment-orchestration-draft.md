# Unified Experiment Orchestration · Design draft v0.2 (second review passed 2026-11-03; S1/S2-A implemented, S3 in progress)

> **English edition.** The Chinese original is [`docs/设计-实验编排统一-v0.1-草稿.md`](../../设计-实验编排统一-v0.1-草稿.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> Status: **second review passed (2026-11-03 15:36, see §7 of the review record)**, implementation has started:
> S1 (the canvas save compiles into the database, `86ef1c1`) and S2-A (the partial order between workflows, `ae06025`) are live,
> S3 (batched execution) is in progress; this file remains the authoritative record of the design position and the tradeoffs.
>
> This is written to lay out the four questions and to put the options and their costs on the table,
> rather than to write the code first and explain it afterwards.
>
> The corresponding questions (raised by the user on 2026-11-03):
> 1. Should `experiment_graph` and `experiment_process` be unified? Who has the last word?
> 2. Multi-workflow orchestration inside an experiment and multi-device orchestration inside a workflow — can they share one canvas framework?
> 3. How is the priority between workflows decided? Do we want "experiments nested inside experiments"?
> 4. An experiment has many samples and the device can only handle one batch at a time — how is "batching" expressed?
>
> Suggested reading order: §0 the conclusion → §1 the evidence of the current state → §2 the question-by-question discussion (with the options and their costs) → §7 the open questions → §8 the notes for the review.

---

## 0. The conclusion at a glance (my recommendation, none of it implemented yet)

| # | Question | Recommendation | Confidence |
|---|---|---|---|
| 1 | Unify graph and process | **process is the single source of truth**; the graph only keeps the layout (coordinates / line styles) and is **parsed into process and written to the database** when the canvas is saved; the table becomes a read-only mirror | High |
| 2 | Share one canvas framework | **In two steps**: in the short term keep the two editors and "compile" the canvas into process; in the medium term evaluate whether the experiment should also become BPMN (`callActivity`) | Medium (the second step deserves another review) |
| 3 | Priority between workflows / nesting | **Use a partial order (DAG) + priority**, and **do not nest sub-experiments**; the real need behind "nesting" is met by "batches" and "templates" | Medium-high |
| 4 | Batching | Add `experiment_batch + experiment_batch_run`; **one experiment = several logical batches + several attempts per batch**; reuse the existing "expansion" idea, do not build a new executor | Medium |

---

## 1. The current state: the evidence first, not impressions

### 1.1 Two models, only one of them is executed

| Table | Who writes it | Who reads it (execution) | Conclusion |
|---|---|---|---|
| `experiment_process` (seq / workflow_id / loop_count / params_override) | The "workflow orchestration table" of the experiment detail, the API | `ExperimentExecutor.expand()` **expands and executes by seq** | **The only structure that is executed** |
| `experiment_graph` (graph jsonb: nodes / edges) | Only the canvas (`GET/PUT /api/experiments/{id}/graph`) | **Nobody reads it** | The canvas is currently a dead end |

Code evidence:

```
ExperimentExecutor.java:112   FROM experiment_process ... ORDER BY seq
QueryService.java:2139         SELECT graph FROM experiment_graph      ← only the canvas API
QueryService.java:2162         INSERT INTO experiment_graph ...
```

**So the "canvas orchestration" of today does not affect the execution once it is drawn** — if the user reorders on the canvas and then runs it, the order that runs is still the order in the table.
This is the first problem that has to be fixed (it is not "whether to unify", it is "they are already inconsistent").

### 1.2 The two "orchestrations" are actually two levels

| | Workflow | Experiment |
|---|---|---|
| What is drawn | A sequence of actions on one or several devices (BPMN task + gateway) | The order, variables, branches and loops between several workflows |
| Editor | bpmn-js (`/designer/:id`) | ReactFlow (`/experiments/:id/canvas`) |
| Storage | `workflow.graph->>'xml'` | `experiment_graph.graph` (jsonb) |
| Executor | `GraphRunner` | `ExperimentExecutor` (**runs linearly by seq only**) |
| Semantics supported | Branch / join / loop / variables | The canvas can draw branches / parallelism / loops, **the executor supports none of them** |

In other words: **the semantics the canvas can draw > the semantics the executor can run**. This is not "we will add it later"; drawing them is misleading.

### 1.3 There is already a precedent of "drawn but not executed"

`experiment_process.run_after` (int, default 0) is in the table and is returned by the API,
but `ExperimentExecutor` **never reads it**. This is the typical case of "the model comes first, the execution does not keep up":
the mere existence of the field makes people think "if I fill it in, it will wait for the previous step to finish".

> The lesson for the design: **do not add a field in advance just to "look complete"**. Every field that is not executed
> is a false premise planted for the next person.

---

## 2. Question 1: how to unify `experiment_graph` and `experiment_process`

### 2.1 The three options

| Option | Approach | Advantages | Costs |
|---|---|---|---|
| **A. process is authoritative, the graph only stores the layout** (recommended) | Canvas save → parse into process rows (seq / order) + write the layout back into the graph | A single execution model; the canvas goes back to its proper job as the "editor"; the table can be validated | You have to write a "graph → sequence" compiler; a DAG cannot be expressed in the graph for the time being |
| B. The graph is authoritative, process is a view | Change the executor to read the graph | The canvas semantics are complete (branches / parallelism can all run) | The executor + scheduling have to be rewritten; the loops / parameter overrides that already exist on process have to be migrated; high risk |
| C. Dual write | Change both sides | Cheap in the short term | **It will inevitably drift** (which is exactly the situation today), rejected |

### 2.2 The rules for recommending A

1. **The canvas is the only editing entry point** (topological changes: add / delete / connect)
2. **Compile on save**: traverse the nodes in topological order → generate `experiment_process` (seq starting at 1)
   - a `workflow` node → one row, the `workflow_id` taken from the node
   - a `loop` node → write `loop_count` on the workflow rows it wraps
   - a `variable` node → written into the experiment variable table (the existing `experiment_variable`)
3. **The table is read-only**: the workflow table on the detail page no longer offers add / delete / change, it only shows the "generated by the canvas" sequence + a 「Edit in the canvas」 button
   - Reason: `seq` is a linear quantity, it **cannot express a DAG**. As long as reordering inside the table is still allowed, the conflict "linear in the table, with branches in the graph" will appear
4. **A failed compilation has to reject the save**: the graph has a cycle, an isolated node, or a workflow node with no workflow bound → 400 + point at which node it is
5. **The single source of the order**: the compilation result; the `x/y` of a node in the graph is only layout and takes no part in the semantics

### 2.3 The boundary of this route (an honest statement)

- The canvas supports `fork/parallel` today, but `experiment_process` + `ExperimentExecutor` **can only run linearly**.
  Until the DAG of Q3 is in place, **a graph with branches can only be compiled down to "the linear order from left to right on the canvas"**.
  So the first stage either:
  - (a) disables fork/parallel on the canvas (it would not run correctly anyway, so it is simply not offered), or
  - (b) allows drawing but explicitly warns at save time "currently executed linearly, the branches do not take effect yet"
- I lean towards **(a)**: semantics that cannot be executed should not be put on the table (consistent with the lesson of §1.3).

---

## 3. Question 2: can one canvas framework be shared

### 3.1 The two levels are in fact isomorphic

Strip away the form and the elements of the two correspond almost one-to-one:

| Concept | Workflow (BPMN) | Experiment (ReactFlow) |
|---|---|---|
| A named step | `bpmn:task` | a `workflow` node |
| Order | `sequenceFlow` | an edge |
| Branch | `exclusiveGateway` | `fork` |
| Parallelism | `parallelGateway` | `parallel` + `join` |
| Loop | a back edge + `maxIterations` | a `loop` node |
| Variables | the `__setVar` / `__expr` built-in nodes | a `variable` node |

**Conclusion: the structures are isomorphic, only the granularity differs** (a task of the workflow = a device action, a workflow node of the experiment = an entire workflow).

### 3.2 The three options

| Option | Approach | Advantages | Costs | When it fits |
|---|---|---|---|---|
| **A. Use BPMN for everything** | The experiment also stores BPMN XML; the workflow node uses `bpmn:callActivity` (`calledElement=workflow:{id}`); `GraphRunner` supports "calling a sub-workflow" | **One parser / executor / state colouring / test suite**; both levels use the same editor | The experiment canvas has to be migrated (5 records today); `callActivity` has to be added to `TYPE_MAP` and the executor has to call recursively; the ReactFlow page is retired | When you want "one engine" |
| **B. Two editors, the canvas is compiled into process** (recommended in the short term) | Keep ReactFlow for the experiment orchestration; only add "compile into the database" (option A of Q1) | Small change (1–2 days); low risk; it removes "the canvas does not take effect" straight away | It is still two models; two sets of semantics to maintain in the long run | Now |
| C. Use ReactFlow for everything | The workflow also switches to ReactFlow | A unified frontend | The BPMN parsing / execution / import-export has to be rewritten; all the mature capability of bpmn-js is lost | Not recommended |

### 3.3 My judgement (uncertain, I would like the review to challenge it)

**B first, A afterwards**, with the criteria written down:

- The benefit of doing A now is not obvious: **at the experiment level there are only 5 records, and nobody really depends on branches or parallelism yet** (the executor does not support them either)
- But A is the only route that removes the "two executors". `GraphRunner` and `ExperimentExecutor`
  have already shown the first signs of the semantics drifting apart (the `run_after` field, the semantics of `bypassed`, the loop iteration count)
- **Trigger condition**: when the DAG of Q3 is actually implemented, the marginal cost of A is the lowest (the executor has to be touched anyway),
  and doing it together is better value than doing it on its own now

> So my recommendation is: **land Q1 first (compile into the database, table read-only), and do the option A of Q2 and the DAG of Q3 together as one milestone**.

---

## 4. Question 3: the priority between workflows, and whether to nest sub-experiments

### 4.1 How the order is decided today

`experiment_process.seq` ascending, **strictly linear**. No priority field takes part in the scheduling
(`priority` is on `experiment`; it is the queueing priority "between experiments", not "between workflows").

### 4.2 The three options

| Option | Expresses | What it can do | What it cannot do |
|---|---|---|---|
| **A. Purely linear** (the current state) | `seq` | Execute one after another | Parallelism, conditional skipping, re-running only a segment after a failure |
| **B. DAG + priority** (recommended) | `edges(from,to)` + a node `priority` | A partial order: what can run in parallel runs in parallel, the critical path goes first; after a failure only the downstream is re-run | The executor has to support concurrent dispatch + join waiting |
| C. Nested sub-experiments | `experiment` self-reference `parent_id` | Reuse "a set of workflows" | Lock ownership, variable scope, cancellation propagation and report aggregation all become twice as complex |

### 4.3 Why B is recommended instead of C

The "nesting" the user mentions — I understand the real need as two things:

1. **Reuse**: the same set of workflows, run once more with a different sample → that is a **template / batch** (Q4), not nesting
2. **Grouping**: dividing one experiment into several phases (pre-processing / on-machine / analysis) → that is **a partial order in the DAG + grouping labels**, not nesting

The problems nesting would introduce (each of them needs a separate design):
- Who owns the "resource lock" of a sub-experiment? If the parent experiment has locked the robot arm, does the sub-experiment inherit it?
- Variable scope: can a sub-experiment change the parent's variables?
- Cancellation / pause: if the parent experiment is paused, do the sub-experiments stop with it or continue?
- Reporting: is the success rate of one execution counted at the parent or at the child?

**Conclusion: no nesting.** Use the DAG to express order and parallelism, use "grouping labels (phase)" for the phase visualisation, and use batches for reuse.

### 4.4 What the DAG needs (draft)

```sql
-- The partial order between the workflows of one experiment (replaces / supplements seq)
ALTER TABLE experiment_process ADD COLUMN priority smallint NOT NULL DEFAULT 5;  -- who runs first when they compete on the same level
CREATE TABLE experiment_process_edge (
  tenant_id   bigint NOT NULL,
  experiment_id bigint NOT NULL,
  from_id     bigint NOT NULL REFERENCES experiment_process(id) ON DELETE CASCADE,
  to_id       bigint NOT NULL REFERENCES experiment_process(id) ON DELETE CASCADE,
  PRIMARY KEY (from_id, to_id)
);
```

Execution semantics:
- A node with an in-degree of 0 can be dispatched; several can run at the same time (but **whether real wall-clock concurrency is allowed**
  depends on the resource model; you cannot look at `DeviceLock` alone)
- Join: it only continues when all the predecessors are done (equivalent to the parallel join of BPMN)
- Failure: by default it **stops the whole order** (as today); the optional "skip the failed branch and continue" is another switch and is not built for now

> One premise has to be closed off here: `DeviceLock` **can only guarantee the mutual exclusion of a single device**; it cannot prove
> that "the DAG concurrency inside one experiment is safe as a whole". If S2 is going to do **real concurrent dispatch** (two branches overlapping in wall-clock time),
> the existing **three-layer resource model + wait-for graph** scheme (position / port / robot arm) has to be brought in at the same time,
> otherwise multi-resource deadlocks of the kind "A holds a position and waits for the robot arm, B holds the robot arm and waits for the target position, C then closes the loop" can still occur.
>
> So S2 has two possible landing positions:
> 1. **The conservative version**: logically parallel, physically serial — only the partial order is computed, and the multiple branches are not allowed to really compete for resources at the same time;
> 2. **The complete version**: real concurrency + three resource layers + the wait-for graph rejects cycles.
>
> My inclination at the moment: **if S2 does not bring the deadlock governance with it, we can only claim "supports a partial order", and not claim "supports parallel execution"**.

---

## 5. Question 4: an experiment has many samples — how to batch them

### 5.1 Breaking the scenario down

> One experiment has 96 samples and the device can only handle 24 at a time (or one plate), so it has to be split into 4 batches.

Three possible landing points:

| Option | Granularity | Expresses | Advantages | Costs |
|---|---|---|---|---|
| A. An experiment-level batch table | `experiment_batch` (batch_no, sample set, status) | N batches per experiment, one run per batch | Good observability; a failure only re-runs one batch; the report can be read per batch | One more table; the states have to be aggregated |
| B. Workflow-level batch parameters | The workflow accepts `batchSize` and loops internally | One run handles several batches | Few runs | The granularity of the failure re-run is coarse; a single run takes too long; the progress is hard to display |
| **C. Expand into several runs** (recommended, a simplification of A) | Reuse the "expansion" idea of M6: at compile time expand `batch_split` into N runs | No new executor; one run row per batch | Reuses the existing run/run_node/progress/failure re-run | N batches = N runs, the list gets longer (it can be grouped by the parent experiment) |

### 5.2 The recommended approach

1. `experiment_process` gains `batch_split` (`null` = no batching; `24` = at most 24 samples per batch)
2. At compile / execution time the sample set is split into N batches → **one run per batch**
3. But **a batch and a run cannot be covered one-to-one**: when a batch fails and is re-run, the history has to be preserved
4. So the data has to be split into two layers:
   - `experiment_batch` = the logical batch (which batch, the sample set, the current aggregated state)
   - `experiment_batch_run` = every actual run of that batch (`run_id / attempt_no / status`)
5. The experiment state = the aggregation of the logical batches (all succeeded = success; any failed = failed, but **the remaining batches can continue**)
6. Re-running after a failure: create a new `experiment_batch_run`, re-run only the failed batch, and do not overwrite the historical `run_id`
7. Variable scope: the experiment-level variables are shared globally; the batch variables (e.g. the sample list of the current batch) are injected at the start of each batch run

### 5.3 What I have not thought through

- **Should the batches be serial?** There is only one device, so in theory the device lock serialises them automatically; but if the batches need "changing the plate / clearing the area" in between,
  an explicit "preparation between batches" step is needed — there is no model for that yet
- **How do the samples get attached to a batch?** Today `experiment_sample` is an experiment-level association table and has no "which batch does it belong to" field
- **At which layer does the batch membership live?** Does `experiment_run` carry `batch_no/batch_id` itself, or does a separate
  `experiment_batch_run` associate the run? I lean towards the latter at the moment, because it naturally supports "several attempts on the same batch".

---

## 6. Summary of the data-model changes (draft, not implemented)

```sql
-- Q2/Q3: the partial order inside one experiment
ALTER TABLE experiment_process ADD COLUMN priority smallint NOT NULL DEFAULT 5;
ALTER TABLE experiment_process ADD COLUMN batch_split int;      -- how many samples at most per batch
CREATE TABLE experiment_process_edge (...);                     -- see §4.4

-- Q4: batches
CREATE TABLE experiment_batch (
  id bigint PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
  tenant_id bigint NOT NULL,
  experiment_id bigint NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
  batch_no int NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'pending',
  UNIQUE (experiment_id, batch_no)
);
CREATE TABLE experiment_batch_run (
  id bigint PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
  tenant_id bigint NOT NULL,
  batch_id bigint NOT NULL REFERENCES experiment_batch(id) ON DELETE CASCADE,
  run_id bigint NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
  attempt_no int NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'pending',
  UNIQUE (batch_id, attempt_no),
  UNIQUE (run_id)
);
ALTER TABLE experiment_sample ADD COLUMN batch_no int;

-- Q1: the graph is demoted to layout
COMMENT ON COLUMN experiment_graph.graph IS '仅布局（节点坐标/连线样式）；语义以 experiment_process 为准';
```

---

## 7. The migration path (three steps, each can go live on its own)

| Step | Content | Deliverable | Estimate |
|---|---|---|---|
| **S1** | Add a **single save entry point**: when the canvas is saved, write `experiment_graph` + the compiled `experiment_process` + `experiment.version` in one transaction; the table becomes read-only; branches / parallelism are disabled in the canvas | It really removes "the canvas does not take effect" and the split of the dual write | 1–2 days |
| **S2** | The DAG: `experiment_process_edge` + the executor dispatches by partial order + join waiting; if real concurrency is done, the **three resource layers + the wait-for graph are brought in at the same time**; the priority field takes effect | A partial order inside one experiment; whether real concurrency is possible depends on whether the resource governance is landed at the same time | 3–5 days |
| **S3** | Batches: `experiment_batch` + `experiment_batch_run` + expansion into several runs + a failure only re-runs that batch | Many samples in batches, and the re-run history is auditable | 2–3 days |
| Optional | The experiment also switches to BPMN (`callActivity`), the two executors become one | One engine | 1–2 weeks (cheaper if merged with S2) |

### 7.1 The DoD of S1 (added, to avoid another "double path")

1. The backend keeps **only one** write entry point for "saving the experiment orchestration"
2. That entry point completes the following **in the same transaction**:
   - save `experiment_graph`
   - compile and replace `experiment_process` as a whole
   - update `experiment.version`
3. The existing entry point that writes the workflow table directly is **write-closed** in the same version (at least the UI no longer exposes it, and the backend rejects external writes)
4. If any sub-step fails, everything is rolled back; "the graph was updated but the process was not", or the other way round, is not allowed
5. The regression tests cover at least:
   - a linear graph is saved successfully → `graph/process` are updated in sync
   - an illegal graph fails to save → neither side is written to the database
   - a simulated `process` write failure → `graph` is rolled back as well

---

## 8. Open questions (the review is asked to challenge these in particular)

1. **Does making the table read-only in S1 make the simple cases harder to use?** (to reorder a linear experiment you now have to open the canvas)
   —— I lean towards "allow reordering inside the table while it is linear, and make the table read-only when there are branches", but that makes the rules more complex and needs a trade-off.
2. **Is disabling fork/parallel on the canvas "honest" or "castration"?** Has any of the data that has already been drawn used it?
3. **Is the real benefit of choosing A for Q2 (BPMN unification)** worth 1–2 weeks? Or is two editors running in parallel in the long run acceptable?
4. **Does S2 do "a partial order" or "real concurrency"?**
   If it does real concurrency, does it bring in the three resource layers + the wait-for graph as well; if it does not, the claim has to be narrowed to
   "only a partial order is supported, no wall-clock concurrency is promised".
5. **Batches and the failure policy**: when one batch fails, do the remaining batches stop or continue? How is the work reported?
6. **The priority field**: is the semantics of `priority` "who runs first when they compete on the same level", or "preempting what is already running"? (the latter is not advised)

---

## 9. The prompt for the reviewing AI

> Please review `docs/设计-实验编排统一-v0.1-草稿.md`. This is a **draft** and the author has marked his own degree of confidence.
>
> Please focus on:
> 1. Whether the assessment of the current state in §1 holds (is `experiment_graph` really not executed by anyone, is `run_after` really unused)
> 2. Whether choosing A in §2 (process is authoritative) makes the "canvas semantics" so limited that they become unusable
> 3. Whether the reasons for refusing "nesting sub-experiments" in §4.3 are sufficient, and whether there is a real need that has been overlooked
> 4. Item 4 of §8 (can DAG concurrency + the device lock deadlock) — please give a concrete deadlock construction or a proof that it cannot happen
> 5. Whether the three migration steps (S1/S2/S3) can go live independently, and whether there is an implicit coupling that forces them to be changed together
>
> The code is authoritative on `main`; the file line numbers quoted in the text may change with the commits, so please search for them.

## Addendum (2026-11-05): where the designer's "drag a device / drag a method" belongs

The A/B/C discussion in §2 of this document is about **orchestration unification** (whether graph or process is authoritative); conclusion B, "keep two editors for now", still holds. A more local set of trade-offs arose in this round and is recorded here so it is not confused with §2:

| Option | Approach | Benefit | Cost | Outcome |
|---|---|---|---|---|
| A | Add a custom palette provider to the bpmn designer so devices appear in the official palette | The most "native" interaction | Needs custom element types and a moddle extension, plus extra compatibility when `BpmnExpander` turns a task into a subProcess | Rejected |
| B | Make the experiment canvas `/canvas` (React Flow) the official place for dragging devices | It already had an "island devices" list; smallest change | The user required the interaction inside the workflow designer; the semantics of the two editors would become further entangled | **Rejected by the user** (the former option 2) |
| C | Move everything to React Flow | A unified frontend | BPMN parsing / compilation / execution / import-export would have to be rewritten and mature bpmn-js features lost | Already judged not recommended in §2 |
| D | Keep the bpmn designer and add a separate "island → device → method" panel on the left with HTML5 drag & drop + `modeling.createShape` + a custom renderer | The execution chain and XML contract stay untouched; images scale and export with the SVG | `$attrs` read-only getter, no `getAbsoluteOrigin()`, the panel narrows the canvas and broke fixed e2e drop points | **Chosen** |

The full cost list and the product wording (a workflow is optionally bound to an island, cross-island devices remain allowed, a plate changing device inserts a robot arm) are in the 2026-11-05 row of `decision-log.md` and D16 of `review-20261103/03-design.md`.

There is also a dedicated handover document, [logic-nodes-by-layer.md](logic-nodes-by-layer.md): it records the conclusion "forks/parallel branches/loops/variables belong inside a workflow, the canvas only does cross-workflow orchestration", the six pieces of capability-mismatch evidence (including `compileExperimentGraph` accepting only three node kinds, `runFlow` skipping parallel, the palette having no parallel gateway, and two condition syntaxes) and the five-batch route. The "process is authoritative, B first then A" conclusion in §2 of this document is unchanged and does not conflict with it.
