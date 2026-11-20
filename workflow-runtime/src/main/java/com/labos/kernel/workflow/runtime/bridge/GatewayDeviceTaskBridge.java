package com.labos.kernel.workflow.runtime.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.gateway.TcpDeviceConnection;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.worker.WorkerTaskHandler;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business bridge implementing both {@link LocalTaskDispatchService.TaskDispatchSpecResolver} and
 * {@link LocalTaskDispatchService.TaskHandlerResolver} to execute compiled BPMN {@link GraphPlan.Node}s
 * against registered {@link com.labos.kernel.workflow.runtime.gateway.DeviceGateway} TCP devices
 * (NDJSON or legacy {@code #!LABSEP!#}) and safe pure-logic builtins ({@code __noop}, {@code __sleep},
 * {@code __set_var}).
 *
 * <p>Device tasks enforce single-command-per-device capacity ({@code capacity=1} on {@code device:<id>}),
 * issue durable commands exclusively through {@code WorkerExecutionContext.createDeviceCommand(...)},
 * and escalate command timeouts to {@code UNKNOWN} so they surface for T21 manual reconciliation
 * without ever auto-resending commands.
 */
public final class GatewayDeviceTaskBridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowRuntimeRepository repository;
    private final Set<String> registeredDeviceIds;
    private final Map<Long, String> resourceToDeviceMap;
    private final Duration commandTimeout;
    private final Duration pollInterval;

    public GatewayDeviceTaskBridge(
            WorkflowRuntimeRepository repository,
            List<TcpDeviceConnection.Endpoint> endpoints,
            Map<Long, String> resourceToDeviceMap,
            Duration commandTimeout) {
        this(repository,
                Objects.requireNonNull(endpoints, "endpoints").stream()
                        .map(TcpDeviceConnection.Endpoint::deviceId)
                        .collect(Collectors.toUnmodifiableSet()),
                resourceToDeviceMap,
                commandTimeout,
                Duration.ofMillis(25));
    }

    public GatewayDeviceTaskBridge(
            WorkflowRuntimeRepository repository,
            Set<String> registeredDeviceIds,
            Map<Long, String> resourceToDeviceMap,
            Duration commandTimeout,
            Duration pollInterval) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registeredDeviceIds = Set.copyOf(Objects.requireNonNull(registeredDeviceIds, "registeredDeviceIds"));
        this.resourceToDeviceMap = Map.copyOf(resourceToDeviceMap == null ? Map.of() : resourceToDeviceMap);
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (commandTimeout.isZero() || commandTimeout.isNegative())
            throw new IllegalArgumentException("commandTimeout must be positive");
        if (pollInterval.isZero() || pollInterval.isNegative())
            throw new IllegalArgumentException("pollInterval must be positive");
    }

    /** Resolve the target {@code DeviceGateway} deviceId for a compiled BPMN TASK node. */
    public String resolveDeviceId(GraphPlan.Node node) {
        Objects.requireNonNull(node, "node");
        if (node.resourceId() != null) {
            String mapped = resourceToDeviceMap.get(node.resourceId());
            if (mapped != null && registeredDeviceIds.contains(mapped)) {
                return mapped;
            }
            String rawId = String.valueOf(node.resourceId());
            if (registeredDeviceIds.contains(rawId)) {
                return rawId;
            }
            String prefixed = "device-" + rawId;
            if (registeredDeviceIds.contains(prefixed)) {
                return prefixed;
            }
        }
        if (node.capabilityCode() != null && registeredDeviceIds.contains(node.capabilityCode())) {
            return node.capabilityCode();
        }
        throw new IllegalArgumentException(
                "No registered DeviceGateway endpoint matches BPMN node '" + node.key()
                        + "' (resourceId=" + node.resourceId()
                        + ", capabilityCode=" + node.capabilityCode() + ")");
    }

    public LocalTaskDispatchService.TaskDispatchSpecResolver specResolver() {
        return this::resolveDispatchSpec;
    }

    public LocalTaskDispatchService.TaskHandlerResolver handlerResolver() {
        return this::resolveHandler;
    }

    public LocalTaskDispatchService.TaskDispatchSpec resolveDispatchSpec(GraphPlan.Node node) {
        Objects.requireNonNull(node, "node");
        String payloadJson = normalizeJsonObject(node.methodParams());
        if (node.kind() == GraphPlan.Kind.BUILTIN) {
            String proto = node.protocol() == null ? "__builtin" : node.protocol();
            return new LocalTaskDispatchService.TaskDispatchSpec(
                    "builtin:" + proto,
                    16,
                    "PURE",
                    "builtin",
                    commandTimeout,
                    payloadJson);
        }
        if (node.kind() == GraphPlan.Kind.TASK) {
            String deviceId = resolveDeviceId(node);
            return new LocalTaskDispatchService.TaskDispatchSpec(
                    "device:" + deviceId,
                    1,
                    "DEVICE",
                    "device:" + deviceId,
                    commandTimeout,
                    payloadJson);
        }
        throw new IllegalArgumentException("Unsupported dispatchable node kind: " + node.kind());
    }

    public Optional<WorkerTaskHandler> resolveHandler(GraphPlan.Node node) {
        Objects.requireNonNull(node, "node");
        if (node.kind() == GraphPlan.Kind.BUILTIN) {
            return resolveBuiltinHandler(node);
        }
        if (node.kind() == GraphPlan.Kind.TASK) {
            String deviceId = resolveDeviceId(node);
            String commandKind = node.protocol();
            if (commandKind == null || commandKind.isBlank()) {
                return Optional.empty();
            }
            String payloadJson = normalizeJsonObject(node.methodParams());
            return Optional.of((req, ctx) -> executeDeviceTask(req, ctx, deviceId, commandKind, payloadJson));
        }
        return Optional.empty();
    }

    private Optional<WorkerTaskHandler> resolveBuiltinHandler(GraphPlan.Node node) {
        String proto = node.protocol() == null ? "" : node.protocol().trim();
        return switch (proto) {
            case "__noop", "__pass" -> Optional.of((req, ctx) ->
                    WorkerTaskHandler.TaskResult.of("out", "{\"builtin\":\"" + proto + "\"}"));
            case "__sleep", "__delay" -> Optional.of((req, ctx) -> {
                long delayMs = extractDelayMs(node.methodParams());
                long deadline = System.nanoTime() + Duration.ofMillis(delayMs).toNanos();
                while (System.nanoTime() < deadline) {
                    if (ctx.isCancellationRequested()) {
                        throw new InterruptedException("Builtin " + proto + " cancelled before completion");
                    }
                    long remainingMs = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
                    Thread.sleep(Math.min(25L, remainingMs));
                }
                return WorkerTaskHandler.TaskResult.of("out", "{\"sleptMs\":" + delayMs + "}");
            });
            case "__set_var", "__export" -> Optional.of((req, ctx) -> {
                Map<String, String> exported = extractFlowVariables(node.methodParams());
                return new WorkerTaskHandler.TaskResult("out", normalizeJsonObject(node.methodParams()), exported);
            });
            default -> Optional.empty();
        };
    }

    private WorkerTaskHandler.TaskResult executeDeviceTask(
            WorkerTaskHandler.TaskRequest req,
            com.labos.kernel.workflow.runtime.worker.WorkerExecutionContext ctx,
            String deviceId,
            String commandKind,
            String payloadJson) throws Exception {
        UUID cmdId = ctx.createDeviceCommand(deviceId, commandKind, "QUERYABLE", payloadJson);
        long deadlineNanos = System.nanoTime() + commandTimeout.toNanos();
        long pollMs = Math.max(10L, pollInterval.toMillis());
        while (System.nanoTime() < deadlineNanos) {
            Optional<WorkflowRuntimeRepository.CommandRecord> cmdOpt =
                    repository.loadCommand(req.tenantId(), cmdId);
            if (cmdOpt.isEmpty()) {
                throw new IllegalStateException("Command disappeared from command_ledger: " + cmdId);
            }
            String state = cmdOpt.get().commandState();
            if ("SUCCEEDED".equals(state)) {
                String out = "{\"commandId\":\"" + cmdId
                        + "\",\"deviceId\":\"" + escapeJson(deviceId)
                        + "\",\"commandKind\":\"" + escapeJson(commandKind)
                        + "\",\"state\":\"SUCCEEDED\"}";
                return WorkerTaskHandler.TaskResult.of("out", out);
            }
            if ("FAILED".equals(state) || "CANCELLED".equals(state)
                    || "UNKNOWN".equals(state) || "DELIVERY_UNKNOWN".equals(state)) {
                throw new RuntimeException("Device command did not succeed: commandId=" + cmdId
                        + ", deviceId=" + deviceId + ", state=" + state);
            }
            Thread.sleep(pollMs);
        }
        repository.markCommandUnknown(req.tenantId(), cmdId,
                "{\"reason\":\"bridge_command_timeout\",\"deviceId\":\"" + escapeJson(deviceId) + "\"}");
        throw new RuntimeException("Device command timed out after " + commandTimeout
                + ": commandId=" + cmdId + ", deviceId=" + deviceId);
    }

    private static String normalizeJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = JSON.readTree(raw);
            return node.isObject() ? JSON.writeValueAsString(node) : "{}";
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON methodParams: " + raw, e);
        }
    }

    private static long extractDelayMs(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.has("ms") && node.get("ms").isNumber()) {
                return Math.max(0L, node.get("ms").asLong());
            }
            if (node.has("delayMs") && node.get("delayMs").isNumber()) {
                return Math.max(0L, node.get("delayMs").asLong());
            }
            if (node.has("seconds") && node.get("seconds").isNumber()) {
                return Math.max(0L, Math.round(node.get("seconds").asDouble() * 1000.0));
            }
            return 0L;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid __sleep methodParams: " + raw, e);
        }
    }

    private static Map<String, String> extractFlowVariables(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isObject()) {
                throw new IllegalArgumentException("__set_var methodParams must be a JSON object");
            }
            Map<String, String> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), JSON.writeValueAsString(e.getValue()));
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid __set_var methodParams: " + raw, e);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
