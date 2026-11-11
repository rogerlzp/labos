package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 设备心跳路径的数据访问（原 {@code HeartbeatService} 里的裸 SQL）。
 *
 * <p>调用方是 new 出来的长驻线程组件，用 {@link Mappers#get} 取代理；
 * 在线状态的 UPSERT / driver_health / 离线告警幂等键（alert_state fingerprint）
 * 语义见 {@code mapper/HeartbeatMapper.xml} 注释。
 */
public interface HeartbeatMapper {

    /**
     * 在线判定的目标：**逐设备**（不是逐 driver）。
     *
     * <p>在线状态来自 Redis 里 tcp client 报上来的长连接状态，
     * key = {@code labos:link:<设备ID>:<设备IP>:<端口>}，**每台设备一条** ⇒
     * 判定粒度必须与 key 粒度一致，否则会把一台设备的在线状态套到同 driver 的
     * 其它设备上。
     *
     * <p>三端口径统一（模拟端 / 中控 / tcp client 认同一个设备）：
     * {@code deviceId} = device_instance.id（设备ID）、
     * {@code ip} = device_instance.ip（设备IP，与 driver.endpoint 的 host 同源）、
     * 端口取 driver.endpoint。
     */
    List<Map<String, Object>> selectLinkTargets(@Param("tenantId") long tenantId);

    /** 探测结果台账（driver_health 一行）。 */
    int insertDriverHealth(@Param("driverId") long driverId,
                           @Param("healthy") boolean healthy,
                           @Param("latencyMs") int latencyMs,
                           @Param("message") String message);

    /**
     **该设备在线状态的 UPSERT：没有 device_state 行就建（idle），
     * 有就更新 online；last_heartbeat 只在探测成功时推进（离线停在最后一次成功点）。
     *
     * @param resourceId 设备对应的资源节点 id（device_state 的业务键）
     * @param nowMillis 探测时刻（epoch 毫秒，to_timestamp(nowMillis/1000.0)）
     */
    int upsertDeviceOnline(@Param("tenantId") long tenantId,
                           @Param("resourceId") long resourceId,
                           @Param("online") boolean online,
                           @Param("nowMillis") long nowMillis);

    /**
     * 离线告警幂等开：fingerprint 'DEVICE-OFFLINE|' || resource_id 做唯一键，
     * 有行重新打开（upsert），不再撞 (tenant_id, fingerprint) 主键。
     *
     * @param offlineMs 离线阈值（毫秒，与 ' milliseconds'::interval 拼接）
     * @return 新开/重开的告警条数
     */
    int upsertOfflineAlerts(@Param("tenantId") long tenantId,
                            @Param("offlineMs") String offlineMs);

    /** 恢复在线且存在未关闭告警的关掉；返回关闭条数。 */
    int closeRecoveredAlerts(@Param("tenantId") long tenantId);
}
