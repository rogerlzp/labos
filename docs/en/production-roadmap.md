# Production Roadmap

> **English edition.** The Chinese original is [`docs/生产化路线图.md`](../生产化路线图.md); this is a line-for-line translation. Numbers, commit hashes and file paths are identical to the Chinese source — if they ever disagree, the Chinese original wins.

> Answers one question: **from the current implementation baseline, how much is still
> missing until the system can really go into production and reach the level of comparable
> commercial products?**

---

## 1. Current state

### What already works

| Capability | Maturity | Evidence |
|---|---|---|
| **Workflow orchestration** | Usable | BPMN graph parsing, parallel / exclusive / loop gateways, device + variable + loop canvas |
| **Workflow execution** | Usable (simulation) | All workflows run through on the virtual bus, no cycles and no isolated nodes |
| **Multi-experiment parallelism** | Basically usable | Resource-level admission (since 2026-11-10) + EDD ordering + queueing |
| **Plate tracking** | Usable | Position-level atomic reservation, dual locks on transport, three-colour visualisation |
| **Device driver framework** | Framework complete | Short / persistent connections, contract validation, probe, mock |
| **Crash recovery** | Basically usable | Idempotency key, startup cleanup |
| **Alerts** | Usable | Generation / de-duplication / merging |
| **Execution traceability** | Usable | Command-level parameters, raw response, device and duration |

### What does not work yet

| Gap | Impact | Severity |
|---|---|---|
| **Never connected to a real device** | Every "pass" is a conclusion drawn in simulation | 🔴 Extreme |
| **No scheduling Gantt / visual scheduling** | Can only queue by priority, cannot be adjusted by hand | 🟠 High |
| **No audit trail and compliance** | Pharmaceutical / clinical scenarios cannot pass the review | 🟠 High |
| **Long tasks (30 minutes of incubation) are misjudged as timeouts** | It fails outright on a real device | 🔴 Extreme |
| **No performance baseline** | No way to know whether 10 parallel experiments will stall | 🟡 Medium |
| **No fine-grained permissions** | Login only, no "who may change a workflow" | 🟡 Medium |
| **No reporting / BI** | Utilisation and turnaround time can only be queried from the database | 🟡 Medium |
| **Single instance** | If the control console goes down, the whole line stops | 🟡 Medium |

---

## 2. Gap analysis: benchmarking against commercial products

Broken down by capability, marking "what we have and what is missing".

### 2.1 Workflow orchestration

| What commercial products have | Us | Gap |
|---|---|---|
| Graphical drag-and-drop orchestration | ✅ Two canvases (experiment / island) | — |
| Variables and loops | ✅ Variable table + `${var}` + loop column | — |
| Branching / parallelism / merging | ✅ Four node shapes to tell them apart | — |
| Workflow template library | ⚠️ There is a workflow library, but no template marketplace | Medium |
| Version management and rollback | ❌ | **Missing** |
| Workflow simulation preview | ✅ Shadow mode | — |

**Conclusion**: the orchestration capability is **close to** that of commercial products;
what is missing is **version management**.

### 2.2 Scheduling

| What commercial products have | Us | Gap |
|---|---|---|
| Multi-experiment parallelism | ✅ Resource-level admission (different devices in the same island may run in parallel) | — |
| Priority + deadline | ✅ EDD | — |
| **Gantt visualisation** | ❌ | **Missing** |
| **Manual drag-to-adjust the schedule** | ❌ | **Missing** |
| Resource calendar (device maintenance windows) | ⚠️ The table exists but is unused | Medium |
| Rolling horizon re-scheduling | ❌ Greedy only | **Missing** |
| Utilisation analysis | ⚠️ Can only be queried from the database | Medium |

**Conclusion**: scheduling is **the biggest capability gap**. What sells commercial
products is exactly "a schedule you can see".

### 2.3 Device onboarding

| What commercial products have | Us | Gap |
|---|---|---|
| Driver framework | ✅ Capability declaration + contract | — |
| Built-in driver library (hundreds of device types) | ❌ Framework only | **Missing** (each one has to be onboarded) |
| Driver hot-plugging | ❌ | Medium |
| Device self-test / calibration | ❌ | Medium |
| Protocol debugging tool | ✅ Probe + mock | — |

**Conclusion**: **the framework is there, the driver library is the manual work** — 1-3
days per device.

### 2.4 Plate positions and samples

| What commercial products have | Us | Gap |
|---|---|---|
| Plate-position-level tracking | ✅ Position-level reservation | — |
| Plate ID tracking (barcode) | ✅ Three-tier resolution | — |
| Sample-level tracking (wells) | ⚠️ The table exists but is unused | Medium |
| Plate position visualisation | ✅ Three colours | — |
| Cold-chain / time-limit tracking | ❌ | Low |

**Conclusion**: on plate positions we are **not behind**.

### 2.5 Operations and compliance

| What commercial products have | Us | Gap |
|---|---|---|
| Execution traceability | ✅ Command level | — |
| **Audit log (who changed what)** | ⚠️ Partial | **Missing** |
| **Electronic signature (21 CFR Part 11)** | ❌ | **Missing** |
| Data integrity validation | ❌ | Medium |
| Alerts and notifications | ✅ | — |
| Report export | ❌ | Medium |
| Multi-tenant isolation | ⚠️ There is a tenant_id, not verified | Medium |

**Conclusion**: **compliance is a hard gate for entering the pharmaceutical industry**,
and it cannot be passed today.

---

## 3. Phased plan

### P0: able to run (2-3 weeks)

**Goal**: one real device, one workflow, one real execution.

| Task | Estimate | Status |
|---|---|---|
| Fix the long-task timeout (`method_status=1` keeps waiting) | 2 days | **✅ Done** |
| One simulator per device (the whole island in local simulation) | 3 days | **✅ Done** |
| Device heartbeat + three states + offline alerts | 2 days | **✅ Done** |
| Write the on-site deployment manual | 1 day | **✅ Done** |
| Connect the first real device (pick the simplest, e.g. the incubator) | 2 days | ⬜ Waiting for the site |
| Run the probe over every endpoint and confirm the contract | 1 day | ⬜ Waiting for the site |
| A real run of one workflow (2-3 nodes) | 2 days | ⬜ Waiting for the site |
| Fix the problems the real run exposes | 3-5 days | ⬜ Unknown, depends on how many problems there are |

**Remaining work: about 1.5-2 weeks** (all of it needs on-site devices and network).

**P0 exit criterion**:

```
one real workflow runs through on a real device, run_command holds the real raw response and the duration
```

---

## Before §3: what can be run now (the local simulation loop is closed)

While waiting for the on-site devices, these can **already be run through**:

| Step | How to run it | What it verifies |
|---|---|---|
| **Start the whole-island simulator** | `./scripts/sim-up.sh LAB-DEMO-02 150` | 7 devices, each on its own port |
| **Driver registration** | the startup log | `[驱动] DRV-SIM-ID1183 → tcp://127.0.0.1:19501` |
| **Heartbeat liveness check** | `GET /api/heartbeat` | online / offline state |
| **Workflow execution** | `POST /api/workflows/239/run` | every device receives its own command |
| **Running state** | the device card in the UI | a yellow light + the method name |
| **Offline alert** | kill one simulator, wait 40 seconds | only that one is reported, and it closes automatically after recovery |
| **Long task** | the `--long` flag | it answers "running" first and then "completed", with no misjudgement |
| **Slow device** | `--delay 5000` | whether the timeout setting is sufficient |
| **Intermittent fault** | `--fail 3` | the retry logic |
| **Device offline** | `--offline` or kill | `EC-05-1006` + an alert |

**This layer can already answer "is the logic right"**; what is left are the physical
constraints and the edge cases of the vendor DLL.

### P1: usable for daily work (1-1.5 months)

**Goal**: 3-5 devices, one complete process, and a duty officer who can operate it
independently.

| Task | Estimate |
|---|---|
| Onboard all the island's devices (5-10 units) | 1-2 weeks |
| Robot point teaching and verification | 3 days |
| Real-run verification of plate tracking | 3 days |
| Fix the concurrency problems (multiple devices acting at the same time) | 1 week |
| Tune the alert rules (reduce false positives) | 3 days |
| Duty officer training + the operating manual | 3 days |
| Canary: one workflow × 20 runs | 1 week |

**P1 exit criterion**:

```
the duty officer can start an experiment independently, and can locate a problem by looking at run_command themselves
```

### P2: able to run multiple experiments in parallel (2-3 months)

**Goal**: 2-3 experiments running at the same time on one island, without collisions.

| Task | Estimate |
|---|---|
| **Scheduling Gantt chart** (frontend, the largest piece of work) | 2-3 weeks |
| Manually adjust the schedule (drag to change the order) | 1 week |
| Rolling horizon re-scheduling (section 6 of the implementation design document) | 2 weeks |
| Utilisation statistics (device / island / shift) | 1 week |
| Performance load test (10 parallel experiments) | 1 week |
| Fix the problems the load test exposes | 1-2 weeks |

**P2 exit criterion**:

```
can answer "does next week's capacity fit?", and give a visual schedule
```

### P3: able to enter a regulated industry (3-6 months)

**Goal**: pass a GxP audit.

| Task | Estimate |
|---|---|
| Audit log (who changed what, and when) | 2 weeks |
| Electronic signature (21 CFR Part 11) | 3-4 weeks |
| Data integrity (tamper-proofing, validation) | 2 weeks |
| Fine-grained permissions (role × operation) | 2 weeks |
| Validation documentation (IQ/OQ/PQ) | 1-2 months |
| Third-party audit support | Ongoing |

**P3 exit criterion**:

```
the auditor asks "who moved this plate from intake to dispatch, when, and on what basis",
and the complete chain can be pulled out within 5 minutes
```

### P4: able to scale (optional)

| Task | Estimate |
|---|---|
| Multi-instance deployment (control console and gateway separated) | 3 weeks |
| High availability (Redis master/slave, gateway active/active) | 2 weeks |
| Driver library (20-50 common device types) | 3-6 months |
| Template marketplace (sharing workflow templates) | 1 month |

---

## 4. Workload summary

| Phase | Goal | Estimate (one person) | Key milestone |
|---|---|---|---|
| **P0** | Run one workflow through on a real device | **2-3 weeks** | demonstrable |
| **P1** | Usable day to day by a duty officer | **1-1.5 months** | can be tried |
| **P2** | Multi-experiment parallelism + visual scheduling | **2-3 months** | can match the core capability of commercial products |
| **P3** | Compliance (GxP) | **3-6 months** | can enter the pharmaceutical industry |
| **P4** | Scale | 6-12 months | can be sold |

**From zero to "matching the core capability of commercial products": about 4-6 months**
(P0-P2).
**To "being able to enter a regulated industry": another 3-6 months** (P3).

---

## 5. The three most important judgements

### 1. Passing in simulation ≠ ready for production

Every "pass" today is a conclusion drawn on the virtual bus. **A real device exposes a
pile of problems**:
wrong driver field names, unreasonable timeout settings, inaccurate robot points, devices
responding more slowly than expected.

**Half of the 2-3 week estimate for P0 is "fixing the problems the real run exposes"** —
how long that really takes depends on luck.

### 2. Scheduling visualisation is the dividing line that makes it "look professional"

In a commercial product demo, the thing that catches the eye is the **Gantt chart**.
We can only queue by priority today, so the user cannot see "why this experiment is at the
back".

**The 2-3 week Gantt chart in P2 has the highest return on investment of everything on the
list**.

### 3. Compliance is an industry gate, not a bonus

If the goal is to enter pharmaceuticals / clinical, **P3 cannot be skipped**.
But if the target is only research laboratories / universities, **P3 can be skipped** — this
depends on the target customer.

---

## 6. Recommended next step

**Follow P0 and connect one real device first.**

Reasons:
1. Every conclusion today comes from simulation; **the earlier you get onto a real device,
   the earlier you find the real problems**
2. P0 only needs 2-3 weeks, and the risk is controllable
3. Only once a real device runs through do you know whether the P1 estimate is reliable

**The concrete first thing**:

```bash
# 1. change the driver endpoint configuration
vi labos-kernel/sidecar-dotnet/appsettings.json

# 2. start the Sidecar
cd labos-kernel/sidecar-dotnet && dotnet run -c Release

# 3. probe validation (the safest first step)
curl -X POST "/api/shadow/probe?resourceId=<设备id>&method=Home"

# 4. once all four contract fields are true, run a single workflow
```

---

## 7. One-line summary of the capability gap

| Dimension | Gap |
|---|---|
| **Workflow orchestration** | Close (missing version management) |
| **Plate tracking** | Not behind |
| **Execution traceability** | Not behind (storing the raw response at command level is an advantage) |
| **Scheduling** | **Clearly behind** (no Gantt, no manual adjustment, no rolling re-scheduling) |
| **Driver library** | **Clearly behind** (a framework but no devices) |
| **Audit and compliance** | **Clearly behind** (cannot enter pharmaceuticals) |
| **Reporting and analysis** | Behind |

**Our strengths**: a clean kernel, the ability to simulate, command-level traceability,
light single-machine deployment.
**Our weaknesses**: never connected to a real machine, no scheduling visualisation, no
compliance capability.

**Finish P0 first, then talk about benchmarking.**
