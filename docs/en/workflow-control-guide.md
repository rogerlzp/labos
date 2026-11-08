# Workflow Control: Conditional Branches, Loops, Variables (Functional Guide)

> **English edition.** The Chinese original is [`docs/流程控制-功能说明.md`](../流程控制-功能说明.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> For: the people authoring a workflow, on-site operations, the acceptance reviewers
> Code version: 2026-11-03 (the commit is at the end of the document)
> Related: [Technical design](workflow-control-design.md) · [Test report](workflow-control-test-report.md)

---

## 1. What this version adds

Before this, the workflow diagram could draw gateways, but **only "parallel" really
worked**:

| Capability | Before | Now |
|---|---|---|
| Parallel fork / join (AND) | ✅ worked | ✅ unchanged |
| Conditional branch (XOR + a condition expression) | ⚠️ It could fork, but **the condition was never evaluated**, it always took the default | ✅ **It really decides on the expression** |
| The branch that was not taken | ❌ It stayed `running` forever, indistinguishable from "not its turn yet" | ✅ It is marked `bypassed` (not selected) |
| Loops (a back edge) | ❌ The back edge was dropped as a duplicate, the second round never ran | ✅ Supported, up to 10 rounds (configurable) |
| Run-time variables `${x}` | ❌ Only the experiment layer did parameter substitution | ✅ They can be used in workflow conditions |
| Node output → variable | ❌ None | ✅ They go into the variable table automatically |

---

## 2. How to use it

### 2.1 Drawing a conditional branch

In the workflow designer:

```
Start → ◇branch (an XOR gateway, the default points at "take B")
         ├─ condition edge: ${ok} == true   → [take branch A]
         └─ default edge                   → [take branch B]
                                     both join at  End
```

**The configuration points**:

| Item | Explanation |
|---|---|
| The gateway type | It has to be an **XOR gateway** (`exclusiveGateway`). A parallel gateway does not look at conditions, it takes all of them |
| The number of outgoing edges | **More than 2 is supported**: they are evaluated in the declared order, the first one that holds wins, and if none holds the default is taken |
| Where the condition goes | On the **outgoing edge** (sequenceFlow), in its `conditionExpression`, not on the node |
| The default edge | It is taken when none of the conditions holds. Without one the "first in the order" is taken and it is logged |
| An empty condition | That edge does not take part in the decision (it is treated as an ordinary sequence edge) |

### 2.2 What a condition expression can contain

```
${temp} > 37
${mode} == 'fast'
(${a} > 1) && (${b} < 5)
${count} >= 3 || ${force} == true
!(${ok} == true)
```

**Only these are supported**, one character more raises an error (this is deliberate, see
"why not eval" in the technical design):

| Supported | Example |
|---|---|
| Comparison | `> >= < <= == !=` |
| Logic | `&& \|\| !` and parentheses |
| Variables | `${variable}` |
| Literals | numbers, `'string'`, `true`/`false` |

⚠️ **Not supported**: arithmetic (`${a}+1`), function calls, regular expressions, and field
paths other than the node output (`${a.b}` currently treats `a.b` as a variable name, and
if it cannot be found it is an empty string).

### 2.3 Drawing a loop

A loop is expressed with a **back edge**: from a later gateway back to an earlier task node.

```
Start → [seal one round] → ◇one more? (the default points at "End")
                      └─ condition edge: ${go} == 'yes' → back to [seal one round]
```

**The guards** (they are mandatory, otherwise a wrong expression becomes an infinite loop):

| Guard | Value | Explanation |
|---|---|---|
| The maximum number of rounds for a single node | **10 by default**, configurable per workflow | Writing `workflow.attr.maxIterations = 3` changes it to 3 rounds (`UPDATE workflow SET attr = attr \|\| '{"maxIterations":3}'`) |
| The upper limit on the total number of steps | **500** | Beyond it the whole run is forcibly stopped, the log says `[循环] 总步数超过 500，强制停下` |

> The round limit is currently the engine default; it is not exposed in the interface yet
> (see "known limitations").

### 2.4 Where the variables come from

| Source | How to use it | Example |
|---|---|---|
| **Passed in at start** | The execution endpoint carries `vars`, or the experiment's "experiment variables" | `POST /api/workflows/1259/run?async=1&vars=go=yes` |
| **The node output** (automatic) | After each task node succeeds, the **fields in the device response `data`** are written into the variable table | `GetPlateType` returns `{"plateType":1}` → the variable `plateType=1` |

On the page: the variables defined in the "experiment variables" table of the experiment
orchestration are injected at execution time.

### 2.5 How to see the result on the page

Run detail → "Graph (coloured by execution status)":

| Style on the graph | Status | Meaning |
|---|---|---|
| Light blue circle | Start | The workflow entry point |
| Light grey circle (dark edge) | End | The workflow exit point |
| Green box | Completed | This step is done |
| Orange box | Awaiting operator | Handed over to an operator (e.g. out of paper `EC-08-400C`) |
| Grey dashed box | Not started | Its turn has not come yet (an earlier step failed / paused) |
| **Faint grey dotted box** | **Branch not taken** | The XOR gateway took the other path, this step is not needed |
| Yellow box | Skipped | Skipped by policy (the plate position is not trustworthy, it stops and waits for a person) |
| Blue box | Running | It is running now |
| Red box | Failed / aborted | |

In the node table, the "Branch not taken" row states the reason:
`分支未选中（异或网关走了另一条）` ("branch not selected (the XOR gateway took the other
path)").

---

## 3. Known limitations (important, do not trip over them)

| # | Limitation | Impact | Plan |
|---|---|---|---|
| 1 | The round limit is a **workflow-level** configuration (`workflow.attr.maxIterations`) | Several back edges in the same workflow share one limit | Support configuring it per gateway later |
| 2 | On a loop re-entry the **same run_node row is updated again** | On the graph you only see the "final completion", not the intermediate state of the 3rd round; `attempt` accumulates | run_node rows split by round (a change to the table structure) |
| 3 | Variables only have **top-level fields** | When the device returns a nested object the inner values cannot be reached | JSON path |
| 4 | Condition expressions do not support arithmetic | `(${a}+1) > 3` does not hold | It can be added, but it ranks below "the stability of the evaluator" |
| 5 | Variable names are case-sensitive | `${Var}` ≠ `${var}` | Keep it as it is (consistent with the BPMN convention) |
| 6 | A wrong expression → the default is taken and it is logged | It does **not** make the whole run fail | Deliberate: taking the default edge is better than deadlocking production |

---

## 4. A real run (directly reproducible)

```bash
# Conditional branch: ok=true takes A, ok=false takes the default (B)
curl -X POST "http://127.0.0.1:18080/api/workflows/1258/run?async=1&vars=ok=true"  -H "Authorization: Bearer $TOKEN"
# → A completed / B bypassed (the branch was not selected)

curl -X POST "http://127.0.0.1:18080/api/workflows/1258/run?async=1&vars=ok=false" -H "Authorization: Bearer $TOKEN"
# → A bypassed / B completed

# Loop: go=yes goes back to itself, it stops after 10 full rounds
curl -X POST "http://127.0.0.1:18080/api/workflows/1259/run?async=1&vars=go=yes" -H "Authorization: Bearer $TOKEN"
# → the simulator receives 10 Seal commands, the whole run is completed

# No variable passed → the condition is false → it runs once
curl -X POST "http://127.0.0.1:18080/api/workflows/1259/run?async=1" -H "Authorization: Bearer $TOKEN"
# → 1 Seal command
```

---

## 6. Gateways, branches and variables: pairs, nesting and conditions (added 2026-11-08)

- **One entry, one exit per workflow**: exactly one start and one end on the canvas; every node except start/end must have an incoming and an outgoing edge, and every node must be reachable from start and able to reach end.
- **Gateways come in pairs**: a split (out-degree > 1) requires a join of the same type (in-degree > 1); a parallel split without a join has no synchronisation semantics, while an exclusive split may merge into the same end event. **1-in-1-out gateways are forbidden** (a meaningless pass-through that is easy to misread).
- **Nesting is allowed but bounded**: a parallel branch may itself contain exclusive/parallel splits, but an inner split must merge **inside its own enclosing branch**; every level has its own pair of joins and one join must not merge two different semantic levels. Recommended limits: nesting depth <= 2 and at most 4 gateway pairs per diagram; the product preflight should warn when exceeded (today it only marks unreachable nodes and stuck joins; the hard check lives in the test helper `validate_flow()`).
- **Where conditions live**: select the sequence flow and write the expression in the "Flow · Condition" panel (e.g. `${ok} == true`, `${counter} > 3`); a gateway may declare a default, and when no condition matches and no default exists the engine takes the first declared edge.
- **Where variables come from**: (1) in-graph variable nodes `__setVar` / `__expr` configured in the node settings panel; (2) run-time input via the `vars` parameter of `POST /api/workflows/{id}/run` (e.g. `?vars=ok=true`, which is how `MTRX-异或-1` works).
- **Current UI status**: node settings, flow conditions and auto-align all have page entries; however there is no execution-parameters form yet (the page execute button only sends `mode/async`), so run-input variables can only be passed through the API - an execution dialog is on the gap list.
- **Save validation (added 2026-11-08)**: the server validates a saved diagram - exactly one start and one end, no isolated nodes, every node reachable from start and able to reach end; otherwise the save fails with HTTP 400 naming the offending nodes. Automation/editor intermediates may pass `?draft=1` to save a draft (the designer page can enable it via `localStorage.labos.designer.draft=1`, which the test helper sets automatically); draft diagrams cannot be executed.

## 5. Change log

| Date | Content |
|---|---|
| 2026-11-03 | The condition evaluator, the `bypassed` status, the back-edge loop, run-time variables and node-output capture |
