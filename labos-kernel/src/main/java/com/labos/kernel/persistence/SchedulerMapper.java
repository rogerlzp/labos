package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 实验调度器（experiment_run 排队/续跑/资源预检）的数据访问
 * （原 {@code ExperimentScheduler} 里的裸 SQL）。
 *
 * <p>调用方是 new 出来的长驻线程组件，用 {@link Mappers#get} 取代理。
 * 建 run 语句复用 {@link BatchMapper#insertQueuedRunWithVars}（同一 INSERT，
 * 不重复建）。
 */
public interface SchedulerMapper {

    /** 排队中的 run + 实验优先级/截止/岛（启动 rehydrate 与续队用，按 id 升序）。 */
    List<Map<String, Object>> selectQueuedRuns(@Param("tenantId") long tenantId);

    /** 续跑落位后清 RESUMING 中间态（仅当仍 queued）；返回更新条数。 */
    int clearResumingState(@Param("tenantId") long tenantId,
                           @Param("runId") long runId);

    /** 实验流程用到的资源集合（去重，NULL 除外）；调用方空集语义不变。 */
    List<Long> selectRequiredResources(@Param("tenantId") long tenantId,
                                       @Param("experimentId") long experimentId);

    /** 实验优先级（NULL 按 50）；实验不存在返回 null（调用方回落 50）。 */
    Long selectExperimentPriority(@Param("tenantId") long tenantId,
                                  @Param("experimentId") long experimentId);

    /** 实验截止时间；无值或实验不存在返回 null。 */
    java.sql.Timestamp selectExperimentDeadline(@Param("tenantId") long tenantId,
                                                @Param("experimentId") long experimentId);
}
