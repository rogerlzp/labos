package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * 实验展示状态 {@code experiment.status} 投影写入的数据访问
 * （原 {@code ExperimentStateProjector} 里的 5 条裸 UPDATE，阶段3第19批收编）。
 *
 * <p>本 mapper 是 S-03「单写者不变量」在 MyBatis 收编后的唯一 SQL 落点：
 * {@code ExperimentStateProjectorSelfTest} 扫描全部 mapper XML，除本文件对应的
 * {@code ExperimentProjectorMapper.xml} 外不得出现 {@code UPDATE experiment … SET status}
 * 形态的 SQL。
 *
 * <p>run→experiment 的状态映射<b>不在这里手写</b>：{@code syncByRun}/{@code syncPausedByRuns}
 * 的 CASE 片段由调用方 {@code StatusVocabulary.runToExperimentCaseSql()} 从唯一映射表
 * 生成后以 {@code caseSql} 传入，XML 里以 {@code ${caseSql}} 原样替换。片段只含词表内的
 * 受控常量（无用户输入、无注入面），{@code StatusVocabulary} 仍是唯一真源。
 */
public interface ExperimentProjectorMapper {

    /**
     * R8 步骤 3：事实=操作员暂停一条 workflow run；投影=该 run 标 paused（唯一写入口）。
     */
    int applyPausedRun(@Param("tenantId") long tenantId, @Param("runId") long runId,
                       @Param("kind") String kind, @Param("reason") String reason,
                       @Param("nodeKey") String nodeKey);

    /**
     * 事实：一条 {@code experiment_run} 起跑/恢复。
     * 投影：其所属 {@code experiment} 标 {@code running}。
     */
    int markRunningByRun(@Param("tenantId") long tenantId, @Param("runId") long runId);

    /**
     * 事实：一条 {@code experiment_run} 到达终态/暂停态。
     * 投影：按 {@code StatusVocabulary} 的 run→experiment 映射同步到 {@code experiment}。
     *
     * @param caseSql {@code StatusVocabulary.runToExperimentCaseSql()} 生成的 CASE 片段
     */
    int syncByRun(@Param("tenantId") long tenantId, @Param("runId") long runId,
                  @Param("caseSql") String caseSql);

    /**
     * 事实：该租户所有 {@code experiment_run} 已 paused（作用域事件，同岛实验一并置 paused）。
     * 投影：仍 {@code queued/running} 的 {@code experiment} 也标成 paused。
     *
     * @param caseSql {@code StatusVocabulary.runToExperimentCaseSql()} 生成的 CASE 片段
     */
    int syncPausedByRuns(@Param("tenantId") long tenantId, @Param("caseSql") String caseSql);

    /**
     * 事实：实验进入按批执行。
     * 投影：{@code experiment} 标 {@code running}（清 finished_at）。
     */
    int markRunningByExperiment(@Param("tenantId") long tenantId,
                                @Param("experimentId") long experimentId);

    /**
     * 事实：一批实验的 D6 聚合结果（{@code finished} / {@code failed} 等）。
     * 投影：写入 {@code experiment.status} 并同步终态时间。
     */
    int syncByBatchAggregate(@Param("tenantId") long tenantId,
                             @Param("experimentId") long experimentId,
                             @Param("aggregate") String aggregate);
}
