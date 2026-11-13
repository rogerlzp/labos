package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 耗材/试剂目录 + 库存批次 + 点位配置。
 *
 * <p>语句见 {@code mapper/ConsumableMapper.xml}。目录表（consumable/reagent）
 * 与批次表（consumable_lot/reagent_lot）都是软删（{@code deleted_at}）。
 * 点位配置写 {@code device_point}（配置层，点位运行态仍在 {@code position_state}）。
 */
public interface ConsumableMapper {

    // ---------------------------------------------------------------- 耗材目录

    List<Map<String, Object>> selectConsumables(@Param("t") long tenantId,
                                                @Param("islandId") Long islandId,
                                                @Param("category") String category);

    Map<String, Object> selectConsumable(@Param("t") long tenantId, @Param("id") long id);

    int insertConsumable(@Param("t") long tenantId,
                         @Param("code") String code,
                         @Param("name") String name,
                         @Param("category") String category,
                         @Param("specification") String specification,
                         @Param("unit") String unit,
                         @Param("maxStack") Integer maxStack);

    int updateConsumable(@Param("id") long id,
                         @Param("name") String name,
                         @Param("category") String category,
                         @Param("specification") String specification,
                         @Param("unit") String unit,
                         @Param("maxStack") Integer maxStack);

    int deleteConsumable(@Param("t") long tenantId, @Param("id") long id);

    // ---------------------------------------------------------------- 耗材库存

    List<Map<String, Object>> selectLots(@Param("t") long tenantId,
                                         @Param("consumableId") Long consumableId,
                                         @Param("islandId") Long islandId);

    Map<String, Object> selectLot(@Param("t") long tenantId, @Param("id") long id);

    int insertLot(@Param("t") long tenantId,
                  @Param("consumableId") long consumableId,
                  @Param("lotNo") String lotNo,
                  @Param("barcode") String barcode,
                  @Param("quantity") int quantity,
                  @Param("remaining") int remaining,
                  @Param("locationId") Long locationId,
                  @Param("deviceId") Long deviceId,
                  @Param("expireAt") String expireAt,
                  @Param("status") String status);

    int updateLot(@Param("id") long id,
                  @Param("remaining") int remaining,
                  @Param("locationId") Long locationId,
                  @Param("deviceId") Long deviceId,
                  @Param("expireAt") String expireAt,
                  @Param("status") String status);

    int deleteLot(@Param("t") long tenantId, @Param("id") long id);

    /** 批次 id 反查（无 RETURNING 时的兑底，lotNo 在 (consumable_id, lot_no) 上唯一）。 */
    Long selectLotIdByNo(@Param("t") long tenantId,
                         @Param("consumableId") long consumableId,
                         @Param("lotNo") String lotNo);

    /** 点位归属的设备 resource id（耗材/试剂挂点位时用，device_id 由点位父级派生）。 */
    Long selectParentDeviceId(@Param("t") long tenantId, @Param("resourceId") long resourceId);

    // ---------------------------------------------------------------- 统一「点位内容」模型

    /** 写 position_state.content_kind/content_id（并清 plate_id：一个点位只有一个物理内容）。 */
    int upsertPositionContent(@Param("t") long tenantId,
                              @Param("resourceId") long resourceId,
                              @Param("contentKind") String contentKind,
                              @Param("contentId") Long contentId);

    /** 清 position_state.content_kind/content_id（内容被移走/删除）。 */
    int clearPositionContent(@Param("t") long tenantId, @Param("resourceId") long resourceId);

    // ---------------------------------------------------------------- 试剂（对称）

    List<Map<String, Object>> selectReagents(@Param("t") long tenantId,
                                             @Param("islandId") Long islandId,
                                             @Param("category") String category);

    int insertReagent(@Param("t") long tenantId,
                      @Param("code") String code,
                      @Param("name") String name,
                      @Param("category") String category,
                      @Param("specification") String specification,
                      @Param("unit") String unit);

    int updateReagent(@Param("id") long id,
                      @Param("name") String name,
                      @Param("category") String category,
                      @Param("specification") String specification,
                      @Param("unit") String unit);

    int deleteReagent(@Param("t") long tenantId, @Param("id") long id);

    List<Map<String, Object>> selectReagentLots(@Param("t") long tenantId,
                                                @Param("reagentId") Long reagentId,
                                                @Param("islandId") Long islandId);

    Map<String, Object> selectReagentLot(@Param("t") long tenantId, @Param("id") long id);

    int insertReagentLot(@Param("t") long tenantId,
                         @Param("reagentId") long reagentId,
                         @Param("lotNo") String lotNo,
                         @Param("volume") double volume,
                         @Param("remaining") double remaining,
                         @Param("locationId") Long locationId,
                         @Param("deviceId") Long deviceId,
                         @Param("expireAt") String expireAt,
                         @Param("status") String status);

    int updateReagentLot(@Param("id") long id,
                         @Param("remaining") double remaining,
                         @Param("locationId") Long locationId,
                         @Param("deviceId") Long deviceId,
                         @Param("expireAt") String expireAt,
                         @Param("status") String status);

    int deleteReagentLot(@Param("t") long tenantId, @Param("id") long id);

    Long selectReagentLotIdByNo(@Param("t") long tenantId,
                                @Param("reagentId") long reagentId,
                                @Param("lotNo") String lotNo);

    // ---------------------------------------------------------------- 点位配置

    int updateDevicePoint(@Param("t") long tenantId,
                          @Param("pointResourceId") long pointResourceId,
                          @Param("pointRole") String pointRole,
                          @Param("robotReachable") boolean robotReachable,
                          @Param("isEntry") boolean isEntry,
                          @Param("isExit") boolean isExit,
                          @Param("orderInDevice") int orderInDevice,
                          @Param("note") String note,
                          @Param("gridRow") Integer gridRow,
                          @Param("gridCol") Integer gridCol,
                          @Param("maxHeightMm") Double maxHeightMm,
                          @Param("plateTypes") String plateTypes,
                          @Param("positionLabel") String positionLabel,
                          @Param("isStandalone") Boolean isStandalone);

    /** 设备编码（不存在返回 null）。 */
    String selectDeviceCode(@Param("t") long tenantId,
                            @Param("deviceResourceId") long deviceResourceId);

    /**
     * 新建「网格之外的单独位置」（进出口装载口）。
     *
     * <p>单条语句里写 resource_node + device_point + position_state，避免半成品：
     * 同 {@code pointCode} 已存在时复用并更新配置（幂等）。
     *
     * @return 点位 resource_node.id
     */
    int insertStandalonePoint(@Param("t") long tenantId,
                              @Param("deviceResourceId") long deviceResourceId,
                              @Param("deviceCode") String deviceCode,
                              @Param("pointCode") String pointCode,
                              @Param("positionLabel") String positionLabel,
                              @Param("isEntry") boolean isEntry,
                              @Param("isExit") boolean isExit);
}
