# Workflow Control: Conditional Branches, Loops, Variables (Test Report)

> **English edition.** The Chinese original is [`docs/流程控制-测试报告.md`](../流程控制-测试报告.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> Version under test: 2026-11-03
> Related: [Functional guide](workflow-control-guide.md) · [Technical design](workflow-control-design.md)

---

## 1. The test scope

| Layer | Content | Number of cases |
|---|---|---|
| Unit | The condition expression evaluator (comparison / logic / variables / refusing injection) | 23 |
| Unit | The graph runner (fork / join / de-duplication) | 6 |
| End to end | The XOR branch (both the true and the false case) | 2 |
| End to end | The back-edge loop (triggered / not triggered) | 2 |
| End to end | Parallel fork + join / one of the parallel branches failing | 2 |
| End to end | The node output driving a condition / loop + parallel / three branches / preflight / the round limit | 5 |
| Regression | The full pure-logic test suite | 373 |

---

## 2. How to run it

```bash
# 1) The full pure-logic suite (including the new evaluator cases, no database needed)
cd /path/to/labos
./scripts/test-all.sh --pure-only
# Expected: 373 passed / 0 failed

# 2) Run the evaluator on its own
cd labos-kernel
javac -encoding UTF-8 -d out -cp "out:lib/postgresql-42.7.3.jar" $(find src/test -name '*.java')
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.engine.ConditionEvaluatorSelfTest
# Expected: 23 passed, 0 failed
```

> `test-all.sh` compiles `src/test` into `out` first. That step did not exist before,
> so a newly added test class only reported "main class not found", which looks like a
> failure but is really just "it was not compiled".

---

## 3. The unit test details (`ConditionEvaluatorSelfTest`, 23 cases)

### 3.1 Basic comparison (6)

| Expression | Expected | Result |
|---|---|---|
| `1 > 0` | true | ✓ |
| `3 >= 3` | true | ✓ |
| `2 < 1` | false | ✓ |
| `10 == 10` | true | ✓ |
| `10 != 10` | false | ✓ |
| `'10' > '9'` | **true** | ✓ (it verifies the numeric comparison, not the lexicographic one) |

### 3.2 Variables (6)

| Case | Expected | Result |
|---|---|---|
| `${temp} > 37`, temp=38 | true | ✓ |
| `${temp} > 37`, temp=37 | false | ✓ |
| `${mode} == 'fast'`, mode=fast | true | ✓ |
| `${mode} != 'fast'`, mode=slow | true | ✓ |
| `${missing} == ''` | true (undefined = empty string) | ✓ |
| `${missing} > 1` | **throws an exception** (it does not guess that it is 0) | ✓ |

### 3.3 Logic and parentheses (6)

| Case | Expected | Result |
|---|---|---|
| `(${a} > 1) && (${b} < 5)`, a=2,b=3 | true | ✓ |
| The same, b=9 | false | ✓ |
| `${a} == 1 \|\| ${b} == 2`, a=1,b=0 | true | ✓ |
| `!(${a} == 1)`, a=1 | false | ✓ |
| `true` / `false` | true / false | ✓ |

### 3.4 Refusing to execute (5)

| Input | Expected | Result |
|---|---|---|
| `${a} >` | The expression ends unexpectedly | ✓ |
| `(1 + 1` | The closing parenthesis is missing | ✓ |
| `foo > 1` | An unknown identifier (it should be written `${foo}`) | ✓ |
| `1 > 0 垃圾` | Superfluous content | ✓ |
| `${a} == 'x' ; Runtime.getRuntime().exec('ls')` | **refused (an injection attempt)** | ✓ |

> The input `1 > 0 垃圾` is the literal string used in
> `ConditionEvaluatorSelfTest` ("superfluous content"); it is kept verbatim so the case
> matches the source.

---

## 4. The end-to-end tests (the simulator + the real execution chain)

Environment: API `18080`, simulator `19601` (S100), sidecar `18501`, real database `labdb`

### 4.1 The XOR branch: the condition is true

```bash
curl -X POST "http://127.0.0.1:18080/api/workflows/1258/run?async=1&vars=ok=true" \
     -H "Authorization: Bearer $TOKEN"
```

| Assertion | Actual (run 205390) | Result |
|---|---|---|
| It takes the condition edge (A) | `A completed` | ✓ |
| B, which was not taken | `B bypassed` | ✓ |
| The whole run | `completed` | ✓ |

### 4.2 The XOR branch: the condition is false → it takes the default

```bash
curl -X POST ".../1258/run?async=1&vars=ok=false"
```

| Assertion | Actual (run 205391) | Result |
|---|---|---|
| It takes the default (B) | `B completed` | ✓ |
| A, which was not taken | `A bypassed` | ✓ |

> For comparison: before the rework, with the same input **A stayed `running`** in both
> branches (neither skipped nor completed),
> and on the page you could only guess from "the whole run is not running" that it had not
> executed.

### 4.3 The loop: the condition holds → it runs to the limit

```bash
curl -X POST ".../1259/run?async=1&vars=go=yes"
```

| Assertion | Actual (run 205389) | Result |
|---|---|---|
| The back edge sends the control back to the task node | The task executes repeatedly | ✓ |
| The simulator receives Seal | **10 of them** | ✓ |
| It stops after the round limit | The whole run is `completed`, it did not loop forever | ✓ |

### 4.4 The loop: no variable passed → the condition is false

| Assertion | Actual | Result |
|---|---|---|
| It does not take the back edge | The simulator receives only **1** Seal | ✓ |
| The whole run | `completed` | ✓ |

### 4.5 The parallel fork / one of the parallel branches failing (a regression, the fixture is unchanged)

| Scenario | Actual | Result |
|---|---|---|
| Both branches succeed | `A completed` / `B completed`, the simulator gets 2 Seal commands, `completed` after the join | ✓ |
| The 2nd one is injected with out-of-paper | `A completed` / `B manual EC-08-400C` / the whole run `paused` | ✓ |

### 4.6 How it looks on the page

Run detail → the graph:

| Scenario | The legend | On the graph |
|---|---|---|
| Branch A gets through | `Completed 1 / Branch not taken 1` | A green box + a faint grey dotted box, joined by an arrow |
| A parallel failure | `Completed 1 / Awaiting operator 1 / Not started 1` | Green / orange / grey dashed |

(The legend uses the English status labels of the product: `Completed`, `Branch not taken`, `Awaiting operator`, `Not started`.)

The corresponding row in the node table is "Branch not taken" and states the reason: `分支未选中（异或网关走了另一条）`.

---

## 4.7 The second round of supplementary verification (2026-11-03, afternoon)

### 4.7.1 The node output drives a condition (the biggest gap until now)

Build `T-OUT`(1261): `开始 → [查板型 GetPlateType] → ◇板型>=1吗 →(真)回到查板型 / (假)结束`
("Start → [read the plate type GetPlateType] → ◇is the plate type >= 1 → (true) back to read the plate type / (false) End"),
with the workflow attr `maxIterations=2`.

| Assertion | Actual (run 205395) | Result |
|---|---|---|
| The fields of the device response go into the variable table | `plateType=1` (from `device_raw_response.data`) | ✓ |
| The condition can read it | `${plateType} >= 1` is true | ✓ |
| The loop stops at the limit | GetPlateType is executed **2 times** and then it ends, the whole run is `completed` | ✓ |

**This step uncovered two real bugs**:

1. `run_command` hangs on the **step node** (`level=1`), and the business node is its
   parent.
   The query searched by `node_key=? AND level=0`, so **it found nothing at all**, and
   the variables were always empty.
2. `run_command.result` only has `{ok,durationMs}`, the device's business fields are in
   `device_raw_response.data`, and they are stored as an escaped JSON string.

### 4.7.2 Loop + parallel (the combination most likely to loop forever)

Build `T-PLOOP`(1262): a parallel fork → (A: a single pass) / (B: with a back edge, the condition `${go}==yes`) → a parallel join,
with `maxIterations=2`.

| Assertion | Actual (run 205396) | Result |
|---|---|---|
| A executes once | `A completed` | ✓ |
| B goes 2 rounds according to the condition | `B completed` | ✓ |
| The join point waits for both paths | The simulator receives **3 Seal commands** in total (A 1 + B 2) | ✓ |
| It neither loops forever nor deadlocks | The whole run is `completed` | ✓ |

### 4.7.3 The round limit is configurable

After `workflow.attr.maxIterations = 3`, run `T-LOOP`(1259): the simulator receives **3** Seal commands (10 by default before). After changing it to 2, `T-OUT` runs exactly 2 rounds — the configuration takes effect.

### 4.7.4 The condition syntax preflight (PF-09)

Build `T-BAD`(1260): the condition is written `${ok} >` (incomplete).

```
GET /api/workflows/1260/preflight
→ WARN PF-09 XOR  the condition expression of the edge XOR→A has a syntax problem: the expression ends unexpectedly: ${ok} > (the default edge is taken at execution time)
→ WARN PF-03 A    the node "走A分支" is never executed
```

The preflight **does not block the start** (WARN), but you can see it on site before the run.

### 4.7.5 The three-way XOR (more than 2 outgoing edges)

Build `T-X3`(1263): the `X 分支` ("X branch") node has three outgoing edges — the condition
`${v}==1`, the condition `${v}==2`, and the default.

| Input | Result |
|---|---|
| `vars=v=1` | `A:completed  B:bypassed  C:bypassed` |
| `vars=v=2` | `A:bypassed  B:completed  C:bypassed` |
| `vars=v=9` | `A:bypassed  B:bypassed  C:completed` |

They are evaluated in the declared order, the first one that holds is selected, and if none holds the default is taken — consistent with the design.

---

## 5. Regression

| Item | Result |
|---|---|
| `./scripts/test-all.sh --pure-only` | **373 passed / 0 failed** (23 new ones) |
| `npm run tsc -b` | 0 errors |
| Running an ordinary workflow (no gateway) once | `completed`, the number of device commands is the same as before the rework |

---

## 6. What was not covered (to be added in the next round)

| # | Gap | Why it matters |
|---|---|---|
| 1 | A back edge **crossing** a parallel fork (entering the parallel again after the loop) | Only "parallel + a loop inside a branch" was tested |
| 2 | The round limit configured per **gateway** | It is at the workflow level today |
| 3 | Arithmetic in a condition (`${a}+1 > 3`) | The evaluator does not support it |
| 4 | On a loop re-entry `run_node` has only one row | The intermediate state of the Nth round is invisible |

> These have already been verified in section 4.7, so they are **removed from the gap
> list**.
> What is still not verified: entering the parallel fork again inside a loop (a back edge
> crossing a gateway), the round limit configured per gateway, and arithmetic in a
> condition.
