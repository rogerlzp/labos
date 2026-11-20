package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.command.DeviceCommandAdapter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single managed persistent TCP connection for one registered {@code deviceId}.
 *
 * <p>Core safety and concurrency invariants:
 * <ul>
 *   <li>All outbound writes ({@code COMMAND_SEND} and {@code HEARTBEAT_PING}) are serialized under
 *       {@link #writeLock} so concurrent threads never interleave NDJSON frames.</li>
 *   <li>Connection epoch gating prevents a stale transport error from tearing down a freshly
 *       reconnected socket.</li>
 *   <li>Reconnect ONLY restores transport liveness; this class maintains NO command retry buffer
 *       and NEVER auto-resends a previously attempted command after disconnect/reconnect.</li>
 *   <li>{@code HEARTBEAT_PONG}/{@code HEARTBEAT} frames update health state only and NEVER enter
 *       {@link DeviceEventSink}.</li>
 *   <li>Inbound device events execute serially on a per-device event thread so slow persistence
 *       never stalls {@code HEARTBEAT_PONG} reads while preserving wire arrival order.</li>
 * </ul>
 */
public final class TcpDeviceConnection implements AutoCloseable {

    public enum ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        ONLINE,
        RECONNECTING,
        CLOSED
    }

    public record Endpoint(String deviceId, String host, int port) {
        public Endpoint {
            if (deviceId == null || deviceId.isBlank())
                throw new IllegalArgumentException("deviceId is required");
            if (host == null || host.isBlank())
                throw new IllegalArgumentException("host is required");
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException("port must be in 1..65535");
        }
    }

    public record Config(
            Duration connectTimeout,
            Duration heartbeatInterval,
            Duration heartbeatTimeout,
            Duration initialReconnectDelay,
            Duration maxReconnectDelay,
            int maxFrameBytes) {
        public Config {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
            Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
            Objects.requireNonNull(initialReconnectDelay, "initialReconnectDelay");
            Objects.requireNonNull(maxReconnectDelay, "maxReconnectDelay");
            if (connectTimeout.isZero() || connectTimeout.isNegative())
                throw new IllegalArgumentException("connectTimeout must be positive");
            if (heartbeatInterval.isZero() || heartbeatInterval.isNegative())
                throw new IllegalArgumentException("heartbeatInterval must be positive");
            if (heartbeatTimeout.compareTo(heartbeatInterval) <= 0)
                throw new IllegalArgumentException("heartbeatTimeout must exceed heartbeatInterval");
            if (initialReconnectDelay.isZero() || initialReconnectDelay.isNegative())
                throw new IllegalArgumentException("initialReconnectDelay must be positive");
            if (maxReconnectDelay.compareTo(initialReconnectDelay) < 0)
                throw new IllegalArgumentException("maxReconnectDelay must be >= initialReconnectDelay");
            if (maxFrameBytes < 256 || maxFrameBytes > 4 * 1024 * 1024)
                throw new IllegalArgumentException("maxFrameBytes must be in 256..4194304");
        }

        public static Config defaults() {
            return new Config(
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(5),
                    65536);
        }
    }

    public record HealthSnapshot(
            String deviceId,
            ConnectionStatus status,
            long connectionEpoch,
            Instant lastConnectedAt,
            Instant lastPongAt,
            long reconnectCount,
            long commandsWritten,
            long heartbeatsSent,
            long heartbeatsReceived,
            long eventsDelivered,
            long eventsRejected,
            long spoofedFramesRejected,
            long malformedFrames) {}

    private record ActiveTransport(long epoch, Socket socket, OutputStream out, BufferedInputStream in) {}

    private final Endpoint endpoint;
    private final Config config;
    private final DeviceEventSink eventSink;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Object stateLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong epochSeq = new AtomicLong(0);
    private final AtomicLong pingSeq = new AtomicLong(0);

    private volatile ActiveTransport transport;
    private volatile ConnectionStatus status = ConnectionStatus.DISCONNECTED;
    private volatile Instant lastConnectedAt;
    private volatile Instant lastPongAt;
    private volatile long lastPongNanos;

    private final AtomicLong reconnectCount = new AtomicLong(0);
    private final AtomicLong commandsWritten = new AtomicLong(0);
    private final AtomicLong heartbeatsSent = new AtomicLong(0);
    private final AtomicLong heartbeatsReceived = new AtomicLong(0);
    private final AtomicLong eventsDelivered = new AtomicLong(0);
    private final AtomicLong eventsRejected = new AtomicLong(0);
    private final AtomicLong spoofedFramesRejected = new AtomicLong(0);
    private final AtomicLong malformedFrames = new AtomicLong(0);

    private final ExecutorService eventExecutor;
    private volatile Thread ioThread;

    public TcpDeviceConnection(Endpoint endpoint, Config config, DeviceEventSink eventSink) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.config = Objects.requireNonNull(config, "config");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.eventExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "labos-gw-event-" + endpoint.deviceId());
            t.setDaemon(true);
            return t;
        });
    }

    public String deviceId() {
        return endpoint.deviceId();
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("TcpDeviceConnection is already closed: " + endpoint.deviceId());
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::connectionLoop, "labos-gw-io-" + endpoint.deviceId());
        t.setDaemon(true);
        this.ioThread = t;
        t.start();
    }

    /**
     * Write a single {@code COMMAND_SEND} frame over the active TCP connection.
     *
     * <p>NEVER buffers or retries across disconnects. If the connection is not {@code ONLINE} or
     * writing fails, returns {@link DeviceCommandAdapter.SendStatus#DELIVERY_UNCERTAIN} so
     * {@code DeviceCommandExecutionService} transitions the command to {@code UNKNOWN}.
     */
    public DeviceCommandAdapter.SendResult sendCommand(DeviceCommandAdapter.DeviceCommandEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!endpoint.deviceId().equals(envelope.deviceId())) {
            throw new IllegalArgumentException("Envelope deviceId " + envelope.deviceId()
                    + " does not match connection deviceId " + endpoint.deviceId());
        }
        if (closed.get()) {
            return DeviceCommandAdapter.SendResult.deliveryUncertain(
                    "Device connection closed: " + endpoint.deviceId());
        }
        byte[] frameBytes;
        try {
            frameBytes = GatewayWireProtocol.encodeCommandSend(envelope);
        } catch (IOException encodeError) {
            return DeviceCommandAdapter.SendResult.deliveryUncertain(
                    "Failed to encode command frame: " + encodeError.getMessage());
        }

        writeLock.lock();
        try {
            ActiveTransport active = this.transport;
            if (closed.get() || status != ConnectionStatus.ONLINE || active == null) {
                return DeviceCommandAdapter.SendResult.deliveryUncertain(
                        "Device " + endpoint.deviceId() + " not ONLINE (status=" + status + ")");
            }
            try {
                active.out().write(frameBytes);
                active.out().flush();
                commandsWritten.incrementAndGet();
                return DeviceCommandAdapter.SendResult.acceptedAsync();
            } catch (IOException writeFailure) {
                handleTransportFailure(active.epoch());
                return DeviceCommandAdapter.SendResult.deliveryUncertain(
                        "TCP write failed for " + endpoint.deviceId() + ": " + writeFailure.getMessage());
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Triggered periodically by {@link DeviceGateway}'s heartbeat scheduler.
     * Checks heartbeat liveness timeout first, then writes a {@code HEARTBEAT_PING} frame if ONLINE.
     */
    void heartbeatTick() {
        if (closed.get()) return;
        ActiveTransport active = this.transport;
        if (status != ConnectionStatus.ONLINE || active == null) {
            return;
        }
        long elapsedNanos = System.nanoTime() - lastPongNanos;
        if (elapsedNanos > config.heartbeatTimeout().toNanos()) {
            handleTransportFailure(active.epoch());
            return;
        }
        String pingId = "ping-" + pingSeq.incrementAndGet();
        byte[] pingBytes;
        try {
            pingBytes = GatewayWireProtocol.encodeHeartbeatPing(
                    endpoint.deviceId(), pingId, System.currentTimeMillis());
        } catch (IOException ignored) {
            return;
        }
        writeLock.lock();
        try {
            ActiveTransport current = this.transport;
            if (closed.get() || status != ConnectionStatus.ONLINE || current == null || current.epoch() != active.epoch()) {
                return;
            }
            try {
                current.out().write(pingBytes);
                current.out().flush();
                heartbeatsSent.incrementAndGet();
            } catch (IOException ioe) {
                handleTransportFailure(current.epoch());
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean awaitStatus(ConnectionStatus target, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        synchronized (stateLock) {
            while (status != target && !closed.get()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return status == target;
                }
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                stateLock.wait(millis, nanos);
            }
            return status == target;
        }
    }

    public HealthSnapshot health() {
        return new HealthSnapshot(
                endpoint.deviceId(),
                status,
                epochSeq.get(),
                lastConnectedAt,
                lastPongAt,
                reconnectCount.get(),
                commandsWritten.get(),
                heartbeatsSent.get(),
                heartbeatsReceived.get(),
                eventsDelivered.get(),
                eventsRejected.get(),
                spoofedFramesRejected.get(),
                malformedFrames.get());
    }

    private void connectionLoop() {
        long backoffMs = config.initialReconnectDelay().toMillis();
        long maxBackoffMs = config.maxReconnectDelay().toMillis();
        boolean hadConnectionOrAttempt = false;

        while (!closed.get()) {
            setStatus(hadConnectionOrAttempt ? ConnectionStatus.RECONNECTING : ConnectionStatus.CONNECTING);
            hadConnectionOrAttempt = true;
            ActiveTransport active = tryConnect();
            if (active == null) {
                if (closed.get()) break;
                setStatus(ConnectionStatus.RECONNECTING);
                if (!sleepBackoff(backoffMs)) break;
                backoffMs = Math.min(maxBackoffMs, Math.max(1L, backoffMs * 2L));
                continue;
            }

            // Reset backoff upon clean connection establishment.
            backoffMs = config.initialReconnectDelay().toMillis();
            readLoop(active);
            handleTransportFailure(active.epoch());

            if (!closed.get()) {
                reconnectCount.incrementAndGet();
                setStatus(ConnectionStatus.RECONNECTING);
                if (!sleepBackoff(backoffMs)) break;
                backoffMs = Math.min(maxBackoffMs, Math.max(1L, backoffMs * 2L));
            }
        }
        setStatus(ConnectionStatus.CLOSED);
    }

    private ActiveTransport tryConnect() {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            int pollTimeoutMs = (int) Math.max(25L, Math.min(200L, config.heartbeatTimeout().toMillis() / 3L));
            socket.setSoTimeout(pollTimeoutMs);
            socket.connect(
                    new InetSocketAddress(endpoint.host(), endpoint.port()),
                    (int) Math.min(Integer.MAX_VALUE, config.connectTimeout().toMillis()));
            if (closed.get()) {
                closeQuietly(socket);
                return null;
            }
            long newEpoch = epochSeq.incrementAndGet();
            ActiveTransport active = new ActiveTransport(
                    newEpoch,
                    socket,
                    socket.getOutputStream(),
                    new BufferedInputStream(socket.getInputStream()));
            synchronized (stateLock) {
                if (closed.get()) {
                    closeQuietly(socket);
                    return null;
                }
                Instant now = Instant.now();
                this.transport = active;
                this.lastConnectedAt = now;
                this.lastPongAt = now;
                this.lastPongNanos = System.nanoTime();
                this.status = ConnectionStatus.ONLINE;
                stateLock.notifyAll();
            }
            return active;
        } catch (IOException connectError) {
            closeQuietly(socket);
            return null;
        }
    }

    private void readLoop(ActiveTransport active) {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
        boolean oversized = false;
        while (!closed.get() && this.transport == active) {
            try {
                int b = active.in().read();
                if (b == -1) {
                    if (!oversized && lineBuf.size() > 0) {
                        String trailing = stripTrailingCr(lineBuf.toByteArray());
                        if (!trailing.isBlank()) {
                            dispatchInboundLine(trailing);
                        }
                    } else if (oversized) {
                        malformedFrames.incrementAndGet();
                    }
                    return;
                }
                if (b == '\n') {
                    if (oversized) {
                        malformedFrames.incrementAndGet();
                        oversized = false;
                        lineBuf.reset();
                        continue;
                    }
                    String line = stripTrailingCr(lineBuf.toByteArray());
                    lineBuf.reset();
                    if (!line.isBlank()) {
                        dispatchInboundLine(line);
                    }
                    continue;
                }
                if (!oversized) {
                    if (lineBuf.size() >= config.maxFrameBytes()) {
                        oversized = true;
                        lineBuf.reset();
                    } else {
                        lineBuf.write(b);
                    }
                }
            } catch (SocketTimeoutException pollWakeup) {
                long elapsedNanos = System.nanoTime() - lastPongNanos;
                if (elapsedNanos > config.heartbeatTimeout().toNanos()) {
                    return;
                }
            } catch (IOException readError) {
                return;
            }
        }
    }

    private static String stripTrailingCr(byte[] raw) {
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') {
            len--;
        }
        return new String(raw, 0, len, StandardCharsets.UTF_8);
    }

    private void dispatchInboundLine(String line) {
        GatewayWireProtocol.InboundFrame frame;
        try {
            frame = GatewayWireProtocol.decodeInboundLine(line);
        } catch (Exception malformed) {
            malformedFrames.incrementAndGet();
            return;
        }

        if (frame instanceof GatewayWireProtocol.HeartbeatPongFrame pong) {
            if (pong.deviceId() != null && !pong.deviceId().isBlank()
                    && !endpoint.deviceId().equals(pong.deviceId())) {
                spoofedFramesRejected.incrementAndGet();
                return;
            }
            lastPongNanos = System.nanoTime();
            lastPongAt = Instant.now();
            heartbeatsReceived.incrementAndGet();
            return;
        }

        GatewayWireProtocol.DeviceEventFrame evFrame = (GatewayWireProtocol.DeviceEventFrame) frame;
        if (evFrame.claimedDeviceId() != null && !evFrame.claimedDeviceId().isBlank()
                && !endpoint.deviceId().equals(evFrame.claimedDeviceId())) {
            // Transport-level spoofing defense: frame on device A's socket claiming to be device B is dropped.
            spoofedFramesRejected.incrementAndGet();
            return;
        }

        DeviceEventSink.InboundDeviceEvent event = new DeviceEventSink.InboundDeviceEvent(
                endpoint.deviceId(),
                evFrame.claimedDeviceId(),
                evFrame.runId(),
                evFrame.commandId(),
                evFrame.sourceMessageId(),
                evFrame.messageType(),
                evFrame.targetState(),
                evFrame.deviceSequence(),
                evFrame.outcomeJson());

        if (closed.get()) return;
        try {
            eventExecutor.execute(() -> {
                if (closed.get()) return;
                try {
                    eventSink.onDeviceEvent(event);
                    eventsDelivered.incrementAndGet();
                } catch (Throwable rejected) {
                    eventsRejected.incrementAndGet();
                }
            });
        } catch (RuntimeException executorClosed) {
            // Ignore late frames arriving concurrently with close().
        }
    }

    private void handleTransportFailure(long failedEpoch) {
        ActiveTransport toClose = null;
        synchronized (stateLock) {
            ActiveTransport current = this.transport;
            if (current != null && current.epoch() == failedEpoch) {
                this.transport = null;
                toClose = current;
                if (!closed.get()) {
                    this.status = ConnectionStatus.RECONNECTING;
                }
                stateLock.notifyAll();
            }
        }
        if (toClose != null) {
            closeQuietly(toClose.socket());
        }
    }

    private void setStatus(ConnectionStatus next) {
        synchronized (stateLock) {
            if (closed.get() && next != ConnectionStatus.CLOSED) {
                return;
            }
            this.status = next;
            stateLock.notifyAll();
        }
    }

    private boolean sleepBackoff(long delayMs) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        synchronized (stateLock) {
            while (!closed.get()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return true;
                }
                try {
                    stateLock.wait(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ActiveTransport active;
        synchronized (stateLock) {
            active = this.transport;
            this.transport = null;
            this.status = ConnectionStatus.CLOSED;
            stateLock.notifyAll();
        }
        if (active != null) {
            closeQuietly(active.socket());
        }
        Thread t = this.ioThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(3000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        eventExecutor.shutdown();
        try {
            if (!eventExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                eventExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            eventExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
