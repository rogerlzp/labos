# Fully Automatic Centrifuge ASpin 2K (Shanghai Aiao Biotechnology)

> **English edition.** The Chinese original is [`docs/设备/离心机-ASpin2K.md`](../../设备/离心机-ASpin2K.md); this is a line-for-line
> translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they
> ever disagree, the Chinese original wins.

## 1. Basic information

| Item | Content |
|---|---|
| Vendor | Shanghai Aiao Biotechnology Co., Ltd. |
| Model | ASpin 2K fully automatic centrifuge |
| Manual | `AC离心机接口协议使用说明V2.0F.pdf` (V2.0, 2023-06-30, classification: confidential) |
| Module node name | `PlateCtri` (configured in `CanCfg.ini`: `PlateCtri=0X0A,0xE0`) |
| Model library | `aspin-2k` (`DeviceModels`, evidence level `MANUAL`) |
| Simulator | `--model aspin-2k` |

> This is the **first** centrifuge that was adapted. There are dozens of centrifuge models, and they
> are all added one at a time in the same way: see "adding a device in five steps" in the [model
> library and error-code mapping](model-library-and-error-code-mapping.md) (`型号库与错误码映射.md`).

## 2. Physical layer and communication

**It is not Ethernet — it is a CAN card or a serial port.**

| Run mode | Configuration | Explanation |
|---|---|---|
| `RunMode=1` | CAN card | `CanKind=4` (ZLG USB-to-CAN) / `16` (PCI9820I) |
| `RunMode=2` | Serial port | Registry `CanKind=6`, `DevIndex` = the COM port number (hexadecimal, 15 = `F`), `CANBaud=0x1C200` |

**Interface form**: it does not send and receive bytes directly, it **calls `Cluster.dll`**:

```delphi
TSendData = function(AInCmd: PChar; ASizeIn: Integer;
                     AOutResult: PChar; var ASizeOut: Integer): Integer; stdcall;
TPcInitEx   = procedure (); stdcall;
TPcUnInitEx = procedure (); stdcall;
```

That is: **LoadLibrary("Cluster.dll") → PcInitEx → SendData(cmdXml, result)**.

`SendData` **returns 1 on success and 0 on failure**; the concrete error is in the XML in `AOutResult`.

## 3. Command set

The commands are **XML**, with a uniform envelope:

```xml
<DispatchCmd>
  <strNodeName>{module name}</strNodeName>
  <strCmdName>{command name}</strCmdName>
  <lpCmdArgs>{parameter XML}</lpCmdArgs>
</DispatchCmd>
```

| `strCmdName` | Module name | Parameters | Meaning | Model-library method name (alias) |
|---|---|---|---|---|
| `PcCreateModules` | `Cluster` | `<PcRefreshModulesArgs nCreateModulesMode="0"/>` | **Create the module instances (this must be done first)** | `CreateModules` |
| `Init` | `PlateCtri` | `<PlateCentrifugeInitArgs nLength="0" byInitMode="0"/>` | Initialise (return to the origin) | `Home` |
| `ControlCover` | `PlateCtri` | `<PlateCentrifugeSetCoverModeArgs nLength="0" byOpenCoverMode="{0\|1\|2}"/>` | Door: **0=closed / 1=open A / 2=open B** | `CloseDoor` / `OpenDoorA` / `OpenDoorB` (`OpenDoor` is kept for compatibility and means A) |
| `Start` | `PlateCtri` | `<PlateCentrifugeSetStateArgs nLength="0" byCentriMode="0" wCentriSec="{time}" wCentriSpeed="{speed}" byCentriRamp="{ramp}"/>` | Start centrifuging (**time / speed / acceleration**) | `RunProgram` |
| `Stop` | `PlateCtri` | empty | Stop centrifuging | `Stop` |
| `CurrSpeed` | `PlateCtri` | empty | Query the current speed (the result is in `nRetValue`) | `CurrSpeed` |
| `CurrTime` | `PlateCtri` | empty | Query the remaining time (the result is in `nRetValue`) | `CurrTime` |
| — | — | — | **The manual has no command for querying the state** | `GetStatus` (marked `documented=false`) |

> The function names in the manual's examples: `InitPlateCentri` / `ControlPlateCentrin` / `StartPlateCentri` / `StopPlateCentri` / `GetCentriCurrSpeed` / `GetCentriCurrLeftTime`. **What is
> actually dispatched is `strCmdName`**; the function names are only an SDK wrapper.

### Three traps that have to be known

1. **`ControlCover` is one door with three commands**, not three independent commands. The early version
   modelled it as "two commands, Open/Close" and even invented a `ControlCoverClose` — that was wrong and has been
   deleted.
2. **Two doors (A/B)**. The manual's user interface is literally "open door A / open door B". `OpenDoor` in
   a workflow only opens door A; to open door B you have to write `OpenDoorB`.
3. **`Start` has no nominal rpm value**, it is `wCentriSpeed` (the example is 1500). The workflow used to contain
   `RunProgram(rpm=12000, minutes=5)`, which cannot be dispatched on site at all.

## 4. Error handling

The manual has **no error-code table**, only "`SendData` returns 1 on success and 0 on failure, the error is
in the XML in `AOutResult`".

So this device's error codes fall into two categories:

| Code | Source | Meaning | Disposition |
|---|---|---|---|
| `EC-05-2001` | Adapter layer | `SendData` returned 0 (the device refused or the communication failed) | MANUAL: read the XML error, check CAN/serial port |
| `EC-05-2002` | Adapter layer | A command was dispatched before the module instance was created | ABORT: `PcCreateModules` first |
| `EC-05-2003` | Adapter layer | An action command was dispatched before `Init` | ABORT: return to the origin first |
| `EC-06-0006` | Kernel generic | The state does not allow it (door not closed, door opened while running) | MANUAL |

**Note**: after a timeout, an action-type command (`Start`) must **not be resent automatically** — the
manual does not say how duplicate commands are handled, and centrifuging twice can damage the sample.
Better to hand over to an operator for confirmation. This is already written into the model's `note`.

## 5. Timeout: the answer the manual gives

In the manual's example code:

```delphi
SendData(sCmdText, sResult);
WaitForLastReply(10000);                    // first wait 10 s for "received"
WaitForEndOfAction(120000 + wCentriTime*1000);  // then wait 120 s + the centrifugation seconds
```

That is: **centrifuging for 5 minutes means waiting for 120s + 300s = 7 minutes**. If the control
console uses the default 15/30-second timeout, **every centrifugation is judged to have timed out**,
which then triggers a retry (= centrifuging again).

The model library already records `actionTimeoutMs = 120000`; the timeout policy on the control-console side should be
configured as `120s + 离心时长` (120 s plus the centrifugation duration), and must not use the default value.

## 6. The simulator (`--model aspin-2k`)

**Implemented** (`sim/AspinBehavior.java`):

| Real behaviour | Simulator behaviour |
|---|---|
| `PcCreateModules` first | Modules not created → `EC-05-2002` |
| `Init` first | `Start` before initialisation → `EC-05-2003` |
| Two doors A/B | `ControlCover(1/2)`; the reply distinguishes `doorA` / `doorB` |
| No centrifuging while a door is open | `Start` → `EC-06-0006` |
| No opening the door while running | `OpenDoorA/B` → `EC-06-0006` (not in the manual, **a safety assumption**) |
| `Start` has three parameters | Echoes back `timeSec` / `speed` / `ramp`; a missing parameter → `EC-06-0005` |
| `CurrSpeed` / `CurrTime` | Returns `nRetValue` |
| The reply uses the real command names | Sends `OpenDoorB`, the reply is `"method":"ControlCover"` |

```bash
# GUI (one window per device)
java -cp out com.labos.kernel.sim.SimulatorApp --model aspin-2k --device 601 --port 19502
# No GUI
java -cp out com.labos.kernel.sim.DeviceSimulator --model aspin-2k --device 601 --port 19502
```

## 7. Open questions (must be confirmed before connecting a real machine)

- **The values of `nControType`/`byOpenCoverMode`**: the manual says 0=closed, 1=open A, 2=open B (V2.0 is explicit). The
  early version suspected that "open=1 / closed=0 has to be confirmed on site" — **V2.0 already gives
  the answer**, so there is no need to ask again.
- **The unit and range of the acceleration in `Start`** (the example is `byCentriRamp=150`) — the manual gives no
  unit.
- **Whether `ControlCover` is possible while centrifuging** (safety interlock) — the manual does not say. The
  simulator treats it as "refused"; it has to be verified once on site.
- **What state the device is in after a power-cut recovery** (door open/closed? does the program
  continue?) — the manual does not say. Conservative approach: after the recovery, `Init` first and
  only then act.
- **The unit of `nRetValue` for `CurrSpeed`/`CurrTime`** — the manual only says "returned via `nRetValue`"; is the speed in
  rpm or 0.1 rpm, is the time in seconds or minutes? This has to be checked once on site.
- **Node-name conflicts when there is more than one centrifuge** — the node number of `PlateCtri` on each
  unit (`0x0A`) must not be repeated, so when the second machine is connected `CanCfg.ini` needs a different
  node number, and one driver endpoint on the control-console side can only talk to one machine.



