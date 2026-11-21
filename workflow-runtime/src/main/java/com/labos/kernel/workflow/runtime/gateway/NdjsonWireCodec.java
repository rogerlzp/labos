package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.command.DeviceCommandAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Default {@link GatewayWireCodec} speaking newline-delimited JSON ({@link GatewayWireProtocol})
 * with strict epoch-bound {@code HEARTBEAT_PING}/{@code HEARTBEAT_PONG} and explicit
 * {@code EVENT_ACK}/{@code EVENT_NACK}.
 */
public final class NdjsonWireCodec implements GatewayWireCodec {
    public static final NdjsonWireCodec INSTANCE = new NdjsonWireCodec();
    private static final byte[] DELIMITER = "\n".getBytes(StandardCharsets.UTF_8);

    private NdjsonWireCodec() {}

    @Override
    public String protocolName() {
        return "ndjson";
    }

    @Override
    public byte[] frameDelimiter() {
        return DELIMITER.clone();
    }

    @Override
    public boolean supportsAppHeartbeat() {
        return true;
    }

    @Override
    public boolean supportsEventAck() {
        return true;
    }

    @Override
    public byte[] encodeCommandSend(DeviceCommandAdapter.DeviceCommandEnvelope envelope) throws IOException {
        return GatewayWireProtocol.encodeCommandSend(envelope);
    }

    @Override
    public byte[] encodeHeartbeatPing(String deviceId, String pingId, long sentAtEpochMs) throws IOException {
        return GatewayWireProtocol.encodeHeartbeatPing(deviceId, pingId, sentAtEpochMs);
    }

    @Override
    public byte[] encodeEventAck(
            String deviceId, String sourceMessageId, UUID commandId, String status) throws IOException {
        return GatewayWireProtocol.encodeEventAck(deviceId, sourceMessageId, commandId, status);
    }

    @Override
    public byte[] encodeEventNack(
            String deviceId, String sourceMessageId, UUID commandId,
            boolean retryable, String reason) throws IOException {
        return GatewayWireProtocol.encodeEventNack(deviceId, sourceMessageId, commandId, retryable, reason);
    }

    @Override
    public GatewayWireProtocol.InboundFrame decodeInboundFrame(String frameText) throws IOException {
        return GatewayWireProtocol.decodeInboundLine(frameText);
    }
}
