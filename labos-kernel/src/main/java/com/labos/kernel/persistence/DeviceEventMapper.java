package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 设备事件（{@code device_event}）的数据访问。
 *
 * <p><b>事件流</b>：设备主动上报 → 入站端点（{@code POST /api/device-events}）写这里
 * → 等待节点（{@code __awaitEvent}）按（设备码, 事件类型）取 id 之后的新事件。
 *
 * <p><b>为什么照 {@code pause_event} 的形状</b>：那边是「中控自己产生、要通知人」，
 * 这边是「设备产生、要唤醒流程」——同一套「事件 + outbox」语义。
 *
 * <p>调用方拿到的是 {@link Mappers#get} 的代理；不在事务内时每次调用自动开/关 session。
 */
public interface DeviceEventMapper {

    /**
     * 写一条设备事件。
     *
     * @param payloadJson 事件载荷（JSON 文本）；空/非法由 SQL 兜底成 {@code {}}
     */
    int insertEvent(@Param("tenantId") long tenantId,
                    @Param("deviceId") Long deviceId,
                    @Param("deviceCode") String deviceCode,
                    @Param("eventType") String eventType,
                    @Param("payloadJson") String payloadJson,
                    @Param("runId") Long runId);

    /**
     * 取 {@code afterId} 之后、匹配条件的事件（按 id 升序）。
     *
     * @param deviceCode 设备码；null/空 = 任意设备
     * @param eventType  事件类型；null/空 = 任意类型
     */
    List<DeviceEventRow> selectEventsSince(@Param("tenantId") long tenantId,
                                            @Param("deviceCode") String deviceCode,
                                            @Param("eventType") String eventType,
                                            @Param("afterId") long afterId,
                                            @Param("limit") int limit);

    /** 当前最大事件 id（等待节点起手时当游标 ⇒ 只看"起手之后"的新事件）。 */
    long selectMaxEventId(@Param("tenantId") long tenantId);

    /** 标记事件已被消费（幂等：已标记的不再改）。 */
    int markDispatched(@Param("tenantId") long tenantId, @Param("id") long id);

    /** 按设备码解析设备实例 id（解析不出来返回 null —— 事件照样落库，设备码原样保留）。 */
    Long selectInstanceIdByCode(@Param("tenantId") long tenantId, @Param("code") String code);
}
