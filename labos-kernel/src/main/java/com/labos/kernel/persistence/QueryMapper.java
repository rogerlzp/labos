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





    /** run 的实际开始时间（甘特图相对时间的基准）。 */
    java.sql.Timestamp selectRunActualStart(long tenantId, long runId);



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



    /** 某个历史版本的 BPMN XML；不存在返回 null。 */
    String selectGraphHistoryXml(long tenantId, long workflowId, long historyId);







    /** 编排流程列表（category='choreography'）。 */
    List<Map<String, Object>> selectChoreographies(long tenantId, int limit);



    /** 图历史版本列表（最近 30 条）。 */
    List<Map<String, Object>> selectGraphHistory(long tenantId, long workflowId);




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


















    /** 节点绑定的方法参数与资源 id；不存在返回 null。 */
    Map<String, Object> selectNodeBinding(long tenantId, long workflowId, String nodeKey);







    /** 记一条板位证据，返回 id。 */
    Long insertPositionEvidence(long tenantId, String plateBarcode, Long resourceId,
                                String type, Long runId, String operator, String note);

    /** 流程时间轴估算：任务节点 + 历史 P50 + 型号库估值，一次 CTE 取全。 */
    List<Map<String, Object>> selectWorkflowTimelineNodes(long tenantId, long workflowId);





    /** 记一条人工介入记录，返回 id。 */
    Long insertIntervention(long tenantId, Long manualWindowId, Long runId,
                            Long experimentRunId, String operator, String action,
                            String plateBarcode, String sampleId, String fromLocation,
                            String toLocation, String result, String note);









    /** 甘特时间轴的 run_node 行（相对毫秒由 Java 侧换算）。 */
    List<Map<String, Object>> selectTimelineNodes(long runId);



    /** 最近的 run 列表（含 loop_index=0 的节点数）。 */
    List<Map<String, Object>> selectRecentRuns(long tenantId, int limit);

    /** 当前进行中的节点（含已下发步骤串）；无则返回 null。 */
    Map<String, Object> selectCurrentNode(long runId);

    /** 某流程的 run 列表（含完成/人工/失败计数）。 */
    List<Map<String, Object>> selectWorkflowRuns(long tenantId, long workflowId, int limit);

    /** 该资源上正在跑的节点还剩多少秒（ETA 用）；无在跑节点返回 null。 */
    Double selectRemainingSec(long tenantId, long resourceId);


    /** 时长模型（按样本数倒序，limit 由调用方钳位）。 */
    List<Map<String, Object>> selectDurations(long tenantId, int limit);



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





    /** 资源节点的父节点（位置 -> 所属设备）；无父节点返回 null。 */
    Long selectParentIdOfNode(long tenantId, long resourceNodeId);

    /** device_instance.id -> resource_node.id（原 SQL 无 tenant 条件，保持一致）。 */
    Long selectDeviceResourceByInstance(long deviceInstanceId);




}
