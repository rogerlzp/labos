package com.labos.kernel.workflow.runtime.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labos.kernel.workflow.runtime.command.DeviceCommandAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link GatewayWireCodec} bridging existing LabOS {@code .NET} TCP drivers and simulator servers
 * ({@code #!LABSEP!#} delimiter, {@code message_type=1} command send, {@code message_type=5}
 * status/finish replies with {@code method_status=1(RUNNING)/2(SUCCEEDED)/3(FAILED)}).
 *
 * <p>Key protocol mappings:
 * <ul>
 *   <li>Outbound {@code message_id} is set to {@code envelope.commandId()} so replies correlate
 *       deterministically to {@code command_ledger} even across restarts.</li>
 *   <li>Inbound {@code sourceMessageId} is synthesized as {@code <message_id>:status:<method_status>}
 *       so intermediate {@code method_status=1} (RUNNING) and terminal {@code method_status=2/3}
 *       events sharing one {@code message_id} do not collide as {@code PROTOCOL_VIOLATION} while
 *       duplicate frames of the same status remain idempotent {@code DUPLICATE_REPLAY}.</li>
 *   <li>Legacy driver replies do not carry {@code runId}; {@code DeviceEventFrame.runId()} is
 *       {@code null} and resolved authoritatively from {@code command_ledger} by {@code commandId}.</li>
 *   <li>Legacy drivers do not implement Gateway {@code HEARTBEAT_PING} or {@code EVENT_ACK};
 *       both capability flags return {@code false} and disconnect detection relies on TCP KeepAlive + EOF.</li>
 * </ul>
 */
public final class LabSepWireCodec implements GatewayWireCodec {
    public static final LabSepWireCodec INSTANCE = new LabSepWireCodec();
    public static final String SEPARATOR = "#!LABSEP!#";

    private static final byte[] DELIMITER = SEPARATOR.getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();

    private LabSepWireCodec() {}

    @Override
    public String protocolName() {
        return "labsep";
    }

    @Override
    public byte[] frameDelimiter() {
        return DELIMITER.clone();
    }

    @Override
    public boolean supportsAppHeartbeat() {
        return false;
    }

    @Override
    public boolean supportsEventAck() {
        return false;
    }

    @Override
    public byte[] encodeCommandSend(DeviceCommandAdapter.DeviceCommandEnvelope envelope) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        ObjectNode root = JSON.createObjectNode();
        root.put("message_id", envelope.commandId().toString());
        root.put("message_type", 1);
        root.put("method", envelope.commandKind());
        root.put("equipment_id", envelope.deviceId());
        JsonNode params = JSON.readTree(envelope.payloadJson());
        root.set("parameters", params.isObject() ? params : JSON.createObjectNode());
        return (JSON.writeValueAsString(root) + SEPARATOR).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] encodeHeartbeatPing(String deviceId, String pingId, long sentAtEpochMs) {
        throw new UnsupportedOperationException("LabSepWireCodec does not support application-level HEARTBEAT_PING");
    }

    @Override
    public byte[] encodeEventAck(String deviceId, String sourceMessageId, UUID commandId, String status) {
        throw new UnsupportedOperationException("LabSepWireCodec does not support EVENT_ACK frames");
    }

    @Override
    public byte[] encodeEventNack(
            String deviceId, String sourceMessageId, UUID commandId, boolean retryable, String reason) {
        throw new UnsupportedOperationException("LabSepWireCodec does not support EVENT_NACK frames");
    }

    @Override
    public GatewayWireProtocol.InboundFrame decodeInboundFrame(String frameText) throws IOException {
        if (frameText == null || frameText.isBlank()) {
            return null;
        }
        JsonNode root = JSON.readTree(frameText);
        if (!root.isObject()) {
            throw new IllegalArgumentException("LabSep wire frame must be a JSON object");
        }
        JsonNode msgIdNode = root.get("message_id");
        if (msgIdNode == null || msgIdNode.isNull() || !msgIdNode.isTextual() || msgIdNode.asText().isBlank()) {
            // Unsolicited driver heartbeat/status broadcast without message_id: ignore safely.
            return null;
        }
        String messageId = msgIdNode.asText().trim();
        UUID commandId;
        try {
            commandId = UUID.fromString(messageId);
        } catch (IllegalArgumentException nonRuntimeId) {
            // Non-UUID message_id not issued by Workflow Runtime: ignore safely without mutating state.
            return null;
        }

        JsonNode statusNode = root.get("method_status");
        if (statusNode == null || !statusNode.isInt()) {
            throw new IllegalArgumentException("LabSep reply missing integer method_status: " + messageId);
        }
        int methodStatus = statusNode.asInt();
        JsonNode errorNode = root.get("error");
        boolean hasError = errorNode != null && !errorNode.isNull()
                && ((errorNode.isObject() && errorNode.size() > 0)
                || (errorNode.isTextual() && !errorNode.asText().isBlank()));

        String messageType;
        String targetState;
        if (methodStatus == 1 && !hasError) {
            messageType = "PROGRESS";
            targetState = "RUNNING";
        } else if (methodStatus == 2 && !hasError) {
            messageType = "COMMAND_RESULT";
            targetState = "SUCCEEDED";
        } else {
            messageType = "COMMAND_RESULT";
            targetState = "FAILED";
        }

        String claimedDeviceId = optionalText(root, "equipment_id");
        String deviceSequence = optionalText(root, "timestamp");
        String sourceMessageId = hasError && (methodStatus == 1 || methodStatus == 2)
                ? (messageId + ":status:" + methodStatus + ":error")
                : (messageId + ":status:" + methodStatus);

        ObjectNode outcome = JSON.createObjectNode();
        outcome.put("methodStatus", methodStatus);
        if (root.has("method") && root.get("method").isTextual()) {
            outcome.put("method", root.get("method").asText());
        }
        if (root.has("data") && !root.get("data").isNull()) {
            outcome.set("data", root.get("data"));
        }
        if (hasError) {
            outcome.set("error", errorNode);
        }
        if (root.has("process_info") && !root.get("process_info").isNull()) {
            outcome.set("processInfo", root.get("process_info"));
        }

        return new GatewayWireProtocol.DeviceEventFrame(
                claimedDeviceId,
                null, // Resolved authoritatively from command_ledger by commandId.
                commandId,
                sourceMessageId,
                messageType,
                targetState,
                deviceSequence,
                JSON.writeValueAsString(outcome));
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        return node.isTextual() ? node.asText() : node.asText();
    }
}
