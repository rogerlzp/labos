# Follow-up Plan (2026-11-04)

> **English edition.** The Chinese original is
> [`docs/评审包-20261104/02-后续规划.md`](../../评审包-20261104/02-后续规划.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are
> identical to the Chinese source — if they ever disagree, the Chinese original wins.

> Companions: `00-索引与评审指引.md` / `01-本轮交付清单与验证.md`.
> Principles: ① anything real-device related waits for material, no speculative code;
> ② every round must be re-runnable (tests / gate / scripts) + the documentation must be
> in sync; ③ platformisation is designed before it is coded.

## 1. Inventory of the remaining work

| # | Item | Source | Status | Dependencies / notes |
|---|---|---|---|---|
| 1 | M10 real-device integration: 360R / PlateLoc manual onboarding, long-task busy/dead rule, on-site checklist, error-code feedback | ROADMAP M10 | **Deferred, waiting for material**; the preparation checklist + the model-library page/API/go-live check are already in place | Vendor manuals, on-site window, safety owner |
| 2 | Multi-tenant isolation | M11 | Not done | Deployment shape / customer requirements |
| 3 | Reporting / BI | M11 | Not done (the permission basics are closed) | The definitions need business sign-off; simulation data can be used first |
| 4 | Multi-instance deployment | M11 | Not done | The scheduler's in-memory queue/locks, sessions and the startup settle definition all have to change |
| 5 | Stale documentation items | ROADMAP M6/M7/M8 historical checkboxes, review-package updates | ✅ Closed (2026-11-04, A2) | None |
| 6 | Technical debt | The `app_role` unused-table decision, the silence API UI, uncovered write entry points, the coverage baseline (not yet ticked in M8), gating the device chain with `--with-db` in CI | Small items accumulate | None |
| 7 | Review loop | BUG ledger + AI review | Continuous | A review package per round |

## 2. Phased plan

### Phase A · Can start immediately (estimated 1–2 weeks)

**A1 Run reporting / statistics foundation (recommended as the next round)**
- Goal: let operations and reviewers **quantify** run quality (without real-device
  data — the existing 678 experiments / 39 runs are enough to get it working, and it
  accumulates naturally once real devices arrive).
- Scope: `GET /api/reports/summary?from=&to=` (experiment count / success rate /
  average duration / by island / top-N by workflow / top-N failure reasons); a frontend
  "run report" page + CSV export; a database self-test + e2e assertion.
- Acceptance: the endpoint's numbers reconcile against SQL; the page and the export
  work; an e2e assertion is added; the definitions (how success rate and duration are
  computed) are documented.
- Dependencies: none | Estimate: 2–3 days.

**A2 Documentation and ROADMAP consistency closure** (✅ completed 2026-11-04)
- Goal: clear out the historical items in M6/M7/M8 that were done but not ticked,
  sync the review packages and the index, and avoid "the reviewer misreads the
  progress".
- Scope: a full ROADMAP cross-check + a development-log index + the reference
  relationships between the 02/03 review packages.
- Acceptance: `grep '\[ \]' ROADMAP.md` leaves only genuinely unfinished items; a
  reviewer can reproduce it. | Estimate: 0.5–1 day.

**A3 Test quality and gating**
- Goal: control the maintenance cost of the 907 with-database tests and raise the
  value of the gate.
- Scope: a coverage baseline (simple counting, may only rise), a plan to gate the
  device chain / parallel capability with `--with-db` in `ci-gate.sh`, an update to the
  soak script (2-hour baseline); an assessment of whether the "assertion-count
  threshold" should become a "list of critical test cases".
- Acceptance: the gate script is re-runnable; the baseline numbers are recorded. | Estimate: 1–2 days.

**A4 (optional) Frontend permission loose ends**: connect the alert silence API to the
UI, add the read-only disable to the remaining uncovered write entry points. | 0.5 day.

### Phase B · M10 real-device integration (waiting for material; 2–4 weeks on site)

**B1 Manual onboarding (the 10 steps of the preparation checklist)**
- Goal: 360R / PlateLoc go from PLANNED → MANUAL, with the simulator, the adapter, the
  error codes and the documentation all from one source.
- Scope: fill in the A–G templates → add the models to DeviceModels → regenerate the
  seed → align the simulator behaviour → bind the devices → single-machine smoke →
  mixed-line acceptance.
- Acceptance: the M10 acceptance (a process with ≥3 devices and ≥5 nodes runs through
  + a recording of failure → recovery → continue). | Dependencies: the manual / the
  on-site window.

**B2 Landing the long-task "busy vs dead" rule**
- Goal: after a timeout on a long task such as centrifugation or sealing, there is no
  more "false failure → resend".
- Scope: `DeviceModel` gains an optional `busyQueryCmd` / `maxActionSec` (or
  equivalent fields); the executor policy: with a busy/idle query → on timeout, query
  first (busy = keep waiting, dead = abort + alarm); without a query → the measured
  maximum × 1.5 and no automatic resend on timeout; pure-logic tests + simulator cases. | Dependencies: the manual's
  busy/idle and status-query commands; estimate: 3–5 days (excluding on-site calibration).

**B3 Tooling for error-code feedback**
- Goal: an unregistered code seen on site can be fed quickly into `DeviceModels` + the
  seed + a simulator-injected regression.
- Scope: `scripts/errorcode-intake.py` (raw code / frame / context → a DeviceModels
  fragment + an injected test-case template); linked with the EC-08 coverage check in
  `device-go-live-check.py`. | Estimate: 2–3 days.

**B4 Extending the on-site integration checklist into a tool**
- Goal: turn `device-go-live-check.py` into an on-site checklist (shadow probe /
  preflight, real-device smoke steps, evidence archiving). | Estimate: 1–2 days.

### Phase C · M11 platformisation (design before coding, 4 weeks+)

**C1 Multi-tenant design** (2–3 days of design): the tenant isolation boundary
(connections / RLS / data archiving), audit, the migration path, alignment with the
existing tenant_id.
**C2 Multi-instance design** (2–3 days of design, then coding): externalising the
scheduler's in-memory queue / island locks (DB / Redis), changing the startup settle
from a single-instance definition to owner / heartbeat, token sharing (Redis),
de-duplicating scheduled jobs.
**C3 Reporting depth / BI + finer permissions**: multi-tenant reporting, the "enable or
delete" decision for the currently-unused `app_role` tables, approval flows (if needed).
- Dependencies: start once the deployment shape / customer requirements are clear, to
  avoid rework.

### Phase D · Continuous

- D1 Review loop: AI review + BUG ledger + development log per round; this round's
  review conclusions land in the ROADMAP.
- D2 Technical debt: the `app_role` unused-table cleanup decision, the two-track error
  codes, test maintenance cost, further slimming of `ApiServer` (the main body is
  done).
- D3 Operations experience: notification channels (alert silence / email / webhook),
  report export, a duty-handover view.

## 3. Ordering and dependencies (why it is ordered this way)

1. A1 reporting does not depend on a real device, so it delivers operational and
   review value immediately while defining the display definitions for M10 on-site
   data;
2. A2 / A3 are done before large amounts of real-device data arrive, so the
   documentation and the gate do not degrade early;
3. B must wait for the manuals; B2 depends hard on the manual's busy/idle and
   status-query commands, so no speculative logic may be written first;
4. C must wait until the deployment shape / customer requirements are clear, otherwise
   multi-instance and multi-tenant work is redone;
5. The device-related UI polish (model-library icon consistency, the device-type
   vocabulary, the production-line ledger details) follows M10 instead of taking a
   round of its own.

## 4. Inputs needed from the user / the vendors

| # | Item | Used for |
|---|---|---|
| 1 | 360R / PlateLoc communication interface description, error-code table, firmware version, on-site connection method | B1 / B2 / B3 blockers |
| 2 | On-site integration window, safety owner, permission for destructive tests | B1 / B4 |
| 3 | Reporting definitions: who looks, at which dimensions, export format / frequency | A1 |
| 4 | Deployment shape: single / multi instance, cloud / on-site machine, whether multi-tenancy is needed | C1 / C2 |
| 5 | Permission requirements: approval flows, account sources (LDAP / SSO), audit export | C3 / A4 |
| 6 | Long-task alarm thresholds and the handling procedure (30-minute centrifugation, etc.) | B2 |

## 5. Risks

- Phase B depends hard on the site / the material, so the timing is uncontrollable →
  use the groundwork tooling (model-library page / go-live script / preparation
  checklist) to reduce idle time;
- Phase C changes a lot (scheduler / sessions), so the design must be reviewed before
  any code is touched;
- The data volume is small during the simulation phase, so conclusions must be
  labelled with the definitions and the sample size;
- The test baseline keeps rising (907) — the maintenance cost of the test cases has to
  be controlled (extract common assertions, avoid duplication);
- Blocking issues found in review take priority over new features (the BUG ledger
  drives this).

## 6. Suggested next round (for scheduling)

**A1 run reporting / statistics foundation (2–3 days)** (the A2 documentation closure
was completed on 2026-11-04):
- Deliverables: the report endpoint / page / CSV + e2e assertions + a numerical
  reconciliation;
- Acceptance: `./scripts/test-all.sh` all green, `ci-gate.sh --e2e` passes, the report
  numbers match a spot-check against SQL, page screenshots;
- Dependencies: none (no real device, no manual needed) — can start immediately.
