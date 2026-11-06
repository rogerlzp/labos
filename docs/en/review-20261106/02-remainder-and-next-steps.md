# 02 · Remainder and next steps (2026-11-06)

## 1. Three things waiting on the user (blocking)

| # | Item | Why you have to decide it | What I do once you decide |
|---|---|---|---|
| 1 | **The on-site list of interruptible methods** — which methods can be stopped and which cannot | robot-arm handling (`Transfer`/`Grip`/`Release`/`Load`/`Unload`) interrupted means **the plate hits the floor** — irreversible; long processes (centrifuge / incubation / PCR / plate reading) are usually safe to stop | I set `device_method.interruptible = true` for those methods (**data only, no code change**) and run one round on the demo island |
| 2 | **The UI must tell "waiting for a human" apart from "interrupted, will retry"** | the on-site action differs: the first waits for a human to press continue, the second **re-runs that step automatically** on resume | I add the copy and the status colouring (the data side can already tell them apart: `aborted` vs `manual`) |
| 3 | **The simulator delay range for daily gate runs** | the robot arm is now at 30–300 s at random (as you specified); the cost is that **the full gate went from ~17 to ~32 minutes** (segment 2.7 alone is 14 minutes) | you pick: ① keep 30–300 s (most realistic demo, slowest gate) ② a narrow range or a fixed seed for daily runs (e.g. `30000:45000:42`), switch back to the full range when demoing ③ some other balance |

> There is one more **pure technical decision** you could take: whether the per-capability default
> timeouts added this round are sensible — in particular "thermocycler/PCR 60 minutes" and
> "scanner 10 minutes", which are the two extremes.

## 2. Open items (registered this round, **not done**)

| Item | State | Note | Suggested |
|---|---|---|---|
| **Deleting a run does not release the device lock** | ⚠ root cause untreated | this round cleans up at the **startup fallback** (ghost locks + orphan locks). But the "delete a run" action itself still does not release the lock ⇒ the device stays unusable until the **next API restart** | release it in the deletion entry points (API / background cleanup / manual SQL scripts); or give `device_state` a lease expiry (judge by `updated_at`) |
| **Pausing during a multi-device parallel execution** | ❌ not covered | the pause is **run-scoped**; with parallel branches running, "which one stops" has no definition | design needed: stop all of them? only the interruptible ones? |
| **`subProcess` silently skipped** | ❌ per the previous round's ruling | `subProcess` is not in the parser's allow-list ⇒ hooking it into the chain silently skips the successors (the whole workflow still reports `completed`) | three candidates: pre-flight interception / remove it from the palette / make the parser transparent. **Awaiting the review opinion** |
| **The UI distinguishing the interrupt state** | ❌ not done | see §1-2 | together with the on-site list |
| **Real-device validation** | ❌ not done | this round is all simulators | needs a real device + the on-site list |
| **Real long tasks (5-minute centrifuge / 30-minute incubation)** | ⚠ mechanism ready, not exercised | the timeout per method is in place (incubation 1800 s / centrifuge 900 s), but it has never run against real hardware | needs real hardware + on-site confirmation |
| **`EC-99-0005` is not registered in the error-code table** | ⚠ known | the code used for interrupts is **absent** from `error_code` ⇒ no recovery policy matches ⇒ the fallback is "manual" | register it with an explicit scope (command level, "re-run this step" rather than "manual") |

## 3. Follow-up batches and priority (proposed)

| Batch | Content | Depends on | Rough effort |
|---|---|---|---|
| **P0** | on-site list → switch on `interruptible` → one round on the demo island (incl. real hardware) | §1-1 | half a day |
| **P0** | release the device lock in the run-deletion entry points | — | 1–2 hours |
| **P1** | UI distinction for the interrupt state (copy + colouring + audit trail) | §1-1 | half a day |
| **P1** | register `EC-99-0005` and make the recovery semantics explicit (re-run this step vs manual) | — | 2–3 hours |
| **P2** | design + implement the pause semantics for multi-device parallel execution | design decision | 1–2 days |
| **P2** | deal with `subProcess` (per the review opinion) | review opinion | half a day |
| **P2** | sweep for "fixed timeout" #4: scan the code for places that assume "the device answers instantly" | — | half a day |
| **P3** | end-to-end real long tasks (centrifuge / incubation) | real hardware | 1 day |

## 4. Inputs needed (so everything can be handed over at once)

1. **The list of interruptible methods**: suggested format `capability/method → interruptible
   (true/false)`. Robot-arm handling defaults to false; long processes default to true.
2. **Access to a real device** (if real-device validation is wanted): at least one device plus a
   method that can safely be executed on site.
3. **Your preference for the simulator range** (see §1-3).
4. **Whether you accept this round's capability default timeouts** (especially the two extremes:
   thermocycler 3600 s and scanner 600 s).

## 5. Re-run reminders (for whoever picks this up)

- Full gate: `export LABOS_TRANSFER_PLAN=1 LABOS_EXEC_GRAPH=1 LABOS_DRIVER_MODE=persistent LABOS_AUTH=1`
  then `./scripts/ci-gate.sh --all`.
- **With the robot-arm simulator at a random 30–300 s the gate takes ~32 minutes**; run a narrow
  range first if you want a faster cycle.
- `verify-s3-2-residuals.py` really **kills the kernel process and restarts it** — test
  environments only.
- The DB password environment variable is `LABOS_DB_PASS`.
- The gate's "thresholds only ever go up": the `MIN_*` values at the top of
  `scripts/ci-gate.sh` may only be raised; lowering one is caught by segment 0, which scans the
  git history and turns the light red.
