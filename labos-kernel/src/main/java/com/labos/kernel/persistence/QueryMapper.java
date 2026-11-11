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















    String selectWorkflowName(long tenantId, long workflowId);

    /** 资源节点的编码（{@code resource_node.code}）；查不到返回 null。 */
    String selectNodeCode(@Param("tenantId") long tenantId, @Param("nodeId") long nodeId);

    /**
     * 按 code 取型号 id（**不带 tenant**，code 由调用方转大写）。
     *
     * <p>与 {@link #selectCapabilityId} 不同：那条带 tenant_id。
     * 设备注册脚本按全局唯一 code 查，沿用原语义。
     */
    Long selectCapabilityIdGlobal(String code);


    /** 资源节点名称。 */
    String selectResourceName(long resourceId);
















































































    /** 能力清单（code + name），设备注册脚本在型号不存在时打印出来排障。 */
    List<Map<String, Object>> selectAllCapabilities();

    /** 全部流程 id（预检全量扫描）。 */
    List<Long> selectAllWorkflowIds(long tenantId);

    /** 租户下用户数（首次启动判断要不要播种子账号）。 */
    int countUsers(long tenantId);

    /** 登录成功回写 last_login_at。 */
    int touchLastLogin(long userId);

    /** 首启播种子账号（仅 countUsers==0 时调用，role 固定 'admin'）。 */
    int insertSeedUser(@Param("tenantId") long tenantId,
                         @Param("username") String username,
                         @Param("displayName") String displayName,
                         @Param("passwordHash") String passwordHash,
                         @Param("salt") String salt);

    /** 登录校验取用户行（不存在即无行；enabled 由调用方判）。 */
    Map<String, Object> selectUserForLogin(@Param("tenantId") long tenantId,
                                           @Param("username") String username);

    /**
     * 审计写入 audit_log。before/after 为 JSON 文本（可空），
     * ip 可空；userId/entityId 可空。
     */
    int insertAuditLog(@Param("tenantId") long tenantId,
                       @Param("userId") Long userId,
                       @Param("username") String username,
                       @Param("action") String action,
                       @Param("entityType") String entityType,
                       @Param("entityId") Long entityId,
                       @Param("before") String before,
                       @Param("after") String after,
                       @Param("ip") String ip,
                       @Param("result") String result);

    /** RecipeCompiler 模板加载（capability/seq/method/sampleCount，按能力+序）。 */
    java.util.List<java.util.Map<String, Object>> selectCapabilityRecipes(long tenantId);

    /** workflow.attr->>'maxIterations'（原样字符串，调用方自己解析，兼容脏值）。 */
    String selectWorkflowMaxIterations(long tenantId, long workflowId);

    /** run.input->'vars'（JSON 文本，调用方解析）。 */
    String selectRunVars(long tenantId, long runId);

    /** run.attendance 当前值（运行中可被切换）。 */
    String selectRunAttendance(long tenantId, long runId);

    /** 设备方法的超时上限（秒），无配置返回 0。 */
    long selectDeviceLockTimeoutSec(long tenantId, long deviceInstanceId);





    /** 资源节点的父节点（位置 -> 所属设备）；无父节点返回 null。 */
    Long selectParentIdOfNode(long tenantId, long resourceNodeId);

    /** device_instance.id -> resource_node.id（原 SQL 无 tenant 条件，保持一致）。 */
    Long selectDeviceResourceByInstance(long deviceInstanceId);




}
