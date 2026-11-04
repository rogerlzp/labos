# Device Documentation Index

> **English edition.** The Chinese original is [`docs/设备/README.md`](../../设备/README.md); this is a line-for-line
> translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they
> ever disagree, the Chinese original wins.

This directory holds the **real material about the devices** and the **alignment notes for the
simulator**. Before bringing on a new device, check whether the manual is here; if it is not, ask for
the manual first and only then talk about onboarding.

---

## 1. What is in the directory

| File | Nature | Explanation |
|---|---|---|
| `AC离心机接口协议使用说明V2.0F.pdf` | **Real manual** | Shanghai Aiao ASpin 2K, CAN/serial port + `Cluster.dll`, XML commands |
| `S100封膜机通信接口说明 .docx` | **Real manual** | S100 sealer, serial port 115200, binary frame protocol |
| `离心机-ASpin2K.md` | My own write-up | Protocol breakdown + model-library mapping + simulator alignment |
| `封膜机-S100.md` | My own write-up | Frame format + 14 error codes + how the control console uses them + simulator alignment |
| `型号库与错误码映射.md` | **Onboarding specification** | The five steps to add a device, the error-code chain, the code-range rules, the state of the real database |
| `现场确认清单.md` | **Gap checklist** | The items the manual does not write down but that must be tested before connecting a real machine (timeouts / units / interlocks) |
| `协议与断线处理.md` | Architecture note | **Does the device have to answer, what happens on a power cut, why idempotency** |

---

## 2. Fourteen device types: which have a real basis, which are inferred

This has to be spelled out, otherwise "the parameters I guessed" get mistaken for the manual.

| # | Type | profile | Basis | Model library / simulator alignment |
|---|---|---|---|---|
| 1 | Fully automatic centrifuge | `centrifuge` | ✅ **Real manual (ASpin 2K)** | ✅ `--model aspin-2k` (two doors, initialisation order, timeouts) |
| 2 | Sealer | `sealer` | ✅ **Real manual (S100)** | ✅ `--model s100` (tray origin, plate type, 14 error codes) |
| 3 | Robot arm | `robot` | Vendor public specification (Brooks PreciseFlex) | ✅ Pick-and-place already aligned |
| 4 | Liquid-handling workstation | `liquid_handler` | Vendor public specification (Tecan / Biomek) | ✅ Volume range already aligned |
| 5 | Plate reader | `reader` | Vendor public specification (SpectraMax) | ✅ Wavelength already aligned |
| 6 | Incubator | `incubator` | Vendor public specification (Thermo) | ✅ Temperature control already aligned |
| 7 | Plate washer | `washer` | Vendor public specification (BioTek) | 🟡 Only the basic methods |
| 8 | Dispenser | `dispenser` | Vendor public specification (Multidrop) | 🟡 Same |
| 9 | Plate storage | `plate_hotel` | Vendor public specification (LiCONiC/Cytomat) | 🟡 Same |
| 10 | Plate peeler | `peeler` | Inferred | 🟡 Same |
| 11 | Barcode scanning | `barcode` | Generic | 🟡 Same |
| 12 | PCR machine | `thermocycler` | Vendor public specification | 🟡 Same |
| 13 | Shaker | `shaker` | Generic | 🟡 Same |
| 14 | Low-temperature storage | `freezer` | Generic | 🟡 Same |

> **Only rows 1 and 2 have a real manual.** The rest are **boundary values** defined from the public
> specification, their purpose is "so that a wrong parameter can raise an error" — **not** "this is
> how the device actually works".

---

## 3. Centrifuges: adding them one at a time

A centrifuge is not a single model. **ASpin 2K is only the first one**; the remaining models are on
the "to be adapted" list of the model library:

```bash
java -cp out com.labos.kernel.sim.DeviceSimulator --list-models
```

| Model | Status | What is missing |
|---|---|---|
| Shanghai Aiao ASpin 2K | ✅ Can be connected | — |
| Eppendorf 5810 / 5810 R | ⏳ To be adapted | Communication interface specification |
| Thermo Multifuge X1/X3 | ⏳ To be adapted | Communication interface specification |
| Beckman Coulter Allegra X-15R | ⏳ To be adapted | Communication interface specification |
| Domestic fully automatic centrifuge (placeholder) | ⏳ To be adapted | The concrete vendor model |

**The cost of adding one machine ≈ half a day**: add one entry in `DeviceModels` (commands + error codes +
timeouts) → add a `DeviceBehavior` (timing constraints) if necessary → regenerate the SQL → run the three
self-tests → update the Markdown files in this directory.

See [`model-library-and-error-code-mapping.md`](model-library-and-error-code-mapping.md) for details.

### The three places that are already aligned (they did not match before)

| Ours (old) | The real ASpin 2K | Current state |
|---|---|---|
| `OpenDoor` / `CloseDoor` | `ControlCover`, **two doors A/B** | ✅ Three aliases: `OpenDoorA` / `OpenDoorB` / `CloseDoor` |
| `RunProgram(rpm, minutes)` | `Start(time, speed, acceleration)` (`Start(时间, 速度, 加速度)`) | ✅ Three parameters, `rpm` is no longer used |
| `GetStatus` | `CurrSpeed` + `CurrTime` (queried separately) | ✅ Two queries; `GetStatus` is marked "not provided in the manual" |
| `Home` | `Init` | ✅ `Home` → `Init` |
| — | `PcCreateModules` (the modules must be created first) | ✅ In the model library; the simulator rejects "modules not created" |

---

## 4. The real-machine layer: the sidecar

The model library answered "what does this machine say"; **who says it** is the sidecar's job (`sidecar-dotnet/`):

```
labos-kernel ──TCP──> sidecar ──┬── serial port, 12-byte frame    (S100)
                                ├── Cluster.dll+XML  (ASpin 2K)
                                └── forward to the simulator       (no hardware at the station)
```

The control console only knows one endpoint; changing the device only changes the transport. The error
codes are translated on the control-console side, the sidecar only brings back the raw code — that way
"should this code be handed to a person or retried" is defined in exactly one place.

Self-test: `./scripts/test-sidecar.sh` (starts the simulator + the sidecar and asserts the timing from the manual).

## 5. Error codes: what the device says and the control console has to understand

```
Device 0x8000000C → look it up in the model library → EC-08-400C (MANUAL)
              → recovery_policy (in the database) → hand over to an operator + the alert carries "change the consumable"
```

- **Device code → control-console code** lives in the code (it travels with the manual version, `DeviceModels`);
- **How to handle it** lives in the database (`recovery_policy`, the site can change it without a restart);
- **An unregistered code** always becomes `EC-08-9001` and is handed to an operator; the raw code is kept in
  `device_raw_response`.

Self-test: `java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.driver.DeviceErrorChainSelfTest`

---

## 6. What a per-device-type document should contain

When adding a device document, follow this structure so that the next person can carry on:

```markdown
# <device name> (<vendor model>)

## 1. Basic information
Vendor / model / manual version / manual path / model-library id / evidence level

## 2. Physical layer and communication
Serial port or Ethernet or CAN; baud rate; protocol form (XML / binary / JSON)

## 3. Command set
| Command | Parameters | Meaning | Model-library method name (alias) |

## 4. Error codes
| Code | Meaning | Control-console code | Disposition | Retry or a person |

## 5. How the simulator aligns
Implemented / differences from the real device / how to produce a fault

## 6. Open questions
The points that must be confirmed before connecting a real machine
```

---

## 7. The manuals that are still missing

To make the simulator really "look like" these devices, their **communication interface
specifications** are needed (not the operator manuals):

- [ ] Centrifuge: Eppendorf 5810R / Thermo Multifuge X1R / Beckman Allegra X-15R (see section 3)
- [ ] Robot arm (what brand is it on site? Brooks / KUKA / other)
- [ ] Liquid-handling workstation
- [ ] Plate reader
- [ ] Incubator
- [ ] Plate storage
- [ ] Plate washer / dispenser
- [ ] Barcode scanning

**Without a manual the simulator can only be "superficially similar"**: the interface looks right, the
parameter ranges look right, but **the command names and the meaning of the parameters are guesses**
and will have to be changed on site. That is why the model library marks this with `Evidence`: `MANUAL` (can be
connected) / `SPEC` (to be verified) / `PLANNED` (only the model is known).


