package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * 执行引擎（{@code ExperimentExecutor}）的 SQL。
 *
 * <p>为什么单开一个而不是塞进 {@code RunMapper}：这里的查询横跨
 * {@code experiment_run} 与 {@code run} 两套表，且 `RunMapper` 正在被另一个
 * 改动（资源级调度）占用 —— 新开接口避免两边同时改同一文件。
 *
 * <p>调用方用 {@link Mappers#get} 取代理（执行器是 new 出来的）。
 */
public interface ExecutionMapper {

    /** experiment_run 当前状态（收尾前问「是不是已暂停」）。 */
    String selectExperimentRunStatus(long tenantId, long runId);

    /** 已被人工暂停时只补错误信息，不改状态（也不要写 updated_at，该表没有这列）。 */
    int updateErrorWhenPaused(long tenantId, long runId, String error);

    /**
     * 收尾：状态 + 错误 + 暂停信息，按需补 paused_at / finished_at，
     * 并把控制态清回 NONE。
     */
    int settleExperimentRun(long tenantId, long runId,
                            @Param("status") String status,
                            @Param("error") String error,
                            @Param("pauseKind") String pauseKind,
                            @Param("pauseReason") String pauseReason,
                            @Param("paused") boolean paused,
                            @Param("terminal") boolean terminal);

    /** 停止请求标记。 */
    Boolean selectStopRequested(long tenantId, long runId);

    /** experiment_run -> experiment.id。 */
    Long selectExperimentIdOfRun(long tenantId, long runId);

    /** run.pause_kind（暂停类型）。 */
    String selectPauseKindOfRun(long tenantId, long runId);

    /** run 当前状态。 */
    String selectRunStatus(long tenantId, long runId);
}
