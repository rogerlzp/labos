package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/**
 * 驱动表（driver）的只读查询，供两个契约/端到端演示脚本使用。
 *
 * <p>演示脚本是独立 {@code main()}，用 {@link Mappers#get} 取代理；
 * 之前它们各自手写一条 SQL，列集不同（一个要 protocol 文本，一个还要
 * driver_kind），所以这里保留两个方法而不是硬合并。
 */
public interface DriverMapper {

    /** id / code / endpoint / protocol（文本）。 */
    List<Map<String, Object>> selectDrivers(long tenantId);

    /** 同上 + driver_kind（manifest 里的类型，缺省 unknown）。 */
    List<Map<String, Object>> selectDriversWithKind(long tenantId);

    /**
     * 端到端演示：driver_id 非空的设备实例 × 能力（driverId/deviceId/capabilityCode）。
     * 无 tenant 过滤 —— 与原语句一致（演示脚本用）。
     */
    List<Map<String, Object>> selectDriverDeviceCapabilities();

    /** 契约演示：驱动级能力（driver_capability JOIN capability，driverId/capabilityCode）。 */
    List<Map<String, Object>> selectDriverCapabilities();

    /**
     * 启用驱动的全量设备视图（WorkflowService 注册驱动用）：每行一个
     * 驱动×设备×能力组合，外加设备型号绑定（device.attr-&gt;&gt;'device_model'）。
     */
    List<Map<String, Object>> selectDriverDeviceRows(long tenantId);

    /** 全部设备实例声明的能力（device_instance.id -&gt; cap code 多行展开）。 */
    List<Map<String, Object>> selectInstanceCapabilities();
}
