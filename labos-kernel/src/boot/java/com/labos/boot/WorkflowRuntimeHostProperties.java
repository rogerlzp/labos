package com.labos.boot;

import com.labos.kernel.workflow.runtime.device.contract.SecurityMode;
import com.labos.kernel.workflow.runtime.gateway.GatewayWireCodec;
import com.labos.kernel.workflow.runtime.gateway.NdjsonWireCodec;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Configuration properties for the embedded single-process Workflow Runtime Host and
 * in-process TCP {@code DeviceGateway}.
 *
 * <p><b>Feature flag default:</b> {@code labos.workflow.runtime.host.enabled=false}.
 * When disabled, neither {@code EmbeddedWorkflowRuntimeHost} nor {@code DeviceGateway}
 * is created or started, leaving the legacy {@code GraphRunner} untouched.
 *
 * <p><b>Trust model:</b> {@code TLS13_MTLS} is the default and sole non-loopback mode;
 * {@code DEV_LOOPBACK_PLAINTEXT} is restricted strictly to loopback addresses ({@code 127.0.0.1}, {@code ::1}).
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
        TcpDeviceConnection.Config gatewayConfig,
        Long scopedKernelTenantId,
        List<WorkstationEndpoint> workstationEndpoints,
        SecurityMode securityMode) {

    /**
     * C2 workstation-level Direction A endpoint binding ({@code workstationId=tls://host:port} or
     * loopback-only {@code workstationId=ndjson://127.0.0.1:port}).
     */
    public record WorkstationEndpoint(
            String workstationId,
            String scheme,
            String host,
            int port,
            SecurityMode securityMode) {
        public WorkstationEndpoint {
            if (workstationId == null || workstationId.isBlank()) {
                throw new IllegalArgumentException("workstationId is required");
            }
            if (scheme == null || scheme.isBlank()) {
                throw new IllegalArgumentException("scheme is required");
            }
            if ("labsep".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException(
                        "labsep:// scheme is forbidden under Contract v1.1; use tls:// or loopback ndjson://");
            }
            if (!"tls".equals(scheme) && !"ndjson".equals(scheme)) {
                throw new IllegalArgumentException("Unsupported workstation endpoint scheme: " + scheme);
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be in 1..65535");
            }
            Objects.requireNonNull(securityMode, "securityMode");
            securityMode.validateHost(host);
            if ("ndjson".equals(scheme) && securityMode != SecurityMode.DEV_LOOPBACK_PLAINTEXT) {
                throw new IllegalArgumentException(
                        "ndjson:// plaintext scheme requires securityMode=DEV_LOOPBACK_PLAINTEXT on loopback");
            }
            if ("tls".equals(scheme) && securityMode != SecurityMode.TLS13_MTLS) {
                throw new IllegalArgumentException(
                        "tls:// scheme requires securityMode=TLS13_MTLS");
            }
        }
    }

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
    public static final String PROP_SCOPED_KERNEL_TENANT_ID = "labos.workflow.runtime.host.scoped-kernel-tenant-id";
    public static final String PROP_WORKSTATION_ENDPOINTS = "labos.workflow.runtime.host.workstation-endpoints";
    public static final String PROP_SECURITY_MODE = "labos.workflow.runtime.host.security-mode";
    public static final String PROP_ENDPOINTS = "labos.workflow.runtime.gateway.endpoints";
    public static final String PROP_CONNECT_TIMEOUT_MS = "labos.workflow.runtime.gateway.connect-timeout-ms";
    public static final String PROP_HB_INTERVAL_MS = "labos.workflow.runtime.gateway.heartbeat-interval-ms";
    public static final String PROP_HB_TIMEOUT_MS = "labos.workflow.runtime.gateway.heartbeat-timeout-ms";
    public static final String PROP_RECONNECT_MIN_MS = "labos.workflow.runtime.gateway.reconnect-initial-ms";
    public static final String PROP_RECONNECT_MAX_MS = "labos.workflow.runtime.gateway.reconnect-max-ms";
    public static final String PROP_MAX_FRAME_BYTES = "labos.workflow.runtime.gateway.max-frame-bytes";
    public static final String PROP_MAX_EVENT_QUEUE = "labos.workflow.runtime.gateway.max-event-queue-size";
    public static final String PROP_BRIDGE_ENABLED = "labos.workflow.runtime.bridge.enabled";
    public static final String PROP_BRIDGE_COMMAND_TIMEOUT_MS = "labos.workflow.runtime.bridge.command-timeout-ms";
    public static final String PROP_BRIDGE_RESOURCE_MAP = "labos.workflow.runtime.bridge.resource-map";
    public static final String PROP_DISCOVER_FROM_REGISTRY = "labos.workflow.runtime.gateway.discover-from-registry";

    public WorkflowRuntimeHostProperties {
        Objects.requireNonNull(lockFile, "lockFile");
        if (hostId == null || hostId.isBlank()) throw new IllegalArgumentException("hostId is required");
        Objects.requireNonNull(dispatchPollInterval, "dispatchPollInterval");
        deviceEndpoints = List.copyOf(Objects.requireNonNull(deviceEndpoints, "deviceEndpoints"));
        Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        if (scopedKernelTenantId != null && scopedKernelTenantId <= 0) {
            throw new IllegalArgumentException("scopedKernelTenantId must be positive");
        }
        workstationEndpoints = List.copyOf(workstationEndpoints == null ? List.of() : workstationEndpoints);
        securityMode = securityMode == null ? SecurityMode.TLS13_MTLS : securityMode;
    }

    public WorkflowRuntimeHostProperties(
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
            TcpDeviceConnection.Config gatewayConfig,
            Long scopedKernelTenantId) {
        this(enabled, lockFile, hostId, workerThreads, workerQueueCapacity, maxConcurrentRuns,
                mailboxThreads, mailboxQueueCapacity, recoveryBatchLimit, dispatchPollInterval,
                dispatchBatchSize, deviceEndpoints, gatewayConfig, scopedKernelTenantId,
                List.of(), SecurityMode.TLS13_MTLS);
    }

    public WorkflowRuntimeHostProperties(
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
        this(enabled, lockFile, hostId, workerThreads, workerQueueCapacity, maxConcurrentRuns,
                mailboxThreads, mailboxQueueCapacity, recoveryBatchLimit, dispatchPollInterval,
                dispatchBatchSize, deviceEndpoints, gatewayConfig, null,
                List.of(), SecurityMode.TLS13_MTLS);
    }

    public static WorkflowRuntimeHostProperties fromEnvironment(Environment env) {
        Objects.requireNonNull(env, "env");
        boolean enabled = Boolean.parseBoolean(env.getProperty(PROP_ENABLED, "false"));
        if (!enabled) {
            // When disabled, skip eager parsing of runtime/gateway properties so malformed or
            // stale settings never break legacy Kernel startup.
            return new WorkflowRuntimeHostProperties(
                    false,
                    Path.of("runtime-host.lock"),
                    "labos-local-host",
                    4, 64, 16, 2, 128, 100,
                    Duration.ofMillis(250L), 32,
                    List.of(),
                    TcpDeviceConnection.Config.defaults(),
                    null,
                    List.of(),
                    SecurityMode.TLS13_MTLS);
        }
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
        Long scopedKernelTenantId = optionalPositiveLongProp(env, PROP_SCOPED_KERNEL_TENANT_ID);
        SecurityMode securityMode = parseSecurityMode(env.getProperty(PROP_SECURITY_MODE, SecurityMode.TLS13_MTLS.name()));
        List<WorkstationEndpoint> workstationEndpoints = parseWorkstationEndpoints(
                env.getProperty(PROP_WORKSTATION_ENDPOINTS, ""), securityMode);
        List<TcpDeviceConnection.Endpoint> endpoints = parseEndpoints(env.getProperty(PROP_ENDPOINTS, ""));
        if (securityMode == SecurityMode.DEV_LOOPBACK_PLAINTEXT) {
            for (TcpDeviceConnection.Endpoint ep : endpoints) {
                securityMode.validateHost(ep.host());
            }
        }
        TcpDeviceConnection.Config gatewayConfig = new TcpDeviceConnection.Config(
                Duration.ofMillis(longProp(env, PROP_CONNECT_TIMEOUT_MS, 3000L)),
                Duration.ofMillis(longProp(env, PROP_HB_INTERVAL_MS, 5000L)),
                Duration.ofMillis(longProp(env, PROP_HB_TIMEOUT_MS, 15000L)),
                Duration.ofMillis(longProp(env, PROP_RECONNECT_MIN_MS, 200L)),
                Duration.ofMillis(longProp(env, PROP_RECONNECT_MAX_MS, 5000L)),
                intProp(env, PROP_MAX_FRAME_BYTES, 65536),
                intProp(env, PROP_MAX_EVENT_QUEUE, 256));
        return new WorkflowRuntimeHostProperties(
                true, lockFile, hostId, workerThreads, workerQueue, maxRuns,
                mailboxThreads, mailboxQueue, recoveryLimit, pollInterval, batchSize,
                endpoints, gatewayConfig, scopedKernelTenantId, workstationEndpoints, securityMode);
    }

    public EmbeddedWorkflowRuntimeHost.Config toHostConfig() {
        return toHostConfig(deviceEndpoints);
    }

    public EmbeddedWorkflowRuntimeHost.Config toHostConfig(List<TcpDeviceConnection.Endpoint> effectiveEndpoints) {
        return toHostConfig(effectiveEndpoints, null);
    }

    public EmbeddedWorkflowRuntimeHost.Config toHostConfig(
            List<TcpDeviceConnection.Endpoint> effectiveEndpoints,
            UUID scopedTenantId) {
        return new EmbeddedWorkflowRuntimeHost.Config(
                lockFile, hostId, workerThreads, workerQueueCapacity, maxConcurrentRuns,
                mailboxThreads, mailboxQueueCapacity, recoveryBatchLimit,
                dispatchPollInterval, dispatchBatchSize, effectiveEndpoints, gatewayConfig, scopedTenantId);
    }

    public static SecurityMode parseSecurityMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return SecurityMode.TLS13_MTLS;
        }
        try {
            return SecurityMode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported runtime security mode: " + raw, e);
        }
    }

    /**
     * Parse comma-separated C2 workstation endpoints
     * ({@code workstationId=[tls://|ndjson://]host:port}).
     */
    public static List<WorkstationEndpoint> parseWorkstationEndpoints(String raw, SecurityMode securityMode) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        SecurityMode effectiveMode = securityMode == null ? SecurityMode.TLS13_MTLS : securityMode;
        String defaultScheme = effectiveMode == SecurityMode.DEV_LOOPBACK_PLAINTEXT ? "ndjson" : "tls";
        List<WorkstationEndpoint> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid workstation endpoint (expected workstationId=[scheme://]host:port): " + trimmed);
            }
            String workstationId = trimmed.substring(0, eq).trim();
            String addr = trimmed.substring(eq + 1).trim();
            String scheme = defaultScheme;
            int schemeIdx = addr.indexOf("://");
            if (schemeIdx >= 0) {
                scheme = addr.substring(0, schemeIdx).trim().toLowerCase();
                addr = addr.substring(schemeIdx + 3).trim();
            }
            int colon = addr.lastIndexOf(':');
            if (colon <= 0 || colon == addr.length() - 1) {
                throw new IllegalArgumentException("Invalid workstation endpoint host:port: " + trimmed);
            }
            String host = addr.substring(0, colon).trim();
            int port;
            try {
                port = Integer.parseInt(addr.substring(colon + 1).trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid workstation endpoint port: " + trimmed, nfe);
            }
            out.add(new WorkstationEndpoint(workstationId, scheme, host, port, effectiveMode));
        }
        return List.copyOf(out);
    }

    /**
     * Parse comma-separated {@code deviceId=[ndjson://]host:port} entries
     * (blank string yields empty list; omitting scheme defaults to {@link NdjsonWireCodec};
     * {@code labsep://} is removed under Contract v1.1).
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
                throw new IllegalArgumentException("Invalid gateway endpoint entry (expected deviceId=[scheme://]host:port): " + trimmed);
            }
            String deviceId = trimmed.substring(0, eq).trim();
            String addr = trimmed.substring(eq + 1).trim();
            GatewayWireCodec codec = NdjsonWireCodec.INSTANCE;
            int schemeIdx = addr.indexOf("://");
            if (schemeIdx >= 0) {
                String scheme = addr.substring(0, schemeIdx).trim().toLowerCase();
                addr = addr.substring(schemeIdx + 3).trim();
                codec = switch (scheme) {
                    case "ndjson" -> NdjsonWireCodec.INSTANCE;
                    case "labsep" -> throw new IllegalArgumentException(
                            "labsep:// endpoint scheme is removed under Contract v1.1 (use ndjson:// or tls://): "
                                    + trimmed);
                    default -> throw new IllegalArgumentException(
                            "Unsupported gateway wire codec scheme '" + scheme + "' in endpoint: " + trimmed);
                };
            }
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
            out.add(new TcpDeviceConnection.Endpoint(deviceId, host, port, codec));
        }
        return List.copyOf(out);
    }

    /**
     * Parse comma-separated {@code resourceId=deviceId} mappings (e.g. {@code 206=pipettor,207=centrifuge}).
     */
    public static Map<Long, String> parseResourceDeviceMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<Long, String> out = new LinkedHashMap<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid bridge resource-map entry (expected resourceId=deviceId): " + trimmed);
            }
            long resourceId;
            try {
                resourceId = Long.parseLong(trimmed.substring(0, eq).trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid numeric resourceId in resource-map: " + trimmed, nfe);
            }
            if (resourceId <= 0) {
                throw new IllegalArgumentException("resourceId must be positive: " + trimmed);
            }
            String deviceId = trimmed.substring(eq + 1).trim();
            if (deviceId.isEmpty()) {
                throw new IllegalArgumentException("deviceId must not be blank in resource-map: " + trimmed);
            }
            out.put(resourceId, deviceId);
        }
        return Map.copyOf(out);
    }

    /** Resolved endpoints and {@code resourceId -> deviceId} map discovered from {@code DeviceMapper.selectDeviceRegistry}. */
    public record RegistryEndpoints(
            List<TcpDeviceConnection.Endpoint> endpoints,
            Map<Long, String> resourceToDeviceMap) {
        public RegistryEndpoints {
            endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
            resourceToDeviceMap = Map.copyOf(Objects.requireNonNull(resourceToDeviceMap, "resourceToDeviceMap"));
        }
    }

    /**
     * Deprecated temporary Stage 0 bridge converting {@code DeviceMapper.selectDeviceRegistry} rows
     * into C1 endpoints for existing Kernel E2E self-tests; AI-2 replaces this in Stage 1 with C2
     * workstation discovery.
     *
     * @deprecated Stage 0 compatibility bridge; scheduled for removal once C2 Sidecar discovery lands.
     */
    @Deprecated(forRemoval = true)
    public static RegistryEndpoints fromDeviceRegistryRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new RegistryEndpoints(List.of(), Map.of());
        }
        List<TcpDeviceConnection.Endpoint> endpoints = new ArrayList<>();
        Map<Long, String> resMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            Long devId = toLong(row.get("deviceId"));
            if (devId == null || devId <= 0) continue;
            String rawEndpoint = row.get("endpoint") == null ? "" : row.get("endpoint").toString().trim();
            if (rawEndpoint.isEmpty()) continue;

            GatewayWireCodec codec = com.labos.kernel.workflow.runtime.gateway.LabSepWireCodec.INSTANCE;
            String addr = rawEndpoint;
            int schemeIdx = addr.indexOf("://");
            if (schemeIdx >= 0) {
                String scheme = addr.substring(0, schemeIdx).trim().toLowerCase();
                addr = addr.substring(schemeIdx + 3).trim();
                codec = switch (scheme) {
                    case "ndjson" -> NdjsonWireCodec.INSTANCE;
                    case "tcp", "labsep" -> com.labos.kernel.workflow.runtime.gateway.LabSepWireCodec.INSTANCE;
                    default -> throw new IllegalArgumentException(
                            "Unsupported registry driver endpoint scheme '" + scheme + "': " + rawEndpoint);
                };
            }
            int colon = addr.lastIndexOf(':');
            if (colon <= 0 || colon == addr.length() - 1) continue;
            String endpointHost = addr.substring(0, colon).trim();
            int port;
            try {
                port = Integer.parseInt(addr.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (port < 1 || port > 65535) continue;

            String explicitIp = row.get("ip") == null ? "" : row.get("ip").toString().trim();
            String effectiveHost = explicitIp.isEmpty() ? endpointHost : explicitIp;
            if (effectiveHost.isEmpty()) continue;

            String deviceKey = String.valueOf(devId);
            endpoints.add(new TcpDeviceConnection.Endpoint(deviceKey, effectiveHost, port, codec));
            Long resourceId = toLong(row.get("resourceId"));
            if (resourceId != null && resourceId > 0) {
                resMap.put(resourceId, deviceKey);
            }
        }
        return new RegistryEndpoints(endpoints, resMap);
    }

    /** Merge registry-discovered endpoints with explicit config (explicit entries win on duplicate deviceId). */
    public static List<TcpDeviceConnection.Endpoint> mergeEndpoints(
            List<TcpDeviceConnection.Endpoint> discovered,
            List<TcpDeviceConnection.Endpoint> explicit) {
        Map<String, TcpDeviceConnection.Endpoint> byDevice = new LinkedHashMap<>();
        if (discovered != null) {
            for (var ep : discovered) byDevice.put(ep.deviceId(), ep);
        }
        if (explicit != null) {
            for (var ep : explicit) byDevice.put(ep.deviceId(), ep);
        }
        return List.copyOf(byDevice.values());
    }

    /** Merge registry-discovered resource->device map with explicit overrides (explicit wins). */
    public static Map<Long, String> mergeResourceMaps(Map<Long, String> discovered, Map<Long, String> explicit) {
        Map<Long, String> merged = new LinkedHashMap<>();
        if (discovered != null) merged.putAll(discovered);
        if (explicit != null) merged.putAll(explicit);
        return Map.copyOf(merged);
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int intProp(Environment env, String key, int fallback) {
        String raw = env.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Integer.parseInt(raw.trim());
    }

    private static long longProp(Environment env, String key, long fallback) {
        String raw = env.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : Long.parseLong(raw.trim());
    }

    private static Long optionalPositiveLongProp(Environment env, String key) {
        String raw = env.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Invalid numeric property " + key + ": " + raw, nfe);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("Property " + key + " must be positive: " + raw);
        }
        return parsed;
    }
}
