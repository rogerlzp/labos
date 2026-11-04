# On-Site Deployment Manual

> **English edition.** The Chinese original is [`docs/现场部署手册.md`](../现场部署手册.md); this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they ever disagree, the Chinese original wins.

> For field engineers. Do the steps in order; every step has an explicit "pass criteria".
> **If a step does not pass, do not go on to the next one.**

---

## 0. Before going on site — confirm these first

| Item | How to confirm |
|---|---|
| The laboratory network is reachable | `ping <驱动服务IP>` |
| The driver service is running | `nc -z <IP> 8501` or `telnet <IP> 8501` |
| The database is reachable | `psql -h <DB_IP> -U <用户> -d labdb -c "select 1"` |
| The clock on the control console machine is accurate | `date` (an inaccurate clock causes wrong timeout decisions) |
| There is a rollback plan | The old system is still running and the data is backed up |

---

## 1. Deploy the control console

### 1.1 Upload the code

```bash
# on the target machine
mkdir -p /opt/labos && cd /opt/labos
# transfer the repository up with scp / rsync
```

### 1.2 Create the database

```bash
cd /opt/labos/pg_rebuild

export HOST=<DB_IP> PORT=5432 DB=labos
export USER_DB=<DB用户> PASS_DB=<DB密码>

./schema/run_schema.sh          # create 98 tables
psql ... -f schema/99_verify.sql # verify
psql ... -f schema/95_seed_data.sql  # optional: seed data
```

**Pass criteria**: `99_verify.sql` is all green and `select count(*) from resource_node`
returns rows.

### 1.3 Start the kernel

```bash
cd /opt/labos/labos-kernel
javac -encoding UTF-8 -d out $(find src -name '*.java')

LABOS_DB_HOST=<DB_IP> \
LABOS_DB_NAME=labdb \
LABOS_DB_USER=<用户> \
LABOS_DB_PASS=<密码> \
nohup java -Dfile.encoding=UTF-8 \
     -cp "out:lib/postgresql-42.7.3.jar" \
     com.labos.kernel.api.ApiServer 18080 >> /opt/labos/api.log 2>&1 &
```

**Pass criteria**:

```
[调度] 已启动（岛级互斥，最多 4 个实验并行）
```

```bash
curl -X POST http://localhost:18080/api/auth/login \
     -d '{"username":"admin","password":"admin123"}'
# a returned token means success
```

### 1.4 Start the frontend

```bash
cd /opt/labos/webapp
npm install && npm run build
# serve the static files with nginx or npm run preview
```

---

## 2. Connect the devices (core)

### 2.1 Change the driver endpoint configuration

The `appsettings.json` of the driver service (.NET Sidecar):

```json
{
  "EquipmentDriver": {
    "Servers": [
      { "endpoint": "tcp://<设备服务IP>:8501" }
    ]
  }
}
```

**Note**: one endpoint manages **multiple devices**. If the laboratory has several driver
services, configure one line each.

### 2.2 Register the endpoint in the database

```sql
-- list the existing endpoints
SELECT id, code, endpoint FROM driver;

-- add / modify (one endpoint corresponds to one driver service)
UPDATE driver SET endpoint = 'tcp://<IP>:8501' WHERE code = 'DRV-xxx';
```

### 2.3 Confirm device ownership

```sql
-- every device must be attached to a driver, otherwise EC-06-0001 is raised
SELECT r.code AS 设备, d.code AS 驱动, d.endpoint
FROM device_instance di
JOIN resource_node r ON r.id = di.resource_id
LEFT JOIN driver d ON d.id = di.driver_id
WHERE di.deleted_at IS NULL;
```

**Any row whose `驱动` or `endpoint` is empty must be filled in first**.

---

## 3. Verification (in order, no skipping)

### Step 1: single-command probe (the safest)

**Pick `Home` (return to home position)** — it does not touch plates and does not hit
anything.

```bash
curl -X POST "http://localhost:18080/api/shadow/probe?resourceId=<设备resource_id>&method=Home"
```

**Pass criteria**:

```json
{
  "ok": true,
  "elapsedMs": 87,
  "contract": {
    "messageId": true,
    "messageType": true,
    "method": true,
    "methodStatus": true
  }
}
```

**Only when all four contract fields are true does it count as working**. If one field is
false, look at what the actual field names are in `rawResponse`.

### Step 2: the key methods of this device

Try them in order: `Home` → `GetStatus` (if there is one) → the real action (`Deposit1`
and so on).

**Record `elapsedMs` for every method you try** — this is the real duration, and you need
it later when setting the timeout.

### Step 3: whole-workflow shadow

```bash
curl -X POST "http://localhost:18080/api/workflows/<流程id>/shadow-run"
```

**Sends no commands**, it only returns "what would be sent".

**Pass criteria**:
- Every node lands on the **right device**
- The method name is **right** (`Deposit1`, not `Deposit1Ex`)
- After the parameters are substituted the **values are sensible**

### Step 4: a real run of one workflow

```bash
curl -X POST "http://localhost:18080/api/workflows/<流程id>/run?islandId=<岛id>"
```

**Pass criteria**:

```sql
SELECT command_seq, method, device_id, status, error_code,
       (result->>'durationMs')::int AS 耗时ms
FROM run_command WHERE run_id = <上一步返回的 runId> ORDER BY command_seq;
```

- Every step has `status = completed`
- **`耗时ms` is not 0** (if it is 0, the system was not really waiting for the device)

### Step 5: repeat 20 times

**Passing once does not mean it is stable**. Run it 20 times and see whether any sporadic
failures occur.

```bash
for i in $(seq 1 20); do
  curl -s -X POST ".../api/workflows/<id>/run?islandId=<id>" \
       -H "Authorization: Bearer $T" -o /dev/null -w "%{http_code} "
done
```

---

## 4. Common error codes

| Error code | Meaning | How to check |
|---|---|---|
| `EC-06-0001` | The device is not registered to a driver endpoint | Check `device_instance.driver_id` |
| `EC-05-1006` | TCP communication failed | Check the network, the port, and whether the driver is alive |
| `EC-05-1007` | The command timed out | Check `elapsedMs` and whether the device is really running |
| `EC-05-0001` | The device returned a failure | Look at `error` in `device_raw_response` |
| `EC-99-0002` | The driver threw an exception | Look at the Sidecar log |
| `EC-PLATE-PRECHECK` | The source device has no plate before the transport | Check `device_state.has_plate` |
| One contract field is false | The returned field name is wrong | Look at the actual fields in `rawResponse` |

---

## 5. How to localise a problem (three steps)

### Step 1: look at the commands

```sql
SELECT command_seq, method, device_id, payload,
       status, error_code, device_raw_response
FROM run_command WHERE run_id = ? ORDER BY command_seq;
```

**This single table answers 80% of the questions**: what was sent, to whom, and what the
device replied.

### Step 2: look at the nodes

```sql
SELECT name, status, resource_id FROM run_node
WHERE run_id = ? ORDER BY id;
```

**See which step failed and which device was used**.

### Step 3: look at the device state

```sql
SELECT r.code, ds.status, ds.has_plate, ds.current_run_id
FROM device_state ds JOIN resource_node r ON r.id = ds.resource_id
WHERE ds.tenant_id = 1;
```

**Where the plate is, and what the device is busy with**.

---

## 6. Rollback plan

**When something goes wrong**:

```bash
# 1. stop the control console
pkill -f ApiServer

# 2. clear the devices by hand (take the plates out, home the robot arm)
#    this step must be done by hand — the system does not know the actual state on site

# 3. switch back to the old system (if it is still running)
# 4. notify the duty officer
```

**Never**: the control console and the old system operating on the same set of devices at
the same time — both sides think the plate is in their own hands, and they will collide.

---

## 7. Routine operations

| Item | Command |
|---|---|
| Check whether the service is alive | `curl http://localhost:18080/health` |
| Check the scheduler status | `curl .../api/scheduler/status` |
| Check the log | `tail -100 /opt/labos/api.log` |
| Check the alerts | the "alerts" page in the UI |
| Restart | `pkill -f ApiServer`, then start it as in 1.3 |

### Do not overwrite the log with `>`

```bash
# wrong: every restart clears the history, so you cannot investigate a problem
nohup java ... > api.log 2>&1 &

# right: append
nohup java ... >> api.log 2>&1 &
```

**This one has bitten us before**: when troubleshooting a problem across a restart, the
history log had been wiped and the error lines were gone.

---

## 8. Go-live checklist

```
[ ] Database created, 99_verify all green
[ ] Kernel started, login returns a token
[ ] Frontend opens, islands and devices are visible
[ ] Every device has a non-empty driver_id
[ ] Single-command probe Home passes (all four contract fields true)
[ ] The key methods of this device have all been tried, and the durations recorded
[ ] Whole-workflow shadow: device allocation, method names and parameters all sensible
[ ] A real run of one workflow passes, run_command contains the real raw response and the duration
[ ] The same workflow ran 20 times, with no sporadic failures
[ ] The duty officer has been trained and knows how to read the alerts and how to stop
[ ] The old system is stopped, the devices have been cleared by hand
[ ] The rollback plan has been rehearsed
```
