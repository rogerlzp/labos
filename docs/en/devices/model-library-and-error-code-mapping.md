# Model Library and Error-Code Mapping

> **English edition.** The Chinese original is [`docs/设备/型号库与错误码映射.md`](../../设备/型号库与错误码映射.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **Answers three questions**:
> 1. When a new device is brought on (say, a third centrifuge), what has to be changed?
> 2. The device reports `0x8000000C` — how does the control console know it
>    "should call someone to change the paper"?
> 3. After the model library is changed, how do the policies in the database stay in
>    sync?

---

## 1. Three layers: type / model / error code

```
Device type (DeviceProfile)      centrifuges, sealers… "what methods and parameter ranges this kind of device roughly has"
      ↓
Concrete model (DeviceModel)     ASpin 2K, S100… "the real command names / parameters / error codes / timeouts of this machine"
      ↓
Error code (ErrorDef)            0x8000000C out of paper → EC-08-400C → an operator changes the consumable
```

| Layer | Code | What it answers | Has to change when a device is added |
|---|---|---|---|
| Type | `sim/DeviceProfile.java` | Parameter boundaries (rpm 200–14000, volume range 0.5–5000 µL) | **No** (unless it is a new category) |
| Model | `device/DeviceModels.java` | Real command names, parameters, error codes, action timeout | **Yes — only here** |
| Model behaviour | `sim/S100Behavior` / `AspinBehavior` | Timing constraints (tray origin, two doors, create modules first) | Only for models that "have timing constraints" |
| Recovery policy | `pg_rebuild/schema/95_seed_data.sql` | How the control console reacts after something goes wrong | **Generated from the model library, never hand-written** |

**Why "type" and "model" are separated**: there are dozens of centrifuge models, and
every vendor names its commands differently. When only the type is modelled, bringing on
a second machine means changing code; once models are modelled, **adding a device =
adding one definition**.

---

## 2. Adding a device: five steps

Taking "one more centrifuge" as the example, all the changes are inside the single file
`DeviceModels.java`:

### Step 1: check whether a manual exists

| Situation | Conclusion | What to do |
|---|---|---|
| There is a communication-interface specification (not an operator manual) | It can be connected to a real machine | Run steps 2–5, `Evidence.MANUAL` |
| Only the public specification is available (speed / capacity / temperature control) | Only the workflow skeleton can run for now | Put it on the `PLANNED` list and wait for the manual |
| Nothing at all | Do nothing | Do not invent a protocol — anything invented is worthless on site |

> The most valuable part of a manual is not "it powers on and powers off" but the
> **error-code table** and the **parameter units**. Without an error-code table,
> connecting it only means "commands can be sent" — when a fault occurs you still shout
> for a human.

### Step 2: copy the commands from the manual

```java
TABLE.put("vendor-model-id", new DeviceModel(
        "vendor-model-id", "centrifuge", "vendor", "model",
        "xml-dll", "CAN card / serial port", "docs/设备/手册.pdf",
        Evidence.MANUAL, /*door count*/2, /*action timeout ms*/120_000, "points that must be confirmed before connecting to a real machine"))
    .method("Init", "Home", "<parameter XML>", 3_000)          // real name → our alias
    .method("ControlCover", "OpenDoorA", "...", 1_500)
    .method("GetStatus", "GetStatus", null, 300, false);  // not in the manual → documented=false
```

Rules:
- **`cmd` must be the name printed in the manual**; the alias is the one our workflows
  use (the simulator accepts both).
- A command the manual does not have (such as `GetStatus`) must be explicitly marked `documented=false` — on
  a real machine it may not exist at all.
- One command with several uses (the S100 `0x04` seal / change plate type / query plate
  type) is registered as three aliases, and `args` states the index explicitly.

### Step 3: copy the error codes

```java
// Only the manual code is written here; the control-console code is computed automatically from "model block + low byte" (never typed by hand — a hand-typed value is always wrong).
.error("0x8000000C", "无纸", Disposition.MANUAL,
       "更换耗材；新卷装上仍报错时弯折从动轮固定金属片")
```

> The two Chinese string literals are the **literal values the product stores** — the
> `error_code.title` ("out of paper") and the remediation text shown in the alert. They are contract
> data, kept verbatim on purpose.

`Disposition` has only four values, **classified by "what to do about it", not by "severity"**:

| Disposition | Meaning | Automatic retry | Severity (derived automatically) |
|---|---|---|---|
| `RETRY` | Transient fault (semaphore, communication jitter) | ✅ 3 times | warn |
| `FIX_PARAM` | Wrong parameter / wrong timing, retrying is useless | ❌ a person fixes it, then it runs again | error |
| `MANUAL` | A person has to be on site (jammed, missing consumable, blade fallen out) | ❌ | error |
| `ABORT` | Protocol / version mismatch | ❌ terminate immediately | fatal |

> The severity is not typed in by hand — `DeviceErrorMapper.severityOf()` **derives** it from the table above. The
> reason: something that can be retried automatically should not be fatal (waking
> someone up at midnight); something that must be handled by a person should not be a
> warn (it would be ignored).

### Step 4 (optional): write the model behaviour

Only models that "have timing constraints" need this (see section 3):

```java
private static DeviceBehavior newBehavior(DeviceModel model) {
    return switch (model.id) {
        case "s100" -> new S100Behavior();
        case "aspin-2k" -> new AspinBehavior();
        case "new-model" -> new XXXBehavior();
        default -> null;          // no dedicated behaviour → use the generic state machine
    };
}
```

### Step 5: regenerate the SQL

```bash
cd labos-kernel
javac -encoding UTF-8 -d out $(find src -name '*.java')
java -cp out com.labos.kernel.device.DeviceErrorSql --emit-sql
# Replace the whole output into the "device-specific error codes" section of 95_seed_data.sql
./scripts/test-all.sh --pure-only    # DeviceModelSelfTest checks whether the SQL is in sync
```

---

## 3. Why the simulator needs "model behaviour"

The generic profile can only validate "method name + parameter range". A real device
also has **timing constraints**, and these are only known after reading the manual:

| Model | Timing constraint | What happens if it is not implemented |
|---|---|---|
| S100 | The tray must be at the origin before sealing (`0x8000001A`) | The workflow misses `MoveTray(0)`, the simulation passes anyway, and on site it fails every time |
| S100 | Faults are **latched** (after changing the paper you have to press "clear fault") | The control console thinks "a retry will do", but on site somebody has to press a button |
| ASpin 2K | `PcCreateModules` must come first to create the module instances | Not a single command can be sent |
| ASpin 2K | `Init` must come first to return to the origin | The device state is unknown and the action has no effect |
| ASpin 2K | Two doors (A/B) — centrifuging is impossible while a door is open | A workflow written for a "single door" does not match the machine on site |

**Once these constraints are implemented, the biggest payoff is not in the simulator but
in the workflow templates**: if a workflow is written wrongly, the simulation stage
already reports `0x8000001A` — there is no need to wait for the site.

---

## 4. Device code → control-console code → action (the chain)

```
Device response {"error_code":"0x8000000C"}
   │
   ├─ TcpDriverAdapter / TcpPersistentDriver
   │     ↓ look up the model library by "which model is this device"
   ├─ DeviceErrorMapper.map(deviceInstanceId, "0x8000000C")
   │     → EC-08-400C / MANUAL / "out of paper" / "change the consumable…"
   │     (not registered → EC-08-9001 + MANUAL; the raw code is kept in message and device_raw_response)
   │
   ├─ DriverResult(errorCode=EC-08-400C, raw=raw frame)
   │     ↓ DriverStepExecutor throws StepFailure(scope=NODE)
   ├─ RecoveryDecider (looks up recovery_policy by error_code)
   │     → MANUAL (hand over to an operator; the alert carries "change the consumable")
   ↓
Alert + run_command.error_code + error_occurrence
```

### How a model is bound to a device

Three sources, in order of decreasing priority:

| Source | How it is written | Use |
|---|---|---|
| `device.attr` | `{"device_model": "s100"}` | **Production route** (configured in the DB, read by `WorkflowService`) |
| Environment variable | `LABOS_DEVICE_MODELS="214=s100,215=aspin-2k"` | On-site commissioning (before it is written to the DB) |
| Environment-variable fallback | `LABOS_DEFAULT_DEVICE_MODEL=s100` | Single-machine demo (**do not use in production**: it treats every device as the same model) |

**What happens when no model is bound**: the device code is passed through unchanged
(the 1.0 behaviour), `recovery_policy` finds no policy ⇒ everything is handed over to an operator.
This is not a crash, it is a silent degradation, which is why the startup log prints
`已绑定型号 {...}` / `未绑定型号` ("model bound {...}" / "model not bound").

### The boundaries of the three kinds of code

| Code range | Who produces it | Example |
|---|---|---|
| `0x8000xxxx` | **The device** (the raw code from the vendor manual) | `0x8000000C` out of paper |
| `EC-08-4xxx` | **We do** (`4x` = model block, `xx` = low byte of the manual code) | `0x8000000C` → `EC-08-400C` |
| `EC-05-20xx` | **The adapter layer** (not returned by the device — a timing / deployment problem we detected) | `EC-05-2002` module instance not created |
| `EC-08-9001` | **The fallback** (the device code is not registered) | any unregistered code |

> Numbering rule: **`EC-08-<model block><manual byte>`** (`EC-08-<型号块><手册字节>`) — two digits for the block plus two for the byte.
>
> | Block | Model | Example |
> |---|---|---|
> | `40` | s100 (S100 sealer) | `0x8000000C` → `EC-08-400C` |
> | `41` | aspin-2k (reserved, the manual has no code table) | — |
> | `42`… | new models take the next ones, **never reuse** | — |
>
> **Why the block is mandatory**: two different models may both report `0x8000000C`, and the
> meanings may be completely different (on the S100 it is "out of paper", on another
> machine it may be "door not closed"). Numbering by byte alone would make two models
> compete for the same control-console code, and the policies would end up crossed —
> which is far more serious than "the numbering does not look pretty".
>
> Operations can decompose `EC-08-401A` into "block 40 = S100, byte 1A = tray not at the origin"
> without looking anything up.

---

## 5. How these codes are used on site

### 5.1 What to look at first when something goes wrong

```sql
-- The most recent device errors: raw code, control-console code and raw response all together
SELECT occurred_at, error_code_id, message, context->>'device_raw_response'
FROM error_occurrence WHERE resolved_at IS NULL ORDER BY occurred_at DESC LIMIT 20;
```

```sql
-- How often this code has occurred historically, and how it was finally handled
SELECT ec.code, ec.title, count(*), o.outcome
FROM error_occurrence o JOIN error_code ec ON ec.id = o.error_code_id
WHERE o.occurred_at > now() - interval '30 days'
GROUP BY 1,2,4 ORDER BY 3 DESC;
```

### 5.2 Changing the disposition (without a restart)

The policies live in the database, and a change takes effect at the next decision:

```sql
-- Example: on site, 2 retries for a CAN communication error was considered too many — change it to 1
UPDATE recovery_policy p SET max_attempts = 1
FROM error_code ec
WHERE p.error_code_id = ec.id AND ec.code = 'EC-08-4004';
```

### 5.3 Adding a code (a code change, not a database change)

The mapping from device code to control-console code is **deliberately kept in the
code** (`DeviceModels`), not put into the database:

- It describes "what the device said" — it is a **fact from the manual** and has to
  travel with the manual version;
- Put into the database it becomes "the site can change it at will", and nobody knows
  when it was changed wrongly;
- Whereas "what we should do about it" (retry/manual/abort) is **policy**, and that is
  what belongs in the database, where the site can tune it.

---

## 6. Current inventory

### Already adapted (`Evidence.MANUAL`, can be connected to a real machine)

| Model id | Device | Basis | Commands | Error codes |
|---|---|---|---|---|
| `aspin-2k` | Shanghai Aiao ASpin 2K centrifuge | V2.0 manual | 8 | not provided in the manual |
| `s100` | S100 sealer | Communication interface specification | 12 | 14 |

### Still to be adapted (`PLANNED`, cannot be connected to a real machine)

See `java -cp out com.labos.kernel.sim.DeviceSimulator --list-models`; in the code it is `DeviceModels.PLANNED`.

| Model | What is missing |
|---|---|
| **Hettich Rotanta 460R** (`model=360R` in the database) | Communication interface specification; **1 unit is running on the production line** |
| **Agilent PlateLoc** (`PlateLoc` in the database) | Communication interface specification; **2 units are running on the production line**; it is semi-automatic heat sealing and does not come from the same source as the S100 |
| Eppendorf 5810 / 5810 R | Communication interface specification |
| Thermo Multifuge X1/X3 | Communication interface specification |
| Beckman Coulter Allegra X-15R | Communication interface specification |
| Domestic fully automatic centrifuge (generic placeholder) | The concrete vendor model |

> The cost of adding one machine ≈ half a day (copying the manual + the self-test + the
> documentation). **The precondition is the manual**; without a manual it can only go on
> the PLANNED list.

---

## 7. Verification commands

```bash
cd labos-kernel
javac -encoding UTF-8 -d out $(find src -name '*.java')

# Model library + mapping + decision (does not touch the database)
java -cp out com.labos.kernel.device.DeviceModelSelfTest
# Simulator behaviour (starts a real socket, follows the timing in the manual)
java -cp out com.labos.kernel.sim.SimulatorBehaviorSelfTest
# Full chain: device code → adapter → control-console code → recovery action
java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.driver.DeviceErrorChainSelfTest

# Look at the mapping table / generate the SQL
java -cp out com.labos.kernel.device.DeviceErrorSql --list
java -cp out com.labos.kernel.device.DeviceErrorSql --emit-sql

# Start an S100 simulator (with GUI)
java -cp out com.labos.kernel.sim.SimulatorApp --model s100 --device 501 --port 19501
# Start an ASpin 2K simulator (no GUI, for automation)
java -cp out com.labos.kernel.sim.DeviceSimulator --model aspin-2k --device 601 --port 19502

# Control console → sidecar → device, full chain (no real machine needed)
./scripts/test-sidecar.sh
```

Producing a fault on the simulator (two ways):

```bash
# Command line: fail once every 3 action commands, answer "out of paper"
--model s100 --fail 3 --fail-code 0x8000000C
# Fail only on sealing, the first 5 succeed (simulates "the paper ran out halfway")
--model s100 --fail 1 --fail-method Seal --fail-code 0x8000000C --fail-from 6
```

The GUI also has a "produce fault" dropdown (triggering a code from the manual) and a
"clear fault" button, as well as the S100's "move tray to 10000" button — used to verify
whether the control-console workflow really returns to the origin.

---

## 8. State of the real database (checked 2026-11-02)

**Database**: `192.0.2.10:5432/labos_demo` (user `labos`) — this is exactly what the local ApiServer connects to.

### 8.1 What has already been loaded

| Item | Count | Explanation |
|---|---|---|
| `error_code` total | 60 → **77** | 17 new rows = 14 for the S100 + 3 for the ASpin adapter layer |
| `recovery_policy` total | 62 → **79** | All `scope='node'` (a device failure is thrown as NODE inside DriverStepExecutor) |
| Numbering | `EC-08-4001…401A` / `EC-05-2001…2003` | **No conflict** with the existing `EC-08-3010` etc. already live |

How it is executed (idempotent, can be re-run):

```bash
cd pg_rebuild
psql -h <DB> -U labos -d labos_demo -v ON_ERROR_STOP=1 --single-transaction -f schema/95_seed_data.sql
```

Verification (the first 4 items run without a database; the last 5 only run when `LABOS_DB_*` is
set):

```bash
LABOS_DB_HOST=192.0.2.10 LABOS_DB_NAME=labos_demo LABOS_DB_USER=labos LABOS_DB_PASS=*** \
  java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.driver.DeviceErrorChainSelfTest
# → the database shows 无纸 → hand over to an operator = MANUAL ...
```

### 8.2 ⚠ Not a single device in the database has a model bound

```sql
select count(*) filter (where attr ? 'device_model'), count(*) from device;   -- 0 | 74
```

**This is correct — do not rush to fill it in**: of the existing 74 units **there is no
S100 and no ASpin 2K**. What is running on the production line is `360R` (Hettich Rotanta
460R) and `PlateLoc` (Agilent), and **the two manuals we hold are not theirs**. Binding them
wrongly means "explaining the S100's out-of-paper code with the meaning of another
machine", which is worse than not binding at all (not binding only degrades to
"unregistered code → hand over to an operator" and never gives wrong advice).

Binding SQL (execute it only after the S100/ASpin really enters the production line, or
after the missing 360R/PlateLoc manuals have been obtained):

```sql
-- First confirm which instance it is (the code has to match the production device number)
select di.id, di.code, d.model from device_instance di
  join device d on d.id = di.device_id
 where di.deleted_at is null and (d.model ilike '%s100%' or d.model ilike '%aspin%');

-- Then bind the model (attr is jsonb; using || merges it without overwriting the other fields)
update device set attr = attr || '{"device_model":"s100"}'::jsonb where code = '<device code>';
```

After binding, look at the startup log: `[驱动] DRV-xxx → tcp://…（N 台设备，已绑定型号 {214=s100}）` ("N devices, model bound {214=s100}").

### 8.3 What to do with the 60 codes that are already live

They were **created automatically from the raw error frames** by the old system (the
`title` stores the whole JSON segment, e.g. `EC-08-3010`). This round **did not touch them**:

- The new codes use the `4xxx` block and do not mix with the old ones;
- The `title` of the old codes is dirty data, but `error_occurrence` hangs historical records off them
  (105 rows), and cleaning it up would have to handle the foreign keys as well — that is
  data-governance work and is out of scope for this round;
- It is recommended to use the mapping table of `DeviceErrorSql --list` afterwards to converge the old
  codes that "can be matched against the manual" onto the new ones, step by step.

---

## 9. Adding a device to the "device pool" (with an image)

The model library (the `DeviceModels` in the code) answers "how do we talk to this machine"; the
**device pool** (the `device` table in the database) answers "which machines do we support"
— the frontend 「Island → Device Pool → Deploy」 creates instances from this pool. Both
sides are needed before a device counts as "added".

### 9.1 One command

```bash
cd labos-kernel
export LABOS_DB_HOST=192.0.2.10 LABOS_DB_NAME=labos_demo LABOS_DB_USER=labos LABOS_DB_PASS=***

# Already in the model library (recommended): name / vendor / type / capability / model binding all automatic
java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.device.DeviceRegistrar \
     --model s100 --image docs/设备/images/s100.jpg

# See the current state
java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.device.DeviceRegistrar --list
```

What it does (all of it idempotent — a re-run only updates, it never adds):

| Step | Explanation |
|---|---|
| 1 | Takes `model / vendor / device_type / capability` from the model library (**never copied by hand**) |
| 2 | Copies the image into `webapp/public/devices/<code>.<ext>`, `icon = /devices/<code>.<ext>` |
| 3 | Writes the `device` row, `attr.device_model` = the model-library id (**the error-code translation depends on it**) |
| 4 | Upserts by `(tenant_id, code)`, so a repeated execution never produces a second row |

**It works without an image too**: a placeholder SVG is generated (with the model name
and the words "schematic" on it), so the pool never shows an empty cell, and we never
pretend to have a photo of the real machine. When there is a photo, overwrite it
directly with `--image`.

### 9.2 How external / future users add one

Give a JSON description file, so that nobody has to remember the parameters:

```json
{
  "code": "ROTANTA_460R",
  "name": "Hettich Rotanta 460R centrifuge",
  "model": "Rotanta 460R",
  "vendor": "Hettich",
  "deviceType": "Centrifugation",
  "capability": "CENTRIFUGE",
  "deviceModel": "hettich-rotanta-460r",
  "image": "docs/设备/images/rotanta.jpg",
  "color": "#FFD9A0",
  "remark": "Centrifuge No. 1; create a separate instance for No. 2"
}
```

```bash
java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.device.DeviceRegistrar \
     --from my-device.json
```

- `deviceModel` **must exist in the model library** (otherwise it is rejected), because the
  error-code translation uses it; leave it empty until the manual arrives — the device
  code then degrades to "unregistered code → hand over to an operator".
- `deviceType` uses the existing vocabulary: `Centrifugation / Sealing / Incubators / Storage / Liquid Handling / Robots / …` (`--list` shows what other people are using).
- `capability` must exist in the `capability` table (`SEAL` / `CENTRIFUGE` / `INCUBATE` …); if it is written wrongly,
  the existing values are printed and it exits.

### 9.3 What is in the pool right now

| code | Name | Type | Capability | Model-library binding |
|---|---|---|---|---|
| `S100` | S100 sealer | Sealing | SEAL | `s100` |
| `ASPIN_2K` | Shanghai Aiao Biotechnology ASpin 2K | Centrifugation | CENTRIFUGE | `aspin-2k` |
| `EQ150` | Rotanta 460R (legacy data) | Centrifugation | CENTRIFUGE | — manual missing |

> `EQ150` is the one that is really running on the production line (Hettich), and it has
> **no model bound** — it stays unbound until the manual arrives; binding it wrongly
> would explain the S100 codes with Hettich's meaning.

---

## 10. End-to-end acceptance: device pool → island → driver → real command

The previous sections proved "is the mapping table right" and "does the simulator look
like the real thing". This section proves something else: **can the configuration in the
database actually send the command out** — in between there are five tables, `device / device_instance / resource_node / resource_capability / driver`, and
if any one of them is wired wrongly the symptom is always "the device shows up in the UI
but does not respond".

```bash
export LABOS_DB_HOST=192.0.2.10 LABOS_DB_NAME=labos_demo LABOS_DB_USER=labos LABOS_DB_PASS=***
./scripts/verify-device-chain.sh
```

What the script does (all of it idempotent, it can be run over and over):

| Step | Action | Typical symptom on failure |
|---|---|---|
| 1 | Register the model into the pool (`DeviceRegistrar`) | The device is not in the pool |
| 2 | Deploy it to an island (equivalent to the frontend drag-and-drop) | It does not show up on the island |
| 3 | Fill in `resource_capability` and bind the `driver` | `EC-06-0001 驱动未加载` ("driver not loaded") |
| 4 | Start simulator ×2 + sidecar ×2 | The port cannot be reached |
| 5 | Send a real command through `/api/shadow/probe` | See the expected values in the table below |
| 6 | Look up `recovery_policy` again | The error code was translated but there is no policy |

The assertions (these are all **real commands**, not unit-test stubs):

| Device | Command | Expected |
|---|---|---|
| S100 | MoveTray(0) → Seal → MoveTray(10000) → Seal | The first two succeed, the last one returns `EC-08-401A` (tray not at the origin) |
| S100 | Return to the origin → Seal | Succeeds (proves the fault is not permanently latched) |
| ASpin 2K | CreateModules → Home → RunProgram → CurrSpeed | All succeed |

After the run: **26 passed / 0 failed**.

### 10.1 A real bug this step caught

On the first run, all 10 probes came back **`EC-06-0001`** (driver not loaded). The root cause
was not in the script but in the product code:

`QueryService.deployModel()` created the `resource_node` + `device_instance`, **but did not write `resource_capability`**. And the driver registry
(`WorkflowService.registerAllDriversFromDb`) filters like this:

```java
if (allCaps.isEmpty()) {
    System.err.println("[驱动] " + m[0] + " 跳过：名下设备都没有声明能力（查 resource_capability）");
    continue;   // ← the whole driver is skipped, and every device under it fails together
}
```

That is: **a device dragged in from the frontend has empty capability rows, and the
whole driver never registers.** A unit test cannot find this bug (the unit test passes a
driver descriptor directly); it only shows up on the "deploy → send a command" path.

Fixed: `deployModel()` now writes `resource_capability` when it deploys (`INSERT ... SELECT capability_id FROM device WHERE id = modelId`). The historical data was fixed
too: of the 247 instances in the whole database, 5 were missing their capability row,
and they have been filled in.

### 10.2 Why the script waits 60 seconds

The driver registry has a 60 s cache (`REGISTRY_TTL_MS`), so a `driver` that was just bound does not
take effect immediately. The `sleep 62` in the script waits for it to expire — **it is not a
slow script, it is by design**. If that feels too slow, restarting the ApiServer takes
effect immediately as well.

### 10.3 The bash 3.2 trap the script stepped on (macOS)

```bash
v=abc; echo "值 $v），复用"      # bash 3.2 reports: v?: unbound variable
```

The bash 3.2 that ships with macOS **swallows the UTF-8 Chinese characters that directly
follow `$v` into the variable name**, so the variable name turns into something like
`v），复用`. So wherever a Chinese character directly follows `$var`, it must be written as
`${var}`. `verify-device-chain.sh` and `verify-m4.sh` have already been fixed in bulk (7 places in total).


