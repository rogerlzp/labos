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

    /**
     * P1 精确点位：节点绑定的点位（device_point.id / 槽位号 / 现场标签）。
     *
     * <p>与 {@link #selectNodeBindingInstanceIds} 互补：那个只解析到「哪台设备」，
     * 搬运要按「第几个槽」走，点位必须在执行期带下去。按 {@code (nodeId, seq)} 排序，
     * 一个节点可绑多位（seq 表达顺序）。
     */
    List<Map<String, Object>> selectNodeBindingPoints(@Param("t") long tenantId,
                                                      @Param("workflowId") long workflowId);

    /** P2-7：读某节点的 barcode_policy + 期望条码（params->>'expected_barcode' / 'barcode'）。 */
    Map<String, Object> selectBarcodePolicyByNodeId(@Param("t") long tenantId,
                                                    @Param("nodeId") long nodeId);

    /**
     * 点位绑定预检（PF-21/23/27/30）：一条绑定一行，带点位/方法/可达性/示教/授权。
     *
     * <p>为什么单独一条查询而不是在 Java 里逐个节点查：预检要跑全量流程，
     * 逐节点查是 N+1；这里一次按 workflow 取全，判定全在内存。
     */
    List<Map<String, Object>> selectNodeBindingPreflight(@Param("t") long tenantId,
                                                         @Param("workflowId") long workflowId);

    /** PF-22：声明了点位模式（binding_mode='point'）却没有任何绑定的节点。 */
    List<Map<String, Object>> selectPointModeNodesWithoutBinding(@Param("t") long tenantId,
                                                                 @Param("workflowId") long workflowId);

    /**
     * 岛内第一台 TRANSFER 能力设备（点位视角搬运规划的机械臂来源）。
     *
     * <p>为什么不用能力池：能力池是**运行时**快照（驱动注册/在线过滤），
     * 而机械臂可能刚好没注册驱动；规划阶段只需要"这个岛里有没有臂、是哪个"，
     * 不能因为一台模拟器没上线就把整条工艺的搬运静默跳过。
     *
     * <p>岛的归属是资源树祖先（`resource_node` 没有 island_id 列）⇒ 递归向下找。
     * 返回 `resource_id` 与 `instance_id` 两列，调用方按执行器口径二选一。
     */
    Map<String, Object> selectTransferDeviceInIsland(@Param("t") long tenantId,
                                                     @Param("islandId") long islandId);

    // ---------------------------------------------------------------- 物料角色（P2-8）

    List<Map<String, Object>> selectMaterialRoles(@Param("t") long tenantId);

    // ---------------------------------------------------------------- 设备事件类型字典

    /**
     * 设备事件类型字典。
     *
     * <p>事件类型字串（waiting_interaction / process_done / …）**不能写死在代码里** ——
     * 它是三方对齐的唯一键：侧车产出、内核匹配、流程节点引用。
     * 写死 ⇒ 任何一边改了字串，另一边静默失配 ⇒ 上位机永远等不到那个事件。
     */
    List<Map<String, Object>> selectDeviceEventTypes(@Param("t") long tenantId);

    int insertDeviceEventType(@Param("t") long tenantId,
                              @Param("eventCode") String eventCode,
                              @Param("displayName") String displayName,
                              @Param("category") String category,
                              @Param("needsAction") boolean needsAction,
                              @Param("sortOrder") int sortOrder);

    int updateDeviceEventType(@Param("id") long id,
                              @Param("displayName") String displayName,
                              @Param("category") String category,
                              @Param("needsAction") boolean needsAction,
                              @Param("sortOrder") int sortOrder,
                              @Param("enabled") boolean enabled);

    int deleteDeviceEventType(@Param("t") long tenantId, @Param("id") long id);

    /**
     * 设备业务状态字典（客户文档 §1.5 的口径）。
     *
     * <p><b>与 {@code device_state.status} 不是一回事</b>：后者是**运行时健康/占用**
     * 标记（idle / running / error），前者是**业务状态机**（设备"在干什么"：
     * 初始化中 / 等待交互 / 转盘运行中 / 操作位执行工序 …）。
     * ⇒ 两套并存，各管各的，别混用。
     *
     * <p>{@code device_kind} 用**平台自己的 device_type 口径**
     * （extractor / dispenser / stirrer / robot / plate_hotel），
     * 不自造一套 ⇒ 字典和设备天然对得上。
     */
    List<Map<String, Object>> selectDeviceBusinessStates(
            @Param("t") long tenantId, @Param("deviceKind") String deviceKind);

    int insertDeviceBusinessState(@Param("t") long tenantId,
                                   @Param("deviceKind") String deviceKind,
                                   @Param("stateCode") String stateCode,
                                   @Param("stateName") String stateName,
                                   @Param("sortOrder") int sortOrder);

    int updateDeviceBusinessState(@Param("id") long id,
                                   @Param("stateName") String stateName,
                                   @Param("sortOrder") Integer sortOrder,
                                   @Param("enabled") Boolean enabled);

    int deleteDeviceBusinessState(@Param("t") long tenantId, @Param("id") long id);

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

    // ---------------------------------------------------------------- 隐式搬运段（P2-3）

    /** 设备（resource_node.id）的装载口 device_point.id（standalone 位置）。 */
    Long selectLoadPortPointId(@Param("t") long tenantId, @Param("deviceResourceId") long deviceResourceId);

    /** 写一条搬运段（TransferPlan 的可观测副本）；seq 由 SQL 按 run 内递增自动算。 */
    /**
     * 人工/扫码确认路径落搬运段（P2 遗留补齐）。
     *
     * <p>为什么单独一条：`insertTransportSegment` 的 robot 是必填 long，
     * 而人工确认时执行者（机械臂）已无从得知（事务行里没有 robot 列）⇒ 允许 NULL。
     * 去重键 `(run_id, from_position_id, to_position_id)`：重复确认不重复落段。
     */
    /** 点位实体 id（resource_node）→ 配置层 device_point.id（transport_segment 的外键口径）。 */
    Long selectDevicePointIdByResource(@Param("t") long tenantId,
                                       @Param("resourceId") long resourceId);

    int insertTransportSegmentManual(@Param("t") long tenantId,
                                     @Param("runId") long runId,
                                     @Param("fromPos") Long fromPos,
                                     @Param("toPos") Long toPos,
                                     @Param("carrier") String carrier);

    int insertTransportSegment(@Param("t") long tenantId,
                               @Param("runId") long runId,
                               @Param("runNodeId") Long runNodeId,
                               @Param("robotResourceId") Long robotResourceId,
                               @Param("fromPositionId") Long fromPositionId,
                               @Param("toPositionId") Long toPositionId,
                               @Param("carrier") String carrier,
                               @Param("status") String status);

    /** P2-10：删除 created_at 早于 cutoff 的历史搬运段（保留期清理）。 */
    int deleteTransportSegmentsBefore(@Param("t") long tenantId, @Param("cutoff") String cutoff);

    // ---------------------------------------------------------------- 工位（P2-1）

    /** 某设备的工位列表（resource_node kind='workstation'）+ 占用态。 */
    List<Map<String, Object>> selectWorkstations(@Param("t") long tenantId,
                                                 @Param("deviceResourceId") Long deviceResourceId);

    /** 第一个空闲工位的 device_point.id（auto_first_free 分配用）。 */
    Long selectFirstFreeWorkstation(@Param("t") long tenantId,
                                    @Param("deviceResourceId") long deviceResourceId);

    /** PF-28：deck_in/deck_out 不成对的设备（双交接位需成对）。 */
    List<Map<String, Object>> selectDeckUnpairedDevices(@Param("t") long tenantId);

    /** 节点有无绑定 + 有无 XML resource，用于回填 binding_mode。 */
    int updateNodeBindingMode(@Param("t") long tenantId,
                              @Param("workflowId") long workflowId,
                              @Param("nodeKey") String nodeKey,
                              @Param("mode") String mode);
}
