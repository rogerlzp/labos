package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 设备事件的持久化、run 级游标和原子消费。 */
public interface DeviceEventMapper {

    /** 写入事件；sourceEventId 重复时返回首次写入的事件 id。 */
    Long insertEvent(@Param("tenantId") long tenantId,
                     @Param("deviceId") Long deviceId,
                     @Param("deviceCode") String deviceCode,
                     @Param("eventType") String eventType,
                     @Param("payloadJson") String payloadJson,
                     @Param("runId") Long runId,
                     @Param("sourceEventId") String sourceEventId,
                     @Param("processId") String processId,
                     @Param("interactionStep") String interactionStep);

    /** 查询观测口；不用于生产等待节点认领。 */
    List<DeviceEventRow> selectEventsSince(@Param("tenantId") long tenantId,
                                            @Param("deviceCode") String deviceCode,
                                            @Param("eventType") String eventType,
                                            @Param("afterId") long afterId,
                                            @Param("limit") int limit);

    long selectMaxEventId(@Param("tenantId") long tenantId);

    /** 首次执行固定游标；续跑 ON CONFLICT 保留并读回旧游标。 */
    long ensureRunCursor(@Param("tenantId") long tenantId,
                         @Param("runId") long runId);

    /**
     * 单语句原子认领：同一事件最多一个等待者；同一 run/node/loop 重入返回原事件。
     * 并发冲突时返回 null，下一轮再读。
     */
    DeviceEventRow claimEvent(@Param("tenantId") long tenantId,
                              @Param("runId") long runId,
                              @Param("nodeKey") String nodeKey,
                              @Param("loopIndex") int loopIndex,
                              @Param("deviceCode") String deviceCode,
                              @Param("eventType") String eventType,
                              @Param("afterId") long afterId);

    /** 兼容观测/旧调用；生产等待节点使用 claimEvent。 */
    int markDispatched(@Param("tenantId") long tenantId, @Param("id") long id);

    Long selectInstanceIdByCode(@Param("tenantId") long tenantId,
                                @Param("code") String code);
}
