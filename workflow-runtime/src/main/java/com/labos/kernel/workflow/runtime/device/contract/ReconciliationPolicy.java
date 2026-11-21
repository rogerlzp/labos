package com.labos.kernel.workflow.runtime.device.contract;

import java.util.Objects;

/** Read-only reconciliation policy governing {@code DELIVERY_UNKNOWN} recovery bounds. */
public record ReconciliationPolicy(
        boolean autoQueryAllowed,
        int maxQueryAttempts,
        long queryIntervalMs,
        long maxReconcileWindowMs,
        EvidenceStrength requiredSuccessStrength,
        String onExhaustedAction) {

    public static final String ESCALATE_UNKNOWN_AND_QUARANTINE = "ESCALATE_UNKNOWN_AND_QUARANTINE";

    public ReconciliationPolicy {
        if (maxQueryAttempts < 0) {
            throw new IllegalArgumentException("maxQueryAttempts must be non-negative");
        }
        if (queryIntervalMs < 0) {
            throw new IllegalArgumentException("queryIntervalMs must be non-negative");
        }
        if (maxReconcileWindowMs < 0) {
            throw new IllegalArgumentException("maxReconcileWindowMs must be non-negative");
        }
        Objects.requireNonNull(requiredSuccessStrength, "requiredSuccessStrength");
        if (onExhaustedAction == null || onExhaustedAction.isBlank()) {
            throw new IllegalArgumentException("onExhaustedAction is required");
        }
    }
}
