package com.labos.kernel.recovery;

/**
 * 决策输出：引擎算出的处置方案。
 *
 * <p>{@code reason} 是必填的——它同时用于告警文案、审计轨迹和事后复盘。
 * <p>最大的问题是「出错了但不知道为什么、也不知道系统打算怎么办」，
 * 这里要求每一次决策都必须能自解释。
 */
public record RecoveryDecision(
        RecoveryAction action,
        long delayMillis,
        Long targetResourceId,
        String reason) {

    public RecoveryDecision {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis 不能为负: " + delayMillis);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空——每次决策都必须可解释");
        }
    }

    public static RecoveryDecision retry(long delayMillis, String reason) {
        return new RecoveryDecision(RecoveryAction.RETRY, delayMillis, null, reason);
    }

    public static RecoveryDecision reassign(Long targetResourceId, String reason) {
        if (targetResourceId == null) {
            throw new IllegalArgumentException("REASSIGN 必须指定目标资源");
        }
        return new RecoveryDecision(RecoveryAction.REASSIGN, 0L, targetResourceId, reason);
    }

    public static RecoveryDecision skip(String reason) {
        return new RecoveryDecision(RecoveryAction.SKIP, 0L, null, reason);
    }

    public static RecoveryDecision abort(String reason) {
        return new RecoveryDecision(RecoveryAction.ABORT, 0L, null, reason);
    }

    public static RecoveryDecision manual(String reason) {
        return new RecoveryDecision(RecoveryAction.MANUAL, 0L, null, reason);
    }

    /** 该决策是否意味着本次执行到此为止（不再自动推进）。 */
    public boolean isTerminal() {
        return action == RecoveryAction.MANUAL
                || action == RecoveryAction.ABORT
                || action == RecoveryAction.SKIP;
    }
}
