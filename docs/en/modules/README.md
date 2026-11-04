> **⚠ This document is outdated (2026-11-01)**: the content corresponds to the early "specification level 9" design,
> the actual code structure is in [`docs/技术架构与模块地图.md`](../../技术架构与模块地图.md).
> It is kept only as historical background.

# Module Design Document Index

> The current state: **the specification exists (749 lines, "what to do"), the module design documents are essentially blank ("how to do it").**
> After the split of the frontend and the backend, both are needed — the specification for the business, the design documents for the implementation.

## The uniform structure of every module design document

When you create a module document, write it with these 7 sections; if one is missing,
write "to be filled in", do not leave it out:

| Section | Content |
|---|---|
| 1 Responsibility and boundaries | What this module is in charge of, **and what it is not** (the boundary matters more than the responsibility) |
| 2 Data model | The tables involved, the key fields, the foreign keys, the enum values |
| 3 Interface contract | The list of APIs + the request/response types (kept in sync with `webapp/src/api/client.ts`) |
| 4 State and flow | The state machine / the flow chart / the sequence |
| 5 Error codes | The error codes this module produces and the recommended handling |
| 6 Concurrency and transactions | Who writes concurrently, whether a lock is needed, how a failure rolls back |
| 7 Known issues | The to-dos, the defects, the disputed designs |

## The list of modules (corresponding to level 9 of chapter 8 of the specification)

| # | Module | Document | Status |
|---|---|---|---|
| M1 | Orchestration (the BPMN designer / compilation / experiment orchestration) | `M1-编排.md` | ⬜ To be written |
| M2 | The preflight before saving | `M2-预检.md` | ⬜ To be written (the code exists, the document is missing) |
| M3 | Execution (the state machine / multi-step / asynchronous / attendance) | `M3-执行.md` | ⬜ To be written |
| M4 | Scheduling (the readiness decision / plate tracking) | `M4-调度.md` | ⬜ To be written |
| M5 | Simulation (the duration model / the Gantt / the time constraints) | `M5-仿真.md` | ⬜ To be written |
| M6 | Errors and recovery (the policies / the decision / the alert aggregation) | `M6-错误恢复.md` | ⬜ To be written |
| M7 | Monitoring (the alert handling / Process Status) | `M7-监控.md` | ⬜ To be written |
| M8 | The device drivers (the contract / the sidecar / the shadow mode) | `M8-驱动.md` | 🟡 Partial (`labos-kernel/DRIVER_CONTRACT.md` + `docs/NET_SIDECAR_DESIGN.md` already exist) |
| M9 | Operations (the policy management / the permissions / the audit) | `M9-运维.md` | ⬜ To be written |

## The documents that already exist and can be reused

| Document | What it covers |
|---|---|
| `labos-kernel/DRIVER_CONTRACT.md` | The M8 driver contract |
| `labos-kernel/docs/NET_SIDECAR_DESIGN.md` | The M8 C# sidecar |
| `labos-kernel/ALERT_REPORT.md` | The measured M6 alert noise reduction |
| `labos-kernel/COVERAGE_REPORT.md` | The M6 policy coverage |
| `labos-kernel/BATCH_VALIDATION_REPORT.md` | The M1/M3 batch acceptance |
