package com.labos.kernel.driver;

/**
 * P2 阶段 C · L5 有效等待交互投影（纯逻辑）。
 *
 * <p>只有 {@code WAITING} 且属于当前会话、当前步骤、本次预期交互的等待才有效；
 * 已消费 / 已取消 / 已过期 / 会话不符 / 步骤不符 一律不得授权机械臂操作交换位。
 */
public final class WaitingInteractionProjection {

    public static final String WAITING = "WAITING";
    public static final String CONSUMED = "CONSUMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    /** 状态本身是否有效（只有 WAITING 有效）。 */
    public static boolean isEffective(String status) {
        return WAITING.equals(status);
    }

    /**
     * 该等待交互是否能授权「当前会话 + 当前步骤 + 本次交互」的动作。
     * 任一无匹配都拒绝（fail-closed）。
     */
    public static boolean authorizes(String status, String deviceSessionId,
                                     String workflowStepId, String interactionId,
                                     String currentSession, String currentStep,
                                     String currentInteraction) {
        if (!isEffective(status)) {
            return false;
        }
        if (deviceSessionId == null || !deviceSessionId.equals(currentSession)) {
            return false;
        }
        if (workflowStepId == null || !workflowStepId.equals(currentStep)) {
            return false;
        }
        if (interactionId == null || !interactionId.equals(currentInteraction)) {
            return false;
        }
        return true;
    }

    private WaitingInteractionProjection() {
    }
}
