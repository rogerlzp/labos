# LabOS Test Specification

> **⚠ DEPRECATED (2026-11-08)**: Superseded by the Testing Master
> (`docs/测试总纲.md`, Chinese, authoritative). The layering model, thresholds, gate
> counts and case counts below are historical snapshots and are **not** current policy.
> This file is kept as historical background under the append-only documentation rule.

> The question this answers: **after changing a feature, on what basis do you claim
> it is not broken and did not break anything else?**
> The answer: write the cases before the implementation; keep both a script track and
> a page track; the change counts as done only when the cases pass, and only then may
> you `git commit`.

Companion documents: [testing-and-release-strategy.md](testing-and-release-strategy.md)
(the L1–L5 verification model),
[full-flow-test-matrix-design.md](full-flow-test-matrix-design.md) (the 45-case flow matrix),
[test-case-master-table.md](test-case-master-table.md) (the full ledger of
feature × case × layer × CI membership).

---

## 1. Three hard rules

If these are not met, the change counts as **not done** and must not be committed.

| # | Rule | How it is enforced |
|---|---|---|
| 1 | **Test first**: write the case before the implementation | The case may start red (failing); at commit time it must be green |
| 2 | **Two tracks**: both script tests and page tests | Backend/orchestration changes → script track; UI changes → page track; both → both |
| 3 | **Pass before commit**: all cases green before `git commit` | Run `./scripts/ci-gate.sh --all` before committing; non-zero means no commit |

**Why test first**: writing the case first forces you to settle what the feature is
actually supposed to output. Writing the case after the implementation usually
captures "whatever the implementation currently does", which cannot catch regressions.

---

## 2. The two tracks

| Track | What it runs | Where it lives | What must be running | Unit of judgement |
|---|---|---|---|---|
| **Script track** | Pure-logic unit tests, DB unit tests, chain scripts, flow matrix | `labos-kernel/src/test/`, `scripts/verify-*.py`, `scripts/verify-*.sh` | Depends on the script (pure logic needs nothing) | Assertion count |
| **Page track** | Real browser clicks: login, build a workflow, build an experiment, run, pause/resume, alerts, policies | `scripts/e2e-*.py` (Python Playwright) | Frontend + API (+ simulators, page-dependent) | Assertion count |

**Why the page track must exist separately**: `run.status=completed` in the database
does not mean the page rendered correctly. Historically, five real bugs (a graph
showing "not executed", a button that would not refresh, a save with no confirmation)
were found by clicking through the page — the script track sees none of them.

### 2.1 Which track a change needs

| Kind of change | Script track | Page track |
|---|---|---|
| Backend logic (state machine, evaluator, recovery policy, scheduling) | Required | Not needed |
| Database read/write (batch creation, holds, pause persistence) | Required (DB unit test) | Not needed |
| API contract (fields added, removed, renamed) | Required; update `webapp/src/api/client.ts` too | Required if it is displayed |
| UI interaction (buttons, modals, empty states, read-only disabling) | Not needed | Required |
| End-to-end chain (page → API → driver → device) | Required (chain script) | Required (e2e) |

---

## 3. Changing a feature means changing its cases

**Execution record, 2026-11-05 (designer main path)**: after adding "device images / automatic robot-arm insertion across devices / explicit delete", `scripts/e2e-method-drop.py` grew from 10 to 20 assertions (17 resident without a database), `MIN_METHOD_DROP` in `ci-gate.sh` rose from 9 to 17, and section 4.12 covers the new assertions; the constants table, script list and item 10 of the honest list in `test-case-master-table.md`, plus the script table and the gate-section description in `review-20261103/04-testing.md`, were updated in the same batch. This record shows that "changing a feature means changing its cases" is not a slogan: all four number/list locations are committed together.

| Scenario | What you must do |
|---|---|
| New feature | Add cases; add a `MIN_*` threshold at the top of `scripts/ci-gate.sh` and wire the section |
| Changed behaviour | Change the expected values in the cases; **changing the implementation alone is not allowed** |
| Removed feature | Delete the cases and lower the matching `MIN_*` threshold (state the reason in the commit message) |
| Renamed API field | Change the field name, the `client.ts` type and the page rendering together |
| Pure refactor (no behaviour change) | Do not touch the cases; run them once to confirm the count and result are unchanged |

**Thresholds may only go up**: the `MIN_*` constants at the top of `ci-gate.sh` are
the lower bound on the number of cases. Fewer turns the gate red, forcing the
question "where did the missing cases go?". To genuinely delete cases you must edit
the constant and explain it in the commit message.

---

## 4. How to write a case

### 4.1 Script-track conventions

| Convention | Detail |
|---|---|
| Where | Pure-logic / DB unit tests go in `labos-kernel/src/test/java/`; environment-dependent chain tests go in `scripts/` |
| Output | The last line must print `通过 N / 失败 M` (the gate extracts these two numbers with a regex) |
| Skip | When the environment is missing, print `SKIP` on the **first line**; the gate reads that as "skipped", not "failed" |
| Database | Never fall back to `127.0.0.1`; set `LABOS_DB_HOST` and friends explicitly |
| Cleanup | Clean up whatever you created (workflows, experiments, holds) so the next run's assertions still hold |

### 4.2 Page-track conventions

| Convention | Detail |
|---|---|
| Where | `scripts/e2e-*.py`, Python Playwright (already installed locally; no npm dependency is added) |
| Arguments | At minimum `--base` (default `http://localhost:5173`), `--user`, `--pass`, `--headed` |
| Counting | A shared `ok()` / `bad()` accumulates `PASS` / `FAIL`; the last line prints `通过 N / 失败 M` |
| Text | antd Chinese buttons contain a space (`登 录`, `保 存`); match with a regex like `re.compile(r"保\s*存")` |
| Read-only | The viewer read-only state comes from `useReadOnly()`; the case must assert that writable buttons are disabled |
| Cleanup | Delete the workflows / experiments / alerts the case created, so the next run is not polluted |

### 4.3 The four surfaces every page case must cover

Adding a page, or changing an interaction on one, must cover at least:

| # | Surface | Example |
|---|---|---|
| 1 | List / empty-state rendering | The table has rows; the empty-state text appears |
| 2 | Main action reachable | Clicking into the detail, the modal opens, save confirms |
| 3 | The database / API actually changed | After clicking pause, `run.status` really is `paused` |
| 4 | Read-only boundary | Under viewer the buttons are disabled and give the reason |

---

## 5. Environment and SKIP rules

| Variable | Purpose | When missing |
|---|---|---|
| `LABOS_DB_HOST` / `_PORT` / `_NAME` / `_USER` / `_PASS` | Database for DB tests and chains | The DB section SKIPs (not a failure) |
| `LABOS_API` | API address (default `http://127.0.0.1:18080`) | API scripts SKIP |
| `LABOS_WEB` | Frontend address (default `http://localhost:5173`; nginx 8080 also works) | The page track SKIPs |
| Simulators 19500–19506 | Simulator integration (`e2e-sim-flow.py`) | That section SKIPs |

**SKIP is neither a failure nor a pass**: the gate prints SKIP separately so a
reviewer can see "this did not run" instead of having it counted as green.
**Silently counting SKIP as a pass is forbidden.**

To create a simulation environment: `labos-kernel/scripts/sim-up.sh` starts seven
simulators (19500–19506).

---

## 6. How the gates join up

| Gate | Location | When it runs |
|---|---|---|
| `./scripts/ci-gate.sh` | Local + GitHub CI | Every push / PR (pure logic 624 + tsc + schema-artifact consistency) |
| `./scripts/ci-gate.sh --all` | Local | Before committing; page track + chain + flow matrix + simulator integration |
| `.githooks/pre-push` | Local git hook | Before every `git push`; runs `--all` and blocks the push on failure |
| `.github/workflows/e2e-nightly.yml` | GitHub | Runs the page track on a schedule, covering the "CI only runs pure logic" blind spot |

**Why a nightly job is needed**: `.github/workflows/ci.yml` only runs the parts that
need no database. The page e2e needs the whole stack running, so for a long time the
**page track never executed in CI**. The nightly job brings up the full stack
(PostgreSQL + API + frontend) to cover that gap. The local pre-push hook blocks a
push before it leaves the machine.

Enable the local hook (once):

```bash
git config core.hooksPath .githooks
```

---

## 7. Pre-commit checklist

- [ ] The cases for the new/changed behaviour are written or updated, covering the tracks that apply
- [ ] `./scripts/ci-gate.sh --all` is fully green (explain any SKIP sections in the commit message)
- [ ] The `MIN_*` thresholds at the top of `ci-gate.sh` match the number of cases
- [ ] If an API field changed: the `client.ts` type and the page rendering both changed
- [ ] The test data created has been cleaned up (workflows, experiments, holds, alerts)
- [ ] Chinese/English documents are in sync (if touched); `python3 scripts/verify-en-docs.py` reports 0 hard errors

---

## 8. Where the master table lives

The full inventory — feature ledger (L1–L10) × case × script × layer × CI membership —
lives in [test-case-master-table.md](test-case-master-table.md). **Update the master
table whenever you add a case**; it must agree with the thresholds in `ci-gate.sh`.
