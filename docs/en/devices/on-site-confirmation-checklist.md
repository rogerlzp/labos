# On-Site Confirmation Checklist (what the manual does not write down, but what has to be nailed down before connecting a real machine)

> **English edition.** The Chinese original is [`docs/设备/现场确认清单.md`](../../设备/现场确认清单.md); this is a line-for-line translation. Numbers, commit
> hashes and file paths are identical to the Chinese source — if they ever disagree, the Chinese original wins.

> **Why this checklist exists**: the manuals of both devices only describe "which commands can be sent", but they **do not write
> down "how long is a timeout", "where does it stop after a power cut" or "what unit are those two values in"**. These gaps can only
> be measured on site, they cannot be invented — a wrong guess shows up as "everything is green in the simulation and the line
> alarms every day".
>
> Every item states: **how to test / the pass criterion / what happens if the guess is wrong**. After the test, write the conclusion
> into `DeviceModels` (the parameters) or into this section (the conclusion), and change ⬜ to ✅.

---

## 1. S100 sealer

### 1.1 How long does one sealing really take ⬜

- **How to test**: trigger one sealing by hand and time it from "sending the command" to "the reply `ac 04 …`".
- **Pass criterion**: measure 10 times and take **the maximum × 1.5**.
- **Value used now**: `delayMs = 12000` (12 seconds, a guess) for `Seal` / `SealWithType` in `DeviceModels`.
- **If the guess is wrong**:
  - Too small → the control console times out → it judges a failure → it triggers a retry → **the plate is sealed twice** (the film
    is pressed twice, the plate may be ruined);
  - Too large → everybody waits for nothing, and the line takt time slows down.

### 1.2 The parameter index table (what the "parameter index" of `0x00`/`0x01` corresponds to) ⬜

- **How to test**: `0x03` (print all parameters) prints a list of "index → value" — copy it down.
- **Pass criterion**: the indices of the common parameters — temperature, time, pressure — can be matched up.
- **Value used now**: the simulator only stores and reads back; never guess an index such as `SetParam(17, 180)`.
- **If the guess is wrong**: the wrong parameter → the film does not seal and **the device raises no error**.

### 1.3 Whether `0x05` (reset) has to be sent after power-up ⬜

- **How to test**: power down → power up → seal without sending a reset and see what comes back.
- **Pass criterion**: if it seals, no reset is needed first; if it replies `0x8000001A`/`0x80000009`, a reset is mandatory.
- **Conservative approach (now)**: after power-up the workflow always does `Reset` + `MoveTray(0)` first.

### 1.4 Where the tray stops when the power goes out during a sealing, and after the recovery ⬜

- **How to test**: pull the power during a sealing, then after powering up again read `0x04 0x5A` (query the plate type) or simply call
  `MoveTray(0)` and listen for any abnormal noise.
- **Pass criterion**: it can safely return to the origin.
- **Conservative approach (now)**: after the recovery, `Reset` + `MoveTray(0)` first, then seal.

### 1.5 The valid range of the tray position ⬜

- **The manual only gives the single example 10000**.
- **How to test**: increase it step by step from 0 until the device reports an error, and record the lower and upper limits.
- **If the guess is wrong**: out of range → `0x80000005` motor jammed, or `0x80000009` invalid value.

### 1.6 What exactly bytes 6–8 of the error frame are ⬜

- The manual says "only byte 5 is the identifying byte, but bytes 6, 7 and 8 also have to be recorded, they are invalid data".
- **How to test**: capture the raw frames of 3 errors of the same kind and compare whether bytes 6–8 are fixed.
- **Why it matters**: if they turn out to be a sub-code, the classification can be finer in the future (for example "out of paper"
  split into "no paper" / "paper jam").

---

## 2. ASpin 2K centrifuge

### 2.1 The unit and range of `byCentriRamp` (the acceleration) ⬜

- The manual's example is `byCentriRamp="150"` with no unit (rpm/s? a gear position?).
- **How to test**: run once each with 100 / 150 / 200 and either listen to the acceleration time or sample with `CurrSpeed`.
- **If the guess is wrong**: too much acceleration shatters the cells — **this one damages the sample, the vendor has to be asked.**

### 2.2 Whether the lid can be opened during centrifugation (safety interlock) ⬜

- **The manual does not say**. The simulator treats it as "refused" (replies `EC-06-0006`).
- **How to test**: send `ControlCover(1)` while it runs at a low speed (500rpm).
- **Pass criterion**:
  - Refused → the control-console side needs no extra protection;
  - **Allowed** → the control console must forbid it at the workflow level (this is a safety accident, not a minor problem).

### 2.3 The unit of `nRetValue` for `CurrSpeed` / `CurrTime` ⬜

- Is the speed in rpm or 0.1 rpm? Is the remaining time in seconds or minutes?
- **How to test**: set `speed=1500, time=60`, start it and query both immediately, then compare with the set values.
- **If the guess is wrong**: the judgement of "has the centrifugation finished" will be wrong (60 seconds taken for 60 minutes).

### 2.4 What state the device is in after a power-cut recovery ⬜

- Is the door open or closed? Does the program continue or abort?
- **Conservative approach (now)**: after the recovery, `Init` first and only then act.

### 2.5 The module node numbers when there is more than one centrifuge ⬜

- The manual's `CanCfg.ini` has `PlateCtri=0x0A,0xE0`. **The node numbers of two units must not be the same**.
- **When connecting the second unit**: change the node number (e.g. `0x0B`) and specify it with `--node` on the sidecar; one sidecar
  process only serves one machine, so the second unit needs its own process + port.

### 2.6 What the XML in `AOutResult` looks like when `SendData` returns 0 ⬜

- The manual only says "the error is in the XML" and gives no sample.
- **How to test**: deliberately send a wrong command (e.g. a wrong `strCmdName`) and store the raw text of `AOutResult`.
- **Why it matters**: the sidecar can only stuff the whole XML into the error description, not a structured error code.

---

## 3. The control-console side (what we have to decide ourselves)

### 3.1 How the timeout of action-type commands is configured ✅ decided

- The model's `actionTimeoutMs` (120 seconds for the ASpin 2K) is **not** the final timeout; the final timeout = `120s + 离心秒数` (120 s plus the
  centrifugation seconds), configured on the workflow node.
- **Basis**: the manual's example `WaitForEndOfAction(120000 + time*1000)`.

### 3.2 Whether action-type commands are retried automatically after a timeout ✅ decided

- **No retry** (`Disposition.MANUAL`): centrifuging twice destroys the sample, sealing twice crushes the plate.
- Only "transient faults" (semaphore overflow, communication jitter) are retried automatically.

### 3.3 Which block the device error codes use ✅ decided

- `EC-08-<型号块><手册字节>` (`EC-08-<model block><manual byte>`): `40`=S100, `41`=ASpin 2K, new models take `42` onwards.
- **Reason**: the `0x8000000C` of the two machines may have different meanings, so they must not share one control-console code.

---

## 4. How to use this checklist

When bringing on a new device:

1. First copy the manual → into `DeviceModels` (the commands, the error codes, `actionTimeoutMs`);
2. Everything the manual **does not write down** goes into the matching section of this file;
3. On site, work through "how to test" and write the conclusion back into `delayMs` / `note` in `DeviceModels`;
4. Change ⬜ to ✅, and record an entry in `docs/开发日志.md`.

> The criterion for whether a piece of information may go into the code: **the manual writes it down** → it goes in; **the vendor
> confirmed it verbally** → it goes in but is marked in `note`; **we guessed it ourselves** → it only goes into a parameter such as
> `delayMs` where "a wrong guess is not fatal", and the `note` has to say explicitly that it is a guess.




