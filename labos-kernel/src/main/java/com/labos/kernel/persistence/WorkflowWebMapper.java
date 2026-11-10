package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/**
 * 流程域 Service（编译/预测/岛锁/影子运行）的数据访问，语句见
 * {@code mapper/WorkflowWebMapper.xml}。
 */
public interface WorkflowWebMapper {

    /** resource_node.id -> device_instance.id；不是设备返回 null。 */
    Long selectDeviceInstanceId(long resourceNodeId);

    /** 抢执行期岛锁（resource_booking 的 EXCLUDE 约束）；违反约束返回 null。 */
    Long insertIslandLock(long tenantId, long islandId);

    int releaseIslandLock(long bookingId);

    /** 每个 workflow 的任务节点数（只数 task）。 */
    List<Map<String, Object>> selectWorkflowTaskCounts(long tenantId);

    /** 节点绑定的设备（params->>'bound_resource_id'）。 */
    List<Map<String, Object>> selectBoundResources(long tenantId, long workflowId);

    /**
     * 节点绑定设备：{@code resourceId} 传 null = 显式解绑。
     * 「null 入参 = 不改动」的判断在调用方，不在这里。
     */
    int updateNodeResource(long tenantId, long workflowId, String nodeKey,
                           Long resourceId);

    /**
     * 节点绑定方法/参数：只写 params（workflow_node 没有 protocol_name 列）。
     * methodParamsJson 为 null 时保留 method_params，只改 protocol_name。
     */
    int updateNodeParams(long tenantId, long workflowId, String nodeKey,
                         String methodParamsRaw, String methodParamsJson,
                         boolean builtin, String method);

    /** 时长模型 p50（method -> ms）。 */
    List<Map<String, Object>> selectDurationP50(long tenantId);

    /** 流程里每个任务节点的能力（node_key -> capability_code）。 */
    List<Map<String, Object>> selectWorkflowCapabilities(long tenantId, long workflowId);

    /** 编排流程列表（category='choreography'）。 */
    List<Map<String, Object>> selectChoreographies(long tenantId, int limit);

    /** 流程图 XML；不存在时为 null。 */
    String selectWorkflowXml(long tenantId, long workflowId);

    /** 节点绑定的方法参数与资源 id；不存在返回 null。 */
    Map<String, Object> selectNodeBinding(long tenantId, long workflowId, String nodeKey);

    /** 流程时间轴估算：任务节点 + 历史 P50 + 型号库估值，一次 CTE 取全。 */
    List<Map<String, Object>> selectWorkflowTimelineNodes(long tenantId, long workflowId);

    /** 这条流程用到的设备资源（task 节点的 resource_id 去重）。 */
    List<Long> selectWorkflowResources(long tenantId, long workflowId);

    /** 当前占着某资源的 run 节点（ETA 明细）；无则返回 null。 */
    Map<String, Object> selectBusyResourceHolder(long tenantId, long resourceId);

    /** 某流程的 run 列表（含完成/人工/失败计数）。 */
    List<Map<String, Object>> selectWorkflowRuns(long tenantId, long workflowId, int limit);

    /** 时长模型（按样本数倒序，limit 由调用方钳位）。 */
    List<Map<String, Object>> selectDurations(long tenantId, int limit);

    /** 图历史版本列表（最近 30 条）。 */
    List<Map<String, Object>> selectGraphHistory(long tenantId, long workflowId);

    /** 某个历史版本的 BPMN XML；不存在返回 null。 */
    String selectGraphHistoryXml(long tenantId, long workflowId, long historyId);


    /** 该资源上正在跑的节点还剩多少秒（ETA 用）；无在跑节点返回 null。 */
    Double selectRemainingSec(long tenantId, long resourceId);


    String selectResourceCode(long tenantId, long resourceId);

}
