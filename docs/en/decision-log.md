# Decision Log

> **English edition.** The Chinese original is [`决策记录.md`](../../决策记录.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

The functional specification [docs/en/functional-specification.md](functional-specification.md) chapter 12 (D1–D11) holds the decisions that were written into **2.0-draft** on 2026-10-29. This chapter does not repeat that table.

If a decision is overturned or narrowed afterwards: append a row below and change the corresponding chapter in the specification at the same time. The rows that are already written are never edited or deleted.

| Date | Reference | Change | Explanation |
|---|---|---|---|
| 2026-10-29 | D1–D11 | Adopted as the 2.0-draft baseline | See chapter 12 of the specification |
| 2026-10-29 | Chapters 4, 6, 7 | The specification now states this system's own control semantics, following the external review | The basis of the review is in the section below |
| 2026-10-29 | Gate 1, 7.3 | **Executing by the graph is implemented** (kernel-0.4) | The full workflow set is walked on the simulation bus by the graph; see appendix B.1 of the specification |
| 2026-10-29 | Gate 3, ledger 2.1 | **The preflight before saving is implemented** | A full dry run finishes with nothing blocked; see appendix B.2 |
| 2026-10-29 | Appendix B.2, PF-03 | **The execution semantics are confirmed: only the connected paths are run (the BPMN standard)** | Unreachable nodes have to have their connections fixed in the designer; the skipping that a conditional branch exports is normal behaviour, not a defect |
| 2026-10-29 | Gate 2, chapter 6 | **The attendance mode is implemented** | `run.attendance`; MANUAL is raised to an error alert under unattended; see appendix B.3 |
| 2026-10-29 | Appendix B.3 | **Asynchronous execution is implemented**, switching during the run takes effect | `?async=1` returns the runId immediately; measured, a switch during a run succeeded and it ended completed |
| 2026-10-29 | Appendix B.4, ledger 7.3 | **The run-status view is implemented** | Live progress in memory (the current node and the device); leftover zombie running entries were cleaned up |

---

## External review, 2026-10-29

The review asked for this system's own control semantics to be written out: object layers, control inside a workflow, run mode, and containers. Manuals, brochures and demo decks of other products are not excerpted.

| # | Where it landed | Status |
|---|---|---|
| 1 | 7.1 The three object layers | Landed |
| 2 | 7.3 Inside the workflow; BPMN element names are only a storage format | Landed |
| 3 | Chapter 6, the run mode | Landed |
| 4 | 7.5 The container, D7 | Landed |

**Time Constraint (the shortest/longest interval between two steps)** has landed in 7.3 and in ledger item 1.6.
A relation between experiment blocks only says when the next block starts. It cannot say "taking the plate out and reading it must not be more than 30 minutes apart".

Not included in this version: arbitrary scripts overriding the readiness decision, global schedule optimisation, the teaching pendant, and treating any one device-interoperability standard as this period's interface. This has landed in 4.3.

The acceptance criterion in the merged draft, "drag an incubation onto the canvas and 6 child nodes grow automatically", conflicts with "the compile result is a projection and is not written back as a layer of BPMN nodes". It has been marked as void in that file.
