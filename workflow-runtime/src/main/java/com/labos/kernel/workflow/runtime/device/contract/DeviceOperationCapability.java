package com.labos.kernel.workflow.runtime.device.contract;

import java.util.List;
import java.util.Objects;

/** Orthogonal operation capability declared inside {@code HELLO_ACK.devices[].capabilities}. */
public record DeviceOperationCapability(
        String operationName,
        String idempotencyClass,
        int defaultTimeoutMs,
        boolean interruptible,
        boolean supportsCancel,
        boolean supportsJournalQuery,
        boolean supportsNativeHardwareQuery,
        ReconciliationPolicy reconciliationPolicy,
        List<String> emitsWaitingEvents) {

    public DeviceOperationCapability {
        if (operationName == null || operationName.isBlank()) {
            throw new IllegalArgumentException("operationName is required");
        }
        if (idempotencyClass == null || idempotencyClass.isBlank()) {
            throw new IllegalArgumentException("idempotencyClass is required");
        }
        if (defaultTimeoutMs <= 0) {
            throw new IllegalArgumentException("defaultTimeoutMs must be positive");
        }
        Objects.requireNonNull(reconciliationPolicy, "reconciliationPolicy");
        emitsWaitingEvents = emitsWaitingEvents == null ? List.of() : List.copyOf(emitsWaitingEvents);
    }
}
