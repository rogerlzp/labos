# End-to-End Verification Report (Simulator Version)

> **English edition.** The Chinese original is [`docs/全链路验证报告-模拟器.md`](../全链路验证报告-模拟器.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> It replaces the real devices with 7 independent device simulators and runs the complete
> **P0 eight-step verification** that has to be done on site.
> One-command execution: `./scripts/verify-all.sh LAB-DEMO-02`

---

## 1. The conclusion

```
passed 13 / failed 0 / skipped 0
```

**The P0 verification procedure can already run through completely on this machine**, what is left is only the physical constraints and the edge cases of the vendor DLLs.

---

## 2. The result of every item

| # | Item | Result | Explanation |
|---|---|---|---|
| 0 | Service and simulators | ✅ | The control console is at 18080, 7 simulators are running, the login succeeded |
| 1 | Driver registration | ✅ | 7 simulated endpoints, one per device |
| 2 | Heartbeat liveness | ✅ | 7/7 online |
| 3 | Probe, device by device | ✅ | All 7 passed, the four contract fields are all true, 152-166ms |
| 4 | Whole-workflow shadow | ✅ | 1 node, the device assignment is correct |
| 5 | Single-workflow real run | ✅ | 2/2 completed, every command has a real duration |
| 6 | Repeated 20 times | ✅ | All 20 succeeded, no sporadic failures |
| 7 | The running state | ✅ | After the execution ended the number of running is 0 (it falls back correctly) |
| 8 | Offline alert | ✅ | 0 not closed, 170 closed, no misdirected error |

### The probe details

```
ID0000           ok  155ms  the four contract fields pass
ID1183           ok  165ms  the four contract fields pass
ID1731           ok  159ms  the four contract fields pass
ID1731-2         ok  155ms  the four contract fields pass
ID1775           ok  161ms  the four contract fields pass
Incubator_2C-1   ok  160ms  the four contract fields pass
Incubator_2C-2   ok  166ms  the four contract fields pass
```

**The four contract fields**: `messageId` / `messageType` / `method` / `methodStatus`.
Missing any one of them means the protocol does not work — this is the first gate on site.

### The real-run details

```
runId=205049
completed 2/2
every run_command has a durationMs > 0 (it means it was really sent to the device, not spinning in the air)
```

---

## 3. The real problems that were fixed along the way

Running the verification script itself also exposed problems, and all of them were fixed:

### 1. 3 devices did not declare any capability (missing data)

```
ID1731-2        XRobot A10LR-PreNGS (2)   capability count 0
Incubator_2C-1  Incubator_2C #1           capability count 0
Incubator_2C-2  Incubator_2C #2           capability count 0
```

**The consequence**: the driver registration was skipped (`DriverDescriptor` requires at least one capability),
those 3 devices were **completely unusable** — the probe failed right away.

**The fix**: add the missing `resource_capability`

```sql
INSERT INTO resource_capability (resource_id, capability_id)
SELECT r.id, c.id FROM resource_node r, capability c
WHERE (r.code = 'ID1731-2' AND c.code = 'TRANSFER')
   OR (r.code LIKE 'Incubator_2C-%' AND c.code = 'INCUBATE')
ON CONFLICT DO NOTHING;
```

**This kind of problem is invisible on the interface** — the device card is displayed as usual, it just never does any work.

### 2. `resource_capability` has no `tenant_id`

When writing the INSERT, `tenant_id` was added out of habit, and it reported an error:

```
column "tenant_id" of relation "resource_capability" does not exist
```

This table is a **many-to-many between devices and capabilities**, the tenant is already implied by `resource_id`.

### 3. The `workflow` table uses `graph`, not `graph_xml`

The script used the column name from memory, in reality it is `graph`.

### 4. `shadow-run` only accepts POST

The script called it with GET and got `{"error":"method not allowed: GET"}` back.

### 5. The full-width colon after `$VAR：` in the script was taken for the variable name

```bash
echo "当前 running 设备数：$RUNNING："   # bash treats "RUNNING：" as the variable name
# → line 229: RUNNING：: unbound variable
```

**The fix**: change everything to `${VAR}`.

**This pitfall has appeared for the 3rd time in this project** (when writing a shell script, a Chinese punctuation mark directly follows the variable name).

### 6. Appending the log with `>>` led to a misjudgement

To look at the driver registration log, `tail /tmp/hb.log` was used — **what was seen was the log of the previous start**,
so it was wrongly assumed that "even after the capability was added it is still skipped".

**The lesson**: when debugging a start-up problem, **restart with a new log file name**, do not `tail` a file that is appended to.

---

## 4. What the simulator can verify / cannot verify

### It can verify (all of it is covered in this round)

| Item | Means |
|---|---|
| Routing (the command goes to the right device) | 7 independent endpoints, a mistake makes the connection fail |
| The protocol contract | The four `contract` fields of the probe |
| Device assignment (by capability) | The `device_id` of the workflow execution |
| A long task is not misjudged | `--long` (it answers "running" first and "completed" afterwards) |
| The timeout setting | `--delay 5000` |
| The retry logic | `--fail 3` |
| The handling of a device going offline | `--offline` / kill |
| The three-state reporting | Query `device_state.status` during the execution |
| The offline alert | Kill one, after 40 seconds only that one is reported |
| The heartbeat | `/api/heartbeat` |
| The stability | Repeated 20 times |

### It cannot verify

| Item | Why |
|---|---|
| **The real duration** | The `--delay` of the simulator is a fixed value, a real device fluctuates |
| **The physical constraints** | Whether it collides, whether the fixture can hold the plate |
| **The vendor DLL boundary** | The state machine and the exception paths inside the driver |
| **Concurrent interference** | The electromagnetic/mechanical interference when several devices act at the same time |
| **Long-term stability** | The simulator is fine for 20 runs, a real machine running for 20 days is another matter |

---

## 5. How to use this report on site

**What this report proves is "the logic is right"**, not "it can go into production".

On site, follow `docs/现场部署手册.md`, **the order must not change**:

```
① Change the endpoint configuration
② Start the Sidecar
③ Probe Home (homing, it does not touch the plate)
④ Only continue when the four contract fields are all true
⑤ Try the key methods one by one, record the real durations
⑥ Whole-workflow shadow
⑦ Single-workflow real run
⑧ Repeated 20 times
```

**The difference against this report**:

| Step | Simulator | Real device |
|---|---|---|
| ③ Probe | 152-166ms | It can be a few hundred milliseconds up to a few seconds |
| ⑤ Methods | All of them return a fixed value | The parameter name of some method may be wrong |
| ⑦ Real run | All 20 pass | **It may fail on the very first run**, and it has to be fixed |

**The "fix" of step ⑦ is the most time-consuming** — estimated at 3-5 days, depending on how many problems there are.

---

## 6. How to reproduce it

```bash
# 1. Start the simulators of the whole island
cd labos-kernel
export LABOS_DB_HOST=<db address> LABOS_DB_NAME=<db name>
export LABOS_DB_USER=<user> LABOS_DB_PASS=<password>
./scripts/sim-up.sh LAB-DEMO-02 150

# 2. Start the control console
nohup java -cp "out:lib/postgresql-42.7.3.jar" \
     com.labos.kernel.api.ApiServer 18080 >> /tmp/api.log 2>&1 &

# 3. Run the verification
cd ..
./scripts/verify-all.sh LAB-DEMO-02
```

**The report is also written to `/tmp/verify-report.txt`**, it can be archived.
