# Island Boundary Implementation Specification

> **执行基线说明（2026-11-07）**
> 本文档由评审分支 `review/labos-industrial-architecture` 归档到 `main` 执行基线，已按用户裁决处理：
> ① **流程（Workflow）不允许跨 Workcell；实验（Experiment）允许跨 Workcell**；
> ② `main` 的暂停修复 `3debf78` 为代码基线；③ 所有工程实施在 `main` 上进行，评审分支仅作设计归档；
> ④ 按 `ADR-DOC-001` 去除商业公司/产品名称，外部实践改以「产品形态 A/B/C」表述。
>
> 本文**保留原始思考与源码证据**，但结论以 `ADR_INDEX.md` 与 `REMEDIATION_STATUS.md` 为唯一准绳；
> 被新裁决取代的条目见文末「裁决影响（2026-11-07）」，不得按被取代条目实施。

Date: 2026-11-07
Branch: `review/labos-industrial-architecture`
Status: design/specification only; no business code or schema changed.

Related audits:
- `LABOS_ISLAND_BOUNDARY_AND_HOLD_DISPATCH_AUDIT_20261107.md`
- `LABOS_SOURCE_AUDIT_A-F_20261107.md`
- `LABOS_REMEDIATION_PLAN_20261107.md`

## 1. Scope and invariants

This specification covers the first implementation slice for Workflow/Experiment Island boundaries. It does not implement robot motion planning, collision checking, SafetyDomain propagation, or Hold scheduling.

Business meaning: an Island is a physical workcell/room containing devices and one or more robot arms. A physical Workflow belongs to exactly one Island. A physical Experiment belongs to exactly one Island and may reference only Workflows assigned to that same Island.

Hard invariants:

1. `UNASSIGNED` is not a wildcard. A Workflow with no authoritative Island cannot be published or executed if it contains physical tasks.
2. A physical task's resource must exist in the same tenant, be a device resource, and belong to the Workflow's Island under the approved topology rule.
3. A Workflow's Island cannot be silently changed by URL parameters, editor state, graph XML, or client payload.
4. An Experiment may reference only Workflows whose authoritative Island equals the Experiment's Island.
5. All write paths enforce the same server-side rules. Frontend validation is usability, not authority.
6. Unknown topology, missing resource, invalid tenant, or unproven transfer reachability is a blocking validation result.
7. No cross-Island transfer is implied by a BPMN sequence edge. A real transfer requires a same-Island robot with proven source/target reachability. If this cannot be proven, block the connection/compile rather than create a direct device-to-device sequence.

## 2. Newly confirmed transaction hazards

### 2.1 Workflow creation writes Island after saving the initial graph

Evidence: `WorkflowService.createWorkflow(name, code, category, islandId)`.

Current sequence:
1. INSERT `workflow` without Island.
2. Call `saveGraph(id, blankGraphXml(...))`.
3. In a separate connection, write `workflow.attr.island_id`.

Implication: adding a mandatory Island check inside `saveGraph` will break creation unless the initial row already has its Island. The current sequence also allows a partially created Workflow if later steps fail.

Required change:
- Create the Workflow row with its authoritative Island in the same transaction as its initial graph, start/end nodes, and edge.
- Prefer a real `workflow.island_id` column with FK/index; if a transitional JSONB field is retained, it must be read/written under the same transaction and treated as authoritative. Do not keep URL query state as authority.
- Do not call the public saveGraph transaction from inside createWorkflow. Extract a connection-scoped internal graph writer/validator so creation and graph persistence share one transaction.

### 2.2 saveGraph history backup is outside the graph transaction

Evidence: `saveGraph()` calls `backupGraph(workflowId)` before opening its transaction.

Implication: a future Island validation failure, SQL failure, or rollback can still leave a history row for a graph version that was never replaced. Concurrent saves can also produce misleading history order.

Required change:
- Lock the Workflow row first (`SELECT ... FOR UPDATE`).
- Validate against the locked authoritative Island.
- Insert history snapshot and replace nodes/edges/XML in the same transaction.
- Commit history and graph together.
- Use tenant-scoped predicates for all workflow/node reads and writes.

### 2.3 Node binding already uses one transaction, but lacks boundary validation

Evidence: `QueryService.updateNodeBinding(..., resourceId)` starts a transaction and updates `workflow_node`, then patches `workflow.graph->>'xml'`, then commits.

Required change:
- Before either update, lock and load the Workflow's authoritative Island.
- Load/validate the target resource in the same tenant and verify its kind and Island.
- Verify the node exists and is a physical task; do not permit a resource binding on a logical builtin node.
- If validation fails, perform no writes.
- Preserve the current useful property that table binding and XML binding commit atomically.
- Note separately: current code treats null resourceId as SQL NULL in the node row even though its Javadoc says null means “do not change binding”; reconcile this API contract before implementation. This is adjacent to, but distinct from, Island enforcement.

### 2.4 Experiment graph save has a useful transaction boundary

Evidence: `QueryService.saveExperimentGraph()` writes `experiment_graph`, validates workflow IDs, replaces `experiment_process`, bumps version, then commits/rolls back one connection.

Required change:
- Keep this atomic structure.
- Extend validation to lock/load the Experiment Island and all referenced Workflow Islands in the same transaction.
- Reject before replacing processes if the Experiment is missing, unassigned, or any referenced Workflow is unassigned/different-Island.
- Ensure the experiment graph and process rows both roll back on mismatch.

### 2.5 Experiment creation and process replacement need ownership validation

Evidence:
- `createExperiment(..., islandId, workflowIds)` inserts Experiment and replaces processes in one transaction.
- `replaceExperimentProcesses(experimentId, workflowIds)` validates workflow existence, then replaces process rows in one transaction.
- Existing `validateExperimentWorkflowIds` only checks positive IDs and tenant-local existence; it does not load either Island.

Required change:
- For create: validate Island exists/tenant-owned, insert Experiment, then validate each Workflow Island against the newly inserted Experiment before process writes; all in one transaction.
- For replacement: lock the Experiment row, validate every Workflow Island against it, then delete/insert process rows.
- Do not trust an Experiment Island supplied by the client after creation.

## 3. Domain API proposal

Keep the validator pure in outcome and explicit in failure; it may use a repository/connection for facts.

Suggested types (names illustrative):

```java
enum BoundaryStatus { VALID, INVALID, UNKNOWN }

record BoundaryViolation(
    String code,
    String subjectType,
    String subjectId,
    Long expectedIslandId,
    Long actualIslandId,
    String detail
) {}

record BoundaryResult(
    BoundaryStatus status,
    List<BoundaryViolation> violations
) {}
```

Suggested service methods:

```java
BoundaryResult validateWorkflowGraph(
    Connection c, long tenantId, long workflowId,
    BpmnGraphParser.ParsedGraph graph);

BoundaryResult validateNodeBinding(
    Connection c, long tenantId, long workflowId,
    String nodeKey, Long resourceId);

BoundaryResult validateExperimentWorkflows(
    Connection c, long tenantId, long experimentId,
    Collection<Long> workflowIds);

BoundaryResult validateChoreography(
    Connection c, long tenantId, Long islandId,
    List<WorkflowBuilder.Step> steps);
```

Implementation requirements:
- The validator must not open its own connection. The caller owns the transaction and passes its connection.
- Distinguish INVALID from UNKNOWN. SQL failure must propagate as an error/UNKNOWN; it must never be converted into an empty resource list.
- Lock rows in a consistent order to reduce deadlocks: Experiment, then Workflows sorted by ID, then resource/topology rows sorted by ID.
- Use tenant-scoped queries for every entity.
- Return actionable violations (node key, resource ID, expected/actual Island), not only a generic “invalid Island”.
- The validator must not mutate data.

## 4. Authoritative Island field and legacy migration

Preferred target:
- Add nullable `workflow.island_id` with FK to the Island/resource topology table and an index on `(tenant_id, island_id)`.
- Backfill from `workflow.attr->>'island_id'` only when the value parses and references an Island owned by the same tenant.
- Keep JSON attr temporarily as a compatibility mirror; reads must prefer the column. Writes update both in the same transaction until old consumers are removed.
- Do not infer Island from the first device node and silently assign it. That inference may be offered as a migration suggestion, not an automatic mutation.
- Existing null/invalid values become `UNASSIGNED` in application projection.
- Before enforcement rollout, produce a read-only inventory: total Workflows; valid attr Island; missing attr; malformed attr; invalid/foreign Island; physical nodes with no resource; nodes whose resource Island differs; referenced Experiments and their Island.
- Migration is staged: schema/backfill/report first; resolve legacy data; enable write enforcement; then enable publish/run enforcement. No destructive migration.

If the existing schema already has an equivalent authoritative column or migration, verify it before adding a duplicate field. This audit has not yet inspected the complete migration history, so the exact DDL is UNKNOWN.

## 5. API and editor contract

### Workflow detail
- Workflow detail response must include authoritative `islandId`, `islandStatus` (`ASSIGNED`/`UNASSIGNED`), and optionally an Island label.
- On opening an existing Workflow, the editor loads detail first and treats server Island as immutable.
- URL `islandId` is only a creation hint; it cannot override existing Workflow ownership.
- If detail is UNASSIGNED, show an explicit assignment/migration flow; do not load all devices.

### Device pool
- For a bound Workflow, request only its Island's devices.
- Remove/disable “clear Island” for an existing bound Workflow.
- API should support a strict mode or a workflow-scoped device pool endpoint. A missing Island must not mean “all devices” in the editor path.
- General inventory endpoints may still expose all devices to authorized admin screens; they must not be reused as a Workflow palette fallback.

### Drop and graph behavior
- Treat drag payload `islandId` as untrusted presentation metadata.
- Before creating any shape, compare server-loaded Workflow Island with the resource's actual Island.
- Save `islandId` in `LastDropped`; compare previous and new physical task Islands.
- On mismatch, reject before `createShape` or `connect`; the graph must remain byte-for-byte unchanged.
- For two different physical devices, require an eligible same-Island robot and explicit reachability for both devices before adding a Transfer node.
- If no robot or reachability is UNKNOWN, do not fall back to a direct SequenceFlow.
- Logical nodes do not change Workflow Island and do not themselves require a robot.

## 6. Write/compile/run enforcement matrix

| Entry point | Required enforcement |
|---|---|
| Workflow create | Validate Island; create row + Island + blank graph atomically |
| Workflow detail | Return authoritative Island |
| Designer drop | Early rejection; no partial shape/edge |
| saveGraph | Parse then validate every physical task against locked Workflow Island before writes |
| node binding PUT/POST | Validate resource before updating DB/XML |
| graph restore | Reuse saveGraph validator; restore cannot bypass policy |
| compile/preflight/publish | Revalidate graph, capabilities, transfer robot and reachability |
| direct Workflow run | Load authoritative Island; do not let query parameter widen/override it |
| choreography build | Derive or require Island; validate every resource and robot transition server-side |
| Experiment create | Validate all referenced Workflow Islands in same transaction |
| replaceExperimentProcesses | Lock Experiment and validate all Workflow Islands before deleting rows |
| saveExperimentGraph | Same check in existing transaction before graph/process replacement |
| Experiment execution | Revalidate Experiment → Workflow → resource Island chain before dispatch |

Do not rely on `WorkflowService.withIsland()` as the boundary. Its current empty-candidate fallback to global candidates is incompatible with a safety boundary and must be removed/replaced by a strict result (empty = configuration failure / waiting resource, never global fallback).

## 7. Minimal tests (must run before enabling enforcement)

### Unit tests
- Validator: same Island valid; different Island invalid; missing resource invalid; tenant mismatch invalid; unknown topology UNKNOWN; logical node exempt from device binding.
- Experiment validator: same Island all valid; one cross-Island Workflow invalid; unassigned Experiment/Workflow invalid.
- Robot transfer validator: same Island and both endpoints reachable valid; one endpoint unreachable invalid; reachability missing/unknown UNKNOWN.

### Database/API integration tests
1. Create Workflow with Island A: row, Island and blank graph all commit together.
2. Force initial graph write failure: no Workflow row remains.
3. Existing Workflow A saveGraph with device B: reject; graph/nodes/edges/history unchanged.
4. SaveGraph valid graph: graph and history snapshot commit together.
5. Node binding A→B resource: reject; XML and workflow_node unchanged.
6. Node binding null semantics: test the documented “clear” vs “no change” behavior explicitly.
7. Restore historical graph containing B resource into Workflow A: reject; current graph unchanged.
8. Experiment A create with Workflow B: reject; no Experiment/process rows remain.
9. Experiment A replace with Workflow B: reject; old process graph remains.
10. Experiment graph save with Workflow B: reject; graph, process rows and version unchanged.
11. Workflow A direct run with query `islandId=B`: reject or ignore B; never execute B devices.
12. Choreography API payload mixes A/B resource IDs: reject before creating a Workflow.

### Frontend tests
- Bound Workflow Island selector is locked and cannot be cleared.
- Dragging B device into A Workflow produces no shape, no edge, and a visible error.
- Drag payload forged with Island A for a real B resource is still rejected by server.
- No-arm / unknown-reachability case does not create a direct connection.

## 8. Implementation sequence (small PRs)

**PR-1: Schema and read-only inventory**
- Inspect complete migration history and topology FK semantics.
- Add authoritative Workflow Island field only if no equivalent exists.
- Backfill safely from valid attr.
- Add inventory/report command; no enforcement yet.

**PR-2: Validator and transactional write paths**
- Implement connection-scoped validator.
- Fix createWorkflow transaction ordering.
- Move graph history backup into saveGraph transaction.
- Enforce saveGraph, node binding, restore, Experiment create/replace/save.
- Add DB/API tests. No frontend dependency for correctness.

**PR-3: Editor UX**
- Load Workflow detail and lock Island.
- Strict device palette; drop-time Island validation.
- Preserve previous node Island in drag state.
- Refuse no-arm/unknown-reachability direct links.
- Add UI tests.

**PR-4: Compile and runtime boundary**
- Strict Island-bound device resolution; remove global fallback.
- Enforce direct run, choreography and Experiment execution.
- Revalidate configuration snapshot before dispatch.

PR-1/2 should not be mixed with Hold scheduler refactoring. Keep Hold tri-state/per-candidate dispatch as a separate change set.

## 9. Explicit non-goals and unresolved facts

- This specification does not claim the current schema lacks an Island column; the complete migration history is not yet inspected.
- It does not assume all robots in an Island can reach all devices.
- It does not implement path planning, collision checking, workspace-zone mutual exclusion, or physical transfer confirmation.
- It does not treat an Island as a single-robot exclusive lock.
- It does not authorize physical execution changes without simulation and hardware-in-the-loop tests.
- Exact HTTP status mapping (400 vs 409 vs 422) remains an API convention decision; all boundary violations must nevertheless be machine-readable and non-2xx.

---

## 裁决影响（2026-11-07，以 main 执行基线为准）

### SUPERSEDED（作废，不得实施）

- §1 invariant 4「A physical Experiment belongs to exactly one Island and may reference only Workflows assigned to that same Island」**整条作废**。裁决：Experiment 允许跨 Workcell。
- §2.5 中「validate every Workflow Island against it（要求相等）」作废，改见 `ADR-ISLAND-002`。
- §3 `validateExperimentWorkflows(...)` 的「Island 相等」语义作废；应改为跨 Workcell 组合合法性校验。
- §6 矩阵中 Experiment create / replace / save / execution 四行的「同 Island」要求作废。
- §7 集成测试 8 / 9 / 10（「Experiment A 引用 Workflow B 必须拒绝」）**期望反转**：应允许，但必须校验每个 Workflow 有权威 Workcell、跨 Workcell 连接具备显式 Handoff 与物理证据。

### 保留并强化

- §1 invariant 1/2/3/5/6/7、§2.1~2.4 事务缺陷、§4 权威字段、§5 编辑器契约、§6 中 Workflow 侧强制项、§7 测试 1~7/11/12、§8 PR 顺序（PR-1/2 不与 Hold 重构混合）。
- Workflow 单 Workcell 约束升级为 `ADR-ISLAND-001`。

### 新增强制项

- `workflow.island_id` 与实验的多 Workcell 关联必须分开建模，禁止复用 `experiment.island_id` 单列表达跨 Workcell 实验。
- 执行上下文的 Workcell **取自当前 Workflow 的权威归属**，不得取自 `experiment.island_id`。
