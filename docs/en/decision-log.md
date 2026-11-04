# Decision Log

> **English edition.** The Chinese original is [`决策记录.md`](../../决策记录.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

The functional specification [`LabOS2.0_功能规格说明书.md`](../../LabOS功能规格说明书.md) chapter 12 (D1–D11) holds the decisions that were written into **2.0-draft** on 2026-10-29. This chapter does not repeat that table.

If a decision is overturned or narrowed afterwards: append a row below and change the corresponding chapter in the specification at the same time. The rows that are already written are never edited or deleted.

| Date | Reference | Change | Explanation |
|---|---|---|---|
| 2026-10-29 | D1–D11 | Adopted as the 2.0-draft baseline | See chapter 12 of the specification |
| 2026-10-29 | Chapters 4, 6, 7 | The way the reference mechanism is written was restructured according to the external review | The basis of the review is in the section below |
| 2026-10-29 | Gate 1, 7.3 | **Executing by the graph is implemented** (kernel-0.4) | 993/993 workflows completed; 193 parallel joins, 55 XOR single sides; see appendix B.1 of the specification |
| 2026-10-29 | Gate 3, ledger 2.1 | **The preflight before saving is implemented** | 993 workflows dry-run in 87 s; 896 executable / 97 warnings / 0 blocked; see appendix B.2 |
| 2026-10-29 | Appendix B.2, PF-03 | **The execution semantics are confirmed: only the connected paths are run (the BPMN standard)** | The unreachable nodes of 94 workflows have to have their connections fixed in the designer; the skipping that a conditional branch exports is normal behaviour, not a defect |
| 2026-10-29 | Gate 2, chapter 6 | **The attendance mode is implemented** | `run.attendance`; MANUAL is raised to an error alert under unattended; see appendix B.3 |
| 2026-10-29 | Appendix B.3 | **Asynchronous execution is implemented**, switching during the run takes effect | `?async=1` returns the runId immediately; measured, during 204871 the switch succeeded and it ended completed |
| 2026-10-29 | Appendix B.4, ledger 7.3 | **The Process Status View is implemented** | The live progress in memory (20/65, the current node, device 183); 51 zombie running entries were cleaned up |

---

## The record of the basis of the external review of 2026-10-29

The core opinion of the review was: **what the specification has to make up is not more feature points, but writing out clearly the mechanism of comparable product A and of comparable product B respectively, and marking which layer is taken.**

The problem the review pointed out was: the early chapter 3 was left with only one comparison table, comparable product A had been reduced to "the experience benchmark" and comparable product B had been reduced to "verifiable", and the things that really decide the system behaviour on both sides did not get into the specification.

### What each side is in charge of (the review's own words)

- **Comparable product A is in charge of "how the method is made while a person is standing next to it"**: on the Process page you drag the instrument workflow → on Play it is compiled into Procedure and Command → the command carries the simulated duration and the simulated error → the scheduler loops and executes the Run Scheduler, only the plate whose "previous step ReadyToGive, next step ReadyToTake" is moved into the queue → the deadlock prevention is written into the ready script of every instrument → an error is a 6-option window.
- **Comparable product B is in charge of "how the system continues after the person walks away"**: nestable flow control blocks (parallel, loop, conditional, wait until a certain point in time, the shortest/longest interval between two steps, exclusive resources, assignment, execute one by one over a container) → the container is an inventory, it can be picked by barcode or by attribute across workflows and replenished while running → the devices can be pooled, one going offline does not stop this batch of work → the errors are divided into the four PARS levels → Attended / Unattended can be switched while running → the simulation is another clock of the same scheduler.
- **The data of LabOS is only used to trim the priority**: utilisation 10% → do not take a global schedule; 193 parallel + 55 XOR were drawn but never executed → Decision / Parallel go into the first version; there is no plate-position data → the three-state nest of comparable product A does not go into the first version.

### The four blocks the review suggested, and where they landed

| # | Suggestion | Where it landed | Status |
|---|---|---|---|
| 1 | Put the object comparison table before chapter 6, so that the three layers of comparable product A and our three layers are not mistaken for the same thing | 7.1 The three object layers | Landed |
| 2 | Rewrite the B layer with comparable product B's flow control, the BPMN element names are only a storage format | 7.3 The B layer | Landed |
| 3 | The run mode becomes a section of its own, collecting errors, simulation and scheduling | Chapter 6 | Landed |
| 4 | The container becomes a layer of its own, split out of the scheduling | 7.5 The container, D7 | Landed |

### The primitive the review explicitly asked to add

**Time Constraint (the shortest/longest interval between two steps)** — this is a primitive that the original specification did not have.
The relation of layer A expresses "when the next block of the workflow starts", it cannot express "the taking out and the plate reading must not be more than 30 minutes apart".
It has landed in 7.3 and in ledger item 1.6.

### What the review explicitly asked to be written into the "not adopted" list

- **Comparable product A**: the six desktop tabs, arbitrary C# ready scripts, static/multi-path scheduling, the teaching pendant, the Hotels/Stacks interpolation, organising the workflow by "one column per plate type"
- **Comparable product B**: global throughput optimisation, CFR 21-11 / CLIA, a 175+ driver market, SiLA as this round's interface, a vision self-calibrating robot arm, a standalone touch screen, Add Work across workflows while running
- **Carrying over the three object layers of comparable product A**: our workflow assets are BPMN workflows, a plate is a container, not a column of the workflow

It has landed in 4.4.

### The material basis (as confirmed by the review)

| Source | The basis |
|---|---|
| Comparable product B's 2014 brochure (Top 25) | The driver library is **175+** (the "400+" written in an earlier architecture draft is in none of the three originals) |
| Comparable product B's 5.0 demo deck of 2017-10 | **9 are legible in the body text** of the flow control, the title says 10; 5.0 additionally adds Add Work |

### The old wording the review asked to invalidate

- The acceptance criterion in the three-party merged draft "drag an incubation onto the canvas and 6 child nodes grow automatically" — it conflicts with "the compile result is a projection and is not written back as a layer of BPMN nodes"; it has been marked as void in that file.
