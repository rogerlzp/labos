package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * P2 阶段 C · L5 有效等待交互投影 数据访问。
 */
public interface WaitingInteractionMapper {

    /** 登记/覆盖一条等待交互（device+session+interaction 唯一）。 */
    int upsertWaiting(@Param("tenantId") long tenantId,
                      @Param("deviceResourceId") long deviceResourceId,
                      @Param("deviceSessionId") String deviceSessionId,
                      @Param("workflowStepId") String workflowStepId,
                      @Param("interactionId") String interactionId,
                      @Param("status") String status,
                      @Param("stateVersion") String stateVersion);

    /** 查当前有效等待（WAITING + 会话 + 步骤）；不存在返回 null。 */
    Map<String, Object> selectEffective(@Param("tenantId") long tenantId,
                                        @Param("deviceResourceId") long deviceResourceId,
                                        @Param("deviceSessionId") String deviceSessionId,
                                        @Param("workflowStepId") String workflowStepId);

    /** 消费：WAITING → CONSUMED（原子）；返回 1 = 成功。 */
    int consumeWaiting(@Param("tenantId") long tenantId,
                       @Param("id") long id);
}
