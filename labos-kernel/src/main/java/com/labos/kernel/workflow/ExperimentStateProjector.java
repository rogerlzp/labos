package com.labos.kernel.workflow;

import com.labos.kernel.persistence.ExperimentProjectorMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
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
 * <p>阶段3第19批起，5 条 UPDATE 的 SQL 本体收编进 MyBatis
 * {@link ExperimentProjectorMapper}（对应 XML 是该形态 SQL 的唯一合法落点，
 * 由 {@code ExperimentStateProjectorSelfTest} 扫描全部 mapper XML 强制）；
 * 本类仍是 Java 侧的<b>唯一写入口</b>，方法签名、参数、过滤条件、事务边界与原样搬运时
 * 完全一致。run→experiment 的映射仍由 {@link StatusVocabulary#runToExperimentCaseSql()}
 * 从唯一映射表生成后经 {@code caseSql} 传入 XML 的 {@code ${caseSql}} 替换点 ——
 * {@code StatusVocabulary} 仍是唯一真源，XML 里不手写 WHEN 分支。
 *
 * <p><b>不变量（由 {@code ExperimentStateProjectorSelfTest} 扫描源码与 mapper XML 强制）</b>：
 * {@code src/main/java} 与 {@code src/boot/resources/mapper} 下除本类的 mapper XML 外，
 * 任何文件都不得出现 {@code UPDATE experiment … SET status} 形态的 SQL。
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

    private static ExperimentProjectorMapper mapper() {
        return Mappers.get(ExperimentProjectorMapper.class);
    }

    /**
     * R8 步骤 1：暂停收敛的「事实 → 投影 pause_kind」纯函数。
     *
     * <p>事实 = 请求的 pause_kind + 本 run 是否含物理结果不确定（reconciliation_required）节点。
     * 投影规则：
     * <ul>
     *   <li>{@code paused_fault}（设备故障，自动恢复）不拦，原样返回；</li>
     *   <li>其余且 {@code hasReconciliationRequired=true} ⇒ {@code manual}（PauseUnknown 转人工，不写干净 paused）；</li>
     *   <li>否则原样返回（干净 paused）。</li>
     * </ul>
     */
    public static String resolvePauseKind(String requestedKind, boolean hasReconciliationRequired) {
        if (requestedKind == null || "paused_fault".equals(requestedKind)) {
            return requestedKind;
        }
        if (hasReconciliationRequired) {
            return "manual";
        }
        return requestedKind;
    }

    /**
     * R8 步骤 3：run.status='paused' 的唯一写入口（事实=操作员暂停一条 workflow run）。
     * 返回受影响行数（0 = run 不在 running，无需暂停）。
     */
    public static int applyPausedRun(Connection c, long tenantId, long runId,
                                     String kind, String reason, String nodeKey) {
        return mapper().applyPausedRun(tenantId, runId, kind, reason, nodeKey);
    }

    /** R8 步骤 4：转人工=暂停（waiting_human），唯一写入口。 */
    public static int applyPausedRunManual(Connection c, long tenantId, long runId) {
        return mapper().applyPausedRunManual(tenantId, runId);
    }

    /** R8 步骤 4：节点边界暂停（COALESCE 保留已有值），唯一写入口。 */
    public static int applyPausedRunAtBoundary(Connection c, long tenantId, long runId,
                                               String kind, String reason, String nodeKey) {
        return mapper().applyPausedRunAtBoundary(tenantId, runId, kind, reason, nodeKey);
    }

    /** R8 步骤 4：物料隔离确认 → paused(manual_window)，唯一写入口。 */
    public static int applyPausedRunMaterialSafety(Connection c, long tenantId, long runId,
                                                   String note) {
        return mapper().applyPausedRunMaterialSafety(tenantId, runId, note);
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
        // c 形参保留（同事务写集语义），实际经 Mappers 绑定连接执行。
        mapper().markRunningByRun(tenantId, runId);
    }

    /**
     * 事实：一条 {@code experiment_run} 到达终态/暂停态。
     * 投影：按 {@link StatusVocabulary} 的 run→experiment 映射同步到 {@code experiment}。
     *
     * <p>源自 {@code ExperimentExecutor.syncExperimentStatus} 的内联 SQL（原样搬运）。
     */
    public static void syncByRun(Connection c, long tenantId, long runId) throws SQLException {
        // 状态映射**不在这里手写**（BUG-13/14 这类"词表不一致"的根）：由
        // StatusVocabulary.runToExperimentCaseSql() 从唯一映射表生成，作为 caseSql
        // 传入 XML 的 ${caseSql} 替换点。生成的 CASE 比三行手写 CASE 多出
        // paused/aborted/… 等恒等 WHEN，其 THEN 值与 ELSE r.status 相同 ——
        // 对任意 run.status 结果完全一致（StatusVocabularySelfTest 逐状态钉死）。
        mapper().syncByRun(tenantId, runId, StatusVocabulary.runToExperimentCaseSql());
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
        mapper().syncPausedByRuns(tenantId, StatusVocabulary.runToExperimentCaseSql());
    }

    /**
     * 事实：实验进入按批执行（{@code BatchOrchestrator} 开始跑批）。
     * 投影：{@code experiment} 标 {@code running}。
     *
     * <p>源自 {@code BatchOrchestrator.markExperimentRunning} 的内联 SQL（原样搬运）。
     */
    public static void markRunningByExperiment(Connection c, long tenantId, long experimentId)
            throws SQLException {
        mapper().markRunningByExperiment(tenantId, experimentId);
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
        mapper().syncByBatchAggregate(tenantId, experimentId, aggregate);
        return aggregate;
    }
}
