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
 * Line-delimited JSON (NDJSON, UTF-8, {@code '\n'} terminated) wire protocol for the
 * single-JVM Device Gateway TCP transport.
 *
 * <p>Outbound frames (Gateway -> Device):
 * <ul>
 *   <li>{@code COMMAND_SEND}: single-attempt command dispatch carrying {@code commandId},
 *       {@code dispatchId}, {@code runId}, {@code deviceId}, {@code commandKind},
 *       {@code idempotencyClass}, and JSON {@code payload}.</li>
 *   <li>{@code HEARTBEAT_PING}: transport liveness probe carrying {@code deviceId},
 *       {@code pingId}, and {@code sentAtEpochMs}.</li>
 *   <li>{@code EVENT_ACK}: explicit durable-persistence confirmation for a device event
 *       ({@code sourceMessageId}, {@code commandId}, {@code status}).</li>
 *   <li>{@code EVENT_NACK}: explicit rejection or retryable backpressure/transient-failure
 *       signal ({@code sourceMessageId}, {@code commandId}, {@code retryable}, {@code reason})
 *       so device events are NEVER silently dropped.</li>
 * </ul>
 *
 * <p>Inbound frames (Device -> Gateway):
 * <ul>
 *   <li>{@code HEARTBEAT_PONG} / {@code HEARTBEAT}: transport liveness response; must carry
 *       matching {@code deviceId} and an active unconsumed {@code pingId} for the current
 *       connection epoch; NEVER forwarded into Command state transitions.</li>
 *   <li>{@code DEVICE_EVENT}: command progress or terminal receipt carrying {@code sourceMessageId},
 *       {@code messageType}, {@code runId}, {@code commandId}, {@code deviceId}, {@code targetState},
 *       optional {@code deviceSequence}, and JSON {@code outcome}.</li>
 * </ul>
 */
public final class GatewayWireProtocol {
    public static final String FRAME_COMMAND_SEND = "COMMAND_SEND";
    public static final String FRAME_HEARTBEAT_PING = "HEARTBEAT_PING";
    public static final String FRAME_HEARTBEAT_PONG = "HEARTBEAT_PONG";
    public static final String FRAME_HEARTBEAT = "HEARTBEAT";
    public static final String FRAME_DEVICE_EVENT = "DEVICE_EVENT";
    public static final String FRAME_EVENT_ACK = "EVENT_ACK";
    public static final String FRAME_EVENT_NACK = "EVENT_NACK";

    private static final ObjectMapper JSON = new ObjectMapper();

    private GatewayWireProtocol() {}

    public sealed interface InboundFrame permits HeartbeatPongFrame, DeviceEventFrame {}

    public record HeartbeatPongFrame(String deviceId, String pingId, long deviceEpochMs)
            implements InboundFrame {}

    public record DeviceEventFrame(
            String claimedDeviceId,
            UUID runId,
            UUID commandId,
            String sourceMessageId,
            String messageType,
            String targetState,
            String deviceSequence,
            String outcomeJson) implements InboundFrame {
        public DeviceEventFrame {
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

    public static byte[] encodeCommandSend(DeviceCommandAdapter.DeviceCommandEnvelope envelope) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        ObjectNode root = JSON.createObjectNode();
        root.put("frameType", FRAME_COMMAND_SEND);
        root.put("commandId", envelope.commandId().toString());
        root.put("dispatchId", envelope.dispatchId().toString());
        root.put("runId", envelope.runId().toString());
        root.put("attemptId", envelope.attemptId().toString());
        root.put("deviceId", envelope.deviceId());
        root.put("commandKind", envelope.commandKind());
        root.put("idempotencyClass", envelope.idempotencyClass());
        JsonNode payloadNode = JSON.readTree(envelope.payloadJson());
        root.set("payload", payloadNode);
        return (JSON.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeHeartbeatPing(String deviceId, String pingId, long sentAtEpochMs) throws IOException {
        if (deviceId == null || deviceId.isBlank())
            throw new IllegalArgumentException("deviceId is required");
        if (pingId == null || pingId.isBlank())
            throw new IllegalArgumentException("pingId is required");
        ObjectNode root = JSON.createObjectNode();
        root.put("frameType", FRAME_HEARTBEAT_PING);
        root.put("deviceId", deviceId);
        root.put("pingId", pingId);
        root.put("sentAtEpochMs", sentAtEpochMs);
        return (JSON.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeEventAck(
            String deviceId, String sourceMessageId, UUID commandId, String status) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("frameType", FRAME_EVENT_ACK);
        root.put("deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        root.put("sourceMessageId", Objects.requireNonNull(sourceMessageId, "sourceMessageId"));
        if (commandId != null) root.put("commandId", commandId.toString());
        root.put("status", status == null || status.isBlank() ? "APPLIED" : status);
        return (JSON.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeEventNack(
            String deviceId, String sourceMessageId, UUID commandId,
            boolean retryable, String reason) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("frameType", FRAME_EVENT_NACK);
        root.put("deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        if (sourceMessageId != null) root.put("sourceMessageId", sourceMessageId);
        if (commandId != null) root.put("commandId", commandId.toString());
        root.put("retryable", retryable);
        root.put("reason", reason == null || reason.isBlank() ? "NACK" : reason);
        return (JSON.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static InboundFrame decodeInboundLine(String line) throws IOException {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Blank wire frame");
        }
        JsonNode root = JSON.readTree(line);
        if (!root.isObject()) {
            throw new IllegalArgumentException("Wire frame must be a JSON object");
        }
        String frameType = optionalText(root, "frameType");
        if (frameType == null || frameType.isBlank()) {
            String msgType = optionalText(root, "messageType");
            if (FRAME_HEARTBEAT.equals(msgType) || FRAME_HEARTBEAT_PONG.equals(msgType)) {
                frameType = FRAME_HEARTBEAT_PONG;
            } else if (msgType != null && !msgType.isBlank()) {
                frameType = FRAME_DEVICE_EVENT;
            } else {
                throw new IllegalArgumentException("Wire frame missing frameType/messageType");
            }
        }
        return switch (frameType) {
            case FRAME_HEARTBEAT_PONG, FRAME_HEARTBEAT -> new HeartbeatPongFrame(
                    optionalText(root, "deviceId"),
                    optionalText(root, "pingId"),
                    root.hasNonNull("deviceEpochMs") ? root.get("deviceEpochMs").asLong() : 0L);
            case FRAME_DEVICE_EVENT -> {
                String messageType = requireText(root, "messageType");
                if (FRAME_HEARTBEAT.equals(messageType) || FRAME_HEARTBEAT_PONG.equals(messageType)) {
                    throw new IllegalArgumentException(
                            "Heartbeat messageType is forbidden inside DEVICE_EVENT frames");
                }
                UUID runId = UUID.fromString(requireText(root, "runId"));
                UUID commandId = UUID.fromString(requireText(root, "commandId"));
                String sourceMessageId = requireText(root, "sourceMessageId");
                String targetState = requireText(root, "targetState");
                String claimedDeviceId = optionalText(root, "deviceId");
                String deviceSequence = optionalText(root, "deviceSequence");
                JsonNode outcomeNode = root.get("outcome");
                String outcomeJson = (outcomeNode == null || outcomeNode.isNull())
                        ? "{}"
                        : JSON.writeValueAsString(outcomeNode);
                yield new DeviceEventFrame(
                        claimedDeviceId, runId, commandId, sourceMessageId,
                        messageType, targetState, deviceSequence, outcomeJson);
            }
            default -> throw new IllegalArgumentException("Unsupported inbound frameType: " + frameType);
        };
    }

    private static String requireText(JsonNode root, String field) {
        String val = optionalText(root, field);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Wire frame missing required field: " + field);
        }
        return val;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Wire frame field must be text: " + field);
        }
        return node.asText();
    }
}
