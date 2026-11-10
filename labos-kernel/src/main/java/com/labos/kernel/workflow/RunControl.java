package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.RunControlMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

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

    private static RunControlMapper mapper() {
        return Mappers.get(RunControlMapper.class);
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
        ) {
            return infoTx(c, runId, false);
        }
    }

    /** 同 {@link #info(long)}，但复用调用方事务连接；可选对 run/experiment 行加锁。 */
    public RunInfo infoTx(Connection c, long runId) throws SQLException {
        return infoTx(c, runId, true);
    }

    private RunInfo infoTx(Connection c, long runId, boolean forUpdate) throws SQLException {
        // c 形参保留（公共/包级签名），语句走 RunControlMapper：在事务内会复用
        // 当前绑定连接（同 DataSource 事务同步），不在事务内时自动开 autocommit session。
        Map<String, Object> m = mapper().selectRunInfo(tenantId, runId, forUpdate);
        if (m == null) {
            throw new IllegalArgumentException("实验运行记录不存在：run#" + runId);
        }
        return new RunInfo(runId, ((Number) m.get("experimentId")).longValue(),
                ((Number) m.get("islandId")).longValue(),
                (String) m.get("status"),
                Boolean.TRUE.equals(m.get("stopRequested")),
                (String) m.get("pauseKind"), (String) m.get("pauseReason"));
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
        markControlRequested(useScope, scopeId, kind.code(), reason);
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

    /**
     * P-06 V2 收尾：把「已受理超过 {@code staleSeconds} 秒、仍在 running」的 run
     * 标成 {@code control_state=PAUSING}（可观测中间态）。
     *
     * <p>只做展示层状态推进：真正 paused/NONE 仍由执行器动作边界 settle 完成，
     * 这里绝不写 paused（否则又是假收敛）。queued run 由
     * {@link #convergeQueuedPauses} 直接收敛，不经过 PAUSING。
     *
     * @return 本次标记条数
     */
    public static int markPausing(com.labos.kernel.persistence.DbConfig db,
                                  long tenantId, int staleSeconds) throws SQLException {
        return mapper().markPausing(tenantId, Math.max(0, staleSeconds));
    }

    /**
     * 把扣留覆盖范围内、还在活动的 run 标记为「暂停请求已受理」（S-04-B）。
     *
     * <p><b>不再立即写 {@code status='paused'}</b>：请求被受理时设备动作可能还在途，
     * 此时显示 PAUSED 是假收敛（旧版 {@code markCoveredPaused} 的问题）。
     * 现在只写 {@code control_state=PAUSE_REQUESTED} + pause_kind/reason，
     * status 保持 running/queued；真正收敛来自两处：
     * <ul>
     *   <li>正在跑的 run：执行器在动作边界看到 Hold 后 {@code settle(..., "paused")}；</li>
     *   <li>排队未派发的 run：调度器每 tick 的 {@link #convergeQueuedPauses} 直接收成 paused。</li>
     * </ul>
     */
    private void markControlRequested(RuntimeHold.Scope scope, Long scopeId,
                                      String kind, String reason) throws SQLException {
        try (Connection c = db.open()) {
            markControlRequestedTx(c, tenantId, scope, scopeId, kind, reason);
        }
        // 收敛时再同步 experiment 表：由执行器 settle（running run）或
        // convergeQueuedPauses（queued run）调用 ExperimentStateProjector.syncPausedByRuns，
        // 保证「experiment_run 已 paused」和「experiment 已 paused」同一时刻发生。
    }

    /**
     * 同 {@link #markControlRequested}，但复用调用方事务连接（P-06 V2：控制状态与
     * Hold / pause_event / 幂等键同事务提交）。
     */
    static void markControlRequestedTx(Connection c, long tenantId,
                                       RuntimeHold.Scope scope, Long scopeId,
                                       String kind, String reason) throws SQLException {
        // c 形参保留（同事务写集语义），动态 scope 过滤收进 XML <choose>，
        // scopeId 不再字符串拼接（顺带消掉拼接注入面）。
        mapper().markControlRequested(tenantId, scope.code(), scopeId, kind, reason);
    }

    /**
     * S-04-B 收敛兜底：暂停请求已受理、但 run 还排在队列里没被派发的，
     * 由调度器每 tick 直接收成 paused（排队中的 run 没有在途动作，可以立即收敛）。
     *
     * <p>正在跑的 run 不在这里处理：执行器必须在动作边界自己 settle，
     * 否则会把设备动作中途「假暂停」。
     *
     * @return 本次收敛的 run 条数
     */
    public static int convergeQueuedPauses(com.labos.kernel.persistence.DbConfig db,
                                           long tenantId) throws SQLException {
        try (Connection c = db.open()) {
            int n = mapper().convergeQueuedPauses(tenantId);
            if (n > 0) {
                ExperimentStateProjector.syncPausedByRuns(c, tenantId);
            }
            return n;
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
            return mapper().requestStop(tenantId, run.runId()) > 0;
        }
        // D10：批次 run 的实验状态必须按 D6 用所有批次聚合（走 D8 回写），
        // 不能让"这一条 run aborted"直接覆盖 experiment.status；
        // 非批次 run 保持现有逻辑（同连接里 syncExperimentStatus，行为不变）。
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        boolean changed = mapper().abortRun(tenantId, run.runId()) > 0;
        boolean batchRun;
        try (Connection c = db.open()) {
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

    /** 事务化停止结果：changed + 是否批次 run（批次回调需在 commit 后做）。 */
    public record StopTx(boolean changed, boolean batchRun) {
    }

    /**
     * P-06 V2 第三切片：停止写集在调用方事务内完成。
     *
     * <p>running 分支只落 {@code stop_requested=true}（在途动作由执行器在动作边界收尾）；
     * 非 running 分支落 aborted + 非批次时同步实验状态；批次聚合在 commit 后补。
     */
    public StopTx stopTx(Connection c, RunInfo run, Long by) throws SQLException {
        if ("running".equals(run.status())) {
            // 事务内：mapper 经 DataSource 事务同步复用 c 这条绑定连接
            return new StopTx(mapper().requestStop(tenantId, run.runId()) > 0, false);
        }
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        boolean changed = mapper().abortRun(tenantId, run.runId()) > 0;
        boolean batchRun = orchestrator.batchRunIdFor(run.runId()) != null;
        if (!batchRun) {
            ExperimentExecutor.syncExperimentStatus(c, tenantId, run.runId());
        }
        return new StopTx(changed, batchRun);
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
        RuntimeHold.Active covered = holds.coveringForExperimentChecked(run.experimentId());

        boolean cleared = false;
        if (covered != null && clearableByExperimentResume(covered.scope())) {
            cleared = holds.clearKind(RuntimeHold.Scope.of(covered.scope()), covered.scopeId(),
                    RuntimeHold.Kind.of(covered.kind()), by, note);
        }

        RuntimeHold.Active still = holds.coveringForExperimentChecked(run.experimentId());
        if (still != null) {
            return new ResumeOutcome(run.experimentId(), cleared,
                    still.reason() == null ? "现场扣留" : still.reason());
        }

        // 拉回待派发：paused / failed 都允许恢复（failed = 错误处理完成后继续）。
        // D10：批次 run 只回写批次层（D8 按 D6 聚合实验状态）；单条 run 的 queued
        // 不能直接当实验状态写 —— 否则其他批还 pending 时实验就先显示 running/终态。
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        boolean batchRun;
        try (Connection c = db.open()) {
            mapper().requeueRun(tenantId, run.runId(), true);
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
        mapper().abortOrphanPausedRuns(tenantId, run.runId());

        return new ResumeOutcome(run.experimentId(), cleared, null);
    }

    /** 事务化恢复结果：outcome + 是否批次 run（批次回调需在 commit 后做）。 */
    public record ResumeTx(ResumeOutcome outcome, boolean batchRun) {
    }

    /**
     * P-06 V2：恢复写集在调用方事务内完成（解 Hold / run 回 queued / 实验表同步 /
     * 作废旧 paused workflow run / 事务内复查扣留）。
     *
     * <p>批次聚合（{@code BatchOrchestrator.onRunStatusChanged}）与调度器重新入队
     * 必须在 commit 之后做（前者可能跨多条 run 聚合、后者是内存队列）；调用方拿
     * {@link ResumeTx#batchRun()} 决定是否补批次回调。commit 后崩溃由启动 rehydrate
     * 兜底 queued run，不会丢。
     */
    public ResumeTx resumeTx(Connection c, RunInfo run, Long by, String note)
            throws SQLException {
        RuntimeHold holds = new RuntimeHold(db, tenantId);
        RuntimeHold.Active covered = holds.coveringForExperimentCheckedTx(c, run.experimentId());
        boolean cleared = false;
        if (covered != null && clearableByExperimentResume(covered.scope())) {
            cleared = holds.clearTx(c, RuntimeHold.Scope.of(covered.scope()),
                    covered.scopeId(), by, note);
        }

        RuntimeHold.Active still = holds.coveringForExperimentCheckedTx(c, run.experimentId());
        if (still != null) {
            ResumeOutcome outcome = new ResumeOutcome(run.experimentId(), cleared,
                    still.reason() == null ? "现场扣留" : still.reason());
            return new ResumeTx(outcome, false);
        }

        // 事务内：mapper 复用 c 这条绑定连接；clearPauseRequested=false 与原
        // 裸 SQL 语义一致（不清 pause_requested_at，非事务路径才清）
        mapper().requeueRun(tenantId, run.runId(), false);
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, tenantId);
        boolean batchRun = orchestrator.batchRunIdFor(run.runId()) != null;
        if (!batchRun) {
            ExperimentExecutor.syncExperimentStatus(c, tenantId, run.runId());
        }

        // ★ 作废「被本次暂停中断、即将被整段重跑」的流程级 run（M-01 契约）。
        mapper().abortOrphanPausedRuns(tenantId, run.runId());

        ResumeOutcome outcome = new ResumeOutcome(run.experimentId(), cleared, null);
        return new ResumeTx(outcome, batchRun);
    }
}
