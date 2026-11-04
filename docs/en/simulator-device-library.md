# Simulator Device Library

> **English edition.** The Chinese original is [`docs/模拟器设备库.md`](../模拟器设备库.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **Purpose**: to make the simulator "look like the real thing".
>
> The simulator today only has 4 types (robot / centrifuge / incubator / reader), each
> type has 5–9 methods, the parameters are not validated and the delay is a fixed 150
> ms. The result is that what we measure is a long way from what happens on site — for
> example the centrifuge does not refuse to run because "the door is not closed", and
> the plate reader does not raise an error because "the wavelength is out of range".
>
> This document is organised by the **mainstream laboratory-automation devices**: the
> type, the vendor models, the key parameters and the typical methods. The simulator
> validates against the parameters given here, so that a misconfiguration in the control
> console is caught immediately.
>
> **What this document describes is the "type" (the parameter boundaries). The real
> command names / error codes of one particular machine are in `设备/型号库与错误码映射.md` + `DeviceModels.java`** — that is the
> place where something can actually be connected to a real machine.

---

## 1. Overview of the device classification

Ordered by the stage of a typical automation line:

| # | Type | profile | Representative models | Section of this document |
|---|---|---|---|---|
| 1 | Robot arm | `robot` | Brooks PreciseFlex c3 / KUKA KR | §2 |
| 2 | Liquid-handling workstation | `liquid_handler` | Tecan Freedom EVO / Hamilton STAR / Biomek i5 | §3 |
| 3 | Centrifuge | `centrifuge` | Eppendorf 5810 / Thermo Multifuge | §4 |
| 4 | Plate reader | `reader` | Molecular Devices SpectraMax M5 / Tecan Infinite | §5 |
| 5 | Incubator | `incubator` | Thermo Heracell / LiCONiC | §6 |
| 6 | Plate washer | `washer` | BioTek 405 / Tecan HydroFlex | §7 |
| 7 | Dispenser | `dispenser` | Thermo Multidrop / BioTek | §8 |
| 8 | Plate storage | `plate_hotel` | LiCONiC / Thermo Cytomat | §9 |
| 9 | Sealer | `sealer` | Agilent PlateLoc / Thermo | §10 |
| 10 | Plate peeler | `peeler` | Brooks / Thermo | §10 |
| 11 | Barcode scanning | `barcode` | All kinds of 1D/2D scan heads | §11 |
| 12 | PCR machine | `thermocycler` | Bio-Rad / Applied Biosystems | §12 |
| 13 | Shaker | `shaker` | Eppendorf / Thermo | §13 |
| 14 | Low-temperature storage | `freezer` | -80 °C automated freezer | §14 |

---

## 2. Robot arm `robot`

**Purpose**: moves plates between devices (grab → move → place). It is the "hand" of the
whole line.

| Vendor | Model | Key parameters |
|---|---|---|
| Brooks Automation | **PreciseFlex c3 / c3X** | Benchtop collaborative arm, low-reduction-ratio, reversible-drive axes, designed for the benchtop lab |
| Brooks | PreciseFlex c5 / c8A / c10 / 400 / 3400 | Covers different reaches and payloads |
| KUKA | KR series | Industrial 6-axis, larger payload and reach |
| Stäubli | TX2 series | High-precision industrial arm, often used on large integrated lines |

**PreciseFlex c3 positioning**: desktop-class microplate handling, "handling within the
footprint of a technician".

**Typical methods** (the simulator has to support these)

| Method | Parameters | Explanation |
|---|---|---|
| `Home` | — | Return to the origin |
| `GetPoints` | — | Report the point table (`code,x,y,z`) |
| `SyncPoints` | pointList | Synchronise the points with the control console |
| `Transfer` | `source,target` | One-step transfer (some vendors split it into three steps, see §15) |
| `TransferTo` | `source,target,slot` | With a target level |
| `Grip` | — | Grab the plate (**irreversible point**) |
| `Release` | — | Place the plate |
| `GetStatus` | — | Status (idle/moving/holding) |
| `Stop` | — | Stop |

**Parameter validation** (the simulator should do this):
- The point is not in the `GetPoints` list → report "the point does not exist"
- `Transfer` while a `Grip` is already held (a plate is in the gripper) → report "a plate is
  already held, it cannot be grabbed twice"
- `Transfer` without a prior `Home` (some vendors require returning to the origin first) →
  configurable as a warning

---

## 3. Liquid-handling workstation `liquid_handler`

**Purpose**: dispensing, dilution, aliquoting, plate replication, NGS library
preparation. The most complex and the most expensive machine on the whole line.

| Vendor | Model | Deck | Channels | Volume range | Plate positions |
|---|---|---|---|---|---|
| Tecan | **Freedom EVO 75/100/150/200** | Four deck sizes, 75–200 cm | Up to **88** channel combinations | **0.5 µL – 50 mL** | Depends on the configuration |
| Beckman Coulter | **Biomek i3** | — | Fixed 8 / flexible 8 / 96 / 384 | 0.5 – 5000 µL | **20** |
| Beckman Coulter | **Biomek i5** | — | Same | 0.5 – 5000 µL | **25** |
| Beckman Coulter | **Biomek i7** | — | Same | 0.5 – 5000 µL | **45** |
| Hamilton | STAR / VANTAGE | — | 8 / 96 / 384 | 0.5 – 1000 µL | Depends on the configuration |
| Agilent | Bravo / AssayMap Bravo | — | 96/384 | 0.003 – 250 µL | 9 |

**Typical methods**

| Method | Parameters | Explanation |
|---|---|---|
| `Home` | — | Return to the origin |
| `GetTips` | `tipBox,count` | Pick up tips |
| `DropTips` | — | Eject the tips |
| `Aspirate` | `volume,labware,well` | Aspirate (µL) |
| `Dispense` | `volume,labware,well` | Dispense |
| `Transfer` | `srcLabware,srcWell,dstLabware,dstWell,volume` | One-step transfer |
| `Mix` | `labware,well,volume,cycles` | Mix |
| `SetTemp` | `celsius` | Deck temperature control (on some models) |
| `GetStatus` | — | Status |

**Parameter validation** (very valuable):
- `volume` outside the volume range of that model (e.g. 0.5–5000 µL) → **report a
  volume-range error**
- `Aspirate` without a prior `GetTips` → report "no tips"
- The plate position number exceeds the model (the Biomek i3 only has 20 positions, 21
  were configured) → report "the plate position does not exist"

---

## 4. Centrifuge `centrifuge`

**Purpose**: centrifugal separation, spinning down plates.

| Vendor | Model | Speed | Max. RCF | Capacity | Temperature control |
|---|---|---|---|---|---|
| Eppendorf | **5810 / 5810 R** | 200 – 14,000 rpm (in 10 rpm steps) | 20,913 × g (fixed-angle rotor) | 4×750 mL or **4×4 MTP** | −9 ~ 40 °C (the R model) |
| Eppendorf | 5810 plate rotor | — | **3,486 × g** | 4 microplates | — |
| Thermo | Multifuge X1/X3 | A similar order of magnitude | — | — | — |

**Details of the 5810** (from the vendor page):
- 18 rotors available, 35 user programs
- Timed 1–99 minutes / continuous centrifugation / short spin
- Automatic rotor identification + imbalance detection
- Noise < 70 dB(A), lid-opening height 29 cm

**Typical methods**

| Method | Parameters | Explanation |
|---|---|---|
| `Home` | — | Return to the origin |
| `OpenDoor` / `CloseDoor` | — | Door |
| `Load` | `slot` | Load (plate position / rotor bucket) |
| `Unload` | `slot` | Unload |
| `RunProgram` | `program` or `rpm,minutes,temp` | Run |
| `SetTemp` | `celsius` | Temperature control (the R model) |
| `GetStatus` | — | idle/running/door_open |
| `Stop` | — | Stop (some models support an emergency stop) |

**Parameter validation** (critical):
- `rpm` outside 200–14,000 → report "the speed is out of range"
- **`RunProgram` while the door is not closed → report "the door is not closed"** (a real device
  refuses it, the simulator has to be able to detect this)
- `temp` outside −9~40 → report "the temperature is out of range"
- `OpenDoor` while running → report "opening the door is forbidden while running"

---

## 5. Plate reader `reader`

**Purpose**: reading plates (absorbance / fluorescence / luminescence / time-resolved).

| Vendor | Model | Wavelength range | Mode | Notes |
|---|---|---|---|---|
| Molecular Devices | **SpectraMax 190** | 190–850 nm (in 1 nm steps) | Absorbance | 0–4.0 OD, PathCheck |
| Molecular Devices | **SpectraMax ABS** | 340–850 nm | Absorbance | −0.000 ~ 4.000 OD |
| Molecular Devices | **SpectraMax M5** | Depends on the module | **Five modes**: Abs/FI/TRF/Lum/FP | The mainstream multi-mode machine |
| Molecular Devices | SpectraMax iD3 / iD5 | Depends on the module | Abs/FI/Lum (the iD5 adds TRF/FP) | Touchscreen |
| Tecan | Infinite series | Depends on the module | Abs/FI/Lum/TRF | — |
| BMG | CLARIOstar | Depends on the module | Multi-mode | — |

**Typical methods**

| Method | Parameters | Explanation |
|---|---|---|
| `OpenDoor` / `CloseDoor` | — | Door |
| `Load` / `Unload` | `slot` | Load / unload the plate |
| `Read` | `mode,wavelength,plate` | Read the plate |
| `GetResult` | `plate` | Fetch the result |
| `GetStatus` | — | — |

**Parameter validation**:
- `wavelength` outside the range of the model (e.g. 190–850) → report "the wavelength is out of
  range"
- `mode` is not supported (only absorbance was configured, but fluorescence is requested)
  → report "this detection mode is not supported by this model"
- `Read` without a prior `Load` → report "no plate"

---

## 6. Incubator `incubator`

**Purpose**: constant temperature / CO₂ incubation, temporary storage.

| Vendor | Model | Temperature control | CO₂ | Plate positions |
|---|---|---|---|---|
| Thermo | Heracell VIOS | Room temperature +5 ~ 55 °C | 0–20% | Depends on the chamber |
| LiCONiC | StoreX / Cytomat series | Depends on the model | Optional | Dozens ~ thousands |
| Panasonic | MCO series | — | — | — |

**Typical methods**: `Home` / `OpenDoor` / `CloseDoor` / `Deposit1` (in) / `Withdraw1` (out) / `SetTemp` / `SetCO2` / `GetTemp` /
`GetStatus`

**Parameter validation**:
- `temp` out of range → error
- `CO2` out of range → error
- The level does not exist (fewer levels configured than requested) → report "the level
  does not exist"

---

## 7. Plate washer `washer`

**Purpose**: ELISA plate washing.

| Vendor | Model | Channels | Notes |
|---|---|---|---|
| BioTek (Agilent) | **405 TS** | 96/384 | Magnetic-bead washing optional |
| Tecan | HydroFlex | 96/384 | — |

**Typical methods**: `Load` / `Wash` (`cycles,volume,soak`) / `Dispense` / `Unload` / `GetStatus`

**Parameter validation**: `volume` out of range, `cycles` ≤ 0 → error

---

## 8. Dispenser `dispenser`

**Purpose**: fast whole-plate dispensing (faster than the liquid-handling workstation,
more channels).

| Vendor | Model | Channels | Volume range |
|---|---|---|---|
| Thermo | **Multidrop Combi** | 8/12/16 channels → whole plate | 0.5 – 2500 µL |
| BioTek | 406 / 405 | Depends on the model | — |

**Typical methods**: `Dispense` (`volume,cassette`) / `Prime` / `GetStatus`

---

## 9. Plate storage `plate_hotel`

**Purpose**: automatic storage and retrieval of large numbers of microplates (the "plate
library").

| Vendor | Model | Capacity | Temperature control |
|---|---|---|---|
| LiCONiC | **StoreX / LTX** | Dozens ~ thousands of plates | Ambient / low temperature optional |
| Thermo | **Cytomat** | Depends on the model | Optional |
| Hamilton | Verso / PlateStore | — | — |

**Typical methods**: `Home` / `Store` (`barcode,slot`) / `Retrieve` (`barcode`) / `GetStatus` / `OpenDoor` / `CloseDoor`

**Parameter validation**: `slot` beyond the capacity → error; the same `barcode` stored twice
→ report "it already exists"

---

## 10. Sealer / plate peeler `sealer` / `peeler`

| Type | Representative | Parameters |
|---|---|---|
| Sealer | Agilent **PlateLoc** | Sealing temperature (e.g. 165 °C), time (e.g. 3.5 s) |
| Peeler | Brooks / Thermo | Peel position, speed |

**Methods**: `Seal` (`temp,seconds`) / `Peel` / `Load` / `Unload` / `GetStatus`

---

## 11. Barcode scanning `barcode`

**Methods**: `Scan` (returns `barcode,format`) / `GetStatus`

**Parameters**: supports 1D (Code128) / 2D (DataMatrix) — microplates usually use
DataMatrix.

---

## 12. PCR machine `thermocycler`

| Vendor | Model | Temperature range | Notes |
|---|---|---|---|
| Bio-Rad | C1000 / T100 | 4 – 100 °C | — |
| Applied Biosystems | ProFlex / Veriti | 4 – 100 °C | — |

**Methods**: `RunProgram` (`program`) / `SetTemp` / `GetStatus` / `Stop`

**Parameter validation**: `temp` outside 4–100 → error; the number of program steps is 0
→ error

---

## 13. Shaker `shaker`

**Methods**: `Start` (`rpm,minutes`) / `Stop` / `GetStatus`

**Parameters**: 0 – 3000 rpm (depends on the model).

---

## 14. Low-temperature storage `freezer`

**Methods**: `Store` / `Retrieve` / `SetTemp` / `GetTemp` / `GetStatus`

**Parameters**: −80 °C ± 5 (out of range raises an alarm).

---

## 15. One important real difference: is a `Transfer` one action or three

**This is the first thing we have to measure with the simulator** (item 2 of the
real-machine verification list in §五 of the review record).

| Vendor style | `Transfer` semantics |
|---|---|
| Integrated (e.g. some Brooks configurations) | **One command** does grab → move → place |
| Step-by-step (most collaborative arms + a separate gripper) | **Three commands**: `Grip` → `MoveTo` → `Release` |

**Why it matters**: our L4 "action-level lock" assumes that a `Transfer` is **one
uninterruptible action**. If a real device needs three commands, then "snatched halfway
through" happens between the `Grip` and the `Release` — both the granularity of the lock and
the moment it is released have to change.

**How the simulator does it**: with a `--transfer-mode one-shot|three-step` switch, **both have to work**, so that a
workflow can be verified under both semantics.

---

## 16. How far the simulator has to go

"It can return OK" is not enough. The goal is to **make a misconfiguration in the
control console show up on the simulator**:

| Layer | Today | Goal |
|---|---|---|
| Method names | 30 across 4 types | Cover the 14 types of this document |
| Parameter validation | **None** | Volume range / ranges / preconditions (door, tips, plate) |
| State machine | None (every call returns OK) | idle/running/holding/door_open, illegal transitions are refused |
| Delay | A fixed 150 ms | **Per device type** (centrifuge 300 s, plate reader 5 s, robot arm 2 s) |
| Error codes | Generic codes + model-specific codes (14 registered for the S100) | Distinguish them per model and map them onto the control-console codes (see `设备/型号库与错误码映射.md`) |
| Log | One line | Structured (method, parameters, duration, result) |

---

## 17. Sources

- Tecan Freedom EVO series (75/100/150/200, up to 88 channels, 0.5 µL–50 mL)
- Beckman Biomek i3/i5/i7 (20/25/45 plate positions, 0.5–5000 µL)
- Eppendorf Centrifuge 5810/5810R (200–14,000 rpm, 20,913×g, plate rotor 3,486×g, −9~40
  °C)
- Molecular Devices SpectraMax 190 (190–850 nm) / ABS (340–850 nm) / M5 (five modes) /
  iD3/iD5
- Brooks PreciseFlex c3/c3X/c5/c8A/c10/400/3400 (benchtop collaborative arm)
- LiCONiC StoreX / Thermo Cytomat (plate storage)
- BioTek 405 TS / Thermo Multidrop (plate washing / dispensing)
- Agilent PlateLoc (sealing)

> The parameters are for the **simulator's boundary validation**, not a basis for
> procurement. The actual configuration is defined by the nameplate / manual of the
> device on site.

