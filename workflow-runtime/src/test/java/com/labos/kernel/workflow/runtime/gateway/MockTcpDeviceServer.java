package com.labos.kernel.workflow.runtime.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Test-only loopback ({@code 127.0.0.1:0}) Mock TCP Device Server speaking {@link GatewayWireProtocol}.
 * Supports programmable command handlers, heartbeat PONG suppression, forced disconnect,
 * raw frame injection, and frame-integrity accounting.
 */
final class MockTcpDeviceServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String deviceId;
    private final ServerSocket serverSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean replyToHeartbeats = new AtomicBoolean(true);
    private final AtomicInteger acceptedConnections = new AtomicInteger(0);
    private final AtomicInteger heartbeatsReceived = new AtomicInteger(0);
    private final AtomicInteger parseErrors = new AtomicInteger(0);
    private final List<JsonNode> receivedCommands = new CopyOnWriteArrayList<>();
    private final AtomicReference<ClientSession> activeSession = new AtomicReference<>();
    private final AtomicReference<BiConsumer<JsonNode, ClientSession>> commandHandler =
            new AtomicReference<>((cmd, session) -> {});
    private final Thread acceptThread;

    final class ClientSession {
        private final Socket socket;
        private final OutputStream out;

        private ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        synchronized void sendJson(ObjectNode node) throws IOException {
            sendRawLine(JSON.writeValueAsString(node));
        }

        synchronized void sendRawLine(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void sendDeviceEvent(
                String claimedDeviceId, String runId, String commandId,
                String sourceMessageId, String messageType, String targetState,
                String deviceSequence, String outcomeJson) throws IOException {
            ObjectNode node = JSON.createObjectNode();
            node.put("frameType", GatewayWireProtocol.FRAME_DEVICE_EVENT);
            if (claimedDeviceId != null) node.put("deviceId", claimedDeviceId);
            node.put("runId", runId);
            node.put("commandId", commandId);
            node.put("sourceMessageId", sourceMessageId);
            node.put("messageType", messageType);
            node.put("targetState", targetState);
            if (deviceSequence != null) node.put("deviceSequence", deviceSequence);
            node.set("outcome", JSON.readTree(outcomeJson == null ? "{}" : outcomeJson));
            sendJson(node);
        }

        synchronized void sendHeartbeatPong(String pingId) throws IOException {
            ObjectNode pong = JSON.createObjectNode();
            pong.put("frameType", GatewayWireProtocol.FRAME_HEARTBEAT_PONG);
            pong.put("deviceId", deviceId);
            if (pingId != null) pong.put("pingId", pingId);
            pong.put("deviceEpochMs", System.currentTimeMillis());
            sendJson(pong);
        }

        void forceClose() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    MockTcpDeviceServer(String deviceId) throws IOException {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        this.acceptThread = new Thread(this::acceptLoop, "mock-tcp-accept-" + deviceId);
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    TcpDeviceConnection.Endpoint endpoint() {
        return new TcpDeviceConnection.Endpoint(deviceId, "127.0.0.1", port());
    }

    void setCommandHandler(BiConsumer<JsonNode, ClientSession> handler) {
        this.commandHandler.set(Objects.requireNonNull(handler, "handler"));
    }

    void setReplyToHeartbeats(boolean enabled) {
        this.replyToHeartbeats.set(enabled);
    }

    int acceptedConnections() {
        return acceptedConnections.get();
    }

    int heartbeatsReceived() {
        return heartbeatsReceived.get();
    }

    int parseErrors() {
        return parseErrors.get();
    }

    List<JsonNode> receivedCommands() {
        return List.copyOf(receivedCommands);
    }

    ClientSession activeSession() {
        return activeSession.get();
    }

    void dropActiveClient() {
        ClientSession s = activeSession.getAndSet(null);
        if (s != null) {
            s.forceClose();
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket client = serverSocket.accept();
                if (closed.get()) {
                    client.close();
                    return;
                }
                client.setTcpNoDelay(true);
                ClientSession session = new ClientSession(client);
                ClientSession prev = activeSession.getAndSet(session);
                if (prev != null) {
                    prev.forceClose();
                }
                acceptedConnections.incrementAndGet();
                Thread reader = new Thread(() -> readClient(session),
                        "mock-tcp-client-" + deviceId + "-" + acceptedConnections.get());
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                if (closed.get()) return;
            }
        }
    }

    private void readClient(ClientSession session) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(session.socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node;
                try {
                    node = JSON.readTree(line);
                } catch (Exception parseEx) {
                    parseErrors.incrementAndGet();
                    continue;
                }
                String frameType = node.path("frameType").asText("");
                if (GatewayWireProtocol.FRAME_HEARTBEAT_PING.equals(frameType)) {
                    heartbeatsReceived.incrementAndGet();
                    if (replyToHeartbeats.get()) {
                        session.sendHeartbeatPong(node.path("pingId").asText(""));
                    }
                } else if (GatewayWireProtocol.FRAME_COMMAND_SEND.equals(frameType)) {
                    receivedCommands.add(node);
                    commandHandler.get().accept(node, session);
                } else {
                    parseErrors.incrementAndGet();
                }
            }
        } catch (IOException ignored) {
        } finally {
            activeSession.compareAndSet(session, null);
            session.forceClose();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        dropActiveClient();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        acceptThread.interrupt();
        try {
            acceptThread.join(2000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
