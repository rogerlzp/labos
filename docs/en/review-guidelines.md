# Review Guidelines

> **English edition.** The Chinese original is [`docs/评审规范.md`](../评审规范.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> This file specifies **how to review this project, how to record a review, and how the
> reviewed party responds**.
> Purpose: make reviews traceable — who reviewed, when, with which model, which code
> they looked at, what they found, and how it was handled.

---

## 1. Why a specification is needed

Earlier reviews were scattered conversations — the review comments lived in chat logs
and so did the responses, so afterwards nobody could answer:

- Who raised this comment at the time?
- On which version of the code was it raised?
- Did the reviewed party act on it? What did it become?
- If not, why not?

**No record = no accountability + the same trap gets stepped in again.**

---

## 2. Required fields of a review record

Every review record (one file per review) must have the following fields:

| Field | Description | Example |
|---|---|---|
| **Review id** | `YYYYMMDD-NN`, incrementing for multiple reviews on one day | `20261101-01` |
| **Review time** | Precise to the minute, with the time zone | `2026-11-01 21:40 (UTC+8)` |
| **Reviewing model** | Model name and version (if known) | `Grok 4.1 Fast` / `Claude Sonnet 4.5` |
| **Review scope** | Which files were looked at, at which version (git commit) | `docs/评审指引.md` + `PositionLock.java`, commit `119271a` |
| **Review method** | Item-by-item verification against the source / reading the documents only / running the tests | Item-by-item verification against the source |
| **Conclusion summary** | Pass / conditional pass / fail | Conditional pass |
| **Comment list** | Numbered items with a severity each | See below |
| **Reviewed party's response** | Item by item: accept / do not accept, the reason, the remediation | See below |
| **Remediation status** | Fixed / to fix / will not fix (with the reason) | |

---

## 3. Severity levels of a comment

| Level | Meaning | Remediation requirement |
|---|---|---|
| **P0 blocking** | Data errors, security incidents, core functionality unusable | Must be fixed in this round, or explicitly de-scoped with a note |
| **P1 severe** | Functionality does not meet expectations, documentation seriously misleading | Fixed this round or next |
| **P2 normal** | Outdated description, poor wording, unclear boundaries | Can be scheduled |
| **P3 suggestion** | Structural improvements, naming, readability | Can be recorded and not fixed |

---

## 4. The reviewed party's response rules

### Every comment must be answered individually; "all fixed" is not acceptable

The response to each comment must contain:

1. **Conclusion**: accept / partially accept / do not accept
2. **Reason**: why it is not accepted (if it is "do not accept")
3. **Remediation**: what was changed (with a commit or file line numbers) / which round it
   is scheduled for / why it is not being fixed
4. **Verification**: how we know the change is correct

### Disallowed ways of responding

| Anti-pattern | Why it does not work |
|---|---|
| "Fixed" (without saying what changed) | Cannot be verified |
| "All review comments have been adopted" (not item by item) | There is probably something missed |
| Silently skipping a comment | That is the same as not having reviewed |
| Changing the documentation but not the code (or the other way round) | Creates a new inconsistency |

### A special requirement: an error pointed out by a review must be acknowledged

If a review points out a **specific error of the reviewed party** (for example "your
next step does not hold — the code is like this"), the reviewed party must:

1. **Acknowledge it explicitly** ("the review is right, my earlier judgement was wrong")
2. **Explain why it was wrong** (not to make excuses, but to prevent a repeat)
3. **Correct the documentation** (outdated descriptions must be fixed, not left to
   mislead the next person)

---

## 5. Preparation before a review (the reviewed party's duty)

A reviewer should not have to spend time hunting for the entry point. Before the
review, the reviewed party must have ready:

| Item | Requirement |
|---|---|
| **Entry document** | One `评审指引` that lets a reader understand the project, the code and the known problems in 30 minutes |
| **Reproducible** | The dependencies, start-up and verification commands run as they are |
| **List of known problems** | **Must be honest**, including unresolved ones |
| **Version information** | Which commit the review targets |
| **Do not hide problems** | Review material that hides problems is worthless |

### Common problems with review material (this project has hit them)

| Problem | The lesson |
|---|---|
| Only saying "completed", never saying "what was not done" | The reviewer cannot judge maturity |
| Presenting simulation results as a real pass rate | A simulation-bus walk is not a real-device pass |
| The "next step" for a known problem is a guess | Comparing with the source showed the direction was completely wrong |
| Several sets of documents each telling their own story | The reviewer does not know which one to believe |

---

## 6. What must happen after a review

| # | Action | Who |
|---|---|---|
| 1 | Write the item-by-item responses into the review record | The reviewed party |
| 2 | Fix the P0/P1 comments with verification attached | The reviewed party |
| 3 | Correct the documents whose errors were pointed out (leave no outdated descriptions) | The reviewed party |
| 4 | If the review finds a conflict in the documentation system, **converge on one story** | The reviewed party |
| 5 | At the next review, check the remediation status of the previous round's comments | Both parties |

---

## 7. Documentation hierarchy rules (specific to this project)

This project once had **four different "sources of truth"**, which left reviewers
unable to decide. The rule is now:

```
① Code + database schema        ← the only truth about landed behaviour
② Design documents in docs/ (newer than the specification)  ← designed, partly not landed
③ LabOS功能规格说明书   ← constrains only "what is still not in the release gate"
④ Existing scheduling documents in the root / docs/modules/README.md  ← obsolete, historical background only
```

**How to handle a conflict**:

- ① vs ② → **the code wins**, but the design document must be annotated with "not
  implemented in code" or "the code differs from the design"
- ② vs ③ → **change the specification first**; do not keep two stories
- Decision numbers are allocated **in blocks**, and no number may be reused globally
  (in force since 2026-11-02; the "each document invents its own D1-Dn" is void):

  | Number block | Owner | Count |
  |---|---|---|
  | **D1-D11** | Chapter 12 of `LabOS功能规格说明书.md` (= `决策记录.md`) | 11 |
  | **D101-D119** | `docs/并发调度决策记录.md` | 19 |
  | **D201-D227** | `docs/开发日志.md` | 27 |
  | **D301-D311** | Chapter 5 of `docs/评审指引.md` | 11 |

  **A cross-file reference must name the file** (e.g. "concurrent scheduling D105").
  A new decision may only continue the numbering inside its own block; when a block
  runs out, append a new block to this table — **never squeeze into another block's
  range**.

**Documents already marked obsolete** (they must start with "superseded by XX"):

- `前后端实施方案_编译甘特就绪调度.md`
- `目标功能文档_三方合并版.md`
- `LabOS_新一代中控实现总纲_20261029.md`
- `docs/modules/README.md`

---

## 8. Review record file naming

```
docs/评审记录/20261101-01-grok.md
                 │     │  └── identifier of the reviewing party
                 │     └───── which review of the day
                 └─────────── date
```

One review, one file, **append-only — never modified** (later remediation is written
in a "remediation update" section at the end of the file).
