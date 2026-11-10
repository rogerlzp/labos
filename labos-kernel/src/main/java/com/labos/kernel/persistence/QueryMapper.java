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

    /**
     * 按 code 取型号 id（**不带 tenant**，code 由调用方转大写）。
     *
     * <p>与 {@link #selectCapabilityId} 不同：那条带 tenant_id。
     * 设备注册脚本按全局唯一 code 查，沿用原语义。
     */
    Long selectCapabilityIdGlobal(String code);

    /** 这批 workflow id 里哪些真的存在（编排校验用）。 */
    List<Long> selectExistingWorkflowIds(long tenantId, List<Long> ids);

    /** 资源节点名称。 */
    String selectResourceName(long resourceId);

    /** run 详情头；不存在返回 null。 */
    Map<String, Object> selectRunDetail(long tenantId, long runId);

    /** run 详情的节点列表（含父节点/层位/循环序号排序）。 */
    List<Map<String, Object>> selectRunDetailNodes(long runId);

    /** run 详情的事件列表（按 payload.seq，最多 100 条）。 */
    List<Map<String, Object>> selectRunEvents(long runId);

    /** 流程计划节点（task 的 node_key -> name）。 */
    List<Map<String, Object>> selectWorkflowTaskNames(long tenantId, long workflowId);

    /** 一次执行里各节点的实际状态（只取父节点、loop 0）。 */
    List<Map<String, Object>> selectRunNodeStates(long tenantId, long runId);

    /** 实验列表页（岛、流程、绑定工位名、流程数，一次查全）。 */
    List<Map<String, Object>> selectExperiments(long tenantId, int limit);

    /** 甘特时间轴的 run_node 行（相对毫秒由 Java 侧换算）。 */
    List<Map<String, Object>> selectTimelineNodes(long runId);

    /** 实验编排的流程列表（含流程名/码与任务节点数）。 */
    List<Map<String, Object>> selectExperimentProcesses(long tenantId, long experimentId);

    /** 恢复策略清单（带错误码，`*` 表示通配）。 */
    List<Map<String, Object>> selectPolicies(long tenantId);

    /** 最近的 run 列表（含 loop_index=0 的节点数）。 */
    List<Map<String, Object>> selectRecentRuns(long tenantId, int limit);

    /** 当前进行中的节点（含已下发步骤串）；无则返回 null。 */
    Map<String, Object> selectCurrentNode(long runId);

    /** 某流程的 run 列表（含完成/人工/失败计数）。 */
    List<Map<String, Object>> selectWorkflowRuns(long tenantId, long workflowId, int limit);

    /** 该资源上正在跑的节点还剩多少秒（ETA 用）；无在跑节点返回 null。 */
    Double selectRemainingSec(long tenantId, long resourceId);

    /** 实验流程的循环次数覆盖值（null = 用默认）。 */
    int updateExperimentProcessLoopCount(long tenantId, long experimentId, int seq,
                                         String loopCount);

    /** 时长模型（按样本数倒序，limit 由调用方钳位）。 */
    List<Map<String, Object>> selectDurations(long tenantId, int limit);

    /** 实验变量列表。 */
    List<Map<String, Object>> selectExperimentVariables(long tenantId, long experimentId);

    /** 设备方法清单（按 resource_node.id 关联，含型号码与参数定义）。 */
    List<Map<String, Object>> selectDeviceMethods(long resourceId);

    /** 能力清单（code + name），设备注册脚本在型号不存在时打印出来排障。 */
    List<Map<String, Object>> selectAllCapabilities();

    /** 全部流程 id（预检全量扫描）。 */
    List<Long> selectAllWorkflowIds(long tenantId);

    /** 租户下用户数（首次启动判断要不要播种子账号）。 */
    int countUsers(long tenantId);

    /** 登录成功回写 last_login_at。 */
    int touchLastLogin(long userId);

    /** workflow.attr->>'maxIterations'（原样字符串，调用方自己解析，兼容脏值）。 */
    String selectWorkflowMaxIterations(long tenantId, long workflowId);

    /** run.input->'vars'（JSON 文本，调用方解析）。 */
    String selectRunVars(long tenantId, long runId);

    /** run.attendance 当前值（运行中可被切换）。 */
    String selectRunAttendance(long tenantId, long runId);

    /** 设备方法的超时上限（秒），无配置返回 0。 */
    long selectDeviceLockTimeoutSec(long tenantId, long deviceInstanceId);

    /**
     * 错误码本体字段的动态 UPDATE（哪些列会被写由 XML 的 &lt;if&gt; 决定）。
     *
     * <p>severity 是 PG 枚举，必须 {@code ::error_severity}；scope/title/detail
     * 是 varchar，原实现没有 cast，保持一致。
     */
    int updateErrorCodeFields(long tenantId, long id,
                              @Param("scope") String scope,
                              @Param("severity") String severity,
                              @Param("title") String title,
                              @Param("detail") String detail);

    /**
     * 只改「主策略」（priority 最小的那条）。
     *
     * <p>COALESCE 表示「没传就不动」，所以 null 不会被写成 NULL。
     */
    int updatePrimaryRecoveryPolicy(long tenantId, long errorCodeId,
                                    @Param("strategy") String strategy,
                                    @Param("maxAttempts") String maxAttempts,
                                    @Param("params") String params,
                                    @Param("enabled") String enabled);

    /**
     * 人工插队：数组顺序即执行顺序，priority 从 100 递减。
     *
     * <p>原来按 index 逐条 batch update；现在用 CASE 一次写完，
     * 「顺序 → priority」的换算在 SQL 里一眼可见。
     */
    int reorderExperimentPriorities(long tenantId, @Param("ids") List<Long> ids);

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
