package com.labos.kernel.workflow.runtime.gateway;

import com.labos.kernel.workflow.runtime.command.DeviceCommandAdapter;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-JVM Device Gateway managing persistent TCP connections, per-device heartbeat liveness,
 * and automatic reconnection for registered devices.
 *
 * <p>Architectural boundary:
 * <ul>
 *   <li>Logically independent communication subsystem with ZERO dependency on
 *       {@code GraphCoordinator}, {@code WorkerPool}, or {@code WorkflowRuntimeRepository}.</li>
 *   <li>Connects to Workflow Runtime exclusively via {@link #asCommandAdapter()} (Command SPI)
 *       and {@link DeviceEventSink} (Event SPI).</li>
 *   <li>Each registered device runs its own isolated heartbeat scheduler, IO loop, and bounded
 *       event queue so one stalled or misbehaving device cannot degrade other devices.</li>
 *   <li>Reconnection only restores TCP transport liveness; in-flight commands are NEVER
 *       automatically re-sent across disconnects.</li>
 * </ul>
 */
public final class DeviceGateway implements AutoCloseable {

    private final TcpDeviceConnection.Config config;
    private final DeviceEventSink eventSink;
    private final Map<String, TcpDeviceConnection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DeviceGateway(
            Collection<TcpDeviceConnection.Endpoint> endpoints,
            TcpDeviceConnection.Config config,
            DeviceEventSink eventSink) {
        Objects.requireNonNull(endpoints, "endpoints");
        this.config = Objects.requireNonNull(config, "config");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        for (TcpDeviceConnection.Endpoint endpoint : endpoints) {
            registerDevice(endpoint);
        }
    }

    public void registerDevice(TcpDeviceConnection.Endpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (closed.get()) {
            throw new IllegalStateException("DeviceGateway is closed");
        }
        TcpDeviceConnection conn = new TcpDeviceConnection(endpoint, config, eventSink);
        TcpDeviceConnection prev = connections.putIfAbsent(endpoint.deviceId(), conn);
        if (prev != null) {
            conn.close();
            throw new IllegalArgumentException("Duplicate device registration: " + endpoint.deviceId());
        }
        if (started.get() && !closed.get()) {
            conn.start();
        }
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("DeviceGateway is already closed");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (TcpDeviceConnection conn : connections.values()) {
            conn.start();
        }
    }

    public DeviceCommandAdapter asCommandAdapter() {
        return this::sendCommand;
    }

    public DeviceCommandAdapter.SendResult sendCommand(DeviceCommandAdapter.DeviceCommandEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (closed.get()) {
            return DeviceCommandAdapter.SendResult.deliveryUncertain("DeviceGateway is closed");
        }
        TcpDeviceConnection conn = connections.get(envelope.deviceId());
        if (conn == null) {
            return DeviceCommandAdapter.SendResult.deliveryUncertain(
                    "Device not registered in DeviceGateway: " + envelope.deviceId());
        }
        return conn.sendCommand(envelope);
    }

    public boolean awaitOnline(String deviceId, Duration timeout) throws InterruptedException {
        TcpDeviceConnection conn = connections.get(deviceId);
        if (conn == null) {
            throw new IllegalArgumentException("Device not registered: " + deviceId);
        }
        return conn.awaitStatus(TcpDeviceConnection.ConnectionStatus.ONLINE, timeout);
    }

    public boolean awaitStatus(
            String deviceId, TcpDeviceConnection.ConnectionStatus status, Duration timeout)
            throws InterruptedException {
        TcpDeviceConnection conn = connections.get(deviceId);
        if (conn == null) {
            throw new IllegalArgumentException("Device not registered: " + deviceId);
        }
        return conn.awaitStatus(status, timeout);
    }

    public Optional<TcpDeviceConnection.HealthSnapshot> health(String deviceId) {
        TcpDeviceConnection conn = connections.get(deviceId);
        return conn == null ? Optional.empty() : Optional.of(conn.health());
    }

    public Map<String, TcpDeviceConnection.HealthSnapshot> allHealth() {
        Map<String, TcpDeviceConnection.HealthSnapshot> out = new LinkedHashMap<>();
        connections.forEach((id, conn) -> out.put(id, conn.health()));
        return Map.copyOf(out);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TcpDeviceConnection conn : connections.values()) {
            conn.close();
        }
    }
}
