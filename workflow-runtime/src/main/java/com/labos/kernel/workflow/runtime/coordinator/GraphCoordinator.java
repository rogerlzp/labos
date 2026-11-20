package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.graph.RestrictedExpressionEvaluator;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-process graph interpreter. It advances only graph-control nodes and routes
 * completed task Attempts; TASK/BUILTIN execution remains owned by the existing Worker path.
 *
 * <p>Every state mutation is delegated to one Repository transaction. This class contains
 * no SQL, device calls, worker scheduling, thread creation or distributed coordination.
 */
public final class GraphCoordinator {
    public record AdvanceSummary(int controlTokensAdvanced, int completedTasksRouted,
                                 int successorTokensCreated, boolean runCompleted) {}

    private final WorkflowRuntimeRepository repository;
    private final int batchSize;
    private final int maxTransitionsPerSignal;

    public GraphCoordinator(WorkflowRuntimeRepository repository) {
        this(repository, 100, 1000);
    }

    public GraphCoordinator(WorkflowRuntimeRepository repository, int batchSize,
                            int maxTransitionsPerSignal) {
        this.repository = Objects.requireNonNull(repository, "repository");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize must be 1..500");
        if (maxTransitionsPerSignal < 1 || maxTransitionsPerSignal > 10000)
            throw new IllegalArgumentException("maxTransitionsPerSignal must be 1..10000");
        this.batchSize = batchSize;
        this.maxTransitionsPerSignal = maxTransitionsPerSignal;
    }

    /**
     * Advance graph-control tokens to quiescence and route completed task Activations.
     * Call after Run start, after task completion, and after any event that makes a Token READY.
     */
    public AdvanceSummary advance(UUID tenantId, UUID runId, Map<String, ?> variables) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Map<String, ?> context = variables == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(variables));
        WorkflowRuntimeRepository.RunDefinitionSnapshot snapshot =
                repository.loadRunDefinitionSnapshot(tenantId, runId);
        GraphPlan plan = GraphPlanCodec.fromJson(snapshot.graphPlanJson());
        Map<String, GraphPlan.Node> nodes = indexNodes(plan);
        Map<String, List<GraphPlan.Edge>> outgoing = indexOutgoing(plan, nodes);

        int controlAdvanced = 0;
        int completedRouted = 0;
        int tokensCreated = 0;
        boolean runCompleted = false;
        int transitions = 0;

        while (transitions < maxTransitionsPerSignal) {
            boolean progressed = false;

            List<WorkflowRuntimeRepository.CompletedGraphActivation> completed =
                    repository.findCompletedGraphActivations(tenantId, runId, batchSize);
            for (WorkflowRuntimeRepository.CompletedGraphActivation activation : completed) {
                GraphPlan.Node node = requireNode(nodes, activation.nodeKey());
                List<GraphPlan.Edge> edges = outgoing.getOrDefault(node.key(), List.of());
                if (edges.isEmpty()) {
                    throw new IllegalStateException("Completed task has no outgoing edge: " + node.key());
                }
                if (edges.size() != 1) {
                    throw new IllegalStateException("Non-gateway node has multiple outgoing edges: " + node.key());
                }
                WorkflowRuntimeRepository.GraphAdvanceResult result =
                        repository.advanceCompletedGraphActivation(tenantId, runId, activation.activationId(),
                                "SEQUENCE", List.of(edges.get(0).to()));
                if ("ADVANCED".equals(result.outcome())) {
                    completedRouted++;
                    tokensCreated += result.tokensCreated();
                    runCompleted |= result.runCompleted();
                    progressed = true;
                    transitions++;
                }
                if (transitions >= maxTransitionsPerSignal) break;
            }
            if (transitions >= maxTransitionsPerSignal) break;

            List<WorkflowRuntimeRepository.GraphToken> ready =
                    repository.findReadyGraphTokens(tenantId, runId, batchSize);
            for (WorkflowRuntimeRepository.GraphToken token : ready) {
                GraphPlan.Node node = requireNode(nodes, token.nodeKey());
                Transition transition = transitionFor(node, outgoing.getOrDefault(node.key(), List.of()), context);
                if (transition == null) continue; // TASK/BUILTIN tokens wait for their execution handler.
                WorkflowRuntimeRepository.GraphAdvanceResult result =
                        repository.advanceGraphToken(tenantId, runId, token.tokenId(), token.nodeKey(),
                                transition.kind(), transition.successors(), transition.joinNodeKey());
                controlAdvanced++;
                tokensCreated += result.tokensCreated();
                runCompleted |= result.runCompleted();
                progressed = true;
                transitions++;
                if (transitions >= maxTransitionsPerSignal) break;
            }
            if (!progressed) return new AdvanceSummary(controlAdvanced, completedRouted, tokensCreated, runCompleted);
        }
        if (transitions >= maxTransitionsPerSignal)
            throw new IllegalStateException("Graph advancement limit reached; possible unbounded cycle or oversized graph");
        return new AdvanceSummary(controlAdvanced, completedRouted, tokensCreated, runCompleted);
    }

    record Transition(String kind, List<String> successors, String joinNodeKey) {
        Transition {
            successors = List.copyOf(successors);
        }
        Transition(String kind, List<String> successors) {
            this(kind,successors,null);
        }
    }

    static Transition transitionFor(GraphPlan.Node node, List<GraphPlan.Edge> edges,
                                            Map<String, ?> variables) {
        return switch (node.kind()) {
            case TASK, BUILTIN -> null;
            case START -> new Transition("SEQUENCE", exactlyOne(node, edges).stream().map(GraphPlan.Edge::to).toList());
            case END -> {
                if (!edges.isEmpty()) throw new IllegalStateException("End node has outgoing edges: " + node.key());
                yield new Transition("END", List.of());
            }
            case SPLIT_PARALLEL -> new Transition("SPLIT", parallelTargets(node, edges));
            case SPLIT_XOR -> new Transition("SEQUENCE", List.of(selectExclusive(node, edges, variables).to()));
            case SPLIT_INCLUSIVE -> new Transition("SPLIT", selectInclusive(node, edges, variables).stream()
                    .map(GraphPlan.Edge::to).toList());
            case JOIN_PARALLEL, JOIN_INCLUSIVE -> {
                exactlyOne(node, edges);
                yield new Transition("JOIN", List.of(edges.get(0).to()), node.key());
            }
            case JOIN_XOR -> new Transition("SEQUENCE", exactlyOne(node, edges).stream()
                    .map(GraphPlan.Edge::to).toList());
        };
    }

    private static List<GraphPlan.Edge> exactlyOne(GraphPlan.Node node, List<GraphPlan.Edge> edges) {
        if (edges.size() != 1)
            throw new IllegalStateException(node.kind() + " node must have exactly one outgoing edge: " + node.key());
        return edges;
    }

    private static List<String> parallelTargets(GraphPlan.Node node, List<GraphPlan.Edge> edges) {
        if (edges.size() < 2)
            throw new IllegalStateException("Parallel Split needs at least two outgoing edges: " + node.key());
        if (edges.stream().anyMatch(e -> e.condition() != null || e.defaultFlow()))
            throw new IllegalStateException("Parallel Split does not evaluate conditional/default flows: " + node.key());
        return uniqueTargets(node, edges);
    }

    static GraphPlan.Edge selectExclusive(GraphPlan.Node node, List<GraphPlan.Edge> edges,
                                                  Map<String, ?> variables) {
        if (edges.isEmpty()) throw new IllegalStateException("Exclusive Split has no outgoing edges: " + node.key());
        List<GraphPlan.Edge> matches = new ArrayList<>();
        GraphPlan.Edge defaultEdge = null;
        for (GraphPlan.Edge edge : edges) {
            if (edge.defaultFlow()) {
                if (defaultEdge != null) throw new IllegalStateException("Multiple default flows at " + node.key());
                defaultEdge = edge;
            } else if (edge.condition() == null) {
                matches.add(edge);
            } else if (RestrictedExpressionEvaluator.evaluateBoolean(edge.condition(), variables)) {
                matches.add(edge);
            }
        }
        if (matches.size() > 1)
            throw new IllegalStateException("Exclusive Split has multiple matching outgoing flows: " + node.key());
        if (matches.size() == 1) return matches.get(0);
        if (defaultEdge != null) return defaultEdge;
        throw new IllegalStateException("Exclusive Split has no matching flow and no default: " + node.key());
    }

    static List<GraphPlan.Edge> selectInclusive(GraphPlan.Node node, List<GraphPlan.Edge> edges,
                                                        Map<String, ?> variables) {
        if (edges.isEmpty()) throw new IllegalStateException("Inclusive Split has no outgoing edges: " + node.key());
        List<GraphPlan.Edge> matches = new ArrayList<>();
        GraphPlan.Edge defaultEdge = null;
        for (GraphPlan.Edge edge : edges) {
            if (edge.defaultFlow()) {
                if (defaultEdge != null) throw new IllegalStateException("Multiple default flows at " + node.key());
            } else if (edge.condition() == null
                    || RestrictedExpressionEvaluator.evaluateBoolean(edge.condition(), variables)) {
                matches.add(edge);
            }
        }
        if (matches.isEmpty()) {
            defaultEdge = edges.stream().filter(GraphPlan.Edge::defaultFlow).findFirst().orElse(null);
            if (defaultEdge == null)
                throw new IllegalStateException("Inclusive Split selected no branches and has no default: " + node.key());
            matches.add(defaultEdge);
        }
        uniqueTargets(node, matches);
        return List.copyOf(matches);
    }

    private static List<String> uniqueTargets(GraphPlan.Node node, List<GraphPlan.Edge> edges) {
        List<String> targets = edges.stream().map(GraphPlan.Edge::to).toList();
        if (targets.stream().distinct().count() != targets.size())
            throw new IllegalStateException("Gateway has duplicate branch target keys: " + node.key());
        return targets;
    }

    private static Map<String, GraphPlan.Node> indexNodes(GraphPlan plan) {
        Map<String, GraphPlan.Node> result = new HashMap<>();
        for (GraphPlan.Node node : plan.nodes()) {
            if (result.put(node.key(), node) != null)
                throw new IllegalArgumentException("Duplicate GraphPlan node key: " + node.key());
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<GraphPlan.Edge>> indexOutgoing(GraphPlan plan,
                                                                   Map<String, GraphPlan.Node> nodes) {
        Map<String, List<GraphPlan.Edge>> result = new HashMap<>();
        for (GraphPlan.Edge edge : plan.edges()) {
            if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to()))
                throw new IllegalArgumentException("GraphPlan edge references an unknown node: " + edge.key());
            result.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        }
        Map<String, List<GraphPlan.Edge>> immutable = new HashMap<>();
        result.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private static GraphPlan.Node requireNode(Map<String, GraphPlan.Node> nodes, String key) {
        GraphPlan.Node node = nodes.get(key);
        if (node == null) throw new IllegalStateException("Token references unknown GraphPlan node: " + key);
        return node;
    }
}
