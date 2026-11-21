package com.labos.kernel.workflow.runtime.device.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable device command envelope shared between Core, Gateway, and contract fixtures.
 *
 * <p>Contract v1.x requires non-whitespace {@code workstationId} and {@code operationName} on every
 * dispatch envelope without nullable legacy fallbacks.
 */
public record DeviceCommandEnvelope(
        UUID tenantId,
        UUID runId,
        UUID attemptId,
        UUID commandId,
        UUID dispatchId,
        String workstationId,
        String deviceId,
        String commandKind,
        String operationName,
        String idempotencyClass,
        String payloadJson) {

    public DeviceCommandEnvelope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(dispatchId, "dispatchId");
        if (workstationId == null || workstationId.isBlank()) {
            throw new IllegalArgumentException("workstationId is required");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (commandKind == null || commandKind.isBlank()) {
            throw new IllegalArgumentException("commandKind is required");
        }
        if (operationName == null || operationName.isBlank()) {
            throw new IllegalArgumentException("operationName is required");
        }
        if (idempotencyClass == null || idempotencyClass.isBlank()) {
            throw new IllegalArgumentException("idempotencyClass is required");
        }
        payloadJson = (payloadJson == null || payloadJson.isBlank()) ? "{}" : payloadJson;
    }
}
