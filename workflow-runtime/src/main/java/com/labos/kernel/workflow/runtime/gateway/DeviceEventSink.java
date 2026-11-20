package com.labos.kernel.workflow.runtime.gateway;

import java.util.Objects;
import java.util.UUID;

/**
 * Event SPI decoupling {@link DeviceGateway} from Workflow Runtime persistence/application services.
 *
 * <p>Heartbeats are consumed inside the Gateway and NEVER delivered to this sink.
 * {@link InboundDeviceEvent#authenticatedDeviceId()} is always bound to the TCP connection's own
 * registered {@code deviceId}, never trusted from untrusted wire payloads.
 */
@FunctionalInterface
public interface DeviceEventSink {
    void onDeviceEvent(InboundDeviceEvent event);

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
