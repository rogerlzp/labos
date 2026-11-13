package com.labos.kernel.workflow;

import com.labos.kernel.persistence.RunMapper;
import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暂停 / 恢复 / 停止 的<b>统一适配层</b>（S-02 / P-06，ADR-PAUSE-007）。
 *
 * <p>设计口径：<b>不新建第三套子系统</b>。它是一层薄适配 —— 按 {@link PauseCommand}
 * 的目标模型路由到既有的两条控制链，不重写状态语义、不直接控制设备协议、
 * 不伪造物理停止、不写最终 {@code PAUSED} 事实：
 *
 * <pre>
 *   业务入口（RunApi / ExperimentApi）
 *          │  PauseCommand（统一请求对象）
 *          ▼
 *      PauseCoordinator ── 路由 / 幂等键（V1-5）
 *          ├── Workflow Run   ──▶ PauseManager                 （run.status / pause_kind）
 *          └── Experiment Run ──▶ RunControl + RuntimeHold     （experiment_run + runtime_hold）
 * </pre>
 *
 * <p><b>外部语义不变</b>（V1 硬约束）：各方法返回既有控制器原样的结果字段，
 * 由入口拼装原有的 JSON —— 响应体逐字段与改动前一致。{@code RunControl.resume()}
 * 里的 M-01「作废旧 paused workflow run」语句原样保留（那是真停契约的一部分）。
 *
 * <p><b>V1-5 幂等</b>（ADR-PAUSE-009）：请求带 {@code X-Request-Id} 时，按
 * {@code pause_request} 表去重 —— 同 {@code requestId} 重复请求不再建 Hold /
 * 不再下发，直接回放首次结果。取不到该头（{@code requestId} 为空）时<b>完全不参与
 * 去重</b>，与改动前行为逐字段一致。
 *
 * <p>本类无连接状态，按请求构造即可（与 {@code PauseManager} / {@code RunControl} 同惯例）。
 */
public final class PauseCoordinator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PauseCoordinator.class);

    private final DbConfig db;
    private final long tenantId;
    /** 续跑 Workflow 级 run 的执行体（E-2）。 */
    private final WorkflowService workflows;
    /** 实验级恢复后重新入队（E-6）。 */
    private final ExperimentScheduler scheduler;

    public PauseCoordinator(DbConfig db, long tenantId,
                            WorkflowService workflows, ExperimentScheduler scheduler) {
        this.db = db;
        this.tenantId = tenantId;
        this.workflows = workflows;
        this.scheduler = scheduler;
    }

    // ==================================================== V1-5 幂等（ADR-PAUSE-009）

    /**
     * 幂等键登记结果。
     *
     * @param firstTime   true=首次请求（应执行）；false=重复请求（应回放，不再执行）
     * @param resultState 重复请求时：首次的处置结果；首次请求时为 null
     * @param kind        重复请求时：首次请求存的 {@code kind}；首次请求时为 null
     * @param detail      重复请求时：首次结果的关键字段（回放用）
     */
    private record Claim(boolean firstTime, String resultState, String kind,
                         Map<String, String> detail) {
        static Claim first() {
            return new Claim(true, null, null, Map.of());
        }
    }

    private static com.labos.kernel.persistence.PauseMapper mapper() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.PauseMapper.class);
    }

    /**
     * 登记本次请求：带 {@code requestId} 时按 {@code (tenant_id, request_id)} 去重。
     *
     * <p>用 {@code INSERT ... ON CONFLICT DO NOTHING} 抢占：插入成功即首次；
     * 冲突即重复（读回首次结果）。无 {@code requestId} 时一律按首次处理 —— 旧行为。
     * 事务内调用时（claimTx）经 DataSource 同步复用绑定连接。
     */
    private Claim claim(PauseCommand cmd) throws SQLException {
        String rid = cmd.requestId();
        if (rid == null || rid.isBlank()) {
            return Claim.first();
        }
        if (mapper().insertPauseRequest(tenantId, rid, cmd.targetType().name(),
                cmd.targetId(), cmd.action().name(), cmd.source(), cmd.kind(),
                cmd.reasonText(), cmd.requestedBy()) == 1) {
            return Claim.first();
        }
        // 冲突 ⇒ 重复请求：读回首次结果用于回放
        Map<String, Object> row = mapper().selectPauseRequestResult(tenantId, rid);
        if (row != null) {
            return new Claim(false, (String) row.get("resultState"),
                    (String) row.get("kind"),
                    parseDetail((String) row.get("resultDetail")));
        }
        // 竞态窗口：插入冲突但读不到（极少）。保守按首次处理，宁可重放一次也不阻断。
        return Claim.first();
    }

    /** 回写首次结果（幂等回放用）。无 {@code requestId} 时为空操作。 */
    private void settle(PauseCommand cmd, Map<String, String> detail) throws SQLException {
        String rid = cmd.requestId();
        if (rid == null || rid.isBlank()) {
            return;
        }
        mapper().updatePauseRequestResult(tenantId, rid, cmd.resultState().name(),
                encodeDetail(detail));
    }

    /**
     * 执行失败时撤回登记：让客户端带同一 {@code requestId} 重试能<b>真正重跑</b>，
     * 而不是被回放成一个「从未执行过」的请求。
     */
    private void abandon(PauseCommand cmd) {
        String rid = cmd.requestId();
        if (rid == null || rid.isBlank()) {
            return;
        }
        try {
            com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.PauseMapper.class)
                    .deletePauseRequest(tenantId, rid);
        } catch (RuntimeException e) {
            log.error("[暂停] 幂等键撤回失败：" + e.getMessage());
        }
    }

    /** 同 {@link #claim}；c 形参保留（Tx 事务内调用，mapper 复用绑定连接）。 */
    private Claim claimTx(Connection c, PauseCommand cmd) throws SQLException {
        return claim(cmd);
    }

    /** 同 {@link #settle}；c 形参保留（Tx 事务内调用）。 */
    private void settleTx(Connection c, PauseCommand cmd, Map<String, String> detail)
            throws SQLException {
        settle(cmd, detail);
    }

    /** Outbox 事件：与 Hold/control_state/pause_request 同事务写入。 */
    private void insertPauseEvent(Connection c, PauseCommand cmd, String eventType,
                                  String scope, Long scopeId) throws SQLException {
        String payload = "{\"scope\":" + (scope == null ? "null" : "\"" + scope + "\"")
                + ",\"scopeId\":" + (scopeId == null ? "null" : scopeId) + "}";
        mapper().insertPauseEvent(tenantId, cmd.requestId(), eventType,
                cmd.targetType().name(), cmd.targetId(), scope, payload);
    }

    private static String encodeDetail(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=')
              .append(java.net.URLEncoder.encode(e.getValue(),
                      java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Map<String, String> parseDetail(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) {
            return m;
        }
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) {
                continue;
            }
            m.put(kv.substring(0, i), java.net.URLDecoder.decode(
                    kv.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int intOf(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longOf(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolOf(String s, boolean def) {
        return s == null ? def : Boolean.parseBoolean(s);
    }

    // ==================================================== Workflow Run（E-1 ~ E-3）

    /** E-1 结果：Workflow 级暂停的既有关键字段（供入口拼原 JSON）。 */
    public record WorkflowPause(int releasedResources, String pauseKindCode) {
    }

    /**
     * E-1：暂停一个 Workflow 级 run（{@code POST /api/runs/{id}/pause}）。
     *
     * @param nodeKey 停在哪个节点（恢复时从这里继续），可空
     * @return 释放的资源条数 + 实际生效的暂停类型码
     */
    public WorkflowPause pauseWorkflowRun(PauseCommand cmd, String nodeKey) throws SQLException {
        // P-06 V2：run 落 paused + 释放资源 + 幂等键结果 + pause_event 同事务。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Claim claim = claimTx(c, cmd);
            if (!claim.firstTime()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                // 重复请求：不再释放资源（首次已释放），回放首次的暂停类型
                return new WorkflowPause(intOf(claim.detail().get("releasedResources"), 0),
                        PauseManager.Kind.of(claim.kind()).code());
            }
            PauseManager.Kind kind = PauseManager.Kind.of(cmd.kind());
            String reason = cmd.reasonText() == null ? "人工暂停" : cmd.reasonText();
            int released = new PauseManager(db, tenantId)
                    .pauseTx(c, cmd.targetId(), kind, reason, nodeKey);
            // released=0 表示该 run 不在 running（无需暂停），与入口既有行为一致：仍是成功受理。
            cmd.result(PauseCommand.ResultState.COMPLETED);
            settleTx(c, cmd, Map.of("releasedResources", String.valueOf(released),
                    "pauseKind", kind.code()));
            insertPauseEvent(c, cmd, "PAUSE_REQUESTED", null, null);
            return new WorkflowPause(released, kind.code());
        });
    }

    /**
     * E-2：恢复一个 Workflow 级 run（{@code POST /api/runs/{id}/resume}）。
     *
     * <p>{@code cmd.kind()} 在此解释为恢复动作（continue / retry / skip / abort）。
     * PauseManager 只把 run 置回 queued，这里接着真的续跑（已完成节点跳过）。
     *
     * @param corrections 板位校正：barcode → 实际位置，可空
     */
    public PauseManager.ResumeResult resumeWorkflowRun(PauseCommand cmd,
                                                       Map<String, String> corrections)
            throws SQLException {
        // P-06 V2 第七切片：校验/板位校正/run 状态/幂等结果/事件同事务；
        // 异步续跑是内存动作，**commit 后**触发（评审 P1-4：原来放在事务内，
        // resumeAsync 内部事务异常会把外层标记 rollback-only，catch 吞掉后提交时
        // 报 UnexpectedRollbackException，真实原因只进日志、操作员看到数据库措辞）。
        PauseManager.ResumeResult r = com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Claim claim = claimTx(c, cmd);
            if (!claim.firstTime()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                // 重复请求：不再续跑，回放首次结果
                boolean ok = PauseCommand.ResultState.COMPLETED
                        .name().equals(claim.resultState());
                return new PauseManager.ResumeResult(ok, claim.detail().get("error"),
                        cmd.targetId(),
                        PauseManager.ResumeAction.of(claim.kind()).name(),
                        intOf(claim.detail().get("correctedPlates"), 0));
            }
            PauseManager.ResumeAction action = PauseManager.ResumeAction.of(cmd.kind());
            // P-08 强门禁：位置未确认（仅运动报告）时不允许继续/重试，只允许终止
            PauseManager.ResumeResult rr;
            if (action != PauseManager.ResumeAction.ABORT
                    && TransferTransaction.hasUnconfirmed(db, tenantId, cmd.targetId())) {
                rr = new PauseManager.ResumeResult(false,
                        "存在未确认的搬运位置，请先扫码/人工确认"
                                + "（POST /api/transfers/{id}/confirm）",
                        cmd.targetId(), action.name(), 0);
            } else {
                rr = new PauseManager(db, tenantId)
                        .resumeTx(c, cmd.targetId(), action, corrections, "api");
            }
            cmd.result(rr.ok() ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.REJECTED);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("correctedPlates", String.valueOf(rr.correctedPlates()));
            if (rr.error() != null) {
                detail.put("error", rr.error());
            }
            settleTx(c, cmd, detail);
            if (rr.ok()) {
                insertPauseEvent(c, cmd, "RESUME_REQUESTED", null, null);
            }
            return rr;
        });

        // 异步续跑：commit 后触发（内存动作）。失败不滚回已提交的恢复，
        // 但把真实原因带回响应，避免操作员只看到 rollback-only 措辞。
        if (r.ok() && PauseManager.ResumeAction.CONTINUE.name().equals(r.action())
                && workflows != null) {
            try {
                workflows.resumeAsync(cmd.targetId());
            } catch (RuntimeException | SQLException ex) {
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.error("[暂停] 恢复后异步续跑启动失败（run 已 queued）：" + msg);
                return new PauseManager.ResumeResult(false,
                        "恢复已提交，但异步续跑启动失败：" + msg,
                        cmd.targetId(), r.action(), r.correctedPlates());
            }
        }
        return r;
    }

    /**
     * E-3a：批量忽略暂停队列（{@code POST /api/runs/dismiss}）。
     *
     * <p>批量无单一 {@code targetId}，不参与 V1-5 幂等键去重（且不建 Hold）。
     *
     * @param ids         指定单号（非空时优先），可空
     * @param kind        只忽略这个暂停类型，可空
     * @param beforeHours 只忽略「最后更新早于 N 小时前」的单子，可空
     * @return 实际忽略了几条
     */
    public int dismissBatchWorkflowRuns(List<Long> ids, String kind, Integer beforeHours)
            throws SQLException {
        // P-06 V2：批量忽略的多次 UPDATE 同一事务（要么全部忽略，要么一条不动）。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            int n = new PauseManager(db, tenantId)
                    .dismissBatchTx(c, ids, kind, beforeHours);
            return n;
        });
    }

    /** E-3b：忽略单条暂停的 run（{@code POST /api/runs/{id}/dismiss}）。 */
    public boolean dismissWorkflowRun(PauseCommand cmd) throws SQLException {
        // P-06 V2：忽略一次崩溃可恢复的 run + 幂等结果 + pause_event 同事务。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Claim claim = claimTx(c, cmd);
            if (!claim.firstTime()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return PauseCommand.ResultState.COMPLETED
                        .name().equals(claim.resultState());
            }
            boolean done = StartupRecovery.dismissTx(c, tenantId, cmd.targetId());
            cmd.result(done ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.REJECTED);
            settleTx(c, cmd, Map.of());
            insertPauseEvent(c, cmd, "DISMISS_REQUESTED", null, null);
            return done;
        });
    }

    // ==================================================== Experiment Run（E-4 ~ E-6）

    /** E-4 结果：实验级暂停受理后的既有关键字段（供入口拼原 JSON）。 */
    public record ExperimentPause(long runId, long experimentId, String status,
                                  String scopeCode, String kindCode,
                                  String controlState) {
    }

    /** E-5 结果：实验级停止的既有关键字段。 */
    public record ExperimentStop(long runId, long experimentId, boolean changed, String status) {
    }

    /** E-6 结果：实验级恢复的既有关键字段。 */
    public record ExperimentResume(long runId, long experimentId, boolean clearedHold,
                                   boolean requeued, String stillHeldReason, String status) {
    }

    /**
     * E-4：实验级暂停，抬一道现场扣留并把覆盖范围内的 run 置 paused
     * （{@code POST /api/experiments/runs/{id}/pause}）。
     *
     * @param scopeCode 请求作用域（site / island / experiment），可空
     * @return 实际生效作用域等关键字段 —— 接口层必须回「实际生效」值而非请求值
     */
    public ExperimentPause pauseExperimentRun(PauseCommand cmd, String scopeCode)
            throws SQLException {
        // P-06 V2 第一切片：Hold + control_state + pause_request 结果 + pause_event
        // 在同一事务里提交；失败整体回滚（幂等键也一起回滚，客户端可重试）。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Claim claim = claimTx(c, cmd);
            if (!claim.firstTime()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                Map<String, String> d = claim.detail();
                return new ExperimentPause(cmd.targetId(),
                        longOf(d.get("experimentId"), 0L),
                        d.getOrDefault("status", "accepted"),
                        d.get("scopeCode"),
                        d.getOrDefault("kindCode", "manual"),
                        d.getOrDefault("controlState", "PAUSE_REQUESTED"));
            }
            RunControl ctl = new RunControl(db, tenantId);
            RunControl.RunInfo ri = ctl.infoTx(c, cmd.targetId());
            RuntimeHold.Scope scope = RuntimeHold.Scope.of(scopeCode);
            RuntimeHold.Kind kind = cmd.kind() == null
                    ? RuntimeHold.Kind.MANUAL : RuntimeHold.Kind.of(cmd.kind());
            String reason = cmd.reasonText() == null ? "人工暂停" : cmd.reasonText();
            RuntimeHold.Scope effective = RunControl.effectiveScope(scope, ri.islandId());
            Long scopeId = effective == RuntimeHold.Scope.SITE ? null
                    : effective == RuntimeHold.Scope.ISLAND ? ri.islandId()
                    : ri.experimentId();
            new RuntimeHold(db, tenantId).raiseTx(c, effective, scopeId, kind, reason, null);
            RunControl.markControlRequestedTx(c, tenantId, effective, scopeId,
                    kind.code(), reason);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("experimentId", String.valueOf(ri.experimentId()));
            detail.put("scopeCode", effective.code());
            detail.put("kindCode", kind.code());
            // S-04-B：受理时只承诺「请求已接受 + 控制过程开始」，不承诺已物理暂停。
            detail.put("status", "accepted");
            detail.put("controlState", "PAUSE_REQUESTED");
            cmd.result(PauseCommand.ResultState.COMPLETED);
            settleTx(c, cmd, detail);
            insertPauseEvent(c, cmd, "PAUSE_REQUESTED", effective.code(), scopeId);
            return new ExperimentPause(cmd.targetId(), ri.experimentId(), "accepted",
                    effective.code(), kind.code(), "PAUSE_REQUESTED");
        });
    }

    /**
     * E-5：实验级停止（协作式，动作边界生效）
     * （{@code POST /api/experiments/runs/{id}/stop}）。
     */
    public ExperimentStop stopExperimentRun(PauseCommand cmd) throws SQLException {
        // P-06 V2 第三切片：停止写集（stop_requested / aborted / 实验表同步 /
        // pause_request 结果 / STOP_REQUESTED 事件）同事务；批次聚合 commit 后补。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Claim claim = claimTx(c, cmd);
            if (!claim.firstTime()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                Map<String, String> d = claim.detail();
                return new ExperimentStop(cmd.targetId(),
                        longOf(d.get("experimentId"), 0L),
                        boolOf(d.get("changed"), false),
                        d.getOrDefault("status", "aborted"));
            }
            RunControl ctl = new RunControl(db, tenantId);
            RunControl.RunInfo ri = ctl.infoTx(c, cmd.targetId());
            RunControl.StopTx st = ctl.stopTx(c, ri, null);
            String status = !st.changed() ? ri.status()
                    : "running".equals(ri.status()) ? "stopping" : "aborted";
            cmd.result(st.changed() ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.REJECTED);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("experimentId", String.valueOf(ri.experimentId()));
            detail.put("changed", String.valueOf(st.changed()));
            detail.put("status", status);
            settleTx(c, cmd, detail);
            insertPauseEvent(c, cmd, "STOP_REQUESTED", "experiment",
                    ri.experimentId());

            if (st.batchRun()) {
                try {
                    new BatchOrchestrator(db, tenantId)
                            .onRunStatusChanged(cmd.targetId());
                } catch (Exception e) {
                    log.error("[暂停] 停止后批次聚合失败（由启动 rehydrate 兜底）："
                            + e.getMessage());
                }
            }
            return new ExperimentStop(cmd.targetId(), ri.experimentId(),
                    st.changed(), status);
        });
    }

    /**
     * E-6：实验级恢复（解除可解扣留 + 重新入队，等调度器从断点续跑）
     * （{@code POST /api/experiments/runs/{id}/resume}）。
     *
     * @param note 恢复备注，可空
     */
    /**
     * 实验运行（experiment_run）-> 它派出的流程运行（run）id。
     *
     * <p>实验级恢复要续跑的是**流程运行**（run 表），而接口传进来的是
     * **实验运行**（experiment_run 表）—— 两者 id 不同，要反查一次。</p>
     */
    private Long firstWorkflowRunId(long experimentRunId) throws SQLException {
        Long id = com.labos.kernel.persistence.Mappers.get(RunMapper.class)
                .selectWorkflowRunIdOfExperimentRun(tenantId, experimentRunId);
        log.debug("[暂停] 实验运行 " + experimentRunId + " ⇒ 流程运行 " + id);
        return id;
    }

    public ExperimentResume resumeExperimentRun(PauseCommand cmd, String note)
            throws SQLException {
        // P-06 V2 第二切片：解 Hold / run 回 queued / 实验表同步 / 作废旧 paused
        // workflow run / 幂等键结果 / pause_event 同事务；批次聚合与重新入队在 commit 后做。
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Claim claim = claimTx(c, cmd);
            if (!claim.firstTime()) {
                com.labos.kernel.persistence.Tx.rollbackOnly();
                Map<String, String> d = claim.detail();
                return new ExperimentResume(cmd.targetId(),
                        longOf(d.get("experimentId"), 0L),
                        boolOf(d.get("cleared"), false),
                        boolOf(d.get("requeued"), false),
                        d.get("stillHeldReason"),
                        d.getOrDefault("status", "queued"));
            }
            RunControl ctl = new RunControl(db, tenantId);
            RunControl.RunInfo ri = ctl.infoTx(c, cmd.targetId());
            RunControl.ResumeTx rt = ctl.resumeTx(c, ri, null, note);
            RunControl.ResumeOutcome out = rt.outcome();
            boolean requeued = out.stillHeldReason() == null;
            // 现场（site）扣留仍在 => 恢复未真正生效，如实标记 UNKNOWN 而不是假装完成。
            cmd.result(requeued ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.UNKNOWN);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("experimentId", String.valueOf(out.experimentId()));
            detail.put("cleared", String.valueOf(out.cleared()));
            detail.put("requeued", String.valueOf(requeued));
            detail.put("status", requeued ? "queued" : "paused");
            if (out.stillHeldReason() != null) {
                detail.put("stillHeldReason", out.stillHeldReason());
            }
            settleTx(c, cmd, detail);
            insertPauseEvent(c, cmd, "RESUME_REQUESTED", "experiment",
                    out.experimentId());

            // commit 后动作：批次聚合（可能跨 run）；重新入队（内存队列，启动 rehydrate 兜底）。
            if (rt.batchRun()) {
                try {
                    new BatchOrchestrator(db, tenantId)
                            .onRunStatusChanged(cmd.targetId());
                } catch (Exception e) {
                    log.error("[暂停] 恢复后批次聚合失败（由启动 rehydrate 兜底）："
                            + e.getMessage());
                }
            }
            if (requeued) {
                // ★ 实验级恢复必须**真正续跑**，不只是把 run 放回队列。
                //
                // 改动前这里只调 scheduler.resumeRun（重新入队）⇒ 调度器重新拾起时
                // 按 run_node 里**已有的行**走 ⇒ manual 节点被跳过、后续节点没跑
                // ⇒ 界面报 completed，实则只跑了前几步（**假完成**，2026-11-11 实测）。
                //
                // workflows.resumeAsync 做的正是"删掉未完成的节点行 + 用同一个
                // workflow run 重跑图"—— run 级恢复一直走的就是这条路径。
                // 这里补上它，实验级恢复才真的"从卡住的节点接着跑"。
                Long wfRunId = firstWorkflowRunId(cmd.targetId());
                if (wfRunId != null && workflows != null) {
                    try {
                        workflows.resumeAsync(wfRunId);
                    } catch (RuntimeException ex) {
                        log.error("[暂停] 恢复后续跑启动失败：" + ex.getMessage());
                    }
                } else if (scheduler != null) {
                    // 拿不到对应的流程运行（没有 workflow 的实验级运行）⇒ 退回重新入队
                    try {
                        scheduler.resumeRun(out.experimentId(), cmd.targetId());
                    } catch (Exception e) {
                        log.error("[暂停] 恢复后重新入队失败（由启动 rehydrate 兜底）："
                                + e.getMessage());
                    }
                }
            }
            return new ExperimentResume(cmd.targetId(), out.experimentId(),
                    out.cleared(), requeued, out.stillHeldReason(),
                    requeued ? "queued" : "paused");
        });
    }
}
