# Database Design

> **English edition.** The Chinese original is [`docs/数据库设计说明.md`](../数据库设计说明.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> This only covers **how it is used today**: what each table stores, the key fields, who
> writes and who reads them, and the common queries.
> The schema scripts are in `pg_rebuild/schema/`; this document is their manual.

---

## 1. Overview

The database name defaults to `labos` (environment variable `LABOS_DB_NAME`); there are
**98 tables** in total, grouped into seven groups by responsibility:

| Group | Tables | Explanation |
|---|---|---|
| **Resource model** | `resource_node` `device_instance` `capability` `device_state` `resource_capability` | The island → device → level-position tree, plus the live device state |
| **Workflow** | `workflow` `workflow_node` `workflow_edge` `workflow_node_step` | The BPMN graph definition |
| **Execution** | `run` `run_node` `run_command` `run_event` `run_variable` `run_artifact` | The complete trace of one execution |
| **Experiment** | `experiment` `experiment_process` `experiment_variable` `experiment_graph` `experiment_run` `experiment_run_step` | Multi-workflow orchestration + variables + loops |
| **Plate position** | `plate` `plate_well` `position_state` `robot_point` | Where the plates are, the robot arm positions |
| **Driver** | `driver` `driver_capability` `driver_health` `device_method` `device_log` | The device method dictionary and the communication |
| **Alerts** | `alert_state` | Alert aggregation |

**Partitioned tables**: `device_log` and `run_event` are partitioned by month
(`device_log_202609` and so on); a query can go straight to the parent table.

---

## 2. The resource model

### `resource_node` — the resource tree (core table)

Island → device → level position, with `parent_id` self-referencing into a tree.

| Field | Explanation |
|---|---|
| `id` | **Primary key**. The graph, the execution records and the position state all use it ★ |
| `parent_id` | The parent node; NULL for an island, a device points at the island, a level position points at the device |
| `kind` | Enum: `island` / `device` / `incubator` / `storage` / `transfer_point` |
| `code` | The business code, e.g. `ID1183`, `I4495`, `LAB-DEMO-02`, **this is what the interface displays** |
| `name` | The Chinese name |
| `attr` jsonb | Extension attributes; the canvas coordinates live in `attr->>'pos_x'` / `pos_y` |

**The level convention**:

```
island: an island
 └ device: a device, e.g. the ID1183 incubator
    └ incubator / storage / transfer_point: a level position, e.g. I4495
```

**Common queries**:

```sql
-- All the devices under a given island
SELECT id, code, name FROM resource_node
WHERE parent_id = (SELECT id FROM resource_node WHERE code = 'LAB-DEMO-02')
  AND kind = 'device';

-- The level positions of a given device
SELECT id, code FROM resource_node
WHERE parent_id = (SELECT id FROM resource_node WHERE code = 'ID1183')
  AND kind IN ('incubator','storage','transfer_point');
```

### `device_instance` — the device instance

**One physical device**. The difference from `resource_node`:

| | `resource_node` | `device_instance` |
|---|---|---|
| Semantics | A resource-tree node (including islands and level positions) | Only the "devices that can be dispatched to" |
| Used by | The graph, the execution records, the positions | **The drivers, the probes** ★ |
| Relation | `device_instance.resource_id → resource_node.id` | |

**⚠ This is the easiest pitfall in this project**: both are `long`, mixing them up raises
no error but behaves wrongly.
The upper layers uniformly use `resource_node.id`; **it is only converted into
`device_instance.id` right before calling the driver**.

```sql
-- The conversion (wrapped in instanceIdOf() in the code)
SELECT id FROM device_instance WHERE resource_id = ? AND deleted_at IS NULL;
```

The key fields: `driver_id` (which driver endpoint it goes through), `ip` (ownership
identifier + troubleshooting, **not the communication address**).

### `capability` — the capability dictionary

`INCUBATE` (incubation), `TRANSFER` (transport), `ALIQUOT` (aliquoting) and so on.
A device is linked to its capabilities through `resource_capability`; a workflow node looks
for a device by **capability**, it never hard-codes a code.

### `device_state` — the live device state

| Field | Explanation |
|---|---|
| `status` | `idle` / `running` / `error` |
| `has_plate` | **Derived field**: any level position under that device holds a plate → true |
| `current_run_id` | The run that currently occupies it |

**Do not maintain `has_plate` by hand**; it is derived from `position_state` by
`PositionLock.refreshHasPlate()`.
Writing it in both places makes them inconsistent ("it shows a plate but the level position
is empty", and the plate cannot be picked up).

---

## 3. Plate positions

### `position_state` — the level-position state (the single source of truth for plate positions)

| Field | Explanation |
|---|---|
| `resource_id` | The `resource_node.id` of the level position |
| `plate_id` | The plate ID; NULL/empty = free |
| `reserved_until` | When the reservation expires (held temporarily while a transfer is in progress) |
| `reserved_by_run` | Who made the reservation |
| `occupied_since` | Since when it has held a plate |

**Three states**:

| State | Criterion | Meaning |
|---|---|---|
| Free | `plate_id` is empty and there is no valid reservation | A plate can be put here |
| Reserved | `reserved_until > now()` | A transfer is in progress, tens of seconds |
| Has a plate | `plate_id` is not empty | It is occupied, waiting for the experiment to pick the plate up |

**The reservation has to be atomic** (one statement does the check and the occupation):

```sql
INSERT INTO position_state (tenant_id, resource_id, reserved_until, reserved_by_run, updated_at)
VALUES (?, ?, now() + ?::interval, ?, now())
ON CONFLICT (tenant_id, resource_id) DO UPDATE
SET reserved_until = now() + ?::interval, reserved_by_run = EXCLUDED.reserved_by_run
WHERE (position_state.plate_id IS NULL OR position_state.plate_id = '')
  AND (position_state.reserved_until IS NULL
       OR position_state.reserved_until < now()
       OR position_state.reserved_by_run = EXCLUDED.reserved_by_run);
```

**Why you cannot SELECT first and then UPDATE**: there is a window in between, and two
experiments would both consider the position free.

### `robot_point` — the robot arm positions

| Field | Explanation |
|---|---|
| `robot_resource_id` | Which robot arm (`resource_node.id`) |
| `code` | The position code; this is what `Transfer(source, target)` uses |
| `x` / `y` / `z` | The coordinates obtained by teaching |
| `device_resource_id` | Which device it is linked to (for display) |

**`code` defaults to the device code** (generated per device during the
synchronisation); afterwards it is enough to fill in the coordinates after the teaching.

### `plate` / `plate_well` — the plate records

`plate` stores the plate ID, the type and the current position; `plate_well` stores the
sample in each well of a 96-well plate.

**Note** that `plate` is a **redundant table**: the truth about a plate position is in
`position_state`.
A failure to write `plate` **must not affect** the position lock (that is why it is not in
the position lock's transaction).

---

## 4. Workflows and executions

### `workflow` / `workflow_node` / `workflow_edge`

The BPMN graph definition.

| Table | Key fields |
|---|---|
| `workflow` | `code`, `name`, `graph_xml` (the bpmn-js XML) |
| `workflow_node` | `node_key` (the BPMN element id), `name`, `method`, `params` jsonb |
| `workflow_edge` | `source_key` → `target_key`, `condition` |

What you commonly find in `workflow_node.params`:

```json
{
  "method_params": { "plate_id": "${batch}-${no}" },
  "bound_resource_id": 200171,
  "x": 320, "y": 200
}
```

### `run` / `run_node` / `run_command` — the execution trace (three layers)

```
run: one execution
 └ run_node: one node
    └ run_command: one device command
```

| Table | Key fields | Who writes it |
|---|---|---|
| `run` | `status`(run_status), `mode`, `workflow_id`, `output` | The execution entry point |
| `run_node` | `name`, `status`(node_status), `resource_id`, `level` | The state machine |
| `run_command` | `command_seq`, `method`, `payload`, `device_raw_response`, `device_id`, `attempt` | The driver execution |

**⚠ The two status enums are not the same type**:

- `run.status` is `run_status`
- `run_node.status` / `run_command.status` is `node_status`

Mixing them up makes JDBC report `column status is of type X but expression is of type Y`.

**Three tables are mandatory when you debug a problem**:

```sql
-- ① Which step failed, what was sent, and what the device replied
SELECT command_seq, method, device_id, status, error_code,
       payload, device_raw_response
FROM run_command WHERE run_id = ? ORDER BY command_seq;

-- ② Which device this step used
SELECT name, status, resource_id FROM run_node WHERE run_id = ? ORDER BY id;

-- ③ The overall conclusion
SELECT status, error_code, output FROM run WHERE id = ?;
```

`run_command` is **the most valuable of the three**: it has `payload` (the actual
parameters), `device_raw_response` (the raw driver text) and `device_id` (which device).
When something goes wrong it is more reliable than the log.

---

## 5. Experiments

```
experiment: the experiment definition
 ├ experiment_process: the steps (which workflow, which step, how many loops)
 ├ experiment_variable: the variables (default values)
 ├ experiment_graph: the canvas (jsonb)
 └ experiment_run: one execution
    └ experiment_run_step: every step after expansion, including loop_index
```

### `experiment`

| Field | Explanation |
|---|---|
| `island_id` | Which island it runs on (**the scheduler does the island-level mutual exclusion based on it**) |
| `priority` | Priority, the smaller the more important |
| `deadline` | The deadline (takes precedence over the priority when sorting) |

### `experiment_process`

| Field | Explanation |
|---|---|
| `workflow_id` | Which workflow to use |
| `seq` | Which step |
| `loop_count` | The loop count; empty=1, supports `${var}` |

### `experiment_run` / `experiment_run_step`

| Table | Key fields |
|---|---|
| `experiment_run` | `status`, `variables` jsonb, `expanded_steps`, `current_seq` |
| `experiment_run_step` | `seq`, `loop_index`, `workflow_id`, `run_id` |

`experiment_run_step.run_id` points at `run.id` — **the experiment layer only records
"which run it was"; the details are looked up in `run`**.

---

## 6. The drivers

### `driver` — the driver endpoint

`endpoint` looks like `tcp://host:8501`. **One driver service handles many devices**,
so `device_instance.ip` is not the communication address — `driver.endpoint` is.

### `device_method` — the method dictionary

| Field | Explanation |
|---|---|
| `device_id` / `capability_id` | Which devices / capabilities can call this method |
| `method` | The method name, e.g. `Deposit1`, `Transfer`, `Home` |
| `params` jsonb | The parameter definition (`control_type`, `required`, `options`) |
| `timeout_ms` | The timeout |

After the frontend has picked a method it fetches `GET /api/methods/{method}/params` to
render the form; the data comes from here.

### `device_log` — the communication log (partitioned by month)

The raw bytes sent and received, **the place to look when debugging a protocol problem**.
It is not queried in daily use (the volume is large).

---

## 7. Common queries

### Which positions on a given island currently hold a plate

```sql
SELECT r.code AS 位置, r2.code AS 所属设备, ps.plate_id, ps.occupied_since
FROM position_state ps
JOIN resource_node r  ON r.id = ps.resource_id
LEFT JOIN resource_node r2 ON r2.id = r.parent_id
WHERE r.parent_id IN (
        SELECT id FROM resource_node
        WHERE parent_id = (SELECT id FROM resource_node WHERE code = ?)
      )
  AND ps.plate_id IS NOT NULL;
```

### The live device state

```sql
SELECT r.code AS 设备, ds.status, ds.has_plate, ds.current_run_id
FROM device_state ds JOIN resource_node r ON r.id = ds.resource_id
WHERE ds.tenant_id = 1;
```

### The last few executions of a given experiment

```sql
SELECT id, status, expanded_steps, current_seq, started_at, finished_at
FROM experiment_run WHERE experiment_id = ?
ORDER BY id DESC LIMIT 20;
```

### Which commands an execution issued

```sql
SELECT command_seq, method, device_id, status, error_code,
       (result->>'durationMs')::int AS 耗时ms
FROM run_command WHERE run_id = ? ORDER BY command_seq;
```

**A duration of 0 or a single digit** → it went through the mock / virtual driver, it was
not really waiting for the device.

---

## 8. Design conventions

| Convention | Explanation |
|---|---|
| **Every table has `tenant_id`** | Multi-tenancy; a query must carry it, otherwise the data gets mixed up |
| **`deleted_at` is a soft delete** | A query on a table with that column has to add `deleted_at IS NULL` |
| **Two sets of IDs** | `resource_node.id` in the upper layers, converted to `device_instance.id` before calling the driver |
| **Two status enums** | `run_status` vs `node_status`, do not mix them |
| **`has_plate` is derived** | It is computed from `position_state`, do not write it by hand |
| **`plate` is redundant** | A write failure does not affect the position lock, do not put it in the position lock's transaction |
| **The command line has to be idempotent** | `run_command.attempt` + the same `command_id`, a resend does not execute twice |

---

## 9. Creating the tables and the initial data

```bash
cd pg_rebuild

# 1) Create the tables (pure DDL, no data at all)
psql -h <host> -U <user> -d <db> -f schema/90_all_in_one.sql

# 2) Verify
psql -h <host> -U <user> -d <db> -f schema/99_verify.sql

# 3) Optional: the initial data (tenant / dictionaries / accounts)
psql -h <host> -U <user> -d <db> -f schema/95_seed_data.sql
```

**Creating the tables and the data are separate**:

| File | Content | Required |
|---|---|---|
| `90_all_in_one.sql` | The DDL for 74 tables | Required |
| `95_seed_data.sql` | Tenant / capability dictionary / error codes / initial accounts | **Optional**, but the tenant is required |

Everything in `95_seed_data.sql` is `ON CONFLICT DO NOTHING`,
so it **deletes nothing and changes nothing** that already exists, and it can be executed
repeatedly safely.

`schema/merge.sh` merges the `10/20/30/40` shards into `90_all_in_one.sql` (without the
seed).
