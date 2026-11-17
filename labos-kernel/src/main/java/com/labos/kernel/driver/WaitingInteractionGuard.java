package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.WaitingInteractionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * P2 阶段 C · L5 等待交互互锁的运行时守卫（机械臂操作交换位前查有效等待）。
 *
 * <p>只有当搬运节点携带 {@code interactionId} 参数时才启用 L5（该节点是「交换位操作」）；
 * 未携带视为非交换位操作，跳过。启用后必须存在当前会话/步骤的有效 WAITING 交互，
 * 且 interaction 匹配，否则拒绝（fail-closed）。
 */
public final class WaitingInteractionGuard {

    private static final Logger log = LoggerFactory.getLogger(WaitingInteractionGuard.class);

    /**
     * 搬运下发前检查。@return null = 放行（或未携带 interactionId 参数）；非 null = 拒绝。
     */
    public static DriverResult checkBeforeTransfer(DbConfig db, long tenantId, long runId,
                                                   long deviceResourceId,
                                                   String deviceSessionId,
                                                   String workflowStepId,
                                                   String interactionId) {
        if (db == null || runId <= 0 || deviceResourceId <= 0) {
            return null;   // 上下文不全，无从判定（也不落审计）
        }
        if (interactionId == null || interactionId.isBlank()) {
            // 未携带交互上下文 → 非交换位操作，跳过 L5。
            // 落 SKIP：区分「没判」与「放行」——否则 L5 全跳过时表里空空，无从解释。
            rec(db, tenantId, runId, deviceResourceId, InterlockDecision.SKIP,
                    null, deviceSessionId, workflowStepId, null,
                    "未携带 interactionId，非交换位操作，L5 跳过", null);
            return null;
        }
        Map<String, Object> eff;
        try {
            eff = Mappers.get(WaitingInteractionMapper.class)
                    .selectEffective(tenantId, deviceResourceId,
                            deviceSessionId, workflowStepId);
        } catch (RuntimeException e) {
            String reason = "等待交互查询失败：" + e.getMessage();
            rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                    null, deviceSessionId, workflowStepId, interactionId,
                    reason, "EC-STATE-UNKNOWN");
            return DriverResult.failed("0", "EC-STATE-UNKNOWN", reason, null, 0);
        }
        if (eff == null) {
            String reason = "无有效等待交互（会话/步骤无 WAITING）";
            rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                    null, deviceSessionId, workflowStepId, interactionId,
                    reason, "EC-INTERLOCK-NO-WAIT");
            return DriverResult.failed("0", "EC-INTERLOCK-NO-WAIT", reason, null, 0);
        }
        String ix = (String) eff.get("interactionId");
        if (ix == null || !ix.equals(interactionId)) {
            String reason = "等待交互不匹配（期望 " + interactionId + "，当前 " + ix + "）";
            rec(db, tenantId, runId, deviceResourceId, InterlockDecision.REJECT,
                    ix, deviceSessionId, workflowStepId, interactionId,
                    reason, "EC-INTERLOCK-NO-WAIT");
            return DriverResult.failed("0", "EC-INTERLOCK-NO-WAIT", reason, null, 0);
        }
        // 有效等待匹配 → 放行
        rec(db, tenantId, runId, deviceResourceId, InterlockDecision.ALLOW,
                ix, deviceSessionId, workflowStepId, interactionId,
                "有效等待匹配", null);
        return null;
    }

    /** 落一条 L5 决策快照（旁路审计，失败不影响判定）。 */
    private static void rec(DbConfig db, long tenantId, long runId, long resourceId,
                            String decision, String factValue,
                            String deviceSessionId, String workflowStepId, String interactionId,
                            String reason, String errorCode) {
        InterlockDecision.Rec r = new InterlockDecision.Rec(
                InterlockDecision.L5_WAIT, decision)
                .fact("waiting_interaction", factValue)
                .times(null, java.time.OffsetDateTime.now().toString())
                .session(deviceSessionId, workflowStepId, interactionId)
                .reason(reason)
                .error(errorCode);
        InterlockDecision.record(db, tenantId, runId, null, null, resourceId, r);
    }

    private WaitingInteractionGuard() {
    }
}
