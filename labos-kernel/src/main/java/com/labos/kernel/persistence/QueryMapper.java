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

    /** 这条流程用到的设备资源（task 节点的 resource_id 去重）。 */
    List<Long> selectWorkflowResources(long tenantId, long workflowId);

    /** 当前占着某资源的 run 节点（ETA 明细）；无则返回 null。 */
    Map<String, Object> selectBusyResourceHolder(long tenantId, long resourceId);

    /** 正在跑的 run 汇总（process-status 的库侧兜底）。 */
    List<Map<String, Object>> selectRunningProcessStatus(long tenantId);

    /** resource_node.id -> device_instance.id（未软删，取一条）。 */
    Long selectInstanceId(long tenantId, long resourceId);

    /** 按设备编码找资源节点 id（kind='device'）。 */
    Long selectResourceIdByDeviceCode(long tenantId, String code);

    /** 某个历史版本的 BPMN XML；不存在返回 null。 */
    String selectGraphHistoryXml(long tenantId, long workflowId, long historyId);

    /** 错误码软删。 */
    int softDeleteErrorCode(long tenantId, long id);

    /** 删除恢复策略。 */
    int deleteRecoveryPolicy(long tenantId, long errorCodeId, long policyId);

    /** 错误码是否已被同级（或 NULL 通配）的启用策略覆盖。 */
    boolean scopeCovered(long tenantId, long errorCodeId);

    /** 用户清单。 */
    List<Map<String, Object>> selectUsers(long tenantId);

    /** 驱动清单（未软删）。 */
    List<Map<String, Object>> selectDrivers(long tenantId);

    /** 设备型号 + 部署台数（一次子查询，避免逐型号 N+1）。 */
    List<Map<String, Object>> selectDeviceModels(long tenantId);

    /** 编排流程列表（category='choreography'）。 */
    List<Map<String, Object>> selectChoreographies(long tenantId, int limit);

    /** 某实验的执行记录（最近 20 条）。 */
    List<Map<String, Object>> selectExperimentRuns(long tenantId, long experimentId);

    /** 审计日志（filter 为 null 时取全部）。 */
    List<Map<String, Object>> selectAuditLog(long tenantId, String filter, int limit);

    /** 图历史版本列表（最近 30 条）。 */
    List<Map<String, Object>> selectGraphHistory(long tenantId, long workflowId);

    /** 某方法的参数定义；不存在返回 null。 */
    Map<String, Object> selectMethodParams(long tenantId, String method);

    /** 未挂到岛上的设备（有 device_instance 但父节点不是 island）。 */
    List<Map<String, Object>> selectUnassignedDevices(long tenantId);

    /** 实验的工位交接清单。 */
    List<Map<String, Object>> selectExperimentHandoffs(long tenantId, long experimentId);

    /** 命令 Outbox（state 为 null 时不过滤）。 */
    List<Map<String, Object>> selectCommandOutbox(long tenantId, String state, int limit);

    /** 搬运事务（status 为 null 时不过滤）。 */
    List<Map<String, Object>> selectTransferTransactions(long tenantId, String status, int limit);

    /** 板位证据（plateBarcode 为 null 时不过滤）。 */
    List<Map<String, Object>> selectPositionEvidence(long tenantId, String plateBarcode,
                                                     int limit);

    /** 隔离中的 run（含反查实验 id 的子查询）。 */
    List<Map<String, Object>> selectQuarantinedRuns(long tenantId, int limit);

    /** 人工介入记录。 */
    List<Map<String, Object>> selectInterventionRecords(long tenantId, int limit);

    /** 实验绑定的工位（含名称）。 */
    List<Map<String, Object>> selectExperimentWorkcells(long tenantId, long experimentId);

    /** 岛动线图；不存在返回 null。 */
    String selectIslandFlow(long tenantId, long islandId);

    /** 保存岛动线（按 island_id 覆盖）。 */
    int upsertIslandFlow(long tenantId, long islandId, String graphJson);

    /** 实验编排图；不存在返回 null。 */
    String selectExperimentGraph(long tenantId, long experimentId);

    /** 保存实验编排图（按 experiment_id 覆盖）。 */
    int upsertExperimentGraph(long tenantId, long experimentId, String graphJson);

    /** 机器人点位（CSV 与列表共用的原始行）。 */
    List<Map<String, Object>> selectRobotPoints(long tenantId, long islandId);

    /** 写设备在资源树上的坐标（jsonb attr 合并，不动其它键）。 */
    int updateDevicePosition(long tenantId, long resourceId, double x, double y);

    /** 机器人点位明细（列表页用）。 */
    List<Map<String, Object>> selectRobotPointDetails(long tenantId, long islandId);

    /** 从资源树同步点位（岛下设备，排除机器人自身）；返回受影响行数。 */
    int syncRobotPoints(long tenantId, long islandId, long robotResourceId);

    /** 错误发生记录（最近 N 条）。 */
    List<Map<String, Object>> selectErrorOccurrences(long tenantId, int limit);

    /** 删岛（只删 kind='island' 的资源节点）。 */
    int deleteIslandNode(long tenantId, long islandId);

    /** 设备实例软删（保留 resource_node 供历史 run_node 追溯）。 */
    int softDeleteDeviceInstance(long tenantId, long resourceId);

    /** 更新恢复策略（COALESCE：没传的字段不动）。 */
    int updateRecoveryPolicy(long tenantId, long id, String strategy,
                             Integer maxAttempts, Integer priority, Boolean enabled);

    /** 设备离线告警（含离线秒数）。 */
    List<Map<String, Object>> selectDeviceAlerts(long tenantId, int limit);

    /** 交接记录（status 为 null 时不过滤）。 */
    List<Map<String, Object>> selectHandoffs(long tenantId, String status, int limit);

    /** 设备池（islandId 为 null/<=0 时全部）。 */
    List<Map<String, Object>> selectDevicePool(long tenantId, Long islandId);

    /** 告警聚合（按 fingerprint，排除 info 与已解决）。 */
    List<Map<String, Object>> selectAlerts(long tenantId, int limit);

    /** 节点绑定的方法参数与资源 id；不存在返回 null。 */
    Map<String, Object> selectNodeBinding(long tenantId, long workflowId, String nodeKey);

    /** 策略校验：错误码是否存在 / 历史命中 / 被更高优先级覆盖。 */
    Map<String, Object> selectPolicyValidation(long tenantId, String errorCode, int priority);

    /** 错误码清单：三重可选过滤（severity / scope / keyword），keyword 走 ILIKE。 */
    List<Map<String, Object>> selectErrorCodes(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("severity") String severity,
            @org.apache.ibatis.annotations.Param("scope") String scope,
            @org.apache.ibatis.annotations.Param("keyword") String keyword);

    /** 拓扑：岛 + 其下设备（一次 JOIN 出全部字段，Java 侧按岛分组）。 */
    List<Map<String, Object>> selectTopology(long tenantId);

    /** 新建用户（密码 hash 与 salt 由调用方算好）。 */
    int insertUser(long tenantId, String username, String displayName, String role,
                   String passwordHash, String salt);

    /** 新建错误码，返回 id。MyBatis 的 <insert> 不能带 resultType，这里用 <select>。 */
    Long insertErrorCode(long tenantId, String code, String scope, String severity,
                         String title, String detail, String source);

    /** 告警处置（按 tenant+fingerprint 覆盖）。 */
    int upsertAlertState(long tenantId, String fingerprint, String status,
                         String assignee, String note);

    /** 记一条板位证据，返回 id。 */
    Long insertPositionEvidence(long tenantId, String plateBarcode, Long resourceId,
                                String type, Long runId, String operator, String note);

    /** 流程时间轴估算：任务节点 + 历史 P50 + 型号库估值，一次 CTE 取全。 */
    List<Map<String, Object>> selectWorkflowTimelineNodes(long tenantId, long workflowId);

    /** 实验执行头（experiment_id / status / expanded_steps）；不存在返回 null。 */
    Map<String, Object> selectExperimentRunHeader(long tenantId, long runId);

    /** 实验编排计划（每条流程 × loop_count）。 */
    List<Map<String, Object>> selectExperimentProcessPlan(long tenantId, long experimentId);

    /** 实验执行的实际步骤行。 */
    List<Map<String, Object>> selectExperimentRunStepRows(long tenantId, long runId);

    /** 建岛，返回 id。 */
    Long insertIsland(long tenantId, String code, String name, String path);

    /** 记一条人工介入记录，返回 id。 */
    Long insertIntervention(long tenantId, Long manualWindowId, Long runId,
                            Long experimentRunId, String operator, String action,
                            String plateBarcode, String sampleId, String fromLocation,
                            String toLocation, String result, String note);

    /** 新增一条工位交接（重复则忽略）。 */
    int insertExperimentHandoff(long tenantId, long experimentId,
                                long fromWorkcellId, long toWorkcellId);

    /** 新增一条实验工位绑定（重复则忽略）。 */
    int insertExperimentWorkcell(long tenantId, long experimentId, long workcellId);

    /** 新建实验，返回 id。 */
    Long insertExperiment(long tenantId, String experimentNo, String name,
                          Integer priority, Long islandId);

    /** 改用户（COALESCE：没传的字段不动）。 */
    int updateUserFields(long tenantId, long id, String role, Boolean enabled,
                         String passwordHash, String salt);

    /** 导入一个机器人点位（同 robot+code 覆盖，未传的列保留旧值）。 */
    int upsertRobotPoint(long tenantId, long islandId, long robotResourceId,
                         String code, String name, java.math.BigDecimal x,
                         java.math.BigDecimal y, java.math.BigDecimal z,
                         Long deviceResourceId, String action);

    /** 设备移出岛（放回设备池）。 */
    int detachDeviceFromIsland(long tenantId, long resourceId);

    /** 设备挂到岛下（path 由调用方按岛 path + 设备 code 拼好）。 */
    int attachDeviceToIsland(long tenantId, long resourceId, long islandId, String path);

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
