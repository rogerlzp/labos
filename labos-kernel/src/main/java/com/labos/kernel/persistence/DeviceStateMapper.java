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
}
