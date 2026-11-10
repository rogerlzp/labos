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

    /** 搬运事务（transfer_transaction）的状态；不存在返回 null。 */
    String selectTransferStatus(long tenantId, long transferId);

    /** 当前 running 的 run 数（准入提示"当前并发 N 个"）。 */
    int countRunningRuns(long tenantId);

    /** run 的执行阶段（RunExecPhase）。 */
    String selectExecPhase(long tenantId, long runId);

    /** run 当前状态。 */
    String selectRunStatus(long tenantId, long runId);

    /** 派发开始：PREPARED → DISPATCH_STARTED（条件命中才更新，幂等单向）。 */
    int markDispatched(long tenantId, long runId);

    /** 收尾：→ SETTLED（非 SETTLED 才更新；paused 不调，语义不变）。 */
    int markSettled(long tenantId, long runId);

    // ---- ExperimentExecutor 编排/落库（阶段3收编） ----

    /** 编排行（id/seq/workflowId/loopCount/paramsOverride，按 seq 升序）。 */
    java.util.List<java.util.Map<String, Object>> selectExperimentProcessRows(
            long tenantId, long experimentId);

    /** 编排偏序边（fromId/toId）。 */
    java.util.List<java.util.Map<String, Object>> selectExperimentProcessEdges(
            long tenantId, long experimentId);

    /** 实验变量默认值（name/defaultValue，按 id 升序）。 */
    java.util.List<java.util.Map<String, Object>> selectExperimentVariables(
            long tenantId, long experimentId);

    /** 复用调度器创建的 run：补变量与展开步数。 */
    int updateRunVariables(long tenantId, long runId, String varsJson, int steps);

    /** 新建 run（queued + 变量 + 展开步数），返回 id。 */
    long insertExperimentRun(long tenantId, long experimentId, String varsJson, int steps);

    /** 被扣留 run 保持 paused，只补 started_at（不要写 updated_at，该表没有这列）。 */
    int touchStartedAtWhenPaused(long tenantId, long runId);

    /** 标 running：清暂停三字段，恢复时（pause_kind 非空）记 resumed_at。 */
    int markExperimentRunRunning(long tenantId, long runId);

    /** 推进 current_seq。 */
    int bumpCurrentSeq(long tenantId, long runId, int seq);

    /** 断点续跑：本 run 已完成的步（seq/loopIndex）。 */
    java.util.List<java.util.Map<String, Object>> selectCompletedSteps(long tenantId, long runId);

    /** 落一条 step 记录（running），返回 id。 */
    long insertRunStep(long tenantId, long runId, int seq, int loopIndex, long workflowId);

    /** step 三态收尾（runId 可空：流程没跑起来就没有 wf run）。 */
    int settleRunStep(long tenantId, long stepId, String status, Long runId, String error);
}
