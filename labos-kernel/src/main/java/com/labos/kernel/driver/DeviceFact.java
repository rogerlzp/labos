package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 阶段 C · 设备事实的重连重查（断线置过期）。
 *
 * <p>设备断线时把该设备所有事实的 observed_at 置空；下次守卫检查按「过期」拒绝，
 * 直到设备重连后重新上报（{@code upsertFact} 写回 fresh observed_at）。守卫本身每次
 * 都读库（无缓存），所以重连后无需特殊动作——设备重新上报即恢复新鲜。
 */
public final class DeviceFact {

    private static final Logger log = LoggerFactory.getLogger(DeviceFact.class);

    /** 断线时调用：置过期该设备所有事实（下次守卫 fail-closed）。 */
    public static void invalidate(DbConfig db, long tenantId, long deviceResourceId) {
        if (db == null || deviceResourceId <= 0) {
            return;
        }
        try {
            Mappers.get(DeviceFactMapper.class)
                    .invalidateDeviceFacts(tenantId, deviceResourceId);
        } catch (RuntimeException e) {
            log.warn("[设备事实] 断线置过期失败（守卫会按时效兜底）：{}", e.getMessage());
        }
    }

    private DeviceFact() {
    }
}
