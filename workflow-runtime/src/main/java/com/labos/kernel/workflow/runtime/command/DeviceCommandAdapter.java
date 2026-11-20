package com.labos.kernel.workflow.runtime.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal SPI between {@link DeviceCommandExecutionService} and a device transport adapter.
 *
 * <p>In G2-B only mock/in-memory implementations are bound; real physical device drivers
 * are deliberately excluded from this milestone.
 */
@FunctionalInterface
public interface DeviceCommandAdapter {
    SendResult send(DeviceCommandEnvelope envelope) throws Exception;

    record DeviceCommandEnvelope(
            UUID tenantId,
            UUID runId,
            UUID attemptId,
            UUID commandId,
            UUID dispatchId,
            String deviceId,
            String commandKind,
            String idempotencyClass,
            String payloadJson) {
        public DeviceCommandEnvelope {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(dispatchId, "dispatchId");
            if (deviceId == null || deviceId.isBlank())
                throw new IllegalArgumentException("deviceId is required");
            if (commandKind == null || commandKind.isBlank())
                throw new IllegalArgumentException("commandKind is required");
            if (idempotencyClass == null || idempotencyClass.isBlank())
                throw new IllegalArgumentException("idempotencyClass is required");
            payloadJson = (payloadJson == null || payloadJson.isBlank()) ? "{}" : payloadJson;
        }
    }

    enum SendStatus {
        /** Command was accepted by the device/mock transport; terminal outcome will arrive via Inbox. */
        ACCEPTED_ASYNC,
        /** Synchronous transport returned a verified terminal SUCCEEDED outcome immediately. */
        COMPLETED_IMMEDIATELY,
        /** Delivery crossed the send boundary but transport could not confirm whether execution occurred. */
        DELIVERY_UNCERTAIN
    }

    record SendResult(
            SendStatus status,
            String sourceMessageId,
            String deviceSequence,
            String outcomeJson,
            String errorMessage) {
        public SendResult {
            Objects.requireNonNull(status, "status");
            outcomeJson = (outcomeJson == null || outcomeJson.isBlank()) ? "{}" : outcomeJson;
        }

        public static SendResult acceptedAsync() {
            return new SendResult(SendStatus.ACCEPTED_ASYNC, null, null, "{}", null);
        }

        public static SendResult completedImmediately(
                String sourceMessageId, String deviceSequence, String outcomeJson) {
            if (sourceMessageId == null || sourceMessageId.isBlank())
                throw new IllegalArgumentException("sourceMessageId is required for immediate completion");
            return new SendResult(SendStatus.COMPLETED_IMMEDIATELY, sourceMessageId, deviceSequence,
                    outcomeJson, null);
        }

        public static SendResult deliveryUncertain(String errorMessage) {
            return new SendResult(SendStatus.DELIVERY_UNCERTAIN, null, null, "{}", errorMessage);
        }
    }
}
