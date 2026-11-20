package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.DeterministicConditionEvaluator;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;

import java.util.*;

/**
 * Deterministic, side-effect-free graph transition planner.
 *
 * <p>This class decides which edges are enabled. Persistence, token creation and
 * dispatch remain the responsibility of the transactional coordinator adapter.
 * It deliberately refuses unsupported or ambiguous gateway shapes.
 */
public final class GraphCoordinator {
    private final DeterministicConditionEvaluator conditions;

    public GraphCoordinator(DeterministicConditionEvaluator conditions) {
        this.conditions = Objects.requireNonNull(conditions, "conditions");
    }

    public enum DecisionKind { ADVANCE, SPLIT, WAIT_FOR_JOIN, JOIN_RELEASED, COMPLETE }

    public record JoinProgress(Set<String> expectedBranches, Set<String> arrivedBranches) {
        public JoinProgress {
            expectedBranches = Set.copyOf(expectedBranches);
            arrivedBranches = Set.copyOf(arrivedBranches);
            if (!expectedBranches.containsAll(arrivedBranches))
                throw new IllegalArgumentException("Arrived branches must be a subset of expected branches");
        }
        public boolean complete() { return !expectedBranches.isEmpty() && arrivedBranches.containsAll(expectedBranches); }
    }

    public record Decision(DecisionKind kind, String nodeKey, List<String> nextNodeKeys,
                           List<String> selectedEdgeKeys) {
        public Decision {
            Objects.requireNonNull(kind, "kind");
            nodeKey = Objects.requireNonNull(nodeKey, "nodeKey");
            nextNodeKeys = List.copyOf(nextNodeKeys);
            selectedEdgeKeys = List.copyOf(selectedEdgeKeys);
        }
    }

    public Decision onActivationSucceeded(GraphPlan plan, String nodeKey,
                                          Map<String, ?> variables, JoinProgress joinProgress) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(variables, "variables");
        GraphPlan.Node node = plan.nodes().stream().filter(n -> n.key().equals(nodeKey))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown node: " + nodeKey));
        List<GraphPlan.Edge> outgoing = plan.edges().stream().filter(e -> e.from().equals(nodeKey)).toList();

        if (node.kind() == GraphPlan.Kind.END) {
            if (!outgoing.isEmpty()) throw new IllegalArgumentException("End node cannot have outgoing edges: " + nodeKey);
            return new Decision(DecisionKind.COMPLETE, nodeKey, List.of(), List.of());
        }
        if (node.kind() == GraphPlan.Kind.JOIN_PARALLEL || node.kind() == GraphPlan.Kind.JOIN_INCLUSIVE) {
            if (joinProgress == null) return new Decision(DecisionKind.WAIT_FOR_JOIN, nodeKey, List.of(), List.of());
            if (!joinProgress.complete()) return new Decision(DecisionKind.WAIT_FOR_JOIN, nodeKey, List.of(), List.of());
            return selectSingleContinuation(nodeKey, outgoing, variables, DecisionKind.JOIN_RELEASED);
        }
        if (node.kind() == GraphPlan.Kind.JOIN_XOR)
            return selectSingleContinuation(nodeKey, outgoing, variables, DecisionKind.ADVANCE);

        if (node.kind() == GraphPlan.Kind.SPLIT_PARALLEL) {
            if (outgoing.isEmpty()) throw new IllegalArgumentException("Parallel split has no outgoing edges: " + nodeKey);
            return decision(DecisionKind.SPLIT, nodeKey, outgoing);
        }
        if (node.kind() == GraphPlan.Kind.SPLIT_XOR || node.kind() == GraphPlan.Kind.SPLIT_INCLUSIVE) {
            List<GraphPlan.Edge> selected = selectConditional(outgoing, variables,
                    node.kind() == GraphPlan.Kind.SPLIT_XOR);
            if (selected.isEmpty()) throw new IllegalStateException("No matching conditional/default flow at " + nodeKey);
            return decision(node.kind() == GraphPlan.Kind.SPLIT_XOR ? DecisionKind.ADVANCE : DecisionKind.SPLIT,
                    nodeKey, selected);
        }

        if (outgoing.isEmpty()) throw new IllegalArgumentException("Non-end node has no outgoing edge: " + nodeKey);
        if (outgoing.size() != 1) throw new IllegalArgumentException("Non-gateway node has multiple outgoing edges: " + nodeKey);
        GraphPlan.Edge edge = outgoing.get(0);
        if (edge.condition() != null || edge.defaultFlow())
            throw new IllegalArgumentException("Conditional/default flow must originate at a supported gateway: " + edge.key());
        return decision(DecisionKind.ADVANCE, nodeKey, List.of(edge));
    }

    private Decision selectSingleContinuation(String nodeKey, List<GraphPlan.Edge> outgoing,
                                               Map<String, ?> variables, DecisionKind kind) {
        if (outgoing.size() != 1)
            throw new IllegalArgumentException("Join continuation must have exactly one outgoing edge: " + nodeKey);
        GraphPlan.Edge edge = outgoing.get(0);
        if (edge.condition() != null || edge.defaultFlow())
            throw new IllegalArgumentException("Join continuation cannot be conditional: " + edge.key());
        return decision(kind, nodeKey, List.of(edge));
    }

    private List<GraphPlan.Edge> selectConditional(List<GraphPlan.Edge> outgoing,
                                                   Map<String, ?> variables, boolean exclusive) {
        List<GraphPlan.Edge> defaults = outgoing.stream().filter(GraphPlan.Edge::defaultFlow).toList();
        if (defaults.size() > 1) throw new IllegalArgumentException("Gateway has multiple default flows");
        List<GraphPlan.Edge> matches = new ArrayList<>();
        for (GraphPlan.Edge edge : outgoing) {
            if (edge.defaultFlow()) continue;
            if (edge.condition() == null)
                throw new IllegalArgumentException("Conditional gateway edge has no condition: " + edge.key());
            if (conditions.evaluate(edge.condition(), variables)) matches.add(edge);
        }
        if (exclusive) {
            if (matches.size() > 1)
                throw new IllegalStateException("XOR gateway has multiple matching conditions: " +
                        matches.stream().map(GraphPlan.Edge::key).toList());
            if (matches.isEmpty()) matches.addAll(defaults);
            return List.copyOf(matches);
        }
        if (matches.isEmpty()) matches.addAll(defaults);
        return List.copyOf(matches);
    }

    private static Decision decision(DecisionKind kind, String nodeKey, List<GraphPlan.Edge> edges) {
        return new Decision(kind, nodeKey, edges.stream().map(GraphPlan.Edge::to).toList(),
                edges.stream().map(GraphPlan.Edge::key).toList());
    }
}
