# Protocol and Disconnect Handling

> **English edition.** The Chinese original is [`docs/设备/协议与断线处理.md`](../../设备/协议与断线处理.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **This document answers two questions**:
> 1. After the device receives a command, does it have to send a message back?
> 2. What happens when the device loses power? Will it wait forever and hang?

---

## 1. First, look closely: the protocols of real devices are not the same at all

After reading the two real manuals under `docs/设备/`, the conclusion is very direct:

| Device | Vendor model | Physical layer | Protocol form |
|---|---|---|---|
| Fully automatic centrifuge | Shanghai Aiao **ASpin 2K** | CAN card / serial port (COM) | **XML messages**, dispatched through `Cluster.dll`'s `SendData()` |
| Sealer | **S100** | Serial port 115200 | **Binary frames** (`0xAA` header … `0xFF` trailer) |

**A command to the centrifuge looks like this**:

```xml
<DispatchCmd>
  <strNodeName>PlateCtri</strNodeName>
  <strCmdName>Start</strCmdName>
  <lpCmdArgs><.../></lpCmdArgs>
</DispatchCmd>
```

**A command to the sealer looks like this** (hexadecimal):

```
aa 04 00 00 00 00 00 00 00 00 00 ff     seal once
aa 04 5a 00 00 00 00 00 00 00 00 ff     query the plate type
```

**What this means**:

> **There is no such thing as "one protocol that connects to every device".** Every device
> needs its own protocol adapter layer; whether it "can be onboarded" depends on **whether
> somebody has written its adapter**, not on "whether our framework is general enough".

**The assumption in the current code**: `TcpPersistentDriver` assumes that every device is 「TCP + JSON + the
`#!LABSEP!#` delimiter」 — which only holds for **our own simulator**. A real device either goes
through a DLL (the centrifuge) or through a serial-port binary frame (the sealer), and
**neither of them can enter this channel**.

**The matching architecture gap**:

```
Now:    [protocol client] ──TCP/JSON──> [device]
Needed: [protocol client] ─┬─ TCP/JSON ──> simulator / network-capable devices
                           ├─ DLL/JNA ──> ASpin 2K (CAN)
                           └─ serial binary ──> S100 (sealer)
```

**This is not something to change right away**, but it has to be on the record: **the cost
of "onboarding a new device" is mostly in writing its protocol adapter**.

---

## 2. Does the device have to send a message back?

**Yes. And it must.**

### 2.1 Our protocol (the simulator / devices that support TCP)

Every command the control console sends carries a `message_id`; the device's reply frame expresses
three states through `method_status`:

| `method_status` | Meaning | Control-console action |
|---|---|---|
| `1` | Running (a long task, e.g. centrifuging for 30 minutes) | Keep waiting, **do not judge a timeout** |
| `2` | Completed | Success, move to the next step |
| `3` | Error (`error_code` + `error_text`) | Look up the recovery policy by error code |

**What happens if it does not answer**: the control console waits until the timeout and
then either retries or hands over to an operator, according to the policy.

### 2.2 Real devices have a different form of "reply"

| Device | Successful reply | Failed reply |
|---|---|---|
| ASpin 2K centrifuge | `SendData` returns an XML result | Same, the result carries the error information |
| S100 sealer | `ac` header + the same command code (e.g. `ac 04 ...`) | `ee` header + a 4-byte error code |

**The S100 error codes carry meaning** — they are not the three words "it failed":

| Error code | Meaning | Disposition |
|---|---|---|
| `0x80000006` | No plate detected | Check the plate that was loaded |
| `0x80000007` | Blade fallen out | A person has to change the blade and restart |
| `0x8000000C` | Out of paper | Change the consumable (mind the follower roller) |
| `0x8000001A` | Tray not at the origin, sealing is impossible | Return to the origin first |
| `0x80000005` | Motor jammed | **A person has to investigate; power-cycle after clearing it** |

> **The lesson**: the error code has to distinguish "a retry will do" from "a person must
> handle it". Today our recovery-policy table maps `retry/skip/abort/manual` onto `error_code`, which is the right
> direction, but **the device-specific error codes have not been mapped in yet**.

---

## 3. What happens when the device loses power? (the key question)

### 3.1 Two cases, handled completely differently

| Scenario | Symptom | How the control console notices | How it is handled today |
|---|---|---|---|
| **A real power cut / the network cable is pulled** | The TCP connection drops | `readerLoop` reads EOF | **Immediately** `failAllPending("EC-99-0001","驱动连接断开")`, **every waiting command fails at once** |
| **Network partition / the device is frozen** | The connection is still there, it simply does not answer | No `method_status` arrives | **15-second timeout** (`EC-05-1007`) |

**So: it never waits forever, it never hangs.** There are two gates.

### 3.2 The complete handling chain

```
Send command (with message_id, enters the pending map)
   │
   ├─ The device answers 2/3 ──────────────→ return the result immediately
   │
   ├─ The connection drops ────────────────→ failAllPending, fail immediately (do not wait for the timeout)
   │
   └─ Silent for 15 seconds ──────────────→ timeout: take it out of pending, return EC-05-1007
                                        │
                                        ↓
                                  look up the recovery policy table
                          ┌─────────────┼─────────────┐
                       retry ×3        abort         manual
                          │             │             │
                    resend (same id)  kill the whole graph  wait for an operator to confirm
                          │
                    still failing → switch to manual
```

**Removing it from `pending` matters**: if the device really does answer later, it pushes into
a `future` that nobody is waiting for — not removing it leaks memory; once it is removed, that
late response is discarded as an "orphan".

### 3.3 But resending has a fatal flaw (already fixed)

**The original implementation**:

```java
new DriverCommand(UUID.randomUUID().toString(), ...)   // every retry gets a new id
```

**Walking through the scenario**:

```
1. The control console sends Transfer, the robot arm really grabs the plate
2. The device's reply is lost on the way (network jitter, but the device is fine)
3. The control console waits 15 seconds and times out
4. The policy retries → resend
5. The device receives it — with a **brand-new message_id** — it does not recognise it
6. The device **grabs again** (but it already holds a plate, or the plate is already in place)
```

**The result is the wrong plate being moved, or a crash.**

**It has now been changed to a stable key**:

```
r{runId}-n{nodeId}-e{how many times execute ran}-s{step number}-d{device}
```

- A retry within the same execution → **the id does not change** → the device can
  deduplicate on it
- Switching device (REASSIGN) → the `d` part changes → a new id (it really is another
  physical action)
- The second execution inside a loop → the `e` part changes → a new id (not a retry, the
  work really has to be done again)

**On the device side**: `DeviceCore` already caches successful results, returns the previous
result directly when it receives a duplicate id and **does not execute again**, and writes
「duplicate command」 to the log. Measured: the second call with the same id returns in
**1ms** (without executing), a new id executes normally.

### 3.4 What about real devices?

**This manual does not write down "how duplicate commands are handled"** — which means it
most likely **does not support idempotency**.

So for real devices the correct approach is to be **conservative**:

| Command type | After a timeout |
|---|---|
| Query type (`GetStatus`/`GetPoints`/`CurrSpeed`) | Can safely be retried (no side effects) |
| **Action type** (`Transfer`/`Grip`/`Start` centrifuging/sealing) | **Do not retry automatically**; switch to `manual` so that somebody goes and looks on site |

> **Better to stop and wait for a person than to move a plate twice.**

**Current configuration**: `EC-05-1007` (timeout) is `retry ×3`. **Recommendation**: action-type
commands should go to `manual` after a timeout; or add a `supports_idempotent_retry` flag to `device_instance`, defaulting to
`false`.

---

## 4. What is still missing (by priority)

| # | Gap | Impact | Recommendation |
|---|---|---|---|
| 1 | **Action-type commands are retried automatically after a timeout** | A plate may be moved twice | Switch to `manual`, or make it a per-device capability switch |
| 2 | **There is no "offline" state determination** | After a power cut the frontend still shows "online" until the next call | Heartbeat liveness fails N times → mark offline + raise an alert |
| 3 | **Reconnection storm** | The moment the device recovers, several threads call `ensureConnected` at the same time | There is already a `synchronized`, but backoff has to be added |
| 4 | **Commands are not persisted** | If the process crashes while a command was "sent but not answered", after a restart nobody knows whether it was sent | The `run_command` row has to cover the "sent" state |
| 5 | ~~There is no mapping for device-specific error codes~~ | The S100's `0x8000000C` (out of paper) cannot be classified as "a person has to handle it" automatically | ✅ **Resolved (2026-11-02)**: see "the disposition of item 5" below |

**How item 5 was disposed of** (a `device_error_map` table was originally planned, but was never
created): the mapping finally went into the **model library in the code** (`device/DeviceModels`), while
the policies stayed in the `recovery_policy` table. Reasons:

- "`0x8000000C` means out of paper" is a **fact about the device**, and has to be version managed
  together with the manual; put into the database it becomes "the site can change it at
  will", and nobody knows when it was changed wrongly;
- "out of paper has to be handed to an operator" is **our policy**, and that belongs in
  the database, where the site can tune it (effective without a restart);
- Whatever a table structure can express, a record in the code can express just as well,
  but the code can go through code review and can have unit tests (`DeviceModelSelfTest` checks that the 14
  codes map one by one).

See the [model library and error-code mapping](model-library-and-error-code-mapping.md)
(`型号库与错误码映射.md`) for the chain details.

**Item 4 deserves a word of its own**: today `run_command` records the execution result, but the
intermediate state "the command has been sent and no answer has come back yet" cannot be
judged after a restart if the process happens to crash at that moment — there is **no way
to tell whether the device actually did it**. The conservative approach is: after a
restart, **force these devices to be confirmed by an operator** instead of guessing.

---

## 5. One-sentence summary

> **The device must send a message back; if it does not, that is a timeout; if the
> connection drops, it fails immediately — it never hangs.** **But a "resend" has to be
> idempotent, otherwise it is better to hand over to an operator.** **Real device
> protocols differ in every way; the onboarding cost is in the adapter, not in the
> framework.**


