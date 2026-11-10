package com.labos.kernel.persistence;

/**
 * 设备运行态与通道占用（HeartbeatService / DevicePortLock 的裸 SQL）。
 *
 * <p>两个调用方都是 new 出来的工具类，用 {@link Mappers#get} 取代理。
 */
public interface DeviceStateMapper {

    /** 驱动报 method_status=1：标记运行中，并记住 current_run_id（不覆盖已有值）。 */
    int markRunning(long tenantId, long deviceInstanceId, String method, Long runId);

    /** 执行完成/失败：清 current_method，写 last_error，status 由调用方决定。 */
    int markIdle(long tenantId, long deviceInstanceId, String status, String lastError);

    /**
     * resource_node.id -> device_instance.id。
     *
     * <p>只取未软删的设备、按 id 升序取第一条 —— 与
     * {@code WorkflowWebMapper.selectDeviceInstanceId} **不同**：那个不带
     * {@code deleted_at} 过滤。两处不能混用。
     */
    Long selectInstanceIdByResource(long resourceId);

    /** 设备编号 -> resource_node.id（params 里的 source/target 换算）。 */
    Long selectResourceIdByCode(long tenantId, String code);

    /** 设备方法是否可中断；查不到返回 null。 */
    Boolean selectMethodInterruptible(long tenantId, long deviceInstanceId, String method);

    /** 设备有没有配通道（没配说明是存量数据，跳过通道锁）。 */
    boolean hasPorts(long tenantId, long resourceId);

    /** 当前占用数；没有该 port 行时返回 null。 */
    Integer selectPortInUse(long tenantId, long portId);
}
