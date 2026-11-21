package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.graph.RestrictedExpressionEvaluator;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Single-process graph interpreter. It advances only graph-control nodes and routes
 * completed task Attempts; TASK/BUILTIN execution remains owned by the existing Worker path.
 *
 * <p>Every state mutation is delegated to one Repository transaction. This class contains
 * no SQL, device calls, worker scheduling, thread creation or distributed coordination.
 * Callers must serialize signals for the same Run through its existing process-local SerialMailbox.
 */
public final class GraphCoordinator {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record AdvanceSummary(int controlTokensAdvanced, int completedTasksRouted,
                                 int successorTokensCreated, boolean runCompleted) {}

    private final WorkflowRuntimeRepository repository;
    private final int batchSize;
    private final int maxTransitionsPerSignal;
    private final Runnable beforeConditionalSplitTxHook;
    private final Runnable insideConditionalSplitTxAfterReadHook;

    public GraphCoordinator(WorkflowRuntimeRepository repository) {
        this(repository, 100, 1000);
    }

    public GraphCoordinator(WorkflowRuntimeRepository repository, int batchSize,
                            int maxTransitionsPerSignal) {
        this(repository, batchSize, maxTransitionsPerSignal, null, null);
    }

    GraphCoordinator(WorkflowRuntimeRepository repository, int batchSize,
                     int maxTransitionsPerSignal,
                     Runnable beforeConditionalSplitTxHook,
                     Runnable insideConditionalSplitTxAfterReadHook) {
        this.repository = Objects.requireNonNull(repository, "repository");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize must be 1..500");
        if (maxTransitionsPerSignal < 1 || maxTransitionsPerSignal > 10000)
            throw new IllegalArgumentException("maxTransitionsPerSignal must be 1..10000");
        this.batchSize = batchSize;
        this.maxTransitionsPerSignal = maxTransitionsPerSignal;
        this.beforeConditionalSplitTxHook = beforeConditionalSplitTxHook;
        this.insideConditionalSplitTxAfterReadHook = insideConditionalSplitTxAfterReadHook;
    }

    /** Advance using the Run's persisted input and flow_variable snapshot. */
    public AdvanceSummary advance(UUID tenantId, UUID runId) {
        return advance(tenantId, runId, Map.of());
    }

    /**
     * Advance graph-control tokens to quiescence and route completed task Activations.
     * Explicit variables override the persisted Run input/flow_variable values.
     */
    public AdvanceSummary advance(UUID tenantId, UUID runId, Map<String, ?> variables) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        WorkflowRuntimeRepository.RunDefinitionSnapshot snapshot =
                repository.loadRunDefinitionSnapshot(tenantId, runId);
        if (!"RUNNING".equals(snapshot.runStatus()) || !"OPEN".equals(snapshot.controlGate()))
            return new AdvanceSummary(0, 0, 0, false);

        UUID offendingTokenId = null;
        try {
            // Fail closed on corrupt persisted variable JSON even when no conditional split executes yet.
            parsePersistedVariables(repository.loadGraphVariableSnapshot(tenantId, runId));

            GraphPlan plan = GraphPlanCodec.fromJson(snapshot.graphPlanJson(), snapshot.diagnosticContext());
            Map<String, GraphPlan.Node> nodes = indexNodes(plan);
            Map<String, List<GraphPlan.Edge>> outgoing = indexOutgoing(plan, nodes);
            validatePlan(plan, nodes, outgoing);

            List<String> controlNodeKeys = plan.nodes().stream()
                    .filter(node -> node.kind() != GraphPlan.Kind.TASK && node.kind() != GraphPlan.Kind.BUILTIN)
                    .map(GraphPlan.Node::key)
                    .toList();

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
                    offendingTokenId = activation.tokenId();
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
                    offendingTokenId = null;
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

                if (!controlNodeKeys.isEmpty()) {
                    List<WorkflowRuntimeRepository.GraphToken> ready =
                            repository.findReadyGraphTokensForNodes(tenantId, runId, controlNodeKeys, batchSize);
                    for (WorkflowRuntimeRepository.GraphToken token : ready) {
                        offendingTokenId = token.tokenId();
                        GraphPlan.Node node = requireNode(nodes, token.nodeKey());
                        List<GraphPlan.Edge> nodeOutgoing = outgoing.getOrDefault(node.key(), List.of());
                        WorkflowRuntimeRepository.GraphAdvanceResult result;
                        if (node.kind() == GraphPlan.Kind.SPLIT_XOR
                                || node.kind() == GraphPlan.Kind.SPLIT_INCLUSIVE) {
                            if (beforeConditionalSplitTxHook != null) {
                                beforeConditionalSplitTxHook.run();
                            }
                            result = repository.advanceGraphTokenWithVariables(
                                    tenantId, runId, token.tokenId(), token.nodeKey(),
                                    persistedSnapshot -> {
                                        Map<String, ?> lockedContext = buildEvaluationContext(persistedSnapshot, variables);
                                        if (insideConditionalSplitTxAfterReadHook != null) {
                                            insideConditionalSplitTxAfterReadHook.run();
                                        }
                                        Transition decision = transitionFor(node, nodeOutgoing, lockedContext);
                                        if (decision == null) {
                                            throw new IllegalStateException(
                                                    "Conditional Split produced no transition: " + node.key());
                                        }
                                        return new WorkflowRuntimeRepository.GraphTokenTransitionDecision(
                                                decision.kind(), decision.successors(), decision.joinNodeKey());
                                    });
                        } else {
                            Transition transition = transitionFor(node, nodeOutgoing, Map.of());
                            if (transition == null) {
                                offendingTokenId = null;
                                continue;
                            }
                            result = repository.advanceGraphToken(
                                    tenantId, runId, token.tokenId(), token.nodeKey(),
                                    transition.kind(), transition.successors(), transition.joinNodeKey());
                        }
                        offendingTokenId = null;
                        controlAdvanced++;
                        tokensCreated += result.tokensCreated();
                        runCompleted |= result.runCompleted();
                        progressed = true;
                        transitions++;
                        if (transitions >= maxTransitionsPerSignal) break;
                    }
                }
                if (!progressed) return new AdvanceSummary(controlAdvanced, completedRouted, tokensCreated, runCompleted);
            }
            if (transitions >= maxTransitionsPerSignal)
                throw new IllegalStateException("Graph advancement limit reached; possible unbounded cycle or oversized graph");
            return new AdvanceSummary(controlAdvanced, completedRouted, tokensCreated, runCompleted);
        } catch (IllegalStateException | IllegalArgumentException
                 | WorkflowRuntimeRepository.InvalidTransitionException fault) {
            // Deterministic graph/evaluation faults must terminalize the Run to prevent poison-pill replay loops.
            repository.failGraphRun(tenantId, runId, offendingTokenId,
                    fault.getClass().getSimpleName(), fault.getMessage());
            throw fault;
        }
    }

    private static Map<String, ?> buildEvaluationContext(
            WorkflowRuntimeRepository.GraphVariableSnapshot persisted,
            Map<String, ?> explicitOverrides) {
        Map<String, Object> mergedVariables = parsePersistedVariables(persisted);
        if (explicitOverrides != null) {
            mergedVariables.putAll(explicitOverrides);
        }
        return Collections.unmodifiableMap(mergedVariables);
    }

    private static Map<String, Object> parsePersistedVariables(
            WorkflowRuntimeRepository.GraphVariableSnapshot persisted) {
        Map<String, Object> variables = new HashMap<>();
        try {
            if (persisted.inputJson() != null && !persisted.inputJson().isBlank()) {
                Map<String, Object> input = JSON.readValue(persisted.inputJson(), new TypeReference<>() {});
                if (input != null) variables.putAll(input);
            }
            for (WorkflowRuntimeRepository.GraphVariable variable : persisted.variables()) {
                variables.put(variable.key(), JSON.readValue(variable.valueJson(), Object.class));
            }
        } catch (IOException invalidJson) {
            throw new IllegalStateException("Persisted Run graph variables contain invalid JSON", invalidJson);
        }
        return variables;
    }

    record Transition(String kind, List<String> successors, String joinNodeKey) {
        Transition {
            successors = List.copyOf(successors);
        }
        Transition(String kind, List<String> successors) {
            this(kind, successors, null);
        }
    }

    static Transition transitionFor(GraphPlan.Node node, List<GraphPlan.Edge> edges,
                                    Map<String, ?> variables) {
        return switch (node.kind()) {
            case TASK, BUILTIN -> null;
            case WAIT -> throw new UnsupportedOperationException("WAIT execution is implemented in AI-3: " + node.key());
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
                defaultEdge = edge;
            } else if (edge.condition() == null
                    || RestrictedExpressionEvaluator.evaluateBoolean(edge.condition(), variables)) {
                matches.add(edge);
            }
        }
        if (matches.isEmpty()) {
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

    /** Validate the full G1 execution subset before a Run is created or advanced. */
    public static void validateGraphPlan(GraphPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, GraphPlan.Node> nodes = indexNodes(plan);
        Map<String, List<GraphPlan.Edge>> outgoing = indexOutgoing(plan, nodes);
        validatePlan(plan, nodes, outgoing);
    }

    static void validatePlan(GraphPlan plan, Map<String, GraphPlan.Node> nodes,
                             Map<String, List<GraphPlan.Edge>> outgoing) {
        long endCount = 0;
        GraphPlan.Node startNode = null;
        for (GraphPlan.Node node : plan.nodes()) {
            List<GraphPlan.Edge> edges = outgoing.getOrDefault(node.key(), List.of());
            for (GraphPlan.Edge edge : edges) {
                if (edge.defaultFlow() && edge.condition() != null)
                    throw new IllegalStateException("Default flow cannot carry a condition: " + edge.key());
                if (edge.condition() != null) RestrictedExpressionEvaluator.validateSyntax(edge.condition());
            }
            switch (node.kind()) {
                case START -> {
                    startNode = node;
                    requireUnconditional(node, exactlyOne(node, edges));
                }
                case END -> {
                    endCount++;
                    if (!edges.isEmpty()) throw new IllegalStateException("End node has outgoing edges: " + node.key());
                }
                case TASK, BUILTIN -> {
                    exactlyOne(node, edges);
                    requireUnconditional(node, edges);
                }
                case SPLIT_PARALLEL -> parallelTargets(node, edges);
                case SPLIT_XOR, SPLIT_INCLUSIVE -> {
                    if (edges.size() < 2)
                        throw new IllegalStateException("Gateway Split needs at least two outgoing edges: " + node.key());
                    uniqueTargets(node, edges);
                    long defaults = edges.stream().filter(GraphPlan.Edge::defaultFlow).count();
                    if (defaults > 1)
                        throw new IllegalStateException("Gateway has multiple default flows: " + node.key());
                }
                case JOIN_PARALLEL, JOIN_INCLUSIVE, JOIN_XOR -> requireUnconditional(node, exactlyOne(node, edges));
            }
        }
        if (endCount == 0) {
            throw new IllegalStateException("GraphPlan must contain at least one END node");
        }
        if (startNode != null && !canReachEnd(startNode.key(), nodes, outgoing)) {
            throw new IllegalStateException("START node cannot reach any END node: " + startNode.key());
        }

        // Validate synchronizing Split/Join pairing, disallow nested synchronizing splits,
        // disallow mixed End/Join or multi-Join convergence, and enforce Split/Join kind matching.
        Map<String, String> joinOwnerByJoinKey = new HashMap<>();
        for (GraphPlan.Node node : plan.nodes()) {
            if (node.kind() != GraphPlan.Kind.SPLIT_PARALLEL && node.kind() != GraphPlan.Kind.SPLIT_INCLUSIVE) continue;
            boolean splitReachesEnd = false;
            Set<String> splitJoins = new HashSet<>();
            for (GraphPlan.Edge edge : outgoing.getOrDefault(node.key(), List.of())) {
                BranchReachability branch = analyzeSynchronizingBranch(edge.to(), nodes, outgoing);
                if (!branch.reachesEnd && branch.joins.isEmpty()) {
                    throw new IllegalStateException("Synchronizing Split branch cannot reach any Join or End: " + node.key());
                }
                splitReachesEnd |= branch.reachesEnd;
                splitJoins.addAll(branch.joins);
            }
            if (splitReachesEnd && !splitJoins.isEmpty()) {
                throw new IllegalStateException("Synchronizing Split cannot mix terminal End branches with Join: " + node.key());
            }
            if (splitJoins.size() > 1) {
                throw new IllegalStateException("Branches of one Split cannot converge into different Join gateways: " + node.key());
            }
            if (splitJoins.size() == 1) {
                String joinKey = splitJoins.iterator().next();
                GraphPlan.Node joinNode = requireNode(nodes, joinKey);
                GraphPlan.Kind expectedJoinKind = node.kind() == GraphPlan.Kind.SPLIT_PARALLEL
                        ? GraphPlan.Kind.JOIN_PARALLEL
                        : GraphPlan.Kind.JOIN_INCLUSIVE;
                if (joinNode.kind() != expectedJoinKind) {
                    throw new IllegalStateException("Split/Join gateway kind mismatch: " + node.key() + " -> " + joinKey);
                }
                String previousOwner = joinOwnerByJoinKey.putIfAbsent(joinKey, node.key());
                if (previousOwner != null) {
                    throw new IllegalStateException("Synchronizing Join is shared by multiple Splits: " + joinKey);
                }
            }
        }

        // Every synchronizing Join must have a unique owning Split and must not be reachable
        // from START via any path that bypasses its owning Split.
        for (GraphPlan.Node node : plan.nodes()) {
            if (node.kind() != GraphPlan.Kind.JOIN_PARALLEL && node.kind() != GraphPlan.Kind.JOIN_INCLUSIVE) continue;
            String ownerSplit = joinOwnerByJoinKey.get(node.key());
            if (ownerSplit == null) {
                throw new IllegalStateException("Synchronizing Join has no matching Split gateway: " + node.key());
            }
            if (startNode != null && canReachWithoutPassing(startNode.key(), node.key(), ownerSplit, outgoing)) {
                throw new IllegalStateException("Synchronizing Join is reachable without passing its owning Split: " + node.key());
            }
        }
    }

    private static List<GraphPlan.Edge> requireUnconditional(GraphPlan.Node node, List<GraphPlan.Edge> edges) {
        if (edges.stream().anyMatch(edge -> edge.condition() != null || edge.defaultFlow()))
            throw new IllegalStateException("Conditional/default outgoing flows are not supported on " + node.kind() + ": " + node.key());
        return edges;
    }

    private static final class BranchReachability {
        boolean reachesEnd;
        final Set<String> joins = new HashSet<>();
    }

    private static BranchReachability analyzeSynchronizingBranch(String startKey,
                                                                 Map<String, GraphPlan.Node> nodes,
                                                                 Map<String, List<GraphPlan.Edge>> outgoing) {
        BranchReachability reachability = new BranchReachability();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(startKey);
        while (!stack.isEmpty()) {
            String currentKey = stack.pop();
            if (!visited.add(currentKey)) continue;
            GraphPlan.Node node = requireNode(nodes, currentKey);
            if (node.kind() == GraphPlan.Kind.END) {
                reachability.reachesEnd = true;
                continue;
            }
            if (node.kind() == GraphPlan.Kind.JOIN_PARALLEL || node.kind() == GraphPlan.Kind.JOIN_INCLUSIVE) {
                reachability.joins.add(node.key());
                continue;
            }
            if (node.kind() == GraphPlan.Kind.SPLIT_PARALLEL || node.kind() == GraphPlan.Kind.SPLIT_INCLUSIVE) {
                throw new IllegalStateException("Nested synchronizing Split is not supported in G1: " + node.key());
            }
            for (GraphPlan.Edge edge : outgoing.getOrDefault(currentKey, List.of())) {
                stack.push(edge.to());
            }
        }
        return reachability;
    }

    private static boolean canReachEnd(String startKey,
                                       Map<String, GraphPlan.Node> nodes,
                                       Map<String, List<GraphPlan.Edge>> outgoing) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(startKey);
        while (!stack.isEmpty()) {
            String key = stack.pop();
            if (!visited.add(key)) continue;
            GraphPlan.Node node = requireNode(nodes, key);
            if (node.kind() == GraphPlan.Kind.END) return true;
            for (GraphPlan.Edge edge : outgoing.getOrDefault(key, List.of())) {
                stack.push(edge.to());
            }
        }
        return false;
    }

    private static boolean canReachWithoutPassing(String startKey, String targetKey, String blockedKey,
                                                  Map<String, List<GraphPlan.Edge>> outgoing) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(startKey);
        while (!stack.isEmpty()) {
            String key = stack.pop();
            if (key.equals(blockedKey)) continue;
            if (key.equals(targetKey)) return true;
            if (!visited.add(key)) continue;
            for (GraphPlan.Edge edge : outgoing.getOrDefault(key, List.of())) {
                stack.push(edge.to());
            }
        }
        return false;
    }

    private static GraphPlan.Node requireNode(Map<String, GraphPlan.Node> nodes, String key) {
        GraphPlan.Node node = nodes.get(key);
        if (node == null) throw new IllegalStateException("Token references unknown GraphPlan node: " + key);
        return node;
    }
}
