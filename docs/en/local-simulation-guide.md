# Local Simulation Guide

> **English edition.** The Chinese original is [`docs/本机仿真指南.md`](../本机仿真指南.md); this is a
> line-for-line translation. Numbers, commit hashes and file paths are identical to the
> Chinese source — if they ever disagree, the Chinese original wins.

> **One simulator process per device**; the control console connects to them exactly as
> it would to real devices. It is not "one mock for all devices" but "N independent
> devices running at the same time".

---

## 1. Why one simulator per device

| One mock for all devices | One simulator per device |
|---|---|
| Every device responds at the same speed | **A single one can be set to slow / faulty** |
| Concurrency cannot be measured | Genuinely concurrent (each socket blocks nobody) |
| Killing the mock stops the whole line | **A single one can be killed, to verify the device going offline** |
| A command for device A may reach B's endpoint | The endpoints are isolated, a wrong route shows up immediately |

**The last one is the most important**: if all devices share one endpoint, the control
console can send A's command to B without any error. With one endpoint per device, **a
wrong route simply cannot connect**.

---

## 2. The cross-platform approach

**Write it in Java**, one class runs on all three platforms:

```bash
# macOS / Linux
java -cp out com.labos.kernel.sim.DeviceSimulator --port 19501 --device 171 --code ID1183

# Windows
java -cp out com.labos.kernel.sim.DeviceSimulator --port 19501 --device 171 --code ID1183
```

**There is no need to package for mac/windows separately**. The JVM solves the
cross-platform problem.

**If you want a "double-click to run" version**:

```bash
# Package it as an executable jar
jar --create --file device-sim.jar --main-class com.labos.kernel.sim.DeviceSimulator -C out .

# Then
java -jar device-sim.jar --port 19501 --device 171
```

Put a `.bat` wrapper on Windows:

```bat
@echo off
java -jar device-sim.jar --port 19501 --device 171 --code ID1183
pause
```

---

## 3. Quick start

### 3.1 Bring up a whole island with one command

```bash
cd labos-kernel

export LABOS_DB_HOST=<database address>
export LABOS_DB_NAME=<database name>
export LABOS_DB_USER=<user>
export LABOS_DB_PASS=<password>

./scripts/sim-up.sh LAB-DEMO-02 150
```

**`150` is the simulated duration per device (in milliseconds)**. Setting it to 3000
simulates a slow device.

**Output**:

```
=== 岛 LAB-DEMO-02：查设备 ===
=== 为每台设备分配端口并登记 driver ===
  ID0000           设备实例=206    端口=19500  能力=ALIQUOT
  ID1183           设备实例=171    端口=19501  能力=INCUBATE
  ID1731           设备实例=214    端口=19502  能力=TRANSFER
  ID1775           设备实例=210    端口=19504  能力=INCUBATE
  ...

=== 已启动 7 个模拟器 ===
```

**The script does four things**:

1. Looks up the devices on the island (`resource_node` + `device_instance`)
2. Assigns one port per device (starting at 19500)
3. **Creates a row in the `driver` table** (`tcp://127.0.0.1:<port>`)
4. **Points `device_instance.driver_id` at it**
5. Starts the simulator processes

### 3.2 Start the control console

```bash
LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer 18080
```

**The startup log lists the registered drivers**:

```
[驱动] DRV-SIM-ID1183 → tcp://127.0.0.1:19501（1 台设备）
[驱动] DRV-SIM-ID1775 → tcp://127.0.0.1:19504（1 台设备）
```

### 3.3 Verify

```bash
# Single-command probe
curl -X POST "http://localhost:18080/api/shadow/probe?resourceId=200171&method=Home"

# Run one workflow
curl -X POST "http://localhost:18080/api/workflows/239/run?islandId=504821"
```

**See what each simulator received**:

```bash
tail -f /tmp/sim-ID1183.log
# [ID1183] #1 Deposit1（模拟耗时 150ms）
```

### 3.4 Stop

```bash
./scripts/sim-down.sh
```

It stops all the simulators and detaches the devices from the simulation driver.

---

## 4. The parameters of a single simulator

```bash
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19501 \
     --device 171 \
     --code ID1183 \
     --cap INCUBATE \
     --delay 150
```

| Parameter | Explanation |
|---|---|
| `--port` | The port to listen on (**required**) |
| `--device` | The device-instance id, the `equipment_id` in the reply |
| `--code` | The device number, for the log only |
| `--cap` | The capability, for the log only |
| `--delay` | The duration of each action (milliseconds), default 0 |
| `--long` | **Long-task mode**: reply "running" first, then reply "completed" after half the time |
| `--fail N` | Fail once every N **action-type** commands (0 = never fail; query-type commands do not count) |
| `--fail-code` | The error code returned on failure, default `EC-05-0001`; a code registered in the model library carries its Chinese meaning and a disposition recommendation |
| `--fail-method` | Only fail on the named method (e.g. `Seal`) |
| `--fail-from N` | Only start failing from the Nth action-type command (the first N-1 are normal, to test "it broke halfway through") |
| `--model` | Simulate a **concrete model** (`--list-models` shows which), which enables the real command names, error codes and timing constraints |
| `--offline` | **Do not listen on the port at all**, simulating a device that is not powered on |

---

## 5. What the simulator lets you verify

### 5.1 A slow device (`--delay`)

```bash
# Make ID1183 take 5 seconds per step
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19501 --device 171 --code ID1183 --delay 5000
```

**Verifies**: whether the control console's timeout setting is sufficient, and whether
the workflow gets stuck because of it.

### 5.2 A long task (`--long`)

```bash
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19501 --device 171 --code ID1183 --delay 4000 --long
```

**Behaviour**: it first replies `method_status=1` (running), and 2 seconds later `2` (completed).

**Verifies**: whether the driver mistakes "running" for a failure. **This is a gate that
has to be passed before going onto a real machine.**

### 5.3 Intermittent faults (`--fail`)

```bash
# Fail once every 3 action-type commands
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19501 --device 171 --code ID1183 --fail 3

# Fail with a real-machine error code: the S100 out of paper (the control console translates it into EC-08-300C → hand over to an operator)
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19501 --device 171 --code ID1183 --model s100 \
     --fail 1 --fail-method Seal --fail-code 0x8000000C --fail-from 6
```

**Verifies**: the retry logic of the state machine, and whether `recovery_policy` takes effect as
expected.

> **The N of `--fail N` only counts action-type commands** (`Transfer`/`Seal`/`Start`…), query-type
> commands (`GetStatus`/`GetPoints`) are not counted. The reason is that the control console
> frequently interleaves queries, and counting "the Nth one received" would let the
> fault land on a query and be skipped, which makes `--fail 3` unpredictable.

### 5.4 A device that is offline (`--offline`, or simply killing it)

```bash
# Way one: it is offline from the start
java ... --offline

# Way two: kill it while it is running
pkill -f "code ID1183"
```

**Verifies**: whether `EC-05-1006` (TCP communication failure) is reported correctly and whether
the alert is triggered.

### 5.5 Routing correctness (the most critical one)

```bash
# Run a workflow that involves two devices
curl -X POST ".../api/workflows/239/run?islandId=..."

# See what each of the two simulators received
tail -3 /tmp/sim-ID1183.log
tail -3 /tmp/sim-ID1775.log
```

**Pass criterion**:

```
sim-ID1183.log: [ID1183] #1 Deposit1
sim-ID1775.log: [ID1775] #1 Withdraw1
```

**Each received one command, and it is its own command** — that means the routing is
correct.

If you find a command in the `ID1183` log that should have gone to `ID1775`, then **the device
assignment is wrong**.

---

## 6. Common questions

### Q: The startup reports "the devices under it do not declare any capability"

```
[驱动] DRV-SIM-ID1731-2 跳过：名下设备都没有声明能力
```

**Cause**: this device has no row in `resource_capability`.

**Fix**:

```sql
INSERT INTO resource_capability (tenant_id, resource_id, capability_id)
SELECT 1, r.id, c.id
FROM resource_node r, capability c
WHERE r.tenant_id = 1 AND r.code = 'ID1731-2' AND c.code = 'TRANSFER'
ON CONFLICT DO NOTHING;
```

### Q: An execution reports `EC-06-0001` (the device was not registered with a driver)

**Check**:

```sql
SELECT r.code, d.code AS driver, d.endpoint
FROM device_instance di
JOIN resource_node r ON r.id = di.resource_id
LEFT JOIN driver d ON d.id = di.driver_id
WHERE r.code = '<device number>';
```

**`driver` is empty** → run `sim-up.sh` once more to register it.

### Q: The simulator came up but the control console cannot connect

**Check the port**:

```bash
lsof -i :19501
```

**No output** → the simulator did not come up; look at `/tmp/sim-<number>.log` (`/tmp/sim-<编号>.log`).

**There is output but it is not your process** → the port is taken; use another one
(change `BASE_PORT`).

### Q: I want to simulate on two islands at the same time

```bash
# Island A uses the 19500 range
./scripts/sim-up.sh LAB-DEMO-02 150

# Island B needs BASE_PORT changed, or start it by hand
```

**The current script handles only one island at a time**. For several islands, change
`BASE_PORT` or start the second group by hand.

---

## 7. The differences from the real environment

**What the simulator can verify**:

- Routing (the command reaches the right device)
- Timeouts (`--delay`)
- Long tasks (`--long`)
- Retries (`--fail`)
- Offline handling (`--offline`)

**What it cannot verify**:

- The real duration (the simulator's delay is fixed, a real device fluctuates)
- Physical constraints (will it collide, will the gripper hold)
- Edge cases of the vendor DLL
- Mechanical interference during concurrency

**So**: passing the simulator = **the logic and the protocol are right**; it does not
mean it can go into production.

---

## 8. Design points (for whoever changes the code)

### 8.1 Why the simulator is a separate process and not a thread

- **Port isolation**: one port per device, a wrong route shows up immediately
- **It can be killed individually**: to verify a device going offline
- **Resource isolation**: one simulator crashing does not affect the others

### 8.2 Why a `driver` row is created in the database

The control console's `buildDriverRegistry()` reads the `driver` table:

```sql
SELECT d.id, d.code, d.endpoint, di.id AS instance_id, c.code AS cap
FROM driver d
JOIN device_instance di ON di.driver_id = d.id
LEFT JOIN resource_capability rc ON rc.resource_id = di.resource_id
LEFT JOIN capability c ON c.id = rc.capability_id
WHERE d.tenant_id = 1 AND d.status = 1 AND d.endpoint IS NOT NULL
```

**Every driver only recognises the devices under it** — this is what guarantees that "a
command never reaches the wrong device".

### 8.3 Why one endpoint is not shared

If all devices share one endpoint, the control console can send A's command to B's
simulator, and the simulator may accept everything (because in the protocol `equipment_id` is only
a field). **Then you only find out on the real machine that "the robot arm went to fetch
a plate that does not exist"** — the cost is far too high.

`DeviceSimulator` therefore validates:

```java
if (!deviceId.equals(equip)) {
    // 收到别的设备的命令，明确返回错误而不是假装成功
    return error("EC-06-0001", "设备不属于本模拟器");
}
```

---

## 9. Customising per device type (--profile)

**Different devices have different method sets**; the simulator decides by type "which
methods should exist".

| Type | Methods | Used for |
|---|---|---|
| **robot** | `Home` `Transfer` `GetPosition` `GetPoints` `Grip` `Release` | Robot arm |
| **centrifuge** | `OpenDoor` `CloseDoor` `Load` `Unload` `RunProgram` | Centrifuge |
| **incubator** | `OpenDoor` `CloseDoor` `Deposit1` `Withdraw1` `SetTemp` | Incubator |
| **reader** | `OpenDoor` `CloseDoor` `Read` `GetResult` | Plate reader |
| **generic** | Unlimited (the existing behaviour) | Fallback |

**A wrong method produces an explicit error**:

```
robot     OpenDoor   → 拒绝 EC-06-0004（机械臂没有开门方法）
incubator Transfer   → 拒绝 EC-06-0004（孵箱不能搬板）
```

**Why it is designed this way**: when a device number is misconfigured on site, it has
to **fail immediately**, instead of letting the simulator "pretend to succeed" — on a
real machine that means a collision.

### Usage

```bash
# Robot arm
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19601 --device 214 --code ID1731 --profile robot

# Centrifuge (with the parameters echoed back)
java -cp out com.labos.kernel.sim.DeviceSimulator \
     --port 19603 --device 999 --code CENT-01 --profile centrifuge
```

**`sim-up.sh` picks automatically by capability**:

```
TRANSFER   → robot
INCUBATE   → incubator
CENTRIFUGE → centrifuge
READ_PLATE → reader
其他       → generic
```

### Simulated behaviour of the special methods

| Method | What the simulator does |
|---|---|
| `Open*` / `Close*` | Replies with success directly, returning `{"door":"open"/"closed"}` |
| `GetPoints` / `SyncPoints` | Returns 4 level positions (`<code>-L1..L4`) |
| `RunProgram` / `Centrifuge` | **Echoes back the rpm/minutes that were passed in**, so that it is easy to verify whether the parameters got through |


