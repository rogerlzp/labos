package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    /**
     * 登记本次请求：带 {@code requestId} 时按 {@code (tenant_id, request_id)} 去重。
     *
     * <p>用 {@code INSERT ... ON CONFLICT DO NOTHING} 抢占：插入成功即首次；
     * 冲突即重复（读回首次结果）。无 {@code requestId} 时一律按首次处理 —— 旧行为。
     */
    private Claim claim(PauseCommand cmd) throws SQLException {
        String rid = cmd.requestId();
        if (rid == null || rid.isBlank()) {
            return Claim.first();
        }
        String insert = """
                INSERT INTO pause_request
                  (tenant_id, request_id, target_type, target_id, action,
                   source, kind, reason, requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, request_id) DO NOTHING
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setLong(1, tenantId);
            ps.setString(2, rid);
            ps.setString(3, cmd.targetType().name());
            ps.setLong(4, cmd.targetId());
            ps.setString(5, cmd.action().name());
            ps.setString(6, cmd.source());
            ps.setString(7, cmd.kind());
            ps.setString(8, cmd.reasonText());
            ps.setString(9, cmd.requestedBy());
            if (ps.executeUpdate() == 1) {
                return Claim.first();
            }
        }
        // 冲突 ⇒ 重复请求：读回首次结果用于回放
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT result_state, kind, result_detail FROM pause_request
                      WHERE tenant_id = ? AND request_id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, rid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Claim(false, rs.getString("result_state"),
                            rs.getString("kind"), parseDetail(rs.getString("result_detail")));
                }
            }
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
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE pause_request SET result_state = ?, result_detail = ?
                      WHERE tenant_id = ? AND request_id = ?
                     """)) {
            ps.setString(1, cmd.resultState().name());
            ps.setString(2, encodeDetail(detail));
            ps.setLong(3, tenantId);
            ps.setString(4, rid);
            ps.executeUpdate();
        }
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
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM pause_request WHERE tenant_id = ? AND request_id = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, rid);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[暂停] 幂等键撤回失败：" + e.getMessage());
        }
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
        Claim claim = claim(cmd);
        if (!claim.firstTime()) {
            // 重复请求：不再释放资源（首次已释放），回放首次的暂停类型
            return new WorkflowPause(intOf(claim.detail().get("releasedResources"), 0),
                    PauseManager.Kind.of(claim.kind()).code());
        }
        try {
            PauseManager.Kind kind = PauseManager.Kind.of(cmd.kind());
            String reason = cmd.reasonText() == null ? "人工暂停" : cmd.reasonText();
            int released = new PauseManager(db, tenantId)
                    .pause(cmd.targetId(), kind, reason, nodeKey);
            // released=0 表示该 run 不在 running（无需暂停），与入口既有行为一致：仍是成功受理。
            cmd.result(PauseCommand.ResultState.COMPLETED);
            settle(cmd, Map.of("releasedResources", String.valueOf(released),
                    "pauseKind", kind.code()));
            return new WorkflowPause(released, kind.code());
        } catch (SQLException | RuntimeException e) {
            abandon(cmd);
            throw e;
        }
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
        Claim claim = claim(cmd);
        if (!claim.firstTime()) {
            // 重复请求：不再续跑，回放首次结果
            boolean ok = PauseCommand.ResultState.COMPLETED.name().equals(claim.resultState());
            return new PauseManager.ResumeResult(ok, claim.detail().get("error"),
                    cmd.targetId(),
                    PauseManager.ResumeAction.of(claim.kind()).name(),
                    intOf(claim.detail().get("correctedPlates"), 0));
        }
        try {
            PauseManager.ResumeAction action = PauseManager.ResumeAction.of(cmd.kind());
            PauseManager.ResumeResult r = new PauseManager(db, tenantId)
                    .resume(cmd.targetId(), action, corrections, "api");
            if (r.ok() && action == PauseManager.ResumeAction.CONTINUE) {
                workflows.resumeAsync(cmd.targetId());
            }
            cmd.result(r.ok() ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.REJECTED);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("correctedPlates", String.valueOf(r.correctedPlates()));
            if (r.error() != null) {
                detail.put("error", r.error());
            }
            settle(cmd, detail);
            return r;
        } catch (SQLException | RuntimeException e) {
            abandon(cmd);
            throw e;
        }
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
        return new PauseManager(db, tenantId).dismissBatch(ids, kind, beforeHours);
    }

    /** E-3b：忽略单条暂停的 run（{@code POST /api/runs/{id}/dismiss}）。 */
    public boolean dismissWorkflowRun(PauseCommand cmd) throws SQLException {
        Claim claim = claim(cmd);
        if (!claim.firstTime()) {
            return PauseCommand.ResultState.COMPLETED.name().equals(claim.resultState());
        }
        try {
            boolean done = StartupRecovery.dismiss(db, tenantId, cmd.targetId());
            cmd.result(done ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.REJECTED);
            settle(cmd, Map.of());
            return done;
        } catch (SQLException | RuntimeException e) {
            abandon(cmd);
            throw e;
        }
    }

    // ==================================================== Experiment Run（E-4 ~ E-6）

    /** E-4 结果：实验级暂停受理后的既有关键字段（供入口拼原 JSON）。 */
    public record ExperimentPause(long runId, long experimentId, String status,
                                  String scopeCode, String kindCode) {
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
        Claim claim = claim(cmd);
        if (!claim.firstTime()) {
            // 重复请求：不再抬第二道扣留（关键：Hold 数不增）
            Map<String, String> d = claim.detail();
            return new ExperimentPause(cmd.targetId(),
                    longOf(d.get("experimentId"), 0L),
                    d.getOrDefault("status", "paused"),
                    d.get("scopeCode"),
                    d.getOrDefault("kindCode", RuntimeHold.Kind.of(claim.kind()).code()));
        }
        try {
            RunControl ctl = new RunControl(db, tenantId);
            RunControl.RunInfo ri = ctl.info(cmd.targetId());
            RuntimeHold.Scope scope = RuntimeHold.Scope.of(scopeCode);
            RuntimeHold.Kind kind = cmd.kind() == null
                    ? RuntimeHold.Kind.MANUAL : RuntimeHold.Kind.of(cmd.kind());
            String reason = cmd.reasonText() == null ? "人工暂停" : cmd.reasonText();
            RuntimeHold.Scope effective = ctl.pause(ri, scope, kind, reason, null);
            cmd.result(PauseCommand.ResultState.COMPLETED);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("experimentId", String.valueOf(ri.experimentId()));
            detail.put("scopeCode", effective.code());
            detail.put("kindCode", kind.code());
            detail.put("status", "paused");
            settle(cmd, detail);
            return new ExperimentPause(cmd.targetId(), ri.experimentId(), "paused",
                    effective.code(), kind.code());
        } catch (SQLException | RuntimeException e) {
            abandon(cmd);
            throw e;
        }
    }

    /**
     * E-5：实验级停止（协作式，动作边界生效）
     * （{@code POST /api/experiments/runs/{id}/stop}）。
     */
    public ExperimentStop stopExperimentRun(PauseCommand cmd) throws SQLException {
        Claim claim = claim(cmd);
        if (!claim.firstTime()) {
            Map<String, String> d = claim.detail();
            return new ExperimentStop(cmd.targetId(), longOf(d.get("experimentId"), 0L),
                    boolOf(d.get("changed"), false), d.getOrDefault("status", "aborted"));
        }
        try {
            RunControl ctl = new RunControl(db, tenantId);
            RunControl.RunInfo ri = ctl.info(cmd.targetId());
            boolean changed = ctl.stop(ri, null);
            String st = !changed ? ri.status()
                    : "running".equals(ri.status()) ? "stopping" : "aborted";
            cmd.result(changed ? PauseCommand.ResultState.COMPLETED
                    : PauseCommand.ResultState.REJECTED);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("experimentId", String.valueOf(ri.experimentId()));
            detail.put("changed", String.valueOf(changed));
            detail.put("status", st);
            settle(cmd, detail);
            return new ExperimentStop(cmd.targetId(), ri.experimentId(), changed, st);
        } catch (SQLException | RuntimeException e) {
            abandon(cmd);
            throw e;
        }
    }

    /**
     * E-6：实验级恢复（解除可解扣留 + 重新入队，等调度器从断点续跑）
     * （{@code POST /api/experiments/runs/{id}/resume}）。
     *
     * @param note 恢复备注，可空
     */
    public ExperimentResume resumeExperimentRun(PauseCommand cmd, String note)
            throws SQLException {
        Claim claim = claim(cmd);
        if (!claim.firstTime()) {
            Map<String, String> d = claim.detail();
            return new ExperimentResume(cmd.targetId(), longOf(d.get("experimentId"), 0L),
                    boolOf(d.get("cleared"), false), boolOf(d.get("requeued"), false),
                    d.get("stillHeldReason"), d.getOrDefault("status", "queued"));
        }
        try {
            RunControl ctl = new RunControl(db, tenantId);
            RunControl.RunInfo ri = ctl.info(cmd.targetId());
            RunControl.ResumeOutcome out = ctl.resume(ri, null, note);
            boolean requeued = out.stillHeldReason() == null;
            if (requeued) {
                // 扣留已解除：重新入队，等调度器从断点续跑（闸门未清时会拦住）
                scheduler.resumeRun(out.experimentId(), cmd.targetId());
            }
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
            settle(cmd, detail);
            return new ExperimentResume(cmd.targetId(), out.experimentId(),
                    out.cleared(), requeued, out.stillHeldReason(),
                    requeued ? "queued" : "paused");
        } catch (SQLException | RuntimeException e) {
            abandon(cmd);
            throw e;
        }
    }
}
