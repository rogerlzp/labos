package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

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
