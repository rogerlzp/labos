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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single managed persistent TCP connection for one registered {@code deviceId}.
 *
 * <p>Core safety and reliability invariants:
 * <ul>
 *   <li>All outbound writes ({@code COMMAND_SEND}, {@code HEARTBEAT_PING}, {@code EVENT_ACK},
 *       {@code EVENT_NACK}) are serialized under {@link #writeLock} so concurrent threads never
 *       interleave NDJSON frames.</li>
 *   <li>Each device runs its own dedicated heartbeat scheduler so a stalled peer can never
 *       block heartbeat supervision of other registered devices.</li>
 *   <li>Every {@code HEARTBEAT_PONG} must carry matching non-blank {@code deviceId} and an
 *       active, unconsumed {@code pingId} issued during the current {@code connectionEpoch} that
 *       has not expired past {@code heartbeatTimeout}; unsolicited, expired, duplicate, or
 *       cross-epoch PONGs never refresh liveness.</li>
 *   <li>Inbound device events are buffered in a strictly bounded per-device queue and answered
 *       with explicit {@code EVENT_ACK} or {@code EVENT_NACK} (retryable on backpressure /
 *       transient DB failure, non-retryable on permanent protocol/domain rejection) so events
 *       are NEVER silently dropped.</li>
 *   <li>Reconnect ONLY restores transport liveness; this class maintains NO command retry buffer
 *       and NEVER auto-resends a previously attempted command after disconnect/reconnect.</li>
 *   <li>Deployment trust model (V1): isolated laboratory LAN with static endpoint allowlist
 *       ({@code deviceId -> host:port}) and connection-bound {@code authenticatedDeviceId};
 *       TLS/mTLS is intentionally omitted for internal-LAN V1.</li>
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
            int maxFrameBytes,
            int maxEventQueueSize) {
        public Config(
                Duration connectTimeout,
                Duration heartbeatInterval,
                Duration heartbeatTimeout,
                Duration initialReconnectDelay,
                Duration maxReconnectDelay,
                int maxFrameBytes) {
            this(connectTimeout, heartbeatInterval, heartbeatTimeout,
                    initialReconnectDelay, maxReconnectDelay, maxFrameBytes, 256);
        }

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
            if (maxEventQueueSize < 1 || maxEventQueueSize > 65536)
                throw new IllegalArgumentException("maxEventQueueSize must be in 1..65536");
        }

        public static Config defaults() {
            return new Config(
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(5),
                    65536,
                    256);
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
            long invalidHeartbeatsRejected,
            long eventsDelivered,
            long eventsRejected,
            long eventsTransientNacked,
            long eventsBackpressureNacked,
            long spoofedFramesRejected,
            long malformedFrames) {}

    private record ActiveTransport(long epoch, Socket socket, OutputStream out, BufferedInputStream in) {}

    private record PendingInboundEvent(long epoch, DeviceEventSink.InboundDeviceEvent event) {}

    private final Endpoint endpoint;
    private final Config config;
    private final DeviceEventSink eventSink;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Object stateLock = new Object();
    private final AtomicBoolean ingressStarted = new AtomicBoolean(false);
    private final AtomicBoolean processingStarted = new AtomicBoolean(false);
    private final AtomicBoolean processingStopped = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong epochSeq = new AtomicLong(0);
    private final AtomicLong pingSeq = new AtomicLong(0);
    private final Map<String, Long> pendingPings = new ConcurrentHashMap<>();
    private final BlockingQueue<PendingInboundEvent> eventQueue;

    private volatile ActiveTransport transport;
    private volatile ConnectionStatus status = ConnectionStatus.DISCONNECTED;
    private volatile Instant lastConnectedAt;
    private volatile Instant lastPongAt;
    private volatile long lastPongNanos;

    private final AtomicLong reconnectCount = new AtomicLong(0);
    private final AtomicLong commandsWritten = new AtomicLong(0);
    private final AtomicLong heartbeatsSent = new AtomicLong(0);
    private final AtomicLong heartbeatsReceived = new AtomicLong(0);
    private final AtomicLong invalidHeartbeatsRejected = new AtomicLong(0);
    private final AtomicLong eventsDelivered = new AtomicLong(0);
    private final AtomicLong eventsRejected = new AtomicLong(0);
    private final AtomicLong eventsTransientNacked = new AtomicLong(0);
    private final AtomicLong eventsBackpressureNacked = new AtomicLong(0);
    private final AtomicLong spoofedFramesRejected = new AtomicLong(0);
    private final AtomicLong malformedFrames = new AtomicLong(0);

    private final ScheduledExecutorService heartbeatScheduler;
    private volatile Thread ioThread;
    private volatile Thread eventThread;

    public TcpDeviceConnection(Endpoint endpoint, Config config, DeviceEventSink eventSink) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.config = Objects.requireNonNull(config, "config");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.eventQueue = new ArrayBlockingQueue<>(config.maxEventQueueSize());
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "labos-gw-hb-" + endpoint.deviceId());
            t.setDaemon(true);
            return t;
        });
    }

    public String deviceId() {
        return endpoint.deviceId();
    }

    /** Current number of inbound device events buffered in the bounded queue awaiting processing. */
    public int queuedEventCount() {
        return eventQueue.size();
    }

    /**
     * Start BOTH transport ingress (TCP connect/read/heartbeat) AND inbound event processing.
     * For {@code RuntimeHost} startup recovery isolation, prefer {@link #startIngressOnly()}
     * before recovery and {@link #startEventProcessing()} after recovery completes.
     */
    public void start() {
        startIngressOnly();
        startEventProcessing();
    }

    /**
     * Start TCP transport ingress (socket connect/reconnect, heartbeats, and bounded inbound
     * queue buffering) WITHOUT starting the {@link DeviceEventSink} consumer thread.
     * Inbound device events remain buffered (with backpressure NACK if full) until
     * {@link #startEventProcessing()} is invoked after startup recovery completes.
     */
    public void startIngressOnly() {
        if (closed.get()) {
            throw new IllegalStateException("TcpDeviceConnection is already closed: " + endpoint.deviceId());
        }
        if (!ingressStarted.compareAndSet(false, true)) {
            return;
        }
        Thread io = new Thread(this::connectionLoop, "labos-gw-io-" + endpoint.deviceId());
        io.setDaemon(true);
        this.ioThread = io;
        io.start();

        long intervalMs = config.heartbeatInterval().toMillis();
        heartbeatScheduler.scheduleAtFixedRate(
                this::heartbeatTickSafe,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Start the per-device {@link DeviceEventSink} consumer thread after startup recovery has completed.
     */
    public void startEventProcessing() {
        if (closed.get() || processingStopped.get()) {
            throw new IllegalStateException("TcpDeviceConnection event processing is closed: " + endpoint.deviceId());
        }
        if (!processingStarted.compareAndSet(false, true)) {
            return;
        }
        Thread evt = new Thread(this::eventLoop, "labos-gw-event-" + endpoint.deviceId());
        evt.setDaemon(true);
        this.eventThread = evt;
        evt.start();
    }

    /**
     * Stop inbound event processing (e.g. during reverse {@code RuntimeHost} shutdown) before
     * tearing down transport ingress.
     */
    public void stopEventProcessing() {
        if (!processingStopped.compareAndSet(false, true)) {
            return;
        }
        joinQuietly(this.eventThread);
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

    private void heartbeatTickSafe() {
        try {
            heartbeatTick();
        } catch (Throwable ignored) {
            // Keep per-device heartbeat scheduler alive across unexpected errors.
        }
    }

    /**
     * Checks heartbeat liveness timeout first, then issues a unique epoch-bound
     * {@code HEARTBEAT_PING} frame if {@code ONLINE}.
     */
    void heartbeatTick() {
        if (closed.get()) return;
        ActiveTransport active = this.transport;
        if (status != ConnectionStatus.ONLINE || active == null) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (nowNanos - lastPongNanos > config.heartbeatTimeout().toNanos()) {
            handleTransportFailure(active.epoch());
            return;
        }
        String pingId = "ping-" + active.epoch() + "-" + pingSeq.incrementAndGet();
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
            // Bound pendingPings against leaks on long-running connections.
            if (pendingPings.size() > 64) {
                long cutoffNanos = nowNanos - config.heartbeatTimeout().toNanos() * 2L;
                pendingPings.entrySet().removeIf(e -> e.getValue() < cutoffNanos);
            }
            pendingPings.put(pingId, nowNanos);
            try {
                current.out().write(pingBytes);
                current.out().flush();
                heartbeatsSent.incrementAndGet();
            } catch (IOException ioe) {
                pendingPings.remove(pingId);
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
                invalidHeartbeatsRejected.get(),
                eventsDelivered.get(),
                eventsRejected.get(),
                eventsTransientNacked.get(),
                eventsBackpressureNacked.get(),
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
                pendingPings.clear();
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
                            dispatchInboundLine(active, trailing);
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
                        dispatchInboundLine(active, line);
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

    private void dispatchInboundLine(ActiveTransport active, String line) {
        GatewayWireProtocol.InboundFrame frame;
        try {
            frame = GatewayWireProtocol.decodeInboundLine(line);
        } catch (Exception malformed) {
            malformedFrames.incrementAndGet();
            return;
        }

        if (frame instanceof GatewayWireProtocol.HeartbeatPongFrame pong) {
            // Strict P1-02 validation: non-blank matching deviceId + active unconsumed pingId for current epoch!
            if (pong.deviceId() == null || pong.deviceId().isBlank()
                    || !endpoint.deviceId().equals(pong.deviceId())) {
                invalidHeartbeatsRejected.incrementAndGet();
                spoofedFramesRejected.incrementAndGet();
                return;
            }
            if (pong.pingId() == null || pong.pingId().isBlank()) {
                invalidHeartbeatsRejected.incrementAndGet();
                return;
            }
            Long sentAtNanos = pendingPings.remove(pong.pingId());
            long nowNanos = System.nanoTime();
            if (sentAtNanos == null
                    || this.transport != active
                    || (nowNanos - sentAtNanos) > config.heartbeatTimeout().toNanos()) {
                invalidHeartbeatsRejected.incrementAndGet();
                return;
            }
            lastPongNanos = nowNanos;
            lastPongAt = Instant.now();
            heartbeatsReceived.incrementAndGet();
            return;
        }

        GatewayWireProtocol.DeviceEventFrame evFrame = (GatewayWireProtocol.DeviceEventFrame) frame;
        if (evFrame.claimedDeviceId() != null && !evFrame.claimedDeviceId().isBlank()
                && !endpoint.deviceId().equals(evFrame.claimedDeviceId())) {
            spoofedFramesRejected.incrementAndGet();
            writeEventNack(active.epoch(), evFrame.sourceMessageId(), evFrame.commandId(),
                    false, "DEVICE_ID_MISMATCH");
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

        if (closed.get()) {
            writeEventNack(active.epoch(), event.sourceMessageId(), event.commandId(),
                    true, "GATEWAY_CLOSING");
            return;
        }

        if (!eventQueue.offer(new PendingInboundEvent(active.epoch(), event))) {
            // Bounded queue backpressure: explicitly NACK retryable so device retries instead of silent drop or OOM.
            eventsBackpressureNacked.incrementAndGet();
            writeEventNack(active.epoch(), event.sourceMessageId(), event.commandId(),
                    true, "GATEWAY_BACKPRESSURE");
        }
    }

    private void eventLoop() {
        while (!closed.get() && !processingStopped.get()) {
            PendingInboundEvent pending;
            try {
                pending = eventQueue.poll(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                if (closed.get() || processingStopped.get()) break;
                continue;
            }
            if (pending == null) continue;
            if (closed.get() || processingStopped.get()) break;
            processPendingEvent(pending);
        }
        // Drain any remaining queued events during shutdown; socket is closed by close()
        // so the peer observes disconnect without EVENT_ACK and knows those events were not persisted.
        eventQueue.clear();
    }

    private void processPendingEvent(PendingInboundEvent pending) {
        DeviceEventSink.InboundDeviceEvent event = pending.event();
        DeviceEventSink.DeliveryResult result;
        try {
            result = Objects.requireNonNull(
                    eventSink.onDeviceEvent(event),
                    "DeviceEventSink returned null DeliveryResult");
        } catch (RuntimeException unhandled) {
            result = DeviceEventSink.DeliveryResult.nackRetryable(
                    unhandled.getClass().getSimpleName() + ": " + safeMessage(unhandled));
        }
        switch (result.disposition()) {
            case ACKED -> {
                eventsDelivered.incrementAndGet();
                writeEventAck(pending.epoch(), event.sourceMessageId(), event.commandId(), result.status());
            }
            case NACK_RETRYABLE -> {
                eventsTransientNacked.incrementAndGet();
                writeEventNack(pending.epoch(), event.sourceMessageId(), event.commandId(),
                        true, result.reason());
            }
            case NACK_PERMANENT -> {
                eventsRejected.incrementAndGet();
                writeEventNack(pending.epoch(), event.sourceMessageId(), event.commandId(),
                        false, result.reason());
            }
        }
    }

    private void writeEventAck(long expectedEpoch, String sourceMessageId, UUID commandId, String status) {
        byte[] bytes;
        try {
            bytes = GatewayWireProtocol.encodeEventAck(endpoint.deviceId(), sourceMessageId, commandId, status);
        } catch (IOException ignored) {
            return;
        }
        writeControlFrame(expectedEpoch, bytes);
    }

    private void writeEventNack(
            long expectedEpoch, String sourceMessageId, UUID commandId, boolean retryable, String reason) {
        byte[] bytes;
        try {
            bytes = GatewayWireProtocol.encodeEventNack(
                    endpoint.deviceId(), sourceMessageId, commandId, retryable, reason);
        } catch (IOException ignored) {
            return;
        }
        writeControlFrame(expectedEpoch, bytes);
    }

    private void writeControlFrame(long expectedEpoch, byte[] bytes) {
        writeLock.lock();
        try {
            ActiveTransport current = this.transport;
            if (current == null || current.epoch() != expectedEpoch) {
                return;
            }
            try {
                current.out().write(bytes);
                current.out().flush();
            } catch (IOException ioe) {
                handleTransportFailure(current.epoch());
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void handleTransportFailure(long failedEpoch) {
        ActiveTransport toClose = null;
        synchronized (stateLock) {
            ActiveTransport current = this.transport;
            if (current != null && current.epoch() == failedEpoch) {
                this.transport = null;
                pendingPings.clear();
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
        heartbeatScheduler.shutdownNow();
        ActiveTransport active;
        synchronized (stateLock) {
            active = this.transport;
            this.transport = null;
            pendingPings.clear();
            this.status = ConnectionStatus.CLOSED;
            stateLock.notifyAll();
        }
        if (active != null) {
            closeQuietly(active.socket());
        }
        joinQuietly(this.ioThread);
        joinQuietly(this.eventThread);
        try {
            if (!heartbeatScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread t) {
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(3000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
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

    private static String safeMessage(Throwable t) {
        return t == null || t.getMessage() == null ? "" : t.getMessage();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
