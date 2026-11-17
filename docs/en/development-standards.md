# Development Standards (branching · code · database · environment)

> **English edition.** The Chinese original is [`docs/开发规范.md`](../开发规范.md);
> this is a condensed translation. If the two ever disagree, the Chinese original wins.
>
> This file only covers what the other standards do **not** cover. For those, read:

| Looking for | Read |
|---|---|
| Commit messages, when to commit, what not to commit | [contribution-guidelines.md](contribution-guidelines.md) |
| Which tests to run, gate criteria, test-to-module map | `docs/测试总纲.md` / `docs/测试规范.md` |
| Review material, severity levels, point-by-point replies | `docs/评审规范.md` |
| Position codes / slot numbering | `docs/点位编号规范.md` |

Everything below comes from incidents in **this** repository, not from a generic template.

---

## 1. Branching model

### 1.1 Current reality

Everyone commits straight to `main` (humans and agents alike). That already caused two
kinds of incidents while 3–4 sessions were working on the same files:

- **Mixed commits** — `ddf37b0` (2026-11-15) merged two workstreams (P1 explicit positions
  and the configuration UI) into one commit spanning 14 files; afterwards the ownership of
  each file could only be guessed from content.
- **Inconsistent `main`** — `40510a8` (2026-11-17) exists solely to "add files referenced by
  main but missing from it", because someone committed only their own files and a clean
  checkout could not compile.

### 1.2 Rules

| Situation | Do this |
|---|---|
| Single developer, single session | Committing to `main` directly is fine (still one topic per commit) |
| **≥2 concurrent sessions** (assume this by default) | One branch per workstream: `feat/<topic>-<YYYYMMDD>`, e.g. `feat/p1-explicit-position-20261115` |
| Production bug fix | `fix/<issue>-<YYYYMMDD>`, branched from `main`, merged back promptly |
| Docs / standards only | May go straight to `main` (small conflict surface), one document family at a time |

Branch naming: `<type>/<kebab-topic>-<YYYYMMDD>`, `type ∈ feat|fix|refactor|docs|test`.
Use the date you **started**, not the merge date.

### 1.3 Merging

```bash
git fetch origin
git rebase origin/main               # resolve conflicts on your own branch
./scripts/test-all.sh --pure-only    # or the gates required by the test master doc
git checkout main && git merge --squash feat/xxx-20261115
git commit                           # message per contribution-guidelines, cite the branch
git push origin main
```

- **Squash**: one branch = one commit. "fix build", "fix again" must not enter `main`'s history.
- **Never force-push `main`.** Fix forward with a new commit; rewriting history breaks other
  sessions' pushes (an incident of this kind is recorded in contribution-guidelines §9).
- **Always rebase before merging**; otherwise `git log --graph` becomes unreadable.

### 1.4 Minimum bar for `main`

1. **A clean checkout compiles** — `bash scripts/compile-kernel.sh` exits 0 on a checkout
   that has `lib/`. "Only compiles on the author's machine" is the most expensive class of
   problem in this repo (`40510a8`).
2. **You did not turn the gates red** — run the gates relevant to your change and record
   commands + results in the commit body.
3. **Docs ship with code** — behaviour change ⇒ doc change; doc change ⇒ verify the numbers.

---

## 2. Code discipline

### 2.1 Inventory first, code second

Before adding a table, a column, or a method, check whether it already exists. Real
incident: we were about to create four tables (model / method / error code / profile) for a
new device family; `information_schema` showed `device`, `device_method`, `error_code`,
`recovery_policy` and `duration_model` already existed — `device_method` even had
`device_id` + `UNIQUE(device_id, method)`. Creating new tables would have been pure
duplication.

```sql
SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY 1;
SELECT column_name, data_type FROM information_schema.columns
 WHERE table_name='device_point' ORDER BY ordinal_position;
```

### 2.2 Never overwrite `out/`

`out/` is the classpath the **running kernel** uses (`scripts/start-kernel.sh` runs
`-cp out`). Verification builds go to a scratch directory:

```bash
cd labos-kernel
rm -rf /tmp/labos-tc && mkdir -p /tmp/labos-tc
find src/main src/boot/java -name '*.java' | LC_ALL=C sort > /tmp/a.txt
javac -encoding UTF-8 -parameters -d /tmp/labos-tc \
      -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" @/tmp/a.txt
```

Only run `scripts/build-kernel.sh` when you actually intend to restart the kernel.

### 2.3 Comments explain *why*, not *what*

State which alternative was rejected and what breaks otherwise. Test: can a newcomer three
years later tell **why you did not do it the other way**?

### 2.4 Boundaries and dependencies

- **The kernel stays framework-free** (see `CONTRIBUTING.md`). Spring lives in `src/boot`
  only; `src/main` must remain runnable bare.
- **Protocol adapters live in separate processes**; device protocol details do not enter the kernel.
- Reuse existing homes for constants/enums (`RuntimeSettings`, the `capability` table,
  `DeviceModels`) instead of starting a parallel set.

### 2.5 The "false red" trap

Running gates in a **copied tree** produces false failures: `test-sidecar*.sh` cannot find
`sidecar-dotnet/` and reports `No such file or directory`. That is not a regression. Report
it as "copy was missing X" — never silently weaken a test to get green.

---

## 3. Database discipline

| Item | Rule |
|---|---|
| Location | `pg_rebuild/migration/YYYYMMDD_<snake_topic>.sql` |
| Idempotency | Must be re-runnable: `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DO $$ ... duplicate_object` |
| Transaction | Apply with `psql -1 -v ON_ERROR_STOP=1 -f <file>` |
| Merge | Structural changes also update `pg_rebuild/schema/90_all_in_one.sql` |
| Seed data | `ON CONFLICT DO NOTHING` — never overwrite values edited on site; use `WHERE ... IS DISTINCT FROM` for patches |

**Never fabricate data.** Statistics tables such as `duration_model`
(method → p50/p90/min/max/sample_count) are measured, not invented — leave them empty and
let them accumulate. Likewise runtime state such as `device_state.online` may only be
written by the heartbeat/sidecar.

Any manual workaround (for example a script that refreshes Redis `labos:link:*` keys) **must
be logged in the dev log together with when it can be deleted**. Such a script once masked a
dead sidecar: stopping the sidecar left the device "online", which cost hours to find.

When seeding test data, a violated CHECK constraint
(e.g. `ck_device_point_deck_in_unreachable`: `deck_in` ⇒ `robot_reachable=false` and
`role='internal'`) means the constraint encodes a **business fact** — internal positions are
served by the device itself. Shape the data to the fact; do not disable the constraint.

Runtime cleanup goes through the API, not raw SQL:
`POST /api/runs/dismiss {"runIds":[...]}` (writes `aborted + EC-HUMAN-DISMISS`, audited).
Note `POST /runs/{id}/dismiss` only handles crash-recoverable records and returns
`{"dismissed":false}` for ordinary paused runs.

---

## 4. Environment discipline (this workstation)

| Port | Purpose |
|---|---|
| 18080 | kernel (`scripts/start-kernel.sh`) |
| 5432 / 6379 | PostgreSQL / Redis |
| 19500–19512 | legacy demo island (`ID-*`) simulators |
| 19612 | D9608-1 (dispenser) |
| 19620–19623 | P_AUTO-1..4 (extractors) |
| 19624–19629 | DISP1..6 (EL406) |
| 19630 / 19631 | ARM1 (robot) / STACK1 (consumable stack) |
| 19700+ | sidecar link ports |

Use `python3 scripts/sim-all.py` (`--status` / `--restart --with-sidecar` / `--stop`) instead
of hand-launching simulators.

Before restarting the kernel:

```bash
psql -tAc "select count(*) from run where status='running'"   # ask first if >0
ps aux | grep [L]abosApplication                              # two kernels = double scheduling
kill <pid>; lsof -nP -iTCP:18080 -sTCP:LISTEN                  # must be empty
nohup bash scripts/start-kernel.sh >> /tmp/labos-kernel.log 2>&1 &
```

Demo/rehearsal switches **must be passed explicitly** (without them the simplified path runs):

```bash
LABOS_TRANSFER_PLAN=1            # TransferPlan: position locks + transport_segment rows
LABOS_TRANSFER_AUTO_CONFIRM=1    # global auto-confirm (otherwise every transfer waits for a human)
LABOS_EXEC_GRAPH=1               # graph execution
LABOS_AUTH=1                     # require login (=0 only for integration/demo)
```

**Online state is Redis `labos:link:<id>:<ip>:<port>`** (sidecar refreshes every 10 s, TTL 30 s),
*not* a TCP probe. Starting simulators without sidecars gives you listening ports and a
permanently `offline` device.

**Do not touch other sessions' work in progress.** Run `git status` first; when a file has
both parties' edits, stage by hunk (`git diff -U3` → `git apply --cached`) instead of
`git add <file>`.

---

## 5. Checklist

**Before starting**

- [ ] `git status` reviewed — know what others are editing
- [ ] Table/column/method existence checked in DB and code
- [ ] Branch opened if ≥2 concurrent sessions

**Before committing**

- [ ] One topic per commit ("and" in the title ⇒ split)
- [ ] `git diff --cached --stat` contains no foreign files
- [ ] Build verified into `/tmp`, `out/` untouched
- [ ] Verification commands and results in the body
- [ ] Docs updated; unfinished work listed under "known issues"

**Before merging / pushing**

- [ ] `git fetch && git rebase origin/main`
- [ ] Clean checkout compiles
- [ ] Relevant gates run; false reds explained
- [ ] No force-push to `main`

**Before environment operations**

- [ ] `running` count and kernel process count checked
- [ ] Demo switches passed explicitly
- [ ] Simulators and sidecars started together (`sim-all.py --with-sidecar`)
- [ ] Manual workarounds logged with an expiry condition
