package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * P2 阶段 A · 互锁决策快照（只增不改）。
 */
public interface InterlockMapper {

    /** 落一条决策快照。返回受影响行数（1 = 成功）。 */
    int insertDecision(@Param("tenantId") long tenantId,
                        @Param("runId") Long runId,
                        @Param("nodeId") Long nodeId,
                        @Param("commandId") String commandId,
                        @Param("resourceId") Long resourceId,
                        @Param("interlockCode") String interlockCode,
                        @Param("factType") String factType,
                        @Param("factValue") String factValue,
                        @Param("observedAt") String observedAt,
                        @Param("receivedAt") String receivedAt,
                        @Param("stateVersion") String stateVersion,
                        @Param("deviceSessionId") String deviceSessionId,
                        @Param("workflowStepId") String workflowStepId,
                        @Param("interactionId") String interactionId,
                        @Param("decision") String decision,
                        @Param("reason") String reason,
                        @Param("errorCode") String errorCode);
}
