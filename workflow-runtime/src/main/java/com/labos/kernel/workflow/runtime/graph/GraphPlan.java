package com.labos.kernel.workflow.runtime.graph;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, versioned execution representation compiled from an authoritative BPMN definition.
 * Runtime state (Run, Token, Activation and Attempt) deliberately does not belong here.
 */
public record GraphPlan(
        int schemaVersion,
        String compilerVersion,
        String definitionKey,
        String definitionVersion,
        String bpmnSha256,
        List<Node> nodes,
        List<Edge> edges) {

    public static final int SCHEMA_VERSION_V1 = 1;
    public static final int SCHEMA_VERSION_V2 = 2;
    public static final long MAX_WAIT_DURATION_MILLIS = 365L * 24 * 60 * 60 * 1000;

    public GraphPlan {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (schemaVersion != SCHEMA_VERSION_V1 && schemaVersion != SCHEMA_VERSION_V2) {
            throw new IllegalArgumentException("Unsupported GraphPlan schemaVersion: " + schemaVersion);
        }
        compilerVersion = required(compilerVersion, "compilerVersion");
        definitionKey = required(definitionKey, "definitionKey");
        definitionVersion = required(definitionVersion, "definitionVersion");
        bpmnSha256 = required(bpmnSha256, "bpmnSha256");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (nodes.isEmpty()) throw new IllegalArgumentException("GraphPlan has no nodes");
        validateVersionAndWaitInvariants(schemaVersion, nodes, edges);
    }

    public record Node(
            String key, String name, Kind kind, String sourceType,
            String protocol, Long resourceId, String methodParams,
            String stageCode, String capabilityCode, Long workflowNodeId,
            WaitSpec waitSpec) {
        public Node(
                String key, String name, Kind kind, String sourceType,
                String protocol, Long resourceId, String methodParams,
                String stageCode, String capabilityCode, Long workflowNodeId) {
            this(key, name, kind, sourceType, protocol, resourceId, methodParams,
                    stageCode, capabilityCode, workflowNodeId, null);
        }

        public Node {
            key = required(key, "node.key");
            name = required(name, "node.name");
            Objects.requireNonNull(kind, "node.kind");
            sourceType = required(sourceType, "node.sourceType");
            if (kind == Kind.WAIT) {
                if (waitSpec == null) {
                    throw new IllegalArgumentException("WAIT node requires waitSpec: " + key);
                }
                if (hasTaskExecutionConfig(protocol, resourceId, methodParams, capabilityCode)) {
                    throw new IllegalArgumentException(
                            "WAIT node cannot carry task execution configuration: " + key);
                }
            } else if (waitSpec != null) {
                throw new IllegalArgumentException("Only WAIT nodes may carry waitSpec: " + key);
            }
        }
    }

    public record Edge(
            String key, String from, String to, String condition, boolean defaultFlow,
            RouteKind routeKind) {
        public Edge(String key, String from, String to, String condition, boolean defaultFlow) {
            this(key, from, to, condition, defaultFlow, null);
        }

        public Edge {
            key = required(key, "edge.key");
            from = required(from, "edge.from");
            to = required(to, "edge.to");
            if (routeKind == RouteKind.TIMEOUT && (condition != null || defaultFlow)) {
                throw new IllegalArgumentException(
                        "TIMEOUT edge cannot carry condition or defaultFlow: " + key);
            }
        }
    }

    public record WaitSpec(
            WaitKind waitKind,
            String correlationKey,
            String durationIso,
            Long durationMillis) {
        public WaitSpec {
            Objects.requireNonNull(waitKind, "waitSpec.waitKind");
            switch (waitKind) {
                case MESSAGE -> {
                    correlationKey = requireStaticCorrelationKey(correlationKey);
                    if (durationIso != null || durationMillis != null) {
                        throw new IllegalArgumentException(
                                "MESSAGE waitSpec must not specify duration");
                    }
                }
                case TIMER -> {
                    if (correlationKey != null) {
                        throw new IllegalArgumentException(
                                "TIMER waitSpec must not specify correlationKey");
                    }
                    durationIso = required(durationIso, "waitSpec.durationIso");
                    long expectedMillis = parseStrictDurationMillis(durationIso);
                    if (durationMillis == null || durationMillis != expectedMillis) {
                        throw new IllegalArgumentException(
                                "TIMER waitSpec durationMillis does not match durationIso");
                    }
                }
                case MESSAGE_WITH_TIMEOUT -> {
                    correlationKey = requireStaticCorrelationKey(correlationKey);
                    durationIso = required(durationIso, "waitSpec.durationIso");
                    long expectedMillis = parseStrictDurationMillis(durationIso);
                    if (durationMillis == null || durationMillis != expectedMillis) {
                        throw new IllegalArgumentException(
                                "MESSAGE_WITH_TIMEOUT waitSpec durationMillis does not match durationIso");
                    }
                }
            }
        }
    }

    public enum WaitKind {
        MESSAGE,
        TIMER,
        MESSAGE_WITH_TIMEOUT
    }

    public enum RouteKind {
        NORMAL,
        TIMEOUT
    }

    public enum Kind {
        TASK, BUILTIN, START, END, WAIT,
        SPLIT_PARALLEL, JOIN_PARALLEL,
        SPLIT_XOR, JOIN_XOR,
        SPLIT_INCLUSIVE, JOIN_INCLUSIVE
    }

    public static long parseStrictDurationMillis(String durationIso) {
        String raw = required(durationIso, "durationIso");
        if (!raw.equals(raw.trim()) || !raw.startsWith("P")) {
            throw new IllegalArgumentException("Invalid ISO-8601 wait duration: " + durationIso);
        }
        Duration parsed;
        try {
            parsed = Duration.parse(raw);
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 wait duration: " + durationIso, e);
        }
        if (parsed.isNegative() || parsed.isZero()) {
            throw new IllegalArgumentException("Wait duration must be positive: " + durationIso);
        }
        if (parsed.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(
                    "Wait duration must not contain sub-millisecond precision: " + durationIso);
        }
        if (parsed.compareTo(Duration.ofMillis(MAX_WAIT_DURATION_MILLIS)) > 0) {
            throw new IllegalArgumentException(
                    "Wait duration exceeds maximum of 365 days: " + durationIso);
        }
        long millis = parsed.toMillis();
        if (millis <= 0 || millis > MAX_WAIT_DURATION_MILLIS) {
            throw new IllegalArgumentException(
                    "Wait duration out of supported range: " + durationIso);
        }
        return millis;
    }

    private static void validateVersionAndWaitInvariants(int schemaVersion, List<Node> nodes, List<Edge> edges) {
        Map<String, Node> nodesByKey = new HashMap<>(nodes.size());
        for (Node n : nodes) {
            nodesByKey.putIfAbsent(n.key(), n);
            if (schemaVersion == SCHEMA_VERSION_V1 && (n.kind() == Kind.WAIT || n.waitSpec() != null)) {
                throw new IllegalArgumentException(
                        "GraphPlan v1 cannot contain WAIT nodes or waitSpec: " + n.key());
            }
        }
        for (Edge e : edges) {
            if (schemaVersion == SCHEMA_VERSION_V1 && e.routeKind() != null) {
                throw new IllegalArgumentException(
                        "GraphPlan v1 cannot contain edge routeKind: " + e.key());
            }
            Node source = nodesByKey.get(e.from());
            if (source == null) {
                continue;
            }
            if (source.kind() != Kind.WAIT && e.routeKind() != null) {
                throw new IllegalArgumentException(
                        "Only edges outgoing from WAIT nodes may carry routeKind: " + e.key());
            }
            if (source.kind() == Kind.WAIT) {
                if (e.routeKind() == null) {
                    throw new IllegalArgumentException(
                            "Edge outgoing from WAIT node must specify routeKind: " + e.key());
                }
                if (e.condition() != null || e.defaultFlow()) {
                    throw new IllegalArgumentException(
                            "WAIT outgoing edge cannot carry condition or defaultFlow: " + e.key());
                }
                if (e.routeKind() == RouteKind.TIMEOUT
                        && source.waitSpec().waitKind() != WaitKind.MESSAGE_WITH_TIMEOUT) {
                    throw new IllegalArgumentException(
                            "TIMEOUT edge requires MESSAGE_WITH_TIMEOUT wait node: " + e.key());
                }
            }
        }
    }

    private static boolean hasTaskExecutionConfig(
            String protocol, Long resourceId, String methodParams, String capabilityCode) {
        return (protocol != null && !protocol.isBlank())
                || resourceId != null
                || (methodParams != null && !methodParams.isBlank())
                || (capabilityCode != null && !capabilityCode.isBlank());
    }

    private static String requireStaticCorrelationKey(String correlationKey) {
        String key = required(correlationKey, "waitSpec.correlationKey");
        if (key.contains("${") || key.contains("#{")) {
            throw new IllegalArgumentException(
                    "Dynamic correlationKey expression is not supported: " + correlationKey);
        }
        return key;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
