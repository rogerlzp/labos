# M10 Prerequisite · Manual → Model-Library Onboarding Preparation Checklist v0.1

> **English edition.** The Chinese original is [`docs/设备/M10-手册到型号库接入准备清单-v0.1.md`](../../设备/M10-手册到型号库接入准备清单-v0.1.md); this is a line-for-line translation. Numbers,
> commit hashes and file paths are identical to the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 (2026-11-03) |
| Status | Awaiting review (M10 prerequisite material; once the manuals are in hand, execute this document) |
| What question it answers | What material is still missing to connect a real 360R / PlateLoc, how it goes into the model library once it arrives, and how "onboarding finished" is counted |
| What it does not do | This document does not write driver code and does not guess the protocol/parameters; every item without a manual is marked ⬜ and waits for the material |

---

## 1. Inventory of the current state (2026-11-03)

### 1.1 The four-layer structure of the model library

| Layer | Code / location | What it answers | Does it change when a new device is onboarded |
|---|---|---|---|
| Device type | `labos-kernel/src/main/java/com/labos/kernel/sim/DeviceProfile.java` | Parameter boundaries (speed / volume range / temperature…) | Only for a new category |
| Concrete model | `labos-kernel/src/main/java/com/labos/kernel/device/DeviceModels.java` | Real command names / parameters / timeouts / error codes | **Change here (one definition per model)** |
| Model behaviour | `sim/S100Behavior` / `sim/AspinBehavior` | Timing constraints (tray origin, two doors, create modules first) | Only written for models that have timing constraints |
| Recovery policy | `pg_rebuild/schema/95_seed_data.sql` | How the control console reacts after an error | **Generated from the model library, never hand-written** |

References: `docs/设备/型号库与错误码映射.md` (the five steps to add a device + the code chain), `docs/模拟器设备库.md` (the parameter boundaries and typical methods of the 14 device
types), `docs/设备/现场确认清单.md` (the items the manual does not write down and that have to be measured on site).

### 1.2 Existing models and manuals

| Model | Type | Manual status | Evidence |
|---|---|---|---|
| aspin-2k (Shanghai Aiao ASpin 2K) | Centrifuge | ✅ Present (V2.0 2023-06-30) | `docs/设备/AC离心机接口协议使用说明V2.0F.pdf` |
| s100 (sealer) | Sealer | ✅ Present | `docs/设备/S100封膜机通信接口说明 .docx` |
| **hettich-rotanta-460r (`model=360R` in the database)** | Centrifuge | ❌ **The communication interface specification is missing**, **1 unit is running** on the production line | Placeholder in `DeviceModels.PLANNED` |
| **agilent-placeloc (`model=PlateLoc` in the database)** | Sealer | ❌ **The communication interface specification is missing**, **2 units are running** on the production line; semi-automatic heat sealing, not from the same protocol source as the S100 | Placeholder in `DeviceModels.PLANNED` |
| eppendorf-5810r | Centrifuge | ❌ No communication interface specification (only the public specification) | Placeholder in `DeviceModels.PLANNED` |
| thermo-multifuge-x1r | Centrifuge | ❌ No communication interface specification | Placeholder in `DeviceModels.PLANNED` |
| beckman-allegra-x15r | Centrifuge | ❌ No communication interface specification | Placeholder in `DeviceModels.PLANNED` |
| domestic-centrifuge-generic | Centrifuge | ❌ No concrete vendor model (generic placeholder) | Placeholder in `DeviceModels.PLANNED` |

> `PLANNED` has **6 entries** in total; of those, **360R (1 unit on the production line) / PlateLoc (2 units)** are the M10 blockers that
> are running on the line but have no manual.

### 1.3 The current state in the database (labos_demo)

- The device table holds 76 model records; the production database has real device instances with model=360R / model=PlateLoc;
- Currently **not a single device has a model bound** (deliberate: without a manual, nothing is bound at random, to avoid
  mistranslating the 360R with the error-code meaning of aspin-2k);
- 13 driver endpoints (protocol tcp / netdll), the simulation chain already works (sidecar → simulator);
- Error-code block usage: S100 = 0x40, ASpin 2K = 0x41; **0x42 / 0x43 are recommended for 360R / PlateLoc** (to be confirmed by the
  review, see §5).

### 1.4 Tools that can be built first (2026-11-03, do them while waiting for the manuals)

| Tool | Location | What it answers | Commit |
|---|---|---|---|
| Model-library page | `/models` (Operations → Model Library) | Which models have a manual and can be connected (MANUAL), which are only placeholders (PLANNED), how many units are running on the line without a manual (360R 1 unit / PlateLoc 2 units, flagged in red) | `a1b21ef` |
| Model-library API | `GET /api/meta/device-models` | The page's data source: the manual details (evidence / manual / number of commands / number of unverified ones / number of error codes / timeouts) + the planned details (what is missing / the recognisable name / how many units are running on the line) | `f2d7e8e` |
| Pre-go-live check script | `scripts/device-go-live-check.py` | On-site commissioning, item by item: PASS/WARN/FAIL — heartbeat freshness, number of drivers online, driver endpoints, model manual coverage, device→model binding rate, error-code EC-08 coverage, capability inventory, topology; with `--strict` a WARN also fails | `4196f1c` |

Measured in the current simulation environment (2026-11-03): **PASS 9 / WARN 2 / FAIL 0** — the two WARN items are "0 drivers online"
and "360R 1 unit + PlateLoc 2 units have no manual", both of which are the expected state before M10; during on-site commissioning
both must be cleared.

> Note: the "running on the production line" figure on the page / in the API is the number of rows in the `device` table counted by `model`
> (it includes simulation and commissioning devices) and **is not the real production-line inventory**; before connecting a real
> machine it has to be checked unit by unit against Table G in §3.7 and against the site.

---

## 2. The list of material to request (what do we ask the user / the vendor for)

> Principle: ask for the **communication interface specification / protocol manual** (including the command frames, the reply frames
> and the error codes), not the "operator manual". The most valuable part of a manual is the **error-code table** and the **parameter
> units**; without an error-code table, connecting it only means "commands can be sent".

### 2.1 Common to every device (P0 blockers)

| # | Material | Explanation | Which table it feeds |
|---|---|---|---|
| 1 | Communication interface specification (PDF/Word) | Frame format, header/trailer, checksum, timeout, resend rules | §3-B |
| 2 | Command-set list (command name + parameters + return value) | The **original command names** have to match the manual exactly; the aliases are what the control-console workflow uses | §3-B |
| 3 | Error-code table (vendor code → meaning → recommended disposition) | Including the sub-code / status-bit explanation; if there is none, explicitly record "the vendor has no code table" | §3-C |
| 4 | Parameter units / ranges / defaults | rpm, seconds, millimetres, temperature…; if the unit is wrong the simulation is all green and the site blows up | §3-A/B/D |
| 5 | State-query / busy-idle-query commands | Mandatory for long tasks (centrifuging / sealing); if there is none, state "only a fixed duration can be waited out" | §3-B/F |
| 6 | Power-up / reset / return-to-origin procedure | Where it stops after a power-cut recovery, whether a reset is needed | §3-E/F |
| 7 | Safety interlock description | Whether the door / lid can be opened while running; **no real machine until this is confirmed** | §3-E |
| 8 | Plate position / tray / consumable specification | Plate type, valid range of the tray position, film-roll specification | §3-D |
| 9 | Firmware version + the vendor's support contact | The protocol may differ per version; somebody has to be called when there is a problem on site | Filing |
| 10 | On-site connection topology (IP/port or serial-port parameters, gateway) | The basis for the driver endpoint configuration | §3-G |

### 2.2 Additionally needed for 360R (Hettich Rotanta 460R, centrifuge)

| # | Material | Why |
|---|---|---|
| 11 | The **units and ranges** of the centrifugation program parameters (speed / time / acceleration / deceleration) | The manual's example byCentriRamp=150 has no unit; a wrong guess shatters the cells |
| 12 | An explicit clause on whether the lid can be opened while running (the safety interlock) | If the manual does not say it = on-site measurement + written confirmation |
| 13 | The error-code / fault-code table | Without a code table → only "communication failed" can be recognised, and "door not closed / unbalanced / over temperature" cannot be told apart |
| 14 | The relationship between the rotor / adapter model and the maximum speed | The same model with a different rotor has a different speed limit, so the parameter boundaries cannot be guessed |

### 2.3 Additionally needed for PlateLoc (Agilent PlateLoc, sealer)

| # | Material | Why |
|---|---|---|
| 15 | The ranges of the sealing temperature / time / pressure parameters and the **parameter index table** | The S100 relies on a 0x00 index preset; PlateLoc is a different protocol and cannot copy that |
| 16 | The origin and the position range of the tray / carrier mechanism | Semi-automatic heat sealing, the loading method may differ from the S100 |
| 17 | The correspondence between the film type / consumable specification and the temperature | If the film changes and the temperature is wrong → it does not seal and **the device raises no error** (the hardest case) |
| 18 | The real duration of one sealing (measured) | A timeout = sealing twice (ruined plate); it has to be measured and the maximum ×1.5 taken |

---

## 3. Parameter-extraction template (fill this in once the manual is in hand)

> One table corresponds to one `DeviceModel` entry in DeviceModels.java; do not touch the code before it is complete.

### 3.1 Table A · Model master record (corresponds to `new DeviceModel(...)`)

| Field | What to fill in | Example (aspin-2k) |
|---|---|---|
| id | Lower-case with hyphens, globally unique | aspin-2k |
| type | Device type (aligned with sim/DeviceProfile) | centrifuge |
| vendor / model | Vendor / model | Shanghai Aiao / ASpin 2K |
| protocol | binary-serial / xml-dll / tcp-json … | xml-dll |
| transport | Serial-port parameters or the type of network address | CAN card / serial port (via Cluster.dll) |
| manual | Manual path (the evidence) | docs/设备/AC离心机…pdf |
| evidence | MANUAL / PLANNED | MANUAL |
| doors | Number of doors (0 = no door) | 2 |
| actionTimeoutMs | The longest action timeout (from the manual or measured) | 120000 |
| notes | The points that must be confirmed before connecting a real machine (one by one) | See the source |
| errorCodeBlock | The error-code block (0x40/0x41/…, see §5) | 0x41 |

### 3.2 Table B · Method mapping (one row per command)

| Manual command name cmd | Control-console alias alias | Parameters / parameter XML | Timeout ms | Is it in the manual documented | Page / section |
|---|---|---|---|---|---|
| … | … | … | … | ✅/❌ | … |

Rules:
- cmd **has to be copied from the manual word for word** (case and spaces count); the alias is what the workflow uses;
- A command the manual does not write down (such as GetStatus) is explicitly marked documented=false — on a real machine it may not
  exist at all;
- One command with several uses (such as the S100 0x04 seal / change plate type / query plate type) is registered with several
  aliases, and the index is stated in the parameters.

### 3.3 Table C · Error codes

| Vendor code | Meaning | Recommended disposition (MANUAL/RETRY/ABORT/IGNORE) | Recommended action (one sentence, for a human to read) | Page |
|---|---|---|---|---|
| … | … | … | … | … |

Rules:
- The control-console code is generated automatically from "the model's error-code block + the low byte", and is **never typed in by
  hand**;
- When the vendor has no code table, write "only adapter-layer codes" and register the communication errors with `adapterError` (see aspin-2k);
- Every code must have a disposition and an action, otherwise the site can only stare.

### 3.4 Table D · Type parameter boundaries (DeviceProfile)

| Parameter | Unit | Minimum | Maximum | Default | Basis (manual page / measured) |
|---|---|---|---|---|---|
| Speed | rpm | 200 | 14000 | — | Manual §x |
| Time | s | 1 | 3600 | — | Manual §x |
| … | | | | | |

### 3.5 Table E · Model behaviour (timing constraints, optional)

| Constraint | Manifestation | Basis |
|---|---|---|
| Example: the tray must be at the origin before sealing | Not at the origin → 0x8000001A | Manual §x / measured |
| Example: the module instances must be created before centrifuging | Otherwise the command cannot be sent | Manual §x |

### 3.6 Table F · On-site measurement items (not in the manual, must be tested)

> A direct extension of `docs/设备/现场确认清单.md`: for each item write "how to test it / the pass criterion / what happens if the guess is wrong / the
> value used now". At a minimum, add these for 360R / PlateLoc: the longest action duration, the power-up reset procedure, the
> power-cut tolerance, the range of the tray position, the raw bytes of the error frame (does it contain a sub-code), and the measured
> safety interlock.

### 3.7 Table G · Binding and acceptance

| Item | Content |
|---|---|
| Production-line devices | The list of instances in the device table with model=360R / model=PlateLoc (id/code/IP) |
| How to bind | Once the manual is in hand and the model is completed, bind device.model → DeviceModels.id with the SQL from §8.3 of `docs/设备/型号库与错误码映射.md` |
| Driver endpoint | Update driver.endpoint/protocol/manifest and restart the sidecar |
| Acceptance command | Visible in --list-models, the simulator behaviour matches, `make seed` regenerates the error codes, a single-machine smoke test, mixed-line acceptance (§4) |

---

## 4. The onboarding procedure (10 steps of DoD once the manual is in hand)

| # | Step | Output | Acceptance |
|---|---|---|---|
| 1 | Check the completeness of the material (tick off the §2 list item by item) | A gap table for the material | No gaps in the P0 items |
| 2 | Fill in tables A–G (§3) | One parameter table per device | The review passes (the parameters / units / error codes have a page number they come from) |
| 3 | Change DeviceModels.java and add one model | Code + a self-test | Visible in --list-models; the pure-logic tests pass |
| 4 | Regenerate the SQL seed (error codes / policies) | A 95_seed_data.sql diff | No conflict in the block numbering, the number of policies matches |
| 5 | Write / align the model behaviour (if needed) | sim/XxxBehavior | The timing-constraint cases pass |
| 6 | Align the simulator behaviour (the same parameter validation / the same error codes) | Simulator test cases | The simulator can reproduce the error paths from the manual |
| 7 | Bind the device + the driver endpoint | A database change + configuration | The heartbeat is online, the device is visible |
| 8 | A single-machine smoke test (on the real machine, under safety preconditions) | A test record (screenshots / logs) | The command round-trips, the error codes are recognised, a timeout is not judged wrongly |
| 9 | Mixed-line acceptance (the M10 gate) | A recording | ≥3 devices and a ≥5-node process run through + a recording of failure→recovery→continue |
| 10 | Validate the long-task policy | A report | Distinguish "the device is busy" from "the device is dead" (M10 scope item 2); a long task does not time out wrongly |

### 4.1 The long-task timeout policy (M10 scope item 2, decided together with the manual)

| Device | Long task | What the manual / the measurement has to answer |
|---|---|---|
| 360R | Centrifugation (minute scale) | Is there a "query the running state" command? What timeout does the manual recommend? What is the maximum centrifugation duration? |
| PlateLoc | Sealing (ten-second scale) | Is there a completion signal? What is the maximum sealing duration? |

The decision rule (first draft, awaiting review):
1. **There is a busy/idle or state-query command** → after a timeout, query first: still running = the device is busy (keep waiting,
   do not resend); not found = the device is dead (abort + alarm);
2. **There is no query command** → the action timeout can only be set to "the measured maximum × 1.5", and **it must not be resent
   automatically after a timeout** (to prevent a duplicate action);
3. Any "action-type command is resent automatically after a timeout" requires an idempotency guarantee, otherwise it is forbidden.

### 4.2 Error-code feedback (M10 scope item 4)

When an unregistered code is met on site:
1. Record **the raw code + the complete frame + the device / firmware version + the context** (which method, which step);
2. Add `.error(...)` for the matching model in DeviceModels (when the manual has it) or `.adapterError(...)` (communication / adapter layer);
3. Run `make seed` to regenerate the error_code + the policies;
4. Regression: injecting that code in the simulator reaches the expected disposition (manual / retry / abort);
5. Record it in the appendix of this document (code → disposition → recurrence).

---

## 5. Gap analysis of the model library (does code or a table have to change?)

| # | Gap | Today | Impact | Recommendation |
|---|---|---|---|---|
| G1 | The model library is hard-coded in Java (DeviceModels.java) | Adding a model = changing code + releasing a version | Acceptable for 2 devices | Keep it as it is before M10; if the number of models grows, consider configuration (not in this round) |
| G2 | PLANNED only has the "missing" text, no "material status" field | It relies on people remembering | The material follow-up is easily lost | Track it in document §2 + this table; if it is put into the code, add manualStatus/manualPath (a small change, to be scheduled) |
| G3 | The error-code block has to be picked by hand | 0x40/0x41 are taken | A clash mixes up the codes | 360R=0x42, PlateLoc=0x43 (to be fixed by the review) |
| G4 | A long task only has the single value actionTimeoutMs | aspin=120s / s100=0 | "Busy" and "dead" cannot be told apart | Add the optional busyQueryCmd / maxActionSec to the model (inside M10, design first) |
| G5 | The mapping of the 76 device rows in the database to the model ids is not in place | The production database has 360R/PlateLoc records but they are not bound | They can only be bound once the manual is there | Execute the binding SQL from §8.3 of the mapping document (once the manual is there) |
| G6 | There is no regression sample library for the real-machine error codes | Only the simulated error paths exist | A change to a field code has no regression | M10 scope item 4 establishes "field code → simulator injection case" |

---

## 6. Alignment with the M10 acceptance (how this checklist supports acceptance)

| M10 acceptance item | What this checklist contributes |
|---|---|
| At least one complete process on site (≥3 devices, ≥5 nodes) runs through | Step 9 in §4; only once the material / parameters / binding are complete is the site scheduled |
| A recording of failure→recovery→continue | Step 9 in §4 + the error dispositions in table C + the code feedback in §4.2 |
| The long-task timeout distinguishes "busy/dead" | §4.1 |
| Real-machine anomaly samples flow back | §4.2 + G6 |

---

## 7. Risks / what is not done / open questions

**Risks**
- Inventing a protocol without a manual = everything is worthless on site (this project has already learned the lesson: all 6 entries
  in PLANNED deliberately have no model bound);
- The PlateLoc is a semi-automatic heat sealer and **the S100's binary frame protocol cannot be copied onto it**; the 360R cannot take
  the error-code meaning of aspin-2k;
- **No real machine before the safety interlock** (opening the door / lid while running) has been confirmed in writing;
- A long-task timeout that is too small → a duplicate action (sealing twice ruins the plate); too large → waiting for nothing and a
  slow takt time.

**What is not done (this round)**
- No driver code, no change to the DeviceModels logic (waiting for the manual);
- The 360R / PlateLoc are not forcibly bound to an existing model;
- The model library is not made configurable (G1, to be evaluated again after M10).

**Open questions (for the user / the vendor)**
1. Can the **communication interface specifications** (electronic version) for the 360R / PlateLoc be provided? Which version / date?
2. The current **firmware versions** of the two devices; does the protocol differ per version?
3. The **connection method** of the 360R (1 unit) / PlateLoc (2 units) on the production line (serial port / Ethernet / middleware)
   and their addresses?
4. Does the vendor provide an **error-code table** and SDK / sample code?
5. The ranges of the PlateLoc sealing parameters (temperature / time / pressure) and the **parameter index table**;
6. The unit / range of the 360R's centrifugation acceleration, the rotor speed limits, and the clause on the lid interlock while
   running;
7. Is **destructive testing** on site allowed (pulling the power / cutting the network / error injection), and who is the safety
   owner?
8. For long tasks (centrifuging for 30 minutes), what **alert threshold and handling procedure** does the site expect?

---

## 8. Appendix · Material follow-up table for 360R / PlateLoc (to be filled in)

| Item | 360R | PlateLoc | Owner | Status |
|---|---|---|---|---|
| Communication interface specification | ⬜ | ⬜ | | |
| Command set | ⬜ | ⬜ | | |
| Error-code table | ⬜ | ⬜ | | |
| Parameter units / ranges | ⬜ | ⬜ | | |
| State-query commands | ⬜ | ⬜ | | |
| Safety interlock | ⬜ | ⬜ | | |
| On-site connection topology | ⬜ | ⬜ | | |
| Tables A–G completed | ⬜ | ⬜ | | |
| Model library written to the database | ⬜ | ⬜ | | |
| Simulator behaviour aligned | ⬜ | ⬜ | | |
| Real-machine smoke test | ⬜ | ⬜ | | |


