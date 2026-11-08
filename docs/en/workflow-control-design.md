# Workflow Control: Conditional Branches, Loops, Variables (Technical Design)

> **English edition.** The Chinese original is [`docs/流程控制-技术设计.md`](../流程控制-技术设计.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> For: the people maintaining the execution engine
> Related: [Functional guide](workflow-control-guide.md) · [Test report](workflow-control-test-report.md) · [Development log](development-log.md)

---

## 1. The problem and the boundaries

Before the rework, `GraphRunner` handled gateways like this:

| Construct | Behaviour |
|---|---|
| `parallelGateway` | The fork activates all the outgoing edges; the join waits for all the incoming edges (AND) |
| `exclusiveGateway` | **It selects only one outgoing edge, but the condition is "print a sentence and then take the default"** |
| A back edge | `if (!done.contains(target))` in `activate()` → the second round is not enqueued, so the loop never happens |

So what was really missing were three things: **expression evaluation**, **letting `done` allow re-entry**, and **where the variables come from**.

Two lines were deliberately drawn in the design:

1. **No scripting engine**. The expressions come from the user's input in the workflow
   diagram, and a `ScriptEngine`/`eval` amounts to handing the industrial PC over.
2. **An evaluation failure does not block production**. A wrong condition → log it + take
   the default, instead of failing the whole run.
   The price is that "a wrong condition is not easy to spot", which is made up for with an
   `unevaluatedConditionCount` counter + the log.

---

## 2. The condition evaluator (`engine.ConditionEvaluator`)

### 2.1 The grammar

```
expr    := or
or      := and ('||' and)*
and     := cmp ('&&' cmp)*
cmp     := unary (('=='|'!='|'>='|'<='|'>'|'<') unary)?
unary   := '!' unary | primary
primary := number | string | 'true' | 'false' | '${variableName}' | '(' expr ')'
```

A recursive-descent implementation, about 300 lines, zero dependencies.

### 2.2 The type rules (this is where the pitfalls are easiest to dig)

| Scenario | Rule | Why |
|---|---|---|
| Both sides look like numbers | Compared as `double` | Otherwise `'10' > '9'` is judged false by lexicographic order |
| `==` / `!=` | Numerically for numbers, otherwise by string/boolean | This allows `'yes' == 'yes'` |
| Magnitude comparison | **Both sides must be numbers**, otherwise an exception is thrown | It does not guess the semantics of `'abc' > 1` |
| The variable does not exist | It evaluates to an empty string | `${missing} == ''` holds; `${missing} > 1` throws an exception and names the variable |

### 2.3 The safety boundary

- The lexer only accepts `number / string / true|false / ${...} / operator / parenthesis`
- Any superfluous character (`;`, a function call, `Runtime...`) → `IllegalArgumentException`,
  **it is not executed**
- The self-test has a dedicated injection case: `${a} == 'x' ; Runtime.getRuntime().exec('ls')` → refused

---

## 3. The changes to the execution engine (`workflow.GraphRunner`)

### 3.1 Conditional branches

```java
// GraphRunner.choose(outs, gatewayKey)
for (GEdge e : outs) {
    if (e.condition() == null || e.condition().isBlank()) continue;
    try {
        if (ConditionEvaluator.eval(e.condition(), variables)) { conditionHitCount++; return e; }
    } catch (RuntimeException ex) {
        unevaluatedConditionCount++;      // leave a trace, do not fail
        log("网关 %s 条件求值失败（%s），按默认边处理：%s", ...);
    }
}
// Reached here: the default edge → the first one in a stable sort (the logic is unchanged)
```

Two new counters (for acceptance / diagnostics): `conditionHitCount()`, `unevaluatedConditionCount()`.

### 3.2 Loops (back edges)

The key change: `done` went from "absolute de-duplication" to "**re-entry is only allowed when the graph has a cycle**".

```java
boolean hasCycle = hasCycle(graph);        // DFS with three colours, computed once at the start of run()
...

int visit = visits.merge(key, 1, Integer::sum);
if (done.contains(key) && !hasCycle) continue;   // an acyclic graph: the de-duplication semantics are kept
if (visit > maxVisits) { log("[循环] 达到上限"); continue; }
if (++steps > maxSteps) { log("[循环] 总步数超限"); break; }
```

The enqueue condition of an OR-join is relaxed in step:

```java
if (!done.contains(edge.to()) || graphHasCycle) ready.add(edge.to());
```

**Why re-entry is not simply allowed everywhere**: repeated activation of a parallel/XOR element is common (the same join point is hit by two edges),
and blanket re-entry would make the device action run twice — that is a physical side effect. Using "does the graph really contain a cycle" as the switch
limits the risk to the scenarios where "the user explicitly drew a back edge".

### 3.3 Variables

```java
private Map<String, String> variables;                 // writable (the node outputs are merged in)
public interface NodeOutputReader { Map<String,String> outputsOf(String nodeKey); }
private NodeOutputReader outputReader = k -> Map.of(); // the default empty implementation (the unit tests / batch acceptance do not touch the database)
```

- On initialisation: `WorkflowService` reads from `run.input.vars` (the experiment variables /
  `?vars=k=v` injection)
- After each successful task: `variables.putAll(outputReader.outputsOf(key))`, the log says
  `[变量] A 输出 {...}`

`WorkflowService.loadNodeOutputs(runId, nodeKey)` fetches the node output, **two traps**:

1. `run_command` hangs on the **step node** (`level=1`, `node_key=S1`), and the business
   node is its
   `parent_node_id`. Searching only by `n.node_key=? AND n.level=0` **never finds anything** —
   it has to be `LEFT JOIN run_node p ON p.id = n.parent_node_id AND (n.node_key=? OR p.node_key=?)`.
2. `run_command.result` only has `{ok,durationMs}`; the device's real business fields are in
   the `data` of `device_raw_response` (stored as an escaped JSON string, it has to be
   unescaped first).

`device_raw_response` is read first, and it falls back to `result` when nothing can be found. A regular expression extracts the top-level scalars (string/number/boolean):
**not doing a full JSON parse is deliberate** — the format of the result body varies from device to device, and over-structuring it would turn a parse failure into an execution failure.

### 3.4 The branch that was not taken: `bypassed`

A new `node_status` enum value `bypassed` (DDL: `ALTER TYPE node_status ADD VALUE 'bypassed'`).

| Status | Semantics | Behaviour at run time |
|---|---|---|
| `SKIPPED` | It should have been done but was not, **the plate position is not trustworthy** | `GraphRunner` does an immediate `break` and hands over to an operator |
| `BYPASSED` | The XOR took the other path, this step is not needed | It continues normally |

When it is marked: **after the whole run has succeeded**, the business nodes (`level=0`) that are still `running` are changed to `bypassed`:

```java
// WorkflowService.markBypassedNodes
if (progress.count(MANUAL) > 0 || progress.count(ABORTED) > 0 || progress.aborted()) return;
UPDATE run_node SET status='bypassed', actual_end=now(),
       input = input || '{"reason":"分支未选中（异或网关走了另一条）"}'
 WHERE run_id=? AND level=0 AND status='running';
```

Why it is only marked on a successful finish: in a failed / paused run, `running` means "waiting to be resumed",
and marking it as `bypassed` would make `resumeAsync` (skip the completed ones, redo the rest) misjudge it.

---

### 3.5 Gateway pairing and nesting rules (added 2026-11-08)

- **Roles are derived from in/out degrees**: in-degree > 1 means a join, out-degree > 1 means a split, both > 1 means wait-then-split, and 1-in-1-out is a pass-through; the "suggested name" on the canvas is only a naming aid and is not read by the executor.
- **Join semantics**: a parallel gateway (`JOIN_PARALLEL`) is an AND join - it advances only when every incoming edge has arrived; any non-parallel merge (including exclusive gateways and ordinary nodes) is an OR join - it advances on the first arriving edge and de-duplicates via `done`.
- **1-in-1-out gateways**: the parser treats them as pass-throughs and reports nothing, but a diamond that does nothing is a diagram-quality problem; the test helper `validate_flow()` already forbids it, and `PreflightChecker` should gain the same warning.
- **Nesting**: the engine executes on graph topology and allows nesting; `validate_flow()` requires every branch of an inner split to merge into a same-type join inside its own branch (an exclusive split may merge into the same end), and recommends depth <= 2 and at most 4 gateway pairs per diagram.
- **Known risk**: when a parallel split has no explicit join, the execution queue may drain early while `settleRun` still marks the run completed and marks unreached nodes bypassed (the false green of the old 4.16 diagram). Page-drawn and matrix-generated diagrams must therefore pass `validate_flow()` before execution.
- **To be added**: `PreflightChecker` warnings for split-without-join / 1-in-1-out / over-deep nesting; and a run-input parameter form on the page execute entry (currently only the `?vars=` API is supported).

## 4. The data flow

```
experiment variables / ?vars=k=v
        │
        ▼
   run.input.vars ──loadRunVars──► GraphRunner.variables
                                        ▲
   run_command.result ──loadNodeOutputs─┘ (merged in after each successful task)
                                        │
                                        ▼
                              ConditionEvaluator.eval(...)
                                        │
                        ┌───────────────┴───────────────┐
                        ▼                               ▼
                 the condition edge (conditionHitCount++)   the default (unevaluatedConditionCount++)
```

---

## 5. The interface changes

| Interface | Change |
|---|---|
| `POST /api/workflows/{id}/run?async=1&vars=k1=v1,k2=v2` | The `vars` parameter is new (the async path used to ignore the variables) |
| `GraphRunner.withVariables(Map)` / `.withOutputReader(...)` / `.withMaxVisits(int)` | New construction-time injection |
| `workflow.attr.maxIterations` | New: the workflow-level limit on the number of loop rounds (default 10) |
| The preflight gains `PF-09` | A syntax check for condition expressions (WARN, does not block) |
| `NodeStatus.BYPASSED` | A new enum value; the `node_status` type gains `'bypassed'` |
| `/api/runs/{id}/graph` | The node status gains one more value, `bypassed` (the frontend already supports it) |

---

## 6. The known technical debt

| # | Debt | Blast radius | Suggestion |
|---|---|---|---|
| 1 | The unique key of `run_node` is `(run_id, node_key, attempt)`, and a loop re-entry does an `UPDATE` on the same row | The intermediate rounds are invisible | Write the rows by `(run_id, node_key, attempt, loop_index)`, which needs a table structure migration |
| 2 | The round limit is at the workflow level (`attr.maxIterations`), not at the gateway level | Several back edges in one workflow share the limit | Read it from the gateway node's attributes (the `GNode` needs an attribute field) |
| 3 | `loadNodeOutputs` does one DB query per task | High-frequency nodes pay an extra delay (currently <1ms per query, acceptable) | Have `DriverStepExecutor` pass the result back to the GraphRunner and drop the query |
| 4 | Parsing the JSON result with a regular expression | Nested / array values cannot be reached | Introduce a lightweight JSON path |
| 5 | A wrong condition is only logged | It is not easy to spot on site | Add a "condition syntax check" to the preflight |

---

## 7. The key files

| File | What it does |
|---|---|
| `labos-kernel/src/main/java/com/labos/kernel/engine/ConditionEvaluator.java` | Expression evaluation (safe, no eval) |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/GraphRunner.java` | Conditional branching `/` loops / variable injection |
| `labos-kernel/src/main/java/com/labos/kernel/workflow/WorkflowService.java` | Loading the variables, capturing the node outputs, marking `bypassed` |
| `labos-kernel/src/main/java/com/labos/kernel/engine/NodeStatus.java` | `BYPASSED` |
| `webapp/src/components/RunGraph.tsx` | The colouring on the graph (including "branch not taken") |
| `labos-kernel/src/test/java/com/labos/kernel/engine/ConditionEvaluatorSelfTest.java` | The evaluator test cases |
