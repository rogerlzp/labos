# Device Sidecar (.NET)

> **English edition.** The Chinese original is [`sidecar-dotnet/README.md`](README.md);
> this is a line-for-line translation. Numbers, command lines and file paths are
> identical to the Chinese source — if they ever disagree, the Chinese original wins.

**The control console knows exactly one endpoint (`tcp://<industrial-PC>:8501`); changing
the device only changes the transport.**

The console does not know whether it is talking to a serial plate sealer or a
`Cluster.dll` centrifuge — that is the last mile of "adding a device must not require
changing the console": the console does not know the device model, the sidecar does.

---

## 1. What problem it solves

```
labos-kernel ──TCP(JSON+SEP)──> sidecar ──┬── serial 12-byte frame     (S100 plate sealer)
                                          ├── Cluster.dll + XML        (ASpin 2K centrifuge)
                                          └── forward to the Java simulator (a station with no hardware)
```

- **Protocol differences** are absorbed in this layer: JSON ↔ binary frames / XML
- **Device knowledge is not hard-coded**: `models.json` is read at start-up (exported
  from the Java model library)
- **Error codes are not translated**: when the device says `0x8000000C` that is carried
  back to the console as-is; the translation (→ `EC-08-400C`) is the job of
  `DeviceErrorMapper` inside `labos-kernel`

> Why error codes are not translated in the sidecar: the sidecar out on site is written
> in .NET and there may be other implementations in other languages, while "should this
> code hand over to an operator or retry" is **business policy** and must be defined in
> exactly one place. A second translation table in the sidecar = two copies that are
> guaranteed to drift.

---

## 2. Running it

### 2.1 Station (no hardware) — in front of the simulator

```bash
# 1) Start the simulator (device side)
cd labos-kernel
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --model s100 --device 501 --port 19501 --delay 1

# 2) Start the sidecar (console side)
cd sidecar-dotnet/Labos.Sidecar
dotnet run -- --model s100 --transport sim \
             --sim-host 127.0.0.1 --sim-port 19501 \
             --port 8501 --device 501

# 3) The console connects to 127.0.0.1:8501 as usual — exactly like a real device
```

One-command self-check (starts and stops automatically, asserts the timing from the
manual, the exit code is the result):

```bash
./scripts/test-sidecar.sh
```

### 2.2 On site — a real device

```bash
# S100 plate sealer (Windows industrial PC, USB to serial)
Labos.Sidecar.exe --model s100 --transport serial --serial-port COM3 --baud 115200 --port 8501

# ASpin 2K centrifuge (must be Windows + 32-bit, Cluster.dll is 32-bit)
Labos.Sidecar.exe --model aspin-2k --transport dll --dll "C:\Lab\Cluster.dll" --node PlateCtri --port 8501
```

### 2.3 Debugging a single command

```bash
dotnet run -- --model s100 --transport sim --sim-port 19501 --device 501 \
             --once "MoveTray{\"value\":0}"
```

---

## 3. Parameters

| Parameter | Description |
|---|---|
| `--model <id>` | **Required**. Model id (`s100` / `aspin-2k`), see `--list` |
| `--models <path>` | Path to the model library, defaults to `models.json` next to the exe |
| `--port <n>` | Listening port, default 8501 |
| `--transport sim\|serial\|dll` | Transport, default `sim` |
| `--sim-host/--sim-port` | Forwarding target for the sim mode (default 127.0.0.1:19501) |
| `--serial-port` / `--baud` | Serial mode: `COM3` / 115200 |
| `--read-ms` | Minimum read timeout in serial mode (default 3000 ms; each command automatically relaxes it according to the model's duration) |
| `--dll` / `--node` | DLL mode: the `Cluster.dll` path and the module node name (default `PlateCtri`) |
| `--device` | Device number for debugging; in sim mode it **must match the simulator's `--device`** |
| `--list` | List the already-adapted and pending models, then exit |
| `--once <Method[{json}]>` | Send one command, then exit |

---

## 4. Where `models.json` comes from

**Do not edit it by hand.** It is exported from the model library:

```bash
cd labos-kernel
javac -encoding UTF-8 -d out $(find src -name '*.java')
java -cp out com.labos.kernel.device.DeviceModelExport > ../sidecar-dotnet/models.json
```

The generated `models.json` **goes into the repository** (the industrial PC has no Java
runtime). `scripts/test-sidecar.sh` compares the export against the file in the
repository and fails outright on a mismatch — this prevents "the model library changed
but nobody re-exported, so the sidecar on site is still using the stale command table".

---

## 5. The boundaries of the three transports

| Transport | What it can do | What it cannot do |
|---|---|---|
| `sim` | Runs the whole chain (console → sidecar → device) at a station | Cannot verify the frame format or the byte order |
| `serial` | S100's 12-byte frames, low byte first, error frame in byte 5 | Never run against a real device, so `ReadTimeout` can only be estimated from the manual |
| `dll` | ASpin's XML + `Cluster.dll` | Windows only; a blocking call inside the DLL cannot time out |

### Known limitations (written in the code comments — do not expect them to disappear)

1. **`Cluster.dll` is 32-bit**: the sidecar must run as a 32-bit process, otherwise
   `DllNotFoundException`.
2. **A DLL call blocks and cannot time out**: `SendData` is synchronous, so a
   10-minute centrifugation blocks for the whole 10 minutes.
   The console-side timeout must therefore be ≥ `120s + centrifugation seconds` — which
   is what `actionTimeoutMs` in the model is for.
3. **`GetStatus` is made up by the sidecar**: neither manual has a status-query command.
   What the sidecar returns is "I am alive", **not the device status**; the real device
   status can only be read from `CurrSpeed`/`CurrTime`.
4. **The serial mode has not been verified on a real device**: the frames are assembled
   from the manual and the byte order and terminator are copied as-is, but the first
   real connection must use `--read-ms 8000` and check the raw frames in the log.

---

## 6. Reading the log

```
[sidecar 19:22:18] listening on 0.0.0.0:8501  model=s100  transport=sim-bridge 127.0.0.1:19501  evidence=MANUAL
[sidecar 19:22:18] received Seal  id=4
[sidecar 19:22:18] failed Seal (4ms): 0x8000001A the tray is not at its origin, cannot seal
```

- Every line carries the **method name + duration + the raw device code**;
- Failures are not swallowed: what the console sees is exactly the `0x8000001A` on that
  line;
- When the evidence level is not `MANUAL`, start-up prints `⚠` (it can only run the
  workflow skeleton; it cannot be connected to a real device).
