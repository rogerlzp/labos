package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;

import java.util.Map;

/**
 * 读侧的高频单查询，语句见 {@code mapper/QueryMapper.xml}。
 *
 * <p>收进来的都是「一条 SELECT、被多处复用」的 SQL：健康检查、资源码、
 * 流程图 XML、设备码存在性、管理员计数、用户角色。放到 XML 后不用在
 * 5000 行的 {@code QueryService} 里翻字符串，也方便直接看索引与 WHERE 条件。
 */
public interface QueryMapper {

    Map<String, Object> health();

    String selectResourceCode(long tenantId, long resourceId);

    /** 流程图 XML；不存在时为 null。 */
    String selectWorkflowXml(long tenantId, long workflowId);

    /** run 对应的 workflow_id 与当前状态（取不到时 null）。 */
    Map<String, Object> selectRunWorkflow(long tenantId, long runId);

    boolean existsDeviceCode(long tenantId, String code);

    int countEnabledAdmins(long tenantId);

    /** role / enabled 两列；{@code forUpdate=true} 时加行锁。 */
    Map<String, Object> selectUserRoleEnabled(long tenantId, long userId, boolean forUpdate);

    int countDevicesUnderIsland(long tenantId, long islandId);

    /** 按 id 查策略作用域（PATCH 没带 scope 时用）。查不到返回 null。 */
    String selectPolicyScope(long tenantId, long policyId);

    /** run 的实际开始时间（甘特图相对时间的基准）。 */
    java.sql.Timestamp selectRunActualStart(long tenantId, long runId);

    /**
     * 串行化「用户角色/启用状态」写入的事务级咨询锁。
     *
     * <p>固定 key（不是 per-user）：保护判定看的是全局 admin 计数，
     * 锁必须覆盖「任意两个用户同时改角色」的交叉场景。
     */
    void lockUserRole();

    /** 岛的 path（移设备/建设备时拼子节点路径）。 */
    String selectIslandPath(long tenantId, long islandId);

    Long selectCapabilityId(long tenantId, String code);

    Long selectDeviceIdByCode(long tenantId, String code);

    String selectWorkflowName(long tenantId, long workflowId);

    /** 全部流程 id（预检全量扫描）。 */
    List<Long> selectAllWorkflowIds(long tenantId);

    /** workflow.attr->>'maxIterations'（原样字符串，调用方自己解析，兼容脏值）。 */
    String selectWorkflowMaxIterations(long tenantId, long workflowId);

    /** run.input->'vars'（JSON 文本，调用方解析）。 */
    String selectRunVars(long tenantId, long runId);

    /** run.attendance 当前值（运行中可被切换）。 */
    String selectRunAttendance(long tenantId, long runId);

    /** 设备方法的超时上限（秒），无配置返回 0。 */
    long selectDeviceLockTimeoutSec(long tenantId, long deviceInstanceId);

    /** 资源节点的编码（按 kind 限定，避免拿到同 id 的其它类型）。 */
    String selectNodeCode(long tenantId, long resourceNodeId, String kind);

    /** 资源节点的父节点（位置 -> 所属设备）；无父节点返回 null。 */
    Long selectParentIdOfNode(long tenantId, long resourceNodeId);

    /** device_instance.id -> resource_node.id（原 SQL 无 tenant 条件，保持一致）。 */
    Long selectDeviceResourceByInstance(long deviceInstanceId);

    /** 批量取实验状态（调序前校验）。id IN 用 foreach，替换手写 ANY(?) + createArrayOf。 */
    List<Map<String, Object>> selectExperimentStatuses(long tenantId, List<Long> ids);

    int bumpExperimentVersion(long tenantId, long experimentId);

    int setDeviceMethodInterruptible(long tenantId, long deviceId,
                                     String method, boolean value);

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
}
