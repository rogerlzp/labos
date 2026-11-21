package com.labos.kernel.workflow.runtime.command;

import com.labos.kernel.workflow.runtime.device.contract.DeviceCommandEnvelope;

import java.util.Objects;

/**
 * Minimal SPI between {@link DeviceCommandExecutionService} and a device transport adapter.
 *
 * <p>In G2-B only mock/in-memory implementations are bound; real physical device drivers
 * are deliberately excluded from this milestone.
 */
@FunctionalInterface
public interface DeviceCommandAdapter {
    SendResult send(DeviceCommandEnvelope envelope) throws Exception;

    enum SendStatus {
        /** Command was accepted by the device/mock transport; terminal outcome will arrive via Inbox. */
        ACCEPTED_ASYNC,
        /** Synchronous transport returned a verified terminal SUCCEEDED outcome immediately. */
        COMPLETED_IMMEDIATELY,
        /** Synchronous transport returned a verified terminal FAILED outcome immediately. */
        COMPLETED_IMMEDIATELY_FAILED,
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

        public static SendResult completedImmediatelyFailed(
                String sourceMessageId, String deviceSequence, String outcomeJson) {
            if (sourceMessageId == null || sourceMessageId.isBlank())
                throw new IllegalArgumentException("sourceMessageId is required for immediate failure");
            return new SendResult(SendStatus.COMPLETED_IMMEDIATELY_FAILED, sourceMessageId, deviceSequence,
                    outcomeJson, null);
        }

        public static SendResult deliveryUncertain(String errorMessage) {
            return new SendResult(SendStatus.DELIVERY_UNCERTAIN, null, null, "{}", errorMessage);
        }
    }
}
