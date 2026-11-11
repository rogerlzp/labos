package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
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

    /** 设备型号 + 部署台数（一次子查询，避免逐型号 N+1）。 */
    List<Map<String, Object>> selectDeviceModels(long tenantId);

    /** 设备实例软删（保留 resource_node 供历史 run_node 追溯）。 */
    int softDeleteDeviceInstance(long tenantId, long resourceId);

    /** 岛的 path（移设备/建设备时拼子节点路径）。 */
    String selectIslandPath(long tenantId, long islandId);

    /** 设备挂到岛下（path 由调用方按岛 path + 设备 code 拼好）。 */
    int attachDeviceToIsland(long tenantId, long resourceId, long islandId, String path);

    /** 设备移出岛（放回设备池）。 */
    int detachDeviceFromIsland(long tenantId, long resourceId);

    /** 资源节点的编码（按 kind 限定，避免拿到同 id 的其它类型）。 */
    String selectNodeCode(long tenantId, long resourceNodeId, String kind);

    /** 写设备在资源树上的坐标（jsonb attr 合并，不动其它键）。 */
    int updateDevicePosition(long tenantId, long resourceId, double x, double y);

    /** 设备池（islandId 为 null/<=0 时全部）。 */
    List<Map<String, Object>> selectDevicePool(long tenantId, Long islandId);

    /** 未挂到岛上的设备（有 device_instance 但父节点不是 island）。 */
    List<Map<String, Object>> selectUnassignedDevices(long tenantId);

    /**
     * 设备清单 —— **模拟端 / tcp client 从中控读的唯一真源**。
     *
     * <p>口径：一台设备 = 设备ID（device_instance.id）+ 设备IP（device_instance.ip）
     * + 端口（driver.endpoint 解析）。三端认的必须是同一组值，所以清单只报
     * 表里的值，不做推断 —— 猜出来的 deviceId 会让 Redis 里的长连接状态挂错设备。
     */
    List<Map<String, Object>> selectDeviceRegistry(long tenantId);

    /** 设备方法清单（按 resource_node.id 关联，含型号码与参数定义）。 */
    List<Map<String, Object>> selectDeviceMethods(long resourceId);

    /** 某方法的参数定义；不存在返回 null。 */
    Map<String, Object> selectMethodParams(long tenantId, String method);

    /** 驱动清单（未软删）。 */
    List<Map<String, Object>> selectDrivers(long tenantId);

    /** 拓扑：岛 + 其下设备（一次 JOIN 出全部字段，Java 侧按岛分组）。 */
    List<Map<String, Object>> selectTopology(long tenantId);

    int setDeviceMethodInterruptible(long tenantId, long deviceId,
                                     String method, boolean value);

    /** 建岛，返回 id。 */
    Long insertIsland(long tenantId, String code, String name, String path);

    int countDevicesUnderIsland(long tenantId, long islandId);

    /** 删岛（只删 kind='island' 的资源节点）。 */
    int deleteIslandNode(long tenantId, long islandId);

    /** 机器人点位明细（列表页用）。 */
    List<Map<String, Object>> selectRobotPointDetails(long tenantId, long islandId);

    /** 机器人点位（CSV 与列表共用的原始行）。 */
    List<Map<String, Object>> selectRobotPoints(long tenantId, long islandId);

    /** 导入一个机器人点位（同 robot+code 覆盖，未传的列保留旧值）。 */
    int upsertRobotPoint(long tenantId, long islandId, long robotResourceId,
                         String code, String name, java.math.BigDecimal x,
                         java.math.BigDecimal y, java.math.BigDecimal z,
                         Long deviceResourceId, String action);

    /** 从资源树同步点位（岛下设备，排除机器人自身）；返回受影响行数。 */
    int syncRobotPoints(long tenantId, long islandId, long robotResourceId);

    /**
     * 机器人点位增量更新。参数为 null 的列不动 —— 原实现用 StringBuilder 拼
     * {@code ", x = ?"}，改成 XML 的 {@code <if>} 后 SQL 结构一眼可见。
     */
    int updateRobotPoint(@Param("tenantId") long tenantId,
                         @Param("islandId") long islandId,
                         @Param("code") String code,
                         @Param("x") Double x,
                         @Param("y") Double y,
                         @Param("z") Double z,
                         @Param("deviceResourceId") Long deviceResourceId,
                         @Param("action") String action);

    /** resource_node.id -> device_instance.id（未软删，取一条）。 */
    Long selectInstanceId(long tenantId, long resourceId);

    /** 岛动线图；不存在返回 null。 */
    String selectIslandFlow(long tenantId, long islandId);

    /** 保存岛动线（按 island_id 覆盖）。 */
    int upsertIslandFlow(long tenantId, long islandId, String graphJson);


    /** 按设备编码找资源节点 id（kind='device'）。 */
    Long selectResourceIdByDeviceCode(long tenantId, String code);


    /** 设备编号存在性（克隆/部署时自动编号避重）。 */
    boolean existsDeviceCode(long tenantId, String code);

    /** 按能力 code 查 capability id。 */
    Long selectCapabilityId(long tenantId, String code);

    /** 按设备编号查型号 id。 */
    Long selectDeviceIdByCode(long tenantId, String code);

    // ---- DeviceRegistrar（设备池登记 CLI，阶段3第30批收编）----

    /**
     * 设备池登记 upsert（{@code (tenant_id, code)} 冲突时更新展示字段，
     * {@code attr} 为合并而非覆盖）；返回 device id。capabilityId 可空。
     */
    Long upsertDevicePool(@Param("tenantId") long tenantId,
                          @Param("code") String code,
                          @Param("name") String name,
                          @Param("model") String model,
                          @Param("vendor") String vendor,
                          @Param("deviceType") String deviceType,
                          @Param("capabilityId") Long capabilityId,
                          @Param("icon") String icon,
                          @Param("color") String color,
                          @Param("remark") String remark,
                          @Param("attrJson") String attrJson);

    /**
     * 方法集同步（{@code device_method}，{@code (device_id, method)} 冲突时更新；
     * 只增改不删 —— 存量方法可能被历史流程引用）。items 每行：
     * method/label/capabilityId(可空)/paramsJson/estimate/timeout。
     */
    int upsertDeviceMethods(@Param("tenantId") long tenantId,
                            @Param("deviceId") long deviceId,
                            @Param("items") List<Map<String, Object>> items);

    /** 设备池清单（{@code --list} 用；deployed = 未软删实例数，按它降序）。 */
    List<Map<String, Object>> selectDevicePoolList(@Param("tenantId") long tenantId);
}
