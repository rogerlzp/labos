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

    /** 时长模型 p50（method -> ms）。 */
    List<Map<String, Object>> selectDurationP50(long tenantId);

    /** 流程里每个任务节点的能力（node_key -> capability_code）。 */
    List<Map<String, Object>> selectWorkflowCapabilities(long tenantId, long workflowId);
}
