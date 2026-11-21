package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.device.contract.DeviceCommandEnvelope;

import java.io.IOException;
import java.util.UUID;

/**
 * Pluggable TCP wire framing and message codec for {@link TcpDeviceConnection}.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link NdjsonWireCodec} (default): newline-delimited JSON speaking {@link GatewayWireProtocol}
 *       with application-level {@code HEARTBEAT_PING}/{@code HEARTBEAT_PONG} and {@code EVENT_ACK}/{@code EVENT_NACK}.</li>
 *   <li>{@link LabSepWireCodec}: legacy LabOS {@code .NET} driver protocol using {@code #!LABSEP!#}
 *       framing, {@code message_type=1/5}, and {@code method_status=1/2/3}.</li>
 * </ul>
 */
public interface GatewayWireCodec {

    /** Stable codec identifier for configuration and diagnostics (e.g. {@code "ndjson"}, {@code "labsep"}). */
    String protocolName();

    /** Non-empty delimiter byte sequence terminating each wire frame. */
    byte[] frameDelimiter();

    /** Whether the remote peer supports application-level {@code HEARTBEAT_PING}/{@code HEARTBEAT_PONG}. */
    boolean supportsAppHeartbeat();

    /** Whether the remote peer understands Gateway {@code EVENT_ACK}/{@code EVENT_NACK} frames. */
    boolean supportsEventAck();

    byte[] encodeCommandSend(DeviceCommandEnvelope envelope) throws IOException;

    byte[] encodeHeartbeatPing(String deviceId, String pingId, long sentAtEpochMs) throws IOException;

    byte[] encodeEventAck(String deviceId, String sourceMessageId, UUID commandId, String status) throws IOException;

    byte[] encodeEventNack(
            String deviceId, String sourceMessageId, UUID commandId,
            boolean retryable, String reason) throws IOException;

    /**
     * Decode one complete inbound frame (with delimiter already stripped).
     * Returning {@code null} indicates a benign, non-command peer frame (for example a legacy
     * driver status broadcast without {@code message_id}) that must be ignored without mutating state.
     */
    GatewayWireProtocol.InboundFrame decodeInboundFrame(String frameText) throws IOException;
}
