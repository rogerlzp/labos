package com.labos.kernel.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 有人值守窗口的纯逻辑层（功能台账 6.5，P0）。
 *
 * <p>规格依据：
 * <ul>
 *   <li>§1.1 门槛 2 / §2.2 / §6：有人值守（attended）出错时弹窗口，
 *       <b>默认项来自 {@code recovery_policy}</b>；无人值守只走恢复策略，
 *       结果是 MANUAL 时记 {@code error} 告警，不弹窗、不等人。</li>
 *   <li>§13.3：窗口对应 run 的 {@code waiting_human} 暂停态，
 *       由前端异步等人，不占用执行线程与岛锁。</li>
 *   <li>台账 6.7：{@code COMPENSATE} 尚未实现，执行降级为 MANUAL。</li>
 * </ul>
 *
 * <p>本类只做确定性判断：不连库、不起线程、不碰 UI、不读系统时间
 * （时间一律由调用方传入，便于回放）。落地层的开窗/落库/回写另行实现。
 */
public final class ManualWindow {

    public static final String ATTENDED = "attended";
    public static final String UNATTENDED = "unattended";

    /** 人可改选的动作集合（规格 §6）：续跑 / 重试 / 跳过 / 停止 / 仍交人工。 */
    public static final List<String> ALLOWED_ACTIONS = List.of(
            "CONTINUE", "RETRY", "SKIP", "ABORT", "MANUAL");

    /** 超时后保持暂停并升告警，绝不自动替人决定。 */
    public static final String TIMEOUT_ACTION = "MANUAL";

    public enum Status {
        PENDING, DECIDED, TIMED_OUT, EXPIRED
    }

    /** 弹窗展示所需的只读快照。 */
    public record View(
            String defaultAction,
            List<String> allowedActions,
            String decisionReason,
            boolean attended) {

        public View {
            if (defaultAction == null || defaultAction.isBlank()) {
                throw new IllegalArgumentException("defaultAction 不能为空");
            }
            if (decisionReason == null || decisionReason.isBlank()) {
                throw new IllegalArgumentException("decisionReason 不能为空——窗口必须能解释为什么停着");
            }
            allowedActions = List.copyOf(allowedActions);
        }
    }

    /**
     * 值守值归一化。空值/未知值一律保守回落 attended：
     * 宁可多弹一次窗口，也不能在无人值守口径不明确时静默跳过人。
     */
    public static String normalizeAttendance(String raw) {
        if (raw == null || raw.isBlank()) {
            return ATTENDED;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return UNATTENDED.equals(v) ? UNATTENDED : ATTENDED;
    }

    public static boolean isAttended(String raw) {
        return ATTENDED.equals(normalizeAttendance(raw));
    }

    /** 只有「有人值守 且 决策交人工」才开窗；其他情况沿用现有自动处置路径。 */
    public static boolean shouldOpen(String attendance, RecoveryDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
        return isAttended(attendance) && decision.action() == RecoveryAction.MANUAL;
    }

    /**
     * 默认项取自策略的<b>配置意图</b>（{@code recovery_policy.strategy}），
     * 不是 {@code RecoveryDecision.action}：触发开窗时后者恒为 MANUAL，
     * 拿它当默认项会让窗口失去「可改选」的意义（§2.2「默认项来自 recovery_policy」）。
     */
    public static String defaultActionOf(RecoveryPolicy policy) {
        if (policy == null || !policy.enabled()) {
            return "MANUAL";
        }
        return toAction(policy.strategy());
    }

    public static String toAction(RecoveryStrategy strategy) {
        if (strategy == null) {
            return "MANUAL";
        }
        return switch (strategy) {
            case RETRY -> "RETRY";
            case REASSIGN -> "REASSIGN";
            case SKIP -> "SKIP";
            case ABORT -> "ABORT";
            case MANUAL, COMPENSATE -> "MANUAL"; // COMPENSATE 台账 6.7 降级
        };
    }

    public static List<String> allowedActions() {
        return ALLOWED_ACTIONS;
    }

    /** 动作白名单校验，拒绝任何越权/拼写错误的值。 */
    public static String normalizeAction(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(v)) {
            throw new IllegalArgumentException("不支持的处置动作: " + raw);
        }
        return v;
    }

    /** pending 且未超时才可决定；已处置/已超时/已过期一律拒绝（幂等保护）。 */
    public static boolean isDecidable(Status status, Instant now, Instant timeoutAt) {
        if (status == null || now == null) {
            throw new IllegalArgumentException("status/now 不能为空");
        }
        if (status != Status.PENDING) {
            return false;
        }
        return timeoutAt == null || now.isBefore(timeoutAt);
    }

    public static boolean isExpired(Instant now, Instant timeoutAt) {
        return now != null && timeoutAt != null && !now.isBefore(timeoutAt);
    }

    /** 超时降级：保持 waiting_human 并升 error 告警，不自动执行默认项。 */
    public static String timeoutAction() {
        return TIMEOUT_ACTION;
    }

    /** §13.8：值守差异只体现在告警级别，弹窗不改变它。 */
    public static String alertLevel(String attendance) {
        return isAttended(attendance) ? "warn" : "error";
    }

    /** 同一 run + 错误码 + 节点只开一扇窗（与落库层的部分唯一索引同义）。 */
    public static String windowKey(long runId, String errorCode, String nodeKey) {
        return runId + "|" + (errorCode == null ? "" : errorCode)
                + "|" + (nodeKey == null ? "" : nodeKey);
    }

    public static View view(String attendance, RecoveryPolicy policy, RecoveryDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
        return new View(defaultActionOf(policy), ALLOWED_ACTIONS,
                decision.reason(), isAttended(attendance));
    }

    private ManualWindow() {
    }
}
