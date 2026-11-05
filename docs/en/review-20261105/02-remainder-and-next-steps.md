# Review Package · 2026-11-05 · Remainder and next steps

> An inventory of what remains on hold / to be decided / to be scheduled after this round,
> with dependencies and priorities. Statuses are stated honestly, not glossed over.

## 1. On hold / to be decided

| Item | Status | Options / dependencies |
|---|---|---|
| `subProcess` silent skip | Registered only, code untouched (2026-11-05 ruling) | Pick one of three: preflight blocking / palette removal / transparent parser; see `docs/en/design/logic-nodes-by-layer.md` §5 |
| Batch 5 · cross-workflow parallel | On hold | Requires opening up parallel / join in `QueryService.compileExperimentGraph` first and making the experiment executor truly parallel; the island lock serialises devices on the same island |
| Script node (`scriptTask`) | To be scheduled | Three tiers: T1 registered Java / T2 uploaded sandbox / T3 external service; `runId` / `experimentId` injected via `WorkflowService` |
| Real-device onboarding (M10) | Waiting for material | Depends on the vendor manuals and the on-site confirmation checklist |

## 2. Follow-up batches and priorities

| Priority | Item | Reason |
|---|---|---|
| P0 | Real-device (not simulator) integration preparation | Timing / concurrency / exception behaviour differ from the simulator; this is the largest exposure |
| P1 | Settle the `subProcess` position (pick one of three) | The silent skip misleads users (the graph is drawn yet a whole stretch does not run and no error is raised) |
| P1 | Land rolling scheduling (spec §14.8) | If the commitment window / freeze zone fails, already-committed experiments are disrupted |
| P2 | Backend run path inserts the robot-arm transfer automatically (honest list item 10) | Requires moving the §14.2 / §14.3 auto-expansion rules from the frontend to the executor |
| P2 | Batch 5 · cross-workflow parallel | Requires settling the DAG and `callActivity` semantics first |
| P3 | Widen the page track | `/gantt`, `/topology`, `/audit` etc. still rely on manual clicking to find errors |

## 3. Required inputs

- **Vendor manuals** (centrifuge ASpin2K / sealer S100) — decide whether the M10 model library and error-code mapping can land.
- **The on-site taught `robot_point.code`** — decides whether automatic transfer insertion is feasible.
- **A multi-tenant / long-run environment** — decides whether isolation and memory-leak tests can start.
