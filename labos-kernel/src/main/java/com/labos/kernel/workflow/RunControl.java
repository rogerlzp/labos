package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 实验级运行控制：暂停 / 停止 / 恢复。
 *
 * <h3>状态机</h3>
 * <pre>
 *   queued ──▶ running ──▶ completed
 *      │          │  │
 *      │          │  └──▶ failed ──(人工处置后)──┐
 *      │          │                              │
 *      └──────────┴──▶ paused ──(恢复)───────────┴──▶ queued（从断点续跑）
 *                     （扣留期，不释放资源）
 *   running/queued/paused ──(停止)──▶ aborted（终态，不可恢复）
 * </pre>
 *
 * <h3>三条口径</h3>
 * <ol>
 *   <li><b>暂停 = 现场扣留</b>：抛一道 {@code runtime_hold}，不是改某条 run 的状态了事。
 *       一个岛通常一台机械臂，实验暂停时同岛其他实验必须一起停在动作边界
 *       （见 {@link RuntimeHold}）。</li>
 *   <li><b>停止是协作式的</b>：运行中的 run 只置 {@code stop_requested}，
 *       由执行器在下一个<b>动作边界</b>转 {@code aborted}；
 *       非运行态（排队/暂停/失败）才直接落 {@code aborted}——没有动作在进行，不必等。</li>
 *   <li><b>恢复从断点续跑</b>：沿用同一条 run，不清 {@code experiment_run_step}，
 *       已完成（{@code status='completed'}）的步会被跳过，从暂停/失败那一步重跑。</li>
 * </ol>
 */
public final class RunControl {

    private final DbConfig db;
    private final long tenantId;

    public RunControl(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /** 一条 run 的关键信息（含所属实验与岛）。 */
    public record RunInfo(long runId, long experimentId, long islandId, String status,
                          boolean stopRequested, String pauseKind, String pauseReason) {
    }

    /** 恢复结果。 */
    public record ResumeOutcome(long experimentId, boolean cleared, String stillHeldReason) {
    }

    public RunInfo info(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.experiment_id, r.status, r.stop_requested,
                            r.pause_kind, r.pause_reason,
                            COALESCE(e.island_id, 0) AS island_id
                       FROM experiment_run r
                       JOIN experiment e
                         ON e.tenant_id = r.tenant_id AND e.id = r.experiment_id
                      WHERE r.tenant_id = ? AND r.id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("实验运行记录不存在：run#" + runId);
                }
                return new RunInfo(runId, rs.getLong("experiment_id"), rs.getLong("island_id"),
                        rs.getString("status"), rs.getBoolean("stop_requested"),
                        rs.getString("pause_kind"), rs.getString("pause_reason"));
            }
        }
    }

    /**
     * 暂停：抬一道现场扣留，并把受影响、尚在排队/运行的 run 立即置 paused。
     *
     * <p>未分配岛（islandId=0）时岛级扣留匹配不到任何实验，自动退化为实验级，
     * 否则"按了暂停却什么都没停"。
     *
     * @return <b>实际生效</b>的作用域。接口层必须回这个值而不是请求值 ——
     *         回请求值会出现"界面说作用域：岛、库里只有一道实验级扣留"，
     *         操作员会以为整岛都停住了（BUG-20261103-11）。
     */
    public RuntimeHold.Scope pause(RunInfo run, RuntimeHold.Scope scope,
                                   RuntimeHold.Kind kind, String reason, Long by)
            throws SQLException {
        RuntimeHold.Scope useScope = effectiveScope(scope, run.islandId());
        Long scopeId = useScope == RuntimeHold.Scope.SITE ? null
                : useScope == RuntimeHold.Scope.ISLAND ? run.islandId() : run.experimentId();
        new RuntimeHold(db, tenantId).raise(useScope, scopeId, kind, reason, by);
        markCoveredPaused(useScope, scopeId, kind.code(), reason);
        return useScope;
    }

    /**
     * 实际生效范围。
     *
     * <p>岛未分配（{@code islandId=0}）时，岛级扣留匹配不到任何实验
     * （覆盖查询要求 {@code e.island_id = scope_id}，0 不等于任何真实岛），
     * 必须退化成实验级 —— 否则操作员"按了暂停却什么都没停"，
     * 而现场以为自己已经停住了。
     */
    static RuntimeHold.Scope effectiveScope(RuntimeHold.Scope requested, long islandId) {
        if (requested == RuntimeHold.Scope.SITE) {
            return RuntimeHold.Scope.SITE;
        }
        if (requested == RuntimeHold.Scope.ISLAND && islandId > 0) {
            return RuntimeHold.Scope.ISLAND;
        }
        return RuntimeHold.Scope.EXPERIMENT;
    }

    /**
     * 恢复时能顺手解除这条扣留吗。
     *
     * <p>{@code site} 是全场闸门（公共气路故障这类事故），
     * 由一次单实验的"恢复"顺手解除整场闸门是危险的：必须在返回值里
     * 如实告知"仍被现场扣留"，让界面提示先解现场扣留。
     */
    static boolean clearableByExperimentResume(String scopeCode) {
        return !RuntimeHold.Scope.SITE.code().equals(scopeCode);
    }

    /** 把扣留覆盖范围内、还在活动的 run 置 paused（给界面即时反馈；执行器随后同样会停）。 */
    private void markCoveredPaused(RuntimeHold.Scope scope, Long scopeId,
                                   String kind, String reason) throws SQLException {
        String filter;
        if (scope == RuntimeHold.Scope.SITE) {
            filter = "";
        } else if (scope == RuntimeHold.Scope.ISLAND) {
            filter = " AND e.island_id = " + scopeId;
        } else {
            filter = " AND r.experiment_id = " + scopeId;
        }
        String sql = """
                UPDATE experiment_run r
                   SET status = 'paused', pause_kind = ?, pause_reason = ?,
                       paused_at = now()
                  FROM experiment e
                 WHERE r.tenant_id = ? AND e.tenant_id = r.tenant_id
                   AND e.id = r.experiment_id
                   AND r.status IN ('queued', 'running')
                """ + filter;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, kind);
            ps.setString(2, reason);
            ps.setLong(3, tenantId);
            ps.executeUpdate();
        }
        // ⚠ **experiment 表也要跟着改成 paused**（2026-11-06 实测发现）：
        //   上面只改了 experiment_run，experiment.status 停在 'running' ——
        //   实验列表上显示「执行中」，但详情/暂停队列里已经是「已暂停」，
        //   **两处自相矛盾**（现场会以为还在跑，不会去点继续）。
        //   状态写入统一走 ExperimentStateProjector（S-03 单写者收口），映射仍由
        //   StatusVocabulary.runToExperimentCaseSql 生成，与 ExperimentExecutor 同一口径。
        try (Connection c = db.open()) {
            ExperimentStateProjector.syncPausedByRuns(c, tenantId);
        }
    }

    /**
     * 停止。
     *
     * @return 是否产生了变化（已经是终态的 run 返回 false）
     */
    public boolean stop(RunInfo run, Long by) throws SQLException {
        if ("running".equals(run.status())) {
            // 有动作在进行，不能硬杀：只落停止请求，执行器在动作边界收尾
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement("""
                         UPDATE experiment_run SET stop_requested = true
                          WHERE tenant_id = ? AND id = ? AND status = 'running'
                         """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, run.runId());
                return ps.executeUpdate() > 0;
            }
        }
        // D10：批次 run 的实验状态必须按 D6 用所有批次聚合（走 D8 回写），
        // 不能让"这一条 run aborted"直接覆盖 experiment.status；
        // 非批次 run 保持现有逻辑（同连接里 syncExperimentStatus，行为不变）。
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        boolean changed;
        boolean batchRun;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE experiment_run
                        SET status = 'aborted', stop_requested = true, finished_at = now(),
                            error = COALESCE(error, '人工停止')
                      WHERE tenant_id = ? AND id = ?
                        AND status NOT IN ('completed', 'aborted', 'cancelled')
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, run.runId());
            changed = ps.executeUpdate() > 0;
            // 改完 run 后再探测批次归属（探测只读，不改状态）
            batchRun = orchestrator.batchRunIdFor(run.runId()) != null;
            if (!batchRun) {
                ExperimentExecutor.syncExperimentStatus(c, tenantId, run.runId());
            }
        }
        if (batchRun) {
            // D8：attempt 行 + 批次汇总 + 实验 D6 聚合一次补齐
            orchestrator.onRunStatusChanged(run.runId());
        }
        return changed;
    }

    /**
     * 恢复：解除覆盖该 run 的最内层扣留，并把 run 拉回 queued（等调度器从断点续跑）。
     *
     * <p><b>场地级扣留不在这里解除</b>：site 扣留是全场事故的人工动作，
     * 由一次单实验的"恢复"顺手解除整场闸门是危险的。此时保持扣留，
     * 在返回值里如实告知 {@code stillHeldReason}，让界面提示"先解现场扣留"。
     */
    public ResumeOutcome resume(RunInfo run, Long by, String note) throws SQLException {
        RuntimeHold holds = new RuntimeHold(db, tenantId);
        // fail-closed（P-03 / ADR-PAUSE-005）：用 checked 版本，读不到扣留直接抛 SQLException
        // ⇒ 恢复操作整体失败。绝不能把「读不到」当成「没有扣留」而放行 resume。
        RuntimeHold.Active covered = holds.coveringForChecked(run.experimentId(), run.islandId());

        boolean cleared = false;
        if (covered != null && clearableByExperimentResume(covered.scope())) {
            cleared = holds.clear(RuntimeHold.Scope.of(covered.scope()), covered.scopeId(), by, note);
        }

        // 拉回待派发：paused / failed 都允许恢复（failed = 错误处理完成后继续）。
        // D10：批次 run 只回写批次层（D8 按 D6 聚合实验状态）；单条 run 的 queued
        // 不能直接当实验状态写 —— 否则其他批还 pending 时实验就先显示 running/终态。
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        boolean batchRun;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE experiment_run
                       SET status = 'queued', stop_requested = false, error = NULL,
                           pause_kind = NULL, pause_reason = NULL, paused_at = NULL,
                           finished_at = NULL
                     WHERE tenant_id = ? AND id = ?
                       AND status IN ('paused', 'failed', 'queued')
                    """)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, run.runId());
                ps.executeUpdate();
            }
            // 改完 run 后再探测批次归属（探测只读，不改状态）
            batchRun = orchestrator.batchRunIdFor(run.runId()) != null;
            if (!batchRun) {
                // 实验表跟着回到"待执行"，否则列表上还是上一次的终态
                ExperimentExecutor.syncExperimentStatus(c, tenantId, run.runId());
            }
        }
        if (batchRun) {
            // D8：attempt 行 queued、批次 queued，实验按 D6 聚合（单批 queued => running）
            orchestrator.onRunStatusChanged(run.runId());
        }

        // ★ 作废「被本次暂停中断、即将被整段重跑」的**流程级 run**。
        //
        //   实验级暂停会把当前步骤的 workflow 级 run 也落成 paused
        //   （见 WorkflowService.pauseRunAtBoundary）；恢复时这一步会被整段重跑、
        //   另起一条新 run（ExperimentExecutor.runStep 每跑一步都新建 run），
        //   那条旧的 paused run 就成了孤儿 —— 它会一直挂在暂停队列
        //   （PauseManager.pausedRuns 查 run.status='paused'）里，
        //   值班员误点「继续」会让机械臂把同一个动作再做一遍（物理副作用不可撤销）。
        //   所以在恢复时把它作废（aborted），只处理「本实验步 + 仍 paused」的那些，
        //   已完成步骤的 run（completed）不受影响。
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE run r
                        SET status = 'aborted', actual_end = now()
                       FROM experiment_run_step s
                      WHERE r.tenant_id = ? AND s.tenant_id = r.tenant_id
                        AND s.run_id = r.id
                        AND s.experiment_run_id = ?
                        AND s.status = 'paused'
                        AND r.status = 'paused'
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, run.runId());
            ps.executeUpdate();
        }

        RuntimeHold.Active still = holds.coveringForChecked(run.experimentId(), run.islandId());
        return new ResumeOutcome(run.experimentId(), cleared,
                still == null ? null : (still.reason() == null ? "现场扣留" : still.reason()));
    }
}
