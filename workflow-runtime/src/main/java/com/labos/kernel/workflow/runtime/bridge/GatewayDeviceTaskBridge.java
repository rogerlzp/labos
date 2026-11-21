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
    private final Map<String, String> deviceToWorkstationMap;
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
                endpoints.stream().collect(Collectors.toUnmodifiableMap(
                        TcpDeviceConnection.Endpoint::deviceId,
                        ep -> "ws-" + ep.deviceId(),
                        (a, b) -> a)),
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
        this(repository, registeredDeviceIds, Map.of(), resourceToDeviceMap, commandTimeout, pollInterval);
    }

    public GatewayDeviceTaskBridge(
            WorkflowRuntimeRepository repository,
            Set<String> registeredDeviceIds,
            Map<String, String> deviceToWorkstationMap,
            Map<Long, String> resourceToDeviceMap,
            Duration commandTimeout,
            Duration pollInterval) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registeredDeviceIds = Set.copyOf(Objects.requireNonNull(registeredDeviceIds, "registeredDeviceIds"));
        this.deviceToWorkstationMap = Map.copyOf(deviceToWorkstationMap == null ? Map.of() : deviceToWorkstationMap);
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
            case "__setVar", "__set_var", "__export" -> Optional.of((req, ctx) -> {
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
        // Pre-side-effect cooperative cancellation gate: abort cleanly before issuing any physical command.
        if (ctx.isCancellationRequested()) {
            throw new InterruptedException("Device task cancelled before command dispatch: " + deviceId);
        }
        String workstationId = deviceToWorkstationMap.getOrDefault(deviceId, "ws-" + deviceId);
        UUID cmdId = ctx.createDeviceCommand(workstationId, deviceId, commandKind, commandKind, "QUERYABLE", payloadJson);
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
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ignoredDuringInFlightDeviceCommand) {
                // Physical device command is ALREADY in flight (SIDE_EFFECT_STARTED committed).
                // Per dual-gate safety: do NOT abandon the in-flight command on cooperative STOP cancel;
                // continue waiting for the physical device outcome (or commandTimeout -> UNKNOWN) so
                // the Run converges cleanly to STOPPED instead of falsely escalating to MANUAL_REQUIRED.
            }
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
            Double ms = readNumericField(node, "ms");
            if (ms == null) ms = readNumericField(node, "delayMs");
            if (ms != null) {
                return Math.max(0L, Math.round(ms));
            }
            Double sec = readNumericField(node, "sec");
            if (sec == null) sec = readNumericField(node, "seconds");
            if (sec != null) {
                return Math.max(0L, Math.round(sec * 1000.0));
            }
            return 0L;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid __sleep methodParams: " + raw, e);
        }
    }

    private static Double readNumericField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asDouble();
        if (v.isTextual()) {
            String trimmed = v.asText().trim();
            if (trimmed.isEmpty()) return null;
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Non-numeric __sleep field '" + field + "': " + trimmed, nfe);
            }
        }
        throw new IllegalArgumentException("Unsupported __sleep field '" + field + "' type: " + v.getNodeType());
    }

    private static Map<String, String> extractFlowVariables(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isObject()) {
                throw new IllegalArgumentException("__setVar methodParams must be a JSON object");
            }
            // Frontend Designer form format: {"var":"score","value":"92"} (or JSON typed value).
            if (node.has("var") && node.get("var").isTextual() && node.has("value") && node.size() <= 2) {
                String varName = node.get("var").asText().trim();
                if (varName.isEmpty()) {
                    throw new IllegalArgumentException("__setVar 'var' field must not be blank");
                }
                return Map.of(varName, coerceScalarJson(node.get("value")));
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
            throw new IllegalArgumentException("Invalid __setVar methodParams: " + raw, e);
        }
    }

    /**
     * Preserve native JSON scalars as-is; for string values coming from UI text inputs,
     * coerce unambiguous booleans ("true"/"false") and strict canonical numbers (no leading
     * zeros such as "001") so typed expressions like {@code score >= 80} evaluate naturally.
     */
    private static String coerceScalarJson(JsonNode valueNode) throws Exception {
        if (valueNode == null || valueNode.isNull()) {
            return "null";
        }
        if (!valueNode.isTextual()) {
            return JSON.writeValueAsString(valueNode);
        }
        String text = valueNode.asText().trim();
        if ("true".equals(text) || "false".equals(text)) {
            return text;
        }
        if (text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            return text;
        }
        return JSON.writeValueAsString(valueNode.asText());
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
