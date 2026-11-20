package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.command.DeviceCommandExecutionService;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Thin Event SPI bridge connecting {@link DeviceGateway} inbound device events to
 * {@link DeviceCommandExecutionService#ingestDeviceEvent}.
 *
 * <p>Always attributes the Inbox {@code source} to {@link InboundDeviceEvent#authenticatedDeviceId()}
 * (the TCP connection's bound device identity) rather than any untrusted payload field.
 */
public final class RuntimeDeviceEventBridge implements DeviceEventSink {

    public record BridgeNotification(
            InboundDeviceEvent event,
            DeviceCommandExecutionService.InboxIngestionOutcome outcome,
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
    public void onDeviceEvent(InboundDeviceEvent event) {
        Objects.requireNonNull(event, "event");
        UUID tenantId = Objects.requireNonNull(tenantResolver.apply(event), "resolved tenantId");
        try {
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
            listener.accept(new BridgeNotification(event, outcome, null));
        } catch (RuntimeException ex) {
            listener.accept(new BridgeNotification(event, null, ex));
            throw ex;
        }
    }
}
