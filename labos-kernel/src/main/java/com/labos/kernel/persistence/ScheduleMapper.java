package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * P2-4 · 排程持久化：schedule_plan（排队优先级/迭代上限等参数）+ resource_booking（资源预约）。
 * 语句见 {@code mapper/ScheduleMapper.xml}。
 */
public interface ScheduleMapper {

    List<Map<String, Object>> selectSchedulePlans(@Param("t") long tenantId);

    Map<String, Object> selectSchedulePlan(@Param("t") long tenantId, @Param("id") long id);

    int insertSchedulePlan(@Param("t") long tenantId,
                           @Param("planNo") String planNo,
                           @Param("algorithm") String algorithm,
                           @Param("status") String status,
                           @Param("optimizationFactor") int optimizationFactor,
                           @Param("maxIterationsRunning") int maxIterationsRunning,
                           @Param("iterationDelaySec") int iterationDelaySec,
                           @Param("externalId") String externalId,
                           @Param("scheduledStart") String scheduledStart);

    int updateSchedulePlan(@Param("id") long id,
                           @Param("status") String status,
                           @Param("optimizationFactor") Integer optimizationFactor,
                           @Param("maxIterationsRunning") Integer maxIterationsRunning,
                           @Param("iterationDelaySec") Integer iterationDelaySec,
                           @Param("externalId") String externalId);

    List<Map<String, Object>> selectResourceBookings(@Param("t") long tenantId,
                                                     @Param("runId") Long runId,
                                                     @Param("limit") int limit);

    /** 写一行资源预约（谁占了哪个资源、从什么时候到什么时候）。 */
    int insertResourceBooking(@Param("t") long tenantId,
                              @Param("resourceId") long resourceId,
                              @Param("periodLower") String periodLower,
                              @Param("periodUpper") String periodUpper,
                              @Param("status") String status,
                              @Param("sourceType") String sourceType,
                              @Param("sourceId") Long sourceId,
                              @Param("runNodeId") Long runNodeId,
                              @Param("note") String note);

    /** 把某条 run 的调度预约（active/planned）标记为 done（执行结束后）。 */
    int markBookingsDone(@Param("t") long tenantId,
                         @Param("sourceType") String sourceType,
                         @Param("sourceId") long sourceId);
}
