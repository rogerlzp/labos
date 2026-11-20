package com.labos.kernel.workflow.runtime.gateway;

import java.util.Objects;
import java.util.UUID;

/**
 * Event SPI decoupling {@link DeviceGateway} from Workflow Runtime persistence/application services.
 *
 * <p>Heartbeats are consumed inside the Gateway and NEVER delivered to this sink.
 * {@link InboundDeviceEvent#authenticatedDeviceId()} is always bound to the TCP connection's own
 * registered {@code deviceId}, never trusted from untrusted wire payloads.
 *
 * <p>Every call returns an explicit {@link DeliveryResult} so the Gateway can emit
 * {@code EVENT_ACK} (durable persistence confirmed) or {@code EVENT_NACK} (retryable transient
 * failure vs permanent protocol/domain rejection) and NEVER silently drops a device event.
 */
@FunctionalInterface
public interface DeviceEventSink {
    DeliveryResult onDeviceEvent(InboundDeviceEvent event);

    enum Disposition {
        /** Event was durably persisted (APPLIED or idempotent DUPLICATE_REPLAY). */
        ACKED,
        /** Transient persistence/infrastructure failure; device MUST retry with same sourceMessageId. */
        NACK_RETRYABLE,
        /** Permanent protocol/domain violation; device should NOT retry unchanged payload. */
        NACK_PERMANENT
    }

    record DeliveryResult(Disposition disposition, String status, String reason) {
        public DeliveryResult {
            Objects.requireNonNull(disposition, "disposition");
        }

        public static DeliveryResult acked(String status) {
            return new DeliveryResult(Disposition.ACKED, status == null ? "APPLIED" : status, null);
        }

        public static DeliveryResult nackRetryable(String reason) {
            return new DeliveryResult(Disposition.NACK_RETRYABLE, null,
                    reason == null || reason.isBlank() ? "TRANSIENT_FAILURE" : reason);
        }

        public static DeliveryResult nackPermanent(String reason) {
            return new DeliveryResult(Disposition.NACK_PERMANENT, null,
                    reason == null || reason.isBlank() ? "PERMANENT_REJECTION" : reason);
        }
    }

    record InboundDeviceEvent(
            String authenticatedDeviceId,
            String claimedDeviceId,
            UUID runId,
            UUID commandId,
            String sourceMessageId,
            String messageType,
            String targetState,
            String deviceSequence,
            String outcomeJson) {
        public InboundDeviceEvent {
            if (authenticatedDeviceId == null || authenticatedDeviceId.isBlank())
                throw new IllegalArgumentException("authenticatedDeviceId is required");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(commandId, "commandId");
            if (sourceMessageId == null || sourceMessageId.isBlank())
                throw new IllegalArgumentException("sourceMessageId is required");
            if (messageType == null || messageType.isBlank())
                throw new IllegalArgumentException("messageType is required");
            if (targetState == null || targetState.isBlank())
                throw new IllegalArgumentException("targetState is required");
            outcomeJson = (outcomeJson == null || outcomeJson.isBlank()) ? "{}" : outcomeJson;
        }
    }
}
