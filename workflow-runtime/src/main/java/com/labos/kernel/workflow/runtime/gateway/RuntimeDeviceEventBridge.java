package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Thin Event SPI bridge connecting {@link DeviceGateway} inbound device events to
 * {@link DeviceCommandExecutionService#ingestDeviceEvent}.
 *
 * <p>Always attributes the Inbox {@code source} to {@link InboundDeviceEvent#authenticatedDeviceId()}
 * (the TCP connection's bound device identity) rather than any untrusted payload field, and
 * translates outcomes into explicit {@link DeviceEventSink.DeliveryResult} values so transient
 * database errors emit retryable {@code EVENT_NACK} instead of being silently dropped.
 */
public final class RuntimeDeviceEventBridge implements DeviceEventSink {

    public record BridgeNotification(
            InboundDeviceEvent event,
            DeviceCommandExecutionService.InboxIngestionOutcome outcome,
            DeliveryResult deliveryResult,
            Throwable error) {}

    private final Function<InboundDeviceEvent, UUID> tenantResolver;
    private final DeviceCommandExecutionService commandService;
    private final Consumer<BridgeNotification> listener;

    public RuntimeDeviceEventBridge(
            UUID tenantId,
            DeviceCommandExecutionService commandService) {
        this(e -> Objects.requireNonNull(tenantId, "tenantId"), commandService, n -> {});
    }

    public RuntimeDeviceEventBridge(
            UUID tenantId,
            DeviceCommandExecutionService commandService,
            Consumer<BridgeNotification> listener) {
        this(e -> Objects.requireNonNull(tenantId, "tenantId"), commandService, listener);
    }

    public RuntimeDeviceEventBridge(
            Function<InboundDeviceEvent, UUID> tenantResolver,
            DeviceCommandExecutionService commandService,
            Consumer<BridgeNotification> listener) {
        this.tenantResolver = Objects.requireNonNull(tenantResolver, "tenantResolver");
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public DeliveryResult onDeviceEvent(InboundDeviceEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            UUID tenantId = Objects.requireNonNull(tenantResolver.apply(event), "resolved tenantId");
            DeviceCommandExecutionService.InboxIngestionOutcome outcome = commandService.ingestDeviceEvent(
                    tenantId,
                    event.runId(),
                    event.authenticatedDeviceId(),
                    event.sourceMessageId(),
                    event.messageType(),
                    event.commandId(),
                    event.targetState(),
                    event.deviceSequence(),
                    event.outcomeJson());
            DeliveryResult result = switch (outcome.status()) {
                case APPLIED -> DeliveryResult.acked("APPLIED");
                case DUPLICATE_REPLAY -> DeliveryResult.acked("DUPLICATE_REPLAY");
                case PROTOCOL_VIOLATION -> DeliveryResult.nackPermanent("PROTOCOL_VIOLATION");
            };
            listener.accept(new BridgeNotification(event, outcome, result, null));
            return result;
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                | IllegalArgumentException
                | IllegalStateException permanentRejection) {
            DeliveryResult result = DeliveryResult.nackPermanent(
                    permanentRejection.getClass().getSimpleName() + ": " + safeMessage(permanentRejection));
            listener.accept(new BridgeNotification(event, null, result, permanentRejection));
            return result;
        } catch (RuntimeException transientFailure) {
            // Includes Spring DataAccessException / PostgreSQL connectivity glitches -> retryable EVENT_NACK!
            DeliveryResult result = DeliveryResult.nackRetryable(
                    transientFailure.getClass().getSimpleName() + ": " + safeMessage(transientFailure));
            listener.accept(new BridgeNotification(event, null, result, transientFailure));
            return result;
        }
    }

    private static String safeMessage(Throwable t) {
        return t == null || t.getMessage() == null ? "" : t.getMessage();
    }
}
