# Expression Engine — Design

> **English edition.** The Chinese original is [`docs/表达式引擎-设计.md`](../表达式引擎-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> The variables in a workflow need **arithmetic, conditionals and rounding**; they cannot
> be pure substitution only.
> But **a scripting engine must not be brought in** — both the security risk and the
> dependencies are too large.

---

## 1. What has to be supported

### 1.1 Must be supported (M1)

| Category | Syntax | Example |
|---|---|---|
| **Arithmetic** | `+ - * / %` | `${usedSlots + 1}` |
| **Rounding** | `floor()` | `${floor(count / 8) + 1}` |
| **Min/max** | `max()` `min()` | `${max(a, b)}` |
| **Comparison** | `> >= < <= == !=` | `${a > b}` |
| **Logic** | `&& \|\| !` | `${a > 0 && b < 10}` |
| **Ternary** | `? :` | `${a > b ? 1 : 2}` |
| **Parentheses** | `( )` | `${(a + b) * 2}` |
| **Variable** | `{name}` | `${i}` |
| **Number** | Integer / decimal | `${3}`, `${0.5}` |

### 1.2 Not supported (**deliberately**)

| Not supported | Why |
|---|---|
| **String functions** (`substring`/`split`/`replace`) | Position numbers are assembled by concatenation; no string processing is needed |
| **Function definitions** | That would turn it into a programming language |
| **Loops** (`for`/`while`) | A loop is a **node type**, not an expression |
| **Objects/arrays** | Variables are all scalars |
| **Regular expressions** | The same |
| **Arbitrary code execution** | The safety floor |

**Principle**: **an expression only "computes one value", it does not "write logic".**

## 2. The syntax

### 2.1 EBNF

```ebnf
expr    := ternary
ternary := logic_or ('?' expr ':' expr)?
logic_or := logic_and ('||' logic_and)*
logic_and := equality ('&&' equality)*
equality := comparison (('==' | '!=') comparison)*
comparison := additive (('>' | '>=' | '<' | '<=') additive)*
additive := multiplicative (('+' | '-') multiplicative)*
multiplicative := unary (('*' | '/' | '%') unary)*
unary   := ('!' | '-')? primary
primary := number
         | string
         | variable
         | func '(' expr (',' expr)* ')'
         | '(' expr ')'
variable := '{' ident '}'
func    := 'floor' | 'ceil' | 'round' | 'max' | 'min' | 'abs'
```

### 2.2 The two scopes

**An expression appears in two places, with different scopes**:

| Location | Example | Source of the variables |
|---|---|---|
| **Inside a parameter value** (inside `${}`) | `"target": "ID1183-L${i}"` | Workflow variables |
| **Inside a condition** (the whole string) | `"condition": "${plateCount} > 0"` | Workflow variables |

**The difference**:
- The `${}` inside a parameter value is an **insertion** (replaced by the result)
- The `${}` inside a condition is usually **one whole expression**

**Handled uniformly**: both are handled as "find the `${...}` → evaluate → replace".

### 2.3 The types

**There are only two: number and string**

```
${1 + 2}          → 3        (number)
${"a" + "b"}      → ab       (string concatenation)
${1 + "a"}        → 1a       (the number is converted to a string and then concatenated)
${1 < 2}          → true     (boolean, outputs "true"/"false")
```

**Division**:

```
${7 / 2}          → 3.5      (floating point)
${floor(7 / 2)}   → 3        (rounded down)
${7 % 2}          → 1        (remainder)
```

**Division by zero**:

```
${1 / 0}          → throws an exception at execution time
                   "表达式求值失败：除数为 0（表达式 1 / 0）"
```

**It does not silently return 0** — silence would let the error hide.

---

## 3. The implementation

### 3.1 Why we write the parser ourselves

| Option | Dependencies | Safety | Amount of code |
|---|---|---|---|
| **Nashorn** (bundled with the JDK) | None (removed in JDK 15+) | **Can execute arbitrary JS** | 0 |
| **GraalJS** | Large (tens of MB) | Can execute arbitrary JS | 0 |
| **AviatorScript** | One jar | Can execute scripts | 0 |
| **Write it ourselves** | **None** | **Can only do arithmetic** | **~300 lines** |

**Write it ourselves**:

- **Zero dependencies** (consistent with the project's "bare JDK" orientation)
- **Safe** (it is impossible to execute arbitrary code)
- **Controllable** (something that is not supported reports an explicit error, instead of
  "accidentally being supported")

### 3.2 The structure

```java
/** An expression: parsed once, evaluated many times. */
public final class Expression {
    private final Node root;
    private final String source;

    public static Expression parse(String src);   // parse; a syntax error throws
    public Object eval(Map<String, Object> vars); // evaluate
}

// The AST nodes (inner classes, not exposed externally)
sealed interface Node {
    record Num(double v) implements Node {}
    record Str(String s) implements Node {}
    record Var(String name) implements Node {}
    record BinOp(Node l, String op, Node r) implements Node {}
    record UnaryOp(String op, Node operand) implements Node {}
    record Ternary(Node cond, Node t, Node f) implements Node {}
    record Func(String name, List<Node> args) implements Node {}
}
```

### 3.3 The parsing (recursive descent)

```java
private Node parseTernary() {
    Node cond = parseOr();
    if (peek() == '?') {
        next();
        Node t = parseExpr();
        expect(':');
        Node f = parseExpr();
        return new Node.Ternary(cond, t, f);
    }
    return cond;
}
// The other levels work the same way
```

**About 300 lines**, including the tokenizer (~80), the parser (~150) and the evaluator
(~70).

### 3.4 The evaluation

```java
Object eval(Node n, Map<String, Object> vars) {
    return switch (n) {
        case Node.Num v -> v.v();
        case Node.Str s -> s.s();
        case Node.Var v -> {
            Object val = vars.get(v.name());
            if (val == null) {
                throw new EvalException(
                    "变量未定义：" + v.name() + "（表达式 " + source + "）");
            }
            yield val;
        }
        case Node.BinOp b -> applyOp(b.op(), eval(b.l(), vars), eval(b.r(), vars));
        case Node.Ternary t -> toBool(eval(t.cond(), vars))
                ? eval(t.t(), vars) : eval(t.f(), vars);
        case Node.Func f -> applyFunc(f.name(), f.args(), vars);
        case Node.UnaryOp u -> applyUnary(u.op(), eval(u.operand(), vars));
    };
}
```

### 3.5 Error handling

**Four kinds of error, and the message has to be specific**:

| Error | Message |
|---|---|
| **Syntax error** | `表达式语法错误："${1 + }"（位置 6：缺少操作数）` |
| **Variable not defined** | `变量未定义：usedSlots（表达式 usedSlots + 1）` |
| **Division by zero** | `表达式求值失败：除数为 0（表达式 1 / 0）` |
| **Type error** | `不能对字符串做减法："abc" - 1` |

**They all carry the original expression** — so you do not have to guess which one it was
while troubleshooting.

---

## 4. The relationship to the existing code

### 4.1 The current `substVars`

```java
// Now: pure substitution
private String substVars(String s) {
    return s.replaceAll("\\$\\{([A-Za-z_]\\w*)}", (m) -> vars.getOrDefault(m.group(1), m.group(0)));
}
```

**It only supports plain variable names, not expressions.**

### 4.2 Changing it to

```java
private String substVars(String s) {
    Matcher m = PATTERN.matcher(s);      // \$\{([^}]+)\}
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
        String exprSrc = m.group(1);     // usedSlots + 1
        Object v = Expression.parse(exprSrc).eval(vars);
        m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
    }
    m.appendTail(sb);
    return sb.toString();
}
```

**Compatibility**: a plain variable name like `${i}` can also be computed by the
expression engine (it is just a `Var` node), so **no special case is needed**.

### 4.3 Caching the parse result

**The same expression is evaluated several times inside a loop**:

```json
"target": "ID1183-L${i}"    // the loop runs 8 times, so it is parsed 8 times?
```

**Cache it**:

```java
private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();

Expression expr(String src) {
    return CACHE.computeIfAbsent(src, Expression::parse);
}
```

**Watch the cache size** (there will not be many kinds of expression, but a size limit
guards against a memory leak).

---

## 5. Examples

### 5.1 A loop + concatenation

```json
{ "nodeType": "loop", "varName": "i", "from": 1, "to": 8,
  "body": [{ "method": "Transfer",
             "params": { "target": "ID1183-L${i}" } }] }
```

```
i=1 → ID1183-L1
i=2 → ID1183-L2
...
```

### 5.2 Computing the next free slot

```json
{ "nodeType": "compute",
  "set": { "nextSlot": "ID1183-L${floor(usedSlots / 1) + 1}" } }
```

**Simplified**: `ID1183-L${usedSlots + 1}`

### 5.3 Three nested loops (8 rows × 12 columns)

```json
{
  "nodeType": "loop", "varName": "r", "from": 1, "to": 8,
  "body": [{
    "nodeType": "loop", "varName": "c", "from": 1, "to": 12,
    "body": [{
      "method": "Transfer",
      "params": { "target": "PLATE-${r}-${c}" }
    }]
  }]
}
```

**Note**: these are **nested loop nodes**, not a loop inside an expression.

### 5.4 Choosing a device by condition

```json
{ "condition": "${plateCount} > 4",
  "then": [{ "method": "Transfer", "params": { "target": "BIG-STACKER-S${plateCount}" } }],
  "else": [{ "method": "Transfer", "params": { "target": "SMALL-STACKER-S1" } }] }
```

---

## 6. The boundaries and the trade-offs

### 6.1 Floating-point precision

```
${0.1 + 0.2}   → 0.30000000000000004
```

**Handling**:

```java
// Format on output: if it is an integer, output the integer, otherwise keep 6 decimals
String fmt(Object v) {
    if (v instanceof Double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.format("%.6f", d).replaceAll("0+$", "");
    }
    return String.valueOf(v);
}
```

**Decimals never appear in position numbers**, so this rule is mainly a guard against
surprises.

### 6.2 The rules for variable names

**Only allowed**: `[A-Za-z_][A-Za-z0-9_]*`

**Not allowed**: Chinese, spaces, dots.

**Rationale**: it is tied to the parsing of `${}`; the simpler it is, the fewer the
ambiguities.

### 6.3 The expression length limit

**At most 500 characters per expression** — to stop someone from writing a whole block
of logic into it.

### 6.4 The evaluation timeout

**At most 100 ms per evaluation** — recursive descent + short-circuit evaluation, so it
normally is not exceeded. If it is, the nesting is very deep, which is a design error.

---

## 7. The M1 task list

| # | Task | Estimate |
|---|---|---|
| 1 | Tokenizer + Parser (~150 lines) | 0.5 days |
| 2 | Evaluator + built-in functions (~100 lines) | 0.5 days |
| 3 | Wiring it into `substVars` | 0.5 days |
| 4 | Unit tests (20+ cases) | 0.5 days |
| 5 | Polishing the error messages | 0.5 days |
| **Total** | | **2.5 days** |

**These 2.5 days have to be added to M1** (originally 1.5 weeks → **1.75 weeks**).

---

## 8. The test cases (they must all be covered)

| # | Expression | Variables | Expected |
|---|---|---|---|
| 1 | `1 + 2` | — | `3` |
| 2 | `i` | `i=5` | `5` |
| 3 | `i + 1` | `i=5` | `6` |
| 4 | `ID1183-L${i}` | `i=3` | `ID1183-L3` |
| 5 | `floor(7 / 2)` | — | `3` |
| 6 | `7 % 3` | — | `1` |
| 7 | `max(a, b)` | `a=3,b=7` | `7` |
| 8 | `a > b ? 1 : 2` | `a=5,b=3` | `1` |
| 9 | `a > 0 && b < 10` | `a=1,b=5` | `true` |
| 10 | `!flag` | `flag=false` | `true` |
| 11 | `(a + b) * 2` | `a=1,b=2` | `6` |
| 12 | `1 / 0` | — | **An exception** |
| 13 | `undefinedVar` | — | **An exception** (it names the variable) |
| 14 | `1 +` | — | **A syntax error** (it gives the position) |
| 15 | `"abc" - 1` | — | **A type error** |
| 16 | `a || b` | `a=false,b=true` | `true` |

**Every one of them has to be checked for the error message carrying the original
expression.**
