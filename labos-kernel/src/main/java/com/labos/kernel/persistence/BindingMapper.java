package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * P0 · 三条绑定链的读写：设备池（device_pool）、点位方法（position_method）、
 * 节点绑定（node_binding）。语句见 {@code mapper/BindingMapper.xml}。
 */
public interface BindingMapper {

    // ---------------------------------------------------------------- 设备池

    List<Map<String, Object>> selectPools(@Param("t") long tenantId,
                                          @Param("islandId") Long islandId,
                                          @Param("kind") String kind);

    Map<String, Object> selectPool(@Param("t") long tenantId, @Param("id") long id);

    int insertPool(@Param("t") long tenantId,
                   @Param("code") String code,
                   @Param("name") String name,
                   @Param("islandId") long islandId,
                   @Param("poolKind") String poolKind,
                   @Param("strategy") String strategy,
                   @Param("skipOnError") boolean skipOnError,
                   @Param("skipOnOffline") boolean skipOnOffline,
                   @Param("allowIterationOnError") boolean allowIterationOnError,
                   @Param("minNestCount") int minNestCount);

    int updatePool(@Param("id") long id,
                   @Param("name") String name,
                   @Param("strategy") String strategy,
                   @Param("skipOnError") boolean skipOnError,
                   @Param("skipOnOffline") boolean skipOnOffline,
                   @Param("allowIterationOnError") boolean allowIterationOnError,
                   @Param("enabled") boolean enabled);

    int deletePool(@Param("t") long tenantId, @Param("id") long id);

    List<Map<String, Object>> selectPoolMembers(@Param("t") long tenantId, @Param("poolId") long poolId);

    int insertPoolMember(@Param("t") long tenantId,
                         @Param("poolId") long poolId,
                         @Param("deviceInstanceId") long deviceInstanceId,
                         @Param("subGroup") String subGroup,
                         @Param("priority") int priority);

    int deletePoolMember(@Param("t") long tenantId,
                         @Param("poolId") long poolId,
                         @Param("deviceInstanceId") long deviceInstanceId);

    /** 设备实例的型号（device_id）—— operation 池建池时校验成员同型号。 */
    Long selectDeviceModelId(@Param("t") long tenantId, @Param("deviceInstanceId") long deviceInstanceId);

    /** 是否有 node_binding 引用这个池（删池前校验，有则 409）。 */
    long countPoolBindings(@Param("t") long tenantId, @Param("poolId") long poolId);

    // ---------------------------------------------------------------- 点位方法

    List<Map<String, Object>> selectPositionMethods(@Param("t") long tenantId,
                                                    @Param("positionId") long positionId,
                                                    @Param("scopeKind") String scopeKind,
                                                    @Param("scopeId") Long scopeId);

    Map<String, Object> selectPositionInfo(@Param("t") long tenantId, @Param("positionId") long positionId);

    int insertPositionMethod(@Param("t") long tenantId,
                             @Param("positionId") long positionId,
                             @Param("methodId") long methodId,
                             @Param("scopeKind") String scopeKind,
                             @Param("scopeId") Long scopeId,
                             @Param("purpose") String purpose,
                             @Param("allocation") String allocation,
                             @Param("priority") int priority);

    int updatePositionMethod(@Param("id") long id,
                             @Param("purpose") String purpose,
                             @Param("allocation") String allocation,
                             @Param("priority") int priority,
                             @Param("enabled") boolean enabled);

    int deletePositionMethod(@Param("id") long id);

    /** 点位×方法矩阵（编排人员用）：按岛列出所有已配置点位的方法绑定。 */
    List<Map<String, Object>> selectPositionMethodMatrix(@Param("t") long tenantId,
                                                         @Param("islandId") long islandId);

    // ---------------------------------------------------------------- 节点绑定

    /** node_key → workflow_node.id（绑定挂在 workflow_node 上）。 */
    Long selectNodeIdByKey(@Param("t") long tenantId,
                           @Param("workflowId") long workflowId,
                           @Param("nodeKey") String nodeKey);

    List<Map<String, Object>> selectNodeBindings(@Param("t") long tenantId, @Param("nodeId") long nodeId);

    int insertNodeBinding(@Param("t") long tenantId,
                          @Param("nodeId") long nodeId,
                          @Param("seq") int seq,
                          @Param("positionId") Long positionId,
                          @Param("deviceInstanceId") Long deviceInstanceId,
                          @Param("poolId") Long poolId,
                          @Param("methodId") Long methodId,
                          @Param("materialRole") String materialRole,
                          @Param("loadingPattern") String loadingPattern,
                          @Param("leaveAtStation") boolean leaveAtStation,
                          @Param("barcodePolicy") String barcodePolicy,
                          @Param("params") String paramsJson);

    int deleteNodeBindings(@Param("t") long tenantId, @Param("nodeId") long nodeId);

    /** 节点绑定解析：node_binding.position_id → device_point.device_resource_id → device_instance.id。
     *  key = node_key，value = device_instance.id（pinned，跳过候选池）。 */
    List<Map<String, Object>> selectNodeBindingInstanceIds(@Param("t") long tenantId,
                                                           @Param("workflowId") long workflowId);

    // ---------------------------------------------------------------- 物料角色（P2-8）

    List<Map<String, Object>> selectMaterialRoles(@Param("t") long tenantId);

    int insertMaterialRole(@Param("t") long tenantId,
                           @Param("roleCode") String roleCode,
                           @Param("displayName") String displayName,
                           @Param("physicalType") String physicalType,
                           @Param("plateType") String plateType,
                           @Param("maxHeightMm") Double maxHeightMm,
                           @Param("lidRoleCode") String lidRoleCode,
                           @Param("barcodeRegex") String barcodeRegex,
                           @Param("autoBarcodePrefix") String autoBarcodePrefix);

    int updateMaterialRole(@Param("id") long id,
                           @Param("displayName") String displayName,
                           @Param("physicalType") String physicalType,
                           @Param("plateType") String plateType,
                           @Param("maxHeightMm") Double maxHeightMm,
                           @Param("barcodeRegex") String barcodeRegex);

    int deleteMaterialRole(@Param("t") long tenantId, @Param("id") long id);

    /** 节点有无绑定 + 有无 XML resource，用于回填 binding_mode。 */
    int updateNodeBindingMode(@Param("t") long tenantId,
                              @Param("workflowId") long workflowId,
                              @Param("nodeKey") String nodeKey,
                              @Param("mode") String mode);
}
