> **English edition** · [简体中文](../../README.zh-CN.md) · Chinese documents live under [`docs/`](../)

# LabOS — English Documentation Index & Translation Ledger

> **This is the ledger for the English edition of LabOS.**
>
> - The Chinese documentation set under `docs/` is **unchanged** and remains
>   authoritative for implementation detail.
> - The English edition is **additive**: new files only, no Chinese file was edited
>   to produce it.
> - Every number in this document is **regenerable**:
>   `python3 scripts/scan-i18n.py --md docs/en/i18n-ledger.md`.

---

## 1. What exists in English today

| English document | Contents | Status |
|---|---|---|
| [`README.md`](../../README.md) | Project facade: capabilities, quick start, architecture, design decisions, i18n model, known limitations | ✅ Complete |
| [`docs/en/INDEX.md`](INDEX.md) | This document: index + ledger + glossary | ✅ Complete |
| [`docs/en/roadmap.md`](roadmap.md) | The authoritative roadmap (English edition of `ROADMAP.md`) | ✅ Complete |
| [`docs/en/architecture.md`](architecture.md) | Package layout, key classes, data flow | ✅ Complete |
| [`docs/en/deployment.md`](deployment.md) | On-site deployment steps and pass criteria | ✅ Complete |
| [`docs/en/production-roadmap.md`](production-roadmap.md) | How far production is, staged estimates | ✅ Complete |
| [`docs/en/onboarding.md`](onboarding.md) | 30-minute onboarding for a new reader | ✅ Complete |
| [`docs/en/review-20261104/00-index.md`](review-20261104/00-index.md) · [`01-deliverables-and-verification.md`](review-20261104/01-deliverables-and-verification.md) · [`02-next-steps.md`](review-20261104/02-next-steps.md) | The 2026-11-04 review package for external reviewers | ✅ Complete |
| [`docs/en/review-20261103/`](review-20261103/) | The 2026-11-03 review bundle (index / functional / technical / design / testing) | ✅ Complete |
| [`docs/en/review-records/`](review-records/) | The review records (12 documents) and the bug ledger | ✅ Complete |
| [`docs/en/review-20261102.md`](review-20261102.md) · [`review-code-20261102.md`](review-code-20261102.md) · [`review-20261102-post-review.md`](review-20261102-post-review.md) · [`design-review-package.md`](design-review-package.md) | The earlier review packages and the design review package | ✅ Complete |
| [`docs/en/design/`](design/) | Unified experiment orchestration — design draft + implementation checklist | ✅ Complete |
| [`docs/en/acceptance/`](acceptance/) | The M1 / M3 / M4 acceptance reports | ✅ Complete |
| [`docs/en/devices/`](devices/) | Device model library, error-code mapping, protocol handling, per-device notes | ✅ Complete |
| [`docs/en/experiment-orchestration-design.md`](experiment-orchestration-design.md) · [`workflow-control-*.md`](workflow-control-guide.md) · [`expression-engine-design.md`](expression-engine-design.md) · [`async-results-and-dynamic-branches.md`](async-results-and-dynamic-branches.md) | Experiment orchestration, flow control, expression engine, dynamic branches | ✅ Complete |
| [`docs/en/parallel-scheduling-*.md`](parallel-scheduling-design.md) · [`concurrency-scheduling-decisions.md`](concurrency-scheduling-decisions.md) · [`hybrid-scheduling-design.md`](hybrid-scheduling-design.md) | Parallel scheduling and its decision record | ✅ Complete |
| [`docs/en/position-occupancy-*.md`](position-occupancy-and-mutual-exclusion-design.md) · [`resource-model-*.md`](resource-model-and-deadlock-avoidance.md) · [`concurrency-deadlock-analysis.md`](concurrency-deadlock-analysis.md) | Position occupancy, the resource model, deadlock analysis | ✅ Complete |
| [`docs/en/development-log.md`](development-log.md) · [`decision-log.md`](decision-log.md) | The day-by-day development log and the decision record | ✅ Complete |
| [`docs/en/simulation-*.md`](local-simulation-guide.md) · [`load-test-report.md`](load-test-report.md) · [`end-to-end-verification-report.md`](end-to-end-verification-report.md) · [`parallel-capability-verification.md`](parallel-capability-verification.md) · [`full-flow-test-matrix-design.md`](full-flow-test-matrix-design.md) · [`testing-and-release-strategy.md`](testing-and-release-strategy.md) | Simulation, load tests and verification reports | ✅ Complete |
| [`docs/en/ui-design-spec.md`](ui-design-spec.md) · [`preflight-ui.md`](preflight-ui.md) · [`execution-monitoring-ui.md`](execution-monitoring-ui.md) · [`paused-queue-ui.md`](paused-queue-ui.md) · [`operations-manual-islands-and-transfers.md`](operations-manual-islands-and-transfers.md) · [`database-design.md`](database-design.md) | UI specifications, on-site operation, database design | ✅ Complete |
| [`docs/en/review-20261103/00-index.md`](review-20261103/00-index.md) · [`01-functional.md`](review-20261103/01-functional.md) · [`02-technical.md`](review-20261103/02-technical.md) · [`03-design.md`](review-20261103/03-design.md) · [`04-testing.md`](review-20261103/04-testing.md) | The 2026-11-03 review bundle (functional / technical / design / testing) | ✅ Complete |
| [`docs/en/contribution-guidelines.md`](contribution-guidelines.md) | How a commit message is written, when to commit, what must not be committed | ✅ Complete |
| [`docs/en/review-guidelines.md`](review-guidelines.md) | How to review, how to record a review, how the reviewed party responds | ✅ Complete |
| `webapp/src/i18n/` | Runtime i18n layer + zh-CN / en-US message packs + domain-code table | ✅ Complete |
| `scripts/scan-i18n.py` | i18n ledger tool and CI gate (key parity, code coverage, residual Chinese) | ✅ Complete |

### Frontend i18n — what is already English-capable

| Surface | Mechanism | Notes |
|---|---|---|
| Application shell (header, session errors, admin-only notice) | `t('app.*')` | `App.tsx` |
| Left navigation (5 groups, 20 entries) | `t('nav.*')` | One menu structure, two message packs — never two menus |
| Roles (`admin` / `operator` / `viewer` / signed out) | `t('role.*')` | Shared by the header and the user-management page |
| Read-only button tooltip | `t('auth.readOnlyTitle')` | Applied by `useReadOnly()`, so every page inherits it |
| **Status vocabulary** (45 run/node/step/experiment/batch codes) | `status.<scope>.<code>` in `i18n/codes.ts` | Chinese comes from the backend (DB authority); English is resolved **by code** |
| **Pause kinds** (3 codes + 3 handling hints) | `pause.<code>` / `pause.<code>.hint` | Same rule |
| **On-site hold kinds** (4) and **hold scopes** (3) | `hold.<code>` / `holdScope.<code>` | Same rule |
| Login page | `t('login.*')` | Fully translated |
| Live board (running runs, crash-interrupted runs, attendance) | `t('runs.*')` | Fully translated |
| API error messages (session expired, graph load/save failures) | `t('error.*')` | Thrown by `api/client.ts` without React dependency |
| Estimated-wait formatting | `t('format.wait.*')` | `utils/format.ts` |
| Ant Design components (date pickers, pagination, empty states) | antd `locale` + dayjs locale | Both follow the runtime locale |

**Verified numbers at the time of writing** (regenerate with the scanner):

| Metric | Value |
|---|---|
| Message keys (zh / en) | 1 176 / 1 176 — key-aligned |
| Domain codes with an English entry | 58 / 58 (45 statuses + 3 pause kinds + 3 hints + 4 hold kinds + 3 hold scopes) |
| English texts actually reachable in the UI (executed, not parsed) | 1 238 — **0 contain Chinese** |
| **Remaining Chinese in the frontend (user-facing, comments excluded)** | **0 characters / 0 files** — every one of the 26 console pages is fully translated |

All 26 console pages have been converted (batches B2–B7 below). The Chinese
console is unchanged: the default locale is still `zh-CN`, and every Chinese
string in the message pack is the original text.

---

## 2. How text is looked up — the two rules

These rules are what make the English edition maintainable. Breaking them is how
translation projects rot.

1. **UI text is looked up by key.** A page calls `t('runs.col.progress')`. It never
   branches on the Chinese string, and it never holds a second, English-only copy of
   the same label.
2. **Domain codes are looked up by code, never by Chinese text.** The backend
   vocabulary (`GET /api/meta/status-vocabulary`) owns the Chinese label, so a status
   added on the backend shows up in the Chinese UI immediately. The English side
   resolves the same **code** against `webapp/src/i18n/codes.ts` and falls back to
   the raw code.

   **Consequence (deliberate):** an English screen may show a bare code such as
   `paused_unknown` if the English table has no entry yet. It will never show a
   Chinese label. `scripts/scan-i18n.py` fails the build if a backend code has no
   English entry — so this fallback is a bug signal, not a normal state.

### Why no i18n library

The frontend has no i18n dependency, and none is being added:

- The kernel is JDK 17 with zero framework dependencies; the frontend holds to the
  same discipline.
- The actual requirement is "look up a key" + "look up a domain code" — no plurals,
  no gender, no interpolation pipeline.
- Key alignment between the two packs is enforced **by the type system**
  (`Record<keyof typeof zhCN, string>`), not by human review. A missing, extra or
  misspelled key is a compile error.

### Runtime language switching

- The header has a `中文 / English` selector; the choice is stored in `localStorage`
  (`labos.locale`) and survives reloads.
- The build-time default is `VITE_DEFAULT_LOCALE` (`zh-CN` by default).
- The browser language is **not** auto-detected on purpose: the e2e suite runs in a
  default `en-US` browser, and auto-detection would make the Chinese UI a matter of
  luck.

---

## 3. Translation ledger — documents

**Every Chinese document has an assigned English filename.** The table below is the
complete registry: it gives the English path each document will live at, so the file
names are settled even where the translation is still pending. ✅ means the English
document exists now; ⬜ means the English path is reserved and the translation is
queued.

| Chinese source | English target | Status |
|---|---|---|
| `ROADMAP.md` | `docs/en/roadmap.md` | ✅ |
| `README.zh-CN.md` | `README.md` | ✅ |
| `docs/技术架构与模块地图.md` | `docs/en/architecture.md` | ✅ |
| `docs/现场部署手册.md` | `docs/en/deployment.md` | ✅ |
| `docs/生产化路线图.md` | `docs/en/production-roadmap.md` | ✅ |
| `docs/评审指引.md` | `docs/en/onboarding.md` | ✅ |
| `docs/M1验收报告.md` | `docs/en/acceptance/m1-report.md` | ✅ |
| `docs/M3-启动准入-主链粗筛.md` | `docs/en/acceptance/m3-start-admission.md` | ✅ |
| `docs/M4-暂停恢复与故障处理.md` | `docs/en/acceptance/m4-pause-resume.md` | ✅ |
| `docs/UI-界面设计说明书.md` | `docs/en/ui-design-spec.md` | ✅ |
| `docs/modules/README.md` | `docs/en/modules/README.md` | ✅ |
| `docs/为什么不用银行家算法.md` | `docs/en/why-not-banker-s-algorithm.md` | ✅ |
| `docs/仿真与验证指南.md` | `docs/en/simulation-and-verification.md` | ✅ |
| `docs/全链路验证报告-模拟器.md` | `docs/en/end-to-end-verification-report.md` | ✅ |
| `docs/初始化与设备接入指南.md` | `docs/en/initialization-and-device-onboarding.md` | ✅ |
| `docs/动态流程能力-总纲.md` | `docs/en/dynamic-workflow-overview.md` | ✅ |
| `docs/压测报告-多实验并行.md` | `docs/en/load-test-report.md` | ✅ |
| `docs/变量传递与点位初始化-设计.md` | `docs/en/variable-passing-and-point-init.md` | ✅ |
| `docs/启动前预检界面.md` | `docs/en/preflight-ui.md` | ✅ |
| `docs/复核包-20261102-审后.md` | `docs/en/review-20261102-post-review.md` | ✅ |
| `docs/多实验并行调度-动态规划与滚动排程.md` | `docs/en/parallel-scheduling-dp-and-rolling-horizon.md` | ✅ |
| `docs/多实验并行调度-设计.md` | `docs/en/parallel-scheduling-design.md` | ✅ |
| `docs/实验室画像与可配置项-设计.md` | `docs/en/lab-profile-and-configurables.md` | ✅ |
| `docs/实验编排-功能设计.md` | `docs/en/experiment-orchestration-design.md` | ✅ |
| `docs/并发死锁分析与对策.md` | `docs/en/concurrency-deadlock-analysis.md` | ✅ |
| `docs/并发调度决策记录.md` | `docs/en/concurrency-scheduling-decisions.md` | ✅ |
| `docs/并行能力验证-20261103.md` | `docs/en/parallel-capability-verification.md` | ✅ |
| `docs/页面实操验证报告-多并行多网关-20261105.md` | `docs/en/page-exercise-report-multi-parallel-20261105.md` | ✅ |
| `docs/暂停与恢复语义验证-四类节点-20261106.md` | `docs/en/pause-resume-semantics-four-node-types-20261106.md` | ✅ |
| `docs/设备执行中暂停-现状实测与实现方案-20261106.md` | `docs/en/pause-during-device-measured-and-plan-20261106.md` | ✅ |
| `docs/并行调度-实施规格.md` | `docs/en/parallel-scheduling-spec.md` | ✅ |
| `docs/开发日志.md` | `docs/en/development-log.md` | ✅ |
| `docs/异步结果查询与动态分支-设计.md` | `docs/en/async-results-and-dynamic-branches.md` | ✅ |
| `docs/执行监控界面.md` | `docs/en/execution-monitoring-ui.md` | ✅ |
| `docs/提交规范.md` | `docs/en/contribution-guidelines.md` | ✅ |
| `docs/操作手册-岛屿与搬运.md` | `docs/en/operations-manual-islands-and-transfers.md` | ✅ |
| `docs/数据库设计说明.md` | `docs/en/database-design.md` | ✅ |
| `docs/暂停恢复与故障处理-设计.md` | `docs/en/pause-resume-and-fault-handling-design.md` | ✅ |
| `docs/暂停队列界面.md` | `docs/en/paused-queue-ui.md` | ✅ |
| `docs/本机仿真指南.md` | `docs/en/local-simulation-guide.md` | ✅ |
| `docs/板位级占用与位置互斥-设计.md` | `docs/en/position-occupancy-and-mutual-exclusion-design.md` | ✅ |
| `docs/模拟器设备库.md` | `docs/en/simulator-device-library.md` | ✅ |
| `docs/流程控制-功能说明.md` | `docs/en/workflow-control-guide.md` | ✅ |
| `docs/流程控制-技术设计.md` | `docs/en/workflow-control-design.md` | ✅ |
| `docs/流程控制-测试报告.md` | `docs/en/workflow-control-test-report.md` | ✅ |
| `docs/完整流程测试矩阵-设计.md` | `docs/en/full-flow-test-matrix-design.md` | ✅ |
| `docs/测试与上线策略.md` | `docs/en/testing-and-release-strategy.md` | ✅ |
| `docs/测试规范.md` | `docs/en/test-specification.md` | ✅ |
| `docs/测试用例总表.md` | `docs/en/test-case-master-table.md` | ✅ |
| `docs/混合调度方案-设计.md` | `docs/en/hybrid-scheduling-design.md` | ✅ |
| `docs/点位编号规范.md` | `docs/en/position-numbering-standard.md` | ✅ |
| `docs/自定义脚本节点-设计.md` | `docs/en/custom-script-node-design.md` | ✅ |
| `docs/自查清单.md` | `docs/en/self-check-checklist.md` | ✅ |
| `docs/表达式引擎-设计.md` | `docs/en/expression-engine-design.md` | ✅ |
| `docs/设备状态与心跳-设计.md` | `docs/en/device-state-and-heartbeat-design.md` | ✅ |
| `docs/设备网关与Redis状态中心-设计.md` | `docs/en/device-gateway-and-redis-design.md` | ✅ |
| `docs/设计-实验编排统一-v0.1-实施清单.md` | `docs/en/design/unified-experiment-orchestration-checklist.md` | ✅ |
| `docs/设计-实验编排统一-v0.1-草稿.md` | `docs/en/design/unified-experiment-orchestration-draft.md` | ✅ |
| `docs/设计-逻辑节点分层与编辑器分工.md` | `docs/en/design/logic-nodes-by-layer.md` | ✅ |
| `docs/设计审核包.md` | `docs/en/design-review-package.md` | ✅ |
| `docs/评审包-20261102.md` | `docs/en/review-20261102.md` | ✅ |
| `docs/评审包-代码-20261102.md` | `docs/en/review-code-20261102.md` | ✅ |
| `docs/评审规范.md` | `docs/en/review-guidelines.md` | ✅ |
| `docs/评审包-20261103/00-索引与评审须知.md` | `docs/en/review-20261103/00-index.md` | ✅ |
| `docs/评审包-20261103/01-功能文档.md` | `docs/en/review-20261103/01-functional.md` | ✅ |
| `docs/评审包-20261103/02-技术文档.md` | `docs/en/review-20261103/02-technical.md` | ✅ |
| `docs/评审包-20261103/03-设计文档.md` | `docs/en/review-20261103/03-design.md` | ✅ |
| `docs/评审包-20261103/04-测试文档.md` | `docs/en/review-20261103/04-testing.md` | ✅ |
| `docs/评审包-20261104/00-索引与评审指引.md` | `docs/en/review-20261104/00-index.md` | ✅ |
| `docs/评审包-20261104/01-本轮交付清单与验证.md` | `docs/en/review-20261104/01-deliverables-and-verification.md` | ✅ |
| `docs/评审包-20261104/02-后续规划.md` | `docs/en/review-20261104/02-next-steps.md` | ✅ |
| `docs/评审包-20261105/00-索引与评审须知.md` | `docs/en/review-20261105/00-index.md` | ✅ |
| `docs/评审包-20261105/01-本轮交付清单与验证.md` | `docs/en/review-20261105/01-deliverables-and-verification.md` | ✅ |
| `docs/评审包-20261105/02-遗留与后续规划.md` | `docs/en/review-20261105/02-remainder-and-next-steps.md` | ✅ |
| `docs/设备/README.md` | `docs/en/devices/README.md` | ✅ |
| `docs/设备/协议与断线处理.md` | `docs/en/devices/protocol-and-disconnect-handling.md` | ✅ |
| `docs/设备/型号库与错误码映射.md` | `docs/en/devices/model-library-and-error-code-mapping.md` | ✅ |
| `docs/设备/封膜机-S100.md` | `docs/en/devices/sealer-s100.md` | ✅ |
| `docs/设备/现场确认清单.md` | `docs/en/devices/on-site-confirmation-checklist.md` | ✅ |
| `docs/设备/离心机-ASpin2K.md` | `docs/en/devices/centrifuge-aspin2k.md` | ✅ |
| `docs/设备/M10-手册到型号库接入准备清单-v0.1.md` | `docs/en/devices/m10-manual-to-model-library-checklist.md` | ✅ |
| `docs/评审记录/20261101-01-grok.md` | `docs/en/review-records/20261101-01-grok.md` | ✅ |
| `docs/评审记录/20261102-01-kimi.md` | `docs/en/review-records/20261102-01-kimi.md` | ✅ |
| `docs/评审记录/20261102-02-self.md` | `docs/en/review-records/20261102-02-self.md` | ✅ |
| `docs/评审记录/20261102-03-code-self.md` | `docs/en/review-records/20261102-03-code-self.md` | ✅ |
| `docs/评审记录/20261102-04-code-after.md` | `docs/en/review-records/20261102-04-code-after.md` | ✅ |
| `docs/评审记录/20261102-05-code-after2.md` | `docs/en/review-records/20261102-05-code-after2.md` | ✅ |
| `docs/评审记录/20261103-01-评审包复核.md` | `docs/en/review-records/20261103-01-review-recheck.md` | ✅ |
| `docs/评审记录/20261103-02-评审包复核.md` | `docs/en/review-records/20261103-02-review-recheck.md` | ✅ |
| `docs/评审记录/20261103-03-编排设计评审.md` | `docs/en/review-records/20261103-03-orchestration-design-review.md` | ✅ |
| `docs/评审记录/20261104-01-评审包复核.md` | `docs/en/review-records/20261104-01-review-recheck.md` | ✅ |
| `docs/评审记录/BUG台账.md` | `docs/en/review-records/bug-ledger.md` | ✅ |
| `docs/评审记录/证据/20261103-run_node-ddl.md` | `docs/en/review-records/evidence/20261103-run_node-ddl.md` | ✅ |
| `docs/资源模型与死锁避免-设计.md` | `docs/en/resource-model-and-deadlock-avoidance.md` | ✅ |
| `docs/资源模型修正-出入口门与点位属性.md` | `docs/en/resource-model-correction-gates-and-points.md` | ✅ |
| `LabOS功能规格说明书.md` | `docs/en/functional-specification.md` | ✅ |
| `LabOS实现总纲_20261029.md` | `docs/en/implementation-master-plan.md` | ✅ |
| `决策记录.md` | `docs/en/decision-log.md` | ✅ |
| `前后端实施方案_编译甘特就绪调度.md` | `docs/en/frontend-backend-implementation-plan.md` | ✅ |
| `sidecar-dotnet/README.md` | `sidecar-dotnet/README.en.md` | ✅ |

**Rules for the ledger**

- A document is ✅ only when the English file exists **and** its links resolve
  **and** the numbers in it match the Chinese source.
- Numbers are never re-typed by hand: copy them from the Chinese document and
  re-verify against the code or the test output.
- An English document never becomes a second source of truth. If it must differ
  from the Chinese original (e.g. it describes a newer state), it says so
  explicitly at the top.
- The Chinese originals are **never modified**. The English edition is additive.

**Batching order** (translation batches, by value):

| Batch | Documents | Status |
|---|---|---|
| D0 | `README.zh-CN.md`, `ROADMAP.md`, the three engineering docs | ✅ |
| D1 | `评审指引.md` (onboarding) + the 20261104 / 20261103 review packages | ✅ |
| D2 | `评审规范.md`, `提交规范.md`, `开发日志.md`, `决策记录.md` | ✅ |
| D3 | The design documents (the `-设计.md` series) | ✅ |
| D4 | `设备/` folder (model library, error-code mapping, per-device notes) | ✅ |
| D5 | The `评审记录/` folder (12 review records + bug ledger) | ✅ |
| D6 | The remaining UI / specification / acceptance / report documents | ✅ |
| D7 | The three historical root documents (functional spec, master plan, frontend-backend plan) | ✅ |

**How the translation was produced and checked**

Every English file was checked by `scripts/verify-en-docs.py`, which compares each
translation against its Chinese source: table rows, code fences, headings and
checkboxes must match one for one, each table row's column count must match the
source row for row, no relative link may be broken, and no Chinese prose may be
left in the text (Chinese inside backticks — paths, identifiers, literal program
output — is expected and allowed).

## 4. Translation ledger — frontend ✅ complete

Regenerate with `python3 scripts/scan-i18n.py`. Residual Chinese counts **user-facing
text only** (comments are excluded — translating a comment is not a deliverable).

| Batch | Pages | Status |
|---|---|---|
| B2 | `RunDetail`, `PausedRuns`, `Gantt`, `Durations`, `RunGraph` | ✅ |
| B3 | `Users`, `Policies`, `Audit`, `Alerts` | ✅ |
| B4 | `Plates`, `ModelLibrary`, `ErrorCodes` | ✅ |
| B5 | `Workflows`, `Designer` | ✅ |
| B6 | `Topology`, `Scheduler`, `ScheduleGantt`, `Island`, `IslandFlow` | ✅ |
| B7 | `ExperimentList`, `ExperimentCanvas`, `Experiments` | ✅ |
| — | `App` (shell + navigation), `Login`, `Runs` | ✅ (first slice) |

**Residual user-facing Chinese in `webapp/src`: 0 characters across 0 files.**

**By design, not translated** (the scanner reports these separately):

| File | Chinese characters | Why |
|---|---:|---|
| `webapp/src/i18n/messages/zh-CN.ts` | 428 | The Chinese pack is the **master** — the English pack is derived from it |
| `webapp/src/utils/status.ts` | 233 | `FALLBACK` is the Chinese-side fallback vocabulary; the English side comes from `i18n/codes.ts` |

### Suggested batching

| Batch | Scope | Size | Acceptance |
|---|---|---|---|
| B2 | `RunDetail` + `PausedRuns` + `Gantt` + `Durations` + `RunGraph` | ~570 chars | These render the **status vocabulary**; the code table already covers them, so most strings are labels |
| B3 | `Users` + `Policies` + `Audit` + `Alerts` | ~520 chars | Role and policy vocabulary |
| B4 | `Plates` + `ModelLibrary` + `ErrorCodes` | ~1 360 chars | Device/plate vocabulary — the term glossary below matters most here |
| B5 | `Workflows` + `Designer` | ~1 130 chars | BPMN terminology |
| B6 | `Island` + `IslandFlow` + `Topology` + `Scheduler` + `ScheduleGantt` | ~1 690 chars | Island/scheduling terminology |
| B7 | `ExperimentList` + `ExperimentCanvas` + `Experiments` | ~2 240 chars | Largest; do it last, after the vocabulary is settled |

Each batch ends with: `npx tsc -b --force` (0 errors) and
`python3 scripts/scan-i18n.py` (0 hard errors).

---

## 5. Terminology glossary

Fixed English for the domain vocabulary. Changing a term here means changing it in
every pack at once — that is the point of keeping the table.

| Chinese | English | Note |
|---|---|---|
| 实验室中控 / 中控 | LabOS / control console | Product name — never translate the product |
| 编排 | orchestration | 画布 = canvas; 编排器 = orchestrator |
| 岛 | island | Island-level mutual exclusion; an island is a group of devices |
| 板位 | plate position | **Not** "plate slot". A position holds a plate |
| 板号 | plate ID | Three-tier resolution: parameter → device response → `AUTO-<runId>` |
| 盘点 | inventory | 板位盘点 = plate inventory |
| 流程 | workflow | 工艺 = process (a recipe-like procedure) |
| 实验 | experiment | 实验单 = experiment record |
| 执行单 / run | run | Run detail = run detail |
| 节点 | node | 步骤 = step; 子步骤 = sub-step |
| 现场扣留 | on-site hold | 扣留作用域 = hold scope (site / island / experiment) |
| 暂停 | pause | 暂停队列 = paused queue; 恢复 = resume |
| 值守 | attendance | 有人值守 = attended; 无人值守 = unattended |
| 调度器 | scheduler | 排程 = scheduling; 甘特 = Gantt |
| 时长分布 | duration distribution | |
| 告警 | alert | 告警策略 = alert policy |
| 审计日志 | audit log | |
| 型号库 | model library | 型号 = device model |
| 错误码 | error code | |
| 影子模式 | shadow mode | Probes what *would* be sent, without sending it |
| 幂等键 | idempotency key | |
| 契约校验 | contract validation | 回包四字段 = the four required response fields |
| 心跳 | heartbeat | |
| 设备网关 | device gateway | |
| 副作用服务 / sidecar | sidecar | |
| 位置锁 | position lock | 互斥 = mutual exclusion |
| 原子抢占 | atomic reservation | |
| 死锁 | deadlock | 循环等待 = circular wait |
| 管理员 / 操作员 / 只读 | administrator / operator / read-only | Roles |
| 影子试跑 | dry run | |
| 仿真总线 | simulation bus | |
| 现场 | on site | 现场部署 = on-site deployment |

---

## 5b. Private material (not published)

The overseas market research — buyer maps, competitor positioning, pricing
intelligence, the standards-alignment analysis — is **market intelligence, not
product documentation**. It is deliberately **not published**: it lives in the local
`private/` directory at the repository root, which is git-ignored.

| Item | Where it lives now | Published? |
|---|---|---|
| `docs/海外市场与路线研究/` (Chinese originals + the `third-party/` archive of standards and regulatory texts) | `private/海外市场与路线研究/` | ❌ No |
| The English editions of the three overseas-research documents | `private/海外市场与路线研究/en/` | ❌ No |

**How the local-only mechanism works**

- `private/` is listed in `.gitignore` (`/private/`), so nothing under it is tracked
  or pushed.
- Moving a document into `private/` removes it from the current tree — it does
  **not** remove it from git history. If a file was already pushed, it stays
  reachable in the published commits until the history is rewritten (§7).

**Candidates for the same treatment** (not moved yet — the call is the owner's):
the review records under `docs/评审记录/` (AI review history), and any future
customer- or supplier-facing material.

## 6. How to reproduce the ledger

```bash
# 1. Frontend ledger + hard checks (key parity, code coverage, English UI has no Chinese)
python3 scripts/scan-i18n.py

# 2. Same, written to Markdown
python3 scripts/scan-i18n.py --md docs/en/i18n-ledger.md

# 3. Type check (the two message packs must stay key-aligned)
cd webapp && npx tsc -b --force

# 4. Backend tests must not regress
./scripts/test-all.sh --pure-only
```

The scanner exits non-zero when a hard check fails, so it can be wired into
`scripts/ci-gate.sh` as a fifth gate (not yet done — tracked as an open item in the
action list).

---

## 7. Open items for the English edition

| # | Item | Status |
|---|---|---|
| 1 | Translate the frontend | ✅ Done — 26/26 pages, 0 residual Chinese |
| 1b | Translate the documents | ✅ **89 / 89 done** — every Chinese document has an English edition |
| 2 | Translate the P0/P1 documents (roadmap, onboarding, overseas research 00–02) | ⬜ |
| 3 | Merge `feat/auto-20261103-acb9a80e` into `main`, then translate overseas research 03–10 | ⬜ Blocked on the merge |
| 4 | Wire `scan-i18n.py` into `scripts/ci-gate.sh` | ⬜ |
| 5 | Backend-side Chinese text (kernel error messages, `StatusVocabulary` labels, SQL comments) | ⬜ Not started — the API returns Chinese `error` strings, which currently surface verbatim in the console |
| 6 | Decide whether the English build ships as a separate artifact (`VITE_DEFAULT_LOCALE=en-US`) or as one build with runtime switching | ⬜ Currently: one build, runtime switching |

**Item 5 is the largest remaining gap.** The kernel returns Chinese error strings in
its `error` field; the console displays them verbatim. Translating them properly
means moving the error **codes** to the API contract (as `StatusVocabulary` already
does for statuses) rather than translating free-form text — the same "look up by
code" rule, applied one layer down.
