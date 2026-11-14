package com.labos.kernel.event;

import com.labos.kernel.persistence.DeviceEventMapper;
import com.labos.kernel.persistence.DeviceEventRow;
import com.labos.kernel.persistence.Mappers;

import java.util.ArrayList;
import java.util.List;

/**
 * 查库的设备事件源（默认实现）。
 *
 * <p><b>为什么 mapper 是延迟解析的</b>：{@link Mappers#get} 在没绑容器时直接抛异常，
 * 而这个对象会在装配期就 new 出来 ⇒ 延迟到第一次真正读事件时才解析，
 * 免得"没绑 MyBatis"把整个应用启动卡死。
 *
 * <p>读不到 / 查失败 ⇒ 返回空列表（让调用方按超时处理），**不抛**：
 * 事件源是"锦上添花"，不能因为它坏了就带崩整条流程。
 */
public final class MapperDeviceEventSource implements DeviceEventSource {

    private final long tenantId;
    private final DeviceEventMapper mapper;

    public MapperDeviceEventSource(long tenantId) {
        this(tenantId, null);
    }

    public MapperDeviceEventSource(long tenantId, DeviceEventMapper mapper) {
        this.tenantId = tenantId;
        this.mapper = mapper;
    }

    private DeviceEventMapper mapper() {
        return mapper != null ? mapper : Mappers.get(DeviceEventMapper.class);
    }

    @Override
    public List<DeviceEvent> since(String deviceCode, String eventType, long afterId, int limit) {
        List<DeviceEventRow> rows =
                mapper().selectEventsSince(tenantId, deviceCode, eventType, afterId, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DeviceEvent> out = new ArrayList<>(rows.size());
        for (DeviceEventRow r : rows) {
            out.add(new DeviceEvent(r.id(), r.deviceCode(), r.eventType(), r.payload()));
        }
        return out;
    }

    @Override
    public void markDispatched(long eventId) {
        mapper().markDispatched(tenantId, eventId);
    }

    /**
     * 当前最大事件 id —— **run 起手时的游标基准**。
     *
     * <p>取在 run 起手（而不是节点起手）是因为设备可能在本节点开始之前就报了：
     * 上位机还在做别的事，提取仪已经到交换点等着了 ⇒ 按节点起手取游标会漏掉那条事件。
     */
    public long currentMaxEventId() {
        return mapper().selectMaxEventId(tenantId);
    }
}
