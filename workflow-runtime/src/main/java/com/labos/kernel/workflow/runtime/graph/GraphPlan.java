package com.labos.kernel.workflow.runtime.graph;

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

    public GraphPlan {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        compilerVersion = required(compilerVersion, "compilerVersion");
        definitionKey = required(definitionKey, "definitionKey");
        definitionVersion = required(definitionVersion, "definitionVersion");
        bpmnSha256 = required(bpmnSha256, "bpmnSha256");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (nodes.isEmpty()) throw new IllegalArgumentException("GraphPlan has no nodes");
    }

    public record Node(
            String key, String name, Kind kind, String sourceType,
            String protocol, Long resourceId, String methodParams,
            String stageCode, String capabilityCode, Long workflowNodeId) {
        public Node {
            key = required(key, "node.key");
            name = required(name, "node.name");
            Objects.requireNonNull(kind, "node.kind");
            sourceType = required(sourceType, "node.sourceType");
        }
    }

    public record Edge(String key, String from, String to, String condition, boolean defaultFlow) {
        public Edge {
            key = required(key, "edge.key");
            from = required(from, "edge.from");
            to = required(to, "edge.to");
        }
    }

    public enum Kind {
        TASK, BUILTIN, START, END,
        SPLIT_PARALLEL, JOIN_PARALLEL,
        SPLIT_XOR, JOIN_XOR,
        SPLIT_INCLUSIVE, JOIN_INCLUSIVE
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
