package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * C 批：run 表的状态收尾 SQL，语句见 {@code mapper/RunMapper.xml}。
 *
 * <p>这几条是执行链上最敏感的写入（决定一次运行是 completed / failed /
 * paused），放进 XML 后能直接看清「改了哪些列、带了什么 WHERE 条件」，
 * 不用在 Java 里拼三段动态 SQL 字符串。
 */
public interface RunMapper {

    /** 执行期异常收尾：只改 status='running' 的行，不覆盖 paused。 */
    int markFailed(long tenantId, long runId, String errorJson);

    /** 正常/异常终止：completed 或 failed。 */
    int settleTerminal(long tenantId, long runId, String status);

    /** 转人工：paused + waiting_human（不写 actual_end）。 */
    int settleManual(long tenantId, long runId);

    /** P-09：执行体收尾，不再派发新命令。 */
    int markSettledPhase(long tenantId, long runId);

    /** 读当前状态（暂停边界判定等）。 */
    String selectStatus(long tenantId, long runId);

    // ---- 调度器读侧（ExperimentScheduler：续队/快照/兜底写状态）----

    /** experiment_run 当前状态（续队前以库为准）。 */
    String selectExperimentRunStatus(long tenantId, long runId);

    /** 岛锁展示用：id -> code/name。 */
    java.util.List<java.util.Map<String, Object>> selectIslandCodes(
            long tenantId, java.util.List<Long> ids);

    /** 锁的 run -> 实验 id / 实验编号（一次 JOIN 批量）。 */
    java.util.List<java.util.Map<String, Object>> selectRunExperiments(
            long tenantId, java.util.List<Long> runIds);

    /** 队列项实验编号（一次批量）。 */
    java.util.List<java.util.Map<String, Object>> selectExperimentNos(
            long tenantId, java.util.List<Long> expIds);

    /**
     * 兜底写状态（执行器 settle 前抛异常时用）。
     *
     * <p>{@code finished=true} 时补 finished_at；两者都带
     * {@code status NOT IN ('aborted','cancelled')} 守卫 —— 人工已终止的 run
     * 不能被迟到的兜底写回成 failed。
     */
    int markExperimentRunStatus(long tenantId, long runId, String status,
                                String error, @Param("finished") boolean finished);

    /** 切换运行中 run 的值守模式；已结束的 run 不受影响（0 行）。 */
    int updateAttendance(long tenantId, long runId, String attendance);
}
