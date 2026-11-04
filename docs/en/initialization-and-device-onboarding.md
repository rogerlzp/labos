# LabOS v0.1 · Initialization and Device Onboarding Guide

> **English edition.** The Chinese original is [`docs/初始化与设备接入指南.md`](../初始化与设备接入指南.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 |
| Date | 2026-11-01 |
| Status | Pending review |

---

## 0. The three questions this document answers

1. Starting from zero, how does a laboratory build up **island → device → workflow**?
2. How do the devices in an island get their **IP**? What is the IP for?
3. What is still missing from the system? (an honest list)

---

## 1. The four-layer data model

```
resource_node（the resource tree, decides "where"）
  ├─ kind=island          island      26
  │    └─ kind=device     device      239
  │         ├─ kind=incubator      incubator slot   1,021
  │         ├─ kind=storage        storage slot     1,122
  │         └─ kind=transfer_point transfer point     420
  │
device（the model dictionary, decides "what"）          59 models
  └─ code=X173  name=Incubator_2C-1
       └─ capability_id → what it can do

device_instance（the instance, decides "which one, connected where"）  239
  ├─ device_id     → the model
  ├─ resource_id   → its place in the resource tree
  ├─ ip            → the device IP (176 have a value, 63 are empty)
  └─ driver_id     → which driver endpoint it connects to (**currently all empty**)

driver（the driver endpoint, decides "which language"）        13
  ├─ endpoint = tcp://192.0.2.10:8501
  ├─ protocol = netdll / tcp
  └─ manifest = the capability list + the delimiter + the device count
```

**In one sentence**: `resource_node` says where, `device` says which model,
`device_instance` says which IP this machine connects to, `driver` says which service port
it connects to.

---

## 2. What the device IP is for

### 2.1 The real communication chain

```
The kernel (Java)
   ↓ TCP + JSON (the delimiter #!LABSEP!#)
The driver service (.NET, running on 192.0.2.10:8501)
   ↓ the vendor DLL / serial port / proprietary protocol
The device (incubator, robot arm, plate reader…)
```

**Key**: the kernel **does not connect to the device directly**, only to the **driver
service**.

So the actual purposes of `device_instance.ip` are:

| Purpose | Explanation |
|---|---|
| **Identifying the ownership** | Which subnet the machine holding this device is on |
| **Troubleshooting** | Knowing which cabinet to go to during on-site debugging |
| **Deriving the driver endpoint** | The comment says `"source": "equipment_instance.ip_address + serial_port"` |

**What really determines the communication is `driver.endpoint`**, not
`device_instance.ip`.

For example:

```
X173  Incubator_2C-1   ip=192.0.2.10  →  driver DRV-19200200196-8501
                                                endpoint=tcp://192.0.2.10:8501
```

The device IP and the driver IP are on the same subnet, but they are not the same thing —
one driver service usually handles a dozen devices on one island.

### 2.2 The 13 existing driver endpoints

| Endpoint | Device count | Capability count |
|---|---:|---:|
| `tcp://192.0.2.10:8501` | 121 | 13 kinds |
| `tcp://192.0.2.3:8500` | 11 | 3 kinds |
| `tcp://192.0.2.10:8502` | 2 | 0 |
| …13 in total | 239 | |

---

## 3. Initialising from zero: six steps

### Step 1: create the island

```sql
INSERT INTO resource_node (tenant_id, code, name, kind, path, sort_order)
VALUES (1, 'LAB-A', 'Lab A', 'island', '/LAB-A', 10)
RETURNING id;
```

On the interface this corresponds to "Operations → Devices & Islands → Add island"
(**not built yet**).

### Step 2: create the device model (skip it if the library already has it)

```sql
INSERT INTO device (tenant_id, code, name, model, vendor, device_type, capability_id)
SELECT 1, 'Incubator_2C', '2℃ incubator', 'X173', 'LabOS', 'Incubators', c.id
FROM capability c WHERE c.code = 'INCUBATE' AND c.tenant_id = 1;
```

**If this machine is already registered in the model library** (`s100` / `aspin-2k`), write
the model id into `attr`:

```sql
UPDATE device SET attr = attr || '{"device_model":"s100"}'::jsonb WHERE code = 'Sealer_1';
```

It runs without that too, but the error codes the device reports are then **passed through
as they are** (`0x8000000C`),
and `recovery_policy` finds no policy ⇒ every fault becomes an "unknown error, hand over to
an operator".
The principle is explained in section 4 of
[`设备/型号库与错误码映射.md`](../设备/型号库与错误码映射.md).

### Step 3: create the device instance (attach it to the island, fill in the IP)

```sql
-- First create the "device node" in the resource tree
INSERT INTO resource_node (tenant_id, parent_id, code, name, kind, path)
VALUES (1, :island_id, 'X173', 'Incubator_2C-1', 'device', '/LAB-A/X173')
RETURNING id;   -- remember it as :resource_id

-- Then create the device instance
INSERT INTO device_instance
  (tenant_id, code, name, device_id, resource_id, ip, driver_id)
VALUES (1, 'X173', 'Incubator_2C-1', :device_id, :resource_id,
        '192.0.2.10', :driver_id);
```

### Step 4: create the driver endpoint

```sql
INSERT INTO driver (tenant_id, code, name, version, protocol, endpoint, manifest)
VALUES (1, 'DRV-19200200196-8501', 'Driver service 192.0.2.10:8501', '1.0', 'netdll',
        'tcp://192.0.2.10:8501',
        '{"tcp_delimiter":"#!LABSEP!#","driver_kind":"dotnet-dll"}'::jsonb);
```

### Step 5: verify the connection (the shadow probe)

```bash
# Sign in to get a token
TOKEN=$(curl -s -X POST http://localhost:18080/api/auth/login \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

# Send a single command to one device and check whether the contract holds
curl -X POST "http://localhost:18080/api/shadow/probe?resourceId=200173&method=OpenDoor" \
  -H "Authorization: Bearer $TOKEN"
```

The response:

```json
{
  "ok": true,
  "elapsedMs": 83,
  "contract": { "messageId": true, "messageType": true,
                "method": true, "methodStatus": true },
  "rawResponse": "{\"message_id\":\"...\",\"message_type\":5,...}"
}
```

**All 4 contract fields have to be true before it counts as connected.** A false means the
driver returns a format that does not match: either change the driver, or change the
adapter layer.

### Step 6: create the workflow

Two routes:

| Route | Suitable for | Operation |
|---|---|---|
| **Dragging in the designer** | A new workflow | "Authoring → Workflows → New" → draw it in bpmn-js → save |
| **Importing stored workflow XML** | Migration | `INSERT INTO workflow (graph)` directly |

Once the workflow node is created, the **preflight** tells you whether it can run:

```bash
curl "http://localhost:18080/api/workflows/{id}/preflight" -H "Authorization: Bearer $TOKEN"
```

---

## 4. The gap to the current state (an honest list)

| # | Gap | Impact | Priority |
|---|---|---|---|
| 1 | **`device_instance.driver_id` is entirely empty** | At run time it falls back to "all devices hang off the same driver"; with several drivers it connects to the wrong one | **P0** |
| 2 | **63 devices have no IP** | They cannot be located in a cabinet, and the endpoint cannot be derived with several drivers | P1 |
| 3 | **The driver endpoints can only be changed in `appsettings.json`** | Changing an endpoint means editing a config file and restarting | P1 |
| 4 | **There is no "Add device" interface** | You have to write SQL | P1 |
| 5 | **There is no "Add island" interface** | You have to write SQL | P1 |
| 6 | **The driver capability `manifest` is computed at import time** | It is not updated after a device is added on site | P2 |

### The concrete impact of gap 1

`buildDriverRegistry()` currently does this:

```java
// All devices are attached to the same mock/tcp driver
DriverDescriptor desc = new DriverDescriptor("DRV-LOCAL-MOCK", "tcp://127.0.0.1:" + port, ...);
reg.register(adapter);
```

**It does not read `device_instance.driver_id`**. So:

- With only one driver endpoint it works fine
- With 13 endpoints, **there is no way to route a device to the right endpoint**

This is the **first hurdle** on the way to the site.

---

## 5. The execution order for going on site

```
Day 1  Change the 5 endpoints in appsettings.json → start the Sidecar
        ↓
Day 2  Shadow probe device by device, verify the contract (13 endpoints × 1-2 devices sampled per endpoint)
        ↓
Day 3  Fill in the device_instance.driver_id relations (write SQL or build an interface)
        ↓
Day 4  Run one real process (pick one with few nodes, e.g. the 2-node 239)
        ↓
Day 5  Run a parallel workflow (pick one of the 193), confirm that both branches execute
```

---

## 6. The summary in one sentence

> **The device IP is only an identifier and a troubleshooting aid; the real communication
> goes through `driver.endpoint`.**
> **The biggest gap right now is that the devices are not linked to the driver endpoints
> (`driver_id` is entirely empty): with a single driver it runs, with several drivers it
> connects to the wrong one.**

---

## Appendix: common queries

```sql
-- All the devices on a given island (with the IP and the driver endpoint)
SELECT i.name AS 岛屿, d.code AS 设备, d.name AS 名称,
       di.ip, dr.endpoint AS 驱动端点
FROM resource_node i
JOIN resource_node d ON d.parent_id = i.id AND d.kind = 'device'
LEFT JOIN device_instance di ON di.resource_id = d.id
LEFT JOIN driver dr ON dr.id = di.driver_id
WHERE i.kind = 'island'
ORDER BY i.code, d.code;

-- Which devices have no IP configured
SELECT code, name FROM device_instance WHERE ip IS NULL OR ip = '' ORDER BY code;

-- Which devices are not linked to a driver
SELECT count(*) FROM device_instance WHERE driver_id IS NULL;
```
