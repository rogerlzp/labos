package com.labos.kernel.workflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 实验展示状态 {@code experiment.status} 的<b>唯一写入者</b>（S-03 / ADR-PAUSE-002、ADR-PAUSE-003）。
 *
 * <p>目标职责划分：<b>入口只写事实，状态由聚合器投影</b>。改动前 {@code experiment.status}
 * 有 5 处分散的直接 {@code UPDATE}（{@code RunControl} 1 处、{@code ExperimentExecutor} 2 处、
 * {@code BatchOrchestrator} 2 处），各自拼 SQL，迟早漂移出「列表显示已完成、详情显示失败」
 * 这类自相矛盾。本类把这些写入<b>全部收口</b>到一处：调用方只表达「发生了什么事实」，
 * 由本类统一算并写投影。
 *
 * <p>本类只做「职责收口」，<b>不改变任何既有语义</b>：每条方法里的 SQL 都是从原调用点
 * <b>原样搬过来</b>的，调用方、参数、过滤条件、事务边界都不动。run→experiment 的映射
 * 仍由 {@link StatusVocabulary#runToExperimentCaseSql()} 从唯一映射表生成（唯一真源），
 * 本类只负责执行它 —— 这就是 S-03 要求的「{@code StatusVocabulary} CASE 回写收口」。
 *
 * <p><b>不变量（由 {@code ExperimentStateProjectorSelfTest} 扫描源码强制）</b>：
 * {@code src/main/java} 下除本类外，任何文件都不得出现
 * {@code UPDATE experiment … SET status} 形态的 SQL。
 *
 * <p>本类<b>不是</b>完整的「由四组事实聚合状态」平台（那是 L-05，标记 DEFERRED）：它只负责把
 * 既有的投影写入收口到一处，为后续（S-04-B / P-06 V2）把投影真正改成由 Hold + 节点 + 命令 +
 * 人工证据计算留出唯一入口。
 */
public final class ExperimentStateProjector {

    /** 单写者不变量自测用的类名标识（勿改；改了要同步 {@code ExperimentStateProjectorSelfTest}）。 */
    public static final String SOLE_WRITER = "ExperimentStateProjector";

    private ExperimentStateProjector() {
    }

    /**
     * 事实：一条 {@code experiment_run} 起跑/恢复。
     * 投影：其所属 {@code experiment} 标 {@code running}（列表不再一直停在「待执行」）。
     *
     * <p>源自 {@code ExperimentExecutor.markRunning} 的内联 SQL（原样搬运）。
     */
    public static void markRunningByRun(Connection c, long tenantId, long runId)
            throws SQLException {
        // 不写 run_id：experiment.run_id 有外键指向 run(id)，而这里的 r.id 是
        // experiment_run.id —— 两张表各自独立自增、不是一个 id 空间；写进去既语义错，
        // 又会在 experiment_run.id 撞到 run 表 id 空洞时抛 23503（线上实测过）。
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE experiment e
                   SET status='running',
                       started_at=COALESCE(e.started_at, now()), updated_at=now()
                  FROM experiment_run r
                 WHERE e.tenant_id=? AND r.id=? AND e.id=r.experiment_id
                """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.executeUpdate();
        }
    }

    /**
     * 事实：一条 {@code experiment_run} 到达终态/暂停态。
     * 投影：按 {@link StatusVocabulary} 的 run→experiment 映射同步到 {@code experiment}。
     *
     * <p>源自 {@code ExperimentExecutor.syncExperimentStatus} 的内联 SQL（原样搬运）。
     */
    public static void syncByRun(Connection c, long tenantId, long runId) throws SQLException {
        // 状态映射**不在这里手写**（BUG-13/14 这类"词表不一致"的根）：由
        // StatusVocabulary.runToExperimentCaseSql() 从唯一映射表生成。生成的 CASE 比
        // 三行手写 CASE 多出 paused/aborted/… 等恒等 WHEN，其 THEN 值与 ELSE r.status
        // 相同 —— 对任意 run.status 结果完全一致（StatusVocabularySelfTest 逐状态钉死）。
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE experiment e SET status = "
                        + StatusVocabulary.runToExperimentCaseSql() + ",\n"
                        + "       started_at = COALESCE(e.started_at, r.started_at),\n"
                        + "       finished_at = r.finished_at,\n"
                        + "       updated_at = now()\n"
                        + "  FROM experiment_run r\n"
                        + " WHERE e.tenant_id = ? AND r.tenant_id = e.tenant_id\n"
                        + "   AND r.id = ? AND e.id = r.experiment_id")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, runId);
            ps.executeUpdate();
        }
    }

    /**
     * 事实：该租户所有 {@code experiment_run} 已 paused（现场暂停是<b>作用域事件</b>，
     * 会把同岛其他实验一起批量置 paused）。
     * 投影：把仍 {@code queued/running} 的 {@code experiment} 也标成 paused ——
     * 否则列表显示「执行中」、详情显示「已暂停」，现场会以为还在跑而不去点继续。
     *
     * <p>源自 {@code RunControl.pause} 收尾的内联 SQL（原样搬运）。
     */
    public static void syncPausedByRuns(Connection c, long tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE experiment e SET status = "
                        + StatusVocabulary.runToExperimentCaseSql() + ",\n"
                        + "       updated_at = now()\n"
                        + "  FROM experiment_run r\n"
                        + " WHERE e.tenant_id = ? AND r.tenant_id = e.tenant_id\n"
                        + "   AND e.id = r.experiment_id\n"
                        + "   AND r.status = 'paused'\n"
                        + "   AND e.status IN ('queued', 'running')")) {
            ps.setLong(1, tenantId);
            ps.executeUpdate();
        }
    }

    /**
     * 事实：实验进入按批执行（{@code BatchOrchestrator} 开始跑批）。
     * 投影：{@code experiment} 标 {@code running}。
     *
     * <p>源自 {@code BatchOrchestrator.markExperimentRunning} 的内联 SQL（原样搬运）。
     */
    public static void markRunningByExperiment(Connection c, long tenantId, long experimentId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE experiment
                   SET status = 'running',
                       started_at = COALESCE(started_at, now()),
                       finished_at = NULL,
                       updated_at = now()
                 WHERE tenant_id = ? AND id = ?
                """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            ps.executeUpdate();
        }
    }

    /**
     * 事实：一批实验的 D6 聚合结果（{@code finished} / {@code failed} 等）。
     * 投影：写入 {@code experiment.status}。
     *
     * <p>源自 {@code BatchOrchestrator.syncExperimentStatusFromBatches} 的内联 SQL（原样搬运）；
     * 聚合值仍由 {@code BatchPlanner.aggregateExperimentStatus}（D6 纯函数）在调用方算出后传入。
     *
     * @return 写入的聚合状态（与入参相同，方便调用方链式使用）
     */
    public static String syncByBatchAggregate(Connection c, long tenantId, long experimentId,
            String aggregate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE experiment
                   SET status = ?,
                       started_at = COALESCE(started_at, now()),
                       finished_at = CASE WHEN ? IN ('finished', 'failed')
                                          THEN now() ELSE NULL END,
                       updated_at = now()
                 WHERE tenant_id = ? AND id = ?
                """)) {
            ps.setString(1, aggregate);
            ps.setString(2, aggregate);
            ps.setLong(3, tenantId);
            ps.setLong(4, experimentId);
            ps.executeUpdate();
        }
        return aggregate;
    }
}
