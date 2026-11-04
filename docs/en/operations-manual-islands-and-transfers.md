# LabOS v0.1 · Operations Manual (Islands and Transfers)

> **English edition.** The Chinese original is [`docs/操作手册-岛屿与搬运.md`](../操作手册-岛屿与搬运.md);
> this is a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

| Item | Value |
|---|---|
| Version | v0.1 |
| Date | 2026-11-01 |
| Applies to | On-site implementation, demonstrations, acceptance |

---

## 0. Up and running in five minutes

### Starting up (three terminals)

```bash
# ① Mock driver (optional; without it the built-in virtual driver is used)
cd labos-kernel/sidecar-dotnet
export DOTNET_ROOT=/opt/homebrew/opt/dotnet/libexec
export PATH=/opt/homebrew/opt/dotnet/bin:$PATH
dotnet run -c Release -- --mock

# ② Kernel
cd labos-kernel
java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.api.ApiServer 18080

# ③ Frontend
cd webapp
npm run dev
```

### Opening it

```
http://<local IP>:5173
Sign in: admin / admin123
```

> On the first start the administrator is created automatically; the password can be
> overridden with `LABOS_ADMIN_PASS`.
> **The password must be changed before going live.**

---

## 1. Create an island

**Operations → Island View → [ New island ]**

| Field | Explanation | Example |
|---|---|---|
| Island code | Used in the resource-tree path, English / digits | `LAB-A` |
| Island name | For display | `Lab A` |

After creation the dropdown switches to the new island automatically.

---

## 2. Add devices to the pool

**"Device pool" on the left → [ Add device ]**

| Field | Explanation | Example |
|---|---|---|
| Device code | Unique | `X900` |
| Device name | For display | `Incubator_37C-1` |
| Capability | Decides which workflow nodes it can take part in | `INCUBATE` |
| Device type | Classification | `Incubators` |
| Device IP | Ownership and troubleshooting | `192.0.2.10` |

> **Three things are created at once**: the resource-tree node (where it is) + the model
> (what it is) + the instance (what it connects to).
> One transaction; if it fails everything is rolled back, so no half-written data is left.

---

## 3. Drag the devices onto the island

**Drag from the pool on the left onto the canvas on the right** (release inside the dashed
box).

The count goes up by 1 immediately after the drop.

**Changing islands**: drag the device back into the pool, then drag it onto another island.

---

## 4. Place the positions

Devices inside an island **can be dragged directly** to any position; the position is saved
automatically on release.

```
The coordinates are stored in resource_node.attr.pos_x / pos_y
Changing the driver or the IP does not affect the position
```

Arrange it as the real physical layout (the robot arm in the middle, the incubators on
the left, the plate readers on the right).

---

## 5. Author a transfer sequence

### 5.1 Adding to the sequence

Click "**Transfer sequence**" under the device card.

The following appears at the top:

```
Transfer sequence: [1. ID1731 ×] [Transfer ▾]  [2. ID1183 ×] [Deposit ▾]  [3. ID1775 ×] [Deposit1Ex ▾]
                                                   [Run it (dispatch stop by stop)] [Save as workflow and open the designer] [Clear]
```

**The method of each stop can be changed** — the system recommends one by capability
(`TRANSFER` → `Transfer`, `INCUBATE` → `Deposit`).

### 5.2 Running it once (verifying the logistics)

Click "**Run it (dispatch stop by stop)**":

```
① The plate slides to ID1731 → dispatch Transfer  → OK 62ms
② The plate slides to ID1183 → dispatch Deposit   → OK 71ms
③ The plate slides to ID1775 → dispatch Deposit1Ex → OK 62ms
```

**Every stop is a real call into the driver**, and the raw device response is returned.

> This step writes nothing to the database and creates no run — it is an **on-site
> debugging tool**,
> so wire it up once after installing the devices and watch whether the logistics work.

---

## 6. Save it as a real workflow

Click "**Save as workflow and open the designer**" → once the workflow has been generated it
jumps into the designer automatically:

```
Workflow #1249 generated (3 stops), opening the designer…
→ after 400ms it jumps to /designer/1249
```

No need to go back to the workflow library to look for it — as soon as it is saved you can
fine-tune the nodes, change the edges and set the parameters.

The stored structure:

```
Node                                          Capability   Method           Bound device
N1  Inheco_Shaking_Incubation / ActionDrawer  INCUBATE    ActionDrawer   200171
N2  XRobot A10LR / BarcodeScan                TRANSFER    BarcodeScan    200214
N3  Incubator_4C Storage / Deposit1Ex         INCUBATE    Deposit1Ex     200210

Edges: S→N1→N2→N3→E
```

**Key**: the node carries `bound_resource_id` — at execution time this device must be
used,
it is not picked at random by capability as in an ordinary workflow.

---

## 7. Execution

**Authoring → Workflows → find the workflow that was just generated → [Execute]**

```
Completed 3/3
```

The run detail shows the device, the duration and the raw device response for every step.

---

## 8. Shadow check (verifying the driver contract)

**Workflows → select a workflow → [Shadow check]**

```
Shadow check · Transfer from ID1183 to ID1775

Nodes 3    Passed 3    Failed 0    Contract gaps 0

Node                        Capability   Method          Device    Result       Contract
Inheco_Shaking...           INCUBATE    ActionDrawer  ID1183   OK 86ms     Complete
XRobot A10LR...             TRANSFER    BarcodeScan   ID1731   OK 62ms     Complete
Incubator_4C...             INCUBATE    Deposit1Ex    ID1775   OK 62ms     Complete
```

**The difference**:

| | Normal execution | Shadow check |
|---|---|---|
| Triggers the recovery policies | ✅ | ❌ |
| Writes run / run_node | ✅ | ❌ |
| A node fails | stop / retry / switch device | **it carries on with the next one** |
| Purpose | Complete the production | **cover every node and verify the contract** |

---

## 9. Audit

**System → Audit Log**

```
Time                User    Action              Target            Result
2026-11-01 18:20   admin  choreography.build  workflow #1249  ok
2026-11-01 18:18   admin  workflow.shadow     workflow #1249  ok
2026-11-01 18:10   admin  run.start           workflow #1249  ok
2026-11-01 18:05   admin  device.create       device          ok
2026-11-01 18:00   admin  auth.login          user #1         ok
```

**Failed logins are recorded too** (`auth.login fail`).

---

## 10. On-site implementation checklist

```bash
# 1. Change the driver endpoints
vi labos-kernel/sidecar-dotnet/appsettings.json   # 5 tcp://192.0.2.x:8501

# 2. Start the Sidecar (without --mock)
dotnet run -- --mock=false

# 3. Single-command probe, device by device
TOKEN=$(curl -s -X POST http://localhost:18080/api/auth/login \
  -d '{"username":"admin","password":"admin123"}' | jq -r .token)

curl -X POST "http://localhost:18080/api/shadow/probe?resourceId=200171&method=OpenDoor" \
  -H "Authorization: Bearer $TOKEN"

# 4. All 4 contract fields must be true for it to count as working
#    messageId / messageType / method / methodStatus

# 5. Shadow check of the whole workflow (the button on the workflow library page)
```

---

## Appendix: frequently asked questions

| Symptom | Cause | What to do |
|---|---|---|
| The page is blank, with no error | The token has expired | It jumps to the login page by itself; if it does not, refresh the page |
| `EC-06-0001 设备未注册` | The device id conventions do not match | Check `device_instance.resource_id` |
| The shadow check uses the wrong device | The node has no device bound | Only a workflow generated from a transfer sequence has `bound_resource_id` |
| The drag is not saved | The button was not released, or the network dropped | After releasing, check whether the backend `attr.pos_x` changed |
| The transfer animation does not move | Both nodes are the same device | Normal — the plate did not go anywhere |
