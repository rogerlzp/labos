package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

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

    /** 层位被哪个 run 预占（position_state.reserved_by_run）。 */
    Long selectPositionReservedBy(long tenantId, long resourceId);

    /** 谁占着这台设备（device_state.current_run_id）；空表示空闲。 */
    Long selectCurrentRunId(long tenantId, long resourceId);

    /** 门禁状态回写（设备访问控制）。 */
    int updateAccessState(long tenantId, long accessId, String state);

    /** 某设备某方法的超时上限（秒），无配置返回 0。 */
    long selectMethodTimeoutSec(long tenantId, long deviceInstanceId, String method);

    /** 该设备全部方法里的超时上限（秒），无配置返回 0。 */
    long selectLibraryTimeoutSec(long tenantId, long deviceInstanceId);

    /**
     * 设备的真实能力编码（resource_capability），查不到返回 null。
     *
     * <p>⚠ {@code resource_capability} 表**没有 tenant_id 列** —— 带上会抛
     * SQLException（"column rc.tenant_id does not exist"），异常被调用方吞掉
     * 就静默返回 null、能力回落成节点上猜出来的那个。
     */
    String selectCapabilityCodeOfInstance(long tenantId, long deviceInstanceId);

    /** 流程节点级超时（秒），无配置返回 0。 */
    long selectWorkflowNodeTimeoutSec(long tenantId, long workflowNodeId);

    /** device_instance.id -> resource_node.id 全量映射（只取映射到的）。 */
    java.util.List<java.util.Map<String, Object>> selectInstanceResourcePairs();

    /** 全部资源节点 id（校验工作流节点引用的资源是否还在）。 */
    java.util.List<Long> selectAllResourceIds();

    /** 设备有没有配通道（没配说明是存量数据，跳过通道锁）。 */
    boolean hasPorts(long tenantId, long resourceId);

    /** 当前占用数；没有该 port 行时返回 null。 */
    Integer selectPortInUse(long tenantId, long portId);

    /** 抢通道（占用数 +1，未满才成功，RETURNING 用 select 承载）；没抢到返回 null。 */
    Integer tryAcquirePort(@Param("tenantId") long tenantId, @Param("portId") long portId);

    /** 释放通道（占用数 -1，不会减到负数）。 */
    int releasePort(@Param("tenantId") long tenantId, @Param("portId") long portId);

    /**
     * 该设备指定方向的可用通道 id（both 方向可当 in/out 用；按 capacity 降序取第一个）；
     * 没配通道或都满了返回 null。
     */
    Long selectAvailablePort(@Param("tenantId") long tenantId,
                             @Param("resourceId") long resourceId,
                             @Param("direction") String direction);

    /** 有板设备的 device_instance.id 集合（WorkflowService 选设备的"忙集"）。 */
    java.util.List<Long> selectPlateBusyInstanceIds(@Param("tenantId") long tenantId);

    /** 岛（resource_node.parent_id）下全部未软删设备的 device_instance.id。 */
    java.util.List<Long> selectIslandInstanceIds(@Param("tenantId") long tenantId,
                                                 @Param("islandId") long islandId);

    /**
     * resource_node.id -&gt; device_instance.id（本租户未软删、resource 非空）。
     *
     * <p>与 {@link #selectInstanceResourcePairs} 方向相反且带租户过滤 ——
     * 那是全量 id-&gt;resource 映射（驱动路由用），这是 resource-&gt;实例（节点绑定用）。
     */
    java.util.List<java.util.Map<String, Object>> selectResourceInstancePairs(
            @Param("tenantId") long tenantId);

    /**
     * method -&gt; capability_id 多数表决（rn=1 即每个 method 票数最高者；
     * 平票取 capability_id 小的，确定性可回放）。
     */
    java.util.List<java.util.Map<String, Object>> selectMethodCapabilityVotes();

    // ---- PositionLock（位置锁） ----

    /** 占位无期限（回程承诺）：reserved_until 落 NULL，只有持有者/人工能放。 */
    int reservePositionForever(long tenantId, long resourceId, long runId);

    /** 原子预约（TTL 秒数文本如 "120 seconds"，同一条件出现两次）。 */
    int reservePosition(long tenantId, long resourceId, String ttlSeconds, long runId);

    /** 占不到时的现场（plateId）；无行返回 null（map 为 null）。 */
    java.util.Map<String, Object> selectPositionOccupant(long tenantId, long resourceId);

    /** 释放预约（不动板）。 */
    int releasePosition(long tenantId, long resourceId, long runId);

    /** 释放某 run 的全部预约（run 结束兜底）；返回清理条数。 */
    int releaseAllPositions(long tenantId, long runId);

    /** UPSERT 登记"板放进去了"（预约转实际占用）。 */
    int putPlateOnPosition(long tenantId, long resourceId, String plateId, long runId);

    /** 层位当前板号（无板/无行返回 null，调用方只用于在途标记）。 */
    String selectPlateOnPosition(long tenantId, long resourceId);

    /** 取板（归属校验：只占本 run 放的；返回 0 = 取不到）。 */
    int clearPlateFromPosition(long tenantId, long resourceId, long runId);

    /** 清掉过期预约（定时任务）；返回清理条数。 */
    int sweepExpiredReservations(long tenantId);

    /** 岛下所有层位占用状态（byIsland 画图用，键见 XML）。 */
    java.util.List<java.util.Map<String, Object>> selectPositionsByIsland(long tenantId,
                                                                          long islandId);

    /** 层位类型（kind::text）；不存在或类型不在允许集合返回 null（无行）。 */
    String selectPositionKind(long tenantId, long resourceId);

    /** 板标"在途"（SAVEPOINT 内调用，失败由调用方局部回滚）。 */
    int markPlateInTransfer(long tenantId, String barcode);

    /** 从 position_state 重算 device_state.has_plate（派生单一数据源）。 */
    int refreshDeviceHasPlate(long tenantId, long deviceResourceId);

    /** 设备下第一个真空闲层位（同 tryReserve 的三态判定）。 */
    Long selectFirstAvailablePosition(long tenantId, long deviceResourceId);

    /** 设备下第一个"本 run 放的板"的层位（归属过滤，fail-closed）。 */
    Long selectFirstOccupiedPosition(long tenantId, long deviceResourceId, long runId);

    /** 占位统计（withPlate/reserved/tracked）。 */
    java.util.Map<String, Object> selectPositionStats(long tenantId);

    // ---- PositionLock 冗余 plate 表（独立连接，失败只记日志） ----

    /** 冗余表登记板位置（UPDATE 命中返回 1）。 */
    int updatePlateLocation(long tenantId, String plateId, long positionResourceId);

    /** 板不在 plate 表时补一行（'unknown' 类型占位）。 */
    int insertPlateIfAbsent(long tenantId, String plateId, long positionResourceId);

    // ---- DeviceLock（设备锁，阶段3第21批收编）----

    /**
     * 抢设备（检查+占用一条 UPDATE，原子性由数据库保证）：只有 current_run_id 为空、
     * 或已经是自己时才更新成功。抢到返回资源 id，没抢到/无行返回 null。
     */
    Long tryAcquireDevice(@Param("tenantId") long tenantId,
                          @Param("resourceId") long resourceId,
                          @Param("runId") long runId);

    /**
     * 释放设备（只释放自己占的，不误伤别人）：status 仅在无 last_error 时回落 idle。
     */
    int releaseDevice(@Param("tenantId") long tenantId,
                      @Param("resourceId") long resourceId,
                      @Param("runId") long runId);

    /** 清孤儿锁（current_run_id 指向的 run 已是终态）；返回清理条数。 */
    int releaseOrphanDeviceLocks(@Param("tenantId") long tenantId);

    /**
     * 清超时锁（updated_at 早于阈值）；ttlSeconds 为秒数文本（与 {@code ' seconds'}
     * 拼接成 interval，与原语句一致），返回清理条数。
     */
    int releaseStaleDeviceLocks(@Param("tenantId") long tenantId,
                                @Param("ttlSeconds") String ttlSeconds);
}
