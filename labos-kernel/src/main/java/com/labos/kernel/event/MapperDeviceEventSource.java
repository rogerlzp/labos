package com.labos.kernel.event;

import com.labos.kernel.persistence.DeviceEventMapper;
import com.labos.kernel.persistence.DeviceEventRow;
import com.labos.kernel.persistence.Mappers;

import java.util.ArrayList;
import java.util.List;

/** PostgreSQL 设备事件源：持久 run 游标 + 单语句原子认领。 */
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
                // 时间窗传 null = 不筛（这个调用点按 id 游标取，不按时间）
                mapper().selectEventsSince(tenantId, deviceCode, eventType, afterId,
                        null, null, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DeviceEvent> out = new ArrayList<>(rows.size());
        for (DeviceEventRow r : rows) {
            out.add(toEvent(r));
        }
        return out;
    }

    @Override
    public DeviceEvent claim(long runId, String nodeKey, int loopIndex,
                             String deviceCode, String eventType,
                             long afterId, int limit) {
        DeviceEventRow row = mapper().claimEvent(
                tenantId, runId, nodeKey, loopIndex, deviceCode, eventType, afterId);
        return row == null ? null : toEvent(row);
    }

    private static DeviceEvent toEvent(DeviceEventRow r) {
        return new DeviceEvent(r.id(), r.deviceCode(), r.eventType(), r.payload());
    }

    @Override
    public void markDispatched(long eventId) {
        mapper().markDispatched(tenantId, eventId);
    }

    public long currentMaxEventId() {
        return mapper().selectMaxEventId(tenantId);
    }

    /** 首次调用固定当前 MAX(id)，后续（包括进程重启后的续跑）读回同一基准。 */
    public long runBaseCursor(long runId) {
        return mapper().ensureRunCursor(tenantId, runId);
    }
}
