# Sealer S100

> **English edition.** The Chinese original is [`docs/设备/封膜机-S100.md`](../../设备/封膜机-S100.md); this is a line-for-line
> translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they ever
> disagree, the Chinese original wins.

## 1. Basic information

| Item | Content |
|---|---|
| Model | S100 sealer |
| Manual | `S100封膜机通信接口说明 .docx` |
| Communication | **Serial port, baud rate 115200, no parity, 1 stop bit** |
| Model library | `s100` (`DeviceModels`, evidence level `MANUAL`) |
| Simulator | `--model s100` (with timing constraints: tray origin, plate type 1–8, latched faults) |

## 2. Frame format

A fixed 12 bytes:

| Position | 1 | 2 | 3 | 4 | 5–10 | 11 | 12 |
|---|---|---|---|---|---|---|---|
| Content | Header | Command code | Index | Reserved | Data (**low byte first**) | Reserved | Reserved |

- Host → device: header **`0xAA`**, trailer **`0xFF`**
- Device → host: acknowledgement **`0xAC`**, error **`0xEE`**
- In an **error frame only byte 5 is the identifying byte**; bytes 6–8 are invalid data (but they are required
  to be recorded)

## 3. Command set

| Command code | Index | Data | Meaning | Method name in the model library |
|---|---|---|---|---|
| `0x00` | Parameter index | Parameter value | Set a parameter | `SetParam` |
| `0x01` | Parameter index | — | Read a parameter (replies `ac 01 …`) | `ReadParam` |
| `0x02` | — | — | Store the parameters permanently (otherwise they are lost on a power cut) | `SaveParam` |
| `0x03` | — | — | Print all parameters | `PrintParams` |
| **`0x04`** | — | — | **Seal once**: `aa 04 00 00 00 00 00 00 00 00 00 ff` | `Seal` |
| `0x04` | `0xA5` | Plate type 1–8 | Seal and change the plate type | `SealWithPlateType` |
| `0x04` | `0x5A` | — | Query the plate type (replies `ac 04 5a 00 08 …`) | `QueryPlateType` |
| `0x05` | — | — | Reset | `Reset` |
| `0x06` | — | — | Clear the fault flag | `ClearFault` |
| `0x07` | `0x08` | — | Query the version | `GetVersion` |
| `0x0C` | — | Absolute tray Y position (0 = return to the origin) | Move the tray | `MoveTray` |

**Example of a tray move** (to 10000):
```
aa 0c 00 00 10 27 00 00 00 00 00 ff
        └─ 10 27 = 0x2710 = 10000 (low byte first)
```

> `0x04` is **one command code carrying three uses**, distinguished by the index byte (`0x00` / `0xA5` / `0x5A`). It
> is registered in the model library under three method names because the adapter layer has to know which
> index to fill in, not because the device has three commands.

## 4. Error codes (`0xEE` header, byte 5 is the identifying byte)

**The manual has 14 in total** (not 15; `0x0D` and `0x0F` do not exist — do not invent them).

| Device code | Meaning | Control-console code | Disposition | Severity | Retry or call a person |
|---|---|---|---|---|---|
| `0x80000001` | Semaphore overflow | `EC-08-4001` | RETRY | warn | Retry automatically 3 times |
| `0x80000002` | Wrong message type | `EC-08-4002` | ABORT | fatal | The protocol version does not match, retrying is useless |
| `0x80000003` | Parameter out of range | `EC-08-4003` | FIX_PARAM | error | Change the parameter (plate type 1–8) |
| `0x80000004` | CAN communication error | `EC-08-4004` | RETRY | warn | Retry twice, check the wiring if it persists |
| `0x80000005` | **Motor jammed** | `EC-08-4005` | MANUAL | error | **A person has to investigate; power-cycle after clearing it** |
| `0x80000006` | No plate detected | `EC-08-4006` | MANUAL | error | **Call someone to load the plate + clear the fault** |
| `0x80000007` | **Blade fallen out** | `EC-08-4007` | MANUAL | error | **Open the machine, change the blade, then restart** |
| `0x80000008` | Unknown command received | `EC-08-4008` | ABORT | fatal | The command / index is not supported, check the version |
| `0x80000009` | Invalid value | `EC-08-4009` | FIX_PARAM | error | Check the position / plate-type values |
| `0x8000000A` | Error while storing parameters | `EC-08-400A` | MANUAL | error | A person confirms and stores the parameters again |
| `0x8000000B` | Sensor failure | `EC-08-400B` | MANUAL | error | Check the origin / in-position signals |
| `0x8000000C` | **Out of paper** | `EC-08-400C` | MANUAL | error | **Change the consumable** (if the new roll still errors → bend the metal plate that fixes the follower roller) |
| `0x8000000E` | Event wait timed out | `EC-08-400E` | RETRY | warn | Retry automatically 3 times |
| `0x8000001A` | **Tray not at the origin, sealing is impossible** | `EC-08-401A` | FIX_PARAM | error | **Send `0x0C` first to return to the origin** |

### How the control console uses this table

```
The device replies 0x8000000C
  → TcpDriverAdapter parses the reply
  → DeviceErrorMapper looks up the model library: EC-08-400C / MANUAL / severity error
  → recovery_policy (in the database, scope=node) → MANUAL
  → the alert body carries "更换耗材；新卷装上仍报错时弯折从动轮固定金属片"
```

Three things are worth noticing:

1. **The numbering is friendly for working backwards**: `EC-08-40` + the low byte of the manual code = `0x8000000C` ↔ `EC-08-400C`.
   Operations can derive the device code directly from the control-console code without looking anything up.
2. **An unregistered code is not silent**: it always becomes `EC-08-9001` (hand over to an operator), and the raw code
   is written into `device_raw_response` and the message, so it can be registered afterwards.
3. **Changing the disposition does not require a code change**: `recovery_policy` lives in the database, so if the site
   wants fewer retries it is a plain UPDATE.

> For the complete chain and "adding a device in five steps" see the [model library and error-code
> mapping](model-library-and-error-code-mapping.md) (`型号库与错误码映射.md`).

## 5. The simulator (`--model s100`)

**Implemented** (`sim/S100Behavior.java`):

| Real behaviour | Simulator behaviour |
|---|---|
| No sealing while the tray is not at the origin | After `MoveTray(10000)` a `Seal` → replies `0x8000001A` |
| The plate type is only 1–8 | `SealWithType(9)` → replies `0x80000003` |
| An unknown command code | Replies `0x80000008` (commands outside the model library are caught earlier on the control-console side by `EC-06-0004`) |
| Faults are **latched** | After reporting `0x8000000C`, sealing again without clearing the fault reports the same code |
| Clearing the fault | `ClearFault` unlocks it; `Reset` also returns to the origin |
| Setting / reading parameters | `SetParam(index,value)` → `ReadParam(index)` reads it back |
| Reset | `Reset` returns to the origin + clears the fault |

**Differences from the real device (you have to know them)**:

| Ours | The real one | Impact |
|---|---|---|
| JSON `{"method":"Seal"}` | A binary 12-byte frame | The protocol adapter layer (.NET sidecar / serial port) has to assemble the frame itself |
| `Seal` has no parameters | The parameters have to be set first with `0x00` | The workflow has to call `SetParam` first; you cannot rely on `Seal(165, 3.5)` |
| Sealing is a fixed 12 seconds | The manual does not state the real duration | Start with 12 seconds and change `delayMs` in `DeviceModels` after measuring on site |
| `"value":10000` | Low byte first, 6 bytes | The adapter layer is responsible for the byte order |

**How to use it to catch workflow defects**:

```bash
# Start the simulator
java -cp out com.labos.kernel.sim.SimulatorApp --model s100 --device 501 --port 19501

# Or the command line + a timed fault (fail once every 3 actions, replying out of paper)
java -cp out com.labos.kernel.sim.DeviceSimulator --model s100 --port 19501 --fail 3 --fail-code 0x8000000C

# Fail only on sealing, the first 5 succeed — simulates "the paper ran out halfway"
java -cp out com.labos.kernel.sim.DeviceSimulator --model s100 --port 19501 \
     --fail 1 --fail-method Seal --fail-code 0x8000000C --fail-from 6
```

The GUI also has: **produce fault** (pick a code from the manual), **clear fault**, **move tray to 10000**.

## 6. Open questions

- **The parameter index table** (what the "parameter index" of `0x00`/`0x01` really corresponds to) — the manual
  has no table. The simulator only does "store it / read it back" and does not interpret the meaning of an
  index.
- **How long one sealing takes** — this decides what timeout the control console should use. It is currently
  12 seconds.
- **Whether a reset is needed after power-up** (`0x05`) — the manual does not say.
- **Where the tray stops when the power goes out during a sealing, and after the recovery** — the manual does
  not say. ⇒ Conservative approach: after a power-cut recovery, first `Reset` + `MoveTray(0)`, then seal.
- **The valid range of the tray position** — the manual only gives the single example 10000, and the simulator
  does not validate an upper limit.


