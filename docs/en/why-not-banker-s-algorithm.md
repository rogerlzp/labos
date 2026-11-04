# Why Not the Banker's Algorithm

> **English edition.** The Chinese original is [`docs/为什么不用银行家算法.md`](../为什么不用银行家算法.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> Explained with a concrete example, without jargon.

---

## 1. What the banker's algorithm requires

**In one sentence**: **every workflow has to declare "at most how many resources do I
need" before it starts running.**

It is like going to a bank to borrow money: **you have to say clearly "at most how much am
I borrowing"**, and only then can the bank decide
"after lending it to you, can the other customers still get their money out?". That is
where the name comes from.

**The textbook example**:

```
The system has 3 printers
Process A declares: at most 2
Process B declares: at most 1

Now A wants 1 and B wants 1:
  after the allocation 1 is left
  check: A still needs 1 (there is one) → A can finish → release 2
        B still needs 0 (enough) → B can finish
  → safe, the allocation is allowed ✅
```

**If A wants 2 and B wants 1**:

```
  after the allocation 0 are left
  check: A still needs 0 → it can finish; B still needs 0 → it can finish
  → also safe
```

**If A wants 2 and B wants 2** (3 in total):

```
  A takes 2, 1 is left
  B wants 2, but only 1 is left → B waits forever
  → unsafe, **this allocation is refused**
```

**Key**: the bank can compute it because **A and B both said in advance "at most how many
printers"**.

---

## 2. Our scenario: it cannot be stated

### A real workflow

```
Start
  ↓
Decision: is there a sample on the plate?
  ├─ yes → incubator (30 minutes) → centrifuge → plate reader → End
  └─ no  → take the plate straight out → End
```

**Now the question: how many devices does this workflow "need at most"?**

| Branch | What it needs |
|---|---|
| There is a sample | Incubator + centrifuge + plate reader = **3 devices** |
| There is no sample | **0 devices** (it is only carried out) |

**Before it reaches the decision node, you do not know which way it goes.**

**So it cannot be declared.**

### If you force a declaration (by the worst case)

**Declare "I need at most 3 devices"**. Does that look workable? **Look at the
consequence**:

```
There are 4 devices in total (incubator, centrifuge, plate reader, spare incubator)

Workflow A declares: at most 3
Workflow B declares: at most 3

B wants to start:
  A has already been given 3, 1 is left
  B wants 3, only 1 is left → **refused**
```

**The result: B can never start, even though A actually only used 1 device.**

### An even worse case: many branches

**A workflow with 5 decision nodes**:

```
Decision 1 → branch A/B
Decision 2 → branch C/D
...
The worst case = the **union** of the devices used by all the branches
```

**The union can amount to "every device on the whole island".**

**Then every workflow declares "I want the whole island"** → **no two workflows can run in
parallel**.

**That is the price of "declaring by the worst case": the concurrency goes to zero.**

---

## 3. Then what about "declaring once the branch is known"

**The idea**: wait until the execution reaches the decision node, know the branch, and
then declare.

**There are three problems**:

### Problem 1: the resources are already held before the declaration

```
Workflow A: Start → reserves the source position → carries the plate → reaches the decision node
                                  ↑ only now does it declare "I need 3 devices"
```

**The premise of the banker's algorithm is "declare before the allocation"**, so what about
the positions that are already held?

**The workaround**: add "already held + future demand" together and compute it. **It can
be done, but the complexity goes up.**

### Problem 2: there are branches after the branch

```
Decision 1 → branch A → Decision 2 → branch C/D
```

**Every decision has to be recomputed**, and **the further you go, the less uncertainty is
left, but the more resources are already held**.

**The recomputation matters less and less.**

### Problem 3: it can change after it has been declared

**Halfway through the workflow, a person changes a parameter** (e.g. changing "incubate
for 30 minutes" into "60 minutes"),
**the resource demand changes and the declaration has to be redone**.

**The banker's algorithm assumes "the declaration does not change"**, and that assumption
does not hold in our scenario.

---

## 4. How the wait-for graph gets around this problem

**The wait-for graph does not ask "how many do you need at most", it only asks "what do
you want now, and who is holding it"**.

```
Workflow A wants the incubator now
  → the incubator is held by B
  → record an edge A → B (A is waiting for B)
  → check: starting from A and following the edges, can you get back to A?
      - no  → wait
      - yes → **refuse** (that is a cycle, it would deadlock)
```

**It does not predict the future, it only handles the request at hand.**

### An example of three workflows forming a cycle

```
Workflow 1 holds A, wants B
Workflow 2 holds B, wants C
Workflow 3 holds C, wants A

① Workflow 1 wants B (held by workflow 2) → add the edge 1→2 → no cycle → wait
② Workflow 2 wants C (held by workflow 3) → add the edge 2→3 → no cycle → wait
③ Workflow 3 wants A (held by workflow 1) → add the edge 3→1
   DFS: 3→1→2→3 ← **a cycle**
   → **refuse workflow 3**, it has to back out and retry

The result: workflows 1 and 2 keep waiting, workflow 3 retries
     After workflow 1 gets B and finishes, it releases A → only then can workflow 3 get A
     **No deadlock** ✅
```

**Note**: the wait-for graph **does not need to know** what workflow 3 wants afterwards.
It only knows "workflow 3 wants A now, A is held by workflow 1, and adding this edge
would create a cycle".

---

## 5. The essential difference between the two

| | The banker's algorithm | The wait-for graph |
|---|---|---|
| **Strategy** | **Avoid** (do not let it enter an unsafe state) | **Detect** (refuse only when there is a cycle) |
| **Needs to know in advance** | ✅ It must (max claim) | ❌ It does not |
| **When it refuses** | **Before** the allocation | **When** the cycle appears |
| **How conservative it is** | **High** (by the worst case) | **Medium** (it refuses only a cycle) |
| **Can it guarantee no deadlock** | ✅ | ✅ |
| **Where it applies** | The demand is **predictable** (memory allocation, bank lending) | The demand is **dynamic** (workflow branches, database transactions) |

**Key**: **both can guarantee no deadlock**, but:

- The banker's algorithm trades "**certainty**" for "**conservatism**"
- The wait-for graph trades "**leniency**" for "**dynamic detection**"

**Our demand is not predictable, so only the second one is usable.**

---

## 6. What industry actually does

**The banker's algorithm is hardly used in real systems**:

| System | What it uses | Why not the banker's algorithm |
|---|---|---|
| **PostgreSQL** | Deadlock detection (the wait-for graph) | The resource demand of a transaction is not predictable |
| **MySQL InnoDB** | Deadlock detection (the wait-for graph) | The same |
| **The Linux kernel** | Lock ordering + detection | The same |
| **The JVM** | Lock ordering | The same |
| **Kubernetes scheduling** | Priority + preemption | The same |

**The banker's algorithm mainly lives in textbooks** — because its premise is too strong
(knowing the maximum demand in advance).

**It is only used where "the resource demand is certain"**:

- Operating system memory allocation (the process knows how much it needs when it starts)
- The periodic tasks of a real-time system (the period and the resource demand are fixed)

**Workflow orchestration is not that kind of scenario.**

---

## 7. What you would have to do if you really wanted to use the banker's algorithm

**To show you the price**:

| Step | What you do | The price |
|---|---|---|
| 1 | **Statically analyse the workflow**: walk all the branches and compute the union of the resources | The more complex the workflow, the slower it is; a cycle needs special handling |
| 2 | **Declare the union** (the max claim) | Every workflow declares "I might want the whole island" |
| 3 | **Accept the drop in concurrency** | Two workflows may both be unable to start |
| 4 | **Recompute** once the branch is known | Complexity; and the already-held resources have to be included |
| 5 | **Declare again** when a person changes a parameter | The safety check has to be run again |

**Step 3 is fatal**:

```
A workflow declares "I need 3 devices", but 90% of the time it only uses 1
→ the system reserves space for 3
→ the other workflows can only wait
→ **the actual concurrency is 1, the theoretical concurrency is 3**
```

**Trading concurrency 3 down to 1 for "more rigor in theory" is not a good deal.**

---

## 8. The answer in one sentence

> **The banker's algorithm requires "declaring at most how many resources are needed
> before execution",
> but a workflow has branches, so it cannot be declared.
> If you force a declaration you have to use the worst case (the union of all the
> branches),
> and then every workflow wants "the whole island" and the concurrency goes to zero.**

**It is not that "the banker's algorithm is bad", it is that "its premise does not hold in
this scenario".**

**The wait-for graph does not need that premise** — it only looks at who is waiting for
whom right now, refuses a cycle,
and it guarantees no deadlock in the same way, without sacrificing concurrency.

---

## 9. If you are still not comfortable, you can do this

**A compromise: a hybrid**.

```
① Before the workflow starts: use the banker's algorithm for one "coarse screening"
   - only count the resources of the "main chain" (not the branches), declare a conservative
     but not excessive max claim
   - if even the main chain does not fit → refuse to start (tell the user in advance
     "there are not enough resources")

② During the execution: use the wait-for graph as the "fine-grained control"
   - every actual request goes through the cycle detection
   - a cycle is refused
```

**The benefit**:
- Before the start there is a "can it run" decision (the value of the banker's algorithm)
- During the execution there is a "no deadlock" guarantee (the value of the wait-for graph)

**The price**:
- Both mechanisms have to be implemented
- The definition of the "main chain" needs a rule (e.g. take the longest branch)

**This scheme is doable, but the complexity is twice that of the pure wait-for graph.**
**Recommendation: build the pure wait-for graph first, and add the coarse screening when
you really run into a scenario where "it should have been refused before the start".**
