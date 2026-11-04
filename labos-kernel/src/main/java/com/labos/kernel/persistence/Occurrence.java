package com.labos.kernel.persistence;

import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

/**
 * 一条待落库的错误记录。
 *
 * <p>{@code severity} 决定它会不会触发告警：
 * <ul>
 *   <li>{@code info} —— 跳过类（SKIPPED）。留痕但不告警，避免把历史 Ignore 的无害噪音变成告警洪水</li>
 *   <li>{@code warn} / {@code error} —— 转人工或终止，需要人看</li>
 * </ul>
 */
public record Occurrence(
        long runId,
        long runNodeId,
        Long resourceId,
        String errorCode,
        RecoveryScope scope,
        int attempt,
        RecoveryStrategy strategy,
        String outcome,
        String message,
        String contextJson,
        String severity) {

    public Occurrence {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        if (severity == null || severity.isBlank()) {
            throw new IllegalArgumentException("severity 不能为空");
        }
    }
}
