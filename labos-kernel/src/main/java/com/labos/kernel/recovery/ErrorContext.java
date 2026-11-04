package com.labos.kernel.recovery;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 决策输入：一次错误发生时的完整上下文。
 *
 * <p>刻意做成扁平不可变对象——决策引擎必须是纯函数，
 * 相同的 ErrorContext 必须得出相同的 RecoveryDecision，
 * 这样才能做回放、仿真和单元测试。
 */
public record ErrorContext(
        String errorCode,
        RecoveryScope scope,
        int attempt,
        Long currentResourceId,
        String capabilityCode,
        Set<Long> failedResourceIds,
        Set<Long> candidateResourceIds,
        String traceId) {

    public ErrorContext {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能为负: " + attempt);
        }
        failedResourceIds = failedResourceIds == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(failedResourceIds));
        candidateResourceIds = candidateResourceIds == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(candidateResourceIds));
    }

    public static Builder builder(String errorCode, RecoveryScope scope) {
        return new Builder(errorCode, scope);
    }

    /** 便于测试的简化构造：只关心错误码与尝试次数。 */
    public static ErrorContext of(String errorCode, RecoveryScope scope, int attempt) {
        return builder(errorCode, scope).attempt(attempt).build();
    }

    public static final class Builder {
        private final String errorCode;
        private final RecoveryScope scope;
        private int attempt = 0;
        private Long currentResourceId;
        private String capabilityCode;
        private Set<Long> failedResourceIds = Set.of();
        private Set<Long> candidateResourceIds = Set.of();
        private String traceId;

        private Builder(String errorCode, RecoveryScope scope) {
            this.errorCode = errorCode;
            this.scope = scope;
        }

        public Builder attempt(int v) { this.attempt = v; return this; }
        public Builder currentResourceId(Long v) { this.currentResourceId = v; return this; }
        public Builder capabilityCode(String v) { this.capabilityCode = v; return this; }
        public Builder failedResourceIds(Set<Long> v) { this.failedResourceIds = v; return this; }
        public Builder candidateResourceIds(Set<Long> v) { this.candidateResourceIds = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }

        public ErrorContext build() {
            return new ErrorContext(errorCode, scope, attempt, currentResourceId,
                    capabilityCode, failedResourceIds, candidateResourceIds, traceId);
        }
    }
}
