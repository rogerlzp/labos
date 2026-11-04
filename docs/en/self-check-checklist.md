# Self-Check Checklist

> **English edition.** The Chinese original is [`docs/自查清单.md`](../自查清单.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> **Where it comes from**: three consecutive review rounds on 2026-11-02. The mistakes I
> made were not a number of independent bugs, but the same meta-problem —
>
> **taking "I did not find it" for "it does not exist", and taking "it is roughly like
> this" for "it was measured".**
>
> The rules written into the documents ("every assertion has to be verifiable in reverse")
> did not stop me,
> because they rely on "diligence". This checklist turns them into **actions you can tick
> off**.

---

## How to use it

Tick it off per scenario, **do not commit until it is ticked off**. Every entry gives the "action" and the "counter-example" —
the counter-examples are all ones I hit myself, none of them are hypothetical.

---

## A. The search kind (before saying "there is none / it does not exist")

| # | Action | The criterion for deciding |
|---|---|---|
| A1 | First list **all the file types the problem could be in**: `.java` / `.sql` / `.ts` / `.tsx` / `.sh` / `.md` / `.yml` / `.xml` | Write it on a sticky note, do not just run through it in your head |
| A2 | When you `grep`, **do not casually add `--include`**; if you add it, add all of them | The types covered are visible in the command |
| A3 | Confirm that no directory is excluded (`out/`, `node_modules/`, `dist/`, `.git/`) | There is no `-exclude-dir` in the command, or a known exception |
| A4 | Write the conclusion as "**0 hits within X/Y/Z**", **not as "it does not exist"** | The scope is in the sentence |
| A5 | **Counter-example verification**: deliberately search for a string you know exists and confirm that the command really can find it | That string has hits, only then does it prove that the search works |

**The pitfalls I hit**

| When | What I did | What was actually the case |
|---|---|---|
| 20261102-03 | `grep -rn "ROLE1" --include=*.java` → 0 hits → judged "P0-11 does not exist" | The problem was in `95_seed_data.sql`, **the defect really existed** |
| Earlier | `grep wireName` excluding `out/` → judged "there is no wireName" | There was one, it was just excluded |

> **A5 is the most important one**: a "0 hits" without counter-example verification is
> the same as not having searched.

---

## B. The number kind (before writing down any quantity)

| # | Action | The criterion for deciding |
|---|---|---|
| B1 | Every number **carries the command + the output** (or at least the command is written into the document) | The reader can copy the command and reproduce it |
| B2 | When the same metric appears in several documents, **define it in one place only and reference it elsewhere** | Two different values never appear |
| B3 | Before delivery, run it again: `git rev-list --count`, `wc -l`, `git diff --shortstat` | It agrees with the document |
| B4 | When the same metric is written in two places in the document, **cross-check them** | Changing one place changes both |
| **B5** | **When you change a number you have to run the command again**, you cannot fill it in from "I remember measuring it" | There is the output from this run |
| B6 | Next to the number, note the **point in time when it was measured** (e.g. "20 at the time this section was written, +1 since") | This avoids a false error caused by it changing after it was written |

**The pitfalls I hit**

| When | What I wrote | What was measured |
|---|---|---|
| 20261102-02 | The number of table rows in the review package was "inflated row by row" | It differs a lot from `wc -l` |
| 20261102-04 | 12 commits / 527 lines / 172 files | Actually **19** commits / **1052** lines of source / **185** files |
| 20261102-04 | §3 unit tests 106, §11.4 unit tests 104 | **106** |

> **The root cause**: writing it from an impression. **"I remember roughly twenty commits"
> is a high-incidence zone for hallucinations.**

---

## C. The code-change kind (before the change / after the change)

| # | Action | The criterion for deciding |
|---|---|---|
| C1 | Before changing the decision logic, **search for every implementation of the same semantics** | `grep` the key condition fragment and list all the hit points |
| C2 | After the change, `grep` **the characteristic fragment of the old logic** and confirm nothing is left | The old fragment has 0 hits (or it is only in a comment) |
| C3 | When you change an interface / a constructor, search **all the call sites** and confirm no `null` assembly is left behind | Every call site has been looked at |
| C4 | "Fixed" = **the call chain works + it can be verified**, not "I changed the code" | You can point at who calls it |
| C5 | Before deleting dead code, `grep` first to confirm there is no caller | Only the definition itself is left |

**The pitfalls I hit**

| Problem | What I did | The consequence |
|---|---|---|
| P1-4 Position reservation | I changed `tryReserve`, **but not `firstAvailablePosition`** | Two sets of decisions for the same semantics → the position that gets picked can never be reserved |
| P0-4 Automatic recovery | `autoResumeFaulted()` was written, **but it had no caller** | The device recovered and the workflow still did not move |
| P1-10 The three states of a plate position | `markPlateUnknown()` was written, **but it had no caller** | The plate was lost and the system still thought it was at the source position |
| P1-1 Resource protection | `db=null` during the assembly | **The four layers of resource protection were never in effect** |

> **C4 is the common lesson of these four: written ≠ connected, connected ≠ correct,
> correct ≠ in effect.**

---

## D. The conclusion kind

| # | Action | The criterion for deciding |
|---|---|---|
| D1 | Every "does not exist / is fixed / all of them" in a conclusion has to point at **the evidence** (file:line or the command output) | Each one can be pointed at |
| D2 | Distinguish "**readable from the code**" from "**verified by running it**", and write the boundary down in the document | There is a statement like "this review is at the code-reading level" |
| D3 | Do not take "I have reviewed it" as evidence — **the review process itself has to have an output** | There is a command, an output and a comparison |

**The pitfall I hit**

- The review package writes "P0 18/18 all fixed", while the record in the same file says
  "15 fixed + 2 partly fixed + 1 does not exist"
  → **two documents of the same party contradict each other**, and both use a vague word
  like "fixed".

---

## E. Before committing (the last gate)

| # | Action | The criterion for deciding |
|---|---|---|
| E1 | Search the whole text for "fixed / all of them / does not exist" and confirm the evidence for each one | No conclusion without evidence |
| E2 | Is there only **one** way of saying the same thing in the whole text | No self-contradiction |
| E3 | Every number is reproducible | The command can be run |
| E4 | The number in the commit message agrees with the actual change | It matches up |
| E5 | The scope of the change agrees with the scope described in the commit message | Nothing smuggled in |
| E6 | **Do not use `git add -A`**; list the paths you want to commit explicitly | `git add <the specific files>` |
| E7 | Before committing, go through `git status` line by line for the additions / deletions | Every file can be explained |

---

## The three hard rules (if you only remember three)

### 1. "0 hits" is not a conclusion, it is a **signal to be verified**

A zero-hit result has to go through A5 (the counter-example verification) first, and only then do you write "0 hits within X".
**Never write "it does not exist".**

### 2. Every number needs a command output, otherwise it is not written

A number written from an impression has a **lower accuracy than random** (I was wrong three times).

### 3. "Fixed" means **being able to point at the call chain**

Not "I changed the code", but "who calls it, and how do I verify it".

---

## Appendix 2: this checklist caught me the first time it was used

Right after writing the checklist, on my first commit I used `git add -A` and committed 8 unrelated HTML files under `docs/海外市场路径研究/` (about 2684 lines) along with it — which
is exactly the "smuggling" that E5/E6 are there to prevent.

**The lesson**: having the checklist in the document is useless, **you have to actually follow it before committing**.
E6 (banning `git add -A`) and E7 (reading `git status` line by line before committing) have been added.

---

## Appendix: this checklist itself has to be checked

| Check item | Explanation |
|---|---|
| Is it mechanically executable | Every entry is "action + the criterion for deciding", not "be careful" |
| Does it cover the mistakes already made | A/B/C/D/E correspond respectively to: a wrong scope, wrong numbers, an incomplete fix, an empty conclusion, a failed delivery |
| Will it go out of date | Every time a new kind of mistake is made, add an entry to the matching section + a counter-example |
