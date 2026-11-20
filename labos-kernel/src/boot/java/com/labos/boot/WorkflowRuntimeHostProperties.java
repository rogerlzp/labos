package com.labos.boot;

import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration properties for the embedded single-process Workflow Runtime Host and
 * in-process TCP {@code DeviceGateway}.
 *
 * <p><b>Feature flag default:</b> {@code labos.workflow.runtime.host.enabled=false}.
 * When disabled, neither {@code EmbeddedWorkflowRuntimeHost} nor {@code DeviceGateway}
 * is created or started, leaving the legacy {@code GraphRunner} untouched.
 *
 * <p><b>Trust model (V1):</b> isolated laboratory LAN with static endpoint allowlist
 * ({@code deviceId=host:port,...}); TLS/mTLS is intentionally omitted for internal-LAN V1.
 */
public record WorkflowRuntimeHostProperties(
        boolean enabled,
        Path lockFile,
        String hostId,
        int workerThreads,
        int workerQueueCapacity,
        int maxConcurrentRuns,
        int mailboxThreads,
        int mailboxQueueCapacity,
        int recoveryBatchLimit,
        Duration dispatchPollInterval,
        int dispatchBatchSize,
        List<TcpDeviceConnection.Endpoint> deviceEndpoints,
        TcpDeviceConnection.Config gatewayConfig) {

    public static final String PROP_ENABLED = "labos.workflow.runtime.host.enabled";
    public static final String PROP_LOCK_FILE = "labos.workflow.runtime.host.lock-file";
    public static final String PROP_HOST_ID = "labos.workflow.runtime.host.id";
    public static final String PROP_WORKER_THREADS = "labos.workflow.runtime.host.worker-threads";
    public static final String PROP_WORKER_QUEUE = "labos.workflow.runtime.host.worker-queue-capacity";
    public static final String PROP_MAX_RUNS = "labos.workflow.runtime.host.max-concurrent-runs";
    public static final String PROP_MAILBOX_THREADS = "labos.workflow.runtime.host.mailbox-threads";
    public static final String PROP_MAILBOX_QUEUE = "labos.workflow.runtime.host.mailbox-queue-capacity";
    public static final String PROP_RECOVERY_LIMIT = "labos.workflow.runtime.host.recovery-batch-limit";
    public static final String PROP_POLL_MS = "labos.workflow.runtime.host.dispatch-poll-ms";
    public static final String PROP_BATCH_SIZE = "labos.workflow.runtime.host.dispatch-batch-size";
    public static final String PROP_ENDPOINTS = "labos.workflow.runtime.gateway.endpoints";
    public static final String PROP_CONNECT_TIMEOUT_MS = "labos.workflow.runtime.gateway.connect-timeout-ms";
    public static final String PROP_HB_INTERVAL_MS = "labos.workflow.runtime.gateway.heartbeat-interval-ms";
    public static final String PROP_HB_TIMEOUT_MS = "labos.workflow.runtime.gateway.heartbeat-timeout-ms";
    public static final String PROP_RECONNECT_MIN_MS = "labos.workflow.runtime.gateway.reconnect-initial-ms";
    public static final String PROP_RECONNECT_MAX_MS = "labos.workflow.runtime.gateway.reconnect-max-ms";
    public static final String PROP_MAX_FRAME_BYTES = "labos.workflow.runtime.gateway.max-frame-bytes";
    public static final String PROP_MAX_EVENT_QUEUE = "labos.workflow.runtime.gateway.max-event-queue-size";

    public WorkflowRuntimeHostProperties {
        Objects.requireNonNull(lockFile, "lockFile");
        if (hostId == null || hostId.isBlank()) throw new IllegalArgumentException("hostId is required");
        Objects.requireNonNull(dispatchPollInterval, "dispatchPollInterval");
        deviceEndpoints = List.copyOf(Objects.requireNonNull(deviceEndpoints, "deviceEndpoints"));
        Objects.requireNonNull(gatewayConfig, "gatewayConfig");
    }

    public static WorkflowRuntimeHostProperties fromEnvironment(Environment env) {
        Objects.requireNonNull(env, "env");
        boolean enabled = Boolean.parseBoolean(env.getProperty(PROP_ENABLED, "false"));
        Path lockFile = Path.of(env.getProperty(PROP_LOCK_FILE, "runtime-host.lock"));
        String hostId = env.getProperty(PROP_HOST_ID, "labos-local-host");
        int workerThreads = intProp(env, PROP_WORKER_THREADS, 4);
        int workerQueue = intProp(env, PROP_WORKER_QUEUE, 64);
        int maxRuns = intProp(env, PROP_MAX_RUNS, 16);
        int mailboxThreads = intProp(env, PROP_MAILBOX_THREADS, 2);
        int mailboxQueue = intProp(env, PROP_MAILBOX_QUEUE, 128);
        int recoveryLimit = intProp(env, PROP_RECOVERY_LIMIT, 100);
        Duration pollInterval = Duration.ofMillis(longProp(env, PROP_POLL_MS, 250L));
        int batchSize = intProp(env, PROP_BATCH_SIZE, 32);
        // Only parse gateway endpoints when host is enabled so a stale/malformed property never breaks startup while disabled.
        List<TcpDeviceConnection.Endpoint> endpoints = enabled
                ? parseEndpoints(env.getProperty(PROP_ENDPOINTS, ""))
                : List.of();
        TcpDeviceConnection.Config gatewayConfig = new TcpDeviceConnection.Config(
                Duration.ofMillis(longProp(env, PROP_CONNECT_TIMEOUT_MS, 3000L)),
                Duration.ofMillis(longProp(env, PROP_HB_INTERVAL_MS, 5000L)),
                Duration.ofMillis(longProp(env, PROP_HB_TIMEOUT_MS, 15000L)),
                Duration.ofMillis(longProp(env, PROP_RECONNECT_MIN_MS, 200L)),
                Duration.ofMillis(longProp(env, PROP_RECONNECT_MAX_MS, 5000L)),
                intProp(env, PROP_MAX_FRAME_BYTES, 65536),
                intProp(env, PROP_MAX_EVENT_QUEUE, 256));
        return new WorkflowRuntimeHostProperties(
                enabled, lockFile, hostId, workerThreads, workerQueue, maxRuns,
                mailboxThreads, mailboxQueue, recoveryLimit, pollInterval, batchSize,
                endpoints, gatewayConfig);
    }

    public EmbeddedWorkflowRuntimeHost.Config toHostConfig() {
        return new EmbeddedWorkflowRuntimeHost.Config(
                lockFile, hostId, workerThreads, workerQueueCapacity, maxConcurrentRuns,
                mailboxThreads, mailboxQueueCapacity, recoveryBatchLimit,
                dispatchPollInterval, dispatchBatchSize, deviceEndpoints, gatewayConfig, null);
    }

    /**
     * Parse comma-separated {@code deviceId=host:port} entries (blank string yields empty list).
     */
    public static List<TcpDeviceConnection.Endpoint> parseEndpoints(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<TcpDeviceConnection.Endpoint> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid gateway endpoint entry (expected deviceId=host:port): " + trimmed);
            }
            String deviceId = trimmed.substring(0, eq).trim();
            String addr = trimmed.substring(eq + 1).trim();
            int colon = addr.lastIndexOf(':');
            if (colon <= 0 || colon == addr.length() - 1) {
                throw new IllegalArgumentException("Invalid gateway endpoint host:port: " + trimmed);
            }
            String host = addr.substring(0, colon).trim();
            int port;
            try {
                port = Integer.parseInt(addr.substring(colon + 1).trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid gateway endpoint port: " + trimmed, nfe);
            }
            out.add(new TcpDeviceConnection.Endpoint(deviceId, host, port));
        }
        return List.copyOf(out);
    }

    private static int intProp(Environment env, String key, int fallback) {
        String raw = env.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Integer.parseInt(raw.trim());
    }

    private static long longProp(Environment env, String key, long fallback) {
        String raw = env.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Long.parseLong(raw.trim());
    }
}
