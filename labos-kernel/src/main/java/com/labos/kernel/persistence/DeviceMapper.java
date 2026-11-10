package com.labos.kernel.persistence;

import java.util.Map;

/**
 * 设备写路径的数据访问（{@code DeviceService} 专用，语句见
 * {@code mapper/DeviceMapper.xml}）。
 *
 * <p>原先这些 SQL 散在 3500 行的 {@code QueryService} 里、映射挂在
 * {@code QueryMapper} 上；按「一个 Controller 域 → 一个 Service → 一个
 * Mapper」归位到这里。
 *
 * <p>{@code insertDeviceNode} 被 cloneDevice / deployModel / createDevice
 * 三处共用（原实现逐字重复了三遍），合并成一条；新流程建模时别再造第二份。
 */
public interface DeviceMapper {

    /** 克隆源：设备 code/name + 目标岛的 path（岛不存在时 path 为 null）。 */
    Map<String, Object> selectCloneSource(long tenantId, long sourceResourceId,
                                          long islandId);

    /** 型号部署的源信息：型号名/code/能力 + 目标岛 path。 */
    Map<String, Object> selectModelForDeploy(long tenantId, long modelId,
                                             long islandId);

    /** 在资源树下建一个 device 节点，返回 id。 */
    Long insertDeviceNode(long tenantId, long islandId, String code,
                          String name, String path);

    /** 从源设备实例克隆一条新实例（连带 device_id / driver_id / ip）。 */
    int cloneDeviceInstance(long tenantId, String code, String name,
                            long newResourceId, long sourceResourceId);

    /** 按型号建设备实例（ip 留空）。 */
    int insertModelDeviceInstance(long tenantId, String code, String name,
                                  long modelId, long resourceId);

    /** 把型号的能力写到资源上（只建实例不建能力会导致驱动整条跳过）。 */
    int insertResourceCapabilityFromModel(long resourceId, long modelId);

    /** 新建设备型号，返回 id。 */
    Long insertDeviceModel(long tenantId, String code, String name,
                           String deviceType, Long capabilityId);

    /** 新建设备实例（手工建设备路径）。 */
    int insertDeviceInstance(long tenantId, String code, String name,
                             long deviceId, long resourceId, String ip, Long driverId);

    /** 改资源树节点显示名。 */
    int updateResourceNodeName(long tenantId, long resourceId, String name);

    /** 改型号能力（影响同型号所有实例——能力属于型号，不属于单台）。 */
    int updateModelCapability(long tenantId, long resourceId, long capabilityId);

    /** 改设备实例的 IP / 驱动（没传的列保持原值）。 */
    int updateDeviceInstanceEndpoint(long tenantId, long resourceId,
                                     String ip, Long driverId);
}
