package com.labos.kernel.recovery;

import java.time.Duration;

/**
 * 恢复策略：错误码 → 该怎么处理。
 *
 * <p>{@code errorCode} 与 {@code scope} 允许为 null，表示通配：
 * <ul>
 *   <li>{@code ("EC-05-1006", COMMAND)} —— 最精确</li>
 *   <li>{@code ("EC-05-1006", null)}    —— 该错误码的所有层级</li>
 *   <li>{@code (null, COMMAND)}         —— 所有错误在指令层的默认处理</li>
 *   <li>{@code (null, null)}            —— 全局兜底</li>
 * </ul>
 *
 * <p>{@code priority} 数字越小越优先，用于同精度策略间的排序。
 */
public record RecoveryPolicy(
        String errorCode,
        RecoveryScope scope,
        RecoveryStrategy strategy,
        int maxAttempts,
        Backoff backoff,
        Duration baseDelay,
        int priority,
        boolean enabled) {

    public RecoveryPolicy {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为空");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts 不能为负: " + maxAttempts);
        }
        if (baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay 不能为空或负数");
        }
    }

    /** 该策略是否适用于给定上下文。 */
    public boolean matches(ErrorContext ctx) {
        if (!enabled) {
            return false;
        }
        boolean codeOk = errorCode == null || errorCode.equals(ctx.errorCode());
        boolean scopeOk = scope == null || scope == ctx.scope();
        return codeOk && scopeOk;
    }

    /**
     * 匹配精度：2=错误码+层级精确，1=只匹配其一，0=全通配。
     * 精度相同时再比 priority。
     */
    public int specificity() {
        int s = 0;
        if (errorCode != null) s++;
        if (scope != null) s++;
        return s;
    }
}
