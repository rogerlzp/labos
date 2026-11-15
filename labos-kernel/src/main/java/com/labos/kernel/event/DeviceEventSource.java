package com.labos.kernel.event;

import java.util.List;

/** 设备事件读取口；生产实现必须把认领做成原子操作。 */
public interface DeviceEventSource {

    List<DeviceEvent> since(String deviceCode, String eventType, long afterId, int limit);

    /** 兼容观测及纯内存测试。 */
    default void markDispatched(long eventId) {
    }

    /**
     * 认领一个等待者的事件。默认实现保留既有内存测试语义；数据库实现必须覆盖为
     * “事件全局唯一消费 + run/node/loop 重入返回同一事件”的原子操作。
     */
    default DeviceEvent claim(long runId, String nodeKey, int loopIndex,
                              String deviceCode, String eventType,
                              long afterId, int limit) {
        List<DeviceEvent> events = since(deviceCode, eventType, afterId, limit);
        if (events == null) {
            return null;
        }
        for (DeviceEvent event : events) {
            if (event != null) {
                markDispatched(event.id());
                return event;
            }
        }
        return null;
    }
}
