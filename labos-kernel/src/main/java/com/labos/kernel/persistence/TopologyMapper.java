package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 整岛导出 / 导入（岛 + 设备 + 点位 + 点位方法 + 示教）。
 *
 * <p>为什么要有这一层：管理员那 7 步配置（设备/点位/方法授权/示教）在演示机、
 * 交付机、测试库之间要反复重建，手点一遍 82 个点位不现实。
 *
 * <p>导出**只带业务码**（岛/设备/点位/方法/驱动全部用 code），不带任何自增 id ——
 * 换一套环境 id 一定不一样，带 id 的包"看着导进去了、其实全错位"。
 */
public interface TopologyMapper {

    // ---------------------------------------------------------------- 导出

    /** 岛本身（code / name / path）。 */
    Map<String, Object> selectIslandRow(@Param("t") long tenantId, @Param("islandId") long islandId);

    /**
     * 岛下设备（资源节点 + device 行 + 实例 + 能力 + 驱动 code）。
     *
     * <p>一台设备在库里是三张表：`resource_node`（树）、`device`（型号/能力）、
     * `device_instance`（IP/驱动）。导出把三者拍平成一行。
     */
    List<Map<String, Object>> selectIslandDevices(@Param("t") long tenantId,
                                                  @Param("islandId") long islandId);

    /** 岛下点位（device_point + 点位资源 code + 所属设备 code）。 */
    List<Map<String, Object>> selectIslandPoints(@Param("t") long tenantId,
                                                 @Param("islandId") long islandId);

    /** 岛下「点位 × 方法」授权（含方法名与设备 code，导入时按型号重新解析 methodId）。 */
    List<Map<String, Object>> selectIslandPointMethods(@Param("t") long tenantId,
                                                       @Param("islandId") long islandId);

    /** 岛下示教点。 */
    List<Map<String, Object>> selectIslandRobotPoints(@Param("t") long tenantId,
                                                      @Param("islandId") long islandId);

    // ---------------------------------------------------------------- 导入

    /** 按 code 找资源节点（判断"已存在就跳过"，导入必须可重复跑）。 */
    Long selectResourceIdByCode(@Param("t") long tenantId, @Param("code") String code);

    /** 驱动 code → driver.id（导入时还原 driver_id；找不到就 null + 警告）。 */
    Long selectDriverIdByCode(@Param("t") long tenantId, @Param("code") String code);

    /**
     * 建一个点位（resource_node + device_point + position_state 一条语句）。
     *
     * <p>照抄批量造网格点位的写法：一条 CTE 写完三张表，**不会留半成品**；
     * 同 code 已存在时复用并更新配置（幂等）。
     */
    int insertPointForImport(@Param("t") long tenantId,
                             @Param("deviceResourceId") long deviceResourceId,
                             @Param("pointCode") String pointCode,
                             /** 资源树 kind：storage / incubator / transfer_point … */
                             @Param("resourceKind") String resourceKind,
                             /** device_point.point_kind：nest / deck_in / deck_out / waste */
                             @Param("pointKind") String pointKind,
                             @Param("pointRole") String pointRole,
                             @Param("robotReachable") boolean robotReachable,
                             @Param("positionLabel") String positionLabel,
                             @Param("slotIndex") Integer slotIndex,
                             @Param("gridRow") Integer gridRow,
                             @Param("gridCol") Integer gridCol,
                             @Param("maxStack") Integer maxStack,
                             @Param("maxHeightMm") Double maxHeightMm,
                             @Param("plateTypes") String plateTypes,
                             @Param("isEntry") boolean isEntry,
                             @Param("isExit") boolean isExit,
                             @Param("isStandalone") boolean isStandalone,
                             @Param("isPrimary") boolean isPrimary,
                             @Param("orderInDevice") Integer orderInDevice,
                             @Param("deviceCode") String deviceCode);

    /**
     * 点位方法授权（幂等）。
     *
     * <p>为什么不用 {@code BindingMapper.insertPositionMethod}：那条是裸 INSERT，
     * 重复时会抛唯一键错误；在事务里 PG 会把**整个事务置为 aborted**，
     * 后续语句全部失败（实测：整岛导入 500）。这里用 {@code ON CONFLICT DO NOTHING}。
     */
    int insertPointMethodIfAbsent(@Param("t") long tenantId,
                                  @Param("positionId") long positionId,
                                  @Param("methodId") long methodId,
                                  @Param("scopeKind") String scopeKind,
                                  @Param("purpose") String purpose,
                                  @Param("allocation") String allocation,
                                  @Param("priority") int priority);

    /**
     * 写示教点（按 uk_robot_point=(tenant, robot_resource_id, code) 幂等）。
     */
    int upsertRobotPointForImport(@Param("t") long tenantId,
                                  @Param("islandId") long islandId,
                                  @Param("robotResourceId") long robotResourceId,
                                  @Param("code") String code,
                                  @Param("name") String name,
                                  @Param("x") Double x,
                                  @Param("y") Double y,
                                  @Param("z") Double z,
                                  @Param("pointType") String pointType,
                                  @Param("fillStatus") String fillStatus,
                                  @Param("deviceResourceId") Long deviceResourceId);
}
